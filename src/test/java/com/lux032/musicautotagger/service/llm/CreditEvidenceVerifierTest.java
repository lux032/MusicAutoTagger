package com.lux032.musicautotagger.service.llm;

import com.lux032.musicautotagger.model.ReviewItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CreditEvidenceVerifierTest {

    private static ReviewItem.CreditEvidenceItem ev(String url, String sourceType, String quote, String versionMatch) {
        ReviewItem.CreditEvidenceItem item = new ReviewItem.CreditEvidenceItem();
        item.setUrl(url);
        item.setSourceType(sourceType);
        item.setQuote(quote);
        item.setVersionMatch(versionMatch);
        return item;
    }

    private static ReviewItem.CreditFieldResult field(ReviewItem.CreditStatus status, List<String> names,
                                                      List<ReviewItem.CreditEvidenceItem> evidence) {
        ReviewItem.CreditFieldResult f = new ReviewItem.CreditFieldResult();
        f.setStatus(status);
        f.setNames(names);
        f.setEvidence(evidence);
        return f;
    }

    @Test
    void verifiesFullPageEvidenceAndRejectsFabricatedUrlOrMissingQuote() {
        String realUrl = "https://himehina.jp/discography/vampy";
        String realCanon = WebSearchClient.canonicalizeUrl(realUrl);
        WebPageFetcher.FetchedPage page = new WebPageFetcher.FetchedPage(
            realUrl, realCanon, "artist_official", true, "direct_http", "ok",
            "VAMPY 作詞：Gohgo 作曲：Soma Genda 編曲：Soma Genda",
            "VAMPY 作詞：Gohgo 作曲：Soma Genda 編曲：Soma Genda"
        );

        ReviewItem.OnlineTrack track = new ReviewItem.OnlineTrack();
        track.setTitle("VAMPY");
        ReviewItem.TrackCreditDetail detail = new ReviewItem.TrackCreditDetail();
        detail.setVersionType("original");
        // Verified on fetched full page (with NFKC full-width punctuation normalization)
        detail.setLyricists(field(ReviewItem.CreditStatus.VERIFIED, List.of("Gohgo"), List.of(
            ev(realUrl + "?utm_source=openai", "artist_official", "作詞: Gohgo", "exact_version")
        )));
        // Fabricated URL not in pool -> must be rejected to NOT_FOUND
        detail.setComposers(field(ReviewItem.CreditStatus.VERIFIED, List.of("Fake Composer"), List.of(
            ev("https://fake.example.com/vampy", "artist_official", "作曲：Fake Composer", "exact_version")
        )));
        // Real URL in pool, but claimed name does NOT exist in fetched page -> must be rejected
        detail.setArrangers(field(ReviewItem.CreditStatus.VERIFIED, List.of("Hallucinated Arranger"), List.of(
            ev(realUrl, "artist_official", "編曲：Hallucinated Arranger", "exact_version")
        )));
        track.setCreditDetail(detail);

        CreditEvidenceVerifier.verifyTrack(track, "HIMEHINA", Map.of(realCanon, page), Map.of(), false, true);

        assertEquals(ReviewItem.CreditStatus.VERIFIED, track.getCreditDetail().getLyricists().getStatus());
        assertEquals(List.of("Gohgo"), track.getLyricists());
        assertEquals(realUrl, track.getCreditDetail().getLyricists().getEvidence().get(0).getUrl(), "utm params should be stripped");

        assertEquals(ReviewItem.CreditStatus.NOT_FOUND, track.getCreditDetail().getComposers().getStatus());
        assertTrue(track.getComposers().isEmpty());

        assertEquals(ReviewItem.CreditStatus.NOT_FOUND, track.getCreditDetail().getArrangers().getStatus());
        assertTrue(track.getArrangers().isEmpty());
    }

    @Test
    void enforcesRemixArrangerAntiInheritanceAndRoleConfusionGuards() {
        String origUrl = "https://himehina.jp/discography/vampy";
        String origCanon = WebSearchClient.canonicalizeUrl(origUrl);
        String remixUrl = "https://prtimes.jp/main/html/rd/p/000000123.html";
        String remixCanon = WebSearchClient.canonicalizeUrl(remixUrl);

        Map<String, WebPageFetcher.FetchedPage> pages = Map.of(
            origCanon, new WebPageFetcher.FetchedPage(origUrl, origCanon, "artist_official", true, "direct_http", "ok",
                "VAMPY 作詞：Gohgo 作曲：Soma Genda 編曲：Soma Genda",
                "VAMPY 作詞：Gohgo 作曲：Soma Genda 編曲：Soma Genda"),
            remixCanon, new WebPageFetcher.FetchedPage(remixUrl, remixCanon, "official_press_release", true, "direct_http", "ok",
                "VAMPY (Latin Night Version) Remixed by yuigot / Produced by HIMEHINA",
                "VAMPY (Latin Night Version) Remixed by yuigot / Produced by HIMEHINA")
        );

        ReviewItem.OnlineTrack track = new ReviewItem.OnlineTrack();
        track.setTitle("VAMPY (Latin Night Version)");
        ReviewItem.TrackCreditDetail detail = new ReviewItem.TrackCreditDetail();
        detail.setVersionType("alternate_arrangement");
        // Original-only lyrics/composition on alt version -> capped at PROBABLE
        detail.setLyricists(field(ReviewItem.CreditStatus.VERIFIED, List.of("Gohgo"), List.of(
            ev(origUrl, "artist_official", "作詞：Gohgo", "original_only")
        )));
        detail.setComposers(field(ReviewItem.CreditStatus.VERIFIED, List.of("Soma Genda"), List.of(
            ev(origUrl, "artist_official", "作曲：Soma Genda", "original_only")
        )));
        // Trying BOTH original_only inheritance AND Remixer-as-Arranger confusion -> MUST be blocked to NOT_FOUND!
        detail.setArrangers(field(ReviewItem.CreditStatus.VERIFIED, List.of("Soma Genda", "yuigot"), List.of(
            ev(origUrl, "artist_official", "編曲：Soma Genda", "original_only"),
            ev(remixUrl, "official_press_release", "Remixed by yuigot", "exact_version")
        )));
        track.setCreditDetail(detail);

        CreditEvidenceVerifier.verifyTrack(track, "HIMEHINA", pages, Map.of(), false, true);

        assertEquals(ReviewItem.CreditStatus.PROBABLE, track.getCreditDetail().getLyricists().getStatus());
        assertEquals(List.of("Gohgo"), track.getLyricists());
        assertEquals(ReviewItem.CreditStatus.PROBABLE, track.getCreditDetail().getComposers().getStatus());
        assertEquals(List.of("Soma Genda"), track.getComposers());

        assertEquals(ReviewItem.CreditStatus.NOT_FOUND, track.getCreditDetail().getArrangers().getStatus());
        assertTrue(track.getArrangers().isEmpty(), "Remix/alt version must NOT inherit original arranger or confuse Remixer");
        assertNotNull(track.getCreditDetail().getArrangers().getNotes());
        assertTrue(track.getCreditDetail().getArrangers().getNotes().contains("Remix"));

        // Re-applying with applyProbable=false clears PROBABLE flat lists
        CreditEvidenceVerifier.applyVerifiedCreditsToTrack(track, false);
        assertTrue(track.getLyricists().isEmpty());
        assertTrue(track.getComposers().isEmpty());
    }

    @Test
    void blocksLyricistsOnInstrumentalTracksAndBlocksAlbumLevelOnlyEvidence() {
        String url = "https://ototoy.jp/album/123";
        String canon = WebSearchClient.canonicalizeUrl(url);
        WebPageFetcher.FetchedPage page = new WebPageFetcher.FetchedPage(
            url, canon, "music_store_platform", true, "direct_http", "ok",
            "Album Staff: 作詞：Gohgo 作曲：Soma Genda 編曲：Soma Genda",
            "Album Staff: 作詞：Gohgo 作曲：Soma Genda 編曲：Soma Genda"
        );

        ReviewItem.OnlineTrack track = new ReviewItem.OnlineTrack();
        track.setTitle("VAMPY (Off Vocal)");
        ReviewItem.TrackCreditDetail detail = new ReviewItem.TrackCreditDetail();
        detail.setVersionType("instrumental");
        detail.setLyricists(field(ReviewItem.CreditStatus.VERIFIED, List.of("Gohgo"), List.of(
            ev(url, "music_store_platform", "作詞：Gohgo", "exact_version")
        )));
        detail.setComposers(field(ReviewItem.CreditStatus.VERIFIED, List.of("Soma Genda"), List.of(
            ev(url, "music_store_platform", "作曲：Soma Genda", "album_level_only")
        )));
        detail.setArrangers(field(ReviewItem.CreditStatus.VERIFIED, List.of("Soma Genda"), List.of(
            ev(url, "music_store_platform", "編曲：Soma Genda", "exact_version")
        )));
        track.setCreditDetail(detail);

        CreditEvidenceVerifier.verifyTrack(track, "HIMEHINA", Map.of(canon, page), Map.of(), false, true);

        assertEquals(ReviewItem.CreditStatus.NOT_FOUND, track.getCreditDetail().getLyricists().getStatus());
        assertTrue(track.getLyricists().isEmpty());
        assertEquals(ReviewItem.CreditStatus.NOT_FOUND, track.getCreditDetail().getComposers().getStatus(),
            "album_level_only must not be promoted to track-level credits");
        assertTrue(track.getComposers().isEmpty());
        assertEquals(ReviewItem.CreditStatus.VERIFIED, track.getCreditDetail().getArrangers().getStatus());
        assertEquals(List.of("Soma Genda"), track.getArrangers());
    }

    @Test
    void detectsSyndicatedPressDuplicatesPreservesConflictCandidatesAndTruncatesQuotes() {
        String u1 = "https://prtimes.jp/main/html/rd/p/1.html";
        String u2 = "https://natalie.mu/music/news/2";
        String u3 = "https://utaten.com/lyric/drain-drain/";
        String c1 = WebSearchClient.canonicalizeUrl(u1);
        String c2 = WebSearchClient.canonicalizeUrl(u2);
        String c3 = WebSearchClient.canonicalizeUrl(u3);
        String sharedPressText = "HIMEHINAの最新EP『VAMPY』収録曲「Drain Drain」は作詞をGohgo、作曲を南田健吾が担当している公式発表の全文コピーです。";
        String longQuote = sharedPressText + " ".repeat(10) + "あ".repeat(250);
        String altSourceText = "Drain Drain 歌詞ページ 作詞：Gohgo 作曲：Soma Genda 編曲：Soma Genda";

        Map<String, WebPageFetcher.FetchedPage> pages = Map.of(
            c1, new WebPageFetcher.FetchedPage(u1, c1, "official_press_release", true, "direct_http", "ok", longQuote, longQuote),
            c2, new WebPageFetcher.FetchedPage(u2, c2, "official_press_release", true, "direct_http", "ok", sharedPressText, sharedPressText),
            c3, new WebPageFetcher.FetchedPage(u3, c3, "jp_lyrics_site", true, "direct_http", "ok", altSourceText, altSourceText)
        );

        ReviewItem.OnlineTrack track = new ReviewItem.OnlineTrack();
        track.setTitle("Drain Drain");
        ReviewItem.TrackCreditDetail detail = new ReviewItem.TrackCreditDetail();
        detail.setVersionType("original");
        detail.setLyricists(field(ReviewItem.CreditStatus.VERIFIED, List.of("Gohgo"), List.of(
            ev(u1, "official_press_release", longQuote, "exact_version"),
            ev(u2, "official_press_release", sharedPressText, "exact_version")
        )));

        // Conflict on composers between two distinct attested sources (u1 vs u3)
        ReviewItem.CreditFieldResult conflictComp = new ReviewItem.CreditFieldResult();
        conflictComp.setStatus(ReviewItem.CreditStatus.CONFLICT);
        conflictComp.setNames(List.of("南田健吾"));
        ReviewItem.CreditConflictCandidate cand1 = new ReviewItem.CreditConflictCandidate();
        cand1.setNames(List.of("南田健吾"));
        cand1.setUrls(List.of(u1));
        cand1.setQuotes(List.of("作曲を南田健吾"));
        ReviewItem.CreditConflictCandidate cand2 = new ReviewItem.CreditConflictCandidate();
        cand2.setNames(List.of("Soma Genda"));
        cand2.setUrls(List.of(u3));
        cand2.setQuotes(List.of("作曲：Soma Genda"));
        conflictComp.setCandidates(List.of(cand1, cand2));
        detail.setComposers(conflictComp);
        track.setCreditDetail(detail);

        CreditEvidenceVerifier.verifyTrack(track, "HIMEHINA", pages, Map.of(), false, true);

        List<ReviewItem.CreditEvidenceItem> lyrEv = track.getCreditDetail().getLyricists().getEvidence();
        assertEquals(2, lyrEv.size());
        assertFalse(lyrEv.get(0).isSyndicatedDuplicate());
        assertTrue(lyrEv.get(1).isSyndicatedDuplicate(), "Syndicated press copy must be flagged as duplicate");
        assertTrue(lyrEv.get(0).getQuote().length() <= CreditEvidenceVerifier.MAX_QUOTE_CHARS);

        assertEquals(ReviewItem.CreditStatus.CONFLICT, track.getCreditDetail().getComposers().getStatus());
        assertTrue(track.getComposers().isEmpty(), "CONFLICT must not auto-populate flat composer list");
        assertEquals(2, track.getCreditDetail().getComposers().getCandidates().size());
    }

    @Test
    void nativePoolWithoutBodyDistinguishesOpenedPageFromSearchOnlyAndNeverOverridesFailedFetchedPage() {
        String openedUrl = "https://www.uta-net.com/song/402838/";
        String searchOnlyUrl = "https://ototoy.jp/album/999";
        String fetchedMismatchUrl = "https://himehina.jp/vampy";

        WebSearchClient.Citation openedCit = WebSearchClient.Citation.of(openedUrl, "Uta-Net", "", true);
        WebSearchClient.Citation searchOnlyCit = WebSearchClient.Citation.of(searchOnlyUrl, "OTOTOY", "", false);
        WebSearchClient.Citation mismatchCit = WebSearchClient.Citation.of(fetchedMismatchUrl, "HIMEHINA", "", true);

        Map<String, WebSearchClient.Citation> pool = Map.of(
            WebSearchClient.canonicalizeUrl(openedUrl), openedCit,
            WebSearchClient.canonicalizeUrl(searchOnlyUrl), searchOnlyCit,
            WebSearchClient.canonicalizeUrl(fetchedMismatchUrl), mismatchCit
        );
        // Suppose we DID fetch himehina.jp directly and it did NOT contain the claimed arranger
        Map<String, WebPageFetcher.FetchedPage> fetched = Map.of(
            WebSearchClient.canonicalizeUrl(fetchedMismatchUrl),
            new WebPageFetcher.FetchedPage(fetchedMismatchUrl, WebSearchClient.canonicalizeUrl(fetchedMismatchUrl),
                "artist_official", true, "direct_http", "ok", "HIMEHINA VAMPY official page without credits",
                "HIMEHINA VAMPY official page without credits")
        );

        ReviewItem.OnlineTrack track = new ReviewItem.OnlineTrack();
        track.setTitle("VAMPY");
        ReviewItem.TrackCreditDetail detail = new ReviewItem.TrackCreditDetail();
        detail.setVersionType("original");
        detail.setLyricists(field(ReviewItem.CreditStatus.VERIFIED, List.of("Gohgo"), List.of(
            ev(openedUrl, "jp_lyrics_site", "作詞：Gohgo", "exact_version")
        )));
        detail.setComposers(field(ReviewItem.CreditStatus.VERIFIED, List.of("Soma Genda"), List.of(
            ev(searchOnlyUrl, "music_store_platform", "作曲：Soma Genda", "exact_version")
        )));
        detail.setArrangers(field(ReviewItem.CreditStatus.VERIFIED, List.of("Phantom Arranger"), List.of(
            ev(fetchedMismatchUrl, "artist_official", "編曲：Phantom Arranger", "exact_version")
        )));
        track.setCreditDetail(detail);

        CreditEvidenceVerifier.verifyTrack(track, "HIMEHINA", fetched, pool, true, true);

        assertEquals(ReviewItem.CreditStatus.VERIFIED, track.getCreditDetail().getLyricists().getStatus());
        assertEquals(ReviewItem.CreditStatus.PROBABLE, track.getCreditDetail().getComposers().getStatus());
        assertEquals(ReviewItem.CreditStatus.NOT_FOUND, track.getCreditDetail().getArrangers().getStatus(),
            "When a full page WAS fetched and failed attestation, native pool fallback must NOT override it");
    }

    @Test
    void enforcesHardenedCodexReviewGuardsAttributionBindingSourceAuthorityMixAndCrossRoundConflict() {
        String blogUrl = "https://random-blog.example.com/album";
        String blogCanon = WebSearchClient.canonicalizeUrl(blogUrl);
        String blogText = "Track 1 Arrangement: Alice. Track 2 Mix: Bob.";

        String officialUrl = "https://himehina.jp/discography/vampy";
        String officialCanon = WebSearchClient.canonicalizeUrl(officialUrl);
        String officialText = "Track 1 作詞：Gohgo 作曲：Soma Genda 編曲：Soma Genda";

        Map<String, WebPageFetcher.FetchedPage> pages = Map.of(
            blogCanon, new WebPageFetcher.FetchedPage(blogUrl, blogCanon, "other", true, "direct_http", "ok", blogText, blogText),
            officialCanon, new WebPageFetcher.FetchedPage(officialUrl, officialCanon, "artist_official", true, "direct_http", "ok", officialText, officialText)
        );

        ReviewItem.OnlineTrack track = new ReviewItem.OnlineTrack();
        track.setTitle("Track 1");
        ReviewItem.TrackCreditDetail detail = new ReviewItem.TrackCreditDetail();
        detail.setVersionType("original");

        // 1. 跨曲目拼接：引文写的是 Alice，却声称 Bob -> 必须拦截（人名必须在引文内）
        //    同时测试独立 "Mix: Bob" 角色护栏
        detail.setArrangers(field(ReviewItem.CreditStatus.VERIFIED, List.of("Bob"), List.of(
            ev(blogUrl, "artist_official", "Arrangement: Alice", "exact_version"),
            ev(blogUrl, "artist_official", "Mix: Bob", "exact_version")
        )));
        // 2. 模型自报 source_type="artist_official" 在普通博客上 -> 服务端重算为 "other"，最高仅 PROBABLE
        detail.setComposers(field(ReviewItem.CreditStatus.VERIFIED, List.of("Alice"), List.of(
            ev(blogUrl, "artist_official", "Arrangement: Alice", "exact_version")
        )));
        // 3. version_match="unknown" 在普通曲目上绝不可升级为 VERIFIED
        detail.setLyricists(field(ReviewItem.CreditStatus.VERIFIED, List.of("Gohgo"), List.of(
            ev(officialUrl, "artist_official", "作詞：Gohgo", "unknown")
        )));
        track.setCreditDetail(detail);

        CreditEvidenceVerifier.verifyTrack(track, "HIMEHINA", pages, Map.of(), false, true);

        assertEquals(ReviewItem.CreditStatus.NOT_FOUND, track.getCreditDetail().getArrangers().getStatus(),
            "Cross-track splice and standalone Mix role must be rejected");
        assertEquals(ReviewItem.CreditStatus.PROBABLE, track.getCreditDetail().getComposers().getStatus(),
            "Spoofed model source_type on unrelated blog must not grant VERIFIED");
        assertEquals("other", track.getCreditDetail().getComposers().getEvidence().get(0).getSourceType());
        assertEquals(ReviewItem.CreditStatus.PROBABLE, track.getCreditDetail().getLyricists().getStatus(),
            "unknown version_match must stay PROBABLE, not VERIFIED");

        // 4. 跨轮次不同独立 VERIFIED 结果合并必须自动合成 CONFLICT
        ReviewItem.CreditFieldResult r1 = field(ReviewItem.CreditStatus.VERIFIED, List.of("Soma Genda"), List.of(
            ev(officialUrl, "artist_official", "作曲：Soma Genda", "exact_version")
        ));
        ReviewItem.CreditFieldResult r2 = field(ReviewItem.CreditStatus.VERIFIED, List.of("南田健吾"), List.of(
            ev("https://utaten.com/lyric/123/", "jp_lyrics_site", "作曲：南田健吾", "exact_version")
        ));
        ReviewItem.CreditFieldResult merged = CreditEvidenceVerifier.mergeFieldResults(r1, r2);
        assertEquals(ReviewItem.CreditStatus.CONFLICT, merged.getStatus());
        assertTrue(merged.getNames().isEmpty());
        assertEquals(2, merged.getCandidates().size());

        // 5. 原生池无正文且引文为空时，必须拒绝成为 PROBABLE
        String nativeUrl = "https://ototoy.jp/album/555";
        Map<String, WebSearchClient.Citation> nativePool = Map.of(
            WebSearchClient.canonicalizeUrl(nativeUrl), WebSearchClient.Citation.of(nativeUrl, "OTOTOY", "", false)
        );
        ReviewItem.OnlineTrack nativeTrack = new ReviewItem.OnlineTrack();
        nativeTrack.setTitle("VAMPY");
        ReviewItem.TrackCreditDetail nd = new ReviewItem.TrackCreditDetail();
        nd.setVersionType("original");
        nd.setComposers(field(ReviewItem.CreditStatus.PROBABLE, List.of("Ghost"), List.of(
            ev(nativeUrl, "music_store_platform", "", "exact_version")
        )));
        nativeTrack.setCreditDetail(nd);
        CreditEvidenceVerifier.verifyTrack(nativeTrack, "HIMEHINA", Map.of(), nativePool, true, true);
        assertEquals(ReviewItem.CreditStatus.NOT_FOUND, nativeTrack.getCreditDetail().getComposers().getStatus(),
            "Empty-quote URL-only native citation must not be admitted");
    }
}
