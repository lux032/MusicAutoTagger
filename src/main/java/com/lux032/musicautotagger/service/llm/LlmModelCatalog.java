package com.lux032.musicautotagger.service.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.util.OutboundUrlValidator;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 从供应商拉取可用模型列表，供面板下拉选择。
 *
 * 顺带承担连通性自检：能拉到模型，说明 URL、Key、协议三者都对；这正是原来那种
 * 「手打模型名 + 猜协议」配置方式最缺的反馈。
 */
@Slf4j
public class LlmModelCatalog implements AutoCloseable {

    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final long MAX_MODELS_RESPONSE_BYTES = 2L * 1024 * 1024;

    private final MusicConfig config;
    private final ThreadLocal<Map<String, InetAddress[]>> requestPins = new ThreadLocal<>();
    private final CloseableHttpClient pinnedClient;
    private volatile HttpClient proxyClient;

    public LlmModelCatalog(MusicConfig config) {
        this.config = config;
        DnsResolver resolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                Map<String, InetAddress[]> pins = requestPins.get();
                InetAddress[] addresses = pins == null ? null : pins.get(host.toLowerCase(Locale.ROOT));
                if (addresses == null) {
                    throw new UnknownHostException("Host not prevalidated");
                }
                return addresses.clone();
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver).build();
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofSeconds(5))
            .setConnectTimeout(Timeout.ofSeconds(10))
            .setResponseTimeout(Timeout.ofSeconds(15))
            .build();
        this.pinnedClient = HttpClients.custom()
            .setConnectionManager(manager)
            .setDefaultRequestConfig(requestConfig)
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .build();
    }

    @Override
    public void close() {
        try {
            pinnedClient.close();
        } catch (IOException e) {
            log.debug("关闭 LlmModelCatalog HTTP 客户端失败: {}", e.getMessage());
        }
    }

    private HttpClient proxyHttpClient() {
        HttpClient client = this.proxyClient;
        if (client == null) {
            synchronized (this) {
                client = this.proxyClient;
                if (client == null) {
                    client = LlmClient.httpClientBuilder(config).build();
                    this.proxyClient = client;
                }
            }
        }
        return client;
    }

    public static class CatalogException extends Exception {
        public CatalogException(String message) {
            super(message);
        }
    }

    /** 拉取结果：除模型列表外，还告诉前端到底是哪个地址生效了 */
    public static class Result {
        public final List<String> models;
        public final String modelsUrl;
        /** 由生效的 models 地址反推出的调用地址，供前端建议用户回填 */
        public final String suggestedApiUrl;

        Result(List<String> models, String modelsUrl, String suggestedApiUrl) {
            this.models = models;
            this.modelsUrl = modelsUrl;
            this.suggestedApiUrl = suggestedApiUrl;
        }
    }

    /**
     * @param apiUrl 用户填写的请求地址，允许只填到域名或基址
     * @param format openai | anthropic
     */
    public Result fetch(String apiUrl, String apiKey, String format) throws CatalogException {
        if (apiUrl == null || apiUrl.isBlank()) {
            throw new CatalogException("llm.models.url.required");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new CatalogException("llm.models.key.required");
        }

        boolean anthropic = "anthropic".equalsIgnoreCase(format);
        List<String> attempts = new ArrayList<>();
        for (String modelsUrl : candidateModelsUrls(apiUrl)) {
            OutboundUrlValidator.ValidatedTarget target = assertOutboundAllowed(modelsUrl);
            try {
                List<String> models = request(target, modelsUrl, apiKey, anthropic);
                return new Result(models, modelsUrl, toApiUrl(modelsUrl, anthropic));
            } catch (CatalogException e) {
                attempts.add(e.getMessage());
            }
        }
        // 逐条列出试过的地址：反代的路径前缀千奇百怪，只报最后一次失败会误导用户
        throw new CatalogException(String.join(" | ", attempts));
    }

    private List<String> request(OutboundUrlValidator.ValidatedTarget target,
                                 String modelsUrl, String apiKey, boolean anthropic) throws CatalogException {
        int statusCode;
        String responseBody;
        boolean usePinned = !config.isLlmAllowPrivateEndpoints()
            && !(config.isProxyEnabled() && config.getProxyHost() != null && !config.getProxyHost().isBlank());
        if (usePinned) {
            requestPins.set(Map.of(target.normalizedHost(), target.addresses()));
            try {
                HttpGet httpGet = new HttpGet(target.uri());
                if (anthropic) {
                    httpGet.setHeader("x-api-key", apiKey);
                    httpGet.setHeader("anthropic-version", "2023-06-01");
                } else {
                    httpGet.setHeader("Authorization", "Bearer " + apiKey);
                }
                try (CloseableHttpResponse response = pinnedClient.execute(httpGet)) {
                    statusCode = response.getCode();
                    if (response.getEntity() != null) {
                        try (InputStream in = response.getEntity().getContent()) {
                            responseBody = OutboundUrlValidator.readBoundedString(in, MAX_MODELS_RESPONSE_BYTES);
                        }
                    } else {
                        responseBody = "";
                    }
                }
            } catch (IOException e) {
                throw new CatalogException("llm.models.network @ " + modelsUrl + ": " + e.getMessage());
            } finally {
                requestPins.remove();
            }
        } else {
            HttpRequest.Builder builder = HttpRequest.newBuilder(target.uri())
                .timeout(TIMEOUT)
                .GET();
            if (anthropic) {
                builder.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
            } else {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            try {
                HttpResponse<InputStream> response = proxyHttpClient().send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                statusCode = response.statusCode();
                try (InputStream in = response.body()) {
                    responseBody = OutboundUrlValidator.readBoundedString(in, MAX_MODELS_RESPONSE_BYTES);
                }
            } catch (IOException e) {
                throw new CatalogException("llm.models.network @ " + modelsUrl + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CatalogException("llm.models.interrupted");
            }
        }

        if (statusCode != 200) {
            throw new CatalogException("llm.models.http." + statusCode
                + " @ " + modelsUrl + ": " + truncate(responseBody));
        }
        List<String> models = parse(responseBody);
        if (models.isEmpty()) {
            throw new CatalogException("llm.models.empty @ " + modelsUrl);
        }
        return models;
    }

    /**
     * 列出值得一试的模型列表地址。
     *
     * 用户常常只填到域名（反代场景尤其如此），此时无法判断 API 挂在根上还是 /v1 下，
     * 因此按「路径推导 -> {base}/v1/models -> {base}/models」依次尝试。
     */
    static List<String> candidateModelsUrls(String apiUrl) {
        List<String> candidates = new ArrayList<>();
        String derived = toModelsUrl(apiUrl);
        candidates.add(derived);

        String root = rootOf(apiUrl);
        for (String candidate : List.of(root + "/v1/models", root + "/models")) {
            if (!candidates.contains(candidate)) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private static String rootOf(String apiUrl) {
        try {
            URI uri = URI.create(apiUrl.trim());
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme();
            String authority = uri.getAuthority();
            if (authority == null || authority.isBlank()) {
                return trimSlash(apiUrl.trim());
            }
            return scheme + "://" + authority;
        } catch (Exception e) {
            return trimSlash(apiUrl.trim());
        }
    }

    /** 由生效的 models 地址反推调用地址，供前端建议用户把 API URL 补全 */
    static String toApiUrl(String modelsUrl, boolean anthropic) {
        if (!modelsUrl.endsWith("/models")) {
            return null;
        }
        String base = modelsUrl.substring(0, modelsUrl.length() - "/models".length());
        return base + (anthropic ? "/messages" : "/chat/completions");
    }

    /**
     * 由请求地址推导模型列表地址。
     *
     * 用户填的是完整的调用路径，而模型列表固定在同级的 /models 上，
     * 所以这里把已知的调用后缀替换掉，其余情况退化为「同级目录 + models」。
     */
    static String toModelsUrl(String apiUrl) {
        String url = trimSlash(apiUrl.trim());
        String lower = url.toLowerCase(Locale.ROOT);
        for (String suffix : List.of("/chat/completions", "/completions", "/messages", "/responses")) {
            if (lower.endsWith(suffix)) {
                return url.substring(0, url.length() - suffix.length()) + "/models";
            }
        }
        if (lower.endsWith("/models")) {
            return url;
        }
        int lastSlash = url.lastIndexOf('/');
        // 只剩协议部分（https://host）时直接追加，避免把 host 也切掉
        if (lastSlash <= url.indexOf("//") + 1) {
            return url + "/models";
        }
        return url.substring(0, lastSlash) + "/models";
    }

    private static String trimSlash(String url) {
        String result = url;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** 兼容三种常见返回形态：{data:[...]}、{models:[...]}、裸数组 */
    private List<String> parse(String body) throws CatalogException {
        Set<String> ids = new LinkedHashSet<>();
        try {
            JsonElement root = GSON.fromJson(body, JsonElement.class);
            JsonArray array = null;
            if (root != null && root.isJsonArray()) {
                array = root.getAsJsonArray();
            } else if (root != null && root.isJsonObject()) {
                JsonObject object = root.getAsJsonObject();
                if (object.has("data") && object.get("data").isJsonArray()) {
                    array = object.getAsJsonArray("data");
                } else if (object.has("models") && object.get("models").isJsonArray()) {
                    array = object.getAsJsonArray("models");
                }
            }
            if (array == null) {
                throw new CatalogException("llm.models.response.unrecognized: " + truncate(body));
            }
            for (JsonElement element : array) {
                if (element.isJsonPrimitive()) {
                    ids.add(element.getAsString());
                } else if (element.isJsonObject()) {
                    JsonObject item = element.getAsJsonObject();
                    for (String key : List.of("id", "model", "name")) {
                        if (item.has(key) && item.get(key).isJsonPrimitive()) {
                            ids.add(item.get(key).getAsString());
                            break;
                        }
                    }
                }
            }
        } catch (CatalogException e) {
            throw e;
        } catch (Exception e) {
            throw new CatalogException("llm.models.response.invalid: " + truncate(body));
        }
        return new ArrayList<>(ids);
    }

    /**
     * 出站地址校验。
     *
     * 「拉取模型」会让服务端去请求用户填写的任意地址，等于把一个登录会话升级成
     * 服务端请求伪造（SSRF）原语：可以拿它探测容器内网、访问云厂商元数据服务
     * （169.254.169.254）等。默认只放行公网 https 地址；自建 Ollama / vLLM 这类
     * 内网端点需要显式打开 llm.allowPrivateEndpoints 自行承担风险。
     */
    private OutboundUrlValidator.ValidatedTarget assertOutboundAllowed(String url) throws CatalogException {
        try {
            return OutboundUrlValidator.validateAndResolve(
                url, config.isLlmAllowPrivateEndpoints(), false, false);
        } catch (OutboundUrlValidator.ValidationException e) {
            throw new CatalogException(e.getMessage());
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
