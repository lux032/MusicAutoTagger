package com.lux032.musicautotagger.service.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.MusicConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TavilyWebSearchClientExtractTest {

    private static MusicConfig newConfig() throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    @Test
    void resolveExtractUrlHandlesStandardAndCustomBasePaths() {
        assertEquals("https://api.tavily.com/extract",
            TavilyWebSearchClient.resolveExtractUrl("https://api.tavily.com/search"));
        assertEquals("https://api.tavily.com/extract",
            TavilyWebSearchClient.resolveExtractUrl("https://api.tavily.com/search/"));
        assertEquals("https://proxy.example.com/tavily/extract",
            TavilyWebSearchClient.resolveExtractUrl("https://proxy.example.com/tavily/search"));
        assertEquals("https://api.tavily.com/extract",
            TavilyWebSearchClient.resolveExtractUrl("https://api.tavily.com/extract"));
        assertEquals("https://api.tavily.com/extract",
            TavilyWebSearchClient.resolveExtractUrl(""));
    }

    @Test
    void searchRawOmitsIncludeDomainsForOpenWebAndPreservesWhenProvided() throws Exception {
        MusicConfig cfg = newConfig();
        cfg.setTavilyApiKey("tvly-test-key");
        cfg.setTavilyApiUrl("https://api.tavily.com/search");

        List<JsonObject> capturedBodies = new ArrayList<>();
        TavilyWebSearchClient client = new TavilyWebSearchClient(cfg, new LlmClient(cfg)) {
            @Override
            JsonObject sendTavilyPost(String tavilyUrl, JsonObject body) {
                capturedBodies.add(body.deepCopy());
                JsonObject resp = new JsonObject();
                JsonArray results = new JsonArray();
                JsonObject item1 = new JsonObject();
                item1.addProperty("url", "https://himehina.jp/discography/vampy/?utm_source=test");
                item1.addProperty("title", "VAMPY | HIMEHINA");
                item1.addProperty("content", "作詞：Gohgo 作曲：Soma Genda");
                results.add(item1);
                // Canonical duplicate should be deduplicated
                JsonObject item2 = new JsonObject();
                item2.addProperty("url", "https://www.himehina.jp/discography/vampy");
                item2.addProperty("title", "VAMPY dup");
                item2.addProperty("content", "dup");
                results.add(item2);
                resp.add("results", results);
                return resp;
            }
        };

        List<WebSearchClient.Citation> openResults = client.searchRaw("HIMEHINA VAMPY 作詞 作曲 編曲", "advanced", 8, null);
        assertEquals(1, openResults.size());
        assertFalse(capturedBodies.get(0).has("include_domains"), "open web search must not include domain filter");
        assertEquals("advanced", capturedBodies.get(0).get("search_depth").getAsString());
        assertEquals(8, capturedBodies.get(0).get("max_results").getAsInt());

        client.searchRaw("VAMPY 歌詞", "basic", 5, List.of("uta-net.com", "utaten.com"));
        assertTrue(capturedBodies.get(1).has("include_domains"));
        assertEquals(2, capturedBodies.get(1).getAsJsonArray("include_domains").size());
        assertEquals("basic", capturedBodies.get(1).get("search_depth").getAsString());
    }

    @Test
    void extractBatchesFiveUrlsPerCallAndSurvivesPartialBatchFailure() throws Exception {
        MusicConfig cfg = newConfig();
        cfg.setTavilyApiKey("tvly-test-key");
        cfg.setTavilyApiUrl("https://api.tavily.com/search");

        List<String> calledUrls = new ArrayList<>();
        List<Integer> batchSizes = new ArrayList<>();
        TavilyWebSearchClient client = new TavilyWebSearchClient(cfg, new LlmClient(cfg)) {
            int callCount = 0;

            @Override
            JsonObject sendTavilyPost(String tavilyUrl, JsonObject body) {
                callCount++;
                calledUrls.add(tavilyUrl);
                JsonArray urls = body.getAsJsonArray("urls");
                batchSizes.add(urls.size());
                if (callCount == 2) {
                    throw new IllegalStateException("simulated batch 2 timeout");
                }
                JsonObject resp = new JsonObject();
                JsonArray results = new JsonArray();
                for (int i = 0; i < urls.size(); i++) {
                    JsonObject item = new JsonObject();
                    item.addProperty("url", urls.get(i).getAsString());
                    item.addProperty("raw_content", "extracted body #" + i);
                    results.add(item);
                }
                resp.add("results", results);
                return resp;
            }
        };

        List<String> input = List.of(
            "https://uta-net.com/song/1/",
            "https://uta-net.com/song/2/",
            "https://uta-net.com/song/3/",
            "https://uta-net.com/song/4/",
            "https://uta-net.com/song/5/",
            "https://uta-net.com/song/1/?utm_source=dup", // duplicate after canonicalize
            "https://uta-net.com/song/6/",
            "https://uta-net.com/song/7/"
        );

        Map<String, String> out = client.extract(input, "basic");
        assertEquals(List.of("https://api.tavily.com/extract", "https://api.tavily.com/extract"), calledUrls);
        assertEquals(List.of(5, 2), batchSizes, "7 unique URLs should be batched as 5 + 2");
        assertEquals(5, out.size(), "Batch 1 results must be retained even if Batch 2 fails");
        assertEquals("extracted body #0", out.get(WebSearchClient.canonicalizeUrl("https://uta-net.com/song/1/")));
    }
}
