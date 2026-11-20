package com.lux032.musicautotagger.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ProcessResult;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.llm.LlmArtistSplitter;
import com.lux032.musicautotagger.util.ArtistCreditSupport;
import com.lux032.musicautotagger.util.FileNameSanitizer;
import com.lux032.musicautotagger.util.FileSystemUtils;
import com.lux032.musicautotagger.util.MetadataUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
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
    public enum JobStatus { RUNNING, SUCCEEDED, WAITING_REVIEW, FAILED }
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
    private final TagWriterService tagWriter;
    private final AudioFingerprintService fingerprintService;
    private final OnlineTrackMatcher trackMatcher;
    private final CoverCandidateService coverCandidateService;
    private final AutoArchiveRegistry autoArchiveRegistry;
    private final ReidentifyReplacementRegistry replacementRegistry;
    private final ArchiveDuplicateDetector duplicateDetector;
    private final AlbumArtistPolicy albumArtistPolicy;
    private final LlmArtistSplitter artistSplitter;
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
        this.onlineIdentificationService = onlineIdentificationOverride != null
            ? onlineIdentificationOverride
            : new OnlineIdentificationService(config, reviewQueue, tagWriter, fingerprintService, artistSplitter);
        cleanupExpiredTrash();
        cleanupStaleWorkspaces();
        resumeCommittedRecoveries();
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
        if (item.getRecoverySourceType() == null) {
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
            "人工确认联网候选: " + (item.getResolvedAlbumTitle() == null ? "" : item.getResolvedAlbumTitle()));
    }

    public List<Map<String, Object>> listItems() {
        List<Map<String, Object>> result = new ArrayList<>();
        collectRoot(result, SourceType.PARTIAL, config.getPartialDirectory());
        collectRoot(result, SourceType.FAILED, config.getFailedDirectory());
        result.sort(Comparator.comparingLong(m -> -((Number) m.get("modifiedAt")).longValue()));
        return result;
    }

    public List<Map<String, Object>> listJobs() {
        List<RecoveryJob> snapshots = new ArrayList<>(jobs.values());
        snapshots.sort(Comparator.comparingLong(RecoveryJob::getCreatedAt).reversed());
        List<Map<String, Object>> result = new ArrayList<>();
        for (RecoveryJob job : snapshots) {
            result.add(job.toView());
        }
        return result;
    }

    public boolean isOnlineSearchAvailable() {
        return onlineIdentificationService.isAvailable();
    }

    /**
     * 对源文件已移除（或不在监控目录）的记录，基于暂存副本执行普通重新识别。
     * 新文件原子写入后才删除旧归档；识别失败则保留原归档并写入失败记录与日志。
     */
    public java.util.concurrent.Future<?> submitStagedReidentify(
            File stagedDir, String displayName, List<File> stagedFiles,
            List<File> fallbackRecordFiles, List<Path> oldTargetPaths) {
        String label = meaningful(displayName) ? displayName : stagedDir.getName();
        Path stagedCleanupRoot = (stagedDir.getParentFile() != null
            && stagedDir.getParentFile().getName().startsWith("reprocess-"))
            ? stagedDir.getParentFile().toPath()
            : stagedDir.toPath();
        LogCollector.addLog("INFO", "手动重新识别（普通识别）：已提交 " + label + "（" + stagedFiles.size() + " 个文件）");
        return executor.submit(() -> {
            String taskId = UUID.randomUUID().toString();
            File reidentifyWorkRoot = resolveWorkRoot().resolve("reidentify-" + taskId).toFile();
            try {
                if (reidentifyWorkRoot.exists()) deleteRecursively(reidentifyWorkRoot.toPath());
                Files.createDirectories(reidentifyWorkRoot.toPath());
                int succeeded = 0;
                int failed = 0;
                try (TagWriterService.OutputScope scope = tagWriter.recoveryWorkspace(reidentifyWorkRoot)) {
                    for (File audioFile : stagedFiles) {
                        ProcessResult result = processor.processRecoveryFile(audioFile, stagedDir);
                        if (result == ProcessResult.SUCCESS) {
                            succeeded++;
                        } else {
                            failed++;
                            // 专辑识别失败时现有流程已将整目录标记并复制到失败/部分识别目录
                            break;
                        }
                    }
                }

                if (reviewQueue != null && reviewQueue.isFolderUnderReview(stagedDir.getAbsolutePath())) {
                    deleteRecursively(reidentifyWorkRoot.toPath());
                    ReviewItem queued = reviewQueue.findPendingByFolder(stagedDir.getAbsolutePath());
                    if (queued != null) {
                        reviewQueue.updatePending(queued.getId(), target -> {
                            if (meaningful(displayName)) target.setFolderName(displayName);
                            target.setStagingDir(stagedDir.getAbsolutePath());
                            return true;
                        });
                    }
                    LogCollector.addLog("WARN", "重新识别转入待人工确认: " + label);
                    return;
                }

                if (failed == 0 && succeeded == stagedFiles.size()) {
                    commitReplacingDuplicates(reidentifyWorkRoot.toPath(), stagedFiles.size(), taskId,
                        null, DuplicatePolicy.ASK, oldTargetPaths);
                    clearReplacementEntries(stagedFiles);
                    // 处理记录中不应保留很快会被删除的暂存路径：更新为实际有效归档路径
                    for (File staged : stagedFiles) {
                        String finalTarget = processedLogger.findTargetPath(staged);
                        if (finalTarget != null && Files.isRegularFile(Path.of(finalTarget))) {
                            List<com.lux032.musicautotagger.model.ProcessedRecord> recs =
                                processedLogger.findRecordsByPaths(List.of(staged.getAbsolutePath()));
                            if (!recs.isEmpty()) {
                                var rec = recs.get(0);
                                processedLogger.removeProcessedRecord(staged);
                                processedLogger.markFileAsProcessed(new File(finalTarget), rec.recordingId,
                                    rec.artist, rec.title, rec.album, rec.releaseGroupId, finalTarget);
                            }
                        }
                    }
                    deleteRecursively(stagedCleanupRoot);
                    LogCollector.addLog("SUCCESS", "手动重新识别成功: " + label);
                } else {
                    deleteRecursively(reidentifyWorkRoot.toPath());
                    clearReplacementEntries(stagedFiles);
                    // 识别失败时保留原归档文件不被误删，并确保处理历史有明确 FAILED 记录供用户跟踪
                    for (int i = 0; i < stagedFiles.size(); i++) {
                        File staged = stagedFiles.get(i);
                        processedLogger.removeProcessedRecord(staged);
                        File fallback = fallbackRecordFiles != null && i < fallbackRecordFiles.size()
                            ? fallbackRecordFiles.get(i) : staged;
                        if (fallback != null && fallback.exists()) {
                            processedLogger.markFileAsProcessed(fallback, "FAILED",
                                "普通识别失败（已转入失败记录）", fallback.getName(), label);
                        }
                    }
                    deleteRecursively(stagedCleanupRoot);
                    LogCollector.addLog("ERROR", "手动重新识别失败，已转入失败记录（成功 " + succeeded + "，失败 " + failed + "）: " + label);
                }
            } catch (Exception e) {
                log.error("基于已归档文件的重新识别失败: {}", label, e);
                LogCollector.addLog("ERROR", "手动重新识别异常 (" + label + "): " + e.getMessage());
                try {
                    deleteRecursively(reidentifyWorkRoot.toPath());
                    deleteRecursively(stagedCleanupRoot);
                } catch (IOException cleanupErr) {
                    log.debug("清理临时工作目录失败: {}", cleanupErr.getMessage());
                }
            }
        });
    }

    /** 忽略部分识别 / 识别失败列表中的项目：移入回收站（保留天数为 0 时按策略直接删除），不影响音乐库已归档文件。 */
    public void dismiss(SourceType type, String relativePath) throws IOException {
        File target = resolveItem(type, relativePath);
        if (!target.exists()) {
            throw new IOException("recovery.item.not.found");
        }
        String key = target.getCanonicalPath();
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
                && (finalAlbumArtists.size() > 1 || ArtistCreditSupport.looksComposite(finalArtist))) {
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
        if (meaningful(selectedCoverSha256)) {
            selectedCover = coverCandidateService.resolveBySha256(
                selectedCoverSha256, chosen.getCoverCandidates())
                .orElseThrow(() -> new IOException("cover.selection.expired"));
            cover = selectedCover.data();
            chosen.setSelectedCoverSha256(selectedCoverSha256);
        } else {
            chosen.setSelectedCoverSha256(null);
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
            committed = commitReplacingDuplicates(workRoot.toPath(), files.size(), item.getId(),
                linkKey, duplicatePolicy, replacedTargetPaths);
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
            for (Path oldFile : replacedFiles) {
                if (!committedFiles.contains(oldFile)) {
                    ReidentifyReplacementRegistry.deleteReplacedFileAndEmptyParents(
                        oldFile, null, outputRoot, config.isExportLyricsToFile());
                }
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
        if (explicit.size() > 1 || (explicit.size() == 1 && !ArtistCreditSupport.looksComposite(explicit.get(0)))) {
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

    private List<Path> collectReplacedTargetPaths(List<File> sourceFiles) {
        if (sourceFiles == null || sourceFiles.isEmpty()) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>();
        for (File source : sourceFiles) {
            String fileHash = processedLogger != null ? processedLogger.computeFileHashOrNull(source) : null;
            if (replacementRegistry != null) {
                replacementRegistry.findOldTarget(source, fileHash).ifPresent(paths::add);
            }
            if (processedLogger != null) {
                String existing = processedLogger.findTargetPath(source);
                if (existing != null && !existing.isBlank()) {
                    paths.add(Path.of(existing));
                }
            }
        }
        return paths;
    }

    private void clearReplacementEntries(List<File> sourceFiles) {
        if (replacementRegistry == null || sourceFiles == null) {
            return;
        }
        for (File source : sourceFiles) {
            String fileHash = processedLogger != null ? processedLogger.computeFileHashOrNull(source) : null;
            replacementRegistry.remove(source, fileHash);
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

    public String submit(SourceType type, String relativePath, RecoveryMode mode, boolean analyzeCover) throws IOException {
        File target = resolveItem(type, relativePath);
        if (!target.exists()) {
            throw new IOException("recovery.item.not.found");
        }
        if (mode == RecoveryMode.LOCAL_ARTIST_MATCH && type != SourceType.PARTIAL) {
            throw new IOException("recovery.llm.partial.only");
        }
        if (mode == RecoveryMode.ONLINE_SEARCH && !onlineIdentificationService.isAvailable()) {
            throw new IOException("llm.web.search.unavailable");
        }

        String key = target.getCanonicalPath();
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
        job.status = JobStatus.RUNNING;
        job.createdAt = System.currentTimeMillis();
        job.updatedAt = job.createdAt;
        jobs.put(job.id, job);

        executor.submit(() -> runJob(job, target, key));
        return job.id;
    }

    private void runJob(RecoveryJob job, File target, String runningKey) {
        File processingTarget = target;
        File singleFileWorkspace = null;
        try {
            if (job.mode == RecoveryMode.ONLINE_SEARCH) {
                File albumRoot = target;
                onlineIdentificationService.search(albumRoot, job.sourceType.name(), job.analyzeCover);
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
                // 待确认阶段不应留下任何正式输出；候选和文件引用已由队列持久化。
                deleteRecursively(reidentifyWorkRoot.toPath());
                if (singleFileWorkspace != null) {
                    Files.deleteIfExists(target.toPath());
                }
                complete(job, JobStatus.WAITING_REVIEW, "重新识别后进入待人工确认队列，文件已转交待确认页");
            } else if (failed == 0 && succeeded == audioFiles.size()) {
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
            return runningPaths.contains(file.getCanonicalPath());
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
        executor.shutdownNow();
    }

    private static class RecoveryJob {
        private String id;
        private SourceType sourceType;
        private String relativePath;
        private String path;
        private boolean useLlm;
        private RecoveryMode mode;
        private boolean analyzeCover;
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
