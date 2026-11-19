package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.util.FileSystemUtils;
import com.lux032.musicautotagger.util.FileNameSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.SupportedFileFormat;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.KeyNotFoundException;
import org.jaudiotagger.tag.Tag;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Refreshes only the album-artist credit on existing VA files in the output library. */
@Slf4j
public final class AlbumArtistRefreshService implements AutoCloseable {
    private static final Pattern MBID = Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Set<String> AUDIO_SUFFIXES = Arrays.stream(SupportedFileFormat.values())
        .map(SupportedFileFormat::getFilesuffix).collect(Collectors.toUnmodifiableSet());

    private final MusicConfig config;
    private final MusicBrainzClient musicBrainz;
    private final TagWriterService writer;
    private final ProcessedFileLogger processedLogger;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "va-album-artist-refresh");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean cancelled;
    private boolean running;
    private String phase = "idle";
    private int scanned, total, updated, unchanged, skipped, failed, filesUpdated, readErrors;
    private String current = "", lastIssue = "", lastIssueAlbum = "";

    public record Status(boolean running, boolean cancelling, String phase, int scanned, int total,
                         int updated, int unchanged, int skipped, int failed, int filesUpdated, int readErrors,
                         String current, String lastIssue, String lastIssueAlbum) {}

    private record Track(Path path, BasicFileAttributes identity, String album, String albumArtist,
                 String releaseId, String releaseGroupId, String recordingId, String title, int disc, int number) {}

    private record Location(Path directory, String album) {}
    private record Album(String releaseId, List<Track> tracks, boolean ambiguous) {}
    private record Position(int disc, int number) {}
    private enum Result { UPDATED, UNCHANGED, UNMATCHED, PARTIAL, CONFLICT, CHANGED, FAILED }

    public AlbumArtistRefreshService(MusicConfig config, MusicBrainzClient musicBrainz, TagWriterService writer,
                                     ProcessedFileLogger processedLogger) {
        this.config = config;
        this.musicBrainz = musicBrainz;
        this.writer = writer;
        this.processedLogger = processedLogger;
    }

    public synchronized boolean start() {
        if (running || executor.isShutdown()) return false;
        running = true;
        cancelled = false;
        phase = "scanning";
        scanned = total = updated = unchanged = skipped = failed = filesUpdated = readErrors = 0;
        current = lastIssue = lastIssueAlbum = "";
        executor.execute(this::run);
        return true;
    }

    public synchronized void cancel() {
        if (running) cancelled = true;
    }

    public synchronized Status getStatus() {
        return new Status(running, running && cancelled, phase, scanned, total, updated, unchanged, skipped,
            failed, filesUpdated, readErrors, current, lastIssue, lastIssueAlbum);
    }

    private void run() {
        String end = "finished";
        try {
            LyricsProtectedPaths paths = LyricsProtectedPaths.capture(config);
            if (!Files.isDirectory(paths.root, LinkOption.NOFOLLOW_LINKS) || !paths.root.equals(paths.root.toRealPath())) {
                throw new IOException("unsafe.output.root");
            }
            paths.validateRoot();
            Map<Location, List<Track>> locations = scan(paths);
            checkCancelled();
            List<Album> albums = groupAlbums(locations);
            synchronized (this) {
                total = albums.size();
                phase = "refreshing";
            }
            Set<String> lookedUpReleases = new HashSet<>();
            for (Album album : albums) {
                checkCancelled();
                Track first = album.tracks().get(0);
                String label = first.album().isBlank() ? paths.root.relativize(first.path()).toString() : first.album();
                synchronized (this) { current = label; }
                try {
                    record(refresh(paths, album, lookedUpReleases), label);
                } catch (IOException | RuntimeException e) {
                    log.warn("VA 专辑艺术家刮削失败: {}", first.path(), e);
                    record(Result.FAILED, label);
                }
            }
        } catch (InterruptedException e) {
            end = "cancelled";
        } catch (Exception e) {
            end = "failed";
            log.warn("无法扫描 VA 专辑", e);
        } finally {
            synchronized (this) {
                phase = cancelled && !"failed".equals(end) ? "cancelled" : end;
                running = false;
                current = "";
            }
        }
    }

    private Map<Location, List<Track>> scan(LyricsProtectedPaths paths) throws IOException {
        Map<Location, List<Track>> locations = new LinkedHashMap<>();
        Files.walkFileTree(paths.root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (cancelled) return FileVisitResult.TERMINATE;
                return paths.excluded(dir, attrs) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (cancelled) return FileVisitResult.TERMINATE;
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                int dot = name.lastIndexOf('.');
                if (attrs.isRegularFile() && dot >= 0 && AUDIO_SUFFIXES.contains(name.substring(dot + 1))
                        && !paths.excluded(file, attrs)) {
                    synchronized (AlbumArtistRefreshService.this) { scanned++; }
                    try {
                        Track track = readTrack(file, attrs);
                        if (track != null) {
                            Path directory = file.getParent();
                            if (!directory.equals(paths.root) && FileSystemUtils.isDiscFolderName(directory.getFileName().toString())) {
                                directory = directory.getParent();
                            }
                            Location location = new Location(directory, normalized(track.album()));
                            locations.computeIfAbsent(location, key -> new ArrayList<>()).add(track);
                        }
                    } catch (Exception e) {
                        visitFileFailed(file, new IOException("Unable to read audio tags", e));
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                log.warn("无法读取 VA 刮削扫描路径: {}", file, e);
                synchronized (AlbumArtistRefreshService.this) { readErrors++; }
                return FileVisitResult.CONTINUE;
            }
        });
        return locations;
    }

    private static Track readTrack(Path file, BasicFileAttributes attrs) throws Exception {
        Tag tag = AudioFileIO.read(file.toFile()).getTag();
        if (tag == null) return null;
        return new Track(file, attrs, field(tag, FieldKey.ALBUM), albumArtist(tag),
            field(tag, FieldKey.MUSICBRAINZ_RELEASEID), field(tag, FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID),
            field(tag, FieldKey.MUSICBRAINZ_TRACK_ID), field(tag, FieldKey.TITLE),
            position(field(tag, FieldKey.DISC_NO)), position(field(tag, FieldKey.TRACK)));
    }

    static String albumArtist(Tag tag) {
        try {
            List<String> values = tag.getAll(FieldKey.ALBUM_ARTIST).stream()
                .filter(value -> value != null && !value.isBlank()).map(String::strip).toList();
            if (values.stream().allMatch(AlbumArtistRefreshService::isVa)) {
                return values.isEmpty() ? "" : values.get(0);
            }
            return String.join(" / ", values);
        } catch (KeyNotFoundException e) {
            return "";
        }
    }

    private static String field(Tag tag, FieldKey key) {
        try {
            String value = tag.getFirst(key);
            return value == null ? "" : value.strip();
        } catch (KeyNotFoundException e) {
            return "";
        }
    }

    private static List<Album> groupAlbums(Map<Location, List<Track>> locations) {
        List<Album> albums = new ArrayList<>();
        for (List<Track> tracks : locations.values()) {
            Map<String, List<Track>> byRelease = new LinkedHashMap<>();
            List<Track> withoutId = new ArrayList<>();
            List<Track> invalidId = new ArrayList<>();
            for (Track track : tracks) {
                String releaseId = id(track.releaseId());
                if (!releaseId.isEmpty()) byRelease.computeIfAbsent(releaseId, key -> new ArrayList<>()).add(track);
                else if (track.releaseId().isBlank()) withoutId.add(track);
                else invalidId.add(track);
            }
            if (byRelease.size() == 1 && invalidId.isEmpty()) {
                byRelease.values().iterator().next().addAll(withoutId);
            } else {
                addAlbum(albums, "", withoutId, !byRelease.isEmpty() || !invalidId.isEmpty());
            }
            byRelease.forEach((releaseId, members) -> addAlbum(albums, releaseId, members, false));
            addAlbum(albums, "", invalidId, true);
        }
        return albums;
    }

    private static void addAlbum(List<Album> albums, String releaseId, List<Track> tracks, boolean ambiguous) {
        if (tracks.stream().anyMatch(track -> isVa(track.albumArtist()))) {
            albums.add(new Album(releaseId, tracks, ambiguous));
        }
    }

    private Result refresh(LyricsProtectedPaths paths, Album album, Set<String> lookedUpReleases)
            throws IOException, InterruptedException {
        if (album.ambiguous()) return Result.CONFLICT;
        JsonNode release = resolveRelease(album, lookedUpReleases);
        if (release == null) return Result.UNMATCHED;
        MusicBrainzClient.ReleaseTagBundle credit = musicBrainz.parseReleaseTagBundle(release);
        String artist = credit.getAlbumArtistCredit();
        if (artist == null || credit.getAlbumArtists().isEmpty()
                || credit.getAlbumArtists().size() != release.path("artist-credit").size()
                || credit.getAlbumArtists().stream().map(AlbumArtistRefreshService::normalized)
                    .anyMatch(name -> Set.of("", "unknown", "unknown artist", "[unknown]").contains(name))) {
            return Result.UNMATCHED;
        }
        List<Track> confirmed = new ArrayList<>();
        boolean unmatchedVa = false;
        for (Track track : album.tracks()) {
            if (album.releaseId().isEmpty() || !track.releaseId().isEmpty()
                    || (compatibleGroup(release, track) && matchPosition(release, track) != null)) {
                confirmed.add(track);
            } else {
                unmatchedVa |= isVa(track.albumArtist());
            }
        }
        if (confirmed.stream().noneMatch(track -> isVa(track.albumArtist()))) return Result.UNMATCHED;
        for (Track track : confirmed) {
            if (!track.albumArtist().isBlank() && !isVa(track.albumArtist())
                    && !normalized(track.albumArtist()).equals(normalized(artist))) return Result.CONFLICT;
        }
        if (isVa(artist)) return unmatchedVa ? Result.PARTIAL : Result.UNCHANGED;

        checkCancelled();
        try (var guard = LibraryWriteGuard.acquireInterruptibly()) {
            checkCancelled();
            for (Track track : confirmed) {
                if (!writer.sameAlbumArtistRefreshFile(paths.root, track.path(), track.identity())) return Result.CHANGED;
            }
            boolean writeFailed = false;
            for (Track track : confirmed) {
                if (!isVa(track.albumArtist())) continue;
                try {
                    refreshAndRelocate(paths, track, credit);
                    synchronized (this) { filesUpdated++; }
                } catch (IOException e) {
                    log.warn("无法更新专辑艺术家: {}", track.path(), e);
                    writeFailed = true;
                }
            }
            if (writeFailed) return Result.FAILED;
            return unmatchedVa ? Result.PARTIAL : Result.UPDATED;
        }
    }

    private void refreshAndRelocate(LyricsProtectedPaths paths, Track track, MusicBrainzClient.ReleaseTagBundle credit)
            throws IOException {
        Path source = track.path();
        Path artistDir = paths.root.resolve(FileNameSanitizer.sanitize(credit.getAlbumArtistCredit()));
        Path destination = track.album().isBlank()
            ? artistDir : artistDir.resolve(FileNameSanitizer.sanitize(track.album()));
        Path oldParent = source.getParent();
        boolean discFolder = !oldParent.equals(paths.root)
            && FileSystemUtils.isDiscFolderName(oldParent.getFileName().toString());
        if (discFolder) destination = destination.resolve(oldParent.getFileName());
        Path target = destination.resolve(source.getFileName());
        if (source.equals(target)) {
            writer.refreshAlbumArtist(paths.root, source, target, track.identity(), credit);
            return;
        }

        List<Path> createdDirectories = new ArrayList<>();
        try {
            prepareDestination(paths, destination, createdDirectories);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
            writer.refreshAlbumArtist(paths.root, source, target, track.identity(), credit);
            retireSource(paths.root, track, target);
        } catch (IOException e) {
            for (int i = createdDirectories.size() - 1; i >= 0; i--) {
                removeEmptyDirectory(paths.root, createdDirectories.get(i));
            }
            throw e;
        }

        if (removeEmptyDirectory(paths.root, oldParent) && discFolder) {
            removeEmptyDirectory(paths.root, oldParent.getParent());
        }
    }

    private static void prepareDestination(LyricsProtectedPaths paths, Path destination, List<Path> created)
            throws IOException {
        Path current = paths.root;
        for (Path component : paths.root.relativize(destination)) {
            current = current.resolve(component);
            for (var entry : paths.entries) {
                if (current.startsWith(entry.path())) throw new IOException("Destination is a protected path");
            }
            if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(current);
                created.add(current);
            }
            BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isDirectory() || paths.excluded(current, attrs) || !current.equals(current.toRealPath())) {
                throw new IOException("Destination is not a safe library directory: " + current);
            }
        }
    }

    private void retireSource(Path root, Track track, Path target) throws IOException {
        Path source = track.path();
        boolean historyUpdated = false;
        try {
            if (!writer.sameAlbumArtistRefreshFile(root, source, track.identity())) {
                throw new IOException("Source changed during relocation");
            }
            processedLogger.relocateTargetPath(source, target);
            historyUpdated = true;
            if (Files.notExists(source, LinkOption.NOFOLLOW_LINKS)) return;
            if (!writer.sameAlbumArtistRefreshFile(root, source, track.identity())) {
                throw new IOException("Source changed during relocation");
            }
            Files.deleteIfExists(source);
        } catch (IOException e) {
            if (historyUpdated) {
                try {
                    processedLogger.relocateTargetPath(target, source);
                } catch (IOException rollbackError) {
                    e.addSuppressed(rollbackError);
                    // Preserve the new copy if history still points to it.
                    throw e;
                }
            }
            try {
                Files.delete(target);
            } catch (IOException rollbackError) {
                e.addSuppressed(rollbackError);
            }
            throw e;
        }
    }

    private static boolean removeEmptyDirectory(Path root, Path directory) {
        if (directory.equals(root)) return false;
        try {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.equals(directory.toRealPath())) {
                return false;
            }
            return Files.deleteIfExists(directory);
        } catch (DirectoryNotEmptyException e) {
            return false;
        } catch (IOException e) {
            log.warn("无法清理空专辑目录: {}", directory, e);
            return false;
        }
    }

    private JsonNode resolveRelease(Album album, Set<String> lookedUpReleases) throws IOException, InterruptedException {
        if (!album.releaseId().isEmpty()) {
            JsonNode release = fetchRelease(album.releaseId(), lookedUpReleases);
            if (release == null || album.tracks().stream().filter(track -> !track.releaseId().isEmpty())
                    .anyMatch(track -> !compatibleGroup(release, track))) return null;
            return release;
        }

        String title = album.tracks().get(0).album();
        if (title.isBlank()) return null;
        Set<String> groups = new HashSet<>();
        for (Track track : album.tracks()) {
            if (track.releaseGroupId().isEmpty()) continue;
            String group = id(track.releaseGroupId());
            if (group.isEmpty()) return null;
            groups.add(group);
        }
        if (groups.size() > 1) return null;
        String releaseGroup = groups.isEmpty() ? "" : groups.iterator().next();

        JsonNode match = null;
        Set<String> seen = new HashSet<>();
        for (JsonNode candidate : musicBrainz.searchReleasesForAlbumArtistRefresh(title, releaseGroup)) {
            checkCancelled();
            String releaseId = id(candidate.path("id").asText(""));
            if (releaseId.isEmpty() || !normalized(title).equals(normalized(candidate.path("title").asText("")))
                    || !seen.add(releaseId)) continue;
            JsonNode release = fetchRelease(releaseId, lookedUpReleases);
            if (release == null) return null;
            if (matchesWithoutReleaseId(release, album.tracks())) {
                if (match != null) return null;
                match = release;
            }
        }
        return match;
    }

    private JsonNode fetchRelease(String releaseId, Set<String> lookedUpReleases) throws IOException, InterruptedException {
        checkCancelled();
        JsonNode release = musicBrainz.getFullReleaseById(releaseId, !lookedUpReleases.contains(releaseId));
        if (release == null || !releaseId.equals(id(release.path("id").asText("")))) return null;
        lookedUpReleases.add(releaseId);
        return release;
    }

    private static boolean matchesWithoutReleaseId(JsonNode release, List<Track> tracks) {
        if (tracks.isEmpty() || tracks.get(0).album().isBlank()
                || !normalized(tracks.get(0).album()).equals(normalized(release.path("title").asText("")))) return false;
        Set<Position> positions = new HashSet<>();
        boolean hasRecordingId = false;
        for (Track track : tracks) {
            if (!compatibleGroup(release, track)) return false;
            Position match = matchPosition(release, track);
            if (match == null || !positions.add(match)) return false;
            hasRecordingId |= !id(track.recordingId()).isEmpty();
        }
        return hasRecordingId || positions.size() >= 2;
    }

    private static boolean compatibleGroup(JsonNode release, Track track) {
        if (track.releaseGroupId().isEmpty()) return true;
        String releaseGroup = id(release.path("release-group").path("id").asText(""));
        return !releaseGroup.isEmpty() && releaseGroup.equals(id(track.releaseGroupId()));
    }

    private static Position matchPosition(JsonNode release, Track local) {
        String recordingId = id(local.recordingId());
        if (recordingId.isEmpty() && (local.number() <= 0 || local.title().isBlank())) return null;
        Position match = null;
        for (JsonNode medium : release.path("media")) {
            int disc = medium.path("position").asInt();
            if (disc <= 0 || (local.disc() > 0 && disc != local.disc())) continue;
            for (JsonNode track : medium.path("tracks")) {
                int number = track.path("position").asInt();
                if (number <= 0 || (local.number() > 0 && number != local.number())) continue;
                JsonNode recording = track.path("recording");
                boolean matches = recordingId.isEmpty()
                    ? normalized(local.title()).equals(normalized(track.path("title").asText(recording.path("title").asText(""))))
                    : recordingId.equals(id(recording.path("id").asText("")));
                if (matches) {
                    if (match != null) return null;
                    match = new Position(disc, number);
                }
            }
        }
        return match;
    }

    private static int position(String value) {
        if (value.isBlank()) return 0;
        try {
            return Integer.parseInt(value.split("/", 2)[0].strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static boolean isVa(String credit) {
        String value = normalized(credit);
        return value.equals("va") || value.equals("various artists");
    }

    private static String normalized(String value) {
        return value == null ? "" : Normalizer.normalize(value.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static String id(String value) {
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return MBID.matcher(normalized).matches() ? normalized : "";
    }

    private synchronized void record(Result result, String label) {
        switch (result) {
            case UPDATED -> updated++;
            case UNCHANGED -> unchanged++;
            case FAILED -> failed++;
            default -> skipped++;
        }
        if (result != Result.UPDATED && result != Result.UNCHANGED) {
            lastIssue = result.name().toLowerCase(Locale.ROOT);
            lastIssueAlbum = label;
            log.info("VA 专辑艺术家刮削结果: {} ({})", label, lastIssue);
        }
    }

    private void checkCancelled() throws InterruptedException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    @Override
    public void close() {
        synchronized (this) {
            cancel();
            executor.shutdown();
        }
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
