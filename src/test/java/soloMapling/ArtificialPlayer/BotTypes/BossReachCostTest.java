package soloMapling.ArtificialPlayer.BotTypes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 1.6.5 b311: the reach gate's walk-cost searches are counted, so the switch's cost is measured before live. */
class BossReachCostTest {

    @AfterEach
    void reset() {
        FollowerFight.ReachCost.resetForTest();
    }

    @Test
    void searchesAreCountedAndTimedPerWindow() {
        FollowerFight.ReachCost.resetForTest();
        FollowerFight.ReachCost.note(1_500_000L, 1_000L);
        FollowerFight.ReachCost.note(500_000L, 2_000L);
        assertEquals("Boss reach gate: 2 walk-cost searches in 60 s, 2.0 ms total, slowest 1.50 ms",
                FollowerFight.ReachCost.line(60_000L));
        assertEquals("Boss reach gate: 0 walk-cost searches in 60 s, 0.0 ms total, slowest 0.00 ms",
                FollowerFight.ReachCost.line(60_000L), "each line starts a fresh window");
    }
}
