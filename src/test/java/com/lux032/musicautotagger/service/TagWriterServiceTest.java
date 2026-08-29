package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lux032.musicautotagger.config.MusicConfig;
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
}
