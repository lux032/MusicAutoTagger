package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.input.BoundedInputStream;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Downloads model-supplied image URLs under strict SSRF and resource limits. */
@Slf4j
public class UntrustedImageFetcher implements AutoCloseable {
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final long MAX_PIXELS = 50_000_000L;
    private final MusicConfig config;
    private final ThreadLocal<Map<String, InetAddress[]>> requestPins = new ThreadLocal<>();
    private final ThreadPoolExecutor executor;
    private final CloseableHttpClient client;
    private volatile CloseableHttpClient proxyClient;
    private volatile String activeProxySignature;

    public UntrustedImageFetcher() {
        this(null);
    }

    public UntrustedImageFetcher(MusicConfig config) {
        this.config = config;
        DnsResolver resolver = new DnsResolver() {
            @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                Map<String, InetAddress[]> pins = requestPins.get();
                InetAddress[] addresses = pins == null ? null : pins.get(host.toLowerCase(Locale.ROOT));
                if (addresses == null) throw new UnknownHostException("Host not prevalidated");
                return addresses.clone();
            }
            @Override public String resolveCanonicalHostname(String host) { return host; }
        };
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver).build();
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofSeconds(5))
            .setConnectTimeout(Timeout.ofSeconds(5)).setResponseTimeout(Timeout.ofSeconds(10)).build();
        client = HttpClients.custom().setConnectionManager(manager).setDefaultRequestConfig(requestConfig)
            .disableRedirectHandling().disableAutomaticRetries().build();
        executor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), r -> {
                Thread t = new Thread(r, "untrusted-image-fetch");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    private boolean isProxyConfigured() {
        return config != null && config.isProxyEnabled()
            && config.getProxyHost() != null && !config.getProxyHost().isBlank()
            && config.getProxyPort() > 0;
    }

    private CloseableHttpClient proxyClient() {
        String host = config.getProxyHost().trim();
        int port = config.getProxyPort();
        String user = config.getProxyUsername() == null ? "" : config.getProxyUsername();
        String pass = config.getProxyPassword() == null ? "" : config.getProxyPassword();
        String sig = host + ":" + port + ":" + user + ":" + pass.hashCode();
        CloseableHttpClient existing = this.proxyClient;
        if (existing != null && sig.equals(this.activeProxySignature)) {
            return existing;
        }
        synchronized (this) {
            existing = this.proxyClient;
            if (existing != null && sig.equals(this.activeProxySignature)) {
                return existing;
            }
            CloseableHttpClient old = existing;
            RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                .setConnectTimeout(Timeout.ofSeconds(8)).setResponseTimeout(Timeout.ofSeconds(12)).build();
            HttpClientBuilder builder = HttpClients.custom()
                .setProxy(new HttpHost(host, port))
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()
                .disableAutomaticRetries();
            if (!user.isEmpty() && !pass.isEmpty()) {
                BasicCredentialsProvider creds = new BasicCredentialsProvider();
                creds.setCredentials(new AuthScope(host, port), new UsernamePasswordCredentials(user, pass.toCharArray()));
                builder.setDefaultCredentialsProvider(creds);
            }
            CloseableHttpClient created = builder.build();
            this.proxyClient = created;
            this.activeProxySignature = sig;
            if (old != null) {
                try { old.close(); } catch (Exception ignored) {}
            }
            return created;
        }
    }

    public Optional<Result> fetch(String url) {
        AtomicReference<HttpGet> activeRequest = new AtomicReference<>();
        Future<Optional<Result>> future = null;
        try {
            future = executor.submit(() -> fetchInternal(url, activeRequest));
            return future.get(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            HttpGet request = activeRequest.get();
            if (request != null) request.abort();
            if (future != null) future.cancel(true);
            Thread.currentThread().interrupt();
            log.debug("Untrusted image fetch interrupted: {}", url);
            return Optional.empty();
        } catch (TimeoutException e) {
            HttpGet request = activeRequest.get();
            if (request != null) request.abort();
            if (future != null) future.cancel(true);
            log.debug("Untrusted image fetch timed out: {}", url);
            return Optional.empty();
        } catch (Exception e) {
            if (future != null) future.cancel(true);
            log.debug("Untrusted image fetch rejected: {} ({})", url, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Result> fetchInternal(String url, AtomicReference<HttpGet> activeRequest) {
        try {
            boolean useProxy = isProxyConfigured();
            URI targetUri;
            CloseableHttpClient httpClient;
            if (useProxy) {
                var target = validateForProxy(url);
                targetUri = target;
                httpClient = proxyClient();
            } else {
                var target = com.lux032.musicautotagger.util.OutboundUrlValidator.validateAndResolve(
                    url, false, true, true);
                requestPins.set(Map.of(target.normalizedHost(), target.addresses()));
                targetUri = target.uri();
                httpClient = client;
            }
            HttpGet request = new HttpGet(targetUri);
            activeRequest.set(request);

            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int status = response.getCode();
                if (status < 200 || status >= 300 || response.getEntity() == null) return Optional.empty();
                long declared = response.getEntity().getContentLength();
                if (declared > MAX_BYTES) return Optional.empty();
                String contentType = response.getEntity().getContentType();
                if (contentType == null) return Optional.empty();
                String normalized = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (!normalized.matches("image/(jpeg|png|webp|gif)")) return Optional.empty();
                byte[] data;
                try (BoundedInputStream in = new BoundedInputStream(response.getEntity().getContent(), MAX_BYTES + 1);
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    in.transferTo(out);
                    if (out.size() > MAX_BYTES) return Optional.empty();
                    data = out.toByteArray();
                }
                return inspect(data);
            }
        } catch (Exception e) {
            log.debug("Unable to fetch untrusted image {}: {}", url, e.getMessage());
            return Optional.empty();
        } finally {
            activeRequest.set(null);
            requestPins.remove();
        }
    }

    public static Optional<Result> inspect(byte[] data) {
        if (data == null || data.length == 0 || data.length > ImageCompressor.MAX_INPUT_BYTES) return Optional.empty();
        // Java 17 ImageIO has no built-in WebP reader. Parse VP8/VP8L/VP8X header dimensions strictly
        // before handing WebP bytes to hardened ImageCompressor ffmpeg fallback.
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
            && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            int[] dims = parseWebpDimensions(data);
            if (dims == null || dims[0] <= 0 || dims[1] <= 0
                || Math.max(dims[0], dims[1]) > ImageCompressor.MAX_SOURCE_DIMENSION
                || (long) dims[0] * dims[1] > MAX_PIXELS) {
                return Optional.empty();
            }
            return Optional.of(new Result(data, "image/webp", dims[0], dims[1], data.length));
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return Optional.empty();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0
                    || Math.max(width, height) > ImageCompressor.MAX_SOURCE_DIMENSION
                    || width * (long) height > MAX_PIXELS) {
                    return Optional.empty();
                }
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String mime = "jpg".equals(format) || "jpeg".equals(format) ? "image/jpeg" : "image/" + format;
                return Optional.of(new Result(data, mime, width, height, data.length));
            } finally { reader.dispose(); }
        } catch (Exception e) { return Optional.empty(); }
    }

    /** 按 RFC 9649 解析 WebP 容器头部（VP8X / VP8L / VP8 ）真实宽高，拦截畸形数据与超高像素炸弹。 */
    static int[] parseWebpDimensions(byte[] data) {
        if (data == null || data.length < 25) return null;
        if (data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F'
            || data[8] != 'W' || data[9] != 'E' || data[10] != 'B' || data[11] != 'P') {
            return null;
        }
        if (data[12] == 'V' && data[13] == 'P' && data[14] == '8' && data[15] == 'X') {
            if (data.length < 30) return null;
            int w = 1 + ((data[24] & 0xff) | ((data[25] & 0xff) << 8) | ((data[26] & 0xff) << 16));
            int h = 1 + ((data[27] & 0xff) | ((data[28] & 0xff) << 8) | ((data[29] & 0xff) << 16));
            return new int[]{w, h};
        }
        if (data[12] == 'V' && data[13] == 'P' && data[14] == '8' && data[15] == 'L') {
            if ((data[20] & 0xff) != 0x2f) return null;
            int bits = (data[21] & 0xff) | ((data[22] & 0xff) << 8)
                | ((data[23] & 0xff) << 16) | ((data[24] & 0xff) << 24);
            int w = (bits & 0x3fff) + 1;
            int h = ((bits >>> 14) & 0x3fff) + 1;
            return new int[]{w, h};
        }
        if (data[12] == 'V' && data[13] == 'P' && data[14] == '8' && data[15] == ' ') {
            if (data.length < 30) return null;
            if ((data[23] & 0xff) != 0x9d || (data[24] & 0xff) != 0x01 || (data[25] & 0xff) != 0x2a) {
                return null;
            }
            int w = ((data[26] & 0xff) | ((data[27] & 0xff) << 8)) & 0x3fff;
            int h = ((data[28] & 0xff) | ((data[29] & 0xff) << 8)) & 0x3fff;
            return new int[]{w, h};
        }
        return null;
    }

    static boolean isPublic(InetAddress address) {
        return com.lux032.musicautotagger.util.OutboundUrlValidator.isPublic(address);
    }

    private static URI validateForProxy(String url) throws com.lux032.musicautotagger.util.OutboundUrlValidator.ValidationException {
        try {
            return com.lux032.musicautotagger.util.OutboundUrlValidator.validateAndResolve(
                url, false, true, true).uri();
        } catch (com.lux032.musicautotagger.util.OutboundUrlValidator.ValidationException e) {
            // 在纯代理环境下（本地无法直连外网 DNS），仅当失败原因是外网域名本地无法解析时，
            // 在确认不是本地/内网主机名与字面量 IP 后允许交给代理远端解析。
            if (!"url.unresolvable".equals(e.getCode())) {
                throw e;
            }
            URI uri = URI.create(url.trim());
            String host = com.lux032.musicautotagger.util.OutboundUrlValidator.stripIpv6Brackets(uri.getHost())
                .toLowerCase(Locale.ROOT);
            if ("localhost".equals(host) || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".internal")
                || host.endsWith(".lan") || !host.contains(".")
                || host.indexOf(':') >= 0 || host.matches("^[0-9.]+$")) {
                throw e;
            }
            return uri;
        }
    }

    @Override public void close() throws Exception {
        executor.shutdownNow();
        client.close();
        CloseableHttpClient pc = this.proxyClient;
        if (pc != null) {
            pc.close();
        }
    }
    public record Result(byte[] data, String mime, int width, int height, long bytes) {}
}
