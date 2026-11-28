package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.util.FileSystemUtils;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryServiceOnlineCreditsTest {

    @TempDir Path tempDir;

    private MusicConfig config;
    private Path monitorDir;
    private Path outputDir;
    private Path partialDir;
    private ReviewQueueService reviewQueue;
    private TagWriterService tagWriter;
    private ProcessedFileLogger processedLogger;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        config = ctor.newInstance();
        monitorDir = Files.createDirectories(tempDir.resolve("monitor"));
        outputDir = Files.createDirectories(tempDir.resolve("output"));
        partialDir = Files.createDirectories(tempDir.resolve("partial"));
        config.setMonitorDirectory(monitorDir.toString());
        config.setOutputDirectory(outputDir.toString());
        config.setPartialDirectory(partialDir.toString());
        config.setFailedDirectory(tempDir.resolve("failed").toString());
        config.setReviewQueuePath(tempDir.resolve("data/review-queue.json").toString());
        config.setReviewStagingDirectory(tempDir.resolve("data/review-staging").toString());
        config.setRecoveryWorkDirectory(tempDir.resolve("data/recovery-work").toString());
        config.setRecoveryTrashDirectory(tempDir.resolve("data/recovery-trash").toString());
        config.setProcessedFileLogPath(tempDir.resolve("processed.log").toString());
        config.setAutoRename(true);
        config.setExportLyricsToFile(false);
        config.setCoverMissingRequireReview(false);

        reviewQueue = new ReviewQueueService(config);
        tagWriter = new TagWriterService(config);
        processedLogger = new ProcessedFileLogger(config, null);
    }

    @Test
    void confirmOnlineAppliesFourTierMergeClearsExplicitEmptyAndSkipsLowConfidenceCredits() throws Exception {
        Path albumDir = Files.createDirectory(monitorDir.resolve("MixedCreditsAlbum"));
        // 4 files -> meetsCoverage allows 3/4 confident matches (track 4 stays low confidence)
        File f1 = createFlac(albumDir.resolve("01. First.flac"), "First", "Singer", "Old", "1",
            "Local C1", "Local L1", null, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        File f2 = createFlac(albumDir.resolve("02. Second.flac"), "Second", "Singer", "Old", "2",
            "Old C2", "Old L2", "Old A2", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        File f3 = createFlac(albumDir.resolve("03. Third.flac"), "Third", "Singer", "Old", "3",
            null, null, null, null);
        File f4 = createFlac(albumDir.resolve("04. Bonus.flac"), "Unrelated Local", "Singer", "Old", "99",
            null, null, null, null);

        // Track 1 has MB composer (+ MBID) in review queue; Track 2/3/4 have no MB credits
        MusicMetadata mb1 = new MusicMetadata();
        mb1.setRecordingId("11111111-1111-4111-8111-111111111111");
        mb1.setTitle("First");
        mb1.setArtist("Singer");
        mb1.setComposer("MB Composer 1");
        mb1.setComposers(List.of("MB Composer 1"));
        mb1.setComposerIds(List.of("cccccccc-cccc-4ccc-8ccc-cccccccccccc"));
        mb1.setLyricist("Local L1"); // merged fallback from source at enqueue time

        FolderAlbumCache.PendingFile pf1 = new FolderAlbumCache.PendingFile(f1, f1, null, mb1, null);
        FolderAlbumCache.PendingFile pf2 = new FolderAlbumCache.PendingFile(f2, f2, null, tagWriter.readTags(f2), null);
        FolderAlbumCache.PendingFile pf3 = new FolderAlbumCache.PendingFile(f3, f3, null, tagWriter.readTags(f3), null);
        FolderAlbumCache.PendingFile pf4 = new FolderAlbumCache.PendingFile(f4, f4, null, tagWriter.readTags(f4), null);
        ReviewItem item = reviewQueue.enqueue(albumDir.toString(), List.of(pf1, pf2, pf3, pf4),
            null, List.of(), List.of(120, 120, 120, 120), "test", 0.5);

        ReviewItem.OnlineCandidate cand = new ReviewItem.OnlineCandidate();
        cand.setId("cand-mixed");
        cand.setTitle("Final Album");
        cand.setArtist("Singer");
        cand.setAlbumArtist("Singer");
        cand.setTracks(List.of(
            onlineTrack(1, 1, "First", "Singer", List.of("Online C1"), List.of("Online L1"), List.of("梶浦由記")),
            onlineTrack(1, 2, "Second", "Singer", List.of("Online C2"), List.of("Online L2"), List.of("Online A2")),
            onlineTrack(1, 3, "Third", "Singer", List.of("澤野弘之"), List.of("cAnON."), List.of("澤野弘之")),
            // Track 4: completely mismatched track number & title -> matchConfidence < 0.6
            onlineTrack(1, 4, "Completely Different Song", "Singer", List.of("Wrong Composer"), List.of("Wrong Lyricist"), List.of("Wrong Arranger"))
        ));
        item.setOnlineCandidates(List.of(cand));
        OnlineIdentificationService online = new OnlineIdentificationService(config, reviewQueue, tagWriter, null, null);
        item.setEvidenceHash(online.evidenceHashForFolder(albumDir.toFile()));
        reviewQueue.update(item);

        // Manual override on Track 2: override composer (clears old MBID), explicitly clear lyricist, leave arranger untouched
        ReviewItem.TrackCreditsOverride override2 = new ReviewItem.TrackCreditsOverride();
        override2.setComposers(List.of("手動作曲"));
        override2.setLyricists(List.of());
        override2.setArrangers(null);

        AudioFingerprintService stubFp = new AudioFingerprintService(config) {
            @Override
            public List<Integer> extractDurationSequence(List<File> files) {
                return List.of(120, 120, 120, 120);
            }
        };

        try (RecoveryService recovery = new RecoveryService(
                config, null, processedLogger, reviewQueue, new FolderAlbumCache(null, null, null), null,
                new FileSystemUtils(config), tagWriter, stubFp, null, null)) {
            recovery.confirmOnlineCandidate(item.getId(), cand.getId(),
                "Final Album", "Singer", "2026-05-01", "", null, null,
                RecoveryService.DuplicatePolicy.ASK, null, true,
                Map.of(f2.getAbsolutePath(), override2));
        }

        // Track 1: MB Composer (+ MBID), Local Lyricist (beats online), Online Arranger (fills gap, no MBID)
        MusicMetadata out1 = tagWriter.readTags(outputDir.resolve("Singer/Final Album/1.01 Singer - First.flac").toFile());
        assertNotNull(out1);
        assertEquals("MB Composer 1", out1.getComposer());
        assertEquals(List.of("cccccccc-cccc-4ccc-8ccc-cccccccccccc"), out1.getComposerIds());
        assertEquals("Local L1", out1.getLyricist());
        assertEquals("梶浦由記", out1.getArranger());
        assertEquals(List.of("梶浦由記"), out1.getArrangers());
        assertNull(out1.getArrangerIds());

        // Track 2: Manual Composer (old MBID cleared), Lyricist explicitly deleted, Local Arranger preserved
        MusicMetadata out2 = tagWriter.readTags(outputDir.resolve("Singer/Final Album/1.02 Singer - Second.flac").toFile());
        assertNotNull(out2);
        assertEquals("手動作曲", out2.getComposer());
        assertEquals(List.of("手動作曲"), out2.getComposers());
        assertNull(out2.getComposerIds(), "Manual override must clear stale composer MBID");
        assertNull(out2.getLyricist(), "Explicit empty manual list must delete existing lyricist tag");
        assertNull(out2.getLyricists());
        assertEquals("Old A2", out2.getArranger());

        // Track 3: All empty locally -> filled from confident online match
        MusicMetadata out3 = tagWriter.readTags(outputDir.resolve("Singer/Final Album/1.03 Singer - Third.flac").toFile());
        assertNotNull(out3);
        assertEquals("澤野弘之", out3.getComposer());
        assertEquals("cAnON.", out3.getLyricist());
        assertEquals("澤野弘之", out3.getArranger());

        // Track 4: Low confidence match -> online credits MUST NOT be applied
        MusicMetadata out4 = tagWriter.readTags(outputDir.resolve("Singer/Final Album/99. Singer - Unrelated Local.flac").toFile());
        assertNotNull(out4);
        assertNull(out4.getComposer());
        assertNull(out4.getLyricist());
        assertNull(out4.getArranger());
    }

    @Test
    void singleFileRecoveryReidentifyPreservesMusicBrainzCreditsWhenConfirmedOnline() throws Exception {
        // Original isolated file has NO credits
        File isolated = createFlac(partialDir.resolve("01. SingleSong.flac"), "SingleSong", "Artist", "Old", "1",
            null, null, null, null);
        // Simulate single-<jobId> temp file path recorded during REIDENTIFY enqueue
        Path singleDir = Files.createDirectories(tempDir.resolve("data/recovery-work/single-job-999"));
        Path stagedCopy = Files.copy(isolated.toPath(), singleDir.resolve("01. SingleSong.flac"));

        MusicMetadata mbMeta = new MusicMetadata();
        mbMeta.setRecordingId("22222222-2222-4222-8222-222222222222");
        mbMeta.setTitle("SingleSong");
        mbMeta.setArtist("Artist");
        mbMeta.setComposer("梶浦由記");
        mbMeta.setComposers(List.of("梶浦由記"));
        mbMeta.setComposerIds(List.of("dddddddd-dddd-4ddd-8ddd-dddddddddddd"));
        mbMeta.setLyricist("梶浦由記");
        mbMeta.setLyricists(List.of("梶浦由記"));
        mbMeta.setLyricistIds(List.of("dddddddd-dddd-4ddd-8ddd-dddddddddddd"));
        mbMeta.setArranger("森空青");
        mbMeta.setArrangers(List.of("森空青"));

        FolderAlbumCache.PendingFile pf = new FolderAlbumCache.PendingFile(
            stagedCopy.toFile(), stagedCopy.toFile(), null, mbMeta, null);
        ReviewItem item = reviewQueue.enqueue(singleDir.toString(), List.of(pf), null, List.of(), List.of(120), "test", 0.5);
        // RecoveryService.runJob links recoverySourceType & recoverySourcePath to the original isolation file
        item.setRecoverySourceType(RecoveryService.SourceType.PARTIAL.name());
        item.setRecoverySourcePath(isolated.getAbsolutePath());

        ReviewItem.OnlineCandidate cand = new ReviewItem.OnlineCandidate();
        cand.setId("cand-single");
        cand.setTitle("Single Album");
        cand.setArtist("Artist");
        cand.setAlbumArtist("Artist");
        cand.setTracks(List.of(onlineTrack(1, 1, "SingleSong", "Artist", List.of("Fallback C"), List.of(), List.of())));
        item.setOnlineCandidates(List.of(cand));
        OnlineIdentificationService online = new OnlineIdentificationService(config, reviewQueue, tagWriter, null, null);
        item.setEvidenceHash(online.evidenceHashForFolder(isolated));
        reviewQueue.update(item);

        AudioFingerprintService stubFp = new AudioFingerprintService(config) {
            @Override
            public List<Integer> extractDurationSequence(List<File> files) {
                return List.of(120);
            }
        };

        try (RecoveryService recovery = new RecoveryService(
                config, null, processedLogger, reviewQueue, new FolderAlbumCache(null, null, null), null,
                new FileSystemUtils(config), tagWriter, stubFp, null, null)) {
            recovery.confirmOnlineCandidate(item.getId(), cand.getId(),
                "Single Album", "Artist", "2026-06-01", "", null, null,
                RecoveryService.DuplicatePolicy.ASK, null, true, null);
        }

        MusicMetadata out = tagWriter.readTags(outputDir.resolve("Artist/Single Album/1.01 Artist - SingleSong.flac").toFile());
        assertNotNull(out);
        assertEquals("梶浦由記", out.getComposer());
        assertEquals(List.of("dddddddd-dddd-4ddd-8ddd-dddddddddddd"), out.getComposerIds());
        assertEquals("梶浦由記", out.getLyricist());
        assertEquals(List.of("dddddddd-dddd-4ddd-8ddd-dddddddddddd"), out.getLyricistIds());
        assertEquals("森空青", out.getArranger());
    }

    @Test
    void confirmOnlineBlocksConflictAndProbableWhenApplyProbableDisabled() throws Exception {
        config.setOnlineCreditsApplyProbable(false);
        Path albumDir = Files.createDirectory(monitorDir.resolve("StatusAdmissionAlbum"));
        File flac = createFlac(albumDir.resolve("01. VAMPY.flac"), "VAMPY", "HIMEHINA", "Old", "1",
            null, null, null, null);

        ReviewItem item = reviewQueue.enqueueRecoveryFolder(albumDir.toString(), null, List.of(flac), tagWriter, null);
        ReviewItem.OnlineCandidate cand = new ReviewItem.OnlineCandidate();
        cand.setId("cand-status");
        cand.setTitle("VAMPY");
        cand.setArtist("HIMEHINA");
        cand.setAlbumArtist("HIMEHINA");

        ReviewItem.OnlineTrack track = onlineTrack(1, 1, "VAMPY", "HIMEHINA",
            List.of("Soma Genda"), List.of("Gohgo"), List.of("Wrong Conflict"));
        ReviewItem.TrackCreditDetail detail = new ReviewItem.TrackCreditDetail();
        ReviewItem.CreditFieldResult comp = new ReviewItem.CreditFieldResult();
        comp.setStatus(ReviewItem.CreditStatus.VERIFIED);
        comp.setNames(List.of("Soma Genda"));
        detail.setComposers(comp);

        ReviewItem.CreditFieldResult lyr = new ReviewItem.CreditFieldResult();
        lyr.setStatus(ReviewItem.CreditStatus.PROBABLE);
        lyr.setNames(List.of("Gohgo"));
        detail.setLyricists(lyr);

        ReviewItem.CreditFieldResult arr = new ReviewItem.CreditFieldResult();
        arr.setStatus(ReviewItem.CreditStatus.CONFLICT);
        arr.setNames(List.of("Wrong Conflict"));
        detail.setArrangers(arr);
        track.setCreditDetail(detail);

        cand.setTracks(List.of(track));
        item.setOnlineCandidates(List.of(cand));
        OnlineIdentificationService online = new OnlineIdentificationService(config, reviewQueue, tagWriter, null, null);
        item.setEvidenceHash(online.evidenceHashForFolder(albumDir.toFile()));
        reviewQueue.update(item);

        AudioFingerprintService stubFp = new AudioFingerprintService(config) {
            @Override
            public List<Integer> extractDurationSequence(List<File> files) {
                return List.of(120);
            }
        };

        try (RecoveryService recovery = new RecoveryService(
                config, null, processedLogger, reviewQueue, new FolderAlbumCache(null, null, null), null,
                new FileSystemUtils(config), tagWriter, stubFp, null, null)) {
            recovery.confirmOnlineCandidate(item.getId(), cand.getId(),
                "VAMPY", "HIMEHINA", "2026-11-27", "", null, null,
                RecoveryService.DuplicatePolicy.ASK, null, true, null);
        }

        MusicMetadata out = tagWriter.readTags(outputDir.resolve("HIMEHINA/VAMPY/1.01 HIMEHINA - VAMPY.flac").toFile());
        assertNotNull(out);
        assertEquals("Soma Genda", out.getComposer(), "VERIFIED field must be written");
        assertNull(out.getLyricist(), "PROBABLE field must NOT be auto-written when online.credits.applyProbable=false");
        assertNull(out.getArranger(), "CONFLICT field must NEVER be auto-written");
    }

    private static ReviewItem.OnlineTrack onlineTrack(int disc, int track, String title, String artist,
                                                      List<String> composers, List<String> lyricists, List<String> arrangers) {
        ReviewItem.OnlineTrack t = new ReviewItem.OnlineTrack();
        t.setDiscNo(disc);
        t.setTrackNo(track);
        t.setTitle(title);
        t.setArtist(artist);
        t.setComposers(composers);
        t.setLyricists(lyricists);
        t.setArrangers(arrangers);
        t.setDuration(120);
        return t;
    }

    private static File createFlac(Path target, String title, String artist, String album, String trackNo,
                                   String composer, String lyricist, String arranger, String composerMbid) throws Exception {
        Files.createDirectories(target.getParent());
        ByteBuffer bytes = ByteBuffer.allocate(554);
        bytes.put(new byte[]{'f', 'L', 'a', 'C', (byte) 128, 0, 0, 34});
        bytes.putLong(18, (44100L << 44) | (1L << 41) | (15L << 36) | 5303025L);
        for (int i = 42; i < bytes.capacity(); i++) bytes.put(i, (byte) (i * 7 + trackNo.hashCode()));
        Files.write(target, bytes.array());
        var audio = AudioFileIO.read(target.toFile());
        Tag tag = audio.getTagOrCreateAndSetDefault();
        tag.setField(FieldKey.TITLE, title);
        tag.setField(FieldKey.ARTIST, artist);
        tag.setField(FieldKey.ALBUM, album);
        tag.setField(FieldKey.ALBUM_ARTIST, artist);
        tag.setField(FieldKey.TRACK, trackNo);
        if (composer != null) {
            tag.setField(FieldKey.COMPOSER, composer);
            ((org.jaudiotagger.tag.flac.FlacTag) tag).setField("COMPOSERS", composer);
        }
        if (composerMbid != null) {
            ((org.jaudiotagger.tag.flac.FlacTag) tag).setField("MUSICBRAINZ_COMPOSERID", composerMbid);
        }
        if (lyricist != null) {
            tag.setField(FieldKey.LYRICIST, lyricist);
            ((org.jaudiotagger.tag.flac.FlacTag) tag).setField("LYRICISTS", lyricist);
        }
        if (arranger != null) {
            tag.setField(FieldKey.ARRANGER, arranger);
            ((org.jaudiotagger.tag.flac.FlacTag) tag).setField("ARRANGERS", arranger);
        }
        audio.commit();
        return target.toFile();
    }
}
