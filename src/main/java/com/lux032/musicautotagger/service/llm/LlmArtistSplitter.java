package com.lux032.musicautotagger.service.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.service.AlbumArtistPolicy;
import com.lux032.musicautotagger.util.ArtistCreditSupport;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Semantic multi-artist splitter backed by the configured LLM.
 *
 * Only invoked when a display string contains likely collaboration delimiters;
 * never guesses via punctuation alone so bands like "Earth, Wind & Fire" stay intact.
 */
@Slf4j
public class LlmArtistSplitter {

    private static final String SYSTEM_PROMPT =
        "You split combined music artist credit strings into individual credited artist names. "
      + "Return ONLY a strict JSON object: {\"artists\":[\"name1\",\"name2\"]}. "
      + "Rules: "
      + "(1) Split collaborations/featured artists in exact appearance order "
      + "(e.g. \"TeddyLoid, 佐倉綾音 & 井上和\" -> {\"artists\":[\"TeddyLoid\",\"佐倉綾音\",\"井上和\"]}; "
      + "\"A feat. B\" -> {\"artists\":[\"A\",\"B\"]}). "
      + "(2) NEVER split a single artist, duo, or band whose own name contains punctuation "
      + "(e.g. \"Earth, Wind & Fire\", \"Simon & Garfunkel\", \"Tyler, The Creator\", \"Above & Beyond\" -> 1-element array). "
      + "(3) Every element must appear verbatim in the input string with identical spelling and script.";

    private static final int MAX_CACHE_SIZE = 2048;

    private final LlmClient llmClient;
    private final Map<String, SplitResult> cache = new ConcurrentHashMap<>();

    public record SplitResult(String display, List<String> artists, boolean splitApplied) {
        public boolean hasStructuredArtists() {
            return artists != null && !artists.isEmpty();
        }
    }

    public LlmArtistSplitter(MusicConfig config) {
        this(new LlmClient(config));
    }

    public LlmArtistSplitter(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public boolean isAvailable() {
        return llmClient != null && llmClient.isConfigured();
    }

    /**
     * Semantically splits a flat artist credit string if needed.
     * Single-artist names without delimiters return immediately with no LLM call.
     */
    public SplitResult split(String rawDisplay) {
        if (rawDisplay == null || rawDisplay.isBlank()) {
            return new SplitResult(rawDisplay, List.of(), false);
        }
        String display = rawDisplay.trim();
        if (AlbumArtistPolicy.VARIOUS_ARTISTS.equalsIgnoreCase(display)) {
            return new SplitResult(AlbumArtistPolicy.VARIOUS_ARTISTS, List.of(), false);
        }
        if (!ArtistCreditSupport.looksComposite(display)) {
            return new SplitResult(display, List.of(display), false);
        }
        if (!isAvailable()) {
            return new SplitResult(display, List.of(), false);
        }
        SplitResult cached = cache.get(display);
        if (cached != null) {
            return cached;
        }

        SplitResult computed = invokeLlmSplit(display);
        if (cache.size() >= MAX_CACHE_SIZE) {
            cache.clear();
        }
        cache.put(display, computed);
        return computed;
    }

    private SplitResult invokeLlmSplit(String display) {
        try {
            String userPrompt = "Input artist credit: \"" + display + "\"\nReturn strict JSON only.";
            LlmClient.LlmResponse response = llmClient.complete(SYSTEM_PROMPT, userPrompt, 300);
            JsonObject json = LlmAlbumJudge.parseJsonObject(response.getText());
            if (json == null || !json.has("artists") || !json.get("artists").isJsonArray()) {
                log.debug("LLM 艺术家拆分未返回有效数组，保留原串: {}", display);
                return new SplitResult(display, List.of(), false);
            }
            JsonArray arr = json.getAsJsonArray("artists");
            List<String> parsed = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                if (el != null && el.isJsonPrimitive()) {
                    String name = el.getAsString().trim();
                    if (!name.isEmpty()) {
                        parsed.add(name);
                    }
                }
            }
            if (parsed.isEmpty()) {
                return new SplitResult(display, List.of(), false);
            }
            if (parsed.size() == 1) {
                if (display.equals(parsed.get(0))) {
                    return new SplitResult(display, List.of(display), false);
                }
                log.debug("LLM 艺术家拆分单人校验未通过 ({} -> {}), 保留原串", display, parsed.get(0));
                return new SplitResult(display, List.of(), false);
            }
            if (!ArtistCreditSupport.matchesDisplayOrder(display, parsed)) {
                log.warn("LLM 艺术家拆分结果与原串对齐校验失败 ({} -> {}), 放弃拆分", display, parsed);
                return new SplitResult(display, List.of(), false);
            }
            log.info("LLM 语义拆分多艺术家: \"{}\" -> {}", display, parsed);
            return new SplitResult(display, List.copyOf(parsed), true);
        } catch (LlmClient.LlmException e) {
            log.warn("LLM 艺术家拆分调用失败，保留原串 ({}): {}", display, e.getMessage());
            return new SplitResult(display, List.of(), false);
        }
    }
}
