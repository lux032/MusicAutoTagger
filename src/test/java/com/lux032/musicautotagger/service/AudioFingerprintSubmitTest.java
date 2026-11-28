package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AudioFingerprintSubmitTest {

    @Test
    void submitsFingerprintsInFormEncodedPostAndRejectsMissingUserKeyOrError() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> responseBody = new AtomicReference<>("{\"status\":\"ok\",\"submissions\":[{\"id\":\"1001\",\"status\":\"pending\"}]}");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/submit", exchange -> {
            byte[] reqBytes = exchange.getRequestBody().readAllBytes();
            capturedBody.set(new String(reqBytes, StandardCharsets.UTF_8));
            byte[] out = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            java.lang.reflect.Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            MusicConfig config = ctor.newInstance();
            config.setAcoustIdApiKey("app-client-key");
            config.setAcoustIdSubmitApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v2/submit");
            // 生产模式必须拒绝非官方 HTTP/内网端点（防凭据外泄与 SSRF）
            config.setAcoustIdUserApiKey("user-submit-secret");
            AudioFingerprintService prodService = new AudioFingerprintService(config);
            List<AudioFingerprintService.AcoustIdSubmissionItem> batch = List.of(
                new AudioFingerprintService.AcoustIdSubmissionItem(195, "AQABfp1", "77777777-7777-4777-8777-777777777771"),
                new AudioFingerprintService.AcoustIdSubmissionItem(210, "AQABfp2", "77777777-7777-4777-8777-777777777772")
            );
            AudioFingerprintService.AcoustIdSubmitException blockedEndpoint = assertThrows(
                AudioFingerprintService.AcoustIdSubmitException.class, () -> prodService.submitFingerprints(batch));
            assertEquals("acoustid.submit.endpoint.invalid", blockedEndpoint.getErrorCode());

            // 显式测试端点模式验证协议编码、缺失 user key 拒绝与错误脱敏
            config.setAcoustIdUserApiKey(null);
            AudioFingerprintService service = new AudioFingerprintService(config, true);

            // 缺少 user key 拒绝提交
            IllegalStateException noUser = assertThrows(IllegalStateException.class, () -> service.submitFingerprints(batch));
            assertEquals("acoustid.user.key.missing", noUser.getMessage());

            // 补齐 user key 后成功提交
            config.setAcoustIdUserApiKey("user-submit-secret");
            AudioFingerprintService.AcoustIdSubmitResult ok = service.submitFingerprints(batch);
            assertTrue(ok.ok());
            assertEquals(2, ok.submittedCount());
            assertEquals(List.of("1001"), ok.submissionIds());

            Map<String, String> form = parseForm(capturedBody.get());
            assertEquals("app-client-key", form.get("client"));
            assertEquals("user-submit-secret", form.get("user"));
            assertEquals("195", form.get("duration.0"));
            assertEquals("AQABfp1", form.get("fingerprint.0"));
            assertEquals("77777777-7777-4777-8777-777777777771", form.get("mbid.0"));
            assertEquals("210", form.get("duration.1"));
            assertEquals("77777777-7777-4777-8777-777777777772", form.get("mbid.1"));

            // 远端返回 status=error 时仅映射固定安全错误码，绝不把远端可能回显的密钥文本带出
            responseBody.set("{\"status\":\"error\",\"error\":{\"code\":6,\"message\":\"invalid user API key: user-submit-secret\"}}");
            AudioFingerprintService.AcoustIdSubmitException apiErr = assertThrows(
                AudioFingerprintService.AcoustIdSubmitException.class, () -> service.submitFingerprints(batch));
            assertEquals("acoustid.error.invalid.user.key", apiErr.getErrorCode());
            assertFalse(apiErr.getMessage().contains("user-submit-secret"));
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, String> parseForm(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isBlank()) return map;
        for (String pair : raw.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                map.put(
                    URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }
}
