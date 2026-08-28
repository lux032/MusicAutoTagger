package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.service.*;
import com.lux032.musicautotagger.util.I18nUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Dashboard 统计信息接口
 */
@Slf4j
public class DashboardServlet extends HttpServlet {

    private final ProcessedFileLogger processedLogger;
    private final CoverArtCache coverArtCache;
    private final FolderAlbumCache folderAlbumCache;
    private final MusicConfig config;
    private final DatabaseService databaseService;
    private final Gson gson;
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public DashboardServlet(ProcessedFileLogger processedLogger,
                           CoverArtCache coverArtCache,
                           FolderAlbumCache folderAlbumCache,
                           MusicConfig config,
                           DatabaseService databaseService) {
        this.processedLogger = processedLogger;
        this.coverArtCache = coverArtCache;
        this.folderAlbumCache = folderAlbumCache;
        this.config = config;
        this.databaseService = databaseService;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        
        try {
            Map<String, Object> data = collectStatistics();
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(gson.toJson(data));
        } catch (Exception e) {
            log.error("获取统计信息失败", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("dbType", config.getDbType());
            error.put("details", "请检查数据库配置是否正确");
            resp.getWriter().write(gson.toJson(error));
        }
    }
    
    private Map<String, Object> collectStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        // 基本配置信息
        Map<String, String> configInfo = new HashMap<>();
        configInfo.put("monitorDirectory", config.getMonitorDirectory());
        configInfo.put("outputDirectory", config.getOutputDirectory());
        configInfo.put("dbType", config.getDbType());
        configInfo.put("scanInterval", config.getScanIntervalSeconds() + I18nUtil.getMessage("unit.seconds", "秒"));
        stats.put("config", configInfo);
        
        // 处理统计
        Map<String, Object> processStats = processedLogger.getStatistics();
        stats.put("processed", processStats);
        
        // 封面缓存统计
        if (coverArtCache != null) {
            CoverArtCache.CacheStatistics coverStats = coverArtCache.getStatistics();
            Map<String, Object> coverInfo = new HashMap<>();
            coverInfo.put("totalCached", coverStats.totalCached);
            coverInfo.put("totalSizeMB", String.format("%.2f MB", coverStats.totalSizeBytes / 1024.0 / 1024.0));
            stats.put("coverCache", coverInfo);
        }
        
        // 文件夹专辑缓存统计
        if (folderAlbumCache != null) {
            FolderAlbumCache.CacheStatistics folderStats = folderAlbumCache.getStatistics();
            Map<String, Object> folderInfo = new HashMap<>();
            folderInfo.put("totalFolders", folderStats.getCachedFolders());
            folderInfo.put("pendingFiles", folderStats.getCollectingFolders());
            stats.put("folderCache", folderInfo);
        }
        
        // 最近成功整理的专辑（最多 12 张）
        stats.put("recentAlbums", getRecentAlbums(12));
        
        // 系统信息
        Map<String, String> systemInfo = new LinkedHashMap<>();
        systemInfo.put("osName", System.getProperty("os.name"));
        systemInfo.put("javaVersion", System.getProperty("java.version"));
        long usedMemory = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
        long maxMemory = Runtime.getRuntime().maxMemory() / 1024 / 1024;
        systemInfo.put("memory", String.format("%dMB / %dMB", usedMemory, maxMemory));
        stats.put("system", systemInfo);
        
        return stats;
    }
    
    /**
     * 这些 recording_id 不是真正的 MusicBrainz 录音 ID，而是失败/特殊流程的占位值。
     * 这类行的 artist 字段存的是「处理异常: XXX」这种诊断文本，不能当成成果展示。
     */
    private static final Set<String> NON_MB_RECORDING_IDS = Set.of(
        "FAILED", "UNKNOWN", "WRITE_FAILED", "EXCEPTION", "CUE_SPLIT", "REVIEW_REJECTED", "ONLINE_SEARCH");

    private static boolean isSuccessfulRecord(String recordingId, String album) {
        if (album == null || album.isBlank() || "Unknown Album".equalsIgnoreCase(album.trim())) {
            return false;
        }
        return recordingId != null && !recordingId.isBlank()
            && !NON_MB_RECORDING_IDS.contains(recordingId.trim());
    }

    /**
     * 最近成功整理的专辑。
     *
     * 两个语义上的考虑：
     *  1. 只算成功识别的记录 —— 失败行的 artist 存的是错误描述，混在成果里既难看也误导；
     *  2. 按专辑聚合而不是按文件 —— 封面本来就是专辑级的，
     *     按文件列会让同一张专辑的 12 首歌铺出 12 个一模一样的封面。
     */
    private List<Map<String, Object>> getRecentAlbums(int limit) {
        if ("mysql".equalsIgnoreCase(config.getDbType())) {
            return getRecentAlbumsFromDb(limit);
        }
        return getRecentAlbumsFromLog(limit);
    }

    private List<Map<String, Object>> getRecentAlbumsFromDb(int limit) {
        List<Map<String, Object>> albums = new ArrayList<>();
        if (databaseService == null) {
            return albums;
        }

        // release_group_id 是后加列，老库可能还没补上
        boolean withRgid = processedLogger != null && processedLogger.isReleaseGroupIdColumnAvailable();
        String rgidSelect = withRgid ? "MAX(release_group_id) AS rgid, " : "";
        String placeholders = NON_MB_RECORDING_IDS.stream()
            .map(x -> "?").collect(java.util.stream.Collectors.joining(", "));

        String sql = "SELECT album, " + rgidSelect
            + "MIN(artist) AS one_artist, COUNT(DISTINCT artist) AS artist_count, "
            + "COUNT(*) AS track_count, MAX(processed_time) AS last_time, MIN(file_path) AS sample_path "
            + "FROM processed_files "
            + "WHERE album IS NOT NULL AND album <> '' AND album <> 'Unknown Album' "
            + "AND recording_id IS NOT NULL AND recording_id <> '' "
            + "AND recording_id NOT IN (" + placeholders + ") "
            + "GROUP BY album ORDER BY last_time DESC LIMIT ?";

        try (Connection conn = databaseService.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            int index = 1;
            for (String sentinel : NON_MB_RECORDING_IDS) {
                pstmt.setString(index++, sentinel);
            }
            pstmt.setInt(index, limit);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> album = new HashMap<>();
                    album.put("album", rs.getString("album"));
                    album.put("artist", rs.getInt("artist_count") == 1
                        ? rs.getString("one_artist") : "Various Artists");
                    album.put("trackCount", rs.getInt("track_count"));
                    album.put("path", rs.getString("sample_path"));
                    if (withRgid) {
                        album.put("releaseGroupId", rs.getString("rgid"));
                    }
                    if (rs.getTimestamp("last_time") != null) {
                        album.put("time", rs.getTimestamp("last_time")
                            .toLocalDateTime().format(dateFormatter));
                    }
                    albums.add(album);
                }
            }
        } catch (SQLException e) {
            log.error("从数据库读取最近专辑失败", e);
        }
        return albums;
    }

    private List<Map<String, Object>> getRecentAlbumsFromLog(int limit) {
        File logFile = new File(config.getProcessedFileLogPath());
        if (!logFile.exists()) {
            return new ArrayList<>();
        }

        // album -> 聚合结果（LinkedHashMap 保持首次出现顺序，排序在后面做）
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        Map<String, Set<String>> artistsByAlbum = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 格式: filePath|recordingId|artist|title|album|time[|releaseGroupId]
                String[] parts = line.split("\\|", -1);
                if (parts.length < 6 || !isSuccessfulRecord(parts[1], parts[4])) {
                    continue;
                }
                String album = parts[4];
                String time = parts[5];

                artistsByAlbum.computeIfAbsent(album, k -> new HashSet<>()).add(parts[2]);

                Map<String, Object> entry = grouped.computeIfAbsent(album, k -> {
                    Map<String, Object> created = new HashMap<>();
                    created.put("album", k);
                    created.put("trackCount", 0);
                    created.put("path", parts[0]);
                    return created;
                });
                entry.put("trackCount", (Integer) entry.get("trackCount") + 1);
                // 时间是 yyyy-MM-dd HH:mm:ss，字典序即时间序，直接比字符串就行
                String known = (String) entry.get("time");
                if (known == null || time.compareTo(known) > 0) {
                    entry.put("time", time);
                }
                if (parts.length >= 7 && !parts[6].isBlank()) {
                    entry.put("releaseGroupId", parts[6]);
                }
            }
        } catch (IOException e) {
            log.error("读取日志文件失败", e);
            return new ArrayList<>();
        }

        for (Map.Entry<String, Map<String, Object>> entry : grouped.entrySet()) {
            Set<String> artists = artistsByAlbum.get(entry.getKey());
            entry.getValue().put("artist", artists != null && artists.size() == 1
                ? artists.iterator().next() : "Various Artists");
        }

        List<Map<String, Object>> albums = new ArrayList<>(grouped.values());
        albums.sort((a, b) -> String.valueOf(b.get("time")).compareTo(String.valueOf(a.get("time"))));
        return albums.size() > limit ? new ArrayList<>(albums.subList(0, limit)) : albums;
    }
}
