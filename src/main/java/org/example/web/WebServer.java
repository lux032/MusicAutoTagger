package org.example.web;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.example.config.MusicConfig;
import org.example.service.CoverArtCache;
import org.example.service.DatabaseService;
import org.example.service.FolderAlbumCache;
import org.example.service.ProcessedFileLogger;

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
        
        server = new Server(port);
        
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
        
        log.info("========================================");
        log.info("Web 监控面板已启动");
        log.info("访问地址: http://localhost:{}", port);
        log.info("========================================");
    }
    
    /**
     * 停止 Web 服务器
     */
    public void stop() throws Exception {
        if (server != null && server.isRunning()) {
            server.stop();
            log.info("Web 服务器已关闭");
        }
    }
    
    /**
     * 检查服务器是否正在运行
     */
    public boolean isRunning() {
        return server != null && server.isRunning();
    }
}