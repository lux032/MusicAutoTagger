package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.llm.LlmArtistSplitter;
import com.lux032.musicautotagger.service.llm.LlmClient;
import com.lux032.musicautotagger.service.llm.TavilyWebSearchClient;
import com.lux032.musicautotagger.service.llm.WebSearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OnlineIdentificationServiceCreditsAndRegionTest {

    @TempDir Path dir;

    private MusicConfig config;
    private ReviewQueueService reviewQueue;
    private TagWriterService tagWriter;
    private OnlineIdentificationService service;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        config = ctor.newInstance();
        config.setReviewQueuePath(dir.resolve("review.json").toString());
        config.setReviewStagingDirectory(dir.resolve("staging").toString());
        reviewQueue = new ReviewQueueService(config);
        tagWriter = new TagWriterService(config);
        service = new OnlineIdentificationService(config, reviewQueue, tagWriter, null, null);
    }

    @Test
    void parsesTrackCreatorCreditsCleansDuplicatesAndHandlesOmittedFields() throws Exception {
        WebSearchClient.SearchResponse resp = new WebSearchClient.SearchResponse();
        resp.addCitation(WebSearchClient.Citation.of("https://vgmdb.net/album/12345", "Album Page", "credits"));
        resp.setText("""
            {
              "needs_second_round": false,
              "candidates": [
                {
                  "title": "Sample OST",
                  "artist": "梶浦由記",
                  "artists": ["梶浦由記"],
                  "album_artist": "梶浦由記",
                  "album_artists": ["梶浦由記"],
                  "release_date": "2026-01-01",
                  "country": "JP",
                  "confidence": 0.92,
                  "source_urls": ["https://vgmdb.net/album/12345"],
                  "tracks": [
                    {
                      "disc": 1,
                      "track": 1,
                      "title": "Track One",
                      "artist": "梶浦由記",
                      "artists": ["梶浦由記"],
                      "composers": [" 梶浦由記 ", "", "梶浦由記", "澤野弘之"],
                      "lyricists": ["  ", "梶浦由記", null, 123, true, {}],
                      "arrangers": ["森空青", "森空青"],
                      "duration": 240
                    },
                    {
                      "disc": 1,
                      "track": 2,
                      "title": "Track Two",
                      "artist": "梶浦由記",
                      "duration": 180
                    }
                  ]
                }
              ],
              "clues": []
            }
            """);

        OnlineIdentificationService.Parsed parsed = service.parse(resp);
        assertEquals(1, parsed.candidates.size());
        List<ReviewItem.OnlineTrack> tracks = parsed.candidates.get(0).getTracks();
        assertEquals(2, tracks.size());

        ReviewItem.OnlineTrack t1 = tracks.get(0);
        assertEquals(List.of("梶浦由記", "澤野弘之"), t1.getComposers());
        assertEquals(List.of("梶浦由記"), t1.getLyricists());
        assertEquals(List.of("森空青"), t1.getArrangers());

        ReviewItem.OnlineTrack t2 = tracks.get(1);
        assertNotNull(t2.getComposers());
        assertTrue(t2.getComposers().isEmpty());
        assertNotNull(t2.getLyricists());
        assertTrue(t2.getLyricists().isEmpty());
        assertNotNull(t2.getArrangers());
        assertTrue(t2.getArrangers().isEmpty());
    }

    @Test
    void promptsRespectReleaseCountryPriorityDynamicallyAndIncludeCreditGuardrails() throws Exception {
        Path albumDir = Files.createDirectory(dir.resolve("Yuki Kajiura - OST"));
        File f1 = Files.writeString(albumDir.resolve("01. Track.flac"), "dummy").toFile();

        // 1. 空配置：不附加地区本地化指令，但包含幕后人员 schema 与防幻觉规则
        config.setReleaseCountryPriority(List.of());
        String emptyPrompt = service.buildPrompt(albumDir.toFile(), List.of(f1), List.of(240), false);
        assertFalse(emptyPrompt.contains("PREFERRED RELEASE REGIONS"));
        assertTrue(emptyPrompt.contains("\"composers\":[string]"));
        assertTrue(emptyPrompt.contains("\"lyricists\":[string]"));
        assertTrue(emptyPrompt.contains("\"arrangers\":[string]"));
        assertTrue(emptyPrompt.contains("NEVER infer or assume the performer/artist/album_artist is the composer, lyricist, or arranger"));

        // 2. 单地区 JP：动态生效，注入日文偏好、允许转换、保留拉丁艺名与欧美艺人、保持官方曲名
        config.setReleaseCountryPriority(List.of("jp"));
        String jpPrompt = service.buildPrompt(albumDir.toFile(), List.of(f1), List.of(240), false);
        assertTrue(jpPrompt.contains("PREFERRED RELEASE REGIONS (priority order): JP"));
        assertTrue(jpPrompt.contains("Primary naming region: JP (Japanese / 日本語 (Kanji & Kana))"));
        assertTrue(jpPrompt.contains("YOASOBI"));
        assertTrue(jpPrompt.contains("Taylor Swift"));
        assertTrue(jpPrompt.contains("do not freely translate track titles"));

        // 3. 多地区 XW, JP, US：跳过通用 XW，选 JP 作为主本地化地区，同时在 missing-tracks prompt 中同样生效
        config.setReleaseCountryPriority(List.of("XW", "JP", "US"));
        ReviewItem item = new ReviewItem();
        ReviewItem.MissingTracks missing = new ReviewItem.MissingTracks();
        missing.setAlbumTitle("OST");
        missing.setAlbumArtist("Yuki Kajiura");
        missing.setReleaseTrackCount(1);
        item.setMissingTracks(missing);
        String missingPrompt = service.buildMissingTracksPrompt(item, albumDir.toFile(), List.of(f1), List.of(240));
        assertTrue(missingPrompt.contains("PREFERRED RELEASE REGIONS (priority order): XW, JP, US"));
        assertTrue(missingPrompt.contains("Primary naming region: JP"));
    }

    @Test
    void tavilyQueryPromptReceivesPreferredReleaseRegionsAndNativeGuidance() throws Exception {
        AtomicReference<String> capturedSystem = new AtomicReference<>();
        AtomicReference<String> capturedUser = new AtomicReference<>();
        LlmClient stubLlm = new LlmClient(config) {
            @Override
            public LlmResponse complete(String systemPrompt, String userPrompt, int maxTokens) {
                capturedSystem.set(systemPrompt);
                capturedUser.set(userPrompt);
                return new LlmResponse("{\"queries\":[\"梶浦由記 OST 作詞 作曲 編曲\"]}", "stub-model", "stub");
            }
        };
        TavilyWebSearchClient tavily = new TavilyWebSearchClient(config, stubLlm);
        config.setReleaseCountryPriority(List.of("JP", "US"));
        Path albumDir = Files.createDirectory(dir.resolve("AlbumForTavily"));
        File f1 = Files.writeString(albumDir.resolve("01. Song.flac"), "dummy").toFile();
        String prompt = service.buildPrompt(albumDir.toFile(), List.of(f1), List.of(200), false);

        Method generateQueries = TavilyWebSearchClient.class.getDeclaredMethod("generateQueries", String.class);
        generateQueries.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> queries = (List<String>) generateQueries.invoke(tavily, prompt);

        assertEquals(List.of("梶浦由記 OST 作詞 作曲 編曲"), queries);
        assertNotNull(capturedSystem.get());
        assertTrue(capturedSystem.get().contains("PREFERRED RELEASE REGIONS"));
        assertTrue(capturedSystem.get().contains("track credits"));
        assertNotNull(capturedUser.get());
        assertTrue(capturedUser.get().contains("PREFERRED RELEASE REGIONS (priority order): JP, US"));
    }
}
