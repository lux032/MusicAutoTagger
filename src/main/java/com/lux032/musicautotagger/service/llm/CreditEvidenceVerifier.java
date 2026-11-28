package com.lux032.musicautotagger.service.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lux032.musicautotagger.model.ReviewItem;
import com.lux032.musicautotagger.util.MetadataUtils;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 确定性字段级幕后人员证据核验器（不依赖模型主观置信度或模型自报 source_type）。
 *
 * 强制执行六道代码级护栏：
 *   1. URL 必须真实存在于本会话检索/抓取池中（经 canonicalizeUrl 规范化比对），来源权威性一律由服务端按落地 URL 判定；
 *   2. 声明人名必须实际出现于引文之中，且已抓取全文页面必须通过 NFKC 归一化引文与人名原文核验（严禁跨曲目拼接归属）；
 *   3. 多条证据同时在引文与完整正文层面执行 5-gram Jaccard 相似度转载通稿去重（≥ 0.65 记为转载副本）；
 *   4. Instrumental / Off Vocal / Karaoke / 伴奏 曲目严禁写入作词人（lyricists）；
 *   5. Remix / Latin / Acoustic / Live / Alternate 等特殊版本曲目严禁继承原版编曲（arrangers 必须 exact_version）；
 *   6. Remixer / Producer / Mix / Mastering 角色严禁混入 arrangers，且冲突状态必须由 ≥ 2 个独立核验候选构成。
 */
public final class CreditEvidenceVerifier {

    public static final int MAX_QUOTE_CHARS = 200;
    public static final int MAX_EVIDENCE_PER_FIELD = 3;
    private static final double SYNDICATION_JACCARD_THRESHOLD = 0.65;
    private static final int FULL_PAGE_SYNDICATION_SAMPLE_CHARS = 2000;

    private static final Pattern INSTRUMENTAL_PATTERN = Pattern.compile(
        "(?i)(?<![a-z0-9])(instrumental|inst\\.?|off\\s*-?\\s*vocal|offvocal|karaoke)(?![a-z0-9])|インスト|伴奏|純音楽|纯音乐"
    );

    private static final Pattern ALT_VERSION_PATTERN = Pattern.compile(
        "(?i)(?<![a-z0-9])(remix|latin|acoustic|live|unplugged|rework|tv\\s*anime|tv\\s*size|short\\s*ver|piano\\s*ver|orchestra|ballad\\s*ver|version|ver\\.)(?![a-z0-9])|リミックス|アレンジ|弾き語り"
    );

    private static final Pattern NON_ARRANGER_ROLE_ONLY = Pattern.compile(
        "(?i)(?<![a-z0-9])(remix|remixed\\s+by|remixer|producer|produced\\s+by|mixing|mixed\\s+by|mix|mastering)(?![a-z0-9])|リミックス|プロデューサー|ミキシング|ミックス|マスタリング"
    );

    private static final Pattern ARRANGER_LABEL_PATTERN = Pattern.compile(
        "(?i)(?<![a-z0-9])(arrangement|arranged\\s+by|arranger|arr\\.)(?![a-z0-9])|編曲|编曲|アレンジ"
    );

    private static final Set<String> HIGH_AUTHORITY_SOURCE_TYPES = Set.of(
        "artist_official", "label_official", "jp_lyrics_site",
        "official_press_release", "music_database", "music_store_platform"
    );

    private CreditEvidenceVerifier() {
    }

    /**
     * 对单首曲目的 {@link ReviewItem.TrackCreditDetail} 草稿执行全套确定性核验，
     * 原地写入核验后的 {@code creditDetail} 并按准入规则回填扁平名单列表。
     */
    public static void verifyTrack(ReviewItem.OnlineTrack track,
                                   String artistHint,
                                   Map<String, WebPageFetcher.FetchedPage> fetchedPagesByCanon,
                                   Map<String, WebSearchClient.Citation> citationPoolByCanon,
                                   boolean allowNativePoolWithoutBody,
                                   boolean applyProbable) {
        if (track == null) {
            return;
        }
        ReviewItem.TrackCreditDetail detail = track.getCreditDetail();
        if (detail == null) {
            return;
        }
        String title = track.getTitle() == null ? "" : track.getTitle();
        String vType = detail.getVersionType() == null ? "original" : detail.getVersionType().trim().toLowerCase(Locale.ROOT);
        boolean isInstrumental = isInstrumentalTrack(title, vType);
        boolean isAltVersion = isAltVersionTrack(title, vType);

        detail.setLyricists(verifyRole("lyricists", detail.getLyricists(), artistHint, isInstrumental, isAltVersion,
            fetchedPagesByCanon, citationPoolByCanon, allowNativePoolWithoutBody));
        detail.setComposers(verifyRole("composers", detail.getComposers(), artistHint, isInstrumental, isAltVersion,
            fetchedPagesByCanon, citationPoolByCanon, allowNativePoolWithoutBody));
        detail.setArrangers(verifyRole("arrangers", detail.getArrangers(), artistHint, isInstrumental, isAltVersion,
            fetchedPagesByCanon, citationPoolByCanon, allowNativePoolWithoutBody));

        applyVerifiedCreditsToTrack(track, applyProbable);
    }

    /**
     * 当模型省略 {@code credit_detail} 仅输出扁平数组时，为新解析曲目构造结构化降级明细：
     * 防止无引文扁平数组借“历史旧队列”路径绕过核验器与 {@code applyProbable=false} 拦截，
     * 同时强制执行伴奏无作词与特殊版本编曲防继承护栏。
     */
    public static void buildUnverifiedFlatFallbackDetail(ReviewItem.OnlineTrack track, boolean applyProbable) {
        if (track == null || track.getCreditDetail() != null) {
            return;
        }
        String title = track.getTitle() == null ? "" : track.getTitle();
        boolean isInstrumental = isInstrumentalTrack(title, null);
        boolean isAltVersion = isAltVersionTrack(title, null);

        ReviewItem.TrackCreditDetail detail = new ReviewItem.TrackCreditDetail();
        detail.setVersionType(isInstrumental ? "instrumental" : (isAltVersion ? "alternate_arrangement" : "original"));
        detail.setComposers(wrapFlatRole(track.getComposers(), false, null));
        detail.setLyricists(wrapFlatRole(track.getLyricists(), isInstrumental, "伴奏曲目已自动拦截作词人写入"));
        detail.setArrangers(wrapFlatRole(track.getArrangers(), isAltVersion, "特殊版本缺少精确编曲证据，已拒绝无引文编曲写入"));
        track.setCreditDetail(detail);
        applyVerifiedCreditsToTrack(track, applyProbable);
    }

    private static ReviewItem.CreditFieldResult wrapFlatRole(List<String> rawNames, boolean blocked, String blockNote) {
        ReviewItem.CreditFieldResult f = new ReviewItem.CreditFieldResult();
        List<String> cleaned = MetadataUtils.cleanDistinctCreditNames(rawNames);
        if (blocked) {
            f.setStatus(ReviewItem.CreditStatus.NOT_FOUND);
            f.setNames(new ArrayList<>());
            if (!cleaned.isEmpty()) {
                f.setNotes(blockNote);
            }
            return f;
        }
        if (cleaned.isEmpty()) {
            f.setStatus(ReviewItem.CreditStatus.NOT_SEARCHED);
            f.setNames(new ArrayList<>());
            return f;
        }
        // 无字段级原文引文的新鲜输出最高降级为 PROBABLE，绝不视为 VERIFIED
        f.setStatus(ReviewItem.CreditStatus.PROBABLE);
        f.setNames(new ArrayList<>(cleaned));
        f.setNotes("未提供字段级原文引文，降级为推测");
        return f;
    }

    /** 将核验通过的字段名单同步回填到 OnlineTrack.composers/lyricists/arrangers；CONFLICT/NOT_FOUND 一律置空。 */
    public static void applyVerifiedCreditsToTrack(ReviewItem.OnlineTrack track, boolean applyProbable) {
        if (track == null || track.getCreditDetail() == null) {
            return;
        }
        ReviewItem.TrackCreditDetail d = track.getCreditDetail();
        track.setLyricists(new ArrayList<>(acceptedNames(d.getLyricists(), applyProbable)));
        track.setComposers(new ArrayList<>(acceptedNames(d.getComposers(), applyProbable)));
        track.setArrangers(new ArrayList<>(acceptedNames(d.getArrangers(), applyProbable)));
    }

    /**
     * 跨轮次/跨候选安全合并同一字段的核验结果：
     * 若两个独立来源分别给出不同的 VERIFIED 名单，自动合成为 CONFLICT 并保留全部候选供人工确认，
     * 防止首轮结果静默吞掉次轮独立反证。
     */
    public static ReviewItem.CreditFieldResult mergeFieldResults(ReviewItem.CreditFieldResult prev,
                                                                 ReviewItem.CreditFieldResult next) {
        if (prev == null) return next;
        if (next == null) return prev;
        if (next.getStatus() == ReviewItem.CreditStatus.CONFLICT) {
            return next;
        }
        if (prev.getStatus() == ReviewItem.CreditStatus.CONFLICT) {
            return prev;
        }
        if (prev.getStatus() == ReviewItem.CreditStatus.VERIFIED && next.getStatus() == ReviewItem.CreditStatus.VERIFIED) {
            List<String> prevNames = MetadataUtils.cleanDistinctCreditNames(prev.getNames());
            List<String> nextNames = MetadataUtils.cleanDistinctCreditNames(next.getNames());
            if (!prevNames.isEmpty() && !nextNames.isEmpty()
                    && !canonicalNameSetKey(prevNames).equals(canonicalNameSetKey(nextNames))
                    && hasIndependentUrls(prev, next)) {
                ReviewItem.CreditFieldResult conflict = new ReviewItem.CreditFieldResult();
                conflict.setStatus(ReviewItem.CreditStatus.CONFLICT);
                conflict.setNames(new ArrayList<>());
                List<ReviewItem.CreditEvidenceItem> combinedEv = new ArrayList<>();
                if (prev.getEvidence() != null) combinedEv.addAll(prev.getEvidence());
                if (next.getEvidence() != null) {
                    for (ReviewItem.CreditEvidenceItem e : next.getEvidence()) {
                        if (combinedEv.size() < MAX_EVIDENCE_PER_FIELD) combinedEv.add(e);
                    }
                }
                conflict.setEvidence(combinedEv);
                conflict.setCandidates(new ArrayList<>(List.of(
                    toConflictCandidate(prevNames, prev.getEvidence()),
                    toConflictCandidate(nextNames, next.getEvidence())
                )));
                conflict.setNotes("多轮检索发现独立权威来源署名不一致，已标记为冲突供人工点选");
                return conflict;
            }
            return prev;
        }
        if (prev.getStatus() == ReviewItem.CreditStatus.VERIFIED) {
            return prev;
        }
        if (next.getStatus() == ReviewItem.CreditStatus.VERIFIED) {
            return next;
        }
        if (prev.getStatus() == ReviewItem.CreditStatus.PROBABLE) {
            return prev;
        }
        if (next.getStatus() == ReviewItem.CreditStatus.PROBABLE) {
            return next;
        }
        return next.getStatus() == ReviewItem.CreditStatus.NOT_FOUND ? next : prev;
    }

    private static boolean hasIndependentUrls(ReviewItem.CreditFieldResult a, ReviewItem.CreditFieldResult b) {
        Set<String> urlsA = new HashSet<>();
        if (a.getEvidence() != null) {
            for (ReviewItem.CreditEvidenceItem e : a.getEvidence()) {
                if (e != null && e.getUrl() != null) urlsA.add(WebSearchClient.canonicalizeUrl(e.getUrl()));
            }
        }
        if (b.getEvidence() != null) {
            for (ReviewItem.CreditEvidenceItem e : b.getEvidence()) {
                if (e != null && e.getUrl() != null && !e.isSyndicatedDuplicate()) {
                    String canon = WebSearchClient.canonicalizeUrl(e.getUrl());
                    if (!canon.isEmpty() && !urlsA.contains(canon)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static ReviewItem.CreditConflictCandidate toConflictCandidate(List<String> names,
                                                                          List<ReviewItem.CreditEvidenceItem> evidence) {
        ReviewItem.CreditConflictCandidate c = new ReviewItem.CreditConflictCandidate();
        c.setNames(new ArrayList<>(names));
        List<String> urls = new ArrayList<>();
        List<String> quotes = new ArrayList<>();
        if (evidence != null) {
            for (ReviewItem.CreditEvidenceItem e : evidence) {
                if (e == null) continue;
                if (e.getUrl() != null && !e.getUrl().isBlank()) urls.add(e.getUrl());
                if (e.getQuote() != null && !e.getQuote().isBlank()) quotes.add(truncate(e.getQuote(), MAX_QUOTE_CHARS));
            }
        }
        c.setUrls(urls);
        c.setQuotes(quotes);
        return c;
    }

    private static List<String> acceptedNames(ReviewItem.CreditFieldResult field, boolean applyProbable) {
        if (field == null || field.getStatus() == null || field.getNames() == null || field.getNames().isEmpty()) {
            return List.of();
        }
        if (field.getStatus() == ReviewItem.CreditStatus.VERIFIED) {
            return MetadataUtils.cleanDistinctCreditNames(field.getNames());
        }
        if (applyProbable && field.getStatus() == ReviewItem.CreditStatus.PROBABLE) {
            return MetadataUtils.cleanDistinctCreditNames(field.getNames());
        }
        return List.of();
    }

    private static ReviewItem.CreditFieldResult verifyRole(
            String role,
            ReviewItem.CreditFieldResult raw,
            String artistHint,
            boolean isInstrumental,
            boolean isAltVersion,
            Map<String, WebPageFetcher.FetchedPage> fetchedPagesByCanon,
            Map<String, WebSearchClient.Citation> citationPoolByCanon,
            boolean allowNativePoolWithoutBody) {

        ReviewItem.CreditFieldResult out = new ReviewItem.CreditFieldResult();
        if (raw == null) {
            out.setStatus(ReviewItem.CreditStatus.NOT_SEARCHED);
            return out;
        }

        List<String> names = MetadataUtils.cleanDistinctCreditNames(raw.getNames());
        ReviewItem.CreditStatus claimedStatus = raw.getStatus() == null
            ? (names.isEmpty() ? ReviewItem.CreditStatus.NOT_FOUND : ReviewItem.CreditStatus.PROBABLE)
            : raw.getStatus();
        StringBuilder notes = new StringBuilder();
        if (raw.getNotes() != null && !raw.getNotes().isBlank()) {
            notes.append(raw.getNotes().trim());
        }

        // 护栏 1：伴奏曲目禁止写入作词人
        if ("lyricists".equals(role) && isInstrumental) {
            out.setStatus(ReviewItem.CreditStatus.NOT_FOUND);
            out.setNames(new ArrayList<>());
            if (!names.isEmpty()) {
                appendNote(notes, "伴奏曲目已自动拦截作词人写入");
            }
            out.setNotes(emptyToNull(notes.toString()));
            return out;
        }

        // 校验冲突候选：必须具备池内独立 URL 且通过正文/引文人名归属校验，避免单 URL 或无证据假冲突
        List<ReviewItem.CreditConflictCandidate> validConflicts = validateConflictCandidates(
            raw.getCandidates(), role, fetchedPagesByCanon, citationPoolByCanon, allowNativePoolWithoutBody);

        // 逐条证据核验
        List<ReviewItem.CreditEvidenceItem> keptEvidence = new ArrayList<>();
        List<ReviewItem.CreditEvidenceItem> validFullEvidence = new ArrayList<>();
        List<ReviewItem.CreditEvidenceItem> validWeakEvidence = new ArrayList<>();
        List<String> seenQuotes = new ArrayList<>();
        List<String> seenPageBodies = new ArrayList<>();
        boolean sawRoleConfusion = false;
        boolean sawVersionRejected = false;

        if (raw.getEvidence() != null) {
            for (ReviewItem.CreditEvidenceItem ev : raw.getEvidence()) {
                if (ev == null || ev.getUrl() == null || ev.getUrl().isBlank()) continue;
                String cleanUrl = WebSearchClient.stripTrackingParams(ev.getUrl().trim());
                String canon = WebSearchClient.canonicalizeUrl(cleanUrl);
                if (canon.isEmpty()) continue;

                WebPageFetcher.FetchedPage fetched = fetchedPagesByCanon == null ? null : fetchedPagesByCanon.get(canon);
                WebSearchClient.Citation citation = citationPoolByCanon == null ? null : citationPoolByCanon.get(canon);

                boolean hasFetchedOk = fetched != null && fetched.ok() && fetched.fullTextForVerify() != null
                    && !fetched.fullTextForVerify().isBlank();
                boolean hasCitation = citation != null;
                if (!hasFetchedOk && !hasCitation) {
                    continue;
                }

                String quote = ev.getQuote() == null ? "" : ev.getQuote().trim();
                String versionMatch = normalizeVersionMatch(ev.getVersionMatch());
                // 安全原则：sourceType 一律由服务端基于最终落地 URL 判定，严禁信任模型自报的 source_type
                String sourceType = hasFetchedOk && fetched.sourceType() != null && !fetched.sourceType().isBlank()
                    ? fetched.sourceType()
                    : WebSearchClient.SourceReliability.classifySourceType(cleanUrl, artistHint);

                // 护栏 2：角色防混淆（Remixer/Producer/Mix/Mastering 不得填入 arrangers）
                if ("arrangers".equals(role) && isRoleConfusedAsArranger(quote)) {
                    sawRoleConfusion = true;
                    appendNote(notes, "原文仅标注 Remix/Producer/Mix 而非编曲，已拒绝填入编曲栏: " + truncate(quote, 80));
                    continue;
                }

                // 护栏 3：版本不匹配、仅专辑级署名、或特殊版本编曲企图继承原版
                if ("mismatch".equals(versionMatch) || "album_level_only".equals(versionMatch)) {
                    sawVersionRejected = true;
                    continue;
                }
                if ("arrangers".equals(role) && isAltVersion && "original_only".equals(versionMatch)) {
                    sawVersionRejected = true;
                    continue;
                }

                // 护栏 4：引文与人名绑定校验（声明人名必须真实出现在引文本身中，防止跨曲目拼凑）
                boolean namesInQuote = names.isEmpty() || allNamesFoundInText(names, quote);
                if (!namesInQuote) {
                    continue;
                }

                boolean verifiedFull = false;
                boolean verifiedWeak = false;
                if (hasFetchedOk) {
                    String corpus = fetched.fullTextForVerify();
                    boolean qOk = quoteFoundInText(quote, corpus);
                    boolean nOk = names.isEmpty() || allNamesFoundInText(names, corpus);
                    if (qOk && nOk) {
                        verifiedFull = true;
                    }
                } else if (hasCitation) {
                    String snip = citation.getSnippet();
                    if (snip != null && !snip.isBlank()) {
                        boolean qOk = quoteFoundInText(quote, snip);
                        boolean nOk = names.isEmpty() || allNamesFoundInText(names, snip);
                        if (qOk && nOk) {
                            verifiedWeak = true;
                        }
                    } else if (allowNativePoolWithoutBody && quote.length() >= 3) {
                        // 原生 /v1/responses 服务端不回传网页原文：仅当未命中本地直连抓取、且引文包含声明人名时生效
                        if (citation.isOpenedPage()) {
                            verifiedFull = true;
                        } else {
                            verifiedWeak = true;
                        }
                    }
                }

                if (!verifiedFull && !verifiedWeak) {
                    continue;
                }

                // 护栏 5：引文 + 完整正文双重 5-gram Jaccard 转载通稿去重
                boolean syndicated = false;
                if (!quote.isEmpty()) {
                    for (String prev : seenQuotes) {
                        if (isSyndicatedDuplicate(quote, prev)) {
                            syndicated = true;
                            break;
                        }
                    }
                }
                if (!syndicated && hasFetchedOk) {
                    String sampleBody = truncate(fetched.fullTextForVerify(), FULL_PAGE_SYNDICATION_SAMPLE_CHARS);
                    for (String prevBody : seenPageBodies) {
                        if (isSyndicatedDuplicate(sampleBody, prevBody)) {
                            syndicated = true;
                            break;
                        }
                    }
                    if (!syndicated) {
                        seenPageBodies.add(sampleBody);
                    }
                }
                if (!syndicated && !quote.isEmpty()) {
                    seenQuotes.add(quote);
                }

                ReviewItem.CreditEvidenceItem item = new ReviewItem.CreditEvidenceItem();
                item.setUrl(cleanUrl);
                item.setSourceType(sourceType);
                item.setQuote(truncate(quote, MAX_QUOTE_CHARS));
                item.setVersionMatch(versionMatch);
                item.setFullPageVerified(verifiedFull);
                item.setSyndicatedDuplicate(syndicated);

                if (keptEvidence.size() < MAX_EVIDENCE_PER_FIELD) {
                    keptEvidence.add(item);
                }
                if (verifiedFull) {
                    validFullEvidence.add(item);
                } else {
                    validWeakEvidence.add(item);
                }
            }
        }

        // 最终五态确定性判定：仅当存在 ≥ 2 个独立核验冲突候选时才成立 CONFLICT
        if (validConflicts.size() >= 2) {
            out.setStatus(ReviewItem.CreditStatus.CONFLICT);
            out.setNames(new ArrayList<>());
            out.setEvidence(keptEvidence);
            out.setCandidates(validConflicts);
            out.setNotes(emptyToNull(notes.toString()));
            return out;
        }

        if (names.isEmpty()) {
            out.setStatus(claimedStatus == ReviewItem.CreditStatus.NOT_SEARCHED
                ? ReviewItem.CreditStatus.NOT_SEARCHED : ReviewItem.CreditStatus.NOT_FOUND);
            out.setNames(new ArrayList<>());
            out.setEvidence(keptEvidence);
            out.setNotes(emptyToNull(notes.toString()));
            return out;
        }

        // 特殊版本编曲必须存在 exact_version 的有效证据
        if ("arrangers".equals(role) && isAltVersion) {
            boolean hasExactAuthFull = validFullEvidence.stream()
                .filter(e -> !e.isSyndicatedDuplicate())
                .anyMatch(e -> "exact_version".equals(e.getVersionMatch())
                    && isAuthoritativeEvidence(e, artistHint, fetchedPagesByCanon));
            boolean hasExactAny = validFullEvidence.stream().anyMatch(e -> "exact_version".equals(e.getVersionMatch()))
                || validWeakEvidence.stream().anyMatch(e -> "exact_version".equals(e.getVersionMatch()));
            if (!hasExactAuthFull && !hasExactAny) {
                out.setStatus(ReviewItem.CreditStatus.NOT_FOUND);
                out.setNames(new ArrayList<>());
                out.setEvidence(keptEvidence);
                appendNote(notes, sawRoleConfusion
                    ? "特殊版本仅发现非编曲角色署名，已保留为空"
                    : "特殊版本缺少该版本的精确编曲证据，已拒绝继承原版编曲");
                out.setNotes(emptyToNull(notes.toString()));
                return out;
            }
            out.setStatus(hasExactAuthFull ? ReviewItem.CreditStatus.VERIFIED : ReviewItem.CreditStatus.PROBABLE);
            out.setNames(new ArrayList<>(names));
            out.setEvidence(keptEvidence);
            out.setNotes(emptyToNull(notes.toString()));
            return out;
        }

        List<ReviewItem.CreditEvidenceItem> nonSyndFull = validFullEvidence.stream()
            .filter(e -> !e.isSyndicatedDuplicate()).toList();
        // VERIFIED 必须具备权威来源 + 精确版本（exact_version），绝不接受 unknown 或 original_only 升级为 VERIFIED
        boolean hasExactAuthFull = nonSyndFull.stream().anyMatch(e ->
            isAuthoritativeEvidence(e, artistHint, fetchedPagesByCanon)
                && "exact_version".equals(e.getVersionMatch()));

        if (hasExactAuthFull) {
            out.setStatus(ReviewItem.CreditStatus.VERIFIED);
            out.setNames(new ArrayList<>(names));
        } else if (!validFullEvidence.isEmpty() || !validWeakEvidence.isEmpty()) {
            out.setStatus(ReviewItem.CreditStatus.PROBABLE);
            out.setNames(new ArrayList<>(names));
            if (isAltVersion && validFullEvidence.stream().allMatch(e -> "original_only".equals(e.getVersionMatch()))) {
                appendNote(notes, "基于原曲词曲证据推导，未见该版本独立署名");
            }
        } else {
            out.setStatus(ReviewItem.CreditStatus.NOT_FOUND);
            out.setNames(new ArrayList<>());
            appendNote(notes, sawVersionRejected
                ? "证据与当前版本不匹配，已拦截"
                : "未在本会话已抓取/检索到的来源中验证到对应引文与人名，已拦截");
        }
        out.setEvidence(keptEvidence);
        out.setNotes(emptyToNull(notes.toString()));
        return out;
    }

    private static boolean isAuthoritativeEvidence(ReviewItem.CreditEvidenceItem ev,
                                                   String artistHint,
                                                   Map<String, WebPageFetcher.FetchedPage> fetchedPagesByCanon) {
        if (ev == null || ev.getSourceType() == null) return false;
        if (HIGH_AUTHORITY_SOURCE_TYPES.contains(ev.getSourceType())) {
            return true;
        }
        // YouTube 仅在抓取到频道/标题且与艺人名吻合时视为官方权威来源，防止粉丝搬运视频直接 VERIFIED
        if ("youtube_official".equals(ev.getSourceType()) && artistHint != null && !artistHint.isBlank()) {
            String canon = WebSearchClient.canonicalizeUrl(ev.getUrl());
            WebPageFetcher.FetchedPage p = fetchedPagesByCanon == null ? null : fetchedPagesByCanon.get(canon);
            if (p != null && p.ok() && p.fullTextForVerify() != null) {
                String head = p.fullTextForVerify().substring(0, Math.min(300, p.fullTextForVerify().length()));
                return compactNormalize(head).contains(compactNormalize(artistHint));
            }
        }
        return false;
    }

    private static List<ReviewItem.CreditConflictCandidate> validateConflictCandidates(
            List<ReviewItem.CreditConflictCandidate> rawCandidates,
            String role,
            Map<String, WebPageFetcher.FetchedPage> fetchedPagesByCanon,
            Map<String, WebSearchClient.Citation> citationPoolByCanon,
            boolean allowNativePoolWithoutBody) {
        List<ReviewItem.CreditConflictCandidate> valid = new ArrayList<>();
        if (rawCandidates == null || rawCandidates.isEmpty()) {
            return valid;
        }
        Set<String> seenNameKeys = new LinkedHashSet<>();
        Set<String> claimedCanonUrls = new HashSet<>();
        for (ReviewItem.CreditConflictCandidate cand : rawCandidates) {
            if (cand == null) continue;
            List<String> cNames = MetadataUtils.cleanDistinctCreditNames(cand.getNames());
            if (cNames.isEmpty()) continue;
            String nameKey = canonicalNameSetKey(cNames);
            if (!seenNameKeys.add(nameKey)) continue;

            List<String> validUrls = new ArrayList<>();
            List<String> validQuotes = new ArrayList<>();
            List<String> rawUrls = cand.getUrls() == null ? List.of() : cand.getUrls();
            List<String> rawQuotes = cand.getQuotes() == null ? List.of() : cand.getQuotes();

            for (int i = 0; i < rawUrls.size(); i++) {
                String u = rawUrls.get(i);
                String canon = WebSearchClient.canonicalizeUrl(u);
                if (canon.isEmpty() || !isInPool(canon, fetchedPagesByCanon, citationPoolByCanon)) continue;
                // 同一 URL 不得同时支撑两个互相冲突的候选名单
                if (claimedCanonUrls.contains(canon)) continue;

                String q = i < rawQuotes.size() && rawQuotes.get(i) != null ? rawQuotes.get(i).trim() : "";
                if ("arrangers".equals(role) && isRoleConfusedAsArranger(q)) continue;

                WebPageFetcher.FetchedPage fetched = fetchedPagesByCanon == null ? null : fetchedPagesByCanon.get(canon);
                WebSearchClient.Citation cit = citationPoolByCanon == null ? null : citationPoolByCanon.get(canon);
                boolean attested = false;
                if (fetched != null && fetched.ok() && fetched.fullTextForVerify() != null) {
                    boolean nOk = allNamesFoundInText(cNames, fetched.fullTextForVerify());
                    boolean qOk = q.isEmpty() || (allNamesFoundInText(cNames, q) && quoteFoundInText(q, fetched.fullTextForVerify()));
                    attested = nOk && qOk;
                } else if (cit != null) {
                    if (cit.getSnippet() != null && !cit.getSnippet().isBlank()) {
                        attested = allNamesFoundInText(cNames, cit.getSnippet());
                    } else if (allowNativePoolWithoutBody) {
                        attested = q.length() >= 3 && allNamesFoundInText(cNames, q);
                    }
                }
                if (attested) {
                    claimedCanonUrls.add(canon);
                    validUrls.add(WebSearchClient.stripTrackingParams(u));
                    if (!q.isEmpty()) {
                        validQuotes.add(truncate(q, MAX_QUOTE_CHARS));
                    }
                }
            }
            if (!validUrls.isEmpty()) {
                ReviewItem.CreditConflictCandidate copy = new ReviewItem.CreditConflictCandidate();
                copy.setNames(new ArrayList<>(cNames));
                copy.setUrls(validUrls);
                copy.setQuotes(validQuotes);
                valid.add(copy);
            }
        }
        return valid;
    }

    public static boolean isInstrumentalTrack(String title, String versionType) {
        return "instrumental".equalsIgnoreCase(versionType)
            || (title != null && INSTRUMENTAL_PATTERN.matcher(title).find());
    }

    public static boolean isAltVersionTrack(String title, String versionType) {
        if (versionType != null) {
            String vt = versionType.trim().toLowerCase(Locale.ROOT);
            if ("remix".equals(vt) || "alternate_arrangement".equals(vt) || "live".equals(vt) || "acoustic".equals(vt)) {
                return true;
            }
        }
        return title != null && ALT_VERSION_PATTERN.matcher(title).find();
    }

    static boolean isRoleConfusedAsArranger(String quote) {
        if (quote == null || quote.isBlank()) {
            return false;
        }
        String norm = Normalizer.normalize(quote, Normalizer.Form.NFKC);
        return NON_ARRANGER_ROLE_ONLY.matcher(norm).find() && !ARRANGER_LABEL_PATTERN.matcher(norm).find();
    }

    static boolean quoteFoundInText(String quote, String corpus) {
        if (quote == null || quote.isBlank() || corpus == null || corpus.isBlank()) {
            return false;
        }
        String nq = compactNormalize(quote);
        String nc = compactNormalize(corpus);
        if (nq.length() < 3) {
            return false;
        }
        if (nc.contains(nq)) {
            return true;
        }
        // 分段核验：要求所有有效片段落在同一局部邻域（≤ 400 字符窗口）内，防止跨曲目零散词拼凑命中
        String spaced = Normalizer.normalize(quote, Normalizer.Form.NFKC);
        String[] parts = spaced.split("[.\\u2026/|:：、,・\\s\\(\\)（）\\[\\]「」『』]+");
        List<String> tokens = new ArrayList<>();
        for (String p : parts) {
            String np = compactNormalize(p);
            if (np.length() >= 2) {
                tokens.add(np);
            }
        }
        if (tokens.isEmpty()) {
            return false;
        }
        // 以最长片段为锚点，检查其前后 400 字符局部窗口内的片段覆盖率 ≥ 85%
        String anchor = tokens.stream().max((a, b) -> Integer.compare(a.length(), b.length())).orElse(tokens.get(0));
        int pos = nc.indexOf(anchor);
        while (pos >= 0) {
            int winStart = Math.max(0, pos - 200);
            int winEnd = Math.min(nc.length(), pos + anchor.length() + 200);
            String window = nc.substring(winStart, winEnd);
            int hits = 0;
            for (String tok : tokens) {
                if (window.contains(tok)) hits++;
            }
            if (((double) hits / tokens.size()) >= 0.85) {
                return true;
            }
            pos = nc.indexOf(anchor, pos + 1);
        }
        return false;
    }

    static boolean allNamesFoundInText(List<String> names, String corpus) {
        if (names == null || names.isEmpty() || corpus == null || corpus.isBlank()) {
            return false;
        }
        String nc = compactNormalize(corpus);
        for (String name : names) {
            String nn = compactNormalize(name);
            if (nn.isEmpty()) continue;
            if (nc.contains(nn)) {
                continue;
            }
            String stripped = compactNormalize(name.replaceAll("[\\(（][^\\)）]*[\\)）]", ""));
            if (stripped.length() >= 2 && nc.contains(stripped)) {
                continue;
            }
            return false;
        }
        return true;
    }

    static boolean isSyndicatedDuplicate(String q1, String q2) {
        String a = compactNormalize(q1);
        String b = compactNormalize(q2);
        if (a.length() < 20 || b.length() < 20) {
            return false;
        }
        if (a.contains(b) || b.contains(a)) {
            return true;
        }
        Set<String> g1 = ngrams(a, 5);
        Set<String> g2 = ngrams(b, 5);
        if (g1.isEmpty() || g2.isEmpty()) {
            return false;
        }
        int inter = 0;
        for (String g : g1) {
            if (g2.contains(g)) inter++;
        }
        int union = g1.size() + g2.size() - inter;
        return union > 0 && ((double) inter / union) >= SYNDICATION_JACCARD_THRESHOLD;
    }

    private static Set<String> ngrams(String text, int n) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i <= text.length() - n; i++) {
            set.add(text.substring(i, i + n));
        }
        return set;
    }

    private static String compactNormalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\s\\u3000]+", "");
    }

    private static String canonicalNameSetKey(List<String> names) {
        List<String> norm = new ArrayList<>();
        for (String n : names) {
            String cn = compactNormalize(n);
            if (!cn.isEmpty()) norm.add(cn);
        }
        norm.sort(String::compareTo);
        return String.join("|", norm);
    }

    private static boolean isInPool(String canon,
                                    Map<String, WebPageFetcher.FetchedPage> fetched,
                                    Map<String, WebSearchClient.Citation> citations) {
        if (canon == null || canon.isEmpty()) return false;
        if (fetched != null && fetched.containsKey(canon) && fetched.get(canon).ok()) return true;
        return citations != null && citations.containsKey(canon);
    }

    private static String normalizeVersionMatch(String vm) {
        if (vm == null || vm.isBlank()) return "unknown";
        String lower = vm.trim().toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "exact_version", "original_only", "album_level_only", "mismatch", "unknown" -> lower;
            default -> "unknown";
        };
    }

    private static void appendNote(StringBuilder sb, String addition) {
        if (addition == null || addition.isBlank()) return;
        if (sb.length() > 0) sb.append(" | ");
        sb.append(addition);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 从模型返回的曲目 JSON 中解析可选的结构化 {@link ReviewItem.TrackCreditDetail}。
     */
    public static ReviewItem.TrackCreditDetail parseCreditDetailJson(JsonObject trackObj) {
        if (trackObj == null) {
            return null;
        }
        JsonObject container = trackObj.has("credit_detail") && trackObj.get("credit_detail").isJsonObject()
            ? trackObj.getAsJsonObject("credit_detail")
            : null;
        boolean hasNestedRoleObj = container != null
            || (trackObj.has("lyricists") && trackObj.get("lyricists").isJsonObject())
            || (trackObj.has("composers") && trackObj.get("composers").isJsonObject())
            || (trackObj.has("arrangers") && trackObj.get("arrangers").isJsonObject());
        if (!hasNestedRoleObj) {
            return null;
        }
        JsonObject src = container != null ? container : trackObj;
        ReviewItem.TrackCreditDetail detail = new ReviewItem.TrackCreditDetail();
        String vt = jsonStr(trackObj, "version_type");
        if (vt == null || vt.isBlank()) {
            vt = jsonStr(src, "version_type");
        }
        detail.setVersionType(vt == null || vt.isBlank() ? "original" : vt.trim().toLowerCase(Locale.ROOT));
        detail.setLyricists(parseFieldResult(src, "lyricists"));
        detail.setComposers(parseFieldResult(src, "composers"));
        detail.setArrangers(parseFieldResult(src, "arrangers"));
        return detail;
    }

    private static ReviewItem.CreditFieldResult parseFieldResult(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
            ReviewItem.CreditFieldResult empty = new ReviewItem.CreditFieldResult();
            empty.setStatus(ReviewItem.CreditStatus.NOT_SEARCHED);
            return empty;
        }
        JsonObject o = parent.getAsJsonObject(key);
        ReviewItem.CreditFieldResult res = new ReviewItem.CreditFieldResult();
        res.setStatus(parseStatus(jsonStr(o, "status")));
        res.setNames(new ArrayList<>(MetadataUtils.cleanDistinctCreditNames(jsonStrList(o, "names"))));
        res.setNotes(emptyToNull(jsonStr(o, "notes")));

        List<ReviewItem.CreditEvidenceItem> evs = new ArrayList<>();
        if (o.has("evidence") && o.get("evidence").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("evidence")) {
                if (!el.isJsonObject()) continue;
                JsonObject eo = el.getAsJsonObject();
                String url = jsonStr(eo, "url");
                if (url == null || url.isBlank()) continue;
                ReviewItem.CreditEvidenceItem item = new ReviewItem.CreditEvidenceItem();
                item.setUrl(url.trim());
                item.setSourceType(jsonStr(eo, "source_type"));
                item.setQuote(truncate(jsonStr(eo, "quote"), MAX_QUOTE_CHARS));
                item.setVersionMatch(normalizeVersionMatch(jsonStr(eo, "version_match")));
                evs.add(item);
            }
        }
        res.setEvidence(evs);

        List<ReviewItem.CreditConflictCandidate> cands = new ArrayList<>();
        if (o.has("candidates") && o.get("candidates").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("candidates")) {
                if (!el.isJsonObject()) continue;
                JsonObject co = el.getAsJsonObject();
                List<String> cNames = MetadataUtils.cleanDistinctCreditNames(jsonStrList(co, "names"));
                if (cNames.isEmpty()) continue;
                ReviewItem.CreditConflictCandidate cc = new ReviewItem.CreditConflictCandidate();
                cc.setNames(new ArrayList<>(cNames));
                cc.setUrls(new ArrayList<>(jsonStrList(co, "urls")));
                List<String> quotes = new ArrayList<>();
                for (String q : jsonStrList(co, "quotes")) {
                    quotes.add(truncate(q, MAX_QUOTE_CHARS));
                }
                cc.setQuotes(quotes);
                cands.add(cc);
            }
        }
        res.setCandidates(cands);
        return res;
    }

    private static ReviewItem.CreditStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return ReviewItem.CreditStatus.NOT_FOUND;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "verified" -> ReviewItem.CreditStatus.VERIFIED;
            case "probable" -> ReviewItem.CreditStatus.PROBABLE;
            case "conflict" -> ReviewItem.CreditStatus.CONFLICT;
            case "not_searched" -> ReviewItem.CreditStatus.NOT_SEARCHED;
            default -> ReviewItem.CreditStatus.NOT_FOUND;
        };
    }

    private static String jsonStr(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static List<String> jsonStrList(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement el : o.getAsJsonArray(key)) {
            if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                String v = el.getAsString().trim();
                if (!v.isEmpty()) out.add(v);
            }
        }
        return out;
    }
}
