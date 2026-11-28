package com.lux032.musicautotagger.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.llm.CreditEvidenceVerifier;
import com.lux032.musicautotagger.service.llm.LlmAlbumJudge;
import com.lux032.musicautotagger.service.llm.LlmArtistSplitter;
import com.lux032.musicautotagger.service.llm.LlmClient;
import com.lux032.musicautotagger.service.llm.NativeWebSearchClient;
import com.lux032.musicautotagger.service.llm.TavilyWebSearchClient;
import com.lux032.musicautotagger.service.llm.TrackCreditEnrichmentService;
import com.lux032.musicautotagger.service.llm.WebPageFetcher;
import com.lux032.musicautotagger.service.llm.WebSearchClient;
import com.lux032.musicautotagger.util.ArtistCreditSupport;
import com.lux032.musicautotagger.util.MetadataUtils;
import com.lux032.musicautotagger.util.TagQualityEvaluator;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 实时联网识别：搜索只生成候选，绝不自动归档。 */
@Slf4j
public class OnlineIdentificationService implements AutoCloseable {
    private static final String SYSTEM = "You are a music release research assistant with web search. "
        + "Use current web sources. Return ONLY one JSON object. Never invent MusicBrainz IDs or unsupported fields. "
        + "A formal candidate needs one HIGH reliability source, or two independent MEDIUM sources. LOW sources are clues only. "
        + "Include at most five candidates. Preserve verbatim source URLs and provide track-level credit evidence when available.";

    private static final int MAX_NATIVE_DIRECT_VERIFY_PAGES = 6;

    private final MusicConfig config;
    // 两个实现都在启动时建好（构造开销只是持有 config 与一个 HttpClient），
    // 运行时按配置切换，避免「改了 provider 却要重启才生效」这种半生效状态
    private final WebSearchClient nativeSearchClient;
    private final TavilyWebSearchClient tavilySearchClient;
    private final WebSearchClient searchClientOverride;
    private final WebPageFetcher webPageFetcher;
    private final TrackCreditEnrichmentService creditEnrichmentService;
    private final ReviewQueueService reviewQueue;
    private final TagWriterService tagWriter;
    private final AudioFingerprintService fingerprintService;
    private final OnlineTrackMatcher trackMatcher;
    private final LlmArtistSplitter artistSplitter;
    private final Gson gson = new Gson();

    public OnlineIdentificationService(MusicConfig config, ReviewQueueService reviewQueue,
                                       TagWriterService tagWriter, AudioFingerprintService fingerprintService) {
        this(config, reviewQueue, tagWriter, fingerprintService, new LlmArtistSplitter(config), null, null, null);
    }

    OnlineIdentificationService(MusicConfig config, ReviewQueueService reviewQueue,
                                TagWriterService tagWriter, AudioFingerprintService fingerprintService,
                                LlmArtistSplitter artistSplitter) {
        this(config, reviewQueue, tagWriter, fingerprintService, artistSplitter, null, null, null);
    }

    OnlineIdentificationService(MusicConfig config, ReviewQueueService reviewQueue,
                                TagWriterService tagWriter, AudioFingerprintService fingerprintService,
                                LlmArtistSplitter artistSplitter, WebSearchClient searchClientOverride) {
        this(config, reviewQueue, tagWriter, fingerprintService, artistSplitter, searchClientOverride, null, null);
    }

    OnlineIdentificationService(MusicConfig config, ReviewQueueService reviewQueue,
                                TagWriterService tagWriter, AudioFingerprintService fingerprintService,
                                LlmArtistSplitter artistSplitter, WebSearchClient searchClientOverride,
                                WebPageFetcher webPageFetcherOverride,
                                TrackCreditEnrichmentService creditEnrichmentOverride) {
        this.config = config;
        LlmClient sharedLlm = new LlmClient(config);
        this.nativeSearchClient = new NativeWebSearchClient(config);
        this.tavilySearchClient = new TavilyWebSearchClient(config, sharedLlm);
        this.searchClientOverride = searchClientOverride;
        this.webPageFetcher = webPageFetcherOverride != null
            ? webPageFetcherOverride
            : new WebPageFetcher(config, this.tavilySearchClient);
        this.creditEnrichmentService = creditEnrichmentOverride != null
            ? creditEnrichmentOverride
            : new TrackCreditEnrichmentService(config, this.tavilySearchClient, sharedLlm, this.webPageFetcher);
        this.reviewQueue = reviewQueue;
        this.tagWriter = tagWriter;
        this.fingerprintService = fingerprintService;
        this.trackMatcher = new OnlineTrackMatcher(tagWriter);
        this.artistSplitter = artistSplitter;
    }

    @Override
    public void close() {
        if (webPageFetcher != null) {
            webPageFetcher.close();
        }
    }

    /**
     * 按 llm.webSearch.provider 选择联网搜索实现。每次调用都重新读配置，
     * 因此在 Web 面板上切换搜索来源无需重启。
     *
     * 模型原生 web search 在第三方端点上普遍不可用（不实现 /responses、不支持 tools、
     * 或声称支持但从不真正检索），因此提供 tavily 这条「外部检索器 + 普通 chat 归纳」的路径。
     */
    private WebSearchClient searchClient() {
        if (searchClientOverride != null) return searchClientOverride;
        return "tavily".equalsIgnoreCase(config.getWebSearchProvider())
            ? tavilySearchClient : nativeSearchClient;
    }

    public boolean isAvailable() { return searchClient().hasEnabledEndpoint(); }

    public String activeProviderName() { return searchClient().name(); }

    public record SearchProgress(
        int stepIndex,
        String stageCode,
        int round,
        int current,
        int total,
        int citations,
        int percent,
        String detail,
        String reviewItemId,
        int fileCount,
        boolean round1Saved
    ) {}

    @FunctionalInterface
    public interface SearchProgressListener {
        void onProgress(SearchProgress progress);
    }

    public ReviewItem search(File albumRoot, String sourceType, boolean analyzeCover) throws Exception {
        return search(albumRoot, sourceType, analyzeCover, null, null, null, null);
    }

    public ReviewItem search(File albumRoot, String sourceType, boolean analyzeCover,
                             SearchProgressListener progress) throws Exception {
        return search(albumRoot, sourceType, analyzeCover, null, null, null, progress);
    }

    public ReviewItem search(File albumRoot, String sourceType, boolean analyzeCover,
                             String displayName, File stagingDir) throws Exception {
        return search(albumRoot, sourceType, analyzeCover, null, displayName, stagingDir, null);
    }

    /**
     * 只为指定的已有条目搜索（后台自动搜索 / 待确认页队列用）。条目已不在待确认时返回 null，绝不新建条目。
     */
    public ReviewItem searchForExistingItem(String itemId, File albumRoot) throws Exception {
        return search(albumRoot, null, false, itemId, null, null, null);
    }

    public ReviewItem searchForExistingItem(String itemId, File albumRoot, boolean analyzeCover,
                                            SearchProgressListener progress) throws Exception {
        return search(albumRoot, null, analyzeCover, itemId, null, null, progress);
    }

    private ReviewItem search(File albumRoot, String sourceType, boolean analyzeCover,
                              String targetItemId, String displayName, File stagingDir,
                              SearchProgressListener progress) throws Exception {
        checkInterrupted();
        List<File> files = collect(albumRoot);
        if (files.isEmpty()) throw new IllegalArgumentException("recovery.no.audio.files");
        emitProgress(progress, 0, "COLLECTING", 1, 0, files.size(), 0, 8, null, targetItemId, files.size(), false);
        String evidenceHash = evidenceHash(files);
        ReviewItem item = targetItemId != null
            ? reviewQueue.prepareOnlineSearchForItem(targetItemId, albumRoot.getAbsolutePath(), evidenceHash)
            : reviewQueue.enqueueRecoveryFolder(albumRoot.getAbsolutePath(), sourceType,
                files, tagWriter, evidenceHash);
        if (item == null) {
            log.info("条目已不在待确认状态，取消联网搜索: {}", albumRoot.getName());
            return null;
        }

        final String itemId = item.getId();
        emitProgress(progress, 0, "COLLECTING", 1, files.size(), files.size(), 0, 14, null, itemId, files.size(), false);
        checkInterrupted();
        List<Integer> durations = fingerprintService != null
            ? fingerprintService.extractDurationSequence(files)
            : List.of();
        // 共享条目的所有修改都在队列锁内完成，且只在条目仍待确认时生效
        if (!reviewQueue.updatePending(itemId, target -> {
            if (durations != null) {
                target.setDurationSequence(new ArrayList<>(durations));
            }
            if (displayName != null && !displayName.isBlank()) {
                target.setFolderName(displayName);
            }
            if (stagingDir != null) {
                target.setStagingDir(stagingDir.getAbsolutePath());
            }
            if (analyzeCover) {
                appendResolutionNote(target, "已申请封面视觉分析；当前原生搜索适配器未检测到可移植的图片+搜索联合协议，已明确降级为文本联网搜索");
            }
            return true;
        })) {
            log.info("条目已不在待确认状态，取消联网搜索: {}", albumRoot.getName());
            return item;
        }
        String prompt = item.effectiveKind() == ReviewItem.Kind.TRACKS_MISSING && item.getMissingTracks() != null
            ? buildMissingTracksPrompt(item, albumRoot, files, durations)
            : buildPrompt(albumRoot, files, durations, analyzeCover);

        // 第一轮结果立即持久化；只有证据不足才执行第二轮。
        // 同一次识别的两轮搜索必须用同一个实现：中途配置变更不应让第二轮换一套证据来源
        WebSearchClient client = searchClient();
        boolean isNative = "native".equalsIgnoreCase(client.name());
        boolean isTavily = "tavily".equalsIgnoreCase(client.name());
        Map<String, WebSearchClient.Citation> citationPoolByCanon = new LinkedHashMap<>();

        checkInterrupted();
        WebSearchClient.ProgressListener r1Progress = bridgeClientProgress(progress, 1, itemId, files.size(), false);
        emitProgress(progress, 1, isTavily ? "ROUND1_QUERIES" : "ROUND1_NATIVE_SEARCH",
            1, 0, 0, 0, 22, null, itemId, files.size(), false);
        WebSearchClient.SearchResponse first = client.search(SYSTEM, prompt, r1Progress);
        checkInterrupted();
        addCitationsToPool(citationPoolByCanon, first.getCitations());
        Parsed parsed = parse(first, List.of(), citationPoolByCanon, Map.of(), isNative);
        if (isNative && config.isOnlineCreditsEnrichEnabled() && hasAnyCreditDetail(parsed)) {
            verifyNativeCandidatesWithDirectFetch(parsed, citationPoolByCanon);
        }
        emitProgress(progress, 2, "ROUND1_MATCHING", 1, 0, files.size(),
            first.getCitations() == null ? 0 : first.getCitations().size(), 72, null, itemId, files.size(), false);
        matchAll(parsed, files, durations);
        if (!save(item, first, parsed, evidenceHash)) {
            return item;
        }
        emitProgress(progress, 2, "ROUND1_SAVED", 1, files.size(), files.size(),
            first.getCitations() == null ? 0 : first.getCitations().size(), 78, null, itemId, files.size(), true);

        WebSearchClient.SearchResponse lastResponse = first;
        boolean ranSecondRound = false;

        if (parsed.candidates.isEmpty() || parsed.needsSecondRound) {
            // 如果在第一轮完成后用户请求中止，直接保留已落盘的第一轮结果并退出
            checkInterrupted();
            ranSecondRound = true;
            String secondPrompt = prompt + "\nFIRST ROUND FINDINGS:\n" + first.getText()
                + "\nRun a second, narrower search using aliases, original-language names, catalog numbers, official pages, Discogs or VGMdb."
                + " Return the same JSON schema.";
            try {
                WebSearchClient.ProgressListener r2Progress = bridgeClientProgress(progress, 2, itemId, files.size(), true);
                emitProgress(progress, 3, isTavily ? "ROUND2_QUERIES" : "ROUND2_NATIVE_SEARCH",
                    2, 0, 0, parsed.clues.size(), 82, null, itemId, files.size(), true);
                WebSearchClient.SearchResponse second = client.search(SYSTEM, secondPrompt, r2Progress);
                checkInterrupted();
                lastResponse = second;
                addCitationsToPool(citationPoolByCanon, second.getCitations());
                // 第二轮候选经常引用第一轮已经检索到的高可信 URL，解析时必须合并一二轮引用池
                Parsed secondParsed = parse(second, parsed.clues, citationPoolByCanon, Map.of(), isNative);
                if (isNative && config.isOnlineCreditsEnrichEnabled() && hasAnyCreditDetail(secondParsed)) {
                    verifyNativeCandidatesWithDirectFetch(secondParsed, citationPoolByCanon);
                }
                merge(parsed, secondParsed);
                mergeEvidence(parsed.clues, second.getCitations());
                emitProgress(progress, 3, "ROUND2_MATCHING", 2, 0, files.size(), parsed.clues.size(), 94, null, itemId, files.size(), true);
                matchAll(parsed, files, durations);
                save(item, second, parsed, evidenceHash);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (LlmClient.LlmException e) {
                if (Thread.currentThread().isInterrupted() || "interrupted".equals(e.getMessage())) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedException("cancelled");
                }
                reviewQueue.updatePending(itemId, target -> {
                    appendResolutionNote(target, "第二轮联网搜索失败；已保留第一轮结果，可人工使用或重新搜索");
                    return true;
                });
            }
        }

        // 原生模式：若单轮已产出正式候选，但所有曲目均无幕后人员且未触发第 2 轮，则执行最多 1 次定向原生词曲编检索
        if (isNative && config.isOnlineCreditsEnrichEnabled() && !ranSecondRound && !parsed.candidates.isEmpty()) {
            ReviewItem.OnlineCandidate best = selectBestCandidate(parsed.candidates);
            if (best != null && allTracksLackCredits(best)) {
                checkInterrupted();
                try {
                    emitProgress(progress, 3, "CREDITS_GAP_FILL", 2, 1, 1, citationPoolByCanon.size(), 86,
                        best.getTitle(), itemId, files.size(), true);
                    String creditPrompt = buildNativeCreditsFollowUpPrompt(best);
                    WebSearchClient.SearchResponse creditResp = client.search(SYSTEM, creditPrompt,
                        bridgeClientProgress(progress, 2, itemId, files.size(), true));
                    checkInterrupted();
                    addCitationsToPool(citationPoolByCanon, creditResp.getCitations());
                    mergeEvidence(parsed.clues, creditResp.getCitations());
                    if (applyNativeCreditsFollowUp(best, creditResp, citationPoolByCanon)) {
                        save(item, creditResp, parsed, evidenceHash);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                } catch (LlmClient.LlmException e) {
                    if (Thread.currentThread().isInterrupted() || "interrupted".equals(e.getMessage())) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedException("cancelled");
                    }
                    log.warn("原生模式定向幕后人员补充失败（保留首轮候选）: {}", e.getMessage());
                }
            }
        }

        // Tavily 模式：正式候选匹配后按配置触发开放域正文读取 + 状态压缩幕后人员补全
        if (isTavily && config.isOnlineCreditsEnrichEnabled() && !parsed.candidates.isEmpty()) {
            ReviewItem.OnlineCandidate best = selectBestCandidate(parsed.candidates);
            if (best != null) {
                checkInterrupted();
                Map<String, MusicMetadata> baseCredits = collectExistingBaseCredits(item, files);
                TrackCreditEnrichmentService.EnrichmentSummary summary = creditEnrichmentService.enrich(
                    best, baseCredits, bridgeEnrichmentProgress(progress, itemId, files.size()));
                if (summary.collectedCitations() != null && !summary.collectedCitations().isEmpty()) {
                    mergeEvidence(parsed.clues, summary.collectedCitations());
                }
                if (summary.searchCalls() > 0 || summary.pagesFetched() > 0) {
                    save(item, lastResponse, parsed, evidenceHash);
                }
                if (summary.interrupted()) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedException("cancelled");
                }
            }
        }
        return item;
    }

    private void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("cancelled");
        }
    }

    private WebSearchClient.ProgressListener bridgeClientProgress(SearchProgressListener progress, int round,
                                                                  String itemId, int fileCount, boolean round1Saved) {
        if (progress == null) return null;
        int stepIdx = round == 1 ? 1 : 3;
        String prefix = round == 1 ? "ROUND1_" : "ROUND2_";
        return (stage, current, total, citations, detail) -> {
            String code;
            int pct;
            if ("GENERATING_QUERIES".equals(stage)) {
                code = prefix + "QUERIES";
                pct = round == 1 ? 24 : 83;
            } else if ("WEB_SEARCH".equals(stage)) {
                code = prefix + "WEB_SEARCH";
                double frac = total > 0 ? Math.min(1.0, (double) current / total) : 0.5;
                pct = round == 1 ? (int) (30 + frac * 26) : (int) (85 + frac * 6);
            } else if ("SUMMARIZING".equals(stage)) {
                code = prefix + "SUMMARIZING";
                pct = round == 1 ? 64 : 92;
            } else if ("NATIVE_RETRY".equals(stage)) {
                code = prefix + "NATIVE_RETRY";
                pct = round == 1 ? 45 : 88;
            } else {
                code = prefix + "NATIVE_SEARCH";
                pct = round == 1 ? 40 : 86;
            }
            emitProgress(progress, stepIdx, code, round, current, total, citations, pct, detail, itemId, fileCount, round1Saved);
        };
    }

    private WebSearchClient.ProgressListener bridgeEnrichmentProgress(SearchProgressListener progress,
                                                                      String itemId, int fileCount) {
        if (progress == null) return null;
        return (stage, current, total, citations, detail) -> {
            int pct = switch (stage) {
                case "CREDITS_ALBUM_SEARCH" -> 82;
                case "CREDITS_FETCHING" -> 87;
                case "CREDITS_GAP_FILL" -> 91;
                case "CREDITS_VERIFYING" -> 95;
                default -> 90;
            };
            emitProgress(progress, 3, stage, 2, current, total, citations, pct, detail, itemId, fileCount, true);
        };
    }

    private void emitProgress(SearchProgressListener progress, int stepIndex, String stageCode, int round,
                              int current, int total, int citations, int percent, String detail,
                              String reviewItemId, int fileCount, boolean round1Saved) {
        if (progress != null) {
            try {
                progress.onProgress(new SearchProgress(stepIndex, stageCode, round, current, total,
                    citations, percent, detail, reviewItemId, fileCount, round1Saved));
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    String buildPrompt(File root, List<File> files, List<Integer> durations, boolean analyzeCover) {
        StringBuilder sb = new StringBuilder();
        sb.append("Research the exact music release represented by this local album folder.\n");
        sb.append("FOLDER: ").append(root.getName()).append("\nFILE COUNT: ").append(files.size()).append('\n');
        sb.append("DURATIONS: ").append(durations).append('\n');
        sb.append("LOCAL TRACKS:\n");
        int i=1;
        for (File file : files) {
            MusicMetadata md=tagWriter.readTags(file);
            sb.append(i++).append(". file=\"").append(file.getName()).append("\"");
            if(md!=null){ sb.append(" title=\"").append(nz(md.getTitle())).append("\" artist=\"")
                .append(nz(md.getArtist())).append("\" album=\"").append(nz(md.getAlbum())).append("\"")
                .append(" disc=").append(nz(md.getDiscNo())).append(" track=").append(nz(md.getTrackNo())); }
            sb.append('\n');
        }
        sb.append("VISUAL COVER ANALYSIS REQUESTED: ").append(analyzeCover)
            .append(". This API integration currently uses cover-derived text only when available; do not claim you saw an image unless image evidence is supplied.\n");
        sb.append("Search current official and music database sources. ");
        appendRegionPreference(sb);
        appendJsonSchema(sb);
        return sb.toString();
    }

    /**
     * 「锁定专辑缺曲」专用 prompt：已经知道最接近的版本，只需要找到收录了缺失曲目的完整版本
     * （单曲的 c/w 曲、豪华版 / 数字版 bonus、更大的原声合集等）以及完整曲目表。
     * 输出格式与普通搜索一致，复用同一套来源门槛与逐曲匹配。
     */
    String buildMissingTracksPrompt(ReviewItem item, File root, List<File> files, List<Integer> durations) {
        ReviewItem.MissingTracks missing = item.getMissingTracks();
        java.util.Map<String, ReviewItem.FileEntry> identified = new java.util.HashMap<>();
        if (item.getFiles() != null) {
            for (ReviewItem.FileEntry entry : item.getFiles()) {
                if (entry.getOriginalPath() != null) identified.put(new File(entry.getOriginalPath()).getAbsolutePath(), entry);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("A local album folder was matched to a MusicBrainz release, but that release does NOT contain\n")
          .append("all of the local tracks. Find the exact edition that contains ALL local tracks and its full tracklist.\n\n");
        sb.append("FOLDER: ").append(root.getName()).append("\nLOCAL FILE COUNT: ").append(files.size()).append('\n');
        sb.append("CLOSEST MUSICBRAINZ RELEASE: title=\"").append(nz(missing.getAlbumTitle()))
          .append("\" artist=\"").append(nz(missing.getAlbumArtist())).append("\"");
        if (missing.getReleaseTrackCount() > 0) sb.append(" tracks=").append(missing.getReleaseTrackCount());
        sb.append('\n');
        if (missing.getUnmatchedFiles() != null && !missing.getUnmatchedFiles().isEmpty()) {
            sb.append("LOCAL TRACKS CONFIRMED MISSING FROM THAT RELEASE:\n");
            for (java.util.Map.Entry<String, String> e : missing.getUnmatchedFiles().entrySet()) {
                sb.append("  - file=\"").append(e.getKey()).append("\" identified_title=\"").append(nz(e.getValue())).append("\"\n");
            }
        } else if (missing.getUnmatchedLocalCount() > 0) {
            sb.append("At least ").append(missing.getUnmatchedLocalCount())
              .append(" local track(s) have no duration counterpart in that release.\n");
        }
        sb.append("DURATIONS (seconds, local order): ").append(durations).append('\n');
        sb.append("LOCAL TRACKS (identified by acoustic fingerprint where available):\n");
        int i = 1;
        for (File file : files) {
            ReviewItem.FileEntry entry = identified.get(file.getAbsolutePath());
            MusicMetadata md = entry != null && entry.getMetadata() != null ? entry.getMetadata() : tagWriter.readTags(file);
            sb.append(i++).append(". file=\"").append(file.getName()).append("\"");
            if (md != null) {
                sb.append(" title=\"").append(nz(md.getTitle())).append("\" artist=\"").append(nz(md.getArtist())).append("\"");
            }
            if (entry != null && entry.getDuration() != null) sb.append(" duration=").append(entry.getDuration());
            sb.append('\n');
        }
        sb.append("Typical causes: a single whose coupling (c/w) track is missing on MusicBrainz, a deluxe / digital edition\n")
          .append("with bonus tracks, or a larger soundtrack collection. Prefer official store pages, the label site, VGMdb, Discogs.\n")
          .append("If the closest release above is in fact the right edition and the extra local files are simply not part of it,\n")
          .append("return it as a candidate and explain which local files do not belong to it.\n");
        appendRegionPreference(sb);
        appendJsonSchema(sb);
        return sb.toString();
    }

    private void appendRegionPreference(StringBuilder sb) {
        List<String> rawRegions = config == null ? null : config.getReleaseCountryPriority();
        if (rawRegions == null || rawRegions.isEmpty()) {
            return;
        }
        java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
        for (String region : rawRegions) {
            if (region != null) {
                String code = region.trim().toUpperCase(Locale.ROOT);
                if (!code.isEmpty()) {
                    normalized.add(code);
                }
            }
        }
        if (normalized.isEmpty()) {
            return;
        }
        List<String> regions = new ArrayList<>(normalized);
        sb.append("\nPREFERRED RELEASE REGIONS (priority order): ")
          .append(String.join(", ", regions)).append('\n');
        sb.append("- When multiple regional editions exist, prefer editions released in regions listed earlier above.\n");

        String primaryRegion = null;
        for (String code : regions) {
            if (!"XW".equals(code) && !"XE".equals(code)) {
                primaryRegion = code;
                break;
            }
        }
        if (primaryRegion != null) {
            sb.append("- Primary naming region: ").append(primaryRegion)
              .append(" (").append(regionLanguageHint(primaryRegion)).append("). ")
              .append("For ALL artist and creator fields (\"artist\", \"artists\", \"album_artist\", \"album_artists\", ")
              .append("\"tracks[].artist\", \"tracks[].artists\", \"tracks[].composers\", \"tracks[].lyricists\", \"tracks[].arrangers\"), ")
              .append("display names in the preferred region's official/standard form as much as possible:\n")
              .append("  1. Prefer official native names directly attested on preferred-region sources (official sites, MusicBrainz, VGMdb, Wikipedia, Apple Music, Discogs).\n")
              .append("  2. If retrieved sources only list a romanized or foreign-language spelling for an artist or creator with a well-known official name in ")
              .append(primaryRegion)
              .append(" (e.g., \"Yuki Kajiura\" -> \"梶浦由記\" for JP), convert/localize it to that official name.\n")
              .append("  3. Keep official stylized Latin stage names (e.g., \"YOASOBI\", \"LiSA\", \"Aimer\", \"TeddyLoid\") and Western/international artists commonly credited in Latin in ")
              .append(primaryRegion)
              .append(" (e.g., \"Taylor Swift\", \"Hans Zimmer\") in their original Latin form; do not force phonetic transliteration on them.\n")
              .append("  4. For obscure or ambiguous personal names whose exact native characters/kanji are uncertain, keep the source's attested spelling instead of guessing.\n")
              .append("  5. Keep display strings (\"artist\", \"album_artist\", \"tracks[].artist\") and their split arrays (\"artists\", \"album_artists\", \"tracks[].artists\") in the EXACT SAME script/language and order.\n")
              .append("  6. Keep album and track titles faithful to the matched official edition's tracklist; do not freely translate track titles.\n");
        }
    }

    private static String regionLanguageHint(String code) {
        return switch (code) {
            case "JP" -> "Japanese / 日本語 (Kanji & Kana)";
            case "CN" -> "Simplified Chinese / 简体中文";
            case "TW", "HK" -> "Traditional Chinese / 繁體中文";
            case "KR" -> "Korean / 한국어";
            case "US", "GB", "AU", "CA", "NZ" -> "English / Latin";
            default -> "official language and naming used in " + code;
        };
    }

    private void appendJsonSchema(StringBuilder sb) {
        sb.append("Return strict JSON:\n")
          .append("{\"needs_second_round\":boolean,\"candidates\":[{\"title\":string,\"artist\":string,\"artists\":[string],")
          .append("\"album_artist\":string,\"album_artists\":[string],\"release_date\":string,\"edition\":string,\"country\":string,")
          .append("\"label\":string,\"catalog_number\":string,\"cover_url\":string,\"confidence\":number 0..1,")
          .append("\"reason\":string,\"tracks\":[{\"disc\":number,\"track\":number,\"title\":string,\"artist\":string,\"artists\":[string],")
          .append("\"composers\":[string],\"lyricists\":[string],\"arrangers\":[string],\"duration\":number,")
          .append("\"version_type\":\"original|remix|alternate_arrangement|live|acoustic|instrumental|other\",")
          .append("\"credit_detail\":{\"lyricists\":Field,\"composers\":Field,\"arrangers\":Field}}],")
          .append("\"source_urls\":[string]}],\"clues\":[{\"url\":string,\"title\":string,\"snippet\":string}]}\n")
          .append("where optional Field={\"names\":[string],\"status\":\"verified|probable|conflict|not_found|not_searched\",")
          .append("\"evidence\":[{\"url\":string,\"source_type\":string,\"quote\":string,\"version_match\":\"exact_version|original_only|album_level_only|mismatch|unknown\"}],")
          .append("\"candidates\":[{\"names\":[string],\"urls\":[string],\"quotes\":[string]}],\"notes\":string|null}.\n")
          .append("For \"artists\", \"album_artists\", and \"tracks[].artists\", always list each individual credited artist separately in order ")
          .append("(e.g. \"artist\":\"TeddyLoid, 佐倉綾音 & 井上和\", \"artists\":[\"TeddyLoid\",\"佐倉綾音\",\"井上和\"]), ")
          .append("and never split a single band or artist whose own name contains punctuation (e.g. \"Earth, Wind & Fire\" -> [\"Earth, Wind & Fire\"]).\n")
          .append("For \"tracks[].composers\", \"tracks[].lyricists\", \"tracks[].arrangers\", and \"credit_detail\": ")
          .append("list each credited person separately in order ONLY when explicitly attested in the cited sources (omit empty fields or use [] when unknown); ")
          .append("NEVER infer or assume the performer/artist/album_artist is the composer, lyricist, or arranger; NEVER invent MusicBrainz IDs; ")
          .append("NEVER apply album-level staff/producers to individual tracks unless explicitly credited per track; ")
          .append("for Instrumental/Off Vocal/Karaoke tracks, lyricists MUST be []; ")
          .append("for Remix/Latin/Acoustic/Live/Alternate versions, NEVER inherit the original version's arrangers and NEVER put Remixer/Producer/Mixing engineer into arrangers (mention them in notes instead); ")
          .append("copy every source_urls and evidence[].url verbatim from actually searched/opened sources, with a concise verbatim quote (<=200 chars).");
    }

    Parsed parse(WebSearchClient.SearchResponse response) throws LlmClient.LlmException {
        return parse(response, List.of());
    }

    Parsed parse(WebSearchClient.SearchResponse response, List<ReviewItem.OnlineEvidence> priorEvidence) throws LlmClient.LlmException {
        Map<String, WebSearchClient.Citation> pool = new LinkedHashMap<>();
        addCitationsToPool(pool, response == null ? null : response.getCitations());
        boolean isNative = response != null && response.getProvider() != null
            && !response.getProvider().toLowerCase(Locale.ROOT).startsWith("tavily");
        return parse(response, priorEvidence, pool, Map.of(), isNative);
    }

    Parsed parse(WebSearchClient.SearchResponse response,
                 List<ReviewItem.OnlineEvidence> priorEvidence,
                 Map<String, WebSearchClient.Citation> citationPoolByCanon,
                 Map<String, WebPageFetcher.FetchedPage> fetchedPagesByCanon,
                 boolean allowNativePoolWithoutBody) throws LlmClient.LlmException {
        JsonObject json = LlmAlbumJudge.parseJsonObject(response.getText());
        if (json == null) throw new LlmClient.LlmException("llm.web.search.response.not.json");
        Parsed result=new Parsed();
        result.needsSecondRound=bool(json,"needs_second_round");
        List<ReviewItem.OnlineEvidence> evidencePool = new ArrayList<>();
        if (priorEvidence != null) {
            for (ReviewItem.OnlineEvidence prior : priorEvidence) {
                if (prior != null && prior.getUrl() != null && !prior.getUrl().isBlank()) {
                    evidencePool.add(prior);
                }
            }
        }
        List<ReviewItem.OnlineEvidence> roundEvidence = toEvidence(response.getCitations());
        result.clues.addAll(roundEvidence);
        Set<String> seenPoolUrls = new HashSet<>();
        for (ReviewItem.OnlineEvidence e : evidencePool) seenPoolUrls.add(WebSearchClient.canonicalizeUrl(e.getUrl()));
        for (ReviewItem.OnlineEvidence e : roundEvidence) {
            if (e.getUrl() != null && seenPoolUrls.add(WebSearchClient.canonicalizeUrl(e.getUrl()))) {
                evidencePool.add(e);
            }
        }
        Map<String, WebSearchClient.Citation> mergedPool = new LinkedHashMap<>();
        if (citationPoolByCanon != null) {
            mergedPool.putAll(citationPoolByCanon);
        }
        for (ReviewItem.OnlineEvidence e : evidencePool) {
            String canon = WebSearchClient.canonicalizeUrl(e.getUrl());
            if (!canon.isEmpty() && !mergedPool.containsKey(canon)) {
                mergedPool.put(canon, WebSearchClient.Citation.of(e.getUrl(), e.getTitle(), e.getSnippet()));
            }
        }
        JsonArray candidates=arr(json,"candidates");
        if(candidates!=null) for(JsonElement el:candidates){
            if(result.candidates.size()>=5) break;
            if(!el.isJsonObject()) continue;
            ReviewItem.OnlineCandidate c=parseCandidate(el.getAsJsonObject(), evidencePool,
                mergedPool, fetchedPagesByCanon, allowNativePoolWithoutBody);
            if(c.isOfficialCandidate()) result.candidates.add(c);
        }
        JsonArray clues=arr(json,"clues");
        if(clues!=null) for(JsonElement el:clues){
            if(!el.isJsonObject()) continue;
            JsonObject o=el.getAsJsonObject(); ReviewItem.OnlineEvidence e=new ReviewItem.OnlineEvidence();
            e.setUrl(str(o,"url"));e.setTitle(str(o,"title"));e.setSnippet(str(o,"snippet"));e.setRetrievedAt(System.currentTimeMillis());e.setReliability("LOW");result.clues.add(e);
        }
        return result;
    }

    private static JsonArray arr(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : null;
    }

    private ReviewItem.OnlineCandidate parseCandidate(
            JsonObject o,
            List<ReviewItem.OnlineEvidence> allEvidence,
            Map<String, WebSearchClient.Citation> citationPoolByCanon,
            Map<String, WebPageFetcher.FetchedPage> fetchedPagesByCanon,
            boolean allowNativePoolWithoutBody) {
        ReviewItem.OnlineCandidate c=new ReviewItem.OnlineCandidate();c.setId(UUID.randomUUID().toString());
        c.setTitle(str(o,"title"));c.setArtist(str(o,"artist"));c.setAlbumArtist(str(o,"album_artist"));
        c.setArtists(new ArrayList<>(resolveStructuredArtists(c.getArtist(), strList(o, "artists"), List.of(), null)));
        c.setAlbumArtists(new ArrayList<>(resolveStructuredArtists(c.getAlbumArtist(), strList(o, "album_artists"), c.getArtists(), c.getArtist())));
        c.setReleaseDate(str(o,"release_date"));c.setEdition(str(o,"edition"));c.setCountry(str(o,"country"));c.setLabel(str(o,"label"));
        c.setCatalogNumber(str(o,"catalog_number"));c.setCoverUrl(str(o,"cover_url"));c.setConfidence(normalizeConfidence(number(o,"confidence")));c.setReason(str(o,"reason"));
        Set<String> canonicalSourceUrls = new HashSet<>();
        JsonArray urls = arr(o, "source_urls");
        if (urls != null) {
            for (JsonElement u : urls) {
                if (u.isJsonPrimitive()) {
                    String canon = WebSearchClient.canonicalizeUrl(u.getAsString());
                    if (!canon.isEmpty()) {
                        canonicalSourceUrls.add(canon);
                    }
                }
            }
        }
        // 模型未标注 source_urls 时不得继承全部检索证据：否则只要本轮搜索出现过任意一个
        // HIGH 域名，候选就会无条件通过来源门槛，恰好在模型输出不规范时失效。
        // 按规范化 URL 匹配，兼顾剥离 ?utm_source=openai / 尾斜杠差异，同时坚决拦截未入池的捏造 URL。
        Set<String> matchedCanonical = new HashSet<>();
        for (ReviewItem.OnlineEvidence e : allEvidence) {
            String canon = WebSearchClient.canonicalizeUrl(e.getUrl());
            if (!canon.isEmpty() && canonicalSourceUrls.contains(canon) && matchedCanonical.add(canon)) {
                c.getSources().add(e);
            }
        }
        long high=c.getSources().stream().filter(e->"HIGH".equals(e.getReliability())).count();
        long mediumDomains=c.getSources().stream().filter(e->"MEDIUM".equals(e.getReliability())).map(ReviewItem.OnlineEvidence::getDomain).distinct().count();
        c.setOfficialCandidate(high>=1||mediumDomains>=2);
        boolean applyProbable = config == null || config.isOnlineCreditsApplyProbable();
        String artistHint = c.getAlbumArtist() != null && !c.getAlbumArtist().isBlank() ? c.getAlbumArtist() : c.getArtist();
        JsonArray tracks=arr(o,"tracks");
        if(tracks!=null) {
            for(JsonElement te:tracks){
                if(!te.isJsonObject()) continue;
                JsonObject t=te.getAsJsonObject();
                ReviewItem.OnlineTrack track=new ReviewItem.OnlineTrack();
                track.setDiscNo(integer(t,"disc"));
                track.setTrackNo(integer(t,"track"));
                track.setTitle(str(t,"title"));
                track.setArtist(str(t,"artist"));
                track.setArtists(new ArrayList<>(resolveStructuredArtists(track.getArtist(), strList(t, "artists"), c.getArtists(), c.getArtist())));
                track.setComposers(new ArrayList<>(distinctCleanNames(strList(t, "composers"))));
                track.setLyricists(new ArrayList<>(distinctCleanNames(strList(t, "lyricists"))));
                track.setArrangers(new ArrayList<>(distinctCleanNames(strList(t, "arrangers"))));
                track.setDuration(integerNullable(t,"duration"));
                ReviewItem.TrackCreditDetail detail = CreditEvidenceVerifier.parseCreditDetailJson(t);
                if (detail != null) {
                    track.setCreditDetail(detail);
                    CreditEvidenceVerifier.verifyTrack(track, artistHint, fetchedPagesByCanon,
                        citationPoolByCanon, allowNativePoolWithoutBody, applyProbable);
                } else {
                    // 新解析曲目省略 credit_detail 时不留空绕过核验器：降级为结构化明细并执行伴奏/特殊版本护栏
                    CreditEvidenceVerifier.buildUnverifiedFlatFallbackDetail(track, applyProbable);
                }
                c.getTracks().add(track);
            }
        }
        return c;
    }

    private static List<String> distinctCleanNames(List<String> raw) {
        List<String> cleaned = ArtistCreditSupport.cleanNames(raw);
        if (cleaned.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new java.util.LinkedHashSet<>(cleaned));
    }

    private List<String> resolveStructuredArtists(String display, List<String> explicit,
                                                  List<String> fallbackArtists, String fallbackDisplay) {
        List<String> cleaned = ArtistCreditSupport.cleanNames(explicit);
        if (!cleaned.isEmpty()) {
            return cleaned;
        }
        if (display == null || display.isBlank()) {
            return List.of();
        }
        String trimmed = display.trim();
        if (fallbackArtists != null && !fallbackArtists.isEmpty()
                && fallbackDisplay != null && trimmed.equalsIgnoreCase(fallbackDisplay.trim())) {
            return List.copyOf(fallbackArtists);
        }
        if (artistSplitter != null) {
            LlmArtistSplitter.SplitResult split = artistSplitter.split(trimmed);
            if (split.hasStructuredArtists()) {
                return split.artists();
            }
        }
        return List.of();
    }

    /** 提前算好逐曲匹配与覆盖率，人工确认前就能在界面上看到到底匹上了几首。 */
    private void matchAll(Parsed parsed, List<File> files, List<Integer> durations) {
        for (ReviewItem.OnlineCandidate candidate : parsed.candidates) {
            trackMatcher.match(candidate, files, durations);
        }
    }

    private static void addCitationsToPool(Map<String, WebSearchClient.Citation> pool,
                                           List<WebSearchClient.Citation> citations) {
        if (pool == null || citations == null) return;
        for (WebSearchClient.Citation c : citations) {
            if (c == null || c.getUrl() == null || c.getUrl().isBlank()) continue;
            String canon = WebSearchClient.canonicalizeUrl(c.getUrl());
            if (canon.isEmpty()) continue;
            WebSearchClient.Citation prev = pool.get(canon);
            if (prev == null) {
                pool.put(canon, c);
            } else if (c.isOpenedPage() && !prev.isOpenedPage()) {
                pool.put(canon, WebSearchClient.Citation.of(
                    prev.getUrl(),
                    prev.getTitle() != null && !prev.getTitle().isBlank() ? prev.getTitle() : c.getTitle(),
                    prev.getSnippet() != null && !prev.getSnippet().isBlank() ? prev.getSnippet() : c.getSnippet(),
                    true));
            }
        }
    }

    private static boolean hasAnyCreditDetail(Parsed parsed) {
        if (parsed == null || parsed.candidates == null) return false;
        for (ReviewItem.OnlineCandidate c : parsed.candidates) {
            if (c.getTracks() == null) continue;
            for (ReviewItem.OnlineTrack t : c.getTracks()) {
                if (t != null && t.getCreditDetail() != null) return true;
            }
        }
        return false;
    }

    /** 原生模式下对前 3 个被曲目证据引用的页面执行零费用直连正文复核，成功则升级为正文核验、失配则拦截幻觉。 */
    private void verifyNativeCandidatesWithDirectFetch(Parsed parsed,
                                                       Map<String, WebSearchClient.Citation> citationPoolByCanon)
            throws InterruptedException {
        if (webPageFetcher == null || parsed == null || parsed.candidates.isEmpty()) return;
        Map<String, String> urlsToFetch = new LinkedHashMap<>();
        String artistHint = "";
        for (ReviewItem.OnlineCandidate c : parsed.candidates) {
            if (artistHint.isEmpty()) {
                artistHint = c.getAlbumArtist() != null && !c.getAlbumArtist().isBlank()
                    ? c.getAlbumArtist() : nz(c.getArtist());
            }
            if (c.getTracks() == null) continue;
            for (ReviewItem.OnlineTrack t : c.getTracks()) {
                ReviewItem.TrackCreditDetail d = t == null ? null : t.getCreditDetail();
                if (d == null) continue;
                collectEvidenceUrls(d.getLyricists(), urlsToFetch);
                collectEvidenceUrls(d.getComposers(), urlsToFetch);
                collectEvidenceUrls(d.getArrangers(), urlsToFetch);
                if (urlsToFetch.size() >= MAX_NATIVE_DIRECT_VERIFY_PAGES) break;
            }
            if (urlsToFetch.size() >= MAX_NATIVE_DIRECT_VERIFY_PAGES) break;
        }
        if (urlsToFetch.isEmpty()) return;

        Map<String, WebPageFetcher.FetchedPage> fetched = new LinkedHashMap<>();
        for (String url : urlsToFetch.values()) {
            checkInterrupted();
            try {
                WebPageFetcher.FetchedPage p = webPageFetcher.fetchDirectOnly(url, artistHint);
                if (p != null && p.ok()) {
                    fetched.put(p.canonicalUrl(), p);
                }
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Exception ignored) {
                // 免费直连复核失败时静默降级为原生服务端来源池核验，绝不阻塞主流程
            }
        }
        if (fetched.isEmpty()) return;

        boolean applyProbable = config == null || config.isOnlineCreditsApplyProbable();
        for (ReviewItem.OnlineCandidate c : parsed.candidates) {
            String hint = c.getAlbumArtist() != null && !c.getAlbumArtist().isBlank()
                ? c.getAlbumArtist() : nz(c.getArtist());
            if (c.getTracks() == null) continue;
            for (ReviewItem.OnlineTrack t : c.getTracks()) {
                if (t != null && t.getCreditDetail() != null) {
                    CreditEvidenceVerifier.verifyTrack(t, hint, fetched, citationPoolByCanon, true, applyProbable);
                }
            }
        }
    }

    private static void collectEvidenceUrls(ReviewItem.CreditFieldResult field, Map<String, String> out) {
        if (field == null || field.getEvidence() == null) return;
        for (ReviewItem.CreditEvidenceItem ev : field.getEvidence()) {
            if (out.size() >= MAX_NATIVE_DIRECT_VERIFY_PAGES) return;
            if (ev != null && ev.getUrl() != null && !ev.getUrl().isBlank()) {
                String canon = WebSearchClient.canonicalizeUrl(ev.getUrl());
                if (!canon.isEmpty() && !out.containsKey(canon)) {
                    out.put(canon, ev.getUrl());
                }
            }
        }
    }

    private static ReviewItem.OnlineCandidate selectBestCandidate(List<ReviewItem.OnlineCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        return candidates.stream()
            .max(Comparator
                .comparingLong((ReviewItem.OnlineCandidate c) -> c.getTracks() == null ? 0L
                    : c.getTracks().stream().filter(t -> t != null && t.getMatchedFilePath() != null
                        && t.getMatchConfidence() >= OnlineTrackMatcher.MIN_CONFIDENCE).count())
                .thenComparingDouble(ReviewItem.OnlineCandidate::getConfidence))
            .orElse(candidates.get(0));
    }

    private static boolean allTracksLackCredits(ReviewItem.OnlineCandidate candidate) {
        if (candidate == null || candidate.getTracks() == null || candidate.getTracks().isEmpty()) return false;
        for (ReviewItem.OnlineTrack t : candidate.getTracks()) {
            if (t == null) continue;
            if ((t.getComposers() != null && !t.getComposers().isEmpty())
                    || (t.getLyricists() != null && !t.getLyricists().isEmpty())
                    || (t.getArrangers() != null && !t.getArrangers().isEmpty())) {
                return false;
            }
            if (t.getCreditDetail() != null) {
                ReviewItem.TrackCreditDetail d = t.getCreditDetail();
                if (hasNonEmptyRole(d.getComposers()) || hasNonEmptyRole(d.getLyricists()) || hasNonEmptyRole(d.getArrangers())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasNonEmptyRole(ReviewItem.CreditFieldResult f) {
        if (f == null) return false;
        if (f.getStatus() == ReviewItem.CreditStatus.CONFLICT) return true;
        return f.getNames() != null && !f.getNames().isEmpty();
    }

    private String buildNativeCreditsFollowUpPrompt(ReviewItem.OnlineCandidate candidate) {
        StringBuilder sb = new StringBuilder();
        sb.append("Find the track-level songwriter/composer/lyricist/arranger credits for this exact confirmed release.\n")
          .append("ALBUM: ").append(nz(candidate.getTitle())).append('\n')
          .append("ARTIST: ").append(nz(candidate.getAlbumArtist() != null ? candidate.getAlbumArtist() : candidate.getArtist())).append('\n')
          .append("RELEASE_DATE: ").append(nz(candidate.getReleaseDate())).append('\n')
          .append("TRACKS:\n");
        int i = 1;
        for (ReviewItem.OnlineTrack t : candidate.getTracks()) {
            sb.append(i++).append(". disc=").append(t.getDiscNo()).append(" track=").append(t.getTrackNo())
              .append(" title=\"").append(nz(t.getTitle())).append("\"\n");
        }
        sb.append("Search official artist/label discography, press releases, lyric databases (Uta-Net/UtaTen/Lyrical Nonsense), YouTube descriptions, and VGMdb/OTOTOY.\n")
          .append("Return ONLY JSON: {\"tracks\":[{\"disc\":number,\"track\":number,\"title\":string,\"version_type\":string,")
          .append("\"credit_detail\":{\"lyricists\":Field,\"composers\":Field,\"arrangers\":Field}}]}\n")
          .append("where Field={\"names\":[string],\"status\":\"verified|probable|conflict|not_found|not_searched\",")
          .append("\"evidence\":[{\"url\":string,\"source_type\":string,\"quote\":string,\"version_match\":\"exact_version|original_only|album_level_only|mismatch|unknown\"}],")
          .append("\"candidates\":[{\"names\":[string],\"urls\":[string],\"quotes\":[string]}],\"notes\":string|null}.\n")
          .append("Follow all anti-hallucination, Instrumental no-lyricist, Remix arranger anti-inheritance, and Remixer!=Arranger rules.");
        return sb.toString();
    }

    private boolean applyNativeCreditsFollowUp(ReviewItem.OnlineCandidate targetCandidate,
                                               WebSearchClient.SearchResponse response,
                                               Map<String, WebSearchClient.Citation> citationPoolByCanon)
            throws InterruptedException {
        JsonObject json = LlmAlbumJudge.parseJsonObject(response.getText());
        if (json == null) return false;
        JsonArray tracksArr = arr(json, "tracks");
        if (tracksArr == null) {
            JsonArray cands = arr(json, "candidates");
            if (cands != null && cands.size() > 0 && cands.get(0).isJsonObject()) {
                tracksArr = arr(cands.get(0).getAsJsonObject(), "tracks");
            }
        }
        if (tracksArr == null || tracksArr.isEmpty()) return false;

        boolean applyProbable = config == null || config.isOnlineCreditsApplyProbable();
        String artistHint = targetCandidate.getAlbumArtist() != null && !targetCandidate.getAlbumArtist().isBlank()
            ? targetCandidate.getAlbumArtist() : nz(targetCandidate.getArtist());
        boolean updated = false;
        for (int i = 0; i < tracksArr.size(); i++) {
            JsonElement el = tracksArr.get(i);
            if (!el.isJsonObject()) continue;
            JsonObject to = el.getAsJsonObject();
            ReviewItem.OnlineTrack matched = matchCandidateTrack(to, targetCandidate.getTracks(), i);
            if (matched == null) continue;
            ReviewItem.TrackCreditDetail detail = CreditEvidenceVerifier.parseCreditDetailJson(to);
            if (detail != null) {
                matched.setCreditDetail(detail);
                CreditEvidenceVerifier.verifyTrack(matched, artistHint, Map.of(), citationPoolByCanon, true, applyProbable);
                updated = true;
            }
        }
        if (updated) {
            Parsed wrapper = new Parsed();
            wrapper.candidates.add(targetCandidate);
            verifyNativeCandidatesWithDirectFetch(wrapper, citationPoolByCanon);
        }
        return updated;
    }

    private static ReviewItem.OnlineTrack matchCandidateTrack(JsonObject to,
                                                              List<ReviewItem.OnlineTrack> tracks,
                                                              int fallbackIdx) {
        String title = to.has("title") && to.get("title").isJsonPrimitive() ? to.get("title").getAsString().trim() : null;
        if (to.has("track") && to.get("track").isJsonPrimitive()) {
            try {
                int tr = to.get("track").getAsInt();
                int dc = to.has("disc") && to.get("disc").isJsonPrimitive() ? to.get("disc").getAsInt() : 0;
                if (tr > 0) {
                    ReviewItem.OnlineTrack matched = null;
                    int hits = 0;
                    for (ReviewItem.OnlineTrack t : tracks) {
                        if (t.getTrackNo() == tr && (dc <= 0 || t.getDiscNo() <= 0 || t.getDiscNo() == dc)) {
                            if (title == null || title.isEmpty() || titlesCompatible(title, t.getTitle())) {
                                matched = t;
                                hits++;
                            }
                        }
                    }
                    if (hits == 1) return matched;
                }
            } catch (Exception ignored) {
            }
        }
        if (title != null && !title.isEmpty()) {
            String norm = normalizeTrackTitle(title);
            ReviewItem.OnlineTrack unique = null;
            int hits = 0;
            for (ReviewItem.OnlineTrack t : tracks) {
                if (t.getTitle() != null && normalizeTrackTitle(t.getTitle()).equals(norm)) {
                    unique = t;
                    hits++;
                }
            }
            if (hits == 1) return unique;
        }
        // 不再做无校验的位置兜底，避免错把其他曲目的幕后人员写入错误音轨
        return null;
    }

    private static boolean titlesCompatible(String a, String b) {
        String na = normalizeTrackTitle(a);
        String nb = normalizeTrackTitle(b);
        if (na.isEmpty() || nb.isEmpty()) return true;
        return na.equals(nb) || na.contains(nb) || nb.contains(na);
    }

    private static String normalizeTrackTitle(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\s\\u3000\\p{Punct}]+", "");
    }

    private Map<String, MusicMetadata> collectExistingBaseCredits(ReviewItem item, List<File> files) {
        Map<String, MusicMetadata> byPath = new LinkedHashMap<>();
        Map<String, MusicMetadata> queuedByPath = new LinkedHashMap<>();
        if (item != null && item.getFiles() != null) {
            for (ReviewItem.FileEntry fe : item.getFiles()) {
                if (fe != null && fe.getOriginalPath() != null && fe.getMetadata() != null) {
                    queuedByPath.put(new File(fe.getOriginalPath()).getAbsolutePath(), fe.getMetadata());
                }
            }
        }
        if (files != null) {
            for (File f : files) {
                if (f == null) continue;
                String abs = f.getAbsolutePath();
                MusicMetadata local = tagWriter != null ? tagWriter.readTags(f) : null;
                MusicMetadata queued = queuedByPath.get(abs);
                MusicMetadata mb = MetadataUtils.extractQueuedMusicBrainzCredits(queued, local);
                MetadataUtils.ResolvedTrackCredits resolved = MetadataUtils.resolveOnlineTrackCredits(
                    null, mb, local, null, false, true);
                MusicMetadata merged = new MusicMetadata();
                MetadataUtils.applyResolvedTrackCredits(resolved, merged);
                byPath.put(abs, merged);
            }
        }
        return byPath;
    }

    /** 搜索返回时条目可能已被人工处理：只在仍待确认时写入，返回是否写入 */
    /** 搜索返回时条目可能已被人工处理：检查与写入在队列锁内一次完成，返回是否写入 */
    private boolean save(ReviewItem item,WebSearchClient.SearchResponse response,Parsed parsed,String hash){
        // 深拷贝：第二轮 merge / matchAll 会原地修改 parsed 里的候选和逐曲匹配，
        // 挂到队列条目上的对象图必须与之完全隔离，不能在锁外被改动
        List<ReviewItem.OnlineCandidate> candidates=deepCopy(parsed.candidates,ReviewItem.OnlineCandidate[].class);
        List<ReviewItem.OnlineEvidence> clues=deepCopy(parsed.clues,ReviewItem.OnlineEvidence[].class);
        String note=candidates.isEmpty()?"联网搜索未找到满足来源门槛的正式候选，已保存线索":"联网搜索完成，等待人工确认";
        long now=System.currentTimeMillis();
        boolean saved=reviewQueue.updatePending(item.getId(),target->{
            target.setOnlineCandidates(candidates);target.setOnlineClues(clues);target.setOnlineSearchedAt(now);
            target.setOnlineSearchProvider(response.getProvider());target.setOnlineSearchModel(response.getModel());target.setEvidenceHash(hash);target.setOnlineEvidenceStale(false);
            String existing=target.getResolutionNote();
            if(existing==null||existing.isBlank()||isOnlineSearchTemplate(existing))target.setResolutionNote(note);else appendResolutionNote(target,note);
            return true;
        });
        if(!saved)log.info("条目已被处理，丢弃联网搜索结果: {}",item.getFolderName());
        return saved;
    }
    static <T> List<T> deepCopy(List<T> source,Class<T[]> arrayType){
        if(source==null)return new ArrayList<>();
        Gson gson=new Gson();
        return new ArrayList<>(java.util.Arrays.asList(gson.fromJson(gson.toJson(source),arrayType)));
    }
    private boolean isOnlineSearchTemplate(String note){return note.startsWith("联网搜索完成")||note.startsWith("联网搜索未找到满足来源门槛");}
    private void appendResolutionNote(ReviewItem item,String note){String existing=item.getResolutionNote();item.setResolutionNote(existing==null||existing.isBlank()?note:existing+" | "+note);}
    private void merge(Parsed a, Parsed b) {
        Map<String, ReviewItem.OnlineCandidate> byKey = new LinkedHashMap<>();
        for (ReviewItem.OnlineCandidate c : a.candidates) {
            byKey.put(key(c), c);
        }
        boolean applyProbable = config == null || config.isOnlineCreditsApplyProbable();
        for (ReviewItem.OnlineCandidate incoming : b.candidates) {
            String k = key(incoming);
            ReviewItem.OnlineCandidate existing = byKey.get(k);
            if (existing == null) {
                if (a.candidates.size() < 5) {
                    a.candidates.add(incoming);
                    byKey.put(k, incoming);
                }
            } else {
                // 第二轮命中同一版本时合并新增来源与更优/冲突的逐曲幕后人员核验结果，避免丢弃次轮补充
                mergeCandidateSourcesAndCredits(existing, incoming, applyProbable);
            }
        }
    }

    private static void mergeCandidateSourcesAndCredits(ReviewItem.OnlineCandidate target,
                                                        ReviewItem.OnlineCandidate incoming,
                                                        boolean applyProbable) {
        if (incoming.getSources() != null) {
            Set<String> seen = new HashSet<>();
            for (ReviewItem.OnlineEvidence e : target.getSources()) {
                seen.add(WebSearchClient.canonicalizeUrl(e.getUrl()));
            }
            for (ReviewItem.OnlineEvidence e : incoming.getSources()) {
                String canon = WebSearchClient.canonicalizeUrl(e.getUrl());
                if (!canon.isEmpty() && seen.add(canon)) {
                    target.getSources().add(e);
                }
            }
        }
        if (target.getTracks() == null || incoming.getTracks() == null) return;
        for (ReviewItem.OnlineTrack incTrack : incoming.getTracks()) {
            if (incTrack == null || incTrack.getCreditDetail() == null) continue;
            ReviewItem.OnlineTrack matched = findMatchingTrackForMerge(target.getTracks(), incTrack);
            if (matched == null) continue;
            if (matched.getCreditDetail() == null) {
                matched.setCreditDetail(incTrack.getCreditDetail());
            } else {
                ReviewItem.TrackCreditDetail td = matched.getCreditDetail();
                ReviewItem.TrackCreditDetail id = incTrack.getCreditDetail();
                td.setLyricists(CreditEvidenceVerifier.mergeFieldResults(td.getLyricists(), id.getLyricists()));
                td.setComposers(CreditEvidenceVerifier.mergeFieldResults(td.getComposers(), id.getComposers()));
                td.setArrangers(CreditEvidenceVerifier.mergeFieldResults(td.getArrangers(), id.getArrangers()));
            }
            CreditEvidenceVerifier.applyVerifiedCreditsToTrack(matched, applyProbable);
        }
    }

    private static ReviewItem.OnlineTrack findMatchingTrackForMerge(List<ReviewItem.OnlineTrack> tracks,
                                                                    ReviewItem.OnlineTrack inc) {
        if (inc.getTrackNo() > 0) {
            ReviewItem.OnlineTrack hit = null;
            int count = 0;
            for (ReviewItem.OnlineTrack t : tracks) {
                if (t.getTrackNo() == inc.getTrackNo()
                        && (inc.getDiscNo() <= 0 || t.getDiscNo() <= 0 || t.getDiscNo() == inc.getDiscNo())
                        && titlesCompatible(t.getTitle(), inc.getTitle())) {
                    hit = t;
                    count++;
                }
            }
            if (count == 1) return hit;
        }
        String norm = normalizeTrackTitle(inc.getTitle());
        if (!norm.isEmpty()) {
            ReviewItem.OnlineTrack hit = null;
            int count = 0;
            for (ReviewItem.OnlineTrack t : tracks) {
                if (normalizeTrackTitle(t.getTitle()).equals(norm)) {
                    hit = t;
                    count++;
                }
            }
            if (count == 1) return hit;
        }
        return null;
    }
    private String key(ReviewItem.OnlineCandidate c){return (nz(c.getArtist())+"|"+nz(c.getTitle())+"|"+nz(c.getReleaseDate())+"|"+nz(c.getEdition())).toLowerCase(Locale.ROOT);}
    private void mergeEvidence(List<ReviewItem.OnlineEvidence> target,List<WebSearchClient.Citation> citations){Set<String> urls=new HashSet<>();for(ReviewItem.OnlineEvidence e:target)urls.add(WebSearchClient.canonicalizeUrl(e.getUrl()));for(ReviewItem.OnlineEvidence e:toEvidence(citations))if(urls.add(WebSearchClient.canonicalizeUrl(e.getUrl())))target.add(e);}
    private List<ReviewItem.OnlineEvidence> toEvidence(List<WebSearchClient.Citation> citations){List<ReviewItem.OnlineEvidence> out=new ArrayList<>();for(WebSearchClient.Citation c:citations){ReviewItem.OnlineEvidence e=new ReviewItem.OnlineEvidence();e.setUrl(c.getUrl());e.setDomain(c.getDomain());e.setTitle(c.getTitle());e.setSnippet(c.getSnippet());e.setRetrievedAt(c.getRetrievedAt());e.setReliability(c.getReliability());out.add(e);}return out;}
    private List<File> collect(File root)throws Exception{List<File> files=new ArrayList<>();if(root.isFile())files.add(root);else try(var s=Files.walk(root.toPath())){s.filter(Files::isRegularFile).map(java.nio.file.Path::toFile).filter(f->isAudio(f)).forEach(files::add);}files.sort(Comparator.comparing(File::getAbsolutePath));return files;}
    private boolean isAudio(File f){String n=f.getName().toLowerCase();for(String ext:config.getSupportedFormats())if(n.endsWith("."+ext.toLowerCase()))return true;return false;}
    /** 供人工确认阶段重算并比对，确保搜索后文件未被改动。必须与搜索时采用同一套收集语义。 */
    public String evidenceHashForFolder(File albumRoot)throws Exception{return evidenceHash(collect(albumRoot));}
    public String evidenceHash(List<File> files)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");for(File f:files){md.update(f.getAbsolutePath().getBytes(StandardCharsets.UTF_8));md.update(Long.toString(f.length()).getBytes());md.update(Long.toString(f.lastModified()).getBytes());MusicMetadata m=tagWriter.readTags(f);if(m!=null)md.update((nz(m.getTitle())+nz(m.getArtist())+nz(m.getAlbum())+nz(m.getTrackNo())+nz(m.getDiscNo())).getBytes(StandardCharsets.UTF_8));}StringBuilder sb=new StringBuilder();for(byte b:md.digest())sb.append(String.format("%02x",b));return sb.toString();}
    static class Parsed{boolean needsSecondRound;List<ReviewItem.OnlineCandidate> candidates=new ArrayList<>();List<ReviewItem.OnlineEvidence> clues=new ArrayList<>();}
    private List<String> strList(JsonObject o, String k) {
        if (o == null || !o.has(k) || !o.get(k).isJsonArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement el : o.getAsJsonArray(k)) {
            if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                String value = el.getAsString().trim();
                if (!value.isEmpty()) out.add(value);
            }
        }
        return out;
    }
    private String str(JsonObject o,String k){return o.has(k)&&o.get(k).isJsonPrimitive()?o.get(k).getAsString():null;}private boolean bool(JsonObject o,String k){try{return o.has(k)&&o.get(k).getAsBoolean();}catch(Exception e){return false;}}private double number(JsonObject o,String k){try{return o.get(k).getAsDouble();}catch(Exception e){return 0;}}
    /** 兼容模型偶尔返回 85 而不是 0.85，同时挡住 NaN/Infinity 和越界值。 */
    private double normalizeConfidence(double value){if(!Double.isFinite(value))return 0;if(value>1&&value<=100)value/=100;return Math.max(0,Math.min(1,value));}
    private int integer(JsonObject o,String k){Integer i=integerNullable(o,k);return i==null?0:i;}private Integer integerNullable(JsonObject o,String k){try{return o.has(k)?o.get(k).getAsInt():null;}catch(Exception e){return null;}}private String nz(String s){return s==null?"":s;}
}
