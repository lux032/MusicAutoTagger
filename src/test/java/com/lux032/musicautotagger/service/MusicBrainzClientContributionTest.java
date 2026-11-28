package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MusicBrainzClientContributionTest {

    private static MusicConfig newConfig() throws Exception {
        java.lang.reflect.Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    @Test
    void searchesBarcodeCatnoReleaseGroupsAndParsesFullReleaseDetailWithArtistCaching() throws Exception {
        MusicConfig config = newConfig();
        MusicBrainzArtistCache cache = new MusicBrainzArtistCache(null);
        List<String> requestedUrls = new ArrayList<>();

        MusicBrainzClient client = new MusicBrainzClient(config, cache) {
            @Override
            synchronized void rateLimit() { }

            @Override
            String executeRequest(String url) throws IOException {
                requestedUrls.add(url);
                if (url.contains("barcode%3A4547366543210")) {
                    return """
                        {"releases":[{
                          "id":"11111111-1111-4111-8111-111111111111",
                          "title":"Intentions",
                          "score":100,
                          "date":"2026-11-25",
                          "country":"JP",
                          "status":"Official",
                          "barcode":"4547366543210",
                          "track-count":1,
                          "release-group":{"id":"22222222-2222-4222-8222-222222222222"},
                          "artist-credit":[{"name":"宇多田ヒカル","artist":{"id":"33333333-3333-4333-8333-333333333333","name":"宇多田ヒカル"}}],
                          "label-info":[{"catalog-number":"ESCL-9999"}]
                        }]}
                        """;
                }
                if (url.contains("catno%3A")) {
                    return "{\"releases\":[]}";
                }
                if (url.contains("/release-group?query=")) {
                    return """
                        {"release-groups":[{
                          "id":"22222222-2222-4222-8222-222222222222",
                          "title":"VAMPY",
                          "score":100,
                          "primary-type":"EP",
                          "secondary-types":[],
                          "first-release-date":"2026-11-27",
                          "artist-credit":[{"name":"HIMEHINA","artist":{"id":"c2deade3-c5e4-4061-8c13-9bd8f53a143b","name":"HIMEHINA"}}]
                        }]}
                        """;
                }
                if (url.contains("/release/44444444-4444-4444-8444-444444444444")) {
                    return """
                        {
                          "id":"44444444-4444-4444-8444-444444444444",
                          "title":"VAMPY",
                          "disambiguation":"Japanese digital EP",
                          "status":"Official",
                          "date":"2026-11-27",
                          "country":"JP",
                          "barcode":"4580000000001",
                          "text-representation":{"script":"Jpan"},
                          "release-group":{"id":"55555555-5555-4555-8555-555555555555","primary-type":"EP","secondary-types":[],"first-release-date":"2026-11-27"},
                          "artist-credit":[{"name":"HIMEHINA","artist":{"id":"c2deade3-c5e4-4061-8c13-9bd8f53a143b","name":"HIMEHINA","sort-name":"HIMEHINA"}}],
                          "label-info":[{"catalog-number":"HH-001","label":{"name":"LaRa"}}],
                          "media":[{
                            "position":1,
                            "format":"Digital Media",
                            "track-count":2,
                            "tracks":[
                              {
                                "id":"66666666-6666-4666-8666-666666666661",
                                "position":1,
                                "number":"1",
                                "title":"VAMPY",
                                "length":195200,
                                "recording":{"id":"77777777-7777-4777-8777-777777777771","title":"VAMPY","length":195200},
                                "artist-credit":[{"name":"HIMEHINA","artist":{"id":"c2deade3-c5e4-4061-8c13-9bd8f53a143b","name":"HIMEHINA","sort-name":"HIMEHINA"}}]
                              },
                              {
                                "id":"66666666-6666-4666-8666-666666666662",
                                "position":2,
                                "number":"2",
                                "title":"Drain Drain",
                                "length":210400,
                                "recording":{"id":"77777777-7777-4777-8777-777777777772","title":"Drain Drain","length":210400},
                                "artist-credit":[{"name":"HIMEHINA","artist":{"id":"c2deade3-c5e4-4061-8c13-9bd8f53a143b","name":"HIMEHINA","sort-name":"HIMEHINA"}}]
                              }
                            ]
                          }]
                        }
                        """;
                }
                throw new MusicBrainzClient.NonRetryableHttpException(404);
            }
        };

        // 无效条码不发起请求
        assertTrue(client.searchReleasesByBarcode("abc").isEmpty());
        assertTrue(requestedUrls.isEmpty());

        List<MusicBrainzClient.ReleaseSearchHit> barcodeHits = client.searchReleasesByBarcode("4547-3665-43210");
        assertEquals(1, barcodeHits.size());
        assertEquals("11111111-1111-4111-8111-111111111111", barcodeHits.get(0).releaseId());
        assertEquals("ESCL-9999", barcodeHits.get(0).catalogNumber());

        // 目录号 Lucene 转义 & 合法 arid 拼装
        assertTrue(client.searchReleasesByCatalogNumber("ESCL\"99", "c2deade3-c5e4-4061-8c13-9bd8f53a143b").isEmpty());
        assertTrue(requestedUrls.get(requestedUrls.size() - 1).contains("arid%3Ac2deade3-c5e4-4061-8c13-9bd8f53a143b"));

        // Release Group 检索优先使用 arid
        List<MusicBrainzClient.ReleaseGroupSearchHit> rgs =
            client.searchReleaseGroups("VAMPY", "HIMEHINA", "c2deade3-c5e4-4061-8c13-9bd8f53a143b", 5);
        assertEquals(1, rgs.size());
        assertEquals("22222222-2222-4222-8222-222222222222", rgs.get(0).releaseGroupId());
        assertEquals("ep", rgs.get(0).primaryType());

        // 完整 Release 解析 + 艺人缓存沉淀
        Optional<MusicBrainzClient.ReleaseDetail> detailOpt =
            client.getFullReleaseWithTracklist("44444444-4444-4444-8444-444444444444");
        assertTrue(detailOpt.isPresent());
        MusicBrainzClient.ReleaseDetail detail = detailOpt.get();
        assertEquals("55555555-5555-4555-8555-555555555555", detail.releaseGroupId());
        assertEquals("LaRa", detail.recordLabel());
        assertEquals("HH-001", detail.catalogNumber());
        assertEquals(2, detail.allTracks().size());
        assertEquals("77777777-7777-4777-8777-777777777772", detail.allTracks().get(1).recordingId());
        assertTrue(cache.lookupByName("HIMEHINA").isUniqueHit(), "完整 Release 解析应被动沉淀艺人缓存");

        // 404 返回 Optional.empty()
        assertTrue(client.getFullReleaseWithTracklist("99999999-9999-4999-8999-999999999999").isEmpty());
        // 非法 UUID 返回 Optional.empty()
        assertTrue(client.getFullReleaseWithTracklist("not-a-uuid").isEmpty());
    }
}
