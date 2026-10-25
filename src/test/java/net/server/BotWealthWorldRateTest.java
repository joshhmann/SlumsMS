package net.server;

import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollBudget;
import soloMapling.ArtificialPlayer.BotWealth;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ticket 64: the wealth scroll bonus must read Bera's (world 1) meso rate; there is no world 0. */
class BotWealthWorldRateTest {
    private final Server server = Server.getInstance();

    @BeforeEach
    @AfterEach
    void reset() {
        server.resetServerWorlds();
        BotWealth.resetForTest(null);
    }

    @Test
    void scrollBonusReadsBerasRateWhenOnlyWorldOneRuns() {
        World bera = mock(World.class);
        when(bera.getId()).thenReturn(1);
        when(bera.getMesoRate()).thenReturn(10);
        assertTrue(server.registerWorld(bera, 0, Map.of(), ""));

        // lv 70 at rate 10: ceiling 5,880,000; holding 9,880,000 -> half of the 4,000,000 excess
        assertEquals(2_000_000, BotWealth.scrollBonus(70, 9_880_000));
        assertEquals(0, BotWealth.scrollBonus(70, 5_000_000), "under its ceiling");

        // ScrollBudget.maxPrice consumes the bonus: daily cap + bonus beats 25% of the spare mesos
        long spare = 9_880_000 - (100_000 + 5_000L * 70);
        long withBonus = 15_000L * 70 + 2_000_000;
        assertEquals(Math.min(withBonus, spare / 4), ScrollBudget.maxPrice(990_001, 70, 9_880_000, false));
        assertTrue(spare / 4 > 15_000L * 70, "the bonus is what lifts the price above the plain daily cap");
    }

    @Test
    void noBonusWithoutAWorld() {
        assertEquals(0, BotWealth.scrollBonus(70, 50_000_000));
    }
}
