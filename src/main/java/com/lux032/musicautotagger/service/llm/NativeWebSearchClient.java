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

/** OpenAI Responses / Anthropic Messages 原生 Web Search 适配器。 */
@Slf4j
public class NativeWebSearchClient implements WebSearchClient {
    private final MusicConfig config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final Gson gson = new Gson();

    public NativeWebSearchClient(MusicConfig config) { this.config = config; }

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
        Exception last = null;
        List<LlmEndpoint> endpoints = searchEndpoints();
        for (int i = 0; i < endpoints.size(); i++) {
            LlmEndpoint endpoint = endpoints.get(i);
            String url = endpoint.getApiUrl();
            String key = endpoint.getApiKey();
            String model = endpoint.getModel();
            // 协议来自供应商的显式配置，不再逐个端点猜 URL 形态
            String provider = endpoint.getFormat();
            for (int attempt = 0; attempt <= Math.max(0, config.getLlmMaxRetries()); attempt++) {
                try {
                    return "anthropic".equals(provider)
                        ? callAnthropic(i, url, key, model, systemPrompt, userPrompt)
                        : callOpenAi(i, url, key, model, systemPrompt, userPrompt);
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
                    last = e;
                    log.warn("联网搜索端点 #{} 不可用: {}", i + 1, e.getMessage());
                    break;
                }
            }
        }
        throw new LlmClient.LlmException("llm.web.search.unavailable: " + (last == null ? "unknown" : last.getMessage()), last);
    }

    private SearchResponse callOpenAi(int index, String configuredUrl, String key, String model,
                                      String systemPrompt, String userPrompt) throws Exception {
        String url = configuredUrl.contains("/responses") ? configuredUrl
            : configuredUrl.replaceAll("/chat/completions/?$", "/responses");
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("instructions", systemPrompt);
        body.addProperty("input", userPrompt);
        JsonArray tools = new JsonArray();
        JsonObject tool = new JsonObject(); tool.addProperty("type", "web_search_preview"); tools.add(tool);
        body.add("tools", tools);
        body.addProperty("max_output_tokens", config.getLlmWebSearchMaxTokens());

        JsonObject json = send(url, key, null, body);
        SearchResponse result = base(index, "openai", model);
        JsonArray output = json.getAsJsonArray("output");
        StringBuilder text = new StringBuilder();
        if (output != null) for (JsonElement blockEl : output) {
            if (!blockEl.isJsonObject()) continue;
            JsonArray content = blockEl.getAsJsonObject().getAsJsonArray("content");
            if (content == null) continue;
            for (JsonElement contentEl : content) {
                if (!contentEl.isJsonObject()) continue;
                JsonObject c = contentEl.getAsJsonObject();
                if (c.has("text")) text.append(c.get("text").getAsString());
                JsonArray annotations = c.getAsJsonArray("annotations");
                if (annotations != null) for (JsonElement annEl : annotations) {
                    if (!annEl.isJsonObject()) continue;
                    JsonObject ann = annEl.getAsJsonObject();
                    JsonObject citation = ann.has("url_citation") && ann.get("url_citation").isJsonObject()
                        ? ann.getAsJsonObject("url_citation") : ann;
                    addCitation(result, string(citation, "url"), string(citation, "title"), null);
                }
            }
        }
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
        SearchResponse result = base(index, "anthropic", model);
        StringBuilder text = new StringBuilder();
        JsonArray content = json.getAsJsonArray("content");
        if (content != null) for (JsonElement el : content) {
            if (!el.isJsonObject()) continue;
            JsonObject block = el.getAsJsonObject();
            if (block.has("text")) text.append(block.get("text").getAsString());
            JsonArray citations = block.getAsJsonArray("citations");
            if (citations != null) for (JsonElement citEl : citations) {
                if (!citEl.isJsonObject()) continue;
                JsonObject cit = citEl.getAsJsonObject();
                addCitation(result, string(cit, "url"), string(cit, "title"), string(cit, "cited_text"));
            }
            JsonArray searchResults = block.getAsJsonArray("content");
            if (searchResults != null) for (JsonElement srEl : searchResults) {
                if (!srEl.isJsonObject()) continue;
                JsonObject sr = srEl.getAsJsonObject();
                addCitation(result, string(sr, "url"), string(sr, "title"), string(sr, "encrypted_content"));
            }
        }
        result.setText(text.toString().trim());
        if (result.getText().isEmpty()) throw new IllegalStateException("Anthropic 搜索响应没有文本");
        return result;
    }

    private JsonObject send(String url, String key, String provider, JsonObject body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(config.getLlmWebSearchTimeoutSeconds()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)));
        if ("anthropic".equals(provider)) {
            builder.header("x-api-key", key).header("anthropic-version", "2023-06-01")
                .header("anthropic-beta", "web-search-2025-03-05");
        } else builder.header("Authorization", "Bearer " + key);
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429 || response.statusCode() >= 500) throw new Retryable("http " + response.statusCode());
        if (response.statusCode() != 200) throw new IllegalStateException("http " + response.statusCode() + ": " + truncate(response.body()));
        JsonObject json = gson.fromJson(response.body(), JsonObject.class);
        if (json == null) throw new Retryable("invalid json");
        return json;
    }

    private SearchResponse base(int index, String provider, String model) {
        SearchResponse r = new SearchResponse(); r.setEndpointIndex(index); r.setProvider(provider); r.setModel(model); return r;
    }
    private void addCitation(SearchResponse r, String url, String title, String snippet) {
        if (url == null || url.isBlank()) return;
        r.addCitation(Citation.of(url, title, snippet));
    }
    private String string(JsonObject o,String k){ return o!=null&&o.has(k)&&o.get(k).isJsonPrimitive()?o.get(k).getAsString():null; }
    private String truncate(String s){ return s==null?"":s.substring(0,Math.min(300,s.length())); }
    private static class Retryable extends Exception { Retryable(String m){super(m);} }
}
