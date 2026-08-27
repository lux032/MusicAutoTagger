package com.lux032.musicautotagger.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
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

    /** id -> item，保持插入顺序，便于面板按时间展示 */
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

    // ==================== 查询 ====================

    public synchronized List<ReviewItem> list(ReviewItem.Status statusFilter) {
        List<ReviewItem> result = new ArrayList<>();
        for (ReviewItem item : items.values()) {
            if (statusFilter == null || statusFilter == item.getStatus()) {
                result.add(item);
            }
        }
        return result;
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

        // 只有新建条目、或本来就由恢复流程创建的条目，才归恢复流程所有
        boolean recoveryOwned = existing == null || existing.getRecoverySourceType() != null;

        if (existing == null) {
            item.setId(UUID.randomUUID().toString());
            item.setCreatedAt(System.currentTimeMillis());
            item.setFolderPath(folderPath);
            item.setFolderName(new File(folderPath).getName());
            item.setStatus(ReviewItem.Status.PENDING_REVIEW);
            item.setReason("联网辅助识别");
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

        if (recoveryOwned) {
            item.setRecoverySourceType(sourceType);
            item.setRecoverySourcePath(folderPath);

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

    public synchronized int countPending() {
        int count = 0;
        for (ReviewItem item : items.values()) {
            if (item.getStatus() == ReviewItem.Status.PENDING_REVIEW) {
                count++;
            }
        }
        return count;
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

        Path stagingDir = stagingRoot.resolve(item.getId());
        item.setStagingDir(stagingDir.toAbsolutePath().toString());

        // 关键顺序（审查 #3）：先构造条目并落盘，**落盘成功之后**才真正搬动转码临时文件。
        // 反过来做的话，一旦 save() 失败就会留下：JSON 无记录 + 临时文件已被搬走 = 孤儿文件。
        List<String> previousStagedPaths = collectStagedPaths(item);

        List<ReviewItem.FileEntry> entries = new ArrayList<>();
        List<StagingMove> plannedMoves = new ArrayList<>();
        for (FolderAlbumCache.PendingFile pending : pendingFiles) {
            entries.add(buildEntry(pending, stagingDir, plannedMoves));
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

        log.warn("========================================");
        log.warn("⚠ 专辑无法确定，已放入「待人工确认」队列");
        log.warn("  文件夹: {}", item.getFolderName());
        log.warn("  文件数: {}", entries.size());
        log.warn("  候选专辑数: {}", item.getCandidates().size());
        log.warn("  统一置信度: {}", String.format("%.2f", confidence));
        log.warn("  文件保持原样，未写入任何标签，等待在 Web 面板中确认");
        log.warn("========================================");
        LogCollector.addLog("WARN", "专辑待人工确认: " + item.getFolderName());

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

    private ReviewItem findPendingByFolder(String folderPath) {
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

    private ReviewItem.FileEntry buildEntry(FolderAlbumCache.PendingFile pending, Path stagingDir,
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
        // 注意：这里只「登记计划」，真正的移动要等 JSON 落盘成功之后。
        File processing = pending.getProcessingFile();
        if (processing != null && !processing.equals(original) && processing.exists()) {
            Path target = stagingDir.resolve(processing.getName());
            entry.setStagedPath(target.toAbsolutePath().toString());
            plannedMoves.add(new StagingMove(entry, processing.toPath(), target,
                pending.getProcessingTempDir()));
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
        MusicMetadata copy = new MusicMetadata();
        copy.setRecordingId(source.getRecordingId());
        copy.setTitle(source.getTitle());
        copy.setArtist(source.getArtist());
        copy.setAlbumArtist(source.getAlbumArtist());
        copy.setAlbum(source.getAlbum());
        copy.setReleaseDate(source.getReleaseDate());
        copy.setClearReleaseDate(source.isClearReleaseDate());
        copy.setGenres(source.getGenres());
        copy.setComposer(source.getComposer());
        copy.setLyricist(source.getLyricist());
        copy.setLyrics(source.getLyrics());
        copy.setDiscNo(source.getDiscNo());
        copy.setTrackNo(source.getTrackNo());
        copy.setDuration(source.getDuration());
        copy.setReleaseGroupId(source.getReleaseGroupId());
        copy.setReleaseId(source.getReleaseId());
        copy.setCoverArtUrl(source.getCoverArtUrl());
        copy.setScore(source.getScore());
        copy.setTrackCount(source.getTrackCount());
        return copy;
    }

    // ==================== 状态变更 ====================

    /**
     * 标记条目已处置，并清理暂存目录。
     */
    public synchronized void markResolved(ReviewItem item, ReviewItem.Status status, String note) {
        if (item == null) {
            return;
        }
        item.setStatus(status);
        item.setResolutionNote(note);
        item.setUpdatedAt(System.currentTimeMillis());
        cleanupStaging(item);
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
            if (item.getStatus() != ReviewItem.Status.CONFIRMED) {
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

    private void cleanupStaging(ReviewItem item) {
        String stagingDir = item.getStagingDir();
        if (stagingDir == null) {
            return;
        }
        Path dir = Paths.get(stagingDir);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.list(dir)) {
            for (Path path : paths.collect(java.util.stream.Collectors.toList())) {
                deleteQuietly(path);
            }
        } catch (IOException e) {
            log.debug("清理暂存目录内容失败: {} - {}", dir, e.getMessage());
        }
        deleteQuietly(dir);
        item.setStagingDir(null);
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
