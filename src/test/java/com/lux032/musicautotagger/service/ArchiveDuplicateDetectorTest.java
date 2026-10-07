package com.lux032.musicautotagger.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveDuplicateDetectorTest {

    @TempDir Path dir;

    private final ArchiveDuplicateDetector detector = new ArchiveDuplicateDetector(
        f -> f.getName().endsWith(".flac") || f.getName().endsWith(".mp3"));

    /** 最小 FLAC：fLaC + STREAMINFO（MD5 位于数据第 18~33 字节），后面随便跟点数据。 */
    static byte[] flac(int md5Seed) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("fLaC".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[] {(byte) 0x80, 0x00, 0x00, 0x22});
        byte[] streamInfo = new byte[34];
        for (int i = 18; i < 34; i++) streamInfo[i] = (byte) (md5Seed == 0 ? 0 : md5Seed * 7 + i);
        out.writeBytes(streamInfo);
        out.writeBytes(new byte[64]);
        return out.toByteArray();
    }

    static Path write(Path path, byte[] data) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.write(path, data);
    }

    @Test
    void readsMd5AndIgnoresUnsetOrNonFlac() throws Exception {
        Path a = write(dir.resolve("a.flac"), flac(1));
        Path zero = write(dir.resolve("zero.flac"), flac(0));
        Path mp3 = write(dir.resolve("a.mp3"), flac(1));
        assertTrue(ArchiveDuplicateDetector.flacAudioMd5(a).isPresent());
        assertEquals(Optional.empty(), ArchiveDuplicateDetector.flacAudioMd5(zero));
        assertEquals(Optional.empty(), ArchiveDuplicateDetector.flacAudioMd5(mp3));
    }

    @Test
    void readsMd5BehindId3Header() throws Exception {
        byte[] body = flac(3);
        int payload = 20;
        byte[] id3 = new byte[10 + payload];
        id3[0] = 'I'; id3[1] = 'D'; id3[2] = '3'; id3[3] = 4; id3[9] = (byte) payload;
        byte[] data = new byte[id3.length + body.length];
        System.arraycopy(id3, 0, data, 0, id3.length);
        System.arraycopy(body, 0, data, id3.length, body.length);
        Path withId3 = write(dir.resolve("id3.flac"), data);
        Path plain = write(dir.resolve("plain.flac"), body);
        assertEquals(ArchiveDuplicateDetector.flacAudioMd5(plain), ArchiveDuplicateDetector.flacAudioMd5(withId3));
    }

    @Test
    void groupsMatchesByAlbumUnitAndFlagsReplaceability() throws Exception {
        Path output = dir.resolve("output");
        // 单曲目录：唯一一首与输入相同 → 可替换
        write(output.resolve("Artist/Single/01.flac"), flac(1));
        // 专辑目录：包含相同的一首 + 另一首 → 不可替换
        write(output.resolve("Artist/Album/01.flac"), flac(1));
        write(output.resolve("Artist/Album/02.flac"), flac(2));
        // 隐藏暂存目录不参与
        write(output.resolve("Artist/.Single.commit-x/01.flac"), flac(1));
        // 毫不相关
        write(output.resolve("Artist/Other/01.flac"), flac(9));

        Path incoming = write(dir.resolve("work/Artist/New/01.flac"), flac(1));
        List<ArchiveDuplicateDetector.DuplicateGroup> groups = detector.find(
            List.of(incoming), output, List.of(output.resolve("Artist")), List.of());

        assertEquals(2, groups.size());
        var single = groups.stream().filter(g -> g.path().endsWith("Single")).findFirst().orElseThrow();
        var album = groups.stream().filter(g -> g.path().endsWith("Album")).findFirst().orElseThrow();
        assertTrue(single.replaceable());
        assertFalse(album.replaceable());
        assertEquals(1, album.matchedFiles().size());
        assertEquals(2, album.totalAudioFiles());
        assertEquals("Artist" + File.separator + "Single", single.toView(output).get("path"));
    }

    @Test
    void excludedPathsAreSkipped() throws Exception {
        Path output = dir.resolve("output");
        write(output.resolve("Artist/Single/01.flac"), flac(1));
        Path incoming = write(dir.resolve("work/Artist/New/01.flac"), flac(1));
        assertTrue(detector.find(List.of(incoming), output, List.of(output.resolve("Artist")),
            List.of(output.resolve("Artist/Single"))).isEmpty());
    }
}
