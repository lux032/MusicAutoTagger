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
        track.setMatchedFilePath("/music/a/01.flac");
        track.setMatchConfidence(0.95);
        ReviewItem.OnlineCandidate candidate = new ReviewItem.OnlineCandidate();
        candidate.setTitle("アトック");
        candidate.setTrackCoverage(1.0);
        candidate.getTracks().add(track);
        List<ReviewItem.OnlineCandidate> parsed = new ArrayList<>(List.of(candidate));

        List<ReviewItem.OnlineCandidate> published =
            OnlineIdentificationService.deepCopy(parsed, ReviewItem.OnlineCandidate[].class);

        // 模拟第二轮 matchAll：先清空再重填逐曲匹配，以及篡改原曲目幕后人员列表
        track.setMatchedFilePath(null);
        track.setMatchConfidence(0);
        track.getComposers().clear();
        track.getLyricists().add("Other");
        track.getArrangers().clear();
        candidate.setTrackCoverage(0);
        parsed.add(new ReviewItem.OnlineCandidate());

        assertEquals(1, published.size());
        assertNotSame(candidate, published.get(0));
        assertEquals(1.0, published.get(0).getTrackCoverage());
        ReviewItem.OnlineTrack publishedTrack = published.get(0).getTracks().get(0);
        assertEquals("/music/a/01.flac", publishedTrack.getMatchedFilePath());
        assertEquals(0.95, publishedTrack.getMatchConfidence());
        assertEquals(List.of("梶浦由記"), publishedTrack.getComposers());
        assertEquals(List.of("梶浦由記"), publishedTrack.getLyricists());
        assertEquals(List.of("梶浦由記", "森空青"), publishedTrack.getArrangers());
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
        assertNotNull(legacy.getMissingCover().getOnlineTrackCredits());
        assertTrue(legacy.getMissingCover().getOnlineTrackCredits().isEmpty());

        // 三态语义：null = 未改，[] = 显式清空，非空 = 覆盖
        ReviewItem.TrackCreditsOverride override = new ReviewItem.TrackCreditsOverride();
        override.setComposers(List.of("澤野弘之"));
        override.setLyricists(List.of());
        override.setArrangers(null);
        legacy.getMissingCover().getOnlineTrackCredits().put("/music/01.flac", override);

        ReviewItem roundTripped = gson.fromJson(gson.toJson(legacy), ReviewItem.class);
        ReviewItem.TrackCreditsOverride restored =
            roundTripped.getMissingCover().getOnlineTrackCredits().get("/music/01.flac");
        assertNotNull(restored);
        assertEquals(List.of("澤野弘之"), restored.getComposers());
        assertNotNull(restored.getLyricists());
        assertTrue(restored.getLyricists().isEmpty());
        assertNull(restored.getArrangers());
    }

    @Test
    void nullSourceGivesEmptyList() {
        assertTrue(OnlineIdentificationService.deepCopy(null, ReviewItem.OnlineCandidate[].class).isEmpty());
    }
}
