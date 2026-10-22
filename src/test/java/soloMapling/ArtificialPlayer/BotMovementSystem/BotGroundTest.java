package soloMapling.ArtificialPlayer.BotMovementSystem;

import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementStructures.MovementRecording;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static soloMapling.ArtificialPlayer.BotMovementSystem.InPacketReader.getMovementRecording;

/**
 * 1.5.0 rc5: town bots half sunk into Henesys. The footholds are Henesys's (Map.wz 100000000) around the bots of the
 * owner's screenshot, and the sunk points are those bots' stored positions on the test server.
 */
class BotGroundTest {

    // {id, x1, y1, x2, y2}: the raised ledge (y 274) left of x 938, the main ground (y 334) right of it, the two hay
    // bales (y 275 and 220) and the platform above them (y 94).
    private static final int[][] HENESYS = {
            {78, 720, 274, 810, 274}, {79, 810, 274, 862, 274}, {82, 862, 274, 900, 274}, {84, 900, 274, 938, 274},
            {86, 938, 274, 938, 300}, {87, 938, 300, 938, 334},
            {88, 938, 334, 990, 334}, {91, 990, 334, 1080, 334}, {97, 1080, 334, 1170, 334},
            {102, 1170, 334, 1260, 334}, {107, 1260, 334, 1350, 334},
            {95, 1076, 275, 1275, 275}, {99, 1129, 220, 1240, 220},
            {104, 1222, 94, 1260, 94}, {106, 1260, 94, 1298, 94},
    };

    private static FootholdTree henesys() {
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        for (int[] f : HENESYS) {
            tree.insert(new Foothold(new Point(f[1], f[2]), new Point(f[3], f[4]), f[0]));
        }
        return tree;
    }

    @Test
    void theScreenshotBotsGoBackOnTheirFootholds() {
        FootholdTree fh = henesys();
        assertAll(
                () -> assertEquals(new Point(1148, 334), BotGround.settle(fh, new Point(1148, 363)),
                        "NachoHat08, 29 px inside the main ground"),
                () -> assertEquals(new Point(813, 274), BotGround.settle(fh, new Point(813, 303)),
                        "MochiUltra, 29 px inside the raised ledge"),
                () -> assertEquals(new Point(1227, 94), BotGround.settle(fh, new Point(1227, 122)),
                        "CandyClock, 28 px under the platform it stood on, not the hay bale 98 px lower"),
                () -> assertEquals(new Point(1189, 275), BotGround.settle(fh, new Point(1189, 248)),
                        "slayergolem, between the bales: the nearer bale (27 px) wins over the one it sank from (28 px)"));
    }

    @Test
    void aBotOnItsFootholdStaysPut() {
        FootholdTree fh = henesys();
        assertAll(
                () -> assertEquals(new Point(1000, 334), BotGround.settle(fh, new Point(1000, 334))),
                () -> assertEquals(new Point(1189, 220), BotGround.settle(fh, new Point(1189, 220)),
                        "the upper bale, with the lower bale 55 px below it"),
                () -> assertEquals(new Point(1189, 275), BotGround.settle(fh, new Point(1189, 275)),
                        "the lower bale, with the upper bale 55 px above it"),
                () -> assertEquals(0, BotGround.offset(fh, new Point(850, 274))));
    }

    @Test
    void offsetsReadForTheGmCommand() {
        FootholdTree fh = henesys();
        assertAll(
                () -> assertEquals(29, BotGround.offset(fh, new Point(1148, 363))),
                () -> assertEquals(-20, BotGround.offset(fh, new Point(1000, 314)), "20 px in the air"),
                () -> assertEquals(Integer.MIN_VALUE, BotGround.offset(fh, new Point(5000, 0)), "no foothold at all"),
                () -> assertNull(BotGround.settle(fh, new Point(5000, 0))));
    }

    @Test
    void aDropLandsOnTheFloorUnderThePortal() {
        FootholdTree fh = henesys();
        assertAll(
                () -> assertEquals(new Point(1000, 334), BotGround.landing(fh, new Point(1000, 257)),
                        "77 px above the ground, where a spawn portal sits"),
                () -> assertEquals(new Point(1000, 334), BotGround.landing(fh, new Point(1000, 334))),
                () -> assertEquals(new Point(1189, 220), BotGround.landing(fh, new Point(1189, 221)),
                        "a portal 1 px under the upper bale lands on it, not on the bale below"),
                () -> assertEquals(new Point(1148, 334), BotGround.landing(fh, new Point(1148, 363)),
                        "a bot already inside the bottom floor has nothing below it: it goes back up"));
    }

    @Test
    void theSpawnDropRecordingEndsOnePixelLower() {
        // The cause: replayed relative to the bot, this recording moved it 1 px down at every spawn.
        MovementRecording drop = getMovementRecording(0, "portalenterdrop");
        assertEquals(1, MovementCommands.offsetReplayNetDy(drop));
    }

    @Test
    void theOtherRelativeRecordingsAreFlat() {
        // Turn-arounds, small steps and the gacha celebration replay relative to the bot over and over: a net rise or
        // fall in any of them would walk a standing bot into the ground (or the air) the same way.
        for (String name : new String[]{"turnaroundtoleft", "turnaroundtoright", "rightleft45", "leftright70",
                "/smallMovement/leftleft20", "/smallMovement/leftright20", "/smallMovement/rightleft20",
                "/smallMovement/rightright20", "/smallMovement/leftleft45", "/smallMovement/leftright70",
                "/smallMovement/rightleft45", "/smallMovement/rightright70"}) {
            MovementRecording mvr = getMovementRecording(0, name);
            assertFalse(mvr.getMovementPacketList().isEmpty(), name + " loads");
            assertEquals(0, MovementCommands.offsetReplayNetDy(mvr), name);
        }
    }

    @Test
    void thirtyBootsNoLongerSinkABot() {
        // Each boot: restore the stored point (BotStore.restorePlacement), then the spawn drop lands the bot
        // (WarpCommands.botEnterPortalDropDown -> BotMoveStreamOffsetLanding), and the store saves where it ended.
        FootholdTree fh = henesys();
        int netDy = MovementCommands.offsetReplayNetDy(getMovementRecording(0, "portalenterdrop"));
        Point stored = new Point(1148, 334);
        Point oldStored = new Point(1148, 334);
        for (int boot = 0; boot < 30; boot++) {
            Point restored = BotGround.settle(fh, stored);
            Point landing = BotGround.landing(fh, restored);
            Point start = new Point(landing.x, landing.y - netDy);
            stored = new Point(start.x, start.y + netDy); // where the relative replay leaves it
            oldStored = new Point(oldStored.x, oldStored.y + netDy); // before rc5: no settle, drop from where it was
        }
        Point fixed = stored;
        Point before = oldStored;
        assertAll(
                () -> assertEquals(new Point(1148, 334), fixed),
                () -> assertEquals(new Point(1148, 364), before, "rc4: 30 px inside the ground after 30 boots"));
    }
}
