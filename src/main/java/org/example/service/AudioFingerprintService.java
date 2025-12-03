package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.util.Timeout;
import org.example.config.MusicConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 音频指纹识别服务
 * 使用 fpcalc (Chromaprint) 和 AcoustID API 进行音频指纹识别
 */
@Slf4j
public class AudioFingerprintService {
    
    private final MusicConfig config;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    // fpcalc 工具路径（需要用户安装 Chromaprint）
    private String fpcalcPath = "fpcalc";
    
    // 重试配置
    private static final int MAX_RETRIES = 3; // 最大重试次数
    private static final long RETRY_DELAY_MS = 5000; // 重试间隔5秒
    
    public AudioFingerprintService(MusicConfig config) {
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
            log.info("AcoustID 客户端使用代理: {}:{}", config.getProxyHost(), config.getProxyPort());
            
            // 注意: 代理认证功能已简化,如需认证代理,请使用系统代理设置
            // 或在代理软件中配置允许本地连接无需认证
        }
        
        return builder.build();
    }
    
    /**
     * 设置 fpcalc 工具路径
     */
    public void setFpcalcPath(String path) {
        this.fpcalcPath = path;
    }
    
    /**
     * 生成音频指纹
     */
    public AudioFingerprint generateFingerprint(File audioFile) throws IOException, InterruptedException {
        if (!audioFile.exists()) {
            throw new IOException("音频文件不存在: " + audioFile.getAbsolutePath());
        }
        
        // 执行 fpcalc 命令
        ProcessBuilder processBuilder = new ProcessBuilder(
            fpcalcPath,
            "-json",
            audioFile.getAbsolutePath()
        );
        
        Process process = processBuilder.start();
        
        // 读取输出
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }
        
        // 等待进程完成
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("fpcalc 执行失败，退出码: " + exitCode);
        }
        
        // 解析 JSON 输出
        String jsonOutput = output.toString();
        log.debug("fpcalc 输出: {}", jsonOutput);
        
        JsonNode root = objectMapper.readTree(jsonOutput);
        
        AudioFingerprint fingerprint = new AudioFingerprint();
        fingerprint.setDuration(root.path("duration").asInt());
        fingerprint.setFingerprint(root.path("fingerprint").asText());
        
        log.info("成功生成音频指纹 - 文件: {}, 时长: {}秒", 
            audioFile.getName(), fingerprint.getDuration());
        
        return fingerprint;
    }
    
    /**
     * 通过 AcoustID API 查询音乐信息（带重试机制）
     */
    public AcoustIdResult lookupByFingerprint(AudioFingerprint fingerprint) throws IOException, InterruptedException {
        if (config.getAcoustIdApiKey() == null || config.getAcoustIdApiKey().isEmpty()) {
            throw new IllegalStateException("AcoustID API Key 未配置");
        }
        
        int retryCount = 0;
        IOException lastException = null;
        
        while (retryCount <= MAX_RETRIES) {
            try {
                // 构建请求参数
                List<NameValuePair> params = new ArrayList<>();
                params.add(new BasicNameValuePair("client", config.getAcoustIdApiKey()));
                params.add(new BasicNameValuePair("duration", String.valueOf(fingerprint.getDuration())));
                params.add(new BasicNameValuePair("fingerprint", fingerprint.getFingerprint()));
                params.add(new BasicNameValuePair("meta", "recordings releasegroups"));
                
                if (retryCount == 0) {
                    log.info("AcoustID API 请求参数 - duration: {}, meta: recordings releasegroups compress",
                        fingerprint.getDuration());
                }
                
                // 发送 POST 请求
                HttpPost httpPost = new HttpPost(config.getAcoustIdApiUrl());
                // 使用 UTF-8 编码,这很关键!
                httpPost.setEntity(new UrlEncodedFormEntity(params, java.nio.charset.StandardCharsets.UTF_8));
                httpPost.setHeader("User-Agent", config.getUserAgent());
                httpPost.setHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                
                try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                    int statusCode = response.getCode();
                    String responseBody = EntityUtils.toString(response.getEntity());
                    
                    if (statusCode != 200) {
                        log.error("AcoustID API 请求失败: {} - {}", statusCode, responseBody);
                        throw new IOException("API 请求失败: " + statusCode);
                    }
                    
                    log.info("AcoustID API 响应: {}", responseBody);
                    return parseAcoustIdResponse(responseBody);
                } catch (ParseException e) {
                    log.error("解析响应失败", e);
                    throw new IOException("解析响应失败", e);
                }
                
            } catch (IOException e) {
                lastException = e;
                retryCount++;
                
                if (retryCount <= MAX_RETRIES) {
                    log.warn("AcoustID API 请求失败(第{}/{}次尝试): {} - {}秒后重试",
                        retryCount, MAX_RETRIES, e.getMessage(), RETRY_DELAY_MS / 1000);
                    
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("重试等待被中断", ie);
                    }
                } else {
                    log.error("AcoustID API 请求失败,已达最大重试次数({}/{}): {}",
                        retryCount, MAX_RETRIES, e.getMessage());
                }
            }
        }
        
        // 所有重试都失败,抛出最后一次异常
        throw lastException;
    }
    
    /**
     * 解析 AcoustID API 响应
     */
    private AcoustIdResult parseAcoustIdResponse(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        
        AcoustIdResult result = new AcoustIdResult();
        result.setStatus(root.path("status").asText());
        
        if (!"ok".equals(result.getStatus())) {
            log.warn("AcoustID 查询未找到匹配结果");
            return result;
        }
        
        JsonNode results = root.path("results");
        if (!results.isArray() || results.size() == 0) {
            log.warn("AcoustID 未返回任何结果");
            return result;
        }
        
        // 取第一个匹配结果
        JsonNode firstResult = results.get(0);
        result.setScore(firstResult.path("score").asDouble());
        result.setAcoustId(firstResult.path("id").asText());
        
        // 解析录音信息
        JsonNode recordings = firstResult.path("recordings");
        log.info("recordings 节点状态 - isMissingNode: {}, isArray: {}, size: {}",
            recordings.isMissingNode(), recordings.isArray(),
            recordings.isArray() ? recordings.size() : 0);
        
        if (recordings.isMissingNode()) {
            log.warn("API 响应中缺少 recordings 字段,这可能是因为:");
            log.warn("1. 该音频在 MusicBrainz 数据库中没有对应的录音信息");
            log.warn("2. API Key 权限不足");
            log.warn("3. API 返回数据被压缩或截断");
            return result;
        }
        
        if (recordings.isArray() && recordings.size() > 0) {
            List<RecordingInfo> recordingList = new ArrayList<>();
            
            for (JsonNode recording : recordings) {
                RecordingInfo info = new RecordingInfo();
                info.setRecordingId(recording.path("id").asText());
                info.setTitle(recording.path("title").asText());
                
                // 解析艺术家
                JsonNode artists = recording.path("artists");
                if (artists.isArray() && artists.size() > 0) {
                    StringBuilder artistNames = new StringBuilder();
                    for (JsonNode artist : artists) {
                        if (artistNames.length() > 0) {
                            artistNames.append(", ");
                        }
                        artistNames.append(artist.path("name").asText());
                    }
                    info.setArtist(artistNames.toString());
                }
                
                // 解析专辑
                JsonNode releaseGroups = recording.path("releasegroups");
                if (releaseGroups.isArray() && releaseGroups.size() > 0) {
                    info.setAlbum(releaseGroups.get(0).path("title").asText());
                }
                
                recordingList.add(info);
            }
            
            result.setRecordings(recordingList);
        }
        
        int recordingCount = result.getRecordings() != null ? result.getRecordings().size() : 0;
        log.info("AcoustID 查询成功 - 匹配度: {}, 找到 {} 条录音信息",
            result.getScore(), recordingCount);
        
        return result;
    }
    
    /**
     * 完整的识别流程
     */
    public AcoustIdResult identifyAudioFile(File audioFile) throws IOException, InterruptedException {
        log.info("开始识别音频文件: {}", audioFile.getName());
        
        // 1. 生成音频指纹
        AudioFingerprint fingerprint = generateFingerprint(audioFile);
        
        // 2. 查询 AcoustID
        AcoustIdResult result = lookupByFingerprint(fingerprint);
        
        if (result.getRecordings() != null && !result.getRecordings().isEmpty()) {
            RecordingInfo bestMatch = result.getRecordings().get(0);
            log.info("识别结果: {} - {} ({})", 
                bestMatch.getArtist(), bestMatch.getTitle(), bestMatch.getAlbum());
        } else {
            log.warn("未能识别文件: {}", audioFile.getName());
        }
        
        return result;
    }
    
    /**
     * 检查 fpcalc 工具是否可用
     */
    public boolean isFpcalcAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(fpcalcPath, "-version");
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
    
    /**
     * 关闭客户端
     */
    public void close() throws IOException {
        httpClient.close();
    }
    
    /**
     * 音频指纹数据类
     */
    @Data
    public static class AudioFingerprint {
        private int duration; // 时长（秒）
        private String fingerprint; // 指纹字符串
    }
    
    /**
     * AcoustID 查询结果
     */
    @Data
    public static class AcoustIdResult {
        private String status;
        private double score;
        private String acoustId;
        private List<RecordingInfo> recordings;
    }
    
    /**
     * 录音信息
     */
    @Data
    public static class RecordingInfo {
        private String recordingId;
        private String title;
        private String artist;
        private String album;
    }
}