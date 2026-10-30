package soloMapling.site;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.server.Json;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The most-hunted-monsters counter behind world.json's top_mobs and top_mobs_7d. */
class SiteMobKillsTest {

    static final long HOUR = TimeUnit.HOURS.toMillis(1);
    final AtomicLong now = new AtomicLong(1_793_000_000_000L);

    @BeforeEach
    void setUp() {
        SiteMobKills.reset(now::get);
        SiteMobKills.setRecording(true);
    }

    @AfterEach
    void tearDown() {
        SiteMobKills.setRecording(false);
        SiteMobKills.reset(null);
    }

    @Test
    void offCountsNothing() {
        SiteMobKills.setRecording(false);
        SiteMobKills.kill(100100);
        assertEquals(List.of(), SiteMobKills.top(24, now.get(), id -> "x"));
    }

    @Test
    void top25ByKillsWithNamesAndTiesByLowerId() {
        for (int mob = 1; mob <= 30; mob++) {
            for (int i = 0; i < mob; i++) {
                SiteMobKills.kill(mob);
            }
        }
        SiteMobKills.kill(29); // 29 and 30 now tie on 30 kills
        List<SiteMobKills.Row> top = SiteMobKills.top(24, now.get(), id -> "Mob " + id);
        assertEquals(SiteMobKills.TOP, top.size());
        assertEquals(new SiteMobKills.Row(29, "Mob 29", 30), top.get(0));
        assertEquals(30, top.get(1).mob());
        assertEquals(28, top.get(2).mob());
    }

    @Test
    void theDayWindowForgetsOldHoursButTheWeekKeepsThem() {
        SiteMobKills.kill(7);
        now.addAndGet(30 * HOUR);
        SiteMobKills.kill(8);
        SiteMobKills.kill(8);
        assertEquals(List.of(8), SiteMobKills.top(24, now.get(), id -> "").stream().map(SiteMobKills.Row::mob).toList());
        assertEquals(List.of(8, 7), SiteMobKills.top(SiteMobKills.HOURS, now.get(), id -> "").stream().map(SiteMobKills.Row::mob).toList());
        now.addAndGet(8 * 24 * HOUR);
        assertEquals(List.of(), SiteMobKills.top(SiteMobKills.HOURS, now.get(), id -> ""), "older than 7 days is gone");
    }

    @Test
    void aReusedSlotStartsEmpty() {
        SiteMobKills.kill(5);
        now.addAndGet(SiteMobKills.HOURS * HOUR); // the same ring slot, a week later
        SiteMobKills.kill(6);
        assertEquals(List.of(6), SiteMobKills.top(SiteMobKills.HOURS, now.get(), id -> "").stream().map(SiteMobKills.Row::mob).toList());
    }

    @Test
    void worldJsonCarriesBothListsAndStaysShaped() {
        for (int i = 0; i < 3; i++) {
            SiteMobKills.kill(100100);
        }
        List<SiteMobKills.Row> day = SiteMobKills.top(24, now.get(), id -> "Snail");
        Map<String, Object> file = SiteWorld.file(new SiteWorld.Online(Map.of(), Map.of(), Map.of()), null, List.of(),
                now.get(), day, day);
        Object doc = Json.parse(Json.write(file));
        assertEquals(List.of(), SiteFileShape.shape("world.json", doc));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) ((Map<?, ?>) doc).get("top_mobs")).getFirst();
        // 1.6.2 b015: the old keys first and unchanged (old readers keep working), then the split
        assertEquals(List.of("mob", "name", "kills", "counted", "estimated"), List.copyOf(row.keySet()));
        assertEquals(3L, ((Number) row.get("kills")).longValue());
        assertEquals("Snail", row.get("name"));
        assertNotNull(((Map<?, ?>) doc).get("top_mobs_7d"));
    }

    /** In-memory stand-in for the table. */
    static final class FakeStore implements SiteMobKills.Store {
        final Map<Long, Map<Integer, Integer>> rows = new java.util.TreeMap<>();
        final Map<Long, Map<Integer, Integer>> est = new java.util.TreeMap<>();
        boolean broken;
        boolean noEstimatedColumn;

        @Override
        public void writeEstimated(Map<Long, Map<Integer, Integer>> c) throws Exception {
            if (noEstimatedColumn) {
                throw new java.sql.SQLException("Unknown column 'estimated'");
            }
            c.forEach((h, m) -> est.computeIfAbsent(h, k -> new java.util.HashMap<>()).putAll(m));
        }

        @Override
        public Map<Long, Map<Integer, Integer>> readEstimated(long from) throws Exception {
            if (noEstimatedColumn) {
                throw new java.sql.SQLException("Unknown column 'estimated'");
            }
            Map<Long, Map<Integer, Integer>> out = new java.util.HashMap<>();
            est.forEach((h, m) -> {
                if (h >= from) {
                    out.put(h, new java.util.HashMap<>(m));
                }
            });
            return out;
        }

        /** Hours whose rows carry a non-null estimated value (the rest were written before the split). */
        @Override
        public long oldestSplitHour(long from) throws Exception {
            if (noEstimatedColumn) {
                throw new java.sql.SQLException("Unknown column 'estimated'");
            }
            return est.keySet().stream().filter(h -> h >= from).min(Long::compare).orElse(-1L);
        }

        public void write(Map<Long, Map<Integer, Integer>> c) throws Exception {
            if (broken) {
                throw new java.sql.SQLException("no table");
            }
            c.forEach((h, m) -> rows.computeIfAbsent(h, k -> new java.util.HashMap<>()).putAll(m));
        }

        public Map<Long, Map<Integer, Integer>> read(long from) throws Exception {
            if (broken) {
                throw new java.sql.SQLException("no table");
            }
            Map<Long, Map<Integer, Integer>> out = new java.util.HashMap<>();
            rows.forEach((h, m) -> {
                if (h >= from) {
                    out.put(h, new java.util.HashMap<>(m));
                }
            });
            return out;
        }

        public void prune(long before) {
            rows.keySet().removeIf(h -> h < before);
        }
    }

    @Test
    void flushWritesRunningTotalsAndARestartReadsThemBack() {
        FakeStore store = new FakeStore();
        SiteMobKills.kill(5);
        SiteMobKills.kill(5);
        assertTrue(SiteMobKills.flush(store, now.get()));
        SiteMobKills.kill(5); // flushed again later: the total is set, not added twice
        assertTrue(SiteMobKills.flush(store, now.get()));
        assertEquals(3, store.rows.get(now.get() / HOUR).get(5));

        SiteMobKills.reset(now::get); // restart
        SiteMobKills.restore(store, now.get());
        SiteMobKills.kill(5);
        assertEquals(4, SiteMobKills.top(24, now.get(), id -> "").getFirst().kills());
    }

    @Test
    void flushPrunesPastThirtyDaysAndSkipsOldHours() {
        FakeStore store = new FakeStore();
        long hourNow = now.get() / HOUR;
        store.rows.put(hourNow - 31 * 24, Map.of(1, 9));
        store.rows.put(hourNow - 29 * 24, Map.of(2, 9));
        SiteMobKills.flush(store, now.get());
        assertFalse(store.rows.containsKey(hourNow - 31 * 24));
        assertTrue(store.rows.containsKey(hourNow - 29 * 24), "kept for 30 days");
        SiteMobKills.restore(store, now.get());
        assertEquals(List.of(), SiteMobKills.top(SiteMobKills.HOURS, now.get(), id -> ""), "only the last 7 days are read back");
    }

    @Test
    void aMissingTableNeverHurts() {
        FakeStore store = new FakeStore();
        store.broken = true;
        SiteMobKills.kill(1);
        assertFalse(SiteMobKills.flush(store, now.get()));
        SiteMobKills.restore(store, now.get());
        assertEquals(1, SiteMobKills.top(24, now.get(), id -> "").getFirst().kills());
    }

    @Test
    void migration047HasItsLedgerAndRollback() throws Exception {
        java.nio.file.Path d = java.nio.file.Path.of("database/migrations");
        String sql = java.nio.file.Files.readString(d.resolve("047-mob-kills.sql"));
        String rb = java.nio.file.Files.readString(d.resolve("047-mob-kills.rollback.sql"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS site_mob_kills"));
        assertTrue(sql.contains("VALUES ('047-mob-kills')"));
        assertTrue(rb.contains("DROP TABLE IF EXISTS site_mob_kills"));
        assertTrue(rb.contains("migration_id = '047-mob-kills'"));
    }

    @Test
    void offscreenKillsSpreadOverTheSpawnTableAndCarryTheRemainder() {
        double left = SiteMobKills.offscreen(List.of(1, 1, 1, 2), 400.5);
        assertEquals(200.5, left, 1e-9, "capped per call, the rest carries");
        List<SiteMobKills.Row> top = SiteMobKills.top(24, now.get(), id -> "");
        assertEquals(200, top.stream().mapToInt(SiteMobKills.Row::kills).sum());
        assertEquals(0.4, SiteMobKills.offscreen(List.of(1), 0.4), 1e-9, "under one kill: nothing yet");
        SiteMobKills.setRecording(false);
        assertEquals(0, SiteMobKills.offscreen(List.of(1), 5), 1e-9);
        assertEquals(200, SiteMobKills.top(24, now.get(), id -> "").stream().mapToInt(SiteMobKills.Row::kills).sum());
    }

    // ---- 1.6.2 b015: counted vs estimated ----

    @Test
    void theSplitSumsToTheTotalAndRowsStaySortedByKills() {
        for (int i = 0; i < 5; i++) {
            SiteMobKills.kill(100100);                       // a player or an on-screen bot: counted
        }
        SiteMobKills.offscreen(List.of(100100), 3);         // offscreen: estimated
        SiteMobKills.offscreen(List.of(1210100), 7);
        List<SiteMobKills.Row> top = SiteMobKills.top(24, now.get(), id -> "");
        assertEquals(List.of(100100, 1210100), top.stream().map(SiteMobKills.Row::mob).toList(), "8 before 7");
        SiteMobKills.Row snail = top.get(0), pig = top.get(1);
        assertEquals(8, snail.kills());
        assertEquals(5, snail.counted());
        assertEquals(3, snail.estimated());
        assertEquals(7, pig.kills());
        assertEquals(0, pig.counted());
        assertEquals(7, pig.estimated());
        for (SiteMobKills.Row r : top) {
            assertEquals(r.kills(), r.counted() + r.estimated());
        }
        Map<?, ?> doc = (Map<?, ?>) Json.parse(Json.write(SiteWorld.file(
                new SiteWorld.Online(Map.of(), Map.of(), Map.of()), null, List.of(), now.get(), top, top)));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) doc.get("top_mobs")).getFirst();
        assertEquals(8L, ((Number) row.get("kills")).longValue());
        assertEquals(5L, ((Number) row.get("counted")).longValue());
        assertEquals(3L, ((Number) row.get("estimated")).longValue());
        assertEquals(List.of(), SiteFileShape.shape("world.json", doc));
    }

    @Test
    void theSplitSurvivesARestartAndAnOldTableKeepsTheTotals() {
        FakeStore store = new FakeStore();
        SiteMobKills.kill(5);
        SiteMobKills.offscreen(List.of(5), 2);
        assertTrue(SiteMobKills.flush(store, now.get()));
        SiteMobKills.reset(now::get);
        SiteMobKills.restore(store, now.get());
        SiteMobKills.Row r = SiteMobKills.top(24, now.get(), id -> "").getFirst();
        assertEquals(List.of(3, 1, 2), List.of(r.kills(), r.counted(), r.estimated()));

        // before migration 056: the totals still flush and restore, the split is not kept
        FakeStore old = new FakeStore();
        old.noEstimatedColumn = true;
        assertTrue(SiteMobKills.flush(old, now.get()));
        SiteMobKills.reset(now::get);
        SiteMobKills.restore(old, now.get());
        r = SiteMobKills.top(24, now.get(), id -> "").getFirst();
        assertEquals(List.of(3, 3, 0), List.of(r.kills(), r.counted(), r.estimated()));
    }

    @Test
    void migration056AddsTheColumnOnceAndRollsBack() throws Exception {
        java.nio.file.Path d = java.nio.file.Path.of("database/migrations");
        String sql = java.nio.file.Files.readString(d.resolve("056-mob-kills-split.sql"));
        String rb = java.nio.file.Files.readString(d.resolve("056-mob-kills-split.rollback.sql"));
        assertTrue(sql.contains("ADD COLUMN estimated INT NOT NULL DEFAULT 0") && sql.contains("information_schema.COLUMNS"));
        assertTrue(sql.contains("VALUES ('056-mob-kills-split')"));
        assertTrue(rb.contains("DROP COLUMN estimated") && rb.contains("migration_id = '056-mob-kills-split'"));
    }

    @Test
    void splitSinceIsTheFirstHourWithRealSplitData() {
        assertNull(SiteMobKills.splitSince()); // nothing recorded, nothing restored

        // old unsplit hours restored from a store (rows, no estimated values) do not count as split
        FakeStore store = new FakeStore();
        long hourMs = 3_600_000L;
        long nowHour = now.get() / hourMs;
        store.rows.put(nowHour - 5, new java.util.HashMap<>(Map.of(5, 40)));
        store.rows.put(nowHour - 4, new java.util.HashMap<>(Map.of(5, 40)));
        SiteMobKills.restore(store, now.get());
        assertNull(SiteMobKills.splitSince());

        // this build's first recorded hour starts the split
        SiteMobKills.kill(5);
        assertEquals(nowHour * hourMs, SiteMobKills.splitSince());

        // a restored hour the store marks split-aware (non-null, even 0) and older than ours moves it back
        SiteMobKills.reset(now::get);
        store.est.put(nowHour - 3, new java.util.HashMap<>(Map.of(5, 0)));
        store.rows.put(nowHour - 3, new java.util.HashMap<>(Map.of(5, 7)));
        SiteMobKills.restore(store, now.get());
        SiteMobKills.kill(5);
        assertEquals((nowHour - 3) * hourMs, SiteMobKills.splitSince());

        // a store without the column (migration 056 not applied) tells nothing: only our own hour
        SiteMobKills.reset(now::get);
        FakeStore old = new FakeStore();
        old.noEstimatedColumn = true;
        SiteMobKills.restore(old, now.get());
        SiteMobKills.kill(5);
        assertEquals(nowHour * hourMs, SiteMobKills.splitSince());
    }

    @Test
    void flushWritesAZeroEstimateSoARealHourIsNotUnknown() {
        FakeStore store = new FakeStore();
        SiteMobKills.kill(5); // counted only, no offscreen kill
        assertTrue(SiteMobKills.flush(store, now.get()));
        assertEquals(Map.of(5, 0), store.est.get(now.get() / 3_600_000L));
    }

    @Test
    void worldJsonCarriesSplitSinceOrNull() {
        Map<String, Object> with = SiteWorld.file(new SiteWorld.Online(Map.of(), Map.of(), Map.of()), null, List.of(),
                now.get(), List.of(), List.of(), 1_700_000_000_000L);
        assertEquals(1_700_000_000_000L, with.get("split_since"));
        Map<String, Object> none = SiteWorld.file(new SiteWorld.Online(Map.of(), Map.of(), Map.of()), null, List.of(),
                now.get(), List.of(), List.of());
        assertTrue(none.containsKey("split_since"));
        assertNull(none.get("split_since"));
        assertTrue(none.containsKey("top_mobs") && none.containsKey("top_mobs_7d"));
    }

    @Test
    void migration057AllowsNullAndRollsBack() throws Exception {
        java.nio.file.Path d = java.nio.file.Path.of("database/migrations");
        String sql = java.nio.file.Files.readString(d.resolve("057-mob-kills-split-null.sql"));
        String rb = java.nio.file.Files.readString(d.resolve("057-mob-kills-split-null.rollback.sql"));
        assertTrue(sql.contains("MODIFY COLUMN estimated INT NULL DEFAULT NULL"));
        assertTrue(sql.contains("VALUES ('057-mob-kills-split-null')"));
        assertTrue(rb.contains("INT NOT NULL DEFAULT 0") && rb.contains("migration_id = '057-mob-kills-split-null'"));
    }
}
