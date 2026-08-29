package com.lux032.musicautotagger.service;

import lombok.extern.slf4j.Slf4j;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.util.I18nUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 专辑批量处理服务
 * 负责专辑的批量处理和待处理文件管理
 */
@Slf4j
public class AlbumBatchProcessor {
    
    private final MusicConfig config;
    private final FolderAlbumCache folderAlbumCache;
    private final TagWriterService tagWriter;
    private final ProcessedFileLogger processedLogger;
    private final CoverArtService coverArtService;
    /** 用于按锁定 Release 获取唯一权威的专辑级标签。 */
    private MusicBrainzClient musicBrainzClient;
    /** 人工确认队列（阶段六，可选） */
    private ReviewQueueService reviewQueueService;
    
    public AlbumBatchProcessor(MusicConfig config, FolderAlbumCache folderAlbumCache,
                               TagWriterService tagWriter, ProcessedFileLogger processedLogger,
                               CoverArtService coverArtService) {
        this.config = config;
        this.folderAlbumCache = folderAlbumCache;
        this.tagWriter = tagWriter;
        this.processedLogger = processedLogger;
        this.coverArtService = coverArtService;
    }

    /**
     * 注入人工确认队列（阶段六 #18）。
     * 用 setter 保持现有构造器签名兼容。
     */
    public void setReviewQueueService(ReviewQueueService reviewQueueService) {
        this.reviewQueueService = reviewQueueService;
    }

    public void setMusicBrainzClient(MusicBrainzClient musicBrainzClient) {
        this.musicBrainzClient = musicBrainzClient;
    }

    /**
     * 处理并写入单个文件
     * @param audioFile 音频文件
     * @param originalFile 原始文件（用于日志和去重记录）
     * @param metadata 元数据
     * @param coverArtData 封面数据
     * @param isQuickScanMode 是否为快速扫描模式（用于区分日志显示）
     * @return true 表示标签写入与归档成功；false 表示失败（已记录到数据库）
     */
    public boolean processAndWriteFile(File audioFile, File originalFile, MusicMetadata metadata, byte[] coverArtData, boolean isQuickScanMode) {
        File displayFile = originalFile != null ? originalFile : audioFile;
        try {
            log.info("正在写入文件标签: {}", displayFile.getName());
            boolean success = tagWriter.processFile(audioFile, metadata, coverArtData);
            
            if (success) {
                if (isQuickScanMode) {
                    log.info("? 文件处理成功（快速扫描模式）: {}", displayFile.getName());
                    LogCollector.addLog("SUCCESS", I18nUtil.getMessage("main.process.success.quick.scan", displayFile.getName()));
                } else {
                    log.info("? 文件处理成功: {}", displayFile.getName());
                    LogCollector.addLog("SUCCESS", I18nUtil.getMessage("main.process.success.fingerprint", displayFile.getName()));
                }
                
                // 记录文件已处理
                processedLogger.markFileAsProcessed(
                    displayFile,
                    metadata.getRecordingId(),
                    metadata.getArtist(),
                    metadata.getTitle(),
                    metadata.getAlbum(),
                    // 封面缓存以 Release Group ID 为 key，记下来仪表板才能直接从缓存取缩略图
                    metadata.getReleaseGroupId()
                );
                return true;
            } else {
                log.error("? 文件处理失败: {}", displayFile.getName());
                LogCollector.addLog("ERROR", I18nUtil.getMessage("main.process.error", displayFile.getName()));
                // 关键修复：写入失败也要记录到数据库，避免文件"静默丢失"
                processedLogger.markFileAsProcessed(
                    displayFile,
                    "WRITE_FAILED",
                    metadata.getArtist() != null ? metadata.getArtist() : "Unknown Artist",
                    metadata.getTitle() != null ? metadata.getTitle() : displayFile.getName(),
                    metadata.getAlbum() != null ? metadata.getAlbum() : "Unknown Album"
                );
                log.info("已将写入失败文件记录到数据库: {}", displayFile.getName());
                return false;
            }
        } catch (Exception e) {
            log.error(I18nUtil.getMessage("main.write.exception"), displayFile.getName(), e);
            // 关键修复：异常时也要记录到数据库，避免文件"静默丢失"
            try {
                processedLogger.markFileAsProcessed(
                    displayFile,
                    "EXCEPTION",
                    metadata.getArtist() != null ? metadata.getArtist() : "Unknown Artist",
                    metadata.getTitle() != null ? metadata.getTitle() : displayFile.getName(),
                    metadata.getAlbum() != null ? metadata.getAlbum() : "Unknown Album"
                );
                log.info("已将异常文件记录到数据库: {}", displayFile.getName());
            } catch (Exception recordError) {
                log.error("记录异常文件到数据库失败: {} - {}", displayFile.getName(), recordError.getMessage());
            }
            return false;
        }
    }

    /**
     * 兼容旧调用：处理并写入单个文件
     */
    public boolean processAndWriteFile(File audioFile, MusicMetadata metadata, byte[] coverArtData, boolean isQuickScanMode) {
        return processAndWriteFile(audioFile, audioFile, metadata, coverArtData, isQuickScanMode);
    }



    /**
     * 批量处理结果（阶段六审查 #5）
     *
     * 以前 `processPendingFilesWithAlbum()` 返回 void，内部逐文件捕获异常，
     * 调用方（尤其是人工确认链路）根本无从得知有没有文件写失败，
     * 于是总是把条目标为「已确认」——用户看到成功，实际只归档了一部分。
     */
    public static class BatchProcessResult {
        private final int successCount;
        private final List<String> failedFiles;

        public BatchProcessResult(int successCount, List<String> failedFiles) {
            this.successCount = successCount;
            this.failedFiles = failedFiles == null ? new ArrayList<>() : failedFiles;
        }

        public int getSuccessCount() {
            return successCount;
        }

        public List<String> getFailedFiles() {
            return failedFiles;
        }

        public int getFailedCount() {
            return failedFiles.size();
        }

        public boolean isFullySuccessful() {
            return failedFiles.isEmpty() && successCount > 0;
        }

        public static BatchProcessResult empty() {
            return new BatchProcessResult(0, new ArrayList<>());
        }
    }

    /**
     * 批量处理文件夹内的待处理文件，统一应用确定的专辑信息
     */
    public BatchProcessResult processPendingFilesWithAlbum(String folderPath, FolderAlbumCache.CachedAlbumInfo albumInfo) {
        return processPendingFilesWithAlbum(folderPath, albumInfo, false);
    }

    /**
     * 批量处理文件夹内的待处理文件，统一应用确定的专辑信息
     *
     * @param unresolvedAlbum 是否为「专辑未确定」模式。为 true 时会显式清空年份标签，
     *                        避免原文件里旧专辑的年份残留下来。
     */
    public BatchProcessResult processPendingFilesWithAlbum(String folderPath, FolderAlbumCache.CachedAlbumInfo albumInfo,
                                                           boolean unresolvedAlbum) {
        List<FolderAlbumCache.PendingFile> pendingFiles = folderAlbumCache.getPendingFiles(folderPath);
        
        if (pendingFiles == null || pendingFiles.isEmpty()) {
            log.warn("文件夹没有待处理文件: {}", folderPath);
            return BatchProcessResult.empty();
        }
        
        log.info("开始批量处理 {} 个待处理文件", pendingFiles.size());
        
        // 关键修复：使用锁定专辑的 Release Group ID 获取正确的封面
        byte[] correctCoverArt = null;
        if (albumInfo.getReleaseGroupId() != null && !albumInfo.getReleaseGroupId().isEmpty()) {
            try {
                log.info("尝试获取锁定专辑的封面 (Release Group ID: {})", albumInfo.getReleaseGroupId());
                correctCoverArt = coverArtService.getCoverArtByReleaseGroupId(
                    albumInfo.getReleaseGroupId(), folderPath);
                
                if (correctCoverArt != null && correctCoverArt.length > 0) {
                    log.info("✓ 成功获取锁定专辑的封面，将替换所有文件的封面");
                } else {
                    log.warn("未能获取锁定专辑的封面，将使用文件原有封面");
                }
            } catch (Exception e) {
                log.warn("获取锁定专辑封面失败，将使用文件原有封面: {}", e.getMessage());
            }
        }
        
        MusicBrainzClient.ReleaseTagBundle releaseTags = null;
        if (!unresolvedAlbum && musicBrainzClient != null
            && albumInfo.getReleaseId() != null && !albumInfo.getReleaseId().isEmpty()) {
            try {
                releaseTags = musicBrainzClient.fetchReleaseTagBundle(albumInfo.getReleaseId());
            } catch (Exception e) {
                log.warn("获取锁定 Release 的专辑级标签失败，将保留文件现有专辑级标签: {}", e.getMessage());
            }
        }

        int successCount = 0;
        int failCount = 0;
        List<File> failedFiles = new ArrayList<>();
        // 写入失败也要计入失败（processAndWriteFile 内部会吃掉异常），
        // 否则人工确认链路会把「写失败」当成成功。
        List<String> failedFileNames = new ArrayList<>();
        
        for (FolderAlbumCache.PendingFile pending : pendingFiles) {
            try {
                File audioFile = pending.getProcessingFile() != null ? pending.getProcessingFile() : pending.getAudioFile();
                MusicMetadata metadata = (MusicMetadata) pending.getMetadata();
                // 关键修复：如果成功获取了正确的封面，使用正确的封面；否则使用原有封面
                byte[] coverArtData = (correctCoverArt != null && correctCoverArt.length > 0) ?
                    correctCoverArt : pending.getCoverArtData();
                
                log.info("批量处理文件 [{}/{}]: {}",
                    successCount + failCount + 1, pendingFiles.size(), audioFile.getName());
                
                // 注意：metadata已经通过指纹识别获取了完整的单曲信息
                // 只需要覆盖专辑相关字段
                metadata.setAlbum(albumInfo.getAlbumTitle());
                metadata.setAlbumArtist(albumInfo.getAlbumArtist());
                metadata.setReleaseGroupId(albumInfo.getReleaseGroupId());
                metadata.setReleaseId(albumInfo.getReleaseId());
                metadata.setReleaseType(albumInfo.getReleaseType());
                metadata.setCompilation(albumInfo.isCompilation());
                // 选项 A 仅在成功取得 MusicBrainz 权威快照时执行；网络故障不能触发误清空。
                if (releaseTags != null) {
                    metadata.setClearAlbumLevelTags(true);
                    releaseTags.applyTo(metadata);
                }
                if (unresolvedAlbum) {
                    // 专辑未确定：宁可没有年份，也不能保留旧专辑的年份。
                    // 注意：releaseDate 置空只会让写入逻辑「跳过不写」，原文件的 YEAR 仍会残留，
                    // 因此必须同时置 clearReleaseDate 标志，让 TagWriter 显式删除该字段。
                    metadata.setReleaseDate(null);
                    metadata.setClearReleaseDate(true);
                    metadata.setClearAlbumLevelTags(true);
                    metadata.setClearReleaseType(true);
                } else if (albumInfo.getReleaseDate() != null && !albumInfo.getReleaseDate().isEmpty()) {
                    metadata.setReleaseDate(albumInfo.getReleaseDate());
                }
                
                // 写入文件（metadata已包含作词、作曲、风格等信息）
                boolean written = processAndWriteFile(audioFile, pending.getAudioFile(), metadata, coverArtData, false);
                if (written) {
                    successCount++;
                } else {
                    failCount++;
                    failedFiles.add(pending.getAudioFile());
                    failedFileNames.add(pending.getAudioFile().getName());
                }
                
            } catch (Exception e) {
                log.error("批量处理文件失败: {}", pending.getAudioFile().getName(), e);
                failCount++;
                failedFiles.add(pending.getAudioFile());
                failedFileNames.add(pending.getAudioFile().getName());
                // 对于失败的文件，也记录到数据库，避免数据缺失
                try {
                    MusicMetadata metadata = (MusicMetadata) pending.getMetadata();
                    processedLogger.markFileAsProcessed(
                        pending.getAudioFile(),
                        metadata.getRecordingId() != null ? metadata.getRecordingId() : "UNKNOWN",
                        metadata.getArtist() != null ? metadata.getArtist() : "Unknown Artist",
                        metadata.getTitle() != null ? metadata.getTitle() : pending.getAudioFile().getName(),
                        albumInfo.getAlbumTitle(),
                        metadata.getReleaseGroupId()
                    );
                    log.info("已记录失败文件到数据库: {}", pending.getAudioFile().getName());
                } catch (Exception recordError) {
                    log.error("记录失败文件到数据库失败: {}", pending.getAudioFile().getName(), recordError);
                }
            } finally {
                cleanupPendingTemp(pending);
            }
        }
        
        log.info("========================================");
        log.info("批量处理完成: 成功 {} 个, 失败 {} 个", successCount, failCount);
        if (!failedFiles.isEmpty()) {
            log.warn("失败文件列表:");
            for (File file : failedFiles) {
                log.warn("  - {}", file.getName());
            }
        }
        log.info("========================================");
        
        // 清除待处理列表
        folderAlbumCache.clearPendingFiles(folderPath);

        return new BatchProcessResult(successCount, failedFileNames);
    }
    
    /**
     * 当专辑无法确定时的处理
     *
     * 关键修复：以前这里会拿「某一首歌识别出的专辑」当作整张专辑强行写入，
     * 导致一张 MusicBrainz 未收录的精选集被整个归到第一首歌所属的旧专辑下。
     * 现在改为：不猜。保留每首歌已经查准的曲目级信息，专辑信息用文件夹名/原标签合成。
     */
    @Deprecated
    public void forceProcessPendingFiles(String folderPath, FolderAlbumCache.AlbumIdentificationInfo bestGuess) {
        // bestGuess 已不再使用：以前拿它当「最佳猜测专辑」正是错误归档的根源。
        processPendingFilesAsUnresolvedAlbum(folderPath);
    }

    /** 入队失败时的占位结果：文件仍然留在 pending 里，什么都没写 */
    private static final BatchProcessResult QUEUED_FOR_REVIEW = BatchProcessResult.empty();

    /**
     * 按「专辑未确定」处理待处理文件
     *
     * 适用场景：MusicBrainz 里根本没有这张专辑（典型例子：新发行的精选集）。
     * 此时：
     *   - 每首歌的歌名/歌手/作曲/作词 都是指纹识别出来的，是准确的 → 保留
     *   - 缺的只是「专辑名」→ 用原文件标签或文件夹名合成
     *   - 专辑艺术家如果不一致 → 写 Various Artists（否则 Plex/Emby 会把一张精选集拆成一堆单曲专辑）
     */
    public BatchProcessResult processPendingFilesAsUnresolvedAlbum(String folderPath) {
        return processPendingFilesAsUnresolvedAlbum(folderPath, true);
    }

    /**
     * @param allowReviewQueue 是否允许进入「待人工确认」队列。
     *        人工已经在面板上选择了「按 MB 未收录归档」时必须传 false，
     *        否则会把刚刚取出的条目又塞回队列，形成死循环。
     */
    public BatchProcessResult processPendingFilesAsUnresolvedAlbum(String folderPath, boolean allowReviewQueue) {
        List<FolderAlbumCache.PendingFile> pendingFiles = folderAlbumCache.getPendingFiles(folderPath);

        if (pendingFiles == null || pendingFiles.isEmpty()) {
            log.warn("文件夹没有待处理文件: {}", folderPath);
            return BatchProcessResult.empty();
        }

        FolderAlbumCache.CachedAlbumInfo synthesized = buildUnresolvedAlbumInfo(folderPath, pendingFiles);

        // 阶段六 #18：开启人工确认时，不再直接落盘，而是进入可跨重启的待确认队列。
        // 关键：此时文件仍未被写标签 / 改名 / 移动，人工选定后才真正执行。
        if (allowReviewQueue && reviewQueueService != null && config.isReviewEnabled()) {
            int filesWithTrackMetadata = 0;
            for (FolderAlbumCache.PendingFile pending : pendingFiles) {
                MusicMetadata md = (MusicMetadata) pending.getMetadata();
                if (md != null && md.getTitle() != null && !md.getTitle().trim().isEmpty()) {
                    filesWithTrackMetadata++;
                }
            }
            double unified = com.lux032.musicautotagger.util.ConfidenceModel
                .unresolvedAlbumConfidence(filesWithTrackMetadata, pendingFiles.size());

            if (com.lux032.musicautotagger.util.ConfidenceModel.decide(unified)
                    == com.lux032.musicautotagger.util.ConfidenceModel.Decision.PENDING_REVIEW) {
                // 关键（审查 #3）：入队必须**确认落盘成功**才能清空内存 pending。
                // 否则会出现：JSON 里没有这个条目 + 内存队列也清了 = 任务直接丢失。
                ReviewItem queued = reviewQueueService.enqueue(
                    folderPath,
                    pendingFiles,
                    synthesized,
                    folderAlbumCache.getFolderCandidates(folderPath),
                    folderAlbumCache.getFolderDurationSequence(folderPath),
                    "专辑无法在 MusicBrainz 中确定（候选覆盖率不足 / 时长序列不匹配）",
                    unified
                );

                if (queued != null) {
                    // 标记 unresolved：同目录后续文件不得再去匹配旧专辑
                    folderAlbumCache.markFolderUnresolved(folderPath);
                    // pending 已转移到待确认队列（含转码暂存文件的接管），不能再留在内存队列里，
                    // 否则关机 flush 会把它们又写一遍
                    folderAlbumCache.clearPendingFiles(folderPath);
                    return QUEUED_FOR_REVIEW;
                }

                log.error("待确认队列落盘失败，为避免任务丢失，改为按「专辑未确定」直接归档（pending 保留）");
                LogCollector.addLog("ERROR", "待确认队列落盘失败，已回退为直接归档");
            }

            log.warn("待确认队列已启用，但曲目级元数据覆盖率过低（统一置信度 {}），不入队，改为直接归档",
                String.format("%.2f", unified));
        }

        log.warn("========================================");
        log.warn("⚠ 专辑无法在 MusicBrainz 中确定，按「专辑未确定」处理");
        log.warn("  文件夹: {}", new File(folderPath).getName());
        log.warn("  合成专辑名: {}", synthesized.getAlbumTitle());
        log.warn("  合成专辑艺术家: {}", synthesized.getAlbumArtist());
        log.warn("  曲目级信息（歌名/歌手/作曲/作词）仍使用指纹识别结果，是准确的");
        log.warn("========================================");
        LogCollector.addLog("WARN", "专辑未确定，已用文件夹名合成专辑信息: " + synthesized.getAlbumTitle());

        // 关键：只标记 unresolved，**不把合成结果写入普通专辑缓存**。
        //
        // 合成的专辑信息是未经验证的，一旦写入 folderAlbumCache，
        // 同目录后续新增的文件会把它当成「已锁定的专辑」走普通分支，导致：
        //   1) 不再设置 clearReleaseDate → 旧专辑年份在增量文件上重新残留
        //   2) 不再重算专辑艺术家 → 新增其他艺术家的曲目时仍沿用旧值，不会降为 Various Artists
        //
        // 不写缓存后，后续文件会重新进入 pending 并由 isFolderUnresolved() 拦住专辑匹配，
        // 收齐后重新合成（年份再次被清除、艺术家基于当前批次重算）。
        folderAlbumCache.markFolderUnresolved(folderPath);

        return processPendingFilesWithAlbum(folderPath, synthesized, true);
    }

    /**
     * 合成「专辑未确定」时的专辑信息
     */
    private FolderAlbumCache.CachedAlbumInfo buildUnresolvedAlbumInfo(
            String folderPath, List<FolderAlbumCache.PendingFile> pendingFiles) {

        // 1. 专辑名：优先用「原文件标签」里的专辑名，否则用清洗后的文件夹名。
        //    注意：这里必须重新读取原文件标签，不能用 pending.getMetadata()。
        //    因为 pending 里是识别后的 detailedMetadata，而 mergeMetadata() 只会保留
        //    composer / lyricist / lyrics / genres，并不会保留源文件的 album，
        //    在 allowAlbumGuess=false 时 md.getAlbum() 几乎恒为 null。
        Map<String, Integer> albumVotes = new HashMap<>();
        Map<String, Integer> artistVotes = new HashMap<>();
        int filesWithRecognizedArtist = 0;

        for (FolderAlbumCache.PendingFile pending : pendingFiles) {
            // 专辑名：从原文件标签读
            try {
                MusicMetadata originalTags = tagWriter.readTags(pending.getAudioFile());
                if (originalTags != null) {
                    String album = originalTags.getAlbum();
                    if (isMeaningfulAlbumName(album)) {
                        albumVotes.merge(album.trim(), 1, Integer::sum);
                    }
                }
            } catch (Exception e) {
                log.debug("读取原文件标签失败: {} - {}", pending.getAudioFile().getName(), e.getMessage());
            }

            // 艺术家：用识别后的元数据（指纹识别结果比原标签准）
            MusicMetadata md = (MusicMetadata) pending.getMetadata();
            if (md != null) {
                String artist = md.getAlbumArtist() != null ? md.getAlbumArtist() : md.getArtist();
                if (artist != null && !artist.trim().isEmpty()) {
                    artistVotes.merge(artist.trim(), 1, Integer::sum);
                    filesWithRecognizedArtist++;
                }
            }
        }

        // 避免「一票原标签」压过文件夹名：
        // 分母用**全部待处理文件数**而非「能读出标签的文件数」，
        // 否则 10 个文件中只有 2 个能读出标签时，2/2 = 100% 会让两个文件决定整张专辑的名字。
        String albumTitle = null;
        String mostVotedAlbum = pickMostVoted(albumVotes);
        if (mostVotedAlbum != null) {
            int votes = albumVotes.get(mostVotedAlbum);
            int denominator = Math.max(1, pendingFiles.size());
            int requiredVotes = (int) Math.ceil(denominator * 0.6);
            // 多文件发行至少需要 2 票，避免单个文件的脏标签决定整张专辑
            if (denominator >= 2) {
                requiredVotes = Math.max(2, requiredVotes);
            }
            if (votes >= requiredVotes) {
                albumTitle = mostVotedAlbum;
                log.info("使用原文件标签中的专辑名: {} ({}/{} 个文件一致)",
                    albumTitle, votes, denominator);
            } else {
                log.info("原标签专辑名「{}」仅 {}/{} 个文件一致（需 {}），改用文件夹名",
                    mostVotedAlbum, votes, denominator, requiredVotes);
            }
        }

        if (albumTitle == null) {
            albumTitle = cleanFolderName(new File(folderPath).getName());
            log.info("使用清洗后的文件夹名作为专辑名: {}", albumTitle);
        }
        if (albumTitle == null || albumTitle.trim().isEmpty()) {
            albumTitle = "Unknown Album";
        }

        // 2. 专辑艺术家：只有当所有文件的艺术家一致时才用它，否则 Various Artists
        //    这一步很关键：精选集里每首歌艺术家都不同，
        //    如果不写 Various Artists，Plex/Emby 会把一张专辑拆成 N 个单曲专辑。
        //    注意：不能只看「不同艺术家的个数是否为 1」——
        //    如果 10 个文件里只有 1 个识别出了艺术家，其余 9 个缺失，
        //    artistVotes.size() 也是 1，会被误判为「全专辑艺术家一致」。
        String albumArtist;
        boolean allFilesHaveArtist = (filesWithRecognizedArtist == pendingFiles.size());
        if (artistVotes.size() == 1 && allFilesHaveArtist) {
            albumArtist = artistVotes.keySet().iterator().next();
            log.info("全部 {} 个文件艺术家一致，专辑艺术家使用: {}", pendingFiles.size(), albumArtist);
        } else if (artistVotes.size() == 1) {
            albumArtist = "Various Artists";
            log.info("艺术家虽只有 1 种，但仅 {}/{} 个文件识别出艺术家，保守起见写入 Various Artists",
                filesWithRecognizedArtist, pendingFiles.size());
        } else {
            albumArtist = "Various Artists";
            log.info("检测到 {} 个不同艺术家，专辑艺术家写入 Various Artists（避免 Plex/Emby 拆分专辑）",
                artistVotes.size());
        }
        albumArtist = MusicMetadata.normalizeAlbumArtist(albumArtist);

        return new FolderAlbumCache.CachedAlbumInfo(
            null,   // releaseGroupId - MusicBrainz 没有这张专辑
            null,   // releaseId
            albumTitle,
            albumArtist,
            pendingFiles.size(),
            "",     // releaseDate - 宁可留空，也不用旧专辑的年份
            0.0,    // 置信度 0：表示未经验证
            FolderAlbumCache.CacheSource.UNKNOWN
        );
    }

    /**
     * 专辑名是否有意义（过滤常见占位值）
     */
    private boolean isMeaningfulAlbumName(String album) {
        if (album == null || album.trim().isEmpty()) {
            return false;
        }
        String normalized = album.trim().toLowerCase();
        if (normalized.equals("unknown album") || normalized.equals("unknown")
            || normalized.equals("various") || normalized.equals("various artists")
            || normalized.equals("untitled") || normalized.equals("no album")
            || normalized.equals("未知专辑") || normalized.equals("未命名专辑")) {
            return false;
        }
        // "Track 01" / "CD1" / 纯数字 等占位值
        if (normalized.matches("^(track|cd|disc)\\s*\\d+$") || normalized.matches("^\\d+$")) {
            return false;
        }
        return true;
    }

    private String pickMostVoted(Map<String, Integer> votes) {
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : votes.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    /**
     * 清洗文件夹名，得到一个像样的专辑名
     * 例："[2024.01.01] Best of XXX [FLAC][VIZL-1777]" -> "Best of XXX"
     */
    private String cleanFolderName(String folderName) {
        if (folderName == null || folderName.trim().isEmpty()) {
            return "Unknown Album";
        }

        String cleaned = folderName;
        // 去掉方括号/圆括号包裹的发行编号、格式标记、日期等
        cleaned = cleaned.replaceAll("\\[[^\\]]*\\]", " ");
        cleaned = cleaned.replaceAll("\\([^\\)]*\\)", " ");
        cleaned = cleaned.replaceAll("\\{[^\\}]*\\}", " ");
        // 去掉常见的格式/采集标记
        cleaned = cleaned.replaceAll("(?i)\\b(FLAC|ALAC|WAV|MP3|APE|DSD|SACD|Hi-?Res|24bit|16bit|\\d{2,3}kHz|WEB|CD\\d*|Disc\\s*\\d+)\\b", " ");
        // 去掉首尾的分隔符和多余空白
        cleaned = cleaned.replaceAll("[\\s_\\-~\u2013\u2014]+$", "");
        cleaned = cleaned.replaceAll("^[\\s_\\-~\u2013\u2014]+", "");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        if (cleaned.isEmpty()) {
            // 全被清洗掉了，退回原始文件夹名
            return folderName.trim();
        }
        return cleaned;
    }
    
    /**
     * 关闭前处理所有待处理文件
     * 避免程序关闭时待处理队列中的文件丢失
     */
    public void processAllPendingFilesBeforeShutdown() {
        Set<String> foldersWithPending = folderAlbumCache.getFoldersWithPendingFiles();

        if (foldersWithPending.isEmpty()) {
            log.info(I18nUtil.getMessage("app.no.pending.files"));
            return;
        }

        log.info("========================================");
        log.info(I18nUtil.getMessage("app.process.pending.files"), foldersWithPending.size());
        log.info("========================================");

        for (String folderPath : foldersWithPending) {
            List<FolderAlbumCache.PendingFile> pendingFiles = folderAlbumCache.getPendingFiles(folderPath);
            if (pendingFiles == null || pendingFiles.isEmpty()) {
                continue;
            }

            log.info("处理文件夹: {} ({} 个待处理文件)", new File(folderPath).getName(), pendingFiles.size());

            // 检查是否已有缓存的专辑信息
            FolderAlbumCache.CachedAlbumInfo cachedAlbum = folderAlbumCache.getFolderAlbum(folderPath, pendingFiles.size());

            if (cachedAlbum != null) {
                // 有缓存的专辑信息，使用它处理
                log.info("使用缓存的专辑信息: {}", cachedAlbum.getAlbumTitle());
                processPendingFilesWithAlbum(folderPath, cachedAlbum);
            } else {
                // 关键修复：以前这里会「拿第一个文件的专辑当整张专辑」，
                // 导致精选集被整个归到第一首歌所属的旧专辑下。现在改为不猜。
                FolderAlbumCache.PendingFile firstPending = pendingFiles.get(0);
                MusicMetadata metadata = (MusicMetadata) firstPending.getMetadata();

                if (metadata != null) {
                    processPendingFilesAsUnresolvedAlbum(folderPath);
                } else {
                    // 元数据也没有，直接写入每个文件自己的元数据
                    log.warn("无法确定专辑信息，直接写入每个文件自己的元数据");
                    for (FolderAlbumCache.PendingFile pending : pendingFiles) {
                        try {
                            MusicMetadata fileMetadata = (MusicMetadata) pending.getMetadata();
                            File processingFile = pending.getProcessingFile() != null ? pending.getProcessingFile() : pending.getAudioFile();
                            processAndWriteFile(processingFile, pending.getAudioFile(), fileMetadata, pending.getCoverArtData(), false);
                        } catch (Exception e) {
                            log.error("关闭前处理文件失败: {}", pending.getAudioFile().getName(), e);
                            // 关键修复：记录失败文件到数据库，避免文件"静默丢失"
                            try {
                                processedLogger.markFileAsProcessed(
                                    pending.getAudioFile(),
                                    "FAILED",
                                    "关闭前处理失败: " + e.getClass().getSimpleName(),
                                    pending.getAudioFile().getName(),
                                    "Unknown Album"
                                );
                                log.info("已将关闭前失败文件记录到数据库: {}", pending.getAudioFile().getName());
                            } catch (Exception recordError) {
                                log.error("记录关闭前失败文件到数据库失败: {} - {}", pending.getAudioFile().getName(), recordError.getMessage());
                            }
                        } finally {
                            cleanupPendingTemp(pending);
                        }
                    }
                    folderAlbumCache.clearPendingFiles(folderPath);
                }
            }
        }

        log.info("========================================");
        log.info("关闭前待处理文件处理完成");
        log.info("========================================");
    }
    
    /**
     * 添加待处理文件到专辑缓存
     */
    public void addPendingFile(String folderPath, File audioFile, File processingFile, java.nio.file.Path tempDirectory,
                               MusicMetadata metadata, byte[] coverArtData) {
        folderAlbumCache.addPendingFileIfAbsent(folderPath, audioFile, processingFile, tempDirectory, metadata, coverArtData);
    }
    
    /**
     * 尝试确定专辑并返回结果
     */
    public FolderAlbumCache.CachedAlbumInfo tryDetermineAlbum(String folderPath, String fileName, 
                                                              int musicFilesInFolder, 
                                                              FolderAlbumCache.AlbumIdentificationInfo albumInfo) {
        return folderAlbumCache.addSample(folderPath, fileName, musicFilesInFolder, albumInfo);
    }

    /**
     * 尝试确定专辑（携带剩余未处理文件数）
     *
     * @param remainingUnprocessedCount 文件夹内尚未处理的音乐文件总数，
     *        用于正确计算本次运行能收集到多少样本（不能用 pending 队列长度代替）
     */
    public FolderAlbumCache.CachedAlbumInfo tryDetermineAlbum(String folderPath, String fileName,
                                                              int musicFilesInFolder,
                                                              FolderAlbumCache.AlbumIdentificationInfo albumInfo,
                                                              int remainingUnprocessedCount) {
        return folderAlbumCache.addSample(folderPath, fileName, musicFilesInFolder, albumInfo, remainingUnprocessedCount);
    }

    public FolderAlbumCache.CachedAlbumInfo tryDetermineAlbum(String folderPath, String fileName,
                                                              int musicFilesInFolder,
                                                              FolderAlbumCache.AlbumIdentificationInfo albumInfo,
                                                              int remainingUnprocessedCount,
                                                              boolean musicFilesCountReliable) {
        return folderAlbumCache.addSample(folderPath, fileName, musicFilesInFolder, albumInfo,
            remainingUnprocessedCount, musicFilesCountReliable);
    }
    
    /**
     * 获取待处理文件数量
     */
    public int getPendingFileCount(String folderPath) {
        return folderAlbumCache.getPendingFileCount(folderPath);
    }
    
    /**
     * 获取已缓存的专辑信息
     */
    public FolderAlbumCache.CachedAlbumInfo getCachedAlbum(String folderPath, int musicFilesInFolder) {
        return folderAlbumCache.getFolderAlbum(folderPath, musicFilesInFolder);
    }

    public FolderAlbumCache.CachedAlbumInfo getCachedAlbum(String folderPath, int musicFilesInFolder,
                                                            boolean musicFilesCountReliable) {
        return folderAlbumCache.getFolderAlbum(folderPath, musicFilesInFolder, musicFilesCountReliable);
    }
    
    /**
     * 设置文件夹专辑缓存
     */
    public void setFolderAlbum(String folderPath, FolderAlbumCache.CachedAlbumInfo albumInfo) {
        folderAlbumCache.setFolderAlbum(folderPath, albumInfo);
    }

    private void cleanupPendingTemp(FolderAlbumCache.PendingFile pending) {
        java.nio.file.Path tempDir = pending.getProcessingTempDir();
        if (tempDir == null) {
            return;
        }
        try {
            java.nio.file.Files.deleteIfExists(pending.getProcessingFile().toPath());
        } catch (java.io.IOException e) {
            log.debug("Failed to delete normalized temp file: {} - {}", pending.getProcessingFile().getAbsolutePath(), e.getMessage());
        }
        try {
            java.nio.file.Files.deleteIfExists(tempDir);
        } catch (java.io.IOException e) {
            log.debug("Failed to delete normalized temp dir: {} - {}", tempDir, e.getMessage());
        }
    }
}

