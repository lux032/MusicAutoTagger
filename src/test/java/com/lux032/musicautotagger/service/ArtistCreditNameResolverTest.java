package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArtistCreditNameResolverTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void detectsPureLatinAndEastAsianScriptsAccurately() {
        assertTrue(ArtistCreditNameResolver.isPureLatinName("Yuki Kajiura"));
        assertTrue(ArtistCreditNameResolver.isPureLatinName("Ryūichi Sakamoto"));
        assertTrue(ArtistCreditNameResolver.isPureLatinName("Kenshi Yonezu & Co."));
        assertTrue(ArtistCreditNameResolver.isPureLatinName("o\u0304hashiTrio"));
        assertTrue(ArtistCreditNameResolver.isPureLatinName("Louis Ⅳ"));

        assertFalse(ArtistCreditNameResolver.isPureLatinName("Ⅳ"));
        assertFalse(ArtistCreditNameResolver.isPureLatinName("梶浦由記"));
        assertFalse(ArtistCreditNameResolver.isPureLatinName("Yuki Kajiura (梶浦由記)"));
        assertFalse(ArtistCreditNameResolver.isPureLatinName("初音ミク"));
        assertFalse(ArtistCreditNameResolver.isPureLatinName("아이유"));
        assertFalse(ArtistCreditNameResolver.isPureLatinName("Пётр Чайковский"));
        assertFalse(ArtistCreditNameResolver.isPureLatinName("   "));
        assertFalse(ArtistCreditNameResolver.isPureLatinName("12345"));

        assertTrue(ArtistCreditNameResolver.containsEastAsianScript("梶浦由記"));
        assertTrue(ArtistCreditNameResolver.containsEastAsianScript("あいみょん"));
        assertTrue(ArtistCreditNameResolver.containsEastAsianScript("サカナクション"));
        assertTrue(ArtistCreditNameResolver.containsEastAsianScript("아이유"));
        assertFalse(ArtistCreditNameResolver.containsEastAsianScript("YOASOBI"));
        assertFalse(ArtistCreditNameResolver.containsEastAsianScript("Taylor Swift"));
    }

    @Test
    void restoresNativeEastAsianNamesWhenEnabled() throws Exception {
        JsonNode kanji = mapper.readTree("{\"name\":\"Yuki Kajiura\",\"artist\":{\"name\":\"梶浦由記\"}}");
        assertEquals("梶浦由記", ArtistCreditNameResolver.resolveCreditName(kanji, true));

        JsonNode hiragana = mapper.readTree("{\"name\":\"Aimyon\",\"artist\":{\"name\":\"あいみょん\"}}");
        assertEquals("あいみょん", ArtistCreditNameResolver.resolveCreditName(hiragana, true));

        JsonNode katakana = mapper.readTree("{\"name\":\"Sakanaction\",\"artist\":{\"name\":\"サカナクション\"}}");
        assertEquals("サカナクション", ArtistCreditNameResolver.resolveCreditName(katakana, true));

        JsonNode hangul = mapper.readTree("{\"name\":\"IU\",\"artist\":{\"name\":\"아이유\"}}");
        assertEquals("아이유", ArtistCreditNameResolver.resolveCreditName(hangul, true));

        JsonNode diacritic = mapper.readTree("{\"name\":\"Ryūichi Sakamoto\",\"artist\":{\"name\":\"坂本龍一\"}}");
        assertEquals("坂本龍一", ArtistCreditNameResolver.resolveCreditName(diacritic, true));
    }

    @Test
    void preservesOfficialLatinStageNamesWesternArtistsAndNonLatinCharacterCredits() throws Exception {
        JsonNode yoasobi = mapper.readTree("{\"name\":\"YOASOBI\",\"artist\":{\"name\":\"YOASOBI\"}}");
        assertEquals("YOASOBI", ArtistCreditNameResolver.resolveCreditName(yoasobi, true));

        JsonNode lisa = mapper.readTree("{\"name\":\"LiSA\",\"artist\":{\"name\":\"LiSA\"}}");
        assertEquals("LiSA", ArtistCreditNameResolver.resolveCreditName(lisa, true));

        JsonNode aimer = mapper.readTree("{\"name\":\"Aimer\",\"artist\":{\"name\":\"Aimer\"}}");
        assertEquals("Aimer", ArtistCreditNameResolver.resolveCreditName(aimer, true));

        JsonNode western = mapper.readTree("{\"name\":\"Taylor Swift\",\"artist\":{\"name\":\"Taylor Swift\"}}");
        assertEquals("Taylor Swift", ArtistCreditNameResolver.resolveCreditName(western, true));

        JsonNode characterCredit = mapper.readTree("{\"name\":\"桜木花道\",\"artist\":{\"name\":\"草尾毅\"}}");
        assertEquals("桜木花道", ArtistCreditNameResolver.resolveCreditName(characterCredit, true));

        JsonNode bilingualCredit = mapper.readTree("{\"name\":\"Yuki Kajiura (梶浦由記)\",\"artist\":{\"name\":\"梶浦由記\"}}");
        assertEquals("Yuki Kajiura (梶浦由記)", ArtistCreditNameResolver.resolveCreditName(bilingualCredit, true));
    }

    @Test
    void preservesJoinPhrasesAndRespectsFalseToggle() throws Exception {
        JsonNode collab = mapper.readTree("""
            [
              {"name":"Yuki Kajiura","joinphrase":" feat. ","artist":{"id":"mbid-1","name":"梶浦由記"}},
              {"name":"Aimer","joinphrase":"","artist":{"id":"mbid-2","name":"Aimer"}}
            ]
            """);

        assertEquals("梶浦由記 feat. Aimer", ArtistCreditNameResolver.resolveCreditedArtistText(collab, true));
        assertEquals("Yuki Kajiura feat. Aimer", ArtistCreditNameResolver.resolveCreditedArtistText(collab, false));
    }
}
