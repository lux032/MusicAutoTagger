package org.example.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.example.config.MusicConfig;
import org.example.util.I18nUtil;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 数据库服务 - 统一管理数据库连接池
 * 作为单一数据源,避免多个服务各自创建连接池造成资源浪费
 */
@Slf4j
public class DatabaseService {
    
    private final HikariDataSource dataSource;
    private final MusicConfig config;
    
    /**
     * 构造函数
     * @param config 配置对象
     */
    public DatabaseService(MusicConfig config) {
        this.config = config;
        this.dataSource = initDataSource();
        
        log.info(I18nUtil.getMessage("db.service.initialized"));
    }
    
    /**
     * 初始化数据源
     */
    private HikariDataSource initDataSource() {
        HikariConfig hikariConfig = new HikariConfig();
        
        String jdbcUrl = String.format(
            "jdbc:mysql://%s:%s/%s?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
            config.getDbHost(),
            config.getDbPort(),
            config.getDbDatabase()
        );
        
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
        } catch (SQLException e) {
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