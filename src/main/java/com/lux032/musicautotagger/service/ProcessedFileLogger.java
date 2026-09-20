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

import com.lux032.musicautotagger.config.MusicConfig;

/**
 * 已处理文件日志服务 - 支持 MySQL 和 文件模式
 * 用于记录和检查文件是否已被处理,防止重复整理
 */
@Slf4j
public class ProcessedFileLogger {

    private final DatabaseService databaseService;
    private final MusicConfig config;
    private final DateTimeFormatter dateFormatter;
    private final boolean isDbMode;
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
     * @param databaseService 数据库服务 (仅在 dbMode 为 mysql 时需要)
     */
    public ProcessedFileLogger(MusicConfig config, DatabaseService databaseService) {
        this.config = config;
        this.databaseService = databaseService;
        this.dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        this.isDbMode = "mysql".equalsIgnoreCase(config.getDbType());

        if (isDbMode) {
            log.info(I18nUtil.getMessage("logger.init.mysql"));
            ensureReleaseGroupIdColumn();
            ensureTargetFilePathColumn();
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
                    + "ADD COLUMN release_group_id VARCHAR(100) NULL COMMENT 'MusicBrainz Release Group ID'");
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
                    + "ADD COLUMN target_file_path VARCHAR(1000) NULL COMMENT '归档目标文件绝对路径'");
                targetFilePathColumnAvailable = true;
                log.info("已为 processed_files 补充 target_file_path 列");
            }
        } catch (SQLException e) {
            targetFilePathColumnAvailable = false;
            log.warn("processed_files 缺少 target_file_path 列且自动补列失败: {}", e.getMessage());
        }
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
                StringBuilder update = new StringBuilder(
                    "file_hash = VALUES(file_hash), file_name = VALUES(file_name), "
                    + "file_size = VALUES(file_size), processed_time = VALUES(processed_time), "
                    + "recording_id = VALUES(recording_id), artist = VALUES(artist), "
                    + "title = VALUES(title), album = VALUES(album)");
                if (withRgid) update.append(", release_group_id = VALUES(release_group_id)");
                if (withTarget) update.append(", target_file_path = COALESCE(VALUES(target_file_path), target_file_path)");
                String sql = "INSERT INTO processed_files (" + String.join(", ", columns) + ") VALUES ("
                    + values + ") ON DUPLICATE KEY UPDATE " + update;

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
                    log.error(I18nUtil.getMessage("logger.write.log.failed"), e);
                }
            }
        }

        log.info(I18nUtil.getMessage("logger.history.recorded"), artist, title, isDbMode ? I18nUtil.getMessage("logger.db.mode") : I18nUtil.getMessage("logger.file.mode"));
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
            String sql = "UPDATE processed_files SET target_file_path = CONCAT(?, SUBSTRING(target_file_path, ?)) "
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
                + "WHERE target_file_path IS NOT NULL AND LEFT(target_file_path, ?) = ?";
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
                stats.put("databaseType", "MySQL");
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
            String sql = "DELETE FROM processed_files WHERE processed_time < DATE_SUB(NOW(), INTERVAL ? DAY)";
            try (Connection conn = databaseService.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, daysToKeep);
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
