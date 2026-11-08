package server;

import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.PacketCreator;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 1.6.7 shop-ammo-tiers (owner, 8 Nov 2026: "all the damn stars and ammo are in general store?!"). Every shop window
 * lists the stars and bullets it does not sell at price 0, only so the client can recharge them. Since 1.6.4 b289 the
 * window printed their whole-stack price (Ilbi 29,999), so they looked for sale; buying one did nothing and left the
 * window waiting. The window shows them at 0 again, and a click on one answers "the trade did not happen".
 * Reflection keeps this compiling on the old code, where it fails on the missing helper.
 */
class ShopRechargeOnlyEntryTest {
    private static final int SUBI = 2070000;
    private static final int ILBI = 2070006;
    private static final int RED_POTION = 2000000;

    private static int shownPrice(int itemId, int shopPrice, int stackPrice) throws Exception {
        Class<?> c = Class.forName("server.ShopPacketPrice");
        Method m = c.getDeclaredMethod("forEntry", int.class, int.class, int.class);
        return (int) m.invoke(null, itemId, shopPrice, stackPrice);
    }

    @Test
    void aRechargeOnlyStarShowsNoPrice() throws Exception {
        assertEquals(0, shownPrice(ILBI, 0, 29_999), "Ilbi is not sold: the window must not show a price");
    }

    @Test
    void aStarTheShopSellsStillShowsWhatTheBuyCharges() throws Exception {
        assertEquals(10_249, shownPrice(SUBI, 500, 10_249), "b289: Subi shows its whole-stack price");
        assertEquals(500, shownPrice(SUBI, 500, -1), "no WZ price: the shop's own price, as before");
        assertEquals(50, shownPrice(RED_POTION, 50, 999), "not a rechargeable: the shop's price");
    }

    @Test
    void clickingARechargeOnlyEntryRefusesTheTradeAndChargesNothing() throws Exception {
        Shop shop = Shop.custom(1011100, 1011100, List.of(new ShopItem((short) 1000, ILBI, 0, 0)));
        Client c = mock(Client.class, RETURNS_DEEP_STUBS);

        try (var db = mockStatic(tools.DatabaseConnection.class)) {
            db.when(tools.DatabaseConnection::getConnection).thenReturn(mock(Connection.class, RETURNS_DEEP_STUBS));
            shop.buy(c, (short) 0, ILBI, (short) 1);
        }

        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(c, atLeastOnce()).sendPacket(sent.capture());
        assertArrayEquals(PacketCreator.shopTransaction((byte) 6).getBytes(), sent.getValue().getBytes(),
                "the client is told the trade did not happen");
        verify(c.getPlayer(), never()).gainMeso(anyInt(), anyBoolean());
    }
}
