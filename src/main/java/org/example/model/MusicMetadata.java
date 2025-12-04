package org.example.model;

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

    // Fields specific to MusicBrainzClient
    private String releaseGroupId;
    private String coverArtUrl;
    private int score;
    private int trackCount;

    // Fields specific to TagWriterService
    private byte[] coverArtData;

    @Override
    public String toString() {
        return String.format("MusicMetadata{title='%s', artist='%s', albumArtist='%s', album='%s', score=%d}",
            title, artist, albumArtist, album, score);
    }
}