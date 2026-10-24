package soloMapling.ArtificialPlayer.BotCombatSystem;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rc6 auto-QA triage (t-cb-2, 25 of 52 positions "inside a mob or facing away"): the POSITION read-out took the
 * nearest mob from the whole map, ledges above and below included. Only the bot's own ledge counts.
 */
class CombatStagingPositionTest {

    static CombatStaging.MobAt mob(String name, int x, int y, boolean own) {
        return new CombatStaging.MobAt(name, new Point(x, y), new Rectangle(x - 20, y - 40, 40, 40), own);
    }

    @Test
    void aMobOnAnotherLedgeIsNotTheNearest() {
        Point bot = new Point(289, -85);
        // a snail on the ledge below, right under the bot and behind it; the one it fights is ahead on its own ledge
        String line = CombatStaging.positionLine(bot, false, List.of(mob("Red Snail", 280, -25, false),
                mob("Pig", 360, -85, true)));
        assertTrue(line.contains("nearest Pig dx 71, facing it"), line);
        assertFalse(line.contains("INSIDE") || line.contains("FACING AWAY"), line);
    }

    @Test
    void ownLedgeFaultsStillShow() {
        Point bot = new Point(500, -325);
        assertTrue(CombatStaging.positionLine(bot, true, List.of(mob("Pig", 560, -325, true))).contains("FACING AWAY"));
        assertTrue(CombatStaging.positionLine(bot, false, List.of(mob("Pig", 505, -325, true))).contains("INSIDE its body"));
        assertEquals("at (500,-325) facing left, no mob on its ledge",
                CombatStaging.positionLine(bot, true, List.of(mob("Pig", 505, -85, false))));
    }

    @Test
    void ownLedgeIsTheSameFloorWithinASlope() {
        CombatStaging.LedgeSplit flat = (ax, ay, bx, by) -> false;
        assertTrue(CombatStaging.onOwnLedge(new Point(0, 0), new Point(100, 60), flat));
        assertFalse(CombatStaging.onOwnLedge(new Point(0, 0), new Point(100, 240), flat), "a ledge 240 px down");
        assertFalse(CombatStaging.onOwnLedge(new Point(0, 0), new Point(100, 0), (ax, ay, bx, by) -> true),
                "the nav graph splits them");
    }
}
