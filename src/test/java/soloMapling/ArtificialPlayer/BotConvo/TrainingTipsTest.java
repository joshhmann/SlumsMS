package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotGrindSystem.LevelingPath;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0 tune: TRAINING threads name the training bots' real spots (LevelingPath.yaml) and a tip can be followed. */
class TrainingTipsTest {

    @AfterEach
    void reset() {
        TrainingTips.clearForTest();
    }

    @Test
    void spotsComeFromTheLevelingPathForTheLevel() {
        for (int level : new int[]{8, 17, 25, 32, 46, 63, 90}) {
            List<TrainingTips.Spot> spots = TrainingTips.spotsFor(level, 0, 3);
            assertFalse(spots.isEmpty(), "level " + level);
            assertTrue(spots.size() <= 3);
            for (TrainingTips.Spot s : spots) {
                assertTrue(TrainingTips.realSpot(s.mapId(), level, 0), level + " " + s);
                assertFalse(s.name().contains(":") || s.name().contains("<"), s.name());
            }
        }
        List<String> names = TrainingTips.spotsFor(25, 0, 10).stream().map(TrainingTips.Spot::name).toList();
        assertEquals(names.size(), names.stream().distinct().count(), "one spot per place: " + names);
        assertFalse(TrainingTips.realSpot(105050200, 63, 0), "Ant Tunnel is no level 63 map");
    }

    @Test
    void pathNamesBecomeWhatPlayersSay() {
        assertEquals("Ant Tunnel", TrainingTips.spotOf(new LevelingPath.Entry(105050200, 1, false, "Dungeon: Ant Tunnel III")).name());
        assertEquals("Kerning subway", TrainingTips.spotOf(new LevelingPath.Entry(103000101, 1, false,
                "Kerning City Subway: Line 1 <Area 1>")).name());
        TrainingTips.Spot terrace = TrainingTips.spotOf(new LevelingPath.Entry(220010500, 1, false, "Ludibrium: Terrace Hall"));
        assertEquals("Terrace Hall (Ludibrium)", terrace.described());
        assertEquals("Pig Beach", TrainingTips.spotOf(new LevelingPath.Entry(104010001, 1, false, "Hidden Street: The Pig Beach")).name());
    }

    @Test
    void theSuggestedSpotIsTheOneTheOtherNames() {
        List<TrainingTips.Spot> spots = List.of(new TrainingTips.Spot(105050200, "Ant Tunnel", null),
                new TrainingTips.Spot(101030001, "Land of Wild Boar", null));
        List<String[]> lines = List.of(new String[]{"Kimchi", "any good spot for lvl 25? not ant tunnel"},
                new String[]{"Bob", "go wild boar land, good exp"});
        assertEquals(101030001, TrainingTips.suggested(lines, "Kimchi", spots).mapId());
        assertEquals(105050200, TrainingTips.suggested(List.<String[]>of(new String[]{"Bob", "try the ant tunnel tbh"}), "Kimchi",
                spots).mapId());
        assertNull(TrainingTips.suggested(List.<String[]>of(new String[]{"Bob", "go kerning subway"}), "Kimchi", spots));
    }

    @Test
    void aTipIsTakenOnceAndLapses() {
        TrainingTips.offer(7, 105050200, 1000);
        assertEquals(105050200, TrainingTips.pending(7));
        assertEquals(105050200, TrainingTips.take(7, 2000));
        assertEquals(-1, TrainingTips.take(7, 3000), "taken once");
        TrainingTips.offer(8, 105050200, 1000);
        assertEquals(-1, TrainingTips.take(8, 1000 + TrainingTips.TIP_MS + 1), "lapsed");
    }

    @Test
    void wrongPlacesFromTheTrace() {
        assertFalse(TrainingTips.wrongPlaces("watch teh fire boars lolz", 63).isEmpty());
        assertTrue(TrainingTips.wrongPlaces("watch teh fire boars lolz", 30).isEmpty());
        assertEquals(List.of("kpq is in kerning, not ludibrium"), TrainingTips.wrongPlaces("go kpq in ludibrium tbh", 25));
        assertFalse(TrainingTips.wrongPlaces("go kerning subway, wild boars r good xp", 30).isEmpty());
        assertTrue(TrainingTips.wrongPlaces("i like to study the ant tunnel map", 25).isEmpty());
        assertTrue(TrainingTips.wrongPlaces("lol ya", 25).isEmpty());
        assertTrue(TrainingTips.wrongPlaces("forgotten path of time is decent", 63).isEmpty(), "not the Path of Time");
    }
}
