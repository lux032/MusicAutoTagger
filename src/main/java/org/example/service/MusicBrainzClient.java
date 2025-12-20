package org.example.service;

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
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.util.Timeout;
import org.example.config.MusicConfig;
import org.example.model.MusicMetadata;
import org.example.util.I18nUtil;

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
    private long lastRequestTime = 0;
    private static final long REQUEST_INTERVAL = 1000; // MusicBrainz 要求至少1秒间隔
    private static final int MAX_RETRIES = 3; // 最大重试次数
    private static final long RETRY_DELAY_MS = 10000; // 重试间隔10秒
    
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
        rateLimit();

        // 增加 media 来获取曲目数信息，增加 artist-rels 和 work-rels 以获取作曲家、作词家信息
        String url = String.format("%s/recording/%s?fmt=json&inc=artists+releases+tags+release-groups+artist-rels+work-rels+work-level-rels+media",
            config.getMusicBrainzApiUrl(), recordingId);

        try {
            String response = executeRequest(url);
            MusicMetadata metadata = parseRecordingResponse(response, musicFilesInFolder, preferredReleaseGroupId, preferredReleaseId, fileDurationSeconds);
            
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
     * 专辑时长序列结果（包含 Release ID）
     */
    @Data
    public static class AlbumDurationResult {
        private final List<Integer> durations;
        private final String releaseId;
        
        public AlbumDurationResult(List<Integer> durations, String releaseId) {
            this.durations = durations;
            this.releaseId = releaseId;
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
     * 获取封面图片 URL(带重试机制) - 公共方法
     */
    public String getCoverArtUrlByReleaseGroupId(String releaseGroupId) {
        return getCoverArtUrl(releaseGroupId);
    }

    /**
     * 获取封面图片 URL(带重试机制) - 内部方法
     */
    private String getCoverArtUrl(String releaseGroupId) {
        int retryCount = 0;
        
        while (retryCount <= MAX_RETRIES) {
            try {
                rateLimit();
                String url = String.format("%s/release-group/%s", config.getCoverArtApiUrl(), releaseGroupId);
                
                HttpGet httpGet = new HttpGet(url);
                httpGet.setHeader("User-Agent", config.getUserAgent());
                httpGet.setHeader("Accept", "application/json");
                
                try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                    if (response.getCode() == 200) {
                        String json = EntityUtils.toString(response.getEntity());
                        JsonNode root = objectMapper.readTree(json);
                        JsonNode images = root.path("images");
                        
                        if (images.isArray()) {
                            for (JsonNode image : images) {
                                if (image.path("front").asBoolean()) {
                                    return image.path("image").asText();
                                }
                            }
                        }
                    }
                }
                // 请求成功但未找到封面,不需要重试
                return null;
                
            } catch (Exception e) {
                retryCount++;
                
                if (retryCount <= MAX_RETRIES) {
                    log.warn("获取封面失败(第{}/{}次尝试): {} - {}秒后重试",
                        retryCount, MAX_RETRIES, e.getMessage(), RETRY_DELAY_MS / 1000);
                    
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("重试等待被中断");
                        return null;
                    }
                } else {
                    log.error("获取封面失败,已达最大重试次数({}/{}): {}", retryCount, MAX_RETRIES, releaseGroupId);
                }
            }
        }
        return null;
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
                    if (response.getCode() == 200) {
                        return EntityUtils.toByteArray(response.getEntity());
                    }
                }
                // 请求成功但状态码不是200,不需要重试
                return null;
                
            } catch (Exception e) {
                retryCount++;
                
                if (retryCount <= MAX_RETRIES) {
                    log.warn("下载封面图片失败(第{}/{}次尝试): {} - {}秒后重试",
                        retryCount, MAX_RETRIES, e.getMessage(), RETRY_DELAY_MS / 1000);
                    
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("重试等待被中断");
                        return null;
                    }
                } else {
                    log.error("下载封面图片失败,已达最大重试次数({}/{}): {}", retryCount, MAX_RETRIES, url);
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
            metadata.setScore(release.path("score").asInt(0));
            
            // Release Group ID
            JsonNode releaseGroup = release.path("release-group");
            if (!releaseGroup.isMissingNode()) {
                metadata.setReleaseGroupId(releaseGroup.path("id").asText());
            }
            
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
                    
                    if (statusCode != 200) {
                        log.error("MusicBrainz API 请求失败: {} - {}", statusCode, responseBody);
                        throw new IOException("API 请求失败: " + statusCode);
                    }
                    
                    log.debug("MusicBrainz API 响应: {}", responseBody);
                    return responseBody;
                }
                
            } catch (IOException e) {
                lastException = e;
                retryCount++;
                
                if (retryCount <= MAX_RETRIES) {
                    log.warn("网络请求失败(第{}/{}次尝试): {} - {}秒后重试",
                        retryCount, MAX_RETRIES, e.getMessage(), RETRY_DELAY_MS / 1000);
                    
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
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
    private MusicMetadata parseRecordingResponse(String json, int musicFilesInFolder, String preferredReleaseGroupId, String preferredReleaseId, int fileDurationSeconds) throws IOException, InterruptedException {
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
        if (releases.isArray() && releases.size() > 0) {
            JsonNode bestRelease = selectBestRelease(releases, musicFilesInFolder, preferredReleaseGroupId, preferredReleaseId);
            metadata.setAlbum(bestRelease.path("title").asText());
            metadata.setReleaseDate(bestRelease.path("date").asText());
            metadata.setReleaseGroupId(bestRelease.path("release-group").path("id").asText());
            
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
        
        // 如果没有找到任何releases,返回基本元数据
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
     */
    private JsonNode selectBestRelease(JsonNode releases, int musicFilesInFolder, String preferredReleaseGroupId, String preferredReleaseId) {
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

        JsonNode bestRelease = null;
        int bestScore = -1;
        int bestTrackCountDiff = Integer.MAX_VALUE;

        log.info("开始选择最佳专辑版本（文件夹内{}个文件），优先匹配曲目数接近的版本", musicFilesInFolder);

        for (JsonNode release : releases) {
            int currentScore = calculateReleaseScore(release, musicFilesInFolder);
            
            // 从media中计算总曲目数
            int trackCount = calculateTrackCount(release);
            int trackCountDiff = Math.abs(trackCount - musicFilesInFolder);
            
            // 统一策略：所有专辑都优先考虑曲目数匹配度
            // 曲目数差异最小的优先
            if (trackCountDiff < bestTrackCountDiff) {
                bestRelease = release;
                bestScore = currentScore;
                bestTrackCountDiff = trackCountDiff;
                log.debug("选择曲目数更接近的专辑: {} ({}首 vs 文件夹{}首)",
                    release.path("title").asText(), trackCount, musicFilesInFolder);
            }
            // 曲目数差异相同时，比较类型评分
            else if (trackCountDiff == bestTrackCountDiff) {
                if (currentScore > bestScore) {
                    bestRelease = release;
                    bestScore = currentScore;
                    log.debug("曲目数相同，选择评分更高的: {} (评分: {})",
                        release.path("title").asText(), currentScore);
                } else if (currentScore == bestScore) {
                    // 评分也相同，优先选择发行时间早的
                    String date1 = bestRelease.path("date").asText("");
                    String date2 = release.path("date").asText("");
                    if (!date2.isEmpty() && date2.compareTo(date1) < 0) {
                        bestRelease = release;
                        log.debug("曲目数和评分相同，选择发行时间更早的: {} ({})",
                            release.path("title").asText(), date2);
                    }
                }
            }
        }
        
        if (bestRelease != null) {
            int finalTrackCount = calculateTrackCount(bestRelease);
            String releaseType = bestRelease.path("release-group").path("primary-type").asText("Unknown");
            log.info("最终选择: {} - {} ({}首曲目，类型: {})",
                bestRelease.path("title").asText(),
                bestRelease.path("artist-credit").get(0).path("artist").path("name").asText("Unknown"),
                finalTrackCount,
                releaseType);
        }
        
        return bestRelease != null ? bestRelease : releases.get(0);
    }

    /**
     * 从release的media中计算总曲目数
     */
    private int calculateTrackCount(JsonNode release) {
        int totalTracks = 0;
        
        // 首先尝试从 track-count 字段获取
        totalTracks = release.path("track-count").asInt(0);
        
        // 如果没有track-count字段，从media中计算
        if (totalTracks == 0) {
            JsonNode media = release.path("media");
            if (media.isArray()) {
                for (JsonNode medium : media) {
                    int trackCountInMedium = medium.path("track-count").asInt(0);
                    totalTracks += trackCountInMedium;
                }
            }
        }
        
        return totalTracks;
    }
    
    /**
     * 计算专辑评分
     * @param release 发行版本
     * @param musicFilesInFolder 文件所在文件夹的音乐文件数量
     */
    private int calculateReleaseScore(JsonNode release, int musicFilesInFolder) {
        int score = 0;
        
        JsonNode releaseGroup = release.path("release-group");
        String type = releaseGroup.path("primary-type").asText("").toLowerCase();
        int trackCount = calculateTrackCount(release);
        
        // 1. 类型评分 (0-100)
        // 根据文件夹内音乐文件数量,优先匹配对应类型
        boolean isMiniCD = (musicFilesInFolder <= 2);        // 单曲或单曲+伴奏
        boolean isEPSized = (musicFilesInFolder >= 3 && musicFilesInFolder <= 6);  // EP大小
        boolean isLargeCollection = (musicFilesInFolder >= 15); // 大型合辑
        
        switch (type) {
            case "album":
                if (isMiniCD) {
                    score += 50;  // 迷你CD,降低Album权重
                } else if (isEPSized) {
                    score += 90;  // EP大小,略微降低Album权重,优先匹配EP
                } else if (isLargeCollection) {
                    // 大型合辑场景：优先匹配Album和Compilation类型
                    score += 120;
                } else {
                    score += 100; // 正常情况,Album优先
                }
                break;
            case "ep":
                if (isMiniCD) {
                    score += 70;
                } else if (isEPSized) {
                    score += 150; // EP大小,大幅提升EP权重
                } else if (isLargeCollection && trackCount >= musicFilesInFolder * 0.7) {
                    // 如果EP曲目数接近文件夹数量,也可能是合辑的一部分
                    score += 90;
                } else {
                    score += 80;
                }
                break;
            case "single":
                score += isMiniCD ? 150 : 60;  // 迷你CD,大幅提升Single权重
                break;
            case "compilation":
                if (isLargeCollection) {
                    // 大型合辑场景：Compilation类型也是很好的选择
                    score += 110;
                } else {
                    score += 40;
                }
                break;
            default:
                score += 20;
        }
        
        if (isMiniCD && type.equals("single")) {
            log.debug("检测到迷你CD（文件夹内{}个文件），优先匹配Single类型", musicFilesInFolder);
        }
        if (isEPSized && type.equals("ep")) {
            log.debug("检测到EP大小（文件夹内{}个文件），优先匹配EP类型", musicFilesInFolder);
        }
        if (isLargeCollection && (type.equals("album") || type.equals("compilation"))) {
            log.debug("检测到大型合辑（文件夹内{}个文件），当前专辑{}首，类型{}",
                musicFilesInFolder, trackCount, type.toUpperCase());
        }
        
        // 二级类型降权 (如 Live, Remix)
        JsonNode secondaryTypes = releaseGroup.path("secondary-types");
        if (secondaryTypes.isArray()) {
            for (JsonNode secondaryType : secondaryTypes) {
                String sType = secondaryType.asText().toLowerCase();
                if (sType.equals("live") || sType.equals("remix") || sType.equals("demo")) {
                    score -= 15;
                }
            }
        }

        // 2. 媒体格式评分 (0-10)
        JsonNode media = release.path("media");
        if (media.isArray() && media.size() > 0) {
            String format = media.get(0).path("format").asText("").toLowerCase();
            if (format.contains("cd") || format.contains("digital")) {
                score += 10;
            } else if (format.contains("vinyl")) {
                score += 5;
            }
        }
        
        // 3. 曲目数量匹配度评分
        if (trackCount > 0) {
            if (isLargeCollection) {
                // 大型合辑：曲目数越接近文件夹数量,分数越高
                int trackDiff = Math.abs(trackCount - musicFilesInFolder);
                int matchScore = Math.max(0, 50 - trackDiff * 2); // 差异越小分数越高
                score += matchScore;
                
                // 额外奖励：如果曲目数在文件夹数量的80%-120%范围内
                if (trackCount >= musicFilesInFolder * 0.8 && trackCount <= musicFilesInFolder * 1.2) {
                    score += 30;
                    log.debug("曲目数匹配度高: {}首 vs 文件夹{}首 (+30分)", trackCount, musicFilesInFolder);
                }
            } else {
                // 非大型合辑：轻微加分
                score += Math.min(trackCount, 20);
            }
        }

        return score;
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
            }
            
            results.add(metadata);
        }
        
        return results;
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
        String url = String.format("%s/release/%s?fmt=json&inc=recordings+artist-credits",
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
            
            log.info("Release Group {} 共有 {} 个 releases，选择曲目数最接近 {} 的版本",
                releaseGroupId, releases.size(), musicFilesInFolder);
            
            // 2. 选择最佳的 Release（曲目数最接近文件夹内文件数量）
            JsonNode bestRelease = null;
            int bestTrackCountDiff = Integer.MAX_VALUE;
            int bestScore = -1;
            
            for (JsonNode release : releases) {
                int trackCount = calculateTrackCount(release);
                int trackCountDiff = Math.abs(trackCount - musicFilesInFolder);
                int score = scoreReleaseForDuration(release);
                
                // 跳过视频格式
                if (score < 0) {
                    continue;
                }
                
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
            return getTrackFromLockedAlbumByDuration(
                bestReleaseId,
                releaseGroupId,
                fileDurationSeconds,
                lockedAlbumTitle,
                lockedAlbumArtist
            );
            
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