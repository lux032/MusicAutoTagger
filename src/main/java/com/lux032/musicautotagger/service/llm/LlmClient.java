package com.lux032.musicautotagger.service.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.lux032.musicautotagger.config.LlmEndpoint;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.util.OutboundUrlValidator;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 通用 LLM 调用客户端（阶段七 #21）
 *
 * 相比原来写在 {@code ArtistMatchingService} 里的实现，这里解决了四个问题：
 *   1. 协议硬编码  -> 交给 {@link LlmProvider} 适配（Anthropic / OpenAI 兼容）
 *   2. 结果不稳定  -> 默认 {@code temperature=0}
 *   3. max_tokens=100 不够写理由 -> 可配置，判定类任务默认 600
 *   4. 无重试退避  -> 对 429/5xx/网络异常做指数退避重试，然后再换下一个端点
 *
 * **本类无可变实例状态**，可以被多个服务并发共享
 * （原实现把候选列表存在实例字段 {@code lastCandidates} 上，并发调用会串数据）。
 */
@Slf4j
public class LlmClient {

    /** 429/5xx 之外不重试：4xx 基本是 key / 模型名 / 参数错误，重试没有意义 */
    private static final long BASE_BACKOFF_MILLIS = 800L;

    private final MusicConfig config;
    private volatile HttpClient httpClient;
    private final Gson gson = new Gson();

    public LlmClient(MusicConfig config) {
        this.config = config;
    }

    private HttpClient httpClient() {
        HttpClient client = this.httpClient;
        if (client == null) {
            synchronized (this) {
                client = this.httpClient;
                if (client == null) {
                    client = buildHttpClient(config);
                    this.httpClient = client;
                }
            }
        }
        return client;
    }

    /** 调用失败（所有端点、所有重试都用尽） */
    public static class LlmException extends Exception {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 一次成功调用的结果 */
    public static class LlmResponse {
        private final String text;
        private final String model;
        private final String provider;

        public LlmResponse(String text, String model, String provider) {
            this.text = text;
            this.model = model;
            this.provider = provider;
        }

        public String getText() {
            return text;
        }

        public String getModel() {
            return model;
        }

        public String getProvider() {
            return provider;
        }
    }

    /**
     * 是否配置了至少一组可用的 (apiKey, apiUrl, model)。
     * 三个列表按下标配对，因此以最短的那个为准。
     */
    public boolean isConfigured() {
        return endpointCount() > 0;
    }

    public int endpointCount() {
        return config.getActiveLlmEndpoints().size();
    }

    /**
     * 依次尝试所有配置的端点；每个端点内部按指数退避重试。
     *
     * @param maxTokens <= 0 时使用配置里的默认值
     */
    public LlmResponse complete(String systemPrompt, String userPrompt, int maxTokens) throws LlmException {
        List<LlmEndpoint> endpoints = config.getActiveLlmEndpoints();
        if (endpoints.isEmpty()) {
            throw new LlmException("llm.not.configured");
        }

        int tokens = maxTokens > 0 ? maxTokens : config.getLlmMaxTokens();
        int maxRetries = Math.max(0, config.getLlmMaxRetries());
        Exception lastError = null;

        for (int i = 0; i < endpoints.size(); i++) {
            LlmEndpoint endpoint = endpoints.get(i);
            String apiKey = endpoint.getApiKey();
            String apiUrl = endpoint.getApiUrl();
            String model = endpoint.getModel();
            // 协议来自供应商的显式配置，不再从 URL 猜
            LlmProvider provider = LlmProvider.forFormat(endpoint.getFormat());

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    String text = callOnce(provider, apiKey, apiUrl, model, systemPrompt, userPrompt, tokens);
                    return new LlmResponse(text, model, provider.name());
                } catch (RetryableException e) {
                    lastError = e;
                    if (attempt < maxRetries) {
                        long backoff = BASE_BACKOFF_MILLIS * (1L << attempt);
                        log.warn("LLM #{} ({}) 调用失败，{}ms 后重试 ({}/{}): {}",
                            i + 1, endpoint.label(), backoff, attempt + 1, maxRetries, e.getMessage());
                        // 退避等待被中断（关机 / 取消）时必须立即放弃，
                        // 否则恢复中断标志后仍会接着向外部 API 发下一次请求
                        sleepOrAbort(backoff);
                    } else {
                        log.warn("LLM #{} ({}) 重试用尽: {}", i + 1, endpoint.label(), e.getMessage());
                    }
                } catch (LlmException e) {
                    if ("interrupted".equals(e.getMessage()) || Thread.currentThread().isInterrupted()) {
                        throw e;
                    }
                    lastError = e;
                    log.warn("LLM #{} ({} / {} / {}) 调用失败且不可重试: {}",
                        i + 1, endpoint.label(), provider.name(), apiUrl, e.getMessage());
                    break;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new LlmException("interrupted", e);
                    }
                    // 非可重试错误（鉴权失败 / 模型名错误 / 响应结构不符）：直接换下一个端点
                    // 必须带上 URL 与协议：404 这类错误的响应体常常是空的，
                    // 只报「http 404」无法判断到底是路径写错还是模型名不存在
                    lastError = e;
                    log.warn("LLM #{} ({} / {} / {}) 调用失败且不可重试: {}",
                        i + 1, endpoint.label(), provider.name(), apiUrl, e.getMessage());
                    break;
                }
            }
        }

        throw new LlmException("llm.all.endpoints.failed: "
            + (lastError == null ? "unknown" : lastError.getMessage()), lastError);
    }

    // ==================== 内部实现 ====================

    private static class RetryableException extends Exception {
        RetryableException(String message) {
            super(message);
        }

        RetryableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private String callOnce(LlmProvider provider, String apiKey, String apiUrl, String model,
                            String systemPrompt, String userPrompt, int maxTokens)
            throws RetryableException, LlmException {

        try {
            OutboundUrlValidator.validateAndResolve(apiUrl, config.isLlmAllowPrivateEndpoints(), false, false);
        } catch (OutboundUrlValidator.ValidationException e) {
            throw new LlmException(e.getMessage(), e);
        }

        JsonObject body = provider.buildRequestBody(
            model, systemPrompt, userPrompt, maxTokens, config.getLlmTemperature());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(Math.max(5, config.getLlmTimeoutSeconds())))
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)));
        provider.applyAuthHeaders(builder, apiKey);

        int status;
        String responseBody;
        try {
            HttpResponse<InputStream> response = httpClient().send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            status = response.statusCode();
            try (InputStream in = response.body()) {
                responseBody = OutboundUrlValidator.readBoundedString(in, OutboundUrlValidator.DEFAULT_MAX_RESPONSE_BYTES);
            }
        } catch (java.io.IOException e) {
            throw new RetryableException("network: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted", e);
        }

        if (status == 429 || status >= 500) {
            throw new RetryableException(describeHttpError(status, apiUrl, provider, responseBody));
        }
        if (status != 200) {
            throw new LlmException(describeHttpError(status, apiUrl, provider, responseBody));
        }

        JsonObject json;
        try {
            json = gson.fromJson(responseBody, JsonObject.class);
        } catch (JsonSyntaxException e) {
            throw new RetryableException("响应不是合法 JSON: " + truncate(responseBody), e);
        }
        if (json == null) {
            throw new RetryableException("响应为空");
        }

        String text = provider.extractText(json);
        if (text == null || text.isEmpty()) {
            throw new LlmException("响应中没有可用文本（协议可能不匹配，可用 llm.provider 显式指定）: "
                + truncate(responseBody));
        }
        return text;
    }

    /** 构造带有统一超时、代理及禁止重定向策略的 HttpClient.Builder（委托给共享 HttpClientFactory） */
    public static HttpClient.Builder httpClientBuilder(MusicConfig config) {
        if (com.lux032.musicautotagger.util.HttpClientFactory.isProxyConfigured(config)) {
            log.info("LLM 请求将走代理: {}:{}", config.getProxyHost(), config.getProxyPort());
        }
        return com.lux032.musicautotagger.util.HttpClientFactory.jdkHttpClientBuilder(config);
    }

    /** 供 {@link TavilyWebSearchClient} 等外部 HTTP 调用复用同一套代理设置 */
    public static HttpClient buildHttpClient(MusicConfig config) {
        return httpClientBuilder(config).build();
    }

    private static void sleepOrAbort(long millis) throws LlmException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted", e);
        }
    }

    /**
     * 拼出带定位信息的错误描述。
     *
     * 网关 / 中转站返回 404 时经常不带任何响应体，此时原先的「http 404: 」
     * 完全无法诊断。这里把 URL、协议写进去，并对最常见的路径配错给出直接提示。
     */
    private static String describeHttpError(int status, String apiUrl, LlmProvider provider, String body) {
        StringBuilder sb = new StringBuilder("http ").append(status)
            .append(" @ ").append(apiUrl)
            .append(" [").append(provider.name()).append("]");
        if (status == 404) {
            sb.append(" （路径或模型不存在：Anthropic 协议应为 /v1/messages，")
              .append("OpenAI 兼容应为 /v1/chat/completions；")
              .append("注意 llm.apiUrl 的路径形态会决定自动选择的协议，两者不匹配时中转站多半直接返回 404）");
        } else if (status == 401 || status == 403) {
            sb.append("（鉴权失败：检查 llm.apiKey 与该端点是否匹配）");
        }
        String detail = truncate(body);
        if (detail != null && !detail.isBlank()) {
            sb.append(": ").append(detail);
        } else {
            sb.append("（响应体为空）");
        }
        return sb.toString();
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
