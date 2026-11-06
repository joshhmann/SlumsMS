package server.maps;

import client.BotClient;
import client.Character;
import net.opcodes.SendOpcode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * b350 FAIL-BEFORE test (expected to fail until the fix): a bot and a real player entering a map at nearly the same
 * time. The bot's spawn broadcast runs before the player is in `characters`, and the player's placement snapshot runs
 * before the bot is in `mapobjects`, so the player never gets the bot's SPAWN_PLAYER.
 */
class MapEntryRaceB350Test {
    @BeforeAll
    static void init() throws Exception {
        MapEntryHarness.initItemProvider();
    }

    @AfterEach
    void clearSeam() {
        MapleMap.afterSpawnBroadcast = null;
    }

    @Test
    void playerEnteringBetweenABotsBroadcastAndItsPlacementStillGetsTheBotSpawn() {
        try (MapEntryHarness h = new MapEntryHarness(100000000)) {
            Character bot = Character.getDefault(new BotClient(0, 0));
            bot.setID(1_000_001);
            bot.setName("RaceBot");
            bot.setPosition(new Point(0, 0));
            bot.setMap(h.map);
            Character me = h.player(0);

            // Interleaving: bot step 1+3 (in characters, broadcast to nobody), then the whole player entry, then the bot's step 5.
            MapleMap.afterSpawnBroadcast = c -> {
                if (c == bot) {
                    MapleMap.afterSpawnBroadcast = null;
                    h.enter(me);
                }
            };
            h.map.addPlayer(bot);
            h.scheduler.drain();

            List<Integer> spawned = h.sent.stream().filter(s -> s.opcode() == SendOpcode.SPAWN_PLAYER.getValue())
                    .map(s -> s.intAt(2)).toList();
            assertTrue(spawned.contains(bot.getId()),
                    "player never received SPAWN_PLAYER for the bot; spawned ids=" + spawned);
        }
    }
}
