package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/*
 * t-cb-2 on TEST (2026-10-24) sampled a level-10 bowman 13 times inside a mob's body in a 52-sample check: its TOUCH
 * step carried it out of one snail to x 457 and the kite step walked it back to x 335, through that same snail, 250 ms
 * later. The step destinations now avoid every mob body on the bot's ledge (and the two steps share one cadence).
 */
class RepositionClearanceTest {

    static final int GROUND = -85;

    @Test
    void aStepDestinationInsideAnotherMobIsMovedPastIt() {
        // snail body x 340..372, the wanted kite destination x 350 sits inside it
        Rectangle snail = new Rectangle(340, GROUND - 24, 32, 24);
        assertEquals(372, EngageBeat.clearOf(List.of(snail), GROUND, 350, 1), "one step of 22 px past its right edge");
        assertEquals(328, EngageBeat.clearOf(List.of(snail), GROUND, 350, -1), "one step of 22 px past its left edge");
    }

    @Test
    void aClearDestinationIsLeftAlone() {
        Rectangle snail = new Rectangle(340, GROUND - 24, 32, 24);
        assertEquals(500, EngageBeat.clearOf(List.of(snail), GROUND, 500, 1));
        // the same x on another ledge is not in the way (bodies listed are on the bot's ledge only, in clearOfMobs)
        assertEquals(350, EngageBeat.clearOf(List.of(), GROUND, 350, 1));
    }

    @Test
    void aPackWithNoGapKeepsTheWantedSpot() {
        // a wall of bodies either side of the wanted x: nothing clear within the scan, so the step stays as planned
        Rectangle wall = new Rectangle(0, GROUND - 60, 400, 60);
        assertEquals(200, EngageBeat.clearOf(List.of(wall), GROUND, 200, 1));
    }
}
