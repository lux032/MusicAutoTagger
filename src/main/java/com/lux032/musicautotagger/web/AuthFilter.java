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
import java.util.HashMap;
import java.util.Map;

public class AuthFilter implements Filter {

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
        String path = req.getRequestURI();

        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        if (path.startsWith("/api/i18n") || path.startsWith("/api/auth/")) {
            chain.doFilter(request, response);
            return;
        }

        HttpSession session = req.getSession(false);
        if (session != null && session.getAttribute("authUser") != null) {
            chain.doFilter(request, response);
            return;
        }

        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        Map<String, Object> payload = new HashMap<>();
        payload.put("error", "unauthorized");
        resp.getWriter().write(gson.toJson(payload));
    }

    @Override
    public void destroy() {
        // No-op
    }
}

