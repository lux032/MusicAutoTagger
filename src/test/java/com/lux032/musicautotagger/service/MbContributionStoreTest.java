package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.model.MbContributionItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MbContributionStoreTest {

    @Test
    void persistsDeduplicatesProtectsBackupPathsAndCleansTerminalAndOrphanCovers(@TempDir Path tempDir) throws Exception {
        Path storeFile = tempDir.resolve("mb-contributions.json");
        Path coverDir = tempDir.resolve("mb-contribution-covers");
        MbContributionStore store = new MbContributionStore(storeFile, coverDir);

        byte[] png = makePng(64, 64);

        MbContributionItem item = new MbContributionItem();
        item.setReviewItemId("rev-1");
        item.setArchivedOutputDir(tempDir.resolve("Music/HIMEHINA/VAMPY").toString());
        item.setStatus(MbContributionItem.Status.READY_TO_SEED);
        item.setCheckCase(MbContributionItem.CheckCase.MISSING_RELEASE_GROUP);
        item.getDraft().setTitle("VAMPY");
        item.getDraft().setAlbumArtist("HIMEHINA");
        MbContributionItem saved = store.upsert(item);
        assertNotNull(saved.getId());

        Optional<MbContributionStore.SavedCoverBackup> backup = store.saveCoverBackup(saved.getId(), png);
        assertTrue(backup.isPresent());
        assertEquals("image/png", backup.get().mime());
        assertEquals(64, backup.get().width());
        assertEquals(64, backup.get().height());
        Path backupPath = coverDir.resolve(backup.get().fileName());
        assertTrue(Files.isRegularFile(backupPath));

        store.update(saved.getId(), it -> {
            MbContributionItem.CoverInfo cover = new MbContributionItem.CoverInfo();
            cover.setOrigin("FOLDER");
            cover.setLocalBackupFileName(backup.get().fileName());
            cover.setMime(backup.get().mime());
            cover.setWidth(backup.get().width());
            cover.setHeight(backup.get().height());
            cover.setBytes(backup.get().bytes());
            it.setCover(cover);
        });

        // 路径穿越防护
        assertTrue(store.resolveSafeCoverPath("../secret.png").isEmpty());
        assertTrue(store.resolveSafeCoverPath("sub/a.png").isEmpty());
        assertTrue(store.resolveSafeCoverPath("a\\b.png").isEmpty());
        assertTrue(store.resolveSafeCoverPath("valid-123.png").isPresent());

        // 同 reviewItemId 重复 upsert 应去重复用 ID
        MbContributionItem dup = new MbContributionItem();
        dup.setReviewItemId("rev-1");
        dup.setArchivedOutputDir(tempDir.resolve("Music/HIMEHINA/VAMPY").toString());
        dup.setStatus(MbContributionItem.Status.READY_TO_SEED);
        MbContributionItem.CoverInfo keepCover = new MbContributionItem.CoverInfo();
        keepCover.setLocalBackupFileName(backup.get().fileName());
        dup.setCover(keepCover);
        MbContributionItem deduped = store.upsert(dup);
        assertEquals(saved.getId(), deduped.getId());
        assertEquals(1, store.list(null, false).size());

        // 跨重启重新加载
        MbContributionStore reloaded = new MbContributionStore(storeFile, coverDir);
        MbContributionItem loaded = reloaded.get(saved.getId());
        assertNotNull(loaded);
        assertTrue(reloaded.findExistingCoverBackup(loaded).isPresent());

        // 孤儿封面清理：活跃项不删，超过 30 天的本存储孤儿文件自动删除，非本存储文件绝不误删
        Path orphanOld = coverDir.resolve("orphan-old.jpg");
        Files.write(orphanOld, png);
        Files.setLastModifiedTime(orphanOld, FileTime.from(Instant.now().minusSeconds(35L * 86400L)));
        Path orphanFresh = coverDir.resolve("orphan-fresh.jpg");
        Files.write(orphanFresh, png);
        Path foreignOld = coverDir.resolve("unrelated.txt");
        Files.writeString(foreignOld, "keep me");
        Files.setLastModifiedTime(foreignOld, FileTime.from(Instant.now().minusSeconds(60L * 86400L)));
        int cleaned = reloaded.cleanupOrphanCoverBackups(System.currentTimeMillis());
        assertEquals(1, cleaned);
        assertFalse(Files.exists(orphanOld));
        assertTrue(Files.exists(orphanFresh));
        assertTrue(Files.exists(foreignOld), "孤儿清理不得删除非本存储管理的无关文件");
        assertTrue(Files.exists(backupPath));

        // 符号链接目录防护：coverBackupDir 若为符号链接必须拒绝读写和清理
        Path externalDir = Files.createDirectories(tempDir.resolve("external-mount"));
        Path externalFile = externalDir.resolve("old-cover.jpg");
        Files.write(externalFile, png);
        Files.setLastModifiedTime(externalFile, FileTime.from(Instant.now().minusSeconds(60L * 86400L)));
        Path symlinkCoverDir = tempDir.resolve("symlink-covers");
        Files.createSymbolicLink(symlinkCoverDir, externalDir);
        MbContributionStore symlinkStore = new MbContributionStore(tempDir.resolve("symlink-store.json"), symlinkCoverDir);
        assertTrue(symlinkStore.saveCoverBackup("c-sym", png).isEmpty(), "符号链接封面目录必须拒绝写入");
        assertEquals(0, symlinkStore.cleanupOrphanCoverBackups(System.currentTimeMillis()));
        assertTrue(Files.exists(externalFile), "符号链接指向的外部目录文件绝不得被清理");

        // 进入终态（DISMISSED）立即自动删除原图备份
        reloaded.update(saved.getId(), it -> it.setStatus(MbContributionItem.Status.DISMISSED));
        assertFalse(Files.exists(backupPath));
        assertNull(reloaded.get(saved.getId()).getCover().getLocalBackupFileName());
        assertEquals(0, ((Number) reloaded.stats().get("actionable")).intValue());
        assertEquals(1, ((Number) reloaded.stats().get("dismissed")).intValue());
    }

    private static byte[] makePng(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
