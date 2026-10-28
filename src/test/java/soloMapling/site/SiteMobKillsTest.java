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
        assertEquals(List.of("mob", "name", "kills"), List.copyOf(row.keySet()));
        assertEquals(3L, ((Number) row.get("kills")).longValue());
        assertEquals("Snail", row.get("name"));
        assertNotNull(((Map<?, ?>) doc).get("top_mobs_7d"));
    }

    /** In-memory stand-in for the table. */
    static final class FakeStore implements SiteMobKills.Store {
        final Map<Long, Map<Integer, Integer>> rows = new java.util.TreeMap<>();
        boolean broken;

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
}
