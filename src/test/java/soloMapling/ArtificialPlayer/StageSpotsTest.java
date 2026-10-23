package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * 1.5.0 rc6 (QA t-pb-1): "when we spawn players they should be a bit spread out so they're all not lined up". Staged
 * bots go either side of the GM at random distances, on the ground, never on top of each other.
 */
class StageSpotsTest {

    /** A flat floor at y 300 from x 0 to 2000, a ledge at y 180 from 600 to 800, nothing else. */
    static Point ground(int x, int y) {
        if (x >= 600 && x <= 800 && y <= 180) {
            return new Point(x, 180);
        }
        return x >= 0 && x <= 2000 ? new Point(x, 300) : null;
    }

    @Test
    void sixStagedBotsSpreadBothSidesOnTheGroundWithRoomBetween() {
        Point gm = new Point(1000, 300);
        List<Point> taken = new ArrayList<>(List.of(gm));
        Random rng = new Random(7);
        Set<Integer> sides = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            Point p = StageSpots.pick(gm, taken, rng, StageSpotsTest::ground);
            assertEquals(300, p.y, "on the floor, not floating at some other height");
            for (Point t : taken) {
                assertTrue(Math.abs(t.x - p.x) >= StageSpots.MIN_GAP, p + " too close to " + t);
            }
            sides.add(Integer.signum(p.x - gm.x));
            taken.add(p);
        }
        assertTrue(sides.contains(1) && sides.contains(-1), "both sides of the GM, not a row to one side");
        Set<Integer> gaps = new HashSet<>();
        for (int i = 2; i < taken.size(); i++) {
            gaps.add(Math.abs(taken.get(i).x - taken.get(i - 1).x));
        }
        assertTrue(gaps.size() > 2, "uneven spacing, not a fixed-step line: " + taken);
    }

    @Test
    void aGmAtTheMapEdgeStillGetsGroundedSpots() {
        Point gm = new Point(20, 300);
        Point p = StageSpots.pick(gm, List.of(gm), new Random(1), StageSpotsTest::ground);
        assertEquals(300, p.y);
        assertTrue(p.x >= 0, "never off the map's edge into the void");
    }

    @Test
    void noGroundAtAllFallsBackToTheCentre() {
        Point gm = new Point(5, 5);
        assertEquals(gm, StageSpots.pick(gm, List.of(), new Random(1), (x, y) -> null));
    }

    @Test
    void everyMultiBotStagingUsesIt() throws Exception {
        for (String f : List.of("BotAffinitySystem/AffinityStaging.java", "BotDramaSystem/DramaStaging.java",
                "BotCoupleSystem/CoupleStaging.java", "BotCoupleSystem/BotWeddings.java", "BotDramaSystem/DramaKit.java")) {
            String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/soloMapling/ArtificialPlayer/" + f));
            assertTrue(src.contains("StageSpots.near"), f + " spreads its bots");
        }
        String qa = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/soloMapling/qa/GameQaWorld.java"));
        assertTrue(qa.contains("StageSpots.near(map, around)"), "!qa idle bots too");
    }
}
