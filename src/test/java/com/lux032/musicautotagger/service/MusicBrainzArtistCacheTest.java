package com.lux032.musicautotagger.service;

import com.lux032.musicautotagger.config.MusicConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MusicBrainzArtistCacheTest {

    private static final String MBID_A = "11111111-1111-4111-8111-111111111111";
    private static final String MBID_B = "22222222-2222-4222-8222-222222222222";

    @TempDir
    Path tempDir;

    private MusicConfig sqliteConfig(Path dbFile) throws Exception {
        Constructor<MusicConfig> ctor = MusicConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        MusicConfig config = ctor.newInstance();
        config.setDbType("sqlite");
        config.setDbSqlitePath(dbFile.toString());
        return config;
    }

    @Test
    void sqliteCachePersistsUniqueArtistAliasesAndDetectsHomonymConflict() throws Exception {
        Path dbFile = tempDir.resolve("artist-cache.db");
        try (DatabaseService db = new DatabaseService(sqliteConfig(dbFile))) {
            MusicBrainzArtistCache cache = new MusicBrainzArtistCache(db);
            cache.recordArtist(
                MBID_A,
                "梶浦由記",
                "Kajiura, Yuki",
                "Japanese composer",
                "JP",
                "Person",
                List.of("Yuki Kajiura"),
                List.of("梶浦　由記"),
                "MB_RECORDING"
            );

            // 使用全新实例模拟跨重启从 SQLite 命中
            MusicBrainzArtistCache reloaded = new MusicBrainzArtistCache(db);
            MusicBrainzArtistCache.NameLookupResult primaryHit = reloaded.lookupByName("梶浦由記");
            assertTrue(primaryHit.isUniqueHit());
            assertEquals(MBID_A, primaryHit.uniqueArtist().mbid());
            assertEquals("Kajiura, Yuki", primaryHit.uniqueArtist().sortName());
            assertEquals("Japanese composer", primaryHit.uniqueArtist().disambiguation());

            // 别名与全角空格归一化也能 0ms 唯一命中
            MusicBrainzArtistCache.NameLookupResult aliasHit = reloaded.lookupByName("  yuki   kajiura ");
            assertTrue(aliasHit.isUniqueHit());
            assertEquals(MBID_A, aliasHit.uniqueArtist().mbid());

            // 记录另一个同名实体后自动触发同名冲突检测，不再返回 uniqueHit
            reloaded.recordArtist(
                MBID_B,
                "高橋諒",
                "Takahashi, Ryo",
                "Japanese composer",
                "JP",
                "Person",
                List.of(),
                List.of(),
                "MB_CREATOR"
            );
            reloaded.recordHomonymCandidates("高橋諒", List.of(
                new MusicBrainzArtistCache.CachedArtist(MBID_A, "高橋諒", "Takahashi, Ryo", "Actor", "JP", "Person", List.of(), "MB_SEARCH", 1L),
                new MusicBrainzArtistCache.CachedArtist(MBID_B, "高橋諒", "Takahashi, Ryo", "Japanese composer", "JP", "Person", List.of(), "MB_SEARCH", 2L)
            ));

            MusicBrainzArtistCache fresh = new MusicBrainzArtistCache(db);
            MusicBrainzArtistCache.NameLookupResult homonym = fresh.lookupByName("高橋諒");
            assertFalse(homonym.isUniqueHit());
            assertTrue(homonym.hasHomonymConflict());
            assertEquals(2, homonym.matches().size());
            assertNull(homonym.uniqueArtist());
        }
    }

    @Test
    void fileModeUsesInMemoryCacheAndMissingTablesGracefullyDegrade() throws Exception {
        // 文件模式（无数据库）
        MusicBrainzArtistCache memOnly = new MusicBrainzArtistCache(null);
        memOnly.recordArtist(MBID_A, "YOASOBI", "YOASOBI", null, "JP", "Group", List.of(), List.of(), "MB_RELEASE");
        assertTrue(memOnly.lookupByName("yoasobi").isUniqueHit());
        assertEquals(MBID_A, memOnly.findByMbid(MBID_A.toUpperCase()).orElseThrow().mbid());
        assertNull(MusicBrainzArtistCache.normalizeValidMbid("00000000-0000-0000-0000-000000000000"));
        assertNull(MusicBrainzArtistCache.normalizeValidMbid("not-a-uuid"));

        // 模拟老 MySQL / 缺失表场景：删掉缓存表后读写不抛异常且自动降级到内存缓存
        Path dbFile = tempDir.resolve("degrade.db");
        try (DatabaseService db = new DatabaseService(sqliteConfig(dbFile))) {
            try (Connection conn = db.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE mb_artist_name_index");
                stmt.execute("DROP TABLE mb_artist_cache");
            }
            MusicBrainzArtistCache degraded = new MusicBrainzArtistCache(db);
            assertDoesNotThrow(() -> degraded.recordArtist(
                MBID_A, "Aimer", "Aimer", null, "JP", "Person", List.of(), List.of(), "MB_RECORDING"));
            assertTrue(degraded.isDbDegraded());
            MusicBrainzArtistCache.NameLookupResult hit = degraded.lookupByName("aimer");
            assertTrue(hit.isUniqueHit());
            assertEquals(MBID_A, hit.uniqueArtist().mbid());
        }
    }

    @Test
    void homonymConflictSurvivesEvictionLongKeysDoNotCollideAndTransientErrorDoesNotDegrade() throws Exception {
        // 1. 超过 255 字符且前缀相同的两个人名不得碰撞，Unicode NBSP 正确折叠
        String prefix = "a".repeat(260);
        String longA = prefix + "\u00A0one";
        String longB = prefix + "\u00A0two";
        assertNotEquals(MusicBrainzArtistCache.normalizePersonKey(longA),
            MusicBrainzArtistCache.normalizePersonKey(longB));
        assertEquals("a b", MusicBrainzArtistCache.normalizePersonKey("A\u00A0\u3000B"));

        Path dbFile = tempDir.resolve("long-keys.db");
        try (DatabaseService db = new DatabaseService(sqliteConfig(dbFile))) {
            MusicBrainzArtistCache cache = new MusicBrainzArtistCache(db);
            cache.recordArtist(MBID_A, longA, null, null, "JP", "Person", List.of(), List.of(), "TEST");
            cache.recordArtist(MBID_B, longB, null, null, "JP", "Person", List.of(), List.of(), "TEST");

            MusicBrainzArtistCache reloaded = new MusicBrainzArtistCache(db);
            assertEquals(MBID_A, reloaded.lookupByName(longA).uniqueArtist().mbid());
            assertEquals(MBID_B, reloaded.lookupByName(longB).uniqueArtist().mbid());
        }

        // 2. 内存 LRU 驱逐同名桶中的一位艺人时，整桶失效而绝不退化为误判唯一命中
        MusicBrainzArtistCache mem = new MusicBrainzArtistCache(null);
        mem.recordHomonymCandidates("Common Name", List.of(
            new MusicBrainzArtistCache.CachedArtist(MBID_A, "Common Name", null, "First", "JP", "Person", List.of(), "TEST", 1L),
            new MusicBrainzArtistCache.CachedArtist(MBID_B, "Common Name", null, "Second", "US", "Person", List.of(), "TEST", 2L)
        ));
        assertTrue(mem.lookupByName("Common Name").hasHomonymConflict());
        // 写入 4100 个新艺人触发 LRU 驱逐 MBID_A / MBID_B
        for (int i = 0; i < 4100; i++) {
            String id = String.format("00000000-0000-4000-8000-%012x", i + 1);
            mem.recordArtist(id, "Filler Artist " + i, null, null, null, null, List.of(), List.of(), "TEST");
        }
        assertFalse(mem.lookupByName("Common Name").isUniqueHit(),
            "Evicting one artist from a homonym bucket must NEVER produce a false unique hit");

        // 3. 瞬态 SQL 异常（非缺表）不永久降级数据库缓存
        DatabaseService transientDb = new DatabaseService(sqliteConfig(tempDir.resolve("transient.db"))) {
            private boolean failedOnce = false;
            @Override
            public Connection getConnection() throws java.sql.SQLException {
                if (!failedOnce) {
                    failedOnce = true;
                    throw new java.sql.SQLException("temporary connection reset", "08001", 0);
                }
                return super.getConnection();
            }
        };
        try (transientDb) {
            MusicBrainzArtistCache cache = new MusicBrainzArtistCache(transientDb);
            cache.recordArtist(MBID_A, "Transient Test", null, null, "JP", "Person", List.of(), List.of(), "TEST");
            assertFalse(cache.isDbDegraded(), "Transient SQL failure must not permanently degrade DB cache");
            // 后续写盘应恢复正常写入 SQLite
            cache.recordArtist(MBID_B, "Recovered Artist", null, null, "JP", "Person", List.of(), List.of(), "TEST");
            MusicBrainzArtistCache check = new MusicBrainzArtistCache(transientDb);
            assertTrue(check.lookupByName("Recovered Artist").isUniqueHit());
        }
    }
}
