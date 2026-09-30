package com.lux032.musicautotagger.service;

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
        track.setMatchedFilePath("/music/a/01.flac");
        track.setMatchConfidence(0.95);
        ReviewItem.OnlineCandidate candidate = new ReviewItem.OnlineCandidate();
        candidate.setTitle("アトック");
        candidate.setTrackCoverage(1.0);
        candidate.getTracks().add(track);
        List<ReviewItem.OnlineCandidate> parsed = new ArrayList<>(List.of(candidate));

        List<ReviewItem.OnlineCandidate> published =
            OnlineIdentificationService.deepCopy(parsed, ReviewItem.OnlineCandidate[].class);

        // 模拟第二轮 matchAll：先清空再重填逐曲匹配
        track.setMatchedFilePath(null);
        track.setMatchConfidence(0);
        candidate.setTrackCoverage(0);
        parsed.add(new ReviewItem.OnlineCandidate());

        assertEquals(1, published.size());
        assertNotSame(candidate, published.get(0));
        assertEquals(1.0, published.get(0).getTrackCoverage());
        assertEquals("/music/a/01.flac", published.get(0).getTracks().get(0).getMatchedFilePath());
        assertEquals(0.95, published.get(0).getTracks().get(0).getMatchConfidence());
    }

    @Test
    void nullSourceGivesEmptyList() {
        assertTrue(OnlineIdentificationService.deepCopy(null, ReviewItem.OnlineCandidate[].class).isEmpty());
    }
}
