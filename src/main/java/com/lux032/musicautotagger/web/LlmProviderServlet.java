package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.lux032.musicautotagger.config.LlmProviderConfig;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.service.llm.LlmModelCatalog;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LLM 供应商配置 API。
 *
 *   GET  /api/llm/providers         列出供应商（密钥掩码）
 *   PUT  /api/llm/providers         整体保存
 *   POST /api/llm/providers/models  从供应商拉取可用模型列表
 */
@Slf4j
public class LlmProviderServlet extends HttpServlet {

    private static final String SESSION_CSRF_KEY = "csrfToken";
    private static final String SECRET_MASK = "********";
    private static final Type PROVIDER_LIST_TYPE = new TypeToken<List<LlmProviderConfig>>() { }.getType();

    private final MusicConfig config;
    private final LlmModelCatalog catalog;
    private final Gson gson = new Gson();

    public LlmProviderServlet(MusicConfig config) {
        this.config = config;
        this.catalog = new LlmModelCatalog(config);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        List<Map<String, Object>> masked = new ArrayList<>();
        for (LlmProviderConfig provider : safeProviders()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", provider.getId());
            item.put("name", provider.getName());
            item.put("apiUrl", provider.getApiUrl());
            // 密钥永不明文出站：一个被劫持的会话不应该等于所有供应商凭据泄露
            item.put("apiKey", isSet(provider.getApiKey()) ? SECRET_MASK : "");
            item.put("format", provider.normalizedFormat());
            item.put("enabled", provider.isEnabled());
            item.put("models", provider.getModels());
            masked.add(item);
        }
        respondJson(resp, HttpServletResponse.SC_OK, Map.of("providers", masked));
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isCsrfValid(req)) {
            respondJson(resp, HttpServletResponse.SC_FORBIDDEN, Map.of("error", "csrf.invalid"));
            return;
        }

        List<LlmProviderConfig> incoming;
        try (BufferedReader reader = req.getReader()) {
            Map<String, Object> body = gson.fromJson(reader, Map.class);
            Object providers = body == null ? null : body.get("providers");
            incoming = gson.fromJson(gson.toJson(providers), PROVIDER_LIST_TYPE);
        } catch (Exception e) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "body.invalid"));
            return;
        }
        if (incoming == null) {
            incoming = new ArrayList<>();
        }

        Map<String, String> existingKeys = new HashMap<>();
        for (LlmProviderConfig provider : safeProviders()) {
            if (provider.getId() != null) {
                existingKeys.put(provider.getId(), provider.getApiKey());
            }
        }

        List<LlmProviderConfig> normalized = new ArrayList<>();
        for (LlmProviderConfig provider : incoming) {
            if (provider == null) {
                continue;
            }
            if (provider.getId() == null || provider.getId().isBlank()) {
                provider.setId(UUID.randomUUID().toString());
            }
            if (provider.getName() == null || provider.getName().isBlank()) {
                provider.setName("Provider " + (normalized.size() + 1));
            }
            if (provider.getApiUrl() != null) {
                provider.setApiUrl(provider.getApiUrl().trim());
            }
            // 掩码原样回传 = 保持原密钥不变；否则新配的供应商一保存就会把密钥抹成 "********"
            if (SECRET_MASK.equals(provider.getApiKey())) {
                provider.setApiKey(existingKeys.get(provider.getId()));
            }
            provider.setFormat(provider.normalizedFormat());
            if (provider.getModels() == null) {
                provider.setModels(new ArrayList<>());
            }
            normalized.add(provider);
        }

        config.setLlmProviders(normalized);
        try {
            config.saveLlmProviders();
        } catch (IOException e) {
            log.error("保存 LLM 供应商配置失败", e);
            respondJson(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, Map.of("error", "save.failed"));
            return;
        }
        respondJson(resp, HttpServletResponse.SC_OK, Map.of(
            "status", "ok",
            "endpointCount", config.getActiveLlmEndpoints().size()));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isCsrfValid(req)) {
            respondJson(resp, HttpServletResponse.SC_FORBIDDEN, Map.of("error", "csrf.invalid"));
            return;
        }
        String path = req.getPathInfo() == null ? "" : req.getPathInfo();
        if (!path.endsWith("/models")) {
            respondJson(resp, HttpServletResponse.SC_NOT_FOUND, Map.of("error", "not.found"));
            return;
        }

        Map<String, Object> body;
        try (BufferedReader reader = req.getReader()) {
            body = gson.fromJson(reader, Map.class);
        } catch (Exception e) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "body.invalid"));
            return;
        }
        if (body == null) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "body.invalid"));
            return;
        }

        String apiUrl = str(body.get("apiUrl"));
        String format = str(body.get("format"));
        String apiKey = str(body.get("apiKey"));
        // 面板上编辑已有供应商时密钥是掩码，此时取已保存的真实值
        if (apiKey == null || apiKey.isBlank() || SECRET_MASK.equals(apiKey)) {
            apiKey = lookupKey(str(body.get("id")));
        }

        try {
            List<String> models = catalog.fetch(apiUrl, apiKey, format);
            respondJson(resp, HttpServletResponse.SC_OK, Map.of("models", models));
        } catch (LlmModelCatalog.CatalogException e) {
            log.warn("拉取模型列表失败: {}", e.getMessage());
            respondJson(resp, HttpServletResponse.SC_BAD_GATEWAY,
                Map.of("error", "llm.models.failed", "detail", e.getMessage()));
        }
    }

    private String lookupKey(String providerId) {
        if (providerId == null) {
            return null;
        }
        for (LlmProviderConfig provider : safeProviders()) {
            if (providerId.equals(provider.getId())) {
                return provider.getApiKey();
            }
        }
        return null;
    }

    private List<LlmProviderConfig> safeProviders() {
        return config.getLlmProviders() == null ? new ArrayList<>() : config.getLlmProviders();
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isSet(String value) {
        return value != null && !value.isEmpty();
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

    private void respondJson(HttpServletResponse resp, int status, Map<String, Object> payload) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(status);
        resp.getWriter().write(gson.toJson(payload));
    }
}
