package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.service.DatabaseService;
import com.lux032.musicautotagger.service.ProcessedFileLogger;
import com.lux032.musicautotagger.service.ProcessedRecordService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

/** RecordsServlet 端到端：JSON 结构、CSRF、批量操作与 CSV 导出。 */
class RecordsServletTest {
    @TempDir
    Path tempDir;

    private Server server;
    private DatabaseService db;
    private String base;
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    private final Gson gson = new Gson();

    @BeforeEach
    void start() throws Exception {
        var ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MusicConfig config = ctor.newInstance();
        config.setDbSqlitePath(tempDir.resolve("web.db").toString());
        config.setMonitorDirectory(tempDir.resolve("music").toString());
        db = new DatabaseService(config);
        ProcessedFileLogger logger = new ProcessedFileLogger(config, db);

        for (int i = 1; i <= 3; i++) {
            Path f = Files.createDirectories(tempDir.resolve("music/Album")).resolve("0" + i + ".flac");
            Files.writeString(f, "x");
            logger.markFileAsProcessed(f.toFile(), "rec-" + i, "Artist", "Song " + i, "Album, \"Deluxe\"", "rg", null);
        }
        // 处理后源文件被删除的失败记录
        Path gone = Files.writeString(tempDir.resolve("gone.mp3"), "x");
        logger.markFileAsProcessed(gone.toFile(), "FAILED", "=cmd", "gone.mp3", "Unknown Album");
        Files.delete(gone);

        ProcessedRecordService service = new ProcessedRecordService(config, logger, null, null, null, () -> null);
        server = new Server(0);
        ServletContextHandler ctx = new ServletContextHandler(ServletContextHandler.SESSIONS);
        // 模拟已登录会话：固定 CSRF token
        Filter login = (req, resp, chain) -> {
            ((HttpServletRequest) req).getSession(true).setAttribute("csrfToken", "tok");
            chain.doFilter(req, resp);
        };
        ctx.addFilter(new FilterHolder(login), "/*", EnumSet.of(DispatcherType.REQUEST));
        ctx.addServlet(new ServletHolder(new RecordsServlet(service, config)), "/api/records/*");
        server.setHandler(ctx);
        server.start();
        base = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    @AfterEach
    void stop() throws Exception {
        if (server != null) server.stop();
        if (db != null) db.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String json, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) b.header("X-CSRF-Token", token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void listReturnsPageCountsAndEnrichedItems() throws Exception {
        HttpResponse<String> resp = get("/api/records/list?limit=2&sort=TITLE&status=SUCCESS");
        assertEquals(200, resp.statusCode());
        JsonObject body = gson.fromJson(resp.body(), JsonObject.class);
        assertEquals(3, body.get("total").getAsInt());
        assertEquals(4, body.getAsJsonObject("counts").get("ALL").getAsInt());
        assertEquals(1, body.getAsJsonObject("counts").get("FAILED").getAsInt());
        assertEquals("SQLite", body.get("storage").getAsString());
        var items = body.getAsJsonArray("items");
        assertEquals(2, items.size());
        JsonObject first = items.get(0).getAsJsonObject();
        assertEquals("Song 1", first.get("title").getAsString());
        assertEquals("SUCCESS", first.get("status").getAsString());
        assertTrue(first.get("sourceExists").getAsBoolean());
        assertTrue(first.get("inMonitorDirectory").getAsBoolean());
        assertNotNull(first.get("processedTime"));
    }

    @Test
    void postRequiresCsrfToken() throws Exception {
        get("/api/records/list"); // 建立会话
        assertEquals(403, post("/api/records/forget", "{\"paths\":[\"x\"]}", null).statusCode());
        assertEquals(403, post("/api/records/forget", "{\"paths\":[\"x\"]}", "wrong").statusCode());
        assertEquals(400, post("/api/records/forget", "{}", "tok").statusCode());
    }

    @Test
    void previewThenReidentifyByFilterAndForgetByPath() throws Exception {
        get("/api/records/list");
        String albumFilter = "{\"filter\":{\"album\":\"Album, \\\"Deluxe\\\"\"}}";
        JsonObject preview = gson.fromJson(post("/api/records/preview", albumFilter, "tok").body(), JsonObject.class);
        assertEquals(3, preview.get("total").getAsInt());
        assertEquals(3, preview.get("requeueable").getAsInt());
        assertFalse(preview.get("monitoringActive").getAsBoolean());

        HttpResponse<String> rejected = post("/api/records/reidentify", albumFilter, "tok");
        assertEquals(500, rejected.statusCode());
        JsonObject reid = gson.fromJson(rejected.body(), JsonObject.class);
        assertEquals("records.operation.failed", reid.get("error").getAsString());
        assertTrue(reid.get("message").getAsString().contains("consumer unavailable"));

        String gone = new File(tempDir.toFile(), "gone.mp3").getAbsolutePath();
        JsonObject missingPreview = gson.fromJson(post("/api/records/preview",
            gson.toJson(java.util.Map.of("paths", java.util.List.of(gone))), "tok").body(), JsonObject.class);
        assertEquals(1, missingPreview.get("sourceMissing").getAsInt());

        JsonObject forget = gson.fromJson(post("/api/records/forget",
            gson.toJson(java.util.Map.of("paths", java.util.List.of(gone))), "tok").body(), JsonObject.class);
        assertEquals(1, forget.get("removed").getAsInt());

        JsonObject after = gson.fromJson(get("/api/records/list").body(), JsonObject.class);
        assertEquals(3, after.get("total").getAsInt(), "Rejected reidentify preserves the three album records");
    }

    @Test
    void exportProducesExcelFriendlyCsv() throws Exception {
        HttpResponse<String> resp = get("/api/records/export?q=" + URLEncoder.encode("", StandardCharsets.UTF_8));
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Disposition").orElse("").contains("processed-records-"));
        String csv = resp.body();
        assertTrue(csv.startsWith("\uFEFFstatus,processed_time"), "UTF-8 BOM + 表头");
        assertTrue(csv.contains("\"Album, \"\"Deluxe\"\"\""), "逗号与引号需正确转义");
        assertTrue(csv.contains(",'=cmd,"), "公式注入防护");
        assertEquals(5, csv.split("\r\n").length);
    }
}
