package com.lux032.musicautotagger.model;

import com.lux032.musicautotagger.service.CoverCandidateView;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 「待人工确认」队列条目（阶段六 #18）
 *
 * 触发场景：曲目已经被指纹识别认出来了（曲目级元数据准确），
 * 但**专辑定不下来**——最典型的就是 MusicBrainz 尚未收录的新精选集。
 *
 * 关键约束：进入本状态时，文件**不能**已经被写标签 / 改名 / 移动。
 * 因此这里记录的是原始文件路径（以及转码产生的暂存文件路径），
 * 真正的落盘动作要等人工在面板上做出选择之后才执行。
 *
 * 本对象会被 Gson 序列化到磁盘，必须保持为纯数据结构（可跨重启恢复）。
 */
@Data
public class ReviewItem {

    public enum VerificationSource {
        MUSICBRAINZ,
        ONLINE_SEARCH,
        LOCAL_TAGS,
        SYNTHESIZED,
        MANUAL_REJECTED
    }

    /**
     * 恢复任务的提交阶段。
     *
     * 输出目录提交成功与隔离副本入回收站是两步文件系统操作，
     * 中间崩溃会造成「输出已完成但源文件还在」的不一致状态，
     * 重试时会撞上 destination.exists 而永远卡住。
     * 提交成功后立即落盘 COMMITTED，启动时只补做剩余收尾，不重写输出。
     */
    public enum RecoveryCommitState {
        COMMITTED,
        COMPLETED
    }

    /** 条目类型（决定面板上的 Tab） */
    public enum Kind {
        /** 专辑定不下来（如 MusicBrainz 未收录的精选集）。旧数据 kind 为 null 时按此处理 */
        ALBUM_UNRESOLVED,
        /** 找到了最接近的版本，但本地有曲目不在该版本中（锁定专辑缺曲） */
        TRACKS_MISSING,
        /** 元数据已确定但缺少封面（未入库拦截或库内扫描补图） */
        COVER_MISSING
    }

    public enum Status {
        /** 等待人工确认 */
        PENDING_REVIEW,
        /** 人工从候选中选定了具体 release，已按该 release 归档 */
        CONFIRMED,
        /** 人工确认「MusicBrainz 未收录」，已用合成信息归档（verified=false） */
        ARCHIVED_UNVERIFIED,
        /** 人工标记为失败 / 忽略 */
        REJECTED
    }

    private String id;
    private String folderPath;
    private String folderName;
    private Status status = Status.PENDING_REVIEW;
    private long createdAt;
    private long updatedAt;

    /** 进入待确认的原因（用于面板展示） */
    private String reason;

    /**
     * 结构化原因（见 {@link ReviewDiagnosis}）。面板用它打标签、筛选并渲染本地化文案；
     * 旧条目为 null，按 {@link ReviewDiagnosis#effectiveCode} 推断。
     */
    private String reasonCode;
    /** 判定时的具体数字（覆盖率、对不上的曲目数等），与 reasonCode 配套 */
    private Map<String, Object> reasonFacts = new ConcurrentHashMap<>();

    /** 条目类型；null 视为 ALBUM_UNRESOLVED（兼容旧队列文件） */
    private Kind kind;

    /** TRACKS_MISSING 条目的缺曲详情 */
    private MissingTracks missingTracks;

    /** COVER_MISSING 条目的缺封面详情（等待补图或确认无封面入库 / 库内原地补图） */
    private MissingCover missingCover;

    /** 该条目入队时是否已自动触发过联网搜索（避免重启 / 重复入队时反复搜索） */
    private boolean autoOnlineSearchTriggered;

    /** 实际类型（把旧数据的 null 归为 ALBUM_UNRESOLVED） */
    public Kind effectiveKind() {
        return kind == null ? Kind.ALBUM_UNRESOLVED : kind;
    }

    /** 统一置信度（阶段六 #20），0~1 */
    private double confidence;

    /** 合成的专辑信息：人工选择「按 MB 未收录归档」时直接使用 */
    private String synthesizedAlbumTitle;
    private String synthesizedAlbumArtist;

    /** 文件夹的时长序列（用于与候选 release 逐条 diff） */
    private List<Integer> durationSequence = new CopyOnWriteArrayList<>();

    /** 候选专辑快照 */
    private List<CandidateSnapshot> candidates = new CopyOnWriteArrayList<>();

    /** 是否已经从 MusicBrainz 展开过候选的 release 明细 */
    private boolean candidatesExpanded;

    /** 待确认的文件清单 */
    private List<FileEntry> files = new CopyOnWriteArrayList<>();

    /** 转码暂存目录（长期挂起时不能占用临时目录，需挪到暂存区） */
    private String stagingDir;

    /** 「处理记录 -> 重新识别」创建的临时副本根目录（reprocess-*），独立于转码 stagingDir，避免状态流转时被覆盖 */
    private String reprocessDir;

    /** 人工处置结果说明 */
    private String resolutionNote;

    /** 确认结果来自 MusicBrainz、联网来源还是合成信息 */
    private VerificationSource verificationSource;

    /** 恢复任务原始来源，确认成功后用于把隔离副本移入回收站 */
    private String recoverySourceType;
    private String recoverySourcePath;
    /** 提交阶段；为 COMMITTED 说明输出已落地，仅剩回收站收尾 */
    private RecoveryCommitState recoveryCommitState;
    /** 已提交到输出目录的顶层路径，供启动收尾核对 */
    private List<String> committedOutputPaths = new CopyOnWriteArrayList<>();
    /** 提交阶段持久化的逐曲处理记录回写清单，供崩溃重启后幂等重放回写再回收隔离副本 */
    private List<CommittedTrackRecord> committedTrackRecords = new CopyOnWriteArrayList<>();
    /** 本地证据摘要；文件发生变化时旧联网候选会过期 */
    private String evidenceHash;
    private boolean onlineEvidenceStale;
    private long onlineSearchedAt;
    private String onlineSearchProvider;
    private String onlineSearchModel;
    private List<OnlineCandidate> onlineCandidates = new CopyOnWriteArrayList<>();
    private List<OnlineEvidence> onlineClues = new CopyOnWriteArrayList<>();

    private static <T> List<T> copyOnWrite(List<T> input) {
        if (input == null) return new CopyOnWriteArrayList<>();
        if (input instanceof CopyOnWriteArrayList<T>) return input;
        return new CopyOnWriteArrayList<>(input);
    }

    private static <K, V> Map<K, V> concurrentMap(Map<K, V> input) {
        if (input instanceof ConcurrentHashMap<K, V>) return input;
        ConcurrentHashMap<K, V> map = new ConcurrentHashMap<>();
        if (input != null) {
            for (Map.Entry<K, V> e : input.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    map.put(e.getKey(), e.getValue());
                }
            }
        }
        return map;
    }

    public void setReasonFacts(Map<String, Object> reasonFacts) {
        this.reasonFacts = concurrentMap(reasonFacts);
    }

    public void setDurationSequence(List<Integer> durationSequence) {
        this.durationSequence = copyOnWrite(durationSequence);
    }

    public void setCandidates(List<CandidateSnapshot> candidates) {
        this.candidates = copyOnWrite(candidates);
    }

    public void setFiles(List<FileEntry> files) {
        this.files = copyOnWrite(files);
    }

    public void setCommittedOutputPaths(List<String> committedOutputPaths) {
        this.committedOutputPaths = copyOnWrite(committedOutputPaths);
    }

    public void setCommittedTrackRecords(List<CommittedTrackRecord> committedTrackRecords) {
        this.committedTrackRecords = copyOnWrite(committedTrackRecords);
    }

    public void setOnlineCandidates(List<OnlineCandidate> onlineCandidates) {
        this.onlineCandidates = copyOnWrite(onlineCandidates);
    }

    public void setOnlineClues(List<OnlineEvidence> onlineClues) {
        this.onlineClues = copyOnWrite(onlineClues);
    }

    /**
     * 确保所有嵌套集合均为线程安全实现（Gson 反序列化会绕过 setter 写入普通 ArrayList/LinkedHashMap），
     * 杜绝并发修改条目集合与队列 save() 序列化之间抛出 ConcurrentModificationException。
     */
    public void ensureThreadSafeCollections() {
        this.reasonFacts = concurrentMap(this.reasonFacts);
        this.durationSequence = copyOnWrite(this.durationSequence);
        this.candidates = copyOnWrite(this.candidates);
        this.files = copyOnWrite(this.files);
        this.committedOutputPaths = copyOnWrite(this.committedOutputPaths);
        this.committedTrackRecords = copyOnWrite(this.committedTrackRecords);
        this.onlineCandidates = copyOnWrite(this.onlineCandidates);
        this.onlineClues = copyOnWrite(this.onlineClues);
        for (CandidateSnapshot c : this.candidates) {
            if (c != null) c.durations = copyOnWrite(c.durations);
        }
        for (OnlineCandidate oc : this.onlineCandidates) {
            if (oc != null) {
                oc.artists = copyOnWrite(oc.artists);
                oc.albumArtists = copyOnWrite(oc.albumArtists);
                oc.tracks = copyOnWrite(oc.tracks);
                for (OnlineTrack tr : oc.tracks) {
                    if (tr != null && tr.creditDetail != null) {
                        tr.creditDetail.ensureThreadSafeCollections();
                    }
                }
                oc.sources = copyOnWrite(oc.sources);
                oc.coverCandidates = copyOnWrite(oc.coverCandidates);
                oc.manualTrackOverrides = copyOnWrite(oc.manualTrackOverrides);
                oc.entityMbids = concurrentMap(oc.entityMbids);
                for (EntityMbidResolution res : oc.entityMbids.values()) {
                    if (res != null) {
                        res.ensureThreadSafeCollections();
                    }
                }
            }
        }
        if (this.missingTracks != null) {
            this.missingTracks.unmatchedFiles = concurrentMap(this.missingTracks.unmatchedFiles);
        }
        if (this.missingCover != null) {
            this.missingCover.albumArtists = copyOnWrite(this.missingCover.albumArtists);
            this.missingCover.localTrackArtists = concurrentMap(this.missingCover.localTrackArtists);
            this.missingCover.localTrackArtistFallbacks = copyOnWrite(this.missingCover.localTrackArtistFallbacks);
            this.missingCover.coverCandidates = copyOnWrite(this.missingCover.coverCandidates);
            this.missingCover.archivedOutputDirs = copyOnWrite(this.missingCover.archivedOutputDirs);
            this.missingCover.onlineTrackCredits = concurrentMap(this.missingCover.onlineTrackCredits);
            this.missingCover.onlineEntityMbidOverrides = concurrentMap(this.missingCover.onlineEntityMbidOverrides);
        }
    }

    /**
     * LLM 封闭式判定结果（阶段七 #22）
     *
     * 默认只是**建议**，条目仍保持 PENDING_REVIEW；
     * 只有 llm.album.autoApply 打开且置信度达标时才会自动落盘。
     */
    private LlmSuggestion llmSuggestion;

    /**
     * 人工选定的 release（CONFIRMED 时填充）
     *
     * 字段要足够完整：重启时需要靠它们重建内存里的 MANUAL_CONFIRMED 专辑锁定，
     * 否则「人工结果不会被自动流程改写」只在当前进程生命周期内成立。
     */
    private String resolvedReleaseId;
    private String resolvedReleaseGroupId;
    private String resolvedAlbumTitle;
    private String resolvedAlbumArtist;
    private String resolvedReleaseDate;
    private String resolvedReleaseType;
    private boolean resolvedCompilation;
    private int resolvedTrackCount;

    /** 人工确认提交后持久化保存的逐曲回写意图，用于崩溃恢复时在回收隔离副本前幂等补齐处理记录。 */
    @Data
    public static class CommittedTrackRecord {
        private String isolatedPath;
        private String fallbackIsolatedPath;
        private String sourceType;
        private String originPath;
        private String outputPath;
        private String recordingId;
        private String artist;
        private String title;
        private String album;
        private String releaseGroupId;
        private boolean recoverySource;
        private boolean reprocessSource;
    }

    /**
     * 缺曲详情：最接近的版本 + 本地对不上的曲目
     */
    @Data
    public static class MissingTracks {
        private String releaseGroupId;
        private String releaseId;
        private String albumTitle;
        private String albumArtist;
        private int releaseTrackCount;
        private int localTrackCount;
        private int unmatchedLocalCount;
        /** LOCK_CHECK（锁定前时长覆盖检查）/ TRACK_CHECK（逐首检测） */
        private String detectedBy;
        /** 逐首检测确定不在版本中的文件：文件名 → 指纹曲名 */
        private Map<String, String> unmatchedFiles = new ConcurrentHashMap<>();

        public void setUnmatchedFiles(Map<String, String> unmatchedFiles) {
            this.unmatchedFiles = concurrentMap(unmatchedFiles);
        }
    }

    /**
     * 缺封面详情：保存已确认的专辑元数据与封面候选，用户选图或确认无封面后完成落盘/补写。
     */
    @Data
    public static class MissingCover {
        public enum Mode {
            /** 新抓取 / 重新识别 / 待确认流转：文件尚未写入音乐库 */
            STAGED_IMPORT,
            /** 手动扫描音乐库发现的已归档缺封面专辑：仅原地补写音频内嵌封面与缓存 */
            LIBRARY_EXISTING
        }

        private Mode mode = Mode.STAGED_IMPORT;
        /** 触发来源：AUTO_SCAN / LOOSE_FILE / REIDENTIFY / REVIEW_CONFIRM / REVIEW_ARCHIVE / REVIEW_ONLINE / REVIEW_LOCAL / LIBRARY_SCAN */
        private String sourcePipeline;
        private String albumTitle;
        private String albumArtist;
        private List<String> albumArtists = new CopyOnWriteArrayList<>();
        private String releaseGroupId;
        private String releaseId;
        private String releaseDate;
        private String releaseType;
        private boolean compilation;
        /** 按未收录合成归档时为 true（写入时清空年份与专辑级残留标签） */
        private boolean unresolvedAlbum;
        /** 监控目录根部的散落单文件 */
        private boolean looseFile;
        /** 自定义归档专辑目录名（联网确认带版本号等） */
        private String archiveDirectoryName;
        /** 特殊 recordingId 哨兵（如 ONLINE_SEARCH / LOCAL_TAGS）；普通 MusicBrainz 链路为 null */
        private String recordingIdSentinel;
        /** 来自「按本地标签入库」流程时的元数据快照 */
        private boolean localArchiveDirect;
        private String localArchiveRevision;
        private Map<String, List<String>> localTrackArtists = new ConcurrentHashMap<>();
        private List<String> localTrackArtistFallbacks = new CopyOnWriteArrayList<>();
        /** 联网候选 ID（若来自联网候选确认） */
        private String onlineCandidateId;
        /** 联网确认时用户在弹窗中提交的逐曲幕后人员覆盖（按本地文件绝对路径索引） */
        private Map<String, TrackCreditsOverride> onlineTrackCredits = new ConcurrentHashMap<>();
        /** 联网确认时用户在弹窗中提交的人物级 MBID 覆盖（键为规范化人名键，值为小写 UUID 或空串表示显式取消绑定） */
        private Map<String, String> onlineEntityMbidOverrides = new ConcurrentHashMap<>();
        /** 用户补充的自定义搜索关键词 */
        private String customSearchQuery;
        private long coverSearchedAt;
        /** IDLE / SEARCHING / DONE / FAILED */
        private String coverSearchStatus;
        private String coverSearchNote;
        /** 封面候选列表（字节保存在 CoverCandidateCache 磁盘目录） */
        private List<CoverCandidateView> coverCandidates = new CopyOnWriteArrayList<>();
        private String selectedCoverSha256;
        /** 用户显式确认「保持无封面」入库或保留 */
        private boolean confirmedNoCover;
        /** STAGED_IMPORT 确认无封面入库后实际落盘的专辑输出目录/散曲路径，供后续库扫描跳过 */
        private List<String> archivedOutputDirs = new CopyOnWriteArrayList<>();
        /** 是否已自动触发过后台搜封面 */
        private boolean coverAutoSearchTriggered;

        public void setAlbumArtists(List<String> albumArtists) {
            this.albumArtists = copyOnWrite(albumArtists);
        }

        public void setLocalTrackArtists(Map<String, List<String>> localTrackArtists) {
            this.localTrackArtists = concurrentMap(localTrackArtists);
        }

        public void setLocalTrackArtistFallbacks(List<String> localTrackArtistFallbacks) {
            this.localTrackArtistFallbacks = copyOnWrite(localTrackArtistFallbacks);
        }

        public void setCoverCandidates(List<CoverCandidateView> coverCandidates) {
            this.coverCandidates = copyOnWrite(coverCandidates);
        }

        public void setArchivedOutputDirs(List<String> archivedOutputDirs) {
            this.archivedOutputDirs = copyOnWrite(archivedOutputDirs);
        }

        public void setOnlineTrackCredits(Map<String, TrackCreditsOverride> onlineTrackCredits) {
            this.onlineTrackCredits = concurrentMap(onlineTrackCredits);
        }

        public void setOnlineEntityMbidOverrides(Map<String, String> onlineEntityMbidOverrides) {
            this.onlineEntityMbidOverrides = concurrentMap(onlineEntityMbidOverrides);
        }
    }

    /**
     * 单个待确认文件
     */
    @Data
    public static class FileEntry {
        /** 监控目录中的原始文件（始终未被修改） */
        private String originalPath;
        /**
         * 暂存文件路径（仅当发生过格式规范化转码时存在）。
         * 为 null 表示直接使用 originalPath。
         */
        private String stagedPath;
        private String fileName;
        /** 时长（秒），用于与候选 release 的时长序列做 diff */
        private Integer duration;
        /** 指纹识别得到的曲目级元数据（准确，确认后直接复用） */
        private MusicMetadata metadata;
    }

    /**
     * LLM 判定建议
     *
     * 只会出现两种结论：「选中某个已有候选」或「都不是」。
     * **不允许**模型自由给出专辑名/年份，否则它会编造一个看上去非常合理的虚构答案。
     */
    @Data
    public static class LlmSuggestion {
        private long evaluatedAt;
        private String model;
        private String provider;
        /** 1-based 候选序号，0 = 都不是 */
        private int choiceIndex;
        private String suggestedReleaseId;
        private String suggestedReleaseGroupId;
        private String suggestedTitle;
        private double confidence;
        /** 是否判定为「MusicBrainz 尚未收录的精选集/自制合辑」 */
        private boolean unreleasedCompilation;
        private String reason;
        /** 是否已按该结论自动落盘 */
        private boolean applied;
    }

    /**
     * 候选专辑快照
     *
     * 分两级：
     * - Release Group 级：来自 AcoustID 的候选（零额外 API 调用）
     * - Release 级：人工打开详情时才从 MusicBrainz 展开，包含曲目数 / 格式 / 时长序列
     */
    @Data
    public static class OnlineEvidence {
        private String url;
        private String domain;
        private String title;
        private String snippet;
        private long retrievedAt;
        /** HIGH / MEDIUM / LOW */
        private String reliability;
    }

    /**
     * 人工在联网确认弹窗中提交的单曲幕后人员覆盖。
     * 字段为 null 表示未改动（沿用自动合并规则），空列表表示人工显式清空，非空列表表示人工覆盖。
     */
    @Data
    public static class TrackCreditsOverride {
        private List<String> composers;
        private List<String> lyricists;
        private List<String> arrangers;
    }

    /** 曲目级联网幕后人员字段的确定性核验状态。 */
    public enum CreditStatus {
        VERIFIED,
        PROBABLE,
        CONFLICT,
        NOT_FOUND,
        NOT_SEARCHED
    }

    /** 单条支持某个幕后人员字段的网页原文证据（仅存短引文 ≤200 字，绝不存完整网页正文）。 */
    @Data
    public static class CreditEvidenceItem {
        private String url;
        private String sourceType;
        private String quote;
        private String versionMatch;
        private boolean fullPageVerified;
        private boolean syndicatedDuplicate;
    }

    /** 多源存在冲突时的单个候选署名集合。 */
    @Data
    public static class CreditConflictCandidate {
        private List<String> names = new ArrayList<>();
        private List<String> urls = new ArrayList<>();
        private List<String> quotes = new ArrayList<>();

        public void ensureThreadSafeCollections() {
            this.names = copyOnWrite(this.names);
            this.urls = copyOnWrite(this.urls);
            this.quotes = copyOnWrite(this.quotes);
        }
    }

    /** 单个角色（作词 / 作曲 / 编曲）的字段级联网核验结果。 */
    @Data
    public static class CreditFieldResult {
        private CreditStatus status = CreditStatus.NOT_SEARCHED;
        private List<String> names = new ArrayList<>();
        private List<CreditEvidenceItem> evidence = new ArrayList<>();
        private List<CreditConflictCandidate> candidates = new ArrayList<>();
        private String notes;

        public void ensureThreadSafeCollections() {
            this.names = copyOnWrite(this.names);
            this.evidence = copyOnWrite(this.evidence);
            this.candidates = copyOnWrite(this.candidates);
            for (CreditConflictCandidate candidate : this.candidates) {
                if (candidate != null) {
                    candidate.ensureThreadSafeCollections();
                }
            }
        }
    }

    /** 单首曲目的字段级幕后人员证据明细（与 OnlineTrack.composers/lyricists/arrangers 共存，历史数据可为 null）。 */
    @Data
    public static class TrackCreditDetail {
        private String versionType;
        private CreditFieldResult lyricists;
        private CreditFieldResult composers;
        private CreditFieldResult arrangers;

        public void ensureThreadSafeCollections() {
            if (this.lyricists != null) this.lyricists.ensureThreadSafeCollections();
            if (this.composers != null) this.composers.ensureThreadSafeCollections();
            if (this.arrangers != null) this.arrangers.ensureThreadSafeCollections();
        }
    }

    @Data
    public static class OnlineTrack {
        private int discNo;
        private int trackNo;
        private String title;
        private String artist;
        private List<String> artists = new ArrayList<>();
        private List<String> composers = new ArrayList<>();
        private List<String> lyricists = new ArrayList<>();
        private List<String> arrangers = new ArrayList<>();
        private TrackCreditDetail creditDetail;
        private Integer duration;
        private String matchedFilePath;
        private double matchConfidence;
    }

    /** 联网识别人物级 MusicBrainz Artist MBID 解析状态。 */
    public enum EntityMbidStatus {
        RESOLVED,
        AMBIGUOUS,
        NOT_FOUND,
        SKIPPED
    }

    /** 联网识别人物级 MusicBrainz Artist MBID 命中来源。 */
    public enum EntityMbidSource {
        LOCAL_CACHE,
        WEB_LINK_VERIFIED,
        MB_SEARCH_EXACT,
        MB_SEARCH_LLM_DISAMBIGUATED,
        MANUAL
    }

    /** MusicBrainz Artist 检索返回的单个真实候选实体快照。 */
    @Data
    public static class EntityMbidCandidate {
        private String mbid;
        private String name;
        private String sortName;
        private String disambiguation;
        private String country;
        private String type;
        private int score;
    }

    /** 联网候选中单个规范化人物的 MusicBrainz Artist MBID 解析结果。 */
    @Data
    public static class EntityMbidResolution {
        private String normalizedKey;
        private String displayName;
        private List<String> roles = new CopyOnWriteArrayList<>();
        private EntityMbidStatus status = EntityMbidStatus.NOT_FOUND;
        private String selectedMbid;
        private String selectedName;
        private String selectedSortName;
        private String selectedDisambiguation;
        private EntityMbidSource source;
        private double confidence;
        private String reason;
        private List<EntityMbidCandidate> candidates = new CopyOnWriteArrayList<>();

        public void setRoles(List<String> roles) {
            this.roles = copyOnWrite(roles);
        }

        public void setCandidates(List<EntityMbidCandidate> candidates) {
            this.candidates = copyOnWrite(candidates);
        }

        public void ensureThreadSafeCollections() {
            this.roles = copyOnWrite(this.roles);
            this.candidates = copyOnWrite(this.candidates);
        }
    }

    @Data
    public static class OnlineCandidate {
        private String id;
        private String title;
        private String artist;
        private List<String> artists = new ArrayList<>();
        private String albumArtist;
        private List<String> albumArtists = new ArrayList<>();
        private String releaseDate;
        private String edition;
        private String country;
        private String label;
        private String catalogNumber;
        private String coverUrl;
        private String coverSourceUrl;
        private double confidence;
        private double trackCoverage;
        private boolean officialCandidate;
        private String reason;
        private List<OnlineTrack> tracks = new CopyOnWriteArrayList<>();
        private List<OnlineEvidence> sources = new CopyOnWriteArrayList<>();
        /** Lightweight cover metadata only; image bytes are stored in the candidate cache. */
        private List<CoverCandidateView> coverCandidates = new CopyOnWriteArrayList<>();
        /** null means the user explicitly chose not to embed a cover. */
        private String selectedCoverSha256;
        /** 首期只开放专辑级编辑，逐曲覆盖字段为后续预留 */
        private List<OnlineTrack> manualTrackOverrides = new CopyOnWriteArrayList<>();
        /** 人物级 MusicBrainz Artist MBID 预解析表（键为规范化人名键，兼容历史数据可为空） */
        private Map<String, EntityMbidResolution> entityMbids = new ConcurrentHashMap<>();

        public void setTracks(List<OnlineTrack> tracks) {
            this.tracks = copyOnWrite(tracks);
        }

        public void setSources(List<OnlineEvidence> sources) {
            this.sources = copyOnWrite(sources);
        }

        public void setCoverCandidates(List<CoverCandidateView> coverCandidates) {
            this.coverCandidates = copyOnWrite(coverCandidates);
        }

        public void setManualTrackOverrides(List<OnlineTrack> manualTrackOverrides) {
            this.manualTrackOverrides = copyOnWrite(manualTrackOverrides);
        }

        public void setEntityMbids(Map<String, EntityMbidResolution> entityMbids) {
            this.entityMbids = concurrentMap(entityMbids);
        }
    }

    /**
     * 候选专辑快照
     */
    @Data
    public static class CandidateSnapshot {
        private String releaseGroupId;
        private String releaseId;
        private String releaseType;
        private boolean compilation;
        private String title;
        private String artist;
        private String date;
        private String mediaFormat;
        private int trackCount;
        /** 有多少个样本的候选里包含这张专辑 */
        private int supportCount;
        private int totalSamples;
        private List<Integer> durations = new CopyOnWriteArrayList<>();
        /** 该 RG 已被查证确认没有可用 Release（正常结果，非展开失败） */
        private boolean confirmedNoRelease;
        /** 与文件夹时长序列的相似度（展开后计算） */
        private Double durationSimilarity;

        public void setDurations(List<Integer> durations) {
            this.durations = copyOnWrite(durations);
        }
    }
}
