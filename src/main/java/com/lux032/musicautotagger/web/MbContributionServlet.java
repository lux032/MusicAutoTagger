package com.lux032.musicautotagger.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lux032.musicautotagger.model.MbContributionItem;
import com.lux032.musicautotagger.service.MusicBrainzContributionService;
import com.lux032.musicautotagger.service.MusicBrainzReleaseBackfillService;
import com.lux032.musicautotagger.service.MusicBrainzSeedBuilder;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MusicBrainz 缺失专辑/版本回馈闭环 Web API（/api/mb-contributions/*）。
 */
@Slf4j
public class MbContributionServlet extends HttpServlet {

    private static final String SESSION_CSRF_KEY = "csrfToken";
    private static final Set<String> SAFE_IMAGE_MIMES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final MusicBrainzContributionService contributionService;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public MbContributionServlet(MusicBrainzContributionService contributionService) {
        this.contributionService = contributionService;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = normalizedPath(req);
        try {
            switch (path) {
                case "stats":
                    respond(resp, 200, contributionService.stats());
                    return;
                case "list":
                    handleList(req, resp);
                    return;
                case "item":
                    handleItem(req, resp);
                    return;
                case "cover":
                    handleCoverDownload(req, resp);
                    return;
                default:
                    respond(resp, 404, Map.of("error", "unknown.action"));
            }
        } catch (MusicBrainzReleaseBackfillService.BackfillException e) {
            respondError(resp, e.getHttpStatus(), e.getErrorCode(), e.getMessage());
        } catch (Exception e) {
            log.error("MusicBrainz 回馈接口查询失败 ({})", path, e);
            respondError(resp, 500, "mb.contribution.operation.failed", null);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isCsrfValid(req)) {
            respond(resp, 403, Map.of("error", "csrf.invalid"));
            return;
        }
        String path = normalizedPath(req);
        Map<String, Object> body = readBody(req);
        try {
            switch (path) {
                case "create-from-review": {
                    String reviewItemId = str(body.get("reviewItemId"));
                    if (reviewItemId == null) {
                        reviewItemId = str(body.get("id"));
                    }
                    if (reviewItemId == null) {
                        respond(resp, 400, Map.of("error", "review.item.id.required"));
                        return;
                    }
                    MusicBrainzContributionService.DetailView view = contributionService.createFromReviewItem(reviewItemId);
                    respond(resp, 200, detailResponse(view, true));
                    return;
                }
                case "recheck": {
                    String id = requireId(body, resp);
                    if (id == null) return;
                    MusicBrainzContributionService.DetailView view = contributionService.recheck(id);
                    respond(resp, 200, detailResponse(view, true));
                    return;
                }
                case "update-draft": {
                    String id = requireId(body, resp);
                    if (id == null) return;
                    MusicBrainzContributionService.DraftPatch patch = parseDraftPatchStrict(body);
                    MusicBrainzContributionService.DetailView view = contributionService.updateDraft(id, patch);
                    respond(resp, 200, detailResponse(view, true));
                    return;
                }
                case "link-release": {
                    String id = requireId(body, resp);
                    if (id == null) return;
                    String rawRelease = str(body.get("releaseUrlOrMbid"));
                    if (rawRelease == null) rawRelease = str(body.get("releaseUrl"));
                    if (rawRelease == null) rawRelease = str(body.get("releaseId"));
                    MusicBrainzContributionService.LinkReleaseResponse linked = contributionService.linkRelease(id, rawRelease);
                    Map<String, Object> out = detailResponse(linked.detail(), true);
                    out.put("backfill", linked.backfill());
                    respond(resp, 200, out);
                    return;
                }
                case "submit-acoustid": {
                    String id = requireId(body, resp);
                    if (id == null) return;
                    try {
                        MusicBrainzContributionService.AcoustIdSubmitResponse submitted = contributionService.submitAcoustId(id);
                        Map<String, Object> out = detailResponse(submitted.detail(), true);
                        out.put("acoustIdResult", submitted.result());
                        respond(resp, 200, out);
                    } catch (MusicBrainzReleaseBackfillService.BackfillException e) {
                        // 仅返回固定安全错误码，绝不透传远端可能回显密钥的原文
                        respondError(resp, e.getHttpStatus(), e.getErrorCode(), null);
                    } catch (IOException e) {
                        respondError(resp, 502, "acoustid.submit.failed", null);
                    }
                    return;
                }
                case "complete": {
                    String id = requireId(body, resp);
                    if (id == null) return;
                    MusicBrainzContributionService.DetailView view = contributionService.complete(id);
                    respond(resp, 200, detailResponse(view, true));
                    return;
                }
                case "dismiss": {
                    String id = requireId(body, resp);
                    if (id == null) return;
                    MusicBrainzContributionService.DetailView view = contributionService.dismiss(id);
                    respond(resp, 200, detailResponse(view, true));
                    return;
                }
                default:
                    respond(resp, 404, Map.of("error", "unknown.action"));
            }
        } catch (MusicBrainzReleaseBackfillService.BackfillException e) {
            respondError(resp, e.getHttpStatus(), e.getErrorCode(), e.getMessage());
        } catch (UncheckedIOException e) {
            log.error("MusicBrainz 回馈持久化失败 ({})", path, e);
            respondError(resp, 500, "mb.contribution.persist.failed", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            respondError(resp, 503, "operation.interrupted", "操作被中断");
        } catch (Exception e) {
            log.error("MusicBrainz 回馈操作失败 ({})", path, e);
            respondError(resp, 500, "mb.contribution.operation.failed", null);
        }
    }

    private void handleList(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String statusParam = trimToNull(req.getParameter("status"));
        String actionableParam = trimToNull(req.getParameter("actionableOnly"));
        MbContributionItem.Status filter = null;
        boolean actionableOnly = "true".equalsIgnoreCase(actionableParam) || "1".equals(actionableParam);

        if (statusParam != null) {
            if ("ACTIONABLE".equalsIgnoreCase(statusParam)) {
                actionableOnly = true;
            } else if (!"ALL".equalsIgnoreCase(statusParam)) {
                try {
                    filter = MbContributionItem.Status.valueOf(statusParam.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    respond(resp, 400, Map.of("error", "invalid.status"));
                    return;
                }
            }
        }

        String q = trimToNull(req.getParameter("q"));
        String qLower = q == null ? null : q.toLowerCase(Locale.ROOT);
        List<MbContributionItem> items = contributionService.list(filter, actionableOnly);
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (MbContributionItem item : items) {
            if (qLower != null && !matchesQuery(item, qLower)) {
                continue;
            }
            summaries.add(toSummary(item));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", summaries);
        out.put("stats", contributionService.stats());
        respond(resp, 200, out);
    }

    private void handleItem(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String id = trimToNull(req.getParameter("id"));
        if (id == null) {
            respond(resp, 400, Map.of("error", "missing.id"));
            return;
        }
        Optional<MusicBrainzContributionService.DetailView> viewOpt = contributionService.getDetailView(id);
        if (viewOpt.isEmpty()) {
            respond(resp, 404, Map.of("error", "mb.contribution.not.found"));
            return;
        }
        respond(resp, 200, detailResponse(viewOpt.get(), false));
    }

    private void handleCoverDownload(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String id = trimToNull(req.getParameter("id"));
        if (id == null) {
            respond(resp, 400, Map.of("error", "missing.id"));
            return;
        }
        Optional<MusicBrainzContributionService.CoverDownload> dlOpt = contributionService.resolveCoverBackupFile(id);
        if (dlOpt.isEmpty() || !Files.isRegularFile(dlOpt.get().file(), LinkOption.NOFOLLOW_LINKS)) {
            respond(resp, 404, Map.of("error", "mb.contribution.cover.not.found"));
            return;
        }
        MusicBrainzContributionService.CoverDownload dl = dlOpt.get();
        String mime = (dl.mime() != null && SAFE_IMAGE_MIMES.contains(dl.mime().toLowerCase(Locale.ROOT)))
            ? dl.mime().toLowerCase(Locale.ROOT)
            : "application/octet-stream";
        String downloadName = (dl.downloadFileName() == null || dl.downloadFileName().isBlank())
            ? "cover.jpg"
            : dl.downloadFileName().replace("\r", "").replace("\n", "").replace("\"", "");
        String asciiFallback = downloadName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (asciiFallback.isBlank()) asciiFallback = "cover.jpg";
        String encoded = URLEncoder.encode(downloadName, StandardCharsets.UTF_8).replace("+", "%20");

        // 使用 NOFOLLOW_LINKS 打开文件流，彻底防 TOCTOU 符号链接读取
        try (InputStream in = Files.newInputStream(dl.file(), LinkOption.NOFOLLOW_LINKS)) {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType(mime);
            resp.setHeader("X-Content-Type-Options", "nosniff");
            resp.setHeader("Cache-Control", "private, no-store");
            resp.setHeader("Content-Disposition",
                "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded);
            resp.setContentLengthLong(Files.size(dl.file()));
            try (OutputStream out = resp.getOutputStream()) {
                in.transferTo(out);
            }
        } catch (IOException e) {
            respond(resp, 404, Map.of("error", "mb.contribution.cover.not.found"));
        }
    }

    private Map<String, Object> toSummary(MbContributionItem item) {
        MbContributionItem.ReleaseDraft d = item.getDraft();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.getId());
        m.put("reviewItemId", item.getReviewItemId());
        m.put("status", item.getStatus() == null ? null : item.getStatus().name());
        m.put("checkCase", item.getCheckCase() == null ? null : item.getCheckCase().name());
        m.put("checkCode", item.getCheckCode());
        m.put("checkMessage", item.getCheckMessage());
        m.put("title", d == null ? null : d.getTitle());
        m.put("comment", d == null ? null : d.getComment());
        m.put("albumArtist", d == null ? null : d.getAlbumArtist());
        m.put("releaseDate", d == null ? null : d.getReleaseDate());
        m.put("releaseType", d == null ? null : d.getReleaseType());
        m.put("mediaFormat", d == null ? null : d.getMediaFormat());
        m.put("country", d == null ? null : d.getCountry());
        m.put("label", d == null ? null : d.getLabel());
        m.put("catalogNumber", d == null ? null : d.getCatalogNumber());
        m.put("barcode", d == null ? null : d.getBarcode());
        m.put("trackCount", d != null && d.getTracks() != null ? d.getTracks().size() : 0);
        m.put("archivedFileCount", item.getArchivedFilePaths() == null ? 0 : item.getArchivedFilePaths().size());
        m.put("archivedOutputDir", item.getArchivedOutputDir());
        m.put("matchedReleaseId", item.getMatchedReleaseId());
        m.put("matchedReleaseGroupId", item.getMatchedReleaseGroupId());
        m.put("matchedReleaseTitle", item.getMatchedReleaseTitle());
        m.put("linkedReleaseId", item.getLinkedReleaseId());
        m.put("linkedReleaseGroupId", item.getLinkedReleaseGroupId());
        m.put("linkedReleaseTitle", item.getLinkedReleaseTitle());
        m.put("backfilledFileCount", item.getBackfilledFileCount());
        m.put("acoustIdSubmittedAt", item.getAcoustIdSubmittedAt());
        m.put("acoustIdSubmittedCount", item.getAcoustIdSubmittedCount());
        m.put("createdAt", item.getCreatedAt());
        m.put("updatedAt", item.getUpdatedAt());
        m.put("checkedAt", item.getCheckedAt());
        return m;
    }

    private Map<String, Object> detailResponse(MusicBrainzContributionService.DetailView view, boolean includeSuccess) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (includeSuccess) {
            out.put("success", true);
        }
        out.put("item", view.item());
        out.put("seedPayload", view.seedPayload());
        out.put("coverGuide", view.coverGuide());
        out.put("worksGuide", view.worksGuide());
        out.put("acoustIdReady", view.acoustIdReady());
        out.put("acoustIdUserKeySet", view.acoustIdUserKeySet());
        return out;
    }

    private static MusicBrainzContributionService.DraftPatch parseDraftPatchStrict(Map<String, Object> body)
            throws MusicBrainzReleaseBackfillService.BackfillException {
        Boolean useExistingRg = null;
        if (body.containsKey("useExistingReleaseGroup")) {
            Object v = body.get("useExistingReleaseGroup");
            if (v instanceof Boolean b) {
                useExistingRg = b;
            } else if (v != null) {
                throw new MusicBrainzReleaseBackfillService.BackfillException(400, "invalid.request",
                    "useExistingReleaseGroup 必须为布尔值");
            }
        }

        List<String> includedUrls = null;
        if (body.containsKey("includedSourceUrls")) {
            Object v = body.get("includedSourceUrls");
            if (v instanceof List<?> raw) {
                includedUrls = new ArrayList<>(raw.size());
                for (Object o : raw) {
                    if (!(o instanceof String s)) {
                        throw new MusicBrainzReleaseBackfillService.BackfillException(400, "invalid.request",
                            "includedSourceUrls 必须为字符串数组");
                    }
                    includedUrls.add(s);
                }
            } else if (v != null) {
                throw new MusicBrainzReleaseBackfillService.BackfillException(400, "invalid.request",
                    "includedSourceUrls 必须为数组");
            }
        }

        String releaseDate = presentStrictString(body, "releaseDate");
        if (releaseDate != null && !releaseDate.isBlank() && !MusicBrainzSeedBuilder.isValidPartialIsoDate(releaseDate)) {
            throw new MusicBrainzReleaseBackfillService.BackfillException(400, "mb.contribution.draft.date.invalid",
                "发行日期格式或日历日期无效（应为 YYYY / YYYY-MM / YYYY-MM-DD）");
        }

        String country = presentStrictString(body, "country");
        if (country != null && !country.isBlank() && !country.trim().matches("^[A-Za-z]{2}$")) {
            throw new MusicBrainzReleaseBackfillService.BackfillException(400, "mb.contribution.draft.country.invalid",
                "发行地区代码必须为两位 ISO 国家码（如 JP / XW）");
        }

        return new MusicBrainzContributionService.DraftPatch(
            presentStrictString(body, "title"),
            presentStrictString(body, "comment"),
            useExistingRg,
            presentStrictString(body, "releaseType"),
            releaseDate,
            country,
            presentStrictString(body, "mediaFormat"),
            presentStrictString(body, "label"),
            presentStrictString(body, "catalogNumber"),
            presentStrictString(body, "barcode"),
            presentStrictString(body, "language"),
            presentStrictString(body, "script"),
            includedUrls
        );
    }

    private static String presentStrictString(Map<String, Object> body, String key)
            throws MusicBrainzReleaseBackfillService.BackfillException {
        if (!body.containsKey(key)) return null;
        Object v = body.get(key);
        if (v == null) return "";
        if (v instanceof String s) return s;
        throw new MusicBrainzReleaseBackfillService.BackfillException(400, "invalid.request",
            key + " 必须为字符串");
    }

    private static boolean matchesQuery(MbContributionItem item, String qLower) {
        if (item == null) return false;
        MbContributionItem.ReleaseDraft d = item.getDraft();
        return contains(item.getId(), qLower)
            || contains(item.getArchivedOutputDir(), qLower)
            || contains(item.getMatchedReleaseTitle(), qLower)
            || contains(item.getLinkedReleaseTitle(), qLower)
            || (d != null && (contains(d.getTitle(), qLower)
                || contains(d.getAlbumArtist(), qLower)
                || contains(d.getLabel(), qLower)
                || contains(d.getCatalogNumber(), qLower)
                || contains(d.getBarcode(), qLower)));
    }

    private static boolean contains(String haystack, String needleLower) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    private String requireId(Map<String, Object> body, HttpServletResponse resp) throws IOException {
        String id = str(body.get("id"));
        if (id == null) {
            respond(resp, 400, Map.of("error", "missing.id"));
            return null;
        }
        return id;
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
        if (session == null) return false;
        Object expectedObj = session.getAttribute(SESSION_CSRF_KEY);
        String actual = req.getHeader("X-CSRF-Token");
        if (!(expectedObj instanceof String expected) || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8));
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

    private void respondError(HttpServletResponse resp, int status, String errorCode, String detail) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", errorCode == null || errorCode.isBlank() ? "mb.contribution.operation.failed" : errorCode);
        if (detail != null && !detail.isBlank()) {
            body.put("detail", detail);
        }
        respond(resp, status, body);
    }

    private void respond(HttpServletResponse resp, int status, Map<String, ?> body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write(gson.toJson(body));
    }
}
