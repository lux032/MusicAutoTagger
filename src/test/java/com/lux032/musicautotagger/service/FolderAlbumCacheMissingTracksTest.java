package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 缺曲判定的状态路径：用桩 MusicBrainz 客户端，不发网络请求。
 */
class FolderAlbumCacheMissingTracksTest {

    /** 只返回预置的版本时长，不访问网络 */
    static class StubMusicBrainzClient extends MusicBrainzClient {
        final Map<String, List<AlbumDurationResult>> releases = new HashMap<>();

        StubMusicBrainzClient(MusicConfig config) {
            super(config);
        }

        @Override
        public List<AlbumDurationResult> getAllReleaseDurationSequences(String releaseGroupId) {
            return new ArrayList<>(releases.getOrDefault(releaseGroupId, List.of()));
        }
    }

    private StubMusicBrainzClient mb;
    private FolderAlbumCache cache;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        mb = new StubMusicBrainzClient(constructor.newInstance());
        cache = new FolderAlbumCache(new DurationSequenceService(), mb, null);
    }

    private static MusicBrainzClient.AlbumDurationResult release(String id, String title, int trackCount, List<Integer> durations) {
        return new MusicBrainzClient.AlbumDurationResult(durations, id, title, trackCount, "Digital Media",
            "single", false, "Artist");
    }

    private static List<FolderAlbumCache.CandidateReleaseGroup> candidates(String rg, String title) {
        return List.of(new FolderAlbumCache.CandidateReleaseGroup(rg, title));
    }

    private static FolderAlbumCache.AlbumIdentificationInfo sample(String rg, String title, int trackCount) {
        return new FolderAlbumCache.AlbumIdentificationInfo(rg, title, "Artist", trackCount, "", "single", false,
            candidates(rg, title));
    }

    @Test
    void firstFileFastPathNeverConcludesMissingTracks() {
        // 大合集的第一首只查到一张 5 首的单曲：快速通道只能排除候选，不能判缺曲
        String folder = "/music/Compilation";
        List<Integer> local = new ArrayList<>();
        for (int i = 0; i < 20; i++) local.add(200 + i * 7);
        cache.cacheFolderDurationSequence(folder, local);
        mb.releases.put("rg-single", List.of(release("r-single", "Single", 5, local.subList(0, 5))));

        assertNull(cache.determineAlbumWithDurationSequence(folder, candidates("rg-single", "Single"), 20, true));
        assertNull(cache.getMissingTracksInfo(folder));
        assertFalse(cache.isFolderUnresolved(folder), "后续样本仍需有机会匹配到正确专辑");
    }

    @Test
    void multiSampleMarksSingleWithMissingCouplingTrack() {
        // アトック：本地 2 首，MB 单曲只收录 1 首
        String folder = "/music/アトック";
        cache.cacheFolderDurationSequence(folder, List.of(251, 268));
        mb.releases.put("rg-atok", List.of(release("r-atok", "アトック", 1, List.of(251))));

        FolderAlbumCache.CachedAlbumInfo result = cache.addSample(folder, "01.flac", 2,
            sample("rg-atok", "アトック", 1), 2, true);

        assertNull(result, "缺曲时不能自动锁定");
        FolderAlbumCache.MissingTracksInfo missing = cache.getMissingTracksInfo(folder);
        assertNotNull(missing);
        assertEquals("r-atok", missing.getReleaseId());
        assertEquals(1, missing.getUnmatchedLocalCount());
        assertTrue(cache.isFolderUnresolved(folder));
    }

    @Test
    void wildlyMismatchedReleaseIsNotTreatedAsMissingTracks() {
        // 20 首对 1 首：根本不是这张专辑，不锁定也不判缺曲
        String folder = "/music/Other";
        List<Integer> local = new ArrayList<>();
        for (int i = 0; i < 20; i++) local.add(200 + i * 7);
        cache.cacheFolderDurationSequence(folder, local);
        mb.releases.put("rg-x", List.of(release("r-x", "X", 1, List.of(200))));

        assertNull(cache.addSample(folder, "01.flac", 20, sample("rg-x", "X", 1), 1, true));
        assertNull(cache.getMissingTracksInfo(folder));
    }

    @Test
    void releaseWithSomeTracksLackingDurationIsNotMissing() {
        // MB 上 10 首里只有 9 首有时长：本地完整的 10 首不能被判缺曲
        String folder = "/music/Album";
        List<Integer> local = new ArrayList<>();
        for (int i = 0; i < 10; i++) local.add(180 + i * 13);
        cache.cacheFolderDurationSequence(folder, local);
        mb.releases.put("rg-a", List.of(release("r-a", "Album", 10, local.subList(0, 9))));

        cache.addSample(folder, "01.flac", 10, sample("rg-a", "Album", 10), 1, true);
        assertNull(cache.getMissingTracksInfo(folder));
        assertFalse(cache.isFolderUnresolved(folder));
    }

    @Test
    void missingTracksJudgementWaitsForInFlightAuthorizedWrite() throws Exception {
        // 写入线程在文件夹锁内拿到授权并写入；判缺曲必须等它写完，不能插在「检查」与「写入」之间
        String folder = "/music/Race";
        java.util.concurrent.CountDownLatch authorized = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseWriter = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean writerSawAllowed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean markedWhileWriting = new java.util.concurrent.atomic.AtomicBoolean();

        Thread writer = new Thread(() -> {
            synchronized (cache.folderLock(folder)) {
                writerSawAllowed.set(cache.isAutomaticWriteAllowed(folder));
                authorized.countDown();
                try {
                    releaseWriter.await(10, java.util.concurrent.TimeUnit.SECONDS);   // 模拟正在写文件
                } catch (InterruptedException ignored) { }
                markedWhileWriting.set(cache.isFolderUnresolved(folder));
            }
        });
        writer.start();
        assertTrue(authorized.await(5, java.util.concurrent.TimeUnit.SECONDS));

        Thread judge = new Thread(() -> cache.markFolderTracksMissing(folder, new FolderAlbumCache.MissingTracksInfo(
            "rg", "r", "A", "B", "single", false, "", 1, 2, 1,
            FolderAlbumCache.MissingTracksInfo.DETECTED_AT_TRACK)));
        try {
            judge.start();
            judge.join(300);
            assertTrue(judge.isAlive(), "写入期间判缺曲必须等待文件夹锁");
        } finally {
            // 断言失败也要放行，避免留下永远等待的线程
            releaseWriter.countDown();
            writer.join(5000);
            judge.join(5000);
        }
        assertTrue(writerSawAllowed.get());
        assertFalse(markedWhileWriting.get(), "写入过程中状态不能被改成缺曲");
        assertTrue(cache.isFolderUnresolved(folder));
        assertFalse(cache.isAutomaticWriteAllowed(folder), "判定之后的写入必须被拒绝");
    }

    @Test
    void automaticLockIsRejectedAfterMissingTracksButManualIsAccepted() {
        String folder = "/music/アトック";
        cache.markFolderTracksMissing(folder, new FolderAlbumCache.MissingTracksInfo(
            "rg", "r", "アトック", "藍井エイル", "single", false, "", 1, 2, 1,
            FolderAlbumCache.MissingTracksInfo.DETECTED_AT_LOCK));

        cache.setFolderAlbum(folder, new FolderAlbumCache.CachedAlbumInfo("rg", "r", "アトック", "藍井エイル",
            1, "", "single", false, 0.99, FolderAlbumCache.CacheSource.DURATION_SEQUENCE));
        assertNull(cache.peekFolderAlbum(folder), "判缺曲后不得再自动锁定（并发线程晚到的结果）");
        assertFalse(cache.isAutomaticWriteAllowed(folder));

        cache.clearFolderUnresolved(folder);
        cache.forceSetFolderAlbum(folder, new FolderAlbumCache.CachedAlbumInfo("rg", "r", "アトック", "藍井エイル",
            1, "", "single", false, 1.0, FolderAlbumCache.CacheSource.MANUAL_CONFIRMED));
        assertTrue(cache.isAutomaticWriteAllowed(folder));
        assertNull(cache.getMissingTracksInfo(folder));
    }
}
