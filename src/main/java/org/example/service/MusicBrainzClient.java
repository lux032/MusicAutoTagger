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
            log.info("MusicBrainz 客户端使用代理: {}:{}", config.getProxyHost(), config.getProxyPort());
            
            // 注意: 代理认证功能已简化,如需认证代理,请使用系统代理设置
            // 或在代理软件中配置允许本地连接无需认证
        }
        
        return builder.build();
    }
    
    /**
     * 通过 Recording ID 查询音乐信息
     * @param recordingId MusicBrainz Recording ID
     * @param musicFilesInFolder 文件所在文件夹的音乐文件数量,用于判断是否为单曲
     */
    public MusicMetadata getRecordingById(String recordingId, int musicFilesInFolder) throws IOException, InterruptedException {
        rateLimit();
        
        // 增加 artist-rels 和 work-rels 以获取作曲家、作词家信息
        String url = String.format("%s/recording/%s?fmt=json&inc=artists+releases+tags+release-groups+artist-rels+work-rels+work-level-rels",
            config.getMusicBrainzApiUrl(), recordingId);
        
        try {
            String response = executeRequest(url);
            MusicMetadata metadata = parseRecordingResponse(response, musicFilesInFolder);
            
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
     * 获取封面图片 URL(带重试机制)
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
     */
    private MusicMetadata parseRecordingResponse(String json, int musicFilesInFolder) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        
        MusicMetadata metadata = new MusicMetadata();
        metadata.setRecordingId(root.path("id").asText());
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
            JsonNode bestRelease = selectBestRelease(releases, musicFilesInFolder);
            metadata.setAlbum(bestRelease.path("title").asText());
            metadata.setReleaseDate(bestRelease.path("date").asText());
            metadata.setReleaseGroupId(bestRelease.path("release-group").path("id").asText());
            
            // 获取专辑艺术家(Album Artist)
            JsonNode releaseArtistCredits = bestRelease.path("artist-credit");
            if (releaseArtistCredits.isArray() && releaseArtistCredits.size() > 0) {
                StringBuilder albumArtists = new StringBuilder();
                for (JsonNode credit : releaseArtistCredits) {
                    if (albumArtists.length() > 0) {
                        albumArtists.append(", ");
                    }
                    albumArtists.append(credit.path("artist").path("name").asText());
                }
                metadata.setAlbumArtist(albumArtists.toString());
            } else {
                // 如果专辑没有艺术家信息,使用单曲的艺术家
                metadata.setAlbumArtist(metadata.getArtist());
                }
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
     * 选择最佳专辑版本
     * 优先级逻辑：
     * - 如果文件夹音乐文件数≤2，优先匹配Single（可能是单曲+伴奏）
     * - 如果文件夹音乐文件数3-6，优先匹配EP，其次匹配Album
     * - 否则按原逻辑：Album > EP > Single > Compilation > Others
     * 同类型下：曲目数多优先 > 发行时间早优先
     * @param releases 所有发行版本
     * @param musicFilesInFolder 文件所在文件夹的音乐文件数量
     */
    private JsonNode selectBestRelease(JsonNode releases, int musicFilesInFolder) {
        JsonNode bestRelease = null;
        int bestScore = -1;

        for (JsonNode release : releases) {
            int currentScore = calculateReleaseScore(release, musicFilesInFolder);
            
            if (bestRelease == null || currentScore > bestScore) {
                bestRelease = release;
                bestScore = currentScore;
            } else if (currentScore == bestScore) {
                // 分数相同，优先选择发行时间早的
                String date1 = bestRelease.path("date").asText("");
                String date2 = release.path("date").asText("");
                if (date2.compareTo(date1) < 0 && !date2.isEmpty()) {
                    bestRelease = release;
                }
            }
        }
        return bestRelease != null ? bestRelease : releases.get(0);
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
        
        // 1. 类型评分 (0-100)
        // 关键改动：根据文件夹内音乐文件数量,优先匹配对应类型
        boolean isMiniCD = (musicFilesInFolder <= 2);        // 单曲或单曲+伴奏
        boolean isEPSized = (musicFilesInFolder >= 3 && musicFilesInFolder <= 6);  // EP大小
        
        switch (type) {
            case "album":
                if (isMiniCD) {
                    score += 50;  // 迷你CD,降低Album权重
                } else if (isEPSized) {
                    score += 90;  // EP大小,略微降低Album权重,优先匹配EP
                } else {
                    score += 100; // 正常情况,Album优先
                }
                break;
            case "ep":
                if (isMiniCD) {
                    score += 70;
                } else if (isEPSized) {
                    score += 150; // EP大小,大幅提升EP权重
                } else {
                    score += 80;
                }
                break;
            case "single":
                score += isMiniCD ? 150 : 60;  // 迷你CD,大幅提升Single权重
                break;
            case "compilation":
                score += 40;
                break;
            default:
                score += 20;
        }
        
        if (isMiniCD && type.equals("single")) {
            log.info("检测到迷你CD（文件夹内{}个文件），优先匹配Single类型", musicFilesInFolder);
        }
        if (isEPSized && type.equals("ep")) {
            log.info("检测到EP大小（文件夹内{}个文件），优先匹配EP类型", musicFilesInFolder);
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
        
        // 3. 完整性评分 (曲目数量)
        int trackCount = release.path("track-count").asInt(0);
        if (trackCount > 0) {
            // 轻微加分，防止单曲被错误识别为大专辑，但也避免选到只有1首歌的"专辑"
            score += Math.min(trackCount, 20);
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
     * 关闭客户端
     */
    public void close() throws IOException {
        httpClient.close();
    }
    
    /**
     * 音乐元数据类
     */
    @Data
    public static class MusicMetadata {
        private String recordingId;
        private String title;
        private String artist;
        private String albumArtist; // 专辑艺术家(用于文件夹组织)
        private String album;
        private String releaseGroupId; // Release Group ID，用于查询封面
        private String releaseDate;
        private List<String> genres;
        private String coverArtUrl; // 封面图片 URL
        private int score; // 搜索匹配度分数
        
        // 新增字段
        private String composer; // 作曲家
        private String lyricist; // 作词家
        private String lyrics; // 歌词
        
        @Override
        public String toString() {
            return String.format("MusicMetadata{title='%s', artist='%s', albumArtist='%s', album='%s', score=%d}",
                title, artist, albumArtist, album, score);
        }
    }
}