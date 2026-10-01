package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ProcessedRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 处理记录查询 / 批量删除在 SQLite 与文件模式下行为必须一致。
 */
class ProcessedRecordQueryTest {
    @TempDir
    Path tempDir;

    private DatabaseService db;

    @AfterEach
    void close() {
        if (db != null) db.close();
    }

    private MusicConfig newConfig() throws Exception {
        var ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private ProcessedFileLogger logger(String mode) throws Exception {
        MusicConfig config = newConfig();
        config.setDbType(mode);
        config.setDbSqlitePath(tempDir.resolve("records.db").toString());
        config.setProcessedFileLogPath(tempDir.resolve("processed.log").toString());
        config.setOutputDirectory(tempDir.resolve("out").toString());
        if ("sqlite".equals(mode)) db = new DatabaseService(config);
        return new ProcessedFileLogger(config, db);
    }

    private File file(String name) throws Exception {
        Path p = tempDir.resolve("music").resolve(name);
        Files.createDirectories(p.getParent());
        if (!Files.exists(p)) Files.writeString(p, name);
        return p.toFile();
    }

    private void seed(ProcessedFileLogger logger) throws Exception {
        logger.markFileAsProcessed(file("a/01 Blue.flac"), "rec-1", "Aimer", "Blue", "Walpurgis", "rg-1", null);
        Thread.sleep(1100);
        logger.markFileAsProcessed(file("a/02 Red.flac"), "rec-2", "Aimer", "Red", "Walpurgis", "rg-1", null);
        Thread.sleep(1100);
        logger.markFileAsProcessed(file("b/x_100%.mp3"), "FAILED", "识别失败", "x_100%.mp3", "Unknown Album");
        Thread.sleep(1100);
        logger.markFileAsProcessed(file("c/whole.flac"), "CUE_SPLIT", "Cue", "whole.flac", "Live");
        Thread.sleep(1100);
        logger.markFileAsProcessed(file("d/online.flac"), "ONLINE_SEARCH", "YOASOBI", "Idol", "THE BOOK 3");
    }

    private ProcessedRecordQuery q() {
        ProcessedRecordQuery q = new ProcessedRecordQuery();
        q.limit = 50;
        return q;
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "file"})
    void countsSearchSortAndPaging(String mode) throws Exception {
        ProcessedFileLogger logger = logger(mode);
        seed(logger);

        ProcessedRecordQuery.Page all = logger.queryRecords(q());
        assertEquals(5, all.total);
        assertEquals(5L, all.counts.get("ALL"));
        assertEquals(3L, all.counts.get("SUCCESS"), "ONLINE_SEARCH 属于成功");
        assertEquals(1L, all.counts.get("FAILED"));
        assertEquals(1L, all.counts.get("OTHER"));
        assertEquals("online.flac", all.items.get(0).fileName, "默认按处理时间倒序");

        ProcessedRecordQuery failed = q();
        failed.status = ProcessedRecord.Status.FAILED;
        ProcessedRecordQuery.Page failedPage = logger.queryRecords(failed);
        assertEquals(1, failedPage.total);
        assertEquals(ProcessedRecord.Status.FAILED, failedPage.items.get(0).status);
        assertEquals(5L, failedPage.counts.get("ALL"), "计数不受状态筛选影响");

        // 关键字不区分大小写，匹配艺术家
        ProcessedRecordQuery kw = q();
        kw.keyword = "aimer";
        assertEquals(2, logger.queryRecords(kw).total);

        // LIKE 通配符必须按字面匹配
        ProcessedRecordQuery literal = q();
        literal.keyword = "_100%";
        assertEquals(1, logger.queryRecords(literal).total);
        literal.keyword = "%";
        assertEquals(1, logger.queryRecords(literal).total);

        ProcessedRecordQuery album = q();
        album.album = "Walpurgis";
        album.sort = ProcessedRecordQuery.Sort.TITLE;
        List<ProcessedRecord> albumItems = logger.queryRecords(album).items;
        assertEquals(List.of("Blue", "Red"), albumItems.stream().map(r -> r.title).toList());
        assertEquals("rg-1", albumItems.get(0).releaseGroupId);
        assertNotNull(albumItems.get(0).processedTime);

        ProcessedRecordQuery paged = q();
        paged.sort = ProcessedRecordQuery.Sort.TIME_ASC;
        paged.limit = 2;
        paged.offset = 2;
        ProcessedRecordQuery.Page p = logger.queryRecords(paged);
        assertEquals(5, p.total);
        assertEquals(List.of("x_100%.mp3", "whole.flac"), p.items.stream().map(r -> r.fileName).toList());

        ProcessedRecordQuery filterAll = q();
        filterAll.album = "Walpurgis";
        assertEquals(2, logger.findRecords(filterAll, 100).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "file"})
    void batchRemoveOnlyTouchesGivenPaths(String mode) throws Exception {
        ProcessedFileLogger logger = logger(mode);
        seed(logger);
        List<ProcessedRecord> walpurgis = logger.findRecordsByPaths(List.of(
            file("a/01 Blue.flac").getAbsolutePath(), file("a/02 Red.flac").getAbsolutePath(),
            tempDir.resolve("nope.flac").toString()));
        assertEquals(2, walpurgis.size());

        int removed = logger.removeProcessedRecords(walpurgis.stream().map(r -> r.filePath).toList());
        assertEquals(2, removed);
        assertEquals(3, logger.queryRecords(q()).total);
        assertFalse(logger.isFileProcessed(new File(walpurgis.get(0).filePath)));
        assertTrue(logger.isFileProcessed(file("d/online.flac")));
        assertEquals(0, logger.removeProcessedRecords(List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "file"})
    void serviceReidentifyRejectsUnavailableConsumerWithoutRemovingRecords(String mode) throws Exception {
        ProcessedFileLogger logger = logger(mode);
        seed(logger);
        MusicConfig config = newConfig();
        config.setMonitorDirectory(tempDir.resolve("music").toString());
        ProcessedRecordService service = new ProcessedRecordService(config, logger, null, null, null, () -> null);

        ProcessedRecordQuery filter = q();
        filter.album = "Walpurgis";
        List<ProcessedRecord> targets = service.resolveTargets(List.of(), filter);
        var preview = service.preview(targets);
        assertEquals(2, preview.get("total"));
        assertEquals(2, preview.get("requeueable"));

        assertThrows(IllegalStateException.class, () -> service.reidentify(targets));
        assertEquals(5, logger.queryRecords(q()).total, "Unavailable consumer must preserve records");

        ProcessedRecordQuery.Page page = service.list(q());
        assertTrue(page.items.stream().allMatch(r -> Boolean.TRUE.equals(r.sourceExists)));
        assertTrue(page.items.stream().allMatch(r -> Boolean.TRUE.equals(r.inMonitorDirectory)));
    }
}
