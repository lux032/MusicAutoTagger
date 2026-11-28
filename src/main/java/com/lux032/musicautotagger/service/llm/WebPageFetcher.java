package com.lux032.musicautotagger.service.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.util.HttpClientFactory;
import com.lux032.musicautotagger.util.OutboundUrlValidator;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.util.Timeout;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受控网页正文抓取器：
 * 1. 强制公网 SSRF 校验（禁止访问私网/回环/元数据 IP，限定 80/443 端口），启用 DNS Pinning 且禁用连接复用；
 * 2. 配置 HTTP 代理时不通过未固定 DNS 的代理直连不受信任外部 URL，防止代理侧 DNS 重绑定绕过 SSRF，必要时交由云端 Tavily /extract；
 * 3. 禁止自动重定向，手动跟随最多 3 跳并逐跳重新执行 SSRF 校验，按最终落地 URL 重算 sourceType；
 * 4. 先校验状态码、Content-Type 与 Content-Length 再流式有界读取（≤ 2MB），超限时立即 abort 连接避免耗尽连接池；
 * 5. 校验声明字符集后交由 jsoup 清洗 HTML 正文；精准解析 YouTube videoDetails JSON；按 5 URL 批次严格限白降级调用 Tavily /extract。
 */
@Slf4j
public class WebPageFetcher implements AutoCloseable {

    public static final long MAX_RESPONSE_BYTES = 2L * 1024 * 1024;
    public static final int MAX_REDIRECTS = 3;
    public static final int MIN_USEFUL_BODY_CHARS = 250;
    public static final int PROMPT_TEXT_LIMIT = 8000;
    public static final int VERIFY_TEXT_LIMIT = 60000;

    private static final String DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final Pattern UTANET_SONG_NO_SLASH =
        Pattern.compile("^(https?://(?:www\\.)?uta-net\\.com/song/\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern YT_VIDEO_DETAILS_KEY =
        Pattern.compile("\"videoDetails\"\\s*:\\s*\\{");
    private static final Pattern CHARSET_PATTERN =
        Pattern.compile("(?i)charset\\s*=\\s*[\"']?([a-z0-9._-]+)");
    public static final Pattern MB_ARTIST_URL_PATTERN = Pattern.compile(
        "(?i)https?://(?:www\\.|beta\\.)?musicbrainz\\.org/artist/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?![0-9a-f-])");
    private static final int MAX_MB_LINKS_PER_PAGE = 50;

    private static final Set<String> ELIGIBLE_EXTRACT_FALLBACK_STATUSES = Set.of(
        "http_401", "http_403", "http_429", "http_500", "http_502", "http_503",
        "waf_challenge", "js_shell_or_thin", "js_shell_or_bot_gate", "proxy_unpinned_blocked"
    );

    private static final List<String> WAF_SIGNATURES = List.of(
        "just a moment...",
        "cf-browser-verification",
        "checking your browser",
        "enable javascript and cookies to continue",
        "attention required! | cloudflare"
    );

    private static final Gson GSON = new Gson();

    private final MusicConfig config;
    private final TavilyWebSearchClient tavilyClient;
    private final ThreadLocal<Map<String, InetAddress[]>> requestPins = new ThreadLocal<>();
    private final CloseableHttpClient pinnedClient;

    /** 从抓取网页 HTML / JSON-LD / 正文中提取到的 MusicBrainz Artist 外链线索。 */
    public record ExtractedMbArtistLink(String mbid, String anchorText, String sourceUrl) {
    }

    public record FetchedPage(
        String url,
        String canonicalUrl,
        String sourceType,
        boolean ok,
        String method,
        String directStatus,
        String textForPrompt,
        String fullTextForVerify,
        List<ExtractedMbArtistLink> mbArtistLinks
    ) {
        public FetchedPage {
            mbArtistLinks = mbArtistLinks == null ? List.of() : List.copyOf(mbArtistLinks);
        }

        public FetchedPage(String url, String canonicalUrl, String sourceType, boolean ok,
                           String method, String directStatus, String textForPrompt, String fullTextForVerify) {
            this(url, canonicalUrl, sourceType, ok, method, directStatus, textForPrompt, fullTextForVerify, List.of());
        }

        public static FetchedPage failed(String url, String sourceType, String method, String directStatus) {
            return new FetchedPage(url, WebSearchClient.canonicalizeUrl(url), sourceType,
                false, method, directStatus, "", "", List.of());
        }
    }

    /** 供单测桩覆写的底层单跳 HTTP 响应抽象。 */
    record RawHttpResult(int statusCode, String contentType, String locationHeader, byte[] body) {
    }

    public WebPageFetcher(MusicConfig config, TavilyWebSearchClient tavilyClient) {
        this.config = config;
        this.tavilyClient = tavilyClient;
        DnsResolver resolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                Map<String, InetAddress[]> pins = requestPins.get();
                InetAddress[] addresses = pins == null ? null : pins.get(host.toLowerCase(Locale.ROOT));
                if (addresses == null) {
                    throw new UnknownHostException("Host not prevalidated: " + host);
                }
                return addresses.clone();
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver).build();
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofSeconds(5))
            .setConnectTimeout(Timeout.ofSeconds(10))
            .setResponseTimeout(Timeout.ofSeconds(15))
            .build();
        this.pinnedClient = HttpClients.custom()
            .setConnectionManager(manager)
            .setDefaultRequestConfig(requestConfig)
            // 禁止连接复用，确保每次抓取都经由当次预校验的 DNS Pinning 地址建连，防 DNS 重绑定/连接池复用错配
            .setConnectionReuseStrategy((request, response, context) -> false)
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .build();
    }

    @Override
    public void close() {
        try {
            pinnedClient.close();
        } catch (IOException e) {
            log.debug("关闭 WebPageFetcher HTTP 客户端失败: {}", e.getMessage());
        }
    }

    /** 对已知对尾斜杠敏感的歌词站做无害 URL 归一化，并清除追踪参数。 */
    public static String normalizeFetchUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        String cleaned = WebSearchClient.stripTrackingParams(rawUrl.trim());
        if (UTANET_SONG_NO_SLASH.matcher(cleaned).matches()) {
            return cleaned + "/";
        }
        return cleaned;
    }

    /** 仅执行免费直连读取（供原生模式可选引文复核使用，不消耗 Tavily 额度）。 */
    public FetchedPage fetchDirectOnly(String rawUrl, String artistHint) throws InterruptedException {
        Map<String, FetchedPage> batch = fetchBatch(List.of(rawUrl), artistHint, false);
        String canon = WebSearchClient.canonicalizeUrl(normalizeFetchUrl(rawUrl));
        return batch.getOrDefault(canon, FetchedPage.failed(rawUrl,
            WebSearchClient.SourceReliability.classifySourceType(rawUrl, artistHint), "direct_http", "failed"));
    }

    /**
     * 批量读取一组网页：先逐个安全直连，再把符合条件的受阻 URL（非 404 / 非 SSRF / 非体积或重定向违规）
     * 一次性批量打包降级至 Tavily /extract（每批最多 5 个 URL 共享 1 credit），且只采信与提交批次精确匹配的返回项。
     */
    public Map<String, FetchedPage> fetchBatch(List<String> rawUrls, String artistHint,
                                               boolean allowTavilyExtract) throws InterruptedException {
        Map<String, FetchedPage> results = new LinkedHashMap<>();
        if (rawUrls == null || rawUrls.isEmpty()) {
            return results;
        }
        List<String> fallbackCandidates = new ArrayList<>();
        Map<String, String> fallbackOriginalUrl = new LinkedHashMap<>();

        for (String raw : rawUrls) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("cancelled");
            }
            String url = normalizeFetchUrl(raw);
            if (url.isEmpty()) {
                continue;
            }
            String canon = WebSearchClient.canonicalizeUrl(url);
            if (canon.isEmpty() || results.containsKey(canon)) {
                continue;
            }
            FetchedPage page = fetchSingleDirect(url, canon, artistHint);
            results.put(canon, page);
            if (!page.ok() && shouldFallbackToExtract(page.directStatus())) {
                fallbackCandidates.add(url);
                fallbackOriginalUrl.put(canon, url);
            }
        }

        boolean extractEnabled = allowTavilyExtract
            && (config == null || config.isOnlineCreditsAllowTavilyExtract())
            && tavilyClient != null && tavilyClient.hasTavilyKey();
        if (extractEnabled && !fallbackCandidates.isEmpty()) {
            try {
                Map<String, String> extracted = tavilyClient.extract(fallbackCandidates, "basic");
                for (Map.Entry<String, String> entry : extracted.entrySet()) {
                    String canon = entry.getKey();
                    // 仅采信属于本批次提交列表中的目标 URL，防止外部响应注入未请求的 canonicalUrl
                    if (!fallbackOriginalUrl.containsKey(canon)) {
                        continue;
                    }
                    String content = entry.getValue();
                    if (content == null || content.isBlank()) {
                        continue;
                    }
                    FetchedPage existing = results.get(canon);
                    String url = fallbackOriginalUrl.get(canon);
                    String sourceType = WebSearchClient.SourceReliability.classifySourceType(url, artistHint);
                    String directStatus = existing != null ? existing.directStatus() : "skipped";
                    String verifyText = truncate(content, VERIFY_TEXT_LIMIT);
                    String promptText = truncate(content, PROMPT_TEXT_LIMIT);
                    List<ExtractedMbArtistLink> links = extractMbArtistLinksFromText(content, url);
                    results.put(canon, new FetchedPage(
                        url, canon, sourceType, true, "tavily_extract", directStatus, promptText, verifyText, links));
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (Exception e) {
                log.warn("批量 Tavily /extract 降级异常: {}", NativeWebSearchClient.redactSecrets(e.getMessage()));
            }
        }
        return results;
    }

    private static boolean shouldFallbackToExtract(String directStatus) {
        if (directStatus == null) {
            return false;
        }
        return ELIGIBLE_EXTRACT_FALLBACK_STATUSES.contains(directStatus)
            || directStatus.startsWith("network_error:");
    }

    private FetchedPage fetchSingleDirect(String initialUrl, String canon, String artistHint)
            throws InterruptedException {
        String initialSourceType = WebSearchClient.SourceReliability.classifySourceType(initialUrl, artistHint);
        String currentUrl = initialUrl;

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("cancelled");
            }
            OutboundUrlValidator.ValidatedTarget target;
            try {
                target = validatePublicTarget(currentUrl);
            } catch (OutboundUrlValidator.ValidationException e) {
                log.warn("网页抓取被 SSRF 策略拦截 ({}): {}", stripQueryForLog(currentUrl), e.getCode());
                return FetchedPage.failed(initialUrl, initialSourceType, "ssrf_blocked", "ssrf_blocked:" + e.getCode());
            }

            // 配置代理时拒绝通过未固定 DNS 的代理直连不受信任外部 URL（防范代理侧 DNS 重绑定 SSRF）
            if (HttpClientFactory.isProxyConfigured(config)) {
                log.debug("已配置 HTTP 代理，跳过不受信任网页的未固定 DNS 直连: {}", stripQueryForLog(currentUrl));
                return FetchedPage.failed(initialUrl, initialSourceType, "direct_http", "proxy_unpinned_blocked");
            }

            RawHttpResult raw;
            try {
                raw = executeSingleHop(target);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (IOException e) {
                String msg = e.getMessage() == null ? "io_error" : e.getMessage();
                if ("response.too.large".equals(msg)) {
                    return FetchedPage.failed(initialUrl, initialSourceType, "direct_http", "response_too_large");
                }
                if ("unsupported.content.type".equals(msg)) {
                    return FetchedPage.failed(initialUrl, initialSourceType, "direct_http", "unsupported_content_type");
                }
                return FetchedPage.failed(initialUrl, initialSourceType, "direct_http",
                    "network_error:" + truncate(NativeWebSearchClient.redactSecrets(msg), 80));
            }

            int status = raw.statusCode();
            if (isRedirect(status) && raw.locationHeader() != null && !raw.locationHeader().isBlank()) {
                if (hop == MAX_REDIRECTS) {
                    return FetchedPage.failed(initialUrl, initialSourceType, "direct_http", "too_many_redirects");
                }
                try {
                    URI next = target.uri().resolve(raw.locationHeader().trim());
                    currentUrl = next.toString();
                    continue;
                } catch (Exception e) {
                    return FetchedPage.failed(initialUrl, initialSourceType, "direct_http", "invalid_redirect");
                }
            }

            // 跨源重定向后按最终落地 URL 重算 sourceType，防止开放重定向借壳伪造权威来源
            String finalSourceType = WebSearchClient.SourceReliability.classifySourceType(currentUrl, artistHint);

            if (status == 401 || status == 403 || status == 429 || status == 404 || status == 410) {
                return FetchedPage.failed(initialUrl, finalSourceType, "direct_http", "http_" + status);
            }
            if (status != 200) {
                return FetchedPage.failed(initialUrl, finalSourceType, "direct_http", "http_" + status);
            }

            return parseHtmlBody(initialUrl, canon, currentUrl, finalSourceType, raw);
        }
        return FetchedPage.failed(initialUrl, initialSourceType, "direct_http", "too_many_redirects");
    }

    /** 开放域网页读取绝不允许私网端点，始终要求公网 IP 与 80/443 端口。 */
    OutboundUrlValidator.ValidatedTarget validatePublicTarget(String url)
            throws OutboundUrlValidator.ValidationException {
        return OutboundUrlValidator.validateAndResolve(url, false, true, true);
    }

    RawHttpResult executeSingleHop(OutboundUrlValidator.ValidatedTarget target)
            throws IOException, InterruptedException {
        requestPins.set(Map.of(target.normalizedHost(), target.addresses()));
        HttpGet get = new HttpGet(target.uri());
        get.setHeader("User-Agent", DEFAULT_UA);
        get.setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        get.setHeader("Accept-Language", "ja,en-US;q=0.9,en;q=0.8");
        get.setHeader("Connection", "close");
        try (CloseableHttpResponse response = pinnedClient.execute(get)) {
            int code = response.getCode();
            Header ctHeader = response.getFirstHeader("Content-Type");
            Header locHeader = response.getFirstHeader("Location");
            Header lenHeader = response.getFirstHeader("Content-Length");
            String contentType = ctHeader == null ? null : ctHeader.getValue();
            String location = locHeader == null ? null : locHeader.getValue();

            // 非 200（重定向/403/404/429 等）不读取正文，直接 abort 关闭连接
            if (code != 200) {
                get.abort();
                return new RawHttpResult(code, contentType, location, new byte[0]);
            }
            if (!isSupportedTextContentType(contentType)) {
                get.abort();
                throw new IOException("unsupported.content.type");
            }
            if (lenHeader != null) {
                try {
                    long declaredLen = Long.parseLong(lenHeader.getValue().trim());
                    if (declaredLen > MAX_RESPONSE_BYTES) {
                        get.abort();
                        throw new IOException("response.too.large");
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            byte[] bytes = new byte[0];
            if (response.getEntity() != null) {
                try (InputStream in = response.getEntity().getContent()) {
                    bytes = OutboundUrlValidator.readBoundedBytes(in, MAX_RESPONSE_BYTES);
                } catch (IOException e) {
                    // 超限或流异常时立即 abort 终止底层套接字，避免 close() 排空超大响应体
                    get.abort();
                    throw e;
                }
            }
            return new RawHttpResult(code, contentType, location, bytes);
        } finally {
            requestPins.remove();
        }
    }

    private static boolean isSupportedTextContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        String ct = contentType.toLowerCase(Locale.ROOT);
        return ct.contains("html") || ct.contains("xml") || ct.contains("text/") || ct.contains("json");
    }

    private FetchedPage parseHtmlBody(String initialUrl, String canon, String finalUrl,
                                      String sourceType, RawHttpResult raw) {
        byte[] bytes = raw.body() == null ? new byte[0] : raw.body();
        if (bytes.length == 0) {
            return FetchedPage.failed(initialUrl, sourceType, "direct_http", "js_shell_or_thin");
        }
        if (!isSupportedTextContentType(raw.contentType())) {
            return FetchedPage.failed(initialUrl, sourceType, "direct_http", "unsupported_content_type");
        }

        String rawUtf8Head = new String(bytes, 0, Math.min(bytes.length, 6000), StandardCharsets.UTF_8)
            .toLowerCase(Locale.ROOT);
        for (String sig : WAF_SIGNATURES) {
            if (rawUtf8Head.contains(sig)) {
                return FetchedPage.failed(initialUrl, sourceType, "direct_http", "waf_challenge");
            }
        }

        if (isYouTubeWatchUrl(finalUrl)) {
            String fullHtml = new String(bytes, StandardCharsets.UTF_8);
            String ytDesc = extractYouTubeDescription(fullHtml);
            if (ytDesc == null || ytDesc.isBlank()) {
                return FetchedPage.failed(initialUrl, sourceType, "direct_http", "js_shell_or_bot_gate");
            }
            List<ExtractedMbArtistLink> ytLinks = extractMbArtistLinksFromText(ytDesc, finalUrl);
            return new FetchedPage(initialUrl, canon, sourceType, true, "direct_http", "ok",
                truncate(ytDesc, PROMPT_TEXT_LIMIT), truncate(ytDesc, VERIFY_TEXT_LIMIT), ytLinks);
        }

        String charset = extractValidatedCharset(raw.contentType());
        String extracted;
        String bodyOnly;
        List<ExtractedMbArtistLink> mbLinks;
        try {
            Document doc = Jsoup.parse(new ByteArrayInputStream(bytes), charset, finalUrl);
            mbLinks = extractMbArtistLinksFromDocument(doc, finalUrl);
            StringBuilder header = new StringBuilder();
            String title = doc.title();
            if (title != null && !title.isBlank()) {
                header.append("PAGE_TITLE: ").append(title.trim()).append('\n');
            }
            Elements metas = doc.select("meta[name=description], meta[property=og:description], meta[property=og:title]");
            for (Element meta : metas) {
                String content = meta.attr("content");
                if (content != null && !content.isBlank()) {
                    header.append("META: ").append(content.trim()).append('\n');
                }
            }
            Elements jsonLds = doc.select("script[type=application/ld+json]");
            int ldKept = 0;
            for (Element ld : jsonLds) {
                if (ldKept >= 2) break;
                String data = ld.data();
                if (data != null && (data.contains("Music") || data.contains("composer")
                        || data.contains("lyricist") || data.contains("VideoObject") || data.contains("track"))) {
                    header.append("JSONLD: ").append(truncate(data.trim(), 2000)).append('\n');
                    ldKept++;
                }
            }
            doc.select("script, style, noscript, svg, iframe, template").remove();
            bodyOnly = normalizeWhitespace(doc.body() == null ? doc.text() : doc.body().wholeText());
            extracted = header.length() > 0
                ? header.append("\nBODY:\n").append(bodyOnly).toString().trim()
                : bodyOnly;
        } catch (Exception e) {
            // 将非法字符集或畸形 HTML 解析异常收敛在单页内，绝不中断整批抓取
            return FetchedPage.failed(initialUrl, sourceType, "direct_http", "parse_error");
        }

        if (effectiveBodyLength(bodyOnly) < MIN_USEFUL_BODY_CHARS) {
            return FetchedPage.failed(initialUrl, sourceType, "direct_http", "js_shell_or_thin");
        }
        return new FetchedPage(initialUrl, canon, sourceType, true, "direct_http", "ok",
            truncate(extracted, PROMPT_TEXT_LIMIT), truncate(extracted, VERIFY_TEXT_LIMIT), mbLinks);
    }

    /** 在 HTML 清洗前从 <a href>、JSON-LD 及页面自身 URL 提取合法的 MusicBrainz Artist UUID 链接。 */
    static List<ExtractedMbArtistLink> extractMbArtistLinksFromDocument(Document doc, String pageUrl) {
        Map<String, ExtractedMbArtistLink> byMbid = new LinkedHashMap<>();
        if (pageUrl != null) {
            extractMbArtistLinkFromUrl(pageUrl, doc == null ? null : doc.title(), pageUrl)
                .ifPresent(link -> mergeMbLink(byMbid, link));
        }
        if (doc != null) {
            for (Element anchor : doc.select("a[href]")) {
                if (byMbid.size() >= MAX_MB_LINKS_PER_PAGE) break;
                String href = anchor.absUrl("href");
                if (href == null || href.isBlank()) {
                    href = anchor.attr("href");
                }
                if (href == null || href.isBlank()) continue;
                extractMbArtistLinkFromUrl(href, anchor.text(), pageUrl)
                    .ifPresent(link -> mergeMbLink(byMbid, link));
            }
            for (Element ld : doc.select("script[type=application/ld+json]")) {
                if (byMbid.size() >= MAX_MB_LINKS_PER_PAGE) break;
                addMbLinksFromText(ld.data(), null, pageUrl, byMbid);
            }
        }
        return byMbid.isEmpty() ? List.of() : List.copyOf(byMbid.values());
    }

    /** 严格按完整 URL 解析校验 MusicBrainz Artist 页面链接（拒绝将 MB 链接嵌套在其他站点查询参数中的伪链接）。 */
    public static java.util.Optional<ExtractedMbArtistLink> extractMbArtistLinkFromUrl(
            String rawUrl, String anchorText, String sourceUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return java.util.Optional.empty();
            }
            String host = uri.getHost();
            if (host == null) {
                return java.util.Optional.empty();
            }
            String lowerHost = host.toLowerCase(Locale.ROOT);
            if (!"musicbrainz.org".equals(lowerHost)
                    && !"www.musicbrainz.org".equals(lowerHost)
                    && !"beta.musicbrainz.org".equals(lowerHost)) {
                return java.util.Optional.empty();
            }
            String path = uri.getPath();
            if (path == null) {
                return java.util.Optional.empty();
            }
            String trimmedPath = path.startsWith("/") ? path.substring(1) : path;
            String[] segments = trimmedPath.split("/");
            if (segments.length < 2 || !"artist".equalsIgnoreCase(segments[0])) {
                return java.util.Optional.empty();
            }
            String mbid = com.lux032.musicautotagger.service.MusicBrainzArtistCache.normalizeValidMbid(segments[1]);
            if (mbid == null) {
                return java.util.Optional.empty();
            }
            String cleanAnchor = cleanAnchorText(anchorText);
            return java.util.Optional.of(new ExtractedMbArtistLink(mbid, cleanAnchor, sourceUrl));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }

    /** 从自由文本（如 JSON-LD）中提取合法的 MusicBrainz Artist UUID 链接；若提供 sourceUrl 也单独严格校验。 */
    public static List<ExtractedMbArtistLink> extractMbArtistLinksFromText(String text, String sourceUrl) {
        Map<String, ExtractedMbArtistLink> byMbid = new LinkedHashMap<>();
        if (sourceUrl != null) {
            extractMbArtistLinkFromUrl(sourceUrl, null, sourceUrl)
                .ifPresent(link -> mergeMbLink(byMbid, link));
        }
        if (text != null) {
            addMbLinksFromText(text, null, sourceUrl, byMbid);
        }
        return byMbid.isEmpty() ? List.of() : List.copyOf(byMbid.values());
    }

    private static void addMbLinksFromText(String input, String anchorText, String sourceUrl,
                                           Map<String, ExtractedMbArtistLink> target) {
        if (input == null || input.isBlank() || target.size() >= MAX_MB_LINKS_PER_PAGE) {
            return;
        }
        Matcher matcher = MB_ARTIST_URL_PATTERN.matcher(input);
        String cleanAnchor = cleanAnchorText(anchorText);
        while (matcher.find() && target.size() < MAX_MB_LINKS_PER_PAGE) {
            String mbid = com.lux032.musicautotagger.service.MusicBrainzArtistCache.normalizeValidMbid(matcher.group(1));
            if (mbid == null) continue;
            mergeMbLink(target, new ExtractedMbArtistLink(mbid, cleanAnchor, sourceUrl));
        }
    }

    private static void mergeMbLink(Map<String, ExtractedMbArtistLink> target, ExtractedMbArtistLink incoming) {
        if (incoming == null || target.size() >= MAX_MB_LINKS_PER_PAGE) {
            return;
        }
        ExtractedMbArtistLink existing = target.get(incoming.mbid());
        if (existing == null
                || ((existing.anchorText() == null || existing.anchorText().isBlank())
                    && incoming.anchorText() != null && !incoming.anchorText().isBlank())) {
            target.put(incoming.mbid(), incoming);
        }
    }

    private static String cleanAnchorText(String anchorText) {
        if (anchorText == null) return null;
        String clean = anchorText.replaceAll("\\s+", " ").trim();
        return clean.isEmpty() ? null : clean;
    }

    /** 定位同一 videoDetails JSON 对象，同步提取 title / author / shortDescription，避免全局正则错配。 */
    static String extractYouTubeDescription(String html) {
        if (html == null || html.isEmpty()) {
            return null;
        }
        Matcher m = YT_VIDEO_DETAILS_KEY.matcher(html);
        if (!m.find()) {
            return null;
        }
        int braceStart = html.indexOf('{', m.start());
        if (braceStart < 0) {
            return null;
        }
        String jsonSlice = extractBalancedJsonObject(html, braceStart, 200_000);
        if (jsonSlice == null) {
            return null;
        }
        try {
            JsonObject details = GSON.fromJson(jsonSlice, JsonObject.class);
            if (details == null) return null;
            String desc = jsonStr(details, "shortDescription");
            if (desc == null || desc.isBlank()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            String title = jsonStr(details, "title");
            if (title != null && !title.isBlank()) sb.append("YT_TITLE: ").append(title).append('\n');
            String author = jsonStr(details, "author");
            if (author != null && !author.isBlank()) sb.append("YT_CHANNEL: ").append(author).append('\n');
            sb.append("YT_DESCRIPTION:\n").append(desc);
            return sb.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String extractBalancedJsonObject(String text, int startIdx, int maxChars) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int endLimit = Math.min(text.length(), startIdx + maxChars);
        for (int i = startIdx; i < endLimit; i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else {
                if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return text.substring(startIdx, i + 1);
                    }
                }
            }
        }
        return null;
    }

    private static String jsonStr(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    static boolean isYouTubeWatchUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            if (host == null) return false;
            String h = host.toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if ("youtube.com".equals(h) || "www.youtube.com".equals(h) || "m.youtube.com".equals(h)) {
                return "/watch".equals(path) || path.startsWith("/watch/");
            }
            return "youtu.be".equals(h) && path.length() > 1;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String extractValidatedCharset(String contentType) {
        if (contentType == null) return null;
        Matcher m = CHARSET_PATTERN.matcher(contentType);
        if (!m.find()) return null;
        String candidate = m.group(1);
        try {
            return Charset.isSupported(candidate) ? candidate : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String stripQueryForLog(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }

    private static String normalizeWhitespace(String raw) {
        if (raw == null || raw.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\\R")) {
            String cleaned = line.replaceAll("[ \\t\\x0B\\f\\r]+", " ").trim();
            if (!cleaned.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(cleaned);
            }
        }
        return sb.toString();
    }

    private static int effectiveBodyLength(String text) {
        if (text == null || text.isEmpty()) return 0;
        int score = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            score += (c >= 0x3000 && c <= 0x9FFF) || (c >= 0xAC00 && c <= 0xD7AF) ? 2 : 1;
        }
        return score;
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }
}
