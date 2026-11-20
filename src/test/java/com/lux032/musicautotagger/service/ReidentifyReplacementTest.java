package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ProcessResult;
import com.lux032.musicautotagger.model.ProcessedRecord;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.llm.LlmArtistSplitter;
import com.lux032.musicautotagger.util.FileSystemUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.lux032.musicautotagger.service.ArchiveDuplicateDetectorTest.flac;
import static com.lux032.musicautotagger.service.ArchiveDuplicateDetectorTest.write;
import static org.junit.jupiter.api.Assertions.*;

class ReidentifyReplacementTest {

    @TempDir
    Path dir;

    private MusicConfig config;
    private Path monitor;
    private Path output;
    private TagWriterService tagWriter;
    private ProcessedFileLogger logger;
    private ReidentifyReplacementRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        var constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        config = constructor.newInstance();
        monitor = dir.resolve("monitor");
        output = dir.resolve("output");
        Files.createDirectories(monitor);
        Files.createDirectories(output);
        config.setMonitorDirectory(monitor.toString());
        config.setOutputDirectory(output.toString());
        config.setRecoveryWorkDirectory(dir.resolve("work").toString());
        config.setRecoveryTrashDirectory(dir.resolve("trash").toString());
        config.setReviewQueuePath(dir.resolve("data/review.json").toString());
        config.setReviewStagingDirectory(dir.resolve("staging").toString());
        config.setProcessedFileLogPath(dir.resolve("processed.log").toString());
        config.setAutoRename(true);
        config.setExportLyricsToFile(false);

        tagWriter = new TagWriterService(config);
        logger = new ProcessedFileLogger(config, null);
        registry = ReidentifyReplacementRegistry.forReviewQueuePath(config.getReviewQueuePath());
    }

    private File wav(Path target, int seconds, byte fill) throws Exception {
        int sampleRate = 8000;
        int dataLen = sampleRate * seconds;
        ByteBuffer b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + dataLen).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
            .putInt(sampleRate).putInt(sampleRate).putShort((short) 1).putShort((short) 8);
        b.put("data".getBytes()).putInt(dataLen);
        for (int i = 0; i < dataLen; i++) b.put(fill);
        Files.createDirectories(target.getParent());
        Files.write(target, b.array());
        return target.toFile();
    }

    private static MusicMetadata meta(String artist, String albumArtist, String album, String title, String trackNo) {
        MusicMetadata md = new MusicMetadata();
        md.setRecordingId("mb-rec-1");
        md.setReleaseGroupId("mb-rg-1");
        md.setArtist(artist);
        md.setAlbumArtist(albumArtist);
        md.setAlbum(album);
        md.setTitle(title);
        md.setDiscNo("1");
        md.setTrackNo(trackNo);
        return md;
    }

    @Test
    void reidentifyToDifferentOutputPathDeletesOldFileAndEmptyDirectories() throws Exception {
        File src = wav(monitor.resolve("Album/01.wav"), 2, (byte) 1);
        AlbumBatchProcessor batch = new AlbumBatchProcessor(
            config, new FolderAlbumCache(null, null, null), tagWriter, logger,
            new CoverArtService(null, null), new LlmArtistSplitter(config), registry);

        // First scrape writes under Old Artist
        assertTrue(batch.processAndWriteFile(src, src, meta("Old Artist", "Old Artist", "Album", "Song", "1"), null, false));
        Path oldTarget = output.resolve("Old Artist/Album/1.01 Old Artist - Song.wav");
        assertTrue(Files.isRegularFile(oldTarget));

        // Re-identify from ProcessedRecordService
        List<File> requeued = new ArrayList<>();
        FileMonitorService stubMonitor = new FileMonitorService(config, logger) {
            @Override
            public boolean isConsumerAvailable() { return true; }

            @Override
            public int requeueFiles(Collection<File> files, Runnable beforeEnqueue) {
                beforeEnqueue.run();
                requeued.addAll(files);
                return files.size();
            }
        };
        ProcessedRecordService recordService = new ProcessedRecordService(
            config, logger, new FolderAlbumCache(null, null, null), new CoverArtService(null, null),
            new FileSystemUtils(config), () -> stubMonitor, registry);

        List<ProcessedRecord> targets = recordService.resolveTargets(List.of(src.getAbsolutePath()), null);
        assertEquals(1, targets.size());
        Map<String, Object> res = recordService.reidentify(targets);
        assertEquals(1, res.get("queued"));
        assertEquals(1, requeued.size());

        // Old target MUST still exist before the re-scrape actually succeeds
        assertTrue(Files.isRegularFile(oldTarget));

        // Re-scrape succeeds with New Artist -> old file and empty Old Artist/Album dirs are deleted
        assertTrue(batch.processAndWriteFile(src, src, meta("New Artist", "New Artist", "Album", "Song", "1"), null, false));
        Path newTarget = output.resolve("New Artist/Album/1.01 New Artist - Song.wav");
        assertTrue(Files.isRegularFile(newTarget));
        assertFalse(Files.exists(oldTarget), "旧路径文件应在重新刮削成功后直接删除");
        assertFalse(Files.exists(output.resolve("Old Artist/Album")), "空专辑目录应清理");
        assertFalse(Files.exists(output.resolve("Old Artist")), "空艺术家目录应清理");
    }

    @Test
    void forgetAndRescanOverwritesSamePathEvenWhenDurationChangesWithoutCreatingSuffix2() throws Exception {
        File src = wav(monitor.resolve("Album/01.wav"), 2, (byte) 1);
        AlbumBatchProcessor batch = new AlbumBatchProcessor(
            config, new FolderAlbumCache(null, null, null), tagWriter, logger,
            new CoverArtService(null, null), new LlmArtistSplitter(config), registry);

        assertTrue(batch.processAndWriteFile(src, src, meta("Artist", "Artist", "Album", "Song", "1"), null, false));
        Path target = output.resolve("Artist/Album/1.01 Artist - Song.wav");
        assertTrue(Files.isRegularFile(target));

        ProcessedRecordService recordService = new ProcessedRecordService(
            config, logger, new FolderAlbumCache(null, null, null), new CoverArtService(null, null),
            new FileSystemUtils(config), () -> null, registry);
        List<ProcessedRecord> targets = recordService.resolveTargets(List.of(src.getAbsolutePath()), null);
        recordService.forget(targets);
        assertTrue(Files.isRegularFile(target));

        // Replace source audio with different duration (> 1.0s diff) and re-scrape to identical output path
        wav(src.toPath(), 5, (byte) 9);
        assertTrue(batch.processAndWriteFile(src, src, meta("Artist", "Artist", "Album", "Song", "1"), null, false));

        assertTrue(Files.isRegularFile(target));
        assertFalse(TagWriterService.withConflictSuffix(target.toFile(), 2).exists(),
            "同一来源重新刮削到同路径时应直接覆盖，不应产生 (2) 副本");
    }

    private static byte[] validFlac(int md5Seed) {
        byte[] flac = flac(md5Seed);
        // STREAMINFO bytes [18..25] hold 20-bit sample rate, 3-bit channels-1, 5-bit bps-1, 36-bit total samples.
        // Set sampleRate = 44100, channels = 2, bps = 16, totalSamples = 88200 (2s).
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

    private File writeTaggedFlac(Path target, int md5Seed, String title, String trackNo) throws Exception {
        write(target, validFlac(md5Seed));
        var af = org.jaudiotagger.audio.AudioFileIO.read(target.toFile());
        var tag = af.getTagOrCreateAndSetDefault();
        tag.setField(org.jaudiotagger.tag.FieldKey.TITLE, title);
        tag.setField(org.jaudiotagger.tag.FieldKey.TRACK, trackNo);
        af.commit();
        return target.toFile();
    }

    @Test
    void recoveryOnlineConfirmReplacesTrackedTargetAcrossSameAndDifferentDirectories() throws Exception {
        Path folder = monitor.resolve("Multi");
        File src1 = writeTaggedFlac(folder.resolve("01.flac"), 11, "T1", "1");
        File src2 = writeTaggedFlac(folder.resolve("02.flac"), 12, "T2", "2");
        Path oldTrack1 = write(output.resolve("OldCollab/Album/01.flac"), validFlac(11));
        Path untouchedSibling = write(output.resolve("OldCollab/Album/99-other.flac"), validFlac(99));

        registry.record(src1.getAbsolutePath(), oldTrack1.toString(), null);
        registry.record(src2.getAbsolutePath(), output.resolve("OldCollab/Album/02.flac").toString(), null);
        write(output.resolve("OldCollab/Album/02.flac"), validFlac(12));

        ReviewQueueService queue = new ReviewQueueService(config);
        ReviewItem item = queue.enqueueRecoveryFolder(folder.toString(), null, List.of(src1, src2), tagWriter, "hash");

        ReviewItem.OnlineCandidate cand = new ReviewItem.OnlineCandidate();
        cand.setId("c1");
        cand.setTitle("Album");
        cand.setArtist("Artist A & Artist B");
        cand.setAlbumArtist("Artist A & Artist B");
        cand.setAlbumArtists(List.of("Artist A", "Artist B"));
        ReviewItem.OnlineTrack t1 = new ReviewItem.OnlineTrack();
        t1.setDiscNo(1); t1.setTrackNo(1); t1.setTitle("T1"); t1.setArtist("Artist A & Artist B");
        t1.setMatchedFilePath(src1.getAbsolutePath()); t1.setMatchConfidence(0.95);
        ReviewItem.OnlineTrack t2 = new ReviewItem.OnlineTrack();
        t2.setDiscNo(1); t2.setTrackNo(2); t2.setTitle("T2"); t2.setArtist("Artist A & Artist B");
        t2.setMatchedFilePath(src2.getAbsolutePath()); t2.setMatchConfidence(0.95);
        cand.getTracks().addAll(List.of(t1, t2));
        item.setOnlineCandidates(List.of(cand));
        OnlineIdentificationService online = new OnlineIdentificationService(config, queue, tagWriter, null);
        item.setEvidenceHash(online.evidenceHashForFolder(folder.toFile()));
        queue.update(item);

        try (RecoveryService recovery = new RecoveryService(
                config, null, logger, queue, new FolderAlbumCache(null, null, null), null,
                new FileSystemUtils(config), tagWriter, null, null)) {
            recovery.confirmOnlineCandidate(item.getId(), cand.getId(),
                "Album", "Artist A & Artist B", "2026-01-01", "", null, null,
                RecoveryService.DuplicatePolicy.ASK, null);
        }

        assertTrue(Files.isRegularFile(output.resolve("Artist A & Artist B/Album/1.01 Artist A & Artist B - T1.flac")));
        assertTrue(Files.isRegularFile(output.resolve("Artist A & Artist B/Album/1.02 Artist A & Artist B - T2.flac")));
        assertFalse(Files.exists(oldTrack1));
        assertFalse(Files.exists(output.resolve("OldCollab/Album/02.flac")));
        assertTrue(Files.isRegularFile(untouchedSibling), "同目录内无关曲目不能被误删");
    }

    @Test
    void recoveryOnlineConfirmReplacesWhenDestinationDirectoryIsIdentical() throws Exception {
        Path folder = monitor.resolve("SameDir");
        File src = writeTaggedFlac(folder.resolve("01.flac"), 21, "New", "1");
        Path existing = write(output.resolve("Artist A & Artist B/Album/01 Old.flac"), validFlac(21));
        registry.record(src.getAbsolutePath(), existing.toString(), null);

        ReviewQueueService queue = new ReviewQueueService(config);
        ReviewItem item = queue.enqueueRecoveryFolder(folder.toString(), null, List.of(src), tagWriter, "hash");

        ReviewItem.OnlineCandidate cand = new ReviewItem.OnlineCandidate();
        cand.setId("c1");
        cand.setTitle("Album");
        cand.setArtist("Artist A & Artist B");
        cand.setAlbumArtist("Artist A & Artist B");
        cand.setAlbumArtists(List.of("Artist A", "Artist B"));
        ReviewItem.OnlineTrack t1 = new ReviewItem.OnlineTrack();
        t1.setDiscNo(1); t1.setTrackNo(1); t1.setTitle("New"); t1.setArtist("Artist A & Artist B");
        t1.setMatchedFilePath(src.getAbsolutePath()); t1.setMatchConfidence(0.95);
        cand.getTracks().add(t1);
        item.setOnlineCandidates(List.of(cand));
        OnlineIdentificationService online = new OnlineIdentificationService(config, queue, tagWriter, null);
        item.setEvidenceHash(online.evidenceHashForFolder(folder.toFile()));
        queue.update(item);

        try (RecoveryService recovery = new RecoveryService(
                config, null, logger, queue, new FolderAlbumCache(null, null, null), null,
                new FileSystemUtils(config), tagWriter, null, null)) {
            recovery.confirmOnlineCandidate(item.getId(), cand.getId(),
                "Album", "Artist A & Artist B", "2026-01-01", "", null, null,
                RecoveryService.DuplicatePolicy.ASK, null);
        }

        assertFalse(Files.exists(existing), "同专辑目录重刮后旧文件应被直接删除");
        assertTrue(Files.isRegularFile(output.resolve("Artist A & Artist B/Album/1.01 Artist A & Artist B - New.flac")));
    }

    @Test
    void reidentifyOnlineSearchRecordWithMissingSourceRunsNormalRecognitionAndRecordsFailure() throws Exception {
        // 即使原记录是 ONLINE_SEARCH 且原失败文件已移入回收站，点击重新识别仍先走普通识别，失败后写入失败记录并输出日志
        LogCollector.clear();
        Path oldTarget = writeTaggedFlac(
            output.resolve("TeddyLoid, 佐倉綾音 & 井上和/Beyond the Night (Single)/1.01 TeddyLoid, 佐倉綾音 & 井上和 - Beyond the Night.flac"),
            31, "Beyond the Night", "1").toPath();
        File missingFailedSource = dir.resolve("failed/TeddyLoid, 佐倉綾音 & 井上和 - Beyond the Night/1.01. Beyond the Night.flac").toFile();
        Files.createDirectories(missingFailedSource.getParentFile().toPath());
        Files.copy(oldTarget, missingFailedSource.toPath());
        logger.markFileAsProcessed(missingFailedSource, "ONLINE_SEARCH", "TeddyLoid, 佐倉綾音 & 井上和",
            "Beyond the Night", "Beyond the Night", null, oldTarget.toString());
        Files.delete(missingFailedSource.toPath()); // moved to trash

        ReviewQueueService queue = new ReviewQueueService(config);
        List<File> seenRecoveryRoots = new ArrayList<>();
        AudioFileProcessorService fakeProcessor = new AudioFileProcessorService(
                config, null, null, tagWriter, null, logger, null, null, null, null,
                new FileSystemUtils(config), new FolderAlbumCache(null, null, null)) {
            @Override
            public ProcessResult processRecoveryFile(File audioFile, File albumRootDir) {
                seenRecoveryRoots.add(albumRootDir);
                return ProcessResult.PERMANENT_FAIL;
            }
        };

        try (RecoveryService recovery = new RecoveryService(
                config, fakeProcessor, logger, queue, new FolderAlbumCache(null, null, null), null,
                new FileSystemUtils(config), tagWriter, null, null, new LlmArtistSplitter(config), null)) {
            ProcessedRecordService recordService = new ProcessedRecordService(
                config, logger, new FolderAlbumCache(null, null, null), new CoverArtService(null, null),
                new FileSystemUtils(config), () -> null, registry);
            recordService.setRecoveryService(recovery);
            recordService.setReviewQueueService(queue);

            List<ProcessedRecord> targets = recordService.resolveTargets(List.of(missingFailedSource.getAbsolutePath()), null);
            assertEquals(1, targets.size());
            Map<String, Object> preview = recordService.preview(targets);
            assertEquals(1, preview.get("recoverableFromTarget"));

            Map<String, Object> res = recordService.reidentify(targets);
            assertEquals(1, res.get("recoveryQueued"));
            assertEquals(1, res.get("queued"));

            long deadline = System.currentTimeMillis() + 5000;
            while (logger.findRecordsByPaths(List.of(oldTarget.toString())).isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }

            assertEquals(1, seenRecoveryRoots.size());
            assertEquals("Beyond the Night", seenRecoveryRoots.get(0).getName(), "普通识别暂存目录应使用真实专辑名而非 UUID");
            assertTrue(Files.isRegularFile(oldTarget), "普通识别失败时不得删除已有归档文件");
            assertTrue(queue.list(ReviewItem.Status.PENDING_REVIEW).isEmpty(), "不应跳过普通识别直接进入联网搜索待确认");

            List<ProcessedRecord> afterRecords = logger.findRecordsByPaths(List.of(oldTarget.toString()));
            assertEquals(1, afterRecords.size());
            assertEquals(ProcessedRecord.Status.FAILED, afterRecords.get(0).status);

            // 验证非静默：LogCollector 中应有提交和失败转入失败记录日志
            long logDeadline = System.currentTimeMillis() + 2000;
            boolean hasSubmitLog = false, hasFailLog = false;
            while (System.currentTimeMillis() < logDeadline && !(hasSubmitLog && hasFailLog)) {
                for (LogCollector.LogEntry entry : LogCollector.getRecentLogs(50)) {
                    if (entry.getMessage().contains("已提交")) hasSubmitLog = true;
                    if (entry.getMessage().contains("已转入失败记录")) hasFailLog = true;
                }
                if (!(hasSubmitLog && hasFailLog)) Thread.sleep(20);
            }
            assertTrue(hasSubmitLog, "应输出重新识别提交日志");
            assertTrue(hasFailLog, "应输出转入失败记录日志");
        }
    }

    @Test
    void dismissRecoveryItemMovesToTrashRemovesFromListAndCanRestore() throws Exception {
        Path failedRoot = dir.resolve("failed");
        config.setFailedDirectory(failedRoot.toString());
        Path failedAlbum = failedRoot.resolve("Unidentified Album");
        writeTaggedFlac(failedAlbum.resolve("01.flac"), 10, "Track 1", "1");

        ReviewQueueService queue = new ReviewQueueService(config);
        try (RecoveryService recovery = new RecoveryService(
                config, null, logger, queue, new FolderAlbumCache(null, null, null), null,
                new FileSystemUtils(config), tagWriter, null, null)) {
            assertEquals(1, recovery.listItems().size());
            LogCollector.clear();

            recovery.dismiss(RecoveryService.SourceType.FAILED, "Unidentified Album");

            assertTrue(recovery.listItems().isEmpty(), "忽略后不应继续停留在失败列表中");
            assertFalse(Files.exists(failedAlbum), "原失败隔离目录应已移走");

            List<Map<String, Object>> trash = recovery.listTrash();
            assertEquals(1, trash.size());
            assertEquals("FAILED", trash.get(0).get("sourceType"));
            assertEquals("Unidentified Album", trash.get(0).get("relativePath"));
            assertEquals(true, trash.get(0).get("restorable"));

            boolean hasLog = LogCollector.getRecentLogs(20).stream()
                .anyMatch(e -> e.getMessage().contains("已忽略") && e.getMessage().contains("Unidentified Album"));
            assertTrue(hasLog, "忽略操作应写入可见日志");

            // 仍支持从回收站还原
            recovery.restoreFromTrash((String) trash.get(0).get("id"));
            assertEquals(1, recovery.listItems().size());
            assertTrue(Files.isDirectory(failedAlbum));
        }
    }

    @Test
    void stagedReidentifySuccessUpdatesRecordToFinalOutputPathAndReplacesOldArchive() throws Exception {
        Path oldTarget = writeTaggedFlac(
            output.resolve("Old Artist/Old Album/01 Song.flac"), 41, "Song", "1").toPath();
        File missingSource = dir.resolve("failed/Old Album/01 Song.flac").toFile();
        Files.createDirectories(missingSource.getParentFile().toPath());
        Files.copy(oldTarget, missingSource.toPath());
        logger.markFileAsProcessed(missingSource, "ONLINE_SEARCH", "Old Artist", "Song", "Old Album", null, oldTarget.toString());
        Files.delete(missingSource.toPath());

        ReviewQueueService queue = new ReviewQueueService(config);
        AudioFileProcessorService fakeProcessor = new AudioFileProcessorService(
                config, null, null, tagWriter, null, logger, null, null, null, null,
                new FileSystemUtils(config), new FolderAlbumCache(null, null, null)) {
            @Override
            public ProcessResult processRecoveryFile(File audioFile, File albumRootDir) {
                var result = tagWriter.processFileWithResult(audioFile, meta("New Artist", "New Artist", "New Album", "Song", "1"), null);
                assertTrue(result.isSuccess());
                logger.markFileAsProcessed(audioFile, "mb-rec-new", "New Artist", "Song", "New Album", "mb-rg-new",
                    result.getTargetFile().getAbsolutePath());
                return ProcessResult.SUCCESS;
            }
        };

        try (RecoveryService recovery = new RecoveryService(
                config, fakeProcessor, logger, queue, new FolderAlbumCache(null, null, null), null,
                new FileSystemUtils(config), tagWriter, null, null, new LlmArtistSplitter(config), null)) {
            ProcessedRecordService recordService = new ProcessedRecordService(
                config, logger, new FolderAlbumCache(null, null, null), new CoverArtService(null, null),
                new FileSystemUtils(config), () -> null, registry);
            recordService.setRecoveryService(recovery);
            recordService.setReviewQueueService(queue);

            List<ProcessedRecord> targets = recordService.resolveTargets(List.of(missingSource.getAbsolutePath()), null);
            recordService.reidentify(targets);

            Path expectedNewTarget = output.resolve("New Artist/New Album/1.01 New Artist - Song.flac");
            long deadline = System.currentTimeMillis() + 5000;
            while (!Files.isRegularFile(expectedNewTarget) && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            // Wait slightly for finalizeCommittedRecords to run after commitWorkspace
            long recordDeadline = System.currentTimeMillis() + 5000;
            while (logger.findRecordsByPaths(List.of(expectedNewTarget.toString())).isEmpty()
                    && System.currentTimeMillis() < recordDeadline) {
                Thread.sleep(25);
            }

            assertFalse(Files.exists(oldTarget), "旧归档文件应已被替换清理");
            List<ProcessedRecord> recs = logger.findRecordsByPaths(List.of(expectedNewTarget.toString()));
            assertEquals(1, recs.size(), "处理历史应更新为真实归档路径，不能残余临时工作目录路径");
            assertEquals(ProcessedRecord.Status.SUCCESS, recs.get(0).status);
            assertEquals(expectedNewTarget.toString(), recs.get(0).targetFilePath);
        }
    }

    @Test
    void recoveryJobReflectsConfirmedOrRejectedReviewStatusInListJobs() throws Exception {
        Path failedRoot = dir.resolve("failed");
        config.setFailedDirectory(failedRoot.toString());
        Path failedAlbum = failedRoot.resolve("PLAY!+ Hatsunetsumiko's GAME COLLECTION");
        writeTaggedFlac(failedAlbum.resolve("01.flac"), 10, "Track 1", "1");

        ReviewQueueService queue = new ReviewQueueService(config);
        var fakeSearch = new com.lux032.musicautotagger.service.llm.WebSearchClient() {
            @Override public boolean hasEnabledEndpoint() { return true; }
            @Override public String name() { return "fake"; }
            @Override public SearchResponse search(String sys, String usr) {
                var resp = new SearchResponse();
                resp.setProvider("fake");
                resp.setModel("fake-model");
                resp.setText("{\"needs_second_round\":false,\"candidates\":[],\"clues\":[]}");
                return resp;
            }
        };
        var online = new OnlineIdentificationService(config, queue, tagWriter, null, new LlmArtistSplitter(config), fakeSearch);
        try (RecoveryService recovery = new RecoveryService(
                config, null, logger, queue, new FolderAlbumCache(null, null, null), null,
                new FileSystemUtils(config), tagWriter, null, null, new LlmArtistSplitter(config), online)) {
            recovery.submit(RecoveryService.SourceType.FAILED, "PLAY!+ Hatsunetsumiko's GAME COLLECTION",
                RecoveryService.RecoveryMode.ONLINE_SEARCH, false);

            long deadline = System.currentTimeMillis() + 5000;
            while ((recovery.listJobs().isEmpty()
                    || !"WAITING_REVIEW".equals(recovery.listJobs().get(0).get("status")))
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertEquals("WAITING_REVIEW", recovery.listJobs().get(0).get("status"));

            ReviewItem item = queue.list(ReviewItem.Status.PENDING_REVIEW).get(0);
            queue.markResolved(item, ReviewItem.Status.REJECTED, "rejected");

            assertEquals("FAILED", recovery.listJobs().get(0).get("status"));
        }
    }

    @Test
    void failedStagedReidentifyFollowedByOnlineConfirmReplacesOldSingleArtistArchive() throws Exception {
        // Reproduces exact Plex screenshot flow:
        // 19:23 single-artist archive -> "处理记录 -> 重新识别" fails & enters /failed_files/Beyond the Night
        // -> 19:44 user runs online search & confirms multi-artist archive -> old single-artist file must be cleaned up.
        Path failedRoot = dir.resolve("failed");
        config.setFailedDirectory(failedRoot.toString());
        Path oldTarget = writeTaggedFlac(
            output.resolve("TeddyLoid/Beyond the Night/1.01 TeddyLoid - Beyond the Night.flac"),
            51, "Beyond the Night", "1").toPath();
        File initialFailed = failedRoot.resolve("initial/1.01.flac").toFile();
        Files.createDirectories(initialFailed.getParentFile().toPath());
        Files.copy(oldTarget, initialFailed.toPath());
        logger.markFileAsProcessed(initialFailed, "ONLINE_SEARCH", "TeddyLoid", "Beyond the Night", "Beyond the Night", null, oldTarget.toString());
        Files.delete(initialFailed.toPath());

        ReviewQueueService queue = new ReviewQueueService(config);
        AudioFileProcessorService fakeProcessor = new AudioFileProcessorService(
                config, null, null, tagWriter, null, logger, null, null, null, null,
                new FileSystemUtils(config), new FolderAlbumCache(null, null, null)) {
            @Override
            public ProcessResult processRecoveryFile(File audioFile, File albumRootDir) {
                try {
                    Path copy = failedRoot.resolve(albumRootDir.getName()).resolve(audioFile.getName());
                    Files.createDirectories(copy.getParent());
                    Files.copy(audioFile.toPath(), copy);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return ProcessResult.PERMANENT_FAIL;
            }
        };
        var fakeSearch = new com.lux032.musicautotagger.service.llm.WebSearchClient() {
            @Override public boolean hasEnabledEndpoint() { return true; }
            @Override public String name() { return "fake"; }
            @Override public SearchResponse search(String sys, String usr) {
                var resp = new SearchResponse();
                resp.setProvider("fake");
                resp.setModel("fake-model");
                resp.addCitation(Citation.of("https://musicbrainz.org/release/99", "MB", ""));
                resp.setText("""
                    {"needs_second_round":false,"candidates":[{
                      "title":"Beyond the Night","artist":"TeddyLoid, 佐倉綾音 & 井上和",
                      "artists":["TeddyLoid","佐倉綾音","井上和"],
                      "album_artist":"TeddyLoid, 佐倉綾音 & 井上和",
                      "album_artists":["TeddyLoid","佐倉綾音","井上和"],
                      "release_date":"2026-11-11","edition":"Digital single","confidence":0.95,
                      "tracks":[{"disc":1,"track":1,"title":"Beyond the Night","artist":"TeddyLoid, 佐倉綾音 & 井上和",
                        "artists":["TeddyLoid","佐倉綾音","井上和"],"duration":2}],
                      "source_urls":["https://musicbrainz.org/release/99"]
                    }],"clues":[]}
                    """);
                return resp;
            }
        };
        var online = new OnlineIdentificationService(config, queue, tagWriter, null, new LlmArtistSplitter(config), fakeSearch);

        try (RecoveryService recovery = new RecoveryService(
                config, fakeProcessor, logger, queue, new FolderAlbumCache(null, null, null), null,
                new FileSystemUtils(config), tagWriter, null, null, new LlmArtistSplitter(config), online)) {
            ProcessedRecordService recordService = new ProcessedRecordService(
                config, logger, new FolderAlbumCache(null, null, null), new CoverArtService(null, null),
                new FileSystemUtils(config), () -> null, registry);
            recordService.setRecoveryService(recovery);
            recordService.setReviewQueueService(queue);

            // Step 1: Click re-identify on old record -> fails normal recognition and moves copy to /failed_files/Beyond the Night
            List<ProcessedRecord> targets = recordService.resolveTargets(List.of(initialFailed.getAbsolutePath()), null);
            recordService.reidentify(targets);

            Path failedCopy = failedRoot.resolve("Beyond the Night/1.01 TeddyLoid - Beyond the Night.flac");
            long deadline = System.currentTimeMillis() + 5000;
            while ((!Files.isRegularFile(failedCopy) || logger.findRecordsByPaths(List.of(oldTarget.toString())).isEmpty())
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertTrue(Files.isRegularFile(failedCopy));
            assertTrue(Files.isRegularFile(oldTarget));

            // Step 2: Run online search on /failed_files/Beyond the Night and confirm multi-artist candidate
            recovery.submit(RecoveryService.SourceType.FAILED, "Beyond the Night",
                RecoveryService.RecoveryMode.ONLINE_SEARCH, false);
            long reviewDeadline = System.currentTimeMillis() + 5000;
            while (queue.list(ReviewItem.Status.PENDING_REVIEW).isEmpty() && System.currentTimeMillis() < reviewDeadline) {
                Thread.sleep(25);
            }
            ReviewItem item = queue.list(ReviewItem.Status.PENDING_REVIEW).get(0);
            recovery.confirmOnlineCandidate(item.getId(), item.getOnlineCandidates().get(0).getId(),
                "Beyond the Night", "TeddyLoid, 佐倉綾音 & 井上和", "2026-11-11", "Digital single",
                null, null, RecoveryService.DuplicatePolicy.ASK, List.of("TeddyLoid", "佐倉綾音", "井上和"));

            Path newTarget = output.resolve("TeddyLoid, 佐倉綾音 & 井上和/Beyond the Night (Digital single)/1.01 TeddyLoid, 佐倉綾音 & 井上和 - Beyond the Night.flac");
            assertTrue(Files.isRegularFile(newTarget), "新的多艺术家归档文件应已创建");
            assertFalse(Files.exists(oldTarget), "第一次刮削的旧单艺术家文件应已被自动清理");
            assertFalse(Files.exists(oldTarget.getParent()), "旧空目录应已清理");
            assertTrue(logger.findRecordsByPaths(List.of(oldTarget.toString())).isEmpty(), "旧目标上的 FAILED 记录应随文件替换一起清理");
        }
    }

    @Test
    void reidentifySkipsMonitorRecordWhenFolderAlreadyPendingReview() throws Exception {
        File src = wav(monitor.resolve("PendingAlbum/01.wav"), 2, (byte) 5);
        logger.markFileAsProcessed(src, "rec-1", "Artist", "Song", "PendingAlbum", null, null);
        ReviewQueueService queue = new ReviewQueueService(config);
        ReviewItem item = queue.enqueueRecoveryFolder(src.getParentFile().getAbsolutePath(), null, List.of(src), tagWriter, "h");
        assertNotNull(item);

        ProcessedRecordService recordService = new ProcessedRecordService(
            config, logger, new FolderAlbumCache(null, null, null), new CoverArtService(null, null),
            new FileSystemUtils(config), () -> null, registry);
        recordService.setReviewQueueService(queue);

        List<ProcessedRecord> targets = recordService.resolveTargets(List.of(src.getAbsolutePath()), null);
        Map<String, Object> preview = recordService.preview(targets);
        assertEquals(1, preview.get("reviewBlockedCount"));

        Map<String, Object> res = recordService.reidentify(targets);
        assertEquals(1, res.get("skippedUnderReview"));
        assertEquals(0, res.get("removed"));
        assertTrue(logger.isFileProcessed(src), "跳过待确认条目时不应删掉原记录");
    }
}
