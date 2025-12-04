package org.example.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 文件夹级别的专辑缓存管理器
 * 用于统一文件夹内所有音乐文件的专辑信息，避免同一专辑的歌曲分散到不同版本
 *
 * 核心功能：
 * 1. 文件夹专辑分析：收集文件夹内前N首歌的识别结果，投票选出最佳专辑版本
 * 2. 专辑统一应用：确定专辑后，文件夹内所有歌曲统一使用该专辑信息
 * 3. 两阶段处理：先收集识别，确定专辑后再批量写入文件
 */
@Slf4j
public class FolderAlbumCache {
    
    // 文件夹路径 -> 待处理文件列表
    private final Map<String, List<PendingFile>> folderPendingFiles = new ConcurrentHashMap<>();
    
    // 文件夹路径 -> 专辑信息缓存
    private final Map<String, CachedAlbumInfo> folderAlbumCache = new ConcurrentHashMap<>();
    
    // 文件夹路径 -> 识别样本收集器
    private final Map<String, AlbumSampleCollector> folderSampleCollectors = new ConcurrentHashMap<>();
    
    // 新增：文件夹路径 -> 时长序列
    private final Map<String, List<Integer>> folderDurationSequences = new ConcurrentHashMap<>();
    
    // 依赖服务
    private final DurationSequenceService durationSequenceService;
    private final MusicBrainzClient musicBrainzClient;
    private final AudioFingerprintService audioFingerprintService;
    
    // 配置参数
    private static final int SAMPLE_SIZE = 3; // 收集前3首歌作为样本（降低以适应小型专辑）
    private static final double CONFIDENCE_THRESHOLD = 0.6; // 60%以上的歌曲匹配同一专辑才认为可信
    private static final int LARGE_ALBUM_THRESHOLD = 10; // 10首以上认为是大型专辑
    private static final double TRACK_COUNT_TOLERANCE = 0.3; // 曲目数容差30%
    
    // 时长序列匹配开关
    private boolean useDurationSequenceMatching = true;
    
    /**
     * 构造函数
     */
    public FolderAlbumCache(DurationSequenceService durationSequenceService,
                           MusicBrainzClient musicBrainzClient,
                           AudioFingerprintService audioFingerprintService) {
        this.durationSequenceService = durationSequenceService;
        this.musicBrainzClient = musicBrainzClient;
        this.audioFingerprintService = audioFingerprintService;
    }
    
    /**
     * 设置是否使用时长序列匹配
     */
    public void setUseDurationSequenceMatching(boolean enabled) {
        this.useDurationSequenceMatching = enabled;
        log.info("时长序列匹配已{}", enabled ? "启用" : "禁用");
    }
    
    /**
     * 获取文件夹的专辑信息
     * @param folderPath 文件夹路径
     * @param musicFilesCount 文件夹内音乐文件总数
     * @return 如果已确定专辑信息则返回，否则返回null
     */
    public CachedAlbumInfo getFolderAlbum(String folderPath, int musicFilesCount) {
        CachedAlbumInfo cached = folderAlbumCache.get(folderPath);
        if (cached != null) {
            log.debug("使用文件夹缓存的专辑信息: {}", cached.getAlbumTitle());
            return cached;
        }
        
        // 检查是否正在收集样本
        AlbumSampleCollector collector = folderSampleCollectors.get(folderPath);
        if (collector != null && !collector.isComplete()) {
            log.debug("文件夹专辑信息收集中: {}/{}", collector.getSamples().size(), SAMPLE_SIZE);
        }
        
        return null;
    }
    
    /**
     * 直接设置文件夹的专辑信息（用于快速扫描成功时）
     * @param folderPath 文件夹路径
     * @param albumInfo 专辑信息
     */
    public void setFolderAlbum(String folderPath, CachedAlbumInfo albumInfo) {
        folderAlbumCache.put(folderPath, albumInfo);
        // 清理可能存在的样本收集器
        folderSampleCollectors.remove(folderPath);
        log.info("直接设置文件夹专辑缓存: {} - {}", albumInfo.getAlbumArtist(), albumInfo.getAlbumTitle());
    }
    
    /**
     * 添加识别样本
     * @param folderPath 文件夹路径
     * @param fileName 文件名
     * @param musicFilesCount 文件夹内音乐文件总数
     * @param albumInfo 识别到的专辑信息
     * @return 如果样本收集完成并确定了专辑，返回确定的专辑信息，否则返回null
     */
    public CachedAlbumInfo addSample(String folderPath, String fileName, int musicFilesCount, AlbumIdentificationInfo albumInfo) {
        // 如果已经确定了专辑，直接返回（不再收集样本）
        CachedAlbumInfo cached = folderAlbumCache.get(folderPath);
        if (cached != null) {
            log.debug("文件夹专辑已确定，跳过样本收集: {} - {}", fileName, cached.getAlbumTitle());
            return cached;
        }
        
        // 获取或创建样本收集器
        AlbumSampleCollector collector = folderSampleCollectors.computeIfAbsent(
            folderPath,
            k -> new AlbumSampleCollector(musicFilesCount)
        );
        
        // 检查样本收集器是否已标记为完成（双重检查）
        if (collector.isComplete() && folderAlbumCache.containsKey(folderPath)) {
            log.debug("样本收集已完成，使用缓存: {}", folderAlbumCache.get(folderPath).getAlbumTitle());
            return folderAlbumCache.get(folderPath);
        }
        
        // 添加样本
        collector.addSample(fileName, albumInfo);
        
        // 动态计算所需样本数：对于小型专辑，使用更少的样本
        int requiredSamples = calculateRequiredSamples(musicFilesCount);
        log.info("添加专辑识别样本: {} - {} (样本数: {}/{})",
            fileName, albumInfo.getAlbumTitle(), collector.getSamples().size(), requiredSamples);
        
        // 检查是否收集足够样本
        if (collector.getSamples().size() >= requiredSamples) {
            
            // 分析样本，确定最佳专辑
            CachedAlbumInfo bestAlbum = analyzeSamplesAndDetermineAlbum(folderPath, collector, musicFilesCount);
            
            if (bestAlbum != null) {
                // 缓存确定的专辑信息
                folderAlbumCache.put(folderPath, bestAlbum);
                // 标记收集器为完成
                collector.markComplete();
                // 移除样本收集器（节省内存）
                folderSampleCollectors.remove(folderPath);
                
                log.info("✓ 确定文件夹专辑: {} - {} ({}首曲目，置信度: {:.1f}%)",
                    bestAlbum.getAlbumArtist(), bestAlbum.getAlbumTitle(),
                    bestAlbum.getTrackCount(), bestAlbum.getConfidence() * 100);
                log.info("✓ 文件夹专辑已锁定，后续文件将统一使用此专辑信息");
                
                return bestAlbum;
            }
        }
        
        return null;
    }
    
    /**
     * 验证歌曲是否匹配缓存的专辑
     * @param folderPath 文件夹路径
     * @param fileName 文件名
     * @param albumInfo 识别到的专辑信息
     * @return true表示匹配，false表示不匹配（可能需要重新评估）
     */
    public boolean validateAgainstCache(String folderPath, String fileName, AlbumIdentificationInfo albumInfo) {
        CachedAlbumInfo cached = folderAlbumCache.get(folderPath);
        if (cached == null) {
            return true; // 没有缓存，不需要验证
        }
        
        // 检查是否匹配
        boolean matches = matchesAlbum(albumInfo, cached);
        
        if (!matches) {
            cached.incrementMismatchCount();
            log.warn("歌曲识别结果与文件夹专辑不匹配: {} - 期望: {}, 实际: {} (不匹配次数: {})", 
                fileName, cached.getAlbumTitle(), albumInfo.getAlbumTitle(), cached.getMismatchCount());
            
            // 如果不匹配次数过多，触发重新评估
            if (cached.getMismatchCount() >= 3) {
                log.warn("不匹配次数过多，清除文件夹专辑缓存，触发重新评估");
                folderAlbumCache.remove(folderPath);
                folderSampleCollectors.remove(folderPath);
                return false;
            }
        }
        
        return matches;
    }
    
    /**
     * 计算所需样本数
     * - 单曲（1-2首）: 需要1个样本
     * - 迷你专辑/EP（3-6首）: 需要2个样本
     * - 小型专辑（7-12首）: 需要3个样本
     * - 大型专辑（13首以上）: 需要3-5个样本
     */
    private int calculateRequiredSamples(int musicFilesCount) {
        if (musicFilesCount <= 2) {
            return 1; // 单曲
        } else if (musicFilesCount <= 6) {
            return 2; // EP
        } else if (musicFilesCount <= 12) {
            return 3; // 小型专辑
        } else {
            return Math.min(5, Math.max(3, musicFilesCount / 4)); // 大型专辑
        }
    }
    
    /**
     * 分析样本并确定最佳专辑（使用时长序列匹配）
     */
    private CachedAlbumInfo analyzeSamplesAndDetermineAlbum(String folderPath, AlbumSampleCollector collector, int musicFilesCount) {
        List<AlbumIdentificationInfo> samples = new ArrayList<>(collector.getSamples().values());
        
        if (samples.isEmpty()) {
            return null;
        }
        
        // 如果启用时长序列匹配，使用新方法
        if (useDurationSequenceMatching) {
            return analyzeSamplesWithDurationSequence(folderPath, samples, musicFilesCount);
        }
        
        // 否则使用原有的投票方法（保留以备兼容）
        return analyzeSamplesWithVoting(samples, musicFilesCount);
    }
    
    /**
     * 使用时长序列匹配分析样本
     */
    private CachedAlbumInfo analyzeSamplesWithDurationSequence(String folderPath,
                                                               List<AlbumIdentificationInfo> samples,
                                                               int musicFilesCount) {
        log.info("=== 开始时长序列匹配分析 ===");
        log.info("文件夹: {}, 样本数: {}, 音乐文件数: {}", folderPath, samples.size(), musicFilesCount);
        
        try {
            // 1. 提取文件夹时长序列（如果尚未提取）
            List<Integer> folderDurations = folderDurationSequences.get(folderPath);
            if (folderDurations == null) {
                File folder = new File(folderPath);
                File[] files = folder.listFiles((dir, name) ->
                    name.toLowerCase().endsWith(".mp3") ||
                    name.toLowerCase().endsWith(".flac") ||
                    name.toLowerCase().endsWith(".m4a") ||
                    name.toLowerCase().endsWith(".wav")
                );
                
                if (files != null && files.length > 0) {
                    List<File> audioFiles = Arrays.asList(files);
                    folderDurations = audioFingerprintService.extractDurationSequence(audioFiles);
                    folderDurationSequences.put(folderPath, folderDurations);
                    log.info("提取文件夹时长序列: {}首", folderDurations.size());
                }
            }
            
            if (folderDurations == null || folderDurations.isEmpty()) {
                log.warn("无法提取文件夹时长序列,回退到投票方法");
                return analyzeSamplesWithVoting(samples, musicFilesCount);
            }
            
            // 2. 收集所有候选专辑的 ReleaseGroupId
            Set<String> releaseGroupIds = samples.stream()
                .map(AlbumIdentificationInfo::getReleaseGroupId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
            
            if (releaseGroupIds.isEmpty()) {
                log.warn("样本中没有有效的 ReleaseGroupId,回退到投票方法");
                return analyzeSamplesWithVoting(samples, musicFilesCount);
            }
            
            // 3. 获取每个候选专辑的官方时长序列
            List<DurationSequenceService.AlbumDurationInfo> candidates = new ArrayList<>();
            for (String releaseGroupId : releaseGroupIds) {
                try {
                    List<Integer> albumDurations = musicBrainzClient.getAlbumDurationSequence(releaseGroupId);
                    if (!albumDurations.isEmpty()) {
                        // 从样本中找到对应的专辑信息
                        AlbumIdentificationInfo albumInfo = samples.stream()
                            .filter(s -> releaseGroupId.equals(s.getReleaseGroupId()))
                            .findFirst()
                            .orElse(null);
                        
                        if (albumInfo != null) {
                            candidates.add(new DurationSequenceService.AlbumDurationInfo(
                                releaseGroupId,
                                albumInfo.getAlbumTitle(),
                                albumInfo.getAlbumArtist(),
                                albumDurations
                            ));
                        }
                    }
                } catch (Exception e) {
                    log.warn("获取专辑{}的时长序列失败: {}", releaseGroupId, e.getMessage());
                }
            }
            
            if (candidates.isEmpty()) {
                log.warn("没有获取到任何候选专辑的时长序列,回退到投票方法");
                return analyzeSamplesWithVoting(samples, musicFilesCount);
            }
            
            // 4. 使用时长序列服务选择最佳匹配
            DurationSequenceService.AlbumMatchResult matchResult =
                durationSequenceService.selectBestMatch(folderDurations, candidates);
            
            if (matchResult != null) {
                DurationSequenceService.AlbumDurationInfo bestAlbum = matchResult.getAlbumInfo();
                double similarity = matchResult.getSimilarity();
                
                log.info("=== 时长序列匹配成功 ===");
                log.info("最佳专辑: {} - {}", bestAlbum.getAlbumArtist(), bestAlbum.getAlbumTitle());
                log.info("相似度: {:.2f}, 质量: {}", similarity, matchResult.getQuality());
                
                return new CachedAlbumInfo(
                    bestAlbum.getReleaseGroupId(),
                    bestAlbum.getAlbumTitle(),
                    bestAlbum.getAlbumArtist(),
                    bestAlbum.getDurations().size(),
                    "", // releaseDate 从样本中获取
                    similarity
                );
            } else {
                log.warn("时长序列匹配未找到合适专辑,回退到投票方法");
                return analyzeSamplesWithVoting(samples, musicFilesCount);
            }
            
        } catch (Exception e) {
            log.error("时长序列匹配过程出错,回退到投票方法", e);
            return analyzeSamplesWithVoting(samples, musicFilesCount);
        }
    }
    
    /**
     * 使用原有投票方法分析样本（保留用于兼容）
     */
    private CachedAlbumInfo analyzeSamplesWithVoting(List<AlbumIdentificationInfo> samples, int musicFilesCount) {
        log.info("使用投票方法分析样本");
        
        // 是否为大型专辑
        boolean isLargeAlbum = musicFilesCount >= LARGE_ALBUM_THRESHOLD;
        
        // 1. 统计专辑出现次数
        Map<String, AlbumVoteInfo> albumVotes = new HashMap<>();
        for (AlbumIdentificationInfo sample : samples) {
            String albumKey = getAlbumKey(sample);
            AlbumVoteInfo voteInfo = albumVotes.computeIfAbsent(albumKey, k -> new AlbumVoteInfo(sample));
            voteInfo.incrementVote();
        }
        
        // 2. 选择最佳专辑
        AlbumVoteInfo bestVote = null;
        int maxVotes = 0;
        
        for (AlbumVoteInfo voteInfo : albumVotes.values()) {
            int votes = voteInfo.getVotes();
            AlbumIdentificationInfo album = voteInfo.getAlbumInfo();
            
            log.info("专辑投票结果: {} - {} (投票数: {}, 曲目数: {})",
                album.getAlbumArtist(), album.getAlbumTitle(), votes, album.getTrackCount());
            
            // 对于大型专辑，优先考虑曲目数匹配度
            if (isLargeAlbum) {
                int trackCountDiff = Math.abs(album.getTrackCount() - musicFilesCount);
                double matchRate = 1.0 - (double) trackCountDiff / musicFilesCount;
                
                // 曲目数在容差范围内，且投票数最高
                if (matchRate >= (1.0 - TRACK_COUNT_TOLERANCE) && votes > maxVotes) {
                    bestVote = voteInfo;
                    maxVotes = votes;
                    log.info("  -> 曲目数匹配良好 (匹配率: {:.1f}%)", matchRate * 100);
                }
            } else {
                // 非大型专辑，主要看投票数
                if (votes > maxVotes) {
                    bestVote = voteInfo;
                    maxVotes = votes;
                }
            }
        }
        
        // 3. 验证置信度
        if (bestVote != null) {
            double confidence = (double) maxVotes / samples.size();
            log.info("最佳专辑置信度: {:.1f}% ({}票/{}样本)", confidence * 100, maxVotes, samples.size());
            
            if (confidence >= CONFIDENCE_THRESHOLD) {
                AlbumIdentificationInfo bestAlbum = bestVote.getAlbumInfo();
                return new CachedAlbumInfo(
                    bestAlbum.getReleaseGroupId(),
                    bestAlbum.getAlbumTitle(),
                    bestAlbum.getAlbumArtist(),
                    bestAlbum.getTrackCount(),
                    bestAlbum.getReleaseDate(),
                    confidence
                );
            } else {
                log.warn("置信度不足，不缓存专辑信息 (需要 >= {:.1f}%)", CONFIDENCE_THRESHOLD * 100);
            }
        }
        
        return null;
    }
    
    /**
     * 生成专辑唯一标识
     */
    private String getAlbumKey(AlbumIdentificationInfo album) {
        // 使用 ReleaseGroupId 作为唯一标识
        if (album.getReleaseGroupId() != null && !album.getReleaseGroupId().isEmpty()) {
            return album.getReleaseGroupId();
        }
        // 如果没有 ReleaseGroupId，使用专辑名+艺术家
        return album.getAlbumTitle() + "|" + album.getAlbumArtist();
    }
    
    /**
     * 检查识别结果是否匹配缓存的专辑
     */
    private boolean matchesAlbum(AlbumIdentificationInfo identified, CachedAlbumInfo cached) {
        // 优先使用 ReleaseGroupId 匹配
        if (identified.getReleaseGroupId() != null && cached.getReleaseGroupId() != null) {
            return identified.getReleaseGroupId().equals(cached.getReleaseGroupId());
        }
        
        // 回退到专辑名+艺术家匹配
        return identified.getAlbumTitle().equals(cached.getAlbumTitle()) &&
               identified.getAlbumArtist().equals(cached.getAlbumArtist());
    }
    
    /**
     * 清除文件夹缓存（用于测试或手动重置）
     */
    public void clearFolderCache(String folderPath) {
        folderAlbumCache.remove(folderPath);
        folderSampleCollectors.remove(folderPath);
        folderDurationSequences.remove(folderPath);
        log.info("已清除文件夹专辑缓存: {}", folderPath);
    }
    
    /**
     * 获取缓存统计信息
     */
    public CacheStatistics getStatistics() {
        return new CacheStatistics(
            folderAlbumCache.size(),
            folderSampleCollectors.size()
        );
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 样本收集器
     */
    private static class AlbumSampleCollector {
        private final int totalMusicFiles;
        private final Map<String, AlbumIdentificationInfo> samples = new LinkedHashMap<>();
        private boolean completed = false; // 标记是否已完成分析
        
        public AlbumSampleCollector(int totalMusicFiles) {
            this.totalMusicFiles = totalMusicFiles;
        }
        
        public void addSample(String fileName, AlbumIdentificationInfo albumInfo) {
            samples.put(fileName, albumInfo);
        }
        
        public Map<String, AlbumIdentificationInfo> getSamples() {
            return samples;
        }
        
        public boolean isComplete() {
            return completed || samples.size() >= SAMPLE_SIZE || samples.size() >= totalMusicFiles;
        }
        
        public void markComplete() {
            this.completed = true;
        }
    }
    
    /**
     * 专辑投票信息
     */
    private static class AlbumVoteInfo {
        private final AlbumIdentificationInfo albumInfo;
        private int votes = 0;
        
        public AlbumVoteInfo(AlbumIdentificationInfo albumInfo) {
            this.albumInfo = albumInfo;
        }
        
        public void incrementVote() {
            votes++;
        }
        
        public int getVotes() {
            return votes;
        }
        
        public AlbumIdentificationInfo getAlbumInfo() {
            return albumInfo;
        }
    }
    
    /**
     * 专辑识别信息（从识别结果提取）
     */
    @Data
    public static class AlbumIdentificationInfo {
        private String releaseGroupId;
        private String albumTitle;
        private String albumArtist;
        private int trackCount;
        private String releaseDate;
        
        public AlbumIdentificationInfo(String releaseGroupId, String albumTitle, String albumArtist, 
                                      int trackCount, String releaseDate) {
            this.releaseGroupId = releaseGroupId;
            this.albumTitle = albumTitle;
            this.albumArtist = albumArtist;
            this.trackCount = trackCount;
            this.releaseDate = releaseDate;
        }
    }
    
    /**
     * 缓存的专辑信息
     */
    @Data
    public static class CachedAlbumInfo {
        private final String releaseGroupId;
        private final String albumTitle;
        private final String albumArtist;
        private final int trackCount;
        private final String releaseDate;
        private final double confidence; // 置信度
        private int mismatchCount = 0; // 不匹配次数
        
        public CachedAlbumInfo(String releaseGroupId, String albumTitle, String albumArtist, 
                              int trackCount, String releaseDate, double confidence) {
            this.releaseGroupId = releaseGroupId;
            this.albumTitle = albumTitle;
            this.albumArtist = albumArtist;
            this.trackCount = trackCount;
            this.releaseDate = releaseDate;
            this.confidence = confidence;
        }
        
        public void incrementMismatchCount() {
            this.mismatchCount++;
        }
    }
    
    /**
     * 缓存统计信息
     */
    @Data
    public static class CacheStatistics {
        private final int cachedFolders; // 已缓存专辑的文件夹数
        private final int collectingFolders; // 正在收集样本的文件夹数
        
        public CacheStatistics(int cachedFolders, int collectingFolders) {
            this.cachedFolders = cachedFolders;
            this.collectingFolders = collectingFolders;
        }
        
        @Override
        public String toString() {
            return String.format("已缓存%d个文件夹, 收集中%d个文件夹", cachedFolders, collectingFolders);
        }
    }
    
    /**
     * 待处理文件信息
     */
    @Data
    public static class PendingFile {
        private final File audioFile;
        private final Object metadata; // MusicBrainzClient.MusicMetadata
        private final byte[] coverArtData;
        private final long addTime;
        
        public PendingFile(File audioFile, Object metadata, byte[] coverArtData) {
            this.audioFile = audioFile;
            this.metadata = metadata;
            this.coverArtData = coverArtData;
            this.addTime = System.currentTimeMillis();
        }
    }
    
    /**
     * 添加待处理文件到文件夹队列
     */
    public void addPendingFile(String folderPath, File audioFile, Object metadata, byte[] coverArtData) {
        List<PendingFile> pending = folderPendingFiles.computeIfAbsent(
            folderPath,
            k -> Collections.synchronizedList(new ArrayList<>())
        );
        pending.add(new PendingFile(audioFile, metadata, coverArtData));
        log.debug("添加待处理文件: {} (文件夹待处理数: {})", audioFile.getName(), pending.size());
    }
    
    /**
     * 获取文件夹的待处理文件列表
     */
    public List<PendingFile> getPendingFiles(String folderPath) {
        return folderPendingFiles.get(folderPath);
    }
    
    /**
     * 清除文件夹的待处理文件列表
     */
    public void clearPendingFiles(String folderPath) {
        folderPendingFiles.remove(folderPath);
        log.debug("已清除文件夹待处理列表: {}", folderPath);
    }
    
    /**
     * 检查文件夹是否有待处理文件
     */
    public boolean hasPendingFiles(String folderPath) {
        List<PendingFile> pending = folderPendingFiles.get(folderPath);
        return pending != null && !pending.isEmpty();
    }
}