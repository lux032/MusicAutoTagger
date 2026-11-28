package com.lux032.musicautotagger.service.llm;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ReviewItem;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TrackCreditEnrichmentServiceTest {

    private static MusicConfig newConfig() throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MusicConfig cfg = ctor.newInstance();
        cfg.setTavilyApiKey("tvly-test");
        cfg.setOnlineCreditsEnrichEnabled(true);
        cfg.setOnlineCreditsMaxFollowUpRounds(2);
        cfg.setOnlineCreditsMaxSearchCalls(6);
        cfg.setOnlineCreditsMaxFetchPages(6);
        cfg.setOnlineCreditsApplyProbable(true);
        return cfg;
    }

    private static ReviewItem.OnlineCandidate vampyCandidate() {
        ReviewItem.OnlineCandidate c = new ReviewItem.OnlineCandidate();
        c.setTitle("VAMPY");
        c.setArtist("HIMEHINA");
        c.setAlbumArtist("HIMEHINA");
        c.setReleaseDate("2026-11-27");
        c.setCountry("JP");

        ReviewItem.OnlineTrack t1 = new ReviewItem.OnlineTrack();
        t1.setDiscNo(1); t1.setTrackNo(1); t1.setTitle("VAMPY"); t1.setArtist("HIMEHINA");
        t1.setMatchedFilePath("/music/01.flac"); t1.setMatchConfidence(0.98);

        ReviewItem.OnlineTrack t2 = new ReviewItem.OnlineTrack();
        t2.setDiscNo(1); t2.setTrackNo(2); t2.setTitle("Drain Drain"); t2.setArtist("HIMEHINA");
        t2.setMatchedFilePath("/music/02.flac"); t2.setMatchConfidence(0.98);

        ReviewItem.OnlineTrack t3 = new ReviewItem.OnlineTrack();
        t3.setDiscNo(1); t3.setTrackNo(3); t3.setTitle("VAMPY (Latin Night Version)"); t3.setArtist("HIMEHINA");
        t3.setMatchedFilePath("/music/03.flac"); t3.setMatchConfidence(0.95);

        c.setTracks(new ArrayList<>(List.of(t1, t2, t3)));
        return c;
    }

    @Test
    void stage1EarlyExitsWhenAllApplicableFieldsVerifiedExcludingAltVersionArranger() throws Exception {
        MusicConfig cfg = newConfig();
        String officialUrl = "https://himehina.jp/discography/vampy";
        String officialCanon = WebSearchClient.canonicalizeUrl(officialUrl);
        String pageText = "VAMPY 作詞：Gohgo 作曲：Soma Genda 編曲：Soma Genda\n"
            + "Drain Drain 作詞：Gohgo 作曲：南田健吾 編曲：南田健吾";

        AtomicInteger searchCount = new AtomicInteger();
        TavilyWebSearchClient stubTavily = new TavilyWebSearchClient(cfg, new LlmClient(cfg)) {
            @Override
            public List<Citation> searchRaw(String query, String searchDepth, int maxResults, List<String> includeDomains) {
                assertNull(includeDomains, "Stage 1 must run open-web search without 13-domain restriction");
                searchCount.incrementAndGet();
                return List.of(Citation.of(officialUrl, "HIMEHINA VAMPY", "作詞：Gohgo 作曲：Soma Genda"));
            }
        };

        WebPageFetcher stubFetcher = new WebPageFetcher(cfg, stubTavily) {
            @Override
            public Map<String, FetchedPage> fetchBatch(List<String> rawUrls, String artistHint, boolean allowExtract) {
                return Map.of(officialCanon, new FetchedPage(
                    officialUrl, officialCanon, "artist_official", true, "direct_http", "ok", pageText, pageText));
            }
        };

        AtomicInteger llmCalls = new AtomicInteger();
        LlmClient stubLlm = new LlmClient(cfg) {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public LlmResponse complete(String systemPrompt, String userPrompt, int maxTokens) {
                llmCalls.incrementAndGet();
                String json = """
                    {
                      "tracks": [
                        {
                          "index": 1, "title": "VAMPY", "version_type": "original",
                          "credit_detail": {
                            "lyricists": {"names": ["Gohgo"], "status": "verified", "evidence": [{"url": "https://himehina.jp/discography/vampy", "source_type": "artist_official", "quote": "作詞：Gohgo", "version_match": "exact_version"}]},
                            "composers": {"names": ["Soma Genda"], "status": "verified", "evidence": [{"url": "https://himehina.jp/discography/vampy", "source_type": "artist_official", "quote": "作曲：Soma Genda", "version_match": "exact_version"}]},
                            "arrangers": {"names": ["Soma Genda"], "status": "verified", "evidence": [{"url": "https://himehina.jp/discography/vampy", "source_type": "artist_official", "quote": "編曲：Soma Genda", "version_match": "exact_version"}]}
                          }
                        },
                        {
                          "index": 2, "title": "Drain Drain", "version_type": "original",
                          "credit_detail": {
                            "lyricists": {"names": ["Gohgo"], "status": "verified", "evidence": [{"url": "https://himehina.jp/discography/vampy", "source_type": "artist_official", "quote": "作詞：Gohgo", "version_match": "exact_version"}]},
                            "composers": {"names": ["南田健吾"], "status": "verified", "evidence": [{"url": "https://himehina.jp/discography/vampy", "source_type": "artist_official", "quote": "作曲：南田健吾", "version_match": "exact_version"}]},
                            "arrangers": {"names": ["南田健吾"], "status": "verified", "evidence": [{"url": "https://himehina.jp/discography/vampy", "source_type": "artist_official", "quote": "編曲：南田健吾", "version_match": "exact_version"}]}
                          }
                        },
                        {
                          "index": 3, "title": "VAMPY (Latin Night Version)", "version_type": "alternate_arrangement",
                          "credit_detail": {
                            "lyricists": {"names": ["Gohgo"], "status": "probable", "evidence": [{"url": "https://himehina.jp/discography/vampy", "source_type": "artist_official", "quote": "作詞：Gohgo", "version_match": "original_only"}]},
                            "composers": {"names": ["Soma Genda"], "status": "probable", "evidence": [{"url": "https://himehina.jp/discography/vampy", "source_type": "artist_official", "quote": "作曲：Soma Genda", "version_match": "original_only"}]},
                            "arrangers": {"names": [], "status": "not_found", "evidence": []}
                          }
                        }
                      ]
                    }
                    """;
                return new LlmResponse(json, "stub", "stub", new TokenUsage(1000, 300, 1300, 0, 0));
            }
        };

        ReviewItem.OnlineCandidate cand = vampyCandidate();
        TrackCreditEnrichmentService svc = new TrackCreditEnrichmentService(cfg, stubTavily, stubLlm, stubFetcher);
        TrackCreditEnrichmentService.EnrichmentSummary summary = svc.enrich(cand, null);

        assertEquals(0, summary.followUpRounds(), "Must early-exit after Stage 1 without triggering Stage 2");
        assertEquals(1, llmCalls.get(), "Only Stage 1 extraction LLM call should run");
        assertFalse(summary.interrupted());
        assertEquals(List.of("Gohgo"), cand.getTracks().get(0).getLyricists());
        assertEquals(List.of("南田健吾"), cand.getTracks().get(1).getComposers());
        assertTrue(cand.getTracks().get(2).getArrangers().isEmpty());
        assertEquals(ReviewItem.CreditStatus.PROBABLE, cand.getTracks().get(2).getCreditDetail().getLyricists().getStatus());
        stubFetcher.close();
    }

    @Test
    void stage2CompressesStateWithoutReplayingRound1BodyAndEnforcesHardBudgets() throws Exception {
        MusicConfig cfg = newConfig();
        cfg.setOnlineCreditsMaxSearchCalls(3);
        cfg.setOnlineCreditsMaxFetchPages(2);
        cfg.setOnlineCreditsMaxFollowUpRounds(2);

        String stage1Url = "https://himehina.jp/discography/vampy";
        String stage1Canon = WebSearchClient.canonicalizeUrl(stage1Url);
        String stage1UniqueMarker = "STAGE1_UNIQUE_RAW_BODY_TOKEN_DO_NOT_REPLAY_987654321";
        String stage1Text = "VAMPY 作詞：Gohgo 作曲：Soma Genda 編曲：Soma Genda " + stage1UniqueMarker;

        String stage2Url = "https://utaten.com/lyric/drain-drain/";
        String stage2Canon = WebSearchClient.canonicalizeUrl(stage2Url);
        String stage2Text = "Drain Drain 作詞：Gohgo 作曲：南田健吾 編曲：南田健吾";

        AtomicInteger searchCounter = new AtomicInteger();
        TavilyWebSearchClient stubTavily = new TavilyWebSearchClient(cfg, new LlmClient(cfg)) {
            @Override
            public List<Citation> searchRaw(String query, String searchDepth, int maxResults, List<String> includeDomains) {
                int n = searchCounter.incrementAndGet();
                if (n <= 2) {
                    return List.of(Citation.of(stage1Url, "HIMEHINA VAMPY", "VAMPY 作詞：Gohgo"));
                }
                return List.of(Citation.of(stage2Url, "Drain Drain 歌詞", "Drain Drain 作曲：南田健吾"));
            }
        };

        AtomicInteger fetchPagesTotal = new AtomicInteger();
        WebPageFetcher stubFetcher = new WebPageFetcher(cfg, stubTavily) {
            @Override
            public Map<String, FetchedPage> fetchBatch(List<String> rawUrls, String artistHint, boolean allowExtract) {
                fetchPagesTotal.addAndGet(rawUrls.size());
                Map<String, FetchedPage> map = new LinkedHashMap<>();
                for (String u : rawUrls) {
                    String canon = WebSearchClient.canonicalizeUrl(u);
                    if (canon.equals(stage1Canon)) {
                        map.put(canon, new FetchedPage(u, canon, "artist_official", true, "direct_http", "ok", stage1Text, stage1Text));
                    } else if (canon.equals(stage2Canon)) {
                        map.put(canon, new FetchedPage(u, canon, "jp_lyrics_site", true, "direct_http", "ok", stage2Text, stage2Text));
                    }
                }
                return map;
            }
        };

        List<String> capturedUserPrompts = new ArrayList<>();
        LlmClient stubLlm = new LlmClient(cfg) {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public LlmResponse complete(String systemPrompt, String userPrompt, int maxTokens) {
                capturedUserPrompts.add(userPrompt);
                if (TrackCreditEnrichmentService.PLANNER_SYSTEM.equals(systemPrompt)) {
                    assertFalse(userPrompt.contains(stage1UniqueMarker),
                        "Stage 2 planner MUST receive compressed state, NEVER Stage 1 raw webpage text");
                    return new LlmResponse(
                        "{\"queries\":[{\"query\":\"HIMEHINA Drain Drain 歌詞 作詞 作曲 編曲\",\"include_domains\":[\"utaten.com\"],\"depth\":\"basic\"},"
                            + "{\"query\":\"extra query 2\",\"depth\":\"basic\"}]}",
                        "stub", "stub", new TokenUsage(200, 60, 260, 0, 0));
                }
                if (capturedUserPrompts.size() == 1) {
                    // Stage 1 only finds Track 1
                    return new LlmResponse("""
                        {"tracks":[
                          {"index":1,"title":"VAMPY","version_type":"original","credit_detail":{
                            "lyricists":{"names":["Gohgo"],"status":"verified","evidence":[{"url":"https://himehina.jp/discography/vampy","source_type":"artist_official","quote":"作詞：Gohgo","version_match":"exact_version"}]},
                            "composers":{"names":["Soma Genda"],"status":"verified","evidence":[{"url":"https://himehina.jp/discography/vampy","source_type":"artist_official","quote":"作曲：Soma Genda","version_match":"exact_version"}]},
                            "arrangers":{"names":["Soma Genda"],"status":"verified","evidence":[{"url":"https://himehina.jp/discography/vampy","source_type":"artist_official","quote":"編曲：Soma Genda","version_match":"exact_version"}]}
                          }}
                        ]}
                        """, "stub", "stub", new TokenUsage(900, 200, 1100, 0, 0));
                }
                // Stage 2 extraction prompt must also NOT contain Stage 1 raw webpage text!
                assertFalse(userPrompt.contains(stage1UniqueMarker),
                    "Stage 2 extraction MUST NOT replay Stage 1 raw webpage text");
                return new LlmResponse("""
                    {"tracks":[
                      {"index":2,"title":"Drain Drain","version_type":"original","credit_detail":{
                        "lyricists":{"names":["Gohgo"],"status":"verified","evidence":[{"url":"https://utaten.com/lyric/drain-drain/","source_type":"jp_lyrics_site","quote":"作詞：Gohgo","version_match":"exact_version"}]},
                        "composers":{"names":["南田健吾"],"status":"verified","evidence":[{"url":"https://utaten.com/lyric/drain-drain/","source_type":"jp_lyrics_site","quote":"作曲：南田健吾","version_match":"exact_version"}]},
                        "arrangers":{"names":["南田健吾"],"status":"verified","evidence":[{"url":"https://utaten.com/lyric/drain-drain/","source_type":"jp_lyrics_site","quote":"編曲：南田健吾","version_match":"exact_version"}]}
                      }}
                    ]}
                    """, "stub", "stub", new TokenUsage(600, 180, 780, 0, 0));
            }
        };

        ReviewItem.OnlineCandidate cand = vampyCandidate();
        TrackCreditEnrichmentService svc = new TrackCreditEnrichmentService(cfg, stubTavily, stubLlm, stubFetcher);
        TrackCreditEnrichmentService.EnrichmentSummary summary = svc.enrich(cand, null);

        assertEquals(3, summary.searchCalls(), "Hard budget maxSearchCalls=3 must be respected");
        assertEquals(2, summary.pagesFetched(), "Hard budget maxFetchPages=2 must be respected");
        assertEquals(1, summary.followUpRounds());
        // Track 1 verified in Stage 1 must be preserved, Track 2 filled in Stage 2!
        assertEquals(List.of("Soma Genda"), cand.getTracks().get(0).getComposers());
        assertEquals(List.of("南田健吾"), cand.getTracks().get(1).getComposers());
        assertEquals(ReviewItem.CreditStatus.VERIFIED, cand.getTracks().get(1).getCreditDetail().getArrangers().getStatus());
        stubFetcher.close();
    }

    @Test
    void interruptionDuringStage2PreservesStage1VerifiedResults() throws Exception {
        MusicConfig cfg = newConfig();
        String officialUrl = "https://himehina.jp/discography/vampy";
        String officialCanon = WebSearchClient.canonicalizeUrl(officialUrl);
        String pageText = "VAMPY 作詞：Gohgo 作曲：Soma Genda 編曲：Soma Genda";

        TavilyWebSearchClient stubTavily = new TavilyWebSearchClient(cfg, new LlmClient(cfg)) {
            @Override
            public List<Citation> searchRaw(String query, String searchDepth, int maxResults, List<String> includeDomains) {
                return List.of(Citation.of(officialUrl, "VAMPY", "作詞：Gohgo"));
            }
        };
        WebPageFetcher stubFetcher = new WebPageFetcher(cfg, stubTavily) {
            @Override
            public Map<String, FetchedPage> fetchBatch(List<String> rawUrls, String artistHint, boolean allowExtract) {
                return Map.of(officialCanon, new FetchedPage(
                    officialUrl, officialCanon, "artist_official", true, "direct_http", "ok", pageText, pageText));
            }
        };
        LlmClient stubLlm = new LlmClient(cfg) {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public LlmResponse complete(String systemPrompt, String userPrompt, int maxTokens) throws LlmException {
                if (TrackCreditEnrichmentService.PLANNER_SYSTEM.equals(systemPrompt)) {
                    // Simulate user clicking Abort during Stage 2
                    Thread.currentThread().interrupt();
                    throw new LlmException("interrupted");
                }
                return new LlmResponse("""
                    {"tracks":[
                      {"index":1,"title":"VAMPY","version_type":"original","credit_detail":{
                        "lyricists":{"names":["Gohgo"],"status":"verified","evidence":[{"url":"https://himehina.jp/discography/vampy","source_type":"artist_official","quote":"作詞：Gohgo","version_match":"exact_version"}]},
                        "composers":{"names":["Soma Genda"],"status":"verified","evidence":[{"url":"https://himehina.jp/discography/vampy","source_type":"artist_official","quote":"作曲：Soma Genda","version_match":"exact_version"}]},
                        "arrangers":{"names":["Soma Genda"],"status":"verified","evidence":[{"url":"https://himehina.jp/discography/vampy","source_type":"artist_official","quote":"編曲：Soma Genda","version_match":"exact_version"}]}
                      }}
                    ]}
                    """, "stub", "stub");
            }
        };

        ReviewItem.OnlineCandidate cand = vampyCandidate();
        TrackCreditEnrichmentService svc = new TrackCreditEnrichmentService(cfg, stubTavily, stubLlm, stubFetcher);
        try {
            TrackCreditEnrichmentService.EnrichmentSummary summary = svc.enrich(cand, null);
            assertTrue(summary.interrupted());
            assertTrue(Thread.interrupted(), "Interrupt flag must be restored for upstream cancellation");
            assertEquals(List.of("Gohgo"), cand.getTracks().get(0).getLyricists(),
                "Stage 1 verified credits must survive Stage 2 interruption");
            assertEquals(ReviewItem.CreditStatus.VERIFIED, cand.getTracks().get(0).getCreditDetail().getComposers().getStatus());
        } finally {
            Thread.interrupted(); // ensure clean thread state for JUnit runner
            stubFetcher.close();
        }
    }

    @Test
    void rejectsContradictoryTrackIndexAndAmbiguousDuplicateTitles() {
        ReviewItem.OnlineTrack t1 = new ReviewItem.OnlineTrack();
        t1.setDiscNo(1); t1.setTrackNo(1); t1.setTitle("VAMPY");
        ReviewItem.OnlineTrack t2 = new ReviewItem.OnlineTrack();
        t2.setDiscNo(2); t2.setTrackNo(1); t2.setTitle("VAMPY");
        ReviewItem.OnlineTrack t3 = new ReviewItem.OnlineTrack();
        t3.setDiscNo(1); t3.setTrackNo(2); t3.setTitle("Drain Drain");
        List<ReviewItem.OnlineTrack> tracks = List.of(t1, t2, t3);

        // index=1 却写着 Drain Drain -> 不覆盖 t1，改为唯一标题匹配到 t3
        com.google.gson.JsonObject contradictory = new com.google.gson.JsonObject();
        contradictory.addProperty("index", 1);
        contradictory.addProperty("title", "Drain Drain");
        assertSame(t3, TrackCreditEnrichmentService.matchExtractedTrack(contradictory, tracks));

        // 仅按重复标题 VAMPY 匹配且无 index -> 存在歧义，拒绝写入任何曲目
        com.google.gson.JsonObject ambiguous = new com.google.gson.JsonObject();
        ambiguous.addProperty("title", "VAMPY");
        assertNull(TrackCreditEnrichmentService.matchExtractedTrack(ambiguous, tracks));
    }
}
