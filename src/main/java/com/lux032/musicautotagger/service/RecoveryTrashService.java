package com.lux032.musicautotagger.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.util.FileSystemUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 人工恢复回收站与崩溃替换备份管理服务（从 RecoveryService 抽离）。
 * 负责回收站入站/列表/还原/彻底删除、保留期自动清理、跨设备安全移动以及替换中途崩溃遗留备份的启动恢复。
 */
@Slf4j
public class RecoveryTrashService {

    static final String PENDING_REPLACE_PREFIX = ".pending-replace-";
    static final String PENDING_REPLACE_MANIFEST = ".manifest.json";
    static final String TRASH_KIND_OUTPUT = "OUTPUT";

    private static final AtomicLong TRASH_SEQ = new AtomicLong(1);

    @FunctionalInterface
    public interface ItemResolver {
        File resolve(RecoveryService.SourceType type, String relativePath) throws IOException;
    }

    private final MusicConfig config;
    private final FileSystemUtils fileSystemUtils;
    private final Gson gson;
    private final ItemResolver itemResolver;
    private final Function<File, List<File>> audioFilesCollector;
    private final Runnable onMutation;
    private final RecoveryOriginRegistry originRegistry;

    public RecoveryTrashService(MusicConfig config,
                                FileSystemUtils fileSystemUtils,
                                Gson gson,
                                ItemResolver itemResolver,
                                Function<File, List<File>> audioFilesCollector,
                                Runnable onMutation) {
        this(config, fileSystemUtils, gson, itemResolver, audioFilesCollector, onMutation,
            RecoveryOriginRegistry.forReviewQueuePath(config == null ? null : config.getReviewQueuePath()));
    }

    public RecoveryTrashService(MusicConfig config,
                                FileSystemUtils fileSystemUtils,
                                Gson gson,
                                ItemResolver itemResolver,
                                Function<File, List<File>> audioFilesCollector,
                                Runnable onMutation,
                                RecoveryOriginRegistry originRegistry) {
        this.config = config;
        this.fileSystemUtils = fileSystemUtils;
        this.gson = gson;
        this.itemResolver = itemResolver;
        this.audioFilesCollector = audioFilesCollector;
        this.onMutation = onMutation;
        this.originRegistry = originRegistry;
    }

    public Path trashRoot() {
        String configured = config.getRecoveryTrashDirectory();
        return Path.of(configured == null || configured.isBlank() ? "data/recovery-trash" : configured);
    }

    public Path nextPendingReplaceRoot(String taskId) {
        return trashRoot().resolve(PENDING_REPLACE_PREFIX + taskId + "_" + TRASH_SEQ.getAndIncrement());
    }

    /**
     * 恢复上次在替换音乐库旧归档中途崩溃遗留的持久化备份，避免用户原库专辑永久丢失。
     */
    public void recoverPendingReplacements() {
        Path root = trashRoot();
        if (!Files.isDirectory(root)) return;
        try (var stream = Files.list(root)) {
            for (Path dir : stream.toList()) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!name.startsWith(PENDING_REPLACE_PREFIX) || !Files.isDirectory(dir)) {
                    continue;
                }
                Path manifestPath = dir.resolve(PENDING_REPLACE_MANIFEST);
                boolean allRestored = true;
                if (Files.isRegularFile(manifestPath)) {
                    try {
                        JsonArray entries = gson.fromJson(Files.readString(manifestPath), JsonArray.class);
                        if (entries != null) {
                            for (int i = entries.size() - 1; i >= 0; i--) {
                                JsonObject obj = entries.get(i).getAsJsonObject();
                                Path backup = Path.of(obj.get("backup").getAsString());
                                Path original = Path.of(obj.get("original").getAsString());
                                if (Files.exists(backup)) {
                                    if (!Files.exists(original)) {
                                        Files.createDirectories(original.getParent());
                                        moveAcrossDevices(backup, original);
                                        log.warn("已从崩溃恢复被替换的旧归档: {}", original);
                                    } else {
                                        Path safeTrash = root.resolve(System.currentTimeMillis() + "_"
                                            + TRASH_SEQ.getAndIncrement() + "_RECOVERED_" + original.getFileName());
                                        moveAcrossDevices(backup, safeTrash);
                                        log.warn("检测到崩溃残留替换备份，已保留至回收站: {}", safeTrash);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        allRestored = false;
                        log.error("恢复崩溃残留替换备份失败，保留目录以防丢数据: {} - {}", dir, e.getMessage());
                    }
                } else {
                    allRestored = false;
                }
                if (allRestored) {
                    try {
                        deleteRecursively(dir);
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            log.warn("扫描崩溃残留替换备份失败: {}", e.getMessage());
        }
    }

    /** 统计回收站当前条目数量（仅统计主体条目，忽略 manifest .json 与临时替换备份）。 */
    public int countTrash() {
        Path root = trashRoot();
        if (!Files.isDirectory(root)) return 0;
        try (var stream = Files.list(root)) {
            return (int) stream.filter(p -> {
                String name = p.getFileName().toString();
                return !name.endsWith(".json") && !name.startsWith(PENDING_REPLACE_PREFIX);
            }).count();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 回收站条目列表，按入站时间倒序。 */
    public List<Map<String, Object>> listTrash() {
        List<Map<String, Object>> result = new ArrayList<>();
        Path root = trashRoot();
        if (!Files.isDirectory(root)) return result;
        try (var stream = Files.list(root)) {
            for (Path path : stream.toList()) {
                String entryName = path.getFileName().toString();
                if (entryName.endsWith(".json") || entryName.startsWith(PENDING_REPLACE_PREFIX)) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                String sourceType = null;
                String relativePath = null;
                boolean outputKind = false;
                Path manifest = path.resolveSibling(path.getFileName() + ".json");
                if (Files.isRegularFile(manifest)) {
                    try {
                        JsonObject json = gson.fromJson(Files.readString(manifest), JsonObject.class);
                        if (json != null) {
                            outputKind = json.has("kind") && TRASH_KIND_OUTPUT.equals(json.get("kind").getAsString())
                                && json.has("originalPath");
                            if (outputKind) sourceType = TRASH_KIND_OUTPUT;
                            else if (json.has("sourceType")) sourceType = json.get("sourceType").getAsString();
                            if (json.has("relativePath")) relativePath = json.get("relativePath").getAsString();
                        }
                    } catch (Exception ignored) {
                        // manifest 损坏时仍然列出条目，只是无法自动恢复
                    }
                }
                List<File> audioFiles = audioFilesCollector.apply(path.toFile());
                entry.put("id", path.getFileName().toString());
                entry.put("sourceType", sourceType);
                entry.put("relativePath", relativePath);
                entry.put("restorable", sourceType != null && relativePath != null);
                entry.put("trashedAt", trashedAt(path));
                entry.put("fileCount", audioFiles.size());
                entry.put("path", path.toAbsolutePath().toString());
                result.add(entry);
            }
        } catch (Exception e) {
            log.warn("列出回收站失败: {}", e.getMessage());
        }
        result.sort(Comparator.comparingLong(m -> -((Number) m.get("trashedAt")).longValue()));
        return result;
    }

    /** 把回收站条目恢复回原隔离目录或音乐库原位。 */
    public synchronized void restoreFromTrash(String id) throws IOException {
        Path entry = resolveTrashEntry(id);
        Path manifest = entry.resolveSibling(entry.getFileName() + ".json");
        if (!Files.isRegularFile(manifest)) throw new IOException("trash.manifest.missing");

        String sourceType;
        String relativePath;
        JsonObject originsObj = null;
        try {
            JsonObject json = gson.fromJson(Files.readString(manifest), JsonObject.class);
            if (json.has("kind") && TRASH_KIND_OUTPUT.equals(json.get("kind").getAsString())) {
                restoreOutputFromTrash(entry, manifest, json.get("originalPath").getAsString());
                return;
            }
            sourceType = json.get("sourceType").getAsString();
            relativePath = json.get("relativePath").getAsString();
            if (json.has("origins") && json.get("origins").isJsonObject()) {
                originsObj = json.getAsJsonObject("origins");
            }
        } catch (Exception e) {
            throw new IOException("trash.manifest.invalid", e);
        }

        File target = itemResolver.resolve(RecoveryService.SourceType.valueOf(sourceType), relativePath);
        if (target.exists()) throw new IOException("trash.restore.target.exists");
        Path restoredBase = target.toPath().toAbsolutePath().normalize();
        Map<String, String> restoredOrigins = new LinkedHashMap<>();
        if (originRegistry != null && originsObj != null && !originsObj.isEmpty()) {
            for (var item : originsObj.entrySet()) {
                if (item.getValue() == null || !item.getValue().isJsonPrimitive()) continue;
                String originPath = item.getValue().getAsString();
                if (originPath == null || originPath.isBlank()) continue;
                String relKey = item.getKey();
                Path restoredFile = (relKey == null || relKey.isBlank() || ".".equals(relKey))
                    ? restoredBase : restoredBase.resolve(relKey).normalize();
                if (restoredFile.equals(restoredBase) || restoredFile.startsWith(restoredBase)) {
                    restoredOrigins.put(restoredFile.toString(), originPath);
                }
            }
        }
        Files.createDirectories(target.toPath().getParent());
        moveAcrossDevices(entry, target.toPath());
        if (originRegistry != null && !restoredOrigins.isEmpty()
                && !originRegistry.recordAllChecked(restoredOrigins)) {
            // 血缘写回失败时回滚音频还原并保留 manifest，避免丢失血缘后静默删除清单
            try {
                moveAcrossDevices(target.toPath(), entry);
            } catch (IOException rollbackErr) {
                log.error("血缘恢复失败且回滚还原受阻，保留回收站清单供人工核对: {}", manifest, rollbackErr);
            }
            throw new IOException("recovery.origin.restore.failed");
        }
        Files.deleteIfExists(manifest);
        notifyMutation();
        LogCollector.addLog("SUCCESS", "已从回收站恢复: " + relativePath + " → " + sourceType);
    }

    private void restoreOutputFromTrash(Path entry, Path manifest, String originalPath) throws IOException {
        try (var libraryWrite = LibraryWriteGuard.acquire()) {
            Path outputRoot = Path.of(config.getOutputDirectory()).toAbsolutePath().normalize();
            Path target = Path.of(originalPath).toAbsolutePath().normalize();
            if (!target.startsWith(outputRoot) || target.equals(outputRoot)) throw new IOException("trash.manifest.invalid");
            if (Files.exists(target)) throw new IOException("trash.restore.target.exists");
            Files.createDirectories(target.getParent());
            moveAcrossDevices(entry, target);
            Files.deleteIfExists(manifest);
            notifyMutation();
            LogCollector.addLog("SUCCESS", "已从回收站还原到音乐库: " + displayOutputPath(target));
        }
    }

    /** 立即彻底删除回收站条目。 */
    public synchronized void deleteFromTrash(String id) throws IOException {
        Path entry = resolveTrashEntry(id);
        deleteRecursively(entry);
        Files.deleteIfExists(entry.resolveSibling(entry.getFileName() + ".json"));
        notifyMutation();
        LogCollector.addLog("WARN", "已删除回收站条目: " + id);
    }

    /** 挡住 id 里的路径穿越，只允许操作回收站根下的直接子项。 */
    Path resolveTrashEntry(String id) throws IOException {
        if (id == null || id.isBlank()) throw new IOException("trash.id.required");
        if (id.endsWith(".json") || id.startsWith(PENDING_REPLACE_PREFIX)) throw new IOException("trash.id.invalid");
        Path root = trashRoot().toAbsolutePath().normalize();
        Path entry = root.resolve(id).toAbsolutePath().normalize();
        if (!entry.getParent().equals(root)) throw new IOException("trash.id.invalid");
        if (!Files.exists(entry)) throw new IOException("trash.entry.not.found");
        return entry;
    }

    public void moveToTrash(File source, String jobId, RecoveryService.SourceType sourceType,
                            String relativePath) throws IOException {
        Path sourceNorm = source.toPath().toAbsolutePath().normalize();
        Path sourceCanon = canonicalOrNormalized(sourceNorm);
        Map<Path, Path> origins = originRegistry == null ? Map.of() : originRegistry.findOriginsUnder(sourceNorm);

        int retention = config.getRecoveryTrashRetentionDays();
        if (retention == 0) {
            deleteRecursively(source.toPath());
            if (originRegistry != null && !origins.isEmpty()) {
                originRegistry.removeMatching(origins);
            }
            notifyMutation();
            return;
        }
        Path root = trashRoot();
        Files.createDirectories(root);
        String safeName = source.getName().replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        long trashedAt = System.currentTimeMillis();
        Path target = root.resolve(trashedAt + "_" + TRASH_SEQ.getAndIncrement() + "_" + jobId + "_" + sourceType + "_" + safeName);
        moveAcrossDevices(source.toPath(), target);

        JsonObject manifest = new JsonObject();
        manifest.addProperty("sourceType", String.valueOf(sourceType));
        manifest.addProperty("relativePath", relativePath);
        manifest.addProperty("trashedAt", trashedAt);
        if (!origins.isEmpty()) {
            JsonObject originsObj = new JsonObject();
            for (Map.Entry<Path, Path> item : origins.entrySet()) {
                String relKey = relativeKeyUnder(item.getKey(), sourceNorm, sourceCanon);
                if (relKey != null) {
                    originsObj.addProperty(relKey, item.getValue().toString());
                }
            }
            if (!originsObj.isEmpty()) {
                manifest.add("origins", originsObj);
            }
        }
        Files.writeString(target.resolveSibling(target.getFileName() + ".json"), gson.toJson(manifest));
        if (originRegistry != null && !origins.isEmpty()) {
            originRegistry.removeMatching(origins);
        }
        notifyMutation();
    }

    private static Path canonicalOrNormalized(Path path) {
        try {
            return path.toFile().getCanonicalFile().toPath().normalize();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String relativeKeyUnder(Path candidate, Path baseNorm, Path baseCanon) {
        Path norm = candidate.toAbsolutePath().normalize();
        Path canon = canonicalOrNormalized(norm);
        if (norm.equals(baseNorm) || norm.equals(baseCanon) || canon.equals(baseCanon)) {
            return ".";
        }
        Path rel = null;
        if (norm.startsWith(baseNorm)) {
            rel = baseNorm.relativize(norm);
        } else if (norm.startsWith(baseCanon)) {
            rel = baseCanon.relativize(norm);
        } else if (canon.startsWith(baseCanon)) {
            rel = baseCanon.relativize(canon);
        }
        if (rel == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Path part : rel) {
            if (sb.length() > 0) sb.append('/');
            sb.append(part.toString());
        }
        return sb.toString();
    }

    /**
     * 把音乐库里的旧归档移入回收站。manifest 记录原绝对路径，还原时搬回音乐库而不是隔离目录。
     * 与隔离副本不同，这里忽略「保留 0 天即直接删除」：提交失败时还要靠它回滚。
     */
    public Path moveOutputToTrash(Path original, String jobId) throws IOException {
        Path root = trashRoot();
        Files.createDirectories(root);
        String safeName = original.getFileName().toString().replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        long trashedAt = System.currentTimeMillis();
        Path target = root.resolve(trashedAt + "_" + TRASH_SEQ.getAndIncrement() + "_" + jobId + "_" + TRASH_KIND_OUTPUT + "_" + safeName);
        moveAcrossDevices(original, target);

        JsonObject manifest = new JsonObject();
        manifest.addProperty("kind", TRASH_KIND_OUTPUT);
        manifest.addProperty("originalPath", original.toAbsolutePath().toString());
        manifest.addProperty("relativePath", displayOutputPath(original));
        manifest.addProperty("trashedAt", trashedAt);
        Files.writeString(target.resolveSibling(target.getFileName() + ".json"), gson.toJson(manifest));
        return target;
    }

    public String displayOutputPath(Path path) {
        try {
            return Path.of(config.getOutputDirectory()).toAbsolutePath().normalize()
                .relativize(path.toAbsolutePath().normalize()).toString();
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }

    public void cleanupExpiredTrash() {
        int retention = config.getRecoveryTrashRetentionDays();
        if (retention < 0) return;
        Path root = trashRoot();
        if (!Files.isDirectory(root)) return;
        long cutoff = System.currentTimeMillis() - retention * 86_400_000L;
        try (var stream = Files.list(root)) {
            for (Path path : stream.toList()) {
                String fileName = path.getFileName().toString();
                if (fileName.endsWith(".json") || fileName.startsWith(PENDING_REPLACE_PREFIX)) continue;
                if (trashedAt(path) < cutoff) {
                    deleteRecursively(path);
                    Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".json"));
                }
            }
        } catch (Exception e) {
            log.warn("清理恢复回收站失败: {}", e.getMessage());
        }
    }

    /**
     * 入站时间取自 manifest（不能用 lastModifiedTime：Files.move 会保留原始 mtime）。
     */
    long trashedAt(Path entry) {
        Path manifest = entry.resolveSibling(entry.getFileName() + ".json");
        try {
            if (Files.isRegularFile(manifest)) {
                JsonObject json = gson.fromJson(Files.readString(manifest), JsonObject.class);
                if (json != null && json.has("trashedAt")) return json.get("trashedAt").getAsLong();
            }
        } catch (Exception ignored) {
        }
        String name = entry.getFileName().toString();
        int separator = name.indexOf('_');
        if (separator > 0) {
            try {
                return Long.parseLong(name.substring(0, separator));
            } catch (NumberFormatException ignored) {
            }
        }
        return Long.MAX_VALUE;
    }

    /**
     * 跨挂载点的移动兜底：同设备直接 rename，跨设备先完整复制到暂存再删除源，避免半途失败丢数据。
     */
    public void moveAcrossDevices(Path source, Path target) throws IOException {
        try (var libraryWrite = LibraryWriteGuard.acquire()) {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(target.toString());
            }
            try {
                Files.move(source, target);
                return;
            } catch (FileAlreadyExistsException exists) {
                throw exists;
            } catch (IOException crossDevice) {
                log.debug("跨设备移动，改为复制后删除: {} -> {}", source, target);
            }
            if (Files.isDirectory(source)) {
                Path parent = target.getParent();
                if (parent != null) Files.createDirectories(parent);
                Path stagingCopy = Files.createTempDirectory(parent != null ? parent : Path.of("."), ".move-copy-");
                try {
                    int[] counts = fileSystemUtils.copyDirectoryRecursively(source, stagingCopy);
                    if (counts[1] > 0 || listFiles(stagingCopy).size() != listFiles(source).size()) {
                        throw new IOException("recovery.move.copy.incomplete");
                    }
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw new FileAlreadyExistsException(target.toString());
                    }
                    Files.move(stagingCopy, target);
                } catch (IOException e) {
                    try { deleteRecursively(stagingCopy); } catch (IOException ignored) {}
                    throw e;
                }
            } else {
                Files.createDirectories(target.getParent());
                Files.copy(source, target);
                if (Files.size(target) != Files.size(source)) {
                    Files.deleteIfExists(target);
                    throw new IOException("recovery.move.copy.incomplete");
                }
            }
            deleteRecursively(source);
        }
    }

    public static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    static List<Path> listFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private void notifyMutation() {
        if (onMutation != null) {
            onMutation.run();
        }
    }
}
