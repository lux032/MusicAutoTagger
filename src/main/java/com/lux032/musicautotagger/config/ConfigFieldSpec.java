package com.lux032.musicautotagger.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 声明式配置字段元数据注册表（单一事实源）。
 * 集中描述各配置项的 properties 键名、默认值、数值边界、是否只读（防 Web API 篡改）以及是否需重启生效，
 * 避免 MusicConfig、ConfigServlet 与模板文件之间发生配置漂移。
 */
public final class ConfigFieldSpec {

    public record Spec(String propertyKey,
                       String defaultValue,
                       boolean readOnly,
                       boolean requiresRestart,
                       Double minValue,
                       Double maxValue) {
        public boolean hasRange() {
            return minValue != null && maxValue != null;
        }

        public boolean isWithinRange(double value) {
            if (!Double.isFinite(value)) return false;
            if (minValue != null && value < minValue) return false;
            if (maxValue != null && value > maxValue) return false;
            return true;
        }
    }

    private static final Map<String, Spec> SPECS;

    static {
        Map<String, Spec> map = new LinkedHashMap<>();
        register(map, "web.port", "8080", false, true, 1.0, 65535.0);
        register(map, "monitor.directory", "", false, true, null, null);
        register(map, "monitor.outputDirectory", "", false, true, null, null);
        register(map, "monitor.scanInterval", "30", false, true, 1.0, 86400.0);
        register(map, "file.maxRetries", "3", false, false, 0.0, 20.0);
        register(map, "cover.preferAnimeEdition", "false", false, false, null, null);
        register(map, "cover.animeEditionMaxCandidates", "5", false, false, 1.0, 20.0);
        register(map, "release.countryPriority", "", false, false, null, null);
        register(map, "db.type", "sqlite", false, true, null, null);
        register(map, "db.sqlite.path", "data/music-tagger.db", true, true, null, null);
        register(map, "db.mysql.port", "3306", false, true, 1.0, 65535.0);
        register(map, "proxy.port", "0", false, true, 0.0, 65535.0);
        register(map, "audio.normalize.ffmpegPath", "ffmpeg", true, true, null, null);
        register(map, "llm.allowPrivateEndpoints", "false", true, true, null, null);
        register(map, "llm.maxTokens", "600", false, false, 1.0, 32000.0);
        register(map, "llm.temperature", "0.0", false, false, 0.0, 2.0);
        register(map, "llm.timeoutSeconds", "60", false, false, 5.0, 600.0);
        register(map, "llm.maxRetries", "2", false, false, 0.0, 10.0);
        register(map, "llm.webSearch.maxTokens", "4000", false, false, 256.0, 32000.0);
        register(map, "llm.webSearch.timeoutSeconds", "180", false, false, 30.0, 900.0);
        register(map, "llm.webSearch.maxConcurrency", "1", false, false, 1.0, 3.0);
        register(map, "llm.webSearch.nativeContextSize", "high", false, false, null, null);
        register(map, "online.credits.enrich.enabled", "true", false, false, null, null);
        register(map, "online.credits.maxFollowUpRounds", "2", false, false, 0.0, 3.0);
        register(map, "online.credits.maxSearchCalls", "6", false, false, 1.0, 12.0);
        register(map, "online.credits.maxFetchPages", "6", false, false, 0.0, 12.0);
        register(map, "online.credits.allowTavilyExtract", "true", false, false, null, null);
        register(map, "online.credits.applyProbable", "true", false, false, null, null);
        register(map, "online.mbid.resolve.enabled", "true", false, false, null, null);
        register(map, "online.mbid.maxLookupsPerAlbum", "15", false, false, 0.0, 50.0);
        register(map, "online.mbid.llmDisambiguate.enabled", "true", false, false, null, null);
        register(map, "llm.album.autoApplyMinConfidence", "0.85", false, false, 0.0, 1.0);
        register(map, "file.partial.minTagCoverage", "0.8", false, false, 0.0, 1.0);
        register(map, "review.queuePath", "data/review-queue.json", true, true, null, null);
        register(map, "review.stagingDirectory", "data/review-staging", true, true, null, null);
        register(map, "recovery.workDirectory", "", true, true, null, null);
        register(map, "recovery.trashDirectory", "data/recovery-trash", true, true, null, null);
        register(map, "recovery.trash.retentionDays", "7", false, false, -1.0, 365.0);
        SPECS = Collections.unmodifiableMap(map);
    }

    private ConfigFieldSpec() {
    }

    private static void register(Map<String, Spec> map, String key, String defaultVal,
                                 boolean readOnly, boolean requiresRestart,
                                 Double min, Double max) {
        map.put(key, new Spec(key, defaultVal, readOnly, requiresRestart, min, max));
    }

    public static Spec get(String propertyKey) {
        return SPECS.get(propertyKey);
    }

    public static Map<String, Spec> all() {
        return SPECS;
    }

    public static boolean isReadOnly(String propertyKey) {
        Spec spec = SPECS.get(propertyKey);
        return spec != null && spec.readOnly();
    }

    public static boolean requiresRestart(String propertyKey) {
        Spec spec = SPECS.get(propertyKey);
        return spec != null && spec.requiresRestart();
    }
}
