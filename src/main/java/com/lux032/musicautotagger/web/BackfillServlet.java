package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lux032.musicautotagger.service.CoverBackfillService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 历史封面回填 API。
 *
 *   GET  /api/backfill/status   查询进度（含待回填专辑数）
 *   POST /api/backfill/start    启动回填
 *   POST /api/backfill/cancel   请求取消
 *
 * 会话鉴权由 AuthFilter 统一把关；两个 POST 额外校验 CSRF，与 ConfigServlet 一致。
 */
@Slf4j
public class BackfillServlet extends HttpServlet {

    private static final String SESSION_CSRF_KEY = "csrfToken";

    private final CoverBackfillService backfillService;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public BackfillServlet(CoverBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!"status".equals(action(req))) {
            respond(resp, 404, Map.of("error", "unknown.action"));
            return;
        }
        Map<String, Object> status = new HashMap<>(backfillService.getStatus());
        // 没在跑的时候才去数一遍，避免回填过程中反复扫描日志/数据库
        status.put("pendingAlbums", backfillService.isRunning() ? -1 : backfillService.countPendingAlbums());
        respond(resp, 200, status);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isCsrfValid(req)) {
            respond(resp, 403, Map.of("error", "csrf.invalid"));
            return;
        }

        String action = action(req);
        switch (action) {
            case "start":
                if (backfillService.start()) {
                    respond(resp, 200, backfillService.getStatus());
                } else {
                    respond(resp, 409, Map.of("error", "backfill.already.running"));
                }
                return;
            case "cancel":
                backfillService.cancel();
                respond(resp, 200, backfillService.getStatus());
                return;
            default:
                respond(resp, 404, Map.of("error", "unknown.action"));
        }
    }

    private String action(HttpServletRequest req) {
        String path = req.getPathInfo();
        if (path == null || path.length() <= 1) {
            return "";
        }
        return path.substring(1);
    }

    private boolean isCsrfValid(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) {
            return false;
        }
        String token = req.getHeader("X-CSRF-Token");
        String sessionToken = (String) session.getAttribute(SESSION_CSRF_KEY);
        return sessionToken != null && sessionToken.equals(token);
    }

    private void respond(HttpServletResponse resp, int status, Map<String, ?> payload) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(status);
        resp.getWriter().write(gson.toJson(payload));
    }
}
