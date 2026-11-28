package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.model.MbContributionItem;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * MusicBrainz 存在性只读预检服务。
 *
 * <p>按条码（barcode） -> 目录号（catalogNumber） -> 标题 + 艺人（优先 Artist MBID）+ 时长序列
 * 将联网确认归档的专辑分流为四类：
 * <ul>
 *   <li>Case A ({@code EXISTING_RELEASE}): MB 已有精确发行版，拦截新建并引导直接关联回填；</li>
 *   <li>Case B ({@code MISSING_EDITION}): 已有 Release Group 但缺当前发行版/地区版/加曲版；</li>
 *   <li>Case C ({@code MISSING_RELEASE_GROUP}): MB 尚未收录该专辑；</li>
 *   <li>Case D ({@code NOT_ELIGIBLE}): 非官方候选或缺少可靠来源/有效曲目覆盖，不自动推荐上传。</li>
 * </ul>
 */
@Slf4j
public class MusicBrainzExistenceChecker {

    private static final int MAX_RELEASE_DETAIL_PROBES = 5;
    private static final double MAX_AVG_DURATION_DIFF_SEC = 3.0;
    private static final int MAX_SINGLE_DURATION_DIFF_SEC = 6;
    private static final int BARCODE_TRACK_DURATION_TOLERANCE_SEC = 5;
    private static final Pattern EDITION_PAREN_SUFFIX = Pattern.compile("\\s*[\\(（][^\\)）]{1,80}[\\)）]\\s*$");
    private static final Pattern NON_ALNUM_KEEP_CJK = Pattern.compile("[^\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}a-z0-9]+");

    private final MusicBrainzClient mbClient;

    public MusicBrainzExistenceChecker(MusicBrainzClient mbClient) {
        this.mbClient = mbClient;
    }

    public record CheckResult(
        MbContributionItem.CheckCase checkCase,
        String checkCode,
        String message,
        String matchedReleaseId,
        String matchedReleaseGroupId,
        String matchedReleaseTitle,
        String matchedReleaseArtist,
        String matchedReleaseDate,
        String matchedReleaseCountry,
        int matchedTrackCount,
        List<MbContributionItem.ExistingReleaseSummary> existingGroupReleases
    ) {
        public static CheckResult notEligible(String message) {
            return new CheckResult(MbContributionItem.CheckCase.NOT_ELIGIBLE, "NOT_ELIGIBLE", message,
                null, null, null, null, null, null, 0, List.of());
        }

        public static CheckResult missingReleaseGroup(String message) {
            return new CheckResult(MbContributionItem.CheckCase.MISSING_RELEASE_GROUP, "MISSING_RELEASE_GROUP", message,
                null, null, null, null, null, null, 0, List.of());
        }

        public static CheckResult error(String checkCode, String message) {
            return new CheckResult(MbContributionItem.CheckCase.ERROR, checkCode, message,
                null, null, null, null, null, null, 0, List.of());
        }
    }

    /**
     * 对回馈草稿执行存在性预检。
     *
     * @param draft        回馈发行草稿
     * @param enforceCaseD 自动入队时传 {@code true}（拦截非官方/无可靠来源/不完整目录）；人工显式触发时仍可查找 Case A，
     *                     但若未命中已有发行，后续仍会校验是否具备合规 Seeding 资格。
     */
    public CheckResult check(MbContributionItem.ReleaseDraft draft, boolean enforceCaseD)
            throws IOException, InterruptedException {
        if (draft == null || !meaningful(draft.getTitle())) {
            return CheckResult.notEligible("缺少有效专辑标题");
        }
        String caseDReason = evaluateCaseDIneligibility(draft);
        if (enforceCaseD && caseDReason != null) {
            return CheckResult.notEligible(caseDReason);
        }
        if (mbClient == null) {
            return CheckResult.error("MB_UNAVAILABLE", "MusicBrainz 客户端不可用");
        }

        List<Integer> targetDurations = extractTargetDurationSeconds(draft);
        boolean hasPositiveTargetDurations = targetDurations.stream().anyMatch(d -> d != null && d > 0);
        int targetTrackCount = draft.getTracks() == null ? 0 : draft.getTracks().size();
        String primaryArtistName = resolvePrimaryArtistName(draft);
        String primaryArtistMbid = resolvePrimaryArtistMbid(draft, primaryArtistName);

        int releaseProbes = 0;
        boolean sawInconclusiveCandidate = false;

        // Step 1a: Barcode 精确检索（必须获得正证据，绝不因预算耗尽或缺失时长误判 Case A）
        String barcode = MusicBrainzClient.normalizeBarcodeDigits(draft.getBarcode());
        if (barcode != null) {
            List<MusicBrainzClient.ReleaseSearchHit> hits = mbClient.searchReleasesByBarcode(barcode);
            for (MusicBrainzClient.ReleaseSearchHit hit : hits) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("cancelled");
                if (hit == null || hit.releaseId() == null) continue;
                if (targetTrackCount > 0 && hit.trackCount() > 0 && hit.trackCount() != targetTrackCount) {
                    continue;
                }
                if (hasPositiveTargetDurations) {
                    if (releaseProbes >= MAX_RELEASE_DETAIL_PROBES) {
                        sawInconclusiveCandidate = true;
                        break;
                    }
                    releaseProbes++;
                    List<Integer> mbDurations = mbClient.getReleaseDurationSequence(hit.releaseId());
                    if (mbDurations == null || mbDurations.isEmpty()) {
                        if (titlesRoughlyMatch(draft.getTitle(), hit.title())) {
                            sawInconclusiveCandidate = true;
                        }
                        continue;
                    }
                    if (!durationsMatch(targetDurations, mbDurations, BARCODE_TRACK_DURATION_TOLERANCE_SEC, BARCODE_TRACK_DURATION_TOLERANCE_SEC)) {
                        continue;
                    }
                } else {
                    if (targetTrackCount <= 0 || hit.trackCount() != targetTrackCount
                            || !titlesRoughlyMatch(draft.getTitle(), hit.title())) {
                        sawInconclusiveCandidate = true;
                        continue;
                    }
                }
                return new CheckResult(
                    MbContributionItem.CheckCase.EXISTING_RELEASE,
                    "EXISTING_BARCODE",
                    "通过条码 " + barcode + " 命中 MusicBrainz 已有发行版",
                    hit.releaseId(), hit.releaseGroupId(), hit.title(), hit.artist(),
                    hit.date(), hit.country(), hit.trackCount(), List.of());
            }
        }

        // Step 1b: 目录号（Catalog Number）检索
        String catno = trimToNull(draft.getCatalogNumber());
        if (catno != null) {
            List<MusicBrainzClient.ReleaseSearchHit> hits = mbClient.searchReleasesByCatalogNumber(
                catno, primaryArtistMbid != null ? primaryArtistMbid : primaryArtistName);
            for (MusicBrainzClient.ReleaseSearchHit hit : hits) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("cancelled");
                if (hit == null || hit.releaseId() == null || hit.score() < 70) continue;
                if (targetTrackCount > 0 && hit.trackCount() > 0 && hit.trackCount() != targetTrackCount) {
                    continue;
                }
                if (!titlesRoughlyMatch(draft.getTitle(), hit.title())) {
                    continue;
                }
                if (hasPositiveTargetDurations) {
                    if (releaseProbes >= MAX_RELEASE_DETAIL_PROBES) {
                        sawInconclusiveCandidate = true;
                        break;
                    }
                    releaseProbes++;
                    List<Integer> mbDurations = mbClient.getReleaseDurationSequence(hit.releaseId());
                    if (mbDurations == null || mbDurations.isEmpty()) {
                        sawInconclusiveCandidate = true;
                        continue;
                    }
                    if (!durationsMatch(targetDurations, mbDurations, MAX_AVG_DURATION_DIFF_SEC, MAX_SINGLE_DURATION_DIFF_SEC)) {
                        continue;
                    }
                } else {
                    if (targetTrackCount <= 0 || hit.trackCount() != targetTrackCount) {
                        sawInconclusiveCandidate = true;
                        continue;
                    }
                }
                return new CheckResult(
                    MbContributionItem.CheckCase.EXISTING_RELEASE,
                    "EXISTING_CATNO",
                    "通过目录号 " + catno + " 命中 MusicBrainz 已有发行版",
                    hit.releaseId(), hit.releaseGroupId(), hit.title(), hit.artist(),
                    hit.date(), hit.country(), hit.trackCount(), List.of());
            }
        }

        // Step 2: Release Group 检索（优先使用已解析 Artist MBID）
        List<MusicBrainzClient.ReleaseGroupSearchHit> rgHits =
            mbClient.searchReleaseGroups(draft.getTitle(), primaryArtistName, primaryArtistMbid, 10);
        if (rgHits.isEmpty() && primaryArtistMbid != null && meaningful(primaryArtistName)) {
            rgHits = mbClient.searchReleaseGroups(draft.getTitle(), primaryArtistName, null, 10);
        }

        MusicBrainzClient.ReleaseGroupSearchHit matchedRg = selectBestMatchingReleaseGroup(
            rgHits, draft.getTitle(), primaryArtistName, primaryArtistMbid);
        if (matchedRg == null) {
            if (sawInconclusiveCandidate) {
                return CheckResult.error("CHECK_INCONCLUSIVE",
                    "MusicBrainz 预检中存在条码/目录号候选但因探查上限或缺少时长未能确证，请手动重新预检或人工核对");
            }
            if (caseDReason != null) {
                return CheckResult.notEligible(caseDReason);
            }
            return CheckResult.missingReleaseGroup("MusicBrainz 中尚未检索到对应的 Release Group 或发行版");
        }

        // 展开该 Release Group 下的现存 Releases 区分 Case A vs Case B
        List<MusicBrainzClient.AlbumDurationResult> groupReleases =
            mbClient.getReleasesForGroup(matchedRg.releaseGroupId());
        List<MbContributionItem.ExistingReleaseSummary> summaries = new ArrayList<>();
        if (groupReleases != null) {
            for (MusicBrainzClient.AlbumDurationResult rel : groupReleases) {
                if (rel == null || !meaningful(rel.getReleaseId())) continue;
                MbContributionItem.ExistingReleaseSummary summary = new MbContributionItem.ExistingReleaseSummary();
                summary.setReleaseId(rel.getReleaseId());
                summary.setTitle(rel.getReleaseTitle());
                summary.setDate(rel.getReleaseDate());
                summary.setCountry(rel.getReleaseCountry());
                summary.setMediaFormat(rel.getMediaFormat());
                summary.setTrackCount(rel.getTrackCount());
                summaries.add(summary);
            }
            for (MusicBrainzClient.AlbumDurationResult rel : groupReleases) {
                if (rel == null || !meaningful(rel.getReleaseId())) continue;
                if (isExactReleaseMatch(draft, targetTrackCount, targetDurations, hasPositiveTargetDurations, rel)) {
                    // 若草稿含明确目录号，在预算内二次校验是否存在目录号冲突（冲突则属于不同发行版，归 Case B）
                    if (catno != null && releaseProbes < MAX_RELEASE_DETAIL_PROBES) {
                        releaseProbes++;
                        Optional<MusicBrainzClient.ReleaseDetail> rd = mbClient.getFullReleaseWithTracklist(rel.getReleaseId());
                        if (rd.isPresent() && meaningful(rd.get().catalogNumber())
                                && !normalizeCatnoKey(catno).equals(normalizeCatnoKey(rd.get().catalogNumber()))) {
                            continue;
                        }
                    }
                    return new CheckResult(
                        MbContributionItem.CheckCase.EXISTING_RELEASE,
                        "EXISTING_GROUP_RELEASE",
                        "在已有 Release Group 中命中曲目数与时长一致的现存发行版",
                        rel.getReleaseId(),
                        matchedRg.releaseGroupId(),
                        meaningful(rel.getReleaseTitle()) ? rel.getReleaseTitle() : matchedRg.title(),
                        matchedRg.artist(),
                        rel.getReleaseDate(),
                        rel.getReleaseCountry(),
                        rel.getTrackCount(),
                        List.copyOf(summaries));
                }
            }
        }

        if (sawInconclusiveCandidate) {
            return CheckResult.error("CHECK_INCONCLUSIVE",
                "MusicBrainz 预检中存在条码/目录号候选但未能确证是否同一版本，请手动重新预检或人工核对");
        }
        if (caseDReason != null) {
            return CheckResult.notEligible(caseDReason);
        }
        return new CheckResult(
            MbContributionItem.CheckCase.MISSING_EDITION,
            "MISSING_EDITION",
            "MusicBrainz 已存在对应 Release Group，但缺少当前发行版（可挂入既有 Release Group）",
            null,
            matchedRg.releaseGroupId(),
            matchedRg.title(),
            matchedRg.artist(),
            matchedRg.firstReleaseDate(),
            null,
            targetTrackCount,
            List.copyOf(summaries));
    }

    static String evaluateCaseDIneligibility(MbContributionItem.ReleaseDraft draft) {
        if (draft == null || !meaningful(draft.getTitle())) {
            return "缺少有效专辑标题";
        }
        if (!draft.isOfficialCandidate()) {
            return "候选未通过官方发行门槛，不自动建议上传";
        }
        boolean hasReliableSource = draft.getSourceUrls() != null && draft.getSourceUrls().stream()
            .anyMatch(s -> s != null && meaningful(s.getUrl())
                && MusicBrainzSeedBuilder.isSafePublicHttpUrl(s.getUrl())
                && ("HIGH".equalsIgnoreCase(s.getReliability()) || "MEDIUM".equalsIgnoreCase(s.getReliability())));
        if (!hasReliableSource) {
            return "缺少可公开访问的权威一手来源链接，不自动建议上传";
        }
        if (draft.getTracks() == null || draft.getTracks().isEmpty()) {
            return "缺少有效曲目表，不自动建议上传";
        }
        long matchedLocal = draft.getTracks().stream()
            .filter(t -> t != null && meaningful(t.getLocalFilePath()) && t.getMatchedConfidence() >= 0.6)
            .count();
        if (matchedLocal == 0) {
            return "本地音频未可靠匹配候选曲目，不自动建议上传";
        }
        if (draft.getTrackCoverage() > 0 && draft.getTrackCoverage() < 0.5
                && matchedLocal < draft.getTracks().size()) {
            return "本地曲目覆盖率过低，暂不自动建议上传";
        }
        return null;
    }

    private boolean isExactReleaseMatch(MbContributionItem.ReleaseDraft draft,
                                        int targetTrackCount,
                                        List<Integer> targetDurations,
                                        boolean hasPositiveTargetDurations,
                                        MusicBrainzClient.AlbumDurationResult rel) {
        if (targetTrackCount > 0 && rel.getTrackCount() > 0 && rel.getTrackCount() != targetTrackCount) {
            return false;
        }
        // 严格区分发行地区（含 JP vs XW），避免把全球数字版与日本限定版混为一谈
        String wantCountry = normalizeCountry(draft.getCountry());
        String relCountry = normalizeCountry(rel.getReleaseCountry());
        if (wantCountry != null && relCountry != null && !wantCountry.equals(relCountry)) {
            return false;
        }
        // 若草稿明确指定了媒介格式（如 Digital Media vs CD）且不兼容，保留为缺失媒介版（Case B）
        if (hasDistinctMediaFormat(draft.getMediaFormat(), rel.getMediaFormat())) {
            return false;
        }
        List<Integer> mbDurations = rel.getDurations();
        if (!hasPositiveTargetDurations || mbDurations == null || mbDurations.isEmpty()) {
            return targetTrackCount > 0 && rel.getTrackCount() == targetTrackCount
                && wantCountry != null && wantCountry.equals(relCountry)
                && !hasDistinctMediaFormat(draft.getMediaFormat(), rel.getMediaFormat());
        }
        return durationsMatch(targetDurations, mbDurations, MAX_AVG_DURATION_DIFF_SEC, MAX_SINGLE_DURATION_DIFF_SEC);
    }

    private static String normalizeCatnoKey(String raw) {
        return raw == null ? "" : raw.replaceAll("[\\s-]+", "").toUpperCase(Locale.ROOT);
    }

    private static boolean hasDistinctMediaFormat(String draftFormat, String mbFormat) {
        String a = classifyMediaBucket(draftFormat);
        String b = classifyMediaBucket(mbFormat);
        return a != null && b != null && !a.equals(b);
    }

    private static String classifyMediaBucket(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("digital") || lower.contains("web") || lower.contains("stream")) return "DIGITAL";
        if (lower.contains("vinyl") || lower.contains("lp") || lower.contains("12\"") || lower.contains("7\"")) return "VINYL";
        if (lower.contains("cassette")) return "CASSETTE";
        if (lower.contains("cd") || lower.contains("sacd") || lower.contains("blu-spec") || lower.contains("hqcd") || lower.contains("shm")) return "CD";
        return null;
    }

    static boolean durationsMatch(List<Integer> left, List<Integer> right, double maxAvgDiff, int maxSingleDiff) {
        if (left == null || right == null || left.isEmpty() || left.size() != right.size()) {
            return false;
        }
        int compared = 0;
        long totalDiff = 0;
        for (int i = 0; i < left.size(); i++) {
            int l = left.get(i);
            int r = right.get(i);
            if (l <= 0 || r <= 0) continue;
            int diff = Math.abs(l - r);
            if (diff > maxSingleDiff) {
                return false;
            }
            totalDiff += diff;
            compared++;
        }
        if (compared == 0) {
            return false;
        }
        return (totalDiff / (double) compared) <= maxAvgDiff;
    }

    private MusicBrainzClient.ReleaseGroupSearchHit selectBestMatchingReleaseGroup(
            List<MusicBrainzClient.ReleaseGroupSearchHit> hits,
            String targetTitle,
            String artistName,
            String artistMbid) {
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        MusicBrainzClient.ReleaseGroupSearchHit best = null;
        for (MusicBrainzClient.ReleaseGroupSearchHit hit : hits) {
            if (hit == null || hit.releaseGroupId() == null || hit.score() < 75) continue;
            if (!titlesRoughlyMatch(targetTitle, hit.title())) continue;
            boolean artistOk = false;
            if (artistMbid != null && hit.artistMbids() != null && hit.artistMbids().contains(artistMbid)) {
                artistOk = true;
            } else if (meaningful(artistName) && meaningful(hit.artist())) {
                artistOk = normalizeTitleKey(artistName).equals(normalizeTitleKey(hit.artist()))
                    || normalizeTitleKey(hit.artist()).contains(normalizeTitleKey(artistName))
                    || normalizeTitleKey(artistName).contains(normalizeTitleKey(hit.artist()));
            } else if (!meaningful(artistName)) {
                artistOk = hit.score() >= 95;
            }
            if (!artistOk) continue;
            if (best == null || hit.score() > best.score()) {
                best = hit;
            }
        }
        return best;
    }

    static boolean titlesRoughlyMatch(String a, String b) {
        String na = normalizeTitleKey(stripEditionSuffix(a));
        String nb = normalizeTitleKey(stripEditionSuffix(b));
        return !na.isEmpty() && na.equals(nb);
    }

    private static String stripEditionSuffix(String title) {
        if (title == null) return "";
        String trimmed = title.trim();
        String stripped = EDITION_PAREN_SUFFIX.matcher(trimmed).replaceFirst("").trim();
        return stripped.isEmpty() ? trimmed : stripped;
    }

    private static String normalizeTitleKey(String raw) {
        if (raw == null) return "";
        String nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
        return NON_ALNUM_KEEP_CJK.matcher(nfkc).replaceAll("");
    }

    private static List<Integer> extractTargetDurationSeconds(MbContributionItem.ReleaseDraft draft) {
        List<Integer> result = new ArrayList<>();
        if (draft.getTracks() == null) return result;
        for (MbContributionItem.ContributionTrack t : draft.getTracks()) {
            if (t == null) continue;
            result.add(t.getDurationMs() > 0 ? (int) Math.round(t.getDurationMs() / 1000.0) : 0);
        }
        return result;
    }

    private static String resolvePrimaryArtistName(MbContributionItem.ReleaseDraft draft) {
        if (draft.getAlbumArtists() != null && !draft.getAlbumArtists().isEmpty()) {
            String first = trimToNull(draft.getAlbumArtists().get(0));
            if (first != null && !AlbumArtistPolicy.VARIOUS_ARTISTS.equalsIgnoreCase(first)) {
                return first;
            }
        }
        String aa = trimToNull(draft.getAlbumArtist());
        if (aa != null && !AlbumArtistPolicy.VARIOUS_ARTISTS.equalsIgnoreCase(aa)) {
            return aa;
        }
        return null;
    }

    private static String resolvePrimaryArtistMbid(MbContributionItem.ReleaseDraft draft, String primaryArtistName) {
        if (primaryArtistName == null || draft.getEntityMbids() == null || draft.getEntityMbids().isEmpty()) {
            return null;
        }
        String key = MusicBrainzArtistCache.normalizePersonKey(primaryArtistName);
        MbContributionItem.EntityMbidRef ref = draft.getEntityMbids().get(key);
        return ref == null ? null : MusicBrainzArtistCache.normalizeValidMbid(ref.getMbid());
    }

    private static String normalizeCountry(String raw) {
        String t = trimToNull(raw);
        if (t == null) return null;
        String upper = t.toUpperCase(Locale.ROOT);
        return upper.matches("^[A-Z]{2}$") ? upper : null;
    }

    private static boolean meaningful(String s) {
        return s != null && !s.isBlank();
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
