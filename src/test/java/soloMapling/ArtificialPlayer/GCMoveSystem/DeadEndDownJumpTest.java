package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import server.maps.MapleMap;
import soloMapling.WzTestData;
import testutil.PropsRestored;

import java.awt.Point;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.5 b344c (graph v71), on the real WZ geometry ({@code -Dwz-path}; skipped without it).
 *
 * <p>Zakum PQ's big room 280011005: the party walks in on the top-right floor (y 78, x 33..843), whose only walk-only
 * way out is a 360 px down-jump to the floor below. The v60 down-jump cap (200 px) left no edge, so a miner without
 * Teleport or Flash Jump never reached ore rock 2112011 at (208, 1217) or the big chest 2112014 at (380, 440), and the
 * run stalled on stage 31 (TEST, 6 Nov 19:35). The second test is the Coordinator's regression check: on ordinary
 * maps v71 adds no edge and changes no walker's route.
 */
@ExtendWith(PropsRestored.class)
class DeadEndDownJumpTest {

    private static final int BIG_ROOM = 280011005;

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    private static final boolean DEFAULT = BotNavigationGraphProvider.deadEndDownJumps;

    @AfterEach
    void restore() {
        BotNavigationGraphProvider.deadEndDownJumps = DEFAULT;
    }

    private static BotNavigationGraph graph(MapleMap map) {
        return BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
    }

    @Test
    void aWalkerGetsFromTheBigRoomsDoorDownToItsOreRockAndTheBigChestAndBackOut() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(BIG_ROOM);
        BotNavigationGraph g = graph(map);
        Point door = map.getPortal("out00").getPosition();
        Point rock = new Point(208, 1217);
        Point chest = new Point(380, 440);
        NavProbe.Result toRock = NavProbe.probe(map, g, door, rock, 0);
        assertTrue(toRock.reachable(), "door -> ore rock 2112011, walking: " + toRock);
        NavProbe.Result toChest = NavProbe.probe(map, g, door, chest, 0);
        assertTrue(toChest.reachable(), "door -> big chest 2112014, walking: " + toChest);
        NavProbe.Result out = NavProbe.probe(map, g, rock, door, 0);
        assertTrue(out.reachable(), "ore rock -> door, walking: " + out);
    }

    @Test
    void onOrdinaryMapsTheGraphAndEveryWalkersRouteAreUnchanged() {
        WzTestData.assumePresent("Map.wz");
        // towns, fields, a tall tree, a dungeon, a sky map, a tower; plus the big room, where only its dead-end floor may change
        int[] maps = {100000000, 101000000, 102000000, 103000000, 104000000, 200000000, 220000000, 240000000,
                101010100, 104040000, 105040300, 211040200, 220050300, 230000000, 280010000, BIG_ROOM};
        int compared = 0;
        // only a dead-end floor a portal stands on may gain its down-jump; Lith Harbor's crate hollow (no portal) does not
        for (int id : maps) {
            MapleMap map = BotNavigationMapLoader.loadMapGeometry(id);
            BotNavigationGraphProvider.deadEndDownJumps = false;
            BotNavigationGraph before = graph(map);
            BotNavigationGraphProvider.deadEndDownJumps = true;
            BotNavigationGraph after = graph(map);

            Set<String> b = keys(before), a = keys(after);
            assertTrue(a.containsAll(b), id + ": v71 removed an edge");
            for (String k : a) {
                if (!b.contains(k)) {
                    int from = Integer.parseInt(k.substring(0, k.indexOf('>')));
                    assertFalse(walkOnlyExit(before, from), id + ": v71 added " + k + " to a floor that had a way out");
                    assertTrue(k.contains("DROP"), id + ": v71 added a non-drop edge " + k);
                }
            }
            if (id != BIG_ROOM) {
                assertEquals(b, a, id + ": an ordinary map's graph changed");
            }
            if (id == BIG_ROOM) {
                continue; // its routes through the entry floor are meant to change: that floor is the fix
            }
            // every walker's route between two ground floors that had one keeps its cost
            List<BotNavigationGraph.Region> ground = before.regionsById.values().stream()
                    .filter(r -> !r.isRopeRegion).toList();
            for (BotNavigationGraph.Region r1 : ground) {
                for (BotNavigationGraph.Region r2 : ground) {
                    if (r1 == r2) {
                        continue;
                    }
                    Point p1 = r1.pointAt((r1.minX + r1.maxX) / 2), p2 = r2.pointAt((r2.minX + r2.maxX) / 2);
                    NavProbe.Result was = NavProbe.probe(map, before, r1.id, p1, p2, 0);
                    if (!was.reachable()) {
                        continue;
                    }
                    NavProbe.Result now = NavProbe.probe(map, after, r1.id, p1, p2, 0);
                    assertEquals(was.cost(), now.cost(), id + " r" + r1.id + "->r" + r2.id + " route changed: " + now);
                    compared++;
                }
            }
        }
        assertTrue(compared > 100, "routes compared: " + compared);
    }

    private static Set<String> keys(BotNavigationGraph g) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<Integer, List<BotNavigationGraph.Edge>> e : g.outgoingByRegionId.entrySet()) {
            for (BotNavigationGraph.Edge edge : e.getValue()) {
                out.add(edge.fromRegionId + ">" + edge.toRegionId + " " + edge.type + " " + edge.startPoint.x + ","
                        + edge.startPoint.y + "->" + edge.endPoint.x + "," + edge.endPoint.y + " c" + edge.cost);
            }
        }
        return out;
    }

    private static boolean walkOnlyExit(BotNavigationGraph g, int region) {
        return BotNavigationGraphProvider.hasWalkOnlyExit(region, g.outgoingByRegionId);
    }
}
