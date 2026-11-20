package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.llm.LlmArtistSplitter;
import com.lux032.musicautotagger.service.llm.LlmClient;
import com.lux032.musicautotagger.service.llm.WebSearchClient;
import com.lux032.musicautotagger.util.ArtistCreditSupport;
import com.lux032.musicautotagger.util.FileSystemUtils;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MultiArtistAssistTest {

    @TempDir
    Path dir;

    private MusicConfig config;
    private Path output;
    private Path partial;
    private TagWriterService tagWriter;

    @BeforeEach
    void setUp() throws Exception {
        var constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        config = constructor.newInstance();
        output = dir.resolve("output");
        partial = dir.resolve("partial");
        Files.createDirectories(output);
        Files.createDirectories(partial);
        config.setOutputDirectory(output.toString());
        config.setPartialDirectory(partial.toString());
        config.setFailedDirectory(dir.resolve("failed").toString());
        config.setRecoveryWorkDirectory(dir.resolve("work").toString());
        config.setRecoveryTrashDirectory(dir.resolve("trash").toString());
        config.setReviewQueuePath(dir.resolve("data/review.json").toString());
        config.setReviewStagingDirectory(dir.resolve("staging").toString());
        config.setProcessedFileLogPath(dir.resolve("processed.log").toString());
        config.setCollapseAlbumArtistToVariousArtists(false);
        config.setExportLyricsToFile(false);
        config.setAutoRename(true);
        config.setReviewEnabled(false);
        tagWriter = new TagWriterService(config);
    }

    private File createWav(Path target, String artist, String title, String album) throws Exception {
        return createWav(target, artist, title, album, null);
    }

    private File createWav(Path target, String artist, String title, String album, String trackNo) throws Exception {
        int sampleRate = 8000;
        int dataLen = sampleRate * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + dataLen).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
            .putInt(sampleRate).putInt(sampleRate).putShort((short) 1).putShort((short) 8);
        b.put("data".getBytes()).putInt(dataLen);
        Files.createDirectories(target.getParent());
        Files.write(target, b.array());

        AudioFile af = AudioFileIO.read(target.toFile());
        Tag tag = af.getTagOrCreateAndSetDefault();
        if (artist != null) tag.setField(FieldKey.ARTIST, artist);
        if (title != null) tag.setField(FieldKey.TITLE, title);
        if (album != null) tag.setField(FieldKey.ALBUM, album);
        if (trackNo != null) tag.setField(FieldKey.TRACK, trackNo);
        af.commit();
        return target.toFile();
    }

    private static LlmArtistSplitter stubSplitter(Map<String, String> responses, AtomicInteger calls) {
        LlmClient fakeClient = new LlmClient(MusicConfig.getInstance()) {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public LlmResponse complete(String systemPrompt, String userPrompt, int maxTokens) {
                calls.incrementAndGet();
                for (Map.Entry<String, String> e : responses.entrySet()) {
                    if (userPrompt.contains(e.getKey())) {
                        return new LlmResponse(e.getValue(), "fake-model", "FAKE");
                    }
                }
                return new LlmResponse("{\"artists\":[]}", "fake-model", "FAKE");
            }
        };
        return new LlmArtistSplitter(fakeClient);
    }

    @Test
    void llmArtistSplitterSplitsCollaborationsPreservesBandsAndRejectsHallucinations() {
        AtomicInteger calls = new AtomicInteger();
        LlmArtistSplitter splitter = stubSplitter(Map.of(
            "TeddyLoid, 佐倉綾音 & 井上和", "{\"artists\":[\"TeddyLoid\",\"佐倉綾音\",\"井上和\"]}",
            "Earth, Wind & Fire", "{\"artists\":[\"Earth, Wind & Fire\"]}",
            "A & B", "{\"artists\":[\"Alpha\",\"Beta\"]}"
        ), calls);

        var collab = splitter.split("TeddyLoid, 佐倉綾音 & 井上和");
        assertTrue(collab.splitApplied());
        assertEquals(List.of("TeddyLoid", "佐倉綾音", "井上和"), collab.artists());
        // Cached on second call
        assertEquals(collab.artists(), splitter.split("TeddyLoid, 佐倉綾音 & 井上和").artists());
        assertEquals(1, calls.get());

        var band = splitter.split("Earth, Wind & Fire");
        assertFalse(band.splitApplied());
        assertEquals(List.of("Earth, Wind & Fire"), band.artists());

        var hallucinated = splitter.split("A & B");
        assertFalse(hallucinated.splitApplied());
        assertTrue(hallucinated.artists().isEmpty());

        int beforeSolo = calls.get();
        var solo = splitter.split("YOASOBI");
        assertEquals(List.of("YOASOBI"), solo.artists());
        assertEquals(beforeSolo, calls.get(), "Single artist without delimiters must not invoke LLM");
    }

    @Test
    void onlineSearchParsesStructuredAndFallsBackToLlmSplitterOnConfirm() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LlmArtistSplitter splitter = stubSplitter(Map.of(
            "TeddyLoid, 佐倉綾音 & 井上和", "{\"artists\":[\"TeddyLoid\",\"佐倉綾音\",\"井上和\"]}",
            "Kenshi Yonezu & Masaki Suda", "{\"artists\":[\"Kenshi Yonezu\",\"Masaki Suda\"]}"
        ), calls);

        ReviewQueueService queue = new ReviewQueueService(config);
        ProcessedFileLogger logger = new ProcessedFileLogger(config, null);
        OnlineIdentificationService online = new OnlineIdentificationService(
            config, queue, tagWriter, null, splitter);

        // Candidate 1 provides explicit structured arrays; Candidate 2 omits arrays and falls back to LlmArtistSplitter
        String json = """
            {
              "needs_second_round": false,
              "candidates": [
                {
                  "title": "Beyond the Night",
                  "artist": "TeddyLoid, 佐倉綾音 & 井上和",
                  "artists": ["TeddyLoid", "佐倉綾音", "井上和"],
                  "album_artist": "TeddyLoid, 佐倉綾音 & 井上和",
                  "album_artists": ["TeddyLoid", "佐倉綾音", "井上和"],
                  "release_date": "2026-11-11",
                  "edition": "Single",
                  "confidence": 0.95,
                  "tracks": [{"disc": 1, "track": 1, "title": "Beyond the Night", "artist": "TeddyLoid, 佐倉綾音 & 井上和", "duration": 258}],
                  "source_urls": ["https://musicbrainz.org/release/1"]
                },
                {
                  "title": "Fallback",
                  "artist": "Kenshi Yonezu & Masaki Suda",
                  "album_artist": "Kenshi Yonezu & Masaki Suda",
                  "confidence": 0.9,
                  "tracks": [{"disc": 1, "track": 1, "title": "Gray and Blue", "artist": "Kenshi Yonezu & Masaki Suda", "duration": 200}],
                  "source_urls": ["https://musicbrainz.org/release/1"]
                }
              ],
              "clues": []
            }
            """;
        var resp = new WebSearchClient.SearchResponse();
        resp.setText(json);
        resp.setProvider("test");
        resp.setModel("model");
        resp.addCitation(WebSearchClient.Citation.of("https://musicbrainz.org/release/1", "MB", ""));
        OnlineIdentificationService.Parsed parsed = online.parse(resp);
        assertEquals(2, parsed.candidates.size());
        assertEquals(List.of("TeddyLoid", "佐倉綾音", "井上和"), parsed.candidates.get(0).getAlbumArtists());
        assertEquals(List.of("TeddyLoid", "佐倉綾音", "井上和"), parsed.candidates.get(0).getTracks().get(0).getArtists());
        assertEquals(List.of("Kenshi Yonezu", "Masaki Suda"), parsed.candidates.get(1).getAlbumArtists());
        assertEquals(List.of("Kenshi Yonezu", "Masaki Suda"), parsed.candidates.get(1).getTracks().get(0).getArtists());

        // End-to-end confirmOnlineCandidate writes ARTISTS, ALBUM_ARTISTS, and credit tags
        Path folder = dir.resolve("monitor/Beyond");
        File src = createWav(folder.resolve("1.01. Beyond the Night.wav"),
            "TeddyLoid, 佐倉綾音 & 井上和", "Beyond the Night", "Beyond the Night", "1");
        ReviewItem item = queue.enqueueRecoveryFolder(folder.toString(), null, List.of(src), tagWriter, "hash-1");
        OnlineTrackMatcher matcher = new OnlineTrackMatcher(tagWriter);
        ReviewItem.OnlineCandidate chosen = parsed.candidates.get(0);
        // Simulate legacy persisted candidate without arrays to verify confirmOnlineCandidate also splits!
        chosen.setArtists(List.of());
        chosen.setAlbumArtists(List.of());
        chosen.getTracks().get(0).setArtists(List.of());
        matcher.match(chosen, List.of(src), List.of(2));
        item.setOnlineCandidates(List.of(chosen));
        item.setEvidenceHash(online.evidenceHashForFolder(folder.toFile()));
        queue.update(item);

        try (RecoveryService recovery = new RecoveryService(
                config, null, logger, queue, new FolderAlbumCache(null, null, null), null,
                new FileSystemUtils(config), tagWriter, null, null, splitter)) {
            recovery.confirmOnlineCandidate(item.getId(), chosen.getId(),
                "Beyond the Night", "TeddyLoid, 佐倉綾音 & 井上和", "2026-11-11", "Single",
                null, null, RecoveryService.DuplicatePolicy.ASK, null);
        }

        Path archived = output.resolve("TeddyLoid, 佐倉綾音 & 井上和/Beyond the Night (Single)/1.01 TeddyLoid, 佐倉綾音 & 井上和 - Beyond the Night.wav");
        assertTrue(Files.isRegularFile(archived));
        MusicMetadata written = tagWriter.readTags(archived.toFile());
        assertEquals("TeddyLoid, 佐倉綾音 & 井上和", written.getArtist());
        assertEquals(List.of("TeddyLoid", "佐倉綾音", "井上和"), written.getArtists());
        assertEquals("TeddyLoid, 佐倉綾音 & 井上和", written.getAlbumArtist());
        assertEquals(List.of("TeddyLoid", "佐倉綾音", "井上和"), written.getAlbumArtists());
        assertNotNull(written.getArtistCredit());
        assertTrue(written.getArtistCredit().contains("TeddyLoid"));
        assertEquals(written.getArtistCredit(), written.getAlbumArtistCredit());
    }

    @Test
    void manualAlbumArtistsOverrideAndCollapsePolicyAreRespectedInConfirm() throws Exception {
        LlmArtistSplitter splitter = stubSplitter(Map.of(), new AtomicInteger());
        ReviewQueueService queue = new ReviewQueueService(config);
        ProcessedFileLogger logger = new ProcessedFileLogger(config, null);

        Path folder = dir.resolve("monitor/Manual");
        File src = createWav(folder.resolve("01. Track.wav"), "A & B", "Track", "Album", "1");
        ReviewItem item = queue.enqueueRecoveryFolder(folder.toString(), null, List.of(src), tagWriter, "h");
        ReviewItem.OnlineCandidate c = new ReviewItem.OnlineCandidate();
        c.setId("cand-1");
        c.setTitle("Album");
        c.setArtist("A & B");
        c.setAlbumArtist("A & B");
        ReviewItem.OnlineTrack t = new ReviewItem.OnlineTrack();
        t.setDiscNo(1);
        t.setTrackNo(1);
        t.setTitle("Track");
        t.setArtist("A & B");
        t.setMatchedFilePath(src.getAbsolutePath());
        t.setMatchConfidence(0.95);
        c.getTracks().add(t);
        item.setOnlineCandidates(List.of(c));
        OnlineIdentificationService online = new OnlineIdentificationService(config, queue, tagWriter, null, splitter);
        item.setEvidenceHash(online.evidenceHashForFolder(folder.toFile()));
        queue.update(item);

        try (RecoveryService recovery = new RecoveryService(
                config, null, logger, queue, new FolderAlbumCache(null, null, null), null,
                new FileSystemUtils(config), tagWriter, null, null, splitter)) {
            recovery.confirmOnlineCandidate(item.getId(), c.getId(),
                "Album", "A & B", "2026-01-01", "", null, null,
                RecoveryService.DuplicatePolicy.ASK, List.of("A", "B"));
        }

        Path archived = output.resolve("A & B/Album/1.01 A & B - Track.wav");
        assertTrue(Files.isRegularFile(archived));
        MusicMetadata written = tagWriter.readTags(archived.toFile());
        assertEquals(List.of("A", "B"), written.getAlbumArtists());
        assertNotNull(written.getAlbumArtistCredit());
    }

    @Test
    void failedFileHandlerSplitsSingleAndAlbumMultiArtists() throws Exception {
        LlmArtistSplitter splitter = stubSplitter(Map.of(
            "TeddyLoid, 佐倉綾音 & 井上和", "{\"artists\":[\"TeddyLoid\",\"佐倉綾音\",\"井上和\"]}",
            "A feat. B", "{\"artists\":[\"A\",\"B\"]}"
        ), new AtomicInteger());
        ArtistMatchingService noOpMatcher = new ArtistMatchingService(config) {
            @Override
            public MatchResult matchArtist(String sourceArtist) {
                return null;
            }
        };
        FailedFileHandler handler = new FailedFileHandler(
            config, tagWriter, new CoverArtService(null, null), new ProcessedFileLogger(config, null),
            new FileSystemUtils(config), noOpMatcher, splitter);

        // Single partial file with composite artist
        File singlePartial = createWav(partial.resolve("Beyond.wav"),
            "TeddyLoid, 佐倉綾音 & 井上和", "Beyond the Night", "Beyond the Night");
        assertTrue(handler.retryPartialWithLlm(singlePartial));
        Path singleArchived = output.resolve("TeddyLoid, 佐倉綾音 & 井上和/Beyond the Night/Beyond.wav");
        assertTrue(Files.isRegularFile(singleArchived));
        MusicMetadata singleTags = tagWriter.readTags(singleArchived.toFile());
        assertEquals(List.of("TeddyLoid", "佐倉綾音", "井上和"), singleTags.getArtists());
        assertEquals(List.of("TeddyLoid", "佐倉綾音", "井上和"), singleTags.getAlbumArtists());
        assertNotNull(singleTags.getArtistCredit());

        // Partial album folder with multiple tracks and collaboration
        Path albumPartial = partial.resolve("CollabAlbum");
        createWav(albumPartial.resolve("01.wav"), "A feat. B", "Track 1", "CollabAlbum");
        createWav(albumPartial.resolve("02.wav"), "C", "Track 2", "CollabAlbum");
        assertTrue(handler.retryPartialWithLlm(albumPartial.toFile()));

        Path track1 = output.resolve("A, B, C/CollabAlbum/01.wav");
        Path track2 = output.resolve("A, B, C/CollabAlbum/02.wav");
        assertTrue(Files.isRegularFile(track1));
        assertTrue(Files.isRegularFile(track2));
        MusicMetadata t1Tags = tagWriter.readTags(track1.toFile());
        assertEquals(List.of("A", "B"), t1Tags.getArtists());
        assertEquals(List.of("A", "B", "C"), t1Tags.getAlbumArtists());
        assertEquals(List.of("C"), tagWriter.readTags(track2.toFile()).getArtists());
    }

    @Test
    void unresolvedAlbumArchiveSplitsAndPersistsStructuredArtists() throws Exception {
        LlmArtistSplitter splitter = stubSplitter(Map.of(
            "TeddyLoid, 佐倉綾音 & 井上和", "{\"artists\":[\"TeddyLoid\",\"佐倉綾音\",\"井上和\"]}"
        ), new AtomicInteger());

        FolderAlbumCache cache = new FolderAlbumCache(null, null, null);
        ProcessedFileLogger logger = new ProcessedFileLogger(config, null);
        AlbumBatchProcessor batch = new AlbumBatchProcessor(
            config, cache, tagWriter, logger, new CoverArtService(null, null),
            splitter, ReidentifyReplacementRegistry.forReviewQueuePath(config.getReviewQueuePath()));

        Path folder = dir.resolve("monitor/Unresolved");
        File src = createWav(folder.resolve("01.wav"), "TeddyLoid, 佐倉綾音 & 井上和", "Beyond the Night", "Beyond the Night");
        MusicMetadata md = new MusicMetadata();
        md.setTitle("Beyond the Night");
        md.setArtist("TeddyLoid, 佐倉綾音 & 井上和");
        md.setAlbum("Beyond the Night");
        md.setDiscNo("1");
        md.setTrackNo("1");

        batch.addPendingFile(folder.toString(), src, src, null, md, null);
        var result = batch.processPendingFilesAsUnresolvedAlbum(folder.toString(), false);
        assertEquals(1, result.getSuccessCount());

        Path archived = output.resolve("TeddyLoid, 佐倉綾音 & 井上和/Beyond the Night/1.01 TeddyLoid, 佐倉綾音 & 井上和 - Beyond the Night.wav");
        assertTrue(Files.isRegularFile(archived));
        MusicMetadata written = tagWriter.readTags(archived.toFile());
        assertEquals(List.of("TeddyLoid", "佐倉綾音", "井上和"), written.getArtists());
        assertEquals(List.of("TeddyLoid", "佐倉綾音", "井上和"), written.getAlbumArtists());
        assertNotNull(written.getAlbumArtistCredit());
        assertEquals("{\"display\":\"TeddyLoid, 佐倉綾音 & 井上和\",\"names\":[\"TeddyLoid\",\"佐倉綾音\",\"井上和\"],\"before\":[\"\",\", \",\" & \"]}",
            ArtistCreditSupport.buildCreditJson("TeddyLoid, 佐倉綾音 & 井上和", List.of("TeddyLoid", "佐倉綾音", "井上和")));
    }
}
