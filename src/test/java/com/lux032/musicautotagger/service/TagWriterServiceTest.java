package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.TagField;
import org.jaudiotagger.tag.flac.FlacTag;
import org.jaudiotagger.tag.id3.AbstractID3v2Frame;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.ID3v23Tag;
import org.jaudiotagger.tag.id3.ID3v24Tag;
import org.jaudiotagger.tag.id3.framebody.AbstractFrameBodyTextInfo;
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX;
import org.jaudiotagger.tag.mp4.Mp4Tag;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TagWriterServiceTest {
    @Test
    void structuredArtistsSurviveAcrossContainersAndMetadataCopies() throws Exception {
        MusicMetadata metadata = new MusicMetadata();
        metadata.setArtist("Mori Calliope, 常闇トワ");
        metadata.setArtists(List.of("Mori Calliope", "常闇トワ"));
        metadata.setAlbumArtist("Various Artists");
        assertEquals(metadata.getArtists(), metadata.copyWithoutHeavyFields().getArtists());
        for (org.jaudiotagger.tag.Tag tag : List.of(new FlacTag(), new VorbisCommentTag(),
                new ID3v23Tag(), new ID3v24Tag(), new Mp4Tag())) {
            invokeUpdateTextTags(tag, metadata);
            assertEquals(List.of("Mori Calliope", "常闇トワ"), tag.getAll(FieldKey.ARTISTS), tag.getClass().getSimpleName());
            assertEquals("Mori Calliope, 常闇トワ", tag.getFirst(FieldKey.ARTIST));
            MusicMetadata solo = new MusicMetadata();
            solo.setArtist("Solo");
            invokeUpdateTextTags(tag, solo);
            assertEquals("", tag.getFirst(FieldKey.ARTISTS));
        }
    }

    @Test
    void musicBrainzNamesRemainStructuredEvenWithPunctuation() throws Exception {
        JsonNode credits = new ObjectMapper().readTree("""
            [{"artist":{"name":"Earth, Wind & Fire"}},
             {"artist":{"name":"常闇トワ"}}]
            """);
        assertEquals(List.of("Earth, Wind & Fire", "常闇トワ"), MusicBrainzClient.artistNames(credits));
        MusicMetadata metadata = new MusicMetadata();
        metadata.setArtist("A, B");
        metadata.setArtists(List.of("A", "B"));
        metadata.setArtist("Solo");
        assertNull(metadata.getArtists());
    }

    @Test
    void missingAlbumArtistTagRemainsNullInsteadOfBecomingVariousArtists() {
        assertNull(TagWriterService.albumArtistFromTag(""));
        assertNull(TagWriterService.albumArtistFromTag("   "));
        assertNull(TagWriterService.albumArtistFromTag(null));
        assertEquals("Earth, Wind & Fire", TagWriterService.albumArtistFromTag("Earth, Wind & Fire"));
    }

    @Test
    void splitPositionSeparatesSlashTotalForMp4Safety() {
        assertArrayEquals(new String[] {"1", "6"}, TagWriterService.splitPosition("1/6", null));
        assertArrayEquals(new String[] {"2", "2"}, TagWriterService.splitPosition("2/2", null));
    }

    @Test
    void splitPositionPrefersExplicitTotal() {
        assertArrayEquals(new String[] {"1", "9"}, TagWriterService.splitPosition("1/6", "9"));
        assertArrayEquals(new String[] {"5", "12"}, TagWriterService.splitPosition("5", "12"));
    }

    @Test
    void writingOriginalYearPreservesOtherId3TxxxFields() throws Exception {
        ID3v24Tag tag = new ID3v24Tag();
        tag.setField(FieldKey.BARCODE, "4547366835731");
        tag.setField(FieldKey.MUSICBRAINZ_RELEASEID, "eda03f17-bc09-4534-a011-01fdfad6316e");

        invokeOriginalReleaseWriter(tag, "2026-07-08", "2026");

        assertEquals("4547366835731", tag.getFirst(FieldKey.BARCODE));
        assertEquals("eda03f17-bc09-4534-a011-01fdfad6316e", tag.getFirst(FieldKey.MUSICBRAINZ_RELEASEID));
        assertEquals("2026", findTxxxValue(tag, "originalyear"));
    }

    @Test
    void bareId3v24CreatesTdorAndOriginalYearTxxx() throws Exception {
        ID3v24Tag tag = new ID3v24Tag();

        invokeOriginalReleaseWriter(tag, "2026-07-08", "2026");

        assertEquals("2026-07-08", textFrameValue(tag, "TDOR"));
        assertEquals("2026", findTxxxValue(tag, "originalyear"));
    }

    @Test
    void id3v23UsesFourDigitToryAndNoTdor() throws Exception {
        ID3v23Tag tag = new ID3v23Tag();

        invokeOriginalReleaseWriter(tag, "2026-07-08", "2026");

        assertEquals("2026", textFrameValue(tag, "TORY"));
        assertNull(firstFrame(tag, "TDOR"));
        assertEquals("2026", findTxxxValue(tag, "originalyear"));
    }

    @Test
    void flacWritesOriginalDateAndYear() throws Exception {
        FlacTag tag = new FlacTag();

        invokeOriginalReleaseWriter(tag, "2026-07-08", "2026");

        assertEquals("2026-07-08", tag.getFirst("ORIGINALDATE"));
        assertEquals("2026", tag.getFirst("ORIGINALYEAR"));
    }

    @Test
    void vorbisWritesOriginalDateAndYear() throws Exception {
        VorbisCommentTag tag = new VorbisCommentTag();

        invokeOriginalReleaseWriter(tag, "2026-07-08", "2026");

        assertEquals("2026-07-08", tag.getFirst("ORIGINALDATE"));
        assertEquals("2026", tag.getFirst("ORIGINALYEAR"));
    }

    @Test
    void mp4OriginalDateAndYearUseDistinctReverseDnsKeys() throws Exception {
        Mp4Tag tag = new Mp4Tag();

        invokeOriginalReleaseWriter(tag, "2026-07-08", "2026");

        assertNotNull(tag.getFirstField("----:com.apple.iTunes:ORIGINALDATE"));
        assertNotNull(tag.getFirstField("----:com.apple.iTunes:ORIGINALYEAR"));
        assertEquals("2026-07-08", tag.getFirst("----:com.apple.iTunes:ORIGINALDATE"));
        assertEquals("2026", tag.getFirst("----:com.apple.iTunes:ORIGINALYEAR"));
    }

    @Test
    void updateTextTagsWritesArrangerComposerAndLyricistAcrossContainers() throws Exception {
        MusicMetadata metadata = new MusicMetadata();
        metadata.setTitle("Song");
        metadata.setComposer("Composer A");
        metadata.setLyricist("Lyricist B");
        metadata.setArranger("Arranger C, Arranger D");

        for (org.jaudiotagger.tag.Tag tag : List.of(new ID3v24Tag(), new ID3v23Tag(), new FlacTag(),
                new VorbisCommentTag(), new Mp4Tag())) {
            invokeUpdateTextTags(tag, metadata);
            String type = tag.getClass().getSimpleName();
            assertEquals("Composer A", tag.getFirst(FieldKey.COMPOSER), type);
            assertEquals("Lyricist B", tag.getFirst(FieldKey.LYRICIST), type);
            assertEquals("Arranger C, Arranger D", tag.getFirst(FieldKey.ARRANGER), type);
        }
    }

    @Test
    void structuredCreatorsRoundTripAcrossContainersAndCopies() throws Exception {
        MusicMetadata md = new MusicMetadata();
        md.setComposer("Last, First, B"); md.setComposers(List.of("Last, First", "B"));
        md.setComposerIds(List.of("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222"));
        md.setLyricist("Berry Risa, Steph Sings!"); md.setLyricists(List.of("Berry Risa", "Steph Sings!"));
        md.setLyricistIds(List.of("33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444"));
        md.setArranger("紅音乃詩, STAND ALONE"); md.setArrangers(List.of("紅音乃詩", "STAND ALONE"));
        md.setArrangerIds(List.of("55555555-5555-4555-8555-555555555555", "66666666-6666-4666-8666-666666666666"));
        assertEquals(md.getComposers(), md.copyWithoutHeavyFields().getComposers());
        assertEquals(md.getLyricists(), md.copyWithoutHeavyFields().getLyricists());
        assertEquals(md.getArrangers(), md.copyWithoutHeavyFields().getArrangers());
        assertEquals(md.getComposerIds(), md.copyWithoutHeavyFields().getComposerIds());
        assertEquals(md.getLyricistIds(), md.copyWithoutHeavyFields().getLyricistIds());
        assertEquals(md.getArrangerIds(), md.copyWithoutHeavyFields().getArrangerIds());
        for (org.jaudiotagger.tag.Tag tag : List.of(new ID3v24Tag(), new ID3v23Tag(), new FlacTag(), new VorbisCommentTag(), new Mp4Tag())) {
            invokeUpdateTextTags(tag, md);
            TagWriterService writer = new TagWriterService(MusicConfig.getInstance());
            Method read = TagWriterService.class.getDeclaredMethod("readCreatorNames", org.jaudiotagger.tag.Tag.class, FieldKey.class, String.class);
            read.setAccessible(true);
            Method readIds = TagWriterService.class.getDeclaredMethod("readCreatorIds", org.jaudiotagger.tag.Tag.class, String.class, List.class);
            readIds.setAccessible(true);
            assertEquals(md.getComposers(), read.invoke(writer, tag, FieldKey.COMPOSER, "COMPOSERS"), tag.getClass().getSimpleName());
            assertEquals(md.getLyricists(), read.invoke(writer, tag, FieldKey.LYRICIST, "LYRICISTS"));
            assertEquals(md.getArrangers(), read.invoke(writer, tag, FieldKey.ARRANGER, "ARRANGERS"));
            assertEquals(md.getComposerIds(), readIds.invoke(writer, tag, "MUSICBRAINZ_COMPOSERID", md.getComposers()), tag.getClass().getSimpleName());
            assertEquals(md.getLyricistIds(), readIds.invoke(writer, tag, "MUSICBRAINZ_LYRICISTID", md.getLyricists()));
            assertEquals(md.getArrangerIds(), readIds.invoke(writer, tag, "MUSICBRAINZ_ARRANGERID", md.getArrangers()));
            MusicMetadata replacement = new MusicMetadata(); replacement.setComposer("Solo");
            invokeUpdateTextTags(tag, replacement);
            assertNull(read.invoke(writer, tag, FieldKey.COMPOSER, "COMPOSERS"));
            assertNull(readIds.invoke(writer, tag, "MUSICBRAINZ_COMPOSERID", List.of("Solo")));
        }
        md.setComposer("Different"); assertNull(md.getComposers()); assertNull(md.getComposerIds());
        md.setLyricist("Different"); assertNull(md.getLyricists()); assertNull(md.getLyricistIds());
        md.setArranger("Different"); assertNull(md.getArrangers()); assertNull(md.getArrangerIds());
    }

    @Test
    void rewritingCreatorClearsSpaceAliasAndRejectsEmptyMbidSlots() throws Exception {
        TagWriterService writer = new TagWriterService(MusicConfig.getInstance());
        Method readIds = TagWriterService.class.getDeclaredMethod("readCreatorIds", org.jaudiotagger.tag.Tag.class, String.class, List.class);
        readIds.setAccessible(true);

        FlacTag flac = new FlacTag();
        flac.setField("COMPOSER", "Old Composer");
        flac.setField("MUSICBRAINZ COMPOSER ID", "11111111-1111-4111-8111-111111111111");
        MusicMetadata replacement = new MusicMetadata();
        replacement.setComposer("New Composer");
        invokeUpdateTextTags(flac, replacement);
        assertEquals("", flac.getFirst("MUSICBRAINZ COMPOSER ID"));

        flac.setField("COMPOSERS", "A; B");
        flac.setField("MUSICBRAINZ_COMPOSERID", "11111111-1111-4111-8111-111111111111;;22222222-2222-4222-8222-222222222222");
        assertNull(readIds.invoke(writer, flac, "MUSICBRAINZ_COMPOSERID", List.of("A", "B")));

        MusicMetadata source = new MusicMetadata();
        source.setComposer("Last, First, B");
        source.setComposers(List.of("Last, First", "B"));
        source.setComposerIds(List.of("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222"));
        MusicMetadata quick = com.lux032.musicautotagger.util.MetadataUtils.createMetadataFromQuickScan(
            source, "Album", "Album Artist", "rg-1", "2025", "01.flac");
        assertEquals(source.getComposers(), quick.getComposers());
        assertEquals(source.getComposerIds(), quick.getComposerIds());

        // Empty slot in COMPOSERS ("A;;B") and edge NUL in MBID ("\u0000X") must be rejected.
        Method readNames = TagWriterService.class.getDeclaredMethod("readCreatorNames", org.jaudiotagger.tag.Tag.class, FieldKey.class, String.class);
        readNames.setAccessible(true);
        flac.setField("COMPOSER", "A, B");
        flac.setField("COMPOSERS", "A;;B");
        flac.setField("MUSICBRAINZ_COMPOSERID", "11111111-1111-4111-8111-111111111111; 22222222-2222-4222-8222-222222222222");
        assertNull(readNames.invoke(writer, flac, FieldKey.COMPOSER, "COMPOSERS"));

        flac.setField("COMPOSERS", "A");
        flac.setField("MUSICBRAINZ_COMPOSERID", "\u000011111111-1111-4111-8111-111111111111");
        assertNull(readIds.invoke(writer, flac, "MUSICBRAINZ_COMPOSERID", List.of("A")));

        // Invalid COMPOSERS ("A;;B") with single native COMPOSER=A must NOT fall back to singleNative MBID.
        flac.setField("COMPOSER", "A");
        flac.setField("COMPOSERS", "A;;B");
        flac.setField("MUSICBRAINZ_COMPOSERID", "11111111-1111-4111-8111-111111111111");
        Method readIdsFull = TagWriterService.class.getDeclaredMethod(
            "readCreatorIds", org.jaudiotagger.tag.Tag.class, FieldKey.class, String.class, List.class, List.class, String.class);
        readIdsFull.setAccessible(true);
        assertNull(readIdsFull.invoke(writer, flac, FieldKey.COMPOSER, "COMPOSERS",
            List.of("MUSICBRAINZ_COMPOSERID", "MUSICBRAINZ COMPOSER ID"), null, "A"));

        // MP4 org.musicbrainz.iTunes namespace and lowercase descriptors must be cleaned on rewrite and checked for conflict.
        Mp4Tag mp4 = new Mp4Tag();
        mp4.setField(new org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField(
            "----:org.musicbrainz.iTunes:MUSICBRAINZ_COMPOSERID", "org.musicbrainz.iTunes", "MUSICBRAINZ_COMPOSERID",
            "11111111-1111-4111-8111-111111111111"));
        mp4.setField(new org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField(
            "----:com.apple.iTunes:MUSICBRAINZ_COMPOSERID", "com.apple.iTunes", "MUSICBRAINZ_COMPOSERID",
            "22222222-2222-4222-8222-222222222222"));
        assertNull(readIds.invoke(writer, mp4, "MUSICBRAINZ_COMPOSERID", List.of("A")));

        mp4.setField(new org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField(
            "----:com.apple.iTunes:musicbrainz_composerid", "com.apple.iTunes", "musicbrainz_composerid",
            "11111111-1111-4111-8111-111111111111"));
        mp4.setField(new org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField(
            "----:org.musicbrainz.iTunes:musicbrainz_composerid", "org.musicbrainz.iTunes", "musicbrainz_composerid",
            "22222222-2222-4222-8222-222222222222"));
        invokeUpdateTextTags(mp4, replacement);
        assertEquals("", mp4.getFirst("----:org.musicbrainz.iTunes:MUSICBRAINZ_COMPOSERID"));
        assertEquals("", mp4.getFirst("----:com.apple.iTunes:musicbrainz_composerid"));
        assertEquals("", mp4.getFirst("----:org.musicbrainz.iTunes:musicbrainz_composerid"));
    }

    @Test
    void updateTextTagsLeavesExistingArrangerWhenMetadataHasNone() throws Exception {
        FlacTag tag = new FlacTag();
        tag.setField(FieldKey.ARRANGER, "Existing Arranger");
        MusicMetadata metadata = new MusicMetadata();
        metadata.setTitle("Song");

        invokeUpdateTextTags(tag, metadata);

        assertEquals("Existing Arranger", tag.getFirst(FieldKey.ARRANGER));
    }

    @Test
    void explicitCreatorClearFlagsDeleteStandardPluralAndMbidTagsAcrossContainers() throws Exception {
        MusicMetadata initial = new MusicMetadata();
        initial.setTitle("Song");
        initial.setComposer("Composer A, Composer B");
        initial.setComposers(List.of("Composer A", "Composer B"));
        initial.setComposerIds(List.of("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222"));
        initial.setLyricist("Lyricist A");
        initial.setLyricists(List.of("Lyricist A"));
        initial.setLyricistIds(List.of("33333333-3333-4333-8333-333333333333"));
        initial.setArranger("Arranger A");
        initial.setArrangers(List.of("Arranger A"));
        initial.setArrangerIds(List.of("44444444-4444-4444-8444-444444444444"));

        // copyWithoutHeavyFields 必须保留显式清空标志
        MusicMetadata clearMeta = new MusicMetadata();
        clearMeta.setClearComposer(true);
        clearMeta.setClearArranger(true);
        MusicMetadata copied = clearMeta.copyWithoutHeavyFields();
        assertEquals(true, copied.isClearComposer());
        assertEquals(false, copied.isClearLyricist());
        assertEquals(true, copied.isClearArranger());

        org.jaudiotagger.tag.wav.WavTag wavTag =
            new org.jaudiotagger.tag.wav.WavTag(org.jaudiotagger.audio.wav.WavOptions.READ_ID3_ONLY);
        wavTag.setID3Tag(new ID3v24Tag());

        TagWriterService writer = new TagWriterService(MusicConfig.getInstance());
        Method readNames = TagWriterService.class.getDeclaredMethod(
            "readCreatorNames", org.jaudiotagger.tag.Tag.class, FieldKey.class, String.class);
        readNames.setAccessible(true);
        Method readIds = TagWriterService.class.getDeclaredMethod(
            "readCreatorIds", org.jaudiotagger.tag.Tag.class, String.class, List.class);
        readIds.setAccessible(true);

        for (org.jaudiotagger.tag.Tag tag : List.of(
                new FlacTag(), new VorbisCommentTag(), new ID3v23Tag(), new ID3v24Tag(), new Mp4Tag(), wavTag)) {
            String type = tag.getClass().getSimpleName();
            invokeUpdateTextTags(tag, initial);
            assertEquals("Composer A", tag.getFirst(FieldKey.COMPOSER), type);
            assertEquals("Lyricist A", tag.getFirst(FieldKey.LYRICIST), type);
            assertEquals("Arranger A", tag.getFirst(FieldKey.ARRANGER), type);

            // 仅清空 composer 与 arranger，lyricist 必须保留
            invokeUpdateTextTags(tag, clearMeta);
            assertEquals("", tag.getFirst(FieldKey.COMPOSER), type);
            assertNull(readNames.invoke(writer, tag, FieldKey.COMPOSER, "COMPOSERS"), type);
            assertNull(readIds.invoke(writer, tag, "MUSICBRAINZ_COMPOSERID", List.of("Composer A", "Composer B")), type);
            assertEquals("", tag.getFirst(FieldKey.ARRANGER), type);
            assertNull(readNames.invoke(writer, tag, FieldKey.ARRANGER, "ARRANGERS"), type);
            assertNull(readIds.invoke(writer, tag, "MUSICBRAINZ_ARRANGERID", List.of("Arranger A")), type);

            assertEquals("Lyricist A", tag.getFirst(FieldKey.LYRICIST), type);
            assertEquals(List.of("Lyricist A"), readNames.invoke(writer, tag, FieldKey.LYRICIST, "LYRICISTS"), type);
            assertEquals(List.of("33333333-3333-4333-8333-333333333333"),
                readIds.invoke(writer, tag, "MUSICBRAINZ_LYRICISTID", List.of("Lyricist A")), type);

            // 再单独清空 lyricist
            MusicMetadata clearLyricistOnly = new MusicMetadata();
            clearLyricistOnly.setClearLyricist(true);
            invokeUpdateTextTags(tag, clearLyricistOnly);
            assertEquals("", tag.getFirst(FieldKey.LYRICIST), type);
            assertNull(readNames.invoke(writer, tag, FieldKey.LYRICIST, "LYRICISTS"), type);
            assertNull(readIds.invoke(writer, tag, "MUSICBRAINZ_LYRICISTID", List.of("Lyricist A")), type);
        }
    }

    private static void invokeUpdateTextTags(org.jaudiotagger.tag.Tag tag, MusicMetadata metadata) throws Exception {
        TagWriterService writer = new TagWriterService(MusicConfig.getInstance());
        Method method = TagWriterService.class.getDeclaredMethod(
            "updateTextTags", org.jaudiotagger.tag.Tag.class, MusicMetadata.class);
        method.setAccessible(true);
        method.invoke(writer, tag, metadata);
    }

    private static void invokeOriginalReleaseWriter(Object tag, String date, String year) throws Exception {
        TagWriterService writer = new TagWriterService(MusicConfig.getInstance());
        Method method = TagWriterService.class.getDeclaredMethod(
            "writeOriginalReleaseTags", org.jaudiotagger.tag.Tag.class, String.class, String.class);
        method.setAccessible(true);
        method.invoke(writer, tag, date, year);
    }

    private static String findTxxxValue(AbstractID3v2Tag tag, String description) {
        List<TagField> frames = tag.getFrame("TXXX");
        if (frames == null) return null;
        for (TagField field : frames) {
            if (field instanceof AbstractID3v2Frame frame
                && frame.getBody() instanceof FrameBodyTXXX body
                && description.equalsIgnoreCase(body.getDescription())) {
                return body.getText();
            }
        }
        return null;
    }

    private static AbstractID3v2Frame firstFrame(AbstractID3v2Tag tag, String frameId) {
        List<TagField> frames = tag.getFrame(frameId);
        if (frames == null || frames.isEmpty()) return null;
        return (AbstractID3v2Frame) frames.get(0);
    }

    private static String textFrameValue(AbstractID3v2Tag tag, String frameId) {
        AbstractID3v2Frame frame = firstFrame(tag, frameId);
        if (frame == null || !(frame.getBody() instanceof AbstractFrameBodyTextInfo body)) return null;
        return body.getText();
    }

    @Test
    void parsesReleaseTagBundleFromMusicBrainzJson() throws Exception {
        String json = """
            {
              "status":"Official",
              "country":"XW",
              "barcode":"4547366835731",
              "text-representation":{"script":"Jpan"},
              "artist-credit":[{"artist":{"id":"artist-id","sort-name":"ReoNa"}}],
              "media":[{"format":"Digital Media"}],
              "label-info":[{"catalog-number":"VVXX02075B00Z","label":{"name":"SACRA MUSIC"}}],
              "release-group":{"primary-type":"Single","secondary-types":[],"first-release-date":"2026-07-08"}
            }
            """;
        JsonNode release = new ObjectMapper().readTree(json);
        MusicBrainzClient client = new MusicBrainzClient(MusicConfig.getInstance());

        MusicBrainzClient.ReleaseTagBundle bundle = client.parseReleaseTagBundle(release);

        assertEquals("artist-id", bundle.getAlbumArtistId());
        assertEquals("official", bundle.getReleaseStatus());
        assertEquals("XW", bundle.getReleaseCountry());
        assertEquals("1", bundle.getDiscTotal());
        assertEquals("single", bundle.getReleaseType());
        assertFalse(bundle.isCompilation());
        assertEquals("SACRA MUSIC", bundle.getRecordLabel());
        assertEquals("VVXX02075B00Z", bundle.getCatalogNumber());
        assertEquals("2026-07-08", bundle.getOriginalReleaseDate());
        assertEquals("2026", bundle.getOriginalYear());
    }

    @Test
    void structuredAlbumArtistsSurviveAcrossContainersAndClearWhenCollapsedToVA() throws Exception {
        String display = "Aiobahn +81 feat. DEMONDICE";
        JsonNode credits = new ObjectMapper().readTree("""
            [
              {"name":"Aiobahn +81","joinphrase":" feat. ","artist":{"name":"Aiobahn +81"}},
              {"name":"DEMONDICE","joinphrase":"","artist":{"name":"DEMONDICE"}}
            ]
            """);
        String sourceCredit = MusicBrainzClient.encodeArtistCredit(credits, display);
        MusicMetadata metadata = new MusicMetadata();
        metadata.setArtist(display);
        metadata.setArtists(List.of("Aiobahn +81", "DEMONDICE"));
        metadata.setArtistCredit(sourceCredit);
        metadata.setAlbumArtist(display);
        metadata.setAlbumArtists(List.of("Aiobahn +81", "DEMONDICE"));
        metadata.setAlbumArtistCredit(sourceCredit);
        assertEquals(sourceCredit, metadata.copyWithoutHeavyFields().getAlbumArtistCredit());

        var readCredit = TagWriterService.class.getDeclaredMethod(
            "readArtistCreditTag", org.jaudiotagger.tag.Tag.class, String.class);
        readCredit.setAccessible(true);
        var writer = new TagWriterService(MusicConfig.getInstance());
        for (org.jaudiotagger.tag.Tag tag : List.of(new FlacTag(), new VorbisCommentTag(),
                new ID3v23Tag(), new ID3v24Tag(), new Mp4Tag())) {
            invokeUpdateTextTags(tag, metadata);
            assertEquals(List.of("Aiobahn +81", "DEMONDICE"), tag.getAll(FieldKey.ALBUM_ARTISTS), tag.getClass().getSimpleName());
            assertEquals("Aiobahn +81 feat. DEMONDICE", tag.getFirst(FieldKey.ALBUM_ARTIST), tag.getClass().getSimpleName());

            assertEquals(display, tag.getFirst(FieldKey.ARTIST));
            assertEquals(sourceCredit, readCredit.invoke(writer, tag, "MUSICBRAINZ_ARTIST_CREDIT"));
            assertEquals(sourceCredit, readCredit.invoke(writer, tag, "MUSICBRAINZ_ALBUM_ARTIST_CREDIT"));

            MusicMetadata collapsed = new MusicMetadata();
            collapsed.setAlbumArtist("Various Artists");
            invokeUpdateTextTags(tag, collapsed);
            assertEquals("", tag.getFirst(FieldKey.ALBUM_ARTISTS), tag.getClass().getSimpleName());
            assertNull(readCredit.invoke(writer, tag, "MUSICBRAINZ_ALBUM_ARTIST_CREDIT"));
            assertEquals(sourceCredit, readCredit.invoke(writer, tag, "MUSICBRAINZ_ARTIST_CREDIT"));
        }
    }

    @Test
    void parseRecordingResponsePopulatesStructuredAlbumArtistsWhenCollapseDisabled() throws Exception {
        String recordingJson = """
            {
              "id": "b1e11e8f-4cab-43ac-b0a0-2424d8be935c",
              "title": "DEMON'S SHOT",
              "artist-credit": [
                {"name": "Aiobahn +81", "joinphrase": " feat. ", "artist": {"id": "a39cf062-a860-4e22-ac59-1a8ebc65a8ff", "name": "Aiobahn +81", "sort-name": "Aiobahn +81"}},
                {"name": "DEMONDICE", "joinphrase": "", "artist": {"id": "62d713c3-b081-4390-a9c7-0b8978d5b76a", "name": "DEMONDICE", "sort-name": "DEMONDICE"}}
              ],
              "releases": [
                {
                  "id": "35378b89-d4a7-44a4-a419-4c91596d1d43",
                  "title": "DEMON'S SHOT",
                  "status": "Official",
                  "country": "XW",
                  "date": "2026-09-30",
                  "artist-credit": [
                    {"name": "Aiobahn +81", "joinphrase": " feat. ", "artist": {"id": "a39cf062-a860-4e22-ac59-1a8ebc65a8ff", "name": "Aiobahn +81", "sort-name": "Aiobahn +81"}},
                    {"name": "DEMONDICE", "joinphrase": "", "artist": {"id": "62d713c3-b081-4390-a9c7-0b8978d5b76a", "name": "DEMONDICE", "sort-name": "DEMONDICE"}}
                  ],
                  "release-group": {"id": "rg-1", "primary-type": "Single", "secondary-types": [], "first-release-date": "2026-09-30"},
                  "media": [{"position": 1, "format": "Digital Media", "track-count": 1}]
                }
              ]
            }
            """;
        JsonNode fullRelease = new ObjectMapper().readTree("""
            {
              "id": "35378b89-d4a7-44a4-a419-4c91596d1d43",
              "title": "DEMON'S SHOT",
              "status": "Official",
              "country": "XW",
              "date": "2026-09-30",
              "artist-credit": [
                {"name": "Aiobahn +81", "joinphrase": " feat. ", "artist": {"id": "a39cf062-a860-4e22-ac59-1a8ebc65a8ff", "name": "Aiobahn +81", "sort-name": "Aiobahn +81"}},
                {"name": "DEMONDICE", "joinphrase": "", "artist": {"id": "62d713c3-b081-4390-a9c7-0b8978d5b76a", "name": "DEMONDICE", "sort-name": "DEMONDICE"}}
              ],
              "release-group": {"id": "rg-1", "primary-type": "Single", "secondary-types": [], "first-release-date": "2026-09-30"},
              "media": [
                {
                  "position": 1,
                  "format": "Digital Media",
                  "tracks": [
                    {
                      "id": "track-1",
                      "position": 1,
                      "recording": {
                        "id": "b1e11e8f-4cab-43ac-b0a0-2424d8be935c",
                        "title": "DEMON'S SHOT",
                        "artist-credit": [
                          {"name": "Aiobahn +81", "joinphrase": " feat. ", "artist": {"id": "a39cf062-a860-4e22-ac59-1a8ebc65a8ff", "name": "Aiobahn +81", "sort-name": "Aiobahn +81"}},
                          {"name": "DEMONDICE", "joinphrase": "", "artist": {"id": "62d713c3-b081-4390-a9c7-0b8978d5b76a", "name": "DEMONDICE", "sort-name": "DEMONDICE"}}
                        ]
                      }
                    }
                  ]
                }
              ]
            }
            """);

        java.lang.reflect.Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MusicConfig keepMultiConfig = ctor.newInstance();
        keepMultiConfig.setCollapseAlbumArtistToVariousArtists(false);

        MusicBrainzClient keepClient = new MusicBrainzClient(keepMultiConfig) {
            @Override
            JsonNode getFullReleaseById(String releaseId) {
                return fullRelease;
            }
        };
        Method parse = MusicBrainzClient.class.getDeclaredMethod(
            "parseRecordingResponse", String.class, int.class, boolean.class, String.class, String.class, int.class, boolean.class);
        parse.setAccessible(true);

        MusicMetadata kept = (MusicMetadata) parse.invoke(keepClient, recordingJson, 1, true, null, null, 255, true);
        assertEquals(List.of("Aiobahn +81", "DEMONDICE"), kept.getArtists());
        assertEquals("Aiobahn +81 feat. DEMONDICE", kept.getAlbumArtist());
        assertEquals(List.of("Aiobahn +81", "DEMONDICE"), kept.getAlbumArtists());

        MusicConfig collapseConfig = ctor.newInstance();
        collapseConfig.setCollapseAlbumArtistToVariousArtists(true);
        MusicBrainzClient collapseClient = new MusicBrainzClient(collapseConfig) {
            @Override
            JsonNode getFullReleaseById(String releaseId) {
                return fullRelease;
            }
        };
        MusicMetadata collapsed = (MusicMetadata) parse.invoke(collapseClient, recordingJson, 1, true, null, null, 255, true);
        assertEquals("Aiobahn +81 feat. DEMONDICE", collapsed.getArtist());
        assertNotNull(collapsed.getArtistCredit());
        assertNull(collapsed.getAlbumArtistCredit());
        assertEquals(List.of("Aiobahn +81", "DEMONDICE"), collapsed.getArtists());
        assertEquals("Various Artists", collapsed.getAlbumArtist());
        assertNull(collapsed.getAlbumArtists());
    }

    @Test
    void releaseTagBundleMatchesBothFeatAndCommaJoinedAlbumArtist() throws Exception {
        JsonNode release = new ObjectMapper().readTree("""
            {
              "artist-credit": [
                {"name": "Aiobahn +81", "joinphrase": " feat. ", "artist": {"id": "a39cf062-a860-4e22-ac59-1a8ebc65a8ff", "name": "Aiobahn +81", "sort-name": "Aiobahn +81"}},
                {"name": "DEMONDICE", "joinphrase": "", "artist": {"id": "62d713c3-b081-4390-a9c7-0b8978d5b76a", "name": "DEMONDICE", "sort-name": "DEMONDICE"}}
              ]
            }
            """);
        java.lang.reflect.Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MusicConfig cfg = ctor.newInstance();
        cfg.setCollapseAlbumArtistToVariousArtists(false);
        MusicBrainzClient.ReleaseTagBundle bundle = new MusicBrainzClient(cfg).parseReleaseTagBundle(release);

        MusicMetadata featMd = new MusicMetadata();
        featMd.setAlbumArtist("Aiobahn +81 feat. DEMONDICE");
        bundle.applyTo(featMd);
        assertEquals(List.of("Aiobahn +81", "DEMONDICE"), featMd.getAlbumArtists());

        MusicMetadata commaMd = new MusicMetadata();
        commaMd.setAlbumArtist("Aiobahn +81, DEMONDICE");
        bundle.applyTo(commaMd);
        assertEquals("Aiobahn +81 feat. DEMONDICE", commaMd.getAlbumArtist());
        assertEquals(List.of("Aiobahn +81", "DEMONDICE"), commaMd.getAlbumArtists());

        MusicMetadata vaMd = new MusicMetadata();
        vaMd.setAlbumArtist("Various Artists");
        bundle.applyTo(vaMd);
        assertNull(vaMd.getAlbumArtists());
    }
}
