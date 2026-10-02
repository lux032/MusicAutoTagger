package com.lux032.musicautotagger.service;

import lombok.extern.slf4j.Slf4j;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ProcessResult;
import com.lux032.musicautotagger.util.I18nUtil;
import com.lux032.musicautotagger.util.FileSystemUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/**
 * 文件监控服务
 * 监控指定目录的音乐文件变化
 */
@Slf4j
public class FileMonitorService {
    
    private final MusicConfig config;
    private final WatchService watchService;
    private final ExecutorService watcherExecutorService;  // 用于长期运行的监控线程
    private final ExecutorService fileCheckExecutorService;  // 用于短期的文件检查任务
    private final BlockingQueue<File> fileQueue;
    private final BlockingQueue<FailedFile> failedFileQueue;  // 失败文件重试队列
    private final Map<String, Long> processedFiles;
    private final Set<String> supportedExtensions;
    private final ProcessedFileLogger processedLogger;
    private final Map<WatchKey, Path> watchKeys;
    private final MonitorTaskHealth health = new MonitorTaskHealth();
    private final Map<String, Future<?>> taskFutures = new ConcurrentHashMap<>();
    private final Object admissionLock = new Object();
    private final Object processingLock = new Object();
    private final Map<File, RuntimeException> unrecordedFailures = new ConcurrentHashMap<>();
    private volatile Predicate<File> autoProcessingSkipPredicate;
    private volatile boolean closed;
    private volatile boolean running;
    private volatile boolean paused;  // 暂停状态标志
    // 每个文件处理间隔。MusicBrainz 客户端自带请求级限流，AcoustID / 歌词每首仅一次请求，
    // 这里只需留一点余量，不必再用 5 秒
    private static final long PROCESS_INTERVAL = 2000;
    private final int maxFileRetries; // 单个文件最大重试次数（从配置读取）
    
    public FileMonitorService(MusicConfig config, ProcessedFileLogger processedLogger) throws IOException {
        this.config = config;
        this.maxFileRetries = config.getMaxRetries(); // 从配置读取最大重试次数
        this.watchService = FileSystems.getDefault().newWatchService();
        // 分离长期运行的守护线程和短期任务线程
        this.watcherExecutorService = Executors.newFixedThreadPool(3);  // 监控循环 + 队列消费者 + 重试队列处理
        this.fileCheckExecutorService = Executors.newCachedThreadPool();  // 文件检查任务(可伸缩)
        this.fileQueue = new LinkedBlockingQueue<>();
        this.failedFileQueue = new LinkedBlockingQueue<>();
        this.processedFiles = new ConcurrentHashMap<>();
        this.supportedExtensions = new HashSet<>(Arrays.asList(config.getSupportedFormats()));
        this.processedLogger = processedLogger;
        this.watchKeys = new ConcurrentHashMap<>();
        this.running = false;
        this.paused = false;
    }
    
    /**
     * 启动文件监控
     */
    public synchronized void start() {
        if (closed) throw new IllegalStateException("Stopped monitor cannot be restarted");
        if (running || !taskFutures.isEmpty()) {
            log.warn(I18nUtil.getMessage("monitor.already.running"));
            return;
        }
        
        try {
            // 注册监控目录
            Path monitorPath = Paths.get(config.getMonitorDirectory());
            if (!Files.isDirectory(monitorPath)) {
                log.error(I18nUtil.getMessage("monitor.directory.not.exist"), monitorPath);
                return;
            }
            
            // 递归注册所有子目录
            registerDirectoryRecursively(monitorPath);
            
            log.info(I18nUtil.getMessage("monitor.start.monitoring"), monitorPath);
            
            running = true;
            log.info("JVM maxMemory={} bytes", Runtime.getRuntime().maxMemory());
            // 首次扫描现有文件
            scanExistingFiles(monitorPath);
            
            // 启动监控线程
            submitTask("watcher", this::watchLoop);
            
            // 启动文件处理队列线程
            submitTask("consumer", this::processFileQueue);
            
            // 启动失败文件重试线程
            submitTask("retry", this::processFailedFileQueue);
            
        } catch (IOException | RuntimeException e) {
            running = false;
            health.update("watcher", "FAULTED", null, "startup", e);
            log.error("启动文件监控失败", e);
        }
    }
    
    /**
     * 停止文件监控
     */
    public synchronized void stop() {
        synchronized (admissionLock) {
            if (closed) return;
            closed = true;
            running = false;
        }
        watcherExecutorService.shutdownNow();
        
        try {
            watchService.close();
            
            // 关闭文件检查线程池
            fileCheckExecutorService.shutdown();
            if (!fileCheckExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                fileCheckExecutorService.shutdownNow();
            }
            
            // 关闭监控线程池
            watcherExecutorService.shutdown();
            if (!watcherExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                watcherExecutorService.shutdownNow();
            }
            log.info(I18nUtil.getMessage("monitor.service.stopped"));
        } catch (IOException e) {
            log.error("停止文件监控失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("停止文件监控被中断", e);
        }
    }

    /**
     * 暂停文件监控
     */
    public void pause() {
        if (!running) {
            log.warn("监控服务未运行，无法暂停");
            return;
        }
        if (paused) {
            log.warn("监控服务已处于暂停状态");
            return;
        }
        paused = true;
        log.info("监控服务已暂停");
        LogCollector.addLog("INFO", "监控服务已暂停");
    }

    /**
     * 恢复文件监控
     */
    public void resume() {
        if (!running) {
            log.warn("监控服务未运行，无法恢复");
            return;
        }
        if (!paused) {
            log.warn("监控服务未处于暂停状态");
            return;
        }
        paused = false;
        log.info("监控服务已恢复");
        LogCollector.addLog("INFO", "监控服务已恢复");

        // 恢复时重新扫描目录，确保暂停期间新增的文件被处理
        log.info("重新扫描监控目录...");
        LogCollector.addLog("INFO", "重新扫描监控目录以检测暂停期间新增的文件");
        Path monitorPath = Paths.get(config.getMonitorDirectory());
        if (Files.exists(monitorPath)) {
            scanExistingFiles(monitorPath);
        }
    }

    /**
     * 检查监控服务是否暂停
     */
    public boolean isPaused() {
        return paused;
    }

    /**
     * 检查监控服务是否正在运行
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 监控循环
     */
    private void watchLoop() {
        while (running) {
            try {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) {
                    continue;
                }
                
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    
                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path filename = ev.context();
                    Path dir = watchKeys.get(key);
                    if (dir == null) continue;
                    Path fullPath = dir.resolve(filename);
                    
                    // 如果是新建的目录,递归注册监控
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(fullPath)) {
                        try {
                            registerDirectoryRecursively(fullPath);
                            log.info(I18nUtil.getMessage("monitor.new.subdirectory"), fullPath);
                        } catch (IOException e) {
                            log.error(I18nUtil.getMessage("monitor.register.failed"), fullPath, e);
                        }
                    }
                    
                    if (isMusicFile(fullPath)) {
                        // 暂停时不处理新文件事件，避免文件数量统计错误
                        if (paused) {
                            log.debug("监控已暂停，跳过文件事件: {}", fullPath.getFileName());
                            continue;
                        }
                        handleFileEvent(fullPath, kind);
                    }
                }
                
                key.reset();
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    /**
     * 处理文件事件
     */
    private void handleFileEvent(Path filePath, WatchEvent.Kind<?> kind) {
        String filePathStr = filePath.toString();
        
        // 防止重复处理
        Long lastProcessTime = processedFiles.get(filePathStr);
        long currentTime = System.currentTimeMillis();
        
        if (lastProcessTime != null && (currentTime - lastProcessTime) < 5000) {
            return;
        }
        
        processedFiles.put(filePathStr, currentTime);
        
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            log.info(I18nUtil.getMessage("monitor.new.file"), filePath.getFileName());
            // 添加到队列,由专门的线程按顺序处理
            // 新文件需要等待写入完成
            try {
                fileCheckExecutorService.submit(() -> addToQueue(filePath, true));
            } catch (RejectedExecutionException e) {
                if (running) throw e;
            }
        } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
            log.debug("文件被修改: {}", filePath.getFileName());
        }
    }
    
    /**
     * 添加文件到处理队列
     * @param filePath 文件路径
     * @param waitForStable 是否等待文件写入稳定(新文件需要,现有文件不需要)
     */
    private void addToQueue(Path filePath, boolean waitForStable) {
        try {
            // 首先检查是否已处理过（持久化日志）
            File file = filePath.toFile();
            if (processedLogger != null && processedLogger.isFileProcessed(file)) {
                log.debug(I18nUtil.getMessage("monitor.file.processed"), filePath.getFileName());
                return;
            }
            
            if (shouldSkipForReview(file)) return;

            // 只有新文件需要等待写入稳定,现有文件直接入队
            if (waitForStable) {
                // 等待文件完全写入（简单的重试机制）
                int maxRetries = 10;
                int retries = 0;
                long lastSize = 0;
                
                while (retries < maxRetries) {
                    Thread.sleep(1000);
                    long currentSize = Files.size(filePath);
                    if (currentSize == lastSize && currentSize > 0) {
                        break;
                    }
                    lastSize = currentSize;
                    retries++;
                }
            }
            
            if (Files.size(filePath) == 0) {
                log.warn(I18nUtil.getMessage("monitor.file.size.zero"), filePath);
                return;
            }
            
            // 添加到队列
            if (!running || Thread.currentThread().isInterrupted()) return;
            fileQueue.offer(file);
            log.info(I18nUtil.getMessage("monitor.file.queued"),
                filePath.getFileName(), fileQueue.size());
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.error("添加文件到队列失败: {}", filePath, e);
        }
    }
    
    /**
     * 处理文件队列(顺序处理,带间隔防止限流)
     */
    private void processFileQueue() {
        log.info(I18nUtil.getMessage("monitor.queue.thread.started"));
        
        int reviewSkipped = 0;
        while (running) {
            try {
                if (health.state("retry").equals("FAULTED")) {
                    Thread.sleep(1000);
                    continue;
                }
                // 检查是否暂停
                if (paused) {
                    health.update("consumer", "PAUSED", null, null, null);
                    Thread.sleep(1000);
                    continue;
                }

                // 从队列取出文件(阻塞等待)
                health.update("consumer", "RUNNING", null, null, null);
                File file = fileQueue.poll(1, TimeUnit.SECONDS);
                if (file == null) {
                    logReviewSkips(reviewSkipped);
                    reviewSkipped = 0;
                    continue;
                }

                boolean skippedForReview = false;
                try {
                    skippedForReview = processOne("consumer", file, 0);
                } catch (RuntimeException e) {
                    if (running && !Thread.currentThread().isInterrupted()) recordFileFailure(file, e);
                }
                
                if (skippedForReview) {
                    if (++reviewSkipped >= 100) {
                        logReviewSkips(reviewSkipped);
                        reviewSkipped = 0;
                    }
                    continue;
                }
                logReviewSkips(reviewSkipped);
                reviewSkipped = 0;
                // 等待指定间隔后再处理下一个文件
                if (!fileQueue.isEmpty()) {
                    log.info(I18nUtil.getMessage("monitor.wait.interval"), PROCESS_INTERVAL);
                    Thread.sleep(PROCESS_INTERVAL);
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        logReviewSkips(reviewSkipped);
        log.info(I18nUtil.getMessage("monitor.queue.thread.stopped"));
    }
    
    /**
     * 处理失败文件重试队列
     */
    private void processFailedFileQueue() {
        log.info(I18nUtil.getMessage("monitor.retry.thread.started"));

        int reviewSkipped = 0;
        while (running) {
            try {
                if (health.state("consumer").equals("FAULTED")) {
                    Thread.sleep(1000);
                    continue;
                }
                health.update("retry", paused ? "PAUSED" : "RUNNING", null, null, null);
                if (paused) {
                    Thread.sleep(1000);
                    continue;
                }
                // 先检查队列是否有内容
                if (failedFileQueue.isEmpty()) {
                    logReviewSkips(reviewSkipped);
                    reviewSkipped = 0;
                    // 队列为空时才等待
                    Thread.sleep(1000);
                    continue;
                }

                // 如果主队列还在处理中，等待主队列处理完毕再开始重试
                if (!fileQueue.isEmpty()) {
                    Thread.sleep(5000); // 短暂等待后再检查
                    continue;
                }

                // Take one at a time: a fatal exit must not discard the rest of the retry queue.
                FailedFile failedFile = failedFileQueue.poll();
                if (failedFile != null) {
                    boolean skippedForReview = false;
                    try {
                        synchronized (processingLock) {
                            if (!canProcess()) {
                                // Preserve the original item/count across a stop or fault race.
                                // The interval below and top-of-loop fault gate prevent busy re-polling.
                                failedFileQueue.offer(failedFile);
                            } else if (shouldSkipForReview(failedFile.getFile())) {
                                skippedForReview = true;
                            } else if (failedFile.getRetryCount() >= maxFileRetries) {
                                log.warn(I18nUtil.getMessage("monitor.retry.max.reached"),
                                    failedFile.getFile().getName(), maxFileRetries);
                                moveToFailedDirectory(failedFile.getFile());
                            } else {
                                log.info(I18nUtil.getMessage("monitor.processing.retry.queue"), failedFileQueue.size() + 1);
                                if (failedFile.getRetryCount() > 0) {
                                    log.info(I18nUtil.getMessage("monitor.retry.file"),
                                        failedFile.getFile().getName(), failedFile.getRetryCount(), maxFileRetries);
                                } else {
                                    log.info(I18nUtil.getMessage("monitor.delay.retry.file"), failedFile.getFile().getName());
                                }
                                processOneChecked("retry", failedFile.getFile(), failedFile.getRetryCount());
                            }
                        }
                    } catch (RuntimeException e) {
                        if (running && !Thread.currentThread().isInterrupted())
                            recordFileFailure(failedFile.getFile(), e);
                    }
                    if (skippedForReview) {
                        if (++reviewSkipped >= 100) {
                            logReviewSkips(reviewSkipped);
                            reviewSkipped = 0;
                        }
                        continue;
                    }
                    logReviewSkips(reviewSkipped);
                    reviewSkipped = 0;
                    // 重试之间也需要间隔
                    Thread.sleep(PROCESS_INTERVAL);
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        logReviewSkips(reviewSkipped);
        log.info(I18nUtil.getMessage("monitor.retry.thread.stopped"));
    }
    
    /**
     * 添加文件到失败队列
     */
    private void addToFailedQueue(File file, int retryCount) {
        FailedFile failedFile = new FailedFile(file, retryCount);
        failedFileQueue.offer(failedFile);
        log.info(I18nUtil.getMessage("monitor.file.added.to.retry"),
            file.getName(), retryCount, failedFileQueue.size());
    }
    
    /**
     * 递归注册目录监控
     */
    private void registerDirectoryRecursively(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                WatchKey key = dir.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY
                );
                watchKeys.put(key, dir);
                log.debug("已注册监控目录: {}", dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    
    /**
     * 扫描现有文件 - 按文件夹分组批量处理
     */
    private void scanExistingFiles(Path directory) {
        log.info(I18nUtil.getMessage("monitor.scan.existing"));
        
        try {
            List<Path> musicFiles = new ArrayList<>();
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                paths.filter(Files::isRegularFile).filter(this::isMusicFile).forEach(musicFiles::add);
            }
            
            log.info(I18nUtil.getMessage("monitor.scan.complete"), musicFiles.size());
            
            // 按文件夹分组
            Map<String, List<Path>> filesByFolder = new LinkedHashMap<>();
            for (Path path : musicFiles) {
                String folderPath = path.getParent().toString();
                filesByFolder.computeIfAbsent(folderPath, k -> new ArrayList<>()).add(path);
            }
            
            log.info(I18nUtil.getMessage("monitor.file.distribution"), filesByFolder.size());
            
            // 统计已处理和待处理的文件数
            int totalSkipped = 0;
            int totalQueued = 0;
            int reviewFiles = 0;
            Set<String> reviewAlbums = new HashSet<>();
            FileSystemUtils fileSystemUtils = new FileSystemUtils(config);
            
            // 按文件夹逐个处理
            for (Map.Entry<String, List<Path>> entry : filesByFolder.entrySet()) {
                String folderPath = entry.getKey();
                List<Path> folderFiles = entry.getValue();
                
                // 过滤掉已处理的文件
                List<Path> unprocessedFiles = new ArrayList<>();
                int skippedInFolder = 0;
                
                for (Path path : folderFiles) {
                    File file = path.toFile();
                    if (processedLogger != null && processedLogger.isFileProcessed(file)) {
                        skippedInFolder++;
                    } else {
                        unprocessedFiles.add(path);
                    }
                }
                
                totalSkipped += skippedInFolder;
                
                if (unprocessedFiles.isEmpty()) {
                    log.debug(I18nUtil.getMessage("monitor.folder.all.processed"), new File(folderPath).getName());
                    continue;
                }
                
                if (shouldSkipForReview(unprocessedFiles.get(0).toFile())) {
                    reviewFiles += unprocessedFiles.size();
                    try {
                        File albumRoot = fileSystemUtils.getAlbumRootDirectory(unprocessedFiles.get(0).toFile());
                        if (albumRoot != null) reviewAlbums.add(albumRoot.getAbsolutePath());
                    } catch (RuntimeException e) {
                        log.debug("Cannot resolve skipped album for scan statistics: {}", folderPath, e);
                    }
                    continue;
                }

                log.info(I18nUtil.getMessage("monitor.folder.status"),
                    new File(folderPath).getName(), unprocessedFiles.size(), skippedInFolder);
                
                // 将该文件夹的所有待处理文件按顺序加入队列
                for (Path path : unprocessedFiles) {
                    try {
                        if (!running || Thread.currentThread().isInterrupted()) continue;
                        if (Files.size(path) == 0) {
                            log.warn(I18nUtil.getMessage("monitor.file.size.zero"), path);
                            continue;
                        }
                        fileQueue.offer(path.toFile());
                        totalQueued++;
                    } catch (IOException e) {
                        log.error("添加文件到队列失败: {}", path, e);
                    }
                }
            }
            
            log.info("========================================");
            log.info(I18nUtil.getMessage("monitor.scan.review.summary"),
                totalSkipped, totalQueued, reviewAlbums.size(), reviewFiles);
            log.info(I18nUtil.getMessage("monitor.process.strategy"));
            log.info("========================================");
            
        } catch (IOException e) {
            log.error("扫描现有文件失败", e);
        }
    }
    
    /**
     * 把指定文件重新加入处理队列（处理记录管理页「重新识别」使用）。
     * 调用方需先删除这些文件的已处理记录，否则入队时会被当作已处理跳过。
     * 按文件夹分组、组内按路径排序后入队，与启动扫描的顺序保持一致。
     * @return 实际入队的文件数（监控未运行、文件不存在或不是支持的格式时不入队）
     */
    public int requeueFiles(java.util.Collection<File> files) {
        return requeueFiles(files, () -> {});
    }

    /** Record removal and admission share the stop/fault lock. */
    public int requeueFiles(java.util.Collection<File> files, Runnable beforeEnqueue) {
        synchronized (admissionLock) {
        if (!isConsumerAvailable() || files == null) {
            throw new IllegalStateException("File consumer unavailable");
        }
        Map<String, List<Path>> byFolder = new java.util.TreeMap<>();
        for (File file : files) {
            Path path = file.toPath();
            if (isMusicFile(path) && path.getParent() != null) {
                byFolder.computeIfAbsent(path.getParent().toString(), k -> new ArrayList<>()).add(path);
            }
        }
        beforeEnqueue.run();
        int queued = 0;
        for (List<Path> folderFiles : byFolder.values()) {
            folderFiles.sort(null);
            for (Path path : folderFiles) {
                processedFiles.remove(path.toString());
                fileQueue.offer(path.toFile());
                queued++;
            }
        }
        if (queued > 0) {
            log.info("已将 {} 个文件重新加入处理队列（手动重新识别）", queued);
            LogCollector.addLog("INFO", "手动重新识别：已将 " + queued + " 个文件加入处理队列");
        }
        return queued;
        }
    }

    public boolean isConsumerAvailable() {
        String state = health.state("consumer");
        return running && !closed && !health.state("retry").equals("FAULTED")
            && !state.equals("FAULTED") && !state.equals("STOPPED")
            && !state.equals("NOT_STARTED");
    }

    public Map<String, Object> getHealthSnapshot() {
        Map<String, Map<String, Object>> tasks = health.snapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mainQueueSize", fileQueue.size());
        result.put("retryQueueSize", failedFileQueue.size());
        result.put("tasks", tasks);
        result.put("unrecordedFailureCount", unrecordedFailures.size());
        Map<String, Object> active = tasks.get("consumer");
        if (!active.containsKey("currentFile")) active = tasks.get("retry");
        for (String key : List.of("currentFile", "currentStage", "currentStartedAt", "lastActivityAt"))
            result.put(key, active.get(key));
        Object lastFault = null;
        long latest = -1;
        for (Map<String, Object> task : tasks.values()) {
            if (task.get("lastFault") instanceof Map<?, ?> fault && (Long) fault.get("at") > latest) {
                latest = (Long) fault.get("at");
                lastFault = fault;
            }
        }
        result.put("lastFault", lastFault);
        return Collections.unmodifiableMap(result);
    }

    private void submitTask(String task, Runnable body) {
        health.update(task, "RUNNING", null, null, null);
        taskFutures.put(task, watcherExecutorService.submit(() -> {
            try {
                body.run();
                if (running && !Thread.currentThread().isInterrupted())
                    throw new IllegalStateException("Long-running task returned unexpectedly");
                synchronized (admissionLock) {
                    health.update(task, "STOPPED", null, null, null);
                }
            } catch (RuntimeException | Error failure) {
                Map<String, Object> previous = health.snapshot().get(task);
                String file = (String) previous.get("currentFile");
                synchronized (admissionLock) {
                    boolean faulted = failure instanceof Error || (running && !Thread.currentThread().isInterrupted());
                    health.update(task, faulted ? "FAULTED" : "STOPPED", file,
                        (String) previous.get("currentStage"), faulted ? failure : null);
                }
                if (health.state(task).equals("STOPPED")) return;
                try {
                    Runtime rt = Runtime.getRuntime();
                    log.error("Monitor task={} file={} mainQueue={} retryQueue={} heap max={} total={} free={}",
                        task, file, fileQueue.size(), failedFileQueue.size(), rt.maxMemory(), rt.totalMemory(), rt.freeMemory(), failure);
                    LogCollector.addLog("ERROR", "Monitor task " + task + " exited: " + failure.getClass().getName());
                } catch (RuntimeException | Error loggingFailure) {
                    // State is published first; logging under memory pressure is best effort.
                }
                throw failure;
            }
        }));
    }

    /** Only a positive live review check may waive the processing interval. */
    private boolean processOne(String task, File file, int retryCount) {
        synchronized (processingLock) {
            if (!canProcess()) return false;
            if (shouldSkipForReview(file)) return true;
            processOneChecked(task, file, retryCount);
            return false;
        }
    }

    private boolean canProcess() {
        return running && !Thread.currentThread().isInterrupted()
            && !health.state("consumer").equals("FAULTED") && !health.state("retry").equals("FAULTED");
    }

    /** Optional live PENDING_REVIEW lookup; RuntimeException falls back to normal processing. */
    public void setAutoProcessingSkipPredicate(Predicate<File> predicate) {
        autoProcessingSkipPredicate = predicate;
    }

    private boolean shouldSkipForReview(File file) {
        Predicate<File> predicate = autoProcessingSkipPredicate;
        if (predicate == null) return false;
        try {
            boolean skip = predicate.test(file);
            if (skip) log.debug(I18nUtil.getMessage("monitor.review.file.skipped"), file);
            return skip;
        } catch (RuntimeException e) {
            log.debug("Review check failed; retaining normal processing for {}", file, e);
            return false;
        }
    }

    private void logReviewSkips(int count) {
        if (count > 0) log.info(I18nUtil.getMessage("monitor.review.skip.summary"), count);
    }

    /** Caller holds processingLock and has checked lifecycle and review state. */
    private void processOneChecked(String task, File file, int retryCount) {
        String relative = getRelativePathFromMonitorDir(file);
        health.update(task, "PROCESSING", relative, "processing", null);
        MonitorTaskHealth.bind(stage -> health.update(task, "PROCESSING", relative, stage, null));
        try {
            try {
                ProcessResult result = Objects.requireNonNull(notifyFileReadyWithResult(file), "Null processing result");
                if (running && !Thread.currentThread().isInterrupted() && result.shouldRetry())
                    addToFailedQueue(file, retryCount + (result.shouldIncrementRetryCount() ? 1 : 0));
            } finally {
                MonitorTaskHealth.clear();
            }
        } catch (RuntimeException e) {
            if (running && !Thread.currentThread().isInterrupted()) recordFileFailure(file, e);
        } catch (Error e) {
            synchronized (admissionLock) {
                health.update(task, "FAULTED", relative,
                    (String) health.snapshot().get(task).get("currentStage"), e);
            }
            throw e;
        }
        health.update(task, "RUNNING", null, null, null);
    }

    private void recordFileFailure(File file, RuntimeException failure) {
        unrecordedFailures.put(file, failure);
        try {
            if (processedLogger == null) throw new IllegalStateException("No processed file logger");
            processedLogger.markFailureAsProcessed(file, failure.getClass().getSimpleName());
            unrecordedFailures.remove(file);
        } catch (RuntimeException recordingFailure) {
            try {
                log.error("Cannot persist FAILED record for {} (retained in memory)", file, recordingFailure);
                LogCollector.addLog("ERROR", "FAILED record could not be persisted; file retained for manual recovery");
            } catch (RuntimeException loggingFailure) { /* retention remains observable */ }
        }
        try {
            log.error("File processing failed: {}", file, failure);
            LogCollector.addLog("ERROR", "File processing failed: " + file.getName());
        } catch (RuntimeException loggingFailure) { /* do not mask processing outcome */ }
    }

    /**
     * 判断是否为音乐文件
     */
    private boolean isMusicFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        
        String fileName = path.getFileName().toString().toLowerCase();
        return supportedExtensions.stream()
            .anyMatch(ext -> fileName.endsWith("." + ext));
    }
    
    /**
     * 通知文件已准备好处理
     * 这个方法将被 Main 类覆盖以注入实际的处理逻辑
     */
    private void notifyFileReady(File file) {
        if (fileReadyCallback != null) {
            fileReadyCallback.accept(file);
        }
    }
    
    /**
     * 通知文件已准备好处理，并返回处理结果
     */
    private ProcessResult notifyFileReadyWithResult(File file) {
        if (fileReadyCallbackWithResult != null) {
            return fileReadyCallbackWithResult.apply(file);
        }
        // 如果没有设置带返回值的回调，使用原来的回调并返回成功
        notifyFileReady(file);
        return ProcessResult.SUCCESS;
    }
    
    private java.util.function.Consumer<File> fileReadyCallback;
    private java.util.function.Function<File, ProcessResult> fileReadyCallbackWithResult;
    
    public void setFileReadyCallback(java.util.function.Consumer<File> callback) {
        this.fileReadyCallback = callback;
    }
    
    public void setFileReadyCallbackWithResult(java.util.function.Function<File, ProcessResult> callback) {
        this.fileReadyCallbackWithResult = callback;
    }
    
    /**
     * 获取队列中待处理文件数量
     */
    public int getQueueSize() {
        return fileQueue.size();
    }
    
    /**
     * 获取已处理文件数量
     */
    public int getProcessedFileCount() {
        return processedFiles.size();
    }
    
    /**
     * 清理过期的处理记录
     */
    public void cleanupOldRecords() {
        long currentTime = System.currentTimeMillis();
        long expirationTime = 24 * 60 * 60 * 1000; // 24小时
        
        processedFiles.entrySet().removeIf(entry ->
            currentTime - entry.getValue() > expirationTime
        );
    }
    
    /**
     * 复制失败文件到失败目录，保留完整的相对路径结构
     *
     * 关键修复：
     * - 复制单个失败文件（而非移动）
     * - 保留从监控目录到文件的完整相对路径结构
     * - 只复制这一个失败的文件，不复制整个专辑文件夹
     *
     * 例如：
     * - 监控目录：/MusicDownload
     * - 失败文件：/MusicDownload/Artist - Album/Disc 1/03 song.flac
     * - 失败目录：/failed_files
     * - 结果：/failed_files/Artist - Album/Disc 1/03 song.flac
     */
    private void moveToFailedDirectory(File file) {
        // 记录到数据库，标记为处理失败（无论是否配置失败目录，都必须记录）
        if (processedLogger != null) {
            try {
                processedLogger.markFailureAsProcessed(file, "重试失败");
                log.info(I18nUtil.getMessage("monitor.failed.file.recorded"), file.getName());
            } catch (RuntimeException e) {
                recordFileFailure(file, e);
            }
        }

        String failedDir = config.getFailedDirectory();
        if (failedDir == null || failedDir.trim().isEmpty()) {
            log.warn(I18nUtil.getMessage("monitor.failed.dir.not.configured"), file.getName());
            return;
        }

        try {
            Path failedDirPath = Paths.get(failedDir);
            if (!Files.exists(failedDirPath)) {
                Files.createDirectories(failedDirPath);
                log.info(I18nUtil.getMessage("monitor.failed.dir.created"), failedDir);
            }

            // 计算相对路径（保留文件夹结构）
            Path sourcePath = file.toPath();
            String relativePath = getRelativePathFromMonitorDir(file);
            Path targetPath = failedDirPath.resolve(relativePath);

            // 创建目标目录（保留文件夹结构）
            Path targetDir = targetPath.getParent();
            if (targetDir != null && !Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
                log.debug("创建失败文件目录结构: {}", targetDir);
            }

            // 如果目标文件已存在，添加时间戳
            if (Files.exists(targetPath)) {
                String baseName = file.getName();
                int dotIndex = baseName.lastIndexOf('.');
                String name = dotIndex > 0 ? baseName.substring(0, dotIndex) : baseName;
                String ext = dotIndex > 0 ? baseName.substring(dotIndex) : "";
                String timestamp = String.valueOf(System.currentTimeMillis());
                targetPath = targetDir.resolve(name + "_" + timestamp + ext);
            }

            // 复制文件（而非移动），保留原文件
            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("✓ 失败文件已复制到: {} (保留原文件)", targetPath);

        } catch (IOException e) {
            log.error(I18nUtil.getMessage("monitor.move.failed.error"), file.getName(), failedDir, e);
        }
    }
    
    /**
     * 获取文件相对于监控目录的相对路径
     */
    private String getRelativePathFromMonitorDir(File file) {
        try {
            String monitorDirPath = new File(config.getMonitorDirectory()).getCanonicalPath();
            String filePath = file.getCanonicalPath();
            
            if (filePath.startsWith(monitorDirPath)) {
                String relativePath = filePath.substring(monitorDirPath.length());
                // 去掉开头的分隔符
                if (relativePath.startsWith(File.separator)) {
                    relativePath = relativePath.substring(1);
                }
                return relativePath;
            }
        } catch (IOException e) {
            log.warn("获取相对路径失败，使用文件名: {}", e.getMessage());
        }
        
        // 如果无法获取相对路径，返回文件名
        return file.getName();
    }
    
    /**
     * 失败文件信息
     */
    private static class FailedFile {
        private final File file;
        private final int retryCount;
        
        public FailedFile(File file, int retryCount) {
            this.file = file;
            this.retryCount = retryCount;
        }
        
        public File getFile() {
            return file;
        }
        
        public int getRetryCount() {
            return retryCount;
        }
    }
}
