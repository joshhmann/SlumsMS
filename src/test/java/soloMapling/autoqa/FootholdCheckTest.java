package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static soloMapling.autoqa.FootholdCheck.Kind;
import static soloMapling.autoqa.FootholdCheck.Pose;
import static soloMapling.autoqa.FootholdCheck.Seg;

class FootholdCheckTest {

    /* A Henesys-like strip: a flat ground at y=274, a slope going up to the right, a platform above, a wall. */
    static final List<Seg> MAP = List.of(
            new Seg(-1000, 274, 0, 274),
            new Seg(0, 274, 300, 214),      // slope: 60 px up over 300 px
            new Seg(-400, 154, -100, 154),  // a platform 120 px above the ground
            new Seg(-1000, 274, -1000, -200) // a wall: never ground
    );

    @Test
    void aBotOnTheGroundIsOk() {
        FootholdCheck.Verdict v = FootholdCheck.check(MAP, -500, 274, Pose.GROUNDED);
        assertEquals(Kind.OK, v.kind());
        assertEquals(0.0, v.dy());
    }

    @Test
    void theToleranceIsTwoPixelsEitherWay() {
        assertEquals(Kind.OK, FootholdCheck.check(MAP, -500, 276, Pose.GROUNDED).kind());
        assertEquals(Kind.OK, FootholdCheck.check(MAP, -500, 272, Pose.GROUNDED).kind());
        assertEquals(Kind.SUNK, FootholdCheck.check(MAP, -500, 277, Pose.GROUNDED).kind());
        assertEquals(Kind.FLOATING, FootholdCheck.check(MAP, -500, 271, Pose.GROUNDED).kind());
    }

    @Test
    void aBotSunkIntoTheGroundIsFlaggedWithHowDeep() {
        // the 2026-10-22 Henesys report: bots drawn waist-deep in the ground
        FootholdCheck.Verdict v = FootholdCheck.check(MAP, -500, 300, Pose.GROUNDED);
        assertEquals(Kind.SUNK, v.kind());
        assertEquals(274.0, v.groundY());
        assertEquals(26.0, v.dy());
        assertTrue(v.bad());
    }

    @Test
    void theServersFindBelowDoesNotSeeASunkBotButThisCheckDoes() {
        // FootholdTree.findBelow only looks at or below the point: under the ground there is nothing, so the
        // server's own lookups never notice. This is why the check takes the nearest line above OR below.
        FootholdTree tree = new FootholdTree(new Point(-1100, -300), new Point(400, 400));
        int id = 1;
        for (Seg s : MAP) {
            tree.insert(new Foothold(new Point(s.x1(), s.y1()), new Point(s.x2(), s.y2()), id++));
        }
        assertEquals(null, tree.findBelow(new Point(-500, 300)));
        assertEquals(Kind.SUNK, FootholdCheck.check(MAP, -500, 300, Pose.GROUNDED).kind());
    }

    @Test
    void slopesAreInterpolatedExactly() {
        // halfway up the slope: y = 274 - 30 = 244. Foothold.calculateFooting's int slope would say 274 here.
        assertEquals(244.0, FootholdCheck.nearestGroundY(MAP, 150, 244));
        assertEquals(Kind.OK, FootholdCheck.check(MAP, 150, 245, Pose.GROUNDED).kind());
        assertEquals(Kind.SUNK, FootholdCheck.check(MAP, 150, 256, Pose.GROUNDED).kind());
        assertNotEquals(244, new Foothold(new Point(0, 274), new Point(300, 214), 9).calculateFooting(150));
    }

    @Test
    void theNearestLineWinsSoAPlatformAboveDoesNotFlagABotBelowIt() {
        // under the platform at x=-200 a grounded bot stands on the ground (274), not 120 px below the platform
        assertEquals(Kind.OK, FootholdCheck.check(MAP, -200, 274, Pose.GROUNDED).kind());
        // and on the platform it is OK too
        assertEquals(Kind.OK, FootholdCheck.check(MAP, -200, 154, Pose.GROUNDED).kind());
        // sunk into the platform: nearest is the platform (10 px) not the ground (110 px)
        FootholdCheck.Verdict v = FootholdCheck.check(MAP, -200, 164, Pose.GROUNDED);
        assertEquals(Kind.SUNK, v.kind());
        assertEquals(154.0, v.groundY());
    }

    @Test
    void airborneClimbingSwimmingCoarseAndUnknownBotsAreNotChecked() {
        for (Pose p : List.of(Pose.AIRBORNE, Pose.CLIMBING, Pose.SWIMMING, Pose.COARSE, Pose.UNKNOWN)) {
            FootholdCheck.Verdict v = FootholdCheck.check(MAP, -500, 100, p);
            assertEquals(Kind.SKIPPED, v.kind(), p.name());
            assertFalse(v.bad());
        }
    }

    @Test
    void wallsAreNeverGroundAndNoFootholdIsItsOwnFinding() {
        assertEquals(Kind.NO_GROUND, FootholdCheck.check(MAP, 900, 274, Pose.GROUNDED).kind());
        assertTrue(Double.isNaN(FootholdCheck.nearestGroundY(List.of(new Seg(5, 0, 5, 100)), 5, 50)));
    }

    @Test
    void stancesOfUndrivenBotsMapToPoses() {
        assertEquals(Pose.GROUNDED, GameHealthSource.poseFromStance(4, false));  // stand right
        assertEquals(Pose.GROUNDED, GameHealthSource.poseFromStance(5, false));  // stand left
        assertEquals(Pose.GROUNDED, GameHealthSource.poseFromStance(20, false)); // sit
        assertEquals(Pose.AIRBORNE, GameHealthSource.poseFromStance(6, false));  // jump
        assertEquals(Pose.CLIMBING, GameHealthSource.poseFromStance(14, false)); // ladder
        assertEquals(Pose.CLIMBING, GameHealthSource.poseFromStance(17, false)); // rope, facing left
        assertEquals(Pose.SWIMMING, GameHealthSource.poseFromStance(4, true));
    }

    @Test
    void onASwimMapEveryBotIsSwimmingWhateverTheDriverSays() {
        // rc5 sweep: Aquarium (230000000) and Aqua Road bots moved over the water graph by the unobserved (coarse)
        // driver never set its swimming flag, and were flagged 50-100 px "floating".
        assertEquals(Pose.SWIMMING, GameHealthSource.pose("GROUNDED", 4, true));
        assertEquals(Pose.SWIMMING, GameHealthSource.pose(null, 4, true));
        assertEquals(Pose.GROUNDED, GameHealthSource.pose("GROUNDED", 6, false), "the driver's pose beats the stance");
        assertEquals(Pose.COARSE, GameHealthSource.pose("COARSE", 4, false));
        assertEquals(Pose.AIRBORNE, GameHealthSource.pose(null, 6, false));
    }
}
