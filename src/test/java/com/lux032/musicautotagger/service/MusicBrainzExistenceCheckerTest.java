package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MbContributionItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MusicBrainzExistenceCheckerTest {

    private static MusicConfig newConfig() throws Exception {
        java.lang.reflect.Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    @Test
    void classifiesAllFourCasesAccurately() throws Exception {
        MusicConfig config = newConfig();

        // Case D: 非官方或缺少可靠来源
        MusicBrainzExistenceChecker noopChecker = new MusicBrainzExistenceChecker(new MusicBrainzClient(config));
        MbContributionItem.ReleaseDraft unofficial = sampleDraft("VAMPY", "HIMEHINA", List.of(195_000L, 210_000L));
        unofficial.setOfficialCandidate(false);
        assertEquals(MbContributionItem.CheckCase.NOT_ELIGIBLE, noopChecker.check(unofficial, true).checkCase());

        // Case A: Barcode 命中且曲目数+时长一致
        MusicBrainzClient barcodeClient = new MusicBrainzClient(config) {
            @Override
            public List<ReleaseSearchHit> searchReleasesByBarcode(String rawBarcode) {
                return List.of(new ReleaseSearchHit(
                    "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222",
                    "VAMPY", "HIMEHINA", "2026-11-27", "JP", "Official", "4580000000001", null, 2, null, 100));
            }
            @Override
            public List<Integer> getReleaseDurationSequence(String releaseId) {
                return List.of(195, 211);
            }
        };
        MbContributionItem.ReleaseDraft withBarcode = sampleDraft("VAMPY", "HIMEHINA", List.of(195_000L, 210_000L));
        withBarcode.setBarcode("4580000000001");
        MusicBrainzExistenceChecker.CheckResult caseA = new MusicBrainzExistenceChecker(barcodeClient).check(withBarcode, true);
        assertEquals(MbContributionItem.CheckCase.EXISTING_RELEASE, caseA.checkCase());
        assertEquals("11111111-1111-4111-8111-111111111111", caseA.matchedReleaseId());

        // Case B: RG 存在，但现有 Release 是 2 轨普通版，而本地是 4 轨日本数字版
        MusicBrainzClient rgClient = new MusicBrainzClient(config) {
            @Override
            public List<ReleaseSearchHit> searchReleasesByBarcode(String rawBarcode) { return List.of(); }
            @Override
            public List<ReleaseSearchHit> searchReleasesByCatalogNumber(String catno, String artist) { return List.of(); }
            @Override
            public List<ReleaseGroupSearchHit> searchReleaseGroups(String title, String artistName, String artistMbid, int limit) {
                return List.of(new ReleaseGroupSearchHit(
                    "22222222-2222-4222-8222-222222222222", "VAMPY", "HIMEHINA",
                    List.of("c2deade3-c5e4-4061-8c13-9bd8f53a143b"), "ep", List.of(), "2026-11-27", 100));
            }
            @Override
            public List<AlbumDurationResult> getReleasesForGroup(String releaseGroupId) {
                return List.of(new AlbumDurationResult(
                    List.of(195, 210), "33333333-3333-4333-8333-333333333333", "VAMPY", 2,
                    "CD", "ep", false, "HIMEHINA", "US", "2026-11-27"));
            }
        };
        MbContributionItem.ReleaseDraft fourTrack = sampleDraft("VAMPY", "HIMEHINA", List.of(195_000L, 210_000L, 180_000L, 200_000L));
        fourTrack.setCountry("JP");
        fourTrack.setMediaFormat("Digital Media");
        MusicBrainzExistenceChecker.CheckResult caseB = new MusicBrainzExistenceChecker(rgClient).check(fourTrack, true);
        assertEquals(MbContributionItem.CheckCase.MISSING_EDITION, caseB.checkCase());
        assertEquals("22222222-2222-4222-8222-222222222222", caseB.matchedReleaseGroupId());
        assertNull(caseB.matchedReleaseId());
        assertEquals(1, caseB.existingGroupReleases().size());

        // Case C: MB 中完全查无此专辑
        MusicBrainzClient emptyClient = new MusicBrainzClient(config) {
            @Override
            public List<ReleaseSearchHit> searchReleasesByBarcode(String rawBarcode) { return List.of(); }
            @Override
            public List<ReleaseSearchHit> searchReleasesByCatalogNumber(String catno, String artist) { return List.of(); }
            @Override
            public List<ReleaseGroupSearchHit> searchReleaseGroups(String title, String artistName, String artistMbid, int limit) {
                return List.of();
            }
        };
        MusicBrainzExistenceChecker.CheckResult caseC = new MusicBrainzExistenceChecker(emptyClient).check(fourTrack, true);
        assertEquals(MbContributionItem.CheckCase.MISSING_RELEASE_GROUP, caseC.checkCase());
        assertNull(caseC.matchedReleaseGroupId());
    }

    private static MbContributionItem.ReleaseDraft sampleDraft(String title, String artist, List<Long> durationsMs) {
        MbContributionItem.ReleaseDraft draft = new MbContributionItem.ReleaseDraft();
        draft.setTitle(title);
        draft.setAlbumArtist(artist);
        draft.setAlbumArtists(List.of(artist));
        draft.setOfficialCandidate(true);
        draft.setTrackCoverage(1.0);
        MbContributionItem.SourceUrlEntry src = new MbContributionItem.SourceUrlEntry();
        src.setUrl("https://music.apple.com/jp/album/vampy/123456");
        src.setDomain("music.apple.com");
        src.setReliability("HIGH");
        draft.setSourceUrls(List.of(src));
        List<MbContributionItem.ContributionTrack> tracks = new java.util.ArrayList<>();
        for (int i = 0; i < durationsMs.size(); i++) {
            MbContributionItem.ContributionTrack t = new MbContributionItem.ContributionTrack();
            t.setDiscNo(1);
            t.setTrackNo(i + 1);
            t.setTitle("Track " + (i + 1));
            t.setDurationMs(durationsMs.get(i));
            t.setLocalFilePath("/Music/" + artist + "/" + title + "/1.0" + (i + 1) + ".flac");
            t.setMatchedConfidence(1.0);
            tracks.add(t);
        }
        draft.setTracks(tracks);
        return draft;
    }
}
