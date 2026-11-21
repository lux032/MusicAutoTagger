package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ProcessedFolderRecord;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    /** 按文件夹（专辑根目录）聚合分页查询。 */
    public ProcessedRecordQuery.FolderPage listFolders(ProcessedRecordQuery query) {
        Path monitorRoot = monitorRoot();
        FolderFilterResult filtered = filterFolderGroups(query, monitorRoot);
        ProcessedRecordQuery.FolderPage page = new ProcessedRecordQuery.FolderPage();
        page.offset = query.safeOffset();
        page.limit = query.safeLimit();
        page.counts = filtered.counts;
        page.total = filtered.matched.size();
        long totalFiles = 0;
        for (FolderGroup g : filtered.matched) {
            totalFiles += g.records.size();
        }
        page.totalFiles = totalFiles;

        int from = Math.min(page.offset, filtered.matched.size());
        int to = Math.min(page.offset + page.limit, filtered.matched.size());
        for (int i = from; i < to; i++) {
            FolderGroup group = filtered.matched.get(i);
            enrichFolder(group);
            page.items.add(group.summary);
        }
        return page;
    }

    /** 查询指定文件夹下的全部子文件记录（用于行内手风琴展开）。 */
    public List<ProcessedRecord> listFolderFiles(String folderPath) {
        if (folderPath == null || folderPath.isBlank()) {
            return List.of();
        }
        Path monitorRoot = monitorRoot();
        String targetKey = normalizeFolderKey(canonicalOrNormalized(new File(folderPath)).toString());
        List<ProcessedRecord> matched = new ArrayList<>();
        for (ProcessedRecord r : logger.loadAllRecords()) {
            FolderInfo info = resolveFolderInfo(r.filePath, monitorRoot);
            if (targetKey.equals(normalizeFolderKey(info.folderPath))) {
                r.folderPath = info.folderPath;
                r.discName = info.discName;
                enrich(r, monitorRoot);
                matched.add(r);
            }
        }
        matched.sort(Comparator.comparing((ProcessedRecord r) -> r.filePath == null ? "" : r.filePath,
            String.CASE_INSENSITIVE_ORDER));
        return matched;
    }

    /** 解析单个文件路径所属的文件夹（专辑根目录）路径。 */
    public String resolveFolderPath(String filePath) {
        return resolveFolderInfo(filePath, monitorRoot()).folderPath;
    }

    /** 导出：不分页，最多 limit 条，不做文件探测（导出大量记录时逐个 stat 太慢）。 */
    public List<ProcessedRecord> export(ProcessedRecordQuery query, int limit) {
        Path monitorRoot = monitorRoot();
        if (query != null && query.isFolderView()) {
            FolderFilterResult filtered = filterFolderGroups(query, monitorRoot);
            List<ProcessedRecord> result = new ArrayList<>();
            for (FolderGroup g : filtered.matched) {
                List<ProcessedRecord> children = new ArrayList<>(g.records);
                children.sort(Comparator.comparing((ProcessedRecord r) -> r.filePath == null ? "" : r.filePath,
                    String.CASE_INSENSITIVE_ORDER));
                for (ProcessedRecord r : children) {
                    r.folderPath = g.summary.folderPath;
                    result.add(r);
                    if (result.size() >= limit) return result;
                }
            }
            return result;
        }
        List<ProcessedRecord> records = logger.findRecords(query, limit);
        for (ProcessedRecord r : records) {
            r.folderPath = resolveFolderInfo(r.filePath, monitorRoot).folderPath;
        }
        return records;
    }

    /** 解析批量操作的目标：显式路径列表优先，否则使用筛选条件。 */
    public List<ProcessedRecord> resolveTargets(Collection<String> paths, ProcessedRecordQuery filter) {
        return resolveTargets(paths, null, filter);
    }

    /** 解析批量操作的目标：显式文件路径 > 显式文件夹路径 > 筛选条件（支持文件视图与文件夹视图）。 */
    public List<ProcessedRecord> resolveTargets(Collection<String> paths, Collection<String> folders,
                                                ProcessedRecordQuery filter) {
        if (paths != null && !paths.isEmpty()) {
            List<String> limited = new ArrayList<>(new LinkedHashSet<>(paths));
            if (limited.size() > MAX_BATCH) limited = limited.subList(0, MAX_BATCH);
            return logger.findRecordsByPaths(limited);
        }
        if (folders != null && !folders.isEmpty()) {
            Path monitorRoot = monitorRoot();
            Set<String> wantedKeys = new LinkedHashSet<>();
            for (String f : folders) {
                if (f != null && !f.isBlank()) {
                    wantedKeys.add(normalizeFolderKey(canonicalOrNormalized(new File(f)).toString()));
                }
            }
            if (wantedKeys.isEmpty()) return List.of();
            List<ProcessedRecord> matched = new ArrayList<>();
            for (ProcessedRecord r : logger.loadAllRecords()) {
                FolderInfo info = resolveFolderInfo(r.filePath, monitorRoot);
                if (wantedKeys.contains(normalizeFolderKey(info.folderPath))) {
                    matched.add(r);
                    if (matched.size() >= MAX_BATCH) break;
                }
            }
            return matched;
        }
        if (filter != null) {
            if (filter.isFolderView()) {
                FolderFilterResult filtered = filterFolderGroups(filter, monitorRoot());
                List<ProcessedRecord> matched = new ArrayList<>();
                for (FolderGroup g : filtered.matched) {
                    for (ProcessedRecord r : g.records) {
                        matched.add(r);
                        if (matched.size() >= MAX_BATCH) return matched;
                    }
                }
                return matched;
            }
            return logger.findRecords(filter, MAX_BATCH);
        }
        return List.of();
    }

    /** 操作前预览：让前端在确认框里准确告诉用户会发生什么。 */
    public Map<String, Object> preview(List<ProcessedRecord> targets) {
        Path monitorRoot = monitorRoot();
        int requeueable = 0, missing = 0, outside = 0, success = 0, failed = 0, other = 0;
        int recoverableFromTarget = 0, reviewBlockedCount = 0;
        Set<String> albums = new LinkedHashSet<>();
        Set<String> folderKeys = new HashSet<>();
        for (ProcessedRecord r : targets) {
            enrich(r, monitorRoot);
            folderKeys.add(normalizeFolderKey(resolveFolderInfo(r.filePath, monitorRoot).folderPath));
            if (r.status == ProcessedRecord.Status.SUCCESS) success++;
            else if (r.status == ProcessedRecord.Status.FAILED) failed++;
            else other++;
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
            if (r.album != null && !r.album.isBlank() && !"Unknown Album".equalsIgnoreCase(r.album) && albums.size() < 6) {
                albums.add(r.album);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", targets.size());
        result.put("folderCount", folderKeys.size());
        result.put("requeueable", requeueable);
        result.put("recoverableFromTarget", recoverableFromTarget);
        result.put("reviewBlockedCount", reviewBlockedCount);
        result.put("sourceMissing", missing);
        result.put("outsideMonitor", outside);
        result.put("successCount", success);
        result.put("failedCount", failed);
        result.put("otherCount", other);
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
            List<RecoveryService.StagedReidentifyFile> stagedFiles = new ArrayList<>();
            try {
                Files.createDirectories(albumStagingDir);
                for (ProcessedRecord r : group) {
                    File usable = Boolean.TRUE.equals(r.sourceExists) ? new File(r.filePath) : new File(r.targetFilePath);
                    Path staged = uniqueTarget(albumStagingDir, usable.getName());
                    Files.copy(usable.toPath(), staged, StandardCopyOption.REPLACE_EXISTING);
                    Path oldTarget = replacementRegistry == null ? null
                        : replacementRegistry.findOldTarget(usable).orElse(null);
                    if (oldTarget == null && r.targetFilePath != null && !r.targetFilePath.isBlank()) {
                        oldTarget = Path.of(r.targetFilePath);
                    }
                    stagedFiles.add(new RecoveryService.StagedReidentifyFile(staged.toFile(), usable, oldTarget));
                }
            } catch (IOException e) {
                log.error("准备重新识别暂存副本失败: {}", e.getMessage(), e);
                LogCollector.addLog("ERROR", "准备重新识别副本失败: " + e.getMessage());
                deleteQuietly(taskRoot);
                continue;
            }

            try {
                recoveryService.submitStagedReidentify(albumStagingDir.toFile(), displayName, stagedFiles, paths(group));
            } catch (java.util.concurrent.RejectedExecutionException e) {
                deleteQuietly(taskRoot);
                LogCollector.addLog("ERROR", "恢复服务已停止，未能提交重新识别: " + displayName);
                continue;
            }
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
            replacementRegistry.record(r.filePath, r.targetFilePath);
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

    // ==================== 按文件夹聚合 ====================

    private static final class FolderInfo {
        final String folderPath;
        final String folderName;
        final String relativePath;
        final boolean looseRoot;
        final String discName;

        FolderInfo(String folderPath, String folderName, String relativePath, boolean looseRoot, String discName) {
            this.folderPath = folderPath;
            this.folderName = folderName;
            this.relativePath = relativePath;
            this.looseRoot = looseRoot;
            this.discName = discName;
        }
    }

    private static final class FolderGroup {
        final ProcessedFolderRecord summary;
        final List<ProcessedRecord> records = new ArrayList<>();
        final Set<String> discNames = new LinkedHashSet<>();
        final Map<String, Integer> albumFreq = new LinkedHashMap<>();
        final Map<String, Integer> artistFreq = new LinkedHashMap<>();

        FolderGroup(ProcessedFolderRecord summary) {
            this.summary = summary;
        }
    }

    private static final class FolderFilterResult {
        final List<FolderGroup> matched;
        final Map<String, Long> counts;

        FolderFilterResult(List<FolderGroup> matched, Map<String, Long> counts) {
            this.matched = matched;
            this.counts = counts;
        }
    }

    private FolderFilterResult filterFolderGroups(ProcessedRecordQuery query, Path monitorRoot) {
        List<FolderGroup> allGroups = groupByFolder(logger.loadAllRecords(), monitorRoot);
        String album = query == null ? null : query.normalizedAlbum();
        String kw = query == null ? null : query.normalizedKeyword();
        String lowerKw = kw == null ? null : kw.toLowerCase(Locale.ROOT);

        List<FolderGroup> base = new ArrayList<>();
        for (FolderGroup g : allGroups) {
            if (album != null && !folderHasAlbum(g, album)) continue;
            if (lowerKw != null && !folderMatchesKeyword(g, lowerKw)) continue;
            base.add(g);
        }

        Map<String, Long> counts = new HashMap<>();
        long success = 0, failed = 0, other = 0;
        for (FolderGroup g : base) {
            if (g.summary.status == ProcessedRecord.Status.SUCCESS) success++;
            else if (g.summary.status == ProcessedRecord.Status.FAILED) failed++;
            else other++;
        }
        counts.put("ALL", (long) base.size());
        counts.put("SUCCESS", success);
        counts.put("FAILED", failed);
        counts.put("OTHER", other);

        ProcessedRecord.Status statusFilter = query == null ? null : query.status;
        List<FolderGroup> matched = new ArrayList<>();
        for (FolderGroup g : base) {
            if (statusFilter == null || g.summary.status == statusFilter) {
                matched.add(g);
            }
        }
        ProcessedRecordQuery.Sort sort = query == null || query.sort == null
            ? ProcessedRecordQuery.Sort.TIME_DESC : query.sort;
        matched.sort(folderComparator(sort));
        return new FolderFilterResult(matched, counts);
    }

    private List<FolderGroup> groupByFolder(List<ProcessedRecord> records, Path monitorRoot) {
        Map<String, FolderGroup> groups = new LinkedHashMap<>();
        for (ProcessedRecord r : records) {
            FolderInfo info = resolveFolderInfo(r.filePath, monitorRoot);
            String key = normalizeFolderKey(info.folderPath);
            FolderGroup g = groups.get(key);
            if (g == null) {
                ProcessedFolderRecord s = new ProcessedFolderRecord();
                s.folderPath = info.folderPath;
                s.folderName = info.folderName;
                s.relativePath = info.relativePath;
                s.looseRoot = info.looseRoot;
                g = new FolderGroup(s);
                groups.put(key, g);
            }
            r.folderPath = info.folderPath;
            r.discName = info.discName;
            g.records.add(r);
            if (info.discName != null) {
                g.discNames.add(info.discName);
            }

            ProcessedFolderRecord s = g.summary;
            s.totalCount++;
            if (r.status == ProcessedRecord.Status.SUCCESS) {
                s.successCount++;
                if (isValidAlbum(r.album)) {
                    g.albumFreq.merge(r.album.trim(), 1, Integer::sum);
                }
                if (isValidArtist(r.artist)) {
                    g.artistFreq.merge(r.artist.trim(), 1, Integer::sum);
                }
            } else if (r.status == ProcessedRecord.Status.FAILED) {
                s.failedCount++;
            } else {
                s.otherCount++;
                String recId = r.recordingId == null ? "" : r.recordingId.trim();
                if ("REVIEW_REJECTED".equals(recId)) s.rejectedCount++;
                else if ("CUE_SPLIT".equals(recId)) s.cueSplitCount++;
            }

            if (r.processedTime != null && (s.latestProcessedTime == null
                || r.processedTime.compareTo(s.latestProcessedTime) > 0)) {
                s.latestProcessedTime = r.processedTime;
            }
        }

        List<FolderGroup> result = new ArrayList<>(groups.size());
        for (FolderGroup g : groups.values()) {
            ProcessedFolderRecord s = g.summary;
            s.discCount = g.discNames.size();
            s.albums = sortByFrequency(g.albumFreq);
            s.primaryAlbum = s.albums.isEmpty() ? null : s.albums.get(0);
            s.artists = sortByFrequency(g.artistFreq);
            s.primaryArtist = s.artists.isEmpty() ? null : s.artists.get(0);

            if (s.failedCount > 0) {
                s.status = ProcessedRecord.Status.FAILED;
                s.folderState = s.successCount > 0
                    ? ProcessedFolderRecord.FolderState.PARTIAL_FAILED
                    : ProcessedFolderRecord.FolderState.ALL_FAILED;
            } else if (s.successCount > 0 && s.rejectedCount == 0) {
                s.status = ProcessedRecord.Status.SUCCESS;
                s.folderState = ProcessedFolderRecord.FolderState.SUCCESS;
            } else {
                s.status = ProcessedRecord.Status.OTHER;
                if (s.rejectedCount > 0) {
                    s.folderState = s.successCount > 0
                        ? ProcessedFolderRecord.FolderState.PARTIAL_REJECTED
                        : ProcessedFolderRecord.FolderState.REJECTED;
                } else if (s.cueSplitCount > 0) {
                    s.folderState = ProcessedFolderRecord.FolderState.CUE_ONLY;
                } else {
                    s.folderState = ProcessedFolderRecord.FolderState.OTHER;
                }
            }
            result.add(g);
        }
        return result;
    }

    private void enrichFolder(FolderGroup group) {
        ProcessedFolderRecord s = group.summary;
        int missing = 0;
        for (ProcessedRecord r : group.records) {
            if (r.filePath == null || !new File(r.filePath).isFile()) {
                missing++;
            }
        }
        s.missingSourceCount = missing;

        if (s.folderState == ProcessedFolderRecord.FolderState.ALL_FAILED || s.successCount <= 0) {
            return;
        }
        // 优先从主专辑对应的成功记录取封面凭据，其次回退到文件夹内任意成功记录
        pickCover(group.records, s.primaryAlbum, s);
        if (s.coverReleaseGroupId == null || s.coverPath == null) {
            pickCover(group.records, null, s);
        }
    }

    private static void pickCover(List<ProcessedRecord> records, String preferredAlbum, ProcessedFolderRecord target) {
        for (ProcessedRecord r : records) {
            if (r.status != ProcessedRecord.Status.SUCCESS) continue;
            if (preferredAlbum != null && !preferredAlbum.equals(r.album == null ? null : r.album.trim())) continue;
            if (target.coverReleaseGroupId == null && r.releaseGroupId != null && !r.releaseGroupId.isBlank()) {
                target.coverReleaseGroupId = r.releaseGroupId;
            }
            if (target.coverPath == null) {
                if (r.targetFilePath != null && !r.targetFilePath.isBlank() && new File(r.targetFilePath).isFile()) {
                    target.coverPath = r.targetFilePath;
                } else if (r.filePath != null && !r.filePath.isBlank() && new File(r.filePath).isFile()) {
                    target.coverPath = r.filePath;
                }
            }
            if (target.coverReleaseGroupId != null && target.coverPath != null) {
                return;
            }
        }
    }

    private FolderInfo resolveFolderInfo(String filePath, Path monitorRoot) {
        if (filePath == null || filePath.isBlank()) {
            return new FolderInfo("", "", "", false, null);
        }
        File source = new File(filePath);
        File parent = source.getParentFile();
        if (parent == null) {
            String p = canonicalOrNormalized(source).toString();
            return new FolderInfo(p, p, p, false, null);
        }
        Path parentPath = canonicalOrNormalized(parent);
        if (monitorRoot != null && parentPath.equals(monitorRoot)) {
            return new FolderInfo(monitorRoot.toString(), fileNameOf(monitorRoot), "", true, null);
        }

        String parentName = fileNameOf(parentPath);
        Path resolved = parentPath;
        String discName = null;
        if (FileSystemUtils.isDiscFolderName(parentName)) {
            Path grand = parentPath.getParent();
            if (grand != null) {
                boolean allowed = monitorRoot == null
                    || (!grand.equals(monitorRoot) && grand.startsWith(monitorRoot));
                if (allowed) {
                    resolved = grand;
                    discName = parentName;
                }
            }
        }

        String folderPath = resolved.toString();
        String folderName = fileNameOf(resolved);
        String relativePath = folderPath;
        if (monitorRoot != null && resolved.startsWith(monitorRoot)) {
            relativePath = monitorRoot.relativize(resolved).toString().replace('\\', '/');
        }
        return new FolderInfo(folderPath, folderName, relativePath, false, discName);
    }

    private static Path canonicalOrNormalized(File file) {
        try {
            return file.getCanonicalFile().toPath();
        } catch (IOException e) {
            return file.toPath().toAbsolutePath().normalize();
        }
    }

    private static String fileNameOf(Path path) {
        Path fn = path.getFileName();
        return fn != null ? fn.toString() : path.toString();
    }

    private static String normalizeFolderKey(String folderPath) {
        if (folderPath == null) return "";
        return File.separatorChar == '\\' ? folderPath.toLowerCase(Locale.ROOT) : folderPath;
    }

    private static boolean isValidAlbum(String album) {
        return album != null && !album.isBlank() && !"Unknown Album".equalsIgnoreCase(album.trim());
    }

    private static boolean isValidArtist(String artist) {
        return artist != null && !artist.isBlank() && !"Unknown Artist".equalsIgnoreCase(artist.trim());
    }

    private static List<String> sortByFrequency(Map<String, Integer> freq) {
        return freq.entrySet().stream()
            .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER))
            .map(Map.Entry::getKey)
            .toList();
    }

    private static boolean folderHasAlbum(FolderGroup g, String album) {
        for (ProcessedRecord r : g.records) {
            if (album.equals(r.album)) return true;
        }
        return false;
    }

    private static boolean folderMatchesKeyword(FolderGroup g, String lowerKw) {
        ProcessedFolderRecord s = g.summary;
        if (containsIgnoreCase(s.folderName, lowerKw)
            || containsIgnoreCase(s.folderPath, lowerKw)
            || containsIgnoreCase(s.relativePath, lowerKw)) {
            return true;
        }
        for (ProcessedRecord r : g.records) {
            if (containsIgnoreCase(r.filePath, lowerKw)
                || containsIgnoreCase(r.artist, lowerKw)
                || containsIgnoreCase(r.title, lowerKw)
                || containsIgnoreCase(r.album, lowerKw)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(String value, String lowerKw) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerKw);
    }

    private static Comparator<FolderGroup> folderComparator(ProcessedRecordQuery.Sort sort) {
        Comparator<String> textNullsLast = Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
        Comparator<String> timeNullsLast = Comparator.nullsLast(Comparator.<String>naturalOrder());
        Comparator<FolderGroup> byPath = Comparator.comparing(g -> g.summary.folderPath, textNullsLast);
        switch (sort) {
            case TIME_ASC:
                return Comparator.comparing((FolderGroup g) -> g.summary.latestProcessedTime, timeNullsLast)
                    .thenComparing(byPath);
            case ALBUM:
                return Comparator.comparing((FolderGroup g) -> g.summary.primaryAlbum, textNullsLast)
                    .thenComparing(g -> g.summary.folderName, textNullsLast)
                    .thenComparing(byPath);
            case ARTIST:
                return Comparator.comparing((FolderGroup g) -> g.summary.primaryArtist, textNullsLast)
                    .thenComparing(g -> g.summary.primaryAlbum, textNullsLast)
                    .thenComparing(byPath);
            case TITLE:
                return Comparator.comparing((FolderGroup g) -> g.summary.folderName, textNullsLast)
                    .thenComparing(byPath);
            case PATH:
                return byPath;
            default:
                return Comparator.comparing((FolderGroup g) -> g.summary.latestProcessedTime,
                    Comparator.nullsLast(Comparator.<String>reverseOrder()))
                    .thenComparing(byPath);
        }
    }
}
