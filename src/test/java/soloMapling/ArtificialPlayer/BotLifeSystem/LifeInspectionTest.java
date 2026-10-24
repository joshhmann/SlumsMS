package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.BotClient;
import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

class LifeInspectionTest {
    @Test
    void emptyHpPotsGivePartialRestockPreviewWithoutInventingOtherNeeds() {
        var snapshot = snapshot(0, .2, false, false);
        Result result = LifeInspection.preview(snapshot);
        assertEquals(Activity.RESTOCK, result.selected().orElseThrow());
        assertEquals(2, result.ranking().size(), "do not invent a third observable candidate");
        assertEquals(2, snapshot.previewNeeds().values().size());
        assertFalse(snapshot.previewNeeds().values().containsKey(Need.SOCIAL));
        String report = String.join("\n", LifeInspection.describe(snapshot, result));
        assertTrue(report.contains("PREVIEW ONLY"));
        assertTrue(report.contains("Unknown: MP/ammo"));
        assertTrue(report.contains("No activity is executed"));
    }

    @Test
    void aFullBagOutweighsRestockingWhenThereAreEnoughPotions() {
        var snapshot = snapshot(100, 1, false, false);
        assertEquals(Activity.SELL, LifeInspection.preview(snapshot).selected().orElseThrow());
    }

    @Test
    void openCommitmentsAndConversationsSuppressAnyPreviewSelection() {
        for (var snapshot : List.of(snapshot(0, 1, true, false), snapshot(0, 1, false, true))) {
            Result result = LifeInspection.preview(snapshot);
            assertEquals(Outcome.HOLD_COMMITMENT, result.outcome());
            assertTrue(result.selected().isEmpty());
            assertEquals(2, result.ranking().size(), "scores remain visible while held");
        }
    }

    @Test
    void captureReadsBotCharacterDirectlyAndNeverUsesItsNullClientPlayerOrWritesInventory() {
        Character chr = mock(Character.class);
        BotClient client = mock(BotClient.class); // getPlayer() deliberately null, as on a real bot
        BotSM bot = mock(BotSM.class);
        when(bot.getChr()).thenReturn(chr);
        when(bot.getState()).thenReturn(BotSM.BotState.TRADING);
        when(bot.getBotType()).thenReturn("TrainingBot");
        when(chr.getClient()).thenReturn(client);
        when(chr.getName()).thenReturn("FixtureBot");
        when(chr.getId()).thenReturn(1_000_005_001);
        when(chr.getLevel()).thenReturn(45);
        when(chr.getMapId()).thenReturn(100000000);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSlotLimit()).thenReturn((byte) 24);
        when(inventory.getNumFreeSlot()).thenReturn((short) 6);
        for (InventoryType type : List.of(InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP,
                InventoryType.ETC)) {
            when(chr.getInventory(type)).thenReturn(inventory);
        }
        try (var vitals = mockStatic(BotVitals.class); var conversations = mockStatic(BotConversation.class)) {
            vitals.when(() -> BotVitals.potionCount(chr, true)).thenReturn(7);
            var snapshot = LifeInspection.capture(bot);
            assertEquals("FixtureBot", snapshot.name());
            assertEquals(7, snapshot.hpPotions());
            assertEquals(.75, snapshot.bagPressure());
            assertTrue(snapshot.commitment());
            verifyNoInteractions(client);
            verify(inventory, times(4)).lockInventory();
            verify(inventory, times(4)).unlockInventory();
            verify(inventory, times(4)).getSlotLimit();
            verify(inventory, times(4)).getNumFreeSlot();
            verifyNoMoreInteractions(inventory);
            vitals.verify(() -> BotVitals.potionCount(chr, true));
            vitals.verifyNoMoreInteractions();
        }
    }

    private static LifeInspection.Snapshot snapshot(int pots, double bag, boolean commitment, boolean chat) {
        return new LifeInspection.Snapshot("FixtureBot", 45, 100000000, "TrainingBot", "RUNNING",
                pots, 10, bag, commitment, chat);
    }
}
