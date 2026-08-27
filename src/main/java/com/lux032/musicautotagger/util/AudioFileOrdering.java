package com.lux032.musicautotagger.util;

import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import java.io.File;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 音频文件的曲序比较器。
 *
 * 优先使用 DISC_NO / TRACK 标签；标签缺失时，回退到父目录和文件名的数字感知排序，
 * 避免 CD10 排在 CD2 前面，也避免纯按标题字典序破坏官方曲序。
 */
public final class AudioFileOrdering {
    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*(\\d+)");
    private static final Pattern ANY_NUMBER = Pattern.compile("(\\d+)");

    private AudioFileOrdering() {
    }

    /**
     * 按曲序排序。
     *
     * <p><b>必须预先算好排序键</b>：{@code key()} 会读取音频标签（磁盘 I/O），
     * 而 {@code Comparator.comparing(...)} 每次比较都会重新调用提取函数，
     * 直接用作 Comparator 会造成 O(n log n) 次标签读取。
     * 这里采用 decorate-sort-undecorate，每个文件只读一次。</p>
     */
    public static void sort(java.util.List<File> files) {
        if (files == null || files.size() < 2) {
            return;
        }
        Map<File, SortKey> keys = new IdentityHashMap<>(files.size());
        for (File file : files) {
            keys.put(file, key(file));
        }
        files.sort(Comparator.comparing(keys::get));
    }

    /**
     * 返回一个会缓存排序键的 Comparator。
     *
     * <p>返回的实例持有内部缓存，<b>不是无状态的</b>，不应长期持有或跨线程共享；
     * 一次排序用完即弃。一般情况请直接用 {@link #sort(java.util.List)}。</p>
     */
    public static Comparator<File> comparator() {
        Map<File, SortKey> cache = new IdentityHashMap<>();
        return Comparator.comparing(file -> cache.computeIfAbsent(file, AudioFileOrdering::key));
    }

    private static SortKey key(File file) {
        Integer disc = null;
        Integer track = null;
        try {
            Tag tag = AudioFileIO.read(file).getTag();
            if (tag != null) {
                disc = parseTagNumber(tag.getFirst(FieldKey.DISC_NO));
                track = parseTagNumber(tag.getFirst(FieldKey.TRACK));
            }
        } catch (Exception ignored) {
            // 标签不可读时按文件系统名称回退，排序本身不应阻断处理。
        }

        String parentName = file.getParentFile() != null ? file.getParentFile().getName() : "";
        if (disc == null) {
            disc = findNumber(parentName);
        }
        if (track == null) {
            track = leadingNumber(file.getName());
        }

        return new SortKey(
            disc != null ? disc : Integer.MAX_VALUE,
            track != null ? track : Integer.MAX_VALUE,
            naturalize(parentName),
            naturalize(file.getName()),
            file.getAbsolutePath().toLowerCase()
        );
    }

    private static Integer parseTagNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher matcher = LEADING_NUMBER.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer leadingNumber(String value) {
        Matcher matcher = LEADING_NUMBER.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer findNumber(String value) {
        Matcher matcher = ANY_NUMBER.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 将数字补零后再比较，实现简单、稳定的数字感知排序。 */
    private static String naturalize(String value) {
        String lower = value == null ? "" : value.toLowerCase();
        Matcher matcher = ANY_NUMBER.matcher(lower);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement;
            try {
                replacement = String.format("%012d", Long.parseLong(matcher.group(1)));
            } catch (NumberFormatException e) {
                replacement = matcher.group(1);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private record SortKey(int disc, int track, String parent, String fileName, String path)
            implements Comparable<SortKey> {
        @Override
        public int compareTo(SortKey other) {
            int result = Integer.compare(disc, other.disc);
            if (result != 0) return result;
            result = Integer.compare(track, other.track);
            if (result != 0) return result;
            result = parent.compareTo(other.parent);
            if (result != 0) return result;
            result = fileName.compareTo(other.fileName);
            if (result != 0) return result;
            return path.compareTo(other.path);
        }
    }
}
