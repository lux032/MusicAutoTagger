package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MbContributionItem;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.service.AudioFingerprintService;
import com.lux032.musicautotagger.service.MbContributionStore;
import com.lux032.musicautotagger.service.MusicBrainzClient;
import com.lux032.musicautotagger.service.MusicBrainzContributionService;
import com.lux032.musicautotagger.service.ProcessedFileLogger;
import com.lux032.musicautotagger.service.TagWriterService;
import com.lux032.musicautotagger.util.AdminCredentialsStore;
import com.lux032.musicautotagger.util.FileSystemUtils;
import com.lux032.musicautotagger.util.SetupStateStore;
import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Constructor;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MbContributionServletTest {

    private final Gson gson = new Gson();

    private static MusicConfig newConfig(Path tempDir) throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MusicConfig cfg = ctor.newInstance();
        cfg.setMonitorDirectory(tempDir.resolve("monitor").toString());
        cfg.setOutputDirectory(tempDir.resolve("output").toString());
        cfg.setFailedDirectory(tempDir.resolve("failed").toString());
        cfg.setPartialDirectory(tempDir.resolve("partial").toString());
        cfg.setCoverArtCacheDirectory(tempDir.resolve("cover_cache").toString());
        cfg.setProcessedFileLogPath(tempDir.resolve("processed.log").toString());
        cfg.setReviewQueuePath(tempDir.resolve("data/review-queue.json").toString());
        cfg.setReviewStagingDirectory(tempDir.resolve("data/review-staging").toString());
        cfg.setRecoveryWorkDirectory(tempDir.resolve("data/recovery-work").toString());
        cfg.setRecoveryTrashDirectory(tempDir.resolve("data/recovery-trash").toString());
        cfg.setLlmProvidersPath(tempDir.resolve("data/llm-providers.json").toString());
        cfg.setDbType("file");
        cfg.setAcoustIdApiKey("client-key");
        cfg.setAcoustIdUserApiKey("user-key");
        return cfg;
    }

    @Test
    void returns503SetupRequiredInSetupMode(@TempDir Path tempDir) throws Exception {
        MusicConfig cfg = newConfig(tempDir);
        Path cfgPath = tempDir.resolve("config.properties");
        AdminCredentialsStore adminStore = new AdminCredentialsStore(tempDir.resolve("data/admin.json"));
        SetupStateStore stateStore = new SetupStateStore(tempDir.resolve("data/setup.json"));

        WebServer web = new WebServer(0);
        web.startInSetupMode(cfg, cfgPath, null, adminStore, stateStore);
        try {
            String base = "http://127.0.0.1:" + web.getLocalPort();
            CookieManager cm = new CookieManager();
            HttpClient http = HttpClient.newBuilder().cookieHandler(cm).build();

            // 1. 未登录返回 401
            assertEquals(401, http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/mb-contributions/stats")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());

            // 2. 初始化管理员并登录后，待初始化模式必须返回 503 setup.required
            HttpResponse<String> auth = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/auth/setup"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"password123\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, auth.statusCode());

            HttpResponse<String> statsResp = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/mb-contributions/stats")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(503, statsResp.statusCode());
            assertTrue(statsResp.body().contains("setup.required"));
        } finally {
            web.stop();
        }
    }

    @Test
    void enforcesAuthCsrfValidationCoverDownloadAndFullWorkflow(@TempDir Path tempDir) throws Exception {
        MusicConfig cfg = newConfig(tempDir);
        Path outputDir = Files.createDirectories(tempDir.resolve("output/HIMEHINA/VAMPY"));
        Path track1 = outputDir.resolve("1.01 HIMEHINA - VAMPY.wav");
        writeWav(track1, 2);

        TagWriterService tagWriter = new TagWriterService(cfg);
        MusicMetadata initMd = new MusicMetadata();
        initMd.setTitle("VAMPY");
        initMd.setAlbum("VAMPY");
        initMd.setArtist("HIMEHINA");
        initMd.setAlbumArtist("HIMEHINA");
        initMd.setDiscNo("1");
        initMd.setTrackNo("1");
        tagWriter.updateTagsOnExistingFile(track1.toFile(), initMd, null);

        MbContributionStore store = MbContributionStore.forReviewQueuePath(cfg.getReviewQueuePath());
        MusicBrainzClient stubMb = new MusicBrainzClient(cfg) {
            @Override
            public Optional<ReleaseDetail> getFullReleaseWithTracklist(String rawReleaseId) {
                if ("44444444-4444-4444-8444-444444444444".equals(rawReleaseId)) {
                    return Optional.of(new ReleaseDetail(
                        rawReleaseId, "55555555-5555-4555-8555-555555555555",
                        "VAMPY", "Digital EP", "official", "2026-11-27", "JP",
                        "4580000000001", "Jpan", "ep", List.of(), false,
                        "HIMEHINA", List.of("HIMEHINA"), null, null, null,
                        "LaRa", "HH-001", "2026-11-27", "2026", 1,
                        List.of(new ReleaseMediumDetail(1, "Digital Media", null, 1, List.of(
                            new ReleaseTrackDetail(1, 1, "1",
                                "66666666-6666-4666-8666-666666666661",
                                "77777777-7777-4777-8777-777777777771",
                                "VAMPY", 2000L, "HIMEHINA", List.of("HIMEHINA"), null, null, null)
                        )))));
                }
                return Optional.empty();
            }

            @Override
            public List<ReleaseGroupSearchHit> searchReleaseGroups(String title, String artistName, String artistMbid, int limit) {
                return List.of();
            }
        };

        AudioFingerprintService stubFp = new AudioFingerprintService(cfg) {
            @Override
            public AudioFingerprint generateFingerprint(File audioFile) {
                AudioFingerprint fp = new AudioFingerprint();
                fp.setFingerprint("FAKE_FP_DATA");
                fp.setDuration(2);
                return fp;
            }

            @Override
            public AcoustIdSubmitResult submitFingerprints(List<AcoustIdSubmissionItem> items,
                                                           java.util.function.Consumer<List<String>> onBatchSubmitted) {
                if (onBatchSubmitted != null) {
                    onBatchSubmitted.accept(items.stream().map(AcoustIdSubmissionItem::recordingMbid).toList());
                }
                return new AcoustIdSubmitResult(true, items.size(), List.of("sub-1"));
            }
        };

        // 预置一条带本地高清原图备份的待回馈条目
        MbContributionItem seed = new MbContributionItem();
        seed.setId("contrib-1");
        seed.setStatus(MbContributionItem.Status.READY_TO_SEED);
        seed.setCheckCase(MbContributionItem.CheckCase.MISSING_RELEASE_GROUP);
        seed.setArchivedOutputDir(outputDir.toString());
        seed.setArchivedFilePaths(List.of(track1.toString()));
        MbContributionItem.ReleaseDraft draft = new MbContributionItem.ReleaseDraft();
        draft.setTitle("VAMPY");
        draft.setAlbumArtist("HIMEHINA");
        draft.setAlbumArtists(List.of("HIMEHINA"));
        draft.setReleaseDate("2026-11-27");
        draft.setOfficialCandidate(true);
        draft.setTrackCoverage(1.0);
        MbContributionItem.SourceUrlEntry src = new MbContributionItem.SourceUrlEntry();
        src.setUrl("https://music.apple.com/jp/album/vampy/123");
        src.setReliability("HIGH");
        src.setIncluded(true);
        draft.setSourceUrls(List.of(src));
        MbContributionItem.ContributionTrack ct = new MbContributionItem.ContributionTrack();
        ct.setDiscNo(1);
        ct.setTrackNo(1);
        ct.setTitle("VAMPY");
        ct.setDurationMs(2000L);
        ct.setLocalFilePath(track1.toString());
        ct.setMatchedConfidence(1.0);
        draft.setTracks(List.of(ct));
        seed.setDraft(draft);
        store.upsert(seed);

        byte[] pngBytes = createPngBytes();
        MbContributionStore.SavedCoverBackup savedCover = store.saveCoverBackup("contrib-1", pngBytes).orElseThrow();
        store.update("contrib-1", it -> {
            MbContributionItem.CoverInfo ci = new MbContributionItem.CoverInfo();
            ci.setOrigin("FOLDER");
            ci.setLocalBackupFileName(savedCover.fileName());
            ci.setMime(savedCover.mime());
            ci.setWidth(savedCover.width());
            ci.setHeight(savedCover.height());
            ci.setBytes(savedCover.bytes());
            it.setCover(ci);
        });

        AdminCredentialsStore adminStore = new AdminCredentialsStore(tempDir.resolve("data/admin.json"));
        SetupStateStore stateStore = new SetupStateStore(tempDir.resolve("data/setup.json"));
        ProcessedFileLogger logger = new ProcessedFileLogger(cfg, null);

        try (MusicBrainzContributionService service = new MusicBrainzContributionService(
                cfg, store, stubMb, tagWriter, logger, stubFp, null, new FileSystemUtils(cfg))) {

            Server server = new Server(0);
            ServletContextHandler ctx = new ServletContextHandler(ServletContextHandler.SESSIONS);
            ctx.addFilter(AuthFilter.class, "/api/*", EnumSet.of(DispatcherType.REQUEST));
            ctx.addServlet(new ServletHolder(new AuthServlet(adminStore, stateStore, cfg, tempDir.resolve("config.properties"))), "/api/auth/*");
            ctx.addServlet(new ServletHolder(new MbContributionServlet(service)), "/api/mb-contributions/*");
            server.setHandler(ctx);
            server.start();
            try {
                String base = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort();
                CookieManager cm = new CookieManager();
                HttpClient http = HttpClient.newBuilder().cookieHandler(cm).build();

                // 1. 登录并拿到 CSRF
                HttpResponse<String> auth = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/auth/setup"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"password123\"}"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(200, auth.statusCode());
                String csrf = gson.fromJson(auth.body(), JsonObject.class).get("csrfToken").getAsString();

                // 2. 无 CSRF 的 POST 必须返回 403
                assertEquals(403, http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/mb-contributions/dismiss"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"contrib-1\"}")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());

                // 3. stats / list / item 契约校验
                HttpResponse<String> stats = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/mb-contributions/stats")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(200, stats.statusCode());
                JsonObject statsJson = gson.fromJson(stats.body(), JsonObject.class);
                assertEquals(1, statsJson.get("actionable").getAsInt());
                assertTrue(statsJson.get("acoustIdReady").getAsBoolean());

                HttpResponse<String> list = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/mb-contributions/list?status=ACTIONABLE&q=VAMPY")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(200, list.statusCode());
                assertEquals(1, gson.fromJson(list.body(), JsonObject.class).getAsJsonArray("items").size());

                // 非法 status 返回 400
                assertEquals(400, http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/mb-contributions/list?status=BOGUS")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());

                // 缺 id 返回 400，不存在 id 返回 404
                assertEquals(400, http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/mb-contributions/item")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(404, http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/mb-contributions/item?id=missing")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());

                // 4. 下载原图备份（含防嗅探头与原始字节一致）
                HttpResponse<byte[]> coverResp = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/mb-contributions/cover?id=contrib-1")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, coverResp.statusCode());
                assertEquals("image/png", coverResp.headers().firstValue("Content-Type").orElse(""));
                assertEquals("nosniff", coverResp.headers().firstValue("X-Content-Type-Options").orElse(""));
                assertArrayEquals(pngBytes, coverResp.body());

                // 5. 更新草稿字段（非法类型与非法日历日期必须返回 400）
                assertEquals(400, http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/mb-contributions/update-draft"))
                        .header("X-CSRF-Token", csrf)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"contrib-1\",\"useExistingReleaseGroup\":\"yes\"}")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(400, http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/mb-contributions/update-draft"))
                        .header("X-CSRF-Token", csrf)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"contrib-1\",\"releaseDate\":\"2026-02-31\"}")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());

                HttpResponse<String> updResp = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/mb-contributions/update-draft"))
                        .header("X-CSRF-Token", csrf)
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(Map.of(
                            "id", "contrib-1",
                            "barcode", "4580000000001",
                            "country", "JP"
                        )))).build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(200, updResp.statusCode());
                JsonObject updJson = gson.fromJson(updResp.body(), JsonObject.class);
                assertTrue(updJson.getAsJsonObject("seedPayload").get("seedable").getAsBoolean());
                assertTrue(updResp.body().contains("4580000000001"));

                // 6. link-release 非法 UUID 返回 400；合法 URL 成功回填并返回 LINKED
                HttpResponse<String> badLink = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/mb-contributions/link-release"))
                        .header("X-CSRF-Token", csrf)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"contrib-1\",\"releaseUrlOrMbid\":\"not-valid\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(400, badLink.statusCode());
                assertTrue(badLink.body().contains("mb.contribution.release.invalid"));

                HttpResponse<String> okLink = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/mb-contributions/link-release"))
                        .header("X-CSRF-Token", csrf)
                        .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"id\":\"contrib-1\",\"releaseUrlOrMbid\":\"https://musicbrainz.org/release/44444444-4444-4444-8444-444444444444\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(200, okLink.statusCode());
                JsonObject okLinkJson = gson.fromJson(okLink.body(), JsonObject.class);
                assertEquals("LINKED", okLinkJson.getAsJsonObject("item").get("status").getAsString());
                assertFalse(okLinkJson.getAsJsonObject("seedPayload").get("seedable").getAsBoolean());
                assertTrue(okLinkJson.getAsJsonObject("coverGuide").get("hasLocalBackup").getAsBoolean());
                assertTrue(okLinkJson.getAsJsonObject("coverGuide").get("addCoverArtBaseUrl").getAsString().contains("/add-cover-art"));
                assertEquals(1, okLinkJson.getAsJsonObject("backfill").get("updatedFiles").getAsInt());

                // 7. 提交 AcoustID 声纹；重复提交必须返回 409 acoustid.already.submitted
                HttpResponse<String> submitFp = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/mb-contributions/submit-acoustid"))
                        .header("X-CSRF-Token", csrf)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"contrib-1\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(200, submitFp.statusCode());
                assertEquals(1, gson.fromJson(submitFp.body(), JsonObject.class)
                    .getAsJsonObject("acoustIdResult").get("submittedCount").getAsInt());

                HttpResponse<String> dupFp = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/mb-contributions/submit-acoustid"))
                        .header("X-CSRF-Token", csrf)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"contrib-1\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(409, dupFp.statusCode());
                assertTrue(dupFp.body().contains("acoustid.already.submitted"));

                // 8. complete 后进入终态并清理原图备份（再请求 /cover 返回 404）
                HttpResponse<String> doneResp = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/mb-contributions/complete"))
                        .header("X-CSRF-Token", csrf)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"contrib-1\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(200, doneResp.statusCode());
                assertEquals("COMPLETED", gson.fromJson(doneResp.body(), JsonObject.class)
                    .getAsJsonObject("item").get("status").getAsString());

                assertEquals(404, http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/mb-contributions/cover?id=contrib-1")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()).statusCode());
            } finally {
                server.stop();
            }
        }
    }

    private static byte[] createPngBytes() throws Exception {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static void writeWav(Path path, int seconds) throws Exception {
        int sampleRate = 8000;
        int dataSize = sampleRate * 2 * seconds;
        int chunkSize = 36 + dataSize;
        byte[] wav = new byte[44 + dataSize];
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        putLeInt(wav, 4, chunkSize);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        putLeInt(wav, 16, 16);
        wav[20] = 1; wav[22] = 1;
        putLeInt(wav, 24, sampleRate);
        putLeInt(wav, 28, sampleRate * 2);
        wav[32] = 2; wav[34] = 16;
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        putLeInt(wav, 40, dataSize);
        Files.write(path, wav);
    }

    private static void putLeInt(byte[] b, int off, int val) {
        b[off] = (byte) (val & 0xFF);
        b[off + 1] = (byte) ((val >>> 8) & 0xFF);
        b[off + 2] = (byte) ((val >>> 16) & 0xFF);
        b[off + 3] = (byte) ((val >>> 24) & 0xFF);
    }
}
