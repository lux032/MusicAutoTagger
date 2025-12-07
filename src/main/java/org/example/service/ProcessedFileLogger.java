package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.util.I18nUtil;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import org.example.config.MusicConfig;

/**
 * 已处理文件日志服务 - 支持 MySQL 和 文件模式
 * 用于记录和检查文件是否已被处理,防止重复整理
 */
@Slf4j
public class ProcessedFileLogger {

    private final DatabaseService databaseService;
    private final MusicConfig config;
    private final DateTimeFormatter dateFormatter;
    private final boolean isDbMode;
    // 关键修复：添加文件写入锁，解决并发写入日志文件的线程安全问题
    private final Object fileWriteLock = new Object();

    /**
     * 构造函数
     * @param databaseService 数据库服务 (仅在 dbMode 为 mysql 时需要)
     */
    public ProcessedFileLogger(MusicConfig config, DatabaseService databaseService) {
        this.config = config;
        this.databaseService = databaseService;
        this.dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        this.isDbMode = "mysql".equalsIgnoreCase(config.getDbType());

        if (isDbMode) {
            log.info(I18nUtil.getMessage("logger.init.mysql"));
        } else {
            log.info(I18nUtil.getMessage("logger.init.file"), config.getProcessedFileLogPath());
            initLogFile();
        }
    }

    private void initLogFile() {
        File logFile = new File(config.getProcessedFileLogPath());
        if (!logFile.exists()) {
            try {
                if (logFile.getParentFile() != null) {
                    logFile.getParentFile().mkdirs();
                }
                logFile.createNewFile();
            } catch (IOException e) {
                log.error(I18nUtil.getMessage("logger.create.log.file.failed"), logFile.getAbsolutePath(), e);
            }
        }
    }

    /**
     * 检查文件是否已被处理过
     * 使用文件完整路径作为唯一标识,允许同一首歌在不同位置被分别处理
     * @param file 要检查的文件
     * @return true=已处理过, false=未处理
     */
    public boolean isFileProcessed(File file) {
        String filePath = file.getAbsolutePath();

        if (isDbMode) {
            try {
                String sql = "SELECT recording_id, artist, title, album, processed_time FROM processed_files WHERE file_path = ?";

                try (Connection conn = databaseService.getConnection();
                     PreparedStatement pstmt = conn.prepareStatement(sql)) {

                    pstmt.setString(1, filePath);

                    try (ResultSet rs = pstmt.executeQuery()) {
                        if (rs.next()) {
                            String artist = rs.getString("artist");
                            String title = rs.getString("title");
                            String processedTime = rs.getTimestamp("processed_time").toLocalDateTime().format(dateFormatter);

                            log.debug(I18nUtil.getMessage("logger.file.already.processed.db"),
                                    file.getName(), processedTime, artist, title);
                            return true;
                        }
                    }
                }
                return false;
            } catch (SQLException e) {
                log.error(I18nUtil.getMessage("db.unavailable") + ": {}", e.getMessage());
                throw new RuntimeException("数据库不可用", e);
            }
        } else {
            // 文件模式：扫描 CSV
            return checkFileInLog(filePath);
        }
    }

    private boolean checkFileInLog(String filePath) {
        File logFile = new File(config.getProcessedFileLogPath());
        if (!logFile.exists()) return false;

        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 简单格式: filePath|recordingId|...
                if (line.startsWith(filePath + "|")) {
                    log.debug(I18nUtil.getMessage("logger.file.already.processed.log"), filePath);
                    return true;
                }
            }
        } catch (IOException e) {
            log.error(I18nUtil.getMessage("logger.read.log.failed"), e);
        }
        return false;
    }

    /**
     * 记录文件已处理
     * 使用文件完整路径作为唯一标识,允许同一首歌在不同位置被分别处理
     * @param file 已处理的文件
     * @param recordingId MusicBrainz录音ID
     * @param artist 艺术家
     * @param title 标题
     * @param album 专辑
     */
    public void markFileAsProcessed(File file, String recordingId, String artist, String title, String album) {
        String filePath = file.getAbsolutePath();
        LocalDateTime now = LocalDateTime.now();

        if (isDbMode) {
            try {
                String fileHash = calculateFileHash(file);
                String sql = "INSERT INTO processed_files " +
                        "(file_hash, file_name, file_path, file_size, processed_time, recording_id, artist, title, album) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE " +
                        "file_hash = VALUES(file_hash), " +
                        "file_name = VALUES(file_name), " +
                        "file_size = VALUES(file_size), " +
                        "processed_time = VALUES(processed_time), " +
                        "recording_id = VALUES(recording_id), " +
                        "artist = VALUES(artist), " +
                        "title = VALUES(title), " +
                        "album = VALUES(album)";

                try (Connection conn = databaseService.getConnection();
                     PreparedStatement pstmt = conn.prepareStatement(sql)) {

                    pstmt.setString(1, fileHash);
                    pstmt.setString(2, file.getName());
                    pstmt.setString(3, filePath);
                    pstmt.setLong(4, file.length());
                    pstmt.setTimestamp(5, Timestamp.valueOf(now));
                    pstmt.setString(6, recordingId);
                    pstmt.setString(7, artist);
                    pstmt.setString(8, title);
                    pstmt.setString(9, album);

                    pstmt.executeUpdate();
                }
            } catch (IOException | SQLException e) {
                log.error(I18nUtil.getMessage("logger.db.record.failed"), e);
            }
        } else {
            // 文件模式: 追加写入（使用同步锁保证线程安全）
            synchronized (fileWriteLock) {
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(config.getProcessedFileLogPath(), true))) {
                    String timeStr = now.format(dateFormatter);
                    // 格式: filePath|recordingId|artist|title|album|time
                    String line = String.format("%s|%s|%s|%s|%s|%s",
                            filePath, recordingId, artist, title, album, timeStr);
                    writer.write(line);
                    writer.newLine();
                } catch (IOException e) {
                    log.error(I18nUtil.getMessage("logger.write.log.failed"), e);
                }
            }
        }

        log.info(I18nUtil.getMessage("logger.history.recorded"), artist, title, isDbMode ? I18nUtil.getMessage("logger.db.mode") : I18nUtil.getMessage("logger.file.mode"));
    }

    /**
     * 计算文件MD5哈希值
     */
    private String calculateFileHash(File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");

            // 对于大文件,只读取前1MB和最后1MB来计算哈希(性能优化)
            long fileSize = file.length();
            int sampleSize = 1024 * 1024; // 1MB

            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[sampleSize];

                // 读取前1MB
                int bytesRead = fis.read(buffer);
                if (bytesRead > 0) {
                    md.update(buffer, 0, bytesRead);
                }

                // 如果文件大于2MB,跳到末尾读取最后1MB
                if (fileSize > sampleSize * 2) {
                    fis.getChannel().position(fileSize - sampleSize);
                    bytesRead = fis.read(buffer);
                    if (bytesRead > 0) {
                        md.update(buffer, 0, bytesRead);
                    }
                }
            }

            // 同时考虑文件大小和路径名(避免同名但内容不同的文件)
            md.update(String.valueOf(fileSize).getBytes());
            md.update(file.getName().getBytes());

            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();

        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5算法不可用", e);
        }
    }

    /**
     * 获取处理记录统计
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();

        if (isDbMode) {
            try (Connection conn = databaseService.getConnection()) {
                // ... (原有MySQL统计逻辑保持不变)
                String countSQL = "SELECT COUNT(*) as total FROM processed_files";
                try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(countSQL)) {
                    if (rs.next()) stats.put("totalProcessed", rs.getLong("total"));
                }
                stats.put("databaseType", "MySQL");
            } catch (SQLException e) {
                log.error("获取统计信息失败", e);
            }
        } else {
            stats.put("databaseType", "File");
            // 简单统计行数
            try (BufferedReader reader = new BufferedReader(new FileReader(config.getProcessedFileLogPath()))) {
                long lines = reader.lines().count();
                stats.put("totalProcessed", lines);
            } catch (IOException e) {
                stats.put("totalProcessed", 0);
            }
        }

        return stats;
    }

    /**
     * 清理旧的日志记录
     * @param daysToKeep 保留最近多少天的记录
     */
    public void cleanupOldRecords(int daysToKeep) {
        if (isDbMode) {
            String sql = "DELETE FROM processed_files WHERE processed_time < DATE_SUB(NOW(), INTERVAL ? DAY)";
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, daysToKeep);
                int deletedCount = pstmt.executeUpdate();
                if (deletedCount > 0) {
                    log.info(I18nUtil.getMessage("logger.cleanup.old.records"), deletedCount);
                }
            } catch (SQLException e) {
                log.error("清理旧记录失败", e);
            }
        } else {
            // 文件模式暂不支持清理 (或以后实现)
            log.info(I18nUtil.getMessage("logger.cleanup.not.supported"));
        }
    }

    /**
     * 关闭服务
     * 注意: 不再关闭数据源,因为数据源由DatabaseService统一管理
     */
    public void close() {
        log.info(I18nUtil.getMessage("logger.service.closed"));
    }
}