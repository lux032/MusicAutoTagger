package com.lux032.musicautotagger.service;

import com.google.gson.Gson;
import com.lux032.musicautotagger.model.ReviewItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第一轮联网搜索结果挂到待确认条目上后，第二轮 merge / matchAll 会原地修改 parsed 里的候选；
 * 挂上去的必须是深拷贝，锁外修改不能影响条目里的逐曲匹配。
 */
class OnlineCandidateDeepCopyTest {

    @Test
    void publishedCandidatesAreIsolatedFromLaterRematching() {
        ReviewItem.OnlineTrack track = new ReviewItem.OnlineTrack();
        track.setTrackNo(1);
        track.setTitle("アトック");
        track.setComposers(new ArrayList<>(List.of("梶浦由記")));
        track.setLyricists(new ArrayList<>(List.of("梶浦由記")));
        track.setArrangers(new ArrayList<>(List.of("梶浦由記", "森空青")));
        ReviewItem.TrackCreditDetail detail = new ReviewItem.TrackCreditDetail();
        detail.setVersionType("original");
        ReviewItem.CreditFieldResult arrField = new ReviewItem.CreditFieldResult();
        arrField.setStatus(ReviewItem.CreditStatus.VERIFIED);
        arrField.setNames(new ArrayList<>(List.of("梶浦由記", "森空青")));
        ReviewItem.CreditEvidenceItem ev = new ReviewItem.CreditEvidenceItem();
        ev.setUrl("https://vgmdb.net/album/12345");
        ev.setSourceType("music_database");
        ev.setQuote("Arrangement: 梶浦由記, 森空青");
        ev.setVersionMatch("exact_version");
        ev.setFullPageVerified(true);
        arrField.setEvidence(new ArrayList<>(List.of(ev)));
        detail.setArrangers(arrField);
        track.setCreditDetail(detail);
        track.setMatchedFilePath("/music/a/01.flac");
        track.setMatchConfidence(0.95);
        ReviewItem.OnlineCandidate candidate = new ReviewItem.OnlineCandidate();
        candidate.setTitle("アトック");
        candidate.setTrackCoverage(1.0);
        candidate.getTracks().add(track);
        ReviewItem.EntityMbidResolution entityRes = new ReviewItem.EntityMbidResolution();
        entityRes.setNormalizedKey("梶浦由記");
        entityRes.setDisplayName("梶浦由記");
        entityRes.setRoles(new ArrayList<>(List.of("composer", "arranger")));
        entityRes.setStatus(ReviewItem.EntityMbidStatus.RESOLVED);
        entityRes.setSelectedMbid("11111111-1111-4111-8111-111111111111");
        entityRes.setSource(ReviewItem.EntityMbidSource.LOCAL_CACHE);
        ReviewItem.EntityMbidCandidate mbCand = new ReviewItem.EntityMbidCandidate();
        mbCand.setMbid("11111111-1111-4111-8111-111111111111");
        mbCand.setName("梶浦由記");
        entityRes.setCandidates(new ArrayList<>(List.of(mbCand)));
        candidate.getEntityMbids().put("梶浦由記", entityRes);
        List<ReviewItem.OnlineCandidate> parsed = new ArrayList<>(List.of(candidate));

        List<ReviewItem.OnlineCandidate> published =
            OnlineIdentificationService.deepCopy(parsed, ReviewItem.OnlineCandidate[].class);

        // 模拟第二轮 matchAll：先清空再重填逐曲匹配，以及篡改原曲目幕后人员列表、证据明细与人物 MBID 表
        track.setMatchedFilePath(null);
        track.setMatchConfidence(0);
        track.getComposers().clear();
        track.getLyricists().add("Other");
        track.getArrangers().clear();
        track.getCreditDetail().getArrangers().getNames().clear();
        track.getCreditDetail().getArrangers().getEvidence().clear();
        candidate.getEntityMbids().get("梶浦由記").getRoles().clear();
        candidate.getEntityMbids().clear();
        candidate.setTrackCoverage(0);
        parsed.add(new ReviewItem.OnlineCandidate());

        assertEquals(1, published.size());
        assertNotSame(candidate, published.get(0));
        assertEquals(1.0, published.get(0).getTrackCoverage());
        ReviewItem.EntityMbidResolution publishedEntity = published.get(0).getEntityMbids().get("梶浦由記");
        assertNotNull(publishedEntity);
        assertEquals("11111111-1111-4111-8111-111111111111", publishedEntity.getSelectedMbid());
        assertEquals(List.of("composer", "arranger"), publishedEntity.getRoles());
        assertEquals(1, publishedEntity.getCandidates().size());
        ReviewItem.OnlineTrack publishedTrack = published.get(0).getTracks().get(0);
        assertEquals("/music/a/01.flac", publishedTrack.getMatchedFilePath());
        assertEquals(0.95, publishedTrack.getMatchConfidence());
        assertEquals(List.of("梶浦由記"), publishedTrack.getComposers());
        assertEquals(List.of("梶浦由記"), publishedTrack.getLyricists());
        assertEquals(List.of("梶浦由記", "森空青"), publishedTrack.getArrangers());
        assertNotNull(publishedTrack.getCreditDetail());
        assertEquals(ReviewItem.CreditStatus.VERIFIED, publishedTrack.getCreditDetail().getArrangers().getStatus());
        assertEquals(List.of("梶浦由記", "森空青"), publishedTrack.getCreditDetail().getArrangers().getNames());
        assertEquals(1, publishedTrack.getCreditDetail().getArrangers().getEvidence().size());
        assertEquals("https://vgmdb.net/album/12345",
            publishedTrack.getCreditDetail().getArrangers().getEvidence().get(0).getUrl());
    }

    @Test
    void legacyReviewItemJsonDeserializesSafelyAndPreservesTrackCreditsOverrideTriState() {
        Gson gson = new Gson();
        String legacyJson = """
            {
              "id": "item-1",
              "onlineCandidates": [
                {
                  "id": "cand-1",
                  "title": "Legacy Album",
                  "tracks": [
                    {"discNo": 1, "trackNo": 1, "title": "Song A", "artist": "Artist A"}
                  ]
                }
              ],
              "missingCover": {
                "mode": "STAGED_IMPORT",
                "albumTitle": "Legacy Album",
                "onlineCandidateId": "cand-1"
              }
            }
            """;

        ReviewItem legacy = gson.fromJson(legacyJson, ReviewItem.class);
        ReviewItem.OnlineTrack legacyTrack = legacy.getOnlineCandidates().get(0).getTracks().get(0);
        assertNotNull(legacyTrack.getComposers());
        assertTrue(legacyTrack.getComposers().isEmpty());
        assertNotNull(legacyTrack.getLyricists());
        assertTrue(legacyTrack.getLyricists().isEmpty());
        assertNotNull(legacyTrack.getArrangers());
        assertTrue(legacyTrack.getArrangers().isEmpty());
        assertNull(legacyTrack.getCreditDetail());
        legacy.ensureThreadSafeCollections();
        assertNotNull(legacy.getOnlineCandidates().get(0).getEntityMbids());
        assertTrue(legacy.getOnlineCandidates().get(0).getEntityMbids().isEmpty());
        assertNotNull(legacy.getMissingCover().getOnlineTrackCredits());
        assertTrue(legacy.getMissingCover().getOnlineTrackCredits().isEmpty());
        assertNotNull(legacy.getMissingCover().getOnlineEntityMbidOverrides());
        assertTrue(legacy.getMissingCover().getOnlineEntityMbidOverrides().isEmpty());

        // 三态语义：null = 未改，[] = 显式清空，非空 = 覆盖；MBID 覆盖支持 UUID 与 "" 显式取消绑定
        ReviewItem.TrackCreditsOverride override = new ReviewItem.TrackCreditsOverride();
        override.setComposers(List.of("澤野弘之"));
        override.setLyricists(List.of());
        override.setArrangers(null);
        legacy.getMissingCover().getOnlineTrackCredits().put("/music/01.flac", override);
        legacy.getMissingCover().getOnlineEntityMbidOverrides().put("澤野弘之", "11111111-1111-4111-8111-111111111111");
        legacy.getMissingCover().getOnlineEntityMbidOverrides().put("unwanted", "");

        ReviewItem roundTripped = gson.fromJson(gson.toJson(legacy), ReviewItem.class);
        roundTripped.ensureThreadSafeCollections();
        ReviewItem.TrackCreditsOverride restored =
            roundTripped.getMissingCover().getOnlineTrackCredits().get("/music/01.flac");
        assertNotNull(restored);
        assertEquals(List.of("澤野弘之"), restored.getComposers());
        assertNotNull(restored.getLyricists());
        assertTrue(restored.getLyricists().isEmpty());
        assertNull(restored.getArrangers());
        assertEquals("11111111-1111-4111-8111-111111111111",
            roundTripped.getMissingCover().getOnlineEntityMbidOverrides().get("澤野弘之"));
        assertEquals("", roundTripped.getMissingCover().getOnlineEntityMbidOverrides().get("unwanted"));

        com.lux032.musicautotagger.model.MusicMetadata md = new com.lux032.musicautotagger.model.MusicMetadata();
        md.setClearArtistId(true);
        md.setClearAlbumArtistId(true);
        com.lux032.musicautotagger.model.MusicMetadata copied = md.copyWithoutHeavyFields();
        assertTrue(copied.isClearArtistId());
        assertTrue(copied.isClearAlbumArtistId());
    }

    @Test
    void nullSourceGivesEmptyList() {
        assertTrue(OnlineIdentificationService.deepCopy(null, ReviewItem.OnlineCandidate[].class).isEmpty());
    }
}
