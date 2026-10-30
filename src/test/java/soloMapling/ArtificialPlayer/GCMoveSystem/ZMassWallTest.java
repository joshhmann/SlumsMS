package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.maps.Foothold;
import server.maps.MapleMap;
import soloMapling.WzTestData;

import java.awt.Point;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * NN-011, client wall collidability by zMass group (ported from nutnnut/Cosmic 43b9f06c8, their graph v70 part a,
 * AGPL-3.0. Credit: NutNNut; tests adapted from their BotDeityRoomSharedGroundTest and the 193000000 wall test).
 *
 * The Deity Room (600020100): the r73 structure's 420 px cliff wall stands at x=-1315 with its foot on the floor, and
 * the floor foothold runs under it unbroken. In the real client (upstream's v83 disassembly, live-checked both ways,
 * walking and in the air) a floor walker passes it: walking scans no walls, only the foothold chain, and in the air a
 * wall collides only for movers of its own zMass group or the map's base group. Our heuristic
 * (Foothold.isCollidableWall: a wall whose lower end chains into ground) blocks every ground step across it, so floor
 * bots heading right froze at x=-1316.
 */
class ZMassWallTest {

    private static final int DEITY_ROOM = 600020100;
    private static final Point ST01 = new Point(541, 155); // portal at the far right

    @TempDir
    Path dir;

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    @BeforeEach
    void scratch() {
        NavEdgeLearning.useDirForTest(dir.resolve("learned"));
        NavStuckLog.useForTest(dir.resolve("stuck"), null);
    }

    @AfterEach
    void restore() {
        NavPortSwitches.zmassWalls = false;
        NavEdgeLearning.useDirForTest(null);
        NavStuckLog.useForTest(null, null);
    }

    private static MapleMap deityRoom() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(DEITY_ROOM);
        BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        return map;
    }

    @Test
    void theLoaderReadsEachFootholdsZMassGroup() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(DEITY_ROOM);
        long grouped = map.getFootholds().getAllFootholds().stream().filter(f -> f.getZMass() >= 0).count();
        assertEquals(map.getFootholds().getAllFootholds().size(), grouped, "every WZ foothold has a group");
        assertTrue(map.getFootholds().getAllFootholds().stream().map(Foothold::getZMass).distinct().count() > 1,
                "the room has more than one structure group");
    }

    @Test
    void withTheSwitchOffTheTowerWallBlocksAFloorWalker() {
        MapleMap map = deityRoom();
        assertTrue(BotPhysicsEngine.isGroundStepBlockedByWall(map, new Point(-1320, 156), 6),
                "the old heuristic blocks the floor at x=-1315");
    }

    @Test
    void groundWalkIsNeverBlockedByWallsAtTheOverlap() {
        NavPortSwitches.zmassWalls = true;
        MapleMap map = deityRoom();
        assertFalse(BotPhysicsEngine.isGroundStepBlockedByWall(map, new Point(-1320, 156), 6),
                "a foreign structure's wall must not block a floor walker heading right");
        assertTrue(BotPhysicsEngine.canWalkGroundStep(map, new Point(-1320, 156), 6),
                "the floor step across the overlap is a plain ground step");
        assertFalse(BotPhysicsEngine.isGroundStepBlockedByWall(map, new Point(-1313, 156), -6),
                "standing on the tower's foot, walking left onto the floor is not a wall stop either");
    }

    private static int furthestRight(MapleMap map, int ticks) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(7500);
        chr.setMap(map);
        Point start = BotPhysicsEngine.findGroundPoint(map, new Point(-1360, 150));
        chr.setPosition(new Point(start));
        BotMovementState entry = new BotMovementState(chr, null);
        entry.flaws.off = true;
        entry.movementProfile = BotMovementProfile.base();
        entry.physX = start.x;
        entry.physY = start.y;
        int best = Integer.MIN_VALUE;
        for (int i = 0; i < ticks && best < -1240; i++) {
            boolean runAiTick = i % 2 == 0;
            BotNavigationManager.NavigationDirective nav = BotNavigationManager.resolveTarget(entry, ST01, runAiTick);
            if (!nav.consumedTick) {
                if (entry.climbing) {
                    BotMovementManager.tickClimbing(entry, nav.targetPos, runAiTick);
                } else if (entry.inAir) {
                    BotMovementManager.tickAirborne(entry, nav.targetPos);
                } else {
                    BotMovementManager.tickGrounded(entry, nav.targetPos);
                }
            }
            if (runAiTick && !entry.inAir && !entry.climbing) {
                BotNavigationManager.tryExecuteCommittedEdgeAfterGroundMovement(entry, ST01);
            }
            best = Math.max(best, entry.bot.getPosition().x);
        }
        return best;
    }

    /* Upstream's VonLaugh case: a floor bot travelling right froze at x=-1316 for its whole travel budget. */
    @Test
    void withTheSwitchOffAFloorBotHeadingRightFreezesAtTheWall() {
        MapleMap map = deityRoom();
        int best = furthestRight(map, 300);
        assertTrue(best < -1240, "the old heuristic was expected to stop the bot at the wall, it reached x=" + best);
    }

    @Test
    void aFloorBotHeadingRightCrossesTheOverlap() {
        NavPortSwitches.zmassWalls = true;
        MapleMap map = deityRoom();
        int best = furthestRight(map, 300);
        assertTrue(best >= -1240, "the bot should cross x=-1315 heading right, but only reached x=" + best);
    }

    /* 193000000's bottom-anchored shaft walls belong to the structure of the ground at their feet, so a mover
     * launching from that ground still collides with them under the zMass rule. */
    @Test
    void ownStructureWallsStillCollide() {
        NavPortSwitches.zmassWalls = true;
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(193000000);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        Map<Integer, Foothold> byId = new HashMap<>();
        for (Foothold f : map.getFootholds().getAllFootholds()) {
            byId.put(f.getId(), f);
        }
        for (int wallId : new int[]{2, 10, 13}) {
            Foothold wall = byId.get(wallId);
            assertNotNull(wall, "fixture: wall " + wallId);
            assertTrue(wall.isWall(), "fixture: foothold " + wallId + " is a wall");
            Foothold prev = byId.get(wall.getPrev());
            Foothold next = byId.get(wall.getNext());
            Foothold ground = prev != null && !prev.isWall() ? prev : next != null && !next.isWall() ? next : null;
            assertNotNull(ground, "fixture: wall " + wallId + " chains into ground");
            int regionId = graph.regionIdByFootholdId.getOrDefault(ground.getId(), -1);
            assertTrue(regionId >= 0, "fixture: a region for the ground under wall " + wallId);
            assertTrue(BotPhysicsEngine.wallCollidesForLaunch(map, wall, graph.getRegion(regionId)),
                    "wall " + wallId + " collides for a mover of its own structure");
        }
    }

    @Test
    void theAirborneRule() {
        int base = 0;
        assertTrue(BotPhysicsEngine.wallCollidesForMover(base, 0, 3), "base-group walls collide for everyone");
        assertTrue(BotPhysicsEngine.wallCollidesForMover(base, 3, 3), "own-group walls collide");
        assertFalse(BotPhysicsEngine.wallCollidesForMover(base, 2, 3), "another structure's walls do not");
        assertFalse(BotPhysicsEngine.wallCollidesForMover(base, 2, BotPhysicsEngine.MOVER_ZMASS_UNKNOWN),
                "a mover that never stood anywhere only meets base walls");
        assertTrue(BotPhysicsEngine.wallCollidesForMover(base, 2, BotPhysicsEngine.MOVER_ZMASS_ALL));
        assertTrue(BotPhysicsEngine.wallCollidesForMover(base, -1, 3), "an ungrouped wall collides");
        assertTrue(BotPhysicsEngine.wallCollidesForMover(-1, 2, 3), "a map with no groups: every wall collides");
    }
}
