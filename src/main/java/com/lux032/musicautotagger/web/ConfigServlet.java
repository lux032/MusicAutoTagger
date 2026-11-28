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

    /**
     * 需重启才能生效的结构性配置字段：Web 保存时只写盘并记入待重启视图，
     * 严禁原地篡改活态 MusicConfig（否则会导致连接池/监控目录/日志后端与内存配置脑裂）。
     */
    private static final Set<String> RESTART_ONLY_FIELDS = Set.of(
        "monitorDirectory",
        "outputDirectory",
        "scanIntervalSeconds",
        "processedFileLogPath",
        "coverArtCacheDirectory",
        "dbType",
        "dbSqlitePath",
        "dbHost",
        "dbPort",
        "dbDatabase",
        "dbUsername",
        "dbPassword",
        "dbMaxPoolSize",
        "dbMinIdle",
        "dbConnectionTimeout",
        "proxyEnabled",
        "proxyHost",
        "proxyPort",
        "proxyUsername",
        "proxyPassword"
    );

    private final MusicConfig config;
    private final Gson gson;
    private final Path configPath;
    private final Map<String, String> propertyKeys;
    private final Set<String> absolutePathFields;
    private final Set<String> allowedLanguages;
    private final Set<String> allowedDbTypes;
    private final Set<String> secretFields;
    /** 已落盘但需重启后生效的配置值（仅在 ConfigServlet 同步方法内访问）。 */
    private final Map<String, Object> pendingRestartValues = new LinkedHashMap<>();

    public ConfigServlet(MusicConfig config) {
        this(config, Paths.get("config.properties"));
    }

    public ConfigServlet(MusicConfig config, Path configPath) {
        this.config = config;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.configPath = configPath != null ? configPath : Paths.get("config.properties");
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
            Map.entry("collapseAlbumArtistToVariousArtists", "tag.albumArtist.collapseToVariousArtists"),
            Map.entry("preferNativeArtistName", "tag.artist.preferNativeName"),
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
            Map.entry("dbSqlitePath", "db.sqlite.path"),
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
            Map.entry("reviewMissingTracksAutoOnlineSearch", "review.missingTracks.autoOnlineSearch"),
            Map.entry("coverMissingRequireReview", "cover.missing.requireReview"),
            Map.entry("coverMissingAutoOnlineSearch", "cover.missing.autoOnlineSearch"),
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
            Map.entry("llmWebSearchMaxConcurrency", "llm.webSearch.maxConcurrency"),
            Map.entry("llmWebSearchNativeContextSize", "llm.webSearch.nativeContextSize"),
            Map.entry("onlineCreditsEnrichEnabled", "online.credits.enrich.enabled"),
            Map.entry("onlineCreditsMaxFollowUpRounds", "online.credits.maxFollowUpRounds"),
            Map.entry("onlineCreditsMaxSearchCalls", "online.credits.maxSearchCalls"),
            Map.entry("onlineCreditsMaxFetchPages", "online.credits.maxFetchPages"),
            Map.entry("onlineCreditsAllowTavilyExtract", "online.credits.allowTavilyExtract"),
            Map.entry("onlineCreditsApplyProbable", "online.credits.applyProbable"),
            Map.entry("onlineMbidResolveEnabled", "online.mbid.resolve.enabled"),
            Map.entry("onlineMbidMaxLookupsPerAlbum", "online.mbid.maxLookupsPerAlbum"),
            Map.entry("onlineMbidLlmDisambiguateEnabled", "online.mbid.llmDisambiguate.enabled"),
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
        this.allowedDbTypes = Set.of("sqlite", "file", "mysql");
        // 这些字段不以明文返回给前端: 一旦有会话被拿到,否则等于所有凭据一次性泄露
        this.secretFields = Set.of(
            "dbPassword",
            "proxyPassword",
            "acoustIdApiKey",
            "tavilyApiKey"
        );
    }

    @Override
    protected synchronized void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> payload = new HashMap<>();
        payload.put("config", currentConfig());
        payload.put("pendingRestartFields", new ArrayList<>(pendingRestartValues.keySet()));

        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().write(gson.toJson(payload));
    }

    @Override
    protected synchronized void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isCsrfValid(req)) {
            respondJson(resp, HttpServletResponse.SC_FORBIDDEN, Map.of("error", "csrf.invalid"));
            return;
        }

        Map<String, Object> body = readBodyAsMap(req);
        try {
            ApplyResult result = applyConfigMap(body, false, false);
            if (!result.updated()) {
                respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "no.updates"));
                return;
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("config", currentConfig());
            payload.put("updated", true);
            payload.put("requiresRestart", result.requiresRestart());
            payload.put("pendingRestartFields", result.pendingRestartFields());
            respondJson(resp, HttpServletResponse.SC_OK, payload);
        } catch (IOException e) {
            if (e instanceof ValidationException) {
                throw e;
            }
            log.error("Failed to save config", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "save.failed");
            if (isConfigDebugEnabled()) {
                error.put("detail", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            respondJson(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, error);
        }
    }

    /** 供 SetupServlet 复用的配置更新结果。 */
    record ApplyResult(boolean updated, boolean requiresRestart, List<String> pendingRestartFields) {
    }

    synchronized Map<String, Object> snapshotConfig() {
        return currentConfig();
    }

    synchronized List<String> getPendingRestartFields() {
        return new ArrayList<>(pendingRestartValues.keySet());
    }

    /**
     * 统一的配置校验、落盘与应用入口（供 PUT /api/config 与 SetupServlet 共享）。
     *
     * @param body             提交字段
     * @param allowEmptyUpdate 为 true 时无字段变更也返回成功（引导仅保留默认值场景）
     * @param directHotApply   为 true 时（仅首次待初始化模式，业务服务尚未启动）直接应用全部字段到活态 MusicConfig
     */
    synchronized ApplyResult applyConfigMap(Map<String, Object> body,
                                            boolean allowEmptyUpdate,
                                            boolean directHotApply) throws IOException {
        Map<String, Object> safeBody = body == null ? Map.of() : body;
        Map<String, Object> updates = new LinkedHashMap<>();
        Map<String, String> propertyUpdates = new LinkedHashMap<>();

        handleString(safeBody, updates, propertyUpdates, "monitorDirectory", true);
        handleString(safeBody, updates, propertyUpdates, "outputDirectory", true);
        handleInteger(safeBody, updates, propertyUpdates, "scanIntervalSeconds");
        handleString(safeBody, updates, propertyUpdates, "musicBrainzApiUrl", false);
        handleString(safeBody, updates, propertyUpdates, "coverArtApiUrl", false);
        handleString(safeBody, updates, propertyUpdates, "userAgent", false);
        handleString(safeBody, updates, propertyUpdates, "acoustIdApiKey", false);
        handleString(safeBody, updates, propertyUpdates, "acoustIdApiUrl", false);
        handleString(safeBody, updates, propertyUpdates, "supportedFormats", false);
        handleBoolean(safeBody, updates, propertyUpdates, "autoRename");
        handleBoolean(safeBody, updates, propertyUpdates, "createBackup");
        handleBoolean(safeBody, updates, propertyUpdates, "collapseAlbumArtistToVariousArtists");
        handleBoolean(safeBody, updates, propertyUpdates, "preferNativeArtistName");
        handleString(safeBody, updates, propertyUpdates, "failedDirectory", false);
        handleString(safeBody, updates, propertyUpdates, "partialDirectory", false);
        handleInteger(safeBody, updates, propertyUpdates, "maxRetries");
        handleBoolean(safeBody, updates, propertyUpdates, "enableDetailedLogging");
        handleString(safeBody, updates, propertyUpdates, "processedFileLogPath", false);
        handleString(safeBody, updates, propertyUpdates, "coverArtCacheDirectory", false);
        handleBoolean(safeBody, updates, propertyUpdates, "preferAnimeCover");
        handleString(safeBody, updates, propertyUpdates, "animeCoverKeywords", false);
        handleIntegerInRange(safeBody, updates, propertyUpdates, "animeCoverMaxCandidates", 1, 20);
        handleString(safeBody, updates, propertyUpdates, "dbType", false);
        // SQLite 数据库文件路径、队列与工作/回收站目录改为只读：防止会话劫持后指向敏感目录触发覆盖或递归删除
        rejectIfChanged(safeBody, "dbSqlitePath", config.getDbSqlitePath());
        handleString(safeBody, updates, propertyUpdates, "dbHost", false);
        handleInteger(safeBody, updates, propertyUpdates, "dbPort");
        handleString(safeBody, updates, propertyUpdates, "dbDatabase", false);
        handleString(safeBody, updates, propertyUpdates, "dbUsername", false);
        handleString(safeBody, updates, propertyUpdates, "dbPassword", false);
        handleInteger(safeBody, updates, propertyUpdates, "dbMaxPoolSize");
        handleInteger(safeBody, updates, propertyUpdates, "dbMinIdle");
        handleLong(safeBody, updates, propertyUpdates, "dbConnectionTimeout");
        handleBoolean(safeBody, updates, propertyUpdates, "proxyEnabled");
        handleString(safeBody, updates, propertyUpdates, "proxyHost", false);
        handleInteger(safeBody, updates, propertyUpdates, "proxyPort");
        handleString(safeBody, updates, propertyUpdates, "proxyUsername", false);
        handleString(safeBody, updates, propertyUpdates, "proxyPassword", false);
        handleString(safeBody, updates, propertyUpdates, "language", false);
        handleBoolean(safeBody, updates, propertyUpdates, "exportLyricsToFile");
        handleBoolean(safeBody, updates, propertyUpdates, "audioNormalizeEnabled");
        rejectIfChanged(safeBody, "audioNormalizeFfmpegPath", config.getAudioNormalizeFfmpegPath());
        handleBoolean(safeBody, updates, propertyUpdates, "cueSplitEnabled");
        handleString(safeBody, updates, propertyUpdates, "cueSplitOutputDir", false);
        handleString(safeBody, updates, propertyUpdates, "releaseCountryPriority", false);
        handleBoolean(safeBody, updates, propertyUpdates, "reviewEnabled");
        handleBoolean(safeBody, updates, propertyUpdates, "reviewMissingTracksAutoOnlineSearch");
        handleBoolean(safeBody, updates, propertyUpdates, "coverMissingRequireReview");
        handleBoolean(safeBody, updates, propertyUpdates, "coverMissingAutoOnlineSearch");
        handleBoolean(safeBody, updates, propertyUpdates, "enableLLMMatching");
        rejectIfBooleanChanged(safeBody, "llmAllowPrivateEndpoints", config.isLlmAllowPrivateEndpoints());
        handleInteger(safeBody, updates, propertyUpdates, "llmMaxTokens");
        handleDouble(safeBody, updates, propertyUpdates, "llmTemperature");
        handleInteger(safeBody, updates, propertyUpdates, "llmTimeoutSeconds");
        handleInteger(safeBody, updates, propertyUpdates, "llmMaxRetries");
        handleInteger(safeBody, updates, propertyUpdates, "llmWebSearchMaxTokens");
        handleInteger(safeBody, updates, propertyUpdates, "llmWebSearchTimeoutSeconds");
        handleIntegerInRange(safeBody, updates, propertyUpdates, "llmWebSearchMaxConcurrency", 1, 3);
        handleString(safeBody, updates, propertyUpdates, "llmWebSearchNativeContextSize", false);
        handleBoolean(safeBody, updates, propertyUpdates, "onlineCreditsEnrichEnabled");
        handleIntegerInRange(safeBody, updates, propertyUpdates, "onlineCreditsMaxFollowUpRounds", 0, 3);
        handleIntegerInRange(safeBody, updates, propertyUpdates, "onlineCreditsMaxSearchCalls", 1, 12);
        handleIntegerInRange(safeBody, updates, propertyUpdates, "onlineCreditsMaxFetchPages", 0, 12);
        handleBoolean(safeBody, updates, propertyUpdates, "onlineCreditsAllowTavilyExtract");
        handleBoolean(safeBody, updates, propertyUpdates, "onlineCreditsApplyProbable");
        handleStrictBoolean(safeBody, updates, propertyUpdates, "onlineMbidResolveEnabled");
        handleStrictIntegerInRange(safeBody, updates, propertyUpdates, "onlineMbidMaxLookupsPerAlbum", 0, 50);
        handleStrictBoolean(safeBody, updates, propertyUpdates, "onlineMbidLlmDisambiguateEnabled");
        handleString(safeBody, updates, propertyUpdates, "webSearchProvider", false);
        handleString(safeBody, updates, propertyUpdates, "tavilyApiKey", false);
        handleString(safeBody, updates, propertyUpdates, "tavilyApiUrl", false);
        handleString(safeBody, updates, propertyUpdates, "tavilySearchDepth", false);
        handleInteger(safeBody, updates, propertyUpdates, "tavilyMaxResults");
        handleInteger(safeBody, updates, propertyUpdates, "tavilyTimeoutSeconds");
        handleString(safeBody, updates, propertyUpdates, "tavilyIncludeDomains", false);
        handleBoolean(safeBody, updates, propertyUpdates, "llmAlbumJudgeEnabled");
        handleBoolean(safeBody, updates, propertyUpdates, "llmAlbumAutoApply");
        handleDouble(safeBody, updates, propertyUpdates, "llmAlbumAutoApplyMinConfidence");
        handleBoolean(safeBody, updates, propertyUpdates, "partialRequireReadableTags");
        handleDouble(safeBody, updates, propertyUpdates, "partialMinTagCoverage");
        rejectIfChanged(safeBody, "reviewQueuePath", config.getReviewQueuePath());
        rejectIfChanged(safeBody, "reviewStagingDirectory", config.getReviewStagingDirectory());
        rejectIfChanged(safeBody, "recoveryWorkDirectory", config.getRecoveryWorkDirectory());
        rejectIfChanged(safeBody, "recoveryTrashDirectory", config.getRecoveryTrashDirectory());
        handleInteger(safeBody, updates, propertyUpdates, "recoveryTrashRetentionDays");

        // 防掩码密钥外泄：若修改了目标端点 URL / Host / Port 且当前已存有配对密钥，则不允许继续沿用掩码密钥
        enforceSecretReentryOnUrlChange(updates, "acoustIdApiUrl", config.getAcoustIdApiUrl(),
            "acoustIdApiKey", config.getAcoustIdApiKey());
        enforceSecretReentryOnUrlChange(updates, "tavilyApiUrl", config.getTavilyApiUrl(),
            "tavilyApiKey", config.getTavilyApiKey());
        enforceSecretReentryOnHostPortChange(updates, "proxyHost", effectiveString("proxyHost", config.getProxyHost()),
            "proxyPort", effectiveInt("proxyPort", config.getProxyPort()),
            "proxyPassword", effectiveString("proxyPassword", config.getProxyPassword()));
        enforceSecretReentryOnHostPortChange(updates, "dbHost", effectiveString("dbHost", config.getDbHost()),
            "dbPort", effectiveInt("dbPort", config.getDbPort()),
            "dbPassword", effectiveString("dbPassword", config.getDbPassword()));

        if (updates.isEmpty()) {
            return new ApplyResult(allowEmptyUpdate, !pendingRestartValues.isEmpty(),
                new ArrayList<>(pendingRestartValues.keySet()));
        }

        persistUpdates(propertyUpdates);
        if (directHotApply) {
            applyUpdates(updates);
            pendingRestartValues.clear();
        } else {
            applyUpdatesSeparatingRestartFields(updates);
        }

        return new ApplyResult(true, !pendingRestartValues.isEmpty(),
            new ArrayList<>(pendingRestartValues.keySet()));
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
        data.put("collapseAlbumArtistToVariousArtists", config.isCollapseAlbumArtistToVariousArtists());
        data.put("preferNativeArtistName", config.isPreferNativeArtistName());
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
        data.put("dbSqlitePath", config.getDbSqlitePath());
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
        data.put("reviewMissingTracksAutoOnlineSearch", config.isReviewMissingTracksAutoOnlineSearch());
        data.put("coverMissingRequireReview", config.isCoverMissingRequireReview());
        data.put("coverMissingAutoOnlineSearch", config.isCoverMissingAutoOnlineSearch());
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
        data.put("llmWebSearchMaxConcurrency", config.getLlmWebSearchMaxConcurrency());
        data.put("llmWebSearchNativeContextSize", MusicConfig.normalizeNativeContextSize(config.getLlmWebSearchNativeContextSize()));
        data.put("onlineCreditsEnrichEnabled", config.isOnlineCreditsEnrichEnabled());
        data.put("onlineCreditsMaxFollowUpRounds", config.getOnlineCreditsMaxFollowUpRounds());
        data.put("onlineCreditsMaxSearchCalls", config.getOnlineCreditsMaxSearchCalls());
        data.put("onlineCreditsMaxFetchPages", config.getOnlineCreditsMaxFetchPages());
        data.put("onlineCreditsAllowTavilyExtract", config.isOnlineCreditsAllowTavilyExtract());
        data.put("onlineCreditsApplyProbable", config.isOnlineCreditsApplyProbable());
        data.put("onlineMbidResolveEnabled", config.isOnlineMbidResolveEnabled());
        data.put("onlineMbidMaxLookupsPerAlbum", config.getOnlineMbidMaxLookupsPerAlbum());
        data.put("onlineMbidLlmDisambiguateEnabled", config.isOnlineMbidLlmDisambiguateEnabled());
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
        // 叠加已落盘等待重启生效的值，让配置面板展示最新保存结果，同时保证运行中的活态 MusicConfig 不被篡改
        for (Map.Entry<String, Object> pending : pendingRestartValues.entrySet()) {
            String key = pending.getKey();
            Object val = pending.getValue();
            if (secretFields.contains(key)) {
                String secretVal = val == null ? null : String.valueOf(val);
                data.put(key, maskSecret(secretVal));
                if ("dbPassword".equals(key)) data.put("dbPasswordSet", isSet(secretVal));
                if ("proxyPassword".equals(key)) data.put("proxyPasswordSet", isSet(secretVal));
            } else {
                data.put(key, val);
            }
        }
        return data;
    }

    private String effectiveString(String field, String liveValue) {
        if (pendingRestartValues.containsKey(field)) {
            Object v = pendingRestartValues.get(field);
            return v == null ? null : String.valueOf(v);
        }
        return liveValue;
    }

    private int effectiveInt(String field, int liveValue) {
        if (pendingRestartValues.containsKey(field) && pendingRestartValues.get(field) instanceof Integer i) {
            return i;
        }
        return liveValue;
    }

    private Object liveRestartOnlyValue(String field) {
        return switch (field) {
            case "monitorDirectory" -> config.getMonitorDirectory();
            case "outputDirectory" -> config.getOutputDirectory();
            case "scanIntervalSeconds" -> config.getScanIntervalSeconds();
            case "processedFileLogPath" -> config.getProcessedFileLogPath();
            case "coverArtCacheDirectory" -> config.getCoverArtCacheDirectory();
            case "dbType" -> config.getDbType();
            case "dbSqlitePath" -> config.getDbSqlitePath();
            case "dbHost" -> config.getDbHost();
            case "dbPort" -> config.getDbPort();
            case "dbDatabase" -> config.getDbDatabase();
            case "dbUsername" -> config.getDbUsername();
            case "dbPassword" -> config.getDbPassword();
            case "dbMaxPoolSize" -> config.getDbMaxPoolSize();
            case "dbMinIdle" -> config.getDbMinIdle();
            case "dbConnectionTimeout" -> config.getDbConnectionTimeout();
            case "proxyEnabled" -> config.isProxyEnabled();
            case "proxyHost" -> config.getProxyHost();
            case "proxyPort" -> config.getProxyPort();
            case "proxyUsername" -> config.getProxyUsername();
            case "proxyPassword" -> config.getProxyPassword();
            default -> null;
        };
    }

    private void applyUpdatesSeparatingRestartFields(Map<String, Object> updates) {
        Map<String, Object> hotUpdates = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (RESTART_ONLY_FIELDS.contains(key)) {
                Object liveVal = liveRestartOnlyValue(key);
                if (java.util.Objects.equals(liveVal, val)) {
                    pendingRestartValues.remove(key);
                } else {
                    pendingRestartValues.put(key, val);
                }
            } else {
                hotUpdates.put(key, val);
            }
        }
        if (!hotUpdates.isEmpty()) {
            applyUpdates(hotUpdates);
        }
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
        Path normalized = configPath.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        Path tempPath = Files.createTempFile(parent != null ? parent : Path.of("."), ".config.properties-", ".tmp");
        try {
            com.lux032.musicautotagger.util.OutboundUrlValidator.setOwnerOnlyPermissions(tempPath);
            try (FileOutputStream fos = new FileOutputStream(tempPath.toFile())) {
                props.store(fos, "Updated by web panel");
            }

            try {
                Files.move(tempPath, normalized, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                try {
                    Files.move(tempPath, normalized, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException moveError) {
                    Files.copy(tempPath, normalized, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException moveError) {
                Files.copy(tempPath, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
            com.lux032.musicautotagger.util.OutboundUrlValidator.setOwnerOnlyPermissions(normalized);
        } finally {
            deleteTempQuietly(tempPath);
        }
    }

    private void writeConfigDirectly(java.util.Properties props) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(configPath.toFile())) {
            props.store(fos, "Updated by web panel");
        }
        com.lux032.musicautotagger.util.OutboundUrlValidator.setOwnerOnlyPermissions(configPath);
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
        if (updates.containsKey("collapseAlbumArtistToVariousArtists")) {
            config.setCollapseAlbumArtistToVariousArtists(
                (Boolean) updates.get("collapseAlbumArtistToVariousArtists"));
        }
        if (updates.containsKey("preferNativeArtistName")) {
            config.setPreferNativeArtistName((Boolean) updates.get("preferNativeArtistName"));
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
        if (updates.containsKey("dbSqlitePath")) {
            config.setDbSqlitePath((String) updates.get("dbSqlitePath"));
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
            String lang = (String) updates.get("language");
            config.setLanguage(lang);
            if (lang != null && !lang.isBlank()) {
                com.lux032.musicautotagger.util.I18nUtil.init(lang);
            }
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
        if (updates.containsKey("reviewMissingTracksAutoOnlineSearch")) {
            config.setReviewMissingTracksAutoOnlineSearch((Boolean) updates.get("reviewMissingTracksAutoOnlineSearch"));
        }
        if (updates.containsKey("coverMissingRequireReview")) {
            config.setCoverMissingRequireReview((Boolean) updates.get("coverMissingRequireReview"));
        }
        if (updates.containsKey("coverMissingAutoOnlineSearch")) {
            config.setCoverMissingAutoOnlineSearch((Boolean) updates.get("coverMissingAutoOnlineSearch"));
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
        if (updates.containsKey("llmWebSearchMaxConcurrency")) {
            config.setLlmWebSearchMaxConcurrency(clamp((Integer) updates.get("llmWebSearchMaxConcurrency"), 1, 3));
        }
        if (updates.containsKey("llmWebSearchNativeContextSize")) {
            config.setLlmWebSearchNativeContextSize(
                MusicConfig.normalizeNativeContextSize((String) updates.get("llmWebSearchNativeContextSize")));
        }
        if (updates.containsKey("onlineCreditsEnrichEnabled")) {
            config.setOnlineCreditsEnrichEnabled((Boolean) updates.get("onlineCreditsEnrichEnabled"));
        }
        if (updates.containsKey("onlineCreditsMaxFollowUpRounds")) {
            config.setOnlineCreditsMaxFollowUpRounds(clamp((Integer) updates.get("onlineCreditsMaxFollowUpRounds"), 0, 3));
        }
        if (updates.containsKey("onlineCreditsMaxSearchCalls")) {
            config.setOnlineCreditsMaxSearchCalls(clamp((Integer) updates.get("onlineCreditsMaxSearchCalls"), 1, 12));
        }
        if (updates.containsKey("onlineCreditsMaxFetchPages")) {
            config.setOnlineCreditsMaxFetchPages(clamp((Integer) updates.get("onlineCreditsMaxFetchPages"), 0, 12));
        }
        if (updates.containsKey("onlineCreditsAllowTavilyExtract")) {
            config.setOnlineCreditsAllowTavilyExtract((Boolean) updates.get("onlineCreditsAllowTavilyExtract"));
        }
        if (updates.containsKey("onlineCreditsApplyProbable")) {
            config.setOnlineCreditsApplyProbable((Boolean) updates.get("onlineCreditsApplyProbable"));
        }
        if (updates.containsKey("onlineMbidResolveEnabled")
                || updates.containsKey("onlineMbidMaxLookupsPerAlbum")
                || updates.containsKey("onlineMbidLlmDisambiguateEnabled")) {
            Integer maxLookups = updates.containsKey("onlineMbidMaxLookupsPerAlbum")
                ? clamp((Integer) updates.get("onlineMbidMaxLookupsPerAlbum"), 0, 50)
                : null;
            config.updateOnlineMbidSettings(
                (Boolean) updates.get("onlineMbidResolveEnabled"),
                maxLookups,
                (Boolean) updates.get("onlineMbidLlmDisambiguateEnabled"));
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
            Path parsed;
            try {
                parsed = Paths.get(trimmedValue).normalize();
            } catch (java.nio.file.InvalidPathException e) {
                throwValidation("path.invalid", field);
                return;
            }
            if (!parsed.isAbsolute() || parsed.getParent() == null) {
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

        if ("tavilyIncludeDomains".equals(field)) {
            // 显式清空时必须写入空字符串而非删除 key，否则重启加载时缺失 key 会回弹为默认域名列表
            String normalized = trimmedValue == null ? "" : Arrays.stream(trimmedValue.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.joining(","));
            updates.put(field, normalized);
            setPropertyUpdate(propertyUpdates, field, normalized);
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
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private void handleDouble(Map<String, Object> body, Map<String, Object> updates,
                              Map<String, String> propertyUpdates, String field) throws IOException {
        if (!body.containsKey(field)) {
            return;
        }
        Double value = asDouble(body.get(field), field);
        if (value == null) {
            return;
        }
        updates.put(field, value);
        setPropertyUpdate(propertyUpdates, field, String.valueOf(value));
    }

    private Double asDouble(Object value, String field) throws IOException {
        if (value == null) {
            return null;
        }
        double parsed;
        if (value instanceof Number number) {
            parsed = number.doubleValue();
        } else {
            try {
                parsed = Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException e) {
                throwValidation("number.invalid", field);
                return null;
            }
        }
        if (!Double.isFinite(parsed)) {
            throwValidation("number.invalid", field);
            return null;
        }
        return parsed;
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

    /** 严格布尔校验：拒绝数组、对象、数字和非 true/false 字符串，防止非法 JSON 静默转为 false。 */
    private void handleStrictBoolean(Map<String, Object> body, Map<String, Object> updates,
                                     Map<String, String> propertyUpdates, String field) throws IOException {
        if (!body.containsKey(field)) {
            return;
        }
        Object raw = body.get(field);
        if (raw == null) {
            return;
        }
        Boolean parsed;
        if (raw instanceof Boolean b) {
            parsed = b;
        } else if (raw instanceof String s) {
            String trimmed = s.trim();
            if ("true".equalsIgnoreCase(trimmed)) {
                parsed = Boolean.TRUE;
            } else if ("false".equalsIgnoreCase(trimmed)) {
                parsed = Boolean.FALSE;
            } else {
                throwValidation("boolean.invalid", field);
                return;
            }
        } else {
            throwValidation("boolean.invalid", field);
            return;
        }
        updates.put(field, parsed);
        setPropertyUpdate(propertyUpdates, field, String.valueOf(parsed));
    }

    /** 严格范围整数校验：拒绝小数（如 12.5）、布尔、对象/数组及越界值（HTTP 400）。 */
    private void handleStrictIntegerInRange(Map<String, Object> body, Map<String, Object> updates,
                                            Map<String, String> propertyUpdates, String field,
                                            int min, int max) throws IOException {
        if (!body.containsKey(field)) {
            return;
        }
        Object raw = body.get(field);
        if (raw == null) {
            return;
        }
        int parsed;
        if (raw instanceof Integer i) {
            parsed = i;
        } else if (raw instanceof Long l) {
            if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                throwValidation("number.invalid", field);
                return;
            }
            parsed = l.intValue();
        } else if (raw instanceof Double d) {
            if (!Double.isFinite(d) || Math.rint(d) != d || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
                throwValidation("number.invalid", field);
                return;
            }
            parsed = (int) d.doubleValue();
        } else if (raw instanceof Float f) {
            if (!Float.isFinite(f) || Math.rint(f) != f || f < Integer.MIN_VALUE || f > Integer.MAX_VALUE) {
                throwValidation("number.invalid", field);
                return;
            }
            parsed = (int) f.floatValue();
        } else if (raw instanceof String s) {
            try {
                parsed = Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throwValidation("number.invalid", field);
                return;
            }
        } else {
            throwValidation("number.invalid", field);
            return;
        }
        if (parsed < min || parsed > max) {
            throwValidation("number.out.of.range", field);
            return;
        }
        updates.put(field, parsed);
        setPropertyUpdate(propertyUpdates, field, String.valueOf(parsed));
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

    private void rejectIfBooleanChanged(Map<String, Object> body, String field, boolean currentValue) throws IOException {
        if (!body.containsKey(field)) {
            return;
        }
        Boolean incoming = asBoolean(body.get(field));
        if (incoming != null && incoming.booleanValue() != currentValue) {
            log.warn("拒绝通过 API 修改只读开关 {}(当前值 [{}],请求值 [{}])", field, currentValue, incoming);
            throwValidation("field.readonly", field);
        }
    }

    private void enforceSecretReentryOnUrlChange(Map<String, Object> updates,
                                                 String urlField, String currentUrl,
                                                 String secretField, String currentSecret) throws IOException {
        if (!isSet(currentSecret) || !updates.containsKey(urlField) || updates.containsKey(secretField)) {
            return;
        }
        String newUrl = (String) updates.get(urlField);
        String oldTrimmed = currentUrl == null ? "" : currentUrl.trim();
        String newTrimmed = newUrl == null ? "" : newUrl.trim();
        if (oldTrimmed.equals(newTrimmed)) {
            return;
        }
        if (!com.lux032.musicautotagger.util.OutboundUrlValidator.sameOrigin(oldTrimmed, newTrimmed)) {
            log.warn("拒绝在修改 {} 时沿用掩码密钥 {}", urlField, secretField);
            throwValidation("secret.required.on.endpoint.change", secretField);
        }
    }

    private void enforceSecretReentryOnHostPortChange(Map<String, Object> updates,
                                                      String hostField, String currentHost,
                                                      String portField, int currentPort,
                                                      String secretField, String currentSecret) throws IOException {
        if (!isSet(currentSecret) || updates.containsKey(secretField)) {
            return;
        }
        boolean hostChanged = false;
        if (updates.containsKey(hostField)) {
            String oldHost = currentHost == null ? "" : currentHost.trim();
            String newHost = updates.get(hostField) == null ? "" : String.valueOf(updates.get(hostField)).trim();
            hostChanged = !oldHost.equalsIgnoreCase(newHost);
        }
        boolean portChanged = updates.containsKey(portField)
            && updates.get(portField) instanceof Integer p && p != currentPort;
        if (hostChanged || portChanged) {
            log.warn("拒绝在修改 {}:{} 时沿用掩码密钥 {}", hostField, portField, secretField);
            throwValidation("secret.required.on.endpoint.change", secretField);
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
        if (sessionToken == null || token == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
            sessionToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Map<String, Object> readBodyAsMap(HttpServletRequest req) throws IOException {
        try (BufferedReader reader = req.getReader()) {
            Map<String, Object> data = gson.fromJson(reader, Map.class);
            return data == null ? new HashMap<>() : data;
        } catch (com.google.gson.JsonParseException e) {
            throwValidation("body.invalid", null);
            return new HashMap<>();
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

    static class ValidationException extends IOException {
        final String error;
        final String field;

        ValidationException(String error, String field) {
            this.error = error;
            this.field = field;
        }
    }
}

