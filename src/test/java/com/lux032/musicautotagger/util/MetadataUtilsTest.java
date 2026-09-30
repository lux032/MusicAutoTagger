package com.lux032.musicautotagger.util;

import com.lux032.musicautotagger.model.MusicMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class MetadataUtilsTest {

    @Test
    void mergeKeepsSourceArrangerWhenNewDataHasNone() {
        MusicMetadata source = new MusicMetadata();
        source.setArranger("Source Arranger");
        MusicMetadata identified = new MusicMetadata();
        identified.setTitle("Song");

        MusicMetadata merged = MetadataUtils.mergeMetadata(source, identified);

        assertEquals("Source Arranger", merged.getArranger());
    }

    @Test
    void mergePrefersNewArrangerOverSource() {
        MusicMetadata source = new MusicMetadata();
        source.setArranger("Source Arranger");
        MusicMetadata identified = new MusicMetadata();
        identified.setArranger("MusicBrainz Arranger");

        assertEquals("MusicBrainz Arranger", MetadataUtils.mergeMetadata(source, identified).getArranger());
    }

    @Test
    void quickScanMetadataKeepsSourceCredits() {
        MusicMetadata source = new MusicMetadata();
        source.setComposer("C");
        source.setLyricist("L");
        source.setArranger("A");

        MusicMetadata md = MetadataUtils.createMetadataFromQuickScan(
            source, "Album", "Album Artist", "rg-id", "2024", "01 Song.flac");

        assertEquals("C", md.getComposer());
        assertEquals("L", md.getLyricist());
        assertEquals("A", md.getArranger());
    }

    @Test
    void preserveTrackCreditsFillsForcedReleaseMetadataGaps() {
        // 模拟 Recording 查询结果（含创作人）与 Release 强制匹配结果（不含曲目级关系）
        MusicMetadata recording = new MusicMetadata();
        recording.setComposer("Composer");
        recording.setLyricist("Lyricist");
        recording.setArranger("Arranger");
        recording.setLyrics("la la la");
        recording.setGenres(List.of("pop"));
        recording.setTitle("Recording Title");

        MusicMetadata forced = new MusicMetadata();
        forced.setTitle("Release Title");
        forced.setTrackNo("3");

        MusicMetadata result = MetadataUtils.preserveTrackCredits(recording, forced);

        assertSame(forced, result);
        assertEquals("Composer", result.getComposer());
        assertEquals("Lyricist", result.getLyricist());
        assertEquals("Arranger", result.getArranger());
        assertEquals("la la la", result.getLyrics());
        assertEquals(List.of("pop"), result.getGenres());
        // 曲目定位字段仍以强制匹配结果为准
        assertEquals("Release Title", result.getTitle());
        assertEquals("3", result.getTrackNo());
    }

    @Test
    void preserveTrackCreditsDoesNotOverrideExistingValues() {
        MusicMetadata from = new MusicMetadata();
        from.setComposer("Old Composer");
        from.setArranger("Old Arranger");
        from.setGenres(List.of("rock"));

        MusicMetadata target = new MusicMetadata();
        target.setComposer("File Composer");
        target.setArranger("");
        target.setGenres(List.of("jazz"));

        MetadataUtils.preserveTrackCredits(from, target);

        assertEquals("File Composer", target.getComposer());
        assertEquals("Old Arranger", target.getArranger());
        assertEquals(List.of("jazz"), target.getGenres());
    }

    @Test
    void overlayTrackCreditsPrefersMusicBrainzAndFallsBackToFileTags() {
        MusicMetadata musicBrainz = new MusicMetadata();
        musicBrainz.setComposer("MB Composer");
        musicBrainz.setArranger("MB Arranger");
        musicBrainz.setLyricist("");
        musicBrainz.setGenres(List.of("pop"));

        MusicMetadata fileTags = new MusicMetadata();
        fileTags.setComposer("File Composer");
        fileTags.setLyricist("File Lyricist");
        fileTags.setLyrics("file lyrics");
        fileTags.setGenres(List.of("rock"));

        MusicMetadata result = MetadataUtils.overlayTrackCredits(musicBrainz, fileTags);

        assertSame(fileTags, result);
        assertEquals("MB Composer", result.getComposer());
        assertEquals("MB Arranger", result.getArranger());
        assertEquals("File Lyricist", result.getLyricist());
        assertEquals("file lyrics", result.getLyrics());
        assertEquals(List.of("pop"), result.getGenres());
        // 来源对象不应被修改（它是队列持久化的数据）
        assertEquals("", musicBrainz.getLyricist());
        assertNull(musicBrainz.getLyrics());
    }

    @Test
    void overlayTrackCreditsToleratesNulls() {
        MusicMetadata target = new MusicMetadata();
        target.setComposer("File Composer");
        assertSame(target, MetadataUtils.overlayTrackCredits(null, target));
        assertEquals("File Composer", target.getComposer());
        assertNull(MetadataUtils.overlayTrackCredits(new MusicMetadata(), null));
    }

    @Test
    void preserveTrackCreditsToleratesNulls() {
        MusicMetadata target = new MusicMetadata();
        assertSame(target, MetadataUtils.preserveTrackCredits(null, target));
        assertNull(target.getComposer());
        assertNull(MetadataUtils.preserveTrackCredits(new MusicMetadata(), null));
    }

    @Test
    void copyWithoutHeavyFieldsKeepsArranger() {
        MusicMetadata md = new MusicMetadata();
        md.setArranger("Arranger");
        md.setCoverArtData(new byte[] {1, 2, 3});

        MusicMetadata copy = md.copyWithoutHeavyFields();

        assertEquals("Arranger", copy.getArranger());
        assertNull(copy.getCoverArtData());
    }
}
