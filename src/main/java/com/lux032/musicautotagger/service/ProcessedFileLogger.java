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
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ProcessedRecord;

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
    // 关键修复：添加文件写入锁，解决并发写入日志文件的线程安全问题
    private final Object fileWriteLock = new Object();
    // 两个后加列必须独立探测：任一 ALTER 失败都不能误判另一列。
    private volatile boolean releaseGroupIdColumnAvailable = false;
    private volatile boolean targetFilePathColumnAvailable = false;

    /** 外部（如 DashboardServlet）查询前先问一下这个列能不能用。 */
    public boolean isReleaseGroupIdColumnAvailable() { return releaseGroupIdColumnAvailable; }
    public boolean isTargetFilePathColumnAvailable() { return targetFilePathColumnAvailable; }

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
            if (isSqlite) {
                importLegacyFileLogIfEmpty();
            }
        } else {
            log.info(I18nUtil.getMessage("logger.init.file"), config.getProcessedFileLogPath());
            initLogFile();
        }
        // 历史记录的全量目录扫描回填暂不在启动时执行；
        // 新处理的专辑仍会在正常写入链路中持久化 target_file_path。
        // backfillMissingTargetPaths();
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

            String sql = "INSERT OR REPLACE INTO processed_files (file_hash, file_name, file_path, file_size, "
                + "processed_time, recording_id, artist, title, album, release_group_id, target_file_path) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
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
        File logFile = new File(config.getProcessedFileLogPath());
        if (!logFile.exists()) return false;

        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 简单格式: filePath|recordingId|...
                if (line.startsWith(filePath + "|")) {
                    log.debug(I18nUtil.getMessage("logger.file.already.processed.log"), filePath);
                    return true;
                }
            }
        } catch (IOException e) {
            log.error(I18nUtil.getMessage("logger.read.log.failed"), e);
        }
        return false;
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
        writeProcessedRecord(file, recordingId, artist, title, album, releaseGroupId, targetFilePath, false);
    }

    /** Consumer failure records must report persistence failure to their caller. */
    public void markFailureAsProcessed(File file, String reason) {
        writeProcessedRecord(file, "FAILED", reason, file.getName(), "Unknown Album", null, null, true);
    }

    private void writeProcessedRecord(File file, String recordingId, String artist, String title,
                                     String album, String releaseGroupId, String targetFilePath, boolean strict) {
        String filePath = file.getAbsolutePath();
        String absoluteTargetPath = targetFilePath == null || targetFilePath.isBlank()
            ? null : new File(targetFilePath).getAbsolutePath();
        LocalDateTime now = LocalDateTime.now();

        if (isDbMode) {
            try {
                String fileHash = calculateFileHash(file);
                boolean withRgid = releaseGroupIdColumnAvailable;
                boolean withTarget = targetFilePathColumnAvailable;
                List<String> columns = new ArrayList<>(Arrays.asList(
                    "file_hash", "file_name", "file_path", "file_size", "processed_time",
                    "recording_id", "artist", "title", "album"));
                if (withRgid) columns.add("release_group_id");
                if (withTarget) columns.add("target_file_path");
                String values = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
                // MySQL: VALUES(col)；SQLite: excluded.col
                java.util.function.UnaryOperator<String> newVal = isSqlite
                    ? c -> "excluded." + c
                    : c -> "VALUES(" + c + ")";
                StringBuilder update = new StringBuilder();
                for (String c : List.of("file_hash", "file_name", "file_size", "processed_time",
                        "recording_id", "artist", "title", "album")) {
                    if (update.length() > 0) update.append(", ");
                    update.append(c).append(" = ").append(newVal.apply(c));
                }
                if (withRgid) update.append(", release_group_id = ").append(newVal.apply("release_group_id"));
                if (withTarget) update.append(", target_file_path = COALESCE(")
                    .append(newVal.apply("target_file_path")).append(", target_file_path)");
                if (isSqlite) update.append(", updated_at = CURRENT_TIMESTAMP");
                String sql = "INSERT INTO processed_files (" + String.join(", ", columns) + ") VALUES ("
                    + values + ")"
                    + (isSqlite ? " ON CONFLICT(file_path) DO UPDATE SET " : " ON DUPLICATE KEY UPDATE ")
                    + update;

                try (Connection conn = databaseService.getConnection();
                     PreparedStatement pstmt = conn.prepareStatement(sql)) {

                    pstmt.setString(1, fileHash);
                    pstmt.setString(2, file.getName());
                    pstmt.setString(3, filePath);
                    pstmt.setLong(4, file.length());
                    pstmt.setTimestamp(5, Timestamp.valueOf(now));
                    pstmt.setString(6, recordingId);
                    pstmt.setString(7, artist);
                    pstmt.setString(8, title);
                    pstmt.setString(9, album);
                    int index = 10;
                    if (withRgid) pstmt.setString(index++, releaseGroupId);
                    if (withTarget) pstmt.setString(index, absoluteTargetPath);

                    pstmt.executeUpdate();
                }
            } catch (IOException | SQLException e) {
                if (strict) throw new IllegalStateException("FAILED record persistence failed", e);
                log.error(I18nUtil.getMessage("logger.db.record.failed"), e);
            }
        } else {
            // 文件模式: 追加写入（使用同步锁保证线程安全）
            synchronized (fileWriteLock) {
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(config.getProcessedFileLogPath(), true))) {
                    String timeStr = now.format(dateFormatter);
                    // 格式: filePath|recordingId|artist|title|album|time|releaseGroupId|targetFilePath
                    // 第 7、8 列均为后加字段，读取方按长度向后兼容。
                    String line = String.join("|", filePath, nullToEmpty(recordingId), nullToEmpty(artist),
                        nullToEmpty(title), nullToEmpty(album), timeStr, nullToEmpty(releaseGroupId),
                        nullToEmpty(absoluteTargetPath));
                    writer.write(line);
                    writer.newLine();
                } catch (IOException e) {
                    if (strict) throw new IllegalStateException("FAILED record persistence failed", e);
                    log.error(I18nUtil.getMessage("logger.write.log.failed"), e);
                }
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
     * 找出所有还没有 release_group_id 的历史记录，按专辑名分组。
     * 按专辑而不是按文件分组，是为了把 MusicBrainz 请求数从「文件数」降到「专辑数」。
     */
    public java.util.List<AlbumGroup> findAlbumsMissingReleaseGroupId() {
        java.util.List<AlbumGroup> groups = new java.util.ArrayList<>();

        if (isDbMode) {
            if (databaseService == null || !releaseGroupIdColumnAvailable) {
                return groups;
            }
            String sql = "SELECT album, MIN(artist) AS one_artist, COUNT(*) AS file_count, "
                + "COUNT(DISTINCT artist) AS artist_count, MIN(recording_id) AS one_recording "
                + "FROM processed_files "
                + "WHERE (release_group_id IS NULL OR release_group_id = '') "
                + "AND album IS NOT NULL AND album <> '' "
                + "GROUP BY album";
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String album = rs.getString("album");
                    if (!isBackfillable(rs.getString("one_recording"), album)) {
                        continue;
                    }
                    String artist = rs.getInt("artist_count") == 1 ? rs.getString("one_artist") : null;
                    groups.add(new AlbumGroup(album, artist, rs.getInt("file_count")));
                }
            } catch (SQLException e) {
                log.error("查询待回填专辑失败", e);
            }
            return groups;
        }

        // 文件模式：扫一遍日志自己分组
        java.util.Map<String, int[]> counts = new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.Set<String>> artists = new java.util.HashMap<>();
        for (String[] parts : readLogRows()) {
            if (parts.length >= 7 && !parts[6].isBlank()) {
                continue; // 已有 rgid
            }
            String album = parts[4];
            if (!isBackfillable(parts[1], album)) {
                continue;
            }
            counts.computeIfAbsent(album, k -> new int[1])[0]++;
            artists.computeIfAbsent(album, k -> new java.util.HashSet<>()).add(parts[2]);
        }
        for (java.util.Map.Entry<String, int[]> entry : counts.entrySet()) {
            java.util.Set<String> albumArtists = artists.get(entry.getKey());
            String artist = albumArtists != null && albumArtists.size() == 1
                ? albumArtists.iterator().next() : null;
            groups.add(new AlbumGroup(entry.getKey(), artist, entry.getValue()[0]));
        }
        return groups;
    }

    /**
     * 把某张专辑下所有缺失 release_group_id 的记录补上。
     * @return 实际更新的行数
     */
    public int applyReleaseGroupId(String album, String releaseGroupId) {
        if (album == null || album.isBlank() || releaseGroupId == null || releaseGroupId.isBlank()) {
            return 0;
        }

        if (isDbMode) {
            if (databaseService == null || !releaseGroupIdColumnAvailable) {
                return 0;
            }
            // 占位值 recording_id 的行本来就没认出专辑，不给它们贴 ID（与文件模式保持一致）
            String placeholders = NON_MB_RECORDING_IDS.stream()
                .map(x -> "?").collect(java.util.stream.Collectors.joining(", "));
            String sql = "UPDATE processed_files SET release_group_id = ? "
                + "WHERE album = ? AND (release_group_id IS NULL OR release_group_id = '') "
                + "AND (recording_id IS NULL OR recording_id NOT IN (" + placeholders + "))";
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, releaseGroupId);
                pstmt.setString(2, album);
                int index = 3;
                for (String sentinel : NON_MB_RECORDING_IDS) {
                    pstmt.setString(index++, sentinel);
                }
                return pstmt.executeUpdate();
            } catch (SQLException e) {
                log.error("回填 release_group_id 失败 (album={})", album, e);
                return 0;
            }
        }

        // 文件模式：原子式重写整个日志
        synchronized (fileWriteLock) {
            File logFile = new File(config.getProcessedFileLogPath());
            if (!logFile.exists()) {
                return 0;
            }
            File tempFile = new File(logFile.getAbsolutePath() + ".backfill.tmp");
            int updated = 0;
            try (BufferedReader reader = new BufferedReader(new FileReader(logFile));
                 BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\|", -1);
                    boolean needsFill = parts.length >= 6
                        && album.equals(parts[4])
                        && (parts.length < 7 || parts[6].isBlank())
                        && isBackfillable(parts[1], parts[4]);
                    if (needsFill) {
                        String[] updatedParts = Arrays.copyOf(parts, Math.max(parts.length, 7));
                        updatedParts[6] = releaseGroupId;
                        writer.write(String.join("|", updatedParts));
                        updated++;
                    } else {
                        writer.write(line);
                    }
                    writer.newLine();
                }
            } catch (IOException e) {
                log.error("重写已处理日志失败", e);
                tempFile.delete();
                return 0;
            }
            try {
                java.nio.file.Files.move(tempFile.toPath(), logFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.error("替换已处理日志失败", e);
                return 0;
            }
            return updated;
        }
    }

    /**
     * 恢复工作区原子提交后，把临时目标路径批量换成最终输出路径。
     * 文件模式按纯前缀处理；MySQL 使用 LEFT 精确比较，避免 LIKE 中 %, _, \\ 的转义陷阱。
     */
    public void rebaseTargetPaths(String oldPrefix, String newPrefix) {
        if (oldPrefix == null || newPrefix == null) return;
        String oldAbsolute = new File(oldPrefix).getAbsolutePath();
        String newAbsolute = new File(newPrefix).getAbsolutePath();
        if (isDbMode) {
            if (!targetFilePathColumnAvailable) return;
            String sql = isSqlite
                ? "UPDATE processed_files SET target_file_path = ? || substr(target_file_path, ?) "
                    + "WHERE target_file_path IS NOT NULL AND substr(target_file_path, 1, ?) = ?"
                : "UPDATE processed_files SET target_file_path = CONCAT(?, SUBSTRING(target_file_path, ?)) "
                    + "WHERE target_file_path IS NOT NULL AND LEFT(target_file_path, ?) = ?";
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, newAbsolute);
                pstmt.setInt(2, oldAbsolute.length() + 1);
                pstmt.setInt(3, oldAbsolute.length());
                pstmt.setString(4, oldAbsolute);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                log.error("重定向归档目标路径失败: {} -> {}", oldAbsolute, newAbsolute, e);
            }
            return;
        }
        rewriteTargetPaths(path -> path.startsWith(oldAbsolute)
            ? newAbsolute + path.substring(oldAbsolute.length()) : path);
    }

    /** 清除已不存在的临时目标路径，供恢复提交失败回滚使用。 */
    public void clearTargetPathsUnder(String prefix) {
        if (prefix == null) return;
        String absolute = new File(prefix).getAbsolutePath();
        if (isDbMode) {
            if (!targetFilePathColumnAvailable) return;
            String sql = "UPDATE processed_files SET target_file_path = NULL "
                + "WHERE target_file_path IS NOT NULL AND "
                + (isSqlite ? "substr(target_file_path, 1, ?)" : "LEFT(target_file_path, ?)") + " = ?";
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, absolute.length());
                pstmt.setString(2, absolute);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                log.error("清除无效归档目标路径失败: {}", absolute, e);
            }
            return;
        }
        rewriteTargetPaths(path -> path.startsWith(absolute) ? "" : path);
    }

    private void rewriteTargetPaths(java.util.function.UnaryOperator<String> mapper) {
        synchronized (fileWriteLock) {
            File logFile = new File(config.getProcessedFileLogPath());
            if (!logFile.exists()) return;
            File tempFile = new File(logFile.getAbsolutePath() + ".targets.tmp");
            try (BufferedReader reader = new BufferedReader(new FileReader(logFile));
                 BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\|", -1);
                    if (parts.length >= 8 && !parts[7].isBlank()) parts[7] = mapper.apply(parts[7]);
                    writer.write(String.join("|", parts));
                    writer.newLine();
                }
            } catch (IOException e) {
                tempFile.delete();
                log.error("重写目标路径失败", e);
                return;
            }
            try {
                Files.move(tempFile.toPath(), logFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                tempFile.delete();
                log.error("替换目标路径日志失败", e);
            }
        }
    }

    /** 启动时为历史记录寻找输出目录中已归档的音频文件并持久化目标路径。 */
    private void backfillMissingTargetPaths() {
        String output = config.getOutputDirectory();
        if (output == null || output.isBlank()) return;
        Path outputRoot = Path.of(output).toAbsolutePath();
        if (!Files.isDirectory(outputRoot)) return;
        if (isDbMode) backfillDbTargetPaths(outputRoot); else backfillFileTargetPaths(outputRoot);
    }

    private void backfillDbTargetPaths(Path outputRoot) {
        if (!targetFilePathColumnAvailable) return;
        String placeholders = NON_MB_RECORDING_IDS.stream().map(x -> "?")
            .collect(java.util.stream.Collectors.joining(", "));
        String sql = "SELECT file_path, album, title FROM processed_files WHERE target_file_path IS NULL "
            + "AND (recording_id IS NULL OR recording_id NOT IN (" + placeholders + "))";
        try (Connection conn = databaseService.getConnection();
             PreparedStatement query = conn.prepareStatement(sql)) {
            int index = 1;
            for (String sentinel : NON_MB_RECORDING_IDS) query.setString(index++, sentinel);
            List<String[]> updates = new ArrayList<>();
            try (ResultSet rs = query.executeQuery()) {
                while (rs.next()) {
                    String target = findArchivedAudio(outputRoot, rs.getString("album"),
                        rs.getString("file_path"), rs.getString("title"));
                    if (target != null) updates.add(new String[]{target, rs.getString("file_path")});
                }
            }
            try (PreparedStatement update = conn.prepareStatement(
                    "UPDATE processed_files SET target_file_path = ? WHERE file_path = ?")) {
                for (String[] row : updates) {
                    update.setString(1, row[0]); update.setString(2, row[1]); update.addBatch();
                }
                if (!updates.isEmpty()) update.executeBatch();
            }
        } catch (SQLException e) {
            log.warn("历史目标路径自愈回填失败: {}", e.getMessage());
        }
    }

    private void backfillFileTargetPaths(Path outputRoot) {
        synchronized (fileWriteLock) {
            File logFile = new File(config.getProcessedFileLogPath());
            File tempFile = new File(logFile.getAbsolutePath() + ".backfill-target.tmp");
            try (BufferedReader reader = new BufferedReader(new FileReader(logFile));
                 BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\|", -1);
                    parts = Arrays.copyOf(parts, Math.max(parts.length, 8));
                    for (int i = 0; i < parts.length; i++) if (parts[i] == null) parts[i] = "";
                    if (parts[7].isBlank() && isBackfillable(parts[1], parts[4])) {
                        String target = findArchivedAudio(outputRoot, parts[4], parts[0], parts[3]);
                        if (target != null) parts[7] = target;
                    }
                    writer.write(String.join("|", parts)); writer.newLine();
                }
            } catch (IOException e) {
                tempFile.delete();
                log.warn("文件日志历史目标路径自愈回填失败: {}", e.getMessage());
                return;
            }
            try {
                Files.move(tempFile.toPath(), logFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                tempFile.delete();
                log.warn("替换历史目标路径日志失败: {}", e.getMessage());
            }
        }
    }

    private String findArchivedAudio(Path outputRoot, String album, String sourcePath, String title) {
        if (album == null || album.isBlank()) return null;
        String albumDir = com.lux032.musicautotagger.util.FileNameSanitizer.sanitize(album);
        String sourceName = sourcePath == null ? "" : new File(sourcePath).getName();
        String safeTitle = title == null ? "" : com.lux032.musicautotagger.util.FileNameSanitizer.sanitize(title);
        try (java.util.stream.Stream<Path> artists = Files.list(outputRoot)) {
            for (Path artist : artists.filter(Files::isDirectory).toList()) {
                Path directory = artist.resolve(albumDir);
                if (!Files.isDirectory(directory)) continue;
                List<Path> candidates;
                try (java.util.stream.Stream<Path> files = Files.list(directory)) {
                    candidates = files.filter(Files::isRegularFile)
                        .filter(p -> isAudioExtension(p.getFileName().toString())).sorted().toList();
                }
                Path exact = candidates.stream()
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(sourceName)).findFirst().orElse(null);
                if (exact != null) return exact.toFile().getAbsolutePath();
                if (!safeTitle.isBlank()) {
                    Path byTitle = candidates.stream().filter(p ->
                        p.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                            .contains(safeTitle.toLowerCase(java.util.Locale.ROOT)))
                        .findFirst().orElse(null);
                    if (byTitle != null) return byTitle.toFile().getAbsolutePath();
                }
                // 单曲专辑无歧义；多曲专辑不能把所有历史行错误指向同一首。
                if (candidates.size() == 1) return candidates.get(0).toFile().getAbsolutePath();
            }
        } catch (IOException e) {
            log.debug("探测历史归档文件失败 (album={}): {}", album, e.getMessage());
        }
        return null;
    }

    private boolean isAudioExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String extension = name.substring(dot + 1);
        String[] supported = config.getSupportedFormats();
        if (supported == null || supported.length == 0) return true;
        return Arrays.stream(supported).anyMatch(x -> extension.equalsIgnoreCase(x.replace(".", "")));
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    /** 读取文件模式日志的所有行（已拆列）。 */
    private java.util.List<String[]> readLogRows() {
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        File logFile = new File(config.getProcessedFileLogPath());
        if (!logFile.exists()) {
            return rows;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|", -1);
                if (parts.length >= 6) {
                    rows.add(parts);
                }
            }
        } catch (IOException e) {
            log.error(I18nUtil.getMessage("logger.read.log.failed"), e);
        }
        return rows;
    }

    /**
     * 删除指定路径的已处理记录，供用户主动重新识别时使用。
     * 文件模式会原子式重写日志；MySQL 模式按 file_path 删除。
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

        synchronized (fileWriteLock) {
            File logFile = new File(config.getProcessedFileLogPath());
            if (!logFile.exists()) {
                return;
            }
            File tempFile = new File(logFile.getAbsolutePath() + ".rewrite.tmp");
            try (BufferedReader reader = new BufferedReader(new FileReader(logFile));
                 BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith(filePath + "|")) {
                        writer.write(line);
                        writer.newLine();
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("重写已处理日志失败", e);
            }
            try {
                java.nio.file.Files.move(tempFile.toPath(), logFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException("替换已处理日志失败", e);
            }
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
            + (targetFilePathColumnAvailable ? ", target_file_path" : "");
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
     * 返回符合条件的所有记录（不分页，最多 max 条），用于「选择全部匹配结果」的批量操作与 CSV 导出。
     */
    public List<ProcessedRecord> findRecords(ProcessedRecordQuery q, int max) {
        if (!isDbMode) {
            return filterFileRecords(q).stream()
                .filter(r -> q.status == null || r.status == q.status)
                .sorted(fileComparator(q.sort))
                .limit(max)
                .toList();
        }
        List<ProcessedRecord> result = new ArrayList<>();
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
                while (rs.next()) result.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询处理记录失败: " + e.getMessage(), e);
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
     * 批量删除处理记录（按记录中保存的原始路径精确匹配）。
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
                for (int i = 0; i < list.size(); i += IN_BATCH) {
                    List<String> chunk = list.subList(i, Math.min(i + IN_BATCH, list.size()));
                    String sql = "DELETE FROM processed_files WHERE file_path IN ("
                        + String.join(", ", java.util.Collections.nCopies(chunk.size(), "?")) + ")";
                    try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                        for (int j = 0; j < chunk.size(); j++) pstmt.setString(j + 1, chunk.get(j));
                        removed += pstmt.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("删除处理记录失败: " + e.getMessage(), e);
            }
            return removed;
        }

        synchronized (fileWriteLock) {
            File logFile = new File(config.getProcessedFileLogPath());
            if (!logFile.exists()) return 0;
            File tempFile = new File(logFile.getAbsolutePath() + ".remove.tmp");
            Set<String> removedPaths = new HashSet<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(logFile));
                 BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int sep = line.indexOf('|');
                    String path = sep < 0 ? line : line.substring(0, sep);
                    if (targets.contains(path)) {
                        removedPaths.add(path);
                        continue;
                    }
                    writer.write(line);
                    writer.newLine();
                }
            } catch (IOException e) {
                tempFile.delete();
                throw new RuntimeException("重写已处理日志失败", e);
            }
            try {
                Files.move(tempFile.toPath(), logFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                tempFile.delete();
                throw new RuntimeException("替换已处理日志失败", e);
            }
            return removedPaths.size();
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
            // 简单统计行数
            File logFile = new File(config.getProcessedFileLogPath());
            if (logFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
                    long lines = reader.lines().count();
                    stats.put("totalProcessed", lines);
                } catch (IOException e) {
                    log.error("读取日志文件统计失败", e);
                    stats.put("totalProcessed", 0);
                }
            } else {
                stats.put("totalProcessed", 0);
            }
        }

        return stats;
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
