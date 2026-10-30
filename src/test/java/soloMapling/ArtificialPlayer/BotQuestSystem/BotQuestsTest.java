package soloMapling.ArtificialPlayer.BotQuestSystem;

import client.Character;
import client.QuestStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.life.NPC;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifePlanner;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ticket 158 items 2 and 3: a grinding bot picks a quest whose monsters are on its map, takes it at the NPC, hunts and
 * turns it in. Watched: the real quest calls, near the NPC, rewards through the yield caps. Offscreen: the log only.
 */
class BotQuestsTest {
    static final int FIELD = 100010000, TOWN = 100000000, FAR_TOWN = 101000000;
    static final int SNAIL = 100100, BLUE = 100101, NPC_ID = 1012100, QUEST = 1001;
    static final long NOW = 1_800_000_000_000L;

    BotQuests.Gate oldGate;
    BotQuests.World oldWorld;
    BotQuests.Yield oldYield;
    FakeGate gate;
    FakeYield yield;
    Map<Integer, List<Integer>> mobs;
    Map<Integer, Set<Integer>> npcs;

    static BotQuestIndex.QuestMeta snailQuest(int id, int lvmin, Map<Integer, Integer> need, int exp, int mesos) {
        return new BotQuestIndex.QuestMeta(id, NPC_ID, NPC_ID, lvmin, 0, need, exp, mesos, Map.of(2000000, 5), false,
                false, false, List.of("mob", "npc"), true);
    }

    @BeforeEach
    void setUp() {
        System.setProperty("slums.botlife.enabled", "true");
        System.setProperty("slums.botlife.execute", "true");
        System.setProperty(BotQuests.PROP, "true");
        oldGate = BotQuests.gate;
        oldWorld = BotQuests.world;
        oldYield = BotQuests.yield;
        gate = new FakeGate();
        yield = new FakeYield();
        mobs = new HashMap<>(Map.of(FIELD, List.of(SNAIL, SNAIL, BLUE)));
        npcs = new HashMap<>(Map.of(TOWN, Set.of(NPC_ID)));
        BotQuests.gate = gate;
        BotQuests.yield = yield;
        BotQuests.world = new BotQuests.World() {
            @Override public List<Integer> mobsOn(int mapId) { return mobs.getOrDefault(mapId, List.of()); }
            @Override public boolean npcOn(int mapId, int npcId) { return npcs.getOrDefault(mapId, Set.of()).contains(npcId); }
            @Override public int returnMap(int mapId) { return mapId == FIELD ? TOWN : -1; }
            @Override public int hops(Character chr, int from, int to) { return from == to ? 0 : 1; }
            @Override public double[] rate(Character chr, int mapId) { return new double[]{10, 20}; } // 200 EXP/min
            @Override public double questExpFactor(Character chr) { return 1; }
        };
        BotQuestIndex.set(BotQuestIndex.of(List.of(snailQuest(QUEST, 1, Map.of(SNAIL, 10), 2000, 500))));
        BotQuests.resetForTests();
        BotQuests.rosterBot = chr -> true;
    }

    @AfterEach
    void tearDown() {
        BotQuests.gate = oldGate;
        BotQuests.world = oldWorld;
        BotQuests.yield = oldYield;
        BotQuestIndex.set(null);
        BotQuests.resetForTests();
        BotQuests.rosterBot = soloMapling.ArtificialPlayer.BotHelpers::isRosterBot;
        System.clearProperty("slums.botlife.enabled");
        System.clearProperty("slums.botlife.execute");
        System.clearProperty(BotQuests.PROP);
        System.clearProperty("slums.botquests.perDay");
    }

    static Character bot(int mapId, int level, Point at, Point npcAt) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(1_000_000_123);
        when(chr.getLevel()).thenReturn(level);
        when(chr.getMapId()).thenReturn(mapId);
        when(chr.getPosition()).thenReturn(at);
        MapleMap map = mock(MapleMap.class);
        if (npcAt != null) {
            NPC npc = mock(NPC.class);
            when(npc.getPosition()).thenReturn(npcAt);
            when(map.getNPCById(NPC_ID)).thenReturn(npc);
        }
        when(chr.getMap()).thenReturn(map);
        return chr;
    }

    // ── picking ──

    @Test
    void aGrindingBotPicksAKillQuestForItsMap() {
        var log = new BotQuestLog();
        assertTrue(BotQuests.consider(bot(FIELD, 15, null, null), log, true, TOWN, NOW));
        var a = log.active();
        assertEquals(QUEST, a.questId);
        assertEquals(TOWN, a.startMap, "the NPC stands in the field's return town");
        assertEquals(TOWN, a.endMap);
        assertEquals(FIELD, a.huntMap);
        assertFalse(a.taken(), "picked, not yet taken: only the NPC can give it");
        assertEquals(Set.of(), BotQuests.facts(log));
    }

    @Test
    void theWorthModelRanksTheBetterQuestFirst() {
        BotQuestIndex.set(BotQuestIndex.of(List.of(snailQuest(QUEST, 1, Map.of(SNAIL, 10), 2000, 0),
                snailQuest(QUEST + 1, 1, Map.of(SNAIL, 10), 8000, 0),
                snailQuest(QUEST + 2, 1, Map.of(SNAIL, 10), 100, 0)))); // 100 EXP for an 80 s trip at 200/min: no
        var ranked = BotQuests.ranked(bot(FIELD, 15, null, null), new BotQuestLog(), FIELD, TOWN,
                BotQuestScorer.TAKE_MIN_SCORE, 5);
        assertEquals(List.of(QUEST + 1, QUEST), ranked.stream().map(c -> c.quest().id()).toList());
        assertEquals(8000 / (80 / 60.0 * 200), ranked.getFirst().score(), 1e-9);
    }

    @Test
    void noPickWhenTheRulesSayNo() {
        var log = new BotQuestLog();
        assertFalse(BotQuests.consider(bot(FIELD, 15, null, null), log, false, TOWN, NOW), "not grinding");
        gate.canStart = false;
        assertFalse(BotQuests.consider(bot(FIELD, 15, null, null), log, true, TOWN, NOW), "Quest.canStart refused");
        BotQuests.resetForTests();
        gate.canStart = true;
        assertTrue(BotQuests.consider(bot(FIELD, 15, null, null), log, true, TOWN, NOW));
        assertNotNull(log.active(), "fine now");
        // the per-bot clock: a second look within ten minutes does nothing, even with no quest
        log.drop();
        BotQuestIndex.set(BotQuestIndex.of(List.of(snailQuest(QUEST + 5, 1, Map.of(SNAIL, 10), 2000, 0))));
        assertFalse(BotQuests.consider(bot(FIELD, 15, null, null), log, true, TOWN, NOW));
    }

    @Test
    void levelCompletedOffMapAndFarNpcQuestsAreLeftOut() {
        var chr = bot(FIELD, 15, null, null);
        var done = new BotQuestLog();
        done.pick(new BotQuestLog.Active(QUEST, NPC_ID, NPC_ID, TOWN, TOWN, FIELD, Map.of(SNAIL, 10), 0));
        done.complete(true, NOW);
        assertTrue(BotQuests.ranked(chr, done, FIELD, TOWN, 0, 5).isEmpty(), "already done");
        BotQuestIndex.set(BotQuestIndex.of(List.of(snailQuest(QUEST, 20, Map.of(SNAIL, 10), 2000, 0))));
        assertTrue(BotQuests.ranked(chr, new BotQuestLog(), FIELD, TOWN, 0, 5).isEmpty(), "level 20 quest, level 15 bot");
        BotQuestIndex.set(BotQuestIndex.of(List.of(snailQuest(QUEST, 1, Map.of(SNAIL, 10, 999, 5), 2000, 0))));
        assertTrue(BotQuests.ranked(chr, new BotQuestLog(), FIELD, TOWN, 0, 5).isEmpty(), "a mob not on this map");
        BotQuestIndex.set(BotQuestIndex.of(List.of(snailQuest(QUEST, 1, Map.of(SNAIL, 10), 2000, 0))));
        npcs.clear();
        npcs.put(FAR_TOWN, Set.of(NPC_ID));
        assertTrue(BotQuests.ranked(chr, new BotQuestLog(), FIELD, TOWN, 0, 5).isEmpty(), "NPC not in a nearby town");
        assertEquals(1, BotQuests.ranked(chr, new BotQuestLog(), FIELD, FAR_TOWN, 0, 5).size(), "its home town counts");
    }

    @Test
    void aQuestTheBotNeverGetsRoundToIsDropped() {
        var log = new BotQuestLog();
        BotQuests.consider(bot(FIELD, 15, null, null), log, true, TOWN, NOW);
        BotQuests.resetForTests();
        BotQuests.consider(bot(FIELD, 15, null, null), log, false, TOWN, NOW + BotQuests.STALE_PICK_MS - 1);
        assertNotNull(log.active(), "still fresh");
        BotQuests.consider(bot(FIELD, 15, null, null), log, false, TOWN, NOW + BotQuests.STALE_PICK_MS + 1);
        assertNull(log.active(), "two hours and never taken: dropped");
        assertTrue(log.skipped(QUEST));
    }

    @Test
    void onlyRosterBotsQuest() {
        BotQuests.rosterBot = chr -> false; // an owned alt: its player's character, its player's quests
        var log = new BotQuestLog();
        assertFalse(BotQuests.consider(bot(FIELD, 15, null, null), log, true, TOWN, NOW));
        assertNull(log.active());
    }

    @Test
    void theDailyCapStopsPicks() {
        System.setProperty("slums.botquests.perDay", "1");
        var log = new BotQuestLog();
        log.pick(new BotQuestLog.Active(QUEST + 9, NPC_ID, NPC_ID, TOWN, TOWN, FIELD, Map.of(SNAIL, 10), 0));
        log.complete(false, NOW);
        assertFalse(BotQuests.consider(bot(FIELD, 15, null, null), log, true, TOWN, NOW));
        assertNull(log.active());
    }

    @Test
    void theSwitchIsOffByDefault() {
        System.clearProperty(BotQuests.PROP);
        var log = new BotQuestLog();
        assertFalse(BotQuests.enabled());
        assertFalse(BotQuests.consider(bot(FIELD, 15, null, null), log, true, TOWN, NOW));
        log.pick(new BotQuestLog.Active(QUEST, NPC_ID, NPC_ID, TOWN, TOWN, FIELD, Map.of(SNAIL, 10), 0));
        assertEquals(BotQuests.Visit.NONE, BotQuests.atNpc(bot(TOWN, 15, null, null), log,
                LifePlanner.Step.TAKE_QUEST, true, NOW));
        assertEquals(-1, BotQuests.huntMap(log));
    }

    // ── offscreen: the log records it, nothing else ──

    @Test
    void offscreenTheQuestIsOnlyRecorded() {
        var log = new BotQuestLog();
        BotQuests.consider(bot(FIELD, 15, null, null), log, true, TOWN, NOW);
        assertEquals(BotQuests.Visit.TAKEN_OFFSCREEN,
                BotQuests.atNpc(bot(TOWN, 15, null, null), log, LifePlanner.Step.TAKE_QUEST, false, NOW));
        assertEquals(Set.of(LifePlanner.Fact.QUEST_TAKEN), BotQuests.facts(log));
        assertEquals(FIELD, BotQuests.huntMap(log));
        BotQuests.offscreenKills(log, TOWN, 50); // not the hunt map: nothing
        assertEquals(0, log.active().killed(SNAIL));
        BotQuests.offscreenKills(log, FIELD, 9); // snails are 2 of the 3 spawns: 6 of 9 kills
        assertEquals(6, log.active().killed(SNAIL));
        assertEquals(BotQuests.Visit.NOT_READY,
                BotQuests.atNpc(bot(TOWN, 15, null, null), log, LifePlanner.Step.TURN_IN_QUEST, false, NOW));
        BotQuests.offscreenKills(log, FIELD, 9);
        assertEquals(Set.of(LifePlanner.Fact.QUEST_TAKEN, LifePlanner.Fact.QUEST_HUNTED), BotQuests.facts(log));
        assertEquals(-1, BotQuests.huntMap(log), "hunted: no more hunting");
        assertEquals(BotQuests.Visit.RECORDED,
                BotQuests.atNpc(bot(TOWN, 15, null, null), log, LifePlanner.Step.TURN_IN_QUEST, false, NOW));
        assertEquals(List.of(QUEST), log.done());
        assertEquals(1, log.modelledDone());
        assertEquals(0, gate.starts + gate.completes, "no quest call offscreen");
        assertEquals(0, yield.mesosTaken, "no rewards offscreen");
    }

    // ── watched: the real path ──

    @Test
    void watchedTheBotTakesAndTurnsInThroughTheRealQuest() {
        var log = new BotQuestLog();
        BotQuests.consider(bot(FIELD, 15, null, null), log, true, TOWN, NOW);
        Character atNpc = bot(TOWN, 15, new Point(100, 0), new Point(130, 0));
        assertEquals(BotQuests.Visit.STARTED, BotQuests.atNpc(atNpc, log, LifePlanner.Step.TAKE_QUEST, true, NOW));
        assertEquals(1, gate.starts);
        assertEquals(BotQuestLog.Mode.REAL, log.active().mode());
        // real kills (raiseQuestMobCount) show up through the quest's own progress
        gate.progress = 10;
        when(atNpc.botQuestState()).thenReturn(true);
        assertEquals(BotQuests.Visit.COMPLETED, BotQuests.atNpc(atNpc, log, LifePlanner.Step.TURN_IN_QUEST, true, NOW));
        assertEquals(1, gate.completes);
        assertEquals(500, yield.mesosTaken, "the meso reward went through the yield caps");
        assertEquals(Map.of(2000000, 5), yield.itemsTaken, "and the item reward");
        assertEquals(1, log.realDone());
        assertNull(log.active());
    }

    @Test
    void tooFarFromTheNpcDoesNothing() {
        var log = new BotQuestLog();
        BotQuests.consider(bot(FIELD, 15, null, null), log, true, TOWN, NOW);
        Character far = bot(TOWN, 15, new Point(-2000, 0), new Point(130, 0));
        assertEquals(BotQuests.Visit.TOO_FAR, BotQuests.atNpc(far, log, LifePlanner.Step.TAKE_QUEST, true, NOW));
        Character noNpc = bot(TOWN, 15, new Point(100, 0), null);
        assertEquals(BotQuests.Visit.TOO_FAR, BotQuests.atNpc(noNpc, log, LifePlanner.Step.TAKE_QUEST, true, NOW));
        assertEquals(0, gate.starts);
        assertFalse(log.active().taken());
    }

    @Test
    void theNpcSayingNoDropsTheQuest() {
        var log = new BotQuestLog();
        BotQuests.consider(bot(FIELD, 15, null, null), log, true, TOWN, NOW);
        gate.canStart = false;
        assertEquals(BotQuests.Visit.DROPPED, BotQuests.atNpc(bot(TOWN, 15, new Point(0, 0), new Point(0, 0)), log,
                LifePlanner.Step.TAKE_QUEST, true, NOW));
        assertNull(log.active());
        assertTrue(log.skipped(QUEST));
    }

    @Test
    void capsThatRefuseTheRewardMakeTheTurnInWait() {
        var log = new BotQuestLog();
        BotQuests.consider(bot(FIELD, 15, null, null), log, true, TOWN, NOW);
        Character atNpc = bot(TOWN, 15, new Point(0, 0), new Point(0, 0));
        BotQuests.atNpc(atNpc, log, LifePlanner.Step.TAKE_QUEST, true, NOW);
        gate.progress = 10;
        when(atNpc.botQuestState()).thenReturn(true);
        yield.mesoAllowance = 200; // the daily meso cap has 200 left of the 500 reward
        for (int i = 0; i < BotQuests.MAX_DEFERRALS; i++) {
            assertEquals(BotQuests.Visit.DEFERRED, BotQuests.atNpc(atNpc, log, LifePlanner.Step.TURN_IN_QUEST, true, NOW));
        }
        assertEquals(0, gate.completes, "no rewards past the caps");
        assertEquals(0, yield.mesosTaken, "a partial take is given back");
        assertEquals(BotQuests.Visit.RECORDED, BotQuests.atNpc(atNpc, log, LifePlanner.Step.TURN_IN_QUEST, true, NOW));
        assertEquals(0, gate.completes, "recorded done, still no rewards");
        assertEquals(List.of(QUEST), log.done());
    }

    @Test
    void killsCountedOffscreenAreNotTurnedInForRewards() {
        var log = new BotQuestLog();
        BotQuests.consider(bot(FIELD, 15, null, null), log, true, TOWN, NOW);
        Character atNpc = bot(TOWN, 15, new Point(0, 0), new Point(0, 0));
        BotQuests.atNpc(atNpc, log, LifePlanner.Step.TAKE_QUEST, true, NOW);
        BotQuests.offscreenKills(log, FIELD, 30); // hunted offscreen: the real quest counted nothing
        gate.canComplete = false;
        assertEquals(BotQuests.Visit.RECORDED, BotQuests.atNpc(atNpc, log, LifePlanner.Step.TURN_IN_QUEST, true, NOW));
        assertEquals(0, gate.completes);
        assertEquals(0, yield.mesosTaken);
    }

    @Test
    void questStepsGoToTheirMaps() {
        var log = new BotQuestLog();
        BotQuests.consider(bot(FIELD, 15, null, null), log, true, TOWN, NOW);
        assertEquals(TOWN, BotQuests.destination(log, LifePlanner.Step.TAKE_QUEST));
        assertEquals(FIELD, BotQuests.destination(log, LifePlanner.Step.HUNT_QUEST));
        assertEquals(TOWN, BotQuests.destination(log, LifePlanner.Step.TURN_IN_QUEST));
        assertEquals(-1, BotQuests.destination(log, LifePlanner.Step.WEDDING));
    }

    // ── fakes ──

    static final class FakeGate implements BotQuests.Gate {
        boolean canStart = true, canComplete = true;
        int starts, completes, progress;
        final Map<Integer, QuestStatus.Status> status = new HashMap<>();

        @Override public boolean canStart(Character chr, int questId, int npc) { return canStart; }
        @Override public boolean canComplete(Character chr, int questId, int npc) { return canComplete && progress >= 10; }
        @Override public void start(Character chr, int questId, int npc) {
            starts++;
            status.put(questId, QuestStatus.Status.STARTED);
        }
        @Override public void complete(Character chr, int questId, int npc) {
            completes++;
            status.put(questId, QuestStatus.Status.COMPLETED);
        }
        @Override public QuestStatus.Status status(Character chr, int questId) {
            return status.getOrDefault(questId, QuestStatus.Status.NOT_STARTED);
        }
        @Override public int progress(Character chr, int questId, int mobId) { return progress; }
    }

    static final class FakeYield implements BotQuests.Yield {
        long mesoAllowance = Long.MAX_VALUE, mesosTaken;
        final Map<Integer, Integer> itemsTaken = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        @Override public long takeMesos(Character chr, long mesos) {
            long got = Math.min(mesos, mesoAllowance);
            mesosTaken += got;
            return got;
        }
        @Override public void returnMesos(Character chr, long mesos) { mesosTaken -= mesos; }
        @Override public boolean takeItem(Character chr, int itemId, int count) {
            itemsTaken.merge(itemId, count, Integer::sum);
            return true;
        }
        @Override public void returnItem(Character chr, int itemId, int count) {
            itemsTaken.merge(itemId, -count, Integer::sum);
        }
        @Override public boolean canHold(Character chr, int itemId, int count) { return true; }
    }
}
