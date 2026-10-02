package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.model.ReviewItem;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OnlineTrackMatcherTest {
    private final OnlineTrackMatcher matcher = new OnlineTrackMatcher(null);

    @Test
    void exactTitlesAndSlightDurationDifferencesMatchWithoutTrackNumbers() {
        var candidate = candidate(
            track("風の声を聴きながら -TV size-", 91),
            track("#最高の片想い", 234),
            track("コラージュ", 269),
            track("風の声を聴きながら", 242));
        var files = List.of(
            new File("三月のパンタシア - 風の声を聴きながら -TV size-.flac"),
            new File("三月のパンタシア - #最高の片想い.flac"),
            new File("三月のパンタシア - コラージュ.flac"),
            new File("三月のパンタシア - 風の声を聴きながら.flac"));
        assertEquals(4, matcher.match(candidate, files, List.of(92, 233, 270, 241)));
        assertEquals(1.0, candidate.getTrackCoverage());
        for (int i = 0; i < files.size(); i++) {
            assertEquals(files.get(i).getAbsolutePath(), candidate.getTracks().get(i).getMatchedFilePath());
        }
    }

    @Test
    void trackCannotBeAssignedTwiceWhenItsMutableHashCodeChanges() {
        var candidate = candidate(track("Song", 100));
        assertEquals(1, matcher.match(candidate,
            List.of(new File("Artist A - Song.flac"), new File("Artist B - Song.flac")),
            List.of(100, 100)));
        assertEquals(0.5, candidate.getTrackCoverage());
    }

    @Test
    void fullWidthLettersAreNormalizedRatherThanDeleted() {
        var candidate = candidate(track("Ｓｏｎｇ", 100));
        assertEquals(1, matcher.match(candidate, List.of(new File("Artist - Song.flac")), List.of(101)));
    }

    @Test
    void titleWithoutDurationRemainsInsufficient() {
        var candidate = candidate(track("Song", null));
        assertEquals(0, matcher.match(candidate, List.of(new File("Artist - Song.flac")), null));
    }

    @Test
    void standardVersionDoesNotReliablyMatchTvSizeEvenWithSameDuration() {
        var candidate = candidate(track("風の声を聴きながら", 91));
        assertEquals(0, matcher.match(candidate,
            List.of(new File("三月のパンタシア - 風の声を聴きながら -TV size-.flac")), List.of(91)));
    }

    @Test
    void exactTitleWithConflictingDurationIsRejected() {
        var candidate = candidate(track("Song", 240));
        assertEquals(0, matcher.match(candidate, List.of(new File("Artist - Song.flac")), List.of(91)));
    }

    private ReviewItem.OnlineTrack track(String title, Integer duration) {
        var track = new ReviewItem.OnlineTrack();
        track.setTitle(title);
        track.setDuration(duration);
        return track;
    }

    private ReviewItem.OnlineCandidate candidate(ReviewItem.OnlineTrack... tracks) {
        var candidate = new ReviewItem.OnlineCandidate();
        candidate.setTracks(List.of(tracks));
        return candidate;
    }
}
