package com.lux032.musicautotagger.service.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.lux032.musicautotagger.service.MusicBrainzClient;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 封闭式 MusicBrainz 同名艺人消歧器。
 *
 * 严格约束：
 *   - 仅向模型提供真实 MusicBrainz 检索候选编号（1..N）及脱敏后的客观元数据字段（绝不传递或要求模型输出 UUID）；
 *   - 模型只能返回候选序号（1..N 的严格整数）或 0（无法确定），代码按序号回查真实 MBID；
 *   - 仅当 choice_index 合法且 confidence >= 0.85 时才允许自动采纳，其余降级为待人工确认。
 */
@Slf4j
public class LlmArtistDisambiguator {

    public static final double AUTO_ACCEPT_CONFIDENCE = 0.85;
    private static final Pattern UUID_SUBSTRING_PATTERN = Pattern.compile(
        "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");

    private static final String SYSTEM_PROMPT =
        "You disambiguate music artists and songwriters against a closed list of real MusicBrainz artist candidates.\n"
        + "Rules:\n"
        + "1. Choose the single candidate index (1..N) that refers to the EXACT same person/group in the album context.\n"
        + "   Credits may include a studio/unit affiliation in parentheses (e.g. 'Name (Studio)') or bilingual native+romanized forms (e.g. '雨良 Amala'); match the underlying person entity when name, sort_name, aliases, disambiguation, and album context clearly align.\n"
        + "2. If evidence is insufficient, multiple candidates remain equally plausible, or none match, return choice_index=0.\n"
        + "3. NEVER invent IDs, names, or facts. Be conservative: a wrong match pollutes the music library.\n"
        + "4. Return ONLY one strict JSON object: {\"choice_index\":integer,\"confidence\":number,\"reason\":string}\n"
        + "   where confidence is a finite number in [0.0, 1.0].";

    private final LlmClient llmClient;
    private final Gson gson = new Gson();

    public record AlbumContext(
        String albumTitle,
        String releaseDate,
        List<String> preferredRegions,
        List<String> roles,
        List<String> coCreators
    ) {
        public AlbumContext {
            preferredRegions = preferredRegions == null ? List.of() : List.copyOf(preferredRegions);
            roles = roles == null ? List.of() : List.copyOf(roles);
            coCreators = coCreators == null ? List.of() : List.copyOf(coCreators);
        }
    }

    public record DisambiguationResult(
        int choiceIndex,
        MusicBrainzClient.ArtistMatch chosen,
        double confidence,
        String reason
    ) {
        public static DisambiguationResult none(String reason) {
            return new DisambiguationResult(0, null, 0.0, reason);
        }

        public boolean isAccepted() {
            return chosen != null && Double.isFinite(confidence) && confidence >= AUTO_ACCEPT_CONFIDENCE;
        }
    }

    public LlmArtistDisambiguator(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public boolean isAvailable() {
        return llmClient != null && llmClient.isConfigured();
    }

    public DisambiguationResult disambiguate(String personName,
                                             AlbumContext context,
                                             List<MusicBrainzClient.ArtistMatch> candidates)
            throws InterruptedException {
        if (!isAvailable() || personName == null || personName.isBlank()
                || candidates == null || candidates.isEmpty()) {
            return DisambiguationResult.none(null);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("cancelled");
        }
        String userPrompt = buildPrompt(personName, context, candidates);
        try {
            LlmClient.LlmResponse response = llmClient.complete(SYSTEM_PROMPT, userPrompt, 400);
            return parseResponse(response == null ? null : response.getText(), candidates);
        } catch (LlmClient.LlmException e) {
            if (Thread.currentThread().isInterrupted() || "interrupted".equals(e.getMessage())) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("cancelled");
            }
            log.warn("同名艺人封闭式 LLM 消歧失败 ({}): {}", personName, e.getMessage());
            return DisambiguationResult.none("LLM 消歧调用失败，已保留同名候选待人工确认");
        }
    }

    String buildPrompt(String personName, AlbumContext context, List<MusicBrainzClient.ArtistMatch> candidates) {
        JsonObject root = new JsonObject();
        root.addProperty("target_name", redactUuids(personName));
        if (context != null) {
            if (context.albumTitle() != null && !context.albumTitle().isBlank()) {
                root.addProperty("album_title", redactUuids(context.albumTitle()));
            }
            if (context.releaseDate() != null && !context.releaseDate().isBlank()) {
                root.addProperty("release_date", redactUuids(context.releaseDate()));
            }
            if (!context.preferredRegions().isEmpty()) {
                JsonArray regions = new JsonArray();
                context.preferredRegions().forEach(r -> regions.add(redactUuids(r)));
                root.add("preferred_regions", regions);
            }
            if (!context.roles().isEmpty()) {
                JsonArray roles = new JsonArray();
                context.roles().forEach(r -> roles.add(redactUuids(r)));
                root.add("roles_on_album", roles);
            }
            if (!context.coCreators().isEmpty()) {
                JsonArray co = new JsonArray();
                context.coCreators().stream().limit(12).forEach(n -> co.add(redactUuids(n)));
                root.add("other_people_on_album", co);
            }
        }
        JsonArray arr = new JsonArray();
        for (int i = 0; i < candidates.size(); i++) {
            MusicBrainzClient.ArtistMatch c = candidates.get(i);
            JsonObject item = new JsonObject();
            item.addProperty("index", i + 1);
            item.addProperty("name", redactUuids(c.name()));
            if (c.sortName() != null) item.addProperty("sort_name", redactUuids(c.sortName()));
            if (c.type() != null) item.addProperty("type", redactUuids(c.type()));
            if (c.country() != null) item.addProperty("country", redactUuids(c.country()));
            if (c.areaName() != null) item.addProperty("area", redactUuids(c.areaName()));
            if (c.disambiguation() != null) item.addProperty("disambiguation", redactUuids(c.disambiguation()));
            if (c.lifeSpanBegin() != null) item.addProperty("life_span_begin", redactUuids(c.lifeSpanBegin()));
            if (c.lifeSpanEnd() != null) item.addProperty("life_span_end", redactUuids(c.lifeSpanEnd()));
            item.addProperty("search_score", c.score());
            if (!c.aliases().isEmpty()) {
                JsonArray aliases = new JsonArray();
                c.aliases().stream().limit(8).forEach(a -> aliases.add(redactUuids(a)));
                item.add("aliases", aliases);
            }
            if (!c.tags().isEmpty()) {
                JsonArray tags = new JsonArray();
                c.tags().stream().limit(8).forEach(tg -> tags.add(redactUuids(tg)));
                item.add("tags", tags);
            }
            arr.add(item);
        }
        root.add("candidates", arr);
        return gson.toJson(root);
    }

    static String redactUuids(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        return UUID_SUBSTRING_PATTERN.matcher(raw).replaceAll("[redacted-id]");
    }

    DisambiguationResult parseResponse(String text, List<MusicBrainzClient.ArtistMatch> candidates) {
        JsonObject json = LlmAlbumJudge.parseJsonObject(text);
        if (json == null) {
            return DisambiguationResult.none("LLM 消歧返回非 JSON，已保留同名候选");
        }
        Integer choiceIndex = readStrictInt(json, "choice_index");
        Double confidence = readStrictConfidence(json, "confidence");
        String reason = readStrictString(json, "reason");
        if (choiceIndex == null || confidence == null) {
            return DisambiguationResult.none("LLM 消歧返回字段类型非法，已保留同名候选");
        }
        if (choiceIndex <= 0 || choiceIndex > candidates.size()) {
            return new DisambiguationResult(0, null, confidence, reason);
        }
        return new DisambiguationResult(choiceIndex, candidates.get(choiceIndex - 1), confidence, reason);
    }

    private static Integer readStrictInt(JsonObject json, String key) {
        if (json == null || !json.has(key)) return null;
        JsonElement el = json.get(key);
        if (el == null || !el.isJsonPrimitive()) return null;
        JsonPrimitive prim = el.getAsJsonPrimitive();
        if (!prim.isNumber()) return null;
        try {
            BigDecimal bd = prim.getAsBigDecimal();
            if (bd == null || bd.stripTrailingZeros().scale() > 0) {
                return null;
            }
            return bd.intValueExact();
        } catch (Exception e) {
            return null;
        }
    }

    private static Double readStrictConfidence(JsonObject json, String key) {
        if (json == null || !json.has(key)) return null;
        JsonElement el = json.get(key);
        if (el == null || !el.isJsonPrimitive()) return null;
        JsonPrimitive prim = el.getAsJsonPrimitive();
        if (!prim.isNumber()) return null;
        try {
            double v = prim.getAsDouble();
            if (!Double.isFinite(v) || v < 0.0 || v > 1.0) {
                return null;
            }
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readStrictString(JsonObject json, String key) {
        if (json == null || !json.has(key)) return null;
        JsonElement el = json.get(key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) return null;
        String s = el.getAsString().trim();
        return s.isEmpty() ? null : (s.length() <= 240 ? s : s.substring(0, 240));
    }
}
