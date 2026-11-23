package com.lux032.musicautotagger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lux032.musicautotagger.config.MusicConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cover Art Archive (CAA) 客户端（从 MusicBrainzClient 抽离的封面解析与下载职责）。
 * 负责 Release Group 默认封面查询、动画版发行封面偏好挑选与缓存以及安全有界图片下载。
 */
@Slf4j
public class CoverArtArchiveClient {

    @FunctionalInterface
    interface RateLimiter {
        void acquire() throws InterruptedException;
    }

    @FunctionalInterface
    interface MbGetExecutor {
        String get(String url) throws IOException, ParseException;
    }

    private final MusicConfig config;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RateLimiter rateLimiter;
    private final MbGetExecutor mbGetExecutor;

    /** Release Group ID -> 动画版封面 URL（空串 = 确认没有动画版），避免同一专辑逐曲重复请求 */
    private final Map<String, String> animeCoverUrlMemo = new ConcurrentHashMap<>();

    CoverArtArchiveClient(MusicConfig config,
                          CloseableHttpClient httpClient,
                          ObjectMapper objectMapper,
                          RateLimiter rateLimiter,
                          MbGetExecutor mbGetExecutor) {
        this.config = config;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
        this.mbGetExecutor = mbGetExecutor;
    }

    public String getCoverArtUrlByReleaseGroupId(String releaseGroupId) {
        if (releaseGroupId == null || releaseGroupId.isEmpty()) {
            return null;
        }
        return resolveCoverArtByReleaseGroupId(releaseGroupId).getCoverArtUrl();
    }

    /**
     * 获取封面 URL，并告知调用方「动画版偏好是否完整生效」。
     * 限流/网络失败导致回退到默认封面时 degraded=true，
     * 调用方应该避免把这张封面写进持久化的专辑缓存，否则一次限流会把真人封面永久钉在动画命名空间里。
     */
    public MusicBrainzClient.CoverArtResolution resolveCoverArtByReleaseGroupId(String releaseGroupId) {
        if (releaseGroupId == null || releaseGroupId.isEmpty()) {
            return new MusicBrainzClient.CoverArtResolution(null, false);
        }
        if (!config.isPreferAnimeCover()) {
            return new MusicBrainzClient.CoverArtResolution(
                fetchFrontCoverUrl("release-group/" + releaseGroupId, releaseGroupId), false);
        }

        AnimeCoverLookup lookup = resolvePreferredEdition(releaseGroupId);
        if (lookup.coverUrl != null) {
            return new MusicBrainzClient.CoverArtResolution(lookup.coverUrl, false);
        }
        log.debug("未找到动画版封面，回退到 Release Group 默认封面: {}", releaseGroupId);
        return new MusicBrainzClient.CoverArtResolution(
            fetchFrontCoverUrl("release-group/" + releaseGroupId, releaseGroupId),
            !lookup.completed);
    }

    /**
     * 下载封面图片（带 URL 协议校验、体积上限与重试机制）
     */
    public byte[] downloadCoverArt(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            java.net.URI parsed = java.net.URI.create(url.trim());
            String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
            if ((!"http".equals(scheme) && !"https".equals(scheme)) || parsed.getHost() == null) {
                log.warn("拒绝非法封面下载地址: {}", url);
                return null;
            }
        } catch (Exception e) {
            log.warn("拒绝非法封面下载地址: {}", url);
            return null;
        }

        int retryCount = 0;

        while (retryCount <= MusicBrainzClient.MAX_RETRIES) {
            try {
                HttpGet httpGet = new HttpGet(url);
                httpGet.setHeader("User-Agent", config.getUserAgent());

                try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                    int statusCode = response.getCode();

                    if (statusCode == 200) {
                        var entity = response.getEntity();
                        if (entity == null) {
                            return null;
                        }
                        if (entity.getContentLength() > ImageCompressor.MAX_INPUT_BYTES) {
                            log.warn("封面图片体积超过上限，拒绝下载: {} ({} bytes)", url, entity.getContentLength());
                            EntityUtils.consumeQuietly(entity);
                            return null;
                        }
                        try (java.io.InputStream in = entity.getContent()) {
                            return com.lux032.musicautotagger.util.OutboundUrlValidator.readBoundedBytes(
                                in, ImageCompressor.MAX_INPUT_BYTES);
                        }
                    }

                    if (statusCode == 404) {
                        log.debug("封面图片不存在: {}", url);
                        return null;
                    }

                    if (MusicBrainzClient.isRetryableStatus(statusCode)) {
                        EntityUtils.consumeQuietly(response.getEntity());
                        throw new MusicBrainzClient.RetryableHttpException(
                            statusCode, MusicBrainzClient.retryAfterMillis(response));
                    }

                    log.warn("下载封面图片失败,状态码 {}: {}", statusCode, url);
                    EntityUtils.consumeQuietly(response.getEntity());
                    return null;
                }

            } catch (Exception e) {
                retryCount++;

                if (retryCount > MusicBrainzClient.MAX_RETRIES) {
                    log.error("下载封面图片失败,已达最大重试次数({}/{}): {} - {}",
                        MusicBrainzClient.MAX_RETRIES, MusicBrainzClient.MAX_RETRIES, url, e.getMessage());
                    break;
                }

                long delayMs = MusicBrainzClient.retryDelayMs(e, retryCount);
                log.warn("下载封面图片失败(第{}/{}次尝试): {} - {}秒后重试",
                    retryCount, MusicBrainzClient.MAX_RETRIES, e.getMessage(), delayMs / 1000);

                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("重试等待被中断");
                    return null;
                }
            }
        }
        return null;
    }

    private AnimeCoverLookup resolvePreferredEdition(String releaseGroupId) {
        String memoKey = keywordSignature() + "|" + releaseGroupId;
        String memo = animeCoverUrlMemo.get(memoKey);
        if (memo != null) {
            return new AnimeCoverLookup(memo.isEmpty() ? null : memo, true);
        }

        AnimeCoverLookup lookup = findPreferredEditionCoverUrl(releaseGroupId);
        if (lookup.completed) {
            animeCoverUrlMemo.put(memoKey, lookup.coverUrl == null ? "" : lookup.coverUrl);
        }
        return lookup;
    }

    private String keywordSignature() {
        List<String> keywords = config.getAnimeCoverKeywords();
        return keywords == null ? "0" : Integer.toHexString(keywords.hashCode());
    }

    private AnimeCoverLookup findPreferredEditionCoverUrl(String releaseGroupId) {
        List<String> keywords = config.getAnimeCoverKeywords();
        if (keywords == null || keywords.isEmpty()) {
            return new AnimeCoverLookup(null, true);
        }

        try {
            rateLimiter.acquire();
            String url = String.format("%s/release?release-group=%s&limit=100&fmt=json",
                config.getMusicBrainzApiUrl(), releaseGroupId);
            JsonNode root = objectMapper.readTree(mbGetExecutor.get(url));
            JsonNode releases = root.path("releases");
            if (!releases.isArray() || releases.size() == 0) {
                return new AnimeCoverLookup(null, true);
            }
            int totalCount = root.path("release-count").asInt(releases.size());
            boolean fullyEnumerated = totalCount <= releases.size();
            if (!fullyEnumerated) {
                log.debug("Release Group {} 共 {} 个发行版，本次只枚举了前 {} 个",
                    releaseGroupId, totalCount, releases.size());
            }

            List<AnimeCoverCandidate> candidates = new ArrayList<>();
            for (JsonNode release : releases) {
                String releaseId = release.path("id").asText("");
                if (releaseId.isEmpty()) {
                    continue;
                }
                String status = release.path("status").asText("");
                if (!status.isEmpty() && !"Official".equalsIgnoreCase(status)) {
                    continue;
                }
                JsonNode caa = release.path("cover-art-archive");
                if (caa.isObject() && caa.has("front") && !caa.path("front").asBoolean(true)) {
                    continue;
                }
                String title = release.path("title").asText("");
                String disambiguation = release.path("disambiguation").asText("");
                AnimeEditionScore score = scoreAnimeEdition(title + " " + disambiguation, keywords);
                if (score.matched()) {
                    candidates.add(new AnimeCoverCandidate(releaseId, title, disambiguation,
                        score, release.path("date").asText("")));
                }
            }

            if (candidates.isEmpty()) {
                log.debug("Release Group {} 下没有命中动画版关键词的发行版", releaseGroupId);
                return new AnimeCoverLookup(null, fullyEnumerated);
            }

            candidates.sort((a, b) -> {
                if (a.score.bestWeight != b.score.bestWeight) {
                    return Integer.compare(b.score.bestWeight, a.score.bestWeight);
                }
                if (a.score.matchCount != b.score.matchCount) {
                    return Integer.compare(b.score.matchCount, a.score.matchCount);
                }
                String dateA = a.date == null || a.date.isEmpty() ? "9999" : a.date;
                String dateB = b.date == null || b.date.isEmpty() ? "9999" : b.date;
                return dateA.compareTo(dateB);
            });

            int maxCandidates = Math.max(1, config.getAnimeCoverMaxCandidates());
            int tried = 0;
            boolean allCandidatesDefinitive = true;
            for (AnimeCoverCandidate candidate : candidates) {
                if (tried >= maxCandidates) {
                    break;
                }
                tried++;
                log.debug("尝试动画版封面候选: {}{} 命中关键词={} (Release ID: {})",
                    candidate.title, candidate.describeDisambiguation(),
                    candidate.score.matchedKeywords, candidate.releaseId);
                CoverFetchResult result = fetchFrontCover("release/" + candidate.releaseId, candidate.releaseId);
                if (result.url != null) {
                    log.info("✓ 使用动画版封面: {}{} (命中关键词: {})", candidate.title,
                        candidate.describeDisambiguation(), candidate.score.matchedKeywords);
                    return new AnimeCoverLookup(result.url, true);
                }
                if (!result.definitive) {
                    allCandidatesDefinitive = false;
                }
            }
            return new AnimeCoverLookup(null,
                fullyEnumerated && allCandidatesDefinitive && tried >= candidates.size());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("查找动画版封面被中断: {}", releaseGroupId);
            return new AnimeCoverLookup(null, false);
        } catch (Exception e) {
            log.warn("查找动画版封面失败，将回退到默认封面: {} - {}", releaseGroupId, e.getMessage());
            return new AnimeCoverLookup(null, false);
        }
    }

    static AnimeEditionScore scoreAnimeEdition(String text, List<String> keywords) {
        AnimeEditionScore result = new AnimeEditionScore();
        if (text == null || text.isBlank() || keywords == null) {
            return result;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        int size = keywords.size();
        for (int i = 0; i < size; i++) {
            String keyword = keywords.get(i);
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            if (normalized.contains(keyword.trim().toLowerCase(Locale.ROOT))) {
                result.add(keyword.trim(), size - i);
            }
        }
        return result;
    }

    private String fetchFrontCoverUrl(String caaPath, String logId) {
        return fetchFrontCover(caaPath, logId).url;
    }

    private CoverFetchResult fetchFrontCover(String caaPath, String logId) {
        int retryCount = 0;

        while (retryCount <= MusicBrainzClient.MAX_RETRIES) {
            try {
                rateLimiter.acquire();
                String url = String.format("%s/%s", config.getCoverArtApiUrl(), caaPath);

                HttpGet httpGet = new HttpGet(url);
                httpGet.setHeader("User-Agent", config.getUserAgent());
                httpGet.setHeader("Accept", "application/json");

                try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                    int statusCode = response.getCode();

                    if (statusCode == 200) {
                        String json = EntityUtils.toString(response.getEntity());
                        JsonNode root = objectMapper.readTree(json);
                        JsonNode images = root.path("images");

                        if (images.isArray()) {
                            for (JsonNode image : images) {
                                if (image.path("front").asBoolean()) {
                                    return CoverFetchResult.found(image.path("image").asText());
                                }
                            }
                        }
                        return CoverFetchResult.notFound();
                    }

                    if (statusCode == 404) {
                        log.debug("Cover Art Archive 无此专辑封面: {}", logId);
                        return CoverFetchResult.notFound();
                    }

                    if (MusicBrainzClient.isRetryableStatus(statusCode)) {
                        EntityUtils.consumeQuietly(response.getEntity());
                        throw new MusicBrainzClient.RetryableHttpException(
                            statusCode, MusicBrainzClient.retryAfterMillis(response));
                    }

                    log.warn("获取封面失败,状态码 {}: {}", statusCode, logId);
                    EntityUtils.consumeQuietly(response.getEntity());
                    return CoverFetchResult.failed();
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("获取封面被中断: {}", logId);
                return CoverFetchResult.failed();
            } catch (Exception e) {
                retryCount++;

                if (retryCount > MusicBrainzClient.MAX_RETRIES) {
                    log.error("获取封面失败,已达最大重试次数({}/{}): {} - {}",
                        MusicBrainzClient.MAX_RETRIES, MusicBrainzClient.MAX_RETRIES, logId, e.getMessage());
                    break;
                }

                long delayMs = MusicBrainzClient.retryDelayMs(e, retryCount);
                log.warn("获取封面失败(第{}/{}次尝试): {} - {}秒后重试",
                    retryCount, MusicBrainzClient.MAX_RETRIES, e.getMessage(), delayMs / 1000);

                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("重试等待被中断");
                    return CoverFetchResult.failed();
                }
            }
        }
        return CoverFetchResult.failed();
    }

    static class AnimeEditionScore {
        int bestWeight = 0;
        int matchCount = 0;
        final List<String> matchedKeywords = new ArrayList<>();

        void add(String keyword, int weight) {
            matchCount++;
            matchedKeywords.add(keyword);
            if (weight > bestWeight) {
                bestWeight = weight;
            }
        }

        boolean matched() {
            return matchCount > 0;
        }
    }

    private static class AnimeCoverLookup {
        final String coverUrl;
        final boolean completed;

        AnimeCoverLookup(String coverUrl, boolean completed) {
            this.coverUrl = coverUrl;
            this.completed = completed;
        }
    }

    private static class AnimeCoverCandidate {
        final String releaseId;
        final String title;
        final String disambiguation;
        final AnimeEditionScore score;
        final String date;

        AnimeCoverCandidate(String releaseId, String title, String disambiguation,
                            AnimeEditionScore score, String date) {
            this.releaseId = releaseId;
            this.title = title == null ? "" : title;
            this.disambiguation = disambiguation == null ? "" : disambiguation;
            this.score = score;
            this.date = date;
        }

        String describeDisambiguation() {
            return disambiguation.isEmpty() ? "" : " (" + disambiguation + ")";
        }
    }

    private static class CoverFetchResult {
        final String url;
        final boolean definitive;

        private CoverFetchResult(String url, boolean definitive) {
            this.url = url;
            this.definitive = definitive;
        }

        static CoverFetchResult found(String url) {
            return new CoverFetchResult(url, true);
        }

        static CoverFetchResult notFound() {
            return new CoverFetchResult(null, true);
        }

        static CoverFetchResult failed() {
            return new CoverFetchResult(null, false);
        }
    }
}
