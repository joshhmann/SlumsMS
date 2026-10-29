package database;

import org.junit.jupiter.api.Test;
import soloMapling.WzTestData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner 2026-10-29: migration 055 gives drop tables to the six mobs 050 missed because they sit outside its regions
 * (Cygnus' knights 8850006-8850009, Castellan 9400408, the Arcane Lantern Erda 8641008), by 050's rules, and leaves
 * the phase/form stubs dropless. The checks 050's rows pass (BbDropTablesDataTest) hold here too.
 */
class BossDropGapsDataTest {

    static final Path SQL = Path.of("database/migrations/055-boss-drop-gaps.sql");
    static final Path ROLLBACK = Path.of("database/migrations/055-boss-drop-gaps.rollback.sql");
    static final Set<Integer> KNIGHTS = Set.of(8850006, 8850007, 8850008, 8850009);
    static final int CASTELLAN = 9400408;
    static final int LANTERN_ERDA = 8641008;
    static final List<Integer> STUBS = List.of(8850005, 8850011, 8880150, 8880302, 8880415, 8645009, 8641009);

    static List<BbDropTablesDataTest.Row> rows() throws Exception {
        return BbDropTablesDataTest.rows(SQL);
    }

    @Test
    void exactlyTheSixMobsGetTablesAndTheStubsNone() throws Exception {
        List<BbDropTablesDataTest.Row> rows = rows();
        Set<Integer> mobs = rows.stream().map(BbDropTablesDataTest.Row::mob).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(new TreeSet<>(List.of(8641008, 8850006, 8850007, 8850008, 8850009, 9400408)), mobs);
        for (int stub : STUBS) {
            assertFalse(mobs.contains(stub), "stub " + stub + " stays dropless");
        }
        BbDropTablesDataTest.assertWellFormed(rows);
        // nothing 050 already covers
        Set<Integer> in050 = BbDropTablesDataTest.rows().stream().map(BbDropTablesDataTest.Row::mob).collect(Collectors.toSet());
        for (int mob : mobs) {
            assertFalse(in050.contains(mob), mob + " is already 050's");
        }
    }

    @Test
    void tiersFollow050sRules() throws Exception {
        Map<Integer, List<BbDropTablesDataTest.Row>> byMob = rows().stream()
                .collect(Collectors.groupingBy(BbDropTablesDataTest.Row::mob));
        for (int knight : KNIGHTS) {
            List<BbDropTablesDataTest.Row> t = byMob.get(knight);
            // a boss (boss flag, 2.1B HP): Zakum-shaped, the 700000 meso row and 90 x level base mesos
            assertTrue(t.stream().anyMatch(r -> r.item() == 0 && r.chance() == 700000), "boss meso row for " + knight);
            assertTrue(t.stream().anyMatch(r -> r.item() == 2000004 && r.chance() == 999999), "Zakum's Elixir row");
            // top gear stays rare: one outclassing piece at 3000 (1000 for AbsoLab), never more
            long rare = t.stream().filter(r -> r.item() / 1000000 == 1 && r.chance() <= 3000).count();
            assertEquals(1, rare, "one rare outclassing piece for " + knight);
            assertEquals(30, t.size(), "boss table size for " + knight);
        }
        // Castellan: boss flag but under 1B HP, so an elite; Ninja Castle is classic, so no post-Big-Bang gear
        List<BbDropTablesDataTest.Row> castellan = byMob.get(CASTELLAN);
        assertTrue(castellan.stream().anyMatch(r -> r.item() == 0 && r.chance() == 400000 && r.min() == 4608),
                "elite mesos: 4x the classic lv 180 midpoint");
        for (String line : Files.readAllLines(Path.of("docs/economy/bb-drops/gap-rows.csv"))) {
            if (line.startsWith(CASTELLAN + ",")) {
                assertFalse(line.contains(",bbgear,") || line.contains(",bbrare,"),
                        "no post-Big-Bang gear for a classic-area elite: " + line);
            }
        }
        assertEquals(castellan.size(), Files.readAllLines(Path.of("docs/economy/bb-drops/gap-rows.csv")).stream()
                .filter(l -> l.startsWith(CASTELLAN + ",")).count(), "gap-rows.csv lists the migration's rows");
        // the Lantern Erda: a normal Arcane mob, with the Arcane River Droplet Stone and no card (045 has it)
        List<BbDropTablesDataTest.Row> erda = byMob.get(LANTERN_ERDA);
        assertTrue(erda.stream().anyMatch(r -> r.item() == 4001878 && r.chance() == 10000));
        assertTrue(erda.stream().anyMatch(r -> r.item() == 0 && r.chance() == 400000));
    }

    @Test
    void itsOwnLedgerAndListedAfter054() throws Exception {
        String sql = Files.readString(SQL);
        String rb = Files.readString(ROLLBACK);
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS boss_drops_055_rows"));
        assertTrue(sql.contains("WHERE NOT EXISTS (SELECT 1 FROM drop_data d WHERE d.dropperid = n.dropperid AND d.itemid = n.itemid)"),
                "pairs already in drop_data are skipped");
        assertTrue(sql.contains("VALUES ('055-boss-drop-gaps')"));
        for (String line : rb.split("\n")) {
            if (line.startsWith("DELETE")) {
                assertTrue(line.contains("boss_drops_055_rows") || line.contains("'055-boss-drop-gaps'"), line);
            }
        }
        assertFalse(rb.contains("bb_drops_050_rows"), "055's rollback never touches 050's rows");
        String conf = Files.readString(Path.of("docs/release/1.6.1.conf"));
        assertTrue(conf.lines().anyMatch(l -> l.startsWith("MIGRATIONS=(")
                && l.contains("054-starter-chair-repair 055-boss-drop-gaps")), "055 listed right after 054");
    }

    @Test
    void everyItemIsARealTradeableNonCashNonQuestItem() throws Exception {
        WzTestData.assumePresent("Item.wz");
        WzTestData.assumePresent("Character.wz");
        WzTestData.assumePresent("Mob.wz");
        BbDropTablesDataTest.assertItemsReal(rows());
        BbDropTablesDataTest.assertEtcUseful(rows());
    }
}
