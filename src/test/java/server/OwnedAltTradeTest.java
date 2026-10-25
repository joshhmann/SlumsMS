package server;

import client.BotClient;
import client.Character;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import tools.PacketCreator;
import java.lang.reflect.Method;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OwnedAltTradeTest {
    @Test void cancellationReturnsRealAltItemsToItsInventory() throws Exception {
        Character alt = mock(Character.class); BotClient client = mock(BotClient.class);
        when(alt.getClient()).thenReturn(client); when(alt.isOwnedAlt()).thenReturn(true);
        Trade trade = new Trade((byte) 0, alt);
        Item potion = new Item(2000000, (short) 1, (short) 3, -1);
        try (MockedStatic<PacketCreator> packets = mockStatic(PacketCreator.class);
             MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            inventory.when(() -> InventoryManipulator.addFromDrop(eq(client), eq(potion), anyBoolean())).thenReturn(true);
            trade.addItem(potion);
            Method cancel = Trade.class.getDeclaredMethod("cancel", byte.class); cancel.setAccessible(true);
            cancel.invoke(trade, (byte) 0);
            inventory.verify(() -> InventoryManipulator.addFromDrop(eq(client), eq(potion), anyBoolean()));
        }
    }
    @Test void receivedAltItemsUseInventoryInsteadOfPopulationHoldings() throws Exception {
        Character alt = mock(Character.class); BotClient client = mock(BotClient.class);
        when(alt.getClient()).thenReturn(client); when(alt.isOwnedAlt()).thenReturn(true);
        Trade trade = new Trade((byte) 0, alt);
        Item potion = new Item(2000000, (short) 1, (short) 3, -1);
        var received = Trade.class.getDeclaredField("exchangeItems"); received.setAccessible(true);
        received.set(trade, new java.util.ArrayList<>(java.util.List.of(potion)));
        try (MockedStatic<PacketCreator> packets = mockStatic(PacketCreator.class);
             MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class);
             MockedStatic<soloMapling.ArtificialPlayer.BotHoldings> holdings = mockStatic(soloMapling.ArtificialPlayer.BotHoldings.class)) {
            inventory.when(() -> InventoryManipulator.addFromDrop(eq(client), eq(potion), anyBoolean())).thenReturn(true);
            Method complete = Trade.class.getDeclaredMethod("completeTrade"); complete.setAccessible(true); complete.invoke(trade);
            inventory.verify(() -> InventoryManipulator.addFromDrop(eq(client), eq(potion), anyBoolean()));
            holdings.verifyNoInteractions();
        }
    }
}
