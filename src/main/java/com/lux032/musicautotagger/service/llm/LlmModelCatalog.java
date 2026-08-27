package com.lux032.musicautotagger.service.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.MusicConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
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
import java.util.Set;

/**
 * 从供应商拉取可用模型列表，供面板下拉选择。
 *
 * 顺带承担连通性自检：能拉到模型，说明 URL、Key、协议三者都对；这正是原来那种
 * 「手打模型名 + 猜协议」配置方式最缺的反馈。
 */
@Slf4j
public class LlmModelCatalog {

    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final MusicConfig config;

    public LlmModelCatalog(MusicConfig config) {
        this.config = config;
    }

    public static class CatalogException extends Exception {
        public CatalogException(String message) {
            super(message);
        }
    }

    /**
     * @param apiUrl 用户填写的完整请求地址（如 https://host/v1/chat/completions）
     * @param format openai | anthropic
     */
    public List<String> fetch(String apiUrl, String apiKey, String format) throws CatalogException {
        if (apiUrl == null || apiUrl.isBlank()) {
            throw new CatalogException("llm.models.url.required");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new CatalogException("llm.models.key.required");
        }

        String modelsUrl = toModelsUrl(apiUrl);
        assertOutboundAllowed(modelsUrl);

        boolean anthropic = "anthropic".equalsIgnoreCase(format);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(modelsUrl))
            .timeout(TIMEOUT)
            .GET();
        if (anthropic) {
            builder.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
        } else {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response;
        try {
            // 不跟随跳转：跳转是绕过上面出站白名单检查的经典手法
            HttpClient http = LlmClient.buildHttpClient(config).newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new CatalogException("llm.models.network: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CatalogException("llm.models.interrupted");
        }

        if (response.statusCode() != 200) {
            throw new CatalogException("llm.models.http." + response.statusCode()
                + " @ " + modelsUrl + ": " + truncate(response.body()));
        }
        List<String> models = parse(response.body());
        if (models.isEmpty()) {
            throw new CatalogException("llm.models.empty @ " + modelsUrl);
        }
        return models;
    }

    /**
     * 由请求地址推导模型列表地址。
     *
     * 用户填的是完整的调用路径，而模型列表固定在同级的 /models 上，
     * 所以这里把已知的调用后缀替换掉，其余情况退化为「同级目录 + models」。
     */
    static String toModelsUrl(String apiUrl) {
        String url = apiUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
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
    private void assertOutboundAllowed(String url) throws CatalogException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new CatalogException("llm.models.url.invalid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new CatalogException("llm.models.url.invalid");
        }
        if (config.isLlmAllowPrivateEndpoints()) {
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new CatalogException("llm.models.url.scheme.invalid");
            }
            return;
        }
        if (!"https".equals(scheme)) {
            throw new CatalogException("llm.models.url.https.required");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                    throw new CatalogException("llm.models.url.private.blocked: " + host
                        + " -> " + address.getHostAddress());
                }
            }
        } catch (UnknownHostException e) {
            throw new CatalogException("llm.models.url.unresolvable: " + host);
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
