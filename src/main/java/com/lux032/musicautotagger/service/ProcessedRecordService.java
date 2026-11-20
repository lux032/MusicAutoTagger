package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ProcessedRecord;
import com.lux032.musicautotagger.util.FileNameSanitizer;
import com.lux032.musicautotagger.util.FileSystemUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 处理记录管理（Web「处理记录」页面的业务层）。
 *
 * 两个写操作的语义：
 *  - 删除记录（forget）：只删 processed_files 中的记录，源文件下次被扫描到（重启 / 恢复监控）时会重新处理；
 *  - 重新识别（reidentify）：删除记录 + 清掉所在专辑目录的内存缓存 + 立即把仍在监控目录中的源文件重新入队。
 * 两者都不会删除或移动任何音频文件。
 */
@Slf4j
public class ProcessedRecordService {

    /** 单次批量操作的上限，防止误操作一次性清空几十万条记录。 */
    public static final int MAX_BATCH = 5000;

    private final MusicConfig config;
    private final ProcessedFileLogger logger;
    private final FolderAlbumCache folderAlbumCache;
    private final CoverArtService coverArtService;
    private final FileSystemUtils fileSystemUtils;
    private final Supplier<FileMonitorService> monitorSupplier;
    private final ReidentifyReplacementRegistry replacementRegistry;
    private RecoveryService recoveryService;
    private ReviewQueueService reviewQueueService;

    public ProcessedRecordService(MusicConfig config, ProcessedFileLogger logger,
                                  FolderAlbumCache folderAlbumCache, CoverArtService coverArtService,
                                  FileSystemUtils fileSystemUtils, Supplier<FileMonitorService> monitorSupplier) {
        this(config, logger, folderAlbumCache, coverArtService, fileSystemUtils, monitorSupplier,
            ReidentifyReplacementRegistry.forReviewQueuePath(config.getReviewQueuePath()));
    }

    ProcessedRecordService(MusicConfig config, ProcessedFileLogger logger,
                           FolderAlbumCache folderAlbumCache, CoverArtService coverArtService,
                           FileSystemUtils fileSystemUtils, Supplier<FileMonitorService> monitorSupplier,
                           ReidentifyReplacementRegistry replacementRegistry) {
        this.config = config;
        this.logger = logger;
        this.folderAlbumCache = folderAlbumCache;
        this.coverArtService = coverArtService;
        this.fileSystemUtils = fileSystemUtils;
        this.monitorSupplier = monitorSupplier;
        this.replacementRegistry = replacementRegistry;
    }

    public void setRecoveryService(RecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    public void setReviewQueueService(ReviewQueueService reviewQueueService) {
        this.reviewQueueService = reviewQueueService;
    }

    /** 分页查询，并为当前页补充文件是否存在等信息。 */
    public ProcessedRecordQuery.Page list(ProcessedRecordQuery query) {
        ProcessedRecordQuery.Page page = logger.queryRecords(query);
        Path monitorRoot = monitorRoot();
        for (ProcessedRecord r : page.items) {
            enrich(r, monitorRoot);
        }
        return page;
    }

    /** 导出：不分页，最多 limit 条，不做文件探测（导出大量记录时逐个 stat 太慢）。 */
    public List<ProcessedRecord> export(ProcessedRecordQuery query, int limit) {
        return logger.findRecords(query, limit);
    }

    /** 解析批量操作的目标：显式路径列表优先，否则使用筛选条件。 */
    public List<ProcessedRecord> resolveTargets(Collection<String> paths, ProcessedRecordQuery filter) {
        if (paths != null && !paths.isEmpty()) {
            List<String> limited = new ArrayList<>(new LinkedHashSet<>(paths));
            if (limited.size() > MAX_BATCH) limited = limited.subList(0, MAX_BATCH);
            return logger.findRecordsByPaths(limited);
        }
        if (filter != null) {
            return logger.findRecords(filter, MAX_BATCH);
        }
        return List.of();
    }

    /** 操作前预览：让前端在确认框里准确告诉用户会发生什么。 */
    public Map<String, Object> preview(List<ProcessedRecord> targets) {
        Path monitorRoot = monitorRoot();
        int requeueable = 0, missing = 0, outside = 0, success = 0;
        int recoverableFromTarget = 0, reviewBlockedCount = 0;
        Set<String> albums = new LinkedHashSet<>();
        for (ProcessedRecord r : targets) {
            enrich(r, monitorRoot);
            if (r.status == ProcessedRecord.Status.SUCCESS) success++;
            boolean hasSource = Boolean.TRUE.equals(r.sourceExists);
            boolean inMonitor = Boolean.TRUE.equals(r.inMonitorDirectory);
            boolean hasTarget = Boolean.TRUE.equals(r.targetExists);
            if (hasSource && inMonitor) {
                requeueable++;
                if (isUnderReview(new File(r.filePath))) reviewBlockedCount++;
            } else if ((hasSource || hasTarget) && recoveryService != null) {
                recoverableFromTarget++;
            } else if (!hasSource) {
                missing++;
            } else {
                outside++;
            }
            if (r.album != null && albums.size() < 6) albums.add(r.album);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", targets.size());
        result.put("requeueable", requeueable);
        result.put("recoverableFromTarget", recoverableFromTarget);
        result.put("reviewBlockedCount", reviewBlockedCount);
        result.put("sourceMissing", missing);
        result.put("outsideMonitor", outside);
        result.put("successCount", success);
        result.put("albums", albums);
        result.put("truncated", targets.size() >= MAX_BATCH);
        result.put("monitoringActive", isMonitoringActive());
        result.put("monitoringPaused", isMonitoringPaused());
        return result;
    }

    /** 仅删除记录（同时登记原成功归档路径，便于之后重扫时清理旧文件）。 */
    public Map<String, Object> forget(List<ProcessedRecord> targets) {
        recordReplacements(targets);
        int removed = logger.removeProcessedRecords(paths(targets));
        log.info("通过处理记录页删除了 {} 条记录", removed);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("removed", removed);
        return result;
    }

    /** 删除记录、清缓存并统一触发普通识别（识别失败按标准流程进失败记录由用户决定后续）。 */
    public Map<String, Object> reidentify(List<ProcessedRecord> targets) {
        Path monitorRoot = monitorRoot();

        List<ProcessedRecord> monitorTargets = new ArrayList<>();
        List<File> monitorFiles = new ArrayList<>();
        Set<String> monitorAlbumRoots = new LinkedHashSet<>();
        List<ProcessedRecord> stagedTargets = new ArrayList<>();
        List<ProcessedRecord> deadTargets = new ArrayList<>();
        int skippedUnderReview = 0;

        for (ProcessedRecord r : targets) {
            enrich(r, monitorRoot);
            boolean hasSource = Boolean.TRUE.equals(r.sourceExists);
            boolean inMonitor = Boolean.TRUE.equals(r.inMonitorDirectory);
            boolean hasTarget = Boolean.TRUE.equals(r.targetExists);
            if (hasSource && inMonitor) {
                File file = new File(r.filePath);
                if (isUnderReview(file)) {
                    skippedUnderReview++;
                    String folderName = albumRootName(file);
                    LogCollector.addLog("WARN", "手动重新识别跳过：" + folderName + " 已有条目在「待人工确认」队列中，请先在待确认页处理");
                    continue;
                }
                monitorTargets.add(r);
                monitorFiles.add(file);
                File root = fileSystemUtils != null ? fileSystemUtils.getAlbumRootDirectory(file) : file.getParentFile();
                if (root != null) monitorAlbumRoots.add(root.getAbsolutePath());
                if (file.getParentFile() != null) monitorAlbumRoots.add(file.getParentFile().getAbsolutePath());
            } else if ((hasSource || hasTarget) && recoveryService != null) {
                stagedTargets.add(r);
            } else {
                deadTargets.add(r);
            }
        }

        int removed = 0;
        int queued = 0;
        if (!monitorTargets.isEmpty()) {
            FileMonitorService monitor = monitorSupplier != null ? monitorSupplier.get() : null;
            if (monitor == null || !monitor.isConsumerAvailable())
                throw new IllegalStateException("File consumer unavailable; records unchanged");
            int[] removedCount = {0};
            queued = monitor.requeueFiles(monitorFiles, () -> {
                // Clear caches and persist old target paths before deleting records.
                recordReplacements(monitorTargets);
                for (String root : monitorAlbumRoots) {
                    if (folderAlbumCache != null) folderAlbumCache.clearFolderCache(root);
                    if (coverArtService != null) coverArtService.clearFolderCache(root);
                }
                removedCount[0] = logger.removeProcessedRecords(paths(monitorTargets));
            });
            removed += removedCount[0];
            if (queued > 0 && isMonitoringPaused()) {
                LogCollector.addLog("WARN", "手动重新识别：已入队 " + queued + " 个文件，当前监控处于暂停状态，恢复监控后才会开始处理");
            }
        }

        int recoveryQueued = submitStagedReidentify(stagedTargets);
        removed += recoveryQueued;

        if (!deadTargets.isEmpty()) {
            int deadRemoved = logger.removeProcessedRecords(paths(deadTargets));
            removed += deadRemoved;
            for (ProcessedRecord r : deadTargets) {
                LogCollector.addLog("INFO", "清理无效处理记录（源文件与归档文件均已不存在）: " + (r.fileName != null ? r.fileName : r.filePath));
            }
        }

        int totalQueued = queued + recoveryQueued;
        log.info("通过处理记录页重新识别：删除 {} 条记录，监控入队 {} 个，归档普通重识别 {} 个，待确认跳过 {} 个",
            removed, queued, recoveryQueued, skippedUnderReview);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("removed", removed);
        result.put("queued", totalQueued);
        result.put("monitorQueued", queued);
        result.put("recoveryQueued", recoveryQueued);
        result.put("skippedUnderReview", skippedUnderReview);
        result.put("notQueued", Math.max(0, targets.size() - totalQueued));
        result.put("monitoringPaused", isMonitoringPaused());
        return result;
    }

    private int submitStagedReidentify(List<ProcessedRecord> records) {
        if (records.isEmpty() || recoveryService == null) {
            return 0;
        }
        Map<String, List<ProcessedRecord>> groups = new LinkedHashMap<>();
        for (ProcessedRecord r : records) {
            File usable = Boolean.TRUE.equals(r.sourceExists) ? new File(r.filePath) : new File(r.targetFilePath);
            String key = usable.getParentFile() != null
                ? usable.getParentFile().getAbsolutePath() : usable.getAbsolutePath();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        int submittedCount = 0;
        for (List<ProcessedRecord> group : groups.values()) {
            String displayName = null;
            for (ProcessedRecord r : group) {
                File usable = Boolean.TRUE.equals(r.sourceExists) ? new File(r.filePath) : new File(r.targetFilePath);
                if (r.album != null && !r.album.isBlank() && !"Unknown Album".equalsIgnoreCase(r.album)) {
                    displayName = r.album;
                    break;
                }
                if (displayName == null) {
                    displayName = usable.getParentFile() != null ? usable.getParentFile().getName() : usable.getName();
                }
            }
            String safeFolder = FileNameSanitizer.sanitize(displayName == null ? "reidentify-album" : displayName);
            if (safeFolder.isBlank() || ".".equals(safeFolder) || "..".equals(safeFolder)) {
                safeFolder = "reidentify-album";
            }

            Path taskRoot = resolveStagingRoot().resolve("reprocess-" + UUID.randomUUID());
            Path albumStagingDir = taskRoot.resolve(safeFolder);
            List<File> stagedFiles = new ArrayList<>();
            List<File> fallbackRecordFiles = new ArrayList<>();
            List<Path> oldTargetPaths = new ArrayList<>();
            try {
                Files.createDirectories(albumStagingDir);
                for (ProcessedRecord r : group) {
                    File usable = Boolean.TRUE.equals(r.sourceExists) ? new File(r.filePath) : new File(r.targetFilePath);
                    Path staged = uniqueTarget(albumStagingDir, usable.getName());
                    Files.copy(usable.toPath(), staged, StandardCopyOption.REPLACE_EXISTING);
                    stagedFiles.add(staged.toFile());
                    fallbackRecordFiles.add(usable);
                    if (r.targetFilePath != null && !r.targetFilePath.isBlank()) {
                        oldTargetPaths.add(Path.of(r.targetFilePath));
                        if (replacementRegistry != null) {
                            String hash = logger != null ? logger.computeFileHashOrNull(staged.toFile()) : null;
                            replacementRegistry.record(staged.toFile().getAbsolutePath(), r.targetFilePath, hash);
                        }
                    }
                }
            } catch (IOException e) {
                log.error("准备重新识别暂存副本失败: {}", e.getMessage(), e);
                LogCollector.addLog("ERROR", "准备重新识别副本失败: " + e.getMessage());
                deleteQuietly(taskRoot);
                continue;
            }

            logger.removeProcessedRecords(paths(group));
            recoveryService.submitStagedReidentify(
                albumStagingDir.toFile(), displayName, stagedFiles, fallbackRecordFiles, oldTargetPaths);
            submittedCount += group.size();
        }
        return submittedCount;
    }

    private boolean isUnderReview(File file) {
        if (reviewQueueService == null || file == null) return false;
        File root = fileSystemUtils != null ? fileSystemUtils.getAlbumRootDirectory(file) : file.getParentFile();
        return root != null && reviewQueueService.isFolderUnderReview(root.getAbsolutePath());
    }

    private String albumRootName(File file) {
        File root = fileSystemUtils != null ? fileSystemUtils.getAlbumRootDirectory(file) : file.getParentFile();
        return root != null ? root.getName() : file.getName();
    }

    private Path resolveStagingRoot() {
        String configured = config.getReviewStagingDirectory();
        if (configured != null && !configured.trim().isEmpty()) {
            return Path.of(configured.trim());
        }
        return Path.of("data", "review-staging");
    }

    private static Path uniqueTarget(Path dir, String fileName) {
        Path candidate = dir.resolve(fileName);
        if (!Files.exists(candidate)) return candidate;
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            Path next = dir.resolve(stem + " (" + i + ")" + ext);
            if (!Files.exists(next)) return next;
        }
        return dir.resolve(UUID.randomUUID() + ext);
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
        }
    }

    public boolean isMonitoringActive() {
        FileMonitorService monitor = monitorSupplier != null ? monitorSupplier.get() : null;
        return monitor != null && monitor.isConsumerAvailable();
    }

    public boolean isMonitoringPaused() {
        FileMonitorService monitor = monitorSupplier != null ? monitorSupplier.get() : null;
        return monitor != null && monitor.isPaused();
    }

    private void recordReplacements(List<ProcessedRecord> records) {
        if (replacementRegistry == null || records == null) return;
        for (ProcessedRecord r : records) {
            if (r == null || r.filePath == null || r.targetFilePath == null || r.targetFilePath.isBlank()) {
                continue;
            }
            if (r.status != ProcessedRecord.Status.SUCCESS) {
                continue;
            }
            String fileHash = logger != null ? logger.computeFileHashOrNull(new File(r.filePath)) : null;
            replacementRegistry.record(r.filePath, r.targetFilePath, fileHash);
        }
    }

    private static List<String> paths(List<ProcessedRecord> records) {
        List<String> result = new ArrayList<>(records.size());
        for (ProcessedRecord r : records) result.add(r.filePath);
        return result;
    }

    private Path monitorRoot() {
        String dir = config.getMonitorDirectory();
        if (dir == null || dir.isBlank()) return null;
        try {
            return new File(dir).getCanonicalFile().toPath();
        } catch (IOException e) {
            return new File(dir).getAbsoluteFile().toPath();
        }
    }

    private void enrich(ProcessedRecord r, Path monitorRoot) {
        File source = new File(r.filePath);
        r.sourceExists = source.isFile();
        r.targetExists = r.targetFilePath == null ? null : new File(r.targetFilePath).isFile();
        boolean inside = false;
        if (monitorRoot != null) {
            try {
                inside = source.getCanonicalFile().toPath().startsWith(monitorRoot);
            } catch (IOException e) {
                inside = source.getAbsoluteFile().toPath().startsWith(monitorRoot);
            }
        }
        r.inMonitorDirectory = inside;
    }
}
