package soloMapling.fishing;

import client.BotClient;
import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import server.maps.MapleMap;
import java.awt.Point;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GameFishingTest {
    @Test void flagsOffDoNotReadOrChangeACharacter() {
        String old = System.getProperty("slums.fishing.enabled");
        try {
            System.clearProperty("slums.fishing.enabled");
            Character chr = mock(Character.class);
            assertFalse(GameFishing.start(chr)); verifyNoInteractions(chr);
        } finally { restore("slums.fishing.enabled", old); }
    }

    @Test void realHeadlessClientCanStartAndCancelWithoutAPlayerOnItsClient() {
        String enabled = System.getProperty("slums.fishing.enabled"), rewards = System.getProperty("slums.fishing.rewards");
        Character chr = mock(Character.class); BotClient client = mock(BotClient.class);
        MapleMap map = mock(MapleMap.class); Inventory inventory = mock(Inventory.class); BotSM bot = mock(BotSM.class);
        when(chr.getId()).thenReturn(1_000_881_003); when(chr.getClient()).thenReturn(client);
        when(chr.getMap()).thenReturn(map); when(chr.getMapId()).thenReturn(120010000);
        when(chr.getWorld()).thenReturn(1); when(map.getChannelId()).thenReturn(1); when(map.getId()).thenReturn(120010000);
        when(chr.getPosition()).thenReturn(new Point(20, 120)); when(chr.getChair()).thenReturn(3011000);
        when(chr.isAlive()).thenReturn(true); when(chr.getInventory(InventoryType.SETUP)).thenReturn(inventory);
        when(inventory.findById(3011000)).thenReturn(new Item(3011000, (short) 1, (short) 1));
        when(bot.getChr()).thenReturn(chr); when(bot.getRunning()).thenReturn(true);
        try (var storage = mockStatic(CharacterStorage.class)) {
            System.setProperty("slums.fishing.enabled", "true"); System.setProperty("slums.fishing.rewards", "true");
            storage.when(() -> CharacterStorage.getBotById(chr.getId())).thenReturn(bot);
            assertTrue(GameFishing.start(chr)); assertTrue(GameFishing.active(chr));
            assertTrue(GameFishing.start(chr), "repeated seating keeps the existing timer");
            verify(client, never()).getPlayer();
            System.setProperty("slums.fishing.enabled", "false");
            GameFishing.tick(1); assertFalse(GameFishing.active(chr), "turning the feature off releases the session");
        } finally {
            GameFishing.cancel(chr); restore("slums.fishing.enabled", enabled); restore("slums.fishing.rewards", rewards);
        }
    }
    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name); else System.setProperty(name, value);
    }
}
