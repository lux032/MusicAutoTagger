package com.lux032.musicautotagger.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FlacId3StripperTest {

    @TempDir
    Path dir;

    private static byte[] flacBody() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("fLaC".getBytes(StandardCharsets.US_ASCII));
        // 最后一个元数据块标志 + STREAMINFO 类型 + 长度 34
        out.writeBytes(new byte[] {(byte) 0x80, 0x00, 0x00, 0x22});
        for (int i = 0; i < 5000; i++) {
            out.write(i * 31 + 7);
        }
        return out.toByteArray();
    }

    private static byte[] id3(int payloadLength, int flags) {
        byte[] tag = new byte[10 + payloadLength + ((flags & 0x10) != 0 ? 10 : 0)];
        tag[0] = 'I';
        tag[1] = 'D';
        tag[2] = '3';
        tag[3] = 4;
        tag[4] = 0;
        tag[5] = (byte) flags;
        tag[6] = (byte) ((payloadLength >> 21) & 0x7F);
        tag[7] = (byte) ((payloadLength >> 14) & 0x7F);
        tag[8] = (byte) ((payloadLength >> 7) & 0x7F);
        tag[9] = (byte) (payloadLength & 0x7F);
        Arrays.fill(tag, 10, tag.length, (byte) 0x55);
        return tag;
    }

    /** 按 Aimer 样本构造：TAG + 标题/艺术家/专辑 30 字节 0 填充 + 年份 + 注释 + 流派。 */
    private static byte[] id3v1(String title, String artist, String album, int track) {
        byte[] tag = new byte[128];
        tag[0] = 'T';
        tag[1] = 'A';
        tag[2] = 'G';
        put(tag, 3, title);
        put(tag, 33, artist);
        put(tag, 63, album);
        put(tag, 93, "2025");
        put(tag, 97, "comment");
        tag[97 + 29] = (byte) track;
        tag[127] = (byte) 0xFF;
        return tag;
    }

    private static void put(byte[] dst, int offset, String s) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(b, 0, dst, offset, b.length);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private Path write(String name, byte[] data) throws Exception {
        Path file = dir.resolve(name);
        Files.write(file, data);
        return file;
    }

    private void assertNoTempLeft() throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(0, files.filter(p -> p.getFileName().toString().endsWith(".tmp")).count());
        }
    }

    @Test
    void cleanFlacIsUntouched() throws Exception {
        byte[] body = flacBody();
        Path file = write("clean.flac", body);
        var mtime = Files.getLastModifiedTime(file);

        assertEquals(FlacId3Stripper.Result.CLEAN, FlacId3Stripper.stripId3FromFlac(file));
        assertArrayEquals(body, Files.readAllBytes(file));
        assertEquals(mtime, Files.getLastModifiedTime(file));
    }

    @Test
    void leadingId3IsStripped() throws Exception {
        byte[] body = flacBody();
        // 长度 300 需要多个 SyncSafe 字节参与计算
        Path file = write("dirty.FLAC", concat(id3(300, 0), body));

        assertEquals(FlacId3Stripper.Result.STRIPPED, FlacId3Stripper.stripId3FromFlac(file.toFile()));
        assertArrayEquals(body, Files.readAllBytes(file));
        assertNoTempLeft();
    }

    @Test
    void id3WithFooterIsStripped() throws Exception {
        byte[] body = flacBody();
        Path file = write("footer.flac", concat(id3(128, 0x10), body));

        assertEquals(FlacId3Stripper.Result.STRIPPED, FlacId3Stripper.stripId3FromFlac(file));
        assertArrayEquals(body, Files.readAllBytes(file));
    }

    @Test
    void stackedId3TagsAreStripped() throws Exception {
        byte[] body = flacBody();
        Path file = write("stacked.flac", concat(id3(20, 0), id3(40, 0), body));

        assertEquals(FlacId3Stripper.Result.STRIPPED, FlacId3Stripper.stripId3FromFlac(file));
        assertArrayEquals(body, Files.readAllBytes(file));
    }

    @Test
    void id3WithoutFlacMarkerIsNotTruncated() throws Exception {
        byte[] data = concat(id3(64, 0), "RIFFgarbage-data".getBytes(StandardCharsets.US_ASCII));
        Path file = write("broken.flac", data);

        assertEquals(FlacId3Stripper.Result.SKIPPED_UNSAFE, FlacId3Stripper.stripId3FromFlac(file));
        assertArrayEquals(data, Files.readAllBytes(file));
        assertNoTempLeft();
    }

    @Test
    void wrongDeclaredLengthIsNotTruncated() throws Exception {
        // 声明长度比实际少 1 字节 → 偏移处不是 fLaC
        byte[] tag = id3(50, 0);
        tag[9] = 49;
        byte[] data = concat(tag, flacBody());
        Path file = write("offbyone.flac", data);

        assertEquals(FlacId3Stripper.Result.SKIPPED_UNSAFE, FlacId3Stripper.stripId3FromFlac(file));
        assertArrayEquals(data, Files.readAllBytes(file));
    }

    @Test
    void oversizedDeclaredLengthIsNotTruncated() throws Exception {
        byte[] tag = id3(10, 0);
        tag[6] = 0x7F; // 声明远超文件大小
        byte[] data = concat(tag, flacBody());
        Path file = write("oversized.flac", data);

        assertEquals(FlacId3Stripper.Result.SKIPPED_UNSAFE, FlacId3Stripper.stripId3FromFlac(file));
        assertArrayEquals(data, Files.readAllBytes(file));
    }

    @Test
    void nonSyncSafeLengthIsRejected() throws Exception {
        byte[] tag = id3(10, 0);
        tag[9] = (byte) 0x8A;
        byte[] data = concat(tag, flacBody());
        Path file = write("badsize.flac", data);

        assertEquals(FlacId3Stripper.Result.SKIPPED_UNSAFE, FlacId3Stripper.stripId3FromFlac(file));
        assertArrayEquals(data, Files.readAllBytes(file));
    }

    @Test
    void truncatedId3HeaderIsLeftAlone() throws Exception {
        byte[] data = {'I', 'D', '3', 4, 0};
        Path file = write("tiny.flac", data);

        assertEquals(FlacId3Stripper.Result.SKIPPED_UNSAFE, FlacId3Stripper.stripId3FromFlac(file));
        assertArrayEquals(data, Files.readAllBytes(file));
    }

    @Test
    void trailingId3v1IsStripped() throws Exception {
        byte[] body = flacBody();
        Path file = write("trailer.flac", concat(body, id3v1("I beg you", "Aimer", "Penny Rain", 0)));

        assertEquals(FlacId3Stripper.Result.STRIPPED, FlacId3Stripper.stripId3FromFlac(file));
        assertArrayEquals(body, Files.readAllBytes(file));
        assertNoTempLeft();
    }

    @Test
    void leadingAndTrailingTagsAreStrippedTogether() throws Exception {
        byte[] body = flacBody();
        Path file = write("both.flac",
            concat(id3(5034, 0), body, id3v1("I beg you", "Aimer", "Penny Rain", 2)));

        assertEquals(FlacId3Stripper.Result.STRIPPED, FlacId3Stripper.stripId3FromFlac(file));
        assertArrayEquals(body, Files.readAllBytes(file));
    }

    @Test
    void extendedTagPlusBlockIsStripped() throws Exception {
        byte[] body = flacBody();
        byte[] ext = new byte[227];
        put(ext, 0, "TAG+Extended title");
        Path file = write("ext.flac", concat(body, ext, id3v1("t", "a", "b", 1)));

        assertEquals(FlacId3Stripper.Result.STRIPPED, FlacId3Stripper.stripId3FromFlac(file));
        assertArrayEquals(body, Files.readAllBytes(file));
    }

    @Test
    void tagBytesThatAreNotId3v1AreKept() throws Exception {
        // 末尾恰好以 TAG 开头但字段含控制字符/0 后又有数据，应视为音频数据
        byte[] fake = new byte[128];
        for (int i = 0; i < fake.length; i++) {
            fake[i] = (byte) (i * 37 + 1);
        }
        fake[0] = 'T';
        fake[1] = 'A';
        fake[2] = 'G';
        byte[] data = concat(flacBody(), fake);
        Path file = write("fake-tag.flac", data);

        assertEquals(FlacId3Stripper.Result.CLEAN, FlacId3Stripper.stripId3FromFlac(file));
        assertArrayEquals(data, Files.readAllBytes(file));
    }

    @Test
    void trailerIsKeptWhenMetadataChainIsBroken() throws Exception {
        byte[] body = flacBody();
        body[4] = 0x00; // 去掉 last-block 标志
        body[5] = 0x7F; // 块长度远超文件
        byte[] data = concat(body, id3v1("t", "a", "b", 0));
        Path file = write("broken-chain.flac", data);

        assertEquals(FlacId3Stripper.Result.CLEAN, FlacId3Stripper.stripId3FromFlac(file));
        assertArrayEquals(data, Files.readAllBytes(file));
    }

    @Test
    void nonFlacExtensionIsIgnored() throws Exception {
        byte[] data = concat(id3(20, 0), flacBody());
        Path file = write("song.mp3", data);

        assertEquals(FlacId3Stripper.Result.NOT_FLAC, FlacId3Stripper.stripId3FromFlac(file));
        assertArrayEquals(data, Files.readAllBytes(file));
    }

    @Test
    void missingFileFailsGracefully() {
        assertEquals(FlacId3Stripper.Result.FAILED,
            FlacId3Stripper.stripId3FromFlac(dir.resolve("missing.flac")));
    }
}
