package com.lux032.musicautotagger.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.input.BoundedInputStream;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
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
    private final ThreadLocal<Map<String, InetAddress[]>> requestPins = new ThreadLocal<>();
    private final ThreadPoolExecutor executor;
    private final CloseableHttpClient client;

    public UntrustedImageFetcher() {
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

    public Optional<Result> fetch(String url) {
        AtomicReference<HttpGet> activeRequest = new AtomicReference<>();
        Future<Optional<Result>> future = null;
        try {
            future = executor.submit(() -> fetchInternal(url, activeRequest));
            return future.get(20, TimeUnit.SECONDS);
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
            URI uri = URI.create(url);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) return Optional.empty();
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) if (!isPublic(address)) return Optional.empty();
            requestPins.set(Map.of(host, addresses));
            HttpGet request = new HttpGet(uri);
            activeRequest.set(request);

            try (CloseableHttpResponse response = client.execute(request)) {
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
        if (data == null || data.length == 0) return Optional.empty();
        // Java 17 ImageIO has no built-in WebP reader. Validate its RIFF/WEBP magic here;
        // CoverCandidateService immediately converts it with ffmpeg before caching or display.
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
            && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return Optional.of(new Result(data, "image/webp", 0, 0, data.length));
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return Optional.empty();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width * (long) height > MAX_PIXELS) return Optional.empty();
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String mime = "jpg".equals(format) || "jpeg".equals(format) ? "image/jpeg" : "image/" + format;
                return Optional.of(new Result(data, mime, width, height, data.length));
            } finally { reader.dispose(); }
        } catch (Exception e) { return Optional.empty(); }
    }

    static boolean isPublic(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff, second = b[1] & 0xff, third = b[2] & 0xff;
            return first != 0
                && !(first == 100 && (second & 0xc0) == 64)
                && !(first == 169 && second == 254)
                && !(first == 192 && second == 0 && third == 0)
                && !(first == 198 && (second & 0xfe) == 18)
                && !(first == 192 && second == 88 && third == 99);
        }
        if (b.length != 16 || (b[0] & 0xfe) == 0xfc) return false;
        boolean nat64 = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64
            && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b;
        if (nat64) {
            for (int i = 4; i < 12; i++) if (b[i] != 0) { nat64 = false; break; }
            if (nat64) return false;
        }
        boolean ipv4Compatible = true;
        for (int i = 0; i < 12; i++) if (b[i] != 0) { ipv4Compatible = false; break; }
        return !ipv4Compatible;
    }

    @Override public void close() throws Exception {
        executor.shutdownNow();
        client.close();
    }
    public record Result(byte[] data, String mime, int width, int height, long bytes) {}
}
