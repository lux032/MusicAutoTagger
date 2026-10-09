package com.lux032.musicautotagger.service;

import com.google.gson.Gson;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ReviewItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReviewQueueServiceOrderingTest {
    @TempDir Path tempDir;
    private MusicConfig config;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        config = constructor.newInstance();
        config.setReviewQueuePath(tempDir.resolve("review-queue.json").toString());
        config.setReviewStagingDirectory(tempDir.resolve("staging").toString());
    }

    @Test
    void sortsByCreationTimeRatherThanStorageOrUpdateOrder() throws Exception {
        ReviewItem old = item("old", 100, ReviewItem.Status.PENDING_REVIEW);
        old.setUpdatedAt(10000);
        ReviewItem newest = item("newest", 300, ReviewItem.Status.PENDING_REVIEW);
        ReviewItem middle = item("middle", 200, ReviewItem.Status.PENDING_REVIEW);
        ReviewQueueService queue = load(List.of(old, newest, middle));

        assertEquals(List.of("newest", "middle", "old"), ids(queue.list(null)));
        assertEquals(List.of("newest", "middle", "old"),
            ids(queue.list(ReviewItem.Status.PENDING_REVIEW)));
        // 查询不能重排磁盘数据；重启后仍遵循相同规则。
        assertEquals(List.of("newest", "middle", "old"),
            ids(new ReviewQueueService(config).list(null)));
        assertEquals(List.of("old", "newest", "middle"), ids(List.of(new Gson().fromJson(
            Files.readString(Path.of(config.getReviewQueuePath())), ReviewItem[].class))));
    }

    @Test
    void equalOrMissingCreationTimesUseReverseInsertionOrderConsistently() throws Exception {
        ReviewQueueService queue = load(List.of(
            item("legacy-first", 0, ReviewItem.Status.PENDING_REVIEW),
            item("first", 100, ReviewItem.Status.PENDING_REVIEW),
            item("second", 100, ReviewItem.Status.PENDING_REVIEW),
            item("legacy-second", 0, ReviewItem.Status.PENDING_REVIEW)));

        List<String> expected = List.of("second", "first", "legacy-second", "legacy-first");
        assertEquals(expected, ids(queue.list(null)));
        assertEquals(expected, ids(queue.list(null)));
        assertEquals(expected, ids(new ReviewQueueService(config).list(null)));
    }

    @Test
    void statusFiltersAndBothKindsKeepNewestFirst() throws Exception {
        ReviewItem oldMissing = item("old-missing", 100, ReviewItem.Status.PENDING_REVIEW);
        oldMissing.setKind(ReviewItem.Kind.TRACKS_MISSING);
        ReviewItem newMissing = item("new-missing", 300, ReviewItem.Status.PENDING_REVIEW);
        newMissing.setKind(ReviewItem.Kind.TRACKS_MISSING);
        ReviewQueueService queue = load(List.of(
            oldMissing,
            item("old-album", 150, ReviewItem.Status.PENDING_REVIEW),
            item("old-rejected", 160, ReviewItem.Status.REJECTED),
            newMissing,
            item("new-album", 350, ReviewItem.Status.PENDING_REVIEW),
            item("new-rejected", 360, ReviewItem.Status.REJECTED)));

        assertEquals(List.of("new-rejected", "old-rejected"),
            ids(queue.list(ReviewItem.Status.REJECTED)));
        List<ReviewItem> pending = queue.list(ReviewItem.Status.PENDING_REVIEW);
        assertEquals(List.of("new-missing", "old-missing"), ids(pending.stream()
            .filter(i -> i.effectiveKind() == ReviewItem.Kind.TRACKS_MISSING).toList()));
        assertEquals(List.of("new-album", "old-album"), ids(pending.stream()
            .filter(i -> i.effectiveKind() == ReviewItem.Kind.ALBUM_UNRESOLVED).toList()));
        assertEquals(List.of(), queue.list(ReviewItem.Status.CONFIRMED));
    }

    @Test
    void newlyEnqueuedItemAppearsFirstWithoutReorderingExistingItems() throws Exception {
        ReviewQueueService queue = load(List.of(
            item("old", 100, ReviewItem.Status.PENDING_REVIEW)));
        ReviewItem added = queue.enqueueRecoveryFolder(tempDir.resolve("new-album").toString(),
            null, List.of(), null, "hash");

        assertEquals(List.of(added.getId(), "old"), ids(queue.list(null)));
        assertEquals(List.of(added.getId(), "old"),
            ids(new ReviewQueueService(config).list(null)));
    }

    private ReviewQueueService load(List<ReviewItem> items) throws Exception {
        Files.writeString(Path.of(config.getReviewQueuePath()), new Gson().toJson(items));
        return new ReviewQueueService(config);
    }

    private static ReviewItem item(String id, long createdAt, ReviewItem.Status status) {
        ReviewItem item = new ReviewItem();
        item.setId(id);
        item.setFolderPath("folder-" + id);
        item.setCreatedAt(createdAt);
        item.setStatus(status);
        return item;
    }

    private static List<String> ids(List<ReviewItem> items) {
        return items.stream().map(ReviewItem::getId).toList();
    }
}
