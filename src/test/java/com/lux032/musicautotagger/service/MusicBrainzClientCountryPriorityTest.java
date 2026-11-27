package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MusicBrainzClientCountryPriorityTest {

    private MusicConfig config;
    private Map<String, String> responses;
    private StubMusicBrainzClient client;

    private static final class StubMusicBrainzClient extends MusicBrainzClient {
        private final Map<String, String> stubs;

        StubMusicBrainzClient(MusicConfig config, Map<String, String> stubs) {
            super(config);
            this.stubs = stubs;
        }

        @Override
        synchronized void rateLimit() {
            // Skip 1s rate limit in offline unit tests
        }

        @Override
        String executeRequest(String url) {
            for (Map.Entry<String, String> entry : stubs.entrySet()) {
                if (url.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            throw new IllegalStateException("Unexpected MusicBrainz URL in test: " + url);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        config = ctor.newInstance();
        responses = new HashMap<>();
        client = new StubMusicBrainzClient(config, responses);
    }

    private static String releaseWithTracks(String id, String title, String country, String format, String date,
                                            int trackCount, String creditName, String artistName) {
        StringBuilder tracks = new StringBuilder();
        for (int i = 1; i <= trackCount; i++) {
            if (i > 1) tracks.append(',');
            tracks.append("""
                {"id":"trk-%s-%d","position":%d,"length":200000,"title":"Track %d",
                 "recording":{"id":"rec-%s-%d","title":"Track %d","length":200000,
                   "artist-credit":[{"name":"%s","artist":{"id":"artist-1","name":"%s","sort-name":"Artist"}}]}}
                """.formatted(id, i, i, i, id, i, i, creditName, artistName));
        }
        return """
            {
              "id":"%s","title":"%s","country":"%s","date":"%s","status":"Official",
              "artist-credit":[{"name":"%s","artist":{"id":"artist-1","name":"%s","sort-name":"Artist"}}],
              "media":[{"position":1,"format":"%s","track-count":%d,"tracks":[%s]}]
            }
            """.formatted(id, title, country, date, creditName, artistName, format, trackCount, tracks);
    }

    @Test
    void fetchAlbumDurationSequenceAndCacheRespectRuntimeCountryPriorityUpdates() throws Exception {
        responses.put("/release-group/rg-1?", """
            {
              "id":"rg-1","primary-type":"Album",
              "releases":[
                {"id":"jp-dvd","title":"DVD Edition","country":"JP","date":"2020-01-01","media":[{"format":"DVD-Video","track-count":2}]},
                {"id":"us-cd","title":"US Edition","country":"US","date":"2020-02-01","media":[{"format":"CD","track-count":2}]},
                {"id":"jp-dig","title":"JP Digital","country":"JP","date":"2020-03-01","media":[{"format":"Digital Media","track-count":2}]}
              ]
            }
            """);
        responses.put("/release/us-cd?", releaseWithTracks("us-cd", "US Edition", "US", "CD", "2020-02-01", 2, "Yuki Kajiura", "梶浦由記"));
        responses.put("/release/jp-dig?", releaseWithTracks("jp-dig", "JP Digital", "JP", "Digital Media", "2020-03-01", 2, "Yuki Kajiura", "梶浦由記"));

        // Empty priority -> CD beats Digital
        config.setReleaseCountryPriority(List.of());
        MusicBrainzClient.AlbumDurationResult defaultResult = client.getAlbumDurationSequence("rg-1");
        assertEquals("us-cd", defaultResult.getReleaseId());
        assertEquals("US", defaultResult.getReleaseCountry());
        assertEquals("2020-02-01", defaultResult.getReleaseDate());

        // Hot-update priority to JP -> cache key isolates previous US selection; excludes JP DVD and picks JP Digital
        config.setReleaseCountryPriority(List.of("JP"));
        MusicBrainzClient.AlbumDurationResult jpResult = client.getAlbumDurationSequence("rg-1");
        assertEquals("jp-dig", jpResult.getReleaseId());
        assertEquals("JP", jpResult.getReleaseCountry());
        assertEquals("2020-03-01", jpResult.getReleaseDate());
    }

    @Test
    void fetchAllReleaseDurationSequencesPrioritizesJpBeforeCapAndIsolatesArtistCache() throws Exception {
        StringBuilder relJson = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            String id = "us-cd-" + i;
            if (i > 1) relJson.append(',');
            relJson.append("""
                {"id":"%s","title":"US %d","country":"US","date":"2020-01-0%d",
                 "artist-credit":[{"name":"Yuki Kajiura","artist":{"id":"artist-1","name":"梶浦由記"}}],
                 "media":[{"format":"CD","track-count":2}]}
                """.formatted(id, i, i));
            responses.put("/release/" + id + "?", releaseWithTracks(id, "US " + i, "US", "CD", "2020-01-0" + i, 2, "Yuki Kajiura", "梶浦由記"));
        }
        relJson.append("""
            ,{"id":"jp-dig","title":"フィクション","country":"JP","date":"2020-05-01",
              "artist-credit":[{"name":"Yuki Kajiura","artist":{"id":"artist-1","name":"梶浦由記"}}],
              "media":[{"format":"Digital Media","track-count":2}]}
            """);
        responses.put("/release/jp-dig?", releaseWithTracks("jp-dig", "フィクション", "JP", "Digital Media", "2020-05-01", 2, "Yuki Kajiura", "梶浦由記"));
        responses.put("/release-group/rg-many?", """
            {
              "id":"rg-many","primary-type":"Album",
              "artist-credit":[{"name":"Yuki Kajiura","artist":{"id":"artist-1","name":"梶浦由記"}}],
              "releases":[%s]
            }
            """.formatted(relJson));

        config.setReleaseCountryPriority(List.of("JP"));
        config.setPreferNativeArtistName(false);
        List<MusicBrainzClient.AlbumDurationResult> rawResults = client.getAllReleaseDurationSequences("rg-many");
        assertEquals(6, rawResults.size());
        assertEquals("jp-dig", rawResults.get(0).getReleaseId());
        assertEquals("JP", rawResults.get(0).getReleaseCountry());
        assertEquals("2020-05-01", rawResults.get(0).getReleaseDate());
        assertEquals("Yuki Kajiura", rawResults.get(0).getAlbumArtist());
        assertTrue(rawResults.stream().anyMatch(r -> "us-cd-1".equals(r.getReleaseId())));
        assertTrue(rawResults.stream().anyMatch(r -> "us-cd-5".equals(r.getReleaseId())));

        // Hot-reload preferNativeArtistName=true must bypass stale Romanized cache entry
        config.setPreferNativeArtistName(true);
        List<MusicBrainzClient.AlbumDurationResult> nativeResults = client.getAllReleaseDurationSequences("rg-many");
        assertEquals("jp-dig", nativeResults.get(0).getReleaseId());
        assertEquals("梶浦由記", nativeResults.get(0).getAlbumArtist());

        // getReleasesForGroup also places JP first and propagates country/date
        List<MusicBrainzClient.AlbumDurationResult> expanded = client.getReleasesForGroup("rg-many");
        assertEquals("jp-dig", expanded.get(0).getReleaseId());
        assertEquals("JP", expanded.get(0).getReleaseCountry());
        assertEquals("2020-05-01", expanded.get(0).getReleaseDate());
    }

    @Test
    void matchTrackInReleaseGroupHonorsTrackCountBeforeCountryPriority() throws Exception {
        responses.put("/release-group/rg-locked?", """
            {
              "id":"rg-locked","primary-type":"Album",
              "releases":[
                {"id":"us-12","title":"US Standard","country":"US","date":"2020-01-01","media":[{"format":"CD","track-count":12}]},
                {"id":"jp-15","title":"JP Deluxe","country":"JP","date":"2020-02-01","media":[{"format":"CD","track-count":15}]}
              ]
            }
            """);
        responses.put("/release/us-12?", releaseWithTracks("us-12", "US Standard", "US", "CD", "2020-01-01", 12, "Yuki Kajiura", "梶浦由記"));
        responses.put("/release/jp-15?", releaseWithTracks("jp-15", "JP Deluxe", "JP", "CD", "2020-02-01", 15, "Yuki Kajiura", "梶浦由記"));

        config.setReleaseCountryPriority(List.of("JP"));
        MusicBrainzClient.TrackMatch byCount = client.matchTrackInReleaseGroup(
            "rg-locked", "rec-us-12-1", "Track 1", 200, 12, true, "Album", "梶浦由記");
        assertTrue(byCount.isFound());
        assertEquals("us-12", byCount.getMetadata().getReleaseId());

        // When track counts are equal, JP Digital beats US CD
        responses.put("/release-group/rg-equal?", """
            {
              "id":"rg-equal","primary-type":"Album",
              "releases":[
                {"id":"us-eq","title":"US CD","country":"US","date":"2020-01-01","media":[{"format":"CD","track-count":4}]},
                {"id":"jp-eq","title":"JP Digital","country":"JP","date":"2020-02-01","media":[{"format":"Digital Media","track-count":4}]}
              ]
            }
            """);
        responses.put("/release/jp-eq?", releaseWithTracks("jp-eq", "JP Digital", "JP", "Digital Media", "2020-02-01", 4, "Yuki Kajiura", "梶浦由記"));
        MusicBrainzClient.TrackMatch byCountry = client.matchTrackInReleaseGroup(
            "rg-equal", "rec-jp-eq-1", "Track 1", 200, 4, true, "Album", "梶浦由記");
        assertTrue(byCountry.isFound());
        assertEquals("jp-eq", byCountry.getMetadata().getReleaseId());
    }

    @Test
    void recordingLookupRespectsCountryPriorityInStage1AndStage2WhileHonoringLockedReleaseId() throws Exception {
        responses.put("/recording/rec-1?", """
            {
              "id":"rec-1","title":"Song 1",
              "artist-credit":[{"name":"Yuki Kajiura","artist":{"id":"artist-1","name":"梶浦由記","sort-name":"Kajiura, Yuki"}}],
              "releases":[
                {
                  "id":"us-cd","title":"Fiction","country":"US","date":"2003-08-06",
                  "release-group":{"id":"rg-fiction","primary-type":"Album"},
                  "artist-credit":[{"name":"Yuki Kajiura","artist":{"id":"artist-1","name":"梶浦由記"}}],
                  "media":[{"format":"CD","track-count":4}]
                },
                {
                  "id":"jp-dig","title":"フィクション","country":"JP","date":"2003-08-06",
                  "release-group":{"id":"rg-fiction","primary-type":"Album"},
                  "artist-credit":[{"name":"Yuki Kajiura","artist":{"id":"artist-1","name":"梶浦由記"}}],
                  "media":[{"format":"Digital Media","track-count":4}]
                },
                {
                  "id":"jp-cd-early","title":"フィクション","country":"JP","date":"2003-08-01",
                  "release-group":{"id":"rg-fiction","primary-type":"Album"},
                  "artist-credit":[{"name":"Yuki Kajiura","artist":{"id":"artist-1","name":"梶浦由記"}}],
                  "media":[{"format":"CD","track-count":4}]
                }
              ]
            }
            """);
        responses.put("/release/us-cd?", releaseWithTracks("us-cd", "Fiction", "US", "CD", "2003-08-06", 4, "Yuki Kajiura", "梶浦由記"));
        responses.put("/release/jp-cd-early?", releaseWithTracks("jp-cd-early", "フィクション", "JP", "CD", "2003-08-01", 4, "Yuki Kajiura", "梶浦由記"));

        config.setReleaseCountryPriority(List.of("JP"));
        config.setPreferNativeArtistName(true);

        // Stage 1: locked Release Group selects JP CD (earlier) instead of first-listed US CD
        MusicMetadata stage1 = client.getRecordingById("rec-1", 4, true, "rg-fiction", null, 200, false);
        assertNotNull(stage1);
        assertEquals("jp-cd-early", stage1.getReleaseId());
        assertEquals("梶浦由記", stage1.getArtist());
        assertEquals("梶浦由記", stage1.getAlbumArtist());

        // Stage 0: explicit locked Release ID overrides country priority
        MusicMetadata stage0 = client.getRecordingById("rec-1", 4, true, "rg-fiction", "us-cd", 200, false);
        assertNotNull(stage0);
        assertEquals("us-cd", stage0.getReleaseId());

        // Stage 2: allowAlbumGuess selects JP CD (earlier) over JP Digital and US CD
        MusicMetadata stage2 = client.getRecordingById("rec-1", 4, true, null, null, 200, true);
        assertNotNull(stage2);
        assertEquals("jp-cd-early", stage2.getReleaseId());
    }

    @Test
    void countryPriorityDoesNotDropBaselineCandidatesOrTurnCrossAlbumAmbiguityIntoAutoLock() throws Exception {
        // P0 regression: RG A has 1 matching 4-track US CD + 5 non-covering 2-track JP Digital editions;
        // RG B has 1 matching 4-track US CD. Both empty and JP countryPriority must preserve RG A's US CD
        // so cross-album ambiguity stays blocked (margin = 0).
        StringBuilder rgAReleases = new StringBuilder();
        rgAReleases.append("""
            {"id":"a-us","title":"Shared Album","country":"US","date":"2020-01-01","media":[{"format":"CD","track-count":4}]}
            """);
        responses.put("/release/a-us?", releaseWithTracks("a-us", "Shared Album", "US", "CD", "2020-01-01", 4, "Artist", "Artist"));
        for (int i = 1; i <= 5; i++) {
            String id = "a-jp-" + i;
            rgAReleases.append("""
                ,{"id":"%s","title":"JP Short %d","country":"JP","date":"2020-02-0%d","media":[{"format":"Digital Media","track-count":2}]}
                """.formatted(id, i, i));
            responses.put("/release/" + id + "?", releaseWithTracks(id, "JP Short " + i, "JP", "Digital Media", "2020-02-0" + i, 2, "Artist", "Artist"));
        }
        responses.put("/release-group/rg-a?", """
            {"id":"rg-a","primary-type":"Album","releases":[%s]}
            """.formatted(rgAReleases));
        responses.put("/release-group/rg-b?", """
            {"id":"rg-b","primary-type":"Album","releases":[
              {"id":"b-us","title":"Shared Album","country":"US","date":"2020-01-01","media":[{"format":"CD","track-count":4}]}
            ]}
            """);
        responses.put("/release/b-us?", releaseWithTracks("b-us", "Shared Album", "US", "CD", "2020-01-01", 4, "Artist", "Artist"));

        List<Integer> folderDurations = List.of(200, 200, 200, 200);
        for (List<String> priority : List.<List<String>>of(List.of(), List.of("JP"))) {
            config.setReleaseCountryPriority(priority);
            List<DurationSequenceService.AlbumDurationInfo> candidates = new java.util.ArrayList<>();
            for (String rgId : List.of("rg-a", "rg-b")) {
                for (MusicBrainzClient.AlbumDurationResult r : client.getAllReleaseDurationSequences(rgId)) {
                    if (DurationSequenceService.countUnmatchedLocalTracks(folderDurations, r.getDurations(), r.getTrackCount()) > 0) {
                        continue;
                    }
                    var info = new DurationSequenceService.AlbumDurationInfo(
                        rgId, r.getReleaseId(), r.getReleaseTitle(), "Artist", r.getDurations(), r.getMediaFormat());
                    info.setReleaseTrackCount(r.getTrackCount());
                    info.setReleaseCountry(r.getReleaseCountry());
                    info.setReleaseDate(r.getReleaseDate());
                    candidates.add(info);
                }
            }
            assertEquals(2, candidates.size(), "Both RG A and RG B covering US CDs must remain for priority=" + priority);
            DurationSequenceService dss = new DurationSequenceService(config);
            assertNull(dss.selectBestMatchWithFolderName(folderDurations, candidates, "Shared Album"),
                "Ambiguous cross-album match must remain blocked for priority=" + priority);
        }
    }

    @Test
    void matchTrackInReleaseGroupFallsBackWhenPreferredJpEditionLacksTargetRecording() throws Exception {
        responses.put("/release-group/rg-fallback?", """
            {
              "id":"rg-fallback","primary-type":"Album",
              "releases":[
                {"id":"us-has-rec","title":"US CD","country":"US","date":"2020-01-01","media":[{"format":"CD","track-count":4}]},
                {"id":"jp-missing-rec","title":"JP Digital","country":"JP","date":"2020-02-01","media":[{"format":"Digital Media","track-count":4}]}
              ]
            }
            """);
        responses.put("/release/jp-missing-rec?", releaseWithTracks("jp-missing-rec", "JP Digital", "JP", "Digital Media", "2020-02-01", 4, "Yuki Kajiura", "梶浦由記"));
        responses.put("/release/us-has-rec?", releaseWithTracks("us-has-rec", "US CD", "US", "CD", "2020-01-01", 4, "Yuki Kajiura", "梶浦由記"));

        config.setReleaseCountryPriority(List.of("JP"));
        // Target recording ID exists only on US edition and title doesn't match JP track titles -> falls back to US
        MusicBrainzClient.TrackMatch match = client.matchTrackInReleaseGroup(
            "rg-fallback", "rec-us-has-rec-1", "Exclusive Bonus Song", 200, 4, true, "Album", "梶浦由記");
        assertTrue(match.isFound());
        assertEquals(MusicBrainzClient.TrackMatchType.RECORDING_ID, match.getType());
        assertEquals("us-has-rec", match.getMetadata().getReleaseId());
    }

    @Test
    void stage1ExcludesVideoOnlyGroupAndStage2DoesNotMergeMissingReleaseGroupIds() throws Exception {
        // Stage 1: pure video locked group must be excluded (returns no album)
        responses.put("/recording/rec-vid?", """
            {
              "id":"rec-vid","title":"Live Song",
              "artist-credit":[{"name":"Artist","artist":{"id":"a-1","name":"Artist"}}],
              "releases":[
                {"id":"jp-dvd","title":"Live DVD","country":"JP","date":"2020-01-01",
                 "release-group":{"id":"rg-vid","primary-type":"Album"},
                 "media":[{"format":"DVD-Video","track-count":4}]}
              ]
            }
            """);
        config.setReleaseCountryPriority(List.of("JP"));
        MusicMetadata vidMeta = client.getRecordingById("rec-vid", 4, true, "rg-vid", null, 200, false);
        assertNotNull(vidMeta);
        assertNull(vidMeta.getReleaseId());

        // Stage 2: unrelated releases missing release-group.id must not merge as sameGroupReleases
        responses.put("/recording/rec-norg?", """
            {
              "id":"rec-norg","title":"Song",
              "artist-credit":[{"name":"Artist","artist":{"id":"a-1","name":"Artist"}}],
              "releases":[
                {"id":"us-norg","title":"US Album","country":"US","date":"2019-01-01","status":"Official",
                 "media":[{"format":"CD","track-count":4}]},
                {"id":"jp-norg","title":"Different JP Album","country":"JP","date":"2021-01-01","status":"Bootleg",
                 "media":[{"format":"Digital Media","track-count":4}]}
              ]
            }
            """);
        responses.put("/release/us-norg?", releaseWithTracks("us-norg", "US Album", "US", "CD", "2019-01-01", 4, "Artist", "Artist"));
        MusicMetadata noRgMeta = client.getRecordingById("rec-norg", 4, true, null, null, 200, true);
        assertNotNull(noRgMeta);
        assertEquals("us-norg", noRgMeta.getReleaseId());
    }

    @Test
    void fetchAlbumDurationSequenceFallsBackPastThreeEmptyJpEditionsAndSnapshotsConfigConcurrently() throws Exception {
        // 3 JP editions have empty durations; 4th baseline US CD has valid durations -> must fall back cleanly
        responses.put("/release-group/rg-empty-jp?", """
            {
              "id":"rg-empty-jp","primary-type":"Album",
              "releases":[
                {"id":"jp-1","title":"JP 1","country":"JP","date":"2020-01-01","media":[{"format":"CD","track-count":2}]},
                {"id":"jp-2","title":"JP 2","country":"JP","date":"2020-01-02","media":[{"format":"CD","track-count":2}]},
                {"id":"jp-3","title":"JP 3","country":"JP","date":"2020-01-03","media":[{"format":"CD","track-count":2}]},
                {"id":"us-ok","title":"US OK","country":"US","date":"2020-01-04","media":[{"format":"CD","track-count":2}]}
              ]
            }
            """);
        for (String emptyId : List.of("jp-1", "jp-2", "jp-3")) {
            responses.put("/release/" + emptyId + "?", "{\"id\":\"" + emptyId + "\",\"media\":[{\"format\":\"CD\",\"tracks\":[]}]}");
        }
        responses.put("/release/us-ok?", releaseWithTracks("us-ok", "US OK", "US", "CD", "2020-01-04", 2, "Artist", "Artist"));

        config.setReleaseCountryPriority(List.of("JP"));
        MusicBrainzClient.AlbumDurationResult fallback = client.getAlbumDurationSequence("rg-empty-jp");
        assertEquals("us-ok", fallback.getReleaseId());
        assertEquals("US", fallback.getReleaseCountry());

        // Concurrency test: hot-reload during an in-flight request must NOT poison the old cache key
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch proceed = new java.util.concurrent.CountDownLatch(1);
        Map<String, String> raceResponses = new HashMap<>();
        raceResponses.put("/release-group/rg-race?", """
            {
              "id":"rg-race","primary-type":"Album",
              "releases":[
                {"id":"us-cd","title":"US CD","country":"US","date":"2020-01-01","media":[{"format":"CD","track-count":2}]},
                {"id":"jp-dig","title":"JP Digital","country":"JP","date":"2020-02-01","media":[{"format":"Digital Media","track-count":2}]}
              ]
            }
            """);
        raceResponses.put("/release/us-cd?", releaseWithTracks("us-cd", "US CD", "US", "CD", "2020-01-01", 2, "Artist", "Artist"));
        raceResponses.put("/release/jp-dig?", releaseWithTracks("jp-dig", "JP Digital", "JP", "Digital Media", "2020-02-01", 2, "Artist", "Artist"));

        java.util.concurrent.atomic.AtomicBoolean pausedOnce = new java.util.concurrent.atomic.AtomicBoolean(false);
        MusicBrainzClient racingClient = new MusicBrainzClient(config) {
            @Override
            synchronized void rateLimit() {
            }

            @Override
            String executeRequest(String url) {
                if (url.contains("/release-group/rg-race?") && pausedOnce.compareAndSet(false, true)) {
                    entered.countDown();
                    try {
                        assertTrue(proceed.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                for (Map.Entry<String, String> entry : raceResponses.entrySet()) {
                    if (url.contains(entry.getKey())) {
                        return entry.getValue();
                    }
                }
                throw new IllegalStateException("Unexpected URL: " + url);
            }
        };

        config.setReleaseCountryPriority(List.of());
        java.util.concurrent.FutureTask<MusicBrainzClient.AlbumDurationResult> inFlight =
            new java.util.concurrent.FutureTask<>(() -> racingClient.getAlbumDurationSequence("rg-race"));
        Thread t = new Thread(inFlight);
        t.start();
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));

        // Update country priority while Thread t is paused inside executeRequest
        config.setReleaseCountryPriority(List.of("JP"));
        proceed.countDown();

        MusicBrainzClient.AlbumDurationResult first = inFlight.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("us-cd", first.getReleaseId(), "In-flight request must honor its entry snapshot");

        // Subsequent request with JP gets JP Digital, and switching back to empty still gets unpoisoned US CD
        assertEquals("jp-dig", racingClient.getAlbumDurationSequence("rg-race").getReleaseId());
        config.setReleaseCountryPriority(List.of());
        assertEquals("us-cd", racingClient.getAlbumDurationSequence("rg-race").getReleaseId());
    }

    @Test
    void ioFailureOnPreferredCountryReleaseFallsBackToHealthyBaselineRelease() throws Exception {
        Map<String, String> flakyResponses = new HashMap<>();
        flakyResponses.put("/release-group/rg-flaky?", """
            {
              "id":"rg-flaky","primary-type":"Album",
              "releases":[
                {"id":"jp-broken","title":"JP Broken","country":"JP","date":"2020-01-01","media":[{"format":"CD","track-count":2}]},
                {"id":"us-healthy","title":"US Healthy","country":"US","date":"2020-01-02","media":[{"format":"CD","track-count":2}]}
              ]
            }
            """);
        flakyResponses.put("/release/us-healthy?", releaseWithTracks("us-healthy", "US Healthy", "US", "CD", "2020-01-02", 2, "Artist", "Artist"));

        MusicBrainzClient flakyClient = new MusicBrainzClient(config) {
            @Override
            synchronized void rateLimit() {
            }

            @Override
            String executeRequest(String url) throws java.io.IOException {
                if (url.contains("/release/jp-broken?")) {
                    throw new java.io.IOException("simulated transient 503 on JP release");
                }
                for (Map.Entry<String, String> entry : flakyResponses.entrySet()) {
                    if (url.contains(entry.getKey())) {
                        return entry.getValue();
                    }
                }
                throw new IllegalStateException("Unexpected URL: " + url);
            }
        };

        config.setReleaseCountryPriority(List.of("JP"));
        MusicBrainzClient.AlbumDurationResult single = flakyClient.getAlbumDurationSequence("rg-flaky");
        assertEquals("us-healthy", single.getReleaseId());

        List<MusicBrainzClient.AlbumDurationResult> all = flakyClient.getAllReleaseDurationSequences("rg-flaky");
        assertEquals(1, all.size());
        assertEquals("us-healthy", all.get(0).getReleaseId());
    }
}
