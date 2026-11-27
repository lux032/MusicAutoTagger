package com.lux032.musicautotagger.util;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.web.AuthServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SetupStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void freshInstallIsNotCompletedAndLegacyAdminIsCompleted() throws Exception {
        AdminCredentialsStore adminStore = new AdminCredentialsStore(tempDir.resolve("data/admin.json"));
        SetupStateStore stateStore = new SetupStateStore(tempDir.resolve("data/setup-state.json"));

        // 全新部署：两者皆无 -> 未初始化
        assertFalse(stateStore.isCompleted(adminStore));
        assertFalse(stateStore.exists());

        // 老用户升级：已有 admin.json 但无 setup-state.json -> 视为已完成，不打扰
        adminStore.save(new AdminCredentialsStore.AdminCredentials("legacyAdmin", "$2a$12$hash", "2026-01-01T00:00:00Z"));
        assertTrue(stateStore.isCompleted(adminStore));
        assertFalse(stateStore.exists());

        // 老用户忽略 AcoustID 提示时不得把 completed 降级为 false
        stateStore.dismissAcoustIdWarning(adminStore);
        assertTrue(stateStore.exists());
        assertTrue(stateStore.isCompleted(adminStore));
        assertTrue(stateStore.isAcoustIdWarningDismissed());
    }

    @Test
    void midSetupAdminCreationKeepsCompletedFalseUntilMarkedCompleted() throws Exception {
        AdminCredentialsStore adminStore = new AdminCredentialsStore(tempDir.resolve("data/admin.json"));
        SetupStateStore stateStore = new SetupStateStore(tempDir.resolve("data/setup-state.json"));
        Path cfgPath = tempDir.resolve("config.properties");
        var ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MusicConfig cfg = ctor.newInstance();

        Server server = new Server(0);
        ServletContextHandler ctx = new ServletContextHandler(ServletContextHandler.SESSIONS);
        ctx.addServlet(new ServletHolder(new AuthServlet(adminStore, stateStore, cfg, cfgPath)), "/api/auth/*");
        server.setHandler(ctx);
        server.start();
        try {
            int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/setup"))
                    .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"admin\",\"password\":\"strongpass1\",\"language\":\"zh_CN\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            assertTrue(resp.body().contains("\"setupCompleted\": false"));

            // 建号后即便重启，因为 setup-state.json 已写入 completed=false，仍判定为未完成引导
            assertTrue(adminStore.exists());
            assertTrue(stateStore.exists());
            assertFalse(stateStore.isCompleted(adminStore));
            assertEquals("zh_CN", cfg.getLanguage());
            assertTrue(Files.readString(cfgPath).contains("i18n.language=zh_CN"));

            // 标记完成后落盘且可重载
            stateStore.markCompleted();
            SetupStateStore reloaded = new SetupStateStore(stateStore.getPath());
            assertTrue(reloaded.isCompleted(adminStore));
            assertNotNull(reloaded.load().completedAt);
        } finally {
            server.stop();
        }
    }

    @Test
    void acoustIdPlaceholderDetectionMatchesEmptyAndTemplateValue() {
        assertTrue(SetupStateStore.isAcoustIdPlaceholderOrEmpty(null));
        assertTrue(SetupStateStore.isAcoustIdPlaceholderOrEmpty(""));
        assertTrue(SetupStateStore.isAcoustIdPlaceholderOrEmpty("   "));
        assertTrue(SetupStateStore.isAcoustIdPlaceholderOrEmpty("YOUR_API_KEY_HERE"));
        assertTrue(SetupStateStore.isAcoustIdPlaceholderOrEmpty(" your_api_key_here "));
        assertFalse(SetupStateStore.isAcoustIdPlaceholderOrEmpty("real-key-12345"));
    }
}
