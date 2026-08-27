package com.lux032.musicautotagger.util;

import com.lux032.musicautotagger.service.FolderAlbumCache.CacheSource;

/**
 * 统一置信度模型（阶段六 #20）
 *
 * 背景：系统里原本有三套互不相通的标尺——
 *   - 快速扫描：0.90 阈值，只基于「标签 + 文件夹名」搜一次 MusicBrainz（最弱证据）
 *   - 时长序列：0.70 / 原始 DTW 0.75 门槛，基于音频本身（最强证据）
 *   - 投票：0.60 阈值，基于多个样本的一致性（中等偏弱）
 *
 * 三个数字量纲完全不同：快速扫描的 0.92 远不如时长序列的 0.80 可信，
 * 但在原来的代码里它们会被直接拿来比较（例如同优先级下比 confidence）。
 *
 * 本类把「某条链路自己的分数」映射到一个**全局可比**的 0~1 置信度，
 * 并给出统一的处置决策（自动归档 / 待人工确认 / 判失败）。
 *
 * 重要边界说明：
 *   本模型**不**用来改写各链路自己的准入门槛（例如快速扫描仍由 isHighConfidence() 把关）。
 *   它的用途是：
 *     1. 缓存优先级相同时的比较（避免拿快速扫描 0.95 压过时长序列 0.85）
 *     2. 「专辑未确定」时决定进人工确认队列还是直接归档
 *     3. Web 待确认面板上向用户展示一个可解释的统一分数
 *   把它接到自动归档主链路上属于更大的行为变更，需要单独评估。
 */
public final class ConfidenceModel {

    private ConfidenceModel() {
    }

    /** 达到该统一置信度才允许无人值守自动归档 */
    public static final double AUTO_ARCHIVE_THRESHOLD = 0.75;

    /** 低于该统一置信度视为「基本没有证据」，不值得占用人工确认队列 */
    public static final double REVIEW_THRESHOLD = 0.20;

    /** 处置决策 */
    public enum Decision {
        /** 证据充分，自动归档 */
        AUTO_ARCHIVE,
        /** 证据不足以自动归档，但曲目级信息可用，交人工确认 */
        PENDING_REVIEW,
        /** 证据太弱，直接按失败处理 */
        REJECT
    }

    /**
     * 把某条链路的原始分数映射到全局统一置信度。
     *
     * 映射规则：每条链路给出 [自身门槛, 1.0] 的原始区间，
     * 线性映射到该链路在全局标尺上「所能达到的可信区间」。
     * 弱证据链路即使拿到满分，也不会超过它的天花板。
     */
    public static double unify(CacheSource source, double rawScore) {
        if (source == null) {
            source = CacheSource.UNKNOWN;
        }
        double raw = clamp(rawScore, 0.0, 1.0);

        switch (source) {
            case MANUAL_CONFIRMED:
                // 人工确认：定义为满分，任何自动链路都不得覆盖
                return 1.0;
            case DURATION_SEQUENCE:
                // 音频时长序列匹配：最强的自动证据
                return map(raw, 0.70, 1.0, 0.70, 0.98);
            case QUICK_SCAN:
                // 标签 + 文件夹名：最弱的自动证据，天花板刻意压在自动归档线附近
                return map(raw, 0.90, 1.0, 0.55, 0.78);
            case VOTING:
                // 多样本投票：中等偏弱
                return map(raw, 0.60, 1.0, 0.35, 0.68);
            case UNKNOWN:
            default:
                // 来源不明（例如「专辑未确定」时合成的信息）：只保留很低的分数
                return raw * 0.30;
        }
    }

    /**
     * 根据统一置信度给出处置决策
     */
    public static Decision decide(double unifiedConfidence) {
        if (unifiedConfidence >= AUTO_ARCHIVE_THRESHOLD) {
            return Decision.AUTO_ARCHIVE;
        }
        if (unifiedConfidence >= REVIEW_THRESHOLD) {
            return Decision.PENDING_REVIEW;
        }
        return Decision.REJECT;
    }

    /**
     * 「专辑未确定」场景的统一置信度。
     *
     * 此时专辑本身没有任何 MusicBrainz 证据（confidence 语义上就是 0），
     * 但曲目级元数据是指纹识别出来的、是准确的。
     * 因此把「有多少个文件拿到了曲目级元数据」作为可用性分数，
     * 让它落在 PENDING_REVIEW 区间而不是直接 REJECT。
     *
     * @param filesWithTrackMetadata 拿到曲目级元数据的文件数
     * @param totalFiles             文件总数
     */
    public static double unresolvedAlbumConfidence(int filesWithTrackMetadata, int totalFiles) {
        if (totalFiles <= 0) {
            return 0.0;
        }
        double ratio = clamp((double) filesWithTrackMetadata / totalFiles, 0.0, 1.0);
        // 上限 0.6：专辑维度始终是「未验证」的，永远不该达到自动归档线
        return ratio * 0.6;
    }

    /**
     * 人类可读的说明，用于日志与 Web 面板
     */
    public static String describe(CacheSource source, double rawScore) {
        double unified = unify(source, rawScore);
        return String.format("%s(原始 %.2f) -> 统一置信度 %.2f [%s]",
            source == null ? "UNKNOWN" : source.name(), rawScore, unified, decide(unified));
    }

    private static double map(double value, double inLow, double inHigh, double outLow, double outHigh) {
        if (inHigh <= inLow) {
            return outHigh;
        }
        double ratio = clamp((value - inLow) / (inHigh - inLow), 0.0, 1.0);
        return outLow + ratio * (outHigh - outLow);
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }
}
