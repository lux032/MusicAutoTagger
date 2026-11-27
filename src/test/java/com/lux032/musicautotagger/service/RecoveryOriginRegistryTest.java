package com.lux032.musicautotagger.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ProcessResult;
import com.lux032.musicautotagger.util.FileSystemUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryOriginRegistryTest {

    @TempDir
    Path dir;

    private MusicConfig config;
    private Path monitor;
    private Path output;
    private Path failed;
    private Path partial;
    private Path trash;
    private RecoveryOriginRegistry originRegistry;

    @BeforeEach
    void setUp() throws Exception {
        var ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        config = ctor.newInstance();
        monitor = Files.createDirectories(dir.resolve("monitor"));
        output = Files.createDirectories(dir.resolve("output"));
        failed = Files.createDirectories(dir.resolve("failed"));
        partial = Files.createDirectories(dir.resolve("partial"));
        trash = Files.createDirectories(dir.resolve("trash"));

        config.setMonitorDirectory(monitor.toString());
        config.setOutputDirectory(output.toString());
        config.setFailedDirectory(failed.toString());
        config.setPartialDirectory(partial.toString());
        config.setRecoveryWorkDirectory(dir.resolve("work").toString());
        config.setRecoveryTrashDirectory(trash.toString());
        config.setReviewQueuePath(dir.resolve("data/review-queue.json").toString());
        config.setProcessedFileLogPath(dir.resolve("processed.log").toString());
        config.setDbType("file");
        config.setPartialRequireReadableTags(false);
        config.setEnableLLMMatching(false);
        config.setAudioNormalizeEnabled(false);

        originRegistry = RecoveryOriginRegistry.forReviewQueuePath(config.getReviewQueuePath());
    }

    private byte[] validJpeg() throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private File writeWav(Path target, byte fill) throws Exception {
        int sampleRate = 8000;
        int dataLen = sampleRate;
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

    @Test
    void batchRecordAliasLookupRemoveAndCorruptProtection() throws Exception {
        Path src1 = monitor.resolve("Album/01.flac");
        Path src2 = monitor.resolve("Album/02.flac");
        Path iso1 = failed.resolve("Album/01.flac");
        Path iso2 = failed.resolve("Album/02.flac");
        Path reprocess = dir.resolve("staging/reprocess-abc/Album/03.flac");
        Path isoReprocess = failed.resolve("Album/03.flac");
        Path legitReprocessAlbum = monitor.resolve("reprocess-edition/04.flac");
        Path isoLegitReprocess = failed.resolve("reprocess-edition/04.flac");

        Map<Path, Path> batch = new LinkedHashMap<>();
        batch.put(iso1, src1);
        batch.put(iso2, src2);
        batch.put(isoReprocess, reprocess);
        batch.put(isoLegitReprocess, legitReprocessAlbum);
        originRegistry.recordAllFromMonitor(batch, monitor);

        assertEquals(src1.toAbsolutePath().normalize(), originRegistry.findOrigin(iso1).orElseThrow());
        assertEquals(src2.toAbsolutePath().normalize(), originRegistry.findOrigin(iso2).orElseThrow());
        assertTrue(originRegistry.findOrigin(isoReprocess).isEmpty(), "staging/reprocess-* 暂存路径不得登记为监控源");
        assertEquals(legitReprocessAlbum.toAbsolutePath().normalize(),
            originRegistry.findOrigin(isoLegitReprocess).orElseThrow(),
            "监控目录内合法命名为 reprocess-* 的专辑不得被误杀");

        // removeMatching 仅删除值匹配的快照，不误删并发写入的新血缘
        Path newerOrigin = monitor.resolve("Album/01-new.flac");
        originRegistry.record(iso1.toString(), newerOrigin.toString());
        originRegistry.removeMatching(Map.of(iso1, src1, iso2, src2));
        assertEquals(newerOrigin.toAbsolutePath().normalize(), originRegistry.findOrigin(iso1).orElseThrow(),
            "新血缘不应被旧快照清理误删");
        assertTrue(originRegistry.findOrigin(iso2).isEmpty());
        originRegistry.record(iso1.toString(), src1.toString());
        originRegistry.record(iso2.toString(), src2.toString());

        // 别名传递（如 single-<jobId> 工作副本）
        Path singleCopy = dir.resolve("work/single-1/01.flac");
        originRegistry.recordAlias(iso1, singleCopy);
        assertEquals(src1.toAbsolutePath().normalize(), originRegistry.findOrigin(singleCopy).orElseThrow());

        // 查目录范围并清理
        Map<Path, Path> underAlbum = originRegistry.findOriginsUnder(failed.resolve("Album"));
        assertEquals(2, underAlbum.size());
        originRegistry.removeUnder(failed.resolve("Album"));
        assertTrue(originRegistry.findOrigin(iso1).isEmpty());
        assertTrue(originRegistry.findOrigin(iso2).isEmpty());
        assertEquals(src1.toAbsolutePath().normalize(), originRegistry.findOrigin(singleCopy).orElseThrow());

        // 损坏 JSON 文件时拒绝覆写清空
        Path storeFile = Path.of(config.getReviewQueuePath()).toAbsolutePath().resolveSibling("recovery-origins.json");
        Files.writeString(storeFile, "{corrupt json");
        originRegistry.record(iso1.toString(), src1.toString());
        originRegistry.remove(singleCopy);
        assertEquals("{corrupt json", Files.readString(storeFile));
    }

    @Test
    void allIsolationEntryPointsRecordOriginMappings() throws Exception {
        FileSystemUtils fs = new FileSystemUtils(config);
        TagWriterService tagWriter = new TagWriterService(config);
        ProcessedFileLogger logger = new ProcessedFileLogger(config, null);
        CoverArtService coverService = new CoverArtService(null, null);
        FailedFileHandler handler = new FailedFileHandler(config, tagWriter, coverService, logger, fs);

        // 1. 失败目录：专辑复制 + 散落单文件复制
        File albumTrack = writeWav(monitor.resolve("Sub/AlbumFail/01.wav"), (byte) 1);
        Files.writeString(monitor.resolve("Sub/AlbumFail/notes.txt"), "readme");
        handler.copyFailedFolderToFailedDirectory(albumTrack.getParentFile());
        assertEquals(albumTrack.toPath().toAbsolutePath().normalize(),
            originRegistry.findOrigin(failed.resolve("Sub/AlbumFail/01.wav")).orElseThrow());
        assertTrue(originRegistry.findOrigin(failed.resolve("Sub/AlbumFail/notes.txt")).isEmpty(),
            "非音频文件不记录血缘");

        File looseFailed = writeWav(monitor.resolve("loose.wav"), (byte) 2);
        handler.copyFailedFileToFailedDirectory(looseFailed);
        assertEquals(looseFailed.toPath().toAbsolutePath().normalize(),
            originRegistry.findOrigin(failed.resolve("loose.wav")).orElseThrow());

        // 2. 部分识别目录：扁平专辑目录 + 散落单文件转码改扩展名 (.wav -> .flac)
        byte[] realJpeg = validJpeg();
        File partialAlbumTrack = writeWav(monitor.resolve("Deep/PartialAlbum/01.wav"), (byte) 3);
        Files.write(monitor.resolve("Deep/PartialAlbum/cover.jpg"), realJpeg);
        assertEquals(FailedFileHandler.PartialHandlingResult.STORED,
            handler.handlePartialRecognitionAlbum(partialAlbumTrack.getParentFile()));
        assertEquals(partialAlbumTrack.toPath().toAbsolutePath().normalize(),
            originRegistry.findOrigin(partial.resolve("PartialAlbum/01.wav")).orElseThrow());

        File partialLooseOrig = writeWav(monitor.resolve("SubLoose/song.wav"), (byte) 4);
        Files.write(monitor.resolve("SubLoose/cover.jpg"), realJpeg);
        Path fakeNormalizedFlac = Files.createDirectories(dir.resolve("norm-temp")).resolve("song.flac");
        Files.write(fakeNormalizedFlac, ArchiveDuplicateDetectorTest.flac(44));
        assertEquals(FailedFileHandler.PartialHandlingResult.STORED,
            handler.handlePartialRecognitionFile(partialLooseOrig, fakeNormalizedFlac.toFile()));
        assertEquals(partialLooseOrig.toPath().toAbsolutePath().normalize(),
            originRegistry.findOrigin(partial.resolve("SubLoose/song.flac")).orElseThrow());

        // 3. FileMonitorService.moveToFailedDirectory（含同名撞名追加时间戳后缀）
        File retryOrig = writeWav(monitor.resolve("retry.wav"), (byte) 5);
        Files.writeString(failed.resolve("retry.wav"), "existing collision");
        FileMonitorService monitorService = new FileMonitorService(config, logger);
        try {
            Method moveToFailed = FileMonitorService.class.getDeclaredMethod("moveToFailedDirectory", File.class);
            moveToFailed.setAccessible(true);
            moveToFailed.invoke(monitorService, retryOrig);
        } finally {
            monitorService.stop();
        }
        Path timestampedCopy;
        try (var s = Files.list(failed)) {
            timestampedCopy = s.filter(p -> p.getFileName().toString().startsWith("retry_")
                && p.getFileName().toString().endsWith(".wav")).findFirst().orElseThrow();
        }
        assertEquals(retryOrig.toPath().toAbsolutePath().normalize(),
            originRegistry.findOrigin(timestampedCopy).orElseThrow());

        // 4. RecoveryService.runJob 单文件工作区 single-<jobId> 透传血缘（通过 CompletableFuture 与终态等待消除跨线程竞态）
        record ObservedSingle(Path workspaceFile, Path resolvedOrigin) { }
        java.util.concurrent.CompletableFuture<ObservedSingle> observed = new java.util.concurrent.CompletableFuture<>();
        AudioFileProcessorService spyProcessor = new AudioFileProcessorService(
            config, null, null, tagWriter, null, logger, null, coverService, null, handler, fs,
            new FolderAlbumCache(null, null, null)) {
            @Override
            public ProcessResult processRecoveryFile(File audioFile, File albumRootDir) {
                observed.complete(new ObservedSingle(
                    audioFile.toPath(),
                    originRegistry.findOrigin(audioFile).orElse(null)));
                return ProcessResult.PERMANENT_FAIL;
            }
        };
        ObservedSingle snapshot;
        try (RecoveryService recovery = new RecoveryService(
                config, spyProcessor, logger, new ReviewQueueService(config),
                new FolderAlbumCache(null, null, null), handler, fs, tagWriter, null, null)) {
            String jobId = recovery.submit(RecoveryService.SourceType.FAILED, "loose.wav",
                RecoveryService.RecoveryMode.REIDENTIFY, false);
            assertNotNull(jobId);
            snapshot = observed.get(5, java.util.concurrent.TimeUnit.SECONDS);
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                boolean finished = recovery.listJobs().stream()
                    .anyMatch(j -> jobId.equals(j.get("id"))
                        && !"QUEUED".equals(j.get("status")) && !"RUNNING".equals(j.get("status")));
                if (finished) break;
                Thread.sleep(30);
            }
        }
        assertNotNull(snapshot.workspaceFile());
        assertEquals(looseFailed.toPath().toAbsolutePath().normalize(), snapshot.resolvedOrigin());
        assertTrue(originRegistry.findOrigin(snapshot.workspaceFile()).isEmpty(), "单文件任务结束后应清理工作区血缘");
    }

    @Test
    void trashMoveAndRestorePreservesOriginsAndSupportsLegacyManifest() throws Exception {
        FileSystemUtils fs = new FileSystemUtils(config);
        RecoveryTrashService trashService = new RecoveryTrashService(
            config, fs, new Gson(),
            (type, rel) -> (type == RecoveryService.SourceType.PARTIAL ? partial : failed).resolve(rel).toFile(),
            target -> {
                if (target.isFile()) return List.of(target);
                List<File> out = new java.util.ArrayList<>();
                fs.collectAudioFilesForMarking(target, out);
                return out;
            },
            () -> { },
            originRegistry);

        // 目录条目往返回收站
        Path origTrack = monitor.resolve("AlbumTrash/Disc 1/01.flac");
        Path failedDir = failed.resolve("AlbumTrash");
        Path failedTrack = failedDir.resolve("Disc 1/01.flac");
        Files.createDirectories(failedTrack.getParent());
        Files.write(failedTrack, ArchiveDuplicateDetectorTest.flac(7));
        originRegistry.record(failedTrack.toString(), origTrack.toString());

        trashService.moveToTrash(failedDir.toFile(), "job1", RecoveryService.SourceType.FAILED, "AlbumTrash");
        assertFalse(Files.exists(failedDir));
        assertTrue(originRegistry.findOrigin(failedTrack).isEmpty(), "入回收站后应移除活态血缘");

        List<Map<String, Object>> trashItems = trashService.listTrash();
        assertEquals(1, trashItems.size());
        String trashId = (String) trashItems.get(0).get("id");
        trashService.restoreFromTrash(trashId);
        assertTrue(Files.isRegularFile(failedTrack));
        assertEquals(origTrack.toAbsolutePath().normalize(), originRegistry.findOrigin(failedTrack).orElseThrow(),
            "从回收站还原后应恢复血缘映射");

        // 兼容无 origins 字段的旧版 manifest
        Path legacyFile = failed.resolve("legacy.flac");
        Files.write(legacyFile, ArchiveDuplicateDetectorTest.flac(8));
        Path trashEntry = trash.resolve("1700000000000_99_old_FAILED_legacy.flac");
        Files.move(legacyFile, trashEntry);
        JsonObject legacyManifest = new JsonObject();
        legacyManifest.addProperty("sourceType", "FAILED");
        legacyManifest.addProperty("relativePath", "legacy.flac");
        legacyManifest.addProperty("trashedAt", 1700000000000L);
        Files.writeString(trashEntry.resolveSibling(trashEntry.getFileName() + ".json"), legacyManifest.toString());

        assertDoesNotThrow(() -> trashService.restoreFromTrash(trashEntry.getFileName().toString()));
        assertTrue(Files.isRegularFile(legacyFile));
        assertTrue(originRegistry.findOrigin(legacyFile).isEmpty());
    }
}
