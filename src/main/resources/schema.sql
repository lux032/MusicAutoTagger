-- ========================================
-- 音乐文件处理记录数据库表结构
-- ========================================

-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS music_demo 
DEFAULT CHARACTER SET utf8mb4 
DEFAULT COLLATE utf8mb4_unicode_ci;

USE music_demo;

-- 已处理文件记录表
CREATE TABLE IF NOT EXISTS processed_files (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    file_hash VARCHAR(64) NOT NULL COMMENT '文件哈希值（MD5，用于检测内容重复）',
    file_name VARCHAR(500) NOT NULL COMMENT '文件名',
    file_path VARCHAR(1000) NOT NULL UNIQUE COMMENT '文件完整路径（唯一键，确保同一路径只处理一次）',
    file_size BIGINT NOT NULL COMMENT '文件大小（字节）',
    processed_time DATETIME NOT NULL COMMENT '处理时间',
    recording_id VARCHAR(100) COMMENT 'MusicBrainz录音ID',
    artist VARCHAR(500) COMMENT '艺术家',
    title VARCHAR(500) COMMENT '曲目标题',
    album VARCHAR(500) COMMENT '专辑名称',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录更新时间',
    INDEX idx_file_hash (file_hash),
    INDEX idx_processed_time (processed_time),
    INDEX idx_recording_id (recording_id),
    INDEX idx_artist (artist(255)),
    INDEX idx_title (title(255)),
    INDEX idx_album (album(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='已处理音乐文件记录表（按文件路径去重，允许同一首歌在不同位置被处理）';

-- 创建视图：最近24小时处理的文件
CREATE OR REPLACE VIEW recent_processed_files AS
SELECT 
    id,
    file_name,
    artist,
    title,
    album,
    processed_time
FROM processed_files
WHERE processed_time >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
ORDER BY processed_time DESC;

-- 创建视图：处理统计
CREATE OR REPLACE VIEW processing_statistics AS
SELECT 
    COUNT(*) AS total_files,
    COUNT(DISTINCT artist) AS unique_artists,
    COUNT(DISTINCT album) AS unique_albums,
    SUM(file_size) AS total_size_bytes,
    MIN(processed_time) AS first_processed,
    MAX(processed_time) AS last_processed
FROM processed_files;

-- 封面缓存表
CREATE TABLE IF NOT EXISTS cover_art_cache (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    url_hash VARCHAR(64) NOT NULL UNIQUE COMMENT '封面URL的MD5哈希值',
    cover_url VARCHAR(2000) NOT NULL COMMENT '封面URL',
    cache_file_path VARCHAR(1000) NOT NULL COMMENT '缓存文件路径',
    file_size BIGINT NOT NULL COMMENT '文件大小（字节）',
    cached_time DATETIME NOT NULL COMMENT '缓存时间',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录更新时间',
    INDEX idx_url_hash (url_hash),
    INDEX idx_cached_time (cached_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='封面图片缓存表';

-- 创建视图：缓存统计
CREATE OR REPLACE VIEW cover_cache_statistics AS
SELECT
    COUNT(*) AS total_cached,
    SUM(file_size) AS total_size_bytes,
    MIN(cached_time) AS oldest_cache,
    MAX(cached_time) AS newest_cache
FROM cover_art_cache;