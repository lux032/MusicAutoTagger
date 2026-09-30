package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQLite（默认存储模式）端到端验证：自动建表、UPSERT、方言相关 SQL、旧文件日志导入。
 */
class SqliteDatabaseModeTest {
    @TempDir
    Path tempDir;

    private MusicConfig sqliteConfig(Path logPath) throws Exception {
        // 绕过单例（会加载工作目录下的 config.properties），直接拿默认配置
        var ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MusicConfig config = ctor.newInstance();
        assertEquals("sqlite", config.getDbType(), "SQLite 应为默认存储模式");
        config.setDbSqlitePath(tempDir.resolve("db/test.db").toString());
        config.setProcessedFileLogPath(logPath.toString());
        config.setOutputDirectory(tempDir.resolve("output").toString());
        return config;
    }

    @Test
    void processedFileLifecycleWorksOnSqlite() throws Exception {
        MusicConfig config = sqliteConfig(tempDir.resolve("none.log"));
        DatabaseService db = new DatabaseService(config);
        try {
            assertTrue(db.isSqlite());
            assertTrue(Files.exists(tempDir.resolve("db/test.db")), "数据库文件应自动创建");

            ProcessedFileLogger logger = new ProcessedFileLogger(config, db);
            assertTrue(logger.isReleaseGroupIdColumnAvailable());
            assertTrue(logger.isTargetFilePathColumnAvailable());

            File source = Files.writeString(tempDir.resolve("a.flac"), "audio").toFile();
            assertFalse(logger.isFileProcessed(source));

            String staging = tempDir.resolve("staging").toFile().getAbsolutePath();
            logger.markFileAsProcessed(source, "rec-1", "Artist", "Title", "Album", null,
                staging + File.separator + "Artist" + File.separator + "a.flac");
            assertTrue(logger.isFileProcessed(source));

            // UPSERT：同一路径再次写入不应报唯一键冲突，且 target 为空时保留旧值
            logger.markFileAsProcessed(source, "rec-2", "Artist", "Title2", "Album", null, null);
            assertEquals(1L, logger.getStatistics().get("totalProcessed"));
            assertEquals("SQLite", logger.getStatistics().get("databaseType"));

            assertEquals(1, logger.findAlbumsMissingReleaseGroupId().size());
            assertEquals(1, logger.applyReleaseGroupId("Album", "rg-1"));
            assertTrue(logger.findAlbumsMissingReleaseGroupId().isEmpty());

            String finalRoot = tempDir.resolve("final").toFile().getAbsolutePath();
            logger.rebaseTargetPaths(staging, finalRoot);
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT title, recording_id, target_file_path, release_group_id, processed_time FROM processed_files")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("Title2", rs.getString("title"));
                    assertEquals("rec-2", rs.getString("recording_id"));
                    assertEquals("rg-1", rs.getString("release_group_id"));
                    assertEquals(finalRoot + File.separator + "Artist" + File.separator + "a.flac",
                        rs.getString("target_file_path"));
                    assertNotNull(rs.getTimestamp("processed_time"));
                }
                try (ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT typeof(processed_time), processed_time FROM processed_files")) {
                    assertTrue(rs.next());
                    assertEquals("text", rs.getString(1), "时间应以可读文本存储");
                    assertTrue(rs.getString(2).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}"));
                }
                try (ResultSet rs = conn.createStatement().executeQuery("PRAGMA journal_mode")) {
                    assertTrue(rs.next());
                    assertEquals("wal", rs.getString(1).toLowerCase());
                }
            }

            logger.clearTargetPathsUnder(finalRoot);
            try (Connection conn = db.getConnection();
                 ResultSet rs = conn.createStatement().executeQuery("SELECT target_file_path FROM processed_files")) {
                assertTrue(rs.next());
                assertNull(rs.getString(1));
            }

            // 清理：把记录改到 40 天前，保留 30 天应被删掉
            try (Connection conn = db.getConnection();
                 PreparedStatement ps = conn.prepareStatement("UPDATE processed_files SET processed_time = ?")) {
                ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now().minusDays(40)));
                ps.executeUpdate();
            }
            logger.cleanupOldRecords(30);
            assertEquals(0L, logger.getStatistics().get("totalProcessed"));

            logger.markFileAsProcessed(source, "rec-3", "Artist", "Title", "Album");
            logger.removeProcessedRecord(source);
            assertFalse(logger.isFileProcessed(source));
        } finally {
            db.close();
        }
    }

    @Test
    void coverArtCacheWorksOnSqlite() throws Exception {
        MusicConfig config = sqliteConfig(tempDir.resolve("none.log"));
        DatabaseService db = new DatabaseService(config);
        try {
            CoverArtCache cache = new CoverArtCache(db, tempDir.resolve("covers").toString(), config);
            byte[] data = {1, 2, 3};
            assertTrue(cache.cacheCoverByReleaseGroupId("rg", data));
            assertTrue(cache.cacheCoverByReleaseGroupId("rg", new byte[]{4, 5, 6, 7}), "重复缓存应走 UPSERT");
            assertArrayEquals(new byte[]{4, 5, 6, 7}, cache.getCachedCoverByReleaseGroupId("rg"));
            CoverArtCache.CacheStatistics stats = cache.getStatistics();
            assertEquals(1, stats.totalCached);
            assertEquals(4, stats.totalSizeBytes);

            cache.cleanupOldCache(-1); // 截止时间在未来 → 全部清理
            assertEquals(0, cache.getStatistics().totalCached);
        } finally {
            db.close();
        }
    }

    @Test
    void legacyFileLogIsImportedIntoEmptySqliteDatabase() throws Exception {
        Path source = Files.writeString(tempDir.resolve("old.flac"), "audio");
        Path log = tempDir.resolve("processed.log");
        Files.writeString(log,
            String.join("|", source.toString(), "rec", "Artist", "Title", "Album",
                "2026-01-02 03:04:05", "rg-old", "") + System.lineSeparator()
            + String.join("|", tempDir.resolve("gone.mp3").toString(), "FAILED", "err", "", "",
                "2026-01-02 03:04:06") + System.lineSeparator());

        MusicConfig config = sqliteConfig(log);
        DatabaseService db = new DatabaseService(config);
        try {
            ProcessedFileLogger logger = new ProcessedFileLogger(config, db);
            assertTrue(logger.isFileProcessed(source.toFile()));
            assertEquals(2L, logger.getStatistics().get("totalProcessed"));
        } finally {
            db.close();
        }

        // 第二次启动：表非空，不应重复导入
        DatabaseService db2 = new DatabaseService(config);
        try {
            ProcessedFileLogger logger = new ProcessedFileLogger(config, db2);
            assertEquals(2L, logger.getStatistics().get("totalProcessed"));
        } finally {
            db2.close();
        }
    }
}
