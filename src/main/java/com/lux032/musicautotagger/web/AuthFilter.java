package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class AuthFilter implements Filter {

    private static final String SESSION_CSRF_KEY = "csrfToken";
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public void init(FilterConfig filterConfig) {
        // No-op
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
        String servletPath = req.getServletPath();
        String pathInfo = req.getPathInfo();
        String path;
        if (servletPath != null && !servletPath.isEmpty()) {
            path = servletPath + (pathInfo == null ? "" : pathInfo);
        } else {
            String uri = req.getRequestURI();
            path = uri == null ? "" : uri;
        }
        int semicolon = path.indexOf(';');
        if (semicolon >= 0) {
            path = path.substring(0, semicolon);
        }

        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        String method = req.getMethod() == null ? "GET" : req.getMethod().toUpperCase(Locale.ROOT);
        if (path.startsWith("/api/i18n") || path.startsWith("/api/auth/")
            || ("GET".equals(method) && "/api/setup/status".equals(path))) {
            chain.doFilter(request, response);
            return;
        }

        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("authUser") == null) {
            writeError(resp, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized");
            return;
        }

        if (!"GET".equals(method) && !"HEAD".equals(method) && !"OPTIONS".equals(method)) {
            if (!isCsrfValid(req, session)) {
                writeError(resp, HttpServletResponse.SC_FORBIDDEN, "csrf.invalid");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean isCsrfValid(HttpServletRequest req, HttpSession session) {
        String token = req.getHeader("X-CSRF-Token");
        String sessionToken = (String) session.getAttribute(SESSION_CSRF_KEY);
        if (token == null || sessionToken == null) {
            return false;
        }
        return MessageDigest.isEqual(
            sessionToken.getBytes(StandardCharsets.UTF_8),
            token.getBytes(StandardCharsets.UTF_8));
    }

    private void writeError(HttpServletResponse resp, int status, String error) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(status);
        Map<String, Object> payload = new HashMap<>();
        payload.put("error", error);
        resp.getWriter().write(gson.toJson(payload));
    }

    @Override
    public void destroy() {
        // No-op
    }
}

