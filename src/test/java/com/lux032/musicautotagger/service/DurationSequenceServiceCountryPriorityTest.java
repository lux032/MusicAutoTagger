package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DurationSequenceServiceCountryPriorityTest {

    private MusicConfig config;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        config = ctor.newInstance();
    }

    private static DurationSequenceService.AlbumDurationInfo candidate(
            String rgId, String releaseId, String title, String artist,
            List<Integer> durations, String format, String country, String date) {
        DurationSequenceService.AlbumDurationInfo info = new DurationSequenceService.AlbumDurationInfo(
            rgId, releaseId, title, artist, durations, format);
        info.setReleaseTrackCount(durations.size());
        info.setReleaseCountry(country);
        info.setReleaseDate(date);
        return info;
    }

    @Test
    void romanizedFolderNameStillSelectsPreferredCountryEditionWithinSameReleaseGroup() {
        config.setReleaseCountryPriority(List.of("JP"));
        DurationSequenceService service = new DurationSequenceService(config);

        List<Integer> folderDurations = List.of(240, 215, 198, 260);
        var usEdition = candidate("rg-fiction", "us-rel", "Fiction", "Yuki Kajiura",
            folderDurations, "CD", "US", "2003-08-06");
        var jpEdition = candidate("rg-fiction", "jp-rel", "フィクション", "梶浦由記",
            folderDurations, "Digital Media", "JP", "2003-08-06");

        var match = service.selectBestMatchWithFolderName(
            folderDurations, List.of(usEdition, jpEdition), "Yuki Kajiura - Fiction");
        assertNotNull(match);
        assertEquals("jp-rel", match.getAlbumInfo().getReleaseId());
        assertEquals("JP", match.getAlbumInfo().getReleaseCountry());

        // Plain selectBestMatch also refines within the same Release Group
        var plainMatch = service.selectBestMatch(folderDurations, List.of(usEdition, jpEdition));
        assertNotNull(plainMatch);
        assertEquals("jp-rel", plainMatch.getAlbumInfo().getReleaseId());
    }

    @Test
    void worseDurationOrExtraTrackEditionInPreferredCountryDoesNotOverrideExactMatch() {
        config.setReleaseCountryPriority(List.of("JP"));
        DurationSequenceService service = new DurationSequenceService(config);

        List<Integer> folderDurations = List.of(240, 215, 198, 260);
        var usExact = candidate("rg-1", "us-exact", "Album", "Artist",
            folderDurations, "CD", "US", "2020-01-01");
        var jpBonus = candidate("rg-1", "jp-bonus", "アルバム", "アーティスト",
            List.of(240, 215, 198, 260, 230, 245), "CD", "JP", "2020-01-01");

        var match = service.selectBestMatchWithFolderName(
            folderDurations, List.of(usExact, jpBonus), "Artist - Album");
        assertNotNull(match);
        assertEquals("us-exact", match.getAlbumInfo().getReleaseId());
    }

    @Test
    void countryPriorityDoesNotOverrideDifferentBetterMatchingReleaseGroupOrDistortMargin() {
        config.setReleaseCountryPriority(List.of("JP"));
        DurationSequenceService service = new DurationSequenceService(config);

        List<Integer> folderDurations = List.of(240, 215, 198, 260);
        var rightAlbumUs = candidate("rg-right", "us-right", "Right Album", "Artist",
            folderDurations, "CD", "US", "2020-01-01");
        var wrongAlbumJp = candidate("rg-wrong", "jp-wrong", "Wrong Album", "Other",
            List.of(240, 215, 180, 220), "CD", "JP", "2020-01-01");

        var match = service.selectBestMatchWithFolderName(
            folderDurations, List.of(wrongAlbumJp, rightAlbumUs), "Right Album");
        assertNotNull(match);
        assertEquals("rg-right", match.getAlbumInfo().getReleaseGroupId());
        assertEquals("us-right", match.getAlbumInfo().getReleaseId());

        // Ambiguous different Release Groups still rejected regardless of JP on one side
        var rivalA = candidate("rg-a", "us-a", "Album", "Artist",
            folderDurations, "CD", "US", "2020-01-01");
        var rivalB = candidate("rg-b", "jp-b", "Album", "Artist",
            folderDurations, "CD", "JP", "2020-01-01");
        assertNull(service.selectBestMatchWithFolderName(folderDurations, List.of(rivalA, rivalB), "Album"));
    }

    @Test
    void samePreferredCountryBreaksTiesByFormatAndEarlierDateAndEmptyConfigKeepsLegacyBehavior() {
        List<Integer> folderDurations = List.of(240, 215, 198, 260);
        var jpDig = candidate("rg-1", "jp-dig", "Album", "Artist",
            folderDurations, "Digital Media", "JP", "2020-05-01");
        var jpCdLate = candidate("rg-1", "jp-cd-late", "Album", "Artist",
            folderDurations, "CD", "JP", "2020-05-10");
        var jpCdEarly = candidate("rg-1", "jp-cd-early", "Album", "Artist",
            folderDurations, "CD", "JP", "2020-04-20");

        config.setReleaseCountryPriority(List.of("JP"));
        DurationSequenceService service = new DurationSequenceService(config);
        var match = service.selectBestMatchWithFolderName(
            folderDurations, List.of(jpDig, jpCdLate, jpCdEarly), "Album");
        assertNotNull(match);
        assertEquals("jp-cd-early", match.getAlbumInfo().getReleaseId());

        // Empty country priority leaves Stage 1 untouched (US title match wins over JP non-matching title)
        config.setReleaseCountryPriority(List.of());
        var usNamed = candidate("rg-1", "us-named", "Fiction", "Yuki Kajiura",
            folderDurations, "CD", "US", "2003-08-06");
        var jpNamed = candidate("rg-1", "jp-named", "フィクション", "梶浦由記",
            folderDurations, "CD", "JP", "2003-08-01");
        var legacyMatch = service.selectBestMatchWithFolderName(
            folderDurations, List.of(usNamed, jpNamed), "Fiction");
        assertNotNull(legacyMatch);
        assertEquals("us-named", legacyMatch.getAlbumInfo().getReleaseId());
    }

    @Test
    void independentlyEnforcesDtwTieEpsilonTrackCountClosenessAndVideoExclusion() {
        config.setReleaseCountryPriority(List.of("JP"));
        DurationSequenceService service = new DurationSequenceService(config);

        List<Integer> folderDurations = List.of(240, 215, 198, 260);

        // 1. Same track count (4 vs 4), but JP DTW differs materially (> 0.01) -> US wins
        var usExactDtw = candidate("rg-dtw", "us-dtw", "Album", "Artist",
            folderDurations, "CD", "US", "2020-01-01");
        var jpWorseDtw = candidate("rg-dtw", "jp-dtw", "Album", "Artist",
            List.of(240, 215, 198, 200), "CD", "JP", "2020-01-01");
        var dtwMatch = service.selectBestMatchWithFolderName(
            folderDurations, List.of(usExactDtw, jpWorseDtw), "Album");
        assertNotNull(dtwMatch);
        assertEquals("us-dtw", dtwMatch.getAlbumInfo().getReleaseId());

        // 2. Identical known durations (DTW = 1.00 for both), but JP has more real tracks (releaseTrackCount=6 vs 4) -> US wins
        var usTrack4 = candidate("rg-cnt", "us-cnt", "Album", "Artist",
            folderDurations, "CD", "US", "2020-01-01");
        var jpTrack6 = candidate("rg-cnt", "jp-cnt", "Album", "Artist",
            folderDurations, "CD", "JP", "2020-01-01");
        jpTrack6.setReleaseTrackCount(6);
        var countMatch = service.selectBestMatchWithFolderName(
            folderDurations, List.of(usTrack4, jpTrack6), "Album");
        assertNotNull(countMatch);
        assertEquals("us-cnt", countMatch.getAlbumInfo().getReleaseId());

        // 3. Video-only JP edition must never win Stage 1 OR Stage 2 even when listed first
        var usCd = candidate("rg-vid", "us-cd", "Album", "Artist",
            folderDurations, "CD", "US", "2020-01-01");
        var jpDvd = candidate("rg-vid", "jp-dvd", "Album", "Artist",
            folderDurations, "DVD-Video", "JP", "2020-01-01");
        var videoMatch = service.selectBestMatchWithFolderName(
            folderDurations, List.of(jpDvd, usCd), "Album");
        assertNotNull(videoMatch);
        assertEquals("us-cd", videoMatch.getAlbumInfo().getReleaseId());

        var plainVideoMatch = service.selectBestMatch(folderDurations, List.of(jpDvd, usCd));
        assertNotNull(plainVideoMatch);
        assertEquals("us-cd", plainVideoMatch.getAlbumInfo().getReleaseId());
    }
}
