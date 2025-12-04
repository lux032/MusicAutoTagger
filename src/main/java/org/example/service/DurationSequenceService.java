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
        
        // 计算DTW距离
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
                
                dtw[i][j] = cost + Math.min(
                    Math.min(dtw[i - 1][j], dtw[i][j - 1]),
                    dtw[i - 1][j - 1]
                );
            }
        }
        
        double dtwDistance = dtw[m][n];
        
        // 归一化:除以序列长度和平均时长
        double avgDuration = (folderDurations.stream().mapToInt(Integer::intValue).average().orElse(200.0) +
                             albumDurations.stream().mapToInt(Integer::intValue).average().orElse(200.0)) / 2.0;
        int maxLength = Math.max(m, n);
        double normalizedDistance = dtwDistance / (maxLength * avgDuration);
        
        // 转换为相似度
        double similarity = 1.0 / (1.0 + normalizedDistance);
        
        log.debug("DTW时长序列匹配 - DTW距离:{:.2f}, 归一化距离:{:.4f}, 相似度:{:.2f}", 
            dtwDistance, normalizedDistance, similarity);
        
        return Math.max(0.0, Math.min(1.0, similarity));
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
        private String albumTitle;
        private String albumArtist;
        private List<Integer> durations; // 曲目时长列表(秒)
        
        public AlbumDurationInfo(String releaseGroupId, String albumTitle, 
                                String albumArtist, List<Integer> durations) {
            this.releaseGroupId = releaseGroupId;
            this.albumTitle = albumTitle;
            this.albumArtist = albumArtist;
            this.durations = durations != null ? new ArrayList<>(durations) : new ArrayList<>();
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