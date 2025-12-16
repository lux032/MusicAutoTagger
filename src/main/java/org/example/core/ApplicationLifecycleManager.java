package org.example.core;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.example.config.MusicConfig;
import org.example.service.*;
import org.example.util.FileSystemUtils;
import org.example.util.I18nUtil;
import org.example.web.WebServer;

import java.io.IOException;

/**
 * 应用程序生命周期管理器
 * 负责初始化和关闭所有服务
 */
@Slf4j
@Getter
public class ApplicationLifecycleManager {
    
    private final MusicConfig config;
    
    // 基础服务实例
    private DatabaseService databaseService;
    private FileMonitorService fileMonitor;
    private AudioFingerprintService fingerprintService;
    private MusicBrainzClient musicBrainzClient;
    private TagWriterService tagWriter;
    private LyricsService lyricsService;
    private ProcessedFileLogger processedLogger;
    private CoverArtCache coverArtCache;
    private FolderAlbumCache folderAlbumCache;
    private QuickScanService quickScanService;
    private DurationSequenceService durationSequenceService;
    private WebServer webServer;
    
    // 新增的服务实例
    private CoverArtService coverArtService;
    private FileSystemUtils fileSystemUtils;
    private FailedFileHandler failedFileHandler;
    private AlbumBatchProcessor albumBatchProcessor;
    private AudioFileProcessorService audioFileProcessorService;
    
    public ApplicationLifecycleManager(MusicConfig config) {
        this.config = config;
    }
    
    /**
     * 初始化所有服务
     */
    public void initializeServices() throws IOException {
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
        durationSequenceService = new DurationSequenceService();
        
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
        
        // Level 3: 初始化新增的服务
        log.info(I18nUtil.getMessage("app.init.cover.art.service"));
        coverArtService = new CoverArtService(coverArtCache, musicBrainzClient);
        
        log.info(I18nUtil.getMessage("app.init.filesystem.utils"));
        fileSystemUtils = new FileSystemUtils(config);
        
        log.info(I18nUtil.getMessage("app.init.album.batch.processor"));
        albumBatchProcessor = new AlbumBatchProcessor(config, folderAlbumCache, tagWriter, processedLogger);
        
        log.info(I18nUtil.getMessage("app.init.failed.file.handler"));
        failedFileHandler = new FailedFileHandler(config, tagWriter, coverArtService, processedLogger, fileSystemUtils);
        
        log.info(I18nUtil.getMessage("app.init.audio.file.processor"));
        audioFileProcessorService = new AudioFileProcessorService(
            config,
            fingerprintService,
            musicBrainzClient,
            tagWriter,
            lyricsService,
            processedLogger,
            quickScanService,
            coverArtService,
            albumBatchProcessor,
            failedFileHandler,
            fileSystemUtils,
            folderAlbumCache
        );
        
        // Level 4: 初始化文件监控服务
        log.info(I18nUtil.getMessage("app.init.file.monitor"));
        fileMonitor = new FileMonitorService(config, processedLogger);
        fileMonitor.setFileReadyCallbackWithResult(audioFileProcessorService::processAudioFile);
        
        log.info(I18nUtil.getMessage("app.all.services.ready"));
    }
    
    /**
     * 启动 Web 监控面板
     */
    public void startWebServer() {
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
    public void startMonitoring() {
        log.info(I18nUtil.getMessage("monitor.start.monitoring") + "...");
        fileMonitor.start();
    }
    
    /**
     * 检查 fpcalc 工具是否可用
     */
    public boolean isFpcalcAvailable() {
        return fingerprintService.isFpcalcAvailable();
    }
    
    /**
     * 优雅关闭所有服务
     */
    public void shutdown() {
        log.info(I18nUtil.getMessage("app.shutting.down"));

        try {
            // 按依赖关系逆序关闭服务
            
            // 关闭 Web 服务器
            if (webServer != null && webServer.isRunning()) {
                try {
                    webServer.stop();
                } catch (Exception e) {
                    log.warn(I18nUtil.getMessage("app.shutdown.web.server.error"), e);
                }
            }
            
            if (fileMonitor != null) {
                fileMonitor.stop();
            }

            // 在关闭前处理所有待处理文件，避免文件丢失
            if (albumBatchProcessor != null) {
                albumBatchProcessor.processAllPendingFilesBeforeShutdown();
            }

            if (fingerprintService != null) {
                fingerprintService.close();
            }

            if (musicBrainzClient != null) {
                try {
                    musicBrainzClient.close();
                } catch (IOException e) {
                    log.warn(I18nUtil.getMessage("app.shutdown.musicbrainz.error"), e);
                }
            }

            if (lyricsService != null) {
                lyricsService.close();
            }

            if (coverArtCache != null) {
                CoverArtCache.CacheStatistics stats = coverArtCache.getStatistics();
                log.info(I18nUtil.getMessage("app.cover.cache.statistics"), stats);
                coverArtCache.close();
            }

            if (folderAlbumCache != null) {
                FolderAlbumCache.CacheStatistics stats = folderAlbumCache.getStatistics();
                log.info(I18nUtil.getMessage("app.folder.album.cache.statistics"), stats);
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
}