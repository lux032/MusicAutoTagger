package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ProcessedFolderRecord;
import com.lux032.musicautotagger.model.ProcessedRecord;
import com.lux032.musicautotagger.util.AudioFileOrdering;
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
    /** 防并发重复提交同一专辑/散曲的暂存重识别任务（按规范化分组键索引在途 Future）。 */
    private final Map<String, java.util.concurrent.Future<?>> runningStagedTasks =
        new java.util.concurrent.ConcurrentHashMap<>();
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

    /** 按文件夹（专辑根目录）聚合分页查询。数据库模式下优先走持久化 folder_path 列的 SQL 分页，避免全表载入堆内存。 */
    public ProcessedRecordQuery.FolderPage listFolders(ProcessedRecordQuery query) {
        FolderResolver resolver = new FolderResolver();
        ProcessedRecordQuery.FolderPage page = new ProcessedRecordQuery.FolderPage();
        page.offset = query.safeOffset();
        page.limit = query.safeLimit();

        if (logger.isFolderPathColumnAvailable()) {
            ProcessedFileLogger.FolderSqlPage sqlPage = logger.queryFolderPageSql(query);
            if (sqlPage != null) {
                page.counts = sqlPage.counts;
                page.total = sqlPage.total;
                page.totalFiles = sqlPage.totalFiles;
                if (!sqlPage.folderPaths.isEmpty()) {
                    List<ProcessedRecord> pageRecords = logger.findRecordsByFolderPaths(
                        sqlPage.folderPaths, Integer.MAX_VALUE);
                    List<FolderGroup> built = groupByFolder(pageRecords == null ? List.of() : pageRecords, resolver);
                    Map<String, FolderGroup> byKey = new LinkedHashMap<>();
                    for (FolderGroup g : built) {
                        byKey.put(normalizeFolderKey(g.summary.folderPath), g);
                    }
                    Set<String> emitted = new HashSet<>();
                    for (String fp : sqlPage.folderPaths) {
                        String key = normalizeFolderKey(fp);
                        FolderGroup g = byKey.get(key);
                        if (g != null && emitted.add(key)) {
                            enrichFolder(g);
                            page.items.add(g.summary);
                        }
                    }
                    for (Map.Entry<String, FolderGroup> entry : byKey.entrySet()) {
                        if (emitted.add(entry.getKey())) {
                            enrichFolder(entry.getValue());
                            page.items.add(entry.getValue().summary);
                        }
                    }
                }
                return page;
            }
        }

        FolderFilterResult filtered = filterFolderGroups(query, resolver);
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
        FolderResolver resolver = new FolderResolver();
        String canonical = canonicalOrNormalized(new File(folderPath)).toString();
        String targetKey = normalizeFolderKey(canonical);
        List<ProcessedRecord> candidates;
        if (logger.isFolderPathColumnAvailable()) {
            Set<String> lookupKeys = new LinkedHashSet<>();
            lookupKeys.add(canonical);
            lookupKeys.add(folderPath.trim());
            candidates = logger.findRecordsByFolderPaths(lookupKeys, Integer.MAX_VALUE);
        } else {
            candidates = logger.loadAllRecords();
        }
        List<ProcessedRecord> matched = new ArrayList<>();
        if (candidates != null) {
            for (ProcessedRecord r : candidates) {
                FolderInfo info = resolver.resolve(r.filePath);
                if (targetKey.equals(normalizeFolderKey(info.folderPath))) {
                    r.folderPath = info.folderPath;
                    r.discName = info.discName;
                    enrich(r, resolver.monitorRoot);
                    matched.add(r);
                }
            }
        }
        matched.sort(Comparator.comparing((ProcessedRecord r) -> r.filePath == null ? "" : r.filePath,
            String.CASE_INSENSITIVE_ORDER));
        return matched;
    }

    /** 解析单个文件路径所属的文件夹（专辑根目录）路径。 */
    public String resolveFolderPath(String filePath) {
        return new FolderResolver().resolve(filePath).folderPath;
    }

    private static final int EXPORT_FOLDER_CHUNK = 100;

    /**
     * 流式导出处理记录：直接边查询边写入回调，避免大库 10 万条导出时在堆内存一次性堆积全部记录。
     */
    public int exportStream(ProcessedRecordQuery query, int limit,
                            java.util.function.Consumer<ProcessedRecord> consumer) {
        if (consumer == null || limit <= 0) {
            return 0;
        }
        FolderResolver resolver = new FolderResolver();
        if (query != null && query.isFolderView()) {
            if (logger.isFolderPathColumnAvailable()) {
                List<String> matchedFolders = logger.findFolderPathsSql(query, limit);
                if (matchedFolders != null) {
                    if (matchedFolders.isEmpty()) return 0;
                    int emitted = 0;
                    for (int i = 0; i < matchedFolders.size() && emitted < limit; i += EXPORT_FOLDER_CHUNK) {
                        List<String> chunk = matchedFolders.subList(i, Math.min(i + EXPORT_FOLDER_CHUNK, matchedFolders.size()));
                        int remaining = limit - emitted;
                        List<ProcessedRecord> fetched = logger.findRecordsByFolderPaths(chunk, remaining);
                        Map<String, List<ProcessedRecord>> byFolder = new LinkedHashMap<>();
                        for (String fp : chunk) {
                            byFolder.put(normalizeFolderKey(fp), new ArrayList<>());
                        }
                        if (fetched != null) {
                            for (ProcessedRecord r : fetched) {
                                FolderInfo info = resolver.resolve(r.filePath);
                                r.folderPath = info.folderPath;
                                r.discName = info.discName;
                                byFolder.computeIfAbsent(normalizeFolderKey(info.folderPath), k -> new ArrayList<>()).add(r);
                            }
                        }
                        for (List<ProcessedRecord> children : byFolder.values()) {
                            children.sort(Comparator.comparing((ProcessedRecord r) -> r.filePath == null ? "" : r.filePath,
                                String.CASE_INSENSITIVE_ORDER));
                            for (ProcessedRecord r : children) {
                                consumer.accept(r);
                                emitted++;
                                if (emitted >= limit) return emitted;
                            }
                        }
                    }
                    return emitted;
                }
            }
            FolderFilterResult filtered = filterFolderGroups(query, resolver);
            int emitted = 0;
            for (FolderGroup g : filtered.matched) {
                List<ProcessedRecord> children = new ArrayList<>(g.records);
                children.sort(Comparator.comparing((ProcessedRecord r) -> r.filePath == null ? "" : r.filePath,
                    String.CASE_INSENSITIVE_ORDER));
                for (ProcessedRecord r : children) {
                    r.folderPath = g.summary.folderPath;
                    consumer.accept(r);
                    emitted++;
                    if (emitted >= limit) return emitted;
                }
            }
            return emitted;
        }
        return logger.streamRecords(query, limit, r -> {
            r.folderPath = resolver.resolve(r.filePath).folderPath;
            consumer.accept(r);
        });
    }

    /** 导出：不分页，最多 limit 条，不做文件探测（保留列表返回形式供既有调用方复用）。 */
    public List<ProcessedRecord> export(ProcessedRecordQuery query, int limit) {
        List<ProcessedRecord> result = new ArrayList<>();
        exportStream(query, limit, result::add);
        return result;
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
            FolderResolver resolver = new FolderResolver();
            Set<String> wantedKeys = new LinkedHashSet<>();
            Set<String> sqlLookupPaths = new LinkedHashSet<>();
            for (String f : folders) {
                if (f != null && !f.isBlank()) {
                    String canonical = canonicalOrNormalized(new File(f)).toString();
                    wantedKeys.add(normalizeFolderKey(canonical));
                    sqlLookupPaths.add(canonical);
                    sqlLookupPaths.add(f.trim());
                }
            }
            if (wantedKeys.isEmpty()) return List.of();
            if (logger.isFolderPathColumnAvailable()) {
                List<ProcessedRecord> direct = logger.findRecordsByFolderPaths(sqlLookupPaths, MAX_BATCH);
                if (direct != null) {
                    return direct;
                }
            }
            List<ProcessedRecord> matched = new ArrayList<>();
            for (ProcessedRecord r : logger.loadAllRecords()) {
                FolderInfo info = resolver.resolve(r.filePath);
                if (wantedKeys.contains(normalizeFolderKey(info.folderPath))) {
                    matched.add(r);
                    if (matched.size() >= MAX_BATCH) break;
                }
            }
            return matched;
        }
        if (filter != null) {
            if (filter.isFolderView()) {
                if (logger.isFolderPathColumnAvailable()) {
                    List<String> folderPaths = logger.findFolderPathsSql(filter, MAX_BATCH);
                    if (folderPaths != null) {
                        if (folderPaths.isEmpty()) return List.of();
                        List<ProcessedRecord> direct = logger.findRecordsByFolderPaths(folderPaths, MAX_BATCH);
                        if (direct != null) {
                            return direct;
                        }
                    }
                }
                FolderFilterResult filtered = filterFolderGroups(filter, new FolderResolver());
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
        FolderResolver resolver = new FolderResolver();
        int requeueable = 0, missing = 0, outside = 0, success = 0, failed = 0, other = 0;
        int recoverableFromTarget = 0, reviewBlockedCount = 0;
        Set<String> albums = new LinkedHashSet<>();
        Set<String> folderKeys = new HashSet<>();
        for (ProcessedRecord r : targets) {
            enrich(r, resolver.monitorRoot);
            folderKeys.add(normalizeFolderKey(resolver.resolve(r.filePath).folderPath));
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
        runningStagedTasks.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isDone());
        FolderResolver resolver = new FolderResolver();
        record StagedCandidate(ProcessedRecord record, File usable, FolderInfo folderInfo) {}
        Map<String, List<StagedCandidate>> groups = new LinkedHashMap<>();
        for (ProcessedRecord r : records) {
            File usable = Boolean.TRUE.equals(r.sourceExists) ? new File(r.filePath) : new File(r.targetFilePath);
            FolderInfo info = resolver.resolve(usable.getAbsolutePath());
            // 监控根散曲、配置根目录散曲，以及输出库内单层艺人目录（outputRoot/<Artist>/track.flac）均视为独立单曲，
            // 防止同艺人不同散曲在暂存重识别时被误合为同一张专辑。
            boolean singleTrack = info.looseRoot
                || resolver.isConfiguredRoot(info.folderPath)
                || resolver.isSingleLevelOutputArtistDir(info.folderPath);
            String key = singleTrack
                ? normalizeFolderKey(usable.getAbsolutePath())
                : normalizeFolderKey(info.folderPath);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new StagedCandidate(r, usable, info));
        }

        int submittedCount = 0;
        for (Map.Entry<String, List<StagedCandidate>> entry : groups.entrySet()) {
            String groupKey = entry.getKey();
            List<StagedCandidate> group = entry.getValue();

            java.util.concurrent.CompletableFuture<Void> placeholder = new java.util.concurrent.CompletableFuture<>();
            java.util.concurrent.Future<?> existing = runningStagedTasks.putIfAbsent(groupKey, placeholder);
            if (existing != null) {
                if (!existing.isDone() || !runningStagedTasks.replace(groupKey, existing, placeholder)) {
                    LogCollector.addLog("WARN", "重新识别任务正在进行中，已跳过重复提交: " + groupKey);
                    continue;
                }
            }

            String displayName = null;
            for (StagedCandidate item : group) {
                ProcessedRecord r = item.record();
                if (r.album != null && !r.album.isBlank() && !"Unknown Album".equalsIgnoreCase(r.album)) {
                    displayName = r.album;
                    break;
                }
                if (displayName == null) {
                    String candidateName = item.folderInfo().folderName;
                    displayName = (candidateName != null && !candidateName.isBlank())
                        ? candidateName
                        : (item.usable().getParentFile() != null ? item.usable().getParentFile().getName() : item.usable().getName());
                }
            }
            String safeFolder = FileNameSanitizer.sanitize(displayName == null ? "reidentify-album" : displayName);
            if (safeFolder.isBlank() || ".".equals(safeFolder) || "..".equals(safeFolder)) {
                safeFolder = "reidentify-album";
            }

            Path taskRoot = resolveStagingRoot().resolve("reprocess-" + UUID.randomUUID());
            Path albumStagingDir = taskRoot.resolve(safeFolder);
            List<RecoveryService.StagedReidentifyFile> stagedFiles = new ArrayList<>();
            List<ProcessedRecord> groupRecords = new ArrayList<>(group.size());
            try {
                Files.createDirectories(albumStagingDir);
                for (StagedCandidate item : group) {
                    ProcessedRecord r = item.record();
                    groupRecords.add(r);
                    File usable = item.usable();
                    Path targetDir = albumStagingDir;
                    if (item.folderInfo().discName != null && !item.folderInfo().discName.isBlank()) {
                        String safeDisc = FileNameSanitizer.sanitize(item.folderInfo().discName);
                        if (!safeDisc.isBlank() && !".".equals(safeDisc) && !"..".equals(safeDisc)) {
                            targetDir = albumStagingDir.resolve(safeDisc);
                            Files.createDirectories(targetDir);
                        }
                    }
                    Path staged = uniqueTarget(targetDir, usable.getName());
                    Files.copy(usable.toPath(), staged, StandardCopyOption.REPLACE_EXISTING);
                    Path oldTarget = replacementRegistry == null ? null
                        : replacementRegistry.findOldTarget(usable).orElse(null);
                    if (oldTarget == null && r.targetFilePath != null && !r.targetFilePath.isBlank()) {
                        oldTarget = Path.of(r.targetFilePath);
                    }
                    stagedFiles.add(new RecoveryService.StagedReidentifyFile(staged.toFile(), usable, oldTarget));
                }
                Comparator<File> fileOrder = AudioFileOrdering.comparator();
                stagedFiles.sort((a, b) -> fileOrder.compare(a.staged(), b.staged()));
            } catch (IOException e) {
                runningStagedTasks.remove(groupKey, placeholder);
                log.error("准备重新识别暂存副本失败: {}", e.getMessage(), e);
                LogCollector.addLog("ERROR", "准备重新识别副本失败: " + e.getMessage());
                deleteQuietly(taskRoot);
                continue;
            }

            try {
                java.util.concurrent.Future<?> submitted = recoveryService.submitStagedReidentify(
                    albumStagingDir.toFile(), displayName, stagedFiles, paths(groupRecords));
                if (submitted == null || submitted.isDone()) {
                    runningStagedTasks.remove(groupKey, placeholder);
                } else {
                    runningStagedTasks.replace(groupKey, placeholder, submitted);
                }
            } catch (java.util.concurrent.RejectedExecutionException e) {
                runningStagedTasks.remove(groupKey, placeholder);
                deleteQuietly(taskRoot);
                LogCollector.addLog("ERROR", "恢复服务已停止，未能提交重新识别: " + displayName);
                continue;
            } catch (RuntimeException e) {
                runningStagedTasks.remove(groupKey, placeholder);
                deleteQuietly(taskRoot);
                throw e;
            }
            submittedCount += group.size();
        }
        return submittedCount;
    }

    private boolean isUnderReview(File file) {
        if (reviewQueueService == null || file == null) return false;
        // 散曲在待确认/缺封面队列中以自身文件路径作为 folderPath，必须先查文件自身路径
        if (reviewQueueService.isFolderUnderReview(file.getAbsolutePath())) {
            return true;
        }
        if (fileSystemUtils != null && fileSystemUtils.isLooseFileInMonitorRoot(file)) {
            return false;
        }
        Path monitorRoot = monitorRoot();
        File parent = file.getParentFile();
        if (fileSystemUtils == null && monitorRoot != null && parent != null
                && canonicalOrNormalized(parent).equals(monitorRoot)) {
            return false;
        }
        File root = fileSystemUtils != null ? fileSystemUtils.getAlbumRootDirectory(file) : parent;
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
        if (replacementRegistry == null || records == null || records.isEmpty()) return;
        Map<String, String> batch = new LinkedHashMap<>();
        for (ProcessedRecord r : records) {
            if (r == null || r.filePath == null || r.targetFilePath == null || r.targetFilePath.isBlank()) {
                continue;
            }
            if (r.status != ProcessedRecord.Status.SUCCESS) {
                continue;
            }
            batch.put(r.filePath, r.targetFilePath);
        }
        if (!batch.isEmpty()) {
            replacementRegistry.recordAll(batch);
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

    private FolderFilterResult filterFolderGroups(ProcessedRecordQuery query, FolderResolver resolver) {
        List<FolderGroup> allGroups = groupByFolder(logger.loadAllRecords(), resolver);
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

    private List<FolderGroup> groupByFolder(List<ProcessedRecord> records, FolderResolver resolver) {
        Map<String, FolderGroup> groups = new LinkedHashMap<>();
        for (ProcessedRecord r : records) {
            FolderInfo info = resolver.resolve(r.filePath);
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

    /**
     * 按单次请求缓存父目录规范化路径与配置根目录，避免对数万条处理记录逐条重复调用
     * {@code File.getCanonicalFile()} 造成昂贵的磁盘 I/O。
     */
    private final class FolderResolver {
        final Path monitorRoot;
        final Path outputRoot;
        private final List<Path> configuredRoots;
        private final Set<String> configuredRootKeys;
        private final Map<String, FolderInfo> byParent = new HashMap<>();

        FolderResolver() {
            this.monitorRoot = monitorRoot();
            String outDir = config == null ? null : config.getOutputDirectory();
            this.outputRoot = (outDir == null || outDir.isBlank()) ? null : canonicalOrNormalized(new File(outDir));
            List<Path> roots = new ArrayList<>(3);
            Set<String> rootKeys = new HashSet<>(4);
            if (config != null) {
                for (String rootDir : new String[] {
                    config.getOutputDirectory(),
                    config.getFailedDirectory(),
                    config.getPartialDirectory()
                }) {
                    if (rootDir != null && !rootDir.isBlank()) {
                        Path p = canonicalOrNormalized(new File(rootDir));
                        roots.add(p);
                        rootKeys.add(normalizeFolderKey(p.toString()));
                    }
                }
            }
            this.configuredRoots = roots;
            this.configuredRootKeys = rootKeys;
        }

        boolean isConfiguredRoot(String folderPath) {
            return folderPath != null && configuredRootKeys.contains(normalizeFolderKey(folderPath));
        }

        /** 判断目录是否为输出库下的单层艺人目录（outputRoot/<Artist>），其直接子文件属于无专辑散曲。 */
        boolean isSingleLevelOutputArtistDir(String folderPath) {
            if (outputRoot == null || folderPath == null || folderPath.isBlank()) {
                return false;
            }
            Path folder = canonicalOrNormalized(new File(folderPath));
            Path parent = folder.getParent();
            return parent != null && parent.equals(outputRoot);
        }

        FolderInfo resolve(String filePath) {
            if (filePath == null || filePath.isBlank()) {
                return new FolderInfo("", "", "", false, null);
            }
            File source = new File(filePath);
            File parent = source.getParentFile();
            if (parent == null) {
                String p = canonicalOrNormalized(source).toString();
                return new FolderInfo(p, p, p, false, null);
            }
            String parentKey = normalizeFolderKey(parent.getPath());
            FolderInfo cached = byParent.get(parentKey);
            if (cached != null) {
                return cached;
            }
            Path parentPath = canonicalOrNormalized(parent);
            FolderInfo computed;
            if (monitorRoot != null && parentPath.equals(monitorRoot)) {
                computed = new FolderInfo(monitorRoot.toString(), fileNameOf(monitorRoot), "", true, null);
            } else {
                String parentName = fileNameOf(parentPath);
                Path resolved = parentPath;
                String discName = null;
                if (FileSystemUtils.isDiscFolderName(parentName)) {
                    Path grand = parentPath.getParent();
                    if (grand != null && canAscendFromDiscFolder(parentPath, grand)) {
                        resolved = grand;
                        discName = parentName;
                    }
                }
                String folderPath = resolved.toString();
                String folderName = fileNameOf(resolved);
                String relativePath = folderPath;
                if (monitorRoot != null && resolved.startsWith(monitorRoot)) {
                    relativePath = monitorRoot.relativize(resolved).toString().replace('\\', '/');
                }
                computed = new FolderInfo(folderPath, folderName, relativePath, false, discName);
            }
            byParent.put(parentKey, computed);
            return computed;
        }

        /**
         * 判断碟片子目录（Disc 1 / CD 2 等）是否允许上升一级到专辑根目录：
         * - 位于监控目录内时，只要父目录不是监控根目录本身即可上升；
         * - 位于监控目录外（如重新识别 / 恢复目录 / 输出音乐库）时，同样允许上升到专辑目录，
         *   仅阻止上升到文件系统根或各配置根目录本身，避免多碟专辑被拆成 Disc 1 / Disc 2 两行。
         */
        private boolean canAscendFromDiscFolder(Path parentPath, Path grand) {
            if (grand.getParent() == null) {
                return false;
            }
            if (monitorRoot != null && parentPath.startsWith(monitorRoot)) {
                return !grand.equals(monitorRoot) && grand.startsWith(monitorRoot);
            }
            for (Path configuredRoot : configuredRoots) {
                if (grand.equals(configuredRoot)) {
                    return false;
                }
            }
            return true;
        }
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
