package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.llm.LlmAlbumJudge;
import com.lux032.musicautotagger.service.llm.LlmClient;
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
    /** 阶段七 #22：封闭式 LLM 判定（默认关闭，由 llm.album.judge.enabled 控制） */
    private final LlmAlbumJudge llmAlbumJudge;

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
        this.llmAlbumJudge = new LlmAlbumJudge(new LlmClient(config));
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
     *
     * <p><b>审查修正 C2（P0）</b>：原实现无论展开是否成功都会无条件
     * {@code setCandidatesExpanded(true)}，而入口处又有「已展开就直接返回」的短路：
     * MusicBrainz 抱一下就会让该条目**永久**停在 RG 级，
     * 面板只能显示「需先展开版本明细」，而后端又硬校验 {@code release.id.required}
     * ——这个文件夹再也无法人工确认，只能手改队列 JSON。
     * 现在只有**全部需要展开的 RG 都成功**才置位，否则下次还能重试。
     */
    public synchronized ReviewItem expandCandidates(String id) throws ResolutionException {
        ReviewItem item = requireItem(id);
        if (item.isCandidatesExpanded() && !hasPendingReleaseGroupCandidates(item)) {
            return item;
        }
        if (item.isCandidatesExpanded()) {
            // 兼容修复前已经落盘的坏状态：expanded=true，但候选仍只有 RG、没有 releaseId。
            // 不能继续相信旧标志，否则升级后这些条目仍然永久卡死。
            log.warn("检测到旧版遗留的错误展开状态，重置并重新展开: {}", item.getFolderName());
            item.setCandidatesExpanded(false);
        }

        List<Integer> folderDurations = item.getDurationSequence();
        List<ReviewItem.CandidateSnapshot> expanded = new ArrayList<>();
        // 任一 RG 展开失败（网络 / 5xx / 限流）就不能把条目钉死为「已展开」
        boolean expansionFailed = false;

        // 重试场景：上一轮已经成功展开过的 RG（条目里已有它的 Release 级快照）
        // **不能再拉一次**，否则同一个 RG 的版本会被重复插入（N 个快照 × N 个版本）。
        java.util.Set<String> alreadyExpandedGroups = new java.util.HashSet<>();
        // 本轮已经请求过的 RG（成功或失败）都不再重复请求；同一 RG 只保留一条待重试快照
        java.util.Set<String> attemptedGroups = new java.util.HashSet<>();
        for (ReviewItem.CandidateSnapshot candidate : item.getCandidates()) {
            if (candidate.getReleaseId() != null && !candidate.getReleaseId().isEmpty()
                && candidate.getReleaseGroupId() != null && !candidate.getReleaseGroupId().isEmpty()) {
                alreadyExpandedGroups.add(candidate.getReleaseGroupId());
            }
        }

        for (ReviewItem.CandidateSnapshot candidate : item.getCandidates()) {
            String rgId = candidate.getReleaseGroupId();
            if (rgId == null || rgId.isEmpty()) {
                expanded.add(candidate);
                continue;
            }
            if (candidate.getReleaseId() != null && !candidate.getReleaseId().isEmpty()) {
                // 上一轮已成功展开得到的 Release 级快照，原样保留
                expanded.add(candidate);
                continue;
            }
            if (alreadyExpandedGroups.contains(rgId) || !attemptedGroups.add(rgId)) {
                // 该 RG 已由旧快照展开过，或本轮已经请求过（包括失败），
                // 丢弃重复的 RG 级快照，避免重复 API 调用与重复候选行。
                continue;
            }
            try {
                List<MusicBrainzClient.AlbumDurationResult> releases =
                    musicBrainzClient.getAllReleaseDurationSequences(rgId);
                if (releases.isEmpty()) {
                    // 对一个已有 RG，展开结果为空时无法证明「它确实没有 Release」还是
                    // MusicBrainz 临时返回了不完整数据。保守地视为未成功，保留 RG 快照供重试。
                    log.warn("候选 RG {} 未返回任何 Release，保留为未展开状态", rgId);
                    expansionFailed = true;
                    expanded.add(candidate);
                    continue;
                }
                int validReleaseCount = 0;
                for (MusicBrainzClient.AlbumDurationResult release : releases) {
                    if (release.getReleaseId() == null || release.getReleaseId().trim().isEmpty()) {
                        log.warn("候选 RG {} 返回了缺少 releaseId 的版本，已忽略", rgId);
                        continue;
                    }
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
                    validReleaseCount++;
                }
                if (validReleaseCount == 0) {
                    // 非空响应不等于可确认：没有 releaseId 的版本不能用于人工确认或 LLM 选择。
                    expansionFailed = true;
                    expanded.add(candidate);
                    log.warn("候选 RG {} 没有返回任何带 releaseId 的有效版本，保留供重试", rgId);
                } else {
                    alreadyExpandedGroups.add(rgId);
                }
            } catch (Exception e) {
                log.warn("展开候选 {} 失败（保留 RG 级快照，下次可重试）: {}", rgId, e.getMessage());
                expansionFailed = true;
                expanded.add(candidate);
            }
        }

        // 相似度高的排前面，没有相似度的排后面
        expanded.sort((a, b) -> Double.compare(
            b.getDurationSimilarity() == null ? -1 : b.getDurationSimilarity(),
            a.getDurationSimilarity() == null ? -1 : a.getDurationSimilarity()));

        item.setCandidates(expanded);
        // 即使本次有失败，已成功展开的部分仍然写回（人工可以先用这些），
        // 只是不置 expanded 标志，以便下次只重试剩下的 RG。
        item.setCandidatesExpanded(!expansionFailed);
        if (expansionFailed) {
            log.warn("候选展开未全部成功，条目保持「未展开」以便重试: {}", item.getFolderName());
            LogCollector.addLog("WARN", "候选版本展开未全部成功，可重试: " + item.getFolderName());
        }
        reviewQueue.update(item);
        return item;
    }

    /** 是否仍有「只有 releaseGroupId、没有 releaseId」的待展开候选 */
    private boolean hasPendingReleaseGroupCandidates(ReviewItem item) {
        if (item.getCandidates() == null) {
            return false;
        }
        for (ReviewItem.CandidateSnapshot candidate : item.getCandidates()) {
            if (candidate.getReleaseGroupId() != null && !candidate.getReleaseGroupId().isEmpty()
                && (candidate.getReleaseId() == null || candidate.getReleaseId().isEmpty())) {
                return true;
            }
        }
        return false;
    }

    /**
     * LLM 判定前的前置校验（审查修正 C2）。
     *
     * 「确实没有候选」与「候选展开失败」**绝不能共享 `choice=0` 的语义**：
     * 后者会让模型在一个空选项列表上被迫回答「都不是」，
     * 进而可能被当成「未收录精选集」，在 autoApply 打开时直接归档——
     * 这是一个由网络抖动导致的错误归档。
     */
    private void requireExpandedForJudgement(ReviewItem item) throws ResolutionException {
        boolean hadRgCandidates = false;
        boolean hasReleaseCandidates = false;
        for (ReviewItem.CandidateSnapshot candidate : item.getCandidates()) {
            if (candidate.getReleaseGroupId() != null && !candidate.getReleaseGroupId().isEmpty()) {
                hadRgCandidates = true;
            }
            if (candidate.getReleaseId() != null && !candidate.getReleaseId().isEmpty()) {
                hasReleaseCandidates = true;
            }
        }
        if (!item.isCandidatesExpanded() || (hadRgCandidates && !hasReleaseCandidates)) {
            log.warn("候选尚未成功展开到 Release 级，拒绝进行 LLM 判定（避免被误读为「没有候选」）: {}",
                item.getFolderName());
            throw new ResolutionException(503, "candidates.expand.failed");
        }
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

    // ==================== 阶段七：LLM 辅助判定 ====================

    /**
     * 用 LLM 做一次**封闭选择题**判定（阶段七 #22）。
     *
     * 三个关键约束：
     *   1. 先展开到 Release 级。仅 Release Group 级的候选即使被选中也无法完成确认，
     *      放进选项里只会诱导出一个无法执行的答案。
     *   2. 模型只能「选第几个 / 都不是」，不允许自由给出专辑名与年份。
     *   3. 结论默认只写回条目作为**建议**，条目仍保持 PENDING_REVIEW；
     *      只有 llm.album.autoApply 打开且置信度达标时才自动落盘。
     */
    public synchronized ReviewItem judgeWithLlm(String id) throws ResolutionException {
        if (!config.isLlmAlbumJudgeEnabled()) {
            throw new ResolutionException(501, "llm.judge.disabled");
        }
        if (!llmAlbumJudge.isAvailable()) {
            throw new ResolutionException(503, "llm.not.configured");
        }

        requirePending(id);
        // 封闭选择题需要 release 级候选（含官方时长序列，这是最强的判断依据）
        ReviewItem item = expandCandidates(id);
        // 展开失败时必须直接报错，不能拿一个空选项列表去问模型
        requireExpandedForJudgement(item);

        LlmAlbumJudge.Judgement judgement;
        try {
            judgement = llmAlbumJudge.judge(item);
        } catch (LlmClient.LlmException e) {
            log.warn("LLM 专辑判定调用失败: {} - {}", item.getFolderName(), e.getMessage());
            throw new ResolutionException(502, "llm.call.failed");
        }

        ReviewItem.LlmSuggestion suggestion = new ReviewItem.LlmSuggestion();
        suggestion.setEvaluatedAt(System.currentTimeMillis());
        suggestion.setModel(judgement.getModel());
        suggestion.setProvider(judgement.getProvider());
        suggestion.setChoiceIndex(judgement.getChoiceIndex());
        suggestion.setSuggestedReleaseId(judgement.getReleaseId());
        suggestion.setSuggestedReleaseGroupId(judgement.getReleaseGroupId());
        suggestion.setSuggestedTitle(judgement.getTitle());
        suggestion.setConfidence(judgement.getConfidence());
        suggestion.setUnreleasedCompilation(judgement.isUnreleasedCompilation());
        suggestion.setReason(judgement.getReason());
        item.setLlmSuggestion(suggestion);
        reviewQueue.update(item);

        LogCollector.addLog("INFO", "LLM 专辑判定: " + item.getFolderName() + " -> "
            + (judgement.getChoiceIndex() > 0 ? judgement.getTitle() : "候选里都不是"));

        if (!shouldAutoApply(judgement)) {
            return item;
        }

        // 审查修正 C1：**不能提前置 applied=true**。
        // 批处理部分失败时，finish() 走的是「保持 PENDING_REVIEW + update()」，
        // **不抛异常**，因此原来的 catch 根本兑不到，面板会显示「已自动落盘」但实际只写了一半。
        // 现在一律按**处置后的最终状态**回填。
        ReviewItem resolved;
        try {
            if (judgement.getChoiceIndex() > 0) {
                log.info("LLM 置信度 {} 达标，自动按选定版本归档: {}",
                    String.format("%.2f", judgement.getConfidence()), judgement.getTitle());
                resolved = confirmCandidate(id, judgement.getReleaseId(), judgement.getReleaseGroupId());
            } else {
                log.info("LLM 判定为「MusicBrainz 未收录的精选集」且置信度达标，自动按未收录归档: {}",
                    item.getFolderName());
                resolved = archiveAsUnverified(id);
            }
        } catch (ResolutionException e) {
            markApplied(item, false);
            throw e;
        }

        boolean completed = resolved.getStatus() == ReviewItem.Status.CONFIRMED
            || resolved.getStatus() == ReviewItem.Status.ARCHIVED_UNVERIFIED;
        if (!completed) {
            log.warn("LLM 自动落盘未全部成功，条目仍为 {}，applied 保持 false: {}",
                resolved.getStatus(), resolved.getFolderName());
        }
        markApplied(resolved, completed);
        return resolved;
    }

    /**
     * 回填 LLM 建议的 applied 标记。
     * 用 {@code resolved} 自己的 suggestion 引用，避免处置过程中条目对象被替换后写错对象。
     */
    private void markApplied(ReviewItem item, boolean applied) {
        if (item == null || item.getLlmSuggestion() == null) {
            return;
        }
        item.getLlmSuggestion().setApplied(applied);
        reviewQueue.update(item);
    }

    /**
     * 自动落盘的门槛（默认全部关闭）。
     *
     * 「都不是」只有在模型同时认为它是未收录精选集时才能自动归档；
     * 否则「都不是 + 不知道是什么」应该继续等人。
     */
    private boolean shouldAutoApply(LlmAlbumJudge.Judgement judgement) {
        if (!config.isLlmAlbumAutoApply()) {
            return false;
        }
        if (judgement.getConfidence() < config.getLlmAlbumAutoApplyMinConfidence()) {
            log.info("LLM 置信度 {} 低于自动落盘阈值 {}，保持待人工确认",
                String.format("%.2f", judgement.getConfidence()),
                config.getLlmAlbumAutoApplyMinConfidence());
            return false;
        }
        if (judgement.getChoiceIndex() > 0) {
            return judgement.getReleaseId() != null && !judgement.getReleaseId().isEmpty();
        }
        return judgement.isUnreleasedCompilation();
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
