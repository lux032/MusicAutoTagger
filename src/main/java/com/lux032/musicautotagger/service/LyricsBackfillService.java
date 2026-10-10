package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Single worker scanning the library, independent of processing history. */
public class LyricsBackfillService implements AutoCloseable {
    private final MusicConfig config;
    private final LyricsService lyrics;
    private final TagWriterService writer;
    private final LyricsNegativeCache cache;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lyrics-backfill"); t.setDaemon(true); return t;
    });
    private volatile Thread worker;
    private boolean running, closed, preview;
    private volatile boolean cancelled;
    private int scanned, eligible, matched, updated, skipped, deferred;
    private String current = "", message = "idle", skipReason = "";
    public LyricsBackfillService(MusicConfig config, LyricsService lyrics, TagWriterService writer) {
        this.config = config; this.lyrics = lyrics; this.writer = writer;
        cache = new LyricsNegativeCache(Path.of(config.getOutputDirectory()).resolve(".lyrics-negative-cache.json"), Clock.systemUTC());
    }
    public synchronized boolean start(boolean preview) {
        if (running || closed) return false;
        this.preview = preview; running = true; cancelled = false;
        scanned = eligible = matched = updated = skipped = deferred = 0; message = "running";
        final LyricsProtectedPaths paths;
        try { paths = LyricsProtectedPaths.capture(config); }
        catch (Exception e) { running = false; message = "failed: " + e.getMessage(); return true; }
        executor.submit(() -> {
            worker = Thread.currentThread();
            try { run(preview, paths); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (Exception e) { synchronized (this) { message = "failed: " + e.getMessage(); } }
            finally {
                synchronized (this) { running = false; current = ""; if (cancelled) message = "cancelled"; else if (message.equals("running")) message = "finished"; }
                worker = null;
            }
        });
        return true;
    }
    public synchronized void cancel() { cancelled = true; Thread t = worker; if (t != null) t.interrupt(); }
    public synchronized Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>(Map.of("running", running, "preview", preview, "scanned", scanned, "eligible", eligible,
            "matched", matched, "updated", updated, "skipped", skipped, "deferred", deferred,
            "currentFile", current, "message", message));
        status.put("skipReason", skipReason);
        return status;
    }
    private void run(boolean preview, LyricsProtectedPaths paths) throws Exception {
        Path root = paths.root;
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !root.equals(root.toRealPath())) throw new IOException("unsafe.output.root");
        paths.validateRoot();
        List<Path> candidates = scan(paths);
        for (Path file : candidates) {
            checkCancelled();
            synchronized (this) { scanned++; current = root.relativize(file).toString(); }
            try {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".ogg") || name.endsWith(".opus")) { skip("audio.range.unverified"); continue; }
                if (name.endsWith(".flac") && LyricsOnlyWriter.hasFlacId3(file, ".flac")) { skip("flac.id3"); continue; }
                if (!safePath(root, file) || hasSidecar(file) || writer.embeddedLyricsState(file.toFile()) != TagWriterService.LyricsState.ABSENT) { skip(); continue; }
                LyricsOnlyWriter.Identity identity = LyricsOnlyWriter.identity(file);
                MusicMetadata md = writer.readTags(file.toFile());
                if (md == null || blank(md.getTitle()) || blank(md.getArtist())) { skip(); continue; }
                md.setLocalPreciseDuration(TagWriterService.readPreciseDuration(file.toFile()));
                if (!LyricsMatcher.reliableDuration(md.getLocalPreciseDuration()) || LyricsMatcher.instrumental(md.getTitle())) { skip(); continue; }
                LyricsOnlyWriter.preflight(root, file);
                if (!identity.equals(LyricsOnlyWriter.identity(file))) { skip("file.changed"); continue; }
                String key = LyricsNegativeCache.key(md);
                if (!preview && cache.contains(key)) { skip(); continue; }
                synchronized (this) { eligible++; }
                LyricsService.Result result = lyrics.lookup(md.getTitle(), md.getArtist(), md.getAlbum(), md.getLocalPreciseDuration(), true);
                checkCancelled();
                if (result.status() == LyricsService.Status.FOUND) {
                    synchronized (this) { matched++; }
                    if (!preview && LyricsOnlyWriter.write(root, file, identity, result.lyrics(), paths.export)) {
                        synchronized (this) { updated++; }
                    } else if (!preview) { skip("write.verification.failed"); }
                } else {
                    synchronized (this) { if (result.status() == LyricsService.Status.DEFERRED) deferred++; else skipped++; }
                    if (!preview && !cancelled) cache.put(key, result.status());
                }
                Thread.sleep(1000);
            } catch (IOException e) { skip(e.getMessage()); }
        }
    }
    List<Path> scan(Path root) throws IOException {
        LyricsProtectedPaths paths = LyricsProtectedPaths.capture(config);
        if (!root.equals(paths.root)) throw new IOException("unsafe.output.root");
        paths.validateRoot();
        return scan(paths);
    }
    private List<Path> scan(LyricsProtectedPaths paths) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(paths.root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (cancelled) return FileVisitResult.TERMINATE;
                return paths.excluded(dir, attrs) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (cancelled) return FileVisitResult.TERMINATE;
                if (attrs.isRegularFile() && !paths.excluded(file, attrs) && supported(file)) files.add(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException e) { return FileVisitResult.CONTINUE; }
        });
        return files;
    }
    static boolean supported(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return List.of(".mp3", ".flac", ".m4a", ".mp4", ".ogg", ".opus").stream().anyMatch(name::endsWith);
    }
    static boolean safePath(Path root, Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) return false;
        for (Path p = normalized; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) return false;
        return normalized.equals(normalized.toRealPath());
    }
    static boolean hasSidecar(Path file) throws IOException {
        String name = file.getFileName().toString(); int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        for (String ext : List.of(".lrc", ".txt")) {
            Path sidecar = file.resolveSibling(base + ext);
            if (Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(sidecar) || Files.size(sidecar) > 0)) return true;
        }
        return false;
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private void skip() { skip("ineligible.or.existing"); }
    private synchronized void skip(String reason) { skipped++; skipReason = reason; }
    private void checkCancelled() throws InterruptedException { if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
    public void close() {
        synchronized (this) { closed = true; cancel(); executor.shutdownNow(); }
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                synchronized (this) { message = "shutdown.pending"; }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
