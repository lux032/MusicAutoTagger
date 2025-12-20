package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.config.MusicConfig;
import org.example.model.MusicMetadata;
import org.example.util.I18nUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
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
     * 处理并写入单个文件
     * @param audioFile 音频文件
     * @param metadata 元数据
     * @param coverArtData 封面数据
     * @param isQuickScanMode 是否为快速扫描模式（用于区分日志显示）
     */
    public void processAndWriteFile(File audioFile, MusicMetadata metadata, byte[] coverArtData, boolean isQuickScanMode) {
        try {
            log.info("正在写入文件标签: {}", audioFile.getName());
            boolean success = tagWriter.processFile(audioFile, metadata, coverArtData);
            
            if (success) {
                if (isQuickScanMode) {
                    log.info("✓ 文件处理成功（快速扫描模式）: {}", audioFile.getName());
                    LogCollector.addLog("SUCCESS", "✓ " + I18nUtil.getMessage("main.process.success.quick.scan", audioFile.getName()));
                } else {
                    log.info("✓ 文件处理成功: {}", audioFile.getName());
                    LogCollector.addLog("SUCCESS", "✓ " + I18nUtil.getMessage("main.process.success.fingerprint", audioFile.getName()));
                }
                
                // 记录文件已处理
                processedLogger.markFileAsProcessed(
                    audioFile,
                    metadata.getRecordingId(),
                    metadata.getArtist(),
                    metadata.getTitle(),
                    metadata.getAlbum()
                );
            } else {
                log.error("✗ 文件处理失败: {}", audioFile.getName());
                LogCollector.addLog("ERROR", "✗ " + I18nUtil.getMessage("main.process.error", audioFile.getName()));
                // 关键修复：写入失败也要记录到数据库，避免文件"静默丢失"
                processedLogger.markFileAsProcessed(
                    audioFile,
                    "WRITE_FAILED",
                    metadata.getArtist() != null ? metadata.getArtist() : "Unknown Artist",
                    metadata.getTitle() != null ? metadata.getTitle() : audioFile.getName(),
                    metadata.getAlbum() != null ? metadata.getAlbum() : "Unknown Album"
                );
                log.info("已将写入失败文件记录到数据库: {}", audioFile.getName());
            }
        } catch (Exception e) {
            log.error(I18nUtil.getMessage("main.write.exception"), audioFile.getName(), e);
            // 关键修复：异常时也要记录到数据库，避免文件"静默丢失"
            try {
                processedLogger.markFileAsProcessed(
                    audioFile,
                    "EXCEPTION",
                    metadata.getArtist() != null ? metadata.getArtist() : "Unknown Artist",
                    metadata.getTitle() != null ? metadata.getTitle() : audioFile.getName(),
                    metadata.getAlbum() != null ? metadata.getAlbum() : "Unknown Album"
                );
                log.info("已将异常文件记录到数据库: {}", audioFile.getName());
            } catch (Exception recordError) {
                log.error("记录异常文件到数据库失败: {} - {}", audioFile.getName(), recordError.getMessage());
            }
        }
    }

    /**
     * 批量处理文件夹内的待处理文件，统一应用确定的专辑信息
     */
    public void processPendingFilesWithAlbum(String folderPath, FolderAlbumCache.CachedAlbumInfo albumInfo) {
        List<FolderAlbumCache.PendingFile> pendingFiles = folderAlbumCache.getPendingFiles(folderPath);
        
        if (pendingFiles == null || pendingFiles.isEmpty()) {
            log.warn("文件夹没有待处理文件: {}", folderPath);
            return;
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
        
        int successCount = 0;
        int failCount = 0;
        List<File> failedFiles = new ArrayList<>();
        
        for (FolderAlbumCache.PendingFile pending : pendingFiles) {
            try {
                File audioFile = pending.getAudioFile();
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
                if (albumInfo.getReleaseDate() != null && !albumInfo.getReleaseDate().isEmpty()) {
                    metadata.setReleaseDate(albumInfo.getReleaseDate());
                }
                
                // 写入文件（metadata已包含作词、作曲、风格等信息）
                processAndWriteFile(audioFile, metadata, coverArtData, false);
                successCount++;
                
            } catch (Exception e) {
                log.error("批量处理文件失败: {}", pending.getAudioFile().getName(), e);
                failCount++;
                failedFiles.add(pending.getAudioFile());
                // 对于失败的文件，也记录到数据库，避免数据缺失
                try {
                    MusicMetadata metadata = (MusicMetadata) pending.getMetadata();
                    processedLogger.markFileAsProcessed(
                        pending.getAudioFile(),
                        metadata.getRecordingId() != null ? metadata.getRecordingId() : "UNKNOWN",
                        metadata.getArtist() != null ? metadata.getArtist() : "Unknown Artist",
                        metadata.getTitle() != null ? metadata.getTitle() : pending.getAudioFile().getName(),
                        albumInfo.getAlbumTitle()
                    );
                    log.info("已记录失败文件到数据库: {}", pending.getAudioFile().getName());
                } catch (Exception recordError) {
                    log.error("记录失败文件到数据库失败: {}", pending.getAudioFile().getName(), recordError);
                }
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
    }
    
    /**
     * 强制处理待处理文件（当专辑无法确定时使用最佳猜测）
     */
    public void forceProcessPendingFiles(String folderPath, FolderAlbumCache.AlbumIdentificationInfo bestGuess) {
        log.info("========================================");
        log.info("强制处理待处理文件，使用最佳猜测专辑: {}", bestGuess.getAlbumTitle());
        log.info("========================================");

        FolderAlbumCache.CachedAlbumInfo forcedAlbum = new FolderAlbumCache.CachedAlbumInfo(
            bestGuess.getReleaseGroupId(),
            null,  // releaseId - 强制处理时没有具体的 Release ID
            bestGuess.getAlbumTitle(),
            bestGuess.getAlbumArtist(),
            bestGuess.getTrackCount(),
            bestGuess.getReleaseDate(),
            0.5 // 低置信度
        );

        // 设置缓存以避免后续文件重复触发
        folderAlbumCache.setFolderAlbum(folderPath, forcedAlbum);

        processPendingFilesWithAlbum(folderPath, forcedAlbum);
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
                // 没有缓存，使用第一个待处理文件的元数据作为最佳猜测
                FolderAlbumCache.PendingFile firstPending = pendingFiles.get(0);
                MusicMetadata metadata = (MusicMetadata) firstPending.getMetadata();

                if (metadata != null && metadata.getAlbum() != null) {
                    log.warn("没有确定的专辑信息，使用第一个文件的元数据作为最佳猜测: {}", metadata.getAlbum());

                    FolderAlbumCache.CachedAlbumInfo guessedAlbum = new FolderAlbumCache.CachedAlbumInfo(
                        metadata.getReleaseGroupId(),
                        null,  // releaseId - 猜测时没有具体的 Release ID
                        metadata.getAlbum(),
                        metadata.getAlbumArtist() != null ? metadata.getAlbumArtist() : metadata.getArtist(),
                        metadata.getTrackCount(),
                        metadata.getReleaseDate(),
                        0.3 // 低置信度
                    );

                    processPendingFilesWithAlbum(folderPath, guessedAlbum);
                } else {
                    // 元数据也没有，直接写入每个文件自己的元数据
                    log.warn("无法确定专辑信息，直接写入每个文件自己的元数据");
                    for (FolderAlbumCache.PendingFile pending : pendingFiles) {
                        try {
                            MusicMetadata fileMetadata = (MusicMetadata) pending.getMetadata();
                            processAndWriteFile(pending.getAudioFile(), fileMetadata, pending.getCoverArtData(), false);
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
    public void addPendingFile(String folderPath, File audioFile, MusicMetadata metadata, byte[] coverArtData) {
        folderAlbumCache.addPendingFileIfAbsent(folderPath, audioFile, metadata, coverArtData);
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
    
    /**
     * 设置文件夹专辑缓存
     */
    public void setFolderAlbum(String folderPath, FolderAlbumCache.CachedAlbumInfo albumInfo) {
        folderAlbumCache.setFolderAlbum(folderPath, albumInfo);
    }
}