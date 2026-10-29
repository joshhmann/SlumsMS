package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.StageSpots;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** TEST t-cr-1 2026-10-29: the cr-1/rv-5/dr-6 bots must stay where they were put, so a walk-up reaches them. */
class BeefSpawnPinTest {

    @Test
    void stagedBeefBotsArePinnedNextToTheGmUntilTheRunStops() {
        QaContext ctx = mock(QaContext.class);
        QaWorld world = mock(QaWorld.class);
        Character gm = mock(Character.class);
        when(ctx.world()).thenReturn(world);
        when(ctx.gm()).thenReturn(gm);
        when(world.spawnIdleBots(any(), any(), anyInt())).thenReturn(List.of(1_000_900_001, 1_000_900_002, 1_000_900_003));
        List<Runnable> stops = new ArrayList<>();
        doAnswer(i -> stops.add(i.getArgument(1))).when(ctx).onStop(anyString(), any());
        List<Integer> ids = BeefScenarios.spawn(ctx, 3, "cr-1");
        for (int id : ids) {
            assertTrue(StageSpots.pinned(id), "bot " + id + " is pinned");
        }
        stops.forEach(Runnable::run);
        for (int id : ids) {
            assertFalse(StageSpots.pinned(id), "bot " + id + " is free again after the run");
        }
    }
}
