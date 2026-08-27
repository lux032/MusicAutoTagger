package com.lux032.musicautotagger.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文件名 / 目录名清洗工具
 *
 * 元数据来自 MusicBrainz 和文件自带标签,都属于外部输入,不能直接当路径用。
 * 除了大家都知道的非法字符外,这里还处理三类实际会导致归档失败的情况:
 *
 * <ul>
 *   <li><b>长度</b> —— ext4/APFS 单个文件名上限 255 <em>字节</em>,
 *       中文在 UTF-8 下一个字占 3 字节,古典乐曲名很容易超。必须按字节截断。</li>
 *   <li><b>Windows 保留设备名</b> —— 名为 {@code CON}/{@code NUL}/{@code COM1} 的
 *       目录在 Windows 上根本建不出来。</li>
 *   <li><b>结尾的点与空格</b> —— Windows 会静默丢弃,导致路径对不上;
 *       同时也把 {@code .} 和 {@code ..} 这种危险分量消灭掉。</li>
 * </ul>
 */
public final class FileNameSanitizer {

    /** 单个路径分量(艺术家名 / 专辑名 / 曲名)的字节上限 */
    private static final int MAX_COMPONENT_BYTES = 120;

    /** 组装后完整文件名的字节上限,留出余量给曲目号前缀和扩展名 */
    private static final int MAX_FILE_NAME_BYTES = 200;

    /** 清洗后为空时使用的占位名 */
    public static final String FALLBACK_NAME = "Unknown";

    /** 各文件系统都不接受的字符 */
    private static final Pattern ILLEGAL_CHARS = Pattern.compile("[\\\\/:*?\"<>|]");

    /** 控制字符(含 NUL),会让部分文件系统直接报错 */
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

    /** 结尾的点与空格,Windows 会静默丢弃 */
    private static final Pattern TRAILING_DOTS_SPACES = Pattern.compile("[. ]+$");

    /** Windows 保留设备名,不区分大小写,带不带扩展名都不行 */
    private static final Set<String> WINDOWS_RESERVED_NAMES = new HashSet<>(Arrays.asList(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    ));

    private FileNameSanitizer() {
    }

    /**
     * 清洗单个路径分量(艺术家名、专辑名、曲名等)
     *
     * @param name 原始名称,可为 null
     * @return 可安全用作单级目录名或文件名的字符串;清洗后为空时返回 {@link #FALLBACK_NAME}
     */
    public static String sanitize(String name) {
        return sanitize(name, FALLBACK_NAME);
    }

    /**
     * 清洗单个路径分量,并指定清洗后为空时的占位名
     */
    public static String sanitize(String name, String fallback) {
        if (name == null) {
            return fallback;
        }

        String result = name;

        // 先保护 <INST> 这类有意义的标记,避免被当成非法字符删掉
        result = result.replace("<INST>", "〔INST〕");
        result = result.replace("<inst>", "〔inst〕");
        result = result.replace("<Inst>", "〔Inst〕");

        result = CONTROL_CHARS.matcher(result).replaceAll("");
        result = ILLEGAL_CHARS.matcher(result).replaceAll("");

        // 还原标记,用文件系统安全的方括号
        result = result.replace("〔INST〕", "[INST]");
        result = result.replace("〔inst〕", "[inst]");
        result = result.replace("〔Inst〕", "[Inst]");

        result = result.replaceAll("\\s+", " ").trim();

        // 长度必须在去掉结尾点/空格之前截断,否则截断后又可能产生新的结尾点
        result = truncateToBytes(result, MAX_COMPONENT_BYTES);
        result = TRAILING_DOTS_SPACES.matcher(result).replaceAll("").trim();

        if (result.isEmpty()) {
            return fallback;
        }

        if (isWindowsReservedName(result)) {
            result = "_" + result;
        }

        return result;
    }

    /**
     * 对已组装好的文件名做最终长度兜底,截断时保留扩展名
     *
     * 单个分量各自不超限,拼起来仍可能超(如"很长的艺术家 - 很长的曲名.flac"),
     * 所以落盘前还需要这一道。
     *
     * @param fileName  完整文件名,含扩展名
     * @param extension 扩展名,含点(如 {@code .flac});没有则传空串
     */
    public static String limitFileName(String fileName, String extension) {
        if (fileName == null || fileName.isEmpty()) {
            return FALLBACK_NAME + (extension == null ? "" : extension);
        }
        if (byteLength(fileName) <= MAX_FILE_NAME_BYTES) {
            return fileName;
        }

        String ext = extension == null ? "" : extension;
        String base = fileName.endsWith(ext) && !ext.isEmpty()
            ? fileName.substring(0, fileName.length() - ext.length())
            : fileName;

        int budget = MAX_FILE_NAME_BYTES - byteLength(ext);
        if (budget <= 0) {
            // 扩展名本身就异常地长,退化为只保留扩展名
            return truncateToBytes(ext, MAX_FILE_NAME_BYTES);
        }

        base = truncateToBytes(base, budget);
        base = TRAILING_DOTS_SPACES.matcher(base).replaceAll("").trim();
        if (base.isEmpty()) {
            base = FALLBACK_NAME;
        }
        return base + ext;
    }

    /**
     * 按 UTF-8 字节数截断,不会把一个多字节字符切成两半
     */
    private static String truncateToBytes(String value, int maxBytes) {
        if (byteLength(value) <= maxBytes) {
            return value;
        }

        StringBuilder builder = new StringBuilder();
        int usedBytes = 0;
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            int charCount = Character.charCount(codePoint);
            String piece = value.substring(index, index + charCount);
            int pieceBytes = byteLength(piece);
            if (usedBytes + pieceBytes > maxBytes) {
                break;
            }
            builder.append(piece);
            usedBytes += pieceBytes;
            index += charCount;
        }
        return builder.toString().trim();
    }

    private static int byteLength(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 判断是否为 Windows 保留设备名(比较时忽略扩展名,"NUL.flac" 同样非法)
     */
    private static boolean isWindowsReservedName(String name) {
        int dotIndex = name.indexOf('.');
        String stem = dotIndex > 0 ? name.substring(0, dotIndex) : name;
        return WINDOWS_RESERVED_NAMES.contains(stem.toUpperCase(Locale.ROOT));
    }
}
