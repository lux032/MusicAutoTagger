package com.lux032.musicautotagger.service.llm;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 辅助专辑判定（阶段七 #22）
 *
 * **必须是封闭选择题，不能是开放问答。**
 *
 * 封闭式：给出候选 release 列表 + 文件夹名 + 各文件标签/文件名/时长，
 *        模型只能回答「选第几个」或「都不是」，外加一句理由。
 * 开放式（**本类刻意不支持**）：直接问「这是什么专辑」会让模型**编造**
 *        专辑名 / 年份 / 曲目号。尤其是 MusicBrainz 未收录的精选集，
 *        模型极可能给出看起来非常合理但完全虚构的答案，比现在的 bug 更难发现。
 *
 * 附带一道是非题：「这个文件夹是否为 MB 未收录的精选集/自制合辑？」
 * ——为覆盖率检测（阶段一 #3）补上语义层解释，是非题的幻觉风险极低。
 *
 * 结论默认只是**建议**，仍需人工确认；是否允许自动落盘由
 * {@code llm.album.autoApply} 控制（默认关闭）。
 */
@Slf4j
public class LlmAlbumJudge {

    private static final String SYSTEM_PROMPT =
        "You are a music metadata assistant. You must answer ONLY with a single JSON object, "
      + "no markdown, no code fence, no extra prose. "
      + "You must NEVER invent album titles, release dates, track numbers or catalog numbers. "
      + "Your only allowed album decision is to pick one of the numbered candidates that are given to you, "
      + "or to answer 0 meaning 'none of them'. "
      + "If the evidence is weak or ambiguous, answer 0 and give a low confidence.";

    private final LlmClient client;

    public LlmAlbumJudge(LlmClient client) {
        this.client = client;
    }

    public boolean isAvailable() {
        return client.isConfigured();
    }

    /**
     * 判定结果。
     *
     * {@code choiceIndex == 0} 表示「候选里都不是」，此时 releaseId 为 null。
     */
    public static class Judgement {
        private int choiceIndex;
        private String releaseId;
        private String releaseGroupId;
        private String title;
        private double confidence;
        private boolean unreleasedCompilation;
        private String reason;
        private String model;
        private String provider;

        public int getChoiceIndex() {
            return choiceIndex;
        }

        public String getReleaseId() {
            return releaseId;
        }

        public String getReleaseGroupId() {
            return releaseGroupId;
        }

        public String getTitle() {
            return title;
        }

        public double getConfidence() {
            return confidence;
        }

        public boolean isUnreleasedCompilation() {
            return unreleasedCompilation;
        }

        public String getReason() {
            return reason;
        }

        public String getModel() {
            return model;
        }

        public String getProvider() {
            return provider;
        }
    }

    /**
     * 对一个待确认条目做封闭式判定。
     *
     * 只有带 {@code releaseId} 的候选才会进入选项列表——
     * 仅 Release Group 级的候选即便被选中也无法完成确认（拿不到碟号/曲目号/发行日期），
     * 放进选项里只会诱导出一个无法执行的答案。
     */
    public Judgement judge(ReviewItem item) throws LlmClient.LlmException {
        List<ReviewItem.CandidateSnapshot> selectable = selectableCandidates(item);
        String prompt = buildPrompt(item, selectable);

        log.info("LLM 专辑判定开始: {} ({} 个可选候选, {} 个文件)",
            item.getFolderName(), selectable.size(), item.getFiles().size());

        LlmClient.LlmResponse response = client.complete(SYSTEM_PROMPT, prompt, 0);
        JsonObject json = parseJsonObject(response.getText());
        if (json == null) {
            throw new LlmClient.LlmException("llm.response.not.json: " + response.getText());
        }

        Judgement judgement = new Judgement();
        judgement.model = response.getModel();
        judgement.provider = response.getProvider();
        judgement.choiceIndex = optInt(json, "choice", 0);
        judgement.confidence = clamp(optDouble(json, "confidence", 0.0));
        judgement.unreleasedCompilation = optBoolean(json, "is_unreleased_compilation", false)
            || optBoolean(json, "isUnreleasedCompilation", false);
        judgement.reason = optString(json, "reason");

        // 越界的 choice 一律按「都不是」处理，绝不越权映射到某个候选
        if (judgement.choiceIndex < 1 || judgement.choiceIndex > selectable.size()) {
            if (judgement.choiceIndex != 0) {
                log.warn("LLM 返回的候选序号越界（{}），按「都不是」处理", judgement.choiceIndex);
                judgement.reason = "(模型返回的序号越界，已按「都不是」处理) "
                    + (judgement.reason == null ? "" : judgement.reason);
            }
            judgement.choiceIndex = 0;
        } else {
            ReviewItem.CandidateSnapshot chosen = selectable.get(judgement.choiceIndex - 1);
            judgement.releaseId = chosen.getReleaseId();
            judgement.releaseGroupId = chosen.getReleaseGroupId();
            judgement.title = chosen.getTitle();
        }

        log.info("LLM 专辑判定结果: choice={} ({}), 置信度={}, 未收录合辑={}, 理由={}",
            judgement.choiceIndex,
            judgement.title == null ? "都不是" : judgement.title,
            String.format("%.2f", judgement.confidence),
            judgement.unreleasedCompilation,
            judgement.reason);

        return judgement;
    }

    /** 只有能真正被确认的候选（带 releaseId）才作为选项 */
    public List<ReviewItem.CandidateSnapshot> selectableCandidates(ReviewItem item) {
        List<ReviewItem.CandidateSnapshot> result = new ArrayList<>();
        if (item.getCandidates() == null) {
            return result;
        }
        for (ReviewItem.CandidateSnapshot candidate : item.getCandidates()) {
            if (candidate.getReleaseId() != null && !candidate.getReleaseId().isEmpty()) {
                result.add(candidate);
            }
        }
        return result;
    }

    // ==================== prompt ====================

    private String buildPrompt(ReviewItem item, List<ReviewItem.CandidateSnapshot> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("A local folder of audio files could not be matched to a MusicBrainz release automatically.\n");
        sb.append("Every track was identified individually by acoustic fingerprint, but the ALBUM is undecided.\n\n");

        sb.append("FOLDER NAME: ").append(nz(item.getFolderName())).append('\n');
        sb.append("FILE COUNT: ").append(item.getFiles() == null ? 0 : item.getFiles().size()).append('\n');
        if (item.getDurationSequence() != null && !item.getDurationSequence().isEmpty()) {
            sb.append("FOLDER TRACK DURATIONS (seconds, in track order): ")
              .append(item.getDurationSequence()).append('\n');
        }
        sb.append('\n');

        sb.append("TRACKS IDENTIFIED IN THE FOLDER:\n");
        int index = 1;
        for (ReviewItem.FileEntry file : item.getFiles()) {
            MusicMetadata md = file.getMetadata();
            sb.append("  ").append(index++).append(". file=\"").append(nz(file.getFileName())).append('"');
            if (md != null) {
                sb.append(" | title=\"").append(nz(md.getTitle())).append('"');
                sb.append(" | artist=\"").append(nz(md.getArtist())).append('"');
                if (md.getTrackNo() != null) {
                    sb.append(" | track=").append(md.getTrackNo());
                }
            }
            if (file.getDuration() != null) {
                sb.append(" | duration=").append(file.getDuration()).append("s");
            }
            sb.append('\n');
        }
        sb.append('\n');

        if (candidates.isEmpty()) {
            sb.append("CANDIDATE RELEASES: (none)\n");
            sb.append("There is no candidate to choose from, so \"choice\" MUST be 0.\n\n");
        } else {
            sb.append("CANDIDATE RELEASES (these are the ONLY album answers you may give):\n");
            int i = 1;
            for (ReviewItem.CandidateSnapshot candidate : candidates) {
                sb.append("  ").append(i++).append(". title=\"").append(nz(candidate.getTitle())).append('"');
                if (candidate.getDate() != null && !candidate.getDate().isEmpty()) {
                    sb.append(" | date=").append(candidate.getDate());
                }
                if (candidate.getMediaFormat() != null && !candidate.getMediaFormat().isEmpty()) {
                    sb.append(" | format=").append(candidate.getMediaFormat());
                }
                sb.append(" | tracks=").append(candidate.getTrackCount());
                if (candidate.getDurationSimilarity() != null) {
                    sb.append(" | durationSequenceSimilarity=")
                      .append(String.format("%.3f", candidate.getDurationSimilarity()));
                }
                sb.append(" | supportedBySamples=").append(candidate.getSupportCount())
                  .append('/').append(candidate.getTotalSamples());
                if (candidate.getDurations() != null && !candidate.getDurations().isEmpty()) {
                    sb.append(" | officialDurations=").append(candidate.getDurations());
                }
                sb.append('\n');
            }
            sb.append('\n');
        }

        sb.append("HOW TO DECIDE:\n");
        sb.append("- The strongest evidence is the track duration sequence: a real match has nearly the same\n");
        sb.append("  number of tracks AND nearly the same durations in the same order.\n");
        sb.append("- 'supportedBySamples' means how many sampled tracks list this release as a possible source.\n");
        sb.append("  A compilation reuses tracks from many old albums, so each old album is supported by only a few tracks.\n");
        sb.append("- If the folder looks like a compilation / best-of / greatest hits / self-made mix that is simply\n");
        sb.append("  NOT in MusicBrainz yet, answer choice=0 and is_unreleased_compilation=true.\n");
        sb.append("- Never pick a candidate just because the artist is the same.\n\n");

        sb.append("ANSWER FORMAT (strict JSON, no code fence):\n");
        sb.append("{\"choice\": <integer 0..").append(candidates.size())
          .append(">, \"confidence\": <number 0..1>, \"is_unreleased_compilation\": <true|false>, ");
        sb.append("\"reason\": \"<one or two short sentences>\"}\n");
        sb.append("choice = 0 means \"none of the candidates is this album\".\n");
        return sb.toString();
    }

    // ==================== 解析 ====================

    /**
     * 宽容解析：模型有时会包上 ```json 代码块或加一句寒暄，
     * 这里取第一个平衡的 {...} 片段再交给 Gson。
     */
    public static JsonObject parseJsonObject(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    String candidate = trimmed.substring(start, i + 1);
                    try {
                        return new Gson().fromJson(candidate, JsonObject.class);
                    } catch (Exception e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static int optInt(JsonObject json, String key, int defaultValue) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return defaultValue;
        }
        try {
            return element.getAsInt();
        } catch (Exception e) {
            try {
                return Integer.parseInt(element.getAsString().trim());
            } catch (Exception ignored) {
                return defaultValue;
            }
        }
    }

    private static double optDouble(JsonObject json, String key, double defaultValue) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return defaultValue;
        }
        try {
            return element.getAsDouble();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static boolean optBoolean(JsonObject json, String key, boolean defaultValue) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return defaultValue;
        }
        try {
            return element.getAsBoolean();
        } catch (Exception e) {
            return "true".equalsIgnoreCase(element.getAsString().trim());
        }
    }

    private static String optString(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    private static double clamp(double value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 1.0);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
