package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ProcessedRecord;
import com.lux032.musicautotagger.util.FileSystemUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    public ProcessedRecordService(MusicConfig config, ProcessedFileLogger logger,
                                  FolderAlbumCache folderAlbumCache, CoverArtService coverArtService,
                                  FileSystemUtils fileSystemUtils, Supplier<FileMonitorService> monitorSupplier) {
        this.config = config;
        this.logger = logger;
        this.folderAlbumCache = folderAlbumCache;
        this.coverArtService = coverArtService;
        this.fileSystemUtils = fileSystemUtils;
        this.monitorSupplier = monitorSupplier;
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
        Set<String> albums = new LinkedHashSet<>();
        for (ProcessedRecord r : targets) {
            enrich(r, monitorRoot);
            if (r.status == ProcessedRecord.Status.SUCCESS) success++;
            if (!Boolean.TRUE.equals(r.sourceExists)) missing++;
            else if (!Boolean.TRUE.equals(r.inMonitorDirectory)) outside++;
            else requeueable++;
            if (r.album != null && albums.size() < 6) albums.add(r.album);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", targets.size());
        result.put("requeueable", requeueable);
        result.put("sourceMissing", missing);
        result.put("outsideMonitor", outside);
        result.put("successCount", success);
        result.put("albums", albums);
        result.put("truncated", targets.size() >= MAX_BATCH);
        result.put("monitoringActive", isMonitoringActive());
        result.put("monitoringPaused", isMonitoringPaused());
        return result;
    }

    /** 仅删除记录。 */
    public Map<String, Object> forget(List<ProcessedRecord> targets) {
        int removed = logger.removeProcessedRecords(paths(targets));
        log.info("通过处理记录页删除了 {} 条记录", removed);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("removed", removed);
        return result;
    }

    /** 删除记录、清缓存并立即重新入队。 */
    public Map<String, Object> reidentify(List<ProcessedRecord> targets) {
        Path monitorRoot = monitorRoot();
        List<File> requeue = new ArrayList<>();
        Set<String> albumRoots = new LinkedHashSet<>();
        for (ProcessedRecord r : targets) {
            enrich(r, monitorRoot);
            if (Boolean.TRUE.equals(r.sourceExists) && Boolean.TRUE.equals(r.inMonitorDirectory)) {
                File file = new File(r.filePath);
                requeue.add(file);
                File root = fileSystemUtils != null ? fileSystemUtils.getAlbumRootDirectory(file) : file.getParentFile();
                if (root != null) albumRoots.add(root.getAbsolutePath());
                if (file.getParentFile() != null) albumRoots.add(file.getParentFile().getAbsolutePath());
            }
        }

        int removed = logger.removeProcessedRecords(paths(targets));

        // 文件夹级缓存里可能还留着上次（可能是错误的）识别结果，不清掉的话会被直接复用
        for (String root : albumRoots) {
            if (folderAlbumCache != null) folderAlbumCache.clearFolderCache(root);
            if (coverArtService != null) coverArtService.clearFolderCache(root);
        }

        FileMonitorService monitor = monitorSupplier != null ? monitorSupplier.get() : null;
        int queued = monitor != null ? monitor.requeueFiles(requeue) : 0;
        log.info("通过处理记录页重新识别：删除 {} 条记录，{} 个文件已入队", removed, queued);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("removed", removed);
        result.put("queued", queued);
        result.put("notQueued", Math.max(0, targets.size() - queued));
        result.put("monitoringPaused", isMonitoringPaused());
        return result;
    }

    public boolean isMonitoringActive() {
        FileMonitorService monitor = monitorSupplier != null ? monitorSupplier.get() : null;
        return monitor != null && monitor.isRunning();
    }

    public boolean isMonitoringPaused() {
        FileMonitorService monitor = monitorSupplier != null ? monitorSupplier.get() : null;
        return monitor != null && monitor.isPaused();
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
