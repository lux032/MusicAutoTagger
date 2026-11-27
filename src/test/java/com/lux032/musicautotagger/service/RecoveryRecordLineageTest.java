package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ProcessResult;
import com.lux032.musicautotagger.model.ProcessedRecord;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.llm.LlmArtistSplitter;
import com.lux032.musicautotagger.util.FileSystemUtils;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.lux032.musicautotagger.service.ArchiveDuplicateDetectorTest.flac;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖隔离副本恢复全出口（ONLINE_SEARCH / LOCAL_TAGS / REIDENTIFY / LOCAL_ARTIST_MATCH / COVER_MISSING / REVIEW_RESOLUTION）
 * 的统一血缘回写、4 种写入分支、存量兜底反推及重识别失败保底与旧归档替换。
 */
class RecoveryRecordLineageTest {

    @TempDir
    Path tempDir;

    private MusicConfig config;
    private Path monitorDir;
    private Path outputDir;
    private Path failedDir;
    private Path partialDir;
    private Path trashDir;
    private Path workDir;
    private Path reviewQueuePath;
    private FileSystemUtils fileSystemUtils;
    private TagWriterService tagWriter;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        config = ctor.newInstance();
        monitorDir = Files.createDirectories(tempDir.resolve("monitor"));
        outputDir = Files.createDirectories(tempDir.resolve("output"));
        failedDir = Files.createDirectories(tempDir.resolve("failed"));
        partialDir = Files.createDirectories(tempDir.resolve("partial"));
        trashDir = Files.createDirectories(tempDir.resolve("trash"));
        workDir = Files.createDirectories(tempDir.resolve("work"));
        reviewQueuePath = tempDir.resolve("data/review-queue.json");
        Files.createDirectories(reviewQueuePath.getParent());

        config.setDbType("file");
        config.setMonitorDirectory(monitorDir.toString());
        config.setOutputDirectory(outputDir.toString());
        config.setFailedDirectory(failedDir.toString());
        config.setPartialDirectory(partialDir.toString());
        config.setRecoveryTrashDirectory(trashDir.toString());
        config.setRecoveryWorkDirectory(workDir.toString());
        config.setReviewQueuePath(reviewQueuePath.toString());
        config.setReviewStagingDirectory(tempDir.resolve("data/review-staging").toString());
        config.setProcessedFileLogPath(tempDir.resolve("data/processed.log").toString());
        config.setCoverArtCacheDirectory(tempDir.resolve("data/cover-cache").toString());
        config.setAutoRename(true);
        config.setExportLyricsToFile(false);
        config.setCoverMissingRequireReview(false);
        config.setEnableLLMMatching(false);
        config.setSupportedFormats(new String[] {"flac", "mp3", "wav"});

        fileSystemUtils = new FileSystemUtils(config);
        tagWriter = new TagWriterService(config);
    }

    // ==================== Task 2.1: 4 Write-back Branches & Legacy Fallback ====================

    @Test
    void recordRecoveredArchiveFileCoversAllFourBranchesIncludingSqliteUnmounted() throws Exception {
        config.setDbType("sqlite");
        config.setDbSqlitePath(tempDir.resolve("data/lineage.db").toString());
        try (DatabaseService db = new DatabaseService(config)) {
            ProcessedFileLogger logger = new ProcessedFileLogger(config, db);
            RecoveryOriginRegistry origins = RecoveryOriginRegistry.forReviewQueuePath(config.getReviewQueuePath());
            try (RecoveryService service = newRecoveryService(logger, new ReviewQueueService(config),
                    new FolderAlbumCache(null, null, null), null, null, null)) {

                // Branch 1: monitor root accessible & monitor file exists -> in-place UPSERT FAILED -> SUCCESS
                Path mon1 = writeTaggedFlac(monitorDir.resolve("VAMPY/01.flac"), 11, "VAMPY", "HIMEHINA", "Track 1", "1");
                Path iso1 = Files.createDirectories(failedDir.resolve("VAMPY")).resolve("01.flac");
                Files.copy(mon1, iso1);
                Path out1 = writeTaggedFlac(outputDir.resolve("HIMEHINA/VAMPY/01.flac"), 11, "VAMPY", "HIMEHINA", "Track 1", "1");
                logger.markFileAsProcessed(mon1.toFile(), "UNKNOWN", "识别失败", "01.flac", "Unknown Album");
                origins.record(iso1.toString(), mon1.toString());

                service.recordRecoveredArchiveFile(iso1.toFile(), null, RecoveryService.SourceType.FAILED, null,
                    out1.toFile(), "ONLINE_SEARCH", "HIMEHINA", "Track 1", "VAMPY", null);

                List<ProcessedRecord> after1 = logger.loadAllRecords();
                assertEquals(1, after1.size());
                assertEquals(mon1.toString(), after1.get(0).filePath);
                assertEquals(ProcessedRecord.Status.SUCCESS, after1.get(0).status);
                assertEquals("ONLINE_SEARCH", after1.get(0).recordingId);
                assertEquals(out1.toString(), after1.get(0).targetFilePath);

                // Branch 2: monitor root accessible & monitor file deleted -> delete stale FAILED row, write finalOutput
                Path mon2 = monitorDir.resolve("DeletedAlbum/01.flac");
                Path iso2 = writeTaggedFlac(failedDir.resolve("DeletedAlbum/01.flac"), 12, "Del", "Art", "Song2", "1");
                Path out2 = writeTaggedFlac(outputDir.resolve("Art/Del/01.flac"), 12, "Del", "Art", "Song2", "1");
                logger.markFileAsProcessed(mon2.toFile(), "UNKNOWN", "识别失败", "01.flac", "Unknown Album");
                origins.record(iso2.toString(), mon2.toString());

                service.recordRecoveredArchiveFile(iso2.toFile(), null, RecoveryService.SourceType.FAILED, null,
                    out2.toFile(), "LOCAL_TAGS", "Art", "Song2", "Del", null);

                assertTrue(logger.findRecordsByPaths(List.of(mon2.toString())).isEmpty(), "旧 FAILED 行应被删除");
                List<ProcessedRecord> out2Recs = logger.findRecordsByPaths(List.of(out2.toString()));
                assertEquals(1, out2Recs.size());
                assertEquals(ProcessedRecord.Status.SUCCESS, out2Recs.get(0).status);
                assertEquals(out2.toString(), out2Recs.get(0).targetFilePath);

                // Branch 4: no monitor origin -> write finalOutput directly
                Path iso4 = writeTaggedFlac(failedDir.resolve("NoOrigin/01.flac"), 14, "NoOrig", "Art4", "Song4", "1");
                Path out4 = writeTaggedFlac(outputDir.resolve("Art4/NoOrig/01.flac"), 14, "NoOrig", "Art4", "Song4", "1");
                service.recordRecoveredArchiveFile(iso4.toFile(), null, RecoveryService.SourceType.FAILED, null,
                    out4.toFile(), "ONLINE_SEARCH", "Art4", "Song4", "NoOrig", null);
                List<ProcessedRecord> out4Recs = logger.findRecordsByPaths(List.of(out4.toString()));
                assertEquals(1, out4Recs.size());
                assertEquals(ProcessedRecord.Status.SUCCESS, out4Recs.get(0).status);

                // Branch 3: monitor root temporarily unmounted/inaccessible -> preserve origin path in SQLite!
                Path missingMonitorRoot = tempDir.resolve("unmounted-nas");
                config.setMonitorDirectory(missingMonitorRoot.toString());
                Path mon3 = missingMonitorRoot.resolve("NasAlbum/01.flac");
                Path iso3 = writeTaggedFlac(failedDir.resolve("NasAlbum/01.flac"), 13, "Nas", "Art3", "Song3", "1");
                Path out3 = writeTaggedFlac(outputDir.resolve("Art3/Nas/01.flac"), 13, "Nas", "Art3", "Song3", "1");
                logger.markFileAsProcessed(mon3.toFile(), "UNKNOWN", "识别失败", "01.flac", "Unknown Album");
                origins.record(iso3.toString(), mon3.toString());

                service.recordRecoveredArchiveFile(iso3.toFile(), null, RecoveryService.SourceType.FAILED, null,
                    out3.toFile(), "ONLINE_SEARCH", "Art3", "Song3", "Nas", null);

                List<ProcessedRecord> mon3Recs = logger.findRecordsByPaths(List.of(mon3.toString()));
                assertEquals(1, mon3Recs.size(), "监控根目录不可访问时应保留监控路径写回且不抛 FileNotFoundException");
                assertEquals(ProcessedRecord.Status.SUCCESS, mon3Recs.get(0).status);
                assertEquals(out3.toString(), mon3Recs.get(0).targetFilePath);
                assertTrue(logger.findRecordsByPaths(List.of(out3.toString())).isEmpty());
            }
        }
    }

    @Test
    void inferLegacyOriginMatchesUniqueCopiesAndSkipsMismatchOrAmbiguity() throws Exception {
        ProcessedFileLogger logger = new ProcessedFileLogger(config, null);
        try (RecoveryService service = newRecoveryService(logger, new ReviewQueueService(config),
                new FolderAlbumCache(null, null, null), null, null, null)) {

            // 1. Legacy FAILED FLAC with same MD5 -> matched
            Path monFlac = writeTaggedFlac(monitorDir.resolve("Artist/VAMPY/01.flac"), 21, "VAMPY", "HIMEHINA", "T1", "1");
            Path failedFlac = Files.createDirectories(failedDir.resolve("Artist/VAMPY")).resolve("01.flac");
            Files.copy(monFlac, failedFlac);
            assertEquals(Optional.of(monFlac), service.inferLegacyOrigin(failedFlac.toFile(), RecoveryService.SourceType.FAILED));

            // 2. Legacy FAILED FLAC with mismatched MD5 -> empty
            Path badFailedFlac = writeTaggedFlac(failedDir.resolve("Artist/Bad/01.flac"), 22, "Bad", "A", "T1", "1");
            writeTaggedFlac(monitorDir.resolve("Artist/Bad/01.flac"), 23, "Bad", "A", "T1", "1");
            assertTrue(service.inferLegacyOrigin(badFailedFlac.toFile(), RecoveryService.SourceType.FAILED).isEmpty());

            // 3. Legacy FAILED deleted monitor source with FAILED row -> inferred so Branch 2 cleans it
            Path deletedMon = monitorDir.resolve("Gone/01.flac");
            Path goneFailed = writeTaggedFlac(failedDir.resolve("Gone/01.flac"), 24, "Gone", "A", "T1", "1");
            logger.markFileAsProcessed(deletedMon.toFile(), "UNKNOWN", "fail", "01.flac", "Unknown Album");
            assertEquals(Optional.of(deletedMon), service.inferLegacyOrigin(goneFailed.toFile(), RecoveryService.SourceType.FAILED));

            // 4. Legacy PARTIAL flattened album with cover embedded (size changed, FLAC MD5 unchanged) -> matched!
            Path monPartialFlac = writeTaggedFlac(monitorDir.resolve("Sub/UniqueAlbum/01.flac"), 25, "UniqueAlbum", "A", "T1", "1");
            logger.markFileAsProcessed(monPartialFlac.toFile(), "UNKNOWN", "fail", "01.flac", "Unknown Album");
            Path partialFlac = Files.createDirectories(partialDir.resolve("UniqueAlbum")).resolve("01.flac");
            Files.copy(monPartialFlac, partialFlac);
            assertTrue(tagWriter.embedFolderCover(partialFlac.toFile(), validJpeg()));
            assertNotEquals(Files.size(monPartialFlac), Files.size(partialFlac));
            assertEquals(Optional.of(monPartialFlac), service.inferLegacyOrigin(partialFlac.toFile(), RecoveryService.SourceType.PARTIAL));

            // 5. Legacy PARTIAL unverified cross-format .wav vs .flac -> must NOT blind-bind without explicit origin!
            Path monWav = monitorDir.resolve("Sub/WavAlbum/01.wav");
            Files.createDirectories(monWav.getParent());
            Files.write(monWav, new byte[64]);
            logger.markFileAsProcessed(monWav.toFile(), "UNKNOWN", "fail", "01.wav", "Unknown Album");
            Path partialNorm = writeTaggedFlac(partialDir.resolve("WavAlbum/01.flac"), 26, "WavAlbum", "A", "T1", "1");
            assertTrue(service.inferLegacyOrigin(partialNorm.toFile(), RecoveryService.SourceType.PARTIAL).isEmpty(),
                "无显式血缘且跨格式无法核验音频内容时不得盲绑");

            // 6. Ambiguous PARTIAL folder name (two monitor FAILED folders named "Greatest Hits") -> empty!
            Path dupA = writeTaggedFlac(monitorDir.resolve("Greatest Hits/01.flac"), 27, "GH", "A", "T1", "1");
            Path dupB = writeTaggedFlac(monitorDir.resolve("ArtistB/Greatest Hits/01.flac"), 27, "GH", "B", "T1", "1");
            logger.markFileAsProcessed(dupA.toFile(), "UNKNOWN", "fail", "01.flac", "Unknown Album");
            logger.markFileAsProcessed(dupB.toFile(), "UNKNOWN", "fail", "01.flac", "Unknown Album");
            Path ambigPartial = Files.createDirectories(partialDir.resolve("Greatest Hits")).resolve("01.flac");
            Files.copy(dupA, ambigPartial);
            assertTrue(service.inferLegacyOrigin(ambigPartial.toFile(), RecoveryService.SourceType.PARTIAL).isEmpty(),
                "直接路径与扁平目录名同时命中不同失败目录时必须判歧义跳过");
        }
    }

    // ==================== Task 2.2: All 4 RecoveryService Exits + Reidentify Replacement ====================

    @Test
    void onlineSearchAndLocalTagsConvergeToSingleMonitorRecordAndSupportReidentifyReplacement() throws Exception {
        ProcessedFileLogger logger = new ProcessedFileLogger(config, null);
        ReviewQueueService queue = new ReviewQueueService(config);
        FolderAlbumCache cache = new FolderAlbumCache(null, null, null);
        CoverArtService coverArtService = new CoverArtService(null, null);
        FailedFileHandler failedHandler = new FailedFileHandler(config, tagWriter, coverArtService, logger, fileSystemUtils);
        ReidentifyReplacementRegistry replacements = ReidentifyReplacementRegistry.forReviewQueuePath(config.getReviewQueuePath());

        // 1) ONLINE_SEARCH path: monitor album fails -> copied to failed/ -> online confirm -> single SUCCESS row on monitor
        Path monAlbumDir = Files.createDirectories(monitorDir.resolve("HIMEHINA - VAMPY"));
        Path monTrack = writeTaggedFlac(monAlbumDir.resolve("01. VAMPY.flac"), 31, "VAMPY", "HIMEHINA", "VAMPY", "1");
        failedHandler.handleAlbumFileFailed(monTrack.toFile(), monAlbumDir.toFile());
        Path failedAlbumDir = failedDir.resolve("HIMEHINA - VAMPY");
        assertTrue(Files.isDirectory(failedAlbumDir));

        ReviewItem onlineItem = queue.enqueueRecoveryFolder(
            failedAlbumDir.toString(), "FAILED", List.of(failedAlbumDir.resolve("01. VAMPY.flac").toFile()), tagWriter, null);
        ReviewItem.OnlineCandidate candidate = new ReviewItem.OnlineCandidate();
        candidate.setId("cand-1");
        candidate.setTitle("VAMPY");
        candidate.setArtist("HIMEHINA");
        candidate.setAlbumArtist("HIMEHINA");
        candidate.setReleaseDate("2026-01-01");
        ReviewItem.OnlineTrack track = new ReviewItem.OnlineTrack();
        track.setDiscNo(1);
        track.setTrackNo(1);
        track.setTitle("VAMPY");
        track.setArtist("HIMEHINA");
        candidate.setTracks(List.of(track));
        onlineItem.setOnlineCandidates(List.of(candidate));
        queue.update(onlineItem);

        try (RecoveryService recovery = newRecoveryService(logger, queue, cache, null, failedHandler, null)) {
            recovery.confirmOnlineCandidate(onlineItem.getId(), "cand-1", "VAMPY", "HIMEHINA",
                "2026-01-01", null, null, null, RecoveryService.DuplicatePolicy.ASK, null, true);

            // Verify single SUCCESS row keyed by monitor file, isolated copy retired to trash
            List<ProcessedRecord> recs = logger.loadAllRecords();
            assertEquals(1, recs.size(), "联网恢复后只应保留 1 条主记录");
            assertEquals(monTrack.toString(), recs.get(0).filePath);
            assertEquals(ProcessedRecord.Status.SUCCESS, recs.get(0).status);
            assertEquals("ONLINE_SEARCH", recs.get(0).recordingId);
            Path firstArchive = Path.of(recs.get(0).targetFilePath);
            assertTrue(Files.isRegularFile(firstArchive));
            assertFalse(Files.exists(failedAlbumDir), "隔离副本应移入回收站");

            // Now re-identify from ProcessedRecordService -> scrape succeeds to a NEW output path -> old archive auto-replaced!
            List<File> requeued = new ArrayList<>();
            FileMonitorService stubMonitor = new FileMonitorService(config, logger) {
                @Override public boolean isConsumerAvailable() { return true; }
                @Override public int requeueFiles(Collection<File> files, Runnable beforeEnqueue) {
                    beforeEnqueue.run();
                    requeued.addAll(files);
                    return files.size();
                }
            };
            ProcessedRecordService recordService = new ProcessedRecordService(
                config, logger, cache, coverArtService, fileSystemUtils, () -> stubMonitor, replacements);
            recordService.setRecoveryService(recovery);
            List<ProcessedRecord> targets = recordService.resolveTargets(List.of(monTrack.toString()), null);
            assertEquals(1, recordService.reidentify(targets).get("queued"));
            assertEquals(List.of(monTrack.toFile()), requeued);
            assertTrue(Files.isRegularFile(firstArchive), "重识别成功前绝不删除旧归档");

            AlbumBatchProcessor batch = new AlbumBatchProcessor(config, cache, tagWriter, logger, coverArtService,
                new LlmArtistSplitter(config), replacements);
            MusicMetadata mbMeta = new MusicMetadata();
            mbMeta.setRecordingId("mb-rec-vampy-1");
            mbMeta.setReleaseGroupId("mb-rg-vampy");
            mbMeta.setArtist("HIMEHINA Official");
            mbMeta.setAlbumArtist("HIMEHINA Official");
            mbMeta.setAlbum("VAMPY (Remastered)");
            mbMeta.setTitle("VAMPY");
            mbMeta.setDiscNo("1");
            mbMeta.setTrackNo("1");
            assertTrue(batch.processAndWriteFile(monTrack.toFile(), monTrack.toFile(), mbMeta, null, false));

            List<ProcessedRecord> afterReidentify = logger.loadAllRecords();
            assertEquals(1, afterReidentify.size());
            assertEquals(monTrack.toString(), afterReidentify.get(0).filePath);
            assertEquals("mb-rec-vampy-1", afterReidentify.get(0).recordingId);
            Path newArchive = Path.of(afterReidentify.get(0).targetFilePath);
            assertTrue(Files.isRegularFile(newArchive));
            assertNotEquals(firstArchive, newArchive);
            assertFalse(Files.exists(firstArchive), "重新识别成功后旧归档应自动清理，不留双份");
            assertFalse(Files.exists(firstArchive.getParent()), "旧空专辑目录应自动清理");

            // 2) LOCAL_TAGS path: failed monitor album -> confirmLocalArchive -> single SUCCESS row on monitor
            Path monLocalDir = Files.createDirectories(monitorDir.resolve("LocalAlbum"));
            Path monLocalTrack = writeTaggedFlac(monLocalDir.resolve("01. Local.flac"), 32, "LocalAlbum", "LocalArt", "Local", "1");
            failedHandler.handleAlbumFileFailed(monLocalTrack.toFile(), monLocalDir.toFile());
            Path failedLocalDir = failedDir.resolve("LocalAlbum");
            ReviewItem localItem = queue.enqueueRecoveryFolder(
                failedLocalDir.toString(), "FAILED", List.of(failedLocalDir.resolve("01. Local.flac").toFile()), tagWriter, null);
            LocalArchiveDraft.Draft draft = recovery.previewLocalArchive(localItem.getId());
            recovery.confirmLocalArchive(localItem.getId(), draft.revision(), "LocalAlbum", "LocalArt",
                List.of("LocalArt"), Map.of(), List.of(), RecoveryService.DuplicatePolicy.ASK);

            List<ProcessedRecord> localRecs = logger.findRecordsByPaths(List.of(monLocalTrack.toString()));
            assertEquals(1, localRecs.size());
            assertEquals(ProcessedRecord.Status.SUCCESS, localRecs.get(0).status);
            assertEquals("LOCAL_TAGS", localRecs.get(0).recordingId);
            assertTrue(Files.isRegularFile(Path.of(localRecs.get(0).targetFilePath)));
            assertFalse(Files.exists(failedLocalDir));
            assertEquals(2, logger.loadAllRecords().size(), "两个专辑共应只有 2 条监控主记录");
        }
    }

    @Test
    void reidentifyDirectCommitAndLocalArtistMatchConvergeToSingleMonitorRecord() throws Exception {
        ProcessedFileLogger logger = new ProcessedFileLogger(config, null);
        ReviewQueueService queue = new ReviewQueueService(config);
        FolderAlbumCache cache = new FolderAlbumCache(null, null, null);
        CoverArtService coverArtService = new CoverArtService(null, null);

        // Prepare monitor loose file + album for REIDENTIFY direct commit
        Path monSingle = writeTaggedFlac(monitorDir.resolve("single.flac"), 41, "SingleAlb", "SingleArt", "SingleSong", "1");
        FailedFileHandler failedHandler = new FailedFileHandler(config, tagWriter, coverArtService, logger, fileSystemUtils);
        failedHandler.handleLooseFileFailed(monSingle.toFile());
        Path failedSingle = failedDir.resolve("single.flac");
        assertTrue(Files.isRegularFile(failedSingle));

        AudioFileProcessorService stubProcessor = new AudioFileProcessorService(
            config, null, null, tagWriter, null, logger, null, coverArtService, null, failedHandler, fileSystemUtils, cache) {
            @Override
            public ProcessResult processRecoveryFile(File audioFile, File albumRootDir) {
                MusicMetadata md = new MusicMetadata();
                md.setRecordingId("mb-reid-1");
                md.setReleaseGroupId("rg-reid-1");
                md.setArtist("SingleArt");
                md.setAlbumArtist("SingleArt");
                md.setAlbum("SingleAlb");
                md.setTitle("SingleSong");
                md.setDiscNo("1");
                md.setTrackNo("1");
                TagWriterService.TagProcessResult res = tagWriter.processFileWithResult(audioFile, md, null);
                assertTrue(res.isSuccess());
                logger.markFileAsProcessed(audioFile, "mb-reid-1", "SingleArt", "SingleSong", "SingleAlb",
                    "rg-reid-1", res.getTargetFile().getAbsolutePath());
                return ProcessResult.SUCCESS;
            }
        };

        // Also prepare PARTIAL album for LOCAL_ARTIST_MATCH with real oldArchive replacement & unrelated sibling guard
        Path monPartialDir = Files.createDirectories(monitorDir.resolve("PartialMatchAlb"));
        Path monPartialTrack = writeTaggedFlac(monPartialDir.resolve("01. Track.flac"), 42, "PartialMatchAlb", "MatchedArt", "Track", "1");
        Files.write(monPartialDir.resolve("cover.jpg"), validJpeg());
        // Create an unrelated failed album sharing the exact same folder name "PartialMatchAlb" (different audio MD5 & origin!)
        Path unrelatedFailedTrack = writeTaggedFlac(failedDir.resolve("PartialMatchAlb/01. Other.flac"), 99, "OtherAlb", "OtherArt", "Other", "1");
        // Pre-create existing artist directory and real old archive + replacement entry
        Files.createDirectories(outputDir.resolve("MatchedArt"));
        Path oldPartialArchive = writeTaggedFlac(outputDir.resolve("OldArt/OldAlb/01. Track.flac"), 42, "OldAlb", "OldArt", "Track", "1");
        ReidentifyReplacementRegistry.forReviewQueuePath(config.getReviewQueuePath())
            .record(monPartialTrack.toString(), oldPartialArchive.toString());
        ArtistMatchingService stubArtistMatcher = new ArtistMatchingService(config) {
            @Override
            public MatchResult matchArtist(String sourceArtist) {
                return new MatchResult("MatchedArt", 0.99, "exact");
            }
        };
        FailedFileHandler llmFailedHandler = new FailedFileHandler(
            config, tagWriter, coverArtService, logger, fileSystemUtils, stubArtistMatcher, new LlmArtistSplitter(config));
        llmFailedHandler.handleAlbumFileFailed(monPartialTrack.toFile(), monPartialDir.toFile());
        Path partialAlbumDir = partialDir.resolve("PartialMatchAlb");
        assertTrue(Files.isDirectory(partialAlbumDir));

        try (RecoveryService recovery = newRecoveryService(logger, queue, cache, stubProcessor, llmFailedHandler, null)) {
            // 1) Submit single-file REIDENTIFY
            String job1 = recovery.submit(RecoveryService.SourceType.FAILED, "single.flac",
                RecoveryService.RecoveryMode.REIDENTIFY, false);
            awaitJob(recovery, job1, "SUCCEEDED");

            assertFalse(Files.exists(failedSingle), "单文件隔离副本应移入回收站");
            List<ProcessedRecord> singleRecs = logger.findRecordsByPaths(List.of(monSingle.toString()));
            assertEquals(1, singleRecs.size(), "单文件 REIDENTIFY 必须回写到原监控源而非输出路径");
            assertEquals(ProcessedRecord.Status.SUCCESS, singleRecs.get(0).status);
            assertEquals("mb-reid-1", singleRecs.get(0).recordingId);
            assertTrue(Files.isRegularFile(Path.of(singleRecs.get(0).targetFilePath)));

            // 2) Submit LOCAL_ARTIST_MATCH on partial album
            String job2 = recovery.submit(RecoveryService.SourceType.PARTIAL, "PartialMatchAlb",
                RecoveryService.RecoveryMode.LOCAL_ARTIST_MATCH, false);
            awaitJob(recovery, job2, "SUCCEEDED");

            assertFalse(Files.exists(partialAlbumDir));
            assertFalse(Files.exists(oldPartialArchive), "LOCAL_ARTIST_MATCH 成功后应清理已登记的旧归档");
            assertTrue(Files.isRegularFile(unrelatedFailedTrack),
                "对侧 failed/ 下同名但不同音频的无关专辑绝不可被联动清理误删");
            List<ProcessedRecord> partialRecs = logger.findRecordsByPaths(List.of(monPartialTrack.toString()));
            assertEquals(1, partialRecs.size(), "LOCAL_ARTIST_MATCH 必须回写到原监控源");
            assertEquals(ProcessedRecord.Status.SUCCESS, partialRecs.get(0).status);
            assertEquals("LOCAL_TAGS", partialRecs.get(0).recordingId);
            assertTrue(Files.isRegularFile(Path.of(partialRecs.get(0).targetFilePath)));
            assertEquals(2, logger.loadAllRecords().size(), "全程无分裂记录或隔离路径残留记录");
        }
    }

    // ==================== Task 2.3: CoverMissingService & ReviewResolutionService ====================

    @Test
    void coverMissingAndReviewResolutionFromRecoveryConvergeRecordsAndRetireIsolatedCopies() throws Exception {
        config.setCoverMissingRequireReview(true);
        ProcessedFileLogger logger = new ProcessedFileLogger(config, null);
        ReviewQueueService queue = new ReviewQueueService(config);
        FolderAlbumCache cache = new FolderAlbumCache(new DurationSequenceService(), null, null);
        MusicBrainzClient noCoverMb = new MusicBrainzClient(config) {
            @Override
            public CoverArtResolution resolveCoverArtByReleaseGroupId(String releaseGroupId) {
                return new CoverArtResolution(null, false);
            }
        };
        CoverArtCache coverArtCache = new CoverArtCache(null, config.getCoverArtCacheDirectory(), config);
        CoverArtService coverArtService = new CoverArtService(coverArtCache, noCoverMb);
        CoverCandidateService coverCandidateService = new CoverCandidateService(config, noCoverMb, coverArtService, coverArtCache);
        AlbumBatchProcessor batchProcessor = new AlbumBatchProcessor(config, cache, tagWriter, logger, coverArtService);
        batchProcessor.setReviewQueueService(queue);
        FailedFileHandler failedHandler = new FailedFileHandler(config, tagWriter, coverArtService, logger, fileSystemUtils);

        try (RecoveryService recovery = newRecoveryService(logger, queue, cache, null, failedHandler, coverCandidateService);
             CoverMissingService coverService = new CoverMissingService(
                 config, queue, cache, batchProcessor, tagWriter, coverCandidateService, logger)) {
            coverService.setRecoveryService(recovery);
            ReviewResolutionService reviewService = new ReviewResolutionService(
                config, queue, cache, batchProcessor, noCoverMb, new DurationSequenceService(), logger);
            reviewService.setRecoveryService(recovery);

            // --- Case A: Recovery item -> COVER_MISSING -> applyCover ---
            Path monCoverDir = Files.createDirectories(monitorDir.resolve("NoCoverRecovery"));
            Path monCoverTrack = writeTaggedFlac(monCoverDir.resolve("01. CoverTrack.flac"), 51, "NoCoverAlb", "CoverArt", "CoverTrack", "1");
            failedHandler.handleAlbumFileFailed(monCoverTrack.toFile(), monCoverDir.toFile());
            Path failedCoverDir = failedDir.resolve("NoCoverRecovery");
            Path failedCoverTrack = failedCoverDir.resolve("01. CoverTrack.flac");

            MusicMetadata cmMd = new MusicMetadata();
            cmMd.setRecordingId("mb-cover-1");
            cmMd.setReleaseGroupId("rg-cover-1");
            cmMd.setArtist("CoverArt");
            cmMd.setAlbumArtist("CoverArt");
            cmMd.setAlbum("NoCoverAlb");
            cmMd.setTitle("CoverTrack");
            cmMd.setTrackNo("1");
            ReviewItem.MissingCover details = new ReviewItem.MissingCover();
            details.setMode(ReviewItem.MissingCover.Mode.STAGED_IMPORT);
            details.setAlbumTitle("NoCoverAlb");
            details.setAlbumArtist("CoverArt");
            details.setReleaseGroupId("rg-cover-1");
            details.setReleaseId("rel-cover-1");
            ReviewItem cmItem = queue.enqueueMissingCover(
                failedCoverDir.toString(),
                List.of(new FolderAlbumCache.PendingFile(failedCoverTrack.toFile(), failedCoverTrack.toFile(), null, cmMd, null)),
                details, "missing cover");
            queue.updatePending(cmItem.getId(), it -> {
                it.setRecoverySourceType("FAILED");
                it.setRecoverySourcePath(failedCoverDir.toString());
                return true;
            });

            CoverCandidateView uploaded = coverService.uploadCover(cmItem.getId(), validJpeg(), "front.jpg");
            ReviewItem resolvedCm = coverService.applyCover(cmItem.getId(), uploaded.getSha256(), RecoveryService.DuplicatePolicy.ASK);
            assertEquals(ReviewItem.Status.CONFIRMED, resolvedCm.getStatus());
            assertTrue(resolvedCm.getResolutionNote().contains("补全封面"), "不应被 finishCommittedRecovery 覆盖 resolutionNote");
            assertFalse(Files.exists(failedCoverDir), "缺封面补图入库后隔离目录应进入回收站");

            List<ProcessedRecord> cmRecs = logger.findRecordsByPaths(List.of(monCoverTrack.toString()));
            assertEquals(1, cmRecs.size());
            assertEquals(ProcessedRecord.Status.SUCCESS, cmRecs.get(0).status);
            assertEquals("mb-cover-1", cmRecs.get(0).recordingId);
            assertTrue(Files.isRegularFile(Path.of(cmRecs.get(0).targetFilePath)));
            assertTrue(logger.findRecordsByPaths(List.of(failedCoverTrack.toString())).isEmpty(), "不应残留隔离路径记录");

            // --- Case B: Recovery single-file (single-<jobId>) -> PENDING_REVIEW -> archiveAsUnverified ---
            config.setCoverMissingRequireReview(false);
            Path monReviewSingle = writeTaggedFlac(monitorDir.resolve("ReviewSingle.flac"), 52, "RevAlb", "RevArt", "RevTrack", "1");
            failedHandler.handleLooseFileFailed(monReviewSingle.toFile());
            Path failedReviewSingle = failedDir.resolve("ReviewSingle.flac");
            Path singleWs = Files.createDirectories(workDir.resolve("single-testjob"));
            Path singleCopy = singleWs.resolve("ReviewSingle.flac");
            Files.copy(failedReviewSingle, singleCopy);
            RecoveryOriginRegistry.forReviewQueuePath(config.getReviewQueuePath())
                .recordAlias(failedReviewSingle, singleCopy);

            MusicMetadata revMd = new MusicMetadata();
            revMd.setRecordingId("mb-rev-1");
            revMd.setArtist("RevArt");
            revMd.setAlbumArtist("RevArt");
            revMd.setAlbum("RevAlb");
            revMd.setTitle("RevTrack");
            revMd.setTrackNo("1");
            FolderAlbumCache.CachedAlbumInfo synth = new FolderAlbumCache.CachedAlbumInfo(
                null, null, "RevAlb", "RevArt", 1, "2026", 0.5, FolderAlbumCache.CacheSource.UNKNOWN);
            ReviewItem revItem = queue.enqueue(
                singleWs.toString(),
                List.of(new FolderAlbumCache.PendingFile(singleCopy.toFile(), singleCopy.toFile(), null, revMd, null)),
                synth, List.of(), List.of(120), "ambiguous", 0.5);
            queue.updatePending(revItem.getId(), it -> {
                it.setRecoverySourceType("FAILED");
                it.setRecoverySourcePath(failedReviewSingle.toString());
                return true;
            });

            ReviewItem resolvedRev = reviewService.archiveAsUnverified(revItem.getId());
            assertEquals(ReviewItem.Status.ARCHIVED_UNVERIFIED, resolvedRev.getStatus());
            assertFalse(Files.exists(failedReviewSingle), "待确认归档后隔离副本应移入回收站");
            assertFalse(Files.exists(singleWs), "单文件恢复工作目录 single-<jobId> 应清理");

            List<ProcessedRecord> revRecs = logger.findRecordsByPaths(List.of(monReviewSingle.toString()));
            assertEquals(1, revRecs.size(), "待确认归档应回写到原监控源");
            assertEquals(ProcessedRecord.Status.SUCCESS, revRecs.get(0).status);
            assertTrue(Files.isRegularFile(Path.of(revRecs.get(0).targetFilePath)));
            assertEquals(2, logger.loadAllRecords().size(), "两条恢复链路均收敛为单条监控主记录");
        }
    }

    // ==================== Task 2.4: Failed Re-scrape Preserves Archive & target_file_path ====================

    @Test
    void failedRescrapePreservesOriginalArchiveTargetPathAndReplacesOnSubsequentRecovery() throws Exception {
        ProcessedFileLogger logger = new ProcessedFileLogger(config, null);
        ReviewQueueService queue = new ReviewQueueService(config);
        FolderAlbumCache cache = new FolderAlbumCache(null, null, null);
        CoverArtService coverArtService = new CoverArtService(null, null);
        FailedFileHandler failedHandler = new FailedFileHandler(config, tagWriter, coverArtService, logger, fileSystemUtils);
        ReidentifyReplacementRegistry replacements = ReidentifyReplacementRegistry.forReviewQueuePath(config.getReviewQueuePath());

        // Step 1: Monitor album already has a single SUCCESS record (from ONLINE_SEARCH) pointing to OldArtist/VAMPY
        Path monAlbum = Files.createDirectories(monitorDir.resolve("VAMPY_FAIL_TEST"));
        Path monTrack = writeTaggedFlac(monAlbum.resolve("01. VAMPY.flac"), 61, "VAMPY", "HIMEHINA", "VAMPY", "1");
        Path oldArchive = writeTaggedFlac(outputDir.resolve("OldArtist/VAMPY/01. VAMPY.flac"), 61, "VAMPY", "HIMEHINA", "VAMPY", "1");
        logger.markFileAsProcessed(monTrack.toFile(), "ONLINE_SEARCH", "HIMEHINA", "VAMPY", "VAMPY", null, oldArchive.toString());

        // Step 2: User triggers reidentify from ProcessedRecordService
        FileMonitorService stubMonitor = new FileMonitorService(config, logger) {
            @Override public boolean isConsumerAvailable() { return true; }
            @Override public int requeueFiles(Collection<File> files, Runnable beforeEnqueue) {
                beforeEnqueue.run();
                return files.size();
            }
        };
        ProcessedRecordService recordService = new ProcessedRecordService(
            config, logger, cache, coverArtService, fileSystemUtils, () -> stubMonitor, replacements);
        List<ProcessedRecord> targets = recordService.resolveTargets(List.of(monTrack.toString()), null);
        recordService.reidentify(targets);

        // Step 3: Re-scrape fails in FailedFileHandler.handleAlbumFileFailed
        failedHandler.handleAlbumFileFailed(monTrack.toFile(), monAlbum.toFile());

        // Assert: old archive untouched, FAILED record preserves target_file_path, alias propagated to failed/ copy
        assertTrue(Files.isRegularFile(oldArchive), "重识别失败时原归档文件绝不能被删除");
        List<ProcessedRecord> failedRecs = logger.findRecordsByPaths(List.of(monTrack.toString()));
        assertEquals(1, failedRecs.size());
        assertEquals(ProcessedRecord.Status.FAILED, failedRecs.get(0).status);
        assertEquals(oldArchive.toString(), failedRecs.get(0).targetFilePath, "失败记录必须保留原 target_file_path");

        Path failedCopy = failedDir.resolve("VAMPY_FAIL_TEST/01. VAMPY.flac");
        assertTrue(Files.isRegularFile(failedCopy));
        assertEquals(Optional.of(oldArchive), replacements.findOldTarget(failedCopy.toFile()));

        // Step 4: Later recovery via LOCAL_TAGS writes to NewArtist/VAMPY_NEW -> automatically replaces oldArchive!
        ReviewItem item = queue.enqueueRecoveryFolder(
            failedDir.resolve("VAMPY_FAIL_TEST").toString(), "FAILED", List.of(failedCopy.toFile()), tagWriter, null);
        try (RecoveryService recovery = newRecoveryService(logger, queue, cache, null, failedHandler, null)) {
            LocalArchiveDraft.Draft draft = recovery.previewLocalArchive(item.getId());
            recovery.confirmLocalArchive(item.getId(), draft.revision(), "VAMPY_NEW", "NewArtist",
                List.of("NewArtist"), Map.of(), List.of(), RecoveryService.DuplicatePolicy.ASK);
        }

        assertFalse(Files.exists(oldArchive), "再次恢复成功后原旧归档应被自动替换删除");
        assertFalse(Files.exists(oldArchive.getParent()), "旧归档空目录应清理");
        List<ProcessedRecord> finalRecs = logger.loadAllRecords();
        assertEquals(1, finalRecs.size());
        assertEquals(monTrack.toString(), finalRecs.get(0).filePath);
        assertEquals(ProcessedRecord.Status.SUCCESS, finalRecs.get(0).status);
        assertTrue(finalRecs.get(0).targetFilePath.contains("NewArtist"));
        assertTrue(Files.isRegularFile(Path.of(finalRecs.get(0).targetFilePath)));
    }

    @Test
    void startupCrashResumeReplaysPersistedTrackRecordsBeforeRetiringAndPreservesSuccessOnDeletedOrigin() throws Exception {
        ProcessedFileLogger logger = new ProcessedFileLogger(config, null);
        ReviewQueueService queue = new ReviewQueueService(config);

        // 1) Simulate crash right after COMMITTED persisted (before record write-back & trash retirement)
        Path monFile = writeTaggedFlac(monitorDir.resolve("CrashAlbum/01.flac"), 71, "CrashAlb", "CrashArt", "Song", "1");
        Path failedFile = Files.createDirectories(failedDir.resolve("CrashAlbum")).resolve("01.flac");
        Files.copy(monFile, failedFile);
        Path outFile = writeTaggedFlac(outputDir.resolve("CrashArt/CrashAlb/01.flac"), 71, "CrashAlb", "CrashArt", "Song", "1");
        logger.markFileAsProcessed(monFile.toFile(), "UNKNOWN", "fail", "01.flac", "Unknown Album");

        ReviewItem crashed = queue.enqueueRecoveryFolder(
            failedFile.getParent().toString(), "FAILED", List.of(failedFile.toFile()), tagWriter, null);
        crashed.setVerificationSource(ReviewItem.VerificationSource.ONLINE_SEARCH);
        crashed.setResolvedAlbumTitle("CrashAlb");
        crashed.setResolvedAlbumArtist("CrashArt");
        crashed.setResolvedTrackCount(1);
        crashed.setRecoveryCommitState(ReviewItem.RecoveryCommitState.COMMITTED);
        crashed.setCommittedOutputPaths(List.of(outFile.getParent().toString()));
        ReviewItem.CommittedTrackRecord tr = new ReviewItem.CommittedTrackRecord();
        tr.setIsolatedPath(failedFile.toString());
        tr.setSourceType("FAILED");
        tr.setOriginPath(monFile.toString());
        tr.setOutputPath(outFile.toString());
        tr.setRecordingId("ONLINE_SEARCH");
        tr.setArtist("CrashArt");
        tr.setTitle("Song");
        tr.setAlbum("CrashAlb");
        tr.setRecoverySource(true);
        crashed.setCommittedTrackRecords(List.of(tr));
        queue.update(crashed);

        // Restart RecoveryService -> resumeCommittedRecoveries MUST replay record write-back AND retire failed copy!
        try (RecoveryService resumed = newRecoveryService(logger, queue, new FolderAlbumCache(null, null, null), null, null, null)) {
            assertEquals(ReviewItem.Status.CONFIRMED, queue.get(crashed.getId()).getStatus());
            assertFalse(Files.exists(failedFile.getParent()), "启动重放后应将隔离目录移入回收站");
            List<ProcessedRecord> recs = logger.findRecordsByPaths(List.of(monFile.toString()));
            assertEquals(1, recs.size());
            assertEquals(ProcessedRecord.Status.SUCCESS, recs.get(0).status);
            assertEquals("ONLINE_SEARCH", recs.get(0).recordingId);
            assertEquals(outFile.toString(), recs.get(0).targetFilePath);

            // 2) Branch 2 guard: deleted origin whose DB record is ALREADY SUCCESS must NOT be deleted!
            Path deletedSuccessOrigin = monitorDir.resolve("AlreadySuccess/01.flac");
            Path existingArchive = writeTaggedFlac(outputDir.resolve("ArtS/Old/01.flac"), 72, "Old", "ArtS", "S", "1");
            logger.markFileAsProcessed(deletedSuccessOrigin.toFile(), "mb-existing-success", "ArtS", "S", "Old", null, existingArchive.toString());
            Path restoredOldCopy = writeTaggedFlac(failedDir.resolve("AlreadySuccess/01.flac"), 73, "New", "ArtS", "S2", "1");
            Path newArchive = writeTaggedFlac(outputDir.resolve("ArtS/New/01.flac"), 73, "New", "ArtS", "S2", "1");
            RecoveryOriginRegistry.forReviewQueuePath(config.getReviewQueuePath())
                .record(restoredOldCopy.toString(), deletedSuccessOrigin.toString());

            resumed.recordRecoveredArchiveFile(restoredOldCopy.toFile(), null, RecoveryService.SourceType.FAILED, null,
                newArchive.toFile(), "LOCAL_TAGS", "ArtS", "S2", "New", null);

            List<ProcessedRecord> keptSuccess = logger.findRecordsByPaths(List.of(deletedSuccessOrigin.toString()));
            assertEquals(1, keptSuccess.size(), "已删源但原记录为 SUCCESS 时绝不可误删该 SUCCESS 记录");
            assertEquals("mb-existing-success", keptSuccess.get(0).recordingId);
            assertEquals(1, logger.findRecordsByPaths(List.of(newArchive.toString())).size());
        }
    }

    // ==================== Helpers ====================

    private RecoveryService newRecoveryService(ProcessedFileLogger logger,
                                               ReviewQueueService queue,
                                               FolderAlbumCache cache,
                                               AudioFileProcessorService processor,
                                               FailedFileHandler failedHandler,
                                               CoverCandidateService coverCandidateService) {
        return new RecoveryService(config, processor, logger, queue, cache, failedHandler,
            fileSystemUtils, tagWriter, null, coverCandidateService);
    }

    private void awaitJob(RecoveryService service, String jobId, String expectedStatus) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            for (Map<String, Object> job : service.listJobs()) {
                if (jobId.equals(job.get("id"))) {
                    String status = String.valueOf(job.get("status"));
                    if (!"QUEUED".equals(status) && !"RUNNING".equals(status)) {
                        assertEquals(expectedStatus, status, "Job message: " + job.get("message"));
                        return;
                    }
                }
            }
            Thread.sleep(50);
        }
        fail("Timed out waiting for recovery job " + jobId);
    }

    private Path writeTaggedFlac(Path target, int md5Seed, String album, String artist,
                                 String title, String trackNo) throws Exception {
        byte[] bytes = validFlacBytes(md5Seed);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        var audio = AudioFileIO.read(target.toFile());
        Tag tag = audio.getTagOrCreateAndSetDefault();
        tag.setField(FieldKey.ALBUM, album);
        tag.setField(FieldKey.ARTIST, artist);
        tag.setField(FieldKey.ALBUM_ARTIST, artist);
        tag.setField(FieldKey.TITLE, title);
        tag.setField(FieldKey.TRACK, trackNo);
        audio.commit();
        return target;
    }

    private static byte[] validFlacBytes(int md5Seed) {
        byte[] flac = flac(md5Seed);
        int sr = 44100;
        int channelsMinus1 = 1;
        int bpsMinus1 = 15;
        long totalSamples = 88200L;
        flac[18] = (byte) (sr >> 12);
        flac[19] = (byte) (sr >> 4);
        flac[20] = (byte) (((sr & 0xF) << 4) | (channelsMinus1 << 1) | ((bpsMinus1 >> 4) & 0x1));
        flac[21] = (byte) (((bpsMinus1 & 0xF) << 4) | ((totalSamples >> 32) & 0xF));
        flac[22] = (byte) (totalSamples >> 24);
        flac[23] = (byte) (totalSamples >> 16);
        flac[24] = (byte) (totalSamples >> 8);
        flac[25] = (byte) totalSamples;
        return flac;
    }

    private static byte[] validJpeg() throws Exception {
        // 生成 > 8KB 的彩色噪点 JPEG，确保内嵌进 FLAC 后突破默认 4KB PADDING 块使文件体积发生变化
        BufferedImage img = new BufferedImage(320, 320, BufferedImage.TYPE_INT_RGB);
        java.util.Random rnd = new java.util.Random(42L);
        for (int y = 0; y < 320; y++) {
            for (int x = 0; x < 320; x++) {
                img.setRGB(x, y, rnd.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
