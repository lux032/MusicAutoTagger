package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TagWriterServiceExtraTrackTest {
    @TempDir
    Path dir;

    private File wav(String name) throws Exception {
        int dataLen = 8000;
        ByteBuffer b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + dataLen).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
            .putInt(8000).putInt(8000).putShort((short) 1).putShort((short) 8);
        b.put("data".getBytes()).putInt(dataLen);
        Path p = dir.resolve("src").resolve(name);
        Files.createDirectories(p.getParent());
        Files.write(p, b.array());
        return p.toFile();
    }

    /** 独立配置实例：不改动全局单例，避免影响其他测试 */
    private TagWriterService writer(Path out) throws Exception {
        java.lang.reflect.Constructor<MusicConfig> constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        MusicConfig config = constructor.newInstance();
        config.setAutoRename(true);
        config.setOutputDirectory(out.toString());
        config.setExportLyricsToFile(false);
        return new TagWriterService(config);
    }

    private MusicMetadata metadata(boolean extra) {
        MusicMetadata md = new MusicMetadata();
        md.setArtist("藍井エイル");
        md.setAlbumArtist("藍井エイル");
        md.setTitle("金魚草");
        md.setAlbum("アトック");
        md.setDiscNo("1");
        md.setTrackNo("2");
        md.setExtraTrack(extra);
        return md;
    }

    @Test
    void extraTrackHasNoTrackPrefixAndNoTrackTag() throws Exception {
        File src = wav("02. 藍井エイル - 金魚草.wav");
        // 源文件带着旧曲目号标签
        AudioFile af = AudioFileIO.read(src);
        Tag tag = af.getTagOrCreateAndSetDefault();
        tag.setField(FieldKey.TRACK, "2");
        tag.setField(FieldKey.TRACK_TOTAL, "12");
        tag.setField(FieldKey.DISC_NO, "1");
        tag.setField(FieldKey.DISC_TOTAL, "1");
        tag.setField(FieldKey.COMMENT, "rip by someone");
        af.commit();

        Path out = dir.resolve("out");
        TagWriterService.TagProcessResult result = writer(out).processFileWithResult(src, metadata(true), null);

        assertTrue(result.isSuccess());
        assertEquals("藍井エイル - 金魚草.wav", result.getTargetFile().getName());
        Tag written = AudioFileIO.read(result.getTargetFile()).getTag();
        assertTrue(written.getFirst(FieldKey.TRACK) == null || written.getFirst(FieldKey.TRACK).isEmpty(),
            "附加曲目不能保留曲目号");
        for (FieldKey key : new FieldKey[]{FieldKey.TRACK_TOTAL, FieldKey.DISC_NO, FieldKey.DISC_TOTAL}) {
            String v = written.getFirst(key);
            assertTrue(v == null || v.isEmpty(), key + " 应被清除");
        }
        String comment = written.getFirst(FieldKey.COMMENT);
        assertTrue(comment.contains("rip by someone"), "原注释要保留");
        assertTrue(comment.contains(TagWriterService.EXTRA_TRACK_COMMENT));

        // 再处理一次（重新识别）：注释不重复追加
        TagWriterService.TagProcessResult again = writer(out).processFileWithResult(result.getTargetFile(), metadata(true), null);
        String comment2 = AudioFileIO.read(again.getTargetFile()).getTag().getFirst(FieldKey.COMMENT);
        assertEquals(comment2.indexOf(TagWriterService.EXTRA_TRACK_COMMENT),
            comment2.lastIndexOf(TagWriterService.EXTRA_TRACK_COMMENT));
    }

    @Test
    void normalTrackKeepsTrackPrefix() throws Exception {
        File src = wav("02. 藍井エイル - 金魚草.wav");
        Path out = dir.resolve("out2");
        TagWriterService.TagProcessResult result = writer(out).processFileWithResult(src, metadata(false), null);
        assertTrue(result.isSuccess());
        assertEquals("1.02 藍井エイル - 金魚草.wav", result.getTargetFile().getName());
    }
}
