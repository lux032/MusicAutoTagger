package com.lux032.musicautotagger.service.llm;

import lombok.Data;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 联网搜索适配层。
 *
 * 背景：早期只有一个 {@link NativeWebSearchClient}，直接依赖模型厂商自带的 Web Search 工具
 * （OpenAI Responses 的 web_search_preview / Anthropic 的 web_search_20250305）。
 * 实践中这条链路不可靠：
 *   - 大量第三方 OpenAI 兼容端点根本不实现 /responses 或不支持 tools；
 *   - 模型是否真的检索、返回几条 citation 完全不可控；
 *   - candidate 的 source_urls 由模型自述，来源门槛形同虚设。
 *
 * 因此抽出本接口，允许换成「外部检索器（Tavily） + 普通 chat 归纳」的实现
 * （{@link TavilyWebSearchClient}），此时 URL 由检索器提供，是真实存在的。
 *
 * 上层（OnlineIdentificationService）只依赖本接口，不感知具体来源。
 */
public interface WebSearchClient {

    /** 是否存在可用的搜索配置；false 时上层应报「联网搜索未启用」 */
    boolean hasEnabledEndpoint();

    /**
     * 执行一次联网搜索并让模型按 systemPrompt 的要求归纳。
     *
     * @return 文本 + 证据（citations）；失败时抛异常，不返回半成品
     */
    SearchResponse search(String systemPrompt, String userPrompt) throws LlmClient.LlmException;

    /**
     * 带阶段进度回调的联网搜索。默认实现直接委派给无回调版本，保持旧实现和测试桩兼容。
     */
    default SearchResponse search(String systemPrompt, String userPrompt, ProgressListener progress)
            throws LlmClient.LlmException {
        return search(systemPrompt, userPrompt);
    }

    /**
     * 面向封面图片检索的联网搜索：默认复用 {@link #search(String, String, ProgressListener)}，
     * Tavily 等支持图片直链返回的检索器会额外开启 include_images 并填充 {@link SearchResponse#getImages()}。
     */
    default SearchResponse searchImages(String systemPrompt, String userPrompt, ProgressListener progress)
            throws LlmClient.LlmException {
        return search(systemPrompt, userPrompt, progress);
    }

    /** 供日志 / 落库展示的实现名，例如 native-openai / tavily */
    String name();

    @FunctionalInterface
    interface ProgressListener {
        void onProgress(String stage, int current, int total, int citations, String detail);
    }

    // ==================== URL 规范化 ====================

    Set<String> TRACKING_QUERY_KEYS = Set.of("fbclid", "gclid", "uo");

    /**
     * 规范化 HTTP(S) URL 以便准确比对检索池与模型引用的 source_urls：
     * 仅接受 http/https，统一小写 scheme/host、去掉 www.、忽略默认 80/443 端口、去尾斜杠、
     * 剔除 utm_* / fbclid / gclid / uo 追踪参数并对剩余查询参数按键名稳定排序（保留重复同名参数原顺序与原始编码）。
     */
    static String canonicalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        String trimmed = rawUrl.trim();
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return "";
            }
            String rawHost = uri.getHost();
            if (rawHost == null || rawHost.isBlank()) {
                return "";
            }
            String host = rawHost.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            int port = uri.getPort();
            String portPart = (port > 0
                && !("http".equals(scheme) && port == 80)
                && !("https".equals(scheme) && port == 443))
                ? ":" + port : "";
            String rawPath = uri.getRawPath();
            String path = (rawPath == null || rawPath.isEmpty()) ? "/" : stripTrailingSlash(rawPath);
            if (path.isEmpty()) {
                path = "/";
            }
            String cleanQuery = buildCleanQuery(uri.getRawQuery(), true);
            StringBuilder sb = new StringBuilder();
            sb.append(scheme).append("://").append(host).append(portPart).append(path);
            if (!cleanQuery.isEmpty()) {
                sb.append('?').append(cleanQuery);
            }
            return sb.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    /** 仅剔除 utm_* / fbclid / gclid / uo 等追踪参数，完整保留其余参数的原始顺序与字节编码（不破坏签名 URL）。 */
    static String stripTrackingParams(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return rawUrl;
        }
        String trimmed = rawUrl.trim();
        int qIdx = trimmed.indexOf('?');
        if (qIdx < 0) {
            return trimmed;
        }
        try {
            URI uri = URI.create(trimmed);
            String cleanQuery = buildCleanQuery(uri.getRawQuery(), false);
            int hashIdx = trimmed.indexOf('#', qIdx);
            String base = trimmed.substring(0, qIdx);
            String frag = hashIdx >= 0 ? trimmed.substring(hashIdx) : "";
            return cleanQuery.isEmpty() ? base + frag : base + "?" + cleanQuery + frag;
        } catch (Exception ignored) {
            return trimmed;
        }
    }

    private static String stripTrailingSlash(String s) {
        String r = s;
        while (r.length() > 1 && r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    private static String buildCleanQuery(String rawQuery, boolean sortByKey) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        record RawPair(String key, String rawPart) {}
        List<RawPair> pairs = new ArrayList<>();
        for (String part : rawQuery.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String rawK = eq >= 0 ? part.substring(0, eq) : part;
            String decodedK;
            try {
                decodedK = URLDecoder.decode(rawK, StandardCharsets.UTF_8);
            } catch (Exception e) {
                decodedK = rawK;
            }
            String lowerK = decodedK.toLowerCase(Locale.ROOT);
            if (lowerK.startsWith("utm_") || TRACKING_QUERY_KEYS.contains(lowerK)) {
                continue;
            }
            pairs.add(new RawPair(decodedK, part));
        }
        if (pairs.isEmpty()) {
            return "";
        }
        if (sortByKey) {
            // TimSort 为稳定排序：仅按键名排序，保留重复同名参数的原始相对顺序
            pairs.sort(Comparator.comparing(RawPair::key));
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.size(); i++) {
            if (i > 0) {
                sb.append('&');
            }
            sb.append(pairs.get(i).rawPart());
        }
        return sb.toString();
    }

    // ==================== 数据结构 ====================

    @Data
    class Citation {
        private String url;
        private String title;
        private String snippet;
        private String domain;
        private long retrievedAt;
        private String reliability;
        /** 原生搜索 open_page/find_in_page 或抓取器成功读取完整正文时为 true */
        private boolean openedPage;

        public static Citation of(String url, String title, String snippet) {
            return of(url, title, snippet, false);
        }

        public static Citation of(String url, String title, String snippet, boolean openedPage) {
            String cleanedUrl = stripTrackingParams(url);
            Citation c = new Citation();
            c.setUrl(cleanedUrl);
            c.setTitle(title);
            c.setSnippet(snippet);
            c.setRetrievedAt(System.currentTimeMillis());
            c.setOpenedPage(openedPage);
            String host = null;
            try {
                host = URI.create(cleanedUrl).getHost();
            } catch (Exception ignored) {
                // 模型 / 检索器偶尔回传非法 URL，域名解析失败只降级为 LOW，不影响整体流程
            }
            c.setDomain(host);
            c.setReliability(SourceReliability.of(host));
            return c;
        }
    }

    @Data
    class SearchResponse {
        private String text;
        private String provider;
        private String model;
        private int endpointIndex;
        private List<Citation> citations = new ArrayList<>();
        private List<String> images = new ArrayList<>();
        private LlmClient.TokenUsage tokenUsage = LlmClient.TokenUsage.empty();

        /** 按规范化 URL 去重后追加；同 URL 重复出现时保留更高的 openedPage 标记与非空标题/摘要 */
        public void addCitation(Citation citation) {
            if (citation == null || citation.getUrl() == null || citation.getUrl().isBlank()) {
                return;
            }
            String canon = canonicalizeUrl(citation.getUrl());
            for (Citation existing : citations) {
                if (canon.equals(canonicalizeUrl(existing.getUrl()))) {
                    if (citation.isOpenedPage()) {
                        existing.setOpenedPage(true);
                    }
                    if ((existing.getTitle() == null || existing.getTitle().isBlank())
                        && citation.getTitle() != null && !citation.getTitle().isBlank()) {
                        existing.setTitle(citation.getTitle());
                    }
                    if ((existing.getSnippet() == null || existing.getSnippet().isBlank())
                        && citation.getSnippet() != null && !citation.getSnippet().isBlank()) {
                        existing.setSnippet(citation.getSnippet());
                    }
                    return;
                }
            }
            citations.add(citation);
        }

        public void addTokenUsage(LlmClient.TokenUsage usage) {
            if (usage != null) {
                this.tokenUsage = (this.tokenUsage == null ? LlmClient.TokenUsage.empty() : this.tokenUsage).plus(usage);
            }
        }

        /** 图片直链去重后追加 */
        public void addImageUrl(String url) {
            if (url == null || url.isBlank()) {
                return;
            }
            String trimmed = url.trim();
            if (!images.contains(trimmed)) {
                images.add(trimmed);
            }
        }
    }

    // ==================== 域名可信度 ====================

    /**
     * 来源可信度注册表。
     *
     * 必须精确匹配或子域名匹配：早期的 contains() 写法会把 fake-discogs.example.com、
     * musicbrainz-data.example.org 这类伪造域名当成 HIGH，直接绕过来源门槛。
     */
    final class SourceReliability {

        private static final List<String> HIGH_TRUST = List.of(
            "musicbrainz.org", "discogs.com", "vgmdb.net", "spotify.com",
            "music.apple.com", "bandcamp.com",
            "ototoy.jp", "mora.jp", "recochoku.jp", "linkco.re",
            "tunecore.co.jp", "qobuz.com", "e-onkyo.com", "vocadb.net");

        private static final List<String> MEDIUM_TRUST = List.of(
            "wikipedia.org", "amazon.com", "amazon.co.jp", "amazon.jp",
            "last.fm", "allmusic.com", "rateyourmusic.com",
            "tower.jp", "hmv.co.jp", "cdjapan.co.jp", "oricon.co.jp",
            "natalie.mu", "prtimes.jp", "realsound.jp", "barks.jp",
            "uta-net.com", "utaten.com", "lyrical-nonsense.com", "genius.com");

        /** 默认 Tavily 专辑级检索优先域名（保留国际核心库并补入东亚主流正版发行源） */
        private static final List<String> DEFAULT_TAVILY_ALBUM_DOMAINS = List.of(
            "musicbrainz.org", "discogs.com", "vgmdb.net", "spotify.com",
            "music.apple.com", "bandcamp.com", "ototoy.jp", "mora.jp", "linkco.re", "vocadb.net",
            "wikipedia.org", "amazon.com", "amazon.co.jp", "amazon.jp",
            "last.fm", "allmusic.com", "rateyourmusic.com");

        private SourceReliability() {
        }

        public static String of(String domain) {
            if (domain == null || domain.isBlank()) {
                return "LOW";
            }
            String d = domain.toLowerCase(Locale.ROOT);
            if (d.startsWith("www.")) {
                d = d.substring(4);
            }
            if (matches(d, HIGH_TRUST)) {
                return "HIGH";
            }
            if (matches(d, MEDIUM_TRUST)) {
                return "MEDIUM";
            }
            return "LOW";
        }

        /** 按域名将来源归类为 8 大类语义类型，供抓取排序与字段级证据展示复用。 */
        public static String classifySourceType(String url, String artistHint) {
            if (url == null || url.isBlank()) {
                return "other";
            }
            String host;
            try {
                host = URI.create(url.trim()).getHost();
            } catch (Exception e) {
                return "other";
            }
            if (host == null || host.isBlank()) {
                return "other";
            }
            String d = host.toLowerCase(Locale.ROOT);
            if (d.startsWith("www.")) {
                d = d.substring(4);
            }
            if (matches(d, List.of("youtube.com", "youtu.be", "music.youtube.com"))) {
                return "youtube_official";
            }
            if (matches(d, List.of(
                "uta-net.com", "utaten.com", "j-lyric.net", "lyrical-nonsense.com",
                "petitlyrics.com", "kashinavi.com", "kasi-time.com", "genius.com",
                "musixmatch.com", "joysound.com", "utamap.com", "kkbox.com", "lyricstranslate.com"))) {
                return "jp_lyrics_site";
            }
            if (matches(d, List.of(
                "musicbrainz.org", "vgmdb.net", "discogs.com", "vocadb.net",
                "allmusic.com", "rateyourmusic.com", "last.fm", "anison.info"))) {
                return "music_database";
            }
            if (matches(d, List.of(
                "music.apple.com", "spotify.com", "open.spotify.com", "bandcamp.com",
                "ototoy.jp", "mora.jp", "recochoku.jp", "e-onkyo.com", "amazon.co.jp",
                "amazon.com", "amazon.jp", "music.amazon.co.jp", "qobuz.com", "tidal.com",
                "deezer.com", "linkco.re", "tunecore.co.jp", "tower.jp", "hmv.co.jp",
                "cdjapan.co.jp", "booth.pm", "melonbooks.co.jp", "yodobashi.com", "kinokuniya.co.jp"))) {
                return "music_store_platform";
            }
            if (matches(d, List.of(
                "prtimes.jp", "natalie.mu", "barks.jp", "realsound.jp", "skream.jp",
                "panora.tokyo", "moguravr.com", "lisani.jp", "spice.eplus.jp",
                "encore.usen.com", "e.usen.com", "cinra.net", "rockinon.com",
                "animeanime.jp", "famitsu.com", "4gamer.net", "dengekionline.com",
                "kai-you.net", "thefirsttimes.jp", "musicvoice.jp", "oricon.co.jp",
                "billboard-japan.com"))) {
                return "official_press_release";
            }
            if (matches(d, List.of(
                "wikipedia.org", "fandom.com", "wikiwiki.jp", "seesaawiki.jp",
                "dic.pixiv.net", "atwiki.jp", "gamerch.com", "bluearchive.wiki",
                "miraheze.org", "dic.nicovideo.jp"))) {
                return "community_wiki";
            }
            if (matches(d, List.of(
                "lantis.jp", "sonymusic.co.jp", "toysfactory.co.jp", "kingrecords.co.jp",
                "jvcmusic.co.jp", "ponycanyon.co.jp", "avex.jp", "universal-music.co.jp",
                "wmg.jp", "columbia.jp", "sacramusic.jp", "aniplex.co.jp", "bushiroad-music.com",
                "hololivepro.com", "nijisanji.jp", "kamitsubaki.jp", "asobisystem.com",
                "bravegroup.co.jp", "rkmusic.jp", "stardustrecords.jp", "bandainamcomusiclive.co.jp"))) {
                return "label_official";
            }
            if (artistHint != null && !artistHint.isBlank()) {
                String normArtist = artistHint.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                String regLabel = extractRegistrableDomainLabel(d).replace("-", "");
                // 仅当可注册主域名标签（不含仿冒子域或前后缀）与艺人名精确一致时判定为艺人官网
                if (normArtist.length() >= 4 && regLabel.equals(normArtist)) {
                    return "artist_official";
                }
            }
            return "other";
        }

        private static final Set<String> MULTI_PART_SECOND_LEVEL_TLDS = Set.of(
            "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp", "gr.jp",
            "co.uk", "org.uk", "com.cn", "net.cn", "co.kr", "com.tw", "com.hk", "com.au"
        );

        private static String extractRegistrableDomainLabel(String host) {
            if (host == null || host.isBlank()) return "";
            String[] parts = host.split("\\.");
            if (parts.length < 2) return host;
            String lastTwo = parts[parts.length - 2] + "." + parts[parts.length - 1];
            if (MULTI_PART_SECOND_LEVEL_TLDS.contains(lastTwo) && parts.length >= 3) {
                return parts[parts.length - 3];
            }
            return parts[parts.length - 2];
        }

        /** 精确相等，或是其真子域（artist.bandcamp.com 这类合法子域仍然放行） */
        private static boolean matches(String domain, List<String> registry) {
            for (String allowed : registry) {
                if (domain.equals(allowed) || domain.endsWith("." + allowed)) {
                    return true;
                }
            }
            return false;
        }

        /** 默认优先检索的域名，作为 Tavily include_domains 的兜底值 */
        public static List<String> defaultPreferredDomains() {
            return new ArrayList<>(DEFAULT_TAVILY_ALBUM_DOMAINS);
        }
    }
}
