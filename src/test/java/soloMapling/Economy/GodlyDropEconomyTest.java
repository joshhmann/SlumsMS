package soloMapling.Economy;

import client.inventory.Equip;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.GodlyDrops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.5.2: a godly mob drop's bump (+1..+5 on a few stats) stays under GearGrade's stat-gain line, so its mark is what
 * makes the economy treat it as godly: bots never sell it, list it in their shops or buy it from players.
 */
class GodlyDropEconomyTest {

    @BeforeEach
    void setUp() {
        new EconomyTestSupport();
    }

    @AfterEach
    void tearDown() {
        EconomyTestSupport.reset();
    }

    private static Equip godlyDrop() {
        Equip e = mock(Equip.class);
        when(e.getItemId()).thenReturn(1302000);
        when(e.getQuantity()).thenReturn((short) 1);
        when(e.getGiftFrom()).thenReturn(GodlyDrops.MARK);
        return e;
    }

    @Test
    void aGodlyDropGradesGodlyAndBotsNeverSellOrBuyIt() {
        Equip e = godlyDrop();
        assertEquals(GearGrade.Grade.GODLY, GearGrade.grade(e));
        assertTrue(GearGrade.godly(e));
        assertEquals("godly gear", SaleRules.whyNot(e));
        assertTrue(BotBuying.whyNotBuyFromPlayer(e).contains("godly"), BotBuying.whyNotBuyFromPlayer(e));
    }
}
