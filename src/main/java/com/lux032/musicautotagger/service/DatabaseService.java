package com.lux032.musicautotagger.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.util.I18nUtil;

import javax.sql.DataSource;
import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * 数据库服务 - 统一管理数据库连接池
 * 作为单一数据源,避免多个服务各自创建连接池造成资源浪费
 *
 * 支持两种 SQL 方言：
 *  - sqlite（默认）：嵌入式单文件数据库，零部署，启动时自动建表；
 *  - mysql：外部 MySQL 服务，需预先执行 schema.sql 建库建表。
 */
@Slf4j
public class DatabaseService implements AutoCloseable {

    public static final String TYPE_SQLITE = "sqlite";
    public static final String TYPE_MYSQL = "mysql";
    public static final String TYPE_FILE = "file";

    /** SQLite 下 DATETIME 以文本形式存储，固定格式保证字典序即时间序。 */
    static final String SQLITE_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS";

    private static final Pattern SAFE_DB_HOST = Pattern.compile("^[A-Za-z0-9._\\-\\[\\]:]+$");
    private static final Pattern SAFE_DB_NAME = Pattern.compile("^[A-Za-z0-9_$-]+$");

    private final HikariDataSource dataSource;
    private final MusicConfig config;
    private final boolean sqlite;
    /** 记录已注册 Unicode LOWER UDF 的底层 SQLiteConnection，避免每次借连接重复注册。 */
    private final Set<Object> registeredSqliteConnections =
        Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /**
     * 配置的 db.type 是否需要数据库服务（sqlite / mysql）。
     */
    public static boolean usesDatabase(MusicConfig config) {
        String type = normalizeType(config.getDbType());
        return TYPE_SQLITE.equals(type) || TYPE_MYSQL.equals(type);
    }

    public static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return TYPE_SQLITE;
        }
        return type.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 构造函数
     * @param config 配置对象
     */
    public DatabaseService(MusicConfig config) {
        this.config = config;
        this.sqlite = TYPE_SQLITE.equals(normalizeType(config.getDbType()));
        HikariDataSource ds = sqlite ? initSqliteDataSource() : initMysqlDataSource();
        this.dataSource = ds;
        if (sqlite) {
            try {
                initSqliteSchema();
            } catch (RuntimeException e) {
                closeQuietly(ds);
                throw e;
            }
        }

        log.info(I18nUtil.getMessage("db.service.initialized"));
    }

    private static void closeQuietly(HikariDataSource ds) {
        if (ds != null && !ds.isClosed()) {
            try {
                ds.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 当前是否为 SQLite 方言。 */
    public boolean isSqlite() {
        return sqlite;
    }

    /** 用于日志 / 仪表板显示的数据库类型名。 */
    public String getDisplayName() {
        return sqlite ? "SQLite" : "MySQL";
    }

    /**
     * 初始化 SQLite 数据源
     */
    private HikariDataSource initSqliteDataSource() {
        String path = config.getDbSqlitePath();
        if (path == null || path.isBlank()) {
            path = "data/music-tagger.db";
        }
        File dbFile = new File(path).getAbsoluteFile();
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            log.warn("无法创建 SQLite 数据库目录: {}", parent);
        }

        String jdbcUrl = "jdbc:sqlite:" + dbFile.getPath();

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setDriverClassName("org.sqlite.JDBC");
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setPoolName("sqlite-pool");
        // SQLite 同一时刻只允许一个写者；WAL 下读写可并发，少量连接足够，
        // 但不能为 1：部分代码会在持有一个连接时再取第二个连接。
        hikariConfig.setMaximumPoolSize(4);
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setConnectionTimeout(config.getDbConnectionTimeout() > 0 ? config.getDbConnectionTimeout() : 30000);
        hikariConfig.setConnectionTestQuery("SELECT 1");

        Properties props = new Properties();
        props.setProperty("journal_mode", "WAL");
        props.setProperty("synchronous", "NORMAL");
        props.setProperty("busy_timeout", "10000");
        props.setProperty("date_class", "TEXT");
        props.setProperty("date_string_format", SQLITE_DATE_FORMAT);
        hikariConfig.setDataSourceProperties(props);

        log.info(I18nUtil.getMessage("db.config"), jdbcUrl);

        HikariDataSource ds = null;
        try {
            ds = new HikariDataSource(hikariConfig);
            try (Connection conn = ds.getConnection()) {
                log.info(I18nUtil.getMessage("db.connection.test.success"));
            }
            return ds;
        } catch (SQLException | RuntimeException e) {
            closeQuietly(ds);
            log.error(I18nUtil.getMessage("db.connection.test.failed"), e);
            log.error("请检查 SQLite 数据库文件路径是否可写: {}", dbFile);
            throw new RuntimeException("SQLite 数据库初始化失败", e);
        }
    }

    /**
     * SQLite 启动时自动建表（幂等），用户无需手动执行任何 SQL。
     * 表结构与 schema.sql 中的 MySQL 版本保持一致。
     */
    private void initSqliteSchema() {
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS processed_files ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "file_hash VARCHAR(64) NOT NULL, "
                + "file_name VARCHAR(500) NOT NULL, "
                + "file_path VARCHAR(1000) NOT NULL UNIQUE, "
                + "file_size BIGINT NOT NULL, "
                + "processed_time DATETIME NOT NULL, "
                + "recording_id VARCHAR(100), "
                + "artist VARCHAR(500), "
                + "title VARCHAR(500), "
                + "album VARCHAR(500), "
                + "release_group_id VARCHAR(100), "
                + "target_file_path VARCHAR(1000), "
                + "folder_path VARCHAR(1000), "
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
            "CREATE INDEX IF NOT EXISTS idx_pf_file_hash ON processed_files(file_hash)",
            "CREATE INDEX IF NOT EXISTS idx_pf_processed_time ON processed_files(processed_time)",
            "CREATE INDEX IF NOT EXISTS idx_pf_recording_id ON processed_files(recording_id)",
            "CREATE INDEX IF NOT EXISTS idx_pf_album ON processed_files(album)",
            "CREATE TABLE IF NOT EXISTS cover_art_cache ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "url_hash VARCHAR(64) NOT NULL UNIQUE, "
                + "cover_url VARCHAR(2000) NOT NULL, "
                + "cache_file_path VARCHAR(1000) NOT NULL, "
                + "file_size BIGINT NOT NULL, "
                + "cached_time DATETIME NOT NULL, "
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
            "CREATE INDEX IF NOT EXISTS idx_cac_cached_time ON cover_art_cache(cached_time)",
            "CREATE TABLE IF NOT EXISTS mb_artist_cache ("
                + "mbid VARCHAR(36) PRIMARY KEY, "
                + "name VARCHAR(500) NOT NULL, "
                + "sort_name VARCHAR(500), "
                + "disambiguation VARCHAR(500), "
                + "country VARCHAR(32), "
                + "artist_type VARCHAR(64), "
                + "aliases_json TEXT, "
                + "source VARCHAR(32), "
                + "updated_at BIGINT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS mb_artist_name_index ("
                + "normalized_name VARCHAR(500) NOT NULL, "
                + "mbid VARCHAR(36) NOT NULL, "
                + "match_kind VARCHAR(32) NOT NULL, "
                + "updated_at BIGINT NOT NULL, "
                + "PRIMARY KEY (normalized_name, mbid))",
            "CREATE INDEX IF NOT EXISTS idx_mb_artist_name_mbid ON mb_artist_name_index(mbid)"
        };
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String sql : ddl) {
                stmt.execute(sql);
            }
            log.info("SQLite 表结构检查完成");
        } catch (SQLException e) {
            throw new RuntimeException("SQLite 建表失败", e);
        }
    }

    /**
     * 校验 MySQL 连接参数并构造安全的 JDBC URL（防 JDBC 参数注入，使用系统时区替代硬编码东八区）。
     */
    static String buildMysqlJdbcUrl(MusicConfig config) {
        String host = config.getDbHost() == null ? "" : config.getDbHost().trim();
        if (host.isEmpty() || !SAFE_DB_HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("Invalid MySQL host: " + host);
        }
        int port = config.getDbPort();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid MySQL port: " + port);
        }
        String database = config.getDbDatabase() == null ? "" : config.getDbDatabase().trim();
        if (database.isEmpty() || !SAFE_DB_NAME.matcher(database).matches()) {
            throw new IllegalArgumentException("Invalid MySQL database name: " + database);
        }
        String timezone = URLEncoder.encode(ZoneId.systemDefault().getId(), StandardCharsets.UTF_8);
        return String.format(
            "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=%s&allowPublicKeyRetrieval=true",
            host, port, database, timezone
        );
    }

    /**
     * 初始化 MySQL 数据源
     */
    private HikariDataSource initMysqlDataSource() {
        HikariConfig hikariConfig = new HikariConfig();

        String jdbcUrl = buildMysqlJdbcUrl(config);

        hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(config.getDbUsername());
        hikariConfig.setPassword(config.getDbPassword());
        hikariConfig.setMaximumPoolSize(config.getDbMaxPoolSize());
        hikariConfig.setMinimumIdle(config.getDbMinIdle());
        hikariConfig.setConnectionTimeout(config.getDbConnectionTimeout());

        // 连接测试
        hikariConfig.setConnectionTestQuery("SELECT 1");

        log.info(I18nUtil.getMessage("db.config"), jdbcUrl);
        log.info(I18nUtil.getMessage("db.username"), config.getDbUsername(), config.getDbDatabase());
        log.info(I18nUtil.getMessage("db.pool.config"),
            config.getDbMaxPoolSize(), config.getDbMinIdle());

        HikariDataSource ds = null;
        try {
            ds = new HikariDataSource(hikariConfig);
            // 测试连接
            try (Connection conn = ds.getConnection()) {
                log.info(I18nUtil.getMessage("db.connection.test.success"));
            }
            return ds;
        } catch (SQLException | RuntimeException e) {
            closeQuietly(ds);
            log.error(I18nUtil.getMessage("db.connection.test.failed"), e);
            log.error(I18nUtil.getMessage("db.check.mysql.running"));
            log.error(I18nUtil.getMessage("db.check.database.created"), config.getDbDatabase());
            log.error(I18nUtil.getMessage("db.check.credentials"), config.getDbUsername());
            log.error(I18nUtil.getMessage("db.create.database.command"));
            log.error(I18nUtil.getMessage("db.create.database.sql"));
            throw new RuntimeException("数据库连接失败", e);
        }
    }

    /**
     * 获取数据库连接。SQLite 连接会自动注册 Unicode 大小写折叠版 LOWER() 函数，
     * 解决 SQLite 默认 ASCII-only LOWER() 无法搜索 "Étoile"、"Кино" 等非 ASCII 文本的问题。
     * @return 数据库连接
     * @throws SQLException SQL异常
     */
    public Connection getConnection() throws SQLException {
        Connection conn = dataSource.getConnection();
        if (sqlite) {
            ensureSqliteUnicodeLower(conn);
        }
        return conn;
    }

    private void ensureSqliteUnicodeLower(Connection conn) {
        try {
            if (!conn.isWrapperFor(org.sqlite.SQLiteConnection.class)) {
                return;
            }
            org.sqlite.SQLiteConnection raw = conn.unwrap(org.sqlite.SQLiteConnection.class);
            if (!registeredSqliteConnections.add(raw)) {
                return;
            }
            org.sqlite.Function.create(raw, "LOWER", new org.sqlite.Function() {
                @Override
                protected void xFunc() throws SQLException {
                    if (args() < 1 || value_type(0) == 5) { // 5 = SQLITE_NULL
                        result();
                        return;
                    }
                    String text = value_text(0);
                    if (text == null) {
                        result();
                    } else {
                        result(text.toLowerCase(Locale.ROOT));
                    }
                }
            });
        } catch (SQLException e) {
            log.debug("注册 SQLite Unicode LOWER 函数失败（将回退默认 LOWER）: {}", e.getMessage());
        }
    }

    /**
     * 获取数据源
     * @return 数据源对象
     */
    public DataSource getDataSource() {
        return dataSource;
    }

    /**
     * 检查数据库是否可用
     * @return true=可用, false=不可用
     */
    public boolean isAvailable() {
        try (Connection conn = getConnection()) {
            return conn.isValid(5);
        } catch (SQLException e) {
            log.error(I18nUtil.getMessage("db.unavailable"), e);
            return false;
        }
    }

    /**
     * 关闭数据源
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info(I18nUtil.getMessage("db.pool.closed"));
        }
    }
}
