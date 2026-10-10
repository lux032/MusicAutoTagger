package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import org.jaudiotagger.audio.*;
import org.jaudiotagger.tag.*;
import org.jaudiotagger.tag.images.StandardArtwork;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LyricsBackfillServiceTest {
    @TempDir Path dir;
    MusicConfig config;
    TagWriterService writer;
    @BeforeEach void setup() throws Exception {
        var ctor = MusicConfig.class.getDeclaredConstructor(); ctor.setAccessible(true); config = ctor.newInstance();
        config.setOutputDirectory(dir.toString()); config.setMonitorDirectory(dir.resolve("incoming").toString());
        config.setRecoveryTrashDirectory(dir.resolve("retired").toString()); writer = new TagWriterService(config);
    }
    Path flac(String name) throws Exception {
        byte[] data = new byte[42 + 512];
        System.arraycopy(new byte[] {'f','L','a','C',(byte)128,0,0,34}, 0, data, 0, 8);
        // 44100 Hz, stereo, 16 bits, exactly 120.25s; synthetically opaque audio bytes.
        long info = ((long)44100 << 44) | (1L << 41) | (15L << 36) | 5303025L;
        for (int i=0;i<8;i++) data[18+i]=(byte)(info >>> (56-8*i));
        for(int i=42;i<data.length;i++) data[i]=(byte)(i*7);
        Path file=dir.resolve(name); Files.createDirectories(file.getParent()); Files.write(file,data);
        AudioFile audio=AudioFileIO.read(file.toFile()); org.jaudiotagger.tag.Tag tag=audio.getTagOrCreateAndSetDefault();
        tag.setField(FieldKey.TITLE,"Alive"); tag.setField(FieldKey.ARTIST,"Earth & Fire"); tag.setField(FieldKey.ALBUM,"Album"); tag.setField(FieldKey.COMMENT,"keep me");
        audio.commit(); return file;
    }
    static class StubLyrics extends LyricsService {
        int calls; volatile CountDownLatch entered, release;
        Result answer = new Result(Status.FOUND,"words",7L);
        StubLyrics(MusicConfig config) { super(config,"http://127.0.0.1:1/api"); }
        @Override public Result lookup(String t,String a,String al,Double d,boolean strong) {
            calls++;
            if (entered!=null) { entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return Result.of(Status.CANCELLED); } }
            return answer;
        }
    }
    void await(LyricsBackfillService service) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(Boolean.TRUE.equals(service.getStatus().get("running")) && System.nanoTime()<end) Thread.sleep(10);
        assertEquals(false,service.getStatus().get("running"));
    }
    @Test void lyricsOnlyPreservesMtimePermissionsTagsAudioAndExports() throws Exception {
        Path file=flac("song.flac"); FileTime time=FileTime.fromMillis(1234567000); Files.setLastModifiedTime(file,time);
        var permissions=Files.getPosixFilePermissions(file); var before=AudioFileIO.read(file.toFile());
        var h=before.getAudioHeader(); String audioHash=LyricsOnlyWriter.hash(file,h.getAudioDataStartPosition(),h.getAudioDataEndPosition()-h.getAudioDataStartPosition());
        assertTrue(LyricsOnlyWriter.write(dir,file,LyricsOnlyWriter.identity(file),"words",true));
        var after=AudioFileIO.read(file.toFile()); assertEquals("words",after.getTag().getFirst(FieldKey.LYRICS));
        assertEquals("keep me",after.getTag().getFirst(FieldKey.COMMENT)); assertEquals("Alive",after.getTag().getFirst(FieldKey.TITLE));
        assertEquals(time,Files.getLastModifiedTime(file)); assertEquals(permissions,Files.getPosixFilePermissions(file));
        h=after.getAudioHeader(); assertEquals(audioHash,LyricsOnlyWriter.hash(file,h.getAudioDataStartPosition(),h.getAudioDataEndPosition()-h.getAudioDataStartPosition()));
        assertEquals(120.25,h.getPreciseTrackLength(),0.001); assertEquals("words",Files.readString(dir.resolve("song.txt")));
        try(var files=Files.list(dir)) { assertFalse(files.anyMatch(p->p.getFileName().toString().startsWith(".lyrics-work-"))); }
    }
    @Test void syntheticMpegWithUnknownAudioRangeIsSafelySkipped() throws Exception {
        byte[] data=new byte[417*400];
        for(int p=0;p<data.length;p+=417) { data[p]=(byte)255;data[p+1]=(byte)251;data[p+2]=(byte)144;data[p+3]=0; }
        Path file=dir.resolve("synthetic.mp3");Files.write(file,data);
        var audio=AudioFileIO.read(file.toFile()); audio.getTagOrCreateAndSetDefault().setField(FieldKey.TITLE,"Alive");
        audio.getTag().setField(FieldKey.ARTIST,"Earth & Fire");audio.commit();
        var h=AudioFileIO.read(file.toFile()).getAudioHeader();
        assertNull(h.getAudioDataStartPosition());
        byte[] original=Files.readAllBytes(file);
        assertThrows(java.io.IOException.class,()->LyricsOnlyWriter.write(dir,file,LyricsOnlyWriter.identity(file),"words",false));
        assertArrayEquals(original,Files.readAllBytes(file));
        try(var lyrics=new StubLyrics(config);var service=new LyricsBackfillService(config,lyrics,writer)) {
            for(boolean preview:List.of(true,false)) {
                assertTrue(service.start(preview));await(service);
                assertEquals(0,lyrics.calls);assertEquals(0,service.getStatus().get("matched"));
                assertEquals("audio.range.unavailable",service.getStatus().get("skipReason"));
                assertArrayEquals(original,Files.readAllBytes(file));
            }
        }
        assertFalse(Files.exists(dir.resolve("synthetic.txt")));
        assertFalse(Files.exists(dir.resolve(".lyrics-negative-cache.json")));
    }

    @Test void structurallyUnverifiableFlacSkipsBeforeLookupForPreviewAndWrite() throws Exception {
        Path file=flac("blocks.flac");byte[] body=Files.readAllBytes(file);
        byte[] original=new byte[body.length+1024*4];
        System.arraycopy(body,0,original,0,4);
        for(int p=4;p<4+1024*4;p+=4) original[p]=1; // non-final, empty padding blocks
        System.arraycopy(body,4,original,4+1024*4,body.length-4);Files.write(file,original);
        assertEquals("Alive",AudioFileIO.read(file.toFile()).getTag().getFirst(FieldKey.TITLE));
        try(var lyrics=new StubLyrics(config);var service=new LyricsBackfillService(config,lyrics,writer)) {
            for(boolean preview:List.of(true,false)) {
                assertTrue(service.start(preview));await(service);assertEquals(0,lyrics.calls);
                assertEquals(0,service.getStatus().get("matched"));
                assertEquals("flac.audio.range.incomplete",service.getStatus().get("skipReason"));
                assertArrayEquals(original,Files.readAllBytes(file));
            }
        }
        assertFalse(Files.exists(dir.resolve("blocks.txt")));
        assertFalse(Files.exists(dir.resolve(".lyrics-negative-cache.json")));
        try(var files=Files.list(dir)) { assertFalse(files.anyMatch(p->p.getFileName().toString().startsWith(".lyrics-work-"))); }
    }

    @Test void lookupTimeFileMutationPreventsWriteAndPreviewMissDoesNotCache() throws Exception {
        Path file=flac("song.flac");
        try(var lyrics=new StubLyrics(config);var service=new LyricsBackfillService(config,lyrics,writer)) {
            lyrics.answer=LyricsService.Result.of(LyricsService.Status.NOT_FOUND);
            service.start(true);await(service);assertFalse(Files.exists(dir.resolve(".lyrics-negative-cache.json")));
            lyrics.answer=new LyricsService.Result(LyricsService.Status.FOUND,"words",7L);
            lyrics.entered=new CountDownLatch(1);lyrics.release=new CountDownLatch(1);service.start(false);
            assertTrue(lyrics.entered.await(2,TimeUnit.SECONDS));
            try(var guard=LibraryWriteGuard.acquire()) { Files.setLastModifiedTime(file,FileTime.fromMillis(1)); }
            lyrics.release.countDown();await(service);assertEquals(0,service.getStatus().get("updated"));
            assertEquals(TagWriterService.LyricsState.ABSENT,writer.embeddedLyricsState(file.toFile()));
        }
    }
    @Test void persistedCacheIsBoundedOnLoad() throws Exception {
        Map<String,Long> values=new LinkedHashMap<>();long expiry=System.currentTimeMillis()+86400000;
        for(int i=0;i<LyricsNegativeCache.MAX_ENTRIES+3;i++)values.put("key"+i,expiry);
        Path file=dir.resolve("cache.json");new com.fasterxml.jackson.databind.ObjectMapper().writeValue(file.toFile(),values);
        var cache=new LyricsNegativeCache(file,Clock.systemUTC());assertFalse(cache.contains("key0"));assertTrue(cache.contains("key2002"));
        cache.put("latest",LyricsService.Status.NOT_FOUND);
        assertEquals(LyricsNegativeCache.MAX_ENTRIES,new com.fasterxml.jackson.databind.ObjectMapper().readTree(file.toFile()).size());
    }
    @Test void atomicFailureLeavesOriginalIntactAndCleansTemporaryCopy() throws Exception {
        Path file=flac("song.flac"); byte[] original=Files.readAllBytes(file);
        assertThrows(java.io.IOException.class, () -> LyricsOnlyWriter.write(dir,file,LyricsOnlyWriter.identity(file),"words",false,
            (temp,target)->{ throw new AtomicMoveNotSupportedException(temp.toString(),target.toString(),"test"); }));
        assertArrayEquals(original,Files.readAllBytes(file));
        try(var files=Files.list(dir)) { assertFalse(files.anyMatch(p->p.getFileName().toString().startsWith(".lyrics-work-"))); }
    }
    @Test void coverAndAllNonLyricsFieldsSurvive() throws Exception {
        Path file=flac("cover.flac"); var audio=AudioFileIO.read(file.toFile());
        var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes=new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image,"png",bytes);
        var art=new StandardArtwork(); art.setBinaryData(bytes.toByteArray()); art.setMimeType("image/png"); art.setDescription("cover"); art.setPictureType(3);
        audio.getTag().setField(art); audio.getTag().setField(FieldKey.COMPOSER,"composer"); audio.commit();
        assertTrue(LyricsOnlyWriter.write(dir,file,LyricsOnlyWriter.identity(file),"words",false));
        var tag=AudioFileIO.read(file.toFile()).getTag(); assertEquals("composer",tag.getFirst(FieldKey.COMPOSER));
        assertArrayEquals(bytes.toByteArray(),tag.getFirstArtwork().getBinaryData()); assertEquals("cover",tag.getFirstArtwork().getDescription());
    }
    @Test void unsupportedOggOpusAndPathEscapeAreNeverWritten() throws Exception {
        for(String ext:List.of("ogg","opus")) {
            Path file=dir.resolve("song."+ext); Files.writeString(file,"unchanged");
            assertFalse(LyricsOnlyWriter.write(dir,file,LyricsOnlyWriter.identity(file),"words",false)); assertEquals("unchanged",Files.readString(file));
        }
        Path file=flac("song.flac"); assertFalse(LyricsOnlyWriter.write(dir.resolve("other"),file,LyricsOnlyWriter.identity(file),"words",false));
        Path link=dir.resolve("symlink.flac"); Files.createSymbolicLink(link,file);
        assertFalse(LyricsBackfillService.safePath(dir,link));
    }
    @Test void previewDoesNotWriteOrCacheThenFormalRunFills() throws Exception {
        Path file=flac("song.flac"); byte[] original=Files.readAllBytes(file);
        try(var lyrics=new StubLyrics(config); var service=new LyricsBackfillService(config,lyrics,writer)) {
            assertTrue(service.start(true)); await(service); assertArrayEquals(original,Files.readAllBytes(file));
            assertFalse(Files.exists(dir.resolve("song.txt"))); assertFalse(Files.exists(dir.resolve(".lyrics-negative-cache.json")));
            assertTrue(service.start(false)); await(service); assertEquals(2,lyrics.calls);
            assertEquals(1,service.getStatus().get("updated"));
        }
    }
    @Test void conflictCancelAndCloseInterruptLookup() throws Exception {
        flac("song.flac");
        try(var lyrics=new StubLyrics(config); var service=new LyricsBackfillService(config,lyrics,writer)) {
            lyrics.entered=new CountDownLatch(1); lyrics.release=new CountDownLatch(1);
            assertTrue(service.start(false)); assertTrue(lyrics.entered.await(2,TimeUnit.SECONDS)); assertFalse(service.start(true));
            service.cancel(); await(service); assertEquals("cancelled",service.getStatus().get("message")); assertEquals(0,service.getStatus().get("updated"));
            service.close(); assertFalse(service.start(false));
        }
    }
    @Test void embeddedSidecarUnknownAndMissingIdentityAreSkipped() throws Exception {
        Path embedded=flac("embedded.flac"); var audio=AudioFileIO.read(embedded.toFile()); audio.getTag().setField(FieldKey.LYRICS,"old"); audio.commit();
        flac("sidecar.flac"); Files.writeString(dir.resolve("sidecar.lrc"),"existing");
        Files.writeString(dir.resolve("bad.mp3"),"bad");
        Path missing=flac("missing.flac"); audio=AudioFileIO.read(missing.toFile()); audio.getTag().deleteField(FieldKey.ARTIST); audio.commit();
        try(var lyrics=new StubLyrics(config);var service=new LyricsBackfillService(config,lyrics,writer)) {
            service.start(false); await(service); assertEquals(0,lyrics.calls); assertEquals(4,service.getStatus().get("skipped"));
        }
        assertEquals("old",AudioFileIO.read(embedded.toFile()).getTag().getFirst(FieldKey.LYRICS));
    }
    @Test void scanExcludesMonitorTrashHiddenCacheWorkspaceWavAndLinks() throws Exception {
        flac("keep/song.flac"); flac("incoming/song.flac"); flac("retired/song.flac"); flac(".hidden/song.flac"); flac("cover_cache/song.flac"); flac(".recovery-work/song.flac");
        config.setCoverArtCacheDirectory(dir.resolve("cover_cache").toString());
        Files.writeString(dir.resolve("skip.wav"),"wav"); Files.createSymbolicLink(dir.resolve("link.flac"),dir.resolve("keep/song.flac"));
        try(var lyrics=new StubLyrics(config);var service=new LyricsBackfillService(config,lyrics,writer)) {
            assertEquals(List.of(dir.resolve("keep/song.flac")),service.scan(dir));
        }
    }
    @Test void changedIdentityNewLyricsAndSidecarRefuseReplacement() throws Exception {
        Path file=flac("song.flac"); var identity=LyricsOnlyWriter.identity(file);
        Files.setLastModifiedTime(file,FileTime.fromMillis(identity.mtime().toMillis()+1));
        assertFalse(LyricsOnlyWriter.write(dir,file,identity,"words",false));
        identity=LyricsOnlyWriter.identity(file); Files.writeString(dir.resolve("song.txt"),"new sidecar");
        assertFalse(LyricsOnlyWriter.write(dir,file,identity,"words",false)); Files.delete(dir.resolve("song.txt"));
        var audio=AudioFileIO.read(file.toFile()); audio.getTag().setField(FieldKey.LYRICS,"new embedded"); audio.commit();
        assertFalse(LyricsOnlyWriter.write(dir,file,LyricsOnlyWriter.identity(file),"words",false));
        assertEquals("new embedded",AudioFileIO.read(file.toFile()).getTag().getFirst(FieldKey.LYRICS));
    }
    @Test void flacWithId3AndWavNeverWritten() throws Exception {
        Path file=flac("song.flac"); byte[] body=Files.readAllBytes(file); byte[] id3=new byte[10+body.length];
        System.arraycopy(new byte[]{'I','D','3',4,0,0,0,0,0,0},0,id3,0,10); System.arraycopy(body,0,id3,10,body.length); Files.write(file,id3);
        assertFalse(LyricsOnlyWriter.write(dir,file,LyricsOnlyWriter.identity(file),"words",false)); assertArrayEquals(id3,Files.readAllBytes(file));
        Path wav=dir.resolve("file.wav"); Files.writeString(wav,"untouched");
        assertFalse(LyricsOnlyWriter.write(dir,wav,LyricsOnlyWriter.identity(wav),"words",false)); assertEquals("untouched",Files.readString(wav));
    }
    @Test void guardIsReentrantAndInterruptibleAndExcludesConcurrentMutation() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(var outer=LibraryWriteGuard.acquire();var inner=LibraryWriteGuard.acquire()) {
            var entered=new CountDownLatch(1); var finished=new CountDownLatch(1);
            var future=executor.submit(()->{ entered.countDown(); try(var scope=LibraryWriteGuard.acquireInterruptibly()) { finished.countDown(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } });
            assertTrue(entered.await(1,TimeUnit.SECONDS)); assertFalse(finished.await(100,TimeUnit.MILLISECONDS)); future.cancel(true);
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(1,TimeUnit.SECONDS)); }
    }
    @Test void v2CacheKeysNeverHitV3() throws Exception {
        MusicMetadata md=new MusicMetadata();md.setTitle("title");md.setArtist("artist");md.setLocalPreciseDuration(120.25);
        Path file=dir.resolve("cache.json");String key=LyricsNegativeCache.key(md);
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(file.toFile(),Map.of(key.replace("lyrics-v3:","lyrics-v2:"),System.currentTimeMillis()+86400000));
        assertEquals("lyrics-v3",LyricsNegativeCache.KEY_VERSION);
        assertFalse(new LyricsNegativeCache(file,Clock.systemUTC()).contains(key));
    }
    @Test void cachePersistsExpiresAndSeparatesFractionAndAlbumButNotFaults() throws Exception {
        MusicMetadata md=new MusicMetadata(); md.setTitle("title"); md.setArtist("artist"); md.setAlbum("a"); md.setLocalPreciseDuration(120.001);
        Path cacheFile=dir.resolve("cache.json"); Clock now=Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),ZoneOffset.UTC);
        var cache=new LyricsNegativeCache(cacheFile,now); String key=LyricsNegativeCache.key(md);
        cache.put(key,LyricsService.Status.DEFERRED); cache.put(key,LyricsService.Status.CANCELLED); assertFalse(Files.exists(cacheFile));
        cache.put(key,LyricsService.Status.NOT_FOUND); assertTrue(new LyricsNegativeCache(cacheFile,now).contains(key));
        md.setLocalPreciseDuration(120.002); assertFalse(cache.contains(LyricsNegativeCache.key(md))); md.setLocalPreciseDuration(120.001); md.setAlbum("b"); assertFalse(cache.contains(LyricsNegativeCache.key(md)));
        assertFalse(new LyricsNegativeCache(cacheFile,Clock.offset(now,Duration.ofDays(8))).contains(key));
        cache.put("ambiguous",LyricsService.Status.UNSAFE); cache.put("instrumental",LyricsService.Status.INSTRUMENTAL);
        var later=new LyricsNegativeCache(cacheFile,Clock.offset(now,Duration.ofDays(2))); assertFalse(later.contains("ambiguous")); assertFalse(later.contains("instrumental")); assertTrue(later.contains(key));
    }
}
