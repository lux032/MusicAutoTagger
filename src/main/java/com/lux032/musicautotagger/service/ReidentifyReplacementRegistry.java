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
        private String oldTargetPath;

        public Entry() { }

        private Entry(String oldTargetPath) {
            this.oldTargetPath = oldTargetPath;
        }
    }

    public void record(String sourcePath, String oldTargetPath) {
        if (sourcePath == null || sourcePath.isBlank() || oldTargetPath == null || oldTargetPath.isBlank()) {
            return;
        }
        synchronized (LOCK) {
            Map<String, Entry> entries = load();
            entries.put(normalizePathKey(sourcePath), new Entry(normalizePathKey(oldTargetPath)));
            save(entries);
        }
    }

    /** Transfer the identity to a copy that this operation actually created. */
    void recordAlias(Path original, Path copy) {
        synchronized (LOCK) {
            Map<String, Entry> entries = load();
            Entry match = findEntry(entries, original.toFile());
            String copyKey = normalizePathKey(copy.toString());
            if (match != null && match.oldTargetPath != null) {
                entries.put(copyKey, match);
                save(entries);
            } else if (entries.remove(copyKey) != null) {
                save(entries);
            }
        }
    }

    /** Only a known source path authorizes replacing an archive. A recording can occur on multiple releases. */
    public Optional<Path> findOldTarget(File sourceFile) {
        synchronized (LOCK) {
            Entry match = findEntry(load(), sourceFile);
            if (match == null || match.oldTargetPath == null || match.oldTargetPath.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(Path.of(match.oldTargetPath));
        }
    }

    /** Remove every source alias for this specific archive once its replacement has committed. */
    public void remove(File sourceFile) {
        synchronized (LOCK) {
            Map<String, Entry> entries = load();
            Entry match = findEntry(entries, sourceFile);
            if (match != null && match.oldTargetPath != null
                    && entries.values().removeIf(entry -> entry != null && match.oldTargetPath.equals(entry.oldTargetPath))) {
                save(entries);
            }
        }
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

    private static Entry findEntry(Map<String, Entry> entries, File sourceFile) {
        if (sourceFile == null) {
            return null;
        }
        Entry direct = entries.get(normalizePathKey(sourceFile.getAbsolutePath()));
        return direct != null ? direct : entries.get(canonicalOrNull(sourceFile));
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
