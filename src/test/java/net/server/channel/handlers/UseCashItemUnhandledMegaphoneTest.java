package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.packet.InPacket;
import net.server.Server;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import server.ItemInformationProvider;
import service.NoteService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** b297: a 507-class cash item with no broadcast handler (5070000/5073000/5074000) must stay in the inventory. */
class UseCashItemUnhandledMegaphoneTest {

    @BeforeAll
    static void initializeProviderWithoutGameData() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {5070000, 5073000, 5074000})
    void anUnhandledMegaphoneIsNotConsumed(int itemId) {
        Client c = mock(Client.class);
        Character chr = mock(Character.class);
        Inventory cash = mock(Inventory.class);
        when(c.getPlayer()).thenReturn(chr);
        when(chr.getLevel()).thenReturn(50);
        when(chr.getInventory(InventoryType.CASH)).thenReturn(cash);
        when(chr.getInventory(InventoryType.EQUIPPED)).thenReturn(mock(Inventory.class));
        when(cash.getItem((short) 1)).thenReturn(new Item(itemId, (short) 1, (short) 1));

        InPacket p = mock(InPacket.class);
        when(p.readShort()).thenReturn((short) 1);
        when(p.readInt()).thenReturn(itemId);
        when(p.readString()).thenReturn("hi");

        ItemInformationProvider ii = mock(ItemInformationProvider.class);
        try (var iip = mockStatic(ItemInformationProvider.class);
             var im = mockStatic(client.inventory.manipulator.InventoryManipulator.class);
             var srv = mockStatic(Server.class)) {
            iip.when(ItemInformationProvider::getInstance).thenReturn(ii);
            Server server = mock(Server.class);
            when(server.getCurrentTime()).thenReturn(1_000_000L);
            srv.when(Server::getInstance).thenReturn(server);
            new UseCashItemHandler(mock(NoteService.class)).handlePacket(p, c);
            im.verify(() -> client.inventory.manipulator.InventoryManipulator.removeFromSlot(
                    any(), any(), org.mockito.ArgumentMatchers.anyShort(), org.mockito.ArgumentMatchers.anyShort(), org.mockito.ArgumentMatchers.anyBoolean(),
                    org.mockito.ArgumentMatchers.anyBoolean()), never());
        }
        verify(cash, never()).removeItem(org.mockito.ArgumentMatchers.anyShort());
    }
}
