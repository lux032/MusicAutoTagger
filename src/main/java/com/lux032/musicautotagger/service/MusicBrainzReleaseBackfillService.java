package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import com.lux032.musicautotagger.model.MbContributionItem;
import com.lux032.musicautotagger.model.MusicMetadata;
import com.lux032.musicautotagger.model.ProcessedRecord;
import com.lux032.musicautotagger.util.AudioFileOrdering;
import com.lux032.musicautotagger.util.FileSystemUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 贴回 MusicBrainz Release URL/MBID 后，权威拉取官方发行元数据并原位回填本地已归档音频与处理记录。
 *
 * <p>核心不变量：
 * <ul>
 *   <li>使用真实路径（{@code toRealPath()} + {@code NOFOLLOW_LINKS}）严格校验输出目录包含关系，禁止符号链接逃逸；记录清单中任一文件缺失即拒绝回填。</li>
 *   <li>严格校验曲目一对一匹配（含标题与时长双重防线，未知时长必须匹配标题），任何失配在写盘前整体拒绝。</li>
 *   <li>全程在 {@link LibraryWriteGuard} 锁内完成复检、带回滚保护的音频标签写入与 {@code processed_files} 原位原子升级；失败时回滚全部已改音频并拒绝标记 {@code LINKED}。</li>
 * </ul>
 */
@Slf4j
public class MusicBrainzReleaseBackfillService {

    private static final int MAX_DURATION_TOLERANCE_SEC = 10;
    private static final Pattern MB_RELEASE_URL_PATTERN = Pattern.compile(
        "(?i)^https?://(?:[a-z0-9-]+\\.)?musicbrainz\\.org/release/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?:[/?#].*)?$");
    private static final Pattern NON_ALNUM_KEEP_CJK = Pattern.compile("[^\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}a-z0-9]+");
    private static final Pattern PAREN_SUFFIX = Pattern.compile("\\s*[\\(（][^\\)）]{1,80}[\\)）]\\s*$");

    private final MusicConfig config;
    private final MusicBrainzClient mbClient;
    private final TagWriterService tagWriter;
    private final ProcessedFileLogger processedLogger;
    private final FileSystemUtils fileSystemUtils;

    public MusicBrainzReleaseBackfillService(MusicConfig config,
                                             MusicBrainzClient mbClient,
                                             TagWriterService tagWriter,
                                             ProcessedFileLogger processedLogger,
                                             FileSystemUtils fileSystemUtils) {
        this.config = config;
        this.mbClient = mbClient;
        this.tagWriter = tagWriter;
        this.processedLogger = processedLogger;
        this.fileSystemUtils = fileSystemUtils;
    }

    public static class BackfillException extends IOException {
        private final int httpStatus;
        private final String errorCode;

        public BackfillException(int httpStatus, String errorCode, String message) {
            super(message);
            this.httpStatus = httpStatus;
            this.errorCode = errorCode;
        }

        public int getHttpStatus() { return httpStatus; }
        public String getErrorCode() { return errorCode; }
    }

    public record BackfillResult(
        String releaseId,
        String releaseGroupId,
        String releaseTitle,
        int updatedFiles,
        int updatedRecords,
        List<MbContributionItem.LinkedTrackMapping> trackMappings
    ) { }

    /** 解析并验证用户输入的 MusicBrainz Release URL 或纯 UUID。 */
    public static String extractReleaseMbid(String rawUrlOrMbid) {
        if (rawUrlOrMbid == null || rawUrlOrMbid.isBlank()) {
            return null;
        }
        String trimmed = rawUrlOrMbid.trim();
        String direct = MusicBrainzArtistCache.normalizeValidMbid(trimmed);
        if (direct != null) {
            return direct;
        }
        Matcher m = MB_RELEASE_URL_PATTERN.matcher(trimmed);
        if (m.matches()) {
            return MusicBrainzArtistCache.normalizeValidMbid(m.group(1));
        }
        return null;
    }

    /**
     * 共享 fail-closed 真实路径校验器：确保目标普通文件（非符号链接）及其所有父目录均严格位于配置的输出目录真实根内。
     */
    public static Optional<Path> resolveRealFileUnderOutputRoot(MusicConfig config, String rawPath) {
        if (config == null || rawPath == null || rawPath.isBlank()) {
            return Optional.empty();
        }
        String outDir = config.getOutputDirectory();
        if (outDir == null || outDir.isBlank()) {
            return Optional.empty();
        }
        try {
            Path realRoot = Path.of(outDir.trim()).toRealPath();
            if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            Path candidate = Path.of(rawPath.trim()).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            Path realFile = candidate.toRealPath();
            if (!realFile.startsWith(realRoot) || realFile.equals(realRoot)) {
                return Optional.empty();
            }
            return Optional.of(realFile);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 共享 fail-closed 真实目录校验器：确保目录（非符号链接）严格位于配置的输出目录真实根内。 */
    public static Optional<Path> resolveRealDirUnderOutputRoot(MusicConfig config, String rawDir) {
        if (config == null || rawDir == null || rawDir.isBlank()) {
            return Optional.empty();
        }
        String outDir = config.getOutputDirectory();
        if (outDir == null || outDir.isBlank()) {
            return Optional.empty();
        }
        try {
            Path realRoot = Path.of(outDir.trim()).toRealPath();
            if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            Path candidate = Path.of(rawDir.trim()).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(candidate) || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            Path realDir = candidate.toRealPath();
            if (!realDir.startsWith(realRoot) || realDir.equals(realRoot)) {
                return Optional.empty();
            }
            return Optional.of(realDir);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public BackfillResult linkAndBackfill(MbContributionItem item, String rawReleaseUrlOrMbid)
            throws IOException, InterruptedException {
        if (item == null) {
            throw new BackfillException(404, "mb.contribution.not.found", "回馈条目不存在");
        }
        String releaseId = extractReleaseMbid(rawReleaseUrlOrMbid);
        if (releaseId == null) {
            throw new BackfillException(400, "mb.contribution.release.invalid",
                "请输入合法的 MusicBrainz Release 链接（https://musicbrainz.org/release/<uuid>）或 UUID");
        }
        if (mbClient == null) {
            throw new BackfillException(503, "musicbrainz.unavailable", "MusicBrainz 客户端不可用");
        }
        Optional<MusicBrainzClient.ReleaseDetail> detailOpt = mbClient.getFullReleaseWithTracklist(releaseId);
        if (detailOpt.isEmpty()) {
            throw new BackfillException(404, "mb.contribution.release.not.found",
                "MusicBrainz 上未找到该 Release（请确认提交已生效且 UUID 正确）");
        }
        MusicBrainzClient.ReleaseDetail detail = detailOpt.get();
        if (MusicBrainzArtistCache.normalizeValidMbid(detail.releaseId()) == null) {
            throw new BackfillException(400, "mb.contribution.release.invalid", "远端 Release MBID 无效");
        }
        List<MusicBrainzClient.ReleaseTrackDetail> officialTracks = detail.allTracks();
        if (officialTracks.isEmpty()) {
            throw new BackfillException(400, "mb.contribution.release.empty.tracks",
                "目标 MusicBrainz Release 不包含任何曲目信息");
        }

        // 在 LibraryWriteGuard 内完成真实路径复检、严格曲目匹配、带回滚保护的音频回填与处理记录原子升级
        try (var guard = LibraryWriteGuard.acquireInterruptibly()) {
            List<File> localFiles = resolveLiveLocalArchivedFiles(item);
            if (localFiles.isEmpty()) {
                throw new BackfillException(400, "mb.contribution.local.files.missing",
                    "本地已归档音频文件不存在或已移出输出目录，无法回填标签");
            }

            List<MatchedPair> pairs = matchLocalFilesToOfficialTracks(localFiles, officialTracks, detail);
            // 写盘前统一校验所有已匹配官方曲目均具备合法 Recording MBID
            for (MatchedPair pair : pairs) {
                if (MusicBrainzArtistCache.normalizeValidMbid(pair.officialTrack().recordingId()) == null) {
                    throw new BackfillException(400, "mb.contribution.release.empty.tracks",
                        "目标 MusicBrainz Release 存在缺少合法 Recording MBID 的曲目，已中止回填");
                }
            }

            Map<Path, Path> backups = new LinkedHashMap<>();
            List<Path> modifiedFiles = new ArrayList<>();
            List<MbContributionItem.LinkedTrackMapping> mappings = new ArrayList<>(pairs.size());
            try {
                // 先在同目录备份全部待改文件，确保任一写盘或数据库提交失败时完整回滚
                for (MatchedPair pair : pairs) {
                    Path target = pair.localFile().toPath();
                    Path backup = Files.createTempFile(target.getParent(), ".mb-backfill-bak-", ".tmp");
                    Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                    backups.put(target, backup);
                }

                for (MatchedPair pair : pairs) {
                    File file = pair.localFile();
                    MusicBrainzClient.ReleaseTrackDetail track = pair.officialTrack();
                    MusicMetadata existing = tagWriter.readTags(file);
                    MusicMetadata merged = mergeOfficialBackfillMetadata(existing, detail, track);
                    boolean ok = tagWriter.updateTagsOnExistingFile(file, merged, null);
                    if (!ok) {
                        throw new BackfillException(500, "mb.contribution.backfill.write.failed",
                            "回填写入本地音频标签失败: " + file.getName());
                    }
                    modifiedFiles.add(file.toPath());

                    MbContributionItem.LinkedTrackMapping mapping = new MbContributionItem.LinkedTrackMapping();
                    mapping.setLocalFilePath(file.getAbsolutePath());
                    mapping.setTrackId(track.trackId());
                    mapping.setRecordingId(track.recordingId());
                    mapping.setDiscNo(track.discNo());
                    mapping.setTrackNo(track.trackNo());
                    mapping.setTitle(track.title());
                    mapping.setDurationMs(track.lengthMs());
                    mappings.add(mapping);
                }

                int updatedRecords = updateProcessedRecordsPreservingLineage(detail, pairs);

                log.info("MusicBrainz Release 回填完成: releaseId={}, rgId={}, 音频={} 首, 记录={} 条",
                    detail.releaseId(), detail.releaseGroupId(), modifiedFiles.size(), updatedRecords);
                LogCollector.addLog("SUCCESS", String.format("已关联 MusicBrainz Release 并回填 %d 首本地官方 MBID: %s",
                    modifiedFiles.size(), detail.title() != null ? detail.title() : detail.releaseId()));

                return new BackfillResult(
                    detail.releaseId(),
                    detail.releaseGroupId(),
                    detail.title(),
                    modifiedFiles.size(),
                    updatedRecords,
                    List.copyOf(mappings));
            } catch (Exception e) {
                rollbackModifiedFiles(modifiedFiles, backups);
                if (e instanceof BackfillException be) {
                    throw be;
                }
                if (e instanceof IOException ioe) {
                    throw ioe;
                }
                throw new BackfillException(500, "mb.contribution.backfill.write.failed",
                    "回填过程发生异常并已回滚: " + e.getMessage());
            } finally {
                for (Path bak : backups.values()) {
                    try {
                        Files.deleteIfExists(bak);
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private void rollbackModifiedFiles(List<Path> modifiedFiles, Map<Path, Path> backups) {
        for (Path target : modifiedFiles) {
            Path bak = backups.get(target);
            if (bak == null || !Files.isRegularFile(bak, LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                Files.copy(bak, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                log.error("回填失败后回滚音频文件失败 ({}): {}", target, ex.getMessage());
            }
        }
    }

    private List<File> resolveLiveLocalArchivedFiles(MbContributionItem item) throws BackfillException {
        List<String> recorded = item.getArchivedFilePaths();
        Set<Path> live = new LinkedHashSet<>();

        // 若条目记录了明确的归档文件清单，必须全部通过真实路径校验；任一文件缺失或越界即拒绝写盘
        if (recorded != null && !recorded.isEmpty()) {
            for (String raw : recorded) {
                if (raw == null || raw.isBlank()) {
                    throw new BackfillException(400, "mb.contribution.local.files.missing",
                        "归档文件清单包含空路径，无法安全回填");
                }
                Optional<Path> verified = resolveRealFileUnderOutputRoot(config, raw);
                if (verified.isEmpty()) {
                    throw new BackfillException(400, "mb.contribution.local.files.missing",
                        "已记录的归档音频文件不存在或已越出输出目录，已中止回填");
                }
                live.add(verified.get());
            }
        } else if (item.getArchivedOutputDir() != null && !item.getArchivedOutputDir().isBlank()) {
            // 仅对无文件清单的遗留条目允许按归档目录扫描（同样执行真实路径校验）
            Optional<Path> realDir = resolveRealDirUnderOutputRoot(config, item.getArchivedOutputDir());
            if (realDir.isPresent()) {
                List<File> collected = new ArrayList<>();
                if (fileSystemUtils != null) {
                    fileSystemUtils.collectAudioFilesForMarking(realDir.get().toFile(), collected);
                } else {
                    collectAudioFallback(realDir.get().toFile(), collected);
                }
                for (File f : collected) {
                    resolveRealFileUnderOutputRoot(config, f.getAbsolutePath()).ifPresent(live::add);
                }
            }
        }

        List<File> result = new ArrayList<>(live.size());
        for (Path p : live) {
            result.add(p.toFile());
        }
        result.sort(AudioFileOrdering.comparator());
        return result;
    }

    private record LocalTrackProbe(File file, int discNo, int trackNo, String title, String album, int durationSec) { }
    private record MatchedPair(File localFile, MusicBrainzClient.ReleaseTrackDetail officialTrack) { }

    private List<MatchedPair> matchLocalFilesToOfficialTracks(List<File> localFiles,
                                                              List<MusicBrainzClient.ReleaseTrackDetail> officialTracks,
                                                              MusicBrainzClient.ReleaseDetail detail)
            throws BackfillException {
        if (localFiles.size() > officialTracks.size()) {
            throw new BackfillException(400, "mb.contribution.track.mismatch",
                String.format("本地音频数量（%d）超过目标 MusicBrainz Release 曲目数（%d），已拒绝回填",
                    localFiles.size(), officialTracks.size()));
        }
        List<LocalTrackProbe> probes = new ArrayList<>(localFiles.size());
        for (File f : localFiles) {
            MusicMetadata md = tagWriter.readTags(f);
            int disc = parsePositiveInt(md == null ? null : md.getDiscNo(), 1);
            int trk = parsePositiveInt(md == null ? null : md.getTrackNo(), 0);
            String title = md == null ? null : md.getTitle();
            String album = md == null ? null : md.getAlbum();
            Double precise = TagWriterService.readPreciseDuration(f);
            int durSec = precise != null && precise > 0
                ? (int) Math.round(precise)
                : (md != null && md.getDuration() > 0 ? md.getDuration() : 0);
            probes.add(new LocalTrackProbe(f, disc, trk, title, album, durSec));
        }

        // 策略 1：按 (discNo, trackNo) 精确键匹配（校验时长，无时长时强制校验标题；同时拒绝全曲且专辑标题均矛盾的错误发行）
        Map<String, MusicBrainzClient.ReleaseTrackDetail> byDiscTrack = new HashMap<>();
        for (MusicBrainzClient.ReleaseTrackDetail ot : officialTracks) {
            byDiscTrack.put(ot.discNo() + ":" + ot.trackNo(), ot);
        }
        boolean allKeyed = probes.stream().allMatch(p -> p.trackNo() > 0);
        if (allKeyed) {
            List<MatchedPair> keyedPairs = new ArrayList<>(probes.size());
            Set<String> usedOfficial = new HashSet<>();
            boolean ok = true;
            for (LocalTrackProbe p : probes) {
                String key = p.discNo() + ":" + p.trackNo();
                MusicBrainzClient.ReleaseTrackDetail ot = byDiscTrack.get(key);
                if (ot == null || !usedOfficial.add(key) || !isTrackEvidenceCompatible(p, ot)) {
                    ok = false;
                    break;
                }
                keyedPairs.add(new MatchedPair(p.file(), ot));
            }
            if (ok && keyedPairs.size() == probes.size() && isAlbumIdentityCoherent(probes, keyedPairs, detail)) {
                return keyedPairs;
            }
        }

        // 策略 2：曲目数完全一致时按排序顺序逐首核对（同样执行时长/标题双重防线与专辑一致性检查）
        if (probes.size() == officialTracks.size()) {
            List<MusicBrainzClient.ReleaseTrackDetail> sortedOfficial = new ArrayList<>(officialTracks);
            sortedOfficial.sort(Comparator.comparingInt(MusicBrainzClient.ReleaseTrackDetail::discNo)
                .thenComparingInt(MusicBrainzClient.ReleaseTrackDetail::trackNo));
            List<MatchedPair> ordered = new ArrayList<>(probes.size());
            boolean ok = true;
            for (int i = 0; i < probes.size(); i++) {
                LocalTrackProbe p = probes.get(i);
                MusicBrainzClient.ReleaseTrackDetail ot = sortedOfficial.get(i);
                if (!isTrackEvidenceCompatible(p, ot)) {
                    ok = false;
                    break;
                }
                ordered.add(new MatchedPair(p.file(), ot));
            }
            if (ok && isAlbumIdentityCoherent(probes, ordered, detail)) {
                return ordered;
            }
        }

        // 策略 3：仅按唯一规范化标题 + 时长容差安全匹配（支持局部缺轨且标签无曲号）
        Map<String, List<MusicBrainzClient.ReleaseTrackDetail>> byTitle = new HashMap<>();
        for (MusicBrainzClient.ReleaseTrackDetail ot : officialTracks) {
            String key = normalizeTitleKey(ot.title());
            if (!key.isEmpty()) {
                byTitle.computeIfAbsent(key, k -> new ArrayList<>()).add(ot);
            }
        }
        List<MatchedPair> titlePairs = new ArrayList<>(probes.size());
        Set<String> usedTrackKey = new HashSet<>();
        for (LocalTrackProbe p : probes) {
            String key = normalizeTitleKey(p.title());
            List<MusicBrainzClient.ReleaseTrackDetail> cands = key.isEmpty() ? List.of() : byTitle.getOrDefault(key, List.of());
            MusicBrainzClient.ReleaseTrackDetail matched = null;
            for (MusicBrainzClient.ReleaseTrackDetail cand : cands) {
                String tk = cand.discNo() + ":" + cand.trackNo();
                if (!usedTrackKey.contains(tk) && isDurationCompatibleWhenPresent(p.durationSec(), cand.lengthMs())) {
                    if (matched != null) {
                        matched = null;
                        break;
                    }
                    matched = cand;
                }
            }
            if (matched == null) {
                throw new BackfillException(400, "mb.contribution.track.mismatch",
                    "曲目与目标 MusicBrainz Release 存在曲序、标题或时长失配（" + p.file().getName() + "），已中止回填以防写错 Recording ID");
            }
            usedTrackKey.add(matched.discNo() + ":" + matched.trackNo());
            titlePairs.add(new MatchedPair(p.file(), matched));
        }
        return titlePairs;
    }

    /** 单曲独立兼容性检查：双边有时长时校验容差；任一方无时长时强制校验标题兼容，绝不盲目放行未知时长。 */
    private static boolean isTrackEvidenceCompatible(LocalTrackProbe p, MusicBrainzClient.ReleaseTrackDetail ot) {
        boolean hasBothDurations = p.durationSec() > 0 && ot.lengthMs() > 0L;
        if (hasBothDurations) {
            int officialSec = (int) Math.round(ot.lengthMs() / 1000.0);
            return Math.abs(p.durationSec() - officialSec) <= MAX_DURATION_TOLERANCE_SEC;
        }
        return titlesCompatible(p.title(), ot.title());
    }

    private static boolean isDurationCompatibleWhenPresent(int localSec, long officialMs) {
        if (localSec <= 0 || officialMs <= 0L) {
            return true;
        }
        int officialSec = (int) Math.round(officialMs / 1000.0);
        return Math.abs(localSec - officialSec) <= MAX_DURATION_TOLERANCE_SEC;
    }

    /**
     * 专辑整体身份一致性防线：防止同轨数、巧合相近时长的完全无关专辑通过位置匹配混入。
     * 只要至少有一首曲目标题兼容，或本地专辑名与官方 Release 标题兼容，即允许（兼顾日文/罗马音互译场景）；
     * 若全部可读曲目标题与专辑标题均严重矛盾，则拒绝回填。
     */
    private static boolean isAlbumIdentityCoherent(List<LocalTrackProbe> probes,
                                                   List<MatchedPair> pairs,
                                                   MusicBrainzClient.ReleaseDetail detail) {
        int comparableTitles = 0;
        int matchedTitles = 0;
        for (int i = 0; i < probes.size(); i++) {
            String localTitle = probes.get(i).title();
            String officialTitle = pairs.get(i).officialTrack().title();
            if (meaningful(localTitle) && meaningful(officialTitle)) {
                comparableTitles++;
                if (titlesCompatible(localTitle, officialTitle)) {
                    matchedTitles++;
                }
            }
        }
        if (matchedTitles > 0) {
            return true;
        }
        boolean anyAlbumCompared = false;
        boolean albumMatched = false;
        for (LocalTrackProbe p : probes) {
            if (meaningful(p.album()) && meaningful(detail.title())) {
                anyAlbumCompared = true;
                if (titlesCompatible(p.album(), detail.title())) {
                    albumMatched = true;
                    break;
                }
            }
        }
        if (comparableTitles > 0 && !albumMatched) {
            return false;
        }
        return !anyAlbumCompared || albumMatched;
    }

    private static boolean titlesCompatible(String a, String b) {
        String na = normalizeTitleKey(a);
        String nb = normalizeTitleKey(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return false;
        }
        if (na.equals(nb) || na.contains(nb) || nb.contains(na)) {
            return true;
        }
        String sa = normalizeTitleKey(stripParenSuffix(a));
        String sb = normalizeTitleKey(stripParenSuffix(b));
        return !sa.isEmpty() && !sb.isEmpty() && (sa.equals(sb) || sa.contains(sb) || sb.contains(sa));
    }

    private static String stripParenSuffix(String raw) {
        if (raw == null) return "";
        String stripped = PAREN_SUFFIX.matcher(raw.trim()).replaceFirst("").trim();
        return stripped.isEmpty() ? raw.trim() : stripped;
    }

    /**
     * 构造合并后的标签对象：权威覆盖官方 MBID，仅在本地缺失时补齐发行元数据，绝不覆盖既有词曲编与封面。
     */
    static MusicMetadata mergeOfficialBackfillMetadata(MusicMetadata existing,
                                                       MusicBrainzClient.ReleaseDetail detail,
                                                       MusicBrainzClient.ReleaseTrackDetail track) {
        MusicMetadata merged = existing == null ? new MusicMetadata() : existing.copyWithoutHeavyFields();

        // 权威官方 MBID
        merged.setReleaseId(detail.releaseId());
        if (meaningful(detail.releaseGroupId())) {
            merged.setReleaseGroupId(detail.releaseGroupId());
        }
        if (meaningful(track.recordingId())) {
            merged.setRecordingId(track.recordingId());
        }
        if (meaningful(track.trackId())) {
            merged.setReleaseTrackId(track.trackId());
        }
        if (meaningful(track.artistId())) {
            merged.setClearArtistId(true);
            merged.setArtistId(track.artistId());
            if (meaningful(track.artistSort())) {
                merged.setArtistSort(track.artistSort());
            }
        }
        if (meaningful(detail.albumArtistId())) {
            merged.setClearAlbumArtistId(true);
            merged.setAlbumArtistId(detail.albumArtistId());
            if (meaningful(detail.albumArtistSort())) {
                merged.setAlbumArtistSort(detail.albumArtistSort());
            }
        }

        // 仅在缺失时补全官方发行级元数据
        if (!meaningful(merged.getBarcode()) && meaningful(detail.barcode())) {
            merged.setBarcode(detail.barcode());
        }
        if (!meaningful(merged.getCatalogNumber()) && meaningful(detail.catalogNumber())) {
            merged.setCatalogNumber(detail.catalogNumber());
        }
        if (!meaningful(merged.getRecordLabel()) && meaningful(detail.recordLabel())) {
            merged.setRecordLabel(detail.recordLabel());
        }
        if (!meaningful(merged.getReleaseCountry()) && meaningful(detail.country())) {
            merged.setReleaseCountry(detail.country());
        }
        if (!meaningful(merged.getReleaseStatus()) && meaningful(detail.status())) {
            merged.setReleaseStatus(detail.status());
        }
        if (!meaningful(merged.getScript()) && meaningful(detail.script())) {
            merged.setScript(detail.script());
        }
        if (!meaningful(merged.getReleaseDate()) && meaningful(detail.date())) {
            merged.setReleaseDate(detail.date());
        }
        if (!meaningful(merged.getOriginalReleaseDate()) && meaningful(detail.originalReleaseDate())) {
            merged.setOriginalReleaseDate(detail.originalReleaseDate());
        }
        if (!meaningful(merged.getOriginalYear()) && meaningful(detail.originalYear())) {
            merged.setOriginalYear(detail.originalYear());
        }
        if (!meaningful(merged.getReleaseType()) && meaningful(detail.primaryType())) {
            merged.setReleaseType(detail.primaryType());
        }
        if (!meaningful(merged.getDiscTotal()) && detail.discTotal() > 0) {
            merged.setDiscTotal(String.valueOf(detail.discTotal()));
        }
        if (detail.compilation()) {
            merged.setCompilation(true);
        }

        // 严格保护既有词曲编不被清空标志误删
        merged.setClearComposer(false);
        merged.setClearLyricist(false);
        merged.setClearArranger(false);
        merged.setClearAlbumLevelTags(false);
        return merged;
    }

    private int updateProcessedRecordsPreservingLineage(MusicBrainzClient.ReleaseDetail detail,
                                                        List<MatchedPair> pairs) throws BackfillException {
        if (processedLogger == null || pairs.isEmpty()) {
            return 0;
        }
        List<String> targetPaths = pairs.stream().map(p -> p.localFile().getAbsolutePath()).toList();
        Map<String, ProcessedRecord> byTarget = new HashMap<>();
        for (ProcessedRecord rec : processedLogger.findRecordsByTargetPaths(targetPaths)) {
            if (rec != null && meaningful(rec.targetFilePath)) {
                byTarget.putIfAbsent(normalizePath(rec.targetFilePath), rec);
            }
        }
        Map<String, ProcessedRecord> byFile = new HashMap<>();
        for (ProcessedRecord rec : processedLogger.findRecordsByPaths(targetPaths)) {
            if (rec != null && meaningful(rec.filePath)) {
                byFile.putIfAbsent(normalizePath(rec.filePath), rec);
            }
        }

        List<ProcessedFileLogger.RecordMigration> migrations = new ArrayList<>();
        for (MatchedPair pair : pairs) {
            String absTarget = pair.localFile().getAbsolutePath();
            String normTarget = normalizePath(absTarget);
            ProcessedRecord existing = byTarget.get(normTarget);
            if (existing == null) {
                existing = byFile.get(normTarget);
            }
            if (existing == null || !meaningful(existing.filePath)) {
                continue;
            }
            String recordingId = meaningful(pair.officialTrack().recordingId())
                ? pair.officialTrack().recordingId() : existing.recordingId;
            String releaseGroupId = meaningful(detail.releaseGroupId())
                ? detail.releaseGroupId() : existing.releaseGroupId;
            LocalDateTime origTime = processedLogger.parseProcessedTime(existing.processedTime);
            // 严格保留既有 targetFilePath（含遗留记录为 null 时不擅自改写），仅升级 recordingId 与 releaseGroupId
            migrations.add(new ProcessedFileLogger.RecordMigration(
                new File(existing.filePath),
                recordingId,
                meaningful(existing.artist) ? existing.artist : pair.officialTrack().artistDisplay(),
                meaningful(existing.title) ? existing.title : pair.officialTrack().title(),
                meaningful(existing.album) ? existing.album : detail.title(),
                releaseGroupId,
                existing.targetFilePath,
                origTime,
                List.of(),
                List.of()
            ));
        }
        if (migrations.isEmpty()) {
            return 0;
        }
        boolean committed = processedLogger.applyRecordMigrationsAtomically(migrations);
        if (!committed) {
            throw new BackfillException(500, "mb.contribution.backfill.record.failed",
                "原子更新处理记录失败，已回滚音频标签写入");
        }
        return migrations.size();
    }

    private static void collectAudioFallback(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (Files.isSymbolicLink(c.toPath())) continue;
            if (c.isDirectory()) collectAudioFallback(c, out);
            else if (c.isFile() && FileSystemUtils.isDefaultAudioFile(c)) out.add(c);
        }
    }

    private static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String head = raw.trim();
        int slash = head.indexOf('/');
        if (slash > 0) head = head.substring(0, slash).trim();
        try {
            int val = Integer.parseInt(head);
            return val > 0 ? val : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String normalizeTitleKey(String raw) {
        if (raw == null) return "";
        String nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
        return NON_ALNUM_KEEP_CJK.matcher(nfkc).replaceAll("");
    }

    private static String normalizePath(String raw) {
        try {
            return Path.of(raw).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return raw;
        }
    }

    private static boolean meaningful(String s) {
        return s != null && !s.isBlank();
    }
}
