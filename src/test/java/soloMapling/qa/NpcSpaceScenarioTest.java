package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NpcSpaceScenarioTest {
    @TempDir Path dir;

    @AfterEach
    void reset() { QaLog.reset(); }

    @Test
    void samplesTheLiveFixtureAndDespawnsItAtStop() throws Exception {
        QaWorld world = mock(QaWorld.class);
        Character gm = QaTestSupport.gm(1, "Admin", 100000000);
        when(world.online(1)).thenReturn(gm);
        when(world.personalSpaceApplies(gm)).thenReturn(true);
        when(world.stageNpcTownBot(gm)).thenReturn(List.of(1_000_005_004));
        when(world.npcClearance(gm, List.of(1_000_005_004)))
                .thenReturn(List.of("NPC CLEARANCE bot 1000005004 dx=80 clear=true"));
        var scheduler = new QaTestSupport.ManualScheduler();
        QaSuite suite = QaTestSupport.suite(PersonalSpaceScenarios.all(), world, dir, scheduler);
        suite.start(gm, "ps-2", new String[0]);
        Path log = suite.runOf(1).logPath();
        scheduler.repeating.getFirst().run();
        verify(world, times(2)).npcClearance(gm, List.of(1_000_005_004));
        suite.stop(1, "done");
        verify(world).despawnBot(1_000_005_004);
        assertTrue(Files.readString(log).contains("dx=80 clear=true"));
    }

    @Test
    void refusesWhenThereIsNoNpcFixture() {
        QaWorld world = mock(QaWorld.class);
        Character gm = QaTestSupport.gm(1, "Admin", 100000000);
        when(world.personalSpaceApplies(gm)).thenReturn(true);
        when(world.stageNpcTownBot(gm)).thenReturn(List.of());
        QaSuite suite = QaTestSupport.suite(PersonalSpaceScenarios.all(), world, dir,
                new QaTestSupport.ManualScheduler());
        assertThrows(QaRefusal.class, () -> suite.start(gm, "ps-2", new String[0]));
        verify(world, never()).npcClearance(any(), any());
    }
}
