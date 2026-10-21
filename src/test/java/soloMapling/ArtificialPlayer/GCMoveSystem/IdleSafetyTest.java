package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.Foothold;
import server.maps.MapObject;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 1.4.2: idle bots walk out of mob packs before idling and when hit while idle. */
class IdleSafetyTest {

    static final int MAP_ID = 990004802;
    static final int FLOOR = 0, UPPER = 1, ISLAND = 2, ROPE = 3, ROPE_HEAD = 4;

    private final List<Monster> mobs = new ArrayList<>();
    private final Point botPos = new Point(200, 300);
    private Character bot;
    private BotMovementState entry;

    private static BotNavigationGraph.Region ground(int id, int x1, int x2, int y) {
        return new BotNavigationGraph.Region(id, List.of(new BotNavigationGraph.Segment(
                new Foothold(new Point(x1, y), new Point(x2, y), 100 + id))));
    }

    private static BotNavigationGraph.Edge edge(int from, int to, BotNavigationGraph.EdgeType type, Point a, Point b) {
        return new BotNavigationGraph.Edge(from, to, type, a, b, a.x, a.x, 0, 0, 0, 0, 0, 500);
    }

    /**
     * A floor (y 300, x 0-1200) with the pack at its left end, a ledge above it (y 100, x 600-900) reached by a jump,
     * a rope at x 1100 up to a short head ledge, and an island (x 2000-2400) nothing reaches.
     */
    static BotNavigationGraph graph() {
        List<BotNavigationGraph.Region> regions = List.of(ground(FLOOR, 0, 1200, 300), ground(UPPER, 600, 900, 100),
                ground(ISLAND, 2000, 2400, 300), new BotNavigationGraph.Region(ROPE, 1100, 130, 290, false),
                ground(ROPE_HEAD, 1050, 1250, 120));
        Map<Integer, BotNavigationGraph.Region> byId = new HashMap<>();
        regions.forEach(r -> byId.put(r.id, r));
        Map<Integer, List<BotNavigationGraph.Edge>> out = new HashMap<>();
        out.put(FLOOR, List.of(edge(FLOOR, UPPER, BotNavigationGraph.EdgeType.JUMP, new Point(620, 300), new Point(640, 100)),
                edge(FLOOR, ROPE, BotNavigationGraph.EdgeType.CLIMB, new Point(1100, 300), new Point(1100, 280))));
        out.put(UPPER, List.of(edge(UPPER, FLOOR, BotNavigationGraph.EdgeType.DROP, new Point(890, 100), new Point(910, 300))));
        out.put(ROPE, List.of(edge(ROPE, ROPE_HEAD, BotNavigationGraph.EdgeType.CLIMB, new Point(1100, 140), new Point(1100, 120)),
                edge(ROPE, FLOOR, BotNavigationGraph.EdgeType.CLIMB, new Point(1100, 280), new Point(1100, 300))));
        out.put(ROPE_HEAD, List.of(edge(ROPE_HEAD, ROPE, BotNavigationGraph.EdgeType.CLIMB, new Point(1100, 120), new Point(1100, 140))));
        return new BotNavigationGraph(MAP_ID, BotNavigationGraphProvider.GRAPH_VERSION, BotMovementProfile.base(),
                regions, byId, Map.of(), out, Set.of());
    }

    private Monster mob(int x, int y) {
        Monster m = mock(Monster.class);
        when(m.isAlive()).thenReturn(true);
        when(m.getPosition()).thenReturn(new Point(x, y));
        when(m.getId()).thenReturn(100100);
        return m;
    }

    @BeforeEach
    void setUp() {
        BotNavigationGraphProvider.GRAPHS.put(BotNavigationGraphProvider.GraphCacheKey.from(MAP_ID, BotMovementProfile.base()), graph());
        mobs.add(mob(180, 300));
        mobs.add(mob(230, 300));
        mobs.add(mob(270, 300));
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(MAP_ID);
        when(map.getAllMonsters()).thenAnswer(i -> new ArrayList<>(mobs));
        when(map.getMapObjectsInRect(any(), any())).thenAnswer(i -> {
            java.awt.Rectangle box = i.getArgument(0);
            List<MapObject> in = new ArrayList<>();
            for (Monster m : mobs) {
                if (box.contains(m.getPosition())) {
                    in.add(m);
                }
            }
            return in;
        });
        when(map.getPortals()).thenReturn(List.of());
        bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(MAP_ID);
        when(bot.getPosition()).thenAnswer(i -> new Point(botPos));
        when(bot.getName()).thenReturn("Idler");
        when(bot.getId()).thenReturn(1);
        entry = new BotMovementState(bot, null);
        entry.movementProfile = BotMovementProfile.base();
        entry.idleReason = "qa";
    }

    @AfterEach
    void restore() {
        IdleSafety.enabled = true;
    }

    private List<Point> mobPoints() {
        return mobs.stream().map(Monster::getPosition).toList();
    }

    @Test
    void aBotAboutToIdleInAPackWalksToASafeReachableSpot() {
        assertTrue(IdleSafety.tick(entry, bot));
        assertNotNull(entry.moveTarget);
        assertEquals("idle-safe", entry.moveTargetSource);
        assertTrue(entry.idleRelocating);
        assertTrue(IdleSafety.clearance(entry.moveTarget, mobPoints()) >= IdleSafety.SAFE_PX, entry.moveTarget.toString());
        assertTrue(entry.moveTarget.x < 2000, "the island is out of reach: " + entry.moveTarget);
        assertTrue(IdleSafety.travel(botPos, entry.moveTarget) <= IdleSafety.MAX_TRAVEL_PX);
        assertEquals(1, entry.idleRelocationsTotal);
        // walking there is not idling; arriving (target cleared) is
        assertNull(IdleSafety.idleReason(entry, System.currentTimeMillis()));
        entry.moveTarget = null;
        assertEquals("qa", IdleSafety.idleReason(entry, System.currentTimeMillis()));
        assertFalse(entry.idleRelocating);
    }

    @Test
    void noMobInReachMeansStayPut() {
        mobs.clear();
        mobs.add(mob(900, 300));
        assertFalse(IdleSafety.tick(entry, bot));
        assertNull(entry.moveTarget);
    }

    @Test
    void aGrindingBotBetweenSwingsIsFightingNotIdle() {
        entry.idleReason = null;
        entry.grinding = true;
        assertNull(IdleSafety.idleReason(entry, System.currentTimeMillis()));
        assertFalse(IdleSafety.tick(entry, bot));
        entry.grinding = false; // a bot that is not grinding, standing with no target, is idle
        assertEquals("stand", IdleSafety.idleReason(entry, System.currentTimeMillis()));
        entry.grinding = true;
        entry.reactingUntilMs = System.currentTimeMillis() + 2_000; // a stop-reaction to a player is idle too
        assertEquals("react", IdleSafety.idleReason(entry, System.currentTimeMillis()));
        assertTrue(IdleSafety.tick(entry, bot));
        assertEquals(0L, entry.reactingUntilMs, "the greeting ends so the bot can walk off");
    }

    @Test
    void aHitWhileIdleRelocatesAtOnceEvenPastTheDangerCapButOnlyOnceLanded() {
        entry.idleEpisodeRelocations = IdleSafety.MAX_DANGER_RELOCATIONS;
        entry.idleCheckAtMs = System.currentTimeMillis() + 60_000;
        assertFalse(IdleSafety.tick(entry, bot), "capped and throttled: no move on danger alone");

        IdleSafety.onHit(entry, bot, mobs.get(0));
        assertEquals(1, entry.idleHitsTotal);
        entry.inAir = true; // the knockback
        assertFalse(IdleSafety.tick(entry, bot));
        entry.inAir = false;
        assertTrue(IdleSafety.tick(entry, bot));
        assertNotNull(entry.moveTarget);
        assertFalse(entry.idleHitPending);
    }

    @Test
    void switchedOffItOnlyCountsHits() {
        IdleSafety.enabled = false;
        IdleSafety.onHit(entry, bot, mobs.get(0));
        assertEquals(1, entry.idleHitsTotal);
        assertFalse(IdleSafety.tick(entry, bot));
        assertNull(entry.moveTarget);
    }

    @Test
    void candidatesCoverLedgesAndRopeTopsInReachOnly() {
        BotNavigationGraph g = graph();
        Set<Integer> reach = IdleSafety.reachable(g, FLOOR);
        assertEquals(Set.of(FLOOR, UPPER, ROPE, ROPE_HEAD), reach);
        List<IdleSafety.Candidate> cs = IdleSafety.candidates(g, reach, new Point(1000, 300));
        assertTrue(cs.stream().anyMatch(c -> c.kind() == IdleSafety.Kind.ROPE_TOP && c.point().equals(new Point(1100, 120))));
        assertTrue(cs.stream().noneMatch(c -> c.regionId() == ISLAND));
        assertTrue(cs.stream().allMatch(c -> c.travel() <= IdleSafety.MAX_TRAVEL_PX));
    }

    @Test
    void chooseNeedsRealClearanceOrAClearImprovement() {
        List<Point> pack = List.of(new Point(200, 300));
        IdleSafety.Candidate near = new IdleSafety.Candidate(new Point(300, 300), FLOOR, IdleSafety.Kind.LEDGE, 100);
        IdleSafety.Candidate safe = new IdleSafety.Candidate(new Point(700, 300), FLOOR, IdleSafety.Kind.LEDGE, 500);
        IdleSafety.Choice c = IdleSafety.choose(List.of(near, safe), pack, new Point(200, 300));
        assertEquals(safe, c.candidate());
        // nothing fully safe: the clearest, if it beats standing still by a margin
        IdleSafety.Candidate better = new IdleSafety.Candidate(new Point(400, 300), FLOOR, IdleSafety.Kind.LEDGE, 200);
        assertEquals(better, IdleSafety.choose(List.of(near, better), pack, new Point(200, 300)).candidate());
        assertNull(IdleSafety.choose(List.of(near), pack, new Point(200, 300)));
        // equal footing: a rope top beats a plain ledge
        IdleSafety.Candidate ledge = new IdleSafety.Candidate(new Point(1200, 300), FLOOR, IdleSafety.Kind.LEDGE, 400);
        IdleSafety.Candidate rope = new IdleSafety.Candidate(new Point(1300, 300), ROPE_HEAD, IdleSafety.Kind.ROPE_TOP, 400);
        assertEquals(rope, IdleSafety.choose(List.of(ledge, rope), pack, new Point(200, 300)).candidate());
    }
}
