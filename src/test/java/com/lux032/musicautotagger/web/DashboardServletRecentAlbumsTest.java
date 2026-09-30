package com.lux032.musicautotagger.web;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.service.ProcessedFileLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DashboardServletRecentAlbumsTest {
    @TempDir
    Path tempDir;

    @Test
    @SuppressWarnings("unchecked")
    void fileAggregationPrefersAnyTargetPathOverSourcePath() throws Exception {
        Path log = tempDir.resolve("processed.log");
        Path sourceA = tempDir.resolve("aaa-source.flac");
        Path sourceB = tempDir.resolve("bbb-source.flac");
        Path target = tempDir.resolve("zzz-target.flac");
        Files.writeString(log,
            String.join("|", sourceA.toString(), "recording-a", "Artist", "One", "Album",
                "2026-09-20 17:00:00", "", "") + System.lineSeparator()
            + String.join("|", sourceB.toString(), "recording-b", "Artist", "Two", "Album",
                "2026-09-20 18:00:00", "", target.toString()) + System.lineSeparator());

        MusicConfig config = MusicConfig.getInstance();
        config.setDbType("file");
        config.setProcessedFileLogPath(log.toString());
        config.setOutputDirectory(tempDir.resolve("empty-output").toString());
        ProcessedFileLogger logger = new ProcessedFileLogger(config, null);
        DashboardServlet servlet = new DashboardServlet(logger, null, null, config, null);
        Method method = DashboardServlet.class.getDeclaredMethod("getRecentAlbumsFromLog", int.class);
        method.setAccessible(true);

        List<Map<String, Object>> albums = (List<Map<String, Object>>) method.invoke(servlet, 12);

        assertEquals(1, albums.size());
        assertEquals(target.toString(), albums.get(0).get("path"));
        assertEquals(2, albums.get(0).get("trackCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void quickScanRecordWithoutRecordingIdIsShownButFailuresAreNot() throws Exception {
        Path log = tempDir.resolve("processed-quickscan.log");
        Files.writeString(log,
            String.join("|", tempDir.resolve("01. SPIN.flac").toString(), "", "Kroi", "SPIN", "SPIN",
                "2026-09-28 00:16:38", "rg-spin", "") + System.lineSeparator()
            + String.join("|", tempDir.resolve("bad.flac").toString(), "UNKNOWN", "识别失败", "bad", "Broken",
                "2026-09-28 00:20:00", "", "") + System.lineSeparator());

        MusicConfig config = MusicConfig.getInstance();
        config.setDbType("file");
        config.setProcessedFileLogPath(log.toString());
        config.setOutputDirectory(tempDir.resolve("empty-output").toString());
        ProcessedFileLogger logger = new ProcessedFileLogger(config, null);
        DashboardServlet servlet = new DashboardServlet(logger, null, null, config, null);
        Method method = DashboardServlet.class.getDeclaredMethod("getRecentAlbumsFromLog", int.class);
        method.setAccessible(true);

        List<Map<String, Object>> albums = (List<Map<String, Object>>) method.invoke(servlet, 12);

        assertEquals(1, albums.size());
        assertEquals("SPIN", albums.get(0).get("album"));
        assertEquals("Kroi", albums.get(0).get("artist"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void featuredTrackDoesNotTurnSoloAlbumIntoVariousArtists() throws Exception {
        Path out = tempDir.resolve("out");
        Path log = tempDir.resolve("processed-feat.log");
        Files.writeString(log,
            String.join("|", tempDir.resolve("a.flac").toString(), "r1", "Suzuki", "One", "ULTRA FLASH",
                "2026-09-30 17:00:00", "rg", out.resolve("Suzuki/ULTRA FLASH/1.01 a.flac").toString())
                + System.lineSeparator()
            + String.join("|", tempDir.resolve("b.flac").toString(), "r2", "Suzuki, Ito", "Two", "ULTRA FLASH",
                "2026-09-30 17:01:00", "rg", out.resolve("Suzuki/ULTRA FLASH/1.12 b.flac").toString())
                + System.lineSeparator()
            + String.join("|", tempDir.resolve("c.flac").toString(), "r3", "X", "Three", "Comp",
                "2026-09-30 17:02:00", "rg2", out.resolve("Various Artists/Comp/1.01 c.flac").toString())
                + System.lineSeparator());

        MusicConfig config = MusicConfig.getInstance();
        config.setDbType("file");
        config.setProcessedFileLogPath(log.toString());
        config.setOutputDirectory(out.toString());
        ProcessedFileLogger logger = new ProcessedFileLogger(config, null);
        DashboardServlet servlet = new DashboardServlet(logger, null, null, config, null);
        Method method = DashboardServlet.class.getDeclaredMethod("getRecentAlbumsFromLog", int.class);
        method.setAccessible(true);

        List<Map<String, Object>> albums = (List<Map<String, Object>>) method.invoke(servlet, 12);
        Map<String, Object> ultra = albums.stream().filter(a -> "ULTRA FLASH".equals(a.get("album"))).findFirst().orElseThrow();
        Map<String, Object> comp = albums.stream().filter(a -> "Comp".equals(a.get("album"))).findFirst().orElseThrow();
        assertEquals("Suzuki", ultra.get("artist"));
        assertEquals("Various Artists", comp.get("artist"));
    }
}
