package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.PacketCreator;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UseSummonBagHandlerTest {

    @BeforeAll
    static void initItemProvider() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection).thenReturn(mock(java.sql.Connection.class, RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    private InPacket createSummonBagPacket(short slot, int itemId) {
        OutPacket out = new ByteBufOutPacket();
        out.writeInt(0);
        out.writeShort(slot);
        out.writeInt(itemId);
        return new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes()));
    }

    @Test
    void testGetSummonMobsNullSafe() {
        assertDoesNotThrow(() -> {
            int[][] mobs = server.ItemInformationProvider.getInstance().getSummonMobs(9999999);
            assertNull(mobs);
        });
    }

    @Test
    void testInvalidSummonBagDoesNotThrowNpeAndSendsEnableActions() {
        Client c = mock(Client.class);
        Character chr = mock(Character.class);
        when(c.getPlayer()).thenReturn(chr);
        when(chr.isAlive()).thenReturn(true);

        Inventory useInv = mock(Inventory.class);
        when(chr.getInventory(InventoryType.USE)).thenReturn(useInv);

        Item bag = mock(Item.class);
        when(bag.getQuantity()).thenReturn((short) 1);
        when(bag.getItemId()).thenReturn(2100000); // Invalid summon bag with no mob data
        when(useInv.getItem((short) 1)).thenReturn(bag);

        UseSummonBagHandler handler = new UseSummonBagHandler();
        InPacket packet = createSummonBagPacket((short) 1, 2100000);

        assertDoesNotThrow(() -> handler.handlePacket(packet, c));
        verify(c).sendPacket(argThat(p -> Arrays.equals(p.getBytes(), PacketCreator.enableActions().getBytes())));
    }
}
