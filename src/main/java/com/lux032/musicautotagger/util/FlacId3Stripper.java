package com.lux032.musicautotagger.util;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.Locale;

/**
 * 剥离 FLAC 文件头部的非法 ID3v2 标签和尾部的非法 ID3v1 标签。
 *
 * FLAC 规范要求文件 Offset 0 处为 {@code fLaC} 流标记，但部分下载源会在头部塞入 ID3v2、
 * 在末尾追加 ID3v1（128 字节 {@code TAG} 块，可能带 227 字节 {@code TAG+} 扩展块）。
 * jaudiotagger 会跳过并原样保留这些数据：头部 ID3v2 导致浏览器解码失败（MEDIA_ERR_SRC_NOT_SUPPORTED）
 * 或服务端读到与 Vorbis Comment 冲突的“幽灵标签”；尾部 ID3v1 会被解码器当作非法音频帧。
 *
 * 安全约束：头部只有在 ID3v2 块结束处严格为 {@code fLaC} 时才会剥离；
 * 尾部只有在文件为合法 FLAC 起始、元数据块链完整且 ID3v1 字段结构校验通过时才会剥离；
 * 通过同目录临时文件 + 原子替换完成，任何异常都保持原文件不变，本类方法不抛异常。
 */
@Slf4j
public final class FlacId3Stripper {

    private static final byte[] FLAC_MARKER = {'f', 'L', 'a', 'C'};
    private static final int ID3_HEADER_LENGTH = 10;
    private static final int ID3_FOOTER_LENGTH = 10;
    private static final int ID3_FLAG_FOOTER = 0x10;
    /** 防御性上限：连续堆叠的 ID3 块数量。 */
    private static final int MAX_STACKED_TAGS = 8;
    private static final int ID3V1_LENGTH = 128;
    private static final int ID3V1_EXT_LENGTH = 227;

    public enum Result {
        /** 非 FLAC 扩展名，未检测。 */
        NOT_FLAC,
        /** 已以 fLaC 开头，无需处理。 */
        CLEAN,
        /** 已成功剥离头部 ID3v2 和/或尾部 ID3v1。 */
        STRIPPED,
        /** 头部不是 fLaC 也不是可安全剥离的 ID3v2，保持原样。 */
        SKIPPED_UNSAFE,
        /** 剥离过程发生 IO 异常，原文件保持不变。 */
        FAILED
    }

    private FlacId3Stripper() {
    }

    public static Result stripId3FromFlac(File file) {
        return file == null ? Result.NOT_FLAC : stripId3FromFlac(file.toPath());
    }

    public static Result stripId3FromFlac(Path file) {
        if (file == null || !isFlac(file)) {
            return Result.NOT_FLAC;
        }
        Path target;
        try {
            // 解析符号链接，避免原子替换把链接本身换成普通文件
            target = file.toRealPath();
        } catch (IOException e) {
            log.warn("FLAC ID3 检测失败，无法解析路径: {} ({})", file, e.getMessage());
            return Result.FAILED;
        }
        if (!Files.isRegularFile(target)) {
            return Result.NOT_FLAC;
        }

        long audioOffset;
        long trailerLength;
        long fileSize;
        try (FileChannel in = FileChannel.open(target, StandardOpenOption.READ)) {
            fileSize = in.size();
            audioOffset = locateFlacMarker(in, fileSize, target);
            if (audioOffset < 0) {
                return Result.SKIPPED_UNSAFE;
            }
            trailerLength = detectId3v1Trailer(in, audioOffset, fileSize, target);
        } catch (IOException e) {
            log.warn("FLAC ID3 检测失败，保持原文件: {} ({})", target, e.getMessage());
            return Result.FAILED;
        }
        if (audioOffset == 0 && trailerLength == 0) {
            return Result.CLEAN;
        }

        return rewriteRange(target, audioOffset, fileSize - trailerLength, fileSize);
    }

    /**
     * 检测文件末尾的 ID3v1（以及紧邻其前的 ID3v1 扩展块 {@code TAG+}）。
     *
     * @param flacStart {@code fLaC} 所在偏移
     * @return 需要从末尾截掉的字节数；0 表示没有或无法安全确认
     */
    private static long detectId3v1Trailer(FileChannel in, long flacStart, long fileSize, Path file)
            throws IOException {
        if (fileSize - flacStart < FLAC_MARKER.length + ID3V1_LENGTH) {
            return 0;
        }
        byte[] tag = readAt(in, fileSize - ID3V1_LENGTH, ID3V1_LENGTH, fileSize);
        if (tag.length != ID3V1_LENGTH || tag[0] != 'T' || tag[1] != 'A' || tag[2] != 'G') {
            return 0;
        }
        if (!looksLikeId3v1(tag)) {
            log.warn("FLAC 文件末尾出现 TAG 但不符合 ID3v1 结构，保留尾部: {}", file);
            return 0;
        }
        long trailer = ID3V1_LENGTH;
        long extStart = fileSize - ID3V1_LENGTH - ID3V1_EXT_LENGTH;
        if (extStart >= flacStart + FLAC_MARKER.length) {
            byte[] ext = readAt(in, extStart, 4, fileSize);
            if (ext.length == 4 && ext[0] == 'T' && ext[1] == 'A' && ext[2] == 'G' && ext[3] == '+') {
                trailer += ID3V1_EXT_LENGTH;
            }
        }
        long audioFramesStart = endOfMetadataBlocks(in, flacStart, fileSize);
        if (audioFramesStart < 0 || audioFramesStart > fileSize - trailer) {
            log.warn("FLAC 元数据块链异常，不剥离尾部 ID3v1: {}", file);
            return 0;
        }
        log.debug("检测到 FLAC 尾部 ID3v1 ({} 字节): {}", trailer, file);
        return trailer;
    }

    /**
     * ID3v1 结构校验：标题/艺术家/专辑/注释为定长文本，出现 0 之后只能继续是 0
     * （ID3v1.1 注释第 29 字节为音轨号例外），且不含其他控制字符；年份为数字/空格/0。
     */
    static boolean looksLikeId3v1(byte[] tag) {
        if (!isId3v1Text(tag, 3, 30) || !isId3v1Text(tag, 33, 30) || !isId3v1Text(tag, 63, 30)) {
            return false;
        }
        for (int i = 93; i < 97; i++) {
            int b = tag[i] & 0xFF;
            if (b != 0 && b != ' ' && (b < '0' || b > '9')) {
                return false;
            }
        }
        // ID3v1.1：注释第 28 字节为 0 时第 29 字节是音轨号
        int commentLength = (tag[97 + 28] == 0 && tag[97 + 29] != 0) ? 28 : 30;
        return isId3v1Text(tag, 97, commentLength);
    }

    private static boolean isId3v1Text(byte[] tag, int offset, int length) {
        boolean terminated = false;
        for (int i = offset; i < offset + length; i++) {
            int b = tag[i] & 0xFF;
            if (terminated) {
                if (b != 0) {
                    return false;
                }
            } else if (b == 0) {
                terminated = true;
            } else if (b < 0x20 || b == 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** 沿 FLAC 元数据块链走到最后一个块之后，返回音频帧起始偏移；结构异常返回 -1。 */
    private static long endOfMetadataBlocks(FileChannel in, long flacStart, long fileSize) throws IOException {
        long p = flacStart + FLAC_MARKER.length;
        for (int i = 0; i < 1024; i++) {
            byte[] h = readAt(in, p, 4, fileSize);
            if (h.length < 4) {
                return -1;
            }
            long length = ((h[1] & 0xFFL) << 16) | ((h[2] & 0xFFL) << 8) | (h[3] & 0xFFL);
            p += 4 + length;
            if (p > fileSize) {
                return -1;
            }
            if ((h[0] & 0x80) != 0) {
                return p;
            }
        }
        return -1;
    }

    /**
     * @return 0 表示已是干净 FLAC；正数为 fLaC 的偏移量；-1 表示无法安全识别
     */
    private static long locateFlacMarker(FileChannel in, long fileSize, Path file) throws IOException {
        long offset = 0;
        for (int i = 0; i <= MAX_STACKED_TAGS; i++) {
            byte[] head = readAt(in, offset, ID3_HEADER_LENGTH, fileSize);
            if (head.length >= 4 && startsWith(head, FLAC_MARKER)) {
                return offset;
            }
            if (head.length < 3 || head[0] != 'I' || head[1] != 'D' || head[2] != '3') {
                if (offset == 0) {
                    log.warn("FLAC 文件头既不是 fLaC 也不是 ID3，跳过处理: {}", file);
                } else {
                    log.warn("FLAC 文件 ID3v2 块结束处(偏移 {})未找到 fLaC 标记，疑似损坏，保持原样: {}",
                        offset, file);
                }
                return -1;
            }
            if (head.length < ID3_HEADER_LENGTH) {
                log.warn("FLAC 文件 ID3v2 头部不完整，保持原样: {}", file);
                return -1;
            }
            int major = head[3] & 0xFF;
            int revision = head[4] & 0xFF;
            int flags = head[5] & 0xFF;
            if (major == 0xFF || revision == 0xFF) {
                log.warn("FLAC 文件 ID3v2 版本号非法 (v2.{}.{})，保持原样: {}", major, revision, file);
                return -1;
            }
            int b0 = head[6] & 0xFF, b1 = head[7] & 0xFF, b2 = head[8] & 0xFF, b3 = head[9] & 0xFF;
            if (((b0 | b1 | b2 | b3) & 0x80) != 0) {
                log.warn("FLAC 文件 ID3v2 长度字段不是合法 SyncSafe 整数，保持原样: {}", file);
                return -1;
            }
            long payload = ((long) b0 << 21) | ((long) b1 << 14) | ((long) b2 << 7) | b3;
            long total = ID3_HEADER_LENGTH + payload + ((flags & ID3_FLAG_FOOTER) != 0 ? ID3_FOOTER_LENGTH : 0);
            offset += total;
            log.debug("检测到 FLAC 头部 ID3v2.{}.{} (flags=0x{}, 长度 {} 字节): {}",
                major, revision, Integer.toHexString(flags), total, file);
            if (offset + FLAC_MARKER.length > fileSize) {
                log.warn("FLAC 文件 ID3v2 声明长度 {} 超出文件大小 {}，保持原样: {}", offset, fileSize, file);
                return -1;
            }
        }
        log.warn("FLAC 文件头部堆叠的 ID3v2 块过多，保持原样: {}", file);
        return -1;
    }

    private static Result rewriteRange(Path target, long audioOffset, long audioEnd, long fileSize) {
        Path dir = target.getParent();
        Path tmp = null;
        try {
            tmp = Files.createTempFile(dir, "." + target.getFileName().toString(), ".id3strip.tmp");
            long expected = audioEnd - audioOffset;
            try (FileChannel in = FileChannel.open(target, StandardOpenOption.READ);
                 FileChannel out = FileChannel.open(tmp, StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
                if (in.size() != fileSize) {
                    throw new IOException("文件在检测期间被修改");
                }
                long position = audioOffset;
                while (position < audioEnd) {
                    long n = in.transferTo(position, audioEnd - position, out);
                    if (n <= 0) {
                        throw new IOException("复制音频数据中断于偏移 " + position);
                    }
                    position += n;
                }
                out.force(true);
                if (out.size() != expected) {
                    throw new IOException("临时文件大小校验失败: " + out.size() + " != " + expected);
                }
            }
            byte[] marker = readHead(tmp, FLAC_MARKER.length);
            if (!startsWith(marker, FLAC_MARKER)) {
                throw new IOException("临时文件头部校验失败");
            }
            copyAttributes(target, tmp);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
            log.info("已剥离 FLAC 非法 ID3 标签 (头部 {} 字节, 尾部 {} 字节): {}",
                audioOffset, fileSize - audioEnd, target.getFileName());
            return Result.STRIPPED;
        } catch (IOException | RuntimeException e) {
            log.warn("剥离 FLAC ID3 标签失败，保留原文件: {} ({})", target, e.toString());
            return Result.FAILED;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    log.debug("清理临时文件失败: {}", tmp);
                }
            }
        }
    }

    private static void copyAttributes(Path source, Path destination) {
        PosixFileAttributeView srcView = Files.getFileAttributeView(source, PosixFileAttributeView.class);
        PosixFileAttributeView dstView = Files.getFileAttributeView(destination, PosixFileAttributeView.class);
        if (srcView == null || dstView == null) {
            return;
        }
        try {
            PosixFileAttributes attrs = srcView.readAttributes();
            dstView.setPermissions(attrs.permissions());
            try {
                dstView.setGroup(attrs.group());
                dstView.setOwner(attrs.owner());
            } catch (IOException | SecurityException | UnsupportedOperationException e) {
                log.debug("无法保留文件属主: {}", source);
            }
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            log.debug("无法保留文件权限: {}", source);
        }
    }

    private static boolean isFlac(Path file) {
        Path name = file.getFileName();
        return name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(".flac");
    }

    private static byte[] readHead(Path file, int length) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            return readAt(ch, 0, length, ch.size());
        }
    }

    private static byte[] readAt(FileChannel ch, long position, int length, long fileSize) throws IOException {
        int n = (int) Math.max(0, Math.min(length, fileSize - position));
        ByteBuffer buf = ByteBuffer.allocate(n);
        while (buf.hasRemaining()) {
            int r = ch.read(buf, position + buf.position());
            if (r < 0) {
                break;
            }
        }
        byte[] out = new byte[buf.position()];
        buf.flip();
        buf.get(out);
        return out;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
