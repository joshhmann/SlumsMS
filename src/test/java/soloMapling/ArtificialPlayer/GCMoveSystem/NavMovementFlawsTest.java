package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import scripting.event.EventInstanceManager;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NavMovementFlawsTest {

    private static final int WALK_STEP = 8;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        NavMovementFlaws.resetForTest();
        NavMovementFlaws.tuning.observedOnly = false; // no observer poll in a unit test
        NavEdgeLearning.useDirForTest(dir);
    }

    @AfterEach
    void tearDown() {
        NavMovementFlaws.resetForTest();
        NavEdgeLearning.useDirForTest(null);
    }

    private static BotMovementState bot(int id, int level) {
        Character chr = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getLevel()).thenReturn(level);
        when(chr.getMap()).thenReturn(map);
        when(chr.getMapId()).thenReturn(100000000);
        return new BotMovementState(chr, null);
    }

    /** A running jump across a gap: launch window [minX, maxX], landing dx away and rise px up. */
    private static BotNavigationGraph.Edge jump(int minX, int maxX, int dx, int rise) {
        return new BotNavigationGraph.Edge(12, 11, BotNavigationGraph.EdgeType.JUMP, new Point(minX, 100),
                new Point(minX + dx, 100 - rise), minX, maxX, WALK_STEP, 0, 0, 0, 0, 100);
    }

    private static BotNavigationGraph.Edge walk() {
        return new BotNavigationGraph.Edge(12, 12, BotNavigationGraph.EdgeType.WALK, new Point(0, 100),
                new Point(300, 100), 0, 0, 0, 0, 0, 0, 0, 0, 0, 100);
    }

    private static BotNavigationGraph graph(BotNavigationGraph.Edge... edges) {
        List<BotNavigationGraph.Region> regions = List.of(new BotNavigationGraph.Region(10, 200, 0, 100, false),
                new BotNavigationGraph.Region(11, 100, 0, 100, false), new BotNavigationGraph.Region(12, 0, 0, 100, false));
        Map<Integer, BotNavigationGraph.Region> byId = new HashMap<>();
        regions.forEach(r -> byId.put(r.id, r));
        return new BotNavigationGraph(990000000, 68, BotMovementProfile.base(), regions, byId, Map.of(),
                Map.of(12, List.of(edges), 11, List.of(), 10, List.of()), Set.of());
    }

    private static void rolls(double... values) {
        int[] i = {0};
        NavMovementFlaws.random = () -> values[Math.min(i[0]++, values.length - 1)];
    }

    // ── who is sloppy ───────────────────────────────────────────────────────────────────────────

    @Test
    void eachBotHasItsOwnFixedSkillAndThePopulationRunsFromClumsyToCrisp() {
        double min = 1;
        double max = 0;
        double sum = 0;
        int n = 2000;
        for (int id = 1; id <= n; id++) {
            double s = NavMovementFlaws.baseSkill(id);
            assertEquals(s, NavMovementFlaws.baseSkill(id), "never re-rolls");
            assertTrue(s >= 0.85 && s <= 0.99, "in range: " + s);
            min = Math.min(min, s);
            max = Math.max(max, s);
            sum += s;
        }
        assertTrue(min < 0.87, "some bots are clumsy: " + min);
        assertTrue(max > 0.98, "some are crisp: " + max);
        double mean = sum / n;
        assertTrue(mean > 0.92 && mean < 0.96, "most are fairly clean: " + mean);
    }

    @Test
    void skillGrowsALittleWithLevelAndMistakesFadeOnAFamiliarMap() {
        assertEquals(0.85, NavMovementFlaws.withLevel(0.85, 1), 0.002);
        assertEquals(0.90, NavMovementFlaws.withLevel(0.85, 120), 0.001);
        assertTrue(NavMovementFlaws.withLevel(0.85, 200) < 0.91, "only slightly");

        NavMovementFlaws.State s = new NavMovementFlaws.State();
        long t0 = 1_000_000L;
        assertEquals(1.0, NavMovementFlaws.familiarity(s, 1, t0));
        assertEquals(0.7, NavMovementFlaws.familiarity(s, 1, t0 + 45 * 60_000L), 1e-9);
        assertEquals(1.0, NavMovementFlaws.familiarity(s, 2, t0 + 46 * 60_000L), "a new map starts fresh");
    }

    @Test
    void someBotsHopEverywhereAndMostDoNot() {
        int hoppers = 0;
        for (int id = 1; id <= 1000; id++) {
            if (NavMovementFlaws.hopper(bot(id, 30))) {
                hoppers++;
            }
        }
        assertTrue(hoppers > 150 && hoppers < 250, "about one in five: " + hoppers);
    }

    // ── how hard a move is ──────────────────────────────────────────────────────────────────────

    @Test
    void walksNeverFailAndTightLongJumpsAreHarderThanEasyOnes() {
        assertEquals(0, NavMovementFlaws.difficulty(walk(), WALK_STEP));
        BotNavigationGraph.Edge drop = new BotNavigationGraph.Edge(12, 11, BotNavigationGraph.EdgeType.DROP,
                new Point(0, 100), new Point(0, 300), -5, 5, 0, 0, 0, 0, 0, 100);
        assertEquals(0, NavMovementFlaws.difficulty(drop, WALK_STEP));
        double easy = NavMovementFlaws.difficulty(jump(0, 60, 40, 0), WALK_STEP);
        double hard = NavMovementFlaws.difficulty(jump(0, 4, 170, 70), WALK_STEP);
        assertTrue(easy < 0.3, "wide short flat jump: " + easy);
        assertTrue(hard > 0.85, "a sliver of a window, long and rising: " + hard);
    }

    // ── when a flaw may happen ──────────────────────────────────────────────────────────────────

    @Test
    void noFlawsDuringCommitmentsEventsCompanionsOrOffscreen() {
        BotMovementState entry = bot(7, 30);
        assertTrue(NavMovementFlaws.eligible(entry));

        entry.heldForCommitment = true;
        assertFalse(NavMovementFlaws.eligible(entry), "trade hold");
        entry.heldForCommitment = false;
        entry.following = true;
        assertFalse(NavMovementFlaws.eligible(entry), "party companion");
        entry.following = false;
        when(entry.bot.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        assertFalse(NavMovementFlaws.eligible(entry), "party quest / boss");
        when(entry.bot.getEventInstance()).thenReturn(null);

        NavMovementFlaws.tuning.observedOnly = true;
        assertFalse(NavMovementFlaws.eligible(entry), "nobody on the map to see it");
        NavMovementFlaws.tuning.observedOnly = false;
        NavMovementFlaws.tuning.enabled = false;
        assertFalse(NavMovementFlaws.eligible(entry), "-Dbot.flaws.enabled=false");
        NavMovementFlaws.tuning.enabled = true;
        NavMovementFlaws.tuning.rate = 0;
        assertFalse(NavMovementFlaws.eligible(entry), "-Dbot.flaws.rate=0");
    }

    @Test
    void aMissIsRolledOncePerApproachAndNeverTwiceInARow() {
        BotMovementState entry = bot(7, 1);
        BotNavigationGraph.Edge hard = jump(0, 4, 170, 70);
        long now = 10_000_000L;
        rolls(0.0, 0.5); // a miss; 3 steps early
        assertEquals(NavMovementFlaws.Plan.EARLY, NavMovementFlaws.planFor(entry, hard, WALK_STEP, now));
        assertEquals(WALK_STEP * 3, entry.flaws.planPx);
        rolls(0.0);
        assertEquals(NavMovementFlaws.Plan.EARLY, NavMovementFlaws.planFor(entry, hard, WALK_STEP, now),
                "the same approach keeps its plan, no re-roll");

        // The miss happened: the cooldown and the same-edge memory turn the next would-be miss into a pause.
        entry.flaws.missReadyAtMs = now + 45_000;
        entry.flaws.lastMissEdge = hard;
        entry.flaws.lastMissAtMs = now;
        entry.flaws.planEdge = null;
        rolls(0.0);
        assertEquals(NavMovementFlaws.Plan.HESITATE, NavMovementFlaws.planFor(entry, hard, WALK_STEP, now + 1));
        entry.flaws.planEdge = null;
        rolls(0.0);
        assertEquals(NavMovementFlaws.Plan.HESITATE, NavMovementFlaws.planFor(entry, hard, WALK_STEP, now + 60_000),
                "not the same edge again within five minutes");
        entry.flaws.planEdge = null;
        rolls(0.0, 0.5);
        assertEquals(NavMovementFlaws.Plan.EARLY, NavMovementFlaws.planFor(entry, hard, WALK_STEP, now + 400_000));
    }

    @Test
    void aHighRollIsAClean_jumpAndAWalkIsNeverFumbled() {
        BotMovementState entry = bot(7, 1);
        rolls(0.99);
        assertEquals(NavMovementFlaws.Plan.NONE, NavMovementFlaws.planFor(entry, jump(0, 4, 170, 70), WALK_STEP, 1));
        rolls(0.0);
        assertEquals(NavMovementFlaws.Plan.NONE, NavMovementFlaws.planFor(entry, walk(), WALK_STEP, 1));
    }

    /**
     * The rates the defaults give, over a population of 500 bots and a mix of jumps: measured, not just asserted, so
     * the ticket's numbers come from here. A miss is rare enough not to hurt grinding; quirks are visible.
     */
    @Test
    void defaultRatesAreRareButVisible() {
        List<BotNavigationGraph.Edge> jumps = List.of(jump(0, 60, 40, 0), jump(0, 24, 100, 20), jump(0, 8, 150, 50),
                jump(0, 4, 170, 70));
        Map<NavMovementFlaws.Plan, Integer> seen = new HashMap<>();
        int tries = 0;
        for (int id = 1; id <= 500; id++) {
            BotMovementState entry = bot(id, 40);
            for (int rep = 0; rep < 40; rep++) {
                for (BotNavigationGraph.Edge e : jumps) {
                    entry.flaws.planEdge = null;
                    entry.flaws.missReadyAtMs = 0;
                    entry.flaws.lastMissEdge = null;
                    seen.merge(NavMovementFlaws.planFor(entry, e, WALK_STEP, 1), 1, Integer::sum);
                    tries++;
                }
            }
        }
        double miss = seen.getOrDefault(NavMovementFlaws.Plan.EARLY, 0) / (double) tries;
        double quirk = 1.0 - miss - seen.getOrDefault(NavMovementFlaws.Plan.NONE, 0) / (double) tries;
        System.out.printf("flaw rates per jump: miss %.2f%%, quirk %.2f%% (%s)%n", miss * 100, quirk * 100, seen);
        assertTrue(miss > 0.01 && miss < 0.05, "miss rate " + miss);
        assertTrue(quirk > 0.04 && quirk < 0.15, "quirk rate " + quirk);
    }

    // ── learning is not poisoned ────────────────────────────────────────────────────────────────

    /** A flaw episode as the navigator runs it: tag, fly, land, and the landing ends the flaw. */
    private static void flawedFlight(BotMovementState bot, BotNavigationGraph g, BotNavigationGraph.Edge edge,
                                     int landed) {
        NavEdgeLearning.tagDeliberateFlaw(bot, edge);
        bot.flaws.flawEdge = edge;
        bot.flaws.flawFromRegion = edge.fromRegionId;
        bot.flaws.flawKind = NavMovementFlaws.Kind.MISS;
        bot.flaws.airborne = true;
        fly(bot, g, edge, landed);
    }

    private static void fly(BotMovementState bot, BotNavigationGraph g, BotNavigationGraph.Edge edge, int landed) {
        bot.navEdge = edge;
        bot.navLearnedEdge = null;
        bot.inAir = true;
        NavEdgeLearning.observe(bot, g, -1);
        NavMovementFlaws.afterObserve(bot, -1);
        bot.inAir = false;
        bot.navEdge = null;
        NavEdgeLearning.observe(bot, g, landed);
        NavMovementFlaws.afterObserve(bot, landed);
    }

    @Test
    void deliberateMissesNeverMoveTheLearnedCostButGenuineMissesDo() {
        BotNavigationGraph.Edge hard = jump(0, 4, 170, 70);
        BotNavigationGraph g = graph(hard);
        BotMovementState entry = bot(7, 30);
        for (int i = 0; i < 10; i++) {
            flawedFlight(entry, g, hard, 10);  // came up short and fell to the platform below
            flawedFlight(entry, g, hard, 12);  // came up short and landed back where it started
            assertNull(entry.navFlawEdge, "every landing spends the tag");
        }
        assertEquals(1.0, NavEdgeLearning.factor(g, hard), "ten deliberate falls teach nothing");
        assertEquals(10, NavEdgeLearning.ignoredForTest(NavEdgeLearning.Cause.DELIBERATE_FLAW));

        // A flaw that landed back on its own platform left no tag behind: the honest retry's miss counts.
        for (int i = 0; i < 6; i++) {
            fly(entry, g, hard, 10);
        }
        assertEquals(NavEdgeLearning.BAD_FACTOR, NavEdgeLearning.factor(g, hard));
    }

    @Test
    void aMissThatStillMakesItCountsAsAMake() {
        BotNavigationGraph.Edge hard = jump(0, 4, 170, 70);
        BotNavigationGraph g = graph(hard);
        BotMovementState entry = bot(7, 30);
        for (int i = 0; i < 4; i++) {
            fly(entry, g, hard, 10); // genuine misses
        }
        double before = NavEdgeLearning.factor(g, hard);
        assertTrue(before > 1.0);
        flawedFlight(entry, g, hard, 11); // took off early and made it anyway
        assertTrue(NavEdgeLearning.factor(g, hard) < before, "a make is a make");
    }

    // ── steering ────────────────────────────────────────────────────────────────────────────────

    @Test
    void anOverrunRunsPastTheSpotThenHandsSteeringBack() {
        BotMovementState entry = bot(7, 30);
        when(entry.bot.getPosition()).thenReturn(new Point(100, 100));
        Point launch = new Point(100, 100);
        entry.flaws.planPx = 130;
        entry.flaws.overrunTicks = 8;
        Point steer = NavMovementFlaws.steer(entry, launch);
        assertEquals(130, steer.x, "runs on past the launch spot");
        assertTrue(entry.navPreciseTarget);
        when(entry.bot.getPosition()).thenReturn(new Point(128, 100));
        assertEquals(launch, NavMovementFlaws.steer(entry, launch), "got there: walks back");
        assertEquals(0, entry.flaws.overrunTicks);

        entry.flaws.twitchTicks = 2;
        entry.flaws.twitchDir = -1;
        assertNotEquals(launch, NavMovementFlaws.steer(entry, launch));
        assertTrue(NavMovementFlaws.steer(entry, launch).x < 128, "a twitch the wrong way");
        assertEquals(launch, NavMovementFlaws.steer(entry, launch), "and back on course");
    }

    @Test
    void aFlawHopKeepsItsEdgeUntilItLands() {
        BotMovementState entry = bot(7, 30);
        entry.flaws.airborne = true;
        entry.inAir = true;
        assertTrue(NavMovementFlaws.holdsNavigation(entry));
        entry.flaws.flawKind = NavMovementFlaws.Kind.MISS;
        assertFalse(NavMovementFlaws.holdsNavigation(entry), "a miss flies its own jump edge like any jump");
        entry.flaws.flawKind = null;
        entry.inAir = false;
        NavMovementFlaws.afterObserve(entry, 12);
        assertFalse(entry.flaws.airborne);
        assertFalse(NavMovementFlaws.holdsNavigation(entry));
    }

    // ── 1.4.1: a moment at the top of the ladder ────────────────────────────────────────────────

    private static BotNavigationGraph.Edge topStepOff() {
        return new BotNavigationGraph.Edge(20, 12, BotNavigationGraph.EdgeType.CLIMB, new Point(100, 0),
                new Point(100, 5), 0, 0, 0, 0, 0, 0, 0, 0, 0, 30);
    }

    @Test
    void aClumsyRollPausesABotAtTheLadderTopForUnderTwoSecondsThenLetsItOff() {
        BotMovementState entry = bot(7, 30);
        entry.climbRope = new server.maps.Rope(100, 0, 400, true);
        BotNavigationGraph.Edge edge = topStepOff();
        rolls(0.0, 0.5);
        assertFalse(NavMovementFlaws.pausingAtRopeTop(entry, edge, new Point(100, 300)), "rolled low on the rope");
        assertTrue(NavMovementFlaws.pausingAtRopeTop(entry, edge, new Point(100, 1)), "pauses at the top");
        long left = entry.flaws.topPauseUntilMs - System.currentTimeMillis();
        assertTrue(left > 0 && left <= NavMovementFlaws.TOP_PAUSE_MAX_MS, "bounded: " + left);
        assertEquals(1, NavMovementFlaws.countForTest(NavMovementFlaws.Kind.TOP_PAUSE));
        entry.flaws.topPauseUntilMs = 1L; // time's up
        assertFalse(NavMovementFlaws.pausingAtRopeTop(entry, edge, new Point(100, 1)));
        assertFalse(NavMovementFlaws.pausingAtRopeTop(entry, edge, new Point(100, 1)), "once per climb");
    }

    @Test
    void mostClimbsAndShortGrabsAtTheHeadNeverPause() {
        BotMovementState entry = bot(7, 30);
        entry.climbRope = new server.maps.Rope(100, 0, 400, true);
        rolls(0.99);
        assertFalse(NavMovementFlaws.pausingAtRopeTop(entry, topStepOff(), new Point(100, 300)));
        assertFalse(NavMovementFlaws.pausingAtRopeTop(entry, topStepOff(), new Point(100, 1)), "a fresh edge rerolls high");
        BotMovementState grabbed = bot(8, 30);
        grabbed.climbRope = new server.maps.Rope(100, 0, 400, true);
        rolls(0.0);
        assertFalse(NavMovementFlaws.pausingAtRopeTop(grabbed, topStepOff(), new Point(100, 1)),
                "grabbed at the head, not climbed up to it");
        BotMovementState off = bot(9, 30);
        off.climbRope = new server.maps.Rope(100, 0, 400, true);
        off.flaws.off = true;
        rolls(0.0);
        assertFalse(NavMovementFlaws.pausingAtRopeTop(off, topStepOff(), new Point(100, 300)));
        assertFalse(NavMovementFlaws.pausingAtRopeTop(off, topStepOff(), new Point(100, 1)), "flaws off");
    }
}
