package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.service.llm.LlmArtistDisambiguator;
import com.lux032.musicautotagger.service.llm.WebPageFetcher;
import com.lux032.musicautotagger.service.llm.WebSearchClient;
import com.lux032.musicautotagger.util.ArtistCreditSupport;
import com.lux032.musicautotagger.util.MetadataUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 联网识别人物级 MusicBrainz Artist MBID 三层解析编排器。
 *
 * 三层漏斗（按确定性与成本从高到低）：
 *   1. Layer 1：本地 {@link MusicBrainzArtistCache}（0 API 调用；仅唯一命中且无同名冲突标记时自动采纳）；
 *   2. Layer 2：归属于当前候选的已抓网页与引用池中的 {@code musicbrainz.org/artist/<uuid>} 外链 + {@code getArtistById} 核验；
 *   3. Layer 3：MusicBrainz {@code /ws/2/artist} 检索 + 唯一精确匹配或封闭式 {@link LlmArtistDisambiguator} 上下文消歧。
 *
 * 严格遵守单张专辑外部出站请求上限（含重试，{@code online.mbid.maxLookupsPerAlbum}）与线程中断信号，
 * 绝不要求或允许模型凭空输出 UUID，且同名多实体不会写入单条短路本地缓存。
 */
@Slf4j
public class OnlineEntityMbidResolver {

    private static final int MAX_SEARCH_CANDIDATES = 8;
    private static final int MAX_FALLBACK_SEARCH_LIMIT = 16;
    private static final int MAX_PERSISTED_CANDIDATES = 5;
    private static final int MAX_UNANCHORED_LINK_PROBES = 8;
    private static final int HIGH_SCORE_COMPETITOR_THRESHOLD = 90;

    private static final Set<String> PLACEHOLDER_KEYS = Set.of(
        "various artists", "unknown artist", "unknown", "va", "v.a.", "v.a"
    );
    private static final Set<String> NON_NAME_LATIN_PREFIXES = Set.of(
        "dj", "mc", "cv", "vo", "gt", "ba", "dr", "feat", "ft", "the", "mr", "ms", "mrs", "st"
    );
    private static final java.util.regex.Pattern TRAILING_AFFILIATION_PATTERN = java.util.regex.Pattern.compile(
        "\\s*(?:\\([^()]{1,64}\\)|（[^（）]{1,64}）|\\[[^\\[\\]]{1,64}\\]|【[^【】]{1,64}】)\\s*$");
    private static final java.util.regex.Pattern CJK_THEN_LATIN_PATTERN = java.util.regex.Pattern.compile(
        "^([\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー々・]+)\\s+([A-Za-z][A-Za-z0-9 ._'\\-]{1,48})$");
    private static final java.util.regex.Pattern LATIN_THEN_CJK_PATTERN = java.util.regex.Pattern.compile(
        "^([A-Za-z][A-Za-z0-9 ._'\\-]{1,48})\\s+([\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー々・]+)$");

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int current, int total, String personName);
    }

    public record ResolutionSummary(
        Map<String, ReviewItem.EntityMbidResolution> resolutions,
        int externalLookups,
        boolean interrupted
    ) {
        public ResolutionSummary {
            resolutions = resolutions == null ? Map.of() : resolutions;
        }
    }

    private static final class PersonTarget {
        final String normalizedKey;
        final String displayName;
        final LinkedHashSet<String> roles = new LinkedHashSet<>();
        int priorityBucket; // 1 = album_artist, 2 = track_artist, 3 = creator
        int frequency;
        final int order;

        PersonTarget(String normalizedKey, String displayName, int priorityBucket, int order) {
            this.normalizedKey = normalizedKey;
            this.displayName = displayName;
            this.priorityBucket = priorityBucket;
            this.order = order;
        }
    }

    private static final class PageLinkRef {
        final String mbid;
        final LinkedHashSet<String> anchors = new LinkedHashSet<>();

        PageLinkRef(String mbid) {
            this.mbid = mbid;
        }
    }

    private record LinkProbeOutcome(Optional<MusicBrainzClient.ArtistMatch> match, boolean verifiedAttemptCompleted) {}

    private final MusicConfig config;
    private final MusicBrainzClient musicBrainzClient;
    private final MusicBrainzArtistCache artistCache;
    private final LlmArtistDisambiguator disambiguator;

    public OnlineEntityMbidResolver(MusicConfig config,
                                    MusicBrainzClient musicBrainzClient,
                                    MusicBrainzArtistCache artistCache,
                                    LlmArtistDisambiguator disambiguator) {
        this.config = config;
        this.musicBrainzClient = musicBrainzClient;
        this.artistCache = artistCache;
        this.disambiguator = disambiguator;
    }

    public ResolutionSummary resolveForCandidate(
            ReviewItem.OnlineCandidate candidate,
            Map<String, WebPageFetcher.FetchedPage> fetchedPages,
            Map<String, WebSearchClient.Citation> citationPool,
            ProgressCallback progress) {
        MusicConfig.OnlineMbidSettings snapshot = config != null
            ? config.getOnlineMbidSettings()
            : MusicConfig.OnlineMbidSettings.defaults();
        return resolveForCandidate(candidate, fetchedPages, citationPool, snapshot, progress);
    }

    public ResolutionSummary resolveForCandidate(
            ReviewItem.OnlineCandidate candidate,
            Map<String, WebPageFetcher.FetchedPage> fetchedPages,
            Map<String, WebSearchClient.Citation> citationPool,
            MusicConfig.OnlineMbidSettings settingsSnapshot,
            ProgressCallback progress) {
        MusicConfig.OnlineMbidSettings settings = settingsSnapshot != null
            ? settingsSnapshot
            : MusicConfig.OnlineMbidSettings.defaults();
        if (candidate == null || !settings.resolveEnabled()) {
            return new ResolutionSummary(Map.of(), 0, false);
        }

        List<PersonTarget> targets = collectTargets(candidate);
        if (targets.isEmpty()) {
            return new ResolutionSummary(Map.of(), 0, false);
        }

        int maxLookups = settings.maxLookupsPerAlbum();
        boolean llmDisambiguateEnabled = settings.llmDisambiguateEnabled();
        List<String> allPeopleNames = targets.stream().map(t -> t.displayName).toList();
        List<String> preferredRegions = config == null || config.getReleaseCountryPriority() == null
            ? List.of() : List.copyOf(config.getReleaseCountryPriority());

        Map<String, PageLinkRef> sessionLinks = collectCandidateScopedMbLinks(candidate, fetchedPages, citationPool);
        Map<String, Optional<MusicBrainzClient.ArtistMatch>> verifiedLinkCache = new LinkedHashMap<>();
        Map<String, ReviewItem.EntityMbidResolution> results = new LinkedHashMap<>();

        int[] lookupBudgetUsed = new int[] { 0 };
        boolean interrupted = false;

        for (int i = 0; i < targets.size(); i++) {
            PersonTarget target = targets.get(i);
            if (Thread.currentThread().isInterrupted()) {
                interrupted = true;
                markRemainingSkipped(targets, i, results, "搜索已取消，未继续查询");
                break;
            }
            if (progress != null) {
                try {
                    progress.onProgress(i + 1, targets.size(), target.displayName);
                } catch (Exception ignored) {
                }
            }
            if (Thread.currentThread().isInterrupted()) {
                interrupted = true;
                markRemainingSkipped(targets, i, results, "搜索已取消，未继续查询");
                break;
            }
            try {
                ReviewItem.EntityMbidResolution resolved = resolveSinglePerson(
                    target, candidate, preferredRegions, allPeopleNames,
                    sessionLinks, verifiedLinkCache, lookupBudgetUsed, maxLookups, llmDisambiguateEnabled);
                results.put(target.normalizedKey, resolved);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                interrupted = true;
                markRemainingSkipped(targets, i, results, "搜索已取消，未继续查询");
                break;
            }
        }

        return new ResolutionSummary(results, lookupBudgetUsed[0], interrupted);
    }

    private ReviewItem.EntityMbidResolution resolveSinglePerson(
            PersonTarget target,
            ReviewItem.OnlineCandidate candidate,
            List<String> preferredRegions,
            List<String> allPeopleNames,
            Map<String, PageLinkRef> sessionLinks,
            Map<String, Optional<MusicBrainzClient.ArtistMatch>> verifiedLinkCache,
            int[] lookupBudgetUsed,
            int maxLookups,
            boolean llmDisambiguateEnabled) throws InterruptedException {

        // ==================== Layer 1: 本地缓存（零外部 API 开销） ====================
        List<MusicBrainzClient.ArtistMatch> localHomonyms = new ArrayList<>();
        boolean localHomonymConflict = false;
        if (artistCache != null) {
            MusicBrainzArtistCache.NameLookupResult local = artistCache.lookupByName(target.displayName);
            if (local.isUniqueHit()) {
                MusicBrainzArtistCache.CachedArtist hit = local.uniqueArtist();
                return buildResolved(target, cachedToMatch(hit), ReviewItem.EntityMbidSource.LOCAL_CACHE,
                    1.0, "命中本地已验证艺人缓存", List.of(cachedToMatch(hit)));
            }
            if (local.hasHomonymConflict()) {
                localHomonymConflict = true;
                for (MusicBrainzArtistCache.CachedArtist ca : local.matches()) {
                    localHomonyms.add(cachedToMatch(ca));
                }
            }
        }

        // ==================== Layer 2: 当前候选归属网页/引用池 MB 外链提取 + API 回查 ====================
        Map<String, MusicBrainzClient.ArtistMatch> verifiedFromLinksByMbid = new LinkedHashMap<>();
        boolean layer2VerificationIncomplete = false;
        if (!sessionLinks.isEmpty()) {
            List<PageLinkRef> orderedLinks = orderLinksForPerson(target, sessionLinks);
            int unanchoredChecked = 0;
            for (PageLinkRef ref : orderedLinks) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("cancelled");
                }
                boolean anchorMatches = anchorMatchesTarget(ref, target.normalizedKey);
                if (!anchorMatches && unanchoredChecked >= MAX_UNANCHORED_LINK_PROBES) {
                    layer2VerificationIncomplete = true;
                    continue;
                }
                if (!anchorMatches) {
                    unanchoredChecked++;
                }
                LinkProbeOutcome probe = resolveLinkMbid(
                    ref.mbid, verifiedLinkCache, lookupBudgetUsed, maxLookups);
                if (!probe.verifiedAttemptCompleted()) {
                    layer2VerificationIncomplete = true;
                    continue;
                }
                if (probe.match().isPresent() && artistMatchesPersonName(probe.match().get(), target.normalizedKey)) {
                    MusicBrainzClient.ArtistMatch hit = probe.match().get();
                    verifiedFromLinksByMbid.merge(hit.mbid(), hit, OnlineEntityMbidResolver::mergeArtistMatch);
                }
            }
            List<MusicBrainzClient.ArtistMatch> verifiedFromLinks = new ArrayList<>(verifiedFromLinksByMbid.values());
            if (verifiedFromLinks.size() == 1 && !layer2VerificationIncomplete) {
                MusicBrainzClient.ArtistMatch hit = verifiedFromLinks.get(0);
                if (artistCache != null && !localHomonymConflict) {
                    artistCache.recordArtist(hit.mbid(), hit.name(), hit.sortName(), hit.disambiguation(),
                        hit.country(), hit.type(), hit.aliases(), List.of(target.displayName), "WEB_LINK_VERIFIED");
                }
                return buildResolved(target, hit, ReviewItem.EntityMbidSource.WEB_LINK_VERIFIED,
                    0.98, "从联网抓取页面外链核验命中", List.of(hit));
            }
        }

        List<MusicBrainzClient.ArtistMatch> verifiedFromLinks = new ArrayList<>(verifiedFromLinksByMbid.values());

        // ==================== Layer 3: MusicBrainz /ws/2/artist 检索 + 封闭式消歧 ====================
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("cancelled");
        }
        if (musicBrainzClient == null) {
            return fallbackWithoutClient(target, mergeMatchesByMbid(verifiedFromLinks, localHomonyms),
                localHomonymConflict || layer2VerificationIncomplete);
        }
        if (lookupBudgetUsed[0] >= maxLookups) {
            List<MusicBrainzClient.ArtistMatch> known = mergeMatchesByMbid(verifiedFromLinks, localHomonyms);
            if (!known.isEmpty()) {
                return buildAmbiguous(target, known, "已达本专辑查询上限，保留已知候选供人工点选");
            }
            return buildSkipped(target, "已达到本专辑人物级 MBID 查询上限");
        }

        List<MusicBrainzClient.ArtistMatch> searched;
        try {
            searched = searchArtistsWithinBudget(target.displayName, lookupBudgetUsed, maxLookups);
        } catch (InterruptedException ie) {
            throw ie;
        } catch (MusicBrainzClient.BudgetExhaustedException exhausted) {
            List<MusicBrainzClient.ArtistMatch> known = mergeMatchesByMbid(verifiedFromLinks, localHomonyms);
            return known.isEmpty()
                ? buildSkipped(target, "已达到本专辑人物级 MBID 查询上限")
                : buildAmbiguous(target, known, "已达本专辑查询上限，保留已知候选供人工点选");
        } catch (IOException e) {
            log.warn("查询 MusicBrainz Artist 失败 ({}): {}", target.displayName, e.getMessage());
            List<MusicBrainzClient.ArtistMatch> known = mergeMatchesByMbid(verifiedFromLinks, localHomonyms);
            return known.isEmpty()
                ? buildNotFound(target, "MusicBrainz 查询失败")
                : buildAmbiguous(target, known, "MusicBrainz 查询失败，保留已有候选");
        }

        List<MusicBrainzClient.ArtistMatch> merged = mergeMatchesByMbid(searched, verifiedFromLinks, localHomonyms);

        List<MusicBrainzClient.ArtistMatch> exactMatches = new ArrayList<>();
        List<MusicBrainzClient.ArtistMatch> nearMatches = new ArrayList<>();
        for (MusicBrainzClient.ArtistMatch match : merged) {
            if (artistMatchesPersonName(match, target.normalizedKey)) {
                exactMatches.add(match);
            } else if (match.score() >= 85) {
                nearMatches.add(match);
            }
        }

        // 受控兜底：仅当原名首查既无精确命中也无高分候选（>=85）时，对括号所属后缀（如 (ninelive.)）
        // 或双语连写署名（如 雨良 Amala）执行最多 1 次合并变体查询；兜底结果绝不盲目走 MB_SEARCH_EXACT 自动绑定。
        boolean usedFallbackSearch = false;
        List<MusicBrainzClient.ArtistMatch> fallbackCandidates = new ArrayList<>();
        if (exactMatches.isEmpty() && nearMatches.isEmpty() && lookupBudgetUsed[0] < maxLookups) {
            List<String> fallbackVariants = buildFallbackSearchVariants(target.displayName);
            if (!fallbackVariants.isEmpty()) {
                usedFallbackSearch = true;
                try {
                    List<MusicBrainzClient.ArtistMatch> rawFallback =
                        searchArtistsAnyWithinBudget(fallbackVariants, lookupBudgetUsed, maxLookups);
                    Set<String> variantKeys = new LinkedHashSet<>();
                    for (String variant : fallbackVariants) {
                        String vk = MusicBrainzArtistCache.normalizePersonKey(variant);
                        if (!vk.isEmpty()) {
                            variantKeys.add(vk);
                        }
                    }
                    for (MusicBrainzClient.ArtistMatch match : mergeMatchesByMbid(rawFallback)) {
                        if (countMatchedVariantKeys(match, variantKeys) > 0 || match.score() >= 85) {
                            fallbackCandidates.add(match);
                        }
                    }
                    // 同时匹配多个变体键（如 name="雨良" 且 sortName="Amala"）的实体排在最前，避免被同名竞争者挤出上限
                    fallbackCandidates.sort(Comparator
                        .comparingInt((MusicBrainzClient.ArtistMatch m) -> countMatchedVariantKeys(m, variantKeys)).reversed()
                        .thenComparing(Comparator.comparingInt(MusicBrainzClient.ArtistMatch::score).reversed()));
                } catch (InterruptedException ie) {
                    throw ie;
                } catch (MusicBrainzClient.BudgetExhaustedException exhausted) {
                    log.debug("变体兜底查询触及预算上限 ({})", target.displayName);
                } catch (IOException e) {
                    log.debug("MusicBrainz Artist 变体兜底查询失败 ({}): {}", target.displayName, e.getMessage());
                }
            }
        }

        boolean hasHighScoreCompetitor = nearMatches.stream()
            .anyMatch(m -> m.score() >= HIGH_SCORE_COMPETITOR_THRESHOLD);

        // Case 3a: 唯一精确匹配、无本地同名冲突且无其他 score>=90 的竞争者（仅限原名精确命中，不含兜底变体）
        if (exactMatches.size() == 1 && !localHomonymConflict && !hasHighScoreCompetitor) {
            MusicBrainzClient.ArtistMatch only = exactMatches.get(0);
            if (artistCache != null) {
                artistCache.recordArtist(only.mbid(), only.name(), only.sortName(), only.disambiguation(),
                    only.country(), only.type(), only.aliases(), List.of(target.displayName), "MB_SEARCH_EXACT");
            }
            return buildResolved(target, only, ReviewItem.EntityMbidSource.MB_SEARCH_EXACT,
                0.95, "MusicBrainz Artist 检索唯一精确匹配",
                mergeMatchesByMbid(List.of(only), nearMatches));
        }

        // Case 3b: 存在多实体精确同名、本地同名冲突、高分竞争者或受控变体兜底候选 -> 交由封闭式 LLM 消歧或人工点选
        List<MusicBrainzClient.ArtistMatch> homonymGroup = mergeMatchesByMbid(exactMatches, localHomonyms);
        boolean isHomonymConflict = exactMatches.size() > 1 || localHomonymConflict || homonymGroup.size() > 1;
        if (isHomonymConflict && artistCache != null && homonymGroup.size() >= 2) {
            List<MusicBrainzArtistCache.CachedArtist> cachedHomonyms = homonymGroup.stream()
                .limit(MAX_PERSISTED_CANDIDATES)
                .map(m -> m.toCachedArtist("MB_HOMONYM_INDEX"))
                .toList();
            artistCache.recordHomonymCandidates(target.displayName, cachedHomonyms);
        }

        List<MusicBrainzClient.ArtistMatch> disambiguationPool = mergeMatchesByMbid(
            exactMatches, localHomonyms, nearMatches, fallbackCandidates);
        if (disambiguationPool.isEmpty()) {
            return buildNotFound(target, null);
        }

        List<MusicBrainzClient.ArtistMatch> boundedPool = disambiguationPool.stream()
            .limit(MAX_SEARCH_CANDIDATES)
            .toList();
        if (llmDisambiguateEnabled && disambiguator != null && disambiguator.isAvailable()) {
            List<String> coCreators = allPeopleNames.stream()
                .filter(n -> !MusicBrainzArtistCache.normalizePersonKey(n).equals(target.normalizedKey))
                .toList();
            LlmArtistDisambiguator.AlbumContext ctx = new LlmArtistDisambiguator.AlbumContext(
                candidate.getTitle(),
                candidate.getReleaseDate(),
                preferredRegions,
                List.copyOf(target.roles),
                coCreators
            );
            LlmArtistDisambiguator.DisambiguationResult decision =
                disambiguator.disambiguate(target.displayName, ctx, boundedPool);
            if (decision.isAccepted()) {
                // 若存在同名多实体冲突或候选池内存在与所选实体同名竞争者，严禁写入单条短路唯一索引
                MusicBrainzClient.ArtistMatch picked = decision.chosen();
                String pickedNameKey = MusicBrainzArtistCache.normalizePersonKey(picked.name());
                boolean pickedNameHasCompetitor = disambiguationPool.stream()
                    .anyMatch(m -> !m.mbid().equals(picked.mbid())
                        && MusicBrainzArtistCache.normalizePersonKey(m.name()).equals(pickedNameKey));
                if (artistCache != null && !isHomonymConflict && !pickedNameHasCompetitor) {
                    artistCache.recordArtist(picked.mbid(), picked.name(), picked.sortName(), picked.disambiguation(),
                        picked.country(), picked.type(), picked.aliases(), List.of(), "MB_SEARCH_LLM");
                }
                return buildResolved(target, picked,
                    ReviewItem.EntityMbidSource.MB_SEARCH_LLM_DISAMBIGUATED,
                    decision.confidence(), decision.reason(), boundedPool);
            }
            return buildAmbiguous(target, boundedPool,
                decision.reason() != null ? decision.reason() : "同名或近似候选置信度不足，等待人工点选");
        }

        return buildAmbiguous(target, boundedPool, usedFallbackSearch
            ? "去括号/双语变体召回候选，等待人工点选"
            : "存在多个同名或近似候选，等待人工点选");
    }

    private List<MusicBrainzClient.ArtistMatch> searchArtistsWithinBudget(
            String queryName, int[] lookupBudgetUsed, int maxLookups)
            throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("cancelled");
        }
        if (lookupBudgetUsed[0] >= maxLookups) {
            throw new MusicBrainzClient.BudgetExhaustedException();
        }
        int before = lookupBudgetUsed[0];
        AutoCloseable scope = musicBrainzClient.withAttemptBudget(() -> {
            if (lookupBudgetUsed[0] >= maxLookups) {
                return false;
            }
            lookupBudgetUsed[0]++;
            return true;
        });
        try {
            List<MusicBrainzClient.ArtistMatch> result = musicBrainzClient.searchArtists(queryName, MAX_SEARCH_CANDIDATES);
            // 兼容单测直接覆写 searchArtists 而未走 executeRequest 的场景
            if (lookupBudgetUsed[0] == before) {
                lookupBudgetUsed[0]++;
            }
            return result;
        } finally {
            closeQuietly(scope);
        }
    }

    private List<MusicBrainzClient.ArtistMatch> searchArtistsAnyWithinBudget(
            List<String> variants, int[] lookupBudgetUsed, int maxLookups)
            throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("cancelled");
        }
        if (lookupBudgetUsed[0] >= maxLookups) {
            throw new MusicBrainzClient.BudgetExhaustedException();
        }
        int before = lookupBudgetUsed[0];
        AutoCloseable scope = musicBrainzClient.withAttemptBudget(() -> {
            if (lookupBudgetUsed[0] >= maxLookups) {
                return false;
            }
            lookupBudgetUsed[0]++;
            return true;
        });
        try {
            List<MusicBrainzClient.ArtistMatch> result = musicBrainzClient.searchArtistsAny(variants, MAX_FALLBACK_SEARCH_LIMIT);
            if (lookupBudgetUsed[0] == before) {
                lookupBudgetUsed[0]++;
            }
            return result;
        } finally {
            closeQuietly(scope);
        }
    }

    /**
     * 为首查 0 命中的日系/多语署名生成受控简化变体：
     *   1) 剥离末尾括号所属/社团后缀（如 "雨良 Amala (ninelive.)" -> "雨良 Amala"、"神前暁（MONACA）" -> "神前暁"）；
     *   2) 拆分明显的「CJK/假名 + 拉丁字母」双语并排署名（如 "雨良 Amala" -> "雨良", "Amala"）。
     * 绝不过度拆分普通英文多词名或 "DJ 太郎" 等常见前缀。
     */
    static List<String> buildFallbackSearchVariants(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return List.of();
        }
        String original = displayName.trim();
        String originalKey = MusicBrainzArtistCache.normalizePersonKey(original);
        LinkedHashMap<String, String> variantsByKey = new LinkedHashMap<>();

        String stripped = original;
        for (int i = 0; i < 3; i++) {
            String next = TRAILING_AFFILIATION_PATTERN.matcher(stripped).replaceFirst("").trim();
            if (next.equals(stripped) || next.isEmpty()) {
                break;
            }
            stripped = next;
        }
        addFallbackVariant(variantsByKey, stripped, originalKey);

        // 在去括号后的基底上检测双语并排署名（CJK/假名 <-> 拉丁字母）
        String base = stripped.isEmpty() ? original : stripped;
        java.util.regex.Matcher cjkFirst = CJK_THEN_LATIN_PATTERN.matcher(base);
        java.util.regex.Matcher latinFirst = cjkFirst.matches() ? null : LATIN_THEN_CJK_PATTERN.matcher(base);
        String cjkPart = null;
        String latinPart = null;
        if (cjkFirst.matches()) {
            cjkPart = cjkFirst.group(1).trim();
            latinPart = cjkFirst.group(2).trim();
        } else if (latinFirst != null && latinFirst.matches()) {
            latinPart = latinFirst.group(1).trim();
            cjkPart = latinFirst.group(2).trim();
        }
        if (cjkPart != null && latinPart != null && isValidBilingualPair(cjkPart, latinPart)) {
            addFallbackVariant(variantsByKey, cjkPart, originalKey);
            addFallbackVariant(variantsByKey, latinPart, originalKey);
        }
        return variantsByKey.isEmpty() ? List.of() : List.copyOf(variantsByKey.values());
    }

    private static boolean isValidBilingualPair(String cjkPart, String latinPart) {
        int cjkCount = cjkPart.codePointCount(0, cjkPart.length());
        String latinKey = MusicBrainzArtistCache.normalizePersonKey(latinPart);
        if (NON_NAME_LATIN_PREFIXES.contains(latinKey)) {
            return false;
        }
        long latinLetters = latinPart.chars().filter(Character::isLetter).count();
        if (latinLetters < 3) {
            return false;
        }
        return cjkCount >= 2 || (cjkCount == 1 && latinLetters >= 4);
    }

    private static void addFallbackVariant(Map<String, String> target, String candidate, String originalKey) {
        if (candidate == null || candidate.isBlank()) return;
        String trimmed = candidate.trim();
        String key = MusicBrainzArtistCache.normalizePersonKey(trimmed);
        if (key.isEmpty() || key.equals(originalKey) || PLACEHOLDER_KEYS.contains(key)) {
            return;
        }
        // 拒绝仅剩单个纯 ASCII 字符的过短变体
        if (trimmed.codePointCount(0, trimmed.length()) < 2 && trimmed.chars().allMatch(ch -> ch < 128)) {
            return;
        }
        target.putIfAbsent(key, trimmed);
    }

    private static int countMatchedVariantKeys(MusicBrainzClient.ArtistMatch artist, Set<String> variantKeys) {
        if (artist == null || variantKeys == null || variantKeys.isEmpty()) {
            return 0;
        }
        Set<String> matched = new LinkedHashSet<>();
        String nameKey = MusicBrainzArtistCache.normalizePersonKey(artist.name());
        if (variantKeys.contains(nameKey)) {
            matched.add(nameKey);
        }
        if (artist.sortName() != null) {
            String sortKey = MusicBrainzArtistCache.normalizePersonKey(artist.sortName());
            if (variantKeys.contains(sortKey)) {
                matched.add(sortKey);
            }
        }
        for (String alias : artist.aliases()) {
            String aliasKey = MusicBrainzArtistCache.normalizePersonKey(alias);
            if (variantKeys.contains(aliasKey)) {
                matched.add(aliasKey);
            }
        }
        return matched.size();
    }

    private LinkProbeOutcome resolveLinkMbid(
            String mbid,
            Map<String, Optional<MusicBrainzClient.ArtistMatch>> verifiedLinkCache,
            int[] lookupBudgetUsed,
            int maxLookups) throws InterruptedException {
        if (verifiedLinkCache.containsKey(mbid)) {
            return new LinkProbeOutcome(verifiedLinkCache.get(mbid), true);
        }
        if (artistCache != null) {
            Optional<MusicBrainzArtistCache.CachedArtist> cached = artistCache.findByMbid(mbid);
            if (cached.isPresent()) {
                Optional<MusicBrainzClient.ArtistMatch> match = Optional.of(cachedToMatch(cached.get()));
                verifiedLinkCache.put(mbid, match);
                return new LinkProbeOutcome(match, true);
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("cancelled");
        }
        if (musicBrainzClient == null || lookupBudgetUsed[0] >= maxLookups) {
            return new LinkProbeOutcome(Optional.empty(), false);
        }
        int before = lookupBudgetUsed[0];
        AutoCloseable scope = musicBrainzClient.withAttemptBudget(() -> {
            if (lookupBudgetUsed[0] >= maxLookups) {
                return false;
            }
            lookupBudgetUsed[0]++;
            return true;
        });
        try {
            Optional<MusicBrainzClient.ArtistMatch> fetched = musicBrainzClient.getArtistById(mbid);
            if (lookupBudgetUsed[0] == before) {
                lookupBudgetUsed[0]++;
            }
            verifiedLinkCache.put(mbid, fetched);
            return new LinkProbeOutcome(fetched, true);
        } catch (InterruptedException ie) {
            throw ie;
        } catch (MusicBrainzClient.BudgetExhaustedException exhausted) {
            return new LinkProbeOutcome(Optional.empty(), false);
        } catch (IOException e) {
            log.debug("核验网页外链 Artist MBID 失败 ({}): {}", mbid, e.getMessage());
            return new LinkProbeOutcome(Optional.empty(), false);
        } finally {
            closeQuietly(scope);
        }
    }

    private static void closeQuietly(AutoCloseable scope) {
        if (scope == null) return;
        try {
            scope.close();
        } catch (Exception ignored) {
        }
    }

    private List<PersonTarget> collectTargets(ReviewItem.OnlineCandidate candidate) {
        Map<String, PersonTarget> byKey = new LinkedHashMap<>();
        int[] orderSeq = new int[] { 0 };

        // 1. 专辑艺人（最高优先级）
        List<String> albumArtists = ArtistCreditSupport.cleanNames(candidate.getAlbumArtists());
        if (albumArtists.isEmpty()) {
            albumArtists = ArtistCreditSupport.cleanNames(candidate.getArtists());
        }
        if (albumArtists.isEmpty()) {
            String fallback = candidate.getAlbumArtist() != null && !candidate.getAlbumArtist().isBlank()
                ? candidate.getAlbumArtist() : candidate.getArtist();
            if (fallback != null && !fallback.isBlank() && !ArtistCreditSupport.looksComposite(fallback)) {
                albumArtists = List.of(fallback.trim());
            }
        }
        for (String name : albumArtists) {
            addTarget(byKey, name, "album_artist", 1, orderSeq);
        }

        // 2. 可靠匹配曲目的艺人 & 3. 准入核验通过的幕后人员
        List<ReviewItem.OnlineTrack> tracks = selectTracksForEntityExtraction(candidate);
        boolean applyProbable = config == null || config.isOnlineCreditsApplyProbable();
        for (ReviewItem.OnlineTrack track : tracks) {
            if (track == null) continue;
            List<String> trackArtists = ArtistCreditSupport.cleanNames(track.getArtists());
            if (trackArtists.isEmpty() && track.getArtist() != null && !track.getArtist().isBlank()
                    && !ArtistCreditSupport.looksComposite(track.getArtist())) {
                trackArtists = List.of(track.getArtist().trim());
            }
            for (String name : trackArtists) {
                addTarget(byKey, name, "artist", 2, orderSeq);
            }
            for (String name : MetadataUtils.extractAdmittedOnlineNames(track, "composers", applyProbable)) {
                addTarget(byKey, name, "composer", 3, orderSeq);
            }
            for (String name : MetadataUtils.extractAdmittedOnlineNames(track, "lyricists", applyProbable)) {
                addTarget(byKey, name, "lyricist", 3, orderSeq);
            }
            for (String name : MetadataUtils.extractAdmittedOnlineNames(track, "arrangers", applyProbable)) {
                addTarget(byKey, name, "arranger", 3, orderSeq);
            }
        }

        List<PersonTarget> sorted = new ArrayList<>(byKey.values());
        sorted.sort(Comparator
            .comparingInt((PersonTarget t) -> t.priorityBucket)
            .thenComparing(Comparator.comparingInt((PersonTarget t) -> t.frequency).reversed())
            .thenComparingInt(t -> t.order));
        return sorted;
    }

    /** 仅提取达到可靠匹配阈值的曲目；若全专无可靠匹配曲目，不回退到未匹配曲目以免浪费预算。 */
    private static List<ReviewItem.OnlineTrack> selectTracksForEntityExtraction(ReviewItem.OnlineCandidate candidate) {
        if (candidate.getTracks() == null || candidate.getTracks().isEmpty()) {
            return List.of();
        }
        return candidate.getTracks().stream()
            .filter(t -> t != null && t.getMatchedFilePath() != null
                && t.getMatchConfidence() >= OnlineTrackMatcher.MIN_CONFIDENCE)
            .toList();
    }

    private static void addTarget(Map<String, PersonTarget> byKey, String rawName, String role,
                                  int priorityBucket, int[] orderSeq) {
        String key = MusicBrainzArtistCache.normalizePersonKey(rawName);
        if (key.isEmpty() || PLACEHOLDER_KEYS.contains(key)) {
            return;
        }
        PersonTarget target = byKey.get(key);
        if (target == null) {
            target = new PersonTarget(key, rawName.trim(), priorityBucket, orderSeq[0]++);
            byKey.put(key, target);
        } else {
            target.priorityBucket = Math.min(target.priorityBucket, priorityBucket);
        }
        target.roles.add(role);
        target.frequency++;
    }

    /** 收集归属于当前候选（sources + 曲目 creditDetail 证据）的规范化 URL 集合，防止串用其他候选页面外链。 */
    private static Set<String> collectCandidateSourceCanonicalUrls(ReviewItem.OnlineCandidate candidate) {
        Set<String> urls = new LinkedHashSet<>();
        if (candidate == null) {
            return urls;
        }
        if (candidate.getSources() != null) {
            for (ReviewItem.OnlineEvidence src : candidate.getSources()) {
                addCanonicalUrl(urls, src == null ? null : src.getUrl());
            }
        }
        if (candidate.getTracks() != null) {
            for (ReviewItem.OnlineTrack track : candidate.getTracks()) {
                if (track == null || track.getCreditDetail() == null) continue;
                ReviewItem.TrackCreditDetail cd = track.getCreditDetail();
                addFieldEvidenceUrls(urls, cd.getComposers());
                addFieldEvidenceUrls(urls, cd.getLyricists());
                addFieldEvidenceUrls(urls, cd.getArrangers());
            }
        }
        return urls;
    }

    private static void addFieldEvidenceUrls(Set<String> urls, ReviewItem.CreditFieldResult field) {
        if (field == null || field.getEvidence() == null) return;
        for (ReviewItem.CreditEvidenceItem ev : field.getEvidence()) {
            if (ev != null) {
                addCanonicalUrl(urls, ev.getUrl());
            }
        }
    }

    private static void addCanonicalUrl(Set<String> urls, String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return;
        String canon = WebSearchClient.canonicalizeUrl(rawUrl);
        if (canon != null && !canon.isBlank()) {
            urls.add(canon);
        }
    }

    private static Map<String, PageLinkRef> collectCandidateScopedMbLinks(
            ReviewItem.OnlineCandidate candidate,
            Map<String, WebPageFetcher.FetchedPage> fetchedPages,
            Map<String, WebSearchClient.Citation> citationPool) {
        Set<String> allowedCanonicalUrls = collectCandidateSourceCanonicalUrls(candidate);
        boolean scopeByCandidate = !allowedCanonicalUrls.isEmpty();

        Map<String, PageLinkRef> links = new LinkedHashMap<>();
        if (fetchedPages != null) {
            for (WebPageFetcher.FetchedPage page : fetchedPages.values()) {
                if (page == null || !page.ok() || page.mbArtistLinks() == null) continue;
                if (scopeByCandidate) {
                    String pageCanon = page.canonicalUrl() != null && !page.canonicalUrl().isBlank()
                        ? WebSearchClient.canonicalizeUrl(page.canonicalUrl())
                        : WebSearchClient.canonicalizeUrl(page.url());
                    String rawCanon = WebSearchClient.canonicalizeUrl(page.url());
                    if (!allowedCanonicalUrls.contains(pageCanon) && !allowedCanonicalUrls.contains(rawCanon)) {
                        continue;
                    }
                }
                for (WebPageFetcher.ExtractedMbArtistLink link : page.mbArtistLinks()) {
                    String mbid = MusicBrainzArtistCache.normalizeValidMbid(link.mbid());
                    if (mbid == null) continue;
                    PageLinkRef ref = links.computeIfAbsent(mbid, PageLinkRef::new);
                    if (link.anchorText() != null && !link.anchorText().isBlank()) {
                        ref.anchors.add(link.anchorText().trim());
                    }
                }
            }
        }
        if (citationPool != null) {
            for (WebSearchClient.Citation citation : citationPool.values()) {
                if (citation == null || citation.getUrl() == null) continue;
                String citationCanon = WebSearchClient.canonicalizeUrl(citation.getUrl());
                if (scopeByCandidate && !allowedCanonicalUrls.contains(citationCanon)) {
                    continue;
                }
                WebPageFetcher.extractMbArtistLinkFromUrl(citation.getUrl(), citation.getTitle(), citation.getUrl())
                    .ifPresent(link -> {
                        PageLinkRef ref = links.computeIfAbsent(link.mbid(), PageLinkRef::new);
                        if (link.anchorText() != null && !link.anchorText().isBlank()) {
                            ref.anchors.add(link.anchorText().trim());
                        }
                    });
            }
        }
        return links;
    }

    private static List<PageLinkRef> orderLinksForPerson(PersonTarget target, Map<String, PageLinkRef> sessionLinks) {
        List<PageLinkRef> matchedAnchors = new ArrayList<>();
        List<PageLinkRef> others = new ArrayList<>();
        for (PageLinkRef ref : sessionLinks.values()) {
            if (anchorMatchesTarget(ref, target.normalizedKey)) {
                matchedAnchors.add(ref);
            } else {
                others.add(ref);
            }
        }
        matchedAnchors.addAll(others);
        return matchedAnchors;
    }

    private static boolean anchorMatchesTarget(PageLinkRef ref, String normalizedKey) {
        for (String anchor : ref.anchors) {
            String norm = MusicBrainzArtistCache.normalizePersonKey(anchor);
            if (!norm.isEmpty() && (norm.equals(normalizedKey) || norm.contains(normalizedKey) || normalizedKey.contains(norm))) {
                return true;
            }
        }
        return false;
    }

    static boolean artistMatchesPersonName(MusicBrainzClient.ArtistMatch artist, String normalizedTargetKey) {
        if (artist == null || normalizedTargetKey == null || normalizedTargetKey.isEmpty()) {
            return false;
        }
        if (MusicBrainzArtistCache.normalizePersonKey(artist.name()).equals(normalizedTargetKey)) {
            return true;
        }
        for (String alias : artist.aliases()) {
            if (MusicBrainzArtistCache.normalizePersonKey(alias).equals(normalizedTargetKey)) {
                return true;
            }
        }
        return false;
    }

    @SafeVarargs
    private static List<MusicBrainzClient.ArtistMatch> mergeMatchesByMbid(List<MusicBrainzClient.ArtistMatch>... lists) {
        Map<String, MusicBrainzClient.ArtistMatch> byMbid = new LinkedHashMap<>();
        if (lists != null) {
            for (List<MusicBrainzClient.ArtistMatch> list : lists) {
                if (list == null) continue;
                for (MusicBrainzClient.ArtistMatch match : list) {
                    if (match == null) continue;
                    String mbid = MusicBrainzArtistCache.normalizeValidMbid(match.mbid());
                    if (mbid == null) continue;
                    byMbid.merge(mbid, match, OnlineEntityMbidResolver::mergeArtistMatch);
                }
            }
        }
        return new ArrayList<>(byMbid.values());
    }

    /** 合并同一 Artist MBID 来自不同层/来源的证据，不丢失任一来源的别名、标签或元数据。 */
    private static MusicBrainzClient.ArtistMatch mergeArtistMatch(MusicBrainzClient.ArtistMatch a,
                                                                  MusicBrainzClient.ArtistMatch b) {
        if (a == null) return b;
        if (b == null) return a;
        LinkedHashSet<String> mergedAliases = new LinkedHashSet<>(a.aliases());
        mergedAliases.addAll(b.aliases());
        LinkedHashSet<String> mergedTags = new LinkedHashSet<>(a.tags());
        mergedTags.addAll(b.tags());
        return new MusicBrainzClient.ArtistMatch(
            a.mbid(),
            preferNonBlank(a.name(), b.name()),
            preferNonBlank(a.sortName(), b.sortName()),
            preferNonBlank(a.disambiguation(), b.disambiguation()),
            preferNonBlank(a.country(), b.country()),
            preferNonBlank(a.areaName(), b.areaName()),
            preferNonBlank(a.type(), b.type()),
            preferNonBlank(a.lifeSpanBegin(), b.lifeSpanBegin()),
            preferNonBlank(a.lifeSpanEnd(), b.lifeSpanEnd()),
            Math.max(a.score(), b.score()),
            List.copyOf(mergedAliases),
            List.copyOf(mergedTags)
        );
    }

    private static String preferNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) return primary;
        return fallback != null && !fallback.isBlank() ? fallback : null;
    }

    private static MusicBrainzClient.ArtistMatch cachedToMatch(MusicBrainzArtistCache.CachedArtist ca) {
        return new MusicBrainzClient.ArtistMatch(
            ca.mbid(), ca.name(), ca.sortName(), ca.disambiguation(),
            ca.country(), null, ca.artistType(), null, null, 100, ca.aliases(), List.of());
    }

    private static ReviewItem.EntityMbidResolution fallbackWithoutClient(
            PersonTarget target, List<MusicBrainzClient.ArtistMatch> known, boolean ambiguousOrIncomplete) {
        if (known.size() == 1 && !ambiguousOrIncomplete) {
            return buildResolved(target, known.get(0), ReviewItem.EntityMbidSource.LOCAL_CACHE, 1.0, null, known);
        }
        if (!known.isEmpty()) {
            return buildAmbiguous(target, known, "存在同名冲突或未完成核验的候选，等待人工点选");
        }
        return buildNotFound(target, null);
    }

    private static void markRemainingSkipped(List<PersonTarget> targets, int startIdx,
                                             Map<String, ReviewItem.EntityMbidResolution> results, String reason) {
        for (int j = startIdx; j < targets.size(); j++) {
            PersonTarget rest = targets.get(j);
            results.putIfAbsent(rest.normalizedKey, buildSkipped(rest, reason));
        }
    }

    private static ReviewItem.EntityMbidResolution buildResolved(
            PersonTarget target, MusicBrainzClient.ArtistMatch chosen,
            ReviewItem.EntityMbidSource source, double confidence, String reason,
            List<MusicBrainzClient.ArtistMatch> candidates) {
        ReviewItem.EntityMbidResolution res = baseResolution(target);
        res.setStatus(ReviewItem.EntityMbidStatus.RESOLVED);
        res.setSelectedMbid(chosen.mbid());
        res.setSelectedName(chosen.name());
        res.setSelectedSortName(chosen.sortName());
        res.setSelectedDisambiguation(chosen.disambiguation());
        res.setSource(source);
        res.setConfidence(confidence);
        res.setReason(reason);
        res.setCandidates(toCandidateSnapshots(chosen, candidates));
        return res;
    }

    private static ReviewItem.EntityMbidResolution buildAmbiguous(
            PersonTarget target, List<MusicBrainzClient.ArtistMatch> candidates, String reason) {
        ReviewItem.EntityMbidResolution res = baseResolution(target);
        res.setStatus(ReviewItem.EntityMbidStatus.AMBIGUOUS);
        res.setReason(reason);
        res.setCandidates(toCandidateSnapshots(null, candidates));
        return res;
    }

    private static ReviewItem.EntityMbidResolution buildNotFound(PersonTarget target, String reason) {
        ReviewItem.EntityMbidResolution res = baseResolution(target);
        res.setStatus(ReviewItem.EntityMbidStatus.NOT_FOUND);
        res.setReason(reason);
        return res;
    }

    private static ReviewItem.EntityMbidResolution buildSkipped(PersonTarget target, String reason) {
        ReviewItem.EntityMbidResolution res = baseResolution(target);
        res.setStatus(ReviewItem.EntityMbidStatus.SKIPPED);
        res.setReason(reason);
        return res;
    }

    private static ReviewItem.EntityMbidResolution baseResolution(PersonTarget target) {
        ReviewItem.EntityMbidResolution res = new ReviewItem.EntityMbidResolution();
        res.setNormalizedKey(target.normalizedKey);
        res.setDisplayName(target.displayName);
        res.setRoles(new ArrayList<>(target.roles));
        return res;
    }

    private static List<ReviewItem.EntityMbidCandidate> toCandidateSnapshots(
            MusicBrainzClient.ArtistMatch chosenFirst, List<MusicBrainzClient.ArtistMatch> all) {
        Map<String, ReviewItem.EntityMbidCandidate> ordered = new LinkedHashMap<>();
        if (chosenFirst != null) {
            ordered.put(chosenFirst.mbid(), toSnapshot(chosenFirst));
        }
        if (all != null) {
            for (MusicBrainzClient.ArtistMatch m : all) {
                if (ordered.size() >= MAX_PERSISTED_CANDIDATES) break;
                if (m != null && m.mbid() != null) {
                    ordered.putIfAbsent(m.mbid(), toSnapshot(m));
                }
            }
        }
        return new ArrayList<>(ordered.values());
    }

    private static ReviewItem.EntityMbidCandidate toSnapshot(MusicBrainzClient.ArtistMatch m) {
        ReviewItem.EntityMbidCandidate c = new ReviewItem.EntityMbidCandidate();
        c.setMbid(m.mbid());
        c.setName(m.name());
        c.setSortName(m.sortName());
        c.setDisambiguation(m.disambiguation());
        c.setCountry(m.country());
        c.setType(m.type());
        c.setScore(m.score());
        return c;
    }
}
