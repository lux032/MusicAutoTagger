package com.lux032.musicautotagger.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ProcessedRecord;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.llm.LlmArtistSplitter;
import com.lux032.musicautotagger.service.llm.LlmClient;
import com.lux032.musicautotagger.service.llm.LlmModelCatalog;
import com.lux032.musicautotagger.service.llm.LlmProvider;
import com.lux032.musicautotagger.service.llm.NativeWebSearchClient;
import com.lux032.musicautotagger.service.llm.WebSearchClient;
import com.lux032.musicautotagger.util.AdminCredentialsStore;
import com.lux032.musicautotagger.util.FileSystemUtils;
import com.lux032.musicautotagger.web.AuthFilter;
import com.lux032.musicautotagger.web.AuthServlet;
import com.lux032.musicautotagger.web.ConfigServlet;
import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 2 专项回归测试（D-1 ~ D-6）：数据库、持久化、LLM 解析与并发配置。
 */
class P1Task2DataLlmConcurrencyRegressionTest {

    @TempDir
    Path tempDir;

    private final Gson gson = new Gson();

    private MusicConfig newConfig() throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MusicConfig cfg = ctor.newInstance();
        cfg.setMonitorDirectory(tempDir.resolve("monitor").toString());
        cfg.setOutputDirectory(tempDir.resolve("output").toString());
        cfg.setFailedDirectory(tempDir.resolve("failed").toString());
        cfg.setPartialDirectory(tempDir.resolve("partial").toString());
        cfg.setReviewQueuePath(tempDir.resolve("data/review-queue.json").toString());
        cfg.setReviewStagingDirectory(tempDir.resolve("data/review-staging").toString());
        cfg.setRecoveryWorkDirectory(tempDir.resolve("data/recovery-work").toString());
        cfg.setRecoveryTrashDirectory(tempDir.resolve("data/recovery-trash").toString());
        cfg.setProcessedFileLogPath(tempDir.resolve("data/processed.log").toString());
        cfg.setDbSqlitePath(tempDir.resolve("data/records.db").toString());
        cfg.setDbType("sqlite");
        return cfg;
    }

    private File touch(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) {
            Files.writeString(path, "audio", StandardCharsets.UTF_8);
        }
        return path.toFile();
    }

    // ==================== D-1：schema.sql 与 DatabaseService ====================

    @Test
    void d1SchemaAndMysqlJdbcUrlSecurityAndTimezone() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/schema.sql"), StandardCharsets.UTF_8);
        assertTrue(schema.contains("file_path VARCHAR(768) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL UNIQUE"),
            "schema.sql file_path must stay within InnoDB 3072B limit and preserve case sensitivity");
        assertTrue(schema.contains("folder_path VARCHAR(768) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL"),
            "schema.sql must define indexed folder_path column");

        MusicConfig cfg = newConfig();
        cfg.setDbHost("db.internal");
        cfg.setDbPort(3306);
        cfg.setDbDatabase("music_tagger");
        String url = DatabaseService.buildMysqlJdbcUrl(cfg);
        assertTrue(url.startsWith("jdbc:mysql://db.internal:3306/music_tagger?"));
        assertTrue(url.contains("serverTimezone="));

        // JDBC 参数注入必须被拒绝
        cfg.setDbHost("127.0.0.1:3306/evil?allowLoadLocalInfile=true#");
        assertThrows(IllegalArgumentException.class, () -> DatabaseService.buildMysqlJdbcUrl(cfg));

        cfg.setDbHost("localhost");
        cfg.setDbDatabase("music?autoDeserialize=true");
        assertThrows(IllegalArgumentException.class, () -> DatabaseService.buildMysqlJdbcUrl(cfg));

        cfg.setDbDatabase("music_tagger");
        cfg.setDbPort(0);
        assertThrows(IllegalArgumentException.class, () -> DatabaseService.buildMysqlJdbcUrl(cfg));
    }

    // ==================== D-2：前缀边界/码点、Unicode 搜索、File 转义与批删事务 ====================

    @Test
    void d2PrefixBoundaryUnicodeCodePointsSqliteLowerAndFileEscaping() throws Exception {
        // 1. SQLite 模式：前缀边界（/Aimer/Dawn 不误伤 /Aimer/Dawn (LE)）、emoji 码点、非 ASCII 大小写搜索
        MusicConfig sqlCfg = newConfig();
        sqlCfg.setDbType("sqlite");
        sqlCfg.setDbSqlitePath(tempDir.resolve("d2.db").toString());
        try (DatabaseService db = new DatabaseService(sqlCfg)) {
            ProcessedFileLogger logger = new ProcessedFileLogger(sqlCfg, db);

            File f1 = touch(tempDir.resolve("monitor/d1/01.flac"));
            File f2 = touch(tempDir.resolve("monitor/d2/01.flac"));
            File f3 = touch(tempDir.resolve("monitor/d3/01.flac"));
            File f4 = touch(tempDir.resolve("monitor/d4/01.flac"));

            Path workDawn = tempDir.resolve("work/Aimer/Dawn");
            Path workDawnLe = tempDir.resolve("work/Aimer/Dawn (LE)");
            Path workEmoji = tempDir.resolve("work/Aimer/🎵Dawn");
            Path outDawn = tempDir.resolve("output/Aimer/Dawn");
            Path outEmoji = tempDir.resolve("output/Aimer/🎵Dawn");

            logger.markFileAsProcessed(f1, "rec-1", "Étoile", "Chanson", "Étoile Album", "rg-1",
                workDawn.resolve("01.flac").toString());
            logger.markFileAsProcessed(f2, "rec-2", "Aimer", "Track LE", "Dawn (LE)", "rg-2",
                workDawnLe.resolve("01.flac").toString());
            logger.markFileAsProcessed(f3, "rec-3", "Кино", "Группа крови", "Звезда", "rg-3",
                workEmoji.resolve("01.flac").toString());
            logger.markFileAsProcessed(f4, "FAILED", "err", "01.flac", "Étoile Album");

            // 重定向 /work/Aimer/Dawn -> /output/Aimer/Dawn 不得误伤 Dawn (LE)
            logger.rebaseTargetPaths(workDawn.toString(), outDawn.toString());
            assertEquals(outDawn.resolve("01.flac").toAbsolutePath().toString(), logger.findTargetPath(f1));
            assertEquals(workDawnLe.resolve("01.flac").toAbsolutePath().toString(), logger.findTargetPath(f2),
                "Sibling directory sharing string prefix must NOT be rewritten");

            // Emoji 路径码点精确替换，不得因 UTF-16 surrogate pair 截断 '0'
            logger.rebaseTargetPaths(workEmoji.toString(), outEmoji.toString());
            assertEquals(outEmoji.resolve("01.flac").toAbsolutePath().toString(), logger.findTargetPath(f3),
                "Emoji path must use Unicode code point index instead of UTF-16 length");

            // 清除 Dawn 不得误清 Dawn (LE)
            logger.clearTargetPathsUnder(outDawn.toString());
            assertNull(logger.findTargetPath(f1));
            assertEquals(workDawnLe.resolve("01.flac").toAbsolutePath().toString(), logger.findTargetPath(f2));

            // SQLite Unicode 大小写无关搜索（法文重音 & 西里尔字母）
            ProcessedRecordQuery qFrench = new ProcessedRecordQuery();
            qFrench.keyword = "étoile";
            assertEquals(2, logger.queryRecords(qFrench).total);

            ProcessedRecordQuery qCyrillic = new ProcessedRecordQuery();
            qCyrillic.keyword = "кино";
            assertEquals(1, logger.queryRecords(qCyrillic).total);

            // 同专辑含 FAILED 行时，findAlbumsMissingReleaseGroupId 仍会排除占位 ID 而不是整张专辑丢失
            File f5 = touch(tempDir.resolve("monitor/mixed/ok.flac"));
            File f6 = touch(tempDir.resolve("monitor/mixed/fail.flac"));
            logger.markFileAsProcessed(f5, "ONLINE_SEARCH", "MixArtist", "Good", "MixedAlbum", null, null);
            logger.markFileAsProcessed(f6, "FAILED", "MixArtist", "Bad", "MixedAlbum", null, null);
            List<ProcessedFileLogger.AlbumGroup> missingRg = logger.findAlbumsMissingReleaseGroupId();
            assertTrue(missingRg.stream().anyMatch(g -> "MixedAlbum".equals(g.album) && g.fileCount == 1),
                "Album with both valid and FAILED rows must still appear for RG backfill with valid row count");

            // 批量删除事务正常提交
            assertEquals(2, logger.removeProcessedRecords(List.of(f5.getAbsolutePath(), f6.getAbsolutePath())));
            assertFalse(logger.isFileProcessed(f5));
            assertFalse(logger.isFileProcessed(f6));
        }

        // 2. File 模式：UTF-8 + 分隔符 `|` 与换行符转义 + 前缀边界
        MusicConfig fileCfg = newConfig();
        fileCfg.setDbType("file");
        fileCfg.setProcessedFileLogPath(tempDir.resolve("data/escaped.log").toString());
        ProcessedFileLogger fileLogger = new ProcessedFileLogger(fileCfg, null);

        File fSpecial = touch(tempDir.resolve("monitor/special/01.flac"));
        File fSib = touch(tempDir.resolve("monitor/special/02.flac"));
        Path pDawn = tempDir.resolve("out/Aimer/Dawn/01.flac");
        Path pDawnLe = tempDir.resolve("out/Aimer/Dawn (LE)/02.flac");

        fileLogger.markFileAsProcessed(fSpecial, "rec-sp", "Artist|A\\B", "Line1\nLine2", "Album|Name",
            "rg-sp", pDawn.toString());
        fileLogger.markFileAsProcessed(fSib, "rec-sib", "Aimer", "Sibling", "Dawn (LE)",
            "rg-sib", pDawnLe.toString());

        assertTrue(fileLogger.isFileProcessed(fSpecial));
        List<ProcessedRecord> loaded = fileLogger.findRecordsByPaths(List.of(fSpecial.getAbsolutePath()));
        assertEquals(1, loaded.size());
        assertEquals("Artist|A\\B", loaded.get(0).artist);
        assertEquals("Line1\nLine2", loaded.get(0).title);
        assertEquals("Album|Name", loaded.get(0).album);
        assertEquals("rg-sp", loaded.get(0).releaseGroupId);
        assertEquals(pDawn.toAbsolutePath().toString(), loaded.get(0).targetFilePath);

        fileLogger.clearTargetPathsUnder(tempDir.resolve("out/Aimer/Dawn").toString());
        assertNull(fileLogger.findTargetPath(fSpecial));
        assertEquals(pDawnLe.toAbsolutePath().toString(), fileLogger.findTargetPath(fSib));
    }

    // ==================== D-3：持久化 folder_path SQL 分页、同步删记录防重、散曲审核与单层艺人散曲 ====================

    @Test
    void d3SqlFolderViewWithoutFullLoadAndStagedReidentifyGuards() throws Exception {
        MusicConfig cfg = newConfig();
        cfg.setDbType("sqlite");
        cfg.setDbSqlitePath(tempDir.resolve("d3.db").toString());
        try (DatabaseService db = new DatabaseService(cfg)) {
            // 使用子类拦截 loadAllRecords()，断言 SQL 文件夹查询路径绝对不会触发全表加载！
            AtomicInteger loadAllCalls = new AtomicInteger();
            ProcessedFileLogger logger = new ProcessedFileLogger(cfg, db) {
                @Override
                public List<ProcessedRecord> loadAllRecords() {
                    loadAllCalls.incrementAndGet();
                    return super.loadAllRecords();
                }
            };
            assertTrue(logger.isFolderPathColumnAvailable());

            File md1 = touch(tempDir.resolve("monitor/MultiDisc/CD1/01.flac"));
            File md2 = touch(tempDir.resolve("monitor/MultiDisc/CD2/02.flac"));
            File loose1 = touch(tempDir.resolve("monitor/loose1.flac"));
            File loose2 = touch(tempDir.resolve("monitor/loose2.flac"));
            logger.markFileAsProcessed(md1, "r1", "Utada", "One", "SCIENCE FICTION", "rg-1", null);
            logger.markFileAsProcessed(md2, "r2", "Utada", "Two", "SCIENCE FICTION", "rg-1", null);
            logger.markFileAsProcessed(loose1, "r3", "SoloA", "Song1", "Single1", "rg-2", null);
            logger.markFileAsProcessed(loose2, "r4", "SoloB", "Song2", "Single2", "rg-3", null);

            // 验证数据库中 folder_path 列已持久化
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT folder_path FROM processed_files WHERE file_path = ?")) {
                ps.setString(1, md1.getAbsolutePath());
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(tempDir.resolve("monitor/MultiDisc").toFile().getCanonicalPath(), rs.getString(1));
                }
            }

            ReviewQueueService reviewQueue = new ReviewQueueService(cfg);
            FileSystemUtils fsu = new FileSystemUtils(cfg);
            ProcessedRecordService service = new ProcessedRecordService(cfg, logger, null, null, fsu, () -> null);
            service.setReviewQueueService(reviewQueue);

            ProcessedRecordQuery folderQ = new ProcessedRecordQuery();
            folderQ.view = "folder";
            ProcessedRecordQuery.FolderPage page = service.listFolders(folderQ);
            assertEquals(2, page.total);
            assertEquals(4, page.totalFiles);

            String mdFolder = tempDir.resolve("monitor/MultiDisc").toFile().getCanonicalPath();
            assertEquals(2, service.listFolderFiles(mdFolder).size());
            assertEquals(2, service.resolveTargets(List.of(), List.of(mdFolder), null).size());
            assertEquals(4, service.resolveTargets(List.of(), List.of(), folderQ).size());
            assertEquals(4, service.export(folderQ, 100).size());
            assertEquals(0, loadAllCalls.get(), "SQL folder queries must NOT call loadAllRecords()");

            // D-3 ③: 散曲在待确认队列中以自身路径入队，isUnderReview 只拦截该散曲，不误拦同目录兄弟散曲
            ReviewItem.MissingCover mc = new ReviewItem.MissingCover();
            mc.setAlbumTitle("Single1");
            mc.setAlbumArtist("SoloA");
            mc.setLooseFile(true);
            FolderAlbumCache.PendingFile pf = new FolderAlbumCache.PendingFile(
                loose1, loose1, null, new MusicMetadata(), null);
            reviewQueue.enqueueMissingCover(loose1.getAbsolutePath(), List.of(pf), mc, "missing cover");

            Map<String, Object> preview = service.preview(
                logger.findRecordsByPaths(List.of(loose1.getAbsolutePath(), loose2.getAbsolutePath())));
            assertEquals(1, preview.get("reviewBlockedCount"),
                "Only loose1 under review should be counted as reviewBlocked, not sibling loose2");

            // D-3 ② & ④: 输出库单层艺人目录散曲 (output/Artist/Song1.flac & Song2.flac) 不得合并，
            // 且防重复提交拦截在途任务
            File outLoose1 = touch(tempDir.resolve("output/Aimer/Song1.flac"));
            File outLoose2 = touch(tempDir.resolve("output/Aimer/Song2.flac"));
            File gone1 = touch(tempDir.resolve("gone/Song1.flac"));
            File gone2 = touch(tempDir.resolve("gone/Song2.flac"));
            logger.markFileAsProcessed(gone1, "r10", "Aimer", "Song1", "Unknown Album", null, outLoose1.getAbsolutePath());
            logger.markFileAsProcessed(gone2, "r11", "Aimer", "Song2", "Unknown Album", null, outLoose2.getAbsolutePath());
            Files.delete(gone1.toPath());
            Files.delete(gone2.toPath());

            List<List<RecoveryService.StagedReidentifyFile>> submittedBatches = new ArrayList<>();
            CompletableFuture<Void> gate = new CompletableFuture<>();
            try (RecoveryService spyRecovery = new RecoveryService(
                    cfg, null, logger, reviewQueue, new FolderAlbumCache(null, null, null), null, fsu,
                    new TagWriterService(cfg), null, null) {
                @Override
                Future<?> submitStagedReidentify(File stagedDir, String displayName,
                                                 List<StagedReidentifyFile> files, List<String> recordsToRemove) {
                    submittedBatches.add(new ArrayList<>(files));
                    logger.removeProcessedRecords(recordsToRemove);
                    return gate;
                }
            }) {
                service.setRecoveryService(spyRecovery);
                List<ProcessedRecord> stagedTargets = logger.findRecordsByPaths(
                    List.of(gone1.getAbsolutePath(), gone2.getAbsolutePath()));
                Map<String, Object> firstRes = service.reidentify(stagedTargets);
                assertEquals(2, firstRes.get("recoveryQueued"));
                assertEquals(2, submittedBatches.size(),
                    "Single-level output/Artist/Song*.flac must be submitted as 2 separate single-track jobs");
                assertFalse(logger.isFileProcessed(gone1), "Staged reidentify must synchronously remove records");

                // 立即重复提交同一批记录（任务仍在运行中）必须被防重拦截
                Map<String, Object> dupRes = service.reidentify(stagedTargets);
                assertEquals(0, dupRes.get("recoveryQueued"),
                    "Duplicate staged reidentify while task is in-flight must be skipped");
                assertEquals(2, submittedBatches.size());
                gate.complete(null);
            }
        }
    }

    // ==================== D-4：注册表防损坏覆写与批量 recordAll ====================

    @Test
    void d4RegistriesRefuseOverwriteOnCorruptJsonAndSupportBatchRecordAll() throws Exception {
        Path autoFile = tempDir.resolve("data/auto-archive-links.json");
        Files.createDirectories(autoFile.getParent());
        String corrupt = "{not-valid-json";
        Files.writeString(autoFile, corrupt, StandardCharsets.UTF_8);

        AutoArchiveRegistry autoRegistry = new AutoArchiveRegistry(autoFile);
        autoRegistry.record("AlbumA", tempDir.resolve("output/Artist/AlbumA"), List.of("01.flac"));
        assertEquals(corrupt, Files.readString(autoFile, StandardCharsets.UTF_8),
            "AutoArchiveRegistry must NOT wipe/overwrite corrupted registry file");
        autoRegistry.remove("AlbumA");
        assertEquals(corrupt, Files.readString(autoFile, StandardCharsets.UTF_8));

        Path repFile = tempDir.resolve("data/pending-replacements.json");
        Files.writeString(repFile, corrupt, StandardCharsets.UTF_8);
        ReidentifyReplacementRegistry repRegistry = new ReidentifyReplacementRegistry(repFile);
        repRegistry.record("/src/01.flac", "/out/01.flac");
        assertEquals(corrupt, Files.readString(repFile, StandardCharsets.UTF_8),
            "ReidentifyReplacementRegistry must NOT wipe/overwrite corrupted registry file");

        // 正常批量 recordAll 一次写入所有映射，不留 .tmp 文件
        Path cleanRep = tempDir.resolve("data/pending-clean.json");
        ReidentifyReplacementRegistry cleanRegistry = new ReidentifyReplacementRegistry(cleanRep);
        Map<String, String> batch = new LinkedHashMap<>();
        batch.put(tempDir.resolve("s/1.flac").toString(), tempDir.resolve("o/1.flac").toString());
        batch.put(tempDir.resolve("s/2.flac").toString(), tempDir.resolve("o/2.flac").toString());
        batch.put(tempDir.resolve("s/3.flac").toString(), tempDir.resolve("o/3.flac").toString());
        cleanRegistry.recordAll(batch);

        assertTrue(cleanRegistry.findOldTarget(tempDir.resolve("s/1.flac").toFile()).isPresent());
        assertTrue(cleanRegistry.findOldTarget(tempDir.resolve("s/3.flac").toFile()).isPresent());
        assertFalse(Files.exists(cleanRep.resolveSibling(cleanRep.getFileName() + ".tmp")));
    }

    // ==================== D-5：LLM JSON 空安全、二轮引用合并、不缓存瞬时异常、目录关闭 ====================

    @Test
    void d5LlmJsonTypeSafetyRound2EvidencePoolAndNoExceptionCache() throws Exception {
        MusicConfig cfg = newConfig();

        // 1. NativeWebSearchClient.parseAnthropicResponse 处理 web_search_tool_result_error（content 为 Object）不抛 CCE
        NativeWebSearchClient nativeClient = new NativeWebSearchClient(cfg);
        Method parseAnthropic = NativeWebSearchClient.class.getDeclaredMethod(
            "parseAnthropicResponse", int.class, String.class, JsonObject.class);
        parseAnthropic.setAccessible(true);
        JsonObject anthropicErrorAndText = JsonParser.parseString("""
            {
              "content": [
                {
                  "type": "web_search_tool_result",
                  "content": {"type": "web_search_tool_result_error", "error_code": "max_uses_exceeded"}
                },
                {
                  "type": "text",
                  "text": {"unexpected": true}
                },
                {
                  "type": "text",
                  "text": "{\\"needs_second_round\\":false,\\"candidates\\":[],\\"clues\\":[]}",
                  "citations": [{"url": "https://musicbrainz.org/release/123", "title": "MB"}]
                }
              ]
            }
            """).getAsJsonObject();
        WebSearchClient.SearchResponse parsedResp = (WebSearchClient.SearchResponse)
            parseAnthropic.invoke(nativeClient, 0, "claude-test", anthropicErrorAndText);
        assertEquals(1, parsedResp.getCitations().size());
        assertEquals("https://musicbrainz.org/release/123", parsedResp.getCitations().get(0).getUrl());

        // LlmProvider 对非数组 content/choices 不抛 ClassCastException
        assertNull(new LlmProvider.Anthropic().extractText(
            JsonParser.parseString("{\"content\":{\"error\":\"boom\"}}").getAsJsonObject()));
        assertNull(new LlmProvider.OpenAiCompatible().extractText(
            JsonParser.parseString("{\"choices\":\"not-array\"}").getAsJsonObject()));

        // 2. OnlineIdentificationService 二轮搜索合并第一轮 HIGH 引用池
        OnlineIdentificationService onlineSvc = new OnlineIdentificationService(
            cfg, new ReviewQueueService(cfg), new TagWriterService(cfg), null, null);
        ReviewItem.OnlineEvidence round1High = new ReviewItem.OnlineEvidence();
        round1High.setUrl("https://musicbrainz.org/release/round1-official");
        round1High.setDomain("musicbrainz.org");
        round1High.setReliability("HIGH");

        WebSearchClient.SearchResponse round2Resp = new WebSearchClient.SearchResponse();
        round2Resp.setText("""
            {
              "needs_second_round": false,
              "candidates": [{
                "title": "Walpurgis",
                "artist": "Aimer",
                "confidence": 0.92,
                "source_urls": ["https://musicbrainz.org/release/round1-official"],
                "tracks": [{"disc": 1, "track": 1, "title": "STAND-ALONE", "artist": "Aimer", "duration": 240}]
              }],
              "clues": "not-an-array"
            }
            """);
        OnlineIdentificationService.Parsed parsed = onlineSvc.parse(round2Resp, List.of(round1High));
        assertEquals(1, parsed.candidates.size(),
            "Round-2 candidate citing round-1 HIGH URL must pass reliability gate");
        assertTrue(parsed.candidates.get(0).isOfficialCandidate());

        // 3. LlmArtistSplitter 不永久缓存瞬时 LlmException
        AtomicInteger calls = new AtomicInteger();
        LlmClient flakyClient = new LlmClient(cfg) {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public LlmResponse complete(String systemPrompt, String userPrompt, int maxTokensOverride) throws LlmException {
                if (calls.incrementAndGet() == 1) {
                    throw new LlmException("transient 429 rate limit");
                }
                return new LlmResponse("{\"artists\":[\"Aimer\",\"SawanoHiroyuki\"]}", "openai", "gpt-test");
            }
        };
        LlmArtistSplitter splitter = new LlmArtistSplitter(flakyClient);
        LlmArtistSplitter.SplitResult first = splitter.split("Aimer & SawanoHiroyuki");
        assertFalse(first.splitApplied());
        LlmArtistSplitter.SplitResult second = splitter.split("Aimer & SawanoHiroyuki");
        assertTrue(second.splitApplied(), "Transient LlmException must NOT poison LlmArtistSplitter cache");
        assertEquals(List.of("Aimer", "SawanoHiroyuki"), second.artists());
        assertEquals(2, calls.get());

        // 4. LlmModelCatalog 实现 AutoCloseable 可显式释放连接池
        try (LlmModelCatalog catalog = new LlmModelCatalog(cfg)) {
            assertNotNull(catalog);
        }
    }

    // ==================== D-6：并发隔离、细粒度锁、队列隔离与热更新防脑裂 ====================

    @Test
    void d6LockedResolverCopyReviewQueueIsolationAndFineGrainedResolutionLock() throws Exception {
        // 1. LockedReleaseTrackResolver 在 NOT_IN_RELEASE 时不得原地篡改入参 MusicMetadata
        MusicMetadata original = new MusicMetadata();
        original.setTitle("Bonus Song");
        original.setArtist("Aimer");
        original.setTrackNo("9");
        original.setDiscNo("2");
        original.setReleaseId("old-release");
        MusicBrainzClient stubMb = new MusicBrainzClient(newConfig()) {
            @Override
            public TrackMatch matchTrackInRelease(String releaseId, String releaseGroupId, String recordingId,
                                                  String trackTitle, int durationSeconds,
                                                  String albumTitle, String albumArtist,
                                                  java.util.Set<String> claimedPositions) {
                return new TrackMatch(TrackMatchType.NOT_FOUND, null);
            }
        };
        LockedReleaseTrackResolver resolver = new LockedReleaseTrackResolver(stubMb);
        LockedReleaseTrackResolver.Resolution res = resolver.resolve(
            original, 200, "new-release", "rg-1", "Album", "Aimer", "album", false);
        assertTrue(res.isExtraTrack());
        assertNull(res.getMetadata().getTrackNo());
        assertEquals("9", original.getTrackNo(), "Input MusicMetadata must NOT be mutated in place");
        assertEquals("2", original.getDiscNo());
        assertFalse(original.isExtraTrack());

        // 2. ReviewQueueService 返回深拷贝：锁外篡改返回对象不会污染队列内部状态
        MusicConfig cfg = newConfig();
        ReviewQueueService queue = new ReviewQueueService(cfg);
        File a1 = touch(tempDir.resolve("monitor/AlbumA/01.flac"));
        File b1 = touch(tempDir.resolve("monitor/AlbumB/01.flac"));
        ReviewItem itemA = queue.enqueue(a1.getParent(),
            List.of(new FolderAlbumCache.PendingFile(a1, a1, null, new MusicMetadata(), null)),
            new FolderAlbumCache.CachedAlbumInfo(null, null, "AlbumA", "ArtistA", 1, "", 0.5, FolderAlbumCache.CacheSource.UNKNOWN),
            List.of(new FolderAlbumCache.FolderCandidate("rg-slow", "AlbumA", 1, 1)),
            List.of(200), "ambiguous", 0.5);
        ReviewItem itemB = queue.enqueue(b1.getParent(),
            List.of(new FolderAlbumCache.PendingFile(b1, b1, null, new MusicMetadata(), null)),
            new FolderAlbumCache.CachedAlbumInfo(null, null, "AlbumB", "ArtistB", 1, "", 0.5, FolderAlbumCache.CacheSource.UNKNOWN),
            List.of(new FolderAlbumCache.FolderCandidate("rg-fast", "AlbumB", 1, 1)),
            List.of(180), "ambiguous", 0.5);

        // 2. ReviewQueueService 快照隔离（getSnapshot / list 不外泄可变引用）+ 并发修改与 save() 无 CME
        ReviewItem detached = queue.getSnapshot(itemA.getId());
        detached.setFolderName("MUTATED_OUTSIDE");
        detached.getFiles().clear();
        ReviewItem fresh = queue.getSnapshot(itemA.getId());
        assertEquals("AlbumA", fresh.getFolderName(), "External mutation of snapshot copy must not leak into queue");
        assertEquals(1, fresh.getFiles().size());

        ReviewItem listedCopy = queue.list(ReviewItem.Status.PENDING_REVIEW).stream()
            .filter(i -> itemA.getId().equals(i.getId())).findFirst().orElseThrow();
        listedCopy.setFolderName("MUTATED_LIST");
        assertEquals("AlbumA", queue.getSnapshot(itemA.getId()).getFolderName());

        // 并发向活态条目增删集合元素 + 同时触发 save() 落盘，验证线程安全集合不会抛出 CME
        AtomicBoolean cmeFailure = new AtomicBoolean(false);
        ExecutorService cmePool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch startLatch = new CountDownLatch(1);
            Future<?> mutator = cmePool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < 80; i++) {
                        ReviewItem.OnlineEvidence ev = new ReviewItem.OnlineEvidence();
                        ev.setUrl("https://example.com/" + i);
                        itemA.getOnlineClues().add(ev);
                        itemA.getReasonFacts().put("k" + i, i);
                        if (itemA.getOnlineClues().size() > 10) {
                            itemA.getOnlineClues().remove(0);
                        }
                    }
                } catch (Throwable t) {
                    cmeFailure.set(true);
                }
            });
            Future<?> saver = cmePool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < 40; i++) {
                        queue.update(itemA);
                    }
                } catch (Throwable t) {
                    cmeFailure.set(true);
                }
            });
            startLatch.countDown();
            mutator.get(10, TimeUnit.SECONDS);
            saver.get(10, TimeUnit.SECONDS);
            assertFalse(cmeFailure.get(), "Concurrent ReviewItem mutations + save() must not throw CME");
        } finally {
            cmePool.shutdownNow();
        }

        // 3. ReviewResolutionService.expandCandidates 在持网络 I/O 时不得阻塞另一条目的处置
        CountDownLatch enteredMb = new CountDownLatch(1);
        CountDownLatch releaseMb = new CountDownLatch(1);
        MusicBrainzClient blockingMb = new MusicBrainzClient(cfg) {
            @Override
            public List<AlbumDurationResult> getReleasesForGroup(String releaseGroupId) {
                if ("rg-slow".equals(releaseGroupId)) {
                    enteredMb.countDown();
                    try {
                        releaseMb.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                return List.of(new AlbumDurationResult(List.of(200), releaseGroupId + "-rel", "Title", 1));
            }
        };
        cfg.setDbType("file");
        ProcessedFileLogger fileLogger = new ProcessedFileLogger(cfg, null);
        ReviewResolutionService resolution = new ReviewResolutionService(
            cfg, queue, new FolderAlbumCache(null, null, null), null, blockingMb,
            new DurationSequenceService(), fileLogger);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ReviewItem> slowExpand = pool.submit(() -> resolution.expandCandidates(itemA.getId()));
            assertTrue(enteredMb.await(3, TimeUnit.SECONDS), "Slow expand should enter MusicBrainz call");

            // 当 itemA 卡在 MusicBrainz 网络请求时，itemB 的 reject 必须在毫秒级完成而非阻塞！
            long t0 = System.nanoTime();
            ReviewItem rejectedB = resolution.reject(itemB.getId(), "skip");
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertEquals(ReviewItem.Status.REJECTED, rejectedB.getStatus());
            assertTrue(elapsedMs < 1500, "Rejecting itemB must not block on itemA's network I/O (took " + elapsedMs + "ms)");

            releaseMb.countDown();
            ReviewItem expandedA = slowExpand.get(5, TimeUnit.SECONDS);
            assertTrue(expandedA.isCandidatesExpanded());
            assertEquals("rg-slow-rel", expandedA.getCandidates().get(0).getReleaseId());
        } finally {
            releaseMb.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void d6OnlineSearchQueueTypeIsolationPromotionAndConfigServletRestartSeparation() throws Exception {
        // 1. OnlineSearchQueueService: 封面搜索与专辑搜索按类型隔离去重，不互相吞掉
        MusicConfig cfg = newConfig();
        cfg.setLlmWebSearchMaxConcurrency(1);
        ReviewQueueService reviewQueue = new ReviewQueueService(cfg);
        File f1 = touch(tempDir.resolve("monitor/BlockAlbum/01.flac"));
        File f2 = touch(tempDir.resolve("monitor/TargetAlbum/01.flac"));
        ReviewItem blocker = reviewQueue.enqueueRecoveryFolder(f1.getParent(), "FAILED", List.of(f1), null, "h1");
        ReviewItem.MissingCover mc = new ReviewItem.MissingCover();
        mc.setAlbumTitle("TargetAlbum");
        mc.setAlbumArtist("Artist");
        ReviewItem target = reviewQueue.enqueueMissingCover(f2.getParent(),
            List.of(new FolderAlbumCache.PendingFile(f2, f2, null, new MusicMetadata(), null)), mc, "missing");

        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch blockerRelease = new CountDownLatch(1);
        OnlineIdentificationService stubOnline = new OnlineIdentificationService(
                cfg, reviewQueue, new TagWriterService(cfg), null, null) {
            @Override
            public boolean isAvailable() { return true; }

            @Override
            public ReviewItem searchForExistingItem(String itemId, File folder, boolean analyzeCover,
                                                    SearchProgressListener listener) throws Exception {
                if (blocker.getId().equals(itemId)) {
                    blockerEntered.countDown();
                    blockerRelease.await(5, TimeUnit.SECONDS);
                }
                return reviewQueue.get(itemId);
            }

            @Override
            public ReviewItem search(File folder, String sourceType, boolean analyzeCover,
                                     SearchProgressListener listener) {
                return reviewQueue.get(target.getId());
            }
        };
        CoverSearchService stubCover = new CoverSearchService(cfg, reviewQueue, null) {
            @Override
            public ReviewItem searchCoversForItem(String itemId, String customQuery,
                                                  OnlineIdentificationService.SearchProgressListener listener) {
                return reviewQueue.get(itemId);
            }
        };

        try (OnlineSearchQueueService sq = new OnlineSearchQueueService(cfg, reviewQueue, stubOnline)) {
            sq.setCoverSearchService(stubCover);
            // 先用 blocker 占满唯一并发槽，使后续两个同目录任务都留在 queued 队列里便于观察去重与升格
            sq.enqueueReviewItem(blocker.getId(), false);
            assertTrue(blockerEntered.await(3, TimeUnit.SECONDS));

            // 同一 target 条目先排一个 COVER_MISSING 任务，再排一个专辑 REVIEW_MANUAL 任务 -> 必须独立入队！
            var coverEnq = sq.enqueueMissingCover(target.getId(), "custom");
            var albumEnq = sq.enqueueReviewItem(target.getId(), false);
            assertFalse(coverEnq.alreadyActive());
            assertFalse(albumEnq.alreadyActive(),
                "Album search must NOT dedupe against a Cover search for the same reviewItemId/folder");
            assertNotEquals(coverEnq.task().get("id"), albumEnq.task().get("id"));

            // 升格测试：自动缺曲任务升格为 RECOVERY_MANUAL 时，sourceType/relativePath 必须同步更新
            File f3 = touch(tempDir.resolve("failed/PromoAlbum/01.flac"));
            ReviewItem promoItem = reviewQueue.enqueueMissingTracks(f3.getParent(),
                List.of(new FolderAlbumCache.PendingFile(f3, f3, null, new MusicMetadata(), null)),
                new FolderAlbumCache.CachedAlbumInfo(null, null, "PromoAlbum", "A", 1, "", 0.5, FolderAlbumCache.CacheSource.UNKNOWN),
                List.of(), List.of(200),
                new FolderAlbumCache.MissingTracksInfo("rg", "rel", "PromoAlbum", "A", "album", false, "", 2, 1, 1, "LOCK_CHECK"));
            var autoEnq = sq.enqueueMissingTracksAuto(promoItem);
            assertNotNull(autoEnq);
            var promoted = sq.enqueueRecoveryTarget("FAILED", "PromoAlbum", f3.getParentFile(), 1, true);
            assertTrue(promoted.promoted());
            assertEquals("RECOVERY_MANUAL", promoted.task().get("origin"));
            assertEquals("FAILED", promoted.task().get("sourceType"),
                "Promoted task must update sourceType to match new RECOVERY_MANUAL origin");
            assertEquals("PromoAlbum", promoted.task().get("relativePath"));
            assertFalse(sq.listRecoveryJobViews().isEmpty());

            blockerRelease.countDown();
        }

        // 2. MusicConfig: scanInterval 含空格不崩溃 + saveToFile 写出 llm.webSearch.maxConcurrency
        Path propsFile = tempDir.resolve("config.properties");
        Files.writeString(propsFile, "monitor.scanInterval =  45 \nllm.webSearch.maxConcurrency = 3\n",
            StandardCharsets.UTF_8);
        MusicConfig loadedCfg = newConfig();
        loadedCfg.loadFromFile(propsFile);
        assertEquals(45, loadedCfg.getScanIntervalSeconds());
        assertEquals(3, loadedCfg.getLlmWebSearchMaxConcurrency());

        Path savedPropsFile = tempDir.resolve("saved-config.properties");
        loadedCfg.saveToFile(savedPropsFile);
        Properties savedProps = new Properties();
        try (var reader = Files.newBufferedReader(savedPropsFile, StandardCharsets.UTF_8)) {
            savedProps.load(reader);
        }
        assertEquals("3", savedProps.getProperty("llm.webSearch.maxConcurrency"));

        // 3. ConfigServlet: 需重启字段仅落盘不篡改活态 MusicConfig；清空 tavilyIncludeDomains 重启不回弹
        MusicConfig liveCfg = newConfig();
        String origMonitorDir = liveCfg.getMonitorDirectory();
        liveCfg.setDbType("sqlite");
        liveCfg.setTavilyIncludeDomains(new ArrayList<>(List.of("musicbrainz.org", "discogs.com")));
        Path servletProps = tempDir.resolve("servlet-config.properties");

        AdminCredentialsStore credsStore = new AdminCredentialsStore(tempDir.resolve("data/admin-d6.json"));
        Server server = new Server(0);
        ServletContextHandler ctx = new ServletContextHandler(ServletContextHandler.SESSIONS);
        ctx.addFilter(AuthFilter.class, "/api/*", EnumSet.of(DispatcherType.REQUEST));
        ctx.addServlet(new ServletHolder(new AuthServlet(credsStore)), "/api/auth/*");
        ctx.addServlet(new ServletHolder(new ConfigServlet(liveCfg, servletProps)), "/api/config");
        server.setHandler(ctx);
        server.start();
        try {
            String base = "http://127.0.0.1:" + ((ServerConnector) server.getConnectors()[0]).getLocalPort();
            CookieManager cookies = new CookieManager();
            HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

            var setupResp = http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/setup"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"password123\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, setupResp.statusCode());
            String csrf = gson.fromJson(setupResp.body(), JsonObject.class).get("csrfToken").getAsString();

            String newMonitorDir = tempDir.resolve("next-monitor").toAbsolutePath().toString();
            String putBody = gson.toJson(Map.of(
                "dbType", "mysql",
                "monitorDirectory", newMonitorDir,
                "tavilyIncludeDomains", "",
                "reviewEnabled", true
            ));
            var putResp = http.send(HttpRequest.newBuilder(URI.create(base + "/api/config"))
                .header("X-CSRF-Token", csrf)
                .PUT(HttpRequest.BodyPublishers.ofString(putBody)).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, putResp.statusCode());

            // 活态内存：热更新字段立即生效，需重启字段保持旧值防运行时脑裂！
            assertTrue(liveCfg.isReviewEnabled());
            assertEquals("sqlite", liveCfg.getDbType(), "Live dbType must NOT mutate before restart");
            assertEquals(origMonitorDir, liveCfg.getMonitorDirectory(),
                "Live monitorDirectory must NOT mutate before restart");
            assertTrue(liveCfg.getTavilyIncludeDomains().isEmpty());

            // GET /api/config 仍回显刚保存的待重启值，并上报 pendingRestartFields
            JsonObject putJson = gson.fromJson(putResp.body(), JsonObject.class);
            assertTrue(putJson.get("requiresRestart").getAsBoolean());
            assertEquals("mysql", putJson.getAsJsonObject("config").get("dbType").getAsString());

            // 磁盘持久化：重启后加载新 config.properties，tavilyIncludeDomains 保持空列表不回弹默认值！
            MusicConfig reloaded = newConfig();
            reloaded.loadFromFile(servletProps);
            assertEquals("mysql", reloaded.getDbType());
            assertEquals(newMonitorDir, reloaded.getMonitorDirectory());
            assertTrue(reloaded.getTavilyIncludeDomains().isEmpty(),
                "Cleared tavilyIncludeDomains must stay empty after restart instead of rebounding to defaults");
        } finally {
            server.stop();
        }
    }
}
