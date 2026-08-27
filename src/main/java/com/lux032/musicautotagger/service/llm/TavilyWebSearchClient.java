package com.lux032.musicautotagger.service.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.lux032.musicautotagger.config.MusicConfig;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tavily 检索 + 普通 LLM 归纳的联网搜索实现。
 *
 * 相比 {@link NativeWebSearchClient} 依赖模型厂商自带的 web search 工具，本实现把检索交给
 * 外部搜索 API，模型只做两件不需要联网能力的事：
 *   1. 由文件名 / 标签生成检索词（模型比正则更懂「日文原名 / 罗马音 / 品番」这类变体）；
 *   2. 基于检索片段归纳成既定的 JSON。
 *
 * 这样带来三个确定性收益：
 *   - 任何 OpenAI 兼容端点都能用，不再要求 /responses 或 tools 支持；
 *   - citation 的 URL 来自检索器，是真实存在的页面，来源可信度门槛不再被模型自述架空；
 *   - 检索失败与归纳失败可以分别定位，不再是一个笼统的「搜索不可用」。
 */
@Slf4j
public class TavilyWebSearchClient implements WebSearchClient {

    /** 生成检索词的系统提示：只吐 JSON，不要解释 */
    private static final String QUERY_SYSTEM =
        "You generate web search queries for identifying a music release. "
        + "Read the folder name, file names and existing tags, then produce short, high-signal queries. "
        + "Cover name variants: original language title, romanized title, catalog number, label, artist. "
        + "Do NOT include site: operators. Return ONLY JSON: {\"queries\":[string]}.";

    private static final int MAX_QUERIES = 4;
    /** 单条检索片段截断长度：8 条 x 4 查询全量正文会轻易撑爆归纳阶段的上下文 */
    private static final int SNIPPET_LIMIT = 1200;

    private final MusicConfig config;
    private final LlmClient llmClient;
    private final HttpClient http;
    private final Gson gson = new Gson();

    public TavilyWebSearchClient(MusicConfig config, LlmClient llmClient) {
        this.config = config;
        this.llmClient = llmClient;
        this.http = LlmClient.buildHttpClient(config);
    }

    @Override
    public String name() {
        return "tavily";
    }

    @Override
    public boolean hasEnabledEndpoint() {
        String key = config.getTavilyApiKey();
        // 归纳阶段仍然要调 LLM，光有 Tavily key 不算可用
        return key != null && !key.isBlank() && llmClient.isConfigured();
    }

    @Override
    public SearchResponse search(String systemPrompt, String userPrompt) throws LlmClient.LlmException {
        if (!hasEnabledEndpoint()) {
            throw new LlmClient.LlmException("llm.web.search.unavailable: tavily.apiKey or llm endpoint missing");
        }

        List<String> queries = generateQueries(userPrompt);
        SearchResponse result = new SearchResponse();
        result.setProvider("tavily");

        List<String> failures = new ArrayList<>();
        for (String query : queries) {
            try {
                collect(query, result);
            } catch (Exception e) {
                // 单条查询失败不影响整体：只要还有别的查询拿到结果就继续
                failures.add(query + " -> " + e.getMessage());
                log.warn("Tavily 检索失败 [{}]: {}", query, e.getMessage());
            }
        }

        if (result.getCitations().isEmpty()) {
            throw new LlmClient.LlmException("llm.web.search.no.results: " + String.join(" | ", failures));
        }

        // 归纳阶段走普通 chat completions，不需要端点支持任何工具协议
        String prompt = userPrompt + "\n\n" + renderEvidence(result.getCitations());
        LlmClient.LlmResponse summary = llmClient.complete(
            systemPrompt, prompt, config.getLlmWebSearchMaxTokens());

        result.setText(summary.getText());
        result.setModel(summary.getModel());
        result.setProvider("tavily+" + summary.getProvider());
        return result;
    }

    // ==================== 第一步：生成检索词 ====================

    /**
     * 让模型基于文件名 / 标签产出检索词。
     *
     * 模型不可用或输出不合规时退回启发式：宁可用一条粗糙的查询，也不要整条链路直接失败。
     */
    private List<String> generateQueries(String userPrompt) {
        Set<String> queries = new LinkedHashSet<>();
        try {
            LlmClient.LlmResponse response = llmClient.complete(QUERY_SYSTEM,
                "Local album context:\n" + userPrompt, 400);
            JsonObject json = LlmAlbumJudge.parseJsonObject(response.getText());
            JsonArray array = json == null ? null : json.getAsJsonArray("queries");
            if (array != null) {
                for (JsonElement element : array) {
                    if (!element.isJsonPrimitive()) {
                        continue;
                    }
                    String query = element.getAsString().trim();
                    if (!query.isEmpty() && queries.size() < MAX_QUERIES) {
                        queries.add(query);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("检索词生成失败，退回启发式: {}", e.getMessage());
        }

        if (queries.isEmpty()) {
            String fallback = heuristicQuery(userPrompt);
            if (fallback != null) {
                queries.add(fallback);
            }
        }
        if (queries.isEmpty()) {
            throw new IllegalStateException("无法生成任何检索词");
        }
        log.info("Tavily 检索词: {}", queries);
        return new ArrayList<>(queries);
    }

    /** 从 prompt 里的 "FOLDER: xxx" 行取文件夹名，去掉常见的音源标记噪声 */
    private String heuristicQuery(String userPrompt) {
        for (String line : userPrompt.split("\n")) {
            if (line.startsWith("FOLDER: ")) {
                String folder = line.substring(8).trim()
                    .replaceAll("[\\[\\]{}()_.]+", " ")
                    .replaceAll("(?i)\\b(flac|mp3|wav|24bit|16bit|44 1khz|96khz|web|cd|dsd|hi res|tak|ape)\\b", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
                return folder.isEmpty() ? null : folder + " album";
            }
        }
        return null;
    }

    // ==================== 第二步：调用 Tavily ====================

    private void collect(String query, SearchResponse result) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("query", query);
        body.addProperty("search_depth", depth());
        body.addProperty("max_results", config.getTavilyMaxResults());
        body.addProperty("include_answer", false);
        body.addProperty("include_raw_content", false);

        List<String> domains = config.getTavilyIncludeDomains();
        if (domains != null && !domains.isEmpty()) {
            JsonArray include = new JsonArray();
            domains.forEach(include::add);
            body.add("include_domains", include);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(config.getTavilyApiUrl()))
            .header("Content-Type", "application/json")
            // 新版 Tavily 用 Bearer；旧版的 body.api_key 已废弃，不再兼容以免混淆错误信息
            .header("Authorization", "Bearer " + config.getTavilyApiKey())
            .timeout(Duration.ofSeconds(Math.max(5, config.getTavilyTimeoutSeconds())))
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("tavily http " + response.statusCode()
                + ": " + truncate(response.body(), 300));
        }

        JsonObject json;
        try {
            json = gson.fromJson(response.body(), JsonObject.class);
        } catch (JsonSyntaxException e) {
            throw new IllegalStateException("tavily 响应不是合法 JSON");
        }
        JsonArray results = json == null ? null : json.getAsJsonArray("results");
        if (results == null) {
            throw new IllegalStateException("tavily 响应缺少 results");
        }
        for (JsonElement element : results) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            result.addCitation(Citation.of(
                string(item, "url"),
                string(item, "title"),
                truncate(string(item, "content"), SNIPPET_LIMIT)));
        }
    }

    // ==================== 第三步：拼装归纳素材 ====================

    /**
     * 把检索结果渲染成带编号与可信度标注的证据块。
     *
     * 明确要求「source_urls 只能取自本列表」：否则模型会凭记忆编造 MusicBrainz 链接，
     * 而上层的来源门槛正是按 URL 匹配证据的，编造的 URL 会让候选无法通过校验（或更糟，
     * 在早期实现里反而被放行）。
     */
    private String renderEvidence(List<Citation> citations) {
        StringBuilder sb = new StringBuilder();
        sb.append("WEB SEARCH RESULTS (retrieved via Tavily, these are the ONLY sources you may cite):\n");
        int i = 1;
        for (Citation c : citations) {
            sb.append('[').append(i++).append("] ").append(nz(c.getTitle())).append('\n')
              .append("    url: ").append(c.getUrl()).append('\n')
              .append("    reliability: ").append(c.getReliability()).append('\n')
              .append("    excerpt: ").append(nz(c.getSnippet()).replace("\n", " ")).append('\n');
        }
        sb.append("\nEvery source_urls entry MUST be copied verbatim from the url fields above. ")
          .append("Do not invent URLs, MusicBrainz IDs or facts that the excerpts do not support. ")
          .append("If the excerpts are insufficient, return an empty candidates array and set needs_second_round=true.");
        return sb.toString();
    }

    // ==================== 工具方法 ====================

    private String depth() {
        String depth = config.getTavilySearchDepth();
        return "basic".equalsIgnoreCase(depth) ? "basic" : "advanced";
    }

    private String string(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private String truncate(String text, int limit) {
        if (text == null) {
            return null;
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "...";
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }
}
