package com.lux032.musicautotagger.service.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.MusicConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeWebSearchClientTest {

    private MusicConfig config;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        config = ctor.newInstance();
    }

    @Test
    void parsesActionSourcesAndOpenPageUrlsWhenJsonAnnotationsAreEmpty() {
        NativeWebSearchClient client = new NativeWebSearchClient(config);
        JsonObject response = gson.fromJson("""
            {
              "output": [
                {
                  "type": "web_search_call",
                  "status": "completed",
                  "action": {
                    "type": "search",
                    "query": "HIMEHINA VAMPY 2026",
                    "sources": [
                      {"type": "url", "url": "https://music.apple.com/jp/album/vampy-ep/6833337851?utm_source=openai", "title": "Apple Music"},
                      {"type": "url", "url": "https://himehina.jp/post/post-468"}
                    ]
                  }
                },
                {
                  "type": "web_search_call",
                  "status": "completed",
                  "action": {
                    "type": "open_page",
                    "url": "https://himehina.jp/post/post-468"
                  }
                },
                {
                  "type": "message",
                  "content": [
                    {
                      "type": "output_text",
                      "text": "{\\"candidates\\":[{\\"title\\":\\"VAMPY\\"}]}",
                      "annotations": []
                    }
                  ]
                }
              ],
              "usage": {
                "input_tokens": 25000,
                "output_tokens": 1200,
                "total_tokens": 26200,
                "input_tokens_details": {"cached_tokens": 4096},
                "output_tokens_details": {"reasoning_tokens": 180}
              }
            }
            """, JsonObject.class);

        WebSearchClient.SearchResponse parsed = client.parseOpenAiResponse(0, "gpt-6-luna", response);
        assertEquals(2, parsed.getCitations().size());
        WebSearchClient.Citation apple = parsed.getCitations().get(0);
        assertEquals("https://music.apple.com/jp/album/vampy-ep/6833337851", apple.getUrl());
        assertEquals("HIGH", apple.getReliability());
        assertFalse(apple.isOpenedPage());

        WebSearchClient.Citation official = parsed.getCitations().get(1);
        assertEquals("https://himehina.jp/post/post-468", official.getUrl());
        assertTrue(official.isOpenedPage());

        assertEquals(25000, parsed.getTokenUsage().promptTokens());
        assertEquals(1200, parsed.getTokenUsage().completionTokens());
        assertEquals(4096, parsed.getTokenUsage().cachedTokens());
        assertEquals(180, parsed.getTokenUsage().reasoningTokens());
        assertEquals(26200, parsed.getTokenUsage().totalTokens());
    }

    @Test
    void degradesGracefullyOnHttp400WhenEndpointRejectsExtendedParameters() throws Exception {
        List<JsonObject> sentBodies = new ArrayList<>();
        NativeWebSearchClient client = new NativeWebSearchClient(config) {
            @Override
            JsonObject send(String url, String key, String provider, JsonObject body) {
                sentBodies.add(body.deepCopy());
                if (sentBodies.size() == 1) {
                    throw new BadRequestException("http 400: unknown parameter search_context_size");
                }
                if (sentBodies.size() == 2) {
                    throw new BadRequestException("http 400: unknown parameter include");
                }
                return gson.fromJson("""
                    {"output":[{"type":"message","content":[{"type":"output_text","text":"{\\"ok\\":true}"}]}]}
                    """, JsonObject.class);
            }
        };

        JsonObject out = client.sendOpenAiWithFallback(
            "https://example.com/v1/responses", "k", "gpt-test", "sys", "usr");
        assertNotNull(out);
        assertEquals(3, sentBodies.size());

        // 第 1 次包含 search_context_size 与 include
        assertTrue(sentBodies.get(0).getAsJsonArray("tools").get(0).getAsJsonObject().has("search_context_size"));
        assertTrue(sentBodies.get(0).has("include"));
        // 第 2 次移除 search_context_size，保留 include
        assertFalse(sentBodies.get(1).getAsJsonArray("tools").get(0).getAsJsonObject().has("search_context_size"));
        assertTrue(sentBodies.get(1).has("include"));
        // 第 3 次回退基础报文
        assertFalse(sentBodies.get(2).getAsJsonArray("tools").get(0).getAsJsonObject().has("search_context_size"));
        assertFalse(sentBodies.get(2).has("include"));
    }

    @Test
    void ignoresFailedToolCallsAndTargetsSingleParamDowngradeAndRedactsSecrets() throws Exception {
        NativeWebSearchClient parser = new NativeWebSearchClient(config);
        JsonObject respWithFailedCall = gson.fromJson("""
            {
              "output": [
                {
                  "type": "web_search_call",
                  "status": "failed",
                  "action": {"type": "open_page", "url": "https://ototoy.jp/failed-call"}
                },
                {
                  "type": "web_search_call",
                  "status": "completed",
                  "action": {"type": "open_page", "url": "https://ototoy.jp/ok-call"}
                },
                {"type": "message", "content": [{"type": "output_text", "text": "{\\"ok\\":true}"}]}
              ]
            }
            """, JsonObject.class);
        WebSearchClient.SearchResponse parsed = parser.parseOpenAiResponse(0, "gpt-test", respWithFailedCall);
        assertEquals(1, parsed.getCitations().size());
        assertEquals("https://ototoy.jp/ok-call", parsed.getCitations().get(0).getUrl());

        // 仅拒绝 include 时：只需重试 1 次并保留 search_context_size
        List<JsonObject> incOnlyBodies = new ArrayList<>();
        NativeWebSearchClient incOnlyClient = new NativeWebSearchClient(config) {
            @Override
            JsonObject send(String url, String key, String provider, JsonObject body) {
                incOnlyBodies.add(body.deepCopy());
                if (incOnlyBodies.size() == 1) {
                    throw BadRequestException.fromResponse(
                        "{\"error\":{\"type\":\"invalid_request_error\",\"param\":\"include\",\"message\":\"Unknown parameter include\"}}", gson);
                }
                return gson.fromJson("{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}", JsonObject.class);
            }
        };
        incOnlyClient.sendOpenAiWithFallback("https://example.com/v1/responses", "k", "gpt-test", "sys", "usr");
        assertEquals(2, incOnlyBodies.size());
        assertTrue(incOnlyBodies.get(1).getAsJsonArray("tools").get(0).getAsJsonObject().has("search_context_size"),
            "Must keep supported search_context_size when only include is rejected");
        assertFalse(incOnlyBodies.get(1).has("include"));

        // model_not_found 非扩展错误必须直接抛出，不浪费重试
        List<JsonObject> modelErrBodies = new ArrayList<>();
        NativeWebSearchClient modelErrClient = new NativeWebSearchClient(config) {
            @Override
            JsonObject send(String url, String key, String provider, JsonObject body) {
                modelErrBodies.add(body.deepCopy());
                throw BadRequestException.fromResponse(
                    "{\"error\":{\"code\":\"model_not_found\",\"param\":\"model\",\"message\":\"Bad key Bearer sk-secret123456789\"}}", gson);
            }
        };
        NativeWebSearchClient.BadRequestException thrown = assertThrows(NativeWebSearchClient.BadRequestException.class, () ->
            modelErrClient.sendOpenAiWithFallback("https://example.com/v1/responses", "k", "bad-model", "sys", "usr"));
        assertEquals(1, modelErrBodies.size());
        assertFalse(thrown.getMessage().contains("sk-secret123456789"), "Secrets must be redacted from error messages");
    }
}
