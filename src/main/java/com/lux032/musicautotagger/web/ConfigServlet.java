package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import com.lux032.musicautotagger.config.MusicConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConfigServlet extends HttpServlet {

    private static final String SESSION_CSRF_KEY = "csrfToken";
    private static final Logger log = LoggerFactory.getLogger(ConfigServlet.class);

    /**
     * 返回给前端的密钥占位符
     * 前端每次保存都会把读到的值原样回传,所以占位符必须能被识别为「不修改」
     */
    private static final String SECRET_MASK = "********";

    /**
     * 列表型密钥的占位符前缀,后面跟下标(如 {@code ********#0})
     * 带下标是为了在用户删除或调整 LLM 配置行之后,仍能把占位符准确还原成对应的那把 key,
     * 否则按位置还原会把 key 配到错误的行上
     */

    private final MusicConfig config;
    private final Gson gson;
    private final Path configPath;
    private final Map<String, String> propertyKeys;
    private final Set<String> absolutePathFields;
    private final Set<String> allowedLanguages;
    private final Set<String> allowedDbTypes;
    private final Set<String> secretFields;

    public ConfigServlet(MusicConfig config) {
        this.config = config;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.configPath = Paths.get("config.properties");
        this.propertyKeys = Map.ofEntries(
            Map.entry("monitorDirectory", "monitor.directory"),
            Map.entry("outputDirectory", "monitor.outputDirectory"),
            Map.entry("scanIntervalSeconds", "monitor.scanInterval"),
            Map.entry("musicBrainzApiUrl", "musicbrainz.apiUrl"),
            Map.entry("coverArtApiUrl", "musicbrainz.coverArtApiUrl"),
            Map.entry("userAgent", "musicbrainz.userAgent"),
            Map.entry("acoustIdApiKey", "acoustid.apiKey"),
            Map.entry("acoustIdApiUrl", "acoustid.apiUrl"),
            Map.entry("supportedFormats", "file.supportedFormats"),
            Map.entry("autoRename", "file.autoRename"),
            Map.entry("createBackup", "file.createBackup"),
            Map.entry("failedDirectory", "file.failedDirectory"),
            Map.entry("partialDirectory", "file.partialDirectory"),
            Map.entry("maxRetries", "file.maxRetries"),
            Map.entry("enableDetailedLogging", "logging.detailed"),
            Map.entry("processedFileLogPath", "logging.processedFileLogPath"),
            Map.entry("coverArtCacheDirectory", "cache.coverArtDirectory"),
            Map.entry("preferAnimeCover", "cover.preferAnimeEdition"),
            Map.entry("animeCoverKeywords", "cover.animeEditionKeywords"),
            Map.entry("animeCoverMaxCandidates", "cover.animeEditionMaxCandidates"),
            Map.entry("dbType", "db.type"),
            Map.entry("dbHost", "db.mysql.host"),
            Map.entry("dbPort", "db.mysql.port"),
            Map.entry("dbDatabase", "db.mysql.database"),
            Map.entry("dbUsername", "db.mysql.username"),
            Map.entry("dbPassword", "db.mysql.password"),
            Map.entry("dbMaxPoolSize", "db.mysql.pool.maxPoolSize"),
            Map.entry("dbMinIdle", "db.mysql.pool.minIdle"),
            Map.entry("dbConnectionTimeout", "db.mysql.pool.connectionTimeout"),
            Map.entry("proxyEnabled", "proxy.enabled"),
            Map.entry("proxyHost", "proxy.host"),
            Map.entry("proxyPort", "proxy.port"),
            Map.entry("proxyUsername", "proxy.username"),
            Map.entry("proxyPassword", "proxy.password"),
            Map.entry("language", "i18n.language"),
            Map.entry("exportLyricsToFile", "lyrics.exportToFile"),
            Map.entry("audioNormalizeEnabled", "audio.normalize.enabled"),
            Map.entry("audioNormalizeFfmpegPath", "audio.normalize.ffmpegPath"),
            Map.entry("cueSplitEnabled", "cue.split.enabled"),
            Map.entry("cueSplitOutputDir", "cue.split.outputDir"),
            Map.entry("releaseCountryPriority", "release.countryPriority"),
            Map.entry("reviewEnabled", "review.enabled"),
            Map.entry("enableLLMMatching", "llm.matching.enabled"),
            // 供应商 / 模型 / 协议 已移到 /api/llm/providers（存 llm-providers.json），
            // 这里不再接管，否则两套存储会写出分叉
            Map.entry("llmAllowPrivateEndpoints", "llm.allowPrivateEndpoints"),
            Map.entry("llmMaxTokens", "llm.maxTokens"),
            Map.entry("llmTemperature", "llm.temperature"),
            Map.entry("llmTimeoutSeconds", "llm.timeoutSeconds"),
            Map.entry("llmMaxRetries", "llm.maxRetries"),
            Map.entry("llmWebSearchMaxTokens", "llm.webSearch.maxTokens"),
            Map.entry("llmWebSearchTimeoutSeconds", "llm.webSearch.timeoutSeconds"),
            Map.entry("webSearchProvider", "llm.webSearch.provider"),
            Map.entry("tavilyApiKey", "tavily.apiKey"),
            Map.entry("tavilyApiUrl", "tavily.apiUrl"),
            Map.entry("tavilySearchDepth", "tavily.searchDepth"),
            Map.entry("tavilyMaxResults", "tavily.maxResults"),
            Map.entry("tavilyTimeoutSeconds", "tavily.timeoutSeconds"),
            Map.entry("tavilyIncludeDomains", "tavily.includeDomains"),
            Map.entry("llmAlbumJudgeEnabled", "llm.album.judge.enabled"),
            Map.entry("llmAlbumAutoApply", "llm.album.autoApply"),
            Map.entry("llmAlbumAutoApplyMinConfidence", "llm.album.autoApplyMinConfidence"),
            Map.entry("partialRequireReadableTags", "file.partial.requireReadableTags"),
            Map.entry("partialMinTagCoverage", "file.partial.minTagCoverage"),
            Map.entry("reviewQueuePath", "review.queuePath"),
            Map.entry("reviewStagingDirectory", "review.stagingDirectory"),
            Map.entry("recoveryWorkDirectory", "recovery.workDirectory"),
            Map.entry("recoveryTrashDirectory", "recovery.trashDirectory"),
            Map.entry("recoveryTrashRetentionDays", "recovery.trash.retentionDays")
        );
        this.absolutePathFields = Set.of(
            "monitorDirectory",
            "outputDirectory",
            "failedDirectory",
            "partialDirectory",
            "processedFileLogPath",
            "coverArtCacheDirectory",
            "cueSplitOutputDir"
            // recoveryWorkDirectory / recoveryTrashDirectory 不列入：
            // 默认值 data/recovery-trash 是相对路径，强制绝对路径会让默认值无法原样回写
        );
        this.allowedLanguages = Set.of("zh_CN", "en_US");
        this.allowedDbTypes = Set.of("file", "mysql");
        // 这些字段不以明文返回给前端: 一旦有会话被拿到,否则等于所有凭据一次性泄露
        this.secretFields = Set.of(
            "dbPassword",
            "proxyPassword",
            "acoustIdApiKey",
            "tavilyApiKey"
        );
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> payload = new HashMap<>();
        payload.put("config", currentConfig());

        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().write(gson.toJson(payload));
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isCsrfValid(req)) {
            respondJson(resp, HttpServletResponse.SC_FORBIDDEN, Map.of("error", "csrf.invalid"));
            return;
        }

        Map<String, Object> body = readBodyAsMap(req);
        Map<String, Object> updates = new LinkedHashMap<>();
        Map<String, String> propertyUpdates = new LinkedHashMap<>();

        handleString(body, updates, propertyUpdates, "monitorDirectory", true);
        handleString(body, updates, propertyUpdates, "outputDirectory", true);
        handleInteger(body, updates, propertyUpdates, "scanIntervalSeconds");
        handleString(body, updates, propertyUpdates, "musicBrainzApiUrl", false);
        handleString(body, updates, propertyUpdates, "coverArtApiUrl", false);
        handleString(body, updates, propertyUpdates, "userAgent", false);
        handleString(body, updates, propertyUpdates, "acoustIdApiKey", false);
        handleString(body, updates, propertyUpdates, "acoustIdApiUrl", false);
        handleString(body, updates, propertyUpdates, "supportedFormats", false);
        handleBoolean(body, updates, propertyUpdates, "autoRename");
        handleBoolean(body, updates, propertyUpdates, "createBackup");
        handleString(body, updates, propertyUpdates, "failedDirectory", false);
        handleString(body, updates, propertyUpdates, "partialDirectory", false);
        handleInteger(body, updates, propertyUpdates, "maxRetries");
        handleBoolean(body, updates, propertyUpdates, "enableDetailedLogging");
        handleString(body, updates, propertyUpdates, "processedFileLogPath", false);
        handleString(body, updates, propertyUpdates, "coverArtCacheDirectory", false);
        handleBoolean(body, updates, propertyUpdates, "preferAnimeCover");
        handleString(body, updates, propertyUpdates, "animeCoverKeywords", false);
        handleIntegerInRange(body, updates, propertyUpdates, "animeCoverMaxCandidates", 1, 20);
        handleString(body, updates, propertyUpdates, "dbType", false);
        handleString(body, updates, propertyUpdates, "dbHost", false);
        handleInteger(body, updates, propertyUpdates, "dbPort");
        handleString(body, updates, propertyUpdates, "dbDatabase", false);
        handleString(body, updates, propertyUpdates, "dbUsername", false);
        handleString(body, updates, propertyUpdates, "dbPassword", false);
        handleInteger(body, updates, propertyUpdates, "dbMaxPoolSize");
        handleInteger(body, updates, propertyUpdates, "dbMinIdle");
        handleLong(body, updates, propertyUpdates, "dbConnectionTimeout");
        handleBoolean(body, updates, propertyUpdates, "proxyEnabled");
        handleString(body, updates, propertyUpdates, "proxyHost", false);
        handleInteger(body, updates, propertyUpdates, "proxyPort");
        handleString(body, updates, propertyUpdates, "proxyUsername", false);
        handleString(body, updates, propertyUpdates, "proxyPassword", false);
        handleString(body, updates, propertyUpdates, "language", false);
        handleBoolean(body, updates, propertyUpdates, "exportLyricsToFile");
        handleBoolean(body, updates, propertyUpdates, "audioNormalizeEnabled");
        // audioNormalizeFfmpegPath 会被直接当作可执行文件启动
        // (见 AudioFormatNormalizer.runFfmpeg 与 CueSplitService.splitTracks),
        // 若允许从 Web 修改,等于把「拿到一个会话」直接升级成「以容器身份任意命令执行」。
        // 该项改为只读: 仅能通过 config.properties 修改,改完需重启。
        rejectIfChanged(body, "audioNormalizeFfmpegPath", config.getAudioNormalizeFfmpegPath());
        handleBoolean(body, updates, propertyUpdates, "cueSplitEnabled");
        handleString(body, updates, propertyUpdates, "cueSplitOutputDir", false);
        handleString(body, updates, propertyUpdates, "releaseCountryPriority", false);
        // 阶段六：待人工确认开关（队列路径 / 暂存目录仍为只读，只能改 config.properties）
        handleBoolean(body, updates, propertyUpdates, "reviewEnabled");
        handleBoolean(body, updates, propertyUpdates, "enableLLMMatching");
        handleBoolean(body, updates, propertyUpdates, "llmAllowPrivateEndpoints");
        handleInteger(body, updates, propertyUpdates, "llmMaxTokens");
        handleDouble(body, updates, propertyUpdates, "llmTemperature");
        handleInteger(body, updates, propertyUpdates, "llmTimeoutSeconds");
        handleInteger(body, updates, propertyUpdates, "llmMaxRetries");
        handleInteger(body, updates, propertyUpdates, "llmWebSearchMaxTokens");
        handleInteger(body, updates, propertyUpdates, "llmWebSearchTimeoutSeconds");
        handleString(body, updates, propertyUpdates, "webSearchProvider", false);
        handleString(body, updates, propertyUpdates, "tavilyApiKey", false);
        handleString(body, updates, propertyUpdates, "tavilyApiUrl", false);
        handleString(body, updates, propertyUpdates, "tavilySearchDepth", false);
        handleInteger(body, updates, propertyUpdates, "tavilyMaxResults");
        handleInteger(body, updates, propertyUpdates, "tavilyTimeoutSeconds");
        handleString(body, updates, propertyUpdates, "tavilyIncludeDomains", false);
        handleBoolean(body, updates, propertyUpdates, "llmAlbumJudgeEnabled");
        handleBoolean(body, updates, propertyUpdates, "llmAlbumAutoApply");
        handleDouble(body, updates, propertyUpdates, "llmAlbumAutoApplyMinConfidence");
        handleBoolean(body, updates, propertyUpdates, "partialRequireReadableTags");
        handleDouble(body, updates, propertyUpdates, "partialMinTagCoverage");
        handleString(body, updates, propertyUpdates, "reviewQueuePath", false);
        handleString(body, updates, propertyUpdates, "reviewStagingDirectory", false);
        handleString(body, updates, propertyUpdates, "recoveryWorkDirectory", false);
        handleString(body, updates, propertyUpdates, "recoveryTrashDirectory", false);
        handleInteger(body, updates, propertyUpdates, "recoveryTrashRetentionDays");

        if (updates.isEmpty()) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "no.updates"));
            return;
        }

        try {
            persistUpdates(propertyUpdates);
            applyUpdates(updates);
        } catch (IOException e) {
            log.error("Failed to save config", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "save.failed");
            if (isConfigDebugEnabled()) {
                error.put("detail", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            respondJson(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, error);
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("config", currentConfig());
        payload.put("updated", true);
        payload.put("requiresRestart", true);
        respondJson(resp, HttpServletResponse.SC_OK, payload);
    }

    private Map<String, Object> currentConfig() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("monitorDirectory", config.getMonitorDirectory());
        data.put("outputDirectory", config.getOutputDirectory());
        data.put("scanIntervalSeconds", config.getScanIntervalSeconds());
        data.put("musicBrainzApiUrl", config.getMusicBrainzApiUrl());
        data.put("coverArtApiUrl", config.getCoverArtApiUrl());
        data.put("userAgent", config.getUserAgent());
        data.put("acoustIdApiKey", maskSecret(config.getAcoustIdApiKey()));
        data.put("acoustIdApiKeySet", isSet(config.getAcoustIdApiKey()));
        data.put("acoustIdApiUrl", config.getAcoustIdApiUrl());
        data.put("supportedFormats", config.getSupportedFormats() == null ? null : String.join(",", config.getSupportedFormats()));
        data.put("autoRename", config.isAutoRename());
        data.put("createBackup", config.isCreateBackup());
        data.put("failedDirectory", config.getFailedDirectory());
        data.put("partialDirectory", config.getPartialDirectory());
        data.put("maxRetries", config.getMaxRetries());
        data.put("enableDetailedLogging", config.isEnableDetailedLogging());
        data.put("processedFileLogPath", config.getProcessedFileLogPath());
        data.put("coverArtCacheDirectory", config.getCoverArtCacheDirectory());
        data.put("preferAnimeCover", config.isPreferAnimeCover());
        data.put("animeCoverKeywords", config.getAnimeCoverKeywords() == null ? ""
            : String.join(",", config.getAnimeCoverKeywords()));
        data.put("animeCoverMaxCandidates", config.getAnimeCoverMaxCandidates());
        data.put("dbType", config.getDbType());
        data.put("dbHost", config.getDbHost());
        data.put("dbPort", config.getDbPort());
        data.put("dbDatabase", config.getDbDatabase());
        data.put("dbUsername", config.getDbUsername());
        data.put("dbPassword", maskSecret(config.getDbPassword()));
        data.put("dbPasswordSet", isSet(config.getDbPassword()));
        data.put("dbMaxPoolSize", config.getDbMaxPoolSize());
        data.put("dbMinIdle", config.getDbMinIdle());
        data.put("dbConnectionTimeout", config.getDbConnectionTimeout());
        data.put("proxyEnabled", config.isProxyEnabled());
        data.put("proxyHost", config.getProxyHost());
        data.put("proxyPort", config.getProxyPort());
        data.put("proxyUsername", config.getProxyUsername());
        data.put("proxyPassword", maskSecret(config.getProxyPassword()));
        data.put("proxyPasswordSet", isSet(config.getProxyPassword()));
        data.put("language", config.getLanguage());
        data.put("exportLyricsToFile", config.isExportLyricsToFile());
        data.put("audioNormalizeEnabled", config.isAudioNormalizeEnabled());
        data.put("audioNormalizeFfmpegPath", config.getAudioNormalizeFfmpegPath());
        data.put("cueSplitEnabled", config.isCueSplitEnabled());
        data.put("cueSplitOutputDir", config.getCueSplitOutputDir());
        data.put("reviewEnabled", config.isReviewEnabled());
        data.put("releaseCountryPriority", config.getReleaseCountryPriority() == null || config.getReleaseCountryPriority().isEmpty()
            ? null
            : String.join(",", config.getReleaseCountryPriority()));
        data.put("enableLLMMatching", config.isEnableLLMMatching());
        data.put("llmAllowPrivateEndpoints", config.isLlmAllowPrivateEndpoints());
        data.put("llmMaxTokens", config.getLlmMaxTokens());
        data.put("llmTemperature", config.getLlmTemperature());
        data.put("llmTimeoutSeconds", config.getLlmTimeoutSeconds());
        data.put("llmMaxRetries", config.getLlmMaxRetries());
        data.put("llmWebSearchMaxTokens", config.getLlmWebSearchMaxTokens());
        data.put("llmWebSearchTimeoutSeconds", config.getLlmWebSearchTimeoutSeconds());
        data.put("webSearchProvider", config.getWebSearchProvider());
        data.put("tavilyApiKey", maskSecret(config.getTavilyApiKey()));
        data.put("tavilyApiUrl", config.getTavilyApiUrl());
        data.put("tavilySearchDepth", config.getTavilySearchDepth());
        data.put("tavilyMaxResults", config.getTavilyMaxResults());
        data.put("tavilyTimeoutSeconds", config.getTavilyTimeoutSeconds());
        data.put("tavilyIncludeDomains", config.getTavilyIncludeDomains() == null ? ""
            : String.join(",", config.getTavilyIncludeDomains()));
        data.put("llmAlbumJudgeEnabled", config.isLlmAlbumJudgeEnabled());
        data.put("llmAlbumAutoApply", config.isLlmAlbumAutoApply());
        data.put("llmAlbumAutoApplyMinConfidence", config.getLlmAlbumAutoApplyMinConfidence());
        data.put("partialRequireReadableTags", config.isPartialRequireReadableTags());
        data.put("partialMinTagCoverage", config.getPartialMinTagCoverage());
        data.put("reviewQueuePath", config.getReviewQueuePath());
        data.put("reviewStagingDirectory", config.getReviewStagingDirectory());
        data.put("recoveryWorkDirectory", config.getRecoveryWorkDirectory());
        data.put("recoveryTrashDirectory", config.getRecoveryTrashDirectory());
        data.put("recoveryTrashRetentionDays", config.getRecoveryTrashRetentionDays());
        return data;
    }

    private void persistUpdates(Map<String, String> updates) throws IOException {
        java.util.Properties props = new java.util.Properties();
        if (Files.exists(configPath)) {
            // 与 MusicConfig.loadFromFile 保持一致: 按 UTF-8 读，否则回写时会把
            // 配置里的日文关键词读成乱码并持久化
            try (java.io.Reader reader = new java.io.InputStreamReader(
                    new FileInputStream(configPath.toFile()), java.nio.charset.StandardCharsets.UTF_8)) {
                props.load(reader);
            }
        }

        for (Map.Entry<String, String> entry : updates.entrySet()) {
            if (entry.getValue() == null) {
                props.remove(entry.getKey());
            } else {
                props.setProperty(entry.getKey(), entry.getValue());
            }
        }
        try {
            writeConfigAtomically(props);
        } catch (IOException e) {
            log.warn("Atomic config save failed, attempting direct write", e);
            try {
                writeConfigDirectly(props);
            } catch (IOException directError) {
                directError.addSuppressed(e);
                throw directError;
            }
        }
    }

    private void writeConfigAtomically(java.util.Properties props) throws IOException {
        Path tempPath = configPath.resolveSibling("config.properties.tmp");
        try (FileOutputStream fos = new FileOutputStream(tempPath.toFile())) {
            props.store(fos, "Updated by web panel");
        }

        try {
            Files.move(tempPath, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(tempPath, configPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveError) {
                Files.copy(tempPath, configPath, StandardCopyOption.REPLACE_EXISTING);
                deleteTempQuietly(tempPath);
            }
        } catch (IOException moveError) {
            Files.copy(tempPath, configPath, StandardCopyOption.REPLACE_EXISTING);
            deleteTempQuietly(tempPath);
        }
    }

    private void writeConfigDirectly(java.util.Properties props) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(configPath.toFile())) {
            props.store(fos, "Updated by web panel");
        }
    }

    private void deleteTempQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("Failed to delete temp config file: {}", path);
        }
    }

    private boolean isConfigDebugEnabled() {
        String value = System.getenv("MTG_CONFIG_DEBUG");
        return value != null && value.equalsIgnoreCase("true");
    }

    private void applyUpdates(Map<String, Object> updates) {
        if (updates.containsKey("monitorDirectory")) {
            config.setMonitorDirectory((String) updates.get("monitorDirectory"));
        }
        if (updates.containsKey("outputDirectory")) {
            config.setOutputDirectory((String) updates.get("outputDirectory"));
        }
        if (updates.containsKey("scanIntervalSeconds")) {
            config.setScanIntervalSeconds((Integer) updates.get("scanIntervalSeconds"));
        }
        if (updates.containsKey("musicBrainzApiUrl")) {
            config.setMusicBrainzApiUrl((String) updates.get("musicBrainzApiUrl"));
        }
        if (updates.containsKey("coverArtApiUrl")) {
            config.setCoverArtApiUrl((String) updates.get("coverArtApiUrl"));
        }
        if (updates.containsKey("userAgent")) {
            config.setUserAgent((String) updates.get("userAgent"));
        }
        if (updates.containsKey("acoustIdApiKey")) {
            config.setAcoustIdApiKey((String) updates.get("acoustIdApiKey"));
        }
        if (updates.containsKey("acoustIdApiUrl")) {
            config.setAcoustIdApiUrl((String) updates.get("acoustIdApiUrl"));
        }
        if (updates.containsKey("supportedFormats")) {
            config.setSupportedFormats((String[]) updates.get("supportedFormats"));
        }
        if (updates.containsKey("autoRename")) {
            config.setAutoRename((Boolean) updates.get("autoRename"));
        }
        if (updates.containsKey("createBackup")) {
            config.setCreateBackup((Boolean) updates.get("createBackup"));
        }
        if (updates.containsKey("failedDirectory")) {
            config.setFailedDirectory((String) updates.get("failedDirectory"));
        }
        if (updates.containsKey("partialDirectory")) {
            config.setPartialDirectory((String) updates.get("partialDirectory"));
        }
        if (updates.containsKey("maxRetries")) {
            config.setMaxRetries((Integer) updates.get("maxRetries"));
        }
        if (updates.containsKey("enableDetailedLogging")) {
            config.setEnableDetailedLogging((Boolean) updates.get("enableDetailedLogging"));
        }
        if (updates.containsKey("processedFileLogPath")) {
            config.setProcessedFileLogPath((String) updates.get("processedFileLogPath"));
        }
        if (updates.containsKey("coverArtCacheDirectory")) {
            config.setCoverArtCacheDirectory((String) updates.get("coverArtCacheDirectory"));
        }
        if (updates.containsKey("preferAnimeCover")) {
            config.setPreferAnimeCover((Boolean) updates.get("preferAnimeCover"));
        }
        if (updates.containsKey("animeCoverKeywords")) {
            String keywords = (String) updates.get("animeCoverKeywords");
            if (keywords == null || keywords.isBlank()) {
                // handleString 会把空值当作「删除该配置项」写回文件，
                // 内存里也必须同步回默认值，否则重启前后行为不一致
                config.setAnimeCoverKeywords(MusicConfig.defaultAnimeCoverKeywords());
            } else {
                config.setAnimeCoverKeywords(java.util.Arrays.stream(keywords.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toList()));
            }
        }
        if (updates.containsKey("animeCoverMaxCandidates")) {
            config.setAnimeCoverMaxCandidates((Integer) updates.get("animeCoverMaxCandidates"));
        }
        if (updates.containsKey("dbType")) {
            config.setDbType((String) updates.get("dbType"));
        }
        if (updates.containsKey("dbHost")) {
            config.setDbHost((String) updates.get("dbHost"));
        }
        if (updates.containsKey("dbPort")) {
            config.setDbPort((Integer) updates.get("dbPort"));
        }
        if (updates.containsKey("dbDatabase")) {
            config.setDbDatabase((String) updates.get("dbDatabase"));
        }
        if (updates.containsKey("dbUsername")) {
            config.setDbUsername((String) updates.get("dbUsername"));
        }
        if (updates.containsKey("dbPassword")) {
            config.setDbPassword((String) updates.get("dbPassword"));
        }
        if (updates.containsKey("dbMaxPoolSize")) {
            config.setDbMaxPoolSize((Integer) updates.get("dbMaxPoolSize"));
        }
        if (updates.containsKey("dbMinIdle")) {
            config.setDbMinIdle((Integer) updates.get("dbMinIdle"));
        }
        if (updates.containsKey("dbConnectionTimeout")) {
            config.setDbConnectionTimeout((Long) updates.get("dbConnectionTimeout"));
        }
        if (updates.containsKey("proxyEnabled")) {
            config.setProxyEnabled((Boolean) updates.get("proxyEnabled"));
        }
        if (updates.containsKey("proxyHost")) {
            config.setProxyHost((String) updates.get("proxyHost"));
        }
        if (updates.containsKey("proxyPort")) {
            config.setProxyPort((Integer) updates.get("proxyPort"));
        }
        if (updates.containsKey("proxyUsername")) {
            config.setProxyUsername((String) updates.get("proxyUsername"));
        }
        if (updates.containsKey("proxyPassword")) {
            config.setProxyPassword((String) updates.get("proxyPassword"));
        }
        if (updates.containsKey("language")) {
            config.setLanguage((String) updates.get("language"));
        }
        if (updates.containsKey("exportLyricsToFile")) {
            config.setExportLyricsToFile((Boolean) updates.get("exportLyricsToFile"));
        }
        if (updates.containsKey("audioNormalizeEnabled")) {
            config.setAudioNormalizeEnabled((Boolean) updates.get("audioNormalizeEnabled"));
        }
        // audioNormalizeFfmpegPath 为只读字段,不接受来自 API 的修改(见 doPut 中的说明)
        if (updates.containsKey("cueSplitEnabled")) {
            config.setCueSplitEnabled((Boolean) updates.get("cueSplitEnabled"));
        }
        if (updates.containsKey("cueSplitOutputDir")) {
            config.setCueSplitOutputDir((String) updates.get("cueSplitOutputDir"));
        }
        if (updates.containsKey("reviewEnabled")) {
            config.setReviewEnabled((Boolean) updates.get("reviewEnabled"));
        }
        if (updates.containsKey("releaseCountryPriority")) {
            @SuppressWarnings("unchecked")
            List<String> priorities = (List<String>) updates.get("releaseCountryPriority");
            config.setReleaseCountryPriority(priorities);
        }
        if (updates.containsKey("enableLLMMatching")) {
            config.setEnableLLMMatching((Boolean) updates.get("enableLLMMatching"));
        }
        if (updates.containsKey("llmAllowPrivateEndpoints")) {
            config.setLlmAllowPrivateEndpoints((Boolean) updates.get("llmAllowPrivateEndpoints"));
        }
        // 这些字段走直接 setter，不经过配置文件加载时的 parseIntInRange，因此在这里钳制
        if (updates.containsKey("llmMaxTokens")) {
            config.setLlmMaxTokens(clamp((Integer) updates.get("llmMaxTokens"), 1, 32000));
        }
        if (updates.containsKey("llmTemperature")) {
            config.setLlmTemperature(clamp((Double) updates.get("llmTemperature"), 0.0, 2.0));
        }
        if (updates.containsKey("llmTimeoutSeconds")) {
            config.setLlmTimeoutSeconds(clamp((Integer) updates.get("llmTimeoutSeconds"), 5, 600));
        }
        if (updates.containsKey("llmMaxRetries")) {
            config.setLlmMaxRetries(clamp((Integer) updates.get("llmMaxRetries"), 0, 10));
        }
        if (updates.containsKey("llmWebSearchMaxTokens")) {
            config.setLlmWebSearchMaxTokens(clamp((Integer) updates.get("llmWebSearchMaxTokens"), 256, 32000));
        }
        if (updates.containsKey("llmWebSearchTimeoutSeconds")) {
            config.setLlmWebSearchTimeoutSeconds(clamp((Integer) updates.get("llmWebSearchTimeoutSeconds"), 30, 900));
        }
        // 联网搜索来源：未知值一律回落 native，避免拼错后静默停用联网搜索
        if (updates.containsKey("webSearchProvider")) {
            String provider = (String) updates.get("webSearchProvider");
            config.setWebSearchProvider("tavily".equalsIgnoreCase(provider) ? "tavily" : "native");
        }
        if (updates.containsKey("tavilyApiKey")) {
            config.setTavilyApiKey((String) updates.get("tavilyApiKey"));
        }
        if (updates.containsKey("tavilyApiUrl")) {
            String url = (String) updates.get("tavilyApiUrl");
            config.setTavilyApiUrl(url == null || url.isBlank() ? "https://api.tavily.com/search" : url.trim());
        }
        if (updates.containsKey("tavilySearchDepth")) {
            String depth = (String) updates.get("tavilySearchDepth");
            config.setTavilySearchDepth("basic".equalsIgnoreCase(depth) ? "basic" : "advanced");
        }
        if (updates.containsKey("tavilyMaxResults")) {
            config.setTavilyMaxResults(clamp((Integer) updates.get("tavilyMaxResults"), 1, 20));
        }
        if (updates.containsKey("tavilyTimeoutSeconds")) {
            config.setTavilyTimeoutSeconds(clamp((Integer) updates.get("tavilyTimeoutSeconds"), 5, 300));
        }
        if (updates.containsKey("tavilyIncludeDomains")) {
            String domains = (String) updates.get("tavilyIncludeDomains");
            config.setTavilyIncludeDomains(domains == null || domains.isBlank()
                ? new java.util.ArrayList<>()
                : java.util.Arrays.stream(domains.split(",")).map(String::trim)
                    .filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toList()));
        }
        if (updates.containsKey("llmAlbumJudgeEnabled")) {
            config.setLlmAlbumJudgeEnabled((Boolean) updates.get("llmAlbumJudgeEnabled"));
        }
        if (updates.containsKey("llmAlbumAutoApply")) {
            config.setLlmAlbumAutoApply((Boolean) updates.get("llmAlbumAutoApply"));
        }
        if (updates.containsKey("llmAlbumAutoApplyMinConfidence")) {
            config.setLlmAlbumAutoApplyMinConfidence(
                clamp((Double) updates.get("llmAlbumAutoApplyMinConfidence"), 0.0, 1.0));
        }
        if (updates.containsKey("partialRequireReadableTags")) {
            config.setPartialRequireReadableTags((Boolean) updates.get("partialRequireReadableTags"));
        }
        if (updates.containsKey("partialMinTagCoverage")) {
            config.setPartialMinTagCoverage(clamp((Double) updates.get("partialMinTagCoverage"), 0.0, 1.0));
        }
        if (updates.containsKey("reviewQueuePath")) {
            config.setReviewQueuePath((String) updates.get("reviewQueuePath"));
        }
        if (updates.containsKey("reviewStagingDirectory")) {
            config.setReviewStagingDirectory((String) updates.get("reviewStagingDirectory"));
        }
        if (updates.containsKey("recoveryWorkDirectory")) {
            config.setRecoveryWorkDirectory((String) updates.get("recoveryWorkDirectory"));
        }
        if (updates.containsKey("recoveryTrashDirectory")) {
            // 置空时回退到默认值，null 会在 saveConfig 里抛 NPE
            String trashDirectory = (String) updates.get("recoveryTrashDirectory");
            config.setRecoveryTrashDirectory(
                trashDirectory == null || trashDirectory.isBlank() ? "data/recovery-trash" : trashDirectory);
        }
        if (updates.containsKey("recoveryTrashRetentionDays")) {
            // handleInteger 不做范围校验，而这里是直接 setter，会绕过配置文件加载时的 -1..365 限制
            int retention = (Integer) updates.get("recoveryTrashRetentionDays");
            config.setRecoveryTrashRetentionDays(Math.max(-1, Math.min(365, retention)));
        }
    }

    private void handleString(Map<String, Object> body, Map<String, Object> updates,
                              Map<String, String> propertyUpdates, String field, boolean required) throws IOException {
        if (!body.containsKey(field)) {
            return;
        }

        String rawValue = asString(body.get(field));
        String trimmedValue = rawValue == null ? null : rawValue.trim();
        if (trimmedValue != null && trimmedValue.isEmpty()) {
            trimmedValue = null;
        }

        // 密钥字段: 前端读到的是占位符,原样回传即表示「保持不变」
        if (secretFields.contains(field) && SECRET_MASK.equals(trimmedValue)) {
            return;
        }

        if (required && trimmedValue == null) {
            throwValidation("required.field", field);
        }

        if (trimmedValue != null && absolutePathFields.contains(field)) {
            if (!Paths.get(trimmedValue).isAbsolute()) {
                throwValidation("path.must.be.absolute", field);
            }
        }

        if ("language".equals(field) && trimmedValue != null && !allowedLanguages.contains(trimmedValue)) {
            throwValidation("language.invalid", field);
        }

        if ("dbType".equals(field) && trimmedValue != null && !allowedDbTypes.contains(trimmedValue)) {
            throwValidation("db.type.invalid", field);
        }

        if ("supportedFormats".equals(field)) {
            if (trimmedValue == null) {
                throwValidation("supported.formats.invalid", field);
            }
            String[] formats = Arrays.stream(trimmedValue.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
            if (formats.length == 0) {
                throwValidation("supported.formats.invalid", field);
            }
            updates.put(field, formats);
            setPropertyUpdate(propertyUpdates, field, String.join(",", formats));
            return;
        }

        if ("releaseCountryPriority".equals(field)) {
            if (trimmedValue == null || trimmedValue.isEmpty()) {
                updates.put(field, new ArrayList<String>());
                setPropertyUpdate(propertyUpdates, field, null);
                return;
            }
            List<String> priorities = Arrays.stream(trimmedValue.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
            updates.put(field, priorities);
            setPropertyUpdate(propertyUpdates, field, String.join(",", priorities));
            return;
        }

        updates.put(field, trimmedValue);
        setPropertyUpdate(propertyUpdates, field, trimmedValue);
    }

    private void handleBoolean(Map<String, Object> body, Map<String, Object> updates,
                               Map<String, String> propertyUpdates, String field) {
        if (!body.containsKey(field)) {
            return;
        }
        Boolean value = asBoolean(body.get(field));
        if (value == null) {
            return;
        }
        updates.put(field, value);
        setPropertyUpdate(propertyUpdates, field, String.valueOf(value));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void handleDouble(Map<String, Object> body, Map<String, Object> updates,
                              Map<String, String> propertyUpdates, String field) throws IOException {
        if (!body.containsKey(field)) {
            return;
        }
        Double value = asDouble(body.get(field));
        if (value == null) {
            return;
        }
        updates.put(field, value);
        setPropertyUpdate(propertyUpdates, field, String.valueOf(value));
    }

    private Double asDouble(Object value) throws IOException {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            throwValidation("number.invalid", null);
            return null;
        }
    }

    /**
     * 与 handleInteger 相同，但先 clamp 再写回文件，
     * 避免「文件里是非法值、内存里是 clamp 值、重启后又变默认值」的不幂等行为
     */
    private void handleIntegerInRange(Map<String, Object> body, Map<String, Object> updates,
                                      Map<String, String> propertyUpdates, String field,
                                      int min, int max) throws IOException {
        if (!body.containsKey(field)) {
            return;
        }
        Integer value = asInteger(body.get(field));
        if (value == null) {
            return;
        }
        int clamped = clamp(value, min, max);
        updates.put(field, clamped);
        setPropertyUpdate(propertyUpdates, field, String.valueOf(clamped));
    }

    private void handleInteger(Map<String, Object> body, Map<String, Object> updates,
                               Map<String, String> propertyUpdates, String field) throws IOException {
        if (!body.containsKey(field)) {
            return;
        }
        Integer value = asInteger(body.get(field));
        if (value == null) {
            return;
        }
        updates.put(field, value);
        setPropertyUpdate(propertyUpdates, field, String.valueOf(value));
    }

    private void handleLong(Map<String, Object> body, Map<String, Object> updates,
                            Map<String, String> propertyUpdates, String field) throws IOException {
        if (!body.containsKey(field)) {
            return;
        }
        Long value = asLong(body.get(field));
        if (value == null) {
            return;
        }
        updates.put(field, value);
        setPropertyUpdate(propertyUpdates, field, String.valueOf(value));
    }

    private void setPropertyUpdate(Map<String, String> updates, String field, String value) {
        String propertyKey = propertyKeys.get(field);
        if (propertyKey == null) {
            return;
        }
        updates.put(propertyKey, value);
    }

    private void throwValidation(String error, String field) throws IOException {
        throw new ValidationException(error, field);
    }

    /**
     * 拒绝对只读字段的修改
     *
     * 前端保存时会把所有字段原样回传,所以只在值确实发生变化时才报错,
     * 避免正常保存被误伤。
     */
    private void rejectIfChanged(Map<String, Object> body, String field, String currentValue) throws IOException {
        if (!body.containsKey(field)) {
            return;
        }
        String incoming = asString(body.get(field));
        String normalizedIncoming = incoming == null ? "" : incoming.trim();
        String normalizedCurrent = currentValue == null ? "" : currentValue.trim();
        if (!normalizedIncoming.equals(normalizedCurrent)) {
            log.warn("拒绝通过 API 修改只读字段 {}(当前值 [{}],请求值 [{}])",
                field, normalizedCurrent, normalizedIncoming);
            throwValidation("field.readonly", field);
        }
    }

    /**
     * 有值则返回占位符,无值则返回 null
     */
    private String maskSecret(String value) {
        return isSet(value) ? SECRET_MASK : null;
    }

    private boolean isSet(String value) {
        return value != null && !value.isEmpty();
    }

    private boolean isCsrfValid(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) {
            return false;
        }
        String token = req.getHeader("X-CSRF-Token");
        String sessionToken = (String) session.getAttribute(SESSION_CSRF_KEY);
        return sessionToken != null && sessionToken.equals(token);
    }

    private Map<String, Object> readBodyAsMap(HttpServletRequest req) throws IOException {
        try (BufferedReader reader = req.getReader()) {
            Map<String, Object> data = gson.fromJson(reader, Map.class);
            return data == null ? new HashMap<>() : data;
        }
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private Boolean asBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Integer asInteger(Object value) throws IOException {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throwValidation("number.invalid", null);
            return null;
        }
    }

    private Long asLong(Object value) throws IOException {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throwValidation("number.invalid", null);
            return null;
        }
    }

    private void respondJson(HttpServletResponse resp, int status, Map<String, Object> payload) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(status);
        resp.getWriter().write(gson.toJson(payload));
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
        throws IOException, jakarta.servlet.ServletException {
        try {
            super.service(req, resp);
        } catch (ValidationException e) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of(
                "error", e.error,
                "field", e.field == null ? "" : e.field
            ));
        }
    }

    private static class ValidationException extends IOException {
        private final String error;
        private final String field;

        private ValidationException(String error, String field) {
            this.error = error;
            this.field = field;
        }
    }
}

