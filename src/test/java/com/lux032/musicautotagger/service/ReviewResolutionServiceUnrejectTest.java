package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ReviewItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReviewResolutionServiceUnrejectTest {
    @TempDir Path tempDir;
    private ReviewQueueService queue;
    private FakeProcessedFileLogger processedLogger;
    private ReviewResolutionService service;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        MusicConfig config = constructor.newInstance();
        config.setReviewQueuePath(tempDir.resolve("review-queue.json").toString());
        config.setReviewStagingDirectory(tempDir.resolve("staging").toString());
        config.setProcessedFileLogPath(tempDir.resolve("processed.log").toString());
        config.setDbType("file");
        queue = new ReviewQueueService(config);
        processedLogger = new FakeProcessedFileLogger(config);
        service = new ReviewResolutionService(config, queue, null, null, null, null, processedLogger);
    }

    @Test
    void restoresRejectedItemAndClearsResolutionNote() throws Exception {
        ReviewItem item = addItem("rejected", "album", ReviewItem.Status.REJECTED, 2);
        item.setResolutionNote("ignored before");

        ReviewItem result = service.unreject(item.getId());

        assertEquals(ReviewItem.Status.PENDING_REVIEW, result.getStatus());
        assertNull(result.getResolutionNote());
        assertEquals(2, processedLogger.removed.size());
        assertEquals(0, queue.countRejected());
    }

    @Test
    void rejectsRestoreWhenFolderAlreadyHasAnotherPendingItem() throws Exception {
        ReviewItem rejected = addItem("rejected", "same-folder", ReviewItem.Status.REJECTED, 1);
        addItem("pending", "same-folder", ReviewItem.Status.PENDING_REVIEW, 1);

        ReviewResolutionService.ResolutionException error = assertThrows(
            ReviewResolutionService.ResolutionException.class, () -> service.unreject(rejected.getId()));

        assertEquals(409, error.getHttpStatus());
        assertEquals("folder.already.pending", error.getMessage());
        assertEquals(ReviewItem.Status.REJECTED, rejected.getStatus());
        assertTrue(processedLogger.removed.isEmpty());
    }

    @Test
    void keepsRejectedWhenEveryProcessedRecordRemovalFails() throws Exception {
        ReviewItem item = addItem("rejected", "album", ReviewItem.Status.REJECTED, 2);
        item.setResolutionNote("keep this");
        processedLogger.failAll = true;

        ReviewResolutionService.ResolutionException error = assertThrows(
            ReviewResolutionService.ResolutionException.class, () -> service.unreject(item.getId()));

        assertEquals(503, error.getHttpStatus());
        assertEquals("unreject.log.failed", error.getMessage());
        assertEquals(ReviewItem.Status.REJECTED, item.getStatus());
        assertEquals("keep this", item.getResolutionNote());
    }

    @Test
    void continuesWhenOnlySomeProcessedRecordRemovalsFail() throws Exception {
        ReviewItem item = addItem("rejected", "album", ReviewItem.Status.REJECTED, 2);
        processedLogger.failName = "track-0.mp3";

        ReviewItem result = service.unreject(item.getId());

        assertEquals(ReviewItem.Status.PENDING_REVIEW, result.getStatus());
        assertEquals(1, processedLogger.removed.size());
    }

    @Test
    void restoresRejectedItemWithNoFiles() throws Exception {
        ReviewItem item = addItem("rejected", "empty-album", ReviewItem.Status.REJECTED, 0);

        ReviewItem result = assertDoesNotThrow(() -> service.unreject(item.getId()));

        assertEquals(ReviewItem.Status.PENDING_REVIEW, result.getStatus());
        assertTrue(processedLogger.removed.isEmpty());
    }

    private ReviewItem addItem(String id, String folderName, ReviewItem.Status status, int fileCount) throws Exception {
        ReviewItem item = new ReviewItem();
        item.setId(id);
        item.setFolderName(folderName);
        item.setFolderPath(tempDir.resolve(folderName).toString());
        item.setStatus(status);
        List<ReviewItem.FileEntry> files = new ArrayList<>();
        for (int i = 0; i < fileCount; i++) {
            ReviewItem.FileEntry entry = new ReviewItem.FileEntry();
            entry.setFileName("track-" + i + ".mp3");
            entry.setOriginalPath(tempDir.resolve(folderName).resolve(entry.getFileName()).toString());
            files.add(entry);
        }
        item.setFiles(files);

        Field itemsField = ReviewQueueService.class.getDeclaredField("items");
        itemsField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, ReviewItem> items = (Map<String, ReviewItem>) itemsField.get(queue);
        items.put(id, item);
        return item;
    }

    private static class FakeProcessedFileLogger extends ProcessedFileLogger {
        private final List<File> removed = new ArrayList<>();
        private boolean failAll;
        private String failName;

        private FakeProcessedFileLogger(MusicConfig config) {
            super(config, null);
        }

        @Override
        public void removeProcessedRecord(File file) {
            if (failAll || file.getName().equals(failName)) {
                throw new RuntimeException("simulated removal failure");
            }
            removed.add(file);
        }
    }
}
