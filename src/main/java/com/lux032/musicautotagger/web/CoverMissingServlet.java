package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.CoverCandidateView;
import com.lux032.musicautotagger.service.CoverMissingService;
import com.lux032.musicautotagger.service.DuplicateArchiveException;
import com.lux032.musicautotagger.service.OnlineSearchQueueService;
import com.lux032.musicautotagger.service.RecoveryService;
import com.lux032.musicautotagger.service.ReviewQueueService;
import com.lux032.musicautotagger.service.ReviewResolutionService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.input.BoundedInputStream;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「缺少封面」专用 API（/api/covers/*）。
 *
 *   GET  /api/covers/stats                     统计与状态（侧栏角标 / 概览页提示）
 *   GET  /api/covers/list?status=...&q=...     缺封面列表
 *   GET  /api/covers/item?id=...               条目详情（含候选封面）
 *   GET  /api/covers/scan/status               手动库扫描进度
 *   POST /api/covers/search                    {"id":"...","customQuery":"..."}
 *   POST /api/covers/batch-search              {"ids":[...]}（可选，空则扫全部未搜项）
 *   POST /api/covers/upload?id=...             原始图片二进制体 + 可选 X-File-Name 头
 *   POST /api/covers/fetch-url                 {"id":"...","url":"https://..."}
 *   POST /api/covers/apply                     {"id":"...","selectedCoverSha256":"...","duplicatePolicy":"ASK"}
 *   POST /api/covers/confirm-no-cover          {"id":"...","duplicatePolicy":"ASK"}
 *   POST /api/covers/reject                    {"id":"...","note":"..."}
 *   POST /api/covers/unreject                  {"id":"..."}
 *   POST /api/covers/scan/start                手动启动输出音乐库缺封面扫描
 *   POST /api/covers/scan/cancel               取消正在进行的扫描
 */
@Slf4j
public class CoverMissingServlet extends HttpServlet {

    private static final String SESSION_CSRF_KEY = "csrfToken";
    private static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024;
    private static final Set<ReviewItem.Kind> COVER_ONLY = Set.of(ReviewItem.Kind.COVER_MISSING);

    private final MusicConfig config;
    private final ReviewQueueService reviewQueue;
    private final CoverMissingService coverMissingService;
    private final OnlineSearchQueueService searchQueue;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public CoverMissingServlet(MusicConfig config,
                               ReviewQueueService reviewQueue,
                               CoverMissingService coverMissingService,
                               OnlineSearchQueueService searchQueue) {
        this.config = config;
        this.reviewQueue = reviewQueue;
        this.coverMissingService = coverMissingService;
        this.searchQueue = searchQueue;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = normalizedPath(req);
        try {
            switch (path) {
                case "stats":
                    Map<String, Object> stats = new LinkedHashMap<>();
                    stats.put("pending", reviewQueue.countPendingMissingCover());
                    stats.put("rejected", reviewQueue.countRejectedMissingCover());
                    stats.put("total", reviewQueue.listByKinds(null, COVER_ONLY).size());
                    stats.put("requireReview", config.isCoverMissingRequireReview());
                    stats.put("autoOnlineSearch", config.isCoverMissingAutoOnlineSearch());
                    stats.put("aiSearchAvailable", coverMissingService.isAiSearchAvailable());
                    stats.put("latest", latestPending(2));
                    stats.put("scan", coverMissingService.getScanStatus());
                    respond(resp, 200, stats);
                    return;
                case "list":
                    handleList(req, resp);
                    return;
                case "item":
                    handleItem(req, resp);
                    return;
                case "scan/status":
                    respond(resp, 200, coverMissingService.getScanStatus());
                    return;
                default:
                    respond(resp, 404, Map.of("error", "unknown.action"));
            }
        } catch (Exception e) {
            log.error("缺少封面查询失败", e);
            respond(resp, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isCsrfValid(req)) {
            respond(resp, 403, Map.of("error", "csrf.invalid"));
            return;
        }
        String path = normalizedPath(req);
        try {
            switch (path) {
                case "scan/start": {
                    if (coverMissingService.startLibraryScan()) {
                        respond(resp, 200, Map.of("success", true, "scan", coverMissingService.getScanStatus()));
                    } else {
                        respond(resp, 409, Map.of("error", "cover.scan.already.running",
                            "scan", coverMissingService.getScanStatus()));
                    }
                    return;
                }
                case "scan/cancel": {
                    coverMissingService.cancelLibraryScan();
                    respond(resp, 200, Map.of("success", true, "scan", coverMissingService.getScanStatus()));
                    return;
                }
                case "upload": {
                    handleUpload(req, resp);
                    return;
                }
                case "batch-search": {
                    if (searchQueue == null) {
                        respond(resp, 501, Map.of("error", "cover.search.unavailable"));
                        return;
                    }
                    Map<String, Object> body = readBody(req);
                    Map<String, Object> result = searchQueue.enqueueBatchForMissingCovers(strList(body.get("ids")));
                    Map<String, Object> out = new LinkedHashMap<>(result);
                    out.put("success", true);
                    respond(resp, 202, out);
                    return;
                }
                default:
                    break;
            }

            Map<String, Object> body = readBody(req);
            String id = str(body.get("id"));
            if (id == null || id.isEmpty()) {
                respond(resp, 400, Map.of("error", "missing.id"));
                return;
            }

            switch (path) {
                case "search": {
                    if (searchQueue == null) {
                        respond(resp, 501, Map.of("error", "cover.search.unavailable"));
                        return;
                    }
                    OnlineSearchQueueService.EnqueueResult res =
                        searchQueue.enqueueMissingCover(id, str(body.get("customQuery")));
                    if (res.alreadyActive()) {
                        respond(resp, 409, Map.of("error", "recovery.already.running", "task", res.task()));
                        return;
                    }
                    respond(resp, 202, Map.of("success", true, "queued", true, "task", res.task()));
                    return;
                }
                case "fetch-url": {
                    CoverCandidateView view = coverMissingService.fetchCoverByUrl(id, str(body.get("url")));
                    ReviewItem item = reviewQueue.get(id);
                    respond(resp, 200, Map.of("success", true, "candidate", view, "item", toDetail(item)));
                    return;
                }
                case "apply": {
                    ReviewItem item = coverMissingService.applyCover(
                        id, str(body.get("selectedCoverSha256")), duplicatePolicy(str(body.get("duplicatePolicy"))));
                    respond(resp, 200, Map.of("success", true, "item", toDetail(item)));
                    return;
                }
                case "confirm-no-cover": {
                    ReviewItem item = coverMissingService.confirmNoCover(
                        id, duplicatePolicy(str(body.get("duplicatePolicy"))));
                    respond(resp, 200, Map.of("success", true, "item", toDetail(item)));
                    return;
                }
                case "reject": {
                    ReviewItem item = coverMissingService.reject(id, str(body.get("note")));
                    respond(resp, 200, Map.of("success", true, "item", toDetail(item)));
                    return;
                }
                case "unreject": {
                    ReviewItem item = coverMissingService.unreject(id);
                    respond(resp, 200, Map.of("success", true, "item", toDetail(item)));
                    return;
                }
                default:
                    respond(resp, 404, Map.of("error", "unknown.action"));
            }
        } catch (ReviewResolutionService.ResolutionException e) {
            respond(resp, e.getHttpStatus(), Map.of("error", e.getMessage()));
        } catch (DuplicateArchiveException e) {
            respond(resp, 409, Map.of("error", e.getMessage(), "duplicates", e.getDuplicates()));
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "cover.operation.failed" : e.getMessage();
            int status = switch (msg) {
                case "item.not.found" -> 404;
                case "item.already.resolved", "recovery.already.running" -> 409;
                case "cover.search.unavailable" -> 503;
                default -> 400;
            };
            respond(resp, status, Map.of("error", msg));
        } catch (Exception e) {
            log.error("缺少封面操作失败: {}", path, e);
            respond(resp, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private void handleUpload(HttpServletRequest req, HttpServletResponse resp)
            throws IOException, ReviewResolutionService.ResolutionException {
        String id = trimToNull(req.getParameter("id"));
        if (id == null) {
            respond(resp, 400, Map.of("error", "missing.id"));
            return;
        }
        String rawName = req.getHeader("X-File-Name");
        if (rawName != null) {
            try {
                rawName = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
        long declared = req.getContentLengthLong();
        if (declared > MAX_UPLOAD_BYTES) {
            respond(resp, 413, Map.of("error", "cover.upload.too.large"));
            return;
        }
        byte[] data;
        try (BoundedInputStream in = new BoundedInputStream(req.getInputStream(), MAX_UPLOAD_BYTES + 1);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            if (out.size() > MAX_UPLOAD_BYTES) {
                respond(resp, 413, Map.of("error", "cover.upload.too.large"));
                return;
            }
            data = out.toByteArray();
        }
        CoverCandidateView view = coverMissingService.uploadCover(id, data, rawName);
        ReviewItem item = reviewQueue.get(id);
        respond(resp, 200, Map.of("success", true, "candidate", view, "item", toDetail(item)));
    }

    private void handleList(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ReviewItem.Status filter = null;
        String statusParam = req.getParameter("status");
        if (statusParam != null && !statusParam.isEmpty() && !"ALL".equalsIgnoreCase(statusParam)) {
            try {
                filter = ReviewItem.Status.valueOf(statusParam.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                respond(resp, 400, Map.of("error", "invalid.status"));
                return;
            }
        }
        String query = req.getParameter("q");
        boolean searching = query != null && !query.trim().isEmpty();
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (ReviewItem item : reviewQueue.listByKinds(filter, COVER_ONLY)) {
            String hit = ReviewQueueService.matchQuery(item, query);
            if (hit == null) continue;
            Map<String, Object> summary = toSummary(item);
            if (searching && !hit.isEmpty()) summary.put("matchedFile", hit);
            summaries.add(summary);
        }
        respond(resp, 200, Map.of(
            "items", summaries,
            "pending", reviewQueue.countPendingMissingCover(),
            "aiSearchAvailable", coverMissingService.isAiSearchAvailable()
        ));
    }

    private void handleItem(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String id = trimToNull(req.getParameter("id"));
        if (id == null) {
            respond(resp, 400, Map.of("error", "missing.id"));
            return;
        }
        ReviewItem item = reviewQueue.get(id);
        if (item == null || item.effectiveKind() != ReviewItem.Kind.COVER_MISSING) {
            respond(resp, 404, Map.of("error", "item.not.found"));
            return;
        }
        respond(resp, 200, Map.of("item", toDetail(item)));
    }

    private List<Map<String, Object>> latestPending(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReviewItem item : reviewQueue.listByKinds(ReviewItem.Status.PENDING_REVIEW, COVER_ONLY)) {
            if (out.size() >= limit) break;
            out.add(toSummary(item));
        }
        return out;
    }

    private Map<String, Object> toSummary(ReviewItem item) {
        ReviewItem.MissingCover mc = item.getMissingCover();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.getId());
        m.put("kind", item.effectiveKind().name());
        m.put("folderName", item.getFolderName());
        m.put("folderPath", item.getFolderPath());
        m.put("status", item.getStatus().name());
        m.put("createdAt", item.getCreatedAt());
        m.put("updatedAt", item.getUpdatedAt());
        m.put("reason", item.getReason());
        m.put("fileCount", item.getFiles() == null ? 0 : item.getFiles().size());
        m.put("albumTitle", mc != null && mc.getAlbumTitle() != null ? mc.getAlbumTitle() : item.getSynthesizedAlbumTitle());
        m.put("albumArtist", mc != null && mc.getAlbumArtist() != null ? mc.getAlbumArtist() : item.getSynthesizedAlbumArtist());
        m.put("mode", mc != null && mc.getMode() != null ? mc.getMode().name() : "STAGED_IMPORT");
        m.put("sourcePipeline", mc != null ? mc.getSourcePipeline() : null);
        m.put("candidateCount", mc != null && mc.getCoverCandidates() != null ? mc.getCoverCandidates().size() : 0);
        m.put("coverSearchStatus", mc != null ? mc.getCoverSearchStatus() : "IDLE");
        m.put("coverSearchedAt", mc != null ? mc.getCoverSearchedAt() : 0L);
        m.put("confirmedNoCover", mc != null && mc.isConfirmedNoCover());
        m.put("selectedCoverSha256", mc != null ? mc.getSelectedCoverSha256() : null);
        m.put("resolutionNote", item.getResolutionNote());
        return m;
    }

    private Map<String, Object> toDetail(ReviewItem item) {
        Map<String, Object> m = new LinkedHashMap<>(toSummary(item));
        ReviewItem.MissingCover mc = item.getMissingCover();
        if (mc != null) {
            m.put("releaseGroupId", mc.getReleaseGroupId());
            m.put("releaseId", mc.getReleaseId());
            m.put("releaseDate", mc.getReleaseDate());
            m.put("releaseType", mc.getReleaseType());
            m.put("unresolvedAlbum", mc.isUnresolvedAlbum());
            m.put("looseFile", mc.isLooseFile());
            m.put("customSearchQuery", mc.getCustomSearchQuery());
            m.put("coverSearchNote", mc.getCoverSearchNote());
            m.put("coverCandidates", mc.getCoverCandidates() == null ? List.of() : mc.getCoverCandidates());
        }
        List<Map<String, Object>> files = new ArrayList<>();
        if (item.getFiles() != null) {
            for (ReviewItem.FileEntry fe : item.getFiles()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("fileName", fe.getFileName());
                f.put("originalPath", fe.getOriginalPath());
                f.put("duration", fe.getDuration());
                MusicMetadata md = fe.getMetadata();
                if (md != null) {
                    f.put("title", md.getTitle());
                    f.put("artist", md.getArtist());
                    f.put("discNo", md.getDiscNo());
                    f.put("trackNo", md.getTrackNo());
                }
                files.add(f);
            }
        }
        m.put("files", files);
        return m;
    }

    private static RecoveryService.DuplicatePolicy duplicatePolicy(String raw) {
        if (raw == null || raw.isBlank()) return RecoveryService.DuplicatePolicy.ASK;
        try {
            return RecoveryService.DuplicatePolicy.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return RecoveryService.DuplicatePolicy.ASK;
        }
    }

    private String normalizedPath(HttpServletRequest req) {
        String path = req.getPathInfo();
        if (path == null) return "";
        if (path.startsWith("/")) path = path.substring(1);
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
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

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }

    private static List<String> strList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    private void respond(HttpServletResponse resp, int status, Map<String, ?> body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write(gson.toJson(body));
    }
}
