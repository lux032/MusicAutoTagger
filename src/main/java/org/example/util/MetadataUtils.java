package org.example.util;

import lombok.extern.slf4j.Slf4j;
import org.example.model.MusicMetadata;
import org.example.service.AudioFingerprintService;

import java.util.List;

/**
 * 元数据工具类
 * 负责元数据的合并、转换和验证
 */
@Slf4j
public class MetadataUtils {
    
    /**
     * 检查是否有完整的标签
     */
    public static boolean hasCompleteTags(MusicMetadata metadata) {
        return metadata.getTitle() != null && !metadata.getTitle().isEmpty() &&
               metadata.getArtist() != null && !metadata.getArtist().isEmpty() &&
               metadata.getAlbum() != null && !metadata.getAlbum().isEmpty();
    }
    
    /**
     * 将 AcoustID RecordingInfo 转换为 MusicBrainz MusicMetadata
     */
    public static MusicMetadata convertToMusicMetadata(AudioFingerprintService.RecordingInfo recordingInfo) {
        MusicMetadata metadata = new MusicMetadata();
        metadata.setRecordingId(recordingInfo.getRecordingId());
        metadata.setTitle(recordingInfo.getTitle());
        metadata.setArtist(recordingInfo.getArtist());
        metadata.setAlbum(recordingInfo.getAlbum());
        return metadata;
    }
    
    /**
     * 从 AcoustID 返回的多个录音中选择最佳匹配
     * 优先选择与已锁定专辑 Release Group ID 匹配的录音
     * 确保返回的录音有完整的 title 和 artist 信息
     *
     * @param recordings AcoustID 返回的录音列表
     * @param lockedReleaseGroupId 锁定的专辑 Release Group ID
     * @return 最佳匹配的录音
     */
    public static AudioFingerprintService.RecordingInfo findBestRecordingMatch(
            List<AudioFingerprintService.RecordingInfo> recordings,
            String lockedReleaseGroupId) {
        return findBestRecordingMatch(recordings, lockedReleaseGroupId, null);
    }
    
    /**
     * 从 AcoustID 返回的多个录音中选择最佳匹配
     * 优先选择与已锁定专辑 Release Group ID 匹配的录音
     * 确保返回的录音有完整的 title 和 artist 信息
     * 当有多个匹配的录音时，根据文件名进一步匹配（如区分 instrumental 版本）
     *
     * @param recordings AcoustID 返回的录音列表
     * @param lockedReleaseGroupId 锁定的专辑 Release Group ID
     * @param fileName 源文件名，用于更精确的匹配
     * @return 最佳匹配的录音
     */
    public static AudioFingerprintService.RecordingInfo findBestRecordingMatch(
            List<AudioFingerprintService.RecordingInfo> recordings,
            String lockedReleaseGroupId,
            String fileName) {

        if (lockedReleaseGroupId != null && !lockedReleaseGroupId.isEmpty()) {
            // 收集所有与锁定专辑匹配且信息完整的录音
            List<AudioFingerprintService.RecordingInfo> matchingRecordings = new java.util.ArrayList<>();
            
            for (AudioFingerprintService.RecordingInfo recording : recordings) {
                if (recording.getReleaseGroups() != null && isRecordingComplete(recording)) {
                    for (AudioFingerprintService.ReleaseGroupInfo rg : recording.getReleaseGroups()) {
                        if (lockedReleaseGroupId.equals(rg.getId())) {
                            matchingRecordings.add(recording);
                            break;
                        }
                    }
                }
            }
            
            if (!matchingRecordings.isEmpty()) {
                // 如果只有一个匹配，直接返回
                if (matchingRecordings.size() == 1) {
                    AudioFingerprintService.RecordingInfo match = matchingRecordings.get(0);
                    log.info("找到与锁定专辑匹配的录音: {} - {}",
                        match.getArtist(), match.getTitle());
                    return match;
                }
                
                // 有多个匹配，根据文件名进行更精确的匹配
                log.info("找到 {} 个与锁定专辑匹配的录音，进行更精确的匹配...", matchingRecordings.size());
                AudioFingerprintService.RecordingInfo bestMatch = selectBestMatchByFileName(matchingRecordings, fileName);
                log.info("根据文件名匹配选择录音: {} - {}",
                    bestMatch.getArtist(), bestMatch.getTitle());
                return bestMatch;
            }
            
            log.warn("未找到与锁定专辑 Release Group ID {} 匹配的录音，将使用最佳匹配", lockedReleaseGroupId);
        }

        // 如果没有锁定专辑或未找到匹配，返回第一个信息完整的录音
        for (AudioFingerprintService.RecordingInfo recording : recordings) {
            if (isRecordingComplete(recording)) {
                return recording;
            }
        }
        
        // 保底：如果所有录音都不完整，返回第一个
        log.warn("所有录音信息都不完整，使用第一个录音");
        return recordings.get(0);
    }
    
    /**
     * 根据文件名从多个匹配的录音中选择最佳匹配
     * 主要用于区分 instrumental、live、remix 等不同版本
     */
    private static AudioFingerprintService.RecordingInfo selectBestMatchByFileName(
            List<AudioFingerprintService.RecordingInfo> recordings,
            String fileName) {
        
        if (fileName == null || fileName.isEmpty() || recordings.size() == 1) {
            return recordings.get(0);
        }
        
        String fileNameLower = fileName.toLowerCase();
        
        // 定义版本标识符列表（包含常见的混音/版本标识）
        String[] versionIndicators = {
            "instrumental", "inst", "karaoke", "off vocal", "offvocal",
            "live", "acoustic", "remix", "extended", "radio edit",
            "tv size", "tv ver", "movie ver", "full ver",
            "album mix", "album ver", "album version", "single mix", "single ver",
            "original mix", "remaster", "remastered", "bonus track",
            "short ver", "long ver", "edit", "demo"
        };
        
        // 检查文件名中包含哪些版本标识符
        List<String> fileNameIndicators = new java.util.ArrayList<>();
        for (String indicator : versionIndicators) {
            if (fileNameLower.contains(indicator)) {
                fileNameIndicators.add(indicator);
            }
        }
        
        // 计算每个录音的匹配分数
        AudioFingerprintService.RecordingInfo bestMatch = null;
        int bestScore = Integer.MIN_VALUE;
        
        for (AudioFingerprintService.RecordingInfo recording : recordings) {
            String titleLower = recording.getTitle().toLowerCase();
            int score = 0;
            
            // 检查录音标题中包含哪些版本标识符
            List<String> titleIndicators = new java.util.ArrayList<>();
            for (String indicator : versionIndicators) {
                if (titleLower.contains(indicator)) {
                    titleIndicators.add(indicator);
                }
            }
            
            // 计算分数：
            // +100 分：文件名和标题中都有相同的版本标识符
            // -100 分：文件名没有但标题有某个版本标识符
            // -50 分：文件名有但标题没有某个版本标识符
            
            for (String indicator : fileNameIndicators) {
                if (titleIndicators.contains(indicator)) {
                    score += 100; // 文件名和标题都有
                    log.debug("录音 '{}' 匹配版本标识符 '{}' (+100)", recording.getTitle(), indicator);
                } else {
                    score -= 50; // 文件名有但标题没有
                    log.debug("录音 '{}' 缺少版本标识符 '{}' (-50)", recording.getTitle(), indicator);
                }
            }
            
            for (String indicator : titleIndicators) {
                if (!fileNameIndicators.contains(indicator)) {
                    score -= 100; // 标题有但文件名没有（惩罚更重）
                    log.debug("录音 '{}' 多余版本标识符 '{}' (-100)", recording.getTitle(), indicator);
                }
            }
            
            // 如果文件名和标题都没有任何版本标识符，给予一些基础分
            if (fileNameIndicators.isEmpty() && titleIndicators.isEmpty()) {
                score += 10;
            }
            
            log.debug("录音 '{}' 最终匹配分数: {}", recording.getTitle(), score);
            
            if (score > bestScore) {
                bestScore = score;
                bestMatch = recording;
            }
        }
        
        if (bestMatch != null) {
            log.info("版本匹配分析完成 - 最佳匹配: '{}' (分数: {})", bestMatch.getTitle(), bestScore);
            return bestMatch;
        }
        
        // 如果无法确定，返回第一个
        return recordings.get(0);
    }
    
    /**
     * 检查录音信息是否完整（有 title 和 artist）
     */
    public static boolean isRecordingComplete(AudioFingerprintService.RecordingInfo recording) {
        return recording.getTitle() != null && !recording.getTitle().isEmpty() &&
               recording.getArtist() != null && !recording.getArtist().isEmpty();
    }

    /**
     * 合并元数据：保留源文件中已有的标签信息
     *
     * 策略：
     * - 歌曲名、专辑名、专辑艺术家：使用新识别的数据（来自快速扫描或指纹识别）
     * - 作曲家、作词家、歌词、风格：优先使用新识别的数据，如果新数据为空则保留源文件的
     *
     * @param sourceMetadata 源文件已有的元数据
     * @param newMetadata 新识别的元数据
     * @return 合并后的元数据
     */
    public static MusicMetadata mergeMetadata(MusicMetadata sourceMetadata, MusicMetadata newMetadata) {
        if (sourceMetadata == null) {
            return newMetadata;
        }

        if (newMetadata == null) {
            return sourceMetadata;
        }

        // 创建结果对象，基于新识别的元数据
        MusicMetadata merged = newMetadata;

        // 保留源文件中的作曲家信息（如果新数据没有）
        if ((merged.getComposer() == null || merged.getComposer().isEmpty()) &&
            (sourceMetadata.getComposer() != null && !sourceMetadata.getComposer().isEmpty())) {
            log.info("保留源文件的作曲家信息: {}", sourceMetadata.getComposer());
            merged.setComposer(sourceMetadata.getComposer());
        }

        // 保留源文件中的作词家信息（如果新数据没有）
        if ((merged.getLyricist() == null || merged.getLyricist().isEmpty()) &&
            (sourceMetadata.getLyricist() != null && !sourceMetadata.getLyricist().isEmpty())) {
            log.info("保留源文件的作词家信息: {}", sourceMetadata.getLyricist());
            merged.setLyricist(sourceMetadata.getLyricist());
        }

        // 保留源文件中的歌词（如果新数据没有）
        if ((merged.getLyrics() == null || merged.getLyrics().isEmpty()) &&
            (sourceMetadata.getLyrics() != null && !sourceMetadata.getLyrics().isEmpty())) {
            log.info("保留源文件的歌词信息");
            merged.setLyrics(sourceMetadata.getLyrics());
        }

        // 保留源文件中的风格信息（如果新数据没有）
        if ((merged.getGenres() == null || merged.getGenres().isEmpty()) &&
            (sourceMetadata.getGenres() != null && !sourceMetadata.getGenres().isEmpty())) {
            log.info("保留源文件的风格信息: {}", sourceMetadata.getGenres());
            merged.setGenres(sourceMetadata.getGenres());
        }

        return merged;
    }
    
    /**
     * 创建基于快速扫描结果的元数据
     * 当AcoustID未关联到详细录音信息但快速扫描已锁定专辑时使用
     */
    public static MusicMetadata createMetadataFromQuickScan(
            MusicMetadata sourceTagsForFallback,
            String lockedAlbumTitle,
            String lockedAlbumArtist,
            String lockedReleaseGroupId,
            String lockedReleaseDate,
            String fileName) {
        
        MusicMetadata detailedMetadata = new MusicMetadata();

        // 优先使用源文件的标签信息
        String titleToUse;
        String artistToUse;

        if (sourceTagsForFallback != null &&
            sourceTagsForFallback.getTitle() != null &&
            !sourceTagsForFallback.getTitle().isEmpty()) {
            // 使用源文件的标题
            titleToUse = sourceTagsForFallback.getTitle();
            log.info("使用源文件标签中的标题: {}", titleToUse);
        } else {
            // 使用文件名作为标题（去掉扩展名）
            int lastDotIndex = fileName.lastIndexOf('.');
            titleToUse = (lastDotIndex > 0) ? fileName.substring(0, lastDotIndex) : fileName;
            log.info("使用文件名作为标题（已去除扩展名）: {}", titleToUse);
        }

        if (sourceTagsForFallback != null &&
            sourceTagsForFallback.getArtist() != null &&
            !sourceTagsForFallback.getArtist().isEmpty()) {
            // 使用源文件的艺术家
            artistToUse = sourceTagsForFallback.getArtist();
            log.info("使用源文件标签中的艺术家: {}", artistToUse);
        } else {
            // 使用锁定的专辑艺术家
            artistToUse = lockedAlbumArtist;
            log.info("使用锁定的专辑艺术家: {}", artistToUse);
        }

        detailedMetadata.setTitle(titleToUse);
        detailedMetadata.setArtist(artistToUse);
        detailedMetadata.setAlbumArtist(lockedAlbumArtist);
        detailedMetadata.setAlbum(lockedAlbumTitle);
        detailedMetadata.setReleaseGroupId(lockedReleaseGroupId);
        detailedMetadata.setReleaseDate(lockedReleaseDate);

        // 同时保留源文件的其他标签信息（作曲、作词、歌词、风格等）
        if (sourceTagsForFallback != null) {
            if (sourceTagsForFallback.getComposer() != null && !sourceTagsForFallback.getComposer().isEmpty()) {
                detailedMetadata.setComposer(sourceTagsForFallback.getComposer());
            }
            if (sourceTagsForFallback.getLyricist() != null && !sourceTagsForFallback.getLyricist().isEmpty()) {
                detailedMetadata.setLyricist(sourceTagsForFallback.getLyricist());
            }
            if (sourceTagsForFallback.getLyrics() != null && !sourceTagsForFallback.getLyrics().isEmpty()) {
                detailedMetadata.setLyrics(sourceTagsForFallback.getLyrics());
            }
            if (sourceTagsForFallback.getGenres() != null && !sourceTagsForFallback.getGenres().isEmpty()) {
                detailedMetadata.setGenres(sourceTagsForFallback.getGenres());
            }
            if (sourceTagsForFallback.getDiscNo() != null && !sourceTagsForFallback.getDiscNo().isEmpty()) {
                detailedMetadata.setDiscNo(sourceTagsForFallback.getDiscNo());
            }
            if (sourceTagsForFallback.getTrackNo() != null && !sourceTagsForFallback.getTrackNo().isEmpty()) {
                detailedMetadata.setTrackNo(sourceTagsForFallback.getTrackNo());
            }
        }
        
        return detailedMetadata;
    }
    
    /**
     * 应用锁定的专辑信息到元数据
     */
    public static void applyLockedAlbumInfo(MusicMetadata metadata, 
                                             String lockedAlbumTitle, 
                                             String lockedAlbumArtist,
                                             String lockedReleaseGroupId,
                                             String lockedReleaseDate) {
        if (lockedAlbumTitle != null) {
            log.info("应用锁定的专辑信息: {}", lockedAlbumTitle);
            metadata.setAlbum(lockedAlbumTitle);
            metadata.setAlbumArtist(lockedAlbumArtist);
            metadata.setReleaseGroupId(lockedReleaseGroupId);
            if (lockedReleaseDate != null && !lockedReleaseDate.isEmpty()) {
                metadata.setReleaseDate(lockedReleaseDate);
            }
        }
    }
}