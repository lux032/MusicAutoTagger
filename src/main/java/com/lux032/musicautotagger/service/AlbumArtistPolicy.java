package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.lux032.musicautotagger.config.MusicConfig;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Applies the configured album-artist representation at business boundaries. */
public final class AlbumArtistPolicy {
    public static final String VARIOUS_ARTISTS = "Various Artists";

    private final MusicConfig config;

    public AlbumArtistPolicy(MusicConfig config) {
        this.config = config;
    }

    public boolean shouldCollapse() {
        return config.isCollapseAlbumArtistToVariousArtists();
    }

    public boolean shouldPreferNativeName() {
        return config == null || config.isPreferNativeArtistName();
    }

    public String fallbackUnknown(String albumArtist) {
        if (albumArtist == null || albumArtist.trim().isEmpty()
            || "Unknown Artist".equalsIgnoreCase(albumArtist.trim())
            || "Unknown".equalsIgnoreCase(albumArtist.trim())) {
            return VARIOUS_ARTISTS;
        }
        return albumArtist.trim();
    }

    public String resolveFromCredits(JsonNode artistCredits) {
        return resolveFromCredits(artistCredits, shouldCollapse(), shouldPreferNativeName());
    }

    /**
     * Resolves album artist using explicit immutable snapshot flags (used by cached MusicBrainz
     * queries so mid-request hot-reloads cannot mix two configurations inside one result).
     */
    public String resolveFromCredits(JsonNode artistCredits, boolean collapseToVariousArtists, boolean preferNativeName) {
        if (artistCredits == null || !artistCredits.isArray() || artistCredits.isEmpty()) {
            return null;
        }
        if (artistCredits.size() == 1) {
            JsonNode credit = artistCredits.get(0);
            String name = ArtistCreditNameResolver.resolveCreditName(credit, preferNativeName);
            if (name == null) {
                return fallbackUnknown(null);
            }
            String trailing = credit.path("joinphrase").isTextual() ? credit.path("joinphrase").asText("") : "";
            return fallbackUnknown(name + trailing);
        }
        if (collapseToVariousArtists) {
            return VARIOUS_ARTISTS;
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < artistCredits.size(); i++) {
            JsonNode credit = artistCredits.get(i);
            String name = ArtistCreditNameResolver.resolveCreditName(credit, preferNativeName);
            if (name != null) joined.append(name);
            if (i < artistCredits.size() - 1) {
                joined.append(credit.path("joinphrase").isTextual() ? credit.path("joinphrase").asText() : ", ");
            } else if (credit.path("joinphrase").isTextual()) {
                joined.append(credit.path("joinphrase").asText(""));
            }
        }
        return fallbackUnknown(joined.toString());
    }

    public String collapseOrJoin(List<String> distinctArtists, boolean allRecognized) {
        Set<String> artists = new LinkedHashSet<>();
        if (distinctArtists != null) {
            for (String artist : distinctArtists) {
                if (artist != null && !artist.trim().isEmpty()) artists.add(artist.trim());
            }
        }
        if (artists.size() == 1 && allRecognized) return artists.iterator().next();
        if (shouldCollapse() || artists.isEmpty()) return VARIOUS_ARTISTS;
        return String.join(", ", artists);
    }
}
