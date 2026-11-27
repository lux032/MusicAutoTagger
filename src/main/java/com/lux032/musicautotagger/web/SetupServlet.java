package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.LlmProviderConfig;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.core.ApplicationLifecycleManager;
import com.lux032.musicautotagger.util.AdminCredentialsStore;
import com.lux032.musicautotagger.util.HttpClientFactory;
import com.lux032.musicautotagger.util.OutboundUrlValidator;
import com.lux032.musicautotagger.util.SetupStateStore;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首次启动初始化引导与连通性校验 API（/api/setup/*）。
 *
 *   GET  /api/setup/status                    公开非敏感状态（登录后附加 acoustIdMissing 与忽略态）
 *   GET  /api/setup/bootstrap                 环境自检 + 脱敏配置回填 + 占位符识别（需登录）
 *   POST /api/setup/check-directories         目录存在性与读写校验（需登录 + CSRF）
 *   POST /api/setup/test-proxy                使用临时代理探测内置固定 MusicBrainz 公开端点（需登录 + CSRF）
 *   POST /api/setup/test-sources              AcoustID 鉴权码判定 + MusicBrainz 可达性测试（需登录 + CSRF）
 *   POST /api/setup/test-tavily               Tavily API Key 连通性测试（需登录 + CSRF）
 *   POST /api/setup/dismiss-acoustid-warning  持久化忽略概览页 AcoustID 轻提示（需登录 + CSRF）
 *   POST /api/setup/complete                  原子落盘配置与供应商；首次待命态直接进程内激活监控（需登录 + CSRF）
 */
@Slf4j
public class SetupServlet extends HttpServlet {

    private static final String SESSION_USER_KEY = "authUser";
    private static final String SESSION_CSRF_KEY = "csrfToken";
    private static final String SECRET_MASK = "********";
    private static final long MAX_PROBE_RESPONSE_BYTES = 256L * 1024;
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
    /** 代理测试锁死的受控公网探测目标（MusicBrainz 公开固定艺术家条目），杜绝任意目标 URL SSRF */
    static final String FIXED_PROXY_PROBE_URL =
        "https://musicbrainz.org/ws/2/artist/5b11f4ce-a62d-471e-81fc-a69a8278c7da?fmt=json";
    private static final String MB_PROBE_SUFFIX = "/artist/5b11f4ce-a62d-471e-81fc-a69a8278c7da?fmt=json";

    private final MusicConfig config;
    private final ApplicationLifecycleManager lifecycleManager;
    private final AdminCredentialsStore adminStore;
    private final SetupStateStore setupStateStore;
    private final ConfigServlet configServlet;
    private final LlmProviderServlet llmProviderServlet;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Object completeLock = new Object();

    public SetupServlet(MusicConfig config,
                        ApplicationLifecycleManager lifecycleManager,
                        AdminCredentialsStore adminStore,
                        SetupStateStore setupStateStore,
                        ConfigServlet configServlet,
                        LlmProviderServlet llmProviderServlet) {
        this.config = config;
        this.lifecycleManager = lifecycleManager;
        this.adminStore = adminStore != null ? adminStore : AdminCredentialsStore.defaultStore();
        this.setupStateStore = setupStateStore != null ? setupStateStore : SetupStateStore.defaultStore();
        this.configServlet = configServlet;
        this.llmProviderServlet = llmProviderServlet;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = normalizePath(req.getPathInfo());
        switch (path) {
            case "", "/status" -> handleStatus(req, resp);
            case "/bootstrap" -> handleBootstrap(req, resp);
            default -> respondJson(resp, HttpServletResponse.SC_NOT_FOUND, Map.of("error", "not.found"));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isAuthenticated(req)) {
            respondJson(resp, HttpServletResponse.SC_UNAUTHORIZED, Map.of("error", "unauthorized"));
            return;
        }
        if (!isCsrfValid(req)) {
            respondJson(resp, HttpServletResponse.SC_FORBIDDEN, Map.of("error", "csrf.invalid"));
            return;
        }

        String path = normalizePath(req.getPathInfo());
        switch (path) {
            case "/check-directories" -> handleCheckDirectories(req, resp);
            case "/test-proxy" -> handleTestProxy(req, resp);
            case "/test-sources" -> handleTestSources(req, resp);
            case "/test-tavily" -> handleTestTavily(req, resp);
            case "/dismiss-acoustid-warning" -> handleDismissAcoustIdWarning(resp);
            case "/complete" -> handleComplete(req, resp);
            default -> respondJson(resp, HttpServletResponse.SC_NOT_FOUND, Map.of("error", "not.found"));
        }
    }

    private void handleStatus(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        boolean hasAdmin;
        try {
            hasAdmin = adminStore.load() != null;
        } catch (IOException e) {
            hasAdmin = false;
        }
        boolean completed = setupStateStore.isCompleted(adminStore);
        boolean active = lifecycleManager == null || lifecycleManager.isActive();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("setupCompleted", completed);
        payload.put("needsAdmin", !hasAdmin);
        payload.put("active", active);
        payload.put("language", config != null && config.getLanguage() != null ? config.getLanguage() : "en_US");

        // 首次未初始化第 1 步需要展示基础环境自检摘要（仅布尔可用性与默认数据库类型，不暴露宿主机路径或凭据）
        if (!completed) {
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("fpcalcAvailable", lifecycleManager != null && lifecycleManager.isFpcalcAvailable());
            env.put("ffmpegAvailable", lifecycleManager != null && lifecycleManager.isFfmpegAvailable());
            env.put("dbType", config != null && config.getDbType() != null ? config.getDbType() : "sqlite");
            payload.put("environment", env);
        }

        // 仅向已登录会话返回配置完整度与提示状态，避免未认证接口暴露内部配置细节
        if (isAuthenticated(req)) {
            payload.put("acoustIdMissing", SetupStateStore.isAcoustIdPlaceholderOrEmpty(
                config == null ? null : config.getAcoustIdApiKey()));
            payload.put("dismissedAcoustIdWarning", setupStateStore.isAcoustIdWarningDismissed());
        }
        respondJson(resp, HttpServletResponse.SC_OK, payload);
    }

    private void handleBootstrap(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isAuthenticated(req)) {
            respondJson(resp, HttpServletResponse.SC_UNAUTHORIZED, Map.of("error", "unauthorized"));
            return;
        }
        boolean completed = setupStateStore.isCompleted(adminStore);
        boolean active = lifecycleManager == null || lifecycleManager.isActive();

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("fpcalcAvailable", lifecycleManager != null && lifecycleManager.isFpcalcAvailable());
        env.put("ffmpegAvailable", lifecycleManager != null && lifecycleManager.isFfmpegAvailable());
        env.put("dbType", config != null && config.getDbType() != null ? config.getDbType() : "sqlite");

        Map<String, Object> cfgSnapshot = configServlet != null
            ? new LinkedHashMap<>(configServlet.snapshotConfig()) : new LinkedHashMap<>();
        String rawAcoustId = config == null ? null : config.getAcoustIdApiKey();
        boolean isPlaceholder = rawAcoustId != null
            && SetupStateStore.ACOUSTID_PLACEHOLDER.equalsIgnoreCase(rawAcoustId.trim());
        boolean acoustIdRealSet = !SetupStateStore.isAcoustIdPlaceholderOrEmpty(rawAcoustId);
        cfgSnapshot.put("acoustIdApiKeyPlaceholder", isPlaceholder);
        cfgSnapshot.put("acoustIdApiKeySet", acoustIdRealSet);
        if (isPlaceholder) {
            cfgSnapshot.put("acoustIdApiKey", "");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("setupCompleted", completed);
        payload.put("active", active);
        payload.put("isRerun", completed && active);
        payload.put("environment", env);
        payload.put("config", cfgSnapshot);
        payload.put("providers", llmProviderServlet != null ? llmProviderServlet.maskedProviders() : List.of());
        payload.put("pendingRestartFields", configServlet != null ? configServlet.getPendingRestartFields() : List.of());
        respondJson(resp, HttpServletResponse.SC_OK, payload);
    }

    private void handleCheckDirectories(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = readBodyAsMap(req);
        if (body == null) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "body.invalid"));
            return;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("monitor", inspectDirectory(asString(body.get("monitorDirectory")), false));
        result.put("output", inspectDirectory(asString(body.get("outputDirectory")), true));
        respondJson(resp, HttpServletResponse.SC_OK, result);
    }

    private Map<String, Object> inspectDirectory(String rawPath, boolean outputDir) {
        Map<String, Object> info = new LinkedHashMap<>();
        String trimmed = rawPath == null ? "" : rawPath.trim();
        info.put("path", trimmed);
        if (trimmed.isEmpty()) {
            info.put("status", "invalid");
            info.put("error", "path.required");
            return info;
        }
        Path parsed;
        try {
            parsed = Paths.get(trimmed).normalize();
        } catch (InvalidPathException e) {
            info.put("status", "invalid");
            info.put("error", "path.invalid");
            return info;
        }
        if (!parsed.isAbsolute() || parsed.getParent() == null) {
            info.put("status", "invalid");
            info.put("error", "path.must.be.absolute");
            return info;
        }

        boolean exists = Files.exists(parsed);
        boolean isDir = exists && Files.isDirectory(parsed);
        boolean readable = isDir && Files.isReadable(parsed);
        boolean writable = isDir && Files.isWritable(parsed);
        info.put("exists", exists);
        info.put("isDirectory", isDir);
        info.put("readable", readable);
        info.put("writable", writable);

        if (!outputDir) {
            if (isDir && readable) {
                info.put("status", "ok");
            } else {
                info.put("status", "missing");
                info.put("error", exists ? "path.unreadable" : "path.not.found");
            }
            return info;
        }

        if (exists) {
            if (isDir && writable) {
                info.put("status", "ok");
            } else {
                info.put("status", "missing");
                info.put("error", isDir ? "path.unwritable" : "path.not.directory");
            }
            return info;
        }

        // 输出目录尚不存在时，向上查找最近已存在父目录判断是否可自动创建
        Path ancestor = parsed.getParent();
        while (ancestor != null && !Files.exists(ancestor)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor != null && Files.isDirectory(ancestor) && Files.isWritable(ancestor)) {
            info.put("status", "willCreate");
        } else {
            info.put("status", "missing");
            info.put("error", "path.unwritable");
        }
        return info;
    }

    private void handleTestProxy(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = readBodyAsMap(req);
        if (body == null) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "body.invalid"));
            return;
        }
        @SuppressWarnings("unchecked")
        Map<?, ?> proxyInput = body.get("proxy") instanceof Map<?, ?> m ? m : body;
        String host = asString(proxyInput.get("proxyHost") != null ? proxyInput.get("proxyHost") : proxyInput.get("host"));
        Integer port = asInteger(proxyInput.get("proxyPort") != null ? proxyInput.get("proxyPort") : proxyInput.get("port"));
        if (host == null || host.isBlank() || port == null || port < 1 || port > 65535) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("ok", false, "error", "proxy.invalid"));
            return;
        }

        MusicConfig proxyCfg;
        try {
            Map<String, Object> forced = new HashMap<>();
            forced.put("enabled", true);
            forced.put("host", host.trim());
            forced.put("port", port);
            forced.put("username", proxyInput.get("proxyUsername") != null
                ? proxyInput.get("proxyUsername") : proxyInput.get("username"));
            forced.put("password", proxyInput.get("proxyPassword") != null
                ? proxyInput.get("proxyPassword") : proxyInput.get("password"));
            proxyCfg = buildTransientProxyConfig(forced, config);
        } catch (ConfigServlet.ValidationException e) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("ok", false, "error", e.error));
            return;
        }

        long start = System.currentTimeMillis();
        try {
            String ua = effectiveUserAgent(asString(body.get("userAgent")));
            HttpRequest probeReq = HttpRequest.newBuilder(URI.create(FIXED_PROXY_PROBE_URL))
                .timeout(PROBE_TIMEOUT)
                .header("User-Agent", ua)
                .header("Accept", "application/json")
                .GET()
                .build();
            ProbeResponse pr = executeProbe(probeReq, proxyCfg);
            long latencyMs = System.currentTimeMillis() - start;
            if (pr.statusCode == 407) {
                respondJson(resp, HttpServletResponse.SC_OK, Map.of(
                    "ok", false, "statusCode", 407, "latencyMs", latencyMs, "error", "proxy.auth.failed"));
                return;
            }
            boolean ok = pr.statusCode >= 200 && pr.statusCode < 400;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", ok);
            out.put("statusCode", pr.statusCode);
            out.put("latencyMs", latencyMs);
            if (!ok) {
                out.put("error", "proxy.target.http." + pr.statusCode);
            }
            respondJson(resp, HttpServletResponse.SC_OK, out);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            respondJson(resp, HttpServletResponse.SC_OK, Map.of(
                "ok", false,
                "latencyMs", latencyMs,
                "error", "proxy.connect.failed",
                "detail", sanitizeErrorMessage(e.getMessage())
            ));
        }
    }

    private void handleTestSources(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = readBodyAsMap(req);
        if (body == null) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "body.invalid"));
            return;
        }

        MusicConfig effectiveProxy;
        try {
            effectiveProxy = resolveEffectiveProxy(body);
        } catch (ConfigServlet.ValidationException e) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", e.error));
            return;
        }

        String ua = effectiveUserAgent(asString(body.get("userAgent")));
        String rawKey = asString(body.get("acoustIdApiKey"));
        String acoustIdKey = resolveSecret(rawKey, config == null ? null : config.getAcoustIdApiKey());

        Map<String, Object> acoustResult = probeAcoustId(acoustIdKey, ua, effectiveProxy);
        Map<String, Object> mbResult = probeMusicBrainz(ua, effectiveProxy);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("viaProxy", HttpClientFactory.isProxyConfigured(effectiveProxy));
        payload.put("acoustid", acoustResult);
        payload.put("musicbrainz", mbResult);
        respondJson(resp, HttpServletResponse.SC_OK, payload);
    }

    private Map<String, Object> probeAcoustId(String apiKey, String ua, MusicConfig effectiveProxy) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (SetupStateStore.isAcoustIdPlaceholderOrEmpty(apiKey)) {
            out.put("status", "empty");
            out.put("ok", false);
            out.put("error", "acoustid.key.empty");
            return out;
        }
        String url = config != null && config.getAcoustIdApiUrl() != null && !config.getAcoustIdApiUrl().isBlank()
            ? config.getAcoustIdApiUrl().trim()
            : "https://api.acoustid.org/v2/lookup";
        try {
            assertUrlAllowed(url);
        } catch (OutboundUrlValidator.ValidationException e) {
            out.put("status", "error");
            out.put("ok", false);
            out.put("error", e.getCode());
            return out;
        }

        long start = System.currentTimeMillis();
        try {
            String form = "client=" + URLEncoder.encode(apiKey.trim(), StandardCharsets.UTF_8)
                + "&duration=1&fingerprint=AQAA";
            HttpRequest probeReq = HttpRequest.newBuilder(URI.create(url))
                .timeout(PROBE_TIMEOUT)
                .header("User-Agent", ua)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
            ProbeResponse pr = executeProbe(probeReq, effectiveProxy);
            long latencyMs = System.currentTimeMillis() - start;
            out.put("latencyMs", latencyMs);
            out.put("statusCode", pr.statusCode);

            JsonObject json = parseJsonObjectSafe(pr.body);
            if (json != null) {
                String status = json.has("status") && json.get("status").isJsonPrimitive()
                    ? json.get("status").getAsString() : "";
                if ("ok".equalsIgnoreCase(status)) {
                    out.put("status", "ok");
                    out.put("ok", true);
                    return out;
                }
                if (json.has("error") && json.get("error").isJsonObject()) {
                    JsonObject errObj = json.getAsJsonObject("error");
                    int code = errObj.has("code") && errObj.get("code").isJsonPrimitive()
                        ? errObj.get("code").getAsInt() : -1;
                    // AcoustID code 4 = invalid API key；code 3 = invalid fingerprint（证明 API Key 已通过鉴权）
                    if (code == 4) {
                        out.put("status", "invalid");
                        out.put("ok", false);
                        out.put("error", "acoustid.key.invalid");
                        return out;
                    }
                    if (code == 3) {
                        out.put("status", "ok");
                        out.put("ok", true);
                        return out;
                    }
                    out.put("status", "error");
                    out.put("ok", false);
                    out.put("error", "acoustid.error." + code);
                    return out;
                }
            }
            if (pr.statusCode >= 200 && pr.statusCode < 300) {
                out.put("status", "ok");
                out.put("ok", true);
            } else {
                out.put("status", "error");
                out.put("ok", false);
                out.put("error", "acoustid.http." + pr.statusCode);
            }
        } catch (Exception e) {
            out.put("status", "error");
            out.put("ok", false);
            out.put("latencyMs", System.currentTimeMillis() - start);
            out.put("error", "acoustid.connect.failed");
            out.put("detail", sanitizeErrorMessage(e.getMessage()));
        }
        return out;
    }

    private Map<String, Object> probeMusicBrainz(String ua, MusicConfig effectiveProxy) {
        Map<String, Object> out = new LinkedHashMap<>();
        String base = config != null && config.getMusicBrainzApiUrl() != null && !config.getMusicBrainzApiUrl().isBlank()
            ? config.getMusicBrainzApiUrl().trim()
            : "https://musicbrainz.org/ws/2";
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String url = base + MB_PROBE_SUFFIX;
        try {
            assertUrlAllowed(url);
        } catch (OutboundUrlValidator.ValidationException e) {
            out.put("status", "error");
            out.put("ok", false);
            out.put("error", e.getCode());
            return out;
        }

        long start = System.currentTimeMillis();
        try {
            HttpRequest probeReq = HttpRequest.newBuilder(URI.create(url))
                .timeout(PROBE_TIMEOUT)
                .header("User-Agent", ua)
                .header("Accept", "application/json")
                .GET()
                .build();
            ProbeResponse pr = executeProbe(probeReq, effectiveProxy);
            long latencyMs = System.currentTimeMillis() - start;
            out.put("latencyMs", latencyMs);
            out.put("statusCode", pr.statusCode);
            if (pr.statusCode >= 200 && pr.statusCode < 400) {
                out.put("status", "ok");
                out.put("ok", true);
            } else {
                out.put("status", "error");
                out.put("ok", false);
                out.put("error", "musicbrainz.http." + pr.statusCode);
            }
        } catch (Exception e) {
            out.put("status", "error");
            out.put("ok", false);
            out.put("latencyMs", System.currentTimeMillis() - start);
            out.put("error", "musicbrainz.connect.failed");
            out.put("detail", sanitizeErrorMessage(e.getMessage()));
        }
        return out;
    }

    private void handleTestTavily(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = readBodyAsMap(req);
        if (body == null) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "body.invalid"));
            return;
        }
        MusicConfig effectiveProxy;
        try {
            effectiveProxy = resolveEffectiveProxy(body);
        } catch (ConfigServlet.ValidationException e) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("ok", false, "error", e.error));
            return;
        }

        String tavilyKey = resolveSecret(asString(body.get("tavilyApiKey")),
            config == null ? null : config.getTavilyApiKey());
        if (tavilyKey == null || tavilyKey.isBlank()) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("ok", false, "error", "tavily.key.empty"));
            return;
        }

        String url = config != null && config.getTavilyApiUrl() != null && !config.getTavilyApiUrl().isBlank()
            ? config.getTavilyApiUrl().trim()
            : "https://api.tavily.com/search";
        try {
            assertUrlAllowed(url);
        } catch (OutboundUrlValidator.ValidationException e) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("ok", false, "error", e.getCode()));
            return;
        }

        long start = System.currentTimeMillis();
        try {
            String reqJson = gson.toJson(Map.of(
                "query", "musicbrainz",
                "search_depth", "basic",
                "max_results", 1
            ));
            HttpRequest probeReq = HttpRequest.newBuilder(URI.create(url))
                .timeout(PROBE_TIMEOUT)
                .header("Authorization", "Bearer " + tavilyKey.trim())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(reqJson))
                .build();
            ProbeResponse pr = executeProbe(probeReq, effectiveProxy);
            long latencyMs = System.currentTimeMillis() - start;
            if (pr.statusCode == 200) {
                respondJson(resp, HttpServletResponse.SC_OK, Map.of(
                    "ok", true, "statusCode", 200, "latencyMs", latencyMs));
            } else if (pr.statusCode == 401 || pr.statusCode == 403) {
                respondJson(resp, HttpServletResponse.SC_OK, Map.of(
                    "ok", false, "statusCode", pr.statusCode, "latencyMs", latencyMs, "error", "tavily.key.invalid"));
            } else {
                respondJson(resp, HttpServletResponse.SC_OK, Map.of(
                    "ok", false, "statusCode", pr.statusCode, "latencyMs", latencyMs,
                    "error", "tavily.http." + pr.statusCode));
            }
        } catch (Exception e) {
            respondJson(resp, HttpServletResponse.SC_OK, Map.of(
                "ok", false,
                "latencyMs", System.currentTimeMillis() - start,
                "error", "tavily.connect.failed",
                "detail", sanitizeErrorMessage(e.getMessage())
            ));
        }
    }

    private void handleDismissAcoustIdWarning(HttpServletResponse resp) throws IOException {
        setupStateStore.dismissAcoustIdWarning(adminStore);
        respondJson(resp, HttpServletResponse.SC_OK, Map.of("dismissed", true));
    }

    @SuppressWarnings("unchecked")
    private void handleComplete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body = readBodyAsMap(req);
        if (body == null) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "body.invalid"));
            return;
        }

        Map<String, Object> configMap;
        if (body.get("config") instanceof Map<?, ?> m) {
            configMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                if (entry.getKey() != null) {
                    configMap.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        } else {
            configMap = new LinkedHashMap<>(body);
            configMap.remove("providers");
        }
        boolean hasProviders = body.containsKey("providers");
        Object rawProviders = body.get("providers");

        synchronized (completeLock) {
            // 先预校验 LLM 供应商（含防改 URL 盗用掩码密钥），避免非法供应商留下半落盘配置
            List<LlmProviderConfig> preparedProviders = null;
            if (hasProviders && llmProviderServlet != null) {
                try {
                    preparedProviders = llmProviderServlet.prepareProviders(rawProviders);
                } catch (ConfigServlet.ValidationException e) {
                    respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of(
                        "error", e.error, "field", e.field == null ? "" : e.field));
                    return;
                }
            }

            boolean standbyMode = lifecycleManager != null && !lifecycleManager.isActive();
            ConfigServlet.ApplyResult cfgResult;
            if (configServlet != null) {
                try {
                    cfgResult = configServlet.applyConfigMap(configMap, true, standbyMode);
                } catch (ConfigServlet.ValidationException e) {
                    respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of(
                        "error", e.error, "field", e.field == null ? "" : e.field));
                    return;
                } catch (IOException e) {
                    log.error("初始化保存配置失败", e);
                    respondJson(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, Map.of("error", "save.failed"));
                    return;
                }
            } else {
                cfgResult = new ConfigServlet.ApplyResult(true, false, List.of());
            }

            if (preparedProviders != null && llmProviderServlet != null) {
                try {
                    llmProviderServlet.commitPreparedProviders(preparedProviders);
                } catch (IOException e) {
                    log.error("初始化保存 LLM 供应商失败", e);
                    respondJson(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, Map.of("error", "save.failed"));
                    return;
                }
            }

            boolean activated = false;
            if (standbyMode && lifecycleManager != null) {
                try {
                    lifecycleManager.activateAfterSetup();
                    activated = true;
                } catch (Exception e) {
                    log.error("初始化完成激活核心服务失败: {}", e.getMessage(), e);
                    String errCode = e.getMessage() != null && e.getMessage().startsWith("setup.")
                        ? e.getMessage() : "setup.activation.failed";
                    int status = errCode.startsWith("setup.monitor.") || errCode.startsWith("setup.output.")
                        ? HttpServletResponse.SC_BAD_REQUEST : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                    respondJson(resp, status, Map.of(
                        "completed", false,
                        "error", errCode,
                        "detail", sanitizeErrorMessage(e.getMessage())
                    ));
                    return;
                }
            }

            setupStateStore.markCompleted();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("completed", true);
            payload.put("activated", activated);
            payload.put("requiresRestart", !standbyMode && cfgResult.requiresRestart());
            payload.put("pendingRestartFields", standbyMode ? List.of() : cfgResult.pendingRestartFields());
            payload.put("acoustIdMissing", SetupStateStore.isAcoustIdPlaceholderOrEmpty(
                config == null ? null : config.getAcoustIdApiKey()));
            respondJson(resp, HttpServletResponse.SC_OK, payload);
        }
    }

    /** 构造向导透传的临时代理配置（含掩码密码防外泄：仅当 host 与 port 未变更时允许复用已存密码）。 */
    static MusicConfig buildTransientProxyConfig(Map<?, ?> proxyMap, MusicConfig savedConfig)
            throws ConfigServlet.ValidationException {
        boolean enabled = asBoolean(proxyMap.get("enabled"), false);
        if (!enabled) {
            return MusicConfig.forProxyOverride(false, "", 0, "", "");
        }
        String host = asString(proxyMap.get("host"));
        Integer port = asInteger(proxyMap.get("port"));
        if (host == null || host.isBlank() || port == null || port < 1 || port > 65535) {
            throw new ConfigServlet.ValidationException("proxy.invalid", "proxyHost");
        }
        String username = asString(proxyMap.get("username"));
        String password = asString(proxyMap.get("password"));
        if (SECRET_MASK.equals(password)) {
            boolean sameEndpoint = savedConfig != null
                && savedConfig.getProxyHost() != null
                && savedConfig.getProxyHost().trim().equalsIgnoreCase(host.trim())
                && savedConfig.getProxyPort() == port;
            if (!sameEndpoint && savedConfig != null
                    && savedConfig.getProxyPassword() != null && !savedConfig.getProxyPassword().isEmpty()) {
                throw new ConfigServlet.ValidationException("secret.required.on.endpoint.change", "proxyPassword");
            }
            password = sameEndpoint ? savedConfig.getProxyPassword() : "";
        }
        return MusicConfig.forProxyOverride(true, host.trim(), port,
            username == null ? "" : username.trim(),
            password == null ? "" : password);
    }

    private MusicConfig resolveEffectiveProxy(Map<String, Object> body) throws ConfigServlet.ValidationException {
        if (body != null && body.get("proxy") instanceof Map<?, ?> proxyMap) {
            return buildTransientProxyConfig(proxyMap, config);
        }
        return config;
    }

    private void assertUrlAllowed(String url) throws OutboundUrlValidator.ValidationException {
        boolean allowPrivate = config != null && config.isLlmAllowPrivateEndpoints();
        OutboundUrlValidator.validateAndResolve(url, allowPrivate, false, false);
    }

    protected record ProbeResponse(int statusCode, String body) {
    }

    /** 受控出站探测执行钩子（使用统一代理工厂、禁止重定向、有界响应读取；单测可覆写免外网依赖）。 */
    protected ProbeResponse executeProbe(HttpRequest request, MusicConfig proxyConfig)
            throws IOException, InterruptedException {
        HttpClient client = HttpClientFactory.jdkHttpClientBuilder(proxyConfig, PROBE_TIMEOUT).build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String body;
        try (InputStream in = response.body()) {
            body = OutboundUrlValidator.readBoundedString(in, MAX_PROBE_RESPONSE_BYTES);
        }
        return new ProbeResponse(response.statusCode(), body);
    }

    private String effectiveUserAgent(String submitted) {
        if (submitted != null && !submitted.isBlank()) {
            return submitted.trim();
        }
        if (config != null && config.getUserAgent() != null && !config.getUserAgent().isBlank()) {
            return config.getUserAgent().trim();
        }
        return "MusicAutoTagger/1.0 ( contact@example.com )";
    }

    private String resolveSecret(String incoming, String saved) {
        if (incoming == null) {
            return null;
        }
        String trimmed = incoming.trim();
        if (SECRET_MASK.equals(trimmed)) {
            return saved;
        }
        return trimmed;
    }

    private JsonObject parseJsonObjectSafe(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonElement el = gson.fromJson(text, JsonElement.class);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String sanitizeErrorMessage(String msg) {
        if (msg == null || msg.isBlank()) {
            return "";
        }
        String trimmed = msg.trim();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
    }

    private String normalizePath(String pathInfo) {
        if (pathInfo == null || "/".equals(pathInfo)) {
            return "";
        }
        return pathInfo.endsWith("/") ? pathInfo.substring(0, pathInfo.length() - 1) : pathInfo;
    }

    private boolean isAuthenticated(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        return session != null && session.getAttribute(SESSION_USER_KEY) != null;
    }

    private boolean isCsrfValid(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) {
            return false;
        }
        String token = req.getHeader("X-CSRF-Token");
        String sessionToken = (String) session.getAttribute(SESSION_CSRF_KEY);
        if (token == null || sessionToken == null) {
            return false;
        }
        return MessageDigest.isEqual(
            sessionToken.getBytes(StandardCharsets.UTF_8),
            token.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readBodyAsMap(HttpServletRequest req) throws IOException {
        try (BufferedReader reader = req.getReader()) {
            Map<String, Object> raw = gson.fromJson(reader, Map.class);
            return raw == null ? new HashMap<>() : raw;
        } catch (Exception e) {
            return null;
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean asBoolean(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static Integer asInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void respondJson(HttpServletResponse resp, int status, Map<String, Object> payload) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(status);
        resp.getWriter().write(gson.toJson(payload));
    }
}
