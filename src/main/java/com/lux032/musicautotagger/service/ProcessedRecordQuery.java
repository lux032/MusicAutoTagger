package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.model.ProcessedRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 处理记录查询条件（搜索 / 状态 / 专辑 / 排序 / 分页）。
 */
public class ProcessedRecordQuery {

    public enum Sort {
        TIME_DESC, TIME_ASC, ALBUM, ARTIST, TITLE, PATH;

        public static Sort parse(String value) {
            if (value == null || value.isBlank()) return TIME_DESC;
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return TIME_DESC;
            }
        }
    }

    public static final int MAX_LIMIT = 200;

    /** 模糊匹配文件路径、艺术家、标题、专辑（不区分大小写）。 */
    public String keyword;
    /** null 表示全部。 */
    public ProcessedRecord.Status status;
    /** 精确匹配的专辑名，null 表示不限。 */
    public String album;
    public Sort sort = Sort.TIME_DESC;
    public int offset = 0;
    public int limit = 50;

    public String normalizedKeyword() {
        return keyword == null || keyword.isBlank() ? null : keyword.trim();
    }

    public String normalizedAlbum() {
        return album == null || album.isEmpty() ? null : album;
    }

    public int safeLimit() {
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    public int safeOffset() {
        return Math.max(0, offset);
    }

    /** 查询结果：当前页 + 总数 + 各状态计数（计数不受 status 条件影响，便于显示在筛选标签上）。 */
    public static class Page {
        public List<ProcessedRecord> items = new ArrayList<>();
        public long total;
        public Map<String, Long> counts = new HashMap<>();
        public int offset;
        public int limit;
    }
}
