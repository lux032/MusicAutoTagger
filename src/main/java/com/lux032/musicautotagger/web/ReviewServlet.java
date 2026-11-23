package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lux032.musicautotagger.model.ReviewDiagnosis;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.CoverCandidateService;
import com.lux032.musicautotagger.service.RecoveryService;
import com.lux032.musicautotagger.service.ReviewQueueService;
import com.lux032.musicautotagger.service.ReviewResolutionService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
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
 *   POST /api/review/unreject   {"id":"..."}
 *   POST /api/review/llm        {"id":"..."}   -> LLM 封闭式判定（阶段七 #22）
 */
@Slf4j
public class ReviewServlet extends HttpServlet {

    private static final String SESSION_CSRF_KEY = "csrfToken";
    private static final java.util.Set<ReviewItem.Kind> REVIEW_ONLY_KINDS =
        java.util.Set.of(ReviewItem.Kind.ALBUM_UNRESOLVED, ReviewItem.Kind.TRACKS_MISSING);

    private final ReviewQueueService reviewQueue;
    private final ReviewResolutionService resolutionService;
    private final com.lux032.musicautotagger.service.RecoveryService recoveryService;
    private final CoverCandidateService coverCandidateService;
    private final java.util.Set<String> onlineSearchInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> coverCandidatesInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public ReviewServlet(ReviewQueueService reviewQueue, ReviewResolutionService resolutionService) {
        this(reviewQueue, resolutionService, null, null);
    }

    public ReviewServlet(ReviewQueueService reviewQueue, ReviewResolutionService resolutionService,
                         com.lux032.musicautotagger.service.RecoveryService recoveryService,
                         CoverCandidateService coverCandidateService) {
        this.reviewQueue = reviewQueue;
        this.resolutionService = resolutionService;
        this.recoveryService = recoveryService;
        this.coverCandidateService = coverCandidateService;
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
                        "pending", reviewQueue.countPendingNonCover(),
                        "pendingAlbum", reviewQueue.countPending(ReviewItem.Kind.ALBUM_UNRESOLVED),
                        "pendingMissingTracks", reviewQueue.countPending(ReviewItem.Kind.TRACKS_MISSING),
                        "rejected", reviewQueue.countRejectedNonCover(),
                        // 概览页直接列出最新的几条并带上原因，点一下就落在那一条上
                        "latest", latestPending(LATEST_PENDING_LIMIT),
                        "total", reviewQueue.listByKinds(null, REVIEW_ONLY_KINDS).size(),
                        // 面板靠这个开关决定要不要显示「待确认」入口：
                        // 关闭且队列为空时隐藏，但队列里还有遗留条目就必须继续显示，否则那些条目无法处置
                        "enabled", resolutionService.getConfig().isReviewEnabled(),
                        "llmEnabled", resolutionService.getConfig().isLlmAlbumJudgeEnabled()));
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
                    respond(resp, 200, Map.of("success", true, "item", toDetail(item),
                        "movedToMissingCover", item.effectiveKind() == ReviewItem.Kind.COVER_MISSING));
                    return;
                }
                case "archive": {
                    ReviewItem item = resolutionService.archiveAsUnverified(id);
                    respond(resp, 200, Map.of("success", true, "item", toDetail(item),
                        "movedToMissingCover", item.effectiveKind() == ReviewItem.Kind.COVER_MISSING));
                    return;
                }
                case "reject": {
                    ReviewItem item = resolutionService.reject(id, str(body.get("note")));
                    respond(resp, 200, Map.of("success", true, "item", toDetail(item)));
                    return;
                }
                case "unreject": {
                    ReviewItem item = resolutionService.unreject(id);
                    respond(resp, 200, Map.of("success", true, "item", toDetail(item)));
                    return;
                }
                case "expand": {
                    ReviewItem item = resolutionService.expandCandidates(id);
                    respond(resp, 200, Map.of("success", true, "item", toDetail(item)));
                    return;
                }
                case "cover-candidates": {
                    handleCoverCandidates(id, str(body.get("candidateId")), resp);
                    return;
                }
                case "online-search": {
                    if (recoveryService == null) {
                        respond(resp, 501, Map.of("error", "recovery.unavailable"));
                        return;
                    }
                    boolean analyzeCover = Boolean.TRUE.equals(body.get("analyzeCover"));
                    com.lux032.musicautotagger.service.OnlineSearchQueueService queue = recoveryService.getSearchQueueService();
                    if (queue != null) {
                        var res = queue.enqueueReviewItem(id, analyzeCover);
                        if (res.alreadyActive()) {
                            respond(resp, 409, Map.of("error", "recovery.already.running", "task", res.task()));
                            return;
                        }
                        respond(resp, 202, Map.of("success", true, "queued", true, "task", res.task()));
                        return;
                    }
                    ReviewItem item = reviewQueue.get(id);
                    if (item == null) {
                        respond(resp, 404, Map.of("error", "item.not.found"));
                        return;
                    }
                    if (item.getStatus() != ReviewItem.Status.PENDING_REVIEW) {
                        respond(resp, 409, Map.of("error", "item.already.resolved"));
                        return;
                    }
                    if (!recoveryService.isOnlineSearchAvailable()) {
                        respond(resp, 503, Map.of("error", "llm.web.search.unavailable"));
                        return;
                    }
                    if (!onlineSearchInFlight.add(id)) {
                        respond(resp, 409, Map.of("error", "recovery.already.running"));
                        return;
                    }
                    try {
                        ReviewItem result = recoveryService.triggerOnlineSearchForReviewFolder(
                            new File(item.getFolderPath()), analyzeCover);
                        respond(resp, 200, Map.of("success", true, "item", toDetail(result)));
                    } finally {
                        onlineSearchInFlight.remove(id);
                    }
                    return;
                }
                case "local-preview": {
                    if (recoveryService == null) throw new ReviewResolutionService.ResolutionException(501, "recovery.unavailable");
                    respond(resp, 200, Map.of("draft", recoveryService.previewLocalArchive(id)));
                    return;
                }
                case "local-confirm": {
                    if (recoveryService == null) throw new ReviewResolutionService.ResolutionException(501, "recovery.unavailable");
                    ReviewItem item;
                    synchronized (resolutionService) {
                        item = recoveryService.confirmLocalArchive(id, str(body.get("revision")),
                            str(body.get("albumTitle")), str(body.get("albumArtist")), strList(body.get("albumArtists")),
                            trackArtistLists(body.get("trackArtists")), strList(body.get("trackArtistFallbacks")),
                            duplicatePolicy(str(body.get("duplicatePolicy"))));
                    }
                    respond(resp, 200, Map.of("success", true, "item", toDetail(item),
                        "movedToMissingCover", item.effectiveKind() == ReviewItem.Kind.COVER_MISSING));
                    return;
                }
                case "online-confirm": {
                    if (recoveryService == null) throw new ReviewResolutionService.ResolutionException(501, "recovery.unavailable");
                    ReviewItem item = recoveryService.confirmOnlineCandidate(id, str(body.get("candidateId")),
                        str(body.get("albumTitle")), str(body.get("albumArtist")), str(body.get("releaseDate")),
                        str(body.get("edition")), str(body.get("archiveDirectoryName")),
                        str(body.get("selectedCoverSha256")), duplicatePolicy(str(body.get("duplicatePolicy"))),
                        strList(body.get("albumArtists")), Boolean.TRUE.equals(body.get("confirmNoCover")));
                    respond(resp, 200, Map.of("success", true, "item", toDetail(item),
                        "movedToMissingCover", item.effectiveKind() == ReviewItem.Kind.COVER_MISSING));
                    return;
                }
                case "llm": {
                    // 阶段七 #22：LLM 辅助判定。封闭选择题（从候选中选 / 都不是），
                    // 结论默认仍是「待人工确认」，除非 llm.album.autoApply 显式打开。
                    ReviewItem item = resolutionService.judgeWithLlm(id);
                    respond(resp, 200, Map.of("success", true, "item", toDetail(item)));
                    return;
                }
                default:
                    respond(resp, 404, Map.of("error", "unknown.action"));
            }
        } catch (ReviewResolutionService.ResolutionException e) {
            log.warn("待确认条目处置被拒绝: {} - {}", id, e.getMessage());
            respond(resp, e.getHttpStatus(), Map.of("error", e.getMessage()));
        } catch (com.lux032.musicautotagger.service.DuplicateArchiveException e) {
            // 什么都没提交；前端据此让用户选择「替换 / 保留两份 / 取消」后带 duplicatePolicy 重新提交
            log.info("待确认归档暂停，音乐库已有相同音频: {} - {}", id, e.describe());
            respond(resp, 409, Map.of("error", e.getMessage(), "duplicates", e.getDuplicates()));
        } catch (java.io.IOException e) {
            log.warn("待确认操作被拒绝: {} - {}", id, e.getMessage());
            String msg = e.getMessage() == null ? "online.confirm.failed" : e.getMessage();
            int status = switch (msg) {
                case "llm.web.search.unavailable" -> 503;
                case "item.not.found" -> 404;
                case "item.already.resolved", "recovery.already.running" -> 409;
                default -> 400;
            };
            respond(resp, status, Map.of("error", msg));
        } catch (Exception e) {
            log.error("待确认条目处置失败: {}", id, e);
            respond(resp, 500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // ==================== handlers ====================

    private void handleCoverCandidates(String id, String candidateId, HttpServletResponse resp) throws IOException {
        if (coverCandidateService == null) {
            respond(resp, 501, Map.of("error", "recovery.unavailable"));
            return;
        }
        String inFlightKey = id + "\n" + candidateId;
        if (!coverCandidatesInFlight.add(inFlightKey)) {
            respond(resp, 409, Map.of("error", "recovery.already.running"));
            return;
        }
        try {
        ReviewItem item = reviewQueue.get(id);
        if (item == null) { respond(resp, 404, Map.of("error", "item.not.found")); return; }
        ReviewItem.OnlineCandidate candidate = item.getOnlineCandidates().stream()
            .filter(c -> candidateId != null && candidateId.equals(c.getId())).findFirst().orElse(null);
        if (candidate == null) { respond(resp, 404, Map.of("error", "online.candidate.not.found")); return; }
        List<File> originals = new ArrayList<>();
        if (item.getFiles() != null) for (ReviewItem.FileEntry entry : item.getFiles()) {
            if (entry.getOriginalPath() != null) originals.add(new File(entry.getOriginalPath()));
        }
        if (originals.isEmpty()) {
            File root = new File(item.getRecoverySourcePath() != null
                ? item.getRecoverySourcePath() : item.getFolderPath());
            if (root.isFile()) originals.add(root);
            else try (var walk = java.nio.file.Files.walk(root.toPath())) {
                walk.filter(java.nio.file.Files::isRegularFile).map(java.nio.file.Path::toFile)
                    .filter(File::isFile).forEach(originals::add);
            }
        }
        candidate.setCoverCandidates(coverCandidateService.collect(item, candidate, originals));
        reviewQueue.update(item);
        respond(resp, 200, Map.of("coverCandidates", candidate.getCoverCandidates()));
        } finally {
            coverCandidatesInFlight.remove(inFlightKey);
        }
    }

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

        // q：按文件夹名 / 路径 / 文件名 / 曲名 / 艺术家搜索。
        // 用户往往只知道「某个文件没被处理」，而队列按文件夹组织，必须能从文件反查到条目。
        String query = req.getParameter("q");
        boolean searching = query != null && !query.trim().isEmpty();
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (ReviewItem item : reviewQueue.listByKinds(filter, REVIEW_ONLY_KINDS)) {
            String hit = ReviewQueueService.matchQuery(item, query);
            if (hit == null) {
                continue;
            }
            Map<String, Object> summary = toSummary(item);
            if (searching && !hit.isEmpty()) {
                summary.put("matchedFile", hit);
            }
            summaries.add(summary);
        }
        respond(resp, 200, Map.of("items", summaries, "pending", reviewQueue.countPendingNonCover()));
    }

    private void handleItem(HttpServletRequest req, HttpServletResponse resp)
            throws IOException, ReviewResolutionService.ResolutionException {
        String id = req.getParameter("id");
        if (id == null || id.isEmpty()) {
            respond(resp, 400, Map.of("error", "missing.id"));
            return;
        }
        ReviewItem item = reviewQueue.get(id);
        if (item == null || item.effectiveKind() == ReviewItem.Kind.COVER_MISSING) {
            respond(resp, 404, Map.of("error", "item.not.found"));
            return;
        }
        // 注意：GET 端点保持严格只读无副作用；展开候选必须通过 POST /api/review/expand（带 CSRF 校验）触发
        respond(resp, 200, Map.of("item", toDetail(item)));
    }

    // ==================== 视图 ====================

    private static final int LATEST_PENDING_LIMIT = 2;

    private List<Map<String, Object>> latestPending(int limit) {
        List<Map<String, Object>> latest = new ArrayList<>();
        for (ReviewItem item : reviewQueue.listByKinds(ReviewItem.Status.PENDING_REVIEW, REVIEW_ONLY_KINDS)) {
            if (latest.size() >= limit) {
                break;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", item.getId());
            map.put("folderName", item.getFolderName());
            map.put("reasonCode", ReviewDiagnosis.effectiveCode(item));
            map.put("createdAt", item.getCreatedAt());
            latest.add(map);
        }
        return latest;
    }

    private Map<String, Object> toSummary(ReviewItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.getId());
        map.put("folderName", item.getFolderName());
        map.put("folderPath", item.getFolderPath());
        map.put("status", item.getStatus().name());
        map.put("kind", item.effectiveKind().name());
        map.put("missingTracks", item.getMissingTracks());
        map.put("reason", item.getReason());
        map.put("reasonCode", ReviewDiagnosis.effectiveCode(item));
        map.put("reasonFacts", item.getReasonFacts() == null ? Map.of() : item.getReasonFacts());
        map.put("confidence", item.getConfidence());
        boolean localEvidenceAvailable = (item.getCandidates() != null && !item.getCandidates().isEmpty())
            || item.getSynthesizedAlbumTitle() != null || item.getSynthesizedAlbumArtist() != null;
        map.put("localEvidenceAvailable", localEvidenceAvailable);
        map.put("fileCount", item.getFiles() == null ? 0 : item.getFiles().size());
        map.put("candidateCount", item.getCandidates() == null ? 0 : item.getCandidates().size());
        map.put("synthesizedAlbumTitle", item.getSynthesizedAlbumTitle());
        map.put("synthesizedAlbumArtist", item.getSynthesizedAlbumArtist());
        map.put("createdAt", item.getCreatedAt());
        map.put("updatedAt", item.getUpdatedAt());
        map.put("resolutionNote", item.getResolutionNote());
        map.put("llmSuggestion", toLlmView(item.getLlmSuggestion()));
        map.put("verificationSource", item.getVerificationSource() == null ? null : item.getVerificationSource().name());
        map.put("onlineCandidateCount", item.getOnlineCandidates() == null ? 0 : item.getOnlineCandidates().size());
        double bestOnlineSourceConfidence = 0;
        double bestOnlineTrackCoverage = 0;
        if (item.getOnlineCandidates() != null) {
            for (ReviewItem.OnlineCandidate candidate : item.getOnlineCandidates()) {
                bestOnlineSourceConfidence = Math.max(bestOnlineSourceConfidence, candidate.getConfidence());
                bestOnlineTrackCoverage = Math.max(bestOnlineTrackCoverage, SystemSuggestionCalculator.reliableCoverage(item, candidate));
            }
        }
        map.put("bestOnlineSourceConfidence", bestOnlineSourceConfidence);
        map.put("bestOnlineTrackCoverage", bestOnlineTrackCoverage);
        map.put("onlineEvidenceStale", item.isOnlineEvidenceStale());
        map.put("onlineSearchedAt", item.getOnlineSearchedAt());
        boolean searchActive = onlineSearchInFlight.contains(item.getId())
            || (recoveryService != null && recoveryService.getSearchQueueService() != null
                && recoveryService.getSearchQueueService().isReviewItemActive(item.getId()));
        map.put("onlineSearchActive", searchActive);
        return map;
    }

    private Map<String, Object> toLlmView(ReviewItem.LlmSuggestion suggestion) {
        if (suggestion == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("evaluatedAt", suggestion.getEvaluatedAt());
        map.put("model", suggestion.getModel());
        map.put("provider", suggestion.getProvider());
        map.put("choiceIndex", suggestion.getChoiceIndex());
        map.put("suggestedReleaseId", suggestion.getSuggestedReleaseId());
        map.put("suggestedReleaseGroupId", suggestion.getSuggestedReleaseGroupId());
        map.put("suggestedTitle", suggestion.getSuggestedTitle());
        map.put("confidence", suggestion.getConfidence());
        map.put("unreleasedCompilation", suggestion.isUnreleasedCompilation());
        map.put("reason", suggestion.getReason());
        map.put("applied", suggestion.isApplied());
        return map;
    }

    private Map<String, Object> toDetail(ReviewItem item) {
        Map<String, Object> map = toSummary(item);
        map.put("durationSequence", item.getDurationSequence());
        map.put("candidatesExpanded", item.isCandidatesExpanded());
        map.put("llmJudgeEnabled", resolutionService.getConfig().isLlmAlbumJudgeEnabled());

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
        map.put("onlineSearchProvider", item.getOnlineSearchProvider());
        map.put("onlineSearchModel", item.getOnlineSearchModel());
        map.put("onlineClues", item.getOnlineClues());
        map.put("onlineCandidates", item.getOnlineCandidates());
        map.put("systemSuggestion", SystemSuggestionCalculator.evaluate(item,
            resolutionService.getConfig().getLlmAlbumAutoApplyMinConfidence()));
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

    private RecoveryService.DuplicatePolicy duplicatePolicy(String value) {
        if (value == null || value.isBlank()) return RecoveryService.DuplicatePolicy.ASK;
        try {
            return RecoveryService.DuplicatePolicy.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return RecoveryService.DuplicatePolicy.ASK;
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, List<String>> trackArtistLists(Object value) throws IOException {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map)) throw new IOException("local.archive.tracks.invalid");
        Map<String, List<String>> tracks = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof List<?> values)
                    || values.isEmpty() || !values.stream().allMatch(el -> el instanceof String s && !s.isBlank())) {
                throw new IOException("local.archive.tracks.invalid");
            }
            tracks.put(name, strList(values));
        }
        return tracks;
    }

    private List<String> strList(Object value) {
        if (value == null) return null;
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object el : list) {
                if (el != null) {
                    String s = String.valueOf(el).trim();
                    if (!s.isEmpty()) out.add(s);
                }
            }
            return out;
        }
        String raw = String.valueOf(value).trim();
        return raw.isEmpty() ? List.of() : com.lux032.musicautotagger.util.ArtistCreditSupport.parseSemicolonOrLineList(raw);
    }

    private void respond(HttpServletResponse resp, int status, Map<String, Object> payload) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(status);
        resp.getWriter().write(gson.toJson(payload));
    }
}
