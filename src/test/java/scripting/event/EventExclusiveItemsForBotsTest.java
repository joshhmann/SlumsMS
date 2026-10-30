package scripting.event;

import client.BotClient;
import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotHoldings;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.2 (KPQ automation fix, TEST 2026-10-30 14:15 UTC): a bot leaving a party quest must not throw out of
 * {@code unregisterPlayer}. KerningPQ.js's {@code end} exits every member and only then disposes the instance; the
 * shared headless bot client has no player, so taking the coupons and passes back through the client threw a
 * NullPointerException, {@code end} stopped half way and the instance stayed until its 30-minute timer (Lakelis
 * refused the next party). The bot's event items now go through its holdings.
 */
class EventExclusiveItemsForBotsTest {

    static final int COUPON = 4001007, PASS = 4001008;

    @Test
    void aBotOnTheSharedClientLeavesWithoutThrowingAndHandsItsEventItemsBack() {
        Character bot = Character.getDefault(new BotClient(0, 0));
        assertTrue(bot.getClient().getPlayer() != bot, "a population bot's client is the shared headless one");
        assertTrue(BotHoldings.grantItem(bot, new Item(COUPON, (short) 0, (short) 7, -1)));
        assertTrue(BotHoldings.grantItem(bot, new Item(PASS, (short) 0, (short) 1, -1)));

        assertDoesNotThrow(() -> EventInstanceManager.dropExclusiveItems(bot, List.of(COUPON, PASS)));

        assertEquals(0, bot.getInventory(InventoryType.ETC).countById(COUPON));
        assertEquals(0, bot.getInventory(InventoryType.ETC).countById(PASS));
    }

    @Test
    void nothingHeldIsFine() {
        Character bot = Character.getDefault(new BotClient(0, 0));
        assertDoesNotThrow(() -> EventInstanceManager.dropExclusiveItems(bot, List.of(COUPON, PASS)));
    }
}
