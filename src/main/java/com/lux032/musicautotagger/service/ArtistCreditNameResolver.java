package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Resolves MusicBrainz artist-credit display names with optional native East Asian
 * script restoration (Han, Hiragana, Katakana, Hangul).
 */
public final class ArtistCreditNameResolver {

    private ArtistCreditNameResolver() {
    }

    /**
     * Returns true iff {@code text} contains at least one Latin letter and every
     * alphabetic character in {@code text} belongs to the Latin script.
     * Spaces, digits, punctuation, and Latin diacritics (including combining marks) are permitted.
     */
    public static boolean isPureLatinName(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean hasLatin = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.LATIN && Character.isLetter(cp)) {
                hasLatin = true;
            } else if (Character.isLetter(cp)) {
                return false;
            }
        }
        return hasLatin;
    }

    /**
     * Returns true iff {@code text} contains at least one East Asian script character
     * (Han, Hiragana, Katakana, or Hangul).
     */
    public static boolean containsEastAsianScript(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves a single MusicBrainz {@code artist-credit} entry's display name.
     *
     * <p>When {@code preferNativeName} is enabled and {@code credit.name} is purely Latin/Romanized
     * while {@code credit.artist.name} contains native East Asian script, {@code credit.artist.name}
     * is used so {@code ARTIST}/{@code ALBUM_ARTIST} align with canonical artist entities without
     * overwriting official Latin stage names, Western artists, or non-Latin character credits.</p>
     */
    public static String resolveCreditName(JsonNode credit, boolean preferNativeName) {
        if (credit == null || credit.isMissingNode() || credit.isNull()) {
            return null;
        }
        String creditedName = credit.path("name").asText("").trim();
        String artistName = credit.path("artist").path("name").asText("").trim();
        if (creditedName.isEmpty()) {
            return artistName.isEmpty() ? null : artistName;
        }
        if (preferNativeName
                && !artistName.isEmpty()
                && isPureLatinName(creditedName)
                && containsEastAsianScript(artistName)) {
            return artistName;
        }
        return creditedName;
    }

    /**
     * Resolves a full MusicBrainz {@code artist-credit} array into a joined display string,
     * preserving original {@code joinphrase} separators (including any trailing joinphrase).
     */
    public static String resolveCreditedArtistText(JsonNode credits, boolean preferNativeName) {
        StringBuilder value = new StringBuilder();
        if (credits != null && credits.isArray()) {
            for (int i = 0; i < credits.size(); i++) {
                JsonNode credit = credits.get(i);
                String name = resolveCreditName(credit, preferNativeName);
                if (name != null) {
                    value.append(name);
                }
                if (i < credits.size() - 1) {
                    value.append(credit.path("joinphrase").isTextual() ? credit.path("joinphrase").asText() : ", ");
                } else if (credit.path("joinphrase").isTextual()) {
                    value.append(credit.path("joinphrase").asText(""));
                }
            }
        }
        return value.toString();
    }
}
