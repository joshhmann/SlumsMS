package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import client.Ring;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import net.server.channel.handlers.RingActionHandler;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Ticket 188 (MA-02): a marriage whose ring item is gone can still be ended with @romance divorce. */
class RomanceDivorceNoRingTest {

    @Test
    void marriageWithoutARingItemCanBeEnded() {
        Character player = mock(Character.class);
        when(player.getPartnerId()).thenReturn(BotStore.BOT_ID_BASE + 3);
        when(player.getMarriageRing()).thenReturn(null);
        when(player.getMarriageItemId()).thenReturn(-1);
        try (var rings = mockStatic(RingActionHandler.class)) {
            assertEquals("Divorced. You are no longer married.", BotRomance.divorce(player));
            rings.verify(() -> RingActionHandler.breakMarriageRecord(player));
        }
    }

    @Test
    void ringRecordWithItemMissingFromBothTabsCanBeEnded() {
        Character player = mock(Character.class);
        Ring ring = mock(Ring.class);
        when(ring.getItemId()).thenReturn(1112803);
        when(player.getPartnerId()).thenReturn(BotStore.BOT_ID_BASE + 3);
        when(player.getMarriageRing()).thenReturn(ring);
        when(player.getInventory(InventoryType.EQUIP)).thenReturn(new Inventory(player, InventoryType.EQUIP, (byte) 24));
        when(player.getInventory(InventoryType.EQUIPPED)).thenReturn(new Inventory(player, InventoryType.EQUIPPED, (byte) 24));
        try (var rings = mockStatic(RingActionHandler.class)) {
            assertEquals("Divorced. You are no longer married.", BotRomance.divorce(player));
            rings.verify(() -> RingActionHandler.breakMarriageRecord(player));
        }
    }

    @Test
    void engagedStaysEngaged() {
        Character player = mock(Character.class);
        when(player.getPartnerId()).thenReturn(BotStore.BOT_ID_BASE + 3);
        when(player.getMarriageItemId()).thenReturn(4031357);
        assertEquals("You are engaged, not married: drop your engagement box to break it off.", BotRomance.divorce(player));
    }
}
