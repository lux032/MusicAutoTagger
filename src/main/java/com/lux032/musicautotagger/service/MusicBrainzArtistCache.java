package com.lux032.musicautotagger.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 本地 MusicBrainz 艺人实体缓存（双表结构 + 同名冲突感知）。
 *
 * <ul>
 *   <li>常规 MusicBrainz 识别成功解析出 Recording 艺人、Release 专辑艺人及幕后创作者时被动沉淀；</li>
 *   <li>联网识别 Layer 1 优先按规范化人名检索：仅命中单一 MBID 且无同名冲突标记时 0ms 返回；
 *       命中多个不同 MBID 或存在同名冲突标记时透传全部候选交由后续外链核验或上下文消歧；</li>
 *   <li>SQLite 自动建表、MySQL 依赖 schema.sql（表缺失时自动降级为有界内存 LRU 缓存，临时故障不永久降级）；
 *       文件模式（databaseService == null）直接使用进程内有界 LRU 缓存。</li>
 * </ul>
 */
@Slf4j
public class MusicBrainzArtistCache {

    private static final int MEMORY_CACHE_LIMIT = 4096;
    private static final int MEMORY_INDEX_LIMIT = 8192;
    private static final int MAX_DB_INDEX_KEY_CHARS = 255;
    private static final int MAX_INDEX_KEYS_PER_ARTIST = 64;
    private static final long REDUNDANT_WRITE_SKIP_MS = 10 * 60 * 1000L;
    private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
    private static final String HASH_KEY_PREFIX = "#sha256:";
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern UNICODE_WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>() {}.getType();
    private static final Gson GSON = new Gson();

    public record CachedArtist(
        String mbid,
        String name,
        String sortName,
        String disambiguation,
        String country,
        String artistType,
        List<String> aliases,
        String source,
        long updatedAt
    ) {
        public CachedArtist {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    public record NameLookupResult(List<CachedArtist> matches, boolean homonymConflict) {
        public NameLookupResult(List<CachedArtist> matches) {
            this(matches, matches != null && matches.size() > 1);
        }

        public NameLookupResult {
            matches = matches == null ? List.of() : List.copyOf(matches);
            homonymConflict = homonymConflict || matches.size() > 1;
        }

        public static NameLookupResult empty() {
            return new NameLookupResult(List.of(), false);
        }

        public boolean isEmpty() {
            return matches.isEmpty() && !homonymConflict;
        }

        public boolean isUniqueHit() {
            return !homonymConflict && matches.size() == 1;
        }

        public boolean hasHomonymConflict() {
            return homonymConflict || matches.size() > 1;
        }

        public CachedArtist uniqueArtist() {
            return isUniqueHit() ? matches.get(0) : null;
        }
    }

    private static final class NameBucket {
        final LinkedHashSet<String> mbids = new LinkedHashSet<>();
        boolean homonymConflict;
    }

    private final DatabaseService databaseService;
    private final AtomicBoolean dbDegraded = new AtomicBoolean(false);
    private final Object memLock = new Object();
    private final Map<String, CachedArtist> memoryByMbid = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedArtist> eldest) {
            if (size() > MEMORY_CACHE_LIMIT) {
                removeMbidFromMemoryIndex(eldest.getKey());
                return true;
            }
            return false;
        }
    };
    private final Map<String, NameBucket> memoryIndexByName = new LinkedHashMap<>(512, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, NameBucket> eldest) {
            return size() > MEMORY_INDEX_LIMIT;
        }
    };

    public MusicBrainzArtistCache(DatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    /** 统一人物姓名规范化键：NFKC、全量 Unicode 空白折叠并转为 Locale.ROOT 小写（不做长度截断以保证应用层精确相等）。 */
    public static String normalizePersonKey(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        String collapsed = UNICODE_WHITESPACE.matcher(nfkc).replaceAll(" ").trim();
        if (collapsed.isEmpty()) {
            return "";
        }
        return collapsed.toLowerCase(Locale.ROOT);
    }

    /** 数据库索引列键：<=255 字符原样存储，超长键转为确定性 SHA-256 摘要（查询后需复核全键相等）。 */
    static String toDbIndexKey(String normalizedKey) {
        if (normalizedKey == null || normalizedKey.isEmpty()) {
            return "";
        }
        if (normalizedKey.length() <= MAX_DB_INDEX_KEY_CHARS) {
            return normalizedKey;
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(normalizedKey.getBytes(StandardCharsets.UTF_8));
            return HASH_KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return normalizedKey.substring(0, MAX_DB_INDEX_KEY_CHARS);
        }
    }

    /** 校验并规范化 MusicBrainz UUID（拒绝空值、非 UUID 与全零 UUID）。 */
    public static String normalizeValidMbid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (!UUID_PATTERN.matcher(trimmed).matches()) {
            return null;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return NIL_UUID.equals(lower) ? null : lower;
    }

    public boolean isDbDegraded() {
        return dbDegraded.get();
    }

    /** 记录或合并更新一条真实校验过的 MusicBrainz Artist 实体及其名称索引。 */
    public void recordArtist(String rawMbid, String name, String sortName, String disambiguation,
                             String country, String artistType,
                             Collection<String> aliases, Collection<String> extraIndexNames,
                             String source) {
        String mbid = normalizeValidMbid(rawMbid);
        if (mbid == null || name == null || name.isBlank()) {
            return;
        }
        String cleanName = name.trim();
        long now = System.currentTimeMillis();

        CachedArtist existing = findByMbid(mbid).orElse(null);
        LinkedHashSet<String> mergedAliases = new LinkedHashSet<>();
        if (existing != null && existing.aliases() != null) {
            mergedAliases.addAll(existing.aliases());
        }
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias != null && !alias.isBlank()) {
                    mergedAliases.add(alias.trim());
                }
            }
        }
        String mergedSortName = preferNonBlank(sortName, existing == null ? null : existing.sortName());
        String mergedDisambiguation = preferNonBlank(disambiguation, existing == null ? null : existing.disambiguation());
        String mergedCountry = preferNonBlank(country, existing == null ? null : existing.country());
        String mergedType = preferNonBlank(artistType, existing == null ? null : existing.artistType());
        String mergedSource = preferNonBlank(source, existing == null ? null : existing.source());

        CachedArtist merged = new CachedArtist(
            mbid, cleanName, mergedSortName, mergedDisambiguation, mergedCountry, mergedType,
            List.copyOf(mergedAliases), mergedSource, now);

        Map<String, String> indexEntries = new LinkedHashMap<>();
        putIndexEntry(indexEntries, cleanName, "PRIMARY");
        for (String alias : mergedAliases) {
            if (indexEntries.size() >= MAX_INDEX_KEYS_PER_ARTIST) break;
            putIndexEntry(indexEntries, alias, "ALIAS");
        }
        if (extraIndexNames != null) {
            for (String extra : extraIndexNames) {
                if (indexEntries.size() >= MAX_INDEX_KEYS_PER_ARTIST) break;
                putIndexEntry(indexEntries, extra, "CREDITED");
            }
        }

        boolean redundant = isRedundantWrite(existing, merged, indexEntries.keySet(), now);
        putInMemory(merged, indexEntries.keySet(), false);
        if (!redundant && useDatabase()) {
            try {
                upsertBatchInDatabase(List.of(new ArtistWriteItem(merged, indexEntries)));
            } catch (SQLException e) {
                handleDatabaseFailure("写入 MusicBrainz 艺人缓存失败", e);
            }
        }
    }

    /**
     * 原子记录某个规范化人名对应的多个同名竞争实体。
     * 内存侧在单把锁内同时发布全部候选与同名冲突标志，数据库侧在单个事务内提交，
     * 避免并发线程观测到半写入状态而误判为 Layer 1 唯一命中。
     */
    public void recordHomonymCandidates(String rawName, Collection<CachedArtist> competingArtists) {
        String key = normalizePersonKey(rawName);
        if (key.isEmpty() || competingArtists == null || competingArtists.size() < 2) {
            return;
        }
        long now = System.currentTimeMillis();
        List<ArtistWriteItem> batch = new ArrayList<>();
        Set<String> seenMbids = new LinkedHashSet<>();

        for (CachedArtist candidate : competingArtists) {
            if (candidate == null) continue;
            String mbid = normalizeValidMbid(candidate.mbid());
            if (mbid == null || !seenMbids.add(mbid) || candidate.name() == null || candidate.name().isBlank()) {
                continue;
            }
            CachedArtist existing = findByMbid(mbid).orElse(null);
            LinkedHashSet<String> mergedAliases = new LinkedHashSet<>();
            if (existing != null) mergedAliases.addAll(existing.aliases());
            mergedAliases.addAll(candidate.aliases());

            CachedArtist merged = new CachedArtist(
                mbid,
                candidate.name().trim(),
                preferNonBlank(candidate.sortName(), existing == null ? null : existing.sortName()),
                preferNonBlank(candidate.disambiguation(), existing == null ? null : existing.disambiguation()),
                preferNonBlank(candidate.country(), existing == null ? null : existing.country()),
                preferNonBlank(candidate.artistType(), existing == null ? null : existing.artistType()),
                List.copyOf(mergedAliases),
                preferNonBlank(candidate.source(), "MB_HOMONYM_INDEX"),
                now);

            Map<String, String> indexEntries = new LinkedHashMap<>();
            indexEntries.put(key, "HOMONYM_CANDIDATE");
            putIndexEntry(indexEntries, merged.name(), "HOMONYM_CANDIDATE");
            batch.add(new ArtistWriteItem(merged, indexEntries));
        }

        if (batch.size() < 2) {
            return;
        }

        // 内存侧在单次持锁中原子发布所有竞争实体与冲突标记
        synchronized (memLock) {
            NameBucket primaryBucket = memoryIndexByName.computeIfAbsent(key, ignored -> new NameBucket());
            primaryBucket.homonymConflict = true;
            for (ArtistWriteItem item : batch) {
                memoryByMbid.put(item.artist().mbid(), item.artist());
                for (String idxKey : item.indexEntries().keySet()) {
                    if (idxKey == null || idxKey.isEmpty()) continue;
                    NameBucket bucket = memoryIndexByName.computeIfAbsent(idxKey, ignored -> new NameBucket());
                    bucket.mbids.add(item.artist().mbid());
                    if (idxKey.equals(key) || bucket.mbids.size() > 1) {
                        bucket.homonymConflict = true;
                    }
                }
            }
        }

        if (useDatabase()) {
            try {
                upsertBatchInDatabase(batch);
            } catch (SQLException e) {
                handleDatabaseFailure("写入 MusicBrainz 同名艺人冲突索引失败", e);
            }
        }
    }

    public Optional<CachedArtist> findByMbid(String rawMbid) {
        String mbid = normalizeValidMbid(rawMbid);
        if (mbid == null) {
            return Optional.empty();
        }
        synchronized (memLock) {
            CachedArtist mem = memoryByMbid.get(mbid);
            if (mem != null) {
                return Optional.of(mem);
            }
        }
        if (!useDatabase()) {
            return Optional.empty();
        }
        try (Connection conn = databaseService.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT mbid, name, sort_name, disambiguation, country, artist_type, aliases_json, source, updated_at "
                     + "FROM mb_artist_cache WHERE mbid = ?")) {
            ps.setString(1, mbid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    CachedArtist loaded = mapRow(rs);
                    // 仅缓存实体主体，不私自覆写名称索引以避免掩盖数据库里的多实体同名冲突
                    synchronized (memLock) {
                        memoryByMbid.put(loaded.mbid(), loaded);
                    }
                    return Optional.of(loaded);
                }
            }
        } catch (SQLException e) {
            handleDatabaseFailure("读取 MusicBrainz 艺人缓存失败", e);
        }
        return Optional.empty();
    }

    public NameLookupResult lookupByName(String rawName) {
        String key = normalizePersonKey(rawName);
        if (key.isEmpty()) {
            return NameLookupResult.empty();
        }
        if (useDatabase()) {
            try {
                NameLookupResult dbResult = queryByNormalizedName(key);
                if (!dbResult.isEmpty()) {
                    synchronized (memLock) {
                        NameBucket bucket = memoryIndexByName.computeIfAbsent(key, ignored -> new NameBucket());
                        if (dbResult.hasHomonymConflict()) {
                            bucket.homonymConflict = true;
                        }
                        for (CachedArtist artist : dbResult.matches()) {
                            memoryByMbid.put(artist.mbid(), artist);
                            bucket.mbids.add(artist.mbid());
                        }
                        if (bucket.mbids.size() > 1) {
                            bucket.homonymConflict = true;
                        }
                    }
                    return dbResult;
                }
            } catch (SQLException e) {
                handleDatabaseFailure("按名称检索 MusicBrainz 艺人缓存失败", e);
            }
        }
        synchronized (memLock) {
            NameBucket bucket = memoryIndexByName.get(key);
            if (bucket == null || (bucket.mbids.isEmpty() && !bucket.homonymConflict)) {
                return NameLookupResult.empty();
            }
            List<CachedArtist> result = new ArrayList<>(bucket.mbids.size());
            for (String id : bucket.mbids) {
                CachedArtist artist = memoryByMbid.get(id);
                if (artist != null) {
                    result.add(artist);
                }
            }
            if (result.isEmpty() && !bucket.homonymConflict) {
                return NameLookupResult.empty();
            }
            return new NameLookupResult(result, bucket.homonymConflict || result.size() > 1);
        }
    }

    private record ArtistWriteItem(CachedArtist artist, Map<String, String> indexEntries) {}

    private boolean isRedundantWrite(CachedArtist existing, CachedArtist merged, Set<String> keys, long now) {
        if (existing == null || (now - existing.updatedAt()) >= REDUNDANT_WRITE_SKIP_MS) {
            return false;
        }
        if (!Objects.equals(existing.name(), merged.name())
                || !Objects.equals(existing.sortName(), merged.sortName())
                || !Objects.equals(existing.disambiguation(), merged.disambiguation())
                || !Objects.equals(existing.country(), merged.country())
                || !Objects.equals(existing.artistType(), merged.artistType())
                || !Objects.equals(existing.aliases(), merged.aliases())) {
            return false;
        }
        synchronized (memLock) {
            for (String k : keys) {
                NameBucket b = memoryIndexByName.get(k);
                if (b == null || !b.mbids.contains(merged.mbid())) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean useDatabase() {
        return databaseService != null && !dbDegraded.get();
    }

    /** 区分「表缺失（永久降级内存缓存）」与「短暂 SQL 故障（仅本次回退内存）」。 */
    private void handleDatabaseFailure(String message, SQLException e) {
        if (isMissingTableException(e)) {
            if (dbDegraded.compareAndSet(false, true)) {
                log.warn("{}，检测到缓存表缺失，已自动降级为内存缓存 (SQLState={}, code={}, error={})",
                    message, e.getSQLState(), e.getErrorCode(), e.getMessage());
            } else {
                log.debug("{}: {}", message, e.getMessage());
            }
        } else {
            log.warn("{}，本次回退内存缓存 (SQLState={}, code={}, error={})",
                message, e.getSQLState(), e.getErrorCode(), e.getMessage());
        }
    }

    private static boolean isMissingTableException(SQLException e) {
        SQLException cur = e;
        while (cur != null) {
            String state = cur.getSQLState();
            int code = cur.getErrorCode();
            String msg = cur.getMessage() == null ? "" : cur.getMessage().toLowerCase(Locale.ROOT);
            if ("42S02".equalsIgnoreCase(state) || "42P01".equalsIgnoreCase(state) || code == 1146) {
                return true;
            }
            if (msg.contains("no such table") || msg.contains("doesn't exist") || msg.contains("table not found")) {
                return true;
            }
            cur = cur.getNextException();
        }
        return false;
    }

    private void upsertBatchInDatabase(List<ArtistWriteItem> items) throws SQLException {
        if (items == null || items.isEmpty()) {
            return;
        }
        boolean sqlite = databaseService.isSqlite();
        String artistSql = sqlite
            ? "INSERT INTO mb_artist_cache (mbid, name, sort_name, disambiguation, country, artist_type, aliases_json, source, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT(mbid) DO UPDATE SET name=excluded.name, sort_name=excluded.sort_name, "
                + "disambiguation=excluded.disambiguation, country=excluded.country, artist_type=excluded.artist_type, "
                + "aliases_json=excluded.aliases_json, source=excluded.source, updated_at=excluded.updated_at"
            : "INSERT INTO mb_artist_cache (mbid, name, sort_name, disambiguation, country, artist_type, aliases_json, source, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE name=VALUES(name), sort_name=VALUES(sort_name), "
                + "disambiguation=VALUES(disambiguation), country=VALUES(country), artist_type=VALUES(artist_type), "
                + "aliases_json=VALUES(aliases_json), source=VALUES(source), updated_at=VALUES(updated_at)";

        // 同名冲突标记一旦写入即具有粘性，不能被后续普通记录覆写抹掉
        String indexSql = sqlite
            ? "INSERT INTO mb_artist_name_index (normalized_name, mbid, match_kind, updated_at) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(normalized_name, mbid) DO UPDATE SET "
                + "match_kind=CASE WHEN mb_artist_name_index.match_kind='HOMONYM_CANDIDATE' OR excluded.match_kind='HOMONYM_CANDIDATE' "
                + "THEN 'HOMONYM_CANDIDATE' ELSE excluded.match_kind END, updated_at=excluded.updated_at"
            : "INSERT INTO mb_artist_name_index (normalized_name, mbid, match_kind, updated_at) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE "
                + "match_kind=IF(match_kind='HOMONYM_CANDIDATE' OR VALUES(match_kind)='HOMONYM_CANDIDATE', 'HOMONYM_CANDIDATE', VALUES(match_kind)), "
                + "updated_at=VALUES(updated_at)";

        try (Connection conn = databaseService.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(artistSql);
                     PreparedStatement ips = conn.prepareStatement(indexSql)) {
                    boolean hasIndexBatch = false;
                    for (ArtistWriteItem item : items) {
                        CachedArtist artist = item.artist();
                        ps.setString(1, artist.mbid());
                        ps.setString(2, truncate(artist.name(), 500));
                        ps.setString(3, truncate(artist.sortName(), 500));
                        ps.setString(4, truncate(artist.disambiguation(), 500));
                        ps.setString(5, truncate(artist.country(), 32));
                        ps.setString(6, truncate(artist.artistType(), 64));
                        ps.setString(7, GSON.toJson(artist.aliases()));
                        ps.setString(8, truncate(artist.source(), 32));
                        ps.setLong(9, artist.updatedAt());
                        ps.addBatch();

                        for (Map.Entry<String, String> entry : item.indexEntries().entrySet()) {
                            String dbKey = toDbIndexKey(entry.getKey());
                            if (dbKey.isEmpty()) continue;
                            ips.setString(1, dbKey);
                            ips.setString(2, artist.mbid());
                            ips.setString(3, entry.getValue());
                            ips.setLong(4, artist.updatedAt());
                            ips.addBatch();
                            hasIndexBatch = true;
                        }
                    }
                    ps.executeBatch();
                    if (hasIndexBatch) {
                        ips.executeBatch();
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    e.addSuppressed(rollbackEx);
                }
                throw e;
            } finally {
                try {
                    conn.setAutoCommit(originalAutoCommit);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    private NameLookupResult queryByNormalizedName(String fullNormalizedKey) throws SQLException {
        String dbKey = toDbIndexKey(fullNormalizedKey);
        boolean hashed = fullNormalizedKey.length() > MAX_DB_INDEX_KEY_CHARS;
        String sql = "SELECT idx.normalized_name, idx.match_kind, c.mbid, c.name, c.sort_name, "
            + "c.disambiguation, c.country, c.artist_type, c.aliases_json, c.source, c.updated_at "
            + "FROM mb_artist_name_index idx "
            + "INNER JOIN mb_artist_cache c ON c.mbid = idx.mbid "
            + "WHERE idx.normalized_name = ? "
            + "ORDER BY c.updated_at DESC";
        List<CachedArtist> results = new ArrayList<>();
        boolean homonymFlag = false;
        try (Connection conn = databaseService.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dbKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // 在 Java 侧按二进制逐字符校验 normalized_name，免疫存量 MySQL utf8mb4_unicode_ci 的大小写/重音折叠
                    String storedKey = rs.getString("normalized_name");
                    if (!dbKey.equals(storedKey)) {
                        continue;
                    }
                    String matchKind = rs.getString("match_kind");
                    CachedArtist artist = mapRow(rs);
                    if (hashed && !"HOMONYM_CANDIDATE".equals(matchKind) && !artistMatchesExactKey(artist, fullNormalizedKey)) {
                        continue;
                    }
                    if ("HOMONYM_CANDIDATE".equals(matchKind)) {
                        homonymFlag = true;
                    }
                    results.add(artist);
                }
            }
        }
        return new NameLookupResult(results, homonymFlag || results.size() > 1);
    }

    private static boolean artistMatchesExactKey(CachedArtist artist, String fullNormalizedKey) {
        if (normalizePersonKey(artist.name()).equals(fullNormalizedKey)) {
            return true;
        }
        for (String alias : artist.aliases()) {
            if (normalizePersonKey(alias).equals(fullNormalizedKey)) {
                return true;
            }
        }
        return false;
    }

    private static CachedArtist mapRow(ResultSet rs) throws SQLException {
        String aliasesJson = rs.getString("aliases_json");
        List<String> aliases = List.of();
        if (aliasesJson != null && !aliasesJson.isBlank()) {
            try {
                List<String> parsed = GSON.fromJson(aliasesJson, STRING_LIST_TYPE);
                if (parsed != null) {
                    aliases = parsed.stream()
                        .filter(s -> s != null && !s.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
                }
            } catch (Exception ignored) {
                aliases = List.of();
            }
        }
        return new CachedArtist(
            rs.getString("mbid"),
            rs.getString("name"),
            rs.getString("sort_name"),
            rs.getString("disambiguation"),
            rs.getString("country"),
            rs.getString("artist_type"),
            aliases,
            rs.getString("source"),
            rs.getLong("updated_at")
        );
    }

    private void putInMemory(CachedArtist artist, Set<String> keys, boolean markConflict) {
        synchronized (memLock) {
            memoryByMbid.put(artist.mbid(), artist);
            for (String key : keys) {
                if (key == null || key.isEmpty()) continue;
                NameBucket bucket = memoryIndexByName.computeIfAbsent(key, ignored -> new NameBucket());
                bucket.mbids.add(artist.mbid());
                if (markConflict || bucket.mbids.size() > 1) {
                    bucket.homonymConflict = true;
                }
            }
        }
    }

    private void removeMbidFromMemoryIndex(String mbid) {
        // 若被驱逐的艺人属于已知同名冲突桶（>=2 成员或带冲突标志），必须整桶失效，
        // 绝不能将其缩减为 1 个成员而在后续误判为唯一命中！
        memoryIndexByName.entrySet().removeIf(entry -> {
            NameBucket bucket = entry.getValue();
            if (!bucket.mbids.contains(mbid)) {
                return false;
            }
            if (bucket.homonymConflict || bucket.mbids.size() > 1) {
                return true;
            }
            bucket.mbids.remove(mbid);
            return bucket.mbids.isEmpty();
        });
    }

    private static void putIndexEntry(Map<String, String> target, String rawName, String matchKind) {
        String key = normalizePersonKey(rawName);
        if (!key.isEmpty()) {
            target.putIfAbsent(key, matchKind);
        }
    }

    private static String preferNonBlank(String candidate, String fallback) {
        if (candidate != null && !candidate.isBlank()) {
            return candidate.trim();
        }
        return fallback != null && !fallback.isBlank() ? fallback.trim() : null;
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
