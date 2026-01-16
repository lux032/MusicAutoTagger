package com.lux032.musicautotagger.config;

import lombok.Data;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
    private String partialDirectory; // 部分识别文件存放目录(有标签或封面但指纹识别失败)
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

    // 国际化配置
    private String language; // 语言设置

    // 歌词配置
    private boolean exportLyricsToFile; // 是否将歌词导出为独立文件

    // 音频规格规范化配置
    private boolean audioNormalizeEnabled; // 是否将高规格音频转换为24/48
    private String audioNormalizeFfmpegPath; // ffmpeg 路径

    // CUE 分割配置
    private boolean cueSplitEnabled; // 是否启用 cue 分割
    private String cueSplitOutputDir; // cue 分割输出目录

    // 发行地区优先级配置
    private List<String> releaseCountryPriority; // 发行地区优先级列表

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
        this.partialDirectory = null; // 默认不移动部分识别文件
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

        // 国际化默认配置
        this.language = "en_US";

        // 音频规格规范化默认配置
        this.audioNormalizeEnabled = false;
        this.audioNormalizeFfmpegPath = "ffmpeg";

        // CUE 分割默认配置
        this.cueSplitEnabled = false;
        this.cueSplitOutputDir = null;

        // 发行地区优先级默认配置（空列表表示不按地区筛选）
        this.releaseCountryPriority = new ArrayList<>();

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
            if (props.containsKey("file.supportedFormats")) {
                String formats = props.getProperty("file.supportedFormats", "").trim();
                if (!formats.isEmpty()) {
                    this.supportedFormats = Arrays.stream(formats.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toArray(String[]::new);
                }
            }
            if (props.containsKey("file.failedDirectory")) {
                this.failedDirectory = props.getProperty("file.failedDirectory");
            }
            if (props.containsKey("file.partialDirectory")) {
                this.partialDirectory = props.getProperty("file.partialDirectory");
            }
            if (props.containsKey("file.maxRetries")) {
                try {
                    this.maxRetries = Integer.parseInt(props.getProperty("file.maxRetries"));
                } catch (NumberFormatException e) {
                    System.err.println("Invalid max retries configuration: " + props.getProperty("file.maxRetries"));
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
                    System.err.println("Invalid proxy port configuration: " + props.getProperty("proxy.port"));
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
                    System.err.println("Invalid database port configuration: " + props.getProperty("db.mysql.port"));
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
                    System.err.println("Invalid max pool size configuration: " + props.getProperty("db.mysql.pool.maxPoolSize"));
                }
            }
            if (props.containsKey("db.mysql.pool.minIdle")) {
                try {
                    this.dbMinIdle = Integer.parseInt(props.getProperty("db.mysql.pool.minIdle"));
                } catch (NumberFormatException e) {
                    System.err.println("Invalid min idle configuration: " + props.getProperty("db.mysql.pool.minIdle"));
                }
            }
            if (props.containsKey("db.mysql.pool.connectionTimeout")) {
                try {
                    this.dbConnectionTimeout = Long.parseLong(props.getProperty("db.mysql.pool.connectionTimeout"));
                } catch (NumberFormatException e) {
                    System.err.println("Invalid connection timeout configuration: " + props.getProperty("db.mysql.pool.connectionTimeout"));
                }
            }

            // 加载国际化配置
            if (props.containsKey("i18n.language")) {
                this.language = props.getProperty("i18n.language");
            }

            // 加载歌词配置
            if (props.containsKey("lyrics.exportToFile")) {
                this.exportLyricsToFile = Boolean.parseBoolean(props.getProperty("lyrics.exportToFile"));
            }

            // 加载音频规格规范化配置
            if (props.containsKey("audio.normalize.enabled")) {
                this.audioNormalizeEnabled = Boolean.parseBoolean(props.getProperty("audio.normalize.enabled"));
            }
            if (props.containsKey("audio.normalize.ffmpegPath")) {
                this.audioNormalizeFfmpegPath = props.getProperty("audio.normalize.ffmpegPath");
            }

            // 加载 CUE 分割配置
            if (props.containsKey("cue.split.enabled")) {
                this.cueSplitEnabled = Boolean.parseBoolean(props.getProperty("cue.split.enabled"));
            }
            if (props.containsKey("cue.split.outputDir")) {
                this.cueSplitOutputDir = props.getProperty("cue.split.outputDir");
            }

            // 加载发行地区优先级配置
            if (props.containsKey("release.countryPriority")) {
                String countryPriorityStr = props.getProperty("release.countryPriority", "").trim();
                if (!countryPriorityStr.isEmpty()) {
                    this.releaseCountryPriority = Arrays.asList(countryPriorityStr.split(","))
                        .stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(java.util.stream.Collectors.toList());
                    System.out.println("Release country priority configured: " + this.releaseCountryPriority);
                }
            }

            System.out.println("Configuration file loaded successfully");
            if (proxyEnabled) {
                System.out.println("HTTP proxy enabled: " + proxyHost + ":" + proxyPort);
            }
        } catch (IOException e) {
            Path configPath = Paths.get("config.properties");
            if (!Files.exists(configPath)) {
                System.out.println("Configuration file not found, generating default configuration");
                try {
                    saveToFile(configPath);
                    System.out.println("Default configuration saved to config.properties");
                } catch (IOException ioException) {
                    System.err.println("Failed to create default configuration: " + ioException.getMessage());
                }
            } else {
                System.out.println("Configuration file not found, using default configuration");
            }
        }
    }

    private void saveToFile(Path configPath) throws IOException {
        Properties props = new Properties();
        props.setProperty("monitor.directory", monitorDirectory);
        props.setProperty("monitor.outputDirectory", outputDirectory);
        props.setProperty("monitor.scanInterval", String.valueOf(scanIntervalSeconds));
        props.setProperty("musicbrainz.apiUrl", musicBrainzApiUrl);
        props.setProperty("musicbrainz.coverArtApiUrl", coverArtApiUrl);
        props.setProperty("musicbrainz.userAgent", userAgent);
        if (acoustIdApiKey != null) {
            props.setProperty("acoustid.apiKey", acoustIdApiKey);
        }
        props.setProperty("acoustid.apiUrl", acoustIdApiUrl);
        props.setProperty("file.autoRename", String.valueOf(autoRename));
        props.setProperty("file.createBackup", String.valueOf(createBackup));
        if (supportedFormats != null && supportedFormats.length > 0) {
            props.setProperty("file.supportedFormats", String.join(",", supportedFormats));
        }
        if (failedDirectory != null) {
            props.setProperty("file.failedDirectory", failedDirectory);
        }
        if (partialDirectory != null) {
            props.setProperty("file.partialDirectory", partialDirectory);
        }
        props.setProperty("file.maxRetries", String.valueOf(maxRetries));
        props.setProperty("logging.detailed", String.valueOf(enableDetailedLogging));
        if (processedFileLogPath != null) {
            props.setProperty("logging.processedFileLogPath", processedFileLogPath);
        }
        if (coverArtCacheDirectory != null) {
            props.setProperty("cache.coverArtDirectory", coverArtCacheDirectory);
        }
        props.setProperty("db.type", dbType);
        props.setProperty("db.mysql.host", dbHost);
        props.setProperty("db.mysql.port", String.valueOf(dbPort));
        props.setProperty("db.mysql.database", dbDatabase);
        props.setProperty("db.mysql.username", dbUsername);
        props.setProperty("db.mysql.password", dbPassword == null ? "" : dbPassword);
        props.setProperty("db.mysql.pool.maxPoolSize", String.valueOf(dbMaxPoolSize));
        props.setProperty("db.mysql.pool.minIdle", String.valueOf(dbMinIdle));
        props.setProperty("db.mysql.pool.connectionTimeout", String.valueOf(dbConnectionTimeout));
        props.setProperty("proxy.enabled", String.valueOf(proxyEnabled));
        if (proxyHost != null) {
            props.setProperty("proxy.host", proxyHost);
        }
        props.setProperty("proxy.port", String.valueOf(proxyPort));
        if (proxyUsername != null) {
            props.setProperty("proxy.username", proxyUsername);
        }
        if (proxyPassword != null) {
            props.setProperty("proxy.password", proxyPassword);
        }
        props.setProperty("i18n.language", language);
        props.setProperty("lyrics.exportToFile", String.valueOf(exportLyricsToFile));
        props.setProperty("audio.normalize.enabled", String.valueOf(audioNormalizeEnabled));
        props.setProperty("audio.normalize.ffmpegPath", audioNormalizeFfmpegPath);
        props.setProperty("cue.split.enabled", String.valueOf(cueSplitEnabled));
        if (cueSplitOutputDir != null) {
            props.setProperty("cue.split.outputDir", cueSplitOutputDir);
        }
        if (releaseCountryPriority != null && !releaseCountryPriority.isEmpty()) {
            props.setProperty("release.countryPriority", String.join(",", releaseCountryPriority));
        }

        try (FileOutputStream fos = new FileOutputStream(configPath.toFile())) {
            props.store(fos, "Auto-generated by MusicAutoTagger");
        }
    }
    
    /**
     * 验证配置是否有效
     */
    public boolean isValid() {
        if (monitorDirectory == null || monitorDirectory.isEmpty()) {
            System.err.println("Monitor directory not configured");
            return false;
        }
        if (outputDirectory == null || outputDirectory.isEmpty()) {
            System.err.println("Output directory not configured");
            return false;
        }
        if (acoustIdApiKey == null || acoustIdApiKey.isEmpty()) {
            System.err.println("WARNING: AcoustID API Key not configured, audio fingerprint recognition will be unavailable");
        }
        return true;
    }
}

