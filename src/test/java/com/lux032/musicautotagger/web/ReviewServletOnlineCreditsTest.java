package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.AudioFingerprintService;
import com.lux032.musicautotagger.service.FolderAlbumCache;
import com.lux032.musicautotagger.service.OnlineIdentificationService;
import com.lux032.musicautotagger.service.ProcessedFileLogger;
import com.lux032.musicautotagger.service.RecoveryService;
import com.lux032.musicautotagger.service.ReviewQueueService;
import com.lux032.musicautotagger.service.ReviewResolutionService;
import com.lux032.musicautotagger.service.TagWriterService;
import com.lux032.musicautotagger.util.FileSystemUtils;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReviewServletOnlineCreditsTest {

    @TempDir Path tempDir;

    private MusicConfig config;
    private Path monitorDir;
    private Path outputDir;
    private ReviewQueueService reviewQueue;
    private TagWriterService tagWriter;
    private RecoveryService recoveryService;
    private Server server;
    private String base;
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        config = ctor.newInstance();
        monitorDir = Files.createDirectories(tempDir.resolve("monitor"));
        outputDir = Files.createDirectories(tempDir.resolve("output"));
        config.setMonitorDirectory(monitorDir.toString());
        config.setOutputDirectory(outputDir.toString());
        config.setReviewQueuePath(tempDir.resolve("data/review-queue.json").toString());
        config.setReviewStagingDirectory(tempDir.resolve("data/review-staging").toString());
        config.setRecoveryWorkDirectory(tempDir.resolve("data/recovery-work").toString());
        config.setRecoveryTrashDirectory(tempDir.resolve("data/recovery-trash").toString());
        config.setProcessedFileLogPath(tempDir.resolve("processed.log").toString());
        config.setAutoRename(true);
        config.setExportLyricsToFile(false);
        config.setCoverMissingRequireReview(false);

        reviewQueue = new ReviewQueueService(config);
        tagWriter = new TagWriterService(config);
        ProcessedFileLogger logger = new ProcessedFileLogger(config, null);
        AudioFingerprintService stubFp = new AudioFingerprintService(config) {
            @Override
            public List<Integer> extractDurationSequence(List<File> files) {
                return files.stream().map(f -> 120).toList();
            }
        };
        recoveryService = new RecoveryService(
            config, null, logger, reviewQueue, new FolderAlbumCache(null, null, null), null,
            new FileSystemUtils(config), tagWriter, stubFp, null);
        ReviewResolutionService resolutionService = new ReviewResolutionService(
            config, reviewQueue, new FolderAlbumCache(null, null, null), null, null, null, logger);

        server = new Server(0);
        ServletContextHandler ctx = new ServletContextHandler(ServletContextHandler.SESSIONS);
        Filter login = (req, resp, chain) -> {
            ((HttpServletRequest) req).getSession(true).setAttribute("csrfToken", "tok");
            chain.doFilter(req, resp);
        };
        ctx.addFilter(new FilterHolder(login), "/*", EnumSet.of(DispatcherType.REQUEST));
        ctx.addServlet(new ServletHolder(new ReviewServlet(reviewQueue, resolutionService, recoveryService, null)),
            "/api/review/*");
        server.setHandler(ctx);
        server.start();
        base = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.stop();
        if (recoveryService != null) recoveryService.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        return postRaw(path, gson.toJson(body));
    }

    private HttpResponse<String> postRaw(String path, String rawJson) throws Exception {
        // 先触发一次 GET 建立会话 cookie
        get("/api/review/stats");
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .header("X-CSRF-Token", "tok")
                .POST(HttpRequest.BodyPublishers.ofString(rawJson))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void detailExposesExistingCreditsAndSourcesAndOnlineConfirmAcceptsValidAndRejectsInvalidTrackCredits() throws Exception {
        Path albumDir = Files.createDirectory(monitorDir.resolve("ServletCreditsAlbum"));
        File flac = createFlac(albumDir.resolve("01. Song.flac"), "Song", "Singer", "Old", "1",
            "Local C", "Local L", null);

        MusicMetadata mbMeta = new MusicMetadata();
        mbMeta.setRecordingId("11111111-1111-4111-8111-111111111111");
        mbMeta.setTitle("Song");
        mbMeta.setArtist("Singer");
        mbMeta.setComposer("梶浦由記");
        mbMeta.setComposers(List.of("梶浦由記"));
        mbMeta.setComposerIds(List.of("22222222-2222-4222-8222-222222222222"));
        mbMeta.setLyricist("Local L");

        FolderAlbumCache.PendingFile pf = new FolderAlbumCache.PendingFile(flac, flac, null, mbMeta, null);
        ReviewItem item = reviewQueue.enqueue(albumDir.toString(), List.of(pf), null, List.of(), List.of(120), "test", 0.5);
        ReviewItem.OnlineCandidate cand = new ReviewItem.OnlineCandidate();
        cand.setId("cand-1");
        cand.setTitle("Final Album");
        cand.setArtist("Singer");
        cand.setAlbumArtist("Singer");
        ReviewItem.OnlineTrack track = new ReviewItem.OnlineTrack();
        track.setDiscNo(1);
        track.setTrackNo(1);
        track.setTitle("Song");
        track.setArtist("Singer");
        track.setArrangers(List.of("森空青"));
        track.setDuration(120);
        cand.setTracks(List.of(track));
        item.setOnlineCandidates(List.of(cand));
        OnlineIdentificationService online = new OnlineIdentificationService(config, reviewQueue, tagWriter, null);
        item.setEvidenceHash(online.evidenceHashForFolder(albumDir.toFile()));
        reviewQueue.update(item);

        // 1. GET /api/review/item 返回每首已有幕后人员与准确来源
        HttpResponse<String> detailResp = get("/api/review/item?id=" + item.getId());
        assertEquals(200, detailResp.statusCode());
        JsonObject detailItem = gson.fromJson(detailResp.body(), JsonObject.class).getAsJsonObject("item");
        JsonArray files = detailItem.getAsJsonArray("files");
        assertEquals(1, files.size());
        JsonObject f0 = files.get(0).getAsJsonObject();
        assertEquals("MUSICBRAINZ", f0.get("composerSource").getAsString());
        assertEquals("梶浦由記", f0.getAsJsonArray("composers").get(0).getAsString());
        assertEquals("LOCAL", f0.get("lyricistSource").getAsString());
        assertEquals("Local L", f0.getAsJsonArray("lyricists").get(0).getAsString());
        assertEquals("NONE", f0.get("arrangerSource").getAsString());
        assertEquals(0, f0.getAsJsonArray("arrangers").size());

        // 2. 非法 trackCredits 结构 -> HTTP 400
        HttpResponse<String> badTypeResp = post("/api/review/online-confirm", Map.of(
            "id", item.getId(),
            "candidateId", cand.getId(),
            "albumTitle", "Final Album",
            "albumArtist", "Singer",
            "trackCredits", List.of("not-a-map")
        ));
        assertEquals(400, badTypeResp.statusCode());
        assertEquals("online.track.credits.invalid",
            gson.fromJson(badTypeResp.body(), JsonObject.class).get("error").getAsString());

        HttpResponse<String> badFieldResp = post("/api/review/online-confirm", Map.of(
            "id", item.getId(),
            "candidateId", cand.getId(),
            "albumTitle", "Final Album",
            "albumArtist", "Singer",
            "trackCredits", Map.of(flac.getAbsolutePath(), Map.of("unknownRole", List.of("X")))
        ));
        assertEquals(400, badFieldResp.statusCode());

        HttpResponse<String> badElementResp = post("/api/review/online-confirm", Map.of(
            "id", item.getId(),
            "candidateId", cand.getId(),
            "albumTitle", "Final Album",
            "albumArtist", "Singer",
            "trackCredits", Map.of(flac.getAbsolutePath(), Map.of("composers", List.of(123)))
        ));
        assertEquals(400, badElementResp.statusCode());

        // 3. 合法 trackCredits 透传：覆盖作曲、显式清空作词、未传编曲沿用联网提取
        HttpResponse<String> okResp = post("/api/review/online-confirm", Map.of(
            "id", item.getId(),
            "candidateId", cand.getId(),
            "albumTitle", "Final Album",
            "albumArtist", "Singer",
            "releaseDate", "2026-07-01",
            "confirmNoCover", true,
            "trackCredits", Map.of(flac.getAbsolutePath(), Map.of(
                "composers", List.of("澤野弘之"),
                "lyricists", List.of()
            ))
        ));
        assertEquals(200, okResp.statusCode(), okResp.body());
        MusicMetadata out = tagWriter.readTags(outputDir.resolve("Singer/Final Album/1.01 Singer - Song.flac").toFile());
        assertNotNull(out);
        assertEquals("澤野弘之", out.getComposer());
        assertNull(out.getLyricist());
        assertEquals("森空青", out.getArranger());
    }

    @Test
    void onlineConfirmWithoutTrackCreditsRemainsBackwardCompatible() throws Exception {
        Path albumDir = Files.createDirectory(monitorDir.resolve("LegacyConfirmAlbum"));
        File flac = createFlac(albumDir.resolve("01. Legacy.flac"), "Legacy", "Singer", "Old", "1",
            null, null, null);
        ReviewItem item = reviewQueue.enqueueRecoveryFolder(albumDir.toString(), null, List.of(flac), tagWriter, null);
        ReviewItem.OnlineCandidate cand = new ReviewItem.OnlineCandidate();
        cand.setId("cand-legacy");
        cand.setTitle("Legacy Album");
        cand.setArtist("Singer");
        cand.setAlbumArtist("Singer");
        ReviewItem.OnlineTrack track = new ReviewItem.OnlineTrack();
        track.setDiscNo(1);
        track.setTrackNo(1);
        track.setTitle("Legacy");
        track.setArtist("Singer");
        track.setComposers(List.of("梶浦由記"));
        track.setDuration(120);
        cand.setTracks(List.of(track));
        item.setOnlineCandidates(List.of(cand));
        OnlineIdentificationService online = new OnlineIdentificationService(config, reviewQueue, tagWriter, null);
        item.setEvidenceHash(online.evidenceHashForFolder(albumDir.toFile()));
        reviewQueue.update(item);

        // 不传 trackCredits 字段：完全向后兼容，自动走四级合并（填入联网作曲）
        HttpResponse<String> resp = post("/api/review/online-confirm", Map.of(
            "id", item.getId(),
            "candidateId", cand.getId(),
            "albumTitle", "Legacy Album",
            "albumArtist", "Singer",
            "confirmNoCover", true
        ));
        assertEquals(200, resp.statusCode(), resp.body());
        MusicMetadata out = tagWriter.readTags(outputDir.resolve("Singer/Legacy Album/1.01 Singer - Legacy.flac").toFile());
        assertNotNull(out);
        assertEquals("梶浦由記", out.getComposer());
    }

    @Test
    void detailSerializesStructuredCreditDetailConflictCandidatesAndApplyProbableFlag() throws Exception {
        config.setOnlineCreditsApplyProbable(false);
        Path albumDir = Files.createDirectory(monitorDir.resolve("StructuredEvidenceAlbum"));
        File flac = createFlac(albumDir.resolve("01. VAMPY.flac"), "VAMPY", "HIMEHINA", "Old", "1",
            null, null, null);
        ReviewItem item = reviewQueue.enqueueRecoveryFolder(albumDir.toString(), null, List.of(flac), tagWriter, null);

        ReviewItem.OnlineCandidate cand = new ReviewItem.OnlineCandidate();
        cand.setId("cand-ev");
        cand.setTitle("VAMPY");
        cand.setArtist("HIMEHINA");
        cand.setAlbumArtist("HIMEHINA");

        ReviewItem.OnlineTrack track = new ReviewItem.OnlineTrack();
        track.setDiscNo(1);
        track.setTrackNo(1);
        track.setTitle("VAMPY");
        track.setArtist("HIMEHINA");
        track.setComposers(List.of("Soma Genda"));
        track.setDuration(120);

        ReviewItem.TrackCreditDetail detail = new ReviewItem.TrackCreditDetail();
        detail.setVersionType("original");
        ReviewItem.CreditFieldResult comp = new ReviewItem.CreditFieldResult();
        comp.setStatus(ReviewItem.CreditStatus.VERIFIED);
        comp.setNames(List.of("Soma Genda"));
        ReviewItem.CreditEvidenceItem ev = new ReviewItem.CreditEvidenceItem();
        ev.setUrl("https://himehina.jp/discography/vampy");
        ev.setSourceType("artist_official");
        ev.setQuote("作曲：Soma Genda");
        ev.setVersionMatch("exact_version");
        ev.setFullPageVerified(true);
        comp.setEvidence(List.of(ev));
        detail.setComposers(comp);

        ReviewItem.CreditFieldResult arr = new ReviewItem.CreditFieldResult();
        arr.setStatus(ReviewItem.CreditStatus.CONFLICT);
        arr.setNotes("两家媒体编曲署名不一致");
        ReviewItem.CreditConflictCandidate cc1 = new ReviewItem.CreditConflictCandidate();
        cc1.setNames(List.of("Soma Genda"));
        cc1.setUrls(List.of("https://himehina.jp/discography/vampy"));
        cc1.setQuotes(List.of("編曲：Soma Genda"));
        ReviewItem.CreditConflictCandidate cc2 = new ReviewItem.CreditConflictCandidate();
        cc2.setNames(List.of("南田健吾"));
        cc2.setUrls(List.of("https://utaten.com/lyric/123/"));
        cc2.setQuotes(List.of("編曲：南田健吾"));
        arr.setCandidates(List.of(cc1, cc2));
        detail.setArrangers(arr);
        track.setCreditDetail(detail);

        cand.setTracks(List.of(track));
        item.setOnlineCandidates(List.of(cand));
        reviewQueue.update(item);

        HttpResponse<String> resp = get("/api/review/item?id=" + item.getId());
        assertEquals(200, resp.statusCode());
        JsonObject detailItem = gson.fromJson(resp.body(), JsonObject.class).getAsJsonObject("item");
        assertFalse(detailItem.get("onlineCreditsApplyProbable").getAsBoolean());
        JsonObject trackJson = detailItem.getAsJsonArray("onlineCandidates").get(0).getAsJsonObject()
            .getAsJsonArray("tracks").get(0).getAsJsonObject();
        JsonObject cd = trackJson.getAsJsonObject("creditDetail");
        assertNotNull(cd);
        assertEquals("original", cd.get("versionType").getAsString());
        assertEquals("VERIFIED", cd.getAsJsonObject("composers").get("status").getAsString());
        assertEquals("artist_official", cd.getAsJsonObject("composers").getAsJsonArray("evidence")
            .get(0).getAsJsonObject().get("sourceType").getAsString());
        assertEquals("作曲：Soma Genda", cd.getAsJsonObject("composers").getAsJsonArray("evidence")
            .get(0).getAsJsonObject().get("quote").getAsString());
        assertEquals("CONFLICT", cd.getAsJsonObject("arrangers").get("status").getAsString());
        assertEquals("两家媒体编曲署名不一致", cd.getAsJsonObject("arrangers").get("notes").getAsString());
        assertEquals(2, cd.getAsJsonObject("arrangers").getAsJsonArray("candidates").size());
    }

    @Test
    void detailExposesEntityMbidsAndOnlineConfirmValidatesAndAppliesEntityMbidOverrides() throws Exception {
        String idSinger = "11111111-1111-4111-8111-111111111111";
        String idComp = "22222222-2222-4222-8222-222222222222";
        Path albumDir = Files.createDirectory(monitorDir.resolve("ServletMbidsAlbum"));
        File flac = createFlac(albumDir.resolve("01. Song.flac"), "Song", "Singer", "Old", "1",
            null, null, null);
        ReviewItem item = reviewQueue.enqueueRecoveryFolder(albumDir.toString(), null, List.of(flac), tagWriter, null);

        ReviewItem.OnlineCandidate cand = new ReviewItem.OnlineCandidate();
        cand.setId("cand-mbid-http");
        cand.setTitle("MBID Album");
        cand.setArtist("Singer");
        cand.setAlbumArtist("Singer");
        ReviewItem.OnlineTrack track = new ReviewItem.OnlineTrack();
        track.setDiscNo(1);
        track.setTrackNo(1);
        track.setTitle("Song");
        track.setArtist("Singer");
        track.setComposers(List.of("Auto Comp"));
        track.setDuration(120);
        cand.setTracks(List.of(track));

        ReviewItem.EntityMbidResolution resSinger = new ReviewItem.EntityMbidResolution();
        resSinger.setDisplayName("Singer");
        resSinger.setNormalizedKey("singer");
        resSinger.setStatus(ReviewItem.EntityMbidStatus.AMBIGUOUS);
        ReviewItem.EntityMbidCandidate mbCand = new ReviewItem.EntityMbidCandidate();
        mbCand.setMbid(idSinger);
        mbCand.setName("Singer");
        mbCand.setSortName("Singer, Official");
        mbCand.setDisambiguation("Japanese vocalist");
        resSinger.setCandidates(List.of(mbCand));

        ReviewItem.EntityMbidResolution resComp = new ReviewItem.EntityMbidResolution();
        resComp.setDisplayName("Auto Comp");
        resComp.setNormalizedKey("auto comp");
        resComp.setStatus(ReviewItem.EntityMbidStatus.RESOLVED);
        resComp.setSource(ReviewItem.EntityMbidSource.LOCAL_CACHE);
        resComp.setSelectedMbid(idComp);
        resComp.setSelectedSortName("Comp, Auto");

        cand.setEntityMbids(Map.of("singer", resSinger, "auto comp", resComp));
        item.setOnlineCandidates(List.of(cand));
        OnlineIdentificationService online = new OnlineIdentificationService(config, reviewQueue, tagWriter, null);
        item.setEvidenceHash(online.evidenceHashForFolder(albumDir.toFile()));
        reviewQueue.update(item);

        // 1. GET /api/review/item 透传配置开关与 candidate.entityMbids
        HttpResponse<String> detailResp = get("/api/review/item?id=" + item.getId());
        assertEquals(200, detailResp.statusCode());
        JsonObject detailItem = gson.fromJson(detailResp.body(), JsonObject.class).getAsJsonObject("item");
        assertTrue(detailItem.get("onlineMbidResolveEnabled").getAsBoolean());
        JsonObject entityMbids = detailItem.getAsJsonArray("onlineCandidates").get(0).getAsJsonObject()
            .getAsJsonObject("entityMbids");
        assertNotNull(entityMbids);
        assertEquals("AMBIGUOUS", entityMbids.getAsJsonObject("singer").get("status").getAsString());
        assertEquals("RESOLVED", entityMbids.getAsJsonObject("auto comp").get("status").getAsString());

        // 2. 非法 entityMbidOverrides 全部返回 HTTP 400 online.mbid.override.invalid
        java.util.Map<String, Object> conflictingKeysMap = new java.util.LinkedHashMap<>();
        conflictingKeysMap.put("Singer", idSinger);
        conflictingKeysMap.put("singer", idComp);
        for (Object badPayload : List.of(
                List.of("not-a-map"),
                Map.of("Singer", "not-a-uuid"),
                Map.of("Singer", "00000000-0000-0000-0000-000000000000"),
                Map.of("Singer", "   "),
                Map.of("Singer", 12345),
                Map.of("   ", idSinger),
                conflictingKeysMap)) {
            HttpResponse<String> badResp = post("/api/review/online-confirm", Map.of(
                "id", item.getId(),
                "candidateId", cand.getId(),
                "albumTitle", "MBID Album",
                "albumArtist", "Singer",
                "confirmNoCover", true,
                "entityMbidOverrides", badPayload
            ));
            assertEquals(400, badResp.statusCode(), "Expected 400 for " + badPayload);
            assertEquals("online.mbid.override.invalid",
                gson.fromJson(badResp.body(), JsonObject.class).get("error").getAsString());
        }
        // 显式发送含 JSON null 值的原始请求体（默认 Gson 会省略 null 字段）
        HttpResponse<String> nullValResp = postRaw("/api/review/online-confirm", """
            {"id":"%s","candidateId":"%s","albumTitle":"MBID Album","albumArtist":"Singer",
             "confirmNoCover":true,"entityMbidOverrides":{"Singer":null}}
            """.formatted(item.getId(), cand.getId()));
        assertEquals(400, nullValResp.statusCode(), "Expected 400 for explicit JSON null value");
        assertEquals("online.mbid.override.invalid",
            gson.fromJson(nullValResp.body(), JsonObject.class).get("error").getAsString());

        // 3. 合法覆盖（大写 UUID 规范化 + "" 显式取消绑定）生效
        HttpResponse<String> okResp = post("/api/review/online-confirm", Map.of(
            "id", item.getId(),
            "candidateId", cand.getId(),
            "albumTitle", "MBID Album",
            "albumArtist", "Singer",
            "confirmNoCover", true,
            "entityMbidOverrides", Map.of(
                "Singer", idSinger.toUpperCase(),
                "Auto Comp", ""
            )
        ));
        assertEquals(200, okResp.statusCode(), okResp.body());
        MusicMetadata out = tagWriter.readTags(outputDir.resolve("Singer/MBID Album/1.01 Singer - Song.flac").toFile());
        assertNotNull(out);
        assertEquals(idSinger, out.getAlbumArtistId());
        assertEquals("Singer, Official", out.getAlbumArtistSort());
        assertEquals(idSinger, out.getArtistId());
        assertEquals("Auto Comp", out.getComposer());
        assertNull(out.getComposerIds(), "Explicit unbind must prevent writing auto-resolved composer MBID");
    }

    private static File createFlac(Path target, String title, String artist, String album, String trackNo,
                                   String composer, String lyricist, String arranger) throws Exception {
        Files.createDirectories(target.getParent());
        ByteBuffer bytes = ByteBuffer.allocate(554);
        bytes.put(new byte[]{'f', 'L', 'a', 'C', (byte) 128, 0, 0, 34});
        bytes.putLong(18, (44100L << 44) | (1L << 41) | (15L << 36) | 5303025L);
        for (int i = 42; i < bytes.capacity(); i++) bytes.put(i, (byte) (i * 11 + trackNo.hashCode()));
        Files.write(target, bytes.array());
        var audio = AudioFileIO.read(target.toFile());
        Tag tag = audio.getTagOrCreateAndSetDefault();
        tag.setField(FieldKey.TITLE, title);
        tag.setField(FieldKey.ARTIST, artist);
        tag.setField(FieldKey.ALBUM, album);
        tag.setField(FieldKey.ALBUM_ARTIST, artist);
        tag.setField(FieldKey.TRACK, trackNo);
        if (composer != null) tag.setField(FieldKey.COMPOSER, composer);
        if (lyricist != null) tag.setField(FieldKey.LYRICIST, lyricist);
        if (arranger != null) tag.setField(FieldKey.ARRANGER, arranger);
        audio.commit();
        return target.toFile();
    }
}
