package com.lux032.musicautotagger.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 音乐库里已存在相同音频，且调用方没有指定处理方式（替换 / 保留两份）。
 * 此时什么都没有提交，输入副本保持原样。
 */
public class DuplicateArchiveException extends IOException {

    public static final String CODE = "online.duplicate.exists";

    private final List<Map<String, Object>> duplicates;

    public DuplicateArchiveException(List<Map<String, Object>> duplicates) {
        super(CODE);
        this.duplicates = List.copyOf(duplicates);
    }

    public List<Map<String, Object>> getDuplicates() {
        return duplicates;
    }

    /** 给异步任务列表用的可读摘要（没有交互对话框可弹）。 */
    public String describe() {
        StringBuilder sb = new StringBuilder("音乐库中已存在相同音频，未提交：");
        for (Map<String, Object> d : duplicates) {
            sb.append(' ').append(d.get("path")).append(" (").append(d.get("matched"))
                .append('/').append(d.get("total")).append(')');
        }
        return sb.toString();
    }
}
