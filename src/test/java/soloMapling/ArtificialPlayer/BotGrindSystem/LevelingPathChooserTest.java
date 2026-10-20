package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Ticket 31: the path pick with the world faked, so each rule shows on its own: band and job, the gates, reach and
// fares, crowding, the trip budget, variety, and "nothing fits" handing back to the old choice.
class LevelingPathChooserTest {

    private static final int HENESYS = 100000000;
    private static final int LUDI = 220000000;
    private static final int LEAFRE = 240000000;

    private static final int HHG = 104040001;        // Victoria, mob 12
    private static final int SF3 = 100040003;        // Victoria, mob 15 (mages)
    private static final int PIG = 104010001;        // Victoria, mob 14 (warriors)
    private static final int TERRACE = 220000300;    // Ludibrium, mob 35
    private static final int TOY = 220010500;        // Ludibrium, mob 38
    private static final int LKC = 211060100;        // Lion King's Castle, rebirth 1, mob 120
    private static final int SKELE = 240040511;      // Leafre, mob 125
    private static final int BH = 310070100;         // Black Heaven, mob 200, no region hub

    private static final String YAML = """
            job_groups:
              warrior: [100-132]
              mage_fp: [200, 210-212]
            bands:
              - levels: [1, 30]
                groups:
                  general:
                    - {map: 104040001, w: 3}
                  warrior:
                    - {map: 104010001, w: 4}
                  mage_fp:
                    - {map: 100040003, w: 4}
              - levels: [30, 60]
                groups:
                  general:
                    - {map: 220000300, w: 3}
                    - {map: 220010500, w: 3, mode: party}
              - levels: [60, 255]
                groups:
                  general:
                    - {map: 211060100, w: 3}
                    - {map: 240040511, w: 3}
                    - {map: 310070100, w: 3}
            """;

    private static final LevelingPathChooser.Tuning TUNING =
            new LevelingPathChooser.Tuning(true, 0.75, 0.35, 15, 3.0, 0.0, 0.0, 0.5);

    private LevelingPath.Path path;
    private FakeWorld world;
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private LevelingPathChooser.Permits permits;

    @BeforeEach
    void setUp() throws Exception {
        path = LevelingPath.parse(new StringReader(YAML));
        world = new FakeWorld();
        permits = new LevelingPathChooser.Permits(clock::get);
        TrainingRegions.install(new TrainingRegions.Config(List.of(), new TrainingRegions.Tuning(0.05, 0.8, 2, 20)));
    }

    @AfterEach
    void restore() {
        TrainingRegions.install(null);
    }

    private static LevelingPathChooser.Request bot(int id, int level, int job, int rebirths, int meso, int home) {
        return new LevelingPathChooser.Request(id, level, rebirths, job, meso, home, Set.of(), 1);
    }

    private TrainingMap pick(LevelingPathChooser.Request r, double... rolls) {
        ArrayDeque<Double> q = new ArrayDeque<>();
        for (double d : rolls) {
            q.add(d);
        }
        return LevelingPathChooser.choose(path, TUNING, world, permits, r, () -> q.isEmpty() ? 0.5 : q.poll());
    }

    // Many draws with real randomness: map -> times picked.
    private Map<Integer, Integer> spread(int n, java.util.function.IntFunction<LevelingPathChooser.Request> bots) {
        java.util.Random rng = new java.util.Random(31);
        Map<Integer, Integer> out = new HashMap<>();
        for (int i = 0; i < n; i++) {
            TrainingMap m = LevelingPathChooser.choose(path, TUNING, world, new LevelingPathChooser.Permits(clock::get),
                    bots.apply(i), rng::nextDouble);
            if (m != null) {
                out.merge(m.mapId(), 1, Integer::sum);
            }
        }
        return out;
    }

    @Test
    void aMageAndAWarriorOfTheSameLevelPreferTheirOwnSpots() {
        Map<Integer, Integer> mages = spread(2000, i -> bot(i, 15, 210, 0, 1_000_000, HENESYS));
        Map<Integer, Integer> warriors = spread(2000, i -> bot(i, 15, 110, 0, 1_000_000, HENESYS));
        assertTrue(mages.getOrDefault(SF3, 0) > 3 * mages.getOrDefault(HHG, 0), mages.toString());
        assertFalse(mages.containsKey(PIG));
        assertTrue(warriors.getOrDefault(PIG, 0) > 3 * warriors.getOrDefault(HHG, 0), warriors.toString());
        assertFalse(warriors.containsKey(SF3));
        assertTrue(mages.getOrDefault(HHG, 0) > 0, "general spots stay in the mix");
    }

    @Test
    void aCohortSpreadsOverTheBandInsteadOfPilingOnOneMap() {
        Map<Integer, Integer> picks = spread(1000, i -> bot(i, 40, 300, 0, 1_000_000, LUDI));
        assertEquals(Set.of(TERRACE, TOY), picks.keySet());
        assertTrue(picks.get(TOY) > 150, picks.toString()); // the party spot still gets bots at half weight
    }

    @Test
    void theRebirthGatesHold() {
        world.reach.put(LEAFRE, Map.of(SKELE, 5));
        for (int i = 0; i < 200; i++) {
            TrainingMap m = LevelingPathChooser.choose(path, TUNING, world, permits, bot(i, 130, 110, 0, 10_000_000,
                    LEAFRE), new java.util.Random(i)::nextDouble);
            assertNotNull(m);
            assertFalse(m.mapId() == LKC, "a bot without a rebirth never picks Lion King's Castle");
        }
        world.reach.put(LEAFRE, Map.of());
        world.unreachable.addAll(List.of(SKELE, BH));
        TrainingMap reborn = pick(bot(1, 130, 110, 1, 10_000_000, LEAFRE), 0.0, 0.0);
        assertNotNull(reborn);
        assertEquals(LKC, reborn.mapId());
    }

    @Test
    void mapsTooFarAboveTheBotAreSkipped() {
        world.reach.put(LEAFRE, Map.of(SKELE, 5));
        Map<Integer, Integer> picks = spread(300, i -> bot(i, 100, 110, 3, 10_000_000, LEAFRE));
        assertTrue(picks.isEmpty(), "Skeles at 125 and BH at 200 are both >15 above a level-100 bot: " + picks);
    }

    @Test
    void aWalkableMapStaysHomeAndAMapAbroadMovesTheBotsHome() {
        world.reach.put(LUDI, Map.of(TERRACE, 2, TOY, 4));
        TrainingMap home = pick(bot(1, 35, 100, 0, 1_000_000, LUDI), 0.0, 0.0);
        assertFalse(home.relocates());
        assertEquals(2, home.hops());

        TrainingMap abroad = pick(bot(2, 35, 100, 0, 1_000_000, HENESYS), 0.0, 0.0);
        assertEquals(TERRACE, abroad.mapId());
        assertTrue(abroad.relocates());
        assertEquals(LUDI, abroad.relocateHomeMapId());
    }

    @Test
    void aBotUnderAHopsLevelCheckIsNeverSentThroughIt() {
        world.levelGate.put(TERRACE, 50);
        world.levelGate.put(TOY, 50);
        assertNull(pick(bot(1, 45, 100, 0, 1_000_000, HENESYS), 0.0, 0.0), "only Jeff's way, and the bot is 45");
        assertNotNull(pick(bot(2, 50, 100, 0, 1_000_000, HENESYS), 0.0, 0.0));
    }

    @Test
    void aBotThatCannotPayOrReachStaysOffThatMap() {
        world.fare = 50_000;
        assertNull(pick(bot(1, 35, 100, 0, 10_000, HENESYS), 0.0, 0.0), "can't pay the boat");
        world.fare = 0;
        world.unreachable.addAll(List.of(TERRACE, TOY));
        assertNull(pick(bot(1, 35, 100, 0, 10_000, HENESYS), 0.0, 0.0), "no route at all");
    }

    @Test
    void fullAndCooledDownMapsAreSkipped() {
        world.reach.put(LUDI, Map.of(TERRACE, 2, TOY, 4));
        world.bots.put(TERRACE, 6); // capacity 6
        for (int i = 0; i < 50; i++) {
            assertEquals(TOY, pick(bot(i, 40, 100, 0, 0, LUDI), 0.0, i / 50.0).mapId());
        }
        LevelingPathChooser.Request cooled = new LevelingPathChooser.Request(1, 40, 0, 100, 0, LUDI, Set.of(TOY), 1);
        assertNull(LevelingPathChooser.choose(path, TUNING, world, permits, cooled, () -> 0.0));
    }

    @Test
    void crowdedAndDrainedMapsGetFewerBots() {
        world.reach.put(LUDI, Map.of(TERRACE, 2, TOY, 4));
        Map<Integer, Integer> even = spread(2000, i -> bot(i, 40, 100, 0, 0, LUDI));
        world.bots.put(TERRACE, 4);
        world.saturation.put(TERRACE, 1.0);
        Map<Integer, Integer> skewed = spread(2000, i -> bot(i, 40, 100, 0, 0, LUDI));
        assertTrue(skewed.get(TERRACE) < even.get(TERRACE) / 3, even + " vs " + skewed);
    }

    @Test
    void tripsAbroadAreRationedWorldWide() {
        // relocations_per_minute = 2 (setUp): the third bot this minute stays home - nothing at home, so null.
        assertNotNull(pick(bot(1, 35, 100, 0, 1_000_000, HENESYS), 0.0, 0.0));
        assertNotNull(pick(bot(2, 35, 100, 0, 1_000_000, HENESYS), 0.0, 0.0));
        assertNull(pick(bot(3, 35, 100, 0, 1_000_000, HENESYS), 0.0, 0.0));
        clock.addAndGet(60_000);
        assertNotNull(pick(bot(4, 35, 100, 0, 1_000_000, HENESYS), 0.0, 0.0));
    }

    @Test
    void aMapWithoutARegionHubKeepsTheBotsHomeButStillGoes() {
        world.unreachable.addAll(List.of(SKELE, LKC));
        TrainingMap m = pick(bot(1, 205, 110, 0, 10_000_000, LEAFRE), 0.0, 0.0);
        assertEquals(BH, m.mapId());
        assertFalse(m.relocates());
    }

    @Test
    void nothingValidMeansNullSoTheOldChoiceRuns() {
        world.missing.addAll(List.of(TERRACE, TOY));
        assertNull(pick(bot(1, 40, 100, 0, 1_000_000, LUDI), 0.0, 0.0));
        assertNull(LevelingPathChooser.choose(LevelingPath.Path.EMPTY, TUNING, world, permits,
                bot(1, 40, 100, 0, 0, LUDI), () -> 0.0));
    }

    @Test
    void sometimesABotTrainsABandBelow() {
        LevelingPathChooser.Tuning below = new LevelingPathChooser.Tuning(true, 0.75, 0.35, 15, 3.0, 1.0, 0.0, 0.5);
        world.reach.put(HENESYS, Map.of(HHG, 3, SF3, 2, PIG, 2));
        TrainingMap m = LevelingPathChooser.choose(path, below, world, permits, bot(1, 40, 210, 0, 0, HENESYS),
                () -> 0.0);
        assertEquals(SF3, m.mapId());
    }

    @Test
    void favouritesAreStablePerBotAndVaryAcrossBots() {
        assertEquals(LevelingPathChooser.favourite(7, TERRACE), LevelingPathChooser.favourite(7, TOY), 1e-12);
        Set<Long> distinct = new HashSet<>();
        for (int bot = 0; bot < 50; bot++) {
            double f = LevelingPathChooser.favourite(bot, TERRACE);
            assertTrue(f >= 0.5 && f <= 2.0);
            distinct.add(Math.round(f * 100));
        }
        assertTrue(distinct.size() > 20);
    }

    @Test
    void theFollowChanceOnlyAppliesWhileHomeStillFits() {
        LevelingPath.install(path);
        LevelingPathChooser.setTuning(TUNING);
        try {
            assertTrue(LevelingPathChooser.wantsPath(false, 0.99));
            assertTrue(LevelingPathChooser.wantsPath(true, 0.5));
            assertFalse(LevelingPathChooser.wantsPath(true, 0.9));
            LevelingPathChooser.setTuning(new LevelingPathChooser.Tuning(false, 1, 0, 15, 3, 0, 0, 0.5));
            assertFalse(LevelingPathChooser.wantsPath(false, 0.0));
        } finally {
            LevelingPathChooser.setTuning(null);
            LevelingPath.install(null);
        }
    }

    @Test
    void theSweepCountsUsableAndReachableMapsPerRebirth() {
        world.unreachable.add(BH);
        List<String> lines = LevelingPathChooser.sweep(path, world, HENESYS);
        assertTrue(lines.get(2).contains("r0=1 r1=2 r2=2 r3=2"), lines.get(2));
        assertTrue(lines.get(lines.size() - 1).contains("[" + BH + "]"), lines.toString());
    }

    @Test
    void aMapLivesInItsOwnRegionsHubEvenWhenAnotherHubWalksThereShorter() {
        int elNath = 211000000;
        int aqua = 230000000;
        int iceValley = 211040200;
        List<TrainingRegions.Region> regions = List.of(
                new TrainingRegions.Region("El Nath", List.of(new TrainingRegions.Window(211000000, 212000000)),
                        List.of(), List.of(elNath), 45, 255, 1.0, true),
                new TrainingRegions.Region("Aqua Road", List.of(new TrainingRegions.Window(230000000, 231000000)),
                        List.of(), List.of(aqua), 40, 255, 1.0, true));
        Map<Integer, Map<Integer, Integer>> walks = Map.of(elNath, Map.of(iceValley, 6), aqua, Map.of(iceValley, 3,
                BH, 9));
        java.util.function.IntFunction<Map<Integer, Integer>> walk = h -> walks.getOrDefault(h, Map.of());
        assertEquals(elNath, LevelingPathChooser.homeFor(iceValley, regions, walk, m -> elNath, m -> -1));
        // No region holds Black Heaven: its WZ return town wins, else the nearest hub, else nowhere.
        assertEquals(310000000, LevelingPathChooser.homeFor(BH, regions, walk, m -> 310000000, m -> -1));
        assertEquals(aqua, LevelingPathChooser.homeFor(BH, regions, walk, m -> 999999999, m -> -1));
        assertEquals(-1, LevelingPathChooser.homeFor(BH, regions, h -> Map.of(), m -> 999999999, m -> -1));
    }

    // Mob levels from the constants' comments; capacity 6 everywhere; Ludibrium maps belong to the Ludi hub.
    private static final class FakeWorld implements LevelingPathChooser.World {
        final Map<Integer, Integer> levels = Map.of(HHG, 12, SF3, 15, PIG, 14, TERRACE, 35, TOY, 38, LKC, 120,
                SKELE, 125, BH, 200);
        final Map<Integer, Map<Integer, Integer>> reach = new HashMap<>();
        final Map<Integer, Integer> bots = new HashMap<>();
        final Map<Integer, Double> saturation = new HashMap<>();
        final Set<Integer> unreachable = new HashSet<>();
        final Map<Integer, Integer> levelGate = new HashMap<>();
        final Set<Integer> missing = new HashSet<>();
        int fare = 1_000;

        @Override
        public int mobLevel(int mapId) {
            return missing.contains(mapId) ? -1 : levels.getOrDefault(mapId, -1);
        }

        @Override
        public int mobCount(int mapId) {
            return mobLevel(mapId) > 0 ? 20 : 0;
        }

        @Override
        public boolean isDungeon(int mapId) {
            return false;
        }

        @Override
        public boolean regionDisabled(int mapId) {
            return false;
        }

        @Override
        public Map<Integer, Integer> walkReach(int homeMapId) {
            return reach.getOrDefault(homeMapId, Map.of());
        }

        @Override
        public int routeFare(int fromMapId, int toMapId, int rebirths, int jobId, int level) {
            if (level < levelGate.getOrDefault(toMapId, 0)) {
                return -1; // the only way there passes a script's level check (Jeff, 50)
            }
            return unreachable.contains(toMapId) ? -1 : fare;
        }

        @Override
        public int capacity(int mapId) {
            return 6;
        }

        @Override
        public int botsOn(int mapId) {
            return bots.getOrDefault(mapId, 0);
        }

        @Override
        public double yieldSaturation(int mapId) {
            return saturation.getOrDefault(mapId, 0.0);
        }

        @Override
        public int homeFor(int mapId) {
            if (mapId / 1_000_000 == 220) {
                return LUDI;
            }
            if (mapId / 1_000_000 == 240) {
                return LEAFRE;
            }
            return -1;
        }
    }
}
