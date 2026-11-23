package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import com.lux032.musicautotagger.core.ApplicationLifecycleManager;
import com.lux032.musicautotagger.util.I18nUtil;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 服务控制 API
 * 提供暂停/恢复监控和重启服务的接口
 */
@Slf4j
public class ControlServlet extends HttpServlet {

    private final ApplicationLifecycleManager lifecycleManager;
    private final Gson gson = new Gson();

    public ControlServlet(ApplicationLifecycleManager lifecycleManager) {
        this.lifecycleManager = lifecycleManager;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        Map<String, Object> status = new HashMap<>();
        status.put("monitoringRunning", lifecycleManager.isMonitoringRunning());
        status.put("monitoringPaused", lifecycleManager.isMonitoringPaused());

        status.putAll(lifecycleManager.getMonitoringHealth());
        resp.getWriter().write(gson.toJson(status));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        String pathInfo = req.getPathInfo();
        Map<String, Object> result = new HashMap<>();

        if (pathInfo == null || pathInfo.equals("/")) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            result.put("error", "missing.action");
            resp.getWriter().write(gson.toJson(result));
            return;
        }

        String action = pathInfo.substring(1); // 去掉开头的 /

        try {
            switch (action) {
                case "pause":
                    lifecycleManager.pauseMonitoring();
                    result.put("success", true);
                    result.put("messageKey", "control.paused.msg");
                    result.put("message", I18nUtil.getMessage("control.paused.msg"));
                    result.put("monitoringPaused", true);
                    log.info("通过 API 暂停监控服务");
                    break;

                case "resume":
                    lifecycleManager.resumeMonitoring();
                    result.put("success", true);
                    result.put("messageKey", "control.resumed.msg");
                    result.put("message", I18nUtil.getMessage("control.resumed.msg"));
                    result.put("monitoringPaused", false);
                    log.info("通过 API 恢复监控服务");
                    break;

                case "restart":
                    result.put("success", true);
                    result.put("messageKey", "control.restarting.msg");
                    result.put("message", I18nUtil.getMessage("control.restarting.msg"));
                    log.info("通过 API 请求重启服务");
                    resp.getWriter().write(gson.toJson(result));
                    resp.flushBuffer();
                    // 在非守护线程中先执行优雅关机（刷盘并释放连接池/线程），再退出交由进程管理器重启
                    Thread restartThread = new Thread(() -> {
                        try {
                            Thread.sleep(300);
                            if (lifecycleManager != null) {
                                lifecycleManager.shutdown();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            log.error("重启前优雅关机异常", e);
                        } finally {
                            exitProcess();
                        }
                    }, "service-restart");
                    restartThread.setDaemon(false);
                    restartThread.start();
                    return;

                default:
                    resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    result.put("error", "unknown.action");
                    result.put("messageKey", "control.unknown.action");
                    result.put("message", I18nUtil.getMessage("control.unknown.action", action));
            }
        } catch (Exception e) {
            log.error("执行控制操作失败: {}", action, e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            result.put("error", "operation.failed");
            result.put("message", e.getMessage());
        }

        result.put("monitoringRunning", lifecycleManager.isMonitoringRunning());
        result.put("monitoringPaused", lifecycleManager.isMonitoringPaused());
        resp.getWriter().write(gson.toJson(result));
    }

    /** 包级钩子，供单测覆写以验证重启前已调用优雅关机而不会真实终止测试 JVM。 */
    protected void exitProcess() {
        System.exit(0);
    }
}

