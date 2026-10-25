package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import soloMapling.WzTestData;

import java.awt.Point;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Ground continuity across overlapping foothold chains (ported from nutnnut/Cosmic 235b6aefa, AGPL-3.0.
 * Credit: NutNNut; their BotMovementSimulationLabTest#shouldMoveClawerTowardLeaderOnNinjaCastleSameRegionTransitFollow).
 *
 * Map 801010000 (Zipangu): the long flat floor (layer 5, y=155) runs under a short ramp chain (layer 3: wall fh61 at
 * x=1315, flat fh62 1315..1350 at y=155, ramp fh60 up to (1440,95)). Over x 1315..1350 both chains sit on the exact
 * same pixels. Nav keeps the region the bot walked in on (v67 lastRegionId continuity), but physics re-resolved the
 * raw foothold, which picks the ramp chain: walking left it ends at the wall, so the step was refused, the walk edge
 * was cleared, and the bot parked on one pixel for good. Several bots doing that = same-pixel stacks.
 */
class BotOverlapGroundContinuityTest {

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    /* {id, x1,y1, x2,y2, prev, next}. The ramp chain is inserted first so the tree resolves the shared pixels to it,
     * as the real map does. */
    private static final int[][] FH = {
            {61, 1315, 180, 1315, 155, 0, 62},   // wall at the ramp chain's left end
            {62, 1315, 155, 1350, 155, 61, 60},  // ramp chain flat foot: overlaps the floor exactly
            {60, 1350, 155, 1440, 95, 62, 0},    // ramp up
            {80, 900, 155, 1080, 155, 0, 81},    // the long floor
            {81, 1080, 155, 1260, 155, 80, 94},
            {94, 1260, 155, 1350, 155, 81, 92},
            {92, 1350, 155, 1440, 155, 94, 96},
            {96, 1440, 155, 1620, 155, 92, 0},
    };

    private static MapleMap buildMap() {
        MapleMap map = new MapleMap(801010000, 0, 0, 801010000, 1.0f);
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        for (int[] f : FH) {
            Foothold fh = new Foothold(new Point(f[1], f[2]), new Point(f[3], f[4]), f[0]);
            fh.setPrev(f[5]);
            fh.setNext(f[6]);
            tree.insert(fh);
        }
        map.setFootholds(tree);
        return map;
    }

    private static BotMovementState botAt(MapleMap map, BotNavigationGraph graph, Point pos) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(424243);
        chr.setMap(map);
        chr.setPosition(new Point(pos));
        BotMovementState entry = new BotMovementState(chr, null);
        entry.flaws.off = true;
        entry.movementProfile = BotMovementProfile.base();
        entry.navGraph = graph;
        entry.physX = pos.x;
        entry.physY = pos.y;
        return entry;
    }

    private static int walkTowards(BotMovementState entry, Point target, int ticks) {
        for (int i = 0; i < ticks; i++) {
            BotMovementManager.tickGrounded(entry, target);
        }
        return entry.bot.getPosition().x;
    }

    @Test
    void fixtureReproducesTheOverlap() {
        MapleMap map = buildMap();
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        int floor = graph.regionIdByFootholdId.get(94);
        int ramp = graph.regionIdByFootholdId.get(62);
        assertNotEquals(floor, ramp, "two separate chains");
        assertEquals(62, BotPhysicsEngine.findGroundFoothold(map, new Point(1330, 155)).getId(),
                "the raw lookup picks the ramp chain on the shared pixels");
        assertEquals(62, BotPhysicsEngine.findGroundFoothold(map, new Point(1315, 155)).getId(),
                "including the wall corner");
    }

    @Test
    void aBotThatWalkedInOnTheFloorKeepsWalkingAcrossTheOverlap() {
        MapleMap map = buildMap();
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        BotMovementState entry = botAt(map, graph, new Point(1315, 155));
        entry.lastRegionId = graph.regionIdByFootholdId.get(94); // nav: it walked in on the floor

        int x = walkTowards(entry, new Point(1000, 155), 40);

        assertTrue(x < 1300, "walked left past the ramp chain's wall on the floor, x=" + x);
        assertEquals(155, entry.bot.getPosition().y);
    }

    @Test
    void theGroundFootholdFollowsTheNavRegion() {
        MapleMap map = buildMap();
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        BotMovementState entry = botAt(map, graph, new Point(1330, 155));
        entry.lastRegionId = graph.regionIdByFootholdId.get(94);
        Foothold fh = BotPhysicsEngine.syncAndDetectGround(entry, entry.bot);
        assertNotNull(fh);
        assertEquals(94, fh.getId(), "the floor chain, not the ramp foot");

        entry.lastRegionId = graph.regionIdByFootholdId.get(62); // walked in down the ramp: stay on the ramp
        assertEquals(62, BotPhysicsEngine.syncAndDetectGround(entry, entry.bot).getId());
    }

    @Test
    void withNoRegionHistoryTheRawLookupStillApplies() {
        MapleMap map = buildMap();
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        BotMovementState entry = botAt(map, graph, new Point(1330, 155));
        entry.lastRegionId = -1;
        assertEquals(62, BotPhysicsEngine.syncAndDetectGround(entry, entry.bot).getId());
        // A region id that no longer covers the bot (it walked off it) falls back to the raw lookup too.
        entry.lastRegionId = graph.regionIdByFootholdId.get(62);
        entry.bot.setPosition(new Point(1100, 155));
        entry.physX = 1100;
        assertEquals(81, BotPhysicsEngine.syncAndDetectGround(entry, entry.bot).getId());
    }

    @Test
    void realZipanguFloorIsWalkableAcrossTheOverlap() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(801010000);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        int floor = graph.findRegionId(map, new Point(-899, 155));
        int raw = graph.findRegionId(map, new Point(1315, 155));
        assertNotEquals(floor, raw, "raw lookup at x=1315 picks the overlapping ramp chain");
        BotMovementState entry = botAt(map, graph, new Point(1315, 155));
        entry.lastRegionId = floor;

        int x = walkTowards(entry, new Point(-839, 155), 40);

        assertTrue(x < 1300, "walked left on the floor region, x=" + x);
    }
}
