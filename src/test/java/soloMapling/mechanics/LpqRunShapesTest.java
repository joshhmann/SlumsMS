package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;

/**
 * 1.6.7 boss-feed-lines batch 2 guard: the three LPQ lines the analyser already parses keep their exact text. The
 * new LPQ lines are added beside them and never change them: {@code RUN start lpq stage 1 in <instance>: ...},
 * {@code RUN stage N cleared in S s (...) [lpq, leader X]} and {@code RUN end lpq in <instance>: cleared after S s ...}.
 */
class LpqRunShapesTest {

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final List<Runnable> ticks = new ArrayList<>();
    private java.util.function.Supplier<ScheduledExecutorService> wasScheduler;

    @BeforeEach
    void ticksAreMine() {
        wasScheduler = MechanicsRuns.scheduler;
        ScheduledExecutorService mine = mock(ScheduledExecutorService.class);
        when(mine.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenAnswer(inv -> {
            ticks.add(inv.getArgument(0));
            return mock(ScheduledFuture.class);
        });
        MechanicsRuns.scheduler = () -> mine;
    }

    @AfterEach
    void putBack() {
        MechanicsRuns.get().stopAll("test over");
        MechanicsRuns.scheduler = wasScheduler;
    }

    private static MechanicsModel.Content lpq() {
        StageData.Result r = StageData.loadDir(Path.of(Mechanics.DEFAULT_DIR));
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        return r.content().stream().filter(c -> c.id().equals("lpq")).findFirst().orElseThrow();
    }

    @Test
    void theStartStageAndEndLinesKeepTheirShape() {
        List<client.Character> five = new ArrayList<>(MechanicsRunsInstancesTest.bots(4_100));
        five.add(MechanicsRunsInstancesTest.member(4_104, true));
        EventInstanceManager eim = MechanicsRunsInstancesTest.kpqOn(1, five, 4_100);
        when(eim.getProperty("1stageclear")).thenReturn("true");
        MechanicsRuns.Active a = MechanicsRuns.get().start(eim, lpq(), true, 0, 1, 1, true, lines::add, null);
        assertTrue(!ticks.isEmpty(), lines.toString());
        ticks.get(0).run();

        assertTrue(a.run().cleared(), lines.toString());
        assertTrue(lines.get(0).matches("RUN start lpq stage 1 in Kerning0 w1 ch 1: .*"), lines.get(0));
        assertTrue(lines.stream().anyMatch(l ->
                l.matches("RUN stage 1 cleared in \\d+ s \\(\\d+ wrong tries shown\\) \\[lpq, leader m4100\\]")), lines.toString());
        String end = lines.get(lines.size() - 1);
        assertTrue(end.matches("RUN end lpq in Kerning0 w1 ch 1: cleared after \\d+ s, \\d+ wrong tries shown"), end);
    }

    @Test
    void theStageLineTagIsAppendedAtTheEndOnly() {
        assertEquals("stage 3 cleared in 12 s (0 wrong tries shown) [lpq, leader Josh]",
                MechanicsRuns.tagged("stage 3 cleared in 12 s (0 wrong tries shown)", "lpq", "Josh"));
        assertEquals("start lpq stage 1 in Ludi0", MechanicsRuns.tagged("start lpq stage 1 in Ludi0", "lpq", "Josh"));
    }
}
