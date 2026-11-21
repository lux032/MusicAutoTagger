package com.lux032.musicautotagger.core;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.service.*;
import com.lux032.musicautotagger.util.FileSystemUtils;
import com.lux032.musicautotagger.util.I18nUtil;
import com.lux032.musicautotagger.web.WebServer;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 应用程序生命周期管理器
 * 负责初始化和关闭所有服务
 */
@Slf4j
@Getter
public class ApplicationLifecycleManager {
    
    private final MusicConfig config;
    private final AtomicBoolean shutdownCalled = new AtomicBoolean(false);

    // 基础服务实例
    private DatabaseService databaseService;
    private FileMonitorService fileMonitor;
    private AudioFingerprintService fingerprintService;
    private MusicBrainzClient musicBrainzClient;
    private TagWriterService tagWriter;
    private LyricsService lyricsService;
    private LyricsBackfillService lyricsBackfillService;
    private AlbumArtistRefreshService albumArtistRefreshService;
    private ProcessedFileLogger processedLogger;
    private CoverArtCache coverArtCache;
    private FolderAlbumCache folderAlbumCache;
    private QuickScanService quickScanService;
    private DurationSequenceService durationSequenceService;
    private WebServer webServer;
    
    // 新增的服务实例
    private CoverArtService coverArtService;
    private CoverBackfillService coverBackfillService;
    private CoverCandidateService coverCandidateService;
    private CoverSearchService coverSearchService;
    private CoverMissingService coverMissingService;
    private FileSystemUtils fileSystemUtils;
    private FailedFileHandler failedFileHandler;
    private AlbumBatchProcessor albumBatchProcessor;
    private AudioFileProcessorService audioFileProcessorService;

    // 阶段六：待人工确认链路
    private ReviewQueueService reviewQueueService;
    private ReviewResolutionService reviewResolutionService;
    private RecoveryService recoveryService;
    private ProcessedRecordService processedRecordService;
    
    public ApplicationLifecycleManager(MusicConfig config) {
        this.config = config;
    }
    
    /**
     * 初始化所有服务
     */
    public void initializeServices() throws IOException {
        log.info(I18nUtil.getMessage("app.init.services"));

        // Level 1: 初始化数据库服务 (SQLite 默认 / MySQL)
        if (DatabaseService.usesDatabase(config)) {
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
        coverArtCache = new CoverArtCache(databaseService, cacheDir, config);
        
        // Level 2: 初始化其他服务
        log.info(I18nUtil.getMessage("app.init.other.services"));
        fingerprintService = new AudioFingerprintService(config);
        musicBrainzClient = new MusicBrainzClient(config);
        lyricsService = new LyricsService(config);
        tagWriter = new TagWriterService(config);
        lyricsBackfillService = new LyricsBackfillService(config, lyricsService, tagWriter);
        albumArtistRefreshService = new AlbumArtistRefreshService(config, musicBrainzClient, tagWriter, processedLogger);
        
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
        coverArtService.setFfmpegPath(config.getAudioNormalizeFfmpegPath());
        coverCandidateService = new CoverCandidateService(
            config, musicBrainzClient, coverArtService, coverArtCache);
        
        log.info(I18nUtil.getMessage("app.init.filesystem.utils"));
        fileSystemUtils = new FileSystemUtils(config);
        
        log.info(I18nUtil.getMessage("app.init.album.batch.processor"));
        albumBatchProcessor = new AlbumBatchProcessor(config, folderAlbumCache, tagWriter, processedLogger, coverArtService);
        albumBatchProcessor.setMusicBrainzClient(musicBrainzClient);

        // 待人工确认队列（阶段六 #18/#19）
        // 队列本身总是初始化（否则重启后已有条目永远无人能处理），
        // 是否把新的未确定专辑放进去则由 review.enabled 控制。
        reviewQueueService = new ReviewQueueService(config);
        reviewResolutionService = new ReviewResolutionService(
            config,
            reviewQueueService,
            folderAlbumCache,
            albumBatchProcessor,
            musicBrainzClient,
            durationSequenceService,
            processedLogger
        );
        albumBatchProcessor.setReviewQueueService(reviewQueueService);
        // 人工确认的专辑锁定必须跨重启生效：
        // FolderAlbumCache 是纯内存的，不回放的话，重启后同目录新增的文件
        // 会重新走自动匹配，可能选出与人工确认不同的专辑。
        reviewQueueService.restoreManualLocks(folderAlbumCache);
        if (config.isReviewEnabled()) {
            log.info("待人工确认链路已启用（专辑无法确定时不写标签，等待面板确认），当前待确认 {} 条",
                reviewQueueService.countPending());
        } else {
            log.info("待人工确认链路未启用（review.enabled=false），专辑未确定时仍按合成信息直接归档");
        }
        
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
        audioFileProcessorService.setReviewQueueService(reviewQueueService);

        // 历史记录的封面回填（设置面板里手动触发，不自动跑）
        coverBackfillService = new CoverBackfillService(processedLogger, musicBrainzClient, coverArtService);

        // 部分识别 / 失败目录的人工重新识别入口
        recoveryService = new RecoveryService(
            config, audioFileProcessorService, processedLogger, reviewQueueService, folderAlbumCache,
            failedFileHandler, fileSystemUtils, tagWriter, fingerprintService, coverCandidateService);

        // 「缺少封面」多源搜图 + 处置 + 手动库扫描服务
        coverSearchService = new CoverSearchService(config, reviewQueueService, coverCandidateService);
        coverMissingService = new CoverMissingService(
            config, reviewQueueService, folderAlbumCache, albumBatchProcessor,
            tagWriter, coverCandidateService, processedLogger);
        coverMissingService.setRecoveryService(recoveryService);
        coverMissingService.setCoverSearchService(coverSearchService);
        if (recoveryService.getSearchQueueService() != null) {
            recoveryService.getSearchQueueService().setCoverSearchService(coverSearchService);
        }

        // 「锁定专辑缺曲」条目入队后自动排入统一 LLM 联网搜索队列（低优先级，不抢占手动触发）。
        reviewQueueService.setMissingTracksEnqueuedListener(this::scheduleMissingTracksOnlineSearch);
        // 「缺少封面」新入库拦截条目入队后自动排入统一搜索队列找封面（库内扫描不自动触发）。
        reviewQueueService.setMissingCoverEnqueuedListener(this::scheduleMissingCoverSearch);
        // 上次关机前已入队、但自动搜索尚未真正执行的缺曲/缺封面条目：启动时补跑
        for (com.lux032.musicautotagger.model.ReviewItem pendingItem
                : reviewQueueService.pendingMissingTracksWithoutAutoSearch()) {
            scheduleMissingTracksOnlineSearch(pendingItem);
        }
        for (com.lux032.musicautotagger.model.ReviewItem pendingCover
                : reviewQueueService.pendingMissingCoverWithoutAutoSearch()) {
            scheduleMissingCoverSearch(pendingCover);
        }
        
        // Level 4: 初始化文件监控服务
        log.info(I18nUtil.getMessage("app.init.file.monitor"));
        fileMonitor = new FileMonitorService(config, processedLogger);
        fileMonitor.setFileReadyCallbackWithResult(audioFileProcessorService::processAudioFile);
        fileMonitor.setAutoProcessingSkipPredicate(file -> {
            java.io.File albumRoot = fileSystemUtils.getAlbumRootDirectory(file);
            return (albumRoot != null && reviewQueueService.isFolderUnderReview(albumRoot.getAbsolutePath()))
                || reviewQueueService.isFolderUnderReview(file.getAbsolutePath());
        });

        // 处理记录管理（Web 「处理记录」页面）
        processedRecordService = new ProcessedRecordService(config, processedLogger, folderAlbumCache,
            coverArtService, fileSystemUtils, () -> fileMonitor);
        processedRecordService.setRecoveryService(recoveryService);
        processedRecordService.setReviewQueueService(reviewQueueService);
        
        log.info(I18nUtil.getMessage("app.all.services.ready"));
    }
    
    /**
     * 启动 Web 监控面板
     */
    public void startWebServer() {
        try {
            webServer = new WebServer(8080);
            webServer.setProcessedRecordService(processedRecordService);
            webServer.start(processedLogger, coverArtCache, folderAlbumCache, config, databaseService, this,
                reviewQueueService, reviewResolutionService, recoveryService, coverBackfillService,
                coverCandidateService);
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
     * 暂停文件监控
     */
    public void pauseMonitoring() {
        if (fileMonitor != null) {
            fileMonitor.pause();
        }
    }

    /**
     * 恢复文件监控
     */
    public void resumeMonitoring() {
        if (fileMonitor != null) {
            fileMonitor.resume();
        }
    }

    /**
     * 检查监控是否暂停
     */
    public boolean isMonitoringPaused() {
        return fileMonitor != null && fileMonitor.isPaused();
    }

    /**
     * 检查监控是否运行中
     */
    public java.util.Map<String, Object> getMonitoringHealth() {
        return fileMonitor != null ? fileMonitor.getHealthSnapshot() : java.util.Map.of();
    }

    public boolean isMonitoringRunning() {
        return fileMonitor != null && fileMonitor.isRunning();
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
    /**
     * 为缺曲条目排一次自动联网搜索（进入统一的 OnlineSearchQueueService，优先级低于人工手动触发）。
     * 任务只绑定条目 ID：执行时条目已被人工处理就直接放弃，不会按文件夹新建条目。
     * 「已触发」标记在任务真正开始时才写入，关机时还在排队的任务重启后会补跑。
     */
    private void scheduleMissingTracksOnlineSearch(com.lux032.musicautotagger.model.ReviewItem queued) {
        if (queued == null || recoveryService == null || recoveryService.getSearchQueueService() == null
            || !config.isReviewMissingTracksAutoOnlineSearch() || !recoveryService.isOnlineSearchAvailable()) {
            return;
        }
        recoveryService.getSearchQueueService().enqueueMissingTracksAuto(queued);
    }

    private void scheduleMissingCoverSearch(com.lux032.musicautotagger.model.ReviewItem queued) {
        if (queued == null || recoveryService == null || recoveryService.getSearchQueueService() == null
            || !config.isCoverMissingAutoOnlineSearch()) {
            return;
        }
        recoveryService.getSearchQueueService().enqueueMissingCoverAuto(queued);
    }

    public void shutdown() {
        if (!shutdownCalled.compareAndSet(false, true)) {
            return;
        }
        log.info(I18nUtil.getMessage("app.shutting.down"));

        try {
            // 按依赖关系逆序关闭服务
            if (coverMissingService != null) {
                coverMissingService.close();
            }
            if (recoveryService != null) {
                recoveryService.close();
            }
            
            // 关闭 Web 服务器
            if (webServer != null && webServer.isRunning()) {
                try {
                    webServer.stop();
                } catch (Exception e) {
                    log.warn(I18nUtil.getMessage("app.shutdown.web.server.error"), e);
                }
            }
            
            if (albumArtistRefreshService != null) albumArtistRefreshService.close();
            if (lyricsBackfillService != null) lyricsBackfillService.close();
            if (lyricsService != null) {
                try { lyricsService.close(); }
                catch (IOException e) { log.warn("关闭歌词服务失败", e); }
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

            if (coverCandidateService != null) {
                try {
                    coverCandidateService.close();
                } catch (Exception e) {
                    log.warn("关闭封面候选服务失败", e);
                }
            }

            if (musicBrainzClient != null) {
                try {
                    musicBrainzClient.close();
                } catch (IOException e) {
                    log.warn(I18nUtil.getMessage("app.shutdown.musicbrainz.error"), e);
                }
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
