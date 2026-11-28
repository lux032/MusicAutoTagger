package com.lux032.musicautotagger.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.llm.LlmClient;
import com.lux032.musicautotagger.service.llm.NativeWebSearchClient;
import com.lux032.musicautotagger.service.llm.TavilyWebSearchClient;
import com.lux032.musicautotagger.service.llm.TrackCreditEnrichmentService;
import com.lux032.musicautotagger.service.llm.WebPageFetcher;
import com.lux032.musicautotagger.service.llm.WebSearchClient;
import com.lux032.musicautotagger.util.FileSystemUtils;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 双引擎（OpenAI /v1/responses 原生联网 + Tavily 开放域补全）端到端验证：
 * 覆盖多曲 EP（含 Remix/特殊版本与未入池捏造来源拦截）、队列持久化（无密钥泄露）与原子归档落盘。
 */
class DualEngineOnlineCreditsEndToEndTest {

    @TempDir Path tempDir;

    private static final String FAKE_SECRET_LLM = "sk-SUPER-SECRET-SHOULD-NEVER-LEAK-9999";
    private static final String FAKE_SECRET_TAVILY = "tvly-SUPER-SECRET-SHOULD-NEVER-LEAK-8888";

    private MusicConfig config;
    private Path monitorDir;
    private Path outputDir;
    private Path queuePath;
    private ReviewQueueService reviewQueue;
    private TagWriterService tagWriter;
    private ProcessedFileLogger processedLogger;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        config = ctor.newInstance();
        monitorDir = Files.createDirectories(tempDir.resolve("monitor"));
        outputDir = Files.createDirectories(tempDir.resolve("output"));
        queuePath = tempDir.resolve("data/review-queue.json");
        config.setMonitorDirectory(monitorDir.toString());
        config.setOutputDirectory(outputDir.toString());
        config.setPartialDirectory(tempDir.resolve("partial").toString());
        config.setFailedDirectory(tempDir.resolve("failed").toString());
        config.setReviewQueuePath(queuePath.toString());
        config.setReviewStagingDirectory(tempDir.resolve("data/review-staging").toString());
        config.setRecoveryWorkDirectory(tempDir.resolve("data/recovery-work").toString());
        config.setRecoveryTrashDirectory(tempDir.resolve("data/recovery-trash").toString());
        config.setProcessedFileLogPath(tempDir.resolve("processed.log").toString());
        config.setAutoRename(true);
        config.setExportLyricsToFile(false);
        config.setCoverMissingRequireReview(false);
        config.setTavilyApiKey(FAKE_SECRET_TAVILY);
        config.setOnlineCreditsEnrichEnabled(true);
        config.setOnlineCreditsApplyProbable(true);
        config.setReleaseCountryPriority(List.of("JP"));

        reviewQueue = new ReviewQueueService(config);
        tagWriter = new TagWriterService(config);
        processedLogger = new ProcessedFileLogger(config, null);
    }

    @Test
    void nativeResponsesEndToEndParsesActionSourcesVerifiesRemixGuardAndArchivesCleanly() throws Exception {
        Path albumDir = Files.createDirectory(monitorDir.resolve("HIMEHINA - VAMPY (Native)"));
        createFlac(albumDir.resolve("01. VAMPY.flac"), "VAMPY", "HIMEHINA", "VAMPY", "1");
        createFlac(albumDir.resolve("02. VAMPY (Latin Night Version).flac"),
            "VAMPY (Latin Night Version)", "HIMEHINA", "VAMPY", "2");

        // 模拟真实 OpenAI /v1/responses 严格 JSON 返回：content[].annotations 为空，来源在 action.sources + open_page 且带 ?utm_source=openai
        String rawOpenAiResponses = """
            {
              "model": "gpt-6-luna",
              "usage": {"input_tokens": 12000, "output_tokens": 800, "total_tokens": 12800,
                        "input_tokens_details": {"cached_tokens": 2000},
                        "output_tokens_details": {"reasoning_tokens": 150}},
              "output": [
                {
                  "type": "web_search_call",
                  "action": {
                    "type": "search",
                    "sources": [
                      {"url": "https://ototoy.jp/_/default/p/2319747/?utm_source=openai", "title": "OTOTOY VAMPY"},
                      {"url": "https://himehina.jp/discography/vampy?utm_source=openai", "title": "HIMEHINA Official"}
                    ]
                  }
                },
                {
                  "type": "web_search_call",
                  "action": {
                    "type": "open_page",
                    "url": "https://himehina.jp/discography/vampy?utm_source=openai"
                  }
                },
                {
                  "type": "message",
                  "content": [
                    {
                      "type": "output_text",
                      "annotations": [],
                      "text": "{\\"needs_second_round\\":false,\\"candidates\\":[{\\"title\\":\\"VAMPY\\",\\"artist\\":\\"HIMEHINA\\",\\"album_artist\\":\\"HIMEHINA\\",\\"release_date\\":\\"2026-11-27\\",\\"country\\":\\"JP\\",\\"confidence\\":0.96,\\"source_urls\\":[\\"https://ototoy.jp/_/default/p/2319747\\",\\"https://himehina.jp/discography/vampy\\"],\\"tracks\\":[{\\"disc\\":1,\\"track\\":1,\\"title\\":\\"VAMPY\\",\\"artist\\":\\"HIMEHINA\\",\\"duration\\":120,\\"version_type\\":\\"original\\",\\"credit_detail\\":{\\"lyricists\\":{\\"names\\":[\\"Gohgo\\"],\\"status\\":\\"verified\\",\\"evidence\\":[{\\"url\\":\\"https://himehina.jp/discography/vampy\\",\\"source_type\\":\\"artist_official\\",\\"quote\\":\\"作詞：Gohgo\\",\\"version_match\\":\\"exact_version\\"}]},\\"composers\\":{\\"names\\":[\\"Soma Genda\\"],\\"status\\":\\"verified\\",\\"evidence\\":[{\\"url\\":\\"https://himehina.jp/discography/vampy\\",\\"source_type\\":\\"artist_official\\",\\"quote\\":\\"作曲：Soma Genda\\",\\"version_match\\":\\"exact_version\\"}]},\\"arrangers\\":{\\"names\\":[\\"Soma Genda\\"],\\"status\\":\\"verified\\",\\"evidence\\":[{\\"url\\":\\"https://himehina.jp/discography/vampy\\",\\"source_type\\":\\"artist_official\\",\\"quote\\":\\"編曲：Soma Genda\\",\\"version_match\\":\\"exact_version\\"}]}}},{\\"disc\\":1,\\"track\\":2,\\"title\\":\\"VAMPY (Latin Night Version)\\",\\"artist\\":\\"HIMEHINA\\",\\"duration\\":120,\\"version_type\\":\\"alternate_arrangement\\",\\"credit_detail\\":{\\"lyricists\\":{\\"names\\":[\\"Gohgo\\"],\\"status\\":\\"verified\\",\\"evidence\\":[{\\"url\\":\\"https://himehina.jp/discography/vampy\\",\\"source_type\\":\\"artist_official\\",\\"quote\\":\\"作詞：Gohgo\\",\\"version_match\\":\\"original_only\\"}]},\\"composers\\":{\\"names\\":[\\"Soma Genda\\"],\\"status\\":\\"verified\\",\\"evidence\\":[{\\"url\\":\\"https://himehina.jp/discography/vampy\\",\\"source_type\\":\\"artist_official\\",\\"quote\\":\\"作曲：Soma Genda\\",\\"version_match\\":\\"original_only\\"}]},\\"arrangers\\":{\\"names\\":[\\"Soma Genda\\"],\\"status\\":\\"verified\\",\\"evidence\\":[{\\"url\\":\\"https://himehina.jp/discography/vampy\\",\\"source_type\\":\\"artist_official\\",\\"quote\\":\\"編曲：Soma Genda\\",\\"version_match\\":\\"original_only\\"}]}}}]}]}"
                    }
                  ]
                }
              ]
            }
            """;

        JsonObject rawJson = JsonParser.parseString(rawOpenAiResponses).getAsJsonObject();
        NativeWebSearchClient nativeParser = new NativeWebSearchClient(config);
        var parseMethod = NativeWebSearchClient.class.getDeclaredMethod("parseOpenAiResponse", int.class, String.class, JsonObject.class);
        parseMethod.setAccessible(true);
        WebSearchClient.SearchResponse parsedNativeResp =
            (WebSearchClient.SearchResponse) parseMethod.invoke(nativeParser, 0, "gpt-6-luna", rawJson);
        assertEquals(2, parsedNativeResp.getCitations().size());
        assertNotNull(parsedNativeResp.getTokenUsage());
        assertEquals(12000, parsedNativeResp.getTokenUsage().promptTokens());

        WebSearchClient stubNativeClient = new WebSearchClient() {
            @Override public String name() { return "native"; }
            @Override public boolean hasEnabledEndpoint() { return true; }
            @Override public SearchResponse search(String systemPrompt, String userPrompt) { return parsedNativeResp; }
        };

        // 禁用测试内真实外网请求，模拟原生模式站点返回 403 时优雅沿用服务端 open_page 核验
        WebPageFetcher offlineFetcher = new WebPageFetcher(config, null) {
            @Override
            public FetchedPage fetchDirectOnly(String rawUrl, String artistHint) {
                return FetchedPage.failed(rawUrl, "artist_official", "direct_http", "http_403");
            }
        };

        AudioFingerprintService stubFp = new AudioFingerprintService(config) {
            @Override public List<Integer> extractDurationSequence(List<File> files) { return List.of(120, 120); }
        };

        ReviewItem item;
        try (OnlineIdentificationService svc = new OnlineIdentificationService(
                config, reviewQueue, tagWriter, stubFp, null, stubNativeClient, offlineFetcher, null)) {
            item = svc.search(albumDir.toFile(), "MANUAL", false);
        }

        ReviewItem persisted = reviewQueue.get(item.getId());
        assertNotNull(persisted);
        assertEquals(1, persisted.getOnlineCandidates().size(), "Native search with empty annotations must pass gate via action.sources");
        ReviewItem.OnlineCandidate cand = persisted.getOnlineCandidates().get(0);
        ReviewItem.OnlineTrack t1 = cand.getTracks().get(0);
        ReviewItem.OnlineTrack t2 = cand.getTracks().get(1);

        assertEquals(ReviewItem.CreditStatus.VERIFIED, t1.getCreditDetail().getArrangers().getStatus());
        assertEquals(List.of("Soma Genda"), t1.getArrangers());
        assertEquals(ReviewItem.CreditStatus.PROBABLE, t2.getCreditDetail().getLyricists().getStatus());
        assertEquals(ReviewItem.CreditStatus.NOT_FOUND, t2.getCreditDetail().getArrangers().getStatus(),
            "Alt version must NOT inherit original arranger");
        assertTrue(t2.getArrangers().isEmpty());

        // 确认落盘到真实 FLAC 文件
        try (RecoveryService recovery = new RecoveryService(
                config, null, processedLogger, reviewQueue, new FolderAlbumCache(null, null, null), null,
                new FileSystemUtils(config), tagWriter, stubFp, null, null)) {
            recovery.confirmOnlineCandidate(persisted.getId(), cand.getId(),
                "VAMPY", "HIMEHINA", "2026-11-27", "", null, null,
                RecoveryService.DuplicatePolicy.ASK, null, true, null);
        }

        MusicMetadata out1 = tagWriter.readTags(outputDir.resolve("HIMEHINA/VAMPY/1.01 HIMEHINA - VAMPY.flac").toFile());
        MusicMetadata out2 = tagWriter.readTags(outputDir.resolve("HIMEHINA/VAMPY/1.02 HIMEHINA - VAMPY (Latin Night Version).flac").toFile());
        assertNotNull(out1);
        assertEquals("Gohgo", out1.getLyricist());
        assertEquals("Soma Genda", out1.getComposer());
        assertEquals("Soma Genda", out1.getArranger());

        assertNotNull(out2);
        assertEquals("Gohgo", out2.getLyricist());
        assertEquals("Soma Genda", out2.getComposer());
        assertNull(out2.getArranger(), "Latin Night Version arranger must remain empty when unattested");

        // 确认 review-queue.json 中绝对没有密钥泄露
        String queueJson = Files.readString(queuePath);
        assertFalse(queueJson.contains(FAKE_SECRET_LLM));
        assertFalse(queueJson.contains(FAKE_SECRET_TAVILY));
    }

    @Test
    void tavilyDualStageEnrichmentEndToEndPersistsAndArchivesCleanly() throws Exception {
        Path albumDir = Files.createDirectory(monitorDir.resolve("HIMEHINA - VAMPY (Tavily)"));
        createFlac(albumDir.resolve("01. Drain Drain.flac"), "Drain Drain", "HIMEHINA", "VAMPY", "1");

        String officialUrl = "https://himehina.jp/discography/vampy";
        String officialCanon = WebSearchClient.canonicalizeUrl(officialUrl);
        String pageText = "VAMPY EP: Drain Drain 作詞：Gohgo 作曲：南田健吾 編曲：南田健吾";

        WebSearchClient stubTavilyAlbumSearch = new WebSearchClient() {
            @Override public String name() { return "tavily"; }
            @Override public boolean hasEnabledEndpoint() { return true; }
            @Override
            public SearchResponse search(String systemPrompt, String userPrompt) {
                SearchResponse r = new SearchResponse();
                r.setProvider("tavily+stub");
                r.setModel("stub-chat");
                r.addCitation(Citation.of("https://ototoy.jp/_/default/p/2319747/", "OTOTOY VAMPY", "HIMEHINA VAMPY"));
                r.setText("""
                    {"needs_second_round":false,"candidates":[{
                      "title":"VAMPY","artist":"HIMEHINA","album_artist":"HIMEHINA","release_date":"2026-11-27","country":"JP",
                      "confidence":0.95,"source_urls":["https://ototoy.jp/_/default/p/2319747/"],
                      "tracks":[{"disc":1,"track":1,"title":"Drain Drain","artist":"HIMEHINA","duration":120}]
                    }]}
                    """);
                return r;
            }
        };

        TavilyWebSearchClient stubRawTavily = new TavilyWebSearchClient(config, new LlmClient(config)) {
            @Override
            public List<Citation> searchRaw(String query, String searchDepth, int maxResults, List<String> includeDomains) {
                return List.of(Citation.of(officialUrl, "HIMEHINA VAMPY", "Drain Drain 作詞：Gohgo"));
            }
        };
        WebPageFetcher stubFetcher = new WebPageFetcher(config, stubRawTavily) {
            @Override
            public Map<String, FetchedPage> fetchBatch(List<String> rawUrls, String artistHint, boolean allowExtract) {
                return Map.of(officialCanon, new FetchedPage(
                    officialUrl, officialCanon, "artist_official", true, "direct_http", "ok", pageText, pageText));
            }
        };
        LlmClient stubLlm = new LlmClient(config) {
            @Override public boolean isConfigured() { return true; }
            @Override
            public LlmResponse complete(String systemPrompt, String userPrompt, int maxTokens) {
                return new LlmResponse("""
                    {"tracks":[{"index":1,"title":"Drain Drain","version_type":"original","credit_detail":{
                      "lyricists":{"names":["Gohgo"],"status":"verified","evidence":[{"url":"https://himehina.jp/discography/vampy","source_type":"artist_official","quote":"作詞：Gohgo","version_match":"exact_version"}]},
                      "composers":{"names":["南田健吾"],"status":"verified","evidence":[{"url":"https://himehina.jp/discography/vampy","source_type":"artist_official","quote":"作曲：南田健吾","version_match":"exact_version"}]},
                      "arrangers":{"names":["南田健吾"],"status":"verified","evidence":[{"url":"https://himehina.jp/discography/vampy","source_type":"artist_official","quote":"編曲：南田健吾","version_match":"exact_version"}]}
                    }}]}
                    """, "stub-chat", "stub");
            }
        };

        TrackCreditEnrichmentService enricher =
            new TrackCreditEnrichmentService(config, stubRawTavily, stubLlm, stubFetcher);
        AudioFingerprintService stubFp = new AudioFingerprintService(config) {
            @Override public List<Integer> extractDurationSequence(List<File> files) { return List.of(120); }
        };

        ReviewItem item;
        try (OnlineIdentificationService svc = new OnlineIdentificationService(
                config, reviewQueue, tagWriter, stubFp, null, stubTavilyAlbumSearch, stubFetcher, enricher)) {
            item = svc.search(albumDir.toFile(), "MANUAL", false);
        }

        ReviewItem persisted = reviewQueue.get(item.getId());
        ReviewItem.OnlineCandidate cand = persisted.getOnlineCandidates().get(0);
        assertEquals(List.of("南田健吾"), cand.getTracks().get(0).getComposers());
        assertEquals(ReviewItem.CreditStatus.VERIFIED, cand.getTracks().get(0).getCreditDetail().getArrangers().getStatus());

        try (RecoveryService recovery = new RecoveryService(
                config, null, processedLogger, reviewQueue, new FolderAlbumCache(null, null, null), null,
                new FileSystemUtils(config), tagWriter, stubFp, null, null)) {
            recovery.confirmOnlineCandidate(persisted.getId(), cand.getId(),
                "VAMPY Tavily", "HIMEHINA", "2026-11-27", "", null, null,
                RecoveryService.DuplicatePolicy.ASK, null, true, null);
        }

        MusicMetadata out = tagWriter.readTags(outputDir.resolve("HIMEHINA/VAMPY Tavily/1.01 HIMEHINA - Drain Drain.flac").toFile());
        assertNotNull(out);
        assertEquals("Gohgo", out.getLyricist());
        assertEquals("南田健吾", out.getComposer());
        assertEquals("南田健吾", out.getArranger());
        assertFalse(Files.readString(queuePath).contains(FAKE_SECRET_TAVILY));
    }

    private static File createFlac(Path target, String title, String artist, String album, String trackNo) throws Exception {
        Files.createDirectories(target.getParent());
        ByteBuffer bytes = ByteBuffer.allocate(554);
        bytes.put(new byte[]{'f', 'L', 'a', 'C', (byte) 128, 0, 0, 34});
        bytes.putLong(18, (44100L << 44) | (1L << 41) | (15L << 36) | 5303025L);
        for (int i = 42; i < bytes.capacity(); i++) bytes.put(i, (byte) (i * 13 + trackNo.hashCode()));
        Files.write(target, bytes.array());
        var audio = AudioFileIO.read(target.toFile());
        Tag tag = audio.getTagOrCreateAndSetDefault();
        tag.setField(FieldKey.TITLE, title);
        tag.setField(FieldKey.ARTIST, artist);
        tag.setField(FieldKey.ALBUM, album);
        tag.setField(FieldKey.ALBUM_ARTIST, artist);
        tag.setField(FieldKey.TRACK, trackNo);
        audio.commit();
        return target.toFile();
    }
}
