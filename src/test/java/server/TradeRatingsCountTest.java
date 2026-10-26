package server;

import client.Character;
import client.inventory.Inventory;
import client.inventory.Item;
import net.server.coordinator.world.InviteCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.ArtificialPlayer.BotConvo.SayDo;
import tools.PacketCreator;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 1.6.0 Track D trade ratings, through the real trade code: completed, fell through after an offer, empty window. */
class TradeRatingsCountTest {

    Character a, b;
    Trade ta, tb;

    @BeforeEach
    void setUp() {
        soloMapling.site.TradeRatingsTestAccess.reset();
        a = mock(Character.class);
        b = mock(Character.class);
        when(a.getId()).thenReturn(1);
        when(b.getId()).thenReturn(2);
        when(a.canHoldMeso(anyInt())).thenReturn(true);
        when(b.canHoldMeso(anyInt())).thenReturn(true);
        when(a.getLevel()).thenReturn(50);
        when(b.getLevel()).thenReturn(50);
        ta = new Trade((byte) 0, a);
        tb = new Trade((byte) 1, b);
        ta.setPartner(tb);
        tb.setPartner(ta);
        when(a.getTrade()).thenAnswer(i -> ta);
        when(b.getTrade()).thenAnswer(i -> tb);
    }

    @AfterEach
    void tearDown() {
        soloMapling.site.TradeRatingsTestAccess.reset();
    }

    private static long[] counts(int id) {
        long[] c = soloMapling.site.TradeRatingsTestAccess.pending().get(id);
        return c == null ? new long[2] : c;
    }

    @Test
    void aCompletedTradeCountsForBothSides() {
        try (MockedStatic<PacketCreator> packets = mockStatic(PacketCreator.class);
             MockedStatic<Inventory> inventory = mockStatic(Inventory.class);
             MockedStatic<SayDo> sayDo = mockStatic(SayDo.class)) {
            inventory.when(() -> Inventory.checkSpotsAndOwnership(any(), anyList())).thenReturn(true);
            Trade.completeTrade(a); // a confirms
            assertEquals(Map.of(), soloMapling.site.TradeRatingsTestAccess.pending(), "one side confirming is not a trade");
            Trade.completeTrade(b); // b confirms: done
        }
        assertArrayEquals(new long[]{1, 0}, counts(1));
        assertArrayEquals(new long[]{1, 0}, counts(2));
    }

    @Test
    void aCancelAfterAnOfferFellThrough() {
        Item potion = new Item(2000000, (short) 1, (short) 3, -1);
        try (MockedStatic<PacketCreator> packets = mockStatic(PacketCreator.class);
             MockedStatic<InviteCoordinator> invites = mockStatic(InviteCoordinator.class);
             MockedStatic<client.inventory.manipulator.InventoryManipulator> inv =
                     mockStatic(client.inventory.manipulator.InventoryManipulator.class)) {
            inv.when(() -> client.inventory.manipulator.InventoryManipulator.addFromDrop(any(client.Client.class), any(), anyBoolean()))
                    .thenReturn(true);
            assertTrue(tb.addItem(potion));
            Trade.cancelTrade(a, Trade.TradeResult.PARTNER_CANCEL);
        }
        assertArrayEquals(new long[]{0, 1}, counts(1));
        assertArrayEquals(new long[]{0, 1}, counts(2));
    }

    @Test
    void anEmptyWindowOpenedAndClosedCountsForNobody() {
        try (MockedStatic<PacketCreator> packets = mockStatic(PacketCreator.class);
             MockedStatic<InviteCoordinator> invites = mockStatic(InviteCoordinator.class)) {
            Trade.cancelTrade(b, Trade.TradeResult.UNSUCCESSFUL_ANOTHER_MAP);
        }
        assertEquals(Map.of(), soloMapling.site.TradeRatingsTestAccess.pending());
    }
}
