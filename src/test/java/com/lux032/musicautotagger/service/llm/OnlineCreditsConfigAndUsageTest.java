package com.lux032.musicautotagger.service.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.ConfigFieldSpec;
import com.lux032.musicautotagger.config.MusicConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OnlineCreditsConfigAndUsageTest {

    @TempDir
    Path dir;

    private MusicConfig newConfig() throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    @Test
    void musicConfigDefaultsLoadSaveAndClampOnlineCreditsOptions() throws Exception {
        MusicConfig cfg = newConfig();
        assertEquals("high", cfg.getLlmWebSearchNativeContextSize());
        assertTrue(cfg.isOnlineCreditsEnrichEnabled());
        assertEquals(2, cfg.getOnlineCreditsMaxFollowUpRounds());
        assertEquals(6, cfg.getOnlineCreditsMaxSearchCalls());
        assertEquals(6, cfg.getOnlineCreditsMaxFetchPages());
        assertTrue(cfg.isOnlineCreditsAllowTavilyExtract());
        assertTrue(cfg.isOnlineCreditsApplyProbable());

        // 验证 ConfigFieldSpec 注册齐全
        assertNotNull(ConfigFieldSpec.get("llm.webSearch.nativeContextSize"));
        assertNotNull(ConfigFieldSpec.get("online.credits.enrich.enabled"));
        assertNotNull(ConfigFieldSpec.get("online.credits.maxFollowUpRounds"));
        assertNotNull(ConfigFieldSpec.get("online.credits.maxSearchCalls"));
        assertNotNull(ConfigFieldSpec.get("online.credits.maxFetchPages"));
        assertNotNull(ConfigFieldSpec.get("online.credits.allowTavilyExtract"));
        assertNotNull(ConfigFieldSpec.get("online.credits.applyProbable"));

        // 合法值写盘再重读
        cfg.setLlmWebSearchNativeContextSize("medium");
        cfg.setOnlineCreditsEnrichEnabled(false);
        cfg.setOnlineCreditsMaxFollowUpRounds(3);
        cfg.setOnlineCreditsMaxSearchCalls(10);
        cfg.setOnlineCreditsMaxFetchPages(8);
        cfg.setOnlineCreditsAllowTavilyExtract(false);
        cfg.setOnlineCreditsApplyProbable(false);
        Path propsFile = dir.resolve("config.properties");
        cfg.saveToFile(propsFile);

        MusicConfig reloaded = newConfig();
        reloaded.loadFromFile(propsFile);
        assertEquals("medium", reloaded.getLlmWebSearchNativeContextSize());
        assertFalse(reloaded.isOnlineCreditsEnrichEnabled());
        assertEquals(3, reloaded.getOnlineCreditsMaxFollowUpRounds());
        assertEquals(10, reloaded.getOnlineCreditsMaxSearchCalls());
        assertEquals(8, reloaded.getOnlineCreditsMaxFetchPages());
        assertFalse(reloaded.isOnlineCreditsAllowTavilyExtract());
        assertFalse(reloaded.isOnlineCreditsApplyProbable());

        // 越界值回退默认值
        Path badProps = dir.resolve("bad.properties");
        Files.writeString(badProps, """
            llm.webSearch.nativeContextSize=ultra
            online.credits.maxFollowUpRounds=99
            online.credits.maxSearchCalls=0
            online.credits.maxFetchPages=-5
            """);
        MusicConfig clamped = newConfig();
        clamped.loadFromFile(badProps);
        assertEquals("high", clamped.getLlmWebSearchNativeContextSize());
        assertEquals(2, clamped.getOnlineCreditsMaxFollowUpRounds());
        assertEquals(6, clamped.getOnlineCreditsMaxSearchCalls());
        assertEquals(6, clamped.getOnlineCreditsMaxFetchPages());
    }

    @Test
    void llmProvidersExtractOpenAiAndAnthropicTokenUsage() {
        Gson gson = new Gson();
        JsonObject openAiJson = gson.fromJson("""
            {
              "choices": [{"message": {"content": "{\\"ok\\":true}"}}],
              "usage": {
                "prompt_tokens": 1200,
                "completion_tokens": 350,
                "total_tokens": 1550,
                "prompt_tokens_details": {"cached_tokens": 512},
                "completion_tokens_details": {"reasoning_tokens": 48}
              }
            }
            """, JsonObject.class);

        LlmProvider openAi = new LlmProvider.OpenAiCompatible();
        LlmClient.TokenUsage u1 = openAi.extractUsage(openAiJson);
        assertEquals(1200, u1.promptTokens());
        assertEquals(350, u1.completionTokens());
        assertEquals(512, u1.cachedTokens());
        assertEquals(48, u1.reasoningTokens());
        assertEquals(1550, u1.totalTokens());

        JsonObject anthropicJson = gson.fromJson("""
            {
              "content": [{"type": "text", "text": "hello"}],
              "usage": {
                "input_tokens": 800,
                "cache_creation_input_tokens": 100,
                "cache_read_input_tokens": 300,
                "output_tokens": 200
              }
            }
            """, JsonObject.class);
        LlmProvider anthropic = new LlmProvider.Anthropic();
        LlmClient.TokenUsage u2 = anthropic.extractUsage(anthropicJson);
        assertEquals(1200, u2.promptTokens(), "Anthropic promptTokens must include uncached + cache_creation + cache_read");
        assertEquals(200, u2.completionTokens());
        assertEquals(300, u2.cachedTokens());
        assertEquals(0, u2.reasoningTokens());
        assertEquals(1400, u2.totalTokens());

        LlmClient.TokenUsage sum = u1.plus(u2);
        assertEquals(2400, sum.promptTokens());
        assertEquals(550, sum.completionTokens());
        assertEquals(812, sum.cachedTokens());
        assertEquals(2950, sum.totalTokens());

        // 缺少 usage 时安全回退全 0
        assertEquals(LlmClient.TokenUsage.empty(), openAi.extractUsage(new JsonObject()));
    }
}
