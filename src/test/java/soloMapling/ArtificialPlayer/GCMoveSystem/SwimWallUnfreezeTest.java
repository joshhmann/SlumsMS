package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Swim wall unfreeze (ported from nutnnut/Cosmic f424904a8, AGPL-3.0. Credit: NutNNut). Swim maps skip the ground nav
 * graph, so when SwimNavigator has no route (its graph still building, no path, or -Dbot.swimnav.enabled=false) a bot
 * standing on the sea floor steers straight at its target. With a rock in the way the ground step was wall-blocked
 * and the bot idled against the rock forever. Now it jumps into the water, and while swimming into a wall it holds UP
 * so it rises over the rock instead of pressing into it.
 */
class SwimWallUnfreezeTest {

    @TempDir
    Path dir;

    @BeforeEach
    void scratch() {
        NavEdgeLearning.useDirForTest(dir.resolve("learned"));
        SwimNavObserved.useDirForTest(dir.resolve("observed"));
        NavStuckLog.useForTest(dir.resolve("stuck"), null);
        SwimNavigator.enabled = false; // no swim route: the plain steering fallback
    }

    @AfterEach
    void restore() {
        NavEdgeLearning.useDirForTest(null);
        NavStuckLog.useForTest(null, null);
        SwimNavigator.enabled = true;
    }

    @Test
    void aBotOnTheSeaFloorGetsOverARockInsteadOfIdlingAgainstIt() {
        MapleMap map = SwimNavGraphTest.pillarMap(990_340_100);
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(424250);
        chr.setMap(map);
        chr.setPosition(new Point(-120, 0));
        BotMovementState entry = new BotMovementState(chr, null);
        entry.flaws.off = true;
        entry.physX = -120;
        entry.physY = 0;
        Point target = new Point(200, 0);

        int bestX = Integer.MIN_VALUE;
        for (int i = 0; i < 400; i++) {
            boolean runAiTick = i % 2 == 0;
            BotNavigationManager.NavigationDirective nav = BotNavigationManager.resolveTarget(entry, target, runAiTick);
            if (!nav.consumedTick) {
                if (entry.inAir) {
                    BotMovementManager.tickSwimming(entry, nav.targetPos);
                } else {
                    BotMovementManager.tickGrounded(entry, nav.targetPos);
                }
            }
            bestX = Math.max(bestX, entry.bot.getPosition().x);
        }
        assertTrue(bestX > 40, "got past the rock (east wall at x=40), furthest x=" + bestX + ", now at "
                + entry.bot.getPosition());
    }
}
