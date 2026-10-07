package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.util.FileSystemUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.lux032.musicautotagger.service.ArchiveDuplicateDetectorTest.flac;
import static com.lux032.musicautotagger.service.ArchiveDuplicateDetectorTest.write;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 复现：LLM 自动归档到 艺术家/ぴ，同时保留了 FAILED 副本；
 * 对副本做联网确认时写到 艺术家/ぴ (Single)，音乐库里出现两份。
 */
class RecoveryServiceDuplicateGuardTest {

    @TempDir Path dir;
    private MusicConfig config;
    private Path output;
    private Path trash;

    @BeforeEach
    void setUp() throws Exception {
        var constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        config = constructor.newInstance();
        output = dir.resolve("output");
        trash = dir.resolve("trash");
        config.setOutputDirectory(output.toString());
        config.setRecoveryWorkDirectory(dir.resolve("work").toString());
        config.setRecoveryTrashDirectory(trash.toString());
        config.setReviewQueuePath(dir.resolve("data/review.json").toString());
        config.setReviewStagingDirectory(dir.resolve("staging").toString());
        config.setProcessedFileLogPath(dir.resolve("processed.log").toString());
    }

    private RecoveryService service() {
        return new RecoveryService(config, null, new ProcessedFileLogger(config, null),
            new ReviewQueueService(config), new FolderAlbumCache(null, null, null), null,
            new FileSystemUtils(config), new TagWriterService(config), null, null);
    }

    private AutoArchiveRegistry registry() {
        return AutoArchiveRegistry.forReviewQueuePath(config.getReviewQueuePath());
    }

    private Path workspace(String name, int md5Seed) throws Exception {
        Path ws = dir.resolve("work/online-" + name);
        write(ws.resolve("ナナヲアカリ/ぴ (Single)/1.01 ナナヲアカリ - ぴ.flac"), flac(md5Seed));
        return ws;
    }

    @Test
    void linkedAutoArchiveIsReplacedInsteadOfDuplicated() throws Exception {
        Path autoArchive = write(output.resolve("ナナヲアカリ/ぴ/01. ぴ.flac"), flac(1)).getParent();
        registry().record("ナナヲアカリ — ぴ", autoArchive, List.of("01. ぴ.flac"));

        try (RecoveryService recovery = service()) {
            recovery.commitReplacingDuplicates(workspace("a", 1), 1, "job",
                "ナナヲアカリ — ぴ", RecoveryService.DuplicatePolicy.ASK);
        }

        assertFalse(Files.exists(autoArchive), "自动归档应被移入回收站");
        assertTrue(Files.exists(output.resolve("ナナヲアカリ/ぴ (Single)/1.01 ナナヲアカリ - ぴ.flac")));
        assertTrue(registry().find("ナナヲアカリ — ぴ").isEmpty(), "链接应在替换后清除");
        try (var s = Files.list(trash)) {
            assertEquals(1, s.filter(p -> p.toString().endsWith(".json")).count());
        }
    }

    @Test
    void replacedAutoArchiveCanBeRestoredToLibrary() throws Exception {
        Path autoArchive = write(output.resolve("ナナヲアカリ/ぴ/01. ぴ.flac"), flac(1)).getParent();
        registry().record("x", autoArchive, List.of("01. ぴ.flac"));
        try (RecoveryService recovery = service()) {
            recovery.commitReplacingDuplicates(workspace("a", 1), 1, "job", "x", RecoveryService.DuplicatePolicy.ASK);
            List<Map<String, Object>> entries = recovery.listTrash();
            assertEquals(1, entries.size());
            assertEquals("OUTPUT", entries.get(0).get("sourceType"));
            assertEquals(true, entries.get(0).get("restorable"));
            recovery.restoreFromTrash((String) entries.get(0).get("id"));
        }
        assertTrue(Files.exists(autoArchive.resolve("01. ぴ.flac")));
    }

    @Test
    void modifiedAutoArchiveIsNotReplacedSilently() throws Exception {
        Path autoArchive = write(output.resolve("ナナヲアカリ/ぴ/01. ぴ.flac"), flac(1)).getParent();
        registry().record("x", autoArchive, List.of("01. ぴ.flac"));
        // 之后有人往目录里放了别的曲目
        write(autoArchive.resolve("02. other.flac"), flac(5));

        try (RecoveryService recovery = service()) {
            DuplicateArchiveException e = assertThrows(DuplicateArchiveException.class, () ->
                recovery.commitReplacingDuplicates(workspace("a", 1), 1, "job", "x", RecoveryService.DuplicatePolicy.ASK));
            assertEquals(false, e.getDuplicates().get(0).get("replaceable"));
        }
        assertTrue(Files.exists(autoArchive.resolve("01. ぴ.flac")));
        assertFalse(Files.exists(output.resolve("ナナヲアカリ/ぴ (Single)")));
    }

    @Test
    void unlinkedDuplicateAsksThenHonoursPolicy() throws Exception {
        Path existing = write(output.resolve("ナナヲアカリ/ぴ/01. ぴ.flac"), flac(1)).getParent();

        try (RecoveryService recovery = service()) {
            DuplicateArchiveException e = assertThrows(DuplicateArchiveException.class, () ->
                recovery.commitReplacingDuplicates(workspace("a", 1), 1, "job", null, RecoveryService.DuplicatePolicy.ASK));
            assertEquals(1, e.getDuplicates().size());
            assertEquals(true, e.getDuplicates().get(0).get("replaceable"));
            assertFalse(Files.exists(output.resolve("ナナヲアカリ/ぴ (Single)")), "询问阶段不得提交任何内容");

            recovery.commitReplacingDuplicates(workspace("b", 1), 1, "job", null, RecoveryService.DuplicatePolicy.REPLACE);
        }
        assertFalse(Files.exists(existing));
        assertTrue(Files.exists(output.resolve("ナナヲアカリ/ぴ (Single)/1.01 ナナヲアカリ - ぴ.flac")));
    }

    @Test
    void keepBothCommitsAlongsideExisting() throws Exception {
        Path existing = write(output.resolve("ナナヲアカリ/ぴ/01. ぴ.flac"), flac(1)).getParent();
        try (RecoveryService recovery = service()) {
            recovery.commitReplacingDuplicates(workspace("a", 1), 1, "job", null, RecoveryService.DuplicatePolicy.KEEP_BOTH);
        }
        assertTrue(Files.exists(existing));
        assertTrue(Files.exists(output.resolve("ナナヲアカリ/ぴ (Single)")));
    }

    @Test
    void differentAudioIsNotADuplicate() throws Exception {
        write(output.resolve("ナナヲアカリ/ぴ/01. ぴ.flac"), flac(2));
        try (RecoveryService recovery = service()) {
            recovery.commitReplacingDuplicates(workspace("a", 1), 1, "job", null, RecoveryService.DuplicatePolicy.ASK);
        }
        assertTrue(Files.exists(output.resolve("ナナヲアカリ/ぴ")));
        assertTrue(Files.exists(output.resolve("ナナヲアカリ/ぴ (Single)")));
    }

    @Test
    void failedCommitRestoresReplacedArchive() throws Exception {
        Path autoArchive = write(output.resolve("ナナヲアカリ/ぴ/01. ぴ.flac"), flac(1)).getParent();
        registry().record("x", autoArchive, List.of("01. ぴ.flac"));
        // 目标目录被一个无关内容占用 → online.destination.exists
        write(output.resolve("ナナヲアカリ/ぴ (Single)/unrelated.txt"), new byte[] {1});

        try (RecoveryService recovery = service()) {
            IOException e = assertThrows(IOException.class, () ->
                recovery.commitReplacingDuplicates(workspace("a", 1), 1, "job", "x", RecoveryService.DuplicatePolicy.ASK));
            assertEquals("online.destination.exists", e.getMessage());
        }
        assertTrue(Files.exists(autoArchive.resolve("01. ぴ.flac")), "提交失败时被替换的自动归档必须还原");
        assertTrue(registry().find("x").isPresent(), "提交失败时链接保留");
    }

}
