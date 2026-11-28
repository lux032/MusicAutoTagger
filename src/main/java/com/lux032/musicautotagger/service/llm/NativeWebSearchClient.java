package com.lux032.musicautotagger.service.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.LlmEndpoint;
import com.lux032.musicautotagger.config.MusicConfig;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** OpenAI Responses / Anthropic Messages 原生 Web Search 适配器。 */
@Slf4j
public class NativeWebSearchClient implements WebSearchClient {
    private final MusicConfig config;
    private volatile HttpClient http;
    private final Gson gson = new Gson();

    public NativeWebSearchClient(MusicConfig config) { this.config = config; }

    private HttpClient http() {
        HttpClient client = this.http;
        if (client == null) {
            synchronized (this) {
                client = this.http;
                if (client == null) {
                    client = LlmClient.buildHttpClient(config);
                    this.http = client;
                }
            }
        }
        return client;
    }

    @Override
    public String name() { return "native"; }

    @Override
    public boolean hasEnabledEndpoint() {
        return !searchEndpoints().isEmpty();
    }

    /** 只取勾选了「参与原生联网搜索」的模型 */
    private List<LlmEndpoint> searchEndpoints() {
        List<LlmEndpoint> all = config.getActiveLlmEndpoints();
        List<LlmEndpoint> enabled = new ArrayList<>();
        for (LlmEndpoint endpoint : all) if (endpoint.isWebSearch()) enabled.add(endpoint);
        return enabled;
    }

    @Override
    public SearchResponse search(String systemPrompt, String userPrompt) throws LlmClient.LlmException {
        return search(systemPrompt, userPrompt, null);
    }

    @Override
    public SearchResponse search(String systemPrompt, String userPrompt, ProgressListener progress)
            throws LlmClient.LlmException {
        Exception last = null;
        List<LlmEndpoint> endpoints = searchEndpoints();
        int totalEndpoints = endpoints.size();
        for (int i = 0; i < totalEndpoints; i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new LlmClient.LlmException("interrupted");
            }
            LlmEndpoint endpoint = endpoints.get(i);
            String url = endpoint.getApiUrl();
            String key = endpoint.getApiKey();
            String model = endpoint.getModel();
            // 协议来自供应商的显式配置，不再逐个端点猜 URL 形态
            String provider = endpoint.getFormat();
            int maxAttempts = Math.max(0, config.getLlmMaxRetries()) + 1;
            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new LlmClient.LlmException("interrupted");
                }
                notifyProgress(progress, attempt == 0 ? "NATIVE_SEARCH" : "NATIVE_RETRY",
                    i + 1, totalEndpoints, attempt + 1, model);
                try {
                    return "anthropic".equals(provider)
                        ? callAnthropic(i, url, key, model, systemPrompt, userPrompt)
                        : callOpenAi(i, url, key, model, systemPrompt, userPrompt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new LlmClient.LlmException("interrupted", interrupted);
                } catch (Retryable e) {
                    last = e;
                    if (attempt < config.getLlmMaxRetries()) {
                        try { Thread.sleep(800L * (1L << attempt)); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new LlmClient.LlmException("interrupted", interrupted);
                        }
                    }
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new LlmClient.LlmException("interrupted", e);
                    }
                    last = e;
                    log.warn("联网搜索端点 #{} 不可用: {}", i + 1, e.getMessage());
                    break;
                }
            }
        }
        throw new LlmClient.LlmException("llm.web.search.unavailable: " + (last == null ? "unknown" : last.getMessage()), last);
    }

    private void notifyProgress(ProgressListener progress, String stage, int current, int total, int attempt, String detail) {
        if (progress != null) {
            try {
                progress.onProgress(stage, current, total, attempt, detail);
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private SearchResponse callOpenAi(int index, String configuredUrl, String key, String model,
                                      String systemPrompt, String userPrompt) throws Exception {
        String url = configuredUrl.contains("/responses") ? configuredUrl
            : configuredUrl.replaceAll("/chat/completions/?$", "/responses");
        JsonObject json = sendOpenAiWithFallback(url, key, model, systemPrompt, userPrompt);
        return parseOpenAiResponse(index, model, json);
    }

    /**
     * 发送 OpenAI /v1/responses 请求：默认携带 search_context_size 与 include=web_search_call.action.sources。
     * 若第三方兼容网关拒绝扩展参数返回 HTTP 400，根据结构化错误参数精准移除不被支持的选项后重试；
     * 若 400 源于非法模型/输入等非扩展参数错误，直接向上抛出以便立即故障转移至下一端点。
     */
    JsonObject sendOpenAiWithFallback(String url, String key, String model,
                                      String systemPrompt, String userPrompt) throws Exception {
        JsonObject fullBody = buildOpenAiRequestBody(model, systemPrompt, userPrompt, true, true);
        BadRequestException firstErr;
        try {
            return send(url, key, null, fullBody);
        } catch (BadRequestException e) {
            firstErr = e;
        }

        if (firstErr.isNonExtensionClientError()) {
            throw firstErr;
        }
        boolean mentionsCtx = firstErr.mentionsParam("search_context_size");
        boolean mentionsInc = firstErr.mentionsParam("include");

        if (mentionsInc && !mentionsCtx) {
            log.warn("OpenAI /v1/responses 拒绝 include 参数（{}），仅移除 include 重试", firstErr.getMessage());
            return send(url, key, null, buildOpenAiRequestBody(model, systemPrompt, userPrompt, true, false));
        }
        if (mentionsCtx && !mentionsInc) {
            log.warn("OpenAI /v1/responses 拒绝 search_context_size 参数（{}），移除后重试", firstErr.getMessage());
            try {
                return send(url, key, null, buildOpenAiRequestBody(model, systemPrompt, userPrompt, false, true));
            } catch (BadRequestException secondErr) {
                if (secondErr.isNonExtensionClientError()) {
                    throw secondErr;
                }
                log.warn("OpenAI /v1/responses 仍拒绝 include 参数（{}），回退到基础 web_search_preview 报文", secondErr.getMessage());
                return send(url, key, null, buildOpenAiRequestBody(model, systemPrompt, userPrompt, false, false));
            }
        }
        // 网关未明确指明具体参数名，或同时拒绝两项扩展参数：一次性回退到基础报文重试一次
        log.warn("OpenAI /v1/responses 拒绝扩展参数（{}），单次降级为基础 web_search_preview 报文重试", firstErr.getMessage());
        return send(url, key, null, buildOpenAiRequestBody(model, systemPrompt, userPrompt, false, false));
    }

    JsonObject buildOpenAiRequestBody(String model, String systemPrompt, String userPrompt,
                                      boolean includeContextSize, boolean includeActionSources) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("instructions", systemPrompt);
        body.addProperty("input", userPrompt);
        JsonArray tools = new JsonArray();
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "web_search_preview");
        if (includeContextSize) {
            tool.addProperty("search_context_size",
                MusicConfig.normalizeNativeContextSize(config == null ? null : config.getLlmWebSearchNativeContextSize()));
        }
        tools.add(tool);
        body.add("tools", tools);
        if (includeActionSources) {
            JsonArray include = new JsonArray();
            include.add("web_search_call.action.sources");
            body.add("include", include);
        }
        body.addProperty("max_output_tokens", config.getLlmWebSearchMaxTokens());
        return body;
    }

    SearchResponse parseOpenAiResponse(int index, String model, JsonObject json) {
        SearchResponse result = base(index, "openai", model);
        JsonArray output = array(json, "output");
        StringBuilder text = new StringBuilder();
        if (output != null) for (JsonElement blockEl : output) {
            if (!blockEl.isJsonObject()) continue;
            JsonObject block = blockEl.getAsJsonObject();
            String blockType = string(block, "type");
            if ("web_search_call".equalsIgnoreCase(blockType) || "web_search".equalsIgnoreCase(blockType)) {
                // 仅采信已完成的工具调用，丢弃 failed/incomplete 调用的 action URL
                String callStatus = string(block, "status");
                if (callStatus != null && !callStatus.isBlank() && !"completed".equalsIgnoreCase(callStatus)) {
                    continue;
                }
                JsonObject action = object(block, "action");
                if (action != null) {
                    String actionType = string(action, "type");
                    boolean opened = "open_page".equalsIgnoreCase(actionType)
                        || "find_in_page".equalsIgnoreCase(actionType);
                    String actionUrl = string(action, "url");
                    if (actionUrl != null && !actionUrl.isBlank()) {
                        addCitation(result, actionUrl, null, null, opened);
                    }
                    JsonArray sources = array(action, "sources");
                    if (sources != null) {
                        for (JsonElement srcEl : sources) {
                            if (srcEl == null) continue;
                            if (srcEl.isJsonPrimitive() && srcEl.getAsJsonPrimitive().isString()) {
                                addCitation(result, srcEl.getAsString(), null, null, false);
                            } else if (srcEl.isJsonObject()) {
                                JsonObject srcObj = srcEl.getAsJsonObject();
                                addCitation(result, string(srcObj, "url"), string(srcObj, "title"), null, false);
                            }
                        }
                    }
                }
            }
            JsonArray content = array(block, "content");
            if (content == null) continue;
            for (JsonElement contentEl : content) {
                if (!contentEl.isJsonObject()) continue;
                JsonObject c = contentEl.getAsJsonObject();
                String piece = string(c, "text");
                if (piece != null) text.append(piece);
                JsonArray annotations = array(c, "annotations");
                if (annotations != null) for (JsonElement annEl : annotations) {
                    if (!annEl.isJsonObject()) continue;
                    JsonObject ann = annEl.getAsJsonObject();
                    JsonObject citation = ann.has("url_citation") && ann.get("url_citation").isJsonObject()
                        ? ann.getAsJsonObject("url_citation") : ann;
                    addCitation(result, string(citation, "url"), string(citation, "title"), null, false);
                }
            }
        }
        result.setTokenUsage(new LlmProvider.OpenAiCompatible().extractUsage(json));
        result.setText(text.toString().trim());
        if (result.getText().isEmpty()) throw new IllegalStateException("OpenAI 搜索响应没有文本");
        return result;
    }

    private SearchResponse callAnthropic(int index, String url, String key, String model,
                                         String systemPrompt, String userPrompt) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", config.getLlmWebSearchMaxTokens());
        body.addProperty("system", systemPrompt);
        JsonArray tools = new JsonArray();
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "web_search_20250305");
        tool.addProperty("name", "web_search");
        tool.addProperty("max_uses", 5);
        tools.add(tool); body.add("tools", tools);
        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject(); message.addProperty("role", "user"); message.addProperty("content", userPrompt);
        messages.add(message); body.add("messages", messages);

        JsonObject json = send(url, key, "anthropic", body);
        return parseAnthropicResponse(index, model, json);
    }

    SearchResponse parseAnthropicResponse(int index, String model, JsonObject json) {
        SearchResponse result = base(index, "anthropic", model);
        StringBuilder text = new StringBuilder();
        JsonArray content = array(json, "content");
        if (content != null) for (JsonElement el : content) {
            if (!el.isJsonObject()) continue;
            JsonObject block = el.getAsJsonObject();
            String piece = string(block, "text");
            if (piece != null) text.append(piece);
            JsonArray citations = array(block, "citations");
            if (citations != null) for (JsonElement citEl : citations) {
                if (!citEl.isJsonObject()) continue;
                JsonObject cit = citEl.getAsJsonObject();
                addCitation(result, string(cit, "url"), string(cit, "title"), string(cit, "cited_text"));
            }
            // Anthropic web_search_tool_result 在报错时 content 为 JsonObject（web_search_tool_result_error），
            // 必须先判断 isJsonArray，防止直接 ClassCastException 崩溃
            JsonArray searchResults = array(block, "content");
            if (searchResults != null) for (JsonElement srEl : searchResults) {
                if (!srEl.isJsonObject()) continue;
                JsonObject sr = srEl.getAsJsonObject();
                addCitation(result, string(sr, "url"), string(sr, "title"), string(sr, "encrypted_content"));
            }
        }
        result.setTokenUsage(new LlmProvider.Anthropic().extractUsage(json));
        result.setText(text.toString().trim());
        if (result.getText().isEmpty()) throw new IllegalStateException("Anthropic 搜索响应没有文本");
        return result;
    }

    JsonObject send(String url, String key, String provider, JsonObject body) throws Exception {
        com.lux032.musicautotagger.util.OutboundUrlValidator.validateAndResolve(
            url, config.isLlmAllowPrivateEndpoints(), false, false);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(Math.max(5, config.getLlmWebSearchTimeoutSeconds())))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)));
        if ("anthropic".equalsIgnoreCase(provider)) {
            builder.header("x-api-key", key).header("anthropic-version", "2023-06-01")
                .header("anthropic-beta", "web-search-2025-03-05");
        } else builder.header("Authorization", "Bearer " + key);
        HttpResponse<java.io.InputStream> response = http().send(
            builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        String responseBody;
        try (java.io.InputStream in = response.body()) {
            responseBody = com.lux032.musicautotagger.util.OutboundUrlValidator.readBoundedString(
                in, com.lux032.musicautotagger.util.OutboundUrlValidator.DEFAULT_MAX_RESPONSE_BYTES);
        }
        if (response.statusCode() == 400) {
            throw BadRequestException.fromResponse(responseBody, gson);
        }
        if (response.statusCode() == 429 || response.statusCode() >= 500) throw new Retryable("http " + response.statusCode());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("http " + response.statusCode() + ": " + sanitizeErrorSummary(responseBody, gson));
        }
        JsonObject json = gson.fromJson(responseBody, JsonObject.class);
        if (json == null) throw new Retryable("invalid json");
        return json;
    }

    static String sanitizeErrorSummary(String responseBody, Gson gson) {
        if (responseBody == null || responseBody.isBlank()) return "empty error";
        try {
            JsonObject obj = gson.fromJson(responseBody, JsonObject.class);
            JsonObject err = obj != null && obj.has("error") && obj.get("error").isJsonObject()
                ? obj.getAsJsonObject("error") : obj;
            if (err != null) {
                List<String> parts = new ArrayList<>();
                for (String k : List.of("type", "code", "param")) {
                    if (err.has(k) && err.get(k).isJsonPrimitive()) {
                        String v = err.get(k).getAsString().trim();
                        if (!v.isEmpty()) parts.add(k + "=" + redactSecrets(v));
                    }
                }
                if (err.has("message") && err.get("message").isJsonPrimitive()) {
                    String m = redactSecrets(err.get("message").getAsString().trim());
                    if (!m.isEmpty()) parts.add("msg=" + (m.length() > 160 ? m.substring(0, 160) : m));
                }
                if (!parts.isEmpty()) return String.join(", ", parts);
            }
        } catch (Exception ignored) {
        }
        return "upstream_error";
    }

    static String redactSecrets(String raw) {
        if (raw == null) return "";
        return raw
            .replaceAll("(?i)(bearer\\s+)[a-z0-9._\\-]{8,}", "$1***")
            .replaceAll("\\b(sk|tvly|ghp|xox[baprs])-[a-zA-Z0-9_\\-]{6,}", "$1-***")
            .replaceAll("([?&][^=\\s&]*(?:key|token|secret|auth|sig|signature)=)[^&\\s]+", "$1***");
    }

    private SearchResponse base(int index, String provider, String model) {
        SearchResponse r = new SearchResponse(); r.setEndpointIndex(index); r.setProvider(provider); r.setModel(model); return r;
    }
    private void addCitation(SearchResponse r, String url, String title, String snippet) {
        addCitation(r, url, title, snippet, false);
    }
    private void addCitation(SearchResponse r, String url, String title, String snippet, boolean openedPage) {
        if (url == null || url.isBlank()) return;
        r.addCitation(Citation.of(url, title, snippet, openedPage));
    }
    private JsonArray array(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : null;
    }
    private JsonObject object(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }
    private String string(JsonObject o,String k){ return o!=null&&o.has(k)&&o.get(k).isJsonPrimitive()?o.get(k).getAsString():null; }
    private static class Retryable extends Exception { Retryable(String m){super(m);} }
    static class BadRequestException extends IllegalStateException {
        private final String errorCode;
        private final String errorParam;
        private final String lowerDetail;

        BadRequestException(String message) {
            this(message, null, null);
        }

        BadRequestException(String message, String errorCode, String errorParam) {
            super(message);
            this.errorCode = errorCode == null ? "" : errorCode.toLowerCase(Locale.ROOT);
            this.errorParam = errorParam == null ? "" : errorParam.toLowerCase(Locale.ROOT);
            this.lowerDetail = message == null ? "" : message.toLowerCase(Locale.ROOT);
        }

        static BadRequestException fromResponse(String responseBody, Gson gson) {
            String code = null;
            String param = null;
            try {
                JsonObject obj = gson.fromJson(responseBody, JsonObject.class);
                JsonObject err = obj != null && obj.has("error") && obj.get("error").isJsonObject()
                    ? obj.getAsJsonObject("error") : obj;
                if (err != null) {
                    if (err.has("code") && err.get("code").isJsonPrimitive()) code = err.get("code").getAsString();
                    if (err.has("param") && err.get("param").isJsonPrimitive()) param = err.get("param").getAsString();
                }
            } catch (Exception ignored) {
            }
            return new BadRequestException("http 400: " + sanitizeErrorSummary(responseBody, gson), code, param);
        }

        boolean mentionsParam(String name) {
            String n = name.toLowerCase(Locale.ROOT);
            return errorParam.contains(n) || lowerDetail.contains(n);
        }

        boolean isNonExtensionClientError() {
            if (mentionsParam("search_context_size") || mentionsParam("include")) {
                return false;
            }
            return errorCode.contains("model_not_found")
                || errorCode.contains("invalid_model")
                || errorCode.contains("context_length_exceeded")
                || errorCode.contains("invalid_api_key")
                || errorCode.contains("insufficient_quota")
                || errorParam.equals("model")
                || errorParam.equals("input")
                || lowerDetail.contains("model_not_found")
                || lowerDetail.contains("context_length_exceeded");
        }
    }
}
