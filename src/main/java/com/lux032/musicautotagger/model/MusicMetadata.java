package com.lux032.musicautotagger.model;

import lombok.Data;
import java.util.List;

/**
 * 统一的音乐元数据模型类
 */
@Data
public class MusicMetadata {
    // Common fields from both services
    private String recordingId;
    private String title;
    private String artist;
    private String albumArtist;
    private String album;
    private String releaseDate;
    private List<String> genres;
    private String composer;
    private String lyricist;
    private String lyrics;
    private String discNo;
    private String trackNo;
    private Integer duration; // 时长（秒）

    // Fields specific to MusicBrainzClient
    private String releaseGroupId;
    private String releaseId;  // 具体的 Release ID，用于确保版本一致性
    private String coverArtUrl;
    private int score;
    private int trackCount;

    // Fields specific to TagWriterService
    private byte[] coverArtData;

    /**
     * 设置专辑艺术家，自动检测多人情况并规范化为 "Various Artists"
     * @param albumArtist 专辑艺术家
     */
    public void setAlbumArtist(String albumArtist) {
        this.albumArtist = normalizeAlbumArtist(albumArtist);
    }

    /**
     * 规范化专辑艺术家：如果是多人、未知或空则返回 "Various Artists"
     * @param albumArtist 原始专辑艺术家
     * @return 规范化后的专辑艺术家
     */
    public static String normalizeAlbumArtist(String albumArtist) {
        // 如果是 null、空字符串或 "Unknown Artist"，返回 "Various Artists"
        if (albumArtist == null || albumArtist.isEmpty() ||
            "Unknown Artist".equalsIgnoreCase(albumArtist) ||
            "Unknown".equalsIgnoreCase(albumArtist)) {
            return "Various Artists";
        }

        // 已经是 Various Artists，直接返回
        if ("Various Artists".equalsIgnoreCase(albumArtist)) {
            return "Various Artists";
        }

        // 检测多人情况：包含逗号、顿号、&、and 等分隔符
        if (albumArtist.contains(", ") ||
            albumArtist.contains("、") ||
            albumArtist.contains(" & ") ||
            albumArtist.contains("; ")) {
            return "Various Artists";
        }

        return albumArtist;
    }

    @Override
    public String toString() {
        return String.format("MusicMetadata{title='%s', artist='%s', albumArtist='%s', album='%s', score=%d}",
            title, artist, albumArtist, album, score);
    }
}
