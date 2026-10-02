package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.util.FileSystemUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryServiceArchiveIntegrityTest {
    @TempDir Path dir;

    @Test
    void fifteenInputsIncludingUnmatchedCollisionCommitAndMapAfterAlbumRename() throws Exception {
        var constructor = MusicConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        MusicConfig config = constructor.newInstance();
        config.setOutputDirectory(dir.resolve("output").toString());
        config.setRecoveryWorkDirectory(dir.resolve("work").toString());
        config.setRecoveryTrashDirectory(dir.resolve("trash").toString());
        config.setReviewQueuePath(dir.resolve("review.json").toString());
        config.setReviewStagingDirectory(dir.resolve("staging").toString());
        config.setProcessedFileLogPath(dir.resolve("processed.log").toString());
        config.setAutoRename(true);
        Path source = Files.createDirectory(dir.resolve("source"));
        List<File> files = new ArrayList<>();
        Map<String, MusicMetadata> metadata = new HashMap<>();
        List<ReviewItem.OnlineTrack> tracks = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            int length = 24000;
            ByteBuffer wav = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN);
            wav.put("RIFF".getBytes()).putInt(36 + length).put("WAVEfmt ".getBytes())
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(8000)
                .putInt(8000).putShort((short) 1).putShort((short) 8).put("data".getBytes()).putInt(length);
            while (wav.hasRemaining()) wav.put((byte) (i + 1));
            File file = Files.write(source.resolve(String.format("source-%02d.wav", i)), wav.array()).toFile();
            files.add(file);
            var md = new MusicMetadata();
            md.setArtist("Artist");
            md.setTitle("Song" + (i == 14 ? 0 : i));
            md.setTrackNo(String.valueOf(i == 0 || i == 14 ? 99 : 15 - i));
            metadata.put(file.getAbsolutePath(), md);
            if (i < 14) {
                var track = new ReviewItem.OnlineTrack();
                track.setTitle("Song" + i);
                track.setArtist("Artist");
                // Reverse output sorting relative to input sorting.
                track.setTrackNo(i == 0 ? 99 : 15 - i);
                track.setDuration(3);
                tracks.add(track);
            }
        }
        // The unmatched input has identical old tags to track 0, but no candidate
        // remains for it. The 99/Song0 output must be retained as a suffix.
        TagWriterService writer = new TagWriterService(config) {
            @Override public MusicMetadata readTags(File file) { return metadata.get(file.getAbsolutePath()); }
        };
        Map<String, String> targets = new HashMap<>();
        ProcessedFileLogger logger = new ProcessedFileLogger(config, null) {
            @Override public void markFileAsProcessed(File file, String recording, String artist,
                    String title, String album, String group, String target) {
                targets.put(file.getAbsolutePath(), target);
            }
        };
        var queue = new ReviewQueueService(config);
        var item = queue.enqueueRecoveryFolder(source.toString(), null, files, null, null);
        var candidate = new ReviewItem.OnlineCandidate();
        candidate.setId("candidate");
        candidate.setTitle("Album");
        candidate.setAlbumArtist("Artist");
        candidate.setTracks(tracks);
        item.setOnlineCandidates(List.of(candidate));
        queue.update(item);
        AudioFingerprintService fingerprint = new AudioFingerprintService(config) {
            @Override public List<Integer> extractDurationSequence(List<File> inputs) {
                return Collections.nCopies(inputs.size(), 3);
            }
        };
        try (var recovery = new RecoveryService(config, null, logger, queue, new FolderAlbumCache(null, null, null), null,
                new FileSystemUtils(config), writer, fingerprint, null)) {
            recovery.confirmOnlineCandidate(item.getId(), "candidate", "Album", "Artist", "", "", "Album Edition", null);
        }
        Path output = dir.resolve("output/Artist/Album Edition");
        try (var stream = Files.list(output)) { assertEquals(15, stream.filter(p -> p.toString().endsWith(".wav")).count()); }
        assertEquals(14.0 / 15, candidate.getTracks().stream().filter(t -> t.getMatchConfidence() >= 0.6).count() / 15.0);
        assertEquals(15, new HashSet<>(targets.values()).size());
        for (int i = 0; i < files.size(); i++) {
            Path target = Path.of(targets.get(files.get(i).getAbsolutePath()));
            assertEquals(output, target.getParent());
            // Payload identity proves actual source-to-target mapping, not sorted indexes.
            assertEquals(i + 1, Files.readAllBytes(target)[100]);
        }
        assertTrue(targets.get(files.get(14).getAbsolutePath()).contains("(2)"));
        assertTrue(files.stream().allMatch(File::exists));
    }
}
