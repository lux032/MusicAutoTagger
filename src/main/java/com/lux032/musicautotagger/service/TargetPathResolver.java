package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.util.FileNameSanitizer;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 音频归档目标路径与文件名解析器（从 TagWriterService 抽离的纯路径/命名职责）。
 * 负责按「专辑艺术家 / 专辑 / [碟号.曲目号] 艺术家 - 曲名.ext」计算归档路径、曲目号前缀与冲突后缀。
 */
@Slf4j
public class TargetPathResolver {

    private static final Pattern AUDIO_EXT_SUFFIX =
        Pattern.compile("(?i)\\.(mp3|flac|m4a|ogg|wav|mp4|aac|opus|ape|tta|alac|wma|aiff|aif|dsf|dff|wv)$");
    private static final Pattern DISC_TRACK_PREFIX =
        Pattern.compile("^(\\d+\\.\\d+)\\s+.+$");
    private static final Pattern TRACK_DOT_PREFIX =
        Pattern.compile("^(\\d+)\\.\\s*(.+)$");

    private final MusicConfig config;

    public TargetPathResolver(MusicConfig config) {
        this.config = config;
    }

    /**
     * 计算目标文件路径（不创建目录，目录创建与权限设置由调用方/发布器统一负责）。
     */
    public File resolveTargetFile(File sourceFile, MusicMetadata metadata, Path outputRoot) {
        String fileName = sourceFile.getName();
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileName.substring(dotIndex);
        }

        String newFileName = fileName;
        if (config != null && config.isAutoRename() && metadata.getArtist() != null && metadata.getTitle() != null) {
            String trackPrefix = metadata.isExtraTrack() ? "" : buildTrackPrefixFromMetadata(metadata);
            if (trackPrefix.isEmpty() && !metadata.isExtraTrack()) {
                trackPrefix = extractTrackPrefix(fileName);
            }

            String artist = sanitizeFileName(metadata.getArtist());
            String title = sanitizeFileName(metadata.getTitle());

            if (trackPrefix != null && !trackPrefix.isEmpty()) {
                newFileName = trackPrefix + artist + " - " + title + extension;
            } else {
                newFileName = artist + " - " + title + extension;
            }
            newFileName = FileNameSanitizer.limitFileName(newFileName, extension);
        }

        String folderArtist = metadata.getAlbumArtist();
        if (folderArtist == null || folderArtist.isEmpty()) {
            folderArtist = metadata.getArtist();
        }

        Path targetPath;
        if (folderArtist != null && !folderArtist.isEmpty()) {
            String artistDir = sanitizeFileName(folderArtist);
            if (metadata.getAlbum() != null && !metadata.getAlbum().isEmpty()) {
                String albumDir = sanitizeFileName(metadata.getAlbum());
                targetPath = outputRoot.resolve(artistDir).resolve(albumDir).resolve(newFileName);
                log.info("目标路径: {}/{}/{} (专辑艺术家: {})", artistDir, albumDir, newFileName, folderArtist);
            } else {
                targetPath = outputRoot.resolve(artistDir).resolve(newFileName);
                log.info("目标路径: {}/{} (无专辑信息)", artistDir, newFileName);
            }
        } else {
            targetPath = outputRoot.resolve(newFileName);
            log.warn("目标路径: {} (无艺术家信息)", newFileName);
        }

        return targetPath.toFile();
    }

    /**
     * 从 metadata 的碟号和曲目号构建文件名前缀：多碟 "2.05 "，单碟 "05. "。
     */
    public String buildTrackPrefixFromMetadata(MusicMetadata metadata) {
        if (metadata == null) {
            return "";
        }
        String discNo = metadata.getDiscNo();
        String trackNo = metadata.getTrackNo();
        if (trackNo == null || trackNo.isEmpty()) {
            return "";
        }
        try {
            int track = Integer.parseInt(trackNo.split("/")[0].trim());
            if (discNo != null && !discNo.isEmpty()) {
                int disc = Integer.parseInt(discNo.split("/")[0].trim());
                String prefix = String.format("%d.%02d ", disc, track);
                log.info("使用 metadata 构建文件名前缀: {} (碟号: {}, 曲目号: {})", prefix, disc, track);
                return prefix;
            } else {
                String prefix = String.format("%02d. ", track);
                log.info("使用 metadata 构建文件名前缀: {} (曲目号: {})", prefix, track);
                return prefix;
            }
        } catch (NumberFormatException e) {
            log.warn("无法解析碟号/曲目号: discNo={}, trackNo={}", discNo, trackNo);
            return "";
        }
    }

    /**
     * 从文件名中提取曲目编号前缀（先剥离音频扩展名，避免 "01.flac" 误截出 "01.f" 或 "01." 越界）。
     */
    public static String extractTrackPrefix(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        String stem = AUDIO_EXT_SUFFIX.matcher(fileName).replaceFirst("");
        if (stem.isEmpty()) {
            return "";
        }
        Matcher discTrackMatcher = DISC_TRACK_PREFIX.matcher(stem);
        if (discTrackMatcher.matches()) {
            return discTrackMatcher.group(1) + " ";
        }
        Matcher trackDotMatcher = TRACK_DOT_PREFIX.matcher(stem);
        if (trackDotMatcher.matches()) {
            String rest = trackDotMatcher.group(2);
            if (!rest.isEmpty() && Character.isDigit(rest.charAt(0))) {
                int numEnd = 0;
                while (numEnd < rest.length() && Character.isDigit(rest.charAt(numEnd))) {
                    numEnd++;
                }
                if (numEnd < rest.length() && !rest.substring(numEnd).isBlank()) {
                    return trackDotMatcher.group(1) + "." + rest.substring(0, numEnd) + " ";
                }
                return "";
            }
            if (!rest.isBlank()) {
                return trackDotMatcher.group(1) + ". ";
            }
        }
        return "";
    }

    public static File withConflictSuffix(File targetFile, int n) {
        String name = targetFile.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return new File(targetFile.getParentFile(), base + " (" + n + ")" + ext);
    }

    public static String sanitizeFileName(String fileName) {
        return FileNameSanitizer.sanitize(fileName);
    }
}
