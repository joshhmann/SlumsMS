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
    private static Character sitter(int mapId, int chair, int id) {
        Character chr = mock(Character.class); BotClient client = mock(BotClient.class);
        MapleMap map = mock(MapleMap.class); Inventory inventory = mock(Inventory.class); BotSM bot = mock(BotSM.class);
        when(chr.getId()).thenReturn(id); when(chr.getClient()).thenReturn(client);
        when(chr.getMap()).thenReturn(map); when(chr.getMapId()).thenReturn(mapId);
        when(chr.getWorld()).thenReturn(1); when(map.getChannelId()).thenReturn(1); when(map.getId()).thenReturn(mapId);
        when(chr.getPosition()).thenReturn(new Point(20, 120)); when(chr.getChair()).thenReturn(chair);
        when(chr.isAlive()).thenReturn(true); when(chr.getInventory(InventoryType.SETUP)).thenReturn(inventory);
        Item item = new Item(chair, (short) 1, (short) 1);
        when(inventory.findById(chair)).thenReturn(item); when(inventory.list()).thenReturn(java.util.List.of(item));
        when(bot.getChr()).thenReturn(chr); when(bot.getRunning()).thenReturn(true);
        return chr;
    }

    /** A player-side sitter: a normal (non-bot) client, logged in, owning and sitting on {@code chair}. */
    private static Character playerSitter(int mapId, int chair, int id) {
        Character chr = mock(Character.class); client.Client client = mock(client.Client.class);
        MapleMap map = mock(MapleMap.class); Inventory inventory = mock(Inventory.class);
        when(chr.getId()).thenReturn(id); when(chr.getClient()).thenReturn(client); when(chr.isLoggedinWorld()).thenReturn(true);
        when(chr.getMap()).thenReturn(map); when(chr.getMapId()).thenReturn(mapId);
        when(chr.getWorld()).thenReturn(1); when(map.getChannelId()).thenReturn(1); when(map.getId()).thenReturn(mapId);
        when(chr.getPosition()).thenReturn(new Point(20, 120)); when(chr.getChair()).thenReturn(chair);
        when(chr.isAlive()).thenReturn(true); when(chr.getInventory(InventoryType.SETUP)).thenReturn(inventory);
        Item item = new Item(chair, (short) 1, (short) 1);
        when(inventory.findById(chair)).thenReturn(item); when(inventory.list()).thenReturn(java.util.List.of(item));
        return chr;
    }

    /** 1.6.0 (owner, 2026-10-27): players fish from any chair on a fishing map; off a fishing map nothing fishes. */
    @Test void playersFishFromAnyChairOnAFishingMapButNotElsewhereAndStandingCancels() {
        String enabled = System.getProperty("slums.fishing.enabled"), rewards = System.getProperty("slums.fishing.rewards");
        Character relaxer = playerSitter(120010000, 3010000, 881_011);
        Character offMap = playerSitter(100000000, 3010000, 881_012);
        Character plain = playerSitter(120010000, 3010000, 881_013);
        try {
            System.setProperty("slums.fishing.enabled", "true"); System.setProperty("slums.fishing.rewards", "true");
            assertEquals(3010000, GameFishing.chairToUse(relaxer), "a player's Relaxer is a fishing chair");
            assertTrue(GameFishing.start(relaxer)); assertTrue(GameFishing.active(relaxer));
            assertFalse(GameFishing.start(offMap), "a chair off a fishing map does not fish");
            assertFalse(GameFishing.active(offMap));
            when(plain.getChair()).thenReturn(3012000);
            assertTrue(GameFishing.start(plain), "3012xxx chairs fish too");
            when(relaxer.getChair()).thenReturn(-1);
            GameFishing.tick(1);
            assertFalse(GameFishing.active(relaxer), "standing up ends the session");
        } finally {
            GameFishing.cancel(relaxer); GameFishing.cancel(plain);
            restore("slums.fishing.enabled", enabled); restore("slums.fishing.rewards", rewards);
        }
    }

    /** 1.6.0 (owner, 2026-10-27): only bots that own the Fishing Chair (3011000) fish, and only sitting on it. */
    @Test void botsFishOnlyFromTheFishingChair() {
        String enabled = System.getProperty("slums.fishing.enabled"), rewards = System.getProperty("slums.fishing.rewards");
        Character relaxerOnly = sitter(120010000, 3010000, 1_000_881_021);
        Character plainOnly = sitter(120010000, 3012000, 1_000_881_022);
        Character owner = sitter(120010000, 3011000, 1_000_881_023);
        BotSM b1 = mock(BotSM.class), b2 = mock(BotSM.class), b3 = mock(BotSM.class);
        when(b1.getChr()).thenReturn(relaxerOnly); when(b1.getRunning()).thenReturn(true);
        when(b2.getChr()).thenReturn(plainOnly); when(b2.getRunning()).thenReturn(true);
        when(b3.getChr()).thenReturn(owner); when(b3.getRunning()).thenReturn(true);
        try (var storage = mockStatic(CharacterStorage.class)) {
            System.setProperty("slums.fishing.enabled", "true"); System.setProperty("slums.fishing.rewards", "true");
            storage.when(() -> CharacterStorage.getBotById(1_000_881_021)).thenReturn(b1);
            storage.when(() -> CharacterStorage.getBotById(1_000_881_022)).thenReturn(b2);
            storage.when(() -> CharacterStorage.getBotById(1_000_881_023)).thenReturn(b3);
            assertEquals(0, GameFishing.chairToUse(relaxerOnly), "a bot's Relaxer is not a fishing chair");
            assertFalse(GameFishing.choosable(relaxerOnly, map -> true), "a Relaxer alone never makes a bot pick FISH");
            assertFalse(GameFishing.start(relaxerOnly), "a bot on a Relaxer does not fish");
            assertFalse(GameFishing.choosable(plainOnly, map -> true));
            assertFalse(GameFishing.start(plainOnly), "a bot on a 3012xxx chair does not fish");
            assertEquals(3011000, GameFishing.chairToUse(owner));
            assertTrue(GameFishing.choosable(owner, map -> true), "a Fishing Chair owner may pick FISH");
            assertTrue(GameFishing.start(owner)); assertTrue(GameFishing.active(owner));
            when(owner.getChair()).thenReturn(3010000);
            GameFishing.tick(1);
            assertFalse(GameFishing.active(owner), "a chair owner moved onto another chair stops fishing");
        } finally {
            GameFishing.cancel(owner);
            restore("slums.fishing.enabled", enabled); restore("slums.fishing.rewards", rewards);
        }
    }
    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name); else System.setProperty(name, value);
    }
}
