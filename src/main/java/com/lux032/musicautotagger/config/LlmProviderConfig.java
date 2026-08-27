package com.lux032.musicautotagger.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个 LLM 供应商的配置。
 *
 * 取代原来的「四个逗号分隔列表按下标配对」（llm.apiKey / llm.apiUrl / llm.model /
 * llm.webSearchEnabled）。那套结构有两个硬伤：
 *   1. 任何一列少一项就整体错位，会把 A 的 key 配到 B 的 url 上（前端只能靠"锁步过滤"打补丁）；
 *   2. 协议靠 URL 猜（LlmProvider.resolve），于是 https://host/v1/messages + gpt-* 模型
 *      会被判成 Anthropic 协议去请求一个 OpenAI 模型，中转站直接回 404 空响应体，无从诊断。
 *
 * 现在协议是显式字段，URL 由用户自己填，两者解耦。
 */
@Data
public class LlmProviderConfig {

    /** 稳定标识，用于前端回传时匹配已保存的密钥（密钥在响应里是掩码） */
    private String id;

    /** 展示名，例如 "OpenAI 官方" / "x666 中转" */
    private String name;

    /** 完整请求地址，由用户自行填写（中转站路径形态五花八门，不做拼接猜测） */
    private String apiUrl;

    private String apiKey;

    /** 报文协议：openai | anthropic。不再从 URL 推断 */
    private String format;

    private boolean enabled = true;

    /** 该供应商下启用的模型；顺序即故障转移顺序 */
    private List<Model> models = new ArrayList<>();

    @Data
    public static class Model {
        /** 模型标识，例如 gpt-4o / claude-3-5-sonnet-20241022 */
        private String id;
        private boolean enabled = true;
        /**
         * 是否参与「模型原生联网搜索」。
         * 仅在 llm.webSearch.provider=native 时有意义；Tavily 模式下检索由外部完成，
         * 任何模型都能承担归纳工作，此开关不参与判断。
         */
        private boolean webSearch;
    }

    /** openai / anthropic 之外的取值一律按 openai 处理：绝大多数第三方端点都是 OpenAI 兼容 */
    public String normalizedFormat() {
        return "anthropic".equalsIgnoreCase(format) ? "anthropic" : "openai";
    }

    public boolean isUsable() {
        return enabled
            && apiUrl != null && !apiUrl.isBlank()
            && apiKey != null && !apiKey.isBlank()
            && models != null && !models.isEmpty();
    }
}
