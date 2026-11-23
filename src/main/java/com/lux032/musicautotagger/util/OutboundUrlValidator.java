package com.lux032.musicautotagger.util;

import org.apache.commons.io.input.BoundedInputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 统一的出站 URL 安全校验（SSRF 防护）、Origin 比对、有界响应读取与敏感文件权限工具。
 */
public final class OutboundUrlValidator {

    public static final long DEFAULT_MAX_RESPONSE_BYTES = 8L * 1024 * 1024;

    private static final Set<PosixFilePermission> OWNER_READ_WRITE =
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private OutboundUrlValidator() {
    }

    public static final class ValidationException extends IOException {
        private final String code;

        public ValidationException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    public record ValidatedTarget(URI uri, String normalizedHost, InetAddress[] addresses) {
    }

    /**
     * 校验出站 URL 是否满足安全约束，并返回预解析后的 IP 列表供调用方绑定（DNS Pinning）。
     *
     * @param rawUrl                待请求 URL
     * @param allowPrivateEndpoints 是否允许访问内网与纯 HTTP 地址（仅允许由配置文件显式开启）
     * @param allowHttpWhenPublic   公网模式下是否允许 http（如图片 URL 允许 http/https，LLM/搜索强制 https）
     * @param restrictStandardPorts 公网模式下是否限制仅允许默认端口 80 / 443
     */
    public static ValidatedTarget validateAndResolve(String rawUrl,
                                                     boolean allowPrivateEndpoints,
                                                     boolean allowHttpWhenPublic,
                                                     boolean restrictStandardPorts) throws ValidationException {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new ValidationException("url.invalid", "llm.models.url.invalid");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception e) {
            throw new ValidationException("url.invalid", "llm.models.url.invalid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isBlank()) {
            throw new ValidationException("url.invalid", "llm.models.url.invalid");
        }
        if (uri.getUserInfo() != null) {
            throw new ValidationException("url.invalid", "llm.models.url.invalid");
        }

        String host = stripIpv6Brackets(rawHost).toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            throw new ValidationException("url.invalid", "llm.models.url.invalid");
        }

        if (allowPrivateEndpoints) {
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new ValidationException("url.scheme.invalid", "llm.models.url.scheme.invalid");
            }
            return new ValidatedTarget(uri, host, new InetAddress[0]);
        }

        if (allowHttpWhenPublic) {
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new ValidationException("url.scheme.invalid", "llm.models.url.scheme.invalid");
            }
        } else if (!"https".equals(scheme)) {
            throw new ValidationException("url.https.required", "llm.models.url.https.required");
        }

        if (restrictStandardPorts) {
            int port = uri.getPort();
            if (port != -1 && port != 80 && port != 443) {
                throw new ValidationException("url.port.blocked", "url.port.blocked: " + port);
            }
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new ValidationException("url.unresolvable", "llm.models.url.unresolvable: " + host);
        }
        if (addresses == null || addresses.length == 0) {
            throw new ValidationException("url.unresolvable", "llm.models.url.unresolvable: " + host);
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new ValidationException("url.private.blocked",
                    "llm.models.url.private.blocked: " + host + " -> " + address.getHostAddress());
            }
        }
        return new ValidatedTarget(uri, host, addresses);
    }

    /**
     * 判断 IP 地址是否属于可路由公网单播地址。
     *
     * IPv4 显式拦截保留/私网/回环/链路本地/CGNAT/测试网/广播/组播段；
     * IPv6 仅放行全球单播 2000::/3 并剔除 Teredo/6to4/文档/基准测试段，
     * 从而一并拦截 ULA(fc00::/7)、NAT64(64:ff9b::/96, 64:ff9b:1::/48)、SIIT(::ffff:0:0:0/96) 等。
     */
    public static boolean isPublic(InetAddress address) {
        if (address == null) {
            return false;
        }
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
            || address.isLinkLocalAddress() || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            int third = b[2] & 0xff;
            if (first == 0 || first == 10 || first == 127 || first >= 224) {
                return false;
            }
            if (first == 100 && (second & 0xc0) == 64) return false;       // 100.64.0.0/10 CGNAT
            if (first == 169 && second == 254) return false;                // 169.254.0.0/16 link-local / metadata
            if (first == 172 && (second & 0xf0) == 16) return false;        // 172.16.0.0/12
            if (first == 192 && second == 168) return false;                // 192.168.0.0/16
            if (first == 192 && second == 0 && third == 0) return false;    // 192.0.0.0/24 IETF protocol
            if (first == 192 && second == 0 && third == 2) return false;    // 192.0.2.0/24 TEST-NET-1
            if (first == 192 && second == 88 && third == 99) return false;  // 192.88.99.0/24 6to4 relay
            if (first == 198 && (second & 0xfe) == 18) return false;        // 198.18.0.0/15 benchmarking
            if (first == 198 && second == 51 && third == 100) return false; // 198.51.100.0/24 TEST-NET-2
            if (first == 203 && second == 0 && third == 113) return false;  // 203.0.113.0/24 TEST-NET-3
            return true;
        }
        if (b.length != 16) {
            return false;
        }
        // IPv6: 仅放行全球单播地址 2000::/3
        int b0 = b[0] & 0xff;
        int b1 = b[1] & 0xff;
        int b2 = b[2] & 0xff;
        int b3 = b[3] & 0xff;
        if ((b0 & 0xe0) != 0x20) {
            return false;
        }
        // 拦截 2002::/16 (6to4 tunneling，可内嵌私网 IPv4)
        if (b0 == 0x20 && b1 == 0x02) {
            return false;
        }
        if (b0 == 0x20 && b1 == 0x01) {
            // 2001::/32 Teredo
            if (b2 == 0x00 && b3 == 0x00) return false;
            // 2001:2::/48 benchmarking
            if (b2 == 0x00 && b3 == 0x02 && (b[4] & 0xff) == 0 && (b[5] & 0xff) == 0) return false;
            // 2001:10::/28 ORCHID
            if (b2 == 0x00 && (b3 & 0xf0) == 0x10) return false;
            // 2001:db8::/32 documentation
            if (b2 == 0x0d && b3 == 0xb8) return false;
        }
        return true;
    }

    /** 比较两个 URL 是否具有完全一致的 Origin（scheme + host + effectivePort），用于防掩码密钥重定向外泄。 */
    public static boolean sameOrigin(String urlA, String urlB) {
        if (urlA == null || urlB == null || urlA.isBlank() || urlB.isBlank()) {
            return false;
        }
        try {
            URI a = URI.create(urlA.trim());
            URI b = URI.create(urlB.trim());
            String schemeA = a.getScheme() == null ? "" : a.getScheme().toLowerCase(Locale.ROOT);
            String schemeB = b.getScheme() == null ? "" : b.getScheme().toLowerCase(Locale.ROOT);
            String hostA = a.getHost() == null ? "" : stripIpv6Brackets(a.getHost()).toLowerCase(Locale.ROOT);
            String hostB = b.getHost() == null ? "" : stripIpv6Brackets(b.getHost()).toLowerCase(Locale.ROOT);
            if (schemeA.isEmpty() || hostA.isEmpty() || !schemeA.equals(schemeB) || !hostA.equals(hostB)) {
                return false;
            }
            return effectivePort(a, schemeA) == effectivePort(b, schemeB);
        } catch (Exception e) {
            return false;
        }
    }

    private static int effectivePort(URI uri, String scheme) {
        int port = uri.getPort();
        if (port > 0) {
            return port;
        }
        if ("https".equals(scheme)) return 443;
        if ("http".equals(scheme)) return 80;
        return -1;
    }

    public static String stripIpv6Brackets(String host) {
        if (host != null && host.length() >= 2 && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    /** 有界读取响应体字节，超过 maxBytes 抛出 IOException 防止 OOM。 */
    public static byte[] readBoundedBytes(InputStream in, long maxBytes) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        try (BoundedInputStream bounded = new BoundedInputStream(in, maxBytes + 1);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            bounded.transferTo(out);
            if (out.size() > maxBytes) {
                throw new IOException("response.too.large");
            }
            return out.toByteArray();
        }
    }

    public static String readBoundedString(InputStream in, long maxBytes) throws IOException {
        return new String(readBoundedBytes(in, maxBytes), StandardCharsets.UTF_8);
    }

    /** 尽力把敏感凭据文件收紧为 POSIX 0600（非 POSIX 文件系统静默跳过）。 */
    public static void setOwnerOnlyPermissions(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.setPosixFilePermissions(path, OWNER_READ_WRITE);
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) {
        }
    }
}
