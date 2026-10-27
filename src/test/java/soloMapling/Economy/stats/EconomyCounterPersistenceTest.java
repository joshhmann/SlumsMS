package soloMapling.Economy.stats;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** Owner 2026-10-26: the day's economy counters survive a restart (migration 032, {@code economycounters}). */
class EconomyCounterPersistenceTest {

    /** The database, in memory: day -> key -> amount, with the Jdbc store's keep-the-larger rule. */
    static final class MemoryStore implements EconomyCounterPersistence.Store {
        final Map<LocalDate, Map<String, Long>> rows = new TreeMap<>();

        @Override
        public Map<String, Long> load(LocalDate day) {
            return new HashMap<>(rows.getOrDefault(day, Map.of()));
        }

        @Override
        public void save(LocalDate day, Map<String, Long> totals, long nowMs) {
            Map<String, Long> r = rows.computeIfAbsent(day, d -> new HashMap<>());
            totals.forEach((k, v) -> r.merge(k, v, Math::max));
        }

        @Override
        public void pruneBefore(LocalDate day) {
            rows.keySet().removeIf(d -> d.isBefore(day));
        }
    }

    static final LocalDate DAY = LocalDate.of(2026, 10, 26);

    @BeforeEach
    @AfterEach
    void clean() {
        EconomyCounters.resetForTest();
    }

    @Test
    void aRestartKeepsTheDaysTotals() {
        MemoryStore db = new MemoryStore();
        EconomyCounterPersistence before = new EconomyCounterPersistence(db);
        before.boot(DAY);
        EconomyCounters.recordMobMesoDrop(true, 40_000);
        EconomyCounters.recordQuestReward(5_000);
        EconomyCounters.recordNpcFee(1_200);
        EconomyCounters.recordNxSpent(300);
        before.save(1L); // the shutdown save

        EconomyCounters.resetForTest(); // the restart: a new JVM starts from zero
        EconomyCounters.recordNpcBuy(700); // something counted before the restore ran
        EconomyCounterPersistence after = new EconomyCounterPersistence(db);
        after.boot(DAY);

        var snap = EconomyCounters.peek();
        assertEquals(40_000, snap.mobMesoDropsBot());
        assertEquals(5_000, snap.questRewardMesos());
        assertEquals(1_200, snap.npcFeeMesos());
        assertEquals(300, snap.nxSpent());
        assertEquals(700, snap.npcBuyMesos(), "counts made before the restore are kept");
    }

    @Test
    void aBootOnANewDayStartsFromZero() {
        MemoryStore db = new MemoryStore();
        db.save(DAY, Map.of("npc_sell", 9_000L), 1L);
        new EconomyCounterPersistence(db).boot(DAY.plusDays(1));
        assertEquals(0, EconomyCounters.peek().npcSellMesos());
    }

    @Test
    void closingTheDayStoresItsFinalTotalsAndMovesTheWindow() {
        MemoryStore db = new MemoryStore();
        EconomyCounterPersistence p = new EconomyCounterPersistence(db);
        p.boot(DAY);
        EconomyCounters.recordFmTax(2_500);
        // the nightly snapshot just after midnight closes DAY
        p.closeDay(DAY, EconomyCounters.snapshotAndReset(), 2L);
        assertEquals(DAY.plusDays(1), p.windowDay());
        assertEquals(2_500L, db.rows.get(DAY).get("fm_tax"));

        EconomyCounters.recordFmTax(100);
        p.save(3L);
        assertEquals(2_500L, db.rows.get(DAY).get("fm_tax"), "the closed day is not overwritten");
        assertEquals(100L, db.rows.get(DAY.plusDays(1)).get("fm_tax"));
    }

    @Test
    void aSaveBeforeTheNightlyJobStillUsesTheOpenDay() {
        MemoryStore db = new MemoryStore();
        EconomyCounterPersistence p = new EconomyCounterPersistence(db);
        p.boot(DAY);
        EconomyCounters.recordUpkeep(800);
        p.save(1L); // runs at 00:00:05 on the next day, before the nightly job: still DAY's counts
        assertEquals(800L, db.rows.get(DAY).get("upkeep"));
        assertNull(db.rows.get(DAY.plusDays(1)));
    }

    @Test
    void counterRowsAreKeptForeverByDefaultAndPrunedWithTheKnob() {
        MemoryStore db = new MemoryStore();
        for (int i = 0; i < 120; i++) {
            db.save(DAY.minusDays(i), Map.of("trade", 1L), 1L);
        }
        EconomyCounterPersistence p = new EconomyCounterPersistence(db);
        p.boot(DAY.plusDays(1));
        p.closeDay(DAY, EconomyCounters.snapshotAndReset(), 2L);
        assertEquals(120, db.rows.size(), "history is kept: nothing pruned by default");

        System.setProperty("slums.history.economyKeepDays", "90");
        try {
            p.closeDay(DAY, EconomyCounters.snapshotAndReset(), 3L);
        } finally {
            System.clearProperty("slums.history.economyKeepDays");
        }
        assertEquals(90, db.rows.size());
        assertEquals(DAY.minusDays(89), db.rows.keySet().iterator().next());
    }

    @Test
    void everyCounterHasAStableRowKey() {
        assertEquals(EconomyCounters.Key.values().length, EconomyCounterPersistence.totals(EconomyCounters.peek()).size());
        assertEquals(EconomyCounters.Key.NPC_FEE, EconomyCounters.Key.byDbKey("npc_fee"));
        EconomyCounters.restore(Map.of("not_a_counter", 5L, "npc_fee", -3L));
        assertEquals(0, EconomyCounters.peek().totalSinks());
    }

    @Test
    void theMigrationIsIdempotentLedgeredAndRollsBack() throws Exception {
        Path dir = Path.of("database/migrations");
        String id = "032-economy-counters";
        String sql = Files.readString(dir.resolve(id + ".sql"));
        String rollback = Files.readString(dir.resolve(id + ".rollback.sql"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS economycounters"));
        assertTrue(sql.contains("PRIMARY KEY (Day, CounterKey)"));
        assertTrue(sql.contains("mapleroot_integration_migrations(migration_id) VALUES ('" + id + "')"));
        assertTrue(rollback.contains("DROP TABLE IF EXISTS economycounters"));
        assertTrue(rollback.contains("migration_id='" + id + "'"), "the rollback clears its ledger row");
        for (String col : new String[]{"Day", "CounterKey", "Amount", "UpdatedAt"}) {
            assertTrue(sql.contains(col + " ") && EconomyCounterPersistence.Jdbc.UPSERT.contains(col), col);
        }
        assertTrue(EconomyCounterPersistence.Jdbc.UPSERT.contains("GREATEST(Amount"),
                "a store never lowers a day's stored total");
        assertTrue(Files.readString(Path.of("docs/release/1.6.0.conf")).contains(id), "listed for the release");
    }
}
