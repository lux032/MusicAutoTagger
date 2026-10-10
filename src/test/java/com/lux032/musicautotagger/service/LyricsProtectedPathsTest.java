package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class LyricsProtectedPathsTest {
    @TempDir Path dir;
    MusicConfig config;
    @BeforeEach void setup() throws Exception {
        var ctor=MusicConfig.class.getDeclaredConstructor();ctor.setAccessible(true);config=ctor.newInstance();
        config.setOutputDirectory(dir.toString());config.setMonitorDirectory(dir.resolve("incoming").toString());
    }
    List<Consumer<String>> setters() { return List.of(config::setMonitorDirectory,config::setRecoveryTrashDirectory,
        config::setRecoveryWorkDirectory,config::setCoverArtCacheDirectory,config::setFailedDirectory,
        config::setPartialDirectory,config::setCueSplitOutputDir,config::setReviewStagingDirectory); }
    Path song(Path path) throws Exception {Files.createDirectories(path.getParent());return Files.writeString(path,"fixture");}
    List<Path> scan() throws Exception {
        try(var lyrics=new LyricsBackfillServiceTest.StubLyrics(config);var service=new LyricsBackfillService(config,lyrics,new TagWriterService(config))) {return service.scan(Path.of(config.getOutputDirectory()));}
    }
    @Test void normalNamesAndHiddenRootAreScannableButHiddenChildrenAndLinksAreNot() throws Exception {
        Path root=dir.resolve("MusicCache");Files.createDirectories(root);config.setOutputDirectory(root.toString());
        List<Path> expected=new ArrayList<>();for(String name:List.of("Cache Cache/x.flac","Trash/x.flac","recovery-trash/x.flac","cache song.flac"))expected.add(song(root.resolve(name)));
        song(root.resolve(".hidden/x.flac"));song(root.resolve(".lyrics-work-test.flac"));Files.createSymbolicLink(root.resolve("link.flac"),expected.get(0));
        assertEquals(new HashSet<>(expected),new HashSet<>(scan()));
        Path hidden=dir.resolve(".library");config.setOutputDirectory(hidden.toString());Path file=song(hidden.resolve("keep.flac"));assertEquals(List.of(file),scan());
    }
    @Test void eightEffectivePathsExcludeOnlyConfiguredSubtreesIncludingRelativeDotDotAndMissing() throws Exception {
        Path keep=song(dir.resolve("keep.flac"));int i=0;
        for(var setter:setters()) {Path protectedPath=dir.resolve("protected"+i++);song(protectedPath.resolve("song.flac"));
            setter.accept(Path.of("").toAbsolutePath().relativize(protectedPath).toString()+"/unused/../");}
        assertEquals(List.of(keep),scan());
        config.setMonitorDirectory(dir.resolve("not-yet-created").toString());assertTrue(scan().contains(keep));
    }
    @Test void defaultBlankSemanticsAndStagingTrimMatchOwningServices() throws Exception {
        config.setRecoveryTrashDirectory("  ");config.setRecoveryWorkDirectory("");config.setCoverArtCacheDirectory(null);config.setReviewStagingDirectory(" ");
        config.setFailedDirectory(null);config.setPartialDirectory("");config.setCueSplitOutputDir(" ");
        var snapshot=LyricsProtectedPaths.capture(config);Map<String,Path> paths=new HashMap<>();for(var entry:snapshot.entries)paths.put(entry.field(),entry.path());
        assertEquals(Path.of("data/recovery-trash").toAbsolutePath(),paths.get("recoveryTrashDirectory"));
        assertEquals(Path.of("data/review-staging").toAbsolutePath(),paths.get("reviewStagingDirectory"));
        assertEquals(dir.resolve(".recovery-work"),paths.get("recoveryWorkDirectory"));assertEquals(dir.resolve(".cover_cache"),paths.get("coverArtCacheDirectory"));
        assertFalse(paths.containsKey("failedDirectory"));assertFalse(paths.containsKey("partialDirectory"));assertFalse(paths.containsKey("cueSplitOutputDir"));
        config.setReviewStagingDirectory(" "+dir.resolve("stage")+" ");assertTrue(LyricsProtectedPaths.capture(config).entries.stream().anyMatch(e->e.field().equals("reviewStagingDirectory")&&e.path().equals(dir.resolve("stage"))));
    }
    @Test void eachProtectedRootConflictFailsWithFieldAndZeroLookup() throws Exception {
        List<String> fields=List.of("monitorDirectory","recoveryTrashDirectory","recoveryWorkDirectory","coverArtCacheDirectory","failedDirectory","partialDirectory","cueSplitOutputDir","reviewStagingDirectory");
        for(int i=0;i<setters().size();i++) {
            var setter=setters().get(i);String previous=dir.resolve("unrelated"+i).toString();
            for(Path protectedPath:List.of(dir,dir.getParent())) {
                setter.accept(protectedPath.toString());
                try(var lyrics=new LyricsBackfillServiceTest.StubLyrics(config);var service=new LyricsBackfillService(config,lyrics,new TagWriterService(config))) {
                    service.start(true);long end=System.currentTimeMillis()+2000;while(service.getStatus().get("running").equals(true)&&System.currentTimeMillis()<end)Thread.sleep(10);
                    assertEquals("failed: lyrics.backfill.root.conflict:"+fields.get(i),service.getStatus().get("message"));assertEquals(0,lyrics.calls);
                }
            }
            setter.accept(previous);
        }
    }
    @Test void externalSymlinkAliasProtectedAndSnapshotDoesNotFollowLaterConfigChanges() throws Exception {
        Path protectedPath=dir.resolve("protected");song(protectedPath.resolve("x.flac"));Path keep=song(dir.resolve("keep.flac"));
        Path alias=dir.resolveSibling(dir.getFileName()+"-alias");Files.createSymbolicLink(alias,protectedPath);
        try {
            config.setMonitorDirectory(alias.toString());assertEquals(List.of(keep),scan());
            var snapshot=LyricsProtectedPaths.capture(config);config.setMonitorDirectory(dir.resolve("elsewhere").toString());
            assertTrue(snapshot.excluded(protectedPath,Files.readAttributes(protectedPath,java.nio.file.attribute.BasicFileAttributes.class)));
            config.setMonitorDirectory(alias.toString());config.setOutputDirectory(protectedPath.toString());
            assertThrows(java.io.IOException.class,()->LyricsProtectedPaths.capture(config).validateRoot());
        } finally {Files.delete(alias);}
    }
    @Test void taskStartSnapshotSurvivesConfigChangeDuringLookup() throws Exception {
        // Keep both files eligible; mutate only protection while first lookup is blocked.
        var fixtures=new LyricsBackfillServiceTest();fixtures.dir=dir;fixtures.config=config;fixtures.writer=new TagWriterService(config);
        fixtures.flac("one.flac");fixtures.flac("two.flac");
        try(var lyrics=new LyricsBackfillServiceTest.StubLyrics(config);var service=new LyricsBackfillService(config,lyrics,new TagWriterService(config))) {
            lyrics.entered=new java.util.concurrent.CountDownLatch(1);lyrics.release=new java.util.concurrent.CountDownLatch(1);
            assertTrue(service.start(true));assertTrue(lyrics.entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            config.setMonitorDirectory(dir.toString());lyrics.release.countDown();
            long end=System.currentTimeMillis()+5000;while(service.getStatus().get("running").equals(true)&&System.currentTimeMillis()<end)Thread.sleep(10);
            assertEquals(2,lyrics.calls);assertEquals(2,service.getStatus().get("matched"));
        }
    }
    @Test void unreadableChildDoesNotPreventScanningNormalFiles() throws Exception {
        Path keep=song(dir.resolve("keep.flac"));
        Path restricted=dir.resolve("restricted");song(restricted.resolve("unreachable.flac"));
        var permissions=Files.getPosixFilePermissions(restricted);
        try {
            Files.setPosixFilePermissions(restricted,Set.of());
            // Root (or a privileged filesystem) can still traverse mode 000; do not claim coverage there.
            Assumptions.assumeFalse(Files.isReadable(restricted),"Process can read mode-000 directories");
            assertEquals(List.of(keep),scan());
        } finally { Files.setPosixFilePermissions(restricted,permissions); }
    }
    @Test void invalidProtectedFileFailsClosed() throws Exception {
        Path file=Files.writeString(dir.resolve("not-directory"),"x");config.setMonitorDirectory(file.toString());
        assertEquals("lyrics.backfill.protected.invalid:monitorDirectory",assertThrows(java.io.IOException.class,()->LyricsProtectedPaths.capture(config)).getMessage());
    }
}
