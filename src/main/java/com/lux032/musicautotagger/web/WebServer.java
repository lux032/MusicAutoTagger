package com.lux032.musicautotagger.web;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.service.CoverArtCache;
import com.lux032.musicautotagger.service.CoverBackfillService;
import com.lux032.musicautotagger.service.CoverCandidateService;
import com.lux032.musicautotagger.service.DatabaseService;
import com.lux032.musicautotagger.service.FolderAlbumCache;
import com.lux032.musicautotagger.service.ProcessedFileLogger;
import com.lux032.musicautotagger.service.ProcessedRecordService;
import com.lux032.musicautotagger.service.ReviewQueueService;
import com.lux032.musicautotagger.service.ReviewResolutionService;
import com.lux032.musicautotagger.service.RecoveryService;
import com.lux032.musicautotagger.util.I18nUtil;
import com.lux032.musicautotagger.util.AdminCredentialsStore;
import com.lux032.musicautotagger.util.SetupStateStore;
import com.lux032.musicautotagger.core.ApplicationLifecycleManager;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;

/**
 * 嵌入式 Web 服务器
 * 提供监控面板、统计接口与首次启动初始化引导（支持待初始化模式与进程内热装配业务路由）
 */
@Slf4j
public class WebServer {

    private Server server;
    private ServerConnector connector;
    private final int port;
    private ProcessedRecordService processedRecordService;
    private Path configPath = Paths.get("config.properties");
    private AdminCredentialsStore adminCredentialsStore = AdminCredentialsStore.defaultStore();
    private SetupStateStore setupStateStore = SetupStateStore.defaultStore();

    // 可热装配业务路由槽位：在待初始化模式下返回 503 setup.required，激活后原子切换为真实 Servlet
    private final SwappableServlet dashboardSlot = new SwappableServlet();
    private final SwappableServlet coverSlot = new SwappableServlet();
    private final SwappableServlet controlSlot = new SwappableServlet();
    private final SwappableServlet reviewSlot = new SwappableServlet();
    private final SwappableServlet coversSlot = new SwappableServlet();
    private final SwappableServlet lyricsBackfillSlot = new SwappableServlet();
    private final SwappableServlet albumArtistRefreshSlot = new SwappableServlet();
    private final SwappableServlet backfillSlot = new SwappableServlet();
    private final SwappableServlet recoverySlot = new SwappableServlet();
    private final SwappableServlet searchQueueSlot = new SwappableServlet();
    private final SwappableServlet recordsSlot = new SwappableServlet();
    private final SwappableServlet setupSlot = new SwappableServlet();

    private ConfigServlet configServlet;
    private LlmProviderServlet llmProviderServlet;

    public WebServer(int port) {
        this.port = port;
    }

    /** 处理记录管理 API 的依赖，需在 start() 之前设置。 */
    public void setProcessedRecordService(ProcessedRecordService processedRecordService) {
        this.processedRecordService = processedRecordService;
    }

    public void setConfigPath(Path configPath) {
        if (configPath != null) {
            this.configPath = configPath;
        }
    }

    public void setAdminCredentialsStore(AdminCredentialsStore adminCredentialsStore) {
        if (adminCredentialsStore != null) {
            this.adminCredentialsStore = adminCredentialsStore;
        }
    }

    public void setSetupStateStore(SetupStateStore setupStateStore) {
        if (setupStateStore != null) {
            this.setupStateStore = setupStateStore;
        }
    }

    /** 注册或替换初始化引导 Servlet（供测试或启动装配使用）。 */
    public void setSetupServlet(HttpServlet setupServlet) {
        this.setupSlot.setDelegate(setupServlet);
    }

    public ConfigServlet getConfigServlet() {
        return configServlet;
    }

    public LlmProviderServlet getLlmProviderServlet() {
        return llmProviderServlet;
    }

    /** 返回实际绑定端口（当构造传入 0 时可获取随机分配的本地端口）。 */
    public int getLocalPort() {
        return connector != null ? connector.getLocalPort() : port;
    }

    /**
     * 以「待初始化模式」启动 Web 服务器：仅挂载认证、国际化、配置、LLM 供应商与初始化路由，
     * 业务接口统一返回 503 setup.required，待引导完成后通过 {@link #activateBusinessServices} 进程内热装配。
     */
    public void startInSetupMode(MusicConfig config,
                                 Path configPath,
                                 ApplicationLifecycleManager lifecycleManager,
                                 AdminCredentialsStore adminStore,
                                 SetupStateStore stateStore) throws Exception {
        setConfigPath(configPath);
        setAdminCredentialsStore(adminStore);
        setSetupStateStore(stateStore);
        startJettySkeleton(config, lifecycleManager);
    }

    /**
     * 启动 Web 服务器
     */
    public void start(ProcessedFileLogger processedLogger,
                     CoverArtCache coverArtCache,
                     FolderAlbumCache folderAlbumCache,
                     MusicConfig config,
                     DatabaseService databaseService,
                     ApplicationLifecycleManager lifecycleManager) throws Exception {
        start(processedLogger, coverArtCache, folderAlbumCache, config, databaseService,
            lifecycleManager, null, null, null, null, null);
    }

    public void start(ProcessedFileLogger processedLogger,
                     CoverArtCache coverArtCache,
                     FolderAlbumCache folderAlbumCache,
                     MusicConfig config,
                     DatabaseService databaseService,
                     ApplicationLifecycleManager lifecycleManager,
                     ReviewQueueService reviewQueueService,
                     ReviewResolutionService reviewResolutionService,
                     RecoveryService recoveryService) throws Exception {
        start(processedLogger, coverArtCache, folderAlbumCache, config, databaseService,
            lifecycleManager, reviewQueueService, reviewResolutionService, recoveryService, null, null);
    }

    /**
     * 启动 Web 服务器（含待人工确认 API，阶段六 #19）
     */
    public void start(ProcessedFileLogger processedLogger,
                     CoverArtCache coverArtCache,
                     FolderAlbumCache folderAlbumCache,
                     MusicConfig config,
                     DatabaseService databaseService,
                     ApplicationLifecycleManager lifecycleManager,
                     ReviewQueueService reviewQueueService,
                     ReviewResolutionService reviewResolutionService,
                     RecoveryService recoveryService,
                     CoverBackfillService coverBackfillService,
                     CoverCandidateService coverCandidateService) throws Exception {
        activateBusinessServices(
            processedLogger, coverArtCache, folderAlbumCache, config, databaseService, lifecycleManager,
            reviewQueueService, reviewResolutionService, recoveryService, coverBackfillService,
            coverCandidateService, processedRecordService);
        startJettySkeleton(config, lifecycleManager);
    }

    /**
     * 进程内热装配全部业务 Servlet（可在待初始化模式下由激活流程调用，无需重启 Jetty）。
     */
    public void activateBusinessServices(ProcessedFileLogger processedLogger,
                                         CoverArtCache coverArtCache,
                                         FolderAlbumCache folderAlbumCache,
                                         MusicConfig config,
                                         DatabaseService databaseService,
                                         ApplicationLifecycleManager lifecycleManager,
                                         ReviewQueueService reviewQueueService,
                                         ReviewResolutionService reviewResolutionService,
                                         RecoveryService recoveryService,
                                         CoverBackfillService coverBackfillService,
                                         CoverCandidateService coverCandidateService,
                                         ProcessedRecordService recordService) {
        dashboardSlot.setDelegate(new DashboardServlet(
            processedLogger, coverArtCache, folderAlbumCache, config, databaseService));
        coverSlot.setDelegate(new CoverServlet(config));
        controlSlot.setDelegate(new ControlServlet(lifecycleManager));

        if (reviewQueueService != null && reviewResolutionService != null) {
            reviewSlot.setDelegate(new ReviewServlet(reviewQueueService, reviewResolutionService,
                recoveryService, coverCandidateService));
        } else {
            reviewSlot.setDelegate(new NotAvailableServlet());
        }

        if (lifecycleManager != null && lifecycleManager.getCoverMissingService() != null && reviewQueueService != null) {
            coversSlot.setDelegate(new CoverMissingServlet(
                config,
                reviewQueueService,
                lifecycleManager.getCoverMissingService(),
                recoveryService == null ? null : recoveryService.getSearchQueueService()));
        } else {
            coversSlot.setDelegate(new NotAvailableServlet());
        }

        if (lifecycleManager != null && lifecycleManager.getLyricsBackfillService() != null) {
            lyricsBackfillSlot.setDelegate(new LyricsBackfillServlet(lifecycleManager.getLyricsBackfillService()));
        } else {
            lyricsBackfillSlot.setDelegate(new NotAvailableServlet());
        }

        if (lifecycleManager != null && lifecycleManager.getAlbumArtistRefreshService() != null) {
            albumArtistRefreshSlot.setDelegate(
                new AlbumArtistRefreshServlet(lifecycleManager.getAlbumArtistRefreshService()));
        } else {
            albumArtistRefreshSlot.setDelegate(new NotAvailableServlet());
        }

        if (coverBackfillService != null) {
            backfillSlot.setDelegate(new BackfillServlet(coverBackfillService));
        } else {
            backfillSlot.setDelegate(new NotAvailableServlet());
        }

        if (recoveryService != null) {
            recoverySlot.setDelegate(new RecoveryServlet(recoveryService));
            searchQueueSlot.setDelegate(new SearchQueueServlet(
                recoveryService.getSearchQueueService(), recoveryService));
        } else {
            recoverySlot.setDelegate(new NotAvailableServlet());
            searchQueueSlot.setDelegate(new NotAvailableServlet());
        }

        if (recordService != null) {
            recordsSlot.setDelegate(new RecordsServlet(recordService, config));
        } else {
            recordsSlot.setDelegate(new NotAvailableServlet());
        }
    }

    private void startJettySkeleton(MusicConfig config, ApplicationLifecycleManager lifecycleManager) throws Exception {
        server = new Server();

        // 配置 HTTP 连接，支持反向代理头信息
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSendServerVersion(false);
        httpConfig.setSendDateHeader(false);

        // 添加受信任内网代理限定版 ForwardedRequestCustomizer：
        // 仅当直连对端位于回环/私网地址（如本机或 Docker 反代）时才采信 X-Forwarded-* 头，避免公网直连伪造
        ForwardedRequestCustomizer forwardedCustomizer = new ForwardedRequestCustomizer() {
            @Override
            public void customize(org.eclipse.jetty.server.Connector conn,
                                  HttpConfiguration channelConfig,
                                  org.eclipse.jetty.server.Request request) {
                try {
                    java.net.InetAddress peer = java.net.InetAddress.getByName(request.getRemoteAddr());
                    if (!com.lux032.musicautotagger.util.OutboundUrlValidator.isPublic(peer)) {
                        super.customize(conn, channelConfig, request);
                    }
                } catch (Exception ignored) {
                }
            }
        };
        httpConfig.addCustomizer(forwardedCustomizer);

        // 创建 HTTP 连接器
        connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        connector.setPort(port);
        connector.setHost("0.0.0.0");  // 监听所有网络接口
        server.addConnector(connector);

        // 创建 Servlet 上下文处理器
        ServletContextHandler servletHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        servletHandler.setContextPath("/");
        servletHandler.getSessionHandler().getSessionCookieConfig().setHttpOnly(true);
        servletHandler.getSessionHandler().getSessionCookieConfig().setName("MAT_SESSION");
        servletHandler.getSessionHandler().setSameSite(org.eclipse.jetty.http.HttpCookie.SameSite.LAX);

        // 基础常驻 API（待初始化模式与运行态均可用）
        servletHandler.addServlet(new ServletHolder(new LogServlet()), "/api/logs");
        servletHandler.addServlet(new ServletHolder(new I18nServlet()), "/api/i18n");
        servletHandler.addServlet(new ServletHolder(
            new AuthServlet(adminCredentialsStore, setupStateStore, config, configPath)), "/api/auth/*");

        configServlet = new ConfigServlet(config, configPath);
        servletHandler.addServlet(new ServletHolder(configServlet), "/api/config");

        llmProviderServlet = new LlmProviderServlet(config);
        servletHandler.addServlet(new ServletHolder(llmProviderServlet), "/api/llm/providers/*");

        if (setupSlot.getDelegate() == null) {
            setupSlot.setDelegate(new SetupServlet(
                config, lifecycleManager, adminCredentialsStore, setupStateStore,
                configServlet, llmProviderServlet));
        }
        servletHandler.addServlet(new ServletHolder(setupSlot), "/api/setup/*");

        // 可热装配业务 API（未激活时返回 503 setup.required）
        servletHandler.addServlet(new ServletHolder(dashboardSlot), "/api/dashboard");
        servletHandler.addServlet(new ServletHolder(coverSlot), "/api/cover");
        servletHandler.addServlet(new ServletHolder(controlSlot), "/api/control/*");
        servletHandler.addServlet(new ServletHolder(reviewSlot), "/api/review/*");
        servletHandler.addServlet(new ServletHolder(coversSlot), "/api/covers/*");
        servletHandler.addServlet(new ServletHolder(lyricsBackfillSlot), "/api/lyrics-backfill/*");
        servletHandler.addServlet(new ServletHolder(albumArtistRefreshSlot), "/api/album-artist-refresh/*");
        servletHandler.addServlet(new ServletHolder(backfillSlot), "/api/backfill/*");
        servletHandler.addServlet(new ServletHolder(recoverySlot), "/api/recovery/*");
        servletHandler.addServlet(new ServletHolder(searchQueueSlot), "/api/search-queue/*");
        servletHandler.addServlet(new ServletHolder(recordsSlot), "/api/records/*");

        // 安全响应头与 API 认证/CSRF 过滤器
        servletHandler.addFilter(SecurityHeadersFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        servletHandler.addFilter(AuthFilter.class, "/api/*", EnumSet.of(DispatcherType.REQUEST));

        // 创建静态资源处理器（关闭浏览器强缓存并开启 ETag，避免升级镜像后子页继续命中旧 HTML）
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setDirectoriesListed(false);
        resourceHandler.setWelcomeFiles(new String[]{"index.html"});
        resourceHandler.setCacheControl("no-cache");
        resourceHandler.setEtags(true);

        // 从 classpath 加载静态资源
        resourceHandler.setResourceBase(
            WebServer.class.getClassLoader().getResource("static").toExternalForm());

        // 组合处理器
        HandlerList handlers = new HandlerList();
        handlers.addHandler(resourceHandler);
        handlers.addHandler(servletHandler);

        server.setHandler(handlers);
        server.start();

        int boundPort = getLocalPort();
        log.info(I18nUtil.getMessage("web.server.separator"));
        log.info(I18nUtil.getMessage("web.server.started"));
        log.info(I18nUtil.getMessage("web.server.url") + boundPort);
        log.info(I18nUtil.getMessage("web.server.separator"));
    }

    /**
     * 停止 Web 服务器
     */
    public void stop() throws Exception {
        if (server != null && server.isRunning()) {
            server.stop();
            log.info(I18nUtil.getMessage("web.server.stopped"));
        }
    }

    /**
     * 检查服务器是否正在运行
     */
    public boolean isRunning() {
        return server != null && server.isRunning();
    }

    /** 可在进程内热切换委托目标的薄包装 Servlet；未装配时返回 503 setup.required。 */
    private static class SwappableServlet extends HttpServlet {
        private volatile HttpServlet delegate;

        void setDelegate(HttpServlet delegate) {
            this.delegate = delegate;
        }

        HttpServlet getDelegate() {
            return delegate;
        }

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            HttpServlet current = this.delegate;
            if (current == null) {
                resp.setContentType("application/json; charset=UTF-8");
                resp.setCharacterEncoding("UTF-8");
                resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                resp.getWriter().write("{\"error\":\"setup.required\"}");
                return;
            }
            current.service(req, resp);
        }
    }

    /** 已激活但某可选模块未启用时的 404 占位 Servlet。 */
    private static class NotAvailableServlet extends HttpServlet {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("application/json; charset=UTF-8");
            resp.setCharacterEncoding("UTF-8");
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            resp.getWriter().write("{\"error\":\"service.unavailable\"}");
        }
    }

    /** 统一追加基础安全响应头 */
    public static class SecurityHeadersFilter implements jakarta.servlet.Filter {
        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response,
                             jakarta.servlet.FilterChain chain)
                throws java.io.IOException, jakarta.servlet.ServletException {
            if (response instanceof jakarta.servlet.http.HttpServletResponse resp) {
                resp.setHeader("X-Content-Type-Options", "nosniff");
                resp.setHeader("X-Frame-Options", "DENY");
                resp.setHeader("Referrer-Policy", "same-origin");
            }
            chain.doFilter(request, response);
        }
    }
}
