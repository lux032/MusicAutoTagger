package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MbContributionItem;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ProcessedRecord;
import com.lux032.musicautotagger.util.FileSystemUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MusicBrainzReleaseBackfillServiceTest {

    private static MusicConfig newConfig(Path dir) throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MusicConfig cfg = ctor.newInstance();
        cfg.setMonitorDirectory(dir.resolve("monitor").toString());
        cfg.setOutputDirectory(dir.resolve("output").toString());
        cfg.setProcessedFileLogPath(dir.resolve("processed.log").toString());
        cfg.setDbType("file");
        return cfg;
    }

    @Test
    void extractsReleaseMbidFromUrlsAndPureUuids() {
        String id = "44444444-4444-4444-8444-444444444444";
        assertEquals(id, MusicBrainzReleaseBackfillService.extractReleaseMbid(id));
        assertEquals(id, MusicBrainzReleaseBackfillService.extractReleaseMbid("https://musicbrainz.org/release/" + id));
        assertEquals(id, MusicBrainzReleaseBackfillService.extractReleaseMbid("https://beta.musicbrainz.org/release/" + id + "/edit-relationships?foo=1"));
        assertNull(MusicBrainzReleaseBackfillService.extractReleaseMbid("00000000-0000-0000-0000-000000000000"));
        assertNull(MusicBrainzReleaseBackfillService.extractReleaseMbid("https://example.com/release/" + id));
        assertNull(MusicBrainzReleaseBackfillService.extractReleaseMbid("not-a-uuid"));
    }

    @Test
    void backfillsOfficialMbidsPreservesExistingCreditsAndMonitorLineageAndRejectsMismatch(@TempDir Path tempDir) throws Exception {
        MusicConfig config = newConfig(tempDir);
        Path monitorDir = Files.createDirectories(tempDir.resolve("monitor/HIMEHINA - VAMPY"));
        Path monitorTrack1 = monitorDir.resolve("01. VAMPY.wav");
        Path outputDir = Files.createDirectories(tempDir.resolve("output/HIMEHINA/VAMPY"));
        Path archivedTrack1 = outputDir.resolve("1.01 HIMEHINA - VAMPY.wav");
        writeWav(monitorTrack1, 2);
        writeWav(archivedTrack1, 2);

        TagWriterService tagWriter = new TagWriterService(config);
        // 预先在归档音频中写入变更 1 的联网标签（含带括号原文幕后人员与 MBID）
        MusicMetadata initial = new MusicMetadata();
        initial.setTitle("VAMPY");
        initial.setAlbum("VAMPY");
        initial.setArtist("HIMEHINA");
        initial.setAlbumArtist("HIMEHINA");
        initial.setDiscNo("1");
        initial.setTrackNo("1");
        initial.setComposer("雨良 Amala (ninelive.)");
        initial.setComposers(List.of("雨良 Amala (ninelive.)"));
        initial.setComposerIds(List.of("74b4d464-7b7c-48c4-b96d-c5adf7f8e1c9"));
        assertTrue(tagWriter.updateTagsOnExistingFile(archivedTrack1.toFile(), initial, null));

        // 预先登记原监控目录主记录（recordingId=ONLINE_SEARCH, targetFilePath=archivedTrack1）
        ProcessedFileLogger logger = new ProcessedFileLogger(config, null);
        logger.markFileAsProcessed(monitorTrack1.toFile(), "ONLINE_SEARCH", "HIMEHINA", "VAMPY", "VAMPY",
            null, archivedTrack1.toString());

        MusicBrainzClient.ReleaseDetail officialDetail = new MusicBrainzClient.ReleaseDetail(
            "44444444-4444-4444-8444-444444444444",
            "55555555-5555-4555-8555-555555555555",
            "VAMPY", "Japanese digital EP", "official", "2026-11-27", "JP",
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

        MusicBrainzClient.ReleaseDetail mismatchDetail = new MusicBrainzClient.ReleaseDetail(
            "88888888-8888-4888-8888-888888888888",
            "55555555-5555-4555-8555-555555555555",
            "Wrong Release", null, "official", "2026-11-27", "JP",
            null, null, "ep", List.of(), false,
            "HIMEHINA", List.of("HIMEHINA"), null, null, null,
            null, null, null, null, 1,
            List.of(new MusicBrainzClient.ReleaseMediumDetail(1, "Digital Media", null, 1, List.of(
                new MusicBrainzClient.ReleaseTrackDetail(
                    1, 1, "1",
                    "66666666-6666-4666-8666-666666666669",
                    "77777777-7777-4777-8777-777777777779",
                    "Different Song", 300_000L, "HIMEHINA", List.of("HIMEHINA"), null, null, null)
            )))
        );

        MusicBrainzClient stubMb = new MusicBrainzClient(config) {
            @Override
            public Optional<ReleaseDetail> getFullReleaseWithTracklist(String rawReleaseId) {
                if ("44444444-4444-4444-8444-444444444444".equals(rawReleaseId)) return Optional.of(officialDetail);
                if ("88888888-8888-4888-8888-888888888888".equals(rawReleaseId)) return Optional.of(mismatchDetail);
                return Optional.empty();
            }
        };

        MusicBrainzReleaseBackfillService svc = new MusicBrainzReleaseBackfillService(
            config, stubMb, tagWriter, logger, new FileSystemUtils(config));

        MbContributionItem item = new MbContributionItem();
        item.setId("c-backfill");
        item.setArchivedOutputDir(outputDir.toString());
        item.setArchivedFilePaths(List.of(archivedTrack1.toString()));

        // 时长严重失配（2s vs 300s）必须拒绝修改本地文件
        MusicBrainzReleaseBackfillService.BackfillException mismatch = assertThrows(
            MusicBrainzReleaseBackfillService.BackfillException.class,
            () -> svc.linkAndBackfill(item, "https://musicbrainz.org/release/88888888-8888-4888-8888-888888888888"));
        assertEquals(400, mismatch.getHttpStatus());
        assertEquals("mb.contribution.track.mismatch", mismatch.getErrorCode());

        // 同轨数同 2s 时长但专辑与曲目标题完全矛盾的无关发行也必须被拒绝
        MusicBrainzClient.ReleaseDetail sameLenWrongAlbum = new MusicBrainzClient.ReleaseDetail(
            "99999999-9999-4999-8999-999999999999",
            "55555555-5555-4555-8555-555555555555",
            "Completely Unrelated Album", null, "official", "2026-11-27", "JP",
            null, null, "ep", List.of(), false,
            "Other Artist", List.of("Other Artist"), null, null, null,
            null, null, null, null, 1,
            List.of(new MusicBrainzClient.ReleaseMediumDetail(1, "Digital Media", null, 1, List.of(
                new MusicBrainzClient.ReleaseTrackDetail(
                    1, 1, "1",
                    "66666666-6666-4666-8666-666666666668",
                    "77777777-7777-4777-8777-777777777778",
                    "Completely Different Song", 2000L, "Other Artist", List.of("Other Artist"), null, null, null)
            )))
        );
        MusicBrainzClient wrongTitleClient = new MusicBrainzClient(config) {
            @Override
            public Optional<ReleaseDetail> getFullReleaseWithTracklist(String rawReleaseId) {
                return Optional.of(sameLenWrongAlbum);
            }
        };
        MusicBrainzReleaseBackfillService wrongTitleSvc = new MusicBrainzReleaseBackfillService(
            config, wrongTitleClient, tagWriter, logger, new FileSystemUtils(config));
        MusicBrainzReleaseBackfillService.BackfillException wrongTitleEx = assertThrows(
            MusicBrainzReleaseBackfillService.BackfillException.class,
            () -> wrongTitleSvc.linkAndBackfill(item, "99999999-9999-4999-8999-999999999999"));
        assertEquals("mb.contribution.track.mismatch", wrongTitleEx.getErrorCode());

        // 清单中有缺失文件时必须拒绝回填，不得静默跳过缺失文件
        MbContributionItem missingFileItem = new MbContributionItem();
        missingFileItem.setId("c-missing");
        missingFileItem.setArchivedOutputDir(outputDir.toString());
        missingFileItem.setArchivedFilePaths(List.of(archivedTrack1.toString(), outputDir.resolve("1.02 missing.wav").toString()));
        MusicBrainzReleaseBackfillService.BackfillException missingEx = assertThrows(
            MusicBrainzReleaseBackfillService.BackfillException.class,
            () -> svc.linkAndBackfill(missingFileItem, "44444444-4444-4444-8444-444444444444"));
        assertEquals("mb.contribution.local.files.missing", missingEx.getErrorCode());

        // 符号链接逃出 outputDirectory 的文件必须被拒绝
        Path outsideWav = tempDir.resolve("outside.wav");
        writeWav(outsideWav, 2);
        Path symlinkInOut = outputDir.resolve("1.01 symlink.wav");
        Files.createSymbolicLink(symlinkInOut, outsideWav);
        MbContributionItem symlinkItem = new MbContributionItem();
        symlinkItem.setId("c-sym");
        symlinkItem.setArchivedOutputDir(outputDir.toString());
        symlinkItem.setArchivedFilePaths(List.of(symlinkInOut.toString()));
        MusicBrainzReleaseBackfillService.BackfillException symEx = assertThrows(
            MusicBrainzReleaseBackfillService.BackfillException.class,
            () -> svc.linkAndBackfill(symlinkItem, "44444444-4444-4444-8444-444444444444"));
        assertEquals("mb.contribution.local.files.missing", symEx.getErrorCode());
        Files.deleteIfExists(symlinkInOut);

        // 合法 Release 回填成功
        MusicBrainzReleaseBackfillService.BackfillResult res =
            svc.linkAndBackfill(item, "https://musicbrainz.org/release/44444444-4444-4444-8444-444444444444");
        assertEquals(1, res.updatedFiles());
        assertEquals(1, res.updatedRecords());
        assertEquals("77777777-7777-4777-8777-777777777771", res.trackMappings().get(0).getRecordingId());

        // 校验落盘音频：官方 MBID 与发行字段已补全，且原有带括号作曲原文及 MBID 完整保留
        MusicMetadata after = tagWriter.readTags(archivedTrack1.toFile());
        assertEquals("44444444-4444-4444-8444-444444444444", after.getReleaseId());
        assertEquals("55555555-5555-4555-8555-555555555555", after.getReleaseGroupId());
        assertEquals("77777777-7777-4777-8777-777777777771", after.getRecordingId());
        assertEquals("4580000000001", after.getBarcode());
        assertEquals("HH-001", after.getCatalogNumber());
        assertEquals("LaRa", after.getRecordLabel());
        assertEquals("雨良 Amala (ninelive.)", after.getComposer(), "回填官方 Release 时不得覆盖原有幕后人员原文");

        // 校验 processed_files 原位升级且监控血缘未分裂（只有 1 条记录，filePath 仍是 monitorTrack1）
        List<ProcessedRecord> allRecs = logger.findRecords(new ProcessedRecordQuery(), 50);
        assertEquals(1, allRecs.size(), "回填不得制造分裂记录");
        assertEquals(monitorTrack1.toString(), allRecs.get(0).filePath);
        assertEquals(archivedTrack1.toString(), allRecs.get(0).targetFilePath);
        assertEquals("77777777-7777-4777-8777-777777777771", allRecs.get(0).recordingId);
        assertEquals("55555555-5555-4555-8555-555555555555", allRecs.get(0).releaseGroupId);
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
