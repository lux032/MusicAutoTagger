package com.lux032.musicautotagger;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.LlmProviderConfig;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.CueSplitService;
import com.lux032.musicautotagger.service.FolderAlbumCache;
import com.lux032.musicautotagger.service.ImageCompressor;
import com.lux032.musicautotagger.service.ProcessedFileLogger;
import com.lux032.musicautotagger.service.RecoveryService;
import com.lux032.musicautotagger.service.ReviewQueueService;
import com.lux032.musicautotagger.service.TagWriterService;
import com.lux032.musicautotagger.service.UntrustedImageFetcher;
import com.lux032.musicautotagger.service.llm.LlmAlbumJudge;
import com.lux032.musicautotagger.service.llm.LlmClient;
import com.lux032.musicautotagger.util.AdminCredentialsStore;
import com.lux032.musicautotagger.util.FileSystemUtils;
import com.lux032.musicautotagger.util.OutboundUrlValidator;
import com.lux032.musicautotagger.util.ProcessRunner;
import com.lux032.musicautotagger.web.AuthFilter;
import com.lux032.musicautotagger.web.AuthServlet;
import com.lux032.musicautotagger.web.ConfigServlet;
import com.lux032.musicautotagger.web.ControlServlet;
import com.lux032.musicautotagger.web.LlmProviderServlet;
import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.CookieManager;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class P0SecurityAndIntegrityRegressionTest {

    @TempDir
    Path tempDir;

    private final Gson gson = new Gson();

    private MusicConfig newTestConfig() throws Exception {
        var ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MusicConfig cfg = ctor.newInstance();
        cfg.setMonitorDirectory(tempDir.resolve("monitor").toString());
        cfg.setOutputDirectory(tempDir.resolve("output").toString());
        cfg.setFailedDirectory(tempDir.resolve("failed").toString());
        cfg.setPartialDirectory(tempDir.resolve("partial").toString());
        cfg.setReviewQueuePath(tempDir.resolve("data/review-queue.json").toString());
        cfg.setReviewStagingDirectory(tempDir.resolve("data/review-staging").toString());
        cfg.setRecoveryWorkDirectory(tempDir.resolve("data/recovery-work").toString());
        cfg.setRecoveryTrashDirectory(tempDir.resolve("data/recovery-trash").toString());
        cfg.setLlmProvidersPath(tempDir.resolve("data/llm-providers.json").toString());
        cfg.setProcessedFileLogPath(tempDir.resolve("data/processed.log").toString());
        cfg.setDbType("file");
        cfg.setCoverMissingRequireReview(false);
        return cfg;
    }

    @Test
    void outboundValidatorBlocksExtendedPrivateAndTransitionIpRanges() throws Exception {
        String[] blocked = {
            "240.0.0.1", "255.255.255.255", "192.0.2.1", "198.51.100.5", "203.0.113.9",
            "100.64.0.1", "100.100.100.200", "169.254.169.254", "127.0.0.1",
            "fc00::1", "fd12:3456::1", "64:ff9b::127.0.0.1", "64:ff9b:1::127.0.0.1",
            "::ffff:0:0:127.0.0.1", "2002:7f00:0001::1", "2001:0:4136:e378::1", "2001:db8::1"
        };
        for (String ip : blocked) {
            assertFalse(OutboundUrlValidator.isPublic(InetAddress.getByName(ip)), "Should block " + ip);
        }
        assertTrue(OutboundUrlValidator.isPublic(InetAddress.getByName("8.8.8.8")));
        assertTrue(OutboundUrlValidator.isPublic(InetAddress.getByName("2001:4860:4860::8888")));

        // 非标端口与非 HTTPS 校验
        assertThrows(OutboundUrlValidator.ValidationException.class,
            () -> OutboundUrlValidator.validateAndResolve("http://8.8.8.8/v1/models", false, false, false));
        assertThrows(OutboundUrlValidator.ValidationException.class,
            () -> OutboundUrlValidator.validateAndResolve("https://8.8.8.8:8443/img.jpg", false, true, true));

        // Origin 比对
        assertTrue(OutboundUrlValidator.sameOrigin("https://api.openai.com/v1/chat/completions", "https://api.openai.com:443/v1/models"));
        assertFalse(OutboundUrlValidator.sameOrigin("https://api.openai.com/v1/chat/completions", "https://evil.example.com/v1/models"));
    }

    @Test
    void ffmpegFallbackRejectsPlaylistsAndWebpBombs() {
        byte[] hlsPlaylist = "#EXTM3U\n#EXT-X-VERSION:3\nhttp://169.254.169.254/latest/meta-data/\n".getBytes(StandardCharsets.UTF_8);
        assertNull(ImageCompressor.compressImage(hlsPlaylist, "ffmpeg"));
        assertTrue(UntrustedImageFetcher.inspect(hlsPlaylist).isEmpty());

        // 构造一个 65536x65536 VP8X WebP 炸弹头（> MAX_PIXELS 与 MAX_SOURCE_DIMENSION）
        byte[] webpBomb = new byte[30];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, webpBomb, 0, 4);
        System.arraycopy("WEBPVP8X".getBytes(StandardCharsets.US_ASCII), 0, webpBomb, 8, 8);
        int hugeMinusOne = 40000 - 1;
        webpBomb[24] = (byte) (hugeMinusOne & 0xff);
        webpBomb[25] = (byte) ((hugeMinusOne >> 8) & 0xff);
        webpBomb[26] = (byte) ((hugeMinusOne >> 16) & 0xff);
        webpBomb[27] = (byte) (hugeMinusOne & 0xff);
        webpBomb[28] = (byte) ((hugeMinusOne >> 8) & 0xff);
        webpBomb[29] = (byte) ((hugeMinusOne >> 16) & 0xff);
        assertTrue(UntrustedImageFetcher.inspect(webpBomb).isEmpty());
        assertNull(ImageCompressor.compressImage(webpBomb, "ffmpeg"));

        // 合法 640x480 VP8X WebP 头能准确解析宽高
        byte[] validWebpHeader = new byte[30];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, validWebpHeader, 0, 4);
        System.arraycopy("WEBPVP8X".getBytes(StandardCharsets.US_ASCII), 0, validWebpHeader, 8, 8);
        int w = 640 - 1, h = 480 - 1;
        validWebpHeader[24] = (byte) (w & 0xff);
        validWebpHeader[25] = (byte) ((w >> 8) & 0xff);
        validWebpHeader[27] = (byte) (h & 0xff);
        validWebpHeader[28] = (byte) ((h >> 8) & 0xff);
        var inspected = UntrustedImageFetcher.inspect(validWebpHeader).orElseThrow();
        assertEquals("image/webp", inspected.mime());
        assertEquals(640, inspected.width());
        assertEquals(480, inspected.height());
    }

    @Test
    void webSecurityGuardsCsrfKeyExfiltrationReadOnlyConfigAndSessionFixation() throws Exception {
        MusicConfig cfg = newTestConfig();
        cfg.setTavilyApiKey("real-tavily-secret");
        cfg.setTavilyApiUrl("https://api.tavily.com/search");
        LlmProviderConfig provider = new LlmProviderConfig();
        provider.setId("prov-1");
        provider.setName("Official");
        provider.setApiUrl("https://api.openai.com/v1/chat/completions");
        provider.setApiKey("sk-real-secret-key");
        provider.setFormat("openai");
        cfg.setLlmProviders(List.of(provider));

        AdminCredentialsStore credsStore = new AdminCredentialsStore(tempDir.resolve("data/admin.json"));
        Server server = new Server(0);
        ServletContextHandler ctx = new ServletContextHandler(ServletContextHandler.SESSIONS);
        ctx.addFilter(AuthFilter.class, "/api/*", EnumSet.of(DispatcherType.REQUEST));
        ctx.addServlet(new ServletHolder(new AuthServlet(credsStore)), "/api/auth/*");
        ctx.addServlet(new ServletHolder(new ConfigServlet(cfg)), "/api/config");
        ctx.addServlet(new ServletHolder(new LlmProviderServlet(cfg)), "/api/llm/providers/*");
        ctx.addServlet(new ServletHolder(new ControlServlet(null)), "/api/control/*");
        server.setHandler(ctx);
        server.start();
        try {
            String base = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort();
            CookieManager cookies = new CookieManager();
            HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

            // 空白用户名注册被拒
            var blankUser = http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/setup"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"   \",\"password\":\"password123\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(400, blankUser.statusCode());

            // 首次初始化管理员并获取 CSRF + Session
            var setupResp = http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/setup"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"password123\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, setupResp.statusCode());
            String sessionAfterSetup = cookies.getCookieStore().getCookies().get(0).getValue();

            // 再次登录必须轮换 Session ID（防 Session Fixation）
            var loginResp = http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"password123\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, loginResp.statusCode());
            String sessionAfterLogin = cookies.getCookieStore().getCookies().get(0).getValue();
            assertNotEquals(sessionAfterSetup, sessionAfterLogin, "Login must rotate session ID");
            String csrf = gson.fromJson(loginResp.body(), JsonObject.class).get("csrfToken").getAsString();

            // P0-6: ControlServlet POST 无 CSRF 必须返回 403
            var controlNoCsrf = http.send(HttpRequest.newBuilder(URI.create(base + "/api/control/pause"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(403, controlNoCsrf.statusCode());

            // P0-1: /api/llm/providers/models 改 URL + 传掩码密钥必须被拒（防密钥外泄）
            var exfilModels = http.send(HttpRequest.newBuilder(URI.create(base + "/api/llm/providers/models"))
                .header("X-CSRF-Token", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"id\":\"prov-1\",\"apiUrl\":\"https://attacker.example.com/v1\",\"apiKey\":\"********\",\"format\":\"openai\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, exfilModels.statusCode());
            assertTrue(exfilModels.body().contains("api.key.required.on.url.change"));

            // P0-1: PUT /api/llm/providers 改 URL + 保留掩码密钥必须被拒
            var exfilPut = http.send(HttpRequest.newBuilder(URI.create(base + "/api/llm/providers"))
                .header("X-CSRF-Token", csrf)
                .PUT(HttpRequest.BodyPublishers.ofString(
                    "{\"providers\":[{\"id\":\"prov-1\",\"name\":\"Official\",\"apiUrl\":\"https://attacker.example.com/v1\",\"apiKey\":\"********\",\"format\":\"openai\"}]}"))
                .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, exfilPut.statusCode());

            // P0-1: PUT /api/config 改 tavilyApiUrl + 沿用掩码密钥必须被拒
            var exfilTavily = http.send(HttpRequest.newBuilder(URI.create(base + "/api/config"))
                .header("X-CSRF-Token", csrf)
                .PUT(HttpRequest.BodyPublishers.ofString(
                    "{\"tavilyApiUrl\":\"https://attacker.example.com/search\",\"tavilyApiKey\":\"********\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, exfilTavily.statusCode());
            assertTrue(exfilTavily.body().contains("secret.required.on.endpoint.change"));

            // P0-3 & P0-4: llmAllowPrivateEndpoints / recoveryTrashDirectory / root "/" 均不可通过 API 修改
            var flipPrivate = http.send(HttpRequest.newBuilder(URI.create(base + "/api/config"))
                .header("X-CSRF-Token", csrf)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"llmAllowPrivateEndpoints\":true}"))
                .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, flipPrivate.statusCode());
            assertTrue(flipPrivate.body().contains("field.readonly"));

            var changeTrash = http.send(HttpRequest.newBuilder(URI.create(base + "/api/config"))
                .header("X-CSRF-Token", csrf)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"recoveryTrashDirectory\":\"/tmp/evil\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, changeTrash.statusCode());
            assertTrue(changeTrash.body().contains("field.readonly"));

            // P0-14: NaN 配置值必须被拒
            var nanCfg = http.send(HttpRequest.newBuilder(URI.create(base + "/api/config"))
                .header("X-CSRF-Token", csrf)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"llmAlbumAutoApplyMinConfidence\":\"NaN\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, nanCfg.statusCode());

            // toString 不得包含明文密钥
            assertFalse(cfg.toString().contains("real-tavily-secret"));
            assertFalse(provider.toString().contains("sk-real-secret-key"));
        } finally {
            server.stop();
        }
    }

    @Test
    void findReprocessRootNeverEscapesStagingRoot() throws Exception {
        MusicConfig cfg = newTestConfig();
        ReviewQueueService queue = new ReviewQueueService(cfg);

        // 用户音乐目录自身包含 reprocess- 祖先目录：绝不能被 findReprocessRoot 命中或被 markResolved 删除！
        Path userDir = Files.createDirectories(tempDir.resolve("monitor/reprocess-vinyl/Artist/Album"));
        Path userAudio = Files.writeString(userDir.resolve("01.flac"), "audio");
        ReviewItem item = new ReviewItem();
        item.setId("item-user");
        item.setFolderPath(userDir.toString());
        assertNull(queue.findReprocessRoot(item));
        queue.markResolved(item, ReviewItem.Status.CONFIRMED, "done");
        assertTrue(Files.exists(userAudio), "User folder starting with reprocess- must NOT be deleted");

        // 真正位于 stagingRoot 下的 reprocess-<UUID> 目录应被准确识别
        Path realReprocess = Files.createDirectories(
            Path.of(cfg.getReviewStagingDirectory()).resolve("reprocess-12345678-1234-1234-1234-1234567890ab/Album"));
        ReviewItem reprocessItem = new ReviewItem();
        reprocessItem.setId("item-reproc");
        reprocessItem.setReprocessDir(realReprocess.getParent().toString());
        assertEquals(realReprocess.getParent().toAbsolutePath().normalize(), queue.findReprocessRoot(reprocessItem));
    }

    @Test
    void recoveryRejectsDotPathAndRestoresPendingReplacementOnStartup() throws Exception {
        MusicConfig cfg = newTestConfig();
        Path failedDir = Files.createDirectories(Path.of(cfg.getFailedDirectory()));
        Path sample = Files.writeString(failedDir.resolve("keep.flac"), "keep");
        Path trashRoot = Files.createDirectories(Path.of(cfg.getRecoveryTrashDirectory()));

        // 模拟上次提交替换旧专辑中途崩溃遗留的 .pending-replace-* 备份
        Path outputAlbum = Path.of(cfg.getOutputDirectory()).resolve("Artist/CrashAlbum");
        Path pendingDir = Files.createDirectories(trashRoot.resolve(".pending-replace-crash1"));
        Path backedUp = Files.createDirectories(pendingDir.resolve("unit-0"));
        Files.writeString(backedUp.resolve("01.flac"), "original-library-audio");
        JsonArray manifest = new JsonArray();
        JsonObject m = new JsonObject();
        m.addProperty("original", outputAlbum.toAbsolutePath().normalize().toString());
        m.addProperty("backup", backedUp.toAbsolutePath().normalize().toString());
        manifest.add(m);
        Files.writeString(pendingDir.resolve(".manifest.json"), gson.toJson(manifest));

        ReviewQueueService queue = new ReviewQueueService(cfg);
        try (RecoveryService recovery = new RecoveryService(cfg, null, new ProcessedFileLogger(cfg, null), queue,
                new FolderAlbumCache(null, null, null), null, new FileSystemUtils(cfg), new TagWriterService(cfg), null, null)) {
            // 启动时已自动将崩溃遗留备份还原到原音乐库路径
            assertTrue(Files.isRegularFile(outputAlbum.resolve("01.flac")));
            assertEquals("original-library-audio", Files.readString(outputAlbum.resolve("01.flac")));

            // P0-4: dismiss(".") 必须抛 recovery.path.invalid 且不删除 failedDirectory
            IOException dotErr = assertThrows(IOException.class,
                () -> recovery.dismiss(RecoveryService.SourceType.FAILED, "."));
            assertEquals("recovery.path.invalid", dotErr.getMessage());
            assertTrue(Files.exists(sample));

            // P0-19: 普通监控目录条目即使有 stagingDir 也不应被判为临时源（防止跳过监控路径标记造成无限循环重刮）
            Method isTemp = RecoveryService.class.getDeclaredMethod("isTemporarySourceItem", ReviewItem.class);
            isTemp.setAccessible(true);
            ReviewItem monitored = new ReviewItem();
            monitored.setFolderPath(Path.of(cfg.getMonitorDirectory()).resolve("Album").toString());
            monitored.setStagingDir(Path.of(cfg.getReviewStagingDirectory()).resolve("item-123").toString());
            assertFalse((boolean) isTemp.invoke(recovery, monitored));

            ReviewItem fromRecovery = new ReviewItem();
            fromRecovery.setRecoverySourceType("FAILED");
            fromRecovery.setRecoverySourcePath("keep.flac");
            assertTrue((boolean) isTemp.invoke(recovery, fromRecovery));
        }
    }

    @Test
    void cueParserHandlesQuotedSpacesNonUtf8AndSkipsDataTracks() throws Exception {
        MusicConfig cfg = newTestConfig();
        CueSplitService cueSplit = new CueSplitService(cfg, new FileSystemUtils(cfg));
        Path cueFile = tempDir.resolve("album.cue");
        String content = "PERFORMER \"歌手名\"\n"
            + "TITLE \"专辑标题\"\n"
            + "FILE \"Artist Name - Album Title (2026).flac\" WAVE\n"
            + "  TRACK 01 AUDIO\n"
            + "    TITLE \"第一首\"\n"
            + "    INDEX 01 00:00:00\n"
            + "  TRACK 02 MODE1/2352\n"
            + "    TITLE \"Data Track\"\n"
            + "    INDEX 01 03:30:00\n";
        Files.write(cueFile, content.getBytes(Charset.forName("GB18030")));

        Method parseCue = CueSplitService.class.getDeclaredMethod("parseCue", File.class);
        parseCue.setAccessible(true);
        CueSplitService.CueAlbumInfo info = (CueSplitService.CueAlbumInfo) parseCue.invoke(cueSplit, cueFile.toFile());
        assertEquals("Artist Name - Album Title (2026).flac", info.getReferencedFileName());
        assertEquals(1, info.getTracks().size(), "Non-AUDIO data track must be skipped");
        assertEquals("第一首", info.getTracks().get(0).getTitle());
    }

    @Test
    void llmAlbumJudgeClampsNaNToZeroAndProcessRunnerSeparatesStderr() throws Exception {
        // P0-14: NaN 置信度不得绕过 autoApplyMinConfidence
        Method clamp = LlmAlbumJudge.class.getDeclaredMethod("clamp", double.class);
        clamp.setAccessible(true);
        assertEquals(0.0, (double) clamp.invoke(null, Double.NaN));
        assertEquals(0.0, (double) clamp.invoke(null, Double.POSITIVE_INFINITY));

        // P0-16: stderr 噪声不污染 stdout JSON
        String jsonOut = ProcessRunner.runSeparateStdout(
            List.of("sh", "-c", "echo '[mp3float @ 0x1] Header missing' >&2; echo '{\"duration\":180}'"), 5);
        assertEquals("{\"duration\":180}", jsonOut.trim());
    }

    @Test
    void tagWriterDoesNotClobberDifferentFlacWithSameDuration() throws Exception {
        MusicConfig cfg = newTestConfig();
        TagWriterService writer = new TagWriterService(cfg);

        Path target = tempDir.resolve("01 - Song.flac");
        Path incoming = tempDir.resolve("incoming.flac");
        writeMinimalFlacWithMd5(target, (byte) 0x11);
        writeMinimalFlacWithMd5(incoming, (byte) 0x22);

        Method copyNoClobber = TagWriterService.class.getDeclaredMethod("copyWithoutClobbering", File.class, File.class);
        copyNoClobber.setAccessible(true);
        File written = (File) copyNoClobber.invoke(writer, incoming.toFile(), target.toFile());
        assertNotEquals(target.toFile(), written, "Same-duration FLAC with different STREAMINFO MD5 must not overwrite");
        assertTrue(written.getName().contains("(2)"));
    }

    private static void writeMinimalFlacWithMd5(Path path, byte md5Fill) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(554);
        bytes.put(new byte[]{'f', 'L', 'a', 'C', (byte) 128, 0, 0, 34});
        bytes.putLong(18, (44100L << 44) | (1L << 41) | (15L << 36) | 5303025L);
        for (int i = 26; i < 42; i++) bytes.put(i, md5Fill);
        for (int i = 42; i < bytes.capacity(); i++) bytes.put(i, md5Fill);
        Files.write(path, bytes.array());
    }
}
