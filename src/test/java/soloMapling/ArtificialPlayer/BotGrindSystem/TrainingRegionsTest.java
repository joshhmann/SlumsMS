package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.FileReader;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Ticket 03: the region table moved to TrainingRegions.yaml. The original four windows must be unchanged, the
// widening must land where the research says bots train, and the rebirth gates still close LKC.
class TrainingRegionsTest {

    private static final String YAML = "src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/TrainingRegions.yaml";

    @AfterEach
    void restore() {
        TrainingRegions.install(null);
    }

    private static void useShippedTable() throws Exception {
        try (FileReader in = new FileReader(YAML)) {
            TrainingRegions.install(TrainingRegions.parse(in));
        }
    }

    @Test
    void theOriginalFourWindowsAreUnchanged() throws Exception {
        useShippedTable();
        for (TrainingRegions.Region original : TrainingRegions.FALLBACK.regions()) {
            TrainingRegions.Window w = original.windows().get(0);
            TrainingRegions.Region shipped = TrainingRegions.regionOf(w.min());
            assertEquals(original.name(), shipped.name());
            assertEquals(w, shipped.windows().get(0));
        }
    }

    @Test
    void theWideningAdmitsTheNewContinents() throws Exception {
        useShippedTable();

        assertTrue(TrainingRegions.isAllowed(230040000, 0)); // Aqua Road: Deep Sea Gorge
        assertTrue(TrainingRegions.isAllowed(240010200, 0)); // Leafre: Minar Forest
        assertTrue(TrainingRegions.isAllowed(250010000, 0)); // Mu Lung
        assertTrue(TrainingRegions.isAllowed(261020000, 0)); // Magatia
        assertTrue(TrainingRegions.isAllowed(541010010, 0)); // Ghost Ship
        assertTrue(TrainingRegions.isAllowed(600020000, 0)); // Masteria
        assertTrue(TrainingRegions.isAllowed(801040000, 0)); // Showa
        assertFalse(TrainingRegions.isAllowed(240060000, 0), "Horntail's cave is excluded");
        assertTrue(TrainingRegions.isAllowed(130010000, 0), "Ereve is open since its version gate was lifted");
        assertTrue(TrainingRegions.isAllowed(270010100, 0), "so is the Temple of Time");
        assertFalse(TrainingRegions.isAllowed(401010000, 3), "upper Grandis is not a region");
    }

    @Test
    void onlyVonLeonsWingNeedsARebirthInsideElNath() throws Exception {
        useShippedTable();

        // 1.6.1 b040: Lion King's Castle is open training; Von Leon's wing keeps rebirth 1
        assertTrue(TrainingRegions.isAllowed(211060100, 0));
        assertFalse(TrainingRegions.isAllowed(211070100, 0));
        assertTrue(TrainingRegions.isAllowed(211070100, 1));
        assertTrue(TrainingRegions.isAllowed(211040100, 0));
    }

    /** 1.6.1 b041: bots of 120+ relocate to lower Grandis and the Future, as the notes say; level 119 stays out. */
    @Test
    void lowerGrandisAndTheFutureAreRegionsFromLevel120() throws Exception {
        useShippedTable();

        TrainingRegions.Region grandis = TrainingRegions.regionOf(400010300);
        TrainingRegions.Region future = TrainingRegions.regionOf(271010200);
        assertEquals("Lower Grandis", grandis.name());
        assertEquals("The Future", future.name());
        for (TrainingRegions.Region r : List.of(grandis, future)) {
            assertTrue(r.attracts(120), r.name());
            assertFalse(r.attracts(119), r.name());
        }
        assertTrue(TrainingRegions.isAllowed(400010300, 0, 120));
        assertFalse(TrainingRegions.isAllowed(400010300, 0, 119));
        assertTrue(TrainingRegions.isAllowed(271010200, 0, 120));
        assertFalse(TrainingRegions.isAllowed(402000124, 0, 200), "the Scrapyard is deeper Grandis: 1 rebirth");
        assertTrue(TrainingRegions.isAllowed(402000124, 1, 1), "open at the 1st rebirth (owner 2026-10-29)");
        assertFalse(TrainingRegions.isAllowed(410000002, 0, 200), "so does the Fox Tree");
        assertNull(TrainingRegions.regionOf(401000000), "Heliseum is no region");
        assertTrue(future.hubs().contains(272000000), "Crack in Time, for Leafre of the Past");
        assertTrue(TrainingRegions.isAllowed(272000100, 0, 120));
        assertFalse(TrainingRegions.isAllowed(272000100, 0, 119));
        for (int hub : grandis.hubs()) {
            assertTrue(server.maps.AreaGates.isLowerGrandis(hub), "hub " + hub);
        }
    }

    @Test
    void hubsAndLevelsComeFromTheFile() throws Exception {
        useShippedTable();
        TrainingRegions.Region leafre = TrainingRegions.regionOf(240000000);

        assertEquals("Leafre", leafre.name());
        assertTrue(leafre.hubs().contains(240000000));
        assertFalse(leafre.attracts(69));
        assertTrue(leafre.attracts(70));
        assertEquals(0.80, TrainingRegions.tuning().outgrownChance(), 1e-9);
    }

    @Test
    void anUnreadableOrEmptyFileFallsBackToTheOriginalFour() throws Exception {
        TrainingRegions.Config empty = TrainingRegions.parse(new StringReader("regions: []\n"));
        assertTrue(empty.regions().isEmpty());

        TrainingRegions.install(TrainingRegions.FALLBACK);
        assertTrue(TrainingRegions.isAllowed(100020000));
        assertFalse(TrainingRegions.isAllowed(240010200));
        assertNull(TrainingRegions.regionOf(240010200));
    }
}
