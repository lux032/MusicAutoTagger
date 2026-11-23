package com.lux032.musicautotagger.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.llm.LlmClient;
import com.lux032.musicautotagger.util.ProcessRunner;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Collects cover choices on demand and keeps their bytes outside review JSON. */
@Slf4j
public class CoverCandidateService implements AutoCloseable {
    private static final int MIN_SCORE = 80;
    private static final long TTL_MILLIS = 7L * 86_400_000L;
    private static final int MAX_ITUNES_CANDIDATES = 5;
    private final MusicConfig config;
    private final MusicBrainzClient musicBrainzClient;
    private final CoverArtService coverArtService;
    private final CoverArtCache coverArtCache;
    private final UntrustedImageFetcher fetcher = new UntrustedImageFetcher();
    private volatile HttpClient httpClient;
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

    private HttpClient httpClient() {
        HttpClient client = this.httpClient;
        if (client == null) {
            synchronized (this) {
                client = this.httpClient;
                if (client == null) {
                    client = LlmClient.buildHttpClient(config);
                    this.httpClient = client;
                }
            }
        }
        return client;
    }

    public CoverArtService getCoverArtService() {
        return coverArtService;
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
            try {
                if (coverFile != null) {
                    Path coverPath = coverFile.toPath();
                    if (!Files.isSymbolicLink(coverPath) && Files.size(coverPath) <= ImageCompressor.MAX_INPUT_BYTES) {
                        data = Files.readAllBytes(coverPath);
                    }
                }
            }
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

    /**
     * 校验并按项目统一规则（ImageCompressor: 最大边 1200px、<=2MB JPEG）压缩用户上传的封面图片。
     */
    public Optional<CoverCandidateView> ingestUploadedBytes(byte[] rawBytes, String originalFileName) {
        if (rawBytes == null || rawBytes.length == 0 || rawBytes.length > ImageCompressor.MAX_INPUT_BYTES) {
            return Optional.empty();
        }
        cleanupExpired();
        List<CoverCandidateView> one = new ArrayList<>();
        String safeName = meaningful(originalFileName) ? new File(originalFileName).getName() : "upload.jpg";
        add(one, rawBytes, CoverCandidateView.Origin.USER_UPLOAD, "本地上传", null, safeName, null, null);
        return one.stream().findFirst();
    }

    /**
     * 通过 SSRF 防护下载器拉取图片 URL 并走统一压缩规则入候选缓存。
     */
    public Optional<CoverCandidateView> ingestFromUrl(String url, CoverCandidateView.Origin origin,
                                                      String label, String note) {
        if (!meaningful(url)) {
            return Optional.empty();
        }
        cleanupExpired();
        String trimmed = url.trim();
        Optional<UntrustedImageFetcher.Result> fetched = fetcher.fetch(trimmed);
        if (fetched.isEmpty()) {
            return Optional.empty();
        }
        List<CoverCandidateView> one = new ArrayList<>();
        String effectiveLabel = meaningful(label) ? label : domain(trimmed);
        addInspected(one, fetched.get(), origin, effectiveLabel, trimmed, null, null, note);
        return one.stream().findFirst();
    }

    /**
     * 按已知 Release Group ID 重查 Cover Art Archive（应对当时网络失败或社区后续补图）。
     */
    public Optional<CoverCandidateView> fetchMusicBrainzCoverByRgid(String releaseGroupId) {
        if (!meaningful(releaseGroupId) || musicBrainzClient == null) {
            return Optional.empty();
        }
        try {
            MusicBrainzClient.CoverArtResolution resolution =
                musicBrainzClient.resolveCoverArtByReleaseGroupId(releaseGroupId);
            if (resolution == null || !meaningful(resolution.getCoverArtUrl())) {
                return Optional.empty();
            }
            byte[] data = musicBrainzClient.downloadCoverArt(resolution.getCoverArtUrl());
            if (data == null || data.length == 0) {
                return Optional.empty();
            }
            List<CoverCandidateView> one = new ArrayList<>();
            add(one, data, CoverCandidateView.Origin.MUSICBRAINZ, "MusicBrainz",
                resolution.getCoverArtUrl(), null, releaseGroupId, null);
            return one.stream().findFirst();
        } catch (Exception e) {
            log.debug("MusicBrainz rgid cover fetch failed ({}): {}", releaseGroupId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 收集本地封面候选（专辑目录图片 + 同专辑其他曲目已有的内嵌封面），全部走统一压缩规则。
     */
    public List<CoverCandidateView> collectLocalCandidates(List<File> audioFiles, File albumRoot) {
        cleanupExpired();
        List<CoverCandidateView> result = new ArrayList<>();
        if (albumRoot != null && albumRoot.isDirectory()) {
            File coverFile = coverArtService.findCoverFileInDirectory(albumRoot);
            if (coverFile != null) {
                try {
                    if (Files.size(coverFile.toPath()) <= ImageCompressor.MAX_INPUT_BYTES) {
                        add(result, Files.readAllBytes(coverFile.toPath()), CoverCandidateView.Origin.FOLDER,
                            albumRoot.getName(), null, coverFile.getName(), null, null);
                    }
                } catch (Exception e) {
                    log.debug("Unable to read local cover {}: {}", coverFile, e.getMessage());
                }
            }
        }
        if (audioFiles != null) {
            for (File audio : audioFiles) {
                if (audio == null || !audio.isFile()) continue;
                byte[] embedded = coverArtService.extractEmbeddedCover(audio);
                if (add(result, embedded, CoverCandidateView.Origin.EMBEDDED, audio.getName(),
                        null, audio.getName(), null, null)) {
                    break;
                }
            }
        }
        return deduplicate(result);
    }

    /**
     * 查询 iTunes / Apple Music 公开免费接口获取高清封面候选（只生成候选，绝不自动入库）。
     */
    public List<CoverCandidateView> searchItunesCovers(String albumTitle, String albumArtist, String customQuery) {
        cleanupExpired();
        List<String> terms = buildItunesTerms(albumTitle, albumArtist, customQuery);
        if (terms.isEmpty()) {
            return List.of();
        }
        boolean preferJp = containsCjk(albumTitle) || containsCjk(albumArtist) || containsCjk(customQuery);
        List<String> countries = preferJp ? List.of("jp", "us") : List.of("us", "jp");
        List<CoverCandidateView> candidates = new ArrayList<>();
        Set<String> seenArtworkUrls = new LinkedHashSet<>();

        for (String term : terms) {
            if (Thread.currentThread().isInterrupted()) {
                return deduplicate(candidates);
            }
            for (String country : countries) {
                if (Thread.currentThread().isInterrupted() || candidates.size() >= MAX_ITUNES_CANDIDATES) {
                    return deduplicate(candidates);
                }
                List<ItunesHit> hits = queryItunesApi(term, country);
                for (ItunesHit hit : hits) {
                    if (Thread.currentThread().isInterrupted() || candidates.size() >= MAX_ITUNES_CANDIDATES) {
                        break;
                    }
                    if (!seenArtworkUrls.add(hit.artworkUrl)) {
                        continue;
                    }
                    String hiRes = upgradeItunesArtworkUrl(hit.artworkUrl, 1200);
                    Optional<CoverCandidateView> view = ingestFromUrl(
                        hiRes, CoverCandidateView.Origin.ITUNES, "Apple Music / iTunes", hit.label);
                    if (view.isEmpty()) {
                        String midRes = upgradeItunesArtworkUrl(hit.artworkUrl, 600);
                        view = ingestFromUrl(
                            midRes, CoverCandidateView.Origin.ITUNES, "Apple Music / iTunes", hit.label);
                    }
                    view.ifPresent(candidates::add);
                }
                if (!candidates.isEmpty() && country.equals(countries.get(0))) {
                    break;
                }
            }
            if (candidates.size() >= 3) {
                break;
            }
        }
        return deduplicate(candidates);
    }

    private record ItunesHit(String artworkUrl, String label) {}

    private List<ItunesHit> queryItunesApi(String term, String country) {
        List<ItunesHit> hits = new ArrayList<>();
        try {
            String encoded = URLEncoder.encode(term, StandardCharsets.UTF_8);
            String apiUrl = "https://itunes.apple.com/search?term=" + encoded
                + "&entity=album&limit=5&country=" + country;
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
                .header("User-Agent", config.getUserAgent() != null ? config.getUserAgent() : "MusicAutoTagger/1.0")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(12))
                .GET()
                .build();
            HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300 || !meaningful(response.body())) {
                return hits;
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray results = root.getAsJsonArray("results");
            if (results == null) {
                return hits;
            }
            for (JsonElement el : results) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                String artwork = obj.has("artworkUrl100") && !obj.get("artworkUrl100").isJsonNull()
                    ? obj.get("artworkUrl100").getAsString() : null;
                if (!meaningful(artwork)) continue;
                String col = obj.has("collectionName") && !obj.get("collectionName").isJsonNull()
                    ? obj.get("collectionName").getAsString() : "";
                String art = obj.has("artistName") && !obj.get("artistName").isJsonNull()
                    ? obj.get("artistName").getAsString() : "";
                String label = meaningful(art) ? art + " - " + col : col;
                hits.add(new ItunesHit(artwork, label));
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("iTunes cover search failed (term={}, country={}): {}", term, country, e.getMessage());
        }
        return hits;
    }

    static String upgradeItunesArtworkUrl(String url, int size) {
        if (url == null) return null;
        return url.replaceAll("/\\d+x\\d+bb(\\.[a-zA-Z0-9]+)$", "/" + size + "x" + size + "bb.jpg");
    }

    private static List<String> buildItunesTerms(String albumTitle, String albumArtist, String customQuery) {
        Set<String> terms = new LinkedHashSet<>();
        if (meaningful(customQuery)) {
            terms.add(customQuery.trim());
        }
        String cleanAlbum = meaningful(albumTitle) ? albumTitle.trim() : "";
        String cleanArtist = meaningful(albumArtist) && !"Various Artists".equalsIgnoreCase(albumArtist.trim())
            && !"VA".equalsIgnoreCase(albumArtist.trim()) ? albumArtist.trim() : "";
        if (!cleanAlbum.isEmpty() && !cleanArtist.isEmpty()) {
            terms.add(cleanArtist + " " + cleanAlbum);
        }
        if (!cleanAlbum.isEmpty()) {
            terms.add(cleanAlbum);
        }
        return new ArrayList<>(terms);
    }

    private static boolean containsCjk(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(text.charAt(i));
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA) {
                return true;
            }
        }
        return false;
    }

    private boolean add(List<CoverCandidateView> target, byte[] data, CoverCandidateView.Origin origin,
                        String label, String url, String fileName, String rgid) {
        return add(target, data, origin, label, url, fileName, rgid, null);
    }

    private boolean add(List<CoverCandidateView> target, byte[] data, CoverCandidateView.Origin origin,
                        String label, String url, String fileName, String rgid, String note) {
        if (data == null || data.length == 0 || data.length > ImageCompressor.MAX_INPUT_BYTES) return false;
        // 先校验已知图片魔数与尺寸边界；不再对任意不可识别字节无限制调用 ffmpeg
        Optional<UntrustedImageFetcher.Result> inspected = UntrustedImageFetcher.inspect(data);
        if (inspected.isEmpty()) {
            // 仅对算术编码 JPEG 等已验证图片魔数但 ImageIO 无法读头的合法图片走受控 ImageCompressor FFmpeg 兜底
            if (ImageCompressor.detectSafeFfmpegDemuxer(data) == null) {
                return false;
            }
            byte[] converted = ImageCompressor.compressImage(data, ffmpegPath());
            if (converted == null || converted.length == 0) {
                return false;
            }
            inspected = UntrustedImageFetcher.inspect(converted);
        }
        if (inspected.isEmpty()) return false;
        return addInspected(target, inspected.get(), origin, label, url, fileName, rgid, note);
    }

    private void addInspected(List<CoverCandidateView> target, UntrustedImageFetcher.Result image,
                              CoverCandidateView.Origin origin, String label, String url,
                              String fileName, String rgid) {
        addInspected(target, image, origin, label, url, fileName, rgid, null);
    }

    private boolean addInspected(List<CoverCandidateView> target, UntrustedImageFetcher.Result image,
                                 CoverCandidateView.Origin origin, String label, String url,
                                 String fileName, String rgid, String note) {
        try {
            byte[] raw = image.data();
            // 所有封面候选路径统一走项目现有的 ImageCompressor 压缩规则（最大边 1200px，<=2MB JPEG；
            // PNG/JPEG/GIF/BMP 直接由 ImageIO 解码，WebP 等格式由 ImageCompressor 内置 ffmpeg 兜底转换，避免二次有损压缩）
            byte[] jpeg = ImageCompressor.compressImage(raw, ffmpegPath());
            if (jpeg == null || jpeg.length == 0) {
                log.debug("Cover candidate rejected by ImageCompressor ({})", label);
                return false;
            }
            Optional<UntrustedImageFetcher.Result> compressedInfo = UntrustedImageFetcher.inspect(jpeg);
            int width = compressedInfo.map(UntrustedImageFetcher.Result::width).orElse(image.width());
            int height = compressedInfo.map(UntrustedImageFetcher.Result::height).orElse(image.height());

            String sha = sha256(jpeg);
            Files.createDirectories(cacheDirectory);
            Path destination = cacheDirectory.resolve(sha + ".jpg");
            if (!Files.isRegularFile(destination)) Files.write(destination, jpeg);
            CoverCandidateView view = new CoverCandidateView();
            view.setSha256(sha); view.setOrigin(origin); view.setOriginLabel(label); view.setSourceUrl(url);
            view.setSourceFileName(fileName); view.setNote(note);
            view.setWidth(width); view.setHeight(height);
            view.setBytes(jpeg.length); view.setMime("image/jpeg"); view.setReleaseGroupId(rgid);
            target.add(view);
            return true;
        } catch (Exception e) {
            log.debug("Unable to cache cover candidate: {}", e.getMessage());
            return false;
        }
    }

    private String ffmpegPath() {
        String ffmpeg = config == null ? null : config.getAudioNormalizeFfmpegPath();
        return (ffmpeg == null || ffmpeg.isBlank()) ? "ffmpeg" : ffmpeg.trim();
    }

    public Optional<ResolvedCover> resolveBySha256(String sha, List<CoverCandidateView> allowed) {
        if (!CoverCandidateCache.validSha256(sha)) return Optional.empty();
        CoverCandidateView selected = allowed == null ? null : allowed.stream()
            .filter(v -> sha.equalsIgnoreCase(v.getSha256())).findFirst().orElse(null);
        if (selected == null) return Optional.empty();
        try {
            Path file = cacheDirectory.resolve(sha.toLowerCase(Locale.ROOT) + ".jpg");
            if (!Files.isRegularFile(file)) return Optional.empty();
            byte[] bytes = Files.readAllBytes(file);
            // 兜底确保旧缓存候选在写入前也符合 ImageCompressor 压缩规则
            byte[] compressed = ImageCompressor.compressImage(bytes, ffmpegPath());
            if (compressed == null || compressed.length == 0) return Optional.empty();
            return Optional.of(new ResolvedCover(compressed, selected.getOrigin(), selected.getReleaseGroupId()));
        } catch (Exception e) { return Optional.empty(); }
    }

    public void promoteIfMusicBrainz(ResolvedCover cover) {
        if (cover.origin() == CoverCandidateView.Origin.MUSICBRAINZ && meaningful(cover.releaseGroupId())) {
            coverArtCache.cacheCoverByReleaseGroupId(cover.releaseGroupId(), cover.data());
        }
    }

    public void cacheCoverForReleaseGroup(String releaseGroupId, byte[] compressedData) {
        if (meaningful(releaseGroupId) && compressedData != null && compressedData.length > 0 && coverArtCache != null) {
            coverArtCache.cacheCoverByReleaseGroupId(releaseGroupId, compressedData);
        }
    }

    public static List<CoverCandidateView> deduplicateCandidates(List<CoverCandidateView> input) {
        return deduplicate(input);
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
