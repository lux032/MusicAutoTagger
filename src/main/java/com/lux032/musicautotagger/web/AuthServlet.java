package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import com.lux032.musicautotagger.util.AdminCredentialsStore;
import org.mindrot.jbcrypt.BCrypt;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AuthServlet extends HttpServlet {

    private static final String SESSION_USER_KEY = "authUser";
    private static final String SESSION_CSRF_KEY = "csrfToken";
    private static final int SESSION_TIMEOUT_SECONDS = 30 * 60;
    private static final int MAX_LOGIN_FAILURES = 10;
    private static final long LOGIN_WINDOW_MILLIS = 15L * 60 * 1000;
    private static final int MAX_TRACKED_CLIENTS = 1024;
    /** 用户名不存在时执行的虚拟 BCrypt 校验，消除用户名时序枚举侧信道 */
    private static final String DUMMY_BCRYPT_HASH =
        "$2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW";

    private final AdminCredentialsStore credentialsStore;
    private final Gson gson;
    private final SecureRandom random;
    private final Map<String, Deque<Long>> loginFailuresByClient = new ConcurrentHashMap<>();
    private final Object setupLock = new Object();

    public AuthServlet(AdminCredentialsStore credentialsStore) {
        this.credentialsStore = credentialsStore;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.random = new SecureRandom();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if (path == null || "/status".equals(path)) {
            writeStatus(req, resp);
            return;
        }

        resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if (path == null) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        switch (path) {
            case "/login":
                handleLogin(req, resp);
                break;
            case "/setup":
                handleSetup(req, resp);
                break;
            case "/password":
                handlePasswordChange(req, resp);
                break;
            case "/logout":
                handleLogout(req, resp);
                break;
            default:
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                break;
        }
    }

    private void writeStatus(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        Map<String, Object> payload = new HashMap<>();
        boolean hasAdmin;
        try {
            hasAdmin = credentialsStore.load() != null;
        } catch (IOException e) {
            hasAdmin = false;
        }
        payload.put("needsSetup", !hasAdmin);
        if (hasAdmin && session != null && session.getAttribute(SESSION_USER_KEY) != null) {
            payload.put("authenticated", true);
            payload.put("username", session.getAttribute(SESSION_USER_KEY));
            String csrfToken = (String) session.getAttribute(SESSION_CSRF_KEY);
            if (csrfToken == null) {
                csrfToken = generateToken();
                session.setAttribute(SESSION_CSRF_KEY, csrfToken);
            }
            payload.put("csrfToken", csrfToken);
        } else {
            payload.put("authenticated", false);
        }

        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().write(gson.toJson(payload));
    }

    private void handleLogin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String clientKey = clientKey(req);
        if (isRateLimited(clientKey)) {
            respondJson(resp, 429, Map.of("error", "too.many.attempts"));
            return;
        }

        AdminCredentialsStore.AdminCredentials creds = credentialsStore.load();
        if (creds == null) {
            respondJson(resp, HttpServletResponse.SC_CONFLICT, Map.of("error", "admin.not.setup"));
            return;
        }

        Map<String, String> body = readBodyAsMap(req);
        if (body == null) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "body.invalid"));
            return;
        }
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || password == null) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("error", "missing.credentials");
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, payload);
            return;
        }

        boolean userMatches = username.equals(creds.username);
        String hashToVerify = userMatches ? creds.passwordHash : DUMMY_BCRYPT_HASH;
        boolean passwordValid = BCrypt.checkpw(password, hashToVerify);
        if (!userMatches || !passwordValid) {
            recordLoginFailure(clientKey);
            respondJson(resp, HttpServletResponse.SC_UNAUTHORIZED, Map.of("error", "invalid.credentials"));
            return;
        }

        clearLoginFailures(clientKey);
        HttpSession session = createSession(req, username);

        Map<String, Object> payload = new HashMap<>();
        payload.put("authenticated", true);
        payload.put("username", username);
        payload.put("csrfToken", session.getAttribute(SESSION_CSRF_KEY));
        respondJson(resp, HttpServletResponse.SC_OK, payload);
    }

    private void handleSetup(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, String> body = readBodyAsMap(req);
        if (body == null) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "body.invalid"));
            return;
        }
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || username.trim().isEmpty() || password == null) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "missing.credentials"));
            return;
        }
        String normalizedUser = username.trim();

        if (password.length() < 8) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "password.too.short"));
            return;
        }

        synchronized (setupLock) {
            if (credentialsStore.load() != null) {
                respondJson(resp, HttpServletResponse.SC_CONFLICT, Map.of("error", "admin.already.setup"));
                return;
            }
            String hash = BCrypt.hashpw(password, BCrypt.gensalt(12));
            AdminCredentialsStore.AdminCredentials creds = new AdminCredentialsStore.AdminCredentials(
                normalizedUser, hash, java.time.Instant.now().toString());
            credentialsStore.save(creds);
        }

        HttpSession session = createSession(req, normalizedUser);
        Map<String, Object> payload = new HashMap<>();
        payload.put("authenticated", true);
        payload.put("username", normalizedUser);
        payload.put("csrfToken", session.getAttribute(SESSION_CSRF_KEY));
        respondJson(resp, HttpServletResponse.SC_OK, payload);
    }

    private void handlePasswordChange(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isCsrfValid(req)) {
            respondJson(resp, HttpServletResponse.SC_FORBIDDEN, Map.of("error", "csrf.invalid"));
            return;
        }

        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute(SESSION_USER_KEY) == null) {
            respondJson(resp, HttpServletResponse.SC_UNAUTHORIZED, Map.of("error", "unauthorized"));
            return;
        }

        AdminCredentialsStore.AdminCredentials creds = credentialsStore.load();
        if (creds == null) {
            respondJson(resp, HttpServletResponse.SC_CONFLICT, Map.of("error", "admin.not.setup"));
            return;
        }

        Map<String, String> body = readBodyAsMap(req);
        if (body == null) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "body.invalid"));
            return;
        }
        String currentPassword = body.get("currentPassword");
        String newPassword = body.get("newPassword");

        if (currentPassword == null || newPassword == null) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "missing.credentials"));
            return;
        }

        if (!BCrypt.checkpw(currentPassword, creds.passwordHash)) {
            respondJson(resp, HttpServletResponse.SC_UNAUTHORIZED, Map.of("error", "invalid.credentials"));
            return;
        }

        if (newPassword.length() < 8) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "password.too.short"));
            return;
        }

        String newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt(12));
        AdminCredentialsStore.AdminCredentials updated = new AdminCredentialsStore.AdminCredentials(
            creds.username, newHash, java.time.Instant.now().toString());
        credentialsStore.save(updated);

        // 改密后轮换 Session ID 与 CSRF Token，降低会话固定风险
        try {
            req.changeSessionId();
        } catch (IllegalStateException ignored) {
        }
        String newCsrf = generateToken();
        session.setAttribute(SESSION_CSRF_KEY, newCsrf);

        respondJson(resp, HttpServletResponse.SC_OK, Map.of("updated", true, "csrfToken", newCsrf));
    }

    private void handleLogout(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isCsrfValid(req)) {
            respondJson(resp, HttpServletResponse.SC_FORBIDDEN, Map.of("error", "csrf.invalid"));
            return;
        }
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        respondJson(resp, HttpServletResponse.SC_OK, Map.of("authenticated", false));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readBodyAsMap(HttpServletRequest req) throws IOException {
        try (BufferedReader reader = req.getReader()) {
            Map<String, Object> raw = gson.fromJson(reader, Map.class);
            if (raw == null) {
                return new HashMap<>();
            }
            Map<String, String> result = new HashMap<>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                if (entry.getKey() != null && entry.getValue() instanceof String s) {
                    result.put(entry.getKey(), s);
                }
            }
            return result;
        } catch (com.google.gson.JsonParseException | ClassCastException e) {
            return null;
        }
    }

    private HttpSession createSession(HttpServletRequest req, String username) {
        HttpSession existing = req.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        HttpSession session = req.getSession(true);
        session.setMaxInactiveInterval(SESSION_TIMEOUT_SECONDS);
        session.setAttribute(SESSION_USER_KEY, username);
        String csrfToken = generateToken();
        session.setAttribute(SESSION_CSRF_KEY, csrfToken);
        return session;
    }

    private boolean isCsrfValid(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) {
            return false;
        }
        String token = req.getHeader("X-CSRF-Token");
        String sessionToken = (String) session.getAttribute(SESSION_CSRF_KEY);
        if (sessionToken == null || token == null) {
            return false;
        }
        return MessageDigest.isEqual(
            sessionToken.getBytes(StandardCharsets.UTF_8),
            token.getBytes(StandardCharsets.UTF_8));
    }

    private String clientKey(HttpServletRequest req) {
        String remote = req.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    private boolean isRateLimited(String clientKey) {
        Deque<Long> deque = loginFailuresByClient.get(clientKey);
        if (deque == null) {
            return false;
        }
        long cutoff = System.currentTimeMillis() - LOGIN_WINDOW_MILLIS;
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() < cutoff) {
                deque.pollFirst();
            }
            return deque.size() >= MAX_LOGIN_FAILURES;
        }
    }

    private void recordLoginFailure(String clientKey) {
        if (loginFailuresByClient.size() > MAX_TRACKED_CLIENTS) {
            loginFailuresByClient.clear();
        }
        Deque<Long> deque = loginFailuresByClient.computeIfAbsent(clientKey, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        long cutoff = now - LOGIN_WINDOW_MILLIS;
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() < cutoff) {
                deque.pollFirst();
            }
            deque.addLast(now);
        }
    }

    private void clearLoginFailures(String clientKey) {
        loginFailuresByClient.remove(clientKey);
    }

    private void respondJson(HttpServletResponse resp, int status, Map<String, Object> payload) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(status);
        resp.getWriter().write(gson.toJson(payload));
    }

    private String generateToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

