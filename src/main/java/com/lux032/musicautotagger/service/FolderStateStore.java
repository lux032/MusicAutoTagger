package com.lux032.musicautotagger.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 文件夹级状态锁条带化与活跃时间追踪器（从 FolderAlbumCache 抽离）。
 * 1. 用固定大小的分段锁数组替代无界 ConcurrentHashMap&lt;String, Object&gt;，杜绝长时间运行后的锁对象内存泄漏。
 * 2. 追踪各文件夹最近活跃时间，支持周期性安全驱逐空闲文件夹缓存（跳过仍有待处理文件或人工锁定的目录）。
 */
public class FolderStateStore {

    public static final int DEFAULT_LOCK_STRIPES = 256;

    private final Object[] lockStripes;
    private final Map<String, Long> lastTouchedAt = new ConcurrentHashMap<>();

    public FolderStateStore() {
        this(DEFAULT_LOCK_STRIPES);
    }

    public FolderStateStore(int stripeCount) {
        int count = Math.max(16, stripeCount);
        this.lockStripes = new Object[count];
        for (int i = 0; i < count; i++) {
            this.lockStripes[i] = new Object();
        }
    }

    /**
     * 获取该文件夹对应的稳定互斥锁（条带化固定上限，永不随文件夹数量增长而泄漏内存）。
     */
    public Object lockFor(String folderPath) {
        String key = folderPath == null ? "" : folderPath;
        int idx = (key.hashCode() & 0x7fffffff) % lockStripes.length;
        return lockStripes[idx];
    }

    public int stripeCount() {
        return lockStripes.length;
    }

    public void touch(String folderPath) {
        if (folderPath != null && !folderPath.isEmpty()) {
            lastTouchedAt.put(folderPath, System.currentTimeMillis());
        }
    }

    public void removeTracking(String folderPath) {
        if (folderPath != null) {
            lastTouchedAt.remove(folderPath);
        }
    }

    public int trackedFolderCount() {
        return lastTouchedAt.size();
    }

    /**
     * 驱逐超过 {@code maxIdleMs} 未访问且不在保护集合中的空闲文件夹状态。
     *
     * @return 实际驱逐的文件夹数量
     */
    public int evictIdleFolders(long maxIdleMs, Set<String> protectedFolders, Consumer<String> clearAction) {
        long cutoff = System.currentTimeMillis() - Math.max(0L, maxIdleMs);
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, Long> entry : lastTouchedAt.entrySet()) {
            String folder = entry.getKey();
            if (protectedFolders != null && protectedFolders.contains(folder)) {
                continue;
            }
            if (entry.getValue() <= cutoff) {
                candidates.add(folder);
            }
        }
        int evicted = 0;
        for (String folder : candidates) {
            synchronized (lockFor(folder)) {
                Long touched = lastTouchedAt.get(folder);
                if (touched != null && touched <= cutoff
                    && (protectedFolders == null || !protectedFolders.contains(folder))) {
                    clearAction.accept(folder);
                    lastTouchedAt.remove(folder);
                    evicted++;
                }
            }
        }
        return evicted;
    }
}
