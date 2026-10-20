package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NavEdgeLearningTest {

    @TempDir
    Path dir;

    @BeforeEach
    void scratch() {
        NavEdgeLearning.useDirForTest(dir);
    }

    @AfterEach
    void real() {
        NavEdgeLearning.useDirForTest(null);
    }

    private static BotNavigationGraph.Edge jump(int from, int to, int cost) {
        return new BotNavigationGraph.Edge(from, to, BotNavigationGraph.EdgeType.JUMP, new Point(from * 100, 50),
                new Point(to * 100, 50), from * 100, from * 100, 0, 0, 0, 0, 0, cost);
    }

    /** Three rope regions side by side: 0 -> 2 directly (cheapest), or 0 -> 1 -> 2. */
    private static BotNavigationGraph threeRopes(int version, BotNavigationGraph.Edge direct,
                                                 BotNavigationGraph.Edge first, BotNavigationGraph.Edge second) {
        List<BotNavigationGraph.Region> regions = List.of(new BotNavigationGraph.Region(0, 0, 0, 100, false),
                new BotNavigationGraph.Region(1, 100, 0, 100, false), new BotNavigationGraph.Region(2, 200, 0, 100, false));
        Map<Integer, BotNavigationGraph.Region> byId = new HashMap<>();
        regions.forEach(r -> byId.put(r.id, r));
        Map<Integer, List<BotNavigationGraph.Edge>> out = new HashMap<>();
        out.put(0, List.of(direct, first));
        out.put(1, List.of(second));
        out.put(2, List.of());
        return new BotNavigationGraph(990000000, version, BotMovementProfile.base(), regions, byId, Map.of(), out, Set.of());
    }

    @Test
    void anEdgeThatKeepsFailingCostsMoreAndOneThatAlmostAlwaysFailsCostsMuchMore() {
        assertEquals(1.0, NavEdgeLearning.factorFor(0, 10));
        assertEquals(1.0, NavEdgeLearning.factorFor(0.2, 0), "a fraction of one old failure is nothing");
        double mixed = NavEdgeLearning.factorFor(2, 2);
        assertTrue(mixed > 1.5 && mixed < 3.0, "half failing: " + mixed);
        assertEquals(NavEdgeLearning.BAD_FACTOR, NavEdgeLearning.factorFor(6, 1));
        assertTrue(NavEdgeLearning.factorFor(1, 0) < NavEdgeLearning.factorFor(3, 0), "confidence grows with evidence");
    }

    @Test
    void failuresFadeWithTime() {
        BotNavigationGraph.Edge direct = jump(0, 2, 100);
        BotNavigationGraph graph = threeRopes(61, direct, jump(0, 1, 60), jump(1, 2, 60));
        long longAgo = System.currentTimeMillis() - 4 * NavEdgeLearning.HALF_LIFE_MS;
        for (int i = 0; i < 6; i++) {
            NavEdgeLearning.record(graph, direct, true, longAgo);
        }
        // Six failures, four half-lives ago, are worth less than one today.
        assertTrue(NavEdgeLearning.factor(graph, direct) < 1.5, "faded: " + NavEdgeLearning.factor(graph, direct));
    }

    @Test
    void thePlannerRoutesAroundAJumpThatKeepsFailing() {
        BotNavigationGraph.Edge direct = jump(0, 2, 100);
        BotNavigationGraph.Edge first = jump(0, 1, 60);
        BotNavigationGraph.Edge second = jump(1, 2, 60);
        BotNavigationGraph graph = threeRopes(61, direct, first, second);
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(990000000);

        List<BotNavigationGraph.Edge> before = BotNavigationManager.runSearch(graph, map, new Point(0, 50), 0, 2,
                new Point(200, 50), null, true, false, 0L).path();
        assertEquals(List.of(direct), before, "the direct jump is cheapest at first");

        for (int i = 0; i < 6; i++) {
            NavEdgeLearning.failure(graph, direct);
        }
        List<BotNavigationGraph.Edge> after = BotNavigationManager.runSearch(graph, map, new Point(0, 50), 0, 2,
                new Point(200, 50), null, true, false, 0L).path();
        assertEquals(List.of(first, second), after, "after six misses the bots take the long way");
    }

    @Test
    void whatWasLearnedSurvivesARestartAndANewGraphVersionWhereTheMoveStillExists() {
        BotNavigationGraph.Edge direct = jump(0, 2, 100);
        BotNavigationGraph v61 = threeRopes(61, direct, jump(0, 1, 60), jump(1, 2, 60));
        for (int i = 0; i < 3; i++) {
            NavEdgeLearning.failure(v61, direct);
        }
        double learned = NavEdgeLearning.factor(v61, direct);
        assertEquals(1, NavEdgeLearning.flush());

        NavEdgeLearning.useDirForTest(dir); // a restart: nothing in memory, same files
        BotNavigationGraph.Edge sameEdge = jump(0, 2, 100);
        BotNavigationGraph reloaded = threeRopes(61, sameEdge, jump(0, 1, 60), jump(1, 2, 60));
        assertEquals(learned, NavEdgeLearning.factor(reloaded, sameEdge), 0.05);

        // A version bump that renumbers the regions and re-derives the windows a few px off: the same jump.
        NavEdgeLearning.useDirForTest(dir);
        BotNavigationGraph.Edge rebuilt = edge(12, 10, BotNavigationGraph.EdgeType.JUMP, new Point(3, 52),
                new Point(205, 50), -2, 6, 0);
        BotNavigationGraph.Edge other = edge(12, 11, BotNavigationGraph.EdgeType.JUMP, new Point(0, 50),
                new Point(100, 50), 0, 0, 0);
        BotNavigationGraph v62 = graph(62, Map.of(12, List.of(rebuilt, other), 11, List.of(), 10, List.of()));
        assertEquals(learned, NavEdgeLearning.factor(v62, rebuilt), 0.05, "carried over to the rebuilt jump");
        assertEquals(1.0, NavEdgeLearning.factor(v62, other));

        // The next bump moves the landing to another platform: that is a different move, learned from scratch.
        assertEquals(1, NavEdgeLearning.flush(), "the carried counts are written for v62");
        NavEdgeLearning.useDirForTest(dir);
        BotNavigationGraph.Edge moved = edge(12, 10, BotNavigationGraph.EdgeType.JUMP, new Point(3, 52),
                new Point(205, 140), -2, 6, 0);
        BotNavigationGraph v63 = graph(63, Map.of(12, List.of(moved), 11, List.of(), 10, List.of()));
        assertEquals(1.0, NavEdgeLearning.factor(v63, moved), "a move the new graph no longer has starts fresh");
    }

    @Test
    void aFileFromBeforeTicket04IsStillReadOnItsOwnGraph() throws Exception {
        BotNavigationGraph.Edge direct = jump(0, 2, 100);
        BotNavigationGraph v68 = threeRopes(68, direct, jump(0, 1, 60), jump(1, 2, 60));
        long now = System.currentTimeMillis();
        java.nio.file.Files.writeString(dir.resolve(NavEdgeLearning.bookKey(v68) + ".json"),
                "{\"version\":68,\"edges\":{\"" + NavEdgeLearning.legacyKey(direct) + "\":[4,0," + now + "]}}");
        assertEquals(NavEdgeLearning.MAX_FACTOR, NavEdgeLearning.factor(v68, direct), 0.05);
    }

    // ── ticket 04: every v68 edge type ──────────────────────────────────────────────────────────

    private static BotNavigationGraph.Edge edge(int from, int to, BotNavigationGraph.EdgeType type, Point start,
                                                Point end, int minX, int maxX, int stepX) {
        return new BotNavigationGraph.Edge(from, to, type, start, end, minX, maxX, stepX, 0, 0, 0, 0, 100);
    }

    private static BotNavigationGraph.Edge ropeExit(int from, int to, int minY, int maxY, int stepX) {
        return new BotNavigationGraph.Edge(from, to, BotNavigationGraph.EdgeType.CLIMB, new Point(0, (minY + maxY) / 2),
                new Point(stepX * 10, 100), 0, 0, minY, maxY, stepX, 0, 0, 0, 0, 100, 140, 120);
    }

    /** Rope regions 10, 11, 12 side by side, with the given outgoing edges. */
    private static BotNavigationGraph graph(int version, Map<Integer, List<BotNavigationGraph.Edge>> out) {
        List<BotNavigationGraph.Region> regions = List.of(new BotNavigationGraph.Region(10, 200, 0, 100, false),
                new BotNavigationGraph.Region(11, 100, 0, 100, false), new BotNavigationGraph.Region(12, 0, 0, 100, false));
        Map<Integer, BotNavigationGraph.Region> byId = new HashMap<>();
        regions.forEach(r -> byId.put(r.id, r));
        return new BotNavigationGraph(990000000, version, BotMovementProfile.base(), regions, byId, Map.of(), out, Set.of());
    }

    @Test
    void everyV68EdgeTypeIsLearnedUnderItsOwnKey() {
        // Out of one launch window: a teleport left, a teleport up, a flash jump and a plain running jump - and two
        // rope exits off the same rope into the same region that differ only in their climb-height window.
        BotNavigationGraph.Edge teleLeft = edge(12, 11, BotNavigationGraph.EdgeType.TELEPORT, new Point(0, 50), new Point(-150, 50), -5, 5, 0);
        BotNavigationGraph.Edge teleUp = edge(12, 11, BotNavigationGraph.EdgeType.TELEPORT, new Point(0, 50), new Point(0, -100), -5, 5, 0);
        BotNavigationGraph.Edge flash = edge(12, 11, BotNavigationGraph.EdgeType.FLASH_JUMP, new Point(0, 50), new Point(100, 50), -5, 5, 4);
        BotNavigationGraph.Edge running = edge(12, 11, BotNavigationGraph.EdgeType.JUMP, new Point(0, 50), new Point(100, 50), -5, 5, 4);
        BotNavigationGraph.Edge standing = edge(12, 11, BotNavigationGraph.EdgeType.JUMP, new Point(0, 50), new Point(100, 50), -5, 5, 0);
        BotNavigationGraph.Edge ropeHigh = ropeExit(12, 10, 0, 40, 4);
        BotNavigationGraph.Edge ropeLow = ropeExit(12, 10, 41, 90, 4);
        List<BotNavigationGraph.Edge> all = List.of(teleLeft, teleUp, flash, running, standing, ropeHigh, ropeLow);
        BotNavigationGraph g = graph(68, Map.of(12, all, 11, List.of(), 10, List.of()));
        assertEquals(all.size(), all.stream().map(NavEdgeLearning::edgeKey).distinct().count());

        for (BotNavigationGraph.Edge failing : all) {
            NavEdgeLearning.useDirForTest(dir);
            for (int i = 0; i < 4; i++) {
                NavEdgeLearning.failure(g, failing);
            }
            for (BotNavigationGraph.Edge e : all) {
                assertEquals(e == failing, NavEdgeLearning.factor(g, e) > 1.0, failing.type + " vs " + e.type);
            }
        }
    }

    @Test
    void aMergedLeadingWalkCountsAgainstTheMoveThePlannerSees() {
        BotNavigationGraph.Edge walk = edge(11, 12, BotNavigationGraph.EdgeType.WALK, new Point(100, 50), new Point(100, 50), 100, 100, 0);
        BotNavigationGraph.Edge jumpOn = edge(12, 10, BotNavigationGraph.EdgeType.JUMP, new Point(0, 50), new Point(200, 50), -5, 5, 4);
        BotNavigationGraph g = graph(68, Map.of(11, List.of(walk), 12, List.of(jumpOn), 10, List.of()));
        BotNavigationGraph.Edge committed = BotNavigationManager.collapseLeadingWalkEdges(List.of(walk, jumpOn));
        assertNotSame(jumpOn, committed);
        for (int i = 0; i < 4; i++) {
            NavEdgeLearning.failure(g, committed);
        }
        assertTrue(NavEdgeLearning.factor(g, jumpOn) > 1.0);
    }

    private static BotMovementState botOfJob(Job job) {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setJob(job);
        return new BotMovementState(bot, null);
    }

    @Test
    void aSkillEdgeABotCannotUseNeverCountsAgainstIt() {
        BotNavigationGraph.Edge flash = edge(12, 11, BotNavigationGraph.EdgeType.FLASH_JUMP, new Point(0, 50), new Point(100, 50), -5, 5, 4);
        BotNavigationGraph.Edge tele = edge(12, 10, BotNavigationGraph.EdgeType.TELEPORT, new Point(0, 50), new Point(150, 50), -5, 5, 0);
        BotNavigationGraph g = graph(68, Map.of(12, List.of(flash, tele), 11, List.of(), 10, List.of()));
        BotMovementState warrior = botOfJob(Job.WARRIOR);
        for (int i = 0; i < 6; i++) {
            NavEdgeLearning.failure(warrior, g, flash);
            NavEdgeLearning.failure(warrior, g, tele);
        }
        assertEquals(1.0, NavEdgeLearning.factor(g, flash));
        assertEquals(1.0, NavEdgeLearning.factor(g, tele));
        assertEquals(12, NavEdgeLearning.ignoredForTest(NavEdgeLearning.Cause.SKILL_UNAVAILABLE));

        // Ticket 02: the job is not enough, the bot must hold the skill.
        BotMovementState hermit = botOfJob(Job.HERMIT);
        BotMovementState wizard = botOfJob(Job.FP_WIZARD);
        Map<Integer, client.Skill> previous = swapSkillFactory(Map.of(4111006, new client.Skill(4111006),
                2101002, new client.Skill(2101002)));
        try {
            hermit.bot.changeSkillLevel(client.SkillFactory.getSkill(4111006), (byte) 20, 20, -1);
            wizard.bot.changeSkillLevel(client.SkillFactory.getSkill(2101002), (byte) 1, 20, -1);
            failHeldSkillEdges(g, flash, tele, hermit, wizard);
        } finally {
            swapSkillFactory(previous);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, client.Skill> swapSkillFactory(Map<Integer, client.Skill> skills) {
        try {
            java.lang.reflect.Field field = client.SkillFactory.class.getDeclaredField("skills");
            field.setAccessible(true);
            Map<Integer, client.Skill> previous = (Map<Integer, client.Skill>) field.get(null);
            field.set(null, skills);
            return previous;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void failHeldSkillEdges(BotNavigationGraph g, BotNavigationGraph.Edge flash,
                                           BotNavigationGraph.Edge tele, BotMovementState hermit,
                                           BotMovementState wizard) {
        for (int i = 0; i < 6; i++) {
            NavEdgeLearning.failure(hermit, g, flash);
            NavEdgeLearning.failure(hermit, g, tele); // a hermit cannot teleport either
            NavEdgeLearning.failure(wizard, g, tele);
        }
        assertEquals(NavEdgeLearning.BAD_FACTOR, NavEdgeLearning.factor(g, flash));
        assertEquals(NavEdgeLearning.BAD_FACTOR, NavEdgeLearning.factor(g, tele));
        assertEquals(18, NavEdgeLearning.ignoredForTest(NavEdgeLearning.Cause.SKILL_UNAVAILABLE));
    }

    @Test
    void aLaunchedJumpThatComesDownInAThirdRegionIsAMissUnlessAHitKnockedItOff() {
        BotNavigationGraph.Edge jumpOn = edge(12, 10, BotNavigationGraph.EdgeType.JUMP, new Point(0, 50), new Point(200, 50), -5, 5, 4);
        BotNavigationGraph g = graph(68, Map.of(12, List.of(jumpOn), 11, List.of(), 10, List.of()));
        BotMovementState bot = botOfJob(Job.WARRIOR);

        fly(bot, g, jumpOn, false, 11);
        assertTrue(NavEdgeLearning.factor(g, jumpOn) > 1.0, "came down on region 11: a miss");

        NavEdgeLearning.useDirForTest(dir);
        fly(bot, g, jumpOn, true, 11);
        assertEquals(1.0, NavEdgeLearning.factor(g, jumpOn), "knocked off course by a mob: not the jump's fault");

        fly(bot, g, jumpOn, false, 10);
        fly(bot, g, jumpOn, false, 12); // fell back where it started: no verdict
        assertEquals(1.0, NavEdgeLearning.factor(g, jumpOn), "made it");
    }

    /** One launch of the edge: a tick in the air (optionally hit), then a tick on the ground in {@code landed}. */
    private static void fly(BotMovementState bot, BotNavigationGraph g, BotNavigationGraph.Edge edge, boolean hit, int landed) {
        bot.navEdge = edge;
        bot.navLearnedEdge = null;
        bot.inAir = true;
        bot.mobHitCooldownMs = 0;
        NavEdgeLearning.observe(bot, g, -1);
        if (hit) {
            bot.mobHitCooldownMs = 600;
            NavEdgeLearning.observe(bot, g, -1);
        }
        bot.inAir = false;
        bot.navEdge = null; // the landing tick dropped the committed edge before the navigator looked
        NavEdgeLearning.observe(bot, g, landed);
    }

    // ── ticket 33's hook ─────────────────────────────────────────────────────────────────────────

    @Test
    void aDeliberateFlawIsNeverLearnedAndOnlyGenuineFailuresRaiseTheCost() {
        BotNavigationGraph.Edge jumpOn = edge(12, 10, BotNavigationGraph.EdgeType.JUMP, new Point(0, 50), new Point(200, 50), -5, 5, 4);
        BotNavigationGraph.Edge rope = ropeExit(12, 11, 0, 90, 4);
        BotNavigationGraph g = graph(68, Map.of(12, List.of(jumpOn, rope), 11, List.of(), 10, List.of()));
        BotMovementState bot = botOfJob(Job.WARRIOR);

        for (int i = 0; i < 8; i++) {
            NavEdgeLearning.tagDeliberateFlaw(bot, jumpOn); // a missed jump, on purpose
            fly(bot, g, jumpOn, false, 11);
            NavEdgeLearning.tagDeliberateFlaw(bot, rope);   // a rope slip, on purpose, then stuck
            NavEdgeLearning.failure(bot, g, rope);
            NavEdgeLearning.deliberateFailure(g, jumpOn);    // a flaw reported after the fact
        }
        assertEquals(1.0, NavEdgeLearning.factor(g, jumpOn));
        assertEquals(1.0, NavEdgeLearning.factor(g, rope));
        assertEquals(24, NavEdgeLearning.ignoredForTest(NavEdgeLearning.Cause.DELIBERATE_FLAW));
        assertTrue(NavEdgeLearning.describe().contains("24 deliberate flaws"));

        // The tag is spent by the outcome: the next, untagged miss is genuine and counts.
        for (int i = 0; i < 6; i++) {
            fly(bot, g, jumpOn, false, 11);
            NavEdgeLearning.failure(bot, g, rope);
        }
        assertEquals(NavEdgeLearning.BAD_FACTOR, NavEdgeLearning.factor(g, jumpOn));
        assertEquals(NavEdgeLearning.BAD_FACTOR, NavEdgeLearning.factor(g, rope));
    }
}
