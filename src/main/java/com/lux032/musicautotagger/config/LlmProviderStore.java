package com.lux032.musicautotagger.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 供应商配置的读写。
 *
 * 单独存成 JSON 而不是塞进 config.properties：供应商是嵌套结构（一个供应商下多个模型，
 * 每个模型还有自己的开关），压成一行 JSON 塞进 properties 后人工编辑基本不可行，
 * 而这个项目的用户确实会手改配置文件。
 */
public class LlmProviderStore {

    private static final Type LIST_TYPE = new TypeToken<List<LlmProviderConfig>>() { }.getType();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LlmProviderStore() {
    }

    public static List<LlmProviderConfig> load(String path) {
        Path file = Paths.get(path);
        if (!Files.exists(file)) {
            return null; // 与「空列表」区分开：null 表示从未配置过，触发旧配置迁移
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            List<LlmProviderConfig> providers = GSON.fromJson(json, LIST_TYPE);
            if (providers == null) {
                return new ArrayList<>();
            }
            // 历史文件可能缺 id（手工编辑过），这里补齐，否则前端无法回传掩码密钥
            for (LlmProviderConfig provider : providers) {
                if (provider.getId() == null || provider.getId().isBlank()) {
                    provider.setId(UUID.randomUUID().toString());
                }
                if (provider.getModels() == null) {
                    provider.setModels(new ArrayList<>());
                }
            }
            return providers;
        } catch (Exception e) {
            // 解析失败时返回 null 会静默触发迁移并覆盖掉用户的文件，这里必须返回空列表并保留原文件
            System.err.println("Failed to read LLM providers from " + path + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public static void save(String path, List<LlmProviderConfig> providers) throws IOException {
        Path file = Paths.get(path);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // 先写临时文件再原子替换：直接覆写时进程被杀会留下半截 JSON，
        // 下次启动解析失败等于所有 LLM 配置丢失
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(providers == null ? new ArrayList<>() : providers),
            StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicUnsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 把旧的四个平行列表迁移成供应商结构。
     *
     * 旧结构里「一行 = 一个 key + 一个 url + 一个模型」，因此每行各自成为一个供应商。
     * 协议沿用旧的 URL 推断逻辑（保持迁移前后行为一致），迁移后用户可在面板上显式改。
     */
    public static List<LlmProviderConfig> migrateFromLegacy(List<String> keys, List<String> urls,
                                                            List<String> models, List<Boolean> webSearchFlags,
                                                            String legacyProvider) {
        List<LlmProviderConfig> providers = new ArrayList<>();
        if (keys == null || urls == null || models == null) {
            return providers;
        }
        int count = Math.min(keys.size(), Math.min(urls.size(), models.size()));
        for (int i = 0; i < count; i++) {
            String url = urls.get(i);
            LlmProviderConfig provider = new LlmProviderConfig();
            provider.setId(UUID.randomUUID().toString());
            provider.setName("Provider " + (i + 1));
            provider.setApiUrl(url);
            provider.setApiKey(keys.get(i));
            provider.setFormat(guessFormat(legacyProvider, url));
            provider.setEnabled(true);

            LlmProviderConfig.Model model = new LlmProviderConfig.Model();
            model.setId(models.get(i));
            model.setEnabled(true);
            model.setWebSearch(webSearchFlags != null && i < webSearchFlags.size()
                && Boolean.TRUE.equals(webSearchFlags.get(i)));
            provider.getModels().add(model);

            providers.add(provider);
        }
        return providers;
    }

    /** 仅用于迁移：复刻 LlmProvider.resolve 的旧判断，保证迁移不改变既有行为 */
    private static String guessFormat(String configured, String apiUrl) {
        if (configured != null) {
            String normalized = configured.trim().toLowerCase(java.util.Locale.ROOT);
            if ("anthropic".equals(normalized) || "claude".equals(normalized)) {
                return "anthropic";
            }
            if (normalized.startsWith("openai") || "compatible".equals(normalized)) {
                return "openai";
            }
        }
        String url = apiUrl == null ? "" : apiUrl.toLowerCase(java.util.Locale.ROOT);
        return url.contains("anthropic") || url.contains("/v1/messages") ? "anthropic" : "openai";
    }
}
