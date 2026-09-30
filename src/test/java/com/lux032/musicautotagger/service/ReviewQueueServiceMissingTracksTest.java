package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReviewQueueServiceMissingTracksTest {
    @TempDir Path tempDir;
    private MusicConfig config;
    private ReviewQueueService queue;
    private Path albumDir;
    private File audioFile;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        config = constructor.newInstance();
        config.setReviewQueuePath(tempDir.resolve("review-queue.json").toString());
        config.setReviewStagingDirectory(tempDir.resolve("staging").toString());
        queue = new ReviewQueueService(config);
        albumDir = Files.createDirectory(tempDir.resolve("アトック"));
        audioFile = Files.write(albumDir.resolve("02. 金魚草.flac"), new byte[]{1, 2, 3}).toFile();
    }

    private FolderAlbumCache.MissingTracksInfo missing() {
        FolderAlbumCache.MissingTracksInfo info = new FolderAlbumCache.MissingTracksInfo(
            "rg-atok", "r-atok", "アトック", "藍井エイル", "single", false, "2021", 1, 2, 1,
            FolderAlbumCache.MissingTracksInfo.DETECTED_AT_TRACK);
        info.addUnmatchedFile("02. 金魚草.flac", "金魚草");
        return info;
    }

    private ReviewItem enqueue(AtomicInteger listenerCalls) {
        if (listenerCalls != null) queue.setMissingTracksEnqueuedListener(item -> listenerCalls.incrementAndGet());
        MusicMetadata md = new MusicMetadata();
        md.setTitle("金魚草");
        md.setDuration(268);
        FolderAlbumCache.PendingFile pending = new FolderAlbumCache.PendingFile(audioFile, audioFile, null, md, null);
        return queue.enqueueMissingTracks(albumDir.toString(), List.of(pending), null, new ArrayList<>(),
            List.of(251, 268), missing());
    }

    @Test
    void missingTracksItemCarriesKindDetailAndClosestCandidateFirst() {
        AtomicInteger calls = new AtomicInteger();
        ReviewItem item = enqueue(calls);

        assertEquals(ReviewItem.Kind.TRACKS_MISSING, item.getKind());
        assertEquals("r-atok", item.getMissingTracks().getReleaseId());
        assertEquals("金魚草", item.getMissingTracks().getUnmatchedFiles().get("02. 金魚草.flac"));
        assertEquals("r-atok", item.getCandidates().get(0).getReleaseId());
        assertEquals(1, queue.countPending(ReviewItem.Kind.TRACKS_MISSING));
        assertEquals(0, queue.countPending(ReviewItem.Kind.ALBUM_UNRESOLVED));
        assertEquals(1, calls.get(), "入队后应通知自动联网搜索");
        assertTrue(queue.isFolderUnderReview(albumDir.toString()));
    }

    @Test
    void kindAndDetailSurviveRestart() {
        enqueue(null);
        ReviewQueueService reloaded = new ReviewQueueService(config);
        ReviewItem item = reloaded.list(ReviewItem.Status.PENDING_REVIEW).get(0);
        assertEquals(ReviewItem.Kind.TRACKS_MISSING, item.effectiveKind());
        assertEquals("アトック", item.getMissingTracks().getAlbumTitle());
        assertEquals(1, reloaded.pendingMissingTracksWithoutAutoSearch().size(), "重启后应补跑自动搜索");
    }

    @Test
    void delayedAutoSearchNeverRecreatesResolvedItem() {
        ReviewItem item = enqueue(null);
        queue.markResolved(item, ReviewItem.Status.CONFIRMED, "人工确认: アトック");

        // 排队中的自动搜索晚到：不能按文件夹新建条目
        assertNull(queue.prepareOnlineSearchForItem(item.getId(), albumDir.toString(), "hash"));
        assertFalse(queue.updateIfPending(item));
        assertFalse(queue.isFolderUnderReview(albumDir.toString()));
        assertEquals(0, queue.countPending());
    }

    @Test
    void lateSearchResultNeverTouchesResolvedItem() {
        ReviewItem item = enqueue(null);
        queue.markResolved(item, ReviewItem.Status.CONFIRMED, "人工确认: アトック");

        java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean();
        assertFalse(queue.updatePending(item.getId(), target -> {
            invoked.set(true);
            target.setResolutionNote("联网搜索完成");
            return true;
        }));
        assertFalse(invoked.get(), "已处理的条目不能被迟到的搜索结果修改");
        assertEquals("人工确认: アトック", queue.get(item.getId()).getResolutionNote());
    }

    @Test
    void resolveWaitsForInFlightSearchWriteBack() throws Exception {
        // 搜索结果写回在队列锁内进行：人工确认只能排在它之前或之后，不会交错
        ReviewItem item = enqueue(null);
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread search = new Thread(() -> queue.updatePending(item.getId(), target -> {
            inside.countDown();
            try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            target.setResolutionNote("联网搜索完成");
            return true;
        }));
        search.start();
        assertTrue(inside.await(5, java.util.concurrent.TimeUnit.SECONDS));

        Thread resolve = new Thread(() -> queue.markResolved(item, ReviewItem.Status.CONFIRMED, "人工确认"));
        try {
            resolve.start();
            resolve.join(300);
            assertTrue(resolve.isAlive(), "确认必须等待正在进行的写回");
        } finally {
            release.countDown();
            search.join(5000);
            resolve.join(5000);
        }
        assertEquals(ReviewItem.Status.CONFIRMED, queue.get(item.getId()).getStatus());
        assertEquals("人工确认", queue.get(item.getId()).getResolutionNote());
    }

    @Test
    void autoSearchTargetMustMatchFolder() {
        ReviewItem item = enqueue(null);
        assertNull(queue.prepareOnlineSearchForItem(item.getId(), "/other/folder", "hash"));
        assertNotNull(queue.prepareOnlineSearchForItem(item.getId(), albumDir.toString(), "hash"));
    }

    @Test
    void legacyItemWithoutKindIsAlbumUnresolved() {
        ReviewItem legacy = new ReviewItem();
        assertNull(legacy.getKind());
        assertEquals(ReviewItem.Kind.ALBUM_UNRESOLVED, legacy.effectiveKind());
    }
}
