package com.lux032.musicautotagger.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.llm.LlmAlbumJudge;
import com.lux032.musicautotagger.service.llm.LlmClient;
import com.lux032.musicautotagger.service.llm.NativeWebSearchClient;
import com.lux032.musicautotagger.service.llm.TavilyWebSearchClient;
import com.lux032.musicautotagger.service.llm.WebSearchClient;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 缺少封面条目的多源封面候选搜索服务。
 *
 * 按顺序收集候选图（全部经 ImageCompressor 压缩规则入候选缓存，绝不自动入库）：
 *   1. 本地候选（专辑目录图片 / 同专辑其他曲目已内嵌封面）+ MusicBrainz CAA 复查
 *   2. Apple Music / iTunes 免费高清封面检索
 *   3. AI 联网检索封面直链（复用 Tavily include_images 或原生联网搜索）
 */
@Slf4j
public class CoverSearchService {

    private static final int MAX_AI_IMAGE_URLS = 8;

    private static final String SYSTEM_PROMPT =
        "You are an album cover art research assistant with web search. "
        + "Find direct high-resolution official album cover image URLs (front jacket / album artwork) "
        + "for the exact music release described by the user. "
        + "Prefer official label sites, VGMdb, Apple Music / mzstatic, Discogs, Mora, OTOTOY, CDJapan, Amazon Japan. "
        + "Return ONLY one strict JSON object: "
        + "{\"cover_urls\":[{\"url\":string,\"source\":string,\"note\":string}],\"search_notes\":string}. "
        + "Only include direct image URLs (jpg/jpeg/png/webp) that you actually saw in the retrieved web search results or image URLs. "
        + "Include at most 6 URLs ordered from best match to fallback.";

    private final MusicConfig config;
    private final ReviewQueueService reviewQueue;
    private final CoverCandidateService coverCandidateService;
    private final WebSearchClient nativeSearchClient;
    private final WebSearchClient tavilySearchClient;
    private final WebSearchClient searchClientOverride;

    public CoverSearchService(MusicConfig config,
                              ReviewQueueService reviewQueue,
                              CoverCandidateService coverCandidateService) {
        this(config, reviewQueue, coverCandidateService, null);
    }

    CoverSearchService(MusicConfig config,
                       ReviewQueueService reviewQueue,
                       CoverCandidateService coverCandidateService,
                       WebSearchClient searchClientOverride) {
        this.config = config;
        this.reviewQueue = reviewQueue;
        this.coverCandidateService = coverCandidateService;
        this.nativeSearchClient = new NativeWebSearchClient(config);
        this.tavilySearchClient = new TavilyWebSearchClient(config, new LlmClient(config));
        this.searchClientOverride = searchClientOverride;
    }

    private WebSearchClient searchClient() {
        if (searchClientOverride != null) return searchClientOverride;
        return "tavily".equalsIgnoreCase(config.getWebSearchProvider())
            ? tavilySearchClient : nativeSearchClient;
    }

    public boolean isAiSearchAvailable() {
        return searchClient().hasEnabledEndpoint();
    }

    public String activeProviderName() {
        return searchClient().name();
    }

    /**
     * 为指定的「缺少封面」条目执行一次完整候选搜索（本地/CAA + iTunes + AI 联网）。
     * 结果写回 ReviewItem.missingCover.coverCandidates，绝不自动入库。
     */
    public ReviewItem searchCoversForItem(String itemId, String customQuery,
                                          OnlineIdentificationService.SearchProgressListener progress) throws Exception {
        checkInterrupted();
        ReviewItem item = reviewQueue.get(itemId);
        if (item == null || item.getStatus() != ReviewItem.Status.PENDING_REVIEW
            || item.effectiveKind() != ReviewItem.Kind.COVER_MISSING
            || item.getMissingCover() == null) {
            return null;
        }

        ReviewItem.MissingCover mc = item.getMissingCover();
        String albumTitle = meaningful(mc.getAlbumTitle()) ? mc.getAlbumTitle() : item.getSynthesizedAlbumTitle();
        String albumArtist = meaningful(mc.getAlbumArtist()) ? mc.getAlbumArtist() : item.getSynthesizedAlbumArtist();
        String effectiveQuery = meaningful(customQuery) ? customQuery.trim() : mc.getCustomSearchQuery();
        int fileCount = item.getFiles() == null ? 0 : item.getFiles().size();

        reviewQueue.updatePending(itemId, target -> {
            if (target.getMissingCover() == null) return false;
            target.getMissingCover().setCoverSearchStatus("SEARCHING");
            target.getMissingCover().setCustomSearchQuery(effectiveQuery);
            return true;
        });

        try {
            List<CoverCandidateView> collected = new ArrayList<>();
            // 保留用户已经手动上传 / 手动拉取 URL 的候选，避免重新搜索把它们冲掉
            if (mc.getCoverCandidates() != null) {
                for (CoverCandidateView existing : mc.getCoverCandidates()) {
                    if (existing != null && (existing.getOrigin() == CoverCandidateView.Origin.USER_UPLOAD
                        || existing.getOrigin() == CoverCandidateView.Origin.USER_URL)) {
                        collected.add(existing);
                    }
                }
            }

            // Step 0: 本地 + MusicBrainz CAA 复查
            emit(progress, 0, "COVER_LOCAL_CHECK", 0, 0, 0, 12, albumTitle, itemId, fileCount);
            List<File> audioFiles = resolveAudioFiles(item);
            File albumRoot = resolveAlbumRoot(item, audioFiles);
            collected.addAll(coverCandidateService.collectLocalCandidates(audioFiles, albumRoot));
            if (CoverCandidateService.isValidUuid(mc.getReleaseGroupId())) {
                coverCandidateService.fetchMusicBrainzCoverByRgid(mc.getReleaseGroupId()).ifPresent(collected::add);
            }
            checkInterrupted();

            // Step 1: Apple Music / iTunes 免费高清封面
            emit(progress, 1, "COVER_ITUNES_SEARCH", 1, 2, collected.size(), 35, albumTitle, itemId, fileCount);
            int itunesCount = 0;
            try {
                List<CoverCandidateView> itunesHits =
                    coverCandidateService.searchItunesCovers(albumTitle, albumArtist, effectiveQuery);
                itunesCount = itunesHits.size();
                collected.addAll(itunesHits);
            } catch (Exception e) {
                rethrowIfInterrupted(e);
                log.debug("iTunes cover search error for {}: {}", albumTitle, e.getMessage());
            }
            checkInterrupted();

            // Step 2: AI 联网搜封面（可选，已配置联网搜索时执行）
            int aiCount = 0;
            String aiNote = null;
            WebSearchClient client = searchClient();
            if (client.hasEnabledEndpoint()) {
                emit(progress, 2, "COVER_AI_SEARCH", 2, 2, collected.size(), 58, albumTitle, itemId, fileCount);
                try {
                    String prompt = buildCoverPrompt(item, mc, albumTitle, albumArtist, effectiveQuery);
                    WebSearchClient.ProgressListener bridge = (stage, cur, tot, cit, detail) -> {
                        int pct = "SUMMARIZING".equals(stage) ? 80 : 68;
                        emit(progress, 2, "COVER_AI_SEARCH", cur, Math.max(tot, 1), cit, pct, detail, itemId, fileCount);
                    };
                    WebSearchClient.SearchResponse resp = client.searchImages(SYSTEM_PROMPT, prompt, bridge);
                    checkInterrupted();
                    Map<String, CandidateHint> urlsToFetch = extractCandidateUrls(resp);
                    emit(progress, 3, "COVER_DOWNLOADING", 0, urlsToFetch.size(), collected.size(), 86, albumTitle, itemId, fileCount);
                    int idx = 0;
                    for (Map.Entry<String, CandidateHint> entry : urlsToFetch.entrySet()) {
                        checkInterrupted();
                        if (idx >= MAX_AI_IMAGE_URLS) break;
                        idx++;
                        CandidateHint hint = entry.getValue();
                        Optional<CoverCandidateView> fetched = coverCandidateService.ingestFromUrl(
                            entry.getKey(),
                            CoverCandidateView.Origin.LLM_URL,
                            meaningful(hint.source) ? hint.source : null,
                            hint.note);
                        if (fetched.isPresent()) {
                            collected.add(fetched.get());
                            aiCount++;
                        }
                        checkInterrupted();
                        emit(progress, 3, "COVER_DOWNLOADING", idx, Math.min(urlsToFetch.size(), MAX_AI_IMAGE_URLS),
                            collected.size(), 86 + (int) Math.round(12.0 * idx / Math.max(1, Math.min(urlsToFetch.size(), MAX_AI_IMAGE_URLS))),
                            albumTitle, itemId, fileCount);
                    }
                    aiNote = extractSearchNotes(resp);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                } catch (Exception e) {
                    rethrowIfInterrupted(e);
                    log.warn("AI 搜封面失败 [{}]: {}", albumTitle, e.getMessage());
                    aiNote = "AI 搜索未完成: " + e.getMessage();
                }
            } else {
                aiNote = "未配置 AI 联网搜索，仅执行了 iTunes 与本地检索";
            }
            checkInterrupted();

            List<CoverCandidateView> deduped = CoverCandidateService.deduplicateCandidates(collected);
            long now = System.currentTimeMillis();
            final int finalItunesCount = itunesCount;
            final int finalAiCount = aiCount;
            final String finalAiNote = aiNote;
            final int[] finalTotalHolder = { deduped.size() };

            reviewQueue.updatePending(itemId, target -> {
                ReviewItem.MissingCover liveMc = target.getMissingCover();
                if (liveMc == null) return false;
                // 二次合并锁内实时候选：保护用户在后台搜索运行期间刚上传或拉取链接的图片不被覆盖
                List<CoverCandidateView> merged = new ArrayList<>();
                if (liveMc.getCoverCandidates() != null) {
                    for (CoverCandidateView existing : liveMc.getCoverCandidates()) {
                        if (existing != null && (existing.getOrigin() == CoverCandidateView.Origin.USER_UPLOAD
                            || existing.getOrigin() == CoverCandidateView.Origin.USER_URL)) {
                            merged.add(existing);
                        }
                    }
                }
                merged.addAll(deduped);
                List<CoverCandidateView> finalList = CoverCandidateService.deduplicateCandidates(merged);
                finalTotalHolder[0] = finalList.size();
                liveMc.setCoverCandidates(new ArrayList<>(finalList));
                liveMc.setCoverSearchedAt(now);
                liveMc.setCoverSearchStatus("DONE");
                liveMc.setCoverSearchNote(buildSummaryNote(finalList.size(), finalItunesCount, finalAiCount, finalAiNote));
                return true;
            });

            String summary = buildSummaryNote(finalTotalHolder[0], itunesCount, aiCount, aiNote);
            emit(progress, 3, "COVER_DONE", finalTotalHolder[0], finalTotalHolder[0], finalTotalHolder[0], 100, summary, itemId, fileCount);
            log.info("封面搜索完成 [{}]: 共 {} 张候选（iTunes {}, AI {}）", albumTitle, finalTotalHolder[0], itunesCount, aiCount);
            return reviewQueue.get(itemId);
        } catch (InterruptedException ie) {
            reviewQueue.updatePending(itemId, target -> {
                ReviewItem.MissingCover liveMc = target.getMissingCover();
                if (liveMc == null || !"SEARCHING".equals(liveMc.getCoverSearchStatus())) return false;
                liveMc.setCoverSearchStatus(liveMc.getCoverSearchedAt() > 0 ? "DONE" : "IDLE");
                return true;
            });
            Thread.currentThread().interrupt();
            throw ie;
        } catch (Exception e) {
            if (isInterruptedError(e)) {
                revertSearchingStatus(itemId);
                Thread.currentThread().interrupt();
                throw new InterruptedException("cancelled");
            }
            reviewQueue.updatePending(itemId, target -> {
                ReviewItem.MissingCover liveMc = target.getMissingCover();
                if (liveMc == null) return false;
                liveMc.setCoverSearchStatus("FAILED");
                liveMc.setCoverSearchNote("搜索异常: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                return true;
            });
            throw e;
        }
    }

    private void revertSearchingStatus(String itemId) {
        reviewQueue.updatePending(itemId, target -> {
            ReviewItem.MissingCover liveMc = target.getMissingCover();
            if (liveMc == null || !"SEARCHING".equals(liveMc.getCoverSearchStatus())) return false;
            liveMc.setCoverSearchStatus(liveMc.getCoverSearchedAt() > 0 ? "DONE" : "IDLE");
            return true;
        });
    }

    private static void rethrowIfInterrupted(Throwable e) throws InterruptedException {
        if (isInterruptedError(e)) {
            Thread.currentThread().interrupt();
            throw new InterruptedException("cancelled");
        }
    }

    private static boolean isInterruptedError(Throwable e) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof InterruptedException || cur instanceof java.io.InterruptedIOException) {
                return true;
            }
            if (cur instanceof LlmClient.LlmException && "interrupted".equals(cur.getMessage())) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private record CandidateHint(String source, String note) {}

    private Map<String, CandidateHint> extractCandidateUrls(WebSearchClient.SearchResponse resp) {
        Map<String, CandidateHint> urls = new LinkedHashMap<>();
        if (resp == null) return urls;
        try {
            JsonObject json = LlmAlbumJudge.parseJsonObject(resp.getText());
            if (json != null && json.has("cover_urls") && json.get("cover_urls").isJsonArray()) {
                JsonArray arr = json.getAsJsonArray("cover_urls");
                for (JsonElement el : arr) {
                    if (el.isJsonPrimitive()) {
                        String u = el.getAsString().trim();
                        if (looksHttpUrl(u)) urls.putIfAbsent(u, new CandidateHint("AI 联网", null));
                    } else if (el.isJsonObject()) {
                        JsonObject obj = el.getAsJsonObject();
                        String u = str(obj, "url");
                        if (looksHttpUrl(u)) {
                            String src = str(obj, "source");
                            String note = str(obj, "note");
                            urls.putIfAbsent(u.trim(), new CandidateHint(meaningful(src) ? src : "AI 联网", note));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Parse AI cover JSON failed: {}", e.getMessage());
        }
        if (resp.getImages() != null) {
            for (String imgUrl : resp.getImages()) {
                if (looksHttpUrl(imgUrl)) {
                    urls.putIfAbsent(imgUrl.trim(), new CandidateHint("Tavily 图片", null));
                }
            }
        }
        return urls;
    }

    private String extractSearchNotes(WebSearchClient.SearchResponse resp) {
        if (resp == null || resp.getText() == null) return null;
        try {
            JsonObject json = LlmAlbumJudge.parseJsonObject(resp.getText());
            return str(json, "search_notes");
        } catch (Exception ignored) {
            return null;
        }
    }

    private String buildCoverPrompt(ReviewItem item, ReviewItem.MissingCover mc,
                                    String albumTitle, String albumArtist, String customQuery) {
        StringBuilder sb = new StringBuilder();
        sb.append("Find the official front album cover image URL for this music release.\n");
        sb.append("FOLDER: ").append(nz(item.getFolderName())).append('\n');
        sb.append("ALBUM: ").append(nz(albumTitle)).append('\n');
        sb.append("ALBUM ARTIST: ").append(nz(albumArtist)).append('\n');
        if (meaningful(mc.getReleaseDate())) {
            sb.append("RELEASE DATE: ").append(mc.getReleaseDate()).append('\n');
        }
        if (meaningful(mc.getReleaseType())) {
            sb.append("RELEASE TYPE: ").append(mc.getReleaseType()).append('\n');
        }
        if (meaningful(customQuery)) {
            sb.append("USER EXTRA KEYWORDS: ").append(customQuery).append('\n');
        }
        if (item.getFiles() != null && !item.getFiles().isEmpty()) {
            sb.append("SAMPLE TRACKS:\n");
            int limit = Math.min(8, item.getFiles().size());
            for (int i = 0; i < limit; i++) {
                ReviewItem.FileEntry fe = item.getFiles().get(i);
                MusicMetadata md = fe.getMetadata();
                sb.append(i + 1).append(". ");
                if (md != null && meaningful(md.getTitle())) {
                    sb.append(md.getTitle());
                    if (meaningful(md.getArtist())) sb.append(" — ").append(md.getArtist());
                } else {
                    sb.append(nz(fe.getFileName()));
                }
                sb.append('\n');
            }
        }
        sb.append("Search for exact front cover artwork (especially for Japanese / anime / game bonus CDs, OSTs, or singles) ");
        sb.append("and return ONLY JSON with direct image URLs.");
        return sb.toString();
    }

    private List<File> resolveAudioFiles(ReviewItem item) {
        List<File> files = new ArrayList<>();
        if (item.getFiles() != null) {
            for (ReviewItem.FileEntry fe : item.getFiles()) {
                if (fe != null && meaningful(fe.getOriginalPath())) {
                    File f = new File(fe.getOriginalPath());
                    if (f.isFile()) files.add(f);
                }
            }
        }
        return files;
    }

    File resolveAlbumRoot(ReviewItem item, List<File> audioFiles) {
        // 监控目录根部或输出根目录下的散落单曲没有专属专辑目录，严禁越界扫描父目录（如 monitorDirectory）里的无关图片
        if (item.getMissingCover() != null && item.getMissingCover().isLooseFile()) {
            return null;
        }
        if (meaningful(item.getFolderPath())) {
            File f = new File(item.getFolderPath());
            if (f.isDirectory()) return f;
            if (f.isFile()) return null;
        }
        if (!audioFiles.isEmpty()) {
            return audioFiles.get(0).getParentFile();
        }
        return null;
    }

    private static String buildSummaryNote(int total, int itunes, int ai, String aiNote) {
        String base = String.format("找到 %d 张候选封面（Apple Music/iTunes %d，AI 联网 %d）", total, itunes, ai);
        return meaningful(aiNote) ? base + " · " + aiNote : base;
    }

    private static void emit(OnlineIdentificationService.SearchProgressListener listener,
                             int stepIndex, String stageCode, int current, int total, int citations,
                             int percent, String detail, String itemId, int fileCount) {
        if (listener == null) return;
        try {
            listener.onProgress(new OnlineIdentificationService.SearchProgress(
                stepIndex, stageCode, 1, current, total, citations, percent, detail, itemId, fileCount, false));
        } catch (Exception ignored) {
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("cancelled");
        }
    }

    private static boolean looksHttpUrl(String value) {
        if (!meaningful(value)) return false;
        String lower = value.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String str(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull() || !obj.get(key).isJsonPrimitive()) {
            return null;
        }
        return obj.get(key).getAsString();
    }

    private static boolean meaningful(String value) {
        return value != null && !value.isBlank();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
