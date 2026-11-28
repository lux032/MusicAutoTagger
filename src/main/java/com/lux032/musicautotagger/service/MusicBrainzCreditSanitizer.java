package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.model.MbContributionItem;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MusicBrainz 社区合规助手：
 * <ol>
 *   <li>Works / 词曲编关系补录参考卡：按 MB 规范自动剥离创作者末尾括号里的所属厂牌/社团后缀
 *       （如 {@code tepe (OTOIRO)} -> 实体 {@code tepe}、{@code 雨良 Amala (ninelive.)} -> 实体 {@code 雨良}
 *       + 可选人名变体 {@code 雨良 Amala}），绝不修改本地音频标签原文；</li>
 *   <li>Cover Art Archive（CAA）高画质封面双模式上传引导：支持 Apple Music/iTunes 3000px 升格、
 *       公网 {@code x_seed} 参数（兼容 ECAU 用户脚本）以及本地未压缩原图下载与建议 Comment。</li>
 * </ol>
 */
public final class MusicBrainzCreditSanitizer {

    private static final Pattern TRAILING_PAREN = Pattern.compile("^(.*?)\\s*[\\(（]([^\\)）]{1,64})[\\)）]\\s*$");
    private static final Pattern ITUNES_SIZE_PATTERN = Pattern.compile("/\\d+x\\d+bb(\\.[a-zA-Z0-9]+)$");
    /** 非所属机构类括号内容（如角色、人声、混音、数字消歧、CV 标注），不得误判为厂牌/社团所属。 */
    private static final Pattern NON_AFFILIATION_SUFFIX = Pattern.compile(
        "(?i)^(?:\\d+|cv[:：.\\s].*|vo\\.?|vocal|vocals|rap|chorus|guitar|bass|drums|piano|strings|remix|inst\\.?|instrumental|feat\\.?.*|from\\s+.*|ver\\.?.*|version.*)$");

    private MusicBrainzCreditSanitizer() { }

    public record SanitizedCreditPerson(
        String rawName,
        String cleanName,
        String affiliationNote,
        String mbid,
        String canonicalName,
        String disambiguation,
        String artistUrl,
        String suggestedCreditedAs,
        String source,
        boolean needsVerification
    ) { }

    public record TrackWorksGuide(
        int discNo,
        int trackNo,
        String title,
        String recordingId,
        List<SanitizedCreditPerson> composers,
        List<SanitizedCreditPerson> lyricists,
        List<SanitizedCreditPerson> arrangers,
        List<String> evidenceUrls,
        List<String> evidenceQuotes,
        String suggestedEditNote
    ) { }

    public record WorksGuide(
        String releaseId,
        String editRelationshipsUrl,
        List<TrackWorksGuide> tracks,
        boolean hasAnyCredits
    ) { }

    public record CoverArtGuide(
        String mode,
        String releaseId,
        String addCoverArtBaseUrl,
        String addCoverArtSeededUrl,
        String publicHighResUrl,
        String originPageUrl,
        boolean hasLocalBackup,
        String localDownloadUrl,
        String mime,
        int width,
        int height,
        long bytes,
        String suggestedComment
    ) { }

    /**
     * 按 MusicBrainz 关系规范清洗单个创作者展示名（仅用于社区补录参考，绝不修改本地标签）。
     */
    public static SanitizedCreditPerson sanitizeCreatorCredit(String rawDisplayName,
                                                              MbContributionItem.EntityMbidRef mbidRef,
                                                              String creditSource) {
        String raw = rawDisplayName == null ? "" : rawDisplayName.trim();
        String mbid = mbidRef == null ? null : MusicBrainzArtistCache.normalizeValidMbid(mbidRef.getMbid());
        String canonical = mbidRef == null ? null : trimToNull(mbidRef.getCanonicalName());
        String disambiguation = mbidRef == null ? null : trimToNull(mbidRef.getDisambiguation());
        String artistUrl = mbid != null ? ("https://musicbrainz.org/artist/" + mbid) : null;

        String cleanName = raw;
        String affiliationNote = null;
        Matcher m = TRAILING_PAREN.matcher(raw);
        if (m.matches()) {
            String base = m.group(1) == null ? "" : m.group(1).trim();
            String inside = m.group(2) == null ? "" : m.group(2).trim();
            if (isLikelyAffiliationSuffix(raw, base, inside, canonical)) {
                cleanName = base;
                affiliationNote = inside;
            }
        }

        String suggestedCreditedAs = null;
        if (canonical != null) {
            if (!MusicBrainzArtistCache.normalizePersonKey(cleanName)
                    .equals(MusicBrainzArtistCache.normalizePersonKey(canonical))) {
                suggestedCreditedAs = cleanName;
            }
        } else if (!cleanName.isEmpty() && !cleanName.equals(raw)) {
            suggestedCreditedAs = cleanName;
        }

        boolean needsVerification = "ONLINE_PROBABLE".equalsIgnoreCase(creditSource);
        return new SanitizedCreditPerson(
            raw, cleanName, affiliationNote, mbid, canonical, disambiguation,
            artistUrl, suggestedCreditedAs, creditSource, needsVerification);
    }

    private static boolean isLikelyAffiliationSuffix(String raw, String base, String inside, String canonical) {
        if (base.isEmpty() || inside.isEmpty()) {
            return false;
        }
        // 若括号内容本身就是规范主名的一部分，绝不剥离
        if (canonical != null
                && MusicBrainzArtistCache.normalizePersonKey(canonical)
                    .equals(MusicBrainzArtistCache.normalizePersonKey(raw))) {
            return false;
        }
        // 非机构类括号（角色/乐器/数字消歧/CV）不作为所属厂牌剥离
        if (NON_AFFILIATION_SUFFIX.matcher(inside).matches()) {
            return false;
        }
        // 若已知规范主名且剥离后与规范主名精确匹配或主名包含于 base 中，确认为所属括号
        if (canonical != null) {
            String normCanon = MusicBrainzArtistCache.normalizePersonKey(canonical);
            String normBase = MusicBrainzArtistCache.normalizePersonKey(base);
            if (normCanon.equals(normBase) || normBase.contains(normCanon) || normCanon.contains(normBase)) {
                return true;
            }
        }
        // 未绑定规范主名时：仅在括号内像社团/厂牌/公司名（非纯数字、非纯符号、长度 >= 2）时识别为所属机构
        return inside.length() >= 2 && !inside.matches("^[\\p{Punct}\\d\\s]+$");
    }

    public static WorksGuide buildWorksGuide(MbContributionItem item) {
        if (item == null || item.getDraft() == null) {
            return new WorksGuide(null, null, List.of(), false);
        }
        String releaseId = MusicBrainzArtistCache.normalizeValidMbid(item.getLinkedReleaseId());
        String editUrl = releaseId != null
            ? ("https://musicbrainz.org/release/" + releaseId + "/edit-relationships")
            : null;
        Map<String, MbContributionItem.EntityMbidRef> mbids = item.getDraft().getEntityMbids();

        // 若已完成 Release 回填，优先按曲序补上真实 Recording MBID
        Map<String, String> recByKey = new java.util.HashMap<>();
        if (item.getBackfilledTracks() != null) {
            for (MbContributionItem.LinkedTrackMapping lm : item.getBackfilledTracks()) {
                if (lm != null && lm.getRecordingId() != null) {
                    recByKey.put(lm.getDiscNo() + ":" + lm.getTrackNo(), lm.getRecordingId());
                }
            }
        }

        List<TrackWorksGuide> guides = new ArrayList<>();
        boolean anyCredits = false;
        List<MbContributionItem.ContributionTrack> tracks = item.getDraft().getTracks();
        if (tracks != null) {
            for (MbContributionItem.ContributionTrack tr : tracks) {
                if (tr == null) continue;
                int disc = tr.getDiscNo() > 0 ? tr.getDiscNo() : 1;
                int pos = tr.getTrackNo();
                List<SanitizedCreditPerson> composers = sanitizeRoleList(tr.getComposers(), tr.getComposerSource(), mbids);
                List<SanitizedCreditPerson> lyricists = sanitizeRoleList(tr.getLyricists(), tr.getLyricistSource(), mbids);
                List<SanitizedCreditPerson> arrangers = sanitizeRoleList(tr.getArrangers(), tr.getArrangerSource(), mbids);
                if (!composers.isEmpty() || !lyricists.isEmpty() || !arrangers.isEmpty()) {
                    anyCredits = true;
                }
                String recId = recByKey.getOrDefault(disc + ":" + pos,
                    MusicBrainzArtistCache.normalizeValidMbid(tr.getExistingRecordingId()));

                // 仅汇总实际展示角色的证据，绝不混入已排除冲突字段的引文或 URL
                Set<String> urlSet = new LinkedHashSet<>();
                Set<String> quoteSet = new LinkedHashSet<>();
                boolean hasPerRoleEvidence = !isEmpty(tr.getComposerEvidenceUrls()) || !isEmpty(tr.getComposerEvidenceQuotes())
                    || !isEmpty(tr.getLyricistEvidenceUrls()) || !isEmpty(tr.getLyricistEvidenceQuotes())
                    || !isEmpty(tr.getArrangerEvidenceUrls()) || !isEmpty(tr.getArrangerEvidenceQuotes());
                if (hasPerRoleEvidence) {
                    if (!composers.isEmpty()) {
                        addSafeUrls(urlSet, tr.getComposerEvidenceUrls());
                        addNonBlank(quoteSet, tr.getComposerEvidenceQuotes());
                    }
                    if (!lyricists.isEmpty()) {
                        addSafeUrls(urlSet, tr.getLyricistEvidenceUrls());
                        addNonBlank(quoteSet, tr.getLyricistEvidenceQuotes());
                    }
                    if (!arrangers.isEmpty()) {
                        addSafeUrls(urlSet, tr.getArrangerEvidenceUrls());
                        addNonBlank(quoteSet, tr.getArrangerEvidenceQuotes());
                    }
                } else if (!composers.isEmpty() || !lyricists.isEmpty() || !arrangers.isEmpty()) {
                    addSafeUrls(urlSet, tr.getCreditEvidenceUrls());
                    addNonBlank(quoteSet, tr.getCreditEvidenceQuotes());
                }

                List<String> urls = List.copyOf(urlSet);
                List<String> quotes = List.copyOf(quoteSet);
                String note = buildTrackEditNote(composers, lyricists, arrangers, urls, quotes);
                guides.add(new TrackWorksGuide(
                    disc, pos, tr.getTitle(), recId, composers, lyricists, arrangers, urls, quotes, note));
            }
        }
        return new WorksGuide(releaseId, editUrl, List.copyOf(guides), anyCredits);
    }

    private static boolean isEmpty(List<String> list) {
        return list == null || list.isEmpty();
    }

    private static void addSafeUrls(Set<String> target, List<String> urls) {
        if (urls == null) return;
        for (String u : urls) {
            String canon = MusicBrainzSeedBuilder.canonicalSafePublicHttpUrl(u);
            if (canon != null) target.add(canon);
        }
    }

    private static void addNonBlank(Set<String> target, List<String> items) {
        if (items == null) return;
        for (String s : items) {
            if (s != null && !s.isBlank() && target.size() < 4) {
                target.add(s.trim());
            }
        }
    }

    private static List<SanitizedCreditPerson> sanitizeRoleList(List<String> names,
                                                                String source,
                                                                Map<String, MbContributionItem.EntityMbidRef> mbids) {
        if (names == null || names.isEmpty() || !isAdmissibleCreditSource(source)) {
            return List.of();
        }
        List<SanitizedCreditPerson> list = new ArrayList<>();
        for (String n : names) {
            String trimmed = trimToNull(n);
            if (trimmed == null) continue;
            MbContributionItem.EntityMbidRef ref = mbids == null ? null
                : mbids.get(MusicBrainzArtistCache.normalizePersonKey(trimmed));
            list.add(sanitizeCreatorCredit(trimmed, ref, source));
        }
        return List.copyOf(list);
    }

    static boolean isAdmissibleCreditSource(String source) {
        if (source == null || source.isBlank()) {
            return true;
        }
        String u = source.trim().toUpperCase(Locale.ROOT);
        return !"CONFLICT".equals(u) && !"NOT_FOUND".equals(u) && !"NOT_SEARCHED".equals(u) && !"NONE".equals(u);
    }

    private static String buildTrackEditNote(List<SanitizedCreditPerson> composers,
                                             List<SanitizedCreditPerson> lyricists,
                                             List<SanitizedCreditPerson> arrangers,
                                             List<String> urls,
                                             List<String> quotes) {
        StringBuilder sb = new StringBuilder();
        Set<String> affiliations = new LinkedHashSet<>();
        for (List<SanitizedCreditPerson> group : List.of(composers, lyricists, arrangers)) {
            for (SanitizedCreditPerson p : group) {
                if (p.affiliationNote() != null) {
                    affiliations.add(p.cleanName() + " (" + p.affiliationNote() + ")");
                }
            }
        }
        if (!affiliations.isEmpty()) {
            sb.append("Original affiliation credits: ").append(String.join(", ", affiliations)).append('\n');
        }
        if (!urls.isEmpty()) {
            sb.append("Sources:\n");
            for (String u : urls) {
                sb.append("- ").append(u).append('\n');
            }
        }
        if (!quotes.isEmpty()) {
            sb.append("Excerpt: ").append(quotes.get(0)).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 构造 Cover Art Archive 双模式上传引导（支持公网原图 {@code x_seed} 参数与本地未压缩备份下载）。
     */
    public static CoverArtGuide buildCoverGuidance(MbContributionItem item,
                                                   boolean hasLocalBackup,
                                                   String coverDownloadApiPath) {
        if (item == null) {
            return new CoverArtGuide("NONE", null, null, null, null, null, false, null, null, 0, 0, 0, null);
        }
        String releaseId = MusicBrainzArtistCache.normalizeValidMbid(item.getLinkedReleaseId());
        String baseUrl = releaseId != null
            ? ("https://musicbrainz.org/release/" + releaseId + "/add-cover-art")
            : null;
        MbContributionItem.CoverInfo cover = item.getCover();
        String publicRaw = cover == null ? null : trimToNull(cover.getPublicUrl());
        String upgraded = publicRaw == null ? null : upgradePublicCoverUrl(publicRaw);
        String publicHiRes = MusicBrainzSeedBuilder.canonicalSafePublicHttpUrl(upgraded);

        String originPage = cover == null ? null : MusicBrainzSeedBuilder.canonicalSafePublicHttpUrl(cover.getOriginPageUrl());
        if (originPage == null && item.getDraft() != null && item.getDraft().getSourceUrls() != null) {
            for (MbContributionItem.SourceUrlEntry s : item.getDraft().getSourceUrls()) {
                if (s != null && s.isIncluded()
                        && ("HIGH".equalsIgnoreCase(s.getReliability()) || "MEDIUM".equalsIgnoreCase(s.getReliability()))) {
                    String canon = MusicBrainzSeedBuilder.canonicalSafePublicHttpUrl(s.getUrl());
                    if (canon != null) {
                        originPage = canon;
                        break;
                    }
                }
            }
        }
        String comment = buildCoverComment(cover, publicHiRes, originPage);
        String seededUrl = baseUrl;
        if (baseUrl != null && publicHiRes != null) {
            StringBuilder q = new StringBuilder(baseUrl).append('?');
            if (originPage != null) {
                q.append("x_seed.origin=").append(urlEncode(originPage)).append('&');
            }
            q.append("x_seed.image.0.url=").append(urlEncode(publicHiRes));
            q.append("&x_seed.image.0.types=front");
            if (comment != null && !comment.isBlank()) {
                q.append("&x_seed.image.0.comment=").append(urlEncode(comment));
            }
            seededUrl = q.toString();
        }
        String localUrl = (hasLocalBackup && coverDownloadApiPath != null && item.getId() != null)
            ? (coverDownloadApiPath + "?id=" + urlEncode(item.getId()))
            : null;
        String mode = publicHiRes != null ? "PUBLIC_URL" : (hasLocalBackup ? "LOCAL_BACKUP" : "NONE");
        return new CoverArtGuide(
            mode,
            releaseId,
            baseUrl,
            seededUrl,
            publicHiRes,
            originPage,
            hasLocalBackup,
            localUrl,
            cover == null ? null : cover.getMime(),
            cover == null ? 0 : cover.getWidth(),
            cover == null ? 0 : cover.getHeight(),
            cover == null ? 0 : cover.getBytes(),
            comment
        );
    }

    /** 将 Apple Music / iTunes 封面 URL 升级至 3000x3000 原分辨率，其余 URL 保持原样。 */
    public static String upgradePublicCoverUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.contains("mzstatic.com/")) {
            return ITUNES_SIZE_PATTERN.matcher(trimmed).replaceFirst("/3000x3000bb$1");
        }
        return trimmed;
    }

    private static String buildCoverComment(MbContributionItem.CoverInfo cover,
                                            String publicHiResUrl,
                                            String originPageUrl) {
        if (originPageUrl != null) {
            return "Front cover from official source: " + originPageUrl;
        }
        if (publicHiResUrl != null) {
            return "Front cover from " + publicHiResUrl;
        }
        if (cover != null && "EMBEDDED".equalsIgnoreCase(cover.getOrigin())) {
            return "Front cover extracted from official digital release audio file";
        }
        return "Front cover from official release";
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
