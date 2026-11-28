package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.llm.LlmArtistSplitter;
import com.lux032.musicautotagger.service.llm.LlmClient;
import com.lux032.musicautotagger.service.llm.TavilyWebSearchClient;
import com.lux032.musicautotagger.service.llm.WebSearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OnlineIdentificationServiceCreditsAndRegionTest {

    @TempDir Path dir;

    private MusicConfig config;
    private ReviewQueueService reviewQueue;
    private TagWriterService tagWriter;
    private OnlineIdentificationService service;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        config = ctor.newInstance();
        config.setReviewQueuePath(dir.resolve("review.json").toString());
        config.setReviewStagingDirectory(dir.resolve("staging").toString());
        reviewQueue = new ReviewQueueService(config);
        tagWriter = new TagWriterService(config);
        service = new OnlineIdentificationService(config, reviewQueue, tagWriter, null, null);
    }

    @Test
    void parsesTrackCreatorCreditsCleansDuplicatesAndHandlesOmittedFields() throws Exception {
        WebSearchClient.SearchResponse resp = new WebSearchClient.SearchResponse();
        resp.addCitation(WebSearchClient.Citation.of("https://vgmdb.net/album/12345", "Album Page", "credits"));
        resp.setText("""
            {
              "needs_second_round": false,
              "candidates": [
                {
                  "title": "Sample OST",
                  "artist": "梶浦由記",
                  "artists": ["梶浦由記"],
                  "album_artist": "梶浦由記",
                  "album_artists": ["梶浦由記"],
                  "release_date": "2026-01-01",
                  "country": "JP",
                  "confidence": 0.92,
                  "source_urls": ["https://vgmdb.net/album/12345"],
                  "tracks": [
                    {
                      "disc": 1,
                      "track": 1,
                      "title": "Track One",
                      "artist": "梶浦由記",
                      "artists": ["梶浦由記"],
                      "composers": [" 梶浦由記 ", "", "梶浦由記", "澤野弘之"],
                      "lyricists": ["  ", "梶浦由記", null, 123, true, {}],
                      "arrangers": ["森空青", "森空青"],
                      "duration": 240
                    },
                    {
                      "disc": 1,
                      "track": 2,
                      "title": "Track Two",
                      "artist": "梶浦由記",
                      "duration": 180
                    }
                  ]
                }
              ],
              "clues": []
            }
            """);

        OnlineIdentificationService.Parsed parsed = service.parse(resp);
        assertEquals(1, parsed.candidates.size());
        List<ReviewItem.OnlineTrack> tracks = parsed.candidates.get(0).getTracks();
        assertEquals(2, tracks.size());

        ReviewItem.OnlineTrack t1 = tracks.get(0);
        assertEquals(List.of("梶浦由記", "澤野弘之"), t1.getComposers());
        assertEquals(List.of("梶浦由記"), t1.getLyricists());
        assertEquals(List.of("森空青"), t1.getArrangers());

        ReviewItem.OnlineTrack t2 = tracks.get(1);
        assertNotNull(t2.getComposers());
        assertTrue(t2.getComposers().isEmpty());
        assertNotNull(t2.getLyricists());
        assertTrue(t2.getLyricists().isEmpty());
        assertNotNull(t2.getArrangers());
        assertTrue(t2.getArrangers().isEmpty());
    }

    @Test
    void promptsRespectReleaseCountryPriorityDynamicallyAndIncludeCreditGuardrails() throws Exception {
        Path albumDir = Files.createDirectory(dir.resolve("Yuki Kajiura - OST"));
        File f1 = Files.writeString(albumDir.resolve("01. Track.flac"), "dummy").toFile();

        // 1. 空配置：不附加地区本地化指令，但包含幕后人员 schema 与防幻觉规则
        config.setReleaseCountryPriority(List.of());
        String emptyPrompt = service.buildPrompt(albumDir.toFile(), List.of(f1), List.of(240), false);
        assertFalse(emptyPrompt.contains("PREFERRED RELEASE REGIONS"));
        assertTrue(emptyPrompt.contains("\"composers\":[string]"));
        assertTrue(emptyPrompt.contains("\"lyricists\":[string]"));
        assertTrue(emptyPrompt.contains("\"arrangers\":[string]"));
        assertTrue(emptyPrompt.contains("NEVER infer or assume the performer/artist/album_artist is the composer, lyricist, or arranger"));

        // 2. 单地区 JP：动态生效，注入日文偏好、允许转换、保留拉丁艺名与欧美艺人、保持官方曲名
        config.setReleaseCountryPriority(List.of("jp"));
        String jpPrompt = service.buildPrompt(albumDir.toFile(), List.of(f1), List.of(240), false);
        assertTrue(jpPrompt.contains("PREFERRED RELEASE REGIONS (priority order): JP"));
        assertTrue(jpPrompt.contains("Primary naming region: JP (Japanese / 日本語 (Kanji & Kana))"));
        assertTrue(jpPrompt.contains("YOASOBI"));
        assertTrue(jpPrompt.contains("Taylor Swift"));
        assertTrue(jpPrompt.contains("do not freely translate track titles"));

        // 3. 多地区 XW, JP, US：跳过通用 XW，选 JP 作为主本地化地区，同时在 missing-tracks prompt 中同样生效
        config.setReleaseCountryPriority(List.of("XW", "JP", "US"));
        ReviewItem item = new ReviewItem();
        ReviewItem.MissingTracks missing = new ReviewItem.MissingTracks();
        missing.setAlbumTitle("OST");
        missing.setAlbumArtist("Yuki Kajiura");
        missing.setReleaseTrackCount(1);
        item.setMissingTracks(missing);
        String missingPrompt = service.buildMissingTracksPrompt(item, albumDir.toFile(), List.of(f1), List.of(240));
        assertTrue(missingPrompt.contains("PREFERRED RELEASE REGIONS (priority order): XW, JP, US"));
        assertTrue(missingPrompt.contains("Primary naming region: JP"));
    }

    @Test
    void tavilyQueryPromptReceivesPreferredReleaseRegionsAndNativeGuidance() throws Exception {
        AtomicReference<String> capturedSystem = new AtomicReference<>();
        AtomicReference<String> capturedUser = new AtomicReference<>();
        LlmClient stubLlm = new LlmClient(config) {
            @Override
            public LlmResponse complete(String systemPrompt, String userPrompt, int maxTokens) {
                capturedSystem.set(systemPrompt);
                capturedUser.set(userPrompt);
                return new LlmResponse("{\"queries\":[\"梶浦由記 OST 作詞 作曲 編曲\"]}", "stub-model", "stub");
            }
        };
        TavilyWebSearchClient tavily = new TavilyWebSearchClient(config, stubLlm);
        config.setReleaseCountryPriority(List.of("JP", "US"));
        Path albumDir = Files.createDirectory(dir.resolve("AlbumForTavily"));
        File f1 = Files.writeString(albumDir.resolve("01. Song.flac"), "dummy").toFile();
        String prompt = service.buildPrompt(albumDir.toFile(), List.of(f1), List.of(200), false);

        Method generateQueries = TavilyWebSearchClient.class.getDeclaredMethod("generateQueries", String.class);
        generateQueries.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> queries = (List<String>) generateQueries.invoke(tavily, prompt);

        assertEquals(List.of("梶浦由記 OST 作詞 作曲 編曲"), queries);
        assertNotNull(capturedSystem.get());
        assertTrue(capturedSystem.get().contains("PREFERRED RELEASE REGIONS"));
        assertTrue(capturedSystem.get().contains("track credits"));
        assertNotNull(capturedUser.get());
        assertTrue(capturedUser.get().contains("PREFERRED RELEASE REGIONS (priority order): JP, US"));
    }

    @Test
    void matchesCanonicalSourceUrlsAndRejectsFabricatedUrlsNotInPool() throws Exception {
        WebSearchClient.SearchResponse resp = new WebSearchClient.SearchResponse();
        // 模拟原生 /v1/responses 动作池返回带追踪参数与尾斜杠的 URL
        resp.addCitation(WebSearchClient.Citation.of(
            "https://music.apple.com/jp/album/vampy-ep/6833337851/?utm_source=openai", "Apple Music", null, false));
        resp.addCitation(WebSearchClient.Citation.of(
            "https://ototoy.jp/_/default/p/2319747/", "OTOTOY", null, true));
        resp.setText("""
            {
              "needs_second_round": false,
              "candidates": [
                {
                  "title": "VAMPY",
                  "artist": "HIMEHINA",
                  "release_date": "2026-11-27",
                  "confidence": 0.95,
                  "source_urls": [
                    "https://music.apple.com/jp/album/vampy-ep/6833337851",
                    "https://musicbrainz.org/release/fabricated-id"
                  ],
                  "tracks": [{"disc": 1, "track": 1, "title": "VAMPY", "artist": "HIMEHINA"}]
                },
                {
                  "title": "Fabricated Only",
                  "artist": "Nobody",
                  "confidence": 0.99,
                  "source_urls": ["https://musicbrainz.org/release/not-in-pool"],
                  "tracks": []
                }
              ]
            }
            """);

        OnlineIdentificationService.Parsed parsed = service.parse(resp);
        assertEquals(1, parsed.candidates.size());
        ReviewItem.OnlineCandidate accepted = parsed.candidates.get(0);
        assertEquals("VAMPY", accepted.getTitle());
        // 只保留在来源池中规范化命中的 Apple Music，捏造的 MusicBrainz URL 必须被过滤
        assertEquals(1, accepted.getSources().size());
        assertEquals("https://music.apple.com/jp/album/vampy-ep/6833337851/", accepted.getSources().get(0).getUrl());
    }

    @Test
    void nativeSearchParsesAndVerifiesStructuredCreditDetailAndTriggersTavilyEnrichmentInTavilyMode() throws Exception {
        // 1. 原生模式：单轮直接产出结构化 credit_detail，走 CreditEvidenceVerifier 与免费直连复核
        WebSearchClient.SearchResponse nativeResp = new WebSearchClient.SearchResponse();
        nativeResp.setProvider("openai-compatible");
        nativeResp.setModel("gpt-6-luna");
        nativeResp.addCitation(WebSearchClient.Citation.of("https://ototoy.jp/_/default/p/2319747/", "OTOTOY VAMPY", "", true));
        nativeResp.addCitation(WebSearchClient.Citation.of("https://himehina.jp/discography/vampy?utm_source=openai", "HIMEHINA Official", "", true));
        nativeResp.setText("""
            {
              "needs_second_round": false,
              "candidates": [{
                "title": "VAMPY",
                "artist": "HIMEHINA",
                "album_artist": "HIMEHINA",
                "release_date": "2026-11-27",
                "confidence": 0.95,
                "source_urls": ["https://ototoy.jp/_/default/p/2319747", "https://himehina.jp/discography/vampy"],
                "tracks": [
                  {
                    "disc": 1, "track": 1, "title": "VAMPY", "artist": "HIMEHINA",
                    "version_type": "original",
                    "credit_detail": {
                      "lyricists": {"names": ["Gohgo"], "status": "verified", "evidence": [{"url": "https://himehina.jp/discography/vampy", "source_type": "artist_official", "quote": "作詞：Gohgo", "version_match": "exact_version"}]},
                      "composers": {"names": ["Soma Genda"], "status": "verified", "evidence": [{"url": "https://himehina.jp/discography/vampy", "source_type": "artist_official", "quote": "作曲：Soma Genda", "version_match": "exact_version"}]},
                      "arrangers": {"names": ["Fake Name"], "status": "verified", "evidence": [{"url": "https://fabricated.example.com/fake", "source_type": "artist_official", "quote": "編曲：Fake Name", "version_match": "exact_version"}]}
                    }
                  },
                  {
                    "disc": 1, "track": 2, "title": "VAMPY (Latin Night Version)", "artist": "HIMEHINA",
                    "version_type": "alternate_arrangement",
                    "credit_detail": {
                      "lyricists": {"names": ["Gohgo"], "status": "verified", "evidence": [{"url": "https://himehina.jp/discography/vampy", "source_type": "artist_official", "quote": "作詞：Gohgo", "version_match": "original_only"}]},
                      "composers": {"names": ["Soma Genda"], "status": "verified", "evidence": [{"url": "https://himehina.jp/discography/vampy", "source_type": "artist_official", "quote": "作曲：Soma Genda", "version_match": "original_only"}]},
                      "arrangers": {"names": ["Soma Genda"], "status": "verified", "evidence": [{"url": "https://himehina.jp/discography/vampy", "source_type": "artist_official", "quote": "編曲：Soma Genda", "version_match": "original_only"}]}
                    }
                  }
                ]
              }]
            }
            """);

        OnlineIdentificationService.Parsed parsed = service.parse(nativeResp);
        assertEquals(1, parsed.candidates.size());
        ReviewItem.OnlineTrack t1 = parsed.candidates.get(0).getTracks().get(0);
        assertEquals(ReviewItem.CreditStatus.VERIFIED, t1.getCreditDetail().getLyricists().getStatus());
        assertEquals(List.of("Gohgo"), t1.getLyricists());
        assertEquals(ReviewItem.CreditStatus.NOT_FOUND, t1.getCreditDetail().getArrangers().getStatus(),
            "Fabricated evidence URL must be rejected");
        assertTrue(t1.getArrangers().isEmpty());

        ReviewItem.OnlineTrack t2 = parsed.candidates.get(0).getTracks().get(1);
        assertEquals(ReviewItem.CreditStatus.PROBABLE, t2.getCreditDetail().getLyricists().getStatus());
        assertEquals(ReviewItem.CreditStatus.NOT_FOUND, t2.getCreditDetail().getArrangers().getStatus(),
            "Alt version must not inherit original arranger");
        assertTrue(t2.getArrangers().isEmpty());

        // 2. Tavily 模式：首轮匹配后触发 TrackCreditEnrichmentService 并安全深拷贝落盘
        Path albumDir = Files.createDirectory(dir.resolve("HIMEHINA - VAMPY"));
        Files.writeString(albumDir.resolve("01. VAMPY.flac"), "dummy");

        WebSearchClient stubTavilySearch = new WebSearchClient() {
            @Override public String name() { return "tavily"; }
            @Override public boolean hasEnabledEndpoint() { return true; }
            @Override
            public SearchResponse search(String systemPrompt, String userPrompt) {
                SearchResponse r = new SearchResponse();
                r.setProvider("tavily+stub");
                r.setModel("stub");
                r.addCitation(Citation.of("https://ototoy.jp/_/default/p/2319747/", "OTOTOY VAMPY", "VAMPY"));
                r.setText("""
                    {"needs_second_round":false,"candidates":[{
                      "title":"VAMPY","artist":"HIMEHINA","album_artist":"HIMEHINA","release_date":"2026-11-27",
                      "confidence":0.9,"source_urls":["https://ototoy.jp/_/default/p/2319747/"],
                      "tracks":[{"disc":1,"track":1,"title":"VAMPY","artist":"HIMEHINA"}]
                    }]}
                    """);
                return r;
            }
        };

        AtomicReference<Boolean> enrichmentTriggered = new AtomicReference<>(false);
        com.lux032.musicautotagger.service.llm.TrackCreditEnrichmentService stubEnricher =
            new com.lux032.musicautotagger.service.llm.TrackCreditEnrichmentService(config, null, null, null) {
                @Override
                public EnrichmentSummary enrich(ReviewItem.OnlineCandidate candidate,
                                                java.util.Map<String, com.lux032.musicautotagger.model.MusicMetadata> existing,
                                                WebSearchClient.ProgressListener progress) {
                    enrichmentTriggered.set(true);
                    ReviewItem.OnlineTrack tr = candidate.getTracks().get(0);
                    ReviewItem.TrackCreditDetail d = new ReviewItem.TrackCreditDetail();
                    d.setVersionType("original");
                    ReviewItem.CreditFieldResult comp = new ReviewItem.CreditFieldResult();
                    comp.setStatus(ReviewItem.CreditStatus.VERIFIED);
                    comp.setNames(List.of("Soma Genda"));
                    d.setComposers(comp);
                    tr.setCreditDetail(d);
                    tr.setComposers(List.of("Soma Genda"));
                    return new EnrichmentSummary(1, 1, 0, null, List.of(), false);
                }
            };

        config.setOnlineCreditsEnrichEnabled(true);
        try (OnlineIdentificationService tavilySvc = new OnlineIdentificationService(
                config, reviewQueue, tagWriter, null, null, stubTavilySearch, null, stubEnricher)) {
            ReviewItem savedItem = tavilySvc.search(albumDir.toFile(), "MANUAL", false);
            assertTrue(enrichmentTriggered.get(), "Tavily mode must invoke TrackCreditEnrichmentService");
            ReviewItem reloaded = reviewQueue.get(savedItem.getId());
            assertNotNull(reloaded);
            assertEquals(1, reloaded.getOnlineCandidates().size());
            ReviewItem.OnlineTrack persistedTrack = reloaded.getOnlineCandidates().get(0).getTracks().get(0);
            assertEquals(List.of("Soma Genda"), persistedTrack.getComposers());
            assertNotNull(persistedTrack.getCreditDetail());
            assertEquals(ReviewItem.CreditStatus.VERIFIED, persistedTrack.getCreditDetail().getComposers().getStatus());
        }
    }

    @Test
    void freshFlatCreditsWithoutCreditDetailCannotBypassApplyProbableOrGuardrails() throws Exception {
        config.setOnlineCreditsApplyProbable(false);
        WebSearchClient.SearchResponse resp = new WebSearchClient.SearchResponse();
        resp.addCitation(WebSearchClient.Citation.of("https://vgmdb.net/album/99999", "Album", "credits"));
        resp.setText("""
            {
              "needs_second_round": false,
              "candidates": [{
                "title": "Flat Fallback EP",
                "artist": "HIMEHINA",
                "confidence": 0.9,
                "source_urls": ["https://vgmdb.net/album/99999"],
                "tracks": [
                  {"disc": 1, "track": 1, "title": "Main Song", "composers": ["Hallucinated Composer"]},
                  {"disc": 1, "track": 2, "title": "Main Song (Instrumental)", "lyricists": ["Wrong Lyricist"]},
                  {"disc": 1, "track": 3, "title": "Main Song (Remix)", "arrangers": ["Original Arranger"]}
                ]
              }]
            }
            """);

        OnlineIdentificationService.Parsed parsed = service.parse(resp);
        assertEquals(1, parsed.candidates.size());
        List<ReviewItem.OnlineTrack> tracks = parsed.candidates.get(0).getTracks();
        // applyProbable=false 时，无 credit_detail 的扁平作曲不得进入默认数组
        assertNotNull(tracks.get(0).getCreditDetail());
        assertEquals(ReviewItem.CreditStatus.PROBABLE, tracks.get(0).getCreditDetail().getComposers().getStatus());
        assertTrue(tracks.get(0).getComposers().isEmpty());
        // 伴奏作词与 Remix 编曲必须直接降为 NOT_FOUND
        assertEquals(ReviewItem.CreditStatus.NOT_FOUND, tracks.get(1).getCreditDetail().getLyricists().getStatus());
        assertTrue(tracks.get(1).getLyricists().isEmpty());
        assertEquals(ReviewItem.CreditStatus.NOT_FOUND, tracks.get(2).getCreditDetail().getArrangers().getStatus());
        assertTrue(tracks.get(2).getArrangers().isEmpty());
    }
}
