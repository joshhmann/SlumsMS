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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UseItemHandlerTest {

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

    private InPacket createUseItemPacket(short slot, int itemId) {
        OutPacket out = new ByteBufOutPacket();
        out.writeInt(0); // timestamp
        out.writeShort(slot);
        out.writeInt(itemId);
        return new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes()));
    }

    @Test
    void testExpeditionPotionCountExhaustedSendsEnableActions() throws Exception {
        Client c = mock(Client.class);
        Character chr = mock(Character.class);
        when(c.getPlayer()).thenReturn(chr);
        when(chr.isAlive()).thenReturn(true);
        chr.inExpedition = true;
        chr.potionCount = 0;

        Inventory useInv = mock(Inventory.class);
        when(chr.getInventory(InventoryType.USE)).thenReturn(useInv);

        Item potion = mock(Item.class);
        when(potion.getQuantity()).thenReturn((short) 10);
        when(potion.getItemId()).thenReturn(2000000); // Red Potion
        when(useInv.getItem((short) 1)).thenReturn(potion);

        UseItemHandler handler = new UseItemHandler();
        InPacket packet = createUseItemPacket((short) 1, 2000000);
        handler.handlePacket(packet, c);

        verify(chr).dropMessage(eq(5), contains("no remaining potion uses"));
        verify(c).sendPacket(argThat(p -> Arrays.equals(p.getBytes(), PacketCreator.enableActions().getBytes())));
    }

    @Test
    void testMissingItemSendsEnableActions() throws Exception {
        Client c = mock(Client.class);
        Character chr = mock(Character.class);
        when(c.getPlayer()).thenReturn(chr);
        when(chr.isAlive()).thenReturn(true);

        Inventory useInv = mock(Inventory.class);
        when(chr.getInventory(InventoryType.USE)).thenReturn(useInv);
        when(useInv.getItem((short) 1)).thenReturn(null);

        UseItemHandler handler = new UseItemHandler();
        InPacket packet = createUseItemPacket((short) 1, 2000000);
        handler.handlePacket(packet, c);

        verify(c).sendPacket(argThat(p -> Arrays.equals(p.getBytes(), PacketCreator.enableActions().getBytes())));
    }
}
