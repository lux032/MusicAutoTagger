package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MbContributionItem;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.util.AudioFileOrdering;
import com.lux032.musicautotagger.util.FileNameSanitizer;
import com.lux032.musicautotagger.util.FileSystemUtils;
import com.lux032.musicautotagger.util.MetadataUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * MusicBrainz 缺失专辑/版本回馈闭环核心编排服务。
 */
@Slf4j
public class MusicBrainzContributionService implements AutoCloseable {

    private static final String COVER_DOWNLOAD_API = "/api/mb-contributions/cover";
    private static final double MIN_TRACK_MATCH_CONFIDENCE = 0.6;
    private static final double MIN_RECORDING_SEED_CONFIDENCE = 0.8;

    private final MusicConfig config;
    private final MbContributionStore store;
    private final MusicBrainzExistenceChecker checker;
    private final MusicBrainzReleaseBackfillService backfillService;
    private final AudioFingerprintService fingerprintService;
    private final ReviewQueueService reviewQueueService;
    private final TagWriterService tagWriter;
    private final FileSystemUtils fileSystemUtils;
    private final ExecutorService executor;
    private final Set<String> acoustIdInFlight = ConcurrentHashMap.newKeySet();

    public MusicBrainzContributionService(MusicConfig config,
                                          MbContributionStore store,
                                          MusicBrainzClient mbClient,
                                          TagWriterService tagWriter,
                                          ProcessedFileLogger processedLogger,
                                          AudioFingerprintService fingerprintService,
                                          ReviewQueueService reviewQueueService,
                                          FileSystemUtils fileSystemUtils) {
        this(config, store, new MusicBrainzExistenceChecker(mbClient),
            new MusicBrainzReleaseBackfillService(config, mbClient, tagWriter, processedLogger, fileSystemUtils),
            fingerprintService, reviewQueueService, tagWriter, fileSystemUtils);
    }

    MusicBrainzContributionService(MusicConfig config,
                                   MbContributionStore store,
                                   MusicBrainzExistenceChecker checker,
                                   MusicBrainzReleaseBackfillService backfillService,
                                   AudioFingerprintService fingerprintService,
                                   ReviewQueueService reviewQueueService,
                                   TagWriterService tagWriter,
                                   FileSystemUtils fileSystemUtils) {
        this.config = config;
        this.store = store;
        this.checker = checker;
        this.backfillService = backfillService;
        this.fingerprintService = fingerprintService;
        this.reviewQueueService = reviewQueueService;
        this.tagWriter = tagWriter;
        this.fileSystemUtils = fileSystemUtils;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mb-contribution-checker");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        recoverInterruptedCheckingItems();
    }

    /** 联网归档完成时由 RecoveryService 传递的轻量上下文快照。 */
    public record ArchiveSnapshot(
        String reviewItemId,
        ReviewItem.OnlineCandidate candidate,
        String finalAlbum,
        String finalArtist,
        List<String> finalAlbumArtists,
        String finalDate,
        String editionComment,
        Path archivedOutputDir,
        List<Path> archivedFiles,
        Map<String, MetadataUtils.EffectiveEntityMbid> effectiveEntityMbids,
        Map<String, ResolvedTrackSnapshot> resolvedTracksByOriginalPath,
        CoverCapture coverCapture
    ) { }

    public record ResolvedTrackSnapshot(
        String archivedFilePath,
        double preciseDurationSec,
        String existingRecordingId,
        List<String> composers,
        List<String> lyricists,
        List<String> arrangers,
        String composerSource,
        String lyricistSource,
        String arrangerSource
    ) { }

    public record CoverCapture(
        String origin,
        String publicUrl,
        String originPageUrl,
        byte[] rawUncompressedBytes
    ) { }

    /** 详情视图，包含原始条目及动态构造的 Seeding / CAA / Works 引导对象。 */
    public record DetailView(
        MbContributionItem item,
        MusicBrainzSeedBuilder.SeedPayload seedPayload,
        MusicBrainzCreditSanitizer.CoverArtGuide coverGuide,
        MusicBrainzCreditSanitizer.WorksGuide worksGuide,
        boolean acoustIdReady,
        boolean acoustIdUserKeySet
    ) { }

    /** 草稿编辑补丁。 */
    public record DraftPatch(
        String title,
        String comment,
        Boolean useExistingReleaseGroup,
        String releaseType,
        String releaseDate,
        String country,
        String mediaFormat,
        String label,
        String catalogNumber,
        String barcode,
        String language,
        String script,
        List<String> includedSourceUrls
    ) { }

    /** 启动恢复：重新调度崩溃前停留在 CHECKING 状态的条目，避免重启后永久卡在预检中。 */
    private void recoverInterruptedCheckingItems() {
        if (store == null) return;
        List<MbContributionItem> stuck = store.list(MbContributionItem.Status.CHECKING, false);
        for (MbContributionItem item : stuck) {
            if (item == null || item.getId() == null) continue;
            final String itemId = item.getId();
            if (!executor.isShutdown()) {
                executor.submit(() -> runExistenceCheckInternal(itemId, false));
            } else {
                try {
                    store.update(itemId, it -> {
                        if (it.getStatus() == MbContributionItem.Status.CHECKING) {
                            it.setCheckCase(MbContributionItem.CheckCase.ERROR);
                            it.setCheckCode("CHECK_INTERRUPTED");
                            it.setCheckMessage("上次预检因服务重启中断，可点击重新预检");
                            it.setStatus(MbContributionItem.Status.CHECK_FAILED);
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 联网确认归档完成后非阻塞触发存在性预检并按四类分流入队：
     * 在请求线程上仅做开关判断与任务投递，将文件探测、草稿落盘、封面备份与 MB 查询全部移至后台线程执行。
     */
    public Future<?> enqueueAfterOnlineArchive(ArchiveSnapshot snapshot) {
        if (snapshot == null || snapshot.candidate() == null) {
            return null;
        }
        MusicConfig.MbContributionSettings settings = config == null
            ? new MusicConfig.MbContributionSettings(true, true)
            : config.getMbContributionSettings();
        if (!settings.enabled() || !settings.autoCheckAfterArchive()) {
            return null;
        }
        if (executor.isShutdown()) {
            return null;
        }
        return executor.submit(() -> {
            try {
                MbContributionItem.ReleaseDraft draft = buildDraftFromSnapshot(snapshot);
                String caseDReason = MusicBrainzExistenceChecker.evaluateCaseDIneligibility(draft);
                if (caseDReason != null) {
                    log.debug("联网归档专辑不满足自动回馈 MusicBrainz 条件（Case D），已跳过: {} ({})",
                        draft.getTitle(), caseDReason);
                    return;
                }
                MbContributionItem initial = buildInitialItem(snapshot, draft);
                MbContributionItem saved = store.upsert(initial);
                saveCapturedCoverIfNeeded(saved.getId(), snapshot.coverCapture());
                runExistenceCheckInternal(saved.getId(), true);
            } catch (Exception e) {
                log.warn("后台处理 MusicBrainz 回馈入队失败: {}", e.getMessage());
            }
        });
    }

    /**
     * 从已确认的联网 {@link ReviewItem} 手动发起存在性预检并创建/刷新待回馈条目。
     */
    public DetailView createFromReviewItem(String reviewItemId) throws IOException, InterruptedException {
        if (reviewItemId == null || reviewItemId.isBlank()) {
            throw new MusicBrainzReleaseBackfillService.BackfillException(400, "review.item.id.required", "缺少待确认条目 ID");
        }
        if (reviewQueueService == null) {
            throw new MusicBrainzReleaseBackfillService.BackfillException(503, "review.queue.unavailable", "待确认队列不可用");
        }
        ReviewItem reviewItem = reviewQueueService.get(reviewItemId.trim());
        if (reviewItem == null) {
            throw new MusicBrainzReleaseBackfillService.BackfillException(404, "item.not.found", "未找到对应的待确认条目");
        }
        if (reviewItem.getStatus() != ReviewItem.Status.CONFIRMED
                || reviewItem.getVerificationSource() != ReviewItem.VerificationSource.ONLINE_SEARCH) {
            throw new MusicBrainzReleaseBackfillService.BackfillException(400, "mb.contribution.review.not.online.confirmed",
                "仅支持从已确认的联网归档条目发起 MusicBrainz 回馈");
        }

        // 若已存在活跃条目，直接重检并返回
        Optional<MbContributionItem> existing = store.findByReviewItemId(reviewItem.getId());
        if (existing.isPresent() && !existing.get().isTerminal()) {
            return recheck(existing.get().getId());
        }

        ReviewItem.OnlineCandidate chosen = findChosenCandidate(reviewItem);
        if (chosen == null) {
            throw new MusicBrainzReleaseBackfillService.BackfillException(400, "online.candidate.not.found",
                "该历史条目缺少联网候选明细，无法构造回馈草稿");
        }

        List<Path> archivedFiles = resolveArchivedFilesFromReviewItem(reviewItem);
        Path archivedDir = resolveCommonDir(archivedFiles, reviewItem);
        MbContributionItem.ReleaseDraft draft = buildDraftFromConfirmedReview(reviewItem, chosen, archivedFiles);
        MbContributionItem item = new MbContributionItem();
        item.setReviewItemId(reviewItem.getId());
        item.setArchivedOutputDir(archivedDir == null ? null : archivedDir.toAbsolutePath().normalize().toString());
        item.setArchivedFilePaths(archivedFiles.stream().map(p -> p.toAbsolutePath().normalize().toString()).toList());
        item.setStatus(MbContributionItem.Status.CHECKING);
        item.setDraft(draft);

        CoverCapture coverCapture = captureCoverFromConfirmedReview(reviewItem, chosen, archivedFiles);
        MbContributionItem saved = store.upsert(item);
        saveCapturedCoverIfNeeded(saved.getId(), coverCapture);

        runExistenceCheckInternal(saved.getId(), false);
        return getDetailView(saved.getId()).orElseThrow(() ->
            new MusicBrainzReleaseBackfillService.BackfillException(500, "mb.contribution.create.failed", "创建回馈条目失败"));
    }

    public List<MbContributionItem> list(MbContributionItem.Status statusFilter, boolean actionableOnly) {
        return store.list(statusFilter, actionableOnly);
    }

    public Map<String, Object> stats() {
        Map<String, Object> stats = store.stats();
        MusicConfig.MbContributionSettings settings = config == null
            ? new MusicConfig.MbContributionSettings(true, true)
            : config.getMbContributionSettings();
        stats.put("enabled", settings.enabled());
        stats.put("autoCheckAfterArchive", settings.autoCheckAfterArchive());
        stats.put("acoustIdUserKeySet", isAcoustIdUserKeySet());
        stats.put("acoustIdReady", isAcoustIdSubmitReady());
        return stats;
    }

    public Optional<DetailView> getDetailView(String id) {
        MbContributionItem item = store.get(id);
        if (item == null) {
            return Optional.empty();
        }
        return Optional.of(toDetailView(item));
    }

    public DetailView recheck(String id) throws IOException, InterruptedException {
        MbContributionItem existing = store.get(id);
        if (existing == null) {
            throw new MusicBrainzReleaseBackfillService.BackfillException(404, "mb.contribution.not.found", "回馈条目不存在");
        }
        runExistenceCheckInternal(existing.getId(), false);
        return getDetailView(existing.getId()).orElseThrow();
    }

    public DetailView updateDraft(String id, DraftPatch patch) throws IOException {
        if (patch == null) {
            throw new MusicBrainzReleaseBackfillService.BackfillException(400, "invalid.request", "缺少草稿更新参数");
        }
        Optional<MbContributionItem> updated = store.update(id, item -> {
            if (patch.useExistingReleaseGroup() != null) {
                item.setUseExistingReleaseGroup(patch.useExistingReleaseGroup());
            }
            MbContributionItem.ReleaseDraft d = item.getDraft();
            if (patch.title() != null) d.setTitle(trimToNull(patch.title()));
            if (patch.comment() != null) d.setComment(trimToNull(patch.comment()));
            if (patch.releaseType() != null) d.setReleaseType(trimToNull(patch.releaseType()));
            if (patch.releaseDate() != null) d.setReleaseDate(trimToNull(patch.releaseDate()));
            if (patch.country() != null) d.setCountry(trimToNull(patch.country()));
            if (patch.mediaFormat() != null) d.setMediaFormat(trimToNull(patch.mediaFormat()));
            if (patch.label() != null) d.setLabel(trimToNull(patch.label()));
            if (patch.catalogNumber() != null) d.setCatalogNumber(trimToNull(patch.catalogNumber()));
            if (patch.barcode() != null) d.setBarcode(trimToNull(patch.barcode()));
            if (patch.language() != null) d.setLanguage(trimToNull(patch.language()));
            if (patch.script() != null) d.setScript(trimToNull(patch.script()));
            if (patch.includedSourceUrls() != null && d.getSourceUrls() != null) {
                Set<String> wanted = new LinkedHashSet<>();
                for (String u : patch.includedSourceUrls()) {
                    if (u != null && !u.isBlank()) wanted.add(u.trim());
                }
                for (MbContributionItem.SourceUrlEntry src : d.getSourceUrls()) {
                    if (src != null && src.getUrl() != null) {
                        src.setIncluded(wanted.contains(src.getUrl().trim()));
                    }
                }
            }
        });
        if (updated.isEmpty()) {
            throw new MusicBrainzReleaseBackfillService.BackfillException(404, "mb.contribution.not.found", "回馈条目不存在");
        }
        return toDetailView(updated.get());
    }

    public record LinkReleaseResponse(DetailView detail, MusicBrainzReleaseBackfillService.BackfillResult backfill) { }

    public LinkReleaseResponse linkRelease(String id, String rawReleaseUrlOrMbid)
            throws IOException, InterruptedException {
        MbContributionItem item = store.get(id);
        if (item == null) {
            throw new MusicBrainzReleaseBackfillService.BackfillException(404, "mb.contribution.not.found", "回馈条目不存在");
        }
        // Case A 支持不传参数时默认使用预检命中的 Release MBID
        String effectiveInput = (rawReleaseUrlOrMbid == null || rawReleaseUrlOrMbid.isBlank())
            ? item.getMatchedReleaseId()
            : rawReleaseUrlOrMbid;
        MusicBrainzReleaseBackfillService.BackfillResult result = backfillService.linkAndBackfill(item, effectiveInput);
        long linkedNow = System.currentTimeMillis();
        MbContributionItem saved = store.update(item.getId(), it -> {
            it.setLinkedReleaseId(result.releaseId());
            it.setLinkedReleaseGroupId(result.releaseGroupId());
            it.setLinkedReleaseTitle(result.releaseTitle());
            it.setLinkedAt(linkedNow);
            it.setBackfilledFileCount(result.updatedFiles());
            it.setBackfilledTracks(result.trackMappings());
            // 重新关联 Release 时重置声纹提交状态，使新映射生效
            it.setAcoustIdMappingVersion(linkedNow);
            it.setAcoustIdSubmittedRecordingIds(List.of());
            it.setAcoustIdSubmittedAt(0L);
            it.setAcoustIdSubmittedCount(0);
            it.setAcoustIdLastError(null);
            if (!it.isTerminal()) {
                it.setStatus(MbContributionItem.Status.LINKED);
            }
        }).orElseThrow();
        return new LinkReleaseResponse(toDetailView(saved), result);
    }

    public record AcoustIdSubmitResponse(DetailView detail, AudioFingerprintService.AcoustIdSubmitResult result) { }

    public AcoustIdSubmitResponse submitAcoustId(String id) throws IOException, InterruptedException {
        MbContributionItem item = store.get(id);
        if (item == null) {
            throw new MusicBrainzReleaseBackfillService.BackfillException(404, "mb.contribution.not.found", "回馈条目不存在");
        }
        if (!acoustIdInFlight.add(item.getId())) {
            throw new MusicBrainzReleaseBackfillService.BackfillException(409, "acoustid.submit.already.running",
                "该专辑的 AcoustID 声纹正在提交中，请勿重复操作");
        }
        try {
            if (!isAcoustIdClientKeySet()) {
                throw new MusicBrainzReleaseBackfillService.BackfillException(400, "acoustid.client.key.missing",
                    "尚未配置 AcoustID API Key（acoustid.apiKey）");
            }
            if (!isAcoustIdUserKeySet()) {
                throw new MusicBrainzReleaseBackfillService.BackfillException(400, "acoustid.user.key.missing",
                    "尚未配置个人 AcoustID User API Key（acoustid.userApiKey），请前往设置页填写");
            }
            if (fingerprintService == null) {
                throw new MusicBrainzReleaseBackfillService.BackfillException(503, "acoustid.unavailable", "音频指纹服务不可用");
            }
            // 重新读取最新状态
            item = store.get(item.getId());
            if (item == null || item.getLinkedReleaseId() == null
                    || item.getBackfilledTracks() == null || item.getBackfilledTracks().isEmpty()) {
                throw new MusicBrainzReleaseBackfillService.BackfillException(400, "mb.contribution.not.linked",
                    "请先关联 MusicBrainz Release 并完成本地 Recording MBID 回填，再提交声纹");
            }

            long mappingVersion = item.getLinkedAt();
            Set<String> alreadySubmitted = new LinkedHashSet<>();
            if (item.getAcoustIdMappingVersion() == mappingVersion && item.getAcoustIdSubmittedRecordingIds() != null) {
                alreadySubmitted.addAll(item.getAcoustIdSubmittedRecordingIds());
            }

            List<AudioFingerprintService.AcoustIdSubmissionItem> batch = new ArrayList<>();
            int validMappedTracks = 0;
            for (MbContributionItem.LinkedTrackMapping track : item.getBackfilledTracks()) {
                if (track == null || !meaningful(track.getLocalFilePath())) continue;
                String recMbid = MusicBrainzArtistCache.normalizeValidMbid(track.getRecordingId());
                if (recMbid == null) continue;
                Optional<Path> verifiedFile = MusicBrainzReleaseBackfillService.resolveRealFileUnderOutputRoot(
                    config, track.getLocalFilePath());
                if (verifiedFile.isEmpty()) continue;
                // 校验当前落盘音频标签仍绑定同一 Recording MBID，避免文件被替换后错提声纹
                if (tagWriter != null) {
                    MusicMetadata currentTags = tagWriter.readTags(verifiedFile.get().toFile());
                    String diskRecId = currentTags == null ? null : MusicBrainzArtistCache.normalizeValidMbid(currentTags.getRecordingId());
                    if (diskRecId != null && !recMbid.equals(diskRecId)) {
                        log.warn("跳过提交 AcoustID（本地文件 Recording MBID 已变更）: {}", verifiedFile.get().getFileName());
                        continue;
                    }
                }
                validMappedTracks++;
                if (alreadySubmitted.contains(recMbid)) {
                    continue;
                }
                AudioFingerprintService.AudioFingerprint fp = fingerprintService.generateFingerprint(verifiedFile.get().toFile());
                if (fp != null && fp.getDuration() > 0 && meaningful(fp.getFingerprint())) {
                    batch.add(new AudioFingerprintService.AcoustIdSubmissionItem(fp.getDuration(), fp.getFingerprint(), recMbid));
                }
            }
            if (validMappedTracks > 0 && batch.isEmpty() && !alreadySubmitted.isEmpty()) {
                throw new MusicBrainzReleaseBackfillService.BackfillException(409, "acoustid.already.submitted",
                    "该专辑全部曲目声纹已提交至 AcoustID，无需重复提交");
            }
            if (batch.isEmpty()) {
                throw new MusicBrainzReleaseBackfillService.BackfillException(400, "acoustid.submit.no.valid.tracks",
                    "没有可提取指纹且已绑定 Recording MBID 的本地音频文件");
            }

            final String itemId = item.getId();
            try {
                AudioFingerprintService.AcoustIdSubmitResult res = fingerprintService.submitFingerprints(batch, confirmedMbids -> {
                    if (confirmedMbids == null || confirmedMbids.isEmpty()) return;
                    store.update(itemId, it -> {
                        if (it.getLinkedAt() != mappingVersion) return;
                        Set<String> merged = new LinkedHashSet<>(it.getAcoustIdSubmittedRecordingIds());
                        merged.addAll(confirmedMbids);
                        it.setAcoustIdMappingVersion(mappingVersion);
                        it.setAcoustIdSubmittedRecordingIds(new ArrayList<>(merged));
                        it.setAcoustIdSubmittedCount(merged.size());
                        it.setAcoustIdSubmittedAt(System.currentTimeMillis());
                        it.setAcoustIdLastError(null);
                    });
                });
                MbContributionItem saved = store.update(itemId, it -> {
                    Set<String> merged = new LinkedHashSet<>(it.getAcoustIdSubmittedRecordingIds());
                    for (AudioFingerprintService.AcoustIdSubmissionItem sub : batch) {
                        merged.add(sub.recordingMbid());
                    }
                    it.setAcoustIdMappingVersion(mappingVersion);
                    it.setAcoustIdSubmittedRecordingIds(new ArrayList<>(merged));
                    it.setAcoustIdSubmittedAt(System.currentTimeMillis());
                    it.setAcoustIdSubmittedCount(merged.size());
                    it.setAcoustIdLastError(null);
                }).orElseThrow();
                LogCollector.addLog("SUCCESS", String.format("已向 AcoustID 提交 %d 首曲目声纹: %s",
                    res.submittedCount(), saved.getDraft().getTitle()));
                return new AcoustIdSubmitResponse(toDetailView(saved), res);
            } catch (AudioFingerprintService.AcoustIdSubmitException e) {
                store.update(itemId, it -> it.setAcoustIdLastError(e.getErrorCode()));
                throw new MusicBrainzReleaseBackfillService.BackfillException(
                    e.getHttpStatus(), e.getErrorCode(), e.getErrorCode());
            } catch (IOException e) {
                store.update(itemId, it -> it.setAcoustIdLastError("acoustid.submit.failed"));
                throw new MusicBrainzReleaseBackfillService.BackfillException(
                    502, "acoustid.submit.failed", "acoustid.submit.failed");
            }
        } finally {
            acoustIdInFlight.remove(id);
        }
    }

    public DetailView complete(String id) throws IOException {
        Optional<MbContributionItem> updated = store.update(id, it -> it.setStatus(MbContributionItem.Status.COMPLETED));
        if (updated.isEmpty()) {
            throw new MusicBrainzReleaseBackfillService.BackfillException(404, "mb.contribution.not.found", "回馈条目不存在");
        }
        return toDetailView(updated.get());
    }

    public DetailView dismiss(String id) throws IOException {
        Optional<MbContributionItem> updated = store.update(id, it -> it.setStatus(MbContributionItem.Status.DISMISSED));
        if (updated.isEmpty()) {
            throw new MusicBrainzReleaseBackfillService.BackfillException(404, "mb.contribution.not.found", "回馈条目不存在");
        }
        return toDetailView(updated.get());
    }

    public Optional<CoverDownload> resolveCoverBackupFile(String id) {
        MbContributionItem item = store.get(id);
        if (item == null) {
            return Optional.empty();
        }
        Optional<Path> backup = store.findExistingCoverBackup(item);
        if (backup.isEmpty()) {
            return Optional.empty();
        }
        String mime = (item.getCover() != null && meaningful(item.getCover().getMime()))
            ? item.getCover().getMime()
            : "image/jpeg";
        String downloadName = (item.getDraft() != null && meaningful(item.getDraft().getTitle())
            ? FileNameSanitizer.sanitize(item.getDraft().getTitle(), "cover")
            : "cover") + "-" + backup.get().getFileName();
        return Optional.of(new CoverDownload(backup.get(), mime, downloadName));
    }

    public record CoverDownload(Path file, String mime, String downloadFileName) { }

    private void runExistenceCheckInternal(String itemId, boolean enforceCaseD) {
        MbContributionItem current = store.get(itemId);
        if (current == null || current.isTerminal()) {
            return;
        }
        final long startedAt = System.currentTimeMillis();
        final long expectedRevision = current.getRevision();
        final String previousRgId = current.getMatchedReleaseGroupId();
        try {
            MusicBrainzExistenceChecker.CheckResult result = checker.check(current.getDraft(), enforceCaseD);
            store.update(itemId, it -> {
                // 防止并发检查重新打开已忽略/已完成的终态条目，或用过期结果覆盖更新的检查
                if (it.isTerminal()) {
                    return;
                }
                if (it.getCheckedAt() > startedAt && it.getRevision() != expectedRevision) {
                    return;
                }
                it.setCheckCase(result.checkCase());
                it.setCheckCode(result.checkCode());
                it.setCheckMessage(result.message());
                it.setCheckedAt(System.currentTimeMillis());
                it.setMatchedReleaseId(result.matchedReleaseId());
                it.setMatchedReleaseGroupId(result.matchedReleaseGroupId());
                it.setMatchedReleaseTitle(result.matchedReleaseTitle());
                it.setMatchedReleaseArtist(result.matchedReleaseArtist());
                it.setMatchedReleaseDate(result.matchedReleaseDate());
                it.setMatchedReleaseCountry(result.matchedReleaseCountry());
                it.setMatchedTrackCount(result.matchedTrackCount());
                it.setExistingGroupReleases(result.existingGroupReleases());
                if (it.getLinkedReleaseId() != null && !it.getLinkedReleaseId().isBlank()) {
                    it.setStatus(MbContributionItem.Status.LINKED);
                    return;
                }
                switch (result.checkCase()) {
                    case EXISTING_RELEASE -> it.setStatus(MbContributionItem.Status.EXISTING_MATCH);
                    case MISSING_EDITION -> {
                        // 若命中的仍是同一个 Release Group，保留用户此前手工切换的挂入/新建选择
                        if (previousRgId == null || !previousRgId.equals(result.matchedReleaseGroupId())) {
                            it.setUseExistingReleaseGroup(true);
                        }
                        it.setStatus(MbContributionItem.Status.READY_TO_SEED);
                    }
                    case MISSING_RELEASE_GROUP -> {
                        it.setUseExistingReleaseGroup(false);
                        it.setStatus(MbContributionItem.Status.READY_TO_SEED);
                    }
                    case NOT_ELIGIBLE -> it.setStatus(MbContributionItem.Status.DISMISSED);
                    case ERROR -> it.setStatus(MbContributionItem.Status.CHECK_FAILED);
                }
            });
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            try {
                store.update(itemId, it -> {
                    if (it.isTerminal() || (it.getCheckedAt() > startedAt && it.getRevision() != expectedRevision)) return;
                    it.setCheckCase(MbContributionItem.CheckCase.ERROR);
                    it.setCheckCode("CHECK_INTERRUPTED");
                    it.setCheckMessage("存在性预检被中断，可手动点击重新预检");
                    it.setStatus(MbContributionItem.Status.CHECK_FAILED);
                });
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            log.warn("MusicBrainz 存在性预检失败 ({}): {}", itemId, e.getMessage());
            try {
                store.update(itemId, it -> {
                    if (it.isTerminal() || (it.getCheckedAt() > startedAt && it.getRevision() != expectedRevision)) return;
                    it.setCheckCase(MbContributionItem.CheckCase.ERROR);
                    it.setCheckCode("CHECK_ERROR");
                    it.setCheckMessage("预检失败: " + e.getMessage());
                    it.setStatus(MbContributionItem.Status.CHECK_FAILED);
                });
            } catch (Exception ignored) {
            }
        }
    }

    private MbContributionItem buildInitialItem(ArchiveSnapshot snapshot, MbContributionItem.ReleaseDraft draft) {
        MbContributionItem item = new MbContributionItem();
        item.setId(UUID.randomUUID().toString());
        item.setReviewItemId(snapshot.reviewItemId());
        item.setArchivedOutputDir(snapshot.archivedOutputDir() == null
            ? null : snapshot.archivedOutputDir().toAbsolutePath().normalize().toString());
        List<String> filePaths = new ArrayList<>();
        if (snapshot.archivedFiles() != null) {
            for (Path p : snapshot.archivedFiles()) {
                if (p != null) filePaths.add(p.toAbsolutePath().normalize().toString());
            }
        }
        item.setArchivedFilePaths(filePaths);
        item.setStatus(MbContributionItem.Status.CHECKING);
        item.setDraft(draft);
        return item;
    }

    private void saveCapturedCoverIfNeeded(String itemId, CoverCapture capture) {
        if (capture == null) return;
        MbContributionItem.CoverInfo info = new MbContributionItem.CoverInfo();
        info.setOrigin(capture.origin());
        info.setOriginPageUrl(MusicBrainzSeedBuilder.canonicalSafePublicHttpUrl(capture.originPageUrl()));
        String safePublic = MusicBrainzSeedBuilder.canonicalSafePublicHttpUrl(capture.publicUrl());
        if (safePublic != null) {
            info.setPublicUrl(safePublic);
        } else if (capture.rawUncompressedBytes() != null && capture.rawUncompressedBytes().length > 0) {
            store.saveCoverBackup(itemId, capture.rawUncompressedBytes()).ifPresent(saved -> {
                info.setLocalBackupFileName(saved.fileName());
                info.setMime(saved.mime());
                info.setWidth(saved.width());
                info.setHeight(saved.height());
                info.setBytes(saved.bytes());
            });
        }
        if (meaningful(info.getPublicUrl()) || meaningful(info.getLocalBackupFileName())) {
            store.update(itemId, it -> it.setCover(info));
        }
    }

    private MbContributionItem.ReleaseDraft buildDraftFromSnapshot(ArchiveSnapshot snapshot) {
        ReviewItem.OnlineCandidate cand = snapshot.candidate();
        MbContributionItem.ReleaseDraft draft = new MbContributionItem.ReleaseDraft();
        draft.setTitle(meaningful(snapshot.finalAlbum()) ? snapshot.finalAlbum().trim() : cand.getTitle());
        draft.setComment(trimToNull(snapshot.editionComment() != null ? snapshot.editionComment() : cand.getEdition()));
        draft.setAlbumArtist(meaningful(snapshot.finalArtist()) ? snapshot.finalArtist().trim()
            : (meaningful(cand.getAlbumArtist()) ? cand.getAlbumArtist().trim() : cand.getArtist()));
        List<String> albumArtists = (snapshot.finalAlbumArtists() != null && !snapshot.finalAlbumArtists().isEmpty())
            ? snapshot.finalAlbumArtists()
            : (cand.getAlbumArtists() != null && !cand.getAlbumArtists().isEmpty() ? cand.getAlbumArtists() : cand.getArtists());
        draft.setAlbumArtists(albumArtists);
        draft.setReleaseDate(meaningful(snapshot.finalDate()) ? snapshot.finalDate().trim() : cand.getReleaseDate());
        draft.setCountry(trimToNull(cand.getCountry()));
        draft.setReleaseType(trimToNull(cand.getReleaseType()));
        draft.setMediaFormat(inferMediaFormat(cand.getMediaFormat(), cand.getEdition(), snapshot.archivedFiles()));
        draft.setLabel(trimToNull(cand.getLabel()));
        draft.setCatalogNumber(trimToNull(cand.getCatalogNumber()));
        draft.setBarcode(trimToNull(cand.getBarcode()));
        draft.setOfficialCandidate(cand.isOfficialCandidate());
        draft.setTrackCoverage(cand.getTrackCoverage());

        // 若联网候选缺 barcode/label/catno，尝试从已归档音频原有标签保守补全
        fillMissingReleaseFieldsFromArchivedFiles(draft, snapshot.archivedFiles());

        draft.setSourceUrls(buildSourceEntries(cand.getSources()));
        draft.setEntityMbids(buildEntityMbidRefs(cand.getEntityMbids(), snapshot.effectiveEntityMbids()));

        List<MbContributionItem.ContributionTrack> tracks = new ArrayList<>();
        Map<String, ResolvedTrackSnapshot> resolvedMap = snapshot.resolvedTracksByOriginalPath() == null
            ? Map.of() : snapshot.resolvedTracksByOriginalPath();
        if (cand.getTracks() != null) {
            int fallbackTrackNo = 1;
            for (ReviewItem.OnlineTrack ot : cand.getTracks()) {
                if (ot == null) continue;
                MbContributionItem.ContributionTrack ct = new MbContributionItem.ContributionTrack();
                ct.setDiscNo(ot.getDiscNo() > 0 ? ot.getDiscNo() : 1);
                ct.setTrackNo(ot.getTrackNo() > 0 ? ot.getTrackNo() : fallbackTrackNo);
                fallbackTrackNo = ct.getTrackNo() + 1;
                ct.setTitle(ot.getTitle());

                // 优先保留单曲特异署名（避免客串/合作曲目被专辑艺人列表覆盖）
                String trackDisplayArtist = meaningful(ot.getArtist()) ? ot.getArtist().trim() : draft.getAlbumArtist();
                ct.setArtist(trackDisplayArtist);
                if (ot.getArtists() != null && !ot.getArtists().isEmpty()) {
                    ct.setArtists(ot.getArtists());
                } else if (meaningful(ot.getArtist())
                        && !ot.getArtist().trim().equalsIgnoreCase(draft.getAlbumArtist() == null ? "" : draft.getAlbumArtist().trim())) {
                    ct.setArtists(List.of(ot.getArtist().trim()));
                } else {
                    ct.setArtists(draft.getAlbumArtists());
                }
                ct.setMatchedConfidence(ot.getMatchConfidence());

                ResolvedTrackSnapshot resolved = ot.getMatchedFilePath() == null ? null : resolvedMap.get(ot.getMatchedFilePath());
                if (resolved != null) {
                    ct.setLocalFilePath(resolved.archivedFilePath());
                    // 仅对高置信匹配曲目采信本地精准时长与经查证的 Recording MBID
                    if (ot.getMatchConfidence() >= MIN_TRACK_MATCH_CONFIDENCE) {
                        double durSec = resolved.preciseDurationSec();
                        if (durSec <= 0 && meaningful(resolved.archivedFilePath())) {
                            Optional<Path> verified = MusicBrainzReleaseBackfillService.resolveRealFileUnderOutputRoot(
                                config, resolved.archivedFilePath());
                            if (verified.isPresent()) {
                                Double probed = TagWriterService.readPreciseDuration(verified.get().toFile());
                                if (probed != null && probed > 0) durSec = probed;
                            }
                        }
                        if (durSec > 0) {
                            ct.setDurationMs(Math.round(durSec * 1000.0));
                            ct.setDurationSource("LOCAL_PRECISE");
                        }
                    }
                    if (ot.getMatchConfidence() >= MIN_RECORDING_SEED_CONFIDENCE) {
                        ct.setExistingRecordingId(MusicBrainzArtistCache.normalizeValidMbid(resolved.existingRecordingId()));
                    }
                    ct.setComposers(resolved.composers());
                    ct.setLyricists(resolved.lyricists());
                    ct.setArrangers(resolved.arrangers());
                    ct.setComposerSource(resolved.composerSource());
                    ct.setLyricistSource(resolved.lyricistSource());
                    ct.setArrangerSource(resolved.arrangerSource());
                } else {
                    copyCreditsFromOnlineTrack(ct, ot);
                }
                if (ct.getDurationMs() <= 0 && ot.getDuration() != null && ot.getDuration() > 0) {
                    ct.setDurationMs(ot.getDuration() * 1000L);
                    ct.setDurationSource("ONLINE");
                } else if (ct.getDurationMs() <= 0) {
                    ct.setDurationSource("UNKNOWN");
                }
                populateCreditEvidence(ct, ot.getCreditDetail());
                tracks.add(ct);
            }
        }
        draft.setTracks(tracks);
        return draft;
    }

    private MbContributionItem.ReleaseDraft buildDraftFromConfirmedReview(ReviewItem reviewItem,
                                                                          ReviewItem.OnlineCandidate cand,
                                                                          List<Path> archivedFiles) {
        Map<String, MetadataUtils.EffectiveEntityMbid> effectiveMbids =
            MetadataUtils.buildEffectiveEntityMbids(cand.getEntityMbids(), Map.of());
        Map<String, ResolvedTrackSnapshot> resolvedMap = new LinkedHashMap<>();
        Map<String, String> outputByIsolated = new LinkedHashMap<>();
        if (reviewItem.getCommittedTrackRecords() != null) {
            for (ReviewItem.CommittedTrackRecord tr : reviewItem.getCommittedTrackRecords()) {
                if (tr != null && meaningful(tr.getIsolatedPath()) && meaningful(tr.getOutputPath())) {
                    outputByIsolated.put(tr.getIsolatedPath(), tr.getOutputPath());
                }
            }
        }
        if (cand.getTracks() != null) {
            for (ReviewItem.OnlineTrack ot : cand.getTracks()) {
                if (ot == null || !meaningful(ot.getMatchedFilePath())) continue;
                String outPath = outputByIsolated.get(ot.getMatchedFilePath());
                if (outPath == null) continue;
                // 严格使用真实路径校验器，拒绝越出 outputDirectory 的路径或符号链接
                Optional<Path> verified = MusicBrainzReleaseBackfillService.resolveRealFileUnderOutputRoot(config, outPath);
                if (verified.isEmpty()) continue;
                File archivedFile = verified.get().toFile();
                Double dur = TagWriterService.readPreciseDuration(archivedFile);
                MusicMetadata md = tagWriter == null ? null : tagWriter.readTags(archivedFile);
                resolvedMap.put(ot.getMatchedFilePath(), new ResolvedTrackSnapshot(
                    archivedFile.getAbsolutePath(),
                    dur != null && dur > 0 ? dur : (md != null ? md.getDuration() : 0.0),
                    null, // 历史联网归档不盲目采信未核验的本地 Recording 标签
                    md != null && md.getComposers() != null && !md.getComposers().isEmpty() ? md.getComposers() : ot.getComposers(),
                    md != null && md.getLyricists() != null && !md.getLyricists().isEmpty() ? md.getLyricists() : ot.getLyricists(),
                    md != null && md.getArrangers() != null && !md.getArrangers().isEmpty() ? md.getArrangers() : ot.getArrangers(),
                    creditSourceLabel(ot.getCreditDetail() == null ? null : ot.getCreditDetail().getComposers()),
                    creditSourceLabel(ot.getCreditDetail() == null ? null : ot.getCreditDetail().getLyricists()),
                    creditSourceLabel(ot.getCreditDetail() == null ? null : ot.getCreditDetail().getArrangers())
                ));
            }
        }
        ArchiveSnapshot snapshot = new ArchiveSnapshot(
            reviewItem.getId(), cand,
            meaningful(reviewItem.getResolvedAlbumTitle()) ? reviewItem.getResolvedAlbumTitle() : cand.getTitle(),
            meaningful(reviewItem.getResolvedAlbumArtist()) ? reviewItem.getResolvedAlbumArtist() : cand.getAlbumArtist(),
            cand.getAlbumArtists(),
            meaningful(reviewItem.getResolvedReleaseDate()) ? reviewItem.getResolvedReleaseDate() : cand.getReleaseDate(),
            cand.getEdition(),
            resolveCommonDir(archivedFiles, reviewItem),
            archivedFiles,
            effectiveMbids,
            resolvedMap,
            null);
        return buildDraftFromSnapshot(snapshot);
    }

    private CoverCapture captureCoverFromConfirmedReview(ReviewItem reviewItem,
                                                         ReviewItem.OnlineCandidate cand,
                                                         List<Path> archivedFiles) {
        ReviewItem.MissingCover mc = reviewItem.getMissingCover();
        if (mc != null && mc.isConfirmedNoCover()) {
            return null;
        }
        // 优先匹配缺封面队列中最终选定的封面候选
        if (mc != null && meaningful(mc.getSelectedCoverSha256()) && mc.getCoverCandidates() != null) {
            for (CoverCandidateView v : mc.getCoverCandidates()) {
                if (v != null && mc.getSelectedCoverSha256().equalsIgnoreCase(v.getSha256())) {
                    if (meaningful(v.getSourceUrl())) {
                        return new CoverCapture(v.getOrigin() == null ? "URL" : v.getOrigin().name(),
                            v.getSourceUrl(), cand.getCoverSourceUrl(), null);
                    }
                    return extractEmbeddedFallbackCover(archivedFiles, v.getOrigin() == null ? "EMBEDDED" : v.getOrigin().name());
                }
            }
        }
        // 其次匹配联网确认时显式选定的封面 SHA
        if (meaningful(cand.getSelectedCoverSha256())) {
            if (cand.getCoverCandidates() != null) {
                for (CoverCandidateView v : cand.getCoverCandidates()) {
                    if (v != null && cand.getSelectedCoverSha256().equalsIgnoreCase(v.getSha256())) {
                        if (meaningful(v.getSourceUrl())) {
                            return new CoverCapture(v.getOrigin() == null ? "URL" : v.getOrigin().name(),
                                v.getSourceUrl(), cand.getCoverSourceUrl(), null);
                        }
                        return extractEmbeddedFallbackCover(archivedFiles, v.getOrigin() == null ? "EMBEDDED" : v.getOrigin().name());
                    }
                }
            }
            return extractEmbeddedFallbackCover(archivedFiles, "EMBEDDED");
        }
        // 仅当无显式选图记录且确实已嵌图时，优先采用归档后实际内嵌封面或候选公网 URL
        CoverCapture embedded = extractEmbeddedFallbackCover(archivedFiles, "EMBEDDED");
        if (embedded != null) {
            return embedded;
        }
        if (meaningful(cand.getCoverUrl())) {
            return new CoverCapture("LLM_URL", cand.getCoverUrl(), cand.getCoverSourceUrl(), null);
        }
        return null;
    }

    private static CoverCapture extractEmbeddedFallbackCover(List<Path> archivedFiles, String origin) {
        if (archivedFiles == null) return null;
        for (Path p : archivedFiles) {
            if (p == null || !Files.isRegularFile(p)) continue;
            byte[] emb = extractRawEmbeddedCover(p.toFile());
            if (emb != null && emb.length > 0) {
                return new CoverCapture(origin, null, null, emb);
            }
        }
        return null;
    }

    private static byte[] extractRawEmbeddedCover(File audioFile) {
        try {
            org.jaudiotagger.audio.AudioFile af = org.jaudiotagger.audio.AudioFileIO.read(audioFile);
            org.jaudiotagger.tag.Tag tag = af == null ? null : af.getTag();
            org.jaudiotagger.tag.images.Artwork art = tag == null ? null : tag.getFirstArtwork();
            return art == null ? null : art.getBinaryData();
        } catch (Exception e) {
            log.debug("读取音频未压缩内嵌封面失败 ({}): {}", audioFile, e.getMessage());
            return null;
        }
    }

    private void fillMissingReleaseFieldsFromArchivedFiles(MbContributionItem.ReleaseDraft draft, List<Path> archivedFiles) {
        if (tagWriter == null || archivedFiles == null || archivedFiles.isEmpty()) return;
        for (Path p : archivedFiles) {
            if (p == null) continue;
            Optional<Path> verified = MusicBrainzReleaseBackfillService.resolveRealFileUnderOutputRoot(config, p.toString());
            if (verified.isEmpty()) continue;
            MusicMetadata md = tagWriter.readTags(verified.get().toFile());
            if (md == null) continue;
            if (!meaningful(draft.getBarcode()) && meaningful(md.getBarcode())) {
                draft.setBarcode(md.getBarcode().trim());
            }
            if (!meaningful(draft.getCatalogNumber()) && meaningful(md.getCatalogNumber())) {
                draft.setCatalogNumber(md.getCatalogNumber().trim());
            }
            if (!meaningful(draft.getLabel()) && meaningful(md.getRecordLabel())) {
                draft.setLabel(md.getRecordLabel().trim());
            }
            break;
        }
    }

    private static List<MbContributionItem.SourceUrlEntry> buildSourceEntries(List<ReviewItem.OnlineEvidence> sources) {
        List<MbContributionItem.SourceUrlEntry> entries = new ArrayList<>();
        if (sources == null) return entries;
        Set<String> seen = new LinkedHashSet<>();
        for (ReviewItem.OnlineEvidence ev : sources) {
            if (ev == null || !meaningful(ev.getUrl())) continue;
            String url = ev.getUrl().trim();
            if (!seen.add(url)) continue;
            MbContributionItem.SourceUrlEntry e = new MbContributionItem.SourceUrlEntry();
            e.setUrl(url);
            e.setDomain(ev.getDomain());
            e.setTitle(ev.getTitle());
            e.setReliability(ev.getReliability());
            e.setIncluded("HIGH".equalsIgnoreCase(ev.getReliability()) || "MEDIUM".equalsIgnoreCase(ev.getReliability()));
            entries.add(e);
        }
        return entries;
    }

    private static Map<String, MbContributionItem.EntityMbidRef> buildEntityMbidRefs(
            Map<String, ReviewItem.EntityMbidResolution> resolutions,
            Map<String, MetadataUtils.EffectiveEntityMbid> effective) {
        Map<String, MbContributionItem.EntityMbidRef> map = new LinkedHashMap<>();
        if (effective != null) {
            for (Map.Entry<String, MetadataUtils.EffectiveEntityMbid> entry : effective.entrySet()) {
                MetadataUtils.EffectiveEntityMbid eff = entry.getValue();
                if (eff == null) continue;
                String valid = MusicBrainzArtistCache.normalizeValidMbid(eff.mbid());
                if (valid == null) continue;
                ReviewItem.EntityMbidResolution res = resolutions == null ? null : resolutions.get(entry.getKey());
                MbContributionItem.EntityMbidRef ref = new MbContributionItem.EntityMbidRef();
                ref.setMbid(valid);
                ref.setDisplayName(res != null ? res.getDisplayName() : null);
                ref.setCanonicalName(res != null ? res.getSelectedName() : null);
                ref.setSortName(meaningful(eff.sortName()) ? eff.sortName() : (res == null ? null : res.getSelectedSortName()));
                ref.setDisambiguation(res == null ? null : res.getSelectedDisambiguation());
                ref.setSource(res != null && res.getSource() != null ? res.getSource().name() : "MANUAL");
                map.put(entry.getKey(), ref);
            }
        }
        return map;
    }

    private static void copyCreditsFromOnlineTrack(MbContributionItem.ContributionTrack ct, ReviewItem.OnlineTrack ot) {
        ReviewItem.TrackCreditDetail detail = ot.getCreditDetail();
        if (detail != null) {
            applyFieldToTrack(detail.getComposers(), ot.getComposers(), ct::setComposers, ct::setComposerSource);
            applyFieldToTrack(detail.getLyricists(), ot.getLyricists(), ct::setLyricists, ct::setLyricistSource);
            applyFieldToTrack(detail.getArrangers(), ot.getArrangers(), ct::setArrangers, ct::setArrangerSource);
        } else {
            ct.setComposers(ot.getComposers());
            ct.setLyricists(ot.getLyricists());
            ct.setArrangers(ot.getArrangers());
            ct.setComposerSource(ot.getComposers() == null || ot.getComposers().isEmpty() ? "NONE" : "ONLINE_PROBABLE");
            ct.setLyricistSource(ot.getLyricists() == null || ot.getLyricists().isEmpty() ? "NONE" : "ONLINE_PROBABLE");
            ct.setArrangerSource(ot.getArrangers() == null || ot.getArrangers().isEmpty() ? "NONE" : "ONLINE_PROBABLE");
        }
    }

    private static void applyFieldToTrack(ReviewItem.CreditFieldResult field,
                                          List<String> fallbackNames,
                                          java.util.function.Consumer<List<String>> namesSetter,
                                          java.util.function.Consumer<String> sourceSetter) {
        if (field == null) {
            namesSetter.accept(fallbackNames == null ? List.of() : fallbackNames);
            sourceSetter.accept(fallbackNames == null || fallbackNames.isEmpty() ? "NONE" : "ONLINE_PROBABLE");
            return;
        }
        String label = creditSourceLabel(field);
        sourceSetter.accept(label);
        if ("ONLINE_VERIFIED".equals(label) || "ONLINE_PROBABLE".equals(label)) {
            namesSetter.accept(field.getNames() != null && !field.getNames().isEmpty()
                ? field.getNames() : (fallbackNames == null ? List.of() : fallbackNames));
        } else {
            namesSetter.accept(List.of());
        }
    }

    public static String creditSourceLabel(ReviewItem.CreditFieldResult field) {
        if (field == null || field.getStatus() == null) return "NONE";
        return switch (field.getStatus()) {
            case VERIFIED -> "ONLINE_VERIFIED";
            case PROBABLE -> "ONLINE_PROBABLE";
            case CONFLICT -> "CONFLICT";
            case NOT_FOUND -> "NOT_FOUND";
            case NOT_SEARCHED -> "NOT_SEARCHED";
        };
    }

    /** 仅收集通过核验（VERIFIED/PROBABLE）角色的证据并按角色隔离存储，严禁混入冲突或未核验字段引文。 */
    private static void populateCreditEvidence(MbContributionItem.ContributionTrack ct, ReviewItem.TrackCreditDetail detail) {
        if (detail == null) return;
        RoleEvidence comp = extractRoleEvidence(detail.getComposers());
        RoleEvidence lyr = extractRoleEvidence(detail.getLyricists());
        RoleEvidence arr = extractRoleEvidence(detail.getArrangers());
        ct.setComposerEvidenceUrls(comp.urls());
        ct.setComposerEvidenceQuotes(comp.quotes());
        ct.setLyricistEvidenceUrls(lyr.urls());
        ct.setLyricistEvidenceQuotes(lyr.quotes());
        ct.setArrangerEvidenceUrls(arr.urls());
        ct.setArrangerEvidenceQuotes(arr.quotes());

        Set<String> allUrls = new LinkedHashSet<>();
        Set<String> allQuotes = new LinkedHashSet<>();
        allUrls.addAll(comp.urls());
        allUrls.addAll(lyr.urls());
        allUrls.addAll(arr.urls());
        allQuotes.addAll(comp.quotes());
        allQuotes.addAll(lyr.quotes());
        allQuotes.addAll(arr.quotes());
        ct.setCreditEvidenceUrls(new ArrayList<>(allUrls));
        ct.setCreditEvidenceQuotes(new ArrayList<>(allQuotes));
    }

    private record RoleEvidence(List<String> urls, List<String> quotes) { }

    private static RoleEvidence extractRoleEvidence(ReviewItem.CreditFieldResult field) {
        if (field == null || field.getEvidence() == null) {
            return new RoleEvidence(List.of(), List.of());
        }
        String label = creditSourceLabel(field);
        if (!"ONLINE_VERIFIED".equals(label) && !"ONLINE_PROBABLE".equals(label)) {
            return new RoleEvidence(List.of(), List.of());
        }
        Set<String> urls = new LinkedHashSet<>();
        Set<String> quotes = new LinkedHashSet<>();
        for (ReviewItem.CreditEvidenceItem ev : field.getEvidence()) {
            if (ev == null) continue;
            if (meaningful(ev.getUrl())) {
                String canon = MusicBrainzSeedBuilder.canonicalSafePublicHttpUrl(ev.getUrl());
                if (canon != null) urls.add(canon);
            }
            if (meaningful(ev.getQuote()) && quotes.size() < 3) {
                quotes.add(ev.getQuote().trim());
            }
        }
        return new RoleEvidence(new ArrayList<>(urls), new ArrayList<>(quotes));
    }

    private ReviewItem.OnlineCandidate findChosenCandidate(ReviewItem item) {
        if (item.getOnlineCandidates() == null || item.getOnlineCandidates().isEmpty()) {
            return null;
        }
        if (item.getMissingCover() != null && meaningful(item.getMissingCover().getOnlineCandidateId())) {
            for (ReviewItem.OnlineCandidate c : item.getOnlineCandidates()) {
                if (c != null && item.getMissingCover().getOnlineCandidateId().equals(c.getId())) {
                    return c;
                }
            }
        }
        if (meaningful(item.getResolvedAlbumTitle())) {
            for (ReviewItem.OnlineCandidate c : item.getOnlineCandidates()) {
                if (c != null && item.getResolvedAlbumTitle().equalsIgnoreCase(c.getTitle())) {
                    return c;
                }
            }
        }
        return item.getOnlineCandidates().get(0);
    }

    private List<Path> resolveArchivedFilesFromReviewItem(ReviewItem item) {
        Set<Path> found = new LinkedHashSet<>();
        if (item.getCommittedTrackRecords() != null) {
            for (ReviewItem.CommittedTrackRecord tr : item.getCommittedTrackRecords()) {
                if (tr != null && meaningful(tr.getOutputPath())) {
                    MusicBrainzReleaseBackfillService.resolveRealFileUnderOutputRoot(config, tr.getOutputPath())
                        .ifPresent(found::add);
                }
            }
        }
        if (found.isEmpty() && item.getCommittedOutputPaths() != null) {
            for (String raw : item.getCommittedOutputPaths()) {
                if (!meaningful(raw)) continue;
                Optional<Path> asFile = MusicBrainzReleaseBackfillService.resolveRealFileUnderOutputRoot(config, raw);
                if (asFile.isPresent()) {
                    found.add(asFile.get());
                    continue;
                }
                Optional<Path> asDir = MusicBrainzReleaseBackfillService.resolveRealDirUnderOutputRoot(config, raw);
                if (asDir.isPresent()) {
                    List<File> audio = new ArrayList<>();
                    if (fileSystemUtils != null) fileSystemUtils.collectAudioFilesForMarking(asDir.get().toFile(), audio);
                    for (File f : audio) {
                        MusicBrainzReleaseBackfillService.resolveRealFileUnderOutputRoot(config, f.getAbsolutePath())
                            .ifPresent(found::add);
                    }
                }
            }
        }
        List<Path> sorted = new ArrayList<>(found);
        sorted.sort((a, b) -> AudioFileOrdering.comparator().compare(a.toFile(), b.toFile()));
        return sorted;
    }

    private Path resolveCommonDir(List<Path> files, ReviewItem item) {
        if (files != null && !files.isEmpty()) {
            Path p = files.get(0).getParent();
            if (p != null) return p;
        }
        if (item != null && item.getCommittedOutputPaths() != null && !item.getCommittedOutputPaths().isEmpty()) {
            String firstRaw = item.getCommittedOutputPaths().get(0);
            Optional<Path> asDir = MusicBrainzReleaseBackfillService.resolveRealDirUnderOutputRoot(config, firstRaw);
            if (asDir.isPresent()) return asDir.get();
            Optional<Path> asFile = MusicBrainzReleaseBackfillService.resolveRealFileUnderOutputRoot(config, firstRaw);
            if (asFile.isPresent()) return asFile.get().getParent();
        }
        return null;
    }

    private static String inferMediaFormat(String explicitFormat, String edition, List<Path> files) {
        if (meaningful(explicitFormat)) return explicitFormat.trim();
        String combined = (edition == null ? "" : edition.toLowerCase(Locale.ROOT));
        if (combined.contains("digital") || combined.contains("web") || combined.contains("stream")) {
            return "Digital Media";
        }
        if (combined.contains("cd")) {
            return "CD";
        }
        if (files != null) {
            for (Path p : files) {
                String s = p == null ? "" : p.toString().toLowerCase(Locale.ROOT);
                if (s.contains("web") || s.contains("digital")) return "Digital Media";
            }
        }
        return "Digital Media";
    }

    private DetailView toDetailView(MbContributionItem item) {
        MusicBrainzSeedBuilder.SeedPayload seed = MusicBrainzSeedBuilder.build(item);
        boolean hasBackup = store.findExistingCoverBackup(item).isPresent();
        MusicBrainzCreditSanitizer.CoverArtGuide coverGuide =
            MusicBrainzCreditSanitizer.buildCoverGuidance(item, hasBackup, COVER_DOWNLOAD_API);
        MusicBrainzCreditSanitizer.WorksGuide worksGuide = MusicBrainzCreditSanitizer.buildWorksGuide(item);
        return new DetailView(item, seed, coverGuide, worksGuide, isAcoustIdSubmitReady(), isAcoustIdUserKeySet());
    }

    private boolean isAcoustIdClientKeySet() {
        return config != null && meaningful(config.getAcoustIdSettings().apiKey());
    }

    private boolean isAcoustIdUserKeySet() {
        return config != null && meaningful(config.getAcoustIdSettings().userApiKey());
    }

    private boolean isAcoustIdSubmitReady() {
        return isAcoustIdClientKeySet() && isAcoustIdUserKeySet();
    }

    private static boolean meaningful(String s) {
        return s != null && !s.isBlank();
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
