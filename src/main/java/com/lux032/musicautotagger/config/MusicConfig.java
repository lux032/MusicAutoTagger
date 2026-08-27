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

    // 人工确认队列配置（阶段六）
    /**
     * 是否启用「待人工确认」链路。
     *
     * 关闭（默认）时保持阶段一的行为：专辑定不下来时直接合成专辑信息并归档。
     * 打开后：这类文件不写标签、不移动，而是进入可跨重启的待确认队列，
     * 等人在 Web 面板上选定。默认关闭是为了避免无人值守部署里文件默默堆积。
     */
    private boolean reviewEnabled;
    private String reviewQueuePath;        // 待确认队列 JSON 路径
    private String reviewStagingDirectory; // 转码暂存目录（长期挂起时不能占用临时目录）

    // LLM 匹配配置
    private boolean enableLLMMatching; // 是否启用 LLM 辅助艺术家匹配
    private List<String> llmApiKeys; // LLM API Keys（支持多个）
    private List<String> llmApiUrls; // LLM API URLs（支持多个）
    private List<String> llmModels; // LLM 模型名称（支持多个）
    /** 与 LLM 端点按下标对应：是否允许参与原生 Web Search */
    private List<Boolean> llmWebSearchEnabled;

    // LLM 通用调用参数（阶段七 #21）
    /** 协议：auto / anthropic / openai（auto 时按 URL 猜） */
    private String llmProvider;
    private int llmMaxTokens;        // 判定类任务要输出理由，100 不够
    private double llmTemperature;   // 判定任务固定 0，保证可复现
    private int llmTimeoutSeconds;
    private int llmMaxRetries;       // 429/5xx/网络错误的指数退避重试次数

    // LLM 专辑判定（阶段七 #22）
    /** 是否允许在待确认面板上调用 LLM 做封闭式专辑判定 */
    private boolean llmAlbumJudgeEnabled;
    /** 是否允许直接按 LLM 结论落盘（默认 false：结论只是建议，仍需人工确认） */
    private boolean llmAlbumAutoApply;
    /** 自动落盘所需的最低置信度 */
    private double llmAlbumAutoApplyMinConfidence;

    // 部分识别（第 2 筐）准入配置（阶段八 #23/#24）
    /** 除封面外，是否还要求标签达到 Plex 可读标准才放进 partialDirectory */
    private boolean partialRequireReadableTags;
    /** album / tracknumber 的最低覆盖率 */
    private double partialMinTagCoverage;

    // 联网搜索专用调用参数
    /**
     * 联网搜索的输出上限，不复用 llm.maxTokens。
     * 后者默认 600 是为「艺术家名匹配」这类短回答定的，
     * 而搜索要返回最多 5 个候选、每个带完整曲目表与来源 URL，
     * 600 token 会直接截断 JSON 导致解析失败。
     */
    private int llmWebSearchMaxTokens;
    /** 联网搜索超时；搜索本身就要几十秒，比普通调用需要更宽的窗口 */
    private int llmWebSearchTimeoutSeconds;

    // 恢复与联网辅助识别
    /** 原子归档工作目录；空值时使用 outputDirectory/.recovery-work */
    private String recoveryWorkDirectory;
    /** 成功后隔离副本回收站保留天数：0 立即删除，-1 永久保留 */
    private int recoveryTrashRetentionDays;
    private String recoveryTrashDirectory;

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

        // 人工确认队列默认配置
        this.reviewEnabled = false;
        this.reviewQueuePath = "data/review-queue.json";
        this.reviewStagingDirectory = "data/review-staging";

        // LLM 匹配默认配置
        this.enableLLMMatching = false;
        this.llmApiKeys = new ArrayList<>();
        this.llmApiUrls = new ArrayList<>();
        this.llmModels = new ArrayList<>();
        this.llmWebSearchEnabled = new ArrayList<>();

        // LLM 通用调用参数默认值
        this.llmProvider = "auto";
        this.llmMaxTokens = 600;
        this.llmTemperature = 0.0;
        this.llmTimeoutSeconds = 60;
        this.llmMaxRetries = 2;

        // LLM 专辑判定默认关闭（额外的 API 调用 + 需要人工复核）
        this.llmAlbumJudgeEnabled = false;
        this.llmAlbumAutoApply = false;
        this.llmAlbumAutoApplyMinConfidence = 0.85;

        // 部分识别准入默认值：封面 + 标签可读性双门槛
        this.partialRequireReadableTags = true;
        this.partialMinTagCoverage = 0.8;

        this.llmWebSearchMaxTokens = 4000;
        this.llmWebSearchTimeoutSeconds = 180;

        this.recoveryWorkDirectory = null;
        this.recoveryTrashRetentionDays = 7;
        this.recoveryTrashDirectory = "data/recovery-trash";

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

            // 加载人工确认队列配置
            if (props.containsKey("review.enabled")) {
                this.reviewEnabled = Boolean.parseBoolean(props.getProperty("review.enabled"));
            }
            if (props.containsKey("review.queuePath")) {
                this.reviewQueuePath = props.getProperty("review.queuePath");
            }
            if (props.containsKey("review.stagingDirectory")) {
                this.reviewStagingDirectory = props.getProperty("review.stagingDirectory");
            }

            // 加载 LLM 匹配配置
            if (props.containsKey("llm.matching.enabled")) {
                this.enableLLMMatching = Boolean.parseBoolean(props.getProperty("llm.matching.enabled"));
            }
            if (props.containsKey("llm.apiKey")) {
                String keys = props.getProperty("llm.apiKey", "").trim();
                if (!keys.isEmpty()) {
                    this.llmApiKeys = Arrays.asList(keys.split(","))
                        .stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(java.util.stream.Collectors.toList());
                }
            }
            if (props.containsKey("llm.apiUrl")) {
                String urls = props.getProperty("llm.apiUrl", "").trim();
                if (!urls.isEmpty()) {
                    this.llmApiUrls = Arrays.asList(urls.split(","))
                        .stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(java.util.stream.Collectors.toList());
                }
            }
            if (props.containsKey("llm.model")) {
                String models = props.getProperty("llm.model", "").trim();
                if (!models.isEmpty()) {
                    this.llmModels = Arrays.asList(models.split(","))
                        .stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(java.util.stream.Collectors.toList());
                }
            }
            if (props.containsKey("llm.webSearchEnabled")) {
                String flags = props.getProperty("llm.webSearchEnabled", "").trim();
                if (!flags.isEmpty()) {
                    this.llmWebSearchEnabled = Arrays.stream(flags.split(","))
                        .map(String::trim)
                        .map(Boolean::parseBoolean)
                        .collect(java.util.stream.Collectors.toList());
                }
            }

            // 加载 LLM 通用调用参数（阶段七 #21）
            if (props.containsKey("llm.provider")) {
                this.llmProvider = props.getProperty("llm.provider", "auto").trim();
            }
            if (props.containsKey("llm.maxTokens")) {
                this.llmMaxTokens = parseIntInRange(
                    props.getProperty("llm.maxTokens"), this.llmMaxTokens, 1, 32000, "llm.maxTokens");
            }
            if (props.containsKey("llm.temperature")) {
                // 上限取 2.0（OpenAI 允许 0~2，Anthropic 是 0~1，超过的值会被端点 400）
                this.llmTemperature = parseDoubleInRange(
                    props.getProperty("llm.temperature"), this.llmTemperature, 0.0, 2.0, "llm.temperature");
            }
            if (props.containsKey("llm.timeoutSeconds")) {
                this.llmTimeoutSeconds = parseIntInRange(
                    props.getProperty("llm.timeoutSeconds"), this.llmTimeoutSeconds, 5, 600, "llm.timeoutSeconds");
            }
            if (props.containsKey("llm.maxRetries")) {
                this.llmMaxRetries = parseIntInRange(
                    props.getProperty("llm.maxRetries"), this.llmMaxRetries, 0, 10, "llm.maxRetries");
            }
            if (props.containsKey("llm.webSearch.maxTokens")) {
                this.llmWebSearchMaxTokens = parseIntInRange(
                    props.getProperty("llm.webSearch.maxTokens"),
                    this.llmWebSearchMaxTokens, 256, 32000, "llm.webSearch.maxTokens");
            }
            if (props.containsKey("llm.webSearch.timeoutSeconds")) {
                this.llmWebSearchTimeoutSeconds = parseIntInRange(
                    props.getProperty("llm.webSearch.timeoutSeconds"),
                    this.llmWebSearchTimeoutSeconds, 30, 900, "llm.webSearch.timeoutSeconds");
            }

            // 加载 LLM 专辑判定配置（阶段七 #22）
            if (props.containsKey("llm.album.judge.enabled")) {
                this.llmAlbumJudgeEnabled = Boolean.parseBoolean(props.getProperty("llm.album.judge.enabled"));
            }
            if (props.containsKey("llm.album.autoApply")) {
                this.llmAlbumAutoApply = Boolean.parseBoolean(props.getProperty("llm.album.autoApply"));
            }
            if (props.containsKey("llm.album.autoApplyMinConfidence")) {
                // 必须是 0~1 的有限值：NaN 会让 `confidence < 阈值` 永远为 false，
                // 直接**绕过自动落盘的唯一安全门槛**
                this.llmAlbumAutoApplyMinConfidence = parseDoubleInRange(
                    props.getProperty("llm.album.autoApplyMinConfidence"),
                    this.llmAlbumAutoApplyMinConfidence, 0.0, 1.0, "llm.album.autoApplyMinConfidence");
            }

            // 加载恢复与原子归档配置
            if (props.containsKey("recovery.workDirectory")) {
                this.recoveryWorkDirectory = props.getProperty("recovery.workDirectory").trim();
            }
            if (props.containsKey("recovery.trashDirectory")) {
                this.recoveryTrashDirectory = props.getProperty("recovery.trashDirectory").trim();
            }
            if (props.containsKey("recovery.trash.retentionDays")) {
                this.recoveryTrashRetentionDays = parseIntInRange(
                    props.getProperty("recovery.trash.retentionDays"),
                    this.recoveryTrashRetentionDays, -1, 365, "recovery.trash.retentionDays");
            }

            // 加载部分识别准入配置（阶段八 #23/#24）
            if (props.containsKey("file.partial.requireReadableTags")) {
                this.partialRequireReadableTags =
                    Boolean.parseBoolean(props.getProperty("file.partial.requireReadableTags"));
            }
            if (props.containsKey("file.partial.minTagCoverage")) {
                // 同上：NaN / 负数会让覆盖率比较全部失效，等于门槛不存在
                this.partialMinTagCoverage = parseDoubleInRange(
                    props.getProperty("file.partial.minTagCoverage"),
                    this.partialMinTagCoverage, 0.0, 1.0, "file.partial.minTagCoverage");
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

    private static int parseIntSafe(String value, int defaultValue) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static double parseDoubleSafe(String value, double defaultValue) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 解析并**校验范围**的浮点配置。
     *
     * {@code Double.parseDouble("NaN")} 是合法的，而 NaN 参与的任何比较都返回 false，
     * 这会让「低于阈值就拒绝」这类**安全门槛静默失效**（而不是报错）。
     * 同理，负数 / 大于 1 的比例也会让门槛要么形同虚设、要么永远不可能通过。
     * 因此非法值一律回退到默认值并告警，而不是默默接受。
     */
    private static double parseDoubleInRange(String value, double defaultValue,
                                             double min, double max, String key) {
        double parsed = parseDoubleSafe(value, Double.NaN);
        if (Double.isFinite(parsed) && parsed >= min && parsed <= max) {
            return parsed;
        }
        System.err.println("Invalid value for " + key + ": '" + value
            + "' (expected a finite number in [" + min + ", " + max + "]), using default " + defaultValue);
        return defaultValue;
    }

    private static int parseIntInRange(String value, int defaultValue, int min, int max, String key) {
        int parsed = parseIntSafe(value, Integer.MIN_VALUE);
        if (parsed >= min && parsed <= max) {
            return parsed;
        }
        System.err.println("Invalid value for " + key + ": '" + value
            + "' (expected an integer in [" + min + ", " + max + "]), using default " + defaultValue);
        return defaultValue;
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
        props.setProperty("review.enabled", String.valueOf(reviewEnabled));
        if (reviewQueuePath != null) {
            props.setProperty("review.queuePath", reviewQueuePath);
        }
        if (reviewStagingDirectory != null) {
            props.setProperty("review.stagingDirectory", reviewStagingDirectory);
        }
        props.setProperty("llm.matching.enabled", String.valueOf(enableLLMMatching));
        if (llmApiKeys != null && !llmApiKeys.isEmpty()) {
            props.setProperty("llm.apiKey", String.join(",", llmApiKeys));
        }
        if (llmApiUrls != null && !llmApiUrls.isEmpty()) {
            props.setProperty("llm.apiUrl", String.join(",", llmApiUrls));
        }
        if (llmModels != null && !llmModels.isEmpty()) {
            props.setProperty("llm.model", String.join(",", llmModels));
        }
        if (llmWebSearchEnabled != null && !llmWebSearchEnabled.isEmpty()) {
            props.setProperty("llm.webSearchEnabled", llmWebSearchEnabled.stream()
                .map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
        }
        props.setProperty("llm.provider", llmProvider == null ? "auto" : llmProvider);
        props.setProperty("llm.maxTokens", String.valueOf(llmMaxTokens));
        props.setProperty("llm.temperature", String.valueOf(llmTemperature));
        props.setProperty("llm.timeoutSeconds", String.valueOf(llmTimeoutSeconds));
        props.setProperty("llm.maxRetries", String.valueOf(llmMaxRetries));
        props.setProperty("llm.album.judge.enabled", String.valueOf(llmAlbumJudgeEnabled));
        props.setProperty("llm.album.autoApply", String.valueOf(llmAlbumAutoApply));
        props.setProperty("llm.album.autoApplyMinConfidence", String.valueOf(llmAlbumAutoApplyMinConfidence));
        props.setProperty("file.partial.requireReadableTags", String.valueOf(partialRequireReadableTags));
        props.setProperty("file.partial.minTagCoverage", String.valueOf(partialMinTagCoverage));
        props.setProperty("llm.webSearch.maxTokens", String.valueOf(llmWebSearchMaxTokens));
        props.setProperty("llm.webSearch.timeoutSeconds", String.valueOf(llmWebSearchTimeoutSeconds));
        if (recoveryWorkDirectory != null && !recoveryWorkDirectory.isEmpty()) {
            props.setProperty("recovery.workDirectory", recoveryWorkDirectory);
        }
        // Properties 不接受 null value，直接 setProperty 会抛 NPE 并让整个配置保存失败
        if (recoveryTrashDirectory != null && !recoveryTrashDirectory.isEmpty()) {
            props.setProperty("recovery.trashDirectory", recoveryTrashDirectory);
        }
        props.setProperty("recovery.trash.retentionDays", String.valueOf(recoveryTrashRetentionDays));

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

