package com.lux032.musicautotagger.web;

import com.lux032.musicautotagger.model.ReviewItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a read-only recommendation from the evidence already attached to a review item.
 * This class deliberately has no dependencies or mutable state so it cannot affect resolution flows.
 */
public final class SystemSuggestionCalculator {
    // Keep the UI recommendation aligned with DurationSequenceService.MatchQuality.
    private static final double LOCAL_STRONG_THRESHOLD = 0.85;
    private static final double LOCAL_MEDIUM_THRESHOLD = 0.75;

    private SystemSuggestionCalculator() {
    }

    public static Map<String, Object> evaluate(ReviewItem item, double llmAutoApplyMinConfidence) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();

        if (item == null || item.getStatus() != ReviewItem.Status.PENDING_REVIEW) {
            result.put("level", "RESOLVED");
            result.put("primaryAction", "NONE");
            result.put("primaryActionParams", params);
            result.put("localScore", null);
            result.put("onlineScore", null);
            result.put("llmScore", null);
            result.put("localQuality", "NONE");
            result.put("onlineQuality", "NONE");
            result.put("hasReleaseLevelCandidate", false);
            result.put("neverSearchedOnline", item == null || item.getOnlineSearchedAt() <= 0L);
            result.put("onlineEvidenceStale", item != null && item.isOnlineEvidenceStale());
            result.put("allCandidatesConfirmedNoRelease", false);
            result.put("llmDirection", null);
            return result;
        }

        List<ReviewItem.CandidateSnapshot> candidates = item.getCandidates() == null
            ? List.of() : new ArrayList<>(item.getCandidates());
        List<ReviewItem.OnlineCandidate> onlineCandidates = item.getOnlineCandidates() == null
            ? List.of() : new ArrayList<>(item.getOnlineCandidates());

        ReviewItem.CandidateSnapshot bestLocal = bestExecutableLocal(item, candidates);
        boolean hasReleaseLevelCandidate = bestLocal != null;
        Double localScore = localScore(item, bestLocal);
        ReviewItem.OnlineCandidate bestOnline = bestOnline(onlineCandidates);
        Double onlineScore = bestOnline == null ? null : onlineScore(bestOnline);
        boolean neverSearchedOnline = item.getOnlineSearchedAt() <= 0L;
        boolean stale = item.isOnlineEvidenceStale();
        boolean allConfirmedNoRelease = !candidates.isEmpty()
            && candidates.stream().allMatch(ReviewItem.CandidateSnapshot::isConfirmedNoRelease);

        ReviewItem.LlmSuggestion llm = item.getLlmSuggestion();
        String llmDirection = null;
        ReviewItem.CandidateSnapshot llmCandidate = null;
        if (llm != null) {
            if (llm.getChoiceIndex() > 0) {
                llmCandidate = findCurrentCandidate(candidates, llm.getSuggestedReleaseId());
                llmDirection = llmCandidate == null ? "INCONCLUSIVE" : "SUPPORTS_CANDIDATE";
            } else if (llm.isUnreleasedCompilation()) {
                llmDirection = "SUPPORTS_UNRELEASED";
            } else {
                llmDirection = "INCONCLUSIVE";
            }
        }

        result.put("localScore", localScore);
        result.put("onlineScore", onlineScore);
        result.put("llmScore", llm == null ? null : llm.getConfidence());
        result.put("localQuality", quality(localScore));
        result.put("onlineQuality", quality(onlineScore));
        result.put("hasReleaseLevelCandidate", hasReleaseLevelCandidate);
        result.put("neverSearchedOnline", neverSearchedOnline);
        result.put("onlineEvidenceStale", stale);
        result.put("allCandidatesConfirmedNoRelease", allConfirmedNoRelease);
        result.put("llmDirection", llmDirection);

        // Direct confirmation requires duration evidence on the exact executable candidate.
        // Folder confidence alone may justify expanding candidates, but never confirming one.
        boolean localStrong = bestLocal != null && validScore(bestLocal.getDurationSimilarity())
            && bestLocal.getDurationSimilarity() >= LOCAL_STRONG_THRESHOLD;
        boolean localMedium = scoreAtLeast(localScore, LOCAL_MEDIUM_THRESHOLD);
        boolean localWeak = localScore == null || localScore < LOCAL_MEDIUM_THRESHOLD;
        boolean onlineStrong = scoreAtLeast(onlineScore, LOCAL_STRONG_THRESHOLD);

        if (localStrong && hasReleaseLevelCandidate && onlineStrong && !stale
            && titlesConflict(bestLocal.getTitle(), bestOnline.getTitle())) {
            setAction(result, "CONFLICT", "CONFIRM_LOCAL");
            putLocalParams(params, bestLocal);
        } else if (localStrong && hasReleaseLevelCandidate) {
            setAction(result, "CONFIRM_LOCAL", "CONFIRM_LOCAL");
            putLocalParams(params, bestLocal);
        } else if (!hasReleaseLevelCandidate && localMedium) {
            setAction(result, "NEEDS_EXPAND", "EXPAND");
        } else if (onlineStrong && !stale) {
            setAction(result, "CONFIRM_ONLINE", "CONFIRM_ONLINE");
            if (bestOnline.getId() != null) params.put("onlineCandidateId", bestOnline.getId());
        } else if ("SUPPORTS_CANDIDATE".equals(llmDirection)
            && llm.getConfidence() >= llmAutoApplyMinConfidence) {
            // The executable ID is copied from the current snapshot, never from the LLM payload.
            setAction(result, "CONFIRM_LOCAL", "CONFIRM_LOCAL");
            putLocalParams(params, llmCandidate);
        } else if ("SUPPORTS_UNRELEASED".equals(llmDirection) || allConfirmedNoRelease) {
            // Archive is promoted only with two corroborating signals, or one strong signal plus weak local evidence.
            boolean promoteArchive = ("SUPPORTS_UNRELEASED".equals(llmDirection) && allConfirmedNoRelease)
                || (("SUPPORTS_UNRELEASED".equals(llmDirection) || allConfirmedNoRelease) && localWeak);
            setAction(result, "SUGGEST_ARCHIVE_UNVERIFIED", promoteArchive ? "ARCHIVE" : "NONE");
        } else if (stale) {
            // Once stronger confirmation/archive paths are exhausted, stale evidence should always be refreshed.
            setAction(result, "TRY_ONLINE_SEARCH", "ONLINE_SEARCH");
        } else if (neverSearchedOnline && (localWeak || candidates.isEmpty())) {
            setAction(result, "TRY_ONLINE_SEARCH", "ONLINE_SEARCH");
        } else {
            setAction(result, "WEAK_ALL", "NONE");
        }
        result.put("primaryActionParams", params);
        return result;
    }

    private static void setAction(Map<String, Object> result, String level, String action) {
        result.put("level", level);
        result.put("primaryAction", action);
    }

    private static void putLocalParams(Map<String, Object> params, ReviewItem.CandidateSnapshot candidate) {
        params.put("releaseId", candidate.getReleaseId());
        params.put("releaseGroupId", candidate.getReleaseGroupId());
    }

    private static Double localScore(ReviewItem item, ReviewItem.CandidateSnapshot bestLocal) {
        if (bestLocal != null && validScore(bestLocal.getDurationSimilarity())) {
            return bestLocal.getDurationSimilarity();
        }
        double confidence = item.getConfidence();
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) return null;
        // Without duration validation, confidence can guide the safe EXPAND action but is capped at MEDIUM.
        return Math.min(confidence, LOCAL_MEDIUM_THRESHOLD);
    }

    private static ReviewItem.CandidateSnapshot bestExecutableLocal(ReviewItem item,
                                                                     List<ReviewItem.CandidateSnapshot> candidates) {
        int fileCount = item.getFiles() == null ? -1 : item.getFiles().size();
        return candidates.stream()
            .filter(c -> hasText(c.getReleaseId()))
            .min(Comparator
                .comparingDouble((ReviewItem.CandidateSnapshot c) -> safeSimilarity(c)).reversed()
                .thenComparing(Comparator.comparingDouble(SystemSuggestionCalculator::supportRatio).reversed())
                .thenComparingInt(c -> trackDelta(c, fileCount))
                .thenComparing(ReviewItem.CandidateSnapshot::getReleaseId))
            .orElse(null);
    }

    private static double safeSimilarity(ReviewItem.CandidateSnapshot candidate) {
        Double value = candidate.getDurationSimilarity();
        return value != null && Double.isFinite(value) ? value : -1.0;
    }

    private static double supportRatio(ReviewItem.CandidateSnapshot candidate) {
        return candidate.getTotalSamples() > 0
            ? candidate.getSupportCount() / (double) candidate.getTotalSamples() : 0.0;
    }

    private static int trackDelta(ReviewItem.CandidateSnapshot candidate, int fileCount) {
        return fileCount < 0 || candidate.getTrackCount() <= 0
            ? Integer.MAX_VALUE : Math.abs(candidate.getTrackCount() - fileCount);
    }

    private static ReviewItem.CandidateSnapshot findCurrentCandidate(List<ReviewItem.CandidateSnapshot> candidates,
                                                                      String releaseId) {
        if (!hasText(releaseId)) return null;
        return candidates.stream().filter(c -> hasText(c.getReleaseId()) && releaseId.equals(c.getReleaseId()))
            .findFirst().orElse(null);
    }

    private static ReviewItem.OnlineCandidate bestOnline(List<ReviewItem.OnlineCandidate> candidates) {
        return candidates.stream().max(Comparator
            .comparingDouble(SystemSuggestionCalculator::onlineScore)
            .thenComparing(c -> c.getId() == null ? "" : c.getId())).orElse(null);
    }

    private static double onlineScore(ReviewItem.OnlineCandidate candidate) {
        // Use the weaker dimension: a source is only strong when both source confidence and local-track coverage are strong.
        return Math.min(clamp(candidate.getConfidence()), clamp(candidate.getTrackCoverage()));
    }

    private static double clamp(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }

    private static String quality(Double score) {
        if (score == null) return "NONE";
        if (score >= LOCAL_STRONG_THRESHOLD) return "STRONG";
        if (score >= LOCAL_MEDIUM_THRESHOLD) return "MEDIUM";
        return "WEAK";
    }

    private static boolean scoreAtLeast(Double score, double threshold) {
        return validScore(score) && score >= threshold;
    }

    private static boolean validScore(Double score) {
        return score != null && Double.isFinite(score) && score >= 0.0 && score <= 1.0;
    }

    private static boolean titlesConflict(String localTitle, String onlineTitle) {
        String local = normalizeTitle(localTitle);
        String online = normalizeTitle(onlineTitle);
        if (local.isEmpty() || online.isEmpty()) return false;
        return !(local.equals(online) || local.contains(online) || online.contains(local));
    }

    private static String normalizeTitle(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT)
            .replaceAll("[\\s\\-–—_:：/\\\\|·・]+", "");
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
