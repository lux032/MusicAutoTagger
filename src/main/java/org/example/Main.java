package org.example;

import lombok.extern.slf4j.Slf4j;
import org.example.config.MusicConfig;
import org.example.service.*;

import java.io.File;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.tag.images.Artwork;
import java.nio.file.Files;
import java.io.IOException;

/**
 * 音乐文件自动标签系统主程序
 * 功能：
 * 1. 监控指定目录的音乐文件下载
 * 2. 使用音频指纹识别技术识别音乐
 * 3. 通过 MusicBrainz 获取音乐元数据
 * 4. 自动更新音频文件的标签信息
 */
@Slf4j
public class Main {
    
    private static DatabaseService databaseService;
    private static FileMonitorService fileMonitor;
    private static AudioFingerprintService fingerprintService;
    private static MusicBrainzClient musicBrainzClient;
    private static TagWriterService tagWriter;
    private static LyricsService lyricsService;
    private static ProcessedFileLogger processedLogger;
    private static CoverArtCache coverArtCache;
    private static MusicConfig config;
    
    // 文件夹级别的封面缓存: 文件夹路径 -> 封面数据
    private static java.util.Map<String, byte[]> folderCoverCache = new java.util.concurrent.ConcurrentHashMap<>();
    
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("音乐文件自动标签系统");
        System.out.println("========================================");
        
        try {
            // 1. 加载配置
            config = MusicConfig.getInstance();
            if (!config.isValid()) {
                log.error("配置无效，程序退出");
                return;
            }
            
            log.info("配置加载成功");
            log.info("监控目录: {}", config.getMonitorDirectory());
            log.info("输出目录: {}", config.getOutputDirectory());
            log.info("扫描间隔: {} 秒", config.getScanIntervalSeconds());
            
            // 2. 初始化服务
            initializeServices();
            
            // 3. 检查依赖工具
            if (!fingerprintService.isFpcalcAvailable()) {
                log.warn("========================================");
                log.warn("警告: fpcalc 工具未安装或不在 PATH 中");
                log.warn("音频指纹识别功能将无法使用");
                log.warn("请安装 Chromaprint: https://acoustid.org/chromaprint");
                log.warn("========================================");
            }
            
            // 4. 启动文件监控
            startMonitoring();
            
            // 5. 等待用户输入以停止程序
            System.out.println("\n系统正在运行中...");
            System.out.println("按回车键停止程序");
            System.in.read();
            
            // 6. 优雅关闭
            shutdown();
            
        } catch (Exception e) {
            log.error("程序运行出错", e);
        }
    }
    
    /**
     * 初始化所有服务
     */
    private static void initializeServices() throws IOException {
        log.info("初始化服务...");
        
        // Level 1: 初始化数据库服务 (如果配置为 MySQL)
        if ("mysql".equalsIgnoreCase(config.getDbType())) {
            log.info("初始化数据库服务...");
            databaseService = new DatabaseService(config);
        } else {
            log.info("使用文件模式，跳过数据库初始化");
        }
        
        // Level 2: 初始化依赖数据库的服务
        log.info("初始化日志服务...");
        processedLogger = new ProcessedFileLogger(config, databaseService);
        
        String cacheDir = config.getCoverArtCacheDirectory();
        if (cacheDir == null || cacheDir.isEmpty()) {
            cacheDir = config.getOutputDirectory() + "/.cover_cache";
        }
        // 在文件模式下, databaseService 为 null, CoverArtCache 会自动降级为文件系统缓存
        coverArtCache = new CoverArtCache(databaseService, cacheDir);
        
        // Level 2: 初始化其他服务
        log.info("初始化其他服务...");
        fingerprintService = new AudioFingerprintService(config);
        musicBrainzClient = new MusicBrainzClient(config);
        lyricsService = new LyricsService(config);
        tagWriter = new TagWriterService(config);
        
        // Level 3: 初始化文件监控服务
        log.info("初始化文件监控服务...");
        fileMonitor = new FileMonitorService(config, processedLogger);
        fileMonitor.setFileReadyCallback(Main::processAudioFile);
        
        log.info("所有服务初始化完成");
    }
    
    /**
     * 启动文件监控
     */
    private static void startMonitoring() {
        log.info("启动文件监控服务...");
        fileMonitor.start();
    }
    
    /**
     * 处理音频文件的核心逻辑
     */
    private static void processAudioFile(File audioFile) {
        log.info("========================================");
        log.info("处理音频文件: {}", audioFile.getName());
        
        try {
            // 0. 检查文件是否已处理过
            if (processedLogger.isFileProcessed(audioFile)) {
                log.info("文件已处理过，跳过: {}", audioFile.getName());
                return;
            }
            
            // 0.3. 检查文件夹是否有临时文件(下载未完成)
            if (hasTempFilesInFolder(audioFile)) {
                log.warn("检测到文件夹中有临时文件,可能正在下载中,跳过处理: {}", audioFile.getParentFile().getName());
                return;
            }
            
            // 0.5. 统计文件夹内音乐文件数量（用于判断是否为单曲/EP/专辑）
            int musicFilesInFolder = countMusicFilesInFolder(audioFile);
            
            // 1. 使用音频指纹识别
            log.info("正在进行音频指纹识别...");
            AudioFingerprintService.AcoustIdResult acoustIdResult =
                fingerprintService.identifyAudioFile(audioFile);
            
            if (acoustIdResult.getRecordings() == null || acoustIdResult.getRecordings().isEmpty()) {
                log.warn("无法通过音频指纹识别文件: {}", audioFile.getName());
                return;
            }
            
            // 2. 获取最佳匹配的录音信息
            AudioFingerprintService.RecordingInfo bestMatch = acoustIdResult.getRecordings().get(0);
            log.info("识别成功: {} - {}", bestMatch.getArtist(), bestMatch.getTitle());
            
            // 3. 通过 MusicBrainz 获取详细元数据（包含封面 URL）
            // 传入文件夹音乐文件数量，用于优化单曲匹配
            log.info("正在获取详细元数据...");
            MusicBrainzClient.MusicMetadata detailedMetadata =
                musicBrainzClient.getRecordingById(bestMatch.getRecordingId(), musicFilesInFolder);
            
            if (detailedMetadata == null) {
                log.warn("无法获取详细元数据");
                detailedMetadata = convertToMusicMetadata(bestMatch);
            }
            
            // 4. 获取封面图片(多层降级策略)
            byte[] coverArtData = getCoverArtWithFallback(audioFile, detailedMetadata);
            
            if (coverArtData != null && coverArtData.length > 0) {
                log.info("✓ 成功获取封面图片");
            } else {
                log.info("未找到封面图片");
            }
            
            // 4.5 获取歌词 (LrcLib)
            log.info("正在获取歌词...");
            // 使用详细元数据的信息查询歌词，如果没有则回退到指纹识别信息
            String searchTitle = detailedMetadata.getTitle();
            String searchArtist = detailedMetadata.getArtist();
            String searchAlbum = detailedMetadata.getAlbum();
            
            // 计算音频时长(秒)
            int duration = 0;
            if (acoustIdResult != null && !acoustIdResult.getRecordings().isEmpty()) {
                // 如果有指纹信息，可以用指纹的时长，或者直接读取文件时长(这里简化用指纹时长，通常够用)
                // 实际上 AudioFingerprintService 里的 duration 是 int
                // 但这里我们没有直接访问 fingerprint 对象。
                // 暂时传 0 让 API 自己模糊匹配，或者如果能获取到文件时长更好。
                // 为了简单起见，这里先尝试不传时长，或者如果您有办法获取文件时长
            }

            String lyrics = lyricsService.getLyrics(searchTitle, searchArtist, searchAlbum, 0);
            if (lyrics != null && !lyrics.isEmpty()) {
                detailedMetadata.setLyrics(lyrics);
            } else {
                log.info("未找到歌词");
            }

            // 5. 处理文件（复制并更新标签）
            log.info("正在处理文件...");
            TagWriterService.MusicMetadata tagMetadata = convertToTagMetadata(detailedMetadata);
            boolean success = tagWriter.processFile(audioFile, tagMetadata, coverArtData);
            
            if (success) {
                log.info("✓ 文件处理成功: {}", audioFile.getName());
                
                // 记录文件已处理
                processedLogger.markFileAsProcessed(
                    audioFile,
                    detailedMetadata.getRecordingId(),
                    detailedMetadata.getArtist(),
                    detailedMetadata.getTitle(),
                    detailedMetadata.getAlbum()
                );
            } else {
                log.error("✗ 文件处理失败: {}", audioFile.getName());
            }
            
        } catch (Exception e) {
            log.error("处理文件失败: {}", audioFile.getName(), e);
        }
        
        log.info("========================================");
    }
    
    /**
     * 获取封面图片(多层降级策略 + 文件夹级别缓存)
     * 优先级:
     * 0. 检查同文件夹是否已有其他文件获取过封面
     * 1. 尝试从网络下载(使用缓存)
     * 2. 如果下载失败,检查音频文件是否自带封面
     * 3. 如果没有自带封面,在音频文件所在目录查找cover图片
     */
    private static byte[] getCoverArtWithFallback(File audioFile, MusicBrainzClient.MusicMetadata metadata) {
        byte[] coverArtData = null;
        String folderPath = audioFile.getParentFile().getAbsolutePath();
        
        // 策略0: 检查文件夹级别缓存
        coverArtData = folderCoverCache.get(folderPath);
        if (coverArtData != null && coverArtData.length > 0) {
            log.info("策略0: 使用同文件夹已获取的封面");
            return coverArtData;
        }
        
        // 策略1: 尝试从网络下载
        if (metadata.getCoverArtUrl() != null) {
            log.info("策略1: 尝试从网络下载封面");
            coverArtData = downloadCoverFromNetwork(metadata.getCoverArtUrl());
            
            if (coverArtData != null && coverArtData.length > 0) {
                log.info("✓ 成功从网络下载封面,缓存到文件夹级别");
                folderCoverCache.put(folderPath, coverArtData);
                return coverArtData;
            }
            log.warn("✗ 网络下载失败,尝试降级策略");
        } else {
            log.warn("未获取到封面URL,跳过网络下载,尝试降级策略");
        }
        
        // 策略2: 检查音频文件是否自带封面
        log.info("策略2: 检查音频文件是否自带封面");
        coverArtData = extractCoverFromAudioFile(audioFile);
        
        if (coverArtData != null && coverArtData.length > 0) {
            log.info("✓ 成功从音频文件提取封面,缓存到文件夹级别");
            folderCoverCache.put(folderPath, coverArtData);
            return coverArtData;
        }
        log.info("✗ 音频文件无封面,尝试降级策略");
        
        // 策略3: 在音频文件所在目录查找cover图片
        log.info("策略3: 在音频文件所在目录查找cover图片");
        coverArtData = findCoverInDirectory(audioFile.getParentFile());
        
        if (coverArtData != null && coverArtData.length > 0) {
            log.info("✓ 成功从目录找到封面图片,缓存到文件夹级别");
            folderCoverCache.put(folderPath, coverArtData);
            return coverArtData;
        }
        
        log.warn("✗ 所有策略均失败,未找到封面图片");
        return null;
    }
    
    /**
     * 从网络下载封面(使用缓存)
     */
    private static byte[] downloadCoverFromNetwork(String coverArtUrl) {
        try {
            // 首先检查缓存
            byte[] coverArtData = coverArtCache.getCachedCover(coverArtUrl);
            
            if (coverArtData != null) {
                log.info("从缓存获取封面");
                return coverArtData;
            }
            
            // 缓存未命中,下载并压缩
            log.info("正在下载封面图片: {}", coverArtUrl);
            byte[] rawCoverArt = musicBrainzClient.downloadCoverArt(coverArtUrl);
            
            if (rawCoverArt != null && rawCoverArt.length > 0) {
                // 压缩图片到2MB以内
                coverArtData = ImageCompressor.compressImage(rawCoverArt);
                
                // 保存到缓存
                coverArtCache.cacheCover(coverArtUrl, coverArtData);
                return coverArtData;
            }
        } catch (Exception e) {
            log.warn("从网络下载封面失败", e);
        }
        return null;
    }
    
    /**
     * 从音频文件提取封面
     */
    private static byte[] extractCoverFromAudioFile(File audioFile) {
        try {
            AudioFile audioFileObj = org.jaudiotagger.audio.AudioFileIO.read(audioFile);
            org.jaudiotagger.tag.Tag tag = audioFileObj.getTag();
            
            if (tag != null) {
                Artwork artwork = tag.getFirstArtwork();
                if (artwork != null) {
                    byte[] imageData = artwork.getBinaryData();
                    if (imageData != null && imageData.length > 0) {
                        // 压缩图片到2MB以内
                        return ImageCompressor.compressImage(imageData);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("从音频文件提取封面失败: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * 在目录中查找封面图片
     * 支持的文件名: cover.jpg, cover.png, folder.jpg, folder.png, album.jpg, album.png
     */
    private static byte[] findCoverInDirectory(File directory) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return null;
        }
        
        // 支持的封面文件名(优先级顺序)
        String[] coverNames = {"cover", "folder", "album", "front"};
        String[] extensions = {".jpg", ".jpeg", ".png", ".webp"};
        
        for (String coverName : coverNames) {
            for (String ext : extensions) {
                File coverFile = new File(directory, coverName + ext);
                if (coverFile.exists() && coverFile.isFile()) {
                    try {
                        byte[] imageData = Files.readAllBytes(coverFile.toPath());
                        if (imageData != null && imageData.length > 0) {
                            log.info("找到封面文件: {}", coverFile.getName());
                            // 压缩图片到2MB以内
                            return ImageCompressor.compressImage(imageData);
                        }
                    } catch (Exception e) {
                        log.debug("读取封面文件失败: {} - {}", coverFile.getName(), e.getMessage());
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * 检查文件夹是否包含临时文件(下载未完成)
     * 常见下载工具临时文件扩展名:
     * - qBittorrent: .!qB (旧版本使用 .!qb)
     * - Transmission: .part
     * - uTorrent/BitTorrent: .ut!
     * - Chrome/Firefox: .crdownload, .tmp
     */
    private static boolean hasTempFilesInFolder(File audioFile) {
        File parentDir = audioFile.getParentFile();
        if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory()) {
            return false;
        }
        
        File[] files = parentDir.listFiles();
        if (files == null) {
            return false;
        }
        
        // 临时文件扩展名列表
        String[] tempExtensions = {".!qb", ".!qB", ".part", ".ut!", ".crdownload", ".tmp", ".download"};
        
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            
            String fileName = file.getName().toLowerCase();
            for (String tempExt : tempExtensions) {
                if (fileName.endsWith(tempExt.toLowerCase())) {
                    log.info("检测到临时文件: {}", file.getName());
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * 统计文件所在文件夹内的音乐文件数量
     */
    private static int countMusicFilesInFolder(File audioFile) {
        File parentDir = audioFile.getParentFile();
        if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory()) {
            return 1;
        }
        
        File[] files = parentDir.listFiles();
        if (files == null) {
            return 1;
        }
        
        int count = 0;
        for (File file : files) {
            if (file.isFile() && isMusicFile(file)) {
                count++;
            }
        }
        
        log.info("文件夹 {} 中共有 {} 个音乐文件", parentDir.getName(), count);
        return count;
    }
    
    /**
     * 判断是否为音乐文件
     */
    private static boolean isMusicFile(File file) {
        String fileName = file.getName().toLowerCase();
        String[] supportedFormats = config.getSupportedFormats();
        for (String format : supportedFormats) {
            if (fileName.endsWith("." + format.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 检查是否有完整的标签
     */
    private static boolean hasCompleteTags(TagWriterService.MusicMetadata metadata) {
        return metadata.getTitle() != null && !metadata.getTitle().isEmpty() &&
               metadata.getArtist() != null && !metadata.getArtist().isEmpty() &&
               metadata.getAlbum() != null && !metadata.getAlbum().isEmpty();
    }
    
    /**
     * 将 AcoustID RecordingInfo 转换为 MusicBrainz MusicMetadata
     */
    private static MusicBrainzClient.MusicMetadata convertToMusicMetadata(
            AudioFingerprintService.RecordingInfo recordingInfo) {
        
        MusicBrainzClient.MusicMetadata metadata = new MusicBrainzClient.MusicMetadata();
        metadata.setRecordingId(recordingInfo.getRecordingId());
        metadata.setTitle(recordingInfo.getTitle());
        metadata.setArtist(recordingInfo.getArtist());
        metadata.setAlbum(recordingInfo.getAlbum());
        return metadata;
    }
    
    /**
     * 将 MusicBrainz MusicMetadata 转换为 TagWriter MusicMetadata
     */
    private static TagWriterService.MusicMetadata convertToTagMetadata(
            MusicBrainzClient.MusicMetadata musicMetadata) {
        
        TagWriterService.MusicMetadata tagMetadata = new TagWriterService.MusicMetadata();
        tagMetadata.setRecordingId(musicMetadata.getRecordingId());
        tagMetadata.setTitle(musicMetadata.getTitle());
        tagMetadata.setArtist(musicMetadata.getArtist());
        tagMetadata.setAlbumArtist(musicMetadata.getAlbumArtist());
        tagMetadata.setAlbum(musicMetadata.getAlbum());
        tagMetadata.setReleaseDate(musicMetadata.getReleaseDate());
        tagMetadata.setGenres(musicMetadata.getGenres());
        
        // 传递新增字段
        tagMetadata.setComposer(musicMetadata.getComposer());
        tagMetadata.setLyricist(musicMetadata.getLyricist());
        tagMetadata.setLyrics(musicMetadata.getLyrics());
        
        return tagMetadata;
    }
    
    /**
     * 优雅关闭所有服务
     */
    private static void shutdown() {
        log.info("正在关闭系统...");
        
        try {
            // 按依赖关系逆序关闭服务
            if (fileMonitor != null) {
                fileMonitor.stop();
            }
            
            if (fingerprintService != null) {
                fingerprintService.close();
            }
            
            if (musicBrainzClient != null) {
                musicBrainzClient.close();
            }
            
            if (lyricsService != null) {
                lyricsService.close();
            }

            if (coverArtCache != null) {
                CoverArtCache.CacheStatistics stats = coverArtCache.getStatistics();
                log.info("封面缓存统计: {}", stats);
                coverArtCache.close();
            }
            
            if (processedLogger != null) {
                processedLogger.close();
            }
            
            // 最后关闭数据库连接池
            if (databaseService != null) {
                databaseService.close();
            }
            
            log.info("系统已安全关闭");
        } catch (Exception e) {
            log.error("关闭服务时出错", e);
        }
    }
}