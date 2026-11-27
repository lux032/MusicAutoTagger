package com.lux032.musicautotagger.web;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.util.I18nUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class MusicConfigPreferNativeArtistNameTest {

    @AfterEach
    void restoreDefaultLanguage() {
        I18nUtil.init("zh_CN");
    }

    private static MusicConfig newIsolatedConfig() throws Exception {
        Constructor<MusicConfig> constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    @Test
    void defaultsToTrueAndHonorsExplicitFalseRoundTrip(@TempDir Path tempDir) throws Exception {
        MusicConfig config = newIsolatedConfig();
        assertTrue(config.isPreferNativeArtistName());

        // Legacy config file without tag.artist.preferNativeName stays true
        Path legacyFile = tempDir.resolve("legacy.properties");
        Files.writeString(legacyFile, "web.port=8080\n", StandardCharsets.UTF_8);
        config.loadFromFile(legacyFile);
        assertTrue(config.isPreferNativeArtistName());

        // Explicit false loads and persists cleanly
        Path disabledFile = tempDir.resolve("disabled.properties");
        Files.writeString(disabledFile, "tag.artist.preferNativeName=false\n", StandardCharsets.UTF_8);
        config.loadFromFile(disabledFile);
        assertFalse(config.isPreferNativeArtistName());

        Path savedFile = tempDir.resolve("saved.properties");
        config.saveToFile(savedFile);
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(savedFile, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        assertEquals("false", props.getProperty("tag.artist.preferNativeName"));

        MusicConfig reloaded = newIsolatedConfig();
        reloaded.loadFromFile(savedFile);
        assertFalse(reloaded.isPreferNativeArtistName());

        // Reloading a legacy file missing tag.artist.preferNativeName on the SAME instance resets to default true
        reloaded.loadFromFile(legacyFile);
        assertTrue(reloaded.isPreferNativeArtistName());

        // setReleaseCountryPriority stores an immutable defensive copy
        java.util.List<String> mutable = new java.util.ArrayList<>(java.util.List.of("JP", "US"));
        reloaded.setReleaseCountryPriority(mutable);
        mutable.clear();
        assertEquals(java.util.List.of("JP", "US"), reloaded.getReleaseCountryPriority());
    }

    @Test
    void configServletHotReloadsPreferNativeArtistNameWithoutRestart(@TempDir Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("config.properties");
        Files.writeString(configPath, "web.port=8080\n", StandardCharsets.UTF_8);

        MusicConfig config = newIsolatedConfig();
        config.loadFromFile(configPath);
        ConfigServlet servlet = new ConfigServlet(config, configPath);

        assertEquals(Boolean.TRUE, servlet.snapshotConfig().get("preferNativeArtistName"));

        ConfigServlet.ApplyResult result = servlet.applyConfigMap(
            Map.of("preferNativeArtistName", Boolean.FALSE), false, false);
        assertTrue(result.updated());
        assertFalse(result.requiresRestart());
        assertFalse(config.isPreferNativeArtistName());
        assertEquals(Boolean.FALSE, servlet.snapshotConfig().get("preferNativeArtistName"));

        Properties persisted = new Properties();
        try (var reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            persisted.load(reader);
        }
        assertEquals("false", persisted.getProperty("tag.artist.preferNativeName"));

        // Re-enable hot-reload
        ConfigServlet.ApplyResult reEnabled = servlet.applyConfigMap(
            Map.of("preferNativeArtistName", Boolean.TRUE), false, false);
        assertTrue(reEnabled.updated());
        assertFalse(reEnabled.requiresRestart());
        assertTrue(config.isPreferNativeArtistName());
    }

    @Test
    void i18nLabelsExistInBothZhCnAndEnUs() {
        I18nUtil.init("zh_CN");
        assertEquals("东亚艺人优先使用原生文字显示名", I18nUtil.getMessage("config.artist.prefer.native"));

        I18nUtil.init("en_US");
        assertEquals("Prefer Native Script for East Asian Artists", I18nUtil.getMessage("config.artist.prefer.native"));
    }
}
