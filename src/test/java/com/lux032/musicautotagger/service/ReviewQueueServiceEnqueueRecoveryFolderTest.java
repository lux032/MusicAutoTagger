package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReviewQueueServiceEnqueueRecoveryFolderTest {
    @TempDir Path tempDir;
    private ReviewQueueService queue;
    private Path albumDir;
    private java.io.File audioFile;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        MusicConfig config = constructor.newInstance();
        config.setReviewQueuePath(tempDir.resolve("review-queue.json").toString());
        config.setReviewStagingDirectory(tempDir.resolve("staging").toString());
        queue = new ReviewQueueService(config);
        albumDir = Files.createDirectory(tempDir.resolve("album"));
        audioFile = Files.write(albumDir.resolve("track.flac"), new byte[]{1, 2, 3}).toFile();
    }

    @Test
    void newReviewSearchBuildsFilesWithoutRecoveryOwnership() {
        ReviewItem item = queue.enqueueRecoveryFolder(albumDir.toString(), null,
            List.of(audioFile), null, "hash");

        assertEquals(1, item.getFiles().size());
        assertEquals(audioFile.getAbsolutePath(), item.getFiles().get(0).getOriginalPath());
        assertNull(item.getRecoverySourceType());
        assertNull(item.getRecoverySourcePath());
    }

    @Test
    void newRecoverySearchBuildsFilesAndSetsRecoveryOwnership() {
        ReviewItem item = queue.enqueueRecoveryFolder(albumDir.toString(), "FAILED",
            List.of(audioFile), null, "hash");

        assertEquals(1, item.getFiles().size());
        assertEquals("FAILED", item.getRecoverySourceType());
        assertEquals(albumDir.toString(), item.getRecoverySourcePath());
    }

    @Test
    void existingReviewItemKeepsOriginalFilesAndAppendsReason() {
        java.io.File original = audioFile;
        java.io.File different = albumDir.resolve("different.flac").toFile();
        FolderAlbumCache.PendingFile pending = new FolderAlbumCache.PendingFile(
            original, original, null, new MusicMetadata(), null);
        ReviewItem existing = queue.enqueue(albumDir.toString(), List.of(pending), null,
            List.of(), List.of(), "原始原因", 0.5);
        List<ReviewItem.FileEntry> originalEntries = existing.getFiles();

        ReviewItem result = queue.enqueueRecoveryFolder(albumDir.toString(), null,
            List.of(different), null, "hash");

        assertSame(existing, result);
        assertSame(originalEntries, result.getFiles());
        assertEquals(original.getAbsolutePath(), result.getFiles().get(0).getOriginalPath());
        assertTrue(result.getReason().contains("联网辅助识别"));
        assertNull(result.getRecoverySourceType());
    }

    @Test
    void existingRecoveryItemKeepsOwnershipWhenSourceTypeIsNull() {
        ReviewItem existing = queue.enqueueRecoveryFolder(albumDir.toString(), "FAILED",
            List.of(audioFile), null, "first-hash");

        ReviewItem result = queue.enqueueRecoveryFolder(albumDir.toString(), null,
            List.of(audioFile), null, "second-hash");

        assertSame(existing, result);
        assertEquals("FAILED", result.getRecoverySourceType());
        assertEquals(albumDir.toString(), result.getRecoverySourcePath());
    }
}
