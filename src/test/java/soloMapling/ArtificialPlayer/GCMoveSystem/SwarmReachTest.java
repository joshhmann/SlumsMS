package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0 world events: on an invasion map a near mob lands its hit even when nobody moves it into the bot. */
class SwarmReachTest {

    @Test
    void aMobWithinReachHitsAndOneFurtherOffDoesNot() {
        Point bot = new Point(100, 200);
        assertTrue(BotContactDamage.withinSwarmReach(bot, new Point(100 + BotContactDamage.SWARM_REACH_X, 200)));
        assertTrue(BotContactDamage.withinSwarmReach(bot, new Point(40, 200 - BotContactDamage.SWARM_REACH_Y)));
        assertFalse(BotContactDamage.withinSwarmReach(bot, new Point(100 + BotContactDamage.SWARM_REACH_X + 1, 200)));
        assertFalse(BotContactDamage.withinSwarmReach(bot, new Point(100, 200 + BotContactDamage.SWARM_REACH_Y + 1)),
                "a mob on the platform above is not in reach");
        assertFalse(BotContactDamage.withinSwarmReach(null, bot));
        assertFalse(BotContactDamage.withinSwarmReach(bot, null));
    }
}
