package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lux032.musicautotagger.config.MusicConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MusicBrainzClientDurationParsingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MusicBrainzClient client;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        client = new MusicBrainzClient(constructor.newInstance());
    }

    @Test
    void fallsBackToRecordingLength() throws Exception {
        JsonNode root = mapper.readTree("{\"media\":[{\"format\":\"Digital Media\",\"tracks\":["
            + "{\"recording\":{\"length\":123600}}]}]}");
        assertEquals(List.of(124), client.extractDurationsFromReleaseJson(root));
    }

    @Test
    void trackLengthTakesPrecedenceOverRecordingLength() throws Exception {
        JsonNode root = mapper.readTree("{\"media\":[{\"format\":\"CD\",\"tracks\":["
            + "{\"length\":100400,\"recording\":{\"length\":200400}}]}]}");
        assertEquals(List.of(100), client.extractDurationsFromReleaseJson(root));
    }

    @Test
    void omitsTracksWithoutPositiveLength() throws Exception {
        JsonNode root = mapper.readTree("{\"media\":[{\"format\":\"CD\",\"tracks\":["
            + "{\"length\":0,\"recording\":{}},{\"length\":-1,\"recording\":{\"length\":0}}]}]}");
        assertEquals(List.of(), client.extractDurationsFromReleaseJson(root));
    }

    @Test
    void skipsVideoMedia() throws Exception {
        JsonNode root = mapper.readTree("{\"media\":[{\"format\":\"DVD\",\"tracks\":["
            + "{\"length\":100000,\"recording\":{}}]}]}");
        assertEquals(List.of(), client.extractDurationsFromReleaseJson(root));
    }

    @Test
    void skipsVideoRecording() throws Exception {
        JsonNode root = mapper.readTree("{\"media\":[{\"format\":\"CD\",\"tracks\":["
            + "{\"length\":100000,\"recording\":{\"video\":true}}]}]}");
        assertEquals(List.of(), client.extractDurationsFromReleaseJson(root));
    }
}
