package com.lux032.musicautotagger.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ProcessResult;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.util.FileNameSanitizer;
import com.lux032.musicautotagger.util.FileSystemUtils;
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

    private final MusicConfig config;
    private final AudioFileProcessorService processor;
    private final ProcessedFileLogger processedLogger;
    private final ReviewQueueService reviewQueue;
    private final FailedFileHandler failedFileHandler;
    private final FileSystemUtils fileSystemUtils;
    private final OnlineIdentificationService onlineIdentificationService;
    private final TagWriterService tagWriter;
    private final AudioFingerprintService fingerprintService;
    private final OnlineTrackMatcher trackMatcher;
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
                           FailedFileHandler failedFileHandler,
                           FileSystemUtils fileSystemUtils,
                           TagWriterService tagWriter,
                           AudioFingerprintService fingerprintService) {
        this.config = config;
        this.processor = processor;
        this.processedLogger = processedLogger;
        this.reviewQueue = reviewQueue;
        this.failedFileHandler = failedFileHandler;
        this.fileSystemUtils = fileSystemUtils;
        this.tagWriter = tagWriter;
        this.fingerprintService = fingerprintService;
        this.trackMatcher = new OnlineTrackMatcher(tagWriter);
        this.onlineIdentificationService = new OnlineIdentificationService(
            config, reviewQueue, tagWriter, fingerprintService);
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

    /** 用户确认联网候选后，在工作目录完成整张处理，再整体提交。 */
    public synchronized ReviewItem confirmOnlineCandidate(String itemId, String candidateId,
                                                           String albumTitle, String albumArtist,
                                                           String releaseDate, String edition,
                                                           String archiveDirectoryName) throws IOException {
        ReviewItem item = reviewQueue.get(itemId);
        if (item == null) throw new IOException("item.not.found");
        if (item.getStatus() != ReviewItem.Status.PENDING_REVIEW) throw new IOException("item.already.resolved");

        // 上一次输出已提交但收尾中断（回收站无权限/磁盘满/挂载断开）。
        // 必须接着收尾，而不是重新写一遍输出（那只会撞 destination.exists 永远卡住）。
        if (item.getRecoveryCommitState() == ReviewItem.RecoveryCommitState.COMMITTED) {
            finishCommittedRecovery(item);
            return item;
        }
        if (item.getRecoverySourceType() == null) throw new IOException("recovery.not.owned.by.recovery.flow");
        if (item.isOnlineEvidenceStale()) throw new IOException("online.evidence.stale");
        ReviewItem.OnlineCandidate chosen = item.getOnlineCandidates().stream()
            .filter(c -> candidateId != null && candidateId.equals(c.getId())).findFirst()
            .orElseThrow(() -> new IOException("online.candidate.not.found"));

        String finalAlbum = meaningful(albumTitle) ? albumTitle.trim() : chosen.getTitle();
        String finalArtist = meaningful(albumArtist) ? albumArtist.trim()
            : (meaningful(chosen.getAlbumArtist()) ? chosen.getAlbumArtist() : chosen.getArtist());
        String finalDate = releaseDate != null ? releaseDate.trim() : chosen.getReleaseDate();
        if (!meaningful(finalAlbum) || !meaningful(finalArtist)) throw new IOException("online.metadata.incomplete");

        File source = new File(item.getRecoverySourcePath() != null ? item.getRecoverySourcePath() : item.getFolderPath());
        List<File> files = collectAudioFiles(source);
        if (files.isEmpty()) throw new IOException("recovery.no.audio.files");

        // 证据新鲜度：搜索之后文件若被改动，旧候选一律作废，避免按陈旧结论归档。
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

        // 重算逐曲匹配：不依赖下标，且覆盖率按「可靠匹配数 / 本地文件数」计算，
        // 而不是只看联网候选列了多少行曲目。
        List<Integer> localDurations;
        try {
            localDurations = fingerprintService.extractDurationSequence(files);
        } catch (Exception e) {
            log.debug("无法提取时长序列，逐曲匹配将不使用时长证据: {}", e.getMessage());
            localDurations = null;
        }
        int confidentMatches = trackMatcher.match(chosen, files, localDurations);
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

        File workRoot = resolveWorkRoot().resolve("online-" + item.getId()).toFile();
        if (workRoot.exists()) deleteRecursively(workRoot.toPath());
        Files.createDirectories(workRoot.toPath());
        List<Path> committed;
        try {
            for (File file : files) {
                MusicMetadata original = tagWriter.readTags(file);
                MusicMetadata md = original == null ? new MusicMetadata() : original;
                md.setAlbum(finalAlbum);
                md.setAlbumArtist(finalArtist);
                md.setReleaseDate(finalDate);
                ReviewItem.OnlineTrack track = trackByFile.get(file.getAbsolutePath());
                if (track != null) {
                    if (meaningful(track.getTitle())) md.setTitle(track.getTitle());
                    if (meaningful(track.getArtist())) md.setArtist(track.getArtist());
                    if (track.getDiscNo() > 0) md.setDiscNo(String.valueOf(track.getDiscNo()));
                    if (track.getTrackNo() > 0) md.setTrackNo(String.valueOf(track.getTrackNo()));
                }
                if (!tagWriter.processFileToRoot(file, md, null, workRoot)) {
                    throw new IOException("online.atomic.write.failed");
                }
            }

            // 归档目录名可能带版本号，与 TagWriter 依据专辑名生成的目录不同。
            // 先在工作区内改名，提交阶段就能统一按相对路径镜像，不必特判某一层目录。
            Path built = workRoot.toPath().resolve(artistDir).resolve(FileNameSanitizer.sanitize(finalAlbum));
            if (Files.isDirectory(built) && !albumDir.equals(built.getFileName().toString())) {
                Files.move(built, built.resolveSibling(albumDir));
            }
            committed = commitWorkspace(workRoot.toPath(), files.size(), item.getId());
        } catch (IOException e) {
            deleteRecursively(workRoot.toPath());
            throw e;
        }

        // 输出已落地，立即落盘提交阶段；之后任一步失败都能在启动时接着收尾，而不会重写输出。
        item.setVerificationSource(ReviewItem.VerificationSource.ONLINE_SEARCH);
        item.setResolvedAlbumTitle(finalAlbum);
        item.setResolvedAlbumArtist(finalArtist);
        item.setResolvedReleaseDate(finalDate);
        item.setRecoveryCommitState(ReviewItem.RecoveryCommitState.COMMITTED);
        item.setCommittedOutputPaths(committed.stream().map(Path::toString).toList());
        reviewQueue.update(item);

        for (File file : files) {
            processedLogger.markFileAsProcessed(file, "ONLINE_SEARCH", finalArtist,
                file.getName(), finalAlbum);
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
            if (Files.exists(outputRoot.resolve(unit))) throw new IOException("online.destination.exists");
        }

        List<Path> committed = new ArrayList<>();
        try {
            for (Path unit : units) {
                Path destination = outputRoot.resolve(unit);
                Files.createDirectories(destination.getParent());
                commitUnit(workspace.resolve(unit), destination, taskId);
                committed.add(destination);
            }
        } catch (IOException failure) {
            rollbackCommitted(committed, workspace, outputRoot, failure);
            throw failure;
        }
        deleteRecursively(workspace);
        return committed;
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
        for (Path destination : committed) {
            try {
                Path back = workspace.resolve(outputRoot.relativize(destination));
                Files.createDirectories(back.getParent());
                Files.move(destination, back);
            } catch (IOException e) {
                log.error("提交失败后回滚受阻，输出目录可能残留部分内容，需人工清理: {}（原因: {}）",
                    destination, cause.getMessage(), e);
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
                    moveToTrash(target, job);
                    complete(job, JobStatus.SUCCEEDED, "本地艺术家目录匹配成功，已移动到输出目录");
                } else {
                    complete(job, JobStatus.FAILED, "LLM 未找到可靠的现有艺术家目录，文件保持不变");
                }
                return;
            }

            // 隔离目录根部的单文件不能直接交给专辑处理器：它不在 monitorDirectory 下，
            // FileSystemUtils 会把整个 partial/failed 根目录误当成同一张专辑。
            // 为它创建独立工作目录，确保文件夹级识别只看到这一首。
            if (target.isFile()) {
                singleFileWorkspace = new File(target.getParentFile(), ".recovery-" + job.id);
                Files.createDirectories(singleFileWorkspace.toPath());
                processingTarget = new File(singleFileWorkspace, target.getName());
                Files.copy(target.toPath(), processingTarget.toPath());
            }

            List<File> audioFiles = collectAudioFiles(processingTarget);
            if (audioFiles.isEmpty()) {
                complete(job, JobStatus.FAILED, "没有找到支持的音频文件");
                return;
            }

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
            tagWriter.setThreadOutputRoot(reidentifyWorkRoot);
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
            tagWriter.clearThreadOutputRoot();
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
                    commitWorkspace(reidentifyWorkRoot.toPath(), audioFiles.size(), job.id);
                } catch (IOException commitError) {
                    deleteRecursively(reidentifyWorkRoot.toPath());
                    throw commitError;
                }
                moveToTrash(target, job);
                if (singleFileWorkspace != null) {
                    deleteRecursively(singleFileWorkspace.toPath());
                }
                complete(job, JobStatus.SUCCEEDED, "重新识别成功，整张专辑已原子提交，隔离副本已移入回收站");
            } else {
                deleteRecursively(reidentifyWorkRoot.toPath());
                if (singleFileWorkspace != null) {
                    deleteRecursively(singleFileWorkspace.toPath());
                }
                complete(job, JobStatus.FAILED,
                    "重新识别仍未全部成功（成功 " + succeeded + "，失败 " + failed + "），文件已保留");
            }
        } catch (Exception e) {
            tagWriter.clearThreadOutputRoot();
            log.error("人工恢复任务失败: {}", target, e);
            if (singleFileWorkspace != null && (reviewQueue == null
                || !reviewQueue.isFolderUnderReview(singleFileWorkspace.getAbsolutePath()))) {
                try {
                    deleteRecursively(singleFileWorkspace.toPath());
                } catch (IOException cleanupError) {
                    log.debug("清理恢复工作目录失败: {}", cleanupError.getMessage());
                }
            }
            complete(job, JobStatus.FAILED, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
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

    private Path trashRoot() {
        String configured = config.getRecoveryTrashDirectory();
        return Path.of(configured == null || configured.isBlank() ? "data/recovery-trash" : configured);
    }

    private void moveToTrash(File source, RecoveryJob job) throws IOException {
        int retention = config.getRecoveryTrashRetentionDays();
        if (retention == 0) {
            deleteRecursively(source.toPath());
            return;
        }
        Path root = trashRoot();
        Files.createDirectories(root);
        String safeName = source.getName().replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        long trashedAt = System.currentTimeMillis();
        Path target = root.resolve(trashedAt + "_" + job.id + "_" + safeName);
        Files.move(source.toPath(), target);

        JsonObject manifest = new JsonObject();
        manifest.addProperty("sourceType", String.valueOf(job.sourceType));
        manifest.addProperty("relativePath", job.relativePath);
        manifest.addProperty("trashedAt", trashedAt);
        Files.writeString(target.resolveSibling(target.getFileName() + ".json"), gson.toJson(manifest));
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
