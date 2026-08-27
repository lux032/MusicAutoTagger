package com.lux032.musicautotagger.util;

import com.lux032.musicautotagger.model.MusicMetadata;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 标签可用性评估（阶段八 #23 / #24）
 *
 * 背景：第 2 筐（指纹识别失败但「上传者整理过」）的准入原本**完全由封面单独决定**，
 * 而 {@code cover.jpg + Track 01 / Unknown Artist} 的种子非常常见。
 * 这类文件进了 partialDirectory，Plex 会读出一堆 {@code Unknown Artist}，
 * **反而污染音乐库**——比不处理更糟。
 *
 * 因此在封面之外补一道「Plex 可读性」判定，两者**同时**满足才准入：
 *   - ≥ coverage 的文件有非空且非占位的 album
 *   - ≥ coverage 的文件有 tracknumber（或文件名带数字前缀）
 *   - ≥ coverage 的文件有非占位的 artist **或** albumArtist
 *
 * <p><b>关于 {@code Various Artists}</b>：它被当作占位值，这是故意的。
 * 正常合辑的每首歌 {@code ARTIST} 是真实歌手，不会命中；
 * 只有「所有曲目的 ARTIST 都写成 Various Artists」才会失败，
 * 而那正是应该被挡的——Plex 会把整张合辑归到一个假艺术家下。
 */
@Slf4j
public final class TagQualityEvaluator {

    /** 文件名数字前缀，如 "01 - Title.flac" / "03.Title.mp3" / "1_title.flac" */
    private static final Pattern FILENAME_TRACK_PREFIX =
        Pattern.compile("^\\s*\\(?\\[?\\d{1,3}\\)?\\]?\\s*[-._)\\]\\s]");

    /**
     * 占位值：大小写不敏感，整串匹配。
     *
     * 中文占位词写成 {@code \\uXXXX} 转义：这里是匹配规则的一部分，
     * 不能因为构建方式（手写 javac / IDE 重新编码）不同而静默失效。
     */
    private static final Pattern PLACEHOLDER = Pattern.compile(
        "^(unknown|unknown\\s+(artist|album|title|album\\s*artist)|various|various\\s+artists|va"
      + "|untitled|no\\s+(album|artist|title)|n/?a|none|null|undefined"
      + "|track\\s*\\d*|audiotrack\\s*\\d*|cd\\s*\\d*|disc\\s*\\d*|disk\\s*\\d*"
      // 未知 / 未知专辑 / 未知艺术家 / 未命名专辑 / 无标题 / 无
      + "|\u672a\u77e5(\u4e13\u8f91|\u827a\u672f\u5bb6|\u540d)?"
      + "|\u672a\u547d\u540d(\u4e13\u8f91)?"
      + "|\u65e0(\u6807\u9898|\u4e13\u8f91)?)$");

    private TagQualityEvaluator() {
    }

    /**
     * 是否是「能给人看的」值：非空、非占位、非纯数字。
     */
    public static boolean isMeaningful(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (PLACEHOLDER.matcher(lower).matches()) {
            return false;
        }
        // 必须含至少一个字母或数字（排掉 "---" / "..." 这类纯符号占位）。
        //
        // 注：**纯数字不当作占位值**。`1989` / `21` / `25` / `808s` 都是真实专辑名，
        // 而真正的占位值（`Track 01` / `CD1`）已经被上面的模式盖住了。
        return normalized.matches(".*[\\p{L}\\p{N}].*");
    }

    public static boolean isPlaceholder(String value) {
        return !isMeaningful(value);
    }

    /** 曲目号：标签里有，或文件名带数字前缀（Plex 也认后者） */
    public static boolean hasTrackNumberHint(File file, String trackNo) {
        if (trackNo != null) {
            String normalized = trackNo.trim();
            // "0" / "00" 不算：一堆文件都写 0 的话排序仍然是乱的
            if (normalized.matches("^\\d+(/\\d+)?$") && !normalized.matches("^0+(/\\d+)?$")) {
                return true;
            }
        }
        return file != null && FILENAME_TRACK_PREFIX.matcher(file.getName()).find();
    }

    /**
     * Plex 可读性评估结果。
     *
     * {@code readable == false} 时 {@link #getReasons()} 给出具体缺什么，
     * 便于在日志里解释「为什么这批有封面的文件仍然没进 partialDirectory」。
     */
    public static class Readiness {
        private final boolean readable;
        private final int fileCount;
        private final double albumCoverage;
        private final double trackNoCoverage;
        private final double artistCoverage;
        private final List<String> reasons;

        Readiness(boolean readable, int fileCount, double albumCoverage,
                  double trackNoCoverage, double artistCoverage, List<String> reasons) {
            this.readable = readable;
            this.fileCount = fileCount;
            this.albumCoverage = albumCoverage;
            this.trackNoCoverage = trackNoCoverage;
            this.artistCoverage = artistCoverage;
            this.reasons = reasons;
        }

        public boolean isReadable() {
            return readable;
        }

        public int getFileCount() {
            return fileCount;
        }

        public double getAlbumCoverage() {
            return albumCoverage;
        }

        public double getTrackNoCoverage() {
            return trackNoCoverage;
        }

        public double getArtistCoverage() {
            return artistCoverage;
        }

        public List<String> getReasons() {
            return reasons;
        }

        public String describe() {
            return String.format(Locale.ROOT,
                "album=%.0f%%, track#=%.0f%%, artist=%.0f%% (%d 个文件)%s",
                albumCoverage * 100, trackNoCoverage * 100, artistCoverage * 100, fileCount,
                reasons.isEmpty() ? "" : " | " + String.join("; ", reasons));
        }
    }

    /**
     * 对一批文件做 Plex 可读性评估。
     *
     * @param files       待评估文件（一整张专辑，或单个散落文件）
     * @param tagReader   读标签的方法；返回 null 视为「读不出标签」
     * @param minCoverage 覆盖率阈值（建议 0.8）
     */
    public static Readiness evaluate(List<File> files,
                                     Function<File, MusicMetadata> tagReader,
                                     double minCoverage) {
        List<String> reasons = new ArrayList<>();
        if (files == null || files.isEmpty()) {
            reasons.add("没有音频文件");
            return new Readiness(false, 0, 0, 0, 0, reasons);
        }

        int total = files.size();
        int albumOk = 0;
        int trackNoOk = 0;
        int artistOk = 0;

        for (File file : files) {
            MusicMetadata metadata = null;
            try {
                metadata = tagReader.apply(file);
            } catch (Exception e) {
                log.debug("读取标签失败: {} - {}", file.getName(), e.getMessage());
            }

            String album = metadata == null ? null : metadata.getAlbum();
            String trackNo = metadata == null ? null : metadata.getTrackNo();

            if (isMeaningful(album)) {
                albumOk++;
            }
            // 审查修正 C4：必须**分别**判断 artist 与 albumArtist。
            // 原实现用 firstNonEmpty(artist, albumArtist)，只看是否为空、不看是否占位：
            //   ARTIST=Unknown Artist + ALBUM_ARTIST=真名
            // 会选中前者并判为不可用，有效的 ALBUM_ARTIST 根本没机会参与；
            // 这也与 TagWriterService.hasPartialTags() 的分别检查不一致。
            if (metadata != null
                && (isMeaningful(metadata.getArtist()) || isMeaningful(metadata.getAlbumArtist()))) {
                artistOk++;
            }
            // 标签读不出来时仍可能靠文件名前缀排序，这一项不因读取失败直接判死
            if (hasTrackNumberHint(file, trackNo)) {
                trackNoOk++;
            }
        }

        double albumCoverage = (double) albumOk / total;
        double trackNoCoverage = (double) trackNoOk / total;
        double artistCoverage = (double) artistOk / total;

        if (albumCoverage < minCoverage) {
            reasons.add(String.format(Locale.ROOT, "专辑名缺失或为占位值 (%d/%d)", albumOk, total));
        }
        if (trackNoCoverage < minCoverage) {
            reasons.add(String.format(Locale.ROOT, "曲目号缺失且文件名无数字前缀 (%d/%d)", trackNoOk, total));
        }
        if (artistCoverage < minCoverage) {
            reasons.add(String.format(Locale.ROOT, "艺术家缺失或为占位值 (%d/%d)", artistOk, total));
        }

        return new Readiness(reasons.isEmpty(), total,
            albumCoverage, trackNoCoverage, artistCoverage, reasons);
    }
}
