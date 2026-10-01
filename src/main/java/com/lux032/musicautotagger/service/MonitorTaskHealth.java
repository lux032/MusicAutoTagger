package com.lux032.musicautotagger.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Immutable task snapshots; progress belongs only to the callback's thread. */
final class MonitorTaskHealth {
    private final AtomicReference<Map<String, Map<String, Object>>> tasks = new AtomicReference<>(initial());
    private static final ThreadLocal<Consumer<String>> PROGRESS = new ThreadLocal<>();

    private static Map<String, Map<String, Object>> initial() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String task : new String[]{"watcher", "consumer", "retry"})
            result.put(task, Map.of("state", "NOT_STARTED", "lastActivityAt", 0L));
        return Map.copyOf(result);
    }
    Map<String, Map<String, Object>> snapshot() { return tasks.get(); }
    String state(String task) { return (String) tasks.get().get(task).get("state"); }
    void update(String task, String state, String file, String stage, Throwable fault) {
        long now = System.currentTimeMillis();
        tasks.updateAndGet(old -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            Map<String, Object> previous = old.get(task);
            entry.put("state", state);
            entry.put("lastActivityAt", now);
            if (file != null) {
                entry.put("currentFile", file);
                entry.put("currentStage", stage);
                entry.put("currentStartedAt", file.equals(previous.get("currentFile"))
                    ? previous.getOrDefault("currentStartedAt", now) : now);
            }
            if (previous.containsKey("lastFault")) entry.put("lastFault", previous.get("lastFault"));
            // Do not expose exception messages, which may contain credentials or absolute paths.
            if (fault != null) entry.put("lastFault", Map.of("at", now, "task", task,
                "type", fault.getClass().getName(), "message", "Task terminated unexpectedly"));
            Map<String, Map<String, Object>> next = new LinkedHashMap<>(old);
            next.put(task, Map.copyOf(entry));
            return Map.copyOf(next);
        });
    }
    static void bind(Consumer<String> progress) { PROGRESS.set(progress); }
    static void clear() { PROGRESS.remove(); }
    static void stage(String stage) { Consumer<String> progress = PROGRESS.get(); if (progress != null) progress.accept(stage); }
}
