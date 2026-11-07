package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import client.BotClient;
import client.Character;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Rope;
import soloMapling.WzTestData;

import java.awt.Point;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v72 (b356/b360): the add-only rope-hop pass on Zakum PQ's 280010110 (the room QA's base-speed run gave up in: "can't
 * reach reactor 2112004 (-511,142), no route r10->r5 walking"). With the pass, the 98 px hop x 359 -> 261 is an edge,
 * a base regular routes from the room's portals to the reactor, and every v71 edge is still there unchanged.
 */
class RopeHopGraphTest {
    static final int ZPQ_ROPE_ROOM = 280_010_110;
    static final Point REACTOR_2112004 = new Point(-511, 142);

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    @AfterEach
    void hopsBackOn() {
        BotNavigationGraphProvider.clientKickRopeHops = true;
        NavRopeHop.set(true);
        NavStuckLog.useForTest(null, null);
    }

    private static BotNavigationGraph build(MapleMap map, boolean hops) {
        BotNavigationGraphProvider.clientKickRopeHops = hops;
        try {
            return BotNavigationGraphProvider.buildUncachedGraph(map, BotMovementProfile.base());
        } finally {
            BotNavigationGraphProvider.clientKickRopeHops = true;
        }
    }

    static Set<String> edgeKeys(BotNavigationGraph g) {
        Set<String> keys = new HashSet<>();
        for (BotNavigationGraph.Region r : g.regions) {
            for (BotNavigationGraph.Edge e : g.getOutgoing(r.id)) {
                keys.add(r.id + ">" + e.toRegionId + " " + e.type + " " + e.startPoint.x + "," + e.startPoint.y + ">"
                        + e.endPoint.x + "," + e.endPoint.y + " x[" + e.launchMinX + "," + e.launchMaxX + "] y["
                        + e.launchMinY + "," + e.launchMaxY + "] s" + e.launchStepX + " c" + e.cost);
            }
        }
        return keys;
    }

    private static boolean hopEdge(BotNavigationGraph g, int fromRopeX, int toRopeX) {
        for (BotNavigationGraph.Region r : g.regions) {
            if (!r.isRopeRegion || r.minX != fromRopeX) {
                continue;
            }
            for (BotNavigationGraph.Edge e : g.getOutgoing(r.id)) {
                BotNavigationGraph.Region to = g.getRegion(e.toRegionId);
                if (to != null && to.isRopeRegion && to.minX == toRopeX) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> portalsReaching(MapleMap map, BotNavigationGraph g, Point target) {
        List<String> out = new ArrayList<>();
        for (Portal p : map.getPortals()) {
            Point from = new Point(p.getPosition());
            if (NavProbe.probe(map, g, g.findRegionId(map, from), from, target, 0).reachable()) {
                out.add(p.getName());
            }
        }
        return out;
    }

    @Test
    void theZakumRopeRoomGainsTheHopAndKeepsEveryOldEdge() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(ZPQ_ROPE_ROOM);
        BotNavigationGraph v71 = build(map, false);
        BotNavigationGraph v72 = build(map, true);

        assertFalse(hopEdge(v71, 359, 261), "v71 has no edge for the 98 px hop (the b360 gap)");
        assertTrue(hopEdge(v72, 359, 261), "v72 adds it");

        Set<String> before = edgeKeys(v71);
        Set<String> after = edgeKeys(v72);
        Set<String> lost = new HashSet<>(before);
        lost.removeAll(after);
        assertEquals(Set.of(), lost, "add-only: no v71 edge is changed or removed");
        assertTrue(after.size() > before.size());

        // QA's run was stuck on r10 (the floor x 663..1407, y 258): only the west00 side could reach the reactor
        assertEquals(List.of("west00"), portalsReaching(map, v71, REACTOR_2112004), "v71: from west00 only");
        assertEquals(Set.of("sp", "west00", "east00"), Set.copyOf(portalsReaching(map, v72, REACTOR_2112004)),
                "v72: from every portal of the room");
        Point r10 = new Point(1035, 258);
        assertFalse(NavProbe.probe(map, v71, v71.findRegionId(map, r10), r10, REACTOR_2112004, 0).reachable(),
                "v71: no route from QA's floor (r10 -> r5)");
        assertTrue(NavProbe.probe(map, v72, v72.findRegionId(map, r10), r10, REACTOR_2112004, 0).reachable(),
                "v72: a base regular routes from it");
    }

    @Test
    void theExecutorFliesTheClientKickOnlyWhereTheOldRopeJumpMisses() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(ZPQ_ROPE_ROOM);
        Rope r261 = map.getRopes().stream().filter(r -> r.x() == 261).findFirst().orElseThrow();
        Rope r1285 = map.getRopes().stream().filter(r -> r.x() == 1285).findFirst().orElseThrow();
        BotMovementProfile base = BotMovementProfile.base();
        assertTrue(BotMovementManager.usesClientKick(map, new Point(359, -88), -1, r261, base), "98 px: client kick");
        assertFalse(BotMovementManager.usesClientKick(map, new Point(1368, -60), -1, r1285, base),
                "83 px, which the old rope jump catches: flown exactly as before");
    }

    /** Hangs a regular on rope {@code from} at {@code y}, launches the executor's rope-to-rope jump and ticks the air. */
    private static Rope flyHop(MapleMap map, Rope from, int y, Rope to, boolean v72Executor) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(7600);
        chr.setMap(map);
        chr.setPosition(new Point(from.x(), y));
        BotMovementState entry = new BotMovementState(chr, null);
        entry.flaws.off = true;
        entry.movementProfile = BotMovementProfile.base();
        entry.physX = from.x();
        entry.physY = y;
        entry.climbing = true;
        entry.climbRope = from;
        int dx = to.x() - from.x();
        if (v72Executor) {
            BotMovementManager.jumpToRope(entry, chr, dx, to);
        } else {
            BotMovementManager.jumpToRope(entry, chr, dx); // the old call: no target, the old kick
        }
        Point aim = new Point(to.x(), y);
        for (int i = 0; i < 80 && entry.inAir; i++) {
            BotMovementManager.tickAirborne(entry, aim);
        }
        return entry.climbing ? entry.climbRope : null;
    }

    @Test
    void aRegularHangingOnRope359HopsOntoRope261WithTheClientKick() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(ZPQ_ROPE_ROOM);
        Rope r359 = map.getRopes().stream().filter(r -> r.x() == 359).findFirst().orElseThrow();
        Rope r261 = map.getRopes().stream().filter(r -> r.x() == 261).findFirst().orElseThrow();
        assertEquals(r261, flyHop(map, r359, -88, r261, true), "the v72 executor catches rope 261");
        assertFalse(r261.equals(flyHop(map, r359, -88, r261, false)), "the old rope jump falls short (the b360 gap)");
    }

    @Test
    void theFallbackSwitchTurnsTheHopsOffWithoutARebuild() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(ZPQ_ROPE_ROOM);
        BotNavigationGraph v72 = build(map, true);
        Point r10 = new Point(1035, 258);
        Rope r261 = map.getRopes().stream().filter(r -> r.x() == 261).findFirst().orElseThrow();
        long flagged = v72.regions.stream().flatMap(r -> v72.getOutgoing(r.id).stream()).filter(e -> e.clientKickHop).count();
        assertTrue(flagged > 0, "the v72 hop edges carry their flag");
        assertTrue(NavProbe.probe(map, v72, v72.findRegionId(map, r10), r10, REACTOR_2112004, 0).reachable());

        NavRopeHop.set(false);
        assertFalse(NavProbe.probe(map, v72, v72.findRegionId(map, r10), r10, REACTOR_2112004, 0).reachable(),
                "switch off: the same graph plans as v71 did");
        assertFalse(BotMovementManager.usesClientKick(map, new Point(359, -88), -1, r261, BotMovementProfile.base()),
                "and the executor flies only the old rope jump");
    }

    @Test
    void aMissedRopeHopGoesIntoTheStuckLogPerEdge(@org.junit.jupiter.api.io.TempDir Path dir) {
        WzTestData.assumePresent("Map.wz");
        NavStuckLog.useForTest(dir, null);
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(ZPQ_ROPE_ROOM);
        Rope r359 = map.getRopes().stream().filter(r -> r.x() == 359).findFirst().orElseThrow();
        Rope r261 = map.getRopes().stream().filter(r -> r.x() == 261).findFirst().orElseThrow();
        NavRopeHop.set(false); // the old jump, which falls short of rope 261
        assertFalse(r261.equals(flyHop(map, r359, -88, r261, true)));
        NavStuckLog.flush();
        List<String> misses = new ArrayList<>();
        for (NavStuckLog.Spot sp : NavStuckLog.read(NavStuckLog.fileFor(dir, java.time.LocalDate.now(java.time.ZoneOffset.UTC)))) {
            if (sp.kind == NavStuckLog.Kind.ROPE_HOP_MISS) {
                misses.add(sp.mapId + " " + sp.edge);
            }
        }
        assertEquals(List.of(ZPQ_ROPE_ROOM + " ROPE_HOP x359>x261 gap 98 old-jump"), misses);

        NavRopeHop.set(true);
        assertEquals(r261, flyHop(map, r359, -88, r261, true));
        NavStuckLog.flush();
        long after = NavStuckLog.read(NavStuckLog.fileFor(dir, java.time.LocalDate.now(java.time.ZoneOffset.UTC))).stream()
                .filter(sp -> sp.kind == NavStuckLog.Kind.ROPE_HOP_MISS).mapToInt(sp -> sp.count).sum();
        assertEquals(1, after, "a caught hop logs nothing");
    }
}
