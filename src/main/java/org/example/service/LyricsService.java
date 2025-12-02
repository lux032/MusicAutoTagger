package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.example.config.MusicConfig;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 歌词服务
 * 使用 LrcLib API 获取歌词
 */
@Slf4j
public class LyricsService {

    private static final String LRCLIB_API_URL = "https://lrclib.net/api/get";
    private final MusicConfig config;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public LyricsService(MusicConfig config) {
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
            .setConnectionRequestTimeout(Timeout.ofSeconds(10))
            .setResponseTimeout(Timeout.ofSeconds(10))
            .build();
        builder.setDefaultRequestConfig(requestConfig);
        
        // 配置代理
        if (config.isProxyEnabled() && config.getProxyHost() != null && !config.getProxyHost().isEmpty()) {
            HttpHost proxy = new HttpHost(config.getProxyHost(), config.getProxyPort());
            builder.setProxy(proxy);
            log.debug("LyricsService 使用代理: {}:{}", config.getProxyHost(), config.getProxyPort());
        }
        
        return builder.build();
    }

    /**
     * 获取歌词
     * @param title 标题
     * @param artist 艺术家
     * @param album 专辑
     * @param durationSeconds 时长(秒)
     * @return 歌词文本(优先返回同步歌词，没有则返回纯文本)
     */
    public String getLyrics(String title, String artist, String album, int durationSeconds) {
        if (title == null || artist == null) {
            return null;
        }

        try {
            // 构建查询参数
            StringBuilder urlBuilder = new StringBuilder(LRCLIB_API_URL);
            urlBuilder.append("?artist_name=").append(URLEncoder.encode(artist, StandardCharsets.UTF_8.toString()));
            urlBuilder.append("&track_name=").append(URLEncoder.encode(title, StandardCharsets.UTF_8.toString()));
            
            if (album != null && !album.isEmpty()) {
                urlBuilder.append("&album_name=").append(URLEncoder.encode(album, StandardCharsets.UTF_8.toString()));
            }
            
            if (durationSeconds > 0) {
                urlBuilder.append("&duration=").append(durationSeconds);
            }

            String url = urlBuilder.toString();
            log.debug("正在请求 LrcLib 获取歌词: {}", url);

            HttpGet httpGet = new HttpGet(url);
            httpGet.setHeader("User-Agent", "MusicTagTool/1.0 ( https://github.com/your/project )");

            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                int statusCode = response.getCode();
                if (statusCode == 404) {
                    log.info("LrcLib 未找到歌词: {} - {}", artist, title);
                    return null;
                }
                
                if (statusCode != 200) {
                    log.warn("LrcLib API 请求失败: {}", statusCode);
                    return null;
                }

                String json = EntityUtils.toString(response.getEntity());
                JsonNode root = objectMapper.readTree(json);

                // 优先获取同步歌词
                String syncedLyrics = root.path("syncedLyrics").asText(null);
                if (syncedLyrics != null && !syncedLyrics.isEmpty()) {
                    log.info("成功获取同步歌词: {} - {}", artist, title);
                    return syncedLyrics;
                }

                // 其次获取纯文本歌词
                String plainLyrics = root.path("plainLyrics").asText(null);
                if (plainLyrics != null && !plainLyrics.isEmpty()) {
                    log.info("成功获取纯文本歌词: {} - {}", artist, title);
                    return plainLyrics;
                }
            }

        } catch (Exception e) {
            log.warn("获取歌词失败: {} - {}", artist, title, e);
        }

        return null;
    }

    public void close() throws IOException {
        httpClient.close();
    }
}