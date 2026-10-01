package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class CoverArtServiceSafetyTest {
    @Test void rejectedNetworkSourceIsNotCachedAndDirectoryFallbackWorks() throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of("target/cover-tests")), "case-");
        AtomicInteger cached = new AtomicInteger();
        CoverArtCache cache = new CoverArtCache(null, root.resolve("cache").toString(), MusicConfig.getInstance()) {
            @Override public byte[] getCachedCover(String url) { return null; }
            @Override public boolean cacheCover(String url, byte[] data) { cached.incrementAndGet(); return true; }
        };
        MusicBrainzClient client = new MusicBrainzClient(MusicConfig.getInstance()) {
            @Override public byte[] downloadCoverArt(String url) { return new byte[]{1, 2, 3}; }
        };
        CoverArtService service = new CoverArtService(cache, client);
        MusicMetadata metadata = new MusicMetadata(); metadata.setCoverArtUrl("https://example.invalid/cover");
        Path audio = Files.writeString(root.resolve("test.flac"), "not audio");
        assertNull(service.getCoverArtWithFallback(audio.toFile(), metadata, null, false));
        assertEquals(0, cached.get()); assertNull(service.getFolderCachedCover(root.toFile().getAbsolutePath()));
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", root.resolve("cover.png").toFile());
        assertNotNull(service.getCoverArtWithFallback(audio.toFile(), metadata, null, false));
        assertEquals(0, cached.get()); assertNotNull(service.getFolderCachedCover(root.toFile().getAbsolutePath()));
    }
}
