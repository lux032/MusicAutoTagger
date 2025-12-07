package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.config.MusicConfig;
import org.example.util.I18nUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;

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
    private volatile boolean running;
    private static final long PROCESS_INTERVAL = 5000; // 每个文件处理间隔5秒,防止API限流
    private final int maxFileRetries; // 单个文件最大重试次数（从配置读取）
    private static final long RETRY_QUEUE_CHECK_INTERVAL = 60000; // 重试队列检查间隔60秒
    
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
    }
    
    /**
     * 启动文件监控
     */
    public void start() {
        if (running) {
            log.warn(I18nUtil.getMessage("monitor.already.running"));
            return;
        }
        
        running = true;
        
        try {
            // 注册监控目录
            Path monitorPath = Paths.get(config.getMonitorDirectory());
            if (!Files.exists(monitorPath)) {
                log.error(I18nUtil.getMessage("monitor.directory.not.exist"), monitorPath);
                return;
            }
            
            // 递归注册所有子目录
            registerDirectoryRecursively(monitorPath);
            
            log.info(I18nUtil.getMessage("monitor.start.monitoring"), monitorPath);
            
            // 首次扫描现有文件
            scanExistingFiles(monitorPath);
            
            // 启动监控线程
            watcherExecutorService.submit(this::watchLoop);
            
            // 启动文件处理队列线程
            watcherExecutorService.submit(this::processFileQueue);
            
            // 启动失败文件重试线程
            watcherExecutorService.submit(this::processFailedFileQueue);
            
        } catch (IOException e) {
            log.error("启动文件监控失败", e);
            running = false;
        }
    }
    
    /**
     * 停止文件监控
     */
    public void stop() {
        if (!running) {
            return;
        }
        
        running = false;
        
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
        } catch (IOException | InterruptedException e) {
            log.error("停止文件监控失败", e);
        }
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
            fileCheckExecutorService.submit(() -> addToQueue(filePath, true));
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
            fileQueue.offer(file);
            log.info(I18nUtil.getMessage("monitor.file.queued"),
                filePath.getFileName(), fileQueue.size());
            
        } catch (IOException | InterruptedException e) {
            log.error("添加文件到队列失败: {}", filePath, e);
        }
    }
    
    /**
     * 处理文件队列(顺序处理,带间隔防止限流)
     */
    private void processFileQueue() {
        log.info(I18nUtil.getMessage("monitor.queue.thread.started"));
        
        while (running) {
            try {
                // 从队列取出文件(阻塞等待)
                File file = fileQueue.poll(1, TimeUnit.SECONDS);
                if (file == null) {
                    continue;
                }
                
                log.info(I18nUtil.getMessage("monitor.processing.file"),
                    file.getName(), fileQueue.size());
                
                // 触发实际处理，并捕获结果
                boolean success = notifyFileReadyWithResult(file);
                
                // 如果处理失败且是网络错误，加入重试队列
                if (!success) {
                    addToFailedQueue(file, 1);
                }
                
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
        
        log.info(I18nUtil.getMessage("monitor.queue.thread.stopped"));
    }
    
    /**
     * 处理失败文件重试队列
     */
    private void processFailedFileQueue() {
        log.info(I18nUtil.getMessage("monitor.retry.thread.started"));

        while (running) {
            try {
                // 先检查队列是否有内容
                if (failedFileQueue.isEmpty()) {
                    // 队列为空时才等待
                    Thread.sleep(RETRY_QUEUE_CHECK_INTERVAL);
                    continue;
                }

                // 如果主队列还在处理中，等待主队列处理完毕再开始重试
                if (!fileQueue.isEmpty()) {
                    Thread.sleep(5000); // 短暂等待后再检查
                    continue;
                }

                log.info(I18nUtil.getMessage("monitor.processing.retry.queue"), failedFileQueue.size());
                
                // 取出所有失败文件进行重试
                List<FailedFile> filesToRetry = new ArrayList<>();
                failedFileQueue.drainTo(filesToRetry);
                
                for (FailedFile failedFile : filesToRetry) {
                    if (failedFile.getRetryCount() >= maxFileRetries) {
                        log.warn(I18nUtil.getMessage("monitor.retry.max.reached"),
                            failedFile.getFile().getName(), maxFileRetries);
                        // 移动到失败文件目录
                        moveToFailedDirectory(failedFile.getFile());
                        continue;
                    }
                    
                    log.info(I18nUtil.getMessage("monitor.retry.file"),
                        failedFile.getFile().getName(), failedFile.getRetryCount(), maxFileRetries);
                    
                    boolean success = notifyFileReadyWithResult(failedFile.getFile());
                    
                    if (!success) {
                        // 重试失败，重新加入队列
                        addToFailedQueue(failedFile.getFile(), failedFile.getRetryCount() + 1);
                    } else {
                        log.info(I18nUtil.getMessage("monitor.retry.success"), failedFile.getFile().getName());
                    }
                    
                    // 重试之间也需要间隔
                    Thread.sleep(PROCESS_INTERVAL);
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
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
            Files.walk(directory)
                .filter(Files::isRegularFile)
                .filter(this::isMusicFile)
                .forEach(musicFiles::add);
            
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
                totalQueued += unprocessedFiles.size();
                
                if (unprocessedFiles.isEmpty()) {
                    log.debug(I18nUtil.getMessage("monitor.folder.all.processed"), new File(folderPath).getName());
                    continue;
                }
                
                log.info(I18nUtil.getMessage("monitor.folder.status"),
                    new File(folderPath).getName(), unprocessedFiles.size(), skippedInFolder);
                
                // 将该文件夹的所有待处理文件按顺序加入队列
                for (Path path : unprocessedFiles) {
                    fileCheckExecutorService.submit(() -> addToQueue(path, false));
                }
            }
            
            log.info("========================================");
            log.info(I18nUtil.getMessage("monitor.scan.summary"),
                totalSkipped, totalQueued);
            log.info(I18nUtil.getMessage("monitor.process.strategy"));
            log.info("========================================");
            
        } catch (IOException e) {
            log.error("扫描现有文件失败", e);
        }
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
    private boolean notifyFileReadyWithResult(File file) {
        if (fileReadyCallbackWithResult != null) {
            return fileReadyCallbackWithResult.apply(file);
        }
        // 如果没有设置带返回值的回调，使用原来的回调并返回true
        notifyFileReady(file);
        return true;
    }
    
    private java.util.function.Consumer<File> fileReadyCallback;
    private java.util.function.Function<File, Boolean> fileReadyCallbackWithResult;
    
    public void setFileReadyCallback(java.util.function.Consumer<File> callback) {
        this.fileReadyCallback = callback;
    }
    
    public void setFileReadyCallbackWithResult(java.util.function.Function<File, Boolean> callback) {
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
     * 移动文件到失败目录，并记录到数据库
     */
    private void moveToFailedDirectory(File file) {
        // 关键修复：无论是否配置失败目录，都必须记录到数据库，避免文件"静默丢失"
        // 记录到数据库，标记为处理失败
        if (processedLogger != null) {
            try {
                processedLogger.markFileAsProcessed(
                    file,
                    "FAILED",
                    "重试失败",
                    file.getName(),
                    "Unknown Album"
                );
                log.info(I18nUtil.getMessage("monitor.failed.file.recorded"), file.getName());
            } catch (Exception e) {
                log.error(I18nUtil.getMessage("monitor.record.failed.error"), file.getName(), e.getMessage());
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

            Path sourcePath = file.toPath();
            Path targetPath = failedDirPath.resolve(file.getName());

            // 如果目标文件已存在，添加时间戳
            if (Files.exists(targetPath)) {
                String baseName = file.getName();
                int dotIndex = baseName.lastIndexOf('.');
                String name = dotIndex > 0 ? baseName.substring(0, dotIndex) : baseName;
                String ext = dotIndex > 0 ? baseName.substring(dotIndex) : "";
                String timestamp = String.valueOf(System.currentTimeMillis());
                targetPath = failedDirPath.resolve(name + "_" + timestamp + ext);
            }

            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info(I18nUtil.getMessage("monitor.failed.file.moved"), targetPath);

        } catch (IOException e) {
            log.error(I18nUtil.getMessage("monitor.move.failed.error"), file.getName(), failedDir, e);
        }
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