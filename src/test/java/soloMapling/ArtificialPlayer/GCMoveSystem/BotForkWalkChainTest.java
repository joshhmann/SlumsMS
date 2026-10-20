package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import server.maps.Rope;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Graph v66 (ported from nutnnut/Cosmic BotRegion11ForkOscillationTest, AGPL-3.0. Credit: NutNNut).
 * Map 100040000 region 11 forks at vertex (883,294) into an upper ridge A (the next-chain to the rope) and a
 * lower dead-end spur B that overlaps it in x. The ground walk must follow the standing foothold's prev/next
 * chain across the fork, not snap down onto the spur.
 */
class BotForkWalkChainTest {

    private static final int MAP = 100040000;

    /* Exact region-11 footholds from 100040000.img: {id, x1,y1, x2,y2, prev, next}. */
    private static final int[][] FH = {
            {160, 594, 404, 630, 391, 0, 161},
            {161, 630, 391, 674, 390, 160, 162},
            {162, 674, 390, 679, 391, 161, 163},
            {163, 679, 391, 702, 387, 162, 164},
            {164, 702, 387, 725, 381, 163, 165},
            {165, 725, 381, 756, 371, 164, 166},
            {166, 756, 371, 776, 363, 165, 167},
            {167, 776, 363, 796, 353, 166, 168},
            {168, 796, 353, 822, 335, 167, 169},
            {169, 822, 335, 844, 316, 168, 170},
            {171, 826, 304, 862, 291, 0, 172},
            {170, 844, 316, 866, 292, 169, 173},
            {172, 862, 291, 866, 292, 171, 173},
            {173, 866, 292, 883, 294, 172, 178}, // stem; next continues UP ridge A (178)
            {177, 883, 294, 891, 295, 173, 174}, // spur B start
            {178, 883, 294, 911, 287, 173, 179}, // ridge A start
            {174, 891, 295, 921, 295, 177, 175},
            {179, 911, 287, 934, 279, 178, 180},
            {175, 921, 295, 932, 293, 174, 176},
            {176, 932, 293, 964, 300, 175, 0},   // spur B dead-end (next=0)
            {180, 934, 279, 954, 268, 179, 181},
            {181, 954, 268, 982, 241, 180, 184},
            {182, 969, 248, 981, 240, 0, 183},
            {183, 981, 240, 982, 241, 182, 184},
            {184, 982, 241, 998, 240, 183, 185},
            {185, 998, 240, 1005, 235, 184, 186},
            {186, 1005, 235, 1028, 236, 185, 187},
            {187, 1028, 236, 1037, 233, 186, 188},
            {188, 1037, 233, 1079, 235, 187, 189}, // CLIMB launch foothold
            {189, 1079, 235, 1087, 232, 188, 190},
            {190, 1087, 232, 1113, 237, 189, 0},
    };

    private static MapleMap buildRegion11() {
        MapleMap map = new MapleMap(MAP, 0, 0, MAP, 1.0f);
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        for (int[] f : FH) {
            Foothold fh = new Foothold(new Point(f[1], f[2]), new Point(f[3], f[4]), f[0]);
            fh.setPrev(f[5]);
            fh.setNext(f[6]);
            tree.insert(fh);
        }
        map.setFootholds(tree);
        map.addRope(new Rope(1072, 63, 199, false));
        return map;
    }

    private static Foothold foothold(MapleMap map, int id) {
        for (Foothold fh : map.getFootholds().getAllFootholds()) {
            if (fh.getId() == id) {
                return fh;
            }
        }
        throw new AssertionError("no foothold " + id);
    }

    @Test
    void spurAndRidgeStayOneRegion() {
        MapleMap map = buildRegion11();
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map);
        assertEquals(graph.regionIdByFootholdId.get(178), graph.regionIdByFootholdId.get(174),
                "spur -> ridge across the fork is a real walk path, so the arms stay one region");
    }

    @Test
    void walkUpTheRidgeFollowsTheChainPastTheFork() {
        MapleMap map = buildRegion11();
        BotNavigationGraphProvider.rebuildGraph(map);
        Foothold fh = foothold(map, 164);
        Point pos = new Point(705, 386);
        for (int x = 708; x <= 1040; x += 3) {
            BotPhysicsEngine.GroundRegionSample sample = BotPhysicsEngine.walkRegionGroundSample(map, fh, x, pos.y);
            assertNotNull(sample, "lost ground walking the ridge at x=" + x + " from fh" + fh.getId());
            int id = sample.foothold().getId();
            assertTrue(id != 177 && id != 174 && id != 175 && id != 176,
                    "walk snapped down onto dead-end spur fh" + id + " at x=" + x);
            fh = sample.foothold();
            pos = sample.point();
        }
        assertTrue(pos.y <= 240, "reached the upper ridge near the climb launch, y=" + pos.y);
    }

    @Test
    void walkFromTheSpurStillClimbsOntoTheRidge() {
        MapleMap map = buildRegion11();
        BotNavigationGraphProvider.rebuildGraph(map);
        // Walking LEFT along the spur, the chain leads back to the stem (177.prev = 173), then onto the ridge.
        Foothold fh = foothold(map, 174);
        Point pos = new Point(905, 295);
        for (int x = 902; x >= 870; x -= 3) {
            BotPhysicsEngine.GroundRegionSample sample = BotPhysicsEngine.walkRegionGroundSample(map, fh, x, pos.y);
            assertNotNull(sample, "lost ground walking the spur at x=" + x);
            fh = sample.foothold();
            pos = sample.point();
        }
        assertEquals(173, fh.getId(), "spur walk follows prev links back to the stem");
    }
}
