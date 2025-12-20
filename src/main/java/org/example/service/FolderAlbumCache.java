package org.example.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.example.model.MusicMetadata;

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
     * 关键修复：实现优先级控制，防止低优先级的缓存覆盖高优先级的缓存
     * @param folderPath 文件夹路径
     * @param albumInfo 专辑信息
     */
    public void setFolderAlbum(String folderPath, CachedAlbumInfo albumInfo) {
        synchronized (folderAlbumCache) {
            CachedAlbumInfo existing = folderAlbumCache.get(folderPath);
            
            if (existing != null) {
                // 已有缓存，检查优先级
                CacheSource existingSource = existing.getSource();
                CacheSource newSource = albumInfo.getSource();
                
                if (newSource == null) {
                    newSource = CacheSource.UNKNOWN;
                }
                if (existingSource == null) {
                    existingSource = CacheSource.UNKNOWN;
                }
                
                // 只有新缓存优先级更高时才覆盖
                if (newSource.hasHigherPriorityThan(existingSource)) {
                    folderAlbumCache.put(folderPath, albumInfo);
                    folderSampleCollectors.remove(folderPath);
                    log.info("更新文件夹专辑缓存（优先级更高）: {} - {} (来源: {} -> {})",
                        albumInfo.getAlbumArtist(), albumInfo.getAlbumTitle(),
                        existingSource, newSource);
                } else if (existingSource.hasHigherPriorityThan(newSource)) {
                    // 现有缓存优先级更高，忽略新缓存
                    log.info("保留现有缓存（优先级更高）: {} - {} (来源: {}, 忽略: {})",
                        existing.getAlbumArtist(), existing.getAlbumTitle(),
                        existingSource, newSource);
                } else {
                    // 优先级相同，使用置信度判断
                    if (albumInfo.getConfidence() > existing.getConfidence()) {
                        folderAlbumCache.put(folderPath, albumInfo);
                        folderSampleCollectors.remove(folderPath);
                        log.info("更新文件夹专辑缓存（置信度更高）: {} - {} (置信度: {}% -> {}%)",
                            albumInfo.getAlbumArtist(), albumInfo.getAlbumTitle(),
                            String.format("%.2f", existing.getConfidence() * 100),
                            String.format("%.2f", albumInfo.getConfidence() * 100));
                    } else {
                        log.info("保留现有缓存（置信度更高或相同）: {} - {}",
                            existing.getAlbumArtist(), existing.getAlbumTitle());
                    }
                }
            } else {
                // 没有现有缓存，直接设置
                folderAlbumCache.put(folderPath, albumInfo);
                folderSampleCollectors.remove(folderPath);
                log.info("直接设置文件夹专辑缓存: {} - {} (来源: {})",
                    albumInfo.getAlbumArtist(), albumInfo.getAlbumTitle(), albumInfo.getSource());
            }
        }
    }
    
    /**
     * 强制设置文件夹的专辑信息（忽略优先级检查）
     * 仅用于特殊情况，如手动修正
     * @param folderPath 文件夹路径
     * @param albumInfo 专辑信息
     */
    public void forceSetFolderAlbum(String folderPath, CachedAlbumInfo albumInfo) {
        synchronized (folderAlbumCache) {
            folderAlbumCache.put(folderPath, albumInfo);
            folderSampleCollectors.remove(folderPath);
            log.info("强制设置文件夹专辑缓存: {} - {} (来源: {})",
                albumInfo.getAlbumArtist(), albumInfo.getAlbumTitle(), albumInfo.getSource());
        }
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

        // 获取当前待处理文件数量
        int pendingFileCount = getPendingFileCount(folderPath);

        // 关键修复：如果待处理文件数量少于所需样本数，调整所需样本数
        // 这种情况发生在专辑大部分文件已处理，只剩少数几个文件时
        int effectiveRequiredSamples = requiredSamples;
        if (pendingFileCount > 0 && pendingFileCount < requiredSamples) {
            effectiveRequiredSamples = pendingFileCount;
            log.info("待处理文件数({})少于所需样本数({})，调整为: {}",
                pendingFileCount, requiredSamples, effectiveRequiredSamples);
        }

        log.info("添加专辑识别样本: {} - {} (样本数: {}/{})",
            fileName, albumInfo.getAlbumTitle(), collector.getSamples().size(), effectiveRequiredSamples);

        // 检查是否收集足够样本
        if (collector.getSamples().size() >= effectiveRequiredSamples) {

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
     * 关键改进：使用 AcoustID 返回的所有候选专辑，而不仅仅是样本中已选定的 releaseGroupId
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

                // 递归收集所有音频文件（支持多CD专辑）
                List<File> audioFiles = collectAudioFilesRecursively(folder);

                if (!audioFiles.isEmpty()) {
                    folderDurations = audioFingerprintService.extractDurationSequence(audioFiles);
                    folderDurationSequences.put(folderPath, folderDurations);
                    log.info("提取专辑时长序列: {}首（递归扫描）", folderDurations.size());
                }
            }

            if (folderDurations == null || folderDurations.isEmpty()) {
                log.warn("无法提取文件夹时长序列,回退到投票方法");
                return analyzeSamplesWithVoting(samples, musicFilesCount);
            }

            // 2. 收集所有候选专辑的 ReleaseGroupId（关键改进：包含 AcoustID 返回的所有候选）
            // 使用 Map 存储 releaseGroupId -> title 的映射，便于后续获取专辑信息
            Map<String, String> allCandidateReleaseGroups = new LinkedHashMap<>();

            // 2.1 首先添加样本中已选定的 releaseGroupId
            for (AlbumIdentificationInfo sample : samples) {
                if (sample.getReleaseGroupId() != null) {
                    allCandidateReleaseGroups.put(sample.getReleaseGroupId(), sample.getAlbumTitle());
                }
            }

            // 2.2 关键改进：添加 AcoustID 返回的所有候选专辑
            for (AlbumIdentificationInfo sample : samples) {
                if (sample.getAllCandidateReleaseGroups() != null) {
                    for (CandidateReleaseGroup candidate : sample.getAllCandidateReleaseGroups()) {
                        if (candidate.getReleaseGroupId() != null &&
                            !allCandidateReleaseGroups.containsKey(candidate.getReleaseGroupId())) {
                            allCandidateReleaseGroups.put(candidate.getReleaseGroupId(), candidate.getTitle());
                        }
                    }
                }
            }

            log.info("收集到 {} 个候选专辑用于时长序列匹配（包含 AcoustID 返回的所有候选）",
                allCandidateReleaseGroups.size());

            if (allCandidateReleaseGroups.isEmpty()) {
                log.warn("没有有效的候选 ReleaseGroupId,回退到投票方法");
                return analyzeSamplesWithVoting(samples, musicFilesCount);
            }

            // 2.3 获取文件夹名称用于相似度匹配
            String folderName = new File(folderPath).getName();
            log.info("文件夹名称: {}", folderName);

            // 3. 获取每个候选专辑的官方时长序列
            // 关键改进：获取每个 Release Group 下所有 Release 的时长序列，而不是只获取第一个
            List<DurationSequenceService.AlbumDurationInfo> candidates = new ArrayList<>();
            for (Map.Entry<String, String> entry : allCandidateReleaseGroups.entrySet()) {
                String releaseGroupId = entry.getKey();
                String albumTitle = entry.getValue();

                try {
                    // 使用新方法获取所有 Release 的时长序列
                    List<MusicBrainzClient.AlbumDurationResult> allReleaseResults =
                        musicBrainzClient.getAllReleaseDurationSequences(releaseGroupId);
                    
                    if (allReleaseResults.isEmpty()) {
                        log.warn("Release Group {} 没有获取到任何有效的时长序列", releaseGroupId);
                        continue;
                    }
                    
                    // 尝试从样本中找到对应的专辑艺术家信息
                    String albumArtist = null;
                    for (AlbumIdentificationInfo sample : samples) {
                        if (releaseGroupId.equals(sample.getReleaseGroupId())) {
                            albumArtist = sample.getAlbumArtist();
                            if (sample.getAlbumTitle() != null && !sample.getAlbumTitle().isEmpty()) {
                                albumTitle = sample.getAlbumTitle(); // 使用更完整的标题
                            }
                            break;
                        }
                    }

                    // 规范化专辑艺术家（null、空、Unknown Artist 会被转换为 "Various Artists"）
                    albumArtist = MusicMetadata.normalizeAlbumArtist(albumArtist);
                    
                    // 为每个 Release 创建候选项
                    for (MusicBrainzClient.AlbumDurationResult releaseResult : allReleaseResults) {
                        String releaseTitle = releaseResult.getReleaseTitle() != null ?
                            releaseResult.getReleaseTitle() : albumTitle;
                        
                        candidates.add(new DurationSequenceService.AlbumDurationInfo(
                            releaseGroupId,
                            releaseResult.getReleaseId(),
                            releaseTitle,
                            albumArtist,
                            releaseResult.getDurations(),
                            releaseResult.getMediaFormat()  // 传递媒体格式
                        ));

                        log.info("候选版本: {} - {} ({}首曲目, Release ID: {}, 格式: {})",
                            albumArtist, releaseTitle, releaseResult.getDurations().size(),
                            releaseResult.getReleaseId(), releaseResult.getMediaFormat());
                    }
                } catch (Exception e) {
                    log.warn("获取专辑{}的时长序列失败: {}", releaseGroupId, e.getMessage());
                }
            }

            if (candidates.isEmpty()) {
                log.warn("没有获取到任何候选专辑的时长序列,回退到投票方法");
                return analyzeSamplesWithVoting(samples, musicFilesCount);
            }

            log.info("成功获取 {} 个候选专辑的时长序列", candidates.size());

            // 4. 使用时长序列服务选择最佳匹配（同时考虑文件夹名称相似度）
            DurationSequenceService.AlbumMatchResult matchResult =
                durationSequenceService.selectBestMatchWithFolderName(folderDurations, candidates, folderName);

            if (matchResult != null) {
                DurationSequenceService.AlbumDurationInfo bestAlbum = matchResult.getAlbumInfo();
                double similarity = matchResult.getSimilarity();

                log.info("=== 时长序列匹配成功 ===");
                log.info("最佳专辑: {} - {}", bestAlbum.getAlbumArtist(), bestAlbum.getAlbumTitle());
                log.info("相似度: {:.2f}, 质量: {}", similarity, matchResult.getQuality());

                return new CachedAlbumInfo(
                    bestAlbum.getReleaseGroupId(),
                    bestAlbum.getReleaseId(),  // 传递 Release ID
                    bestAlbum.getAlbumTitle(),
                    bestAlbum.getAlbumArtist(),
                    bestAlbum.getDurations().size(),
                    "", // releaseDate 从样本中获取
                    similarity,
                    CacheSource.DURATION_SEQUENCE  // 标记来源为时长序列匹配
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
     * 直接执行时长序列匹配确定专辑（不需要样本收集）
     * 用于在第一个文件处理时就确定正确的专辑
     *
     * @param folderPath 文件夹路径
     * @param candidateReleaseGroups AcoustID 返回的候选专辑列表
     * @param musicFilesCount 文件夹内音乐文件数量
     * @return 如果匹配成功返回专辑信息，否则返回 null
     */
    public CachedAlbumInfo determineAlbumWithDurationSequence(
            String folderPath,
            List<CandidateReleaseGroup> candidateReleaseGroups,
            int musicFilesCount) {
        
        if (!useDurationSequenceMatching) {
            log.debug("时长序列匹配已禁用");
            return null;
        }
        
        if (candidateReleaseGroups == null || candidateReleaseGroups.isEmpty()) {
            log.debug("没有候选专辑，跳过时长序列匹配");
            return null;
        }
        
        // 检查是否已有缓存
        CachedAlbumInfo cached = folderAlbumCache.get(folderPath);
        if (cached != null) {
            log.debug("已有缓存专辑: {}", cached.getAlbumTitle());
            return cached;
        }
        
        log.info("=== 第一个文件处理：立即执行时长序列匹配 ===");
        log.info("文件夹: {}, 候选专辑数: {}, 音乐文件数: {}", folderPath, candidateReleaseGroups.size(), musicFilesCount);
        
        try {
            // 1. 提取文件夹时长序列
            List<Integer> folderDurations = folderDurationSequences.get(folderPath);
            if (folderDurations == null) {
                File folder = new File(folderPath);
                List<File> audioFiles = collectAudioFilesRecursively(folder);
                
                if (!audioFiles.isEmpty()) {
                    folderDurations = audioFingerprintService.extractDurationSequence(audioFiles);
                    folderDurationSequences.put(folderPath, folderDurations);
                    log.info("提取专辑时长序列: {}首（递归扫描）", folderDurations.size());
                }
            }
            
            if (folderDurations == null || folderDurations.isEmpty()) {
                log.warn("无法提取文件夹时长序列");
                return null;
            }
            
            // 2. 获取文件夹名称用于相似度匹配
            String folderName = new File(folderPath).getName();
            log.info("文件夹名称: {}", folderName);
            
            // 3. 获取每个候选专辑的官方时长序列
            // 关键改进：获取每个 Release Group 下所有 Release 的时长序列，而不是只获取第一个
            List<DurationSequenceService.AlbumDurationInfo> candidates = new ArrayList<>();
            for (CandidateReleaseGroup candidate : candidateReleaseGroups) {
                String releaseGroupId = candidate.getReleaseGroupId();
                String albumTitle = candidate.getTitle();
                
                try {
                    // 使用新方法获取所有 Release 的时长序列
                    List<MusicBrainzClient.AlbumDurationResult> allReleaseResults =
                        musicBrainzClient.getAllReleaseDurationSequences(releaseGroupId);
                    
                    if (allReleaseResults.isEmpty()) {
                        log.warn("Release Group {} 没有获取到任何有效的时长序列", releaseGroupId);
                        continue;
                    }
                    
                    // 规范化专辑艺术家（null、空、Unknown Artist 会被转换为 "Various Artists"）
                    String normalizedArtist = MusicMetadata.normalizeAlbumArtist(null);
                    
                    // 为每个 Release 创建候选项
                    for (MusicBrainzClient.AlbumDurationResult releaseResult : allReleaseResults) {
                        String releaseTitle = releaseResult.getReleaseTitle() != null ?
                            releaseResult.getReleaseTitle() : albumTitle;
                        
                        candidates.add(new DurationSequenceService.AlbumDurationInfo(
                            releaseGroupId,
                            releaseResult.getReleaseId(),
                            releaseTitle,
                            normalizedArtist,
                            releaseResult.getDurations(),
                            releaseResult.getMediaFormat()  // 传递媒体格式
                        ));
                        
                        log.info("候选版本: {} ({}首曲目, Release ID: {}, 格式: {})",
                            releaseTitle, releaseResult.getDurations().size(),
                            releaseResult.getReleaseId(), releaseResult.getMediaFormat());
                    }
                } catch (Exception e) {
                    log.warn("获取专辑{}的时长序列失败: {}", releaseGroupId, e.getMessage());
                }
            }
            
            if (candidates.isEmpty()) {
                log.warn("没有获取到任何候选专辑的时长序列");
                return null;
            }
            
            log.info("成功获取 {} 个候选专辑的时长序列", candidates.size());
            
            // 4. 使用时长序列服务选择最佳匹配
            DurationSequenceService.AlbumMatchResult matchResult =
                durationSequenceService.selectBestMatchWithFolderName(folderDurations, candidates, folderName);
            
            if (matchResult != null) {
                DurationSequenceService.AlbumDurationInfo bestAlbum = matchResult.getAlbumInfo();
                double similarity = matchResult.getSimilarity();
                
                log.info("=== 时长序列匹配成功（第一个文件立即确定） ===");
                log.info("最佳专辑: {} (Release Group ID: {})", bestAlbum.getAlbumTitle(), bestAlbum.getReleaseGroupId());
                log.info("相似度: {:.2f}", similarity);
                
                CachedAlbumInfo albumInfo = new CachedAlbumInfo(
                    bestAlbum.getReleaseGroupId(),
                    bestAlbum.getReleaseId(),  // 传递 Release ID
                    bestAlbum.getAlbumTitle(),
                    bestAlbum.getAlbumArtist(),
                    bestAlbum.getDurations().size(),
                    "",
                    similarity,
                    CacheSource.DURATION_SEQUENCE  // 标记来源为时长序列匹配
                );
                
                // 关键修复：使用 setFolderAlbum 而不是直接 put，以尊重优先级
                setFolderAlbum(folderPath, albumInfo);
                log.info("✓ 文件夹专辑已锁定（第一个文件即确定）: {}", albumInfo.getAlbumTitle());
                
                return albumInfo;
            } else {
                log.warn("时长序列匹配未找到合适专辑");
                return null;
            }
            
        } catch (Exception e) {
            log.error("时长序列匹配过程出错", e);
            return null;
        }
    }
    
    /**
     * 递归收集文件夹及其子文件夹中的所有音频文件
     */
    private List<File> collectAudioFilesRecursively(File folder) {
        List<File> audioFiles = new ArrayList<>();
        collectAudioFilesRecursively(folder, audioFiles);
        
        // 按完整路径排序，确保多CD专辑顺序正确
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
     * 判断文件是否为音频文件
     */
    private boolean isAudioFile(File file) {
        String lowerName = file.getName().toLowerCase();
        return lowerName.endsWith(".mp3") ||
               lowerName.endsWith(".flac") ||
               lowerName.endsWith(".m4a") ||
               lowerName.endsWith(".wav");
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
                    null,  // 投票方法没有 releaseId
                    bestAlbum.getAlbumTitle(),
                    bestAlbum.getAlbumArtist(),
                    bestAlbum.getTrackCount(),
                    bestAlbum.getReleaseDate(),
                    confidence,
                    CacheSource.VOTING  // 标记来源为投票方法
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
        private List<CandidateReleaseGroup> allCandidateReleaseGroups; // 新增：存储 AcoustID 返回的所有候选专辑
        
        public AlbumIdentificationInfo(String releaseGroupId, String albumTitle, String albumArtist,
                                      int trackCount, String releaseDate) {
            this.releaseGroupId = releaseGroupId;
            this.albumTitle = albumTitle;
            this.albumArtist = albumArtist;
            this.trackCount = trackCount;
            this.releaseDate = releaseDate;
            this.allCandidateReleaseGroups = new ArrayList<>();
        }
        
        public AlbumIdentificationInfo(String releaseGroupId, String albumTitle, String albumArtist,
                                      int trackCount, String releaseDate,
                                      List<CandidateReleaseGroup> allCandidateReleaseGroups) {
            this.releaseGroupId = releaseGroupId;
            this.albumTitle = albumTitle;
            this.albumArtist = albumArtist;
            this.trackCount = trackCount;
            this.releaseDate = releaseDate;
            this.allCandidateReleaseGroups = allCandidateReleaseGroups != null ?
                allCandidateReleaseGroups : new ArrayList<>();
        }
    }
    
    /**
     * 候选专辑信息（来自 AcoustID）
     */
    @Data
    public static class CandidateReleaseGroup {
        private final String releaseGroupId;
        private final String title;
        
        public CandidateReleaseGroup(String releaseGroupId, String title) {
            this.releaseGroupId = releaseGroupId;
            this.title = title;
        }
    }
    
    /**
     * 缓存来源枚举
     * 用于区分不同方式产生的缓存，实现优先级控制
     */
    public enum CacheSource {
        QUICK_SCAN(100),           // 快速扫描（最高优先级）- 基于文件标签和文件夹名的精确匹配
        DURATION_SEQUENCE(50),     // 时长序列匹配（中等优先级）- 基于音频时长序列的匹配
        VOTING(30),                // 投票方法（较低优先级）- 基于多个样本的投票
        UNKNOWN(0);                // 未知来源（最低优先级）
        
        private final int priority;
        
        CacheSource(int priority) {
            this.priority = priority;
        }
        
        public int getPriority() {
            return priority;
        }
        
        public boolean hasHigherPriorityThan(CacheSource other) {
            return this.priority > other.priority;
        }
    }
    
    /**
     * 缓存的专辑信息
     */
    @Data
    public static class CachedAlbumInfo {
        private final String releaseGroupId;
        private final String releaseId;  // 新增：具体的 Release ID，用于确保版本一致性
        private final String albumTitle;
        private final String albumArtist;
        private final int trackCount;
        private final String releaseDate;
        private final double confidence; // 置信度
        private final CacheSource source; // 新增：缓存来源，用于优先级判断
        private int mismatchCount = 0; // 不匹配次数
        
        public CachedAlbumInfo(String releaseGroupId, String releaseId, String albumTitle, String albumArtist,
                              int trackCount, String releaseDate, double confidence) {
            this(releaseGroupId, releaseId, albumTitle, albumArtist, trackCount, releaseDate, confidence, CacheSource.UNKNOWN);
        }
        
        public CachedAlbumInfo(String releaseGroupId, String releaseId, String albumTitle, String albumArtist,
                              int trackCount, String releaseDate, double confidence, CacheSource source) {
            this.releaseGroupId = releaseGroupId;
            this.releaseId = releaseId;
            this.albumTitle = albumTitle;
            // 规范化专辑艺术家（null、空、Unknown Artist 会被转换为 "Various Artists"）
            this.albumArtist = MusicMetadata.normalizeAlbumArtist(albumArtist);
            this.trackCount = trackCount;
            this.releaseDate = releaseDate;
            this.confidence = confidence;
            this.source = source;
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

    /**
     * 检查文件是否已在待处理队列中
     * @param folderPath 文件夹路径
     * @param audioFile 音频文件
     * @return true表示文件已在队列中
     */
    public boolean isFileInPendingQueue(String folderPath, File audioFile) {
        List<PendingFile> pending = folderPendingFiles.get(folderPath);
        if (pending == null || pending.isEmpty()) {
            return false;
        }
        String targetPath = audioFile.getAbsolutePath();
        synchronized (pending) {
            for (PendingFile pf : pending) {
                if (pf.getAudioFile().getAbsolutePath().equals(targetPath)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 原子操作：检查文件是否在队列中，如果不在则添加
     * 关键修复：解决isFileInPendingQueue和addPendingFile之间的竞态条件
     * @param folderPath 文件夹路径
     * @param audioFile 音频文件
     * @param metadata 元数据
     * @param coverArtData 封面数据
     * @return true表示文件已添加到队列，false表示文件已存在于队列中
     */
    public boolean addPendingFileIfAbsent(String folderPath, File audioFile, Object metadata, byte[] coverArtData) {
        List<PendingFile> pending = folderPendingFiles.computeIfAbsent(
            folderPath,
            k -> Collections.synchronizedList(new ArrayList<>())
        );

        String targetPath = audioFile.getAbsolutePath();
        synchronized (pending) {
            // 在同步块内检查是否已存在
            for (PendingFile pf : pending) {
                if (pf.getAudioFile().getAbsolutePath().equals(targetPath)) {
                    log.debug("文件已在待处理队列中，跳过重复添加: {}", audioFile.getName());
                    return false;
                }
            }
            // 不存在则添加
            pending.add(new PendingFile(audioFile, metadata, coverArtData));
            log.debug("添加待处理文件: {} (文件夹待处理数: {})", audioFile.getName(), pending.size());
            return true;
        }
    }

    /**
     * 获取文件夹待处理文件数量
     * @param folderPath 文件夹路径
     * @return 待处理文件数量
     */
    public int getPendingFileCount(String folderPath) {
        List<PendingFile> pending = folderPendingFiles.get(folderPath);
        return pending != null ? pending.size() : 0;
    }

    /**
     * 获取所有有待处理文件的文件夹路径
     * @return 文件夹路径集合
     */
    public Set<String> getFoldersWithPendingFiles() {
        Set<String> folders = new HashSet<>();
        for (Map.Entry<String, List<PendingFile>> entry : folderPendingFiles.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                folders.add(entry.getKey());
            }
        }
        return folders;
    }
}