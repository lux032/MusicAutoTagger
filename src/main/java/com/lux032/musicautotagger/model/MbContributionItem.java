package com.lux032.musicautotagger.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 待回馈 MusicBrainz 条目模型，原子持久化至 {@code mb-contributions.json}。
 */
@Data
public class MbContributionItem {

    public enum Status {
        /** 正在执行 MusicBrainz 存在性预检 */
        CHECKING,
        /** 命中 Case B/C，可前往 MusicBrainz Release Editor 预填提交 */
        READY_TO_SEED,
        /** 命中 Case A，MusicBrainz 已存在精确 Release，可直接关联并回填本地 MBID */
        EXISTING_MATCH,
        /** 已贴回或关联官方 Release 并完成本地标签回填，可继续上传封面/补录 Works/提交声纹 */
        LINKED,
        /** 用户确认全部回馈步骤已完成 */
        COMPLETED,
        /** 用户忽略该回馈条目 */
        DISMISSED,
        /** 预检失败（如网络/限流异常），可手动重新预检 */
        CHECK_FAILED
    }

    public enum CheckCase {
        /** Case A：精确发行版已存在于 MusicBrainz，禁止重复新建 */
        EXISTING_RELEASE,
        /** Case B：Release Group 已存在，但缺少当前发行版/地区版/加曲版 */
        MISSING_EDITION,
        /** Case C：Release Group 与 Release 均不存在，可新建专辑 */
        MISSING_RELEASE_GROUP,
        /** Case D：非官方候选或本地覆盖不完整，不自动建议上传 */
        NOT_ELIGIBLE,
        /** 存在性预检调用失败 */
        ERROR
    }

    private String id;
    private String reviewItemId;
    private String archivedOutputDir;
    private List<String> archivedFilePaths = new CopyOnWriteArrayList<>();

    private Status status = Status.CHECKING;
    private CheckCase checkCase;
    private String checkCode;
    private String checkMessage;
    private long revision = 1L;
    private long checkedAt;
    private long createdAt;
    private long updatedAt;

    // 预检命中的已有 MB 实体（Case A / Case B）
    private String matchedReleaseId;
    private String matchedReleaseGroupId;
    private String matchedReleaseTitle;
    private String matchedReleaseArtist;
    private String matchedReleaseDate;
    private String matchedReleaseCountry;
    private int matchedTrackCount;
    /** Case B 下是否默认挂入已有 Release Group（用户可在微调草稿中切换为新建 RG） */
    private boolean useExistingReleaseGroup = true;
    private List<ExistingReleaseSummary> existingGroupReleases = new CopyOnWriteArrayList<>();

    // 可编辑发行草稿
    private ReleaseDraft draft = new ReleaseDraft();

    // Cover Art Archive 高画质封面信息
    private CoverInfo cover;

    // 已关联的官方 Release 与本地回填结果
    private String linkedReleaseId;
    private String linkedReleaseGroupId;
    private String linkedReleaseTitle;
    private long linkedAt;
    private int backfilledFileCount;
    private List<LinkedTrackMapping> backfilledTracks = new CopyOnWriteArrayList<>();

    // AcoustID 声纹提交状态
    private long acoustIdSubmittedAt;
    private int acoustIdSubmittedCount;
    private long acoustIdMappingVersion;
    private List<String> acoustIdSubmittedRecordingIds = new CopyOnWriteArrayList<>();
    private String acoustIdLastError;

    public void setArchivedFilePaths(List<String> archivedFilePaths) {
        this.archivedFilePaths = copyOnWrite(archivedFilePaths);
    }

    public void setExistingGroupReleases(List<ExistingReleaseSummary> existingGroupReleases) {
        this.existingGroupReleases = copyOnWrite(existingGroupReleases);
    }

    public void setBackfilledTracks(List<LinkedTrackMapping> backfilledTracks) {
        this.backfilledTracks = copyOnWrite(backfilledTracks);
    }

    public void setAcoustIdSubmittedRecordingIds(List<String> acoustIdSubmittedRecordingIds) {
        this.acoustIdSubmittedRecordingIds = copyOnWrite(acoustIdSubmittedRecordingIds);
    }

    public void ensureThreadSafeCollections() {
        this.archivedFilePaths = copyOnWrite(this.archivedFilePaths);
        this.existingGroupReleases = copyOnWrite(this.existingGroupReleases);
        this.backfilledTracks = copyOnWrite(this.backfilledTracks);
        this.acoustIdSubmittedRecordingIds = copyOnWrite(this.acoustIdSubmittedRecordingIds);
        if (this.draft == null) {
            this.draft = new ReleaseDraft();
        }
        this.draft.ensureThreadSafeCollections();
    }

    public boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.DISMISSED;
    }

    public boolean isActionable() {
        return status == Status.READY_TO_SEED
            || status == Status.EXISTING_MATCH
            || status == Status.LINKED
            || status == Status.CHECK_FAILED
            || status == Status.CHECKING;
    }

    @Data
    public static class ExistingReleaseSummary {
        private String releaseId;
        private String title;
        private String date;
        private String country;
        private String mediaFormat;
        private int trackCount;
        private String catalogNumber;
        private String barcode;
        private String disambiguation;
    }

    @Data
    public static class SourceUrlEntry {
        private String url;
        private String domain;
        private String title;
        /** HIGH / MEDIUM / LOW */
        private String reliability;
        private boolean included = true;
    }

    @Data
    public static class EntityMbidRef {
        private String mbid;
        private String displayName;
        private String canonicalName;
        private String sortName;
        private String disambiguation;
        private String source;
    }

    @Data
    public static class ContributionTrack {
        private int discNo = 1;
        private int trackNo;
        private String title;
        private String artist;
        private List<String> artists = new CopyOnWriteArrayList<>();
        /** 毫秒级曲目时长，<=0 表示未知 */
        private long durationMs;
        /** LOCAL_PRECISE / ONLINE / UNKNOWN */
        private String durationSource;
        /** 对应已归档本地音频路径（未匹配时为空） */
        private String localFilePath;
        private double matchedConfidence;
        /** 前置指纹识别曾命中过的合法 Recording MBID（可选） */
        private String existingRecordingId;

        private List<String> composers = new CopyOnWriteArrayList<>();
        private List<String> lyricists = new CopyOnWriteArrayList<>();
        private List<String> arrangers = new CopyOnWriteArrayList<>();

        /** MANUAL / MUSICBRAINZ / LOCAL / ONLINE_VERIFIED / ONLINE_PROBABLE / NONE */
        private String composerSource;
        private String lyricistSource;
        private String arrangerSource;

        private List<String> creditEvidenceUrls = new CopyOnWriteArrayList<>();
        private List<String> creditEvidenceQuotes = new CopyOnWriteArrayList<>();
        private List<String> composerEvidenceUrls = new CopyOnWriteArrayList<>();
        private List<String> composerEvidenceQuotes = new CopyOnWriteArrayList<>();
        private List<String> lyricistEvidenceUrls = new CopyOnWriteArrayList<>();
        private List<String> lyricistEvidenceQuotes = new CopyOnWriteArrayList<>();
        private List<String> arrangerEvidenceUrls = new CopyOnWriteArrayList<>();
        private List<String> arrangerEvidenceQuotes = new CopyOnWriteArrayList<>();

        public void setArtists(List<String> artists) { this.artists = copyOnWrite(artists); }
        public void setComposers(List<String> composers) { this.composers = copyOnWrite(composers); }
        public void setLyricists(List<String> lyricists) { this.lyricists = copyOnWrite(lyricists); }
        public void setArrangers(List<String> arrangers) { this.arrangers = copyOnWrite(arrangers); }
        public void setCreditEvidenceUrls(List<String> urls) { this.creditEvidenceUrls = copyOnWrite(urls); }
        public void setCreditEvidenceQuotes(List<String> quotes) { this.creditEvidenceQuotes = copyOnWrite(quotes); }
        public void setComposerEvidenceUrls(List<String> urls) { this.composerEvidenceUrls = copyOnWrite(urls); }
        public void setComposerEvidenceQuotes(List<String> quotes) { this.composerEvidenceQuotes = copyOnWrite(quotes); }
        public void setLyricistEvidenceUrls(List<String> urls) { this.lyricistEvidenceUrls = copyOnWrite(urls); }
        public void setLyricistEvidenceQuotes(List<String> quotes) { this.lyricistEvidenceQuotes = copyOnWrite(quotes); }
        public void setArrangerEvidenceUrls(List<String> urls) { this.arrangerEvidenceUrls = copyOnWrite(urls); }
        public void setArrangerEvidenceQuotes(List<String> quotes) { this.arrangerEvidenceQuotes = copyOnWrite(quotes); }

        public void ensureThreadSafeCollections() {
            this.artists = copyOnWrite(this.artists);
            this.composers = copyOnWrite(this.composers);
            this.lyricists = copyOnWrite(this.lyricists);
            this.arrangers = copyOnWrite(this.arrangers);
            this.creditEvidenceUrls = copyOnWrite(this.creditEvidenceUrls);
            this.creditEvidenceQuotes = copyOnWrite(this.creditEvidenceQuotes);
            this.composerEvidenceUrls = copyOnWrite(this.composerEvidenceUrls);
            this.composerEvidenceQuotes = copyOnWrite(this.composerEvidenceQuotes);
            this.lyricistEvidenceUrls = copyOnWrite(this.lyricistEvidenceUrls);
            this.lyricistEvidenceQuotes = copyOnWrite(this.lyricistEvidenceQuotes);
            this.arrangerEvidenceUrls = copyOnWrite(this.arrangerEvidenceUrls);
            this.arrangerEvidenceQuotes = copyOnWrite(this.arrangerEvidenceQuotes);
        }
    }

    @Data
    public static class ReleaseDraft {
        private String title;
        private String comment;
        private String albumArtist;
        private List<String> albumArtists = new CopyOnWriteArrayList<>();
        private String releaseDate;
        private String country;
        private String releaseType;
        private String mediaFormat;
        private String label;
        private String catalogNumber;
        private String barcode;
        private String language;
        private String script;
        private boolean officialCandidate;
        private double trackCoverage;
        private List<SourceUrlEntry> sourceUrls = new CopyOnWriteArrayList<>();
        private List<ContributionTrack> tracks = new CopyOnWriteArrayList<>();
        /** 规范化人名键 -> 已核验/人工选定的实体 MBID 引用 */
        private Map<String, EntityMbidRef> entityMbids = new ConcurrentHashMap<>();

        public void setAlbumArtists(List<String> albumArtists) { this.albumArtists = copyOnWrite(albumArtists); }
        public void setSourceUrls(List<SourceUrlEntry> sourceUrls) { this.sourceUrls = copyOnWrite(sourceUrls); }
        public void setTracks(List<ContributionTrack> tracks) { this.tracks = copyOnWrite(tracks); }
        public void setEntityMbids(Map<String, EntityMbidRef> entityMbids) { this.entityMbids = concurrentMap(entityMbids); }

        public void ensureThreadSafeCollections() {
            this.albumArtists = copyOnWrite(this.albumArtists);
            this.sourceUrls = copyOnWrite(this.sourceUrls);
            this.tracks = copyOnWrite(this.tracks);
            for (ContributionTrack t : this.tracks) {
                if (t != null) t.ensureThreadSafeCollections();
            }
            this.entityMbids = concurrentMap(this.entityMbids);
        }
    }

    @Data
    public static class CoverInfo {
        private String origin;
        private String publicUrl;
        private String originPageUrl;
        /** 仅保存纯文件名（不含任何目录分隔符），解析时严格限定在 mb-contribution-covers/ 根目录内 */
        private String localBackupFileName;
        private String mime;
        private int width;
        private int height;
        private long bytes;
    }

    @Data
    public static class LinkedTrackMapping {
        private String localFilePath;
        private String trackId;
        private String recordingId;
        private int discNo;
        private int trackNo;
        private String title;
        private long durationMs;
    }

    private static <T> List<T> copyOnWrite(List<T> source) {
        if (source == null) {
            return new CopyOnWriteArrayList<>();
        }
        List<T> filtered = new ArrayList<>(source.size());
        for (T item : source) {
            if (item != null) {
                filtered.add(item);
            }
        }
        return new CopyOnWriteArrayList<>(filtered);
    }

    private static <K, V> Map<K, V> concurrentMap(Map<K, V> source) {
        Map<K, V> map = new ConcurrentHashMap<>();
        if (source != null) {
            for (Map.Entry<K, V> e : source.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    map.put(e.getKey(), e.getValue());
                }
            }
        }
        return map;
    }

    public static Map<String, Object> emptyStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("checking", 0);
        stats.put("readyToSeed", 0);
        stats.put("existingMatch", 0);
        stats.put("linked", 0);
        stats.put("completed", 0);
        stats.put("dismissed", 0);
        stats.put("checkFailed", 0);
        stats.put("actionable", 0);
        stats.put("total", 0);
        return stats;
    }
}
