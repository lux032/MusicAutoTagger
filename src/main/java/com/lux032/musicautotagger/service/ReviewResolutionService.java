package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 待确认条目的处置服务（阶段六 #19）
 *
 * 对应设计里的三个人工动作：
 *   A. 从候选中确认  -> {@link #confirmCandidate}
 *   B. 按「MB 未收录」归档 -> {@link #archiveAsUnverified}
 *   C. 标记失败 / 忽略 -> {@link #reject}
 *
 * 这里是**写入型 + 会触发文件移动**的逻辑，因此：
 *   - 每个动作先校验状态（只有 PENDING_REVIEW 可被处置），保证幂等
 *   - 同一时刻只允许一个处置在跑（方法级 synchronized），避免两次点击写同一批文件
 */
@Slf4j
public class ReviewResolutionService {

    private final MusicConfig config;
    private final ReviewQueueService reviewQueue;
    private final FolderAlbumCache folderAlbumCache;
    private final AlbumBatchProcessor albumBatchProcessor;
    private final MusicBrainzClient musicBrainzClient;
    private final DurationSequenceService durationSequenceService;
    private final ProcessedFileLogger processedLogger;

    public ReviewResolutionService(MusicConfig config,
                                   ReviewQueueService reviewQueue,
                                   FolderAlbumCache folderAlbumCache,
                                   AlbumBatchProcessor albumBatchProcessor,
                                   MusicBrainzClient musicBrainzClient,
                                   DurationSequenceService durationSequenceService,
                                   ProcessedFileLogger processedLogger) {
        this.config = config;
        this.reviewQueue = reviewQueue;
        this.folderAlbumCache = folderAlbumCache;
        this.albumBatchProcessor = albumBatchProcessor;
        this.musicBrainzClient = musicBrainzClient;
        this.durationSequenceService = durationSequenceService;
        this.processedLogger = processedLogger;
    }

    /** 处置失败时抛出，Web 层据此返回 4xx/5xx */
    public static class ResolutionException extends Exception {
        private final int httpStatus;

        public ResolutionException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }

        public int getHttpStatus() {
            return httpStatus;
        }
    }

    // ==================== 候选展开 ====================

    /**
     * 展开候选：把 Release Group 级的候选扩成 Release 级，
     * 并给出每个 Release 与本文件夹时长序列的相似度，供人工逐条 diff。
     *
     * 这一步会打 MusicBrainz，因此**只在人工打开详情时**执行，且结果写回条目缓存。
     */
    public synchronized ReviewItem expandCandidates(String id) throws ResolutionException {
        ReviewItem item = requireItem(id);
        if (item.isCandidatesExpanded()) {
            return item;
        }

        List<Integer> folderDurations = item.getDurationSequence();
        List<ReviewItem.CandidateSnapshot> expanded = new ArrayList<>();

        for (ReviewItem.CandidateSnapshot candidate : item.getCandidates()) {
            String rgId = candidate.getReleaseGroupId();
            if (rgId == null || rgId.isEmpty()) {
                expanded.add(candidate);
                continue;
            }
            try {
                List<MusicBrainzClient.AlbumDurationResult> releases =
                    musicBrainzClient.getAllReleaseDurationSequences(rgId);
                if (releases.isEmpty()) {
                    expanded.add(candidate);
                    continue;
                }
                for (MusicBrainzClient.AlbumDurationResult release : releases) {
                    ReviewItem.CandidateSnapshot snapshot = new ReviewItem.CandidateSnapshot();
                    snapshot.setReleaseGroupId(rgId);
                    snapshot.setReleaseId(release.getReleaseId());
                    snapshot.setTitle(release.getReleaseTitle() != null
                        ? release.getReleaseTitle() : candidate.getTitle());
                    snapshot.setArtist(item.getSynthesizedAlbumArtist());
                    snapshot.setMediaFormat(release.getMediaFormat());
                    snapshot.setTrackCount(release.getDurations() != null
                        ? release.getDurations().size() : release.getTrackCount());
                    snapshot.setDurations(release.getDurations());
                    snapshot.setSupportCount(candidate.getSupportCount());
                    snapshot.setTotalSamples(candidate.getTotalSamples());
                    if (folderDurations != null && !folderDurations.isEmpty()
                        && release.getDurations() != null && !release.getDurations().isEmpty()) {
                        snapshot.setDurationSimilarity(
                            durationSequenceService.calculateSimilarityDTW(folderDurations, release.getDurations()));
                    }
                    expanded.add(snapshot);
                }
            } catch (Exception e) {
                log.warn("展开候选 {} 失败: {}", rgId, e.getMessage());
                expanded.add(candidate);
            }
        }

        // 相似度高的排前面，没有相似度的排后面
        expanded.sort((a, b) -> Double.compare(
            b.getDurationSimilarity() == null ? -1 : b.getDurationSimilarity(),
            a.getDurationSimilarity() == null ? -1 : a.getDurationSimilarity()));

        item.setCandidates(expanded);
        item.setCandidatesExpanded(true);
        reviewQueue.update(item);
        return item;
    }

    // ==================== 动作 A：从候选中确认 ====================

    /**
     * 人工从候选里选定了一个 release，按它锁定整张专辑并归档。
     */
    public synchronized ReviewItem confirmCandidate(String id, String releaseId, String releaseGroupId)
            throws ResolutionException {
        ReviewItem item = requirePending(id);

        // 审查 #2：必须选定**具体的 Release**。
        // 刚入队的候选只有 releaseGroupId，拿它确认的话：
        //   - 拿不到磟号 / 曲目号 / 该版本的曲目标题 / 发行日期
        //   - trackCount 为 0、releaseId 为 null，写出来的是一个残缺的「人工确认」结果
        // 前端也会隐藏按钮，但后端必须做硬校验，不能只依赖前端。
        if (releaseId == null || releaseId.trim().isEmpty()) {
            throw new ResolutionException(400, "release.id.required");
        }

        ReviewItem.CandidateSnapshot chosen = findCandidateByReleaseId(item, releaseId.trim());
        if (chosen == null) {
            throw new ResolutionException(400, "candidate.not.found");
        }

        // 审查 #1：先预检全部原始文件，任何一个缺失都不得开始处置。
        // 否则 10 个文件缺 1 个时，剩下 9 个会被归档、条目被标为已完成、
        // 暂存目录被清理，缺失的那个永远无法继续处理。
        requireAllOriginalFiles(item);

        String albumTitle = chosen.getTitle() != null ? chosen.getTitle() : item.getSynthesizedAlbumTitle();
        String albumArtist = chosen.getArtist() != null && !chosen.getArtist().isEmpty()
            ? chosen.getArtist() : item.getSynthesizedAlbumArtist();

        FolderAlbumCache.CachedAlbumInfo albumInfo = new FolderAlbumCache.CachedAlbumInfo(
            chosen.getReleaseGroupId(),
            chosen.getReleaseId(),
            albumTitle,
            albumArtist,
            chosen.getTrackCount(),
            chosen.getDate() != null ? chosen.getDate() : "",
            1.0,
            FolderAlbumCache.CacheSource.MANUAL_CONFIRMED
        );

        String folderPath = item.getFolderPath();
        restorePendingFiles(item, chosen);

        // 人工确认的结果优先级最高，且要解除 unresolved 守卫，
        // 否则同目录后续新增的文件仍会被拦在专辑匹配之外。
        folderAlbumCache.clearFolderUnresolved(folderPath);
        folderAlbumCache.forceSetFolderAlbum(folderPath, albumInfo);

        log.info("========================================");
        log.info("✓ 人工确认专辑: {} - {} (Release ID: {})", albumArtist, albumTitle, chosen.getReleaseId());
        log.info("  文件夹: {}", item.getFolderName());
        log.info("========================================");
        LogCollector.addLog("SUCCESS", "人工确认专辑: " + albumTitle);

        AlbumBatchProcessor.BatchProcessResult result =
            albumBatchProcessor.processPendingFilesWithAlbum(folderPath, albumInfo, false);

        // 人工锁定的完整快照：重启后靠它重建 MANUAL_CONFIRMED 缓存
        item.setResolvedReleaseId(chosen.getReleaseId());
        item.setResolvedReleaseGroupId(chosen.getReleaseGroupId());
        item.setResolvedAlbumTitle(albumTitle);
        item.setResolvedAlbumArtist(albumArtist);
        item.setResolvedReleaseDate(chosen.getDate());
        item.setResolvedTrackCount(chosen.getTrackCount());

        finish(item, result, ReviewItem.Status.CONFIRMED, "人工确认: " + albumTitle);
        return item;
    }

    // ==================== 动作 B：按「MB 未收录」归档 ====================

    /**
     * 人工确认「MusicBrainz 里确实没有这张专辑」，按合成信息归档，标记为未验证。
     */
    public synchronized ReviewItem archiveAsUnverified(String id) throws ResolutionException {
        ReviewItem item = requirePending(id);

        requireAllOriginalFiles(item);
        restorePendingFiles(item, null);

        String folderPath = item.getFolderPath();
        folderAlbumCache.markFolderUnresolved(folderPath);

        log.info("人工确认「MusicBrainz 未收录」，按合成专辑信息归档: {}", item.getSynthesizedAlbumTitle());
        LogCollector.addLog("INFO", "人工按未收录归档: " + item.getSynthesizedAlbumTitle());

        // 关键：allowReviewQueue=false，否则会把刚取出的条目又塞回队列
        AlbumBatchProcessor.BatchProcessResult result =
            albumBatchProcessor.processPendingFilesAsUnresolvedAlbum(folderPath, false);

        finish(item, result, ReviewItem.Status.ARCHIVED_UNVERIFIED,
            "人工按 MusicBrainz 未收录归档（verified=false）");
        return item;
    }

    /**
     * 处置收尾（审查 #5）：只有**全部文件都写成功**才能标为已完成并清理暂存。
     *
     * 部分失败时保持 PENDING_REVIEW：
     * 暂存文件不被删除，用户可以修复问题（磁盘满 / 权限 / 文件被占用）后重试。
     * 已成功的文件重试时会被再写一次（幂等写标签 + 覆盖拷贝），代价可接受。
     */
    private void finish(ReviewItem item, AlbumBatchProcessor.BatchProcessResult result,
                        ReviewItem.Status successStatus, String note) {
        if (result != null && result.isFullySuccessful()) {
            reviewQueue.markResolved(item, successStatus, note);
            return;
        }

        String failedList = result == null ? "未知" : String.join(", ", result.getFailedFiles());
        log.error("人工处置未全部成功，条目保持待确认状态: {} (成功 {} 个，失败: {})",
            item.getFolderName(),
            result == null ? 0 : result.getSuccessCount(),
            failedList);
        LogCollector.addLog("ERROR", "待确认条目处理部分失败，保持待确认: " + item.getFolderName());

        item.setResolutionNote("上次处理未全部成功（成功 "
            + (result == null ? 0 : result.getSuccessCount())
            + " 个，失败: " + failedList + "），请修复后重试");
        reviewQueue.update(item);
    }

    // ==================== 动作 C：标记失败 / 忽略 ====================

    /**
     * 人工放弃这个文件夹：不写标签、不归档，只记录下来避免下一轮扫描反复重试。
     */
    public synchronized ReviewItem reject(String id, String note) throws ResolutionException {
        ReviewItem item = requirePending(id);

        for (ReviewItem.FileEntry entry : item.getFiles()) {
            File original = new File(entry.getOriginalPath());
            try {
                processedLogger.markFileAsProcessed(
                    original,
                    "REVIEW_REJECTED",
                    entry.getMetadata() != null && entry.getMetadata().getArtist() != null
                        ? entry.getMetadata().getArtist() : "Unknown Artist",
                    entry.getMetadata() != null && entry.getMetadata().getTitle() != null
                        ? entry.getMetadata().getTitle() : entry.getFileName(),
                    item.getSynthesizedAlbumTitle() != null
                        ? item.getSynthesizedAlbumTitle() : "Unknown Album"
                );
            } catch (Exception e) {
                log.warn("记录被拒绝文件失败: {} - {}", entry.getFileName(), e.getMessage());
            }
        }

        // 该文件夹的 unresolved 标记保留：即使用户之后又放入新文件，
        // 也不应该悄悄归入某张旧专辑。
        log.info("人工标记为失败/忽略: {} ({} 个文件)", item.getFolderName(), item.getFiles().size());
        LogCollector.addLog("WARN", "人工标记待确认专辑为失败: " + item.getFolderName());

        reviewQueue.markResolved(item, ReviewItem.Status.REJECTED,
            note != null && !note.trim().isEmpty() ? note.trim() : "人工标记为失败/忽略");
        return item;
    }

    // ==================== 内部工具 ====================

    /**
     * 把待确认条目里的文件重新放回 pending 队列，交给现有的批量写入链路。
     *
     * 注意 processingTempDir 传 null：
     * 暂存文件由 ReviewQueueService 统一清理，不能让 cleanupPendingTemp 在写入过程中删掉它。
     *
     * @param lockedRelease 非 null 时，会尝试从该 release 中按时长取回更准确的曲目信息
     * @return 实际恢复的文件数（调用前已由 requireAllOriginalFiles 保证全部存在）
     */
    private int restorePendingFiles(ReviewItem item, ReviewItem.CandidateSnapshot lockedRelease) {
        int restored = 0;
        for (ReviewItem.FileEntry entry : item.getFiles()) {
            File original = new File(entry.getOriginalPath());

            File processing = original;
            if (entry.getStagedPath() != null) {
                File staged = new File(entry.getStagedPath());
                if (staged.exists()) {
                    processing = staged;
                } else {
                    // 暂存的是格式规范化（降采样）后的副本，丢了不影响内容正确性，
                    // 但归档结果将不再满足 audio.normalize 配置，必须显式告知。
                    log.warn("暂存的转码文件已丢失，回退使用原始文件（归档结果可能不再满足格式规范化配置）: {}",
                        entry.getStagedPath());
                    LogCollector.addLog("WARN", "暂存转码文件丢失，已回退原始文件: " + entry.getFileName());
                }
            }

            MusicMetadata metadata = entry.getMetadata() != null ? entry.getMetadata() : new MusicMetadata();
            metadata = enrichFromLockedRelease(metadata, entry, lockedRelease, item);

            albumBatchProcessor.addPendingFile(
                item.getFolderPath(),
                original,
                processing,
                null,       // 暂存文件不交给 cleanupPendingTemp 删除
                metadata,
                null        // 封面会按最终锁定的 Release Group 重新获取
            );
            restored++;
        }
        return restored;
    }

    /**
     * 人工选定 release 后，尽量用该 release 里的曲目信息（碟号/曲目号/标题）替换识别结果。
     * 失败时保留原有曲目级元数据——它本来就是准确的，只是缺少专辑维度的信息。
     */
    private MusicMetadata enrichFromLockedRelease(MusicMetadata metadata,
                                                  ReviewItem.FileEntry entry,
                                                  ReviewItem.CandidateSnapshot lockedRelease,
                                                  ReviewItem item) {
        if (lockedRelease == null || lockedRelease.getReleaseId() == null) {
            return metadata;
        }
        Integer duration = entry.getDuration();
        if (duration == null || duration <= 0) {
            return metadata;
        }
        try {
            MusicMetadata fromRelease = musicBrainzClient.getTrackFromLockedAlbumByDuration(
                lockedRelease.getReleaseId(),
                lockedRelease.getReleaseGroupId(),
                duration,
                lockedRelease.getTitle() != null ? lockedRelease.getTitle() : item.getSynthesizedAlbumTitle(),
                lockedRelease.getArtist() != null ? lockedRelease.getArtist() : item.getSynthesizedAlbumArtist()
            );
            if (fromRelease == null) {
                return metadata;
            }
            // 保留原识别结果中 release 查询不会返回的字段
            if (fromRelease.getComposer() == null) {
                fromRelease.setComposer(metadata.getComposer());
            }
            if (fromRelease.getLyricist() == null) {
                fromRelease.setLyricist(metadata.getLyricist());
            }
            if (fromRelease.getLyrics() == null) {
                fromRelease.setLyrics(metadata.getLyrics());
            }
            if (fromRelease.getGenres() == null) {
                fromRelease.setGenres(metadata.getGenres());
            }
            fromRelease.setReleaseId(lockedRelease.getReleaseId());
            fromRelease.setDuration(duration);
            return fromRelease;
        } catch (Exception e) {
            log.warn("从锁定 release 获取曲目信息失败，保留原识别结果: {} - {}",
                entry.getFileName(), e.getMessage());
            return metadata;
        }
    }

    /**
     * 只按 releaseId 查找。
     * **故意不提供按 releaseGroupId 的回退**：确认动作必须落到具体发行版本上，
     * 否则界面写着「确认具体版本」，实际只确认了 Release Group。
     */
    private ReviewItem.CandidateSnapshot findCandidateByReleaseId(ReviewItem item, String releaseId) {
        for (ReviewItem.CandidateSnapshot candidate : item.getCandidates()) {
            if (releaseId.equals(candidate.getReleaseId())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 处置前预检：所有原始文件必须存在。
     * 任何一个缺失就拒绝整个处置（410），条目保持 PENDING_REVIEW，
     * 避免「部分归档 + 条目标完成 + 暂存被清」导致缺失文件永久无法处理。
     */
    private void requireAllOriginalFiles(ReviewItem item) throws ResolutionException {
        List<String> missing = new ArrayList<>();
        for (ReviewItem.FileEntry entry : item.getFiles()) {
            if (entry.getOriginalPath() == null || !new File(entry.getOriginalPath()).isFile()) {
                missing.add(entry.getFileName() != null ? entry.getFileName() : entry.getOriginalPath());
            }
        }
        if (!missing.isEmpty()) {
            log.warn("待确认条目 {} 中有 {} 个原始文件已不存在，拒绝处置: {}",
                item.getFolderName(), missing.size(), String.join(", ", missing));
            throw new ResolutionException(410, "files.missing");
        }
    }

    private ReviewItem requireItem(String id) throws ResolutionException {
        ReviewItem item = reviewQueue.get(id);
        if (item == null) {
            throw new ResolutionException(404, "item.not.found");
        }
        return item;
    }

    private ReviewItem requirePending(String id) throws ResolutionException {
        ReviewItem item = requireItem(id);
        if (item.getStatus() != ReviewItem.Status.PENDING_REVIEW) {
            // 幂等保护：重复点击 / 并发请求不得把同一批文件再写一遍
            throw new ResolutionException(409, "item.already.resolved");
        }
        return item;
    }

    public MusicConfig getConfig() {
        return config;
    }
}
