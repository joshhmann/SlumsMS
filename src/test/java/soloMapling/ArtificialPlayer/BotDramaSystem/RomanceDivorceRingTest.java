package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import client.Client;
import client.Ring;
import client.inventory.Item;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import net.server.channel.handlers.RingActionHandler;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * rc6 auto-QA triage (t-rm-4, 2026-10-23): "@romance divorce" said "Your ring is gone" but the stock break only
 * shows the drop, so the dead wedding ring stayed in the equip tab; after a few t-rm-2 runs the pretend player's tab
 * was full and every later wedding was refused ("make room for the ring in your equip inventory").
 */
class RomanceDivorceRingTest {

    @Test
    void divorceTakesTheWeddingRingOutOfTheEquipTab() {
        Character player = mock(Character.class);
        Client client = mock(Client.class);
        Inventory equip = new Inventory(player, InventoryType.EQUIP, (byte) 24);
        Item ringItem = new Item(1112803, (short) 7, (short) 1);
        equip.addItemFromDB(ringItem);
        Ring ring = mock(Ring.class);
        when(ring.getItemId()).thenReturn(1112803);
        when(player.getPartnerId()).thenReturn(BotStore.BOT_ID_BASE + 42);
        when(player.getMarriageRing()).thenReturn(ring);
        when(player.getInventory(InventoryType.EQUIP)).thenReturn(equip);
        when(player.getClient()).thenReturn(client);
        try (var rings = mockStatic(RingActionHandler.class);
             var manip = mockStatic(InventoryManipulator.class)) {
            manip.when(() -> InventoryManipulator.removeFromSlot(eq(client), eq(InventoryType.EQUIP), anyShort(),
                    anyShort(), anyBoolean())).thenAnswer(call -> {
                equip.removeSlot(call.getArgument(2));
                return null;
            });
            assertEquals("Divorced. Your ring is gone.", BotRomance.divorce(player));
            rings.verify(() -> RingActionHandler.breakMarriageRing(player, 1112803));
        }
        assertEquals(0, equip.list().size(), "the dead ring must not stay in the equip tab");
    }
}
