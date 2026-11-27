package com.lux032.musicautotagger.service;

import com.google.gson.Gson;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ProcessedRecord;
import com.lux032.musicautotagger.model.ReviewItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;

import static com.lux032.musicautotagger.service.ArchiveDuplicateDetectorTest.flac;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证启动期历史分裂记录幂等对账器（证据链 1 活态/归档队列、证据链 2 整夹 FLAC MD5、
 * 已删源文件清理、多目录歧义与部分匹配跳过、不可访问跳过、幂等重复执行及损坏行隔离）。
 */
class RecoveryRecordReconcilerTest {

    @TempDir
    Path tempDir;

    private MusicConfig config;
    private Path monitorDir;
    private Path outputDir;
    private Path failedDir;
    private Path partialDir;
    private DatabaseService db;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        config = ctor.newInstance();
        monitorDir = Files.createDirectories(tempDir.resolve("monitor"));
        outputDir = Files.createDirectories(tempDir.resolve("output"));
        failedDir = Files.createDirectories(tempDir.resolve("failed"));
        partialDir = Files.createDirectories(tempDir.resolve("partial"));

        config.setDbType("file");
        config.setMonitorDirectory(monitorDir.toString());
        config.setOutputDirectory(outputDir.toString());
        config.setFailedDirectory(failedDir.toString());
        config.setPartialDirectory(partialDir.toString());
        config.setReviewQueuePath(tempDir.resolve("data/review-queue.json").toString());
        config.setReviewStagingDirectory(tempDir.resolve("data/review-staging").toString());
        config.setProcessedFileLogPath(tempDir.resolve("data/processed.log").toString());
        config.setDbSqlitePath(tempDir.resolve("data/reconcile.db").toString());
        config.setSupportedFormats(new String[] {"flac", "mp3"});
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
            db = null;
        }
    }

    private ProcessedFileLogger createLogger(String mode) {
        config.setDbType(mode);
        if ("sqlite".equals(mode)) {
            db = new DatabaseService(config);
            return new ProcessedFileLogger(config, db);
        }
        return new ProcessedFileLogger(config, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "file"})
    void evidence2MergesWholeFolderFlacMd5PreservingTimeWithoutTouchingFilesAndIsIdempotent(String mode) throws Exception {
        ProcessedFileLogger logger = createLogger(mode);
        ReviewQueueService queue = new ReviewQueueService(config);

        // Simulate VAMPY split history: 2-track FLAC album failed in monitor, archived in output
        Path monAlbum = Files.createDirectories(monitorDir.resolve("HIMEHINA — VAMPY (2026)"));
        Path mon1 = writeFlac(monAlbum.resolve("01. VAMPY.flac"), 101);
        Path mon2 = writeFlac(monAlbum.resolve("02. Song Two.flac"), 102);

        Path outAlbum = Files.createDirectories(outputDir.resolve("HIMEHINA/VAMPY"));
        Path out1 = writeFlac(outAlbum.resolve("1.01 HIMEHINA - VAMPY.flac"), 101);
        Path out2 = writeFlac(outAlbum.resolve("1.02 HIMEHINA - Song Two.flac"), 102);

        LocalDateTime archiveTime = LocalDateTime.of(2026, 3, 15, 20, 10, 5);
        logger.markFileAsProcessed(mon1.toFile(), "UNKNOWN", "识别失败 - 整个专辑", "01. VAMPY.flac", "Unknown Album");
        logger.markFileAsProcessed(mon2.toFile(), "UNKNOWN", "识别失败 - 整个专辑", "02. Song Two.flac", "Unknown Album");
        logger.markFileAsProcessed(out1.toFile(), "ONLINE_SEARCH", "HIMEHINA", "VAMPY", "VAMPY", "rg-vampy", out1.toString(), archiveTime);
        logger.markFileAsProcessed(out2.toFile(), "ONLINE_SEARCH", "HIMEHINA", "Song Two", "VAMPY", "rg-vampy", out2.toString(), archiveTime);

        byte[] mon1Bytes = Files.readAllBytes(mon1);
        byte[] mon2Bytes = Files.readAllBytes(mon2);
        byte[] out1Bytes = Files.readAllBytes(out1);
        byte[] out2Bytes = Files.readAllBytes(out2);

        RecoveryRecordReconciler reconciler = new RecoveryRecordReconciler(config, logger, queue);
        RecoveryRecordReconciler.ReconcileResult first = reconciler.reconcile();
        assertEquals(1, first.mergedAlbums());
        assertEquals(2, first.mergedRecords());
        assertEquals(0, first.cleanedDeletedSourceRecords());

        List<ProcessedRecord> all = logger.loadAllRecords();
        assertEquals(2, all.size(), "分裂的 4 条记录应收敛为 2 条监控主记录");
        ProcessedRecord r1 = logger.findRecordsByPaths(List.of(mon1.toString())).get(0);
        ProcessedRecord r2 = logger.findRecordsByPaths(List.of(mon2.toString())).get(0);
        assertEquals(ProcessedRecord.Status.SUCCESS, r1.status);
        assertEquals("ONLINE_SEARCH", r1.recordingId);
        assertEquals("VAMPY", r1.title);
        assertEquals("rg-vampy", r1.releaseGroupId);
        assertEquals("2026-03-15 20:10:05", r1.processedTime, "必须保留原始归档时间");
        assertEquals(out1.toString(), r1.targetFilePath, "必须精确逐曲映射到对应 MD5 的输出文件");

        assertEquals(ProcessedRecord.Status.SUCCESS, r2.status);
        assertEquals("Song Two", r2.title);
        assertEquals("2026-03-15 20:10:05", r2.processedTime);
        assertEquals(out2.toString(), r2.targetFilePath, "必须精确逐曲映射到对应 MD5 的输出文件");

        // Disk files must never be modified or deleted (verify exact bytes)
        assertArrayEquals(mon1Bytes, Files.readAllBytes(mon1));
        assertArrayEquals(mon2Bytes, Files.readAllBytes(mon2));
        assertArrayEquals(out1Bytes, Files.readAllBytes(out1));
        assertArrayEquals(out2Bytes, Files.readAllBytes(out2));

        // Second run must be completely idempotent
        RecoveryRecordReconciler.ReconcileResult second = reconciler.reconcile();
        assertFalse(second.hasChanges());
        assertEquals(2, logger.loadAllRecords().size());
    }

    @Test
    void evidence1MergesFromLiveAndArchivedReviewQueueAndCleansDeletedMonitorSources() throws Exception {
        ProcessedFileLogger logger = createLogger("sqlite");
        ReviewQueueService queue = new ReviewQueueService(config);

        // Album 1: MP3 album (no FLAC MD5!) resolved via live review-queue.json CONFIRMED item
        Path monMp3Dir = Files.createDirectories(monitorDir.resolve("Mp3Album"));
        Path monMp3_1 = Files.write(monMp3Dir.resolve("01.mp3"), new byte[] {1, 2, 3, 4});
        Path monMp3_2 = Files.write(monMp3Dir.resolve("02.mp3"), new byte[] {5, 6, 7, 8});
        Path outMp3Dir = Files.createDirectories(outputDir.resolve("Artist1/Mp3Album"));
        Path outMp3_1 = Files.write(outMp3Dir.resolve("01. Artist1 - First.mp3"), new byte[] {1, 2, 3, 4});
        Path outMp3_2 = Files.write(outMp3Dir.resolve("02. Artist1 - Second.mp3"), new byte[] {5, 6, 7, 8});

        LocalDateTime time1 = LocalDateTime.of(2026, 2, 1, 12, 0, 0);
        logger.markFileAsProcessed(monMp3_1.toFile(), "UNKNOWN", "fail", "01.mp3", "Unknown Album");
        logger.markFileAsProcessed(monMp3_2.toFile(), "UNKNOWN", "fail", "02.mp3", "Unknown Album");
        logger.markFileAsProcessed(outMp3_1.toFile(), "ONLINE_SEARCH", "Artist1", "First", "Mp3Album", null, outMp3_1.toString(), time1);
        logger.markFileAsProcessed(outMp3_2.toFile(), "ONLINE_SEARCH", "Artist1", "Second", "Mp3Album", null, outMp3_2.toString(), time1);

        ReviewItem liveItem = queue.enqueueRecoveryFolder(
            failedDir.resolve("Mp3Album").toString(), "FAILED", List.of(), null, null);
        liveItem.setCommittedOutputPaths(List.of(outMp3Dir.toString()));
        queue.markResolved(liveItem, ReviewItem.Status.CONFIRMED, "confirmed");

        // Album 2: Monitor source deleted, evidence lives in review-queue-archive.jsonl (alongside a corrupt line!)
        Path deletedMonDir = monitorDir.resolve("ArchivedDeletedAlbum");
        Path deletedMonFile = deletedMonDir.resolve("01.mp3");
        Path outDelDir = Files.createDirectories(outputDir.resolve("Artist2/ArchivedDeletedAlbum"));
        Path outDelFile = Files.write(outDelDir.resolve("01.mp3"), new byte[] {9, 9, 9});
        logger.markFileAsProcessed(deletedMonFile.toFile(), "UNKNOWN", "fail", "01.mp3", "Unknown Album");
        logger.markFileAsProcessed(outDelFile.toFile(), "LOCAL_TAGS", "Artist2", "Track", "ArchivedDeletedAlbum",
            null, outDelFile.toString(), LocalDateTime.of(2026, 1, 10, 9, 0, 0));

        ReviewItem archivedItem = new ReviewItem();
        archivedItem.setId("archived-1");
        archivedItem.setStatus(ReviewItem.Status.CONFIRMED);
        archivedItem.setRecoverySourceType("PARTIAL");
        archivedItem.setRecoverySourcePath(partialDir.resolve("ArchivedDeletedAlbum").toString());
        archivedItem.setCommittedOutputPaths(List.of(outDelDir.toString()));
        Path archiveFile = queue.getArchiveFile();
        Files.createDirectories(archiveFile.getParent());
        Files.writeString(archiveFile,
            "{corrupt json line\n" + new Gson().toJson(archivedItem) + "\n",
            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        RecoveryRecordReconciler.ReconcileResult res = new RecoveryRecordReconciler(config, logger, queue).reconcile();
        assertEquals(2, res.mergedAlbums());
        assertEquals(2, res.mergedRecords());
        assertEquals(1, res.cleanedDeletedSourceRecords());

        // Album 1 merged onto monitor MP3 files with original timestamp
        List<ProcessedRecord> mp3Recs = logger.findRecordsByPaths(List.of(monMp3_1.toString(), monMp3_2.toString()));
        assertEquals(2, mp3Recs.size());
        assertTrue(mp3Recs.stream().allMatch(r -> r.status == ProcessedRecord.Status.SUCCESS
            && "2026-02-01 12:00:00".equals(r.processedTime)));
        assertTrue(logger.findRecordsByPaths(List.of(outMp3_1.toString(), outMp3_2.toString())).isEmpty());

        // Album 2 stale monitor FAILED row deleted, output SUCCESS row preserved
        assertTrue(logger.findRecordsByPaths(List.of(deletedMonFile.toString())).isEmpty());
        List<ProcessedRecord> keptOut = logger.findRecordsByPaths(List.of(outDelFile.toString()));
        assertEquals(1, keptOut.size());
        assertEquals(ProcessedRecord.Status.SUCCESS, keptOut.get(0).status);
    }

    @Test
    void skipsAmbiguousPartialNonFlacMissingSourceAndUnmountedMonitorSafely() throws Exception {
        ProcessedFileLogger logger = createLogger("sqlite");
        ReviewQueueService queue = new ReviewQueueService(config);

        // 1. Ambiguous: same FLAC MD5 exists in TWO output folders -> must skip!
        Path monAmb = writeFlac(monitorDir.resolve("Ambiguous/01.flac"), 201);
        Path outAmb1 = writeFlac(outputDir.resolve("ArtA/Single/01.flac"), 201);
        Path outAmb2 = writeFlac(outputDir.resolve("ArtA/Album/01.flac"), 201);
        logger.markFileAsProcessed(monAmb.toFile(), "UNKNOWN", "fail", "01.flac", "Unknown Album");
        logger.markFileAsProcessed(outAmb1.toFile(), "ONLINE_SEARCH", "ArtA", "S", "Single", null, outAmb1.toString());
        logger.markFileAsProcessed(outAmb2.toFile(), "ONLINE_SEARCH", "ArtA", "S", "Album", null, outAmb2.toString());

        // 2. Partial match: monitor has 2 tracks, output only has 1 -> must skip!
        Path monPart1 = writeFlac(monitorDir.resolve("Partial/01.flac"), 202);
        Path monPart2 = writeFlac(monitorDir.resolve("Partial/02.flac"), 203);
        Path outPart1 = writeFlac(outputDir.resolve("ArtB/Partial/01.flac"), 202);
        logger.markFileAsProcessed(monPart1.toFile(), "UNKNOWN", "fail", "01.flac", "Unknown Album");
        logger.markFileAsProcessed(monPart2.toFile(), "UNKNOWN", "fail", "02.flac", "Unknown Album");
        logger.markFileAsProcessed(outPart1.toFile(), "ONLINE_SEARCH", "ArtB", "S1", "Partial", null, outPart1.toString());

        // 3. Non-FLAC without review-queue history -> must skip!
        Path monMp3 = Files.createDirectories(monitorDir.resolve("PlainMp3")).resolve("01.mp3");
        Files.write(monMp3, new byte[] {1, 2, 3});
        Path outMp3 = Files.createDirectories(outputDir.resolve("ArtC/PlainMp3")).resolve("01.mp3");
        Files.write(outMp3, new byte[] {1, 2, 3});
        logger.markFileAsProcessed(monMp3.toFile(), "UNKNOWN", "fail", "01.mp3", "Unknown Album");
        logger.markFileAsProcessed(outMp3.toFile(), "ONLINE_SEARCH", "ArtC", "S", "PlainMp3", null, outMp3.toString());

        // 4. Missing monitor source without review-queue history -> must skip!
        Path monMissing = monitorDir.resolve("MissingSrc/01.flac");
        Path outMissing = writeFlac(outputDir.resolve("ArtD/MissingSrc/01.flac"), 204);
        logger.markFileAsProcessed(monMissing.toFile(), "UNKNOWN", "fail", "01.flac", "Unknown Album");
        logger.markFileAsProcessed(outMissing.toFile(), "ONLINE_SEARCH", "ArtD", "S", "MissingSrc", null, outMissing.toString());

        // 4b. Bidirectional ambiguity: TWO failed monitor folders with identical FLAC MD5 matching ONE output folder -> must skip!
        Path monDup1 = writeFlac(monitorDir.resolve("DupSrc1/01.flac"), 210);
        Path monDup2 = writeFlac(monitorDir.resolve("DupSrc2/01.flac"), 210);
        Path outSingle = writeFlac(outputDir.resolve("ArtDup/Alb/01.flac"), 210);
        logger.markFileAsProcessed(monDup1.toFile(), "UNKNOWN", "fail", "01.flac", "Unknown Album");
        logger.markFileAsProcessed(monDup2.toFile(), "UNKNOWN", "fail", "01.flac", "Unknown Album");
        logger.markFileAsProcessed(outSingle.toFile(), "ONLINE_SEARCH", "ArtDup", "S", "Alb", null, outSingle.toString());

        // 4c. Partial disk coverage: monitor folder has 2 FLACs on disk, only 1 is FAILED -> must NOT match 1-track output!
        Path monHalf1 = writeFlac(monitorDir.resolve("HalfFailed/01.flac"), 211);
        writeFlac(monitorDir.resolve("HalfFailed/02.flac"), 212);
        Path outHalf1 = writeFlac(outputDir.resolve("ArtHalf/Half/01.flac"), 211);
        logger.markFileAsProcessed(monHalf1.toFile(), "UNKNOWN", "fail", "01.flac", "Unknown Album");
        logger.markFileAsProcessed(outHalf1.toFile(), "ONLINE_SEARCH", "ArtHalf", "S1", "Half", null, outHalf1.toString());

        // 4d. Evidence 1 PARTIAL ambiguity (both monitor/Greatest Hits and monitor/Sub/Greatest Hits exist) -> must skip!
        Path gh1 = Files.write(Files.createDirectories(monitorDir.resolve("Greatest Hits")).resolve("01.mp3"), new byte[] {1, 2});
        Path gh2 = Files.write(Files.createDirectories(monitorDir.resolve("Sub/Greatest Hits")).resolve("01.mp3"), new byte[] {3, 4});
        Path outGh = Files.write(Files.createDirectories(outputDir.resolve("ArtGH/Greatest Hits")).resolve("01.mp3"), new byte[] {3, 4});
        logger.markFileAsProcessed(gh1.toFile(), "UNKNOWN", "fail", "01.mp3", "Unknown Album");
        logger.markFileAsProcessed(gh2.toFile(), "UNKNOWN", "fail", "01.mp3", "Unknown Album");
        logger.markFileAsProcessed(outGh.toFile(), "ONLINE_SEARCH", "ArtGH", "S", "Greatest Hits", null, outGh.toString());
        ReviewItem ambigPartialItem = queue.enqueueRecoveryFolder(partialDir.resolve("Greatest Hits").toString(), "PARTIAL", List.of(), null, null);
        ambigPartialItem.setCommittedOutputPaths(List.of(outGh.getParent().toString()));
        queue.markResolved(ambigPartialItem, ReviewItem.Status.CONFIRMED, "confirmed");

        // 4e. Evidence 1 mixed-format FLAC MD5 contradiction -> must skip!
        Path mixMonDir = Files.createDirectories(monitorDir.resolve("MixContra"));
        Path mixMonMp3 = Files.write(mixMonDir.resolve("01.mp3"), new byte[] {1, 2});
        Path mixMonFlac = writeFlac(mixMonDir.resolve("02.flac"), 220);
        Path mixOutDir = Files.createDirectories(outputDir.resolve("ArtMix/MixContra"));
        Path mixOutMp3 = Files.write(mixOutDir.resolve("01.mp3"), new byte[] {1, 2});
        Path mixOutFlac = writeFlac(mixOutDir.resolve("02.flac"), 221); // Different FLAC MD5!
        logger.markFileAsProcessed(mixMonMp3.toFile(), "UNKNOWN", "fail", "01.mp3", "Unknown Album");
        logger.markFileAsProcessed(mixMonFlac.toFile(), "UNKNOWN", "fail", "02.flac", "Unknown Album");
        logger.markFileAsProcessed(mixOutMp3.toFile(), "ONLINE_SEARCH", "ArtMix", "S1", "MixContra", null, mixOutMp3.toString());
        logger.markFileAsProcessed(mixOutFlac.toFile(), "ONLINE_SEARCH", "ArtMix", "S2", "MixContra", null, mixOutFlac.toString());
        ReviewItem mixItem = queue.enqueueRecoveryFolder(failedDir.resolve("MixContra").toString(), "FAILED", List.of(), null, null);
        mixItem.setCommittedOutputPaths(List.of(mixOutDir.toString()));
        queue.markResolved(mixItem, ReviewItem.Status.CONFIRMED, "confirmed");

        // 4f. Evidence 1 reused output folder (committed path covers only 1 of 2 output records) -> must skip!
        Path reuseMon = Files.write(Files.createDirectories(monitorDir.resolve("ReuseSrc")).resolve("01.mp3"), new byte[] {7, 8});
        Path reuseOutDir = Files.createDirectories(outputDir.resolve("ArtReuse/Shared"));
        Path reuseOut1 = Files.write(reuseOutDir.resolve("01.mp3"), new byte[] {7, 8});
        Path reuseOut2 = Files.write(reuseOutDir.resolve("02.mp3"), new byte[] {9, 10});
        logger.markFileAsProcessed(reuseMon.toFile(), "UNKNOWN", "fail", "01.mp3", "Unknown Album");
        logger.markFileAsProcessed(reuseOut1.toFile(), "ONLINE_SEARCH", "ArtReuse", "S1", "Shared", null, reuseOut1.toString());
        logger.markFileAsProcessed(reuseOut2.toFile(), "ONLINE_SEARCH", "ArtReuse", "S2", "Shared", null, reuseOut2.toString());
        ReviewItem reuseItem = queue.enqueueRecoveryFolder(failedDir.resolve("ReuseSrc").toString(), "FAILED", List.of(), null, null);
        reuseItem.setCommittedOutputPaths(List.of(reuseOut1.toString()));
        queue.markResolved(reuseItem, ReviewItem.Status.CONFIRMED, "confirmed");

        // 4g. Evidence 1 missing output file on disk -> must skip!
        Path missOutMon = Files.write(Files.createDirectories(monitorDir.resolve("MissOutSrc")).resolve("01.mp3"), new byte[] {5, 5});
        Path missOutFile = outputDir.resolve("ArtMissOut/Alb/01.mp3"); // does NOT exist on disk
        logger.markFileAsProcessed(missOutMon.toFile(), "UNKNOWN", "fail", "01.mp3", "Unknown Album");
        logger.markFileAsProcessed(missOutFile.toFile(), "ONLINE_SEARCH", "ArtMissOut", "S1", "Alb", null, missOutFile.toString());
        ReviewItem missOutItem = queue.enqueueRecoveryFolder(failedDir.resolve("MissOutSrc").toString(), "FAILED", List.of(), null, null);
        missOutItem.setCommittedOutputPaths(List.of(missOutFile.toString()));
        queue.markResolved(missOutItem, ReviewItem.Status.CONFIRMED, "confirmed");

        int beforeCount = logger.loadAllRecords().size();
        RecoveryRecordReconciler.ReconcileResult res = new RecoveryRecordReconciler(config, logger, queue).reconcile();
        assertFalse(res.hasChanges(), "歧义、部分覆盖、指纹冲突、复用输出夹及缺失归档均必须跳过");
        assertEquals(beforeCount, logger.loadAllRecords().size());

        // 5. Unmounted monitor root -> must skip cleanly even if valid FLAC pair exists!
        Path monOk = writeFlac(monitorDir.resolve("Valid/01.flac"), 205);
        Path outOk = writeFlac(outputDir.resolve("ArtE/Valid/01.flac"), 205);
        logger.markFileAsProcessed(monOk.toFile(), "UNKNOWN", "fail", "01.flac", "Unknown Album");
        logger.markFileAsProcessed(outOk.toFile(), "ONLINE_SEARCH", "ArtE", "S", "Valid", null, outOk.toString());

        config.setMonitorDirectory(tempDir.resolve("offline-nas").toString());
        RecoveryRecordReconciler.ReconcileResult offlineRes = new RecoveryRecordReconciler(config, logger, queue).reconcile();
        assertFalse(offlineRes.hasChanges(), "监控根目录不可访问时必须安全跳过");
    }

    private static Path writeFlac(Path target, int md5Seed) throws Exception {
        Files.createDirectories(target.getParent());
        Files.write(target, flac(md5Seed));
        return target;
    }
}
