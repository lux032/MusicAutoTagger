package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.util.Timeout;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.util.I18nUtil;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MusicBrainz API 客户端
 * 用于查询音乐元数据信息
 */
@Slf4j
public class MusicBrainzClient {
    
    private final MusicConfig config;
    private final AlbumArtistPolicy albumArtistPolicy;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CoverArtArchiveClient coverArtArchiveClient;
    private volatile MusicBrainzArtistCache artistCache;
    private static final int RELEASE_CACHE_LIMIT = 64;
    private final java.util.Map<String, JsonNode> fullReleaseCache = Collections.synchronizedMap(
        new LinkedHashMap<String, JsonNode>(RELEASE_CACHE_LIMIT, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, JsonNode> eldest) {
                return size() > RELEASE_CACHE_LIMIT;
            }
        });
    /**
     * Release Group → 时长序列缓存。快速扫描、时长序列匹配、投票后的覆盖检查会反复请求同一张专辑，
     * 每次都要过 1 秒限流。只缓存非空结果，失败 / 无数据下次仍会重试。
     */
    private static final int DURATION_CACHE_LIMIT = 128;
    private static final long DURATION_CACHE_TTL_MS = 30 * 60 * 1000L;
    private final java.util.Map<String, TimedValue<AlbumDurationResult>> albumDurationCache =
        lruMap(DURATION_CACHE_LIMIT);
    private final java.util.Map<String, TimedValue<List<AlbumDurationResult>>> allReleaseDurationCache =
        lruMap(DURATION_CACHE_LIMIT);

    private static final class TimedValue<T> {
        final T value;
        final long at = System.currentTimeMillis();
        TimedValue(T value) { this.value = value; }
        boolean fresh() { return System.currentTimeMillis() - at < DURATION_CACHE_TTL_MS; }
    }

    private static <V> java.util.Map<String, V> lruMap(int limit) {
        return Collections.synchronizedMap(new LinkedHashMap<String, V>(limit, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, V> eldest) {
                return size() > limit;
            }
        });
    }

    private long lastRequestTime = 0;
    private static final long REQUEST_INTERVAL = 1000; // MusicBrainz 要求至少1秒间隔
    static final int MAX_RETRIES = 3; // 最大重试次数
    private static final long RETRY_DELAY_MS = 10000; // 首次重试间隔10秒,之后指数退避
    private static final long MAX_RETRY_DELAY_MS = 60000; // 单次重试等待上限60秒
    private final ThreadLocal<java.util.function.BooleanSupplier> attemptBudgetGate = new ThreadLocal<>();

    /** 当单次解析会话已耗尽出站 HTTP 尝试预算（含重试）时立即中止请求。 */
    static final class BudgetExhaustedException extends IOException {
        BudgetExhaustedException() {
            super("musicbrainz.attempt.budget.exhausted");
        }
    }

    /** 绑定当前线程的出站 HTTP 尝试预算闸门（含自动重试），确保重试不会突破专辑查询上限。 */
    AutoCloseable withAttemptBudget(java.util.function.BooleanSupplier gate) {
        java.util.function.BooleanSupplier previous = attemptBudgetGate.get();
        if (gate == null) {
            attemptBudgetGate.remove();
        } else {
            attemptBudgetGate.set(gate);
        }
        return () -> {
            if (previous == null) {
                attemptBudgetGate.remove();
            } else {
                attemptBudgetGate.set(previous);
            }
        };
    }

    /** 候选发行版曲目数相对文件夹文件数的允许偏差比例 */
    private static final double TRACK_COUNT_TOLERANCE_RATIO = 0.20;
    /**
     * 只有目录里至少有这么多音乐文件时，「文件数」才可能代表一张专辑的曲目数。
     * 低于该值（典型为监控目录根部的散落单曲）时用它做门槛会把所有候选全部否掉。
     */
    private static final int MIN_FILES_FOR_TRACK_COUNT_GATE = 3;
    /** 浮点评分比较容差，避免用 == 判断「同分」导致 tie-break 规则形同虚设 */
    private static final double SCORE_EPSILON = 1e-9;
    
    public MusicBrainzClient(MusicConfig config) {
        this(config, null);
    }

    public MusicBrainzClient(MusicConfig config, MusicBrainzArtistCache artistCache) {
        this.config = config;
        this.albumArtistPolicy = new AlbumArtistPolicy(config);
        this.httpClient = createHttpClient(config);
        this.objectMapper = new ObjectMapper();
        this.artistCache = artistCache;
        this.coverArtArchiveClient = new CoverArtArchiveClient(
            config, this.httpClient, this.objectMapper, this::rateLimit, this::executeRequest);
    }

    public void setArtistCache(MusicBrainzArtistCache artistCache) {
        this.artistCache = artistCache;
    }

    public MusicBrainzArtistCache getArtistCache() {
        return artistCache;
    }

    /** MusicBrainz Artist 实体标准化检索/核验结果。 */
    public record ArtistMatch(
        String mbid,
        String name,
        String sortName,
        String disambiguation,
        String country,
        String areaName,
        String type,
        String lifeSpanBegin,
        String lifeSpanEnd,
        int score,
        List<String> aliases,
        List<String> tags
    ) {
        public ArtistMatch {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            tags = tags == null ? List.of() : List.copyOf(tags);
        }

        public MusicBrainzArtistCache.CachedArtist toCachedArtist(String source) {
            return new MusicBrainzArtistCache.CachedArtist(
                mbid, name, sortName, disambiguation, country, type, aliases, source, System.currentTimeMillis());
        }
    }

    /**
     * 按人名或别名检索 MusicBrainz Artist 实体（复用统一 1 req/s 限流、代理与重试策略）。
     */
    public List<ArtistMatch> searchArtists(String queryName, int limit) throws IOException, InterruptedException {
        if (queryName == null || queryName.isBlank()) {
            return List.of();
        }
        int clampedLimit = Math.max(1, Math.min(25, limit));
        String escaped = escapeLucenePhrase(queryName);
        if (escaped.isEmpty()) {
            return List.of();
        }
        rateLimit();
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("cancelled");
        }
        String query = "artist:\"" + escaped + "\" OR alias:\"" + escaped + "\"";
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = String.format("%s/artist?query=%s&fmt=json&limit=%d",
            config.getMusicBrainzApiUrl(), encodedQuery, clampedLimit);
        try {
            String response = executeRequest(url);
            return parseArtistSearchResponse(response);
        } catch (NonRetryableHttpException e) {
            log.debug("MusicBrainz Artist 检索返回不可重试错误 ({}): {}", queryName, e.getMessage());
            return List.of();
        } catch (IOException e) {
            if (e.getCause() instanceof InterruptedException ie || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("cancelled");
            }
            throw e;
        } catch (ParseException e) {
            log.error("解析 MusicBrainz Artist 检索响应失败", e);
            throw new IOException("解析响应失败", e);
        }
    }

    /**
     * 单次 Lucene OR 合并检索多个姓名变体（如去括号所属后缀或双语分段），最多消耗 1 次外部 API 请求。
     */
    public List<ArtistMatch> searchArtistsAny(List<String> queryNames, int limit) throws IOException, InterruptedException {
        if (queryNames == null || queryNames.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String raw : queryNames) {
            if (raw == null || raw.isBlank()) continue;
            String escaped = escapeLucenePhrase(raw);
            if (!escaped.isEmpty()) {
                cleaned.add(escaped);
            }
            if (cleaned.size() >= 4) {
                break;
            }
        }
        if (cleaned.isEmpty()) {
            return List.of();
        }
        if (cleaned.size() == 1) {
            for (String raw : queryNames) {
                if (raw != null && !raw.isBlank() && !escapeLucenePhrase(raw).isEmpty()) {
                    return searchArtists(raw, limit);
                }
            }
        }
        int clampedLimit = Math.max(1, Math.min(25, limit));
        rateLimit();
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("cancelled");
        }
        StringBuilder query = new StringBuilder();
        for (String escaped : cleaned) {
            if (query.length() > 0) {
                query.append(" OR ");
            }
            query.append("artist:\"").append(escaped).append("\" OR alias:\"").append(escaped).append('"');
        }
        String encodedQuery = URLEncoder.encode(query.toString(), StandardCharsets.UTF_8);
        String url = String.format("%s/artist?query=%s&fmt=json&limit=%d",
            config.getMusicBrainzApiUrl(), encodedQuery, clampedLimit);
        try {
            String response = executeRequest(url);
            return parseArtistSearchResponse(response);
        } catch (NonRetryableHttpException e) {
            log.debug("MusicBrainz Artist 变体合并检索返回不可重试错误 ({}): {}", queryNames, e.getMessage());
            return List.of();
        } catch (IOException e) {
            if (e.getCause() instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("cancelled");
            }
            throw e;
        } catch (ParseException e) {
            log.error("解析 MusicBrainz Artist 变体合并检索响应失败", e);
            throw new IOException("解析响应失败", e);
        }
    }

    /**
     * 按 Artist MBID 拉取单个实体（用于核验从网页外链提取的 UUID）。
     */
    public java.util.Optional<ArtistMatch> getArtistById(String rawMbid) throws IOException, InterruptedException {
        String mbid = MusicBrainzArtistCache.normalizeValidMbid(rawMbid);
        if (mbid == null) {
            return java.util.Optional.empty();
        }
        rateLimit();
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("cancelled");
        }
        String url = String.format("%s/artist/%s?fmt=json&inc=aliases+tags",
            config.getMusicBrainzApiUrl(), mbid);
        try {
            String response = executeRequest(url);
            JsonNode root = objectMapper.readTree(response);
            return java.util.Optional.ofNullable(parseArtistNode(root));
        } catch (NonRetryableHttpException e) {
            log.debug("MusicBrainz Artist 单实体查询未命中 ({}): {}", mbid, e.getMessage());
            return java.util.Optional.empty();
        } catch (IOException e) {
            if (e.getCause() instanceof InterruptedException ie || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("cancelled");
            }
            throw e;
        } catch (ParseException e) {
            log.error("解析 MusicBrainz Artist 单实体响应失败", e);
            throw new IOException("解析响应失败", e);
        }
    }

    List<ArtistMatch> parseArtistSearchResponse(String json) throws IOException {
        List<ArtistMatch> results = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return results;
        }
        JsonNode root = objectMapper.readTree(json);
        JsonNode artists = root.path("artists");
        if (!artists.isArray()) {
            return results;
        }
        for (JsonNode artistNode : artists) {
            ArtistMatch match = parseArtistNode(artistNode);
            if (match != null) {
                results.add(match);
            }
        }
        return results;
    }

    ArtistMatch parseArtistNode(JsonNode artistNode) {
        if (artistNode == null || artistNode.isMissingNode() || !artistNode.isObject()) {
            return null;
        }
        String mbid = MusicBrainzArtistCache.normalizeValidMbid(artistNode.path("id").asText(null));
        String name = blankToNull(artistNode.path("name").asText(null));
        if (mbid == null || name == null) {
            return null;
        }
        String sortName = blankToNull(artistNode.path("sort-name").asText(null));
        String disambiguation = blankToNull(artistNode.path("disambiguation").asText(null));
        String country = blankToNull(artistNode.path("country").asText(null));
        JsonNode area = artistNode.path("area");
        String areaName = area.isObject() ? blankToNull(area.path("name").asText(null)) : null;
        if (country == null && area.isObject()) {
            JsonNode isoCodes = area.path("iso-3166-1-codes");
            if (isoCodes.isArray() && !isoCodes.isEmpty()) {
                country = blankToNull(isoCodes.get(0).asText(null));
            }
        }
        String type = blankToNull(artistNode.path("type").asText(null));
        JsonNode lifeSpan = artistNode.path("life-span");
        String begin = lifeSpan.isObject() ? blankToNull(lifeSpan.path("begin").asText(null)) : null;
        String end = lifeSpan.isObject() ? blankToNull(lifeSpan.path("end").asText(null)) : null;
        int score = artistNode.path("score").asInt(0);

        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        JsonNode aliasNodes = artistNode.path("aliases");
        if (aliasNodes.isArray()) {
            for (JsonNode alias : aliasNodes) {
                String aliasName = blankToNull(alias.path("name").asText(null));
                if (aliasName != null) {
                    aliases.add(aliasName);
                }
            }
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        JsonNode tagNodes = artistNode.path("tags");
        if (tagNodes.isArray()) {
            for (JsonNode tag : tagNodes) {
                String tagName = blankToNull(tag.path("name").asText(null));
                if (tagName != null) {
                    tags.add(tagName);
                }
            }
        }
        return new ArtistMatch(
            mbid, name, sortName, disambiguation, country, areaName, type, begin, end, score,
            List.copyOf(aliases), List.copyOf(tags));
    }

    private void cacheArtistCredits(JsonNode credits, String source) {
        MusicBrainzArtistCache cache = this.artistCache;
        if (cache == null || credits == null || !credits.isArray()) {
            return;
        }
        try {
            for (JsonNode credit : credits) {
                if (credit == null || !credit.isObject()) continue;
                JsonNode artist = credit.path("artist");
                String creditedName = blankToNull(credit.path("name").asText(null));
                cacheArtistNode(cache, artist, creditedName, source);
            }
        } catch (Exception e) {
            log.debug("被动沉淀 MusicBrainz 艺人缓存失败: {}", e.getMessage());
        }
    }

    private void cacheArtistNode(MusicBrainzArtistCache cache, JsonNode artist, String creditedName, String source) {
        if (cache == null || artist == null || !artist.isObject()) {
            return;
        }
        String mbid = MusicBrainzArtistCache.normalizeValidMbid(artist.path("id").asText(null));
        String name = blankToNull(artist.path("name").asText(null));
        if (mbid == null || name == null) {
            return;
        }
        String sortName = blankToNull(artist.path("sort-name").asText(null));
        String disambiguation = blankToNull(artist.path("disambiguation").asText(null));
        String country = blankToNull(artist.path("country").asText(null));
        String type = blankToNull(artist.path("type").asText(null));
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        JsonNode aliasNodes = artist.path("aliases");
        if (aliasNodes.isArray()) {
            for (JsonNode alias : aliasNodes) {
                String aliasName = blankToNull(alias.path("name").asText(null));
                if (aliasName != null) {
                    aliases.add(aliasName);
                }
            }
        }
        List<String> extraNames = (creditedName != null && !creditedName.equals(name))
            ? List.of(creditedName) : List.of();
        cache.recordArtist(mbid, name, sortName, disambiguation, country, type, List.copyOf(aliases), extraNames, source);
    }
    
    /**
     * 创建 HttpClient,支持代理与认证配置（委托给共享 HttpClientFactory）
     */
    private CloseableHttpClient createHttpClient(MusicConfig config) {
        if (com.lux032.musicautotagger.util.HttpClientFactory.isProxyConfigured(config)) {
            log.info(I18nUtil.getMessage("proxy.musicbrainz.enabled", config.getProxyHost(), config.getProxyPort()));
        } else if (config != null && config.isProxyEnabled()) {
            log.warn(I18nUtil.getMessage("proxy.enabled.no.host"));
        }
        // 关闭 HttpClient 自带的自动重试，由本类统一按 Retry-After 与指数退避重试
        return com.lux032.musicautotagger.util.HttpClientFactory
            .apacheHttpClientBuilder(config, Timeout.ofSeconds(30), null, Timeout.ofSeconds(30))
            .disableAutomaticRetries()
            .build();
    }
    
    /**
     * 通过 Recording ID 查询音乐信息
     * @param recordingId MusicBrainz Recording ID
     * @param musicFilesInFolder 文件所在文件夹的音乐文件数量,用于判断是否为单曲
     * @param preferredReleaseGroupId 优先选择的 Release Group ID
     */
    public MusicMetadata getRecordingById(String recordingId, int musicFilesInFolder, String preferredReleaseGroupId) throws IOException, InterruptedException {
        return getRecordingById(recordingId, musicFilesInFolder, preferredReleaseGroupId, null, 0);
    }

    /**
     * 通过 Recording ID 查询音乐信息（支持指定 Release ID）
     * @param recordingId MusicBrainz Recording ID
     * @param musicFilesInFolder 文件所在文件夹的音乐文件数量,用于判断是否为单曲
     * @param preferredReleaseGroupId 优先选择的 Release Group ID
     * @param preferredReleaseId 优先选择的 Release ID（确保版本一致性）
     */
    public MusicMetadata getRecordingById(String recordingId, int musicFilesInFolder, String preferredReleaseGroupId, String preferredReleaseId) throws IOException, InterruptedException {
        return getRecordingById(recordingId, musicFilesInFolder, preferredReleaseGroupId, preferredReleaseId, 0);
    }

    /**
     * 通过 Recording ID 查询音乐信息（支持指定 Release ID 和文件时长）
     * @param recordingId MusicBrainz Recording ID
     * @param musicFilesInFolder 文件所在文件夹的音乐文件数量,用于判断是否为单曲
     * @param preferredReleaseGroupId 优先选择的 Release Group ID
     * @param preferredReleaseId 优先选择的 Release ID（确保版本一致性）
     * @param fileDurationSeconds 当前文件的时长（秒），用于时长匹配备选方案
     */
    public MusicMetadata getRecordingById(String recordingId, int musicFilesInFolder, String preferredReleaseGroupId, String preferredReleaseId, int fileDurationSeconds) throws IOException, InterruptedException {
        // 默认允许「按曲目数猜测专辑」，保持旧调用方的行为不变
        return getRecordingById(recordingId, musicFilesInFolder, preferredReleaseGroupId, preferredReleaseId, fileDurationSeconds, true);
    }

    /**
     * 通过 Recording ID 查询音乐信息（可控制是否允许「按曲目数猜测专辑」）
     *
     * @param allowAlbumGuess 当没有锁定的 Release/ReleaseGroup 时，是否允许退化为
     *                        「挑一个曲目数最接近的 release」。
     *                        传 false 时，若无法可信地确定专辑，将返回不带专辑信息的曲目级元数据，
     *                        由上层按「专辑未确定」处理，避免把曲目错误归入某张旧专辑。
     */
    public MusicMetadata getRecordingById(String recordingId, int musicFilesInFolder, String preferredReleaseGroupId,
                                          String preferredReleaseId, int fileDurationSeconds,
                                          boolean allowAlbumGuess) throws IOException, InterruptedException {
        return getRecordingById(recordingId, musicFilesInFolder, true, preferredReleaseGroupId,
            preferredReleaseId, fileDurationSeconds, allowAlbumGuess);
    }

    public MusicMetadata getRecordingById(String recordingId, int musicFilesInFolder,
                                          boolean musicFilesCountReliable,
                                          String preferredReleaseGroupId, String preferredReleaseId,
                                          int fileDurationSeconds, boolean allowAlbumGuess)
                                          throws IOException, InterruptedException {
        rateLimit();

        // 增加 media 来获取曲目数信息，增加 artist-rels 和 work-rels 以获取作曲家、作词家信息
        String url = String.format("%s/recording/%s?fmt=json&inc=artists+releases+tags+release-groups+artist-rels+work-rels+work-level-rels+media",
            config.getMusicBrainzApiUrl(), recordingId);

        try {
            String response = executeRequest(url);
            MusicMetadata metadata = parseRecordingResponse(response, musicFilesInFolder, musicFilesCountReliable,
                preferredReleaseGroupId, preferredReleaseId, fileDurationSeconds, allowAlbumGuess);
            
            // 尝试获取封面 URL
            if (metadata.getReleaseGroupId() != null) {
                String coverArtUrl = getCoverArtUrl(metadata.getReleaseGroupId());
                metadata.setCoverArtUrl(coverArtUrl);
            }
            
            return metadata;
        } catch (ParseException e) {
            log.error("解析响应失败", e);
            throw new IOException("解析响应失败", e);
        }
    }
    
    /**
     * 获取专辑的完整时长序列
     * @param releaseGroupId Release Group ID
     * @return 包含时长列表和选中的 Release ID 的结果对象
     */
    /** 单次查询期间使用的不可变配置快照，避免并发热更新导致缓存键与选版/署名不一致。 */
    private record EffectiveLookupConfig(
            List<String> countryPriority,
            boolean preferNativeArtistName,
            boolean collapseToVariousArtists) {
        String cacheKeyFor(String releaseGroupId) {
            String countryKey = MusicBrainzReleasePriority.normalizeCountryPriorityKey(countryPriority);
            return releaseGroupId + "|cp=" + countryKey
                + "|nat=" + preferNativeArtistName
                + "|va=" + collapseToVariousArtists;
        }
    }

    private List<String> releaseCountryPriority() {
        List<String> priority = config != null ? config.getReleaseCountryPriority() : null;
        return priority == null || priority.isEmpty() ? List.of() : List.copyOf(priority);
    }

    private EffectiveLookupConfig snapshotLookupConfig() {
        return new EffectiveLookupConfig(
            releaseCountryPriority(),
            isPreferNativeArtistName(),
            albumArtistPolicy.shouldCollapse());
    }

    public AlbumDurationResult getAlbumDurationSequence(String releaseGroupId) throws IOException, InterruptedException {
        EffectiveLookupConfig snap = snapshotLookupConfig();
        String cacheKey = releaseGroupId == null ? null : snap.cacheKeyFor(releaseGroupId);
        TimedValue<AlbumDurationResult> cached = cacheKey == null ? null : albumDurationCache.get(cacheKey);
        if (cached != null && cached.fresh()) {
            log.debug("使用缓存的专辑时长序列: {}", releaseGroupId);
            return cached.value;
        }
        AlbumDurationResult result = fetchAlbumDurationSequence(releaseGroupId, snap);
        if (cacheKey != null && result != null && result.getDurations() != null
            && !result.getDurations().isEmpty()) {
            albumDurationCache.put(cacheKey, new TimedValue<>(result));
        }
        return result;
    }

    private AlbumDurationResult fetchAlbumDurationSequence(String releaseGroupId, EffectiveLookupConfig snap)
            throws IOException, InterruptedException {
        rateLimit();
        
        // 查询 release-group 获取所有 releases
        String url = String.format("%s/release-group/%s?fmt=json&inc=releases+media",
            config.getMusicBrainzApiUrl(), releaseGroupId);
        
        try {
            String response = executeRequest(url);
            JsonNode root = objectMapper.readTree(response);
            String releaseType = normalizeReleaseType(root.path("primary-type").asText(""));
            boolean compilation = hasCompilationSecondaryType(root);
            
            // 获取所有 releases
            JsonNode releases = root.path("releases");
            if (!releases.isArray() || releases.size() == 0) {
                log.warn("Release Group {} 没有找到任何 releases", releaseGroupId);
                return new AlbumDurationResult(new ArrayList<>(), null);
            }
            
            log.info("Release Group {} 共有 {} 个releases，开始查找有时长数据的版本",
                releaseGroupId, releases.size());
            
            // 排除纯视频格式；构造探查序列（前 3 个优先地区版本 + 前 3 个基线格式版本，去重），
            // 确保即便优先地区前 3 个版本均缺时长，也能安全回退到有完整时长的基线版本。
            List<JsonNode> audioReleases = new ArrayList<>();
            for (JsonNode release : releases) {
                if (scoreReleaseForDuration(release) >= 0) {
                    audioReleases.add(release);
                }
            }
            List<JsonNode> probeReleases = buildSingleProbeOrder(audioReleases, snap.countryPriority());
            
            IOException lastFetchError = null;
            for (int i = 0; i < probeReleases.size(); i++) {
                JsonNode release = probeReleases.get(i);
                String releaseId = release.path("id").asText();
                String releaseTitle = release.path("title").asText();
                String mediaFormat = extractMediaFormat(release);
                String releaseCountry = blankToNull(release.path("country").asText(null));
                String releaseDate = blankToNull(release.path("date").asText(null));
                
                log.debug("尝试第 {}/{} 个release: {} (ID: {}, 地区: {}, 格式: {})",
                    i + 1, probeReleases.size(), releaseTitle, releaseId, releaseCountry, mediaFormat);
                
                List<Integer> durations;
                try {
                    durations = getReleaseDurationSequence(releaseId);
                } catch (IOException e) {
                    lastFetchError = e;
                    log.warn("获取 Release {}({}) 时长失败，继续尝试下一个候选: {}",
                        releaseTitle, releaseId, e.getMessage());
                    continue;
                }
                
                // 如果获取到有效的时长序列（至少有一些曲目），返回结果
                if (!durations.isEmpty()) {
                    log.info("成功从 release {} 获取到 {} 首曲目的时长序列",
                        releaseTitle, durations.size());
                    // 带上真实曲目数：部分曲目缺时长时，覆盖检查不能把缺的当成「版本里没有」
                    return new AlbumDurationResult(durations, releaseId, releaseTitle,
                        Math.max(calculateTrackCount(release), durations.size()),
                        mediaFormat, releaseType, compilation, null, releaseCountry, releaseDate);
                } else {
                    log.debug("Release {} 没有时长数据，继续尝试下一个", releaseTitle);
                }
            }

            if (lastFetchError != null) {
                throw lastFetchError;
            }

            // 所有尝试都失败
            log.warn("Release Group {} 的前{}个release都没有时长数据",
                releaseGroupId, probeReleases.size());
            return new AlbumDurationResult(new ArrayList<>(), null);
            
        } catch (ParseException e) {
            log.error("解析 Release Group 响应失败", e);
            return new AlbumDurationResult(new ArrayList<>(), null);
        }
    }

    private List<JsonNode> buildSingleProbeOrder(List<JsonNode> audioReleases, List<String> countryPriority) {
        if (audioReleases.isEmpty()) {
            return List.of();
        }
        List<JsonNode> baseline = new ArrayList<>(audioReleases);
        baseline.sort((r1, r2) -> MusicBrainzReleasePriority.compareReleaseJson(r1, r2, List.of()));
        if (countryPriority.isEmpty()) {
            return baseline.subList(0, Math.min(3, baseline.size()));
        }
        List<JsonNode> preferred = new ArrayList<>();
        for (JsonNode r : audioReleases) {
            if (MusicBrainzReleasePriority.countryRank(r.path("country").asText(null), countryPriority) != Integer.MAX_VALUE) {
                preferred.add(r);
            }
        }
        preferred.sort((r1, r2) -> MusicBrainzReleasePriority.compareReleaseJson(r1, r2, countryPriority));

        List<JsonNode> combined = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        int prefLimit = Math.min(3, preferred.size());
        for (int i = 0; i < prefLimit; i++) {
            JsonNode r = preferred.get(i);
            if (seenIds.add(r.path("id").asText(""))) {
                combined.add(r);
            }
        }
        int fallbackAdded = 0;
        for (JsonNode r : baseline) {
            if (fallbackAdded >= 3) {
                break;
            }
            if (seenIds.add(r.path("id").asText(""))) {
                combined.add(r);
                fallbackAdded++;
            }
        }
        return combined;
    }
    
    /**
     * 获取 Release Group 下所有 Release 的时长序列（用于精确匹配）
     * 改进版本：返回所有有效的 Release 时长序列，而不是只返回第一个
     *
     * @param releaseGroupId Release Group ID
     * @return 包含所有 Release 时长序列的列表
     */
    public List<AlbumDurationResult> getAllReleaseDurationSequences(String releaseGroupId) throws IOException, InterruptedException {
        EffectiveLookupConfig snap = snapshotLookupConfig();
        String cacheKey = releaseGroupId == null ? null : snap.cacheKeyFor(releaseGroupId);
        TimedValue<List<AlbumDurationResult>> cached =
            cacheKey == null ? null : allReleaseDurationCache.get(cacheKey);
        if (cached != null && cached.fresh()) {
            log.debug("使用缓存的全部版本时长序列: {}", releaseGroupId);
            return new ArrayList<>(cached.value);
        }
        List<AlbumDurationResult> result = fetchAllReleaseDurationSequences(releaseGroupId, snap);
        if (cacheKey != null && result != null && !result.isEmpty()) {
            allReleaseDurationCache.put(cacheKey, new TimedValue<>(new ArrayList<>(result)));
        }
        return result;
    }

    private List<AlbumDurationResult> fetchAllReleaseDurationSequences(String releaseGroupId, EffectiveLookupConfig snap)
            throws IOException, InterruptedException {
        rateLimit();
        
        List<AlbumDurationResult> results = new ArrayList<>();
        
        // 查询 release-group 获取所有 releases
        // inc 必须带 artist-credits：候选专辑的艺术家要从 MusicBrainz 本身来，
        // 否则上层拿不到艺术家只能退化成 "Various Artists"（单一艺术家专辑会被误判为合辑）。
        String url = String.format("%s/release-group/%s?fmt=json&inc=releases+media+artist-credits",
            config.getMusicBrainzApiUrl(), releaseGroupId);
        
        try {
            String response = executeRequest(url);
            JsonNode root = objectMapper.readTree(response);
            String releaseType = normalizeReleaseType(root.path("primary-type").asText(""));
            boolean compilation = hasCompilationSecondaryType(root);
            // Release Group 层的艺术家（使用本次查询的不可变配置快照）
            String groupArtist = resolveAlbumArtistFromCredits(root.path("artist-credit"), snap);
            
            // 获取所有 releases
            JsonNode releases = root.path("releases");
            if (!releases.isArray() || releases.size() == 0) {
                log.warn("Release Group {} 没有找到任何 releases", releaseGroupId);
                return results;
            }
            
            log.info("Release Group {} 共有 {} 个 releases，获取所有有效版本的时长序列",
                releaseGroupId, releases.size());
            
            List<JsonNode> audioReleases = new ArrayList<>();
            for (JsonNode release : releases) {
                if (scoreReleaseForDuration(release) >= 0) {
                    audioReleases.add(release);
                }
            }

            List<JsonNode> baselineSorted = new ArrayList<>(audioReleases);
            baselineSorted.sort((r1, r2) -> MusicBrainzReleasePriority.compareReleaseJson(r1, r2, List.of()));

            if (snap.countryPriority().isEmpty()) {
                int tryCount = Math.min(10, baselineSorted.size());
                int successCount = 0;
                for (int i = 0; i < tryCount && successCount < 5; i++) {
                    AlbumDurationResult fetched = fetchSingleReleaseDurationResult(
                        baselineSorted.get(i), releaseType, compilation, groupArtist, snap);
                    if (fetched != null) {
                        results.add(fetched);
                        successCount++;
                    }
                }
                log.info("共获取到 {} 个 release 的时长序列", results.size());
                return results;
            }

            // 配置了地区优先级时：同时保留「优先地区候选（优先覆盖不同曲目数，最多 3 个）」与
            // 「与空地区配置等价的基线候选（最多 5 个）」，按 releaseId 去重且优先地区排在前面。
            // 这样既保证 JP 等目标地区不被超过 5 个非目标地区 CD 截断，又保证绝不因地区配置
            // 挤掉原本应参与跨专辑歧义/margin 判定的基线候选。
            List<JsonNode> preferredSorted = new ArrayList<>();
            for (JsonNode release : audioReleases) {
                if (MusicBrainzReleasePriority.countryRank(
                        release.path("country").asText(null), snap.countryPriority()) != Integer.MAX_VALUE) {
                    preferredSorted.add(release);
                }
            }
            preferredSorted.sort((r1, r2) -> MusicBrainzReleasePriority.compareReleaseJson(r1, r2, snap.countryPriority()));
            List<JsonNode> preferredDiversified = prioritizeDistinctTrackCounts(preferredSorted);

            Map<String, AlbumDurationResult> fetchedById = new LinkedHashMap<>();
            Set<String> attemptedIds = new LinkedHashSet<>();
            IOException lastFetchError = null;

            int prefSuccess = 0;
            int prefAttempts = Math.min(6, preferredDiversified.size());
            for (int i = 0; i < prefAttempts && prefSuccess < 3; i++) {
                JsonNode release = preferredDiversified.get(i);
                String releaseId = release.path("id").asText("");
                if (releaseId.isEmpty() || !attemptedIds.add(releaseId)) {
                    continue;
                }
                try {
                    AlbumDurationResult fetched = fetchSingleReleaseDurationResult(
                        release, releaseType, compilation, groupArtist, snap);
                    if (fetched != null) {
                        fetchedById.put(releaseId, fetched);
                        prefSuccess++;
                    }
                } catch (IOException e) {
                    lastFetchError = e;
                    log.warn("拉取优先地区 Release {} 时长失败，继续回退到后续/基线候选: {}", releaseId, e.getMessage());
                }
            }

            int baselineSuccess = 0;
            int baselineTryBudget = Math.min(10, baselineSorted.size());
            for (int i = 0; i < baselineTryBudget && baselineSuccess < 5; i++) {
                JsonNode release = baselineSorted.get(i);
                String releaseId = release.path("id").asText("");
                if (releaseId.isEmpty()) {
                    continue;
                }
                if (fetchedById.containsKey(releaseId)) {
                    baselineSuccess++;
                    continue;
                }
                if (!attemptedIds.add(releaseId)) {
                    continue;
                }
                try {
                    AlbumDurationResult fetched = fetchSingleReleaseDurationResult(
                        release, releaseType, compilation, groupArtist, snap);
                    if (fetched != null) {
                        fetchedById.put(releaseId, fetched);
                        baselineSuccess++;
                    }
                } catch (IOException e) {
                    lastFetchError = e;
                    log.warn("拉取基线 Release {} 时长失败，继续尝试后续候选: {}", releaseId, e.getMessage());
                }
            }

            if (fetchedById.isEmpty() && lastFetchError != null) {
                throw lastFetchError;
            }

            results.addAll(fetchedById.values());
            log.info("共获取到 {} 个 release 的时长序列（优先地区有效: {}, 基线有效: {}）",
                results.size(), prefSuccess, baselineSuccess);
            return results;
            
        } catch (ParseException e) {
            log.error("解析 Release Group 响应失败", e);
            return results;
        }
    }

    /** 将优先地区候选中曲目数各不相同的首个版本排到前面，其余保持原优先级顺序，避免重复曲目数占满配额。 */
    private List<JsonNode> prioritizeDistinctTrackCounts(List<JsonNode> sorted) {
        if (sorted.size() <= 1) {
            return sorted;
        }
        List<JsonNode> firstPerCount = new ArrayList<>();
        List<JsonNode> extras = new ArrayList<>();
        Set<Integer> seenCounts = new LinkedHashSet<>();
        for (JsonNode r : sorted) {
            int count = calculateTrackCount(r);
            if (seenCounts.add(count)) {
                firstPerCount.add(r);
            } else {
                extras.add(r);
            }
        }
        firstPerCount.addAll(extras);
        return firstPerCount;
    }

    private AlbumDurationResult fetchSingleReleaseDurationResult(
            JsonNode release, String releaseType, boolean compilation,
            String groupArtist, EffectiveLookupConfig snap) throws IOException, InterruptedException {
        String releaseId = release.path("id").asText();
        String releaseTitle = release.path("title").asText();
        int trackCount = calculateTrackCount(release);
        String mediaFormat = extractMediaFormat(release);
        String releaseCountry = blankToNull(release.path("country").asText(null));
        String releaseDate = blankToNull(release.path("date").asText(null));

        String releaseArtist = resolveAlbumArtistFromCredits(release.path("artist-credit"), snap);
        if (releaseArtist == null) {
            releaseArtist = groupArtist;
        }

        log.debug("尝试获取 release {} 的时长序列 (ID: {}, 曲目数: {}, 地区: {}, 格式: {})",
            releaseTitle, releaseId, trackCount, releaseCountry, mediaFormat);

        List<Integer> durations = getReleaseDurationSequence(releaseId);
        if (durations.isEmpty()) {
            return null;
        }
        log.info("✓ 成功获取 release {} 的时长序列 ({} 首曲目, 地区: {}, 格式: {})",
            releaseTitle, durations.size(), releaseCountry, mediaFormat);
        return new AlbumDurationResult(durations, releaseId, releaseTitle, trackCount, mediaFormat,
            releaseType, compilation, releaseArtist, releaseCountry, releaseDate);
    }
    
    /**
     * 获取 Release Group 下实际存在的 Release，供人工审核候选展开使用。
     * 与 duration matching 不同：Release 即使没有有效时长也会保留。
     */
    public List<AlbumDurationResult> getReleasesForGroup(String releaseGroupId)
            throws IOException, InterruptedException {
        EffectiveLookupConfig snap = snapshotLookupConfig();
        rateLimit();
        List<AlbumDurationResult> results = new ArrayList<>();
        String url = String.format("%s/release-group/%s?fmt=json&inc=releases+media+artist-credits",
            config.getMusicBrainzApiUrl(), releaseGroupId);

        try {
            String response = executeRequest(url);
            JsonNode root = objectMapper.readTree(response);
            String releaseType = normalizeReleaseType(root.path("primary-type").asText(""));
            boolean compilation = hasCompilationSecondaryType(root);
            String groupArtist = resolveAlbumArtistFromCredits(root.path("artist-credit"), snap);
            JsonNode releases = root.path("releases");
            if (!releases.isArray() || releases.size() == 0) {
                log.info("Release Group {} 确认没有任何 Release（正常响应，非请求失败）", releaseGroupId);
                return results;
            }

            List<JsonNode> sortedReleases = new ArrayList<>();
            for (JsonNode release : releases) {
                if (scoreReleaseForDuration(release) >= 0) {
                    sortedReleases.add(release);
                }
            }
            sortedReleases.sort((r1, r2) -> MusicBrainzReleasePriority.compareReleaseJson(r1, r2, snap.countryPriority()));

            int tryCount = Math.min(10, sortedReleases.size());
            for (int i = 0; i < tryCount; i++) {
                JsonNode release = sortedReleases.get(i);
                String releaseId = release.path("id").asText();
                if (releaseId.isEmpty()) {
                    log.warn("Release Group {} 返回缺少 releaseId 的版本，已忽略", releaseGroupId);
                    continue;
                }

                String releaseTitle = release.path("title").asText();
                int trackCount = calculateTrackCount(release);
                String mediaFormat = extractMediaFormat(release);
                String releaseCountry = blankToNull(release.path("country").asText(null));
                String releaseDate = blankToNull(release.path("date").asText(null));
                String releaseArtist = resolveAlbumArtistFromCredits(release.path("artist-credit"), snap);
                if (releaseArtist == null) {
                    releaseArtist = groupArtist;
                }

                List<Integer> durations;
                try {
                    durations = getReleaseDurationSequence(releaseId);
                } catch (IOException e) {
                    log.warn("Release {} 存在，但获取时长数据失败，按无可用时长保留候选: {}",
                        releaseId, e.getMessage());
                    durations = new ArrayList<>();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                }

                if (durations.isEmpty()) {
                    log.info("Release {} 存在，但未取得可用曲目时长（曲目缺少 length/recording.length 或为纯视频版本）",
                        releaseTitle);
                } else {
                    log.info("✓ Release {} 存在且获取到 {} 首曲目时长", releaseTitle, durations.size());
                }
                results.add(new AlbumDurationResult(durations, releaseId, releaseTitle, trackCount,
                    mediaFormat, releaseType, compilation, releaseArtist, releaseCountry, releaseDate));
            }
            log.info("Release Group {} 共展开 {} 个实际 Release 候选", releaseGroupId, results.size());
            return results;
        } catch (ParseException e) {
            log.error("解析 Release Group 响应失败", e);
            return results;
        }
    }

    /** 从 artist-credit 节点按不可变配置快照解析专辑艺术家。 */
    private String resolveAlbumArtistFromCredits(JsonNode artistCredits, EffectiveLookupConfig snap) {
        return albumArtistPolicy.resolveFromCredits(
            artistCredits, snap.collapseToVariousArtists(), snap.preferNativeArtistName());
    }

    /** 从 artist-credit 节点按配置解析专辑艺术家。 */
    private String resolveAlbumArtistFromCredits(JsonNode artistCredits) {
        return albumArtistPolicy.resolveFromCredits(artistCredits);
    }

    /**
     * 专辑时长序列结果（包含 Release ID 和媒体格式）
     */
    @Data
    public static class AlbumDurationResult {
        private final List<Integer> durations;
        private final String releaseId;
        private final String releaseTitle;
        private final int trackCount;
        private final String mediaFormat;  // 新增：媒体格式（如 "CD", "Digital Media" 等）
        private final String releaseType;
        private final boolean compilation;
        /** 该 Release 的专辑艺术家（来自 MusicBrainz artist-credit）；未知时为 null */
        private final String albumArtist;
        /** 该 Release 的发行地区代码（如 "JP", "US"）；未知时为 null */
        private final String releaseCountry;
        /** 该 Release 的发行日期（ISO 格式，如 "2020-04-20"）；未知时为 null */
        private final String releaseDate;
        
        public AlbumDurationResult(List<Integer> durations, String releaseId) {
            this(durations, releaseId, null, durations != null ? durations.size() : 0, null);
        }
        
        public AlbumDurationResult(List<Integer> durations, String releaseId, String releaseTitle, int trackCount) {
            this(durations, releaseId, releaseTitle, trackCount, null);
        }
        
        public AlbumDurationResult(List<Integer> durations, String releaseId, String releaseTitle, int trackCount, String mediaFormat) {
            this(durations, releaseId, releaseTitle, trackCount, mediaFormat, null, false);
        }

        public AlbumDurationResult(List<Integer> durations, String releaseId, String releaseTitle, int trackCount,
                                   String mediaFormat, String releaseType, boolean compilation) {
            this(durations, releaseId, releaseTitle, trackCount, mediaFormat, releaseType, compilation, null);
        }

        public AlbumDurationResult(List<Integer> durations, String releaseId, String releaseTitle, int trackCount,
                                   String mediaFormat, String releaseType, boolean compilation, String albumArtist) {
            this(durations, releaseId, releaseTitle, trackCount, mediaFormat, releaseType, compilation,
                albumArtist, null, null);
        }

        public AlbumDurationResult(List<Integer> durations, String releaseId, String releaseTitle, int trackCount,
                                   String mediaFormat, String releaseType, boolean compilation, String albumArtist,
                                   String releaseCountry, String releaseDate) {
            this.albumArtist = albumArtist;
            this.durations = durations;
            this.releaseId = releaseId;
            this.releaseTitle = releaseTitle;
            this.trackCount = trackCount;
            this.mediaFormat = mediaFormat;
            this.releaseType = releaseType;
            this.compilation = compilation;
            this.releaseCountry = releaseCountry;
            this.releaseDate = releaseDate;
        }
    }
    
    /**
     * 获取 Release 的完整时长序列
     * @param releaseId Release ID
     * @return 曲目时长列表(秒)
     */
    public List<Integer> getReleaseDurationSequence(String releaseId) throws IOException, InterruptedException {
        rateLimit();
        
        String url = String.format("%s/release/%s?fmt=json&inc=recordings+media",
            config.getMusicBrainzApiUrl(), releaseId);
        
        try {
            String response = executeRequest(url);
            JsonNode root = objectMapper.readTree(response);
            
            // DEBUG: 打印完整的响应结构
            log.debug("Release API 响应: {}", response);
            
            List<Integer> durations = extractDurationsFromReleaseJson(root);
            log.info("Release {} 时长解析完成，可用时长曲目数: {}", releaseId, durations.size());
            return durations;
            
        } catch (ParseException e) {
            log.error("解析 Release 响应失败", e);
            return new ArrayList<>();
        }
    }

    /** 从 Release API 的已解析响应中提取非视频音轨的有效时长（秒）。 */
    /** 曲目有效时长（毫秒）：优先 track.length，缺失时用 recording.length。覆盖检查与逐首匹配必须一致 */
    static int effectiveTrackLengthMs(JsonNode track) {
        int ms = track.path("length").asInt(0);
        return ms > 0 ? ms : track.path("recording").path("length").asInt(0);
    }

    List<Integer> extractDurationsFromReleaseJson(JsonNode root) {
        List<Integer> durations = new ArrayList<>();
        JsonNode media = root.path("media");
        log.debug("Media 节点存在: {}, isArray: {}, size: {}",
            !media.isMissingNode(), media.isArray(), media.size());

        if (media.isArray()) {
            for (int i = 0; i < media.size(); i++) {
                JsonNode medium = media.get(i);
                log.debug("Medium[{}] 内容: {}", i, medium.toString());

                String format = medium.path("format").asText("").toLowerCase();
                if (isVideoFormat(format)) {
                    log.debug("跳过视频格式媒体: {} (format: {})", i, format);
                    continue;
                }

                JsonNode tracks = medium.path("tracks");
                log.debug("Tracks 节点存在: {}, isArray: {}, size: {}",
                    !tracks.isMissingNode(), tracks.isArray(), tracks.size());
                if (!tracks.isArray()) {
                    continue;
                }

                for (JsonNode track : tracks) {
                    JsonNode recording = track.path("recording");
                    if (recording.path("video").asBoolean(false)) {
                        log.debug("跳过视频 track: {}", track.path("title").asText(""));
                        continue;
                    }

                    int durationMs = effectiveTrackLengthMs(track);
                    log.debug("Track effective length: {} ms", durationMs);
                    if (durationMs > 0) {
                        durations.add((durationMs + 500) / 1000);
                    }
                }
            }
        }
        return durations;
    }

    /**
     * 为 Release 打分，用于选择最适合获取时长序列的版本
     * 优先选择 CD 或 Digital 格式
     */
    private int scoreReleaseForDuration(JsonNode release) {
        return MusicBrainzReleasePriority.mediaFormatScore(release);
    }

    /**
     * 判断媒体格式是否为视频格式
     * @param format 媒体格式字符串（小写）
     * @return 如果是视频格式返回true
     */
    private boolean isVideoFormat(String format) {
        return MusicBrainzReleasePriority.isVideoFormat(format);
    }
    
    /**
     * 从 Release 节点提取媒体格式
     * @param release Release JSON 节点
     * @return 媒体格式字符串（如 "CD", "Digital Media"），如果无法确定返回 null
     */
    private String extractMediaFormat(JsonNode release) {
        return MusicBrainzReleasePriority.extractPrimaryAudioFormat(release);
    }

    /**
     * 获取封面图片 URL(带重试机制) - 公共方法
     */
    public String getCoverArtUrlByReleaseGroupId(String releaseGroupId) {
        return coverArtArchiveClient.getCoverArtUrlByReleaseGroupId(releaseGroupId);
    }

    /**
     * 获取封面 URL，并告知调用方「动画版偏好是否完整生效」。
     * 限流/网络失败导致回退到默认封面时 degraded=true，
     * 调用方应该避免把这张封面写进持久化的专辑缓存，否则一次限流会把真人封面永久钉在动画命名空间里。
     */
    public CoverArtResolution resolveCoverArtByReleaseGroupId(String releaseGroupId) {
        return coverArtArchiveClient.resolveCoverArtByReleaseGroupId(releaseGroupId);
    }

    /**
     * 封面解析结果
     */
    public static class CoverArtResolution {
        private final String coverArtUrl;
        private final boolean animePreferenceDegraded;

        public CoverArtResolution(String coverArtUrl, boolean animePreferenceDegraded) {
            this.coverArtUrl = coverArtUrl;
            this.animePreferenceDegraded = animePreferenceDegraded;
        }

        public String getCoverArtUrl() {
            return coverArtUrl;
        }

        /** true = 本次回退到默认封面是因为查询失败，而不是确定没有动画版 */
        public boolean isAnimePreferenceDegraded() {
            return animePreferenceDegraded;
        }
    }

    private String getCoverArtUrl(String releaseGroupId) {
        return coverArtArchiveClient.getCoverArtUrlByReleaseGroupId(releaseGroupId);
    }

    /**
     * 下载封面图片(带重试机制)
     */
    public byte[] downloadCoverArt(String url) {
        return coverArtArchiveClient.downloadCoverArt(url);
    }
    
    /**
     * 通过 AcoustID 查询音乐信息
     */
    public List<MusicMetadata> searchByAcoustId(String acoustId) throws IOException, InterruptedException {
        rateLimit();
        
        String url = String.format("%s/recording?query=acoustid:%s&fmt=json",
            config.getMusicBrainzApiUrl(), acoustId);
        
        try {
            String response = executeRequest(url);
            return parseSearchResponse(response);
        } catch (ParseException e) {
            log.error("解析响应失败", e);
            throw new IOException("解析响应失败", e);
        }
    }
    
    /**
     * 转义 Lucene 短语查询中的反斜杠、引号与换行符，防止包含引号或语法字符的曲名/专辑名破坏查询。
     */
    static String escapeLucenePhrase(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("\r", " ")
            .replace("\n", " ")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .trim();
    }

    /**
     * 通过标题和艺术家搜索
     */
    public List<MusicMetadata> searchByTitleAndArtist(String title, String artist) throws IOException, InterruptedException {
        rateLimit();
        
        String query = "recording:\"" + escapeLucenePhrase(title) + "\" AND artist:\"" + escapeLucenePhrase(artist) + "\"";
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        
        String url = String.format("%s/recording?query=%s&fmt=json&limit=5",
            config.getMusicBrainzApiUrl(), encodedQuery);
        
        try {
            String response = executeRequest(url);
            return parseSearchResponse(response);
        } catch (ParseException e) {
            log.error("解析响应失败", e);
            throw new IOException("解析响应失败", e);
        }
    }
    
    /**
     * 搜索专辑（用于快速扫描）
     * @param albumName 专辑名称
     * @param artistName 艺术家名称（可选）
     * @return 搜索结果列表
     */
    public List<MusicMetadata> searchAlbum(String albumName, String artistName) throws IOException, InterruptedException {
        rateLimit();
        
        // 构建搜索查询 - 先做 Lucene 短语转义并构建完整查询，再进行 URL 编码
        StringBuilder query = new StringBuilder();
        query.append("release:\"").append(escapeLucenePhrase(albumName)).append("\"");
        
        if (artistName != null && !artistName.trim().isEmpty()) {
            query.append(" AND artist:\"").append(escapeLucenePhrase(artistName)).append("\"");
        }
        
        // 对整个查询字符串进行URL编码
        String encodedQuery = URLEncoder.encode(query.toString(), StandardCharsets.UTF_8);
        
        String url = String.format("%s/release?query=%s&fmt=json&limit=10",
            config.getMusicBrainzApiUrl(), encodedQuery);
        
        try {
            String response = executeRequest(url);
            return parseAlbumSearchResponse(response);
        } catch (ParseException e) {
            log.error("解析专辑搜索响应失败", e);
            throw new IOException("解析响应失败", e);
        }
    }
    
    /** Returns all candidates within the bounded maintenance search, or none if it is incomplete. */
    List<JsonNode> searchReleasesForAlbumArtistRefresh(String album, String releaseGroupId)
            throws IOException, InterruptedException {
        String url;
        String countKey;
        if (releaseGroupId.isEmpty()) {
            String quotedAlbum = escapeLucenePhrase(album);
            String query = URLEncoder.encode("release:\"" + quotedAlbum + "\"", StandardCharsets.UTF_8);
            url = String.format("%s/release?query=%s&fmt=json&limit=25", config.getMusicBrainzApiUrl(), query);
            countKey = "count";
        } else {
            url = String.format("%s/release?release-group=%s&limit=100&fmt=json",
                config.getMusicBrainzApiUrl(), releaseGroupId);
            countKey = "release-count";
        }
        rateLimit();
        try {
            JsonNode response = objectMapper.readTree(executeRequest(url));
            JsonNode releases = response.path("releases");
            if (!releases.isArray() || response.path(countKey).asInt(-1) != releases.size()) {
                return List.of();
            }
            List<JsonNode> candidates = new ArrayList<>();
            releases.forEach(candidates::add);
            return candidates;
        } catch (ParseException e) {
            throw new IOException("Invalid MusicBrainz album search response", e);
        }
    }

    private String normalizeReleaseType(String value) {
        return value == null || value.trim().isEmpty()
            ? null : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private boolean hasCompilationSecondaryType(JsonNode releaseGroup) {
        JsonNode secondaryTypes = releaseGroup.path("secondary-types");
        if (secondaryTypes.isArray()) {
            for (JsonNode secondaryType : secondaryTypes) {
                if ("compilation".equalsIgnoreCase(secondaryType.asText(""))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 将 MusicBrainz Release Group 类型写入统一元数据模型。 */
    private void applyReleaseGroupMetadata(MusicMetadata metadata, JsonNode releaseGroup) {
        if (metadata == null || releaseGroup == null || releaseGroup.isMissingNode()) {
            return;
        }
        String releaseGroupId = releaseGroup.path("id").asText("").trim();
        if (!releaseGroupId.isEmpty()) {
            metadata.setReleaseGroupId(releaseGroupId);
        }
        String primaryType = normalizeReleaseType(releaseGroup.path("primary-type").asText(""));
        if (primaryType != null) {
            metadata.setReleaseType(primaryType);
        }
        metadata.setCompilation(hasCompilationSecondaryType(releaseGroup));
    }

    /**
     * 解析专辑搜索响应
     */
    private List<MusicMetadata> parseAlbumSearchResponse(String json) throws IOException {
        List<MusicMetadata> results = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);
        JsonNode releases = root.path("releases");
        
        if (!releases.isArray()) {
            return results;
        }
        
        for (JsonNode release : releases) {
            MusicMetadata metadata = new MusicMetadata();
            
            // 基本信息
            metadata.setAlbum(release.path("title").asText());
            metadata.setReleaseDate(release.path("date").asText());
            metadata.setReleaseId(release.path("id").asText(""));
            metadata.setScore(release.path("score").asInt(0));
            
            // Release Group ID 与类型；helper 会安全忽略缺失节点
            applyReleaseGroupMetadata(metadata, release.path("release-group"));
            
            // 艺术家信息
            JsonNode artistCredits = release.path("artist-credit");
            if (artistCredits.isArray() && artistCredits.size() > 0) {
                String resolvedAlbumArtist = albumArtistPolicy.resolveFromCredits(artistCredits);
                metadata.setAlbumArtist(resolvedAlbumArtist);
                if (resolvedAlbumArtist != null && !AlbumArtistPolicy.VARIOUS_ARTISTS.equals(resolvedAlbumArtist)) {
                    metadata.setAlbumArtists(artistNames(artistCredits));
                    metadata.setAlbumArtistCredit(encodeArtistCredit(artistCredits, resolvedAlbumArtist));
                }
                applyRecordingArtistCredit(metadata, artistCredits);
            }
            
            // 曲目数
            int trackCount = calculateTrackCount(release);
            metadata.setTrackCount(trackCount);
            
            results.add(metadata);
            
            log.debug("找到专辑: {} - {} ({}首)",
                metadata.getAlbumArtist(), metadata.getAlbum(), trackCount);
        }
        
        log.info("专辑搜索返回 {} 个结果", results.size());
        return results;
    }
    
    /**
     * 执行 HTTP 请求（带重试机制）
     */
    String executeRequest(String url) throws IOException, ParseException {
        int retryCount = 0;
        IOException lastException = null;
        
        while (retryCount <= MAX_RETRIES) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("请求已被中断", new InterruptedException("cancelled"));
            }
            java.util.function.BooleanSupplier gate = attemptBudgetGate.get();
            if (gate != null && !gate.getAsBoolean()) {
                throw new BudgetExhaustedException();
            }
            try {
                HttpGet httpGet = new HttpGet(url);
                httpGet.setHeader("User-Agent", config.getUserAgent());
                httpGet.setHeader("Accept", "application/json");
                
                try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                    int statusCode = response.getCode();
                    String responseBody = EntityUtils.toString(response.getEntity());
                    
                    if (statusCode == 200) {
                        log.debug("MusicBrainz API 响应: {}", responseBody);
                        return responseBody;
                    }

                    if (isRetryableStatus(statusCode)) {
                        log.warn("MusicBrainz API 限流或暂时不可用: {} - {}", statusCode, responseBody);
                        throw new RetryableHttpException(statusCode, retryAfterMillis(response));
                    }

                    // 4xx(如 404 查无此记录)重试也不会改变结果,直接失败,
                    // 免得为一个合法的"没查到"白等 3 次重试
                    log.error("MusicBrainz API 请求失败: {} - {}", statusCode, responseBody);
                    throw new NonRetryableHttpException(statusCode);
                }
                
            } catch (NonRetryableHttpException | BudgetExhaustedException e) {
                throw e;
            } catch (IOException e) {
                lastException = e;
                retryCount++;
                
                if (retryCount <= MAX_RETRIES) {
                    long delayMs = retryDelayMs(e, retryCount);
                    log.warn("网络请求失败(第{}/{}次尝试): {} - {}秒后重试",
                        retryCount, MAX_RETRIES, e.getMessage(), delayMs / 1000);
                    
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("重试等待被中断", ie);
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("重试等待被中断", new InterruptedException("cancelled"));
                    }
                } else {
                    log.error("网络请求失败,已达最大重试次数({}/{})", retryCount, MAX_RETRIES);
                }
            }
        }
        
        // 所有重试都失败,抛出最后一次异常
        throw lastException;
    }
    
    /**
     * 解析单个 Recording 响应
     * @param json JSON响应
     * @param musicFilesInFolder 文件所在文件夹的音乐文件数量
     * @param preferredReleaseGroupId 优先选择的 Release Group ID
     * @param preferredReleaseId 优先选择的 Release ID（确保版本一致性）
     * @param fileDurationSeconds 当前文件的时长（秒），用于时长匹配备选方案
     */
    private MusicMetadata parseRecordingResponse(String json, int musicFilesInFolder, boolean musicFilesCountReliable,
                                                 String preferredReleaseGroupId, String preferredReleaseId,
                                                 int fileDurationSeconds, boolean allowAlbumGuess) throws IOException, InterruptedException {
        JsonNode root = objectMapper.readTree(json);
         
        MusicMetadata metadata = new MusicMetadata();
        String recordingId = root.path("id").asText();
        metadata.setRecordingId(recordingId);
        metadata.setTitle(root.path("title").asText());
        
        // 解析艺术家
        JsonNode artistCredits = root.path("artist-credit");
        if (artistCredits.isArray() && artistCredits.size() > 0) {
            applyRecordingArtistCredit(metadata, artistCredits);
            metadata.setArtistId(joinArtistValues(artistCredits, "id"));
            metadata.setArtistSort(joinArtistValues(artistCredits, "sort-name"));
        }
        
        // 解析并选择最佳专辑
        JsonNode releases = root.path("releases");
        JsonNode bestRelease = null;
        if (releases.isArray() && releases.size() > 0) {
            bestRelease = selectBestRelease(releases, musicFilesInFolder, musicFilesCountReliable,
                preferredReleaseGroupId, preferredReleaseId, allowAlbumGuess);
            if (bestRelease == null) {
                log.warn("⚠ 无法可信地确定该曲目所属专辑（{} 个候选版本均不可信）", releases.size());
                log.warn("  仅返回曲目级元数据，由上层按「专辑未确定」处理，不会强行归入某张旧专辑");
            }
        }

        if (bestRelease != null) {
            metadata.setAlbum(bestRelease.path("title").asText());
            metadata.setReleaseDate(bestRelease.path("date").asText());
            applyReleaseGroupMetadata(metadata, bestRelease.path("release-group"));
            metadata.setReleaseId(bestRelease.path("id").asText());  // 设置 Release ID，用于版本一致性检查
            
            // 设置曲目数
            int trackCount = calculateTrackCount(bestRelease);
            metadata.setTrackCount(trackCount);
            
            // 解析碟号和曲目号
            String releaseId = bestRelease.path("id").asText();
            JsonNode fullRelease = getFullReleaseById(releaseId);

            // 获取专辑艺术家(Album Artist)——必须在 bundle.applyTo(metadata) 之前设置，
            // 否则 applyTo 看到 albumArtist==null 且随后的 setAlbumArtist 会清空 albumArtists。
            JsonNode releaseArtistCredits = bestRelease.path("artist-credit");
            if ((!releaseArtistCredits.isArray() || releaseArtistCredits.isEmpty()) && fullRelease != null) {
                releaseArtistCredits = fullRelease.path("artist-credit");
            }
            String resolvedAlbumArtist = albumArtistPolicy.resolveFromCredits(releaseArtistCredits);
            metadata.setAlbumArtist(resolvedAlbumArtist != null
                ? resolvedAlbumArtist : albumArtistPolicy.fallbackUnknown(metadata.getArtist()));

            if (fullRelease != null) {
                ReleaseTagBundle bundle = parseReleaseTagBundle(fullRelease);
                if (bundle != null) bundle.applyTo(metadata);
                findAndSetTrackPosition(fullRelease, recordingId, metadata, fileDurationSeconds);
            } else {
                if (resolvedAlbumArtist != null && !AlbumArtistPolicy.VARIOUS_ARTISTS.equals(resolvedAlbumArtist)
                        && releaseArtistCredits.isArray() && !releaseArtistCredits.isEmpty()) {
                    metadata.setAlbumArtists(artistNames(releaseArtistCredits));
                    metadata.setAlbumArtistCredit(encodeArtistCredit(releaseArtistCredits, resolvedAlbumArtist));
                }
                log.warn("Could not fetch full release details for release ID: {}. Disc and track number will be missing.", releaseId);
            }
            
            // 解析流派标签
            JsonNode tags = root.path("tags");
            if (tags.isArray() && tags.size() > 0) {
                List<String> genres = new ArrayList<>();
                for (JsonNode tag : tags) {
                    genres.add(tag.path("name").asText());
                }
                metadata.setGenres(genres);
            }
            
            // 解析作曲家和作词家信息
            parseCreatorCredits(root, metadata);
            
            return metadata;
        }

        // 没有可信的专辑信息：仍然返回曲目级元数据（流派 / 作曲 / 作词都是可靠的）
        JsonNode tagsWithoutRelease = root.path("tags");
        if (tagsWithoutRelease.isArray() && tagsWithoutRelease.size() > 0) {
            List<String> genres = new ArrayList<>();
            for (JsonNode tag : tagsWithoutRelease) {
                genres.add(tag.path("name").asText());
            }
            metadata.setGenres(genres);
        }
        parseCreatorCredits(root, metadata);

        return metadata;
    }
    
    /**
     * 在 Release 中查找特定 Recording 的位置（碟号和曲目号）
     * 支持时长匹配备选方案：当 Recording ID 匹配失败时，使用时长匹配
     *
     * @param release Release 信息
     * @param recordingId Recording ID
     * @param metadata 元数据对象（用于设置碟号、曲目号，以及在时长匹配成功时更新标题、艺术家等）
     * @param fileDurationSeconds 文件时长（秒），用于时长匹配备选方案
     */
    private void findAndSetTrackPosition(JsonNode release, String recordingId, MusicMetadata metadata, int fileDurationSeconds) {
            JsonNode media = release.path("media");
            if (!media.isArray()) {
                return;
            }

            // 第一阶段：尝试通过 Recording ID 精确匹配
            for (JsonNode medium : media) {
                JsonNode tracks = medium.path("tracks");
                if (tracks.isArray()) {
                    for (JsonNode track : tracks) {
                        String currentRecordingId = track.path("recording").path("id").asText("");
                        if (recordingId.equals(currentRecordingId)) {
                            String discNumber = medium.path("position").asText("");
                            String trackNumber = track.path("position").asText("");

                            metadata.setDiscNo(discNumber);
                            metadata.setTrackNo(trackNumber);
                            applyMatchedTrackMetadata(metadata, medium, track);

                            log.info("✓ 通过 Recording ID 找到曲目位置: 碟号 {}, 曲目号 {}", discNumber, trackNumber);
                            return; // 找到后即可退出
                        }
                    }
                }
            }

            log.warn("在专辑 {} 中未找到 Recording ID {} 的精确匹配", release.path("title").asText(), recordingId);

            // 第二阶段：如果 Recording ID 匹配失败，且提供了文件时长，尝试时长匹配。
            // 已知曲名时必须曲名一致：只凭时长会把一首不在此版本里的歌改名成另一首。
            String wantedTitle = normalizeTitleForMatch(metadata.getTitle());
            boolean requireTitle = !wantedTitle.isEmpty();
            if (fileDurationSeconds > 0) {
                log.info("尝试使用时长匹配备选方案（文件时长: {}秒{}）...", fileDurationSeconds,
                    requireTitle ? "，要求曲名一致" : "");

                final int DURATION_TOLERANCE_SECONDS = requireTitle
                    ? TITLE_MATCH_DURATION_TOLERANCE_SECONDS : 2;
                JsonNode bestMatchTrack = null;
                JsonNode bestMatchMedium = null;
                int bestDurationDiff = Integer.MAX_VALUE;

                // 遍历所有曲目，找到时长最接近的
                for (JsonNode medium : media) {
                    JsonNode tracks = medium.path("tracks");
                    if (tracks.isArray()) {
                        for (JsonNode track : tracks) {
                            int trackDurationMs = effectiveTrackLengthMs(track);
                            if (trackDurationMs > 0) {
                                int trackDurationSec = (trackDurationMs + 500) / 1000; // 四舍五入转换为秒
                                int durationDiff = Math.abs(trackDurationSec - fileDurationSeconds);

                                boolean titleOk = !requireTitle
                                    || wantedTitle.equals(normalizeTitleForMatch(track.path("title").asText("")))
                                    || wantedTitle.equals(normalizeTitleForMatch(
                                        track.path("recording").path("title").asText("")));
                                // 如果时长差异在容差范围内，且是目前最接近的
                                if (titleOk && durationDiff <= DURATION_TOLERANCE_SECONDS && durationDiff < bestDurationDiff) {
                                    bestMatchTrack = track;
                                    bestMatchMedium = medium;
                                    bestDurationDiff = durationDiff;
                                }
                            }
                        }
                    }
                }

                // 如果找到了时长匹配的曲目
                if (bestMatchTrack != null) {
                    String discNumber = bestMatchMedium.path("position").asText("");
                    String trackNumber = bestMatchTrack.path("position").asText("");

                    metadata.setDiscNo(discNumber);
                    metadata.setTrackNo(trackNumber);
                    applyMatchedTrackMetadata(metadata, bestMatchMedium, bestMatchTrack);

                    // 关键：使用匹配到的 track 的 recording 元数据（标题、艺术家等）
                    JsonNode matchedRecording = bestMatchTrack.path("recording");
                    String matchedRecordingId = matchedRecording.path("id").asText("");
                    String matchedTitle = matchedRecording.path("title").asText("");

                    // 更新 Recording ID 和标题
                    if (!matchedRecordingId.isEmpty()) {
                        metadata.setRecordingId(matchedRecordingId);
                        log.info("✓ 时长匹配成功，更新 Recording ID: {} -> {}", recordingId, matchedRecordingId);
                    }

                    if (!matchedTitle.isEmpty()) {
                        metadata.setTitle(matchedTitle);
                        log.info("✓ 时长匹配成功，更新标题: {}", matchedTitle);
                    }

                    int matchedDurationSec = (effectiveTrackLengthMs(bestMatchTrack) + 500) / 1000;
                    log.info("✓ 通过时长匹配找到曲目位置: 碟号 {}, 曲目号 {} (文件时长: {}秒, 匹配曲目时长: {}秒, 差异: {}秒)",
                        discNumber, trackNumber, fileDurationSeconds, matchedDurationSec, bestDurationDiff);
                    return;
                } else {
                    log.warn("时长匹配也未找到合适的曲目（容差范围: ±{}秒）", DURATION_TOLERANCE_SECONDS);
                }
            } else {
                log.info("未提供文件时长，跳过时长匹配备选方案");
            }
    }

    /** 从实际命中的 medium/track 提取位置相关字段，严禁使用整专曲目总数代替当前碟总数。 */
    private void applyMatchedTrackMetadata(MusicMetadata metadata, JsonNode medium, JsonNode track) {
        metadata.setReleaseTrackId(blankToNull(track.path("id").asText(null)));
        int trackTotal = countTracksInMedium(medium, true);
        metadata.setTrackTotal(trackTotal > 0 ? String.valueOf(trackTotal) : null);
        metadata.setMediaFormat(blankToNull(medium.path("format").asText(null)));
        JsonNode credits = track.path("recording").path("artist-credit");
        if (!credits.isArray() || credits.isEmpty()) credits = track.path("artist-credit");
        if (credits.isArray() && !credits.isEmpty()) {
            applyRecordingArtistCredit(metadata, credits);
            metadata.setArtistId(joinArtistValues(credits, "id"));
            metadata.setArtistSort(joinArtistValues(credits, "sort-name"));
        }
    }
    
    /** 录音级 / 作品级关系中视为「编曲」的类型（MusicBrainz relationship type 名称，小写比较）。 */
    private static final Set<String> ARRANGER_RELATION_TYPES =
        Set.of("arranger", "instrument arranger", "vocal arranger", "orchestrator");
    /**
     * MusicBrainz 的 {@code writer} 表示「词曲作者，分工不明」，因此同时计入作曲与作词，
     * 否则只录了 writer 的作品会丢失作曲。录音级与作品级使用同一套类型：
     * librettist 等关系实际挂在作品上，按层级区分反而会漏掉。
     */
    private static final Set<String> COMPOSER_RELATION_TYPES = Set.of("composer", "composing", "writer");
    private static final Set<String> LYRICIST_RELATION_TYPES = Set.of("lyricist", "librettist", "writer");

    /**
     * 解析作曲、作词、编曲信息。
     * 从 recording 级 relations 与 work 级 relations 中提取，使用 LinkedHashSet 按姓名精确去重，
     * 避免旧实现用 {@code String.contains} 把「Ann」误判为已存在于「Anna」中而漏掉创作人。
     */
    void parseCreatorCredits(JsonNode recording, MusicMetadata metadata) {
        try {
            JsonNode relations = recording.path("relations");
            if (!relations.isArray()) {
                return;
            }

            Map<String, String> composers = new LinkedHashMap<>();
            Map<String, String> lyricists = new LinkedHashMap<>();
            Map<String, String> arrangers = new LinkedHashMap<>();

            for (JsonNode relation : relations) {
                collectCredit(relation, COMPOSER_RELATION_TYPES, composers);
                collectCredit(relation, LYRICIST_RELATION_TYPES, lyricists);
                collectCredit(relation, ARRANGER_RELATION_TYPES, arrangers);
                cacheCreatorRelationArtist(relation);

                // 从 work 关系中提取
                JsonNode workRelations = relation.path("work").path("relations");
                if (workRelations.isArray()) {
                    for (JsonNode workRel : workRelations) {
                        collectCredit(workRel, COMPOSER_RELATION_TYPES, composers);
                        collectCredit(workRel, LYRICIST_RELATION_TYPES, lyricists);
                        collectCredit(workRel, ARRANGER_RELATION_TYPES, arrangers);
                        cacheCreatorRelationArtist(workRel);
                    }
                }
            }

            if (!composers.isEmpty()) {
                metadata.setComposer(String.join(", ", composers.keySet()));
                metadata.setComposers(List.copyOf(composers.keySet()));
                metadata.setComposerIds(alignedCreatorIds(composers));
                log.debug("找到作曲家: {}", metadata.getComposer());
            }
            if (!lyricists.isEmpty()) {
                metadata.setLyricist(String.join(", ", lyricists.keySet()));
                metadata.setLyricists(List.copyOf(lyricists.keySet()));
                metadata.setLyricistIds(alignedCreatorIds(lyricists));
                log.debug("找到作词家: {}", metadata.getLyricist());
            }
            if (!arrangers.isEmpty()) {
                metadata.setArranger(String.join(", ", arrangers.keySet()));
                metadata.setArrangers(List.copyOf(arrangers.keySet()));
                metadata.setArrangerIds(alignedCreatorIds(arrangers));
                log.debug("找到编曲: {}", metadata.getArranger());
            }
        } catch (Exception e) {
            log.warn("解析作曲/作词/编曲信息失败", e);
        }
    }

    private void cacheCreatorRelationArtist(JsonNode relation) {
        MusicBrainzArtistCache cache = this.artistCache;
        if (cache == null || relation == null || !relation.isObject()) {
            return;
        }
        String type = relation.path("type").asText("").trim().toLowerCase(Locale.ROOT);
        if (!COMPOSER_RELATION_TYPES.contains(type)
                && !LYRICIST_RELATION_TYPES.contains(type)
                && !ARRANGER_RELATION_TYPES.contains(type)) {
            return;
        }
        try {
            String targetCredit = blankToNull(relation.path("target-credit").asText(null));
            cacheArtistNode(cache, relation.path("artist"), targetCredit, "MB_CREATOR");
        } catch (Exception e) {
            log.debug("被动沉淀幕后人员缓存失败: {}", e.getMessage());
        }
    }

    private static final String CREATOR_ID_CONFLICT = "__CREATOR_ID_CONFLICT__";

    private static void collectCredit(JsonNode relation, Set<String> acceptedTypes, Map<String, String> target) {
        String type = relation.path("type").asText("").trim().toLowerCase(Locale.ROOT);
        if (!acceptedTypes.contains(type)) {
            return;
        }
        JsonNode artist = relation.path("artist");
        if (artist.isMissingNode() || artist.isNull()) {
            return;
        }
        String name = artist.path("name").asText("").trim();
        if (name.isEmpty()) {
            return;
        }
        String id = artist.path("id").asText("").trim();
        String existing = target.get(name);
        if (existing == null) {
            target.put(name, id);
        } else if (CREATOR_ID_CONFLICT.equals(existing)) {
            return;
        } else if (existing.isEmpty()) {
            if (!id.isEmpty()) {
                target.put(name, id);
            }
        } else if (!id.isEmpty() && !existing.equalsIgnoreCase(id)) {
            target.put(name, CREATOR_ID_CONFLICT);
        }
    }

    private static List<String> alignedCreatorIds(Map<String, String> credits) {
        if (credits.isEmpty()) {
            return null;
        }
        List<String> ids = new ArrayList<>(credits.size());
        Set<String> seen = new LinkedHashSet<>();
        for (String id : credits.values()) {
            if (id == null || id.isBlank() || CREATOR_ID_CONFLICT.equals(id)) {
                return null;
            }
            String normalized = id.trim().toLowerCase(Locale.ROOT);
            if (!seen.add(normalized)) {
                return null;
            }
            ids.add(normalized);
        }
        return List.copyOf(ids);
    }

    /**
     * 获取完整的 Release 信息。缓存按具体 Release ID 隔离，避免整专逐曲重复请求。
     */
    JsonNode getFullReleaseById(String releaseId) throws IOException, InterruptedException {
        return getFullReleaseById(releaseId, false);
    }

    JsonNode getFullReleaseById(String releaseId, boolean refresh) throws IOException, InterruptedException {
        if (releaseId == null || releaseId.isEmpty()) {
            return null;
        }
        JsonNode cached = fullReleaseCache.get(releaseId);
        if (!refresh && cached != null) {
            return cached;
        }
        String url = String.format("%s/release/%s?fmt=json&inc=recordings+artist-credits+labels+release-groups+media",
            config.getMusicBrainzApiUrl(), releaseId);

        try {
            rateLimit();
            JsonNode release = objectMapper.readTree(executeRequest(url));
            fullReleaseCache.put(releaseId, release);
            return release;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (IOException e) {
            log.error("Failed to fetch full release details for ID: {}", releaseId, e);
            throw e;
        } catch (ParseException e) {
            log.error("Failed to parse HTTP response for release ID: {}", releaseId, e);
            throw new IOException("Invalid MusicBrainz response for release " + releaseId, e);
        } catch (RuntimeException e) {
            log.error("Failed to parse full release details for ID: {}", releaseId, e);
            return null;
        }
    }

    /** 锁定 Release 的专辑级标签快照，是批处理写入的唯一权威来源。 */
    @Data
    public static class ReleaseTagBundle {
        private List<String> albumArtists;
        private String albumArtistCredit;
        private String originalAlbumArtistCredit;
        private String albumArtistId;
        private String albumArtistSort;
        private String releaseStatus;
        private String releaseCountry;
        private String script;
        private String barcode;
        private String catalogNumber;
        private String recordLabel;
        private String discTotal;
        private String releaseType;
        private boolean compilation;
        private String originalReleaseDate;
        private String originalYear;

        public void applyTo(MusicMetadata metadata) {
            // Respect the configured album-artist policy (e.g. Various Artists).
            String currentAlbumArtist = metadata.getAlbumArtist();
            boolean matchesStructuredCredit = albumArtists != null && !albumArtists.isEmpty()
                    && currentAlbumArtist != null
                    && !AlbumArtistPolicy.VARIOUS_ARTISTS.equalsIgnoreCase(currentAlbumArtist.trim())
                    && (currentAlbumArtist.equals(albumArtistCredit)
                        || currentAlbumArtist.equals(String.join(", ", albumArtists)));
            if (matchesStructuredCredit) {
                metadata.setAlbumArtist(albumArtistCredit);
                metadata.setAlbumArtists(List.copyOf(albumArtists));
                metadata.setAlbumArtistCredit(originalAlbumArtistCredit);
            } else {
                metadata.setAlbumArtists(null);
                metadata.setAlbumArtistCredit(null);
            }
            metadata.setAlbumArtistId(albumArtistId);
            metadata.setAlbumArtistSort(albumArtistSort);
            metadata.setReleaseStatus(releaseStatus);
            metadata.setReleaseCountry(releaseCountry);
            metadata.setScript(script);
            metadata.setBarcode(barcode);
            metadata.setCatalogNumber(catalogNumber);
            metadata.setRecordLabel(recordLabel);
            metadata.setDiscTotal(discTotal);
            if (releaseType != null) {
                metadata.setReleaseType(releaseType);
            }
            metadata.setCompilation(compilation);
            metadata.setClearReleaseType(true);
            metadata.setOriginalReleaseDate(originalReleaseDate);
            metadata.setOriginalYear(originalYear);
            metadata.setClearAlbumLevelTags(true);
        }
    }

    public ReleaseTagBundle fetchReleaseTagBundle(String releaseId) throws IOException, InterruptedException {
        JsonNode release = getFullReleaseById(releaseId);
        return release == null ? null : parseReleaseTagBundle(release);
    }

    ReleaseTagBundle parseReleaseTagBundle(JsonNode release) {
        ReleaseTagBundle bundle = new ReleaseTagBundle();
        JsonNode credits = release.path("artist-credit");
        cacheArtistCredits(credits, "MB_RELEASE");
        bundle.setAlbumArtists(artistNames(credits));
        bundle.setAlbumArtistCredit(albumArtistPolicy.resolveFromCredits(credits));
        bundle.setOriginalAlbumArtistCredit(encodeArtistCredit(credits, bundle.getAlbumArtistCredit()));
        bundle.setAlbumArtistId(joinArtistValues(credits, "id"));
        bundle.setAlbumArtistSort(joinArtistValues(credits, "sort-name"));
        bundle.setReleaseStatus(lowerOrNull(release.path("status").asText(null)));
        bundle.setReleaseCountry(blankToNull(release.path("country").asText(null)));
        bundle.setScript(blankToNull(release.path("text-representation").path("script").asText(null)));
        bundle.setBarcode(blankToNull(release.path("barcode").asText(null)));
        JsonNode media = release.path("media");
        bundle.setDiscTotal(media.isArray() && media.size() > 0 ? String.valueOf(media.size()) : null);
        JsonNode releaseGroup = release.path("release-group");
        bundle.setReleaseType(lowerOrNull(releaseGroup.path("primary-type").asText(null)));
        bundle.setCompilation(hasCompilationSecondaryType(releaseGroup));

        Set<String> labels = new LinkedHashSet<>();
        Set<String> catalogs = new LinkedHashSet<>();
        JsonNode labelInfo = release.path("label-info");
        if (labelInfo.isArray()) {
            for (JsonNode info : labelInfo) {
                addNonBlank(catalogs, info.path("catalog-number").asText(null));
                addNonBlank(labels, info.path("label").path("name").asText(null));
            }
        }
        bundle.setCatalogNumber(joinValues(catalogs));
        bundle.setRecordLabel(joinValues(labels));
        String originalDate = release.path("release-group").path("first-release-date").asText(null);
        bundle.setOriginalReleaseDate(blankToNull(originalDate));
        bundle.setOriginalYear(originalDate != null && originalDate.matches("^\\d{4}.*") ? originalDate.substring(0, 4) : null);
        return bundle;
    }

    private boolean isPreferNativeArtistName() {
        return config == null || config.isPreferNativeArtistName();
    }

    static String resolveCreditedArtistText(JsonNode credits) {
        return resolveCreditedArtistText(credits, true);
    }

    static String resolveCreditedArtistText(JsonNode credits, boolean preferNativeName) {
        return ArtistCreditNameResolver.resolveCreditedArtistText(credits, preferNativeName);
    }

    private void applyRecordingArtistCredit(MusicMetadata metadata, JsonNode credits) {
        cacheArtistCredits(credits, "MB_RECORDING");
        String display = resolveCreditedArtistText(credits, isPreferNativeArtistName()).trim();
        if (!display.isEmpty()) {
            metadata.setArtist(display);
            metadata.setArtists(artistNames(credits));
            metadata.setArtistCredit(encodeArtistCredit(credits, display));
        }
    }

    static String encodeArtistCredit(JsonNode credits, String display) {
        List<String> names = artistNames(credits);
        if (display == null || AlbumArtistPolicy.VARIOUS_ARTISTS.equalsIgnoreCase(display)
                || names.size() < 2 || names.size() != credits.size()) {
            return null;
        }
        var data = JsonNodeFactory.instance.objectNode();
        data.put("display", display);
        var nameValues = data.putArray("names");
        names.forEach(nameValues::add);
        var before = data.putArray("before");
        before.add("");
        for (int i = 0; i < credits.size() - 1; i++) {
            before.add(credits.get(i).path("joinphrase").asText(", "));
        }
        return data.toString();
    }

    static List<String> artistNames(JsonNode credits) {
        List<String> names = new ArrayList<>();
        if (credits != null && credits.isArray()) {
            for (JsonNode credit : credits) {
                String name = credit.path("artist").path("name").asText("").trim();
                if (!name.isEmpty()) names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private static String joinArtistValues(JsonNode credits, String key) {
        Set<String> values = new LinkedHashSet<>();
        if (credits.isArray()) {
            for (JsonNode credit : credits) {
                addNonBlank(values, credit.path("artist").path(key).asText(null));
            }
        }
        return joinValues(values);
    }

    private static void addNonBlank(Set<String> values, String value) {
        String normalized = blankToNull(value);
        if (normalized != null) values.add(normalized);
    }

    private static String joinValues(Set<String> values) {
        return values.isEmpty() ? null : String.join("; ", values);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String lowerOrNull(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }
    
    /**
     * 选择最佳专辑版本
     * 优先级逻辑：
     * 1. 优先匹配具体的 Release ID（确保版本一致性）
     * 2. 其次匹配 Release Group ID
     * 3. 最后按原有逻辑选择
     *
     * @param releases 所有发行版本
     * @param musicFilesInFolder 文件所在文件夹的音乐文件数量
     * @param preferredReleaseGroupId 优先选择的 Release Group ID
     * @param preferredReleaseId 优先选择的 Release ID（确保版本一致性）
     * @param allowAlbumGuess 当上面两级都没命中时，是否允许退化为「按曲目数猜一个」。
     *                        传 false 时直接返回 null，表示「这首曲目所属的专辑无法确定」。
     */
    private JsonNode selectBestRelease(JsonNode releases, int musicFilesInFolder, boolean musicFilesCountReliable,
                                       String preferredReleaseGroupId, String preferredReleaseId,
                                       boolean allowAlbumGuess) {
        // --- Stage 0: 优先匹配具体的 Release ID（最高优先级）---
        if (preferredReleaseId != null && !preferredReleaseId.isEmpty()) {
            for (JsonNode release : releases) {
                String currentReleaseId = release.path("id").asText("");
                if (preferredReleaseId.equals(currentReleaseId)) {
                    log.info("找到并选择与锁定的 Release ID {} 匹配的专辑: {}", preferredReleaseId, release.path("title").asText());
                    return release; // 找到精确匹配的 Release，直接返回
                }
            }
            log.warn("Recording's releases 中未找到匹配的 Release ID: {}，继续尝试匹配 Release Group ID", preferredReleaseId);
        }

        boolean trackCountUsable = musicFilesCountReliable
            && musicFilesInFolder >= MIN_FILES_FOR_TRACK_COUNT_GATE;

        // --- Stage 1: 匹配 Release Group ID（排除纯视频后按曲目数接近度 > 地区优先级 > 媒体格式 > 更早日期择优）---
        if (preferredReleaseGroupId != null && !preferredReleaseGroupId.isEmpty()) {
            List<JsonNode> candidates = new ArrayList<>();
            boolean foundAnyInGroup = false;
            for (JsonNode release : releases) {
                String currentReleaseGroupId = release.path("release-group").path("id").asText("");
                if (preferredReleaseGroupId.equals(currentReleaseGroupId)) {
                    foundAnyInGroup = true;
                    if (scoreReleaseForDuration(release) >= 0) {
                        candidates.add(release);
                    }
                }
            }
            if (!candidates.isEmpty()) {
                List<String> countryPriority = releaseCountryPriority();
                JsonNode chosen = selectBestCandidateInGroup(candidates, musicFilesInFolder, trackCountUsable, countryPriority);
                log.info("找到并选择与锁定的 Release Group ID {} 匹配的专辑: {} (地区: {})",
                    preferredReleaseGroupId, chosen.path("title").asText(), chosen.path("country").asText("Unknown"));
                return chosen;
            }
            if (foundAnyInGroup) {
                log.warn("锁定的 Release Group ID {} 仅包含纯视频发行版，已排除", preferredReleaseGroupId);
            } else {
                log.warn("Recording's releases did not contain the preferred Release Group ID: {}. Falling back to best match logic.", preferredReleaseGroupId);
            }
        }
        // --- End of new logic ---

        // --- Stage 2: 没有任何锁定信息 ---
        // 关键修复：以前这里会「挑一个曲目数最接近的 release」并且用 releases.get(0) 兜底，
        // 导致 MusicBrainz 里根本没有收录的专辑（例如新发行的精选集）被错误归入曲目所属的旧专辑。
        // 现在只有在明确允许猜测的场景（如监控目录根部的散落单文件）才继续往下走。
        if (!allowAlbumGuess) {
            log.warn("未命中锁定的 Release / Release Group，且当前场景禁止「按曲目数猜测专辑」");
            log.warn("  判定为：该曲目所属专辑无法确定（可能是 MusicBrainz 尚未收录的专辑）");
            return null;
        }

        JsonNode bestRelease = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        if (trackCountUsable) {
            log.info("开始选择最佳专辑版本（文件夹内{}个文件），曲目数作为加权评分与可信度门槛", musicFilesInFolder);
        } else {
            log.info("开始选择最佳专辑版本（文件夹内{}个文件，曲目数不可用于约束），仅按发行类型/媒体格式评分",
                musicFilesInFolder);
        }

        for (JsonNode release : releases) {
            if (scoreReleaseForDuration(release) < 0) {
                continue;
            }
            int trackCount = calculateTrackCount(release);
            if (trackCountUsable && !isTrackCountPlausible(trackCount, musicFilesInFolder)) {
                int trackCountDiff = trackCount > 0 ? Math.abs(trackCount - musicFilesInFolder) : -1;
                log.debug("跳过曲目数不可信的候选: {} (候选{}首, 文件夹{}首, 差异{})",
                    release.path("title").asText(), trackCount, musicFilesInFolder, trackCountDiff);
                continue;
            }

            double currentScore = calculateReleaseScore(release, musicFilesInFolder, trackCountUsable);
            if (currentScore > bestScore + SCORE_EPSILON) {
                bestRelease = release;
                bestScore = currentScore;
                log.debug("选择综合评分更高的专辑: {} (评分: {}, {}首 vs 文件夹{}首)",
                    release.path("title").asText(), String.format("%.2f", currentScore),
                    trackCount, musicFilesInFolder);
            } else if (bestRelease != null && Math.abs(currentScore - bestScore) <= SCORE_EPSILON) {
                // 综合评分基本相同，优先选择发行时间早的版本，保证选择稳定。
                String date1 = bestRelease.path("date").asText("");
                String date2 = release.path("date").asText("");
                if (!date2.isEmpty() && (date1.isEmpty() || date2.compareTo(date1) < 0)) {
                    bestRelease = release;
                    bestScore = Math.max(bestScore, currentScore);
                }
            }
        }

        // 曲目数门槛把所有候选都筛掉时，不能直接放弃：这是「允许猜测」的场景，
        // 退化为不看曲目数重新挑一次，仍然优于返回 null 让上层完全失去专辑信息。
        if (bestRelease == null && trackCountUsable) {
            log.warn("没有候选通过曲目数可信度门槛，降级为仅按发行类型/媒体格式重新选择");
            for (JsonNode release : releases) {
                if (scoreReleaseForDuration(release) < 0) {
                    continue;
                }
                double currentScore = calculateReleaseScore(release, musicFilesInFolder, false);
                if (currentScore > bestScore + SCORE_EPSILON) {
                    bestRelease = release;
                    bestScore = currentScore;
                }
            }
        }

        if (bestRelease != null) {
            // --- 地区优先级选择：仅在具备非空 Release Group ID 时，在同组同曲目数非视频版本中按统一规则择优 ---
            List<String> countryPriority = releaseCountryPriority();
            String bestReleaseGroupId = bestRelease.path("release-group").path("id").asText("").trim();
            if (!countryPriority.isEmpty() && !bestReleaseGroupId.isEmpty()) {
                int bestTrackCount = calculateTrackCount(bestRelease);

                // 收集同一 Release Group、曲目数相同且非视频的所有版本
                List<JsonNode> sameGroupReleases = new ArrayList<>();
                for (JsonNode release : releases) {
                    if (scoreReleaseForDuration(release) < 0) {
                        continue;
                    }
                    String groupId = release.path("release-group").path("id").asText("").trim();
                    int trackCount = calculateTrackCount(release);
                    if (bestReleaseGroupId.equals(groupId) && trackCount == bestTrackCount) {
                        sameGroupReleases.add(release);
                    }
                }

                // 只有存在命中优先地区的版本时才替换原选择，并在同地区内按媒体格式与更早发行日期决胜
                if (sameGroupReleases.size() > 1) {
                    JsonNode preferred = null;
                    for (JsonNode release : sameGroupReleases) {
                        String country = release.path("country").asText("");
                        if (MusicBrainzReleasePriority.countryRank(country, countryPriority) == Integer.MAX_VALUE) {
                            continue;
                        }
                        if (preferred == null
                                || MusicBrainzReleasePriority.compareReleaseJson(release, preferred, countryPriority) < 0) {
                            preferred = release;
                        }
                    }
                    if (preferred != null) {
                        log.info("在同一专辑的{}个版本中，按地区优先级选择{}版本",
                            sameGroupReleases.size(), preferred.path("country").asText(""));
                        bestRelease = preferred;
                    } else {
                        log.debug("同一专辑的{}个版本中未找到优先地区{}的版本，保持原选择",
                            sameGroupReleases.size(), countryPriority);
                    }
                }
            }
            // --- End of country priority selection ---

            int finalTrackCount = calculateTrackCount(bestRelease);
            String releaseType = bestRelease.path("release-group").path("primary-type").asText("Unknown");
            String country = bestRelease.path("country").asText("Unknown");
            log.info("最终选择: {} - {} ({}首曲目，类型: {}，地区: {})",
                bestRelease.path("title").asText(),
                bestRelease.path("artist-credit").path(0).path("artist").path("name").asText("Unknown"),
                finalTrackCount,
                releaseType,
                country);
        }
        
        if (bestRelease == null) {
            log.warn("允许猜测专辑，但候选列表为空或全部不可用");
        }
        return bestRelease;
    }

    private boolean isTrackCountPlausible(int trackCount, int musicFilesInFolder) {
        if (trackCount <= 0 || musicFilesInFolder <= 0) {
            return false;
        }
        return Math.abs(trackCount - musicFilesInFolder) <= allowedTrackCountDiff(musicFilesInFolder);
    }

    private int allowedTrackCountDiff(int musicFilesInFolder) {
        return Math.max(2, (int) Math.ceil(musicFilesInFolder * TRACK_COUNT_TOLERANCE_RATIO));
    }

    /**
     * 计算 release 的音频曲目数，口径与 {@code getReleaseDurationSequence()} 保持一致：
     * 排除 DVD/Blu-ray/VHS 等视频 medium，以及 {@code recording.video=true} 的曲目。
     *
     * <p><b>重要</b>：recording lookup（{@code /recording/{id}?inc=releases+media}）返回的
     * {@code media} 数组**只包含含有该 recording 的那张碟**，而顶层 {@code track-count}
     * 才是整个 release 的总曲目数。若此时按 media 求和，一张 2CD/24 轨的专辑会被算成
     * 单碟的 12 轨，进而被曲目数门槛整体否掉。因此这里通过
     * 「顶层声明数 &gt; media 原始求和」来识别 media 被裁剪的情况并回落到顶层数值。</p>
     */
    private int calculateTrackCount(JsonNode release) {
        JsonNode media = release.path("media");
        int declaredTotal = release.path("track-count").asInt(0);

        if (!media.isArray() || media.isEmpty()) {
            return declaredTotal;
        }

        int rawMediaTotal = 0;  // 含视频，仅用于判断 media 是否被裁剪
        int audioTotal = 0;     // 已排除视频 medium / video track
        for (JsonNode medium : media) {
            rawMediaTotal += countTracksInMedium(medium, false);
            if (isVideoFormat(medium.path("format").asText("").toLowerCase())) {
                continue;
            }
            audioTotal += countTracksInMedium(medium, true);
        }

        if (declaredTotal > rawMediaTotal) {
            log.debug("release {} 的 media 被裁剪（media 求和 {} < 顶层 track-count {}），改用顶层曲目数",
                release.path("id").asText(""), rawMediaTotal, declaredTotal);
            return declaredTotal;
        }
        return audioTotal;
    }

    /**
     * @param excludeVideoTracks 为 true 时跳过 {@code recording.video=true} 的曲目
     *                           （仅在 medium 展开了 tracks 数组时才可能生效）
     */
    private int countTracksInMedium(JsonNode medium, boolean excludeVideoTracks) {
        JsonNode tracks = medium.path("tracks");
        if (tracks.isArray() && !tracks.isEmpty()) {
            int count = 0;
            for (JsonNode track : tracks) {
                if (excludeVideoTracks && track.path("recording").path("video").asBoolean(false)) {
                    continue;
                }
                count++;
            }
            return count;
        }
        return medium.path("track-count").asInt(0);
    }

    /**
     * 计算统一量纲的发行评分（0-100 左右）。曲目数、类型、媒体格式分别占 60/30/10，
     * 避免曲目数既做硬主键又重复以魔数档位计分。
     */
    private double calculateReleaseScore(JsonNode release, int musicFilesInFolder, boolean trackCountUsable) {
        // 曲目数不可用时该维度整体缺席，而不是给 0 分——否则所有候选被同等压低，
        // 排序完全由 type/media 决定的同时还平白丢掉了区分度。
        double trackScore = 0.0;
        if (trackCountUsable) {
            int trackCount = calculateTrackCount(release);
            int trackDiff = Math.abs(trackCount - musicFilesInFolder);
            int allowedDiff = allowedTrackCountDiff(musicFilesInFolder);
            trackScore = 60.0 * Math.max(0.0, 1.0 - (double) trackDiff / (allowedDiff + 1));
        }

        JsonNode releaseGroup = release.path("release-group");
        String type = releaseGroup.path("primary-type").asText("").toLowerCase();
        double typeScore;
        if (musicFilesInFolder <= 2) {
            typeScore = "single".equals(type) ? 30 : ("ep".equals(type) ? 20 : 12);
        } else if (musicFilesInFolder <= 6) {
            typeScore = "ep".equals(type) ? 30 : ("album".equals(type) ? 24 : 12);
        } else {
            typeScore = "album".equals(type) ? 30 : ("compilation".equals(type) ? 27 : 12);
        }

        JsonNode secondaryTypes = releaseGroup.path("secondary-types");
        if (secondaryTypes.isArray()) {
            for (JsonNode secondaryType : secondaryTypes) {
                String secondary = secondaryType.asText("").toLowerCase();
                if (secondary.equals("live") || secondary.equals("remix") || secondary.equals("demo")) {
                    typeScore -= 5;
                }
            }
        }

        double mediaScore = 0;
        JsonNode media = release.path("media");
        if (media.isArray()) {
            for (JsonNode medium : media) {
                String format = medium.path("format").asText("").toLowerCase();
                if (isVideoFormat(format)) continue;
                if (format.contains("cd") || format.contains("digital")) {
                    mediaScore = Math.max(mediaScore, 10);
                } else if (!format.isEmpty()) {
                    mediaScore = Math.max(mediaScore, 5);
                }
            }
        }

        return trackScore + Math.max(0, typeScore) + mediaScore;
    }

    /**
     * 解析搜索结果响应
     */
    private List<MusicMetadata> parseSearchResponse(String json) throws IOException {
        List<MusicMetadata> results = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);
        JsonNode recordings = root.path("recordings");
        
        if (!recordings.isArray()) {
            return results;
        }
        
        for (JsonNode recording : recordings) {
            MusicMetadata metadata = new MusicMetadata();
            metadata.setRecordingId(recording.path("id").asText());
            metadata.setTitle(recording.path("title").asText());
            metadata.setScore(recording.path("score").asInt(0));
            
            // 解析艺术家
            JsonNode artistCredits = recording.path("artist-credit");
            if (artistCredits.isArray() && artistCredits.size() > 0) {
                applyRecordingArtistCredit(metadata, artistCredits);
                metadata.setArtistId(joinArtistValues(artistCredits, "id"));
            }
            
            // 解析专辑
            JsonNode releases = recording.path("releases");
            if (releases.isArray() && releases.size() > 0) {
                JsonNode firstRelease = releases.get(0);
                metadata.setAlbum(firstRelease.path("title").asText());
                metadata.setReleaseId(firstRelease.path("id").asText(""));
                applyReleaseGroupMetadata(metadata, firstRelease.path("release-group"));
            }
            
            results.add(metadata);
        }
        
        return results;
    }
    
    /**
     * 判断状态码是否值得重试
     * 429 = 限流, 503 = MusicBrainz/CAA 过载时的标准响应, 5xx = 服务端临时故障
     */
    static boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || (statusCode >= 500 && statusCode < 600);
    }

    /**
     * 解析 Retry-After 响应头(仅支持秒数形式,MusicBrainz 与 CAA 用的就是这种)
     * @return 毫秒数,无法解析时返回 -1
     */
    static long retryAfterMillis(HttpResponse response) {
        Header header = response.getFirstHeader("Retry-After");
        if (header == null || header.getValue() == null) {
            return -1;
        }
        try {
            long seconds = Long.parseLong(header.getValue().trim());
            return seconds > 0 ? seconds * 1000 : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 计算本次重试的等待时间
     * 服务端明确给了 Retry-After 就听它的,否则指数退避(10s/20s/40s),
     * 避免被限流时还以固定频率继续打
     */
    static long retryDelayMs(Exception e, int attempt) {
        if (e instanceof RetryableHttpException) {
            long retryAfter = ((RetryableHttpException) e).getRetryAfterMillis();
            if (retryAfter > 0) {
                return Math.min(retryAfter, MAX_RETRY_DELAY_MS);
            }
        }
        long delay = RETRY_DELAY_MS << Math.min(attempt - 1, 8);
        return Math.min(delay, MAX_RETRY_DELAY_MS);
    }

    /**
     * 服务端返回了可重试的状态码(限流或临时故障)
     */
    static class RetryableHttpException extends IOException {
        private final long retryAfterMillis;

        RetryableHttpException(int statusCode, long retryAfterMillis) {
            super("HTTP " + statusCode
                + (retryAfterMillis > 0 ? " (Retry-After: " + retryAfterMillis / 1000 + "s)" : ""));
            this.retryAfterMillis = retryAfterMillis;
        }

        long getRetryAfterMillis() {
            return retryAfterMillis;
        }
    }

    /**
     * 服务端返回了重试也不会改变的错误(如 404 查无此记录)
     */
    private static class NonRetryableHttpException extends IOException {
        NonRetryableHttpException(int statusCode) {
            super("API 请求失败: " + statusCode);
        }
    }

    /**
     * 速率限制
     */
    synchronized void rateLimit() throws InterruptedException {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastRequest = currentTime - lastRequestTime;
        
        if (timeSinceLastRequest < REQUEST_INTERVAL) {
            long sleepTime = REQUEST_INTERVAL - timeSinceLastRequest;
            log.debug("等待 {} ms 以符合 API 速率限制", sleepTime);
            Thread.sleep(sleepTime);
        }
        
        lastRequestTime = System.currentTimeMillis();
    }
    
    /**
     * 从锁定的专辑中按时长查找匹配的曲目（强制使用锁定专辑）
     * 当 AcoustID 返回的 Recording 不属于锁定的专辑时使用此方法
     *
     * @param releaseId 锁定的 Release ID
     * @param releaseGroupId 锁定的 Release Group ID
     * @param fileDurationSeconds 文件时长（秒）
     * @param lockedAlbumTitle 锁定的专辑标题
     * @param lockedAlbumArtist 锁定的专辑艺术家
     * @return 匹配到的元数据，如果未找到返回 null
     */
    public MusicMetadata getTrackFromLockedAlbumByDuration(
            String releaseId,
            String releaseGroupId,
            int fileDurationSeconds,
            String lockedAlbumTitle,
            String lockedAlbumArtist) throws IOException, InterruptedException {
        
        TrackMatch match = matchTrackInRelease(releaseId, releaseGroupId, null, null,
            fileDurationSeconds, lockedAlbumTitle, lockedAlbumArtist);
        return match.getMetadata();
    }

    /** 曲目在指定 Release 中的匹配方式 */
    public enum TrackMatchType {
        /** Recording ID 精确命中 */
        RECORDING_ID,
        /** 不同 recording，但曲名一致且时长相近（如重制版 / 不同发行的同一首歌） */
        TITLE_AND_DURATION,
        /** 指纹没有给出录音，只能按时长匹配（旧行为，仅在没有任何曲目身份信息时允许） */
        DURATION_ONLY,
        /** 该曲目不在此 Release 中 */
        NOT_FOUND,
        /** Release 数据取不到（网络 / 解析失败），无法判断 */
        UNAVAILABLE
    }

    public static final class TrackMatch {
        private final TrackMatchType type;
        private final MusicMetadata metadata;

        TrackMatch(TrackMatchType type, MusicMetadata metadata) {
            this.type = type;
            this.metadata = metadata;
        }

        public TrackMatchType getType() { return type; }
        public MusicMetadata getMetadata() { return metadata; }
        public boolean isFound() { return metadata != null; }
        public boolean isNotInRelease() { return type == TrackMatchType.NOT_FOUND; }
    }

    /** 曲名一致判定所允许的时长差（秒） */
    static final int TITLE_MATCH_DURATION_TOLERANCE_SECONDS = 5;
    /** 纯时长匹配的容差（秒），仅在曲目身份未知时使用 */
    static final int DURATION_ONLY_TOLERANCE_SECONDS = 3;

    /**
     * 在指定 Release 中查找曲目。
     *
     * <p>匹配顺序：Recording ID → 曲名 + 时长 → （仅当 recordingId 未知时）纯时长。
     * 指纹已经给出了录音身份时，<b>绝不</b>只凭时长把它指派成 Release 里另一首歌——
     * 旧实现正是这样把《金魚草》改名成《アトック》，并覆盖掉真正的《アトック》。</p>
     *
     * @param recordingId    指纹识别出的录音 ID（未知时为 null）
     * @param recordingTitle 指纹识别出的曲名（用于曲名匹配）
     */
    private static final ThreadLocal<java.util.Set<String>> ACTIVE_CLAIMED_POSITIONS = new ThreadLocal<>();

    public TrackMatch matchTrackInRelease(String releaseId, String releaseGroupId,
                                          String recordingId, String recordingTitle,
                                          int fileDurationSeconds,
                                          String lockedAlbumTitle, String lockedAlbumArtist) {
        if (releaseId == null || releaseId.isEmpty()) {
            log.warn("未提供锁定的 Release ID，无法在锁定版本中查找曲目");
            return new TrackMatch(TrackMatchType.UNAVAILABLE, null);
        }
        JsonNode release;
        try {
            release = getFullReleaseById(releaseId);
        } catch (Exception e) {
            log.warn("获取锁定 Release {} 失败，无法判断曲目是否在其中: {}", releaseId, e.getMessage());
            return new TrackMatch(TrackMatchType.UNAVAILABLE, null);
        }
        if (release == null || !release.path("media").isArray() || release.path("media").size() == 0) {
            log.warn("锁定 Release {} 没有媒体信息", releaseId);
            return new TrackMatch(TrackMatchType.UNAVAILABLE, null);
        }
        return matchTrackInReleaseNode(release, releaseId, releaseGroupId, recordingId, recordingTitle,
            fileDurationSeconds, lockedAlbumTitle, lockedAlbumArtist, ACTIVE_CLAIMED_POSITIONS.get());
    }

    public TrackMatch matchTrackInRelease(String releaseId, String releaseGroupId,
                                          String recordingId, String recordingTitle,
                                          int fileDurationSeconds,
                                          String lockedAlbumTitle, String lockedAlbumArtist,
                                          java.util.Set<String> claimedPositions) {
        java.util.Set<String> previous = ACTIVE_CLAIMED_POSITIONS.get();
        ACTIVE_CLAIMED_POSITIONS.set(claimedPositions);
        try {
            return matchTrackInRelease(releaseId, releaseGroupId, recordingId, recordingTitle,
                fileDurationSeconds, lockedAlbumTitle, lockedAlbumArtist);
        } finally {
            if (previous == null) {
                ACTIVE_CLAIMED_POSITIONS.remove();
            } else {
                ACTIVE_CLAIMED_POSITIONS.set(previous);
            }
        }
    }

    /** 与 {@link #matchTrackInRelease} 相同，但直接使用已取得的 Release JSON（便于单元测试） */
    TrackMatch matchTrackInReleaseNode(JsonNode release, String releaseId, String releaseGroupId,
                                       String recordingId, String recordingTitle,
                                       int fileDurationSeconds,
                                       String lockedAlbumTitle, String lockedAlbumArtist) {
        return matchTrackInReleaseNode(release, releaseId, releaseGroupId, recordingId, recordingTitle,
            fileDurationSeconds, lockedAlbumTitle, lockedAlbumArtist, ACTIVE_CLAIMED_POSITIONS.get());
    }

    TrackMatch matchTrackInReleaseNode(JsonNode release, String releaseId, String releaseGroupId,
                                       String recordingId, String recordingTitle,
                                       int fileDurationSeconds,
                                       String lockedAlbumTitle, String lockedAlbumArtist,
                                       java.util.Set<String> claimedPositions) {
        boolean identityKnown = recordingId != null && !recordingId.isBlank();
        String wantedTitle = normalizeTitleForMatch(recordingTitle);

        JsonNode exactTrack = null, exactMedium = null;
        JsonNode titleTrack = null, titleMedium = null;
        int titleDiff = Integer.MAX_VALUE;
        JsonNode durationTrack = null, durationMedium = null;
        int durationDiffBest = Integer.MAX_VALUE;

        for (JsonNode medium : release.path("media")) {
            if (isVideoFormat(medium.path("format").asText("").toLowerCase())) continue;
            JsonNode tracks = medium.path("tracks");
            if (!tracks.isArray()) continue;
            String discKey = String.valueOf(medium.path("position").asInt(1));
            for (JsonNode track : tracks) {
                JsonNode recording = track.path("recording");
                if (recording.path("video").asBoolean(false)) continue;

                if (identityKnown && recordingId.equals(recording.path("id").asText(""))) {
                    exactTrack = track;
                    exactMedium = medium;
                    break;
                }

                String trackPos = track.path("position").asText("");
                String slotKey = discKey + ":" + trackPos;
                boolean slotClaimed = claimedPositions != null && !trackPos.isEmpty() && claimedPositions.contains(slotKey);

                int trackDurationMs = effectiveTrackLengthMs(track);
                int diff = trackDurationMs > 0 && fileDurationSeconds > 0
                    ? Math.abs((trackDurationMs + 500) / 1000 - fileDurationSeconds) : Integer.MAX_VALUE;

                if (!slotClaimed && !wantedTitle.isEmpty() && diff <= TITLE_MATCH_DURATION_TOLERANCE_SECONDS && diff < titleDiff
                    && (wantedTitle.equals(normalizeTitleForMatch(track.path("title").asText("")))
                        || wantedTitle.equals(normalizeTitleForMatch(recording.path("title").asText(""))))) {
                    titleTrack = track;
                    titleMedium = medium;
                    titleDiff = diff;
                }
                if (!slotClaimed && diff <= DURATION_ONLY_TOLERANCE_SECONDS && diff < durationDiffBest) {
                    durationTrack = track;
                    durationMedium = medium;
                    durationDiffBest = diff;
                }
            }
            if (exactTrack != null) break;
        }

        TrackMatchType type;
        JsonNode chosenTrack;
        JsonNode chosenMedium;
        if (exactTrack != null) {
            type = TrackMatchType.RECORDING_ID; chosenTrack = exactTrack; chosenMedium = exactMedium;
        } else if (titleTrack != null) {
            type = TrackMatchType.TITLE_AND_DURATION; chosenTrack = titleTrack; chosenMedium = titleMedium;
        } else if (!identityKnown && durationTrack != null) {
            type = TrackMatchType.DURATION_ONLY; chosenTrack = durationTrack; chosenMedium = durationMedium;
        } else {
            log.warn("曲目「{}」(recording: {}, {}秒) 不在锁定版本「{}」(Release ID: {}) 中",
                recordingTitle, recordingId, fileDurationSeconds, lockedAlbumTitle, releaseId);
            return new TrackMatch(TrackMatchType.NOT_FOUND, null);
        }

        try {
            MusicMetadata metadata = buildTrackMetadataFromRelease(release, chosenMedium, chosenTrack,
                releaseId, releaseGroupId, lockedAlbumTitle, lockedAlbumArtist);
            log.info("✓ 在锁定版本中找到曲目（{}）: {} - {} (碟号 {}, 曲目号 {})", type,
                metadata.getArtist(), metadata.getTitle(), metadata.getDiscNo(), metadata.getTrackNo());
            return new TrackMatch(type, metadata);
        } catch (Exception e) {
            log.warn("解析锁定版本曲目信息失败: {}", e.getMessage());
            return new TrackMatch(TrackMatchType.UNAVAILABLE, null);
        }
    }

    /** 曲名比较用的规范化：NFKC、忽略大小写、只保留字母和数字 */
    static String normalizeTitleForMatch(String title) {
        if (title == null) return "";
        String n = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(n.length());
        n.codePoints().filter(Character::isLetterOrDigit).forEach(sb::appendCodePoint);
        return sb.toString();
    }

    private MusicMetadata buildTrackMetadataFromRelease(JsonNode release, JsonNode medium, JsonNode track,
                                                        String releaseId, String releaseGroupId,
                                                        String lockedAlbumTitle, String lockedAlbumArtist)
            throws IOException, InterruptedException {
        MusicMetadata metadata = new MusicMetadata();
        metadata.setAlbum(lockedAlbumTitle);
        metadata.setAlbumArtist(lockedAlbumArtist);
        metadata.setReleaseGroupId(releaseGroupId);
        metadata.setReleaseId(releaseId);
        applyReleaseGroupMetadata(metadata, release.path("release-group"));

        metadata.setDiscNo(medium.path("position").asText(""));
        metadata.setTrackNo(track.path("position").asText(""));
        applyMatchedTrackMetadata(metadata, medium, track);

        JsonNode matchedRecording = track.path("recording");
        metadata.setRecordingId(matchedRecording.path("id").asText(""));
        metadata.setTitle(matchedRecording.path("title").asText(""));

        metadata.setReleaseDate(release.path("date").asText(""));
        if (releaseId != null && !releaseId.isEmpty()) {
            ReleaseTagBundle bundle = fetchReleaseTagBundle(releaseId);
            if (bundle != null) bundle.applyTo(metadata);
        }
        return metadata;
    }
    
    /**
     * 从锁定的专辑中按时长查找匹配的曲目（通过 Release Group ID）
     * 当只有 Release Group ID 而没有具体 Release ID 时使用此方法
     * 会先获取 Release Group 的最佳 Release，然后按时长匹配
     *
     * @param releaseGroupId 锁定的 Release Group ID
     * @param fileDurationSeconds 文件时长（秒）
     * @param musicFilesInFolder 文件夹内音乐文件数量，用于选择曲目数最接近的 Release
     * @param lockedAlbumTitle 锁定的专辑标题
     * @param lockedAlbumArtist 锁定的专辑艺术家
     * @return 匹配到的元数据，如果未找到返回 null
     */
    public MusicMetadata getTrackFromLockedAlbumByReleaseGroup(
            String releaseGroupId,
            int fileDurationSeconds,
            int musicFilesInFolder,
            String lockedAlbumTitle,
            String lockedAlbumArtist) throws IOException, InterruptedException {
        return getTrackFromLockedAlbumByReleaseGroup(releaseGroupId, fileDurationSeconds,
            musicFilesInFolder, true, lockedAlbumTitle, lockedAlbumArtist);
    }

    public MusicMetadata getTrackFromLockedAlbumByReleaseGroup(
            String releaseGroupId,
            int fileDurationSeconds,
            int musicFilesInFolder,
            boolean musicFilesCountReliable,
            String lockedAlbumTitle,
            String lockedAlbumArtist) throws IOException, InterruptedException {
        return matchTrackInReleaseGroup(releaseGroupId, null, null, fileDurationSeconds,
            musicFilesInFolder, musicFilesCountReliable, lockedAlbumTitle, lockedAlbumArtist).getMetadata();
    }

    /**
     * 只锁定了 Release Group 时：先按曲目数 / 格式选出具体版本，再用
     * {@link #matchTrackInRelease} 的分级规则查找曲目。
     */
    public TrackMatch matchTrackInReleaseGroup(
            String releaseGroupId,
            String recordingId,
            String recordingTitle,
            int fileDurationSeconds,
            int musicFilesInFolder,
            boolean musicFilesCountReliable,
            String lockedAlbumTitle,
            String lockedAlbumArtist) throws IOException, InterruptedException {

        if (releaseGroupId == null || releaseGroupId.isEmpty()) {
            log.warn("未提供 Release Group ID，无法执行强制专辑匹配");
            return new TrackMatch(TrackMatchType.UNAVAILABLE, null);
        }
        
        log.info("=== 强制使用锁定专辑模式（通过 Release Group ID）===");
        log.info("锁定专辑: {} (Release Group ID: {})", lockedAlbumTitle, releaseGroupId);
        log.info("文件时长: {}秒，文件夹内 {} 个音乐文件", fileDurationSeconds, musicFilesInFolder);

        // 专辑（Release Group）已由其他证据锁定，这里只是选具体版本。
        // 曲目数不可靠时应降级为「只看媒体格式评分」，而不是直接放弃整首曲目的元数据。
        boolean trackCountUsable = musicFilesCountReliable
            && musicFilesInFolder >= MIN_FILES_FOR_TRACK_COUNT_GATE;
        if (!trackCountUsable) {
            log.info("文件夹曲目数不可用于选版，降级为仅按媒体格式（CD/Digital 优先）选择 Release");
        }

        // 1. 获取 Release Group 的所有 Releases
        rateLimit();
        String rgUrl = String.format("%s/release-group/%s?fmt=json&inc=releases+media",
            config.getMusicBrainzApiUrl(), releaseGroupId);
        
        try {
            String rgResponse = executeRequest(rgUrl);
            JsonNode rgRoot = objectMapper.readTree(rgResponse);
            
            JsonNode releases = rgRoot.path("releases");
            if (!releases.isArray() || releases.size() == 0) {
                log.warn("Release Group {} 没有找到任何 releases", releaseGroupId);
                return new TrackMatch(TrackMatchType.UNAVAILABLE, null);
            }
            
            log.info("Release Group {} 共有 {} 个 releases（{}）",
                releaseGroupId, releases.size(),
                trackCountUsable ? "选择曲目数最接近 " + musicFilesInFolder + " 的版本" : "仅按媒体格式选版");

            // 2. 排除纯视频格式后按（曲目数接近度 > 地区优先级 > 媒体格式 > 更早日期）排序候选，
            // 并在首选版本未找到目标曲目时顺延探查后续候选（最多 3 个），避免首选地区版本缺该录音时丢失精确命中。
            List<JsonNode> audioReleases = new ArrayList<>();
            for (JsonNode release : releases) {
                if (scoreReleaseForDuration(release) >= 0) {
                    audioReleases.add(release);
                }
            }
            if (audioReleases.isEmpty()) {
                log.warn("未找到合适的 Release");
                return new TrackMatch(TrackMatchType.UNAVAILABLE, null);
            }
            List<String> countryPriority = releaseCountryPriority();
            audioReleases.sort((a, b) -> compareCandidatesInGroup(a, b, musicFilesInFolder, trackCountUsable, countryPriority));

            int probeLimit = Math.min(3, audioReleases.size());
            TrackMatch fallbackUnmatched = null;
            for (int i = 0; i < probeLimit; i++) {
                JsonNode candidate = audioReleases.get(i);
                String candidateReleaseId = candidate.path("id").asText();
                String candidateTitle = candidate.path("title").asText();
                int candidateTrackCount = calculateTrackCount(candidate);

                log.info("探查候选 Release ({}/{}): {} (ID: {}, 地区: {}, {} 首曲目)",
                    i + 1, probeLimit, candidateTitle, candidateReleaseId,
                    candidate.path("country").asText("Unknown"), candidateTrackCount);

                TrackMatch match = matchTrackInRelease(candidateReleaseId, releaseGroupId, recordingId, recordingTitle,
                    fileDurationSeconds, lockedAlbumTitle, lockedAlbumArtist);
                if (match.isFound()) {
                    MusicMetadata metadata = match.getMetadata();
                    applyReleaseGroupMetadata(metadata, rgRoot);
                    metadata.setReleaseGroupId(releaseGroupId);
                    metadata.setReleaseId(candidateReleaseId);
                    return match;
                }
                if (fallbackUnmatched == null
                        || (fallbackUnmatched.getType() == TrackMatchType.UNAVAILABLE
                            && match.getType() == TrackMatchType.NOT_FOUND)) {
                    fallbackUnmatched = match;
                }
            }
            return fallbackUnmatched != null ? fallbackUnmatched : new TrackMatch(TrackMatchType.NOT_FOUND, null);
            
        } catch (ParseException e) {
            log.error("解析 Release Group 响应失败", e);
            return new TrackMatch(TrackMatchType.UNAVAILABLE, null);
        }
    }
    
    private int compareCandidatesInGroup(JsonNode a, JsonNode b, int musicFilesInFolder,
                                         boolean trackCountUsable, List<String> countryPriority) {
        if (trackCountUsable) {
            int diffA = Math.abs(calculateTrackCount(a) - musicFilesInFolder);
            int diffB = Math.abs(calculateTrackCount(b) - musicFilesInFolder);
            int diffCmp = Integer.compare(diffA, diffB);
            if (diffCmp != 0) {
                return diffCmp;
            }
        }
        return MusicBrainzReleasePriority.compareReleaseJson(a, b, countryPriority);
    }

    private JsonNode selectBestCandidateInGroup(List<JsonNode> candidates, int musicFilesInFolder,
                                                boolean trackCountUsable, List<String> countryPriority) {
        JsonNode bestRelease = null;
        for (JsonNode release : candidates) {
            if (bestRelease == null
                    || compareCandidatesInGroup(release, bestRelease, musicFilesInFolder, trackCountUsable, countryPriority) < 0) {
                bestRelease = release;
            }
        }
        return bestRelease;
    }

    /**
     * 关闭客户端
     */
    public void close() throws IOException {
        httpClient.close();
    }
    
}
