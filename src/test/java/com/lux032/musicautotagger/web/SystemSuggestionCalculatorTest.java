package com.lux032.musicautotagger.web;

import com.lux032.musicautotagger.model.ReviewItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SystemSuggestionCalculatorTest {
    private static final double LLM_THRESHOLD = 0.85;

    @Test
    void strongLocalWithoutReleaseLevelCandidateRequiresExpand() {
        ReviewItem item = item();
        item.setConfidence(0.90);
        item.setCandidates(List.of(candidate(null, "rg", "Album", null, false)));
        assertSuggestion(item, "NEEDS_EXPAND", "EXPAND");
    }

    @Test
    void weakLocalAndStrongOnlineConfirmsOnline() {
        ReviewItem item = item();
        item.setConfidence(0.50);
        item.setOnlineSearchedAt(1L);
        item.setOnlineCandidates(List.of(online("o1", "Album", 0.92, 0.90)));
        assertSuggestion(item, "CONFIRM_ONLINE", "CONFIRM_ONLINE");
    }

    @Test
    void twoStrongSignalsWithDifferentTitlesReportConflict() {
        ReviewItem item = item();
        item.setCandidates(List.of(candidate("r1", "rg", "Alpha", 0.95, false)));
        item.setOnlineCandidates(List.of(online("o1", "Beta", 0.94, 0.92)));
        assertSuggestion(item, "CONFLICT", "CONFIRM_LOCAL");
    }

    @Test
    void staleStrongOnlineEvidenceRequestsRefresh() {
        ReviewItem item = item();
        item.setConfidence(0.40);
        item.setOnlineSearchedAt(1L);
        item.setOnlineEvidenceStale(true);
        item.setOnlineCandidates(List.of(online("o1", "Album", 0.95, 0.95)));
        assertSuggestion(item, "TRY_ONLINE_SEARCH", "ONLINE_SEARCH");
    }

    @Test
    void staleEvidenceWithMediumLocalScoreStillRequestsRefresh() {
        ReviewItem item = item();
        item.setOnlineSearchedAt(1L);
        item.setOnlineEvidenceStale(true);
        item.setCandidates(List.of(candidate("r1", "rg", "Album", 0.80, false)));
        assertSuggestion(item, "TRY_ONLINE_SEARCH", "ONLINE_SEARCH");
    }

    @Test
    void highFolderConfidenceWithoutDurationEvidenceNeverConfirmsCandidate() {
        ReviewItem item = item();
        item.setConfidence(0.95);
        item.setOnlineSearchedAt(1L);
        item.setCandidates(List.of(candidate("r1", "rg", "Album", null, false)));
        Map<String, Object> result = evaluate(item);
        assertNotEquals("CONFIRM_LOCAL", result.get("level"));
        assertNotEquals("CONFIRM_LOCAL", result.get("primaryAction"));
        assertEquals("MEDIUM", result.get("localQuality"));
    }

    @Test
    void noSignalsAfterCompletedSearchIsWeakAll() {
        ReviewItem item = item();
        item.setOnlineSearchedAt(1L);
        assertSuggestion(item, "WEAK_ALL", "NONE");
    }

    @Test
    void llmUnreleasedDirectionSuggestsArchive() {
        ReviewItem item = item();
        item.setLlmSuggestion(llm(0, null, true, 0.90));
        Map<String, Object> result = evaluate(item);
        assertEquals("SUPPORTS_UNRELEASED", result.get("llmDirection"));
        assertEquals("SUGGEST_ARCHIVE_UNVERIFIED", result.get("level"));
        assertEquals("ARCHIVE", result.get("primaryAction"));
    }

    @Test
    void llmNoneWithoutUnreleasedIsInconclusive() {
        ReviewItem item = item();
        item.setOnlineSearchedAt(1L);
        item.setLlmSuggestion(llm(0, null, false, 0.95));
        Map<String, Object> result = evaluate(item);
        assertEquals("INCONCLUSIVE", result.get("llmDirection"));
        assertEquals("WEAK_ALL", result.get("level"));
    }

    @Test
    void allCandidatesConfirmedNoReleaseSuggestArchive() {
        ReviewItem item = item();
        item.setCandidates(List.of(candidate(null, "rg", "Album", null, true)));
        Map<String, Object> result = evaluate(item);
        assertEquals(Boolean.TRUE, result.get("allCandidatesConfirmedNoRelease"));
        assertEquals("SUGGEST_ARCHIVE_UNVERIFIED", result.get("level"));
        assertEquals("ARCHIVE", result.get("primaryAction"));
    }

    @Test
    void resolvedStatusReturnsNoActionImmediately() {
        ReviewItem item = item();
        item.setStatus(ReviewItem.Status.CONFIRMED);
        assertSuggestion(item, "RESOLVED", "NONE");
    }

    @Test
    void zeroTotalSamplesDoesNotBreakTieBreaking() {
        ReviewItem item = item();
        ReviewItem.CandidateSnapshot b = candidate("b", "rg", "Album", 0.90, false);
        ReviewItem.CandidateSnapshot a = candidate("a", "rg", "Album", 0.90, false);
        b.setTotalSamples(0);
        a.setTotalSamples(0);
        item.setCandidates(List.of(b, a));
        Map<String, Object> params = params(evaluate(item));
        assertEquals("a", params.get("releaseId"));
    }

    @Test
    void matchingHighConfidenceLlmCandidateBecomesExecutable() {
        ReviewItem item = item();
        item.setOnlineSearchedAt(1L);
        item.setCandidates(List.of(candidate("current", "rg", "Album", 0.70, false)));
        item.setLlmSuggestion(llm(1, "current", false, 0.90));
        Map<String, Object> result = evaluate(item);
        assertEquals("SUPPORTS_CANDIDATE", result.get("llmDirection"));
        assertEquals("CONFIRM_LOCAL", result.get("level"));
        assertEquals("CONFIRM_LOCAL", result.get("primaryAction"));
        assertEquals("current", params(result).get("releaseId"));
    }

    @Test
    void matchingLowConfidenceLlmCandidateDoesNotBecomeExecutable() {
        ReviewItem item = item();
        item.setOnlineSearchedAt(1L);
        item.setCandidates(List.of(candidate("current", "rg", "Album", 0.70, false)));
        item.setLlmSuggestion(llm(1, "current", false, 0.80));
        Map<String, Object> result = evaluate(item);
        assertEquals("SUPPORTS_CANDIDATE", result.get("llmDirection"));
        assertNotEquals("CONFIRM_LOCAL", result.get("level"));
        assertNotEquals("current", params(result).get("releaseId"));
    }

    @Test
    void staleLlmReleaseIdIsNeverPassedThrough() {
        ReviewItem item = item();
        item.setOnlineSearchedAt(1L);
        item.setCandidates(List.of(candidate("current", "rg", "Album", 0.70, false)));
        item.setLlmSuggestion(llm(1, "missing", false, 0.99));
        Map<String, Object> result = evaluate(item);
        assertEquals("INCONCLUSIVE", result.get("llmDirection"));
        assertEquals("WEAK_ALL", result.get("level"));
        assertFalse(params(result).containsValue("missing"));
    }

    @Test
    void neverSearchedOnlineIsExplicitBoolean() {
        Map<String, Object> result = evaluate(item());
        assertEquals(Boolean.TRUE, result.get("neverSearchedOnline"));
        assertSuggestion(item(), "TRY_ONLINE_SEARCH", "ONLINE_SEARCH");
    }

    private static ReviewItem item() {
        ReviewItem item = new ReviewItem();
        item.setStatus(ReviewItem.Status.PENDING_REVIEW);
        item.setFiles(new ArrayList<>());
        item.setCandidates(new ArrayList<>());
        item.setOnlineCandidates(new ArrayList<>());
        return item;
    }

    private static ReviewItem.CandidateSnapshot candidate(String releaseId, String groupId, String title,
                                                            Double similarity, boolean noRelease) {
        ReviewItem.CandidateSnapshot candidate = new ReviewItem.CandidateSnapshot();
        candidate.setReleaseId(releaseId);
        candidate.setReleaseGroupId(groupId);
        candidate.setTitle(title);
        candidate.setDurationSimilarity(similarity);
        candidate.setConfirmedNoRelease(noRelease);
        return candidate;
    }

    private static ReviewItem.OnlineCandidate online(String id, String title, double confidence, double coverage) {
        ReviewItem.OnlineCandidate candidate = new ReviewItem.OnlineCandidate();
        candidate.setId(id);
        candidate.setTitle(title);
        candidate.setConfidence(confidence);
        candidate.setTrackCoverage(coverage);
        return candidate;
    }

    private static ReviewItem.LlmSuggestion llm(int choice, String releaseId, boolean unreleased, double confidence) {
        ReviewItem.LlmSuggestion suggestion = new ReviewItem.LlmSuggestion();
        suggestion.setChoiceIndex(choice);
        suggestion.setSuggestedReleaseId(releaseId);
        suggestion.setUnreleasedCompilation(unreleased);
        suggestion.setConfidence(confidence);
        return suggestion;
    }

    private static Map<String, Object> evaluate(ReviewItem item) {
        return SystemSuggestionCalculator.evaluate(item, LLM_THRESHOLD);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Map<String, Object> result) {
        return (Map<String, Object>) result.get("primaryActionParams");
    }

    private static void assertSuggestion(ReviewItem item, String level, String action) {
        Map<String, Object> result = evaluate(item);
        assertEquals(level, result.get("level"));
        assertEquals(action, result.get("primaryAction"));
    }
}
