package com.lux032.musicautotagger.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.lux032.musicautotagger.model.MbContributionItem;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 待回馈 MusicBrainz 条目的原子 JSON 存储与高画质原图备份管理器。
 *
 * <p>数据位于 {@code reviewQueuePath} 同级目录的 {@code mb-contributions.json} 与
 * {@code mb-contribution-covers/}，在 SQLite、MySQL 与文件模式下均零迁移开箱即用。
 */
@Slf4j
public class MbContributionStore {

    public static final long ORPHAN_BACKUP_TTL_MILLIS = 30L * 86_400_000L;
    private static final Type LIST_TYPE = new TypeToken<List<MbContributionItem>>() { }.getType();
    private static final Pattern OWNED_COVER_FILENAME =
        Pattern.compile("^[A-Za-z0-9_-]{1,80}\\.(?:jpg|jpeg|png|webp|gif|bmp)$");

    private final Path storeFile;
    private final Path coverBackupDir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, MbContributionItem> items = new LinkedHashMap<>();

    public MbContributionStore(Path storeFile, Path coverBackupDir) {
        this.storeFile = storeFile.toAbsolutePath().normalize();
        this.coverBackupDir = coverBackupDir.toAbsolutePath().normalize();
        loadFromDisk();
        cleanupOrphanCoverBackups(System.currentTimeMillis());
    }

    public static MbContributionStore forReviewQueuePath(String reviewQueuePath) {
        Path base = (reviewQueuePath == null || reviewQueuePath.isBlank())
            ? Path.of("data", "review-queue.json")
            : Path.of(reviewQueuePath.trim());
        Path absBase = base.toAbsolutePath().normalize();
        return new MbContributionStore(
            absBase.resolveSibling("mb-contributions.json"),
            absBase.resolveSibling("mb-contribution-covers"));
    }

    public Path getStoreFile() {
        return storeFile;
    }

    public Path getCoverBackupDir() {
        return coverBackupDir;
    }

    public synchronized MbContributionItem get(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return items.get(id.trim());
    }

    public synchronized Optional<MbContributionItem> findByReviewItemId(String reviewItemId) {
        if (reviewItemId == null || reviewItemId.isBlank()) {
            return Optional.empty();
        }
        String key = reviewItemId.trim();
        for (MbContributionItem item : items.values()) {
            if (key.equals(item.getReviewItemId())) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<MbContributionItem> findByArchivedOutputDir(String archivedOutputDir) {
        String norm = normalizePathKey(archivedOutputDir);
        if (norm == null) {
            return Optional.empty();
        }
        for (MbContributionItem item : items.values()) {
            if (norm.equals(normalizePathKey(item.getArchivedOutputDir()))) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    public synchronized List<MbContributionItem> list(MbContributionItem.Status statusFilter, boolean actionableOnly) {
        List<MbContributionItem> result = new ArrayList<>();
        for (MbContributionItem item : items.values()) {
            if (statusFilter != null && item.getStatus() != statusFilter) {
                continue;
            }
            if (statusFilter == null && actionableOnly && !item.isActionable()) {
                continue;
            }
            result.add(item);
        }
        result.sort(Comparator.comparingLong(MbContributionItem::getUpdatedAt).reversed()
            .thenComparingLong(MbContributionItem::getCreatedAt).reversed());
        return result;
    }

    public synchronized Map<String, Object> stats() {
        int checking = 0;
        int readyToSeed = 0;
        int existingMatch = 0;
        int linked = 0;
        int completed = 0;
        int dismissed = 0;
        int checkFailed = 0;
        for (MbContributionItem item : items.values()) {
            if (item == null || item.getStatus() == null) continue;
            switch (item.getStatus()) {
                case CHECKING -> checking++;
                case READY_TO_SEED -> readyToSeed++;
                case EXISTING_MATCH -> existingMatch++;
                case LINKED -> linked++;
                case COMPLETED -> completed++;
                case DISMISSED -> dismissed++;
                case CHECK_FAILED -> checkFailed++;
            }
        }
        int actionable = checking + readyToSeed + existingMatch + linked + checkFailed;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("checking", checking);
        map.put("readyToSeed", readyToSeed);
        map.put("existingMatch", existingMatch);
        map.put("linked", linked);
        map.put("completed", completed);
        map.put("dismissed", dismissed);
        map.put("checkFailed", checkFailed);
        map.put("actionable", actionable);
        map.put("total", items.size());
        return map;
    }

    /**
     * 新增或按 {@code reviewItemId} / {@code archivedOutputDir} 去重更新条目；
     * 严格遵循「先持久化快照，成功后再发布内存状态与清理旧备份」。
     */
    public synchronized MbContributionItem upsert(MbContributionItem incoming) {
        if (incoming == null) {
            throw new IllegalArgumentException("contribution item must not be null");
        }
        long now = System.currentTimeMillis();
        MbContributionItem existing = null;
        if (incoming.getId() != null && !incoming.getId().isBlank()) {
            existing = items.get(incoming.getId().trim());
        }
        if (existing == null && incoming.getReviewItemId() != null && !incoming.getReviewItemId().isBlank()) {
            existing = findByReviewItemId(incoming.getReviewItemId()).orElse(null);
        }
        if (existing == null && incoming.getArchivedOutputDir() != null && !incoming.getArchivedOutputDir().isBlank()) {
            existing = findByArchivedOutputDir(incoming.getArchivedOutputDir()).orElse(null);
        }

        MbContributionItem candidate = deepCopy(incoming);
        String backupToDeleteAfterCommit = null;
        if (existing != null) {
            candidate.setId(existing.getId());
            if (candidate.getCreatedAt() <= 0) {
                candidate.setCreatedAt(existing.getCreatedAt() > 0 ? existing.getCreatedAt() : now);
            }
            candidate.setRevision(Math.max(1L, existing.getRevision()) + 1L);
            String oldBackup = existing.getCover() == null ? null : existing.getCover().getLocalBackupFileName();
            String newBackup = candidate.getCover() == null ? null : candidate.getCover().getLocalBackupFileName();
            if (oldBackup != null && !oldBackup.equals(newBackup)) {
                backupToDeleteAfterCommit = oldBackup;
            }
        } else {
            if (candidate.getId() == null || candidate.getId().isBlank()) {
                candidate.setId(UUID.randomUUID().toString());
            }
            if (candidate.getCreatedAt() <= 0) {
                candidate.setCreatedAt(now);
            }
            candidate.setRevision(Math.max(1L, candidate.getRevision()));
        }
        candidate.setUpdatedAt(now);
        candidate.ensureThreadSafeCollections();

        String terminalBackupToDelete = null;
        if (candidate.isTerminal() && candidate.getCover() != null && candidate.getCover().getLocalBackupFileName() != null) {
            terminalBackupToDelete = candidate.getCover().getLocalBackupFileName();
            candidate.getCover().setLocalBackupFileName(null);
        }

        Map<String, MbContributionItem> nextMap = new LinkedHashMap<>(items);
        nextMap.put(candidate.getId(), candidate);
        persistSnapshotOrThrow(nextMap);

        // 持久化成功后再提交内存并清理旧/终态封面备份
        items.put(candidate.getId(), candidate);
        if (backupToDeleteAfterCommit != null) {
            deleteCoverBackupQuietly(backupToDeleteAfterCommit);
        }
        if (terminalBackupToDelete != null) {
            deleteCoverBackupQuietly(terminalBackupToDelete);
        }
        return candidate;
    }

    /**
     * 在存储锁内原子更新已有条目：在克隆副本上执行变更，先持久化到磁盘，成功后再提交内存与清理备份。
     */
    public synchronized Optional<MbContributionItem> update(String id, java.util.function.Consumer<MbContributionItem> mutator) {
        MbContributionItem current = get(id);
        if (current == null) {
            return Optional.empty();
        }
        MbContributionItem candidate = deepCopy(current);
        String previousBackup = candidate.getCover() == null ? null : candidate.getCover().getLocalBackupFileName();
        mutator.accept(candidate);
        candidate.setRevision(Math.max(1L, current.getRevision()) + 1L);
        candidate.setUpdatedAt(System.currentTimeMillis());
        candidate.ensureThreadSafeCollections();

        String currentBackup = candidate.getCover() == null ? null : candidate.getCover().getLocalBackupFileName();
        String replacedBackup = (previousBackup != null && !previousBackup.equals(currentBackup)) ? previousBackup : null;
        String terminalBackup = null;
        if (candidate.isTerminal() && candidate.getCover() != null && candidate.getCover().getLocalBackupFileName() != null) {
            terminalBackup = candidate.getCover().getLocalBackupFileName();
            candidate.getCover().setLocalBackupFileName(null);
        }

        Map<String, MbContributionItem> nextMap = new LinkedHashMap<>(items);
        nextMap.put(candidate.getId(), candidate);
        persistSnapshotOrThrow(nextMap);

        items.put(candidate.getId(), candidate);
        if (replacedBackup != null) {
            deleteCoverBackupQuietly(replacedBackup);
        }
        if (terminalBackup != null) {
            deleteCoverBackupQuietly(terminalBackup);
        }
        return Optional.of(candidate);
    }

    /**
     * 将合法未压缩原图字节写入 {@code mb-contribution-covers/<id>.<ext>}，返回安全纯文件名与探测元信息。
     */
    public synchronized Optional<SavedCoverBackup> saveCoverBackup(String contributionId, byte[] rawBytes) {
        if (contributionId == null || !isSafeId(contributionId)
                || rawBytes == null || rawBytes.length == 0 || rawBytes.length > ImageCompressor.MAX_INPUT_BYTES) {
            return Optional.empty();
        }
        Optional<UntrustedImageFetcher.Result> inspected = UntrustedImageFetcher.inspect(rawBytes);
        String mime;
        int width = 0;
        int height = 0;
        if (inspected.isPresent()) {
            mime = inspected.get().mime();
            width = inspected.get().width();
            height = inspected.get().height();
        } else {
            String demuxer = ImageCompressor.detectSafeFfmpegDemuxer(rawBytes);
            if (demuxer == null) {
                return Optional.empty();
            }
            mime = mimeForDemuxer(demuxer);
        }
        String ext = extensionForMime(mime);
        String fileName = contributionId + "." + ext;
        Optional<Path> rootOpt = resolveVerifiedCoverRoot(true);
        if (rootOpt.isEmpty()) {
            log.warn("拒绝向符号链接或非法封面备份目录写入原图: {}", coverBackupDir);
            return Optional.empty();
        }
        Path target = resolveSafeCoverPathInRoot(rootOpt.get(), fileName, false).orElse(null);
        if (target == null) {
            return Optional.empty();
        }
        try {
            writeAtomically(target, rawBytes);
            return Optional.of(new SavedCoverBackup(fileName, mime, width, height, rawBytes.length));
        } catch (IOException e) {
            log.warn("保存 CAA 原图备份失败 ({}): {}", fileName, e.getMessage());
            return Optional.empty();
        }
    }

    public record SavedCoverBackup(String fileName, String mime, int width, int height, long bytes) { }

    /**
     * 严格解析备份原图路径：拒绝路径穿越、非本存储命名格式、目录或文件符号链接逃逸。
     */
    public Optional<Path> resolveSafeCoverPath(String fileName) {
        if (fileName == null || !isOwnedCoverFileName(fileName.trim())) {
            return Optional.empty();
        }
        if (Files.isSymbolicLink(coverBackupDir)) {
            return Optional.empty();
        }
        if (Files.exists(coverBackupDir, LinkOption.NOFOLLOW_LINKS)) {
            Optional<Path> root = resolveVerifiedCoverRoot(false);
            return root.flatMap(r -> resolveSafeCoverPathInRoot(r, fileName.trim(), false));
        }
        // 目录尚未创建时仅做词法校验，并确认路径上无符号链接
        Path candidate = coverBackupDir.resolve(fileName.trim()).toAbsolutePath().normalize();
        if (!candidate.getParent().equals(coverBackupDir) || Files.isSymbolicLink(candidate)) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    public Optional<Path> findExistingCoverBackup(MbContributionItem item) {
        if (item == null || item.getCover() == null || item.getCover().getLocalBackupFileName() == null) {
            return Optional.empty();
        }
        Optional<Path> root = resolveVerifiedCoverRoot(false);
        if (root.isEmpty()) {
            return Optional.empty();
        }
        return resolveSafeCoverPathInRoot(root.get(), item.getCover().getLocalBackupFileName().trim(), true);
    }

    public void deleteCoverBackupQuietly(String fileName) {
        if (fileName == null || !isOwnedCoverFileName(fileName.trim())) {
            return;
        }
        Optional<Path> root = resolveVerifiedCoverRoot(false);
        if (root.isEmpty()) {
            return;
        }
        resolveSafeCoverPathInRoot(root.get(), fileName.trim(), true).ifPresent(path -> {
            try {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException e) {
                log.debug("清理 CAA 原图备份失败 ({}): {}", path.getFileName(), e.getMessage());
            }
        });
    }

    /** 清理不再被任何非终态条目引用且超过 30 天的本存储孤儿原图备份文件（严禁跟随符号链接或删除非本存储文件）。 */
    public synchronized int cleanupOrphanCoverBackups(long nowMillis) {
        Optional<Path> realRootOpt = resolveVerifiedCoverRoot(false);
        if (realRootOpt.isEmpty()) {
            return 0;
        }
        Path realRoot = realRootOpt.get();
        Set<String> activeNames = new HashSet<>();
        for (MbContributionItem item : items.values()) {
            if (item != null && !item.isTerminal() && item.getCover() != null
                    && item.getCover().getLocalBackupFileName() != null) {
                activeNames.add(item.getCover().getLocalBackupFileName());
            }
        }
        int deleted = 0;
        try (Stream<Path> stream = Files.list(realRoot)) {
            for (Path p : stream.toList()) {
                String name = p.getFileName().toString();
                if (!isOwnedCoverFileName(name) || activeNames.contains(name)) {
                    continue;
                }
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    Path realFile = p.toRealPath();
                    if (!realRoot.equals(realFile.getParent())) {
                        continue;
                    }
                    FileTime mtime = Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS);
                    if (nowMillis - mtime.toMillis() >= ORPHAN_BACKUP_TTL_MILLIS) {
                        if (Files.deleteIfExists(p)) {
                            deleted++;
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            log.debug("扫描 CAA 孤儿原图备份失败: {}", e.getMessage());
        }
        if (deleted > 0) {
            log.info("已清理 {} 个过期的 CAA 孤儿封面备份", deleted);
        }
        return deleted;
    }

    private Optional<Path> resolveVerifiedCoverRoot(boolean createIfMissing) {
        try {
            if (Files.isSymbolicLink(coverBackupDir)) {
                return Optional.empty();
            }
            if (!Files.exists(coverBackupDir, LinkOption.NOFOLLOW_LINKS)) {
                if (!createIfMissing) {
                    return Optional.empty();
                }
                Path parent = coverBackupDir.getParent();
                if (parent != null && Files.isSymbolicLink(parent)) {
                    return Optional.empty();
                }
                Files.createDirectories(coverBackupDir);
            }
            if (Files.isSymbolicLink(coverBackupDir)
                    || !Files.isDirectory(coverBackupDir, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            Path realRoot = coverBackupDir.toRealPath();
            if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            return Optional.of(realRoot);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<Path> resolveSafeCoverPathInRoot(Path realRoot, String fileName, boolean mustExist) {
        if (!isOwnedCoverFileName(fileName)) {
            return Optional.empty();
        }
        Path candidate = realRoot.resolve(fileName).normalize();
        if (!realRoot.equals(candidate.getParent())) {
            return Optional.empty();
        }
        if (Files.isSymbolicLink(candidate)) {
            return Optional.empty();
        }
        if (mustExist) {
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            try {
                Path realCandidate = candidate.toRealPath();
                if (!realRoot.equals(realCandidate.getParent())) {
                    return Optional.empty();
                }
                return Optional.of(realCandidate);
            } catch (IOException e) {
                return Optional.empty();
            }
        }
        return Optional.of(candidate);
    }

    private MbContributionItem deepCopy(MbContributionItem src) {
        MbContributionItem copy = gson.fromJson(gson.toJson(src), MbContributionItem.class);
        if (copy != null) {
            copy.ensureThreadSafeCollections();
        }
        return copy;
    }

    private void loadFromDisk() {
        if (!Files.isRegularFile(storeFile)) {
            return;
        }
        try {
            String json = Files.readString(storeFile, StandardCharsets.UTF_8);
            if (json == null || json.isBlank()) {
                return;
            }
            List<MbContributionItem> loaded = gson.fromJson(json, LIST_TYPE);
            if (loaded == null) {
                return;
            }
            for (MbContributionItem item : loaded) {
                if (item == null || item.getId() == null || item.getId().isBlank()) {
                    continue;
                }
                if (item.getRevision() <= 0) {
                    item.setRevision(1L);
                }
                item.ensureThreadSafeCollections();
                items.put(item.getId(), item);
            }
            log.info("已加载 MusicBrainz 回馈队列: {} 条（来源 {}）", items.size(), storeFile.getFileName());
        } catch (Exception e) {
            log.error("加载 MusicBrainz 回馈队列失败 ({}): {}", storeFile, e.getMessage());
        }
    }

    private void persistSnapshotOrThrow(Map<String, MbContributionItem> snapshot) {
        try {
            Path parent = storeFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] bytes = gson.toJson(new ArrayList<>(snapshot.values())).getBytes(StandardCharsets.UTF_8);
            writeAtomically(storeFile, bytes);
        } catch (IOException e) {
            log.error("保存 MusicBrainz 回馈队列失败 ({}): {}", storeFile, e.getMessage());
            throw new UncheckedIOException("Failed to persist mb-contributions store", e);
        }
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempFile(parent, ".mb-contrib-", ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private static boolean isSafeId(String id) {
        return id != null && id.matches("^[A-Za-z0-9_-]{1,80}$");
    }

    private static boolean isOwnedCoverFileName(String fileName) {
        return fileName != null && !fileName.contains("..") && !fileName.contains("/") && !fileName.contains("\\")
            && OWNED_COVER_FILENAME.matcher(fileName).matches();
    }

    private static String normalizePathKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Path.of(raw.trim()).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return raw.trim();
        }
    }

    private static String mimeForDemuxer(String demuxer) {
        return switch (demuxer.toLowerCase(Locale.ROOT)) {
            case "png_pipe" -> "image/png";
            case "webp_pipe" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp_pipe" -> "image/bmp";
            default -> "image/jpeg";
        };
    }

    private static String extensionForMime(String mime) {
        if (mime == null) return "jpg";
        return switch (mime.toLowerCase(Locale.ROOT)) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/bmp" -> "bmp";
            default -> "jpg";
        };
    }
}
