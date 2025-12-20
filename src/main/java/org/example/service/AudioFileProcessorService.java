package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.config.MusicConfig;
import org.example.model.MusicMetadata;
import org.example.model.ProcessResult;
import org.example.util.FileSystemUtils;
import org.example.util.I18nUtil;
import org.example.util.MetadataUtils;

import java.io.File;

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
        
        try {
            // 0. 检查文件是否已处理过
            if (processedLogger.isFileProcessed(audioFile)) {
                log.info(I18nUtil.getMessage("main.file.already.processed"), audioFile.getName());
                return ProcessResult.SUCCESS; // 已处理，返回成功
            }
            
            // 0.3. 检查文件夹是否有临时文件(下载未完成)
            if (fileSystemUtils.hasTempFilesInFolder(audioFile)) {
                log.warn(I18nUtil.getMessage("main.temp.files.detected"), audioFile.getParentFile().getName());
                // 返回 DELAY_RETRY 表示需要延迟重试，但不消耗重试次数
                // 因为这不是真正的处理失败，只是暂时不适合处理
                return ProcessResult.DELAY_RETRY;
            }
            
            // 0.5. 获取专辑根目录（监控目录的第一级子目录）
            File albumRootDir = fileSystemUtils.getAlbumRootDirectory(audioFile);
            String folderPath = albumRootDir.getAbsolutePath();
            
            // 0.5.1 统计专辑根目录内音乐文件数量（递归统计所有子文件夹）
            int musicFilesInFolder = fileSystemUtils.countMusicFilesInFolder(audioFile);
            
            // 0.6. 检测是否为散落在监控目录根目录的单个文件（保底处理）
            boolean isLooseFileInMonitorRoot = fileSystemUtils.isLooseFileInMonitorRoot(audioFile);
            
            MusicMetadata detailedMetadata = null;
            boolean isQuickScanMode = false; // 标记是否使用快速扫描模式处理
            
            // ===== 优先检查文件夹专辑缓存 =====
            FolderAlbumCache.CachedAlbumInfo cachedAlbum = albumBatchProcessor.getCachedAlbum(folderPath, musicFilesInFolder);
            
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
                
            } else if (!isLooseFileInMonitorRoot) {
                // 没有缓存且不是散落文件，进行快速扫描
                log.info("尝试第一级快速扫描（基于标签和文件夹名称）...");
                LogCollector.addLog("INFO", "📂 " + I18nUtil.getMessage("main.quick.scan.attempt", audioFile.getName()));
                QuickScanService.QuickScanResult quickResult = quickScanService.quickScan(audioFile, musicFilesInFolder);

                if (quickResult != null && quickResult.isHighConfidence()) {
                    // 快速扫描成功，锁定专辑信息
                    log.info("✓ 快速扫描成功，锁定专辑信息");
                    LogCollector.addLog("SUCCESS", "✓ " + I18nUtil.getMessage("main.quick.scan.success", audioFile.getName()));
                    isQuickScanMode = true; // 标记为快速扫描模式
                    MusicMetadata quickMetadata = quickResult.getMetadata();
                    
                    lockedAlbumTitle = quickMetadata.getAlbum();
                    lockedAlbumArtist = quickMetadata.getAlbumArtist() != null ?
                        quickMetadata.getAlbumArtist() : quickMetadata.getArtist();
                    lockedReleaseGroupId = quickMetadata.getReleaseGroupId();
                    lockedReleaseDate = quickMetadata.getReleaseDate();
                    
                    // 立即将专辑信息写入文件夹缓存
                    FolderAlbumCache.CachedAlbumInfo albumInfo = new FolderAlbumCache.CachedAlbumInfo(
                        lockedReleaseGroupId,
                        null,  // releaseId - 快速扫描时没有具体的 Release ID
                        lockedAlbumTitle,
                        lockedAlbumArtist,
                        quickMetadata.getTrackCount(),
                        lockedReleaseDate,
                        quickResult.getSimilarity()
                    );
                    albumBatchProcessor.setFolderAlbum(folderPath, albumInfo);
                    log.info("已将快速扫描结果缓存到文件夹级别");
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
            LogCollector.addLog("INFO", "🔍 " + I18nUtil.getMessage("main.fingerprint.identifying", audioFile.getName()));
            AudioFingerprintService.AcoustIdResult acoustIdResult =
                fingerprintService.identifyAudioFile(audioFile);

            // ===== 关键修复：在获取详细元数据之前，先执行时长序列匹配确定专辑 =====
            // 这样第一个文件就能使用正确的 preferredReleaseGroupId
            if (lockedAlbumTitle == null && !isLooseFileInMonitorRoot &&
                acoustIdResult.getRecordings() != null && !acoustIdResult.getRecordings().isEmpty()) {
                
                // 收集 AcoustID 返回的所有候选专辑
                java.util.List<FolderAlbumCache.CandidateReleaseGroup> allCandidates = new java.util.ArrayList<>();
                for (AudioFingerprintService.RecordingInfo recording : acoustIdResult.getRecordings()) {
                    if (recording.getReleaseGroups() != null) {
                        for (AudioFingerprintService.ReleaseGroupInfo rg : recording.getReleaseGroups()) {
                            boolean exists = allCandidates.stream()
                                .anyMatch(c -> c.getReleaseGroupId().equals(rg.getId()));
                            if (!exists) {
                                allCandidates.add(new FolderAlbumCache.CandidateReleaseGroup(
                                    rg.getId(), rg.getTitle()));
                            }
                        }
                    }
                }
                
                if (!allCandidates.isEmpty()) {
                    log.info("第一个文件处理：收集到 {} 个候选专辑，立即执行时长序列匹配", allCandidates.size());
                    
                    // 立即执行时长序列匹配
                    FolderAlbumCache.CachedAlbumInfo determinedAlbum =
                        folderAlbumCache.determineAlbumWithDurationSequence(folderPath, allCandidates, musicFilesInFolder);
                    
                    if (determinedAlbum != null) {
                        // 时长序列匹配成功，锁定专辑信息
                        lockedAlbumTitle = determinedAlbum.getAlbumTitle();
                        lockedAlbumArtist = determinedAlbum.getAlbumArtist();
                        lockedReleaseGroupId = determinedAlbum.getReleaseGroupId();
                        lockedReleaseId = determinedAlbum.getReleaseId();  // 关键：获取具体的 Release ID
                        lockedReleaseDate = determinedAlbum.getReleaseDate();

                        log.info("✓ 第一个文件即确定专辑: {} (Release Group ID: {}, Release ID: {})",
                            lockedAlbumTitle, lockedReleaseGroupId, lockedReleaseId);
                        
                        // 同时设置到批处理器的缓存中
                        albumBatchProcessor.setFolderAlbum(folderPath, determinedAlbum);
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
                    LogCollector.addLog("WARN", "⚠ " + I18nUtil.getMessage("main.acoustid.no.match", audioFile.getName()));
                    log.info("建议：手动添加标签或等待 MusicBrainz 社区完善数据");

                    // 处理识别失败
                    if (isLooseFileInMonitorRoot) {
                        failedFileHandler.handleLooseFileFailed(audioFile);
                    } else {
                        failedFileHandler.handleAlbumFileFailed(audioFile, albumRootDir);
                    }

                    return ProcessResult.PERMANENT_FAIL; // 识别失败，不重试但记录
                } else {
                    // 有锁定的专辑信息（快速扫描成功），使用锁定的专辑信息继续处理
                    log.info("AcoustID 未关联到详细录音信息，但快速扫描已锁定专辑，继续处理");
                    LogCollector.addLog("INFO", "📋 " + I18nUtil.getMessage("main.acoustid.no.match.use.quick.scan", audioFile.getName()));
                    LogCollector.addLog("INFO", "📋 " + I18nUtil.getMessage("main.quick.scan.locked.album", lockedAlbumArtist, lockedAlbumTitle));
                    
                    MusicMetadata sourceTagsForFallback = tagWriter.readTags(audioFile);
                    detailedMetadata = MetadataUtils.createMetadataFromQuickScan(
                        sourceTagsForFallback,
                        lockedAlbumTitle,
                        lockedAlbumArtist,
                        lockedReleaseGroupId,
                        lockedReleaseDate,
                        audioFile.getName()
                    );
                }
            } else {
                // 指纹识别成功，获取详细元数据
                // 关键：指纹识别成功获取到recordings，应该使用指纹识别模式（而非快速扫描模式）
                isQuickScanMode = false;

                AudioFingerprintService.RecordingInfo bestMatch = MetadataUtils.findBestRecordingMatch(
                    acoustIdResult.getRecordings(),
                    lockedReleaseGroupId,
                    audioFile.getName()
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
                    LogCollector.addLog("INFO", "🔍 " + I18nUtil.getMessage("main.acoustid.has.recording.id"));
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
                detailedMetadata = musicBrainzClient.getRecordingById(bestMatch.getRecordingId(), musicFilesParam, lockedReleaseGroupId, lockedReleaseId, fileDurationSeconds);

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
                            MusicMetadata sourceTagsForFallback = tagWriter.readTags(audioFile);
                            detailedMetadata = MetadataUtils.createMetadataFromQuickScan(
                                sourceTagsForFallback,
                                lockedAlbumTitle,
                                lockedAlbumArtist,
                                lockedReleaseGroupId,
                                lockedReleaseDate,
                                audioFile.getName()
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
                    // 修复：即使 lockedReleaseId 为 null，只要有 lockedReleaseGroupId 也应该检测不匹配
                    if (lockedReleaseGroupId != null && !lockedReleaseGroupId.isEmpty()) {
                        
                        String returnedReleaseGroupId = detailedMetadata.getReleaseGroupId();
                        boolean albumMismatch = (returnedReleaseGroupId == null ||
                                                returnedReleaseGroupId.isEmpty() ||
                                                !lockedReleaseGroupId.equals(returnedReleaseGroupId));
                        
                        if (albumMismatch) {
                            log.warn("⚠ 检测到专辑不匹配！");
                            log.warn("  锁定专辑 Release Group ID: {}", lockedReleaseGroupId);
                            log.warn("  返回的 Release Group ID: {}", returnedReleaseGroupId);
                            
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

            // ===== 读取源文件已有标签并合并 =====
            // 在快速扫描锁定专辑但音频指纹数据库缺失的情况下，保留源文件的作曲、作词、歌词、风格等信息
            log.info("读取源文件已有标签信息...");
            MusicMetadata sourceMetadata = tagWriter.readTags(audioFile);
            if (sourceMetadata != null) {
                log.info("合并源文件标签信息...");
                detailedMetadata = MetadataUtils.mergeMetadata(sourceMetadata, detailedMetadata);
            } else {
                log.debug("源文件没有可读取的标签信息");
            }
            
            // 4. 获取封面图片(多层降级策略)
            byte[] coverArtData = coverArtService.getCoverArtWithFallback(
                audioFile, detailedMetadata, lockedReleaseGroupId, isLooseFileInMonitorRoot);
            
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
                albumBatchProcessor.processAndWriteFile(audioFile, detailedMetadata, coverArtData, false);
            } else if (lockedAlbumTitle != null) {
                // 已有锁定的专辑信息（来自快速扫描或缓存），直接处理文件
                log.info("使用已锁定的专辑信息: {}", lockedAlbumTitle);
                albumBatchProcessor.processAndWriteFile(audioFile, detailedMetadata, coverArtData, isQuickScanMode);
            } else {
                // 未锁定专辑：收集样本进行投票
                log.info("启用文件夹级别专辑锁定（{}首音乐文件）", musicFilesInFolder);

                int trackCount = detailedMetadata.getTrackCount();

                // 收集 AcoustID 返回的所有候选 ReleaseGroups
                java.util.List<FolderAlbumCache.CandidateReleaseGroup> allCandidates = new java.util.ArrayList<>();
                if (acoustIdResult.getRecordings() != null) {
                    for (AudioFingerprintService.RecordingInfo recording : acoustIdResult.getRecordings()) {
                        if (recording.getReleaseGroups() != null) {
                            for (AudioFingerprintService.ReleaseGroupInfo rg : recording.getReleaseGroups()) {
                                // 避免重复添加
                                boolean exists = allCandidates.stream()
                                    .anyMatch(c -> c.getReleaseGroupId().equals(rg.getId()));
                                if (!exists) {
                                    allCandidates.add(new FolderAlbumCache.CandidateReleaseGroup(
                                        rg.getId(), rg.getTitle()));
                                }
                            }
                        }
                    }
                }
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
                albumBatchProcessor.addPendingFile(folderPath, audioFile, detailedMetadata, coverArtData);

                // 尝试确定专辑
                FolderAlbumCache.CachedAlbumInfo determinedAlbum = albumBatchProcessor.tryDetermineAlbum(
                    folderPath,
                    audioFile.getName(),
                    musicFilesInFolder,
                    albumInfo
                );

                if (determinedAlbum != null) {
                    // 专辑已确定，批量处理所有待处理文件
                    log.info("========================================");
                    log.info("✓ 文件夹专辑已确定: {}", determinedAlbum.getAlbumTitle());
                    log.info("开始批量处理文件夹内的所有文件...");
                    log.info("========================================");

                    albumBatchProcessor.processPendingFilesWithAlbum(folderPath, determinedAlbum);
                } else {
                    log.info("专辑收集中，待处理文件已加入队列: {}", audioFile.getName());

                    // 检查是否所有文件都已加入待处理队列但专辑仍未确定
                    // 这种情况可能发生在样本收集过程中部分文件识别失败
                    int pendingCount = albumBatchProcessor.getPendingFileCount(folderPath);
                    if (pendingCount >= musicFilesInFolder) {
                        log.warn("所有文件已加入待处理队列但专辑仍未确定，强制处理");
                        albumBatchProcessor.forceProcessPendingFiles(folderPath, albumInfo);
                    }
                }
            }

            return ProcessResult.SUCCESS; // 处理成功

        } catch (java.io.IOException e) {
            // 网络异常（包括 SocketException），返回 NETWORK_ERROR_RETRY 以触发重试并增加重试计数
            log.error(I18nUtil.getMessage("main.network.error"), audioFile.getName(), e.getMessage());
            log.info(I18nUtil.getMessage("main.retry.queued"));
            return ProcessResult.NETWORK_ERROR_RETRY;

        } catch (Exception e) {
            // 其他异常（如识别失败），不重试，但必须记录到数据库避免静默丢失
            log.error(I18nUtil.getMessage("main.process.error"), audioFile.getName(), e);

            // 关键修复：记录失败文件到数据库，避免文件"静默丢失"
            try {
                processedLogger.markFileAsProcessed(
                    audioFile,
                    "FAILED",
                    "处理异常: " + e.getClass().getSimpleName(),
                    audioFile.getName(),
                    "Unknown Album"
                );
                log.info("已将异常失败文件记录到数据库: {}", audioFile.getName());
            } catch (Exception recordError) {
                log.error("记录异常失败文件到数据库失败: {} - {}", audioFile.getName(), recordError.getMessage());
            }

            return ProcessResult.PERMANENT_FAIL; // 返回永久失败避免重试（非网络问题）

        } finally {
            log.info("========================================");
        }
    }
}