package server.maps;

import config.YamlConfig;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotMapCap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.5.0 (3k bots): a map instance counts its bots on entry and exit, and the cap's full set follows it. */
class MapBotCountTest {

    static final int MAP = 910000996;

    @Test
    void botsAreCountedInAndOutAndRealPlayersAreNot() throws Exception {
        MapEntryHarness.initItemProvider();
        String saved = YamlConfig.config.server.BOT_MAP_CAP_OVERRIDES;
        YamlConfig.config.server.BOT_MAP_CAP_OVERRIDES = MAP + ":3";
        try (MapEntryHarness h = new MapEntryHarness(MAP)) {
            h.bots(2, 400);
            assertEquals(2, h.map.getBotCount());
            assertTrue(BotMapCap.hasRoom(h.map));
            assertFalse(BotMapCap.fullMaps(1).contains(MAP));

            var me = h.player(0);
            h.enter(me);
            assertEquals(2, h.map.getBotCount(), "a real player is not a bot");

            h.bot(10);
            h.map.addPlayer(h.bots.get(0)); // a second add of the same bot counts once
            assertEquals(3, h.map.getBotCount());
            assertFalse(BotMapCap.hasRoom(h.map));
            assertTrue(BotMapCap.fullMaps(1).contains(MAP), "at its cap: in the full set");

            h.map.removePlayer(h.bots.get(2));
            assertEquals(2, h.map.getBotCount());
            assertTrue(BotMapCap.hasRoom(h.map));
            assertFalse(BotMapCap.fullMaps(1).contains(MAP), "a bot left: out of the full set");
            h.map.removePlayer(h.bots.get(2)); // not there any more: no double count
            assertEquals(2, h.map.getBotCount());
            h.leave(me);
            assertEquals(2, h.map.getBotCount());
        } finally {
            YamlConfig.config.server.BOT_MAP_CAP_OVERRIDES = saved;
        }
    }
}
