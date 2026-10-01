package com.lux032.musicautotagger.web;

import com.google.gson.*;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.core.ApplicationLifecycleManager;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.lang.reflect.Proxy;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ControlServletHealthTest {
    @Test void existingKeysAndHealthWithoutStackAreSerialized() throws Exception {
        ApplicationLifecycleManager lifecycle = new ApplicationLifecycleManager(MusicConfig.getInstance()) {
            @Override public boolean isMonitoringRunning() { return true; }
            @Override public boolean isMonitoringPaused() { return false; }
            @Override public Map<String, Object> getMonitoringHealth() {
                return Map.of("mainQueueSize", 2, "retryQueueSize", 1, "tasks", Map.of("consumer", Map.of("state", "FAULTED")),
                    "lastFault", Map.of("type", "java.lang.OutOfMemoryError", "message", "Task terminated unexpectedly"));
            }
        };
        StringWriter body = new StringWriter();
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class[]{HttpServletResponse.class}, (proxy, method, args) -> method.getName().equals("getWriter") ? new PrintWriter(body) : null);
        new ControlServlet(lifecycle).doGet(null, response);
        JsonObject json = JsonParser.parseString(body.toString()).getAsJsonObject();
        assertTrue(json.get("monitoringRunning").getAsBoolean());
        assertFalse(json.get("monitoringPaused").getAsBoolean());
        assertEquals(2, json.get("mainQueueSize").getAsInt());
        assertTrue(json.has("tasks")); assertTrue(json.has("lastFault"));
        assertFalse(body.toString().contains("stack"));
    }
}
