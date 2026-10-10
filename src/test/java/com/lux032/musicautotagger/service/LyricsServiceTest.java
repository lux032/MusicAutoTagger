package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.util.MetadataUtils;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LyricsServiceTest {
    HttpServer server;
    LyricsService service;
    List<String> queries = new CopyOnWriteArrayList<>();
    Queue<Response> replies = new ConcurrentLinkedQueue<>();
    record Response(int code, String body, String retryAfter, long delay) {}
    static final String PLAIN = "{\"id\":7,\"trackName\":\"Alive\",\"artistName\":\"Earth & Fire\",\"duration\":120.25,\"plainLyrics\":\"words\"}";
    static MusicConfig newConfig() throws Exception {
        var constructor = MusicConfig.class.getDeclaredConstructor(); constructor.setAccessible(true);
        return constructor.newInstance();
    }
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
        server.createContext("/api", exchange -> {
            queries.add(exchange.getRequestURI().toString());
            assertEquals("LyricsTest/contact", exchange.getRequestHeaders().getFirst("User-Agent"));
            Response response = replies.poll();
            if (response == null) response = new Response(404, "{}", null, 0);
            try { Thread.sleep(response.delay()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try {
                if (response.retryAfter() != null) exchange.getResponseHeaders().set("Retry-After", response.retryAfter());
                byte[] bytes = response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(response.code(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception ignored) {} finally { exchange.close(); }
        });
        server.start();
        MusicConfig config = newConfig(); config.setUserAgent("LyricsTest/contact");
        service = new LyricsService(config, "http://127.0.0.1:" + server.getAddress().getPort() + "/api");
    }
    @AfterEach void teardown() throws Exception { service.close(); server.stop(0); }
    void reply(int code, String body) { replies.add(new Response(code, body, null, 0)); }
    LyricsService.Result lookup() { return service.lookup("Alive", "Earth & Fire", "Album", 120.25, true); }

    @Test void localDurationRequiredAndBounded() {
        for (Double d : Arrays.asList(null, 0.0, 0.99, Double.NaN, 3600.01))
            assertEquals(LyricsService.Status.UNSAFE, service.lookup("Alive", "Earth & Fire", null, d, true).status());
        assertTrue(queries.isEmpty());
        assertTrue(LyricsMatcher.reliableDuration(1.0)); assertTrue(LyricsMatcher.reliableDuration(3600.0));
    }
    @Test void getUsesPrecisionAndReturnsPlain() {
        reply(200, PLAIN);
        assertEquals("words", lookup().lyrics());
        assertTrue(queries.get(0).contains("duration=120.25"));
        assertTrue(queries.get(0).contains("album_name=Album"));
    }
    @Test void album404FallsBackThenSearchUsesNamedParameters() {
        reply(404, "{}"); reply(404, "{}"); reply(200, "[" + PLAIN + "]");
        assertEquals(LyricsService.Status.FOUND, lookup().status());
        assertEquals(3, queries.size());
        assertFalse(queries.get(1).contains("album_name"));
        assertTrue(queries.get(1).contains("duration=120.25"));
        assertTrue(queries.get(2).startsWith("/api/search?track_name="));
        assertFalse(queries.get(2).contains("?q="));
    }
    @Test void empty200SearchesButInstrumentalTerminates() {
        reply(200, PLAIN.replace(",\"plainLyrics\":\"words\"", "")); reply(200, "[" + PLAIN + "]");
        assertEquals("words", lookup().lyrics()); assertEquals(2, queries.size());
        queries.clear(); reply(200, PLAIN.replace("\"id\":7", "\"id\":7,\"instrumental\":true"));
        assertEquals(LyricsService.Status.INSTRUMENTAL, lookup().status()); assertEquals(1, queries.size());
        assertEquals(LyricsService.Status.INSTRUMENTAL, service.lookup("Alive (Instrumental)", "Earth & Fire", null, 120.25, true).status());
        assertEquals(1, queries.size());
    }
    @Test void identityAndDurationAreRecheckedOnGet() {
        reply(200, PLAIN.replace("120.25", "122.251")); assertEquals(LyricsService.Status.UNSAFE, lookup().status());
        reply(200, PLAIN.replace("120.25", "122.25")); assertEquals(LyricsService.Status.FOUND, lookup().status());
        reply(200, PLAIN.replace("Earth & Fire", "Earth")); assertEquals(LyricsService.Status.UNSAFE, lookup().status());
        reply(200, PLAIN.replace("Alive", "Alive (Live)")); assertEquals(LyricsService.Status.UNSAFE, lookup().status());
    }
    @Test void ambiguityAndTruncationAndWeakSearchAreRejected() {
        reply(404, "{}"); reply(404, "{}"); reply(200, "[" + PLAIN + "," + PLAIN + "]");
        assertEquals(LyricsService.Status.FOUND, lookup().status());
        reply(404, "{}"); reply(404, "{}"); reply(200, "[" + String.join(",", Collections.nCopies(20, PLAIN)) + "]");
        assertEquals(LyricsService.Status.UNSAFE, lookup().status());
        queries.clear(); reply(404, "{}");
        assertEquals(LyricsService.Status.UNSAFE, service.lookup("Alive", "Earth & Fire", null, 120.25, false).status());
        assertEquals(1, queries.size());
    }
    LyricsService.Result search(String... nodes) {
        reply(404, "{}");reply(404, "{}");reply(200, "["+String.join(",",nodes)+"]");return lookup();
    }
    @Test void exactDifferentIdsChooseMinimumAndPreserveOriginalText() {
        String lower=PLAIN.replace("\"id\":7","\"id\":3");
        assertEquals(3L,search(PLAIN,lower).recordId());
        String whitespace=lower.replace("words","\\r\\nwords  \\r\\n");
        assertEquals("\r\nwords  \r\n",search(PLAIN,whitespace).lyrics());
        MusicMetadata md=new MusicMetadata();md.setTitle("Alive");md.setArtist("Earth & Fire");md.setAlbum("Album");md.setLocalPreciseDuration(120.25);
        reply(404,"{}");reply(404,"{}");reply(200,"["+PLAIN+","+lower+"]");service.enrich(md);
        assertEquals(3L,md.getAutomaticLyrics().recordId());
    }
    @Test void sameIdConflictIsUnsafeEvenBeforeHardFiltering() {
        assertEquals(LyricsService.Status.UNSAFE,search(PLAIN,PLAIN.replace("Alive","Unrelated")).status());
    }
    @Test void differingWholeNodeFieldsRemainAmbiguous() {
        String lower=PLAIN.replace("\"id\":7","\"id\":3");
        for(String different:List.of(lower.replace("120.25","120.251"),lower.replace("Alive","Alive!"),
            lower.replace("words","Words"),lower.replace("words","word  s"),
            lower.replace("\"id\":3","\"id\":3,\"albumName\":\"Other\""),
            lower.replace("\"id\":3","\"id\":3,\"isrc\":\"different\""),
            lower.replace("\"id\":3","\"id\":3,\"instrumental\":true"))) {
            assertEquals(LyricsService.Status.UNSAFE,search(PLAIN,different).status());
        }
        String sync=PLAIN.replace("\"plainLyrics\":", "\"syncedLyrics\":\"[00:01.00]words\",\"plainLyrics\":");
        assertEquals(LyricsService.Status.UNSAFE,search(sync,lower).status());
        assertEquals(LyricsService.Status.UNSAFE,search(sync,sync.replace("\"id\":7","\"id\":3").replace("00:01","00:02")).status());
        assertEquals(LyricsService.Status.UNSAFE,search(PLAIN.replace("120.25","120"),lower.replace("120.25","120.0")).status());
    }
    @Test void invalidIdsAndInstrumentalTypesAreUnsafeAndTruncationPrecedesDedup() {
        for(String id:List.of("null","\"7\"","7.5","-1","0","9223372036854775808"))
            assertEquals(LyricsService.Status.UNSAFE,search(PLAIN.replace("\"id\":7","\"id\":"+id)).status());
        assertEquals(LyricsService.Status.UNSAFE,search(PLAIN.replace("\"id\":7,", "")).status());
        for(String value:List.of("null","\"false\"","0"))
            assertEquals(LyricsService.Status.UNSAFE,search(PLAIN.replace("\"id\":7","\"id\":7,\"instrumental\":"+value)).status());
        for(int count:List.of(20,21))assertEquals(LyricsService.Status.UNSAFE,search(Collections.nCopies(count,PLAIN).toArray(String[]::new)).status());
    }
    @Test void equivalentInvalidSyncUsesOnlyRepresentativesPlain() {
        String invalid=PLAIN.replace("\"plainLyrics\":", "\"syncedLyrics\":\"[99:00.00]bad\",\"plainLyrics\":");
        assertEquals("words",search(invalid,invalid.replace("\"id\":7","\"id\":3")).lyrics());
        String empty=PLAIN.replace("\"words\"","null");
        assertEquals(LyricsService.Status.NOT_FOUND,search(empty,empty.replace("\"id\":7","\"id\":3").replace("null","\"  \\r\\n\"")).status());
    }
    @Test void normalizationPreservesNonLatinMarksWhileFoldingLatinAccents() {
        for (String[] pair : List.of(new String[] {"ハード", "ハート"}, new String[] {"パン", "ハン"},
            new String[] {"ビール", "ヒール"}, new String[] {"का", "क"}, new String[] {"กิ", "ก"})) {
            assertNotEquals(LyricsMatcher.normalize(pair[0]), LyricsMatcher.normalize(pair[1]));
            assertFalse(LyricsMatcher.sameIdentity(pair[0], "artist", pair[1], "artist"));
            assertFalse(LyricsMatcher.sameIdentity("title", pair[0], "title", pair[1]));
        }
        assertEquals(LyricsMatcher.normalize("Beyoncé"), LyricsMatcher.normalize("Beyonce"));
        assertEquals(LyricsMatcher.normalize("Ｆóó’s"), LyricsMatcher.normalize("foos"));
        assertEquals(LyricsMatcher.normalize("パン"), LyricsMatcher.normalize("ハ\u309aン"));
    }
    @Test void getRejectsDifferentJapaneseTitleDespiteSameDuration() {
        reply(200, PLAIN.replace("Alive", "ハート"));
        assertEquals(LyricsService.Status.UNSAFE,
            service.lookup("ハード", "Earth & Fire", "Album", 120.25, true).status());
        assertEquals(1, queries.size());
    }
    @Test void finalIdentityGuardRejectsChangedJapaneseVoicing() {
        MusicMetadata md = new MusicMetadata();
        md.setTitle("パン"); md.setArtist("artist"); md.setLocalPreciseDuration(120.25);
        md.setLyrics("automatic words");
        md.setAutomaticLyrics(new MusicMetadata.AutomaticLyrics("lrclib", 7L, null, "パン", "artist",
            120.25, List.of()));
        md.discardStaleAutomaticLyrics(); assertEquals("automatic words", md.getLyrics());
        md.setTitle("ハン"); md.discardStaleAutomaticLyrics(); assertNull(md.getLyrics());
    }
    @Test void syncValidationFallsBackToSameRecordsPlain() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var node = mapper.readTree(PLAIN);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("syncedLyrics", "[02:03.00]too late");
        assertEquals("words", LyricsMatcher.text(node, 120.25));
        assertFalse(LyricsMatcher.validSynced("[00:02.0]a\n[00:01.0]b", 120));
        assertFalse(LyricsMatcher.validSynced("[00:99.0]a", 120));
        assertTrue(LyricsMatcher.validSynced("[00:00.00]a\n[02:02.25]b", 120.25));
        assertTrue(LyricsMatcher.versions("Alive Demon").isEmpty());
        assertEquals(Set.of("live", "demo"), LyricsMatcher.versions("Alive (live) - demo"));
        assertTrue(LyricsMatcher.sameIdentity("Ｆóó’s!", "Earth & Fire", "foos", "Earth & Fire"));
        assertFalse(LyricsMatcher.sameIdentity("Alive", "Earth & Fire", "Alive", "Fire & Earth"));
    }
    @Test void retryAfterDefersAndSharesCooldown() {
        replies.add(new Response(429, "{}", "3600", 0));
        long start = System.nanoTime();
        assertEquals(LyricsService.Status.DEFERRED, lookup().status());
        assertEquals(LyricsService.Status.DEFERRED, lookup().status());
        assertEquals(1, queries.size()); assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500);
    }
    @Test void failuresAreNot404AndCircuitBoundsAttempts() {
        for (int i = 0; i < 3; i++) reply(503, "{}");
        assertEquals(LyricsService.Status.DEFERRED, lookup().status());
        assertEquals(3, queries.size()); assertEquals(LyricsService.Status.DEFERRED, lookup().status());
        assertEquals(3, queries.size());
    }
    @Test void retriesAcrossStepsHaveOneTotalRequestCap() {
        reply(503, "{}"); reply(503, "{}"); reply(404, "{}");
        reply(503, "{}"); reply(503, "{}"); reply(404, "{}");
        assertEquals(LyricsService.Status.DEFERRED, lookup().status());
        assertEquals(LyricsService.MAX_REQUESTS, queries.size());
        assertTrue(queries.stream().noneMatch(q -> q.startsWith("/api/search")));
    }
    @Test void sameTextDifferentVersionsNeverSelectsAndVocalConflictIsUnsafe() {
        String live = PLAIN.replace("Alive", "Alive (Live)");
        reply(404, "{}"); reply(404, "{}"); reply(200, "[" + live + "]");
        assertEquals(LyricsService.Status.NOT_FOUND, lookup().status());
        String instrumental = PLAIN.replace("\"id\":7", "\"id\":8,\"instrumental\":true");
        reply(404, "{}"); reply(404, "{}"); reply(200, "[" + PLAIN + "," + instrumental + "]");
        assertEquals(LyricsService.Status.UNSAFE, lookup().status());
    }
    @Test void responseTimeoutIsBoundedAndCloseCancels() throws Exception {
        for (int i = 0; i < 3; i++) replies.add(new Response(200, PLAIN, null, 4000));
        long start = System.nanoTime(); assertEquals(LyricsService.Status.DEFERRED, lookup().status());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < LyricsService.BUDGET_MS);
        assertEquals(3, queries.size());
        service.close(); assertEquals(LyricsService.Status.CANCELLED, lookup().status());
    }
    @Test void interruptCancelsWithoutRequest() {
        Thread.currentThread().interrupt();
        try { assertEquals(LyricsService.Status.CANCELLED, lookup().status()); assertTrue(queries.isEmpty()); }
        finally { Thread.interrupted(); }
    }
    @Test void provenanceCopiesSerializesAndGuardsChangedIdentity() throws Exception {
        MusicMetadata md = new MusicMetadata(); md.setTitle("Alive"); md.setArtist("Earth & Fire"); md.setRecordingId("r"); md.setLocalPreciseDuration(120.25);
        reply(200, PLAIN); service.enrich(md);
        assertEquals(7L, md.getAutomaticLyrics().recordId());
        MusicMetadata copy = md.copyWithoutHeavyFields();
        ObjectMapper mapper = new ObjectMapper(); copy = mapper.readValue(mapper.writeValueAsString(copy), MusicMetadata.class);
        copy.discardStaleAutomaticLyrics(); assertEquals("words", copy.getLyrics());
        copy.setTitle("Alive (Live)"); copy.discardStaleAutomaticLyrics(); assertNull(copy.getLyrics());
        md.setRecordingId("other"); md.discardStaleAutomaticLyrics(); assertNull(md.getLyrics());
        md.setLyrics("existing"); md.setTitle("Other"); md.discardStaleAutomaticLyrics(); assertEquals("existing", md.getLyrics());
    }
    @Test void mergeAndOverlayPreserveSourceLyricsAndBlankIsMissing() {
        MusicMetadata source = new MusicMetadata(); source.setLyrics("existing");
        MusicMetadata newer = new MusicMetadata(); newer.setLyrics("automatic");
        assertEquals("existing", MetadataUtils.mergeMetadata(source, newer).getLyrics());
        MusicMetadata incoming = new MusicMetadata(); incoming.setLyrics("new");
        MetadataUtils.overlayTrackCredits(incoming, source); assertEquals("existing", source.getLyrics());
        source.setLyrics("  "); MetadataUtils.preserveTrackCredits(incoming, source); assertEquals("new", source.getLyrics());
    }
    @Test void vorbisVariantsAreDetectedAndRead() throws Exception {
        for (String id : List.of("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS")) {
            VorbisCommentTag tag = new VorbisCommentTag(); tag.setField(id, "old words");
            assertEquals(TagWriterService.LyricsState.PRESENT, TagWriterService.lyricsState(tag));
            assertEquals("old words", TagWriterService.readEmbeddedLyrics(tag));
        }
        assertEquals(TagWriterService.LyricsState.ABSENT, TagWriterService.lyricsState(new VorbisCommentTag()));
    }
    @Test void id3MultipleLyricsAndSyltSurviveFullTextUpdate() throws Exception {
        var tag = new org.jaudiotagger.tag.id3.ID3v24Tag();
        var first = new org.jaudiotagger.tag.id3.ID3v24Frame("USLT");
        first.setBody(new org.jaudiotagger.tag.id3.framebody.FrameBodyUSLT((byte) 3, "eng", "one", "first"));
        var second = new org.jaudiotagger.tag.id3.ID3v24Frame("USLT");
        second.setBody(new org.jaudiotagger.tag.id3.framebody.FrameBodyUSLT((byte) 3, "jpn", "two", "second"));
        tag.setField(first); tag.setField(second);
        var sync = new org.jaudiotagger.tag.id3.ID3v24Frame("SYLT");
        sync.setBody(new org.jaudiotagger.tag.id3.framebody.FrameBodySYLT(3, "eng", 2, 1, "sync", new byte[] {0})); tag.setField(sync);
        assertEquals(TagWriterService.LyricsState.PRESENT, TagWriterService.lyricsState(tag));
        MusicMetadata md = new MusicMetadata(); md.setTitle("new title"); md.setLyrics("replacement");
        var method = TagWriterService.class.getDeclaredMethod("updateTextTags", org.jaudiotagger.tag.Tag.class, MusicMetadata.class); method.setAccessible(true);
        method.invoke(new TagWriterService(newConfig()), tag, md);
        assertEquals(2, tag.getFields("USLT").size()); assertEquals(1, tag.getFields("SYLT").size());
        assertEquals("first", ((org.jaudiotagger.tag.id3.framebody.FrameBodyUSLT) ((org.jaudiotagger.tag.id3.AbstractID3v2Frame) tag.getFields("USLT").get(0)).getBody()).getLyric());
        var onlySync = new org.jaudiotagger.tag.id3.ID3v24Tag(); onlySync.setField(sync);
        assertEquals(TagWriterService.LyricsState.PRESENT, TagWriterService.lyricsState(onlySync));
        method.invoke(new TagWriterService(newConfig()), onlySync, md); assertFalse(onlySync.hasField("USLT"));
        var mp4 = new org.jaudiotagger.tag.mp4.Mp4Tag(); mp4.setField(FieldKey.LYRICS, "old mp4");
        method.invoke(new TagWriterService(newConfig()), mp4, md); assertEquals("old mp4", mp4.getFirst(FieldKey.LYRICS));
        for (String id : List.of("UNSYNCEDLYRICS", "UNSYNCED LYRICS")) {
            var vorbis = new VorbisCommentTag(); vorbis.setField(id, "old variant");
            method.invoke(new TagWriterService(newConfig()), vorbis, md);
            assertEquals("old variant", vorbis.getFirst(id)); assertFalse(vorbis.hasField("LYRICS"));
        }
    }
    @Test void localAudioHeaderPrecisionAndUnknownRead(@TempDir Path dir) throws Exception {
        int dataLength = 8000 * 120 + 2000;
        var b = java.nio.ByteBuffer.allocate(44 + dataLength).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + dataLength).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(8000).putInt(8000).putShort((short) 1).putShort((short) 8);
        b.put("data".getBytes()).putInt(dataLength);
        Path wav = dir.resolve("local.wav"); Files.write(wav, b.array());
        assertEquals(120.25, TagWriterService.readPreciseDuration(wav.toFile()), 0.001);
        Path broken = dir.resolve("bad.mp3"); Files.writeString(broken, "bad");
        assertNull(TagWriterService.readPreciseDuration(broken.toFile()));
        assertEquals(TagWriterService.LyricsState.UNKNOWN, new TagWriterService(newConfig()).embeddedLyricsState(broken.toFile()));
    }
    @Test void fpcalcJsonPrecisionIsRetainedButRealBinaryNotAssumed(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("fpcalc-test");
        Files.writeString(script, "#!/bin/sh\nprintf '%s' '{\"duration\":120.25,\"fingerprint\":\"test\"}'\n");
        assertTrue(script.toFile().setExecutable(true));
        Path audio = dir.resolve("file.wav"); Files.writeString(audio, "stub input");
        var fingerprint = new AudioFingerprintService(newConfig());
        try {
            fingerprint.setFpcalcPath(script.toString());
            var result = fingerprint.generateFingerprint(audio.toFile());
            assertEquals(120, result.getDuration()); assertEquals(120.25, result.getPreciseDuration());
        } finally { fingerprint.close(); }
    }
    @Test void closeAbortsActiveResponse() throws Exception {
        replies.add(new Response(200, PLAIN, null, 10000));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(this::lookup);
            for (int i = 0; i < 100 && queries.isEmpty(); i++) Thread.sleep(10);
            assertFalse(queries.isEmpty()); service.close();
            assertEquals(LyricsService.Status.CANCELLED, future.get(1, TimeUnit.SECONDS).status());
        } finally { executor.shutdownNow(); }
    }
    @Test void exportPlainTextDoesNotOverwrite(@TempDir Path dir) throws Exception {
        MusicConfig config = newConfig(); TagWriterService writer = new TagWriterService(config);
        var method = TagWriterService.class.getDeclaredMethod("exportLyricsToFile", java.io.File.class, String.class); method.setAccessible(true);
        method.invoke(writer, dir.resolve("song.flac").toFile(), "plain words");
        assertEquals("plain words", Files.readString(dir.resolve("song.txt"))); assertFalse(Files.exists(dir.resolve("song.lrc")));
        method.invoke(writer, dir.resolve("song.flac").toFile(), "replacement"); assertEquals("plain words", Files.readString(dir.resolve("song.txt")));
        method.invoke(writer, dir.resolve("song.flac").toFile(), "[00:01.00]sync"); assertTrue(Files.exists(dir.resolve("song.lrc")));
    }
}
