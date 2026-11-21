package com.lux032.musicautotagger.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「为什么没能自动处理」的结构化诊断。
 *
 * 判定点（覆盖率检测、锁定前覆盖检查、投票）在做出「不锁定」决定时记录一份，
 * 入队时随条目落盘。面板按 code + facts 渲染本地化文案，
 * 不再依赖一句写死的中文原因。
 *
 * code 用字符串而不是枚举：队列文件跨版本持久化，
 * 旧版本读到未知的 code 时应当降级显示，而不是整个队列文件解析失败。
 */
public final class ReviewDiagnosis {

    /** 没有任何一张候选专辑能解释足够多的曲目（疑似精选集 / 自制合辑） */
    public static final String NO_DOMINANT_ALBUM = "NO_DOMINANT_ALBUM";
    /** 投票选出的专辑与本地曲目时长严重不符 */
    public static final String VOTE_MISMATCH = "VOTE_MISMATCH";
    /** 最接近的版本覆盖不了本地曲目，且文件夹曲目数不可靠，无法判定为缺曲 */
    public static final String TRACK_COUNT_UNRELIABLE = "TRACK_COUNT_UNRELIABLE";
    /** 曲目认出来了，但识别结果里没有任何专辑信息 */
    public static final String NO_ALBUM_INFO = "NO_ALBUM_INFO";
    /** 有候选专辑，但样本间的一致性不够 */
    public static final String LOW_CONFIDENCE = "LOW_CONFIDENCE";
    /** 锁定专辑缺曲 */
    public static final String TRACKS_MISSING = "TRACKS_MISSING";
    /** 缺少封面（等待补全封面或人工确认保持无封面入库） */
    public static final String COVER_MISSING = "COVER_MISSING";
    /** 从「重新识别」转入的联网辅助识别 */
    public static final String ONLINE_ASSIST = "ONLINE_ASSIST";
    /** 兜底：专辑未能确定，但没有更具体的诊断（含旧版本入队的条目） */
    public static final String ALBUM_UNDETERMINED = "ALBUM_UNDETERMINED";

    private final String code;
    private final Map<String, Object> facts = new LinkedHashMap<>();

    private ReviewDiagnosis(String code) {
        this.code = code;
    }

    public static ReviewDiagnosis of(String code) {
        return new ReviewDiagnosis(code);
    }

    /** 附带一条事实；null 值直接忽略，面板按「缺失」处理 */
    public ReviewDiagnosis with(String key, Object value) {
        if (key != null && value != null) {
            facts.put(key, value);
        }
        return this;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getFacts() {
        return new LinkedHashMap<>(facts);
    }

    /**
     * 条目的实际原因分类。
     *
     * 旧队列文件没有 reasonCode，按类型和原有文本推断，保证旧条目也能被筛选和打标签。
     */
    public static String effectiveCode(ReviewItem item) {
        if (item == null) {
            return ALBUM_UNDETERMINED;
        }
        String code = item.getReasonCode();
        if (code != null && !code.isEmpty()) {
            return code;
        }
        if (item.effectiveKind() == ReviewItem.Kind.TRACKS_MISSING) {
            return TRACKS_MISSING;
        }
        if (item.effectiveKind() == ReviewItem.Kind.COVER_MISSING) {
            return COVER_MISSING;
        }
        if (item.getRecoverySourceType() != null) {
            return ONLINE_ASSIST;
        }
        return ALBUM_UNDETERMINED;
    }
}
