package com.lux032.musicautotagger.service;

import lombok.extern.slf4j.Slf4j;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.service.llm.LlmClient;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LLM 艺术家匹配服务
 * 使用 LLM 进行模糊匹配，处理罗马音、非标准命名等情况
 *
 * 阶段七 #21 重构要点：
 *   - 协议不再硬编码 Anthropic，改由 {@link LlmClient} + provider 适配层处理
 *     （配置项名字是通用的 llm.apiUrl，原实现接 OpenAI 兼容端点会直接失败）
 *   - 删除实例字段 {@code lastCandidates}：它在并发调用时会串数据，
 *     现在候选列表只在方法栈内传递
 *   - temperature=0 + 指数退避重试由 LlmClient 统一保证
 *   - 输出改为「序号 + 理由」，max_tokens 由配置控制（原来固定 100 写不下理由）
 */
@Slf4j
public class ArtistMatchingService {

    private static final String SYSTEM_PROMPT =
        "You match artist names across languages and transliterations. "
      + "Answer with a single line in the form 'INDEX|REASON' where INDEX is one of the given numbers, "
      + "or 'NONE|REASON' if no candidate is the same artist. Never invent a new artist name.";

    private final MusicConfig config;
    private final LlmClient llmClient;

    public ArtistMatchingService(MusicConfig config) {
        this(config, new LlmClient(config));
    }

    public ArtistMatchingService(MusicConfig config, LlmClient llmClient) {
        this.config = config;
        this.llmClient = llmClient;
    }

    /**
     * 匹配结果
     */
    public static class MatchResult {
        private final String matchedArtist;
        private final double confidence;
        private final String reason;

        public MatchResult(String matchedArtist, double confidence) {
            this(matchedArtist, confidence, null);
        }

        public MatchResult(String matchedArtist, double confidence, String reason) {
            this.matchedArtist = matchedArtist;
            this.confidence = confidence;
            this.reason = reason;
        }

        public String getMatchedArtist() {
            return matchedArtist;
        }

        public double getConfidence() {
            return confidence;
        }

        public String getReason() {
            return reason;
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

        if (!llmClient.isConfigured()) {
            log.warn("未配置有效的 LLM（需要 llm.apiKey / llm.apiUrl / llm.model 数量一致）");
            return null;
        }

        log.info("开始 LLM 匹配: 源艺术家 = {}, 候选数量 = {}, 端点数 = {}",
            sourceArtist, artistFolders.size(), llmClient.endpointCount());

        try {
            LlmClient.LlmResponse response = llmClient.complete(
                SYSTEM_PROMPT, buildMatchingPrompt(sourceArtist, artistFolders), 0);
            return parseMatchResult(response.getText(), artistFolders);
        } catch (LlmClient.LlmException e) {
            log.warn("LLM 艺术家匹配失败: {}", e.getMessage());
            return null;
        }
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

    private String buildMatchingPrompt(String sourceArtist, List<String> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("Source artist tag: \"").append(sourceArtist).append("\"\n\n");
        sb.append("Existing artist folders:\n");
        for (int i = 0; i < candidates.size(); i++) {
            sb.append(i + 1).append(". ").append(candidates.get(i)).append('\n');
        }
        sb.append("\nWhich folder refers to the SAME artist as the source tag? ");
        sb.append("Consider romanization, aliases, translations and different scripts. ");
        sb.append("Different artists with similar names must NOT be matched.\n");
        sb.append("Answer exactly one line: 'INDEX|REASON' or 'NONE|REASON'.\n");
        return sb.toString();
    }

    /**
     * 解析「序号|理由」。
     * 兼容模型只回一个数字、或包了引号/句号的情况；
     * 越界或非数字一律按「没有匹配」处理，绝不越权映射到某个候选。
     */
    private MatchResult parseMatchResult(String text, List<String> candidates) {
        if (text == null) {
            return null;
        }
        String line = text.trim();
        int newline = line.indexOf('\n');
        if (newline > 0) {
            line = line.substring(0, newline).trim();
        }

        String indexPart = line;
        String reason = null;
        int separator = line.indexOf('|');
        if (separator >= 0) {
            indexPart = line.substring(0, separator).trim();
            reason = line.substring(separator + 1).trim();
        }

        // 去掉常见修饰：引号 / 句号 / "答案:" 之类
        indexPart = indexPart.replaceAll("[\"'`。.：:]", "").trim();

        if (indexPart.toUpperCase(Locale.ROOT).contains("NONE")) {
            log.info("LLM 未找到匹配的艺术家: {}", reason);
            return null;
        }

        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(indexPart);
        if (!matcher.find()) {
            log.warn("LLM 返回的不是有效序号: {}", line);
            return null;
        }

        int index = Integer.parseInt(matcher.group()) - 1;
        if (index < 0 || index >= candidates.size()) {
            log.warn("LLM 返回的序号越界（{}），按「没有匹配」处理", index + 1);
            return null;
        }

        String matched = candidates.get(index);
        log.info("LLM 匹配成功: {} (理由: {})", matched, reason);
        return new MatchResult(matched, 0.9, reason);
    }
}
