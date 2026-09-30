package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ProcessedRecord;
import com.lux032.musicautotagger.service.DatabaseService;
import com.lux032.musicautotagger.service.ProcessedRecordQuery;
import com.lux032.musicautotagger.service.ProcessedRecordService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 处理记录管理 API。
 *
 *   GET  /api/records/list        分页查询（q / status / album / sort / offset / limit）
 *   GET  /api/records/export      按当前筛选导出 CSV
 *   POST /api/records/preview     批量操作预览   body: {paths:[...]} 或 {filter:{...}}
 *   POST /api/records/forget      仅删除记录
 *   POST /api/records/reidentify  删除记录并重新加入处理队列
 */
@Slf4j
public class RecordsServlet extends HttpServlet {

    private static final String SESSION_CSRF_KEY = "csrfToken";
    private static final int EXPORT_LIMIT = 100_000;

    private final ProcessedRecordService service;
    private final MusicConfig config;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public RecordsServlet(ProcessedRecordService service, MusicConfig config) {
        this.service = service;
        this.config = config;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String action = action(req);
        try {
            if ("list".equals(action)) {
                ProcessedRecordQuery query = queryFromParams(req);
                ProcessedRecordQuery.Page page = service.list(query);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("items", page.items);
                body.put("total", page.total);
                body.put("counts", page.counts);
                body.put("offset", page.offset);
                body.put("limit", page.limit);
                body.put("storage", storageName());
                body.put("monitoringActive", service.isMonitoringActive());
                body.put("monitoringPaused", service.isMonitoringPaused());
                respond(resp, 200, body);
            } else if ("export".equals(action)) {
                export(req, resp);
            } else {
                respond(resp, 404, Map.of("error", "unknown.action"));
            }
        } catch (RuntimeException e) {
            log.error("处理记录查询失败", e);
            respond(resp, 500, Map.of("error", "records.query.failed", "message", String.valueOf(e.getMessage())));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isCsrfValid(req)) {
            respond(resp, 403, Map.of("error", "csrf.invalid"));
            return;
        }
        String action = action(req);
        if (!"preview".equals(action) && !"forget".equals(action) && !"reidentify".equals(action)) {
            respond(resp, 404, Map.of("error", "unknown.action"));
            return;
        }

        Map<String, Object> body = readBody(req);
        List<String> paths = stringList(body.get("paths"));
        ProcessedRecordQuery filter = body.get("filter") instanceof Map<?, ?> m ? queryFromMap(m) : null;
        if (paths.isEmpty() && filter == null) {
            respond(resp, 400, Map.of("error", "records.no.target"));
            return;
        }

        try {
            List<ProcessedRecord> targets = service.resolveTargets(paths, filter);
            Map<String, Object> result;
            switch (action) {
                case "preview": result = service.preview(targets); break;
                case "forget": result = service.forget(targets); break;
                default: result = service.reidentify(targets); break;
            }
            Map<String, Object> out = new LinkedHashMap<>(result);
            out.put("success", true);
            respond(resp, 200, out);
        } catch (RuntimeException e) {
            log.error("处理记录操作失败: {}", action, e);
            respond(resp, 500, Map.of("error", "records.operation.failed", "message", String.valueOf(e.getMessage())));
        }
    }

    private void export(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ProcessedRecordQuery query = queryFromParams(req);
        List<ProcessedRecord> records = service.export(query, EXPORT_LIMIT);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        resp.setStatus(200);
        resp.setContentType("text/csv; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"processed-records-" + stamp + ".csv\"");
        PrintWriter w = resp.getWriter();
        w.write('\uFEFF'); // BOM：让 Excel 以 UTF-8 打开，中文/日文不乱码
        w.write(csvLine("status", "processed_time", "artist", "title", "album", "recording_id",
            "release_group_id", "file_path", "target_file_path"));
        for (ProcessedRecord r : records) {
            w.write(csvLine(r.status == null ? "" : r.status.name(), r.processedTime, r.artist, r.title, r.album,
                r.recordingId, r.releaseGroupId, r.filePath, r.targetFilePath));
        }
        w.flush();
    }

    private static String csvLine(String... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            String v = values[i] == null ? "" : values[i];
            // 防 CSV 公式注入：以 = + - @ 开头的单元格在 Excel 中会被当成公式
            if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) v = "'" + v;
            if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
                v = "\"" + v.replace("\"", "\"\"") + "\"";
            }
            sb.append(v);
        }
        return sb.append("\r\n").toString();
    }

    private String storageName() {
        String type = DatabaseService.normalizeType(config.getDbType());
        switch (type) {
            case DatabaseService.TYPE_SQLITE: return "SQLite";
            case DatabaseService.TYPE_MYSQL: return "MySQL";
            default: return "File";
        }
    }

    // ---------- 参数解析 ----------

    private ProcessedRecordQuery queryFromParams(HttpServletRequest req) {
        Map<String, Object> map = new HashMap<>();
        for (String key : new String[]{"q", "status", "album", "sort", "offset", "limit"}) {
            String v = req.getParameter(key);
            if (v != null) map.put(key, v);
        }
        return queryFromMap(map);
    }

    private ProcessedRecordQuery queryFromMap(Map<?, ?> map) {
        ProcessedRecordQuery q = new ProcessedRecordQuery();
        q.keyword = str(map.get("q"));
        q.album = str(map.get("album"));
        q.sort = ProcessedRecordQuery.Sort.parse(str(map.get("sort")));
        String status = str(map.get("status"));
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            try {
                q.status = ProcessedRecord.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                q.status = null;
            }
        }
        q.offset = intValue(map.get("offset"), 0);
        q.limit = intValue(map.get("limit"), 50);
        return q;
    }

    private static int intValue(Object value, int fallback) {
        if (value == null) return fallback;
        try {
            return (int) Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) if (o != null) result.add(String.valueOf(o));
        }
        return result;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String action(HttpServletRequest req) {
        String path = req.getPathInfo();
        return path == null || path.length() <= 1 ? "" : path.substring(1).split("/")[0];
    }

    private boolean isCsrfValid(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        return session != null && session.getAttribute(SESSION_CSRF_KEY) != null
            && session.getAttribute(SESSION_CSRF_KEY).equals(req.getHeader("X-CSRF-Token"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readBody(HttpServletRequest req) {
        try (BufferedReader reader = req.getReader()) {
            Map<String, Object> data = gson.fromJson(reader, Map.class);
            return data == null ? new HashMap<>() : data;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private void respond(HttpServletResponse resp, int status, Map<String, Object> body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write(gson.toJson(body));
    }
}
