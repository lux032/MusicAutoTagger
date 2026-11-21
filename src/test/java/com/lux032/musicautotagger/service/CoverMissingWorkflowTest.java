package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.llm.LlmClient;
import com.lux032.musicautotagger.service.llm.WebSearchClient;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CoverMissingWorkflowTest {

    @TempDir Path tempDir;

    private MusicConfig config;
    private Path monitorDir;
    private Path outputDir;
    private Path coverCacheDir;
    private ReviewQueueService reviewQueue;
    private FolderAlbumCache folderAlbumCache;
    private TagWriterService tagWriter;
    private ProcessedFileLogger processedLogger;
    private CoverArtCache coverArtCache;
    private CoverArtService coverArtService;
    private CoverCandidateService coverCandidateService;
    private AlbumBatchProcessor batchProcessor;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        config = constructor.newInstance();
        monitorDir = Files.createDirectories(tempDir.resolve("monitor"));
        outputDir = Files.createDirectories(tempDir.resolve("output"));
        coverCacheDir = Files.createDirectories(tempDir.resolve("cover-cache"));
        config.setMonitorDirectory(monitorDir.toString());
        config.setOutputDirectory(outputDir.toString());
        config.setCoverArtCacheDirectory(coverCacheDir.toString());
        config.setFailedDirectory(tempDir.resolve("failed").toString());
        config.setPartialDirectory(tempDir.resolve("partial").toString());
        config.setReviewQueuePath(tempDir.resolve("data/review-queue.json").toString());
        config.setReviewStagingDirectory(tempDir.resolve("data/review-staging").toString());
        config.setRecoveryWorkDirectory(tempDir.resolve("data/recovery-work").toString());
        config.setRecoveryTrashDirectory(tempDir.resolve("data/recovery-trash").toString());
        config.setProcessedFileLogPath(tempDir.resolve("processed.log").toString());
        config.setAutoRename(true);
        config.setExportLyricsToFile(false);
        config.setCoverMissingRequireReview(true);
        config.setCoverMissingAutoOnlineSearch(true);

        reviewQueue = new ReviewQueueService(config);
        folderAlbumCache = new FolderAlbumCache(new DurationSequenceService(), null, null);
        tagWriter = new TagWriterService(config);
        processedLogger = new ProcessedFileLogger(config, null);
        coverArtCache = new CoverArtCache(null, coverCacheDir.toString(), config);
        MusicBrainzClient noCoverMbClient = new MusicBrainzClient(config) {
            @Override
            public CoverArtResolution resolveCoverArtByReleaseGroupId(String releaseGroupId) {
                return new CoverArtResolution(null, false);
            }
        };
        coverArtService = new CoverArtService(coverArtCache, noCoverMbClient);
        coverCandidateService = new CoverCandidateService(config, noCoverMbClient, coverArtService, coverArtCache);
        batchProcessor = new AlbumBatchProcessor(config, folderAlbumCache, tagWriter, processedLogger, coverArtService);
        batchProcessor.setReviewQueueService(reviewQueue);
    }

    @Test
    void uploadedOversizedPngIsCompressedToJpegWithinProjectRules() throws Exception {
        byte[] oversizedPng = createImageBytes(1800, 1800, "png");
        Optional<CoverCandidateView> viewOpt = coverCandidateService.ingestUploadedBytes(oversizedPng, "bonus_jacket.png");
        assertTrue(viewOpt.isPresent());
        CoverCandidateView view = viewOpt.get();
        assertEquals(CoverCandidateView.Origin.USER_UPLOAD, view.getOrigin());
        assertEquals("image/jpeg", view.getMime());
        assertTrue(view.getWidth() <= 1200 && view.getHeight() <= 1200, "compressed dimensions should be <= 1200");
        assertTrue(view.getBytes() > 0 && view.getBytes() <= 2 * 1024 * 1024, "compressed bytes should be <= 2MB");
    }

    @Test
    void coverArtServiceDetectsJapaneseNamesAndUniqueSingleImage() throws Exception {
        Path jacketDir = Files.createDirectory(tempDir.resolve("album-jacket"));
        Path jacket = Files.write(jacketDir.resolve("jacket.jpg"), createImageBytes(300, 300, "jpg"));
        assertEquals(jacket.toFile(), coverArtService.findCoverFileInDirectory(jacketDir.toFile()));

        Path singleImgDir = Files.createDirectory(tempDir.resolve("album-single"));
        Path unique = Files.write(singleImgDir.resolve("超かぐや姫特典CD.jpg"), createImageBytes(300, 300, "jpg"));
        assertEquals(unique.toFile(), coverArtService.findCoverFileInDirectory(singleImgDir.toFile()));

        // Multiple unrelated images must NOT be guessed
        Files.write(singleImgDir.resolve("extra_scan.jpg"), createImageBytes(300, 300, "jpg"));
        assertNull(coverArtService.findCoverFileInDirectory(singleImgDir.toFile()));
    }

    @Test
    void batchProcessorHoldsAlbumMissingCoverAndIsolatesCountsFromReviewQueue() throws Exception {
        Path albumDir = Files.createDirectory(monitorDir.resolve("BonusCD"));
        File flac = createFlac(albumDir.resolve("01 Remember.flac"), "Remember", "yuigot", "「超かぐや姫！」特典CD", "Various Artists", null);

        AtomicInteger autoSearchCalls = new AtomicInteger();
        reviewQueue.setMissingCoverEnqueuedListener(item -> autoSearchCalls.incrementAndGet());

        MusicMetadata md = new MusicMetadata();
        md.setRecordingId("rec-1");
        md.setTitle("Remember");
        md.setArtist("yuigot");
        md.setAlbum("「超かぐや姫！」特典CD");
        md.setAlbumArtist("Various Artists");
        md.setReleaseGroupId("rg-bonus-cd");
        md.setTrackNo("1");

        batchProcessor.addPendingFile(albumDir.toString(), flac, flac, null, md, null);
        FolderAlbumCache.CachedAlbumInfo albumInfo = new FolderAlbumCache.CachedAlbumInfo(
            "rg-bonus-cd", "rel-bonus-cd", "「超かぐや姫！」特典CD", "Various Artists",
            1, "2026-11-21", 1.0, FolderAlbumCache.CacheSource.DURATION_SEQUENCE);

        AlbumBatchProcessor.BatchProcessResult result =
            batchProcessor.processPendingFilesWithAlbum(albumDir.toString(), albumInfo, false);

        assertTrue(result.isQueuedForMissingCover(), "Missing cover must intercept instead of archiving");
        assertNotNull(result.getQueuedReviewItemId());
        assertEquals(0, Files.list(outputDir).count(), "Output library must remain untouched while waiting for cover");
        assertEquals(1, reviewQueue.countPendingMissingCover());
        assertEquals(0, reviewQueue.countPendingNonCover(), "Missing cover count must not pollute 待确认");
        assertEquals(1, autoSearchCalls.get(), "Auto cover search listener should fire for held import");
        assertTrue(reviewQueue.isFolderUnderReview(albumDir.toString()));

        // Upload compressed cover and apply -> should archive WITH embedded cover & populate cover cache!
        try (CoverMissingService service = new CoverMissingService(
                config, reviewQueue, folderAlbumCache, batchProcessor, tagWriter, coverCandidateService, processedLogger)) {
            CoverCandidateView uploaded = service.uploadCover(
                result.getQueuedReviewItemId(), createImageBytes(1600, 1600, "png"), "cover.png");
            assertTrue(uploaded.getWidth() <= 1200);

            ReviewItem resolved = service.applyCover(
                result.getQueuedReviewItemId(), uploaded.getSha256(), RecoveryService.DuplicatePolicy.ASK);
            assertEquals(ReviewItem.Status.CONFIRMED, resolved.getStatus());
            assertFalse(resolved.getMissingCover().isConfirmedNoCover());
        }

        Path archived = outputDir.resolve("Various Artists/「超かぐや姫！」特典CD/01. yuigot - Remember.flac");
        assertTrue(Files.isRegularFile(archived), "File should be archived after cover is supplied");
        assertTrue(tagWriter.hasEmbeddedCover(archived.toFile()), "Archived FLAC must contain embedded cover");
        assertNotNull(coverArtCache.getCachedCoverByReleaseGroupId("rg-bonus-cd"), "Cover cache should be populated");
        assertEquals(0, reviewQueue.countPendingMissingCover());
    }

    @Test
    void confirmNoCoverArchivesWithoutCoverAndMarksDecision() throws Exception {
        Path albumDir = Files.createDirectory(monitorDir.resolve("NoCoverAlbum"));
        File flac = createFlac(albumDir.resolve("01 Track.flac"), "Track", "Artist", "NoCoverAlbum", "Artist", null);

        MusicMetadata md = new MusicMetadata();
        md.setRecordingId("rec-2");
        md.setTitle("Track");
        md.setArtist("Artist");
        md.setAlbum("NoCoverAlbum");
        md.setAlbumArtist("Artist");
        md.setTrackNo("1");

        batchProcessor.addPendingFile(albumDir.toString(), flac, flac, null, md, null);
        FolderAlbumCache.CachedAlbumInfo albumInfo = new FolderAlbumCache.CachedAlbumInfo(
            "rg-nocover", "rel-nocover", "NoCoverAlbum", "Artist",
            1, "2026-01-01", 1.0, FolderAlbumCache.CacheSource.DURATION_SEQUENCE);
        AlbumBatchProcessor.BatchProcessResult intercepted =
            batchProcessor.processPendingFilesWithAlbum(albumDir.toString(), albumInfo, false);
        assertTrue(intercepted.isQueuedForMissingCover());

        try (CoverMissingService service = new CoverMissingService(
                config, reviewQueue, folderAlbumCache, batchProcessor, tagWriter, coverCandidateService, processedLogger)) {
            ReviewItem resolved = service.confirmNoCover(intercepted.getQueuedReviewItemId(), RecoveryService.DuplicatePolicy.ASK);
            assertEquals(ReviewItem.Status.CONFIRMED, resolved.getStatus());
            assertTrue(resolved.getMissingCover().isConfirmedNoCover());
        }

        Path archived = outputDir.resolve("Artist/NoCoverAlbum/01. Artist - Track.flac");
        assertTrue(Files.isRegularFile(archived));
        assertFalse(tagWriter.hasEmbeddedCover(archived.toFile()));
    }

    @Test
    void manualLibraryScanRunsOnlyOnTriggerAndSupportsInPlaceEmbedAndAckSkip() throws Exception {
        // Album 1: archived without cover (will be patched in-place)
        Path missingDir = Files.createDirectories(outputDir.resolve("ArtistA/MissingOne"));
        File missingFlac = createFlac(missingDir.resolve("01. Song.flac"), "Song", "ArtistA", "MissingOne", "ArtistA", null);

        // Album 2: archived without cover (user will confirm no-cover)
        Path ackDir = Files.createDirectories(outputDir.resolve("ArtistB/AckNoCover"));
        File ackFlac = createFlac(ackDir.resolve("01. Song2.flac"), "Song2", "ArtistB", "AckNoCover", "ArtistB", null);

        // Album 3: already has embedded cover (must be skipped)
        Path okDir = Files.createDirectories(outputDir.resolve("ArtistC/HasCover"));
        File okFlac = createFlac(okDir.resolve("01. Song3.flac"), "Song3", "ArtistC", "HasCover", "ArtistC", null);
        assertTrue(tagWriter.embedCoverAtomically(okFlac, ImageCompressor.compressImage(createImageBytes(400, 400, "jpg"))));

        try (CoverMissingService service = new CoverMissingService(
                config, reviewQueue, folderAlbumCache, batchProcessor, tagWriter, coverCandidateService, processedLogger)) {
            // Must NOT auto-scan before user triggers it!
            assertFalse(service.isLibraryScanRunning());
            assertEquals(0, reviewQueue.countPendingMissingCover());

            // Manual trigger #1
            assertTrue(service.startLibraryScan());
            Map<String, Object> status = awaitScan(service);
            assertEquals(3, status.get("scannedAlbums"));
            assertEquals(2, status.get("foundMissing"));
            assertEquals(2, reviewQueue.countPendingMissingCover());

            List<ReviewItem> items = reviewQueue.listByKinds(ReviewItem.Status.PENDING_REVIEW, Set.of(ReviewItem.Kind.COVER_MISSING));
            ReviewItem item1 = items.stream().filter(i -> "MissingOne".equals(i.getMissingCover().getAlbumTitle())).findFirst().orElseThrow();
            ReviewItem item2 = items.stream().filter(i -> "AckNoCover".equals(i.getMissingCover().getAlbumTitle())).findFirst().orElseThrow();
            assertEquals(ReviewItem.MissingCover.Mode.LIBRARY_EXISTING, item1.getMissingCover().getMode());

            // In-place embed on item1
            CoverCandidateView uploaded = service.uploadCover(item1.getId(), createImageBytes(1400, 1400, "jpg"), "front.jpg");
            service.applyCover(item1.getId(), uploaded.getSha256(), RecoveryService.DuplicatePolicy.ASK);
            assertTrue(tagWriter.hasEmbeddedCover(missingFlac), "Library file should now have embedded cover");

            // Confirm no-cover on item2
            service.confirmNoCover(item2.getId(), RecoveryService.DuplicatePolicy.ASK);
            assertFalse(tagWriter.hasEmbeddedCover(ackFlac));

            // Manual trigger #2: Album 1 now has cover, Album 2 is acknowledged -> 0 new missing!
            assertTrue(service.startLibraryScan());
            Map<String, Object> status2 = awaitScan(service);
            assertEquals(0, status2.get("foundMissing"));
            assertEquals(1, status2.get("skippedAcknowledged"));
            assertEquals(0, reviewQueue.countPendingMissingCover());
        }
    }

    @Test
    void coverSearchServiceRunsAiSearchWithoutAutoArchiving() throws Exception {
        Path albumDir = Files.createDirectory(monitorDir.resolve("AiSearchAlbum"));
        File flac = createFlac(albumDir.resolve("01. Track.flac"), "Track", "Artist", "AiSearchAlbum", "Artist", null);

        ReviewItem.MissingCover details = new ReviewItem.MissingCover();
        details.setMode(ReviewItem.MissingCover.Mode.STAGED_IMPORT);
        details.setAlbumTitle("AiSearchAlbum");
        details.setAlbumArtist("Artist");
        FolderAlbumCache.PendingFile pf = new FolderAlbumCache.PendingFile(flac, flac, null, new MusicMetadata(), null);
        ReviewItem item = reviewQueue.enqueueMissingCover(albumDir.toString(), List.of(pf), details, "test");

        WebSearchClient stubSearch = new WebSearchClient() {
            @Override public boolean hasEnabledEndpoint() { return true; }
            @Override public String name() { return "tavily"; }
            @Override public SearchResponse search(String s, String u) { return searchImages(s, u, null); }
            @Override public SearchResponse searchImages(String systemPrompt, String userPrompt, ProgressListener progress) {
                assertTrue(userPrompt.contains("AiSearchAlbum"));
                SearchResponse resp = new SearchResponse();
                resp.setText("{\"cover_urls\":[],\"search_notes\":\"checked official pages\"}");
                return resp;
            }
        };

        CoverSearchService searchService = new CoverSearchService(config, reviewQueue, coverCandidateService, stubSearch);
        ReviewItem after = searchService.searchCoversForItem(item.getId(), "bonus edition", null);
        assertNotNull(after);
        assertEquals(ReviewItem.Status.PENDING_REVIEW, after.getStatus(), "AI cover search must NEVER auto-archive");
        assertEquals("DONE", after.getMissingCover().getCoverSearchStatus());
        assertTrue(after.getMissingCover().getCoverSearchedAt() > 0L);
        assertEquals("bonus edition", after.getMissingCover().getCustomSearchQuery());
    }

    private Map<String, Object> awaitScan(CoverMissingService service) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (service.isLibraryScanRunning() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(service.isLibraryScanRunning(), "Library scan should finish promptly");
        return service.getScanStatus();
    }

    private static byte[] createImageBytes(int width, int height, String format) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(40, 120, 200));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        g.fillOval(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    private static File createFlac(Path target, String title, String artist, String album,
                                   String albumArtist, String rgid) throws Exception {
        Files.createDirectories(target.getParent());
        ByteBuffer bytes = ByteBuffer.allocate(554);
        bytes.put(new byte[]{'f', 'L', 'a', 'C', (byte) 128, 0, 0, 34});
        bytes.putLong(18, (44100L << 44) | (1L << 41) | (15L << 36) | 5303025L);
        for (int i = 42; i < bytes.capacity(); i++) bytes.put(i, (byte) (i * 7));
        Files.write(target, bytes.array());
        var audio = AudioFileIO.read(target.toFile());
        Tag tag = audio.getTagOrCreateAndSetDefault();
        tag.setField(FieldKey.TITLE, title);
        tag.setField(FieldKey.ARTIST, artist);
        tag.setField(FieldKey.ALBUM, album);
        tag.setField(FieldKey.ALBUM_ARTIST, albumArtist);
        tag.setField(FieldKey.TRACK, "1");
        if (rgid != null) tag.setField(FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID, rgid);
        audio.commit();
        return target.toFile();
    }
}
