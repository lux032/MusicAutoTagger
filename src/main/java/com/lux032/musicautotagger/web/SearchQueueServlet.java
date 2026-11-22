package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.lux032.musicautotagger.service.OnlineSearchQueueService;
import com.lux032.musicautotagger.service.RecoveryService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一 LLM 联网搜索队列 API（/api/search-queue/*）。
 *
 * 路由：
 *   GET  /api/search-queue/status          -> 获取当前运行中 / 排队中 / 近期历史快照
 *   POST /api/search-queue/enqueue-review  -> 单个或批量加入待确认条目
 *   POST /api/search-queue/enqueue-recovery-> 单个或批量加入重新识别条目
 *   POST /api/search-queue/prioritize      -> 将排队中任务置顶优先
 *   POST /api/search-queue/cancel          -> 移出等待队列或中止运行中任务
 *   POST /api/search-queue/retry           -> 重试失败或中止的任务
 *   POST /api/search-queue/clear-queued    -> 清空所有等待中的任务
 *   POST /api/search-queue/clear-finished  -> 清空近期已完成 / 失败记录
 */
@Slf4j
public class SearchQueueServlet extends HttpServlet {

    private static final String SESSION_CSRF_KEY = "csrfToken";

    private final OnlineSearchQueueService queueService;
    private final RecoveryService recoveryService;
    private final Gson gson = new Gson();

    public SearchQueueServlet(OnlineSearchQueueService queueService, RecoveryService recoveryService) {
        this.queueService = queueService;
        this.recoveryService = recoveryService;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String action = action(req);
        if ("status".equals(action) || action.isEmpty()) {
            respond(resp, 200, queueService.getSnapshot());
            return;
        }
        respond(resp, 404, Map.of("error", "unknown.action"));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isCsrfValid(req)) {
            respond(resp, 403, Map.of("error", "csrf.invalid"));
            return;
        }
        String action = action(req);
        Map<String, Object> body = readBody(req);
        try {
            switch (action) {
                case "enqueue-review" -> handleEnqueueReview(body, resp);
                case "enqueue-recovery" -> handleEnqueueRecovery(body, resp);
                case "prioritize" -> {
                    Map<String, Object> task = queueService.prioritize(str(body.get("taskId")));
                    if (task == null) {
                        respond(resp, 404, Map.of("error", "search.task.not.found"));
                    } else {
                        respond(resp, 200, Map.of("success", true, "task", task));
                    }
                }
                case "cancel" -> {
                    boolean ok = queueService.cancel(str(body.get("taskId")));
                    if (!ok) {
                        respond(resp, 404, Map.of("error", "search.task.not.found"));
                    } else {
                        respond(resp, 200, Map.of("success", true));
                    }
                }
                case "retry" -> {
                    OnlineSearchQueueService.EnqueueResult result = queueService.retry(str(body.get("taskId")));
                    if (result == null || result.task() == null) {
                        respond(resp, 503, Map.of("error", "search.queue.closed"));
                    } else {
                        respond(resp, 202, Map.of("success", true, "task", result.task()));
                    }
                }
                case "clear-queued" -> {
                    int cleared = queueService.clearQueued();
                    respond(resp, 200, Map.of("success", true, "cleared", cleared));
                }
                case "clear-finished" -> {
                    int cleared = queueService.clearFinished();
                    respond(resp, 200, Map.of("success", true, "cleared", cleared));
                }
                default -> respond(resp, 404, Map.of("error", "unknown.action"));
            }
        } catch (IOException e) {
            String message = e.getMessage() == null ? "search.queue.failed" : e.getMessage();
            int status = switch (message) {
                case "llm.web.search.unavailable", "cover.search.unavailable", "search.queue.closed" -> 503;
                case "item.not.found", "recovery.item.not.found", "search.task.not.found" -> 404;
                case "item.already.resolved", "recovery.already.running" -> 409;
                default -> 400;
            };
            respond(resp, status, Map.of("error", message));
        } catch (Exception e) {
            log.error("搜索队列操作失败: {}", action, e);
            respond(resp, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private void handleEnqueueReview(Map<String, Object> body, HttpServletResponse resp) throws IOException {
        if (!queueService.isAvailable()) {
            respond(resp, 503, Map.of("error", "llm.web.search.unavailable"));
            return;
        }
        boolean analyzeCover = bool(body.get("analyzeCover"));
        List<String> ids = new ArrayList<>();
        if (body.get("ids") instanceof List<?> list) {
            for (Object el : list) {
                String id = str(el);
                if (id != null && !id.isBlank()) ids.add(id.trim());
            }
        } else {
            String single = str(body.get("id"));
            if (single != null && !single.isBlank()) ids.add(single.trim());
        }
        if (ids.isEmpty()) {
            respond(resp, 400, Map.of("error", "missing.id"));
            return;
        }

        List<Map<String, Object>> enqueued = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (String id : ids) {
            try {
                OnlineSearchQueueService.EnqueueResult res = queueService.enqueueReviewItem(id, analyzeCover);
                if (res.alreadyActive()) {
                    skipped.add(Map.of("id", id, "reason", "recovery.already.running"));
                } else {
                    enqueued.add(res.task());
                }
            } catch (IOException e) {
                if (ids.size() == 1) {
                    throw e;
                }
                skipped.add(Map.of("id", id, "reason", e.getMessage() == null ? "search.queue.failed" : e.getMessage()));
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("enqueued", enqueued);
        payload.put("skipped", skipped);
        if (enqueued.size() == 1 && ids.size() == 1) {
            payload.put("task", enqueued.get(0));
        }
        respond(resp, 202, payload);
    }

    private void handleEnqueueRecovery(Map<String, Object> body, HttpServletResponse resp) throws IOException {
        if (recoveryService == null) {
            respond(resp, 501, Map.of("error", "recovery.unavailable"));
            return;
        }
        if (!queueService.isAvailable()) {
            respond(resp, 503, Map.of("error", "llm.web.search.unavailable"));
            return;
        }
        boolean analyzeCover = bool(body.get("analyzeCover"));
        List<Map<?, ?>> targets = new ArrayList<>();
        if (body.get("items") instanceof List<?> list) {
            for (Object el : list) {
                if (el instanceof Map<?, ?> m) targets.add(m);
            }
        } else if (body.containsKey("sourceType") && body.containsKey("relativePath")) {
            targets.add(body);
        }
        if (targets.isEmpty()) {
            respond(resp, 400, Map.of("error", "recovery.path.required"));
            return;
        }

        List<Map<String, Object>> enqueued = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Map<?, ?> target : targets) {
            String src = str(target.get("sourceType"));
            String rel = str(target.get("relativePath"));
            try {
                RecoveryService.SourceType type = RecoveryService.SourceType.valueOf(src);
                OnlineSearchQueueService.EnqueueResult res =
                    recoveryService.enqueueOnlineSearch(type, rel, analyzeCover);
                if (res.alreadyActive()) {
                    skipped.add(Map.of("sourceType", src, "relativePath", rel == null ? "" : rel,
                        "reason", "recovery.already.running"));
                } else {
                    enqueued.add(res.task());
                }
            } catch (IllegalArgumentException e) {
                if (targets.size() == 1) {
                    respond(resp, 400, Map.of("error", "recovery.source.invalid"));
                    return;
                }
                skipped.add(Map.of("sourceType", src == null ? "" : src, "relativePath", rel == null ? "" : rel,
                    "reason", "recovery.source.invalid"));
            } catch (IOException e) {
                if (targets.size() == 1) {
                    throw e;
                }
                skipped.add(Map.of("sourceType", src == null ? "" : src, "relativePath", rel == null ? "" : rel,
                    "reason", e.getMessage() == null ? "recovery.submit.failed" : e.getMessage()));
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("enqueued", enqueued);
        payload.put("skipped", skipped);
        if (enqueued.size() == 1 && targets.size() == 1) {
            payload.put("task", enqueued.get(0));
        }
        respond(resp, 202, payload);
    }

    private String action(HttpServletRequest req) {
        String path = req.getPathInfo();
        return path == null || path.length() <= 1 ? "" : path.substring(1).split("/")[0];
    }

    private boolean isCsrfValid(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        return session != null && session.getAttribute(SESSION_CSRF_KEY) != null
            && session.getAttribute(SESSION_CSRF_KEY).equals(req.getHeader("X-CSRF-Token"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readBody(HttpServletRequest req) throws IOException {
        try (BufferedReader reader = req.getReader()) {
            Map<String, Object> data = gson.fromJson(reader, Map.class);
            return data == null ? new HashMap<>() : data;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private String str(Object value) { return value == null ? null : String.valueOf(value); }
    private boolean bool(Object value) { return value != null && Boolean.parseBoolean(String.valueOf(value)); }

    private void respond(HttpServletResponse resp, int status, Map<String, Object> body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(gson.toJson(body));
    }
}
