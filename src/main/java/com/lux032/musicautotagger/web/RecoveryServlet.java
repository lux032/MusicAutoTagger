package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.lux032.musicautotagger.service.RecoveryService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** 部分识别 / 识别失败文件的人工恢复 API。 */
public class RecoveryServlet extends HttpServlet {
    private static final String SESSION_CSRF_KEY = "csrfToken";
    private final RecoveryService recoveryService;
    private final Gson gson = new Gson();

    public RecoveryServlet(RecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String action = action(req);
        if ("list".equals(action)) {
            respond(resp, 200, Map.of("items", recoveryService.listItems(), "jobs", recoveryService.listJobs(),
                "onlineSearchAvailable", recoveryService.isOnlineSearchAvailable()));
        } else if ("jobs".equals(action)) {
            respond(resp, 200, Map.of("jobs", recoveryService.listJobs()));
        } else if ("trash".equals(action)) {
            respond(resp, 200, Map.of("items", recoveryService.listTrash()));
        } else {
            respond(resp, 404, Map.of("error", "unknown.action"));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isCsrfValid(req)) {
            respond(resp, 403, Map.of("error", "csrf.invalid"));
            return;
        }
        String action = action(req);
        if ("trash-restore".equals(action) || "trash-delete".equals(action)) {
            String id = str(readBody(req).get("id"));
            try {
                if ("trash-restore".equals(action)) {
                    recoveryService.restoreFromTrash(id);
                } else {
                    recoveryService.deleteFromTrash(id);
                }
                respond(resp, 200, Map.of("success", true));
            } catch (IOException e) {
                String message = e.getMessage() == null ? "trash.operation.failed" : e.getMessage();
                respond(resp, "trash.entry.not.found".equals(message) ? 404 : 400, Map.of("error", message));
            }
            return;
        }
        if (!"retry".equals(action)) {
            respond(resp, 404, Map.of("error", "unknown.action"));
            return;
        }

        Map<String, Object> body = readBody(req);
        try {
            RecoveryService.SourceType type = RecoveryService.SourceType.valueOf(str(body.get("sourceType")));
            String modeValue = str(body.get("mode"));
            RecoveryService.RecoveryMode mode = modeValue == null
                ? (bool(body.get("useLlm")) ? RecoveryService.RecoveryMode.LOCAL_ARTIST_MATCH : RecoveryService.RecoveryMode.REIDENTIFY)
                : RecoveryService.RecoveryMode.valueOf(modeValue);
            String jobId = recoveryService.submit(type, str(body.get("relativePath")), mode,
                bool(body.get("analyzeCover")));
            respond(resp, 202, Map.of("success", true, "jobId", jobId));
        } catch (IllegalArgumentException e) {
            respond(resp, 400, Map.of("error", "recovery.source.invalid"));
        } catch (IOException e) {
            String message = e.getMessage() == null ? "recovery.submit.failed" : e.getMessage();
            int status = "recovery.item.not.found".equals(message) ? 404 :
                "recovery.already.running".equals(message) ? 409 : 400;
            respond(resp, status, Map.of("error", message));
        }
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
        resp.getWriter().write(gson.toJson(body));
    }
}
