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
        
        // 最近处理的文件（最多10条）
        List<Map<String, String>> recentFiles = getRecentProcessedFiles(10);
        stats.put("recentFiles", recentFiles);
        
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
    
    private List<Map<String, String>> getRecentProcessedFiles(int limit) {
        List<Map<String, String>> files = new ArrayList<>();

        if ("mysql".equalsIgnoreCase(config.getDbType())) {
            // MySQL 模式：从数据库读取
            if (databaseService != null) {
                String sql = "SELECT file_path, artist, title, album, processed_time " +
                            "FROM processed_files ORDER BY processed_time DESC LIMIT ?";
                try (Connection conn = databaseService.getConnection();
                     PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, limit);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        while (rs.next()) {
                            Map<String, String> file = new HashMap<>();
                            file.put("path", rs.getString("file_path"));
                            file.put("artist", rs.getString("artist"));
                            file.put("title", rs.getString("title"));
                            file.put("album", rs.getString("album"));
                            if (rs.getTimestamp("processed_time") != null) {
                                file.put("time", rs.getTimestamp("processed_time")
                                        .toLocalDateTime().format(dateFormatter));
                            }
                            files.add(file);
                        }
                    }
                } catch (SQLException e) {
                    log.error("从数据库读取最近处理文件失败", e);
                }
            }
        } else {
            // 文件模式：从文本日志读取
            File logFile = new File(config.getProcessedFileLogPath());
            if (logFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
                    List<String> lines = new ArrayList<>();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                    }

                    // 倒序获取最后N条
                    int start = Math.max(0, lines.size() - limit);
                    for (int i = lines.size() - 1; i >= start; i--) {
                        String[] parts = lines.get(i).split("\\|");
                        if (parts.length >= 6) {
                            Map<String, String> file = new HashMap<>();
                            file.put("path", parts[0]);
                            file.put("artist", parts[2]);
                            file.put("title", parts[3]);
                            file.put("album", parts[4]);
                            file.put("time", parts[5]);
                            files.add(file);
                        }
                    }
                } catch (IOException e) {
                    log.error("读取日志文件失败", e);
                }
            }
        }

        return files;
    }
}
