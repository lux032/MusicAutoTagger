package com.lux032.musicautotagger.util;

import com.lux032.musicautotagger.config.MusicConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileSystemUtilsAlbumRootTest {
    @TempDir
    Path monitor;

    private FileSystemUtils utils;

    @BeforeEach
    void setUp() {
        MusicConfig config = MusicConfig.getInstance();
        config.setMonitorDirectory(monitor.toString());
        config.setSupportedFormats(new String[]{"mp3", "flac", "m4a", "ogg", "wav"});
        utils = new FileSystemUtils(config);
    }

    private File touch(String relative) throws Exception {
        Path p = monitor.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.createFile(p);
        return p.toFile();
    }

    @Test
    void artistAlbumLayoutUsesAlbumFolderNotArtistFolder() throws Exception {
        File a = touch("Artist/This game/01.flac");
        touch("Artist/lead/01.flac");
        assertEquals(monitor.resolve("Artist/This game").toFile(), utils.getAlbumRootDirectory(a));
    }

    @Test
    void discSubfolderResolvesToAlbumFolder() throws Exception {
        File a = touch("Artist/Album/Disc 1/01.flac");
        File b = touch("Album2/CD2/01.flac");
        File c = touch("Album3/Disc 2 - Bonus/01.flac");
        assertEquals(monitor.resolve("Artist/Album").toFile(), utils.getAlbumRootDirectory(a));
        assertEquals(monitor.resolve("Album2").toFile(), utils.getAlbumRootDirectory(b));
        assertEquals(monitor.resolve("Album3").toFile(), utils.getAlbumRootDirectory(c));
    }

    @Test
    void discFolderDirectlyUnderMonitorDoesNotEscape() throws Exception {
        File a = touch("CD1/01.flac");
        assertEquals(monitor.resolve("CD1").toFile(), utils.getAlbumRootDirectory(a));
    }

    @Test
    void collectOnlyDescendsIntoDiscFolders() throws Exception {
        touch("Artist/loose.flac");
        touch("Artist/Album A/01.flac");
        touch("Artist/Disc 1/01.flac");
        List<File> files = new ArrayList<>();
        utils.collectAudioFilesForMarking(monitor.resolve("Artist").toFile(), files);
        assertEquals(2, files.size());
    }

    @Test
    void discFolderNamePattern() {
        assertTrue(FileSystemUtils.isDiscFolderName("Disc 1"));
        assertTrue(FileSystemUtils.isDiscFolderName("CD01"));
        assertTrue(FileSystemUtils.isDiscFolderName("disk-2"));
        assertTrue(FileSystemUtils.isDiscFolderName("DISC.3"));
        assertTrue(FileSystemUtils.isDiscFolderName("CD 2 [Live]"));
        assertFalse(FileSystemUtils.isDiscFolderName("Discovery"));
        assertFalse(FileSystemUtils.isDiscFolderName("CD Collection"));
        assertFalse(FileSystemUtils.isDiscFolderName("This game"));
    }
}
