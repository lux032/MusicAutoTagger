package com.lux032.musicautotagger.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 记录「LLM 艺术家匹配已自动归档、但仍保留了 FAILED 复核副本」的对应关系。
 *
 * 自动归档不是人工确认，所以失败副本会保留在恢复页面；但如果不记下它已经归档到哪里，
 * 之后对这份副本做重新识别/联网确认就会在音乐库里再写一份（目录名一旦不同，
 * 提交前的同名检查拦不住）。有了这条链接，恢复成功时可以把旧的自动归档换掉而不是并存。
 *
 * 数据量很小，每次直接读写 JSON 文件，不做内存缓存，
 * 这样 FailedFileHandler 与 RecoveryService 各自持有实例也不会看到不一致的状态。
 */
@Slf4j
public class AutoArchiveRegistry {

    private static final Object LOCK = new Object();
    private static final java.lang.reflect.Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Link>>() { }.getType();

    private final Path storeFile;
    private final Gson gson = new Gson();

    public AutoArchiveRegistry(Path storeFile) {
        this.storeFile = storeFile;
    }

    /** 与待确认队列放在同一数据目录，避免新增配置项。 */
    public static AutoArchiveRegistry forReviewQueuePath(String reviewQueuePath) {
        Path base = reviewQueuePath == null || reviewQueuePath.isBlank()
            ? Path.of("data", "review-queue.json") : Path.of(reviewQueuePath);
        return new AutoArchiveRegistry(base.toAbsolutePath().resolveSibling("auto-archive-links.json"));
    }

    public static class Link {
        /** 自动归档在输出目录中的位置：专辑时为目录，散落单曲时为文件。 */
        private String archivedPath;
        /** 归档完成时的音频文件清单（相对 archivedPath；单文件时为文件名本身）。 */
        private List<String> audioFiles = new ArrayList<>();
        private long archivedAt;

        public Link() { }

        public Link(String archivedPath, List<String> audioFiles, long archivedAt) {
            this.archivedPath = archivedPath;
            this.audioFiles = audioFiles == null ? new ArrayList<>() : new ArrayList<>(audioFiles);
            this.archivedAt = archivedAt;
        }

        public String getArchivedPath() { return archivedPath; }
        public List<String> getAudioFiles() { return audioFiles == null ? List.of() : audioFiles; }
        public long getArchivedAt() { return archivedAt; }
    }

    /** @param failedRelativePath 失败目录下的条目名（与恢复页面的 relativePath 一致） */
    public void record(String failedRelativePath, Path archivedPath, List<String> audioFiles) {
        if (failedRelativePath == null || archivedPath == null) return;
        synchronized (LOCK) {
            Map<String, Link> links = load();
            links.put(failedRelativePath, new Link(archivedPath.toAbsolutePath().toString(), audioFiles,
                System.currentTimeMillis()));
            save(links);
        }
    }

    public Optional<Link> find(String failedRelativePath) {
        if (failedRelativePath == null) return Optional.empty();
        synchronized (LOCK) {
            return Optional.ofNullable(load().get(failedRelativePath));
        }
    }

    public void remove(String failedRelativePath) {
        if (failedRelativePath == null) return;
        synchronized (LOCK) {
            Map<String, Link> links = load();
            if (links.remove(failedRelativePath) != null) save(links);
        }
    }

    private Map<String, Link> load() {
        if (!Files.isRegularFile(storeFile)) return new LinkedHashMap<>();
        try {
            Map<String, Link> links = gson.fromJson(Files.readString(storeFile, StandardCharsets.UTF_8), MAP_TYPE);
            return links == null ? new LinkedHashMap<>() : links;
        } catch (Exception e) {
            // 链接只是去重辅助信息，损坏时退化为「没有链接」，由音频指纹去重兜底。
            log.warn("自动归档链接文件无法解析，已忽略: {} - {}", storeFile, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void save(Map<String, Link> links) {
        try {
            Files.createDirectories(storeFile.getParent());
            Path tmp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(links, MAP_TYPE), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("保存自动归档链接失败: {} - {}", storeFile, e.getMessage());
        }
    }
}
