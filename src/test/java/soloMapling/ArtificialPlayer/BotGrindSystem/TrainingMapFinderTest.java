package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The finder's selection once the WZ walk has admitted its candidates: in-band maps win, and when nothing is
// in-band a bot of any level still gets somewhere to grind, the map it stands on included. The deep-Ludibrium
// numbers are the real ones reachable from Forbidden Time (220070301, mob level 99).
class TrainingMapFinderTest {

    private static final TrainingMap FORBIDDEN_TIME = new TrainingMap(220070301, 99, 0);
    private static final List<TrainingMap> DEEP_LUDI = List.of(
            new TrainingMap(220070300, 95, 1),
            new TrainingMap(220070400, 108, 2),
            new TrainingMap(220060400, 108, 4),
            new TrainingMap(220060301, 98, 6),
            new TrainingMap(220070000, 63, 4));

    private static List<Integer> ids(List<TrainingMap> maps) {
        return maps.stream().map(TrainingMap::mapId).sorted().toList();
    }

    @Test
    void inBandMapsAreReturnedWhenThereAreAny() {
        List<TrainingMap> picked = TrainingMapFinder.select(DEEP_LUDI, FORBIDDEN_TIME, false, 125, 100, 137, 1);

        assertEquals(List.of(220060400, 220070400), ids(picked));
    }

    @Test
    void aDeepHubProAboveEveryMapStillGetsTheHardestOnes() {
        // The Path of Time Hub cohort at 125: floor 115 clears no allowed map, which used to leave the
        // fallback empty and the bot idling "in town" while standing in Forbidden Time's 48 spawns.
        List<TrainingMap> picked = TrainingMapFinder.select(DEEP_LUDI, FORBIDDEN_TIME, false, 125, 115, 137, 115);

        assertEquals(List.of(220060400, 220070301, 220070400), ids(picked));
    }

    @Test
    void theFallbackStillPrefersMapsAtOrAboveTheHardFloor() {
        List<TrainingMap> picked = TrainingMapFinder.select(DEEP_LUDI, FORBIDDEN_TIME, false, 122, 112, 134, 105);

        assertEquals(List.of(220060400, 220070400), ids(picked));
    }

    @Test
    void theCurrentMapIsAFallbackEvenWithoutIncludeOrigin() {
        List<TrainingMap> picked = TrainingMapFinder.select(List.of(), FORBIDDEN_TIME, false, 125, 100, 137, 1);

        assertEquals(List.of(220070301), ids(picked));
    }

    @Test
    void theCurrentMapIsOnlyInBandWithIncludeOrigin() {
        List<TrainingMap> without = TrainingMapFinder.select(DEEP_LUDI, FORBIDDEN_TIME, false, 99, 74, 111, 1);
        List<TrainingMap> with = TrainingMapFinder.select(DEEP_LUDI, FORBIDDEN_TIME, true, 99, 74, 111, 1);

        assertFalse(ids(without).contains(220070301));
        assertTrue(ids(with).contains(220070301));
    }

    @Test
    void aRebirthLevelBotGetsTheClosestLevelMaps() {
        List<TrainingMap> picked = TrainingMapFinder.select(DEEP_LUDI, null, false, 255, 230, 267, 1);

        assertEquals(List.of(220060301, 220060400, 220070400), ids(picked));
    }

    @Test
    void nothingAdmittedMeansNothing() {
        assertTrue(TrainingMapFinder.select(List.of(), null, false, 125, 100, 137, 1).isEmpty());
    }
}
