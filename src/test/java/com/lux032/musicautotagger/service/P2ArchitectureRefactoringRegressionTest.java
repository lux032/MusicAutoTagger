package com.lux032.musicautotagger.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.ConfigFieldSpec;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ProcessedRecord;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.util.FileSystemUtils;
import com.lux032.musicautotagger.util.HttpClientFactory;
import com.lux032.musicautotagger.util.JsonUtils;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务 4（P2 架构重构与长期演进）回归测试：
 * - P2-3: 前端公共模块（ui.js / errors.js / api.js）收敛与五页统一接入
 * - P2-2: HttpClientFactory / JsonUtils / ReviewQueue 历史归档 / 流式 CSV 导出
 * - P2-1: God Class 拆分（CoverArtArchiveClient / TargetPathResolver / AtomicAudioPublisher /
 *         RecoveryTrashService / FolderStateStore / FolderNormalizationCoordinator）与配置漂移清理
 */
class P2ArchitectureRefactoringRegressionTest {

    @TempDir
    Path tempDir;

    private static MusicConfig newConfig() throws Exception {
        var ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    // ==================== P2-3: 前端公共模块收敛 ====================

    @Test
    void p2_3_sharedFrontendModulesWiredAcrossAllFivePagesWithoutDuplicatedErrorKeys() throws Exception {
        Path staticDir = Path.of("src/main/resources/static");
        assertTrue(Files.isRegularFile(staticDir.resolve("ui.js")));
        assertTrue(Files.isRegularFile(staticDir.resolve("errors.js")));
        assertTrue(Files.isRegularFile(staticDir.resolve("api.js")));

        String uiJs = Files.readString(staticDir.resolve("ui.js"), StandardCharsets.UTF_8);
        String errorsJs = Files.readString(staticDir.resolve("errors.js"), StandardCharsets.UTF_8);
        String apiJs = Files.readString(staticDir.resolve("api.js"), StandardCharsets.UTF_8);
        assertTrue(uiJs.contains("global.AppUI"), "ui.js 应导出 AppUI");
        assertTrue(uiJs.contains("escapeHtml"), "ui.js 应提供统一 escapeHtml");
        assertTrue(errorsJs.contains("global.AppErrors"), "errors.js 应导出 AppErrors");
        assertTrue(errorsJs.contains("humanError"), "errors.js 应提供统一 humanError");
        assertTrue(apiJs.contains("global.AppApi"), "api.js 应导出 AppApi");
        assertTrue(apiJs.contains("X-CSRF-Token"), "api.js 应统一注入 X-CSRF-Token");

        for (String page : List.of("index.html", "review.html", "covers.html", "recovery.html", "records.html")) {
            String html = Files.readString(staticDir.resolve(page), StandardCharsets.UTF_8);
            assertTrue(html.contains("src=\"ui.js"), page + " 应引入 ui.js");
            assertTrue(html.contains("src=\"errors.js"), page + " 应引入 errors.js");
            assertTrue(html.contains("src=\"api.js"), page + " 应引入 api.js");
            assertFalse(html.contains("const ERROR_KEYS = {"),
                page + " 不应再内联重复定义 ERROR_KEYS");
        }
    }

    // ==================== P2-2: 基础设施与持久化收敛 ====================

    @Test
    void p2_2_httpClientFactoryBuildsDirectAndProxiedClientsWithDistinctSignatures() throws Exception {
        MusicConfig direct = newConfig();
        direct.setProxyEnabled(false);
        assertFalse(HttpClientFactory.isProxyConfigured(direct));
        assertEquals("DIRECT", HttpClientFactory.proxySignature(direct));

        HttpClient jdkDirect = HttpClientFactory.buildJdkHttpClient(direct);
        assertNotNull(jdkDirect);
        assertEquals(HttpClient.Redirect.NEVER, jdkDirect.followRedirects());

        try (CloseableHttpClient apacheDirect = HttpClientFactory.buildApacheHttpClient(
                direct, Timeout.ofSeconds(5), Timeout.ofSeconds(5), Timeout.ofSeconds(10))) {
            assertNotNull(apacheDirect);
        }

        MusicConfig proxied = newConfig();
        proxied.setProxyEnabled(true);
        proxied.setProxyHost("127.0.0.1");
        proxied.setProxyPort(7890);
        proxied.setProxyUsername("alice");
        proxied.setProxyPassword("secret");
        assertTrue(HttpClientFactory.isProxyConfigured(proxied));
        String sig1 = HttpClientFactory.proxySignature(proxied);
        assertNotEquals("DIRECT", sig1);

        proxied.setProxyPassword("secret-2");
        assertNotEquals(sig1, HttpClientFactory.proxySignature(proxied),
            "代理凭据变更必须改变 proxySignature 以触发客户端重建");

        HttpClient jdkProxied = HttpClientFactory.buildJdkHttpClient(proxied);
        assertNotNull(jdkProxied);
        assertTrue(jdkProxied.proxy().isPresent());
        assertTrue(jdkProxied.authenticator().isPresent());

        try (CloseableHttpClient apacheProxied = HttpClientFactory.buildApacheHttpClient(
                proxied, Timeout.ofSeconds(5), Timeout.ofSeconds(5), Timeout.ofSeconds(10))) {
            assertNotNull(apacheProxied);
        }
    }

    @Test
    void p2_2_jsonUtilsSafelyParsesAndHandlesNullInvalidOrNonFiniteValues() {
        assertNull(JsonUtils.parseObject(null));
        assertNull(JsonUtils.parseObject("not-json"));
        assertNull(JsonUtils.parseObject("[1, 2, 3]"));

        JsonObject obj = JsonUtils.parseObject(
            "{\"name\":\"  Aimer  \",\"score\":\"0.92\",\"badNum\":\"NaN\",\"count\":12,\"flag\":true,\"nested\":{\"k\":\"v\"},\"arr\":[1,2]}");
        assertNotNull(obj);
        assertEquals("  Aimer  ", JsonUtils.string(obj, "name"));
        assertEquals("Aimer", JsonUtils.trimmedString(obj, "name"));
        assertEquals(0.92, JsonUtils.doubleOr(obj, "score", -1.0), 1e-6);
        assertEquals(-1.0, JsonUtils.doubleOr(obj, "badNum", -1.0), 1e-6);
        assertEquals(12, JsonUtils.intOr(obj, "count", 0));
        assertTrue(JsonUtils.boolOr(obj, "flag", false));
        assertEquals("v", JsonUtils.string(JsonUtils.object(obj, "nested"), "k"));
        assertEquals(2, JsonUtils.array(obj, "arr").size());
        assertNull(JsonUtils.string(obj, "missing"));
    }

    @Test
    void p2_2_reviewQueueArchivesOldResolvedItemsWhileProtectingPendingAndCoverSkipGuards() throws Exception {
        MusicConfig cfg = newConfig();
        Path queuePath = tempDir.resolve("review-queue.json");
        cfg.setReviewQueuePath(queuePath.toString());
        cfg.setReviewStagingDirectory(tempDir.resolve("staging").toString());

        ReviewQueueService queue = new ReviewQueueService(cfg);
        queue.setMaxRetainedResolvedItems(2);
        TagWriterService tagWriter = new TagWriterService(cfg);

        // 1. 保留一条 PENDING_REVIEW 条目（不得归档）
        Path pendingDir = Files.createDirectories(tempDir.resolve("pending-album"));
        Path pendingTrack = Files.writeString(pendingDir.resolve("01.flac"), "a");
        ReviewItem pending = queue.enqueueRecoveryFolder(
            pendingDir.toString(), "FAILED", List.of(pendingTrack.toFile()), tagWriter, "h-pending");
        assertNotNull(pending);

        // 2. 保留一条 COVER_MISSING + confirmedNoCover 的已决条目（扫描跳过守卫，不得归档）
        Path noCoverDir = Files.createDirectories(tempDir.resolve("nocover-album"));
        Path noCoverTrack = Files.writeString(noCoverDir.resolve("01.flac"), "b");
        ReviewItem.MissingCover mc = new ReviewItem.MissingCover();
        mc.setAlbumTitle("NoCoverAlbum");
        mc.setAlbumArtist("Artist");
        mc.setConfirmedNoCover(true);
        ReviewItem noCover = queue.enqueueLibraryMissingCover(
            noCoverDir.toString(), List.of(noCoverTrack.toFile()), tagWriter, mc);
        assertNotNull(noCover);
        queue.markResolved(noCover, ReviewItem.Status.CONFIRMED, "confirmed no cover");

        // 3. 入队并解决 5 条普通条目（限留 2 条，最旧 3 条应归档到 review-queue-archive.jsonl）
        List<String> resolvedIds = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Path dir = Files.createDirectories(tempDir.resolve("album-" + i));
            Path track = Files.writeString(dir.resolve("01.flac"), "x");
            ReviewItem item = queue.enqueueRecoveryFolder(
                dir.toString(), "FAILED", List.of(track.toFile()), tagWriter, "h-" + i);
            // 确保时间戳严格递增，归档顺序可确定验证
            item.setUpdatedAt(1_700_000_000_000L + i * 1000L);
            queue.markResolved(item, ReviewItem.Status.CONFIRMED, "done-" + i);
            resolvedIds.add(item.getId());
        }

        // 活态队列应保留：1 pending + 1 confirmedNoCover + 2 newest resolved = 4 条
        List<ReviewItem> activeAll = queue.list(null);
        assertEquals(4, activeAll.size());
        assertNotNull(queue.get(pending.getId()), "PENDING_REVIEW 条目必须留在活态队列");
        assertNotNull(queue.get(noCover.getId()), "COVER_MISSING confirmedNoCover 守卫必须留在活态队列");
        assertNull(queue.get(resolvedIds.get(0)), "最旧已决条目应已归档");
        assertNull(queue.get(resolvedIds.get(1)), "次旧已决条目应已归档");
        assertNull(queue.get(resolvedIds.get(2)), "第三旧已决条目应已归档");
        assertNotNull(queue.get(resolvedIds.get(3)), "较新已决条目应保留在活态队列");
        assertNotNull(queue.get(resolvedIds.get(4)), "最新已决条目应保留在活态队列");

        Path archiveFile = queue.getArchiveFile();
        assertTrue(Files.isRegularFile(archiveFile), "归档 NDJSON 文件应已生成");
        List<String> archiveLines = Files.readAllLines(archiveFile, StandardCharsets.UTF_8);
        assertEquals(3, archiveLines.size(), "归档文件应包含 3 条已归档历史记录");
        Gson gson = new Gson();
        for (String line : archiveLines) {
            ReviewItem archived = gson.fromJson(line, ReviewItem.class);
            assertNotNull(archived.getId());
            assertEquals(ReviewItem.Status.CONFIRMED, archived.getStatus());
        }
    }

    @Test
    void p2_2_streamingCsvExportStreamsFileAndFolderViewsWithoutDroppingRows() throws Exception {
        MusicConfig cfg = newConfig();
        cfg.setDbType("sqlite");
        cfg.setDbSqlitePath(tempDir.resolve("stream.db").toString());
        cfg.setMonitorDirectory(tempDir.resolve("music").toString());

        try (DatabaseService db = new DatabaseService(cfg)) {
            ProcessedFileLogger logger = new ProcessedFileLogger(cfg, db);
            for (int albumIdx = 1; albumIdx <= 3; albumIdx++) {
                Path albumDir = Files.createDirectories(tempDir.resolve("music/Album-" + albumIdx));
                for (int t = 1; t <= 4; t++) {
                    Path f = Files.writeString(albumDir.resolve("0" + t + ".flac"), "audio");
                    logger.markFileAsProcessed(f.toFile(), "rec-" + albumIdx + "-" + t,
                        "Artist " + albumIdx, "Track " + t, "Album " + albumIdx, "rg-" + albumIdx, null);
                }
            }

            List<ProcessedRecord> directStreamed = new ArrayList<>();
            int streamedCount = logger.streamRecords(new ProcessedRecordQuery(), 100, directStreamed::add);
            assertEquals(12, streamedCount);
            assertEquals(12, directStreamed.size());

            ProcessedRecordService service = new ProcessedRecordService(cfg, logger, null, null, null, () -> null);

            List<ProcessedRecord> fileViewStreamed = new ArrayList<>();
            ProcessedRecordQuery fileQuery = new ProcessedRecordQuery();
            fileQuery.view = "file";
            int fileCount = service.exportStream(fileQuery, 100, fileViewStreamed::add);
            assertEquals(12, fileCount);
            assertEquals(12, fileViewStreamed.size());

            List<ProcessedRecord> folderViewStreamed = new ArrayList<>();
            ProcessedRecordQuery folderQuery = new ProcessedRecordQuery();
            folderQuery.view = "folder";
            int folderCount = service.exportStream(folderQuery, 100, folderViewStreamed::add);
            assertEquals(12, folderCount);
            assertEquals(12, folderViewStreamed.size());
        }
    }

    // ==================== P2-1: God Class 拆分与配置漂移清理 ====================

    @Test
    void p2_1_targetPathResolverAndAtomicAudioPublisherHandlePrefixesConflictsAndFlacMd5() throws Exception {
        MusicConfig cfg = newConfig();
        cfg.setAutoRename(true);
        TargetPathResolver resolver = new TargetPathResolver(cfg);

        assertEquals("", TargetPathResolver.extractTrackPrefix("01.flac"));
        assertEquals("01. ", TargetPathResolver.extractTrackPrefix("01. Song.flac"));
        assertEquals("2.05 ", TargetPathResolver.extractTrackPrefix("2.05 Song.flac"));

        MusicMetadata meta = new MusicMetadata();
        meta.setArtist("YOASOBI");
        meta.setAlbumArtist("YOASOBI");
        meta.setAlbum("THE BOOK");
        meta.setTitle("Yoru ni Kakeru");
        meta.setDiscNo("1/1");
        meta.setTrackNo("2/9");

        File src = tempDir.resolve("raw.flac").toFile();
        Path outRoot = tempDir.resolve("output");
        File target = resolver.resolveTargetFile(src, meta, outRoot);
        assertEquals(outRoot.resolve("YOASOBI").resolve("THE BOOK").resolve("1.02 YOASOBI - Yoru ni Kakeru.flac").toFile(),
            target);

        File suffixed = TargetPathResolver.withConflictSuffix(target, 2);
        assertEquals("1.02 YOASOBI - Yoru ni Kakeru (2).flac", suffixed.getName());

        // 构造最小合法 FLAC 头验证 STREAMINFO MD5 提取
        Path flac = tempDir.resolve("sample.flac");
        byte[] header = new byte[42];
        header[0] = 'f'; header[1] = 'L'; header[2] = 'a'; header[3] = 'C';
        header[4] = (byte) 0x80; // last block, type=0 (STREAMINFO)
        header[5] = 0; header[6] = 0; header[7] = 34; // block length = 34
        for (int i = 26; i < 42; i++) {
            header[i] = (byte) (i - 25);
        }
        Files.write(flac, header);
        assertEquals("0102030405060708090a0b0c0d0e0f10", AtomicAudioPublisher.readFlacStreaminfoMd5(flac.toFile()));

        // 验证 CoverArtArchiveClient 动画版关键词打分独立可用
        var score = CoverArtArchiveClient.scoreAnimeEdition("Title (期間生産限定盤)", List.of("アニメ盤", "期間生産限定盤"));
        assertTrue(score.matched());
        assertEquals(1, score.matchCount);
        assertEquals(1, score.bestWeight);
    }

    @Test
    void p2_1_recoveryTrashServiceSupportsTrashCountListRestoreDeleteAndIgnoresManifests() throws Exception {
        MusicConfig cfg = newConfig();
        Path failedRoot = Files.createDirectories(tempDir.resolve("failed"));
        Path trashRoot = tempDir.resolve("trash");
        cfg.setFailedDirectory(failedRoot.toString());
        cfg.setOutputDirectory(tempDir.resolve("output").toString());
        cfg.setRecoveryTrashDirectory(trashRoot.toString());
        cfg.setRecoveryTrashRetentionDays(7);
        cfg.setSupportedFormats(new String[]{"flac", "mp3"});

        FileSystemUtils fs = new FileSystemUtils(cfg);
        RecoveryTrashService trash = new RecoveryTrashService(
            cfg, fs, new Gson(),
            (type, rel) -> failedRoot.resolve(rel).toFile(),
            dir -> {
                List<File> files = new ArrayList<>();
                fs.collectAudioFilesForMarking(dir, files);
                return files;
            },
            () -> {}
        );

        Path album = Files.createDirectories(failedRoot.resolve("FailedAlbum"));
        Files.writeString(album.resolve("01.flac"), "audio");
        // 创建一个崩溃备份目录，countTrash/listTrash 必须忽略
        Files.createDirectories(trashRoot.resolve(".pending-replace-crash"));

        trash.moveToTrash(album.toFile(), "job-1", RecoveryService.SourceType.FAILED, "FailedAlbum");
        assertFalse(Files.exists(album));
        assertEquals(1, trash.countTrash(), "仅统计主体条目，忽略 .json 与 .pending-replace-*");

        List<Map<String, Object>> listed = trash.listTrash();
        assertEquals(1, listed.size());
        String id = String.valueOf(listed.get(0).get("id"));
        assertEquals("FAILED", listed.get(0).get("sourceType"));
        assertEquals("FailedAlbum", listed.get(0).get("relativePath"));
        assertEquals(Boolean.TRUE, listed.get(0).get("restorable"));

        trash.restoreFromTrash(id);
        assertTrue(Files.isDirectory(album), "应已还原回原隔离目录");
        assertEquals(0, trash.countTrash());

        // 再次入站并彻底删除
        trash.moveToTrash(album.toFile(), "job-2", RecoveryService.SourceType.FAILED, "FailedAlbum");
        String id2 = String.valueOf(trash.listTrash().get(0).get("id"));
        trash.deleteFromTrash(id2);
        assertEquals(0, trash.countTrash());
        assertFalse(Files.exists(trashRoot.resolve(id2 + ".json")));
    }

    @Test
    void p2_1_folderStateStoreProvidesBoundedStripedLocksAndEvictsIdleFolders() {
        FolderAlbumCache cache = new FolderAlbumCache(null, null, null);
        Set<Object> distinctLocks = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String path = "/music/album-" + i;
            Object lock = cache.folderLock(path);
            assertSame(lock, cache.folderLock(path), "同一路径必须映射到稳定一致的锁对象");
            distinctLocks.add(lock);
        }
        assertTrue(distinctLocks.size() <= FolderStateStore.DEFAULT_LOCK_STRIPES,
            "锁对象数量应严格受限于 256 条带上限，实际: " + distinctLocks.size());

        // 设置自动缓存与人工确认缓存
        String autoFolder = "/music/auto-idle";
        String manualFolder = "/music/manual-locked";
        cache.setFolderAlbum(autoFolder, new FolderAlbumCache.CachedAlbumInfo(
            "rg-1", "rel-1", "Auto Album", "Artist", 5, "2024", 0.9, FolderAlbumCache.CacheSource.DURATION_SEQUENCE));
        cache.forceSetFolderAlbum(manualFolder, new FolderAlbumCache.CachedAlbumInfo(
            "rg-2", "rel-2", "Manual Album", "Artist", 5, "2024", 1.0, FolderAlbumCache.CacheSource.MANUAL_CONFIRMED));

        int evicted = cache.evictIdleFolders(0L);
        assertEquals(1, evicted, "空闲自动缓存应被驱逐，人工锁定缓存必须被保护");
        assertNull(cache.getFolderAlbum(autoFolder, 5));
        assertNotNull(cache.getFolderAlbum(manualFolder, 5));
    }

    @Test
    void p2_1_folderNormalizationCoordinatorIsolatesLooseFilesAndCleansUpPlans() throws Exception {
        MusicConfig cfg = newConfig();
        cfg.setAudioNormalizeEnabled(false);
        cfg.setSupportedFormats(new String[]{"flac", "mp3"});
        AudioFormatNormalizer normalizer = new AudioFormatNormalizer(cfg);
        FileSystemUtils fs = new FileSystemUtils(cfg);
        FolderNormalizationCoordinator coordinator = new FolderNormalizationCoordinator(normalizer, fs);

        Path monitorRoot = Files.createDirectories(tempDir.resolve("monitor"));
        Path loose = Files.writeString(monitorRoot.resolve("loose.flac"), "a");
        FolderNormalizationCoordinator.Plan loosePlan =
            coordinator.getOrPreparePlan(loose.toFile(), monitorRoot.toFile(), true);
        assertNotNull(loosePlan.getResult(loose.toFile()));
        assertEquals(0, coordinator.activePlanCount(), "监控根目录散落文件不得缓存为共享目录计划");

        Path albumDir = Files.createDirectories(monitorRoot.resolve("AlbumX"));
        Path track1 = Files.writeString(albumDir.resolve("01.flac"), "a");
        FolderNormalizationCoordinator.Plan plan1 =
            coordinator.getOrPreparePlan(track1.toFile(), albumDir.toFile(), false);
        FolderNormalizationCoordinator.Plan plan2 =
            coordinator.getOrPreparePlan(track1.toFile(), albumDir.toFile(), false);
        assertSame(plan1, plan2, "同专辑目录应复用同一标准化计划");
        assertEquals(1, coordinator.activePlanCount());

        coordinator.cleanupPlan(albumDir.toFile().getAbsolutePath());
        assertEquals(0, coordinator.activePlanCount());
    }

    @Test
    void p2_1_configFieldSpecAndExampleTemplatesEliminateConfigDriftAndDeadBackfillRemoved() throws Exception {
        Map<String, ConfigFieldSpec.Spec> specs = MusicConfig.fieldSpecs();
        assertTrue(specs.containsKey("web.port"));
        assertTrue(specs.containsKey("release.countryPriority"));
        assertTrue(ConfigFieldSpec.isReadOnly("audio.normalize.ffmpegPath"));
        assertTrue(ConfigFieldSpec.isReadOnly("llm.allowPrivateEndpoints"));
        assertTrue(ConfigFieldSpec.requiresRestart("web.port"));
        assertTrue(ConfigFieldSpec.get("web.port").isWithinRange(8080));
        assertFalse(ConfigFieldSpec.get("web.port").isWithinRange(70000));

        String enExample = Files.readString(Path.of("config.properties.example"), StandardCharsets.UTF_8);
        String zhExample = Files.readString(Path.of("config.properties.example.zh"), StandardCharsets.UTF_8);
        assertTrue(enExample.contains("web.port="), "config.properties.example 应包含 web.port");
        assertTrue(enExample.contains("release.countryPriority="), "config.properties.example 应包含 release.countryPriority");
        assertTrue(zhExample.contains("web.port="), "config.properties.example.zh 应包含 web.port");
        assertTrue(zhExample.contains("release.countryPriority="), "config.properties.example.zh 应包含 release.countryPriority");

        String loggerSrc = Files.readString(
            Path.of("src/main/java/com/lux032/musicautotagger/service/ProcessedFileLogger.java"),
            StandardCharsets.UTF_8);
        assertFalse(loggerSrc.contains("backfillMissingTargetPaths"),
            "ProcessedFileLogger 中的死代码 backfillMissingTargetPaths 应已彻底移除");
    }
}
