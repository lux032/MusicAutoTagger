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
}
