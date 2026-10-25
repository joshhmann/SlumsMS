package soloMapling.qa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FishingScenarioTest {
    @TempDir Path directory;
    @Test void actualTimerPolicyPassesWithoutGameInventoryOrWorldChanges() throws Exception {
        var world = mock(QaWorld.class);
        var gm = QaTestSupport.gm(1, "Admin", 100000000);
        var suite = QaTestSupport.suite(FishingScenarios.all(), world, directory, new QaTestSupport.ManualScheduler());
        suite.start(gm, "fh-1", new String[0]);
        assertTrue(Files.readString(suite.runOf(1).logPath()).contains("FISHING POLICY PASS grants=1 duplicate=blocked movement=cancelled"));
        verifyNoInteractions(world);
        suite.stop(1, "done");
    }
    @Test void actualFishingFixtureUsesTheWatersideSpawnInsteadOfRandomUpperPortals() throws Exception {
        String enabled = System.getProperty("slums.fishing.enabled"), rewards = System.getProperty("slums.fishing.rewards");
        var world = mock(QaWorld.class);
        var gm = QaTestSupport.gm(7, "Admin", 100000000);
        when(gm.getChair()).thenReturn(-1);
        when(world.countItem(gm, 3011000)).thenReturn(1);
        when(world.warp(gm, 120010000)).thenReturn(true);
        when(world.warpToPortal(gm, 120010000, "sp")).thenReturn(true);
        var active = new java.util.concurrent.atomic.AtomicBoolean();
        try (var fishing = mockStatic(soloMapling.fishing.GameFishing.class)) {
            System.setProperty("slums.fishing.enabled", "true");
            System.setProperty("slums.fishing.rewards", "true");
            fishing.when(() -> soloMapling.fishing.GameFishing.active(gm)).thenAnswer(i -> active.get());
            doAnswer(i -> { active.set(true); return null; }).when(gm).sitChair(3011000);
            var suite = QaTestSupport.suite(FishingScenarios.all(), world, directory, new QaTestSupport.ManualScheduler());
            suite.start(gm, "fh-2", new String[0]);
            try {
                verify(world).warpToPortal(gm, 120010000, "sp");
                verify(world, never()).warp(gm, 120010000);
                assertTrue(Files.readString(suite.runOf(7).logPath()).contains("FISHING REAL STARTED"));
            } finally { suite.stop(7, "done"); }
        } finally {
            if (enabled == null) System.clearProperty("slums.fishing.enabled"); else System.setProperty("slums.fishing.enabled", enabled);
            if (rewards == null) System.clearProperty("slums.fishing.rewards"); else System.setProperty("slums.fishing.rewards", rewards);
        }
    }

}
