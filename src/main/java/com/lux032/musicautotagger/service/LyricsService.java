package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** Application-owned shared client. Failures defer lyrics, never delay archival for a long cooldown. */
public class LyricsService implements AutoCloseable {
    public enum Status { FOUND, NOT_FOUND, INSTRUMENTAL, UNSAFE, DEFERRED, CANCELLED }
    public record Result(Status status, String lyrics, Long recordId) {
        static Result of(Status status) { return new Result(status, null, null); }
    }
    static final int MAX_REQUESTS = 6;
    static final long BUDGET_MS = 18000;
    private final MusicConfig config;
    private final String baseUrl;
    private final CloseableHttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<HttpGet> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile long cooldownUntil;
    private int consecutiveFailures;

    public LyricsService(MusicConfig config) { this(config, "https://lrclib.net/api"); }
    LyricsService(MusicConfig config, String baseUrl) {
        this.config = config;
        this.baseUrl = baseUrl;
        HttpClientBuilder builder = HttpClients.custom().disableAutomaticRetries()
            .setDefaultRequestConfig(RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(2))
                .setConnectionRequestTimeout(Timeout.ofSeconds(1))
                .setResponseTimeout(Timeout.ofSeconds(2)).build());
        if (config.isProxyEnabled() && config.getProxyHost() != null && !config.getProxyHost().isBlank())
            builder.setProxy(new HttpHost(config.getProxyHost(), config.getProxyPort()));
        client = builder.build();
    }

    /** Legacy adapter; callers with local precision should use lookup or enrich. */
    public String getLyrics(String title, String artist, String album, int seconds) {
        return lookup(title, artist, album, (double) seconds, true).lyrics();
    }
    public void enrich(MusicMetadata metadata) {
        if (metadata == null || (metadata.getLyrics() != null && !metadata.getLyrics().isBlank())) return;
        Result result = lookup(metadata.getTitle(), metadata.getArtist(), metadata.getAlbum(),
            metadata.getLocalPreciseDuration(), !metadata.isWeakLyricsIdentity());
        if (result.status() == Status.FOUND) {
            metadata.setLyrics(result.lyrics());
            metadata.setAutomaticLyrics(new MusicMetadata.AutomaticLyrics("lrclib", result.recordId(),
                metadata.getRecordingId(), metadata.getTitle(), metadata.getArtist(), metadata.getLocalPreciseDuration(),
                List.copyOf(LyricsMatcher.versions(metadata.getTitle()))));
        }
    }
    public Result lookup(String title, String artist, String album, Double duration, boolean strongIdentity) {
        if (!LyricsMatcher.reliableDuration(duration) || LyricsMatcher.normalize(title).isEmpty()
            || LyricsMatcher.normalize(artist).isEmpty()) return Result.of(Status.UNSAFE);
        if (LyricsMatcher.instrumental(title)) return Result.of(Status.INSTRUMENTAL);
        if (closed || Thread.currentThread().isInterrupted()) return Result.of(Status.CANCELLED);
        if (System.currentTimeMillis() < cooldownUntil) return Result.of(Status.DEFERRED);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUDGET_MS);
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lyrics-deadline"); t.setDaemon(true); return t;
        });
        Set<HttpGet> requests = ConcurrentHashMap.newKeySet();
        timer.schedule(() -> requests.forEach(HttpGet::cancel), BUDGET_MS, TimeUnit.MILLISECONDS);
        Thread owner = Thread.currentThread();
        timer.scheduleAtFixedRate(() -> {
            if (owner.isInterrupted()) requests.forEach(HttpGet::cancel);
        }, 50, 50, TimeUnit.MILLISECONDS);
        int[] count = {0};
        try {
            String query = "?track_name=" + encode(title) + "&artist_name=" + encode(artist);
            String getQuery = query + "&duration=" + duration;
            Reply reply = request("/get" + getQuery + (album == null || album.isBlank() ? "" : "&album_name=" + encode(album)), deadline, count, requests);
            if (reply.status != Status.NOT_FOUND && reply.node == null) return Result.of(reply.status);
            if (reply.node != null) {
                Result result = evaluate(reply.node, title, artist, duration);
                if (result.status != Status.NOT_FOUND) return result;
            }
            if (reply.status == Status.NOT_FOUND && album != null && !album.isBlank()) {
                reply = request("/get" + getQuery, deadline, count, requests);
                if (reply.node == null && reply.status != Status.NOT_FOUND) return Result.of(reply.status);
                if (reply.node != null) {
                    Result result = evaluate(reply.node, title, artist, duration);
                    if (result.status != Status.NOT_FOUND) return result;
                }
            }
            if (!strongIdentity) return Result.of(Status.UNSAFE);
            reply = request("/search" + query, deadline, count, requests);
            if (reply.node == null) return Result.of(reply.status);
            if (!reply.node.isArray() || reply.node.size() >= 20) return Result.of(Status.UNSAFE);
            return selectSearch(reply.node, title, artist, duration);
        } finally { timer.shutdownNow(); }
    }
    private Result selectSearch(JsonNode array, String title, String artist, double duration) {
        Map<Long, JsonNode> originals = new HashMap<>();
        List<JsonNode> unique = new ArrayList<>();
        for (JsonNode node : array) {
            JsonNode id = node.path("id");
            if (validId(id)) {
                JsonNode previous = originals.putIfAbsent(id.asLong(), node);
                if (previous != null) {
                    if (!previous.equals(node)) return Result.of(Status.UNSAFE);
                    continue;
                }
            }
            unique.add(node);
        }
        JsonNode representative = null, equivalent = null;
        for (JsonNode node : unique) {
            if (!LyricsMatcher.matches(node, title, artist, duration)) continue;
            if (!node.isObject() || !validId(node.path("id")) || !validInstrumental(node)) return Result.of(Status.UNSAFE);
            com.fasterxml.jackson.databind.node.ObjectNode comparison = node.deepCopy();
            comparison.remove("id");
            for (String field : List.of("plainLyrics", "syncedLyrics")) {
                JsonNode text = node.get(field);
                if (text != null && !text.isNull() && !text.isTextual()) return Result.of(Status.UNSAFE);
                String normalized = normalizedLyrics(text == null ? null : text.asText(null));
                if (normalized == null) comparison.putNull(field); else comparison.put(field, normalized);
            }
            if (equivalent != null && !equivalent.equals(comparison)) return Result.of(Status.UNSAFE);
            equivalent = comparison;
            if (representative == null || node.path("id").asLong() < representative.path("id").asLong()) representative = node;
        }
        return representative == null ? Result.of(Status.NOT_FOUND) : evaluate(representative, title, artist, duration);
    }
    private static boolean validId(JsonNode id) { return id.isIntegralNumber() && id.canConvertToLong() && id.asLong() > 0; }
    private static boolean validInstrumental(JsonNode node) { return !node.has("instrumental") || node.get("instrumental").isBoolean(); }
    private static String normalizedLyrics(String text) {
        if (text == null || text.isBlank()) return null;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) lines[i] = lines[i].stripTrailing();
        int start = 0, end = lines.length;
        while (start < end && lines[start].isEmpty()) start++;
        while (end > start && lines[end - 1].isEmpty()) end--;
        return String.join("\n", Arrays.copyOfRange(lines, start, end));
    }
    private Result evaluate(JsonNode node, String title, String artist, double duration) {
        if (!LyricsMatcher.matches(node, title, artist, duration) || !validId(node.path("id")) || !validInstrumental(node)) return Result.of(Status.UNSAFE);
        if (node.path("instrumental").asBoolean()) return Result.of(Status.INSTRUMENTAL);
        String text = LyricsMatcher.text(node, duration);
        return text == null ? Result.of(Status.NOT_FOUND) : new Result(Status.FOUND, text, node.path("id").isIntegralNumber() ? node.path("id").asLong() : null);
    }
    private record Reply(Status status, JsonNode node) {}
    private Reply request(String path, long deadline, int[] count, Set<HttpGet> requests) {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (closed || Thread.currentThread().isInterrupted()) return new Reply(Status.CANCELLED, null);
            if (count[0] >= MAX_REQUESTS || System.nanoTime() >= deadline || System.currentTimeMillis() < cooldownUntil)
                return new Reply(Status.DEFERRED, null);
            count[0]++;
            HttpGet get = new HttpGet(baseUrl + path);
            get.setHeader("User-Agent", config.getUserAgent());
            active.add(get); requests.add(get);
            try (CloseableHttpResponse response = client.execute(get)) {
                int code = response.getCode();
                if (code == 200) { healthy(); return new Reply(Status.FOUND, mapper.readTree(EntityUtils.toString(response.getEntity()))); }
                if (code == 404) { healthy(); return new Reply(Status.NOT_FOUND, null); }
                if (code != 429 && code < 500) return new Reply(Status.DEFERRED, null);
                failed();
                if (response.getFirstHeader("Retry-After") != null) {
                    long delay = retryAfter(response.getFirstHeader("Retry-After").getValue());
                    if (delay > 0) { cooldownUntil = System.currentTimeMillis() + Math.min(delay, 3600000); return new Reply(Status.DEFERRED, null); }
                }
            } catch (Exception e) {
                if (closed || Thread.currentThread().isInterrupted()) return new Reply(Status.CANCELLED, null);
                failed();
            } finally { active.remove(get); requests.remove(get); }
            if (attempt < 2) {
                try { Thread.sleep(ThreadLocalRandom.current().nextLong(100, 301)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return new Reply(Status.CANCELLED, null); }
            }
        }
        return new Reply(Status.DEFERRED, null);
    }
    private synchronized void healthy() { consecutiveFailures = 0; }
    private synchronized void failed() { if (++consecutiveFailures >= 3) cooldownUntil = System.currentTimeMillis() + 30000; }
    static long retryAfter(String value) {
        try { return Math.max(0, Math.multiplyExact(Long.parseLong(value.trim()), 1000)); }
        catch (RuntimeException e) {
            try { return Math.max(0, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()); }
            catch (RuntimeException ignored) { return 0; }
        }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    public void close() throws IOException { closed = true; active.forEach(HttpGet::cancel); client.close(); }
}
