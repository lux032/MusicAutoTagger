package com.lux032.musicautotagger.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * 首次启动初始化状态持久化存储（data/setup-state.json）。
 *
 * 判定规则：
 * 1. 若 setup-state.json 存在，以其中的 completed 为准；
 * 2. 若 setup-state.json 不存在，但 data/admin.json 已存在有效凭据，
 *    视为老版本升级用户（已完成初始化），避免强弹全屏引导打扰；
 * 3. 两者均不存在，视为全新首次启动（未完成初始化）。
 */
public class SetupStateStore {

    public static final String ACOUSTID_PLACEHOLDER = "YOUR_API_KEY_HERE";

    private final Path path;
    private final Gson gson;

    public SetupStateStore(Path path) {
        this.path = path;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public static SetupStateStore defaultStore() {
        return new SetupStateStore(Path.of("data", "setup-state.json"));
    }

    public Path getPath() {
        return path;
    }

    public boolean exists() {
        return Files.exists(path);
    }

    public synchronized SetupState load() throws IOException {
        if (!exists()) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, SetupState.class);
        } catch (com.google.gson.JsonParseException e) {
            return null;
        }
    }

    public synchronized void save(SetupState state) throws IOException {
        if (state == null) {
            throw new IllegalArgumentException("SetupState required");
        }
        Path normalized = path.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path tempPath = Files.createTempFile(parent != null ? parent : Path.of("."), ".setup-state-", ".tmp");
        try {
            OutboundUrlValidator.setOwnerOnlyPermissions(tempPath);
            try (Writer writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
                gson.toJson(state, writer);
            }
            try {
                Files.move(tempPath, normalized, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempPath, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
            OutboundUrlValidator.setOwnerOnlyPermissions(normalized);
        } finally {
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException ignored) {
            }
        }
    }

    public synchronized boolean isCompleted(AdminCredentialsStore adminStore) {
        try {
            SetupState state = load();
            if (state != null) {
                return state.completed;
            }
        } catch (IOException ignored) {
        }
        if (adminStore == null) {
            return false;
        }
        try {
            return adminStore.load() != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 首次创建管理员时调用：若尚无状态文件，落盘 completed=false，
     * 防止用户建号后中途关闭或重启导致被误判为「已有 admin.json 的老用户」。
     */
    public synchronized void markInProgressIfAbsent() throws IOException {
        if (exists()) {
            return;
        }
        SetupState state = new SetupState();
        state.completed = false;
        state.completedAt = null;
        state.dismissedAcoustIdWarning = false;
        save(state);
    }

    public synchronized void markCompleted() throws IOException {
        SetupState state = null;
        try {
            state = load();
        } catch (IOException ignored) {
        }
        if (state == null) {
            state = new SetupState();
        }
        state.completed = true;
        state.completedAt = Instant.now().toString();
        save(state);
    }

    public synchronized boolean isAcoustIdWarningDismissed() {
        try {
            SetupState state = load();
            return state != null && state.dismissedAcoustIdWarning;
        } catch (IOException e) {
            return false;
        }
    }

    public synchronized void dismissAcoustIdWarning(AdminCredentialsStore adminStore) throws IOException {
        SetupState state = null;
        try {
            state = load();
        } catch (IOException ignored) {
        }
        if (state == null) {
            state = new SetupState();
            // 老用户尚无状态文件但已处于完成态，忽略横幅时不能把 completed 误置为 false
            state.completed = isCompleted(adminStore);
            if (state.completed) {
                state.completedAt = Instant.now().toString();
            }
        }
        state.dismissedAcoustIdWarning = true;
        save(state);
    }

    public static boolean isAcoustIdPlaceholderOrEmpty(String apiKey) {
        if (apiKey == null) {
            return true;
        }
        String trimmed = apiKey.trim();
        return trimmed.isEmpty() || ACOUSTID_PLACEHOLDER.equalsIgnoreCase(trimmed);
    }

    public static class SetupState {
        public boolean completed;
        public String completedAt;
        public boolean dismissedAcoustIdWarning;
    }
}
