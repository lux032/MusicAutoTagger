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
    private String originalReleaseDate;
    private String originalYear;
    private String artistId;
    private String albumArtistId;
    private String releaseTrackId;
    private String artistSort;
    private String albumArtistSort;
    private String releaseStatus;
    private String releaseCountry;
    private String mediaFormat;
    private String script;
    private String barcode;
    private String catalogNumber;
    private String recordLabel;
    private String discTotal;
    private String trackTotal;
    /** 专辑已锁定或明确未确定时，先删除所有专辑级标签，再写入当前非空值。 */
    private boolean clearAlbumLevelTags;
    /** MusicBrainz Release Group 主类型，统一使用小写值（album、single、ep 等）。 */
    private String releaseType;
    /** 显式删除源文件中遗留的专辑类型与合辑标志。 */
    private boolean clearReleaseType;
    /** MusicBrainz Release Group 的 secondary-types 是否包含 Compilation。 */
    private boolean compilation;
    /**
     * 显式「清空年份」标志。
     * releaseDate 为空只表示「不更新」，无法表达「删除原有年份」。
     * 专辑未确定时必须删除原文件里可能存在的旧专辑年份，否则会出现
     * 「专辑名 = 新精选集，年份 = 1998（旧专辑）」这种矛盾结果。
     */
    private boolean clearReleaseDate;
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
     * 复制除封面二进制之外的全部字段，供人工审核队列安全持久化。
     */
    public MusicMetadata copyWithoutHeavyFields() {
        MusicMetadata copy = new MusicMetadata();
        copy.recordingId = recordingId;
        copy.title = title;
        copy.artist = artist;
        copy.albumArtist = albumArtist;
        copy.album = album;
        copy.releaseDate = releaseDate;
        copy.originalReleaseDate = originalReleaseDate;
        copy.originalYear = originalYear;
        copy.artistId = artistId;
        copy.albumArtistId = albumArtistId;
        copy.releaseTrackId = releaseTrackId;
        copy.artistSort = artistSort;
        copy.albumArtistSort = albumArtistSort;
        copy.releaseStatus = releaseStatus;
        copy.releaseCountry = releaseCountry;
        copy.mediaFormat = mediaFormat;
        copy.script = script;
        copy.barcode = barcode;
        copy.catalogNumber = catalogNumber;
        copy.recordLabel = recordLabel;
        copy.discTotal = discTotal;
        copy.trackTotal = trackTotal;
        copy.clearAlbumLevelTags = clearAlbumLevelTags;
        copy.releaseType = releaseType;
        copy.clearReleaseType = clearReleaseType;
        copy.compilation = compilation;
        copy.clearReleaseDate = clearReleaseDate;
        copy.genres = genres == null ? null : List.copyOf(genres);
        copy.composer = composer;
        copy.lyricist = lyricist;
        copy.lyrics = lyrics;
        copy.discNo = discNo;
        copy.trackNo = trackNo;
        copy.duration = duration;
        copy.releaseGroupId = releaseGroupId;
        copy.releaseId = releaseId;
        copy.coverArtUrl = coverArtUrl;
        copy.score = score;
        copy.trackCount = trackCount;
        return copy;
    }

    /** 专辑艺术家模型字段只做赋值；业务归一化由 AlbumArtistPolicy 负责。 */
    public void setAlbumArtist(String albumArtist) {
        this.albumArtist = albumArtist;
    }

    /**
     * @deprecated 使用 AlbumArtistPolicy.fallbackUnknown；此方法仅保留空值兼容兜底。
     */
    @Deprecated
    public static String normalizeAlbumArtist(String albumArtist) {
        if (albumArtist == null || albumArtist.trim().isEmpty()
            || "Unknown Artist".equalsIgnoreCase(albumArtist.trim())
            || "Unknown".equalsIgnoreCase(albumArtist.trim())) {
            return "Various Artists";
        }
        return albumArtist.trim();
    }

    @Override
    public String toString() {
        return String.format("MusicMetadata{title='%s', artist='%s', albumArtist='%s', album='%s', score=%d}",
            title, artist, albumArtist, album, score);
    }
}
