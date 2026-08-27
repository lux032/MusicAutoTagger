package com.lux032.musicautotagger.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 「待人工确认」队列条目（阶段六 #18）
 *
 * 触发场景：曲目已经被指纹识别认出来了（曲目级元数据准确），
 * 但**专辑定不下来**——最典型的就是 MusicBrainz 尚未收录的新精选集。
 *
 * 关键约束：进入本状态时，文件**不能**已经被写标签 / 改名 / 移动。
 * 因此这里记录的是原始文件路径（以及转码产生的暂存文件路径），
 * 真正的落盘动作要等人工在面板上做出选择之后才执行。
 *
 * 本对象会被 Gson 序列化到磁盘，必须保持为纯数据结构（可跨重启恢复）。
 */
@Data
public class ReviewItem {

    public enum Status {
        /** 等待人工确认 */
        PENDING_REVIEW,
        /** 人工从候选中选定了具体 release，已按该 release 归档 */
        CONFIRMED,
        /** 人工确认「MusicBrainz 未收录」，已用合成信息归档（verified=false） */
        ARCHIVED_UNVERIFIED,
        /** 人工标记为失败 / 忽略 */
        REJECTED
    }

    private String id;
    private String folderPath;
    private String folderName;
    private Status status = Status.PENDING_REVIEW;
    private long createdAt;
    private long updatedAt;

    /** 进入待确认的原因（用于面板展示） */
    private String reason;

    /** 统一置信度（阶段六 #20），0~1 */
    private double confidence;

    /** 合成的专辑信息：人工选择「按 MB 未收录归档」时直接使用 */
    private String synthesizedAlbumTitle;
    private String synthesizedAlbumArtist;

    /** 文件夹的时长序列（用于与候选 release 逐条 diff） */
    private List<Integer> durationSequence = new ArrayList<>();

    /** 候选专辑快照 */
    private List<CandidateSnapshot> candidates = new ArrayList<>();

    /** 是否已经从 MusicBrainz 展开过候选的 release 明细 */
    private boolean candidatesExpanded;

    /** 待确认的文件清单 */
    private List<FileEntry> files = new ArrayList<>();

    /** 转码暂存目录（长期挂起时不能占用临时目录，需挪到暂存区） */
    private String stagingDir;

    /** 人工处置结果说明 */
    private String resolutionNote;

    /**
     * LLM 封闭式判定结果（阶段七 #22）
     *
     * 默认只是**建议**，条目仍保持 PENDING_REVIEW；
     * 只有 llm.album.autoApply 打开且置信度达标时才会自动落盘。
     */
    private LlmSuggestion llmSuggestion;

    /**
     * 人工选定的 release（CONFIRMED 时填充）
     *
     * 字段要足够完整：重启时需要靠它们重建内存里的 MANUAL_CONFIRMED 专辑锁定，
     * 否则「人工结果不会被自动流程改写」只在当前进程生命周期内成立。
     */
    private String resolvedReleaseId;
    private String resolvedReleaseGroupId;
    private String resolvedAlbumTitle;
    private String resolvedAlbumArtist;
    private String resolvedReleaseDate;
    private int resolvedTrackCount;

    /**
     * 单个待确认文件
     */
    @Data
    public static class FileEntry {
        /** 监控目录中的原始文件（始终未被修改） */
        private String originalPath;
        /**
         * 暂存文件路径（仅当发生过格式规范化转码时存在）。
         * 为 null 表示直接使用 originalPath。
         */
        private String stagedPath;
        private String fileName;
        /** 时长（秒），用于与候选 release 的时长序列做 diff */
        private Integer duration;
        /** 指纹识别得到的曲目级元数据（准确，确认后直接复用） */
        private MusicMetadata metadata;
    }

    /**
     * LLM 判定建议
     *
     * 只会出现两种结论：「选中某个已有候选」或「都不是」。
     * **不允许**模型自由给出专辑名/年份，否则它会编造一个看上去非常合理的虚构答案。
     */
    @Data
    public static class LlmSuggestion {
        private long evaluatedAt;
        private String model;
        private String provider;
        /** 1-based 候选序号，0 = 都不是 */
        private int choiceIndex;
        private String suggestedReleaseId;
        private String suggestedReleaseGroupId;
        private String suggestedTitle;
        private double confidence;
        /** 是否判定为「MusicBrainz 尚未收录的精选集/自制合辑」 */
        private boolean unreleasedCompilation;
        private String reason;
        /** 是否已按该结论自动落盘 */
        private boolean applied;
    }

    /**
     * 候选专辑快照
     *
     * 分两级：
     * - Release Group 级：来自 AcoustID 的候选（零额外 API 调用）
     * - Release 级：人工打开详情时才从 MusicBrainz 展开，包含曲目数 / 格式 / 时长序列
     */
    @Data
    public static class CandidateSnapshot {
        private String releaseGroupId;
        private String releaseId;
        private String title;
        private String artist;
        private String date;
        private String mediaFormat;
        private int trackCount;
        /** 有多少个样本的候选里包含这张专辑 */
        private int supportCount;
        private int totalSamples;
        private List<Integer> durations = new ArrayList<>();
        /** 与文件夹时长序列的相似度（展开后计算） */
        private Double durationSimilarity;
    }
}
