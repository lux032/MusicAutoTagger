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

    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "file"})
    void folderViewGroupsMultiDiscMixedStatusesCueAndLooseRoot(String mode) throws Exception {
        ProcessedFileLogger logger = logger(mode);
        MusicConfig config = newConfig();
        config.setMonitorDirectory(tempDir.resolve("music").toString());
        ProcessedRecordService service = new ProcessedRecordService(config, logger, null, null, null, () -> null);

        // 1. 多碟完整成功（CD1 + CD2 自动合并到同一专辑根目录）
        logger.markFileAsProcessed(file("MultiDisc/CD1/01.flac"), "rec-md1", "Utada", "Track 1", "SCIENCE FICTION", "rg-sf", null);
        logger.markFileAsProcessed(file("MultiDisc/CD2/02.flac"), "rec-md2", "Utada", "Track 2", "SCIENCE FICTION", "rg-sf", null);

        // 2. 成功 + CUE_SPLIT 伴随 -> 整夹仍归入 SUCCESS
        logger.markFileAsProcessed(file("CueAlbum/whole.flac"), "CUE_SPLIT", "Utada", "whole.flac", "First Love");
        logger.markFileAsProcessed(file("CueAlbum/01.flac"), "rec-c1", "Utada", "Automatic", "First Love", "rg-fl", null);

        // 3. 部分失败（1 成功 + 1 失败）-> 归入 FAILED，folderState=PARTIAL_FAILED，保留刮削专辑名
        logger.markFileAsProcessed(file("PartialAlbum/01.flac"), "ONLINE_SEARCH", "YOASOBI", "Idol", "THE BOOK 3", "rg-tb3", null);
        logger.markFileAsProcessed(file("PartialAlbum/02.flac"), "FAILED", "识别失败", "02.flac", "Unknown Album");

        // 4. 全失败 -> 归入 FAILED，folderState=ALL_FAILED，无刮削专辑与封面
        logger.markFileAsProcessed(file("AllFailed/01.mp3"), "FAILED", "无候选", "01.mp3", "Unknown Album");

        // 5. 监控根目录散落文件 -> 合并到 looseRoot 分组，多专辑
        logger.markFileAsProcessed(file("loose1.flac"), "rec-l1", "Artist A", "Song A", "Album A", "rg-a", null);
        logger.markFileAsProcessed(file("loose2.flac"), "rec-l2", "Artist B", "Song B", "Album B", "rg-b", null);

        // 6. 人工驳回 -> 归入 OTHER
        logger.markFileAsProcessed(file("RejectedAlbum/01.flac"), "REVIEW_REJECTED", "Unknown", "01.flac", "Unknown Album");

        ProcessedRecordQuery folderQ = q();
        folderQ.view = "folder";
        ProcessedRecordQuery.FolderPage all = service.listFolders(folderQ);

        assertEquals(6, all.total);
        assertEquals(10, all.totalFiles);
        assertEquals(6L, all.counts.get("ALL"));
        assertEquals(3L, all.counts.get("SUCCESS"), "MultiDisc + CueAlbum + LooseRoot");
        assertEquals(2L, all.counts.get("FAILED"), "PartialAlbum + AllFailed");
        assertEquals(1L, all.counts.get("OTHER"), "RejectedAlbum");

        var byName = new java.util.HashMap<String, com.lux032.musicautotagger.model.ProcessedFolderRecord>();
        for (var f : all.items) {
            byName.put(f.looseRoot ? "__LOOSE__" : f.folderName, f);
        }

        var md = byName.get("MultiDisc");
        assertNotNull(md);
        assertEquals(2, md.totalCount);
        assertEquals(2, md.discCount);
        assertEquals("SCIENCE FICTION", md.primaryAlbum);
        assertEquals("rg-sf", md.coverReleaseGroupId);
        assertNotNull(md.coverPath);

        var cue = byName.get("CueAlbum");
        assertEquals(ProcessedRecord.Status.SUCCESS, cue.status);
        assertEquals(com.lux032.musicautotagger.model.ProcessedFolderRecord.FolderState.SUCCESS, cue.folderState);
        assertEquals(1, cue.cueSplitCount);
        assertEquals(1, cue.successCount);

        var partial = byName.get("PartialAlbum");
        assertEquals(ProcessedRecord.Status.FAILED, partial.status);
        assertEquals(com.lux032.musicautotagger.model.ProcessedFolderRecord.FolderState.PARTIAL_FAILED, partial.folderState);
        assertEquals("THE BOOK 3", partial.primaryAlbum);
        assertEquals("rg-tb3", partial.coverReleaseGroupId);

        var failed = byName.get("AllFailed");
        assertEquals(com.lux032.musicautotagger.model.ProcessedFolderRecord.FolderState.ALL_FAILED, failed.folderState);
        assertNull(failed.primaryAlbum);
        assertNull(failed.coverReleaseGroupId);
        assertNull(failed.coverPath);

        var loose = byName.get("__LOOSE__");
        assertNotNull(loose);
        assertTrue(loose.looseRoot);
        assertEquals(2, loose.totalCount);
        assertEquals(2, loose.albums.size());

        // 展开多碟目录子文件，包含 discName
        List<ProcessedRecord> mdFiles = service.listFolderFiles(md.folderPath);
        assertEquals(2, mdFiles.size());
        assertEquals("CD1", mdFiles.get(0).discName);
        assertEquals("CD2", mdFiles.get(1).discName);

        // 筛选 FAILED Tab 后按文件夹解析目标：应作用于 PartialAlbum 的全部 2 条记录（含成功的那条）
        ProcessedRecordQuery failedFolderQ = q();
        failedFolderQ.view = "folder";
        failedFolderQ.status = ProcessedRecord.Status.FAILED;
        List<ProcessedRecord> targets = service.resolveTargets(List.of(), List.of(partial.folderPath), null);
        assertEquals(2, targets.size(), "按文件夹操作应作用于该文件夹全部记录");

        List<ProcessedRecord> filterTargets = service.resolveTargets(List.of(), List.of(), failedFolderQ);
        assertEquals(3, filterTargets.size(), "PartialAlbum(2) + AllFailed(1)");

        var preview = service.preview(filterTargets);
        assertEquals(2, preview.get("folderCount"));
        assertEquals(3, preview.get("total"));

        // 文件夹视图导出：返回命中文件夹的所有文件明细并带上 folderPath
        List<ProcessedRecord> exported = service.export(failedFolderQ, 100);
        assertEquals(3, exported.size());
        assertTrue(exported.stream().allMatch(r -> r.folderPath != null && !r.folderPath.isBlank()));
    }
}
