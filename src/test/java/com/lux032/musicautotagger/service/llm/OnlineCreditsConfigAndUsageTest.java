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
        assertTrue(cfg.isOnlineMbidResolveEnabled());
        assertEquals(15, cfg.getOnlineMbidMaxLookupsPerAlbum());
        assertTrue(cfg.isOnlineMbidLlmDisambiguateEnabled());

        // 验证 ConfigFieldSpec 注册齐全
        assertNotNull(ConfigFieldSpec.get("llm.webSearch.nativeContextSize"));
        assertNotNull(ConfigFieldSpec.get("online.credits.enrich.enabled"));
        assertNotNull(ConfigFieldSpec.get("online.credits.maxFollowUpRounds"));
        assertNotNull(ConfigFieldSpec.get("online.credits.maxSearchCalls"));
        assertNotNull(ConfigFieldSpec.get("online.credits.maxFetchPages"));
        assertNotNull(ConfigFieldSpec.get("online.credits.allowTavilyExtract"));
        assertNotNull(ConfigFieldSpec.get("online.credits.applyProbable"));
        assertNotNull(ConfigFieldSpec.get("online.mbid.resolve.enabled"));
        assertNotNull(ConfigFieldSpec.get("online.mbid.maxLookupsPerAlbum"));
        assertNotNull(ConfigFieldSpec.get("online.mbid.llmDisambiguate.enabled"));
        assertFalse(ConfigFieldSpec.requiresRestart("online.mbid.resolve.enabled"));
        assertFalse(ConfigFieldSpec.requiresRestart("online.mbid.maxLookupsPerAlbum"));
        assertFalse(ConfigFieldSpec.requiresRestart("online.mbid.llmDisambiguate.enabled"));

        // 合法值写盘再重读
        cfg.setLlmWebSearchNativeContextSize("medium");
        cfg.setOnlineCreditsEnrichEnabled(false);
        cfg.setOnlineCreditsMaxFollowUpRounds(3);
        cfg.setOnlineCreditsMaxSearchCalls(10);
        cfg.setOnlineCreditsMaxFetchPages(8);
        cfg.setOnlineCreditsAllowTavilyExtract(false);
        cfg.setOnlineCreditsApplyProbable(false);
        cfg.setOnlineMbidResolveEnabled(false);
        cfg.setOnlineMbidMaxLookupsPerAlbum(25);
        cfg.setOnlineMbidLlmDisambiguateEnabled(false);
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
        assertFalse(reloaded.isOnlineMbidResolveEnabled());
        assertEquals(25, reloaded.getOnlineMbidMaxLookupsPerAlbum());
        assertFalse(reloaded.isOnlineMbidLlmDisambiguateEnabled());

        // 越界值回退默认值
        Path badProps = dir.resolve("bad.properties");
        Files.writeString(badProps, """
            llm.webSearch.nativeContextSize=ultra
            online.credits.maxFollowUpRounds=99
            online.credits.maxSearchCalls=0
            online.credits.maxFetchPages=-5
            online.mbid.maxLookupsPerAlbum=999
            """);
        MusicConfig clamped = newConfig();
        clamped.loadFromFile(badProps);
        assertEquals("high", clamped.getLlmWebSearchNativeContextSize());
        assertEquals(2, clamped.getOnlineCreditsMaxFollowUpRounds());
        assertEquals(6, clamped.getOnlineCreditsMaxSearchCalls());
        assertEquals(6, clamped.getOnlineCreditsMaxFetchPages());
        assertEquals(15, clamped.getOnlineMbidMaxLookupsPerAlbum());

        // 缺省属性文件重载时恢复默认值
        Path emptyProps = dir.resolve("empty.properties");
        Files.writeString(emptyProps, "web.port=8080\n");
        reloaded.loadFromFile(emptyProps);
        assertTrue(reloaded.isOnlineMbidResolveEnabled());
        assertEquals(15, reloaded.getOnlineMbidMaxLookupsPerAlbum());
        assertTrue(reloaded.isOnlineMbidLlmDisambiguateEnabled());
    }

    @Test
    void configServletHotReloadsOnlineMbidOptionsWithoutRestart() throws Exception {
        Path configPath = dir.resolve("hot.properties");
        Files.writeString(configPath, "web.port=8080\n");
        MusicConfig cfg = newConfig();
        cfg.loadFromFile(configPath);
        com.lux032.musicautotagger.web.ConfigServlet servlet =
            new com.lux032.musicautotagger.web.ConfigServlet(cfg, configPath);

        java.lang.reflect.Method apply = com.lux032.musicautotagger.web.ConfigServlet.class
            .getDeclaredMethod("applyConfigMap", java.util.Map.class, boolean.class, boolean.class);
        apply.setAccessible(true);
        Object res = apply.invoke(servlet, java.util.Map.of(
            "onlineMbidResolveEnabled", Boolean.FALSE,
            "onlineMbidMaxLookupsPerAlbum", 30,
            "onlineMbidLlmDisambiguateEnabled", Boolean.FALSE
        ), false, false);
        java.lang.reflect.Method requiresRestart = res.getClass().getDeclaredMethod("requiresRestart");
        requiresRestart.setAccessible(true);
        assertFalse((Boolean) requiresRestart.invoke(res));
        assertFalse(cfg.isOnlineMbidResolveEnabled());
        assertEquals(30, cfg.getOnlineMbidMaxLookupsPerAlbum());
        assertFalse(cfg.isOnlineMbidLlmDisambiguateEnabled());

        java.util.Properties persisted = new java.util.Properties();
        try (var reader = Files.newBufferedReader(configPath)) {
            persisted.load(reader);
        }
        assertEquals("false", persisted.getProperty("online.mbid.resolve.enabled"));
        assertEquals("30", persisted.getProperty("online.mbid.maxLookupsPerAlbum"));
        assertEquals("false", persisted.getProperty("online.mbid.llmDisambiguate.enabled"));

        // 非法类型、小数与越界值必须抛出 ValidationException（对应 HTTP 400）
        for (java.util.Map<String, Object> badBody : java.util.List.<java.util.Map<String, Object>>of(
                java.util.Map.of("onlineMbidMaxLookupsPerAlbum", 12.5d),
                java.util.Map.of("onlineMbidMaxLookupsPerAlbum", -1),
                java.util.Map.of("onlineMbidMaxLookupsPerAlbum", 51),
                java.util.Map.of("onlineMbidMaxLookupsPerAlbum", "abc"),
                java.util.Map.of("onlineMbidResolveEnabled", 123),
                java.util.Map.of("onlineMbidLlmDisambiguateEnabled", "maybe"))) {
            java.lang.reflect.InvocationTargetException ite = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> apply.invoke(servlet, badBody, false, false),
                "Expected validation failure for " + badBody);
            assertEquals("ValidationException", ite.getCause().getClass().getSimpleName());
        }

        // 不可变快照与上界夹紧（0..50）保证跨线程一致性
        MusicConfig.OnlineMbidSettings snap = cfg.updateOnlineMbidSettings(true, 999, true);
        assertEquals(50, snap.maxLookupsPerAlbum());
        assertEquals(snap, cfg.getOnlineMbidSettings());
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
