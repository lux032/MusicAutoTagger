package com.lux032.musicautotagger.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ProcessResult;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.llm.LlmArtistSplitter;
import com.lux032.musicautotagger.service.llm.LlmClient;
import com.lux032.musicautotagger.util.ArtistCreditSupport;
import com.lux032.musicautotagger.util.FileNameSanitizer;
import com.lux032.musicautotagger.util.FileSystemUtils;
import com.lux032.musicautotagger.util.MetadataUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 部分识别 / 识别失败文件的人工恢复入口。
 *
 * 重试直接使用隔离目录中的副本，不会修改监控目录里的原始下载文件。
 * 成功后删除隔离副本；若进入待人工确认队列或仍然失败，则保留副本供继续处理。
 */
@Slf4j
public class RecoveryService implements AutoCloseable {

    public enum SourceType { PARTIAL, FAILED }
    public enum JobStatus { QUEUED, RUNNING, SUCCEEDED, WAITING_REVIEW, FAILED }
    public enum RecoveryMode { REIDENTIFY, ONLINE_SEARCH, LOCAL_ARTIST_MATCH }
    /**
     * 音乐库里已有相同音频时怎么办。
     * ASK：不提交，抛 {@link DuplicateArchiveException} 让用户选；
     * REPLACE：把完全被本次输入覆盖的旧归档单元移入回收站后再提交；
     * KEEP_BOTH：照常提交，两份并存。
     * 与失败副本关联的 LLM 自动归档不受此策略影响，始终被替换。
     */
    public enum DuplicatePolicy { ASK, REPLACE, KEEP_BOTH }

    private final MusicConfig config;
    private final AudioFileProcessorService processor;
    private final ProcessedFileLogger processedLogger;
    private final ReviewQueueService reviewQueue;
    private final FolderAlbumCache folderAlbumCache;
    private final FailedFileHandler failedFileHandler;
    private final FileSystemUtils fileSystemUtils;
    private final OnlineIdentificationService onlineIdentificationService;
    private final OnlineSearchQueueService searchQueueService;
    private final TagWriterService tagWriter;
    private final AudioFingerprintService fingerprintService;
    private final OnlineTrackMatcher trackMatcher;
    private final CoverCandidateService coverCandidateService;
    private final AutoArchiveRegistry autoArchiveRegistry;
    private final ReidentifyReplacementRegistry replacementRegistry;
    private final ArchiveDuplicateDetector duplicateDetector;
    private final AlbumArtistPolicy albumArtistPolicy;
    private final LlmArtistSplitter artistSplitter;
    private final LocalArchiveDraft localArchiveDraft;
    private final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "manual-recovery");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, RecoveryJob> jobs = new ConcurrentHashMap<>();
    private final Set<String> runningPaths = ConcurrentHashMap.newKeySet();

    public RecoveryService(MusicConfig config,
                           AudioFileProcessorService processor,
                           ProcessedFileLogger processedLogger,
                           ReviewQueueService reviewQueue,
                           FolderAlbumCache folderAlbumCache,
                           FailedFileHandler failedFileHandler,
                           FileSystemUtils fileSystemUtils,
                           TagWriterService tagWriter,
                           AudioFingerprintService fingerprintService,
                           CoverCandidateService coverCandidateService) {
        this(config, processor, processedLogger, reviewQueue, folderAlbumCache, failedFileHandler,
            fileSystemUtils, tagWriter, fingerprintService, coverCandidateService, new LlmArtistSplitter(config));
    }

    RecoveryService(MusicConfig config,
                    AudioFileProcessorService processor,
                    ProcessedFileLogger processedLogger,
                    ReviewQueueService reviewQueue,
                    FolderAlbumCache folderAlbumCache,
                    FailedFileHandler failedFileHandler,
                    FileSystemUtils fileSystemUtils,
                    TagWriterService tagWriter,
                    AudioFingerprintService fingerprintService,
                    CoverCandidateService coverCandidateService,
                    LlmArtistSplitter artistSplitter) {
        this(config, processor, processedLogger, reviewQueue, folderAlbumCache, failedFileHandler,
            fileSystemUtils, tagWriter, fingerprintService, coverCandidateService, artistSplitter, null);
    }

    RecoveryService(MusicConfig config,
                    AudioFileProcessorService processor,
                    ProcessedFileLogger processedLogger,
                    ReviewQueueService reviewQueue,
                    FolderAlbumCache folderAlbumCache,
                    FailedFileHandler failedFileHandler,
                    FileSystemUtils fileSystemUtils,
                    TagWriterService tagWriter,
                    AudioFingerprintService fingerprintService,
                    CoverCandidateService coverCandidateService,
                    LlmArtistSplitter artistSplitter,
                    OnlineIdentificationService onlineIdentificationOverride) {
        this.config = config;
        this.processor = processor;
        this.processedLogger = processedLogger;
        this.reviewQueue = reviewQueue;
        this.folderAlbumCache = folderAlbumCache;
        this.failedFileHandler = failedFileHandler;
        this.fileSystemUtils = fileSystemUtils;
        this.tagWriter = tagWriter;
        this.fingerprintService = fingerprintService;
        this.trackMatcher = new OnlineTrackMatcher(tagWriter);
        this.coverCandidateService = coverCandidateService;
        this.autoArchiveRegistry = AutoArchiveRegistry.forReviewQueuePath(config.getReviewQueuePath());
        this.replacementRegistry = ReidentifyReplacementRegistry.forReviewQueuePath(config.getReviewQueuePath());
        this.duplicateDetector = new ArchiveDuplicateDetector(fileSystemUtils::isMusicFile);
        this.albumArtistPolicy = new AlbumArtistPolicy(config);
        this.artistSplitter = artistSplitter;
        this.localArchiveDraft = new LocalArchiveDraft(new LlmClient(config));
        this.onlineIdentificationService = onlineIdentificationOverride != null
            ? onlineIdentificationOverride
            : new OnlineIdentificationService(config, reviewQueue, tagWriter, fingerprintService, artistSplitter);
        this.searchQueueService = new OnlineSearchQueueService(config, reviewQueue, this.onlineIdentificationService);
        this.searchQueueService.setRecoveryOtherJobsSupplier(this::listOtherJobs);
        cleanupExpiredTrash();
        cleanupStaleWorkspaces();
        resumeCommittedRecoveries();
    }

    public OnlineSearchQueueService getSearchQueueService() {
        return searchQueueService;
    }

    /**
     * 任务只存在内存里，重启后工作目录不可能再有归属。
     * 上次进程异常退出留下的残留物在这里统一清掉，避免无限堆积。
     * 注意：已提交的任务不依赖工作目录，它的收尾信息在 ReviewItem 里。
     */
    private void cleanupStaleWorkspaces() {
        Path root = resolveWorkRoot();
        if (!Files.isDirectory(root)) return;
        try (var stream = Files.list(root)) {
            for (Path path : stream.toList()) {
                deleteRecursively(path);
                log.info("已清理上次中断的恢复工作目录: {}", path);
            }
        } catch (Exception e) {
            log.warn("清理恢复工作目录失败: {}", e.getMessage());
        }
    }

    /**
     * 输出已提交但隔离副本还没进回收站的任务，启动时把剩下的步骤做完。
     * 这里绝不重写输出，只做回收站移动与条目定稿。
     */
    private void resumeCommittedRecoveries() {
        if (reviewQueue == null) return;
        for (ReviewItem item : reviewQueue.list(ReviewItem.Status.PENDING_REVIEW)) {
            if (item.getRecoveryCommitState() != ReviewItem.RecoveryCommitState.COMMITTED) continue;
            try {
                finishCommittedRecovery(item);
                log.info("已完成上次中断的恢复收尾: {}", item.getFolderName());
            } catch (Exception e) {
                log.error("恢复收尾失败，条目保持待处理: {} - {}", item.getFolderName(), e.getMessage());
            }
        }
    }

    /**
     * 提交后的收尾：隔离副本入回收站 + 条目定稿。
     * 可重入：源目录已不在时直接跳过移动。
     */
    private void finishCommittedRecovery(ReviewItem item) throws IOException {
        File source = item.getRecoverySourcePath() == null ? null : new File(item.getRecoverySourcePath());
        if (source != null && source.exists() && item.getRecoverySourceType() != null) {
            RecoveryJob trashJob = new RecoveryJob();
            trashJob.id = item.getId();
            trashJob.sourceType = SourceType.valueOf(item.getRecoverySourceType());
            trashJob.relativePath = source.getName();
            moveToTrash(source, trashJob);
        }
        boolean local = item.getVerificationSource() == ReviewItem.VerificationSource.LOCAL_TAGS;
        if (item.getRecoverySourceType() == null && local) {
            folderAlbumCache.clearFolderUnresolved(item.getFolderPath());
        } else if (item.getRecoverySourceType() == null) {
            // 普通 review 条目的原目录仍在 monitor 树下，确认后必须解除 unresolved 守卫，
            // 否则后续新文件永远无法再走专辑匹配（对齐 ReviewResolutionService.confirmCandidate 的既有行为）。
            FolderAlbumCache.CachedAlbumInfo albumInfo = new FolderAlbumCache.CachedAlbumInfo(
                null, null, item.getResolvedAlbumTitle(), item.getResolvedAlbumArtist(),
                item.getResolvedTrackCount(), item.getResolvedReleaseDate(),
                null, false, 1.0, FolderAlbumCache.CacheSource.MANUAL_CONFIRMED);
            folderAlbumCache.clearFolderUnresolved(item.getFolderPath());
            folderAlbumCache.forceSetFolderAlbum(item.getFolderPath(), albumInfo);
        }
        item.setRecoveryCommitState(ReviewItem.RecoveryCommitState.COMPLETED);
        reviewQueue.markResolved(item, ReviewItem.Status.CONFIRMED,
            (local ? "人工确认本地入库: " : "人工确认联网候选: ")
                + (item.getResolvedAlbumTitle() == null ? "" : item.getResolvedAlbumTitle()));
    }

    public List<Map<String, Object>> listItems() {
        List<Map<String, Object>> result = new ArrayList<>();
        collectRoot(result, SourceType.PARTIAL, config.getPartialDirectory());
        collectRoot(result, SourceType.FAILED, config.getFailedDirectory());
        result.sort(Comparator.comparingLong(m -> -((Number) m.get("modifiedAt")).longValue()));
        return result;
    }

    public List<Map<String, Object>> listJobs() {
        List<Map<String, Object>> result = new ArrayList<>(listOtherJobs());
        if (searchQueueService != null) {
            result.addAll(searchQueueService.listRecoveryJobViews());
        }
        result.sort(Comparator.comparingLong(m -> -((Number) m.getOrDefault("createdAt", 0L)).longValue()));
        return result;
    }

    /** 仅返回非联网搜索的恢复任务（REIDENTIFY / LOCAL_ARTIST_MATCH） */
    public List<Map<String, Object>> listOtherJobs() {
        List<RecoveryJob> snapshots = new ArrayList<>(jobs.values());
        snapshots.sort(Comparator.comparingLong(RecoveryJob::getCreatedAt).reversed());
        List<Map<String, Object>> result = new ArrayList<>();
        for (RecoveryJob job : snapshots) {
            Map<String, Object> view = job.toView();
            if (job.status == JobStatus.WAITING_REVIEW && job.reviewItemId != null && reviewQueue != null) {
                ReviewItem reviewItem = reviewQueue.get(job.reviewItemId);
                if (reviewItem != null) {
                    view.put("reviewKind", reviewItem.effectiveKind().name());
                    boolean isCoverMissing = reviewItem.effectiveKind() == ReviewItem.Kind.COVER_MISSING;
                    if (reviewItem.getStatus() == ReviewItem.Status.CONFIRMED
                            || reviewItem.getStatus() == ReviewItem.Status.ARCHIVED_UNVERIFIED) {
                        view.put("status", JobStatus.SUCCEEDED.name());
                        view.put("message", isCoverMissing ? "已在缺少封面页面确认完成" : "已在待确认页面确认完成");
                    } else if (reviewItem.getStatus() == ReviewItem.Status.REJECTED) {
                        view.put("status", JobStatus.FAILED.name());
                        view.put("message", isCoverMissing ? "已在缺少封面页面忽略" : "已在待确认页面驳回");
                    }
                } else {
                    view.put("status", JobStatus.FAILED.name());
                    view.put("message", "待确认条目已移除");
                }
            }
            result.add(view);
        }
        return result;
    }

    /** 统计回收站当前条目数量（仅统计主体条目，忽略 manifest .json）。 */
    public int countTrash() {
        Path root = trashRoot();
        if (!Files.isDirectory(root)) return 0;
        try (var stream = Files.list(root)) {
            return (int) stream.filter(p -> !p.getFileName().toString().endsWith(".json")).count();
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean isOnlineSearchAvailable() {
        return onlineIdentificationService.isAvailable();
    }

    record StagedReidentifyFile(File staged, File fallback, Path oldTarget) { }

    /**
     * 对源文件已移除（或不在监控目录）的记录，基于暂存副本执行普通重新识别。
     */
    java.util.concurrent.Future<?> submitStagedReidentify(
            File stagedDir, String displayName, List<StagedReidentifyFile> files, List<String> recordsToRemove) {
        String label = meaningful(displayName) ? displayName : stagedDir.getName();
        Path stagedCleanupRoot = (stagedDir.getParentFile() != null
            && stagedDir.getParentFile().getName().startsWith("reprocess-"))
            ? stagedDir.getParentFile().toPath()
            : stagedDir.toPath();
        List<File> stagedFiles = files.stream().map(StagedReidentifyFile::staged).toList();
        List<Path> oldTargetPaths = files.stream().map(StagedReidentifyFile::oldTarget)
            .filter(java.util.Objects::nonNull).toList();
        LogCollector.addLog("INFO", "手动重新识别（普通识别）：已提交 " + label + "（" + files.size() + " 个文件）");
        return executor.submit(() -> {
            String taskId = UUID.randomUUID().toString();
            Path workRoot = resolveWorkRoot().resolve("reidentify-" + taskId);
            boolean waitingForReview = false;
            boolean committed = false;
            try {
                processedLogger.removeProcessedRecords(recordsToRemove);
                Files.createDirectories(workRoot);
                for (StagedReidentifyFile file : files) {
                    recordReplacementAlias(file.staged().toPath(), file.oldTarget());
                }
                int succeeded = 0;
                try (TagWriterService.OutputScope scope = tagWriter.recoveryWorkspace(workRoot.toFile());
                     AutoCloseable suppressLlm = FailedFileHandler.suppressLlmAutoArchive()) {
                    for (File audioFile : stagedFiles) {
                        if (processor.processRecoveryFile(audioFile, stagedDir) != ProcessResult.SUCCESS) {
                            break;
                        }
                        succeeded++;
                    }
                }

                if (reviewQueue != null && reviewQueue.isFolderUnderReview(stagedDir.getAbsolutePath())) {
                    waitingForReview = true;
                    ReviewItem queued = reviewQueue.findPendingByFolder(stagedDir.getAbsolutePath());
                    if (queued != null) {
                        reviewQueue.updatePending(queued.getId(), target -> {
                            if (meaningful(displayName)) target.setFolderName(displayName);
                            target.setStagingDir(stagedDir.getAbsolutePath());
                            if (target.effectiveKind() == ReviewItem.Kind.COVER_MISSING && target.getMissingCover() != null) {
                                target.getMissingCover().setSourcePipeline("REIDENTIFY");
                            }
                            return true;
                        });
                    }
                    boolean coverMissing = queued != null && queued.effectiveKind() == ReviewItem.Kind.COVER_MISSING;
                    LogCollector.addLog("WARN", (coverMissing ? "重新识别缺少封面，已转入「缺少封面」等待补图: " : "重新识别转入待人工确认: ") + label);
                    return;
                }

                if (succeeded != files.size()) {
                    recordStagedReidentifyFailure(files, label, "普通识别失败");
                    LogCollector.addLog("ERROR", "手动重新识别失败，已转入失败记录（成功 " + succeeded + "/" + files.size() + "）: " + label);
                    return;
                }

                Map<String, Path> tempTargets = collectTempRelativeTargets(workRoot, stagedFiles);
                commitReplacingDuplicates(workRoot, files.size(), taskId, null, DuplicatePolicy.ASK, oldTargetPaths);
                committed = true;
                clearReplacementEntries(stagedFiles);
                finalizeCommittedRecords(tempTargets, stagedFiles);
                LogCollector.addLog("SUCCESS", "手动重新识别成功: " + label);
            } catch (Exception e) {
                String reason = e instanceof DuplicateArchiveException duplicate ? duplicate.describe() : e.getMessage();
                if (!committed && !waitingForReview) {
                    try {
                        recordStagedReidentifyFailure(files, label, meaningful(reason) ? reason : "重新识别提交失败");
                    } catch (RuntimeException recordError) {
                        e.addSuppressed(recordError);
                    }
                }
                log.error("基于已归档文件的重新识别失败: {}", label, e);
                LogCollector.addLog("ERROR", "手动重新识别异常 (" + label + "): " + reason);
            } finally {
                try {
                    deleteRecursively(workRoot);
                    if (!waitingForReview) deleteRecursively(stagedCleanupRoot);
                } catch (IOException cleanupErr) {
                    log.warn("清理重新识别暂存目录失败: {}", cleanupErr.getMessage());
                }
            }
        });
    }

    private void recordStagedReidentifyFailure(List<StagedReidentifyFile> files, String label, String reason) {
        processedLogger.removeProcessedRecords(files.stream().map(file -> file.staged().getAbsolutePath()).toList());
        for (StagedReidentifyFile file : files) {
            recordReplacementAlias(file.fallback().toPath(), file.oldTarget());
            File retryFile = file.fallback().isFile() ? file.fallback()
                : file.oldTarget() == null ? null : file.oldTarget().toFile();
            if (retryFile != null && retryFile.isFile()) {
                String oldTarget = file.oldTarget() == null ? null : file.oldTarget().toString();
                processedLogger.markFileAsProcessed(retryFile, "FAILED", reason, retryFile.getName(), label, null, oldTarget);
            }
        }
    }

    private void recordReplacementAlias(Path source, Path oldTarget) {
        if (oldTarget != null) {
            replacementRegistry.record(source.toString(), oldTarget.toString());
        }
    }

    /** 忽略部分识别 / 识别失败列表中的项目：移入回收站（保留天数为 0 时按策略直接删除），不影响音乐库已归档文件。 */
    public void dismiss(SourceType type, String relativePath) throws IOException {
        File target = resolveItem(type, relativePath);
        if (!target.exists()) {
            throw new IOException("recovery.item.not.found");
        }
        String key = target.getCanonicalPath();
        if (searchQueueService != null && searchQueueService.isFolderActive(key)) {
            throw new IOException("recovery.already.running");
        }
        if (!runningPaths.add(key)) {
            throw new IOException("recovery.already.running");
        }
        try {
            moveToTrash(target, "dismiss-" + UUID.randomUUID().toString().substring(0, 8), type, relativePath);
            LogCollector.addLog("INFO", "已忽略隔离项目（已移入回收站）: " + relativePath + " [" + type.name() + "]");
        } finally {
            runningPaths.remove(key);
        }
    }

    /** 普通待确认条目直接触发联网搜索，不将原始监控目录标记为 recovery 隔离副本。 */
    public ReviewItem triggerOnlineSearchForReviewFolder(File folder, boolean analyzeCover) throws Exception {
        return onlineIdentificationService.search(folder, null, analyzeCover);
    }

    /** 只为指定条目搜索；条目已不在待确认时返回 null，不会新建条目 */
    public ReviewItem triggerOnlineSearchForReviewItem(String itemId, File folder) throws Exception {
        return onlineIdentificationService.searchForExistingItem(itemId, folder);
    }

    /** 回收站条目列表，按入站时间倒序。 */
    public List<Map<String, Object>> listTrash() {
        List<Map<String, Object>> result = new ArrayList<>();
        Path root = trashRoot();
        if (!Files.isDirectory(root)) return result;
        try (var stream = Files.list(root)) {
            for (Path path : stream.toList()) {
                if (path.getFileName().toString().endsWith(".json")) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                String sourceType = null;
                String relativePath = null;
                boolean outputKind = false;
                Path manifest = path.resolveSibling(path.getFileName() + ".json");
                if (Files.isRegularFile(manifest)) {
                    try {
                        JsonObject json = gson.fromJson(Files.readString(manifest), JsonObject.class);
                        if (json != null) {
                            outputKind = json.has("kind") && TRASH_KIND_OUTPUT.equals(json.get("kind").getAsString())
                                && json.has("originalPath");
                            if (outputKind) sourceType = TRASH_KIND_OUTPUT;
                            else if (json.has("sourceType")) sourceType = json.get("sourceType").getAsString();
                            if (json.has("relativePath")) relativePath = json.get("relativePath").getAsString();
                        }
                    } catch (Exception ignored) {
                        // manifest 损坏时仍然列出条目，只是无法自动恢复
                    }
                }
                List<File> audioFiles = collectAudioFiles(path.toFile());
                entry.put("id", path.getFileName().toString());
                entry.put("sourceType", sourceType);
                entry.put("relativePath", relativePath);
                entry.put("restorable", sourceType != null && relativePath != null);
                entry.put("trashedAt", trashedAt(path));
                entry.put("fileCount", audioFiles.size());
                entry.put("path", path.toAbsolutePath().toString());
                result.add(entry);
            }
        } catch (Exception e) {
            log.warn("列出回收站失败: {}", e.getMessage());
        }
        result.sort(Comparator.comparingLong(m -> -((Number) m.get("trashedAt")).longValue()));
        return result;
    }

    /**
     * 把回收站条目恢复回原隔离目录。
     * 只搬回隔离副本，<b>不会删除已经生成的输出目录</b>；
     * 原路径已存在时直接拒绝，避免覆盖用户后来放回去的文件。
     */
    public synchronized void restoreFromTrash(String id) throws IOException {
        Path entry = resolveTrashEntry(id);
        Path manifest = entry.resolveSibling(entry.getFileName() + ".json");
        if (!Files.isRegularFile(manifest)) throw new IOException("trash.manifest.missing");

        String sourceType;
        String relativePath;
        try {
            JsonObject json = gson.fromJson(Files.readString(manifest), JsonObject.class);
            if (json.has("kind") && TRASH_KIND_OUTPUT.equals(json.get("kind").getAsString())) {
                restoreOutputFromTrash(entry, manifest, json.get("originalPath").getAsString());
                return;
            }
            sourceType = json.get("sourceType").getAsString();
            relativePath = json.get("relativePath").getAsString();
        } catch (Exception e) {
            throw new IOException("trash.manifest.invalid", e);
        }

        File target = resolveItem(SourceType.valueOf(sourceType), relativePath);
        if (target.exists()) throw new IOException("trash.restore.target.exists");
        Files.createDirectories(target.toPath().getParent());
        moveAcrossDevices(entry, target.toPath());
        Files.deleteIfExists(manifest);
        LogCollector.addLog("SUCCESS", "已从回收站恢复: " + relativePath + " → " + sourceType);
    }

    /** 被替换的旧归档搬回音乐库原位；只允许还原到输出目录内且目标未被占用。 */
    private void restoreOutputFromTrash(Path entry, Path manifest, String originalPath) throws IOException {
        try (var libraryWrite = LibraryWriteGuard.acquire()) {
            Path outputRoot = Path.of(config.getOutputDirectory()).toAbsolutePath().normalize();
            Path target = Path.of(originalPath).toAbsolutePath().normalize();
            if (!target.startsWith(outputRoot) || target.equals(outputRoot)) throw new IOException("trash.manifest.invalid");
            if (Files.exists(target)) throw new IOException("trash.restore.target.exists");
            Files.createDirectories(target.getParent());
            moveAcrossDevices(entry, target);
            Files.deleteIfExists(manifest);
            LogCollector.addLog("SUCCESS", "已从回收站还原到音乐库: " + displayOutputPath(target));
        }
    }

    /** 立即彻底删除回收站条目。 */
    public synchronized void deleteFromTrash(String id) throws IOException {
        Path entry = resolveTrashEntry(id);
        deleteRecursively(entry);
        Files.deleteIfExists(entry.resolveSibling(entry.getFileName() + ".json"));
        LogCollector.addLog("WARN", "已删除回收站条目: " + id);
    }

    /** 挡住 id 里的路径穿越，只允许操作回收站根下的直接子项。 */
    private Path resolveTrashEntry(String id) throws IOException {
        if (id == null || id.isBlank()) throw new IOException("trash.id.required");
        if (id.endsWith(".json")) throw new IOException("trash.id.invalid");
        Path root = trashRoot().toAbsolutePath().normalize();
        Path entry = root.resolve(id).toAbsolutePath().normalize();
        if (!entry.getParent().equals(root)) throw new IOException("trash.id.invalid");
        if (!Files.exists(entry)) throw new IOException("trash.entry.not.found");
        return entry;
    }

    public LocalArchiveDraft.Draft previewLocalArchive(String itemId) throws IOException {
        ReviewItem item = requirePendingRecoveryReview(itemId);
        if (item.getRecoveryCommitState() == ReviewItem.RecoveryCommitState.COMMITTED) {
            return new LocalArchiveDraft.Draft("", item.getResolvedAlbumTitle(), item.getResolvedAlbumArtist(),
                List.of(), List.of(), item.getResolvedTrackCount(), List.of(), false);
        }
        File source = localArchiveSource(item);
        LocalArchiveSources snapshot = localArchiveSources(source, item);
        Map<String, MusicMetadata> tags = new LinkedHashMap<>();
        snapshot.files().forEach((name, path) -> {
            MusicMetadata metadata = tagWriter.readTags(path.toFile());
            tags.put(name, metadata == null ? new MusicMetadata() : metadata);
        });
        if (!snapshot.revision().equals(localArchiveSources(source, item).revision())) {
            throw new IOException("local.archive.stale");
        }
        String folder = meaningful(item.getFolderName()) ? item.getFolderName() : source.getName();
        return localArchiveDraft.build(snapshot.revision(), folder, tags);
    }

    public synchronized ReviewItem confirmLocalArchive(String itemId, String revision, String album, String artist,
                                                       List<String> artists, Map<String, List<String>> trackArtists,
                                                       List<String> trackArtistFallbacks, DuplicatePolicy duplicatePolicy) throws IOException {
        ReviewItem item = requirePendingRecoveryReview(itemId);
        File source = localArchiveSource(item);
        String runningKey = source.getCanonicalPath();
        if (!runningPaths.add(runningKey)) throw new IOException("recovery.already.running");
        try {
            if (item.getRecoveryCommitState() == ReviewItem.RecoveryCommitState.COMMITTED) {
                finishCommittedRecovery(item);
                return item;
            }
            Set<String> inherit = trackArtistFallbacks == null ? Set.of() : new LinkedHashSet<>(trackArtistFallbacks);
            return archiveLocalTags(item, source, revision, album, artist, artists, trackArtists, inherit,
                duplicatePolicy, null, true);
        } finally {
            runningPaths.remove(runningKey);
        }
    }

    /** 「缺少封面」页补图或确认无封面后，继续完成之前暂缓的「按本地标签入库」。 */
    public synchronized ReviewItem commitDeferredLocalMissingCover(ReviewItem item, byte[] coverBytes,
                                                                   DuplicatePolicy duplicatePolicy) throws IOException {
        if (item == null || item.getMissingCover() == null) throw new IOException("item.not.found");
        ReviewItem.MissingCover mc = item.getMissingCover();
        File source = localArchiveSource(item);
        String runningKey = source.getCanonicalPath();
        if (!runningPaths.add(runningKey)) throw new IOException("recovery.already.running");
        try {
            if (item.getRecoveryCommitState() == ReviewItem.RecoveryCommitState.COMMITTED) {
                finishCommittedRecovery(item);
                return item;
            }
            LocalArchiveSources fresh = localArchiveSources(source, item);
            Map<String, List<String>> trackArtists = mc.getLocalTrackArtists() == null
                ? Map.of() : mc.getLocalTrackArtists();
            Set<String> inherit = mc.getLocalTrackArtistFallbacks() == null
                ? Set.of() : new LinkedHashSet<>(mc.getLocalTrackArtistFallbacks());
            return archiveLocalTags(item, source, fresh.revision(), mc.getAlbumTitle(), mc.getAlbumArtist(),
                mc.getAlbumArtists(), trackArtists, inherit,
                duplicatePolicy == null ? DuplicatePolicy.ASK : duplicatePolicy, coverBytes, false);
        } finally {
            runningPaths.remove(runningKey);
        }
    }

    private ReviewItem archiveLocalTags(ReviewItem item, File source, String revision, String album, String artist,
                                        List<String> artists, Map<String, List<String>> trackArtists,
                                        Set<String> inherit, DuplicatePolicy duplicatePolicy,
                                        byte[] explicitCoverArt, boolean allowMissingCoverIntercept) throws IOException {
        List<String> albumNames = ArtistCreditSupport.cleanNames(artists);
        if (!meaningful(album) || !meaningful(artist) || albumNames.isEmpty()) {
            throw new IOException("local.archive.metadata.required");
        }
        album = album.strip();
        artist = artist.strip();

        LocalArchiveSources snapshot = localArchiveSources(source, item);
        if (!snapshot.revision().equals(revision)) throw new IOException("local.archive.stale");
        if (!snapshot.files().keySet().containsAll(trackArtists.keySet())
            || !snapshot.files().keySet().containsAll(inherit)
            || inherit.stream().anyMatch(trackArtists::containsKey)
            || trackArtists.values().stream().anyMatch(names -> names == null || names.isEmpty())) {
            throw new IOException("local.archive.tracks.invalid");
        }

        byte[] coverToEmbed = null;
        if (explicitCoverArt != null && explicitCoverArt.length > 0) {
            coverToEmbed = compressCoverBytes(explicitCoverArt);
        } else {
            boolean allHaveEmbedded = true;
            for (Path src : snapshot.files().values()) {
                if (!tagWriter.hasEmbeddedCover(src.toFile())) {
                    allHaveEmbedded = false;
                    break;
                }
            }
            if (!allHaveEmbedded) {
                coverToEmbed = findLocalFallbackCover(source, snapshot.files().values().stream().map(Path::toFile).toList());
                if ((coverToEmbed == null || coverToEmbed.length == 0)
                    && allowMissingCoverIntercept
                    && config.isCoverMissingRequireReview()) {
                    ReviewItem.MissingCover details = new ReviewItem.MissingCover();
                    details.setMode(ReviewItem.MissingCover.Mode.STAGED_IMPORT);
                    details.setSourcePipeline("REVIEW_LOCAL");
                    details.setLocalArchiveDirect(true);
                    details.setLocalArchiveRevision(revision);
                    details.setAlbumTitle(album);
                    details.setAlbumArtist(artist);
                    details.setAlbumArtists(new ArrayList<>(albumNames));
                    details.setLocalTrackArtists(new LinkedHashMap<>(trackArtists));
                    details.setLocalTrackArtistFallbacks(new ArrayList<>(inherit));
                    details.setRecordingIdSentinel("LOCAL_TAGS");
                    return reviewQueue.transitionToMissingCover(
                        item, details, "本地标签已确认，但缺少封面，等待补全或确认无封面入库");
                }
            }
        }

        Path artistDir = Path.of(FileNameSanitizer.sanitize(artist));
        String albumDir = FileNameSanitizer.sanitize(album);
        Path workRoot = resolveWorkRoot();
        Files.createDirectories(workRoot);
        Path workspace = Files.createTempDirectory(workRoot, "local-");
        List<Path> committed;
        Path albumUnit;
        try {
            albumUnit = artistDir.resolve(albumDir);
            for (var entry : snapshot.files().entrySet()) {
                Path copy = workspace.resolve(albumUnit).resolve(entry.getKey());
                Files.createDirectories(copy.getParent());
                Files.copy(entry.getValue(), copy, LinkOption.NOFOLLOW_LINKS);
                if (!Files.isRegularFile(copy, LinkOption.NOFOLLOW_LINKS)) throw new IOException("local.archive.stale");
                tagWriter.writeLocalArchiveTags(copy, album, artist, albumNames, trackArtists.get(entry.getKey()),
                    inherit.contains(entry.getKey()));
                if (coverToEmbed != null && coverToEmbed.length > 0) {
                    tagWriter.embedCoverAtomically(copy.toFile(), coverToEmbed);
                }
            }
            if (!snapshot.revision().equals(localArchiveSources(source, item).revision())) {
                throw new IOException("local.archive.stale");
            }
            var originalFiles = snapshot.files().values().stream().map(Path::toFile).toList();
            String linkKey = SourceType.FAILED.name().equals(item.getRecoverySourceType()) ? source.getName() : null;
            List<String> searchDirs = new ArrayList<>(albumNames.stream().map(FileNameSanitizer::sanitize).toList());
            searchDirs.add(FileNameSanitizer.sanitize(AlbumArtistPolicy.VARIOUS_ARTISTS));
            try (var libraryWrite = LibraryWriteGuard.acquire()) {
                if (duplicatePolicy == DuplicatePolicy.KEEP_BOTH) {
                    int copyNumber = 2;
                    while (Files.exists(Path.of(config.getOutputDirectory()).resolve(albumUnit))) {
                        albumUnit = artistDir.resolve(albumDir + " (" + copyNumber++ + ")");
                    }
                    Path copiedAlbum = workspace.resolve(artistDir.resolve(albumDir));
                    if (!copiedAlbum.equals(workspace.resolve(albumUnit))) {
                        Files.move(copiedAlbum, workspace.resolve(albumUnit));
                    }
                }
                committed = commitReplacingDuplicates(workspace, originalFiles.size(), item.getId(), linkKey,
                    duplicatePolicy, collectReplacedTargetPaths(originalFiles), searchDirs);
            }
            clearReplacementEntries(originalFiles);
        } finally {
            try {
                deleteRecursively(workspace);
            } catch (IOException e) {
                log.warn("无法清理本地入库工作目录: {}", workspace, e);
            }
        }

        item.setVerificationSource(ReviewItem.VerificationSource.LOCAL_TAGS);
        item.setResolvedAlbumTitle(album);
        item.setResolvedAlbumArtist(artist);
        item.setResolvedTrackCount(snapshot.files().size());
        item.setRecoveryCommitState(ReviewItem.RecoveryCommitState.COMMITTED);
        item.setCommittedOutputPaths(committed.stream().map(Path::toString).toList());
        reviewQueue.update(item);

        for (var entry : snapshot.files().entrySet()) {
            File file = entry.getValue().toFile();
            File output = Path.of(config.getOutputDirectory()).resolve(albumUnit).resolve(entry.getKey()).toFile();
            processedLogger.markFileAsProcessed(file, "LOCAL_TAGS", artist, file.getName(), album,
                null, output.getAbsolutePath());
        }
        finishCommittedRecovery(item);
        return item;
    }

    private ReviewItem requirePendingRecoveryReview(String itemId) throws IOException {
        ReviewItem item = reviewQueue.get(itemId);
        if (item == null) throw new IOException("item.not.found");
        if (item.getStatus() != ReviewItem.Status.PENDING_REVIEW) throw new IOException("item.already.resolved");
        return item;
    }

    private File localArchiveSource(ReviewItem item) {
        return new File(item.getRecoverySourcePath() != null ? item.getRecoverySourcePath() : item.getFolderPath());
    }

    private record LocalArchiveSources(Map<String, Path> files, String revision) { }

    private LocalArchiveSources localArchiveSources(File source, ReviewItem item) throws IOException {
        Path sourcePath = source.toPath().toAbsolutePath().normalize();
        if (Files.isSymbolicLink(sourcePath)) throw new IOException("recovery.path.invalid");
        if (!Files.exists(sourcePath) || !Files.isReadable(sourcePath)) throw new IOException("files.missing");
        boolean directory = Files.isDirectory(sourcePath);
        List<File> files;
        if (directory && item.getRecoverySourceType() != null) {
            // 隔离条目整个目录归同一专辑，沿用联网搜索的递归范围。
            try (var entries = Files.walk(sourcePath)) {
                files = entries.filter(path -> Files.isRegularFile(path) || Files.isSymbolicLink(path))
                    .map(Path::toFile).filter(fileSystemUtils::isMusicFile).sorted().toList();
            }
        } else {
            files = collectAudioFiles(source);
        }
        Path root = directory ? sourcePath : sourcePath.getParent();
        Set<Path> inputs = new java.util.TreeSet<>();
        files.stream().map(file -> file.toPath().toAbsolutePath().normalize()).forEach(inputs::add);
        // 联网识别可能已记录在非标准碟片子目录里的文件。保留这些已知曲目，
        // 同时只补入本地扫描认定属于此专辑的文件，避免混入相邻专辑。
        if (item.getFiles() != null) {
            for (ReviewItem.FileEntry entry : item.getFiles()) {
                if (entry.getOriginalPath() == null) continue;
                Path queued = Path.of(entry.getOriginalPath()).toAbsolutePath().normalize();
                if (!queued.startsWith(root) || (!directory && !queued.equals(sourcePath))) {
                    throw new IOException("recovery.path.invalid");
                }
                if (!Files.isRegularFile(queued) || !Files.isReadable(queued)
                        || !fileSystemUtils.isMusicFile(queued.toFile())) {
                    throw new IOException("files.missing");
                }
                inputs.add(queued);
            }
        }
        if (inputs.isEmpty()) throw new IOException("recovery.no.audio.files");
        Path realRoot = root.toRealPath();
        Map<String, Path> paths = new LinkedHashMap<>();
        List<List<String>> attributes = new ArrayList<>();
        for (Path path : inputs) {
            if (!path.startsWith(root) || !path.toRealPath().equals(realRoot.resolve(root.relativize(path)))) {
                throw new IOException("recovery.path.invalid");
            }
            BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attr.isRegularFile()) throw new IOException("recovery.path.invalid");
            String relative = root.relativize(path).toString();
            paths.put(relative, path);
            attributes.add(List.of(path.toString(), String.valueOf(attr.fileKey()), String.valueOf(attr.size()),
                attr.lastModifiedTime().toString(), attr.creationTime().toString()));
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(gson.toJson(attributes).getBytes(StandardCharsets.UTF_8));
            return new LocalArchiveSources(paths, HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 用户确认联网候选后，在工作目录完成整张处理，再整体提交。 */
    public synchronized ReviewItem confirmOnlineCandidate(String itemId, String candidateId,
                                                           String albumTitle, String albumArtist,
                                                           String releaseDate, String edition,
                                                           String archiveDirectoryName,
                                                           String selectedCoverSha256) throws IOException {
        return confirmOnlineCandidate(itemId, candidateId, albumTitle, albumArtist, releaseDate, edition,
            archiveDirectoryName, selectedCoverSha256, DuplicatePolicy.ASK, null);
    }

    public synchronized ReviewItem confirmOnlineCandidate(String itemId, String candidateId,
                                                           String albumTitle, String albumArtist,
                                                           String releaseDate, String edition,
                                                           String archiveDirectoryName,
                                                           String selectedCoverSha256,
                                                           DuplicatePolicy duplicatePolicy) throws IOException {
        return confirmOnlineCandidate(itemId, candidateId, albumTitle, albumArtist, releaseDate, edition,
            archiveDirectoryName, selectedCoverSha256, duplicatePolicy, null);
    }

    public synchronized ReviewItem confirmOnlineCandidate(String itemId, String candidateId,
                                                           String albumTitle, String albumArtist,
                                                           String releaseDate, String edition,
                                                           String archiveDirectoryName,
                                                           String selectedCoverSha256,
                                                           DuplicatePolicy duplicatePolicy,
                                                           List<String> manualAlbumArtists) throws IOException {
        return confirmOnlineCandidate(itemId, candidateId, albumTitle, albumArtist, releaseDate, edition,
            archiveDirectoryName, selectedCoverSha256, duplicatePolicy, manualAlbumArtists, false);
    }

    public synchronized ReviewItem confirmOnlineCandidate(String itemId, String candidateId,
                                                           String albumTitle, String albumArtist,
                                                           String releaseDate, String edition,
                                                           String archiveDirectoryName,
                                                           String selectedCoverSha256,
                                                           DuplicatePolicy duplicatePolicy,
                                                           List<String> manualAlbumArtists,
                                                           boolean confirmNoCover) throws IOException {
        return confirmOnlineCandidateInternal(itemId, candidateId, albumTitle, albumArtist, releaseDate, edition,
            archiveDirectoryName, selectedCoverSha256, null, duplicatePolicy, manualAlbumArtists, confirmNoCover, true);
    }

    /** 「缺少封面」页补图或确认无封面后，继续完成之前暂缓的「联网候选确认入库」。 */
    public synchronized ReviewItem commitDeferredOnlineMissingCover(ReviewItem item, byte[] coverBytes,
                                                                    DuplicatePolicy duplicatePolicy) throws IOException {
        if (item == null || item.getMissingCover() == null) throw new IOException("item.not.found");
        ReviewItem.MissingCover mc = item.getMissingCover();
        return confirmOnlineCandidateInternal(item.getId(), mc.getOnlineCandidateId(),
            mc.getAlbumTitle(), mc.getAlbumArtist(), mc.getReleaseDate(), null,
            mc.getArchiveDirectoryName(), null, coverBytes,
            duplicatePolicy == null ? DuplicatePolicy.ASK : duplicatePolicy,
            mc.getAlbumArtists(), coverBytes == null || coverBytes.length == 0, false);
    }

    private ReviewItem confirmOnlineCandidateInternal(String itemId, String candidateId,
                                                      String albumTitle, String albumArtist,
                                                      String releaseDate, String edition,
                                                      String archiveDirectoryName,
                                                      String selectedCoverSha256,
                                                      byte[] directCoverBytes,
                                                      DuplicatePolicy duplicatePolicy,
                                                      List<String> manualAlbumArtists,
                                                      boolean confirmNoCover,
                                                      boolean allowMissingCoverIntercept) throws IOException {
        ReviewItem item = reviewQueue.get(itemId);
        if (item == null) throw new IOException("item.not.found");
        if (item.getStatus() != ReviewItem.Status.PENDING_REVIEW) throw new IOException("item.already.resolved");

        // 上一次输出已提交但收尾中断（回收站无权限/磁盘满/挂载断开）。
        // 必须接着收尾，而不是重新写一遍输出（那只会撞 destination.exists 永远卡住）。
        if (item.getRecoveryCommitState() == ReviewItem.RecoveryCommitState.COMMITTED) {
            finishCommittedRecovery(item);
            return item;
        }
        // 普通 review 条目没有物理隔离副本；确认联网候选时可以正常写入输出，
        // 但收尾阶段不能把它的原始 folderPath 当成 recovery 副本移进回收站。
        if (item.isOnlineEvidenceStale()) throw new IOException("online.evidence.stale");
        ReviewItem.OnlineCandidate chosen = (item.getOnlineCandidates() == null
            ? List.<ReviewItem.OnlineCandidate>of() : item.getOnlineCandidates()).stream()
            .filter(c -> candidateId != null && candidateId.equals(c.getId())).findFirst()
            .orElseThrow(() -> new IOException("online.candidate.not.found"));

        String chosenEffectiveAlbumArtist = meaningful(chosen.getAlbumArtist())
            ? chosen.getAlbumArtist().trim() : chosen.getArtist();
        String finalAlbum = meaningful(albumTitle) ? albumTitle.trim() : chosen.getTitle();
        String finalArtist = meaningful(albumArtist) ? albumArtist.trim() : chosenEffectiveAlbumArtist;
        String finalDate = releaseDate != null ? releaseDate.trim() : chosen.getReleaseDate();
        if (!meaningful(finalAlbum) || !meaningful(finalArtist)) throw new IOException("online.metadata.incomplete");

        List<String> finalAlbumArtists = resolveOnlineAlbumArtists(
            finalArtist, chosen, chosenEffectiveAlbumArtist, manualAlbumArtists);
        if (albumArtistPolicy.shouldCollapse()
                && finalAlbumArtists.size() > 1) {
            finalArtist = AlbumArtistPolicy.VARIOUS_ARTISTS;
            finalAlbumArtists = List.of();
        }

        File source = new File(item.getRecoverySourcePath() != null ? item.getRecoverySourcePath() : item.getFolderPath());
        List<File> originalFiles = collectAudioFiles(source);
        if (originalFiles.isEmpty()) throw new IOException("recovery.no.audio.files");

        // 证据新鲜度始终按搜索时的原始目录计算；转码暂存副本只参与最终写入。
        String currentHash;
        try {
            currentHash = onlineIdentificationService.evidenceHashForFolder(source);
        } catch (Exception e) {
            throw new IOException("online.evidence.hash.failed", e);
        }
        if (item.getEvidenceHash() != null && !item.getEvidenceHash().equals(currentHash)) {
            item.setOnlineEvidenceStale(true);
            reviewQueue.update(item);
            throw new IOException("online.evidence.stale");
        }

        List<File> files = originalFiles;
        java.util.Map<String, String> originalPathByProcessingPath = new java.util.HashMap<>();
        if (item.getRecoverySourceType() == null && item.getFiles() != null && !item.getFiles().isEmpty()) {
            files = new ArrayList<>();
            originalFiles = new ArrayList<>();
            for (ReviewItem.FileEntry entry : item.getFiles()) {
                File original = new File(entry.getOriginalPath());
                File processing = original;
                if (entry.getStagedPath() != null) {
                    File staged = new File(entry.getStagedPath());
                    if (staged.exists()) {
                        processing = staged;
                    } else {
                        log.warn("暂存的转码文件已丢失，回退使用原始文件（归档结果可能不再满足格式规范化配置）: {}",
                            entry.getStagedPath());
                        LogCollector.addLog("WARN", "暂存转码文件丢失，已回退原始文件: " + entry.getFileName());
                    }
                }
                if (!original.isFile()) throw new IOException("files.missing");
                files.add(processing);
                originalFiles.add(original);
                originalPathByProcessingPath.put(processing.getAbsolutePath(), original.getAbsolutePath());
            }
        }

        // 重算逐曲匹配：不依赖下标，且覆盖率按「可靠匹配数 / 本地文件数」计算，
        // 而不是只看联网候选列了多少行曲目。
        List<Integer> localDurations;
        try {
            localDurations = fingerprintService.extractDurationSequence(originalFiles);
        } catch (Exception e) {
            log.debug("无法提取时长序列，逐曲匹配将不使用时长证据: {}", e.getMessage());
            localDurations = null;
        }
        int confidentMatches = trackMatcher.match(chosen, originalFiles, localDurations);
        reviewQueue.update(item);
        if (!meetsCoverage(files.size(), confidentMatches)) {
            throw new IOException("online.track.coverage.insufficient:" + confidentMatches + "/" + files.size());
        }

        // 只有达到阈值的曲目才允许覆盖本地标签，其余保留原标签
        java.util.Map<String, ReviewItem.OnlineTrack> trackByFile = new java.util.HashMap<>();
        for (ReviewItem.OnlineTrack track : chosen.getTracks()) {
            if (track.getMatchedFilePath() != null
                && track.getMatchConfidence() >= OnlineTrackMatcher.MIN_CONFIDENCE) {
                trackByFile.put(track.getMatchedFilePath(), track);
            }
        }

        String artistDir = FileNameSanitizer.sanitize(finalArtist);
        String albumDir = FileNameSanitizer.sanitize(
            meaningful(archiveDirectoryName) ? archiveDirectoryName.trim()
                : (meaningful(edition) ? finalAlbum + " (" + edition.trim() + ")" : finalAlbum));

        CoverCandidateService.ResolvedCover selectedCover = null;
        byte[] cover = null;
        if (directCoverBytes != null && directCoverBytes.length > 0) {
            cover = compressCoverBytes(directCoverBytes);
        } else if (meaningful(selectedCoverSha256)) {
            selectedCover = coverCandidateService.resolveBySha256(
                selectedCoverSha256, chosen.getCoverCandidates())
                .orElseThrow(() -> new IOException("cover.selection.expired"));
            cover = selectedCover.data();
            chosen.setSelectedCoverSha256(selectedCoverSha256);
        } else {
            chosen.setSelectedCoverSha256(null);
            if (!confirmNoCover) {
                cover = findLocalFallbackCover(source, originalFiles);
                if ((cover == null || cover.length == 0)
                    && allowMissingCoverIntercept
                    && config.isCoverMissingRequireReview()) {
                    ReviewItem.MissingCover details = new ReviewItem.MissingCover();
                    details.setMode(ReviewItem.MissingCover.Mode.STAGED_IMPORT);
                    details.setSourcePipeline("REVIEW_ONLINE");
                    details.setAlbumTitle(finalAlbum);
                    details.setAlbumArtist(finalArtist);
                    if (finalAlbumArtists != null && !finalAlbumArtists.isEmpty()) {
                        details.setAlbumArtists(new ArrayList<>(finalAlbumArtists));
                    }
                    details.setReleaseDate(finalDate);
                    details.setArchiveDirectoryName(albumDir);
                    details.setRecordingIdSentinel("ONLINE_SEARCH");
                    details.setOnlineCandidateId(candidateId);
                    if (chosen.getCoverCandidates() != null && !chosen.getCoverCandidates().isEmpty()) {
                        details.setCoverCandidates(new ArrayList<>(chosen.getCoverCandidates()));
                    }
                    return reviewQueue.transitionToMissingCover(
                        item, details, "联网候选已确认，但缺少封面，等待补全或确认无封面入库");
                }
            }
        }

        File workRoot = resolveWorkRoot().resolve("online-" + item.getId()).toFile();
        if (workRoot.exists()) deleteRecursively(workRoot.toPath());
        Files.createDirectories(workRoot.toPath());
        List<Path> committed;
        // 入队前指纹识别阶段已从 MusicBrainz 拿到的曲目级元数据（作曲/作词/编曲/流派等），
        // 按原始路径索引；MusicBrainz 有值则覆盖源文件标签，没有则保留源文件原值。
        java.util.Map<String, MusicMetadata> queuedMetadataByOriginalPath = new java.util.HashMap<>();
        if (item.getFiles() != null) {
            for (ReviewItem.FileEntry entry : item.getFiles()) {
                if (entry.getOriginalPath() != null && entry.getMetadata() != null) {
                    queuedMetadataByOriginalPath.put(
                        new File(entry.getOriginalPath()).getAbsolutePath(), entry.getMetadata());
                }
            }
        }

        List<Path> replacedTargetPaths = collectReplacedTargetPaths(originalFiles);
        Map<Path, Path> newOutputByOriginalPath = new LinkedHashMap<>();
        Map<String, Path> outputByOriginalPath = new LinkedHashMap<>();
        try (TagWriterService.OutputScope scope = tagWriter.recoveryWorkspace(workRoot)) {
            for (File file : files) {
                MusicMetadata original = tagWriter.readTags(file);
                MusicMetadata md = original == null ? new MusicMetadata() : original;
                String matchedPath = originalPathByProcessingPath.getOrDefault(
                    file.getAbsolutePath(), file.getAbsolutePath());
                MetadataUtils.overlayTrackCredits(queuedMetadataByOriginalPath.get(matchedPath), md);
                md.setAlbum(finalAlbum);
                ArtistCreditSupport.applyAlbumArtistCredit(md, finalArtist, finalAlbumArtists, albumArtistPolicy);
                md.setReleaseDate(finalDate);
                ReviewItem.OnlineTrack track = trackByFile.get(matchedPath);
                if (track != null) {
                    if (meaningful(track.getTitle())) md.setTitle(track.getTitle());
                    if (meaningful(track.getArtist())) {
                        List<String> trackArtists = resolveOnlineTrackArtists(
                            track, finalArtist, finalAlbumArtists);
                        ArtistCreditSupport.applyTrackArtistCredit(md, track.getArtist(), trackArtists);
                    } else {
                        ensureTrackStructuredArtists(md);
                    }
                    if (track.getDiscNo() > 0) md.setDiscNo(String.valueOf(track.getDiscNo()));
                    if (track.getTrackNo() > 0) md.setTrackNo(String.valueOf(track.getTrackNo()));
                } else {
                    ensureTrackStructuredArtists(md);
                }
                TagWriterService.TagProcessResult result =
                    tagWriter.processFileToRootWithResult(file, md, cover, workRoot);
                if (!result.isSuccess() || result.getTargetFile() == null) {
                    throw new IOException("online.atomic.write.failed");
                }
                outputByOriginalPath.put(matchedPath, workRoot.toPath().toAbsolutePath()
                    .relativize(result.getTargetFile().toPath()));
            }

            // 归档目录名可能带版本号，与 TagWriter 依据专辑名生成的目录不同。
            // 先在工作区内改名，提交阶段就能统一按相对路径镜像，不必特判某一层目录。
            Path built = workRoot.toPath().resolve(artistDir).resolve(FileNameSanitizer.sanitize(finalAlbum));
            if (Files.isDirectory(built) && !albumDir.equals(built.getFileName().toString())) {
                Path renamed = built.resolveSibling(albumDir);
                Files.move(built, renamed);
                Path oldRelative = workRoot.toPath().relativize(built);
                Path newRelative = workRoot.toPath().relativize(renamed);
                outputByOriginalPath.replaceAll((original, relative) -> relative.startsWith(oldRelative)
                    ? newRelative.resolve(oldRelative.relativize(relative)) : relative);
            }
            String linkKey = SourceType.FAILED.name().equals(item.getRecoverySourceType()) ? source.getName() : null;
            List<String> extraArtists = new ArrayList<>();
            if (finalAlbumArtists != null) {
                for (String a : finalAlbumArtists) {
                    if (meaningful(a)) extraArtists.add(FileNameSanitizer.sanitize(a));
                }
            }
            extraArtists.add(FileNameSanitizer.sanitize(AlbumArtistPolicy.VARIOUS_ARTISTS));
            committed = commitReplacingDuplicates(workRoot.toPath(), files.size(), item.getId(),
                linkKey, duplicatePolicy, replacedTargetPaths, extraArtists);
            clearReplacementEntries(originalFiles);
        } catch (IOException e) {
            deleteRecursively(workRoot.toPath());
            throw e;
        }

        // 输出已落地，立即落盘提交阶段；之后任一步失败都能在启动时接着收尾，而不会重写输出。
        item.setVerificationSource(ReviewItem.VerificationSource.ONLINE_SEARCH);
        item.setResolvedAlbumTitle(finalAlbum);
        item.setResolvedAlbumArtist(finalArtist);
        item.setResolvedReleaseDate(finalDate);
        item.setResolvedTrackCount(files.size());
        item.setRecoveryCommitState(ReviewItem.RecoveryCommitState.COMMITTED);
        item.setCommittedOutputPaths(committed.stream().map(Path::toString).toList());
        reviewQueue.update(item);

        for (File file : originalFiles) {
            Path relative = outputByOriginalPath.get(file.getAbsolutePath());
            String targetPath = Path.of(config.getOutputDirectory()).resolve(relative)
                .toFile().getAbsolutePath();
            ReviewItem.OnlineTrack matchedTrack = trackByFile.get(file.getAbsolutePath());
            String trackTitle = matchedTrack != null && meaningful(matchedTrack.getTitle())
                ? matchedTrack.getTitle() : file.getName();
            // 如果源文件来自待确认临时暂存目录（即将随 markResolved 清理），记录最终归档路径以免处理记录立即断链。
            File recordSource = item.getStagingDir() != null ? new File(targetPath) : file;
            processedLogger.markFileAsProcessed(recordSource, "ONLINE_SEARCH", finalArtist,
                trackTitle, finalAlbum, null, targetPath);
        }

        if (selectedCover != null) {
            coverCandidateService.promoteIfMusicBrainz(selectedCover);
        }
        finishCommittedRecovery(item);
        return item;
    }

    /** 供「缺少封面」服务在提交完来自 recovery 目录的条目后统一回收隔离副本。 */
    public void retireRecoveryCopyIfNeeded(ReviewItem item) throws IOException {
        if (item == null || item.getRecoverySourceType() == null || item.getRecoverySourcePath() == null) {
            return;
        }
        finishCommittedRecovery(item);
    }

    private byte[] compressCoverBytes(byte[] raw) {
        if (raw == null || raw.length == 0) return null;
        if (coverCandidateService != null && coverCandidateService.getCoverArtService() != null) {
            return coverCandidateService.getCoverArtService().compressCover(raw);
        }
        String ffmpeg = config == null ? null : config.getAudioNormalizeFfmpegPath();
        return ImageCompressor.compressImage(raw, (ffmpeg == null || ffmpeg.isBlank()) ? "ffmpeg" : ffmpeg);
    }

    private byte[] findLocalFallbackCover(File source, List<File> audioFiles) {
        CoverArtService cas = coverCandidateService != null ? coverCandidateService.getCoverArtService() : null;
        if (cas != null) {
            if (source != null) {
                File dir = source.isDirectory() ? source : source.getParentFile();
                if (dir != null) {
                    byte[] dirCover = cas.findCoverInDirectory(dir);
                    if (dirCover != null && dirCover.length > 0) return dirCover;
                    File root = cas.resolveAlbumRootDirectory(dir);
                    if (root != null && !root.equals(dir)) {
                        dirCover = cas.findCoverInDirectory(root);
                        if (dirCover != null && dirCover.length > 0) return dirCover;
                    }
                }
            }
            if (audioFiles != null) {
                for (File f : audioFiles) {
                    byte[] embedded = cas.extractEmbeddedCover(f);
                    if (embedded != null && embedded.length > 0) return embedded;
                }
            }
        }
        return null;
    }

    /**
     * 把工作区整体提交到 outputDirectory。
     *
     * 按相对路径镜像，不对目录层级做任何假设：TagWriter 可能产出
     * artist/album/file、artist/file 或（无艺术家时）根级 file 三种形态，
     * 早期实现只识别第一种，另外两种会被跳过后随工作区一起删除。
     *
     * 提交前强制校验音频文件数：数量不符说明有文件写到了工作区之外
     * （例如 ThreadLocal 输出根失效），或被同名覆盖，此时必须整体失败。
     */
    private List<Path> commitWorkspace(Path workspace, int expectedAudioCount, String taskId) throws IOException {
        return commitWorkspace(workspace, expectedAudioCount, taskId, Set.of());
    }

    private List<Path> commitWorkspace(Path workspace, int expectedAudioCount, String taskId,
                                       Set<Path> mergeUnits) throws IOException {
        try (var libraryWrite = LibraryWriteGuard.acquire()) {
            if (!Files.isDirectory(workspace)) throw new IOException("recovery.workspace.missing");

            List<Path> allFiles = listFiles(workspace);
            long audioCount = allFiles.stream().filter(p -> fileSystemUtils.isMusicFile(p.toFile())).count();
            if (audioCount != expectedAudioCount) {
                throw new IOException("recovery.workspace.count.mismatch:" + audioCount + "/" + expectedAudioCount);
            }

            // 提交单元：有专辑层时以 artist/album 整个目录为单位，否则退化到单文件。
            // 以目录为单位才能用一次 rename 完成提交，避免逐文件写入时中途失败留下半张专辑。
            Path outputRoot = Path.of(config.getOutputDirectory());
            Set<Path> units = new LinkedHashSet<>();
            for (Path file : allFiles) {
                Path relative = workspace.relativize(file);
                units.add(relative.getNameCount() >= 3 ? relative.subpath(0, 2) : relative);
            }
            for (Path unit : units) {
                if (Files.exists(outputRoot.resolve(unit)) && !mergeUnits.contains(unit)) {
                    throw new IOException("online.destination.exists");
                }
            }

            List<Path> committed = new ArrayList<>();
            List<Path> mergedFiles = new ArrayList<>();
            try {
                for (Path unit : units) {
                    Path destination = outputRoot.resolve(unit);
                    Path source = workspace.resolve(unit);
                    if (mergeUnits.contains(unit) && Files.isDirectory(destination) && Files.isDirectory(source)) {
                        for (Path newFile : listFiles(source)) {
                            Path rel = source.relativize(newFile);
                            Path targetFile = destination.resolve(rel);
                            if (Files.exists(targetFile)) {
                                throw new IOException("online.destination.exists");
                            }
                            Files.createDirectories(targetFile.getParent());
                            commitUnit(newFile, targetFile, taskId);
                            processedLogger.rebaseTargetPaths(newFile.toString(), targetFile.toString());
                            mergedFiles.add(targetFile);
                            committed.add(targetFile);
                        }
                    } else {
                        Files.createDirectories(destination.getParent());
                        commitUnit(source, destination, taskId);
                        processedLogger.rebaseTargetPaths(source.toString(), destination.toString());
                        committed.add(destination);
                    }
                }
            } catch (IOException failure) {
                rollbackCommitted(committed, workspace, outputRoot, failure);
                throw failure;
            }
            deleteRecursively(workspace);
            return committed;

        }
    }

    /** 被替换掉的旧输出：原位置 + 回收站条目（提交失败时据此搬回）。 */
    private record RetiredOutput(Path original, Path trashEntry) { }

    /** 待删除替换的旧文件/目录（提交前先移到隐藏暂存位，提交成功后彻底删除，失败则还原）。 */
    private record StagedReplacement(Path original, Path stagedBackup) { }

    List<Path> commitReplacingDuplicates(Path workspace, int expectedAudioCount, String taskId,
                                         String failedRelativePath, DuplicatePolicy policy) throws IOException {
        return commitReplacingDuplicates(workspace, expectedAudioCount, taskId, failedRelativePath, policy, List.of());
    }

    /**
     * 带重复音频保护的提交。
     *
     * 1. 失败副本关联了 LLM 自动归档且归档未被改动过 → 无条件替换（它就是同一份输入）。
     * 2. 已被「重新识别 / 删除记录」登记替换的旧输出 → 提交成功后直接删除并清理空目录。
     * 3. 其余目标艺术家目录里的相同音频按 {@link DuplicatePolicy} 处理。
     * 旧输出先移入回收站或暂存位再提交，提交失败则搬回原位。
     *
     * @param failedRelativePath 来源为 FAILED 副本时的条目名，用于查自动归档链接；否则为 null
     */
    List<Path> commitReplacingDuplicates(Path workspace, int expectedAudioCount, String taskId,
                                         String failedRelativePath, DuplicatePolicy policy,
                                         java.util.Collection<Path> replacedTargetPaths) throws IOException {
        return commitReplacingDuplicates(workspace, expectedAudioCount, taskId, failedRelativePath,
            policy, replacedTargetPaths, List.of());
    }

    List<Path> commitReplacingDuplicates(Path workspace, int expectedAudioCount, String taskId,
                                         String failedRelativePath, DuplicatePolicy policy,
                                         java.util.Collection<Path> replacedTargetPaths,
                                         java.util.Collection<String> extraSearchArtistDirs) throws IOException {
        try (var libraryWrite = LibraryWriteGuard.acquire()) {
            if (policy == null) policy = DuplicatePolicy.ASK;
            Path outputRoot = Path.of(config.getOutputDirectory()).toAbsolutePath().normalize();

            List<Path> toRetire = new ArrayList<>();
            validAutoArchive(failedRelativePath, outputRoot).ifPresent(toRetire::add);

            Set<Path> replacedFiles = new LinkedHashSet<>();
            if (replacedTargetPaths != null) {
                for (Path p : replacedTargetPaths) {
                    if (p == null) continue;
                    Path norm = p.toAbsolutePath().normalize();
                    if (norm.startsWith(outputRoot) && !norm.equals(outputRoot) && Files.isRegularFile(norm)) {
                        replacedFiles.add(norm);
                    }
                }
            }

            List<Path> incoming = Files.isDirectory(workspace)
                ? listFiles(workspace).stream().filter(p -> fileSystemUtils.isMusicFile(p.toFile())).toList()
                : List.of();
            Set<Path> searchRoots = new LinkedHashSet<>();
            for (Path file : incoming) {
                Path relative = workspace.relativize(file);
                if (relative.getNameCount() >= 2) searchRoots.add(outputRoot.resolve(relative.getName(0)));
            }
            if (extraSearchArtistDirs != null) {
                for (String artistDir : extraSearchArtistDirs) {
                    if (meaningful(artistDir)) {
                        searchRoots.add(outputRoot.resolve(artistDir.trim()));
                    }
                }
            }
            List<Path> excluded = new ArrayList<>(toRetire);
            excluded.addAll(replacedFiles);
            List<ArchiveDuplicateDetector.DuplicateGroup> duplicates =
                duplicateDetector.find(incoming, outputRoot, searchRoots, excluded);
            if (!duplicates.isEmpty()) {
                switch (policy) {
                    case ASK -> throw new DuplicateArchiveException(
                        duplicates.stream().map(d -> d.toView(outputRoot)).toList());
                    case REPLACE -> duplicates.stream()
                        .filter(ArchiveDuplicateDetector.DuplicateGroup::replaceable)
                        .map(ArchiveDuplicateDetector.DuplicateGroup::path)
                        .forEach(toRetire::add);
                    case KEEP_BOTH -> log.info("音乐库已有相同音频，按用户选择保留两份: {}",
                        duplicates.stream().map(d -> d.path().toString()).toList());
                }
            }

            List<RetiredOutput> retired = retireOutputs(toRetire, taskId);
            List<StagedReplacement> stagedReplacements = new ArrayList<>();
            Set<Path> mergeUnits = new LinkedHashSet<>();
            List<Path> committed;
            try {
                stageCollidingReplacements(workspace, outputRoot, replacedFiles, taskId, stagedReplacements, mergeUnits);
                committed = commitWorkspace(workspace, expectedAudioCount, taskId, mergeUnits);
            } catch (IOException e) {
                restoreStagedReplacements(stagedReplacements);
                restoreRetired(retired);
                throw e;
            }
            cleanupStagedReplacements(stagedReplacements, committed, outputRoot);
            Set<Path> committedFiles = collectCommittedRegularFiles(committed);
            List<String> staleReplacedRecordPaths = new ArrayList<>();
            for (Path oldFile : replacedFiles) {
                if (!committedFiles.contains(oldFile)) {
                    ReidentifyReplacementRegistry.deleteReplacedFileAndEmptyParents(
                        oldFile, null, outputRoot, config.isExportLyricsToFile());
                    staleReplacedRecordPaths.add(oldFile.toString());
                }
            }
            if (!staleReplacedRecordPaths.isEmpty() && processedLogger != null) {
                processedLogger.removeProcessedRecords(staleReplacedRecordPaths);
            }
            if (failedRelativePath != null) {
                autoArchiveRegistry.remove(failedRelativePath);
            }
            for (RetiredOutput r : retired) {
                log.info("已用本次恢复结果替换旧归档（可在回收站还原）: {}", r.original());
                LogCollector.addLog("INFO", "已替换重复的旧归档，原目录已移入回收站: "
                    + outputRoot.relativize(r.original()));
            }
            return committed;

        }
    }

    /**
     * 只有自动归档仍是当时的样子才允许无条件替换：位于输出目录内，
     * 且没有出现登记清单之外的音频（用户或其他流程往里添了曲目就不再替换，交给重复检测询问用户）。
     */
    private java.util.Optional<Path> validAutoArchive(String failedRelativePath, Path outputRoot) {
        if (failedRelativePath == null) return java.util.Optional.empty();
        AutoArchiveRegistry.Link link = autoArchiveRegistry.find(failedRelativePath).orElse(null);
        if (link == null || link.getArchivedPath() == null) return java.util.Optional.empty();
        Path archived = Path.of(link.getArchivedPath()).toAbsolutePath().normalize();
        if (!archived.startsWith(outputRoot) || archived.equals(outputRoot) || !Files.exists(archived)) {
            return java.util.Optional.empty();
        }
        if (Files.isDirectory(archived)) {
            Set<String> recorded = new java.util.HashSet<>(link.getAudioFiles());
            List<String> current = collectAudioFiles(archived.toFile()).stream()
                .map(f -> archived.relativize(f.toPath().toAbsolutePath().normalize()).toString()).toList();
            if (current.isEmpty() || !recorded.containsAll(current)) {
                log.info("自动归档目录已被改动，不再自动替换: {}", archived);
                return java.util.Optional.empty();
            }
        } else if (!fileSystemUtils.isMusicFile(archived.toFile())) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(archived);
    }

    private List<RetiredOutput> retireOutputs(List<Path> paths, String taskId) throws IOException {
        List<RetiredOutput> retired = new ArrayList<>();
        Set<Path> distinct = new LinkedHashSet<>();
        for (Path path : paths) {
            Path normalized = path.toAbsolutePath().normalize();
            // 父目录已在清单里时不重复移动子项
            if (distinct.stream().noneMatch(normalized::startsWith)) distinct.add(normalized);
        }
        try {
            for (Path path : distinct) {
                if (!Files.exists(path)) continue;
                Path entry = moveOutputToTrash(path, taskId);
                retired.add(new RetiredOutput(path, entry));
                if (processedLogger != null) processedLogger.clearTargetPathsUnder(path.toString());
            }
        } catch (IOException e) {
            restoreRetired(retired);
            throw e;
        }
        return retired;
    }

    private void restoreRetired(List<RetiredOutput> retired) {
        for (RetiredOutput r : retired) {
            try {
                if (Files.exists(r.original())) {
                    log.error("无法还原被替换的旧归档，原位置已被占用，保留在回收站: {}", r.trashEntry());
                    continue;
                }
                Files.createDirectories(r.original().getParent());
                moveAcrossDevices(r.trashEntry(), r.original());
                Files.deleteIfExists(r.trashEntry().resolveSibling(r.trashEntry().getFileName() + ".json"));
            } catch (IOException e) {
                log.error("提交失败后还原旧归档受阻，可在回收站手动还原: {}", r.trashEntry(), e);
            }
        }
    }

    /**
     * 单个提交单元的落地，优先级：ATOMIC_MOVE → 普通 rename → 跨文件系统复制。
     * 跨文件系统时先复制到目标同级的隐藏暂存目录、校验文件数后再改名，
     * 确保任何时刻都不会在最终输出路径上出现不完整的专辑。
     */
    private void commitUnit(Path source, Path destination, String taskId) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (IOException | UnsupportedOperationException atomicUnsupported) {
            log.debug("ATOMIC_MOVE 不可用，降级为普通移动: {}", destination);
        }
        try {
            Files.move(source, destination);
            return;
        } catch (IOException crossDevice) {
            log.debug("普通移动失败，改为跨文件系统复制提交: {}", destination);
        }

        Path staging = destination.resolveSibling("." + destination.getFileName() + ".commit-" + taskId);
        if (Files.exists(staging)) deleteRecursively(staging);
        try {
            if (Files.isDirectory(source)) {
                fileSystemUtils.copyDirectoryRecursively(source, staging);
                if (listFiles(staging).size() != listFiles(source).size()) {
                    throw new IOException("recovery.commit.copy.incomplete");
                }
            } else {
                Files.createDirectories(staging.getParent());
                Files.copy(source, staging);
                if (Files.size(staging) != Files.size(source)) {
                    throw new IOException("recovery.commit.copy.incomplete");
                }
            }
            Files.move(staging, destination);
            deleteRecursively(source);
        } catch (IOException e) {
            deleteRecursively(staging);
            throw e;
        }
    }

    /** 多单元提交中途失败时，把已落地的单元搬回工作区，避免输出目录残留半成品。 */
    private void rollbackCommitted(List<Path> committed, Path workspace, Path outputRoot, IOException cause) {
        try (var libraryWrite = LibraryWriteGuard.acquire()) {
            for (Path destination : committed) {
                try {
                    Path back = workspace.resolve(outputRoot.relativize(destination));
                    Files.createDirectories(back.getParent());
                    Files.move(destination, back);
                    processedLogger.clearTargetPathsUnder(destination.toString());
                } catch (IOException e) {
                    processedLogger.clearTargetPathsUnder(destination.toString());
                    log.error("提交失败后回滚受阻，输出目录可能残留部分内容，需人工清理: {}（原因: {}）",
                        destination, cause.getMessage(), e);
                }
            }

        }
    }

    private List<Path> listFiles(Path root) throws IOException {
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private Path resolveWorkRoot() {
        String configured = config.getRecoveryWorkDirectory();
        return Path.of(configured == null || configured.isBlank()
            ? Path.of(config.getOutputDirectory(), ".recovery-work").toString() : configured);
    }

    private List<String> resolveOnlineAlbumArtists(String finalArtist, ReviewItem.OnlineCandidate chosen,
                                                 String chosenEffectiveAlbumArtist,
                                                 List<String> manualAlbumArtists) {
        List<String> manual = ArtistCreditSupport.cleanNames(manualAlbumArtists);
        if (!manual.isEmpty()) {
            return manual;
        }
        if (chosenEffectiveAlbumArtist != null && finalArtist.equalsIgnoreCase(chosenEffectiveAlbumArtist.trim())) {
            List<String> candidateAlbumArtists = ArtistCreditSupport.cleanNames(chosen.getAlbumArtists());
            if (!candidateAlbumArtists.isEmpty()) {
                return candidateAlbumArtists;
            }
            if (chosen.getArtist() != null && finalArtist.equalsIgnoreCase(chosen.getArtist().trim())) {
                List<String> candidateArtists = ArtistCreditSupport.cleanNames(chosen.getArtists());
                if (!candidateArtists.isEmpty()) {
                    return candidateArtists;
                }
            }
        }
        return splitDisplayArtists(finalArtist);
    }

    private List<String> resolveOnlineTrackArtists(ReviewItem.OnlineTrack track, String finalAlbumArtist,
                                                 List<String> finalAlbumArtists) {
        List<String> explicit = ArtistCreditSupport.cleanNames(track.getArtists());
        if (!explicit.isEmpty()) {
            return explicit;
        }
        String trackArtist = track.getArtist() == null ? "" : track.getArtist().trim();
        if (trackArtist.isEmpty()) {
            return List.of();
        }
        if (finalAlbumArtist != null && trackArtist.equalsIgnoreCase(finalAlbumArtist.trim())
                && finalAlbumArtists != null && !finalAlbumArtists.isEmpty()) {
            return finalAlbumArtists;
        }
        return splitDisplayArtists(trackArtist);
    }

    private void ensureTrackStructuredArtists(MusicMetadata md) {
        if (md == null || !meaningful(md.getArtist())) {
            return;
        }
        if (md.getArtists() != null && !md.getArtists().isEmpty()) {
            return;
        }
        List<String> split = splitDisplayArtists(md.getArtist());
        if (!split.isEmpty()) {
            ArtistCreditSupport.applyTrackArtistCredit(md, md.getArtist(), split);
        }
    }

    private List<String> splitDisplayArtists(String display) {
        if (!meaningful(display)) {
            return List.of();
        }
        LlmArtistSplitter.SplitResult split = artistSplitter != null ? artistSplitter.split(display) : null;
        if (split != null && split.hasStructuredArtists()) {
            return split.artists();
        }
        return ArtistCreditSupport.looksComposite(display) ? List.of() : List.of(display.trim());
    }

    private Path findReplacedTarget(File source) {
        Path tracked = replacementRegistry.findOldTarget(source).orElse(null);
        if (tracked != null || processedLogger == null) {
            return tracked;
        }
        String existing = processedLogger.findTargetPath(source);
        return meaningful(existing) ? Path.of(existing) : null;
    }

    private List<Path> collectReplacedTargetPaths(List<File> sourceFiles) {
        return sourceFiles.stream().map(this::findReplacedTarget).filter(java.util.Objects::nonNull).toList();
    }

    private void clearReplacementEntries(List<File> sourceFiles) {
        for (File source : sourceFiles) {
            replacementRegistry.remove(source);
        }
    }

    private void stageCollidingReplacements(Path workspace, Path outputRoot, Set<Path> replacedFiles,
                                            String taskId, List<StagedReplacement> staged,
                                            Set<Path> mergeUnits) throws IOException {
        if (replacedFiles.isEmpty() || !Files.isDirectory(workspace)) {
            return;
        }
        Set<Path> units = new LinkedHashSet<>();
        for (Path file : listFiles(workspace)) {
            Path relative = workspace.relativize(file);
            units.add(relative.getNameCount() >= 3 ? relative.subpath(0, 2) : relative);
        }
        Path backupRoot = resolveWorkRoot().resolve(".replace-" + taskId);
        int index = 0;
        for (Path unit : units) {
            Path destination = outputRoot.resolve(unit).toAbsolutePath().normalize();
            if (!Files.exists(destination)) {
                continue;
            }
            if (Files.isDirectory(destination)) {
                List<Path> inside = replacedFiles.stream().filter(p -> p.startsWith(destination)).toList();
                if (inside.isEmpty()) {
                    continue;
                }
                List<File> existingAudio = collectAudioFiles(destination.toFile());
                boolean allReplaced = !existingAudio.isEmpty() && existingAudio.stream()
                    .allMatch(f -> replacedFiles.contains(f.toPath().toAbsolutePath().normalize()));
                if (allReplaced) {
                    Files.createDirectories(backupRoot);
                    Path backup = backupRoot.resolve("unit-" + (index++));
                    moveAcrossDevices(destination, backup);
                    staged.add(new StagedReplacement(destination, backup));
                } else {
                    for (Path oldFile : inside) {
                        if (Files.exists(oldFile)) {
                            Files.createDirectories(backupRoot);
                            Path backup = backupRoot.resolve("file-" + (index++));
                            moveAcrossDevices(oldFile, backup);
                            staged.add(new StagedReplacement(oldFile, backup));
                        }
                    }
                    mergeUnits.add(unit);
                }
            } else if (Files.isRegularFile(destination) && replacedFiles.contains(destination)) {
                Files.createDirectories(backupRoot);
                Path backup = backupRoot.resolve("file-" + (index++));
                moveAcrossDevices(destination, backup);
                staged.add(new StagedReplacement(destination, backup));
            }
        }
    }

    private void restoreStagedReplacements(List<StagedReplacement> staged) {
        for (int i = staged.size() - 1; i >= 0; i--) {
            StagedReplacement item = staged.get(i);
            try {
                if (Files.exists(item.stagedBackup()) && !Files.exists(item.original())) {
                    Files.createDirectories(item.original().getParent());
                    moveAcrossDevices(item.stagedBackup(), item.original());
                }
            } catch (IOException e) {
                log.error("提交失败后还原被替换的旧归档失败: {} <- {}", item.original(), item.stagedBackup(), e);
            }
        }
    }

    private void cleanupStagedReplacements(List<StagedReplacement> staged, List<Path> committed, Path outputRoot) {
        Set<Path> committedFiles = collectCommittedRegularFiles(committed);
        for (StagedReplacement item : staged) {
            try {
                deleteRecursively(item.stagedBackup());
                if (!committedFiles.contains(item.original())) {
                    ReidentifyReplacementRegistry.deleteReplacedFileAndEmptyParents(
                        item.original(), null, outputRoot, config.isExportLyricsToFile());
                }
            } catch (IOException e) {
                log.warn("清理暂存的旧归档副本失败: {} - {}", item.stagedBackup(), e.getMessage());
            }
        }
        if (!staged.isEmpty()) {
            try {
                deleteRecursively(staged.get(0).stagedBackup().getParent());
            } catch (IOException e) {
                log.debug("清理临时替换备份目录失败: {}", e.getMessage());
            }
        }
    }

    private Set<Path> collectCommittedRegularFiles(List<Path> committed) {
        Set<Path> files = new LinkedHashSet<>();
        for (Path entry : committed) {
            if (Files.isRegularFile(entry)) {
                files.add(entry.toAbsolutePath().normalize());
            } else if (Files.isDirectory(entry)) {
                try {
                    for (Path f : listFiles(entry)) {
                        files.add(f.toAbsolutePath().normalize());
                    }
                } catch (IOException e) {
                    log.debug("列举已提交文件失败 ({}): {}", entry, e.getMessage());
                }
            }
        }
        return files;
    }

    private boolean meetsCoverage(int files, int tracks) {
        int matched = Math.min(files, tracks);
        if (files <= 3) return matched == files;
        if (files <= 7) return matched >= files - 1;
        return matched >= (int) Math.ceil(files * 0.8);
    }

    private boolean meaningful(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public OnlineSearchQueueService.EnqueueResult enqueueOnlineSearch(SourceType type, String relativePath,
                                                                      boolean analyzeCover) throws IOException {
        File target = resolveItem(type, relativePath);
        if (!target.exists()) {
            throw new IOException("recovery.item.not.found");
        }
        if (!onlineIdentificationService.isAvailable()) {
            throw new IOException("llm.web.search.unavailable");
        }
        String key = target.getCanonicalPath();
        if (runningPaths.contains(key)) {
            throw new IOException("recovery.already.running");
        }
        int fileCount = collectAudioFiles(target).size();
        return searchQueueService.enqueueRecoveryTarget(type.name(), relativePath, target, fileCount, analyzeCover);
    }

    public String submit(SourceType type, String relativePath, RecoveryMode mode, boolean analyzeCover) throws IOException {
        if (mode == RecoveryMode.ONLINE_SEARCH) {
            OnlineSearchQueueService.EnqueueResult res = enqueueOnlineSearch(type, relativePath, analyzeCover);
            if (res.alreadyActive()) {
                throw new IOException("recovery.already.running");
            }
            return String.valueOf(res.task().get("id"));
        }

        File target = resolveItem(type, relativePath);
        if (!target.exists()) {
            throw new IOException("recovery.item.not.found");
        }
        if (mode == RecoveryMode.LOCAL_ARTIST_MATCH && type != SourceType.PARTIAL) {
            throw new IOException("recovery.llm.partial.only");
        }

        String key = target.getCanonicalPath();
        if (searchQueueService != null && searchQueueService.isFolderActive(key)) {
            throw new IOException("recovery.already.running");
        }
        if (!runningPaths.add(key)) {
            throw new IOException("recovery.already.running");
        }

        RecoveryJob job = new RecoveryJob();
        job.id = UUID.randomUUID().toString();
        job.sourceType = type;
        job.relativePath = relativePath;
        job.path = key;
        job.useLlm = mode != RecoveryMode.REIDENTIFY;
        job.mode = mode;
        job.analyzeCover = analyzeCover;
        job.status = JobStatus.QUEUED;
        job.createdAt = System.currentTimeMillis();
        job.updatedAt = job.createdAt;
        jobs.put(job.id, job);

        executor.submit(() -> runJob(job, target, key));
        return job.id;
    }

    private void runJob(RecoveryJob job, File target, String runningKey) {
        job.status = JobStatus.RUNNING;
        job.updatedAt = System.currentTimeMillis();
        File processingTarget = target;
        File singleFileWorkspace = null;
        try {
            if (job.mode == RecoveryMode.ONLINE_SEARCH) {
                File albumRoot = target;
                ReviewItem item = onlineIdentificationService.search(albumRoot, job.sourceType.name(), job.analyzeCover);
                if (item != null) job.reviewItemId = item.getId();
                complete(job, JobStatus.WAITING_REVIEW, "联网搜索完成，候选已进入待确认页面");
                return;
            }
            if (job.mode == RecoveryMode.LOCAL_ARTIST_MATCH) {
                boolean matched = failedFileHandler.retryPartialWithLlm(target);
                if (matched) {
                    // 主副本已经被匹配流程移动到输出目录，只需清理另一隔离目录里的历史重复副本。
                    cleanupAssociatedRecoveryCopy(job);
                    complete(job, JobStatus.SUCCEEDED, "本地艺术家目录匹配成功，已移动到输出目录；关联隔离副本已清理");
                } else {
                    complete(job, JobStatus.FAILED, "LLM 未找到可靠的现有艺术家目录，文件保持不变");
                }
                return;
            }

            // 隔离目录根部的单文件不能直接交给专辑处理器：它不在 monitorDirectory 下，
            // FileSystemUtils 会把整个 partial/failed 根目录误当成同一张专辑。
            // 为它创建独立工作目录，确保文件夹级识别只看到这一首。
            if (target.isFile()) {
                singleFileWorkspace = resolveWorkRoot().resolve("single-" + job.id).toFile();
                Files.createDirectories(singleFileWorkspace.toPath());
                processingTarget = new File(singleFileWorkspace, target.getName());
                Files.copy(target.toPath(), processingTarget.toPath());
                recordReplacementAlias(processingTarget.toPath(), findReplacedTarget(target));
            }

            List<File> audioFiles = collectAudioFiles(processingTarget);
            if (audioFiles.isEmpty()) {
                complete(job, JobStatus.FAILED, "没有找到支持的音频文件");
                return;
            }

            List<Path> replacedTargetPaths = collectReplacedTargetPaths(audioFiles);
            // 失败处置会把文件记为 processed。人工重试必须先撤销这些路径的旧记录，
            // 否则处理器会在入口处直接返回“已处理”。
            for (File audioFile : audioFiles) {
                processedLogger.removeProcessedRecord(audioFile);
            }

            int succeeded = 0;
            int failed = 0;
            File reidentifyWorkRoot = resolveWorkRoot().resolve("reidentify-" + job.id).toFile();
            if (reidentifyWorkRoot.exists()) deleteRecursively(reidentifyWorkRoot.toPath());
            Files.createDirectories(reidentifyWorkRoot.toPath());
            try (TagWriterService.OutputScope scope = tagWriter.recoveryWorkspace(reidentifyWorkRoot)) {
                for (File audioFile : audioFiles) {
                    ProcessResult result = processor.processRecoveryFile(audioFile, processingTarget);
                    if (result == ProcessResult.SUCCESS) {
                        succeeded++;
                    } else {
                        failed++;
                        // 专辑识别失败时现有流程会把整个目录标记为 processed，继续循环没有意义。
                        if (target.isDirectory()) {
                            break;
                        }
                    }
                }
            }
            job.successCount = succeeded;
            job.failedCount = failed;

            String reviewFolder = processingTarget.isDirectory()
                ? processingTarget.getAbsolutePath() : processingTarget.getParentFile().getAbsolutePath();
            if (reviewQueue != null && reviewQueue.isFolderUnderReview(reviewFolder)) {
                // 待确认 / 缺少封面阶段不应留下任何正式输出；候选和文件引用已由队列持久化。
                ReviewItem queued = reviewQueue.findPendingByFolder(reviewFolder);
                boolean isCoverMissing = false;
                if (queued != null) {
                    job.reviewItemId = queued.getId();
                    isCoverMissing = queued.effectiveKind() == ReviewItem.Kind.COVER_MISSING;
                    if (isCoverMissing) {
                        final boolean attachRecoverySource = (singleFileWorkspace == null);
                        reviewQueue.updatePending(queued.getId(), itemToUpdate -> {
                            if (attachRecoverySource) {
                                itemToUpdate.setRecoverySourceType(job.sourceType.name());
                                itemToUpdate.setRecoverySourcePath(target.getAbsolutePath());
                            }
                            if (itemToUpdate.getMissingCover() != null) {
                                itemToUpdate.getMissingCover().setSourcePipeline("REIDENTIFY");
                            }
                            return true;
                        });
                    }
                }
                deleteRecursively(reidentifyWorkRoot.toPath());
                if (singleFileWorkspace != null) {
                    Files.deleteIfExists(target.toPath());
                }
                complete(job, JobStatus.WAITING_REVIEW,
                    isCoverMissing ? "重新识别后缺少封面，已转入「缺少封面」页面等待补图"
                        : "重新识别后进入待人工确认队列，文件已转交待确认页");
            } else if (failed == 0 && succeeded == audioFiles.size()) {
                Map<String, Path> tempTargets = collectTempRelativeTargets(reidentifyWorkRoot.toPath(), audioFiles);
                try {
                    commitReplacingDuplicates(reidentifyWorkRoot.toPath(), audioFiles.size(), job.id,
                        job.sourceType == SourceType.FAILED ? job.relativePath : null,
                        DuplicatePolicy.ASK, replacedTargetPaths);
                    clearReplacementEntries(audioFiles);
                } catch (IOException commitError) {
                    deleteRecursively(reidentifyWorkRoot.toPath());
                    throw commitError;
                }
                retireRecoveryCopies(target, job);
                finalizeCommittedRecords(tempTargets, audioFiles);
                if (singleFileWorkspace != null) {
                    deleteRecursively(singleFileWorkspace.toPath());
                }
                complete(job, JobStatus.SUCCEEDED, "重新识别成功，整张专辑已原子提交，关联隔离副本已清理");
            } else {
                deleteRecursively(reidentifyWorkRoot.toPath());
                if (singleFileWorkspace != null) {
                    deleteRecursively(singleFileWorkspace.toPath());
                }
                complete(job, JobStatus.FAILED,
                    "重新识别仍未全部成功（成功 " + succeeded + "，失败 " + failed + "），文件已保留");
            }
        } catch (Exception e) {
            if (e instanceof DuplicateArchiveException duplicate) {
                log.warn("人工恢复未提交，音乐库已有相同音频: {} - {}", target, duplicate.describe());
            } else {
                log.error("人工恢复任务失败: {}", target, e);
            }
            if (singleFileWorkspace != null && (reviewQueue == null
                || !reviewQueue.isFolderUnderReview(singleFileWorkspace.getAbsolutePath()))) {
                try {
                    deleteRecursively(singleFileWorkspace.toPath());
                } catch (IOException cleanupError) {
                    log.debug("清理恢复工作目录失败: {}", cleanupError.getMessage());
                }
            }
            complete(job, JobStatus.FAILED, e instanceof DuplicateArchiveException duplicate ? duplicate.describe()
                : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            runningPaths.remove(runningKey);
        }
    }

    private void complete(RecoveryJob job, JobStatus status, String message) {
        job.status = status;
        job.message = message;
        job.updatedAt = System.currentTimeMillis();
        LogCollector.addLog(status == JobStatus.SUCCEEDED ? "SUCCESS" :
            status == JobStatus.WAITING_REVIEW ? "WARN" : "ERROR",
            "人工恢复: " + job.relativePath + " - " + message);
    }

    private void collectRoot(List<Map<String, Object>> result, SourceType type, String configuredRoot) {
        if (configuredRoot == null || configuredRoot.trim().isEmpty()) {
            return;
        }
        File root = new File(configuredRoot.trim());
        File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.getName().startsWith(".recovery-")) {
                continue;
            }
            if (!child.isDirectory() && !fileSystemUtils.isMusicFile(child)) {
                continue;
            }
            List<File> audioFiles = collectAudioFiles(child);
            if (audioFiles.isEmpty()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceType", type.name());
            item.put("relativePath", child.getName());
            item.put("name", child.getName());
            item.put("path", child.getAbsolutePath());
            item.put("directory", child.isDirectory());
            item.put("fileCount", audioFiles.size());
            item.put("modifiedAt", child.lastModified());
            item.put("running", isRunning(child));
            if (type == SourceType.FAILED) {
                // 已被 LLM 自动归档过的复核副本：告诉用户音乐库里已经有一份，恢复成功后会替换它
                autoArchiveRegistry.find(child.getName())
                    .map(AutoArchiveRegistry.Link::getArchivedPath)
                    .filter(p -> new File(p).exists())
                    .ifPresent(p -> item.put("autoArchivedPath", displayOutputPath(Path.of(p))));
            }
            result.add(item);
        }
    }

    private boolean isRunning(File file) {
        try {
            String key = file.getCanonicalPath();
            return runningPaths.contains(key) || (searchQueueService != null && searchQueueService.isFolderActive(key));
        } catch (IOException e) {
            return false;
        }
    }

    private File resolveItem(SourceType type, String relativePath) throws IOException {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            throw new IOException("recovery.path.required");
        }
        String rootValue = type == SourceType.PARTIAL ? config.getPartialDirectory() : config.getFailedDirectory();
        if (rootValue == null || rootValue.trim().isEmpty()) {
            throw new IOException("recovery.directory.not.configured");
        }
        File root = new File(rootValue.trim()).getCanonicalFile();
        File target = new File(root, relativePath).getCanonicalFile();
        String rootPath = root.getPath();
        String targetPath = target.getPath();
        if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
            throw new IOException("recovery.path.invalid");
        }
        return target;
    }

    private List<File> collectAudioFiles(File target) {
        List<File> files = new ArrayList<>();
        if (target == null || !target.exists()) {
            return files;
        }
        if (target.isFile()) {
            if (fileSystemUtils.isMusicFile(target)) {
                files.add(target);
            }
        } else {
            fileSystemUtils.collectAudioFilesForMarking(target, files);
            files.sort(Comparator.comparing(File::getAbsolutePath));
        }
        return files;
    }

    /**
     * 跨挂载点的移动兜底。
     *
     * 典型 Docker 部署里隔离目录（容器 overlay 层或独立 bind mount）与
     * 回收站（./data 卷）分属不同文件系统，Files.move 会直接抛
     * "Invalid cross-device link"。复制后校验再删源，避免半途失败丢数据。
     */
    private void moveAcrossDevices(Path source, Path target) throws IOException {
        try (var libraryWrite = LibraryWriteGuard.acquire()) {
            try {
                Files.move(source, target);
                return;
            } catch (IOException crossDevice) {
                log.debug("跨设备移动，改为复制后删除: {} -> {}", source, target);
            }
            if (Files.isDirectory(source)) {
                fileSystemUtils.copyDirectoryRecursively(source, target);
                if (listFiles(target).size() != listFiles(source).size()) {
                    deleteRecursively(target);
                    throw new IOException("recovery.move.copy.incomplete");
                }
            } else {
                Files.createDirectories(target.getParent());
                Files.copy(source, target);
                if (Files.size(target) != Files.size(source)) {
                    Files.deleteIfExists(target);
                    throw new IOException("recovery.move.copy.incomplete");
                }
            }
            deleteRecursively(source);

        }
    }

    private Path trashRoot() {
        String configured = config.getRecoveryTrashDirectory();
        return Path.of(configured == null || configured.isBlank() ? "data/recovery-trash" : configured);
    }

    /**
     * 恢复成功后同时收敛 PARTIAL/FAILED 两个隔离视图。
     *
     * 新产生的数据已经保证两者互斥；这里继续处理另一侧的同名历史副本，
     * 兼容升级前已经存在的重复记录，避免“部分识别已成功，但失败页仍残留”的歧义。
     * 两份内容可能并不相同（部分识别副本可能经过转码/补标签），因此都遵守回收站策略，
     * 不能把关联副本直接永久删除。
     */
    private void retireRecoveryCopies(File source, RecoveryJob job) throws IOException {
        moveToTrash(source, job);
        cleanupAssociatedRecoveryCopy(job);
    }

    private void cleanupAssociatedRecoveryCopy(RecoveryJob job) {
        SourceType siblingType = job.sourceType == SourceType.PARTIAL ? SourceType.FAILED : SourceType.PARTIAL;
        String siblingRoot = siblingType == SourceType.PARTIAL
            ? config.getPartialDirectory() : config.getFailedDirectory();
        if (siblingRoot == null || siblingRoot.isBlank()) return;

        try {
            File sibling = resolveItem(siblingType, job.relativePath);
            if (sibling.exists()) {
                moveToTrash(sibling, job.id, siblingType, job.relativePath);
                log.info("关联的历史隔离副本已移入回收站: {}", sibling.getAbsolutePath());
            }
        } catch (IOException e) {
            // 输出已经成功提交时，清理历史重复副本失败不应把整个恢复任务反转成失败。
            log.warn("处理关联隔离副本失败，将保留给用户手动处理: {} / {} - {}",
                siblingType, job.relativePath, e.getMessage());
        }
    }

    private void moveToTrash(File source, RecoveryJob job) throws IOException {
        moveToTrash(source, job.id, job.sourceType, job.relativePath);
    }

    private void moveToTrash(File source, String jobId, SourceType sourceType,
                             String relativePath) throws IOException {
        int retention = config.getRecoveryTrashRetentionDays();
        if (retention == 0) {
            deleteRecursively(source.toPath());
            return;
        }
        Path root = trashRoot();
        Files.createDirectories(root);
        String safeName = source.getName().replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        long trashedAt = System.currentTimeMillis();
        // PARTIAL/FAILED 历史副本可能同名且在同一毫秒入站，类型标签用于避免目标路径碰撞。
        Path target = root.resolve(trashedAt + "_" + jobId + "_" + sourceType + "_" + safeName);
        moveAcrossDevices(source.toPath(), target);

        JsonObject manifest = new JsonObject();
        manifest.addProperty("sourceType", String.valueOf(sourceType));
        manifest.addProperty("relativePath", relativePath);
        manifest.addProperty("trashedAt", trashedAt);
        Files.writeString(target.resolveSibling(target.getFileName() + ".json"), gson.toJson(manifest));
    }

    /**
     * 把音乐库里的旧归档移入回收站。manifest 记录原绝对路径，还原时搬回音乐库而不是隔离目录。
     * 与隔离副本不同，这里忽略「保留 0 天即直接删除」：提交失败时还要靠它回滚。
     */
    private Path moveOutputToTrash(Path original, String jobId) throws IOException {
        Path root = trashRoot();
        Files.createDirectories(root);
        String safeName = original.getFileName().toString().replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        long trashedAt = System.currentTimeMillis();
        Path target = root.resolve(trashedAt + "_" + jobId + "_" + TRASH_KIND_OUTPUT + "_" + safeName);
        moveAcrossDevices(original, target);

        JsonObject manifest = new JsonObject();
        manifest.addProperty("kind", TRASH_KIND_OUTPUT);
        manifest.addProperty("originalPath", original.toAbsolutePath().toString());
        manifest.addProperty("relativePath", displayOutputPath(original));
        manifest.addProperty("trashedAt", trashedAt);
        Files.writeString(target.resolveSibling(target.getFileName() + ".json"), gson.toJson(manifest));
        return target;
    }

    private static final String TRASH_KIND_OUTPUT = "OUTPUT";

    private String displayOutputPath(Path path) {
        try {
            return Path.of(config.getOutputDirectory()).toAbsolutePath().normalize()
                .relativize(path.toAbsolutePath().normalize()).toString();
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }

    private void cleanupExpiredTrash() {
        int retention = config.getRecoveryTrashRetentionDays();
        if (retention < 0) return;
        Path root = trashRoot();
        if (!Files.isDirectory(root)) return;
        long cutoff = System.currentTimeMillis() - retention * 86_400_000L;
        try (var stream = Files.list(root)) {
            for (Path path : stream.toList()) {
                // manifest 随主体一起删，不单独参与判定
                if (path.getFileName().toString().endsWith(".json")) continue;
                if (trashedAt(path) < cutoff) {
                    deleteRecursively(path);
                    Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".json"));
                }
            }
        } catch (Exception e) {
            log.warn("清理恢复回收站失败: {}", e.getMessage());
        }
    }

    /**
     * 入站时间取自 manifest。
     * 不能用 lastModifiedTime：Files.move 会保留原始 mtime，
     * 一张早年下载的专辑刚进回收站就会被判为过期。
     */
    private long trashedAt(Path entry) {
        Path manifest = entry.resolveSibling(entry.getFileName() + ".json");
        try {
            if (Files.isRegularFile(manifest)) {
                JsonObject json = gson.fromJson(Files.readString(manifest), JsonObject.class);
                if (json != null && json.has("trashedAt")) return json.get("trashedAt").getAsLong();
            }
        } catch (Exception ignored) {
            // 降级到目录名前缀
        }
        String name = entry.getFileName().toString();
        int separator = name.indexOf('_');
        if (separator > 0) {
            try {
                return Long.parseLong(name.substring(0, separator));
            } catch (NumberFormatException ignored) {
                // 说明不是本服务写入的条目
            }
        }
        return Long.MAX_VALUE; // 无法判定时保守保留
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(path)) {
            for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    @Override
    public void close() {
        if (searchQueueService != null) {
            searchQueueService.close();
        }
        executor.shutdownNow();
    }

    private Map<String, Path> collectTempRelativeTargets(Path workRoot, List<File> sourceFiles) {
        Map<String, Path> relativeBySource = new LinkedHashMap<>();
        if (processedLogger == null || workRoot == null || sourceFiles == null) {
            return relativeBySource;
        }
        Path normalizedWork = workRoot.toAbsolutePath().normalize();
        for (File source : sourceFiles) {
            String tempTarget = processedLogger.findTargetPath(source);
            if (tempTarget == null || tempTarget.isBlank()) {
                continue;
            }
            Path targetPath = Path.of(tempTarget).toAbsolutePath().normalize();
            if (targetPath.startsWith(normalizedWork)) {
                relativeBySource.put(source.getAbsolutePath(), normalizedWork.relativize(targetPath));
            }
        }
        return relativeBySource;
    }

    private void finalizeCommittedRecords(Map<String, Path> relativeBySource, List<File> sourceFiles) {
        if (processedLogger == null || relativeBySource == null || relativeBySource.isEmpty()
                || config.getOutputDirectory() == null || config.getOutputDirectory().isBlank()) {
            return;
        }
        Path outputRoot = Path.of(config.getOutputDirectory()).toAbsolutePath().normalize();
        for (File source : sourceFiles) {
            Path relative = relativeBySource.get(source.getAbsolutePath());
            if (relative == null) {
                continue;
            }
            File finalTarget = outputRoot.resolve(relative).normalize().toFile();
            if (!finalTarget.isFile()) {
                continue;
            }
            List<com.lux032.musicautotagger.model.ProcessedRecord> recs =
                processedLogger.findRecordsByPaths(List.of(source.getAbsolutePath()));
            if (recs.isEmpty()) {
                continue;
            }
            var rec = recs.get(0);
            processedLogger.removeProcessedRecord(source);
            processedLogger.markFileAsProcessed(finalTarget, rec.recordingId,
                rec.artist, rec.title, rec.album, rec.releaseGroupId, finalTarget.getAbsolutePath());
        }
    }

    private static class RecoveryJob {
        private String id;
        private SourceType sourceType;
        private String relativePath;
        private String path;
        private boolean useLlm;
        private RecoveryMode mode;
        private boolean analyzeCover;
        private String reviewItemId;
        private JobStatus status;
        private String message;
        private int successCount;
        private int failedCount;
        private long createdAt;
        private long updatedAt;

        long getCreatedAt() { return createdAt; }

        Map<String, Object> toView() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("sourceType", sourceType.name());
            map.put("relativePath", relativePath);
            map.put("path", path);
            map.put("useLlm", useLlm);
            map.put("mode", mode == null ? null : mode.name());
            map.put("analyzeCover", analyzeCover);
            map.put("reviewItemId", reviewItemId);
            map.put("status", status.name());
            map.put("message", message);
            map.put("successCount", successCount);
            map.put("failedCount", failedCount);
            map.put("createdAt", createdAt);
            map.put("updatedAt", updatedAt);
            return map;
        }
    }
}
