package com.lux032.musicautotagger.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 时长序列匹配服务
 * 使用音频时长序列作为"指纹"来匹配专辑,比单纯的文件数量匹配更精确
 * 
 * 核心功能:
 * 1. 提取文件夹内音频文件的时长序列
 * 2. 获取MusicBrainz专辑的官方时长序列
 * 3. 使用序列相似度算法计算匹配度
 * 4. 根据匹配度选择最佳专辑版本
 */
@Slf4j
public class DurationSequenceService {
    
    // 时长容差(秒) - 考虑到不同版本可能有轻微差异
    private static final int DURATION_TOLERANCE = 5;

    // 原始 DTW 是自动匹配的硬门槛；名称和格式仅参与候选排序。
    private static final double MIN_DURATION_SIMILARITY = 0.75;
    // 最佳与「异专辑次佳」差距过小时视为歧义，不自动决定。
    // 注意：只跨 Release Group 比较，同一张专辑的不同发行版本不算歧义。
    private static final double MIN_BEST_MATCH_MARGIN = 0.05;
    
    /**
     * 使用动态时间规整(DTW)算法计算相似度
     * DTW对序列的时间扭曲更宽容,适合处理专辑中可能存在的额外曲目
     *
     * @param folderDurations 文件夹内文件的时长序列
     * @param albumDurations 专辑官方时长序列
     * @return 相似度分数 (0.0-1.0)
     */
    public double calculateSimilarityDTW(List<Integer> folderDurations, List<Integer> albumDurations) {
        // 默认使用加权DTW
        return calculateSimilarityWeightedDTW(folderDurations, albumDurations);
    }
    
    /**
     * 使用加权动态时间规整(Weighted DTW)算法计算相似度
     * 对专辑首尾曲目给予更高权重，因为：
     * 1. 首曲通常是最具代表性的开场曲，时长特征明显
     * 2. 尾曲同样具有特征性（可能是主打曲或特殊结尾）
     * 3. 中间曲目可能因版本不同而有变化（bonus track等）
     *
     * @param folderDurations 文件夹内文件的时长序列
     * @param albumDurations 专辑官方时长序列
     * @return 相似度分数 (0.0-1.0)
     */
    public double calculateSimilarityWeightedDTW(List<Integer> folderDurations, List<Integer> albumDurations) {
        if (folderDurations == null || albumDurations == null ||
            folderDurations.isEmpty() || albumDurations.isEmpty()) {
            return 0.0;
        }
        
        int m = folderDurations.size();
        int n = albumDurations.size();
        
        // DTW距离矩阵
        double[][] dtw = new double[m + 1][n + 1];
        
        // 初始化为无穷大
        for (int i = 0; i <= m; i++) {
            for (int j = 0; j <= n; j++) {
                dtw[i][j] = Double.MAX_VALUE;
            }
        }
        dtw[0][0] = 0;
        
        // 计算加权DTW距离
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                int duration1 = folderDurations.get(i - 1);
                int duration2 = albumDurations.get(j - 1);
                
                // 计算时长差异的代价
                double cost = Math.abs(duration1 - duration2);
                
                // 在容差范围内,代价为0
                if (cost <= DURATION_TOLERANCE) {
                    cost = 0;
                }
                
                // 获取位置权重 - 首尾曲目权重更高
                double weight1 = getPositionWeight(i - 1, m);
                double weight2 = getPositionWeight(j - 1, n);
                // 使用两个权重的平均值
                double combinedWeight = (weight1 + weight2) / 2.0;
                
                // 应用权重到代价
                double weightedCost = cost * combinedWeight;
                
                dtw[i][j] = weightedCost + Math.min(
                    Math.min(dtw[i - 1][j], dtw[i][j - 1]),
                    dtw[i - 1][j - 1]
                );
            }
        }
        
        double dtwDistance = dtw[m][n];
        
        // 归一化:除以序列长度和平均时长，同时考虑权重的影响
        double avgDuration = (folderDurations.stream().mapToInt(Integer::intValue).average().orElse(200.0) +
                             albumDurations.stream().mapToInt(Integer::intValue).average().orElse(200.0)) / 2.0;
        int maxLength = Math.max(m, n);
        // 计算平均权重用于归一化
        double avgWeight = calculateAverageWeight(maxLength);
        double normalizedDistance = dtwDistance / (maxLength * avgDuration * avgWeight);
        
        // 转换为相似度
        double similarity = 1.0 / (1.0 + normalizedDistance);
        
        // ==================== 首尾精确匹配加分机制 ====================
        // 当首尾曲目精确匹配时，给予额外加分奖励
        double bonusScore = 0.0;
        
        // 检查第一首曲目是否精确匹配
        int firstFolderDuration = folderDurations.get(0);
        int firstAlbumDuration = albumDurations.get(0);
        if (Math.abs(firstFolderDuration - firstAlbumDuration) <= DURATION_TOLERANCE) {
            bonusScore += FIRST_TRACK_MATCH_BONUS;
            log.debug("  ★ 首曲精确匹配 (+{}%加分)", String.format("%.0f", FIRST_TRACK_MATCH_BONUS * 100));
        }
        
        // 检查最后一首曲目是否精确匹配
        int lastFolderDuration = folderDurations.get(m - 1);
        int lastAlbumDuration = albumDurations.get(n - 1);
        if (Math.abs(lastFolderDuration - lastAlbumDuration) <= DURATION_TOLERANCE) {
            bonusScore += LAST_TRACK_MATCH_BONUS;
            log.debug("  ★ 尾曲精确匹配 (+{}%加分)", String.format("%.0f", LAST_TRACK_MATCH_BONUS * 100));
        }
        
        // 应用加分，但确保不超过1.0
        double finalSimilarity = Math.min(1.0, similarity + bonusScore);
        
        log.debug("加权DTW时长序列匹配 - DTW距离:{}, 归一化距离:{}, 基础相似度:{}, 加分:{}, 最终相似度:{}",
            String.format("%.2f", dtwDistance),
            String.format("%.4f", normalizedDistance),
            String.format("%.2f", similarity),
            String.format("%.2f", bonusScore),
            String.format("%.2f", finalSimilarity));
        
        return Math.max(0.0, finalSimilarity);
    }
    
    // ==================== 权重和加分常量 ====================
    
    // 首尾曲目权重（用于DTW代价计算）
    private static final double FIRST_LAST_WEIGHT = 1.5;
    // 次首尾曲目权重
    private static final double SECOND_WEIGHT = 1.25;
    // 标准权重
    private static final double NORMAL_WEIGHT = 1.0;
    
    // 首尾精确匹配加分（首曲匹配+5%，尾曲匹配+5%）
    private static final double FIRST_TRACK_MATCH_BONUS = 0.05;
    private static final double LAST_TRACK_MATCH_BONUS = 0.05;
    
    /**
     * 获取位置权重
     * 首尾曲目权重最高(1.5)，次首尾曲目次高(1.25)，其他曲目标准权重(1.0)
     *
     * @param position 曲目位置(0-based)
     * @param totalLength 序列总长度
     * @return 位置权重
     */
    private double getPositionWeight(int position, int totalLength) {
        if (totalLength <= 0) {
            return NORMAL_WEIGHT;
        }
        
        // 处理短专辑的情况
        if (totalLength <= 2) {
            // 只有1-2首曲目，都给高权重
            return FIRST_LAST_WEIGHT;
        }
        
        if (totalLength <= 4) {
            // 3-4首曲目，首尾高权重，其他标准
            if (position == 0 || position == totalLength - 1) {
                return FIRST_LAST_WEIGHT;
            }
            return NORMAL_WEIGHT;
        }
        
        // 5首及以上曲目的标准情况
        if (position == 0 || position == totalLength - 1) {
            // 第一首和最后一首：最高权重
            return FIRST_LAST_WEIGHT;
        } else if (position == 1 || position == totalLength - 2) {
            // 第二首和倒数第二首：次高权重
            return SECOND_WEIGHT;
        }
        
        return NORMAL_WEIGHT;
    }
    
    /**
     * 计算序列的平均权重，用于归一化
     *
     * @param length 序列长度
     * @return 平均权重
     */
    private double calculateAverageWeight(int length) {
        if (length <= 0) {
            return NORMAL_WEIGHT;
        }
        
        double totalWeight = 0.0;
        for (int i = 0; i < length; i++) {
            totalWeight += getPositionWeight(i, length);
        }
        return totalWeight / length;
    }
    
    /**
     * 评估专辑匹配质量
     * 
     * @param similarity 相似度分数
     * @return 匹配质量等级
     */
    public MatchQuality evaluateMatchQuality(double similarity) {
        return MatchQuality.of(similarity);
    }
    
    /**
     * 从多个候选专辑中选择最佳匹配
     * 
     * @param folderDurations 文件夹时长序列
     * @param candidates 候选专辑列表
     * @return 最佳匹配的专辑,如果没有符合阈值的则返回null
     */
    public AlbumMatchResult selectBestMatch(List<Integer> folderDurations, 
                                           List<AlbumDurationInfo> candidates) {
        if (folderDurations == null || folderDurations.isEmpty() || 
            candidates == null || candidates.isEmpty()) {
            return null;
        }
        
        AlbumMatchResult bestMatch = null;
        AlbumDurationInfo bestCandidate = null;
        double bestSimilarity = 0.0;

        log.info("开始时长序列匹配 - 文件夹时长序列: {}", formatDurationSequence(folderDurations));

        List<ScoredCandidate> scores = new ArrayList<>();
        for (AlbumDurationInfo candidate : candidates) {
            // 使用DTW算法计算相似度(对额外曲目更宽容)
            double similarity = calculateSimilarityDTW(folderDurations, candidate.getDurations());
            scores.add(new ScoredCandidate(candidate, similarity));

            log.info("候选专辑: {} - {} ({}首曲目)", 
                candidate.getAlbumTitle(), 
                candidate.getAlbumArtist(),
                candidate.getDurations().size());
            log.info("  时长序列: {}", formatDurationSequence(candidate.getDurations()));
            log.info("  相似度: {} ({})", String.format("%.2f", similarity), evaluateMatchQuality(similarity));

            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestCandidate = candidate;
                bestMatch = new AlbumMatchResult(candidate, similarity);
            }
        }

        double rivalSimilarity = bestRivalScore(scores, bestCandidate);
        double margin = Double.isInfinite(rivalSimilarity) ? Double.POSITIVE_INFINITY
            : bestSimilarity - rivalSimilarity;
        // 检查原始 DTW 硬门槛和候选区分度
        if (bestMatch != null && bestSimilarity >= MIN_DURATION_SIMILARITY &&
            margin >= MIN_BEST_MATCH_MARGIN) {
            log.info("✓ 选择最佳匹配: {} - {} (相似度: {})",
                bestMatch.getAlbumInfo().getAlbumTitle(),
                bestMatch.getAlbumInfo().getAlbumArtist(),
                String.format("%.2f", bestSimilarity));
            return bestMatch;
        } else {
            log.warn("未找到可信的匹配专辑 (最佳DTW: {}, 异专辑次佳DTW: {}, margin: {}, 硬门槛: {}, 最小margin: {})",
                String.format("%.2f", bestSimilarity),
                Double.isInfinite(rivalSimilarity) ? "N/A" : String.format("%.2f", rivalSimilarity),
                Double.isInfinite(margin) ? "N/A" : String.format("%.2f", margin),
                String.format("%.2f", MIN_DURATION_SIMILARITY),
                String.format("%.2f", MIN_BEST_MATCH_MARGIN));
            return null;
        }
    }

    /**
     * 找出「与最佳候选属于**不同** Release Group」的最高得分。
     *
     * <p>候选列表是按 <b>Release</b> 而非 Release Group 展开的，同一张专辑的日版/美版/再版
     * 时长序列完全一致、得分也完全一致。若直接拿全局次佳做 margin，热门专辑必然
     * margin=0 而被误判为「歧义」。真正的歧义只发生在**不同专辑**之间。</p>
     *
     * @return 异专辑候选的最高得分；不存在异专辑候选时返回 {@link Double#NEGATIVE_INFINITY}
     */
    private double bestRivalScore(List<ScoredCandidate> scores, AlbumDurationInfo best) {
        if (best == null) {
            return Double.NEGATIVE_INFINITY;
        }
        String bestGroupId = best.getReleaseGroupId();
        double rival = Double.NEGATIVE_INFINITY;
        for (ScoredCandidate scored : scores) {
            if (scored.candidate == best) {
                continue;
            }
            // releaseGroupId 为空时无法证明同属一张专辑，保守地当作竞争对手。
            boolean sameAlbum = bestGroupId != null
                && bestGroupId.equals(scored.candidate.getReleaseGroupId());
            if (!sameAlbum) {
                rival = Math.max(rival, scored.score);
            }
        }
        return rival;
    }

    /** 候选与其得分的配对。故意不用 Map：AlbumDurationInfo 是 @Data，值相等会误合并候选。 */
    private static final class ScoredCandidate {
        private final AlbumDurationInfo candidate;
        private final double score;

        ScoredCandidate(AlbumDurationInfo candidate, double score) {
            this.candidate = candidate;
            this.score = score;
        }
    }
    
    /**
     * 从多个候选专辑中选择最佳匹配（同时考虑文件夹名称相似度和媒体格式匹配）
     * 关键改进：
     * 1. 利用文件夹名称与专辑名称的相似度来辅助选择
     * 2. 从文件夹名称提取媒体格式（如 CD、Digital），优先选择匹配的 Release
     *
     * @param folderDurations 文件夹时长序列
     * @param candidates 候选专辑列表
     * @param folderName 文件夹名称
     * @return 最佳匹配的专辑,如果没有符合阈值的则返回null
     */
    public AlbumMatchResult selectBestMatchWithFolderName(List<Integer> folderDurations,
                                                          List<AlbumDurationInfo> candidates,
                                                          String folderName) {
        if (folderDurations == null || folderDurations.isEmpty() ||
            candidates == null || candidates.isEmpty()) {
            return null;
        }

        AlbumMatchResult bestMatch = null;
        AlbumDurationInfo bestCandidate = null;
        double bestCombinedScore = Double.NEGATIVE_INFINITY;
        List<ScoredCandidate> scores = new ArrayList<>();

        log.info("开始时长序列匹配（含文件夹名称匹配和媒体格式匹配）");
        log.info("文件夹名称: {}", folderName);
        log.info("文件夹时长序列: {}", formatDurationSequence(folderDurations));

        // 标准化文件夹名称用于比较
        String normalizedFolderName = normalizeName(folderName);
        
        // 关键改进：从文件夹名提取媒体格式
        MediaFormat preferredFormat = extractMediaFormat(folderName);
        if (preferredFormat != MediaFormat.UNKNOWN) {
            log.info("从文件夹名提取的媒体格式: {} (将优先选择此格式的 Release)", preferredFormat);
        }

        for (AlbumDurationInfo candidate : candidates) {
            // 1. 计算时长序列相似度（使用DTW算法）
            double durationSimilarity = calculateSimilarityDTW(folderDurations, candidate.getDurations());

            // 2. 计算文件夹名称与专辑名称的相似度
            String normalizedAlbumTitle = normalizeName(candidate.getAlbumTitle());
            double nameSimilarity = calculateNameSimilarity(normalizedFolderName, normalizedAlbumTitle);

            // 3. 计算综合得分
            // 权重：时长相似度 70%，名称相似度 30%
            double combinedScore = durationSimilarity * 0.7 + nameSimilarity * 0.3;

            // 名称高度匹配时的额外加分
            if (nameSimilarity >= 0.8) {
                combinedScore += 0.1; // 额外加10%
                log.info("  ★ 文件夹名称高度匹配，额外加分 (+0.1)");
            }
            
            // 4. 关键改进：媒体格式匹配加分
            // 如果文件夹名称指定了媒体格式，且候选专辑的格式匹配，给予额外加分
            if (preferredFormat != MediaFormat.UNKNOWN && candidate.getMediaFormat() != null) {
                MediaFormat candidateFormat = parseMediaFormat(candidate.getMediaFormat());
                if (candidateFormat == preferredFormat) {
                    combinedScore += 0.15; // 格式完全匹配，额外加15%
                    log.info("  ★ 媒体格式完全匹配 ({})，额外加分 (+0.15)", preferredFormat);
                } else if (candidateFormat != MediaFormat.UNKNOWN) {
                    // 格式不匹配，轻微扣分
                    combinedScore -= 0.05;
                    log.info("  ⚠ 媒体格式不匹配 (期望: {}, 实际: {})，扣分 (-0.05)",
                        preferredFormat, candidateFormat);
                }
            }

            log.info("候选专辑: {} - {} ({}首曲目, 格式: {})",
                candidate.getAlbumTitle(),
                candidate.getAlbumArtist(),
                candidate.getDurations().size(),
                candidate.getMediaFormat() != null ? candidate.getMediaFormat() : "未知");
            log.info("  时长序列: {}", formatDurationSequence(candidate.getDurations()));
            log.info("  时长相似度: {}, 名称相似度: {}, 综合得分: {}",
                String.format("%.2f", durationSimilarity),
                String.format("%.2f", nameSimilarity),
                String.format("%.2f", combinedScore));

            scores.add(new ScoredCandidate(candidate, combinedScore));

            if (combinedScore > bestCombinedScore) {
                bestCombinedScore = combinedScore;
                bestCandidate = candidate;
                // 同时保留原始 DTW 分，供本层和上层做音频硬门槛判断
                bestMatch = new AlbumMatchResult(candidate, combinedScore, durationSimilarity);
            }
        }

        // margin 只在**不同 Release Group** 之间计算：同一张专辑的多个发行版本得分必然接近，
        // 不应被当成「到底是哪张专辑」的歧义。
        double rivalScore = bestRivalScore(scores, bestCandidate);
        double margin = Double.isInfinite(rivalScore) ? Double.POSITIVE_INFINITY
            : bestCombinedScore - rivalScore;
        // 名称/格式只负责排序；是否可自动决定只看原始 DTW 与异专辑 margin。
        if (bestMatch != null && bestMatch.getDurationSimilarity() >= MIN_DURATION_SIMILARITY &&
            margin >= MIN_BEST_MATCH_MARGIN) {
            log.info("✓ 选择最佳匹配: {} - {} (综合得分: {}, 原始时长相似度: {}, 格式: {})",
                bestMatch.getAlbumInfo().getAlbumTitle(),
                bestMatch.getAlbumInfo().getAlbumArtist(),
                String.format("%.2f", bestCombinedScore),
                String.format("%.2f", bestMatch.getDurationSimilarity()),
                bestMatch.getAlbumInfo().getMediaFormat());
            return bestMatch;
        } else {
            log.warn("未找到可信的匹配专辑 (最佳综合分: {}, 原始DTW: {}, 异专辑margin: {}, DTW硬门槛: {}, 最小margin: {})",
                String.format("%.2f", bestCombinedScore),
                bestMatch != null ? String.format("%.2f", bestMatch.getDurationSimilarity()) : "N/A",
                Double.isInfinite(margin) ? "N/A" : String.format("%.2f", margin),
                String.format("%.2f", MIN_DURATION_SIMILARITY), String.format("%.2f", MIN_BEST_MATCH_MARGIN));
            return null;
        }
    }
    
    /**
     * 媒体格式枚举
     */
    public enum MediaFormat {
        CD,
        DIGITAL,
        VINYL,
        CASSETTE,
        SACD,
        UNKNOWN
    }
    
    /**
     * 从文件夹名提取媒体格式
     * 支持的格式标识：
     * - CD: "CD", "[CD", "CD FLAC", "CD ALAC"
     * - Digital: "Digital", "WEB", "iTunes"
     * - Vinyl: "Vinyl", "LP", "12\""
     * - SACD: "SACD", "DSD"
     */
    public MediaFormat extractMediaFormat(String folderName) {
        if (folderName == null || folderName.isEmpty()) {
            return MediaFormat.UNKNOWN;
        }
        
        String upper = folderName.toUpperCase();
        // 只接受独立 token 或常见括号标记，避免艺人名/专辑名中的普通子串触发格式加分。
        if (hasFormatToken(upper, "SACD") || hasFormatToken(upper, "DSD")) {
            return MediaFormat.SACD;
        }
        if (hasFormatToken(upper, "CD")) {
            return MediaFormat.CD;
        }
        if (hasFormatToken(upper, "DIGITAL") || hasFormatToken(upper, "WEB") ||
            hasFormatToken(upper, "ITUNES") || hasFormatToken(upper, "STREAMING")) {
            return MediaFormat.DIGITAL;
        }
        if (hasFormatToken(upper, "VINYL") || hasFormatToken(upper, "LP") ||
            TWELVE_INCH.matcher(upper).find()) {
            return MediaFormat.VINYL;
        }
        if (hasFormatToken(upper, "CASSETTE") || hasFormatToken(upper, "TAPE")) {
            return MediaFormat.CASSETTE;
        }

        return MediaFormat.UNKNOWN;
    }

    /**
     * 判断名称里是否含有独立的格式 token，避免普通单词子串（如 "CLIP" 里的 LP）误触发。
     *
     * <p>token 后允许紧跟磟号/序号（{@code CD1}、{@code CD01}、{@code [CD2]}），token 前允许
     * 紧跟数量前缀（{@code 2CD}、{@code 3LP}）——这些都是多碟发行最常见的写法，
     * 不能因为“严格 token 边界”而漏认。</p>
     */
    private boolean hasFormatToken(String value, String token) {
        return formatTokenPattern(token).matcher(value).find();
    }

    private static final java.util.Map<String, Pattern> FORMAT_TOKEN_PATTERNS = new ConcurrentHashMap<>();

    private static final Pattern TWELVE_INCH =
        Pattern.compile("(?:^|[\\s\\[\\(])12(?:\"|'')(?:$|[\\s\\]\\)])");

    private static Pattern formatTokenPattern(String token) {
        return FORMAT_TOKEN_PATTERNS.computeIfAbsent(token, t -> Pattern.compile(
            "(?:^|[\\s\\[\\(\\{_\\-])\\d{0,2}" + Pattern.quote(t) +
            "\\d{0,2}(?:$|[\\s\\]\\)\\}_\\-])"
        ));
    }
    
    /**
     * 解析 MusicBrainz 返回的媒体格式字符串
     */
    private MediaFormat parseMediaFormat(String format) {
        if (format == null || format.isEmpty()) {
            return MediaFormat.UNKNOWN;
        }
        
        String lower = format.toLowerCase();
        
        if (lower.contains("cd")) {
            return MediaFormat.CD;
        }
        if (lower.contains("digital") || lower.contains("download") || lower.contains("streaming")) {
            return MediaFormat.DIGITAL;
        }
        if (lower.contains("vinyl") || lower.contains("lp") || lower.contains("12\"")) {
            return MediaFormat.VINYL;
        }
        if (lower.contains("sacd") || lower.contains("dsd")) {
            return MediaFormat.SACD;
        }
        if (lower.contains("cassette") || lower.contains("tape")) {
            return MediaFormat.CASSETTE;
        }
        
        return MediaFormat.UNKNOWN;
    }

    /**
     * 标准化名称用于比较
     * 移除常见的干扰字符和标准化格式
     */
    private String normalizeName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }

        // 转小写
        String normalized = name.toLowerCase();

        // 移除常见的文件夹后缀（如 [VIZL-1777]）
        normalized = normalized.replaceAll("\\[.*?\\]", "");
        normalized = normalized.replaceAll("\\(.*?\\)", "");

        // 移除特殊字符，保留字母、数字和空格
        normalized = normalized.replaceAll("[^a-z0-9\\u4e00-\\u9fa5\\u3040-\\u309f\\u30a0-\\u30ff\\s]", " ");

        // 合并多个空格
        normalized = normalized.replaceAll("\\s+", " ").trim();

        return normalized;
    }

    /**
     * 计算两个名称的相似度
     * 使用 Jaccard 相似度 + 子串匹配
     */
    private double calculateNameSimilarity(String name1, String name2) {
        if (name1 == null || name2 == null || name1.isEmpty() || name2.isEmpty()) {
            return 0.0;
        }

        // 1. 检查是否包含关系（子串匹配）
        if (name1.contains(name2) || name2.contains(name1)) {
            // 计算包含比例
            double containRatio = (double) Math.min(name1.length(), name2.length()) /
                                  Math.max(name1.length(), name2.length());
            if (containRatio > 0.5) {
                return Math.max(0.8, containRatio); // 至少返回0.8
            }
        }

        // 2. 使用词汇级别的 Jaccard 相似度
        String[] words1 = name1.split("\\s+");
        String[] words2 = name2.split("\\s+");

        java.util.Set<String> set1 = new java.util.HashSet<>(java.util.Arrays.asList(words1));
        java.util.Set<String> set2 = new java.util.HashSet<>(java.util.Arrays.asList(words2));

        // 计算交集
        java.util.Set<String> intersection = new java.util.HashSet<>(set1);
        intersection.retainAll(set2);

        // 计算并集
        java.util.Set<String> union = new java.util.HashSet<>(set1);
        union.addAll(set2);

        if (union.isEmpty()) {
            return 0.0;
        }

        double jaccardSimilarity = (double) intersection.size() / union.size();

        // 3. 使用编辑距离作为补充
        double editDistanceSimilarity = 1.0 - (double) levenshteinDistance(name1, name2) /
                                        Math.max(name1.length(), name2.length());

        // 综合两种相似度
        return Math.max(jaccardSimilarity, editDistanceSimilarity * 0.8);
    }

    /**
     * 计算 Levenshtein 编辑距离
     */
    private int levenshteinDistance(String s1, String s2) {
        int m = s1.length();
        int n = s2.length();

        int[][] dp = new int[m + 1][n + 1];

        for (int i = 0; i <= m; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= n; j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = 1 + Math.min(dp[i - 1][j - 1],
                                   Math.min(dp[i - 1][j], dp[i][j - 1]));
                }
            }
        }

        return dp[m][n];
    }

    /**
     * 格式化时长序列用于日志输出
     */
    private String formatDurationSequence(List<Integer> durations) {
        if (durations == null || durations.isEmpty()) {
            return "[]";
        }
        
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(durations.size(), 10); i++) {
            if (i > 0) sb.append(", ");
            sb.append(formatDuration(durations.get(i)));
        }
        if (durations.size() > 10) {
            sb.append(", ...(共").append(durations.size()).append("首)");
        }
        sb.append("]");
        return sb.toString();
    }
    
    /**
     * 格式化时长为 mm:ss 格式
     */
    private String formatDuration(int seconds) {
        int minutes = seconds / 60;
        int secs = seconds % 60;
        return String.format("%d:%02d", minutes, secs);
    }
    
    // ==================== 数据类 ====================
    
    /**
     * 专辑时长信息
     */
    @Data
    public static class AlbumDurationInfo {
        private String releaseGroupId;
        private String releaseId;  // 具体的 Release ID
        private String albumTitle;
        private String albumArtist;
        private List<Integer> durations; // 曲目时长列表(秒)
        private String mediaFormat;  // 新增：媒体格式（如 "CD", "Digital Media" 等）
        
        public AlbumDurationInfo(String releaseGroupId, String releaseId, String albumTitle,
                                String albumArtist, List<Integer> durations) {
            this(releaseGroupId, releaseId, albumTitle, albumArtist, durations, null);
        }
        
        public AlbumDurationInfo(String releaseGroupId, String releaseId, String albumTitle,
                                String albumArtist, List<Integer> durations, String mediaFormat) {
            this.releaseGroupId = releaseGroupId;
            this.releaseId = releaseId;
            this.albumTitle = albumTitle;
            this.albumArtist = albumArtist;
            this.durations = durations != null ? new ArrayList<>(durations) : new ArrayList<>();
            this.mediaFormat = mediaFormat;
        }
    }
    
    /**
     * 专辑匹配结果
     */
    @Data
    public static class AlbumMatchResult {
        private AlbumDurationInfo albumInfo;
        /**
         * 综合排序分。
         * 注意：该分数可能包含文件夹名称相似度、媒体格式匹配等**非音频证据**的加分，
         * 因此**不得**用作「音频时长是否真的对上了」的硬门槛，只能用于候选间排序。
         */
        private double similarity;
        /**
         * 原始时长序列（DTW）相似度，不含名称/格式加分。
         * 需要「音频层面确实强匹配」的判断（如第一首文件立即锁定整个文件夹）必须用这个分数。
         */
        private double durationSimilarity;
        private MatchQuality quality; // 匹配质量
        
        public AlbumMatchResult(AlbumDurationInfo albumInfo, double similarity) {
            this(albumInfo, similarity, similarity);
        }

        public AlbumMatchResult(AlbumDurationInfo albumInfo, double similarity, double durationSimilarity) {
            this.albumInfo = albumInfo;
            this.similarity = similarity;
            this.durationSimilarity = durationSimilarity;
            // 匹配质量必须反映音频证据，不能被名称/格式加分污染。
            this.quality = MatchQuality.of(durationSimilarity);
        }
    }
    
    /**
     * 匹配质量等级
     */
    public enum MatchQuality {
        EXCELLENT("优秀"),
        GOOD("良好"),
        ACCEPTABLE("可接受"),
        POOR("较差");

        /** 单一真相：质量分级只在这里定义，避免多处硬编码阀值不一致。 */
        public static MatchQuality of(double durationSimilarity) {
            if (durationSimilarity >= 0.95) return EXCELLENT;
            if (durationSimilarity >= 0.85) return GOOD;
            if (durationSimilarity >= MIN_DURATION_SIMILARITY) return ACCEPTABLE;
            return POOR;
        }

        private final String description;
        
        MatchQuality(String description) {
            this.description = description;
        }
        
        @Override
        public String toString() {
            return description;
        }
    }
}
