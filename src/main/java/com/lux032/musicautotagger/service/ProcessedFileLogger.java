package com.lux032.musicautotagger.service;

import lombok.extern.slf4j.Slf4j;
import com.lux032.musicautotagger.util.I18nUtil;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ProcessedRecord;
import com.lux032.musicautotagger.util.FileSystemUtils;

/**
 * 已处理文件日志服务 - 支持 SQLite（默认）、MySQL 和 文件模式
 * 用于记录和检查文件是否已被处理,防止重复整理
 */
@Slf4j
public class ProcessedFileLogger {

    private final DatabaseService databaseService;
    private final MusicConfig config;
    private final DateTimeFormatter dateFormatter;
    private final boolean isDbMode;
    /** 数据库模式下是否为 SQLite 方言（否则为 MySQL）。 */
    private final boolean isSqlite;
    // 文件模式读写锁：保护并发读取与原子重写，避免读取到半截文件或过期句柄
    private final ReentrantReadWriteLock fileLock = new ReentrantReadWriteLock();
    // 后加列独立探测：任一 ALTER 失败都不能误判另一列。
    private volatile boolean releaseGroupIdColumnAvailable = false;
    private volatile boolean targetFilePathColumnAvailable = false;
    private volatile boolean folderPathColumnAvailable = false;

    /** 外部（如 DashboardServlet / ProcessedRecordService）查询前先问一下这些列能不能用。 */
    public boolean isReleaseGroupIdColumnAvailable() { return releaseGroupIdColumnAvailable; }
    public boolean isTargetFilePathColumnAvailable() { return targetFilePathColumnAvailable; }
    public boolean isFolderPathColumnAvailable() { return folderPathColumnAvailable; }

    /**
     * 构造函数
     * @param databaseService 数据库服务 (仅在 dbMode 为 sqlite / mysql 时需要)
     */
    public ProcessedFileLogger(MusicConfig config, DatabaseService databaseService) {
        this.config = config;
        this.databaseService = databaseService;
        this.dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        this.isDbMode = databaseService != null && DatabaseService.usesDatabase(config);
        this.isSqlite = isDbMode && databaseService.isSqlite();

        if (isDbMode) {
            log.info(I18nUtil.getMessage("logger.init.mysql").replace("MySQL", databaseService.getDisplayName()));
            ensureReleaseGroupIdColumn();
            ensureTargetFilePathColumn();
            ensureFolderPathColumn();
            if (isSqlite) {
                importLegacyFileLogIfEmpty();
            }
        } else {
            log.info(I18nUtil.getMessage("logger.init.file"), config.getProcessedFileLogPath());
            initLogFile();
        }
    }

    /**
     * 老库里没有 release_group_id 这一列（它是后加的，用于把已处理文件关联到封面缓存）。
     * 这里做一次幂等的在线补列，避免用户必须手动重跑 schema.sql。
     * 补列失败不致命：写入时会自动退回到不带该列的语句。
     */
    private void ensureReleaseGroupIdColumn() {
        if (databaseService == null) {
            return;
        }
        try (Connection conn = databaseService.getConnection()) {
            try (ResultSet rs = conn.getMetaData().getColumns(
                    conn.getCatalog(), null, "processed_files", "release_group_id")) {
                if (rs.next()) {
                    releaseGroupIdColumnAvailable = true;
                    return;
                }
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("ALTER TABLE processed_files "
                    + "ADD COLUMN release_group_id VARCHAR(100) NULL"
                    + (isSqlite ? "" : " COMMENT 'MusicBrainz Release Group ID'"));
                releaseGroupIdColumnAvailable = true;
                log.info("已为 processed_files 补充 release_group_id 列");
            }
        } catch (SQLException e) {
            releaseGroupIdColumnAvailable = false;
            log.warn("processed_files 缺少 release_group_id 列且自动补列失败，仪表板封面将回退到内嵌封面: {}",
                e.getMessage());
        }
    }

    private void ensureTargetFilePathColumn() {
        if (databaseService == null) return;
        try (Connection conn = databaseService.getConnection()) {
            try (ResultSet rs = conn.getMetaData().getColumns(
                    conn.getCatalog(), null, "processed_files", "target_file_path")) {
                if (rs.next()) {
                    targetFilePathColumnAvailable = true;
                    return;
                }
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("ALTER TABLE processed_files "
                    + "ADD COLUMN target_file_path VARCHAR(1000) NULL"
                    + (isSqlite ? "" : " COMMENT '归档目标文件绝对路径'"));
                targetFilePathColumnAvailable = true;
                log.info("已为 processed_files 补充 target_file_path 列");
            }
        } catch (SQLException e) {
            targetFilePathColumnAvailable = false;
            log.warn("processed_files 缺少 target_file_path 列且自动补列失败: {}", e.getMessage());
        }
    }

    /**
     * 为 processed_files 补充持久化的 folder_path 列与索引，并对旧记录分批回填，
     * 使文件夹视图翻页、手风琴展开、批量操作全部走 SQL 而非全表加载到堆内存。
     */
    private void ensureFolderPathColumn() {
        if (databaseService == null) return;
        try (Connection conn = databaseService.getConnection()) {
            boolean existed = false;
            try (ResultSet rs = conn.getMetaData().getColumns(
                    conn.getCatalog(), null, "processed_files", "folder_path")) {
                existed = rs.next();
            }
            if (!existed) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("ALTER TABLE processed_files "
                        + "ADD COLUMN folder_path "
                        + (isSqlite
                            ? "VARCHAR(1000) NULL"
                            : "VARCHAR(768) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '所属专辑根目录路径'"));
                    log.info("已为 processed_files 补充 folder_path 列");
                }
            }
            ensureFolderPathIndex(conn);
            folderPathColumnAvailable = true;
            backfillNullFolderPaths(conn);
        } catch (SQLException e) {
            folderPathColumnAvailable = false;
            log.warn("processed_files 缺少 folder_path 列且自动补列失败，文件夹视图将回退全量内存聚合: {}", e.getMessage());
        }
    }

    private void ensureFolderPathIndex(Connection conn) {
        try {
            if (isSqlite) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_pf_folder_path ON processed_files(folder_path)");
                }
                return;
            }
            boolean hasIndex = false;
            try (ResultSet rs = conn.getMetaData().getIndexInfo(
                    conn.getCatalog(), null, "processed_files", false, false)) {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME");
                    if ("folder_path".equalsIgnoreCase(col)) {
                        hasIndex = true;
                        break;
                    }
                }
            }
            if (!hasIndex) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE INDEX idx_folder_path ON processed_files(folder_path)");
                }
            }
        } catch (SQLException e) {
            log.debug("创建 folder_path 索引跳过（可能已存在）: {}", e.getMessage());
        }
    }

    private void backfillNullFolderPaths(Connection conn) {
        String selectSql = "SELECT id, file_path FROM processed_files "
            + "WHERE folder_path IS NULL OR folder_path = '' LIMIT 1000";
        String updateSql = "UPDATE processed_files SET folder_path = ? WHERE id = ?";
        try {
            while (true) {
                List<Object[]> batch = new ArrayList<>();
                try (PreparedStatement query = conn.prepareStatement(selectSql);
                     ResultSet rs = query.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        String fp = rs.getString("file_path");
                        String folder = computeFolderPath(fp, config);
                        if (folder != null && !folder.isEmpty()) {
                            batch.add(new Object[]{folder, id});
                        }
                    }
                }
                if (batch.isEmpty()) {
                    return;
                }
                boolean autoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try (PreparedStatement upd = conn.prepareStatement(updateSql)) {
                    for (Object[] row : batch) {
                        upd.setString(1, (String) row[0]);
                        upd.setLong(2, (Long) row[1]);
                        upd.addBatch();
                    }
                    upd.executeBatch();
                    conn.commit();
                } catch (SQLException e) {
                    try {
                        conn.rollback();
                    } catch (SQLException rollbackErr) {
                        e.addSuppressed(rollbackErr);
                    }
                    throw e;
                } finally {
                    try {
                        conn.setAutoCommit(autoCommit);
                    } catch (SQLException ignored) {
                    }
                }
                if (batch.size() < 1000) {
                    return;
                }
            }
        } catch (SQLException e) {
            log.warn("回填 processed_files.folder_path 失败: {}", e.getMessage());
        }
    }

    /**
     * 计算文件所属专辑根目录路径（与 ProcessedRecordService.FolderResolver 规则一致：
     * 规范化父目录，若为 Disc 1/CD 2 等碟片子目录则上升一级，不跨越文件系统根或配置根目录）。
     */
    public static String computeFolderPath(String filePath, MusicConfig config) {
        if (filePath == null || filePath.isBlank()) {
            return "";
        }
        File source = new File(filePath);
        File parent = source.getParentFile();
        if (parent == null) {
            return canonicalOrNormalizedPath(source).toString();
        }
        Path parentPath = canonicalOrNormalizedPath(parent);
        Path monitorRoot = resolveConfiguredRootPath(config == null ? null : config.getMonitorDirectory());
        if (monitorRoot != null && parentPath.equals(monitorRoot)) {
            return monitorRoot.toString();
        }
        Path parentNamePath = parentPath.getFileName();
        String parentName = parentNamePath != null ? parentNamePath.toString() : parentPath.toString();
        Path resolved = parentPath;
        if (FileSystemUtils.isDiscFolderName(parentName)) {
            Path grand = parentPath.getParent();
            if (grand != null && grand.getParent() != null) {
                boolean allowed = true;
                if (monitorRoot != null && parentPath.startsWith(monitorRoot)) {
                    allowed = !grand.equals(monitorRoot) && grand.startsWith(monitorRoot);
                } else if (config != null) {
                    for (String rootDir : new String[]{
                        config.getOutputDirectory(), config.getFailedDirectory(), config.getPartialDirectory()
                    }) {
                        Path configuredRoot = resolveConfiguredRootPath(rootDir);
                        if (configuredRoot != null && grand.equals(configuredRoot)) {
                            allowed = false;
                            break;
                        }
                    }
                }
                if (allowed) {
                    resolved = grand;
                }
            }
        }
        return resolved.toString();
    }

    private static Path resolveConfiguredRootPath(String dir) {
        if (dir == null || dir.isBlank()) {
            return null;
        }
        return canonicalOrNormalizedPath(new File(dir));
    }

    private static Path canonicalOrNormalizedPath(File file) {
        try {
            return file.getCanonicalFile().toPath();
        } catch (IOException e) {
            return file.toPath().toAbsolutePath().normalize();
        }
    }

    /**
     * 从旧版默认的文件模式升级到 SQLite 时，把 processed_files.log 中的历史记录一次性导入，
     * 避免切换默认存储后所有文件被重新识别。仅在表为空时执行，原日志文件保持不动。
     */
    private void importLegacyFileLogIfEmpty() {
        String logPath = config.getProcessedFileLogPath();
        if (logPath == null || logPath.isBlank()) return;
        File logFile = new File(logPath);
        if (!logFile.isFile() || logFile.length() == 0) return;

        try (Connection conn = databaseService.getConnection()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM processed_files")) {
                if (rs.next() && rs.getLong(1) > 0) return;
            }
            List<String[]> rows = readLogRows();
            if (rows.isEmpty()) return;

            boolean withFolder = folderPathColumnAvailable;
            String sql = "INSERT OR REPLACE INTO processed_files (file_hash, file_name, file_path, file_size, "
                + "processed_time, recording_id, artist, title, album, release_group_id, target_file_path"
                + (withFolder ? ", folder_path" : "") + ") "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?" + (withFolder ? ", ?" : "") + ")";
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            int imported = 0;
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                for (String[] parts : rows) {
                    File source = new File(parts[0]);
                    // file_hash 仅做记录用途；导入时不逐个读文件算哈希，避免大库启动过慢
                    String hash = "";
                    Timestamp time;
                    try {
                        time = Timestamp.valueOf(LocalDateTime.parse(parts[5], dateFormatter));
                    } catch (RuntimeException e) {
                        time = Timestamp.valueOf(LocalDateTime.now());
                    }
                    pstmt.setString(1, hash);
                    pstmt.setString(2, source.getName());
                    pstmt.setString(3, parts[0]);
                    pstmt.setLong(4, source.isFile() ? source.length() : 0L);
                    pstmt.setTimestamp(5, time);
                    pstmt.setString(6, emptyToNull(parts[1]));
                    pstmt.setString(7, emptyToNull(parts[2]));
                    pstmt.setString(8, emptyToNull(parts[3]));
                    pstmt.setString(9, emptyToNull(parts[4]));
                    pstmt.setString(10, parts.length >= 7 ? emptyToNull(parts[6]) : null);
                    pstmt.setString(11, parts.length >= 8 ? emptyToNull(parts[7]) : null);
                    if (withFolder) {
                        pstmt.setString(12, computeFolderPath(parts[0], config));
                    }
                    pstmt.addBatch();
                    imported++;
                }
                pstmt.executeBatch();
                conn.commit();
                log.info("已从旧文件日志导入 {} 条处理记录到 SQLite: {}", imported, logFile.getAbsolutePath());
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            log.warn("导入旧文件日志到 SQLite 失败（不影响运行）: {}", e.getMessage());
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private void initLogFile() {
        File logFile = new File(config.getProcessedFileLogPath());
        if (!logFile.exists()) {
            try {
                if (logFile.getParentFile() != null) {
                    logFile.getParentFile().mkdirs();
                }
                logFile.createNewFile();
            } catch (IOException e) {
                log.error(I18nUtil.getMessage("logger.create.log.file.failed"), logFile.getAbsolutePath(), e);
            }
        }
    }

    /**
     * 检查文件是否已被处理过
     * 使用文件完整路径作为唯一标识,允许同一首歌在不同位置被分别处理
     * @param file 要检查的文件
     * @return true=已处理过, false=未处理
     */
    public boolean isFileProcessed(File file) {
        String filePath = file.getAbsolutePath();

        if (isDbMode) {
            try {
                String sql = "SELECT recording_id, artist, title, album, processed_time FROM processed_files WHERE file_path = ?";

                try (Connection conn = databaseService.getConnection();
                     PreparedStatement pstmt = conn.prepareStatement(sql)) {

                    pstmt.setString(1, filePath);

                    try (ResultSet rs = pstmt.executeQuery()) {
                        if (rs.next()) {
                            String artist = rs.getString("artist");
                            String title = rs.getString("title");
                            String processedTime = rs.getTimestamp("processed_time").toLocalDateTime().format(dateFormatter);

                            log.debug(I18nUtil.getMessage("logger.file.already.processed.db"),
                                    file.getName(), processedTime, artist, title);
                            return true;
                        }
                    }
                }
                return false;
            } catch (SQLException e) {
                log.error(I18nUtil.getMessage("db.unavailable") + ": {}", e.getMessage());
                throw new RuntimeException("数据库不可用", e);
            }
        } else {
            // 文件模式：扫描 CSV
            return checkFileInLog(filePath);
        }
    }

    private boolean checkFileInLog(String filePath) {
        fileLock.readLock().lock();
        try {
            Path logPath = Path.of(config.getProcessedFileLogPath());
            if (!Files.exists(logPath)) return false;

            try (BufferedReader reader = Files.newBufferedReader(logPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = splitLogLine(line);
                    if (parts.length > 0 && filePath.equals(parts[0])) {
                        log.debug(I18nUtil.getMessage("logger.file.already.processed.log"), filePath);
                        return true;
                    }
                }
            } catch (IOException e) {
                log.error(I18nUtil.getMessage("logger.read.log.failed"), e);
            }
            return false;
        } finally {
            fileLock.readLock().unlock();
        }
    }

    /**
     * 记录文件已处理
     * 使用文件完整路径作为唯一标识,允许同一首歌在不同位置被分别处理
     * @param file 已处理的文件
     * @param recordingId MusicBrainz录音ID
     * @param artist 艺术家
     * @param title 标题
     * @param album 专辑
     */
    public void markFileAsProcessed(File file, String recordingId, String artist, String title, String album) {
        markFileAsProcessed(file, recordingId, artist, title, album, null, null);
    }

    /**
     * 记录文件已处理（带 Release Group ID）
     * releaseGroupId 是封面缓存的 key，仪表板靠它把已处理文件映射到缓存里的封面图。
     */
    public void markFileAsProcessed(File file, String recordingId, String artist, String title,
                                    String album, String releaseGroupId) {
        markFileAsProcessed(file, recordingId, artist, title, album, releaseGroupId, null);
    }

    /**
     * 记录源文件处理历史以及归档目标路径。
     * targetFilePath 仅存目标路径，绝不参与 file_hash、file_name、file_size、file_path
     * 去重键的计算；这些字段始终来自监控目录中的源文件 file。
     */
    public void markFileAsProcessed(File file, String recordingId, String artist, String title,
                                    String album, String releaseGroupId, String targetFilePath) {
        writeProcessedRecord(file, recordingId, artist, title, album, releaseGroupId, targetFilePath, null, false);
    }

    /**
     * 记录文件已处理并指定既有处理时间（供历史分裂记录对账合并保留原归档时间使用）。
     * {@code processedTime} 为 null 时回退为当前时间；兼容 SQLite、MySQL 与文件日志三种模式。
     */
    public void markFileAsProcessed(File file, String recordingId, String artist, String title,
                                    String album, String releaseGroupId, String targetFilePath,
                                    LocalDateTime processedTime) {
        writeProcessedRecord(file, recordingId, artist, title, album, releaseGroupId, targetFilePath, processedTime, false);
    }

    /** 解析 {@link ProcessedRecord#processedTime}（格式 yyyy-MM-dd HH:mm:ss），非法或为空时返回 null。 */
    public LocalDateTime parseProcessedTime(String processedTime) {
        if (processedTime == null || processedTime.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(processedTime.trim(), dateFormatter);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Consumer failure records must report persistence failure to their caller. */
    public void markFailureAsProcessed(File file, String reason) {
        markFailureAsProcessed(file, reason, null);
    }

    /** Consumer failure records with optional preserved archive target path. */
    public void markFailureAsProcessed(File file, String reason, String targetFilePath) {
        writeProcessedRecord(file, "FAILED", reason, file.getName(), "Unknown Album", null, targetFilePath, null, true);
    }

    /**
     * 单条原子记录迁移指令：在一次事务/原子重写中完成目标行 UPSERT，以及无条件/仅限 FAILED 的冗余行清理。
     */
    public record RecordMigration(File upsertFile,
                                  String recordingId,
                                  String artist,
                                  String title,
                                  String album,
                                  String releaseGroupId,
                                  String targetFilePath,
                                  LocalDateTime processedTime,
                                  Collection<String> deletePathsUnconditional,
                                  Collection<String> deletePathsIfFailed) { }

    /**
     * 在单事务（SQLite/MySQL）或单次写锁原子重写（文件模式）中提交一批处理记录迁移。
     * 仅当全部写入与清理均持久化成功时返回 {@code true}；失败时回滚并返回 {@code false}。
     */
    public boolean applyRecordMigrationsAtomically(List<RecordMigration> migrations) {
        if (migrations == null || migrations.isEmpty()) {
            return true;
        }
        if (isDbMode) {
            try (Connection conn = databaseService.getConnection()) {
                boolean autoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    for (RecordMigration m : migrations) {
                        if (m == null) continue;
                        if (m.upsertFile() != null) {
                            executeDbUpsert(conn, m.upsertFile(), m.recordingId(), m.artist(), m.title(),
                                m.album(), m.releaseGroupId(), m.targetFilePath(),
                                m.processedTime() != null ? m.processedTime() : LocalDateTime.now());
                        }
                        if (m.deletePathsUnconditional() != null && !m.deletePathsUnconditional().isEmpty()) {
                            String delSql = "DELETE FROM processed_files WHERE file_path = ?";
                            try (PreparedStatement del = conn.prepareStatement(delSql)) {
                                for (String path : dedupe(m.deletePathsUnconditional())) {
                                    del.setString(1, path);
                                    del.executeUpdate();
                                }
                            }
                        }
                        if (m.deletePathsIfFailed() != null && !m.deletePathsIfFailed().isEmpty()) {
                            String delFailedSql = "DELETE FROM processed_files WHERE file_path = ? AND " + FAILED_SQL;
                            try (PreparedStatement del = conn.prepareStatement(delFailedSql)) {
                                for (String path : dedupe(m.deletePathsIfFailed())) {
                                    del.setString(1, path);
                                    del.executeUpdate();
                                }
                            }
                        }
                    }
                    conn.commit();
                    return true;
                } catch (IOException | SQLException e) {
                    try {
                        conn.rollback();
                    } catch (SQLException rollbackErr) {
                        e.addSuppressed(rollbackErr);
                    }
                    log.error("原子迁移处理记录事务失败，已回滚: {}", e.getMessage(), e);
                    return false;
                } finally {
                    try {
                        conn.setAutoCommit(autoCommit);
                    } catch (SQLException ignored) {
                    }
                }
            } catch (SQLException e) {
                log.error("获取数据库连接执行原子迁移失败: {}", e.getMessage(), e);
                return false;
            }
        }

        // 文件模式：写锁下一次性读取 -> 内存应用全部 upsert / 条件删除 -> 独占临时文件原子替换
        fileLock.writeLock().lock();
        try {
            Path logPath = Path.of(config.getProcessedFileLogPath()).toAbsolutePath().normalize();
            Path parent = logPath.getParent();
            if (parent != null) Files.createDirectories(parent);

            Map<String, String[]> byPath = new LinkedHashMap<>();
            if (Files.isRegularFile(logPath)) {
                try (BufferedReader reader = Files.newBufferedReader(logPath, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = splitLogLine(line);
                        if (parts.length >= 6) {
                            byPath.remove(parts[0]);
                            byPath.put(parts[0], parts);
                        }
                    }
                }
            }

            for (RecordMigration m : migrations) {
                if (m == null) continue;
                if (m.upsertFile() != null) {
                    String fp = m.upsertFile().getAbsolutePath();
                    String absTarget = m.targetFilePath() == null || m.targetFilePath().isBlank()
                        ? "" : new File(m.targetFilePath()).getAbsolutePath();
                    String[] existing = byPath.get(fp);
                    if (absTarget.isEmpty() && existing != null && existing.length >= 8) {
                        absTarget = existing[7];
                    }
                    LocalDateTime ts = m.processedTime() != null ? m.processedTime() : LocalDateTime.now();
                    String[] row = new String[] {
                        fp, nullToEmpty(m.recordingId()), nullToEmpty(m.artist()),
                        nullToEmpty(m.title()), nullToEmpty(m.album()),
                        ts.format(dateFormatter), nullToEmpty(m.releaseGroupId()), absTarget
                    };
                    byPath.remove(fp);
                    byPath.put(fp, row);
                }
                if (m.deletePathsUnconditional() != null) {
                    for (String path : dedupe(m.deletePathsUnconditional())) {
                        byPath.remove(path);
                    }
                }
                if (m.deletePathsIfFailed() != null) {
                    for (String path : dedupe(m.deletePathsIfFailed())) {
                        String[] existing = byPath.get(path);
                        if (existing != null && existing.length >= 2
                                && ProcessedRecord.classify(existing[1]) == ProcessedRecord.Status.FAILED) {
                            byPath.remove(path);
                        }
                    }
                }
            }

            Path temp = Files.createTempFile(parent != null ? parent : Path.of("."), ".processed-migrate-", ".tmp");
            try {
                try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                    for (String[] parts : byPath.values()) {
                        writer.write(formatLogLine(parts));
                        writer.newLine();
                    }
                }
                moveReplaceAtomic(temp, logPath);
                return true;
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            log.error("文件模式原子迁移处理记录失败: {}", e.getMessage(), e);
            return false;
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    private void executeDbUpsert(Connection conn, File file, String recordingId, String artist, String title,
                                 String album, String releaseGroupId, String targetFilePath,
                                 LocalDateTime effectiveTime) throws IOException, SQLException {
        String filePath = file.getAbsolutePath();
        String absoluteTargetPath = targetFilePath == null || targetFilePath.isBlank()
            ? null : new File(targetFilePath).getAbsolutePath();
        boolean sourceReadable = file.isFile();
        String fileHash = sourceReadable ? calculateFileHash(file) : "";
        long fileSize = sourceReadable ? file.length() : 0L;
        boolean withRgid = releaseGroupIdColumnAvailable;
        boolean withTarget = targetFilePathColumnAvailable;
        boolean withFolder = folderPathColumnAvailable;
        String folderPath = withFolder ? computeFolderPath(filePath, config) : null;
        List<String> columns = new ArrayList<>(Arrays.asList(
            "file_hash", "file_name", "file_path", "file_size", "processed_time",
            "recording_id", "artist", "title", "album"));
        if (withRgid) columns.add("release_group_id");
        if (withTarget) columns.add("target_file_path");
        if (withFolder) columns.add("folder_path");
        String values = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
        // MySQL: VALUES(col)；SQLite: excluded.col
        java.util.function.UnaryOperator<String> newVal = isSqlite
            ? c -> "excluded." + c
            : c -> "VALUES(" + c + ")";
        // 离线更新（源文件不可读）时不覆盖已有的 file_hash / file_size，保留原监控源度量值
        List<String> updateCols = sourceReadable
            ? List.of("file_hash", "file_name", "file_size", "processed_time", "recording_id", "artist", "title", "album")
            : List.of("file_name", "processed_time", "recording_id", "artist", "title", "album");
        StringBuilder update = new StringBuilder();
        for (String c : updateCols) {
            if (update.length() > 0) update.append(", ");
            update.append(c).append(" = ").append(newVal.apply(c));
        }
        if (withRgid) update.append(", release_group_id = ").append(newVal.apply("release_group_id"));
        if (withTarget) update.append(", target_file_path = COALESCE(")
            .append(newVal.apply("target_file_path")).append(", target_file_path)");
        if (withFolder) update.append(", folder_path = ").append(newVal.apply("folder_path"));
        if (isSqlite) update.append(", updated_at = CURRENT_TIMESTAMP");
        String sql = "INSERT INTO processed_files (" + String.join(", ", columns) + ") VALUES ("
            + values + ")"
            + (isSqlite ? " ON CONFLICT(file_path) DO UPDATE SET " : " ON DUPLICATE KEY UPDATE ")
            + update;

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, fileHash);
            pstmt.setString(2, file.getName());
            pstmt.setString(3, filePath);
            pstmt.setLong(4, fileSize);
            pstmt.setTimestamp(5, Timestamp.valueOf(effectiveTime));
            pstmt.setString(6, recordingId);
            pstmt.setString(7, artist);
            pstmt.setString(8, title);
            pstmt.setString(9, album);
            int index = 10;
            if (withRgid) pstmt.setString(index++, releaseGroupId);
            if (withTarget) pstmt.setString(index++, absoluteTargetPath);
            if (withFolder) pstmt.setString(index, folderPath);
            pstmt.executeUpdate();
        }
    }

    private void writeProcessedRecord(File file, String recordingId, String artist, String title,
                                     String album, String releaseGroupId, String targetFilePath,
                                     LocalDateTime processedTime, boolean strict) {
        String filePath = file.getAbsolutePath();
        String absoluteTargetPath = targetFilePath == null || targetFilePath.isBlank()
            ? null : new File(targetFilePath).getAbsolutePath();
        LocalDateTime effectiveTime = processedTime != null ? processedTime : LocalDateTime.now();

        if (isDbMode) {
            try (Connection conn = databaseService.getConnection()) {
                executeDbUpsert(conn, file, recordingId, artist, title, album,
                    releaseGroupId, absoluteTargetPath, effectiveTime);
            } catch (IOException | SQLException e) {
                if (strict) throw new IllegalStateException("FAILED record persistence failed", e);
                log.error(I18nUtil.getMessage("logger.db.record.failed"), e);
            }
        } else {
            // 文件模式: UTF-8 编码 + 分隔符/换行符转义 + 写锁
            fileLock.writeLock().lock();
            try {
                Path logPath = Path.of(config.getProcessedFileLogPath());
                Path parent = logPath.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                try (BufferedWriter writer = Files.newBufferedWriter(
                        logPath, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    String timeStr = effectiveTime.format(dateFormatter);
                    // 格式: filePath|recordingId|artist|title|album|time|releaseGroupId|targetFilePath
                    // 第 7、8 列均为后加字段，读取方按长度向后兼容。
                    String line = formatLogLine(filePath, nullToEmpty(recordingId), nullToEmpty(artist),
                        nullToEmpty(title), nullToEmpty(album), timeStr, nullToEmpty(releaseGroupId),
                        nullToEmpty(absoluteTargetPath));
                    writer.write(line);
                    writer.newLine();
                }
            } catch (IOException e) {
                if (strict) throw new IllegalStateException("FAILED record persistence failed", e);
                log.error(I18nUtil.getMessage("logger.write.log.failed"), e);
            } finally {
                fileLock.writeLock().unlock();
            }
        }

        log.info(I18nUtil.getMessage("logger.history.recorded"), artist, title,
            isDbMode ? databaseService.getDisplayName() : I18nUtil.getMessage("logger.file.mode"));
    }

    // ==================== 封面回填支持 ====================

    /**
     * 记录里这些 recording_id 不是真正的 MusicBrainz 录音 ID，而是失败/特殊流程的占位值，
     * 这些条目本来就没有认出专辑，回填时直接跳过。
     *
     * 注意：ONLINE_SEARCH 不在此列——它是联网搜索确认成功的正常产物（专辑名/艺术家均已确定，
     * 只是没有 MusicBrainz recording_id），理应可以按专辑名回填 release_group_id 来获取封面。
     */
    private static final java.util.Set<String> NON_MB_RECORDING_IDS = java.util.Set.of(
        "FAILED", "UNKNOWN", "WRITE_FAILED", "EXCEPTION", "CUE_SPLIT", "REVIEW_REJECTED");

    /** 一个待回填的专辑分组。 */
    public static class AlbumGroup {
        public final String album;
        /** 整张专辑只有单一艺术家时才有值；合辑类的为 null，搜索时不限定艺术家。 */
        public final String artist;
        public final int fileCount;

        public AlbumGroup(String album, String artist, int fileCount) {
            this.album = album;
            this.artist = artist;
            this.fileCount = fileCount;
        }
    }

    private static boolean isBackfillable(String recordingId, String album) {
        if (album == null || album.isBlank() || "Unknown Album".equalsIgnoreCase(album.trim())) {
            return false;
        }
        return recordingId == null || !NON_MB_RECORDING_IDS.contains(recordingId.trim());
    }

    /**
     * 找出所有还没有 release_group_id 的历史记录，按 (album, artist) 分组，
     * 避免不同艺人的同名专辑（如 "Greatest Hits"）混为一组互相污染。
     */
    public java.util.List<AlbumGroup> findAlbumsMissingReleaseGroupId() {
        java.util.List<AlbumGroup> groups = new java.util.ArrayList<>();

        if (isDbMode) {
            if (databaseService == null || !releaseGroupIdColumnAvailable) {
                return groups;
            }
            String placeholders = NON_MB_RECORDING_IDS.stream()
                .map(x -> "?").collect(java.util.stream.Collectors.joining(", "));
            String sql = "SELECT album, COALESCE(artist, '') AS group_artist, COUNT(*) AS file_count "
                + "FROM processed_files "
                + "WHERE (release_group_id IS NULL OR release_group_id = '') "
                + "AND album IS NOT NULL AND album <> '' "
                + "AND (recording_id IS NULL OR recording_id NOT IN (" + placeholders + ")) "
                + "GROUP BY album, COALESCE(artist, '')";
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                int idx = 1;
                for (String sentinel : NON_MB_RECORDING_IDS) {
                    pstmt.setString(idx++, sentinel);
                }
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        String album = rs.getString("album");
                        if (!isBackfillable(null, album)) {
                            continue;
                        }
                        String rawArtist = rs.getString("group_artist");
                        String artist = (rawArtist == null || rawArtist.isBlank()) ? null : rawArtist;
                        groups.add(new AlbumGroup(album, artist, rs.getInt("file_count")));
                    }
                }
            } catch (SQLException e) {
                log.error("查询待回填专辑失败", e);
            }
            return groups;
        }

        // 文件模式：扫一遍日志按 (album, artist) 分组
        java.util.Map<String, int[]> counts = new java.util.LinkedHashMap<>();
        java.util.Map<String, String[]> keys = new java.util.HashMap<>();
        for (String[] parts : readLogRows()) {
            if (parts.length >= 7 && !parts[6].isBlank()) {
                continue; // 已有 rgid
            }
            String album = parts[4];
            if (!isBackfillable(parts[1], album)) {
                continue;
            }
            String artist = parts[2] == null || parts[2].isBlank() ? "" : parts[2];
            String groupKey = album + "\u0000" + artist;
            counts.computeIfAbsent(groupKey, k -> new int[1])[0]++;
            keys.putIfAbsent(groupKey, new String[]{album, artist.isEmpty() ? null : artist});
        }
        for (java.util.Map.Entry<String, int[]> entry : counts.entrySet()) {
            String[] pair = keys.get(entry.getKey());
            groups.add(new AlbumGroup(pair[0], pair[1], entry.getValue()[0]));
        }
        return groups;
    }

    /**
     * 把某张专辑下所有缺失 release_group_id 的记录补上（不限定艺术家，供旧调用兼容）。
     * @return 实际更新的行数
     */
    public int applyReleaseGroupId(String album, String releaseGroupId) {
        return applyReleaseGroupIdInternal(album, null, false, releaseGroupId);
    }

    /**
     * 把指定 (album, artist) 下所有缺失 release_group_id 的记录补上，避免污染其他艺人的同名专辑。
     * @return 实际更新的行数
     */
    public int applyReleaseGroupId(String album, String artist, String releaseGroupId) {
        return applyReleaseGroupIdInternal(album, artist, true, releaseGroupId);
    }

    private int applyReleaseGroupIdInternal(String album, String artist, boolean filterArtist, String releaseGroupId) {
        if (album == null || album.isBlank() || releaseGroupId == null || releaseGroupId.isBlank()) {
            return 0;
        }
        boolean hasArtist = artist != null && !artist.isBlank();

        if (isDbMode) {
            if (databaseService == null || !releaseGroupIdColumnAvailable) {
                return 0;
            }
            // 占位值 recording_id 的行本来就没认出专辑，不给它们贴 ID（与文件模式保持一致）
            String placeholders = NON_MB_RECORDING_IDS.stream()
                .map(x -> "?").collect(java.util.stream.Collectors.joining(", "));
            String artistClause = !filterArtist ? ""
                : (hasArtist ? "AND artist = ? " : "AND (artist IS NULL OR artist = '') ");
            String sql = "UPDATE processed_files SET release_group_id = ? "
                + "WHERE album = ? " + artistClause
                + "AND (release_group_id IS NULL OR release_group_id = '') "
                + "AND (recording_id IS NULL OR recording_id NOT IN (" + placeholders + "))";
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                int index = 1;
                pstmt.setString(index++, releaseGroupId);
                pstmt.setString(index++, album);
                if (filterArtist && hasArtist) {
                    pstmt.setString(index++, artist);
                }
                for (String sentinel : NON_MB_RECORDING_IDS) {
                    pstmt.setString(index++, sentinel);
                }
                return pstmt.executeUpdate();
            } catch (SQLException e) {
                log.error("回填 release_group_id 失败 (album={}, artist={})", album, artist, e);
                return 0;
            }
        }

        // 文件模式：原子式重写整个日志（写锁 + UTF-8 + 字段转义）
        fileLock.writeLock().lock();
        try {
            Path logPath = Path.of(config.getProcessedFileLogPath());
            if (!Files.exists(logPath)) {
                return 0;
            }
            Path tempPath = logPath.resolveSibling(logPath.getFileName() + ".backfill.tmp");
            int updated = 0;
            try {
                try (BufferedReader reader = Files.newBufferedReader(logPath, StandardCharsets.UTF_8);
                     BufferedWriter writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = splitLogLine(line);
                        boolean artistMatches = parts.length >= 6 && (!filterArtist
                            || (hasArtist ? artist.equals(parts[2]) : (parts[2] == null || parts[2].isBlank())));
                        boolean needsFill = parts.length >= 6
                            && album.equals(parts[4])
                            && artistMatches
                            && (parts.length < 7 || parts[6].isBlank())
                            && isBackfillable(parts[1], parts[4]);
                        if (needsFill) {
                            String[] updatedParts = Arrays.copyOf(parts, Math.max(parts.length, 7));
                            updatedParts[6] = releaseGroupId;
                            writer.write(formatLogLine(updatedParts));
                            updated++;
                        } else {
                            writer.write(line);
                        }
                        writer.newLine();
                    }
                }
                moveReplaceAtomic(tempPath, logPath);
                return updated;
            } catch (IOException e) {
                log.error("重写/替换已处理日志失败", e);
                return 0;
            } finally {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException ignored) {
                }
            }
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    private static String normalizeDirPrefix(String prefix) {
        String abs = new File(prefix).getAbsolutePath();
        String sep = File.separator;
        while (abs.length() > sep.length() && abs.endsWith(sep)) {
            abs = abs.substring(0, abs.length() - sep.length());
        }
        return abs;
    }

    /**
     * 恢复工作区原子提交后，把临时目标路径批量换成最终输出路径。
     * 按完整路径或「前缀 + 路径分隔符」精确匹配，避免 /Aimer/Dawn 误伤 /Aimer/Dawn (LE)；
     * 使用 Unicode 码点数替代 UTF-16 length()，防止含 emoji/生僻字的路径截断错位。
     */
    public void rebaseTargetPaths(String oldPrefix, String newPrefix) {
        if (oldPrefix == null || newPrefix == null) return;
        String oldAbsolute = normalizeDirPrefix(oldPrefix);
        String newAbsolute = normalizeDirPrefix(newPrefix);
        String sep = File.separator;
        String oldWithSep = oldAbsolute.endsWith(sep) ? oldAbsolute : oldAbsolute + sep;
        String newWithSep = newAbsolute.endsWith(sep) ? newAbsolute : newAbsolute + sep;
        if (isDbMode) {
            if (!targetFilePathColumnAvailable) return;
            int cpOldWithSep = oldWithSep.codePointCount(0, oldWithSep.length());
            String sql = isSqlite
                ? "UPDATE processed_files SET target_file_path = CASE "
                    + "WHEN target_file_path = ? THEN ? "
                    + "ELSE ? || substr(target_file_path, ?) END "
                    + "WHERE target_file_path IS NOT NULL AND (target_file_path = ? OR substr(target_file_path, 1, ?) = ?)"
                : "UPDATE processed_files SET target_file_path = CASE "
                    + "WHEN BINARY target_file_path = BINARY ? THEN ? "
                    + "ELSE CONCAT(?, SUBSTRING(target_file_path, ?)) END "
                    + "WHERE target_file_path IS NOT NULL AND (BINARY target_file_path = BINARY ? OR BINARY LEFT(target_file_path, ?) = BINARY ?)";
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, oldAbsolute);
                pstmt.setString(2, newAbsolute);
                pstmt.setString(3, newWithSep);
                pstmt.setInt(4, cpOldWithSep + 1);
                pstmt.setString(5, oldAbsolute);
                pstmt.setInt(6, cpOldWithSep);
                pstmt.setString(7, oldWithSep);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                log.error("重定向归档目标路径失败: {} -> {}", oldAbsolute, newAbsolute, e);
            }
            return;
        }
        rewriteTargetPaths(path -> {
            if (path.equals(oldAbsolute)) return newAbsolute;
            if (path.startsWith(oldWithSep)) return newWithSep + path.substring(oldWithSep.length());
            return path;
        });
    }

    /** Update exactly one media path across any records that point to it. */
    void relocateTargetPath(Path source, Path target) throws IOException {
        if (isDbMode) {
            if (!targetFilePathColumnAvailable) return;
            String sql = "UPDATE processed_files SET target_file_path = ? WHERE "
                + (isSqlite ? "target_file_path = ?" : "BINARY target_file_path = BINARY ?");
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement statement = conn.prepareStatement(sql)) {
                statement.setString(1, target.toString());
                statement.setString(2, source.toString());
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new IOException("Unable to update archive path", e);
            }
        } else {
            rewriteTargetPathsStrict(path -> path.equals(source.toString()) ? target.toString() : path);
        }
    }

    /** 清除已不存在的临时目标路径，供恢复提交失败回滚使用。 */
    public void clearTargetPathsUnder(String prefix) {
        if (prefix == null) return;
        String absolute = normalizeDirPrefix(prefix);
        String sep = File.separator;
        String withSep = absolute.endsWith(sep) ? absolute : absolute + sep;
        if (isDbMode) {
            if (!targetFilePathColumnAvailable) return;
            int cpWithSep = withSep.codePointCount(0, withSep.length());
            String sql = isSqlite
                ? "UPDATE processed_files SET target_file_path = NULL "
                    + "WHERE target_file_path IS NOT NULL AND (target_file_path = ? OR substr(target_file_path, 1, ?) = ?)"
                : "UPDATE processed_files SET target_file_path = NULL "
                    + "WHERE target_file_path IS NOT NULL AND (BINARY target_file_path = BINARY ? OR BINARY LEFT(target_file_path, ?) = BINARY ?)";
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, absolute);
                pstmt.setInt(2, cpWithSep);
                pstmt.setString(3, withSep);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                log.error("清除无效归档目标路径失败: {}", absolute, e);
            }
            return;
        }
        rewriteTargetPaths(path -> (path.equals(absolute) || path.startsWith(withSep)) ? "" : path);
    }

    private void rewriteTargetPaths(java.util.function.UnaryOperator<String> mapper) {
        try {
            rewriteTargetPathsStrict(mapper);
        } catch (IOException e) {
            log.error("更新目标路径日志失败", e);
        }
    }

    private void rewriteTargetPathsStrict(java.util.function.UnaryOperator<String> mapper) throws IOException {
        fileLock.writeLock().lock();
        try {
            Path logPath = Path.of(config.getProcessedFileLogPath()).toAbsolutePath();
            if (!Files.exists(logPath)) return;
            Path parent = logPath.getParent();
            Path temp = Files.createTempFile(parent != null ? parent : Path.of("."), ".processed-targets-", ".tmp");
            try {
                var permissions = Files.getFileAttributeView(logPath, java.nio.file.attribute.PosixFileAttributeView.class);
                if (permissions != null) Files.setPosixFilePermissions(temp, permissions.readAttributes().permissions());
                boolean changed = false;
                try (BufferedReader reader = Files.newBufferedReader(logPath, StandardCharsets.UTF_8);
                     BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = splitLogLine(line);
                        if (parts.length >= 8 && !parts[7].isBlank()) {
                            String path = mapper.apply(parts[7]);
                            if (!parts[7].equals(path)) {
                                changed = true;
                                parts[7] = path;
                                writer.write(formatLogLine(parts));
                                writer.newLine();
                                continue;
                            }
                        }
                        writer.write(line);
                        writer.newLine();
                    }
                }
                if (changed) moveReplaceAtomic(temp, logPath);
            } finally {
                Files.deleteIfExists(temp);
            }
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    /** 对文件模式日志单列进行最小转义（仅转义 \\、|、\\r、\\n，保持普通路径与旧日志格式兼容）。 */
    static String escapeLogField(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '|' -> sb.append("\\|");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String formatLogLine(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(escapeLogField(fields[i]));
        }
        return sb.toString();
    }

    /** 拆分文件模式日志行，仅在未转义的 | 处切分并还原 \\\\、\\|、\\n、\\r。 */
    static String[] splitLogLine(String line) {
        List<String> parts = new ArrayList<>(8);
        StringBuilder field = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                char next = line.charAt(i + 1);
                switch (next) {
                    case '\\' -> { field.append('\\'); i++; }
                    case '|' -> { field.append('|'); i++; }
                    case 'n' -> { field.append('\n'); i++; }
                    case 'r' -> { field.append('\r'); i++; }
                    default -> field.append(c); // 兼容旧日志中未转义的 Windows 路径反斜杠
                }
            } else if (c == '|') {
                parts.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        parts.add(field.toString());
        return parts.toArray(new String[0]);
    }

    private static void moveReplaceAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 读取文件模式日志的所有行（已拆列，读锁 + UTF-8）。 */
    private java.util.List<String[]> readLogRows() {
        fileLock.readLock().lock();
        try {
            java.util.List<String[]> rows = new java.util.ArrayList<>();
            Path logPath = Path.of(config.getProcessedFileLogPath());
            if (!Files.exists(logPath)) {
                return rows;
            }
            try (BufferedReader reader = Files.newBufferedReader(logPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = splitLogLine(line);
                    if (parts.length >= 6) {
                        rows.add(parts);
                    }
                }
            } catch (IOException e) {
                log.error(I18nUtil.getMessage("logger.read.log.failed"), e);
            }
            return rows;
        } finally {
            fileLock.readLock().unlock();
        }
    }

    /**
     * 删除指定路径的已处理记录，供用户主动重新识别时使用。
     * 文件模式会原子式重写日志；DB 模式按 file_path 删除。
     */
    public void removeProcessedRecord(File file) {
        if (file == null) {
            return;
        }
        String filePath = file.getAbsolutePath();
        if (isDbMode) {
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement("DELETE FROM processed_files WHERE file_path = ?")) {
                pstmt.setString(1, filePath);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("删除已处理记录失败", e);
            }
            return;
        }

        fileLock.writeLock().lock();
        try {
            Path logPath = Path.of(config.getProcessedFileLogPath());
            if (!Files.exists(logPath)) {
                return;
            }
            Path tempPath = logPath.resolveSibling(logPath.getFileName() + ".rewrite.tmp");
            try {
                try (BufferedReader reader = Files.newBufferedReader(logPath, StandardCharsets.UTF_8);
                     BufferedWriter writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = splitLogLine(line);
                        if (parts.length == 0 || !filePath.equals(parts[0])) {
                            writer.write(line);
                            writer.newLine();
                        }
                    }
                }
                moveReplaceAtomic(tempPath, logPath);
            } catch (IOException e) {
                throw new RuntimeException("重写/替换已处理日志失败", e);
            } finally {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException ignored) {
                }
            }
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    // ==================== 处理记录管理（搜索 / 分页 / 批量删除） ====================

    /** LIKE 转义字符：不用反斜杠，因为 MySQL 字符串字面量里反斜杠本身还要再转义一次，两种方言写法不一致。 */
    private static final char LIKE_ESCAPE = '!';
    private static final int IN_BATCH = 500;

    private static String sqlList(Set<String> values) {
        return values.stream().sorted().map(v -> "'" + v + "'")
            .collect(java.util.stream.Collectors.joining(", "));
    }

    private static final String FAILED_SQL = "recording_id IN (" + sqlList(ProcessedRecord.FAILED_IDS) + ")";
    private static final String OTHER_SQL = "recording_id IN (" + sqlList(ProcessedRecord.OTHER_IDS) + ")";
    private static final String SUCCESS_SQL = "(recording_id IS NULL OR recording_id NOT IN ("
        + sqlList(ProcessedRecord.FAILED_IDS) + ", " + sqlList(ProcessedRecord.OTHER_IDS) + "))";

    private static String likePattern(String keyword) {
        StringBuilder sb = new StringBuilder("%");
        for (char c : keyword.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == LIKE_ESCAPE || c == '%' || c == '_') sb.append(LIKE_ESCAPE);
            sb.append(c);
        }
        return sb.append('%').toString();
    }

    /** 关键字 + 专辑条件（不含状态）。 */
    private String baseWhere(ProcessedRecordQuery q, List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        String keyword = q.normalizedKeyword();
        if (keyword != null) {
            String pattern = likePattern(keyword);
            where.append(" AND (");
            String[] cols = {"file_path", "artist", "title", "album"};
            for (int i = 0; i < cols.length; i++) {
                if (i > 0) where.append(" OR ");
                where.append("LOWER(").append(cols[i]).append(") LIKE ? ESCAPE '").append(LIKE_ESCAPE).append("'");
                params.add(pattern);
            }
            where.append(")");
        }
        String album = q.normalizedAlbum();
        if (album != null) {
            where.append(" AND album = ?");
            params.add(album);
        }
        return where.toString();
    }

    private static String statusSql(ProcessedRecord.Status status) {
        if (status == null) return null;
        switch (status) {
            case FAILED: return FAILED_SQL;
            case OTHER: return OTHER_SQL;
            default: return SUCCESS_SQL;
        }
    }

    private String orderBy(ProcessedRecordQuery.Sort sort) {
        String nc = isSqlite ? " COLLATE NOCASE" : "";
        switch (sort) {
            case TIME_ASC: return " ORDER BY processed_time ASC, file_path ASC";
            case ALBUM: return " ORDER BY album" + nc + " ASC, file_path ASC";
            case ARTIST: return " ORDER BY artist" + nc + " ASC, album" + nc + " ASC, file_path ASC";
            case TITLE: return " ORDER BY title" + nc + " ASC, file_path ASC";
            case PATH: return " ORDER BY file_path ASC";
            default: return " ORDER BY processed_time DESC, file_path ASC";
        }
    }

    private static void bind(PreparedStatement pstmt, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof Integer) pstmt.setInt(i + 1, (Integer) p);
            else pstmt.setString(i + 1, (String) p);
        }
    }

    private String selectColumns() {
        return "file_path, file_name, recording_id, artist, title, album, processed_time"
            + (releaseGroupIdColumnAvailable ? ", release_group_id" : "")
            + (targetFilePathColumnAvailable ? ", target_file_path" : "")
            + (folderPathColumnAvailable ? ", folder_path" : "");
    }

    private ProcessedRecord mapRow(ResultSet rs) throws SQLException {
        ProcessedRecord r = new ProcessedRecord();
        r.filePath = rs.getString("file_path");
        r.fileName = rs.getString("file_name");
        r.recordingId = rs.getString("recording_id");
        r.artist = rs.getString("artist");
        r.title = rs.getString("title");
        r.album = rs.getString("album");
        Timestamp ts = rs.getTimestamp("processed_time");
        r.processedTime = ts == null ? null : ts.toLocalDateTime().format(dateFormatter);
        if (releaseGroupIdColumnAvailable) r.releaseGroupId = emptyToNull(rs.getString("release_group_id"));
        if (targetFilePathColumnAvailable) r.targetFilePath = emptyToNull(rs.getString("target_file_path"));
        if (folderPathColumnAvailable) r.folderPath = emptyToNull(rs.getString("folder_path"));
        r.status = ProcessedRecord.classify(r.recordingId);
        return r;
    }

    /**
     * 分页查询处理记录。SQLite / MySQL 走 SQL，文件模式在内存中过滤排序。
     */
    public ProcessedRecordQuery.Page queryRecords(ProcessedRecordQuery q) {
        ProcessedRecordQuery.Page page = new ProcessedRecordQuery.Page();
        page.offset = q.safeOffset();
        page.limit = q.safeLimit();

        if (!isDbMode) {
            List<ProcessedRecord> base = filterFileRecords(q);
            fillCounts(page.counts, base);
            List<ProcessedRecord> filtered = base.stream()
                .filter(r -> q.status == null || r.status == q.status)
                .sorted(fileComparator(q.sort))
                .toList();
            page.total = filtered.size();
            page.items = new ArrayList<>(filtered.subList(
                Math.min(page.offset, filtered.size()),
                Math.min(page.offset + page.limit, filtered.size())));
            return page;
        }

        try (Connection conn = databaseService.getConnection()) {
            List<Object> params = new ArrayList<>();
            String where = baseWhere(q, params);
            String countSql = "SELECT COUNT(*) AS all_count, "
                + "SUM(CASE WHEN " + FAILED_SQL + " THEN 1 ELSE 0 END) AS failed_count, "
                + "SUM(CASE WHEN " + OTHER_SQL + " THEN 1 ELSE 0 END) AS other_count "
                + "FROM processed_files" + where;
            try (PreparedStatement pstmt = conn.prepareStatement(countSql)) {
                bind(pstmt, params);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        long all = rs.getLong("all_count");
                        long failed = rs.getLong("failed_count");
                        long other = rs.getLong("other_count");
                        page.counts.put("ALL", all);
                        page.counts.put("FAILED", failed);
                        page.counts.put("OTHER", other);
                        page.counts.put("SUCCESS", all - failed - other);
                    }
                }
            }
            page.total = page.counts.getOrDefault(q.status == null ? "ALL" : q.status.name(), 0L);

            String status = statusSql(q.status);
            String sql = "SELECT " + selectColumns() + " FROM processed_files" + where
                + (status != null ? " AND " + status : "")
                + orderBy(q.sort) + " LIMIT ? OFFSET ?";
            List<Object> pageParams = new ArrayList<>(params);
            pageParams.add(page.limit);
            pageParams.add(page.offset);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                bind(pstmt, pageParams);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) page.items.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询处理记录失败: " + e.getMessage(), e);
        }
        return page;
    }

    /**
     * 流式读取符合条件的记录（不将 10 万条记录一次性全部加载到 JVM 堆内存，用于流式 CSV 导出）。
     * @return 实际流出的记录数
     */
    public int streamRecords(ProcessedRecordQuery q, int max, java.util.function.Consumer<ProcessedRecord> consumer) {
        if (consumer == null || max <= 0) {
            return 0;
        }
        if (!isDbMode) {
            int[] count = {0};
            filterFileRecords(q).stream()
                .filter(r -> q.status == null || r.status == q.status)
                .sorted(fileComparator(q.sort))
                .limit(max)
                .forEach(r -> {
                    consumer.accept(r);
                    count[0]++;
                });
            return count[0];
        }
        int emitted = 0;
        List<Object> params = new ArrayList<>();
        String where = baseWhere(q, params);
        String status = statusSql(q.status);
        String sql = "SELECT " + selectColumns() + " FROM processed_files" + where
            + (status != null ? " AND " + status : "") + orderBy(q.sort) + " LIMIT ?";
        params.add(max);
        try (Connection conn = databaseService.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            bind(pstmt, params);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    consumer.accept(mapRow(rs));
                    emitted++;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("流式查询处理记录失败: " + e.getMessage(), e);
        }
        return emitted;
    }

    /**
     * 返回符合条件的所有记录（不分页，最多 max 条），用于「选择全部匹配结果」的批量操作与导出。
     */
    public List<ProcessedRecord> findRecords(ProcessedRecordQuery q, int max) {
        List<ProcessedRecord> result = new ArrayList<>();
        streamRecords(q, max, result::add);
        return result;
    }

    /**
     * 加载全部处理记录（供按文件夹聚合查询使用，兼容 SQLite / MySQL / 文件模式）。
     */
    public List<ProcessedRecord> loadAllRecords() {
        if (!isDbMode) {
            return readFileRecords();
        }
        List<ProcessedRecord> result = new ArrayList<>();
        String sql = "SELECT " + selectColumns() + " FROM processed_files";
        try (Connection conn = databaseService.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                result.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("加载全部处理记录失败: " + e.getMessage(), e);
        }
        return result;
    }

    /** 按路径精确查找记录（不存在的路径会被忽略）。 */
    public List<ProcessedRecord> findRecordsByPaths(Collection<String> paths) {
        List<ProcessedRecord> result = new ArrayList<>();
        if (paths == null || paths.isEmpty()) return result;
        Set<String> wanted = dedupe(paths);

        if (!isDbMode) {
            for (ProcessedRecord r : readFileRecords()) {
                if (wanted.contains(r.filePath)) result.add(r);
            }
            return result;
        }
        List<String> list = new ArrayList<>(wanted);
        try (Connection conn = databaseService.getConnection()) {
            for (int i = 0; i < list.size(); i += IN_BATCH) {
                List<String> chunk = list.subList(i, Math.min(i + IN_BATCH, list.size()));
                String sql = "SELECT " + selectColumns() + " FROM processed_files WHERE file_path IN ("
                    + String.join(", ", java.util.Collections.nCopies(chunk.size(), "?")) + ")";
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    for (int j = 0; j < chunk.size(); j++) pstmt.setString(j + 1, chunk.get(j));
                    try (ResultSet rs = pstmt.executeQuery()) {
                        while (rs.next()) result.add(mapRow(rs));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询处理记录失败: " + e.getMessage(), e);
        }
        return result;
    }

    /** 保持输入顺序、去掉空值的去重集合。 */
    private static Set<String> dedupe(Collection<String> values) {
        Set<String> set = new java.util.LinkedHashSet<>();
        for (String v : values) if (v != null && !v.isEmpty()) set.add(v);
        return set;
    }

    /**
     * 查找源文件对应的既有成功归档目标路径（按 file_path 精确匹配）。
     */
    public String findTargetPath(File file) {
        if (file == null) {
            return null;
        }
        String filePath = file.getAbsolutePath();
        if (!isDbMode) {
            String found = null;
            for (ProcessedRecord r : readFileRecords()) {
                if (filePath.equals(r.filePath)
                    && r.status == ProcessedRecord.Status.SUCCESS
                    && r.targetFilePath != null && !r.targetFilePath.isBlank()) {
                    found = r.targetFilePath;
                }
            }
            return found;
        }
        if (!targetFilePathColumnAvailable) {
            return null;
        }
        try (Connection conn = databaseService.getConnection()) {
            String byPath = "SELECT recording_id, target_file_path FROM processed_files "
                + "WHERE file_path = ? AND target_file_path IS NOT NULL";
            try (PreparedStatement pstmt = conn.prepareStatement(byPath)) {
                pstmt.setString(1, filePath);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next() && ProcessedRecord.classify(rs.getString("recording_id")) == ProcessedRecord.Status.SUCCESS) {
                        return emptyToNull(rs.getString("target_file_path"));
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("查询既有归档目标路径失败 ({}): {}", file.getName(), e.getMessage());
        }
        return null;
    }

    /** 按持久化 folder_path 列表批量查询处理记录（避免全表 loadAllRecords 载入堆内存）。 */
    public List<ProcessedRecord> findRecordsByFolderPaths(Collection<String> folderPaths, int max) {
        List<ProcessedRecord> result = new ArrayList<>();
        if (folderPaths == null || folderPaths.isEmpty() || max <= 0) return result;
        Set<String> wanted = dedupe(folderPaths);
        if (wanted.isEmpty()) return result;
        if (!isDbMode || !folderPathColumnAvailable) {
            return null;
        }
        List<String> list = new ArrayList<>(wanted);
        try (Connection conn = databaseService.getConnection()) {
            for (int i = 0; i < list.size() && result.size() < max; i += IN_BATCH) {
                List<String> chunk = list.subList(i, Math.min(i + IN_BATCH, list.size()));
                int remaining = max - result.size();
                String sql = "SELECT " + selectColumns() + " FROM processed_files WHERE folder_path IN ("
                    + String.join(", ", java.util.Collections.nCopies(chunk.size(), "?")) + ") "
                    + "ORDER BY file_path ASC LIMIT ?";
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    for (int j = 0; j < chunk.size(); j++) pstmt.setString(j + 1, chunk.get(j));
                    pstmt.setInt(chunk.size() + 1, remaining);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        while (rs.next()) {
                            result.add(mapRow(rs));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("按文件夹查询处理记录失败: " + e.getMessage(), e);
        }
        return result;
    }

    /** SQL 文件夹分页查询结果（仅含当前页文件夹路径、总数、状态计数与匹配文件数）。 */
    public static final class FolderSqlPage {
        public final List<String> folderPaths = new ArrayList<>();
        public final Map<String, Long> counts = new HashMap<>();
        public long total;
        public long totalFiles;
    }

    private static final String FOLDER_SUCCESS_PRED =
        "(failed_cnt = 0 AND (file_count - failed_cnt - other_cnt) > 0 AND rejected_cnt = 0)";

    private String buildFolderAggSubquery(ProcessedRecordQuery q, List<Object> params) {
        StringBuilder sql = new StringBuilder(
            "SELECT folder_path, "
            + "COUNT(*) AS file_count, "
            + "MAX(processed_time) AS latest_time, "
            + "SUM(CASE WHEN " + FAILED_SQL + " THEN 1 ELSE 0 END) AS failed_cnt, "
            + "SUM(CASE WHEN " + OTHER_SQL + " THEN 1 ELSE 0 END) AS other_cnt, "
            + "SUM(CASE WHEN recording_id = 'REVIEW_REJECTED' THEN 1 ELSE 0 END) AS rejected_cnt, "
            + "MIN(CASE WHEN " + SUCCESS_SQL
                + " AND album IS NOT NULL AND TRIM(album) <> '' AND LOWER(TRIM(album)) <> 'unknown album' "
                + "THEN album END) AS sort_album, "
            + "MIN(CASE WHEN " + SUCCESS_SQL
                + " AND artist IS NOT NULL AND TRIM(artist) <> '' AND LOWER(TRIM(artist)) <> 'unknown artist' "
                + "THEN artist END) AS sort_artist "
            + "FROM processed_files "
            + "WHERE folder_path IS NOT NULL AND folder_path <> '' "
            + "GROUP BY folder_path");

        List<String> havingClauses = new ArrayList<>();
        String album = q == null ? null : q.normalizedAlbum();
        if (album != null) {
            havingClauses.add("SUM(CASE WHEN album = ? THEN 1 ELSE 0 END) > 0");
            params.add(album);
        }
        String keyword = q == null ? null : q.normalizedKeyword();
        if (keyword != null) {
            String pattern = likePattern(keyword);
            String esc = " ESCAPE '" + LIKE_ESCAPE + "'";
            havingClauses.add("SUM(CASE WHEN LOWER(folder_path) LIKE ?" + esc
                + " OR LOWER(file_path) LIKE ?" + esc
                + " OR LOWER(artist) LIKE ?" + esc
                + " OR LOWER(title) LIKE ?" + esc
                + " OR LOWER(album) LIKE ?" + esc
                + " THEN 1 ELSE 0 END) > 0");
            for (int i = 0; i < 5; i++) {
                params.add(pattern);
            }
        }
        if (!havingClauses.isEmpty()) {
            sql.append(" HAVING ").append(String.join(" AND ", havingClauses));
        }
        return sql.toString();
    }

    private static String folderStatusFilterSql(ProcessedRecord.Status status) {
        if (status == null) return "1=1";
        switch (status) {
            case FAILED: return "failed_cnt > 0";
            case SUCCESS: return FOLDER_SUCCESS_PRED;
            default: return "NOT (failed_cnt > 0) AND NOT " + FOLDER_SUCCESS_PRED;
        }
    }

    private static String folderOrderBySql(ProcessedRecordQuery.Sort sort) {
        ProcessedRecordQuery.Sort effective = sort == null ? ProcessedRecordQuery.Sort.TIME_DESC : sort;
        switch (effective) {
            case TIME_ASC:
                return " ORDER BY CASE WHEN latest_time IS NULL THEN 1 ELSE 0 END ASC, latest_time ASC, LOWER(folder_path) ASC";
            case ALBUM:
                return " ORDER BY CASE WHEN sort_album IS NULL THEN 1 ELSE 0 END ASC, LOWER(sort_album) ASC, LOWER(folder_path) ASC";
            case ARTIST:
                return " ORDER BY CASE WHEN sort_artist IS NULL THEN 1 ELSE 0 END ASC, LOWER(sort_artist) ASC, "
                    + "CASE WHEN sort_album IS NULL THEN 1 ELSE 0 END ASC, LOWER(sort_album) ASC, LOWER(folder_path) ASC";
            case TITLE:
            case PATH:
                return " ORDER BY LOWER(folder_path) ASC";
            default:
                return " ORDER BY CASE WHEN latest_time IS NULL THEN 1 ELSE 0 END ASC, latest_time DESC, LOWER(folder_path) ASC";
        }
    }

    /**
     * 基于持久化 folder_path 列的 SQL 文件夹分页查询：不将整表载入内存。
     */
    public FolderSqlPage queryFolderPageSql(ProcessedRecordQuery q) {
        if (!isDbMode || !folderPathColumnAvailable) {
            return null;
        }
        FolderSqlPage page = new FolderSqlPage();
        ProcessedRecord.Status status = q == null ? null : q.status;
        int limit = q == null ? 50 : q.safeLimit();
        int offset = q == null ? 0 : q.safeOffset();
        ProcessedRecordQuery.Sort sort = q == null ? ProcessedRecordQuery.Sort.TIME_DESC : q.sort;

        try (Connection conn = databaseService.getConnection()) {
            List<Object> params = new ArrayList<>();
            String agg = buildFolderAggSubquery(q, params);
            String matchedPred = folderStatusFilterSql(status);

            String countSql = "SELECT COUNT(*) AS all_folders, "
                + "COALESCE(SUM(CASE WHEN failed_cnt > 0 THEN 1 ELSE 0 END), 0) AS failed_folders, "
                + "COALESCE(SUM(CASE WHEN " + FOLDER_SUCCESS_PRED + " THEN 1 ELSE 0 END), 0) AS success_folders, "
                + "COALESCE(SUM(CASE WHEN " + matchedPred + " THEN file_count ELSE 0 END), 0) AS matched_files "
                + "FROM (" + agg + ") agg";
            try (PreparedStatement pstmt = conn.prepareStatement(countSql)) {
                bind(pstmt, params);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        long all = rs.getLong("all_folders");
                        long failed = rs.getLong("failed_folders");
                        long success = rs.getLong("success_folders");
                        long other = Math.max(0L, all - failed - success);
                        page.counts.put("ALL", all);
                        page.counts.put("SUCCESS", success);
                        page.counts.put("FAILED", failed);
                        page.counts.put("OTHER", other);
                        page.totalFiles = rs.getLong("matched_files");
                    }
                }
            }
            page.total = page.counts.getOrDefault(status == null ? "ALL" : status.name(), 0L);
            if (page.total == 0L) {
                return page;
            }

            String pageSql = "SELECT folder_path FROM (" + agg + ") agg WHERE " + matchedPred
                + folderOrderBySql(sort) + " LIMIT ? OFFSET ?";
            List<Object> pageParams = new ArrayList<>(params);
            pageParams.add(limit);
            pageParams.add(offset);
            try (PreparedStatement pstmt = conn.prepareStatement(pageSql)) {
                bind(pstmt, pageParams);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        page.folderPaths.add(rs.getString("folder_path"));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("按文件夹分页查询处理记录失败: " + e.getMessage(), e);
        }
        return page;
    }

    /** 基于持久化 folder_path 列的 SQL 文件夹路径列表查询（供批量操作与导出使用）。 */
    public List<String> findFolderPathsSql(ProcessedRecordQuery q, int maxFolders) {
        if (!isDbMode || !folderPathColumnAvailable) {
            return null;
        }
        List<String> folders = new ArrayList<>();
        ProcessedRecord.Status status = q == null ? null : q.status;
        ProcessedRecordQuery.Sort sort = q == null ? ProcessedRecordQuery.Sort.TIME_DESC : q.sort;
        try (Connection conn = databaseService.getConnection()) {
            List<Object> params = new ArrayList<>();
            String agg = buildFolderAggSubquery(q, params);
            String sql = "SELECT folder_path FROM (" + agg + ") agg WHERE "
                + folderStatusFilterSql(status) + folderOrderBySql(sort) + " LIMIT ?";
            params.add(maxFolders);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                bind(pstmt, params);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        folders.add(rs.getString("folder_path"));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("按条件查询文件夹列表失败: " + e.getMessage(), e);
        }
        return folders;
    }

    /**
     * 批量删除处理记录（按记录中保存的原始路径精确匹配）。
     * DB 模式将全部分批 DELETE 包在同一事务中，避免中途失败留下半删脏态。
     * @return 实际被删除的记录数（文件模式下按不同路径计数）
     */
    public int removeProcessedRecords(Collection<String> paths) {
        if (paths == null || paths.isEmpty()) return 0;
        Set<String> targets = dedupe(paths);
        if (targets.isEmpty()) return 0;

        if (isDbMode) {
            List<String> list = new ArrayList<>(targets);
            int removed = 0;
            try (Connection conn = databaseService.getConnection()) {
                boolean autoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    for (int i = 0; i < list.size(); i += IN_BATCH) {
                        List<String> chunk = list.subList(i, Math.min(i + IN_BATCH, list.size()));
                        String sql = "DELETE FROM processed_files WHERE file_path IN ("
                            + String.join(", ", java.util.Collections.nCopies(chunk.size(), "?")) + ")";
                        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                            for (int j = 0; j < chunk.size(); j++) pstmt.setString(j + 1, chunk.get(j));
                            removed += pstmt.executeUpdate();
                        }
                    }
                    conn.commit();
                } catch (SQLException e) {
                    try {
                        conn.rollback();
                    } catch (SQLException rollbackErr) {
                        e.addSuppressed(rollbackErr);
                    }
                    throw e;
                } finally {
                    try {
                        conn.setAutoCommit(autoCommit);
                    } catch (SQLException ignored) {
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("删除处理记录失败: " + e.getMessage(), e);
            }
            return removed;
        }

        fileLock.writeLock().lock();
        try {
            Path logPath = Path.of(config.getProcessedFileLogPath());
            if (!Files.exists(logPath)) return 0;
            Path tempPath = logPath.resolveSibling(logPath.getFileName() + ".remove.tmp");
            Set<String> removedPaths = new HashSet<>();
            try {
                try (BufferedReader reader = Files.newBufferedReader(logPath, StandardCharsets.UTF_8);
                     BufferedWriter writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = splitLogLine(line);
                        String path = parts.length > 0 ? parts[0] : "";
                        if (targets.contains(path)) {
                            removedPaths.add(path);
                            continue;
                        }
                        writer.write(line);
                        writer.newLine();
                    }
                }
                moveReplaceAtomic(tempPath, logPath);
                return removedPaths.size();
            } catch (IOException e) {
                throw new RuntimeException("重写/替换已处理日志失败", e);
            } finally {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException ignored) {
                }
            }
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    /** 文件模式：读取全部记录，同一路径以最后一次写入为准（与数据库 UPSERT 语义一致）。 */
    private List<ProcessedRecord> readFileRecords() {
        Map<String, ProcessedRecord> byPath = new LinkedHashMap<>();
        for (String[] parts : readLogRows()) {
            ProcessedRecord r = new ProcessedRecord();
            r.filePath = parts[0];
            r.fileName = new File(parts[0]).getName();
            r.recordingId = emptyToNull(parts[1]);
            r.artist = emptyToNull(parts[2]);
            r.title = emptyToNull(parts[3]);
            r.album = emptyToNull(parts[4]);
            r.processedTime = emptyToNull(parts[5]);
            r.releaseGroupId = parts.length >= 7 ? emptyToNull(parts[6]) : null;
            r.targetFilePath = parts.length >= 8 ? emptyToNull(parts[7]) : null;
            r.status = ProcessedRecord.classify(r.recordingId);
            byPath.remove(r.filePath);
            byPath.put(r.filePath, r);
        }
        return new ArrayList<>(byPath.values());
    }

    private List<ProcessedRecord> filterFileRecords(ProcessedRecordQuery q) {
        String keyword = q.normalizedKeyword();
        String kw = keyword == null ? null : keyword.toLowerCase(Locale.ROOT);
        String album = q.normalizedAlbum();
        List<ProcessedRecord> result = new ArrayList<>();
        for (ProcessedRecord r : readFileRecords()) {
            if (album != null && !album.equals(r.album)) continue;
            if (kw != null && !(containsIgnoreCase(r.filePath, kw) || containsIgnoreCase(r.artist, kw)
                    || containsIgnoreCase(r.title, kw) || containsIgnoreCase(r.album, kw))) {
                continue;
            }
            result.add(r);
        }
        return result;
    }

    private static boolean containsIgnoreCase(String value, String lowerKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerKeyword);
    }

    private static void fillCounts(Map<String, Long> counts, List<ProcessedRecord> records) {
        long failed = 0, other = 0;
        for (ProcessedRecord r : records) {
            if (r.status == ProcessedRecord.Status.FAILED) failed++;
            else if (r.status == ProcessedRecord.Status.OTHER) other++;
        }
        counts.put("ALL", (long) records.size());
        counts.put("FAILED", failed);
        counts.put("OTHER", other);
        counts.put("SUCCESS", records.size() - failed - other);
    }

    private static Comparator<ProcessedRecord> fileComparator(ProcessedRecordQuery.Sort sort) {
        Comparator<String> text = Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER);
        Comparator<String> time = Comparator.nullsFirst(Comparator.<String>naturalOrder());
        Comparator<ProcessedRecord> byPath = Comparator.comparing(r -> r.filePath);
        switch (sort) {
            case TIME_ASC: return Comparator.comparing((ProcessedRecord r) -> r.processedTime, time).thenComparing(byPath);
            case ALBUM: return Comparator.comparing((ProcessedRecord r) -> r.album, text).thenComparing(byPath);
            case ARTIST: return Comparator.comparing((ProcessedRecord r) -> r.artist, text)
                .thenComparing(r -> r.album, text).thenComparing(byPath);
            case TITLE: return Comparator.comparing((ProcessedRecord r) -> r.title, text).thenComparing(byPath);
            case PATH: return byPath;
            default: return Comparator.comparing((ProcessedRecord r) -> r.processedTime, time).reversed()
                .thenComparing(byPath);
        }
    }

    /**
     * 计算文件MD5哈希值
     */
    private String calculateFileHash(File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");

            // 对于大文件,只读取前1MB和最后1MB来计算哈希(性能优化)
            long fileSize = file.length();
            int sampleSize = 1024 * 1024; // 1MB

            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[sampleSize];

                // 读取前1MB
                int bytesRead = fis.read(buffer);
                if (bytesRead > 0) {
                    md.update(buffer, 0, bytesRead);
                }

                // 如果文件大于2MB,跳到末尾读取最后1MB
                if (fileSize > sampleSize * 2) {
                    fis.getChannel().position(fileSize - sampleSize);
                    bytesRead = fis.read(buffer);
                    if (bytesRead > 0) {
                        md.update(buffer, 0, bytesRead);
                    }
                }
            }

            // 同时考虑文件大小和路径名(避免同名但内容不同的文件)
            md.update(String.valueOf(fileSize).getBytes());
            md.update(file.getName().getBytes());

            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();

        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5算法不可用", e);
        }
    }

    /**
     * 获取处理记录统计
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();

        if (isDbMode) {
            try (Connection conn = databaseService.getConnection()) {
                // ... (原有MySQL统计逻辑保持不变)
                String countSQL = "SELECT COUNT(*) as total FROM processed_files";
                try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(countSQL)) {
                    if (rs.next()) stats.put("totalProcessed", rs.getLong("total"));
                }
                stats.put("databaseType", databaseService.getDisplayName());
            } catch (SQLException e) {
                log.error("获取统计信息失败", e);
            }
        } else {
            stats.put("databaseType", "File");
            fileLock.readLock().lock();
            try {
                Path logPath = Path.of(config.getProcessedFileLogPath());
                if (Files.exists(logPath)) {
                    try (BufferedReader reader = Files.newBufferedReader(logPath, StandardCharsets.UTF_8)) {
                        long lines = reader.lines().count();
                        stats.put("totalProcessed", lines);
                    } catch (IOException e) {
                        log.error("读取日志文件统计失败", e);
                        stats.put("totalProcessed", 0);
                    }
                } else {
                    stats.put("totalProcessed", 0);
                }
            } finally {
                fileLock.readLock().unlock();
            }
        }

        return stats;
    }

    /**
     * 最近 days 天每天处理的文件数，按日期从旧到新排列，最后一个元素是今天。
     * 只在数据库模式下可用（文件模式没有可靠的时间索引），不可用时返回空列表。
     * 按天聚合放在 Java 侧，避免 MySQL / SQLite 日期函数不一致。
     */
    public List<Integer> getDailyCounts(int days) {
        if (!isDbMode || days <= 0) {
            return List.of();
        }
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate first = today.minusDays(days - 1L);
        int[] counts = new int[days];
        String sql = "SELECT processed_time FROM processed_files WHERE processed_time >= ?";
        try (Connection conn = databaseService.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setTimestamp(1, Timestamp.valueOf(first.atStartOfDay()));
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp(1);
                    if (ts == null) continue;
                    long idx = java.time.temporal.ChronoUnit.DAYS.between(first, ts.toLocalDateTime().toLocalDate());
                    if (idx >= 0 && idx < days) counts[(int) idx]++;
                }
            }
        } catch (SQLException e) {
            log.warn("获取每日处理统计失败: {}", e.getMessage());
            return List.of();
        }
        List<Integer> result = new ArrayList<>(days);
        for (int c : counts) result.add(c);
        return result;
    }

    /**
     * 清理旧的日志记录
     * @param daysToKeep 保留最近多少天的记录
     */
    public void cleanupOldRecords(int daysToKeep) {
        if (isDbMode) {
            // 截止时间在 Java 侧计算，兼容 MySQL / SQLite
            String sql = "DELETE FROM processed_files WHERE processed_time < ?";
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now().minusDays(daysToKeep)));
                int deletedCount = pstmt.executeUpdate();
                if (deletedCount > 0) {
                    log.info(I18nUtil.getMessage("logger.cleanup.old.records"), deletedCount);
                }
            } catch (SQLException e) {
                log.error("清理旧记录失败", e);
            }
        } else {
            // 文件模式暂不支持清理 (或以后实现)
            log.info(I18nUtil.getMessage("logger.cleanup.not.supported"));
        }
    }

    /**
     * 关闭服务
     * 注意: 不再关闭数据源,因为数据源由DatabaseService统一管理
     */
    public void close() {
        log.info(I18nUtil.getMessage("logger.service.closed"));
    }
}
