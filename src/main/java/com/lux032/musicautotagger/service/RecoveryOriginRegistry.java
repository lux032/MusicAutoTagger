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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 持久化记录「隔离恢复副本（failed / partial / 单文件恢复工作区） -> 监控目录原始源文件」的血缘映射。
 *
 * <p>隔离副本经联网搜索、AI 辅助入库、重新识别、缺封面补图或待确认归档成功后，
 * 恢复流程据此把最终归档结果回写到原监控目录处理记录，保持单条主记录语义，
 * 避免处理记录同时残留监控源 FAILED 行与输出库 SUCCESS 行。
 */
@Slf4j
public class RecoveryOriginRegistry {

    private static final Object LOCK = new Object();
    private static final java.lang.reflect.Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Entry>>() { }.getType();

    private final Path storeFile;
    private final Gson gson = new Gson();

    public RecoveryOriginRegistry(Path storeFile) {
        this.storeFile = storeFile;
    }

    public static RecoveryOriginRegistry forReviewQueuePath(String reviewQueuePath) {
        Path base = reviewQueuePath == null || reviewQueuePath.isBlank()
            ? Path.of("data", "review-queue.json") : Path.of(reviewQueuePath);
        return new RecoveryOriginRegistry(base.toAbsolutePath().resolveSibling("recovery-origins.json"));
    }

    public static class Entry {
        private String originPath;

        public Entry() { }

        private Entry(String originPath) {
            this.originPath = originPath;
        }

        public String getOriginPath() {
            return originPath;
        }
    }

    public void record(String isolatedPath, String originPath) {
        if (isolatedPath == null || isolatedPath.isBlank() || originPath == null || originPath.isBlank()) {
            return;
        }
        recordAll(Map.of(isolatedPath, originPath));
    }

    /** 批量登记映射（仅执行一次 JSON 读 + 写，避免整张专辑复制引发写放大）。 */
    public void recordAll(Map<String, String> isolatedToOrigin) {
        recordAllChecked(isolatedToOrigin);
    }

    /** 批量登记映射并返回是否持久化成功（供回收站还原在删除 manifest 前确认落盘）。 */
    public boolean recordAllChecked(Map<String, String> isolatedToOrigin) {
        if (isolatedToOrigin == null || isolatedToOrigin.isEmpty()) {
            return true;
        }
        Map<String, Entry> valid = new LinkedHashMap<>();
        for (Map.Entry<String, String> item : isolatedToOrigin.entrySet()) {
            String isolated = item.getKey();
            String origin = item.getValue();
            if (isolated == null || isolated.isBlank() || origin == null || origin.isBlank()) {
                continue;
            }
            valid.put(normalizePathKey(isolated), new Entry(normalizePathKey(origin)));
        }
        if (valid.isEmpty()) {
            return true;
        }
        synchronized (LOCK) {
            Optional<Map<String, Entry>> loaded = loadOrEmpty();
            if (loaded.isEmpty()) {
                log.error("隔离副本血缘记录文件读取异常，拒绝批量覆写以防丢失已有记录: {}", storeFile);
                return false;
            }
            Map<String, Entry> entries = loaded.get();
            entries.putAll(valid);
            return save(entries);
        }
    }

    /**
     * 登记单个隔离副本的监控来源：若 {@code source} 自身已登记血缘则沿链透传，
     * 否则仅当 {@code source} 严格位于 {@code monitorRoot} 内时登记，绝不把 {@code reprocess-*} 暂存路径当成监控源。
     */
    public void recordFromMonitor(Path copy, Path source, Path monitorRoot) {
        if (copy == null || source == null) {
            return;
        }
        Map<Path, Path> single = new LinkedHashMap<>();
        single.put(copy, source);
        recordAllFromMonitor(single, monitorRoot);
    }

    /** 批量登记来自监控目录（或已有血缘链透传）的隔离副本映射。 */
    public void recordAllFromMonitor(Map<Path, Path> copyToSource, Path monitorRoot) {
        if (copyToSource == null || copyToSource.isEmpty()) {
            return;
        }
        Path normalizedMonitorRoot = monitorRoot == null ? null : monitorRoot.toAbsolutePath().normalize();
        Path canonicalMonitorRoot = monitorRoot == null ? null : canonicalPathOrNull(monitorRoot);
        synchronized (LOCK) {
            Optional<Map<String, Entry>> loaded = loadOrEmpty();
            if (loaded.isEmpty()) {
                log.error("隔离副本血缘记录文件读取异常，拒绝批量覆写以防丢失已有记录: {}", storeFile);
                return;
            }
            Map<String, Entry> entries = loaded.get();
            boolean changed = false;
            for (Map.Entry<Path, Path> item : copyToSource.entrySet()) {
                Path copy = item.getKey();
                Path source = item.getValue();
                if (copy == null || source == null) {
                    continue;
                }
                String copyKey = normalizePathKey(copy.toString());
                Entry chained = findEntry(entries, source.toFile());
                if (chained != null && chained.originPath != null && !chained.originPath.isBlank()) {
                    entries.put(copyKey, new Entry(chained.originPath));
                    changed = true;
                } else if (isStrictlyInsideMonitor(source, normalizedMonitorRoot, canonicalMonitorRoot)) {
                    entries.put(copyKey, new Entry(normalizePathKey(source.toString())));
                    changed = true;
                }
            }
            if (changed) {
                save(entries);
            }
        }
    }

    /** 把已有隔离副本的血缘别名透传给临时副本（例如单文件重识别创建的 single-<jobId> 工作副本）。 */
    public void recordAlias(Path original, Path copy) {
        if (original == null || copy == null) {
            return;
        }
        synchronized (LOCK) {
            Optional<Map<String, Entry>> loaded = loadOrEmpty();
            if (loaded.isEmpty()) {
                log.error("隔离副本血缘记录文件读取异常，跳过别名登记以防覆写清空: {}", storeFile);
                return;
            }
            Map<String, Entry> entries = loaded.get();
            Entry match = findEntry(entries, original.toFile());
            String copyKey = normalizePathKey(copy.toString());
            if (match != null && match.originPath != null && !match.originPath.isBlank()) {
                entries.put(copyKey, new Entry(match.originPath));
                save(entries);
            } else if (entries.remove(copyKey) != null) {
                save(entries);
            }
        }
    }

    public Optional<Path> findOrigin(File isolatedFile) {
        if (isolatedFile == null) {
            return Optional.empty();
        }
        synchronized (LOCK) {
            Optional<Map<String, Entry>> loaded = loadOrEmpty();
            if (loaded.isEmpty()) {
                return Optional.empty();
            }
            Entry match = findEntry(loaded.get(), isolatedFile);
            if (match == null || match.originPath == null || match.originPath.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(Path.of(match.originPath));
        }
    }

    public Optional<Path> findOrigin(Path isolatedPath) {
        return isolatedPath == null ? Optional.empty() : findOrigin(isolatedPath.toFile());
    }

    /** 查询等于或严格位于 {@code rootPath} 之下的全部血缘映射（供回收站入站打包使用）。 */
    public Map<Path, Path> findOriginsUnder(Path rootPath) {
        Map<Path, Path> result = new LinkedHashMap<>();
        if (rootPath == null) {
            return result;
        }
        Path normalizedRoot = rootPath.toAbsolutePath().normalize();
        Path canonicalRoot = canonicalPathOrNull(rootPath);
        synchronized (LOCK) {
            Optional<Map<String, Entry>> loaded = loadOrEmpty();
            if (loaded.isEmpty()) {
                return result;
            }
            for (Map.Entry<String, Entry> item : loaded.get().entrySet()) {
                Entry entry = item.getValue();
                if (item.getKey() == null || entry == null || entry.originPath == null || entry.originPath.isBlank()) {
                    continue;
                }
                Path keyPath = Path.of(item.getKey()).toAbsolutePath().normalize();
                if (matchesRootOrChild(keyPath, normalizedRoot, canonicalRoot)) {
                    result.put(keyPath, Path.of(entry.originPath));
                }
            }
        }
        return result;
    }

    public void remove(File isolatedFile) {
        if (isolatedFile == null) {
            return;
        }
        removeAll(java.util.List.of(isolatedFile.toPath()));
    }

    public void remove(Path isolatedPath) {
        if (isolatedPath == null) {
            return;
        }
        removeAll(java.util.List.of(isolatedPath));
    }

    public void removeAll(Collection<Path> isolatedPaths) {
        if (isolatedPaths == null || isolatedPaths.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            Optional<Map<String, Entry>> loaded = loadOrEmpty();
            if (loaded.isEmpty()) {
                log.error("隔离副本血缘记录文件读取异常，跳过删除操作以防覆写清空: {}", storeFile);
                return;
            }
            Map<String, Entry> entries = loaded.get();
            boolean changed = false;
            for (Path path : isolatedPaths) {
                if (path == null) continue;
                if (entries.remove(normalizePathKey(path.toString())) != null) {
                    changed = true;
                }
                String canonical = canonicalOrNull(path.toFile());
                if (canonical != null && entries.remove(canonical) != null) {
                    changed = true;
                }
            }
            if (changed) {
                save(entries);
            }
        }
    }

    /**
     * 仅移除键与值均与快照一致的血缘映射，避免并发新副本复用相同隔离路径时被旧清理误删。
     */
    public void removeMatching(Map<Path, Path> expectedSnapshot) {
        if (expectedSnapshot == null || expectedSnapshot.isEmpty()) {
            return;
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (Map.Entry<Path, Path> e : expectedSnapshot.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            expected.put(normalizePathKey(e.getKey().toString()), normalizePathKey(e.getValue().toString()));
        }
        if (expected.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            Optional<Map<String, Entry>> loaded = loadOrEmpty();
            if (loaded.isEmpty()) {
                log.error("隔离副本血缘记录文件读取异常，跳过快照匹配删除以防覆写清空: {}", storeFile);
                return;
            }
            Map<String, Entry> entries = loaded.get();
            boolean changed = entries.entrySet().removeIf(item -> {
                if (item.getKey() == null || item.getValue() == null || item.getValue().originPath == null) {
                    return false;
                }
                String key = normalizePathKey(item.getKey());
                String wantedOrigin = expected.get(key);
                return wantedOrigin != null && wantedOrigin.equals(normalizePathKey(item.getValue().originPath));
            });
            if (changed) {
                save(entries);
            }
        }
    }

    /** 清理等于或严格位于 {@code rootPath} 之下的全部血缘映射（文件入回收站或临时目录销毁时调用）。 */
    public void removeUnder(Path rootPath) {
        if (rootPath == null) {
            return;
        }
        Path normalizedRoot = rootPath.toAbsolutePath().normalize();
        Path canonicalRoot = canonicalPathOrNull(rootPath);
        synchronized (LOCK) {
            Optional<Map<String, Entry>> loaded = loadOrEmpty();
            if (loaded.isEmpty()) {
                log.error("隔离副本血缘记录文件读取异常，跳过目录清理以防覆写清空: {}", storeFile);
                return;
            }
            Map<String, Entry> entries = loaded.get();
            boolean changed = entries.entrySet().removeIf(item -> {
                if (item.getKey() == null) return false;
                Path keyPath = Path.of(item.getKey()).toAbsolutePath().normalize();
                return matchesRootOrChild(keyPath, normalizedRoot, canonicalRoot);
            });
            if (changed) {
                save(entries);
            }
        }
    }

    private static boolean isStrictlyInsideMonitor(Path source, Path normalizedMonitorRoot, Path canonicalMonitorRoot) {
        if (source == null || normalizedMonitorRoot == null) {
            return false;
        }
        Path normSource = source.toAbsolutePath().normalize();
        if (isInternalStagingPath(normSource)) {
            return false;
        }
        // 对于磁盘上已存在的路径，必须核验规范化后的真实路径也严格位于监控目录内，防止符号链接越界逃逸
        if (Files.exists(normSource) && canonicalMonitorRoot != null) {
            Path canonicalSource = canonicalPathOrNull(normSource);
            return canonicalSource != null
                && !isInternalStagingPath(canonicalSource)
                && canonicalSource.startsWith(canonicalMonitorRoot)
                && !canonicalSource.equals(canonicalMonitorRoot);
        }
        if (normSource.startsWith(normalizedMonitorRoot) && !normSource.equals(normalizedMonitorRoot)) {
            return true;
        }
        if (canonicalMonitorRoot != null) {
            Path canonicalSource = canonicalPathOrNull(normSource);
            if (canonicalSource != null && !isInternalStagingPath(canonicalSource)
                    && canonicalSource.startsWith(canonicalMonitorRoot)
                    && !canonicalSource.equals(canonicalMonitorRoot)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInternalStagingPath(Path path) {
        if (path == null) {
            return false;
        }
        String prev = null;
        for (Path segment : path) {
            String name = segment.toString();
            if (".recovery-work".equals(name)) {
                return true;
            }
            // 仅排除暂存目录下的 reprocess-*，不误杀监控目录中合法命名为 reprocess-* 的专辑文件夹
            if (name.startsWith("reprocess-") && prev != null
                    && (prev.equals("review-staging") || prev.equals("staging"))) {
                return true;
            }
            prev = name;
        }
        return false;
    }

    private static boolean matchesRootOrChild(Path candidate, Path normalizedRoot, Path canonicalRoot) {
        if (candidate.equals(normalizedRoot) || candidate.startsWith(normalizedRoot)) {
            return true;
        }
        if (canonicalRoot != null) {
            if (candidate.equals(canonicalRoot) || candidate.startsWith(canonicalRoot)) {
                return true;
            }
            Path canonicalCandidate = canonicalPathOrNull(candidate);
            if (canonicalCandidate != null
                    && (canonicalCandidate.equals(canonicalRoot) || canonicalCandidate.startsWith(canonicalRoot))) {
                return true;
            }
        }
        return false;
    }

    private static Entry findEntry(Map<String, Entry> entries, File file) {
        if (file == null) {
            return null;
        }
        Entry direct = entries.get(normalizePathKey(file.getAbsolutePath()));
        if (direct != null) {
            return direct;
        }
        String canonical = canonicalOrNull(file);
        return canonical == null ? null : entries.get(canonical);
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

    private static Path canonicalPathOrNull(Path path) {
        try {
            return path.toFile().getCanonicalFile().toPath().normalize();
        } catch (IOException e) {
            return null;
        }
    }

    private Optional<Map<String, Entry>> loadOrEmpty() {
        if (!Files.exists(storeFile)) {
            return Optional.of(new LinkedHashMap<>());
        }
        if (!Files.isRegularFile(storeFile)) {
            log.warn("隔离副本血缘记录路径不是普通文件，拒绝覆写: {}", storeFile);
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
            log.warn("读取或解析隔离副本血缘记录失败，拒绝清空覆写: {} - {}", storeFile, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean save(Map<String, Entry> entries) {
        Path normalized = storeFile.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)) {
            log.warn("隔离副本血缘记录路径为符号链接，拒绝覆写: {}", storeFile);
            return false;
        }
        Path parent = normalized.getParent();
        Path tmp = null;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            tmp = Files.createTempFile(parent != null ? parent : Path.of("."), ".recovery-origins-", ".tmp");
            Files.writeString(tmp, gson.toJson(entries, MAP_TYPE), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, normalized, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
            return true;
        } catch (IOException e) {
            log.warn("保存隔离副本血缘记录失败: {} - {}", storeFile, e.getMessage());
            return false;
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
