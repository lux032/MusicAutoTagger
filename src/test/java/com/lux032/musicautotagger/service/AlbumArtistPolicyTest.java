package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lux032.musicautotagger.config.MusicConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlbumArtistPolicyTest {
    private MusicConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void createIsolatedConfig() throws Exception {
        Constructor<MusicConfig> constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        config = constructor.newInstance();
    }

    @Test
    void defaultsToPreservingCreditedAlbumArtists() {
        assertFalse(config.isCollapseAlbumArtistToVariousArtists());
    }

    @Test
    void savesApprovedConfigurationKey() throws Exception {
        config.setCollapseAlbumArtistToVariousArtists(true);
        Path file = Files.createTempFile("music-config", ".properties");
        try {
            Method save = MusicConfig.class.getDeclaredMethod("saveToFile", Path.class);
            save.setAccessible(true);
            save.invoke(config, file);
            Properties properties = new Properties();
            try (java.io.Reader reader = Files.newBufferedReader(file)) {
                properties.load(reader);
            }
            assertEquals("true", properties.getProperty("tag.albumArtist.collapseToVariousArtists"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void preservesSingleAuthoritativeBandName() throws Exception {
        config.setCollapseAlbumArtistToVariousArtists(true);
        AlbumArtistPolicy policy = new AlbumArtistPolicy(config);
        JsonNode credits = mapper.readTree("[{\"artist\":{\"name\":\"Earth, Wind & Fire\"}}]");

        assertEquals("Earth, Wind & Fire", policy.resolveFromCredits(credits));
    }

    @Test
    void collapsesMultipleCreditsWhenEnabled() throws Exception {
        config.setCollapseAlbumArtistToVariousArtists(true);
        AlbumArtistPolicy policy = new AlbumArtistPolicy(config);
        JsonNode credits = mapper.readTree("[{\"name\":\"A\",\"joinphrase\":\" feat. \"},{\"name\":\"B\"}]");

        assertEquals("Various Artists", policy.resolveFromCredits(credits));
    }

    @Test
    void rebuildsMultipleCreditsUsingJoinphraseWhenDisabled() throws Exception {
        config.setCollapseAlbumArtistToVariousArtists(false);
        AlbumArtistPolicy policy = new AlbumArtistPolicy(config);
        JsonNode credits = mapper.readTree("[{\"name\":\"A\",\"joinphrase\":\" feat. \"},{\"name\":\"B\"}]");

        assertEquals("A feat. B", policy.resolveFromCredits(credits));
    }

    @Test
    void unresolvedAlbumJoinsDistinctArtistsWhenDisabled() {
        config.setCollapseAlbumArtistToVariousArtists(false);
        AlbumArtistPolicy policy = new AlbumArtistPolicy(config);

        assertEquals("周杰伦, 蔡依林", policy.collapseOrJoin(List.of("周杰伦", "蔡依林", "周杰伦"), true));
    }

    @Test
    void unresolvedAlbumCollapsesWhenEnabledAndUnknownAlwaysFallsBack() {
        config.setCollapseAlbumArtistToVariousArtists(true);
        AlbumArtistPolicy policy = new AlbumArtistPolicy(config);

        assertEquals("Various Artists", policy.collapseOrJoin(List.of("A", "B"), true));
        config.setCollapseAlbumArtistToVariousArtists(false);
        assertEquals("Various Artists", policy.fallbackUnknown("  "));
    }

    @Test
    void albumArtistAndReleaseTagBundleFollowPreferNativeNameSetting() throws Exception {
        JsonNode release = mapper.readTree("""
            {
              "id": "rel-1",
              "title": "Fiction",
              "status": "Official",
              "country": "US",
              "artist-credit": [
                {"name": "Yuki Kajiura", "joinphrase": " feat. ", "artist": {"id": "id-yuki", "name": "梶浦由記", "sort-name": "Kajiura, Yuki"}},
                {"name": "Aimer", "joinphrase": "", "artist": {"id": "id-aimer", "name": "Aimer", "sort-name": "Aimer"}}
              ],
              "media": [
                {
                  "position": 1,
                  "format": "CD",
                  "tracks": [
                    {
                      "id": "trk-1",
                      "position": 1,
                      "length": 240000,
                      "title": "Key of the Twilight",
                      "recording": {
                        "id": "rec-1",
                        "title": "Key of the Twilight",
                        "length": 240000,
                        "artist-credit": [
                          {"name": "Yuki Kajiura", "joinphrase": " feat. ", "artist": {"id": "id-yuki", "name": "梶浦由記", "sort-name": "Kajiura, Yuki"}},
                          {"name": "Aimer", "joinphrase": "", "artist": {"id": "id-aimer", "name": "Aimer", "sort-name": "Aimer"}}
                        ]
                      }
                    }
                  ]
                }
              ]
            }
            """);

        config.setCollapseAlbumArtistToVariousArtists(false);
        config.setPreferNativeArtistName(true);
        MusicBrainzClient client = new MusicBrainzClient(config);

        MusicBrainzClient.ReleaseTagBundle nativeBundle = client.parseReleaseTagBundle(release);
        assertEquals("梶浦由記 feat. Aimer", nativeBundle.getAlbumArtistCredit());
        assertEquals(List.of("梶浦由記", "Aimer"), nativeBundle.getAlbumArtists());
        assertEquals("id-yuki; id-aimer", nativeBundle.getAlbumArtistId());
        JsonNode encodedAlbumNative = mapper.readTree(nativeBundle.getOriginalAlbumArtistCredit());
        assertEquals("梶浦由記 feat. Aimer", encodedAlbumNative.path("display").asText());
        assertEquals("梶浦由記", encodedAlbumNative.path("names").get(0).asText());
        assertEquals("Aimer", encodedAlbumNative.path("names").get(1).asText());
        assertEquals(" feat. ", encodedAlbumNative.path("before").get(1).asText());

        MusicBrainzClient.TrackMatch nativeTrack = client.matchTrackInReleaseNode(
            release, null, "rg-1", "rec-1", "Key of the Twilight", 240, "Fiction", "梶浦由記 feat. Aimer");
        assertTrue(nativeTrack.isFound());
        assertEquals("梶浦由記 feat. Aimer", nativeTrack.getMetadata().getArtist());
        assertEquals(List.of("梶浦由記", "Aimer"), nativeTrack.getMetadata().getArtists());
        assertEquals("id-yuki; id-aimer", nativeTrack.getMetadata().getArtistId());
        JsonNode encodedTrackNative = mapper.readTree(nativeTrack.getMetadata().getArtistCredit());
        assertEquals("梶浦由記 feat. Aimer", encodedTrackNative.path("display").asText());
        assertEquals(" feat. ", encodedTrackNative.path("before").get(1).asText());

        // Hot-toggle to false dynamically restores raw Romanized credits
        config.setPreferNativeArtistName(false);
        MusicBrainzClient.ReleaseTagBundle rawBundle = client.parseReleaseTagBundle(release);
        assertEquals("Yuki Kajiura feat. Aimer", rawBundle.getAlbumArtistCredit());
        assertEquals(List.of("梶浦由記", "Aimer"), rawBundle.getAlbumArtists());
        assertEquals("Yuki Kajiura feat. Aimer",
            mapper.readTree(rawBundle.getOriginalAlbumArtistCredit()).path("display").asText());

        MusicBrainzClient.TrackMatch rawTrack = client.matchTrackInReleaseNode(
            release, null, "rg-1", "rec-1", "Key of the Twilight", 240, "Fiction", "Yuki Kajiura feat. Aimer");
        assertTrue(rawTrack.isFound());
        assertEquals("Yuki Kajiura feat. Aimer", rawTrack.getMetadata().getArtist());
        assertEquals("Yuki Kajiura feat. Aimer",
            mapper.readTree(rawTrack.getMetadata().getArtistCredit()).path("display").asText());

        // Null config defaults to preferNativeName=true, aligning with MusicConfig default
        assertTrue(new AlbumArtistPolicy(null).shouldPreferNativeName());
    }
}
