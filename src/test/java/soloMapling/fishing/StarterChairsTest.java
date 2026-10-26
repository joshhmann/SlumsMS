package soloMapling.fishing;

import client.BotClient;
import client.Character;
import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StarterChairsTest {
    private static Character player() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(4242);
        chr.setClient(mock(Client.class));
        return chr;
    }

    private static int count(Character chr, int id) {
        return chr.getInventory(InventoryType.SETUP).countById(id);
    }

    @Test
    void loginGrantsBothOnceAndASoldChairIsNotGivenAgain() {
        Character chr = player();
        assertTrue(StarterChairs.onLogin(chr));
        assertEquals(1, count(chr, StarterChairs.RELAXER));
        assertEquals(1, count(chr, FishingPolicy.CHAIR));
        chr.getInventory(InventoryType.SETUP).removeItem(chr.getInventory(InventoryType.SETUP).findById(FishingPolicy.CHAIR).getPosition());
        assertFalse(StarterChairs.onLogin(chr));
        assertEquals(0, count(chr, FishingPolicy.CHAIR));
    }

    @Test
    void ownerOfEitherChairIsOnlyMarked() {
        Character chr = player();
        chr.getInventory(InventoryType.SETUP).addItem(new Item(StarterChairs.RELAXER, (short) 0, (short) 1));
        assertFalse(StarterChairs.onLogin(chr));
        assertEquals(0, count(chr, FishingPolicy.CHAIR));
        assertTrue(StarterChairs.given(chr));
    }

    @Test
    void fullBagRetriesNextLoginAndTellsOnce() {
        Character chr = spy(player());
        doNothing().when(chr).dropMessage(anyInt(), anyString());
        var setup = chr.getInventory(InventoryType.SETUP);
        for (int i = 0; setup.getNumFreeSlot() > 1; i++) setup.addItem(new Item(3010001 + i, (short) 0, (short) 1));
        // only one slot free: neither chair is owned, so nothing fits
        assertFalse(StarterChairs.onLogin(chr));
        assertFalse(StarterChairs.given(chr));
        StarterChairs.noticeIfWaiting(chr);
        StarterChairs.noticeIfWaiting(chr);
        verify(chr, times(1)).dropMessage(eq(5), anyString());
        setup.removeItem(setup.list().iterator().next().getPosition());
        setup.removeItem(setup.list().iterator().next().getPosition());
        assertTrue(StarterChairs.onLogin(chr));
        assertEquals(1, count(chr, StarterChairs.RELAXER));
    }

    @Test
    void creationRecipeHasBothChairs() {
        var recipe = new client.creator.CharacterFactoryRecipe(client.Job.BEGINNER, 1, 100000000, 0, 0, 0, 0);
        StarterChairs.addToRecipe(recipe);
        var ids = recipe.getStartingItems().stream().filter(p -> p.getRight() == InventoryType.SETUP)
                .map(p -> p.getLeft().getItemId()).toList();
        assertEquals(java.util.List.of(StarterChairs.RELAXER, FishingPolicy.CHAIR), ids);
    }

    @Test
    void botsAreUntouched() {
        Character bot = player();
        bot.setID(soloMapling.server.SoloMaplingConstants.GameConstants.BOT_BASE_ID + 5);
        assertFalse(StarterChairs.onLogin(bot));
        assertEquals(0, count(bot, FishingPolicy.CHAIR));
    }
}
