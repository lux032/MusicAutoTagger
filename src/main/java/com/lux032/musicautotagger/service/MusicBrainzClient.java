package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;

/**
 * MusicBrainz API 客户端
 * 用于查询音乐元数据信息
 */
@Slf4j
public class MusicBrainzClient {
    
    private final MusicConfig config;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    /** Release Group ID -> 动画版封面 URL（空串 = 确认没有动画版），避免同一专辑逐曲重复请求 */
    private final java.util.Map<String, String> animeCoverUrlMemo = new java.util.concurrent.ConcurrentHashMap<>();
    private long lastRequestTime = 0;
    private static final long REQUEST_INTERVAL = 1000; // MusicBrainz 要求至少1秒间隔
    private static final int MAX_RETRIES = 3; // 最大重试次数
    private static final long RETRY_DELAY_MS = 10000; // 首次重试间隔10秒,之后指数退避
    private static final long MAX_RETRY_DELAY_MS = 60000; // 单次重试等待上限60秒

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
        this.config = config;
        this.httpClient = createHttpClient(config);
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 创建 HttpClient,支持代理配置
     */
    private CloseableHttpClient createHttpClient(MusicConfig config) {
        HttpClientBuilder builder = HttpClients.custom();
        
        // 设置超时
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofSeconds(30))
            .setResponseTimeout(Timeout.ofSeconds(30))
            .build();
        builder.setDefaultRequestConfig(requestConfig);

        // 关闭 HttpClient 自带的自动重试。它默认会对 429/503 各自动重试一次,
        // 叠加本类的重试后,被限流时实际请求数会翻倍(实测 4 次尝试打出 8 个请求)。
        // 重试统一交给本类处理,以便配合 Retry-After 和指数退避。
        builder.disableAutomaticRetries();

        // 配置代理
        if (config.isProxyEnabled() && config.getProxyHost() != null && !config.getProxyHost().isEmpty()) {
            HttpHost proxy = new HttpHost(config.getProxyHost(), config.getProxyPort());
            builder.setProxy(proxy);
            log.info(I18nUtil.getMessage("proxy.musicbrainz.enabled", config.getProxyHost(), config.getProxyPort()));
            
            // 注意: 代理认证功能已简化,如需认证代理,请使用系统代理设置
            // 或在代理软件中配置允许本地连接无需认证
        } else if (config.isProxyEnabled()) {
            log.warn(I18nUtil.getMessage("proxy.enabled.no.host"));
        }
        
        return builder.build();
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
    public AlbumDurationResult getAlbumDurationSequence(String releaseGroupId) throws IOException, InterruptedException {
        rateLimit();
        
        // 查询 release-group 获取所有 releases
        String url = String.format("%s/release-group/%s?fmt=json&inc=releases+media",
            config.getMusicBrainzApiUrl(), releaseGroupId);
        
        try {
            String response = executeRequest(url);
            JsonNode root = objectMapper.readTree(response);
            
            // 获取所有 releases
            JsonNode releases = root.path("releases");
            if (!releases.isArray() || releases.size() == 0) {
                log.warn("Release Group {} 没有找到任何 releases", releaseGroupId);
                return new AlbumDurationResult(new ArrayList<>(), null);
            }
            
            log.info("Release Group {} 共有 {} 个releases，开始查找有时长数据的版本",
                releaseGroupId, releases.size());
            
            // 按优先级排序所有 releases
            List<JsonNode> sortedReleases = new ArrayList<>();
            for (JsonNode release : releases) {
                sortedReleases.add(release);
            }
            sortedReleases.sort((r1, r2) -> {
                int score1 = scoreReleaseForDuration(r1);
                int score2 = scoreReleaseForDuration(r2);
                return Integer.compare(score2, score1); // 降序
            });
            
            // 尝试前3个最佳 release（或更少如果总数不足3个）
            int tryCount = Math.min(3, sortedReleases.size());
            for (int i = 0; i < tryCount; i++) {
                JsonNode release = sortedReleases.get(i);
                String releaseId = release.path("id").asText();
                String releaseTitle = release.path("title").asText();
                
                log.debug("尝试第 {}/{} 个release: {} (ID: {})",
                    i + 1, tryCount, releaseTitle, releaseId);
                
                List<Integer> durations = getReleaseDurationSequence(releaseId);
                
                // 如果获取到有效的时长序列（至少有一些曲目），返回结果
                if (!durations.isEmpty()) {
                    log.info("成功从 release {} 获取到 {} 首曲目的时长序列",
                        releaseTitle, durations.size());
                    return new AlbumDurationResult(durations, releaseId);
                } else {
                    log.debug("Release {} 没有时长数据，继续尝试下一个", releaseTitle);
                }
            }

            // 所有尝试都失败
            log.warn("Release Group {} 的前{}个release都没有时长数据",
                releaseGroupId, tryCount);
            return new AlbumDurationResult(new ArrayList<>(), null);
            
        } catch (ParseException e) {
            log.error("解析 Release Group 响应失败", e);
            return new AlbumDurationResult(new ArrayList<>(), null);
        }
    }
    
    /**
     * 获取 Release Group 下所有 Release 的时长序列（用于精确匹配）
     * 改进版本：返回所有有效的 Release 时长序列，而不是只返回第一个
     *
     * @param releaseGroupId Release Group ID
     * @return 包含所有 Release 时长序列的列表
     */
    public List<AlbumDurationResult> getAllReleaseDurationSequences(String releaseGroupId) throws IOException, InterruptedException {
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
            // Release Group 层的艺术家，作为各 Release 缺失 artist-credit 时的兜底
            String groupArtist = resolveAlbumArtistFromCredits(root.path("artist-credit"));
            
            // 获取所有 releases
            JsonNode releases = root.path("releases");
            if (!releases.isArray() || releases.size() == 0) {
                log.warn("Release Group {} 没有找到任何 releases", releaseGroupId);
                return results;
            }
            
            log.info("Release Group {} 共有 {} 个 releases，获取所有有效版本的时长序列",
                releaseGroupId, releases.size());
            
            // 按优先级排序所有 releases（优先选择 CD 或 Digital 格式）
            List<JsonNode> sortedReleases = new ArrayList<>();
            for (JsonNode release : releases) {
                sortedReleases.add(release);
            }
            sortedReleases.sort((r1, r2) -> {
                int score1 = scoreReleaseForDuration(r1);
                int score2 = scoreReleaseForDuration(r2);
                return Integer.compare(score2, score1); // 降序
            });
            
            // 获取所有有时长数据的 release（最多尝试10个）
            int tryCount = Math.min(10, sortedReleases.size());
        int successCount = 0;
        
        for (int i = 0; i < tryCount && successCount < 5; i++) {
            JsonNode release = sortedReleases.get(i);
            String releaseId = release.path("id").asText();
            String releaseTitle = release.path("title").asText();
            int trackCount = calculateTrackCount(release);
            
            // 跳过视频格式
            int score = scoreReleaseForDuration(release);
            if (score < 0) {
                continue;
            }
            
            // 获取媒体格式
            String mediaFormat = extractMediaFormat(release);
            
            // 该 Release 的专辑艺术家（多人/未知才是 Various Artists）
            String releaseArtist = resolveAlbumArtistFromCredits(release.path("artist-credit"));
            if (releaseArtist == null) {
                releaseArtist = groupArtist;
            }
            
            log.debug("尝试获取 release {} 的时长序列 (ID: {}, 曲目数: {}, 格式: {})",
                releaseTitle, releaseId, trackCount, mediaFormat);
            
            List<Integer> durations = getReleaseDurationSequence(releaseId);
            
            // 如果获取到有效的时长序列
            if (!durations.isEmpty()) {
                results.add(new AlbumDurationResult(durations, releaseId, releaseTitle, trackCount, mediaFormat,
                    releaseType, compilation, releaseArtist));
                successCount++;
                log.info("✓ 成功获取 release {} 的时长序列 ({} 首曲目, 格式: {})",
                    releaseTitle, durations.size(), mediaFormat);
            }
        }
            
            log.info("共获取到 {} 个 release 的时长序列", results.size());
            return results;
            
        } catch (ParseException e) {
            log.error("解析 Release Group 响应失败", e);
            return results;
        }
    }
    
    /**
     * 从 artist-credit 节点解析专辑艺术家。
     * 多人或 Unknown Artist 返回 "Various Artists"；无法解析时返回 null（交由调用方兜底）。
     */
    private String resolveAlbumArtistFromCredits(JsonNode artistCredits) {
        if (artistCredits == null || !artistCredits.isArray() || artistCredits.size() == 0) {
            return null;
        }
        if (artistCredits.size() > 1) {
            return "Various Artists";
        }
        String artistName = artistCredits.get(0).path("artist").path("name").asText("");
        if (artistName.isEmpty()) {
            return null;
        }
        if (artistName.contains(", ") || artistName.contains("\u3001")
            || "Unknown Artist".equalsIgnoreCase(artistName)) {
            return "Various Artists";
        }
        return artistName;
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
            this.albumArtist = albumArtist;
            this.durations = durations;
            this.releaseId = releaseId;
            this.releaseTitle = releaseTitle;
            this.trackCount = trackCount;
            this.mediaFormat = mediaFormat;
            this.releaseType = releaseType;
            this.compilation = compilation;
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
            
            List<Integer> durations = new ArrayList<>();
            
            // 遍历所有 media (碟片)
            JsonNode media = root.path("media");
            log.debug("Media 节点存在: {}, isArray: {}, size: {}",
                !media.isMissingNode(), media.isArray(), media.size());
            
            if (media.isArray()) {
                for (int i = 0; i < media.size(); i++) {
                    JsonNode medium = media.get(i);
                    log.debug("Medium[{}] 内容: {}", i, medium.toString());

                    // 检查媒体格式，跳过视频格式
                    String format = medium.path("format").asText("").toLowerCase();
                    if (isVideoFormat(format)) {
                        log.debug("跳过视频格式媒体: {} (format: {})", i, format);
                        continue;
                    }

                    JsonNode tracks = medium.path("tracks");
                    log.debug("Tracks 节点存在: {}, isArray: {}, size: {}",
                        !tracks.isMissingNode(), tracks.isArray(), tracks.size());

                    if (tracks.isArray()) {
                        for (JsonNode track : tracks) {
                            // 检查 track 的 recording 是否为视频
                            JsonNode recording = track.path("recording");
                            boolean isVideo = recording.path("video").asBoolean(false);
                            if (isVideo) {
                                log.debug("跳过视频 track: {}", track.path("title").asText(""));
                                continue;
                            }

                            // 获取时长(毫秒),转换为秒
                            int durationMs = track.path("length").asInt(0);
                            log.debug("Track length: {} ms", durationMs);
                            if (durationMs > 0) {
                                int durationSec = (durationMs + 500) / 1000; // 四舍五入
                                durations.add(durationSec);
                            }
                        }
                    }
                }
            }
            
            log.info("获取专辑时长序列成功 - Release: {}, 曲目数: {}", releaseId, durations.size());
            
            return durations;
            
        } catch (ParseException e) {
            log.error("解析 Release 响应失败", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 为 Release 打分，用于选择最适合获取时长序列的版本
     * 优先选择 CD 或 Digital 格式
     */
    private int scoreReleaseForDuration(JsonNode release) {
        int score = 0;

        JsonNode media = release.path("media");
        if (media.isArray() && media.size() > 0) {
            String format = media.get(0).path("format").asText("").toLowerCase();

            // 如果是视频格式，给予负分以排除
            if (isVideoFormat(format)) {
                return -100;
            }

            if (format.contains("cd")) {
                score = 100;
            } else if (format.contains("digital")) {
                score = 90;
            } else if (!format.isEmpty()) {
                score = 50;
            }
        }

        return score;
    }

    /**
     * 判断媒体格式是否为视频格式
     * @param format 媒体格式字符串（小写）
     * @return 如果是视频格式返回true
     */
    private boolean isVideoFormat(String format) {
        if (format == null || format.isEmpty()) {
            return false;
        }
        // 常见的视频格式
        return format.contains("dvd") ||
               format.contains("blu-ray") ||
               format.contains("bluray") ||
               format.contains("hd dvd") ||
               format.contains("hd-dvd") ||
               format.contains("vhs") ||
               format.contains("laserdisc") ||
               format.contains("vcd") ||
               format.contains("svcd") ||
               format.contains("umd") ||
               format.contains("video");
    }
    
    /**
     * 从 Release 节点提取媒体格式
     * @param release Release JSON 节点
     * @return 媒体格式字符串（如 "CD", "Digital Media"），如果无法确定返回 null
     */
    private String extractMediaFormat(JsonNode release) {
        JsonNode media = release.path("media");
        if (media.isArray() && media.size() > 0) {
            // 获取第一个 media 的格式
            String format = media.get(0).path("format").asText("");
            if (!format.isEmpty()) {
                return format;
            }
        }
        return null;
    }

    /**
     * 获取封面图片 URL(带重试机制) - 公共方法
     */
    public String getCoverArtUrlByReleaseGroupId(String releaseGroupId) {
        return getCoverArtUrl(releaseGroupId);
    }

    /**
     * 获取封面 URL，并告知调用方「动画版偏好是否完整生效」。
     * 限流/网络失败导致回退到默认封面时 degraded=true，
     * 调用方应该避免把这张封面写进持久化的专辑缓存，否则一次限流会把真人封面永久钉在动画命名空间里。
     */
    public CoverArtResolution resolveCoverArtByReleaseGroupId(String releaseGroupId) {
        if (releaseGroupId == null || releaseGroupId.isEmpty()) {
            return new CoverArtResolution(null, false);
        }
        if (!config.isPreferAnimeCover()) {
            return new CoverArtResolution(fetchFrontCoverUrl("release-group/" + releaseGroupId, releaseGroupId), false);
        }

        AnimeCoverLookup lookup = resolvePreferredEdition(releaseGroupId);
        if (lookup.coverUrl != null) {
            return new CoverArtResolution(lookup.coverUrl, false);
        }
        log.debug("未找到动画版封面，回退到 Release Group 默认封面: {}", releaseGroupId);
        return new CoverArtResolution(
            fetchFrontCoverUrl("release-group/" + releaseGroupId, releaseGroupId),
            !lookup.completed);
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

    /**
     * 获取封面图片 URL(带重试机制) - 内部方法
     *
     * 若开启 cover.preferAnimeEdition，会先在该 Release Group 的所有发行版中
     * 找出「动画盘 / アニメ盤 / 期間生産限定盤」等动画封面版本，优先使用其封面；
     * 找不到时再回退到 Release Group 默认封面（通常是歌手真人封面）。
     */
    private String getCoverArtUrl(String releaseGroupId) {
        if (releaseGroupId == null || releaseGroupId.isEmpty()) {
            return null;
        }

        return resolveCoverArtByReleaseGroupId(releaseGroupId).getCoverArtUrl();
    }

    /**
     * 动画版封面解析（带进程内记忆）
     * 同一张专辑的每首曲都会走到这里，没有记忆的话会把 MB/CAA 请求数放大数倍。
     * 为避免把一次限流失败永久地记成「没有动画版」，只在枚举流程正常完成时才记录负结果。
     * 记忆 key 包含关键词签名，以便运行中从 Web 面板改关键词后立即生效。
     */
    private AnimeCoverLookup resolvePreferredEdition(String releaseGroupId) {
        String memoKey = keywordSignature() + "|" + releaseGroupId;
        String memo = animeCoverUrlMemo.get(memoKey);
        if (memo != null) {
            return new AnimeCoverLookup(memo.isEmpty() ? null : memo, true);
        }

        AnimeCoverLookup lookup = findPreferredEditionCoverUrl(releaseGroupId);
        if (lookup.completed) {
            animeCoverUrlMemo.put(memoKey, lookup.coverUrl == null ? "" : lookup.coverUrl);
        }
        return lookup;
    }

    /**
     * 当前关键词配置的签名，用于记忆 key（关键词变了旧结果就不再命中）
     */
    private String keywordSignature() {
        List<String> keywords = config.getAnimeCoverKeywords();
        return keywords == null ? "0" : Integer.toHexString(keywords.hashCode());
    }

    /**
     * 在 Release Group 的发行版列表中挑选「动画封面版本」，并返回其正面封面 URL
     */
    private AnimeCoverLookup findPreferredEditionCoverUrl(String releaseGroupId) {
        List<String> keywords = config.getAnimeCoverKeywords();
        if (keywords == null || keywords.isEmpty()) {
            return new AnimeCoverLookup(null, true);
        }

        try {
            rateLimit();
            // 用 browse 端点而不是 release-group?inc=releases：
            // 后者子查询默认只返回 25 条，大专辑会截断；前者还能拿到 status/country
            String url = String.format("%s/release?release-group=%s&limit=100&fmt=json",
                config.getMusicBrainzApiUrl(), releaseGroupId);
            JsonNode root = objectMapper.readTree(executeRequest(url));
            JsonNode releases = root.path("releases");
            if (!releases.isArray() || releases.size() == 0) {
                return new AnimeCoverLookup(null, true);
            }
            // browse 单页最多 100 条。超过一页时本次枚举并不完整，
            // 不能把「没找到」当作确定结论记下来
            int totalCount = root.path("release-count").asInt(releases.size());
            boolean fullyEnumerated = totalCount <= releases.size();
            if (!fullyEnumerated) {
                log.debug("Release Group {} 共 {} 个发行版，本次只枚举了前 {} 个",
                    releaseGroupId, totalCount, releases.size());
            }

            List<AnimeCoverCandidate> candidates = new ArrayList<>();
            for (JsonNode release : releases) {
                String releaseId = release.path("id").asText("");
                if (releaseId.isEmpty()) {
                    continue;
                }
                // 非正式发行（bootleg/promotion）的扫图质量不可控，不作为动画版候选
                String status = release.path("status").asText("");
                if (!status.isEmpty() && !"Official".equalsIgnoreCase(status)) {
                    continue;
                }
                // browse 结果自带 cover-art-archive.front，先用它筛掉没有正面封面的发行版，
                // 避免为没封面的候选白白打一轮 CAA 请求
                JsonNode caa = release.path("cover-art-archive");
                if (caa.isObject() && caa.has("front") && !caa.path("front").asBoolean(true)) {
                    continue;
                }
                String title = release.path("title").asText("");
                String disambiguation = release.path("disambiguation").asText("");
                AnimeEditionScore score = scoreAnimeEdition(title + " " + disambiguation, keywords);
                if (score.matched()) {
                    candidates.add(new AnimeCoverCandidate(releaseId, title, disambiguation,
                        score, release.path("date").asText("")));
                }
            }

            if (candidates.isEmpty()) {
                log.debug("Release Group {} 下没有命中动画版关键词的发行版", releaseGroupId);
                return new AnimeCoverLookup(null, fullyEnumerated);
            }

            // 排序：先看命中的最强关键词（避免多个弱词累加压过一个强词），
            // 再看命中数量，最后发行日期早的优先（初版动画盘通常最早）
            candidates.sort((a, b) -> {
                if (a.score.bestWeight != b.score.bestWeight) {
                    return Integer.compare(b.score.bestWeight, a.score.bestWeight);
                }
                if (a.score.matchCount != b.score.matchCount) {
                    return Integer.compare(b.score.matchCount, a.score.matchCount);
                }
                String dateA = a.date == null || a.date.isEmpty() ? "9999" : a.date;
                String dateB = b.date == null || b.date.isEmpty() ? "9999" : b.date;
                return dateA.compareTo(dateB);
            });

            int maxCandidates = Math.max(1, config.getAnimeCoverMaxCandidates());
            int tried = 0;
            boolean allCandidatesDefinitive = true;
            for (AnimeCoverCandidate candidate : candidates) {
                if (tried >= maxCandidates) {
                    break;
                }
                tried++;
                log.debug("尝试动画版封面候选: {}{} 命中关键词={} (Release ID: {})",
                    candidate.title, candidate.describeDisambiguation(),
                    candidate.score.matchedKeywords, candidate.releaseId);
                CoverFetchResult result = fetchFrontCover("release/" + candidate.releaseId, candidate.releaseId);
                if (result.url != null) {
                    log.info("✓ 使用动画版封面: {}{} (命中关键词: {})", candidate.title,
                        candidate.describeDisambiguation(), candidate.score.matchedKeywords);
                    return new AnimeCoverLookup(result.url, true);
                }
                if (!result.definitive) {
                    // 限流/超时导致的失败，不能当作「这个候选没封面」
                    allCandidatesDefinitive = false;
                }
            }
            // 候选都没有封面：只有全部试完、且每一次都是确定性结果时，才能当作「确定没有」记下来
            return new AnimeCoverLookup(null,
                fullyEnumerated && allCandidatesDefinitive && tried >= candidates.size());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("查找动画版封面被中断: {}", releaseGroupId);
            return new AnimeCoverLookup(null, false);
        } catch (Exception e) {
            log.warn("查找动画版封面失败，将回退到默认封面: {} - {}", releaseGroupId, e.getMessage());
            return new AnimeCoverLookup(null, false);
        }
    }

    /**
     * 根据关键词给发行版打分，关键词列表越靠前权重越高
     */
    private AnimeEditionScore scoreAnimeEdition(String text, List<String> keywords) {
        AnimeEditionScore result = new AnimeEditionScore();
        if (text == null || text.isBlank()) {
            return result;
        }
        String normalized = text.toLowerCase(java.util.Locale.ROOT);
        int size = keywords.size();
        for (int i = 0; i < size; i++) {
            String keyword = keywords.get(i);
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            if (normalized.contains(keyword.trim().toLowerCase(java.util.Locale.ROOT))) {
                result.add(keyword.trim(), size - i);
            }
        }
        return result;
    }

    /**
     * 动画版关键词命中情况
     */
    private static class AnimeEditionScore {
        int bestWeight = 0;
        int matchCount = 0;
        final List<String> matchedKeywords = new ArrayList<>();

        void add(String keyword, int weight) {
            matchCount++;
            matchedKeywords.add(keyword);
            if (weight > bestWeight) {
                bestWeight = weight;
            }
        }

        boolean matched() {
            return matchCount > 0;
        }
    }

    /**
     * 动画版封面查找结果
     * @param completed 枚举流程是否正常跑完（决定能否把负结果记忆下来）
     */
    private static class AnimeCoverLookup {
        final String coverUrl;
        final boolean completed;

        AnimeCoverLookup(String coverUrl, boolean completed) {
            this.coverUrl = coverUrl;
            this.completed = completed;
        }
    }

    /**
     * 动画封面候选发行版
     */
    private static class AnimeCoverCandidate {
        final String releaseId;
        final String title;
        final String disambiguation;
        final AnimeEditionScore score;
        final String date;

        AnimeCoverCandidate(String releaseId, String title, String disambiguation,
                            AnimeEditionScore score, String date) {
            this.releaseId = releaseId;
            this.title = title == null ? "" : title;
            this.disambiguation = disambiguation == null ? "" : disambiguation;
            this.score = score;
            this.date = date;
        }

        String describeDisambiguation() {
            return disambiguation.isEmpty() ? "" : " (" + disambiguation + ")";
        }
    }

    /**
     * 从 Cover Art Archive 获取正面封面 URL(带重试机制)
     * @param caaPath CAA 资源路径，如 "release-group/{id}" 或 "release/{id}"
     * @param logId 日志中显示的实体 ID
     */
    private String fetchFrontCoverUrl(String caaPath, String logId) {
        return fetchFrontCover(caaPath, logId).url;
    }

    /**
     * 从 Cover Art Archive 获取正面封面（区分「确实没有封面」与「本次请求失败」）
     * 两者必须分开: 把限流失败当成「没有封面」会让错误结果被永久缓存
     */
    private CoverFetchResult fetchFrontCover(String caaPath, String logId) {
        int retryCount = 0;
        
        while (retryCount <= MAX_RETRIES) {
            try {
                rateLimit();
                String url = String.format("%s/%s", config.getCoverArtApiUrl(), caaPath);
                
                HttpGet httpGet = new HttpGet(url);
                httpGet.setHeader("User-Agent", config.getUserAgent());
                httpGet.setHeader("Accept", "application/json");
                
                try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                    int statusCode = response.getCode();

                    if (statusCode == 200) {
                        String json = EntityUtils.toString(response.getEntity());
                        JsonNode root = objectMapper.readTree(json);
                        JsonNode images = root.path("images");
                        
                        if (images.isArray()) {
                            for (JsonNode image : images) {
                                if (image.path("front").asBoolean()) {
                                    return CoverFetchResult.found(image.path("image").asText());
                                }
                            }
                        }
                        // 查询成功,该专辑确实没有正面封面
                        return CoverFetchResult.notFound();
                    }

                    if (statusCode == 404) {
                        // Cover Art Archive 没有这张专辑的记录,重试也不会有
                        log.debug("Cover Art Archive 无此专辑封面: {}", logId);
                        return CoverFetchResult.notFound();
                    }

                    if (isRetryableStatus(statusCode)) {
                        // 429/503 是限流响应,批量导入时非常常见。
                        // 这里必须重试: 若当成"没有封面"返回,文件会被正常归档并写入已处理日志,
                        // 之后重跑也不会再补封面
                        EntityUtils.consumeQuietly(response.getEntity());
                        throw new RetryableHttpException(statusCode, retryAfterMillis(response));
                    }

                    log.warn("获取封面失败,状态码 {}: {}", statusCode, logId);
                    EntityUtils.consumeQuietly(response.getEntity());
                    return CoverFetchResult.failed();
                }

            } catch (InterruptedException ie) {
                // 中断不能被当成「没有封面」，也不该继续重试
                Thread.currentThread().interrupt();
                log.warn("获取封面被中断: {}", logId);
                return CoverFetchResult.failed();
            } catch (Exception e) {
                retryCount++;
                
                if (retryCount > MAX_RETRIES) {
                    log.error("获取封面失败,已达最大重试次数({}/{}): {} - {}",
                        MAX_RETRIES, MAX_RETRIES, logId, e.getMessage());
                    break;
                }

                long delayMs = retryDelayMs(e, retryCount);
                log.warn("获取封面失败(第{}/{}次尝试): {} - {}秒后重试",
                    retryCount, MAX_RETRIES, e.getMessage(), delayMs / 1000);

                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("重试等待被中断");
                    return CoverFetchResult.failed();
                }
            }
        }
        return CoverFetchResult.failed();
    }

    /**
     * CAA 封面查询结果
     * @param definitive true = 服务端给出了确定答案（有封面 / 确实没有）；false = 本次请求失败
     */
    private static class CoverFetchResult {
        final String url;
        final boolean definitive;

        private CoverFetchResult(String url, boolean definitive) {
            this.url = url;
            this.definitive = definitive;
        }

        static CoverFetchResult found(String url) {
            return new CoverFetchResult(url, true);
        }

        static CoverFetchResult notFound() {
            return new CoverFetchResult(null, true);
        }

        static CoverFetchResult failed() {
            return new CoverFetchResult(null, false);
        }
    }

    /**
     * 下载封面图片(带重试机制)
     */
    public byte[] downloadCoverArt(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }

        int retryCount = 0;
        
        while (retryCount <= MAX_RETRIES) {
            try {
                HttpGet httpGet = new HttpGet(url);
                httpGet.setHeader("User-Agent", config.getUserAgent());
                
                try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                    int statusCode = response.getCode();

                    if (statusCode == 200) {
                        return EntityUtils.toByteArray(response.getEntity());
                    }

                    if (statusCode == 404) {
                        log.debug("封面图片不存在: {}", url);
                        return null;
                    }

                    if (isRetryableStatus(statusCode)) {
                        // 同上: 限流不能被当成"图片不存在"
                        EntityUtils.consumeQuietly(response.getEntity());
                        throw new RetryableHttpException(statusCode, retryAfterMillis(response));
                    }

                    log.warn("下载封面图片失败,状态码 {}: {}", statusCode, url);
                    EntityUtils.consumeQuietly(response.getEntity());
                    return null;
                }
                
            } catch (Exception e) {
                retryCount++;
                
                if (retryCount > MAX_RETRIES) {
                    log.error("下载封面图片失败,已达最大重试次数({}/{}): {} - {}",
                        MAX_RETRIES, MAX_RETRIES, url, e.getMessage());
                    break;
                }

                long delayMs = retryDelayMs(e, retryCount);
                log.warn("下载封面图片失败(第{}/{}次尝试): {} - {}秒后重试",
                    retryCount, MAX_RETRIES, e.getMessage(), delayMs / 1000);

                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("重试等待被中断");
                    return null;
                }
            }
        }
        return null;
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
     * 通过标题和艺术家搜索
     */
    public List<MusicMetadata> searchByTitleAndArtist(String title, String artist) throws IOException, InterruptedException {
        rateLimit();
        
        String encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8.toString());
        String encodedArtist = URLEncoder.encode(artist, StandardCharsets.UTF_8.toString());
        
        String url = String.format("%s/recording?query=recording:%s%%20AND%%20artist:%s&fmt=json&limit=5",
            config.getMusicBrainzApiUrl(), encodedTitle, encodedArtist);
        
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
        
        // 构建搜索查询 - 先构建完整查询，再进行URL编码
        StringBuilder query = new StringBuilder();
        query.append("release:\"").append(albumName).append("\"");
        
        if (artistName != null && !artistName.trim().isEmpty()) {
            query.append(" AND artist:\"").append(artistName).append("\"");
        }
        
        // 对整个查询字符串进行URL编码
        String encodedQuery = URLEncoder.encode(query.toString(), StandardCharsets.UTF_8.toString());
        
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
                // 如果有多个艺术家，专辑艺术家使用 "Various Artists"
                if (artistCredits.size() > 1) {
                    metadata.setAlbumArtist("Various Artists");
                    // artist 字段保留完整列表
                    StringBuilder artists = new StringBuilder();
                    for (JsonNode credit : artistCredits) {
                        if (artists.length() > 0) {
                            artists.append(", ");
                        }
                        artists.append(credit.path("artist").path("name").asText());
                    }
                    metadata.setArtist(artists.toString());
                } else {
                    String artistName = artistCredits.get(0).path("artist").path("name").asText();
                    // 检查单个艺术家名称是否包含多人，或者是 Unknown Artist
                    if (artistName.contains(", ") || artistName.contains("、") ||
                        "Unknown Artist".equalsIgnoreCase(artistName)) {
                        metadata.setAlbumArtist("Various Artists");
                    } else {
                        metadata.setAlbumArtist(artistName);
                    }
                    metadata.setArtist(artistName);
                }
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
    private String executeRequest(String url) throws IOException, ParseException {
        int retryCount = 0;
        IOException lastException = null;
        
        while (retryCount <= MAX_RETRIES) {
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
                
            } catch (NonRetryableHttpException e) {
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
            StringBuilder artists = new StringBuilder();
            for (JsonNode credit : artistCredits) {
                if (artists.length() > 0) {
                    artists.append(", ");
                }
                artists.append(credit.path("artist").path("name").asText());
            }
            metadata.setArtist(artists.toString());
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
            if (fullRelease != null) {
                findAndSetTrackPosition(fullRelease, recordingId, metadata, fileDurationSeconds);
            } else {
                log.warn("Could not fetch full release details for release ID: {}. Disc and track number will be missing.", releaseId);
            }

            // 获取专辑艺术家(Album Artist)
            JsonNode releaseArtistCredits = bestRelease.path("artist-credit");
            if (releaseArtistCredits.isArray() && releaseArtistCredits.size() > 0) {
                // 如果有多个艺术家，使用 "Various Artists"
                if (releaseArtistCredits.size() > 1) {
                    metadata.setAlbumArtist("Various Artists");
                    log.info("专辑艺术家为多人({}人)，使用 Various Artists", releaseArtistCredits.size());
                } else {
                    // 单个艺术家，检查是否包含逗号分隔的多人，或者是 Unknown Artist
                    String artistName = releaseArtistCredits.get(0).path("artist").path("name").asText();
                    if (artistName.contains(", ") || artistName.contains("、") ||
                        "Unknown Artist".equalsIgnoreCase(artistName)) {
                        metadata.setAlbumArtist("Various Artists");
                        log.info("专辑艺术家为多人或未知({})，使用 Various Artists", artistName);
                    } else {
                        metadata.setAlbumArtist(artistName);
                    }
                }
            } else {
                // 如果专辑没有艺术家信息,使用单曲的艺术家
                metadata.setAlbumArtist(metadata.getArtist());
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
            parseComposerAndLyricist(root, metadata);
            
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
        parseComposerAndLyricist(root, metadata);

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

                            log.info("✓ 通过 Recording ID 找到曲目位置: 碟号 {}, 曲目号 {}", discNumber, trackNumber);
                            return; // 找到后即可退出
                        }
                    }
                }
            }

            log.warn("在专辑 {} 中未找到 Recording ID {} 的精确匹配", release.path("title").asText(), recordingId);

            // 第二阶段：如果 Recording ID 匹配失败，且提供了文件时长，尝试时长匹配
            if (fileDurationSeconds > 0) {
                log.info("尝试使用时长匹配备选方案（文件时长: {}秒）...", fileDurationSeconds);

                final int DURATION_TOLERANCE_SECONDS = 2; // 时长容差：±2秒
                JsonNode bestMatchTrack = null;
                JsonNode bestMatchMedium = null;
                int bestDurationDiff = Integer.MAX_VALUE;

                // 遍历所有曲目，找到时长最接近的
                for (JsonNode medium : media) {
                    JsonNode tracks = medium.path("tracks");
                    if (tracks.isArray()) {
                        for (JsonNode track : tracks) {
                            int trackDurationMs = track.path("length").asInt(0);
                            if (trackDurationMs > 0) {
                                int trackDurationSec = (trackDurationMs + 500) / 1000; // 四舍五入转换为秒
                                int durationDiff = Math.abs(trackDurationSec - fileDurationSeconds);

                                // 如果时长差异在容差范围内，且是目前最接近的
                                if (durationDiff <= DURATION_TOLERANCE_SECONDS && durationDiff < bestDurationDiff) {
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

                    // 更新艺术家信息
                    JsonNode artistCredits = matchedRecording.path("artist-credit");
                    if (artistCredits.isArray() && artistCredits.size() > 0) {
                        StringBuilder artists = new StringBuilder();
                        for (JsonNode credit : artistCredits) {
                            if (artists.length() > 0) {
                                artists.append(", ");
                            }
                            artists.append(credit.path("artist").path("name").asText());
                        }
                        String matchedArtist = artists.toString();
                        if (!matchedArtist.isEmpty()) {
                            metadata.setArtist(matchedArtist);
                            log.info("✓ 时长匹配成功，更新艺术家: {}", matchedArtist);
                        }
                    }

                    int matchedDurationSec = (bestMatchTrack.path("length").asInt(0) + 500) / 1000;
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
    
    /**
     * 解析作曲家和作词家信息
     * 从 relations 和 work-relations 中提取
     */
    private void parseComposerAndLyricist(JsonNode recording, MusicMetadata metadata) {
            try {
                JsonNode relations = recording.path("relations");
                if (!relations.isArray()) {
                    return;
                }
                
                StringBuilder composers = new StringBuilder();
                StringBuilder lyricists = new StringBuilder();
                
                for (JsonNode relation : relations) {
                    String relationType = relation.path("type").asText("");
                    JsonNode artist = relation.path("artist");
                    
                    if (!artist.isMissingNode()) {
                        String artistName = artist.path("name").asText("");
                        
                        // 作曲家关系类型
                        if ("composer".equalsIgnoreCase(relationType) ||
                            "composing".equalsIgnoreCase(relationType)) {
                            if (composers.length() > 0) {
                                composers.append(", ");
                            }
                            composers.append(artistName);
                        }
                        
                        // 作词家关系类型
                        if ("lyricist".equalsIgnoreCase(relationType) ||
                            "writer".equalsIgnoreCase(relationType) ||
                            "librettist".equalsIgnoreCase(relationType)) {
                            if (lyricists.length() > 0) {
                                lyricists.append(", ");
                            }
                            lyricists.append(artistName);
                        }
                    }
                    
                    // 从 work 关系中提取
                    JsonNode work = relation.path("work");
                    if (!work.isMissingNode()) {
                        JsonNode workRelations = work.path("relations");
                        if (workRelations.isArray()) {
                            for (JsonNode workRel : workRelations) {
                                String workRelType = workRel.path("type").asText("");
                                JsonNode workArtist = workRel.path("artist");
                                
                                if (!workArtist.isMissingNode()) {
                                    String workArtistName = workArtist.path("name").asText("");
                                    
                                    if ("composer".equalsIgnoreCase(workRelType)) {
                                        if (composers.length() > 0 && !composers.toString().contains(workArtistName)) {
                                            composers.append(", ");
                                        }
                                        if (!composers.toString().contains(workArtistName)) {
                                            composers.append(workArtistName);
                                        }
                                    }
                                    
                                    if ("lyricist".equalsIgnoreCase(workRelType) ||
                                        "writer".equalsIgnoreCase(workRelType)) {
                                        if (lyricists.length() > 0 && !lyricists.toString().contains(workArtistName)) {
                                            lyricists.append(", ");
                                        }
                                        if (!lyricists.toString().contains(workArtistName)) {
                                            lyricists.append(workArtistName);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                if (composers.length() > 0) {
                    metadata.setComposer(composers.toString());
                    log.debug("找到作曲家: {}", composers);
                }
                
                if (lyricists.length() > 0) {
                    metadata.setLyricist(lyricists.toString());
                    log.debug("找到作词家: {}", lyricists);
                }
                
        } catch (Exception e) {
            log.warn("解析作曲家/作词家信息失败", e);
        }
    }

    /**
     * 获取完整的 Release 信息
     */
    private JsonNode getFullReleaseById(String releaseId) throws IOException, InterruptedException {
        if (releaseId == null || releaseId.isEmpty()) {
            return null;
        }
        rateLimit();
        // inc=recordings is crucial to get the track list with recording IDs
        String url = String.format("%s/release/%s?fmt=json&inc=recordings",
            config.getMusicBrainzApiUrl(), releaseId);
        
        try {
            String response = executeRequest(url);
            return objectMapper.readTree(response);
        } catch (Exception e) {
            log.error("Failed to fetch full release details for ID: {}", releaseId, e);
            return null;
        }
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

        // --- Stage 1: 匹配 Release Group ID ---
        if (preferredReleaseGroupId != null && !preferredReleaseGroupId.isEmpty()) {
            for (JsonNode release : releases) {
                String currentReleaseGroupId = release.path("release-group").path("id").asText("");
                if (preferredReleaseGroupId.equals(currentReleaseGroupId)) {
                    log.info("找到并选择与锁定的 Release Group ID {} 匹配的专辑: {}", preferredReleaseGroupId, release.path("title").asText());
                    return release; // Found the exact one, this is our best choice.
                }
            }
            log.warn("Recording's releases did not contain the preferred Release Group ID: {}. Falling back to best match logic.", preferredReleaseGroupId);
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

        // 曲目数只有在「输入可靠」且「确实是一个专辑规模的目录」时才能约束候选。
        // 监控目录根部的散落单文件（本分支唯一的调用场景）里，musicFilesInFolder 表示的是
        // 根目录下有多少个互不相关的文件，用它做门槛会把所有候选全部否掉，等于关闭随缘模式。
        boolean trackCountUsable = musicFilesCountReliable
            && musicFilesInFolder >= MIN_FILES_FOR_TRACK_COUNT_GATE;

        if (trackCountUsable) {
            log.info("开始选择最佳专辑版本（文件夹内{}个文件），曲目数作为加权评分与可信度门槛", musicFilesInFolder);
        } else {
            log.info("开始选择最佳专辑版本（文件夹内{}个文件，曲目数不可用于约束），仅按发行类型/媒体格式评分",
                musicFilesInFolder);
        }

        for (JsonNode release : releases) {
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
                double currentScore = calculateReleaseScore(release, musicFilesInFolder, false);
                if (currentScore > bestScore + SCORE_EPSILON) {
                    bestRelease = release;
                    bestScore = currentScore;
                }
            }
        }

        if (bestRelease != null) {
            // --- 地区优先级选择：在同一个 Release Group 中选择优先地区的版本 ---
            List<String> countryPriority = config.getReleaseCountryPriority();
            if (countryPriority != null && !countryPriority.isEmpty()) {
                String bestReleaseGroupId = bestRelease.path("release-group").path("id").asText("");
                int bestTrackCount = calculateTrackCount(bestRelease);

                // 收集同一 Release Group 且曲目数相同的所有版本
                List<JsonNode> sameGroupReleases = new ArrayList<>();
                for (JsonNode release : releases) {
                    String groupId = release.path("release-group").path("id").asText("");
                    int trackCount = calculateTrackCount(release);
                    if (bestReleaseGroupId.equals(groupId) && trackCount == bestTrackCount) {
                        sameGroupReleases.add(release);
                    }
                }

                // 按地区优先级选择
                if (sameGroupReleases.size() > 1) {
                    boolean foundPreferredCountry = false;
                    for (String preferredCountry : countryPriority) {
                        for (JsonNode release : sameGroupReleases) {
                            String country = release.path("country").asText("");
                            if (preferredCountry.equalsIgnoreCase(country)) {
                                log.info("在同一专辑的{}个版本中，按地区优先级选择{}版本",
                                    sameGroupReleases.size(), preferredCountry);
                                bestRelease = release;
                                foundPreferredCountry = true;
                                break;
                            }
                        }
                        if (foundPreferredCountry) {
                            break;
                        }
                    }
                    if (!foundPreferredCountry) {
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
                bestRelease.path("artist-credit").get(0).path("artist").path("name").asText("Unknown"),
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
                StringBuilder artists = new StringBuilder();
                for (JsonNode credit : artistCredits) {
                    if (artists.length() > 0) {
                        artists.append(", ");
                    }
                    artists.append(credit.path("artist").path("name").asText());
                }
                metadata.setArtist(artists.toString());
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
    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || (statusCode >= 500 && statusCode < 600);
    }

    /**
     * 解析 Retry-After 响应头(仅支持秒数形式,MusicBrainz 与 CAA 用的就是这种)
     * @return 毫秒数,无法解析时返回 -1
     */
    private static long retryAfterMillis(HttpResponse response) {
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
    private static long retryDelayMs(Exception e, int attempt) {
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
    private static class RetryableHttpException extends IOException {
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
    private void rateLimit() throws InterruptedException {
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
        
        if (releaseId == null || releaseId.isEmpty()) {
            log.warn("未提供锁定的 Release ID，无法执行强制专辑匹配");
            return null;
        }
        
        if (fileDurationSeconds <= 0) {
            log.warn("未提供有效的文件时长，无法执行时长匹配");
            return null;
        }
        
        log.info("=== 强制使用锁定专辑模式 ===");
        log.info("锁定专辑: {} (Release ID: {})", lockedAlbumTitle, releaseId);
        log.info("文件时长: {}秒，将在锁定专辑中按时长查找匹配曲目", fileDurationSeconds);
        
        // 获取完整的 Release 信息（包含 recordings）
        rateLimit();
        String url = String.format("%s/release/%s?fmt=json&inc=recordings+artist-credits+release-groups",
            config.getMusicBrainzApiUrl(), releaseId);
        
        try {
            String response = executeRequest(url);
            JsonNode release = objectMapper.readTree(response);
            
            JsonNode media = release.path("media");
            if (!media.isArray() || media.size() == 0) {
                log.warn("锁定专辑没有媒体信息");
                return null;
            }
            
            // 时长匹配容差
            final int DURATION_TOLERANCE_SECONDS = 3; // 容差：±3秒
            
            JsonNode bestMatchTrack = null;
            JsonNode bestMatchMedium = null;
            int bestDurationDiff = Integer.MAX_VALUE;
            
            // 遍历所有曲目，找到时长最接近的
            for (JsonNode medium : media) {
                // 跳过视频格式
                String format = medium.path("format").asText("").toLowerCase();
                if (isVideoFormat(format)) {
                    continue;
                }
                
                JsonNode tracks = medium.path("tracks");
                if (tracks.isArray()) {
                    for (JsonNode track : tracks) {
                        // 跳过视频曲目
                        JsonNode recording = track.path("recording");
                        if (recording.path("video").asBoolean(false)) {
                            continue;
                        }
                        
                        int trackDurationMs = track.path("length").asInt(0);
                        if (trackDurationMs > 0) {
                            int trackDurationSec = (trackDurationMs + 500) / 1000; // 四舍五入
                            int durationDiff = Math.abs(trackDurationSec - fileDurationSeconds);
                            
                            // 如果时长差异在容差范围内，且是目前最接近的
                            if (durationDiff <= DURATION_TOLERANCE_SECONDS && durationDiff < bestDurationDiff) {
                                bestMatchTrack = track;
                                bestMatchMedium = medium;
                                bestDurationDiff = durationDiff;
                            }
                        }
                    }
                }
            }
            
            // 如果找到了匹配的曲目
            if (bestMatchTrack != null) {
                MusicMetadata metadata = new MusicMetadata();
                
                // 设置专辑信息（使用锁定的信息）
                metadata.setAlbum(lockedAlbumTitle);
                metadata.setAlbumArtist(lockedAlbumArtist);
                metadata.setReleaseGroupId(releaseGroupId);
                metadata.setReleaseId(releaseId);
                applyReleaseGroupMetadata(metadata, release.path("release-group"));
                
                // 设置碟号和曲目号
                String discNumber = bestMatchMedium.path("position").asText("");
                String trackNumber = bestMatchTrack.path("position").asText("");
                metadata.setDiscNo(discNumber);
                metadata.setTrackNo(trackNumber);
                
                // 从匹配的 recording 获取曲目信息
                JsonNode matchedRecording = bestMatchTrack.path("recording");
                metadata.setRecordingId(matchedRecording.path("id").asText(""));
                metadata.setTitle(matchedRecording.path("title").asText(""));
                
                // 获取艺术家信息
                JsonNode artistCredits = matchedRecording.path("artist-credit");
                if (artistCredits.isArray() && artistCredits.size() > 0) {
                    StringBuilder artists = new StringBuilder();
                    for (JsonNode credit : artistCredits) {
                        if (artists.length() > 0) {
                            artists.append(", ");
                        }
                        artists.append(credit.path("artist").path("name").asText());
                    }
                    metadata.setArtist(artists.toString());
                } else {
                    // 如果 recording 没有艺术家信息，尝试从 track 的 artist-credit 获取
                    JsonNode trackArtistCredits = bestMatchTrack.path("artist-credit");
                    if (trackArtistCredits.isArray() && trackArtistCredits.size() > 0) {
                        StringBuilder artists = new StringBuilder();
                        for (JsonNode credit : trackArtistCredits) {
                            if (artists.length() > 0) {
                                artists.append(", ");
                            }
                            artists.append(credit.path("artist").path("name").asText());
                        }
                        metadata.setArtist(artists.toString());
                    }
                }
                
                // 获取发行日期
                metadata.setReleaseDate(release.path("date").asText(""));
                
                int matchedDurationSec = (bestMatchTrack.path("length").asInt(0) + 500) / 1000;
                log.info("✓ 强制专辑匹配成功！");
                log.info("  曲目: {} - {}", metadata.getArtist(), metadata.getTitle());
                log.info("  位置: 碟号 {}, 曲目号 {}", discNumber, trackNumber);
                log.info("  时长匹配: 文件 {}秒 vs 曲目 {}秒 (差异: {}秒)",
                    fileDurationSeconds, matchedDurationSec, bestDurationDiff);
                
                return metadata;
            } else {
                log.warn("在锁定专辑 {} 中未找到时长匹配的曲目（文件时长: {}秒，容差: ±{}秒）",
                    lockedAlbumTitle, fileDurationSeconds, DURATION_TOLERANCE_SECONDS);
                return null;
            }
            
        } catch (ParseException e) {
            log.error("解析锁定专辑响应失败", e);
            return null;
        }
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

        if (releaseGroupId == null || releaseGroupId.isEmpty()) {
            log.warn("未提供 Release Group ID，无法执行强制专辑匹配");
            return null;
        }
        
        if (fileDurationSeconds <= 0) {
            log.warn("未提供有效的文件时长，无法执行时长匹配");
            return null;
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
                return null;
            }
            
            log.info("Release Group {} 共有 {} 个 releases（{}）",
                releaseGroupId, releases.size(),
                trackCountUsable ? "选择曲目数最接近 " + musicFilesInFolder + " 的版本" : "仅按媒体格式选版");

            // 2. 选择最佳的 Release
            JsonNode bestRelease = null;
            int bestTrackCountDiff = Integer.MAX_VALUE;
            int bestScore = -1;

            for (JsonNode release : releases) {
                int score = scoreReleaseForDuration(release);

                // 跳过视频格式
                if (score < 0) {
                    continue;
                }

                if (!trackCountUsable) {
                    if (score > bestScore) {
                        bestRelease = release;
                        bestScore = score;
                    }
                    continue;
                }

                int trackCount = calculateTrackCount(release);
                int trackCountDiff = Math.abs(trackCount - musicFilesInFolder);

                // 优先选择曲目数最接近的
                if (trackCountDiff < bestTrackCountDiff) {
                    bestRelease = release;
                    bestTrackCountDiff = trackCountDiff;
                    bestScore = score;
                } else if (trackCountDiff == bestTrackCountDiff && score > bestScore) {
                    // 曲目数相同时，选择格式评分更高的
                    bestRelease = release;
                    bestScore = score;
                }
            }
            
            if (bestRelease == null) {
                log.warn("未找到合适的 Release");
                return null;
            }
            
            String bestReleaseId = bestRelease.path("id").asText();
            String bestReleaseTitle = bestRelease.path("title").asText();
            int bestTrackCount = calculateTrackCount(bestRelease);
            
            log.info("选择 Release: {} (ID: {}, {} 首曲目)",
                bestReleaseTitle, bestReleaseId, bestTrackCount);
            
            // 3. 使用选定的 Release ID 调用现有的时长匹配方法
            MusicMetadata metadata = getTrackFromLockedAlbumByDuration(
                bestReleaseId,
                releaseGroupId,
                fileDurationSeconds,
                lockedAlbumTitle,
                lockedAlbumArtist
            );
            if (metadata != null) {
                applyReleaseGroupMetadata(metadata, rgRoot);
                metadata.setReleaseGroupId(releaseGroupId);
                metadata.setReleaseId(bestReleaseId);
            }
            return metadata;
            
        } catch (ParseException e) {
            log.error("解析 Release Group 响应失败", e);
            return null;
        }
    }
    
    /**
     * 关闭客户端
     */
    public void close() throws IOException {
        httpClient.close();
    }
    
}
