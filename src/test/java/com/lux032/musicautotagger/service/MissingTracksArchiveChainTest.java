package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 缺曲文件夹按最接近的版本归档的完整链路（关闭人工确认时的自动兜底，人工确认后同样走这条写入链路）：
 * 版本里有的歌用版本曲目号；不在版本里的歌、指纹未识别的歌作为附加曲目，互不覆盖。
 */
class MissingTracksArchiveChainTest {
    @TempDir Path dir;

    private static final String RELEASE_JSON = "{\"date\":\"2021-02-24\",\"media\":[{\"position\":1,\"format\":\"Digital Media\",\"tracks\":["
        + "{\"id\":\"t1\",\"position\":\"1\",\"title\":\"アトック\",\"length\":3000,"
        + "\"recording\":{\"id\":\"rec-atok\",\"title\":\"アトック\","
        + "\"artist-credit\":[{\"artist\":{\"id\":\"a1\",\"name\":\"藍井エイル\",\"sort-name\":\"Aoi, Eir\"}}]}}]}]}";

    /** 不访问网络：锁定版本来自内置 JSON */
    static class StubClient extends MusicBrainzClient {
        final JsonNode release;

        StubClient(MusicConfig config) throws Exception {
            super(config);
            release = new ObjectMapper().readTree(RELEASE_JSON);
        }

        @Override
        public TrackMatch matchTrackInRelease(String releaseId, String releaseGroupId, String recordingId,
                                              String recordingTitle, int seconds, String album, String artist) {
            return matchTrackInReleaseNode(release, releaseId, releaseGroupId, recordingId, recordingTitle,
                seconds, album, artist);
        }

        @Override
        public ReleaseTagBundle fetchReleaseTagBundle(String releaseId) {
            return null;
        }
    }

    private MusicConfig config;
    private FolderAlbumCache cache;
    private AlbumBatchProcessor processor;
    private Path folder;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        config = constructor.newInstance();
        config.setAutoRename(true);
        config.setOutputDirectory(dir.resolve("out").toString());
        config.setExportLyricsToFile(false);
        config.setDbType("file");
        config.setProcessedFileLogPath(dir.resolve("processed.log").toString());
        config.setReviewEnabled(false);

        StubClient mb = new StubClient(config);
        cache = new FolderAlbumCache(new DurationSequenceService(), mb, null);
        processor = new AlbumBatchProcessor(config, cache, new TagWriterService(config),
            new ProcessedFileLogger(config, null), new CoverArtService(null, mb));
        processor.setMusicBrainzClient(mb);
        folder = Files.createDirectories(dir.resolve("src").resolve("アトック"));
    }

    /** 3 秒的 WAV；fill 区分内容 */
    private File wav(String name, int seconds, byte fill) throws Exception {
        int dataLen = 8000 * seconds;
        ByteBuffer b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + dataLen).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
            .putInt(8000).putInt(8000).putShort((short) 1).putShort((short) 8);
        b.put("data".getBytes()).putInt(dataLen);
        for (int i = 0; i < dataLen; i++) b.put(fill);
        Path p = folder.resolve(name);
        Files.write(p, b.array());
        return p.toFile();
    }

    private void pending(File file, String recordingId, String title, int duration, String trackNo) {
        MusicMetadata md = new MusicMetadata();
        md.setRecordingId(recordingId);
        md.setTitle(title);
        md.setArtist("藍井エイル");
        md.setDuration(duration);
        md.setTrackNo(trackNo);
        cache.addPendingFile(folder.toString(), file, file, null, md, null);
    }

    @Test
    void lockReachedMidCollectionFlushesBackloggedPending() throws Exception {
        // 前两首在收集中积压（其中一首指纹未识别），第三首快速锁定完整版本：
        // 写第三首时必须把积压的两首按同一专辑一起写出，而不是挂到关机
        pending(wav("01. アトック.wav", 3, (byte) 1), "rec-atok", "アトック", 3, "1");
        pending(wav("02. unknown.wav", 10, (byte) 2), null, "アトック (Live)", 10, "2");
        cache.setFolderAlbum(folder.toString(), new FolderAlbumCache.CachedAlbumInfo("rg-atok", "r-atok",
            "アトック", "藍井エイル", 1, "2021-02-24", "single", false, 0.99,
            FolderAlbumCache.CacheSource.DURATION_SEQUENCE));

        File third = wav("03. third.wav", 20, (byte) 3);
        MusicMetadata md = new MusicMetadata();
        md.setTitle("Third");
        md.setArtist("藍井エイル");
        md.setAlbumArtist("藍井エイル");
        md.setAlbum("アトック");
        md.setExtraTrack(true);

        assertEquals(2, processor.writeWithFolderLock(folder.toString(), third, third, md, null, false));
        assertEquals(0, processor.getPendingFileCount(folder.toString()), "积压的待处理文件必须已写出");

        Path albumDir = dir.resolve("out").resolve("藍井エイル").resolve("アトック");
        String written;
        try (var walk = Files.walk(dir.resolve("out"))) {
            written = walk.map(p -> dir.resolve("out").relativize(p).toString()).sorted()
                .collect(java.util.stream.Collectors.joining(", "));
        }
        assertTrue(Files.exists(albumDir.resolve("01. 藍井エイル - アトック.wav")), written);
        assertTrue(Files.exists(albumDir.resolve("藍井エイル - アトック (Live).wav")),
            "指纹未识别、版本里找不到的曲目作为附加曲目写出");
        assertTrue(Files.exists(albumDir.resolve("藍井エイル - Third.wav")));
    }

    @Test
    void failureHandoffRejectsLatePendingUntilHumanTakesOver() throws Exception {
        String f = folder.toString();
        synchronized (cache.folderLock(f)) {
            cache.markFailureHandoff(f);
        }
        File late = wav("04. late.wav", 3, (byte) 4);
        MusicMetadata md = new MusicMetadata();
        md.setTitle("late");
        assertFalse(processor.addPendingFile(f, late, late, null, md, null),
            "整个文件夹交给失败管线后，并发线程晚到的待处理文件必须被拒绝");
        assertEquals(0, processor.getPendingFileCount(f));

        cache.clearFailureHandoff(f);
        assertTrue(processor.addPendingFile(f, late, late, null, md, null), "人工接管后恢复待处理必须可用");
    }

    @Test
    void closestReleaseArchiveKeepsEverySongDistinct() throws Exception {
        // 前两首都是 3 秒：旧逻辑会把第二首按时长硬塞成「アトック」并互相覆盖；
        // 第三首指纹未识别且时长与版本对不上，应成为附加曲目
        pending(wav("01. アトック.wav", 3, (byte) 1), "rec-atok", "アトック", 3, "1");
        pending(wav("02. 金魚草.wav", 3, (byte) 2), "rec-kingyo", "金魚草", 3, "2");
        pending(wav("03. unknown.wav", 10, (byte) 3), null, "アトック (Instrumental)", 10, "3");

        cache.markFolderTracksMissing(folder.toString(), new FolderAlbumCache.MissingTracksInfo(
            "rg-atok", "r-atok", "アトック", "藍井エイル", "single", false, "2021-02-24", 1, 3, 2,
            FolderAlbumCache.MissingTracksInfo.DETECTED_AT_LOCK));

        AlbumBatchProcessor.BatchProcessResult result = processor.processPendingFilesAsUnresolvedAlbum(folder.toString());

        assertEquals(3, result.getSuccessCount());
        Path albumDir = dir.resolve("out").resolve("藍井エイル").resolve("アトック");
        Set<String> names = new TreeSet<>();
        try (var stream = Files.list(albumDir)) {
            stream.forEach(p -> names.add(p.getFileName().toString()));
        }
        assertEquals(Set.of(
            "1.01 藍井エイル - アトック.wav",
            "藍井エイル - 金魚草.wav",
            "藍井エイル - アトック (Instrumental).wav"), names);

        String extraTrack = AudioFileIO.read(albumDir.resolve("藍井エイル - 金魚草.wav").toFile())
            .getTag().getFirst(FieldKey.TRACK);
        assertTrue(extraTrack == null || extraTrack.isEmpty(), "附加曲目不写曲目号");
        assertEquals(1, Files.readAllBytes(albumDir.resolve("1.01 藍井エイル - アトック.wav"))[100],
            "版本里的那首必须是真正的アトック，没有被其他歌覆盖");
    }
}
