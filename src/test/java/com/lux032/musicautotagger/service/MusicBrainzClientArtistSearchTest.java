package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import org.apache.hc.core5.http.ParseException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MusicBrainzClientArtistSearchTest {

    private static final String MBID_KAJIURA = "11111111-1111-4111-8111-111111111111";
    private static final String MBID_AIMER = "22222222-2222-4222-8222-222222222222";
    private static final String MBID_COMPOSER = "33333333-3333-4333-8333-333333333333";

    private final ObjectMapper mapper = new ObjectMapper();

    private MusicConfig newConfig() throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MusicConfig cfg = ctor.newInstance();
        cfg.setMusicBrainzApiUrl("https://musicbrainz.example/ws/2");
        return cfg;
    }

    private static class StubMusicBrainzClient extends MusicBrainzClient {
        final List<String> requestedUrls = new ArrayList<>();
        private final java.util.function.Function<String, String> responder;
        private final boolean throw404;

        StubMusicBrainzClient(MusicConfig config, MusicBrainzArtistCache cache,
                              java.util.function.Function<String, String> responder, boolean throw404) {
            super(config, cache);
            this.responder = responder;
            this.throw404 = throw404;
        }

        @Override
        synchronized void rateLimit() {
            // 单元测试不休眠 1 秒
        }

        @Override
        String executeRequest(String url) throws IOException, ParseException {
            requestedUrls.add(url);
            if (throw404) {
                throw new IOException("API 请求失败: 404");
            }
            return responder.apply(url);
        }
    }

    @Test
    void searchArtistsEscapesLuceneAndParsesMetadataAliasesAndTags() throws Exception {
        String searchJson = """
            {
              "artists": [
                {
                  "id": "%s",
                  "score": 100,
                  "type": "Person",
                  "name": "梶浦由記",
                  "sort-name": "Kajiura, Yuki",
                  "country": "JP",
                  "area": {"name": "Japan", "iso-3166-1-codes": ["JP"]},
                  "disambiguation": "Japanese composer",
                  "life-span": {"begin": "1965-08-06", "ended": false},
                  "aliases": [
                    {"name": "Yuki Kajiura"},
                    {"name": "かじうら ゆき"}
                  ],
                  "tags": [
                    {"count": 5, "name": "anime"},
                    {"count": 3, "name": "soundtrack"}
                  ]
                }
              ]
            }
            """.formatted(MBID_KAJIURA);

        StubMusicBrainzClient client = new StubMusicBrainzClient(newConfig(), null, url -> searchJson, false);
        List<MusicBrainzClient.ArtistMatch> matches = client.searchArtists("Yuki \"Kajiura\"", 8);

        assertEquals(1, client.requestedUrls.size());
        String url = client.requestedUrls.get(0);
        assertTrue(url.contains("/artist?query="));
        assertTrue(url.contains("limit=8"));
        assertTrue(url.contains("artist%3A%22Yuki+%5C%22Kajiura%5C%22%22"));

        assertEquals(1, matches.size());
        MusicBrainzClient.ArtistMatch m = matches.get(0);
        assertEquals(MBID_KAJIURA, m.mbid());
        assertEquals("梶浦由記", m.name());
        assertEquals("Kajiura, Yuki", m.sortName());
        assertEquals("Japanese composer", m.disambiguation());
        assertEquals("JP", m.country());
        assertEquals("Japan", m.areaName());
        assertEquals("Person", m.type());
        assertEquals("1965-08-06", m.lifeSpanBegin());
        assertEquals(100, m.score());
        assertEquals(List.of("Yuki Kajiura", "かじうら ゆき"), m.aliases());
        assertEquals(List.of("anime", "soundtrack"), m.tags());
    }

    @Test
    void getArtistByIdValidatesUuidAndParsesSingleArtist() throws Exception {
        String artistJson = """
            {
              "id": "%s",
              "type": "Person",
              "name": "Aimer",
              "sort-name": "Aimer",
              "area": {"name": "Japan", "iso-3166-1-codes": ["JP"]},
              "disambiguation": "Japanese pop singer",
              "aliases": [{"name": "エメ"}],
              "tags": [{"name": "j-pop"}]
            }
            """.formatted(MBID_AIMER);

        StubMusicBrainzClient client = new StubMusicBrainzClient(newConfig(), null, url -> artistJson, false);
        assertTrue(client.getArtistById("invalid-uuid").isEmpty());
        assertTrue(client.getArtistById("00000000-0000-0000-0000-000000000000").isEmpty());
        assertEquals(0, client.requestedUrls.size());

        Optional<MusicBrainzClient.ArtistMatch> found = client.getArtistById(MBID_AIMER.toUpperCase());
        assertTrue(found.isPresent());
        assertEquals(MBID_AIMER, found.get().mbid());
        assertEquals("JP", found.get().country());
        assertEquals(List.of("エメ"), found.get().aliases());
        assertTrue(client.requestedUrls.get(0).contains("/artist/" + MBID_AIMER + "?fmt=json&inc=aliases+tags"));
    }

    @Test
    void passiveCachingRecordsArtistsAndCreatorsWithoutBreakingOnCacheErrors() throws Exception {
        MusicBrainzArtistCache cache = new MusicBrainzArtistCache(null);
        MusicBrainzClient client = new MusicBrainzClient(newConfig(), cache);

        // 1. parseReleaseTagBundle 被动沉淀专辑艺人及其罗马音 credit.name
        JsonNode releaseNode = mapper.readTree("""
            {
              "id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
              "title": "Fiction",
              "artist-credit": [
                {
                  "name": "Yuki Kajiura",
                  "artist": {
                    "id": "%s",
                    "name": "梶浦由記",
                    "sort-name": "Kajiura, Yuki",
                    "disambiguation": "Japanese composer"
                  }
                }
              ],
              "media": []
            }
            """.formatted(MBID_KAJIURA));
        client.parseReleaseTagBundle(releaseNode);

        assertTrue(cache.lookupByName("梶浦由記").isUniqueHit());
        assertTrue(cache.lookupByName("Yuki Kajiura").isUniqueHit());
        assertEquals(MBID_KAJIURA, cache.lookupByName("yuki kajiura").uniqueArtist().mbid());

        // 2. parseCreatorCredits 被动沉淀作曲/作词/编曲
        JsonNode recordingNode = mapper.readTree("""
            {
              "relations": [
                {
                  "type": "performance",
                  "work": {
                    "relations": [
                      {
                        "type": "composer",
                        "target-credit": "Sawano",
                        "artist": {
                          "id": "%s",
                          "name": "澤野弘之",
                          "sort-name": "Sawano, Hiroyuki"
                        }
                      }
                    ]
                  }
                }
              ]
            }
            """.formatted(MBID_COMPOSER));
        MusicMetadata md = new MusicMetadata();
        client.parseCreatorCredits(recordingNode, md);
        assertEquals(List.of("澤野弘之"), md.getComposers());
        assertTrue(cache.lookupByName("澤野弘之").isUniqueHit());
        assertTrue(cache.lookupByName("Sawano").isUniqueHit());
        assertEquals(MBID_COMPOSER, cache.lookupByName("澤野弘之").uniqueArtist().mbid());

        // 3. 缓存抛运行时异常时绝不中断 MusicBrainzClient 主解析逻辑
        MusicBrainzArtistCache brokenCache = new MusicBrainzArtistCache(null) {
            @Override
            public void recordArtist(String rawMbid, String name, String sortName, String disambiguation,
                                     String country, String artistType, java.util.Collection<String> aliases,
                                     java.util.Collection<String> extraIndexNames, String source) {
                throw new RuntimeException("simulated cache failure");
            }
        };
        MusicBrainzClient resilient = new MusicBrainzClient(newConfig(), brokenCache);
        MusicMetadata md2 = new MusicMetadata();
        assertDoesNotThrow(() -> resilient.parseCreatorCredits(recordingNode, md2));
        assertEquals(List.of("澤野弘之"), md2.getComposers());
        assertEquals(List.of(MBID_COMPOSER), md2.getComposerIds());
    }

    @Test
    void passiveCachingCapturesAliasesAndAttemptBudgetCapsOutboundRetries() throws Exception {
        MusicBrainzArtistCache cache = new MusicBrainzArtistCache(null);
        MusicBrainzClient client = new MusicBrainzClient(newConfig(), cache);

        JsonNode releaseWithAliases = mapper.readTree("""
            {
              "id": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
              "artist-credit": [
                {
                  "name": "Aimer",
                  "artist": {
                    "id": "%s",
                    "name": "Aimer",
                    "sort-name": "Aimer",
                    "aliases": [{"name": "エメ"}, {"name": "えめ"}]
                  }
                }
              ]
            }
            """.formatted(MBID_AIMER));
        client.parseReleaseTagBundle(releaseWithAliases);
        assertTrue(cache.lookupByName("エメ").isUniqueHit(), "Passive caching must index supplied artist aliases");
        assertEquals(MBID_AIMER, cache.lookupByName("エメ").uniqueArtist().mbid());

        // 预算闸门为 0 时，executeRequest 立即抛出 BudgetExhaustedException 而不发任何出站 HTTP
        try (AutoCloseable ignored = client.withAttemptBudget(() -> false)) {
            assertThrows(MusicBrainzClient.BudgetExhaustedException.class,
                () -> client.executeRequest("http://127.0.0.1:1/unreachable"));
        }

        // searchArtistsAny 将多个变体合并为单次 Lucene OR 请求，只消耗 1 次外部调用
        String amalaJson = """
            {
              "artists": [{
                "id": "74b4d464-7b7c-48c4-b96d-c5adf7f8e1c9",
                "score": 100,
                "type": "Person",
                "name": "雨良",
                "sort-name": "Amala",
                "country": "JP",
                "disambiguation": "Amala, Vocaloid producer",
                "tags": [{"name": "vocaloid"}]
              }]
            }
            """;
        StubMusicBrainzClient multiClient = new StubMusicBrainzClient(newConfig(), null, u -> amalaJson, false);
        List<MusicBrainzClient.ArtistMatch> multiRes =
            multiClient.searchArtistsAny(List.of("雨良 Amala", "雨良", "Amala", "雨良"), 8);
        assertEquals(1, multiClient.requestedUrls.size(), "Multiple variants must be combined into a single MB request");
        assertTrue(multiClient.requestedUrls.get(0).contains("+OR+"));
        assertEquals(1, multiRes.size());
        assertEquals("74b4d464-7b7c-48c4-b96d-c5adf7f8e1c9", multiRes.get(0).mbid());
    }
}
