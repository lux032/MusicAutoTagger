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
        recordAll(Map.of(sourcePath, oldTargetPath));
    }

    /** 批量登记待替换映射（仅执行一次 JSON 读 + 写，避免大批量重识别引发 O(N^2) 写放大）。 */
    public void recordAll(Map<String, String> sourceToOldTarget) {
        if (sourceToOldTarget == null || sourceToOldTarget.isEmpty()) {
            return;
        }
        Map<String, Entry> valid = new LinkedHashMap<>();
        for (Map.Entry<String, String> item : sourceToOldTarget.entrySet()) {
            String src = item.getKey();
            String dst = item.getValue();
            if (src == null || src.isBlank() || dst == null || dst.isBlank()) {
                continue;
            }
            valid.put(normalizePathKey(src), new Entry(normalizePathKey(dst)));
        }
        if (valid.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            Optional<Map<String, Entry>> loaded = loadOrEmpty();
            if (loaded.isEmpty()) {
                log.error("待替换记录文件读取异常，拒绝批量覆写以防丢失已有记录: {}", storeFile);
                return;
            }
            Map<String, Entry> entries = loaded.get();
            entries.putAll(valid);
            save(entries);
        }
    }

    /** Transfer the identity to a copy that this operation actually created. */
    void recordAlias(Path original, Path copy) {
        if (original == null || copy == null) {
            return;
        }
        synchronized (LOCK) {
            Optional<Map<String, Entry>> loaded = loadOrEmpty();
            if (loaded.isEmpty()) {
                log.error("待替换记录文件读取异常，跳过别名登记以防覆写清空: {}", storeFile);
                return;
            }
            Map<String, Entry> entries = loaded.get();
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
            Optional<Map<String, Entry>> loaded = loadOrEmpty();
            if (loaded.isEmpty()) {
                return Optional.empty();
            }
            Entry match = findEntry(loaded.get(), sourceFile);
            if (match == null || match.oldTargetPath == null || match.oldTargetPath.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(Path.of(match.oldTargetPath));
        }
    }

    /** Remove every source alias for this specific archive once its replacement has committed. */
    public void remove(File sourceFile) {
        synchronized (LOCK) {
            Optional<Map<String, Entry>> loaded = loadOrEmpty();
            if (loaded.isEmpty()) {
                log.error("待替换记录文件读取异常，跳过删除操作以防覆写清空: {}", storeFile);
                return;
            }
            Map<String, Entry> entries = loaded.get();
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

    private Optional<Map<String, Entry>> loadOrEmpty() {
        if (!Files.exists(storeFile)) {
            return Optional.of(new LinkedHashMap<>());
        }
        if (!Files.isRegularFile(storeFile)) {
            log.warn("待替换记录路径不是普通文件，拒绝覆写: {}", storeFile);
            return Optional.empty();
        }
        try {
            String raw = Files.readString(storeFile, StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return Optional.of(new LinkedHashMap<>());
            }
            Map<String, Entry> loaded = gson.fromJson(raw, MAP_TYPE);
            return Optional.ofNullable(loaded);
        } catch (Exception e) {
            log.warn("读取或解析待替换记录失败，拒绝清空覆写: {} - {}", storeFile, e.getMessage());
            return Optional.empty();
        }
    }

    private void save(Map<String, Entry> entries) {
        Path normalized = storeFile.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        Path tmp = null;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            tmp = normalized.resolveSibling(normalized.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(entries, MAP_TYPE), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, normalized, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } catch (IOException e) {
            log.warn("保存待替换记录失败: {} - {}", storeFile, e.getMessage());
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
