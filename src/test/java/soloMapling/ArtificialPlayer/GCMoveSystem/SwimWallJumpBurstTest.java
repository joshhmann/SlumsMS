package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * NN-011, swim wall escape by jump burst (ported from nutnnut/Cosmic 3516aa238, AGPL-3.0. Credit: NutNNut). The
 * f424904a8 port (SwimWallUnfreezeTest) makes a swimming bot pressed into a wall hold UP, but UP thrust (412 px/s2)
 * is weaker than swim gravity (590 px/s2): holding UP only slows the sink. A rock taller than the hop off the sea
 * floor pins the bot against it for good. With -Dbot.nav.swimWallJump the bot fires its cooldown-gated swim-jump
 * burst while wall-blocked and rises over.
 *
 * The swim-jump cooldown runs on the wall clock; the loop moves the bot's cooldown on by one tick (50 ms) per tick so
 * the test sees the same number of bursts a live bot gets in the same game time.
 */
class SwimWallJumpBurstTest {

    @TempDir
    Path dir;

    @BeforeEach
    void scratch() {
        NavEdgeLearning.useDirForTest(dir.resolve("learned"));
        SwimNavObserved.useDirForTest(dir.resolve("observed"));
        NavStuckLog.useForTest(dir.resolve("stuck"), null);
        SwimNavigator.enabled = false; // no swim route: the plain steering fallback, where the wall escape lives
    }

    @AfterEach
    void restore() {
        NavEdgeLearning.useDirForTest(null);
        NavStuckLog.useForTest(null, null);
        SwimNavigator.enabled = true;
        NavPortSwitches.swimWallJump = false;
    }

    /* A sea floor at y 0 from x -600 to 600 with a 600 px rock (x -40..40) in the middle; water up to y -1200. */
    static MapleMap tallRockMap(int mapId) {
        MapleMap map = new MapleMap(mapId, 0, 0, mapId, 1.0f);
        map.setSwim(true);
        map.setMapLineBoundings(-800, 100, -1200, 600);
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        int[][] fhs = {
                {1, -600, 0, -40, 0, 0, 2},
                {2, -40, 0, -40, -600, 1, 3},
                {3, -40, -600, 40, -600, 2, 4},
                {4, 40, -600, 40, 0, 3, 5},
                {5, 40, 0, 600, 0, 4, 0},
        };
        for (int[] f : fhs) {
            Foothold fh = new Foothold(new Point(f[1], f[2]), new Point(f[3], f[4]), f[0]);
            fh.setPrev(f[5]);
            fh.setNext(f[6]);
            tree.insert(fh);
        }
        map.setFootholds(tree);
        return map;
    }

    /* What the bot did: the highest point it reached against the rock's west face, and the furthest east it got. */
    private record Run(int highestAtWestFace, int furthestEast) {
    }

    private static Run swimAt(MapleMap map, int ticks) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(424260);
        chr.setMap(map);
        chr.setPosition(new Point(-120, 0));
        BotMovementState entry = new BotMovementState(chr, null);
        entry.flaws.off = true;
        entry.physX = -120;
        entry.physY = 0;
        Point target = new Point(200, -300); // open water east of the rock, half its height
        int highest = Integer.MAX_VALUE;
        int furthest = Integer.MIN_VALUE;
        for (int i = 0; i < ticks; i++) {
            boolean runAiTick = i % 2 == 0;
            BotNavigationManager.NavigationDirective nav = BotNavigationManager.resolveTarget(entry, target, runAiTick);
            if (!nav.consumedTick) {
                if (entry.inAir) {
                    BotMovementManager.tickSwimming(entry, nav.targetPos);
                } else {
                    BotMovementManager.tickGrounded(entry, nav.targetPos);
                }
            }
            entry.swimNextJumpAtMs -= BotPhysicsEngine.cfg.TICK_MS; // one game tick passes for the cooldown
            Point p = entry.bot.getPosition();
            if (p.x >= -45 && p.x <= -38) {
                highest = Math.min(highest, p.y);
            }
            furthest = Math.max(furthest, p.x);
        }
        return new Run(highest, furthest);
    }

    /* Before: pressed into the rock the bot only slows its sink. It never gets near the top (y -600). (It does get
     * past later, by slipping through the rock's foot from the sea floor: a separate swim physics issue, not this
     * port's.) */
    @Test
    void withTheSwitchOffABotPressedIntoATallRockSinksDownItsFace() {
        Run run = swimAt(tallRockMap(990_340_201), 300);
        assertTrue(run.highestAtWestFace() > -400, "UP-hold alone climbed the face to y=" + run.highestAtWestFace());
    }

    @Test
    void aSwimmingBotJumpsOverATallRockInsteadOfSinkingAgainstIt() {
        NavPortSwitches.swimWallJump = true;
        Run run = swimAt(tallRockMap(990_340_202), 300);
        assertTrue(run.highestAtWestFace() < -600, "rose past the rock's top (y -600) on its west face, highest y="
                + run.highestAtWestFace());
        assertTrue(run.furthestEast() > 100, "then swam on east toward the target, furthest x=" + run.furthestEast());
    }
}
