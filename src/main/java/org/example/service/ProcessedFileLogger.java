package org.example.service;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.io.FileInputStream;

/**
 * 已处理文件日志服务 - MySQL版本
 * 用于记录和检查文件是否已被处理,防止重复整理
 */
@Slf4j
public class ProcessedFileLogger {
    
    private final DatabaseService databaseService;
    private final DateTimeFormatter dateFormatter;
    
    /**
     * 构造函数 - 依赖注入DatabaseService
     * @param databaseService 数据库服务
     */
    public ProcessedFileLogger(DatabaseService databaseService) {
        this.databaseService = databaseService;
        this.dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        
        log.info("已处理文件日志服务初始化完成 - 使用共享数据库连接池");
    }
    
    
    /**
     * 检查文件是否已被处理过
     * 使用文件完整路径作为唯一标识,允许同一首歌在不同位置被分别处理
     * @param file 要检查的文件
     * @return true=已处理过, false=未处理
     */
    public boolean isFileProcessed(File file) {
        try {
            String filePath = file.getAbsolutePath();
            
            String sql = "SELECT recording_id, artist, title, album, processed_time FROM processed_files WHERE file_path = ?";
            
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                
                pstmt.setString(1, filePath);
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        String recordingId = rs.getString("recording_id");
                        String artist = rs.getString("artist");
                        String title = rs.getString("title");
                        String processedTime = rs.getTimestamp("processed_time").toLocalDateTime()
                            .format(dateFormatter);
                        
                        log.info("文件已处理过: {} (处理时间: {}, 艺术家: {}, 标题: {})",
                            file.getName(), processedTime, artist, title);
                        return true;
                    }
                }
            }
            
            return false;
            
        } catch (SQLException e) {
            log.error("数据库故障,无法检查文件处理状态: {}", file.getName(), e);
            // 关键修复: 数据库故障时抛出异常,而不是返回false导致重复处理
            throw new RuntimeException("数据库不可用,暂停文件处理", e);
        }
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
        try {
            String filePath = file.getAbsolutePath();
            String fileHash = calculateFileHash(file);
            LocalDateTime now = LocalDateTime.now();
            
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
                
                log.info("已记录处理历史: {} - {} - {} (路径: {})", artist, title, album, filePath);
            }
            
        } catch (IOException | SQLException e) {
            log.error("记录文件处理历史失败: {}", file.getName(), e);
        }
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
        
        try (Connection conn = databaseService.getConnection()) {
            // 总处理数量
            String countSQL = "SELECT COUNT(*) as total FROM processed_files";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(countSQL)) {
                if (rs.next()) {
                    stats.put("totalProcessed", rs.getLong("total"));
                }
            }
            
            // 最近24小时处理数量
            String recentSQL = "SELECT COUNT(*) as recent FROM processed_files WHERE processed_time >= DATE_SUB(NOW(), INTERVAL 24 HOUR)";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(recentSQL)) {
                if (rs.next()) {
                    stats.put("processedLast24Hours", rs.getLong("recent"));
                }
            }
            
            stats.put("databaseType", "MySQL");
            
        } catch (SQLException e) {
            log.error("获取统计信息失败", e);
        }
        
        return stats;
    }
    
    /**
     * 清理旧的日志记录
     * @param daysToKeep 保留最近多少天的记录
     */
    public void cleanupOldRecords(int daysToKeep) {
        String sql = "DELETE FROM processed_files WHERE processed_time < DATE_SUB(NOW(), INTERVAL ? DAY)";
        
        try (Connection conn = databaseService.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, daysToKeep);
            int deletedCount = pstmt.executeUpdate();
            
            if (deletedCount > 0) {
                log.info("清理了 {} 条过期记录 (保留{}天内)", deletedCount, daysToKeep);
            }
            
        } catch (SQLException e) {
            log.error("清理旧记录失败", e);
        }
    }
    
    /**
     * 关闭服务
     * 注意: 不再关闭数据源,因为数据源由DatabaseService统一管理
     */
    public void close() {
        log.info("已处理文件日志服务已关闭");
    }
}