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

    @Test
    void confirmOnlineWritesAlignedArtistAndCreatorMbidsDropsPartialAndNeverFabricatesAlbumIds() throws Exception {
        String idKajiura = "11111111-1111-4111-8111-111111111111";
        String idMori = "22222222-2222-4222-8222-222222222222";
        String idVocal = "33333333-3333-4333-8333-333333333333";

        Path albumDir = Files.createDirectory(monitorDir.resolve("AlignedMbidsAlbum"));
        File flac = createFlac(albumDir.resolve("01. Horizon.flac"), "Horizon", "Old Artist", "Old", "1",
            null, null, "Old Arranger", null);
        // 写入一个旧编曲 MBID，用来验证部分命中时旧编曲 MBID 会被清除而不残留
        var preAudio = AudioFileIO.read(flac);
        ((org.jaudiotagger.tag.flac.FlacTag) preAudio.getTag()).setField(
            "MUSICBRAINZ_ARRANGERID", "99999999-9999-4999-8999-999999999999");
        preAudio.commit();

        config.setCollapseAlbumArtistToVariousArtists(false);
        ReviewItem item = reviewQueue.enqueueRecoveryFolder(albumDir.toString(), null, List.of(flac), tagWriter, null);
        ReviewItem.OnlineCandidate cand = new ReviewItem.OnlineCandidate();
        cand.setId("cand-mbids");
        cand.setTitle("Horizon EP");
        cand.setArtist("梶浦由記, Vocal A");
        cand.setArtists(List.of("梶浦由記", "Vocal A"));
        cand.setAlbumArtist("梶浦由記, Vocal A");
        cand.setAlbumArtists(List.of("梶浦由記", "Vocal A"));

        ReviewItem.OnlineTrack track = onlineTrack(1, 1, "Horizon", "梶浦由記, Vocal A",
            List.of("梶浦由記", "森空青"), List.of("梶浦由記"), List.of("森空青", "Unmatched Newcomer"));
        track.setArtists(List.of("梶浦由記", "Vocal A"));
        cand.setTracks(List.of(track));

        // 仅 梶浦由記 / Vocal A / 森空青 为 RESOLVED，Unmatched Newcomer 为 NOT_FOUND
        cand.setEntityMbids(Map.of(
            "梶浦由記", resolvedEntity("梶浦由記", idKajiura, "Kajiura, Yuki"),
            "vocal a", resolvedEntity("Vocal A", idVocal, "Vocal A"),
            "森空青", resolvedEntity("森空青", idMori, "Mori, Sorao")
        ));
        item.setOnlineCandidates(List.of(cand));
        OnlineIdentificationService online = new OnlineIdentificationService(config, reviewQueue, tagWriter, null, null);
        item.setEvidenceHash(online.evidenceHashForFolder(albumDir.toFile()));
        reviewQueue.update(item);

        // 覆盖 track 编曲为 ["森空青", "Unmatched Newcomer"] 以走部分命中逻辑（原文件已有 Old Arranger）
        ReviewItem.TrackCreditsOverride arrOverride = new ReviewItem.TrackCreditsOverride();
        arrOverride.setArrangers(List.of("森空青", "Unmatched Newcomer"));

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
                "Horizon EP", "梶浦由記, Vocal A", "2026-08-01", "", null, null,
                RecoveryService.DuplicatePolicy.ASK, List.of("梶浦由記", "Vocal A"), true,
                Map.of(flac.getAbsolutePath(), arrOverride), null);
        }

        File outFile = outputDir.resolve("梶浦由記, Vocal A/Horizon EP/1.01 梶浦由記, Vocal A - Horizon.flac").toFile();
        assertTrue(outFile.isFile());
        MusicMetadata out = tagWriter.readTags(outFile);
        assertNotNull(out);
        assertEquals(idKajiura + "; " + idVocal, out.getAlbumArtistId());
        assertEquals("Kajiura, Yuki; Vocal A", out.getAlbumArtistSort());
        assertEquals(idKajiura + "; " + idVocal, out.getArtistId());
        assertEquals("Kajiura, Yuki; Vocal A", out.getArtistSort());
        assertEquals(List.of(idKajiura, idMori), out.getComposerIds());
        assertEquals(List.of(idKajiura), out.getLyricistIds());
        assertEquals(List.of("森空青", "Unmatched Newcomer"), out.getArrangers());
        assertNull(out.getArrangerIds(), "Partial multi-arranger match must drop arranger MBID and clear stale ID");

        // 严禁写入任何虚构的专辑级或录音级 MBID
        Tag rawTag = AudioFileIO.read(outFile).getTag();
        assertEquals("", rawTag.getFirst(FieldKey.MUSICBRAINZ_RELEASEID));
        assertEquals("", rawTag.getFirst(FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID));
        assertEquals("", rawTag.getFirst(FieldKey.MUSICBRAINZ_TRACK_ID));
        assertEquals("", ((org.jaudiotagger.tag.flac.FlacTag) rawTag).getFirst("MUSICBRAINZ_ARRANGERID"));
    }

    @Test
    void confirmOnlineClearsStaleArtistIdsOnOverwriteOrVaCollapseAndRetainsUnmatchedTrackArtistId() throws Exception {
        String staleAlbumArtistId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
        String staleTrackArtistId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
        String keptLocalArtistId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

        Path albumDir = Files.createDirectory(monitorDir.resolve("StaleCleanupAlbum"));
        File f1 = createFlacWithArtistIds(albumDir.resolve("01. First.flac"), "First", "Old Singer 1", "Old", "1",
            staleTrackArtistId, "Old, Singer 1", staleAlbumArtistId, "Old, Album");
        File f2 = createFlacWithArtistIds(albumDir.resolve("02. Second.flac"), "Second", "Old Singer 2", "Old", "2",
            staleTrackArtistId, "Old, Singer 2", staleAlbumArtistId, "Old, Album");
        File f3 = createFlacWithArtistIds(albumDir.resolve("03. Third.flac"), "Third", "Old Singer 3", "Old", "3",
            staleTrackArtistId, "Old, Singer 3", staleAlbumArtistId, "Old, Album");
        File f4 = createFlacWithArtistIds(albumDir.resolve("04. Bonus.flac"), "Unrelated Local", "Local Artist", "Old", "99",
            keptLocalArtistId, "Artist, Local", staleAlbumArtistId, "Old, Album");

        config.setCollapseAlbumArtistToVariousArtists(true);
        ReviewItem item = reviewQueue.enqueueRecoveryFolder(albumDir.toString(), null, List.of(f1, f2, f3, f4), tagWriter, null);
        ReviewItem.OnlineCandidate cand = new ReviewItem.OnlineCandidate();
        cand.setId("cand-cleanup");
        cand.setTitle("Compilation Album");
        cand.setArtist("Artist A, Artist B");
        cand.setAlbumArtist("Artist A, Artist B");
        cand.setAlbumArtists(List.of("Artist A", "Artist B"));
        cand.setTracks(List.of(
            onlineTrack(1, 1, "First", "Unresolved Artist 1", List.of(), List.of(), List.of()),
            onlineTrack(1, 2, "Second", "Unresolved Artist 2", List.of(), List.of(), List.of()),
            onlineTrack(1, 3, "Third", "Unresolved Artist 3", List.of(), List.of(), List.of()),
            onlineTrack(1, 4, "Totally Different Track", "Other Artist", List.of(), List.of(), List.of())
        ));
        item.setOnlineCandidates(List.of(cand));
        OnlineIdentificationService online = new OnlineIdentificationService(config, reviewQueue, tagWriter, null, null);
        item.setEvidenceHash(online.evidenceHashForFolder(albumDir.toFile()));
        reviewQueue.update(item);

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
                "Compilation Album", "Artist A, Artist B", "2026-09-01", "", null, null,
                RecoveryService.DuplicatePolicy.ASK, List.of("Artist A", "Artist B"), true, null, null);
        }

        // 折叠为 Various Artists -> 必须清除旧 MUSICBRAINZ_RELEASEARTISTID；改写了曲目艺人无有效 MBID -> 必须清除旧 MUSICBRAINZ_ARTISTID
        MusicMetadata out1 = tagWriter.readTags(
            outputDir.resolve("Various Artists/Compilation Album/1.01 Unresolved Artist 1 - First.flac").toFile());
        assertNotNull(out1);
        assertEquals("Various Artists", out1.getAlbumArtist());
        assertNull(out1.getAlbumArtistId(), "VA collapse must clear stale MUSICBRAINZ_RELEASEARTISTID");
        assertNull(out1.getAlbumArtistSort());
        assertEquals("Unresolved Artist 1", out1.getArtist());
        assertNull(out1.getArtistId(), "Overwritten track artist without MBID must clear stale MUSICBRAINZ_ARTISTID");
        assertNull(out1.getArtistSort());

        // 未达可靠匹配阈值的第 4 轨：保留原本地艺人及既有 MUSICBRAINZ_ARTISTID，仅专辑艺人折叠清理
        MusicMetadata out4 = tagWriter.readTags(
            outputDir.resolve("Various Artists/Compilation Album/99. Local Artist - Unrelated Local.flac").toFile());
        assertNotNull(out4);
        assertEquals("Local Artist", out4.getArtist());
        assertEquals(keptLocalArtistId, out4.getArtistId(), "Unmatched track retaining local artist must preserve existing MUSICBRAINZ_ARTISTID");
        assertNull(out4.getAlbumArtistId());
    }

    @Test
    void confirmOnlineAppliesEntityMbidOverridesRejectsInvalidAndPersistsAcrossMissingCoverRestart() throws Exception {
        String idAuto = "11111111-1111-4111-8111-111111111111";
        String idChosen = "22222222-2222-4222-8222-222222222222";

        Path albumDir = Files.createDirectory(monitorDir.resolve("MissingCoverMbidAlbum"));
        File flac = createFlac(albumDir.resolve("01. Song.flac"), "Song", "Ambiguous Singer", "Old", "1",
            null, null, null, null);

        config.setCoverMissingRequireReview(true);
        ReviewItem item = reviewQueue.enqueueRecoveryFolder(albumDir.toString(), null, List.of(flac), tagWriter, null);
        ReviewItem.OnlineCandidate cand = new ReviewItem.OnlineCandidate();
        cand.setId("cand-mc-mbid");
        cand.setTitle("Deferred Album");
        cand.setArtist("Ambiguous Singer");
        cand.setAlbumArtist("Ambiguous Singer");
        cand.setTracks(List.of(onlineTrack(1, 1, "Song", "Ambiguous Singer", List.of("Auto Composer"), List.of(), List.of())));

        ReviewItem.EntityMbidResolution ambiguousRes = new ReviewItem.EntityMbidResolution();
        ambiguousRes.setDisplayName("Ambiguous Singer");
        ambiguousRes.setNormalizedKey("ambiguous singer");
        ambiguousRes.setStatus(ReviewItem.EntityMbidStatus.AMBIGUOUS);
        ReviewItem.EntityMbidCandidate c1 = new ReviewItem.EntityMbidCandidate();
        c1.setMbid(idChosen);
        c1.setName("Ambiguous Singer");
        c1.setSortName("Singer, Ambiguous");
        ambiguousRes.setCandidates(List.of(c1));

        cand.setEntityMbids(Map.of(
            "ambiguous singer", ambiguousRes,
            "auto composer", resolvedEntity("Auto Composer", idAuto, "Composer, Auto")
        ));
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
            // 非法 UUID 必须被拒绝
            java.io.IOException invalidEx = assertThrows(java.io.IOException.class, () ->
                recovery.confirmOnlineCandidate(item.getId(), cand.getId(),
                    "Deferred Album", "Ambiguous Singer", "2026-10-01", "", null, null,
                    RecoveryService.DuplicatePolicy.ASK, null, false, null,
                    Map.of("Ambiguous Singer", "not-a-uuid")));
            assertEquals("online.mbid.override.invalid", invalidEx.getMessage());

            // 合法覆盖（大写 UUID 规范化）+ 显式取消绑定 Auto Composer -> 缺封面转入 COVER_MISSING
            ReviewItem moved = recovery.confirmOnlineCandidate(item.getId(), cand.getId(),
                "Deferred Album", "Ambiguous Singer", "2026-10-01", "", null, null,
                RecoveryService.DuplicatePolicy.ASK, null, false, null,
                Map.of("Ambiguous Singer", idChosen.toUpperCase(), "Auto Composer", ""));
            assertEquals(ReviewItem.Kind.COVER_MISSING, moved.effectiveKind());
            assertNotNull(moved.getMissingCover());
            assertEquals(Map.of("ambiguous singer", idChosen, "auto composer", ""),
                moved.getMissingCover().getOnlineEntityMbidOverrides());
        }

        // 模拟进程重启：重新从磁盘加载 review-queue.json 后完成无封面入库
        ReviewQueueService restartedQueue = new ReviewQueueService(config);
        ReviewItem reloaded = restartedQueue.get(item.getId());
        assertNotNull(reloaded);
        assertEquals(ReviewItem.Kind.COVER_MISSING, reloaded.effectiveKind());
        assertEquals(Map.of("ambiguous singer", idChosen, "auto composer", ""),
            reloaded.getMissingCover().getOnlineEntityMbidOverrides());

        try (RecoveryService restartedRecovery = new RecoveryService(
                config, null, processedLogger, restartedQueue, new FolderAlbumCache(null, null, null), null,
                new FileSystemUtils(config), tagWriter, stubFp, null, null)) {
            restartedRecovery.commitDeferredOnlineMissingCover(reloaded, null, true, RecoveryService.DuplicatePolicy.ASK);
        }

        MusicMetadata out = tagWriter.readTags(
            outputDir.resolve("Ambiguous Singer/Deferred Album/1.01 Ambiguous Singer - Song.flac").toFile());
        assertNotNull(out);
        assertEquals(idChosen, out.getAlbumArtistId());
        assertEquals("Singer, Ambiguous", out.getAlbumArtistSort());
        assertEquals(idChosen, out.getArtistId());
        assertEquals("Auto Composer", out.getComposer());
        assertNull(out.getComposerIds(), "Persisted explicit unbind override must survive missing-cover restart");
    }

    @Test
    void retainedUnmatchedTrackPreservesMultiValueVorbisArtistIdsAndSupportsPartialCollabOverride() throws Exception {
        String idA = "11111111-1111-4111-8111-111111111111";
        String idB = "22222222-2222-4222-8222-222222222222";
        String idBNew = "33333333-3333-4333-8333-333333333333";

        Path albumDir = Files.createDirectory(monitorDir.resolve("MultiValueRetainedAlbum"));
        File f1 = createFlac(albumDir.resolve("01. First.flac"), "First", "Singer", "Old", "1", null, null, null, null);
        File f2 = createFlac(albumDir.resolve("02. Second.flac"), "Second", "Singer", "Old", "2", null, null, null, null);
        File f3 = createFlac(albumDir.resolve("03. Third.flac"), "Third", "Singer", "Old", "3", null, null, null, null);
        File f4 = createFlac(albumDir.resolve("04. UnmatchedMulti.flac"), "Unrelated Local", "Collab A, Collab B", "Old", "99", null, null, null, null);
        // 写入重复多值 Vorbis MUSICBRAINZ_ARTISTID 字段（非分号拼接，模拟 Picard/MusicBee 常见写法）
        var audio4 = AudioFileIO.read(f4);
        org.jaudiotagger.tag.flac.FlacTag flacTag4 = (org.jaudiotagger.tag.flac.FlacTag) audio4.getTag();
        flacTag4.addField(FieldKey.ARTISTS, "Collab A");
        flacTag4.addField(FieldKey.ARTISTS, "Collab B");
        flacTag4.addField(FieldKey.MUSICBRAINZ_ARTISTID, idA);
        flacTag4.addField(FieldKey.MUSICBRAINZ_ARTISTID, idB);
        flacTag4.addField(FieldKey.ARTIST_SORT, "A, Collab");
        flacTag4.addField(FieldKey.ARTIST_SORT, "B, Collab");
        audio4.commit();

        ReviewItem item = reviewQueue.enqueueRecoveryFolder(albumDir.toString(), null, List.of(f1, f2, f3, f4), tagWriter, null);
        ReviewItem.OnlineCandidate cand = new ReviewItem.OnlineCandidate();
        cand.setId("cand-multi-retained");
        cand.setTitle("Multi Retained Album");
        cand.setArtist("Singer");
        cand.setAlbumArtist("Singer");
        cand.setTracks(List.of(
            onlineTrack(1, 1, "First", "Singer", List.of(), List.of(), List.of()),
            onlineTrack(1, 2, "Second", "Singer", List.of(), List.of(), List.of()),
            onlineTrack(1, 3, "Third", "Singer", List.of(), List.of(), List.of()),
            onlineTrack(1, 4, "Completely Unmatched", "Other", List.of(), List.of(), List.of())
        ));
        item.setOnlineCandidates(List.of(cand));
        OnlineIdentificationService online = new OnlineIdentificationService(config, reviewQueue, tagWriter, null, null);
        item.setEvidenceHash(online.evidenceHashForFolder(albumDir.toFile()));
        reviewQueue.update(item);

        AudioFingerprintService stubFp = new AudioFingerprintService(config) {
            @Override
            public List<Integer> extractDurationSequence(List<File> files) {
                return List.of(120, 120, 120, 120);
            }
        };

        // 1. 不传覆盖时，未匹配曲目必须原样保留两个重复多值 Vorbis MUSICBRAINZ_ARTISTID，绝不坍缩为首个值
        try (RecoveryService recovery = new RecoveryService(
                config, null, processedLogger, reviewQueue, new FolderAlbumCache(null, null, null), null,
                new FileSystemUtils(config), tagWriter, stubFp, null, null)) {
            recovery.confirmOnlineCandidate(item.getId(), cand.getId(),
                "Multi Retained Album", "Singer", "2026-11-01", "", null, null,
                RecoveryService.DuplicatePolicy.ASK, null, true, null, null);
        }
        File out4 = outputDir.resolve("Singer/Multi Retained Album/99. Collab A, Collab B - Unrelated Local.flac").toFile();
        Tag rawOut4 = AudioFileIO.read(out4).getTag();
        assertEquals(List.of(idA, idB), rawOut4.getAll(FieldKey.MUSICBRAINZ_ARTISTID),
            "Retained unmatched track must preserve repeated multi-value Vorbis MUSICBRAINZ_ARTISTID fields verbatim");

        // 2. 对保留的二人合作曲目仅覆盖其中一人（Collab B -> idBNew）时，另一人沿用原对齐基线 idA
        Path albumDir2 = Files.createDirectory(monitorDir.resolve("MultiValueRetainedAlbum2"));
        File g1 = createFlac(albumDir2.resolve("01. First.flac"), "First", "Singer", "Old", "1", null, null, null, null);
        File g2 = createFlac(albumDir2.resolve("02. Second.flac"), "Second", "Singer", "Old", "2", null, null, null, null);
        File g3 = createFlac(albumDir2.resolve("03. Third.flac"), "Third", "Singer", "Old", "3", null, null, null, null);
        File g4 = Files.copy(f4.toPath(), albumDir2.resolve("04. UnmatchedMulti.flac")).toFile();

        ReviewItem item2 = reviewQueue.enqueueRecoveryFolder(albumDir2.toString(), null, List.of(g1, g2, g3, g4), tagWriter, null);
        item2.setOnlineCandidates(List.of(cand));
        item2.setEvidenceHash(online.evidenceHashForFolder(albumDir2.toFile()));
        reviewQueue.update(item2);

        try (RecoveryService recovery = new RecoveryService(
                config, null, processedLogger, reviewQueue, new FolderAlbumCache(null, null, null), null,
                new FileSystemUtils(config), tagWriter, stubFp, null, null)) {
            recovery.confirmOnlineCandidate(item2.getId(), cand.getId(),
                "Multi Retained Album 2", "Singer", "2026-11-01", "", null, null,
                RecoveryService.DuplicatePolicy.ASK, null, true, null,
                Map.of("Collab B", idBNew));
        }
        MusicMetadata outG4 = tagWriter.readTags(
            outputDir.resolve("Singer/Multi Retained Album 2/99. Collab A, Collab B - Unrelated Local.flac").toFile());
        assertNotNull(outG4);
        assertEquals(idA + "; " + idBNew, outG4.getArtistId(),
            "Partial override on retained collaboration must merge unchanged collaborator's baseline MBID");
    }

    private static ReviewItem.EntityMbidResolution resolvedEntity(String display, String mbid, String sortName) {
        ReviewItem.EntityMbidResolution r = new ReviewItem.EntityMbidResolution();
        r.setDisplayName(display);
        r.setNormalizedKey(MusicBrainzArtistCache.normalizePersonKey(display));
        r.setStatus(ReviewItem.EntityMbidStatus.RESOLVED);
        r.setSource(ReviewItem.EntityMbidSource.MB_SEARCH_EXACT);
        r.setSelectedMbid(mbid);
        r.setSelectedName(display);
        r.setSelectedSortName(sortName);
        return r;
    }

    private static File createFlacWithArtistIds(Path target, String title, String artist, String album, String trackNo,
                                                String artistId, String artistSort,
                                                String albumArtistId, String albumArtistSort) throws Exception {
        File file = createFlac(target, title, artist, album, trackNo, null, null, null, null);
        var audio = AudioFileIO.read(file);
        Tag tag = audio.getTag();
        if (artistId != null) tag.setField(FieldKey.MUSICBRAINZ_ARTISTID, artistId);
        if (artistSort != null) tag.setField(FieldKey.ARTIST_SORT, artistSort);
        if (albumArtistId != null) tag.setField(FieldKey.MUSICBRAINZ_RELEASEARTISTID, albumArtistId);
        if (albumArtistSort != null) tag.setField(FieldKey.ALBUM_ARTIST_SORT, albumArtistSort);
        audio.commit();
        return file;
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
