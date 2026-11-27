package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ProcessedRecord;
import com.lux032.musicautotagger.util.FileSystemUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ProcessedRecordService.preview 在文件夹视图与文件视图下准确统计 nonMbConfirmedCount。
 */
class ProcessedRecordServiceTest {

    @TempDir
    Path tempDir;

    private DatabaseService db;

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
            db = null;
        }
    }

    @Test
    void previewCountsNonMbConfirmedRecordsInFileAndFolderViews() throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MusicConfig config = ctor.newInstance();
        Path monitor = Files.createDirectories(tempDir.resolve("monitor"));
        Path output = Files.createDirectories(tempDir.resolve("output"));
        config.setDbType("sqlite");
        config.setDbSqlitePath(tempDir.resolve("records.db").toString());
        config.setMonitorDirectory(monitor.toString());
        config.setOutputDirectory(output.toString());
        config.setReviewQueuePath(tempDir.resolve("data/review-queue.json").toString());

        db = new DatabaseService(config);
        ProcessedFileLogger logger = new ProcessedFileLogger(config, db);
        ProcessedRecordService service = new ProcessedRecordService(
            config, logger, null, null, new FileSystemUtils(config), () -> null);

        // Folder A: 2 ONLINE_SEARCH + 1 LOCAL_TAGS
        Path folderA = Files.createDirectories(monitor.resolve("VAMPY"));
        Path a1 = Files.writeString(folderA.resolve("01.flac"), "a1");
        Path a2 = Files.writeString(folderA.resolve("02.flac"), "a2");
        Path a3 = Files.writeString(folderA.resolve("03.flac"), "a3");
        logger.markFileAsProcessed(a1.toFile(), "ONLINE_SEARCH", "HIMEHINA", "T1", "VAMPY", null, output.resolve("HIMEHINA/VAMPY/01.flac").toString());
        logger.markFileAsProcessed(a2.toFile(), "ONLINE_SEARCH", "HIMEHINA", "T2", "VAMPY", null, output.resolve("HIMEHINA/VAMPY/02.flac").toString());
        logger.markFileAsProcessed(a3.toFile(), "LOCAL_TAGS", "HIMEHINA", "T3", "VAMPY", null, output.resolve("HIMEHINA/VAMPY/03.flac").toString());

        // Folder B: 1 normal MB UUID + 1 FAILED + 1 REVIEW_REJECTED
        Path folderB = Files.createDirectories(monitor.resolve("MixedAlbum"));
        Path b1 = Files.writeString(folderB.resolve("01.flac"), "b1");
        Path b2 = Files.writeString(folderB.resolve("02.flac"), "b2");
        Path b3 = Files.writeString(folderB.resolve("03.flac"), "b3");
        logger.markFileAsProcessed(b1.toFile(), "11111111-2222-3333-4444-555555555555", "ArtistB", "S1", "MixedAlbum", "rg-b", output.resolve("ArtistB/MixedAlbum/01.flac").toString());
        logger.markFileAsProcessed(b2.toFile(), "FAILED", "fail", "02.flac", "Unknown Album");
        logger.markFileAsProcessed(b3.toFile(), "REVIEW_REJECTED", "ArtistB", "S3", "MixedAlbum");

        // File view: select a1 (ONLINE_SEARCH) + b1 (MB) + b2 (FAILED)
        List<ProcessedRecord> fileTargets = service.resolveTargets(
            List.of(a1.toString(), b1.toString(), b2.toString()), null, null);
        Map<String, Object> filePreview = service.preview(fileTargets);
        assertEquals(3, filePreview.get("total"));
        assertEquals(2, filePreview.get("successCount"));
        assertEquals(1, filePreview.get("failedCount"));
        assertEquals(1, filePreview.get("nonMbConfirmedCount"));

        // Folder view: explicit folder selection for folderA
        List<ProcessedRecord> folderATargets = service.resolveTargets(null, List.of(folderA.toString()), null);
        Map<String, Object> folderAPreview = service.preview(folderATargets);
        assertEquals(3, folderAPreview.get("total"));
        assertEquals(3, folderAPreview.get("nonMbConfirmedCount"));

        // Folder view: filter all folders
        ProcessedRecordQuery folderFilter = new ProcessedRecordQuery();
        folderFilter.view = "folder";
        List<ProcessedRecord> allFolderTargets = service.resolveTargets(null, null, folderFilter);
        Map<String, Object> allFolderPreview = service.preview(allFolderTargets);
        assertEquals(6, allFolderPreview.get("total"));
        assertEquals(2, allFolderPreview.get("folderCount"));
        assertEquals(4, allFolderPreview.get("successCount"));
        assertEquals(3, allFolderPreview.get("nonMbConfirmedCount"),
            "仅统计 SUCCESS 且 recordingId 为 ONLINE_SEARCH 或 LOCAL_TAGS 的记录");

        // Folder view: folderB only -> 0 non-MB confirmed
        List<ProcessedRecord> folderBTargets = service.resolveTargets(null, List.of(folderB.toString()), null);
        assertEquals(0, service.preview(folderBTargets).get("nonMbConfirmedCount"));
    }
}
