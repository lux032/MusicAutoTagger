package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.model.MbContributionItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MusicBrainzCreditSanitizerTest {

    @Test
    void stripsAffiliationBracketsForWorksGuideAndBuildsCaaSeededLinks() {
        MbContributionItem item = new MbContributionItem();
        item.setId("c-1");
        item.setLinkedReleaseId("44444444-4444-4444-8444-444444444444");

        MbContributionItem.EntityMbidRef tepeRef = new MbContributionItem.EntityMbidRef();
        tepeRef.setMbid("9913e701-9bad-4e37-8968-e231ef42803b");
        tepeRef.setCanonicalName("tepe");
        tepeRef.setDisambiguation("composer & arranger");

        MbContributionItem.EntityMbidRef amalaRef = new MbContributionItem.EntityMbidRef();
        amalaRef.setMbid("74b4d464-7b7c-48c4-b96d-c5adf7f8e1c9");
        amalaRef.setCanonicalName("雨良");
        amalaRef.setDisambiguation("Vocaloid producer");

        item.getDraft().setEntityMbids(Map.of(
            MusicBrainzArtistCache.normalizePersonKey("tepe (OTOIRO)"), tepeRef,
            MusicBrainzArtistCache.normalizePersonKey("雨良 Amala (ninelive.)"), amalaRef
        ));

        MbContributionItem.ContributionTrack t1 = new MbContributionItem.ContributionTrack();
        t1.setDiscNo(1);
        t1.setTrackNo(1);
        t1.setTitle("Drain Drain");
        t1.setComposers(List.of("雨良 Amala (ninelive.)"));
        t1.setComposerSource("ONLINE_PROBABLE");
        t1.setLyricists(List.of("tepe (OTOIRO)"));
        t1.setLyricistSource("ONLINE_VERIFIED");
        t1.setArrangers(List.of("冲突未决人物"));
        t1.setArrangerSource("CONFLICT");
        t1.setCreditEvidenceUrls(List.of("https://spc.himehina.jp/vampy"));
        t1.setCreditEvidenceQuotes(List.of("作詞・作曲・編曲：雨良 Amala (ninelive.)"));
        item.getDraft().setTracks(List.of(t1));

        MusicBrainzCreditSanitizer.WorksGuide guide = MusicBrainzCreditSanitizer.buildWorksGuide(item);
        assertEquals("https://musicbrainz.org/release/44444444-4444-4444-8444-444444444444/edit-relationships",
            guide.editRelationshipsUrl());
        assertTrue(guide.hasAnyCredits());
        assertEquals(1, guide.tracks().size());

        MusicBrainzCreditSanitizer.TrackWorksGuide tg = guide.tracks().get(0);
        // 雨良 Amala (ninelive.) -> 剥离 (ninelive.)，主名为 雨良，人名变体建议 credited as = 雨良 Amala，且标记 probable 需核对
        assertEquals(1, tg.composers().size());
        MusicBrainzCreditSanitizer.SanitizedCreditPerson amala = tg.composers().get(0);
        assertEquals("雨良 Amala (ninelive.)", amala.rawName());
        assertEquals("雨良 Amala", amala.cleanName());
        assertEquals("ninelive.", amala.affiliationNote());
        assertEquals("雨良 Amala", amala.suggestedCreditedAs());
        assertEquals("74b4d464-7b7c-48c4-b96d-c5adf7f8e1c9", amala.mbid());
        assertTrue(amala.needsVerification());

        // tepe (OTOIRO) -> 剥离 (OTOIRO)，与主名 tepe 一致，credited as 应为空
        assertEquals(1, tg.lyricists().size());
        MusicBrainzCreditSanitizer.SanitizedCreditPerson tepe = tg.lyricists().get(0);
        assertEquals("tepe", tepe.cleanName());
        assertEquals("OTOIRO", tepe.affiliationNote());
        assertNull(tepe.suggestedCreditedAs());
        assertFalse(tepe.needsVerification());

        // 冲突字段必须自动排除
        assertTrue(tg.arrangers().isEmpty());
        assertTrue(tg.suggestedEditNote().contains("ninelive."));
        assertTrue(tg.suggestedEditNote().contains("https://spc.himehina.jp/vampy"));

        // CAA 引导：Apple Music 1200 -> 3000 升格 + x_seed 参数
        MbContributionItem.CoverInfo cover = new MbContributionItem.CoverInfo();
        cover.setOrigin("ITUNES");
        cover.setPublicUrl("https://is1-ssl.mzstatic.com/image/thumb/Music221/v4/aa/bb/cc/1200x1200bb.jpg");
        cover.setOriginPageUrl("https://music.apple.com/jp/album/vampy/123456");
        cover.setMime("image/jpeg");
        cover.setWidth(1200);
        cover.setHeight(1200);
        item.setCover(cover);

        MusicBrainzCreditSanitizer.CoverArtGuide caa =
            MusicBrainzCreditSanitizer.buildCoverGuidance(item, true, "/api/mb-contributions/cover");
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music221/v4/aa/bb/cc/3000x3000bb.jpg",
            caa.publicHighResUrl());
        assertTrue(caa.addCoverArtSeededUrl().startsWith(
            "https://musicbrainz.org/release/44444444-4444-4444-8444-444444444444/add-cover-art?"));
        assertTrue(caa.addCoverArtSeededUrl().contains("x_seed.image.0.types=%5B1%5D"));
        assertFalse(caa.addCoverArtSeededUrl().contains("x_seed.image.0.comment="));
        assertTrue(caa.addCoverArtSeededUrl().contains("3000x3000bb.jpg"));
        assertEquals("/api/mb-contributions/cover?id=c-1", caa.localDownloadUrl());

        // 本地内嵌封面回退来源时应跳过单曲歌词页，优先挑专辑商店链接；单曲按自身歌词证据推断 lyricsLanguage
        MbContributionItem.ContributionTrack jpTrack = new MbContributionItem.ContributionTrack();
        jpTrack.setDiscNo(1);
        jpTrack.setTrackNo(2);
        jpTrack.setTitle("きみキミ血ぅ毒");
        jpTrack.setLyricists(List.of("tepe (OTOIRO)"));
        jpTrack.setLyricistSource("ONLINE_VERIFIED");
        MbContributionItem.ContributionTrack instTrack = new MbContributionItem.ContributionTrack();
        instTrack.setDiscNo(1);
        instTrack.setTrackNo(3);
        instTrack.setTitle("きみキミ血ぅ毒 (Instrumental)");
        instTrack.setComposers(List.of("tepe (OTOIRO)"));
        instTrack.setComposerSource("ONLINE_VERIFIED");
        item.getDraft().setTracks(List.of(t1, jpTrack, instTrack));

        MbContributionItem.SourceUrlEntry lyricsSrc = new MbContributionItem.SourceUrlEntry();
        lyricsSrc.setUrl("https://ftp.uta-net.com/song/402836");
        lyricsSrc.setReliability("HIGH");
        MbContributionItem.SourceUrlEntry amazonSrc = new MbContributionItem.SourceUrlEntry();
        amazonSrc.setUrl("https://music.amazon.co.jp/albums/B0HR9LNDSZ");
        amazonSrc.setReliability("HIGH");
        item.getDraft().setSourceUrls(List.of(lyricsSrc, amazonSrc));

        MbContributionItem.CoverInfo embeddedCover = new MbContributionItem.CoverInfo();
        embeddedCover.setOrigin("EMBEDDED");
        embeddedCover.setOriginPageUrl("https://ftp.uta-net.com/song/402836");
        item.setCover(embeddedCover);
        MusicBrainzCreditSanitizer.CoverArtGuide localCaa =
            MusicBrainzCreditSanitizer.buildCoverGuidance(item, true, "/api/mb-contributions/cover");
        assertEquals("https://music.amazon.co.jp/albums/B0HR9LNDSZ", localCaa.originPageUrl());
        assertTrue(localCaa.suggestedComment().contains("https://music.amazon.co.jp/albums/B0HR9LNDSZ"));
        assertFalse(localCaa.suggestedComment().contains("uta-net.com"));

        // 仅剩歌词页时，CAA originPageUrl 必须留空而不是误把歌词页标成官方封面出处
        item.getDraft().setSourceUrls(List.of(lyricsSrc));
        MusicBrainzCreditSanitizer.CoverArtGuide onlyLyricsCaa =
            MusicBrainzCreditSanitizer.buildCoverGuidance(item, true, "/api/mb-contributions/cover");
        assertNull(onlyLyricsCaa.originPageUrl());
        assertFalse(onlyLyricsCaa.suggestedComment().contains("uta-net.com"));

        MusicBrainzCreditSanitizer.WorksGuide jpGuide = MusicBrainzCreditSanitizer.buildWorksGuide(item);
        assertNull(jpGuide.tracks().get(0).lyricsLanguage());
        assertEquals("jpn", jpGuide.tracks().get(1).lyricsLanguage());
        assertNull(jpGuide.tracks().get(2).lyricsLanguage());
    }
}
