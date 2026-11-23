package com.lux032.musicautotagger.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewDiagnosis;
import com.lux032.musicautotagger.model.ReviewItem;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 待人工确认队列（阶段六 #18）
 *
 * 为什么必须落盘：
 * `FolderAlbumCache` 全部是内存 ConcurrentHashMap，重启即失。
 * 而人工确认天然是跨重启的动作——用户不可能在进程活着的那几分钟内恰好点开面板。
 *
 * 存储格式选用 JSON（而不是复用 ProcessedFileLogger 的 CSV/MySQL）：
 * 条目是嵌套结构（候选列表、文件列表、时长序列、每文件的曲目级元数据），
 * CSV 表达不了，而 MySQL 是可选依赖（file 模式下压根没有数据库）。
 *
 * 重要约束：入队时文件**不能**被写标签 / 改名 / 移动。
 * 仅有的例外是格式规范化产生的转码临时文件——它原本在系统临时目录里，
 * 长期挂起会被系统清理掉，因此入队时挪到暂存目录（stagingDir）。
 */
@Slf4j
public class ReviewQueueService {

    private static final Type ITEM_LIST_TYPE = new TypeToken<List<ReviewItem>>() {
    }.getType();

    private final MusicConfig config;
    private final Path storeFile;
    private final Path stagingRoot;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** id -> item，保持插入顺序；查询时按入队时间倒序，时间相同时后入队的优先。 */
    private final Map<String, ReviewItem> items = new LinkedHashMap<>();

    public ReviewQueueService(MusicConfig config) {
        this.config = config;
        this.storeFile = resolveStoreFile(config);
        this.stagingRoot = resolveStagingRoot(config);
        load();
    }

    private static Path resolveStoreFile(MusicConfig config) {
        String configured = config.getReviewQueuePath();
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured.trim());
        }
        return Paths.get("data", "review-queue.json");
    }

    private static Path resolveStagingRoot(MusicConfig config) {
        String configured = config.getReviewStagingDirectory();
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured.trim());
        }
        return Paths.get("data", "review-staging");
    }

    public Path getStagingRoot() {
        return stagingRoot.toAbsolutePath().normalize();
    }

    // ==================== 查询 ====================

    /** 返回新入队优先的列表，不以更新时间排序，避免搜索或处置让旧条目跳到顶部。 */
    public synchronized List<ReviewItem> list(ReviewItem.Status statusFilter) {
        return listByKinds(statusFilter, null);
    }

    /** 按状态和条目类型集合过滤列表（新入队优先） */
    public synchronized List<ReviewItem> listByKinds(ReviewItem.Status statusFilter,
                                                     java.util.Set<ReviewItem.Kind> kinds) {
        List<ReviewItem> result = new ArrayList<>();
        for (ReviewItem item : items.values()) {
            if ((statusFilter == null || statusFilter == item.getStatus())
                && (kinds == null || kinds.contains(item.effectiveKind()))) {
                result.add(item);
            }
        }
        Collections.reverse(result);
        result.sort(Comparator.comparingLong(ReviewItem::getCreatedAt).reversed());
        return result;
    }

    /**
     * 条目是否命中搜索词（不区分大小写的子串匹配）。
     *
     * 用户手里通常只有一个文件名或歌名，而队列是按文件夹组织的，
     * 所以除了文件夹名 / 路径，还要匹配条目里的文件名、指纹曲名、艺术家和合成专辑名。
     *
     * @return null 表示未命中；空串表示文件夹本身命中；
     *         其余为命中的文件名（面板用它告诉用户「是这个文件夹里的哪个文件」）
     */
    public static String matchQuery(ReviewItem item, String query) {
        if (item == null) {
            return null;
        }
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) {
            return "";
        }
        if (containsIgnoreCase(item.getFolderName(), q) || containsIgnoreCase(item.getFolderPath(), q)
            || containsIgnoreCase(item.getSynthesizedAlbumTitle(), q)
            || containsIgnoreCase(item.getSynthesizedAlbumArtist(), q)
            || (item.getMissingCover() != null && (
                containsIgnoreCase(item.getMissingCover().getAlbumTitle(), q)
                || containsIgnoreCase(item.getMissingCover().getAlbumArtist(), q)))) {
            return "";
        }
        if (item.getFiles() != null) {
            for (ReviewItem.FileEntry file : item.getFiles()) {
                if (file == null) continue;
                MusicMetadata md = file.getMetadata();
                if (containsIgnoreCase(file.getFileName(), q)
                    || (md != null && (containsIgnoreCase(md.getTitle(), q) || containsIgnoreCase(md.getArtist(), q)))) {
                    String name = file.getFileName();
                    if (name == null || name.isEmpty()) {
                        name = file.getOriginalPath() == null ? "" : new File(file.getOriginalPath()).getName();
                    }
                    String title = md == null ? null : md.getTitle();
                    // 文件名本身没命中时带上曲名，否则用户看不出为什么匹配到这个文件
                    return !containsIgnoreCase(name, q) && title != null && !title.isBlank()
                        ? name + " · " + title : name;
                }
            }
        }
        return null;
    }

    private static boolean containsIgnoreCase(String text, String lowerQuery) {
        return text != null && text.toLowerCase(java.util.Locale.ROOT).contains(lowerQuery);
    }

    public synchronized ReviewItem get(String id) {
        return id == null ? null : items.get(id);
    }

    /**
     * 为恢复目录创建一个不修改文件的待确认条目，供原生联网搜索写入候选。
     *
     * 如果同一目录已经有普通（MusicBrainz）待确认条目，只把联网证据附加上去，
     * <b>绝不触碰它的 files</b>：那些 FileEntry 里可能带着规范化转码后的 stagedPath，
     * 以及指纹识别得到的曲目级元数据与候选的对应关系，
     * 覆盖后人工确认阶段会去处理错误的一批文件。
     */
    public synchronized ReviewItem enqueueRecoveryFolder(String folderPath, String sourceType,
                                                         List<File> audioFiles,
                                                         TagWriterService tagWriter,
                                                         String evidenceHash) {
        ReviewItem existing = findPendingByFolder(folderPath);
        ReviewItem item = existing != null ? existing : new ReviewItem();
        boolean isNewItem = existing == null;

        // 只有新建条目、或本来就由恢复流程创建的条目，才归恢复流程所有
        boolean recoveryOwned = isNewItem || existing.getRecoverySourceType() != null;

        if (isNewItem) {
            item.setId(UUID.randomUUID().toString());
            item.setCreatedAt(System.currentTimeMillis());
            item.setFolderPath(folderPath);
            item.setFolderName(new File(folderPath).getName());
            item.setStatus(ReviewItem.Status.PENDING_REVIEW);
            item.setReason("联网辅助识别");
            item.setReasonCode(ReviewDiagnosis.ONLINE_ASSIST);
            items.put(item.getId(), item);
        } else if (!recoveryOwned) {
            String reason = existing.getReason();
            if (reason == null || !reason.contains("联网辅助识别")) {
                existing.setReason((reason == null || reason.isBlank() ? "" : reason + " + ") + "联网辅助识别（仅参考）");
            }
            log.info("目录已有普通待确认条目，联网候选仅作为参考附加，不覆盖已有文件列表: {}", folderPath);
        }

        item.setEvidenceHash(evidenceHash);
        item.setOnlineEvidenceStale(false);
        item.setUpdatedAt(System.currentTimeMillis());

        // 普通 review 条目触发的联网搜索不归属 recovery 流程，不能被 SourceType.valueOf
        // 误判，也不能被回收站逻辑当成物理恢复副本处理。
        if (recoveryOwned && sourceType != null) {
            item.setRecoverySourceType(sourceType);
            item.setRecoverySourcePath(folderPath);
        }

        // 新条目仍需建立文件快照；已有条目必须保留原有 files（其中可能包含 stagedPath）。
        if (isNewItem) {
            List<ReviewItem.FileEntry> entries = new ArrayList<>();
            for (File audio : audioFiles) {
                ReviewItem.FileEntry entry = new ReviewItem.FileEntry();
                entry.setOriginalPath(audio.getAbsolutePath());
                entry.setFileName(audio.getName());
                MusicMetadata metadata = tagWriter == null ? null : tagWriter.readTags(audio);
                entry.setMetadata(metadata == null ? new MusicMetadata() : stripHeavyFields(metadata));
                if (metadata != null) entry.setDuration(metadata.getDuration());
                entries.add(entry);
            }
            item.setFiles(entries);
        }
        saveQuietly();
        return item;
    }

    /**
     * 为已有条目准备联网搜索：只有条目仍处于待确认、且文件夹一致时才返回，绝不新建条目。
     * 后台自动搜索必须走这里：搜索排队期间人工可能已经确认 / 忽略，
     * 按文件夹查找会新建一个普通条目，把已处理好的文件夹重新拦住。
     */
    public synchronized ReviewItem prepareOnlineSearchForItem(String itemId, String folderPath, String evidenceHash) {
        ReviewItem item = itemId == null ? null : items.get(itemId);
        if (item == null || item.getStatus() != ReviewItem.Status.PENDING_REVIEW
            || folderPath == null || !folderPath.equals(item.getFolderPath())) {
            return null;
        }
        item.setEvidenceHash(evidenceHash);
        item.setOnlineEvidenceStale(false);
        item.setUpdatedAt(System.currentTimeMillis());
        saveQuietly();
        return item;
    }

    /**
     * 在队列锁内检查条目仍待确认，再应用修改并落盘。
     * 后台流程（联网搜索返回、自动搜索标记）修改共享条目必须走这里：
     * 锁外先检查再修改，中间人工确认的话，迟到的结果会写进已确认的条目。
     *
     * @param mutation 返回 false 表示放弃修改（不落盘）
     * @return 是否已修改并落盘
     */
    public synchronized boolean updatePending(String itemId, java.util.function.Predicate<ReviewItem> mutation) {
        ReviewItem item = itemId == null ? null : items.get(itemId);
        if (item == null || item.getStatus() != ReviewItem.Status.PENDING_REVIEW) {
            return false;
        }
        if (!mutation.test(item)) {
            return false;
        }
        update(item);
        return true;
    }

    /** 仅当条目仍待确认时才持久化（联网搜索返回时用）；返回是否写入 */
    public synchronized boolean updateIfPending(ReviewItem item) {
        if (item == null || item.getStatus() != ReviewItem.Status.PENDING_REVIEW
            || !items.containsKey(item.getId())) {
            return false;
        }
        update(item);
        return true;
    }

    /** 待确认、尚未自动搜索过的缺曲条目（启动时补跑用） */
    public synchronized List<ReviewItem> pendingMissingTracksWithoutAutoSearch() {
        List<ReviewItem> result = new ArrayList<>();
        for (ReviewItem item : items.values()) {
            if (item.getStatus() == ReviewItem.Status.PENDING_REVIEW
                && item.effectiveKind() == ReviewItem.Kind.TRACKS_MISSING
                && !item.isAutoOnlineSearchTriggered() && item.getOnlineSearchedAt() <= 0L) {
                result.add(item);
            }
        }
        return result;
    }

    /** 待处理、尚未自动搜过封面的「缺少封面」拦截条目（启动时补跑用，仅限新入库拦截模式） */
    public synchronized List<ReviewItem> pendingMissingCoverWithoutAutoSearch() {
        List<ReviewItem> result = new ArrayList<>();
        for (ReviewItem item : items.values()) {
            if (item.getStatus() == ReviewItem.Status.PENDING_REVIEW
                && item.effectiveKind() == ReviewItem.Kind.COVER_MISSING
                && item.getMissingCover() != null
                && item.getMissingCover().getMode() == ReviewItem.MissingCover.Mode.STAGED_IMPORT
                && !item.getMissingCover().isCoverAutoSearchTriggered()
                && item.getMissingCover().getCoverSearchedAt() <= 0L) {
                result.add(item);
            }
        }
        return result;
    }

    public synchronized int countPending() {
        int count = 0;
        for (ReviewItem item : items.values()) {
            if (item.getStatus() == ReviewItem.Status.PENDING_REVIEW) {
                count++;
            }
        }
        return count;
    }

    /** 「待确认」页面统计（不含「缺少封面」） */
    public synchronized int countPendingNonCover() {
        int count = 0;
        for (ReviewItem item : items.values()) {
            if (item.getStatus() == ReviewItem.Status.PENDING_REVIEW
                && item.effectiveKind() != ReviewItem.Kind.COVER_MISSING) {
                count++;
            }
        }
        return count;
    }

    /** 「缺少封面」页面待处理数量 */
    public synchronized int countPendingMissingCover() {
        return countPending(ReviewItem.Kind.COVER_MISSING);
    }

    /** 按条目类型统计待确认数量（面板 Tab 角标） */
    public synchronized int countPending(ReviewItem.Kind kind) {
        int count = 0;
        for (ReviewItem item : items.values()) {
            if (item.getStatus() == ReviewItem.Status.PENDING_REVIEW && item.effectiveKind() == kind) {
                count++;
            }
        }
        return count;
    }

    public synchronized int countRejected() {
        int count = 0;
        for (ReviewItem item : items.values()) {
            if (item.getStatus() == ReviewItem.Status.REJECTED) {
                count++;
            }
        }
        return count;
    }

    public synchronized int countRejectedNonCover() {
        int count = 0;
        for (ReviewItem item : items.values()) {
            if (item.getStatus() == ReviewItem.Status.REJECTED
                && item.effectiveKind() != ReviewItem.Kind.COVER_MISSING) {
                count++;
            }
        }
        return count;
    }

    public synchronized int countRejectedMissingCover() {
        int count = 0;
        for (ReviewItem item : items.values()) {
            if (item.getStatus() == ReviewItem.Status.REJECTED
                && item.effectiveKind() == ReviewItem.Kind.COVER_MISSING) {
                count++;
            }
        }
        return count;
    }

    /** 库内已归档专辑是否曾被用户明确确认「保持无封面」（手动扫描音乐库时跳过） */
    public synchronized boolean isLibraryFolderConfirmedNoCover(String folderPath) {
        if (folderPath == null) return false;
        for (ReviewItem item : items.values()) {
            if (item.effectiveKind() == ReviewItem.Kind.COVER_MISSING
                && item.getStatus() == ReviewItem.Status.CONFIRMED
                && folderPath.equals(item.getFolderPath())
                && item.getMissingCover() != null
                && item.getMissingCover().isConfirmedNoCover()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 撤销忽略前检查同一目录是否已有另一条待确认记录。
     * REJECTED 期间目录可能因新增文件再次入队；若直接恢复旧条目，会让两个条目争用同一批原始文件。
     */
    public synchronized boolean hasOtherPendingForFolder(String folderPath, String excludeId) {
        if (folderPath == null) {
            return false;
        }
        for (ReviewItem item : items.values()) {
            if (item.getStatus() == ReviewItem.Status.PENDING_REVIEW
                && folderPath.equals(item.getFolderPath())
                && (excludeId == null || !excludeId.equals(item.getId()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 该文件夹是否正处于「等待人工确认」状态。
     *
     * 调用方（AudioFileProcessorService）必须用它拦住自动处理：
     * 待确认的文件不会被标记为 processed，下一轮扫描仍会看到它们。
     */
    public synchronized boolean isFolderUnderReview(String folderPath) {
        if (folderPath == null) {
            return false;
        }
        for (ReviewItem item : items.values()) {
            if (item.getStatus() == ReviewItem.Status.PENDING_REVIEW
                && folderPath.equals(item.getFolderPath())) {
                return true;
            }
        }
        return false;
    }

    // ==================== 入队 ====================

    /**
     * 把一个「曲目认出来了、但专辑定不下来」的文件夹放入待确认队列。
     *
     * @param pendingFiles      当前 pending 队列中的文件（不会被写入 / 移动）
     * @param synthesized       合成的专辑信息（人工选择「按未收录归档」时使用）
     * @param candidates        候选专辑快照（Release Group 级，来自 AcoustID，零额外 API 调用）
     * @param durationSequence  文件夹时长序列
     * @return 新建或已存在的条目
     */
    public synchronized ReviewItem enqueue(String folderPath,
                                           List<FolderAlbumCache.PendingFile> pendingFiles,
                                           FolderAlbumCache.CachedAlbumInfo synthesized,
                                           List<FolderAlbumCache.FolderCandidate> candidates,
                                           List<Integer> durationSequence,
                                           String reason,
                                           double confidence) {
        return enqueue(folderPath, pendingFiles, synthesized, candidates, durationSequence,
            reason, confidence, null);
    }

    /**
     * @param diagnosis 判定点记录的结构化原因；为 null 时按「专辑未能确定」兜底
     */
    public synchronized ReviewItem enqueue(String folderPath,
                                           List<FolderAlbumCache.PendingFile> pendingFiles,
                                           FolderAlbumCache.CachedAlbumInfo synthesized,
                                           List<FolderAlbumCache.FolderCandidate> candidates,
                                           List<Integer> durationSequence,
                                           String reason,
                                           double confidence,
                                           ReviewDiagnosis diagnosis) {
        return enqueueInternal(folderPath, pendingFiles, synthesized, candidates, durationSequence,
            reason, confidence, item -> {
                item.setKind(ReviewItem.Kind.ALBUM_UNRESOLVED);
                applyDiagnosis(item, diagnosis != null
                    ? diagnosis : ReviewDiagnosis.of(ReviewDiagnosis.ALBUM_UNDETERMINED));
            });
    }

    private static void applyDiagnosis(ReviewItem item, ReviewDiagnosis diagnosis) {
        item.setReasonCode(diagnosis.getCode());
        item.setReasonFacts(diagnosis.getFacts());
    }

    /** 缺曲条目入队后的回调（用于自动联网搜索），在队列锁外异步执行更安全 */
    private volatile java.util.function.Consumer<ReviewItem> missingTracksEnqueuedListener;

    /** 缺少封面条目入队后的回调（用于自动搜封面），在队列锁外异步执行更安全 */
    private volatile java.util.function.Consumer<ReviewItem> missingCoverEnqueuedListener;

    public void setMissingTracksEnqueuedListener(java.util.function.Consumer<ReviewItem> listener) {
        this.missingTracksEnqueuedListener = listener;
    }

    public void setMissingCoverEnqueuedListener(java.util.function.Consumer<ReviewItem> listener) {
        this.missingCoverEnqueuedListener = listener;
    }

    /**
     * 「缺少封面」入队（新入库拦截：自动刮削 / 散落单文件 / 重新识别）。
     */
    public ReviewItem enqueueMissingCover(String folderPath,
                                          List<FolderAlbumCache.PendingFile> pendingFiles,
                                          ReviewItem.MissingCover details,
                                          String reason) {
        if (details == null) {
            return null;
        }
        String effectiveReason = (reason == null || reason.isBlank())
            ? "缺少封面，等待补全封面或人工确认保持无封面入库" : reason;
        FolderAlbumCache.CachedAlbumInfo synthesized = new FolderAlbumCache.CachedAlbumInfo(
            details.getReleaseGroupId(),
            details.getReleaseId(),
            details.getAlbumTitle(),
            details.getAlbumArtist(),
            pendingFiles == null ? 0 : pendingFiles.size(),
            details.getReleaseDate() != null ? details.getReleaseDate() : "",
            details.getReleaseType(),
            details.isCompilation(),
            1.0,
            FolderAlbumCache.CacheSource.UNKNOWN
        );
        ReviewItem item;
        synchronized (this) {
            item = enqueueInternal(folderPath, pendingFiles, synthesized, null, null,
                effectiveReason, 1.0, target -> {
                    target.setKind(ReviewItem.Kind.COVER_MISSING);
                    applyDiagnosis(target, ReviewDiagnosis.of(ReviewDiagnosis.COVER_MISSING));
                    if (details.getMode() == null) {
                        details.setMode(ReviewItem.MissingCover.Mode.STAGED_IMPORT);
                    }
                    if (details.getCoverSearchStatus() == null) {
                        details.setCoverSearchStatus("IDLE");
                    }
                    target.setMissingCover(details);
                });
        }
        notifyMissingCoverEnqueued(item);
        return item;
    }

    /**
     * 把现有的待确认条目（选定 MB 版本 / 按未收录归档 / 联网候选 / 本地标签入库）原位流转为「缺少封面」。
     * 保留原有的 stagingDir、recoverySourceType、recoverySourcePath、files 等全部上下文。
     */
    public ReviewItem transitionToMissingCover(ReviewItem existing,
                                               ReviewItem.MissingCover details,
                                               String reason) {
        if (existing == null || details == null) {
            return null;
        }
        ReviewItem updated;
        synchronized (this) {
            ReviewItem item = items.get(existing.getId());
            if (item == null) {
                item = existing;
                items.put(item.getId(), item);
            }
            item.setKind(ReviewItem.Kind.COVER_MISSING);
            item.setStatus(ReviewItem.Status.PENDING_REVIEW);
            applyDiagnosis(item, ReviewDiagnosis.of(ReviewDiagnosis.COVER_MISSING));
            item.setReason((reason == null || reason.isBlank())
                ? "已确认元数据但缺少封面，等待补全或确认无封面入库" : reason);
            if (details.getAlbumTitle() != null && !details.getAlbumTitle().isBlank()) {
                item.setSynthesizedAlbumTitle(details.getAlbumTitle());
            }
            if (details.getAlbumArtist() != null && !details.getAlbumArtist().isBlank()) {
                item.setSynthesizedAlbumArtist(details.getAlbumArtist());
            }
            if (details.getMode() == null) {
                details.setMode(ReviewItem.MissingCover.Mode.STAGED_IMPORT);
            }
            if (details.getCoverSearchStatus() == null) {
                details.setCoverSearchStatus("IDLE");
            }
            item.setMissingCover(details);
            item.setResolutionNote(null);
            item.setUpdatedAt(System.currentTimeMillis());
            saveQuietly();
            updated = item;
        }
        log.warn("缺少封面，条目已转入「缺少封面」等待处理: {} ({})",
            updated.getFolderName(), details.getAlbumTitle());
        LogCollector.addLog("WARN", "缺少封面，已转入「缺少封面」等待补图: " + updated.getFolderName());
        notifyMissingCoverEnqueued(updated);
        return updated;
    }

    /**
     * 手动扫描输出音乐库发现的已归档缺封面专辑入队（不自动触发 AI 搜图，由用户在页面手动或批量触发）。
     */
    public synchronized ReviewItem enqueueLibraryMissingCover(String folderPath,
                                                              List<File> audioFiles,
                                                              TagWriterService tagWriter,
                                                              ReviewItem.MissingCover details) {
        if (folderPath == null || audioFiles == null || audioFiles.isEmpty() || details == null) {
            return null;
        }
        ReviewItem existing = findPendingByFolder(folderPath);
        if (existing != null) {
            return existing;
        }
        ReviewItem item = new ReviewItem();
        item.setId(UUID.randomUUID().toString());
        long now = System.currentTimeMillis();
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        item.setFolderPath(folderPath);
        item.setFolderName(new File(folderPath).getName());
        item.setStatus(ReviewItem.Status.PENDING_REVIEW);
        item.setKind(ReviewItem.Kind.COVER_MISSING);
        applyDiagnosis(item, ReviewDiagnosis.of(ReviewDiagnosis.COVER_MISSING));
        item.setReason("扫描音乐库发现缺少内嵌封面");
        item.setConfidence(1.0);
        item.setSynthesizedAlbumTitle(details.getAlbumTitle());
        item.setSynthesizedAlbumArtist(details.getAlbumArtist());
        details.setMode(ReviewItem.MissingCover.Mode.LIBRARY_EXISTING);
        details.setSourcePipeline("LIBRARY_SCAN");
        if (details.getCoverSearchStatus() == null) {
            details.setCoverSearchStatus("IDLE");
        }
        item.setMissingCover(details);

        List<ReviewItem.FileEntry> entries = new ArrayList<>();
        for (File audio : audioFiles) {
            ReviewItem.FileEntry entry = new ReviewItem.FileEntry();
            entry.setOriginalPath(audio.getAbsolutePath());
            entry.setFileName(audio.getName());
            MusicMetadata md = tagWriter == null ? null : tagWriter.readTags(audio);
            entry.setMetadata(md == null ? new MusicMetadata() : stripHeavyFields(md));
            if (md != null) entry.setDuration(md.getDuration());
            entries.add(entry);
        }
        item.setFiles(entries);
        items.put(item.getId(), item);
        saveQuietly();
        return item;
    }

    private void notifyMissingCoverEnqueued(ReviewItem item) {
        java.util.function.Consumer<ReviewItem> listener = missingCoverEnqueuedListener;
        if (item != null && listener != null
            && item.getMissingCover() != null
            && item.getMissingCover().getMode() == ReviewItem.MissingCover.Mode.STAGED_IMPORT
            && !item.getMissingCover().isCoverAutoSearchTriggered()) {
            try {
                listener.accept(item);
            } catch (Exception e) {
                log.warn("缺少封面入队回调失败（不影响条目本身）: {}", e.getMessage());
            }
        }
    }

    /**
     * 「锁定专辑缺曲」入队：整个文件夹的待处理文件进入待确认，最接近的版本作为首个候选。
     */
    public ReviewItem enqueueMissingTracks(String folderPath,
                                           List<FolderAlbumCache.PendingFile> pendingFiles,
                                           FolderAlbumCache.CachedAlbumInfo synthesized,
                                           List<FolderAlbumCache.FolderCandidate> candidates,
                                           List<Integer> durationSequence,
                                           FolderAlbumCache.MissingTracksInfo missing) {
        String reason = "锁定专辑缺曲：「" + missing.getAlbumTitle() + "」" + missing.describe();
        ReviewItem item;
        synchronized (this) {
            item = enqueueInternal(folderPath, pendingFiles, synthesized, candidates, durationSequence,
                reason, 0.0, target -> applyMissingTracks(target, missing));
        }
        java.util.function.Consumer<ReviewItem> listener = missingTracksEnqueuedListener;
        if (item != null && listener != null && !item.isAutoOnlineSearchTriggered()) {
            try {
                listener.accept(item);
            } catch (Exception e) {
                log.warn("缺曲条目入队回调失败（不影响条目本身）: {}", e.getMessage());
            }
        }
        return item;
    }

    private void applyMissingTracks(ReviewItem item, FolderAlbumCache.MissingTracksInfo missing) {
        item.setKind(ReviewItem.Kind.TRACKS_MISSING);
        // 具体数字在 missingTracks 里，面板直接读，不在 facts 里重复一份
        applyDiagnosis(item, ReviewDiagnosis.of(ReviewDiagnosis.TRACKS_MISSING));
        ReviewItem.MissingTracks detail = new ReviewItem.MissingTracks();
        detail.setReleaseGroupId(missing.getReleaseGroupId());
        detail.setReleaseId(missing.getReleaseId());
        detail.setAlbumTitle(missing.getAlbumTitle());
        detail.setAlbumArtist(missing.getAlbumArtist());
        detail.setReleaseTrackCount(missing.getReleaseTrackCount());
        detail.setLocalTrackCount(missing.getLocalTrackCount());
        detail.setUnmatchedLocalCount(missing.getUnmatchedLocalCount());
        detail.setDetectedBy(missing.getDetectedBy());
        detail.setUnmatchedFiles(new java.util.LinkedHashMap<>(missing.getUnmatchedFiles()));
        item.setMissingTracks(detail);

        // 最接近的版本带着 releaseId 放在首位：人工可以直接选它，
        // 对得上的用该版本的曲目位置，对不上的作为附加曲目
        if (missing.getReleaseId() != null && !missing.getReleaseId().isEmpty()) {
            List<ReviewItem.CandidateSnapshot> list = item.getCandidates() == null
                ? new ArrayList<>() : new ArrayList<>(item.getCandidates());
            list.removeIf(c -> missing.getReleaseId().equals(c.getReleaseId()));
            ReviewItem.CandidateSnapshot closest = new ReviewItem.CandidateSnapshot();
            closest.setReleaseGroupId(missing.getReleaseGroupId());
            closest.setReleaseId(missing.getReleaseId());
            closest.setTitle(missing.getAlbumTitle());
            closest.setArtist(missing.getAlbumArtist());
            closest.setReleaseType(missing.getReleaseType());
            closest.setCompilation(missing.isCompilation());
            closest.setDate(missing.getReleaseDate());
            closest.setTrackCount(missing.getReleaseTrackCount());
            list.add(0, closest);
            item.setCandidates(list);
        }
    }

    private ReviewItem enqueueInternal(String folderPath,
                                       List<FolderAlbumCache.PendingFile> pendingFiles,
                                       FolderAlbumCache.CachedAlbumInfo synthesized,
                                       List<FolderAlbumCache.FolderCandidate> candidates,
                                       List<Integer> durationSequence,
                                       String reason,
                                       double confidence,
                                       java.util.function.Consumer<ReviewItem> customizer) {
        if (folderPath == null || pendingFiles == null || pendingFiles.isEmpty()) {
            return null;
        }

        ReviewItem existing = findPendingByFolder(folderPath);
        ReviewItem item = existing != null ? existing : new ReviewItem();
        boolean isNewItem = existing == null;

        if (isNewItem) {
            item.setId(UUID.randomUUID().toString());
            item.setCreatedAt(System.currentTimeMillis());
            item.setFolderPath(folderPath);
            item.setFolderName(new File(folderPath).getName());
            item.setStatus(ReviewItem.Status.PENDING_REVIEW);
            items.put(item.getId(), item);
        }

        item.setUpdatedAt(System.currentTimeMillis());
        item.setReason(reason);
        item.setConfidence(confidence);
        if (synthesized != null) {
            item.setSynthesizedAlbumTitle(synthesized.getAlbumTitle());
            item.setSynthesizedAlbumArtist(synthesized.getAlbumArtist());
        }
        if (durationSequence != null && !durationSequence.isEmpty()) {
            item.setDurationSequence(new ArrayList<>(durationSequence));
        }
        if (candidates != null && !candidates.isEmpty()) {
            List<ReviewItem.CandidateSnapshot> snapshots = new ArrayList<>();
            for (FolderAlbumCache.FolderCandidate candidate : candidates) {
                ReviewItem.CandidateSnapshot snapshot = new ReviewItem.CandidateSnapshot();
                snapshot.setReleaseGroupId(candidate.getReleaseGroupId());
                snapshot.setTitle(candidate.getTitle());
                snapshot.setSupportCount(candidate.getSupportCount());
                snapshot.setTotalSamples(candidate.getTotalSamples());
                snapshots.add(snapshot);
            }
            item.setCandidates(snapshots);
            item.setCandidatesExpanded(false);
        }
        if (customizer != null) {
            customizer.accept(item);
        }

        Path stagingDir = stagingRoot.resolve(item.getId());
        item.setStagingDir(stagingDir.toAbsolutePath().toString());

        // 关键顺序（审查 #3）：先构造条目并落盘，**落盘成功之后**才真正搬动转码临时文件。
        // 反过来做的话，一旦 save() 失败就会留下：JSON 无记录 + 临时文件已被搬走 = 孤儿文件。
        List<String> previousStagedPaths = collectStagedPaths(item);

        List<ReviewItem.FileEntry> entries = new ArrayList<>();
        List<StagingMove> plannedMoves = new ArrayList<>();
        int entryIndex = 0;
        for (FolderAlbumCache.PendingFile pending : pendingFiles) {
            entries.add(buildEntry(pending, stagingDir, entryIndex++, plannedMoves));
        }
        item.setFiles(entries);

        try {
            save();
        } catch (IOException e) {
            // 事务式回滚：条目没能落盘，就当作从未入队；文件此刻尚未被搬动。
            log.error("待人工确认队列落盘失败，已回滚入队操作: {}", e.getMessage());
            LogCollector.addLog("ERROR", "待确认队列落盘失败，条目未入队: " + item.getFolderName());
            if (isNewItem) {
                items.remove(item.getId());
            } else {
                // 旧条目已存在：必须释放「该文件夹处于待确认」的扫描守卫，
                // 否则调用方改走直接归档后，该目录会被一个永远不会被处理的 PENDING 条目锁死。
                item.setStatus(ReviewItem.Status.REJECTED);
                item.setResolutionNote("队列落盘失败，已回退为直接归档");
            }
            return null;
        }

        // 旧条目重新入队时，清理不再被引用的旧暂存文件，避免暂存区泄漏
        List<String> currentStagedPaths = collectStagedPaths(item);
        for (String old : previousStagedPaths) {
            if (!currentStagedPaths.contains(old)) {
                deleteQuietly(Paths.get(old));
            }
        }

        // 落盘成功后再搬运暂存文件；个别搬运失败只降级为「使用原始文件」，不影响整条记录
        boolean stagingChanged = false;
        for (StagingMove move : plannedMoves) {
            if (!move.execute()) {
                move.getEntry().setStagedPath(null);
                stagingChanged = true;
            }
        }
        if (stagingChanged) {
            try {
                save();
            } catch (IOException e) {
                log.warn("更新暂存路径失败（条目已在队列中，最坏情况是回退使用原始文件）: {}", e.getMessage());
            }
        }

        if (item.effectiveKind() == ReviewItem.Kind.COVER_MISSING) {
            log.warn("========================================");
            log.warn("⚠ 缺少封面，已暂停入库并放入「缺少封面」队列");
            log.warn("  文件夹: {}", item.getFolderName());
            log.warn("  专辑: {}", item.getMissingCover() != null ? item.getMissingCover().getAlbumTitle() : item.getSynthesizedAlbumTitle());
            log.warn("  文件数: {}", entries.size());
            log.warn("  等待在 Web 面板补全封面或确认无封面入库");
            log.warn("========================================");
            LogCollector.addLog("WARN", "缺少封面，已暂缓入库等待处理: " + item.getFolderName());
        } else {
            log.warn("========================================");
            log.warn("⚠ 专辑无法确定，已放入「待人工确认」队列");
            log.warn("  文件夹: {}", item.getFolderName());
            log.warn("  文件数: {}", entries.size());
            log.warn("  候选专辑数: {}", item.getCandidates().size());
            log.warn("  统一置信度: {}", String.format("%.2f", confidence));
            log.warn("  文件保持原样，未写入任何标签，等待在 Web 面板中确认");
            log.warn("========================================");
            LogCollector.addLog("WARN", "专辑待人工确认: " + item.getFolderName());
        }

        return item;
    }

    private List<String> collectStagedPaths(ReviewItem item) {
        List<String> paths = new ArrayList<>();
        if (item.getFiles() != null) {
            for (ReviewItem.FileEntry entry : item.getFiles()) {
                if (entry.getStagedPath() != null) {
                    paths.add(entry.getStagedPath());
                }
            }
        }
        return paths;
    }

    synchronized ReviewItem findPendingByFolder(String folderPath) {
        if (folderPath == null) return null;
        for (ReviewItem item : items.values()) {
            if (item.getStatus() == ReviewItem.Status.PENDING_REVIEW
                && folderPath.equals(item.getFolderPath())) {
                return item;
            }
        }
        return null;
    }

    /**
     * 计划中的暂存搬运动作（落盘成功后才执行）
     */
    private class StagingMove {
        private final ReviewItem.FileEntry entry;
        private final Path source;
        private final Path target;
        private final Path tempDir;

        private StagingMove(ReviewItem.FileEntry entry, Path source, Path target, Path tempDir) {
            this.entry = entry;
            this.source = source;
            this.target = target;
            this.tempDir = tempDir;
        }

        private ReviewItem.FileEntry getEntry() {
            return entry;
        }

        private boolean execute() {
            try {
                Files.createDirectories(target.getParent());
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                log.debug("转码临时文件已挪入暂存目录: {}", target);
                return true;
            } catch (IOException e) {
                log.warn("移动转码临时文件到暂存目录失败，将回退使用原始文件（可能不再满足格式规范化配置）: {} - {}",
                    source, e.getMessage());
                return false;
            } finally {
                deleteQuietly(tempDir);
            }
        }
    }

    private ReviewItem.FileEntry buildEntry(FolderAlbumCache.PendingFile pending, Path stagingDir, int index,
                                            List<StagingMove> plannedMoves) {
        ReviewItem.FileEntry entry = new ReviewItem.FileEntry();
        File original = pending.getAudioFile();
        entry.setOriginalPath(original.getAbsolutePath());
        entry.setFileName(original.getName());

        MusicMetadata metadata = pending.getMetadata() instanceof MusicMetadata
            ? (MusicMetadata) pending.getMetadata() : null;
        if (metadata != null) {
            entry.setMetadata(stripHeavyFields(metadata));
            entry.setDuration(metadata.getDuration());
        }

        // 转码产生的临时文件：挪进暂存目录，避免长期挂起时被系统清理。
        // 带唯一序号前缀防止多碟专辑同名文件（如 Disc 1/01.flac 与 Disc 2/01.flac）互相覆盖。
        // 注意：这里只「登记计划」，真正的移动要等 JSON 落盘成功之后。
        File processing = pending.getProcessingFile();
        if (processing != null && !processing.equals(original) && processing.exists()) {
            String safeBase = new File(processing.getName()).getName();
            String stagedName = String.format("%03d_%s", index, safeBase);
            Path target = stagingDir.resolve(stagedName).toAbsolutePath().normalize();
            entry.setStagedPath(target.toString());
            if (!processing.toPath().toAbsolutePath().normalize().equals(target)) {
                plannedMoves.add(new StagingMove(entry, processing.toPath(), target,
                    pending.getProcessingTempDir()));
            }
        } else {
            deleteQuietly(pending.getProcessingTempDir());
        }

        return entry;
    }

    /**
     * 去掉不适合落盘的重字段（封面二进制）。
     * 封面在人工确认之后会按最终锁定的专辑重新获取，没必要把几百 KB 写进 JSON。
     */
    private MusicMetadata stripHeavyFields(MusicMetadata source) {
        return source.copyWithoutHeavyFields();
    }

    // ==================== 状态变更 ====================

    /**
     * 标记条目已处置，并在完成归档后清理暂存目录。
     * 注意：REJECTED（忽略）状态下保留暂存/重识别临时目录，支持后续「撤销忽略」无损恢复。
     */
    public synchronized void markResolved(ReviewItem item, ReviewItem.Status status, String note) {
        if (item == null) {
            return;
        }
        item.setStatus(status);
        item.setResolutionNote(note);
        item.setUpdatedAt(System.currentTimeMillis());
        if (status != ReviewItem.Status.REJECTED) {
            cleanupStaging(item);
        }
        saveQuietly();
    }

    public synchronized void update(ReviewItem item) {
        if (item == null) {
            return;
        }
        item.setUpdatedAt(System.currentTimeMillis());
        saveQuietly();
    }

    public synchronized void remove(String id) {
        ReviewItem removed = items.remove(id);
        if (removed != null) {
            cleanupStaging(removed);
            saveQuietly();
        }
    }

    /**
     * 从已确认的条目恢复「人工锁定的专辑」（审查 #6）。
     *
     * `FolderAlbumCache` 是纯内存的，重启后 MANUAL_CONFIRMED 缓存会消失，
     * 而 `isFolderUnderReview()` 只拦 PENDING_REVIEW——
     * 于是同目录新增的文件会重新走自动匹配，可能选出与人工确认不同的专辑，
     * “人工结果永不被覆盖”的承诺只在当前进程生命周期内成立。
     *
     * 启动时回放一次，让承诺跨重启依然成立。
     */
    public synchronized int restoreManualLocks(FolderAlbumCache folderAlbumCache) {
        if (folderAlbumCache == null) {
            return 0;
        }
        int restored = 0;
        for (ReviewItem item : items.values()) {
            if (item.getStatus() != ReviewItem.Status.CONFIRMED
                    || item.getVerificationSource() == ReviewItem.VerificationSource.LOCAL_TAGS) {
                continue;
            }
            if (item.getFolderPath() == null || item.getResolvedAlbumTitle() == null) {
                continue;
            }
            // 文件夹已不存在（用户已清理）就没必要再锁
            if (!new File(item.getFolderPath()).isDirectory()) {
                continue;
            }
            FolderAlbumCache.CachedAlbumInfo albumInfo = new FolderAlbumCache.CachedAlbumInfo(
                item.getResolvedReleaseGroupId(),
                item.getResolvedReleaseId(),
                item.getResolvedAlbumTitle(),
                item.getResolvedAlbumArtist(),
                item.getResolvedTrackCount(),
                item.getResolvedReleaseDate() != null ? item.getResolvedReleaseDate() : "",
                item.getResolvedReleaseType(),
                item.isResolvedCompilation(),
                1.0,
                FolderAlbumCache.CacheSource.MANUAL_CONFIRMED
            );
            folderAlbumCache.forceSetFolderAlbum(item.getFolderPath(), albumInfo);
            folderAlbumCache.clearFolderUnresolved(item.getFolderPath());
            restored++;
        }
        if (restored > 0) {
            log.info("已从待确认队列恢复 {} 个人工确认的专辑锁定", restored);
        }
        return restored;
    }

    private static final java.util.regex.Pattern REPROCESS_DIR_PATTERN =
        java.util.regex.Pattern.compile("^reprocess-[A-Za-z0-9._-]+$");

    private void cleanupStaging(ReviewItem item) {
        if (item == null) {
            return;
        }
        // 已入库音乐库条目绝不允许删除任何目录
        if (item.effectiveKind() == ReviewItem.Kind.COVER_MISSING
                && item.getMissingCover() != null
                && item.getMissingCover().getMode() == ReviewItem.MissingCover.Mode.LIBRARY_EXISTING) {
            item.setStagingDir(null);
            item.setReprocessDir(null);
            return;
        }
        Path base = getStagingRoot();
        Path reprocessRoot = findReprocessRoot(item, base);
        String stagingDir = item.getStagingDir();
        if (stagingDir != null) {
            Path dir = Paths.get(stagingDir).toAbsolutePath().normalize();
            if (isStrictlyInside(dir, base) && Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                deleteRecursivelyQuietly(dir);
            }
            item.setStagingDir(null);
        }
        if (reprocessRoot != null && isStrictlyInside(reprocessRoot, base) && Files.exists(reprocessRoot)) {
            deleteRecursivelyQuietly(reprocessRoot);
        }
        item.setReprocessDir(null);
    }

    /** 定位本队列托管的暂存根目录内「处理记录 -> 重新识别」生成的临时目录（reprocess-<UUID>）。 */
    public Path findReprocessRoot(ReviewItem item) {
        return findReprocessRoot(item, this.stagingRoot);
    }

    /**
     * 定位「处理记录 -> 重新识别」生成的临时根目录（reprocess-*），严格限定在 stagingRoot 直接子目录下，
     * 绝不越界匹配用户音乐库或监控目录中同名祖先目录。
     */
    public static Path findReprocessRoot(ReviewItem item, Path stagingRoot) {
        if (item == null || stagingRoot == null) {
            return null;
        }
        if (item.effectiveKind() == ReviewItem.Kind.COVER_MISSING
                && item.getMissingCover() != null
                && item.getMissingCover().getMode() == ReviewItem.MissingCover.Mode.LIBRARY_EXISTING) {
            return null;
        }
        Path base = stagingRoot.toAbsolutePath().normalize();
        Path realBase = tryRealPath(base);
        for (String candidate : new String[] { item.getReprocessDir(), item.getFolderPath(), item.getStagingDir() }) {
            Path root = reprocessChildWithin(candidate, base, realBase);
            if (root != null) {
                return root;
            }
        }
        return null;
    }

    private static Path reprocessChildWithin(String rawPath, Path base, Path realBase) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        try {
            Path cur = Paths.get(rawPath.trim()).toAbsolutePath().normalize();
            if (!isStrictlyInside(cur, base)) {
                return null;
            }
            Path rel = base.relativize(cur);
            if (rel.getNameCount() < 1) {
                return null;
            }
            String topName = rel.getName(0).toString();
            if (!REPROCESS_DIR_PATTERN.matcher(topName).matches()) {
                return null;
            }
            Path candidateRoot = base.resolve(topName).toAbsolutePath().normalize();
            if (!isStrictlyInside(candidateRoot, base)) {
                return null;
            }
            if (Files.isSymbolicLink(candidateRoot)) {
                return null;
            }
            if (realBase != null && Files.exists(candidateRoot)) {
                Path realCandidate = candidateRoot.toRealPath();
                if (!isStrictlyInside(realCandidate, realBase)) {
                    return null;
                }
            }
            return candidateRoot;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isStrictlyInside(Path candidate, Path base) {
        return candidate != null && base != null && candidate.startsWith(base) && !candidate.equals(base);
    }

    private static Path tryRealPath(Path path) {
        try {
            return Files.exists(path) ? path.toRealPath() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void deleteRecursivelyQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                deleteQuietly(p);
            }
        } catch (IOException e) {
            log.debug("递归清理暂存目录失败: {} - {}", root, e.getMessage());
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("删除失败: {} - {}", path, e.getMessage());
        }
    }

    // ==================== 持久化 ====================

    private void load() {
        if (!Files.isRegularFile(storeFile)) {
            log.info("待人工确认队列为空（尚无 {}）", storeFile.toAbsolutePath());
            return;
        }
        if (loadFrom(storeFile)) {
            return;
        }

        // 主文件损坏：不能静默地「以空队列启动」——那会让待确认目录失去扫描守卫，
        // 被重新自动处理。先把损坏文件留存，再尝试从 .bak 恢复。
        Path corrupt = storeFile.resolveSibling(storeFile.getFileName() + ".corrupt." + System.currentTimeMillis());
        try {
            Files.move(storeFile, corrupt, StandardCopyOption.REPLACE_EXISTING);
            log.error("待确认队列文件损坏，已留存为: {}", corrupt);
        } catch (IOException e) {
            log.error("待确认队列文件损坏且无法重命名: {}", e.getMessage());
        }

        Path backup = backupFile();
        if (Files.isRegularFile(backup) && loadFrom(backup)) {
            log.warn("已从备份 {} 恢复待确认队列", backup);
            LogCollector.addLog("WARN", "待确认队列主文件损坏，已从备份恢复");
            saveQuietly();
            return;
        }

        log.error("待确认队列无法加载（主文件与备份均不可用），将以空队列启动");
        LogCollector.addLog("ERROR", "待确认队列加载失败，请检查 .corrupt 文件");
    }

    private boolean loadFrom(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<ReviewItem> loaded = gson.fromJson(reader, ITEM_LIST_TYPE);
            if (loaded == null) {
                return false;
            }
            items.clear();
            for (ReviewItem item : loaded) {
                if (item != null && item.getId() != null) {
                    items.put(item.getId(), item);
                }
            }
            log.info("已加载待人工确认队列: {} 条（待确认 {} 条，来源 {}）",
                items.size(), countPendingInternal(), file.getFileName());
            return true;
        } catch (Exception e) {
            log.error("解析待人工确认队列失败（{}）: {}", file, e.getMessage());
            return false;
        }
    }

    private Path backupFile() {
        return storeFile.resolveSibling(storeFile.getFileName() + ".bak");
    }

    private int countPendingInternal() {
        int count = 0;
        for (ReviewItem item : items.values()) {
            if (item.getStatus() == ReviewItem.Status.PENDING_REVIEW) {
                count++;
            }
        }
        return count;
    }

    /**
     * 落盘。失败时**必须抛异常**：调用方（入队）需要据此决定是否回滚，
     * 不能向下层描述“已保存”而实际没有。
     */
    private void save() throws IOException {
        Path parent = storeFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // 先把上一份完好的内容备份出去，避免写坏后无文可归
        if (Files.isRegularFile(storeFile)) {
            try {
                Files.copy(storeFile, backupFile(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.debug("备份待确认队列失败（不阻断主流程）: {}", e.getMessage());
            }
        }

        // 先写临时文件再替换，避免进程被杀时留下半截 JSON
        Path tmp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            gson.toJson(new ArrayList<>(items.values()), ITEM_LIST_TYPE, writer);
        }

        try {
            Files.move(tmp, storeFile,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // 部分文件系统（网络盘 / 跨卷）不支持原子移动，降级并明确告知
            log.warn("当前文件系统不支持原子替换，降级为普通替换（崩溃时可能需从 .bak 恢复）");
            Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 状态变更类操作的落盘：失败只能告警（文件已经写完了，回滚无意义）
     */
    private void saveQuietly() {
        try {
            save();
        } catch (IOException e) {
            log.error("保存待人工确认队列失败（状态变更可能在重启后丢失）: {}", e.getMessage());
            LogCollector.addLog("ERROR", "待确认队列保存失败: " + e.getMessage());
        }
    }

    public MusicConfig getConfig() {
        return config;
    }
}
