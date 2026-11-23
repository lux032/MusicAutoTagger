package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.util.AudioFileOrdering;
import com.lux032.musicautotagger.util.FileSystemUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「缺少封面」处置与手动音乐库扫描服务。
 *
 * 职责：
 *   1. 对未入库拦截（STAGED_IMPORT）的专辑/单曲：选定封面或确认无封面后才真正写入音乐库；
 *   2. 对手动扫描音乐库发现（LIBRARY_EXISTING）的已归档缺封面专辑：原地原子补写内嵌封面与缓存；
 *   3. 接收用户本地上传图片与 URL 拉取（全部走 ImageCompressor 统一压缩规则）；
 *   4. 手动触发扫描输出音乐库（绝不自动运行）。
 */
@Slf4j
public class CoverMissingService implements AutoCloseable {

    private final MusicConfig config;
    private final ReviewQueueService reviewQueue;
    private final FolderAlbumCache folderAlbumCache;
    private final AlbumBatchProcessor albumBatchProcessor;
    private final TagWriterService tagWriter;
    private final CoverCandidateService coverCandidateService;
    private final ProcessedFileLogger processedLogger;
    private RecoveryService recoveryService;
    private CoverSearchService coverSearchService;
    private final Map<String, Object> itemLocks = new ConcurrentHashMap<>();

    private Object itemLock(String id) {
        return itemLocks.computeIfAbsent(id == null ? "" : id, k -> new Object());
    }

    // 手动音乐库缺封面扫描器（严格仅由用户手动触发）
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cover-missing-scan");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean scanRunning = new AtomicBoolean(false);
    private final AtomicBoolean scanCancelled = new AtomicBoolean(false);
    private volatile int scannedAlbums = 0;
    private volatile int scannedFiles = 0;
    private volatile int foundMissing = 0;
    private volatile int skippedAcknowledged = 0;
    private volatile String currentAlbum = null;
    private volatile String scanMessage = null;
    private volatile long scanStartedAt = 0;
    private volatile long scanFinishedAt = 0;

    public CoverMissingService(MusicConfig config,
                               ReviewQueueService reviewQueue,
                               FolderAlbumCache folderAlbumCache,
                               AlbumBatchProcessor albumBatchProcessor,
                               TagWriterService tagWriter,
                               CoverCandidateService coverCandidateService,
                               ProcessedFileLogger processedLogger) {
        this.config = config;
        this.reviewQueue = reviewQueue;
        this.folderAlbumCache = folderAlbumCache;
        this.albumBatchProcessor = albumBatchProcessor;
        this.tagWriter = tagWriter;
        this.coverCandidateService = coverCandidateService;
        this.processedLogger = processedLogger;
    }

    public void setRecoveryService(RecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    public void setCoverSearchService(CoverSearchService coverSearchService) {
        this.coverSearchService = coverSearchService;
    }

    public boolean isAiSearchAvailable() {
        return coverSearchService != null && coverSearchService.isAiSearchAvailable();
    }

    // ==================== 候选补充（上传 / URL） ====================

    public CoverCandidateView uploadCover(String id, byte[] rawBytes, String fileName)
            throws ReviewResolutionService.ResolutionException {
        requirePendingMissingCover(id);
        if (rawBytes == null || rawBytes.length == 0) {
            throw new ReviewResolutionService.ResolutionException(400, "cover.upload.empty");
        }
        if (rawBytes.length > ImageCompressor.MAX_INPUT_BYTES) {
            throw new ReviewResolutionService.ResolutionException(413, "cover.upload.too.large");
        }
        CoverCandidateView view = coverCandidateService.ingestUploadedBytes(rawBytes, fileName)
            .orElseThrow(() -> new ReviewResolutionService.ResolutionException(400, "cover.upload.invalid"));
        synchronized (itemLock(id)) {
            ReviewItem item = requirePendingMissingCover(id);
            prependCandidate(item, view);
        }
        return view;
    }

    public CoverCandidateView fetchCoverByUrl(String id, String url)
            throws ReviewResolutionService.ResolutionException {
        requirePendingMissingCover(id);
        if (!meaningful(url)) {
            throw new ReviewResolutionService.ResolutionException(400, "cover.url.required");
        }
        CoverCandidateView view = coverCandidateService.ingestFromUrl(
                url.trim(), CoverCandidateView.Origin.USER_URL, null, null)
            .orElseThrow(() -> new ReviewResolutionService.ResolutionException(400, "cover.url.fetch.failed"));
        synchronized (itemLock(id)) {
            ReviewItem item = requirePendingMissingCover(id);
            prependCandidate(item, view);
        }
        return view;
    }

    private void prependCandidate(ReviewItem item, CoverCandidateView view) {
        reviewQueue.updatePending(item.getId(), target -> {
            ReviewItem.MissingCover mc = target.getMissingCover();
            if (mc == null) return false;
            List<CoverCandidateView> list = new ArrayList<>();
            list.add(view);
            if (mc.getCoverCandidates() != null) {
                list.addAll(mc.getCoverCandidates());
            }
            mc.setCoverCandidates(CoverCandidateService.deduplicateCandidates(list));
            mc.setSelectedCoverSha256(view.getSha256());
            return true;
        });
    }

    // ==================== 处置：应用封面 / 确认无封面 / 忽略 ====================

    public ReviewItem applyCover(String id, String selectedCoverSha256,
                                 RecoveryService.DuplicatePolicy duplicatePolicy)
            throws ReviewResolutionService.ResolutionException, IOException {
        synchronized (itemLock(id)) {
            ReviewItem item = requirePendingMissingCover(id);
            if (!meaningful(selectedCoverSha256)) {
                throw new ReviewResolutionService.ResolutionException(400, "cover.selection.required");
            }
            ReviewItem.MissingCover mc = item.getMissingCover();
            CoverCandidateService.ResolvedCover resolved = coverCandidateService.resolveBySha256(
                    selectedCoverSha256.trim(), mc.getCoverCandidates())
                .orElseThrow(() -> new ReviewResolutionService.ResolutionException(410, "cover.selection.expired"));
            return resolveItem(item, resolved.data(), selectedCoverSha256.trim(), false, duplicatePolicy);
        }
    }

    public ReviewItem confirmNoCover(String id, RecoveryService.DuplicatePolicy duplicatePolicy)
            throws ReviewResolutionService.ResolutionException, IOException {
        synchronized (itemLock(id)) {
            ReviewItem item = requirePendingMissingCover(id);
            return resolveItem(item, null, null, true, duplicatePolicy);
        }
    }

    public ReviewItem reject(String id, String note)
            throws ReviewResolutionService.ResolutionException {
        synchronized (itemLock(id)) {
            ReviewItem item = requirePendingMissingCover(id);
            ReviewItem.MissingCover mc = item.getMissingCover();
            boolean isLibrary = mc.getMode() == ReviewItem.MissingCover.Mode.LIBRARY_EXISTING;

            if (!isLibrary && item.getFiles() != null) {
                for (ReviewItem.FileEntry entry : item.getFiles()) {
                    if (entry == null || !meaningful(entry.getOriginalPath())) continue;
                    try {
                        processedLogger.markFileAsProcessed(
                            new File(entry.getOriginalPath()),
                            "REVIEW_REJECTED",
                            entry.getMetadata() != null && meaningful(entry.getMetadata().getArtist())
                                ? entry.getMetadata().getArtist() : "Unknown Artist",
                            entry.getMetadata() != null && meaningful(entry.getMetadata().getTitle())
                                ? entry.getMetadata().getTitle() : entry.getFileName(),
                            meaningful(mc.getAlbumTitle()) ? mc.getAlbumTitle() : "Unknown Album"
                        );
                    } catch (Exception e) {
                        log.warn("记录被忽略的缺封面文件失败: {} - {}", entry.getFileName(), e.getMessage());
                    }
                }
            }
            String effectiveNote = meaningful(note)
                ? note.trim() : (isLibrary ? "已从缺少封面列表移出" : "人工标记为忽略/不入库");
            reviewQueue.markResolved(item, ReviewItem.Status.REJECTED, effectiveNote);
            return item;
        }
    }

    public ReviewItem unreject(String id) throws ReviewResolutionService.ResolutionException {
        synchronized (itemLock(id)) {
            ReviewItem item = requireMissingCoverItem(id);
            if (item.getStatus() != ReviewItem.Status.REJECTED) {
                throw new ReviewResolutionService.ResolutionException(409, "item.not.rejected");
            }
            if (reviewQueue.hasOtherPendingForFolder(item.getFolderPath(), id)) {
                throw new ReviewResolutionService.ResolutionException(409, "folder.already.pending");
            }
            boolean isLibrary = item.getMissingCover().getMode() == ReviewItem.MissingCover.Mode.LIBRARY_EXISTING;
            if (!isLibrary && item.getFiles() != null) {
                for (ReviewItem.FileEntry entry : item.getFiles()) {
                    if (entry == null || !meaningful(entry.getOriginalPath())) continue;
                    try {
                        processedLogger.removeProcessedRecord(new File(entry.getOriginalPath()));
                    } catch (Exception e) {
                        log.warn("撤销缺封面忽略记录失败: {} - {}", entry.getFileName(), e.getMessage());
                    }
                }
            }
            item.setStatus(ReviewItem.Status.PENDING_REVIEW);
            item.setResolutionNote(null);
            reviewQueue.update(item);
            return item;
        }
    }

    private ReviewItem resolveItem(ReviewItem item, byte[] coverBytes, String selectedSha,
                                   boolean confirmedNoCover, RecoveryService.DuplicatePolicy duplicatePolicy)
            throws ReviewResolutionService.ResolutionException, IOException {
        ReviewItem.MissingCover mc = item.getMissingCover();
        if (mc.getMode() == ReviewItem.MissingCover.Mode.LIBRARY_EXISTING) {
            return resolveLibraryExisting(item, mc, coverBytes, selectedSha, confirmedNoCover);
        }
        return resolveStagedImport(item, mc, coverBytes, selectedSha, confirmedNoCover, duplicatePolicy);
    }

    private ReviewItem resolveLibraryExisting(ReviewItem item, ReviewItem.MissingCover mc,
                                              byte[] coverBytes, String selectedSha, boolean confirmedNoCover)
            throws ReviewResolutionService.ResolutionException {
        if (confirmedNoCover) {
            mc.setConfirmedNoCover(true);
            mc.setSelectedCoverSha256(null);
            reviewQueue.markResolved(item, ReviewItem.Status.CONFIRMED, "人工确认保持无封面（后续扫描将跳过）");
            LogCollector.addLog("INFO", "已确认保持无封面: " + displayAlbum(item, mc));
            return item;
        }

        List<File> existingFiles = new ArrayList<>();
        if (item.getFiles() != null) {
            for (ReviewItem.FileEntry entry : item.getFiles()) {
                if (entry != null && meaningful(entry.getOriginalPath())) {
                    File f = new File(entry.getOriginalPath());
                    if (f.isFile()) existingFiles.add(f);
                }
            }
        }
        if (existingFiles.isEmpty()) {
            throw new ReviewResolutionService.ResolutionException(410, "files.missing");
        }

        int written = 0;
        List<String> failed = new ArrayList<>();
        for (File file : existingFiles) {
            if (tagWriter.embedCoverAtomically(file, coverBytes)) {
                written++;
            } else {
                failed.add(file.getName());
            }
        }
        if (!failed.isEmpty() || written == 0) {
            item.setResolutionNote("封面写入未全部成功（成功 " + written + " 个，失败: " + String.join(", ", failed) + "）");
            reviewQueue.update(item);
            throw new ReviewResolutionService.ResolutionException(500, "cover.embed.partial.failed");
        }

        if (meaningful(mc.getReleaseGroupId())) {
            coverCandidateService.cacheCoverForReleaseGroup(mc.getReleaseGroupId(), coverBytes);
        }
        mc.setSelectedCoverSha256(selectedSha);
        mc.setConfirmedNoCover(false);
        reviewQueue.markResolved(item, ReviewItem.Status.CONFIRMED,
            "已补全封面并写入音乐库（" + written + " 个文件）");
        LogCollector.addLog("SUCCESS", "已补全音乐库专辑封面: " + displayAlbum(item, mc) + "（" + written + " 个文件）");
        return item;
    }

    private ReviewItem resolveStagedImport(ReviewItem item, ReviewItem.MissingCover mc,
                                           byte[] coverBytes, String selectedSha, boolean confirmedNoCover,
                                           RecoveryService.DuplicatePolicy duplicatePolicy)
            throws ReviewResolutionService.ResolutionException, IOException {
        if (mc.isLocalArchiveDirect()) {
            if (recoveryService == null) {
                throw new ReviewResolutionService.ResolutionException(501, "recovery.unavailable");
            }
            mc.setSelectedCoverSha256(selectedSha);
            mc.setConfirmedNoCover(confirmedNoCover);
            reviewQueue.update(item);
            ReviewItem committed = recoveryService.commitDeferredLocalMissingCover(item, coverBytes, duplicatePolicy);
            committed.setResolutionNote(confirmedNoCover
                ? "人工确认保持无封面入库（本地标签）" : "已补全封面并入库（本地标签）");
            reviewQueue.update(committed);
            return committed;
        }

        if ("ONLINE_SEARCH".equals(mc.getRecordingIdSentinel()) && meaningful(mc.getOnlineCandidateId())) {
            if (recoveryService == null) {
                throw new ReviewResolutionService.ResolutionException(501, "recovery.unavailable");
            }
            mc.setSelectedCoverSha256(selectedSha);
            mc.setConfirmedNoCover(confirmedNoCover);
            reviewQueue.update(item);
            ReviewItem committed = recoveryService.commitDeferredOnlineMissingCover(item, coverBytes, duplicatePolicy);
            committed.setResolutionNote(confirmedNoCover
                ? "人工确认保持无封面入库（联网候选）" : "已补全封面并入库（联网候选）");
            reviewQueue.update(committed);
            return committed;
        }

        requireAllOriginalFiles(item);

        if (mc.isLooseFile()) {
            ReviewItem.FileEntry entry = item.getFiles().get(0);
            File original = new File(entry.getOriginalPath());
            File processing = resolveProcessingFile(entry, original);
            MusicMetadata md = entry.getMetadata() != null ? entry.getMetadata() : new MusicMetadata();
            if (coverBytes != null && coverBytes.length > 0 && meaningful(mc.getReleaseGroupId())) {
                coverCandidateService.cacheCoverForReleaseGroup(mc.getReleaseGroupId(), coverBytes);
            }
            boolean ok = albumBatchProcessor.processAndWriteFile(processing, original, md, coverBytes, false);
            if (!ok) {
                item.setResolutionNote("单曲写入失败，请检查权限或磁盘后重试");
                reviewQueue.update(item);
                throw new ReviewResolutionService.ResolutionException(500, "cover.apply.write.failed");
            }
            mc.setSelectedCoverSha256(selectedSha);
            mc.setConfirmedNoCover(confirmedNoCover);
            finalizeTemporarySourceRecords(item);
            retireRecoveryAndCleanupStaging(item);
            reviewQueue.markResolved(item, ReviewItem.Status.CONFIRMED,
                confirmedNoCover ? "人工确认保持无封面入库" : "已补全封面并入库");
            LogCollector.addLog("SUCCESS", (confirmedNoCover ? "确认无封面入库: " : "补全封面并入库: ") + displayAlbum(item, mc));
            return item;
        }

        String folderPath = item.getFolderPath();
        folderAlbumCache.clearFailureHandoff(folderPath);
        folderAlbumCache.clearFolderCoverMissing(folderPath);
        folderAlbumCache.clearPendingFiles(folderPath);

        for (ReviewItem.FileEntry entry : item.getFiles()) {
            File original = new File(entry.getOriginalPath());
            File processing = resolveProcessingFile(entry, original);
            MusicMetadata md = entry.getMetadata() != null ? entry.getMetadata() : new MusicMetadata();
            albumBatchProcessor.addPendingFile(folderPath, original, processing, null, md, null);
        }

        FolderAlbumCache.CachedAlbumInfo albumInfo = new FolderAlbumCache.CachedAlbumInfo(
            mc.getReleaseGroupId(),
            mc.getReleaseId(),
            meaningful(mc.getAlbumTitle()) ? mc.getAlbumTitle() : item.getSynthesizedAlbumTitle(),
            meaningful(mc.getAlbumArtist()) ? mc.getAlbumArtist() : item.getSynthesizedAlbumArtist(),
            item.getFiles().size(),
            mc.getReleaseDate() != null ? mc.getReleaseDate() : "",
            mc.getReleaseType(),
            mc.isCompilation(),
            1.0,
            FolderAlbumCache.CacheSource.MANUAL_CONFIRMED
        );
        if (mc.getAlbumArtists() != null && !mc.getAlbumArtists().isEmpty()) {
            albumInfo.setAlbumArtists(new ArrayList<>(mc.getAlbumArtists()));
        }

        if (!mc.isUnresolvedAlbum() && meaningful(mc.getReleaseGroupId())) {
            folderAlbumCache.clearFolderUnresolved(folderPath);
            folderAlbumCache.forceSetFolderAlbum(folderPath, albumInfo);
        }

        AlbumBatchProcessor.BatchProcessResult result =
            albumBatchProcessor.processPendingFilesWithAlbum(
                folderPath, albumInfo, mc.isUnresolvedAlbum(), coverBytes, false);

        if (result == null || !result.isFullySuccessful()) {
            String failedList = result == null ? "未知" : String.join(", ", result.getFailedFiles());
            item.setResolutionNote("上次入库未全部成功（失败: " + failedList + "），请修复后重试");
            reviewQueue.update(item);
            throw new ReviewResolutionService.ResolutionException(500, "cover.apply.write.failed");
        }

        if (coverBytes != null && coverBytes.length > 0 && meaningful(mc.getReleaseGroupId())) {
            coverCandidateService.cacheCoverForReleaseGroup(mc.getReleaseGroupId(), coverBytes);
        }
        mc.setSelectedCoverSha256(selectedSha);
        mc.setConfirmedNoCover(confirmedNoCover);
        finalizeTemporarySourceRecords(item);
        retireRecoveryAndCleanupStaging(item);
        String note = confirmedNoCover
            ? "人工确认保持无封面入库: " + displayAlbum(item, mc)
            : "已补全封面并入库: " + displayAlbum(item, mc);
        reviewQueue.markResolved(item, ReviewItem.Status.CONFIRMED, note);
        LogCollector.addLog("SUCCESS", note);
        return item;
    }

    /**
     * 来自暂存重识别（reprocess-*）或隔离恢复目录（partial/failed）的条目，
     * 源文件会在提交后清理/回收；把处理记录的 file_path 迁移到最终落盘路径，避免处理记录立即断链。
     * 注意：普通监控目录条目虽然也有转码 stagingDir（review-staging/<itemId>），但其原始文件留在监控目录内，
     * 绝不能删除其监控路径上的处理记录，否则下一轮扫描会重复刮削甚至死循环入队。
     */
    private void finalizeTemporarySourceRecords(ReviewItem item) {
        if (processedLogger == null || item == null || item.getFiles() == null) {
            return;
        }
        Path reprocessRoot = reviewQueue.findReprocessRoot(item);
        boolean temporarySource = reprocessRoot != null
            || (meaningful(item.getRecoverySourceType()) && meaningful(item.getRecoverySourcePath()));
        if (!temporarySource) {
            return;
        }
        for (ReviewItem.FileEntry entry : item.getFiles()) {
            if (entry == null || !meaningful(entry.getOriginalPath())) continue;
            File source = new File(entry.getOriginalPath());
            List<com.lux032.musicautotagger.model.ProcessedRecord> recs =
                processedLogger.findRecordsByPaths(List.of(source.getAbsolutePath()));
            if (recs == null || recs.isEmpty()) continue;
            com.lux032.musicautotagger.model.ProcessedRecord rec = recs.get(0);
            if (!meaningful(rec.targetFilePath)) continue;
            File target = new File(rec.targetFilePath);
            if (!target.isFile()) continue;
            processedLogger.removeProcessedRecord(source);
            processedLogger.markFileAsProcessed(
                target, rec.recordingId, rec.artist, rec.title, rec.album, rec.releaseGroupId,
                target.getAbsolutePath());
        }
    }

    private void retireRecoveryAndCleanupStaging(ReviewItem item) {
        if (recoveryService != null && item.getRecoverySourceType() != null && item.getRecoverySourcePath() != null) {
            try {
                recoveryService.retireRecoveryCopyIfNeeded(item);
            } catch (Exception e) {
                log.warn("回收关联隔离副本失败（输出已成功落盘）: {}", e.getMessage());
            }
        }
        // 来自「处理记录 -> 重新识别」的 reprocess-* 暂存目录，确认完成后清理
        Path reprocessRoot = reviewQueue.findReprocessRoot(item);
        if (reprocessRoot != null) {
            deleteRecursivelyQuietly(reprocessRoot);
        }
    }

    // ==================== 手动扫描输出音乐库（仅用户手动触发） ====================

    public boolean isLibraryScanRunning() {
        return scanRunning.get();
    }

    /** 手动启动输出音乐库缺封面扫描。不会自动调用。 */
    public boolean startLibraryScan() {
        if (!scanRunning.compareAndSet(false, true)) {
            return false;
        }
        scanCancelled.set(false);
        scannedAlbums = 0;
        scannedFiles = 0;
        foundMissing = 0;
        skippedAcknowledged = 0;
        currentAlbum = null;
        scanMessage = "正在扫描输出音乐库...";
        scanStartedAt = System.currentTimeMillis();
        scanFinishedAt = 0;

        scanExecutor.submit(() -> {
            try {
                runLibraryScan();
            } catch (Exception e) {
                log.error("扫描音乐库缺失封面异常终止", e);
                scanMessage = "扫描异常: " + e.getMessage();
            } finally {
                currentAlbum = null;
                scanFinishedAt = System.currentTimeMillis();
                scanRunning.set(false);
            }
        });
        return true;
    }

    public void cancelLibraryScan() {
        scanCancelled.set(true);
    }

    public Map<String, Object> getScanStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", scanRunning.get());
        m.put("scannedAlbums", scannedAlbums);
        m.put("scannedFiles", scannedFiles);
        m.put("foundMissing", foundMissing);
        m.put("skippedAcknowledged", skippedAcknowledged);
        m.put("currentAlbum", currentAlbum);
        m.put("message", scanMessage);
        m.put("startedAt", scanStartedAt);
        m.put("finishedAt", scanFinishedAt);
        return m;
    }

    private void runLibraryScan() throws IOException {
        LyricsProtectedPaths paths = LyricsProtectedPaths.capture(config);
        Path root = paths.root;
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !root.equals(root.toRealPath())) {
            throw new IOException("unsafe.output.root");
        }
        paths.validateRoot();

        Map<Path, List<File>> filesByAlbumDir = new LinkedHashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (scanCancelled.get()) return FileVisitResult.TERMINATE;
                return paths.excluded(dir, attrs) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (scanCancelled.get()) return FileVisitResult.TERMINATE;
                if (attrs.isRegularFile() && !paths.excluded(file, attrs) && isSupportedAudio(file)) {
                    Path parent = file.getParent();
                    if (parent != null) {
                        Path albumDir = parent;
                        if (parent.getFileName() != null
                            && FileSystemUtils.isDiscFolderName(parent.getFileName().toString())
                            && parent.getParent() != null
                            && !parent.getParent().equals(root)) {
                            albumDir = parent.getParent();
                        }
                        filesByAlbumDir.computeIfAbsent(albumDir, k -> new ArrayList<>()).add(file.toFile());
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        for (Map.Entry<Path, List<File>> entry : filesByAlbumDir.entrySet()) {
            if (scanCancelled.get()) {
                scanMessage = String.format("已取消：已扫描 %d 张专辑，发现 %d 张缺少封面", scannedAlbums, foundMissing);
                return;
            }
            Path albumDir = entry.getKey();
            List<File> albumFiles = entry.getValue();
            AudioFileOrdering.sort(albumFiles);
            currentAlbum = albumDir.getFileName() == null ? albumDir.toString() : albumDir.getFileName().toString();
            scannedAlbums++;

            boolean anyMissingCover = false;
            for (File f : albumFiles) {
                if (scanCancelled.get()) break;
                scannedFiles++;
                if (!tagWriter.hasEmbeddedCover(f)) {
                    anyMissingCover = true;
                }
            }
            if (!anyMissingCover) {
                continue;
            }

            String folderPath = albumDir.toAbsolutePath().normalize().toString();
            if (reviewQueue.isLibraryFolderConfirmedNoCover(folderPath)) {
                skippedAcknowledged++;
                continue;
            }
            if (reviewQueue.isFolderUnderReview(folderPath)) {
                continue;
            }

            ReviewItem.MissingCover details = buildLibraryMissingDetails(root, albumDir, albumFiles);
            List<CoverCandidateView> localCandidates =
                coverCandidateService.collectLocalCandidates(albumFiles, albumDir.toFile());
            if (!localCandidates.isEmpty()) {
                details.setCoverCandidates(new ArrayList<>(localCandidates));
            }

            ReviewItem queued = reviewQueue.enqueueLibraryMissingCover(folderPath, albumFiles, tagWriter, details);
            if (queued != null) {
                foundMissing++;
            }
        }

        scanMessage = String.format("扫描完成：共检查 %d 张专辑（%d 首），发现 %d 张缺少封面%s",
            scannedAlbums, scannedFiles, foundMissing,
            skippedAcknowledged > 0 ? "（跳过已确认无封面 " + skippedAcknowledged + " 张）" : "");
        log.info(scanMessage);
        LogCollector.addLog("INFO", scanMessage);
    }

    private ReviewItem.MissingCover buildLibraryMissingDetails(Path outputRoot, Path albumDir, List<File> albumFiles) {
        ReviewItem.MissingCover details = new ReviewItem.MissingCover();
        details.setMode(ReviewItem.MissingCover.Mode.LIBRARY_EXISTING);
        details.setSourcePipeline("LIBRARY_SCAN");

        MusicMetadata sample = null;
        for (File f : albumFiles) {
            MusicMetadata md = tagWriter.readTags(f);
            if (md != null && (meaningful(md.getAlbum()) || meaningful(md.getAlbumArtist()) || meaningful(md.getArtist()))) {
                sample = md;
                break;
            }
        }

        String folderAlbum = albumDir.getFileName() == null ? "" : albumDir.getFileName().toString();
        String folderArtist = "";
        Path artistDir = albumDir.getParent();
        if (artistDir != null && !artistDir.equals(outputRoot) && artistDir.getFileName() != null) {
            folderArtist = artistDir.getFileName().toString();
        }

        String albumTitle = (sample != null && meaningful(sample.getAlbum())) ? sample.getAlbum() : folderAlbum;
        String albumArtist = (sample != null && meaningful(sample.getAlbumArtist())) ? sample.getAlbumArtist()
            : ((sample != null && meaningful(sample.getArtist())) ? sample.getArtist() : folderArtist);
        details.setAlbumTitle(albumTitle);
        details.setAlbumArtist(albumArtist);
        if (sample != null) {
            if (meaningful(sample.getReleaseGroupId())) details.setReleaseGroupId(sample.getReleaseGroupId());
            if (meaningful(sample.getReleaseId())) details.setReleaseId(sample.getReleaseId());
            if (meaningful(sample.getReleaseDate())) details.setReleaseDate(sample.getReleaseDate());
        }
        return details;
    }

    // ==================== 内部工具 ====================

    private ReviewItem requireMissingCoverItem(String id) throws ReviewResolutionService.ResolutionException {
        ReviewItem item = reviewQueue.get(id);
        if (item == null || item.effectiveKind() != ReviewItem.Kind.COVER_MISSING || item.getMissingCover() == null) {
            throw new ReviewResolutionService.ResolutionException(404, "item.not.found");
        }
        return item;
    }

    private ReviewItem requirePendingMissingCover(String id) throws ReviewResolutionService.ResolutionException {
        ReviewItem item = requireMissingCoverItem(id);
        if (item.getStatus() != ReviewItem.Status.PENDING_REVIEW) {
            throw new ReviewResolutionService.ResolutionException(409, "item.already.resolved");
        }
        return item;
    }

    private void requireAllOriginalFiles(ReviewItem item) throws ReviewResolutionService.ResolutionException {
        if (item.getFiles() == null || item.getFiles().isEmpty()) {
            throw new ReviewResolutionService.ResolutionException(410, "files.missing");
        }
        for (ReviewItem.FileEntry entry : item.getFiles()) {
            if (entry == null || !meaningful(entry.getOriginalPath()) || !new File(entry.getOriginalPath()).isFile()) {
                throw new ReviewResolutionService.ResolutionException(410, "files.missing");
            }
        }
    }

    private File resolveProcessingFile(ReviewItem.FileEntry entry, File original) {
        if (entry != null && meaningful(entry.getStagedPath())) {
            File staged = new File(entry.getStagedPath());
            if (staged.isFile()) return staged;
        }
        return original;
    }

    private static boolean isSupportedAudio(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".m4a")
            || lower.endsWith(".mp4") || lower.endsWith(".ogg") || lower.endsWith(".opus")
            || lower.endsWith(".wav");
    }

    private static String displayAlbum(ReviewItem item, ReviewItem.MissingCover mc) {
        if (mc != null && meaningful(mc.getAlbumTitle())) return mc.getAlbumTitle();
        if (item != null && meaningful(item.getFolderName())) return item.getFolderName();
        return "Unknown Album";
    }

    private static boolean meaningful(String value) {
        return value != null && !value.isBlank();
    }

    private static void deleteRecursivelyQuietly(Path path) {
        try {
            if (!Files.exists(path)) return;
            try (var walk = Files.walk(path)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        scanCancelled.set(true);
        scanExecutor.shutdownNow();
    }
}
