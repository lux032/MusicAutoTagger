package com.lux032.musicautotagger.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * 国际化工具类
 * 用于加载和获取多语言资源
 */
@Slf4j
public class I18nUtil {

    private static final Pattern MESSAGE_FORMAT_PLACEHOLDER = Pattern.compile("\\{\\d+\\}");
    private static volatile Properties messages;
    private static volatile String currentLanguage = "en_US"; // 默认英文

    /**
     * 初始化国际化资源（先在本地填充完 Properties 再原子发布到 volatile 引用，避免并发线程看到半初始化态）。
     * @param language 语言代码，如 zh_CN 或 en_US
     */
    public static synchronized void init(String language) {
        if (language == null || language.trim().isEmpty()) {
            language = "en_US";
        }
        String normalized = language.trim();
        Properties loaded = new Properties();
        String resourceFile = "/messages_" + normalized + ".properties";

        try (InputStream is = I18nUtil.class.getResourceAsStream(resourceFile)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourceFile);
            }
            try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                loaded.load(reader);
            }
            messages = loaded;
            currentLanguage = normalized;
            log.info("Successfully loaded i18n resource file: {}", resourceFile);
        } catch (IOException e) {
            log.error("Failed to load i18n resource file: {}, falling back to default English", resourceFile, e);
            if (!"en_US".equals(normalized)) {
                init("en_US");
            } else {
                messages = loaded;
                currentLanguage = "en_US";
            }
        }
    }

    private static Properties ensureMessages() {
        Properties current = messages;
        if (current == null) {
            init(currentLanguage);
            current = messages;
        }
        return current;
    }

    /**
     * 获取国际化消息
     * @param key 消息键
     * @return 对应语言的消息文本，如果找不到则返回键本身
     */
    public static String getMessage(String key) {
        return ensureMessages().getProperty(key, key);
    }

    /**
     * 获取国际化消息（带默认值）
     * @param key 消息键
     * @param defaultValue 默认值
     * @return 对应语言的消息文本，如果找不到则返回默认值
     */
    public static String getMessageWithDefault(String key, String defaultValue) {
        return ensureMessages().getProperty(key, defaultValue);
    }

    /**
     * 获取国际化消息（支持参数替换）
     * 支持 SLF4J 风格的 {} 占位符和 MessageFormat 风格的 {0} {1} 占位符
     * @param key 消息键
     * @param args 参数数组，用于替换消息模板中的占位符
     * @return 格式化后的消息文本
     */
    public static String getMessage(String key, Object... args) {
        String pattern = ensureMessages().getProperty(key, key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        return formatMessage(pattern, args);
    }

    /**
     * 格式化消息，同时支持 SLF4J 风格 {} 和 MessageFormat 风格 {0} {1} 占位符（兼容多行模板）
     * @param pattern 消息模板
     * @param args 参数数组
     * @return 格式化后的消息
     */
    public static String formatMessage(String pattern, Object... args) {
        if (pattern == null || args == null || args.length == 0) {
            return pattern;
        }

        // 使用 Matcher.find() 替代 String.matches(".*\\{\\d+\\}.*")，避免含换行符文案失效
        if (MESSAGE_FORMAT_PLACEHOLDER.matcher(pattern).find()) {
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
        return ensureMessages();
    }

    /**
     * 按指定语言只读加载消息表，不修改当前全局语言状态（供未登录初始化引导实时预览切换语言）。
     * 仅允许已知白名单语言代码；非法值返回 null。
     */
    public static Properties loadMessagesForLanguage(String language) {
        if (language == null) {
            return null;
        }
        String normalized = language.trim();
        if (!"zh_CN".equals(normalized) && !"en_US".equals(normalized)) {
            return null;
        }
        String resourceFile = "/messages_" + normalized + ".properties";
        try (InputStream is = I18nUtil.class.getResourceAsStream(resourceFile)) {
            if (is == null) {
                return null;
            }
            Properties loaded = new Properties();
            try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                loaded.load(reader);
            }
            return loaded;
        } catch (IOException e) {
            log.warn("按需加载语言资源失败 {}: {}", normalized, e.getMessage());
            return null;
        }
    }
}

