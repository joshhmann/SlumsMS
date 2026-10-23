package client.command.commands.gm3;

import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** rc6 auto-QA triage (t-bk-1): showcase bots spawned on the GM's empty ledge and never swung. */
class ShowcaseAnchorTest {

    @Test
    void theMobSpotWinsOverTheGm() {
        assertEquals(new Point(300, 150), BotShowcaseCommand.anchor(new Point(300, 150), new Point(-77, -526)));
    }

    @Test
    void noMobSpotMeansTheGmsSpot() {
        assertEquals(new Point(-77, -526), BotShowcaseCommand.anchor(null, new Point(-77, -526)));
    }
}
