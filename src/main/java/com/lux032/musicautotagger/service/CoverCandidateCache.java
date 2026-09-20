package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;

import java.nio.file.Path;

/** Shared candidate-cover cache location used by the collector and preview servlet. */
public final class CoverCandidateCache {
    private CoverCandidateCache() {}

    public static Path directory(MusicConfig config) {
        String base = config.getCoverArtCacheDirectory();
        if (base == null || base.isBlank()) {
            base = config.getOutputDirectory() + "/.cover_cache";
        }
        return Path.of(base, ".candidates");
    }

    public static boolean validSha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }
}
