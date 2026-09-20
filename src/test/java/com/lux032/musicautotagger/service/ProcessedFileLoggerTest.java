package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
