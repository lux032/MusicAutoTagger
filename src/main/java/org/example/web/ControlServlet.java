package org.example.web;

import com.google.gson.Gson;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.example.core.ApplicationLifecycleManager;

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
                    result.put("message", "监控已暂停");
                    result.put("monitoringPaused", true);
                    log.info("通过 API 暂停监控服务");
                    break;

                case "resume":
                    lifecycleManager.resumeMonitoring();
                    result.put("success", true);
                    result.put("message", "监控已恢复");
                    result.put("monitoringPaused", false);
                    log.info("通过 API 恢复监控服务");
                    break;

                case "restart":
                    result.put("success", true);
                    result.put("message", "服务即将重启");
                    log.info("通过 API 请求重启服务");
                    resp.getWriter().write(gson.toJson(result));
                    // 在新线程中执行重启，让响应先返回
                    new Thread(() -> {
                        try {
                            Thread.sleep(500);
                            System.exit(0); // 退出程序，由外部进程管理器重启
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                    return;

                default:
                    resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    result.put("error", "unknown.action");
                    result.put("message", "未知操作: " + action);
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
}
