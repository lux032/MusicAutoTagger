package com.lux032.musicautotagger.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Tracks previous scraped output paths for files that were forgotten or queued for re-identification.
 *
 * <p>Old scraped files are NEVER deleted before the re-scrape succeeds. Once a new output file is
 * safely written, any obsolete previous output at a different path inside {@code outputDirectory}
 * is deleted along with empty parent album/artist directories.
 */
@Slf4j
public class ReidentifyReplacementRegistry {

    private static final Object LOCK = new Object();
    private static final java.lang.reflect.Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Entry>>() { }.getType();

    private final Path storeFile;
    private final Gson gson = new Gson();

    public ReidentifyReplacementRegistry(Path storeFile) {
        this.storeFile = storeFile;
    }

    public static ReidentifyReplacementRegistry forReviewQueuePath(String reviewQueuePath) {
        Path base = reviewQueuePath == null || reviewQueuePath.isBlank()
            ? Path.of("data", "review-queue.json") : Path.of(reviewQueuePath);
        return new ReidentifyReplacementRegistry(base.toAbsolutePath().resolveSibling("pending-replacements.json"));
    }

    public static class Entry {
        private String sourcePath;
        private String oldTargetPath;
        private String fileHash;
        private String audioMd5;
        private long recordedAt;

        public Entry() { }

        public Entry(String sourcePath, String oldTargetPath, String fileHash, long recordedAt) {
            this(sourcePath, oldTargetPath, fileHash, null, recordedAt);
        }

        public Entry(String sourcePath, String oldTargetPath, String fileHash, String audioMd5, long recordedAt) {
            this.sourcePath = sourcePath;
            this.oldTargetPath = oldTargetPath;
            this.fileHash = fileHash;
            this.audioMd5 = audioMd5;
            this.recordedAt = recordedAt;
        }

        public String getSourcePath() { return sourcePath; }
        public String getOldTargetPath() { return oldTargetPath; }
        public String getFileHash() { return fileHash; }
        public String getAudioMd5() { return audioMd5; }
        public long getRecordedAt() { return recordedAt; }
    }

    public void record(String sourcePath, String oldTargetPath, String fileHash) {
        record(sourcePath, oldTargetPath, fileHash, null);
    }

    public void record(String sourcePath, String oldTargetPath, String fileHash, String audioMd5) {
        if (sourcePath == null || sourcePath.isBlank() || oldTargetPath == null || oldTargetPath.isBlank()) {
            return;
        }
        String key = normalizePathKey(sourcePath);
        String normalizedTarget = normalizePathKey(oldTargetPath);
        synchronized (LOCK) {
            Map<String, Entry> entries = load();
            entries.put(key, new Entry(key, normalizedTarget,
                fileHash == null || fileHash.isBlank() ? null : fileHash.trim(),
                audioMd5 == null || audioMd5.isBlank() ? null : audioMd5.trim(),
                System.currentTimeMillis()));
            save(entries);
        }
    }

    public Optional<Path> findOldTarget(File sourceFile, String fileHash) {
        return findOldTarget(sourceFile, fileHash, null);
    }

    public Optional<Path> findOldTarget(File sourceFile, String fileHash, String audioMd5) {
        if (sourceFile == null && (fileHash == null || fileHash.isBlank()) && (audioMd5 == null || audioMd5.isBlank())) {
            return Optional.empty();
        }
        synchronized (LOCK) {
            Map<String, Entry> entries = load();
            Entry match = findEntry(entries, sourceFile, fileHash, audioMd5);
            if (match == null || match.getOldTargetPath() == null || match.getOldTargetPath().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(Path.of(match.getOldTargetPath()));
        }
    }

    public void remove(File sourceFile, String fileHash) {
        remove(sourceFile, fileHash, null);
    }

    public void remove(File sourceFile, String fileHash, String audioMd5) {
        if (sourceFile == null && (fileHash == null || fileHash.isBlank()) && (audioMd5 == null || audioMd5.isBlank())) {
            return;
        }
        synchronized (LOCK) {
            Map<String, Entry> entries = load();
            boolean changed = false;
            if (sourceFile != null) {
                if (entries.remove(normalizePathKey(sourceFile.getAbsolutePath())) != null) changed = true;
                String canonical = canonicalOrNull(sourceFile);
                if (canonical != null && entries.remove(canonical) != null) changed = true;
            }
            if (fileHash != null && !fileHash.isBlank()) {
                String trimmedHash = fileHash.trim();
                changed |= entries.values().removeIf(e -> trimmedHash.equals(e.getFileHash()));
            }
            if (audioMd5 != null && !audioMd5.isBlank()) {
                String trimmedMd5 = audioMd5.trim();
                changed |= entries.values().removeIf(e -> trimmedMd5.equals(e.getAudioMd5()));
            }
            if (changed) {
                save(entries);
            }
        }
    }

    /**
     * Completes a replacement after the new output file has been written:
     * deletes the old file (if at a different path inside {@code outputRoot}),
     * cleans empty parent directories, and clears the pending entry.
     */
    public boolean completeReplacement(File sourceFile, String fileHash, Path newTarget,
                                       Path outputRoot, boolean cleanLyricsTxt) {
        return completeReplacement(sourceFile, fileHash, null, newTarget, outputRoot, cleanLyricsTxt);
    }

    public boolean completeReplacement(File sourceFile, String fileHash, String audioMd5, Path newTarget,
                                       Path outputRoot, boolean cleanLyricsTxt) {
        Optional<Path> oldTargetOpt = findOldTarget(sourceFile, fileHash, audioMd5);
        if (oldTargetOpt.isEmpty()) {
            return false;
        }
        Path oldTarget = oldTargetOpt.get();
        boolean deleted = deleteReplacedFileAndEmptyParents(oldTarget, newTarget, outputRoot, cleanLyricsTxt);
        remove(sourceFile, fileHash, audioMd5);
        return deleted;
    }

    /**
     * Deletes an obsolete scraped output file if it resides strictly inside {@code outputRoot}
     * and differs from {@code newTarget}, then removes any parent directories that became empty.
     */
    public static boolean deleteReplacedFileAndEmptyParents(Path oldTarget, Path newTarget,
                                                            Path outputRoot, boolean cleanLyricsTxt) {
        if (oldTarget == null || outputRoot == null) {
            return false;
        }
        Path normalizedRoot = outputRoot.toAbsolutePath().normalize();
        Path normalizedOld = oldTarget.toAbsolutePath().normalize();
        if (!normalizedOld.startsWith(normalizedRoot) || normalizedOld.equals(normalizedRoot)) {
            return false;
        }
        if (newTarget != null && normalizedOld.equals(newTarget.toAbsolutePath().normalize())) {
            return false;
        }
        boolean deleted = false;
        try {
            if (Files.isRegularFile(normalizedOld)) {
                deleted = Files.deleteIfExists(normalizedOld);
                if (deleted) {
                    log.info("已清理重新刮削前的旧归档文件: {}", normalizedOld);
                    LogCollector.addLog("INFO", "已删除重新刮削的旧归档文件: " + normalizedRoot.relativize(normalizedOld));
                }
            }
            deleteSidecarLyrics(normalizedOld, cleanLyricsTxt);
            cleanupEmptyParents(normalizedOld.getParent(), normalizedRoot);
        } catch (IOException e) {
            log.warn("清理旧归档文件失败 ({}): {}", normalizedOld, e.getMessage());
        }
        return deleted;
    }

    public static void cleanupEmptyParents(Path startDir, Path outputRoot) {
        if (startDir == null || outputRoot == null) {
            return;
        }
        Path normalizedRoot = outputRoot.toAbsolutePath().normalize();
        Path dir = startDir.toAbsolutePath().normalize();
        while (dir != null && dir.startsWith(normalizedRoot) && !dir.equals(normalizedRoot)) {
            try (Stream<Path> entries = Files.list(dir)) {
                if (entries.findAny().isPresent()) {
                    break;
                }
                Files.deleteIfExists(dir);
                log.debug("已清理空归档目录: {}", dir);
                dir = dir.getParent();
            } catch (IOException e) {
                log.debug("清理空目录失败 ({}): {}", dir, e.getMessage());
                break;
            }
        }
    }

    private static void deleteSidecarLyrics(Path audioFile, boolean cleanTxt) {
        String name = audioFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        Path lrc = audioFile.resolveSibling(stem + ".lrc");
        try {
            Files.deleteIfExists(lrc);
            if (cleanTxt) {
                Files.deleteIfExists(audioFile.resolveSibling(stem + ".txt"));
            }
        } catch (IOException e) {
            log.debug("清理旧歌词附属文件失败 ({}): {}", audioFile, e.getMessage());
        }
    }

    private static Entry findEntry(Map<String, Entry> entries, File sourceFile, String fileHash, String audioMd5) {
        if (sourceFile != null) {
            Entry direct = entries.get(normalizePathKey(sourceFile.getAbsolutePath()));
            if (direct != null) return direct;
            String canonical = canonicalOrNull(sourceFile);
            if (canonical != null) {
                Entry byCanonical = entries.get(canonical);
                if (byCanonical != null) return byCanonical;
            }
        }
        if (fileHash != null && !fileHash.isBlank()) {
            String trimmed = fileHash.trim();
            for (Entry entry : entries.values()) {
                if (trimmed.equals(entry.getFileHash())) {
                    return entry;
                }
            }
        }
        if (audioMd5 != null && !audioMd5.isBlank()) {
            String trimmedMd5 = audioMd5.trim();
            for (Entry entry : entries.values()) {
                if (trimmedMd5.equals(entry.getAudioMd5())) {
                    return entry;
                }
            }
        }
        return null;
    }

    private static String normalizePathKey(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString();
    }

    private static String canonicalOrNull(File file) {
        try {
            return file.getCanonicalFile().toPath().normalize().toString();
        } catch (IOException e) {
            return null;
        }
    }

    private Map<String, Entry> load() {
        if (!Files.isRegularFile(storeFile)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Entry> loaded = gson.fromJson(Files.readString(storeFile, StandardCharsets.UTF_8), MAP_TYPE);
            return loaded == null ? new LinkedHashMap<>() : loaded;
        } catch (Exception e) {
            log.warn("读取待替换记录失败，已忽略: {} - {}", storeFile, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void save(Map<String, Entry> entries) {
        try {
            if (storeFile.getParent() != null) {
                Files.createDirectories(storeFile.getParent());
            }
            Path tmp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(entries, MAP_TYPE), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("保存待替换记录失败: {} - {}", storeFile, e.getMessage());
        }
    }
}
