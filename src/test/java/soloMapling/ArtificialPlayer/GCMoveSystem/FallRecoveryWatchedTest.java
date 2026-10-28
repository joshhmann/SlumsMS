package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 1.6.1 QA: a watched bot that fell off the map is set down under where it fell, not under a far-away goal. */
class FallRecoveryWatchedTest {
    @Test
    void watchedBotIgnoresItsGoalSoItReappearsWhereItFell() {
        assertNull(GCMovementDriver.fallRecoveryGoal(new Point(900, 10), new Point(-500, 0), true));
    }

    @Test
    void unwatchedBotKeepsGoalFirstPick() {
        assertEquals(new Point(900, 10), GCMovementDriver.fallRecoveryGoal(new Point(900, 10), new Point(-500, 0), false));
        assertEquals(new Point(-500, 0), GCMovementDriver.fallRecoveryGoal(null, new Point(-500, 0), false));
    }
}
