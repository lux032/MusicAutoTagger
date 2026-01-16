package com.lux032.musicautotagger.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Properties;

/**
 * 国际化工具类
 * 用于加载和获取多语言资源
 */
@Slf4j
public class I18nUtil {

    private static Properties messages;
    private static String currentLanguage = "en_US"; // 默认英文

    /**
     * 初始化国际化资源
     * @param language 语言代码，如 zh_CN 或 en_US
     */
    public static void init(String language) {
        if (language == null || language.trim().isEmpty()) {
            language = "en_US";
        }

        currentLanguage = language;
        messages = new Properties();

        String resourceFile = "/messages_" + language + ".properties";

        try (InputStream is = I18nUtil.class.getResourceAsStream(resourceFile);
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            messages.load(reader);
            log.info("Successfully loaded i18n resource file: {}", resourceFile);
        } catch (IOException | NullPointerException e) {
            log.error("Failed to load i18n resource file: {}, falling back to default English", resourceFile, e);
            // 如果加载失败，尝试加载默认英文资源
            if (!"en_US".equals(language)) {
                init("en_US");
            }
        }
    }

    /**
     * 获取国际化消息
     * @param key 消息键
     * @return 对应语言的消息文本，如果找不到则返回键本身
     */
    public static String getMessage(String key) {
        if (messages == null) {
            init(currentLanguage);
        }
        return messages.getProperty(key, key);
    }

    /**
     * 获取国际化消息（带默认值）
     * @param key 消息键
     * @param defaultValue 默认值
     * @return 对应语言的消息文本，如果找不到则返回默认值
     */
    public static String getMessageWithDefault(String key, String defaultValue) {
        if (messages == null) {
            init(currentLanguage);
        }
        return messages.getProperty(key, defaultValue);
    }

    /**
     * 获取国际化消息（支持参数替换）
     * 支持 SLF4J 风格的 {} 占位符和 MessageFormat 风格的 {0} {1} 占位符
     * @param key 消息键
     * @param args 参数数组，用于替换消息模板中的占位符
     * @return 格式化后的消息文本
     */
    public static String getMessage(String key, Object... args) {
        if (messages == null) {
            init(currentLanguage);
        }
        String pattern = messages.getProperty(key, key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        return formatMessage(pattern, args);
    }

    /**
     * 格式化消息，同时支持 SLF4J 风格 {} 和 MessageFormat 风格 {0} {1} 占位符
     * @param pattern 消息模板
     * @param args 参数数组
     * @return 格式化后的消息
     */
    private static String formatMessage(String pattern, Object... args) {
        if (pattern == null || args == null || args.length == 0) {
            return pattern;
        }

        // 检查是否包含 MessageFormat 风格的占位符 {0}, {1} 等
        if (pattern.matches(".*\\{\\d+\\}.*")) {
            try {
                return MessageFormat.format(pattern, args);
            } catch (Exception e) {
                // 如果 MessageFormat 失败，回退到 SLF4J 风格
            }
        }

        // 使用 SLF4J 风格的 {} 占位符
        StringBuilder result = new StringBuilder();
        int argIndex = 0;
        int i = 0;

        while (i < pattern.length()) {
            if (i < pattern.length() - 1 && pattern.charAt(i) == '{' && pattern.charAt(i + 1) == '}') {
                // 找到 {} 占位符
                if (argIndex < args.length) {
                    result.append(args[argIndex] != null ? args[argIndex].toString() : "null");
                    argIndex++;
                } else {
                    result.append("{}");
                }
                i += 2;
            } else {
                result.append(pattern.charAt(i));
                i++;
            }
        }

        return result.toString();
    }

    /**
     * 获取当前语言
     * @return 当前语言代码
     */
    public static String getCurrentLanguage() {
        return currentLanguage;
    }

    /**
     * 获取所有消息属性（用于前端）
     * @return Properties对象
     */
    public static Properties getAllMessages() {
        if (messages == null) {
            init(currentLanguage);
        }
        return messages;
    }
}

