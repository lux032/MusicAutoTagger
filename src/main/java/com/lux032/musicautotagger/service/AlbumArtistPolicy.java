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

    public String fallbackUnknown(String albumArtist) {
        if (albumArtist == null || albumArtist.trim().isEmpty()
            || "Unknown Artist".equalsIgnoreCase(albumArtist.trim())
            || "Unknown".equalsIgnoreCase(albumArtist.trim())) {
            return VARIOUS_ARTISTS;
        }
        return albumArtist.trim();
    }

    public String resolveFromCredits(JsonNode artistCredits) {
        if (artistCredits == null || !artistCredits.isArray() || artistCredits.isEmpty()) {
            return null;
        }
        if (artistCredits.size() == 1) {
            return fallbackUnknown(creditName(artistCredits.get(0)));
        }
        if (shouldCollapse()) {
            return VARIOUS_ARTISTS;
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < artistCredits.size(); i++) {
            JsonNode credit = artistCredits.get(i);
            String name = creditName(credit);
            if (name != null) joined.append(name);
            String joinPhrase = credit.path("joinphrase").asText("");
            if (!joinPhrase.isEmpty()) {
                joined.append(joinPhrase);
            } else if (i < artistCredits.size() - 1) {
                joined.append(", ");
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

    private static String creditName(JsonNode credit) {
        String creditedName = credit.path("name").asText("").trim();
        if (!creditedName.isEmpty()) return creditedName;
        String artistName = credit.path("artist").path("name").asText("").trim();
        return artistName.isEmpty() ? null : artistName;
    }
}
