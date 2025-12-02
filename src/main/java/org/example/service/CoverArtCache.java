package org.example.service;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;

/**
 * 封面图片缓存服务
 * 避免同一专辑的封面重复下载和压缩
 */
@Slf4j
public class CoverArtCache {
    
    private final DatabaseService databaseService;
    private final String cacheDirectory;
    
    /**
     * 构造函数 - 依赖注入DatabaseService
     * @param databaseService 数据库服务
     * @param cacheDirectory 缓存目录路径
     */
    public CoverArtCache(DatabaseService databaseService, String cacheDirectory) {
        this.databaseService = databaseService;
        this.cacheDirectory = cacheDirectory;
        
        // 确保缓存目录存在
        File cacheDir = new File(cacheDirectory);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
            log.info("创建封面缓存目录: {}", cacheDirectory);
        }
        
        log.info("封面缓存服务初始化完成 - 使用共享数据库连接池");
    }
    
    /**
     * 检查封面是否已缓存
     * @param coverArtUrl 封面URL
     * @return 缓存的封面数据,如果未缓存则返回null
     */
    public byte[] getCachedCover(String coverArtUrl) {
        if (coverArtUrl == null || coverArtUrl.isEmpty()) {
            return null;
        }
        
        try {
            String urlHash = calculateHash(coverArtUrl);
            
            // 如果没有数据库服务，直接检查文件系统
            if (databaseService == null) {
                String cacheFileName = urlHash + ".jpg";
                Path cacheFilePath = Paths.get(cacheDirectory, cacheFileName);
                File cacheFile = cacheFilePath.toFile();
                if (cacheFile.exists()) {
                    log.info("使用缓存的封面(File): {}", coverArtUrl);
                    return Files.readAllBytes(cacheFilePath);
                }
                return null;
            }

            String sql = "SELECT cache_file_path FROM cover_art_cache WHERE url_hash = ?";
            
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                
                pstmt.setString(1, urlHash);
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        String cacheFilePath = rs.getString("cache_file_path");
                        File cacheFile = new File(cacheFilePath);
                        
                        if (cacheFile.exists()) {
                            log.info("使用缓存的封面(DB): {}", coverArtUrl);
                            return Files.readAllBytes(cacheFile.toPath());
                        } else {
                            log.warn("缓存文件不存在: {}", cacheFilePath);
                            // 删除无效的缓存记录
                            deleteCacheRecord(urlHash);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("检查封面缓存失败", e);
        }
        
        return null;
    }
    
    /**
     * 保存封面到缓存
     * @param coverArtUrl 封面URL
     * @param coverData 封面数据(已压缩)
     * @return 是否保存成功
     */
    public boolean cacheCover(String coverArtUrl, byte[] coverData) {
        if (coverArtUrl == null || coverArtUrl.isEmpty() || coverData == null || coverData.length == 0) {
            return false;
        }
        
        try {
            String urlHash = calculateHash(coverArtUrl);
            
            // 生成缓存文件路径: cacheDir/urlHash.jpg
            String cacheFileName = urlHash + ".jpg";
            Path cacheFilePath = Paths.get(cacheDirectory, cacheFileName);
            
            // 保存文件
            Files.write(cacheFilePath, coverData);
            log.info("封面已缓存到文件: {}", cacheFilePath);
            
            // 如果没有数据库服务，到此结束
            if (databaseService == null) {
                return true;
            }

            // 保存数据库记录
            String sql = "INSERT INTO cover_art_cache (url_hash, cover_url, cache_file_path, file_size, cached_time) " +
                        "VALUES (?, ?, ?, ?, NOW()) " +
                        "ON DUPLICATE KEY UPDATE " +
                        "cache_file_path = VALUES(cache_file_path), " +
                        "file_size = VALUES(file_size), " +
                        "cached_time = VALUES(cached_time)";
            
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                
                pstmt.setString(1, urlHash);
                pstmt.setString(2, coverArtUrl);
                pstmt.setString(3, cacheFilePath.toString());
                pstmt.setLong(4, coverData.length);
                
                pstmt.executeUpdate();
                log.info("封面缓存记录已保存到数据库");
                return true;
            }
            
        } catch (Exception e) {
            log.error("保存封面缓存失败", e);
            return false;
        }
    }
    
    /**
     * 删除缓存记录
     */
    private void deleteCacheRecord(String urlHash) {
        try {
            String sql = "DELETE FROM cover_art_cache WHERE url_hash = ?";
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, urlHash);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("删除缓存记录失败", e);
        }
    }
    
    /**
     * 计算URL的哈希值
     */
    private String calculateHash(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(url.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5算法不可用", e);
        }
    }
    
    /**
     * 清理旧的缓存文件
     * @param daysToKeep 保留天数
     */
    public void cleanupOldCache(int daysToKeep) {
        String sql = "SELECT url_hash, cache_file_path FROM cover_art_cache " +
                    "WHERE cached_time < DATE_SUB(NOW(), INTERVAL ? DAY)";
        
        try (Connection conn = databaseService.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, daysToKeep);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                int deletedCount = 0;
                while (rs.next()) {
                    String urlHash = rs.getString("url_hash");
                    String cacheFilePath = rs.getString("cache_file_path");
                    
                    // 删除文件
                    File cacheFile = new File(cacheFilePath);
                    if (cacheFile.exists()) {
                        cacheFile.delete();
                    }
                    
                    // 删除数据库记录
                    deleteCacheRecord(urlHash);
                    deletedCount++;
                }
                
                if (deletedCount > 0) {
                    log.info("清理了 {} 个过期的封面缓存", deletedCount);
                }
            }
            
        } catch (SQLException e) {
            log.error("清理旧缓存失败", e);
        }
    }
    
    /**
     * 获取缓存统计信息
     */
    public CacheStatistics getStatistics() {
        CacheStatistics stats = new CacheStatistics();
        
        try (Connection conn = databaseService.getConnection()) {
            // 总缓存数量
            String countSQL = "SELECT COUNT(*) as total, SUM(file_size) as total_size FROM cover_art_cache";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(countSQL)) {
                if (rs.next()) {
                    stats.totalCached = rs.getLong("total");
                    stats.totalSizeBytes = rs.getLong("total_size");
                }
            }
            
        } catch (SQLException e) {
            log.error("获取缓存统计信息失败", e);
        }
        
        return stats;
    }
    
    /**
     * 关闭服务
     * 注意: 不再关闭数据源,因为数据源由DatabaseService统一管理
     */
    public void close() {
        log.info("封面缓存服务已关闭");
    }
    
    /**
     * 缓存统计信息
     */
    public static class CacheStatistics {
        public long totalCached;
        public long totalSizeBytes;
        
        @Override
        public String toString() {
            return String.format("缓存统计: %d 个封面, 总大小: %.2f MB",
                totalCached, totalSizeBytes / 1024.0 / 1024.0);
        }
    }
}