package com.lux032.musicautotagger.service;

import lombok.extern.slf4j.Slf4j;
import com.lux032.musicautotagger.config.MusicConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 艺术家匹配服务
 * 使用 LLM 进行模糊匹配，处理罗马音、非标准命名等情况
 */
@Slf4j
public class ArtistMatchingService {

    private final MusicConfig config;
    private final HttpClient httpClient;
    private final Gson gson;
    private List<String> lastCandidates;

    public ArtistMatchingService(MusicConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.gson = new Gson();
    }

    /**
     * 匹配结果
     */
    public static class MatchResult {
        private final String matchedArtist;
        private final double confidence;

        public MatchResult(String matchedArtist, double confidence) {
            this.matchedArtist = matchedArtist;
            this.confidence = confidence;
        }

        public String getMatchedArtist() {
            return matchedArtist;
        }

        public double getConfidence() {
            return confidence;
        }
    }

    /**
     * 匹配源文件艺术家标签与 outputDirectory 中的艺术家文件夹
     * @param sourceArtist 源文件的艺术家标签
     * @return 匹配结果，如果没有匹配返回 null
     */
    public MatchResult matchArtist(String sourceArtist) {
        if (sourceArtist == null || sourceArtist.trim().isEmpty()) {
            return null;
        }

        // 获取 outputDirectory 中的所有艺术家文件夹
        List<String> artistFolders = getArtistFolders();
        if (artistFolders.isEmpty()) {
            log.info("outputDirectory 中没有艺术家文件夹");
            return null;
        }

        log.info("开始 LLM 匹配: 源艺术家 = {}, 候选数量 = {}", sourceArtist, artistFolders.size());

        // 获取配置的 LLM 数量
        int llmCount = Math.min(
            Math.min(config.getLlmApiKeys().size(), config.getLlmApiUrls().size()),
            config.getLlmModels().size()
        );

        if (llmCount == 0) {
            log.warn("未配置有效的 LLM");
            return null;
        }

        // 依次尝试每个 LLM 配置
        for (int i = 0; i < llmCount; i++) {
            String apiKey = config.getLlmApiKeys().get(i);
            String apiUrl = config.getLlmApiUrls().get(i);
            String model = config.getLlmModels().get(i);

            log.info("尝试 LLM #{}: model={}", i + 1, model);

            try {
                String matchedArtist = callLLMForMatching(sourceArtist, artistFolders, apiKey, apiUrl, model);
                if (matchedArtist != null && !matchedArtist.isEmpty()) {
                    log.info("LLM #{} 匹配成功: {} -> {}", i + 1, sourceArtist, matchedArtist);
                    return new MatchResult(matchedArtist, 0.9);
                }
            } catch (Exception e) {
                log.warn("LLM #{} 调用失败: {}", i + 1, e.getMessage());
                if (i == llmCount - 1) {
                    log.error("所有 LLM 配置均失败");
                }
            }
        }

        return null;
    }

    /**
     * 获取 outputDirectory 中的所有艺术家文件夹名称
     */
    private List<String> getArtistFolders() {
        List<String> folders = new ArrayList<>();
        File outputDir = new File(config.getOutputDirectory());

        if (!outputDir.exists() || !outputDir.isDirectory()) {
            return folders;
        }

        File[] files = outputDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    folders.add(file.getName());
                }
            }
        }

        return folders;
    }

    /**
     * 调用 LLM API 进行艺术家匹配
     */
    private String callLLMForMatching(String sourceArtist, List<String> candidateArtists,
                                      String apiKey, String apiUrl, String model) throws Exception {
        this.lastCandidates = candidateArtists;
        String prompt = buildMatchingPrompt(sourceArtist, candidateArtists);

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);

        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("max_tokens", 100);
        requestBody.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new Exception("LLM API 返回错误: " + response.statusCode() + " - " + response.body());
        }

        return parseMatchResult(response.body());
    }

    private String buildMatchingPrompt(String sourceArtist, List<String> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("请判断艺术家名称 \"").append(sourceArtist).append("\" 是否与以下任一艺术家匹配（考虑罗马音、别名、翻译等）：\n\n");
        for (int i = 0; i < candidates.size(); i++) {
            sb.append(i + 1).append(". ").append(candidates.get(i)).append("\n");
        }
        sb.append("\n如果匹配，只返回对应的序号（如 1、2、3）。如果不匹配，返回 \"NONE\"。");
        return sb.toString();
    }

    private String parseMatchResult(String responseBody) {
        try {
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);
            JsonArray content = json.getAsJsonArray("content");
            if (content != null && content.size() > 0) {
                String text = content.get(0).getAsJsonObject().get("text").getAsString().trim();
                if (text.equals("NONE") || text.isEmpty()) {
                    return null;
                }
                // 解析序号
                try {
                    int index = Integer.parseInt(text) - 1;
                    if (index >= 0 && index < lastCandidates.size()) {
                        return lastCandidates.get(index);
                    }
                } catch (NumberFormatException e) {
                    log.warn("LLM 返回的不是有效序号: {}", text);
                }
                return null;
            }
        } catch (Exception e) {
            log.error("解析 LLM 响应失败: {}", e.getMessage());
        }
        return null;
    }
}
