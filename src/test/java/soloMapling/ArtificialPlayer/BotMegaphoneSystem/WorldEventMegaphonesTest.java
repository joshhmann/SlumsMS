package soloMapling.ArtificialPlayer.BotMegaphoneSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldEventMegaphonesTest {

    private final WorldEventMegaphones megas = WorldEventMegaphones.getInstance();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final AtomicBoolean gateOpen = new AtomicBoolean(true);
    private final List<WorldEventMegaphones.Shout> posted = new ArrayList<>();

    private void seams() {
        megas.useForTest(now::get, gateOpen::get, posted::add);
    }

    @AfterEach
    void restore() {
        megas.useForTest(null, null, null);
        System.clearProperty("solomapling.rareDropMaxChance");
    }

    private WorldEventMegaphones.Shout shout(String key) {
        return new WorldEventMegaphones.Shout(null, "WorldBossKillWitness", Map.of("BOSS", "Zakum"), key, now.get());
    }

    @Test
    void notableBossesSkipBodyPartsAndTrivialMobs() {
        assertTrue(WorldEventMegaphones.isNotableBoss(true, "Zakum", 140));
        assertTrue(WorldEventMegaphones.isNotableBoss(true, "Mushmom", 30));
        assertFalse(WorldEventMegaphones.isNotableBoss(true, "Zakum's Arm 1", 140));
        assertFalse(WorldEventMegaphones.isNotableBoss(true, "Horntail's Left Head", 160));
        assertFalse(WorldEventMegaphones.isNotableBoss(false, "Jr. Wraith", 60));
        assertFalse(WorldEventMegaphones.isNotableBoss(true, "Mano", 8));
    }

    @Test
    void rareDropsAreRareScrollsOrBossEquips() {
        assertTrue(WorldEventMegaphones.isRareDrop(2049100, 150, false, (short) 0), "chaos scroll");
        assertFalse(WorldEventMegaphones.isRareDrop(2040807, 700, false, (short) 0), "a common scroll chance");
        assertFalse(WorldEventMegaphones.isRareDrop(1002357, 700, false, (short) 0), "an equip off a normal mob");
        assertTrue(WorldEventMegaphones.isRareDrop(1002357, 700, true, (short) 0), "an equip off a boss");
        assertFalse(WorldEventMegaphones.isRareDrop(2049100, 150, false, (short) 1234), "quest items never");
        assertFalse(WorldEventMegaphones.isRareDrop(4000000, 10, false, (short) 0), "etc items never");
        System.setProperty("solomapling.rareDropMaxChance", "700");
        assertTrue(WorldEventMegaphones.isRareDrop(2040807, 700, false, (short) 0), "threshold is a -D tunable");
    }

    @Test
    void tokensFillOrTheLineIsSkipped() {
        assertEquals("gg Josh!! Zakum down",
                WorldEventMegaphones.fill("gg {PLAYER}!! {BOSS} down", Map.of("PLAYER", "Josh", "BOSS", "Zakum")));
        assertNull(WorldEventMegaphones.fill("gg {PLAYER}!! {BOSS} down", Map.of("BOSS", "Zakum")));
    }

    @Test
    void everyWorldNodeHasLinesThatFillFromItsTokens() {
        Map<String, Map<String, String>> nodes = Map.of(
                "WorldBossKillSelf", Map.of("BOSS", "Zakum", "CH", "1"),
                "WorldBossKillWitness", Map.of("BOSS", "Zakum", "CH", "1", "PLAYER", "Josh"),
                "WorldBossKillHeard", Map.of("BOSS", "Zakum", "CH", "1", "PLAYER", "Josh"),
                "WorldRareDropSelf", Map.of("ITEM", "Chaos Scroll 60%", "MOB", "Drake", "CH", "1"),
                "WorldRareDropWitness", Map.of("ITEM", "Chaos Scroll 60%", "MOB", "Drake", "CH", "1", "PLAYER", "Josh"),
                "WorldPQForming", Map.of("PQ", "OPQ", "LEVEL", "55", "JOB", "priest", "CH", "1"));
        nodes.forEach((node, tokens) -> {
            String line = WorldEventMegaphones.pickLine(node, tokens);
            assertNotNull(line, node);
            assertFalse(line.contains("{"), node + ": " + line);
            assertTrue(line.length() <= 70, node + " too long: " + line);
        });
    }

    @Test
    void anEventGoesOutAtOnceWhenTheGateIsOpen() {
        seams();
        assertTrue(megas.offer(shout("boss:zakum:1")));
        assertEquals(1, posted.size());
    }

    @Test
    void theSameEventIsNotRepeatedWithinTheCooldown() {
        seams();
        megas.offer(shout("boss:zakum:1"));
        now.addAndGet(60_000);
        assertFalse(megas.offer(shout("boss:zakum:1")));
        assertEquals(1, posted.size());
        now.addAndGet(600_000);
        assertTrue(megas.offer(shout("boss:zakum:1")));
        assertEquals(2, posted.size());
    }

    @Test
    void aClosedGateParksTheEventUntilTheTickerFlushesIt() {
        seams();
        gateOpen.set(false);
        assertFalse(megas.offer(shout("drop:1:2049100")));
        assertTrue(posted.isEmpty(), "the gate still rules: nothing went out");
        assertNotNull(megas.pendingShout());
        assertFalse(megas.flushPending(), "gate still closed");
        gateOpen.set(true);
        now.addAndGet(120_000);
        assertTrue(megas.flushPending());
        assertEquals(1, posted.size());
        assertNull(megas.pendingShout());
    }

    @Test
    void aStaleParkedEventIsDropped() {
        seams();
        gateOpen.set(false);
        megas.offer(shout("pq:opq"));
        gateOpen.set(true);
        now.addAndGet(300_000);
        assertFalse(megas.flushPending());
        assertTrue(posted.isEmpty());
        assertNull(megas.pendingShout());
    }
}
