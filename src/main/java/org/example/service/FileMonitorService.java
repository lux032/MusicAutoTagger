package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.config.MusicConfig;

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
    private final Map<String, Long> processedFiles;
    private final Set<String> supportedExtensions;
    private final ProcessedFileLogger processedLogger;
    private final Map<WatchKey, Path> watchKeys;
    private volatile boolean running;
    private static final long PROCESS_INTERVAL = 5000; // 每个文件处理间隔5秒,防止API限流
    
    public FileMonitorService(MusicConfig config, ProcessedFileLogger processedLogger) throws IOException {
        this.config = config;
        this.watchService = FileSystems.getDefault().newWatchService();
        // 分离长期运行的守护线程和短期任务线程
        this.watcherExecutorService = Executors.newFixedThreadPool(2);  // 监控循环 + 队列消费者
        this.fileCheckExecutorService = Executors.newCachedThreadPool();  // 文件检查任务(可伸缩)
        this.fileQueue = new LinkedBlockingQueue<>();
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
            log.warn("文件监控服务已经在运行中");
            return;
        }
        
        running = true;
        
        try {
            // 注册监控目录
            Path monitorPath = Paths.get(config.getMonitorDirectory());
            if (!Files.exists(monitorPath)) {
                log.error("监控目录不存在: {}", monitorPath);
                return;
            }
            
            // 递归注册所有子目录
            registerDirectoryRecursively(monitorPath);
            
            log.info("开始监控目录: {}", monitorPath);
            
            // 首次扫描现有文件
            scanExistingFiles(monitorPath);
            
            // 启动监控线程
            watcherExecutorService.submit(this::watchLoop);
            
            // 启动文件处理队列线程
            watcherExecutorService.submit(this::processFileQueue);
            
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
            log.info("文件监控服务已停止");
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
                            log.info("已注册新建子目录: {}", fullPath);
                        } catch (IOException e) {
                            log.error("注册新建子目录失败: {}", fullPath, e);
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
            log.info("检测到新文件: {}", filePath.getFileName());
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
                log.info("文件已处理过（日志记录），跳过: {}", filePath.getFileName());
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
                log.warn("文件大小为0，跳过处理: {}", filePath);
                return;
            }
            
            // 添加到队列
            fileQueue.offer(file);
            log.info("文件已加入处理队列: {} (队列长度: {})",
                filePath.getFileName(), fileQueue.size());
            
        } catch (IOException | InterruptedException e) {
            log.error("添加文件到队列失败: {}", filePath, e);
        }
    }
    
    /**
     * 处理文件队列(顺序处理,带间隔防止限流)
     */
    private void processFileQueue() {
        log.info("文件处理队列线程已启动");
        
        while (running) {
            try {
                // 从队列取出文件(阻塞等待)
                File file = fileQueue.poll(1, TimeUnit.SECONDS);
                if (file == null) {
                    continue;
                }
                
                log.info("开始处理队列中的文件: {} (剩余: {})",
                    file.getName(), fileQueue.size());
                
                // 触发实际处理
                notifyFileReady(file);
                
                // 等待指定间隔后再处理下一个文件
                if (!fileQueue.isEmpty()) {
                    log.info("等待 {}ms 后处理下一个文件(防止API限流)...", PROCESS_INTERVAL);
                    Thread.sleep(PROCESS_INTERVAL);
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        log.info("文件处理队列线程已停止");
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
     * 扫描现有文件
     */
    private void scanExistingFiles(Path directory) {
        log.info("递归扫描现有文件...");
        
        try {
            List<Path> musicFiles = new ArrayList<>();
            Files.walk(directory)
                .filter(Files::isRegularFile)
                .filter(this::isMusicFile)
                .forEach(musicFiles::add);
            
            log.info("扫描完成,发现 {} 个音乐文件", musicFiles.size());
            
            // 将文件添加到处理队列
            // 现有文件不需要等待稳定性检查,直接入队
            for (Path path : musicFiles) {
                fileCheckExecutorService.submit(() -> addToQueue(path, false));
            }
            
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
    
    private java.util.function.Consumer<File> fileReadyCallback;
    
    public void setFileReadyCallback(java.util.function.Consumer<File> callback) {
        this.fileReadyCallback = callback;
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
}