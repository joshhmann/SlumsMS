package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates;

import java.io.FileReader;
import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Ticket 31: the shipped LevelingPath.yaml reads as the research drafted it (15 bands, 509 entries less the 5 the
// WZ check disabled), job ids land in the right groups, and a band hands a job its own spots plus the general ones.
class LevelingPathTest {

    private static LevelingPath.Path shipped() throws Exception {
        try (FileReader in = new FileReader(LevelingPath.YAML_PATH)) {
            return LevelingPath.parse(in);
        }
    }

    @AfterEach
    void restore() {
        LevelingPath.install(null);
    }

    @Test
    void theShippedPathHasEveryBandAndTheValidatedEntries() throws Exception {
        LevelingPath.Path p = shipped();
        assertEquals(15, p.bands().size());
        // 509 drafted, 5 disabled as # INVALID, 43 as # UNREACHABLE (no player can get there), 28 reachable added.
        assertEquals(489, p.entryCount());
        assertEquals(1, p.bands().get(0).lo());
        assertEquals(255, p.bands().get(p.bands().size() - 1).hi());
    }

    @Test
    void everyLevelFrom1To255HasABandWithGeneralSpots() throws Exception {
        LevelingPath.Path p = shipped();
        for (int level = 1; level <= 255; level++) {
            int band = p.bandIndex(level);
            assertTrue(band >= 0, "no band at level " + level);
            assertFalse(p.bands().get(band).group(LevelingPath.GENERAL).isEmpty(), "no general spots at " + level);
        }
        assertEquals(1, p.bandIndex(10));  // [1, 10) then [10, 21)
        assertEquals(-1, p.bandIndex(0));
    }

    @Test
    void theDisabledEntriesAreGone() throws Exception {
        Set<Integer> maps = new HashSet<>();
        for (LevelingPath.Band b : shipped().bands()) {
            b.groups().values().forEach(es -> es.forEach(e -> maps.add(e.mapId())));
        }
        for (int never : List.of(271030102, 271030320, 271030530, 271030600, 541020620,
                // UNREACHABLE: Mirror World, Kritias, Umbra, LKC past Walls 1, ToT lanes, Ninja Castle, MC, Aran Rien
                323030000, 329010000, 307000310, 211060300, 270030100, 800040201, 106020100, 140010200)) {
            assertFalse(maps.contains(never), "map " + never + " should be disabled");
        }
        for (int map : maps) {
            assertNotEquals(BotMapGates.NEVER, BotMapGates.requiredRebirths(map), "never-enter map " + map);
        }
    }

    @Test
    void jobIdsMapToTheirGroups() throws Exception {
        LevelingPath.Path p = shipped();
        assertEquals("warrior", p.groupOf(112));
        assertEquals("mage_fp", p.groupOf(200));
        assertEquals("mage_il", p.groupOf(221));
        assertEquals("mage_cleric", p.groupOf(232));
        assertEquals("archer", p.groupOf(321));
        assertEquals("thief", p.groupOf(412));
        assertEquals("pirate_brawler", p.groupOf(512));
        assertEquals("pirate_gunslinger", p.groupOf(522));
        assertEquals("aran", p.groupOf(2112));
        assertEquals("warrior", p.groupOf(1110)); // Dawn Warrior inherits the warrior route
        assertEquals(LevelingPath.GENERAL, p.groupOf(0));
        assertEquals(LevelingPath.GENERAL, p.groupOf(900)); // GM / unknown
    }

    @Test
    void aJobGetsItsOwnSpotsAtFullWeightAndGeneralOnesMixedIn() throws Exception {
        LevelingPath.Path p = shipped();
        int band = p.bandIndex(15);
        List<LevelingPath.Entry> mage = p.candidates(band, 210, 0.35);
        LevelingPath.Entry southernForest3 = find(mage, 100040003);
        assertEquals(4.0, southernForest3.weight(), 1e-9);
        LevelingPath.Entry hhg2 = find(mage, 104040001); // general only
        assertEquals(4.0 * 0.35, hhg2.weight(), 1e-9);
        // Pig Beach is in both the warrior and general lists: the warrior keeps its own (larger) weight.
        assertEquals(4.0, find(p.candidates(band, 110, 0.35), 104010001).weight(), 1e-9);
        // No mixing: only the job's own list.
        assertEquals(p.bands().get(band).group("mage_fp").size(), p.candidates(band, 210, 0).size());
    }

    @Test
    void aGroupWithNothingInTheBandUsesTheGeneralList() throws Exception {
        LevelingPath.Path p = shipped();
        int band = p.bandIndex(150);
        assertEquals(p.bands().get(band).group(LevelingPath.GENERAL), p.candidates(band, 412, 0.35));
    }

    @Test
    void partySpotsAreMarked() throws Exception {
        long party = shipped().bands().stream().flatMap(b -> b.groups().values().stream())
                .flatMap(List::stream).filter(LevelingPath.Entry::party).count();
        assertEquals(16, party); // mode: both stays at full weight
    }

    @Test
    void anUnreadableFileLeavesThePathEmpty() throws Exception {
        assertTrue(LevelingPath.parse(new StringReader("just text")).isEmpty());
        LevelingPath.Path p = LevelingPath.parse(new StringReader("""
                bands:
                  - levels: [30, 20]
                    groups: {general: [{map: 1, w: 1}]}
                  - levels: [1, 255]
                    groups:
                      general:
                        - {map: 104040000, w: 0}
                        - {map: 104040001, w: 2, mode: party}
                """));
        assertEquals(1, p.bands().size());
        assertEquals(1, p.entryCount());
        assertTrue(p.bands().get(0).group(LevelingPath.GENERAL).get(0).party());
    }

    private static LevelingPath.Entry find(List<LevelingPath.Entry> es, int map) {
        return es.stream().filter(e -> e.mapId() == map).findFirst().orElseThrow();
    }
}
