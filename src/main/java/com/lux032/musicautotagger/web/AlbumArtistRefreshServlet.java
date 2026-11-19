package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.lux032.musicautotagger.service.AlbumArtistRefreshService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

public final class AlbumArtistRefreshServlet extends HttpServlet {
    private final AlbumArtistRefreshService service;
    private final Gson gson = new Gson();

    public AlbumArtistRefreshServlet(AlbumArtistRefreshService service) {
        this.service = service;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if ("/status".equals(req.getPathInfo())) {
            respond(resp, HttpServletResponse.SC_OK, service.getStatus());
        } else {
            respond(resp, HttpServletResponse.SC_NOT_FOUND, Map.of("error", "unknown.action"));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        var session = req.getSession(false);
        Object token = session == null ? null : session.getAttribute("csrfToken");
        if (token == null || !token.equals(req.getHeader("X-CSRF-Token"))) {
            respond(resp, HttpServletResponse.SC_FORBIDDEN, Map.of("error", "csrf.invalid"));
        } else if ("/start".equals(req.getPathInfo())) {
            if (service.start()) {
                respond(resp, HttpServletResponse.SC_ACCEPTED, service.getStatus());
            } else {
                respond(resp, HttpServletResponse.SC_CONFLICT, Map.of("error", "backfill.already.running"));
            }
        } else if ("/cancel".equals(req.getPathInfo())) {
            service.cancel();
            respond(resp, HttpServletResponse.SC_OK, service.getStatus());
        } else {
            respond(resp, HttpServletResponse.SC_NOT_FOUND, Map.of("error", "unknown.action"));
        }
    }

    private void respond(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json; charset=UTF-8");
        response.getWriter().write(gson.toJson(body));
    }
}
