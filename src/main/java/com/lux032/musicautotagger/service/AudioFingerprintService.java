package com.lux032.musicautotagger.service;

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
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.util.I18nUtil;
import com.lux032.musicautotagger.util.ProcessRunner;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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
    private final boolean allowTestSubmitEndpoint;
    
    // fpcalc 工具路径（需要用户安装 Chromaprint）
    private String fpcalcPath = "fpcalc";
    
    // 重试配置
    private static final int MAX_RETRIES = 3; // 最大重试次数
    private static final long RETRY_DELAY_MS = 5000; // 重试间隔5秒

    // fpcalc 子进程超时配置(防止损坏文件或挂起的网络存储卡死整条处理流水线)
    private static final long FPCALC_TIMEOUT_SECONDS = 120;        // 指纹/时长提取超时
    private static final long FPCALC_VERSION_TIMEOUT_SECONDS = 10; // 可用性探测超时
    
    public AudioFingerprintService(MusicConfig config) {
        this(config, false);
    }

    AudioFingerprintService(MusicConfig config, boolean allowTestSubmitEndpoint) {
        this.config = config;
        this.httpClient = createHttpClient(config);
        this.objectMapper = new ObjectMapper();
        this.allowTestSubmitEndpoint = allowTestSubmitEndpoint;
    }
    
    /**
     * 创建 HttpClient,支持代理与认证配置（委托给共享 HttpClientFactory）
     */
    private CloseableHttpClient createHttpClient(MusicConfig config) {
        if (com.lux032.musicautotagger.util.HttpClientFactory.isProxyConfigured(config)) {
            log.info(I18nUtil.getMessage("proxy.acoustid.enabled", config.getProxyHost(), config.getProxyPort()));
        } else if (config != null && config.isProxyEnabled()) {
            log.warn(I18nUtil.getMessage("proxy.enabled.no.host"));
        }
        return com.lux032.musicautotagger.util.HttpClientFactory.buildApacheHttpClient(
            config, Timeout.ofSeconds(30), null, Timeout.ofSeconds(30));
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
        String jsonOutput = runFpcalc(FPCALC_TIMEOUT_SECONDS,
            fpcalcPath,
            "-json",
            audioFile.getAbsolutePath()
        );
        
        log.debug("fpcalc 输出: {}", jsonOutput);
        
        JsonNode root = objectMapper.readTree(jsonOutput);
        
        AudioFingerprint fingerprint = new AudioFingerprint();
        fingerprint.setDuration(root.path("duration").asInt());
        fingerprint.setPreciseDuration(root.path("duration").isNumber() ? root.path("duration").asDouble() : null);
        fingerprint.setFingerprint(root.path("fingerprint").asText());
        
        log.info("成功生成音频指纹 - 文件: {}, 时长: {}秒",
            audioFile.getName(), fingerprint.getDuration());
        
        return fingerprint;
    }
    
    /**
     * 仅提取音频文件时长(不生成完整指纹,速度更快)
     * 用于批量获取文件夹内所有文件的时长序列
     */
    public int extractDuration(File audioFile) throws IOException, InterruptedException {
        if (!audioFile.exists()) {
            throw new IOException("音频文件不存在: " + audioFile.getAbsolutePath());
        }
        
        // 执行 fpcalc 命令(仅获取时长,不生成指纹)
        String jsonOutput = runFpcalc(FPCALC_TIMEOUT_SECONDS,
            fpcalcPath,
            "-json",
            "-length", "0",  // 不计算指纹,只获取元数据
            audioFile.getAbsolutePath()
        );
        
        JsonNode root = objectMapper.readTree(jsonOutput);
        
        int duration = root.path("duration").asInt();
        log.debug("提取音频时长 - 文件: {}, 时长: {}秒", audioFile.getName(), duration);
        
        return duration;
    }
    
    /**
     * 批量提取文件夹内所有音频文件的时长序列
     * @param audioFiles 音频文件列表
     * @return 时长列表(秒)
     */
    public List<Integer> extractDurationSequence(List<File> audioFiles) {
        List<Integer> durations = new ArrayList<>();

        log.info("开始批量提取时长序列 - 文件数: {}", audioFiles.size());

        for (File audioFile : audioFiles) {
            try {
                int duration = extractDuration(audioFile);
                if (duration <= 0) {
                    log.warn("提取到无效时长，整段序列作废以避免曲序错位 ({}/{}): {}",
                        durations.size() + 1, audioFiles.size(), audioFile.getName());
                    return new ArrayList<>();
                }
                durations.add(duration);
            } catch (Exception e) {
                // 不能 continue：静默少一个元素会让后续所有时长与官方曲序错位，产生高风险误匹配。
                log.warn("提取时长失败，整段序列作废 ({}/{}): {} - {}",
                    durations.size() + 1, audioFiles.size(), audioFile.getName(), e.getMessage());
                return new ArrayList<>();
            }
        }

        // 走到这里说明全部成功（任何失败都已提前 return）
        log.info("完成时长序列提取 - {} 个文件全部成功", durations.size());

        return durations;
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
                // 使用空格连接多个 meta 参数（URL编码后空格会变成+），确保返回完整的录音信息
                params.add(new BasicNameValuePair("meta", "recordings releasegroups compress"));
                
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
     * 通过 AcoustID /v2/submit 批量提交本地音频 Chromaprint 声纹与官方 MusicBrainz Recording MBID。
     * 每批最多提交 50 首，单次读取不可变凭据/端点快照，禁止重定向，且绝不透传或记录远端原文错误信息。
     */
    public AcoustIdSubmitResult submitFingerprints(List<AcoustIdSubmissionItem> items) throws IOException, InterruptedException {
        return submitFingerprints(items, null);
    }

    public AcoustIdSubmitResult submitFingerprints(List<AcoustIdSubmissionItem> items,
                                                   java.util.function.Consumer<List<String>> onBatchSubmitted)
            throws IOException, InterruptedException {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("acoustid.submit.empty");
        }
        MusicConfig.AcoustIdSettings snap = config == null
            ? new MusicConfig.AcoustIdSettings(null, null, null, null)
            : config.getAcoustIdSettings();
        String clientKey = snap.apiKey() == null ? "" : snap.apiKey().trim();
        if (clientKey.isEmpty()) {
            throw new IllegalStateException("acoustid.client.key.missing");
        }
        String userKey = snap.userApiKey() == null ? "" : snap.userApiKey().trim();
        if (userKey.isEmpty()) {
            throw new IllegalStateException("acoustid.user.key.missing");
        }
        String submitUrl = (snap.submitApiUrl() == null || snap.submitApiUrl().isBlank())
            ? "https://api.acoustid.org/v2/submit"
            : snap.submitApiUrl().trim();
        validateSubmitEndpoint(submitUrl);

        List<AcoustIdSubmissionItem> valid = new ArrayList<>();
        for (AcoustIdSubmissionItem item : items) {
            if (item == null || item.durationSeconds() <= 0
                    || item.fingerprint() == null || item.fingerprint().isBlank()) {
                continue;
            }
            String validMbid = MusicBrainzArtistCache.normalizeValidMbid(item.recordingMbid());
            if (validMbid == null) {
                continue;
            }
            valid.add(new AcoustIdSubmissionItem(item.durationSeconds(), item.fingerprint().trim(), validMbid));
        }
        if (valid.isEmpty()) {
            throw new IllegalArgumentException("acoustid.submit.no.valid.tracks");
        }

        RequestConfig noRedirectConfig = RequestConfig.custom()
            .setRedirectsEnabled(false)
            .build();

        final int batchSize = 50;
        int submittedTotal = 0;
        List<String> submissionIds = new ArrayList<>();
        for (int start = 0; start < valid.size(); start += batchSize) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("cancelled");
            }
            List<AcoustIdSubmissionItem> batch = valid.subList(start, Math.min(valid.size(), start + batchSize));
            List<NameValuePair> params = new ArrayList<>(2 + batch.size() * 3);
            params.add(new BasicNameValuePair("client", clientKey));
            params.add(new BasicNameValuePair("user", userKey));
            for (int i = 0; i < batch.size(); i++) {
                AcoustIdSubmissionItem track = batch.get(i);
                params.add(new BasicNameValuePair("duration." + i, String.valueOf(track.durationSeconds())));
                params.add(new BasicNameValuePair("fingerprint." + i, track.fingerprint()));
                params.add(new BasicNameValuePair("mbid." + i, track.recordingMbid()));
            }
            HttpPost httpPost = new HttpPost(submitUrl);
            httpPost.setConfig(noRedirectConfig);
            httpPost.setEntity(new UrlEncodedFormEntity(params, java.nio.charset.StandardCharsets.UTF_8));
            httpPost.setHeader("User-Agent", config.getUserAgent() != null ? config.getUserAgent() : "MusicAutoTagger/1.0");
            httpPost.setHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                int statusCode = response.getCode();
                if (statusCode >= 300 && statusCode < 400) {
                    log.warn("AcoustID /v2/submit 拒绝重定向响应: status={}", statusCode);
                    throw new AcoustIdSubmitException("acoustid.submit.redirect.blocked", 502, submittedTotal);
                }
                String body = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), java.nio.charset.StandardCharsets.UTF_8);
                int apiErrorCode = extractAcoustIdErrorCode(body);
                if (statusCode != 200) {
                    String mapped = mapAcoustIdErrorCode(apiErrorCode, statusCode);
                    log.warn("AcoustID /v2/submit 请求失败: status={}, code={}, mapped={}", statusCode, apiErrorCode, mapped);
                    throw new AcoustIdSubmitException(mapped, statusCode == 429 ? 429 : 502, submittedTotal);
                }
                JsonNode root = objectMapper.readTree(body);
                String status = root.path("status").asText("");
                if (!"ok".equalsIgnoreCase(status)) {
                    String mapped = mapAcoustIdErrorCode(apiErrorCode, statusCode);
                    log.warn("AcoustID /v2/submit 返回错误状态: code={}, mapped={}", apiErrorCode, mapped);
                    throw new AcoustIdSubmitException(mapped, 502, submittedTotal);
                }
                JsonNode subs = root.path("submissions");
                if (subs.isArray()) {
                    for (JsonNode s : subs) {
                        String sid = s.path("id").asText("");
                        if (!sid.isEmpty()) {
                            submissionIds.add(sid);
                        }
                    }
                }
                submittedTotal += batch.size();
                if (onBatchSubmitted != null) {
                    List<String> batchMbids = batch.stream().map(AcoustIdSubmissionItem::recordingMbid).toList();
                    onBatchSubmitted.accept(batchMbids);
                }
            } catch (AcoustIdSubmitException e) {
                throw e;
            } catch (ParseException e) {
                log.warn("解析 AcoustID /v2/submit 响应失败");
                throw new AcoustIdSubmitException("acoustid.submit.failed", 502, submittedTotal);
            } catch (IOException e) {
                log.warn("AcoustID /v2/submit 网络异常: {}", e.getClass().getSimpleName());
                throw new AcoustIdSubmitException("acoustid.submit.failed", 502, submittedTotal);
            }
        }
        log.info("AcoustID /v2/submit 提交成功: {} 首曲目", submittedTotal);
        return new AcoustIdSubmitResult(true, submittedTotal, List.copyOf(submissionIds));
    }

    private void validateSubmitEndpoint(String submitUrl) throws AcoustIdSubmitException {
        if (allowTestSubmitEndpoint) {
            return;
        }
        try {
            java.net.URI uri = java.net.URI.create(submitUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            int port = uri.getPort();
            if (!"https".equals(scheme) || uri.getUserInfo() != null
                    || (port != -1 && port != 443)
                    || (!"api.acoustid.org".equals(host) && !host.endsWith(".acoustid.org"))) {
                throw new AcoustIdSubmitException("acoustid.submit.endpoint.invalid", 400, 0);
            }
            if (!com.lux032.musicautotagger.util.HttpClientFactory.isProxyConfigured(config)) {
                com.lux032.musicautotagger.util.OutboundUrlValidator.validateAndResolve(submitUrl, false, false, true);
            }
        } catch (AcoustIdSubmitException e) {
            throw e;
        } catch (Exception e) {
            throw new AcoustIdSubmitException("acoustid.submit.endpoint.invalid", 400, 0);
        }
    }

    private int extractAcoustIdErrorCode(String body) {
        if (body == null || body.isBlank()) {
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("error").path("code").asInt(0);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String mapAcoustIdErrorCode(int apiCode, int httpStatus) {
        return switch (apiCode) {
            case 4 -> "acoustid.error.invalid.client.key";
            case 6 -> "acoustid.error.invalid.user.key";
            case 5 -> "acoustid.error.upstream";
            case 8 -> "acoustid.submit.failed";
            default -> {
                if (httpStatus == 429) yield "acoustid.error.rate.limited";
                if (httpStatus >= 500) yield "acoustid.error.upstream";
                yield "acoustid.submit.failed";
            }
        };
    }

    /** 携带固定安全错误码（绝不包含远端回显明文）的 AcoustID 提交异常。 */
    public static class AcoustIdSubmitException extends IOException {
        private final String errorCode;
        private final int httpStatus;
        private final int submittedBeforeFailure;

        public AcoustIdSubmitException(String errorCode, int httpStatus, int submittedBeforeFailure) {
            super(errorCode);
            this.errorCode = errorCode;
            this.httpStatus = httpStatus;
            this.submittedBeforeFailure = submittedBeforeFailure;
        }

        public String getErrorCode() { return errorCode; }
        public int getHttpStatus() { return httpStatus; }
        public int getSubmittedBeforeFailure() { return submittedBeforeFailure; }
    }

    /** 单条待提交 AcoustID 的声纹记录。 */
    public record AcoustIdSubmissionItem(int durationSeconds, String fingerprint, String recordingMbid) { }

    /** AcoustID /v2/submit 批量提交结果。 */
    public record AcoustIdSubmitResult(boolean ok, int submittedCount, List<String> submissionIds) { }

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
            int incompleteCount = 0;
            
            for (JsonNode recording : recordings) {
                // 获取基本信息
                String recordingId = recording.path("id").asText();
                
                // 跳过没有 recording ID 的记录
                if (recordingId == null || recordingId.isEmpty()) {
                    log.debug("跳过没有 ID 的录音记录");
                    continue;
                }
                
                String title = recording.path("title").asText();
                
                // 解析艺术家
                String artistName = null;
                JsonNode artists = recording.path("artists");
                if (artists.isArray() && artists.size() > 0) {
                    StringBuilder artistNames = new StringBuilder();
                    for (JsonNode artist : artists) {
                        if (artistNames.length() > 0) {
                            artistNames.append(", ");
                        }
                        artistNames.append(artist.path("name").asText());
                    }
                    artistName = artistNames.toString();
                }
                
                RecordingInfo info = new RecordingInfo();
                info.setRecordingId(recordingId);
                
                // 检查是否有完整的 title 和 artist 信息
                boolean hasCompleteInfo = (title != null && !title.isEmpty() &&
                                          artistName != null && !artistName.isEmpty());
                
                if (hasCompleteInfo) {
                    info.setTitle(title);
                    info.setArtist(artistName);
                } else {
                    // 即使缺少 title/artist，也保留 recording ID
                    // 后续可以通过 MusicBrainz API 使用 recording ID 查询完整信息
                    incompleteCount++;
                    log.debug("录音信息不完整但保留 ID: id={}, title={}, artist={}",
                        recordingId, title, artistName);
                    // 设置空值或 null，让后续处理知道需要从 MusicBrainz 获取
                    info.setTitle(title != null && !title.isEmpty() ? title : null);
                    info.setArtist(artistName != null && !artistName.isEmpty() ? artistName : null);
                }
                
                // 解析专辑
                JsonNode releaseGroups = recording.path("releasegroups");
                if (releaseGroups.isArray() && releaseGroups.size() > 0) {
                    info.setAlbum(releaseGroups.get(0).path("title").asText());

                    List<ReleaseGroupInfo> releaseGroupList = new ArrayList<>();
                    for (JsonNode rg : releaseGroups) {
                        ReleaseGroupInfo rgInfo = new ReleaseGroupInfo();
                        rgInfo.setId(rg.path("id").asText());
                        rgInfo.setTitle(rg.path("title").asText());
                        releaseGroupList.add(rgInfo);
                    }
                    info.setReleaseGroups(releaseGroupList);
                }

                recordingList.add(info);
            }
            
            result.setRecordings(recordingList);
            
            // 记录信息不完整但已保留的录音数量
            if (incompleteCount > 0) {
                log.info("AcoustID 返回了 {} 条录音，其中 {} 条缺少 title/artist 信息（已保留 recording ID 用于 MusicBrainz 查询）",
                    recordings.size(), incompleteCount);
            }
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
        
        // 3. 保存文件时长到结果中（用于后续时长匹配）
        result.setDuration(fingerprint.getDuration());
        result.setPreciseDuration(fingerprint.getPreciseDuration());
        
        if (result.getRecordings() != null && !result.getRecordings().isEmpty()) {
            RecordingInfo bestMatch = result.getRecordings().get(0);
            String artist = bestMatch.getArtist();
            String title = bestMatch.getTitle();
            String album = bestMatch.getAlbum();
            // 处理可能为 null 或空的情况
            String displayArtist = (artist != null && !artist.isEmpty()) ? artist : "(待获取)";
            String displayTitle = (title != null && !title.isEmpty()) ? title : "(待获取)";
            String displayAlbum = (album != null && !album.isEmpty()) ? album : "(待获取)";
            log.info("识别结果: {} - {} ({})", displayArtist, displayTitle, displayAlbum);
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
            runFpcalc(FPCALC_VERSION_TIMEOUT_SECONDS, fpcalcPath, "-version");
            return true;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 执行 fpcalc 子进程并返回其输出
     * 超时与输出消费的处理见 {@link ProcessRunner}
     */
    private String runFpcalc(long timeoutSeconds, String... command) throws IOException, InterruptedException {
        return ProcessRunner.runSeparateStdout(Arrays.asList(command), timeoutSeconds);
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
        private Double preciseDuration; // Preserve fpcalc JSON precision; not assumed accurate for lyrics.
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
        private int duration; // 音频文件时长（秒），用于后续时长匹配
        private Double preciseDuration;
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
        private List<ReleaseGroupInfo> releaseGroups;
    }

    @Data
    public static class ReleaseGroupInfo {
        private String id;
        private String title;
    }
}
