package com.lux032.musicautotagger.service.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebSearchClientReliabilityAndUrlTest {

    @Test
    void canonicalizesUrlsAndStripsTrackingParameters() {
        assertEquals(
            "https://spc.himehina.jp/vampy",
            WebSearchClient.canonicalizeUrl("https://spc.himehina.jp/vampy/?utm_source=openai")
        );
        assertEquals(
            "https://music.apple.com/jp/album/vampy-ep/6833337851",
            WebSearchClient.canonicalizeUrl("HTTPS://www.music.apple.com:443/jp/album/vampy-ep/6833337851/?uo=2&utm_campaign=test")
        );
        assertEquals(
            "https://youtube.com/watch?v=fObP6lzjClI",
            WebSearchClient.canonicalizeUrl("https://www.youtube.com/watch?utm_source=openai&v=fObP6lzjClI&fbclid=xyz")
        );
        assertEquals(
            "https://spc.himehina.jp/vampy",
            WebSearchClient.stripTrackingParams("https://spc.himehina.jp/vampy?utm_source=openai")
        );
        assertEquals(
            "https://www.youtube.com/watch?v=fObP6lzjClI",
            WebSearchClient.stripTrackingParams("https://www.youtube.com/watch?v=fObP6lzjClI&utm_source=openai")
        );
        // 拒绝非 HTTP(S) 协议与畸形 URL
        assertEquals("", WebSearchClient.canonicalizeUrl("javascript:alert(1)"));
        assertEquals("", WebSearchClient.canonicalizeUrl("file:///etc/passwd"));
        // 保留签名 URL 原始编码与同名参数原始顺序
        assertEquals(
            "https://cdn.example.com/file?sig=a%2Bb%3Dc&edition=jp&edition=us",
            WebSearchClient.stripTrackingParams("https://cdn.example.com/file?sig=a%2Bb%3Dc&utm_source=openai&edition=jp&edition=us")
        );
        assertNotEquals(
            WebSearchClient.canonicalizeUrl("https://shop.example.com/album?edition=jp&edition=us"),
            WebSearchClient.canonicalizeUrl("https://shop.example.com/album?edition=us&edition=jp"),
            "Repeated parameter order must be preserved"
        );
    }

    @Test
    void sourceReliabilityCoversEastAsianStoresMediaAndBlocksSpoofedDomains() {
        // 国际 + 东亚正版数字发行/数据库 -> HIGH
        assertEquals("HIGH", WebSearchClient.SourceReliability.of("musicbrainz.org"));
        assertEquals("HIGH", WebSearchClient.SourceReliability.of("ototoy.jp"));
        assertEquals("HIGH", WebSearchClient.SourceReliability.of("www.mora.jp"));
        assertEquals("HIGH", WebSearchClient.SourceReliability.of("recochoku.jp"));
        assertEquals("HIGH", WebSearchClient.SourceReliability.of("linkco.re"));
        assertEquals("HIGH", WebSearchClient.SourceReliability.of("vocadb.net"));

        // 国际 + 日系主流零售/通稿/歌词站 -> MEDIUM
        assertEquals("MEDIUM", WebSearchClient.SourceReliability.of("ja.wikipedia.org"));
        assertEquals("MEDIUM", WebSearchClient.SourceReliability.of("prtimes.jp"));
        assertEquals("MEDIUM", WebSearchClient.SourceReliability.of("natalie.mu"));
        assertEquals("MEDIUM", WebSearchClient.SourceReliability.of("realsound.jp"));
        assertEquals("MEDIUM", WebSearchClient.SourceReliability.of("www.uta-net.com"));
        assertEquals("MEDIUM", WebSearchClient.SourceReliability.of("utaten.com"));
        assertEquals("MEDIUM", WebSearchClient.SourceReliability.of("tower.jp"));

        // 仿冒域名必须继续拦截为 LOW
        assertEquals("LOW", WebSearchClient.SourceReliability.of("fake-ototoy.example.com"));
        assertEquals("LOW", WebSearchClient.SourceReliability.of("not-vgmdb.net"));
        assertEquals("LOW", WebSearchClient.SourceReliability.of("prtimes.jp.evil.org"));
        assertEquals("LOW", WebSearchClient.SourceReliability.of(""));
        assertEquals("LOW", WebSearchClient.SourceReliability.of(null));
    }

    @Test
    void searchResponseDeduplicatesCitationsByCanonicalUrlAndUpgradesOpenedPage() {
        WebSearchClient.SearchResponse resp = new WebSearchClient.SearchResponse();
        resp.addCitation(WebSearchClient.Citation.of("https://www.uta-net.com/song/402838/?utm_source=openai", null, null, false));
        resp.addCitation(WebSearchClient.Citation.of("https://uta-net.com/song/402838", "VAMPY 歌詞", "編曲：カワイヒデヒロ", true));

        assertEquals(1, resp.getCitations().size());
        WebSearchClient.Citation c = resp.getCitations().get(0);
        assertEquals("https://www.uta-net.com/song/402838/", c.getUrl());
        assertTrue(c.isOpenedPage());
        assertEquals("VAMPY 歌詞", c.getTitle());
        assertEquals("MEDIUM", c.getReliability());
    }

    @Test
    void classifiesEightMusicWebsiteCategories() {
        assertEquals("artist_official", WebSearchClient.SourceReliability.classifySourceType("https://himehina.jp/post/post-468", "HIMEHINA"));
        assertEquals("label_official", WebSearchClient.SourceReliability.classifySourceType("https://rkmusic.jp/release/189", "KMNZ"));
        assertEquals("jp_lyrics_site", WebSearchClient.SourceReliability.classifySourceType("https://www.uta-net.com/song/402838/", "HIMEHINA"));
        assertEquals("music_store_platform", WebSearchClient.SourceReliability.classifySourceType("https://ototoy.jp/_/default/p/2319747", "harha"));
        assertEquals("official_press_release", WebSearchClient.SourceReliability.classifySourceType("https://prtimes.jp/main/html/rd/p/000000700.000044525.html", "HIMEHINA"));
        assertEquals("youtube_official", WebSearchClient.SourceReliability.classifySourceType("https://www.youtube.com/watch?v=fObP6lzjClI", "HIMEHINA"));
        assertEquals("music_database", WebSearchClient.SourceReliability.classifySourceType("https://vgmdb.net/album/12345", "Test"));
        assertEquals("community_wiki", WebSearchClient.SourceReliability.classifySourceType("https://ja.wikipedia.org/wiki/VAMPY", "HIMEHINA"));
        // 严禁子域/前后缀仿冒艺人官网
        assertEquals("other", WebSearchClient.SourceReliability.classifySourceType("https://himehina.evil.com/vampy", "HIMEHINA"));
        assertEquals("other", WebSearchClient.SourceReliability.classifySourceType("https://fake-himehina.com/vampy", "HIMEHINA"));
        assertEquals("artist_official", WebSearchClient.SourceReliability.classifySourceType("https://spc.himehina.jp/vampy", "HIMEHINA"));
    }
}
