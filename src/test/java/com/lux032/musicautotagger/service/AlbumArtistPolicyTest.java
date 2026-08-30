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
    void defaultsToCollapsingForBackwardCompatibility() {
        assertTrue(config.isCollapseAlbumArtistToVariousArtists());
    }

    @Test
    void savesApprovedConfigurationKey() throws Exception {
        config.setCollapseAlbumArtistToVariousArtists(false);
        Path file = Files.createTempFile("music-config", ".properties");
        try {
            Method save = MusicConfig.class.getDeclaredMethod("saveToFile", Path.class);
            save.setAccessible(true);
            save.invoke(config, file);
            Properties properties = new Properties();
            try (java.io.Reader reader = Files.newBufferedReader(file)) {
                properties.load(reader);
            }
            assertEquals("false", properties.getProperty("tag.albumArtist.collapseToVariousArtists"));
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
}
