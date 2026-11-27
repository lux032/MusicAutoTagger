package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ProcessedRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessedFileLoggerTest {
    @TempDir
    Path tempDir;

    @Test
    void releaseGroupBackfillPreservesTargetPathColumn() throws Exception {
        Path source = Files.writeString(tempDir.resolve("source.flac"), "audio");
        Path target = Files.createDirectories(tempDir.resolve("output/Artist/Album"))
            .resolve("target.flac");
        Files.writeString(target, "tagged audio");
        Path log = tempDir.resolve("processed.log");
        Files.writeString(log, String.join("|", source.toString(), "recording", "Artist", "Title",
            "Album", "2026-09-20 17:47:30", "", target.toString()) + System.lineSeparator());

        ProcessedFileLogger logger = new ProcessedFileLogger(fileConfig(log, tempDir.resolve("output")), null);
        assertEquals(1, logger.applyReleaseGroupId("Album", "rgid-1"));

        String[] parts = row(log);
        assertEquals(8, parts.length);
        assertEquals("rgid-1", parts[6]);
        assertEquals(target.toString(), parts[7]);
    }

    @Test
    void targetPathNeverReplacesSourceDeduplicationKey() throws Exception {
        Path source = Files.writeString(tempDir.resolve("incoming.flac"), "source bytes");
        Path target = Files.writeString(tempDir.resolve("archived.flac"), "target bytes");
        Path log = tempDir.resolve("processed.log");
        ProcessedFileLogger logger = new ProcessedFileLogger(fileConfig(log, tempDir.resolve("unused")), null);

        logger.markFileAsProcessed(source.toFile(), "recording", "Artist", "Title", "Album",
            null, target.toString());

        String[] parts = row(log);
        assertEquals(source.toFile().getAbsolutePath(), parts[0]);
        assertEquals(target.toFile().getAbsolutePath(), parts[7]);
        assertTrue(logger.isFileProcessed(source.toFile()));
        assertFalse(logger.isFileProcessed(target.toFile()));
    }

    @Test
    void startupDoesNotScanOutputDirectoryToBackfillHistoricalRows() throws Exception {
        Path output = Files.createDirectories(tempDir.resolve("output/Artist/NIGHTBREAK"));
        Files.writeString(output.resolve("01. Mori Calliope - NIGHTBREAK.flac"), "tagged");
        Path source = tempDir.resolve("download/NIGHTBREAK.flac");
        Path log = tempDir.resolve("processed.log");
        String historicalRow = String.join("|", source.toString(), "recording", "Mori Calliope",
            "NIGHTBREAK", "NIGHTBREAK", "2026-09-20 17:47:30", "") + System.lineSeparator();
        Files.writeString(log, historicalRow);

        new ProcessedFileLogger(fileConfig(log, tempDir.resolve("output")), null);

        assertEquals(historicalRow, Files.readString(log));
    }

    @Test
    void recoveryRebaseAndRollbackClearOnlyTargetColumn() throws Exception {
        Path source = Files.writeString(tempDir.resolve("source.flac"), "audio");
        Path workspace = tempDir.resolve("work/Artist/Album/track.flac").toAbsolutePath();
        Path output = tempDir.resolve("output/Artist/Album/track.flac").toAbsolutePath();
        Path log = tempDir.resolve("processed.log");
        ProcessedFileLogger logger = new ProcessedFileLogger(fileConfig(log, tempDir.resolve("unused")), null);
        logger.markFileAsProcessed(source.toFile(), "recording", "Artist", "Title", "Album", null,
            workspace.toString());

        logger.rebaseTargetPaths(tempDir.resolve("work").toString(), tempDir.resolve("output").toString());
        assertEquals(output.toString(), row(log)[7]);
        logger.clearTargetPathsUnder(tempDir.resolve("output").toString());

        String[] parts = row(log);
        assertEquals(source.toFile().getAbsolutePath(), parts[0]);
        assertEquals("", parts[7]);
    }

    @Test
    void markFileAsProcessedPreservesExplicitProcessedTimeInFileAndSqliteModes() throws Exception {
        Path source = Files.writeString(tempDir.resolve("source-time.flac"), "audio");
        Path target = Files.writeString(tempDir.resolve("target-time.flac"), "archived");
        LocalDateTime historicalTime = LocalDateTime.of(2025, 11, 12, 8, 30, 45);

        // File mode
        Path log = tempDir.resolve("processed-time.log");
        ProcessedFileLogger fileLogger = new ProcessedFileLogger(fileConfig(log, tempDir.resolve("out-file")), null);
        fileLogger.markFileAsProcessed(source.toFile(), "UNKNOWN", "fail", "source-time.flac", "Unknown Album");
        fileLogger.markFileAsProcessed(source.toFile(), "ONLINE_SEARCH", "HIMEHINA", "VAMPY", "VAMPY",
            null, target.toString(), historicalTime);
        fileLogger.markFileAsProcessed(source.toFile(), "ONLINE_SEARCH", "HIMEHINA", "VAMPY", "VAMPY",
            null, target.toString(), historicalTime);
        List<ProcessedRecord> fileRecs = fileLogger.loadAllRecords();
        assertEquals(1, fileRecs.size());
        assertEquals("2025-11-12 08:30:45", fileRecs.get(0).processedTime);
        assertEquals(historicalTime, fileLogger.parseProcessedTime(fileRecs.get(0).processedTime));
        assertNull(fileLogger.parseProcessedTime("invalid-time"));

        // SQLite mode (also verifies idempotent UPSERT and missing source file fallback)
        MusicConfig sqliteCfg = sqliteConfig(tempDir.resolve("processed-time.db"), tempDir.resolve("out-db"));
        try (DatabaseService db = new DatabaseService(sqliteCfg)) {
            ProcessedFileLogger dbLogger = new ProcessedFileLogger(sqliteCfg, db);
            dbLogger.markFileAsProcessed(source.toFile(), "UNKNOWN", "fail", "source-time.flac", "Unknown Album");
            dbLogger.markFileAsProcessed(source.toFile(), "ONLINE_SEARCH", "HIMEHINA", "VAMPY", "VAMPY",
                "rg-1", target.toString(), historicalTime);
            dbLogger.markFileAsProcessed(source.toFile(), "ONLINE_SEARCH", "HIMEHINA", "VAMPY", "VAMPY",
                "rg-1", target.toString(), historicalTime);

            List<ProcessedRecord> dbRecs = dbLogger.findRecordsByPaths(List.of(source.toFile().getAbsolutePath()));
            assertEquals(1, dbRecs.size());
            assertEquals("2025-11-12 08:30:45", dbRecs.get(0).processedTime);
            assertEquals(target.toFile().getAbsolutePath(), dbRecs.get(0).targetFilePath);

            // Unmounted/non-existent source file must still persist safely in DB mode AND preserve existing source hash/size on update
            Path offlineExisting = Files.writeString(tempDir.resolve("offline-existing.flac"), "original source content 12345");
            dbLogger.markFileAsProcessed(offlineExisting.toFile(), "UNKNOWN", "fail", "offline-existing.flac", "Unknown Album");
            String origHash;
            long origSize;
            try (var conn = db.getConnection();
                 var ps = conn.prepareStatement("SELECT file_hash, file_size FROM processed_files WHERE file_path = ?")) {
                ps.setString(1, offlineExisting.toFile().getAbsolutePath());
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    origHash = rs.getString(1);
                    origSize = rs.getLong(2);
                }
            }
            assertFalse(origHash.isEmpty());
            assertTrue(origSize > 0);
            Files.delete(offlineExisting); // simulate source now offline

            dbLogger.markFileAsProcessed(offlineExisting.toFile(), "ONLINE_SEARCH", "HIMEHINA", "Song", "VAMPY",
                null, target.toString(), historicalTime);
            try (var conn = db.getConnection();
                 var ps = conn.prepareStatement("SELECT file_hash, file_size FROM processed_files WHERE file_path = ?")) {
                ps.setString(1, offlineExisting.toFile().getAbsolutePath());
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(origHash, rs.getString(1), "离线更新不得用归档哈希覆写原监控源 file_hash");
                    assertEquals(origSize, rs.getLong(2), "离线更新不得用归档体积覆写原监控源 file_size");
                }
            }

            Path missingSource = tempDir.resolve("offline/missing.flac");
            dbLogger.markFileAsProcessed(missingSource.toFile(), "ONLINE_SEARCH", "HIMEHINA", "Song", "VAMPY",
                null, target.toString(), historicalTime);
            List<ProcessedRecord> offlineRecs = dbLogger.findRecordsByPaths(List.of(missingSource.toFile().getAbsolutePath()));
            assertEquals(1, offlineRecs.size());
            assertEquals(ProcessedRecord.Status.SUCCESS, offlineRecs.get(0).status);
            assertEquals("2025-11-12 08:30:45", offlineRecs.get(0).processedTime);
        }
    }

    private MusicConfig sqliteConfig(Path dbPath, Path output) throws Exception {
        Files.createDirectories(output);
        var ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MusicConfig config = ctor.newInstance();
        config.setDbType("sqlite");
        config.setDbSqlitePath(dbPath.toString());
        config.setOutputDirectory(output.toString());
        config.setSupportedFormats(new String[] {"flac", "mp3"});
        return config;
    }

    private String[] row(Path log) throws Exception {
        return Files.readString(log).strip().split("\\|", -1);
    }

    private MusicConfig fileConfig(Path log, Path output) throws Exception {
        Files.createDirectories(output);
        MusicConfig config = MusicConfig.getInstance();
        config.setDbType("file");
        config.setProcessedFileLogPath(log.toString());
        config.setOutputDirectory(output.toString());
        config.setSupportedFormats(new String[] {"flac", "mp3"});
        return config;
    }
}
