package server.cashshop;

import client.Character;
import client.Client;
import net.packet.InPacket;
import net.packet.Packet;
import net.server.channel.handlers.EnterCashShopHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.CashShop;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CashShopButtonTest {
    @Test void existingButtonOpensWindowAndReleasesInputWithoutMapTransition() {
        String previous = System.getProperty("cashshop.window.enabled");
        try {
            System.setProperty("cashshop.window.enabled", "true");
            Client client = mock(Client.class);
            Character player = mock(Character.class);
            when(client.getPlayer()).thenReturn(player);
            when(player.isAlive()).thenReturn(true);
            when(player.getCashShop()).thenReturn(mock(CashShop.class));
            new EnterCashShopHandler().handlePacket(mock(InPacket.class), client);
            ArgumentCaptor<Packet> packets = ArgumentCaptor.forClass(Packet.class);
            verify(client, times(4)).sendPacket(packets.capture());
            byte[] open = packets.getAllValues().get(1).getBytes();
            assertEquals(0x3731, Short.toUnsignedInt(ByteBuffer.wrap(open).order(ByteOrder.LITTLE_ENDIAN).getShort()));
            assertEquals(CashShopWindowPackets.RESP_OPEN, open[2]);
            verify(player, never()).changeMap(anyInt());
            verify(player.getCashShop(), never()).open(true);
        } finally {
            if (previous == null) System.clearProperty("cashshop.window.enabled");
            else System.setProperty("cashshop.window.enabled", previous);
        }
    }
}
