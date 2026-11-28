package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MbContributionItem;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.util.FileSystemUtils;
import com.lux032.musicautotagger.util.MetadataUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MusicBrainzContributionServiceTest {

    private static MusicConfig newConfig(Path dir) throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MusicConfig cfg = ctor.newInstance();
        cfg.setMonitorDirectory(dir.resolve("monitor").toString());
        cfg.setOutputDirectory(dir.resolve("output").toString());
        cfg.setProcessedFileLogPath(dir.resolve("processed.log").toString());
        cfg.setReviewQueuePath(dir.resolve("data/review-queue.json").toString());
        cfg.setDbType("file");
        cfg.setAcoustIdApiKey("app-key");
        cfg.setAcoustIdUserApiKey("user-key");
        return cfg;
    }

    @Test
    void runsEndToEndContributionFlowFromArchiveToCaseALinkAndAcoustIdSubmit(@TempDir Path tempDir) throws Exception {
        MusicConfig config = newConfig(tempDir);
        Path outDir = Files.createDirectories(tempDir.resolve("output/HIMEHINA/VAMPY"));
        Path archivedWav = outDir.resolve("1.01 HIMEHINA - VAMPY.wav");
        writeWav(archivedWav, 2);

        TagWriterService tagWriter = new TagWriterService(config);
        MusicMetadata initMd = new MusicMetadata();
        initMd.setTitle("VAMPY");
        initMd.setAlbum("VAMPY");
        initMd.setArtist("HIMEHINA");
        initMd.setAlbumArtist("HIMEHINA");
        initMd.setDiscNo("1");
        initMd.setTrackNo("1");
        assertTrue(tagWriter.updateTagsOnExistingFile(archivedWav.toFile(), initMd, null));

        ProcessedFileLogger logger = new ProcessedFileLogger(config, null);
        MbContributionStore store = MbContributionStore.forReviewQueuePath(config.getReviewQueuePath());

        MusicBrainzClient.ReleaseDetail officialDetail = new MusicBrainzClient.ReleaseDetail(
            "44444444-4444-4444-8444-444444444444",
            "55555555-5555-4555-8555-555555555555",
            "VAMPY", null, "official", "2026-11-27", "JP",
            "4580000000001", "Jpan", "ep", List.of(), false,
            "HIMEHINA", List.of("HIMEHINA"), null,
            "c2deade3-c5e4-4061-8c13-9bd8f53a143b", "HIMEHINA",
            "LaRa", "HH-001", "2026-11-27", "2026", 1,
            List.of(new MusicBrainzClient.ReleaseMediumDetail(1, "Digital Media", null, 1, List.of(
                new MusicBrainzClient.ReleaseTrackDetail(
                    1, 1, "1",
                    "66666666-6666-4666-8666-666666666661",
                    "77777777-7777-4777-8777-777777777771",
                    "VAMPY", 2000L, "HIMEHINA", List.of("HIMEHINA"), null,
                    "c2deade3-c5e4-4061-8c13-9bd8f53a143b", "HIMEHINA")
            )))
        );

        MusicBrainzClient stubMb = new MusicBrainzClient(config) {
            @Override
            public List<ReleaseSearchHit> searchReleasesByBarcode(String rawBarcode) {
                return List.of(new ReleaseSearchHit(
                    "44444444-4444-4444-8444-444444444444", "55555555-5555-4555-8555-555555555555",
                    "VAMPY", "HIMEHINA", "2026-11-27", "JP", "Official", "4580000000001", "HH-001", 1, null, 100));
            }
            @Override
            public List<Integer> getReleaseDurationSequence(String releaseId) { return List.of(2); }
            @Override
            public Optional<ReleaseDetail> getFullReleaseWithTracklist(String rawReleaseId) {
                return "44444444-4444-4444-8444-444444444444".equals(rawReleaseId)
                    ? Optional.of(officialDetail) : Optional.empty();
            }
        };

        AtomicInteger submitCalls = new AtomicInteger();
        AudioFingerprintService stubFp = new AudioFingerprintService(config) {
            @Override
            public AudioFingerprint generateFingerprint(File audioFile) {
                AudioFingerprint fp = new AudioFingerprint();
                fp.setDuration(2);
                fp.setFingerprint("AQABstub");
                return fp;
            }
            @Override
            public AcoustIdSubmitResult submitFingerprints(List<AcoustIdSubmissionItem> items,
                                                           java.util.function.Consumer<List<String>> onBatchSubmitted) {
                submitCalls.incrementAndGet();
                if (onBatchSubmitted != null) {
                    onBatchSubmitted.accept(items.stream().map(AcoustIdSubmissionItem::recordingMbid).toList());
                }
                return new AcoustIdSubmitResult(true, items.size(), List.of("sub-1"));
            }
        };

        try (MusicBrainzContributionService service = new MusicBrainzContributionService(
                config, store, stubMb, tagWriter, logger, stubFp, null, new FileSystemUtils(config))) {
            ReviewItem.OnlineCandidate cand = new ReviewItem.OnlineCandidate();
            cand.setTitle("VAMPY");
            cand.setAlbumArtist("HIMEHINA");
            cand.setAlbumArtists(List.of("HIMEHINA"));
            cand.setBarcode("4580000000001");
            cand.setOfficialCandidate(true);
            cand.setTrackCoverage(1.0);
            ReviewItem.OnlineEvidence ev = new ReviewItem.OnlineEvidence();
            ev.setUrl("https://music.apple.com/jp/album/vampy/1");
            ev.setDomain("music.apple.com");
            ev.setReliability("HIGH");
            cand.setSources(List.of(ev));
            ReviewItem.OnlineTrack ot = new ReviewItem.OnlineTrack();
            ot.setDiscNo(1);
            ot.setTrackNo(1);
            ot.setTitle("VAMPY");
            ot.setMatchedFilePath("/monitor/01.wav");
            ot.setMatchConfidence(1.0);
            cand.setTracks(List.of(ot));

            MusicBrainzContributionService.ArchiveSnapshot snap = new MusicBrainzContributionService.ArchiveSnapshot(
                "rev-case-a", cand, "VAMPY", "HIMEHINA", List.of("HIMEHINA"), "2026-11-27", "Japanese digital EP",
                outDir, List.of(archivedWav),
                Map.of(MusicBrainzArtistCache.normalizePersonKey("HIMEHINA"),
                    new MetadataUtils.EffectiveEntityMbid("c2deade3-c5e4-4061-8c13-9bd8f53a143b", "HIMEHINA")),
                Map.of("/monitor/01.wav", new MusicBrainzContributionService.ResolvedTrackSnapshot(
                    archivedWav.toString(), 2.0, null,
                    List.of("tepe (OTOIRO)"), List.of(), List.of(),
                    "ONLINE_VERIFIED", "NONE", "NONE")),
                new MusicBrainzContributionService.CoverCapture("ITUNES",
                    "https://is1-ssl.mzstatic.com/image/thumb/x/1200x1200bb.jpg",
                    "https://music.apple.com/jp/album/vampy/1", null)
            );

            // 1. 异步入队并命中 Case A
            Future<?> fut = service.enqueueAfterOnlineArchive(snap);
            assertNotNull(fut);
            fut.get(10, TimeUnit.SECONDS);

            List<MbContributionItem> list = service.list(null, true);
            assertEquals(1, list.size());
            MbContributionItem created = list.get(0);
            assertEquals(MbContributionItem.Status.EXISTING_MATCH, created.getStatus());
            assertEquals(MbContributionItem.CheckCase.EXISTING_RELEASE, created.getCheckCase());
            assertEquals("44444444-4444-4444-8444-444444444444", created.getMatchedReleaseId());

            // 2. Case A 直接关联（不传 URL 时使用预检命中的 Release MBID）并完成本地回填
            MusicBrainzContributionService.LinkReleaseResponse linked = service.linkRelease(created.getId(), null);
            assertEquals(MbContributionItem.Status.LINKED, linked.detail().item().getStatus());
            assertEquals(1, linked.backfill().updatedFiles());
            assertTrue(linked.detail().coverGuide().addCoverArtSeededUrl().contains("3000x3000bb.jpg"));
            assertTrue(linked.detail().worksGuide().hasAnyCredits());

            // 3. 提交声纹至 AcoustID
            MusicBrainzContributionService.AcoustIdSubmitResponse sub = service.submitAcoustId(created.getId());
            assertEquals(1, submitCalls.get());
            assertEquals(1, sub.result().submittedCount());
            assertEquals(1, sub.detail().item().getAcoustIdSubmittedCount());

            // 4. 关闭自动预检开关后不再自动入队
            config.setMbContributionAutoCheckAfterArchive(false);
            assertNull(service.enqueueAfterOnlineArchive(snap));
        }
    }

    private static void writeWav(Path path, int seconds) throws Exception {
        int sampleRate = 8000;
        int dataSize = sampleRate * 2 * seconds;
        int chunkSize = 36 + dataSize;
        byte[] wav = new byte[44 + dataSize];
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        putLeInt(wav, 4, chunkSize);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        putLeInt(wav, 16, 16);
        wav[20] = 1; wav[22] = 1;
        putLeInt(wav, 24, sampleRate);
        putLeInt(wav, 28, sampleRate * 2);
        wav[32] = 2; wav[34] = 16;
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        putLeInt(wav, 40, dataSize);
        Files.write(path, wav);
    }

    private static void putLeInt(byte[] b, int off, int val) {
        b[off] = (byte) (val & 0xFF);
        b[off + 1] = (byte) ((val >>> 8) & 0xFF);
        b[off + 2] = (byte) ((val >>> 16) & 0xFF);
        b[off + 3] = (byte) ((val >>> 24) & 0xFF);
    }
}
