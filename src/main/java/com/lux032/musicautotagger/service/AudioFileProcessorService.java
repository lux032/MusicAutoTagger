package com.lux032.musicautotagger.service;

import lombok.extern.slf4j.Slf4j;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ProcessResult;
import com.lux032.musicautotagger.util.FileSystemUtils;
import com.lux032.musicautotagger.util.I18nUtil;
import com.lux032.musicautotagger.util.MetadataUtils;

import java.io.File;
import java.util.ArrayList;
import com.lux032.musicautotagger.util.AudioFileOrdering;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 音频文件处理核心服务
 * 负责音频文件的识别、元数据获取和处理
 */
@Slf4j
public class AudioFileProcessorService {
    
    private final MusicConfig config;
    private final AudioFingerprintService fingerprintService;
    private final MusicBrainzClient musicBrainzClient;
    private final TagWriterService tagWriter;
    private final LyricsService lyricsService;
    private final ProcessedFileLogger processedLogger;
    private final QuickScanService quickScanService;
    private final CoverArtService coverArtService;
    private final AlbumBatchProcessor albumBatchProcessor;
    private final FailedFileHandler failedFileHandler;
    private final FileSystemUtils fileSystemUtils;
    private final FolderAlbumCache folderAlbumCache;
    private final AudioFormatNormalizer audioFormatNormalizer;
    private final CueSplitService cueSplitService;
    private final Map<String, FolderNormalizationPlan> folderNormalizationPlans = new java.util.concurrent.ConcurrentHashMap<>();
    /** 文件夹路径 -> 尚未处理的音乐文件数（批量 flush 后失效） */
    private final Map<String, Integer> folderUnprocessedCounts = new java.util.concurrent.ConcurrentHashMap<>();
    /** 人工确认队列（可选，未启用时为 null） */
    private ReviewQueueService reviewQueueService;
    /** 人工恢复任务显式指定的专辑根目录，避免隔离目录被误判为监控目录结构。 */
    private final ThreadLocal<File> recoveryAlbumRoot = new ThreadLocal<>();
    
    public AudioFileProcessorService(MusicConfig config,
                                     AudioFingerprintService fingerprintService,
                                     MusicBrainzClient musicBrainzClient,
                                     TagWriterService tagWriter,
                                     LyricsService lyricsService,
                                     ProcessedFileLogger processedLogger,
                                     QuickScanService quickScanService,
                                     CoverArtService coverArtService,
                                     AlbumBatchProcessor albumBatchProcessor,
                                     FailedFileHandler failedFileHandler,
                                     FileSystemUtils fileSystemUtils,
                                     FolderAlbumCache folderAlbumCache) {
        this.config = config;
        this.fingerprintService = fingerprintService;
        this.musicBrainzClient = musicBrainzClient;
        this.tagWriter = tagWriter;
        this.lyricsService = lyricsService;
        this.processedLogger = processedLogger;
        this.quickScanService = quickScanService;
        this.coverArtService = coverArtService;
        this.albumBatchProcessor = albumBatchProcessor;
        this.failedFileHandler = failedFileHandler;
        this.fileSystemUtils = fileSystemUtils;
        this.folderAlbumCache = folderAlbumCache;
        this.audioFormatNormalizer = new AudioFormatNormalizer(config);
        this.cueSplitService = new CueSplitService(config, fileSystemUtils);
    }

    /**
     * 注入人工确认队列（阶段六）。
     * 用 setter 而不是构造参数，是为了保持现有构造器签名兼容。
     */
    public void setReviewQueueService(ReviewQueueService reviewQueueService) {
        this.reviewQueueService = reviewQueueService;
    }

    /**
     * 从 partial/failed 隔离目录手动重试。
     * 显式传入整张专辑的根目录，确保多碟子目录也作为同一个文件夹处理。
     */
    public ProcessResult processRecoveryFile(File audioFile, File albumRootDir) {
        recoveryAlbumRoot.set(albumRootDir);
        try {
            return processAudioFile(audioFile);
        } finally {
            recoveryAlbumRoot.remove();
        }
    }
    
    /**
     * 处理音频文件的核心逻辑（带返回值）- 两阶段处理
     * 阶段1: 识别收集阶段 - 收集专辑信息，不写文件
     * 阶段2: 批量写入阶段 - 确定专辑后统一批量处理
     * @return ProcessResult 表示处理结果类型：
     *         - SUCCESS: 处理成功
     *         - DELAY_RETRY: 需要延迟重试（如检测到临时文件），不增加重试计数
     *         - NETWORK_ERROR_RETRY: 网络错误需要重试，增加重试计数
     *         - PERMANENT_FAIL: 永久失败，不重试
     */
    public ProcessResult processAudioFile(File audioFile) {
        log.info(I18nUtil.getMessage("main.title.separator"));
        log.info(I18nUtil.getMessage("main.processing.file"), audioFile.getName());
        LogCollector.addLog("INFO", I18nUtil.getMessage("main.title.separator"));
        LogCollector.addLog("INFO", I18nUtil.getMessage("main.processing.file", audioFile.getName()));

        File originalAudioFile = audioFile;
        AudioFormatNormalizer.NormalizationResult normalizationResult = null;
        File processingAudioFile = audioFile;
        boolean deferNormalizationCleanup = false;

        try {
            // 0. 检查文件是否已处理过
            if (processedLogger.isFileProcessed(originalAudioFile)) {
                log.info(I18nUtil.getMessage("main.file.already.processed"), originalAudioFile.getName());
                return ProcessResult.SUCCESS; // 已处理，返回成功
            }
            
            // 0.3. 检查文件夹是否有临时文件(下载未完成)
            if (fileSystemUtils.hasTempFilesInFolder(originalAudioFile)) {
                log.warn(I18nUtil.getMessage("main.temp.files.detected"), originalAudioFile.getParentFile().getName());
                // 返回 DELAY_RETRY 表示需要延迟重试，但不消耗重试次数
                // 因为这不是真正的处理失败，只是暂时不适合处理
                return ProcessResult.DELAY_RETRY;
            }

            // 0.35 cue 分割检测（仅 cue + 单一大文件场景）
            CueSplitService.SplitResult splitResult = cueSplitService.trySplit(originalAudioFile);
            if (splitResult.isPerformed()) {
                log.info("Cue split detected, output dir: {}", splitResult.getOutputDir().getAbsolutePath());

                ProcessResult aggregateResult = ProcessResult.SUCCESS;
                for (File splitFile : splitResult.getSplitFiles()) {
                    ProcessResult result = processAudioFile(splitFile);
                    if (result == ProcessResult.NETWORK_ERROR_RETRY) {
                        aggregateResult = ProcessResult.NETWORK_ERROR_RETRY;
                    } else if (result == ProcessResult.DELAY_RETRY &&
                               aggregateResult == ProcessResult.SUCCESS) {
                        aggregateResult = ProcessResult.DELAY_RETRY;
                    }
                }

                if (aggregateResult == ProcessResult.SUCCESS) {
                    try {
                        String album = splitResult.getCueInfo() != null ?
                            splitResult.getCueInfo().getAlbumTitle() : "Unknown Album";
                        String albumArtist = splitResult.getCueInfo() != null ?
                            splitResult.getCueInfo().getAlbumArtist() : "Cue Split";
                        processedLogger.markFileAsProcessed(
                            originalAudioFile,
                            "CUE_SPLIT",
                            albumArtist != null ? albumArtist : "Cue Split",
                            originalAudioFile.getName(),
                            album != null ? album : "Unknown Album"
                        );
                    } catch (Exception e) {
                        log.warn("Failed to mark cue source file as processed: {}", e.getMessage());
                    }
                }

                return aggregateResult;
            }

            // 0.5. 获取专辑根目录（监控目录的第一级子目录）
            File explicitRecoveryRoot = recoveryAlbumRoot.get();
            File albumRootDir = explicitRecoveryRoot != null
                ? explicitRecoveryRoot : fileSystemUtils.getAlbumRootDirectory(originalAudioFile);
            String folderPath = albumRootDir.getAbsolutePath();
            
            // 0.5.1 统计专辑根目录内音乐文件数量，并评估这个数字是否适合参与专辑匹配。
            // count 始终可用于队列/样本规模控制；
            //   reliable=false（hard）  -> 禁止曲目数参与评分与门槛
            //   safeForFastLock=false     -> 仅禁用「第一文件立即锁定」这类激进优化
            FileSystemUtils.MusicFileCountResult musicFileCount =
                fileSystemUtils.inspectMusicFilesInFolder(originalAudioFile, explicitRecoveryRoot);
            int musicFilesInFolder = musicFileCount.getCount();
            boolean musicFilesCountReliable = musicFileCount.isReliable();
            boolean safeForFastLock = musicFileCount.isSafeForFastLock();
            
            // 0.5.2 阶段六 #18：该文件夹已进入「待人工确认」队列时，不得再自动处理。
            // 待确认的文件故意不写标签、不改名、不移动，也就不会被记为 processed，
            // 如果不在这里拦住，下一轮扫描会反复重新识别并重复入队。
            if (reviewQueueService != null && reviewQueueService.isFolderUnderReview(folderPath)) {
                log.info("文件夹已在待人工确认队列中，跳过自动处理: {}", albumRootDir.getName());
                return ProcessResult.SUCCESS;
            }

            // 0.6. 检测是否为散落在监控目录根目录的单个文件（保底处理）
            boolean isLooseFileInMonitorRoot = explicitRecoveryRoot == null
                && fileSystemUtils.isLooseFileInMonitorRoot(originalAudioFile);

            // 0.4 规格检查与规范化（文件夹级别）
            FolderNormalizationPlan normalizationPlan = null;
            if (config.isAudioNormalizeEnabled()) {
                normalizationPlan = getOrPrepareNormalizationPlan(originalAudioFile, albumRootDir, isLooseFileInMonitorRoot);
                normalizationResult = normalizationPlan.getResult(originalAudioFile);
                processingAudioFile = normalizationResult.getProcessingFile();
            } else {
                normalizationResult = audioFormatNormalizer.normalizeIfNeeded(originalAudioFile);
                processingAudioFile = normalizationResult.getProcessingFile();
            }
            
            MusicMetadata detailedMetadata = null;
            boolean isQuickScanMode = false; // 标记是否使用快速扫描模式处理
            
            // ===== 优先检查文件夹专辑缓存 =====
            FolderAlbumCache.CachedAlbumInfo cachedAlbum = albumBatchProcessor.getCachedAlbum(
                folderPath, musicFilesInFolder, musicFilesCountReliable);
            
            String lockedAlbumTitle = null;
            String lockedAlbumArtist = null;
            String lockedReleaseGroupId = null;
            String lockedReleaseId = null;  // 新增：具体的 Release ID，用于确保版本一致性
            String lockedReleaseDate = null;

            if (cachedAlbum != null) {
                // 已有缓存专辑，锁定专辑信息，但仍需指纹识别获取单曲详细信息
                log.info("✓ 使用文件夹缓存的专辑信息");
                log.info("专辑: {} - {}", cachedAlbum.getAlbumArtist(), cachedAlbum.getAlbumTitle());

                lockedAlbumTitle = cachedAlbum.getAlbumTitle();
                lockedAlbumArtist = cachedAlbum.getAlbumArtist();
                lockedReleaseGroupId = cachedAlbum.getReleaseGroupId();
                lockedReleaseId = cachedAlbum.getReleaseId();  // 获取具体的 Release ID
                lockedReleaseDate = cachedAlbum.getReleaseDate();
                
            } else if (folderAlbumCache.isFolderUnresolved(folderPath)) {
                // 该文件夹已被判定为「专辑未确定」（如 MusicBrainz 未收录的精选集）。
                // 后续新增文件不再尝试快速扫描，避免同一文件夹出现
                // 「一部分走 unresolved、一部分被归入另一张专辑」的分裂结果。
                log.info("文件夹已判定为「专辑未确定」，跳过快速扫描，直接进入指纹识别");
            } else if (!isLooseFileInMonitorRoot) {
                // 没有缓存且不是散落文件，进行快速扫描
                log.info("尝试第一级快速扫描（基于标签和文件夹名称）...");
                LogCollector.addLog("INFO", I18nUtil.getMessage("main.quick.scan.attempt", audioFile.getName()));
                List<Integer> folderDurations = null;
                if (normalizationPlan != null) {
                    folderDurations = normalizationPlan.getOrComputeDurationSequence(fingerprintService);
                    folderAlbumCache.cacheFolderDurationSequence(folderPath, folderDurations);
                } else if (explicitRecoveryRoot != null) {
                    List<File> recoveryFiles = new ArrayList<>();
                    fileSystemUtils.collectAudioFilesForMarking(explicitRecoveryRoot, recoveryFiles);
                    recoveryFiles.sort(AudioFileOrdering.comparator());
                    folderDurations = fingerprintService.extractDurationSequence(recoveryFiles);
                    folderAlbumCache.cacheFolderDurationSequence(folderPath, folderDurations);
                }
                QuickScanService.QuickScanResult quickResult = quickScanService.quickScan(
                    originalAudioFile,
                    musicFilesInFolder,
                    musicFilesCountReliable,
                    folderDurations
                );

                if (quickResult != null && quickResult.isHighConfidence()) {
                    // 快速扫描成功，锁定专辑信息
                    log.info("✓ 快速扫描成功，锁定专辑信息");
                    LogCollector.addLog("SUCCESS", I18nUtil.getMessage("main.quick.scan.success", audioFile.getName()));
                    isQuickScanMode = true; // 标记为快速扫描模式
                    MusicMetadata quickMetadata = quickResult.getMetadata();
                    
                    lockedAlbumTitle = quickMetadata.getAlbum();
                    lockedAlbumArtist = quickMetadata.getAlbumArtist() != null ?
                        quickMetadata.getAlbumArtist() : quickMetadata.getArtist();
                    lockedReleaseGroupId = quickMetadata.getReleaseGroupId();
                    lockedReleaseDate = quickMetadata.getReleaseDate();
                    
                    // 立即将专辑信息写入文件夹缓存。
                    // 虽然入口叫 QuickScan，但成功条件已经是「整文件夹官方时长序列 DTW >= 90%」，
                    // 证据本质上是 DURATION_SEQUENCE，而不是仅凭标签/文件夹名的弱证据。
                    // 若仍标成 QUICK_SCAN，AcoustID 返回旧单曲 RG 时一次冲突就会清缓存，
                    // 样本收集器随之反复重置，最终既破坏正确锁定，也进不了人工确认队列。
                    FolderAlbumCache.CachedAlbumInfo albumInfo = new FolderAlbumCache.CachedAlbumInfo(
                        lockedReleaseGroupId,
                        quickMetadata.getReleaseId(),
                        lockedAlbumTitle,
                        lockedAlbumArtist,
                        quickMetadata.getTrackCount(),
                        lockedReleaseDate,
                        quickResult.getSimilarity(),
                        FolderAlbumCache.CacheSource.DURATION_SEQUENCE
                    );
                    albumBatchProcessor.setFolderAlbum(folderPath, albumInfo);
                    log.info("已将快速扫描的整专时长匹配结果缓存到文件夹级别（证据: DURATION_SEQUENCE）");
                }
            } else {
                // 散落文件，跳过快速扫描
                log.info("散落文件跳过快速扫描，将直接进入随缘匹配模式（指纹识别）");
            }
            
            // ===== 保底处理：如果是散落文件，跳过专辑匹配，直接指纹识别 =====
            if (isLooseFileInMonitorRoot) {
                log.info("========================================");
                log.info("检测到散落在监控目录的单个文件，启用保底处理机制");
                log.info("跳过专辑匹配，直接进行指纹识别");
                log.info("========================================");
            }
            
            // ===== 无论快速扫描是否成功，都进行指纹识别获取单曲详细信息 =====
            log.info("正在进行音频指纹识别以获取单曲详细元数据...");
            LogCollector.addLog("INFO", I18nUtil.getMessage("main.fingerprint.identifying", audioFile.getName()));
            AudioFingerprintService.AcoustIdResult acoustIdResult =
                fingerprintService.identifyAudioFile(processingAudioFile);

            // ===== 阶段五 #17：缓存反悔检查 =====
            // 以前 validateAgainstCache() 实际从未被调用——一旦专辑被锁定，
            // 后续每个文件都会被 applyLockedAlbumInfo() 强行套上锁定专辑，
            // 永远「匹配」，反悔机制形同虚设。
            // 现改为用指纹识别返回的**原始候选集**反验缓存。
            if (cachedAlbum != null && !isLooseFileInMonitorRoot) {
                List<FolderAlbumCache.CandidateReleaseGroup> candidatesForValidation =
                    collectCandidateReleaseGroups(acoustIdResult);

                // AcoustID 经常把伴奏/off-vocal 识别成同长度的原唱单曲，且返回的候选
                // Release Group 不包含实际所属的实体发行。这不是否定文件夹级整专时长匹配的可靠反证。
                // 否则 QUICK_SCAN 会被第 2/5/6 首反复清除，样本收集器也随之重置，永远到不了人工确认。
                boolean versionedTrack = MetadataUtils.hasVersionQualifier(originalAudioFile.getName());
                FolderAlbumCache.CacheValidationResult validation;
                if (versionedTrack) {
                    validation = FolderAlbumCache.CacheValidationResult.VALID;
                    log.info("特殊版本曲目跳过 AcoustID 候选对文件夹专辑的反悔检查: {}",
                        originalAudioFile.getName());
                } else {
                    validation = folderAlbumCache.validateAgainstCandidates(
                        folderPath, originalAudioFile.getName(), candidatesForValidation);
                }

                // 只有 INVALIDATED 才代表缓存真的被丢弃；
                // CONFLICT_RETAINED 表示冲突已登记但尚未达阈值，缓存仍需继续使用。
                if (validation == FolderAlbumCache.CacheValidationResult.INVALIDATED) {
                    log.warn("专辑缓存已因与识别结果冲突而被丢弃，本文件改走样本收集流程: {}",
                        originalAudioFile.getName());
                    cachedAlbum = null;
                    lockedAlbumTitle = null;
                    lockedAlbumArtist = null;
                    lockedReleaseGroupId = null;
                    lockedReleaseId = null;
                    lockedReleaseDate = null;
                }
            }

            // ===== 关键修复：在获取详细元数据之前，先执行时长序列匹配确定专辑 =====
            // 这样第一个文件就能使用正确的 preferredReleaseGroupId
            if (lockedAlbumTitle == null && !isLooseFileInMonitorRoot &&
                acoustIdResult.getRecordings() != null && !acoustIdResult.getRecordings().isEmpty()) {
                
                // 收集 AcoustID 返回的所有候选专辑
                java.util.List<FolderAlbumCache.CandidateReleaseGroup> allCandidates =
                    collectCandidateReleaseGroups(acoustIdResult);
                
                if (!allCandidates.isEmpty()) {
                    log.info("第一个文件处理：收集到 {} 个候选专辑，立即执行时长序列匹配", allCandidates.size());
                    
                    // 立即执行时长序列匹配
                    FolderAlbumCache.CachedAlbumInfo determinedAlbum =
                        folderAlbumCache.determineAlbumWithDurationSequence(
                            folderPath, allCandidates, musicFilesInFolder, safeForFastLock);
                    
                    if (determinedAlbum != null) {
                        // 时长序列匹配成功，设置到缓存中（尊重优先级）
                        albumBatchProcessor.setFolderAlbum(folderPath, determinedAlbum);
                        
                        // 关键修复：从缓存重新获取专辑信息，确保使用优先级更高的正确值
                        // 这样可以避免时长序列匹配返回的 albumArtist 覆盖快速扫描的正确值
                        FolderAlbumCache.CachedAlbumInfo actualCached =
                            albumBatchProcessor.getCachedAlbum(folderPath, musicFilesInFolder, musicFilesCountReliable);
                        if (actualCached != null) {
                            lockedAlbumTitle = actualCached.getAlbumTitle();
                            lockedAlbumArtist = actualCached.getAlbumArtist();
                            lockedReleaseGroupId = actualCached.getReleaseGroupId();
                            lockedReleaseId = actualCached.getReleaseId();
                            lockedReleaseDate = actualCached.getReleaseDate();
                        } else {
                            // 备选：使用返回值（通常不会走到这里）
                            lockedAlbumTitle = determinedAlbum.getAlbumTitle();
                            lockedAlbumArtist = determinedAlbum.getAlbumArtist();
                            lockedReleaseGroupId = determinedAlbum.getReleaseGroupId();
                            lockedReleaseId = determinedAlbum.getReleaseId();
                            lockedReleaseDate = determinedAlbum.getReleaseDate();
                        }

                        log.info("✓ 第一个文件即确定专辑: {} (专辑艺术家: {}, Release Group ID: {}, Release ID: {})",
                            lockedAlbumTitle, lockedAlbumArtist, lockedReleaseGroupId, lockedReleaseId);
                    } else {
                        log.info("时长序列匹配未能确定专辑，将在后续样本收集后再尝试");
                    }
                }
            }

            if (acoustIdResult.getRecordings() == null || acoustIdResult.getRecordings().isEmpty()) {
                // 如果没有锁定的专辑信息，则识别失败
                if (lockedAlbumTitle == null) {
                    log.warn(I18nUtil.getMessage("main.fingerprint.failed"), audioFile.getName());
                    log.info("该文件的 AcoustID 未关联到 MusicBrainz 录音信息");
                    LogCollector.addLog("WARN", I18nUtil.getMessage("main.acoustid.no.match", audioFile.getName()));
                    log.info("建议：手动添加标签或等待 MusicBrainz 社区完善数据");

                    // 处理识别失败
                    if (isLooseFileInMonitorRoot) {
                        failedFileHandler.handleLooseFileFailed(originalAudioFile, processingAudioFile);
                    } else {
                        failedFileHandler.handleAlbumFileFailed(originalAudioFile, albumRootDir);
                        // 该文件不会进入 pending，但之前统计的「剩余未处理数」把它算进去了。
                        // 必须失效重算，否则 pendingCount 永远追不上 remainingUnprocessed，
                        // 剩余待处理文件会一直挂到关机才被 flush。
                        folderUnprocessedCounts.remove(folderPath);
                        fileSystemUtils.invalidateInspection(folderPath);
                    }

                    return ProcessResult.PERMANENT_FAIL; // 识别失败，不重试但记录
                } else {
                    // 有锁定的专辑信息（快速扫描成功），使用锁定的专辑信息继续处理
                    log.info("AcoustID 未关联到详细录音信息，但快速扫描已锁定专辑，继续处理");
                    LogCollector.addLog("INFO", I18nUtil.getMessage("main.acoustid.no.match.use.quick.scan", audioFile.getName()));
                    LogCollector.addLog("INFO", I18nUtil.getMessage("main.quick.scan.locked.album", lockedAlbumArtist, lockedAlbumTitle));
                    
                    MusicMetadata sourceTagsForFallback = tagWriter.readTags(originalAudioFile);
                    detailedMetadata = MetadataUtils.createMetadataFromQuickScan(
                        sourceTagsForFallback,
                        lockedAlbumTitle,
                        lockedAlbumArtist,
                        lockedReleaseGroupId,
                        lockedReleaseDate,
                        originalAudioFile.getName()
                    );
                }
            } else {
                // 指纹识别成功，获取详细元数据
                // 关键：指纹识别成功获取到recordings，应该使用指纹识别模式（而非快速扫描模式）
                isQuickScanMode = false;

                AudioFingerprintService.RecordingInfo bestMatch = MetadataUtils.findBestRecordingMatch(
                    acoustIdResult.getRecordings(),
                    lockedReleaseGroupId,
                    originalAudioFile.getName()
                );

                // 检查 bestMatch 是否有完整信息
                boolean hasCompleteInfo = (bestMatch.getTitle() != null && !bestMatch.getTitle().isEmpty() &&
                                          bestMatch.getArtist() != null && !bestMatch.getArtist().isEmpty());

                // 处理可能为 null 或空的情况
                String displayArtist = (bestMatch.getArtist() != null && !bestMatch.getArtist().isEmpty())
                    ? bestMatch.getArtist() : "(待从MusicBrainz获取)";
                String displayTitle = (bestMatch.getTitle() != null && !bestMatch.getTitle().isEmpty())
                    ? bestMatch.getTitle() : "(待从MusicBrainz获取)";

                if (hasCompleteInfo) {
                    log.info("识别成功: {} - {}", displayArtist, displayTitle);
                    LogCollector.addLog("SUCCESS", I18nUtil.getMessage("main.identify.success", displayArtist, displayTitle));
                } else {
                    log.info("AcoustID 返回了 Recording ID: {}，但缺少详细信息，将从 MusicBrainz 查询", bestMatch.getRecordingId());
                    LogCollector.addLog("INFO", I18nUtil.getMessage("main.acoustid.has.recording.id"));
                }

                // 始终传入实际的文件数量，让 MusicBrainz 在回退匹配时能正确选择
                // selectBestRelease() 会优先匹配 preferredReleaseGroupId，匹配失败时才使用文件数量
                int musicFilesParam = musicFilesInFolder;

                // 使用 AcoustID 指纹识别时获取的文件时长（更可靠）
                int fileDurationSeconds = acoustIdResult.getDuration();
                if (fileDurationSeconds > 0) {
                    log.debug("使用 AcoustID 获取的文件时长: {}秒", fileDurationSeconds);
                } else {
                    log.warn("AcoustID 未返回文件时长信息");
                }

                // 通过 MusicBrainz 获取详细元数据（包含作词、作曲、风格等）
                // 即使 AcoustID 返回的信息不完整，只要有 Recording ID 就可以查询
                // 关键修复：传递 lockedReleaseId 以确保版本一致性，传递 fileDurationSeconds 用于时长匹配备选方案
                log.info("正在从 MusicBrainz 获取详细元数据 (Recording ID: {})...", bestMatch.getRecordingId());

                // 关键修复：只有「监控目录根部的散落单文件」才允许按曲目数猜专辑（随缘模式）。
                // 对于专辑文件夹，如果前面的快速扫描和时长序列匹配都没能锁定专辑，
                // 说明 MusicBrainz 里很可能根本没有这张专辑（如新发行的精选集），
                // 此时宁可返回「专辑未确定」，也不能把它归入曲目所属的旧专辑。
                boolean allowAlbumGuess = isLooseFileInMonitorRoot;
                if (!allowAlbumGuess && lockedReleaseGroupId == null) {
                    log.info("专辑文件夹尚未锁定专辑，已禁用「按曲目数猜测专辑」，避免错误归入旧专辑");
                }
                detailedMetadata = musicBrainzClient.getRecordingById(
                    bestMatch.getRecordingId(), musicFilesParam, musicFilesCountReliable,
                    lockedReleaseGroupId, lockedReleaseId, fileDurationSeconds, allowAlbumGuess);

                if (detailedMetadata == null) {
                    log.warn("无法从 MusicBrainz 获取详细元数据");
                    if (hasCompleteInfo) {
                        // 如果 AcoustID 有完整信息，使用它作为备选
                        detailedMetadata = MetadataUtils.convertToMusicMetadata(bestMatch);
                    } else {
                        // AcoustID 和 MusicBrainz 都没有完整信息
                        log.warn("AcoustID 和 MusicBrainz 均无法提供完整元数据");
                        // 如果有快速扫描锁定的专辑信息，使用它
                        if (lockedAlbumTitle != null) {
                            log.info("使用快速扫描锁定的专辑信息作为备选");
                            MusicMetadata sourceTagsForFallback = tagWriter.readTags(originalAudioFile);
                            detailedMetadata = MetadataUtils.createMetadataFromQuickScan(
                                sourceTagsForFallback,
                                lockedAlbumTitle,
                                lockedAlbumArtist,
                                lockedReleaseGroupId,
                                lockedReleaseDate,
                                originalAudioFile.getName()
                            );
                        } else {
                            // 完全没有信息，创建基本元数据
                            detailedMetadata = new MusicMetadata();
                            detailedMetadata.setRecordingId(bestMatch.getRecordingId());
                        }
                    }
                } else {
                    // 成功从 MusicBrainz 获取到详细信息
                    log.info("✓ 成功从 MusicBrainz 获取详细元数据: {} - {}",
                        detailedMetadata.getArtist(), detailedMetadata.getTitle());
                    
                    // ===== 关键修复：检查返回的专辑是否匹配锁定的专辑 =====
                    // 如果已锁定专辑，但 MusicBrainz 返回的 Recording 不属于锁定专辑，
                    // 则使用"强制使用锁定专辑"模式从锁定专辑中按时长查找匹配曲目
                    //
                    // 关键改进：不仅检查 Release Group ID，还要检查 Release ID
                    // 因为同一个 Release Group 下可能有多个不同的 Release（如 Digital Soundtrack vs Original Soundtrack）
                    // 时长序列匹配可能选择了特定的 Release，需要确保版本一致性
                    if (lockedReleaseGroupId != null && !lockedReleaseGroupId.isEmpty()) {
                        
                        String returnedReleaseGroupId = detailedMetadata.getReleaseGroupId();
                        String returnedReleaseId = detailedMetadata.getReleaseId();
                        
                        // 检查 Release Group ID 是否匹配
                        boolean releaseGroupMismatch = (returnedReleaseGroupId == null ||
                                                returnedReleaseGroupId.isEmpty() ||
                                                !lockedReleaseGroupId.equals(returnedReleaseGroupId));
                        
                        // 关键改进：即使 Release Group ID 匹配，也要检查 Release ID 是否匹配
                        // 这可以避免同一 Release Group 下选择错误的 Release 版本
                        boolean releaseIdMismatch = false;
                        if (lockedReleaseId != null && !lockedReleaseId.isEmpty()) {
                            releaseIdMismatch = (returnedReleaseId == null ||
                                                returnedReleaseId.isEmpty() ||
                                                !lockedReleaseId.equals(returnedReleaseId));
                            if (releaseIdMismatch && !releaseGroupMismatch) {
                                log.info("检测到 Release ID 不匹配（但 Release Group ID 相同）");
                                log.info("  锁定 Release ID: {} vs 返回 Release ID: {}", lockedReleaseId, returnedReleaseId);
                            }
                        }
                        
                        boolean albumMismatch = releaseGroupMismatch || releaseIdMismatch;
                        
                        if (albumMismatch) {
                            log.warn("⚠ 检测到专辑版本不匹配！");
                            if (releaseGroupMismatch) {
                                log.warn("  Release Group ID 不匹配:");
                                log.warn("    锁定: {}", lockedReleaseGroupId);
                                log.warn("    返回: {}", returnedReleaseGroupId);
                            }
                            if (releaseIdMismatch) {
                                log.warn("  Release ID 不匹配:");
                                log.warn("    锁定: {} ({})", lockedReleaseId, lockedAlbumTitle);
                                log.warn("    返回: {} ({})", returnedReleaseId, detailedMetadata.getAlbum());
                            }
                            
                            // 如果有具体的 Release ID，尝试强制匹配
                            if (lockedReleaseId != null && !lockedReleaseId.isEmpty()) {
                                log.info("启用强制使用锁定专辑模式（Release ID: {}）...", lockedReleaseId);
                                
                                // 调用强制专辑匹配方法
                                MusicMetadata forcedMetadata = musicBrainzClient.getTrackFromLockedAlbumByDuration(
                                    lockedReleaseId,
                                    lockedReleaseGroupId,
                                    fileDurationSeconds,
                                    lockedAlbumTitle,
                                    lockedAlbumArtist
                                );
                                
                                if (forcedMetadata != null) {
                                    // 强制匹配成功，使用新的元数据
                                    log.info("✓ 强制专辑匹配成功，使用锁定专辑中的曲目信息");
                                    detailedMetadata = forcedMetadata;
                                } else {
                                    // 强制匹配失败，保留原有元数据但应用锁定的专辑信息
                                    log.warn("强制专辑匹配失败，将保留 AcoustID 识别的曲目信息但覆盖专辑信息");
                                }
                            } else {
                                // 没有具体的 Release ID，尝试通过 Release Group ID 强制匹配
                                log.warn("没有锁定的 Release ID，尝试通过 Release Group ID 强制匹配");
                                
                                MusicMetadata forcedMetadataByRG = musicBrainzClient.getTrackFromLockedAlbumByReleaseGroup(
                                    lockedReleaseGroupId,
                                    fileDurationSeconds,
                                    musicFilesInFolder,
                                    musicFilesCountReliable,
                                    lockedAlbumTitle,
                                    lockedAlbumArtist
                                );
                                
                                if (forcedMetadataByRG != null) {
                                    // 强制匹配成功，使用新的元数据
                                    log.info("✓ 通过 Release Group ID 强制专辑匹配成功");
                                    detailedMetadata = forcedMetadataByRG;
                                } else {
                                    // 强制匹配失败，保留原有元数据但应用锁定的专辑信息
                                    log.warn("通过 Release Group ID 强制匹配也失败，将保留 AcoustID 识别的曲目信息但覆盖专辑信息");
                                }
                            }
                        }
                    }
                }

                // 如果有锁定的专辑信息，用锁定的信息覆盖（确保专辑信息不被改变）
                MetadataUtils.applyLockedAlbumInfo(detailedMetadata, lockedAlbumTitle, lockedAlbumArtist, lockedReleaseGroupId, lockedReleaseDate);
            }

            // 对明确的伴奏/特殊版本，强制按源标签保留标题与曲序。
            // 必须在 MusicBrainz 的「按时长强制匹配」之后执行，因为同一张专辑中
            // 原唱和伴奏通常等长，仅按时长必然会优先命中前面的原唱曲目。
            if (MetadataUtils.hasVersionQualifier(originalAudioFile.getName())) {
                MusicMetadata versionSource = tagWriter.readTags(originalAudioFile);
                if (versionSource != null) {
                    detailedMetadata = MetadataUtils.mergeMetadata(versionSource, detailedMetadata);
                }
            }

            // ===== 读取源文件已有标签并合并 =====
            // 在快速扫描锁定专辑但音频指纹数据库缺失的情况下，保留源文件的作曲、作词、歌词、风格等信息
            log.info("读取源文件已有标签信息...");
            MusicMetadata sourceMetadata = tagWriter.readTags(originalAudioFile);
            if (sourceMetadata != null) {
                log.info("合并源文件标签信息...");
                detailedMetadata = MetadataUtils.mergeMetadata(sourceMetadata, detailedMetadata);
            } else {
                log.debug("源文件没有可读取的标签信息");
            }
            
            // 4. 获取封面图片(多层降级策略)
            byte[] coverArtData = coverArtService.getCoverArtWithFallback(
                originalAudioFile, detailedMetadata, lockedReleaseGroupId, isLooseFileInMonitorRoot);
            
            if (coverArtData != null && coverArtData.length > 0) {
                log.info("✓ 成功获取封面图片");
            } else {
                log.info(I18nUtil.getMessage("main.cover.not.found"));
            }
            
            // 4.5 获取歌词 (LrcLib)
            log.info(I18nUtil.getMessage("main.getting.lyrics"));
            String lyrics = lyricsService.getLyrics(
                detailedMetadata.getTitle(),
                detailedMetadata.getArtist(),
                detailedMetadata.getAlbum(),
                0
            );
            if (lyrics != null && !lyrics.isEmpty()) {
                detailedMetadata.setLyrics(lyrics);
            } else {
                log.info(I18nUtil.getMessage("main.lyrics.not.found"));
            }
            
            // 5. 文件夹级别的专辑锁定处理
            // 注意：散落文件跳过专辑锁定和投票机制，直接处理
            if (isLooseFileInMonitorRoot) {
                log.info("散落文件保底处理：直接写入元数据（随缘模式）");
                albumBatchProcessor.processAndWriteFile(processingAudioFile, originalAudioFile, detailedMetadata, coverArtData, false);
            } else if (lockedAlbumTitle != null) {
                // 已有锁定的专辑信息（来自快速扫描或缓存），直接处理文件
                log.info("使用已锁定的专辑信息: {}", lockedAlbumTitle);
                albumBatchProcessor.processAndWriteFile(processingAudioFile, originalAudioFile, detailedMetadata, coverArtData, isQuickScanMode);
            } else {
                // 未锁定专辑：收集样本进行投票
                log.info("启用文件夹级别专辑锁定（{}首音乐文件）", musicFilesInFolder);

                int trackCount = detailedMetadata.getTrackCount();

                // 收集 AcoustID 返回的所有候选 ReleaseGroups
                java.util.List<FolderAlbumCache.CandidateReleaseGroup> allCandidates =
                    collectCandidateReleaseGroups(acoustIdResult);
                log.info("收集到 {} 个候选专辑用于时长序列匹配", allCandidates.size());

                FolderAlbumCache.AlbumIdentificationInfo albumInfo = new FolderAlbumCache.AlbumIdentificationInfo(
                    detailedMetadata.getReleaseGroupId(),
                    detailedMetadata.getAlbum(),
                    detailedMetadata.getAlbumArtist() != null ? detailedMetadata.getAlbumArtist() : detailedMetadata.getArtist(),
                    trackCount,
                    detailedMetadata.getReleaseDate(),
                    allCandidates
                );

                // 关键修复：使用原子操作添加待处理文件，避免竞态条件
                albumBatchProcessor.addPendingFile(
                    folderPath,
                    originalAudioFile,
                    processingAudioFile,
                    normalizationResult != null ? normalizationResult.getTempDirectory() : null,
                    detailedMetadata,
                    coverArtData
                );
                deferNormalizationCleanup = normalizationResult != null && normalizationResult.isConverted();

                // 尝试确定专辑
                // 关键：传入「剩余未处理文件数」而不是让 FolderAlbumCache 自己去看 pending 队列长度。
                // pending 队列在第一个文件时恒为 1，会把所需样本数压到 1，
                // 导致第一首文件立即以单样本进入普通分析，绕过快速通道的严格门槛。
                int remainingUnprocessed = getRemainingUnprocessedCount(folderPath, albumRootDir);
                FolderAlbumCache.CachedAlbumInfo determinedAlbum = albumBatchProcessor.tryDetermineAlbum(
                    folderPath,
                    audioFile.getName(),
                    musicFilesInFolder,
                    albumInfo,
                    remainingUnprocessed,
                    musicFilesCountReliable
                );

                if (determinedAlbum != null) {
                    // 专辑已确定，批量处理所有待处理文件
                    log.info("========================================");
                    log.info("✓ 文件夹专辑已确定: {}", determinedAlbum.getAlbumTitle());
                    log.info("开始批量处理文件夹内的所有文件...");
                    log.info("========================================");

                    albumBatchProcessor.processPendingFilesWithAlbum(folderPath, determinedAlbum);
                    folderUnprocessedCounts.remove(folderPath);
                    fileSystemUtils.invalidateInspection(folderPath);
                } else {
                    log.info("专辑收集中，待处理文件已加入队列: {}", originalAudioFile.getName());

                    // 检查是否所有文件都已加入待处理队列但专辑仍未确定
                    // 这种情况可能发生在样本收集过程中部分文件识别失败
                    // 注意：源文件处理后不会离开监控目录，所以不能只拿 musicFilesInFolder 比较，
                    // 否则「一张专辑只剩少量新文件」时永远触发不了，文件会一直挂到关机。
                    int pendingCount = albumBatchProcessor.getPendingFileCount(folderPath);
                    if (pendingCount >= musicFilesInFolder || pendingCount >= remainingUnprocessed) {
                        log.warn("所有待处理文件均已入队但专辑仍未确定（pending={}, 剩余未处理={}, 文件夹总数={}）",
                            pendingCount, remainingUnprocessed, musicFilesInFolder);
                        albumBatchProcessor.forceProcessPendingFiles(folderPath, albumInfo);
                        folderUnprocessedCounts.remove(folderPath);
                        fileSystemUtils.invalidateInspection(folderPath);
                    }
                }
            }

            return ProcessResult.SUCCESS; // 处理成功

        } catch (java.io.IOException e) {
            // 网络异常（包括 SocketException），返回 NETWORK_ERROR_RETRY 以触发重试并增加重试计数
            log.error(I18nUtil.getMessage("main.network.error"), originalAudioFile.getName(), e.getMessage());
            log.info(I18nUtil.getMessage("main.retry.queued"));
            return ProcessResult.NETWORK_ERROR_RETRY;

        } catch (Exception e) {
            // 其他异常（如识别失败），不重试，但必须记录到数据库避免静默丢失
            log.error(I18nUtil.getMessage("main.process.error"), originalAudioFile.getName(), e);

            // 关键修复：记录失败文件到数据库，避免文件"静默丢失"
            try {
                processedLogger.markFileAsProcessed(
                    originalAudioFile,
                    "FAILED",
                    "处理异常: " + e.getClass().getSimpleName(),
                    originalAudioFile.getName(),
                    "Unknown Album"
                );
                log.info("已将异常失败文件记录到数据库: {}", originalAudioFile.getName());
            } catch (Exception recordError) {
                log.error("记录异常失败文件到数据库失败: {} - {}", originalAudioFile.getName(), recordError.getMessage());
            }

            return ProcessResult.PERMANENT_FAIL; // 返回永久失败避免重试（非网络问题）

        } finally {
            if (!deferNormalizationCleanup && normalizationResult != null && normalizationResult.isConverted()) {
                audioFormatNormalizer.cleanup(normalizationResult);
            }
            log.info("========================================");
        }
    }

    /**
     * 获取文件夹内「尚未被处理过」的音乐文件总数
     *
     * 为什么需要它：
     * 源文件处理后是被复制到输出目录而非移走，仍然留在监控目录里，
     * 因此 countMusicFilesInFolder() 数的是磁盘上的全部文件，不会随处理进度缩小。
     * 而样本收集需要知道「本次运行到底还有多少文件会进来」。
     *
     * 结果按文件夹缓存，避免 DB 模式下每个文件都做 N 次查询。
     * 批量 flush 后会失效重算。
     */
    private int getRemainingUnprocessedCount(String folderPath, File albumRootDir) {
        Integer cached = folderUnprocessedCounts.get(folderPath);
        if (cached != null) {
            return cached;
        }

        int count = 0;
        try {
            List<File> audioFiles = new ArrayList<>();
            fileSystemUtils.collectAudioFilesForMarking(albumRootDir, audioFiles);
            for (File f : audioFiles) {
                if (processedLogger == null || !processedLogger.isFileProcessed(f)) {
                    count++;
                }
            }
            log.info("文件夹 {} 共 {} 个音乐文件，其中尚未处理: {} 个",
                albumRootDir.getName(), audioFiles.size(), count);
        } catch (Exception e) {
            log.warn("统计剩余未处理文件数失败，退回保守值: {}", e.getMessage());
            count = 0;
        }

        if (count <= 0) {
            // 当前文件本身就是未处理的，至少为 1
            count = 1;
        }

        // 只缓存足够大的统计值。
        // 逐首下载场景下，第一首完成时目录里可能只有 1 个文件，
        // 若把这个“1”永久缓存，后续到达的文件就永远无法把样本要求抬回去。
        // 小值不缓存（此时文件少，重算代价也低），让它能随文件到达而增长。
        if (count >= 3) {
            folderUnprocessedCounts.put(folderPath, count);
        }
        return count;
    }

    /**
     * 从 AcoustID 结果中收集去重后的候选 Release Group 列表
     */
    private List<FolderAlbumCache.CandidateReleaseGroup> collectCandidateReleaseGroups(
            AudioFingerprintService.AcoustIdResult acoustIdResult) {
        List<FolderAlbumCache.CandidateReleaseGroup> candidates = new ArrayList<>();
        if (acoustIdResult == null || acoustIdResult.getRecordings() == null) {
            return candidates;
        }
        for (AudioFingerprintService.RecordingInfo recording : acoustIdResult.getRecordings()) {
            if (recording.getReleaseGroups() == null) {
                continue;
            }
            for (AudioFingerprintService.ReleaseGroupInfo rg : recording.getReleaseGroups()) {
                if (rg == null || rg.getId() == null) {
                    continue;
                }
                boolean exists = candidates.stream()
                    .anyMatch(c -> rg.getId().equals(c.getReleaseGroupId()));
                if (!exists) {
                    candidates.add(new FolderAlbumCache.CandidateReleaseGroup(rg.getId(), rg.getTitle()));
                }
            }
        }
        return candidates;
    }

    private FolderNormalizationPlan getOrPrepareNormalizationPlan(File originalAudioFile, File albumRootDir, boolean isLooseFileInMonitorRoot) {
        String folderPath = isLooseFileInMonitorRoot ?
            originalAudioFile.getParentFile().getAbsolutePath() :
            albumRootDir.getAbsolutePath();

        FolderNormalizationPlan existing = folderNormalizationPlans.get(folderPath);
        if (existing != null) {
            existing.ensureFilePrepared(originalAudioFile, audioFormatNormalizer);
            return existing;
        }

        List<File> audioFiles = new ArrayList<>();
        if (isLooseFileInMonitorRoot) {
            audioFiles.add(originalAudioFile);
        } else {
            fileSystemUtils.collectAudioFilesForMarking(albumRootDir, audioFiles);
        }
        AudioFileOrdering.sort(audioFiles);

        Map<String, AudioFormatNormalizer.NormalizationResult> results = new HashMap<>();
        List<File> orderedOriginalFiles = new ArrayList<>(audioFiles.size());
        for (File file : audioFiles) {
            AudioFormatNormalizer.NormalizationResult result = audioFormatNormalizer.normalizeIfNeeded(file);
            results.put(file.getAbsolutePath(), result);
            orderedOriginalFiles.add(file);
        }

        FolderNormalizationPlan plan = new FolderNormalizationPlan(results, orderedOriginalFiles);
        folderNormalizationPlans.put(folderPath, plan);
        plan.ensureFilePrepared(originalAudioFile, audioFormatNormalizer);
        return plan;
    }

    private static class FolderNormalizationPlan {
        private final Map<String, AudioFormatNormalizer.NormalizationResult> results;
        private final List<File> orderedOriginalFiles;
        private List<Integer> durationSequence;
        /** 有新文件加入、尚未重新排序。排序需读标签，延迟到真正需要有序列表时再做。 */
        private boolean orderDirty;

        private FolderNormalizationPlan(Map<String, AudioFormatNormalizer.NormalizationResult> results,
                                        List<File> orderedOriginalFiles) {
            this.results = results;
            this.orderedOriginalFiles = orderedOriginalFiles;
        }

        private AudioFormatNormalizer.NormalizationResult getResult(File originalFile) {
            AudioFormatNormalizer.NormalizationResult result = results.get(originalFile.getAbsolutePath());
            if (result == null) {
                return AudioFormatNormalizer.NormalizationResult.noop(originalFile);
            }
            return result;
        }

        private void ensureFilePrepared(File originalFile, AudioFormatNormalizer normalizer) {
            String key = originalFile.getAbsolutePath();
            if (results.containsKey(key)) {
                return;
            }
            AudioFormatNormalizer.NormalizationResult result = normalizer.normalizeIfNeeded(originalFile);
            results.put(key, result);
            orderedOriginalFiles.add(originalFile);
            // 不在此处排序：本方法会被逐文件调用，每次都排序会退化为 O(n²) 级的标签读取。
            orderDirty = true;
            durationSequence = null;
        }

        private List<Integer> getOrComputeDurationSequence(AudioFingerprintService fingerprintService) {
            if (durationSequence != null && !durationSequence.isEmpty()) {
                return durationSequence;
            }
            if (orderDirty) {
                AudioFileOrdering.sort(orderedOriginalFiles);
                orderDirty = false;
            }
            List<File> processingFiles = new ArrayList<>(orderedOriginalFiles.size());
            for (File originalFile : orderedOriginalFiles) {
                AudioFormatNormalizer.NormalizationResult result = results.get(originalFile.getAbsolutePath());
                processingFiles.add(result != null ? result.getProcessingFile() : originalFile);
            }
            durationSequence = fingerprintService.extractDurationSequence(processingFiles);
            return durationSequence;
        }
    }
}

