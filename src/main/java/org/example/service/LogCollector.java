package org.example.service;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 日志收集器 - 用于收集应用程序运行时的日志信息
 * 供 Web 监控面板实时展示
 */
public class LogCollector {
    
    private static final int MAX_LOG_SIZE = 200; // 最多保留200条日志
    private static final ConcurrentLinkedQueue<LogEntry> logs = new ConcurrentLinkedQueue<>();
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    /**
     * 添加日志条目
     */
    public static void addLog(String level, String message) {
        LogEntry entry = new LogEntry(
            LocalDateTime.now().format(formatter),
            level,
            message
        );
        
        logs.offer(entry);
        
        // 如果超过最大数量，移除最旧的日志
        while (logs.size() > MAX_LOG_SIZE) {
            logs.poll();
        }
    }
    
    /**
     * 获取最近的N条日志
     */
    public static List<LogEntry> getRecentLogs(int limit) {
        List<LogEntry> result = new ArrayList<>();
        int count = 0;
        
        // 从队列尾部开始获取（最新的日志）
        Object[] logArray = logs.toArray();
        for (int i = logArray.length - 1; i >= 0 && count < limit; i--) {
            result.add((LogEntry) logArray[i]);
            count++;
        }
        
        return result;
    }
    
    /**
     * 清空日志
     */
    public static void clear() {
        logs.clear();
    }
    
    /**
     * 日志条目
     */
    @Data
    public static class LogEntry {
        private final String timestamp;
        private final String level;
        private final String message;
        
        public LogEntry(String timestamp, String level, String message) {
            this.timestamp = timestamp;
            this.level = level;
            this.message = message;
        }
    }
}