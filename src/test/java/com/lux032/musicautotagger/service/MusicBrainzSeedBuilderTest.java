package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.model.MbContributionItem;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MusicBrainzSeedBuilderTest {

    @Test
    void buildsCompliantSeedingForCaseBMultiArtistPartialLocalCoverageAndRejectsFakeUuid() {
        MbContributionItem item = new MbContributionItem();
        item.setId("contrib-1");
        item.setStatus(MbContributionItem.Status.READY_TO_SEED);
        item.setCheckCase(MbContributionItem.CheckCase.MISSING_EDITION);
        item.setMatchedReleaseGroupId("22222222-2222-4222-8222-222222222222");
        item.setUseExistingReleaseGroup(true);

        MbContributionItem.ReleaseDraft draft = item.getDraft();
        draft.setOfficialCandidate(true);
        draft.setTrackCoverage(1.0);
        draft.setTitle("Beyond the Night");
        draft.setComment("Japanese digital single");
        draft.setAlbumArtist("TeddyLoid, 佐倉綾音 & 井上和");
        draft.setAlbumArtists(List.of("TeddyLoid", "佐倉綾音", "井上和"));
        draft.setReleaseDate("2026-11-27");
        draft.setCountry("jp");
        draft.setReleaseType("single");
        draft.setMediaFormat("Digital Media");
        draft.setLabel("King Records");
        draft.setCatalogNumber("KICA-9999");
        draft.setBarcode("4988003600001");

        // 绑定 2 位真实 MBID + 1 位非法 UUID（应自动拦截不输出为 mbid）
        MbContributionItem.EntityMbidRef ref1 = new MbContributionItem.EntityMbidRef();
        ref1.setMbid("11111111-1111-4111-8111-111111111111");
        ref1.setCanonicalName("TeddyLoid");
        MbContributionItem.EntityMbidRef ref2 = new MbContributionItem.EntityMbidRef();
        ref2.setMbid("33333333-3333-4333-8333-333333333333");
        ref2.setCanonicalName("佐倉綾音");
        MbContributionItem.EntityMbidRef badRef = new MbContributionItem.EntityMbidRef();
        badRef.setMbid("not-a-valid-uuid");
        draft.setEntityMbids(Map.of(
            MusicBrainzArtistCache.normalizePersonKey("TeddyLoid"), ref1,
            MusicBrainzArtistCache.normalizePersonKey("佐倉綾音"), ref2,
            MusicBrainzArtistCache.normalizePersonKey("井上和"), badRef
        ));

        // 2 首完整官方曲目，但本地只有第 1 首
        MbContributionItem.ContributionTrack t1 = new MbContributionItem.ContributionTrack();
        t1.setDiscNo(1);
        t1.setTrackNo(1);
        t1.setTitle("Beyond the Night");
        t1.setArtist("TeddyLoid, 佐倉綾音 & 井上和");
        t1.setArtists(List.of("TeddyLoid", "佐倉綾音", "井上和"));
        t1.setDurationMs(215432L);
        t1.setDurationSource("LOCAL_PRECISE");
        t1.setLocalFilePath("/Music/a.flac");
        t1.setMatchedConfidence(1.0);
        t1.setExistingRecordingId("77777777-7777-4777-8777-777777777771");

        MbContributionItem.ContributionTrack t2 = new MbContributionItem.ContributionTrack();
        t2.setDiscNo(1);
        t2.setTrackNo(2);
        t2.setTitle("Beyond the Night (Instrumental)");
        t2.setArtist("TeddyLoid");
        t2.setArtists(List.of("TeddyLoid"));
        t2.setDurationMs(215000L);
        t2.setDurationSource("ONLINE");
        draft.setTracks(List.of(t1, t2));

        MbContributionItem.SourceUrlEntry highSrc = new MbContributionItem.SourceUrlEntry();
        highSrc.setUrl("https://music.apple.com/jp/album/beyond/123?utm_source=test");
        highSrc.setTitle("Apple Music");
        highSrc.setReliability("HIGH");
        MbContributionItem.SourceUrlEntry lowSrc = new MbContributionItem.SourceUrlEntry();
        lowSrc.setUrl("https://random-blog.example.com/post");
        lowSrc.setReliability("LOW");
        MbContributionItem.SourceUrlEntry localSrc = new MbContributionItem.SourceUrlEntry();
        localSrc.setUrl("http://172.16.0.10/internal");
        localSrc.setReliability("HIGH");
        draft.setSourceUrls(List.of(highSrc, lowSrc, localSrc));

        MusicBrainzSeedBuilder.SeedPayload payload = MusicBrainzSeedBuilder.build(item);
        assertTrue(payload.seedable());
        Map<String, String> map = new LinkedHashMap<>();
        for (MusicBrainzSeedBuilder.SeedField f : payload.fields()) {
            map.put(f.name(), f.value());
        }

        assertEquals(MusicBrainzSeedBuilder.RELEASE_ADD_URL, payload.actionUrl());
        assertEquals("Beyond the Night", map.get("name"));
        assertEquals("22222222-2222-4222-8222-222222222222", map.get("release_group"));
        assertNull(map.get("type"), "挂入已有 RG 时不应同时传新建 RG type");
        assertEquals("official", map.get("status"));
        assertEquals("None", map.get("packaging"));
        assertEquals("2026", map.get("events.0.date.year"));
        assertEquals("11", map.get("events.0.date.month"));
        assertEquals("27", map.get("events.0.date.day"));
        assertEquals("JP", map.get("events.0.country"));
        assertEquals("4988003600001", map.get("barcode"));
        assertEquals("King Records", map.get("labels.0.name"));
        assertEquals("KICA-9999", map.get("labels.0.catalog_number"));

        // 多人艺人 + 连接符 + 非法 UUID 拦截
        assertEquals("11111111-1111-4111-8111-111111111111", map.get("artist_credit.names.0.mbid"));
        assertEquals(", ", map.get("artist_credit.names.0.join_phrase"));
        assertEquals("33333333-3333-4333-8333-333333333333", map.get("artist_credit.names.1.mbid"));
        assertEquals(" & ", map.get("artist_credit.names.1.join_phrase"));
        assertNull(map.get("artist_credit.names.2.mbid"), "非法 UUID 不得写入 mbid");
        assertEquals("井上和", map.get("artist_credit.names.2.artist.name"));

        // 完整官方 2 首曲目表均保留（含第 1 首本地精准毫秒时长与既有 Recording MBID）
        assertEquals("Digital Media", map.get("mediums.0.format"));
        assertEquals("215432", map.get("mediums.0.track.0.length"));
        assertEquals("77777777-7777-4777-8777-777777777771", map.get("mediums.0.track.0.recording"));
        assertEquals("Beyond the Night (Instrumental)", map.get("mediums.0.track.1.name"));
        assertEquals("215000", map.get("mediums.0.track.1.length"));
        assertFalse(payload.warnings().isEmpty(), "本地缺轨时应给用户提示");

        // 仅输出安全可信来源 URL
        assertEquals("https://music.apple.com/jp/album/beyond/123", map.get("urls.0.url"));
        assertNull(map.get("urls.1.url"));
        assertTrue(map.get("edit_note").contains("https://music.apple.com/jp/album/beyond/123"));

        // 切换为新建 RG 后应省略 release_group 并携带 type
        item.setUseExistingReleaseGroup(false);
        MusicBrainzSeedBuilder.SeedPayload newRgPayload = MusicBrainzSeedBuilder.build(item);
        Map<String, String> newRgMap = new LinkedHashMap<>();
        newRgPayload.fields().forEach(f -> newRgMap.put(f.name(), f.value()));
        assertNull(newRgMap.get("release_group"));
        assertEquals("single", newRgMap.get("type"));

        // 不可存在日历日期（如 2月31日）与 Case A / 终态拦截
        assertFalse(MusicBrainzSeedBuilder.isValidPartialIsoDate("2026-02-31"));
        assertTrue(MusicBrainzSeedBuilder.isValidPartialIsoDate("2026-02-28"));

        item.setStatus(MbContributionItem.Status.EXISTING_MATCH);
        item.setCheckCase(MbContributionItem.CheckCase.EXISTING_RELEASE);
        MusicBrainzSeedBuilder.SeedPayload blockedCaseA = MusicBrainzSeedBuilder.build(item);
        assertFalse(blockedCaseA.seedable());
        assertTrue(blockedCaseA.fields().isEmpty());
    }
}
