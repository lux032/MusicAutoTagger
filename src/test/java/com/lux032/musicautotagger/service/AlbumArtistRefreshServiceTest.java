package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lux032.musicautotagger.config.MusicConfig;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.StandardArtwork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AlbumArtistRefreshServiceTest {
    private static final String RELEASE = "12345678-1234-1234-1234-123456789abc";
    private static final String SECOND_RELEASE = "12345678-1234-1234-1234-123456789def";
    private static final String ARTIST = "87654321-1234-1234-1234-123456789abc";

    @TempDir Path root;
    private MusicConfig config;

    @BeforeEach
    void configure() throws Exception {
        var constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        config = constructor.newInstance();
        config.setOutputDirectory(root.toString());
        config.setMonitorDirectory(root.resolve("incoming").toString());
        config.setProcessedFileLogPath(root.resolve("processed.log").toString());
    }

    @Test
    void writesAlbumArtistAndPreservesTrackTagsArtworkAndAudio() throws Exception {
        Path file = file("Disc 1/one.flac", " vA ", 1, RELEASE);
        Path alreadyTagged = file("Disc 2/two.flac", "Album Artist", 2, RELEASE);
        Path unrelated = file("another/one.flac", "", 1, RELEASE);
        Path protectedFile = file("incoming/one.flac", "VA", 1, RELEASE);

        var audio = AudioFileIO.read(file.toFile());
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", png);
        var artwork = new StandardArtwork();
        artwork.setBinaryData(png.toByteArray());
        artwork.setMimeType("image/png");
        artwork.setPictureType(3);
        audio.getTag().setField(artwork);
        audio.commit();

        FileTime modified = FileTime.fromMillis(1234567000);
        Files.setLastModifiedTime(file, modified);
        byte[] payload = audioPayload(file);
        byte[] alreadyTaggedBytes = Files.readAllBytes(alreadyTagged);
        byte[] unrelatedBytes = Files.readAllBytes(unrelated);
        byte[] protectedBytes = Files.readAllBytes(protectedFile);

        var release = release(RELEASE, "Album Artist");
        try (var client = new StubClient(release);
             var service = service(client)) {
            var status = run(service);
            assertEquals(1, status.updated());
            assertEquals(1, status.filesUpdated());
            assertEquals(0, status.failed());
            assertEquals(1, client.lookups);
            assertEquals(0, client.searches);
            assertEquals(0, run(service).total());
        }

        assertFalse(Files.exists(file));
        assertFalse(Files.exists(file.getParent()));
        file = root.resolve("Album Artist/Album/Disc 1/one.flac");
        Tag tag = AudioFileIO.read(file.toFile()).getTag();
        assertEquals("Album Artist", tag.getFirst(FieldKey.ALBUM_ARTIST));
        assertEquals(List.of("Album Artist"), tag.getAll(FieldKey.ALBUM_ARTISTS));
        assertEquals(ARTIST, tag.getFirst(FieldKey.MUSICBRAINZ_RELEASEARTISTID));
        assertEquals("Artist, Album", tag.getFirst(FieldKey.ALBUM_ARTIST_SORT));
        assertEquals("Guest Performer", tag.getFirst(FieldKey.ARTIST));
        assertEquals(List.of("Guest Performer", "Another Performer"), tag.getAll(FieldKey.ARTISTS));
        assertEquals("Original lyrics", tag.getFirst(FieldKey.LYRICS));
        assertEquals("Album", tag.getFirst(FieldKey.ALBUM));
        assertEquals(RELEASE, tag.getFirst(FieldKey.MUSICBRAINZ_RELEASEID));
        assertArrayEquals(png.toByteArray(), tag.getFirstArtwork().getBinaryData());
        assertArrayEquals(payload, audioPayload(file));
        assertTrue(Files.getLastModifiedTime(file).compareTo(modified) > 0);
        assertArrayEquals(alreadyTaggedBytes, Files.readAllBytes(alreadyTagged));
        assertArrayEquals(unrelatedBytes, Files.readAllBytes(unrelated));
        assertArrayEquals(protectedBytes, Files.readAllBytes(protectedFile));
    }

    @Test
    void refreshesMp3JointCreditAndHonorsVaCollapseSetting() throws Exception {
        byte[] mpeg = new byte[417 * 400];
        for (int i = 0; i < mpeg.length; i += 417) {
            mpeg[i] = (byte) 0xff;
            mpeg[i + 1] = (byte) 0xfb;
            mpeg[i + 2] = (byte) 0x90;
        }
        Path file = root.resolve("track.mp3");
        Files.write(file, mpeg);
        tagFile(file, "VA", 1, RELEASE);
        JsonNode release = new ObjectMapper().readTree("""
            {
              "id": "%s", "title": "Album",
              "artist-credit": [
                {"name":"First","joinphrase":" & ","artist":{"name":"First","id":"%s","sort-name":"First"}},
                {"name":"Second","artist":{"name":"Second","id":"%s","sort-name":"Second"}}
              ]
            }
            """.formatted(RELEASE, ARTIST, "87654321-1234-1234-1234-123456789def"));

        config.setCollapseAlbumArtistToVariousArtists(true);
        try (var client = new StubClient(release);
             var service = service(client)) {
            assertEquals(1, run(service).unchanged());
            config.setCollapseAlbumArtistToVariousArtists(false);
            assertEquals(1, run(service).filesUpdated());
        }
        assertFalse(Files.exists(file));
        Tag tag = AudioFileIO.read(root.resolve("First & Second/Album/track.mp3").toFile()).getTag();
        assertEquals("First & Second", tag.getFirst(FieldKey.ALBUM_ARTIST));
        assertEquals("Guest Performer", tag.getFirst(FieldKey.ARTIST));
        assertEquals("Original lyrics", tag.getFirst(FieldKey.LYRICS));
    }

    @Test
    void conflictingConcreteCreditAndGenuineVaArePreserved() throws Exception {
        Path conflicting = file("Conflict/one.flac", "VA", 1, RELEASE);
        file("Conflict/two.flac", "Handwritten Credit", 2, RELEASE);
        Path genuine = file("Compilation/one.flac", "Various Artists", 1, SECOND_RELEASE);

        try (var client = new StubClient(release(RELEASE, "Album Artist"), release(SECOND_RELEASE, "Various Artists"));
             var service = service(client)) {
            var status = run(service);
            assertEquals(1, status.skipped());
            assertEquals(1, status.unchanged());
            assertEquals("conflict", status.lastIssue());
            assertEquals(0, status.filesUpdated());
        }
        assertEquals("VA", AudioFileIO.read(conflicting.toFile()).getTag().getFirst(FieldKey.ALBUM_ARTIST));
        assertEquals("Various Artists", AudioFileIO.read(genuine.toFile()).getTag().getFirst(FieldKey.ALBUM_ARTIST));
    }

    @Test
    void missingReleaseIdRequiresUniqueTrackEvidence() throws Exception {
        Path file = file("one.flac", "VA", 1, "");
        Path second = file("two.flac", "VA", 2, "");
        var release = release(RELEASE, "Album Artist");

        try (var client = new StubClient(release, release(SECOND_RELEASE, "Another Artist"));
             var service = service(client)) {
            assertEquals(1, run(service).skipped());
            assertEquals("VA", AudioFileIO.read(file.toFile()).getTag().getFirst(FieldKey.ALBUM_ARTIST));
        }
        try (var client = new StubClient(release);
             var service = service(client)) {
            assertEquals(2, run(service).filesUpdated());
            assertFalse(Files.exists(second));
            Path moved = root.resolve("Album Artist/Album/two.flac");
            assertEquals("Album Artist", AudioFileIO.read(moved.toFile()).getTag().getFirst(FieldKey.ALBUM_ARTIST));
        }
    }

    @Test
    void keepsUnconfirmedTracksWithoutBlockingKnownRelease() throws Exception {
        Path known = file("known.flac", "VA", 1, RELEASE);
        Path inferred = file("inferred.flac", "VA", 2, "");
        Path unknown = file("unknown.flac", "VA", 3, "");
        byte[] unknownBytes = Files.readAllBytes(unknown);

        try (var client = new StubClient(release(RELEASE, "Album Artist"));
             var service = service(client)) {
            var status = run(service);
            assertEquals(2, status.filesUpdated());
            assertEquals("partial", status.lastIssue());
        }
        for (Path path : List.of(known, inferred)) {
            assertFalse(Files.exists(path));
            Path moved = root.resolve("Album Artist/Album").resolve(path.getFileName());
            assertEquals("Album Artist", AudioFileIO.read(moved.toFile()).getTag().getFirst(FieldKey.ALBUM_ARTIST));
        }
        assertArrayEquals(unknownBytes, Files.readAllBytes(unknown));
    }

    @Test
    void cancellationDuringLookupPreventsWriteAndRejectsAnotherStart() throws Exception {
        Path file = file("one.flac", "VA", 1, RELEASE);
        byte[] original = Files.readAllBytes(file);
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);

        try (var client = new StubClient(release(RELEASE, "Album Artist")) {
            @Override
            JsonNode getFullReleaseById(String releaseId, boolean refresh) throws InterruptedException {
                entered.countDown();
                if (!resume.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test lookup timed out");
                return super.getFullReleaseById(releaseId, refresh);
            }
        }; var service = service(client)) {
            assertTrue(service.start());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(service.start());
                service.cancel();
                assertTrue(service.getStatus().cancelling());
            } finally {
                resume.countDown();
            }
            var status = await(service);
            assertEquals("cancelled", status.phase());
            assertEquals(0, status.filesUpdated());
        }
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void removesOnlyEmptyOldAlbumDirectoriesAndUpdatesExactHistoryPath() throws Exception {
        Path empty = file("Various Artists/Empty/one.flac", "VA", 1, RELEASE);
        Path sidecars = file("Various Artists/Sidecars/two.flac", "VA", 2, RELEASE);
        Path cover = Files.writeString(sidecars.resolveSibling("cover.jpg"), "cover");
        Path lyrics = Files.writeString(sidecars.resolveSibling("two.lrc"), "lyrics");
        Path history = Path.of(config.getProcessedFileLogPath());
        String originalRecord = "/source/one.flac||Artist|Track 1|Album|2026-01-01 00:00:00||";
        String otherRecord = "/source/other.flac||Artist|Track|Album|2026-01-01 00:00:00||";
        Files.writeString(history, originalRecord + empty + "\n" + otherRecord + empty + ".other\n");

        try (var client = new StubClient(release(RELEASE, "Album Artist")); var service = service(client)) {
            assertEquals(2, run(service).filesUpdated());
        }

        Path moved = root.resolve("Album Artist/Album/one.flac");
        assertTrue(Files.isRegularFile(moved));
        assertTrue(Files.isRegularFile(root.resolve("Album Artist/Album/two.flac")));
        assertFalse(Files.exists(empty.getParent()));
        assertFalse(Files.exists(sidecars));
        assertEquals("cover", Files.readString(cover));
        assertEquals("lyrics", Files.readString(lyrics));
        assertEquals(List.of(originalRecord + moved, otherRecord + empty + ".other"), Files.readAllLines(history));
    }

    @Test
    void occupiedTargetLeavesBothFilesUntouched() throws Exception {
        Path file = file("Various Artists/Album/one.flac", "VA", 1, RELEASE);
        Path occupied = file("Album Artist/Album/one.flac", "Existing", 1, SECOND_RELEASE);
        byte[] original = Files.readAllBytes(file);
        byte[] other = Files.readAllBytes(occupied);

        try (var client = new StubClient(release(RELEASE, "Album Artist")); var service = service(client)) {
            var status = run(service);
            assertEquals(1, status.failed());
            assertEquals(0, status.filesUpdated());
        }
        assertArrayEquals(original, Files.readAllBytes(file));
        assertArrayEquals(other, Files.readAllBytes(occupied));
    }

    @Test
    void retainsSourceIfHistoryCannotBeUpdated() throws Exception {
        Path file = file("Various Artists/Album/one.flac", "VA", 1, RELEASE);
        byte[] original = Files.readAllBytes(file);
        var logger = new ProcessedFileLogger(config, null) {
            @Override
            void relocateTargetPath(Path source, Path target) throws IOException {
                throw new IOException("history unavailable");
            }
        };

        try (var client = new StubClient(release(RELEASE, "Album Artist"));
             var service = new AlbumArtistRefreshService(config, client, new TagWriterService(config), logger)) {
            assertEquals(1, run(service).failed());
        }
        assertArrayEquals(original, Files.readAllBytes(file));
        assertFalse(Files.exists(root.resolve("Album Artist")));
    }

    private AlbumArtistRefreshService service(MusicBrainzClient client) {
        return new AlbumArtistRefreshService(config, client, new TagWriterService(config),
            new ProcessedFileLogger(config, null));
    }

    private AlbumArtistRefreshService.Status run(AlbumArtistRefreshService service) throws Exception {
        assertTrue(service.start());
        var status = await(service);
        assertEquals("finished", status.phase());
        return status;
    }

    private AlbumArtistRefreshService.Status await(AlbumArtistRefreshService service) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (service.getStatus().running() && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(service.getStatus().running());
        return service.getStatus();
    }

    private Path file(String name, String albumArtist, int number, String releaseId) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        ByteBuffer bytes = ByteBuffer.allocate(554);
        bytes.put(new byte[]{'f', 'L', 'a', 'C', (byte) 128, 0, 0, 34});
        bytes.putLong(18, (44100L << 44) | (1L << 41) | (15L << 36) | 5303025L);
        for (int i = 42; i < bytes.capacity(); i++) bytes.put(i, (byte) (i * 7));
        Files.write(file, bytes.array());
        tagFile(file, albumArtist, number, releaseId);
        return file;
    }

    private void tagFile(Path file, String albumArtist, int number, String releaseId) throws Exception {
        var audio = AudioFileIO.read(file.toFile());
        Tag tag = audio.getTagOrCreateAndSetDefault();
        tag.setField(FieldKey.ALBUM, "Album");
        tag.setField(FieldKey.TITLE, "Track " + number);
        tag.setField(FieldKey.TRACK, String.valueOf(number));
        tag.setField(FieldKey.ARTIST, "Guest Performer");
        tag.setField(FieldKey.ARTISTS, "Guest Performer");
        tag.addField(FieldKey.ARTISTS, "Another Performer");
        tag.setField(FieldKey.LYRICS, "Original lyrics");
        if (!albumArtist.isEmpty()) tag.setField(FieldKey.ALBUM_ARTIST, albumArtist);
        if (!releaseId.isEmpty()) tag.setField(FieldKey.MUSICBRAINZ_RELEASEID, releaseId);
        audio.commit();
    }

    private byte[] audioPayload(Path file) throws Exception {
        var header = AudioFileIO.read(file.toFile()).getAudioHeader();
        return java.util.Arrays.copyOfRange(Files.readAllBytes(file),
            header.getAudioDataStartPosition().intValue(), header.getAudioDataEndPosition().intValue());
    }

    private JsonNode release(String id, String artist) throws Exception {
        return new ObjectMapper().readTree("""
            {
              "id": "%s", "title": "Album",
              "artist-credit": [{"name":"%s","artist":{"name":"%s","id":"%s","sort-name":"Artist, Album"}}],
              "media":[{"position":1,"tracks":[{"position":1,"title":"Track 1"},{"position":2,"title":"Track 2"}]}]
            }
            """.formatted(id, artist, artist, ARTIST));
    }

    private class StubClient extends MusicBrainzClient implements AutoCloseable {
        final List<JsonNode> releases;
        int lookups, searches;

        StubClient(JsonNode... releases) {
            super(config);
            this.releases = List.of(releases);
        }

        @Override
        JsonNode getFullReleaseById(String releaseId, boolean refresh) throws InterruptedException {
            lookups++;
            return releases.stream().filter(release -> releaseId.equals(release.path("id").asText()))
                .findFirst().orElseThrow();
        }

        @Override
        List<JsonNode> searchReleasesForAlbumArtistRefresh(String album, String releaseGroupId) {
            searches++;
            return releases;
        }
    }
}
