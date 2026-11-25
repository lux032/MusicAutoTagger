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
        // 先触发一次 GET 建立会话 cookie
        get("/api/review/stats");
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .header("X-CSRF-Token", "tok")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
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
