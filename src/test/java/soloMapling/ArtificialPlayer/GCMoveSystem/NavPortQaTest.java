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

    /**
     * b254 (auto-QA 2026-10-31 10:00 and 15:41 UTC: t-nn-2 "the parked bot gave its hop up 1 time(s) in 40 AI
     * ticks"): the replay's own give-ups were learned under its made-up map id and persisted
     * (cache/bot-nav/learned/990340298-s100-j100.json on TEST), so the planner stopped serving the gated hop and the
     * check measured a healthy server as broken. Every replay must read the same as the first.
     */
    @Test
    void everyReplayOfTheParkedBotGivesUpAsMuchAsTheFirst() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
        WzTestData.assumePresent("Map.wz");
        NavPortSwitches.blockedPosWatchdog = true;
        try {
            for (int i = 0; i < 3; i++) {
                NavPortQa.ParkedRun run = NavPortQa.parkedBot(40);
                assertTrue(run.gatedTicks() == 40 && run.giveUps() >= 3, "replay " + i + ": " + run);
            }
        } finally {
            NavPortSwitches.blockedPosWatchdog = false;
        }
        String[] learned = dir.resolve("learned").toFile().list();
        assertTrue(learned == null || java.util.Arrays.stream(learned).noneMatch(n -> n.startsWith("990")),
                "a made-up map keeps no learned book: " + java.util.Arrays.toString(learned));
    }
}
