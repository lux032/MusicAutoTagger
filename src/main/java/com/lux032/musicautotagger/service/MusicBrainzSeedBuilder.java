package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.model.MbContributionItem;
import com.lux032.musicautotagger.util.OutboundUrlValidator;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MusicBrainz 官方 Release Editor Seeding（{@code POST https://musicbrainz.org/release/add}）表单载荷构造器。
 *
 * <p>严格遵循官方文档参数规范：
 * {@code name}、{@code comment}、{@code release_group}、{@code type}、{@code status}、{@code packaging}、
 * {@code language}、{@code script}、{@code barcode}、{@code events.0.*}、{@code labels.0.*}、
 * {@code artist_credit.names.i.*}、{@code mediums.m.format}、{@code mediums.m.track.t.*}、
 * {@code urls.u.url}、{@code edit_note}。
 */
public final class MusicBrainzSeedBuilder {

    public static final String RELEASE_ADD_URL = "https://musicbrainz.org/release/add";
    private static final int MAX_SEED_URLS = 10;
    private static final int MAX_EDIT_NOTE_SOURCES = 25;
    private static final Pattern ISO_DATE = Pattern.compile("^(\\d{4})(?:-(\\d{1,2})(?:-(\\d{1,2}))?)?$");
    private static final Set<String> VALID_PRIMARY_TYPES = Set.of("album", "single", "ep", "broadcast", "other");
    private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");
    private static final Pattern GENERIC_FORMAT_DISAMBIGUATION = Pattern.compile(
        "(?i)^(?:(?:japanese|english|korean|chinese|mandarin|cantonese|taiwanese|hong\\s+kong|us|uk|jp|kr|cn|eu|global|worldwide|international|domestic|overseas)\\s+){0,2}"
            + "(?:(?:digital|web|streaming|download|cd|vinyl|lp|cassette)\\s+){0,2}"
            + "(?:single|ep|mini[-\\s]?album|album|release|edition)$");
    private static final Set<String> LYRICS_ONLY_DOMAINS = Set.of(
        "uta-net.com", "utamap.com", "j-lyric.net", "lyrical-nonsense.com",
        "kashinavi.com", "petitlyrics.com", "mojim.com", "utaten.com",
        "musixmatch.com", "azlyrics.com", "lyrics.com", "metrolyrics.com", "songlyrics.com");
    private static final Pattern COMPOSITE_LABEL_SPLIT = Pattern.compile("\\s+[/|]\\s+");
    private static final Pattern INSTRUMENTAL_TITLE_PATTERN = Pattern.compile(
        "(?i)(?:\\b(?:instrumental|inst\\.?|off\\s*vocal|karaoke|minus\\s*one|backing\\s*track)\\b|カラオケ|インスト)");

    private MusicBrainzSeedBuilder() { }

    public record SeedField(String name, String value) { }

    public record SeedPayload(
        boolean seedable,
        String blockedReason,
        String actionUrl,
        List<SeedField> fields,
        String editNote,
        List<String> warnings
    ) { }

    public static SeedPayload build(MbContributionItem item) {
        if (item == null || item.getDraft() == null) {
            return blocked("mb.contribution.seed.missing.draft", List.of("缺少发行草稿数据"));
        }
        if (item.isTerminal()) {
            return blocked("mb.contribution.seed.terminal", List.of());
        }
        if (item.getCheckCase() == MbContributionItem.CheckCase.EXISTING_RELEASE
                || item.getStatus() == MbContributionItem.Status.EXISTING_MATCH) {
            return blocked("mb.contribution.seed.existing.release", List.of());
        }
        if (item.getStatus() == MbContributionItem.Status.LINKED) {
            return blocked("mb.contribution.seed.already.linked", List.of());
        }
        if (item.getStatus() == MbContributionItem.Status.CHECKING) {
            return blocked("mb.contribution.seed.checking", List.of());
        }
        if (item.getStatus() == MbContributionItem.Status.CHECK_FAILED
                || item.getCheckCase() == MbContributionItem.CheckCase.ERROR) {
            return blocked("mb.contribution.seed.check.failed", List.of());
        }
        if (item.getStatus() != MbContributionItem.Status.READY_TO_SEED
                || (item.getCheckCase() != MbContributionItem.CheckCase.MISSING_EDITION
                    && item.getCheckCase() != MbContributionItem.CheckCase.MISSING_RELEASE_GROUP)) {
            return blocked("mb.contribution.seed.not.eligible", List.of());
        }

        MbContributionItem.ReleaseDraft draft = item.getDraft();
        String ineligibleReason = MusicBrainzExistenceChecker.evaluateCaseDIneligibility(draft);
        if (ineligibleReason != null) {
            return blocked("mb.contribution.seed.not.eligible", List.of(ineligibleReason));
        }

        List<SeedField> fields = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        addField(fields, "name", draft.getTitle());

        String rgMbid = (item.getCheckCase() == MbContributionItem.CheckCase.MISSING_EDITION
                && item.isUseExistingReleaseGroup())
            ? MusicBrainzArtistCache.normalizeValidMbid(item.getMatchedReleaseGroupId())
            : null;
        String seedComment = trimToNull(draft.getComment());
        // Case B（同组缺版本）即使未自动挂入已有 RG 也保留版本消歧；仅全新独立 RG 隐藏纯格式通用消歧词
        if (item.getCheckCase() != MbContributionItem.CheckCase.MISSING_EDITION
                && rgMbid == null
                && isGenericFormatDisambiguation(seedComment)) {
            seedComment = null;
        }
        addField(fields, "comment", seedComment);
        if (rgMbid != null) {
            addField(fields, "release_group", rgMbid);
        } else {
            String primaryType = normalizePrimaryType(draft.getReleaseType(), draft);
            if (primaryType != null) {
                addField(fields, "type", primaryType);
            }
        }

        addField(fields, "status", "official");
        String mediaFormat = normalizeMediaFormat(draft.getMediaFormat());
        if ("Digital Media".equalsIgnoreCase(mediaFormat)) {
            addField(fields, "packaging", "None");
        }

        String language = trimToNull(draft.getLanguage());
        String script = trimToNull(draft.getScript());
        if (language == null && script == null && shouldInferJapaneseReleaseLanguage(draft)) {
            language = "jpn";
            script = "Jpan";
        }
        addField(fields, "language", language);
        addField(fields, "script", script);

        String barcode = MusicBrainzClient.normalizeBarcodeDigits(draft.getBarcode());
        if (barcode != null) {
            addField(fields, "barcode", barcode);
        }

        // Release event (date + country)
        appendReleaseEvent(fields, draft.getReleaseDate(), draft.getCountry());

        // Label(s) + catalog number（多厂牌拆分时不盲目把目录号挂给第一家，改为写进 edit_note 供人工确认）
        List<String> labels = splitCompositeLabels(draft.getLabel());
        for (int lIdx = 0; lIdx < labels.size(); lIdx++) {
            addField(fields, "labels." + lIdx + ".name", labels.get(lIdx));
        }
        String unattachedCatalogNumber = null;
        if (labels.size() <= 1) {
            addField(fields, "labels.0.catalog_number", draft.getCatalogNumber());
        } else if (trimToNull(draft.getCatalogNumber()) != null) {
            unattachedCatalogNumber = draft.getCatalogNumber().trim();
            warnings.add("检测到多厂牌拼接，已拆分厂牌并将目录号放入编辑说明，请在 Release Editor 中挂到对应厂牌。");
        }

        // Release-level artist credit
        List<String> albumArtists = resolveArtistsList(draft.getAlbumArtists(), draft.getAlbumArtist());
        appendArtistCredit(fields, "artist_credit", albumArtists, draft.getAlbumArtist(), draft.getEntityMbids());

        // Mediums & full official tracklist
        List<MbContributionItem.ContributionTrack> tracks = draft.getTracks() == null
            ? List.of() : new ArrayList<>(draft.getTracks());
        tracks.sort(Comparator.comparingInt((MbContributionItem.ContributionTrack t) -> Math.max(1, t.getDiscNo()))
            .thenComparingInt(MbContributionItem.ContributionTrack::getTrackNo));

        Map<Integer, List<MbContributionItem.ContributionTrack>> byDisc = new LinkedHashMap<>();
        for (MbContributionItem.ContributionTrack t : tracks) {
            if (t == null) continue;
            int disc = t.getDiscNo() > 0 ? t.getDiscNo() : 1;
            byDisc.computeIfAbsent(disc, k -> new ArrayList<>()).add(t);
        }

        int localMatchedCount = 0;
        int localPreciseDurationCount = 0;
        int mediumIdx = 0;
        for (Map.Entry<Integer, List<MbContributionItem.ContributionTrack>> entry : byDisc.entrySet()) {
            String mediumPrefix = "mediums." + mediumIdx;
            if (mediaFormat != null) {
                addField(fields, mediumPrefix + ".format", mediaFormat);
            }
            List<MbContributionItem.ContributionTrack> discTracks = entry.getValue();
            for (int tIdx = 0; tIdx < discTracks.size(); tIdx++) {
                MbContributionItem.ContributionTrack tr = discTracks.get(tIdx);
                String trPrefix = mediumPrefix + ".track." + tIdx;
                String trTitle = trimToNull(tr.getTitle());
                addField(fields, trPrefix + ".name", trTitle != null ? trTitle : ("Track " + (tIdx + 1)));
                int pos = tr.getTrackNo() > 0 ? tr.getTrackNo() : (tIdx + 1);
                addField(fields, trPrefix + ".number", String.valueOf(pos));
                if (tr.getDurationMs() > 0) {
                    addField(fields, trPrefix + ".length", String.valueOf(tr.getDurationMs()));
                }
                if (tr.getLocalFilePath() != null && !tr.getLocalFilePath().isBlank()) {
                    localMatchedCount++;
                    if ("LOCAL_PRECISE".equalsIgnoreCase(tr.getDurationSource())) {
                        localPreciseDurationCount++;
                    }
                }
                String recMbid = MusicBrainzArtistCache.normalizeValidMbid(tr.getExistingRecordingId());
                if (recMbid != null) {
                    addField(fields, trPrefix + ".recording", recMbid);
                }
                List<String> trackArtists = resolveArtistsList(tr.getArtists(), tr.getArtist());
                if (!trackArtists.isEmpty()) {
                    appendArtistCredit(fields, trPrefix + ".artist_credit", trackArtists, tr.getArtist(), draft.getEntityMbids());
                }
            }
            mediumIdx++;
        }

        if (!tracks.isEmpty() && localMatchedCount < tracks.size()) {
            warnings.add(String.format("本地已归档 %d/%d 首，已按官方完整 %d 首曲目表预填，仅对本地匹配曲目补强精准毫秒时长。",
                localMatchedCount, tracks.size(), tracks.size()));
        }

        // External authoritative URLs: 独立计配额——先收集全部可信来源供 edit_note，再挑最多 MAX_SEED_URLS 条专辑级链接进 Release urls.*
        List<CanonicalSource> allSafeSources = collectSafeIncludedSources(draft.getSourceUrls(), MAX_EDIT_NOTE_SOURCES);
        int uIdx = 0;
        for (CanonicalSource src : allSafeSources) {
            if (isTrackOrLyricsOnlyUrl(src.canonicalUrl())) {
                continue;
            }
            addField(fields, "urls." + uIdx + ".url", src.canonicalUrl());
            uIdx++;
            if (uIdx >= MAX_SEED_URLS) {
                break;
            }
        }
        if (allSafeSources.isEmpty()) {
            warnings.add("当前没有勾选可公开访问的一手来源链接，建议在 MusicBrainz 编辑说明中补充出处。");
        }

        String editNote = buildEditNote(allSafeSources, localPreciseDurationCount, tracks.size(), unattachedCatalogNumber);
        addField(fields, "edit_note", editNote);

        return new SeedPayload(true, null, RELEASE_ADD_URL, List.copyOf(fields), editNote, List.copyOf(warnings));
    }

    private static SeedPayload blocked(String reason, List<String> warnings) {
        return new SeedPayload(false, reason, RELEASE_ADD_URL, List.of(), "", List.copyOf(warnings));
    }

    private record CanonicalSource(String canonicalUrl, String title) { }

    /** 校验真实历法日期（拒绝如 2026-02-31 的非法日期）。 */
    public static boolean isValidPartialIsoDate(String rawDate) {
        String date = trimToNull(rawDate);
        if (date == null) return true;
        Matcher m = ISO_DATE.matcher(date);
        if (!m.matches()) return false;
        int year = Integer.parseInt(m.group(1));
        if (year < 1000 || year > 2100) return false;
        if (m.group(2) == null) return true;
        int month = Integer.parseInt(m.group(2));
        if (month < 1 || month > 12) return false;
        if (m.group(3) == null) return true;
        int day = Integer.parseInt(m.group(3));
        try {
            LocalDate.of(year, month, day);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    private static void appendReleaseEvent(List<SeedField> fields, String rawDate, String rawCountry) {
        String date = trimToNull(rawDate);
        if (date != null && isValidPartialIsoDate(date)) {
            Matcher m = ISO_DATE.matcher(date);
            if (m.matches()) {
                addField(fields, "events.0.date.year", m.group(1));
                if (m.group(2) != null) {
                    addField(fields, "events.0.date.month", String.valueOf(Integer.parseInt(m.group(2))));
                }
                if (m.group(3) != null) {
                    addField(fields, "events.0.date.day", String.valueOf(Integer.parseInt(m.group(3))));
                }
            }
        }
        String country = trimToNull(rawCountry);
        if (country != null) {
            String upper = country.toUpperCase(Locale.ROOT);
            if (upper.matches("^[A-Z]{2}$")) {
                addField(fields, "events.0.country", upper);
            }
        }
    }

    private static void appendArtistCredit(List<SeedField> fields,
                                           String prefix,
                                           List<String> artists,
                                           String displayString,
                                           Map<String, MbContributionItem.EntityMbidRef> entityMbids) {
        if (artists == null || artists.isEmpty()) {
            return;
        }
        List<String> joinPhrases = inferJoinPhrases(artists, displayString);
        for (int i = 0; i < artists.size(); i++) {
            String name = trimToNull(artists.get(i));
            if (name == null) continue;
            String base = prefix + ".names." + i;
            String mbid = null;
            if (entityMbids != null) {
                MbContributionItem.EntityMbidRef ref = entityMbids.get(MusicBrainzArtistCache.normalizePersonKey(name));
                if (ref != null) {
                    mbid = MusicBrainzArtistCache.normalizeValidMbid(ref.getMbid());
                }
            }
            if (mbid != null) {
                addField(fields, base + ".mbid", mbid);
                addField(fields, base + ".name", name);
            } else {
                addField(fields, base + ".artist.name", name);
                addField(fields, base + ".name", name);
            }
            if (i < artists.size() - 1) {
                String jp = i < joinPhrases.size() ? joinPhrases.get(i) : ", ";
                // MusicBrainz join_phrase 必须保留前后空格（例如 ", "、" & "），严禁 trim
                if (jp != null && !jp.isEmpty()) {
                    fields.add(new SeedField(base + ".join_phrase", jp));
                }
            }
        }
    }

    static List<String> inferJoinPhrases(List<String> artists, String displayString) {
        if (artists == null || artists.size() <= 1) {
            return List.of();
        }
        int gaps = artists.size() - 1;
        List<String> inferred = new ArrayList<>(gaps);
        String display = trimToNull(displayString);
        if (display != null) {
            int cursor = 0;
            boolean ok = true;
            for (int i = 0; i < artists.size(); i++) {
                String name = artists.get(i);
                int idx = display.indexOf(name, cursor);
                if (idx < 0) {
                    ok = false;
                    break;
                }
                if (i > 0) {
                    String gap = display.substring(cursor, idx);
                    inferred.add(gap.isEmpty() ? ", " : gap);
                }
                cursor = idx + name.length();
            }
            if (ok && inferred.size() == gaps) {
                return inferred;
            }
        }
        List<String> fallback = new ArrayList<>(gaps);
        for (int i = 0; i < gaps; i++) {
            fallback.add(i == gaps - 1 ? " & " : ", ");
        }
        return fallback;
    }

    private static List<CanonicalSource> collectSafeIncludedSources(List<MbContributionItem.SourceUrlEntry> raw, int limit) {
        List<CanonicalSource> result = new ArrayList<>();
        if (raw == null || limit <= 0) return result;
        Set<String> seen = new LinkedHashSet<>();
        for (MbContributionItem.SourceUrlEntry entry : raw) {
            if (entry == null || !entry.isIncluded() || entry.getUrl() == null || entry.getUrl().isBlank()) {
                continue;
            }
            if (!"HIGH".equalsIgnoreCase(entry.getReliability()) && !"MEDIUM".equalsIgnoreCase(entry.getReliability())) {
                continue;
            }
            String canon = canonicalSafePublicHttpUrl(entry.getUrl());
            if (canon == null || !seen.add(canon)) {
                continue;
            }
            result.add(new CanonicalSource(canon, trimToNull(entry.getTitle())));
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    /** 规范化并校验公网 HTTP/HTTPS URL（剥离追踪参数，拒绝内网/私网/凭证/非标端口）。 */
    public static String canonicalSafePublicHttpUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return null;
        String trimmed = rawUrl.trim();
        if (!isSafePublicHttpUrl(trimmed)) return null;
        String canon = com.lux032.musicautotagger.service.llm.WebSearchClient.canonicalizeUrl(trimmed);
        if (canon == null || canon.isBlank() || !isSafePublicHttpUrl(canon)) {
            return null;
        }
        return canon;
    }

    static boolean isSafePublicHttpUrl(String url) {
        if (url == null || url.isBlank()) return false;
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            return false;
        }
        if (uri.getUserInfo() != null) {
            return false;
        }
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) {
            return false;
        }
        String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isBlank()) {
            return false;
        }
        String host = OutboundUrlValidator.stripIpv6Brackets(rawHost).toLowerCase(Locale.ROOT);
        if (host.isEmpty() || "localhost".equals(host) || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".lan")
                || host.endsWith(".home") || host.endsWith(".arpa")) {
            return false;
        }
        // IPv4 / IPv6 字面量直接交给 OutboundUrlValidator.isPublic 严格判定（含 172.16/12、169.254/16、100.64/10、ULA 等）
        boolean isIpLiteral = host.indexOf(':') >= 0 || IPV4_LITERAL.matcher(host).matches() || host.matches("^\\d+$");
        if (isIpLiteral) {
            try {
                InetAddress addr = InetAddress.getByName(host);
                return OutboundUrlValidator.isPublic(addr);
            } catch (Exception e) {
                return false;
            }
        }
        // 域名必须含点且不得解析到私网/保留地址（无 DNS/纯代理离线环境仅在 UnknownHostException 时按公网 FQDN 放行）
        if (!host.contains(".")) {
            return false;
        }
        try {
            InetAddress[] resolved = InetAddress.getAllByName(host);
            if (resolved != null) {
                for (InetAddress addr : resolved) {
                    if (!OutboundUrlValidator.isPublic(addr)) {
                        return false;
                    }
                }
            }
        } catch (UnknownHostException ignored) {
            // 代理或离线测试环境下无法本地解析，已通过上述私网后缀与字面量校验
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    private static String buildEditNote(List<CanonicalSource> sources,
                                        int localPreciseCount,
                                        int totalTracks,
                                        String unattachedCatalogNumber) {
        StringBuilder sb = new StringBuilder();
        sb.append("Sources:\n");
        if (sources.isEmpty()) {
            sb.append("- (Please add primary official source URLs before submitting)\n");
        } else {
            for (CanonicalSource s : sources) {
                sb.append("- ").append(s.canonicalUrl());
                if (s.title() != null) {
                    sb.append(" (").append(s.title()).append(")");
                }
                sb.append('\n');
            }
        }
        if (unattachedCatalogNumber != null && !unattachedCatalogNumber.isBlank()) {
            sb.append("\nCatalog number (verify label assignment): ").append(unattachedCatalogNumber).append('\n');
        }
        if (localPreciseCount > 0) {
            sb.append("\nTrack lengths for ").append(localPreciseCount).append('/').append(totalTracks)
              .append(" track(s) measured from local audio files.\n");
        }
        sb.append("\nMetadata prepared with MusicAutoTagger and reviewed by editor before submission.");
        return sb.toString();
    }

    private static List<String> resolveArtistsList(List<String> structured, String display) {
        List<String> clean = new ArrayList<>();
        if (structured != null) {
            for (String s : structured) {
                String t = trimToNull(s);
                if (t != null) clean.add(t);
            }
        }
        if (clean.isEmpty()) {
            String d = trimToNull(display);
            if (d != null) clean.add(d);
        }
        return clean;
    }

    private static String normalizePrimaryType(String raw, MbContributionItem.ReleaseDraft draft) {
        String t = trimToNull(raw);
        if (t != null) {
            String lower = t.toLowerCase(Locale.ROOT);
            if (VALID_PRIMARY_TYPES.contains(lower)) {
                return lower;
            }
        }
        String comment = draft.getComment() == null ? "" : draft.getComment().toLowerCase(Locale.ROOT);
        if (comment.contains("single") || (draft.getTracks() != null && draft.getTracks().size() <= 2)) {
            return "single";
        }
        if (comment.contains("ep") || (draft.getTracks() != null && draft.getTracks().size() <= 6)) {
            return "ep";
        }
        return "album";
    }

    private static String normalizeMediaFormat(String raw) {
        String t = trimToNull(raw);
        if (t == null) return null;
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.contains("digital") || lower.equals("web")) return "Digital Media";
        if (lower.equals("cd")) return "CD";
        return t;
    }

    /** 判断是否为仅描述国家/格式/发行类型的通用消歧词（新建独立 RG 时按 MB 规范应留空）。 */
    static boolean isGenericFormatDisambiguation(String comment) {
        String t = trimToNull(comment);
        return t != null && GENERIC_FORMAT_DISAMBIGUATION.matcher(t).matches();
    }

    /** 判断 URL 是否为单曲歌词页或单轨商店页（保留在 edit_note 佐证，不塞入 Release 级外部链接）。 */
    public static boolean isTrackOrLyricsOnlyUrl(String url) {
        if (url == null || url.isBlank()) return false;
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            return false;
        }
        String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isBlank()) return false;
        String host = rawHost.toLowerCase(Locale.ROOT);
        for (String domain : LYRICS_ONLY_DOMAINS) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery().toLowerCase(Locale.ROOT);
        if ((host.equals("genius.com") || host.endsWith(".genius.com")) && path.endsWith("-lyrics")) {
            return true;
        }
        boolean isAppleMusic = host.equals("music.apple.com") || host.endsWith(".music.apple.com");
        if (isAppleMusic && path.contains("/album/") && hasQueryParam(query, "i")) {
            return true;
        }
        if ((host.equals("open.spotify.com") && path.startsWith("/track/"))
                || (isAppleMusic && path.contains("/song/"))
                || ((host.equals("kkbox.com") || host.endsWith(".kkbox.com")) && path.contains("/song/"))) {
            return true;
        }
        return false;
    }

    private static boolean hasQueryParam(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isEmpty()) return false;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            if (k.equals(name) && eq >= 0 && eq < pair.length() - 1) {
                return true;
            }
        }
        return false;
    }

    /** 将 "HAO Music / Studio LaRa" 等带空格分隔符的拼接厂牌拆分为多条独立厂牌（最多 5 条）。 */
    static List<String> splitCompositeLabels(String rawLabel) {
        String t = trimToNull(rawLabel);
        if (t == null) return List.of();
        String[] parts = COMPOSITE_LABEL_SPLIT.split(t);
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String part : parts) {
            String clean = trimToNull(part);
            if (clean != null && seen.add(clean.toLowerCase(Locale.ROOT))) {
                out.add(clean);
                if (out.size() >= 5) break;
            }
        }
        return out;
    }

    /**
     * 按单曲歌词证据保守推断 Work 歌词语言（仅非纯器乐且有本轨日文歌词证据时返回 {@code jpn}，其余留空避免误写器乐或混语曲目）。
     */
    public static String inferTrackLyricsLanguage(MbContributionItem.ContributionTrack track) {
        if (track == null) return null;
        String title = track.getTitle() == null ? "" : track.getTitle();
        if (INSTRUMENTAL_TITLE_PATTERN.matcher(title).find()) {
            return null;
        }
        boolean hasLyricist = track.getLyricists() != null && !track.getLyricists().isEmpty()
            && MusicBrainzCreditSanitizer.isAdmissibleCreditSource(track.getLyricistSource());
        if (!hasLyricist) {
            return null;
        }
        if (containsJapaneseKana(title)) {
            return "jpn";
        }
        if (track.getLyricistEvidenceQuotes() != null) {
            for (String q : track.getLyricistEvidenceQuotes()) {
                if (containsJapaneseKana(q)) {
                    return "jpn";
                }
            }
        }
        if (track.getLyricistEvidenceUrls() != null) {
            for (String u : track.getLyricistEvidenceUrls()) {
                if (u != null && (u.contains("uta-net.com") || u.contains("utamap.com")
                        || u.contains("j-lyric.net") || u.contains("utaten.com") || u.contains("kashinavi.com"))) {
                    return "jpn";
                }
            }
        }
        return null;
    }

    /** Release 级日文语言/文字保守推断：专辑标题含假名，或多数曲目含假名，或 JP 发行且至少一首曲目含假名。 */
    private static boolean shouldInferJapaneseReleaseLanguage(MbContributionItem.ReleaseDraft draft) {
        if (draft == null) return false;
        if (containsJapaneseKana(draft.getTitle())) return true;
        List<MbContributionItem.ContributionTrack> tracks = draft.getTracks();
        if (tracks == null || tracks.isEmpty()) return false;
        int total = 0;
        int kanaTracks = 0;
        for (MbContributionItem.ContributionTrack tr : tracks) {
            if (tr == null || trimToNull(tr.getTitle()) == null) continue;
            total++;
            if (containsJapaneseKana(tr.getTitle())) {
                kanaTracks++;
            }
        }
        if (kanaTracks == 0 || total == 0) return false;
        if ("JP".equalsIgnoreCase(trimToNull(draft.getCountry()))) {
            return true;
        }
        return kanaTracks * 2 >= total;
    }

    private static boolean containsJapaneseKana(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeBlock b = Character.UnicodeBlock.of(text.charAt(i));
            if (b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA) {
                return true;
            }
        }
        return false;
    }

    private static void addField(List<SeedField> fields, String name, String value) {
        String v = trimToNull(value);
        if (v != null) {
            fields.add(new SeedField(name, v));
        }
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
