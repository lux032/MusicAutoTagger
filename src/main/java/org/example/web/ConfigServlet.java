package org.example.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.example.config.MusicConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConfigServlet extends HttpServlet {

    private static final String SESSION_CSRF_KEY = "csrfToken";
    private static final Logger log = LoggerFactory.getLogger(ConfigServlet.class);

    private final MusicConfig config;
    private final Gson gson;
    private final Path configPath;
    private final Map<String, String> propertyKeys;
    private final Set<String> absolutePathFields;
    private final Set<String> allowedLanguages;
    private final Set<String> allowedDbTypes;

    public ConfigServlet(MusicConfig config) {
        this.config = config;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.configPath = Paths.get("config.properties");
        this.propertyKeys = Map.ofEntries(
            Map.entry("monitorDirectory", "monitor.directory"),
            Map.entry("outputDirectory", "monitor.outputDirectory"),
            Map.entry("scanIntervalSeconds", "monitor.scanInterval"),
            Map.entry("musicBrainzApiUrl", "musicbrainz.apiUrl"),
            Map.entry("coverArtApiUrl", "musicbrainz.coverArtApiUrl"),
            Map.entry("userAgent", "musicbrainz.userAgent"),
            Map.entry("acoustIdApiKey", "acoustid.apiKey"),
            Map.entry("acoustIdApiUrl", "acoustid.apiUrl"),
            Map.entry("supportedFormats", "file.supportedFormats"),
            Map.entry("autoRename", "file.autoRename"),
            Map.entry("createBackup", "file.createBackup"),
            Map.entry("failedDirectory", "file.failedDirectory"),
            Map.entry("partialDirectory", "file.partialDirectory"),
            Map.entry("maxRetries", "file.maxRetries"),
            Map.entry("enableDetailedLogging", "logging.detailed"),
            Map.entry("processedFileLogPath", "logging.processedFileLogPath"),
            Map.entry("coverArtCacheDirectory", "cache.coverArtDirectory"),
            Map.entry("dbType", "db.type"),
            Map.entry("dbHost", "db.mysql.host"),
            Map.entry("dbPort", "db.mysql.port"),
            Map.entry("dbDatabase", "db.mysql.database"),
            Map.entry("dbUsername", "db.mysql.username"),
            Map.entry("dbPassword", "db.mysql.password"),
            Map.entry("dbMaxPoolSize", "db.mysql.pool.maxPoolSize"),
            Map.entry("dbMinIdle", "db.mysql.pool.minIdle"),
            Map.entry("dbConnectionTimeout", "db.mysql.pool.connectionTimeout"),
            Map.entry("proxyEnabled", "proxy.enabled"),
            Map.entry("proxyHost", "proxy.host"),
            Map.entry("proxyPort", "proxy.port"),
            Map.entry("proxyUsername", "proxy.username"),
            Map.entry("proxyPassword", "proxy.password"),
            Map.entry("language", "i18n.language"),
            Map.entry("exportLyricsToFile", "lyrics.exportToFile"),
            Map.entry("audioNormalizeEnabled", "audio.normalize.enabled"),
            Map.entry("audioNormalizeFfmpegPath", "audio.normalize.ffmpegPath"),
            Map.entry("cueSplitEnabled", "cue.split.enabled"),
            Map.entry("cueSplitOutputDir", "cue.split.outputDir"),
            Map.entry("releaseCountryPriority", "release.countryPriority")
        );
        this.absolutePathFields = Set.of(
            "monitorDirectory",
            "outputDirectory",
            "failedDirectory",
            "partialDirectory",
            "processedFileLogPath",
            "coverArtCacheDirectory",
            "cueSplitOutputDir"
        );
        this.allowedLanguages = Set.of("zh_CN", "en_US");
        this.allowedDbTypes = Set.of("file", "mysql");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> payload = new HashMap<>();
        payload.put("config", currentConfig());

        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().write(gson.toJson(payload));
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isCsrfValid(req)) {
            respondJson(resp, HttpServletResponse.SC_FORBIDDEN, Map.of("error", "csrf.invalid"));
            return;
        }

        Map<String, Object> body = readBodyAsMap(req);
        Map<String, Object> updates = new LinkedHashMap<>();
        Map<String, String> propertyUpdates = new LinkedHashMap<>();

        handleString(body, updates, propertyUpdates, "monitorDirectory", true);
        handleString(body, updates, propertyUpdates, "outputDirectory", true);
        handleInteger(body, updates, propertyUpdates, "scanIntervalSeconds");
        handleString(body, updates, propertyUpdates, "musicBrainzApiUrl", false);
        handleString(body, updates, propertyUpdates, "coverArtApiUrl", false);
        handleString(body, updates, propertyUpdates, "userAgent", false);
        handleString(body, updates, propertyUpdates, "acoustIdApiKey", false);
        handleString(body, updates, propertyUpdates, "acoustIdApiUrl", false);
        handleString(body, updates, propertyUpdates, "supportedFormats", false);
        handleBoolean(body, updates, propertyUpdates, "autoRename");
        handleBoolean(body, updates, propertyUpdates, "createBackup");
        handleString(body, updates, propertyUpdates, "failedDirectory", false);
        handleString(body, updates, propertyUpdates, "partialDirectory", false);
        handleInteger(body, updates, propertyUpdates, "maxRetries");
        handleBoolean(body, updates, propertyUpdates, "enableDetailedLogging");
        handleString(body, updates, propertyUpdates, "processedFileLogPath", false);
        handleString(body, updates, propertyUpdates, "coverArtCacheDirectory", false);
        handleString(body, updates, propertyUpdates, "dbType", false);
        handleString(body, updates, propertyUpdates, "dbHost", false);
        handleInteger(body, updates, propertyUpdates, "dbPort");
        handleString(body, updates, propertyUpdates, "dbDatabase", false);
        handleString(body, updates, propertyUpdates, "dbUsername", false);
        handleString(body, updates, propertyUpdates, "dbPassword", false);
        handleInteger(body, updates, propertyUpdates, "dbMaxPoolSize");
        handleInteger(body, updates, propertyUpdates, "dbMinIdle");
        handleLong(body, updates, propertyUpdates, "dbConnectionTimeout");
        handleBoolean(body, updates, propertyUpdates, "proxyEnabled");
        handleString(body, updates, propertyUpdates, "proxyHost", false);
        handleInteger(body, updates, propertyUpdates, "proxyPort");
        handleString(body, updates, propertyUpdates, "proxyUsername", false);
        handleString(body, updates, propertyUpdates, "proxyPassword", false);
        handleString(body, updates, propertyUpdates, "language", false);
        handleBoolean(body, updates, propertyUpdates, "exportLyricsToFile");
        handleBoolean(body, updates, propertyUpdates, "audioNormalizeEnabled");
        handleString(body, updates, propertyUpdates, "audioNormalizeFfmpegPath", false);
        handleBoolean(body, updates, propertyUpdates, "cueSplitEnabled");
        handleString(body, updates, propertyUpdates, "cueSplitOutputDir", false);
        handleString(body, updates, propertyUpdates, "releaseCountryPriority", false);

        if (updates.isEmpty()) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of("error", "no.updates"));
            return;
        }

        try {
            persistUpdates(propertyUpdates);
            applyUpdates(updates);
        } catch (IOException e) {
            log.error("Failed to save config", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "save.failed");
            if (isConfigDebugEnabled()) {
                error.put("detail", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            respondJson(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, error);
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("config", currentConfig());
        payload.put("updated", true);
        payload.put("requiresRestart", true);
        respondJson(resp, HttpServletResponse.SC_OK, payload);
    }

    private Map<String, Object> currentConfig() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("monitorDirectory", config.getMonitorDirectory());
        data.put("outputDirectory", config.getOutputDirectory());
        data.put("scanIntervalSeconds", config.getScanIntervalSeconds());
        data.put("musicBrainzApiUrl", config.getMusicBrainzApiUrl());
        data.put("coverArtApiUrl", config.getCoverArtApiUrl());
        data.put("userAgent", config.getUserAgent());
        data.put("acoustIdApiKey", config.getAcoustIdApiKey());
        data.put("acoustIdApiUrl", config.getAcoustIdApiUrl());
        data.put("supportedFormats", config.getSupportedFormats() == null ? null : String.join(",", config.getSupportedFormats()));
        data.put("autoRename", config.isAutoRename());
        data.put("createBackup", config.isCreateBackup());
        data.put("failedDirectory", config.getFailedDirectory());
        data.put("partialDirectory", config.getPartialDirectory());
        data.put("maxRetries", config.getMaxRetries());
        data.put("enableDetailedLogging", config.isEnableDetailedLogging());
        data.put("processedFileLogPath", config.getProcessedFileLogPath());
        data.put("coverArtCacheDirectory", config.getCoverArtCacheDirectory());
        data.put("dbType", config.getDbType());
        data.put("dbHost", config.getDbHost());
        data.put("dbPort", config.getDbPort());
        data.put("dbDatabase", config.getDbDatabase());
        data.put("dbUsername", config.getDbUsername());
        data.put("dbPassword", config.getDbPassword());
        data.put("dbMaxPoolSize", config.getDbMaxPoolSize());
        data.put("dbMinIdle", config.getDbMinIdle());
        data.put("dbConnectionTimeout", config.getDbConnectionTimeout());
        data.put("proxyEnabled", config.isProxyEnabled());
        data.put("proxyHost", config.getProxyHost());
        data.put("proxyPort", config.getProxyPort());
        data.put("proxyUsername", config.getProxyUsername());
        data.put("proxyPassword", config.getProxyPassword());
        data.put("language", config.getLanguage());
        data.put("exportLyricsToFile", config.isExportLyricsToFile());
        data.put("audioNormalizeEnabled", config.isAudioNormalizeEnabled());
        data.put("audioNormalizeFfmpegPath", config.getAudioNormalizeFfmpegPath());
        data.put("cueSplitEnabled", config.isCueSplitEnabled());
        data.put("cueSplitOutputDir", config.getCueSplitOutputDir());
        data.put("releaseCountryPriority", config.getReleaseCountryPriority() == null || config.getReleaseCountryPriority().isEmpty()
            ? null
            : String.join(",", config.getReleaseCountryPriority()));
        return data;
    }

    private void persistUpdates(Map<String, String> updates) throws IOException {
        java.util.Properties props = new java.util.Properties();
        if (Files.exists(configPath)) {
            try (FileInputStream fis = new FileInputStream(configPath.toFile())) {
                props.load(fis);
            }
        }

        for (Map.Entry<String, String> entry : updates.entrySet()) {
            if (entry.getValue() == null) {
                props.remove(entry.getKey());
            } else {
                props.setProperty(entry.getKey(), entry.getValue());
            }
        }
        try {
            writeConfigAtomically(props);
        } catch (IOException e) {
            log.warn("Atomic config save failed, attempting direct write", e);
            try {
                writeConfigDirectly(props);
            } catch (IOException directError) {
                directError.addSuppressed(e);
                throw directError;
            }
        }
    }

    private void writeConfigAtomically(java.util.Properties props) throws IOException {
        Path tempPath = configPath.resolveSibling("config.properties.tmp");
        try (FileOutputStream fos = new FileOutputStream(tempPath.toFile())) {
            props.store(fos, "Updated by web panel");
        }

        try {
            Files.move(tempPath, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(tempPath, configPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveError) {
                Files.copy(tempPath, configPath, StandardCopyOption.REPLACE_EXISTING);
                deleteTempQuietly(tempPath);
            }
        } catch (IOException moveError) {
            Files.copy(tempPath, configPath, StandardCopyOption.REPLACE_EXISTING);
            deleteTempQuietly(tempPath);
        }
    }

    private void writeConfigDirectly(java.util.Properties props) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(configPath.toFile())) {
            props.store(fos, "Updated by web panel");
        }
    }

    private void deleteTempQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("Failed to delete temp config file: {}", path);
        }
    }

    private boolean isConfigDebugEnabled() {
        String value = System.getenv("MTG_CONFIG_DEBUG");
        return value != null && value.equalsIgnoreCase("true");
    }

    private void applyUpdates(Map<String, Object> updates) {
        if (updates.containsKey("monitorDirectory")) {
            config.setMonitorDirectory((String) updates.get("monitorDirectory"));
        }
        if (updates.containsKey("outputDirectory")) {
            config.setOutputDirectory((String) updates.get("outputDirectory"));
        }
        if (updates.containsKey("scanIntervalSeconds")) {
            config.setScanIntervalSeconds((Integer) updates.get("scanIntervalSeconds"));
        }
        if (updates.containsKey("musicBrainzApiUrl")) {
            config.setMusicBrainzApiUrl((String) updates.get("musicBrainzApiUrl"));
        }
        if (updates.containsKey("coverArtApiUrl")) {
            config.setCoverArtApiUrl((String) updates.get("coverArtApiUrl"));
        }
        if (updates.containsKey("userAgent")) {
            config.setUserAgent((String) updates.get("userAgent"));
        }
        if (updates.containsKey("acoustIdApiKey")) {
            config.setAcoustIdApiKey((String) updates.get("acoustIdApiKey"));
        }
        if (updates.containsKey("acoustIdApiUrl")) {
            config.setAcoustIdApiUrl((String) updates.get("acoustIdApiUrl"));
        }
        if (updates.containsKey("supportedFormats")) {
            config.setSupportedFormats((String[]) updates.get("supportedFormats"));
        }
        if (updates.containsKey("autoRename")) {
            config.setAutoRename((Boolean) updates.get("autoRename"));
        }
        if (updates.containsKey("createBackup")) {
            config.setCreateBackup((Boolean) updates.get("createBackup"));
        }
        if (updates.containsKey("failedDirectory")) {
            config.setFailedDirectory((String) updates.get("failedDirectory"));
        }
        if (updates.containsKey("partialDirectory")) {
            config.setPartialDirectory((String) updates.get("partialDirectory"));
        }
        if (updates.containsKey("maxRetries")) {
            config.setMaxRetries((Integer) updates.get("maxRetries"));
        }
        if (updates.containsKey("enableDetailedLogging")) {
            config.setEnableDetailedLogging((Boolean) updates.get("enableDetailedLogging"));
        }
        if (updates.containsKey("processedFileLogPath")) {
            config.setProcessedFileLogPath((String) updates.get("processedFileLogPath"));
        }
        if (updates.containsKey("coverArtCacheDirectory")) {
            config.setCoverArtCacheDirectory((String) updates.get("coverArtCacheDirectory"));
        }
        if (updates.containsKey("dbType")) {
            config.setDbType((String) updates.get("dbType"));
        }
        if (updates.containsKey("dbHost")) {
            config.setDbHost((String) updates.get("dbHost"));
        }
        if (updates.containsKey("dbPort")) {
            config.setDbPort((Integer) updates.get("dbPort"));
        }
        if (updates.containsKey("dbDatabase")) {
            config.setDbDatabase((String) updates.get("dbDatabase"));
        }
        if (updates.containsKey("dbUsername")) {
            config.setDbUsername((String) updates.get("dbUsername"));
        }
        if (updates.containsKey("dbPassword")) {
            config.setDbPassword((String) updates.get("dbPassword"));
        }
        if (updates.containsKey("dbMaxPoolSize")) {
            config.setDbMaxPoolSize((Integer) updates.get("dbMaxPoolSize"));
        }
        if (updates.containsKey("dbMinIdle")) {
            config.setDbMinIdle((Integer) updates.get("dbMinIdle"));
        }
        if (updates.containsKey("dbConnectionTimeout")) {
            config.setDbConnectionTimeout((Long) updates.get("dbConnectionTimeout"));
        }
        if (updates.containsKey("proxyEnabled")) {
            config.setProxyEnabled((Boolean) updates.get("proxyEnabled"));
        }
        if (updates.containsKey("proxyHost")) {
            config.setProxyHost((String) updates.get("proxyHost"));
        }
        if (updates.containsKey("proxyPort")) {
            config.setProxyPort((Integer) updates.get("proxyPort"));
        }
        if (updates.containsKey("proxyUsername")) {
            config.setProxyUsername((String) updates.get("proxyUsername"));
        }
        if (updates.containsKey("proxyPassword")) {
            config.setProxyPassword((String) updates.get("proxyPassword"));
        }
        if (updates.containsKey("language")) {
            config.setLanguage((String) updates.get("language"));
        }
        if (updates.containsKey("exportLyricsToFile")) {
            config.setExportLyricsToFile((Boolean) updates.get("exportLyricsToFile"));
        }
        if (updates.containsKey("audioNormalizeEnabled")) {
            config.setAudioNormalizeEnabled((Boolean) updates.get("audioNormalizeEnabled"));
        }
        if (updates.containsKey("audioNormalizeFfmpegPath")) {
            config.setAudioNormalizeFfmpegPath((String) updates.get("audioNormalizeFfmpegPath"));
        }
        if (updates.containsKey("cueSplitEnabled")) {
            config.setCueSplitEnabled((Boolean) updates.get("cueSplitEnabled"));
        }
        if (updates.containsKey("cueSplitOutputDir")) {
            config.setCueSplitOutputDir((String) updates.get("cueSplitOutputDir"));
        }
        if (updates.containsKey("releaseCountryPriority")) {
            @SuppressWarnings("unchecked")
            List<String> priorities = (List<String>) updates.get("releaseCountryPriority");
            config.setReleaseCountryPriority(priorities);
        }
    }

    private void handleString(Map<String, Object> body, Map<String, Object> updates,
                              Map<String, String> propertyUpdates, String field, boolean required) throws IOException {
        if (!body.containsKey(field)) {
            return;
        }

        String rawValue = asString(body.get(field));
        String trimmedValue = rawValue == null ? null : rawValue.trim();
        if (trimmedValue != null && trimmedValue.isEmpty()) {
            trimmedValue = null;
        }

        if (required && trimmedValue == null) {
            throwValidation("required.field", field);
        }

        if (trimmedValue != null && absolutePathFields.contains(field)) {
            if (!Paths.get(trimmedValue).isAbsolute()) {
                throwValidation("path.must.be.absolute", field);
            }
        }

        if ("language".equals(field) && trimmedValue != null && !allowedLanguages.contains(trimmedValue)) {
            throwValidation("language.invalid", field);
        }

        if ("dbType".equals(field) && trimmedValue != null && !allowedDbTypes.contains(trimmedValue)) {
            throwValidation("db.type.invalid", field);
        }

        if ("supportedFormats".equals(field)) {
            if (trimmedValue == null) {
                throwValidation("supported.formats.invalid", field);
            }
            String[] formats = Arrays.stream(trimmedValue.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
            if (formats.length == 0) {
                throwValidation("supported.formats.invalid", field);
            }
            updates.put(field, formats);
            setPropertyUpdate(propertyUpdates, field, String.join(",", formats));
            return;
        }

        if ("releaseCountryPriority".equals(field)) {
            if (trimmedValue == null || trimmedValue.isEmpty()) {
                updates.put(field, new ArrayList<String>());
                setPropertyUpdate(propertyUpdates, field, null);
                return;
            }
            List<String> priorities = Arrays.stream(trimmedValue.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
            updates.put(field, priorities);
            setPropertyUpdate(propertyUpdates, field, String.join(",", priorities));
            return;
        }

        updates.put(field, trimmedValue);
        setPropertyUpdate(propertyUpdates, field, trimmedValue);
    }

    private void handleBoolean(Map<String, Object> body, Map<String, Object> updates,
                               Map<String, String> propertyUpdates, String field) {
        if (!body.containsKey(field)) {
            return;
        }
        Boolean value = asBoolean(body.get(field));
        if (value == null) {
            return;
        }
        updates.put(field, value);
        setPropertyUpdate(propertyUpdates, field, String.valueOf(value));
    }

    private void handleInteger(Map<String, Object> body, Map<String, Object> updates,
                               Map<String, String> propertyUpdates, String field) throws IOException {
        if (!body.containsKey(field)) {
            return;
        }
        Integer value = asInteger(body.get(field));
        if (value == null) {
            return;
        }
        updates.put(field, value);
        setPropertyUpdate(propertyUpdates, field, String.valueOf(value));
    }

    private void handleLong(Map<String, Object> body, Map<String, Object> updates,
                            Map<String, String> propertyUpdates, String field) throws IOException {
        if (!body.containsKey(field)) {
            return;
        }
        Long value = asLong(body.get(field));
        if (value == null) {
            return;
        }
        updates.put(field, value);
        setPropertyUpdate(propertyUpdates, field, String.valueOf(value));
    }

    private void setPropertyUpdate(Map<String, String> updates, String field, String value) {
        String propertyKey = propertyKeys.get(field);
        if (propertyKey == null) {
            return;
        }
        updates.put(propertyKey, value);
    }

    private void throwValidation(String error, String field) throws IOException {
        throw new ValidationException(error, field);
    }

    private boolean isCsrfValid(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) {
            return false;
        }
        String token = req.getHeader("X-CSRF-Token");
        String sessionToken = (String) session.getAttribute(SESSION_CSRF_KEY);
        return sessionToken != null && sessionToken.equals(token);
    }

    private Map<String, Object> readBodyAsMap(HttpServletRequest req) throws IOException {
        try (BufferedReader reader = req.getReader()) {
            Map<String, Object> data = gson.fromJson(reader, Map.class);
            return data == null ? new HashMap<>() : data;
        }
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private Boolean asBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Integer asInteger(Object value) throws IOException {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throwValidation("number.invalid", null);
            return null;
        }
    }

    private Long asLong(Object value) throws IOException {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throwValidation("number.invalid", null);
            return null;
        }
    }

    private void respondJson(HttpServletResponse resp, int status, Map<String, Object> payload) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(status);
        resp.getWriter().write(gson.toJson(payload));
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
        throws IOException, jakarta.servlet.ServletException {
        try {
            super.service(req, resp);
        } catch (ValidationException e) {
            respondJson(resp, HttpServletResponse.SC_BAD_REQUEST, Map.of(
                "error", e.error,
                "field", e.field == null ? "" : e.field
            ));
        }
    }

    private static class ValidationException extends IOException {
        private final String error;
        private final String field;

        private ValidationException(String error, String field) {
            this.error = error;
            this.field = field;
        }
    }
}
