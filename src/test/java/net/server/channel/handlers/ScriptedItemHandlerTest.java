package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import server.ItemInformationProvider.ScriptedItem;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** s-crystal: the client locks after 0x4E until the server sends enableActions, so every exit must send it. */
class ScriptedItemHandlerTest {

    private Client client(Item inSlot) {
        Client c = mock(Client.class);
        Character chr = mock(Character.class);
        Inventory inv = mock(Inventory.class);
        when(c.getPlayer()).thenReturn(chr);
        when(chr.getInventory(any(InventoryType.class))).thenReturn(inv);
        when(inv.getItem((short) 3)).thenReturn(inSlot);
        return c;
    }

    @Test
    void unknownItemStillUnlocksTheClient() {
        Client c = client(null);
        ScriptedItemHandler.handle(c, (short) 3, 2430159, null);
        verify(c).sendPacket(any());
    }

    @Test
    void wrongSlotStillUnlocksTheClient() {
        Client c = client(null);
        ScriptedItemHandler.handle(c, (short) 3, 2430159, new ScriptedItem(2020005, "AlcasterCrystal", false));
        verify(c).sendPacket(any());
    }

    @Test
    void scriptThatDoesNotRunStillUnlocksTheClient() {
        Client c = client(new Item(2430159, (short) 3, (short) 1));
        when(c.canClickNPC()).thenReturn(false);
        ScriptedItemHandler.handle(c, (short) 3, 2430159, new ScriptedItem(2020005, "AlcasterCrystal", false));
        verify(c, atLeastOnce()).sendPacket(any());
    }
}
