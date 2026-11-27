package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.util.I18nUtil;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

class I18nServletTest {

    private final Gson gson = new Gson();

    @AfterEach
    void restoreLanguage() {
        I18nUtil.init("zh_CN");
    }

    @Test
    void supportsReadOnlyLanguagePreviewAndFallsBackOnInvalidLang() throws Exception {
        I18nUtil.init("en_US");

        Server server = new Server(0);
        ServletContextHandler ctx = new ServletContextHandler();
        ctx.addServlet(new ServletHolder(new I18nServlet()), "/api/i18n");
        server.setHandler(ctx);
        server.start();
        try {
            String base = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort();
            HttpClient http = HttpClient.newHttpClient();

            // 预览 zh_CN 不得篡改全局当前语言（仍为 en_US）
            HttpResponse<String> zhResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/i18n?lang=zh_CN")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, zhResp.statusCode());
            JsonObject zhJson = gson.fromJson(zhResp.body(), JsonObject.class);
            assertEquals("zh_CN", zhJson.get("language").getAsString());
            assertEquals("扫描间隔（秒）", zhJson.getAsJsonObject("messages").get("config.scan.interval").getAsString());
            assertEquals("en_US", I18nUtil.getCurrentLanguage());

            // 非法语言参数（含路径穿越尝试）安全回退到当前全局语言
            HttpResponse<String> invalidResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/i18n?lang=../evil")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, invalidResp.statusCode());
            JsonObject invalidJson = gson.fromJson(invalidResp.body(), JsonObject.class);
            assertEquals("en_US", invalidJson.get("language").getAsString());
            assertEquals("Scan Interval (seconds)", invalidJson.getAsJsonObject("messages").get("config.scan.interval").getAsString());
        } finally {
            server.stop();
        }
    }
}
