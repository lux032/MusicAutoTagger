package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lux032.musicautotagger.service.LyricsBackfillService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Map;

/** Missing-lyrics jobs: authenticated status; CSRF-protected start, preview and cancel. */
@Slf4j
public class LyricsBackfillServlet extends HttpServlet {

    private static final String SESSION_CSRF_KEY = "csrfToken";

    private final LyricsBackfillService backfillService;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public LyricsBackfillServlet(LyricsBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!"status".equals(action(req))) {
            respond(resp, 404, Map.of("error", "unknown.action"));
            return;
        }
        respond(resp, 200, backfillService.getStatus());
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isCsrfValid(req)) {
            respond(resp, 403, Map.of("error", "csrf.invalid"));
            return;
        }

        String action = action(req);
        switch (action) {
            case "preview":
            case "start":
                if (backfillService.start("preview".equals(action))) {
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
