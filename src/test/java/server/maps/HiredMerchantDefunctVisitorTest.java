package server.maps;

import client.Character;
import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import tools.PacketCreator;

import java.awt.Point;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.5.2 (ticket 80, ported from nutnnut/Cosmic 0a9f08bf3): a character that logged out while visiting a hired
 * merchant and was then disposed (client nulled) could stay in the shop's visitor slots. Every later join or leave
 * broadcast to it (nutnnut: a NullPointerException crash loop), and its slot stayed taken for good, so a shop with
 * three such ghosts could never be entered again. Ghost visitors are now purged instead.
 */
class HiredMerchantDefunctVisitorTest {

    private static HiredMerchant shop() {
        Character owner = mock(Character.class);
        when(owner.getPosition()).thenReturn(new Point(0, 0));
        when(owner.getClient()).thenReturn(mock(Client.class));
        when(owner.getMap()).thenReturn(mock(MapleMap.class));
        return new HiredMerchant(owner, "test", 5030000);
    }

    private static Character live(int id) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn("live" + id);
        when(chr.getClient()).thenReturn(mock(Client.class));
        return chr;
    }

    private static Character ghost(int id) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn("ghost" + id);
        when(chr.getClient()).thenReturn(null);   // empty(true) has run
        return chr;
    }

    private static Object visitors(HiredMerchant shop) throws Exception {
        Field f = HiredMerchant.class.getDeclaredField("visitors");
        f.setAccessible(true);
        return f.get(shop);
    }

    private static void seat(HiredMerchant shop, int slot, Character chr) throws Exception {
        Object visitors = visitors(shop);
        Constructor<?> ctor = visitors.getClass().getComponentType().getDeclaredConstructor(Character.class, Instant.class);
        ctor.setAccessible(true);
        Array.set(visitors, slot, ctor.newInstance(chr, Instant.now()));
    }

    @Test
    void aShopFullOfGhostsCanBeEnteredAgain() throws Exception {
        HiredMerchant shop = shop();
        seat(shop, 0, ghost(1));
        seat(shop, 1, ghost(2));
        seat(shop, 2, ghost(3));

        Character newcomer = live(4);

        try (MockedStatic<PacketCreator> packets = mockStatic(PacketCreator.class)) {   // packets aren't under test
            assertTrue(shop.addVisitor(newcomer), "three ghosts kept every slot taken");
        }
        assertEquals(0, shop.getVisitorSlotThreadsafe(newcomer));
        assertNull(Array.get(visitors(shop), 1));
        assertNull(Array.get(visitors(shop), 2));
    }

    @Test
    void aBroadcastSkipsAndPurgesAGhostButReachesTheOthers() throws Exception {
        HiredMerchant shop = shop();
        Character ghost = ghost(1);
        Character other = live(2);
        seat(shop, 0, ghost);
        seat(shop, 1, other);

        Packet packet = mock(Packet.class);
        shop.broadcastToVisitorsThreadsafe(packet);

        verify(ghost, never()).sendPacket(any(Packet.class));
        verify(other, times(1)).sendPacket(packet);
        assertNull(Array.get(visitors(shop), 0));
        assertNotNull(Array.get(visitors(shop), 1));
    }
}
