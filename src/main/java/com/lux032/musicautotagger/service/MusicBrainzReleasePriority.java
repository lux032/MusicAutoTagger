package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Shared release ranking helper enforcing:
 * non-video > {@code release.countryPriority} > audio media format (CD > Digital > other) > earlier release date.
 */
public final class MusicBrainzReleasePriority {

    private MusicBrainzReleasePriority() {
    }

    /** Returns true iff the media format string denotes a video-only medium that must be excluded. */
    public static boolean isVideoFormat(String format) {
        if (format == null || format.isEmpty()) {
            return false;
        }
        String lower = format.toLowerCase(Locale.ROOT);
        // DVD-Audio 是纯音频媒介，不能误判为视频
        if (lower.contains("dvd-audio") || lower.contains("dvd audio")) {
            return false;
        }
        return lower.contains("dvd")
            || lower.contains("blu-ray")
            || lower.contains("bluray")
            || lower.contains("hd dvd")
            || lower.contains("hd-dvd")
            || lower.contains("vhs")
            || lower.contains("laserdisc")
            || lower.contains("vcd")
            || lower.contains("svcd")
            || lower.contains("umd")
            || lower.contains("video");
    }

    /** Scores an audio/video media format string (negative means excluded video). */
    public static int mediaFormatScore(String mediaFormat) {
        if (mediaFormat == null || mediaFormat.isBlank()) {
            return 0;
        }
        String lower = mediaFormat.toLowerCase(Locale.ROOT);
        if (isVideoFormat(lower)) {
            return -100;
        }
        if (lower.contains("cd")) {
            return 100;
        }
        if (lower.contains("digital")) {
            return 90;
        }
        return 50;
    }

    /**
     * Scores a MusicBrainz release JSON node across all of its media.
     *
     * <p>Returns the highest audio medium score if any medium is non-video (so mixed CD+DVD
     * releases remain eligible regardless of disc order), or {@code -100} only when every
     * medium in the release is video-only.</p>
     */
    public static int mediaFormatScore(JsonNode release) {
        if (release == null || release.isMissingNode() || release.isNull()) {
            return 0;
        }
        JsonNode media = release.path("media");
        if (!media.isArray() || media.isEmpty()) {
            return 0;
        }
        boolean hasAudioMedium = false;
        int bestAudioScore = 0;
        for (JsonNode medium : media) {
            int score = mediaFormatScore(medium.path("format").asText(""));
            if (score >= 0) {
                hasAudioMedium = true;
                if (score > bestAudioScore) {
                    bestAudioScore = score;
                }
            }
        }
        return hasAudioMedium ? bestAudioScore : -100;
    }

    /** Extracts the highest-scoring non-video audio medium format from a MusicBrainz release JSON node. */
    public static String extractPrimaryAudioFormat(JsonNode release) {
        if (release == null || release.isMissingNode() || release.isNull()) {
            return null;
        }
        JsonNode media = release.path("media");
        if (!media.isArray() || media.isEmpty()) {
            return null;
        }
        String bestFormat = null;
        int bestScore = -1;
        for (JsonNode medium : media) {
            String format = medium.path("format").asText("").trim();
            if (format.isEmpty()) {
                continue;
            }
            int score = mediaFormatScore(format);
            if (score >= 0 && score > bestScore) {
                bestScore = score;
                bestFormat = format;
            }
        }
        return bestFormat;
    }

    /**
     * Returns the zero-based priority rank of {@code country} within {@code countryPriority}
     * (case-insensitive), or {@link Integer#MAX_VALUE} if unconfigured or unmatched.
     */
    public static int countryRank(String country, List<String> countryPriority) {
        if (country == null || country.isBlank() || countryPriority == null || countryPriority.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        String target = country.trim();
        for (int i = 0; i < countryPriority.size(); i++) {
            String preferred = countryPriority.get(i);
            if (preferred != null && !preferred.isBlank() && preferred.trim().equalsIgnoreCase(target)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    /** Compares two ISO release dates so earlier non-empty dates sort first. */
    public static int compareReleaseDate(String dateA, String dateB) {
        String a = dateA == null ? "" : dateA.trim();
        String b = dateB == null ? "" : dateB.trim();
        if (a.isEmpty() && b.isEmpty()) {
            return 0;
        }
        if (a.isEmpty()) {
            return 1;
        }
        if (b.isEmpty()) {
            return -1;
        }
        return a.compareTo(b);
    }

    /**
     * Compares two release candidates (negative = A preferred over B):
     * non-video > countryPriority rank > mediaFormatScore > earlier release date.
     */
    public static int compareReleaseMetadata(String countryA, int formatScoreA, String dateA,
                                             String countryB, int formatScoreB, String dateB,
                                             List<String> countryPriority) {
        boolean videoA = formatScoreA < 0;
        boolean videoB = formatScoreB < 0;
        if (videoA != videoB) {
            return videoA ? 1 : -1;
        }
        int countryCmp = Integer.compare(
            countryRank(countryA, countryPriority),
            countryRank(countryB, countryPriority));
        if (countryCmp != 0) {
            return countryCmp;
        }
        int formatCmp = Integer.compare(formatScoreB, formatScoreA);
        if (formatCmp != 0) {
            return formatCmp;
        }
        return compareReleaseDate(dateA, dateB);
    }

    /** Compares two MusicBrainz release JSON nodes using {@link #compareReleaseMetadata}. */
    public static int compareReleaseJson(JsonNode a, JsonNode b, List<String> countryPriority) {
        String countryA = a == null ? null : a.path("country").asText(null);
        String countryB = b == null ? null : b.path("country").asText(null);
        int formatA = mediaFormatScore(a);
        int formatB = mediaFormatScore(b);
        String dateA = a == null ? null : a.path("date").asText(null);
        String dateB = b == null ? null : b.path("date").asText(null);
        return compareReleaseMetadata(countryA, formatA, dateA, countryB, formatB, dateB, countryPriority);
    }

    /** Builds a canonical cache-key fragment representing {@code countryPriority}. */
    public static String normalizeCountryPriorityKey(List<String> countryPriority) {
        if (countryPriority == null || countryPriority.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(",");
        for (String code : countryPriority) {
            if (code != null && !code.isBlank()) {
                joiner.add(code.trim().toUpperCase(Locale.ROOT));
            }
        }
        return joiner.toString();
    }
}
