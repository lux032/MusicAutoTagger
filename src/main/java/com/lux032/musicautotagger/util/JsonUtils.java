package com.lux032.musicautotagger.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 统一的空安全、类型安全 JSON 辅助工具与共享序列化实例。
 * 收口全项目分散的 Gson / Jackson 容错读取逻辑，消除因第三方 API 返回 null/异构字段导致的 ClassCastException。
 */
public final class JsonUtils {

    private static final Gson SHARED_GSON = new Gson();
    private static final Gson SHARED_HTML_SAFE_GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final ObjectMapper SHARED_OBJECT_MAPPER = new ObjectMapper();

    private JsonUtils() {
    }

    public static Gson sharedGson() {
        return SHARED_GSON;
    }

    public static Gson sharedHtmlSafeGson() {
        return SHARED_HTML_SAFE_GSON;
    }

    public static ObjectMapper sharedObjectMapper() {
        return SHARED_OBJECT_MAPPER;
    }

    public static JsonObject parseObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonElement el = JsonParser.parseString(raw);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String string(JsonObject obj, String key) {
        if (obj == null || key == null) {
            return null;
        }
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) {
            return null;
        }
        try {
            return el.getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String trimmedString(JsonObject obj, String key) {
        String value = string(obj, key);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static double doubleOr(JsonObject obj, String key, double fallback) {
        if (obj == null || key == null) {
            return fallback;
        }
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) {
            return fallback;
        }
        try {
            double v = el.getAsDouble();
            return Double.isFinite(v) ? v : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static int intOr(JsonObject obj, String key, int fallback) {
        if (obj == null || key == null) {
            return fallback;
        }
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return el.getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static boolean boolOr(JsonObject obj, String key, boolean fallback) {
        if (obj == null || key == null) {
            return fallback;
        }
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return el.getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static JsonObject object(JsonObject obj, String key) {
        if (obj == null || key == null) {
            return null;
        }
        JsonElement el = obj.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    public static JsonArray array(JsonObject obj, String key) {
        if (obj == null || key == null) {
            return null;
        }
        JsonElement el = obj.get(key);
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : null;
    }
}
