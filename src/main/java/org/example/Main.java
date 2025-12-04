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
    private static FolderAlbumCache folderAlbumCache;
    private static QuickScanService quickScanService;
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
        
        // 初始化时长序列匹配服务
        DurationSequenceService durationSequenceService = new DurationSequenceService();
        
        // 初始化文件夹专辑缓存（注入依赖服务）
        folderAlbumCache = new FolderAlbumCache(
            durationSequenceService,
            musicBrainzClient,
            fingerprintService
        );
        
        // 初始化快速扫描服务
        quickScanService = new QuickScanService(
            config,
            musicBrainzClient,
            durationSequenceService,
            fingerprintService
        );
        
        log.info("✓ 时长序列匹配服务已启用");
        log.info("✓ 快速扫描服务已启用（两级扫描策略）");
        
        // Level 3: 初始化文件监控服务
        log.info("初始化文件监控服务...");
        fileMonitor = new FileMonitorService(config, processedLogger);
        fileMonitor.setFileReadyCallbackWithResult(Main::processAudioFileWithResult);
        
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
     * 处理音频文件的核心逻辑（带返回值）- 两阶段处理
     * 阶段1: 识别收集阶段 - 收集专辑信息，不写文件
     * 阶段2: 批量写入阶段 - 确定专辑后统一批量处理
     * @return true表示成功，false表示失败（会加入重试队列）
     */
    private static boolean processAudioFileWithResult(File audioFile) {
        log.info("========================================");
        log.info("处理音频文件: {}", audioFile.getName());
        
        try {
            // 0. 检查文件是否已处理过
            if (processedLogger.isFileProcessed(audioFile)) {
                log.info("文件已处理过，跳过: {}", audioFile.getName());
                return true; // 已处理，返回成功
            }
            
            // 0.3. 检查文件夹是否有临时文件(下载未完成)
            if (hasTempFilesInFolder(audioFile)) {
                log.warn("检测到文件夹中有临时文件,可能正在下载中,跳过处理: {}", audioFile.getParentFile().getName());
                return false; // 返回false以触发重试，而不是永久跳过
            }
            
            // 0.5. 统计文件夹内音乐文件数量（用于判断是否为单曲/EP/专辑）
            int musicFilesInFolder = countMusicFilesInFolder(audioFile);
            String folderPath = audioFile.getParentFile().getAbsolutePath();
            
            MusicBrainzClient.MusicMetadata detailedMetadata = null;
            
            // ===== 优先检查文件夹专辑缓存 =====
            FolderAlbumCache.CachedAlbumInfo cachedAlbum = folderAlbumCache.getFolderAlbum(folderPath, musicFilesInFolder);
            
            String lockedAlbumTitle = null;
            String lockedAlbumArtist = null;
            String lockedReleaseGroupId = null;
            String lockedReleaseDate = null;
            
            if (cachedAlbum != null) {
                // 已有缓存专辑，锁定专辑信息，但仍需指纹识别获取单曲详细信息
                log.info("✓ 使用文件夹缓存的专辑信息");
                log.info("专辑: {} - {}", cachedAlbum.getAlbumArtist(), cachedAlbum.getAlbumTitle());
                
                lockedAlbumTitle = cachedAlbum.getAlbumTitle();
                lockedAlbumArtist = cachedAlbum.getAlbumArtist();
                lockedReleaseGroupId = cachedAlbum.getReleaseGroupId();
                lockedReleaseDate = cachedAlbum.getReleaseDate();
                
            } else {
                // 没有缓存，进行快速扫描
                log.info("尝试第一级快速扫描（基于标签和文件夹名称）...");
                QuickScanService.QuickScanResult quickResult = quickScanService.quickScan(audioFile, musicFilesInFolder);
                
                if (quickResult != null && quickResult.isHighConfidence()) {
                    // 快速扫描成功，锁定专辑信息
                    log.info("✓ 快速扫描成功，锁定专辑信息");
                    MusicBrainzClient.MusicMetadata quickMetadata = quickResult.getMetadata();
                    
                    lockedAlbumTitle = quickMetadata.getAlbum();
                    lockedAlbumArtist = quickMetadata.getAlbumArtist() != null ?
                        quickMetadata.getAlbumArtist() : quickMetadata.getArtist();
                    lockedReleaseGroupId = quickMetadata.getReleaseGroupId();
                    lockedReleaseDate = quickMetadata.getReleaseDate();
                    
                    // 立即将专辑信息写入文件夹缓存
                    FolderAlbumCache.CachedAlbumInfo albumInfo = new FolderAlbumCache.CachedAlbumInfo(
                        lockedReleaseGroupId,
                        lockedAlbumTitle,
                        lockedAlbumArtist,
                        quickMetadata.getTrackCount(),
                        lockedReleaseDate,
                        quickResult.getSimilarity()
                    );
                    folderAlbumCache.setFolderAlbum(folderPath, albumInfo);
                    log.info("已将快速扫描结果缓存到文件夹级别");
                }
            }
            
            // ===== 无论快速扫描是否成功，都进行指纹识别获取单曲详细信息 =====
            log.info("正在进行音频指纹识别以获取单曲详细元数据...");
            AudioFingerprintService.AcoustIdResult acoustIdResult =
                fingerprintService.identifyAudioFile(audioFile);
            
            if (acoustIdResult.getRecordings() == null || acoustIdResult.getRecordings().isEmpty()) {
                log.warn("无法通过音频指纹识别文件: {}", audioFile.getName());
                log.info("该文件的 AcoustID 未关联到 MusicBrainz 录音信息");
                
                // 如果没有锁定的专辑信息，则识别失败
                if (lockedAlbumTitle == null) {
                    log.info("建议：手动添加标签或等待 MusicBrainz 社区完善数据");
                    
                    // 如果配置了失败目录，复制整个文件夹到失败目录
                    if (config.getFailedDirectory() != null && !config.getFailedDirectory().isEmpty()) {
                        try {
                            copyFailedFolderToFailedDirectory(audioFile);
                        } catch (Exception e) {
                            log.error("复制失败文件夹到失败目录时出错: {}", e.getMessage());
                        }
                    }
                    
                    // 记录识别失败的文件
                    processedLogger.markFileAsProcessed(
                        audioFile,
                        "UNKNOWN",
                        "识别失败",
                        audioFile.getName(),
                        "Unknown Album"
                    );
                    return true; // 识别失败，不重试但记录
                } else {
                    // 有锁定的专辑信息，创建基础metadata
                    log.warn("无法获取单曲详细信息，使用基础信息");
                    detailedMetadata = new MusicBrainzClient.MusicMetadata();
                    detailedMetadata.setTitle(audioFile.getName()); // 使用文件名作为标题
                    detailedMetadata.setArtist(lockedAlbumArtist);
                    detailedMetadata.setAlbumArtist(lockedAlbumArtist);
                    detailedMetadata.setAlbum(lockedAlbumTitle);
                    detailedMetadata.setReleaseGroupId(lockedReleaseGroupId);
                    detailedMetadata.setReleaseDate(lockedReleaseDate);
                }
            } else {
                // 指纹识别成功，获取详细元数据
                AudioFingerprintService.RecordingInfo bestMatch = acoustIdResult.getRecordings().get(0);
                log.info("识别成功: {} - {}", bestMatch.getArtist(), bestMatch.getTitle());
                
                // 如果有锁定的专辑信息，传入1作为musicFilesInFolder以避免selectBestRelease被文件数量影响
                // 否则传入实际的文件数量
                int musicFilesParam = (lockedAlbumTitle != null) ? 1 : musicFilesInFolder;
                
                // 通过 MusicBrainz 获取详细元数据（包含作词、作曲、风格等）
                log.info("正在获取详细元数据...");
                detailedMetadata = musicBrainzClient.getRecordingById(bestMatch.getRecordingId(), musicFilesParam);
                
                if (detailedMetadata == null) {
                    log.warn("无法获取详细元数据");
                    detailedMetadata = convertToMusicMetadata(bestMatch);
                }
                
                // 如果有锁定的专辑信息，用锁定的信息覆盖（确保专辑信息不被改变）
                if (lockedAlbumTitle != null) {
                    log.info("应用锁定的专辑信息: {}", lockedAlbumTitle);
                    detailedMetadata.setAlbum(lockedAlbumTitle);
                    detailedMetadata.setAlbumArtist(lockedAlbumArtist);
                    detailedMetadata.setReleaseGroupId(lockedReleaseGroupId);
                    if (lockedReleaseDate != null && !lockedReleaseDate.isEmpty()) {
                        detailedMetadata.setReleaseDate(lockedReleaseDate);
                    }
                }
            }
            
            // 4. 获取封面图片(多层降级策略)
            // 如果有锁定的专辑信息，需要基于锁定的专辑获取封面
            byte[] coverArtData;
            if (lockedReleaseGroupId != null) {
                // 使用锁定的专辑信息获取封面
                coverArtData = getCoverArtWithFallback(audioFile, detailedMetadata, lockedReleaseGroupId);
            } else {
                // 使用指纹识别的专辑信息获取封面
                coverArtData = getCoverArtWithFallback(audioFile, detailedMetadata, null);
            }
            
            if (coverArtData != null && coverArtData.length > 0) {
                log.info("✓ 成功获取封面图片");
            } else {
                log.info("未找到封面图片");
            }
            
            // 4.5 获取歌词 (LrcLib)
            log.info("正在获取歌词...");
            String lyrics = lyricsService.getLyrics(
                detailedMetadata.getTitle(),
                detailedMetadata.getArtist(),
                detailedMetadata.getAlbum(),
                0
            );
            if (lyrics != null && !lyrics.isEmpty()) {
                detailedMetadata.setLyrics(lyrics);
            } else {
                log.info("未找到歌词");
            }
            
            // 5. 文件夹级别的专辑锁定处理
            if (lockedAlbumTitle != null) {
                // 已有锁定的专辑信息（来自快速扫描或缓存），直接处理文件
                log.info("使用已锁定的专辑信息: {}", lockedAlbumTitle);
                processAndWriteFile(audioFile, detailedMetadata, coverArtData);
            } else {
                // 未锁定专辑：收集样本进行投票
                log.info("启用文件夹级别专辑锁定（{}首音乐文件）", musicFilesInFolder);
                
                int trackCount = detailedMetadata.getTrackCount();
                
                FolderAlbumCache.AlbumIdentificationInfo albumInfo = new FolderAlbumCache.AlbumIdentificationInfo(
                    detailedMetadata.getReleaseGroupId(),
                    detailedMetadata.getAlbum(),
                    detailedMetadata.getAlbumArtist() != null ? detailedMetadata.getAlbumArtist() : detailedMetadata.getArtist(),
                    trackCount,
                    detailedMetadata.getReleaseDate()
                );
                
                // 添加到待处理队列
                folderAlbumCache.addPendingFile(folderPath, audioFile, detailedMetadata, coverArtData);
                
                // 尝试确定专辑
                FolderAlbumCache.CachedAlbumInfo determinedAlbum = folderAlbumCache.addSample(
                    folderPath,
                    audioFile.getName(),
                    musicFilesInFolder,
                    albumInfo
                );
                
                if (determinedAlbum != null) {
                    // 专辑已确定，批量处理所有待处理文件
                    log.info("========================================");
                    log.info("✓ 文件夹专辑已确定: {}", determinedAlbum.getAlbumTitle());
                    log.info("开始批量处理文件夹内的所有文件...");
                    log.info("========================================");
                    
                    processPendingFilesWithAlbum(folderPath, determinedAlbum);
                } else {
                    log.info("专辑收集中，待处理文件已加入队列: {}", audioFile.getName());
                }
            }
            
            return true; // 处理成功
            
        } catch (java.io.IOException e) {
            // 网络异常（包括 SocketException），返回false以触发重试
            log.error("网络错误导致处理文件失败: {} - {}", audioFile.getName(), e.getMessage());
            log.info("文件将被加入重试队列");
            return false;
            
        } catch (Exception e) {
            // 其他异常（如识别失败），不重试
            log.error("处理文件失败: {}", audioFile.getName(), e);
            return true; // 返回true避免重试（非网络问题）
            
        } finally {
            log.info("========================================");
        }
    }
    
    /**
     * 处理并写入单个文件
     */
    private static void processAndWriteFile(File audioFile, MusicBrainzClient.MusicMetadata metadata, byte[] coverArtData) {
        try {
            log.info("正在写入文件标签: {}", audioFile.getName());
            TagWriterService.MusicMetadata tagMetadata = convertToTagMetadata(metadata);
            boolean success = tagWriter.processFile(audioFile, tagMetadata, coverArtData);
            
            if (success) {
                log.info("✓ 文件处理成功: {}", audioFile.getName());
                
                // 记录文件已处理
                processedLogger.markFileAsProcessed(
                    audioFile,
                    metadata.getRecordingId(),
                    metadata.getArtist(),
                    metadata.getTitle(),
                    metadata.getAlbum()
                );
            } else {
                log.error("✗ 文件处理失败: {}", audioFile.getName());
            }
        } catch (Exception e) {
            log.error("写入文件失败: {}", audioFile.getName(), e);
        }
    }
    
    /**
     * 批量处理文件夹内的待处理文件，统一应用确定的专辑信息
     */
    private static void processPendingFilesWithAlbum(String folderPath, FolderAlbumCache.CachedAlbumInfo albumInfo) {
        java.util.List<FolderAlbumCache.PendingFile> pendingFiles = folderAlbumCache.getPendingFiles(folderPath);
        
        if (pendingFiles == null || pendingFiles.isEmpty()) {
            log.warn("文件夹没有待处理文件: {}", folderPath);
            return;
        }
        
        log.info("开始批量处理 {} 个待处理文件", pendingFiles.size());
        
        int successCount = 0;
        int failCount = 0;
        java.util.List<File> failedFiles = new java.util.ArrayList<>();
        
        for (FolderAlbumCache.PendingFile pending : pendingFiles) {
            try {
                File audioFile = pending.getAudioFile();
                MusicBrainzClient.MusicMetadata metadata = (MusicBrainzClient.MusicMetadata) pending.getMetadata();
                byte[] coverArtData = pending.getCoverArtData();
                
                log.info("批量处理文件 [{}/{}]: {}",
                    successCount + failCount + 1, pendingFiles.size(), audioFile.getName());
                
                // 注意：metadata已经通过指纹识别获取了完整的单曲信息
                // 只需要覆盖专辑相关字段
                metadata.setAlbum(albumInfo.getAlbumTitle());
                metadata.setAlbumArtist(albumInfo.getAlbumArtist());
                metadata.setReleaseGroupId(albumInfo.getReleaseGroupId());
                if (albumInfo.getReleaseDate() != null && !albumInfo.getReleaseDate().isEmpty()) {
                    metadata.setReleaseDate(albumInfo.getReleaseDate());
                }
                
                // 写入文件（metadata已包含作词、作曲、风格等信息）
                processAndWriteFile(audioFile, metadata, coverArtData);
                successCount++;
                
            } catch (Exception e) {
                log.error("批量处理文件失败: {}", pending.getAudioFile().getName(), e);
                failCount++;
                failedFiles.add(pending.getAudioFile());
                // 对于失败的文件，也记录到数据库，避免数据缺失
                try {
                    MusicBrainzClient.MusicMetadata metadata = (MusicBrainzClient.MusicMetadata) pending.getMetadata();
                    processedLogger.markFileAsProcessed(
                        pending.getAudioFile(),
                        metadata.getRecordingId() != null ? metadata.getRecordingId() : "UNKNOWN",
                        metadata.getArtist() != null ? metadata.getArtist() : "Unknown Artist",
                        metadata.getTitle() != null ? metadata.getTitle() : pending.getAudioFile().getName(),
                        albumInfo.getAlbumTitle()
                    );
                    log.info("已记录失败文件到数据库: {}", pending.getAudioFile().getName());
                } catch (Exception recordError) {
                    log.error("记录失败文件到数据库失败: {}", pending.getAudioFile().getName(), recordError);
                }
            }
        }
        
        log.info("========================================");
        log.info("批量处理完成: 成功 {} 个, 失败 {} 个", successCount, failCount);
        if (!failedFiles.isEmpty()) {
            log.warn("失败文件列表:");
            for (File file : failedFiles) {
                log.warn("  - {}", file.getName());
            }
        }
        log.info("========================================");
        
        // 清除待处理列表
        folderAlbumCache.clearPendingFiles(folderPath);
    }
    
    /**
     * 获取封面图片(多层降级策略 + 文件夹级别缓存)
     * 优先级:
     * 0. 检查同文件夹是否已有其他文件获取过封面
     * 1. 尝试从网络下载(使用缓存)
     * 2. 如果下载失败,检查音频文件是否自带封面
     * 3. 如果没有自带封面,在音频文件所在目录查找cover图片
     *
     * @param audioFile 音频文件
     * @param metadata 元数据（包含封面URL）
     * @param lockedReleaseGroupId 锁定的专辑release group ID，如果非null则优先使用此专辑的封面
     */
    private static byte[] getCoverArtWithFallback(File audioFile, MusicBrainzClient.MusicMetadata metadata, String lockedReleaseGroupId) {
        byte[] coverArtData = null;
        String folderPath = audioFile.getParentFile().getAbsolutePath();
        
        // 策略0: 检查文件夹级别缓存
        coverArtData = folderCoverCache.get(folderPath);
        if (coverArtData != null && coverArtData.length > 0) {
            log.info("策略0: 使用同文件夹已获取的封面");
            return coverArtData;
        }
        
        // 策略1: 尝试从网络下载
        String coverArtUrl = null;
        
        // 如果有锁定的专辑ID，只使用锁定专辑的封面
        if (lockedReleaseGroupId != null) {
            log.info("使用锁定专辑的封面 (Release Group ID: {})", lockedReleaseGroupId);
            try {
                coverArtUrl = musicBrainzClient.getCoverArtUrlByReleaseGroupId(lockedReleaseGroupId);
                if (coverArtUrl != null) {
                    log.info("获取到锁定专辑的封面URL: {}", coverArtUrl);
                } else {
                    log.warn("锁定专辑没有可用的封面，将跳过网络下载，直接进入降级策略");
                }
            } catch (Exception e) {
                log.warn("获取锁定专辑封面URL失败，将跳过网络下载，直接进入降级策略: {}", e.getMessage());
            }
        } else if (metadata.getCoverArtUrl() != null) {
            // 没有锁定专辑时，才使用指纹识别返回的封面URL
            log.info("使用指纹识别返回的封面URL");
            coverArtUrl = metadata.getCoverArtUrl();
        }
        
        if (coverArtUrl != null) {
            log.info("策略1: 尝试从网络下载封面");
            coverArtData = downloadCoverFromNetwork(coverArtUrl);
            
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
     * 统计文件所在文件夹内的音乐文件数量（智能递归，支持多CD专辑）
     *
     * 逻辑:
     * - 如果父文件夹是监控目录本身，只统计当前层级（避免混入其他专辑）
     * - 如果父文件夹是监控目录的子文件夹，递归统计（支持多CD专辑）
     */
    private static int countMusicFilesInFolder(File audioFile) {
        File parentDir = audioFile.getParentFile();
        if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory()) {
            return 1;
        }
        
        // 获取监控目录的规范路径
        String monitorDirPath;
        try {
            monitorDirPath = new File(config.getMonitorDirectory()).getCanonicalPath();
        } catch (java.io.IOException e) {
            monitorDirPath = config.getMonitorDirectory();
        }
        
        // 获取父文件夹的规范路径
        String parentDirPath;
        try {
            parentDirPath = parentDir.getCanonicalPath();
        } catch (java.io.IOException e) {
            parentDirPath = parentDir.getAbsolutePath();
        }
        
        // 如果父文件夹就是监控目录，只统计当前层级
        if (parentDirPath.equals(monitorDirPath)) {
            log.info("文件位于监控目录根目录，只统计当前层级");
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
            log.info("监控目录根目录中共有 {} 个音乐文件", count);
            return count;
        } else {
            // 父文件夹是监控目录的子文件夹，递归统计（支持多CD专辑）
            int count = countMusicFilesRecursively(parentDir);
            log.info("文件夹 {} 中共有 {} 个音乐文件（包括子文件夹）", parentDir.getName(), count);
            return count;
        }
    }
    
    /**
     * 递归统计文件夹及其子文件夹中的音乐文件数量
     */
    private static int countMusicFilesRecursively(File directory) {
        if (!directory.isDirectory()) {
            return 0;
        }
        
        File[] files = directory.listFiles();
        if (files == null) {
            return 0;
        }
        
        int count = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                // 递归统计子文件夹
                count += countMusicFilesRecursively(file);
            } else if (file.isFile() && isMusicFile(file)) {
                count++;
            }
        }
        
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
     * 复制失败文件所在的整个文件夹到失败目录
     * 保留文件夹结构，方便用户手动处理
     */
    private static void copyFailedFolderToFailedDirectory(File audioFile) throws IOException {
        File sourceFolder = audioFile.getParentFile();
        if (sourceFolder == null || !sourceFolder.exists()) {
            log.warn("源文件夹不存在，无法复制");
            return;
        }
        
        // 构建目标路径：失败目录/原文件夹名
        String folderName = sourceFolder.getName();
        File targetFolder = new File(config.getFailedDirectory(), folderName);
        
        // 检查是否已经复制过该文件夹
        if (targetFolder.exists()) {
            log.debug("失败文件夹已存在，跳过复制: {}", targetFolder.getAbsolutePath());
            return;
        }
        
        log.info("========================================");
        log.info("识别失败，复制整个文件夹到失败目录");
        log.info("源文件夹: {}", sourceFolder.getAbsolutePath());
        log.info("目标位置: {}", targetFolder.getAbsolutePath());
        
        // 创建目标文件夹
        if (!targetFolder.mkdirs()) {
            log.error("无法创建目标文件夹: {}", targetFolder.getAbsolutePath());
            return;
        }
        
        // 复制文件夹中的所有文件
        File[] files = sourceFolder.listFiles();
        if (files == null) {
            log.warn("无法列出源文件夹内容");
            return;
        }
        
        int copiedCount = 0;
        int skippedCount = 0;
        
        for (File file : files) {
            if (!file.isFile()) {
                continue; // 跳过子目录
            }
            
            File targetFile = new File(targetFolder, file.getName());
            try {
                Files.copy(file.toPath(), targetFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                copiedCount++;
                log.debug("已复制: {}", file.getName());
            } catch (IOException e) {
                log.warn("复制文件失败: {} - {}", file.getName(), e.getMessage());
                skippedCount++;
            }
        }
        
        log.info("文件夹复制完成: 成功 {} 个文件, 跳过 {} 个", copiedCount, skippedCount);
        log.info("失败文件夹位置: {}", targetFolder.getAbsolutePath());
        log.info("========================================");
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
            
            if (folderAlbumCache != null) {
                FolderAlbumCache.CacheStatistics stats = folderAlbumCache.getStatistics();
                log.info("文件夹专辑缓存统计: {}", stats);
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