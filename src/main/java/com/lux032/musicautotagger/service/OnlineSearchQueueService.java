package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.web.SystemSuggestionCalculator;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 统一的 LLM 联网搜索后台队列。
 *
 * 收口三条原本割裂的搜索链路：
 *   1. 「重新识别」页触发的联网辅助识别（RECOVERY_MANUAL）
 *   2. 「待确认」页触发的联网增强搜索（REVIEW_MANUAL）
 *   3. 「锁定专辑缺曲」入队后的后台自动联网搜索（MISSING_TRACKS_AUTO）
 *
 * 调度特性：
 *   - 支持动态并发上限（1..3，读取 {@code config.getLlmWebSearchMaxConcurrency()}，默认 1 串行）
 *   - 手动任务（priority=1）优先于后台自动搜索（priority=2）；同优先级按入队顺序 FIFO
 *   - 显式区分 QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED，并实时上报阶段进度
 *   - 支持置顶优先、移出等待队列、中止运行中任务（若第 1 轮已落盘则保留第 1 轮结果）、一键重试
 *   - 已完成/失败/中止的历史任务仅内存保留最近 50 条
 */
@Slf4j
public class OnlineSearchQueueService implements AutoCloseable {

    public static final int MAX_HISTORY = 50;
    public static final int PRIORITY_MANUAL = 1;
    public static final int PRIORITY_AUTO = 2;

    public enum Origin {
        REVIEW_MANUAL,
        RECOVERY_MANUAL,
        MISSING_TRACKS_AUTO
    }

    public enum Status {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    private final MusicConfig config;
    private final ReviewQueueService reviewQueue;
    private final OnlineIdentificationService onlineIdentificationService;
    private final ExecutorService workers;
    private final Object lock = new Object();
    private final AtomicLong orderGenerator = new AtomicLong(1);
    private final AtomicInteger threadSeq = new AtomicInteger(1);

    private final List<SearchTask> queued = new ArrayList<>();
    private final List<SearchTask> running = new ArrayList<>();
    private final List<SearchTask> finished = new ArrayList<>();
    private final Map<String, SearchTask> tasksById = new LinkedHashMap<>();

    private volatile Supplier<List<Map<String, Object>>> recoveryOtherJobsSupplier;
    private volatile boolean closed;

    public OnlineSearchQueueService(MusicConfig config,
                                    ReviewQueueService reviewQueue,
                                    OnlineIdentificationService onlineIdentificationService) {
        this.config = config;
        this.reviewQueue = reviewQueue;
        this.onlineIdentificationService = onlineIdentificationService;
        this.workers = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "llm-search-worker-" + threadSeq.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    public void setRecoveryOtherJobsSupplier(Supplier<List<Map<String, Object>>> supplier) {
        this.recoveryOtherJobsSupplier = supplier;
    }

    public boolean isAvailable() {
        return onlineIdentificationService != null && onlineIdentificationService.isAvailable();
    }

    public int getMaxConcurrency() {
        if (config == null) {
            return 1;
        }
        int configured = config.getLlmWebSearchMaxConcurrency();
        return Math.max(1, Math.min(3, configured));
    }

    /** 检查指定规范化路径当前是否正在排队或运行联网搜索 */
    public boolean isFolderActive(String canonicalPath) {
        if (canonicalPath == null) {
            return false;
        }
        synchronized (lock) {
            for (SearchTask task : running) {
                if (canonicalPath.equals(task.folderPath)) {
                    return true;
                }
            }
            for (SearchTask task : queued) {
                if (canonicalPath.equals(task.folderPath)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 检查指定待确认条目是否正在排队或运行联网搜索 */
    public boolean isReviewItemActive(String reviewItemId) {
        if (reviewItemId == null) {
            return false;
        }
        synchronized (lock) {
            for (SearchTask task : running) {
                if (reviewItemId.equals(task.reviewItemId)) {
                    return true;
                }
            }
            for (SearchTask task : queued) {
                if (reviewItemId.equals(task.reviewItemId)) {
                    return true;
                }
            }
            return false;
        }
    }

    // ==================== 入队入口 ====================

    public EnqueueResult enqueueReviewItem(String reviewItemId, boolean analyzeCover) throws IOException {
        if (!isAvailable()) {
            throw new IOException("llm.web.search.unavailable");
        }
        if (reviewQueue == null || reviewItemId == null || reviewItemId.isBlank()) {
            throw new IOException("item.not.found");
        }
        ReviewItem item = reviewQueue.get(reviewItemId);
        if (item == null) {
            throw new IOException("item.not.found");
        }
        if (item.getStatus() != ReviewItem.Status.PENDING_REVIEW) {
            throw new IOException("item.already.resolved");
        }
        String canonicalPath = canonicalPath(new File(item.getFolderPath()));
        int fileCount = item.getFiles() == null ? 0 : item.getFiles().size();
        String folderName = item.getFolderName() != null && !item.getFolderName().isBlank()
            ? item.getFolderName() : new File(item.getFolderPath()).getName();

        return enqueueInternal(
            Origin.REVIEW_MANUAL,
            PRIORITY_MANUAL,
            null,
            null,
            canonicalPath,
            item.getFolderPath(),
            folderName,
            item.getId(),
            fileCount,
            analyzeCover
        );
    }

    public EnqueueResult enqueueRecoveryTarget(String sourceType, String relativePath, File target,
                                               int fileCount, boolean analyzeCover) throws IOException {
        if (!isAvailable()) {
            throw new IOException("llm.web.search.unavailable");
        }
        if (target == null || !target.exists()) {
            throw new IOException("recovery.item.not.found");
        }
        String canonicalPath = canonicalPath(target);
        String folderName = relativePath != null && !relativePath.isBlank() ? relativePath : target.getName();
        return enqueueInternal(
            Origin.RECOVERY_MANUAL,
            PRIORITY_MANUAL,
            sourceType,
            relativePath,
            canonicalPath,
            target.getAbsolutePath(),
            folderName,
            null,
            fileCount,
            analyzeCover
        );
    }

    /**
     * 缺曲条目入队后的后台自动联网搜索。
     * 注意：不在这里标记 autoOnlineSearchTriggered，而是在任务真正开始执行时才认领，
     * 这样关机时还在排队的任务重启后依然会被补跑。
     */
    public EnqueueResult enqueueMissingTracksAuto(ReviewItem queuedItem) {
        if (closed || queuedItem == null || !isAvailable()) {
            return null;
        }
        if (config != null && !config.isReviewMissingTracksAutoOnlineSearch()) {
            return null;
        }
        if (queuedItem.getStatus() != ReviewItem.Status.PENDING_REVIEW
            || queuedItem.isAutoOnlineSearchTriggered()
            || queuedItem.getOnlineSearchedAt() > 0L) {
            return null;
        }
        String canonicalPath = canonicalPath(new File(queuedItem.getFolderPath()));
        int fileCount = queuedItem.getFiles() == null ? 0 : queuedItem.getFiles().size();
        String folderName = queuedItem.getFolderName() != null && !queuedItem.getFolderName().isBlank()
            ? queuedItem.getFolderName() : new File(queuedItem.getFolderPath()).getName();
        return enqueueInternal(
            Origin.MISSING_TRACKS_AUTO,
            PRIORITY_AUTO,
            null,
            null,
            canonicalPath,
            queuedItem.getFolderPath(),
            folderName,
            queuedItem.getId(),
            fileCount,
            false
        );
    }

    private EnqueueResult enqueueInternal(Origin origin, int priority, String sourceType, String relativePath,
                                          String canonicalPath, String rawPath, String folderName,
                                          String reviewItemId, int fileCount, boolean analyzeCover) {
        synchronized (lock) {
            if (closed) {
                return new EnqueueResult(null, false, false);
            }

            // 去重：按 reviewItemId 或规范化文件夹路径查是否已在队列或运行中
            SearchTask existing = findActiveLocked(canonicalPath, reviewItemId);
            if (existing != null) {
                // 若已有的是排队中的后台自动任务，而用户现在手动点了搜索 → 直接升格为手动高优先级！
                if (existing.status == Status.QUEUED && existing.priority > priority) {
                    existing.priority = priority;
                    existing.origin = origin;
                    existing.orderSeq = orderGenerator.getAndIncrement();
                    existing.updatedAt = System.currentTimeMillis();
                    sortQueuedLocked();
                    dispatchNextLocked();
                    return new EnqueueResult(existing.toView(queuePositionLocked(existing.id)), false, true);
                }
                return new EnqueueResult(existing.toView(queuePositionLocked(existing.id)), true, false);
            }

            SearchTask task = new SearchTask();
            task.id = UUID.randomUUID().toString();
            task.origin = origin;
            task.priority = priority;
            task.orderSeq = orderGenerator.getAndIncrement();
            task.sourceType = sourceType;
            task.relativePath = relativePath;
            task.folderPath = canonicalPath;
            task.rawFolderPath = rawPath;
            task.folderName = folderName;
            task.reviewItemId = reviewItemId;
            task.fileCount = fileCount;
            task.analyzeCover = analyzeCover;
            task.engine = onlineIdentificationService != null ? onlineIdentificationService.activeProviderName() : "native";
            task.status = Status.QUEUED;
            task.stageCode = "QUEUED";
            task.stepIndex = 0;
            task.round = 1;
            task.percent = 0;
            task.createdAt = System.currentTimeMillis();
            task.updatedAt = task.createdAt;

            queued.add(task);
            tasksById.put(task.id, task);
            sortQueuedLocked();
            dispatchNextLocked();
            return new EnqueueResult(task.toView(queuePositionLocked(task.id)), false, false);
        }
    }

    // ==================== 队列控制 ====================

    /** 将等待中的任务置顶到队列首位（并提升为手动优先级） */
    public Map<String, Object> prioritize(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        synchronized (lock) {
            SearchTask target = findInList(queued, taskId);
            if (target == null) {
                return null;
            }
            long minOrder = queued.stream().mapToLong(t -> t.orderSeq).min().orElse(0L);
            target.priority = PRIORITY_MANUAL;
            target.orderSeq = minOrder - 1;
            target.updatedAt = System.currentTimeMillis();
            sortQueuedLocked();
            return target.toView(queuePositionLocked(target.id));
        }
    }

    /** 取消任务：等待中则直接移出队列；运行中则中断执行线程（若第 1 轮已保存则保留第 1 轮结果） */
    public boolean cancel(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        Future<?> futureToCancel = null;
        synchronized (lock) {
            SearchTask qTask = findInList(queued, taskId);
            if (qTask != null) {
                queued.remove(qTask);
                tasksById.remove(qTask.id);
                qTask.status = Status.CANCELLED;
                qTask.finishedAt = System.currentTimeMillis();
                qTask.updatedAt = qTask.finishedAt;
                LogCollector.addLog("INFO", "已移出 LLM 搜索等待队列: " + qTask.folderName);
                return true;
            }

            SearchTask rTask = findInList(running, taskId);
            if (rTask != null) {
                rTask.cancelRequested = true;
                rTask.updatedAt = System.currentTimeMillis();
                futureToCancel = rTask.future;
                LogCollector.addLog("WARN", "已请求中止正在进行的 LLM 搜索: " + rTask.folderName);
            } else {
                return false;
            }
        }
        if (futureToCancel != null) {
            futureToCancel.cancel(true);
        }
        return true;
    }

    /** 重试已失败或已中止的任务（以手动高优先级重新入队） */
    public EnqueueResult retry(String taskId) throws IOException {
        if (!isAvailable()) {
            throw new IOException("llm.web.search.unavailable");
        }
        SearchTask old;
        synchronized (lock) {
            old = findInList(finished, taskId);
            if (old == null || (old.status != Status.FAILED && old.status != Status.CANCELLED)) {
                throw new IOException("search.task.not.retryable");
            }
            finished.remove(old);
            tasksById.remove(old.id);
        }

        if (old.sourceType != null && old.relativePath != null) {
            File target = new File(old.rawFolderPath != null ? old.rawFolderPath : old.folderPath);
            if (!target.exists()) {
                throw new IOException("recovery.item.not.found");
            }
            return enqueueRecoveryTarget(old.sourceType, old.relativePath, target, old.fileCount, old.analyzeCover);
        }
        if (old.reviewItemId != null) {
            return enqueueReviewItem(old.reviewItemId, old.analyzeCover);
        }
        throw new IOException("item.not.found");
    }

    /** 清空所有排队等待中的任务（不影响正在运行中的任务） */
    public int clearQueued() {
        synchronized (lock) {
            int count = queued.size();
            for (SearchTask t : queued) {
                tasksById.remove(t.id);
            }
            queued.clear();
            if (count > 0) {
                LogCollector.addLog("INFO", "已清空 LLM 搜索等待队列（" + count + " 项）");
            }
            return count;
        }
    }

    /** 清空近期已完成 / 失败 / 中止记录 */
    public int clearFinished() {
        synchronized (lock) {
            int count = finished.size();
            for (SearchTask t : finished) {
                tasksById.remove(t.id);
            }
            finished.clear();
            return count;
        }
    }

    /** 配置并发上限变动后可显式触发一次补位调度 */
    public void nudgeScheduler() {
        synchronized (lock) {
            dispatchNextLocked();
        }
    }

    // ==================== 调度与执行 ====================

    private void dispatchNextLocked() {
        if (closed) {
            return;
        }
        int limit = getMaxConcurrency();
        sortQueuedLocked();
        while (running.size() < limit && !queued.isEmpty()) {
            SearchTask next = queued.remove(0);
            next.status = Status.RUNNING;
            next.engine = onlineIdentificationService != null ? onlineIdentificationService.activeProviderName() : next.engine;
            next.startedAt = System.currentTimeMillis();
            next.updatedAt = next.startedAt;
            next.stageCode = "COLLECTING";
            next.stepIndex = 0;
            next.round = 1;
            next.percent = 6;
            running.add(next);
            next.future = workers.submit(() -> executeTask(next));
        }
    }

    private void executeTask(SearchTask task) {
        try {
            // 后台自动搜索：在真正开始执行时才在队列锁内认领，确保关机时排队的任务重启还能补跑
            if (task.origin == Origin.MISSING_TRACKS_AUTO && task.reviewItemId != null && reviewQueue != null) {
                boolean claimed = reviewQueue.updatePending(task.reviewItemId, item -> {
                    if (item.isAutoOnlineSearchTriggered() || item.getOnlineSearchedAt() > 0L) {
                        return false;
                    }
                    item.setAutoOnlineSearchTriggered(true);
                    return true;
                });
                if (!claimed) {
                    finishSkipped(task);
                    return;
                }
                log.info("缺曲条目自动联网搜索: {}", task.rawFolderPath);
            }

            if (task.cancelRequested || Thread.currentThread().isInterrupted()) {
                finishCancelled(task);
                return;
            }

            OnlineIdentificationService.SearchProgressListener listener = progress -> applyProgress(task, progress);
            File folder = new File(task.rawFolderPath != null ? task.rawFolderPath : task.folderPath);
            ReviewItem result;
            if (task.origin == Origin.RECOVERY_MANUAL) {
                result = onlineIdentificationService.search(folder, task.sourceType, task.analyzeCover, listener);
            } else {
                result = onlineIdentificationService.searchForExistingItem(task.reviewItemId, folder, task.analyzeCover, listener);
            }

            if (task.cancelRequested || Thread.currentThread().isInterrupted()) {
                finishCancelled(task);
                return;
            }

            if (result == null) {
                finishSkipped(task);
                return;
            }

            finishSucceeded(task, result);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finishCancelled(task);
        } catch (Exception e) {
            if (task.cancelRequested || Thread.currentThread().isInterrupted() || isInterruptedCause(e)) {
                finishCancelled(task);
            } else {
                finishFailed(task, e);
            }
        }
    }

    private void applyProgress(SearchTask task, OnlineIdentificationService.SearchProgress progress) {
        if (progress == null) {
            return;
        }
        synchronized (lock) {
            task.stepIndex = progress.stepIndex();
            task.stageCode = progress.stageCode();
            task.round = progress.round();
            task.stageCurrent = progress.current();
            task.stageTotal = progress.total();
            task.citationsCount = progress.citations();
            task.percent = progress.percent();
            task.stageDetail = progress.detail();
            if (progress.reviewItemId() != null && !progress.reviewItemId().isBlank()) {
                task.reviewItemId = progress.reviewItemId();
            }
            if (progress.fileCount() > 0) {
                task.fileCount = progress.fileCount();
            }
            if (progress.round1Saved()) {
                task.round1Saved = true;
            }
            task.updatedAt = System.currentTimeMillis();
        }
    }

    private void finishSucceeded(SearchTask task, ReviewItem result) {
        int candCount = result.getOnlineCandidates() == null ? 0 : result.getOnlineCandidates().size();
        int clueCount = result.getOnlineClues() == null ? 0 : result.getOnlineClues().size();
        double bestCov = 0;
        if (result.getOnlineCandidates() != null) {
            for (ReviewItem.OnlineCandidate c : result.getOnlineCandidates()) {
                bestCov = Math.max(bestCov, SystemSuggestionCalculator.reliableCoverage(result, c));
            }
        }
        synchronized (lock) {
            running.remove(task);
            task.reviewItemId = result.getId();
            task.status = Status.SUCCEEDED;
            task.stageCode = "DONE";
            task.stepIndex = 4;
            task.percent = 100;
            task.candidateCount = candCount;
            task.clueCount = clueCount;
            task.bestCoverage = bestCov;
            task.finishedAt = System.currentTimeMillis();
            task.updatedAt = task.finishedAt;
            recordFinishedLocked(task);
            dispatchNextLocked();
        }
        LogCollector.addLog("SUCCESS", "LLM 联网搜索完成: " + task.folderName
            + "（候选 " + candCount + "，线索 " + clueCount + "）");
    }

    private void finishFailed(SearchTask task, Exception e) {
        String err = e.getMessage() == null || e.getMessage().isBlank()
            ? e.getClass().getSimpleName() : e.getMessage();
        log.warn("LLM 联网搜索任务失败: {} - {}", task.folderName, err);
        synchronized (lock) {
            running.remove(task);
            task.status = Status.FAILED;
            task.stageCode = "FAILED";
            task.errorMessage = err;
            task.finishedAt = System.currentTimeMillis();
            task.updatedAt = task.finishedAt;
            recordFinishedLocked(task);
            dispatchNextLocked();
        }
        LogCollector.addLog("ERROR", "LLM 联网搜索失败 (" + task.folderName + "): " + err);
    }

    private void finishCancelled(SearchTask task) {
        synchronized (lock) {
            running.remove(task);
            task.status = Status.CANCELLED;
            task.stageCode = "CANCELLED";
            task.finishedAt = System.currentTimeMillis();
            task.updatedAt = task.finishedAt;
            recordFinishedLocked(task);
            dispatchNextLocked();
        }
        LogCollector.addLog("WARN", "已中止 LLM 联网搜索: " + task.folderName
            + (task.round1Saved ? "（已保留第 1 轮结果）" : ""));
    }

    /** 条目在排队期间已被人工确认/忽略，静默结束不进失败记录 */
    private void finishSkipped(SearchTask task) {
        synchronized (lock) {
            running.remove(task);
            tasksById.remove(task.id);
            dispatchNextLocked();
        }
    }

    private void recordFinishedLocked(SearchTask task) {
        finished.add(0, task);
        while (finished.size() > MAX_HISTORY) {
            SearchTask evicted = finished.remove(finished.size() - 1);
            tasksById.remove(evicted.id);
        }
    }

    // ==================== 查询视图 ====================

    public Map<String, Object> getSnapshot() {
        List<Map<String, Object>> runningViews = new ArrayList<>();
        List<Map<String, Object>> queuedViews = new ArrayList<>();
        List<Map<String, Object>> finishedViews = new ArrayList<>();
        int succeededCount = 0;
        int failedCount = 0;
        int cancelledCount = 0;

        synchronized (lock) {
            // 如果用户在设置里调高了并发上限，顺便补位
            dispatchNextLocked();
            for (SearchTask t : running) {
                runningViews.add(t.toView(null));
            }
            for (int i = 0; i < queued.size(); i++) {
                queuedViews.add(queued.get(i).toView(i + 1));
            }
            for (SearchTask t : finished) {
                finishedViews.add(t.toView(null));
                if (t.status == Status.SUCCEEDED) succeededCount++;
                else if (t.status == Status.FAILED) failedCount++;
                else if (t.status == Status.CANCELLED) cancelledCount++;
            }
        }

        List<Map<String, Object>> otherRecovery = List.of();
        Supplier<List<Map<String, Object>>> supplier = recoveryOtherJobsSupplier;
        if (supplier != null) {
            try {
                List<Map<String, Object>> raw = supplier.get();
                if (raw != null) otherRecovery = raw;
            } catch (Exception ignored) {
                // ignore
            }
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("running", runningViews.size());
        counts.put("queued", queuedViews.size());
        counts.put("succeeded", succeededCount);
        counts.put("failed", failedCount);
        counts.put("cancelled", cancelledCount);
        counts.put("totalActive", runningViews.size() + queuedViews.size());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("available", isAvailable());
        snapshot.put("provider", onlineIdentificationService != null ? onlineIdentificationService.activeProviderName() : "native");
        snapshot.put("maxConcurrency", getMaxConcurrency());
        snapshot.put("serverTime", System.currentTimeMillis());
        snapshot.put("running", runningViews);
        snapshot.put("queued", queuedViews);
        snapshot.put("finished", finishedViews);
        snapshot.put("counts", counts);
        snapshot.put("recoveryOtherJobs", otherRecovery);
        return snapshot;
    }

    /**
     * 兼容 {@code RecoveryService.listJobs()}：返回来源为「重新识别」页的联网搜索任务视图，
     * 并在任务完成后随对应 ReviewItem 状态动态映射为 WAITING_REVIEW / SUCCEEDED / FAILED。
     */
    public List<Map<String, Object>> listRecoveryJobViews() {
        List<SearchTask> snapshot = new ArrayList<>();
        Map<String, Integer> positions = new LinkedHashMap<>();
        synchronized (lock) {
            for (SearchTask t : running) {
                if (t.origin == Origin.RECOVERY_MANUAL) snapshot.add(t);
            }
            for (int i = 0; i < queued.size(); i++) {
                SearchTask t = queued.get(i);
                if (t.origin == Origin.RECOVERY_MANUAL) {
                    snapshot.add(t);
                    positions.put(t.id, i + 1);
                }
            }
            for (SearchTask t : finished) {
                if (t.origin == Origin.RECOVERY_MANUAL) snapshot.add(t);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (SearchTask t : snapshot) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", t.id);
            view.put("sourceType", t.sourceType);
            view.put("relativePath", t.relativePath);
            view.put("path", t.folderPath);
            view.put("useLlm", true);
            view.put("mode", RecoveryService.RecoveryMode.ONLINE_SEARCH.name());
            view.put("analyzeCover", t.analyzeCover);
            view.put("reviewItemId", t.reviewItemId);
            view.put("queuePosition", positions.get(t.id));
            view.put("stageCode", t.stageCode);
            view.put("percent", t.percent);
            view.put("createdAt", t.createdAt);
            view.put("updatedAt", t.updatedAt);

            String status;
            String message;
            if (t.status == Status.QUEUED) {
                status = "QUEUED";
                message = "排队中（第 " + positions.getOrDefault(t.id, 1) + " 位）";
            } else if (t.status == Status.RUNNING) {
                status = RecoveryService.JobStatus.RUNNING.name();
                message = "联网搜索进行中";
            } else if (t.status == Status.FAILED) {
                status = RecoveryService.JobStatus.FAILED.name();
                message = t.errorMessage;
            } else if (t.status == Status.CANCELLED) {
                status = RecoveryService.JobStatus.FAILED.name();
                message = t.round1Saved ? "已中止（保留了第 1 轮结果）" : "已手动中止";
            } else {
                // SUCCEEDED -> 与旧 RecoveryService.listJobs 语义保持 100% 一致
                status = RecoveryService.JobStatus.WAITING_REVIEW.name();
                message = "联网搜索完成，候选已进入待确认页面";
                if (t.reviewItemId != null && reviewQueue != null) {
                    ReviewItem item = reviewQueue.get(t.reviewItemId);
                    if (item != null) {
                        if (item.getStatus() == ReviewItem.Status.CONFIRMED
                                || item.getStatus() == ReviewItem.Status.ARCHIVED_UNVERIFIED) {
                            status = RecoveryService.JobStatus.SUCCEEDED.name();
                            message = "已在待确认页面确认完成";
                        } else if (item.getStatus() == ReviewItem.Status.REJECTED) {
                            status = RecoveryService.JobStatus.FAILED.name();
                            message = "已在待确认页面驳回";
                        }
                    } else {
                        status = RecoveryService.JobStatus.FAILED.name();
                        message = "待确认条目已移除";
                    }
                }
            }
            view.put("status", status);
            view.put("message", message);
            view.put("successCount", t.candidateCount);
            view.put("failedCount", 0);
            out.add(view);
        }
        return out;
    }

    @Override
    public void close() {
        closed = true;
        synchronized (lock) {
            queued.clear();
            for (SearchTask t : running) {
                t.cancelRequested = true;
                if (t.future != null) {
                    t.future.cancel(true);
                }
            }
        }
        workers.shutdownNow();
    }

    // ==================== 内部工具 ====================

    private SearchTask findActiveLocked(String canonicalPath, String reviewItemId) {
        for (SearchTask t : running) {
            if (sameTarget(t, canonicalPath, reviewItemId)) return t;
        }
        for (SearchTask t : queued) {
            if (sameTarget(t, canonicalPath, reviewItemId)) return t;
        }
        return null;
    }

    private boolean sameTarget(SearchTask t, String canonicalPath, String reviewItemId) {
        if (reviewItemId != null && reviewItemId.equals(t.reviewItemId)) {
            return true;
        }
        return canonicalPath != null && canonicalPath.equals(t.folderPath);
    }

    private SearchTask findInList(List<SearchTask> list, String id) {
        for (SearchTask t : list) {
            if (id.equals(t.id)) return t;
        }
        return null;
    }

    private Integer queuePositionLocked(String id) {
        for (int i = 0; i < queued.size(); i++) {
            if (id.equals(queued.get(i).id)) return i + 1;
        }
        return null;
    }

    private void sortQueuedLocked() {
        queued.sort(Comparator.comparingInt((SearchTask t) -> t.priority)
            .thenComparingLong(t -> t.orderSeq));
    }

    private boolean isInterruptedCause(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof InterruptedException || "interrupted".equals(cur.getMessage())) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    // ==================== 数据结构 ====================

    public record EnqueueResult(Map<String, Object> task, boolean alreadyActive, boolean promoted) {}

    private static class SearchTask {
        private String id;
        private Origin origin;
        private int priority;
        private long orderSeq;
        private String sourceType;
        private String relativePath;
        private String folderPath;
        private String rawFolderPath;
        private String folderName;
        private String reviewItemId;
        private int fileCount;
        private boolean analyzeCover;
        private String engine;

        private Status status;
        private int stepIndex;
        private String stageCode;
        private int round;
        private int stageCurrent;
        private int stageTotal;
        private int citationsCount;
        private int percent;
        private String stageDetail;
        private boolean round1Saved;

        private int candidateCount;
        private int clueCount;
        private double bestCoverage;
        private String errorMessage;

        private long createdAt;
        private long startedAt;
        private long updatedAt;
        private long finishedAt;

        private volatile boolean cancelRequested;
        private Future<?> future;

        Map<String, Object> toView(Integer queuePosition) {
            long now = System.currentTimeMillis();
            long elapsedMs = 0;
            if (startedAt > 0) {
                elapsedMs = Math.max(0, (finishedAt > 0 ? finishedAt : now) - startedAt);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("origin", origin.name());
            m.put("priority", priority);
            m.put("sourceType", sourceType);
            m.put("relativePath", relativePath);
            m.put("folderPath", folderPath);
            m.put("folderName", folderName);
            m.put("reviewItemId", reviewItemId);
            m.put("fileCount", fileCount);
            m.put("engine", engine);
            m.put("status", status.name());
            m.put("queuePosition", queuePosition);
            m.put("stepIndex", stepIndex);
            m.put("stageCode", stageCode);
            m.put("round", round);
            m.put("stageCurrent", stageCurrent);
            m.put("stageTotal", stageTotal);
            m.put("citationsCount", citationsCount);
            m.put("percent", percent);
            m.put("stageDetail", stageDetail);
            m.put("round1Saved", round1Saved);
            m.put("candidateCount", candidateCount);
            m.put("clueCount", clueCount);
            m.put("bestCoverage", bestCoverage);
            m.put("errorMessage", errorMessage);
            m.put("createdAt", createdAt);
            m.put("startedAt", startedAt);
            m.put("updatedAt", updatedAt);
            m.put("finishedAt", finishedAt);
            m.put("elapsedMs", elapsedMs);
            m.put("cancelRequested", cancelRequested);
            return m;
        }
    }
}
