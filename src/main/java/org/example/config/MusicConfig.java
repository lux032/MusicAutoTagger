package org.example.config;

import lombok.Data;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * 音乐监控系统配置类
 */
@Data
public class MusicConfig {
    
    // 监控目录配置
    private String monitorDirectory;
    private String outputDirectory; // 输出目录
    private int scanIntervalSeconds;
    
    // MusicBrainz API 配置
    private String musicBrainzApiUrl;
    private String coverArtApiUrl; // Cover Art Archive API URL
    private String userAgent;
    
    // AcoustID 配置
    private String acoustIdApiKey;
    private String acoustIdApiUrl;
    
    // 文件处理配置
    private String[] supportedFormats;
    private boolean autoRename;
    private boolean createBackup;
    private String failedDirectory; // 识别失败文件存放目录
    private int maxRetries; // 最大重试次数
    
    // 日志配置
    private boolean enableDetailedLogging;
    private String processedFileLogPath; // 已处理文件日志路径
    
    // 缓存配置
    private String coverArtCacheDirectory; // 封面缓存目录
    
    // 数据库配置
    private String dbType; // file (默认) 或 mysql
    private String dbHost;
    private int dbPort;
    private String dbDatabase;
    private String dbUsername;
    private String dbPassword;
    private int dbMaxPoolSize;
    private int dbMinIdle;
    private long dbConnectionTimeout;
    
    // HTTP 代理配置
    private boolean proxyEnabled;
    private String proxyHost;
    private int proxyPort;
    private String proxyUsername;
    private String proxyPassword;
    
    private static MusicConfig instance;
    
    private MusicConfig() {
        // 默认配置
        this.monitorDirectory = System.getProperty("user.home") + "/Downloads";
        this.outputDirectory = System.getProperty("user.home") + "/Music/Tagged";
        this.scanIntervalSeconds = 30;
        this.musicBrainzApiUrl = "https://musicbrainz.org/ws/2";
        this.coverArtApiUrl = "https://coverartarchive.org";
        this.userAgent = "MusicDemo/1.0 ( contact@example.com )";
        this.acoustIdApiUrl = "https://api.acoustid.org/v2/lookup";
        this.supportedFormats = new String[]{"mp3", "flac", "m4a", "ogg", "wav"};
        this.autoRename = true;
        this.createBackup = true;
        this.failedDirectory = null; // 默认不移动失败文件
        this.maxRetries = 3; // 默认重试3次
        this.enableDetailedLogging = true;
        this.processedFileLogPath = System.getProperty("user.home") + "/.musicdemo/processed_files.log";
        this.coverArtCacheDirectory = null; // 默认为null,后续会设置为 outputDirectory + "/.cover_cache"
        
        // 数据库默认配置
        this.dbType = "file";
        this.dbHost = "localhost";
        this.dbPort = 3306;
        this.dbDatabase = "music_demo";
        this.dbUsername = "root";
        this.dbPassword = "";
        this.dbMaxPoolSize = 10;
        this.dbMinIdle = 2;
        this.dbConnectionTimeout = 30000;
    }
    
    /**
     * 获取配置单例
     */
    public static synchronized MusicConfig getInstance() {
        if (instance == null) {
            instance = new MusicConfig();
            instance.loadFromFile();
        }
        return instance;
    }
    
    /**
     * 从配置文件加载配置
     */
    private void loadFromFile() {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            props.load(fis);
            
            // 加载配置项
            if (props.containsKey("monitor.directory")) {
                this.monitorDirectory = props.getProperty("monitor.directory");
            }
            if (props.containsKey("monitor.outputDirectory")) {
                this.outputDirectory = props.getProperty("monitor.outputDirectory");
            }
            if (props.containsKey("monitor.scanInterval")) {
                this.scanIntervalSeconds = Integer.parseInt(props.getProperty("monitor.scanInterval"));
            }
            if (props.containsKey("musicbrainz.apiUrl")) {
                this.musicBrainzApiUrl = props.getProperty("musicbrainz.apiUrl");
            }
            if (props.containsKey("musicbrainz.coverArtApiUrl")) {
                this.coverArtApiUrl = props.getProperty("musicbrainz.coverArtApiUrl");
            }
            if (props.containsKey("musicbrainz.userAgent")) {
                this.userAgent = props.getProperty("musicbrainz.userAgent");
            }
            if (props.containsKey("acoustid.apiKey")) {
                this.acoustIdApiKey = props.getProperty("acoustid.apiKey");
            }
            if (props.containsKey("acoustid.apiUrl")) {
                this.acoustIdApiUrl = props.getProperty("acoustid.apiUrl");
            }
            if (props.containsKey("file.autoRename")) {
                this.autoRename = Boolean.parseBoolean(props.getProperty("file.autoRename"));
            }
            if (props.containsKey("file.createBackup")) {
                this.createBackup = Boolean.parseBoolean(props.getProperty("file.createBackup"));
            }
            if (props.containsKey("file.failedDirectory")) {
                this.failedDirectory = props.getProperty("file.failedDirectory");
            }
            if (props.containsKey("file.maxRetries")) {
                try {
                    this.maxRetries = Integer.parseInt(props.getProperty("file.maxRetries"));
                } catch (NumberFormatException e) {
                    System.err.println("最大重试次数配置错误: " + props.getProperty("file.maxRetries"));
                }
            }
            if (props.containsKey("logging.detailed")) {
                this.enableDetailedLogging = Boolean.parseBoolean(props.getProperty("logging.detailed"));
            }
            if (props.containsKey("logging.processedFileLogPath")) {
                this.processedFileLogPath = props.getProperty("logging.processedFileLogPath");
            }
            
            // 加载缓存配置
            if (props.containsKey("cache.coverArtDirectory")) {
                this.coverArtCacheDirectory = props.getProperty("cache.coverArtDirectory");
            }
            
            // 加载代理配置
            if (props.containsKey("proxy.enabled")) {
                this.proxyEnabled = Boolean.parseBoolean(props.getProperty("proxy.enabled"));
            }
            if (props.containsKey("proxy.host")) {
                this.proxyHost = props.getProperty("proxy.host");
            }
            if (props.containsKey("proxy.port")) {
                try {
                    this.proxyPort = Integer.parseInt(props.getProperty("proxy.port"));
                } catch (NumberFormatException e) {
                    System.err.println("代理端口配置错误: " + props.getProperty("proxy.port"));
                }
            }
            if (props.containsKey("proxy.username")) {
                this.proxyUsername = props.getProperty("proxy.username");
            }
            if (props.containsKey("proxy.password")) {
                this.proxyPassword = props.getProperty("proxy.password");
            }
            
            // 加载数据库配置
            if (props.containsKey("db.type")) {
                this.dbType = props.getProperty("db.type");
            }
            if (props.containsKey("db.mysql.host")) {
                this.dbHost = props.getProperty("db.mysql.host");
            }
            if (props.containsKey("db.mysql.port")) {
                try {
                    this.dbPort = Integer.parseInt(props.getProperty("db.mysql.port"));
                } catch (NumberFormatException e) {
                    System.err.println("数据库端口配置错误: " + props.getProperty("db.mysql.port"));
                }
            }
            if (props.containsKey("db.mysql.database")) {
                this.dbDatabase = props.getProperty("db.mysql.database");
            }
            if (props.containsKey("db.mysql.username")) {
                this.dbUsername = props.getProperty("db.mysql.username");
            }
            if (props.containsKey("db.mysql.password")) {
                this.dbPassword = props.getProperty("db.mysql.password");
            }
            if (props.containsKey("db.mysql.pool.maxPoolSize")) {
                try {
                    this.dbMaxPoolSize = Integer.parseInt(props.getProperty("db.mysql.pool.maxPoolSize"));
                } catch (NumberFormatException e) {
                    System.err.println("连接池最大连接数配置错误: " + props.getProperty("db.mysql.pool.maxPoolSize"));
                }
            }
            if (props.containsKey("db.mysql.pool.minIdle")) {
                try {
                    this.dbMinIdle = Integer.parseInt(props.getProperty("db.mysql.pool.minIdle"));
                } catch (NumberFormatException e) {
                    System.err.println("连接池最小空闲连接数配置错误: " + props.getProperty("db.mysql.pool.minIdle"));
                }
            }
            if (props.containsKey("db.mysql.pool.connectionTimeout")) {
                try {
                    this.dbConnectionTimeout = Long.parseLong(props.getProperty("db.mysql.pool.connectionTimeout"));
                } catch (NumberFormatException e) {
                    System.err.println("连接超时配置错误: " + props.getProperty("db.mysql.pool.connectionTimeout"));
                }
            }
            
            System.out.println("配置文件加载成功");
            if (proxyEnabled) {
                System.out.println("HTTP 代理已启用: " + proxyHost + ":" + proxyPort);
            }
        } catch (IOException e) {
            System.out.println("未找到配置文件，使用默认配置");
        }
    }
    
    /**
     * 验证配置是否有效
     */
    public boolean isValid() {
        if (monitorDirectory == null || monitorDirectory.isEmpty()) {
            System.err.println("监控目录未配置");
            return false;
        }
        if (outputDirectory == null || outputDirectory.isEmpty()) {
            System.err.println("输出目录未配置");
            return false;
        }
        if (acoustIdApiKey == null || acoustIdApiKey.isEmpty()) {
            System.err.println("警告: AcoustID API Key 未配置，将无法使用音频指纹识别");
        }
        return true;
    }
}