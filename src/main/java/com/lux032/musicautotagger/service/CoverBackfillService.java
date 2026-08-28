package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.model.MusicMetadata;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 历史记录封面回填。
 *
 * 背景：release_group_id 这一列是后加的，之前处理过的文件都没有这个值，
 * 而仪表板的封面缩略图正是靠它去封面缓存里取图，所以老记录只能显示占位图。
 *
 * 做法：按**专辑**（不是按文件）分组，拿专辑名 + 艺术家去 MusicBrainz 搜一次，
 * 命中就把该专辑下所有缺失的记录一次性补上，顺带把封面预热进缓存。
 * 这样请求数是「专辑数」而不是「文件数」，几百个文件通常只有几十次请求。
 *
 * 整个过程是纯补充操作：只写 release_group_id 这一个字段，不动音频文件，
 * 也不覆盖任何已有的值，随时可以中断，重跑一次只会处理剩下没补上的。
 */
@Slf4j
public class CoverBackfillService {

    /** 低于这个匹配分数的搜索结果直接丢弃，宁可不补也不要补错专辑。 */
    private static final int MIN_SCORE = 80;

    private final ProcessedFileLogger processedLogger;
    private final MusicBrainzClient musicBrainzClient;
    private final CoverArtService coverArtService;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cover-backfill");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    // 进度快照，只在回填线程里写，读的时候允许有一点点滞后
    private volatile int totalAlbums = 0;
    private volatile int processedAlbums = 0;
    private volatile int matchedAlbums = 0;
    private volatile int updatedFiles = 0;
    private volatile int cachedCovers = 0;
    private volatile String currentAlbum = null;
    private volatile String lastMessage = null;
    private volatile long startedAt = 0;
    private volatile long finishedAt = 0;

    public CoverBackfillService(ProcessedFileLogger processedLogger,
                                MusicBrainzClient musicBrainzClient,
                                CoverArtService coverArtService) {
        this.processedLogger = processedLogger;
        this.musicBrainzClient = musicBrainzClient;
        this.coverArtService = coverArtService;
    }

    /** 待回填的专辑数量，用于在设置面板里显示「有多少可补」。 */
    public int countPendingAlbums() {
        try {
            return processedLogger.findAlbumsMissingReleaseGroupId().size();
        } catch (Exception e) {
            log.warn("统计待回填专辑失败: {}", e.getMessage());
            return 0;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * 启动回填。已经在跑就直接返回 false，不排队也不并发。
     */
    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        cancelRequested.set(false);
        totalAlbums = 0;
        processedAlbums = 0;
        matchedAlbums = 0;
        updatedFiles = 0;
        cachedCovers = 0;
        currentAlbum = null;
        lastMessage = null;
        startedAt = System.currentTimeMillis();
        finishedAt = 0;

        executor.submit(() -> {
            try {
                runBackfill();
            } catch (Exception e) {
                log.error("封面回填异常终止", e);
                lastMessage = "异常终止: " + e.getMessage();
            } finally {
                currentAlbum = null;
                finishedAt = System.currentTimeMillis();
                running.set(false);
            }
        });
        return true;
    }

    /** 请求停止；当前这张专辑处理完就退出。 */
    public void cancel() {
        cancelRequested.set(true);
    }

    private void runBackfill() {
        List<ProcessedFileLogger.AlbumGroup> groups = processedLogger.findAlbumsMissingReleaseGroupId();
        totalAlbums = groups.size();
        log.info("开始封面回填，待处理专辑 {} 张", totalAlbums);

        if (groups.isEmpty()) {
            lastMessage = "没有需要回填的记录";
            return;
        }

        for (ProcessedFileLogger.AlbumGroup group : groups) {
            if (cancelRequested.get()) {
                lastMessage = "已取消";
                log.info("封面回填被取消，已处理 {}/{}", processedAlbums, totalAlbums);
                return;
            }

            currentAlbum = group.album;
            try {
                String releaseGroupId = resolveReleaseGroupId(group);
                if (releaseGroupId == null) {
                    log.debug("未匹配到专辑: {}", group.album);
                } else {
                    int updated = processedLogger.applyReleaseGroupId(group.album, releaseGroupId);
                    if (updated > 0) {
                        matchedAlbums++;
                        updatedFiles += updated;
                    }
                    // 预热封面缓存：命中缓存就是本地读取，没有才会去下载
                    if (coverArtService != null && warmCover(releaseGroupId)) {
                        cachedCovers++;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastMessage = "已中断";
                return;
            } catch (Exception e) {
                // 单张专辑失败不影响整体，继续下一张
                log.warn("回填专辑失败 [{}]: {}", group.album, e.getMessage());
            } finally {
                processedAlbums++;
            }
        }

        currentAlbum = null;
        lastMessage = String.format("完成：匹配 %d/%d 张专辑，补全 %d 条记录，缓存封面 %d 张",
            matchedAlbums, totalAlbums, updatedFiles, cachedCovers);
        log.info("封面回填完成 - {}", lastMessage);
    }

    /**
     * 用专辑名（+ 艺术家）搜 MusicBrainz，挑一个足够可信的 Release Group。
     * 宁缺毋滥：分数不够或标题对不上就返回 null，避免给历史记录贴错封面。
     */
    private String resolveReleaseGroupId(ProcessedFileLogger.AlbumGroup group) throws Exception {
        List<MusicMetadata> results = musicBrainzClient.searchAlbum(group.album, group.artist);
        if (results == null || results.isEmpty()) {
            return null;
        }

        String wanted = normalize(group.album);
        MusicMetadata best = null;
        for (MusicMetadata candidate : results) {
            if (candidate.getReleaseGroupId() == null || candidate.getReleaseGroupId().isBlank()) {
                continue;
            }
            if (candidate.getScore() < MIN_SCORE) {
                continue;
            }
            if (!normalize(candidate.getAlbum()).equals(wanted)) {
                continue;
            }
            if (best == null || candidate.getScore() > best.getScore()) {
                best = candidate;
            }
        }
        return best == null ? null : best.getReleaseGroupId();
    }

    private boolean warmCover(String releaseGroupId) {
        try {
            byte[] data = coverArtService.getCoverArtByReleaseGroupId(releaseGroupId, null);
            return data != null && data.length > 0;
        } catch (Exception e) {
            log.debug("预热封面失败 (rgid={}): {}", releaseGroupId, e.getMessage());
            return false;
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase()
            .replaceAll("[\\s\\p{Punct}]+", "")
            .trim();
    }

    /** 供 API 输出的进度快照。 */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("running", running.get());
        status.put("totalAlbums", totalAlbums);
        status.put("processedAlbums", processedAlbums);
        status.put("matchedAlbums", matchedAlbums);
        status.put("updatedFiles", updatedFiles);
        status.put("cachedCovers", cachedCovers);
        status.put("currentAlbum", currentAlbum);
        status.put("message", lastMessage);
        status.put("startedAt", startedAt);
        status.put("finishedAt", finishedAt);
        return status;
    }

    public void shutdown() {
        cancelRequested.set(true);
        executor.shutdownNow();
    }
}
