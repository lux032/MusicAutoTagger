package com.lux032.musicautotagger.util;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.service.AlbumArtistPolicy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared helpers for structured multi-artist credits across non-MusicBrainz paths
 * (online search, LLM matching, unverified archive, and manual confirmation overrides).
 */
public final class ArtistCreditSupport {

    private static final List<String> COMPOSITE_MARKERS = List.of(
        ", ", ",", " & ", " / ", "、", "，", ";",
        " feat.", " feat ", " featuring ", " ft.", " ft ",
        " with ", " vs.", " vs ", " × "
    );

    private static final Pattern CONNECTOR_TOKENS = Pattern.compile(
        "(?i)\\b(featuring|feat\\.?|ft\\.?|with|vs\\.?|and)\\b"
    );

    private ArtistCreditSupport() {
    }

    /** Returns true when a flat display string appears to combine multiple artists. */
    public static boolean looksComposite(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        if (AlbumArtistPolicy.VARIOUS_ARTISTS.equalsIgnoreCase(trimmed)) {
            return false;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String marker : COMPOSITE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** Normalizes an artist list by trimming blanks and preserving order. */
    public static List<String> cleanNames(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String name : names) {
            if (name != null) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    cleaned.add(trimmed);
                }
            }
        }
        return cleaned.isEmpty() ? List.of() : List.copyOf(cleaned);
    }

    /** Parses semicolon- or newline-delimited artist lists entered by a user (never splits on commas). */
    public static List<String> parseSemicolonOrLineList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split("[;；\\r\\n]+");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result.isEmpty() ? List.of() : List.copyOf(result);
    }

    /**
     * Validates that {@code names} appear verbatim in {@code display} in order,
     * with only separators / connector words in between or trailing.
     */
    public static boolean matchesDisplayOrder(String display, List<String> names) {
        if (display == null || display.isBlank() || names == null || names.isEmpty()) {
            return false;
        }
        String trimmed = display.trim();
        int pos = 0;
        StringBuilder leftovers = new StringBuilder();
        for (String rawName : names) {
            if (rawName == null || rawName.isBlank()) {
                return false;
            }
            String name = rawName.trim();
            int idx = trimmed.indexOf(name, pos);
            if (idx < 0) {
                return false;
            }
            leftovers.append(trimmed, pos, idx).append(' ');
            pos = idx + name.length();
        }
        leftovers.append(trimmed.substring(pos));

        String stripped = CONNECTOR_TOKENS.matcher(leftovers.toString()).replaceAll("");
        for (int i = 0; i < stripped.length(); ) {
            int cp = stripped.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    /**
     * Encodes {@code MUSICBRAINZ_ARTIST_CREDIT} / {@code MUSICBRAINZ_ALBUM_ARTIST_CREDIT} JSON
     * when {@code names} has >= 2 entries that align cleanly to {@code display} with no trailing text.
     */
    public static String buildCreditJson(String display, List<String> names) {
        List<String> cleaned = cleanNames(names);
        if (display == null || display.isBlank()
                || AlbumArtistPolicy.VARIOUS_ARTISTS.equalsIgnoreCase(display.trim())
                || cleaned.size() < 2) {
            return null;
        }
        String trimmed = display.trim();
        List<String> before = new ArrayList<>(cleaned.size());
        int pos = 0;
        for (int i = 0; i < cleaned.size(); i++) {
            String name = cleaned.get(i);
            int idx = trimmed.indexOf(name, pos);
            if (idx < 0) {
                return null;
            }
            if (i == 0 && idx != 0) {
                return null;
            }
            before.add(trimmed.substring(pos, idx));
            pos = idx + name.length();
        }
        if (pos != trimmed.length()) {
            return null;
        }

        var data = JsonNodeFactory.instance.objectNode();
        data.put("display", trimmed);
        var nameValues = data.putArray("names");
        cleaned.forEach(nameValues::add);
        var beforeValues = data.putArray("before");
        before.forEach(beforeValues::add);
        return data.toString();
    }

    /** Applies display artist and structured {@code artists}/{@code artistCredit} onto track metadata. */
    public static void applyTrackArtistCredit(MusicMetadata md, String display, List<String> artists) {
        if (md == null || display == null || display.isBlank()) {
            return;
        }
        String trimmed = display.trim();
        md.setArtist(trimmed);
        List<String> cleaned = cleanNames(artists);
        if (!cleaned.isEmpty()) {
            md.setArtists(cleaned);
            md.setArtistCredit(buildCreditJson(trimmed, cleaned));
        } else if (!looksComposite(trimmed) && !AlbumArtistPolicy.VARIOUS_ARTISTS.equalsIgnoreCase(trimmed)) {
            md.setArtists(List.of(trimmed));
            md.setArtistCredit(null);
        } else {
            md.setArtists(null);
            md.setArtistCredit(null);
        }
    }

    /** Applies display album artist and structured {@code albumArtists}/{@code albumArtistCredit}, respecting policy. */
    public static void applyAlbumArtistCredit(MusicMetadata md, String display, List<String> albumArtists,
                                              AlbumArtistPolicy policy) {
        if (md == null || display == null || display.isBlank()) {
            return;
        }
        String trimmed = display.trim();
        List<String> cleaned = cleanNames(albumArtists);
        boolean collapse = policy != null && policy.shouldCollapse()
            && (cleaned.size() > 1 || looksComposite(trimmed));
        if (collapse || AlbumArtistPolicy.VARIOUS_ARTISTS.equalsIgnoreCase(trimmed)) {
            md.setAlbumArtist(AlbumArtistPolicy.VARIOUS_ARTISTS);
            return;
        }
        md.setAlbumArtist(trimmed);
        if (!cleaned.isEmpty()) {
            md.setAlbumArtists(cleaned);
            md.setAlbumArtistCredit(buildCreditJson(trimmed, cleaned));
        } else if (!looksComposite(trimmed)) {
            md.setAlbumArtists(List.of(trimmed));
            md.setAlbumArtistCredit(null);
        } else {
            md.setAlbumArtists(null);
            md.setAlbumArtistCredit(null);
        }
    }
}
