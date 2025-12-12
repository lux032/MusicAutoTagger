package org.example;

import lombok.extern.slf4j.Slf4j;
import org.example.config.MusicConfig;
import org.example.model.MusicMetadata;
import org.example.service.*;
import org.example.util.I18nUtil;
import org.example.web.WebServer;
import org.example.service.LogCollector;

import java.io.File;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.tag.images.Artwork;
import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;

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
    private static WebServer webServer;
    
    // 文件夹级别的封面缓存: 文件夹路径 -> 封面数据
    private static java.util.Map<String, byte[]> folderCoverCache = new java.util.concurrent.ConcurrentHashMap<>();
    
    public static void main(String[] args) {
        System.out.println(I18nUtil.getMessage("main.title.separator"));
        System.out.println(I18nUtil.getMessage("main.title"));
        System.out.println(I18nUtil.getMessage("main.title.separator"));
        
        try {
            // 1. 加载配置
            config = MusicConfig.getInstance();
            if (!config.isValid()) {
                log.error(I18nUtil.getMessage("app.config.invalid"));
                return;
            }
            
            log.info(I18nUtil.getMessage("app.config.loaded"));
            log.info(I18nUtil.getMessage("app.monitor.directory"), config.getMonitorDirectory());
            log.info(I18nUtil.getMessage("app.output.directory"), config.getOutputDirectory());
            log.info(I18nUtil.getMessage("app.scan.interval"), config.getScanIntervalSeconds());
            
            // 2. 初始化服务
            initializeServices();
            
            // 3. 检查依赖工具
            if (!fingerprintService.isFpcalcAvailable()) {
                log.warn(I18nUtil.getMessage("main.title.separator"));
                log.warn(I18nUtil.getMessage("main.fpcalc.warning.line"));
                log.warn(I18nUtil.getMessage("main.fpcalc.feature.disabled"));
                log.warn(I18nUtil.getMessage("main.fpcalc.install.guide"));
                log.warn(I18nUtil.getMessage("main.title.separator"));
                }
                
                // 4. 启动 Web 监控面板
                startWebServer();
                
                // 5. 启动文件监控
                startMonitoring();
                
                // 6. 等待用户输入以停止程序
            System.out.println("\n" + I18nUtil.getMessage("main.system.running"));
            System.out.println(I18nUtil.getMessage("main.press.enter.to.stop"));
            System.in.read();
            
            // 6. 优雅关闭
            shutdown();
            
        } catch (Exception e) {
            log.error(I18nUtil.getMessage("main.error"), e);
        }
    }
    
    /**
     * 初始化所有服务
     */
    private static void initializeServices() throws IOException {
        log.info(I18nUtil.getMessage("app.init.services"));

        // Level 0: 初始化国际化
        I18nUtil.init(config.getLanguage());
        log.info(I18nUtil.getMessage("app.init.i18n"), config.getLanguage());

        // Level 1: 初始化数据库服务 (如果配置为 MySQL)
        if ("mysql".equalsIgnoreCase(config.getDbType())) {
            log.info(I18nUtil.getMessage("app.init.database"));
            databaseService = new DatabaseService(config);
        } else {
            log.info(I18nUtil.getMessage("app.init.file.mode"));
        }
        
        // Level 2: 初始化依赖数据库的服务
        log.info(I18nUtil.getMessage("app.init.log.service"));
        processedLogger = new ProcessedFileLogger(config, databaseService);
        
        String cacheDir = config.getCoverArtCacheDirectory();
        if (cacheDir == null || cacheDir.isEmpty()) {
            cacheDir = config.getOutputDirectory() + "/.cover_cache";
        }
        // 在文件模式下, databaseService 为 null, CoverArtCache 会自动降级为文件系统缓存
        coverArtCache = new CoverArtCache(databaseService, cacheDir);
        
        // Level 2: 初始化其他服务
        log.info(I18nUtil.getMessage("app.init.other.services"));
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
        
        log.info(I18nUtil.getMessage("app.duration.sequence.enabled"));
        log.info(I18nUtil.getMessage("app.quick.scan.enabled"));
        
        // Level 3: 初始化文件监控服务
        log.info(I18nUtil.getMessage("app.init.file.monitor"));
        fileMonitor = new FileMonitorService(config, processedLogger);
        fileMonitor.setFileReadyCallbackWithResult(Main::processAudioFileWithResult);
        
        log.info(I18nUtil.getMessage("app.all.services.ready"));
    }
    
    /**
     * 启动 Web 监控面板
     */
    private static void startWebServer() {
        try {
            webServer = new WebServer(8080);
            webServer.start(processedLogger, coverArtCache, folderAlbumCache, config, databaseService);
        } catch (Exception e) {
            log.error(I18nUtil.getMessage("main.web.start.error"), e);
            log.warn(I18nUtil.getMessage("main.web.unavailable"));
        }
    }
    
    /**
     * 启动文件监控
     */
    private static void startMonitoring() {
        log.info(I18nUtil.getMessage("monitor.start.monitoring") + "...");
        fileMonitor.start();
    }
    
    /**
     * 处理音频文件的核心逻辑（带返回值）- 两阶段处理
     * 阶段1: 识别收集阶段 - 收集专辑信息，不写文件
     * 阶段2: 批量写入阶段 - 确定专辑后统一批量处理
     * @return true表示成功，false表示失败（会加入重试队列）
     */
    private static boolean processAudioFileWithResult(File audioFile) {
        log.info(I18nUtil.getMessage("main.title.separator"));
        log.info(I18nUtil.getMessage("main.processing.file"), audioFile.getName());
        LogCollector.addLog("INFO", I18nUtil.getMessage("main.title.separator"));
        LogCollector.addLog("INFO", I18nUtil.getMessage("main.processing.file", audioFile.getName()));
        
        try {
            // 0. 检查文件是否已处理过
            if (processedLogger.isFileProcessed(audioFile)) {
                log.info(I18nUtil.getMessage("main.file.already.processed"), audioFile.getName());
                return true; // 已处理，返回成功
            }
            
            // 0.3. 检查文件夹是否有临时文件(下载未完成)
            if (hasTempFilesInFolder(audioFile)) {
                log.warn(I18nUtil.getMessage("main.temp.files.detected"), audioFile.getParentFile().getName());
                return false; // 返回false以触发重试，而不是永久跳过
            }
            
            // 0.5. 获取专辑根目录（监控目录的第一级子目录）
            File albumRootDir = getAlbumRootDirectory(audioFile);
            String folderPath = albumRootDir.getAbsolutePath();
            
            // 0.5.1 统计专辑根目录内音乐文件数量（递归统计所有子文件夹）
            int musicFilesInFolder = countMusicFilesInFolder(audioFile);
            
            // 0.6. 检测是否为散落在监控目录根目录的单个文件（保底处理）
            boolean isLooseFileInMonitorRoot = isLooseFileInMonitorRoot(audioFile);
            
            MusicMetadata detailedMetadata = null;
            
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
                
            } else if (!isLooseFileInMonitorRoot) {
                // 没有缓存且不是散落文件，进行快速扫描
                log.info("尝试第一级快速扫描（基于标签和文件夹名称）...");
                LogCollector.addLog("INFO", "📂 " + I18nUtil.getMessage("main.quick.scan.attempt", audioFile.getName()));
                QuickScanService.QuickScanResult quickResult = quickScanService.quickScan(audioFile, musicFilesInFolder);

                if (quickResult != null && quickResult.isHighConfidence()) {
                    // 快速扫描成功，锁定专辑信息
                    log.info("✓ 快速扫描成功，锁定专辑信息");
                    LogCollector.addLog("SUCCESS", "✓ " + I18nUtil.getMessage("main.quick.scan.success", audioFile.getName()));
                    MusicMetadata quickMetadata = quickResult.getMetadata();
                    
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
            } else {
                // 散落文件，跳过快速扫描
                log.info("散落文件跳过快速扫描，将直接进入随缘匹配模式（指纹识别）");
            }
            
            // ===== 保底处理：如果是散落文件，跳过专辑匹配，直接指纹识别 =====
            if (isLooseFileInMonitorRoot) {
                log.info("========================================");
                log.info("检测到散落在监控目录的单个文件，启用保底处理机制");
                log.info("跳过专辑匹配，直接进行指纹识别");
                log.info("========================================");
            }
            
            // ===== 无论快速扫描是否成功，都进行指纹识别获取单曲详细信息 =====
            log.info("正在进行音频指纹识别以获取单曲详细元数据...");
            LogCollector.addLog("INFO", "🔍 " + I18nUtil.getMessage("main.fingerprint.identifying", audioFile.getName()));
            AudioFingerprintService.AcoustIdResult acoustIdResult =
                fingerprintService.identifyAudioFile(audioFile);

            if (acoustIdResult.getRecordings() == null || acoustIdResult.getRecordings().isEmpty()) {
                log.warn(I18nUtil.getMessage("main.fingerprint.failed"), audioFile.getName());
                log.info("该文件的 AcoustID 未关联到 MusicBrainz 录音信息");
                LogCollector.addLog("WARN", "⚠ " + I18nUtil.getMessage("main.acoustid.no.match", audioFile.getName()));

                // 如果没有锁定的专辑信息，则识别失败
                if (lockedAlbumTitle == null) {
                    log.info("建议：手动添加标签或等待 MusicBrainz 社区完善数据");

                    // 散落文件：复制单个文件到失败目录
                    if (isLooseFileInMonitorRoot) {
                        log.warn("散落文件识别失败: {}", audioFile.getName());
                        LogCollector.addLog("WARN", "✗ " + I18nUtil.getMessage("main.recognition.failed.loose", audioFile.getName()));
                        
                        // 先尝试处理部分识别文件
                        handlePartialRecognitionFile(audioFile);
                        
                        if (config.getFailedDirectory() != null && !config.getFailedDirectory().isEmpty()) {
                            try {
                                copyFailedFileToFailedDirectory(audioFile);
                            } catch (Exception e) {
                                log.error("复制失败文件到失败目录时出错: {}", e.getMessage());
                            }
                        }
                        processedLogger.markFileAsProcessed(
                            audioFile,
                            "UNKNOWN",
                            "识别失败 - 散落文件",
                            audioFile.getName(),
                            "Unknown Album"
                        );
                    } else {
                        // 正常专辑文件：复制整个专辑根目录到失败目录
                        log.warn("专辑识别失败: {}", albumRootDir.getName());
                        LogCollector.addLog("WARN", "✗ " + I18nUtil.getMessage("main.recognition.failed.album", albumRootDir.getName(), audioFile.getName()));
                        
                        // 先尝试处理部分识别文件
                        handlePartialRecognitionFile(audioFile);
                        
                        if (config.getFailedDirectory() != null && !config.getFailedDirectory().isEmpty()) {
                            try {
                                copyFailedFolderToFailedDirectory(albumRootDir);
                            } catch (Exception e) {
                                log.error("复制失败文件夹到失败目录时出错: {}", e.getMessage());
                            }
                        }

                        // 标记整个专辑根目录下的所有文件为"已处理"，避免继续识别
                        markAlbumAsProcessed(albumRootDir, "识别失败 - 整个专辑");
                    }

                    return true; // 识别失败，不重试但记录
                } else {
                    // 有锁定的专辑信息，创建基础metadata
                    log.warn("无法获取单曲详细信息，使用基础信息");
                    detailedMetadata = new MusicMetadata();

                    // 优先使用源文件的标签信息
                    MusicMetadata sourceTagsForFallback = tagWriter.readTags(audioFile);
                    String titleToUse;
                    String artistToUse;

                    if (sourceTagsForFallback != null &&
                        sourceTagsForFallback.getTitle() != null &&
                        !sourceTagsForFallback.getTitle().isEmpty()) {
                        // 使用源文件的标题
                        titleToUse = sourceTagsForFallback.getTitle();
                        log.info("使用源文件标签中的标题: {}", titleToUse);
                    } else {
                        // 使用文件名作为标题（去掉扩展名）
                        String fileName = audioFile.getName();
                        int lastDotIndex = fileName.lastIndexOf('.');
                        titleToUse = (lastDotIndex > 0) ? fileName.substring(0, lastDotIndex) : fileName;
                        log.info("使用文件名作为标题（已去除扩展名）: {}", titleToUse);
                    }

                    if (sourceTagsForFallback != null &&
                        sourceTagsForFallback.getArtist() != null &&
                        !sourceTagsForFallback.getArtist().isEmpty()) {
                        // 使用源文件的艺术家
                        artistToUse = sourceTagsForFallback.getArtist();
                        log.info("使用源文件标签中的艺术家: {}", artistToUse);
                    } else {
                        // 使用锁定的专辑艺术家
                        artistToUse = lockedAlbumArtist;
                        log.info("使用锁定的专辑艺术家: {}", artistToUse);
                    }

                    detailedMetadata.setTitle(titleToUse);
                    detailedMetadata.setArtist(artistToUse);
                    detailedMetadata.setAlbumArtist(lockedAlbumArtist);
                    detailedMetadata.setAlbum(lockedAlbumTitle);
                    detailedMetadata.setReleaseGroupId(lockedReleaseGroupId);
                    detailedMetadata.setReleaseDate(lockedReleaseDate);

                    // 同时保留源文件的其他标签信息（作曲、作词、歌词、风格等）
                    if (sourceTagsForFallback != null) {
                        if (sourceTagsForFallback.getComposer() != null && !sourceTagsForFallback.getComposer().isEmpty()) {
                            detailedMetadata.setComposer(sourceTagsForFallback.getComposer());
                        }
                        if (sourceTagsForFallback.getLyricist() != null && !sourceTagsForFallback.getLyricist().isEmpty()) {
                            detailedMetadata.setLyricist(sourceTagsForFallback.getLyricist());
                        }
                        if (sourceTagsForFallback.getLyrics() != null && !sourceTagsForFallback.getLyrics().isEmpty()) {
                            detailedMetadata.setLyrics(sourceTagsForFallback.getLyrics());
                        }
                        if (sourceTagsForFallback.getGenres() != null && !sourceTagsForFallback.getGenres().isEmpty()) {
                            detailedMetadata.setGenres(sourceTagsForFallback.getGenres());
                        }
                        if (sourceTagsForFallback.getDiscNo() != null && !sourceTagsForFallback.getDiscNo().isEmpty()) {
                            detailedMetadata.setDiscNo(sourceTagsForFallback.getDiscNo());
                        }
                        if (sourceTagsForFallback.getTrackNo() != null && !sourceTagsForFallback.getTrackNo().isEmpty()) {
                            detailedMetadata.setTrackNo(sourceTagsForFallback.getTrackNo());
                        }
                    }
                }
            } else {
                // 指纹识别成功，获取详细元数据
                AudioFingerprintService.RecordingInfo bestMatch = findBestRecordingMatch(
                    acoustIdResult.getRecordings(),
                    lockedReleaseGroupId
                );
                log.info("识别成功: {} - {}", bestMatch.getArtist(), bestMatch.getTitle());
                LogCollector.addLog("SUCCESS", I18nUtil.getMessage("main.identify.success", bestMatch.getArtist(), bestMatch.getTitle()));

                // 如果有锁定的专辑信息，传入1作为musicFilesInFolder以避免selectBestRelease被文件数量影响
                // 否则传入实际的文件数量
                int musicFilesParam = (lockedAlbumTitle != null) ? 1 : musicFilesInFolder;

                // 通过 MusicBrainz 获取详细元数据（包含作词、作曲、风格等）
                log.info("正在获取详细元数据...");
                detailedMetadata = musicBrainzClient.getRecordingById(bestMatch.getRecordingId(), musicFilesParam, lockedReleaseGroupId);

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

            // ===== 读取源文件已有标签并合并 =====
            // 在快速扫描锁定专辑但音频指纹数据库缺失的情况下，保留源文件的作曲、作词、歌词、风格等信息
            log.info("读取源文件已有标签信息...");
            MusicMetadata sourceMetadata = tagWriter.readTags(audioFile);
            if (sourceMetadata != null) {
                log.info("合并源文件标签信息...");
                detailedMetadata = mergeMetadata(sourceMetadata, detailedMetadata);
            } else {
                log.debug("源文件没有可读取的标签信息");
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
                log.info(I18nUtil.getMessage("main.cover.not.found"));
            }
            
            // 4.5 获取歌词 (LrcLib)
            log.info(I18nUtil.getMessage("main.getting.lyrics"));
            String lyrics = lyricsService.getLyrics(
                detailedMetadata.getTitle(),
                detailedMetadata.getArtist(),
                detailedMetadata.getAlbum(),
                0
            );
            if (lyrics != null && !lyrics.isEmpty()) {
                detailedMetadata.setLyrics(lyrics);
            } else {
                log.info(I18nUtil.getMessage("main.lyrics.not.found"));
            }
            
            // 5. 文件夹级别的专辑锁定处理
            // 注意：散落文件跳过专辑锁定和投票机制，直接处理
            if (isLooseFileInMonitorRoot) {
                log.info("散落文件保底处理：直接写入元数据（随缘模式）");
                processAndWriteFile(audioFile, detailedMetadata, coverArtData);
            } else if (lockedAlbumTitle != null) {
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

                // 关键修复：使用原子操作添加待处理文件，避免竞态条件
                folderAlbumCache.addPendingFileIfAbsent(folderPath, audioFile, detailedMetadata, coverArtData);

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

                    // 检查是否所有文件都已加入待处理队列但专辑仍未确定
                    // 这种情况可能发生在样本收集过程中部分文件识别失败
                    int pendingCount = folderAlbumCache.getPendingFileCount(folderPath);
                    if (pendingCount >= musicFilesInFolder) {
                        log.warn("所有文件已加入待处理队列但专辑仍未确定，强制处理");
                        forceProcessPendingFiles(folderPath, albumInfo);
                    }
                }
            }

            return true; // 处理成功

        } catch (java.io.IOException e) {
            // 网络异常（包括 SocketException），返回false以触发重试
            log.error(I18nUtil.getMessage("main.network.error"), audioFile.getName(), e.getMessage());
            log.info(I18nUtil.getMessage("main.retry.queued"));
            return false;

        } catch (Exception e) {
            // 其他异常（如识别失败），不重试，但必须记录到数据库避免静默丢失
            log.error(I18nUtil.getMessage("main.process.error"), audioFile.getName(), e);

            // 关键修复：记录失败文件到数据库，避免文件"静默丢失"
            try {
                processedLogger.markFileAsProcessed(
                    audioFile,
                    "FAILED",
                    "处理异常: " + e.getClass().getSimpleName(),
                    audioFile.getName(),
                    "Unknown Album"
                );
                log.info("已将异常失败文件记录到数据库: {}", audioFile.getName());
            } catch (Exception recordError) {
                log.error("记录异常失败文件到数据库失败: {} - {}", audioFile.getName(), recordError.getMessage());
            }

            return true; // 返回true避免重试（非网络问题）

        } finally {
            log.info("========================================");
        }
    }

    /**
     * 强制处理待处理文件（当专辑无法确定时使用最佳猜测）
     */
    private static void forceProcessPendingFiles(String folderPath, FolderAlbumCache.AlbumIdentificationInfo bestGuess) {
        log.info("========================================");
        log.info("强制处理待处理文件，使用最佳猜测专辑: {}", bestGuess.getAlbumTitle());
        log.info("========================================");

        FolderAlbumCache.CachedAlbumInfo forcedAlbum = new FolderAlbumCache.CachedAlbumInfo(
            bestGuess.getReleaseGroupId(),
            bestGuess.getAlbumTitle(),
            bestGuess.getAlbumArtist(),
            bestGuess.getTrackCount(),
            bestGuess.getReleaseDate(),
            0.5 // 低置信度
        );

        // 设置缓存以避免后续文件重复触发
        folderAlbumCache.setFolderAlbum(folderPath, forcedAlbum);

        processPendingFilesWithAlbum(folderPath, forcedAlbum);
    }
    
    /**
     * 处理并写入单个文件
     */
    private static void processAndWriteFile(File audioFile, MusicMetadata metadata, byte[] coverArtData) {
        try {
            log.info("正在写入文件标签: {}", audioFile.getName());
            boolean success = tagWriter.processFile(audioFile, metadata, coverArtData);
            
            if (success) {
                log.info("✓ 文件处理成功: {}", audioFile.getName());
                LogCollector.addLog("SUCCESS", "✓ " + I18nUtil.getMessage("main.processing.file", audioFile.getName()) + " - " + I18nUtil.getMessage("log.level.success"));
                
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
                LogCollector.addLog("ERROR", "✗ " + I18nUtil.getMessage("main.process.error", audioFile.getName()));
                // 关键修复：写入失败也要记录到数据库，避免文件"静默丢失"
                processedLogger.markFileAsProcessed(
                    audioFile,
                    "WRITE_FAILED",
                    metadata.getArtist() != null ? metadata.getArtist() : "Unknown Artist",
                    metadata.getTitle() != null ? metadata.getTitle() : audioFile.getName(),
                    metadata.getAlbum() != null ? metadata.getAlbum() : "Unknown Album"
                );
                log.info("已将写入失败文件记录到数据库: {}", audioFile.getName());
            }
        } catch (Exception e) {
            log.error(I18nUtil.getMessage("main.write.exception"), audioFile.getName(), e);
            // 关键修复：异常时也要记录到数据库，避免文件"静默丢失"
            try {
                processedLogger.markFileAsProcessed(
                    audioFile,
                    "EXCEPTION",
                    metadata.getArtist() != null ? metadata.getArtist() : "Unknown Artist",
                    metadata.getTitle() != null ? metadata.getTitle() : audioFile.getName(),
                    metadata.getAlbum() != null ? metadata.getAlbum() : "Unknown Album"
                );
                log.info("已将异常文件记录到数据库: {}", audioFile.getName());
            } catch (Exception recordError) {
                log.error("记录异常文件到数据库失败: {} - {}", audioFile.getName(), recordError.getMessage());
            }
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
                MusicMetadata metadata = (MusicMetadata) pending.getMetadata();
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
                    MusicMetadata metadata = (MusicMetadata) pending.getMetadata();
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
     * 0.5. 如果有锁定的专辑ID，检查是否已经为这个专辑获取过封面（跨文件夹复用）
     * 1. 尝试从网络下载(使用缓存)
     * 2. 如果下载失败,检查音频文件是否自带封面
     * 3. 如果没有自带封面,在音频文件所在目录查找cover图片
     *
     * @param audioFile 音频文件
     * @param metadata 元数据（包含封面URL）
     * @param lockedReleaseGroupId 锁定的专辑release group ID，如果非null则优先使用此专辑的封面
     */
    private static byte[] getCoverArtWithFallback(File audioFile, MusicMetadata metadata, String lockedReleaseGroupId) {
        byte[] coverArtData = null;
        String folderPath = audioFile.getParentFile().getAbsolutePath();
        
        // 检查是否为散落文件
        boolean isLooseFile = isLooseFileInMonitorRoot(audioFile);

        // 策略0: 检查文件夹级别缓存（散落文件跳过此策略）
        if (!isLooseFile) {
            coverArtData = folderCoverCache.get(folderPath);
            if (coverArtData != null && coverArtData.length > 0) {
                log.info("策略0: 使用同文件夹已获取的封面");
                return coverArtData;
            }
        } else {
            log.info("散落文件跳过文件夹级别缓存，独立获取封面");
        }

        // 策略0.5: 如果有锁定的专辑ID，检查 CoverArtCache 中是否已经为这个专辑获取过封面
        if (lockedReleaseGroupId != null) {
            coverArtData = coverArtCache.getCachedCoverByReleaseGroupId(lockedReleaseGroupId);

            if (coverArtData != null && coverArtData.length > 0) {
                log.info("策略0.5: 使用已缓存的锁定专辑封面 (Release Group ID: {})", lockedReleaseGroupId);
                // 只有非散落文件才缓存到文件夹级别
                if (!isLooseFile) {
                    folderCoverCache.put(folderPath, coverArtData);
                    log.info("已缓存到文件夹级别");
                }
                return coverArtData;
            }
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
                log.info("✓ 成功从网络下载封面");
                
                // 只有非散落文件才缓存到文件夹级别
                if (!isLooseFile) {
                    folderCoverCache.put(folderPath, coverArtData);
                    log.info("已缓存到文件夹级别");
                }

                // 如果有锁定的专辑ID，同时缓存到 Release Group ID 级别，供其他文件夹复用
                if (lockedReleaseGroupId != null) {
                    coverArtCache.cacheCoverByReleaseGroupId(lockedReleaseGroupId, coverArtData);
                    log.info("已将封面缓存到专辑级别 (Release Group ID: {})", lockedReleaseGroupId);
                }

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
            log.info("✓ 成功从音频文件提取封面");
            
            // 只有非散落文件才缓存到文件夹级别
            if (!isLooseFile) {
                folderCoverCache.put(folderPath, coverArtData);
                log.info("已缓存到文件夹级别");
            }
            
            return coverArtData;
        }
        log.info("✗ 音频文件无封面,尝试降级策略");
        
        // 策略3: 在音频文件所在目录查找cover图片
        log.info("策略3: 在音频文件所在目录查找cover图片");
        coverArtData = findCoverInDirectory(audioFile.getParentFile());
        
        if (coverArtData != null && coverArtData.length > 0) {
            log.info("✓ 成功从目录找到封面图片");
            
            // 只有非散落文件才缓存到文件夹级别
            if (!isLooseFile) {
                folderCoverCache.put(folderPath, coverArtData);
                log.info("已缓存到文件夹级别");
            }
            
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
     * - 如果父文件夹是多CD专辑的子文件夹（如 Disc 1, CD1），向上获取专辑根目录
     * - 如果父文件夹是专辑根目录，递归统计（支持多CD专辑）
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
            // 从父文件夹向上查找专辑根目录（监控目录的第一级子目录）
            // 创建一个临时文件来调用 getAlbumRootDirectory
            File tempFile = new File(parentDir, "temp");
            File albumRootDir = getAlbumRootDirectory(tempFile);
            
            // 递归统计专辑根目录下的所有音乐文件
            int count = countMusicFilesRecursively(albumRootDir);
            log.info("专辑文件夹 {} 中共有 {} 个音乐文件（包括子文件夹）", albumRootDir.getName(), count);
            return count;
        }
    }
    
    /**
     * 获取专辑根目录
     * 规则：监控目录下的第一级子目录即为专辑根目录
     * 例如：监控目录/Artist - Album/Disc 1/01.flac -> 专辑根目录为 监控目录/Artist - Album
     */
    private static File getAlbumRootDirectory(File audioFile) {
        try {
            String monitorDirPath = new File(config.getMonitorDirectory()).getCanonicalPath();
            File current = audioFile.getParentFile();
            
            // 向上查找，直到找到监控目录的直接子目录
            while (current != null) {
                File parent = current.getParentFile();
                if (parent != null) {
                    String parentPath = parent.getCanonicalPath();
                    if (parentPath.equals(monitorDirPath)) {
                        // current 是监控目录的直接子目录，即专辑根目录
                        return current;
                    }
                }
                current = parent;
            }
            
            // 如果找不到，返回文件所在目录（保底）
            return audioFile.getParentFile();
            
        } catch (java.io.IOException e) {
            log.warn("获取专辑根目录失败: {}", e.getMessage());
            return audioFile.getParentFile();
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
     * 检测是否为散落在监控目录根目录的文件
     * 用于保底处理机制
     */
    private static boolean isLooseFileInMonitorRoot(File audioFile) {
        File parentDir = audioFile.getParentFile();
        if (parentDir == null) {
            return false;
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
        
        // 只要父文件夹就是监控目录根目录,就认为是散落文件
        return parentDirPath.equals(monitorDirPath);
    }
    
    /**
     * 检查是否有完整的标签
     */
    private static boolean hasCompleteTags(MusicMetadata metadata) {
        return metadata.getTitle() != null && !metadata.getTitle().isEmpty() &&
               metadata.getArtist() != null && !metadata.getArtist().isEmpty() &&
               metadata.getAlbum() != null && !metadata.getAlbum().isEmpty();
    }
    
    /**
     * 将 AcoustID RecordingInfo 转换为 MusicBrainz MusicMetadata
     */
    private static MusicMetadata convertToMusicMetadata(
            AudioFingerprintService.RecordingInfo recordingInfo) {
        
        MusicMetadata metadata = new MusicMetadata();
        metadata.setRecordingId(recordingInfo.getRecordingId());
        metadata.setTitle(recordingInfo.getTitle());
        metadata.setArtist(recordingInfo.getArtist());
        metadata.setAlbum(recordingInfo.getAlbum());
        return metadata;
    }
    
    /**
     * 处理部分识别的文件(有标签或封面但指纹识别失败)
     * 将文件复制到部分识别目录，保留原始的文件夹结构，并尝试内嵌文件夹封面
     */
    private static void handlePartialRecognitionFile(File audioFile) {
        if (config.getPartialDirectory() == null || config.getPartialDirectory().isEmpty()) {
            return; // 未配置部分识别目录，跳过
        }
        
        try {
            // 检查是否有内嵌封面
            boolean hasEmbeddedCover = tagWriter.hasEmbeddedCover(audioFile);
            
            // 检查文件夹中是否有封面
            byte[] folderCover = findCoverInDirectory(audioFile.getParentFile());
            boolean hasFolderCover = (folderCover != null && folderCover.length > 0);
            
            // 封面是必需条件：如果既没有内嵌封面也没有文件夹封面，不处理
            if (!hasEmbeddedCover && !hasFolderCover) {
                log.info(I18nUtil.getMessage("main.partial.recognition.no.cover"));
                return;
            }
            
            // 检查是否有部分标签信息
            boolean hasPartialTags = tagWriter.hasPartialTags(audioFile);
            
            log.info("========================================");
            log.info(I18nUtil.getMessage("main.partial.recognition.detected"));
            LogCollector.addLog("INFO", "✓ " + I18nUtil.getMessage("main.partial.recognition.detected") + ": " + audioFile.getName());
            log.info(I18nUtil.getMessage("main.partial.recognition.has.embedded.cover") + ": {}", hasEmbeddedCover);
            log.info(I18nUtil.getMessage("main.partial.recognition.has.folder.cover") + ": {}", hasFolderCover);
            log.info(I18nUtil.getMessage("main.partial.recognition.has.tags") + ": {}", hasPartialTags);
            
            // 计算相对于监控目录的相对路径，保留文件夹结构
            String monitorDirPath = new File(config.getMonitorDirectory()).getCanonicalPath();
            String audioFilePath = audioFile.getCanonicalPath();
            
            // 获取相对路径
            String relativePath;
            if (audioFilePath.startsWith(monitorDirPath)) {
                relativePath = audioFilePath.substring(monitorDirPath.length());
                // 去掉开头的分隔符
                if (relativePath.startsWith(File.separator)) {
                    relativePath = relativePath.substring(1);
                }
            } else {
                // 如果无法获取相对路径，退回到只使用文件名
                relativePath = audioFile.getName();
            }
            
            // 构建目标文件路径，保留文件夹结构
            File targetFile = new File(config.getPartialDirectory(), relativePath);
            
            // 创建目标目录
            if (!targetFile.getParentFile().exists()) {
                targetFile.getParentFile().mkdirs();
            }
            
            // 如果目标文件已存在，跳过复制
            if (targetFile.exists()) {
                log.debug("部分识别文件已存在，跳过复制: {}", targetFile.getAbsolutePath());
                return;
            }
            
            // 复制文件到部分识别目录
            log.info(I18nUtil.getMessage("main.partial.recognition.copying") + ": {}", targetFile.getAbsolutePath());
            Files.copy(audioFile.toPath(), targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
            // 如果文件夹有封面但文件没有内嵌封面，则内嵌封面
            if (hasFolderCover && !hasEmbeddedCover) {
                log.info(I18nUtil.getMessage("main.partial.recognition.embedding.cover"));
                LogCollector.addLog("INFO", "  → " + I18nUtil.getMessage("main.partial.recognition.embedding.cover"));
                boolean embedSuccess = tagWriter.embedFolderCover(targetFile, folderCover);
                if (embedSuccess) {
                    log.info(I18nUtil.getMessage("main.partial.recognition.embed.success"));
                    LogCollector.addLog("SUCCESS", "  " + I18nUtil.getMessage("main.partial.recognition.embed.success"));
                } else {
                    log.warn(I18nUtil.getMessage("main.partial.recognition.embed.failed"));
                    LogCollector.addLog("WARN", "  " + I18nUtil.getMessage("main.partial.recognition.embed.failed"));
                }
            }
            
            log.info(I18nUtil.getMessage("main.partial.recognition.complete") + ": {}", targetFile.getAbsolutePath());
            LogCollector.addLog("SUCCESS", I18nUtil.getMessage("main.partial.recognition.complete") + ": " + relativePath);
            log.info("========================================");
            
        } catch (Exception e) {
            log.error(I18nUtil.getMessage("main.partial.recognition.failed") + ": {}", audioFile.getName(), e);
        }
    }
    
    /**
     * 复制失败的单个文件到失败目录
     */
    private static void copyFailedFileToFailedDirectory(File audioFile) throws IOException {
        if (audioFile == null || !audioFile.exists()) {
            log.warn("文件不存在，无法复制");
            return;
        }
        
        String fileName = audioFile.getName();
        File targetFile = new File(config.getFailedDirectory(), fileName);
        
        // 检查是否已经复制过该文件
        if (targetFile.exists()) {
            log.debug("失败文件已存在，跳过复制: {}", targetFile.getAbsolutePath());
            return;
        }
        
        log.info("========================================");
        log.info("识别失败，复制单个文件到失败目录");
        log.info("源文件: {}", audioFile.getAbsolutePath());
        log.info("目标位置: {}", targetFile.getAbsolutePath());
        
        try {
            Files.copy(audioFile.toPath(), targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("文件复制完成: {}", targetFile.getAbsolutePath());
        } catch (IOException e) {
            log.error("复制文件失败: {} - {}", fileName, e.getMessage());
            throw e;
        }
        
        log.info("========================================");
    }
    
    /**
     * 复制失败的专辑根目录到失败目录
     * 保留文件夹结构（包括所有子文件夹如 Disc 1, Disc 2），方便用户手动处理
     */
    private static void copyFailedFolderToFailedDirectory(File albumRootFolder) throws IOException {
        if (albumRootFolder == null || !albumRootFolder.exists()) {
            log.warn("专辑根目录不存在，无法复制");
            return;
        }
        
        // 构建目标路径：失败目录/专辑根文件夹名
        String folderName = albumRootFolder.getName();
        File targetFolder = new File(config.getFailedDirectory(), folderName);
        
        // 检查是否已经复制过该文件夹
        if (targetFolder.exists()) {
            log.debug("失败文件夹已存在，跳过复制: {}", targetFolder.getAbsolutePath());
            return;
        }
        
        log.info("========================================");
        log.info("识别失败，复制整个专辑根目录到失败目录");
        log.info("源文件夹: {}", albumRootFolder.getAbsolutePath());
        log.info("目标位置: {}", targetFolder.getAbsolutePath());
        
        // 递归复制整个专辑根目录（包括所有子文件夹）
        int[] counts = copyDirectoryRecursively(albumRootFolder.toPath(), targetFolder.toPath());
        int copiedCount = counts[0];
        int skippedCount = counts[1];
        
        log.info("文件夹复制完成: 成功 {} 个文件, 跳过 {} 个", copiedCount, skippedCount);
        log.info("失败文件夹位置: {}", targetFolder.getAbsolutePath());
        log.info("========================================");
    }
    
    /**
     * 递归复制目录及其所有内容
     * @return int[2] - [复制成功数, 跳过数]
     */
    /**
     * 标记专辑根目录下的所有音频文件为已处理
     * 用于识别失败后，避免继续处理同一专辑的其他文件
     */
    private static void markAlbumAsProcessed(File albumRootDir, String reason) {
        if (albumRootDir == null || !albumRootDir.exists()) {
            return;
        }
        
        log.info("========================================");
        log.info("标记整个专辑为已处理: {}", albumRootDir.getName());
        
        int markedCount = 0;
        try {
            // 递归收集专辑根目录下的所有音频文件
            java.util.List<File> audioFiles = new java.util.ArrayList<>();
            collectAudioFilesForMarking(albumRootDir, audioFiles);
            
            // 标记所有音频文件为已处理
            for (File audioFile : audioFiles) {
                try {
                    processedLogger.markFileAsProcessed(
                        audioFile,
                        "UNKNOWN",
                        reason,
                        audioFile.getName(),
                        "Unknown Album"
                    );
                    markedCount++;
                } catch (Exception e) {
                    log.warn("标记文件失败: {} - {}", audioFile.getName(), e.getMessage());
                }
            }
            
            log.info("已标记 {} 个音频文件为已处理，队列中的其他文件将被跳过", markedCount);
            log.info("========================================");
            
        } catch (Exception e) {
            log.error("标记专辑文件时出错: {}", e.getMessage());
        }
    }
    
    /**
     * 递归收集专辑根目录下的所有音频文件
     */
    private static void collectAudioFilesForMarking(File directory, java.util.List<File> result) {
        if (!directory.isDirectory()) {
            return;
        }
        
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        
        for (File file : files) {
            if (file.isDirectory()) {
                // 递归进入子文件夹
                collectAudioFilesForMarking(file, result);
            } else if (isMusicFile(file)) {
                // 添加音频文件
                result.add(file);
            }
        }
    }
    
    /**
     * 递归复制目录及其所有内容
     * @return int[2] - [复制成功数, 跳过数]
     */
    private static int[] copyDirectoryRecursively(Path source, Path target) throws IOException {
        int[] counts = new int[2]; // [copiedCount, skippedCount]
        
        Files.walkFileTree(source, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Path targetDir = target.resolve(source.relativize(dir));
                try {
                    Files.createDirectories(targetDir);
                } catch (IOException e) {
                    log.warn("无法创建目录: {} - {}", targetDir, e.getMessage());
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Path targetFile = target.resolve(source.relativize(file));
                try {
                    Files.copy(file, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    counts[0]++; // copiedCount
                    log.debug("已复制: {}", file.getFileName());
                } catch (IOException e) {
                    log.warn("复制文件失败: {} - {}", file.getFileName(), e.getMessage());
                    counts[1]++; // skippedCount
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        
        return counts;
    }
    
    /**
     * 优雅关闭所有服务
     */
    private static void shutdown() {
        log.info(I18nUtil.getMessage("app.shutting.down"));

        try {
            // 按依赖关系逆序关闭服务
            
            // 关闭 Web 服务器
            if (webServer != null && webServer.isRunning()) {
                try {
                    webServer.stop();
                } catch (Exception e) {
                    log.warn("关闭 Web 服务器时出错", e);
                }
            }
            
            if (fileMonitor != null) {
                fileMonitor.stop();
            }

            // 在关闭前处理所有待处理文件，避免文件丢失
            if (folderAlbumCache != null) {
                processAllPendingFilesBeforeShutdown();
            }

            if (fingerprintService != null) {
                fingerprintService.close();
            }

            if (musicBrainzClient != null) {
                try {
                    musicBrainzClient.close();
                } catch (IOException e) {
                    log.warn("关闭 MusicBrainz 客户端时出错", e);
                }
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

            log.info(I18nUtil.getMessage("app.shutdown.complete"));
        } catch (Exception e) {
            log.error(I18nUtil.getMessage("app.shutdown.error"), e);
        }
    }

    /**
     * 关闭前处理所有待处理文件
     * 避免程序关闭时待处理队列中的文件丢失
     */
    private static void processAllPendingFilesBeforeShutdown() {
        java.util.Set<String> foldersWithPending = folderAlbumCache.getFoldersWithPendingFiles();

        if (foldersWithPending.isEmpty()) {
            log.info(I18nUtil.getMessage("app.no.pending.files"));
            return;
        }

        log.info("========================================");
        log.info(I18nUtil.getMessage("app.process.pending.files"), foldersWithPending.size());
        log.info("========================================");

        for (String folderPath : foldersWithPending) {
            java.util.List<FolderAlbumCache.PendingFile> pendingFiles = folderAlbumCache.getPendingFiles(folderPath);
            if (pendingFiles == null || pendingFiles.isEmpty()) {
                continue;
            }

            log.info("处理文件夹: {} ({} 个待处理文件)", new File(folderPath).getName(), pendingFiles.size());

            // 检查是否已有缓存的专辑信息
            FolderAlbumCache.CachedAlbumInfo cachedAlbum = folderAlbumCache.getFolderAlbum(folderPath, pendingFiles.size());

            if (cachedAlbum != null) {
                // 有缓存的专辑信息，使用它处理
                log.info("使用缓存的���辑信息: {}", cachedAlbum.getAlbumTitle());
                processPendingFilesWithAlbum(folderPath, cachedAlbum);
            } else {
                // 没有缓存，使用第一个待处理文件的元数据作为最佳猜测
                FolderAlbumCache.PendingFile firstPending = pendingFiles.get(0);
                MusicMetadata metadata = (MusicMetadata) firstPending.getMetadata();

                if (metadata != null && metadata.getAlbum() != null) {
                    log.warn("没有确定的专辑信息，使用第一个文件的元数据作为最佳猜测: {}", metadata.getAlbum());

                    FolderAlbumCache.CachedAlbumInfo guessedAlbum = new FolderAlbumCache.CachedAlbumInfo(
                        metadata.getReleaseGroupId(),
                        metadata.getAlbum(),
                        metadata.getAlbumArtist() != null ? metadata.getAlbumArtist() : metadata.getArtist(),
                        metadata.getTrackCount(),
                        metadata.getReleaseDate(),
                        0.3 // 低置信度
                    );

                    processPendingFilesWithAlbum(folderPath, guessedAlbum);
                } else {
                    // 元数据也没有，直接写入每个文件自己的元数据
                    log.warn("无法确定专辑信息，直接写入每个文件自己的元数据");
                    for (FolderAlbumCache.PendingFile pending : pendingFiles) {
                        try {
                            MusicMetadata fileMetadata = (MusicMetadata) pending.getMetadata();
                            processAndWriteFile(pending.getAudioFile(), fileMetadata, pending.getCoverArtData());
                        } catch (Exception e) {
                            log.error("关闭前处理文件失败: {}", pending.getAudioFile().getName(), e);
                            // 关键修复：记录失败文件到数据库，避免文件"静默丢失"
                            try {
                                processedLogger.markFileAsProcessed(
                                    pending.getAudioFile(),
                                    "FAILED",
                                    "关闭前处理失败: " + e.getClass().getSimpleName(),
                                    pending.getAudioFile().getName(),
                                    "Unknown Album"
                                );
                                log.info("已将关闭前失败文件记录到数据库: {}", pending.getAudioFile().getName());
                            } catch (Exception recordError) {
                                log.error("记录关闭前失败文件到数据库失败: {} - {}", pending.getAudioFile().getName(), recordError.getMessage());
                            }
                        }
                    }
                    folderAlbumCache.clearPendingFiles(folderPath);
                }
            }
        }

        log.info("========================================");
        log.info("关闭前待处理文件处理完成");
        log.info("========================================");
    }
    /**
     * 从 AcoustID 返回的多个录音中选择最佳匹配
     * 优先选择与已锁定专辑 Release Group ID 匹配的录音
     */
    private static AudioFingerprintService.RecordingInfo findBestRecordingMatch(
            java.util.List<AudioFingerprintService.RecordingInfo> recordings,
            String lockedReleaseGroupId) {

        if (lockedReleaseGroupId != null && !lockedReleaseGroupId.isEmpty()) {
            for (AudioFingerprintService.RecordingInfo recording : recordings) {
                if (recording.getReleaseGroups() != null) {
                    for (AudioFingerprintService.ReleaseGroupInfo rg : recording.getReleaseGroups()) {
                        if (lockedReleaseGroupId.equals(rg.getId())) {
                            log.info("找到与锁定专辑匹配的录音: {} - {}",
                                recording.getArtist(), recording.getTitle());
                            return recording;
                        }
                    }
                }
            }
            log.warn("未找到与锁定专辑 Release Group ID {} 匹配的录音，将使用最佳匹配", lockedReleaseGroupId);
        }

        // 如果没有锁定专辑或未找到匹配，返回第一个（匹配度最高）
        return recordings.get(0);
    }

    /**
     * 合并元数据：保留源文件中已有的标签信息
     *
     * 策略：
     * - 歌曲名、专辑名、专辑艺术家：使用新识别的数据（来自快速扫描或指纹识别）
     * - 作曲家、作词家、歌词、风格：优先使用新识别的数据，如果新数据为空则保留源文件的
     *
     * @param sourceMetadata 源文件已有的元数据
     * @param newMetadata 新识别的元数据
     * @return 合并后的元数据
     */
    private static MusicMetadata mergeMetadata(MusicMetadata sourceMetadata, MusicMetadata newMetadata) {
        if (sourceMetadata == null) {
            return newMetadata;
        }

        if (newMetadata == null) {
            return sourceMetadata;
        }

        // 创建结果对象，基于新识别的元数据
        MusicMetadata merged = newMetadata;

        // 保留源文件中的作曲家信息（如果新数据没有）
        if ((merged.getComposer() == null || merged.getComposer().isEmpty()) &&
            (sourceMetadata.getComposer() != null && !sourceMetadata.getComposer().isEmpty())) {
            log.info("保留源文件的作曲家信息: {}", sourceMetadata.getComposer());
            merged.setComposer(sourceMetadata.getComposer());
        }

        // 保留源文件中的作词家信息（如果新数据没有）
        if ((merged.getLyricist() == null || merged.getLyricist().isEmpty()) &&
            (sourceMetadata.getLyricist() != null && !sourceMetadata.getLyricist().isEmpty())) {
            log.info("保留源文件的作词家信息: {}", sourceMetadata.getLyricist());
            merged.setLyricist(sourceMetadata.getLyricist());
        }

        // 保留源文件中的歌词（如果新数据没有）
        if ((merged.getLyrics() == null || merged.getLyrics().isEmpty()) &&
            (sourceMetadata.getLyrics() != null && !sourceMetadata.getLyrics().isEmpty())) {
            log.info("保留源文件的歌词信息");
            merged.setLyrics(sourceMetadata.getLyrics());
        }

        // 保留源文件中的风格信息（如果新数据没有）
        if ((merged.getGenres() == null || merged.getGenres().isEmpty()) &&
            (sourceMetadata.getGenres() != null && !sourceMetadata.getGenres().isEmpty())) {
            log.info("保留源文件的风格信息: {}", sourceMetadata.getGenres());
            merged.setGenres(sourceMetadata.getGenres());
        }

        return merged;
    }
}