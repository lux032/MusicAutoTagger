package com.lux032.musicautotagger.core;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.service.*;
import com.lux032.musicautotagger.util.AdminCredentialsStore;
import com.lux032.musicautotagger.util.FileSystemUtils;
import com.lux032.musicautotagger.util.I18nUtil;
import com.lux032.musicautotagger.util.ProcessRunner;
import com.lux032.musicautotagger.util.SetupStateStore;
import com.lux032.musicautotagger.web.WebServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 应用程序生命周期管理器
 * 负责初始化和关闭所有服务
 */
@Slf4j
@Getter
public class ApplicationLifecycleManager {
    
    private final MusicConfig config;
    private final Path configPath;
    private final AdminCredentialsStore adminCredentialsStore;
    private final SetupStateStore setupStateStore;
    private final AtomicBoolean shutdownCalled = new AtomicBoolean(false);
    private final Object activationLock = new Object();
    private volatile boolean servicesInitialized = false;
    private int webPortOverride = -1;

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
        this(config, Paths.get("config.properties"),
            AdminCredentialsStore.defaultStore(), SetupStateStore.defaultStore());
    }

    public ApplicationLifecycleManager(MusicConfig config,
                                       Path configPath,
                                       AdminCredentialsStore adminCredentialsStore,
                                       SetupStateStore setupStateStore) {
        this.config = config;
        this.configPath = configPath != null ? configPath : Paths.get("config.properties");
        this.adminCredentialsStore = adminCredentialsStore != null
            ? adminCredentialsStore : AdminCredentialsStore.defaultStore();
        this.setupStateStore = setupStateStore != null
            ? setupStateStore : SetupStateStore.defaultStore();
    }

    /** 供测试指定临时端口（含 0 表示随机可用端口）。 */
    public void setWebPortOverride(int webPortOverride) {
        this.webPortOverride = webPortOverride;
    }

    public boolean isSetupCompleted() {
        return setupStateStore.isCompleted(adminCredentialsStore);
    }

    public boolean isActive() {
        return servicesInitialized;
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
        folderAlbumCache.setFileSystemUtils(fileSystemUtils);
        coverArtService.setFileSystemUtils(fileSystemUtils);
        
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
        albumBatchProcessor.setFailedFileHandler(failedFileHandler);
        
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

        // 所有核心服务初始化完毕后再补跑上次关机前未执行的自动搜索任务，避免初始化失败时后台线程已提前启动
        for (com.lux032.musicautotagger.model.ReviewItem pendingItem
                : reviewQueueService.pendingMissingTracksWithoutAutoSearch()) {
            scheduleMissingTracksOnlineSearch(pendingItem);
        }
        for (com.lux032.musicautotagger.model.ReviewItem pendingCover
                : reviewQueueService.pendingMissingCoverWithoutAutoSearch()) {
            scheduleMissingCoverSearch(pendingCover);
        }
        
        servicesInitialized = true;
        log.info(I18nUtil.getMessage("app.all.services.ready"));
    }

    private int resolveWebPort() {
        if (webPortOverride >= 0) {
            return webPortOverride;
        }
        return config != null && config.getWebPort() >= 1 && config.getWebPort() <= 65535
            ? config.getWebPort() : 8080;
    }

    /**
     * 以待初始化模式启动 Web 监控面板（不初始化数据库、核心服务与文件监控）。
     */
    public void startWebServerInSetupMode() throws Exception {
        int port = resolveWebPort();
        webServer = new WebServer(port);
        webServer.startInSetupMode(config, configPath, this, adminCredentialsStore, setupStateStore);
    }

    /**
     * 引导完成后的进程内免重启激活：校验关键目录 -> 初始化核心服务 -> 绑定业务路由 -> 启动监控。
     * 失败时清理半初始化资源并保持待命状态，允许用户修正后重试。
     */
    public void activateAfterSetup() throws IOException {
        synchronized (activationLock) {
            if (servicesInitialized) {
                return;
            }
            if (config == null || config.getMonitorDirectory() == null || config.getMonitorDirectory().isBlank()) {
                throw new IOException("setup.monitor.directory.missing");
            }
            Path monitorPath = Paths.get(config.getMonitorDirectory().trim()).normalize();
            if (!Files.isDirectory(monitorPath) || !Files.isReadable(monitorPath)) {
                throw new IOException("setup.monitor.directory.missing");
            }
            if (config.getOutputDirectory() == null || config.getOutputDirectory().isBlank()) {
                throw new IOException("setup.output.directory.missing");
            }
            Path outputPath = Paths.get(config.getOutputDirectory().trim()).normalize();
            if (!Files.exists(outputPath)) {
                Files.createDirectories(outputPath);
            }
            if (!Files.isDirectory(outputPath) || !Files.isWritable(outputPath)) {
                throw new IOException("setup.output.directory.unwritable");
            }

            try {
                initializeServices();
                if (webServer != null) {
                    webServer.activateBusinessServices(
                        processedLogger, coverArtCache, folderAlbumCache, config, databaseService, this,
                        reviewQueueService, reviewResolutionService, recoveryService, coverBackfillService,
                        coverCandidateService, processedRecordService);
                }
                startMonitoring();
            } catch (IOException | RuntimeException e) {
                cleanupPartialInitialization();
                throw e;
            }
        }
    }

    private void cleanupPartialInitialization() {
        servicesInitialized = false;
        closeQuietly("文件监控服务", () -> { if (fileMonitor != null) fileMonitor.stop(); });
        closeQuietly("封面回填服务", () -> { if (coverBackfillService != null) coverBackfillService.shutdown(); });
        closeQuietly("缺封面服务", () -> { if (coverMissingService != null) coverMissingService.close(); });
        closeQuietly("恢复与在线搜索服务", () -> { if (recoveryService != null) recoveryService.close(); });
        closeQuietly("专辑艺人刷新服务", () -> { if (albumArtistRefreshService != null) albumArtistRefreshService.close(); });
        closeQuietly("歌词回填服务", () -> { if (lyricsBackfillService != null) lyricsBackfillService.close(); });
        closeQuietly("歌词服务", () -> { if (lyricsService != null) lyricsService.close(); });
        closeQuietly("指纹服务", () -> { if (fingerprintService != null) fingerprintService.close(); });
        closeQuietly("封面候选服务", () -> { if (coverCandidateService != null) coverCandidateService.close(); });
        closeQuietly("MusicBrainz 客户端", () -> { if (musicBrainzClient != null) musicBrainzClient.close(); });
        closeQuietly("封面缓存", () -> { if (coverArtCache != null) coverArtCache.close(); });
        closeQuietly("处理记录日志", () -> { if (processedLogger != null) processedLogger.close(); });
        closeQuietly("数据库连接池", () -> { if (databaseService != null) databaseService.close(); });

        fileMonitor = null;
        coverBackfillService = null;
        coverMissingService = null;
        coverSearchService = null;
        recoveryService = null;
        albumArtistRefreshService = null;
        lyricsBackfillService = null;
        lyricsService = null;
        fingerprintService = null;
        coverCandidateService = null;
        musicBrainzClient = null;
        coverArtService = null;
        coverArtCache = null;
        folderAlbumCache = null;
        quickScanService = null;
        durationSequenceService = null;
        tagWriter = null;
        fileSystemUtils = null;
        failedFileHandler = null;
        albumBatchProcessor = null;
        audioFileProcessorService = null;
        reviewQueueService = null;
        reviewResolutionService = null;
        processedRecordService = null;
        processedLogger = null;
        databaseService = null;
    }
    
    /**
     * 启动 Web 监控面板
     */
    public void startWebServer() {
        try {
            int port = resolveWebPort();
            webServer = new WebServer(port);
            webServer.setConfigPath(configPath);
            webServer.setAdminCredentialsStore(adminCredentialsStore);
            webServer.setSetupStateStore(setupStateStore);
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
     * 检查 fpcalc 工具是否可用（支持在待初始化模式下直接探测）
     */
    public boolean isFpcalcAvailable() {
        if (fingerprintService != null) {
            return fingerprintService.isFpcalcAvailable();
        }
        return probeExecutable("fpcalc", "-version");
    }

    /**
     * 检查 ffmpeg 工具是否可用
     */
    public boolean isFfmpegAvailable() {
        String ffmpeg = config != null && config.getAudioNormalizeFfmpegPath() != null
            && !config.getAudioNormalizeFfmpegPath().isBlank()
            ? config.getAudioNormalizeFfmpegPath().trim()
            : "ffmpeg";
        return probeExecutable(ffmpeg, "-version");
    }

    private boolean probeExecutable(String executable, String versionArg) {
        try {
            return ProcessRunner.execute(List.of(executable, versionArg), 5).isSuccess();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
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

        // 1. 停止外部入口（Web API 与文件监控）
        closeQuietly("Web 服务器", () -> {
            if (webServer != null && webServer.isRunning()) {
                webServer.stop();
            }
        });
        closeQuietly("文件监控服务", () -> {
            if (fileMonitor != null) {
                fileMonitor.stop();
            }
        });

        // 2. 在关闭后台服务与 HTTP/数据库之前刷完所有待处理文件，避免文件丢失
        closeQuietly("专辑批处理刷盘", () -> {
            if (albumBatchProcessor != null) {
                albumBatchProcessor.processAllPendingFilesBeforeShutdown();
            }
        });

        // 3. 停止各后台任务执行器（含之前遗漏的 coverBackfillService）
        closeQuietly("封面回填服务", () -> {
            if (coverBackfillService != null) {
                coverBackfillService.shutdown();
            }
        });
        closeQuietly("缺封面服务", () -> {
            if (coverMissingService != null) {
                coverMissingService.close();
            }
        });
        closeQuietly("恢复与在线搜索服务", () -> {
            if (recoveryService != null) {
                recoveryService.close();
            }
        });
        closeQuietly("专辑艺人刷新服务", () -> {
            if (albumArtistRefreshService != null) {
                albumArtistRefreshService.close();
            }
        });
        closeQuietly("歌词回填服务", () -> {
            if (lyricsBackfillService != null) {
                lyricsBackfillService.close();
            }
        });

        // 4. 关闭外部 HTTP 客户端与候选抓取器
        closeQuietly("歌词服务", () -> {
            if (lyricsService != null) {
                lyricsService.close();
            }
        });
        closeQuietly("指纹服务", () -> {
            if (fingerprintService != null) {
                fingerprintService.close();
            }
        });
        closeQuietly("封面候选服务", () -> {
            if (coverCandidateService != null) {
                coverCandidateService.close();
            }
        });
        closeQuietly("MusicBrainz 客户端", () -> {
            if (musicBrainzClient != null) {
                musicBrainzClient.close();
            }
        });

        // 5. 关闭缓存、日志与数据库连接池（最后关闭）
        closeQuietly("封面缓存", () -> {
            if (coverArtCache != null) {
                CoverArtCache.CacheStatistics stats = coverArtCache.getStatistics();
                log.info(I18nUtil.getMessage("app.cover.cache.statistics"), stats);
                coverArtCache.close();
            }
        });
        closeQuietly("文件夹专辑缓存统计", () -> {
            if (folderAlbumCache != null) {
                FolderAlbumCache.CacheStatistics stats = folderAlbumCache.getStatistics();
                log.info(I18nUtil.getMessage("app.folder.album.cache.statistics"), stats);
            }
        });
        closeQuietly("处理记录日志", () -> {
            if (processedLogger != null) {
                processedLogger.close();
            }
        });
        closeQuietly("数据库连接池", () -> {
            if (databaseService != null) {
                databaseService.close();
            }
        });

        log.info(I18nUtil.getMessage("app.shutdown.complete"));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void closeQuietly(String name, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("关闭 {} 失败: {}", name, e.getMessage(), e);
        }
    }
}
