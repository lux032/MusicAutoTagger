package com.lux032.musicautotagger.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 快速扫描服务 - 第一级扫描
 * 
 * 功能:
 * 1. 读取音频文件的现有标签(Artist, Album)
 * 2. 解析文件夹名称(支持常见格式如 "Artist - Album (Year)")
 * 3. 去 MusicBrainz 搜索专辑
 * 4. 使用时长序列匹配验证(匹配度 > 90% 直接完成)
 * 5. 如果失败,返回null以进入第二级指纹扫描
 */
@Slf4j
public class QuickScanService {
    
    private final MusicConfig config;
    private final MusicBrainzClient musicBrainzClient;
    private final DurationSequenceService durationSequenceService;
    private final AudioFingerprintService fingerprintService;
    
    // 文件夹时长序列缓存: 文件夹路径 -> 时长序列
    private final Map<String, List<Integer>> folderDurationCache = new java.util.concurrent.ConcurrentHashMap<>();
    
    // 文件夹名称解析正则表达式
    // 支持格式: "Artist - Album (Year)", "Artist - Album", "Album (Year)", "Album"
    private static final Pattern FOLDER_PATTERN = Pattern.compile(
        "^(?:(.+?)\\s*-\\s*)?(.+?)(?:\\s*\\(\\d{4}\\))?$"
    );
    
    // 时长匹配阈值 - 90%
    private static final double QUICK_MATCH_THRESHOLD = 0.90;
    
    public QuickScanService(MusicConfig config,
                           MusicBrainzClient musicBrainzClient,
                           DurationSequenceService durationSequenceService,
                           AudioFingerprintService fingerprintService) {
        this.config = config;
        this.musicBrainzClient = musicBrainzClient;
        this.durationSequenceService = durationSequenceService;
        this.fingerprintService = fingerprintService;
    }
    
    /**
     * 执行快速扫描
     * @param audioFile 音频文件
     * @param musicFilesInFolder 文件夹内音乐文件数量
     * @return 如果快速扫描成功返回结果,否则返回null进入第二级扫描
     */
    public QuickScanResult quickScan(File audioFile, int musicFilesInFolder) {
        return quickScan(audioFile, musicFilesInFolder, null);
    }

    /**
     * 执行快速扫描（可指定预提取的时长序列）
     * @param audioFile 音频文件
     * @param musicFilesInFolder 文件夹内音乐文件数量
     * @param folderDurations 预提取的时长序列（可为空）
     * @return 如果快速扫描成功返回结果,否则返回null进入第二级扫描
     */
    public QuickScanResult quickScan(File audioFile, int musicFilesInFolder, List<Integer> folderDurations) {
        log.info("========================================");
        log.info("开始第一级快速扫描: {}", audioFile.getName());
        log.info("========================================");
        
        try {
            // 1. 收集专辑信息(从标签和文件夹名)
            AlbumSearchInfo searchInfo = collectAlbumInfo(audioFile);
            
            if (searchInfo == null || !searchInfo.isValid()) {
                log.info("未能从标签或文件夹提取专辑信息,跳过快速扫描");
                return null;
            }
            
            log.info("提取到专辑信息 - Artist: {}, Album: {}", 
                searchInfo.getArtist(), searchInfo.getAlbum());
            
            // 2. 在 MusicBrainz 搜索专辑
            List<MusicMetadata> searchResults =
                searchByAlbumInfo(searchInfo);
            
            if (searchResults == null || searchResults.isEmpty()) {
                log.info("MusicBrainz 未找到匹配专辑,进入第二级扫描");
                return null;
            }
            
            log.info("找到 {} 个候选专辑", searchResults.size());
            
            // 3. 提取文件夹时长序列（如有预提取则直接使用）
            List<Integer> durationsToUse = folderDurations;
            if (durationsToUse == null || durationsToUse.isEmpty()) {
                durationsToUse = extractFolderDurations(audioFile.getParentFile());
            } else {
                cacheFolderDurations(audioFile.getParentFile(), durationsToUse);
            }
            
            if (durationsToUse == null || durationsToUse.isEmpty()) {
                log.warn("无法提取文件夹时长序列,进入第二级扫描");
                return null;
            }
            
            log.info("文件夹时长序列: {}首", durationsToUse.size());
            
            // 4. 为每个候选专辑获取时长序列并匹配
            QuickScanResult bestMatch = findBestMatchByDuration(
                searchResults, durationsToUse, musicFilesInFolder
            );
            
            if (bestMatch != null && bestMatch.getSimilarity() >= QUICK_MATCH_THRESHOLD) {
                log.info("========================================");
                log.info("✓ 快速扫描成功! 相似度: {:.2f}%", bestMatch.getSimilarity() * 100);
                log.info("专辑: {} - {}", 
                    bestMatch.getMetadata().getAlbumArtist(), 
                    bestMatch.getMetadata().getAlbum());
                log.info("========================================");
                return bestMatch;
            } else {
                log.info("时长匹配度未达标({}%), 进入第二级扫描", 
                    bestMatch != null ? String.format("%.2f", bestMatch.getSimilarity() * 100) : "0");
                return null;
            }
            
        } catch (Exception e) {
            log.warn("快速扫描过程出错,进入第二级扫描: {}", e.getMessage());
            return null;
        }
    }

    private void cacheFolderDurations(File folder, List<Integer> durations) {
        if (durations == null || durations.isEmpty()) {
            return;
        }
        try {
            File albumRootDir = getAlbumRootDirectory(folder);
            String folderPath;
            try {
                folderPath = albumRootDir.getCanonicalPath();
            } catch (java.io.IOException e) {
                folderPath = albumRootDir.getAbsolutePath();
            }
            folderDurationCache.put(folderPath, durations);
        } catch (Exception e) {
            log.debug("缓存时长序列失败: {}", e.getMessage());
        }
    }
    
    /**
     * 收集专辑信息(从标签和文件夹名)
     */
    private AlbumSearchInfo collectAlbumInfo(File audioFile) {
        String artist = null;
        String album = null;
        
        // 优先从音频标签读取
        try {
            AudioFile af = AudioFileIO.read(audioFile);
            Tag tag = af.getTag();
            
            if (tag != null) {
                artist = tag.getFirst(FieldKey.ARTIST);
                album = tag.getFirst(FieldKey.ALBUM);
                
                // 如果没有 ARTIST,尝试 ALBUM_ARTIST
                if (artist == null || artist.trim().isEmpty()) {
                    artist = tag.getFirst(FieldKey.ALBUM_ARTIST);
                }
                
                log.debug("从标签读取 - Artist: {}, Album: {}", artist, album);
            }
        } catch (Exception e) {
            log.debug("读取标签失败: {}", e.getMessage());
        }
        
        // 如果标签中没有完整信息,尝试从文件夹名解析
        if ((artist == null || artist.trim().isEmpty()) || 
            (album == null || album.trim().isEmpty())) {
            
            String folderName = audioFile.getParentFile().getName();
            Matcher matcher = FOLDER_PATTERN.matcher(folderName);
            
            if (matcher.matches()) {
                String parsedArtist = matcher.group(1);
                String parsedAlbum = matcher.group(2);
                
                if (artist == null || artist.trim().isEmpty()) {
                    artist = parsedArtist != null ? parsedArtist.trim() : null;
                }
                if (album == null || album.trim().isEmpty()) {
                    album = parsedAlbum != null ? parsedAlbum.trim() : null;
                }
                
                log.debug("从文件夹名解析 - Artist: {}, Album: {}", artist, album);
            }
        }
        
        if (album != null && !album.trim().isEmpty()) {
            return new AlbumSearchInfo(artist, album);
        }
        
        return null;
    }
    
    /**
     * 在 MusicBrainz 搜索专辑
     */
    private List<MusicMetadata> searchByAlbumInfo(AlbumSearchInfo searchInfo) {
        try {
            // 使用 MusicBrainz 的搜索 API
            // 注意: 这需要在 MusicBrainzClient 中添加搜索方法
            return musicBrainzClient.searchAlbum(
                searchInfo.getAlbum(), 
                searchInfo.getArtist()
            );
        } catch (Exception e) {
            log.warn("搜索专辑失败: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 提取文件夹内所有音频文件的时长序列
     * 支持递归扫描子文件夹（如 CD1, CD2 等多CD专辑结构）
     *
     * 使用缓存机制，避免重复提取同一文件夹的时长序列
     *
     * 规则：获取监控目录的第一级子目录作为专辑根目录，递归扫描其下所有音频文件
     */
    private List<Integer> extractFolderDurations(File folder) {
        try {
            // 获取监控目录的第一级子目录作为专辑根目录
            File albumRootDir = getAlbumRootDirectory(folder);
            
            // 使用专辑根目录作为缓存键
            String folderPath;
            try {
                folderPath = albumRootDir.getCanonicalPath();
            } catch (java.io.IOException e) {
                folderPath = albumRootDir.getAbsolutePath();
            }
            
            // 检查缓存
            List<Integer> cachedDurations = folderDurationCache.get(folderPath);
            if (cachedDurations != null) {
                log.debug("使用专辑时长序列缓存: {} ({}首)", albumRootDir.getName(), cachedDurations.size());
                return cachedDurations;
            }
            
            // 递归扫描专辑根目录下的所有音频文件
            log.debug("递归扫描专辑根目录: {}", albumRootDir.getName());
            List<File> audioFiles = collectAudioFilesRecursively(albumRootDir);
            
            if (audioFiles == null || audioFiles.isEmpty()) {
                return null;
            }
            
            log.debug("在专辑文件夹 {} 中找到 {} 个音频文件",
                albumRootDir.getName(), audioFiles.size());
            
            // 提取时长序列
            List<Integer> durations = fingerprintService.extractDurationSequence(audioFiles);
            
            // 缓存结果
            if (durations != null && !durations.isEmpty()) {
                folderDurationCache.put(folderPath, durations);
                log.debug("已缓存专辑时长序列: {} ({}首)", albumRootDir.getName(), durations.size());
            }
            
            return durations;
            
        } catch (Exception e) {
            log.warn("提取文件夹时长序列失败: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 收集单层文件夹中的音频文件（不递归）
     */
    private List<File> collectAudioFilesSingleLevel(File folder) {
        List<File> audioFiles = new ArrayList<>();
        
        File[] files = folder.listFiles();
        if (files == null) {
            return audioFiles;
        }
        
        for (File file : files) {
            if (file.isFile() && isAudioFile(file)) {
                audioFiles.add(file);
            }
        }
        
        // 按文件名排序
        audioFiles.sort((f1, f2) -> f1.getName().compareTo(f2.getName()));
        
        return audioFiles;
    }
    
    /**
     * 递归收集文件夹及其子文件夹中的所有音频文件
     * 文件按完整路径排序，确保多CD专辑的正确顺序
     *
     * @param folder 要扫描的文件夹
     * @return 排序后的音频文件列表
     */
    private List<File> collectAudioFilesRecursively(File folder) {
        List<File> audioFiles = new ArrayList<>();
        collectAudioFilesRecursively(folder, audioFiles);
        
        // 按完整路径排序，这样可以保证：
        // 1. CD1 的文件在 CD2 前面
        // 2. 同一文件夹内的文件按文件名排序
        audioFiles.sort((f1, f2) -> f1.getPath().compareTo(f2.getPath()));
        
        return audioFiles;
    }
    
    /**
     * 递归收集音频文件的辅助方法
     */
    private void collectAudioFilesRecursively(File folder, List<File> result) {
        if (!folder.isDirectory()) {
            return;
        }
        
        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }
        
        for (File file : files) {
            if (file.isDirectory()) {
                // 递归进入子文件夹
                collectAudioFilesRecursively(file, result);
            } else if (isAudioFile(file)) {
                // 添加音频文件
                result.add(file);
            }
        }
    }
    
    /**
     * 判断文件是否为支持的音频格式
     */
    private boolean isAudioFile(File file) {
        String lowerName = file.getName().toLowerCase();
        return lowerName.endsWith(".mp3") ||
               lowerName.endsWith(".flac") ||
               lowerName.endsWith(".m4a") ||
               lowerName.endsWith(".wav");
    }
    
    /**
     * 获取专辑根目录
     * 规则：监控目录下的第一级子目录即为专辑根目录
     * 例如：监控目录/Artist - Album/Disc 1/01.flac -> 专辑根目录为 监控目录/Artist - Album
     */
    private File getAlbumRootDirectory(File folder) {
        try {
            String monitorDirPath = new File(config.getMonitorDirectory()).getCanonicalPath();
            File current = folder;
            
            // 向上查找，直到找到监控目录的直接子目录
            while (current != null) {
                File parent = current.getParentFile();
                if (parent != null) {
                    String parentPath = parent.getCanonicalPath();
                    if (parentPath.equals(monitorDirPath)) {
                        // current 是监控目录的直接子目录，即专辑根目录
                        return current;
                    }
                }
                current = parent;
            }
            
            // 如果找不到，返回原文件夹（保底）
            return folder;
            
        } catch (java.io.IOException e) {
            log.warn("获取专辑根目录失败: {}", e.getMessage());
            return folder;
        }
    }
    
    /**
     * 根据时长序列找到最佳匹配
     * 优化: 一旦找到高置信度匹配(>90%)，立即返回，避免不必要的API调用
     */
    private QuickScanResult findBestMatchByDuration(
            List<MusicMetadata> candidates,
            List<Integer> folderDurations,
            int musicFilesInFolder) {
        
        QuickScanResult bestResult = null;
        double bestSimilarity = 0.0;
        
        for (int i = 0; i < candidates.size(); i++) {
            MusicMetadata candidate = candidates.get(i);
            try {
                String releaseGroupId = candidate.getReleaseGroupId();
                if (releaseGroupId == null || releaseGroupId.isEmpty()) {
                    log.debug("候选专辑 {} 没有 Release Group ID，跳过", candidate.getAlbum());
                    continue;
                }
                
                // 获取专辑时长序列
                MusicBrainzClient.AlbumDurationResult durationResult =
                    musicBrainzClient.getAlbumDurationSequence(releaseGroupId);
                List<Integer> albumDurations = durationResult.getDurations();

                if (albumDurations.isEmpty()) {
                    log.debug("候选专辑 {} 没有时长数据，跳过", candidate.getAlbum());
                    continue;
                }
                
                // 计算相似度
                double similarity = durationSequenceService.calculateSimilarityDTW(
                    folderDurations, albumDurations
                );
                
                log.info("候选专辑: {} - {} (相似度: {:.2f}%)",
                    candidate.getAlbumArtist(), candidate.getAlbum(), similarity * 100);
                
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    bestResult = new QuickScanResult(candidate, similarity);
                    
                    // 优化：如果相似度已经超过阈值，立即返回，不再检查剩余候选
                    if (similarity >= QUICK_MATCH_THRESHOLD) {
                        log.info("找到高置信度匹配 ({:.2f}%)，停止检查剩余 {} 个候选专辑",
                            similarity * 100, candidates.size() - i - 1);
                        return bestResult;
                    }
                }
                
            } catch (Exception e) {
                log.warn("检查候选专辑失败: {} - {}",
                    candidate.getAlbum(), e.getMessage());
            }
        }
        
        return bestResult;
    }
    
    // ==================== 数据类 ====================
    
    /**
     * 专辑搜索信息
     */
    @Data
    private static class AlbumSearchInfo {
        private final String artist;
        private final String album;
        
        public AlbumSearchInfo(String artist, String album) {
            this.artist = artist;
            this.album = album;
        }
        
        public boolean isValid() {
            return album != null && !album.trim().isEmpty();
        }
    }
    
    /**
     * 快速扫描结果
     */
    @Data
    public static class QuickScanResult {
        private final MusicMetadata metadata;
        private final double similarity;
        
        public QuickScanResult(MusicMetadata metadata, double similarity) {
            this.metadata = metadata;
            this.similarity = similarity;
        }
        
        public boolean isHighConfidence() {
            return similarity >= QUICK_MATCH_THRESHOLD;
        }
    }
}
