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
 * Owner 2026-10-29: migration 055 gives drop tables to the boss-fight mobs 050 missed because they sit outside its
 * regions, by 050's rules: one BOSS table per clear on the form that dies (Cygnus 8850012), ELITE tables for the other
 * fighting forms (her knights 8850006-8850009, Castellan 9400408 and Castellan Toad 9400409), the Arcane Lantern Erda
 * 8641008 as a normal mob; the phase/form stubs stay dropless. The checks 050's rows pass (BbDropTablesDataTest) hold.
 */
class BossDropGapsDataTest {

    static final Path SQL = Path.of("database/migrations/055-boss-drop-gaps.sql");
    static final Path ROLLBACK = Path.of("database/migrations/055-boss-drop-gaps.rollback.sql");
    static final Set<Integer> KNIGHTS = Set.of(8850006, 8850007, 8850008, 8850009);
    static final int CYGNUS = 8850012;
    static final int CASTELLAN = 9400408;
    static final int TOAD = 9400409;
    static final int LANTERN_ERDA = 8641008;
    static final List<Integer> STUBS = List.of(8850005, 8850011, 8880150, 8880302, 8880415, 8645009, 8641009);

    static List<BbDropTablesDataTest.Row> rows() throws Exception {
        return BbDropTablesDataTest.rows(SQL);
    }

    @Test
    void exactlyTheSixMobsGetTablesAndTheStubsNone() throws Exception {
        List<BbDropTablesDataTest.Row> rows = rows();
        Set<Integer> mobs = rows.stream().map(BbDropTablesDataTest.Row::mob).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(new TreeSet<>(List.of(8641008, 8850006, 8850007, 8850008, 8850009, 8850012, 9400408, 9400409)), mobs);
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
        // one boss table per clear: Cygnus' form that dies, Zakum-shaped (700000 meso row, Zakum's Elixir row), one rare
        // outclassing piece at 3000 (1000 for AbsoLab)
        List<BbDropTablesDataTest.Row> cygnus = byMob.get(CYGNUS);
        assertTrue(cygnus.stream().anyMatch(r -> r.item() == 0 && r.chance() == 700000), "boss meso row");
        assertTrue(cygnus.stream().anyMatch(r -> r.item() == 2000004 && r.chance() == 999999), "Zakum's Elixir row");
        assertEquals(1, cygnus.stream().filter(r -> r.item() / 1000000 == 1 && r.chance() <= 3000).count());
        assertEquals(30, cygnus.size());
        for (int knight : KNIGHTS) {
            List<BbDropTablesDataTest.Row> t = byMob.get(knight);
            // an elite: 4x a classic lv 188 meso midpoint at the normal 400000, no boss-tier rows (no 999999 potions,
            // no 22500 equips), the rare piece at twice the normal 60 (or 15)
            assertTrue(t.stream().anyMatch(r -> r.item() == 0 && r.chance() == 400000 && r.min() == 4813), "elite mesos " + knight);
            assertTrue(t.stream().noneMatch(r -> r.chance() == 999999 || (r.item() / 1000000 == 1 && r.chance() >= 22500)),
                    "no boss-tier rolls for " + knight);
            assertEquals(1, t.stream().filter(r -> r.item() / 1000000 == 1 && (r.chance() == 120 || r.chance() == 30)).count(),
                    "one rare piece at x2 for " + knight);
            assertEquals(32, t.size(), "elite table size for " + knight);
        }
        // Castellan: boss flag but under 1B HP, so an elite; Ninja Castle is classic, so no post-Big-Bang gear
        // Castellan Toad (the form that dies, the kill CastellanBattle counts) and Castellan, which also dies with drops
        // before it revives as the Toad: elite, classic pool only
        for (int mob : List.of(CASTELLAN, TOAD)) {
            List<BbDropTablesDataTest.Row> t = byMob.get(mob);
            assertTrue(t.stream().anyMatch(r -> r.item() == 0 && r.chance() == 400000 && r.min() == 4608),
                    "elite mesos: 4x the classic lv 180 midpoint, " + mob);
            for (String line : Files.readAllLines(Path.of("docs/economy/bb-drops/gap-rows.csv"))) {
                if (line.startsWith(mob + ",")) {
                    assertFalse(line.contains(",bbgear,") || line.contains(",bbrare,"),
                            "no post-Big-Bang gear for a classic-area elite: " + line);
                }
            }
            assertEquals(t.size(), Files.readAllLines(Path.of("docs/economy/bb-drops/gap-rows.csv")).stream()
                    .filter(l -> l.startsWith(mob + ",")).count(), "gap-rows.csv lists the migration's rows");
        }
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
