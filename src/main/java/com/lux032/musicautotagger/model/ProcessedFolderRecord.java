package com.lux032.musicautotagger.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条按文件夹（专辑根目录）聚合的处理记录摘要，用于 Web「处理记录」按文件夹视图。
 */
public class ProcessedFolderRecord {

    /** 文件夹整体细粒度状态。 */
    public enum FolderState {
        SUCCESS,
        PARTIAL_FAILED,
        ALL_FAILED,
        PARTIAL_REJECTED,
        REJECTED,
        CUE_ONLY,
        OTHER
    }

    /** 规范化的文件夹路径（作为唯一标识）。 */
    public String folderPath;
    /** 文件夹显示名（散落根目录时由前端按 looseRoot 展示本地化名称）。 */
    public String folderName;
    /** 相对监控目录的路径（在监控目录外时回退为 folderPath）。 */
    public String relativePath;
    /** 是否为监控目录根目录下的散落文件分组。 */
    public boolean looseRoot;

    /** 与顶部 ALL / SUCCESS / FAILED / OTHER Tab 互斥对应的主状态。 */
    public ProcessedRecord.Status status;
    /** 细粒度状态，用于区分全部失败、部分失败、已驳回等徽章。 */
    public FolderState folderState;

    public int totalCount;
    public int successCount;
    public int failedCount;
    public int otherCount;
    public int rejectedCount;
    public int cueSplitCount;
    public int discCount;
    public int missingSourceCount;

    /** 成功记录中出现次数最多的有效专辑名（非空、非 Unknown Album）。 */
    public String primaryAlbum;
    /** 成功记录中所有有效专辑名（按频次降序）。 */
    public List<String> albums = new ArrayList<>();
    /** 成功记录中出现次数最多的艺术家。 */
    public String primaryArtist;
    /** 成功记录中所有艺术家（按频次降序）。 */
    public List<String> artists = new ArrayList<>();

    /** 封面查询凭据（优先来自成功记录，前端通过 /api/cover 加载专辑封面替换文件夹图标）。 */
    public String coverReleaseGroupId;
    public String coverPath;

    /** 文件夹内最近一次处理时间（yyyy-MM-dd HH:mm:ss）。 */
    public String latestProcessedTime;
}
