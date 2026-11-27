package com.lux032.musicautotagger.core;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.util.AdminCredentialsStore;
import com.lux032.musicautotagger.util.SetupStateStore;
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

import static org.junit.jupiter.api.Assertions.*;

class SetupLifecycleIntegrationTest {

    @TempDir
    Path tempDir;

    private MusicConfig newTestConfig(Path monitorDir, Path outputDir) throws Exception {
        var ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MusicConfig cfg = ctor.newInstance();
        cfg.setMonitorDirectory(monitorDir.toString());
        cfg.setOutputDirectory(outputDir.toString());
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
    void standbyModeBlocksBusinessApisAndActivatesInProcessWithoutRestart() throws Exception {
        Path missingMonitor = tempDir.resolve("missing-monitor");
        Path realMonitor = tempDir.resolve("monitor");
        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(realMonitor);

        MusicConfig cfg = newTestConfig(missingMonitor, outputDir);
        AdminCredentialsStore adminStore = new AdminCredentialsStore(tempDir.resolve("data/admin.json"));
        SetupStateStore stateStore = new SetupStateStore(tempDir.resolve("data/setup-state.json"));
        Path cfgPath = tempDir.resolve("config.properties");

        ApplicationLifecycleManager lifecycle = new ApplicationLifecycleManager(cfg, cfgPath, adminStore, stateStore);
        lifecycle.setWebPortOverride(0);
        lifecycle.startWebServerInSetupMode();

        try {
            assertFalse(lifecycle.isSetupCompleted());
            assertFalse(lifecycle.isActive());
            assertFalse(lifecycle.isMonitoringRunning());
            assertNull(lifecycle.getFileMonitor());

            String base = "http://127.0.0.1:" + lifecycle.getWebServer().getLocalPort();
            CookieManager cookies = new CookieManager();
            HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

            // 未登录访问业务接口返回 401
            HttpResponse<String> unauthDash = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/dashboard")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthDash.statusCode());

            // 建号登录
            HttpResponse<String> setupResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/auth/setup"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"password123\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, setupResp.statusCode());

            // 已登录但未激活 -> 业务路由返回 503 setup.required
            HttpResponse<String> standbyDash = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/dashboard")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(503, standbyDash.statusCode());
            assertTrue(standbyDash.body().contains("setup.required"));

            // 监控目录不存在时激活失败，且保持待命态可重试
            assertThrows(IOException.class, lifecycle::activateAfterSetup);
            assertFalse(lifecycle.isActive());
            assertFalse(lifecycle.isMonitoringRunning());

            // 修正目录后在同一 JVM / 同一 Jetty 端口进程内激活
            cfg.setMonitorDirectory(realMonitor.toString());
            lifecycle.activateAfterSetup();
            stateStore.markCompleted();

            assertTrue(lifecycle.isActive());
            assertTrue(lifecycle.isMonitoringRunning());
            assertTrue(Files.isDirectory(outputDir));

            // 同一端口立即返回 200 OK
            HttpResponse<String> activeDash = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/dashboard")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, activeDash.statusCode());
            assertTrue(activeDash.body().contains("\"monitorDirectory\""));
        } finally {
            lifecycle.shutdown();
        }
    }
}
