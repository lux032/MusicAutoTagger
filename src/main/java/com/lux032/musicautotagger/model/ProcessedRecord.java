package com.lux032.musicautotagger.model;

import java.util.Set;

/**
 * 一条「已处理文件」记录（处理记录管理页使用）。
 * 字段与 processed_files 表 / 文件模式日志的列一一对应，
 * sourceExists / targetExists / inMonitorDirectory 由服务层按需补充。
 */
public class ProcessedRecord {

    /** 记录状态分类，用于筛选与徽章展示。 */
    public enum Status { SUCCESS, FAILED, OTHER }

    /** 识别/处理失败时写入的哨兵 recording_id。 */
    public static final Set<String> FAILED_IDS = Set.of("FAILED", "UNKNOWN", "WRITE_FAILED", "EXCEPTION");
    /** 既不算成功也不算失败的特殊处置：人工驳回、CUE 整轨源文件（已拆分）。 */
    public static final Set<String> OTHER_IDS = Set.of("REVIEW_REJECTED", "CUE_SPLIT");

    public String filePath;
    public String fileName;
    public String recordingId;
    public String artist;
    public String title;
    public String album;
    public String releaseGroupId;
    public String targetFilePath;
    /** yyyy-MM-dd HH:mm:ss */
    public String processedTime;
    public Status status;

    // ---- 服务层补充 ----
    public Boolean sourceExists;
    public Boolean targetExists;
    public Boolean inMonitorDirectory;

    public static Status classify(String recordingId) {
        if (recordingId == null) return Status.SUCCESS;
        String id = recordingId.trim();
        if (FAILED_IDS.contains(id)) return Status.FAILED;
        if (OTHER_IDS.contains(id)) return Status.OTHER;
        return Status.SUCCESS;
    }
}
