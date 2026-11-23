package com.lux032.musicautotagger.service;

import com.google.gson.JsonParser;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.llm.LlmClient;
import com.lux032.musicautotagger.util.FileSystemUtils;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocalArchiveTest {
    private static final String JOINT_CREDIT = "Hironori Anazawa & Shoji Meguro";
    private static final List<String> JOINT_NAMES = List.of("Hironori Anazawa", "Shoji Meguro");

    @TempDir Path root;

    @Test
    void checksAmbiguousSingletonListsAndUsesEachTracksOwnCredit() throws Exception {
        MusicConfig config = config();
        var draft = new LocalArchiveDraft(new LlmClient(config) {
            @Override public boolean isConfigured() { return true; }

            @Override public LlmResponse complete(String system, String user, int maxTokens) {
                var credits = JsonParser.parseString(user).getAsJsonObject().getAsJsonObject("credits");
                assertEquals(JOINT_CREDIT, credits.get("0").getAsString());
                assertEquals("Earth, Wind & Fire", credits.get("1").getAsString());
                assertEquals(2, credits.size());
                return new LlmResponse("""
                    {"albumTitle":"Ignore this", "albumArtist":"Ignore this",
                     "credits":[{"index":0,"artists":["Hironori Anazawa","Shoji Meguro"]},
                                {"index":1,"artists":["Earth, Wind & Fire"]}]}
                    """, "test", "test");
            }
        });
        var first = metadata("Existing album", JOINT_CREDIT, JOINT_CREDIT);
        first.setAlbumArtists(List.of(JOINT_CREDIT));
        first.setArtists(List.of(JOINT_CREDIT));
        var second = metadata("Existing album", JOINT_CREDIT, "Earth, Wind & Fire");
        second.setAlbumArtists(List.of(JOINT_CREDIT));
        var inputs = new LinkedHashMap<String, MusicMetadata>();
        inputs.put("a.flac", first);
        inputs.put("b.flac", second);
        var credited = metadata("Existing album", JOINT_CREDIT, "Other & Guest");
        credited.setAlbumArtists(List.of(JOINT_CREDIT));
        credited.setArtists(List.of("Orchestra", "Choir"));
        inputs.put("c.flac", credited);

        var result = draft.build("revision", "Different Folder", inputs);
        assertEquals("Existing album", result.albumTitle());
        assertEquals(JOINT_CREDIT, result.albumArtist());
        assertEquals(JOINT_NAMES, result.albumArtists());
        assertEquals(JOINT_NAMES, result.tracks().get(0).artists());
        assertEquals(List.of("Earth, Wind & Fire"), result.tracks().get(1).artists());
        assertEquals(2, result.tracks().size());
    }

    @Test
    void usesFolderWhenAlbumArtistMissingAndLeavesUncertainTracksUnchecked() throws Exception {
        var draft = new LocalArchiveDraft(new LlmClient(config()) {
            @Override public boolean isConfigured() { return true; }

            @Override public LlmResponse complete(String system, String user, int maxTokens) {
                assertTrue(user.contains("\"inferAlbumArtist\":true"));
                return new LlmResponse("""
                    {"albumTitle":"Orchestral Album",
                     "albumArtist":"Hironori Anazawa & Shoji Meguro",
                     "albumArtists":["Hironori Anazawa","Shoji Meguro"],
                     "credits":[{"index":0,"artists":["Unrelated Name"]}]}
                    """, "test", "test");
            }
        });
        var inputs = Map.of("a.flac", metadata("", "", "Other & Guest"),
            "b.flac", metadata("Old album", "Old artist", "Solo performer"));
        var result = draft.build("revision", JOINT_CREDIT + " - Orchestral Album [WEB FLAC]", inputs);
        assertEquals("Orchestral Album", result.albumTitle());
        assertEquals(JOINT_NAMES, result.albumArtists());
        assertEquals(1, result.tracks().size());
        assertTrue(result.tracks().get(0).needsReview());
        assertTrue(result.tracks().get(0).artists().isEmpty());
    }

    @Test
    void previewIsReadOnlyAndManualArchiveWritesOnlyApprovedFieldsWithoutOnlineMatches() throws Exception {
        MusicConfig config = config();
        Path source = root.resolve("incoming");
        Path first = flac(source.resolve("[Disc 1]/one.flac"), JOINT_CREDIT, 1);
        Path second = flac(source.resolve("[Disc 2]/two.flac"), "Hironori Anazawa", 2);
        var secondAudio = AudioFileIO.read(second.toFile());
        secondAudio.getTag().setField(FieldKey.ARTISTS, "ユークロニア王立交響楽団");
        secondAudio.commit();
        byte[] originalFirst = Files.readAllBytes(first);
        byte[] originalSecond = Files.readAllBytes(second);
        var writer = new TagWriterService(config);
        var queue = new ReviewQueueService(config);
        var item = queue.enqueueRecoveryFolder(source.toString(), null, List.of(first.toFile(), second.toFile()),
            writer, null);
        var candidate = new ReviewItem.OnlineCandidate();
        candidate.setId("online");
        candidate.setTracks(List.of());
        item.setOnlineCandidates(List.of(candidate));
        queue.update(item);

        try (var service = new RecoveryService(config, null, new ProcessedFileLogger(config, null), queue,
                new FolderAlbumCache(null, null, null), null, new FileSystemUtils(config), writer, null, null)) {
            var draft = service.previewLocalArchive(item.getId());
            assertEquals(2, draft.fileCount());
            assertEquals("Old album", draft.albumTitle());
            assertEquals(JOINT_CREDIT, draft.albumArtist());
            assertTrue(draft.albumArtists().isEmpty());
            assertEquals(List.of("[Disc 1]/one.flac"), draft.tracks().stream().map(LocalArchiveDraft.Track::file).toList());
            assertTrue(draft.tracks().get(0).needsReview());
            assertEquals(ReviewItem.Status.PENDING_REVIEW, item.getStatus());
            assertFalse(Files.exists(root.resolve("output")));
            assertArrayEquals(originalFirst, Files.readAllBytes(first));

            Files.setLastModifiedTime(first, FileTime.fromMillis(1234567));
            IOException stale = assertThrows(IOException.class, () ->
                service.confirmLocalArchive(item.getId(), draft.revision(), "New album", JOINT_CREDIT,
                    JOINT_NAMES, Map.of(), List.of(), RecoveryService.DuplicatePolicy.ASK));
            assertEquals("local.archive.stale", stale.getMessage());
            assertFalse(Files.exists(root.resolve("output")));

            String revision = service.previewLocalArchive(item.getId()).revision();
            IOException protectedCredit = assertThrows(IOException.class, () ->
                service.confirmLocalArchive(item.getId(), revision, "New album", JOINT_CREDIT, JOINT_NAMES,
                    Map.of("[Disc 2]/two.flac", List.of("Hironori Anazawa")), List.of(), RecoveryService.DuplicatePolicy.ASK));
            assertEquals("local.archive.stale", protectedCredit.getMessage());
            assertFalse(Files.exists(root.resolve("output")));
            service.confirmLocalArchive(item.getId(), revision, "New album", JOINT_CREDIT, JOINT_NAMES,
                Map.of("[Disc 1]/one.flac", JOINT_NAMES), List.of(), RecoveryService.DuplicatePolicy.ASK);
        }
        assertEquals(ReviewItem.Status.CONFIRMED, item.getStatus());
        assertEquals(ReviewItem.VerificationSource.LOCAL_TAGS, item.getVerificationSource());
        assertArrayEquals(originalFirst, Files.readAllBytes(first));
        assertArrayEquals(originalSecond, Files.readAllBytes(second));

        Path output = root.resolve("output").resolve(JOINT_CREDIT).resolve("New album");
        var firstTag = AudioFileIO.read(output.resolve("[Disc 1]/one.flac").toFile()).getTag();
        var secondTag = AudioFileIO.read(output.resolve("[Disc 2]/two.flac").toFile()).getTag();
        assertEquals("New album", firstTag.getFirst(FieldKey.ALBUM));
        assertEquals(JOINT_CREDIT, firstTag.getFirst(FieldKey.ALBUM_ARTIST));
        assertEquals(JOINT_NAMES, firstTag.getAll(FieldKey.ALBUM_ARTISTS));
        assertEquals(JOINT_NAMES, firstTag.getAll(FieldKey.ARTISTS));
        assertEquals(JOINT_NAMES, secondTag.getAll(FieldKey.ALBUM_ARTISTS));
        assertEquals(List.of("ユークロニア王立交響楽団"), secondTag.getAll(FieldKey.ARTISTS));
        assertEquals("Hironori Anazawa", secondTag.getFirst(FieldKey.ARTIST));
        assertEquals("Song 2", secondTag.getFirst(FieldKey.TITLE));
        assertEquals("2", secondTag.getFirst(FieldKey.TRACK));
        assertEquals("2", secondTag.getFirst(FieldKey.DISC_NO));
        assertEquals("2026", secondTag.getFirst(FieldKey.YEAR));
        assertEquals("Original lyrics", secondTag.getFirst(FieldKey.LYRICS));
    }

    @Test
    void archivesNestedRecoveryFilesAndRetiresSourceAfterCommit() throws Exception {
        MusicConfig config = config();
        config.setRecoveryTrashRetentionDays(7);
        Path source = root.resolve("failed/album");
        Path file = flac(source.resolve("Edition/Disc One/track.flac"), "", 1);
        Path credited = flac(source.resolve("Edition/Disc One/credited.flac"), "", 2);
        var creditedAudio = AudioFileIO.read(credited.toFile());
        creditedAudio.getTag().setField(FieldKey.ARTISTS, "Guest Performer");
        creditedAudio.commit();
        byte[] original = Files.readAllBytes(file);
        var writer = new TagWriterService(config);
        var queue = new ReviewQueueService(config);
        var item = queue.enqueueRecoveryFolder(source.toString(), "FAILED", List.of(file.toFile(), credited.toFile()), writer, null);
        String folderTrack = "Edition/Disc One/track.flac";
        String creditedTrack = "Edition/Disc One/credited.flac";
        List<String> approvedArtists = List.of("Confirmed One", "Confirmed Two");
        String approvedCredit = "Confirmed One & Confirmed Two";

        try (var service = new RecoveryService(config, null, new ProcessedFileLogger(config, null), queue,
                new FolderAlbumCache(null, null, null), null, new FileSystemUtils(config), writer, null, null)) {
            var draft = service.previewLocalArchive(item.getId());
            assertTrue(Files.exists(file));
            assertEquals(List.of(folderTrack), draft.tracks().stream()
                .filter(LocalArchiveDraft.Track::inheritAlbumArtists).map(LocalArchiveDraft.Track::file).toList());
            IOException stale = assertThrows(IOException.class, () -> service.confirmLocalArchive(item.getId(),
                draft.revision(), "Album", approvedCredit, approvedArtists, Map.of(), List.of(creditedTrack),
                RecoveryService.DuplicatePolicy.ASK));
            assertEquals("local.archive.stale", stale.getMessage());
            assertTrue(Files.exists(file));

            service.confirmLocalArchive(item.getId(), draft.revision(), "Album", approvedCredit, approvedArtists,
                Map.of(), List.of(folderTrack), RecoveryService.DuplicatePolicy.ASK);
        }
        assertEquals(ReviewItem.RecoveryCommitState.COMPLETED, item.getRecoveryCommitState());
        assertFalse(Files.exists(source));
        Path album = root.resolve("output").resolve(approvedCredit).resolve("Album");
        var inherited = AudioFileIO.read(album.resolve(folderTrack).toFile()).getTag();
        assertEquals(approvedCredit, inherited.getFirst(FieldKey.ARTIST));
        assertEquals(approvedArtists, inherited.getAll(FieldKey.ARTISTS));
        var preserved = AudioFileIO.read(album.resolve(creditedTrack).toFile()).getTag();
        assertEquals("", preserved.getFirst(FieldKey.ARTIST));
        assertEquals(List.of("Guest Performer"), preserved.getAll(FieldKey.ARTISTS));
        try (var entries = Files.list(root.resolve("trash"))) {
            Path trashEntry = entries.filter(Files::isDirectory).findFirst().orElseThrow();
            assertArrayEquals(original, Files.readAllBytes(trashEntry.resolve("Edition/Disc One/track.flac")));
        }
    }

    @Test
    void missingCoverInterceptStillRejectsProtectedCreditsAndDetectsStaleRevision() throws Exception {
        MusicConfig config = config();
        config.setCoverMissingRequireReview(true);
        Path source = root.resolve("incoming-missing-cover");
        Path first = flac(source.resolve("one.flac"), JOINT_CREDIT, 1);
        Path second = flac(source.resolve("two.flac"), "Hironori Anazawa", 2);
        var secondAudio = AudioFileIO.read(second.toFile());
        secondAudio.getTag().setField(FieldKey.ARTISTS, "Protected Orchestra");
        secondAudio.commit();
        var writer = new TagWriterService(config);
        var queue = new ReviewQueueService(config);
        var item = queue.enqueueRecoveryFolder(source.toString(), null, List.of(first.toFile(), second.toFile()), writer, null);

        try (var service = new RecoveryService(config, null, new ProcessedFileLogger(config, null), queue,
                new FolderAlbumCache(null, null, null), null, new FileSystemUtils(config), writer, null, null)) {
            String revision = service.previewLocalArchive(item.getId()).revision();
            // 受保护音轨的非法 credit 修改必须在进入缺封面队列前直接被拒
            IOException protectedErr = assertThrows(IOException.class, () ->
                service.confirmLocalArchive(item.getId(), revision, "New album", JOINT_CREDIT, JOINT_NAMES,
                    Map.of("two.flac", List.of("Hironori Anazawa")), List.of(), RecoveryService.DuplicatePolicy.ASK));
            assertEquals("local.archive.stale", protectedErr.getMessage());
            assertEquals(ReviewItem.Kind.ALBUM_UNRESOLVED, queue.get(item.getId()).effectiveKind());

            // 合法请求无封面时进入 COVER_MISSING 并保留 revision；若期间源文件变更，延迟提交必须拦截
            ReviewItem deferred = service.confirmLocalArchive(item.getId(), revision, "New album", JOINT_CREDIT, JOINT_NAMES,
                Map.of("one.flac", JOINT_NAMES), List.of(), RecoveryService.DuplicatePolicy.ASK);
            assertEquals(ReviewItem.Kind.COVER_MISSING, deferred.effectiveKind());

            Files.setLastModifiedTime(first, FileTime.fromMillis(9876543L));
            IOException staleAfterDefer = assertThrows(IOException.class, () ->
                service.commitDeferredLocalMissingCover(deferred, null, RecoveryService.DuplicatePolicy.ASK));
            assertEquals("local.archive.stale", staleAfterDefer.getMessage());
        }
    }

    private static MusicMetadata metadata(String album, String albumArtist, String artist) {
        var md = new MusicMetadata();
        md.setAlbum(album);
        md.setAlbumArtist(albumArtist);
        md.setArtist(artist);
        return md;
    }

    private MusicConfig config() throws Exception {
        var constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var config = constructor.newInstance();
        config.setOutputDirectory(root.resolve("output").toString());
        config.setRecoveryWorkDirectory(root.resolve("work").toString());
        config.setRecoveryTrashDirectory(root.resolve("trash").toString());
        config.setProcessedFileLogPath(root.resolve("processed.log").toString());
        config.setReviewQueuePath(root.resolve("review.json").toString());
        config.setReviewStagingDirectory(root.resolve("staging").toString());
        config.setCoverMissingRequireReview(false);
        return config;
    }

    private Path flac(Path path, String artist, int number) throws Exception {
        Files.createDirectories(path.getParent());
        ByteBuffer bytes = ByteBuffer.allocate(554);
        bytes.put(new byte[]{'f', 'L', 'a', 'C', (byte) 128, 0, 0, 34});
        bytes.putLong(18, (44100L << 44) | (1L << 41) | (15L << 36) | 5303025L);
        for (int i = 42; i < bytes.capacity(); i++) bytes.put(i, (byte) (i * number));
        Files.write(path, bytes.array());
        var audio = AudioFileIO.read(path.toFile());
        var tag = audio.getTagOrCreateAndSetDefault();
        tag.setField(FieldKey.ALBUM, "Old album");
        tag.setField(FieldKey.ALBUM_ARTIST, JOINT_CREDIT);
        if (!artist.isEmpty()) tag.setField(FieldKey.ARTIST, artist);
        tag.setField(FieldKey.TITLE, "Song " + number);
        tag.setField(FieldKey.TRACK, Integer.toString(number));
        tag.setField(FieldKey.DISC_NO, Integer.toString(number));
        tag.setField(FieldKey.YEAR, "2026");
        tag.setField(FieldKey.LYRICS, "Original lyrics");
        audio.commit();
        return path;
    }
}
