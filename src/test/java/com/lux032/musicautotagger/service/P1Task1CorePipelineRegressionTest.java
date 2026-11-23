package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.llm.LlmClient;
import com.lux032.musicautotagger.service.llm.TavilyWebSearchClient;
import com.lux032.musicautotagger.service.llm.WebSearchClient;
import com.lux032.musicautotagger.util.AudioFileOrdering;
import com.lux032.musicautotagger.util.ConfidenceModel;
import com.lux032.musicautotagger.util.FileSystemUtils;
import com.lux032.musicautotagger.util.FlacId3Stripper;
import com.lux032.musicautotagger.util.MetadataUtils;
import com.sun.net.httpserver.HttpServer;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.StandardArtwork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审计任务 1（P1 核心管道：C-1 ~ C-6、A-1 ~ A-5）专项回归测试。
 */
class P1Task1CorePipelineRegressionTest {

    @TempDir
    Path tempDir;

    private MusicConfig newConfig(Path root) throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MusicConfig cfg = ctor.newInstance();
        cfg.setMonitorDirectory(Files.createDirectories(root.resolve("monitor")).toString());
        cfg.setOutputDirectory(Files.createDirectories(root.resolve("output")).toString());
        cfg.setFailedDirectory(Files.createDirectories(root.resolve("failed")).toString());
        cfg.setPartialDirectory(Files.createDirectories(root.resolve("partial")).toString());
        cfg.setCoverArtCacheDirectory(Files.createDirectories(root.resolve("cover-cache")).toString());
        cfg.setReviewQueuePath(root.resolve("data/review-queue.json").toString());
        cfg.setReviewStagingDirectory(root.resolve("data/review-staging").toString());
        cfg.setRecoveryWorkDirectory(root.resolve("data/recovery-work").toString());
        cfg.setRecoveryTrashDirectory(root.resolve("data/recovery-trash").toString());
        cfg.setProcessedFileLogPath(root.resolve("data/processed.log").toString());
        cfg.setDbType("file");
        cfg.setAutoRename(true);
        cfg.setExportLyricsToFile(false);
        cfg.setCoverMissingRequireReview(true);
        cfg.setCoverMissingAutoOnlineSearch(false);
        return cfg;
    }

    private File createFlac(Path path, String title, String artist, String album, String albumArtist) throws Exception {
        return createFlacWithSampleRate(path, title, artist, album, albumArtist, 44100, 16);
    }

    private File createFlacWithSampleRate(Path path, String title, String artist, String album,
                                          String albumArtist, int sampleRate, int bitsPerSample) throws Exception {
        byte[] data = new byte[42 + 512];
        System.arraycopy(new byte[]{'f', 'L', 'a', 'C', (byte) 128, 0, 0, 34}, 0, data, 0, 8);
        long totalSamples = sampleRate * 120L;
        long info = ((long) sampleRate << 44) | (1L << 41) | ((long) (bitsPerSample - 1) << 36) | totalSamples;
        for (int i = 0; i < 8; i++) {
            data[18 + i] = (byte) (info >>> (56 - 8 * i));
        }
        for (int i = 42; i < data.length; i++) {
            data[i] = (byte) ((i * 13) & 0xFF);
        }
        Files.createDirectories(path.getParent());
        Files.write(path, data);
        AudioFile af = AudioFileIO.read(path.toFile());
        Tag tag = af.getTagOrCreateAndSetDefault();
        if (title != null) tag.setField(FieldKey.TITLE, title);
        if (artist != null) tag.setField(FieldKey.ARTIST, artist);
        if (album != null) tag.setField(FieldKey.ALBUM, album);
        if (albumArtist != null) tag.setField(FieldKey.ALBUM_ARTIST, albumArtist);
        af.commit();
        return path.toFile();
    }

    private byte[] createImageBytes(int width, int height, String format) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(40, 120, 200));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        g.fillRect(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    private Map<String, Object> awaitLibraryScan(CoverMissingService service) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (service.isLibraryScanRunning() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(service.isLibraryScanRunning());
        return service.getScanStatus();
    }

    // ==================== C-1 ====================

    @Test
    void c1_confirmNoCoverSkipsCaaAndFolderFallbackCovers() throws Exception {
        Path root = tempDir.resolve("c1");
        MusicConfig cfg = newConfig(root);
        ReviewQueueService reviewQueue = new ReviewQueueService(cfg);
        FolderAlbumCache folderCache = new FolderAlbumCache(new DurationSequenceService(), null, null);
        TagWriterService tagWriter = new TagWriterService(cfg);
        ProcessedFileLogger processedLogger = new ProcessedFileLogger(cfg, null);
        CoverArtCache coverArtCache = new CoverArtCache(null, cfg.getCoverArtCacheDirectory(), cfg);

        byte[] dummyJpeg = createImageBytes(300, 300, "jpg");
        String rgid = "11111111-2222-3333-4444-555555555555";
        AtomicBoolean caaAvailable = new AtomicBoolean(false);
        MusicBrainzClient mbClient = new MusicBrainzClient(cfg) {
            @Override
            public CoverArtResolution resolveCoverArtByReleaseGroupId(String releaseGroupId) {
                if (caaAvailable.get()) {
                    return new CoverArtResolution("https://example.com/cover.jpg", true);
                }
                return new CoverArtResolution(null, false);
            }
        };
        CoverArtService coverArtService = new CoverArtService(coverArtCache, mbClient);
        CoverCandidateService candidateService = new CoverCandidateService(cfg, mbClient, coverArtService, coverArtCache);
        AlbumBatchProcessor batchProcessor = new AlbumBatchProcessor(cfg, folderCache, tagWriter, processedLogger, coverArtService);
        batchProcessor.setReviewQueueService(reviewQueue);

        Path albumDir = Files.createDirectories(Path.of(cfg.getMonitorDirectory()).resolve("NoCoverC1"));
        File flac = createFlac(albumDir.resolve("01 Song.flac"), "Song", "ArtistC1", "NoCoverC1", "ArtistC1");
        MusicMetadata md = new MusicMetadata();
        md.setRecordingId("rec-c1");
        md.setTitle("Song");
        md.setArtist("ArtistC1");
        md.setAlbum("NoCoverC1");
        md.setAlbumArtist("ArtistC1");
        md.setReleaseGroupId(rgid);
        md.setTrackNo("1");

        batchProcessor.addPendingFile(albumDir.toString(), flac, flac, null, md, null);
        FolderAlbumCache.CachedAlbumInfo albumInfo = new FolderAlbumCache.CachedAlbumInfo(
            rgid, "rel-c1", "NoCoverC1", "ArtistC1", 1, "2025-01-01", 1.0, FolderAlbumCache.CacheSource.DURATION_SEQUENCE);
        AlbumBatchProcessor.BatchProcessResult intercepted =
            batchProcessor.processPendingFilesWithAlbum(albumDir.toString(), albumInfo, false);
        assertTrue(intercepted.isQueuedForMissingCover());

        // 把封面放进目录和缓存，验证 confirmNoCover(CoverPolicy.NONE) 仍然绝不嵌入封面
        caaAvailable.set(true);
        coverArtCache.cacheCoverByReleaseGroupId(rgid, dummyJpeg);
        Files.write(albumDir.resolve("cover.jpg"), dummyJpeg);

        try (CoverMissingService service = new CoverMissingService(
                cfg, reviewQueue, folderCache, batchProcessor, tagWriter, candidateService, processedLogger)) {
            ReviewItem resolved = service.confirmNoCover(intercepted.getQueuedReviewItemId(), RecoveryService.DuplicatePolicy.ASK);
            assertEquals(ReviewItem.Status.CONFIRMED, resolved.getStatus());
            assertTrue(resolved.getMissingCover().isConfirmedNoCover());
            assertFalse(resolved.getMissingCover().getArchivedOutputDirs().isEmpty());
        }

        Path archived = Path.of(cfg.getOutputDirectory()).resolve("ArtistC1/NoCoverC1/01. ArtistC1 - Song.flac");
        assertTrue(Files.isRegularFile(archived));
        assertFalse(tagWriter.hasEmbeddedCover(archived.toFile()), "confirmNoCover 不应重新拉取 CAA 或目录兜底封面");

        // 再验证本地入库 confirmNoCover 同样跳过目录兜底封面
        FileSystemUtils fsUtils = new FileSystemUtils(cfg);
        FailedFileHandler failedHandler = new FailedFileHandler(cfg, tagWriter, coverArtService, processedLogger, fsUtils);
        RecoveryService recoveryService = new RecoveryService(
            cfg, null, processedLogger, reviewQueue, folderCache, failedHandler, fsUtils, tagWriter, null, candidateService);
        Path localDir = Files.createDirectories(Path.of(cfg.getMonitorDirectory()).resolve("LocalNoCover"));
        File localFlac = createFlac(localDir.resolve("01 Local.flac"), "Local", "LocalArtist", "LocalNoCover", "LocalArtist");

        MusicMetadata localMd = new MusicMetadata();
        localMd.setTitle("Local");
        localMd.setArtist("LocalArtist");
        localMd.setAlbum("LocalNoCover");
        localMd.setAlbumArtist("LocalArtist");
        FolderAlbumCache.PendingFile pf = new FolderAlbumCache.PendingFile(localFlac, localFlac, null, localMd, null);
        FolderAlbumCache.CachedAlbumInfo synth = new FolderAlbumCache.CachedAlbumInfo(
            null, null, "LocalNoCover", "LocalArtist", 1, null, 0.5, FolderAlbumCache.CacheSource.UNKNOWN);
        ReviewItem localItem = reviewQueue.enqueue(
            localDir.toString(), List.of(pf), synth, List.of(), List.of(120), "unresolved", 0.5);

        LocalArchiveDraft.Draft draft = recoveryService.previewLocalArchive(localItem.getId());
        ReviewItem interceptedLocal = recoveryService.confirmLocalArchive(
            localItem.getId(), draft.revision(), "LocalNoCover", "LocalArtist", List.of("LocalArtist"),
            Map.of(), List.of(), RecoveryService.DuplicatePolicy.ASK);
        assertEquals(ReviewItem.Kind.COVER_MISSING, interceptedLocal.effectiveKind());

        // 放入目录封面后以 confirmNoCover=true 提交
        Files.write(localDir.resolve("cover.jpg"), dummyJpeg);
        ReviewItem doneLocal = recoveryService.commitDeferredLocalMissingCover(
            interceptedLocal, null, true, RecoveryService.DuplicatePolicy.ASK);
        assertEquals(ReviewItem.Status.CONFIRMED, doneLocal.getStatus());
        Path localArchived = Path.of(cfg.getOutputDirectory()).resolve("LocalArtist/LocalNoCover/01 Local.flac");
        assertTrue(Files.isRegularFile(localArchived));
        assertFalse(tagWriter.hasEmbeddedCover(localArchived.toFile()), "本地入库 confirmNoCover 不应嵌入目录封面");
    }

    // ==================== C-2 ====================

    @Test
    void c2_libraryScanIgnoresOpusSeparatesLooseTracksSkipsRejectedAndDefersFailureState() throws Exception {
        Path root = tempDir.resolve("c2");
        MusicConfig cfg = newConfig(root);
        Path out = Path.of(cfg.getOutputDirectory());

        // .opus 应被忽略；两首散曲必须各自独立建项；另建两个正常专辑目录
        Files.writeString(out.resolve("ignored.opus"), "fake-opus");
        File looseA = createFlac(out.resolve("looseA.flac"), "Loose A", "ArtA", "Single A", "ArtA");
        File looseB = createFlac(out.resolve("looseB.flac"), "Loose B", "ArtB", "Single B", "ArtB");
        createFlac(out.resolve("ArtistR/RejectedAlbum/01.flac"), "R1", "ArtistR", "RejectedAlbum", "ArtistR");
        createFlac(out.resolve("ArtistK/KeptAlbum/01.flac"), "K1", "ArtistK", "KeptAlbum", "ArtistK");

        ReviewQueueService reviewQueue = new ReviewQueueService(cfg);
        FolderAlbumCache folderCache = new FolderAlbumCache(new DurationSequenceService(), null, null);
        TagWriterService tagWriter = new TagWriterService(cfg);
        ProcessedFileLogger processedLogger = new ProcessedFileLogger(cfg, null);
        CoverArtCache coverArtCache = new CoverArtCache(null, cfg.getCoverArtCacheDirectory(), cfg);
        MusicBrainzClient mbClient = new MusicBrainzClient(cfg) {
            @Override
            public CoverArtResolution resolveCoverArtByReleaseGroupId(String rgid) {
                return new CoverArtResolution(null, false);
            }
        };
        CoverArtService coverArtService = new CoverArtService(coverArtCache, mbClient);
        CoverCandidateService candidateService = new CoverCandidateService(cfg, mbClient, coverArtService, coverArtCache);
        AlbumBatchProcessor batchProcessor = new AlbumBatchProcessor(cfg, folderCache, tagWriter, processedLogger, coverArtService);
        batchProcessor.setReviewQueueService(reviewQueue);

        try (CoverMissingService service = new CoverMissingService(
                cfg, reviewQueue, folderCache, batchProcessor, tagWriter, candidateService, processedLogger)) {
            assertTrue(service.startLibraryScan());
            Map<String, Object> status = awaitLibraryScan(service);
            assertEquals(4, status.get("foundMissing"), "应忽略 .opus 并将两首散曲独立计数");

            List<ReviewItem> pending = reviewQueue.listByKinds(ReviewItem.Status.PENDING_REVIEW, Set.of(ReviewItem.Kind.COVER_MISSING));
            assertEquals(4, pending.size());
            long looseCount = pending.stream()
                .filter(i -> i.getMissingCover() != null && i.getMissingCover().isLooseFile()).count();
            assertEquals(2, looseCount, "outputRoot 下两首散曲必须各自独立建项");
            assertTrue(pending.stream().anyMatch(i -> i.getFolderPath().equals(looseA.getAbsolutePath())));
            assertTrue(pending.stream().anyMatch(i -> i.getFolderPath().equals(looseB.getAbsolutePath())));

            // 驳回 RejectedAlbum 后再次扫描，不应重新入队
            ReviewItem rejectedItem = pending.stream()
                .filter(i -> "RejectedAlbum".equals(i.getMissingCover().getAlbumTitle())).findFirst().orElseThrow();
            reviewQueue.markResolved(rejectedItem, ReviewItem.Status.REJECTED, "忽略");

            ReviewItem keptItem = pending.stream()
                .filter(i -> "KeptAlbum".equals(i.getMissingCover().getAlbumTitle())).findFirst().orElseThrow();
            reviewQueue.markResolved(keptItem, ReviewItem.Status.REJECTED, "临时移除便于测试 STAGED_IMPORT");

            // 模拟 STAGED_IMPORT 确认无封面记录了 archivedOutputDirs，再次扫描也应跳过
            Path stagedArchivedDir = out.resolve("ArtistS/StagedNoCover");
            File stagedFlac = createFlac(stagedArchivedDir.resolve("01.flac"), "S1", "ArtistS", "StagedNoCover", "ArtistS");
            MusicMetadata smd = new MusicMetadata();
            smd.setTitle("S1");
            smd.setArtist("ArtistS");
            FolderAlbumCache.PendingFile spf = new FolderAlbumCache.PendingFile(stagedFlac, stagedFlac, null, smd, null);
            FolderAlbumCache.CachedAlbumInfo sSynth = new FolderAlbumCache.CachedAlbumInfo(
                null, null, "StagedNoCover", "ArtistS", 1, null, 1.0, FolderAlbumCache.CacheSource.UNKNOWN);
            ReviewItem stagedRecord = reviewQueue.enqueue(
                tempDir.resolve("c2/monitor/StagedNoCover").toString(),
                List.of(spf), sSynth, List.of(), List.of(120), "missing", 1.0);
            ReviewItem.MissingCover stagedMc = new ReviewItem.MissingCover();
            stagedMc.setMode(ReviewItem.MissingCover.Mode.STAGED_IMPORT);
            stagedMc.setAlbumTitle("StagedNoCover");
            stagedMc.setAlbumArtist("ArtistS");
            stagedMc.setConfirmedNoCover(true);
            stagedMc.setArchivedOutputDirs(List.of(stagedArchivedDir.toAbsolutePath().normalize().toString()));
            reviewQueue.transitionToMissingCover(stagedRecord, stagedMc, "missing");
            reviewQueue.markResolved(stagedRecord, ReviewItem.Status.CONFIRMED, "confirmed no cover");

            assertTrue(service.startLibraryScan());
            Map<String, Object> status2 = awaitLibraryScan(service);
            assertEquals(0, status2.get("foundMissing"), "REJECTED 与 STAGED_IMPORT 已确认无封面目录均应被跳过");

            // 测试 commitDeferred* 失败时不提前落盘 confirmedNoCover=true
            File brokenFlac = createFlac(tempDir.resolve("c2/monitor/BrokenLocal/01.flac"), "B1", "ArtistB", "BrokenLocal", "ArtistB");
            MusicMetadata bmd = new MusicMetadata();
            bmd.setTitle("B1");
            bmd.setArtist("ArtistB");
            FolderAlbumCache.PendingFile bpf = new FolderAlbumCache.PendingFile(brokenFlac, brokenFlac, null, bmd, null);
            FolderAlbumCache.CachedAlbumInfo bSynth = new FolderAlbumCache.CachedAlbumInfo(
                null, null, "BrokenLocal", "ArtistB", 1, null, 1.0, FolderAlbumCache.CacheSource.UNKNOWN);
            ReviewItem brokenStaged = reviewQueue.enqueue(
                tempDir.resolve("c2/monitor/BrokenLocal").toString(),
                List.of(bpf), bSynth, List.of(), List.of(120), "wait", 1.0);
            assertNotNull(brokenStaged);
            ReviewItem.MissingCover brokenMc = new ReviewItem.MissingCover();
            brokenMc.setMode(ReviewItem.MissingCover.Mode.STAGED_IMPORT);
            brokenMc.setLocalArchiveDirect(true);
            brokenMc.setAlbumTitle("BrokenLocal");
            brokenMc.setAlbumArtist("ArtistB");
            reviewQueue.transitionToMissingCover(brokenStaged, brokenMc, "wait");

            // 未设置 recoveryService 会直接抛 ResolutionException(501)
            assertThrows(ReviewResolutionService.ResolutionException.class,
                () -> service.confirmNoCover(brokenStaged.getId(), RecoveryService.DuplicatePolicy.ASK));
            ReviewItem reloaded = reviewQueue.get(brokenStaged.getId());
            assertEquals(ReviewItem.Status.PENDING_REVIEW, reloaded.getStatus());
            assertFalse(reloaded.getMissingCover().isConfirmedNoCover(), "提交失败绝不能提前落盘 confirmedNoCover=true");
        }
    }

    // ==================== C-3 ====================

    @Test
    void c3_coverSearchInterruptRevertsStatusPromptSeparationLooseRootAndUuidCheck() throws Exception {
        Path root = tempDir.resolve("c3");
        MusicConfig cfg = newConfig(root);
        ReviewQueueService reviewQueue = new ReviewQueueService(cfg);
        CoverArtCache coverArtCache = new CoverArtCache(null, cfg.getCoverArtCacheDirectory(), cfg);
        AtomicInteger mbCalls = new AtomicInteger();
        MusicBrainzClient mbClient = new MusicBrainzClient(cfg) {
            @Override
            public CoverArtResolution resolveCoverArtByReleaseGroupId(String rgid) {
                mbCalls.incrementAndGet();
                return new CoverArtResolution(null, false);
            }
        };
        CoverArtService coverArtService = new CoverArtService(coverArtCache, mbClient);
        CoverCandidateService candidateService = new CoverCandidateService(cfg, mbClient, coverArtService, coverArtCache);

        // 非法 UUID 必须被拦截，不发起 CAA 请求
        assertTrue(candidateService.fetchMusicBrainzCoverByRgid("../bad-rgid").isEmpty());
        assertEquals(0, mbCalls.get());

        // Tavily renderEvidence(includeImages=true) 不应追加专辑 candidates/needs_second_round 模板
        TavilyWebSearchClient tavilyClient = new TavilyWebSearchClient(cfg, new LlmClient(cfg));
        Method renderEvidence = TavilyWebSearchClient.class.getDeclaredMethod("renderEvidence", List.class, boolean.class);
        renderEvidence.setAccessible(true);
        List<WebSearchClient.Citation> citations = List.of(
            WebSearchClient.Citation.of("https://example.com/album", "Cover Page", "snippet"));
        String coverPrompt = (String) renderEvidence.invoke(tavilyClient, citations, true);
        String albumPrompt = (String) renderEvidence.invoke(tavilyClient, citations, false);
        assertFalse(coverPrompt.contains("needs_second_round"));
        assertTrue(albumPrompt.contains("needs_second_round"));

        // 散曲 resolveAlbumRoot 必须返回 null，防止扫遍 monitorDirectory
        CoverSearchService searchService = new CoverSearchService(cfg, reviewQueue, candidateService, new WebSearchClient() {
            @Override public String name() { return "stub"; }
            @Override public boolean hasEnabledEndpoint() { return true; }
            @Override public SearchResponse search(String sys, String user) throws LlmClient.LlmException {
                throw new LlmClient.LlmException("interrupted", new InterruptedException("stop"));
            }
        });
        Path looseTrack = createFlac(Path.of(cfg.getMonitorDirectory()).resolve("loose.flac"), "L", "A", "Al", "A").toPath();
        ReviewItem looseItem = new ReviewItem();
        ReviewItem.MissingCover looseMc = new ReviewItem.MissingCover();
        looseMc.setLooseFile(true);
        looseItem.setMissingCover(looseMc);
        looseItem.setFolderPath(cfg.getMonitorDirectory());
        assertNull(searchService.resolveAlbumRoot(looseItem, List.of(looseTrack.toFile())), "散曲不得返回 monitorDirectory");

        ReviewItem filePathItem = new ReviewItem();
        filePathItem.setFolderPath(looseTrack.toString());
        assertNull(searchService.resolveAlbumRoot(filePathItem, List.of(looseTrack.toFile())), "指向单文件的 folderPath 不得上升到父目录");

        // 中断异常必须抛出 InterruptedException 并回退状态到 IDLE（非 DONE）
        File searchTrack = createFlac(Path.of(cfg.getMonitorDirectory()).resolve("SearchDir/01.flac"), "T", "TestArt", "TestAlb", "TestArt");
        MusicMetadata searchMd = new MusicMetadata();
        searchMd.setTitle("T");
        searchMd.setArtist("TestArt");
        FolderAlbumCache.PendingFile searchPf = new FolderAlbumCache.PendingFile(searchTrack, searchTrack, null, searchMd, null);
        FolderAlbumCache.CachedAlbumInfo synth = new FolderAlbumCache.CachedAlbumInfo(
            null, null, "TestAlb", "TestArt", 1, null, 1.0, FolderAlbumCache.CacheSource.UNKNOWN);
        ReviewItem item = reviewQueue.enqueue(
            searchTrack.getParentFile().getAbsolutePath(),
            List.of(searchPf), synth, List.of(), List.of(120), "wait", 1.0);
        assertNotNull(item);
        ReviewItem.MissingCover mc = new ReviewItem.MissingCover();
        mc.setMode(ReviewItem.MissingCover.Mode.STAGED_IMPORT);
        mc.setAlbumTitle("TestAlb");
        mc.setAlbumArtist("TestArt");
        reviewQueue.transitionToMissingCover(item, mc, "wait");

        try {
            assertThrows(InterruptedException.class, () -> searchService.searchCoversForItem(item.getId(), null, null));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        ReviewItem after = reviewQueue.get(item.getId());
        assertEquals("IDLE", after.getMissingCover().getCoverSearchStatus(), "中断后必须回退到 IDLE 而不是 DONE");
    }

    // ==================== C-4 ====================

    @Test
    void c4_imageCompressorPreserves1200pxSkipsReencodeAndCandidateRefreshesMtime() throws Exception {
        // 1400x1400 PNG 必须缩放到 1200x1200，而非 Math.ceil(1400/1200)=2 子采样腰斩成 700x700
        byte[] png1400 = createImageBytes(1400, 1400, "png");
        byte[] compressed = ImageCompressor.compressImage(png1400);
        assertNotNull(compressed);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(compressed));
        assertEquals(1200, decoded.getWidth());
        assertEquals(1200, decoded.getHeight());

        // 已合规 800x800 RGB JPEG 必须原样透传（字节完全一致，避免二次有损重编码）
        byte[] compliantJpeg = createImageBytes(800, 800, "jpg");
        byte[] passedThrough = ImageCompressor.compressImage(compliantJpeg);
        assertArrayEquals(compliantJpeg, passedThrough, "已合规 JPEG 不应重复有损重编码");

        // CoverCandidateService 重入同一图片时必须刷新 mtime 防止提前被清理
        Path root = tempDir.resolve("c4");
        MusicConfig cfg = newConfig(root);
        CoverArtCache artCache = new CoverArtCache(null, cfg.getCoverArtCacheDirectory(), cfg);
        MusicBrainzClient mbClient = new MusicBrainzClient(cfg);
        CoverArtService coverArtService = new CoverArtService(artCache, mbClient);
        CoverCandidateService candidateService = new CoverCandidateService(cfg, mbClient, coverArtService, artCache);

        CoverCandidateView first = candidateService.ingestUploadedBytes(compliantJpeg, "front.jpg").orElseThrow();
        Path cachedFile;
        try (var stream = Files.walk(Path.of(cfg.getCoverArtCacheDirectory()))) {
            cachedFile = stream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().startsWith(first.getSha256()))
                .findFirst().orElseThrow();
        }
        FileTime oldTime = FileTime.from(Instant.now().minusSeconds(3600 * 24 * 10));
        Files.setLastModifiedTime(cachedFile, oldTime);

        candidateService.ingestUploadedBytes(compliantJpeg, "front.jpg").orElseThrow();
        FileTime refreshed = Files.getLastModifiedTime(cachedFile);
        assertTrue(refreshed.toMillis() > oldTime.toMillis(), "同图重入应刷新候选文件 mtime");

        // UntrustedImageFetcher(config) 即使配置了代理也必须拦截私网/回环目标地址
        cfg.setProxyEnabled(true);
        cfg.setProxyHost("127.0.0.1");
        cfg.setProxyPort(8888);
        UntrustedImageFetcher proxyFetcher = new UntrustedImageFetcher(cfg);
        assertTrue(proxyFetcher.fetch("http://127.0.0.1/secret.jpg").isEmpty());
    }

    // ==================== C-5 ====================

    @Test
    void c5_sqliteCacheNoDeadlockArtistScopedBackfillAndBoundedLruFolderCache() throws Exception {
        Path root = tempDir.resolve("c5");
        MusicConfig cfg = newConfig(root);
        cfg.setDbType("sqlite");
        cfg.setDbSqlitePath(root.resolve("data/test.db").toString());

        DatabaseService db = new DatabaseService(cfg);
        try {
            // 1. CoverArtCache 在 SQLite 单连接池下读取到缺失文件时不会嵌套取连接死锁
            CoverArtCache cache = new CoverArtCache(db, cfg.getCoverArtCacheDirectory(), cfg);
            byte[] jpeg = createImageBytes(300, 300, "jpg");
            cache.cacheCover("https://example.com/a.jpg", jpeg);
            // 删掉磁盘缓存文件，再读取触发 deleteCacheEntry，验证无死锁
            try (var stream = Files.list(Path.of(cfg.getCoverArtCacheDirectory()))) {
                for (Path p : stream.toList()) {
                    if (Files.isRegularFile(p)) Files.deleteIfExists(p);
                }
            }
            assertNull(cache.getCachedCover("https://example.com/a.jpg"));
            assertDoesNotThrow(() -> cache.cleanupOldCache(0));

            // 2. CoverBackfill / ProcessedFileLogger 按 (album, artist) 隔离同名专辑，不跨艺人污染
            ProcessedFileLogger logger = new ProcessedFileLogger(cfg, db);
            File f1 = createFlac(root.resolve("monitor/A1/gh1.flac"), "T1", "Artist One", "Greatest Hits", "Artist One");
            File f2 = createFlac(root.resolve("monitor/A2/gh2.flac"), "T2", "Artist Two", "Greatest Hits", "Artist Two");
            File f3 = createFlac(root.resolve("monitor/A3/unknown.flac"), "T3", "Artist Three", "Greatest Hits", "Artist Three");
            logger.markFileAsProcessed(f1, "rec-1", "Artist One", "T1", "Greatest Hits", null, null);
            logger.markFileAsProcessed(f2, "rec-2", "Artist Two", "T2", "Greatest Hits", null, null);
            logger.markFileAsProcessed(f3, "UNKNOWN", "Artist Three", "T3", "Greatest Hits", null, null);

            List<ProcessedFileLogger.AlbumGroup> missing = logger.findAlbumsMissingReleaseGroupId();
            assertEquals(2, missing.size(), "两个不同艺人的同名专辑应分别返回，且排除 UNKNOWN 哨兵");
            int updated = logger.applyReleaseGroupId("Greatest Hits", "Artist One", "rg-artist-one");
            assertEquals(1, updated, "按 (album, artist) 回填只应更新 Artist One");
            List<ProcessedFileLogger.AlbumGroup> remaining = logger.findAlbumsMissingReleaseGroupId();
            assertEquals(1, remaining.size());
            assertEquals("Artist Two", remaining.get(0).artist);

            // 3. CoverArtService.folderCoverCache 有界 LRU（上限 64 条）
            CoverArtService coverArtService = new CoverArtService(cache, new MusicBrainzClient(cfg));
            Method putFolderCover = CoverArtService.class.getDeclaredMethod("putFolderCover", String.class, byte[].class, String.class);
            putFolderCover.setAccessible(true);
            for (int i = 0; i < 80; i++) {
                putFolderCover.invoke(coverArtService, "/album/" + i, jpeg, "rg-" + i);
            }
            Field cacheField = CoverArtService.class.getDeclaredField("folderCoverCache");
            cacheField.setAccessible(true);
            Map<?, ?> internalCache = (Map<?, ?>) cacheField.get(coverArtService);
            assertTrue(internalCache.size() <= 64, "folderCoverCache 必须受 64 条上限约束，实际: " + internalCache.size());
        } finally {
            db.close();
        }
    }

    // ==================== C-6 ====================

    @Test
    void c6_lyricsMatcherOverflowChorusLinkedArtworkAndCircuitBreaker() throws Exception {
        // 超长分钟数不抛 NumberFormatException；多时间戳副歌行合法
        assertFalse(LyricsMatcher.validSynced("[999999999999:00.00] overflow\n[999999999999:05.00] line2", 300));
        assertTrue(LyricsMatcher.validSynced("[00:10.00][01:20.00] Chorus\n[00:25.00] Verse 2", 200));

        // LyricsOnlyWriter 遇到外链封面（binaryData == null）不抛 NPE
        Path root = tempDir.resolve("c6");
        Path flac = createFlac(root.resolve("linked.flac"), "Title", "Artist", "Album", "Artist").toPath();
        AudioFile audio = AudioFileIO.read(flac.toFile());
        StandardArtwork linked = new StandardArtwork();
        linked.setLinked(true);
        linked.setImageUrl("https://example.com/cover.jpg");
        linked.setMimeType("-->");
        linked.setBinaryData(null);
        audio.getTag().setField(linked);
        audio.commit();
        assertTrue(LyricsOnlyWriter.write(root, flac, LyricsOnlyWriter.identity(flac), "Some lyrics", false));

        // LyricsService：200 畸形 JSON 不判健康；5xx 熔断重置计数且不缩短 429 Retry-After
        AtomicInteger mode = new AtomicInteger(0); // 0=malformed200, 1=429(120s), 2=500
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> {
            int m = mode.get();
            byte[] body;
            int status;
            if (m == 0) {
                status = 200;
                body = "{not-valid-json".getBytes(StandardCharsets.UTF_8);
            } else if (m == 1) {
                status = 429;
                exchange.getResponseHeaders().add("Retry-After", "120");
                body = "rate limited".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 500;
                body = "error".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            MusicConfig cfg = newConfig(root);
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
            try (LyricsService lyricsService = new LyricsService(cfg, baseUrl)) {
                // 畸形 JSON 200 应返回 DEFERRED 而非误判健康/NOT_FOUND
                LyricsService.Result r0 = lyricsService.lookup("Song", "Artist", "Album", 180.0, true);
                assertEquals(LyricsService.Status.DEFERRED, r0.status());

                // 触发 3 次 500 熔断后，consecutiveFailures 必须清零
                mode.set(2);
                Field cfField = LyricsService.class.getDeclaredField("consecutiveFailures");
                cfField.setAccessible(true);
                Field cdField = LyricsService.class.getDeclaredField("cooldownUntil");
                cdField.setAccessible(true);
                cfField.setInt(lyricsService, 0);
                cdField.setLong(lyricsService, 0L);

                for (int i = 0; i < 3; i++) {
                    lyricsService.lookup("Song" + i, "Artist", "Album", 180.0, true);
                }
                assertTrue(cdField.getLong(lyricsService) > System.currentTimeMillis(), "连续 3 次 5xx 应触发冷却");
                assertEquals(0, cfField.getInt(lyricsService), "触发熔断后应重置连续失败计数");

                // 429 设置 120s 冷却后，再调用 extendCooldown(5000) 不得把冷却时间缩短
                cdField.setLong(lyricsService, 0L);
                mode.set(1);
                lyricsService.lookup("RateLimited", "Artist", "Album", 180.0, true);
                long after429 = cdField.getLong(lyricsService);
                assertTrue(after429 - System.currentTimeMillis() > 60_000L);
                Method extendCooldown = LyricsService.class.getDeclaredMethod("extendCooldown", long.class);
                extendCooldown.setAccessible(true);
                extendCooldown.invoke(lyricsService, 5_000L);
                assertEquals(after429, cdField.getLong(lyricsService), "较短冷却不得缩短已有的长 Retry-After 冷却");
            }
        } finally {
            server.stop(0);
        }

        // LyricsNegativeCache 原子落盘可重读
        Path negPath = root.resolve("neg-cache.json");
        LyricsNegativeCache neg = new LyricsNegativeCache(negPath, Clock.systemUTC());
        neg.put("k1", LyricsService.Status.NOT_FOUND);
        LyricsNegativeCache reloadedNeg = new LyricsNegativeCache(negPath, Clock.systemUTC());
        assertTrue(reloadedNeg.contains("k1"));
    }

    // ==================== A-1 ====================

    @Test
    void a1_multiDiscOrderingQuickScanPartialCoverCueSplitAndSampleKeys() throws Exception {
        Path root = tempDir.resolve("a1");
        MusicConfig cfg = newConfig(root);

        // 1. AudioFileOrdering 不把 "2020 - Album" 的年份当碟号
        File yearDirTrack = createFlac(root.resolve("2020 - Album/01.flac"), "T1", "A", "Al", "A");
        File disc2Track = createFlac(root.resolve("Disc 2/01.flac"), "T2", "A", "Al", "A");
        File disc1Track = createFlac(root.resolve("Disc 1/01.flac"), "T0", "A", "Al", "A");
        List<File> files = new ArrayList<>(List.of(yearDirTrack, disc2Track, disc1Track));
        AudioFileOrdering.sort(files);
        assertEquals(List.of(disc1Track, disc2Track, yearDirTrack), files,
            "Disc 1 < Disc 2 < 无碟号的 2020 - Album（不能把 2020 当成碟号）");

        // 2. QuickScanService 在 Disc 1 子目录里应使用专辑根目录名搜索
        Path disc1Dir = Files.createDirectories(Path.of(cfg.getMonitorDirectory()).resolve("RealArtist - RealAlbum/Disc 1"));
        File untagged = createFlac(disc1Dir.resolve("01.flac"), null, null, null, null);
        AtomicReference<String> searchedAlbum = new AtomicReference<>();
        AtomicReference<String> searchedArtist = new AtomicReference<>();
        MusicBrainzClient mbSpy = new MusicBrainzClient(cfg) {
            @Override
            public List<MusicMetadata> searchAlbum(String albumName, String artistName) {
                searchedAlbum.set(albumName);
                searchedArtist.set(artistName);
                return List.of();
            }
        };
        QuickScanService quickScan = new QuickScanService(cfg, mbSpy, new DurationSequenceService(), null);
        quickScan.quickScan(untagged, 1, List.of(120));
        assertEquals("RealAlbum", searchedAlbum.get(), "多碟子目录快速扫描必须使用专辑根目录名而非 Disc 1");
        assertEquals("RealArtist", searchedArtist.get());

        // 3. FailedFileHandler 从碟片子目录回退加载封面后必须同步更新 hasFolderCover
        TagWriterService tagWriter = new TagWriterService(cfg);
        CoverArtCache artCache = new CoverArtCache(null, cfg.getCoverArtCacheDirectory(), cfg);
        CoverArtService coverArtService = new CoverArtService(artCache, mbSpy);
        FileSystemUtils fsUtils = new FileSystemUtils(cfg);
        coverArtService.setFileSystemUtils(fsUtils);
        ProcessedFileLogger logger = new ProcessedFileLogger(cfg, null);
        FailedFileHandler failedHandler = new FailedFileHandler(cfg, tagWriter, coverArtService, logger, fsUtils);

        Path partialAlbumRoot = Files.createDirectories(Path.of(cfg.getMonitorDirectory()).resolve("MultiDiscPartial"));
        Path partialDisc1 = Files.createDirectories(partialAlbumRoot.resolve("Disc 1"));
        createFlac(partialDisc1.resolve("01.flac"), "Track 1", "PartialArtist", "MultiDiscPartial", "PartialArtist");
        Files.write(partialDisc1.resolve("cover.jpg"), createImageBytes(300, 300, "jpg"));
        FailedFileHandler.PartialHandlingResult partialRes = failedHandler.handlePartialRecognitionAlbum(partialAlbumRoot.toFile());
        assertEquals(FailedFileHandler.PartialHandlingResult.STORED, partialRes,
            "封面仅存在于 Disc 1 子目录时也必须被识别为有效封面");

        // 4. CueSplitService 支持碟片子目录（CD1/disc1.cue + disc1.flac）并保留专辑根/CD1 结构
        Path cueOutput = Files.createDirectories(root.resolve("cue-out"));
        Path fakeFfmpeg = root.resolve("fake-ffmpeg.sh");
        Files.writeString(fakeFfmpeg, "#!/bin/sh\nfor last; do true; done\nmkdir -p \"$(dirname \"$last\")\"\nprintf 'fLaC' > \"$last\"\n");
        fakeFfmpeg.toFile().setExecutable(true);
        cfg.setCueSplitEnabled(true);
        cfg.setCueSplitOutputDir(cueOutput.toString());
        cfg.setAudioNormalizeFfmpegPath(fakeFfmpeg.toString());

        Path cueAlbumRoot = Files.createDirectories(Path.of(cfg.getMonitorDirectory()).resolve("MultiDiscCueAlbum"));
        Path cueCd1 = Files.createDirectories(cueAlbumRoot.resolve("CD1"));
        Files.write(cueAlbumRoot.resolve("cover.jpg"), createImageBytes(200, 200, "jpg"));
        File bigFlac = createFlac(cueCd1.resolve("disc1.flac"), "Disc1", "Art", "MultiDiscCueAlbum", "Art");
        Files.writeString(cueCd1.resolve("disc1.cue"),
            "PERFORMER \"Art\"\nFILE \"disc1.flac\" WAVE\n  TRACK 01 AUDIO\n    TITLE \"Opening\"\n    INDEX 01 00:00:00\n");

        CueSplitService cueSplit = new CueSplitService(cfg, fsUtils);
        CueSplitService.SplitResult splitRes = cueSplit.trySplit(bigFlac);
        assertTrue(splitRes.isPerformed(), "碟片子目录下的 CUE+大文件应被成功切分");
        assertEquals("CD1", splitRes.getOutputDir().getName(), "切分输出应保留 CD1 子目录层级");
        assertTrue(Files.exists(splitRes.getOutputDir().toPath().resolve("cover.jpg")));
        assertTrue(Files.exists(splitRes.getOutputDir().getParentFile().toPath().resolve("cover.jpg")));

        // 5. FolderAlbumCache 使用相对路径作为样本键时，CD1/01.flac 与 CD2/01.flac 不会互相覆盖
        FolderAlbumCache folderCache = new FolderAlbumCache(new DurationSequenceService(), null, null);
        AlbumBatchProcessor batchProcessor = new AlbumBatchProcessor(cfg, folderCache, tagWriter, logger, coverArtService);
        FolderAlbumCache.AlbumIdentificationInfo sampleInfo = new FolderAlbumCache.AlbumIdentificationInfo(
            "rg-x", "TwoDisc", "Artist", 12, "2024", "album", false, List.of());
        batchProcessor.tryDetermineAlbum(cueAlbumRoot.toString(), "CD1/01.flac", 10, sampleInfo, 10, false);
        batchProcessor.tryDetermineAlbum(cueAlbumRoot.toString(), "CD2/01.flac", 10, sampleInfo, 9, false);
        Field collectorsField = FolderAlbumCache.class.getDeclaredField("folderSampleCollectors");
        collectorsField.setAccessible(true);
        Map<?, ?> collectors = (Map<?, ?>) collectorsField.get(folderCache);
        Object collector = collectors.get(cueAlbumRoot.toString());
        assertNotNull(collector);
        Method getSamples = collector.getClass().getDeclaredMethod("getSamples");
        getSamples.setAccessible(true);
        Map<?, ?> sampleMap = (Map<?, ?>) getSamples.invoke(collector);
        assertEquals(2, sampleMap.size(), "CD1/01.flac 与 CD2/01.flac 应作为两个独立样本保留");
    }

    // ==================== A-2 ====================

    @Test
    void a2_sacdParsingDtwFilteringBeforeRankingAndVersionQualifierWordBoundary() {
        DurationSequenceService dss = new DurationSequenceService();

        // 1. parseMediaFormat 必须先判 SACD 再判 CD
        DurationSequenceService.AlbumDurationInfo cdRelease = new DurationSequenceService.AlbumDurationInfo(
            "rg-1", "rel-cd", "Masterpiece", "Art", List.of(200, 210, 220), "CD");
        DurationSequenceService.AlbumDurationInfo sacdRelease = new DurationSequenceService.AlbumDurationInfo(
            "rg-1", "rel-sacd", "Masterpiece", "Art", List.of(200, 210, 220), "Hybrid SACD");
        DurationSequenceService.AlbumMatchResult sacdMatch = dss.selectBestMatchWithFolderName(
            List.of(200, 210, 220), List.of(cdRelease, sacdRelease), "Masterpiece [SACD]");
        assertNotNull(sacdMatch);
        assertEquals("rel-sacd", sacdMatch.getAlbumInfo().getReleaseId(), "Hybrid SACD 必须优先匹配 [SACD] 目录");

        // 2. 低 DTW + 高名称/格式加分的候选绝不能遮蔽高 DTW 的正确候选
        DurationSequenceService.AlbumDurationInfo badDtwHighName = new DurationSequenceService.AlbumDurationInfo(
            "rg-bad", "rel-bad", "Target Album", "Art", List.of(100, 100, 100), "CD");
        DurationSequenceService.AlbumDurationInfo goodDtwDiffName = new DurationSequenceService.AlbumDurationInfo(
            "rg-good", "rel-good", "Completely Different Japanese Title", "Art", List.of(240, 310, 195), "Digital Media");
        DurationSequenceService.AlbumMatchResult best = dss.selectBestMatchWithFolderName(
            List.of(240, 310, 195), List.of(badDtwHighName, goodDtwDiffName), "Target Album [CD]");
        assertNotNull(best, "高 DTW 候选不应被不合格的低 DTW 候选遮蔽");
        assertEquals("rg-good", best.getAlbumInfo().getReleaseGroupId());

        // 3. MetadataUtils.hasVersionQualifier 使用词边界匹配，避免常见英文词误判
        for (String normalTitle : List.of("01 Alive.flac", "02 Believe.flac", "03 Demon.flac",
                "04 Instead.flac", "05 Credit.flac", "06 Deliver.flac")) {
            assertFalse(MetadataUtils.hasVersionQualifier(normalTitle), "不应误判普通曲名: " + normalTitle);
        }
        for (String versioned : List.of("01 Song (Live).flac", "02 Song (Inst.).flac", "03 Song (Off Vocal).flac",
                "04 千本桜(伴奏).flac", "05 Song - Demo.flac", "06 Song [Instrumental].flac")) {
            assertTrue(MetadataUtils.hasVersionQualifier(versioned), "应识别特殊版本标识: " + versioned);
        }
    }

    // ==================== A-3 ====================

    @Test
    void a3_confidenceDecayRejectRoutingAndWriteWithFolderLockCoverHandling() throws Exception {
        // 1. ConfidenceModel.map 低分向下线性衰减，不再钳位到 0.70/0.55/0.35
        assertEquals(0.0, ConfidenceModel.unify(FolderAlbumCache.CacheSource.DURATION_SEQUENCE, 0.0), 1e-9);
        assertEquals(0.0, ConfidenceModel.unify(FolderAlbumCache.CacheSource.QUICK_SCAN, 0.0), 1e-9);
        assertEquals(0.0, ConfidenceModel.unify(FolderAlbumCache.CacheSource.VOTING, 0.0), 1e-9);
        assertEquals(ConfidenceModel.Decision.REJECT,
            ConfidenceModel.decide(ConfidenceModel.unify(FolderAlbumCache.CacheSource.DURATION_SEQUENCE, 0.0)));
        assertEquals(0.70, ConfidenceModel.unify(FolderAlbumCache.CacheSource.DURATION_SEQUENCE, 0.70), 1e-9);
        assertEquals(0.55, ConfidenceModel.unify(FolderAlbumCache.CacheSource.QUICK_SCAN, 0.90), 1e-9);
        assertEquals(0.35, ConfidenceModel.unify(FolderAlbumCache.CacheSource.VOTING, 0.60), 1e-9);
        assertTrue(ConfidenceModel.unify(FolderAlbumCache.CacheSource.DURATION_SEQUENCE, 0.35) < 0.70);

        // 2. AlbumBatchProcessor 对 Decision.REJECT 路由到 FailedFileHandler 而非直接入库
        Path root = tempDir.resolve("a3");
        MusicConfig cfg = newConfig(root);
        cfg.setReviewEnabled(true);
        cfg.setCoverMissingRequireReview(false);
        ReviewQueueService reviewQueue = new ReviewQueueService(cfg);
        FolderAlbumCache folderCache = new FolderAlbumCache(new DurationSequenceService(), null, null);
        TagWriterService tagWriter = new TagWriterService(cfg);
        ProcessedFileLogger logger = new ProcessedFileLogger(cfg, null);
        CoverArtCache artCache = new CoverArtCache(null, cfg.getCoverArtCacheDirectory(), cfg);
        FileSystemUtils fsUtils = new FileSystemUtils(cfg);

        byte[] officialCover = createImageBytes(400, 400, "jpg");
        byte[] staleEmbeddedCover = createImageBytes(200, 200, "png");
        String rgid = "22222222-3333-4444-5555-666666666666";
        artCache.cacheCoverByReleaseGroupId(rgid, officialCover);
        MusicBrainzClient mbClient = new MusicBrainzClient(cfg) {
            @Override
            public CoverArtResolution resolveCoverArtByReleaseGroupId(String releaseGroupId) {
                return new CoverArtResolution("https://example.com/official.jpg", true);
            }
        };
        CoverArtService coverArtService = new CoverArtService(artCache, mbClient);
        FailedFileHandler failedHandler = new FailedFileHandler(cfg, tagWriter, coverArtService, logger, fsUtils);
        AlbumBatchProcessor batchProcessor = new AlbumBatchProcessor(cfg, folderCache, tagWriter, logger, coverArtService);
        batchProcessor.setReviewQueueService(reviewQueue);
        batchProcessor.setFailedFileHandler(failedHandler);

        Path rejectDir = Files.createDirectories(Path.of(cfg.getMonitorDirectory()).resolve("RejectMe"));
        File rejectFlac = createFlac(rejectDir.resolve("01.flac"), null, null, null, null);
        MusicMetadata emptyMd = new MusicMetadata(); // title=null, artist=null -> 0 usable -> Decision.REJECT
        batchProcessor.addPendingFile(rejectDir.toString(), rejectFlac, rejectFlac, null, emptyMd, null);
        batchProcessor.processPendingFilesAsUnresolvedAlbum(rejectDir.toString());
        assertEquals(0, Files.list(Path.of(cfg.getOutputDirectory())).count(), "Decision.REJECT 不得写入 outputDirectory");
        assertTrue(Files.isDirectory(Path.of(cfg.getFailedDirectory()).resolve("RejectMe")),
            "Decision.REJECT 应路由到 failedDirectory");

        // 3. writeWithFolderLock 冲刷积压曲目时，绝不把当前曲目的旧封面当作 explicitCoverArt 覆盖官方 CAA 封面
        Path lockedDir = Files.createDirectories(Path.of(cfg.getMonitorDirectory()).resolve("LockedAlbum"));
        File pendingFlac = createFlac(lockedDir.resolve("01 Pending.flac"), "Pending", "Art", "LockedAlbum", "Art");
        File currentFlac = createFlac(lockedDir.resolve("02 Current.flac"), "Current", "Art", "LockedAlbum", "Art");

        MusicMetadata pendingMd = new MusicMetadata();
        pendingMd.setRecordingId("rec-pending");
        pendingMd.setTitle("Pending");
        pendingMd.setArtist("Art");
        pendingMd.setAlbum("LockedAlbum");
        pendingMd.setAlbumArtist("Art");
        pendingMd.setReleaseGroupId(rgid);
        pendingMd.setTrackNo("1");
        batchProcessor.addPendingFile(lockedDir.toString(), pendingFlac, pendingFlac, null, pendingMd, null);

        FolderAlbumCache.CachedAlbumInfo lockedInfo = new FolderAlbumCache.CachedAlbumInfo(
            rgid, "rel-locked", "LockedAlbum", "Art", 2, "2024-01-01", 1.0, FolderAlbumCache.CacheSource.DURATION_SEQUENCE);
        folderCache.setFolderAlbum(lockedDir.toString(), lockedInfo);

        MusicMetadata currentMd = new MusicMetadata();
        currentMd.setRecordingId("rec-current");
        currentMd.setTitle("Current");
        currentMd.setArtist("Art");
        currentMd.setAlbum("LockedAlbum");
        currentMd.setAlbumArtist("Art");
        currentMd.setReleaseGroupId(rgid);
        currentMd.setTrackNo("2");

        int flushed = batchProcessor.writeWithFolderLock(
            lockedDir.toString(), currentFlac, currentFlac, currentMd, staleEmbeddedCover, false);
        assertEquals(1, flushed);
        Path archivedPending = Path.of(cfg.getOutputDirectory()).resolve("Art/LockedAlbum/01. Art - Pending.flac");
        assertTrue(Files.isRegularFile(archivedPending));
        byte[] writtenArt = AudioFileIO.read(archivedPending.toFile()).getTag().getFirstArtwork().getBinaryData();
        assertArrayEquals(officialCover, writtenArt, "积压曲目应采用官方 CAA/缓存封面而非被单曲旧封面污染");
    }

    // ==================== A-4 ====================

    @Test
    void a4_trackPrefixPermissionsFlacLongNameAtomicWritesAndNormalizerDoesNotHoldGuard() throws Exception {
        // 1. extractTrackPrefix 修复扩展名误截与越界
        assertEquals("", TagWriterService.extractTrackPrefix("01.flac"));
        assertEquals("", TagWriterService.extractTrackPrefix("01."));
        assertEquals("", TagWriterService.extractTrackPrefix("01. "));
        assertEquals("01. ", TagWriterService.extractTrackPrefix("01.Title.flac"));
        assertEquals("01. ", TagWriterService.extractTrackPrefix("01. Title.flac"));
        assertEquals("2.01 ", TagWriterService.extractTrackPrefix("2.01 Title.flac"));
        assertEquals("", TagWriterService.extractTrackPrefix("2.01.flac"));

        Path root = tempDir.resolve("a4");
        MusicConfig cfg = newConfig(root);
        TagWriterService tagWriter = new TagWriterService(cfg);

        // 2. 默认权限收紧为 0755 / 0644（仅在未显式配置环境覆盖时验证）
        File src = createFlac(root.resolve("monitor/PermAlb/01.flac"), "Song", "PermArt", "PermAlb", "PermArt");
        MusicMetadata md = new MusicMetadata();
        md.setTitle("Song");
        md.setArtist("PermArt");
        md.setAlbum("PermAlb");
        md.setAlbumArtist("PermArt");
        md.setTrackNo("1");
        TagWriterService.TagProcessResult written = tagWriter.processFileWithResult(src, md, null);
        assertTrue(written.isSuccess());
        if (System.getenv("MTG_FILE_MODE") == null && System.getenv("MTG_DIR_MODE") == null
                && !"false".equalsIgnoreCase(System.getenv("MTG_CHMOD"))) {
            Set<PosixFilePermission> filePerms = Files.getPosixFilePermissions(written.getTargetFile().toPath());
            Set<PosixFilePermission> dirPerms = Files.getPosixFilePermissions(written.getTargetFile().getParentFile().toPath());
            assertEquals(PosixFilePermissions.fromString("rw-r--r--"), filePerms);
            assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), dirPerms);
        }

        // 3. FlacId3Stripper 对超长文件名（230 字节）使用短前缀临时文件，避免 ENAMETOOLONG
        String longName = "a".repeat(225) + ".flac";
        Path longFlac = root.resolve(longName);
        byte[] cleanFlacBytes = Files.readAllBytes(src.toPath());
        byte[] withId3 = new byte[10 + cleanFlacBytes.length];
        withId3[0] = 'I'; withId3[1] = 'D'; withId3[2] = '3'; withId3[3] = 3; withId3[4] = 0;
        System.arraycopy(cleanFlacBytes, 0, withId3, 10, cleanFlacBytes.length);
        Files.write(longFlac, withId3);
        assertEquals(FlacId3Stripper.Result.STRIPPED, FlacId3Stripper.stripId3FromFlac(longFlac));

        // 4. updateTagsOnExistingFile / writeLocalArchiveTags / embedFolderCover 原子写入不留临时文件
        File target = createFlac(root.resolve("atomic/test.flac"), "Old", "OldArt", "OldAlb", "OldArt");
        md.setTitle("Updated");
        assertTrue(tagWriter.updateTagsOnExistingFile(target, md, null));
        assertEquals("Updated", AudioFileIO.read(target).getTag().getFirst(FieldKey.TITLE));
        tagWriter.writeLocalArchiveTags(target.toPath(), "NewAlb", "NewArt", List.of("NewArt"), null, false);
        assertEquals("NewAlb", AudioFileIO.read(target).getTag().getFirst(FieldKey.ALBUM));
        assertTrue(tagWriter.embedFolderCover(target, createImageBytes(200, 200, "jpg")));
        assertTrue(tagWriter.hasEmbeddedCover(target));
        try (var stream = Files.list(target.getParentFile().toPath())) {
            assertFalse(stream.anyMatch(p -> p.getFileName().toString().startsWith(".")), "原子写入后不应残留隐藏临时文件");
        }

        // 5. AudioFormatNormalizer.normalizeToTargetIfNeeded 执行 ffmpeg 期间不得持有 LibraryWriteGuard
        Path hiResFlac = createFlacWithSampleRate(
            root.resolve("monitor/hires.flac"), "HiRes", "Art", "Alb", "Art", 96000, 24).toPath();
        Path markerStarted = root.resolve("ffmpeg-started");
        Path markerRelease = root.resolve("ffmpeg-release");
        Path slowFfmpeg = root.resolve("slow-ffmpeg.sh");
        Files.writeString(slowFfmpeg,
            "#!/bin/sh\n"
                + "touch \"" + markerStarted + "\"\n"
                + "while [ ! -f \"" + markerRelease + "\" ]; do sleep 0.02; done\n"
                + "for last; do true; done\n"
                + "cp \"" + hiResFlac + "\" \"$last\"\n");
        slowFfmpeg.toFile().setExecutable(true);

        cfg.setAudioNormalizeEnabled(true);
        cfg.setAudioNormalizeFfmpegPath(slowFfmpeg.toString());
        AudioFormatNormalizer normalizer = new AudioFormatNormalizer(cfg);
        Path targetOut = root.resolve("partial/hires.flac");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> normFuture = pool.submit(() -> normalizer.normalizeToTargetIfNeeded(hiResFlac.toFile(), targetOut.toFile()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(markerStarted) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(Files.exists(markerStarted), "fake ffmpeg 应已启动");

            CountDownLatch guardAcquired = new CountDownLatch(1);
            Thread probe = new Thread(() -> {
                try (var guard = LibraryWriteGuard.acquire()) {
                    guardAcquired.countDown();
                }
            });
            probe.start();
            assertTrue(guardAcquired.await(2, TimeUnit.SECONDS),
                "ffmpeg 运行期间必须能立即获取 LibraryWriteGuard，不能持全局写锁阻塞");
            probe.join(2000);

            Files.writeString(markerRelease, "go");
            assertTrue(normFuture.get(5, TimeUnit.SECONDS));
            assertTrue(Files.isRegularFile(targetOut));
        } finally {
            Files.writeString(markerRelease, "go");
            pool.shutdownNow();
        }
    }

    // ==================== A-5 ====================

    @Test
    void a5_oggUppercaseSupportSymlinkSafeDeleteRelativeFailedPathsAndLuceneJoinphrase() throws Exception {
        Path root = tempDir.resolve("a5");
        MusicConfig cfg = newConfig(root);
        FileSystemUtils fsUtils = new FileSystemUtils(cfg);

        // 1. .ogg 与大写后缀均被识别
        assertTrue(fsUtils.isMusicFile(new File("track.ogg")));
        assertTrue(fsUtils.isMusicFile(new File("TRACK.OGG")));
        assertTrue(fsUtils.isMusicFile(new File("TRACK.FLAC")));
        assertTrue(FileSystemUtils.isDefaultAudioFile(new File("SONG.OGG")));
        assertTrue(FileSystemUtils.isDefaultAudioFile(new File("SONG.FLAC")));

        // 2. FailedFileHandler.deleteDirectory 不跟随目录软链接
        TagWriterService tagWriter = new TagWriterService(cfg);
        CoverArtCache artCache = new CoverArtCache(null, cfg.getCoverArtCacheDirectory(), cfg);
        MusicBrainzClient mbClient = new MusicBrainzClient(cfg);
        CoverArtService coverArtService = new CoverArtService(artCache, mbClient);
        ProcessedFileLogger logger = new ProcessedFileLogger(cfg, null);
        FailedFileHandler failedHandler = new FailedFileHandler(cfg, tagWriter, coverArtService, logger, fsUtils);

        Path externalDir = Files.createDirectories(root.resolve("external-precious"));
        Path preciousFile = Files.writeString(externalDir.resolve("keep.txt"), "do-not-delete");
        Path victimDir = Files.createDirectories(root.resolve("to-delete"));
        Path symlink = victimDir.resolve("link-to-external");
        Files.createSymbolicLink(symlink, externalDir);
        failedHandler.deleteDirectory(victimDir.toFile());
        assertFalse(Files.exists(victimDir));
        assertTrue(Files.isRegularFile(preciousFile), "删除含目录软链接的目录时绝不能删掉软链接指向的外部文件");

        // 3. failedDirectory 保留相对路径，避免不同艺人同名专辑（Greatest Hits）碰撞丢弃，且 RecoveryService 分别列出
        Path monA = Files.createDirectories(Path.of(cfg.getMonitorDirectory()).resolve("ArtistA/Greatest Hits"));
        Path monB = Files.createDirectories(Path.of(cfg.getMonitorDirectory()).resolve("ArtistB/Greatest Hits"));
        createFlac(monA.resolve("01 A.flac"), "A", "ArtistA", "Greatest Hits", "ArtistA");
        createFlac(monB.resolve("01 B.flac"), "B", "ArtistB", "Greatest Hits", "ArtistB");
        failedHandler.copyFailedFolderToFailedDirectory(monA.toFile());
        failedHandler.copyFailedFolderToFailedDirectory(monB.toFile());
        assertTrue(Files.isRegularFile(Path.of(cfg.getFailedDirectory()).resolve("ArtistA/Greatest Hits/01 A.flac")));
        assertTrue(Files.isRegularFile(Path.of(cfg.getFailedDirectory()).resolve("ArtistB/Greatest Hits/01 B.flac")));

        ReviewQueueService reviewQueue = new ReviewQueueService(cfg);
        FolderAlbumCache folderCache = new FolderAlbumCache(new DurationSequenceService(), null, null);
        CoverCandidateService candidateService = new CoverCandidateService(cfg, mbClient, coverArtService, artCache);
        RecoveryService recoveryService = new RecoveryService(
            cfg, null, logger, reviewQueue, folderCache, failedHandler, fsUtils, tagWriter, null, candidateService);
        List<Map<String, Object>> listed = recoveryService.listItems();
        assertEquals(2, listed.size(), "RecoveryService 必须分别列出 ArtistA/Greatest Hits 与 ArtistB/Greatest Hits");
        Set<Object> relPaths = Set.of(listed.get(0).get("relativePath"), listed.get(1).get("relativePath"));
        assertEquals(Set.of("ArtistA/Greatest Hits", "ArtistB/Greatest Hits"), relPaths);

        // 4. Lucene 转义 & 保留末尾 joinphrase
        assertEquals("\\\"Heroes\\\" \\\\ 1 2", MusicBrainzClient.escapeLucenePhrase("\"Heroes\" \\ 1\n2"));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode creditsWithTrailing = mapper.readTree(
            "[{\"name\":\"Artist A\",\"joinphrase\":\" (feat. \"},{\"name\":\"Artist B\",\"joinphrase\":\")\"}]");
        assertEquals("Artist A (feat. Artist B)", MusicBrainzClient.resolveCreditedArtistText(creditsWithTrailing));
        cfg.setCollapseAlbumArtistToVariousArtists(false);
        AlbumArtistPolicy policy = new AlbumArtistPolicy(cfg);
        assertEquals("Artist A (feat. Artist B)", policy.resolveFromCredits(creditsWithTrailing));

        JsonNode singleWithTrailing = mapper.readTree("[{\"name\":\"Solo\",\"joinphrase\":\"!\"}]");
        assertEquals("Solo!", policy.resolveFromCredits(singleWithTrailing));
    }
}
