package com.lux032.musicautotagger.service.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.MusicConfig;
import lombok.Data;
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
public class NativeWebSearchClient {
    private final MusicConfig config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final Gson gson = new Gson();

    public NativeWebSearchClient(MusicConfig config) { this.config = config; }

    @Data
    public static class Citation {
        private String url;
        private String title;
        private String snippet;
        private String domain;
        private long retrievedAt;
        private String reliability;
    }

    @Data
    public static class SearchResponse {
        private String text;
        private String provider;
        private String model;
        private int endpointIndex;
        private List<Citation> citations = new ArrayList<>();
    }

    public boolean hasEnabledEndpoint() {
        for (int i = 0; i < endpointCount(); i++) if (enabled(i)) return true;
        return false;
    }

    public SearchResponse search(String systemPrompt, String userPrompt) throws LlmClient.LlmException {
        Exception last = null;
        for (int i = 0; i < endpointCount(); i++) {
            if (!enabled(i)) continue;
            String url = config.getLlmApiUrls().get(i);
            String key = config.getLlmApiKeys().get(i);
            String model = config.getLlmModels().get(i);
            String provider = provider(url);
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
        body.addProperty("max_output_tokens", config.getLlmMaxTokens());

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
        body.addProperty("max_tokens", config.getLlmMaxTokens());
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
            .timeout(Duration.ofSeconds(Math.max(30, config.getLlmTimeoutSeconds())))
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
        if (r.citations.stream().anyMatch(c -> url.equals(c.url))) return;
        Citation c = new Citation(); c.url=url; c.title=title; c.snippet=snippet; c.retrievedAt=System.currentTimeMillis();
        try { c.domain=URI.create(url).getHost(); } catch(Exception ignored) {}
        c.reliability=reliability(c.domain); r.citations.add(c);
    }
    /**
     * 高可信注册表。必须精确匹配或子域名匹配：
     * 早期的 contains() 写法会把 fake-discogs.example.com、musicbrainz-data.example.org
     * 这类伪造域名当成 HIGH，直接绕过来源门槛。
     */
    private static final List<String> HIGH_TRUST = List.of(
        "musicbrainz.org", "discogs.com", "vgmdb.net", "spotify.com",
        "music.apple.com", "bandcamp.com");
    private static final List<String> MEDIUM_TRUST = List.of(
        "wikipedia.org", "amazon.com", "amazon.co.jp", "amazon.jp",
        "last.fm", "allmusic.com", "rateyourmusic.com");

    private String reliability(String domain) {
        if (domain == null || domain.isBlank()) return "LOW";
        String d = domain.toLowerCase(java.util.Locale.ROOT);
        if (d.startsWith("www.")) d = d.substring(4);
        if (matchesRegistry(d, HIGH_TRUST)) return "HIGH";
        if (matchesRegistry(d, MEDIUM_TRUST)) return "MEDIUM";
        return "LOW";
    }

    /** 精确相等，或是其真子域（artist.bandcamp.com 这类合法子域仍然放行） */
    private boolean matchesRegistry(String domain, List<String> registry) {
        for (String allowed : registry) {
            if (domain.equals(allowed) || domain.endsWith("." + allowed)) return true;
        }
        return false;
    }
    private int endpointCount(){ return Math.min(config.getLlmApiKeys().size(), Math.min(config.getLlmApiUrls().size(), config.getLlmModels().size())); }
    private boolean enabled(int i){ List<Boolean> f=config.getLlmWebSearchEnabled(); return f!=null && i<f.size() && Boolean.TRUE.equals(f.get(i)); }
    /**
     * 按端点逐个判定协议：URL 特征优先，全局 llm.provider 仅作兜底。
     * 早期实现只要全局配置含 anthropic 就把所有端点当 Anthropic 调，多端点混配会全线失败。
     */
    private String provider(String url){
        if(url!=null){
            if(url.contains("anthropic")||url.contains("/messages")) return "anthropic";
            if(url.contains("openai")||url.contains("/responses")||url.contains("/chat/completions")) return "openai";
        }
        String configured=config.getLlmProvider()==null?"":config.getLlmProvider().toLowerCase();
        return configured.contains("anthropic")?"anthropic":"openai";
    }
    private String string(JsonObject o,String k){ return o!=null&&o.has(k)&&o.get(k).isJsonPrimitive()?o.get(k).getAsString():null; }
    private String truncate(String s){ return s==null?"":s.substring(0,Math.min(300,s.length())); }
    private static class Retryable extends Exception { Retryable(String m){super(m);} }
}
