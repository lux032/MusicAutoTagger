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

        // 切换为新建 RG 后应省略 release_group 并携带 type，且 Case B 仍保留版本消歧
        item.setUseExistingReleaseGroup(false);
        MusicBrainzSeedBuilder.SeedPayload newRgPayload = MusicBrainzSeedBuilder.build(item);
        Map<String, String> newRgMap = new LinkedHashMap<>();
        newRgPayload.fields().forEach(f -> newRgMap.put(f.name(), f.value()));
        assertNull(newRgMap.get("release_group"));
        assertEquals("single", newRgMap.get("type"));
        assertEquals("Japanese digital single", newRgMap.get("comment"));

        // 不可存在日历日期（如 2月31日）与 Case A / 终态拦截
        assertFalse(MusicBrainzSeedBuilder.isValidPartialIsoDate("2026-02-31"));
        assertTrue(MusicBrainzSeedBuilder.isValidPartialIsoDate("2026-02-28"));

        item.setStatus(MbContributionItem.Status.EXISTING_MATCH);
        item.setCheckCase(MbContributionItem.CheckCase.EXISTING_RELEASE);
        MusicBrainzSeedBuilder.SeedPayload blockedCaseA = MusicBrainzSeedBuilder.build(item);
        assertFalse(blockedCaseA.seedable());
        assertTrue(blockedCaseA.fields().isEmpty());
    }

    @Test
    void filtersLyricsUrlsFromReleaseLinksSplitsCompositeLabelsInfersKanaLanguageAndOmitsGenericCommentOnNewRg() {
        MbContributionItem item = new MbContributionItem();
        item.setId("contrib-vampy");
        item.setStatus(MbContributionItem.Status.READY_TO_SEED);
        item.setCheckCase(MbContributionItem.CheckCase.MISSING_RELEASE_GROUP);

        MbContributionItem.ReleaseDraft draft = item.getDraft();
        draft.setOfficialCandidate(true);
        draft.setTrackCoverage(1.0);
        draft.setTitle("VAMPY");
        draft.setComment("Japanese digital EP");
        draft.setAlbumArtist("HIMEHINA");
        draft.setAlbumArtists(List.of("HIMEHINA"));
        draft.setReleaseDate("2026-11-27");
        draft.setCountry("JP");
        draft.setReleaseType("EP");
        draft.setMediaFormat("Digital Media");
        draft.setLabel("HAO Music / Studio LaRa");

        MbContributionItem.ContributionTrack t1 = new MbContributionItem.ContributionTrack();
        t1.setDiscNo(1);
        t1.setTrackNo(1);
        t1.setTitle("VAMPY");
        t1.setArtist("HIMEHINA");
        t1.setDurationMs(216767L);
        t1.setDurationSource("LOCAL_PRECISE");
        t1.setLocalFilePath("/Music/HIMEHINA/VAMPY/01.flac");
        t1.setMatchedConfidence(1.0);

        MbContributionItem.ContributionTrack t2 = new MbContributionItem.ContributionTrack();
        t2.setDiscNo(1);
        t2.setTrackNo(2);
        t2.setTitle("きみキミ血ぅ毒");
        t2.setArtist("HIMEHINA");
        t2.setDurationMs(169367L);
        t2.setDurationSource("LOCAL_PRECISE");
        t2.setLocalFilePath("/Music/HIMEHINA/VAMPY/02.flac");
        t2.setMatchedConfidence(1.0);
        draft.setTracks(List.of(t1, t2));

        MbContributionItem.SourceUrlEntry lyricsSrc = new MbContributionItem.SourceUrlEntry();
        lyricsSrc.setUrl("https://ftp.uta-net.com/song/402836");
        lyricsSrc.setReliability("HIGH");
        MbContributionItem.SourceUrlEntry storeSrc = new MbContributionItem.SourceUrlEntry();
        storeSrc.setUrl("https://music.amazon.co.jp/albums/B0HR9LNDSZ");
        storeSrc.setReliability("HIGH");
        draft.setSourceUrls(List.of(lyricsSrc, storeSrc));

        MusicBrainzSeedBuilder.SeedPayload payload = MusicBrainzSeedBuilder.build(item);
        assertTrue(payload.seedable());
        Map<String, String> map = new LinkedHashMap<>();
        payload.fields().forEach(f -> map.put(f.name(), f.value()));

        // 新建独立 RG 时省略纯格式消歧 "Japanese digital EP"
        assertNull(map.get("comment"));
        // 曲目含日文假名时自动补全 jpn / Jpan
        assertEquals("jpn", map.get("language"));
        assertEquals("Jpan", map.get("script"));
        // 拼接厂牌拆分
        assertEquals("HAO Music", map.get("labels.0.name"));
        assertEquals("Studio LaRa", map.get("labels.1.name"));
        // 单曲歌词页仅留在 edit_note，不塞进 Release External Links
        assertEquals("https://music.amazon.co.jp/albums/B0HR9LNDSZ", map.get("urls.0.url"));
        assertNull(map.get("urls.1.url"));
        assertTrue(map.get("edit_note").contains("https://ftp.uta-net.com/song/402836"));
        assertTrue(map.get("edit_note").contains("https://music.amazon.co.jp/albums/B0HR9LNDSZ"));

        // 有明确版本区分意义的 comment（如 Limited Edition）应保留
        draft.setComment("Limited Edition");
        draft.setCatalogNumber("HAO-001");
        MusicBrainzSeedBuilder.SeedPayload limited = MusicBrainzSeedBuilder.build(item);
        Map<String, String> limitedMap = new LinkedHashMap<>();
        limited.fields().forEach(f -> limitedMap.put(f.name(), f.value()));
        assertEquals("Limited Edition", limitedMap.get("comment"));
        // 多厂牌拆分时不把目录号盲目挂到第一家，而是写入 edit_note 并提示
        assertNull(limitedMap.get("labels.0.catalog_number"));
        assertTrue(limitedMap.get("edit_note").contains("HAO-001"));
        assertFalse(limited.warnings().isEmpty());

        // 10 条歌词页不能挤掉第 11 条专辑商店链接；Apple Music ?i= 单轨页过滤；YouTube 链接保留
        java.util.ArrayList<MbContributionItem.SourceUrlEntry> manySources = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            MbContributionItem.SourceUrlEntry s = new MbContributionItem.SourceUrlEntry();
            s.setUrl("https://www.uta-net.com/song/" + (400000 + i));
            s.setReliability("HIGH");
            manySources.add(s);
        }
        MbContributionItem.SourceUrlEntry appleTrack = new MbContributionItem.SourceUrlEntry();
        appleTrack.setUrl("https://music.apple.com/jp/album/vampy/6833337851?i=6833337852");
        appleTrack.setReliability("HIGH");
        manySources.add(appleTrack);
        MbContributionItem.SourceUrlEntry store11 = new MbContributionItem.SourceUrlEntry();
        store11.setUrl("https://music.amazon.co.jp/albums/B0HR9LNDSZ");
        store11.setReliability("HIGH");
        manySources.add(store11);
        MbContributionItem.SourceUrlEntry yt = new MbContributionItem.SourceUrlEntry();
        yt.setUrl("https://youtu.be/dQw4w9WgXcQ");
        yt.setReliability("MEDIUM");
        manySources.add(yt);
        draft.setSourceUrls(manySources);

        MusicBrainzSeedBuilder.SeedPayload quotaPayload = MusicBrainzSeedBuilder.build(item);
        Map<String, String> quotaMap = new LinkedHashMap<>();
        quotaPayload.fields().forEach(f -> quotaMap.put(f.name(), f.value()));
        assertEquals("https://music.amazon.co.jp/albums/B0HR9LNDSZ", quotaMap.get("urls.0.url"));
        assertEquals("https://youtu.be/dQw4w9WgXcQ", quotaMap.get("urls.1.url"));
        assertNull(quotaMap.get("urls.2.url"));
        assertTrue(quotaMap.get("edit_note").contains("https://uta-net.com/song/400001"));
        assertTrue(quotaMap.get("edit_note").contains("https://music.amazon.co.jp/albums/B0HR9LNDSZ"));

        // 单曲歌词语言推断：器乐轨为 null，日文有作词轨为 jpn
        t2.setLyricists(List.of("tepe"));
        t2.setLyricistSource("ONLINE_VERIFIED");
        assertEquals("jpn", MusicBrainzSeedBuilder.inferTrackLyricsLanguage(t2));
        MbContributionItem.ContributionTrack instTrack = new MbContributionItem.ContributionTrack();
        instTrack.setTitle("きみキミ血ぅ毒 (Instrumental)");
        instTrack.setLyricists(List.of("tepe"));
        instTrack.setLyricistSource("ONLINE_VERIFIED");
        assertNull(MusicBrainzSeedBuilder.inferTrackLyricsLanguage(instTrack));

        // 非 JP 英文专辑仅 1/3 曲目含假名时不应误判整张专辑为 jpn/Jpan
        draft.setCountry("US");
        MbContributionItem.ContributionTrack t3 = new MbContributionItem.ContributionTrack();
        t3.setDiscNo(1);
        t3.setTrackNo(3);
        t3.setTitle("Drain Drain");
        t3.setArtist("HIMEHINA");
        t3.setDurationMs(162000L);
        t3.setLocalFilePath("/Music/HIMEHINA/VAMPY/03.flac");
        t3.setMatchedConfidence(1.0);
        draft.setTracks(List.of(t1, t2, t3));
        MusicBrainzSeedBuilder.SeedPayload usPayload = MusicBrainzSeedBuilder.build(item);
        Map<String, String> usMap = new LinkedHashMap<>();
        usPayload.fields().forEach(f -> usMap.put(f.name(), f.value()));
        assertNull(usMap.get("language"));
        assertNull(usMap.get("script"));
    }
}
