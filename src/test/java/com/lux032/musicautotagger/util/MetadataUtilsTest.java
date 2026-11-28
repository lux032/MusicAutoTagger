package com.lux032.musicautotagger.util;

import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void resolveOnlineTrackCreditsAppliesIndependentFourTierPriority() {
        MusicMetadata mb = new MusicMetadata();
        mb.setRecordingId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        mb.setComposer("MB Composer");
        mb.setComposers(List.of("MB Composer"));
        mb.setComposerIds(List.of("11111111-1111-4111-8111-111111111111"));

        MusicMetadata local = new MusicMetadata();
        local.setComposer("Local Composer");
        local.setLyricist("Local Lyricist");
        local.setLyricists(List.of("Local Lyricist"));

        ReviewItem.OnlineTrack online = new ReviewItem.OnlineTrack();
        online.setComposers(List.of("Online Composer"));
        online.setLyricists(List.of("Online Lyricist"));
        online.setArrangers(List.of("梶浦由記", "森空青"));

        MetadataUtils.ResolvedTrackCredits resolved =
            MetadataUtils.resolveOnlineTrackCredits(null, mb, local, online, true);

        // Composer: MB wins and retains MBID
        assertEquals(MetadataUtils.CreditSource.MUSICBRAINZ, resolved.composer().source());
        assertEquals("MB Composer", resolved.composer().display());
        assertEquals(List.of("11111111-1111-4111-8111-111111111111"), resolved.composer().mbids());
        // Lyricist: Local beats Online
        assertEquals(MetadataUtils.CreditSource.LOCAL, resolved.lyricist().source());
        assertEquals("Local Lyricist", resolved.lyricist().display());
        // Arranger: Online fills blank with no MBID
        assertEquals(MetadataUtils.CreditSource.ONLINE, resolved.arranger().source());
        assertEquals("梶浦由記, 森空青", resolved.arranger().display());
        assertEquals(List.of("梶浦由記", "森空青"), resolved.arranger().names());
        assertNull(resolved.arranger().mbids());

        // Low-confidence online track must be ignored
        MetadataUtils.ResolvedTrackCredits lowConf =
            MetadataUtils.resolveOnlineTrackCredits(null, mb, local, online, false);
        assertEquals(MetadataUtils.CreditSource.NONE, lowConf.arranger().source());
        assertNull(lowConf.arranger().display());
    }

    @Test
    void resolveAndApplyManualOverrideAndExplicitClear() {
        MusicMetadata mb = new MusicMetadata();
        mb.setComposer("MB Composer");
        mb.setComposers(List.of("MB Composer"));
        mb.setComposerIds(List.of("11111111-1111-4111-8111-111111111111"));
        mb.setLyricist("MB Lyricist");
        mb.setLyricists(List.of("MB Lyricist"));
        mb.setLyricistIds(List.of("22222222-2222-4222-8222-222222222222"));

        ReviewItem.TrackCreditsOverride manual = new ReviewItem.TrackCreditsOverride();
        manual.setComposers(List.of("澤野弘之", "澤野弘之"));
        manual.setLyricists(List.of()); // explicit clear
        manual.setArrangers(null);      // untouched

        ReviewItem.OnlineTrack online = new ReviewItem.OnlineTrack();
        online.setArrangers(List.of("Online Arranger"));

        MetadataUtils.ResolvedTrackCredits resolved =
            MetadataUtils.resolveOnlineTrackCredits(manual, mb, null, online, true);

        assertEquals(MetadataUtils.CreditSource.MANUAL, resolved.composer().source());
        assertEquals("澤野弘之", resolved.composer().display());
        assertNull(resolved.composer().mbids());
        assertFalse(resolved.composer().clear());

        assertEquals(MetadataUtils.CreditSource.MANUAL, resolved.lyricist().source());
        assertTrue(resolved.lyricist().clear());
        assertNull(resolved.lyricist().display());

        assertEquals(MetadataUtils.CreditSource.ONLINE, resolved.arranger().source());
        assertEquals("Online Arranger", resolved.arranger().display());

        MusicMetadata target = new MusicMetadata();
        target.setComposer("Old");
        target.setComposerIds(List.of("11111111-1111-4111-8111-111111111111"));
        target.setLyricist("Old L");
        target.setLyricistIds(List.of("22222222-2222-4222-8222-222222222222"));

        MetadataUtils.applyResolvedTrackCredits(resolved, target);
        assertEquals("澤野弘之", target.getComposer());
        assertEquals(List.of("澤野弘之"), target.getComposers());
        assertNull(target.getComposerIds());
        assertFalse(target.isClearComposer());

        assertNull(target.getLyricist());
        assertNull(target.getLyricists());
        assertNull(target.getLyricistIds());
        assertTrue(target.isClearLyricist());

        assertEquals("Online Arranger", target.getArranger());
        assertEquals(List.of("Online Arranger"), target.getArrangers());
        assertNull(target.getArrangerIds());
        assertFalse(target.isClearArranger());
    }

    @Test
    void extractQueuedMusicBrainzCreditsDistinguishesMbFromCopiedLocalTags() {
        // Path B: recovery folder queued snapshot equals local tags (no MB recordingId)
        MusicMetadata local = new MusicMetadata();
        local.setComposer("Local Composer");
        local.setLyricist("Local Lyricist");
        MusicMetadata pathBQueued = local.copyWithoutHeavyFields();
        assertNull(MetadataUtils.extractQueuedMusicBrainzCredits(pathBQueued, local));

        // Path A: MB provided composer (+ MBID) and merged local lyricist as fallback
        MusicMetadata pathAQueued = new MusicMetadata();
        pathAQueued.setRecordingId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        pathAQueued.setComposer("MB Composer");
        pathAQueued.setComposers(List.of("MB Composer"));
        pathAQueued.setComposerIds(List.of("11111111-1111-4111-8111-111111111111"));
        pathAQueued.setLyricist("Local Lyricist");

        MusicMetadata extracted = MetadataUtils.extractQueuedMusicBrainzCredits(pathAQueued, local);
        assertNotNull(extracted);
        assertEquals("MB Composer", extracted.getComposer());
        assertEquals(List.of("11111111-1111-4111-8111-111111111111"), extracted.getComposerIds());
        assertNull(extracted.getLyricist(), "Fallback copied from local must remain LOCAL, not MUSICBRAINZ");
    }

    @Test
    void resolveOnlineTrackCreditsRespectsCreditDetailStatusAndApplyProbableFlag() {
        ReviewItem.OnlineTrack online = new ReviewItem.OnlineTrack();
        online.setComposers(List.of("Soma Genda"));
        online.setLyricists(List.of("Gohgo"));
        online.setArrangers(List.of("Conflicting Arranger"));

        ReviewItem.TrackCreditDetail detail = new ReviewItem.TrackCreditDetail();
        ReviewItem.CreditFieldResult comp = new ReviewItem.CreditFieldResult();
        comp.setStatus(ReviewItem.CreditStatus.VERIFIED);
        comp.setNames(List.of("Soma Genda"));
        detail.setComposers(comp);

        ReviewItem.CreditFieldResult lyr = new ReviewItem.CreditFieldResult();
        lyr.setStatus(ReviewItem.CreditStatus.PROBABLE);
        lyr.setNames(List.of("Gohgo"));
        detail.setLyricists(lyr);

        ReviewItem.CreditFieldResult arr = new ReviewItem.CreditFieldResult();
        arr.setStatus(ReviewItem.CreditStatus.CONFLICT);
        arr.setNames(List.of("Conflicting Arranger")); // even if mistakenly populated, CONFLICT must be blocked!
        detail.setArrangers(arr);
        online.setCreditDetail(detail);

        // applyProbable = true -> VERIFIED & PROBABLE admitted, CONFLICT blocked
        MetadataUtils.ResolvedTrackCredits withProbable =
            MetadataUtils.resolveOnlineTrackCredits(null, null, null, online, true, true);
        assertEquals(MetadataUtils.CreditSource.ONLINE, withProbable.composer().source());
        assertEquals("Soma Genda", withProbable.composer().display());
        assertEquals(MetadataUtils.CreditSource.ONLINE, withProbable.lyricist().source());
        assertEquals("Gohgo", withProbable.lyricist().display());
        assertEquals(MetadataUtils.CreditSource.NONE, withProbable.arranger().source());

        // applyProbable = false -> ONLY VERIFIED admitted; PROBABLE & CONFLICT blocked
        MetadataUtils.ResolvedTrackCredits strictOnly =
            MetadataUtils.resolveOnlineTrackCredits(null, null, null, online, true, false);
        assertEquals(MetadataUtils.CreditSource.ONLINE, strictOnly.composer().source());
        assertEquals(MetadataUtils.CreditSource.NONE, strictOnly.lyricist().source());
        assertEquals(MetadataUtils.CreditSource.NONE, strictOnly.arranger().source());

        // Manual override still beats CONFLICT / blocked PROBABLE
        ReviewItem.TrackCreditsOverride manual = new ReviewItem.TrackCreditsOverride();
        manual.setArrangers(List.of("Chosen Arranger"));
        MetadataUtils.ResolvedTrackCredits manualWin =
            MetadataUtils.resolveOnlineTrackCredits(manual, null, null, online, true, false);
        assertEquals(MetadataUtils.CreditSource.MANUAL, manualWin.arranger().source());
        assertEquals("Chosen Arranger", manualWin.arranger().display());
    }
}
