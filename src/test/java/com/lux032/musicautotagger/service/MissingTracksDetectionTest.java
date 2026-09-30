package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lux032.musicautotagger.config.MusicConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定专辑缺曲：一对一覆盖检查 + 在锁定版本中定位曲目。
 * 用例取自真实数据：アトック 单曲在 MusicBrainz 只收录了 1 首（缺 c/w《金魚草》），
 * 黑神话精选集本地 71 首、锁定版本 67 首。
 */
class MissingTracksDetectionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MusicBrainzClient client;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        client = new MusicBrainzClient(constructor.newInstance());
    }

    // ==================== 一对一覆盖检查 ====================

    @Test
    void identicalSequenceIsFullyCovered() {
        assertEquals(0, DurationSequenceService.countUnmatchedLocalTracks(
            List.of(284, 291, 237, 284, 290), List.of(284, 291, 237, 284, 290)));
    }

    @Test
    void singleMissingCouplingTrackIsDetected() {
        // 本地：アトック 251s + 金魚草 268s；MB 单曲只有 アトック
        assertEquals(1, DurationSequenceService.countUnmatchedLocalTracks(List.of(251, 268), List.of(251)));
    }

    @Test
    void equalLengthDuplicatesCannotShareOneReleaseTrack() {
        // 原唱与伴奏等长时，两首本地曲目不能都算到版本里唯一的那一首上（DTW 会允许，这里不行）
        assertEquals(1, DurationSequenceService.countUnmatchedLocalTracks(List.of(251, 251), List.of(251)));
    }

    @Test
    void localSubsetOfReleaseIsNotMissing() {
        // 只下载了专辑的一部分：本地更少不算缺曲
        assertEquals(0, DurationSequenceService.countUnmatchedLocalTracks(List.of(200, 240), List.of(180, 200, 240, 300)));
    }

    @Test
    void orderDoesNotMatter() {
        // 无曲号文件名按字母排序时顺序不可信
        assertEquals(0, DurationSequenceService.countUnmatchedLocalTracks(
            List.of(300, 180, 240), List.of(180, 240, 300)));
    }

    @Test
    void toleranceIsRespected() {
        assertEquals(0, DurationSequenceService.countUnmatchedLocalTracks(List.of(205), List.of(200)));
        assertEquals(1, DurationSequenceService.countUnmatchedLocalTracks(List.of(206), List.of(200)));
    }

    @Test
    void largerLocalCollectionReportsAllExtras() {
        List<Integer> release = new ArrayList<>();
        for (int i = 0; i < 67; i++) release.add(100 + i * 20);
        List<Integer> local = new ArrayList<>(release);
        Collections.addAll(local, 99999, 88888, 77777, 66666);
        assertEquals(4, DurationSequenceService.countUnmatchedLocalTracks(local, release));
    }

    @Test
    void releaseTracksWithoutDurationCanAbsorbUnmatchedLocalTracks() {
        // 版本 10 首、只有 9 首有时长：本地多出的 1 首可能就是那首缺时长的
        List<Integer> release = List.of(100, 120, 140, 160, 180, 200, 220, 240, 260);
        List<Integer> local = new ArrayList<>(release);
        local.add(999);
        assertEquals(1, DurationSequenceService.countUnmatchedLocalTracks(local, release));
        assertEquals(0, DurationSequenceService.countUnmatchedLocalTracks(local, release, 10));
        assertEquals(1, DurationSequenceService.countUnmatchedLocalTracks(local, release, 9));
    }

    @Test
    void plausibilityGate() {
        assertTrue(DurationSequenceService.isPlausibleMissingTracks(2, 1));    // アトック
        assertTrue(DurationSequenceService.isPlausibleMissingTracks(71, 4));   // 黑神话
        assertFalse(DurationSequenceService.isPlausibleMissingTracks(63, 58)); // 合集对单曲
        assertFalse(DurationSequenceService.isPlausibleMissingTracks(10, 0));
    }

    @Test
    void titleMatchUsesRecordingLengthWhenTrackLengthMissing() throws Exception {
        JsonNode release = mapper.readTree("{\"media\":[{\"position\":1,\"format\":\"CD\",\"tracks\":["
            + "{\"id\":\"t1\",\"position\":\"3\",\"title\":\"Song\","
            + "\"recording\":{\"id\":\"orig\",\"title\":\"Song\",\"length\":200000}}]}]}");
        MusicBrainzClient.TrackMatch match = client.matchTrackInReleaseNode(release, null, "rg",
            "remaster", "Song", 203, "Album", "Artist");
        assertEquals(MusicBrainzClient.TrackMatchType.TITLE_AND_DURATION, match.getType());
        assertEquals("3", match.getMetadata().getTrackNo());
    }

    @Test
    void missingDataIsNotJudged() {
        assertEquals(0, DurationSequenceService.countUnmatchedLocalTracks(List.of(), List.of(200)));
        assertEquals(0, DurationSequenceService.countUnmatchedLocalTracks(List.of(200), null));
    }

    // ==================== 在锁定版本中定位曲目 ====================

    private JsonNode atokkuSingle() throws Exception {
        return mapper.readTree("{\"date\":\"2021-01-01\",\"media\":[{\"position\":1,\"format\":\"Digital Media\",\"tracks\":["
            + "{\"id\":\"t1\",\"position\":\"1\",\"title\":\"アトック\",\"length\":251000,"
            + "\"recording\":{\"id\":\"7baefef3\",\"title\":\"アトック\","
            + "\"artist-credit\":[{\"artist\":{\"id\":\"a1\",\"name\":\"藍井エイル\",\"sort-name\":\"Aoi, Eir\"}}]}}]}]}");
    }

    @Test
    void recordingInReleaseIsFoundById() throws Exception {
        MusicBrainzClient.TrackMatch match = client.matchTrackInReleaseNode(atokkuSingle(), null, "rg",
            "7baefef3", "アトック", 251, "アトック", "藍井エイル");
        assertEquals(MusicBrainzClient.TrackMatchType.RECORDING_ID, match.getType());
        assertEquals("1", match.getMetadata().getTrackNo());
    }

    @Test
    void differentSongWithSimilarDurationIsNotForcedIntoRelease() throws Exception {
        // 旧逻辑：金魚草 (250s) 与 アトック (251s) 时长差 1 秒 → 被改名成 アトック 并覆盖真文件
        MusicBrainzClient.TrackMatch match = client.matchTrackInReleaseNode(atokkuSingle(), null, "rg",
            "kingyoso-rec", "金魚草", 250, "アトック", "藍井エイル");
        assertEquals(MusicBrainzClient.TrackMatchType.NOT_FOUND, match.getType());
        assertNull(match.getMetadata());
        assertTrue(match.isNotInRelease());
    }

    @Test
    void sameTitleDifferentRecordingMatchesByTitleAndDuration() throws Exception {
        MusicBrainzClient.TrackMatch match = client.matchTrackInReleaseNode(atokkuSingle(), null, "rg",
            "other-master", "ｱﾄｯｸ", 254, "アトック", "藍井エイル");
        assertEquals(MusicBrainzClient.TrackMatchType.TITLE_AND_DURATION, match.getType());
    }

    @Test
    void sameTitleButFarDurationIsNotMatched() throws Exception {
        MusicBrainzClient.TrackMatch match = client.matchTrackInReleaseNode(atokkuSingle(), null, "rg",
            "live-rec", "アトック", 300, "アトック", "藍井エイル");
        assertEquals(MusicBrainzClient.TrackMatchType.NOT_FOUND, match.getType());
    }

    @Test
    void unknownIdentityFallsBackToDurationOnly() throws Exception {
        MusicBrainzClient.TrackMatch match = client.matchTrackInReleaseNode(atokkuSingle(), null, "rg",
            null, null, 252, "アトック", "藍井エイル");
        assertEquals(MusicBrainzClient.TrackMatchType.DURATION_ONLY, match.getType());
        MusicBrainzClient.TrackMatch far = client.matchTrackInReleaseNode(atokkuSingle(), null, "rg",
            null, null, 260, "アトック", "藍井エイル");
        assertEquals(MusicBrainzClient.TrackMatchType.NOT_FOUND, far.getType());
    }

    @Test
    void titleNormalizationIgnoresWidthCaseAndPunctuation() {
        assertEquals(MusicBrainzClient.normalizeTitleForMatch("This Game!"),
            MusicBrainzClient.normalizeTitleForMatch("ｔｈｉｓ　ｇａｍｅ"));
        assertNotEquals(MusicBrainzClient.normalizeTitleForMatch("This game"),
            MusicBrainzClient.normalizeTitleForMatch("This game (Instrumental)"));
    }
}
