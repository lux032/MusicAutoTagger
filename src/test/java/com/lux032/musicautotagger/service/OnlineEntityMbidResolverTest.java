package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.llm.LlmArtistDisambiguator;
import com.lux032.musicautotagger.service.llm.LlmClient;
import com.lux032.musicautotagger.service.llm.WebPageFetcher;
import com.lux032.musicautotagger.service.llm.WebSearchClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OnlineEntityMbidResolverTest {

    private static final String MBID_CACHED = "11111111-1111-4111-8111-111111111111";
    private static final String MBID_LINK = "22222222-2222-4222-8222-222222222222";
    private static final String MBID_EXACT = "33333333-3333-4333-8333-333333333333";
    private static final String MBID_HOMONYM_1 = "44444444-4444-4444-8444-444444444444";
    private static final String MBID_HOMONYM_2 = "55555555-5555-4555-8555-555555555555";
    private static final String MBID_MISMATCH = "66666666-6666-4666-8666-666666666666";

    @TempDir
    Path tempDir;

    private MusicConfig newConfig() throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MusicConfig cfg = ctor.newInstance();
        cfg.setReviewQueuePath(tempDir.resolve("review.json").toString());
        cfg.setReviewStagingDirectory(tempDir.resolve("staging").toString());
        return cfg;
    }

    @Test
    void resolvesAcrossAllThreeLayersPreventsHomonymCachePollutionAndRespectsBudget() throws Exception {
        MusicConfig cfg = newConfig();
        cfg.setReleaseCountryPriority(List.of("JP"));
        cfg.setOnlineMbidMaxLookupsPerAlbum(10);

        MusicBrainzArtistCache cache = new MusicBrainzArtistCache(null);
        // Layer 1 预置：梶浦由記 唯一命中
        cache.recordArtist(MBID_CACHED, "梶浦由記", "Kajiura, Yuki", "Japanese composer",
            "JP", "Person", List.of("Yuki Kajiura"), List.of(), "MB_RECORDING");

        AtomicInteger searchCalls = new AtomicInteger();
        AtomicInteger getByIdCalls = new AtomicInteger();
        MusicBrainzClient stubMb = new MusicBrainzClient(cfg, cache) {
            @Override
            public Optional<ArtistMatch> getArtistById(String rawMbid) {
                getByIdCalls.incrementAndGet();
                String mbid = MusicBrainzArtistCache.normalizeValidMbid(rawMbid);
                if (MBID_LINK.equals(mbid)) {
                    return Optional.of(new ArtistMatch(MBID_LINK, "Aimer", "Aimer", "Japanese singer",
                        "JP", "Japan", "Person", null, null, 100, List.of("エメ"), List.of("j-pop")));
                }
                if (MBID_MISMATCH.equals(mbid)) {
                    return Optional.of(new ArtistMatch(MBID_MISMATCH, "Completely Different Person", "Different", null,
                        "US", "United States", "Person", null, null, 100, List.of(), List.of()));
                }
                return Optional.empty();
            }

            @Override
            public List<ArtistMatch> searchArtists(String queryName, int limit) {
                searchCalls.incrementAndGet();
                if ("森空青".equals(queryName)) {
                    return List.of(new ArtistMatch(MBID_EXACT, "森空青", "Mori, Sorao", "Japanese arranger",
                        "JP", "Japan", "Person", null, null, 100, List.of(), List.of()));
                }
                if ("高橋諒".equals(queryName)) {
                    return List.of(
                        new ArtistMatch(MBID_HOMONYM_1, "高橋諒", "Takahashi, Ryo", "US drummer",
                            "US", "United States", "Person", null, null, 100, List.of(), List.of("jazz")),
                        new ArtistMatch(MBID_HOMONYM_2, "高橋諒", "Takahashi, Ryo", "Japanese anime composer",
                            "JP", "Japan", "Person", null, null, 100, List.of("Void_Chords"), List.of("anime"))
                    );
                }
                return List.of();
            }
        };

        LlmClient stubLlm = new LlmClient(cfg) {
            @Override
            public boolean isConfigured() { return true; }
            @Override
            public LlmResponse complete(String s, String u, int m) {
                return new LlmResponse("{\"choice_index\":2,\"confidence\":0.93,\"reason\":\"JP anime composer\"}",
                    "test-model", "stub");
            }
        };
        OnlineEntityMbidResolver resolver = new OnlineEntityMbidResolver(
            cfg, stubMb, cache, new LlmArtistDisambiguator(stubLlm));

        ReviewItem.OnlineCandidate candidate = new ReviewItem.OnlineCandidate();
        candidate.setTitle("Test OST");
        candidate.setAlbumArtist("Aimer");
        candidate.setAlbumArtists(new ArrayList<>(List.of("Aimer")));
        candidate.setReleaseDate("2026-05-01");
        ReviewItem.OnlineTrack track = new ReviewItem.OnlineTrack();
        track.setTitle("Song 1");
        track.setArtist("Various Artists");
        track.setArtists(new ArrayList<>(List.of("Various Artists", "Aimer")));
        track.setComposers(new ArrayList<>(List.of("梶浦由記", "高橋諒")));
        track.setArrangers(new ArrayList<>(List.of("森空青")));
        track.setMatchedFilePath("/music/01.flac");
        track.setMatchConfidence(0.95);
        candidate.getTracks().add(track);

        // Layer 2 网页包含正确外链 (Aimer) 和失配外链 (MBID_MISMATCH)
        WebPageFetcher.FetchedPage wikiPage = new WebPageFetcher.FetchedPage(
            "https://ja.wikipedia.org/wiki/Aimer",
            "https://ja.wikipedia.org/wiki/Aimer",
            "community_wiki", true, "direct_http", "ok", "body", "body",
            List.of(
                new WebPageFetcher.ExtractedMbArtistLink(MBID_LINK, "Aimer", "https://ja.wikipedia.org/wiki/Aimer"),
                new WebPageFetcher.ExtractedMbArtistLink(MBID_MISMATCH, "森空青", "https://ja.wikipedia.org/wiki/Aimer")
            ));

        OnlineEntityMbidResolver.ResolutionSummary summary = resolver.resolveForCandidate(
            candidate, Map.of(wikiPage.canonicalUrl(), wikiPage), Map.of(), null);

        assertFalse(summary.interrupted());
        Map<String, ReviewItem.EntityMbidResolution> map = summary.resolutions();
        // 占位名 Various Artists 必须跳过
        assertFalse(map.containsKey("various artists"));

        // Layer 1: 梶浦由記 命中本地缓存
        ReviewItem.EntityMbidResolution kajiura = map.get(MusicBrainzArtistCache.normalizePersonKey("梶浦由記"));
        assertNotNull(kajiura);
        assertEquals(ReviewItem.EntityMbidStatus.RESOLVED, kajiura.getStatus());
        assertEquals(ReviewItem.EntityMbidSource.LOCAL_CACHE, kajiura.getSource());
        assertEquals(MBID_CACHED, kajiura.getSelectedMbid());

        // Layer 2: Aimer 从网页外链核验命中，无需 searchArtists
        ReviewItem.EntityMbidResolution aimer = map.get(MusicBrainzArtistCache.normalizePersonKey("Aimer"));
        assertNotNull(aimer);
        assertEquals(ReviewItem.EntityMbidStatus.RESOLVED, aimer.getStatus());
        assertEquals(ReviewItem.EntityMbidSource.WEB_LINK_VERIFIED, aimer.getSource());
        assertEquals(MBID_LINK, aimer.getSelectedMbid());

        // Layer 3a: 森空青 拒绝了失配外链，经 searchArtists 唯一精确命中
        ReviewItem.EntityMbidResolution mori = map.get(MusicBrainzArtistCache.normalizePersonKey("森空青"));
        assertNotNull(mori);
        assertEquals(ReviewItem.EntityMbidStatus.RESOLVED, mori.getStatus());
        assertEquals(ReviewItem.EntityMbidSource.MB_SEARCH_EXACT, mori.getSource());
        assertEquals(MBID_EXACT, mori.getSelectedMbid());

        // Layer 3b: 高橋諒 同名多候选经封闭式 LLM 消歧命中 2 号并保留全部竞争候选
        ReviewItem.EntityMbidResolution takahashi = map.get(MusicBrainzArtistCache.normalizePersonKey("高橋諒"));
        assertNotNull(takahashi);
        assertEquals(ReviewItem.EntityMbidStatus.RESOLVED, takahashi.getStatus());
        assertEquals(ReviewItem.EntityMbidSource.MB_SEARCH_LLM_DISAMBIGUATED, takahashi.getSource());
        assertEquals(MBID_HOMONYM_2, takahashi.getSelectedMbid());
        assertEquals(2, takahashi.getCandidates().size());

        // 关键防污染校验：本地缓存里 高橋諒 必须记录了多实体冲突，绝不能在下张专辑走 Layer 1 单条短路
        MusicBrainzArtistCache.NameLookupResult homonymLookup = cache.lookupByName("高橋諒");
        assertTrue(homonymLookup.hasHomonymConflict());
        assertFalse(homonymLookup.isUniqueHit());
        assertEquals(2, homonymLookup.matches().size());

        // 关闭 LLM 消歧后，同名候选直接标为 AMBIGUOUS 且不调用 LLM
        cfg.setOnlineMbidLlmDisambiguateEnabled(false);
        ReviewItem.OnlineCandidate cand2 = new ReviewItem.OnlineCandidate();
        cand2.setTitle("Another Album");
        cand2.setAlbumArtists(new ArrayList<>(List.of("高橋諒")));
        OnlineEntityMbidResolver.ResolutionSummary sumNoLlm = resolver.resolveForCandidate(cand2, Map.of(), Map.of(), null);
        ReviewItem.EntityMbidResolution amb = sumNoLlm.resolutions().get(MusicBrainzArtistCache.normalizePersonKey("高橋諒"));
        assertEquals(ReviewItem.EntityMbidStatus.AMBIGUOUS, amb.getStatus());
        assertNull(amb.getSelectedMbid());
        assertEquals(2, amb.getCandidates().size());

        // 预算上限=1 时超出预算的人物标为 SKIPPED
        cfg.setOnlineMbidMaxLookupsPerAlbum(1);
        ReviewItem.OnlineCandidate candBudget = new ReviewItem.OnlineCandidate();
        candBudget.setTitle("Budget Album");
        candBudget.setAlbumArtists(new ArrayList<>(List.of("NewPersonOne", "NewPersonTwo")));
        OnlineEntityMbidResolver.ResolutionSummary sumBudget = resolver.resolveForCandidate(candBudget, Map.of(), Map.of(), null);
        assertEquals(1, sumBudget.externalLookups());
        assertEquals(ReviewItem.EntityMbidStatus.NOT_FOUND, sumBudget.resolutions().get("newpersonone").getStatus());
        assertEquals(ReviewItem.EntityMbidStatus.SKIPPED, sumBudget.resolutions().get("newpersontwo").getStatus());

        // 中断信号早停：预设中断后不发起任何外部请求，返回 interrupted=true 且剩余标为 SKIPPED
        cfg.setOnlineMbidMaxLookupsPerAlbum(10);
        int searchesBefore = searchCalls.get();
        Thread.currentThread().interrupt();
        try {
            OnlineEntityMbidResolver.ResolutionSummary sumInterrupted =
                resolver.resolveForCandidate(candBudget, Map.of(), Map.of(), null);
            assertTrue(sumInterrupted.interrupted());
            assertEquals(searchesBefore, searchCalls.get(), "Interrupted thread must not make external MB lookups");
            assertEquals(ReviewItem.EntityMbidStatus.SKIPPED, sumInterrupted.resolutions().get("newpersonone").getStatus());
        } finally {
            Thread.interrupted(); // 清理线程中断标志以免影响后续测试
        }
    }

    @Test
    void onlineIdentificationServiceRespectsHotConfigToggleAndPersistsEntityMbids() throws Exception {
        MusicConfig cfg = newConfig();
        cfg.setWebSearchProvider("native");
        cfg.setOnlineCreditsEnrichEnabled(false);
        ReviewQueueService queue = new ReviewQueueService(cfg);
        TagWriterService tagWriter = new TagWriterService(cfg);
        MusicBrainzArtistCache cache = new MusicBrainzArtistCache(null);
        cache.recordArtist(MBID_CACHED, "梶浦由記", "Kajiura, Yuki", "Japanese composer",
            "JP", "Person", List.of(), List.of(), "MB_RECORDING");

        WebSearchClient stubSearch = new WebSearchClient() {
            @Override
            public String name() { return "native"; }
            @Override
            public boolean hasEnabledEndpoint() { return true; }
            @Override
            public SearchResponse search(String systemPrompt, String userPrompt) {
                SearchResponse resp = new SearchResponse();
                resp.setProvider("openai-compatible");
                resp.setModel("stub");
                resp.addCitation(Citation.of("https://vgmdb.net/album/999", "VGMdb", "OST", true));
                resp.setText("""
                    {
                      "needs_second_round": false,
                      "candidates": [{
                        "title": "OST",
                        "artist": "梶浦由記",
                        "artists": ["梶浦由記"],
                        "album_artist": "梶浦由記",
                        "album_artists": ["梶浦由記"],
                        "release_date": "2026-01-01",
                        "confidence": 0.95,
                        "source_urls": ["https://vgmdb.net/album/999"],
                        "tracks": [{"disc": 1, "track": 1, "title": "Track 1", "artist": "梶浦由記", "artists": ["梶浦由記"], "duration": 200}]
                      }]
                    }
                    """);
                return resp;
            }
        };

        Path albumDir = Files.createDirectory(tempDir.resolve("Kajiura - OST"));
        File f1 = Files.writeString(albumDir.resolve("01 - Track 1.flac"), "dummy").toFile();

        OnlineIdentificationService svc = new OnlineIdentificationService(
            cfg, queue, tagWriter, null, null, stubSearch, null, null, null, cache, null);

        // 1. 开启开关 -> 自动解析、上报 RESOLVING_MBIDS 进度并持久化 entityMbids
        cfg.setOnlineMbidResolveEnabled(true);
        List<String> stageCodes = new ArrayList<>();
        ReviewItem item1 = svc.search(albumDir.toFile(), null, false, p -> stageCodes.add(p.stageCode()));
        assertTrue(stageCodes.contains("RESOLVING_MBIDS"), "Expected RESOLVING_MBIDS progress event, got " + stageCodes);
        ReviewItem persisted1 = queue.get(item1.getId());
        assertEquals(1, persisted1.getOnlineCandidates().size());
        ReviewItem.EntityMbidResolution res1 = persisted1.getOnlineCandidates().get(0)
            .getEntityMbids().get(MusicBrainzArtistCache.normalizePersonKey("梶浦由記"));
        assertNotNull(res1);
        assertEquals(ReviewItem.EntityMbidStatus.RESOLVED, res1.getStatus());
        assertEquals(MBID_CACHED, res1.getSelectedMbid());

        // 2. 热关闭开关 -> 跳过人物级 MBID 解析
        cfg.setOnlineMbidResolveEnabled(false);
        Path albumDir2 = Files.createDirectory(tempDir.resolve("Kajiura - OST 2"));
        Files.writeString(albumDir2.resolve("01 - Track 1.flac"), "dummy");
        List<String> stageCodesDisabled = new ArrayList<>();
        ReviewItem item2 = svc.search(albumDir2.toFile(), null, false, p -> stageCodesDisabled.add(p.stageCode()));
        assertFalse(stageCodesDisabled.contains("RESOLVING_MBIDS"));
        ReviewItem persisted2 = queue.get(item2.getId());
        assertTrue(persisted2.getOnlineCandidates().get(0).getEntityMbids().isEmpty());
    }

    @Test
    void layer2WithholdsBindingOnIncompleteVerificationAndOtherCandidatePagesAndLayer3PreservesExactAndSkipsUnmatched() throws Exception {
        MusicConfig cfg = newConfig();
        cfg.setOnlineMbidLlmDisambiguateEnabled(false);

        // 1. Layer 2 预算=1 且有 2 条候选外链时，校验不完整不得自动拍板 WEB_LINK_VERIFIED 或写入唯一缓存
        cfg.setOnlineMbidMaxLookupsPerAlbum(1);
        MusicBrainzArtistCache cache1 = new MusicBrainzArtistCache(null);
        MusicBrainzClient mbTwoLinks = new MusicBrainzClient(cfg, cache1) {
            @Override
            public Optional<ArtistMatch> getArtistById(String rawMbid) {
                if (MBID_HOMONYM_1.equals(rawMbid)) {
                    return Optional.of(new ArtistMatch(MBID_HOMONYM_1, "高橋諒", "Takahashi, Ryo", "First",
                        "US", "US", "Person", null, null, 100, List.of(), List.of()));
                }
                if (MBID_HOMONYM_2.equals(rawMbid)) {
                    return Optional.of(new ArtistMatch(MBID_HOMONYM_2, "高橋諒", "Takahashi, Ryo", "Second",
                        "JP", "JP", "Person", null, null, 100, List.of(), List.of()));
                }
                return Optional.empty();
            }
        };
        OnlineEntityMbidResolver resolver1 = new OnlineEntityMbidResolver(cfg, mbTwoLinks, cache1, null);
        ReviewItem.OnlineCandidate c1 = new ReviewItem.OnlineCandidate();
        c1.setTitle("Album");
        c1.setAlbumArtists(new ArrayList<>(List.of("高橋諒")));
        ReviewItem.OnlineEvidence evOwn = new ReviewItem.OnlineEvidence();
        evOwn.setUrl("https://ja.wikipedia.org/wiki/OwnAlbum");
        c1.setSources(List.of(evOwn));

        WebPageFetcher.FetchedPage ownPage = new WebPageFetcher.FetchedPage(
            "https://ja.wikipedia.org/wiki/OwnAlbum", "https://ja.wikipedia.org/wiki/OwnAlbum",
            "community_wiki", true, "direct_http", "ok", "body", "body",
            List.of(
                new WebPageFetcher.ExtractedMbArtistLink(MBID_HOMONYM_1, "高橋諒", "https://ja.wikipedia.org/wiki/OwnAlbum"),
                new WebPageFetcher.ExtractedMbArtistLink(MBID_HOMONYM_2, "高橋諒", "https://ja.wikipedia.org/wiki/OwnAlbum")
            ));
        WebPageFetcher.FetchedPage foreignPage = new WebPageFetcher.FetchedPage(
            "https://ja.wikipedia.org/wiki/OtherCandidate", "https://ja.wikipedia.org/wiki/OtherCandidate",
            "community_wiki", true, "direct_http", "ok", "body", "body",
            List.of(new WebPageFetcher.ExtractedMbArtistLink(MBID_LINK, "Aimer", "https://ja.wikipedia.org/wiki/OtherCandidate")));

        OnlineEntityMbidResolver.ResolutionSummary incompleteSum = resolver1.resolveForCandidate(
            c1,
            Map.of(ownPage.canonicalUrl(), ownPage, foreignPage.canonicalUrl(), foreignPage),
            Map.of(), null);
        ReviewItem.EntityMbidResolution rInc = incompleteSum.resolutions().get(MusicBrainzArtistCache.normalizePersonKey("高橋諒"));
        assertEquals(ReviewItem.EntityMbidStatus.AMBIGUOUS, rInc.getStatus(),
            "Incomplete Layer 2 verification must NOT auto-bind WEB_LINK_VERIFIED");
        assertTrue(cache1.lookupByName("高橋諒").isEmpty(), "Incomplete Layer 2 must NOT pollute unique cache");

        // 2. 非当前候选页面的外链（foreignPage）不得被当前候选误用
        ReviewItem.OnlineCandidate cForeign = new ReviewItem.OnlineCandidate();
        cForeign.setTitle("Other");
        cForeign.setAlbumArtists(new ArrayList<>(List.of("Aimer")));
        cForeign.setSources(List.of(evOwn)); // 仅指向 ownPage（不含 Aimer 外链）
        OnlineEntityMbidResolver.ResolutionSummary foreignSum = resolver1.resolveForCandidate(
            cForeign, Map.of(foreignPage.canonicalUrl(), foreignPage), Map.of(), null);
        assertNotEquals(ReviewItem.EntityMbidSource.WEB_LINK_VERIFIED,
            foreignSum.resolutions().get("aimer").getSource());

        // 3. Layer 3: 本地 2 个同名冲突 + 搜索返回 1 个精确命中时，绝不能把该精确命中从候选池丢弃；
        //    同时未达可靠匹配阈值的曲目不得消耗预算
        cfg.setOnlineMbidMaxLookupsPerAlbum(10);
        MusicBrainzArtistCache cache3 = new MusicBrainzArtistCache(null);
        cache3.recordHomonymCandidates("高橋諒", List.of(
            new MusicBrainzArtistCache.CachedArtist(MBID_HOMONYM_1, "高橋諒", null, "Local 1", "US", "Person", List.of(), "TEST", 1L),
            new MusicBrainzArtistCache.CachedArtist(MBID_HOMONYM_2, "高橋諒", null, "Local 2", "JP", "Person", List.of(), "TEST", 2L)
        ));
        AtomicInteger searchedCount = new AtomicInteger();
        MusicBrainzClient mbExactPlusNear = new MusicBrainzClient(cfg, cache3) {
            @Override
            public List<ArtistMatch> searchArtists(String queryName, int limit) {
                searchedCount.incrementAndGet();
                if ("高橋諒".equals(queryName)) {
                    return List.of(new ArtistMatch(MBID_EXACT, "高橋諒", "Takahashi, Ryo", "Third exact",
                        "JP", "Japan", "Person", null, null, 100, List.of(), List.of()));
                }
                if ("ScoreComp".equals(queryName)) {
                    return List.of(
                        new ArtistMatch(MBID_LINK, "ScoreComp", "ScoreComp", "Exact", "JP", "Japan", "Person", null, null, 100, List.of(), List.of()),
                        new ArtistMatch(MBID_MISMATCH, "ScoreComp Project", "ScoreComp Project", "Near high score", "JP", "Japan", "Group", null, null, 92, List.of(), List.of())
                    );
                }
                return List.of();
            }
        };
        OnlineEntityMbidResolver resolver3 = new OnlineEntityMbidResolver(cfg, mbExactPlusNear, cache3, null);
        ReviewItem.OnlineCandidate c3 = new ReviewItem.OnlineCandidate();
        c3.setTitle("Test");
        c3.setAlbumArtists(new ArrayList<>(List.of("高橋諒", "ScoreComp")));
        ReviewItem.OnlineTrack unmatchedTrack = new ReviewItem.OnlineTrack();
        unmatchedTrack.setTitle("Unmatched");
        unmatchedTrack.setArtists(new ArrayList<>(List.of("ShouldNotBeQueried")));
        unmatchedTrack.setComposers(new ArrayList<>(List.of("UnmatchedComposer")));
        unmatchedTrack.setMatchConfidence(0.2); // 低于阈值
        c3.setTracks(List.of(unmatchedTrack));

        OnlineEntityMbidResolver.ResolutionSummary sum3 = resolver3.resolveForCandidate(c3, Map.of(), Map.of(), null);
        assertEquals(2, searchedCount.get(), "Unmatched track performers/credits must NOT consume lookup budget");
        assertFalse(sum3.resolutions().containsKey("shouldnotbequeried"));
        assertFalse(sum3.resolutions().containsKey("unmatchedcomposer"));

        ReviewItem.EntityMbidResolution rExactPreserved = sum3.resolutions().get(MusicBrainzArtistCache.normalizePersonKey("高橋諒"));
        assertEquals(ReviewItem.EntityMbidStatus.AMBIGUOUS, rExactPreserved.getStatus());
        assertTrue(rExactPreserved.getCandidates().stream().anyMatch(c -> MBID_EXACT.equals(c.getMbid())),
            "Exact match MUST be preserved in disambiguation pool when local homonym conflict exists");

        ReviewItem.EntityMbidResolution rHighComp = sum3.resolutions().get("scorecomp");
        assertEquals(ReviewItem.EntityMbidStatus.AMBIGUOUS, rHighComp.getStatus(),
            "High-scoring competitor (score >= 90) must prevent blind MB_SEARCH_EXACT auto-accept");
    }
}
