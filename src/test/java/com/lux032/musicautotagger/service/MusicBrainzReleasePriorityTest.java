package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MusicBrainzReleasePriorityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode release(String id, String country, String format, String date) throws Exception {
        String dateField = date == null ? "" : ",\"date\":\"" + date + "\"";
        String countryField = country == null ? "" : ",\"country\":\"" + country + "\"";
        return mapper.readTree("{\"id\":\"" + id + "\"" + countryField + dateField
            + ",\"media\":[{\"format\":\"" + format + "\"}]}");
    }

    @Test
    void prefersConfiguredCountryDigitalOverNonPreferredCd() throws Exception {
        JsonNode usCd = release("us-cd", "US", "CD", "2020-01-01");
        JsonNode jpDigital = release("jp-dig", "JP", "Digital Media", "2020-06-01");

        assertTrue(MusicBrainzReleasePriority.compareReleaseJson(jpDigital, usCd, List.of("JP")) < 0);
        assertTrue(MusicBrainzReleasePriority.compareReleaseJson(usCd, jpDigital, List.of("JP")) > 0);
    }

    @Test
    void respectsMultiTierCaseInsensitiveCountryOrder() throws Exception {
        JsonNode jp = release("jp", "jp", "Digital Media", "2021-01-01");
        JsonNode tw = release("tw", "TW", "CD", "2020-01-01");
        JsonNode us = release("us", "US", "CD", "2019-01-01");
        List<String> priority = List.of("JP", "TW", "US");

        assertTrue(MusicBrainzReleasePriority.compareReleaseJson(jp, tw, priority) < 0);
        assertTrue(MusicBrainzReleasePriority.compareReleaseJson(tw, us, priority) < 0);
    }

    @Test
    void alwaysExcludesVideoFormatsRegardlessOfCountry() throws Exception {
        JsonNode jpDvd = release("jp-dvd", "JP", "DVD-Video", "2020-01-01");
        JsonNode usCd = release("us-cd", "US", "CD", "2020-06-01");

        assertEquals(-100, MusicBrainzReleasePriority.mediaFormatScore(jpDvd));
        assertTrue(MusicBrainzReleasePriority.compareReleaseJson(usCd, jpDvd, List.of("JP")) < 0);
    }

    @Test
    void breaksTiesWithinSameCountryByMediaFormatThenEarlierReleaseDate() throws Exception {
        JsonNode jpDigital = release("jp-dig", "JP", "Digital Media", "2020-05-01");
        JsonNode jpCdLater = release("jp-cd-late", "JP", "CD", "2020-05-10");
        JsonNode jpCdEarlier = release("jp-cd-early", "JP", "CD", "2020-04-20");
        JsonNode jpCdNoDate = release("jp-cd-nodate", "JP", "CD", null);
        List<String> priority = List.of("JP");

        assertTrue(MusicBrainzReleasePriority.compareReleaseJson(jpCdLater, jpDigital, priority) < 0);
        assertTrue(MusicBrainzReleasePriority.compareReleaseJson(jpCdEarlier, jpCdLater, priority) < 0);
        assertTrue(MusicBrainzReleasePriority.compareReleaseJson(jpCdLater, jpCdNoDate, priority) < 0);
    }

    @Test
    void emptyCountryPriorityPreservesExistingCdOverDigitalPrecedence() throws Exception {
        JsonNode usCd = release("us-cd", "US", "CD", "2020-05-01");
        JsonNode jpDigital = release("jp-dig", "JP", "Digital Media", "2020-01-01");

        assertTrue(MusicBrainzReleasePriority.compareReleaseJson(usCd, jpDigital, List.of()) < 0);
        assertTrue(MusicBrainzReleasePriority.compareReleaseJson(usCd, jpDigital, null) < 0);
    }

    @Test
    void handlesMixedAudioVideoDiscsAndDvdAudioCorrectly() throws Exception {
        JsonNode dvdAudio = release("dvd-a", "JP", "DVD-Audio", "2020-01-01");
        assertFalse(MusicBrainzReleasePriority.isVideoFormat("DVD-Audio"));
        assertEquals(50, MusicBrainzReleasePriority.mediaFormatScore(dvdAudio));

        JsonNode dvdThenCd = mapper.readTree(
            "{\"id\":\"mix-1\",\"country\":\"JP\",\"media\":[{\"format\":\"DVD-Video\"},{\"format\":\"CD\"}]}");
        JsonNode cdThenDvd = mapper.readTree(
            "{\"id\":\"mix-2\",\"country\":\"JP\",\"media\":[{\"format\":\"CD\"},{\"format\":\"DVD-Video\"}]}");
        JsonNode allVideo = mapper.readTree(
            "{\"id\":\"vid-only\",\"country\":\"JP\",\"media\":[{\"format\":\"DVD-Video\"},{\"format\":\"Blu-ray\"}]}");

        assertEquals(100, MusicBrainzReleasePriority.mediaFormatScore(dvdThenCd));
        assertEquals("CD", MusicBrainzReleasePriority.extractPrimaryAudioFormat(dvdThenCd));
        assertEquals(100, MusicBrainzReleasePriority.mediaFormatScore(cdThenDvd));
        assertEquals("CD", MusicBrainzReleasePriority.extractPrimaryAudioFormat(cdThenDvd));
        assertEquals(-100, MusicBrainzReleasePriority.mediaFormatScore(allVideo));
        assertNull(MusicBrainzReleasePriority.extractPrimaryAudioFormat(allVideo));
    }
}
