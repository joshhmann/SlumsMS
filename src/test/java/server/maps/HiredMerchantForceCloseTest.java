package server.maps;

import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import soloMapling.FreeMarket.HiredMerchantArtificial;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.5.2: HiredMerchant.forceClose started with map.broadcastMessage(...) on the merchant's map field. A merchant whose
 * map was already gone (the channel disposed its maps during a shutdown, or a close had already run) threw
 * "Cannot invoke MapleMap.broadcastMessage(...) because this.map is null" - 32 times in one bot-shop sweep on the test
 * server - and the throw skipped everything after it: visitors were never removed, the merchant stayed registered in the
 * world, its items were never persisted and the owner's HasMerchant flag was left set, so that player could not open
 * another shop.
 */
class HiredMerchantForceCloseTest {

    @Test
    void forceCloseWithoutAMapStillFinishesTheClose() {
        Character owner = mock(Character.class);
        when(owner.getPosition()).thenReturn(new Point(0, 0));
        when(owner.getClient()).thenReturn(mock(Client.class));
        HiredMerchantArtificial shop = new HiredMerchantArtificial(owner, "test", 5030000, 1_000_000_123, "BotShop");

        shop.setOpen(true);

        assertDoesNotThrow(shop::forceClose);
        assertFalse(shop.isOpen(), "the close must still run past the missing map");
    }
}
