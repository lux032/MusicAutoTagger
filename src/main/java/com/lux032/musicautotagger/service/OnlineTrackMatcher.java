package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把联网候选的曲目表逐首匹配到本地文件。
 *
 * 早期实现直接按下标配对（files.get(i) ↔ tracks.get(i)），但本地文件按绝对路径排序，
 * 而联网曲目表按碟号/曲号排序，"Disc 10" 会排在 "Disc 2" 前、Bonus track 位置也不固定，
 * 一旦错位就会造成整张专辑标题串位。这里改为按证据打分匹配，
 * 只有达到置信度阈值的曲目才允许覆盖本地标签。
 */
@Slf4j
public class OnlineTrackMatcher {

    /** 低于该置信度的曲目一律保留原标签，并且不计入覆盖率 */
    public static final double MIN_CONFIDENCE = 0.6;

    private final TagWriterService tagWriter;

    public OnlineTrackMatcher(TagWriterService tagWriter) {
        this.tagWriter = tagWriter;
    }

    /**
     * 就地写入 track.matchedFilePath / track.matchConfidence，并返回达到阈值的匹配数。
     * durations 可为 null；为 null 时不使用时长证据。
     */
    public int match(ReviewItem.OnlineCandidate candidate, List<File> files, List<Integer> durations) {
        List<ReviewItem.OnlineTrack> tracks = candidate == null ? null : candidate.getTracks();
        if (tracks == null || tracks.isEmpty() || files == null || files.isEmpty()) {
            return 0;
        }

        List<Local> locals = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            Integer duration = durations != null && i < durations.size() ? durations.get(i) : null;
            locals.add(new Local(files.get(i), tagWriter == null ? null : tagWriter.readTags(files.get(i)), duration));
        }

        // 全对全打分后贪心取最优，避免顺序依赖
        List<Pair> pairs = new ArrayList<>();
        for (ReviewItem.OnlineTrack track : tracks) {
            track.setMatchedFilePath(null);
            track.setMatchConfidence(0);
            for (Local local : locals) {
                double score = score(track, local);
                if (score > 0) {
                    pairs.add(new Pair(track, local, score));
                }
            }
        }
        pairs.sort((a, b) -> Double.compare(b.score, a.score));

        Set<ReviewItem.OnlineTrack> usedTracks = new HashSet<>();
        Set<String> usedFiles = new HashSet<>();
        int confident = 0;
        for (Pair pair : pairs) {
            if (usedTracks.contains(pair.track) || usedFiles.contains(pair.local.path())) {
                continue;
            }
            usedTracks.add(pair.track);
            usedFiles.add(pair.local.path());
            pair.track.setMatchedFilePath(pair.local.path());
            pair.track.setMatchConfidence(round(pair.score));
            if (pair.score >= MIN_CONFIDENCE) {
                confident++;
            }
        }

        candidate.setTrackCoverage(round((double) confident / files.size()));
        return confident;
    }

    /**
     * 证据加权：碟号+曲号是最强信号，其次是标题/文件名，时长只作辅助确认。
     * 完全没有任何可用证据时返回 0，宁可不匹配也不靠位置猜。
     */
    private double score(ReviewItem.OnlineTrack track, Local local) {
        double score = 0;
        boolean hasEvidence = false;

        Integer localTrackNo = parseNumber(local.trackNo());
        if (track.getTrackNo() > 0 && localTrackNo != null) {
            hasEvidence = true;
            if (track.getTrackNo() == localTrackNo) {
                score += 0.40;
                Integer localDiscNo = parseNumber(local.discNo());
                // 单碟专辑常常不写碟号，缺失时不惩罚
                if (track.getDiscNo() > 0 && localDiscNo != null) {
                    score += track.getDiscNo() == localDiscNo ? 0.10 : -0.35;
                }
            } else {
                score -= 0.30;
            }
        }

        String trackTitle = normalize(track.getTitle());
        if (!trackTitle.isEmpty()) {
            double best = 0;
            String localTitle = normalize(local.title());
            if (!localTitle.isEmpty()) {
                best = similarity(trackTitle, localTitle);
            }
            // 文件名常常比标签更可靠（标签可能为空或是乱码）
            String fileName = normalize(stripExtension(local.file().getName()));
            if (!fileName.isEmpty()) {
                double byName = fileName.contains(trackTitle) ? 1.0 : similarity(trackTitle, fileName);
                best = Math.max(best, byName);
            }
            if (best > 0) {
                hasEvidence = true;
                score += 0.40 * best;
            }
        }

        if (track.getDuration() != null && track.getDuration() > 0
            && local.duration() != null && local.duration() > 0) {
            int delta = Math.abs(track.getDuration() - local.duration());
            double tolerance = Math.max(15, track.getDuration() * 0.08);
            if (delta <= tolerance) {
                hasEvidence = true;
                score += 0.20 * (1 - delta / tolerance);
            } else {
                score -= 0.25;
            }
        }

        if (!hasEvidence) {
            return 0;
        }
        return Math.max(0, Math.min(1, score));
    }

    private Integer parseNumber(String value) {
        if (value == null) return null;
        // 兼容 "3/12" 这类写法
        String head = value.trim().split("/")[0].trim();
        if (head.isEmpty()) return null;
        try {
            return Integer.parseInt(head);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 归一化：去掉大小写、空白与标点，避免全半角/连字符差异干扰比对 */
    private String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s\\u3000-\\u303F\\uFF00-\\uFFEF]+", "");
    }

    /** 归一化编辑距离相似度，低于 0.5 视为不相关 */
    private double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        int distance = levenshtein(a, b);
        double ratio = 1.0 - (double) distance / Math.max(a.length(), b.length());
        return ratio < 0.5 ? 0 : ratio;
    }

    private int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private record Local(File file, MusicMetadata metadata, Integer durationOverride) {
        String path() { return file.getAbsolutePath(); }
        String title() { return metadata == null ? null : metadata.getTitle(); }
        String trackNo() { return metadata == null ? null : metadata.getTrackNo(); }
        String discNo() { return metadata == null ? null : metadata.getDiscNo(); }
        Integer duration() {
            if (durationOverride != null && durationOverride > 0) return durationOverride;
            return metadata == null ? null : metadata.getDuration();
        }
    }

    private record Pair(ReviewItem.OnlineTrack track, Local local, double score) { }
}
