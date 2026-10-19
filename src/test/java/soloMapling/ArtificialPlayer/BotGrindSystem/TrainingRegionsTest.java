package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.FileReader;
import java.io.StringReader;

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
        assertFalse(TrainingRegions.isAllowed(130010000, 0), "Ereve stays closed");
        assertFalse(TrainingRegions.isAllowed(400010100, 3), "Grandis is not a region");
    }

    @Test
    void lionKingsCastleNeedsARebirthEvenInsideElNath() throws Exception {
        useShippedTable();

        assertFalse(TrainingRegions.isAllowed(211060100, 0));
        assertTrue(TrainingRegions.isAllowed(211060100, 1));
        assertTrue(TrainingRegions.isAllowed(211040100, 0));
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
