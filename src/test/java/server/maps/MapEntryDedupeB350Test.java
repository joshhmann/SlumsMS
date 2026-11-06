package server.maps;

import client.Character;
import net.opcodes.SendOpcode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** b350 fix tests: the entry spawn reaches a player exactly once per entry, other spawn paths are untouched. */
class MapEntryDedupeB350Test {
    @BeforeAll
    static void init() throws Exception {
        MapEntryHarness.initItemProvider();
    }

    @AfterEach
    void clearSeam() {
        MapleMap.afterSpawnBroadcast = null;
    }

    private static long spawnsOf(MapEntryHarness h, Character who) {
        return h.sent.stream().filter(s -> s.opcode() == SendOpcode.SPAWN_PLAYER.getValue() && s.intAt(2) == who.getId()).count();
    }

    /** Opposite interleaving to MapEntryRaceB350Test: the player is in `characters` and the bot is in `mapobjects`. */
    @Test
    void botEnteringAfterThePlayerIsInCharactersButBeforeItsSnapshotSpawnsOnce() {
        try (MapEntryHarness h = new MapEntryHarness(100000000)) {
            Character me = h.player(0);
            MapleMap.afterSpawnBroadcast = c -> {
                if (c == me) {
                    MapleMap.afterSpawnBroadcast = null;
                    h.bot(0); // whole bot entry: broadcast reaches the player, bot lands in mapobjects, then the player snapshots
                }
            };
            h.enter(me);
            h.scheduler.drain();
            assertEquals(1, spawnsOf(h, h.bots.get(0)), "exactly one SPAWN_PLAYER for the bot");
        }
    }

    @Test
    void aBotThatLeavesAndReentersWhilePlayerStaysIsSpawnedOncePerEntry() {
        try (MapEntryHarness h = new MapEntryHarness(100000000)) {
            Character me = h.player(0);
            h.enter(me);
            Character bot = h.bot(0);
            assertEquals(1, spawnsOf(h, bot));
            h.map.removePlayer(bot);
            bot.setMap(h.map);
            h.map.addPlayer(bot);
            h.scheduler.drain();
            assertEquals(2, spawnsOf(h, bot), "one spawn for each entry");
        }
    }

    /** Character.Hide(false) re-broadcasts the spawn with enteringField=false through this very call. */
    @Test
    void aNonEntryRespawnStillSendsItsPacketAfterTheEntrySpawn() {
        try (MapEntryHarness h = new MapEntryHarness(100000000)) {
            Character me = h.player(0);
            h.enter(me);
            Character bot = h.bot(0);
            assertEquals(1, spawnsOf(h, bot));
            h.map.broadcastSpawnPlayerMapObjectMessage(bot, bot, false);
            assertEquals(2, spawnsOf(h, bot), "the unhide-style respawn is not deduped");
        }
    }
}
