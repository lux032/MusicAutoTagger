package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ProcessedRecord;
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
import java.util.ArrayList;
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

    @Test
    void votingLockOnThirdTrackCollectsAllTwelveTracksAndReplacesAllOldLibraryFiles() throws Exception {
        ReidentifyReplacementRegistry replacementRegistry =
            ReidentifyReplacementRegistry.forReviewQueuePath(config.getReviewQueuePath());
        AlbumBatchProcessor batchWithReplace = new AlbumBatchProcessor(
            config, folderAlbumCache, tagWriter, processedLogger, coverArtService, null, replacementRegistry);
        batchWithReplace.setReviewQueueService(reviewQueue);

        Path reprocessTask = Path.of(config.getReviewStagingDirectory()).resolve("reprocess-abc");
        Path stagedAlbumDir = reprocessTask.resolve("BonusCD");
        Path oldAlbumDir = outputDir.resolve("OldArtist").resolve("OldBonusCD");
        List<File> stagedFiles = new ArrayList<>();
        List<Path> oldTargets = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            String name = String.format("%02d Track %d.flac", i, i);
            File staged = createFlac(stagedAlbumDir.resolve(name), "Track " + i, "Artist " + i,
                "Bonus CD", "Various Artists", "rg-bonus-12");
            File oldLib = createFlac(oldAlbumDir.resolve(name), "Track " + i, "Artist " + i,
                "OldBonusCD", "OldArtist", "rg-bonus-12");
            stagedFiles.add(staged);
            oldTargets.add(oldLib.toPath());
            replacementRegistry.record(staged.getAbsolutePath(), oldLib.getAbsolutePath());
        }

        String folderPath = stagedAlbumDir.toFile().getAbsolutePath();
        FolderAlbumCache.CachedAlbumInfo lockedAlbum = new FolderAlbumCache.CachedAlbumInfo(
            "rg-bonus-12", "rel-bonus-12", "Bonus CD", "Various Artists",
            12, "2026", "Album", true, 0.95, FolderAlbumCache.CacheSource.DURATION_SEQUENCE);
        folderAlbumCache.forceSetFolderAlbum(folderPath, lockedAlbum);

        // First 3 tracks trigger voting lock with allFilesCollected=false -> must NOT prematurely enqueue only 3 tracks
        for (int i = 0; i < 3; i++) {
            File f = stagedFiles.get(i);
            MusicMetadata md = new MusicMetadata();
            md.setTitle("Track " + (i + 1));
            md.setArtist("Artist " + (i + 1));
            md.setAlbum("Bonus CD");
            md.setAlbumArtist("Various Artists");
            md.setTrackNo(String.valueOf(i + 1));
            md.setTrackCount(12);
            md.setRecordingId("rec-" + (i + 1));
            md.setReleaseGroupId("rg-bonus-12");
            batchWithReplace.addPendingFile(folderPath, f, f, null, md, null);
        }

        AlbumBatchProcessor.BatchProcessResult partialResult =
            batchWithReplace.processPendingFilesWithAlbum(folderPath, lockedAlbum, false, null, true, false);
        assertTrue(partialResult.isDeferringForMissingCover(),
            "Must defer queueing when only 3/12 tracks are collected");
        assertEquals(0, reviewQueue.countPendingMissingCover(),
            "Must NOT prematurely enqueue only 3 tracks");
        assertTrue(folderAlbumCache.isFolderCoverMissing(folderPath));
        assertEquals(3, batchWithReplace.getPendingFileCount(folderPath));

        // Remaining tracks 4..12 arrive
        for (int i = 3; i < 12; i++) {
            File f = stagedFiles.get(i);
            MusicMetadata md = new MusicMetadata();
            md.setTitle("Track " + (i + 1));
            md.setArtist("Artist " + (i + 1));
            md.setAlbum("Bonus CD");
            md.setAlbumArtist("Various Artists");
            md.setTrackNo(String.valueOf(i + 1));
            md.setTrackCount(12);
            md.setRecordingId("rec-" + (i + 1));
            md.setReleaseGroupId("rg-bonus-12");
            batchWithReplace.addPendingFile(folderPath, f, f, null, md, null);
        }

        // All 12 collected -> finalize enqueues complete 12-track COVER_MISSING item
        AlbumBatchProcessor.BatchProcessResult finalQueueResult =
            batchWithReplace.processPendingFilesAsUnresolvedAlbum(folderPath);
        assertTrue(finalQueueResult.isQueuedForMissingCover());
        assertEquals(1, reviewQueue.countPendingMissingCover());

        ReviewItem queued = reviewQueue.listByKinds(ReviewItem.Status.PENDING_REVIEW,
            Set.of(ReviewItem.Kind.COVER_MISSING)).get(0);
        assertEquals(12, queued.getFiles().size(), "Queued COVER_MISSING item must contain all 12 tracks");
        queued.setStagingDir(folderPath);
        reviewQueue.update(queued);

        // Upload cover & apply -> all 12 archived with cover, all 12 old library files deleted,
        // temporary reprocess-* dir cleaned, and processed records migrated to final output paths
        try (CoverMissingService service = new CoverMissingService(
                config, reviewQueue, folderAlbumCache, batchWithReplace, tagWriter, coverCandidateService, processedLogger)) {
            CoverCandidateView uploaded = service.uploadCover(queued.getId(), createImageBytes(800, 800, "png"), "cover.png");
            ReviewItem resolved = service.applyCover(
                queued.getId(), uploaded.getSha256(), RecoveryService.DuplicatePolicy.ASK);
            assertEquals(ReviewItem.Status.CONFIRMED, resolved.getStatus());
        }

        Path newAlbumDir = outputDir.resolve("Various Artists").resolve("Bonus CD");
        try (var s = Files.list(newAlbumDir)) {
            List<Path> written = s.filter(p -> p.toString().endsWith(".flac")).toList();
            assertEquals(12, written.size(), "All 12 tracks must be archived to new album directory");
            for (Path p : written) {
                assertNotNull(AudioFileIO.read(p.toFile()).getTag().getFirstArtwork(),
                    "Every archived track must have the embedded cover");
            }
        }
        for (Path oldTarget : oldTargets) {
            assertFalse(Files.exists(oldTarget), "Old coverless library file must be deleted: " + oldTarget);
        }
        assertFalse(Files.exists(oldAlbumDir), "Empty old album directory must be removed");
        assertFalse(Files.exists(reprocessTask), "Temporary reprocess-* staging dir must be cleaned");
        assertTrue(processedLogger.findRecordsByPaths(List.of(stagedFiles.get(0).getAbsolutePath())).isEmpty(),
            "Processed log must migrate away from deleted staging path");
    }

    @Test
    void recordsFolderViewMergesDiscSubfoldersOutsideMonitorDirectory() throws Exception {
        Path failed = Files.createDirectories(Path.of(config.getFailedDirectory()));
        Path recoveredAlbum = failed.resolve("Metaphor Orchestral Album");
        File d1 = createFlac(recoveredAlbum.resolve("Disc 1").resolve("01.flac"),
            "Overture", "Composer", "Metaphor Orchestral Album", "Composer", "rg-meta");
        File d2 = createFlac(recoveredAlbum.resolve("Disc 2").resolve("01.flac"),
            "Finale", "Composer", "Metaphor Orchestral Album", "Composer", "rg-meta");
        processedLogger.markFileAsProcessed(d1, "rec-1", "Composer", "Overture", "Metaphor Orchestral Album",
            "rg-meta", outputDir.resolve("Composer").resolve("Metaphor Orchestral Album").resolve("01.flac").toString());
        processedLogger.markFileAsProcessed(d2, "rec-2", "Composer", "Finale", "Metaphor Orchestral Album",
            "rg-meta", outputDir.resolve("Composer").resolve("Metaphor Orchestral Album").resolve("02.flac").toString());

        ProcessedRecordService service = new ProcessedRecordService(
            config, processedLogger, folderAlbumCache, coverArtService, null, null);
        ProcessedRecordQuery query = new ProcessedRecordQuery();
        query.view = "folder";
        ProcessedRecordQuery.FolderPage page = service.listFolders(query);

        assertEquals(1, page.total, "Disc 1 and Disc 2 outside monitorDirectory must merge into 1 album folder");
        assertEquals("Metaphor Orchestral Album", page.items.get(0).folderName);
        assertEquals(2, page.items.get(0).discCount);
        assertEquals(2, page.items.get(0).totalCount);
    }

    @Test
    void normalMonitorStagedAudioKeepsProcessedRecordOnMonitorSourceFile() throws Exception {
        // Regression test for Issue #1: normal monitorDirectory items whose normalized audio was copied
        // into review-staging/<id> MUST keep their ProcessedRecord keyed on the monitor source file!
        Path albumDir = Files.createDirectory(monitorDir.resolve("NormalizedMonitorAlbum"));
        File monitorSource = createFlac(albumDir.resolve("01Source.flac"), "Track1", "Artist", "NormAlbum", "Artist", null);
        Path normTempDir = Files.createDirectory(tempDir.resolve("norm-tmp"));
        File normalizedTemp = Files.copy(monitorSource.toPath(), normTempDir.resolve("01Source-norm.flac")).toFile();

        MusicMetadata md = new MusicMetadata();
        md.setRecordingId("rec-norm-1");
        md.setTitle("Track1");
        md.setArtist("Artist");
        md.setAlbum("NormAlbum");
        md.setAlbumArtist("Artist");
        md.setTrackNo("1");

        batchProcessor.addPendingFile(albumDir.toString(), monitorSource, normalizedTemp, normTempDir, md, null);
        FolderAlbumCache.CachedAlbumInfo albumInfo = new FolderAlbumCache.CachedAlbumInfo(
            "rg-norm", "rel-norm", "NormAlbum", "Artist",
            1, "2026-02-01", 1.0, FolderAlbumCache.CacheSource.DURATION_SEQUENCE);

        AlbumBatchProcessor.BatchProcessResult intercepted =
            batchProcessor.processPendingFilesWithAlbum(albumDir.toString(), albumInfo, false);
        assertTrue(intercepted.isQueuedForMissingCover());
        ReviewItem queued = reviewQueue.get(intercepted.getQueuedReviewItemId());
        assertNotNull(queued.getStagingDir(), "Normalized temp file should be staged into review-staging/<id>");
        Path reviewStagingDir = Path.of(queued.getStagingDir());
        assertTrue(Files.isDirectory(reviewStagingDir));

        try (CoverMissingService service = new CoverMissingService(
                config, reviewQueue, folderAlbumCache, batchProcessor, tagWriter, coverCandidateService, processedLogger)) {
            CoverCandidateView uploaded = service.uploadCover(queued.getId(), createImageBytes(600, 600, "jpg"), "c.jpg");
            service.applyCover(queued.getId(), uploaded.getSha256(), RecoveryService.DuplicatePolicy.ASK);
        }

        assertTrue(Files.isRegularFile(monitorSource.toPath()), "Monitor source file remains in monitor directory");
        assertTrue(processedLogger.isFileProcessed(monitorSource),
            "ProcessedRecord MUST stay keyed on monitor source file so next scan does not re-process endlessly");
        assertFalse(Files.exists(reviewStagingDir), "review-staging/<id> directory should be cleaned after confirmation");
    }

    @Test
    void reprocessPendingReviewTransitionToMissingCoverCleansReprocessDirAndBlocksDoubleConfirm() throws Exception {
        // Regression test for Issue #2 & ReviewResolutionService COVER_MISSING guard
        Path reprocessRoot = Files.createDirectories(Path.of(config.getReviewStagingDirectory()).resolve("reprocess-xyz"));
        Path stagedAlbumDir = Files.createDirectories(reprocessRoot.resolve("UnverifiedBonus"));
        File stagedFlac = createFlac(stagedAlbumDir.resolve("01Song.flac"), "Song", "Singer", "UnverifiedBonus", "Singer", null);

        MusicMetadata md = new MusicMetadata();
        md.setRecordingId("rec-u-1");
        md.setTitle("Song");
        md.setArtist("Singer");
        md.setAlbum("UnverifiedBonus");
        md.setAlbumArtist("Singer");
        md.setTrackNo("1");
        FolderAlbumCache.PendingFile pf = new FolderAlbumCache.PendingFile(stagedFlac, stagedFlac, null, md, null);

        FolderAlbumCache.CachedAlbumInfo synth = new FolderAlbumCache.CachedAlbumInfo(
            null, null, "UnverifiedBonus", "Singer", 1, "", 0.0, FolderAlbumCache.CacheSource.DURATION_SEQUENCE);
        ReviewItem item = reviewQueue.enqueue(
            stagedAlbumDir.toString(), List.of(pf), synth, List.of(), List.of(120), "no match", 0.0);
        assertNotNull(item);
        reviewQueue.updatePending(item.getId(), target -> {
            target.setReprocessDir(reprocessRoot.toAbsolutePath().toString());
            return true;
        });

        ReviewResolutionService resolutionService = new ReviewResolutionService(
            config, reviewQueue, folderAlbumCache, batchProcessor, null, new DurationSequenceService(), processedLogger);
        ReviewItem transitioned = resolutionService.archiveAsUnverified(item.getId());
        assertEquals(ReviewItem.Kind.COVER_MISSING, transitioned.effectiveKind(),
            "Should transition to COVER_MISSING when cover is absent");

        // Double-confirming from review.html must be rejected with 409
        ReviewResolutionService.ResolutionException ex = assertThrows(
            ReviewResolutionService.ResolutionException.class,
            () -> resolutionService.archiveAsUnverified(item.getId()));
        assertEquals(409, ex.getHttpStatus());

        try (CoverMissingService coverService = new CoverMissingService(
                config, reviewQueue, folderAlbumCache, batchProcessor, tagWriter, coverCandidateService, processedLogger)) {
            coverService.confirmNoCover(item.getId(), RecoveryService.DuplicatePolicy.ASK);
        }

        Path archived = outputDir.resolve("Singer/UnverifiedBonus/01. Singer - Song.flac");
        assertTrue(Files.isRegularFile(archived));
        assertFalse(Files.exists(reprocessRoot), "reprocess-* root must be deleted after COVER_MISSING confirmation");
        assertTrue(processedLogger.findRecordsByPaths(List.of(stagedFlac.getAbsolutePath())).isEmpty(),
            "ProcessedRecord must not point to deleted reprocess-* path");
        assertEquals(1, processedLogger.findRecordsByPaths(List.of(archived.toString())).size(),
            "ProcessedRecord must migrate to final archived file path");
    }

    @Test
    void userUploadDuringBackgroundCoverSearchIsPreservedAtFront() throws Exception {
        // Regression test for Issue #4: concurrent user upload while CoverSearchService runs
        Path albumDir = Files.createDirectory(monitorDir.resolve("RaceAlbum"));
        File flac = createFlac(albumDir.resolve("01.flac"), "T", "A", "RaceAlbum", "A", null);
        ReviewItem.MissingCover details = new ReviewItem.MissingCover();
        details.setMode(ReviewItem.MissingCover.Mode.STAGED_IMPORT);
        details.setAlbumTitle("RaceAlbum");
        details.setAlbumArtist("A");
        ReviewItem item = reviewQueue.enqueueMissingCover(
            albumDir.toString(),
            List.of(new FolderAlbumCache.PendingFile(flac, flac, null, new MusicMetadata(), null)),
            details, "race");

        java.util.concurrent.CountDownLatch searchEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch allowSearchFinish = new java.util.concurrent.CountDownLatch(1);

        WebSearchClient blockingSearch = new WebSearchClient() {
            @Override public boolean hasEnabledEndpoint() { return true; }
            @Override public String name() { return "tavily"; }
            @Override public SearchResponse search(String s, String u) { return searchImages(s, u, null); }
            @Override public SearchResponse searchImages(String sys, String usr, ProgressListener p) {
                searchEntered.countDown();
                try {
                    allowSearchFinish.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                SearchResponse r = new SearchResponse();
                r.setText("{\"cover_urls\":[],\"search_notes\":\"done\"}");
                return r;
            }
        };

        CoverCandidateService noNetworkCandidates = new CoverCandidateService(
                config, new MusicBrainzClient(config), coverArtService, coverArtCache) {
            @Override
            public List<CoverCandidateView> searchItunesCovers(String albumTitle, String albumArtist, String customQuery) {
                return List.of();
            }
        };
        CoverSearchService searchService = new CoverSearchService(config, reviewQueue, noNetworkCandidates, blockingSearch);

        try (CoverMissingService coverService = new CoverMissingService(
                config, reviewQueue, folderAlbumCache, batchProcessor, tagWriter, noNetworkCandidates, processedLogger)) {
            Thread bg = new Thread(() -> {
                try {
                    searchService.searchCoversForItem(item.getId(), null, null);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            bg.start();
            assertTrue(searchEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));

            // User uploads a cover while background search is mid-flight
            CoverCandidateView userUploaded = coverService.uploadCover(
                item.getId(), createImageBytes(700, 700, "png"), "my_scan.png");

            allowSearchFinish.countDown();
            bg.join(5000);

            ReviewItem updated = reviewQueue.get(item.getId());
            List<CoverCandidateView> cands = updated.getMissingCover().getCoverCandidates();
            assertFalse(cands.isEmpty(), "User uploaded cover must not be wiped by background search");
            assertEquals(userUploaded.getSha256(), cands.get(0).getSha256());
            assertEquals(CoverCandidateView.Origin.USER_UPLOAD, cands.get(0).getOrigin());
            assertEquals(userUploaded.getSha256(), updated.getMissingCover().getSelectedCoverSha256());
        }
    }

    @Test
    void multiDiscStagedReidentifySubmitsSingleTaskPreservingDiscSubfolders() throws Exception {
        // Regression test for Issue #3: archived Disc 1 + Disc 2 must stay in ONE reprocess-* task with Disc subfolders
        Path archivedAlbum = outputDir.resolve("Composer/Symphony Box");
        File d1 = createFlac(archivedAlbum.resolve("Disc 1/01.flac"), "Mov 1", "Composer", "Symphony Box", "Composer", null);
        File d2 = createFlac(archivedAlbum.resolve("Disc 2/01.flac"), "Mov 2", "Composer", "Symphony Box", "Composer", null);

        File goneSrc1 = tempDir.resolve("gone-monitor/Symphony Box/Disc 1/01.flac").toFile();
        File goneSrc2 = tempDir.resolve("gone-monitor/Symphony Box/Disc 2/01.flac").toFile();
        processedLogger.markFileAsProcessed(goneSrc1, "r1", "Composer", "Mov 1", "Symphony Box", null, d1.getAbsolutePath());
        processedLogger.markFileAsProcessed(goneSrc2, "r2", "Composer", "Mov 2", "Symphony Box", null, d2.getAbsolutePath());

        List<File> capturedAlbumRoots = new ArrayList<>();
        List<List<RecoveryService.StagedReidentifyFile>> capturedBatches = new ArrayList<>();

        try (RecoveryService spyRecovery = new RecoveryService(
                config, null, processedLogger, reviewQueue, folderAlbumCache, null,
                new com.lux032.musicautotagger.util.FileSystemUtils(config), tagWriter, null, null) {
            @Override
            java.util.concurrent.Future<?> submitStagedReidentify(
                    File stagedDir, String displayName, List<StagedReidentifyFile> files, List<String> recordsToRemove) {
                capturedAlbumRoots.add(stagedDir);
                capturedBatches.add(new ArrayList<>(files));
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        }) {
            ProcessedRecordService recordService = new ProcessedRecordService(
                config, processedLogger, folderAlbumCache, coverArtService,
                new com.lux032.musicautotagger.util.FileSystemUtils(config), () -> null);
            recordService.setRecoveryService(spyRecovery);
            recordService.setReviewQueueService(reviewQueue);

            List<ProcessedRecord> targets = recordService.resolveTargets(
                List.of(goneSrc1.getAbsolutePath(), goneSrc2.getAbsolutePath()), null);
            Map<String, Object> res = recordService.reidentify(targets);
            assertEquals(2, res.get("recoveryQueued"));
        }

        assertEquals(1, capturedBatches.size(), "Multi-disc album must be submitted as a SINGLE combined reidentify job");
        List<RecoveryService.StagedReidentifyFile> staged = capturedBatches.get(0);
        assertEquals(2, staged.size());
        assertEquals("Disc 1", staged.get(0).staged().getParentFile().getName());
        assertEquals("01.flac", staged.get(0).staged().getName());
        assertEquals("Disc 2", staged.get(1).staged().getParentFile().getName());
        assertEquals("01.flac", staged.get(1).staged().getName());
    }

    @Test
    void shutdownWithIncompleteMissingCoverFolderPreservesMonitorFilesWithoutPartialQueue() throws Exception {
        // Regression test for Issue #5: shutdown when only 2 of 4 tracks were collected must NOT enqueue a 2-track COVER_MISSING item
        Path albumDir = Files.createDirectory(monitorDir.resolve("ShutdownPartialAlbum"));
        File f1 = createFlac(albumDir.resolve("01.flac"), "T1", "Artist", "ShutdownPartialAlbum", "Artist", null);
        File f2 = createFlac(albumDir.resolve("02.flac"), "T2", "Artist", "ShutdownPartialAlbum", "Artist", null);
        File f3 = createFlac(albumDir.resolve("03.flac"), "T3", "Artist", "ShutdownPartialAlbum", "Artist", null);
        File f4 = createFlac(albumDir.resolve("04.flac"), "T4", "Artist", "ShutdownPartialAlbum", "Artist", null);

        FolderAlbumCache.CachedAlbumInfo locked = new FolderAlbumCache.CachedAlbumInfo(
            "rg-sd", "rel-sd", "ShutdownPartialAlbum", "Artist",
            4, "2026-01-01", 1.0, FolderAlbumCache.CacheSource.DURATION_SEQUENCE);
        folderAlbumCache.forceSetFolderAlbum(albumDir.toString(), locked);

        MusicMetadata md1 = new MusicMetadata(); md1.setTitle("T1"); md1.setArtist("Artist"); md1.setTrackNo("1");
        MusicMetadata md2 = new MusicMetadata(); md2.setTitle("T2"); md2.setArtist("Artist"); md2.setTrackNo("2");
        batchProcessor.addPendingFile(albumDir.toString(), f1, f1, null, md1, null);
        batchProcessor.addPendingFile(albumDir.toString(), f2, f2, null, md2, null);

        batchProcessor.processAllPendingFilesBeforeShutdown();

        assertEquals(0, reviewQueue.countPendingMissingCover(),
            "Incomplete folder on shutdown must not enqueue a partial COVER_MISSING item");
        assertEquals(0, batchProcessor.getPendingFileCount(albumDir.toString()));
        assertFalse(processedLogger.isFileProcessed(f1));
        assertFalse(processedLogger.isFileProcessed(f3));
        assertTrue(f4.isFile());
    }

    @Test
    void deferredOnlineMissingCoverPersistsManualTrackCreditsAcrossQueueReload() throws Exception {
        Path albumDir = Files.createDirectory(monitorDir.resolve("OnlineMissingCoverCredits"));
        File flac = createFlac(albumDir.resolve("01. Starlight.flac"), "Starlight", "Singer", "Old", "Singer", null);
        // 给源文件写入原有作词，用于验证显式清空经过缺封面中转和队列重载后仍能生效
        var audio = AudioFileIO.read(flac);
        Tag t = audio.getTagOrCreateAndSetDefault();
        t.setField(FieldKey.LYRICIST, "Old Lyricist");
        ((org.jaudiotagger.tag.flac.FlacTag) t).setField("LYRICISTS", "Old Lyricist");
        audio.commit();

        ReviewItem item = reviewQueue.enqueueRecoveryFolder(albumDir.toString(), null, List.of(flac), tagWriter, null);
        ReviewItem.OnlineCandidate cand = new ReviewItem.OnlineCandidate();
        cand.setId("cand-cover");
        cand.setTitle("Starlight Single");
        cand.setArtist("Singer");
        cand.setAlbumArtist("Singer");
        ReviewItem.OnlineTrack track = new ReviewItem.OnlineTrack();
        track.setDiscNo(1);
        track.setTrackNo(1);
        track.setTitle("Starlight");
        track.setArtist("Singer");
        track.setArrangers(List.of("Online Arranger"));
        track.setDuration(120);
        cand.setTracks(List.of(track));
        item.setOnlineCandidates(List.of(cand));
        OnlineIdentificationService online = new OnlineIdentificationService(config, reviewQueue, tagWriter, null, null);
        item.setEvidenceHash(online.evidenceHashForFolder(albumDir.toFile()));
        reviewQueue.update(item);

        ReviewItem.TrackCreditsOverride manual = new ReviewItem.TrackCreditsOverride();
        manual.setComposers(List.of("梶浦由記", "澤野弘之"));
        manual.setLyricists(List.of()); // 显式清空原有作词
        manual.setArrangers(null);      // 沿用联网提取的编曲

        AudioFingerprintService stubFp = new AudioFingerprintService(config) {
            @Override
            public List<Integer> extractDurationSequence(List<File> files) {
                return List.of(120);
            }
        };

        // 1. 首次联网确认：无封面 -> 被拦截转入 COVER_MISSING，并持久化 onlineTrackCredits
        try (RecoveryService recovery = new RecoveryService(
                config, null, processedLogger, reviewQueue, folderAlbumCache, null,
                new com.lux032.musicautotagger.util.FileSystemUtils(config), tagWriter, stubFp, coverCandidateService, null)) {
            ReviewItem held = recovery.confirmOnlineCandidate(item.getId(), cand.getId(),
                "Starlight Single", "Singer", "2026-08-01", "", null, null,
                RecoveryService.DuplicatePolicy.ASK, null, false,
                Map.of(flac.getAbsolutePath(), manual));
            assertEquals(ReviewItem.Kind.COVER_MISSING, held.effectiveKind());
            assertEquals(ReviewItem.Status.PENDING_REVIEW, held.getStatus());
            assertEquals(1, held.getMissingCover().getOnlineTrackCredits().size());
        }

        // 2. 模拟进程重启：重新从磁盘加载 ReviewQueueService，并在「缺少封面」页补图提交
        ReviewQueueService reloadedQueue = new ReviewQueueService(config);
        try (RecoveryService recovery2 = new RecoveryService(
                config, null, processedLogger, reloadedQueue, folderAlbumCache, null,
                new com.lux032.musicautotagger.util.FileSystemUtils(config), tagWriter, stubFp, coverCandidateService, null);
             CoverMissingService coverService = new CoverMissingService(
                config, reloadedQueue, folderAlbumCache, batchProcessor, tagWriter, coverCandidateService, processedLogger)) {
            coverService.setRecoveryService(recovery2);
            CoverCandidateView uploaded = coverService.uploadCover(
                item.getId(), createImageBytes(600, 600, "jpg"), "front.jpg");
            ReviewItem done = coverService.applyCover(
                item.getId(), uploaded.getSha256(), RecoveryService.DuplicatePolicy.ASK);
            assertEquals(ReviewItem.Status.CONFIRMED, done.getStatus());
        }

        Path archived = outputDir.resolve("Singer/Starlight Single/1.01 Singer - Starlight.flac");
        assertTrue(Files.isRegularFile(archived));
        assertTrue(tagWriter.hasEmbeddedCover(archived.toFile()));
        MusicMetadata written = tagWriter.readTags(archived.toFile());
        assertEquals("梶浦由記, 澤野弘之", written.getComposer());
        assertEquals(List.of("梶浦由記", "澤野弘之"), written.getComposers());
        assertNull(written.getLyricist(), "Explicitly cleared lyricist must survive missing-cover detour and restart");
        assertEquals("Online Arranger", written.getArranger());
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
