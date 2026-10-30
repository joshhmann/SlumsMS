package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.WzTestData;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/* t-nn-1's replay (NavPortQa.swimWall) with the swim navigator as the server has it by default (on). */
class NavPortQaTest {

    @TempDir
    Path dir;

    @BeforeEach
    void scratch() {
        NavEdgeLearning.useDirForTest(dir.resolve("learned"));
        SwimNavObserved.useDirForTest(dir.resolve("observed"));
        NavStuckLog.useForTest(dir.resolve("stuck"), null);
    }

    @AfterEach
    void restore() {
        NavEdgeLearning.useDirForTest(null);
        SwimNavObserved.useDirForTest(null);
        NavStuckLog.useForTest(null, null);
        NavPortSwitches.swimWallJump = false;
    }

    @Test
    void theReplayedSwimmerClearsTheRockWithTheSwitchOn() {
        assertTrue(SwimNavigator.enabled, "the live default");
        NavPortSwitches.swimWallJump = true;
        NavPortQa.SwimRun run = NavPortQa.swimWall(300);
        assertTrue(run.highestAtWestFace() < -600 && run.furthestEast() > 100, run.toString());
    }

    @Test
    void withTheSwitchOffTheReplayedSwimmerStaysPinned() {
        NavPortQa.SwimRun run = NavPortQa.swimWall(300);
        assertTrue(run.highestAtWestFace() > -400, run.toString());
    }

    @Test
    void theReplayedParkedBotGivesUpOnlyWithTheWatchdog() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
        WzTestData.assumePresent("Map.wz");
        NavPortQa.ParkedRun off = NavPortQa.parkedBot(40);
        assertTrue(off.giveUps() == 0 && off.gatedTicks() == 40, off.toString());
        NavPortSwitches.blockedPosWatchdog = true;
        try {
            NavPortQa.ParkedRun on = NavPortQa.parkedBot(40);
            assertTrue(on.giveUps() >= 3, on.toString());
        } finally {
            NavPortSwitches.blockedPosWatchdog = false;
        }
    }
}
