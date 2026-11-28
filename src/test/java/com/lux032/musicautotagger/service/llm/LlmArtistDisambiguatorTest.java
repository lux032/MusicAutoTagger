package com.lux032.musicautotagger.service.llm;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.service.MusicBrainzClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LlmArtistDisambiguatorTest {

    private static final String MBID_1 = "11111111-1111-4111-8111-111111111111";
    private static final String MBID_2 = "22222222-2222-4222-8222-222222222222";

    private MusicConfig newConfig() throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private List<MusicBrainzClient.ArtistMatch> sampleHomonyms() {
        return List.of(
            new MusicBrainzClient.ArtistMatch(MBID_1, "高橋諒", "Takahashi, Ryo", "US jazz drummer", "US", "United States",
                "Person", "1970", null, 100, List.of("Ryo Takahashi"), List.of("jazz")),
            new MusicBrainzClient.ArtistMatch(MBID_2, "高橋諒", "Takahashi, Ryo", "Japanese anime composer (Void_Chords)", "JP", "Japan",
                "Person", "1985", null, 100, List.of("Ryo Takahashi", "Void_Chords"), List.of("anime", "soundtrack"))
        );
    }

    @Test
    void selectsValidCandidateAtOrAboveThresholdAndNeverExposesUuidsInPrompt() throws Exception {
        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        LlmClient stubLlm = new LlmClient(newConfig()) {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public LlmResponse complete(String systemPrompt, String userPrompt, int maxTokens) {
                capturedPrompt.set(userPrompt);
                return new LlmResponse(
                    "{\"choice_index\":2,\"confidence\":0.94,\"reason\":\"JP anime composer matches album context\"}",
                    "test-model", "stub");
            }
        };

        LlmArtistDisambiguator disambiguator = new LlmArtistDisambiguator(stubLlm);
        LlmArtistDisambiguator.AlbumContext ctx = new LlmArtistDisambiguator.AlbumContext(
            "Anime OST", "2026-04-01", List.of("JP"), List.of("composer", "arranger"), List.of("Aimer"));

        LlmArtistDisambiguator.DisambiguationResult result =
            disambiguator.disambiguate("高橋諒", ctx, sampleHomonyms());

        assertTrue(result.isAccepted());
        assertEquals(2, result.choiceIndex());
        assertEquals(MBID_2, result.chosen().mbid());
        assertEquals(0.94, result.confidence(), 1e-6);

        // 绝不向模型暴露或要求输出原始 UUID
        assertNotNull(capturedPrompt.get());
        assertFalse(capturedPrompt.get().contains(MBID_1));
        assertFalse(capturedPrompt.get().contains(MBID_2));
        assertTrue(capturedPrompt.get().contains("Void_Chords"));
    }

    @Test
    void rejectsOutOfRangeLowConfidenceNonJsonAndLlmFailure() throws Exception {
        List<MusicBrainzClient.ArtistMatch> homonyms = sampleHomonyms();
        LlmArtistDisambiguator.AlbumContext ctx = new LlmArtistDisambiguator.AlbumContext(
            "Album", "2026", List.of("JP"), List.of("composer"), List.of());

        // 越界序号
        LlmArtistDisambiguator outOfRange = new LlmArtistDisambiguator(stubClient("{\"choice_index\":9,\"confidence\":0.99,\"reason\":\"bad\"}"));
        LlmArtistDisambiguator.DisambiguationResult r1 = outOfRange.disambiguate("高橋諒", ctx, homonyms);
        assertFalse(r1.isAccepted());
        assertNull(r1.chosen());

        // 置信度不足 (< 0.85)
        LlmArtistDisambiguator lowConf = new LlmArtistDisambiguator(stubClient("{\"choice_index\":2,\"confidence\":0.70,\"reason\":\"maybe\"}"));
        LlmArtistDisambiguator.DisambiguationResult r2 = lowConf.disambiguate("高橋諒", ctx, homonyms);
        assertFalse(r2.isAccepted());
        assertNotNull(r2.chosen());

        // 返回 0（无法确定）
        LlmArtistDisambiguator zero = new LlmArtistDisambiguator(stubClient("{\"choice_index\":0,\"confidence\":0.2,\"reason\":\"uncertain\"}"));
        assertFalse(zero.disambiguate("高橋諒", ctx, homonyms).isAccepted());

        // 非法 JSON / NaN / 超出范围的置信度
        LlmArtistDisambiguator garbage = new LlmArtistDisambiguator(stubClient("not json at all"));
        assertFalse(garbage.disambiguate("高橋諒", ctx, homonyms).isAccepted());

        LlmArtistDisambiguator invalidConf = new LlmArtistDisambiguator(stubClient("{\"choice_index\":1,\"confidence\":99,\"reason\":\"oops\"}"));
        assertFalse(invalidConf.disambiguate("高橋諒", ctx, homonyms).isAccepted());

        // LLM 异常降级不抛未受检异常
        LlmClient failing = new LlmClient(newConfig()) {
            @Override
            public boolean isConfigured() { return true; }
            @Override
            public LlmResponse complete(String s, String u, int m) throws LlmException {
                throw new LlmException("upstream 502");
            }
        };
        LlmArtistDisambiguator failDisamb = new LlmArtistDisambiguator(failing);
        LlmArtistDisambiguator.DisambiguationResult rFail = failDisamb.disambiguate("高橋諒", ctx, homonyms);
        assertFalse(rFail.isAccepted());
        assertNull(rFail.chosen());
    }

    @Test
    void rejectsFractionalOrStringNumbersAndRedactsUuidsFromPromptFields() throws Exception {
        List<MusicBrainzClient.ArtistMatch> homonyms = sampleHomonyms();
        LlmArtistDisambiguator.AlbumContext ctx = new LlmArtistDisambiguator.AlbumContext(
            "OST " + MBID_1, "2026", List.of("JP"), List.of("composer"), List.of("CoCreator " + MBID_2));

        // 小数序号（1.9）、字符串序号（"2"）、字符串置信度（"0.95"）必须全部拒收
        for (String badReply : List.of(
                "{\"choice_index\":1.9,\"confidence\":0.95,\"reason\":\"fractional\"}",
                "{\"choice_index\":\"2\",\"confidence\":0.95,\"reason\":\"string index\"}",
                "{\"choice_index\":2,\"confidence\":\"0.95\",\"reason\":\"string confidence\"}",
                "{\"choice_index\":true,\"confidence\":0.95,\"reason\":\"bool\"}")) {
            LlmArtistDisambiguator dis = new LlmArtistDisambiguator(stubClient(badReply));
            LlmArtistDisambiguator.DisambiguationResult r = dis.disambiguate("高橋諒", ctx, homonyms);
            assertFalse(r.isAccepted(), "Must reject malformed reply: " + badReply);
            assertNull(r.chosen(), "Malformed reply must not select any candidate: " + badReply);
        }

        // 自由文本字段中混入的 UUID 也必须脱敏，绝不送入模型 prompt
        MusicBrainzClient.ArtistMatch leaky = new MusicBrainzClient.ArtistMatch(
            MBID_1, "Artist", "Artist", "ref " + MBID_2, "JP", "Japan",
            "Person", null, null, 100, List.of("Alias " + MBID_1), List.of("tag-" + MBID_2));
        LlmArtistDisambiguator dis = new LlmArtistDisambiguator(stubClient("{\"choice_index\":0,\"confidence\":0.0,\"reason\":\"\"}"));
        String prompt = dis.buildPrompt("Artist " + MBID_1, ctx, List.of(leaky));
        assertFalse(prompt.contains(MBID_1), "Prompt must redact UUIDs in free text");
        assertFalse(prompt.contains(MBID_2), "Prompt must redact UUIDs in free text");
        assertTrue(prompt.contains("[redacted-id]"));
    }

    private LlmClient stubClient(String reply) throws Exception {
        return new LlmClient(newConfig()) {
            @Override
            public boolean isConfigured() { return true; }
            @Override
            public LlmResponse complete(String s, String u, int m) {
                return new LlmResponse(reply, "stub", "stub");
            }
        };
    }
}
