package com.lux032.musicautotagger.service.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.util.JsonUtils;

import java.net.http.HttpRequest;
import java.util.Locale;

/**
 * LLM 协议适配层（阶段七 #21）
 *
 * 背景：原 {@code ArtistMatchingService} 把 Anthropic 协议硬编码在了业务代码里
 * （{@code x-api-key} + {@code anthropic-version} 头 + {@code content[0].text} 解析），
 * 但配置项名字是通用的 {@code llm.apiUrl}，用户填一个 OpenAI 兼容端点会直接失败。
 *
 * 这里把「请求怎么拼 / 头怎么加 / 响应怎么解析」抽出来，业务侧只关心 prompt 与文本结果。
 */
public interface LlmProvider {

    /** 供日志展示的协议名 */
    String name();

    /**
     * 构造请求体。
     *
     * @param temperature 判定类任务固定传 0，保证结果可复现
     */
    JsonObject buildRequestBody(String model, String systemPrompt, String userPrompt,
                                int maxTokens, double temperature);

    /** 追加鉴权相关的 HTTP 头 */
    void applyAuthHeaders(HttpRequest.Builder builder, String apiKey);

    /**
     * 从响应体中取出模型输出的纯文本。
     *
     * @return 文本内容；结构不符合预期时返回 null（由调用方判定为失败并重试 / 换端点）
     */
    String extractText(JsonObject response);

    /** 从响应体中解析可选 Token 消耗（未返回 usage 时返回全 0）。 */
    default LlmClient.TokenUsage extractUsage(JsonObject response) {
        return LlmClient.TokenUsage.empty();
    }

    // ==================== 选择实现 ====================

    /**
     * 按供应商显式配置的格式选择实现。
     *
     * 新配置结构下协议是用户选的，不再从 URL 推断：
     * 中转站常见「路径长得像 Anthropic、实际只接受 OpenAI 报文」的组合，
     * 猜错时端点多半直接回 404 空体，根本无从诊断。
     */
    static LlmProvider forFormat(String format) {
        return "anthropic".equalsIgnoreCase(format) ? new Anthropic() : new OpenAiCompatible();
    }

    /**
     * 按配置或 URL 猜测协议。
     *
     * 判断依据只用 URL 形态，不做网络探测：
     *   - {@code /v1/messages} 或域名含 anthropic  -> Anthropic
     *   - 其余一律按 OpenAI 兼容（这是目前绝大多数第三方端点的形态）
     */
    static LlmProvider resolve(String configured, String apiUrl) {
        if (configured != null) {
            switch (configured.trim().toLowerCase(Locale.ROOT)) {
                case "anthropic":
                case "claude":
                    return new Anthropic();
                case "openai":
                case "openai-compatible":
                case "compatible":
                    return new OpenAiCompatible();
                default:
                    break; // auto / 空值 -> 走下面的自动判断
            }
        }
        String url = apiUrl == null ? "" : apiUrl.toLowerCase(Locale.ROOT);
        if (url.contains("anthropic") || url.contains("/v1/messages")) {
            return new Anthropic();
        }
        return new OpenAiCompatible();
    }

    // ==================== Anthropic ====================

    class Anthropic implements LlmProvider {

        private static final String API_VERSION = "2023-06-01";

        @Override
        public String name() {
            return "anthropic";
        }

        @Override
        public JsonObject buildRequestBody(String model, String systemPrompt, String userPrompt,
                                           int maxTokens, double temperature) {
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.addProperty("content", userPrompt);

            JsonArray messages = new JsonArray();
            messages.add(message);

            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("max_tokens", maxTokens);
            body.addProperty("temperature", temperature);
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                // Anthropic 的 system 是顶层字段，不是 messages 里的一条
                body.addProperty("system", systemPrompt);
            }
            body.add("messages", messages);
            return body;
        }

        @Override
        public void applyAuthHeaders(HttpRequest.Builder builder, String apiKey) {
            builder.header("x-api-key", apiKey);
            builder.header("anthropic-version", API_VERSION);
        }

        @Override
        public String extractText(JsonObject response) {
            JsonArray content = JsonUtils.array(response, "content");
            if (content == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (JsonElement element : content) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                String text = JsonUtils.string(element.getAsJsonObject(), "text");
                if (text != null) {
                    sb.append(text);
                }
            }
            String result = sb.toString().trim();
            return result.isEmpty() ? null : result;
        }

        @Override
        public LlmClient.TokenUsage extractUsage(JsonObject response) {
            JsonObject usage = JsonUtils.object(response, "usage");
            if (usage == null) {
                return LlmClient.TokenUsage.empty();
            }
            int inputUncached = optNonNegativeInt(usage, "input_tokens");
            int cacheCreation = optNonNegativeInt(usage, "cache_creation_input_tokens");
            int cacheRead = optNonNegativeInt(usage, "cache_read_input_tokens");
            int promptTotal = inputUncached + cacheCreation + cacheRead;
            int output = optNonNegativeInt(usage, "output_tokens");
            int total = promptTotal + output;
            return new LlmClient.TokenUsage(promptTotal, output, cacheRead, 0, total);
        }
    }

    // ==================== OpenAI 兼容 ====================

    /**
     * 覆盖 OpenAI 官方以及绝大多数「OpenAI 兼容」端点
     * （DeepSeek / 通义 / OpenRouter / vLLM / Ollama 的 /v1/chat/completions 等）。
     */
    class OpenAiCompatible implements LlmProvider {

        @Override
        public String name() {
            return "openai-compatible";
        }

        @Override
        public JsonObject buildRequestBody(String model, String systemPrompt, String userPrompt,
                                           int maxTokens, double temperature) {
            JsonArray messages = new JsonArray();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                JsonObject system = new JsonObject();
                system.addProperty("role", "system");
                system.addProperty("content", systemPrompt);
                messages.add(system);
            }
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", userPrompt);
            messages.add(user);

            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("max_tokens", maxTokens);
            body.addProperty("temperature", temperature);
            body.add("messages", messages);
            return body;
        }

        @Override
        public void applyAuthHeaders(HttpRequest.Builder builder, String apiKey) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        @Override
        public String extractText(JsonObject response) {
            JsonArray choices = JsonUtils.array(response, "choices");
            if (choices == null || choices.size() == 0 || !choices.get(0).isJsonObject()) {
                return null;
            }
            JsonObject first = choices.get(0).getAsJsonObject();
            JsonObject message = JsonUtils.object(first, "message");
            String content = JsonUtils.trimmedString(message, "content");
            if (content != null) {
                return content;
            }
            // 少数兼容端点直接返回 text 字段
            return JsonUtils.trimmedString(first, "text");
        }

        @Override
        public LlmClient.TokenUsage extractUsage(JsonObject response) {
            JsonObject usage = JsonUtils.object(response, "usage");
            if (usage == null) {
                return LlmClient.TokenUsage.empty();
            }
            int prompt = optNonNegativeInt(usage, "prompt_tokens");
            if (prompt == 0) {
                prompt = optNonNegativeInt(usage, "input_tokens");
            }
            int completion = optNonNegativeInt(usage, "completion_tokens");
            if (completion == 0) {
                completion = optNonNegativeInt(usage, "output_tokens");
            }
            JsonObject promptDetails = JsonUtils.object(usage, "prompt_tokens_details");
            if (promptDetails == null) {
                promptDetails = JsonUtils.object(usage, "input_tokens_details");
            }
            int cached = optNonNegativeInt(promptDetails, "cached_tokens");
            JsonObject completionDetails = JsonUtils.object(usage, "completion_tokens_details");
            if (completionDetails == null) {
                completionDetails = JsonUtils.object(usage, "output_tokens_details");
            }
            int reasoning = optNonNegativeInt(completionDetails, "reasoning_tokens");
            int total = optNonNegativeInt(usage, "total_tokens");
            if (total == 0) {
                total = prompt + completion;
            }
            return new LlmClient.TokenUsage(prompt, completion, cached, reasoning, total);
        }
    }

    private static int optNonNegativeInt(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return 0;
        }
        try {
            return Math.max(0, obj.get(key).getAsInt());
        } catch (Exception e) {
            return 0;
        }
    }
}
