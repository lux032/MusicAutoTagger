package com.lux032.musicautotagger.config;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 供应商 x 模型 展开后的单个可调用端点，是调用侧（LlmClient / NativeWebSearchClient）
 * 唯一关心的形态。
 *
 * 展开顺序 = 供应商顺序 -> 供应商内模型顺序，也就是故障转移顺序。
 */
@Data
@AllArgsConstructor
public class LlmEndpoint {

    private String providerId;
    private String providerName;
    private String apiUrl;
    private String apiKey;
    private String model;
    /** openai | anthropic，来自供应商的显式配置 */
    private String format;
    private boolean webSearch;

    /** 日志用：出错时必须能一眼看出是哪个供应商的哪个模型 */
    public String label() {
        String provider = providerName == null || providerName.isBlank() ? "unnamed" : providerName;
        return provider + " / " + model;
    }
}
