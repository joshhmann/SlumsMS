package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeActivityQueue;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotLifeExecutionScenarioTest {
    @TempDir Path directory;

    @Test
    void queuesAndRetriesTheSameRequestAndRemovesTheFixtureAtStop() throws Exception {
        QaWorld world = mock(QaWorld.class);
        Character gm = QaTestSupport.gm(1, "Admin", 104040000);
        when(world.botLifeExecutionEnabled()).thenReturn(true);
        when(world.mobLevel(gm)).thenReturn(5);
        when(world.stageGrinders(gm, List.of("WARRIOR:30"), 1, 0, true, true)).thenReturn(List.of(1000005001));
        var queue = new LifeActivityQueue();
        var choice = new LifeDecision.Result(List.of(), Optional.of(LifeDecision.Activity.RESTOCK),
                LifeDecision.Outcome.CHOOSE);
        var request = queue.prepare(choice, 100, 1000);
        when(world.prepareLifeRestock(1000005001)).thenReturn(request);
        when(world.submitLifeRestock(1000005001, request)).thenReturn("QUEUED", "DUPLICATE", "STALE");
        when(world.botLifeReport(1000005001)).thenReturn(List.of("READY bot=1000005001 phase=GRIND"));
        var scheduler = new QaTestSupport.ManualScheduler();
        QaSuite suite = QaTestSupport.suite(BotLifeScenarios.all(), world, directory, scheduler);
        suite.start(gm, "bl-2", new String[0]);
        suite.action(gm, "restock", new String[0]);
        suite.action(gm, "repeat", new String[0]);
        Path log = suite.runOf(1).logPath();
        assertTrue(Files.readString(log).contains("LIFE QUEUED request=1 result=QUEUED"));
        assertTrue(Files.readString(log).contains("LIFE REPLAY request=1 result=DUPLICATE"));
        assertTrue(Files.readString(log).contains("LIFE REPLAY request=1 result=STALE"));
        verify(world).prepareLifeRestock(1000005001);
        verify(world, times(3)).submitLifeRestock(1000005001, request);
        suite.stop(1, "done");
        verify(world).unstageGrinders(List.of(1000005001));
    }

    @Test
    void disabledExecutionRefusesBeforeCreatingAnyBot() {
        QaWorld world = mock(QaWorld.class);
        Character gm = QaTestSupport.gm(1, "Admin", 104040000);
        QaSuite suite = QaTestSupport.suite(BotLifeScenarios.all(), world, directory,
                new QaTestSupport.ManualScheduler());
        assertThrows(QaRefusal.class, () -> suite.start(gm, "bl-2", new String[0]));
        verify(world, never()).stageGrinders(any(), anyList(), anyInt(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    void aRetryThatWouldQueueAgainFailsTheCheck() {
        QaWorld world = mock(QaWorld.class);
        Character gm = QaTestSupport.gm(1, "Admin", 104040000);
        when(world.botLifeExecutionEnabled()).thenReturn(true);
        when(world.mobLevel(gm)).thenReturn(5);
        when(world.stageGrinders(gm, List.of("WARRIOR:30"), 1, 0, true, true)).thenReturn(List.of(1000005001));
        var queue = new LifeActivityQueue();
        var request = queue.prepare(new LifeDecision.Result(List.of(), Optional.of(LifeDecision.Activity.RESTOCK),
                LifeDecision.Outcome.CHOOSE), 100, 1000);
        when(world.prepareLifeRestock(1000005001)).thenReturn(request);
        when(world.submitLifeRestock(1000005001, request)).thenReturn("QUEUED");
        QaSuite suite = QaTestSupport.suite(BotLifeScenarios.all(), world, directory,
                new QaTestSupport.ManualScheduler());
        suite.start(gm, "bl-2", new String[0]);
        assertThrows(QaRefusal.class, () -> suite.action(gm, "restock", new String[0]));
        suite.stop(1, "done");
    }
}
