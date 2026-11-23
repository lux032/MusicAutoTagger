package com.lux032.musicautotagger.util;

import com.lux032.musicautotagger.config.MusicConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 统一的出站 HTTP 客户端工厂（收敛 java.net.http.HttpClient 与 Apache HttpClient 5 构造逻辑）。
 *
 * 统管代理配置、仅对 {@link Authenticator.RequestorType#PROXY} 生效的代理认证、连接超时、
 * 过期/空闲连接驱逐以及禁止默认自动重定向等安全默认值。
 */
@Slf4j
public final class HttpClientFactory {

    private static final Duration DEFAULT_JDK_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final TimeValue DEFAULT_IDLE_EVICTION = TimeValue.ofMinutes(1);

    private HttpClientFactory() {
    }

    public static boolean isProxyConfigured(MusicConfig config) {
        return config != null
            && config.isProxyEnabled()
            && config.getProxyHost() != null
            && !config.getProxyHost().isBlank()
            && config.getProxyPort() > 0;
    }

    /** 用于代理客户端热切换的轻量签名，避免凭据/主机变更后复用旧代理连接。 */
    public static String proxySignature(MusicConfig config) {
        if (!isProxyConfigured(config)) {
            return "DIRECT";
        }
        String host = config.getProxyHost().trim();
        int port = config.getProxyPort();
        String user = config.getProxyUsername() == null ? "" : config.getProxyUsername();
        String pass = config.getProxyPassword() == null ? "" : config.getProxyPassword();
        return host + ":" + port + ":" + user + ":" + pass.hashCode();
    }

    /** 构造带有统一超时、代理及禁止重定向策略的 JDK HttpClient.Builder。 */
    public static HttpClient.Builder jdkHttpClientBuilder(MusicConfig config) {
        return jdkHttpClientBuilder(config, DEFAULT_JDK_CONNECT_TIMEOUT);
    }

    public static HttpClient.Builder jdkHttpClientBuilder(MusicConfig config, Duration connectTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(connectTimeout == null ? DEFAULT_JDK_CONNECT_TIMEOUT : connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER);

        if (isProxyConfigured(config)) {
            String host = config.getProxyHost().trim();
            int port = config.getProxyPort();
            builder.proxy(ProxySelector.of(new InetSocketAddress(host, port)));
            if (config.getProxyUsername() != null && !config.getProxyUsername().isEmpty()) {
                final String username = config.getProxyUsername();
                final char[] password = (config.getProxyPassword() == null ? "" : config.getProxyPassword()).toCharArray();
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        // 严格限定仅向代理服务器提供代理凭据，防止目标站返回 401 时窃取代理账密
                        if (getRequestorType() != RequestorType.PROXY) {
                            return null;
                        }
                        return new PasswordAuthentication(username, password.clone());
                    }
                });
            }
        }
        return builder;
    }

    public static HttpClient buildJdkHttpClient(MusicConfig config) {
        return jdkHttpClientBuilder(config).build();
    }

    /** 构造带有统一超时、连接驱逐与代理（含代理认证）的 Apache HttpClientBuilder。 */
    public static HttpClientBuilder apacheHttpClientBuilder(MusicConfig config,
                                                            Timeout connectionRequestTimeout,
                                                            Timeout connectTimeout,
                                                            Timeout responseTimeout) {
        RequestConfig.Builder rc = RequestConfig.custom();
        if (connectionRequestTimeout != null) {
            rc.setConnectionRequestTimeout(connectionRequestTimeout);
        }
        if (connectTimeout != null) {
            rc.setConnectTimeout(connectTimeout);
        }
        if (responseTimeout != null) {
            rc.setResponseTimeout(responseTimeout);
        }

        HttpClientBuilder builder = HttpClients.custom()
            .setDefaultRequestConfig(rc.build())
            .evictExpiredConnections()
            .evictIdleConnections(DEFAULT_IDLE_EVICTION);

        applyApacheProxy(builder, config);
        return builder;
    }

    public static void applyApacheProxy(HttpClientBuilder builder, MusicConfig config) {
        if (builder == null || !isProxyConfigured(config)) {
            return;
        }
        String host = config.getProxyHost().trim();
        int port = config.getProxyPort();
        builder.setProxy(new HttpHost(host, port));
        String user = config.getProxyUsername();
        if (user != null && !user.isEmpty()) {
            String pass = config.getProxyPassword() == null ? "" : config.getProxyPassword();
            BasicCredentialsProvider creds = new BasicCredentialsProvider();
            creds.setCredentials(new AuthScope(host, port), new UsernamePasswordCredentials(user, pass.toCharArray()));
            builder.setDefaultCredentialsProvider(creds);
        }
    }

    public static CloseableHttpClient buildApacheHttpClient(MusicConfig config,
                                                            Timeout connectionRequestTimeout,
                                                            Timeout connectTimeout,
                                                            Timeout responseTimeout) {
        return apacheHttpClientBuilder(config, connectionRequestTimeout, connectTimeout, responseTimeout).build();
    }
}
