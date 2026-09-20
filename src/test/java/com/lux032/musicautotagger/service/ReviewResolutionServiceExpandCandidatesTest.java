package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ReviewItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReviewResolutionServiceExpandCandidatesTest {
    @TempDir Path tempDir;
    private MusicConfig config;
    private ReviewQueueService queue;
    private FakeMusicBrainzClient musicBrainz;
    private ReviewResolutionService service;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        config = constructor.newInstance();
        config.setReviewQueuePath(tempDir.resolve("review-queue.json").toString());
        config.setReviewStagingDirectory(tempDir.resolve("staging").toString());
        musicBrainz = new FakeMusicBrainzClient(config);
        queue = new ReviewQueueService(config);
        service = new ReviewResolutionService(config, queue, null, null, musicBrainz,
            new DurationSequenceService(), null);
    }

    @Test
    void expandsMultipleReleasesWithDurations() throws Exception {
        ReviewItem item = addItem(List.of("rg"), List.of(100, 200));
        musicBrainz.returnFor("rg", List.of(release("r1", List.of(100, 200)), release("r2", List.of(101, 201))));

        ReviewItem result = service.expandCandidates(item.getId());

        assertTrue(result.isCandidatesExpanded());
        assertEquals(2, result.getCandidates().size());
        assertTrue(result.getCandidates().stream().allMatch(c -> c.getReleaseId() != null));
        assertTrue(result.getCandidates().stream().noneMatch(ReviewItem.CandidateSnapshot::isConfirmedNoRelease));
    }

    @Test
    void expandsNightbreakReleasesEvenWhenAllDurationsAreEmpty() throws Exception {
        ReviewItem item = addItem(List.of("rg"), List.of(100, 200));
        musicBrainz.returnFor("rg", List.of(release("r1", List.of()), release("r2", List.of()), release("r3", List.of())));

        ReviewItem result = assertDoesNotThrow(() -> service.expandCandidates(item.getId()));

        assertTrue(result.isCandidatesExpanded());
        assertEquals(3, result.getCandidates().size());
        assertTrue(result.getCandidates().stream().allMatch(c -> c.getReleaseId() != null));
        assertTrue(result.getCandidates().stream().allMatch(c -> c.getDurationSimilarity() == null));
    }

    @Test
    void keepsAllMixedDurationReleasesAndScoresOnlyUsableOnes() throws Exception {
        ReviewItem item = addItem(List.of("rg"), List.of(100, 200));
        musicBrainz.returnFor("rg", List.of(release("with", List.of(100, 200)), release("without", List.of())));

        ReviewItem result = service.expandCandidates(item.getId());

        assertEquals(2, result.getCandidates().size());
        ReviewItem.CandidateSnapshot with = find(result, "with");
        ReviewItem.CandidateSnapshot without = find(result, "without");
        assertNotNull(with.getDurationSimilarity());
        assertNull(without.getDurationSimilarity());
    }

    @Test
    void marksConfirmedEmptyGroupAndAllowsJudgementPrecondition() throws Exception {
        ReviewItem item = addItem(List.of("empty"), List.of(100));
        musicBrainz.returnFor("empty", List.of());

        ReviewItem result = service.expandCandidates(item.getId());

        assertTrue(result.isCandidatesExpanded());
        assertTrue(result.getCandidates().get(0).isConfirmedNoRelease());
        Method method = ReviewResolutionService.class.getDeclaredMethod("requireExpandedForJudgement", ReviewItem.class);
        method.setAccessible(true);
        assertDoesNotThrow(() -> invoke(method, service, result));
    }

    @Test
    void keepsIoFailurePendingForRetry() throws Exception {
        ReviewItem item = addItem(List.of("failed"), List.of(100));
        musicBrainz.failFor("failed");

        ReviewItem result = service.expandCandidates(item.getId());

        assertFalse(result.isCandidatesExpanded());
        assertEquals(1, result.getCandidates().size());
        assertNull(result.getCandidates().get(0).getReleaseId());
        assertFalse(result.getCandidates().get(0).isConfirmedNoRelease());
    }

    @Test
    void mixedGroupsPreserveEachIndependentOutcome() throws Exception {
        ReviewItem item = addItem(List.of("empty", "success", "failed"), List.of(100));
        musicBrainz.returnFor("empty", List.of());
        musicBrainz.returnFor("success", List.of(release("release-ok", List.of(100))));
        musicBrainz.failFor("failed");

        ReviewItem result = service.expandCandidates(item.getId());

        assertFalse(result.isCandidatesExpanded());
        assertTrue(result.getCandidates().stream().anyMatch(c -> "empty".equals(c.getReleaseGroupId()) && c.isConfirmedNoRelease()));
        assertTrue(result.getCandidates().stream().anyMatch(c -> "release-ok".equals(c.getReleaseId())));
        assertTrue(result.getCandidates().stream().anyMatch(c -> "failed".equals(c.getReleaseGroupId())
            && c.getReleaseId() == null && !c.isConfirmedNoRelease()));
    }

    private ReviewItem addItem(List<String> groups, List<Integer> folderDurations) throws Exception {
        ReviewItem item = new ReviewItem();
        item.setId("item-" + System.nanoTime());
        item.setFolderName("album");
        item.setFolderPath(tempDir.resolve("album").toString());
        item.setStatus(ReviewItem.Status.PENDING_REVIEW);
        item.setDurationSequence(new ArrayList<>(folderDurations));
        List<ReviewItem.CandidateSnapshot> candidates = new ArrayList<>();
        for (String group : groups) {
            ReviewItem.CandidateSnapshot candidate = new ReviewItem.CandidateSnapshot();
            candidate.setReleaseGroupId(group);
            candidate.setTitle(group);
            candidates.add(candidate);
        }
        item.setCandidates(candidates);
        Field itemsField = ReviewQueueService.class.getDeclaredField("items");
        itemsField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, ReviewItem> items = (Map<String, ReviewItem>) itemsField.get(queue);
        items.put(item.getId(), item);
        return item;
    }

    private static MusicBrainzClient.AlbumDurationResult release(String id, List<Integer> durations) {
        return new MusicBrainzClient.AlbumDurationResult(new ArrayList<>(durations), id, id, durations.size(),
            "Digital Media", "album", false, "Artist");
    }

    private static ReviewItem.CandidateSnapshot find(ReviewItem item, String releaseId) {
        return item.getCandidates().stream().filter(c -> releaseId.equals(c.getReleaseId())).findFirst().orElseThrow();
    }

    private static Object invoke(Method method, Object target, Object argument) throws Throwable {
        try {
            return method.invoke(target, argument);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static final class FakeMusicBrainzClient extends MusicBrainzClient {
        private final Map<String, List<AlbumDurationResult>> results = new HashMap<>();
        private final List<String> failures = new ArrayList<>();

        FakeMusicBrainzClient(MusicConfig config) { super(config); }
        void returnFor(String group, List<AlbumDurationResult> releases) { results.put(group, releases); }
        void failFor(String group) { failures.add(group); }

        @Override
        public List<AlbumDurationResult> getReleasesForGroup(String releaseGroupId) throws IOException {
            if (failures.contains(releaseGroupId)) throw new IOException("simulated failure");
            return results.getOrDefault(releaseGroupId, List.of());
        }
    }
}
