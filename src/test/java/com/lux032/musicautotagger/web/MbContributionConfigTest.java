package com.lux032.musicautotagger.web;

import com.lux032.musicautotagger.config.ConfigFieldSpec;
import com.lux032.musicautotagger.config.MusicConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MbContributionConfigTest {

    private static MusicConfig newConfig() throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    @Test
    void loadsSavesAndHotReloadsContributionAndAcoustIdUserKeyConfig(@TempDir Path tempDir) throws Exception {
        Path props = tempDir.resolve("config.properties");
        Files.writeString(props, """
            monitor.directory=%s
            monitor.outputDirectory=%s
            acoustid.apiKey=app-key-123
            acoustid.userApiKey=user-secret-999
            mb.contribution.enabled=false
            mb.contribution.autoCheckAfterArchive=false
            """.formatted(tempDir.resolve("in"), tempDir.resolve("out")));

        MusicConfig config = newConfig();
        config.loadFromFile(props);
        assertFalse(config.isMbContributionEnabled());
        assertFalse(config.isMbContributionAutoCheckAfterArchive());
        assertEquals("user-secret-999", config.getAcoustIdUserApiKey());
        assertEquals("https://api.acoustid.org/v2/submit", config.getAcoustIdSubmitApiUrl());
        assertFalse(config.toString().contains("user-secret-999"), "acoustIdUserApiKey 不得泄露到 toString");

        assertNotNull(ConfigFieldSpec.get("mb.contribution.enabled"));
        assertNotNull(ConfigFieldSpec.get("mb.contribution.autoCheckAfterArchive"));
        assertNotNull(ConfigFieldSpec.get("acoustid.userApiKey"));
        assertFalse(ConfigFieldSpec.requiresRestart("mb.contribution.enabled"));
        assertFalse(ConfigFieldSpec.requiresRestart("acoustid.userApiKey"));

        ConfigServlet servlet = new ConfigServlet(config, props);
        Map<String, Object> snap = servlet.snapshotConfig();
        assertEquals("********", snap.get("acoustIdUserApiKey"));
        assertEquals(Boolean.TRUE, snap.get("acoustIdUserApiKeySet"));

        // 掩码占位符保持原密钥不变，同时热更新两个开关
        ConfigServlet.ApplyResult keepMask = servlet.applyConfigMap(Map.of(
            "mbContributionEnabled", true,
            "mbContributionAutoCheckAfterArchive", true,
            "acoustIdUserApiKey", "********"
        ), false, true);
        assertTrue(keepMask.updated());
        assertFalse(keepMask.requiresRestart());
        assertTrue(config.isMbContributionEnabled());
        assertTrue(config.isMbContributionAutoCheckAfterArchive());
        assertEquals("user-secret-999", config.getAcoustIdUserApiKey());

        // 修改 submit URL 时若仍沿用掩码密钥，应被拒绝以防密钥外泄
        assertThrows(Exception.class, () -> servlet.applyConfigMap(Map.of(
            "acoustIdSubmitApiUrl", "https://evil.example.com/submit",
            "acoustIdUserApiKey", "********"
        ), false, true));

        // 显式清空 acoustIdUserApiKey
        ConfigServlet.ApplyResult clearKey = servlet.applyConfigMap(Map.of(
            "acoustIdUserApiKey", ""
        ), false, true);
        assertTrue(clearKey.updated());
        assertNull(config.getAcoustIdUserApiKey());
        assertEquals(Boolean.FALSE, servlet.snapshotConfig().get("acoustIdUserApiKeySet"));

        // 重新设置并通过 saveToFile / loadFromFile 往返校验
        servlet.applyConfigMap(Map.of(
            "acoustIdUserApiKey", "new-user-key-777",
            "mbContributionAutoCheckAfterArchive", false
        ), false, true);
        assertEquals("new-user-key-777", config.getAcoustIdUserApiKey());

        MusicConfig reloaded = newConfig();
        reloaded.loadFromFile(props);
        assertTrue(reloaded.isMbContributionEnabled());
        assertFalse(reloaded.isMbContributionAutoCheckAfterArchive());
        assertEquals("new-user-key-777", reloaded.getAcoustIdUserApiKey());
    }
}
