package soloMapling.ArtificialPlayer.BotGrindSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotGrindSystem.GearFirstPlanner.Candidate;
import soloMapling.ArtificialPlayer.BotGrindSystem.GearFirstPlanner.GearProspect;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Ticket 162 part 2: gear first, EXP the tiebreaker (port of nutnnut's BotGrindPlanner) and its wiring.
class GearFirstTest {

    @AfterEach
    void reset() {
        System.clearProperty("slums.grind.gearFirst");
        GearFirstAdvisor.useForTest(null);
    }

    private static Candidate map(int id, double exp, List<GearProspect> drops) {
        return new Candidate(id, 4.0, exp, 8, 0, drops);
    }

    @Test
    void withNoUpgradeInReachTheBestExpWins() {
        GearFirstPlanner.Scores s = GearFirstPlanner.score(List.of(map(1, 100, List.of()), map(2, 50, List.of())));
        assertEquals(0, s.needGear(), 1e-9);
        assertTrue(s.unified()[0] > s.unified()[1]);
        assertTrue(!s.gearFirst());
    }

    @Test
    void aDominantAttainableUpgradeLeadsOverExp() {
        // Map 2 has half the EXP but drops a +30% DPS piece at 1 in 200 per kill (well within two hours).
        GearProspect big = new GearProspect(1302010, 0.005, 0.30);
        GearFirstPlanner.Scores s = GearFirstPlanner.score(List.of(map(1, 100, List.of()), map(2, 50, List.of(big))));
        assertEquals(1.0, s.needGear(), 1e-9);
        assertTrue(s.unified()[1] > s.unified()[0]);
        assertEquals(1302010, s.wanted().itemId());
        assertTrue(s.gearFirst());
    }

    @Test
    void aJackpotNobodyCanExpectBarelyMoves() {
        GearProspect jackpot = new GearProspect(1302011, 1e-7, 0.5);
        GearFirstPlanner.Scores s = GearFirstPlanner.score(List.of(map(1, 100, List.of()),
                map(2, 50, List.of(jackpot))));
        assertTrue(s.needGear() < 0.01);
        assertTrue(s.unified()[0] > s.unified()[1], "EXP still leads");
    }

    @Test
    void killRateIsCappedByWhatTheMapRespawns() {
        Candidate fast = new Candidate(1, 0.1, 10, 1, 1000, List.of());   // a tiny packed map, one spawn
        assertEquals(3600.0 / GearFirstPlanner.RESPAWN_PERIOD_SECONDS, GearFirstPlanner.killsPerHour(fast), 1e-9);
    }

    @Test
    void farmScoresAreExpectedCopiesAnHour() {
        GearProspect drop = new GearProspect(1302010, 0.01, 0);
        double[] f = GearFirstPlanner.farmScores(List.of(map(1, 100, List.of()), map(2, 10, List.of(drop))), 1302010);
        assertEquals(0, f[0], 1e-9);
        assertTrue(f[1] > 0);
    }

    // ---- the advisor over stub game data ----

    private static final class Stub implements GearFirstAdvisor.Sources {
        @Override public double[] pace(Character chr, int mapId) { return new double[]{4.0, mapId == 100 ? 100 : 50}; }
        @Override public List<Integer> spawns(int mapId) { return List.of(9000 + mapId, 9000 + mapId); }
        @Override public Map<Integer, Double> equipDrops(int mobId) {
            return mobId == 9200 ? Map.of(1302010, 0.005) : Map.of();
        }
        @Override public double gain(Character chr, int itemId) { return itemId == 1302010 ? 0.3 : 0; }
        @Override public String name(int itemId) { return "Sword"; }
        private final Map<Integer, Double> saturation = new java.util.HashMap<>();
        @Override public double saturation(int mapId) { return saturation.getOrDefault(mapId, 0.0); }
    }

    private static Character bot() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(1_000_000_000 + 1621);
        return chr;
    }

    @Test
    void offByDefaultTheChoiceIsUntouched() {
        GearFirstAdvisor.useForTest(new Stub());
        List<TrainingMap> maps = List.of(new TrainingMap(100, 20, 1), new TrainingMap(200, 20, 1));
        assertNull(GearFirstAdvisor.advise(bot(), maps));
        assertNull(GearFirstAdvisor.farmPick(bot(), 1302010, maps));
    }

    @Test
    void switchedOnTheDropMapWeighsMoreAndBecomesTheWant() {
        System.setProperty("slums.grind.gearFirst", "true");
        GearFirstAdvisor.useForTest(new Stub());
        Character chr = bot();
        List<TrainingMap> maps = List.of(new TrainingMap(100, 20, 1), new TrainingMap(200, 20, 1));
        GearFirstAdvisor.Advice advice = GearFirstAdvisor.advise(chr, maps);
        assertNotNull(advice);
        assertTrue(advice.weights()[1] > advice.weights()[0], java.util.Arrays.toString(advice.weights()));
        assertEquals(1302010, GearFirstAdvisor.want(chr.getId()).itemId());
        assertEquals(200, GearFirstAdvisor.farmPick(chr, 1302010, maps).mapId());
    }

    // 1.6.2 bot audit (G7): the gear-first choice applies the same crowd rule as the EXP-first one - a map whose yield
    // the bots there already spend weighs less, and a farm target on a spent map is refused.
    @Test
    void aSpentMapWeighsLikeTheExpFirstChoiceWeighsIt() {
        System.setProperty("slums.grind.gearFirst", "true");
        List<TrainingMap> maps = List.of(new TrainingMap(100, 20, 1), new TrainingMap(200, 20, 1));

        GearFirstAdvisor.useForTest(new Stub());
        double[] quiet = GearFirstAdvisor.advise(bot(), maps).weights();

        Stub crowded = new Stub();
        crowded.saturation.put(100, 1.0);
        GearFirstAdvisor.useForTest(crowded);
        double[] spent = GearFirstAdvisor.advise(bot(), maps).weights();

        assertEquals(quiet[0] * 0.25, spent[0], 1e-9, "a map the bots there have drained weighs a quarter");
        assertEquals(quiet[1], spent[1], 1e-9, "a quiet map is untouched");
    }

    @Test
    void aFarmTargetOnASpentMapIsRefused() {
        System.setProperty("slums.grind.gearFirst", "true");
        List<TrainingMap> maps = List.of(new TrainingMap(100, 20, 1), new TrainingMap(200, 20, 1));
        Stub stub = new Stub();
        stub.saturation.put(200, 1.0);   // the only map that drops the wanted sword is drained
        GearFirstAdvisor.useForTest(stub);
        assertNull(GearFirstAdvisor.farmPick(bot(), 1302010, maps), "no farm trip to a map that yields nothing more");

        stub.saturation.clear();
        assertEquals(200, GearFirstAdvisor.farmPick(bot(), 1302010, maps).mapId(), "a quiet map is still a farm target");
    }
}
