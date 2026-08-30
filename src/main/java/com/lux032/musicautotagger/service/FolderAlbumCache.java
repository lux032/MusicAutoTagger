package com.lux032.musicautotagger.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.util.AudioFileOrdering;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    // 新增：被判定为「专辑无法确定」的文件夹（大概率是 MusicBrainz 尚未收录的专辑 / 自制精选集）
    private final Set<String> unresolvedFolders = ConcurrentHashMap.newKeySet();

    /** 文件夹 -> 缓存专辑曲目数与实际文件数连续不一致的次数 */
    private final Map<String, Integer> cacheTrackCountMismatches = new ConcurrentHashMap<>();
    /**
     * 强证据缓存（时长序列）连续不一致达到该次数才丢弃，
     * 避免边下载边处理时文件数持续变化导致反复重新匹配。
     */
    private static final int MAX_CACHE_TRACK_COUNT_MISMATCHES = 3;
    /**
     * 阶段五 #17：弱证据（快速扫描 / 投票）的反悔阈值要低得多。
     * 弱证据本身就很可能是错的，再要求「连续 3 次不匹配」才重新评估，
     * 意味着一整张小专辑（3~6 首）可能在反悔生效前就已经全部写错了。
     */
    private static final int MAX_WEAK_CACHE_MISMATCHES = 1;
    /** 强证据缓存与识别结果冲突时的反悔阈值 */
    private static final int MAX_STRONG_CACHE_MISMATCHES = 3;

    /** 文件夹 -> 最后一次分析时看到的候选专辑快照（用于人工确认面板） */
    private final Map<String, List<FolderCandidate>> folderCandidates = new ConcurrentHashMap<>();
    
    // 依赖服务
    private final DurationSequenceService durationSequenceService;
    private final MusicBrainzClient musicBrainzClient;
    private final AudioFingerprintService audioFingerprintService;
    
    // 配置参数
    private static final int SAMPLE_SIZE = 3; // 收集前3首歌作为样本（降低以适应小型专辑）
    private static final double CONFIDENCE_THRESHOLD = 0.6; // 60%以上的歌曲匹配同一专辑才认为可信
    private static final int LARGE_ALBUM_THRESHOLD = 10; // 10首以上认为是大型专辑
    private static final double TRACK_COUNT_TOLERANCE = 0.3; // 曲目数容差30%

    // 候选专辑覆盖率阈值：
    // 如果没有任何一张候选专辑能覆盖超过这个比例的样本，
    // 说明这个文件夹里的歌分别来自很多张不同的专辑（典型的精选集），
    // 而 MusicBrainz 里并没有「这张精选集」本身。
    private static final double MIN_CANDIDATE_COVERAGE = 0.6;

    // 做出「MusicBrainz 未收录」这个负面判定所需的最少有效样本数。
    // 只有 2 个样本时，覆盖率只可能是 50% 或 100%，60% 的阈值实际等于要求全票一致，
    // 只要有一个样本的 AcoustID 候选缺了共同 Release Group 就会误判。
    private static final int MIN_SAMPLES_FOR_UNRESOLVED = 3;

    // 第一个文件就立即锁定整个文件夹的门槛（明显高于普通阈值 0.7）。
    // 因为立即锁定会跳过后续的样本收集与候选覆盖率检测，
    // 只有强匹配才允许走这条快速通道，否则一次误判会污染整个文件夹。
    //
    // 重要：必须对「原始 DTW 相似度」设门槛，而不能用综合得分。
    // 综合得分 = DTW×0.7 + 名称×0.3 + 名称加分 0.1 + 格式加分 0.15，
    // 例如 DTW 仅 0.55、但文件夹名与格式都匹配时综合得分可达 0.935，
    // 会让一个音频层面并不匹配的候选轻松越过门槛。
    private static final double FIRST_FILE_LOCK_MIN_DURATION_SIMILARITY = 0.85;
    
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
        return getFolderAlbum(folderPath, musicFilesCount, true);
    }

    public CachedAlbumInfo getFolderAlbum(String folderPath, int musicFilesCount, boolean musicFilesCountReliable) {
        CachedAlbumInfo cached = folderAlbumCache.get(folderPath);
        if (cached != null) {
            CacheSource cachedSource = cached.getSource() != null ? cached.getSource() : CacheSource.UNKNOWN;
            // 人工确认的结果不参与任何自动反悔
            if (musicFilesCountReliable && !cachedSource.isManual()
                && cached.getTrackCount() > 0 && musicFilesCount > 0) {
                int difference = Math.abs(cached.getTrackCount() - musicFilesCount);
                int allowed = Math.max(2, (int) Math.ceil(musicFilesCount * TRACK_COUNT_TOLERANCE));
                if (difference > allowed) {
                    // 只在连续多次不一致后才丢弃缓存。
                    // 边下载边处理时文件数会持续变化，单次不一致就删缓存会导致
                    // 同一文件夹反复重新匹配，每次都是一整轮 MusicBrainz 查询。
                    int mismatches = cacheTrackCountMismatches.merge(folderPath, 1, Integer::sum);
                    // 阶段五 #17：弱证据缓存（快速扫描 / 投票）反悔得更快
                    int maxMismatches = cachedSource.isWeakEvidence()
                        ? MAX_WEAK_CACHE_MISMATCHES : MAX_CACHE_TRACK_COUNT_MISMATCHES;
                    log.warn("缓存专辑曲目数与当前可靠文件数不一致（第{}/{}次，来源: {}）: {} (缓存{}首/当前{}首)",
                        mismatches, maxMismatches, cachedSource,
                        cached.getAlbumTitle(), cached.getTrackCount(), musicFilesCount);
                    if (mismatches >= maxMismatches) {
                        log.warn("连续 {} 次不一致，丢弃该文件夹的专辑缓存并重新匹配", mismatches);
                        folderAlbumCache.remove(folderPath, cached);
                        cacheTrackCountMismatches.remove(folderPath);
                        cached = null;
                    }
                } else {
                    cacheTrackCountMismatches.remove(folderPath);
                }
            }
            if (cached != null) {
                log.debug("使用文件夹缓存的专辑信息: {}", cached.getAlbumTitle());
                return cached;
            }
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
                    // 优先级相同，使用**统一置信度**判断（阶段六 #20）。
                    // 不能直接比 confidence：各链路量纲不同，快速扫描的 0.92
                    // 并不比时长序列的 0.80 更可信。
                    if (albumInfo.getUnifiedConfidence() > existing.getUnifiedConfidence()) {
                        folderAlbumCache.put(folderPath, albumInfo);
                        folderSampleCollectors.remove(folderPath);
                        log.info("更新文件夹专辑缓存（统一置信度更高）: {} - {} (统一置信度: {}% -> {}%)",
                            albumInfo.getAlbumArtist(), albumInfo.getAlbumTitle(),
                            String.format("%.2f", existing.getUnifiedConfidence() * 100),
                            String.format("%.2f", albumInfo.getUnifiedConfidence() * 100));
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
        // 旧调用：无法得知剩余未处理文件数，保守地按「整个文件夹都需要处理」处理
        return addSample(folderPath, fileName, musicFilesCount, albumInfo, musicFilesCount);
    }

    /**
     * 添加识别样本
     *
     * @param remainingUnprocessedCount 该文件夹下「尚未被处理过」的音乐文件总数。
     *        用于判断本次运行到底能收集到多少个样本。
     *        注意：**不能**用瞬时的 pending 队列长度代替——
     *        第一个文件刚入队时 pending 恒为 1，会把所需样本数压到 1，
     *        导致第一首文件立即以单样本进入普通分析，绕过快速通道的严格门槛与覆盖率检测。
     */
    public CachedAlbumInfo addSample(String folderPath, String fileName, int musicFilesCount,
                                     AlbumIdentificationInfo albumInfo, int remainingUnprocessedCount) {
        return addSample(folderPath, fileName, musicFilesCount, albumInfo, remainingUnprocessedCount, true);
    }

    public CachedAlbumInfo addSample(String folderPath, String fileName, int musicFilesCount,
                                     AlbumIdentificationInfo albumInfo, int remainingUnprocessedCount,
                                     boolean musicFilesCountReliable) {
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

        // 关键修复：以前这里用「当前 pending 队列长度」去压低所需样本数，
        // 但它无法区分「专辑刚开始处理，只有第一首入队」与「专辑真的只剩少量文件」。
        // 顺序处理时第一首文件天然满足 pendingFileCount(1) < requiredSamples，
        // 于是每张专辑都会在第一首就以单样本提前进入普通分析，
        // 绕过快速通道的原始 DTW 严格门槛，也使候选覆盖率检测（需 3 样本）永远无法生效。
        //
        // 改为使用「剩余未处理文件数」——这个值来自已处理记录，不会因队列瞬时状态而波动。
        // 注意：源文件处理后是被复制而非移走，仍留在监控目录，
        // 因此 musicFilesCount 不会随处理进度缩小，不能直接拿它当分母。
        int effectiveRequiredSamples = requiredSamples;
        if (remainingUnprocessedCount > 0 && remainingUnprocessedCount < requiredSamples) {
            effectiveRequiredSamples = Math.max(1, remainingUnprocessedCount);
            log.info("文件夹剩余未处理文件数({})少于所需样本数({})，调整为: {}",
                remainingUnprocessedCount, requiredSamples, effectiveRequiredSamples);
        }

        log.info("添加专辑识别样本: {} - {} (样本数: {}/{})",
            fileName, albumInfo.getAlbumTitle(), collector.getSamples().size(), effectiveRequiredSamples);

        // 检查是否收集足够样本
        if (collector.getSamples().size() >= effectiveRequiredSamples) {

            // 分析样本，确定最佳专辑
            CachedAlbumInfo bestAlbum = analyzeSamplesAndDetermineAlbum(
                folderPath, collector, musicFilesCount, musicFilesCountReliable);

            if (bestAlbum != null) {
                // 缓存确定的专辑信息
                folderAlbumCache.put(folderPath, bestAlbum);
                // 标记收集器为完成
                collector.markComplete();
                // 移除样本收集器（节省内存）
                folderSampleCollectors.remove(folderPath);

                log.info("✓ 确定文件夹专辑: {} - {} ({}首曲目，置信度: {}%)",
                    bestAlbum.getAlbumArtist(), bestAlbum.getAlbumTitle(),
                    bestAlbum.getTrackCount(), String.format("%.1f", bestAlbum.getConfidence() * 100));
                log.info("✓ 文件夹专辑已锁定，后续文件将统一使用此专辑信息");

                return bestAlbum;
            }
        }

        return null;
    }
    
    /**
     * 缓存反验结果（阶段五审查）
     *
     * 原先用 boolean 返回值语义不清：强证据的第一次冲突也返回 false，
     * 很容易被误读成「缓存已被丢弃」。现拆分为三种明确状态。
     */
    public enum CacheValidationResult {
        /** 识别结果与缓存一致（或无法判定） */
        VALID,
        /** 冲突已登记，但尚未达到反悔阈值，**缓存仍然在位** */
        CONFLICT_RETAINED,
        /** 冲突达到阈值，缓存已被丢弃，需要重新评估 */
        INVALIDATED
    }

    /**
     * 验证歌曲是否匹配缓存的专辑
     * @param folderPath 文件夹路径
     * @param fileName 文件名
     * @param albumInfo 识别到的专辑信息
     */
    public CacheValidationResult validateAgainstCache(String folderPath, String fileName,
                                                     AlbumIdentificationInfo albumInfo) {
        CachedAlbumInfo cached = folderAlbumCache.get(folderPath);
        if (cached == null) {
            return CacheValidationResult.VALID; // 没有缓存，不需要验证
        }
        
        // 检查是否匹配
        boolean matches = matchesAlbum(albumInfo, cached);
        
        if (!matches) {
            return registerCacheMismatch(folderPath, fileName, cached,
                albumInfo != null ? albumInfo.getAlbumTitle() : null);
        }

        // 匹配上了就清零，避免偶发的不匹配累积成误删
        cached.resetMismatchCount();
        return CacheValidationResult.VALID;
    }

    /**
     * 阶段五 #17：用「此文件的全部候选专辑」反验已锁定的缓存。
     *
     * 为什么不直接用 validateAgainstCache：
     * 那个方法拿的是「最终选定的专辑」，而一旦上游已经锁定了专辑，
     * 后续每个文件都会被强行套上锁定专辑（applyLockedAlbumInfo），
     * 结果永远「匹配」，反悔机制实际从未被触发过。
     *
     * 这里改用**指纹识别返回的原始候选集合**做判断：
     * 如果一首歌的所有候选专辑里**没有任何一个**是已锁定的专辑，
     * 说明这首歌很可能根本不属于这张专辑。
     *
     * @return 见 {@link CacheValidationResult}；只有 {@code INVALIDATED} 才代表缓存被丢弃
     */
    public CacheValidationResult validateAgainstCandidates(String folderPath, String fileName,
                                                          List<CandidateReleaseGroup> candidates) {
        CachedAlbumInfo cached = folderAlbumCache.get(folderPath);
        if (cached == null) {
            return CacheValidationResult.VALID;
        }
        String cachedRgId = cached.getReleaseGroupId();
        if (cachedRgId == null || cachedRgId.isEmpty()) {
            // 缓存本身没有 Release Group（如合成信息），无法做这个判断
            return CacheValidationResult.VALID;
        }
        if (candidates == null || candidates.isEmpty()) {
            // 这首歌压根没有候选（未收录 / 识别失败），不能拿它当反证
            return CacheValidationResult.VALID;
        }

        for (CandidateReleaseGroup candidate : candidates) {
            if (candidate != null && cachedRgId.equals(candidate.getReleaseGroupId())) {
                cached.resetMismatchCount();
                return CacheValidationResult.VALID;
            }
        }

        return registerCacheMismatch(folderPath, fileName, cached, "候选集中无此专辑");
    }

    /**
     * 登记一次「识别结果与缓存专辑冲突」，必要时丢弃缓存。
     * 反悔阈值按缓存来源分级（阶段五 #17）。
     */
    private CacheValidationResult registerCacheMismatch(String folderPath, String fileName,
                                                        CachedAlbumInfo cached, String actualTitle) {
        CacheSource source = cached.getSource() != null ? cached.getSource() : CacheSource.UNKNOWN;

        if (source.isManual()) {
            log.info("歌曲识别结果与人工确认的专辑不一致，以人工确认为准: {} ({} vs {})",
                fileName, cached.getAlbumTitle(), actualTitle);
            return CacheValidationResult.VALID;
        }

        cached.incrementMismatchCount();
        int threshold = source.isWeakEvidence() ? MAX_WEAK_CACHE_MISMATCHES : MAX_STRONG_CACHE_MISMATCHES;

        log.warn("歌曲识别结果与文件夹专辑不匹配: {} - 期望: {}, 实际: {} (不匹配 {}/{}，来源: {})",
            fileName, cached.getAlbumTitle(), actualTitle,
            cached.getMismatchCount(), threshold, source);

        if (cached.getMismatchCount() >= threshold) {
            log.warn("不匹配次数达到阈值（来源 {} 属于{}证据），清除文件夹专辑缓存，触发重新评估",
                source, source.isWeakEvidence() ? "弱" : "强");
            LogCollector.addLog("WARN", "专辑缓存与识别结果冲突，已丢弃缓存并重新评估: " + cached.getAlbumTitle());
            folderAlbumCache.remove(folderPath, cached);
            folderSampleCollectors.remove(folderPath);
            cacheTrackCountMismatches.remove(folderPath);
            return CacheValidationResult.INVALIDATED;
        }

        // 注意：到这里表示「冲突已登记、但缓存仍在位」，
        // 调用方不能把它当成缓存已被丢弃。
        return CacheValidationResult.CONFLICT_RETAINED;
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
    private CachedAlbumInfo analyzeSamplesAndDetermineAlbum(String folderPath, AlbumSampleCollector collector,
                                                              int musicFilesCount, boolean musicFilesCountReliable) {
        List<AlbumIdentificationInfo> samples = new ArrayList<>(collector.getSamples().values());
        
        if (samples.isEmpty()) {
            return null;
        }

        // 已经判定过是「专辑未确定」的文件夹，不重复检测、不重复请求 MusicBrainz
        if (isFolderUnresolved(folderPath)) {
            log.debug("文件夹已判定为「专辑未确定」，跳过专辑匹配: {}", folderPath);
            return null;
        }

        // 关键新增：候选专辑覆盖率检测
        // 先判断「这个文件夹到底是不是一张 MusicBrainz 里存在的专辑」，
        // 而不是直接去「从候选里挑一个最像的」。
        if (isLikelyNotInMusicBrainz(folderPath, samples)) {
            return null;
        }

        // 如果启用时长序列匹配，使用新方法
        if (useDurationSequenceMatching) {
            return analyzeSamplesWithDurationSequence(
                folderPath, samples, musicFilesCount, musicFilesCountReliable);
        }

        // 否则使用原有的投票方法（保留以备兼容）
        return analyzeSamplesWithVoting(samples, musicFilesCount, musicFilesCountReliable);
    }
    
    /**
     * 候选专辑覆盖率检测：判断这个文件夹是否「大概率是 MusicBrainz 尚未收录的专辑」。
     *
     * 原理：
     * 每个文件经指纹识别后，都会得到一组「这首歌出现在哪些专辑里」的候选。
     * - 如果这真是一张专辑：几乎每个文件的候选里都会包含同一张专辑 → 覆盖率接近 100%
     * - 如果这是一张精选集（而 MusicBrainz 没有收录）：每首歌分别来自不同的旧专辑，
     *   任何一张旧专辑都只能覆盖少数几首 → 覆盖率很低
     *
     * @return true 表示判定为「专辑无法确定」，不应该再去匹配任何旧专辑
     */
    private boolean isLikelyNotInMusicBrainz(String folderPath, List<AlbumIdentificationInfo> samples) {
        // 统计每个候选专辑被多少个样本支持
        Map<String, Integer> coverageCount = new LinkedHashMap<>();
        Map<String, String> titleOf = new LinkedHashMap<>();
        int samplesWithCandidates = 0;

        for (AlbumIdentificationInfo sample : samples) {
            Set<String> rgIdsOfThisSample = new HashSet<>();

            // 样本自己选定的专辑
            if (sample.getReleaseGroupId() != null && !sample.getReleaseGroupId().isEmpty()) {
                rgIdsOfThisSample.add(sample.getReleaseGroupId());
                titleOf.putIfAbsent(sample.getReleaseGroupId(), sample.getAlbumTitle());
            }
            // AcoustID 返回的全部候选
            if (sample.getAllCandidateReleaseGroups() != null) {
                for (CandidateReleaseGroup c : sample.getAllCandidateReleaseGroups()) {
                    if (c.getReleaseGroupId() != null && !c.getReleaseGroupId().isEmpty()) {
                        rgIdsOfThisSample.add(c.getReleaseGroupId());
                        titleOf.putIfAbsent(c.getReleaseGroupId(), c.getTitle());
                    }
                }
            }

            if (rgIdsOfThisSample.isEmpty()) {
                continue;
            }
            samplesWithCandidates++;
            for (String rgId : rgIdsOfThisSample) {
                coverageCount.merge(rgId, 1, Integer::sum);
            }
        }

        // 样本太少时不做负面判定。
        // 1 个样本覆盖率恒为 100%，毫无意义；
        // 2 个样本只能得到 50% 或 100%，60% 阈值实际等于要求全票一致，容易误伤 EP / 单曲。
        if (samplesWithCandidates < MIN_SAMPLES_FOR_UNRESOLVED) {
            log.debug("有效样本仅 {} 个（需要 {} 个），不做「MusicBrainz 未收录」判定",
                samplesWithCandidates, MIN_SAMPLES_FOR_UNRESOLVED);
            return false;
        }

        // 找出覆盖率最高的候选
        String bestRgId = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : coverageCount.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                bestRgId = e.getKey();
            }
        }

        double maxCoverage = (double) bestCount / samplesWithCandidates;

        // 把当前看到的候选快照存下来，供人工确认面板使用（阶段六 #18/#19）。
        // 不做任何额外的网络请求，候选全部来自已有的 AcoustID 结果。
        final int totalSamples = samplesWithCandidates;
        List<FolderCandidate> snapshot = new ArrayList<>();
        coverageCount.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .forEach(e -> snapshot.add(new FolderCandidate(
                e.getKey(), titleOf.get(e.getKey()), e.getValue(), totalSamples)));
        recordFolderCandidates(folderPath, snapshot);

        log.info("=== 候选专辑覆盖率检测 ===");
        log.info("有效样本数: {}, 不同候选专辑数: {}", samplesWithCandidates, coverageCount.size());
        log.info("最高覆盖率: {}/{} = {}%  ({})",
            bestCount, samplesWithCandidates,
            String.format("%.1f", maxCoverage * 100),
            bestRgId != null ? titleOf.get(bestRgId) : "无");

        if (maxCoverage < MIN_CANDIDATE_COVERAGE) {
            log.warn("========================================");
            log.warn("⚠ 判定：这个文件夹大概率是 MusicBrainz 尚未收录的专辑");
            log.warn("  理由：没有任何一张候选专辑能解释足够多的曲目（最高只覆盖 {}%，需要 {}%）",
                String.format("%.1f", maxCoverage * 100),
                String.format("%.0f", MIN_CANDIDATE_COVERAGE * 100));
            log.warn("  这通常意味着：这是一张精选集 / 自制合辑，曲目分别来自 {} 张不同的专辑",
                coverageCount.size());
            log.warn("  处理：不会强行归入任何一张旧专辑，改为按「专辑未确定」处理");
            log.warn("========================================");
            LogCollector.addLog("WARN", "检测到可能是 MusicBrainz 未收录的专辑（精选集），已避免错误归入旧专辑");
            markFolderUnresolved(folderPath);
            return true;
        }

        log.info("覆盖率达标，继续正常的专辑匹配流程");
        return false;
    }

    /**
     * 标记文件夹为「专辑未确定」
     */
    public void markFolderUnresolved(String folderPath) {
        if (folderPath != null && !folderPath.isEmpty()) {
            unresolvedFolders.add(folderPath);
        }
    }

    /**
     * 文件夹是否已被判定为「专辑未确定」
     */
    public boolean isFolderUnresolved(String folderPath) {
        return folderPath != null && unresolvedFolders.contains(folderPath);
    }

    /**
     * 清除「专辑未确定」标记。
     * 人工在待确认面板里选定了具体的 release 后必须调用，
     * 否则同目录后续新增的文件会继续被 unresolved 守卫拦住。
     */
    public void clearFolderUnresolved(String folderPath) {
        if (folderPath != null) {
            unresolvedFolders.remove(folderPath);
        }
    }

    /**
     * 记录文件夹级的候选专辑快照（阶段六 #18）。
     *
     * 进入人工确认队列时需要把「当时看到的候选」一并落盘，
     * 否则重启后面板上会只剩一个文件夹路径，人无从选起。
     */
    public void recordFolderCandidates(String folderPath, List<FolderCandidate> candidates) {
        if (folderPath == null || candidates == null || candidates.isEmpty()) {
            return;
        }
        folderCandidates.put(folderPath, new ArrayList<>(candidates));
    }

    /**
     * 获取文件夹级的候选专辑快照（可能为空列表）
     */
    public List<FolderCandidate> getFolderCandidates(String folderPath) {
        List<FolderCandidate> candidates = folderCandidates.get(folderPath);
        return candidates == null ? new ArrayList<>() : new ArrayList<>(candidates);
    }

    /**
     * 获取已缓存的文件夹时长序列（可能为 null）
     */
    public List<Integer> getFolderDurationSequence(String folderPath) {
        List<Integer> durations = folderDurationSequences.get(folderPath);
        return durations == null ? null : new ArrayList<>(durations);
    }

    /**
     * 使用时长序列匹配分析样本
     * 关键改进：使用 AcoustID 返回的所有候选专辑，而不仅仅是样本中已选定的 releaseGroupId
     */
    private CachedAlbumInfo analyzeSamplesWithDurationSequence(String folderPath,
                                                               List<AlbumIdentificationInfo> samples,
                                                               int musicFilesCount,
                                                               boolean musicFilesCountReliable) {
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
                return analyzeSamplesWithVoting(samples, musicFilesCount, musicFilesCountReliable);
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
                return analyzeSamplesWithVoting(samples, musicFilesCount, musicFilesCountReliable);
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

                    // 为每个 Release 创建候选项
                    for (MusicBrainzClient.AlbumDurationResult releaseResult : allReleaseResults) {
                        String releaseTitle = releaseResult.getReleaseTitle() != null ?
                            releaseResult.getReleaseTitle() : albumTitle;
                        
                        // 优先用样本里的艺术家，其次用 MusicBrainz Release 自身的 artist-credit；
                        // 都拿不到才规范化为 "Various Artists"
                        String candidateArtist = albumArtist;
                        if (candidateArtist == null || candidateArtist.isEmpty()) {
                            candidateArtist = releaseResult.getAlbumArtist();
                        }
                        candidateArtist = MusicMetadata.normalizeAlbumArtist(candidateArtist);
                        
                        candidates.add(new DurationSequenceService.AlbumDurationInfo(
                            releaseGroupId,
                            releaseResult.getReleaseId(),
                            releaseTitle,
                            candidateArtist,
                            releaseResult.getDurations(),
                            releaseResult.getMediaFormat(),
                            releaseResult.getReleaseType(),
                            releaseResult.isCompilation()
                        ));

                        log.info("候选版本: {} - {} ({}首曲目, Release ID: {}, 格式: {})",
                            candidateArtist, releaseTitle, releaseResult.getDurations().size(),
                            releaseResult.getReleaseId(), releaseResult.getMediaFormat());
                    }
                } catch (Exception e) {
                    log.warn("获取专辑{}的时长序列失败: {}", releaseGroupId, e.getMessage());
                }
            }

            if (candidates.isEmpty()) {
                log.warn("没有获取到任何候选专辑的时长序列,回退到投票方法");
                return analyzeSamplesWithVoting(samples, musicFilesCount, musicFilesCountReliable);
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
                log.info("相似度: {}, 质量: {}", String.format("%.2f", similarity), matchResult.getQuality());

                return new CachedAlbumInfo(
                    bestAlbum.getReleaseGroupId(),
                    bestAlbum.getReleaseId(),  // 传递 Release ID
                    bestAlbum.getAlbumTitle(),
                    bestAlbum.getAlbumArtist(),
                    bestAlbum.getDurations().size(),
                    "", // releaseDate 从样本中获取
                    bestAlbum.getReleaseType(),
                    bestAlbum.isCompilation(),
                    similarity,
                    CacheSource.DURATION_SEQUENCE  // 标记来源为时长序列匹配
                );
            } else {
                log.warn("时长序列匹配未找到合适专辑,回退到投票方法");
                return analyzeSamplesWithVoting(samples, musicFilesCount, musicFilesCountReliable);
            }

        } catch (Exception e) {
            log.error("时长序列匹配过程出错,回退到投票方法", e);
            return analyzeSamplesWithVoting(samples, musicFilesCount, musicFilesCountReliable);
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
        return determineAlbumWithDurationSequence(folderPath, candidateReleaseGroups, musicFilesCount, true);
    }

    /**
     * @param safeForFastLock 文件数输入是否干净到能允许「只看第一首歌就锁定整个文件夹」。
     *                        只要有任何存疑信号（包括 soft 级别）就应为 false，
     *                        回到多样本流程——只是慢一点，不会失去专辑信息。
     */
    public CachedAlbumInfo determineAlbumWithDurationSequence(
            String folderPath,
            List<CandidateReleaseGroup> candidateReleaseGroups,
            int musicFilesCount,
            boolean safeForFastLock) {

        if (!useDurationSequenceMatching) {
            log.debug("时长序列匹配已禁用");
            return null;
        }
        
        if (candidateReleaseGroups == null || candidateReleaseGroups.isEmpty()) {
            log.debug("没有候选专辑，跳过时长序列匹配");
            return null;
        }
        
        // 关键守卫：该文件夹已被判定为「专辑未确定」（如 MusicBrainz 未收录的精选集），
        // 后续新增的文件不得再走快速通道去匹配旧专辑，
        // 否则同一个文件夹会出现「一部分文件走 unresolved、一部分被归入旧专辑」的分裂结果。
        if (isFolderUnresolved(folderPath)) {
            log.info("文件夹已判定为「专辑未确定」，跳过第一文件快速匹配: {}", folderPath);
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

        // 先把候选快照记下来：即使快速通道失败，后续进人工确认队列时也能拿到可选项。
        // （覆盖率检测需要 3 个样本才运行，样本不足时候选快照只能靠这里填）
        if (folderCandidates.get(folderPath) == null) {
            List<FolderCandidate> snapshot = new ArrayList<>();
            for (CandidateReleaseGroup c : candidateReleaseGroups) {
                if (c != null && c.getReleaseGroupId() != null) {
                    snapshot.add(new FolderCandidate(c.getReleaseGroupId(), c.getTitle(), 1, 1));
                }
            }
            recordFolderCandidates(folderPath, snapshot);
        }
        
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
                    
                    // 为每个 Release 创建候选项
                    for (MusicBrainzClient.AlbumDurationResult releaseResult : allReleaseResults) {
                        String releaseTitle = releaseResult.getReleaseTitle() != null ?
                            releaseResult.getReleaseTitle() : albumTitle;
                        
                        // 关键修复：这里以前恒定传 null，导致单一艺术家专辑也被写成 "Various Artists"。
                        // 现在使用 MusicBrainz Release 的 artist-credit，只有真正多人/未知才退化为 VA。
                        String normalizedArtist =
                            MusicMetadata.normalizeAlbumArtist(releaseResult.getAlbumArtist());
                        
                        candidates.add(new DurationSequenceService.AlbumDurationInfo(
                            releaseGroupId,
                            releaseResult.getReleaseId(),
                            releaseTitle,
                            normalizedArtist,
                            releaseResult.getDurations(),
                            releaseResult.getMediaFormat(),
                            releaseResult.getReleaseType(),
                            releaseResult.isCompilation()
                        ));
                        
                        log.info("候选版本: {} - {} ({}首曲目, Release ID: {}, 格式: {})",
                            normalizedArtist, releaseTitle, releaseResult.getDurations().size(),
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

                // 关键门槛：立即锁定会跳过后续的样本收集与候选覆盖率检测，
                // 因此只允许「音频层面确实强匹配」才能走这条快速通道。
                //
                // 注意：这里必须用 durationSimilarity（原始 DTW 分），
                // 而不能用 similarity（综合得分）——后者含有文件夹名称与媒体格式的加分，
                // 一个 DTW 仅 0.55 的错误候选可能因名称/格式加分而拿到 0.93 的综合分。
                double durationSimilarity = matchResult.getDurationSimilarity();
                int candidateTrackCount = bestAlbum.getDurations() != null ? bestAlbum.getDurations().size() : 0;
                int trackCountDiff = Math.abs(candidateTrackCount - musicFilesCount);
                int allowedDiff = Math.max(1, (int) Math.ceil(musicFilesCount * 0.15));

                if (durationSimilarity < FIRST_FILE_LOCK_MIN_DURATION_SIMILARITY) {
                    log.info("第一个文件的原始时长相似度 {} 未达到立即锁定门槛 {}（综合得分 {}），不锁定整个文件夹",
                        String.format("%.2f", durationSimilarity),
                        String.format("%.2f", FIRST_FILE_LOCK_MIN_DURATION_SIMILARITY),
                        String.format("%.2f", similarity));
                    log.info("  将改为正常的样本收集流程，以便后续执行候选专辑覆盖率检测");
                    return null;
                }

                // 第一文件快速锁定依赖完整专辑曲目数；输入不可靠时必须降级到多样本流程。
                if (!safeForFastLock) {
                    log.info("文件夹曲目数输入不可靠，禁用第一文件立即锁定，改走多样本分析");
                    return null;
                }

                // 防御性检查：快速通道风险高，曲目数信息缺失时宁可不走
                if (candidateTrackCount <= 0 || musicFilesCount <= 0) {
                    log.info("曲目数信息不足（候选 {} 首 / 文件夹 {} 个），不允许第一首立即锁定",
                        candidateTrackCount, musicFilesCount);
                    return null;
                }

                if (trackCountDiff > allowedDiff) {
                    log.info("第一个文件匹配到的专辑曲目数({})与文件夹文件数({})差距过大，不立即锁定",
                        candidateTrackCount, musicFilesCount);
                    log.info("  将改为正常的样本收集流程，以便后续执行候选专辑覆盖率检测");
                    return null;
                }

                log.info("=== 时长序列匹配成功（第一个文件立即确定） ===");
                log.info("最佳专辑: {} (Release Group ID: {})", bestAlbum.getAlbumTitle(), bestAlbum.getReleaseGroupId());
                log.info("原始时长相似度: {}（门槛 {}）, 综合得分: {}",
                    String.format("%.2f", durationSimilarity),
                    String.format("%.2f", FIRST_FILE_LOCK_MIN_DURATION_SIMILARITY),
                    String.format("%.2f", similarity));

                CachedAlbumInfo albumInfo = new CachedAlbumInfo(
                    bestAlbum.getReleaseGroupId(),
                    bestAlbum.getReleaseId(),  // 传递 Release ID
                    bestAlbum.getAlbumTitle(),
                    bestAlbum.getAlbumArtist(),
                    bestAlbum.getDurations().size(),
                    "",
                    bestAlbum.getReleaseType(),
                    bestAlbum.isCompilation(),
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
        
        // 优先按 DISC/TRACK 标签排序，失败时使用数字感知的目录/文件名排序。
        AudioFileOrdering.sort(audioFiles);
        
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
        return analyzeSamplesWithVoting(samples, musicFilesCount, true);
    }

    private CachedAlbumInfo analyzeSamplesWithVoting(List<AlbumIdentificationInfo> samples, int musicFilesCount,
                                                      boolean musicFilesCountReliable) {
        log.info("使用投票方法分析样本");

        // 文件数不可靠时禁用大型专辑的曲目数加权，只按多样本一致性投票。
        boolean isLargeAlbum = musicFilesCountReliable && musicFilesCount >= LARGE_ALBUM_THRESHOLD;

        // 0. 关键：先过滤掉「根本没有专辑信息」的样本。
        //    开启 allowAlbumGuess=false 后，MusicBrainz 可能返回 albumTitle / releaseGroupId 均为空的元数据。
        //    如果不过滤，同一艺术家的多个空样本会聚合成 "null|Artist" 这个投票键，
        //    以 100% 置信度产出一个「标题为空的已确定专辑」，反而绕过 unresolved 链路。
        List<AlbumIdentificationInfo> validSamples = new ArrayList<>();
        for (AlbumIdentificationInfo sample : samples) {
            if (isUsableAlbumSample(sample)) {
                validSamples.add(sample);
            }
        }

        if (validSamples.isEmpty()) {
            log.warn("没有任何包含有效专辑信息的样本（共 {} 个样本），保持「专辑未确定」", samples.size());
            return null;
        }

        if (validSamples.size() < samples.size()) {
            log.info("已过滤掉 {} 个无专辑信息的样本，实际参与投票: {} 个",
                samples.size() - validSamples.size(), validSamples.size());
        }

        // 1. 统计专辑出现次数
        Map<String, AlbumVoteInfo> albumVotes = new HashMap<>();
        for (AlbumIdentificationInfo sample : validSamples) {
            String albumKey = getAlbumKey(sample);
            AlbumVoteInfo voteInfo = albumVotes.computeIfAbsent(albumKey, k -> new AlbumVoteInfo(sample));
            voteInfo.incrementVote();
        }

        // 后续置信度的分母使用「有效样本数」
        samples = validSamples;
        
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
                    log.info("  -> 曲目数匹配良好 (匹配率: {}%)", String.format("%.1f", matchRate * 100));
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
            log.info("最佳专辑置信度: {}% ({}票/{}样本)",
                String.format("%.1f", confidence * 100), maxVotes, samples.size());
            
            if (confidence >= CONFIDENCE_THRESHOLD) {
                AlbumIdentificationInfo bestAlbum = bestVote.getAlbumInfo();
                return new CachedAlbumInfo(
                    bestAlbum.getReleaseGroupId(),
                    null,  // 投票方法没有 releaseId
                    bestAlbum.getAlbumTitle(),
                    bestAlbum.getAlbumArtist(),
                    bestAlbum.getTrackCount(),
                    bestAlbum.getReleaseDate(),
                    bestAlbum.getReleaseType(),
                    bestAlbum.isCompilation(),
                    confidence,
                    CacheSource.VOTING  // 标记来源为投票方法
                );
            } else {
                log.warn("置信度不足，不缓存专辑信息 (需要 >= {}%)",
                    String.format("%.1f", CONFIDENCE_THRESHOLD * 100));
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
        // 注意：调用方必须先用 isUsableAlbumSample() 过滤，避免生成 "null|Artist" 这种无效键
        return album.getAlbumTitle() + "|" + album.getAlbumArtist();
    }

    /**
     * 样本是否包含可用于投票的专辑信息
     * 要求：至少有 ReleaseGroupId 或非空的专辑名
     */
    private boolean isUsableAlbumSample(AlbumIdentificationInfo sample) {
        if (sample == null) {
            return false;
        }
        boolean hasReleaseGroupId = sample.getReleaseGroupId() != null && !sample.getReleaseGroupId().isEmpty();
        boolean hasAlbumTitle = sample.getAlbumTitle() != null && !sample.getAlbumTitle().trim().isEmpty();
        return hasReleaseGroupId && hasAlbumTitle;
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
        return Objects.equals(identified.getAlbumTitle(), cached.getAlbumTitle()) &&
               Objects.equals(identified.getAlbumArtist(), cached.getAlbumArtist());
    }
    
    /**
     * 清除文件夹缓存（用于测试或手动重置）
     */
    public void clearFolderCache(String folderPath) {
        folderAlbumCache.remove(folderPath);
        folderSampleCollectors.remove(folderPath);
        folderDurationSequences.remove(folderPath);
        unresolvedFolders.remove(folderPath);
        cacheTrackCountMismatches.remove(folderPath);
        folderCandidates.remove(folderPath);
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

    /**
     * 缓存文件夹时长序列（用于统一使用预处理后的时长序列）
     */
    public void cacheFolderDurationSequence(String folderPath, List<Integer> durations) {
        if (folderPath == null || folderPath.isEmpty()) {
            return;
        }
        if (durations == null || durations.isEmpty()) {
            return;
        }
        folderDurationSequences.put(folderPath, durations);
        log.debug("已缓存文件夹时长序列: {} ({}首)", folderPath, durations.size());
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
        private String releaseType;
        private boolean compilation;
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
            this(releaseGroupId, albumTitle, albumArtist, trackCount, releaseDate, null, false,
                allCandidateReleaseGroups);
        }

        public AlbumIdentificationInfo(String releaseGroupId, String albumTitle, String albumArtist,
                                      int trackCount, String releaseDate, String releaseType,
                                      boolean compilation,
                                      List<CandidateReleaseGroup> allCandidateReleaseGroups) {
            this.releaseGroupId = releaseGroupId;
            this.albumTitle = albumTitle;
            this.albumArtist = albumArtist;
            this.trackCount = trackCount;
            this.releaseDate = releaseDate;
            this.releaseType = releaseType;
            this.compilation = compilation;
            this.allCandidateReleaseGroups = allCandidateReleaseGroups != null ?
                allCandidateReleaseGroups : new ArrayList<>();
        }
    }
    
    /**
     * 文件夹级候选专辑快照（用于人工确认队列）
     * supportCount = 有多少个样本的候选里包含这张专辑
     */
    @Data
    public static class FolderCandidate {
        private final String releaseGroupId;
        private final String title;
        private final int supportCount;
        private final int totalSamples;

        public FolderCandidate(String releaseGroupId, String title, int supportCount, int totalSamples) {
            this.releaseGroupId = releaseGroupId;
            this.title = title;
            this.supportCount = supportCount;
            this.totalSamples = totalSamples;
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
        // 阶段五 #16：优先级重排。
        //
        // 原顺序是 QUICK_SCAN(100) > DURATION_SEQUENCE(50) > VOTING(30)，
        // 但快速扫描只靠「标签 + 文件夹名」搜一次 MusicBrainz，是**最弱的证据**，
        // 却被设为最高优先级，导致基于音频本身的时长序列匹配结果**永远无法纠正它**——
        // 一旦上传者的标签是错的，整个文件夹就全错，而且改不回来。
        //
        // 现顺序：手动确认 > 时长序列 > 快速扫描 > 投票 > 未知。
        MANUAL_CONFIRMED(200),     // 人工确认（最高优先级）- 任何自动链路都不得覆盖
        DURATION_SEQUENCE(100),    // 时长序列匹配 - 基于音频本身，最强的自动证据
        QUICK_SCAN(50),            // 快速扫描 - 仅基于文件标签和文件夹名，最弱的自动证据
        VOTING(30),                // 投票方法 - 基于多个样本的投票
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

        /**
         * 是否为弱证据来源。
         * 弱证据一旦与后续文件的识别结果冲突，应当**更快地反悔**（见阶段五 #17）。
         */
        public boolean isWeakEvidence() {
            return this == QUICK_SCAN || this == VOTING || this == UNKNOWN;
        }

        /** 人工确认的结果不接受任何自动反悔 */
        public boolean isManual() {
            return this == MANUAL_CONFIRMED;
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
        private final String releaseType;
        private final boolean compilation;
        private final double confidence; // 置信度（该链路自己的原始分数，量纲不统一）
        private final CacheSource source; // 新增：缓存来源，用于优先级判断
        /**
         * 阶段六 #20：统一置信度。
         * confidence 是各链路自己的标尺（快速扫描 0.90、DTW 0.70、投票 0.60），
         * 直接互相比较是没有意义的。unifiedConfidence 把它们映射到同一把尺子上。
         */
        private final double unifiedConfidence;
        private int mismatchCount = 0; // 不匹配次数
        
        public CachedAlbumInfo(String releaseGroupId, String releaseId, String albumTitle, String albumArtist,
                              int trackCount, String releaseDate, double confidence) {
            this(releaseGroupId, releaseId, albumTitle, albumArtist, trackCount, releaseDate,
                null, false, confidence, CacheSource.UNKNOWN);
        }
        
        public CachedAlbumInfo(String releaseGroupId, String releaseId, String albumTitle, String albumArtist,
                              int trackCount, String releaseDate, double confidence, CacheSource source) {
            this(releaseGroupId, releaseId, albumTitle, albumArtist, trackCount, releaseDate,
                null, false, confidence, source);
        }

        public CachedAlbumInfo(String releaseGroupId, String releaseId, String albumTitle, String albumArtist,
                              int trackCount, String releaseDate, String releaseType, boolean compilation,
                              double confidence, CacheSource source) {
            this.releaseGroupId = releaseGroupId;
            this.releaseId = releaseId;
            this.albumTitle = albumTitle;
            // 缓存只做空值安全兜底，不改写已经解析好的多艺术家名称。
            this.albumArtist = MusicMetadata.normalizeAlbumArtist(albumArtist);
            this.trackCount = trackCount;
            this.releaseDate = releaseDate;
            this.releaseType = releaseType;
            this.compilation = compilation;
            this.confidence = confidence;
            this.source = source;
            this.unifiedConfidence = com.lux032.musicautotagger.util.ConfidenceModel.unify(source, confidence);
        }
        
        public void incrementMismatchCount() {
            this.mismatchCount++;
        }

        public void resetMismatchCount() {
            this.mismatchCount = 0;
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
        private final File processingFile;
        private final java.nio.file.Path processingTempDir;
        private final Object metadata; // MusicBrainzClient.MusicMetadata
        private final byte[] coverArtData;
        private final long addTime;
        
        public PendingFile(File audioFile, File processingFile, java.nio.file.Path processingTempDir,
                           Object metadata, byte[] coverArtData) {
            this.audioFile = audioFile;
            this.processingFile = processingFile;
            this.processingTempDir = processingTempDir;
            this.metadata = metadata;
            this.coverArtData = coverArtData;
            this.addTime = System.currentTimeMillis();
        }
    }
    
    /**
     * 添加待处理文件到文件夹队列
     */
    public void addPendingFile(String folderPath, File audioFile, File processingFile, java.nio.file.Path processingTempDir,
                               Object metadata, byte[] coverArtData) {
        List<PendingFile> pending = folderPendingFiles.computeIfAbsent(
            folderPath,
            k -> Collections.synchronizedList(new ArrayList<>())
        );
        pending.add(new PendingFile(audioFile, processingFile, processingTempDir, metadata, coverArtData));
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
    public boolean addPendingFileIfAbsent(String folderPath, File audioFile, File processingFile,
                                          java.nio.file.Path processingTempDir, Object metadata, byte[] coverArtData) {
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
            pending.add(new PendingFile(audioFile, processingFile, processingTempDir, metadata, coverArtData));
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

