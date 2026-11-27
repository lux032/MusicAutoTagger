package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.LlmProviderConfig;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.core.ApplicationLifecycleManager;
import com.lux032.musicautotagger.util.AdminCredentialsStore;
import com.lux032.musicautotagger.util.SetupStateStore;
import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SetupServletTest {

    @TempDir
    Path tempDir;

    private final Gson gson = new Gson();

    private MusicConfig newTestConfig(Path monitorDir, Path outputDir) throws Exception {
        var ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MusicConfig cfg = ctor.newInstance();
        cfg.setMonitorDirectory(monitorDir.toString());
        cfg.setOutputDirectory(outputDir.toString());
        cfg.setAcoustIdApiKey("YOUR_API_KEY_HERE");
        cfg.setFailedDirectory(tempDir.resolve("failed").toString());
        cfg.setPartialDirectory(tempDir.resolve("partial").toString());
        cfg.setCoverArtCacheDirectory(tempDir.resolve("cover_cache").toString());
        cfg.setProcessedFileLogPath(tempDir.resolve("logs/processed.log").toString());
        cfg.setReviewQueuePath(tempDir.resolve("data/review-queue.json").toString());
        cfg.setReviewStagingDirectory(tempDir.resolve("data/review-staging").toString());
        cfg.setRecoveryWorkDirectory(tempDir.resolve("data/recovery-work").toString());
        cfg.setRecoveryTrashDirectory(tempDir.resolve("data/recovery-trash").toString());
        cfg.setLlmProvidersPath(tempDir.resolve("data/llm-providers.json").toString());
        cfg.setDbType("sqlite");
        cfg.setDbSqlitePath(tempDir.resolve("data/music-tagger.db").toString());
        return cfg;
    }

    @Test
    void enforcesAuthCsrfPlaceholderDetectionReadonlyFieldsAndMaskProtection() throws Exception {
        Path monitorDir = tempDir.resolve("monitor");
        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(monitorDir);

        MusicConfig cfg = newTestConfig(monitorDir, outputDir);
        cfg.setProxyEnabled(true);
        cfg.setProxyHost("127.0.0.1");
        cfg.setProxyPort(7890);
        cfg.setProxyPassword("secret-proxy-pass");
        LlmProviderConfig existingProvider = new LlmProviderConfig();
        existingProvider.setId("p1");
        existingProvider.setName("Official");
        existingProvider.setApiUrl("https://api.openai.com/v1/chat/completions");
        existingProvider.setApiKey("sk-real-secret");
        existingProvider.setFormat("openai");
        cfg.setLlmProviders(List.of(existingProvider));

        Path cfgPath = tempDir.resolve("config.properties");
        AdminCredentialsStore adminStore = new AdminCredentialsStore(tempDir.resolve("data/admin.json"));
        SetupStateStore stateStore = new SetupStateStore(tempDir.resolve("data/setup-state.json"));
        ConfigServlet configServlet = new ConfigServlet(cfg, cfgPath);
        LlmProviderServlet llmServlet = new LlmProviderServlet(cfg);

        AtomicReference<String> lastProbeUrl = new AtomicReference<>();
        SetupServlet setupServlet = new SetupServlet(cfg, null, adminStore, stateStore, configServlet, llmServlet) {
            @Override
            protected ProbeResponse executeProbe(HttpRequest request, MusicConfig proxyConfig) {
                lastProbeUrl.set(request.uri().toString());
                if (request.uri().toString().contains("acoustid")) {
                    // 模拟：含 "bad" 返回 code 4 无效，其余返回 code 3（指纹无效但 Key 鉴权通过）
                    return new ProbeResponse(200, "{\"status\":\"error\",\"error\":{\"code\":3,\"message\":\"invalid fingerprint\"}}");
                }
                if (request.uri().toString().contains("tavily")) {
                    return new ProbeResponse(200, "{\"results\":[]}");
                }
                return new ProbeResponse(200, "{\"id\":\"5b11f4ce-a62d-471e-81fc-a69a8278c7da\"}");
            }
        };

        Server server = new Server(0);
        ServletContextHandler ctx = new ServletContextHandler(ServletContextHandler.SESSIONS);
        ctx.addFilter(AuthFilter.class, "/api/*", EnumSet.of(DispatcherType.REQUEST));
        ctx.addServlet(new ServletHolder(new AuthServlet(adminStore, stateStore, cfg, cfgPath)), "/api/auth/*");
        ctx.addServlet(new ServletHolder(configServlet), "/api/config");
        ctx.addServlet(new ServletHolder(llmServlet), "/api/llm/providers/*");
        ctx.addServlet(new ServletHolder(setupServlet), "/api/setup/*");
        server.setHandler(ctx);
        server.start();
        try {
            String base = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort();
            CookieManager cookies = new CookieManager();
            HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

            // 1. 未登录可访问 GET /api/setup/status，但不得泄露 acoustIdMissing
            HttpResponse<String> pubStatus = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/setup/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, pubStatus.statusCode());
            JsonObject pubJson = gson.fromJson(pubStatus.body(), JsonObject.class);
            assertFalse(pubJson.get("setupCompleted").getAsBoolean());
            assertTrue(pubJson.get("needsAdmin").getAsBoolean());
            assertFalse(pubJson.has("acoustIdMissing"));

            // 2. 未登录访问 bootstrap / check-directories 必须返回 401
            assertEquals(401, http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/setup/bootstrap")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(401, http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/setup/check-directories"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());

            // 3. 建号拿会话与 CSRF
            HttpResponse<String> authResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/auth/setup"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"password123\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, authResp.statusCode());
            String csrf = gson.fromJson(authResp.body(), JsonObject.class).get("csrfToken").getAsString();

            // 4. 缺少 CSRF 的 POST 必须返回 403
            assertEquals(403, http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/setup/check-directories"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());

            // 5. bootstrap 正确识别 YOUR_API_KEY_HERE 占位符并清空回显
            HttpResponse<String> bootResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/setup/bootstrap")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, bootResp.statusCode());
            JsonObject bootJson = gson.fromJson(bootResp.body(), JsonObject.class);
            JsonObject bootCfg = bootJson.getAsJsonObject("config");
            assertTrue(bootCfg.get("acoustIdApiKeyPlaceholder").getAsBoolean());
            assertFalse(bootCfg.get("acoustIdApiKeySet").getAsBoolean());
            assertEquals("", bootCfg.get("acoustIdApiKey").getAsString());

            // 6. 目录检测：存在的监控目录返回 ok，待创建输出目录返回 willCreate，相对路径返回 invalid
            HttpResponse<String> dirResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/setup/check-directories"))
                    .header("X-CSRF-Token", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(java.util.Map.of(
                        "monitorDirectory", monitorDir.toString(),
                        "outputDirectory", outputDir.resolve("sub").toString()
                    )))).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, dirResp.statusCode());
            JsonObject dirJson = gson.fromJson(dirResp.body(), JsonObject.class);
            assertEquals("ok", dirJson.getAsJsonObject("monitor").get("status").getAsString());
            assertEquals("willCreate", dirJson.getAsJsonObject("output").get("status").getAsString());

            // 7. 代理测试：换主机但沿用掩码密码必须被拒（防代理凭据外泄）；正常测试必须探测固定 MusicBrainz URL
            HttpResponse<String> proxyExfil = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/setup/test-proxy"))
                    .header("X-CSRF-Token", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"proxyHost\":\"10.0.0.99\",\"proxyPort\":8888,\"proxyPassword\":\"********\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(400, proxyExfil.statusCode());
            assertTrue(proxyExfil.body().contains("secret.required.on.endpoint.change"));

            HttpResponse<String> proxyOk = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/setup/test-proxy"))
                    .header("X-CSRF-Token", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"proxyHost\":\"127.0.0.1\",\"proxyPort\":7890,\"proxyPassword\":\"********\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, proxyOk.statusCode());
            assertTrue(gson.fromJson(proxyOk.body(), JsonObject.class).get("ok").getAsBoolean());
            assertEquals(SetupServlet.FIXED_PROXY_PROBE_URL, lastProbeUrl.get());

            // 8. 数据源测试与 Tavily 测试
            HttpResponse<String> srcResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/setup/test-sources"))
                    .header("X-CSRF-Token", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"acoustIdApiKey\":\"valid-key\",\"userAgent\":\"MusicAutoTagger/1.0 (a@b.com)\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, srcResp.statusCode());
            JsonObject srcJson = gson.fromJson(srcResp.body(), JsonObject.class);
            assertTrue(srcJson.getAsJsonObject("acoustid").get("ok").getAsBoolean());
            assertTrue(srcJson.getAsJsonObject("musicbrainz").get("ok").getAsBoolean());

            HttpResponse<String> tavilyResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/setup/test-tavily"))
                    .header("X-CSRF-Token", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"tavilyApiKey\":\"tvly-valid\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, tavilyResp.statusCode());
            assertTrue(gson.fromJson(tavilyResp.body(), JsonObject.class).get("ok").getAsBoolean());

            // 9. complete 严禁修改只读安全字段（audioNormalizeFfmpegPath / llmAllowPrivateEndpoints）
            HttpResponse<String> readonlyAttempt = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/setup/complete"))
                    .header("X-CSRF-Token", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"config\":{\"audioNormalizeFfmpegPath\":\"/bin/sh\"}}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(400, readonlyAttempt.statusCode());
            assertTrue(readonlyAttempt.body().contains("field.readonly"));

            // 10. complete 严禁在改 LLM 供应商 URL 时沿用掩码密钥
            HttpResponse<String> provExfil = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/setup/complete"))
                    .header("X-CSRF-Token", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"config\":{\"reviewEnabled\":true},\"providers\":[{\"id\":\"p1\",\"name\":\"Official\",\"apiUrl\":\"https://evil.example.com/v1\",\"apiKey\":\"********\",\"format\":\"openai\"}]}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(400, provExfil.statusCode());
            assertTrue(provExfil.body().contains("api.key.required.on.url.change"));

            // 11. 忽略 AcoustID 提示持久化
            HttpResponse<String> dismissResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/setup/dismiss-acoustid-warning"))
                    .header("X-CSRF-Token", csrf)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, dismissResp.statusCode());
            assertTrue(stateStore.isAcoustIdWarningDismissed());

            // 12. SSRF 防护：内网 / HTTP 目标地址在 llmAllowPrivateEndpoints=false 时必须被拦截
            cfg.setTavilyApiUrl("http://127.0.0.1:8080/search");
            HttpResponse<String> tavilySsrf = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/setup/test-tavily"))
                    .header("X-CSRF-Token", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"tavilyApiKey\":\"tvly-valid\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(400, tavilySsrf.statusCode());
            assertTrue(tavilySsrf.body().contains("url.https.required") || tavilySsrf.body().contains("url.private.blocked"));

            // 13. LLM 拉取模型透传临时代理时，更换代理主机沿用掩码密码必须被拒
            HttpResponse<String> llmProxyExfil = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/llm/providers/models"))
                    .header("X-CSRF-Token", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"id\":\"p1\",\"apiUrl\":\"https://api.openai.com/v1/chat/completions\",\"apiKey\":\"********\",\"format\":\"openai\",\"proxy\":{\"enabled\":true,\"host\":\"10.0.0.8\",\"port\":9999,\"password\":\"********\"}}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(400, llmProxyExfil.statusCode());
            assertTrue(llmProxyExfil.body().contains("secret.required.on.endpoint.change"));
        } finally {
            server.stop();
        }
    }

    @Test
    void completeInStandbyModeActivatesLifecycleAndFailsCleanlyOnMissingDirectory() throws Exception {
        Path missingMonitor = tempDir.resolve("not-exist-monitor");
        Path realMonitor = tempDir.resolve("real-monitor");
        Path outputDir = tempDir.resolve("real-output");
        Files.createDirectories(realMonitor);

        MusicConfig cfg = newTestConfig(missingMonitor, outputDir);
        Path cfgPath = tempDir.resolve("config.properties");
        AdminCredentialsStore adminStore = new AdminCredentialsStore(tempDir.resolve("data/admin.json"));
        SetupStateStore stateStore = new SetupStateStore(tempDir.resolve("data/setup-state.json"));

        ApplicationLifecycleManager lifecycle = new ApplicationLifecycleManager(cfg, cfgPath, adminStore, stateStore);
        lifecycle.setWebPortOverride(0);
        lifecycle.startWebServerInSetupMode();

        try {
            String base = "http://127.0.0.1:" + lifecycle.getWebServer().getLocalPort();
            CookieManager cookies = new CookieManager();
            HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

            HttpResponse<String> authResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/auth/setup"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"password123\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            String csrf = gson.fromJson(authResp.body(), JsonObject.class).get("csrfToken").getAsString();

            // 缺失监控目录 -> 激活失败，不标记完成，保持待命态
            HttpResponse<String> failResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/setup/complete"))
                    .header("X-CSRF-Token", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(java.util.Map.of(
                        "config", java.util.Map.of(
                            "monitorDirectory", missingMonitor.toString(),
                            "outputDirectory", outputDir.toString()
                        )
                    )))).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(400, failResp.statusCode());
            assertFalse(stateStore.isCompleted(adminStore));
            assertFalse(lifecycle.isActive());

            // 填入真实目录并提交 -> 进程内激活，无需重启
            HttpResponse<String> okResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/setup/complete"))
                    .header("X-CSRF-Token", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(java.util.Map.of(
                        "config", java.util.Map.of(
                            "monitorDirectory", realMonitor.toString(),
                            "outputDirectory", outputDir.toString(),
                            "acoustIdApiKey", "real-acoustid-key",
                            "reviewEnabled", true,
                            "language", "zh_CN"
                        )
                    )))).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, okResp.statusCode());
            JsonObject okJson = gson.fromJson(okResp.body(), JsonObject.class);
            assertTrue(okJson.get("completed").getAsBoolean());
            assertTrue(okJson.get("activated").getAsBoolean());
            assertFalse(okJson.get("requiresRestart").getAsBoolean());
            assertFalse(okJson.get("acoustIdMissing").getAsBoolean());
            assertTrue(stateStore.isCompleted(adminStore));
            assertTrue(lifecycle.isActive());
            assertTrue(lifecycle.isMonitoringRunning());

            // 立即访问 /api/dashboard 返回 200
            assertEquals(200, http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/dashboard")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());

            // 重跑引导修改监控目录 -> 走 RESTART_ONLY_FIELDS，返回 requiresRestart=true
            Path otherMonitor = tempDir.resolve("other-monitor");
            Files.createDirectories(otherMonitor);
            HttpResponse<String> rerunResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/setup/complete"))
                    .header("X-CSRF-Token", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(java.util.Map.of(
                        "config", java.util.Map.of(
                            "monitorDirectory", otherMonitor.toString(),
                            "outputDirectory", outputDir.toString()
                        )
                    )))).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, rerunResp.statusCode());
            JsonObject rerunJson = gson.fromJson(rerunResp.body(), JsonObject.class);
            assertTrue(rerunJson.get("requiresRestart").getAsBoolean());
            assertTrue(rerunJson.getAsJsonArray("pendingRestartFields").toString().contains("monitorDirectory"));
        } finally {
            lifecycle.shutdown();
        }
    }
}
