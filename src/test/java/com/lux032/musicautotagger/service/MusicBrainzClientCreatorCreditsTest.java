package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MusicBrainzClientCreatorCreditsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MusicBrainzClient client;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        client = new MusicBrainzClient(constructor.newInstance());
    }

    private MusicMetadata parse(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        MusicMetadata metadata = new MusicMetadata();
        client.parseCreatorCredits(root, metadata);
        return metadata;
    }

    @Test
    void extractsArrangersFromRecordingAndWorkLevelRelations() throws Exception {
        MusicMetadata md = parse("""
            {"relations":[
              {"type":"arranger","artist":{"name":"Rec Arranger"}},
              {"type":"instrument arranger","artist":{"name":"Strings Arranger"}},
              {"type":"vocal arranger","artist":{"name":"Vocal Arranger"}},
              {"type":"performance","work":{"relations":[
                {"type":"orchestrator","artist":{"name":"Orchestrator"}},
                {"type":"arranger","artist":{"name":"Rec Arranger"}},
                {"type":"composer","artist":{"name":"Work Composer"}},
                {"type":"lyricist","artist":{"name":"Work Lyricist"}}
              ]}}
            ]}
            """);

        assertEquals("Rec Arranger, Strings Arranger, Vocal Arranger, Orchestrator", md.getArranger());
        assertEquals("Work Composer", md.getComposer());
        assertEquals("Work Lyricist", md.getLyricist());
    }

    @Test
    void substringNamesAreNotTreatedAsDuplicates() throws Exception {
        // 旧实现用 contains 判重，"Ann" 会被当成已存在于 "Anna" 中而丢失
        MusicMetadata md = parse("""
            {"relations":[
              {"type":"composer","artist":{"name":"Anna"}},
              {"type":"lyricist","artist":{"name":"Anna"}},
              {"type":"performance","work":{"relations":[
                {"type":"composer","artist":{"name":"Ann"}},
                {"type":"composer","artist":{"name":"Anna"}},
                {"type":"writer","artist":{"name":"Ann"}}
              ]}}
            ]}
            """);

        assertEquals("Anna, Ann", md.getComposer());
        assertEquals("Anna, Ann", md.getLyricist());
        assertNull(md.getArranger());
    }

    @Test
    void unrelatedRelationTypesAndBlankNamesAreIgnored() throws Exception {
        MusicMetadata md = parse("""
            {"relations":[
              {"type":"producer","artist":{"name":"Producer"}},
              {"type":"arranger","artist":{"name":"  "}},
              {"type":"mix","artist":{"name":"Mixer"}}
            ]}
            """);

        assertNull(md.getComposer());
        assertNull(md.getLyricist());
        assertNull(md.getArranger());
    }

    @Test
    void missingRelationsLeavesMetadataUntouched() throws Exception {
        MusicMetadata md = parse("{}");
        assertNull(md.getArranger());
        assertNull(md.getComposer());
    }
}
