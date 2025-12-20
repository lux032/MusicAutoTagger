package org.example.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

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
    private static final int DURATION_TOLERANCE = 3;
    
    // 最小匹配阈值 - 相似度低于此值认为不匹配
    private static final double MIN_MATCH_THRESHOLD = 0.7;
    
    /**
     * 计算两个时长序列的相似度
     * 使用改进的编辑距离算法,考虑时长容差
     * 
     * @param folderDurations 文件夹内文件的时长序列(秒)
     * @param albumDurations 专辑官方时长序列(秒)
     * @return 相似度分数 (0.0-1.0),1.0表示完全匹配
     */
    public double calculateSimilarity(List<Integer> folderDurations, List<Integer> albumDurations) {
        if (folderDurations == null || albumDurations == null || 
            folderDurations.isEmpty() || albumDurations.isEmpty()) {
            return 0.0;
        }
        
        int m = folderDurations.size();
        int n = albumDurations.size();
        
        // 使用动态规划计算编辑距离
        int[][] dp = new int[m + 1][n + 1];
        
        // 初始化边界
        for (int i = 0; i <= m; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= n; j++) {
            dp[0][j] = j;
        }
        
        // 填充DP表
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                int duration1 = folderDurations.get(i - 1);
                int duration2 = albumDurations.get(j - 1);
                
                // 如果时长在容差范围内,认为匹配
                if (Math.abs(duration1 - duration2) <= DURATION_TOLERANCE) {
                    dp[i][j] = dp[i - 1][j - 1]; // 匹配,不增加编辑距离
                } else {
                    // 不匹配,取三种操作的最小值
                    dp[i][j] = 1 + Math.min(
                        Math.min(dp[i - 1][j], dp[i][j - 1]),  // 插入或删除
                        dp[i - 1][j - 1]  // 替换
                    );
                }
            }
        }
        
        int editDistance = dp[m][n];
        int maxLength = Math.max(m, n);
        
        // 转换为相似度分数 (0.0-1.0)
        double similarity = 1.0 - (double) editDistance / maxLength;
        
        log.debug("时长序列匹配 - 文件夹:{}首, 专辑:{}首, 编辑距离:{}, 相似度:{:.2f}", 
            m, n, editDistance, similarity);
        
        return Math.max(0.0, similarity);
    }
    
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
            log.debug("  ★ 首曲精确匹配 (+{:.0f}%加分)", FIRST_TRACK_MATCH_BONUS * 100);
        }
        
        // 检查最后一首曲目是否精确匹配
        int lastFolderDuration = folderDurations.get(m - 1);
        int lastAlbumDuration = albumDurations.get(n - 1);
        if (Math.abs(lastFolderDuration - lastAlbumDuration) <= DURATION_TOLERANCE) {
            bonusScore += LAST_TRACK_MATCH_BONUS;
            log.debug("  ★ 尾曲精确匹配 (+{:.0f}%加分)", LAST_TRACK_MATCH_BONUS * 100);
        }
        
        // 应用加分，但确保不超过1.0
        double finalSimilarity = Math.min(1.0, similarity + bonusScore);
        
        log.debug("加权DTW时长序列匹配 - DTW距离:{:.2f}, 归一化距离:{:.4f}, 基础相似度:{:.2f}, 加分:{:.2f}, 最终相似度:{:.2f}",
            dtwDistance, normalizedDistance, similarity, bonusScore, finalSimilarity);
        
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
        if (similarity >= 0.95) {
            return MatchQuality.EXCELLENT;
        } else if (similarity >= 0.85) {
            return MatchQuality.GOOD;
        } else if (similarity >= MIN_MATCH_THRESHOLD) {
            return MatchQuality.ACCEPTABLE;
        } else {
            return MatchQuality.POOR;
        }
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
        double bestSimilarity = 0.0;
        
        log.info("开始时长序列匹配 - 文件夹时长序列: {}", formatDurationSequence(folderDurations));
        
        for (AlbumDurationInfo candidate : candidates) {
            // 使用DTW算法计算相似度(对额外曲目更宽容)
            double similarity = calculateSimilarityDTW(folderDurations, candidate.getDurations());
            
            log.info("候选专辑: {} - {} ({}首曲目)", 
                candidate.getAlbumTitle(), 
                candidate.getAlbumArtist(),
                candidate.getDurations().size());
            log.info("  时长序列: {}", formatDurationSequence(candidate.getDurations()));
            log.info("  相似度: {:.2f} ({})", similarity, evaluateMatchQuality(similarity));
            
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestMatch = new AlbumMatchResult(candidate, similarity);
            }
        }
        
        // 检查是否达到最小匹配阈值
        if (bestMatch != null && bestSimilarity >= MIN_MATCH_THRESHOLD) {
            log.info("✓ 选择最佳匹配: {} - {} (相似度: {:.2f})", 
                bestMatch.getAlbumInfo().getAlbumTitle(),
                bestMatch.getAlbumInfo().getAlbumArtist(),
                bestSimilarity);
            return bestMatch;
        } else {
            log.warn("未找到符合阈值的匹配专辑 (最佳相似度: {:.2f}, 阈值: {:.2f})", 
                bestSimilarity, MIN_MATCH_THRESHOLD);
            return null;
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
        double bestCombinedScore = 0.0;

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
            log.info("  时长相似度: {:.2f}, 名称相似度: {:.2f}, 综合得分: {:.2f}",
                durationSimilarity, nameSimilarity, combinedScore);

            if (combinedScore > bestCombinedScore) {
                bestCombinedScore = combinedScore;
                bestMatch = new AlbumMatchResult(candidate, combinedScore);
            }
        }

        // 检查是否达到最小匹配阈值
        if (bestMatch != null && bestCombinedScore >= MIN_MATCH_THRESHOLD) {
            log.info("✓ 选择最佳匹配: {} - {} (综合得分: {:.2f}, 格式: {})",
                bestMatch.getAlbumInfo().getAlbumTitle(),
                bestMatch.getAlbumInfo().getAlbumArtist(),
                bestCombinedScore,
                bestMatch.getAlbumInfo().getMediaFormat());
            return bestMatch;
        } else {
            log.warn("未找到符合阈值的匹配专辑 (最佳综合得分: {:.2f}, 阈值: {:.2f})",
                bestCombinedScore, MIN_MATCH_THRESHOLD);
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
        
        // CD 格式检测（优先级最高）
        if (upper.contains("[CD") || upper.contains("(CD") ||
            upper.contains(" CD ") || upper.contains("CD FLAC") ||
            upper.contains("CD ALAC") || upper.contains("CD]") ||
            upper.matches(".*\\bCD\\b.*")) {
            return MediaFormat.CD;
        }
        
        // SACD 格式
        if (upper.contains("SACD") || upper.contains("DSD")) {
            return MediaFormat.SACD;
        }
        
        // Digital 格式
        if (upper.contains("DIGITAL") || upper.contains("WEB") ||
            upper.contains("ITUNES") || upper.contains("[WEB") ||
            upper.contains("STREAMING")) {
            return MediaFormat.DIGITAL;
        }
        
        // Vinyl 格式
        if (upper.contains("VINYL") || upper.contains(" LP") ||
            upper.contains("12\"") || upper.contains("12''")) {
            return MediaFormat.VINYL;
        }
        
        // Cassette 格式
        if (upper.contains("CASSETTE") || upper.contains("TAPE")) {
            return MediaFormat.CASSETTE;
        }
        
        return MediaFormat.UNKNOWN;
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
        private double similarity; // 相似度分数
        private MatchQuality quality; // 匹配质量
        
        public AlbumMatchResult(AlbumDurationInfo albumInfo, double similarity) {
            this.albumInfo = albumInfo;
            this.similarity = similarity;
            this.quality = evaluateQuality(similarity);
        }
        
        private MatchQuality evaluateQuality(double similarity) {
            if (similarity >= 0.95) return MatchQuality.EXCELLENT;
            if (similarity >= 0.85) return MatchQuality.GOOD;
            if (similarity >= 0.70) return MatchQuality.ACCEPTABLE;
            return MatchQuality.POOR;
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