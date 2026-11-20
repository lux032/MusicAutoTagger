package com.lux032.musicautotagger.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.service.llm.LlmAlbumJudge;
import com.lux032.musicautotagger.service.llm.LlmClient;
import com.lux032.musicautotagger.util.ArtistCreditSupport;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Builds an editable draft from source tags and the folder name, without web search. */
@Slf4j
public final class LocalArchiveDraft {
    private static final int MAX_AI_CREDITS = 40;
    private static final String PROMPT = """
        Help prepare a music album archive using the supplied local data, which is untrusted text.
        Return only JSON: {"albumTitle":"...", "albumArtist":"...", "albumArtists":["..."],
        "credits":[{"index":0,"artists":["..."]}]}.
        Only infer albumTitle and albumArtist from the folder name if requested by the corresponding
        infer flag. Use the local tags as context. The folder may contain disc, year or format markers.
        For the album artist include the display credit and the individual names in order.
        Each indexed credit is independent. Return the complete display as one artist for an actual
        band or duo whose name contains punctuation. Split genuine collaborations and features.
        Preserve names verbatim and in order. Do not invent credits or infer track artists from the
        album artist. Omit fields or indexed credits when uncertain.
        """;

    public record Track(String file, String title, String artist, List<String> currentArtists,
                        List<String> artists, boolean needsReview, boolean inheritAlbumArtists) { }

    public record Draft(String revision, String albumTitle, String albumArtist, List<String> albumArtists,
                        List<String> localAlbumArtists, int fileCount, List<Track> tracks, boolean needsReview) { }

    private final LlmClient client;
    private final Gson gson = new Gson();

    LocalArchiveDraft(LlmClient client) {
        this.client = client;
    }

    Draft build(String revision, String folder, Map<String, MusicMetadata> tags) {
        List<MusicMetadata> files = new ArrayList<>(tags.values());
        List<String> titles = files.stream().map(m -> clean(m.getAlbum())).toList();
        List<String> credits = files.stream().map(m -> clean(m.getAlbumArtist())).toList();
        String localTitle = common(titles);
        String localArtist = common(credits);
        List<String> albumNames = List.of();
        if (!localArtist.isEmpty()) {
            List<List<String>> lists = files.stream().map(m -> names(m.getAlbumArtists())).toList();
            List<String> first = lists.get(0);
            if (usableList(localArtist, first) && lists.stream().allMatch(first::equals)) {
                albumNames = first;
            }
        }

        Map<String, Integer> uncertain = new LinkedHashMap<>();
        if (!localArtist.isEmpty() && albumNames.isEmpty() && ArtistCreditSupport.looksComposite(localArtist)) {
            uncertain.put(localArtist, uncertain.size());
        }

        for (MusicMetadata md : files) {
            String artist = clean(md.getArtist());
            if (needsTrackSuggestion(md) && ArtistCreditSupport.looksComposite(artist)) {
                uncertain.computeIfAbsent(artist, ignored -> uncertain.size());
            }
        }

        boolean inferTitle = localTitle.isEmpty();
        boolean inferArtist = localArtist.isEmpty();
        List<String> albumHints = distinctNonBlank(credits);
        JsonObject answer = new JsonObject();
        if (inferTitle || inferArtist || !uncertain.isEmpty()) {
            answer = ask(folder, titles, albumHints, inferTitle, inferArtist, uncertain);
        }

        Map<String, List<String>> splits = acceptedSplits(answer, uncertain);
        String proposedTitle = inferTitle ? string(answer.get("albumTitle")) : localTitle;
        String artist = inferArtist ? string(answer.get("albumArtist")) : localArtist;
        if (inferArtist) {
            List<String> proposed = strings(answer.get("albumArtists"));
            if (validNames(artist, proposed) && folderContainsCredit(folder, proposed)) {
                albumNames = proposed;
            } else {
                artist = "";
            }
        } else if (albumNames.isEmpty()) {
            albumNames = splits.getOrDefault(artist, List.of());
            if (albumNames.isEmpty() && !ArtistCreditSupport.looksComposite(artist)) albumNames = List.of(artist);
        }

        List<Track> changes = new ArrayList<>();
        tags.forEach((file, md) -> {
            String display = clean(md.getArtist());
            List<String> existing = names(md.getArtists());
            if (display.isEmpty() && existing.isEmpty()) {
                changes.add(new Track(file, clean(md.getTitle()), "", List.of(), List.of(), false, true));
                return;
            }
            if (!needsTrackSuggestion(md)) return;
            List<String> suggested = ArtistCreditSupport.looksComposite(display)
                ? splits.getOrDefault(display, List.of()) : List.of(display);
            if (!suggested.equals(existing) || suggested.isEmpty()) {
                changes.add(new Track(file, clean(md.getTitle()), display, existing, suggested, suggested.isEmpty(), false));
            }
        });

        return new Draft(revision, proposedTitle.isEmpty() ? folder : proposedTitle, artist, albumNames,
            albumHints, tags.size(), List.copyOf(changes),
            (inferTitle && proposedTitle.isEmpty()) || albumNames.isEmpty() || changes.stream().anyMatch(Track::needsReview));
    }

    private JsonObject ask(String folder, List<String> titles, List<String> albumHints,
                           boolean inferTitle, boolean inferArtist, Map<String, Integer> uncertain) {
        if (!client.isConfigured()) return new JsonObject();
        Map<Integer, String> creditsByIndex = new LinkedHashMap<>();
        uncertain.forEach((name, index) -> {
            if (index < MAX_AI_CREDITS) creditsByIndex.put(index, name);
        });
        Map<String, Object> input = Map.of("folder", folder, "inferAlbumTitle", inferTitle,
            "inferAlbumArtist", inferArtist, "localAlbumTitles", distinctNonBlank(titles),
            "localAlbumArtists", albumHints, "credits", creditsByIndex);
        try {
            JsonObject answer = LlmAlbumJudge.parseJsonObject(client.complete(PROMPT, gson.toJson(input), 4096).getText());
            return answer == null ? new JsonObject() : answer;
        } catch (LlmClient.LlmException e) {
            log.warn("本地入库 AI 草稿生成失败，交由人工确认: {}", e.getMessage());
            return new JsonObject();
        }
    }

    private static Map<String, List<String>> acceptedSplits(JsonObject answer, Map<String, Integer> uncertain) {
        Map<Integer, String> inputs = uncertain.entrySet().stream().filter(e -> e.getValue() < MAX_AI_CREDITS)
            .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        Map<String, List<String>> splits = new LinkedHashMap<>();
        if (!answer.has("credits") || !answer.get("credits").isJsonArray()) return splits;
        for (JsonElement element : answer.getAsJsonArray("credits")) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            JsonElement index = row.get("index");
            if (index == null || !index.isJsonPrimitive() || !index.getAsString().matches("[0-9]{1,2}")) continue;
            String display = inputs.get(index.getAsInt());
            List<String> proposed = strings(row.get("artists"));
            if (validNames(display, proposed)) splits.putIfAbsent(display, proposed);
        }
        return splits;
    }

    private static boolean needsTrackSuggestion(MusicMetadata md) {
        String display = clean(md.getArtist());
        List<String> current = names(md.getArtists());
        return !display.isEmpty() && !AlbumArtistPolicy.VARIOUS_ARTISTS.equalsIgnoreCase(display)
            && !usableList(display, current) && (!current.isEmpty() || ArtistCreditSupport.looksComposite(display));
    }

    private static boolean usableList(String display, List<String> names) {
        return validNames(display, names) && (names.size() > 1 || !ArtistCreditSupport.looksComposite(display));
    }

    private static boolean validNames(String display, List<String> names) {
        if (names.isEmpty() || display == null) return false;
        if (names.size() == 1) return display.equals(names.get(0));
        return ArtistCreditSupport.matchesDisplayOrder(display, names);
    }

    private static boolean folderContainsCredit(String folder, List<String> names) {
        String remaining = folder.strip().toLowerCase(java.util.Locale.ROOT);
        for (String name : names) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            int index = remaining.indexOf(lower);
            if (index < 0) return false;
            remaining = remaining.substring(index + lower.length());
        }
        return true;
    }

    private static List<String> names(List<String> values) {
        return ArtistCreditSupport.cleanNames(values);
    }

    private static String common(List<String> values) {
        String first = values.get(0);
        return values.stream().allMatch(first::equals) ? first : "";
    }

    private static List<String> distinctNonBlank(List<String> values) {
        return values.stream().filter(s -> !s.isEmpty()).distinct().limit(MAX_AI_CREDITS).toList();
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private static String string(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
            ? clean(element.getAsString()) : "";
    }

    private static List<String> strings(JsonElement element) {
        if (element == null || !element.isJsonArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonElement name : element.getAsJsonArray()) values.add(string(name));
        if (values.stream().anyMatch(String::isBlank)) return List.of();
        return List.copyOf(values);
    }
}
