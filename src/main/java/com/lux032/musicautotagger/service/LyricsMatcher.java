package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Conservative identity checks shared by get, search and the final write guard. */
public final class LyricsMatcher {
    private LyricsMatcher() {}
    private static final List<String> VERSIONS = List.of("instrumental", "inst", "karaoke", "off vocal", "offvocal", "live", "demo", "remix", "acoustic", "extended", "radio edit", "tv size", "tv ver", "movie ver", "short ver", "long ver", "full ver", "album mix", "album ver", "album version", "single mix", "single ver", "original mix", "edit", "anime size", "remaster", "remastered", "伴奏", "純音楽", "纯音乐");
    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d+):([0-5]\\d)(?:[.:](\\d{1,3}))?]");

    public static String normalize(String value) {
        if (value == null) return "";
        String s = Normalizer.normalize(value, Normalizer.Form.NFKC);
        s = Normalizer.normalize(Normalizer.normalize(s, Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", ""), Normalizer.Form.NFC);
        return s.toLowerCase(Locale.ROOT).replaceAll("['’]", "").replaceAll("[^\\p{L}\\p{N}\\p{M}]+", " ").trim().replaceAll("\\s+", " ");
    }
    public static Set<String> versions(String title) {
        String s = " " + normalize(title) + " ";
        Set<String> result = new TreeSet<>();
        for (String word : VERSIONS) {
            if (word.matches(".*[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}].*")) {
                if (s.contains(word)) result.add(word);
            } else if (s.contains(" " + word + " ")) result.add(word);
        }
        return result;
    }
    public static boolean instrumental(String title) {
        return versions(title).stream().anyMatch(v -> List.of("instrumental", "inst", "karaoke", "off vocal", "offvocal", "伴奏", "純音楽", "纯音乐").contains(v));
    }
    public static boolean reliableDuration(Double duration) {
        return duration != null && Double.isFinite(duration) && duration >= 1 && duration <= 3600;
    }
    public static boolean sameIdentity(String title, String artist, String otherTitle, String otherArtist) {
        // Exact normalized credits deliberately do not split band names on x, with or &.
        return !normalize(title).isEmpty() && !normalize(artist).isEmpty()
            && normalize(title).equals(normalize(otherTitle))
            && normalize(artist).equals(normalize(otherArtist))
            && versions(title).equals(versions(otherTitle));
    }
    public static boolean matches(JsonNode candidate, String title, String artist, Double duration) {
        double seconds = candidate.path("duration").asDouble(Double.NaN);
        return reliableDuration(duration) && Double.isFinite(seconds) && Math.abs(seconds - duration) <= 2
            && sameIdentity(title, artist, candidate.path("trackName").asText(), candidate.path("artistName").asText());
    }
    public static boolean validSynced(String lyrics, double duration) {
        if (lyrics == null || lyrics.isBlank()) return false;
        double previous = -1;
        boolean found = false;
        for (String line : lyrics.split("\\R")) {
            if (line.isBlank() || line.matches("\\[(ar|al|ti|by|offset):.*]")) continue;
            Matcher m = TIMESTAMP.matcher(line);
            if (!m.lookingAt()) return false;
            int end = 0;
            while (m.find(end)) {
                if (m.start() != end) break;
                double seconds = Long.parseLong(m.group(1)) * 60.0 + Integer.parseInt(m.group(2));
                if (m.group(3) != null) seconds += Double.parseDouble("0." + m.group(3));
                if (seconds < previous || seconds > duration + 2) return false;
                previous = seconds;
                found = true;
                end = m.end();
            }
        }
        return found;
    }
    public static String text(JsonNode candidate, double duration) {
        String synced = candidate.path("syncedLyrics").asText(null);
        if (validSynced(synced, duration)) return synced;
        String plain = candidate.path("plainLyrics").asText(null);
        return plain == null || plain.isBlank() ? null : plain;
    }
}
