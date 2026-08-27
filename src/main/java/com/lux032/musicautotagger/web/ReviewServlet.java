package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.ReviewQueueService;
import com.lux032.musicautotagger.service.ReviewResolutionService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 待人工确认 API（阶段六 #19）
 *
 * 这是本项目**第一个写入型业务 API**，而且会触发真实的文件写入与移动，
 * 因此安全要求高于现有的只读接口：
 *   - 会话鉴权由 {@link AuthFilter} 统一把关（/api/* 均需登录）
 *   - 写操作额外校验 CSRF Token（与 ConfigServlet 一致）
 *   - 幂等由 {@link ReviewResolutionService} 的状态检查保证（重复提交返回 409）
 *
 * 路由：
 *   GET  /api/review/list?status=PENDING_REVIEW
 *   GET  /api/review/item?id=xxx[&expand=1]
 *   POST /api/review/confirm    {"id":"...","releaseId":"...","releaseGroupId":"..."}
 *   POST /api/review/archive    {"id":"..."}
 *   POST /api/review/reject     {"id":"...","note":"..."}
 *   POST /api/review/llm        {"id":"..."}   -> 501（阶段七）
 */
@Slf4j
public class ReviewServlet extends HttpServlet {

    private static final String SESSION_CSRF_KEY = "csrfToken";

    private final ReviewQueueService reviewQueue;
    private final ReviewResolutionService resolutionService;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public ReviewServlet(ReviewQueueService reviewQueue, ReviewResolutionService resolutionService) {
        this.reviewQueue = reviewQueue;
        this.resolutionService = resolutionService;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String action = action(req);

        try {
            switch (action) {
                case "list":
                    handleList(req, resp);
                    return;
                case "item":
                    handleItem(req, resp);
                    return;
                case "stats":
                    respond(resp, 200, Map.of(
                        "pending", reviewQueue.countPending(),
                        "total", reviewQueue.list(null).size()));
                    return;
                default:
                    respond(resp, 404, Map.of("error", "unknown.action"));
            }
        } catch (ReviewResolutionService.ResolutionException e) {
            respond(resp, e.getHttpStatus(), Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("待确认队列查询失败", e);
            respond(resp, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isCsrfValid(req)) {
            respond(resp, 403, Map.of("error", "csrf.invalid"));
            return;
        }

        String action = action(req);
        Map<String, Object> body = readBody(req);
        String id = str(body.get("id"));

        if (id == null || id.isEmpty()) {
            respond(resp, 400, Map.of("error", "missing.id"));
            return;
        }

        try {
            switch (action) {
                case "confirm": {
                    ReviewItem item = resolutionService.confirmCandidate(
                        id, str(body.get("releaseId")), str(body.get("releaseGroupId")));
                    respond(resp, 200, Map.of("success", true, "item", toDetail(item)));
                    return;
                }
                case "archive": {
                    ReviewItem item = resolutionService.archiveAsUnverified(id);
                    respond(resp, 200, Map.of("success", true, "item", toDetail(item)));
                    return;
                }
                case "reject": {
                    ReviewItem item = resolutionService.reject(id, str(body.get("note")));
                    respond(resp, 200, Map.of("success", true, "item", toDetail(item)));
                    return;
                }
                case "expand": {
                    ReviewItem item = resolutionService.expandCandidates(id);
                    respond(resp, 200, Map.of("success", true, "item", toDetail(item)));
                    return;
                }
                case "llm":
                    // 阶段七：LLM 辅助判定。必须是封闭选择题（从候选中选 / 都不是），
                    // 且结论默认仍是「待人工确认」，不自动落盘。
                    respond(resp, 501, Map.of("error", "llm.not.implemented"));
                    return;
                default:
                    respond(resp, 404, Map.of("error", "unknown.action"));
            }
        } catch (ReviewResolutionService.ResolutionException e) {
            log.warn("待确认条目处置被拒绝: {} - {}", id, e.getMessage());
            respond(resp, e.getHttpStatus(), Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("待确认条目处置失败: {}", id, e);
            respond(resp, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // ==================== handlers ====================

    private void handleList(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ReviewItem.Status filter = null;
        String statusParam = req.getParameter("status");
        if (statusParam != null && !statusParam.isEmpty() && !"ALL".equalsIgnoreCase(statusParam)) {
            try {
                filter = ReviewItem.Status.valueOf(statusParam.toUpperCase());
            } catch (IllegalArgumentException e) {
                respond(resp, 400, Map.of("error", "invalid.status"));
                return;
            }
        }

        List<Map<String, Object>> summaries = new ArrayList<>();
        for (ReviewItem item : reviewQueue.list(filter)) {
            summaries.add(toSummary(item));
        }
        respond(resp, 200, Map.of("items", summaries, "pending", reviewQueue.countPending()));
    }

    private void handleItem(HttpServletRequest req, HttpServletResponse resp)
            throws IOException, ReviewResolutionService.ResolutionException {
        String id = req.getParameter("id");
        if (id == null || id.isEmpty()) {
            respond(resp, 400, Map.of("error", "missing.id"));
            return;
        }
        ReviewItem item = reviewQueue.get(id);
        if (item == null) {
            respond(resp, 404, Map.of("error", "item.not.found"));
            return;
        }
        // expand=1 会打 MusicBrainz，把候选展开到 release 级并计算时长相似度
        if ("1".equals(req.getParameter("expand")) || "true".equals(req.getParameter("expand"))) {
            item = resolutionService.expandCandidates(id);
        }
        respond(resp, 200, Map.of("item", toDetail(item)));
    }

    // ==================== 视图 ====================

    private Map<String, Object> toSummary(ReviewItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.getId());
        map.put("folderName", item.getFolderName());
        map.put("folderPath", item.getFolderPath());
        map.put("status", item.getStatus().name());
        map.put("reason", item.getReason());
        map.put("confidence", item.getConfidence());
        map.put("fileCount", item.getFiles() == null ? 0 : item.getFiles().size());
        map.put("candidateCount", item.getCandidates() == null ? 0 : item.getCandidates().size());
        map.put("synthesizedAlbumTitle", item.getSynthesizedAlbumTitle());
        map.put("synthesizedAlbumArtist", item.getSynthesizedAlbumArtist());
        map.put("createdAt", item.getCreatedAt());
        map.put("updatedAt", item.getUpdatedAt());
        map.put("resolutionNote", item.getResolutionNote());
        return map;
    }

    private Map<String, Object> toDetail(ReviewItem item) {
        Map<String, Object> map = toSummary(item);
        map.put("durationSequence", item.getDurationSequence());
        map.put("candidatesExpanded", item.isCandidatesExpanded());

        List<Map<String, Object>> candidates = new ArrayList<>();
        if (item.getCandidates() != null) {
            for (ReviewItem.CandidateSnapshot c : item.getCandidates()) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("releaseGroupId", c.getReleaseGroupId());
                cm.put("releaseId", c.getReleaseId());
                cm.put("title", c.getTitle());
                cm.put("artist", c.getArtist());
                cm.put("date", c.getDate());
                cm.put("mediaFormat", c.getMediaFormat());
                cm.put("trackCount", c.getTrackCount());
                cm.put("supportCount", c.getSupportCount());
                cm.put("totalSamples", c.getTotalSamples());
                cm.put("durations", c.getDurations());
                cm.put("durationSimilarity", c.getDurationSimilarity());
                cm.put("durationDiff", diffAgainstFolder(item.getDurationSequence(), c.getDurations()));
                candidates.add(cm);
            }
        }
        map.put("candidates", candidates);

        List<Map<String, Object>> files = new ArrayList<>();
        if (item.getFiles() != null) {
            for (ReviewItem.FileEntry f : item.getFiles()) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("fileName", f.getFileName());
                fm.put("originalPath", f.getOriginalPath());
                fm.put("duration", f.getDuration());
                if (f.getMetadata() != null) {
                    fm.put("title", f.getMetadata().getTitle());
                    fm.put("artist", f.getMetadata().getArtist());
                    fm.put("trackNo", f.getMetadata().getTrackNo());
                    fm.put("discNo", f.getMetadata().getDiscNo());
                }
                files.add(fm);
            }
        }
        map.put("files", files);
        return map;
    }

    /**
     * 逐条时长 diff：把文件夹时长序列与候选 release 的时长序列按位置对齐，
     * 返回每个位置的秒差（缺位用 null 表示），供面板直接展示。
     */
    private List<Integer> diffAgainstFolder(List<Integer> folderDurations, List<Integer> candidateDurations) {
        List<Integer> diff = new ArrayList<>();
        if (folderDurations == null || candidateDurations == null) {
            return diff;
        }
        int size = Math.max(folderDurations.size(), candidateDurations.size());
        for (int i = 0; i < size; i++) {
            Integer a = i < folderDurations.size() ? folderDurations.get(i) : null;
            Integer b = i < candidateDurations.size() ? candidateDurations.get(i) : null;
            diff.add(a == null || b == null ? null : a - b);
        }
        return diff;
    }

    // ==================== 工具 ====================

    private String action(HttpServletRequest req) {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.length() <= 1) {
            return "";
        }
        String action = pathInfo.substring(1);
        int slash = action.indexOf('/');
        return slash >= 0 ? action.substring(0, slash) : action;
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> readBody(HttpServletRequest req) throws IOException {
        try (BufferedReader reader = req.getReader()) {
            Map<String, Object> data = gson.fromJson(reader, Map.class);
            return data == null ? new HashMap<>() : data;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void respond(HttpServletResponse resp, int status, Map<String, Object> payload) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(status);
        resp.getWriter().write(gson.toJson(payload));
    }
}
