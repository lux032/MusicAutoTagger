package com.lux032.musicautotagger;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.core.ApplicationLifecycleManager;
import com.lux032.musicautotagger.service.CoverMissingService;
import com.lux032.musicautotagger.service.LogCollector;
import com.lux032.musicautotagger.service.RecoveryService;
import com.lux032.musicautotagger.service.ReviewQueueService;
import com.lux032.musicautotagger.util.FileSystemUtils;
import com.lux032.musicautotagger.util.I18nUtil;
import com.lux032.musicautotagger.web.ControlServlet;
import com.lux032.musicautotagger.web.RecoveryServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 3 (P1) 回归测试：覆盖 U-1 ~ U-3（前端交互、i18n 与轮询/认证）与 O-1（部署/运维配置）。
 */
class P1Task3FrontendI18nOpsRegressionTest {

    private static final Gson GSON = new Gson();
    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath().normalize();

    @AfterEach
    void restoreDefaultLanguage() {
        I18nUtil.init("zh_CN");
        LogCollector.clear();
    }

    private static HttpServletRequest stubRequest(String pathInfo) {
        return (HttpServletRequest) Proxy.newProxyInstance(
            HttpServletRequest.class.getClassLoader(),
            new Class<?>[]{HttpServletRequest.class},
            (proxy, method, args) -> {
                if ("getPathInfo".equals(method.getName())) return pathInfo;
                return defaultValue(method.getReturnType());
            });
    }

    private static HttpServletResponse stubResponse(StringWriter bodyOut) {
        PrintWriter writer = new PrintWriter(bodyOut);
        return (HttpServletResponse) Proxy.newProxyInstance(
            HttpServletResponse.class.getClassLoader(),
            new Class<?>[]{HttpServletResponse.class},
            (proxy, method, args) -> {
                if ("getWriter".equals(method.getName())) return writer;
                if ("flushBuffer".equals(method.getName())) {
                    writer.flush();
                    return null;
                }
                return defaultValue(method.getReturnType());
            });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static MusicConfig newConfig() throws Exception {
        var ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static MusicConfig loadConfig(Path path) throws Exception {
        MusicConfig cfg = newConfig();
        cfg.loadFromFile(path);
        return cfg;
    }

    // ==================== O-1: 部署、权限、端口与优雅重启 ====================

    @Test
    void o1_dockerComposeAndEntrypointAndDebugArtifactCleanup() throws Exception {
        String compose = Files.readString(PROJECT_ROOT.resolve("docker-compose.yml"), StandardCharsets.UTF_8);
        assertTrue(compose.contains("ports:"), "docker-compose.yml should expose ports mapping");
        assertTrue(compose.contains(":8080"), "docker-compose.yml should map container port 8080");

        String dockerfile = Files.readString(PROJECT_ROOT.resolve("Dockerfile"), StandardCharsets.UTF_8);
        assertTrue(dockerfile.contains("gosu"), "Dockerfile should install gosu for privilege drop");

        String entrypoint = Files.readString(PROJECT_ROOT.resolve("entrypoint.sh"), StandardCharsets.UTF_8);
        assertFalse(entrypoint.contains("chown -R \"$PUID:$PGID\" /app /music"),
            "entrypoint.sh must NOT recursively chown /music or entire /app");
        assertFalse(entrypoint.contains("su -m"),
            "entrypoint.sh must not rely on su -m -c wrapper");
        assertTrue(entrypoint.contains("exec gosu"), "entrypoint.sh should exec via gosu");

        assertFalse(Files.exists(PROJECT_ROOT.resolve("src/main/java/TestAcoustIDMeta.java")),
            "TestAcoustIDMeta.java debug script should be removed from main sources");
    }

    @Test
    void o1_musicConfigParsesValidatesAndPersistsWebPort(@TempDir Path tempDir) throws Exception {
        Path cfgPath = tempDir.resolve("config.properties");
        Files.writeString(cfgPath, "web.port=9091\n", StandardCharsets.UTF_8);

        MusicConfig loaded = loadConfig(cfgPath);
        assertEquals(9091, loaded.getWebPort());

        loaded.setWebPort(18080);
        Path savedPath = tempDir.resolve("saved.properties");
        loaded.saveToFile(savedPath);
        String savedContent = Files.readString(savedPath, StandardCharsets.UTF_8);
        assertTrue(savedContent.contains("web.port=18080"));
        assertEquals(18080, loadConfig(savedPath).getWebPort());

        // 非法端口应安全回落为 8080
        Path invalidPath = tempDir.resolve("invalid.properties");
        Files.writeString(invalidPath, "web.port=99999\n", StandardCharsets.UTF_8);
        assertEquals(8080, loadConfig(invalidPath).getWebPort());
    }

    @Test
    void o1_controlServletRestartCallsShutdownBeforeExitProcess() throws Exception {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch exited = new CountDownLatch(1);

        ApplicationLifecycleManager lifecycle = new ApplicationLifecycleManager(newConfig()) {
            @Override
            public void shutdown() {
                order.add("shutdown");
            }
        };

        I18nUtil.init("en_US");
        ControlServlet servlet = new ControlServlet(lifecycle) {
            @Override
            protected void exitProcess() {
                order.add("exit");
                exited.countDown();
            }
        };

        StringWriter body = new StringWriter();
        HttpServletRequest req = stubRequest("/restart");
        HttpServletResponse resp = stubResponse(body);

        Method doPost = ControlServlet.class.getDeclaredMethod("doPost", HttpServletRequest.class, HttpServletResponse.class);
        doPost.setAccessible(true);
        doPost.invoke(servlet, req, resp);

        assertTrue(exited.await(5, TimeUnit.SECONDS), "restart thread should invoke exitProcess()");
        assertEquals(List.of("shutdown", "exit"), order, "shutdown() must finish before exitProcess()");

        Map<String, Object> json = GSON.fromJson(body.toString(), new TypeToken<Map<String, Object>>() {}.getType());
        assertEquals(Boolean.TRUE, json.get("success"));
        assertEquals("control.restarting.msg", json.get("messageKey"));
        assertEquals("Service is restarting", json.get("message"));
    }

    // ==================== U-2: i18n 重复键、多行格式化、错误码与后端文案 ====================

    @Test
    void u2_i18nFormatsMultilineTemplatesAndHasNoDuplicateScanIntervalKey() throws Exception {
        String formatted = I18nUtil.formatMessage("Line 1: {0}\nLine 2: {1}", "Alpha", 42);
        assertEquals("Line 1: Alpha\nLine 2: 42", formatted);

        // 校验 properties 文件中 config.scan.interval 仅出现一次，且保留带单位文案
        for (String lang : List.of("en_US", "zh_CN")) {
            Path propPath = PROJECT_ROOT.resolve("src/main/resources/messages_" + lang + ".properties");
            List<String> lines = Files.readAllLines(propPath, StandardCharsets.UTF_8);
            long count = lines.stream().filter(l -> l.startsWith("config.scan.interval=")).count();
            assertEquals(1, count, "config.scan.interval should appear exactly once in messages_" + lang + ".properties");
        }

        I18nUtil.init("en_US");
        assertEquals("Scan Interval (seconds)", I18nUtil.getMessage("config.scan.interval"));
        I18nUtil.init("zh_CN");
        assertEquals("扫描间隔（秒）", I18nUtil.getMessage("config.scan.interval"));
    }

    @Test
    void u2_coverMissingServiceScanStatusExposesLocalizedMessageKey(@TempDir Path tempDir) throws Exception {
        Path monitorDir = tempDir.resolve("monitor");
        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(monitorDir);
        Files.createDirectories(outputDir);

        MusicConfig config = newConfig();
        config.setMonitorDirectory(monitorDir.toString());
        config.setOutputDirectory(outputDir.toString());
        config.setReviewQueuePath(tempDir.resolve("review-queue.json").toString());

        ReviewQueueService queue = new ReviewQueueService(config);

        I18nUtil.init("en_US");
        try (CoverMissingService service = new CoverMissingService(
                config, queue, null, null, null, null, null)) {
            assertTrue(service.startLibraryScan());
            long deadline = System.currentTimeMillis() + 5000;
            while (service.isLibraryScanRunning() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertFalse(service.isLibraryScanRunning());
            Map<String, Object> status = service.getScanStatus();
            assertEquals("covers.scan.status.done", status.get("messageKey"));
            assertTrue(String.valueOf(status.get("message")).startsWith("Scan complete:"),
                "Expected English scan completion message, got: " + status.get("message"));
        }
    }

    @Test
    void u2_coversHtmlMapsAllBackendErrorCodes() throws Exception {
        String coversHtml = Files.readString(
            PROJECT_ROOT.resolve("src/main/resources/static/covers.html"), StandardCharsets.UTF_8);
        for (String realCode : List.of(
                "unreject.log.failed",
                "recovery.no.audio.files",
                "release.id.required",
                "candidates.expand.failed",
                "local.archive.stale",
                "local.archive.metadata.required",
                "local.archive.tracks.invalid",
                "local.archive.write.failed",
                "online\\.track\\.coverage\\.insufficient:",
                "recovery\\.workspace\\.count\\.mismatch:")) {
            assertTrue(coversHtml.contains(realCode),
                "covers.html should map backend error code: " + realCode);
        }
    }

    // ==================== U-3 & U-1: 轻量 summary 接口、日志递增 seq、前端竞态与轮询防护 ====================

    @Test
    void u3_recoverySummaryEndpointCountsPartialFailedAndTrashAccurately(@TempDir Path tempDir) throws Exception {
        Path partialDir = tempDir.resolve("partial");
        Path failedDir = tempDir.resolve("failed");
        Path trashDir = tempDir.resolve("trash");
        Path workDir = tempDir.resolve("work");
        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(partialDir.resolve("AlbumP1"));
        Files.writeString(partialDir.resolve("AlbumP1/01.flac"), "fake");
        Files.createDirectories(failedDir.resolve("ArtistGroup/AlbumF1"));
        Files.writeString(failedDir.resolve("ArtistGroup/AlbumF1/01.mp3"), "fake");
        Files.createDirectories(failedDir.resolve("ArtistGroup/AlbumF2"));
        Files.writeString(failedDir.resolve("ArtistGroup/AlbumF2/01.flac"), "fake");
        long now = System.currentTimeMillis();
        String trashName = now + "_1_job_FAILED_OldAlbum";
        Files.createDirectories(trashDir.resolve(trashName));
        Files.writeString(trashDir.resolve(trashName + ".json"), "{\"trashedAt\":" + now + "}");
        Files.createDirectories(trashDir.resolve(".pending-replace-ignoreme"));
        Files.createDirectories(outputDir);

        MusicConfig config = newConfig();
        config.setPartialDirectory(partialDir.toString());
        config.setFailedDirectory(failedDir.toString());
        config.setRecoveryTrashDirectory(trashDir.toString());
        config.setRecoveryWorkDirectory(workDir.toString());
        config.setOutputDirectory(outputDir.toString());
        config.setReviewQueuePath(tempDir.resolve("review-queue.json").toString());

        FileSystemUtils fs = new FileSystemUtils(config);
        try (RecoveryService recovery = new RecoveryService(
                config, null, null, null, null, null, fs, null, null, null)) {
            Map<String, Object> summary = recovery.countItemsSummary();
            assertEquals(1, summary.get("partial"));
            assertEquals(2, summary.get("failed"));
            assertEquals(3, summary.get("total"));
            assertEquals(1, summary.get("trashCount"));

            // 通过 RecoveryServlet GET /api/recovery/summary 校验 HTTP 响应
            RecoveryServlet servlet = new RecoveryServlet(recovery);
            StringWriter out = new StringWriter();
            HttpServletRequest req = stubRequest("/summary");
            HttpServletResponse resp = stubResponse(out);

            Method doGet = RecoveryServlet.class.getDeclaredMethod("doGet", HttpServletRequest.class, HttpServletResponse.class);
            doGet.setAccessible(true);
            doGet.invoke(servlet, req, resp);

            Map<String, Object> httpBody = GSON.fromJson(out.toString(), new TypeToken<Map<String, Object>>() {}.getType());
            assertEquals(1.0, ((Number) httpBody.get("partial")).doubleValue());
            assertEquals(2.0, ((Number) httpBody.get("failed")).doubleValue());
            assertEquals(3.0, ((Number) httpBody.get("total")).doubleValue());
            assertEquals(1.0, ((Number) httpBody.get("trashCount")).doubleValue());
        }
    }

    @Test
    void u3_logCollectorAssignsStrictlyIncreasingSeq() {
        LogCollector.clear();
        LogCollector.addLog("INFO", "first");
        LogCollector.addLog("WARN", "second");
        LogCollector.addLog("ERROR", "third");

        List<LogCollector.LogEntry> logs = LogCollector.getRecentLogs(10);
        assertEquals(3, logs.size());
        assertTrue(logs.get(0).getSeq() > 0);
        assertTrue(logs.get(1).getSeq() > logs.get(0).getSeq());
        assertTrue(logs.get(2).getSeq() > logs.get(1).getSeq());
    }

    @Test
    void u1AndU3_frontendAssetsContainRacePollingAuthAndFeedbackFixes() throws Exception {
        String reviewHtml = Files.readString(PROJECT_ROOT.resolve("src/main/resources/static/review.html"), StandardCharsets.UTF_8);
        assertTrue(reviewHtml.contains("id=\"detailSearchLiveSlot\""), "review.html should have surgical live-card slot");
        assertTrue(reviewHtml.contains("syncDetailSearchLiveCard()"), "review.html should avoid full renderDetail() on queue tick");
        assertTrue(reviewHtml.contains("resp.status === 401"), "review.html should redirect on 401");

        String coversHtml = Files.readString(PROJECT_ROOT.resolve("src/main/resources/static/covers.html"), StandardCharsets.UTF_8);
        assertTrue(coversHtml.contains("listSeq:") && coversHtml.contains("itemSeq:"), "covers.html should track request sequence tokens");
        assertTrue(coversHtml.contains("input.value = ''"), "covers.html should reset file input after upload");

        String sqJs = Files.readString(PROJECT_ROOT.resolve("src/main/resources/static/search-queue.js"), StandardCharsets.UTF_8);
        assertTrue(sqJs.contains("hasLoadedOnce"), "search-queue.js should not treat initial finished history as newlyFinished");
        assertTrue(sqJs.contains("function stop()"), "search-queue.js should expose stop()");
        assertTrue(sqJs.contains("e.status === 401"), "search-queue.js should halt polling on 401");

        String shellJs = Files.readString(PROJECT_ROOT.resolve("src/main/resources/static/shell.js"), StandardCharsets.UTF_8);
        assertTrue(shellJs.contains("/api/recovery/summary"), "shell.js should poll lightweight /api/recovery/summary");
        assertTrue(shellJs.contains("document.hidden"), "shell.js should pause polling when hidden");
        assertTrue(shellJs.contains("resp.status === 401"), "shell.js should stop and redirect on 401");

        String recoveryHtml = Files.readString(PROJECT_ROOT.resolve("src/main/resources/static/recovery.html"), StandardCharsets.UTF_8);
        assertTrue(recoveryHtml.contains("r.status === 401"), "recovery.html should redirect on 401");

        String indexHtml = Files.readString(PROJECT_ROOT.resolve("src/main/resources/static/index.html"), StandardCharsets.UTF_8);
        assertTrue(indexHtml.contains("/api/recovery/summary"), "index.html should poll lightweight /api/recovery/summary");
        assertTrue(indexHtml.contains("id=\"toast\""), "index.html should have visible toast container");
        assertTrue(indexHtml.contains("showToast(t('web.change.password.success'))"), "password change should show toast");
        assertTrue(indexHtml.contains("logClearedSeq"), "clearLogs() should filter by seq to prevent rebound");
        assertTrue(indexHtml.contains("withButtonLock"), "index.html should guard action buttons against double-click");
        assertFalse(indexHtml.contains("toLocaleTimeString('zh-CN'"), "index.html should not hardcode zh-CN time locale");
        Matcher hardcodedAria = Pattern.compile("aria-label=\"说明\"").matcher(indexHtml);
        assertFalse(hardcodedAria.find(), "index.html should localize info-tip aria-label");
    }
}
