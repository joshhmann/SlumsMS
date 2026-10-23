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
    // ---- rc6, owner QA 2026-10-23: war / recruit megaphones never showed during !qa gw-1, gw-2, gu-3 ----

    private WorldEventMegaphones.Shout shout(String node, String key) {
        return new WorldEventMegaphones.Shout(null, node, Map.of("GUILD", "Legendz"), key, now.get());
    }

    @Test
    void aMegaphoneTheRunningQaScenarioWatchesSkipsTheGateAndTheCooldown() {
        seams();
        List<String> noted = new ArrayList<>();
        megas.forceForTest(key -> key.startsWith("drama:war"), () -> noted.add("note"));
        gateOpen.set(false);
        assertTrue(megas.offer(shout("GuildWarDeclare", "drama:war:7")), "out at once though the gate is shut");
        assertTrue(megas.offer(shout("GuildWarDeclare", "drama:war:7")), "and again: a QA step repeats on purpose");
        assertEquals(2, posted.size());
        assertEquals(2, noted.size(), "the next ambient megaphone still waits a full gap");
        assertFalse(megas.offer(shout("GuildRecruitCall", "guild:recruit:1")), "a key nobody watches keeps the gate");
        assertEquals(2, posted.size());
    }

    @Test
    void aRoutinePqCallNeverPushesAWaitingGuildEventOutOfTheSlot() {
        seams();
        gateOpen.set(false);
        assertFalse(megas.offer(shout("GuildWarTaunt", "drama:wartaunt:7:1")));
        assertFalse(megas.offer(shout("WorldPQForming", "pq:opq")));
        assertEquals("GuildWarTaunt", megas.pendingShout().node(), "the war taunt still waits");
        assertFalse(megas.offer(shout("GuildPoach", "drama:poach:9")));
        assertEquals("GuildPoach", megas.pendingShout().node(), "a newer real event still replaces it");
        gateOpen.set(true);
        assertTrue(megas.flushPending());
        assertEquals("GuildPoach", posted.get(0).node());
    }

    @Test
    void guildAndWarKeysMapToTheirQaTopics() {
        assertEquals(soloMapling.qa.QaLog.Topic.GUILD_WAR, WorldEventMegaphones.qaTopic("drama:war:3"));
        assertEquals(soloMapling.qa.QaLog.Topic.GUILD_WAR, WorldEventMegaphones.qaTopic("drama:wartaunt:3:99"));
        assertEquals(soloMapling.qa.QaLog.Topic.GUILD_WAR, WorldEventMegaphones.qaTopic("drama:peace:3"));
        assertEquals(soloMapling.qa.QaLog.Topic.BOT_GUILD, WorldEventMegaphones.qaTopic("guild:recruit:1:5"));
        assertNull(WorldEventMegaphones.qaTopic("drama:breakup:4"), "romance drama is not a guild megaphone");
        assertNull(WorldEventMegaphones.qaTopic("pq:opq"));
        assertFalse(WorldEventMegaphones.qaWatches("guild:recruit:1"), "no !qa run: never forced (live)");
    }
}
