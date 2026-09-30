package com.lux032.musicautotagger.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.util.I18nUtil;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Properties;

/**
 * 数据库服务 - 统一管理数据库连接池
 * 作为单一数据源,避免多个服务各自创建连接池造成资源浪费
 *
 * 支持两种 SQL 方言：
 *  - sqlite（默认）：嵌入式单文件数据库，零部署，启动时自动建表；
 *  - mysql：外部 MySQL 服务，需预先执行 schema.sql 建库建表。
 */
@Slf4j
public class DatabaseService {

    public static final String TYPE_SQLITE = "sqlite";
    public static final String TYPE_MYSQL = "mysql";
    public static final String TYPE_FILE = "file";

    /** SQLite 下 DATETIME 以文本形式存储，固定格式保证字典序即时间序。 */
    static final String SQLITE_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS";

    private final HikariDataSource dataSource;
    private final MusicConfig config;
    private final boolean sqlite;

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
        this.dataSource = sqlite ? initSqliteDataSource() : initMysqlDataSource();
        if (sqlite) {
            initSqliteSchema();
        }

        log.info(I18nUtil.getMessage("db.service.initialized"));
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

        try {
            HikariDataSource ds = new HikariDataSource(hikariConfig);
            try (Connection conn = ds.getConnection()) {
                log.info(I18nUtil.getMessage("db.connection.test.success"));
            }
            return ds;
        } catch (SQLException | RuntimeException e) {
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
            "CREATE INDEX IF NOT EXISTS idx_cac_cached_time ON cover_art_cache(cached_time)"
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
     * 初始化 MySQL 数据源
     */
    private HikariDataSource initMysqlDataSource() {
        HikariConfig hikariConfig = new HikariConfig();

        String jdbcUrl = String.format(
            "jdbc:mysql://%s:%s/%s?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
            config.getDbHost(),
            config.getDbPort(),
            config.getDbDatabase()
        );

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

        try {
            HikariDataSource ds = new HikariDataSource(hikariConfig);
            // 测试连接
            try (Connection conn = ds.getConnection()) {
                log.info(I18nUtil.getMessage("db.connection.test.success"));
            }
            return ds;
        } catch (SQLException | RuntimeException e) {
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
     * 获取数据库连接
     * @return 数据库连接
     * @throws SQLException SQL异常
     */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
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
