package com.lux032.musicautotagger.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class AdminCredentialsStore {

    private final Path path;
    private final Gson gson;

    public AdminCredentialsStore(Path path) {
        this.path = path;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public static AdminCredentialsStore defaultStore() {
        return new AdminCredentialsStore(Path.of("data", "admin.json"));
    }

    public boolean exists() {
        return Files.exists(path);
    }

    public AdminCredentials load() throws IOException {
        if (!exists()) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            AdminCredentials creds = gson.fromJson(reader, AdminCredentials.class);
            if (creds == null || creds.username == null || creds.passwordHash == null) {
                return null;
            }
            return creds;
        }
    }

    public void save(AdminCredentials creds) throws IOException {
        if (creds == null) {
            throw new IllegalArgumentException("Credentials required");
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tempPath)) {
            gson.toJson(creds, writer);
        }

        try {
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static class AdminCredentials {
        public String username;
        public String passwordHash;
        public String updatedAt;

        public AdminCredentials() {
        }

        public AdminCredentials(String username, String passwordHash, String updatedAt) {
            this.username = username;
            this.passwordHash = passwordHash;
            this.updatedAt = updatedAt;
        }
    }
}

