package com.lux032.musicautotagger.service.llm;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.util.OutboundUrlValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebPageFetcherTest {

    private static MusicConfig newConfig() throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static OutboundUrlValidator.ValidatedTarget fakePublicTarget(String url) throws Exception {
        URI uri = URI.create(url);
        return new OutboundUrlValidator.ValidatedTarget(
            uri,
            uri.getHost().toLowerCase(),
            new InetAddress[]{InetAddress.getByName("93.184.216.34")}
        );
    }

    @Test
    void normalizesUtaNetSongUrlAndStripsTrackingParams() {
        assertEquals("https://www.uta-net.com/song/402838/",
            WebPageFetcher.normalizeFetchUrl("https://www.uta-net.com/song/402838?utm_source=openai"));
        assertEquals("https://www.uta-net.com/song/402838/",
            WebPageFetcher.normalizeFetchUrl("https://www.uta-net.com/song/402838/"));
        assertEquals("https://himehina.jp/discography/vampy",
            WebPageFetcher.normalizeFetchUrl("https://himehina.jp/discography/vampy?utm_medium=cpc&fbclid=123"));
    }

    @Test
    void blocksPrivateLoopbackMetadataAndNonStandardPortTargets() throws Exception {
        MusicConfig cfg = newConfig();
        cfg.setOnlineCreditsAllowTavilyExtract(true);
        cfg.setTavilyApiKey("tvly-test");
        List<List<String>> extractCalls = new ArrayList<>();
        TavilyWebSearchClient stubTavily = new TavilyWebSearchClient(cfg, new LlmClient(cfg)) {
            @Override
            public Map<String, String> extract(List<String> urls, String extractDepth) {
                extractCalls.add(new ArrayList<>(urls));
                return Map.of();
            }
        };

        try (WebPageFetcher fetcher = new WebPageFetcher(cfg, stubTavily)) {
            Map<String, WebPageFetcher.FetchedPage> pages = fetcher.fetchBatch(List.of(
                "http://127.0.0.1/secret",
                "http://169.254.169.254/latest/meta-data/",
                "https://example.com:8443/non-standard-port"
            ), "HIMEHINA", true);

            assertEquals(3, pages.size());
            for (WebPageFetcher.FetchedPage p : pages.values()) {
                assertFalse(p.ok());
                assertEquals("ssrf_blocked", p.method());
                assertTrue(p.directStatus().startsWith("ssrf_blocked:"));
            }
            assertTrue(extractCalls.isEmpty(), "SSRF-blocked URLs must NEVER be forwarded to Tavily /extract");
        }
    }

    @Test
    void blocksRedirectHopToPrivateIp() throws Exception {
        MusicConfig cfg = newConfig();
        try (WebPageFetcher fetcher = new WebPageFetcher(cfg, null) {
            @Override
            OutboundUrlValidator.ValidatedTarget validatePublicTarget(String url) throws OutboundUrlValidator.ValidationException {
                if (url.startsWith("https://public.example.com/")) {
                    try {
                        return fakePublicTarget(url);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
                // Let the real SSRF validator reject the private redirect hop!
                return super.validatePublicTarget(url);
            }

            @Override
            RawHttpResult executeSingleHop(OutboundUrlValidator.ValidatedTarget target) {
                return new RawHttpResult(302, "text/html", "http://127.0.0.1:8080/internal", new byte[0]);
            }
        }) {
            WebPageFetcher.FetchedPage page = fetcher.fetchDirectOnly("https://public.example.com/redirect", "Artist");
            assertFalse(page.ok());
            assertEquals("ssrf_blocked", page.method());
            assertTrue(page.directStatus().startsWith("ssrf_blocked:"));
        }
    }

    @Test
    void decodesShiftJisJapaneseHtmlAndHandlesOversizedResponses() throws Exception {
        MusicConfig cfg = newConfig();
        String jpHtml = "<html><head><meta charset=\"Shift_JIS\"><title>VAMPY 特設ページ</title></head><body>"
            + "<h1>HIMEHINA VAMPY 楽曲クレジット</h1>"
            + "<p>1. VAMPY 作詞：Gohgo 作曲：Soma Genda 編曲：Soma Genda</p>"
            + "<p>2. Drain Drain 作詞：Gohgo 作曲：南田健吾 編曲：南田健吾。詳細な制作クレジットと公式リリース情報をお届けします。"
            + "本作品は2026年11月27日に配信限定EPとしてリリースされた最新作です。収録曲全4曲の情報を掲載しています。</p>"
            + "</body></html>";
        byte[] shiftJisBytes = jpHtml.getBytes(Charset.forName("Shift_JIS"));

        try (WebPageFetcher fetcher = new WebPageFetcher(cfg, null) {
            @Override
            OutboundUrlValidator.ValidatedTarget validatePublicTarget(String url) {
                try {
                    return fakePublicTarget(url);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            RawHttpResult executeSingleHop(OutboundUrlValidator.ValidatedTarget target) throws IOException {
                if (target.uri().toString().contains("huge")) {
                    throw new IOException("response.too.large");
                }
                return new RawHttpResult(200, "text/html; charset=Shift_JIS", null, shiftJisBytes);
            }
        }) {
            WebPageFetcher.FetchedPage okPage = fetcher.fetchDirectOnly("https://himehina.jp/vampy", "HIMEHINA");
            assertTrue(okPage.ok());
            assertEquals("direct_http", okPage.method());
            assertTrue(okPage.fullTextForVerify().contains("作詞：Gohgo"));
            assertTrue(okPage.fullTextForVerify().contains("南田健吾"));

            WebPageFetcher.FetchedPage tooLarge = fetcher.fetchDirectOnly("https://example.com/huge", "HIMEHINA");
            assertFalse(tooLarge.ok());
            assertEquals("response_too_large", tooLarge.directStatus());
        }
    }

    @Test
    void batchesBlockedAndYoutubeEmptyShellIntoSingleTavilyExtractAndSkips404() throws Exception {
        MusicConfig cfg = newConfig();
        cfg.setTavilyApiKey("tvly-test");
        cfg.setOnlineCreditsAllowTavilyExtract(true);

        List<List<String>> extractBatches = new ArrayList<>();
        TavilyWebSearchClient stubTavily = new TavilyWebSearchClient(cfg, new LlmClient(cfg)) {
            @Override
            public Map<String, String> extract(List<String> urls, String extractDepth) {
                extractBatches.add(new ArrayList<>(urls));
                return Map.of(
                    WebSearchClient.canonicalizeUrl("https://www.uta-net.com/song/402838/"),
                    "VAMPY 歌詞 作詞：Gohgo 作曲：Soma Genda 編曲：Soma Genda",
                    WebSearchClient.canonicalizeUrl("https://www.youtube.com/watch?v=abc12345"),
                    "HIMEHINA - VAMPY (Official MV) Lyrics: Gohgo / Music: Soma Genda"
                );
            }
        };

        try (WebPageFetcher fetcher = new WebPageFetcher(cfg, stubTavily) {
            @Override
            OutboundUrlValidator.ValidatedTarget validatePublicTarget(String url) {
                try {
                    return fakePublicTarget(url);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            RawHttpResult executeSingleHop(OutboundUrlValidator.ValidatedTarget target) {
                String u = target.uri().toString();
                if (u.contains("uta-net.com")) {
                    return new RawHttpResult(403, "text/html", null, "Forbidden".getBytes(StandardCharsets.UTF_8));
                }
                if (u.contains("youtube.com")) {
                    // Empty shell without shortDescription
                    return new RawHttpResult(200, "text/html", null, "<html><body>YouTube</body></html>".getBytes(StandardCharsets.UTF_8));
                }
                return new RawHttpResult(404, "text/html", null, "Not Found".getBytes(StandardCharsets.UTF_8));
            }
        }) {
            Map<String, WebPageFetcher.FetchedPage> pages = fetcher.fetchBatch(List.of(
                "https://www.uta-net.com/song/402838",
                "https://www.youtube.com/watch?v=abc12345",
                "https://example.com/deleted-404"
            ), "HIMEHINA", true);

            assertEquals(1, extractBatches.size(), "Blocked + YouTube empty shell must be batched in one /extract call");
            assertEquals(List.of(
                "https://www.uta-net.com/song/402838/",
                "https://www.youtube.com/watch?v=abc12345"
            ), extractBatches.get(0), "404 page must NOT be sent to /extract, and uta-net URL must have trailing slash");

            WebPageFetcher.FetchedPage utanet = pages.get(WebSearchClient.canonicalizeUrl("https://www.uta-net.com/song/402838/"));
            assertNotNull(utanet);
            assertTrue(utanet.ok());
            assertEquals("tavily_extract", utanet.method());
            assertTrue(utanet.fullTextForVerify().contains("Soma Genda"));

            WebPageFetcher.FetchedPage yt = pages.get(WebSearchClient.canonicalizeUrl("https://www.youtube.com/watch?v=abc12345"));
            assertNotNull(yt);
            assertTrue(yt.ok());
            assertEquals("tavily_extract", yt.method());

            WebPageFetcher.FetchedPage notFound = pages.get(WebSearchClient.canonicalizeUrl("https://example.com/deleted-404"));
            assertNotNull(notFound);
            assertFalse(notFound.ok());
            assertEquals("http_404", notFound.directStatus());
        }
    }

    @Test
    void extractsYoutubeShortDescriptionDirectlyWithoutTavily() throws Exception {
        MusicConfig cfg = newConfig();
        String ytHtml = "<html><script>var ytInitialPlayerResponse = {\"videoDetails\":{\"title\":\"HIMEHINA『VAMPY』MV\","
            + "\"author\":\"HIMEHINA Channel\",\"shortDescription\":\"作詞：Gohgo\\n作曲：Soma Genda\\n編曲：Soma Genda\"}};</script></html>";

        try (WebPageFetcher fetcher = new WebPageFetcher(cfg, null) {
            @Override
            OutboundUrlValidator.ValidatedTarget validatePublicTarget(String url) {
                try {
                    return fakePublicTarget(url);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            RawHttpResult executeSingleHop(OutboundUrlValidator.ValidatedTarget target) {
                return new RawHttpResult(200, "text/html; charset=utf-8", null, ytHtml.getBytes(StandardCharsets.UTF_8));
            }
        }) {
            WebPageFetcher.FetchedPage page = fetcher.fetchDirectOnly("https://www.youtube.com/watch?v=xyz", "HIMEHINA");
            assertTrue(page.ok());
            assertEquals("direct_http", page.method());
            assertTrue(page.fullTextForVerify().contains("作詞：Gohgo"));
            assertTrue(page.fullTextForVerify().contains("編曲：Soma Genda"));
        }
    }

    @Test
    void handlesInvalidCharsetCrossOriginRedirectProxyBlockAndExtractBatchAllowlist() throws Exception {
        MusicConfig cfg = newConfig();
        cfg.setTavilyApiKey("tvly-test");
        cfg.setOnlineCreditsAllowTavilyExtract(true);

        List<List<String>> extractBatches = new ArrayList<>();
        TavilyWebSearchClient stubTavily = new TavilyWebSearchClient(cfg, new LlmClient(cfg)) {
            @Override
            public Map<String, String> extract(List<String> urls, String extractDepth) {
                extractBatches.add(new ArrayList<>(urls));
                return Map.of(
                    WebSearchClient.canonicalizeUrl("https://www.uta-net.com/song/999/"), "valid extracted body",
                    // 未在提交列表中的越界 URL 必须被丢弃
                    WebSearchClient.canonicalizeUrl("https://evil.example.com/injected"), "injected content"
                );
            }
        };

        String validBody = "<html><body><p>" + "日文官方页面正文测试内容。".repeat(30) + "</p></body></html>";
        try (WebPageFetcher fetcher = new WebPageFetcher(cfg, stubTavily) {
            @Override
            OutboundUrlValidator.ValidatedTarget validatePublicTarget(String url) {
                try {
                    return fakePublicTarget(url);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            RawHttpResult executeSingleHop(OutboundUrlValidator.ValidatedTarget target) throws IOException {
                String u = target.uri().toString();
                if (u.contains("himehina.jp/redirect-out")) {
                    return new RawHttpResult(302, "text/html", "https://random-blog.example.com/post", new byte[0]);
                }
                if (u.contains("random-blog.example.com")) {
                    return new RawHttpResult(200, "text/html; charset=bogus-charset-9999", null, validBody.getBytes(StandardCharsets.UTF_8));
                }
                if (u.contains("huge.example.com")) {
                    throw new IOException("response.too.large");
                }
                return new RawHttpResult(403, "text/html", null, new byte[0]);
            }
        }) {
            Map<String, WebPageFetcher.FetchedPage> batch = fetcher.fetchBatch(List.of(
                "https://himehina.jp/redirect-out",
                "https://huge.example.com/file",
                "https://www.uta-net.com/song/999/"
            ), "HIMEHINA", true);

            // 1. 跨源重定向后 sourceType 必须按最终落地域名重算为 other，而非初始的 artist_official；非法 charset 自动回退解析成功
            WebPageFetcher.FetchedPage redirected = batch.get(WebSearchClient.canonicalizeUrl("https://himehina.jp/redirect-out"));
            assertNotNull(redirected);
            assertTrue(redirected.ok());
            assertEquals("other", redirected.sourceType(), "Cross-origin redirect must recompute sourceType from final URL");

            // 2. response_too_large 不应降级提交给 Tavily /extract；仅 403 页面提交，且未请求的 injected URL 被忽略
            assertEquals(1, extractBatches.size());
            assertEquals(List.of("https://www.uta-net.com/song/999/"), extractBatches.get(0));
            assertFalse(batch.containsKey(WebSearchClient.canonicalizeUrl("https://evil.example.com/injected")));
        }

        // 3. 配置 HTTP 代理时，拒绝未固定 DNS 的不受信任网页直连
        MusicConfig proxyCfg = newConfig();
        proxyCfg.setProxyEnabled(true);
        proxyCfg.setProxyHost("127.0.0.1");
        proxyCfg.setProxyPort(7890);
        try (WebPageFetcher proxyFetcher = new WebPageFetcher(proxyCfg, null) {
            @Override
            OutboundUrlValidator.ValidatedTarget validatePublicTarget(String url) {
                try {
                    return fakePublicTarget(url);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }) {
            WebPageFetcher.FetchedPage blocked = proxyFetcher.fetchDirectOnly("https://himehina.jp/vampy", "HIMEHINA");
            assertFalse(blocked.ok());
            assertEquals("proxy_unpinned_blocked", blocked.directStatus());
        }
    }
}
