package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.maps.MapleMap;
import soloMapling.WzTestData;

import java.awt.Point;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * NN-011, the parked-at-a-launch-gate watchdog (runtime part of nutnnut/Cosmic 43b9f06c8, AGPL-3.0. Credit:
 * NutNNut). A bot whose next hop's launch gate refuses it ("drop-pos", "jump-pos", "tele-pos" ...) without it moving
 * gives the hop up after ~300-500 ms and replans. The count only ran for a hop re-used from the previous tick, and
 * every BotMovementManager.clearNavigationState zeroed it. Clears happen between AI ticks for incidental reasons (a
 * refused ground step, the post-move execute check), after which the planner serves the same hop again as "new":
 * the count never got past 1 and the bot stood there for good (theirs: MAGEFUNNY, 12 minutes on 600020100).
 *
 * Real map: the Henesys department store (100000102). A bot on the top floor (r1, x -8) heading for the exit; its first hop
 * has a launch gate somewhere else on the shelf. The motor is not run, so the bot stays parked at the gate, and
 * the nav is cleared after every tick the way an incidental clear does.
 */
class BlockedPosWatchdogTest {

    private static final int DEPT_STORE = 100000102;
    private static final Point EXIT = new Point(300, 182);

    @TempDir
    Path dir;

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    @BeforeEach
    void scratch() {
        NavEdgeLearning.useDirForTest(dir.resolve("learned"));
        NavStuckLog.useForTest(dir.resolve("stuck"), Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void restore() {
        NavEdgeLearning.useDirForTest(null);
        NavStuckLog.useForTest(null, null);
        NavPortSwitches.blockedPosWatchdog = false;
    }

    private BotMovementState parkedBot(MapleMap map, Point start, int id) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setMap(map);
        Point ground = BotPhysicsEngine.findGroundPoint(map, start);
        chr.setPosition(new Point(ground));
        BotMovementState entry = new BotMovementState(chr, null);
        entry.flaws.off = true;
        entry.movementProfile = BotMovementProfile.base();
        entry.physX = ground.x;
        entry.physY = ground.y;
        return entry;
    }

    private int gatedTicks;

    /* Runs 40 AI ticks with the bot parked and the nav cleared after each; returns how many times the watchdog gave
     * the hop up (and counts the ticks a launch gate refused the bot in gatedTicks). */
    private int giveUps(MapleMap map, BotMovementState entry) {
        gatedTicks = 0;
        for (int i = 0; i < 40; i++) {
            BotNavigationManager.resolveTarget(entry, EXIT, true);
            if (entry.lastEdgeBlockReason != null && entry.lastEdgeBlockReason.endsWith("-pos")) {
                gatedTicks++;
            }
            BotMovementManager.clearNavigationState(entry); // the incidental clear
        }
        NavStuckLog.flush();
        int count = 0;
        for (NavStuckLog.Spot spot : NavStuckLog.read(NavStuckLog.fileFor(dir.resolve("stuck"), LocalDate.parse("2026-10-30")))) {
            if (spot.kind == NavStuckLog.Kind.GAVE_UP && spot.mapId == map.getId()) {
                count += spot.count;
            }
        }
        return count;
    }

    private MapleMap store() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(DEPT_STORE);
        BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        return map;
    }

    @Test
    void withTheSwitchOffIncidentalClearsHideTheParkedBot() {
        MapleMap map = store();
        assertEquals(0, giveUps(map, parkedBot(map, new Point(-8, -128), 7302)));
        assertEquals(40, gatedTicks, "fixture: a launch gate refused the parked bot on every tick");
    }

    @Test
    void withTheSwitchOnTheParkedBotGivesTheHopUpDespiteIncidentalClears() {
        NavPortSwitches.blockedPosWatchdog = true;
        MapleMap map = store();
        int giveUps = giveUps(map, parkedBot(map, new Point(-8, -128), 7303));
        // 40 AI ticks at a 6-10 tick give-up: 4 to 6 give-ups (each one marks the hop failed, replans and starts a
        // fresh count).
        assertTrue(giveUps >= 3, "gave up " + giveUps + " times in 40 parked ticks");
    }
}
