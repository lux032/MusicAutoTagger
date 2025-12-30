package org.example.web;

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
import org.example.config.MusicConfig;
import org.example.service.CoverArtCache;
import org.example.service.DatabaseService;
import org.example.service.FolderAlbumCache;
import org.example.service.ProcessedFileLogger;
import org.example.util.I18nUtil;

/**
 * 嵌入式 Web 服务器
 * 提供监控面板和统计接口
 */
@Slf4j
public class WebServer {
    
    private Server server;
    private final int port;
    
    public WebServer(int port) {
        this.port = port;
    }
    
    /**
     * 启动 Web 服务器
     */
    public void start(ProcessedFileLogger processedLogger,
                     CoverArtCache coverArtCache,
                     FolderAlbumCache folderAlbumCache,
                     MusicConfig config,
                     DatabaseService databaseService) throws Exception {
        
        server = new Server();
        
        // 配置 HTTP 连接，支持反向代理头信息
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSendServerVersion(false);
        httpConfig.setSendDateHeader(false);
        
        // 添加 ForwardedRequestCustomizer 以支持反向代理
        // 这会处理 X-Forwarded-For, X-Forwarded-Proto, X-Forwarded-Host 等头信息
        ForwardedRequestCustomizer forwardedCustomizer = new ForwardedRequestCustomizer();
        httpConfig.addCustomizer(forwardedCustomizer);
        
        // 创建 HTTP 连接器
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        connector.setPort(port);
        connector.setHost("0.0.0.0");  // 监听所有网络接口
        server.addConnector(connector);
        
        // 创建 Servlet 上下文处理器
        ServletContextHandler servletHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        servletHandler.setContextPath("/");
        
        // 注册 Dashboard API
        DashboardServlet dashboardServlet = new DashboardServlet(
            processedLogger, coverArtCache, folderAlbumCache, config, databaseService);
        servletHandler.addServlet(new ServletHolder(dashboardServlet), "/api/dashboard");
        
        // 注册日志 API
        LogServlet logServlet = new LogServlet();
        servletHandler.addServlet(new ServletHolder(logServlet), "/api/logs");

        // 注册国际化 API
        I18nServlet i18nServlet = new I18nServlet();
        servletHandler.addServlet(new ServletHolder(i18nServlet), "/api/i18n");

        // 创建静态资源处理器
        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setDirectoriesListed(false);
        resourceHandler.setWelcomeFiles(new String[]{"index.html"});
        
        // 从 classpath 加载静态资源
        resourceHandler.setResourceBase(
            WebServer.class.getClassLoader().getResource("static").toExternalForm());
        
        // 组合处理器
        HandlerList handlers = new HandlerList();
        handlers.addHandler(resourceHandler);
        handlers.addHandler(servletHandler);
        
        server.setHandler(handlers);
        server.start();
        
        log.info(I18nUtil.getMessage("web.server.separator"));
        log.info(I18nUtil.getMessage("web.server.started"));
        log.info(I18nUtil.getMessage("web.server.url") + port);
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
}