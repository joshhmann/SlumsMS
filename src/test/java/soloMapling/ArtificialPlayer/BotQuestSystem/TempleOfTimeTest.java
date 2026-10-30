package soloMapling.ArtificialPlayer.BotQuestSystem;

import client.BotClient;
import client.Character;
import client.Job;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ticket 158 item 5: the Temple of Time driver hands the bot the mainline in order as its active quest (a BotLife
 * QUEST goal on 162-questing's path), spreads the lane hunters (at most the lane cap per map and channel, then
 * another channel, then wait), keeps its progress in the quest log, opens the corridor only as far as the bot got,
 * and makes the 3507 detour to the job's first instructor.
 */
class TempleOfTimeTest {

    static final long NOW = 1_800_000_000_000L;
    final Map<String, String> oldProps = new HashMap<>();
    TempleOfTime.Server oldHost;
    final Map<Integer, Integer> botChannel = new HashMap<>();
    List<Integer> worldChannels = List.of(1);

    void prop(String k, String v) {
        oldProps.putIfAbsent(k, System.getProperty(k));
        System.setProperty(k, v);
    }

    @BeforeEach
    void setUp() {
        prop("slums.botlife.enabled", "true");
        prop("slums.botlife.execute", "true");
        prop(BotQuests.PROP, "true");
        prop(TempleOfTime.PROP, "true");
        oldHost = TempleOfTime.host;
        TempleOfTime.host = new TempleOfTime.Server() {
            @Override public List<Integer> channels(Character chr) { return worldChannels; }
            @Override public int channelOf(Character chr) { return botChannel.getOrDefault(chr.getId(), 1); }
            @Override public int players(Character chr, int channel, int mapId) { return 0; }
        };
        TempleOfTime.resetForTests();
        TempleLanes.resetForTests();
    }

    @AfterEach
    void tearDown() {
        TempleOfTime.host = oldHost;
        oldProps.forEach((k, v) -> {
            if (v == null) System.clearProperty(k); else System.setProperty(k, v);
        });
        TempleOfTime.resetForTests();
        TempleLanes.resetForTests();
    }

    static Character bot(int id, int level, long meso) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getLevel()).thenReturn(level);
        when(chr.getMeso()).thenReturn((int) Math.min(Integer.MAX_VALUE, meso));
        when(chr.getJob()).thenReturn(Job.HERO);
        when(chr.isBot()).thenReturn(true);
        return chr;
    }

    /** A bot id whose ambition is at most {@code level}. */
    static int idFor(int level, int from) {
        for (int id = from; ; id++) {
            if (TempleOfTime.ambition(id) <= level) return id;
        }
    }

    @Test
    void theMainlineComesInOrderAndTheLogKeepsTheProgress() {
        int id = idFor(140, 1_000_000_000);
        Character chr = bot(id, 140, 5_000_000);
        BotQuestLog log = new BotQuestLog();
        for (int i = 0; i < TempleRoute.MAINLINE.size(); i++) {
            TempleRoute.Step step = TempleRoute.MAINLINE.get(i);
            assertEquals(Boolean.TRUE, TempleOfTime.consider(chr, log, NOW + i), "step " + i);
            BotQuestLog.Active a = log.active();
            assertEquals(step.questId(), a.questId);
            assertEquals(step.startMap(), a.startMap);
            assertEquals(step.endMap(), a.endMap);
            if (!step.talk()) {
                assertEquals(step.lanes().get(0), a.huntMap, "the lane");
                assertEquals(step.lanes().get(0), TempleLanes.registeredMap(id));
            }
            log.taken(BotQuestLog.Mode.MODELLED);
            a.need.forEach((mob, n) -> log.addKills(mob, n));
            assertTrue(a.hunted());
            log.complete(false, NOW + i);
            assertEquals(i + 1, log.templeDone());
        }
        assertNull(TempleOfTime.consider(chr, log, NOW + 100), "the chain is done: normal quests again");
        BotQuestLog again = BotQuestLog.read(log.save());
        assertEquals(TempleRoute.MAINLINE.size(), again.templeDone(), "saved with the life profile");
    }

    @Test
    void progressOnlyCountsTheMainlineInOrder() {
        BotQuestLog log = new BotQuestLog();
        log.pick(new BotQuestLog.Active(3502, 1, 1, 1, 1, 1, Map.of(1, 1), NOW));
        log.taken(BotQuestLog.Mode.MODELLED);
        log.complete(false, NOW);
        assertEquals(0, log.templeDone(), "3502 before 3500 does not count");
        log.pick(new BotQuestLog.Active(3500, 1, 1, 1, 1, 1, Map.of(), NOW));
        log.complete(false, NOW);
        assertEquals(1, log.templeDone());
    }

    @Test
    void levelsAmbitionAndTheSorcerersFeeComeFirst() {
        int id = idFor(95, 1_000_000_000);
        BotQuestLog log = new BotQuestLog();
        assertNull(TempleOfTime.consider(bot(id, TempleOfTime.ambition(id) - 1, 0), log, NOW), "below its ambition");
        assertNull(log.active());
        Map<String, Object> saved = new HashMap<>(log.save());
        saved.put("temple", TempleRoute.indexOf(TempleRoute.Q_SORCERER));
        BotQuestLog atSorcerer = BotQuestLog.read(saved);
        int rich = idFor(120, 1_000_000_000);
        assertNull(TempleOfTime.consider(bot(rich, 125, 900_000), atSorcerer, NOW), "saves up 1,000,000 first");
        assertEquals(Boolean.TRUE, TempleOfTime.consider(bot(rich, 125, 2_000_000), atSorcerer, NOW));
        assertEquals(TempleRoute.Q_SORCERER, atSorcerer.active().questId);
        Map<String, Object> s2 = new HashMap<>(new BotQuestLog().save());
        s2.put("temple", TempleRoute.indexOf(3508));
        assertNull(TempleOfTime.consider(bot(idFor(100, 1_000_000_000), 100, 0), BotQuestLog.read(s2), NOW),
                "3508 needs level 106");
    }

    @Test
    void laneHuntersSpreadOverChannelsAndWaitWhenEveryLaneIsFull() {
        worldChannels = List.of(1, 2);
        int cap = TempleLanes.cap();
        Map<String, Object> s = new HashMap<>(new BotQuestLog().save());
        s.put("temple", 1); // 3501, Memory Lane 1
        Map<Integer, Integer> perChannel = new HashMap<>();
        int id = 1_000_000_000;
        for (int n = 0; n < 2 * cap + 1; n++) {
            id = idFor(100, id + 1);
            Character chr = bot(id, 100, 0);
            BotQuestLog log = BotQuestLog.read(s);
            Boolean picked = TempleOfTime.consider(chr, log, NOW);
            if (n < cap) {
                assertEquals(Boolean.TRUE, picked, "hunter " + n + " on its own channel");
            } else if (n < 2 * cap) {
                assertNull(picked, "channel 1's lane 1 is full");
                assertEquals(2, TempleOfTime.wantedChannel(chr), "the brain is offered a cc to channel 2");
                botChannel.put(id, 2); // the bot's own update carried the cc out
                assertEquals(-1, TempleOfTime.wantedChannel(chr), "there now");
                assertNull(TempleOfTime.consider(chr, log, NOW + 1_000), "it waits a moment before asking again");
                assertEquals(Boolean.TRUE, TempleOfTime.consider(chr, log, NOW + TempleOfTime.CROWD_DEFER_MIN_MS + 1));
            } else {
                assertNull(picked, "both channels' lane 1 is full: grind elsewhere for now");
                assertEquals(-1, TempleOfTime.wantedChannel(chr));
                assertNull(log.active());
                continue;
            }
            assertEquals(270010100, log.active().huntMap);
            perChannel.merge(botChannel.getOrDefault(id, 1), 1, Integer::sum);
        }
        assertEquals(Map.of(1, cap, 2, cap), perChannel, "never more than the cap on one map of one channel");
    }

    /** Audit 163 (K12): an owned alt obeys the corridor too, by its own quests (it does not take the lane portals). */
    @Test
    void anOwnedAltObeysTheCorridor() {
        Character alt = mock(Character.class);
        when(alt.getId()).thenReturn(8);
        when(alt.isBot()).thenReturn(false);
        when(alt.isOwnedAlt()).thenReturn(true);
        assertTrue(TempleOfTime.mayEnter(270010100, alt), "the first lane is open to all");
        assertFalse(TempleOfTime.mayEnter(270020100, alt), "past an exit whose quest it never did");
    }

    @Test
    void theCorridorOpensAsFarAsTheBotGot() {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(7);
        when(chr.isBot()).thenReturn(true);
        Map<String, Object> s = new HashMap<>(new BotQuestLog().save());
        s.put("temple", TempleRoute.indexOf(3504)); // 3500-3503 done
        TempleOfTime.note(7, BotQuestLog.read(s));
        assertTrue(TempleOfTime.mayEnter(270010100, chr));
        assertTrue(TempleOfTime.mayEnter(270010400, chr), "lane 4 opens with 3503");
        assertFalse(TempleOfTime.mayEnter(270010500, chr), "lane 5 needs 3504");
        assertFalse(TempleOfTime.mayEnter(270020100, chr));
        assertTrue(TempleOfTime.passes(chr, 3503));
        assertFalse(TempleOfTime.passes(chr, 3504));
        Character player = mock(Character.class);
        assertTrue(TempleOfTime.mayEnter(270030500, player), "players go by the portals' own scripts");
        System.setProperty(TempleOfTime.PROP, "false");
        assertTrue(TempleOfTime.mayEnter(270020100, chr), "switch off: nothing changes for bots");
    }

    @Test
    void theLaneExitsAreTransitEdgesOnlyAQuestOpens() {
        var exits = soloMapling.ArtificialPlayer.BotTravelSystem.BotTransit.templeExits();
        assertEquals(TempleRoute.EXITS.size(), exits.size());
        for (int i = 0; i < exits.size(); i++) {
            assertEquals(TempleRoute.EXITS.get(i).quest(), exits.get(i).questGate());
            assertEquals("in00", exits.get(i).portalName());
            assertEquals("out00", exits.get(i).toPortalName());
            assertEquals(0, exits.get(i).fare());
        }
    }

    @Test
    void theMemoryKeeperSendsTheBotToItsFirstInstructorFirst() {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(9);
        when(chr.getJob()).thenReturn(Job.HERO);
        when(chr.getMapId()).thenReturn(TempleRoute.MEMORY_MAP);
        when(chr.getPosition()).thenReturn(new Point(0, 0));
        when(chr.getClient()).thenReturn(mock(BotClient.class));
        MapleMap map = mock(MapleMap.class);
        NPC keeper = mock(NPC.class);
        when(keeper.getPosition()).thenReturn(new Point(100, 0));
        when(map.getNPCById(TempleRoute.MEMORY_KEEPER)).thenReturn(keeper);
        when(chr.getMap()).thenReturn(map);
        when(chr.getQuest(org.mockito.ArgumentMatchers.any(server.quest.Quest.class)))
                .thenAnswer(inv -> new client.QuestStatus(inv.getArgument(0), client.QuestStatus.Status.NOT_STARTED));
        BotQuestLog log = new BotQuestLog();
        TempleRoute.Step keeperStep = TempleRoute.MAINLINE.get(TempleRoute.indexOf(TempleRoute.Q_MEMORY_KEEPER));
        log.pick(TempleOfTime.active(keeperStep, -1, NOW));
        log.taken(BotQuestLog.Mode.REAL);
        try (var quests = org.mockito.Mockito.mockStatic(server.quest.Quest.class)) {
            quests.when(() -> server.quest.Quest.getInstance(org.mockito.ArgumentMatchers.anyInt()))
                    .thenAnswer(inv -> mock(server.quest.Quest.class));
            BotQuests.Visit v = TempleOfTime.atNpc(chr, log, LifePlanner.Step.TURN_IN_QUEST, true, NOW);
            assertEquals(BotQuests.Visit.DEFERRED, v);
        }
        BotQuestLog.Active sub = log.active();
        assertNotNull(sub);
        assertEquals(3523, sub.questId, "a hero's first instructor: Dances with Balrog");
        assertEquals(102000003, sub.startMap);
        assertFalse(sub.taken(), "the plan's next step is the instructor's map");
        assertEquals(0, log.templeDone(), "3507 is not done yet");

        // offscreen at the instructor: modelled, back to the Memory Keeper with 3507 under way
        when(chr.getMapId()).thenReturn(102000003);
        BotQuests.Visit off = TempleOfTime.atNpc(chr, log, LifePlanner.Step.TAKE_QUEST, false, NOW);
        assertEquals(BotQuests.Visit.TAKEN_OFFSCREEN, off);
        assertEquals(TempleRoute.Q_MEMORY_KEEPER, log.active().questId);
        assertTrue(log.active().taken());
        assertEquals(TempleRoute.MEMORY_MAP, log.active().endMap);
    }

    @Test
    void theSorcererChargesHisFeeOffscreenToo() {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(11);
        when(chr.getMapId()).thenReturn(TempleRoute.SORCERER_MAP);
        BotQuestLog log = new BotQuestLog();
        log.pick(TempleOfTime.active(TempleRoute.MAINLINE.get(TempleRoute.indexOf(TempleRoute.Q_SORCERER)), -1, NOW));
        try (var holdings = org.mockito.Mockito.mockStatic(soloMapling.ArtificialPlayer.BotHoldings.class)) {
            holdings.when(() -> soloMapling.ArtificialPlayer.BotHoldings.spendMeso(chr, TempleRoute.SORCERER_FEE))
                    .thenReturn(false, true);
            assertEquals(BotQuests.Visit.DEFERRED,
                    TempleOfTime.atNpc(chr, log, LifePlanner.Step.TAKE_QUEST, false, NOW), "can't pay: no potion");
            assertNull(log.active(), "put back to wait (audit #2)");
            log.pick(TempleOfTime.active(TempleRoute.MAINLINE.get(TempleRoute.indexOf(TempleRoute.Q_SORCERER)), -1, NOW));
            assertEquals(BotQuests.Visit.TAKEN_OFFSCREEN,
                    TempleOfTime.atNpc(chr, log, LifePlanner.Step.TAKE_QUEST, false, NOW));
            assertTrue(log.active().taken());
            holdings.verify(() -> soloMapling.ArtificialPlayer.BotHoldings.spendMeso(chr, TempleRoute.SORCERER_FEE),
                    org.mockito.Mockito.times(2));
        }
    }

    /** Audit #2 (b111): a step that cannot be finished waits with a doubling backoff, then is dropped for good. */
    @Test
    void anUnfinishableStepBacksOffThenIsDropped() {
        int id = idFor(140, 1_000_000_000);
        Character chr = bot(id, 140, 5_000_000);
        when(chr.getMapId()).thenReturn(TempleRoute.SORCERER_MAP);
        BotQuestLog log = new BotQuestLog();
        int sorcerer = TempleRoute.indexOf(TempleRoute.Q_SORCERER);
        for (int i = 0; i < sorcerer; i++) {
            assertEquals(Boolean.TRUE, TempleOfTime.consider(chr, log, NOW));
            log.taken(BotQuestLog.Mode.MODELLED);
            log.active().need.forEach((mob, n) -> log.addKills(mob, n));
            log.complete(false, NOW);
        }
        long t = NOW;
        try (var holdings = org.mockito.Mockito.mockStatic(soloMapling.ArtificialPlayer.BotHoldings.class)) {
            holdings.when(() -> soloMapling.ArtificialPlayer.BotHoldings.spendMeso(chr, TempleRoute.SORCERER_FEE))
                    .thenReturn(false); // the fee never goes through
            long backoff = TempleOfTime.NOT_READY_BACKOFF_MS;
            for (int wait = 1; wait <= TempleOfTime.MAX_NOT_READY; wait++) {
                assertEquals(Boolean.TRUE, TempleOfTime.consider(chr, log, t), "picked again, wait " + wait);
                assertEquals(TempleRoute.Q_SORCERER, log.active().questId);
                assertEquals(BotQuests.Visit.DEFERRED,
                        TempleOfTime.atNpc(chr, log, LifePlanner.Step.TAKE_QUEST, false, t));
                assertNull(log.active(), "put back: the bot grinds meanwhile");
                assertNull(TempleOfTime.consider(chr, log, t + backoff - 1), "still backing off");
                t += backoff;
                backoff *= 2;
            }
            assertEquals(Boolean.TRUE, TempleOfTime.consider(chr, log, t));
            assertEquals(BotQuests.Visit.DROPPED, TempleOfTime.atNpc(chr, log, LifePlanner.Step.TAKE_QUEST, false, t));
        }
        assertTrue(log.skipped(TempleRoute.Q_SORCERER));
        assertNull(TempleOfTime.consider(chr, log, t + 100 * 3_600_000L), "a dropped step is never re-picked this run");
    }

    @Test
    void aSkippedOrCompletedStepIsNotPicked() {
        int id = idFor(140, 1_000_000_000);
        Character chr = bot(id, 140, 5_000_000);
        BotQuestLog log = new BotQuestLog();
        log.skip(TempleRoute.MAINLINE.get(0).questId());
        assertNull(TempleOfTime.consider(chr, log, NOW), "dropped earlier: the Temple has nothing for it");
    }

    @Test
    void offWithoutTheSwitches() {
        System.setProperty(TempleOfTime.PROP, "false");
        assertNull(TempleOfTime.consider(bot(idFor(140, 1_000_000_000), 140, 5_000_000), new BotQuestLog(), NOW));
        System.setProperty(TempleOfTime.PROP, "true");
        System.setProperty(BotQuests.PROP, "false");
        assertFalse(TempleOfTime.enabled(), "rides on ticket 158's quest path");
    }
}
