package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lux032.musicautotagger.model.MusicMetadata;
import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

/** Bounded persistent misses only; preview and transient failures must not populate it. */
final class LyricsNegativeCache {
    static final String KEY_VERSION = "lyrics-v3";
    static final int MAX_ENTRIES = 2000;
    private final Path file;
    private final Clock clock;
    private final Map<String, Long> entries = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    LyricsNegativeCache(Path file, Clock clock) {
        this.file = file; this.clock = clock;
        try {
            if (Files.isRegularFile(file) && Files.size(file) <= 2_000_000)
                entries.putAll(mapper.readValue(file.toFile(), new TypeReference<Map<String, Long>>() {}));
        } catch (IOException | RuntimeException ignored) { entries.clear(); }
        prune();
    }
    static String key(MusicMetadata md) {
        // Exact fields + Double hex preserve strict identity, album-sensitive get and fractional boundaries.
        return KEY_VERSION + ":" + new com.google.gson.Gson().toJson(List.of(
            Objects.toString(md.getTitle(), ""), Objects.toString(md.getArtist(), ""),
            Objects.toString(md.getAlbum(), ""), Objects.toString(md.getRecordingId(), ""),
            md.getLocalPreciseDuration() == null ? "" : Double.toHexString(md.getLocalPreciseDuration()),
            LyricsMatcher.versions(md.getTitle()).toString()));
    }
    synchronized boolean contains(String key) { prune(); return entries.containsKey(key); }
    synchronized void put(String key, LyricsService.Status status) throws IOException {
        long days = switch (status) { case NOT_FOUND -> 7; case UNSAFE, INSTRUMENTAL -> 1; default -> 0; };
        if (days == 0) return;
        entries.remove(key); entries.put(key, clock.millis() + days * 86400000L); prune();
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), ".lyrics-cache-", ".tmp");
        try {
            mapper.writeValue(tmp.toFile(), entries);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }
    private void prune() {
        entries.entrySet().removeIf(e -> e.getValue() == null || e.getValue() <= clock.millis());
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
    }
}
