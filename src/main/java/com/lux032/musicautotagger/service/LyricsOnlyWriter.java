package com.lux032.musicautotagger.service;

import org.jaudiotagger.audio.*;
import org.jaudiotagger.tag.*;
import org.jaudiotagger.tag.flac.FlacTag;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;

/** Copy/verify/atomic-replace transaction; never archives, renames or rewrites unrelated fields. */
@lombok.extern.slf4j.Slf4j
final class LyricsOnlyWriter {
    record Identity(Object key, long size, FileTime mtime, String digest) {}
    static Identity identity(Path file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.fileKey() == null) throw new IOException("file.identity.unavailable");
        return new Identity(attrs.fileKey(), attrs.size(), attrs.lastModifiedTime(), hash(file, 0, attrs.size()));
    }
    /** Read-only applicability check shared by preview and real jobs; never replaces the guarded rechecks. */
    static void preflight(Path root, Path file) throws IOException {
        try {
            if (!LyricsBackfillService.safePath(root, file)) throw new IOException("unsafe.file.path");
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!LyricsBackfillService.supported(file) || name.endsWith(".ogg") || name.endsWith(".opus"))
                throw new IOException("audio.range.unverified");
            if (hasFlacId3(file, name.substring(name.lastIndexOf('.')))) throw new IOException("flac.id3");
            if (LyricsBackfillService.hasSidecar(file)) throw new IOException("existing.sidecar");
            if (!Files.isReadable(file) || !Files.isWritable(file.getParent())) throw new IOException("file.permissions.unavailable");
            if (Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) == null)
                throw new IOException("file.posix.unavailable");
            Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            AudioFile audio = AudioFileIO.read(file.toFile());
            if (TagWriterService.lyricsState(audio.getTag()) != TagWriterService.LyricsState.ABSENT)
                throw new IOException("lyrics.existing.or.unknown");
            snapshot(audio, file);
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("lyrics.preflight.failed", e); }
    }
    @FunctionalInterface interface AtomicReplace { void replace(Path temp, Path target) throws IOException; }
    static boolean write(Path root, Path file, Identity expected, String lyrics, boolean export) throws IOException, InterruptedException {
        return write(root, file, expected, lyrics, export,
            (temp, target) -> Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }
    static boolean write(Path root, Path file, Identity expected, String lyrics, boolean export, AtomicReplace replace) throws IOException, InterruptedException {
        try (var guard = LibraryWriteGuard.acquireInterruptibly()) {
            if (Thread.currentThread().isInterrupted() || !LyricsBackfillService.safePath(root, file)
                || !expected.equals(identity(file)) || LyricsBackfillService.hasSidecar(file)) return false;
            String ext = file.getFileName().toString(); ext = ext.substring(ext.lastIndexOf('.')).toLowerCase(Locale.ROOT);
            if (!LyricsBackfillService.supported(file) || ext.equals(".ogg") || ext.equals(".opus") || hasFlacId3(file, ext)) return false;
            AudioFile source = AudioFileIO.read(file.toFile());
            if (TagWriterService.lyricsState(source.getTag()) != TagWriterService.LyricsState.ABSENT) return false;
            Snapshot before = snapshot(source, file);
            Path temp = Files.createTempFile(file.getParent(), ".lyrics-work-", ext);
            try {
                PosixFileAttributes permissions = Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                AudioFile copy = AudioFileIO.read(temp.toFile());
                copy.getTagOrCreateAndSetDefault().setField(FieldKey.LYRICS, lyrics);
                copy.commit();
                AudioFile verified = AudioFileIO.read(temp.toFile());
                if (!lyrics.equals(TagWriterService.readEmbeddedLyrics(verified.getTag())) || !before.equals(snapshot(verified, temp))) return false;
                Files.setPosixFilePermissions(temp, permissions.permissions());
                PosixFileAttributeView view = Files.getFileAttributeView(temp, PosixFileAttributeView.class);
                view.setOwner(permissions.owner()); view.setGroup(permissions.group());
                Files.setLastModifiedTime(temp, expected.mtime());
                try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
                // Application mutations share the guard. External writers must be stopped by the operator.
                if (Thread.currentThread().isInterrupted() || !LyricsBackfillService.safePath(root, file)
                    || !expected.equals(identity(file)) || LyricsBackfillService.hasSidecar(file)
                    || TagWriterService.lyricsState(AudioFileIO.read(file.toFile()).getTag()) != TagWriterService.LyricsState.ABSENT) return false;
                replace.replace(temp, file);
                if (export) {
                    try { export(file, lyrics, before.duration()); }
                    catch (IOException e) { log.warn("歌词已写入，旁置导出失败: {} - {}", file, e.getMessage()); }
                }
                return true;
            } finally { Files.deleteIfExists(temp); }
        } catch (InterruptedException e) { throw e; }
        catch (Exception e) { if (e instanceof IOException io) throw io; throw new IOException("lyrics.verify.failed", e); }
    }
    private record Snapshot(List<String> fields, List<String> artwork, String audioDigest, double duration,
                            String encoding, String sampleRate, String channels) {}
    private static Snapshot snapshot(AudioFile audio, Path path) throws Exception {
        AudioHeader h = audio.getAudioHeader();
        Long start = h.getAudioDataStartPosition(), end = h.getAudioDataEndPosition();
        if (start == null || end == null || start < 0 || end <= start || end > Files.size(path)) throw new IOException("audio.range.unavailable");
        verifyContainerRange(path, start, end);
        Tag tag = audio.getTag();
        List<String> fields = new ArrayList<>(), artwork = new ArrayList<>();
        if (tag != null) {
            Tag actual = tag instanceof FlacTag flac ? flac.getVorbisCommentTag() : tag;
            Iterator<TagField> iterator = actual.getFields();
            while (iterator.hasNext()) {
                TagField field = iterator.next(); String id = field.getId().toUpperCase(Locale.ROOT);
                if (!Set.of("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "USLT", "SYLT", "ULT", "SLT", "©LYR").contains(id))
                    fields.add(field.getId() + ":" + Base64.getEncoder().encodeToString(field.getRawContent()));
            }
            for (var art : tag.getArtworkList()) artwork.add(art.getMimeType() + ":" + art.getDescription() + ":" + art.getPictureType() + ":" + Base64.getEncoder().encodeToString(art.getBinaryData()));
        }
        Collections.sort(fields);
        return new Snapshot(fields, artwork, hash(path, start, end - start), h.getPreciseTrackLength(), h.getEncodingType(), h.getSampleRate(), h.getChannels());
    }
    private static void verifyContainerRange(Path path, long start, long end) throws IOException {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".flac")) {
            try (var input = new RandomAccessFile(path.toFile(), "r")) {
                if (input.readInt() != 0x664c6143) throw new IOException("flac.id3");
                boolean last = false;
                for (int count = 0; !last && count < 1024; count++) {
                    int type = input.readUnsignedByte(); last = (type & 128) != 0;
                    int length = (input.readUnsignedByte() << 16) | (input.readUnsignedByte() << 8) | input.readUnsignedByte();
                    input.seek(input.getFilePointer() + length);
                    if (input.getFilePointer() > input.length()) throw new IOException("flac.blocks.invalid");
                }
                if (!last || input.getFilePointer() != start || end != input.length()) throw new IOException("flac.audio.range.incomplete");
            }
        } else if (name.endsWith(".m4a") || name.endsWith(".mp4")) {
            // Multiple mdats or noncanonical range semantics cannot prove complete audio preservation.
            try (var input = new RandomAccessFile(path.toFile(), "r")) {
                int mdats = 0; long pos = 0;
                while (pos < input.length()) {
                    if (input.length() - pos < 8) throw new IOException("mp4.atom.invalid");
                    input.seek(pos); long size = Integer.toUnsignedLong(input.readInt()); int type = input.readInt(); int header = 8;
                    if (size == 1) { size = input.readLong(); header = 16; }
                    else if (size == 0) size = input.length() - pos;
                    if (size < header || size > input.length() - pos) throw new IOException("mp4.atom.invalid");
                    if (type == 0x6d646174) {
                        mdats++;
                        if (start != pos + header || end != pos + size) throw new IOException("mp4.audio.range.incomplete");
                    }
                    pos += size;
                }
                if (mdats != 1) throw new IOException("mp4.audio.range.unverified");
            }
        } else if (!name.endsWith(".mp3")) throw new IOException("audio.range.unverified");
    }

    static boolean hasFlacId3(Path path, String ext) throws IOException {
        if (!ext.equals(".flac")) return false;
        try (InputStream in = Files.newInputStream(path)) { return !Arrays.equals(in.readNBytes(4), new byte[] {'f', 'L', 'a', 'C'}); }
    }
    static String hash(Path file, long start, long length) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                channel.position(start); java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(65536);
                while (length > 0) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("lyrics.cancelled");
                    buffer.clear(); buffer.limit((int) Math.min(buffer.capacity(), length));
                    int read = channel.read(buffer); if (read <= 0) throw new IOException("unexpected.eof");
                    digest.update(buffer.array(), 0, read); length -= read;
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static void export(Path file, String lyrics, double duration) throws IOException {
        String name = file.getFileName().toString(); String base = name.substring(0, name.lastIndexOf('.'));
        Path sidecar = file.resolveSibling(base + (LyricsMatcher.validSynced(lyrics, duration) ? ".lrc" : ".txt"));
        try { Files.writeString(sidecar, lyrics, StandardOpenOption.CREATE_NEW); }
        catch (FileAlreadyExistsException ignored) {}
    }
}
