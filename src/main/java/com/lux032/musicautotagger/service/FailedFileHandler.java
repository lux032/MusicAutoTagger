package com.lux032.musicautotagger.service;

import lombok.extern.slf4j.Slf4j;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.util.FileSystemUtils;
import com.lux032.musicautotagger.util.I18nUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 失败文件处理服务
 * 负责处理识别失败的文件和部分识别文件
 */
@Slf4j
public class FailedFileHandler {
    
    private final MusicConfig config;
    private final TagWriterService tagWriter;
    private final CoverArtService coverArtService;
    private final ProcessedFileLogger processedLogger;
    private final FileSystemUtils fileSystemUtils;
    private final AudioFormatNormalizer audioFormatNormalizer;
    
    public FailedFileHandler(MusicConfig config, TagWriterService tagWriter, 
                             CoverArtService coverArtService, ProcessedFileLogger processedLogger,
                             FileSystemUtils fileSystemUtils) {
        this.config = config;
        this.tagWriter = tagWriter;
        this.coverArtService = coverArtService;
        this.processedLogger = processedLogger;
        this.fileSystemUtils = fileSystemUtils;
        this.audioFormatNormalizer = new AudioFormatNormalizer(config);
    }

    /**
     * 处理部分识别的文件(有标签或封面但指纹识别失败)
     * 将文件复制到部分识别目录，保留原始的文件夹结构，并尝试内嵌文件夹封面
     */
    public void handlePartialRecognitionFile(File audioFile) {
        handlePartialRecognitionFile(audioFile, null);
    }

    /**
     * 处理部分识别的文件(有标签或封面但指纹识别失败)
     * 将文件复制到部分识别目录，保留原始的文件夹结构，并尝试内嵌文件夹封面
     */
    public void handlePartialRecognitionFile(File audioFile, File processingFile) {
        if (config.getPartialDirectory() == null || config.getPartialDirectory().isEmpty()) {
            return; // 未配置部分识别目录，跳过
        }
        
        try {
            // 检查是否有内嵌封面
            boolean hasEmbeddedCover = tagWriter.hasEmbeddedCover(audioFile);
            
            // 检查文件夹中是否有封面
            byte[] folderCover = coverArtService.findCoverInDirectory(audioFile.getParentFile());
            boolean hasFolderCover = (folderCover != null && folderCover.length > 0);
            
            // 封面是必需条件：如果既没有内嵌封面也没有文件夹封面，不处理
            if (!hasEmbeddedCover && !hasFolderCover) {
                log.info(I18nUtil.getMessage("main.partial.recognition.no.cover"));
                LogCollector.addLog("INFO", "  " + I18nUtil.getMessage("main.partial.recognition.no.cover"));
                return;
            }
            
            // 检查是否有部分标签信息
            boolean hasPartialTags = tagWriter.hasPartialTags(audioFile);
            
            log.info("========================================");
            log.info(I18nUtil.getMessage("main.partial.recognition.detected"));
            LogCollector.addLog("INFO", I18nUtil.getMessage("main.partial.recognition.detected") + ": " + audioFile.getName());
            log.info(I18nUtil.getMessage("main.partial.recognition.has.embedded.cover") + ": {}", hasEmbeddedCover);
            log.info(I18nUtil.getMessage("main.partial.recognition.has.folder.cover") + ": {}", hasFolderCover);
            log.info(I18nUtil.getMessage("main.partial.recognition.has.tags") + ": {}", hasPartialTags);
            
            // 获取相对路径
            String relativePath = fileSystemUtils.getRelativePath(audioFile);
            
            // 构建目标文件路径，保留文件夹结构
            File targetFile = new File(config.getPartialDirectory(), relativePath);
            
            // 创建目标目录
            if (!targetFile.getParentFile().exists()) {
                targetFile.getParentFile().mkdirs();
            }
            
            // 如果目标文件已存在，跳过复制
            if (targetFile.exists()) {
                log.debug("部分识别文件已存在，跳过复制: {}", targetFile.getAbsolutePath());
                return;
            }
            
            // 复制文件到部分识别目录
            log.info(I18nUtil.getMessage("main.partial.recognition.copying") + ": {}", targetFile.getAbsolutePath());
            LogCollector.addLog("INFO", "  -> " + I18nUtil.getMessage("main.partial.recognition.copying") + ": " + relativePath);
            File sourceFile = (processingFile != null && processingFile.exists()) ? processingFile : audioFile;
            Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            
            // 如果使用了转码文件，尽量保留原始标签
            if (sourceFile != audioFile) {
                com.lux032.musicautotagger.model.MusicMetadata sourceTags = tagWriter.readTags(audioFile);
                if (sourceTags != null) {
                    tagWriter.updateTagsOnExistingFile(targetFile, sourceTags, null);
                }
            }

            // 处理封面：优先文件夹封面，否则使用源文件内嵌封面
            byte[] embeddedCover = null;
            if (!hasFolderCover && hasEmbeddedCover) {
                embeddedCover = coverArtService.extractEmbeddedCover(audioFile);
            }
            byte[] coverToEmbed = hasFolderCover ? folderCover : embeddedCover;
            if (coverToEmbed != null && !tagWriter.hasEmbeddedCover(targetFile)) {
                log.info(I18nUtil.getMessage("main.partial.recognition.embedding.cover"));
                LogCollector.addLog("INFO", "  -> " + I18nUtil.getMessage("main.partial.recognition.embedding.cover"));
                boolean embedSuccess = tagWriter.embedFolderCover(targetFile, coverToEmbed);
                if (embedSuccess) {
                    log.info(I18nUtil.getMessage("main.partial.recognition.embed.success"));
                    LogCollector.addLog("SUCCESS", "  " + I18nUtil.getMessage("main.partial.recognition.embed.success"));
                } else {
                    log.warn(I18nUtil.getMessage("main.partial.recognition.embed.failed"));
                    LogCollector.addLog("WARN", "  " + I18nUtil.getMessage("main.partial.recognition.embed.failed"));
                }
            }
            
            log.info(I18nUtil.getMessage("main.partial.recognition.complete") + ": {}", targetFile.getAbsolutePath());
            LogCollector.addLog("SUCCESS", I18nUtil.getMessage("main.partial.recognition.complete") + ": " + relativePath);
            log.info("========================================");
            
        } catch (Exception e) {
            log.error(I18nUtil.getMessage("main.partial.recognition.failed") + ": {}", audioFile.getName(), e);
        }
    }
    
    /**
     * 处理部分识别的专辑（整个专辑文件夹）
     * 将整个专辑文件夹复制到部分识别目录，并为每个文件内嵌封面
     */
    public void handlePartialRecognitionAlbum(File albumRootDir) {
        if (config.getPartialDirectory() == null || config.getPartialDirectory().isEmpty()) {
            return; // 未配置部分识别目录，跳过
        }
        
        if (albumRootDir == null || !albumRootDir.exists()) {
            return;
        }
        
        try {
            // 检查文件夹中是否有封面
            byte[] folderCover = coverArtService.findCoverInDirectory(albumRootDir);
            boolean hasFolderCover = (folderCover != null && folderCover.length > 0);
            
            // 收集专辑中的所有音频文件
            List<File> audioFiles = new ArrayList<>();
            fileSystemUtils.collectAudioFilesForMarking(albumRootDir, audioFiles);
            
            if (audioFiles.isEmpty()) {
                return;
            }
            
            // 检查是否有任何文件有内嵌封面
            boolean anyHasEmbeddedCover = false;
            for (File audioFile : audioFiles) {
                if (tagWriter.hasEmbeddedCover(audioFile)) {
                    anyHasEmbeddedCover = true;
                    break;
                }
            }
            
            // 封面是必需条件：如果既没有内嵌封面也没有文件夹封面，不处理
            if (!anyHasEmbeddedCover && !hasFolderCover) {
                log.info(I18nUtil.getMessage("main.partial.recognition.no.cover"));
                LogCollector.addLog("INFO", "  " + I18nUtil.getMessage("main.partial.recognition.no.cover"));
                return;
            }
            
            // 检查是否有部分标签信息（检查第一个文件即可）
            boolean hasPartialTags = tagWriter.hasPartialTags(audioFiles.get(0));
            
            log.info("========================================");
            log.info(I18nUtil.getMessage("main.partial.recognition.album.detected", albumRootDir.getName()));
            LogCollector.addLog("INFO", I18nUtil.getMessage("main.partial.recognition.album.detected", albumRootDir.getName()));
            log.info(I18nUtil.getMessage("main.partial.recognition.has.folder.cover") + ": {}", hasFolderCover);
            log.info(I18nUtil.getMessage("main.partial.recognition.has.tags") + ": {}", hasPartialTags);
            log.info("专辑文件数: {}", audioFiles.size());
            
            // 构建目标文件夹路径
            String folderName = albumRootDir.getName();
            File targetFolder = new File(config.getPartialDirectory(), folderName);
            
            // 如果目标文件夹已存在，跳过复制
            if (targetFolder.exists()) {
                log.debug("部分识别专辑文件夹已存在，跳过复制: {}", targetFolder.getAbsolutePath());
                return;
            }
            
            // 复制整个专辑文件夹到部分识别目录
            log.info(I18nUtil.getMessage("main.partial.recognition.copying.album") + ": {}", targetFolder.getAbsolutePath());
            LogCollector.addLog("INFO", "  -> " + I18nUtil.getMessage("main.partial.recognition.copying.album") + ": " + folderName);
            
            int[] counts = fileSystemUtils.copyDirectoryRecursively(albumRootDir.toPath(), targetFolder.toPath());
            int copiedCount = counts[0];
            int skippedCount = counts[1];
            
            log.info("专辑文件夹复制完成: 成功 {} 个文件, 跳过 {} 个", copiedCount, skippedCount);
            int embedSuccessCount = 0;
            int embedSkipCount = 0;
            int embedFailCount = 0;
            int normalizedCount = 0;

            // 遍历源文件并定位目标文件（必要时转码，并恢复标签/封面）
            Path albumRootPath = albumRootDir.toPath();
            for (File audioFile : audioFiles) {
                Path relative = albumRootPath.relativize(audioFile.toPath());
                File targetAudioFile = new File(targetFolder, relative.toString());

                if (!targetAudioFile.exists()) {
                    continue;
                }

                boolean converted = audioFormatNormalizer.normalizeToTargetIfNeeded(audioFile, targetAudioFile);
                if (converted) {
                    normalizedCount++;
                    com.lux032.musicautotagger.model.MusicMetadata sourceTags = tagWriter.readTags(audioFile);
                    if (sourceTags != null) {
                        tagWriter.updateTagsOnExistingFile(targetAudioFile, sourceTags, null);
                    }
                }

                byte[] coverToEmbed = null;
                if (hasFolderCover) {
                    coverToEmbed = folderCover;
                } else if (tagWriter.hasEmbeddedCover(audioFile)) {
                    coverToEmbed = coverArtService.extractEmbeddedCover(audioFile);
                }

                if (coverToEmbed != null) {
                    if (!tagWriter.hasEmbeddedCover(targetAudioFile)) {
                        boolean embedSuccess = tagWriter.embedFolderCover(targetAudioFile, coverToEmbed);
                        if (embedSuccess) {
                            embedSuccessCount++;
                        } else {
                            embedFailCount++;
                        }
                    } else {
                        embedSkipCount++;
                    }
                }
            }

            if (normalizedCount > 0) {
                log.info("部分识别专辑已转码 {} 个文件", normalizedCount);
            }

            if (embedSuccessCount + embedSkipCount + embedFailCount > 0) {
                log.info("封面内嵌完成: 成功 {} 个, 跳过 {} 个(已有封面), 失败 {} 个",
                    embedSuccessCount, embedSkipCount, embedFailCount);
                LogCollector.addLog("SUCCESS", "  " + I18nUtil.getMessage("main.partial.recognition.embed.album.complete", embedSuccessCount, embedSkipCount));
            }

            log.info(I18nUtil.getMessage("main.partial.recognition.album.complete") + ": {}", targetFolder.getAbsolutePath());
            LogCollector.addLog("SUCCESS", I18nUtil.getMessage("main.partial.recognition.album.complete") + ": " + folderName);
            log.info("========================================");
            
        } catch (Exception e) {
            log.error(I18nUtil.getMessage("main.partial.recognition.album.failed") + ": {}", albumRootDir.getName(), e);
        }
    }
    
    /**
     * 复制失败的单个文件到失败目录
     */
    public void copyFailedFileToFailedDirectory(File audioFile) throws IOException {
        if (audioFile == null || !audioFile.exists()) {
            log.warn("文件不存在，无法复制");
            return;
        }
        
        String fileName = audioFile.getName();
        File targetFile = new File(config.getFailedDirectory(), fileName);
        
        // 检查是否已经复制过该文件
        if (targetFile.exists()) {
            log.debug("失败文件已存在，跳过复制: {}", targetFile.getAbsolutePath());
            return;
        }
        
        log.info("========================================");
        log.info("识别失败，复制单个文件到失败目录");
        log.info("源文件: {}", audioFile.getAbsolutePath());
        log.info("目标位置: {}", targetFile.getAbsolutePath());
        
        try {
            Files.copy(audioFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log.info("文件复制完成: {}", targetFile.getAbsolutePath());
        } catch (IOException e) {
            log.error("复制文件失败: {} - {}", fileName, e.getMessage());
            throw e;
        }
        
        log.info("========================================");
    }
    
    /**
     * 复制失败的专辑根目录到失败目录
     * 保留文件夹结构（包括所有子文件夹如 Disc 1, Disc 2），方便用户手动处理
     */
    public void copyFailedFolderToFailedDirectory(File albumRootFolder) throws IOException {
        if (albumRootFolder == null || !albumRootFolder.exists()) {
            log.warn("专辑根目录不存在，无法复制");
            return;
        }
        
        // 构建目标路径：失败目录/专辑根文件夹名
        String folderName = albumRootFolder.getName();
        File targetFolder = new File(config.getFailedDirectory(), folderName);
        
        // 检查是否已经复制过该文件夹
        if (targetFolder.exists()) {
            log.debug("失败文件夹已存在，跳过复制: {}", targetFolder.getAbsolutePath());
            return;
        }
        
        log.info("========================================");
        log.info("识别失败，复制整个专辑根目录到失败目录");
        log.info("源文件夹: {}", albumRootFolder.getAbsolutePath());
        log.info("目标位置: {}", targetFolder.getAbsolutePath());
        
        // 递归复制整个专辑根目录（包括所有子文件夹）
        int[] counts = fileSystemUtils.copyDirectoryRecursively(albumRootFolder.toPath(), targetFolder.toPath());
        int copiedCount = counts[0];
        int skippedCount = counts[1];
        
        log.info("文件夹复制完成: 成功 {} 个文件, 跳过 {} 个", copiedCount, skippedCount);
        log.info("失败文件夹位置: {}", targetFolder.getAbsolutePath());
        log.info("========================================");
    }
    
    /**
     * 标记专辑根目录下的所有音频文件为已处理
     * 用于识别失败后，避免继续处理同一专辑的其他文件
     */
    public void markAlbumAsProcessed(File albumRootDir, String reason) {
        if (albumRootDir == null || !albumRootDir.exists()) {
            return;
        }
        
        log.info("========================================");
        log.info("标记整个专辑为已处理: {}", albumRootDir.getName());
        
        int markedCount = 0;
        try {
            // 递归收集专辑根目录下的所有音频文件
            List<File> audioFiles = new ArrayList<>();
            fileSystemUtils.collectAudioFilesForMarking(albumRootDir, audioFiles);
            
            // 标记所有音频文件为已处理
            for (File audioFile : audioFiles) {
                try {
                    processedLogger.markFileAsProcessed(
                        audioFile,
                        "UNKNOWN",
                        reason,
                        audioFile.getName(),
                        "Unknown Album"
                    );
                    markedCount++;
                } catch (Exception e) {
                    log.warn("标记文件失败: {} - {}", audioFile.getName(), e.getMessage());
                }
            }
            
            log.info("已标记 {} 个音频文件为已处理，队列中的其他文件将被跳过", markedCount);
            log.info("========================================");
            
        } catch (Exception e) {
            log.error("标记专辑文件时出错: {}", e.getMessage());
        }
    }
    
    /**
     * 处理识别失败的散落文件
     */
    public void handleLooseFileFailed(File audioFile) {
        handleLooseFileFailed(audioFile, null);
    }

    public void handleLooseFileFailed(File audioFile, File processingFile) {
        log.warn("散落文件识别失败: {}", audioFile.getName());
        LogCollector.addLog("WARN", I18nUtil.getMessage("main.recognition.failed.loose", audioFile.getName()));
        
        // 尝试处理部分识别文件
        handlePartialRecognitionFile(audioFile, processingFile);
        
        // 复制到失败目录
        if (config.getFailedDirectory() != null && !config.getFailedDirectory().isEmpty()) {
            try {
                copyFailedFileToFailedDirectory(audioFile);
            } catch (Exception e) {
                log.error("复制失败文件到失败目录时出错: {}", e.getMessage());
            }
        }
        
        processedLogger.markFileAsProcessed(
            audioFile,
            "UNKNOWN",
            "识别失败 - 散落文件",
            audioFile.getName(),
            "Unknown Album"
        );
    }
    
    /**
     * 处理识别失败的专辑文件
     */
    public void handleAlbumFileFailed(File audioFile, File albumRootDir) {
        log.warn("专辑识别失败: {}", albumRootDir.getName());
        LogCollector.addLog("WARN", I18nUtil.getMessage("main.recognition.failed.album", albumRootDir.getName(), audioFile.getName()));
        
        // 尝试处理整个专辑的部分识别（复制整个专辑到部分识别目录）
        handlePartialRecognitionAlbum(albumRootDir);
        
        // 复制整个专辑到失败目录
        if (config.getFailedDirectory() != null && !config.getFailedDirectory().isEmpty()) {
            try {
                copyFailedFolderToFailedDirectory(albumRootDir);
            } catch (Exception e) {
                log.error("复制失败文件夹到失败目录时出错: {}", e.getMessage());
            }
        }

        // 标记整个专辑根目录下的所有文件为"已处理"，避免继续识别
        markAlbumAsProcessed(albumRootDir, "识别失败 - 整个专辑");
    }
}

