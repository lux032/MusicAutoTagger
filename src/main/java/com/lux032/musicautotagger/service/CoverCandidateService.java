package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.util.ProcessRunner;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Collects cover choices on demand and keeps their bytes outside review JSON. */
@Slf4j
public class CoverCandidateService implements AutoCloseable {
    private static final int MIN_SCORE = 80;
    private static final long TTL_MILLIS = 7L * 86_400_000L;
    private final MusicConfig config;
    private final MusicBrainzClient musicBrainzClient;
    private final CoverArtService coverArtService;
    private final CoverArtCache coverArtCache;
    private final UntrustedImageFetcher fetcher = new UntrustedImageFetcher();
    private final Path cacheDirectory;

    public CoverCandidateService(MusicConfig config, MusicBrainzClient musicBrainzClient,
                                 CoverArtService coverArtService, CoverArtCache coverArtCache) {
        this.config = config;
        this.musicBrainzClient = musicBrainzClient;
        this.coverArtService = coverArtService;
        this.coverArtCache = coverArtCache;
        this.cacheDirectory = CoverCandidateCache.directory(config);
        cleanupExpired();
    }

    public List<CoverCandidateView> collect(ReviewItem item, ReviewItem.OnlineCandidate candidate,
                                            List<File> originalFiles) {
        cleanupExpired();
        List<CoverCandidateView> result = new ArrayList<>();
        try { collectMusicBrainz(candidate).ifPresent(result::add); }
        catch (Exception e) { log.debug("MusicBrainz cover candidate failed: {}", e.getMessage()); }

        File folder = originalFiles.isEmpty() ? null : originalFiles.get(0).getParentFile();
        if (folder != null) {
            File root = coverArtService.resolveAlbumRootDirectory(folder);
            File coverFile = coverArtService.findCoverFileInDirectory(root);
            byte[] data = null;
            try { if (coverFile != null) data = Files.readAllBytes(coverFile.toPath()); }
            catch (Exception e) { log.debug("Unable to read local cover {}: {}", coverFile, e.getMessage()); }
            add(result, data, CoverCandidateView.Origin.FOLDER, root.getName(), null,
                coverFile == null ? null : coverFile.getName(), null);
        }
        for (File original : originalFiles) {
            byte[] data = coverArtService.extractEmbeddedCover(original);
            if (add(result, data, CoverCandidateView.Origin.EMBEDDED, original.getName(), null,
                original.getName(), null)) break;
        }
        if (candidate.getCoverUrl() != null && !candidate.getCoverUrl().isBlank()) {
            fetcher.fetch(candidate.getCoverUrl()).ifPresent(image -> addInspected(result, image,
                CoverCandidateView.Origin.LLM_URL, domain(candidate.getCoverUrl()), candidate.getCoverUrl(), null, null));
        }
        return deduplicate(result);
    }

    private Optional<CoverCandidateView> collectMusicBrainz(ReviewItem.OnlineCandidate candidate) throws Exception {
        String artist = meaningful(candidate.getAlbumArtist()) ? candidate.getAlbumArtist() : candidate.getArtist();
        List<MusicMetadata> albums = musicBrainzClient.searchAlbum(candidate.getTitle(), artist);
        if (albums == null || albums.isEmpty()) return Optional.empty();
        String wantedTitle = normalize(candidate.getTitle()), wantedArtist = normalize(artist);
        MusicMetadata best = null;
        for (MusicMetadata album : albums) {
            if (!meaningful(album.getReleaseGroupId()) || album.getScore() < MIN_SCORE
                || !normalize(album.getAlbum()).equals(wantedTitle)) continue;
            String albumArtist = meaningful(album.getAlbumArtist()) ? album.getAlbumArtist() : album.getArtist();
            if (meaningful(artist) && !normalize(albumArtist).equals(wantedArtist)) continue;
            if (best == null || album.getScore() > best.getScore()) best = album;
        }
        if (best == null) return Optional.empty();
        MusicBrainzClient.CoverArtResolution resolution =
            musicBrainzClient.resolveCoverArtByReleaseGroupId(best.getReleaseGroupId());
        if (!meaningful(resolution.getCoverArtUrl())) return Optional.empty();
        byte[] data = musicBrainzClient.downloadCoverArt(resolution.getCoverArtUrl());
        List<CoverCandidateView> one = new ArrayList<>();
        add(one, data, CoverCandidateView.Origin.MUSICBRAINZ, "MusicBrainz", resolution.getCoverArtUrl(),
            null, best.getReleaseGroupId());
        return one.stream().findFirst();
    }

    private boolean add(List<CoverCandidateView> target, byte[] data, CoverCandidateView.Origin origin,
                        String label, String url, String fileName, String rgid) {
        if (data == null || data.length == 0) return false;
        Optional<UntrustedImageFetcher.Result> inspected = UntrustedImageFetcher.inspect(data);
        if (inspected.isEmpty()) inspected = convertWithFfmpeg(data);
        if (inspected.isEmpty()) return false;
        addInspected(target, inspected.get(), origin, label, url, fileName, rgid);
        return true;
    }

    private void addInspected(List<CoverCandidateView> target, UntrustedImageFetcher.Result image,
                              CoverCandidateView.Origin origin, String label, String url,
                              String fileName, String rgid) {
        try {
            byte[] jpeg = image.data();
            if (!"image/jpeg".equals(image.mime())) {
                Optional<UntrustedImageFetcher.Result> converted = convertWithFfmpeg(jpeg);
                if (converted.isEmpty()) return;
                image = converted.get(); jpeg = image.data();
            }
            String sha = sha256(jpeg);
            Files.createDirectories(cacheDirectory);
            Path destination = cacheDirectory.resolve(sha + ".jpg");
            if (!Files.isRegularFile(destination)) Files.write(destination, jpeg);
            CoverCandidateView view = new CoverCandidateView();
            view.setSha256(sha); view.setOrigin(origin); view.setOriginLabel(label); view.setSourceUrl(url);
            view.setSourceFileName(fileName); view.setWidth(image.width()); view.setHeight(image.height());
            view.setBytes(jpeg.length); view.setMime("image/jpeg"); view.setReleaseGroupId(rgid);
            target.add(view);
        } catch (Exception e) { log.debug("Unable to cache cover candidate: {}", e.getMessage()); }
    }

    private Optional<UntrustedImageFetcher.Result> convertWithFfmpeg(byte[] data) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("cover-candidate-");
            Path input = dir.resolve("input.image"), output = dir.resolve("output.jpg");
            Files.write(input, data);
            String ffmpeg = config.getAudioNormalizeFfmpegPath();
            if (ffmpeg == null || ffmpeg.isBlank()) ffmpeg = "ffmpeg";
            ProcessRunner.Result result = ProcessRunner.execute(List.of(ffmpeg, "-y", "-i", input.toString(),
                "-frames:v", "1", output.toString()), 20);
            if (!result.isSuccess() || !Files.isRegularFile(output)) return Optional.empty();
            return UntrustedImageFetcher.inspect(Files.readAllBytes(output));
        } catch (Exception e) { return Optional.empty(); }
        finally {
            if (dir != null) try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            } catch (Exception ignored) {}
        }
    }

    public Optional<ResolvedCover> resolveBySha256(String sha, List<CoverCandidateView> allowed) {
        if (!CoverCandidateCache.validSha256(sha)) return Optional.empty();
        CoverCandidateView selected = allowed == null ? null : allowed.stream()
            .filter(v -> sha.equalsIgnoreCase(v.getSha256())).findFirst().orElse(null);
        if (selected == null) return Optional.empty();
        try {
            Path file = cacheDirectory.resolve(sha.toLowerCase(Locale.ROOT) + ".jpg");
            if (!Files.isRegularFile(file)) return Optional.empty();
            return Optional.of(new ResolvedCover(Files.readAllBytes(file), selected.getOrigin(), selected.getReleaseGroupId()));
        } catch (Exception e) { return Optional.empty(); }
    }

    public void promoteIfMusicBrainz(ResolvedCover cover) {
        if (cover.origin() == CoverCandidateView.Origin.MUSICBRAINZ && meaningful(cover.releaseGroupId())) {
            coverArtCache.cacheCoverByReleaseGroupId(cover.releaseGroupId(), cover.data());
        }
    }

    private void cleanupExpired() {
        try {
            if (!Files.isDirectory(cacheDirectory)) return;
            long cutoff = System.currentTimeMillis() - TTL_MILLIS;
            try (var files = Files.list(cacheDirectory)) {
                for (Path file : files.filter(Files::isRegularFile).toList())
                    if (Files.getLastModifiedTime(file).toMillis() < cutoff) Files.deleteIfExists(file);
            }
        } catch (Exception e) { log.debug("Candidate cover cleanup failed: {}", e.getMessage()); }
    }

    private static List<CoverCandidateView> deduplicate(List<CoverCandidateView> input) {
        java.util.LinkedHashMap<String, CoverCandidateView> map = new java.util.LinkedHashMap<>();
        for (CoverCandidateView v : input) map.putIfAbsent(v.getSha256(), v);
        return new ArrayList<>(map.values());
    }
    private static String domain(String url) { try { return URI.create(url).getHost(); } catch (Exception e) { return url; } }
    private static String normalize(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]+", "").trim(); }
    private static boolean meaningful(String value) { return value != null && !value.isBlank(); }
    private static String sha256(byte[] data) throws Exception { StringBuilder s=new StringBuilder(); for(byte b:MessageDigest.getInstance("SHA-256").digest(data))s.append(String.format("%02x",b)); return s.toString(); }
    @Override public void close() throws Exception { fetcher.close(); }
    public record ResolvedCover(byte[] data, CoverCandidateView.Origin origin, String releaseGroupId) {}
}
