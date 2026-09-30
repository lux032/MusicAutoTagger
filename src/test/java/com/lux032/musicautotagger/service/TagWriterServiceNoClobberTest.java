package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TagWriterServiceNoClobberTest {
    @TempDir
    Path dir;

    private final TagWriterService writer = new TagWriterService(MusicConfig.getInstance());

    /** 生成指定秒数的 8kHz / 8bit / 单声道 PCM WAV，fill 用来区分内容 */
    private File wav(String name, int seconds, byte fill) throws Exception {
        int sampleRate = 8000;
        int dataLen = sampleRate * seconds;
        ByteBuffer b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + dataLen).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
            .putInt(sampleRate).putInt(sampleRate).putShort((short) 1).putShort((short) 8);
        b.put("data".getBytes()).putInt(dataLen);
        for (int i = 0; i < dataLen; i++) b.put(fill);
        Path p = dir.resolve(name);
        Files.write(p, b.array());
        return p.toFile();
    }

    @Test
    void writesDirectlyWhenTargetMissing() throws Exception {
        File src = wav("src.wav", 3, (byte) 1);
        File target = dir.resolve("out/01 a.wav").toFile();
        target.getParentFile().mkdirs();
        File actual = writer.copyWithoutClobbering(src, target);
        assertEquals(target, actual);
        assertTrue(target.isFile());
    }

    @Test
    void overwritesWhenExistingIsSameAudio() throws Exception {
        File target = wav("01 a.wav", 3, (byte) 1);
        File src = wav("src.wav", 3, (byte) 2);
        File actual = writer.copyWithoutClobbering(src, target);
        assertEquals(target, actual);
        assertEquals(2, Files.readAllBytes(target.toPath())[100]);
        assertFalse(TagWriterService.withConflictSuffix(target, 2).exists());
    }

    @Test
    void keepsExistingAndWritesSuffixWhenDifferentAudio() throws Exception {
        File target = wav("01 a.wav", 3, (byte) 1);
        File src = wav("src.wav", 6, (byte) 2);
        File actual = writer.copyWithoutClobbering(src, target);

        assertEquals(dir.resolve("01 a (2).wav").toFile(), actual);
        assertEquals(1, Files.readAllBytes(target.toPath())[100], "原文件不能被覆盖");
        assertEquals(2, Files.readAllBytes(actual.toPath())[100]);

        // 第三首不同的歌继续顺延到 (3)
        File src3 = wav("src3.wav", 9, (byte) 3);
        assertEquals(dir.resolve("01 a (3).wav").toFile(), writer.copyWithoutClobbering(src3, target));
        // 与 (2) 同一首歌的再次写入：覆盖 (2)，不再新增
        File src2again = wav("src2again.wav", 6, (byte) 4);
        assertEquals(dir.resolve("01 a (2).wav").toFile(), writer.copyWithoutClobbering(src2again, target));
    }
}
