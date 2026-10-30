package soloMapling.ArtificialPlayer.BotQuestSystem;

import client.Character;
import client.QuestStatus;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.life.NPC;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifePlanner;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.2 bot audit (K11): the real quest gate ({@code Quest.getInstance(id).canStart/start/canComplete/complete}) is a
 * scripted rule set, and a throw out of it escaped {@code atNpc}/{@code ranked}, was swallowed by the bot tick's
 * catch-all (BotTickService) and came back every tick with nothing in the log. Each gate call is guarded now: a throw
 * is logged once per bot and quest id and the quest is dropped, as it is when the NPC refuses.
 */
class BotQuestGateFaultTest {

    static final int FIELD = 100010000, TOWN = 100000000;
    static final int SNAIL = 100100, NPC_ID = 1012100, QUEST = 1001, OTHER_QUEST = 1002;
    static final long NOW = 1_800_000_000_000L;

    private static final class Capture extends AbstractAppender {
        final List<String> lines = new CopyOnWriteArrayList<>();

        Capture() {
            super("botquest-gate-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            lines.add(event.getMessage().getFormattedMessage());
        }
    }

    final Capture capture = new Capture();
    BotQuests.Gate oldGate;
    BotQuests.World oldWorld;
    BotQuests.Yield oldYield;
    ThrowingGate gate;
    BotQuestsTest.FakeYield yield;
    Logger logger;
    Level level;

    @BeforeEach
    void setUp() {
        System.setProperty("slums.botlife.enabled", "true");
        System.setProperty("slums.botlife.execute", "true");
        System.setProperty(BotQuests.PROP, "true");
        oldGate = BotQuests.gate;
        oldWorld = BotQuests.world;
        oldYield = BotQuests.yield;
        gate = new ThrowingGate();
        yield = new BotQuestsTest.FakeYield();
        BotQuests.gate = gate;
        BotQuests.yield = yield;
        BotQuests.world = new BotQuests.World() {
            @Override public List<Integer> mobsOn(int mapId) { return mapId == FIELD ? List.of(SNAIL) : List.of(); }
            @Override public boolean npcOn(int mapId, int npcId) { return mapId == TOWN && npcId == NPC_ID; }
            @Override public int returnMap(int mapId) { return mapId == FIELD ? TOWN : -1; }
            @Override public int hops(Character chr, int from, int to) { return from == to ? 0 : 1; }
            @Override public double[] rate(Character chr, int mapId) { return new double[]{10, 20}; }
            @Override public double questExpFactor(Character chr) { return 1; }
        };
        BotQuestIndex.set(BotQuestIndex.of(List.of(
                BotQuestsTest.snailQuest(QUEST, 1, Map.of(SNAIL, 10), 2000, 500),
                BotQuestsTest.snailQuest(OTHER_QUEST, 1, Map.of(SNAIL, 10), 4000, 0))));
        BotQuests.resetForTests();
        BotQuests.rosterBot = chr -> true;

        logger = (Logger) LogManager.getLogger(BotQuests.class);
        level = logger.getLevel();
        Configurator.setLevel(logger.getName(), Level.WARN); // the test config turns the root logger off
        capture.start();
        logger.addAppender(capture);
    }

    @AfterEach
    void tearDown() {
        logger.removeAppender(capture);
        capture.stop();
        Configurator.setLevel(logger.getName(), level);
        BotQuests.gate = oldGate;
        BotQuests.world = oldWorld;
        BotQuests.yield = oldYield;
        BotQuestIndex.set(null);
        BotQuests.resetForTests();
        BotQuests.rosterBot = soloMapling.ArtificialPlayer.BotHelpers::isRosterBot;
        System.clearProperty("slums.botlife.enabled");
        System.clearProperty("slums.botlife.execute");
        System.clearProperty(BotQuests.PROP);
    }

    static Character bot(int mapId) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(1_000_000_321);
        when(chr.getLevel()).thenReturn(15);
        when(chr.getMapId()).thenReturn(mapId);
        when(chr.getPosition()).thenReturn(new Point(0, 0));
        when(chr.botQuestState()).thenReturn(true); // the quest map is already filled: restore() is a no-op
        MapleMap map = mock(MapleMap.class);
        NPC npc = mock(NPC.class);
        when(npc.getPosition()).thenReturn(new Point(0, 0));
        when(map.getNPCById(NPC_ID)).thenReturn(npc);
        when(chr.getMap()).thenReturn(map);
        return chr;
    }

    static BotQuestLog picked() {
        BotQuestLog log = new BotQuestLog();
        log.pick(new BotQuestLog.Active(QUEST, NPC_ID, NPC_ID, TOWN, TOWN, FIELD, Map.of(SNAIL, 10), 0));
        return log;
    }

    private long gateLines() {
        return capture.lines.stream().filter(l -> l.contains("quest " + QUEST)).count();
    }

    @Test
    void aThrowingCanStartDropsTheQuestAndLogsOnce() {
        BotQuestLog log = picked();
        gate.throwOn = "canStart";
        assertEquals(BotQuests.Visit.DROPPED, assertDoesNotThrow(() -> BotQuests.atNpc(bot(TOWN), log,
                LifePlanner.Step.TAKE_QUEST, true, NOW)), "the throw must not reach the bot tick");
        assertNull(log.active());
        assertTrue(log.skipped(QUEST), "dropped, so the bot never picks it again");
        assertEquals(1, gateLines(), "one line, with the quest id");

        // the same bot meets the same broken gate again (a new pick): the quest drops again, silently
        log.pick(new BotQuestLog.Active(QUEST, NPC_ID, NPC_ID, TOWN, TOWN, FIELD, Map.of(SNAIL, 10), 0));
        assertEquals(BotQuests.Visit.DROPPED, BotQuests.atNpc(bot(TOWN), log, LifePlanner.Step.TAKE_QUEST, true, NOW));
        assertNull(log.active());
        assertEquals(1, gateLines(), "logged once per bot and quest");
    }

    @Test
    void aThrowingStartDropsTheQuest() {
        BotQuestLog log = picked();
        gate.throwOn = "start";
        assertEquals(BotQuests.Visit.DROPPED, assertDoesNotThrow(() -> BotQuests.atNpc(bot(TOWN), log,
                LifePlanner.Step.TAKE_QUEST, true, NOW)));
        assertNull(log.active());
        assertTrue(log.skipped(QUEST));
        assertEquals(0, gate.starts);
        assertEquals(1, gateLines());
    }

    @Test
    void aThrowingStatusDropsTheQuest() {
        BotQuestLog log = picked();
        gate.throwOn = "status";
        assertEquals(BotQuests.Visit.DROPPED, assertDoesNotThrow(() -> BotQuests.atNpc(bot(TOWN), log,
                LifePlanner.Step.TAKE_QUEST, true, NOW)));
        assertNull(log.active());
        assertEquals(1, gate.starts, "the start ran; the status answer is what threw");
        assertEquals(1, gateLines());
    }

    @Test
    void aThrowingCompleteGivesTheRewardsBackAndDropsTheQuest() {
        BotQuestLog log = picked();
        assertEquals(BotQuests.Visit.STARTED, BotQuests.atNpc(bot(TOWN), log, LifePlanner.Step.TAKE_QUEST, true, NOW));
        gate.progress = 10;
        gate.throwOn = "complete";
        assertEquals(BotQuests.Visit.DROPPED, assertDoesNotThrow(() -> BotQuests.atNpc(bot(TOWN), log,
                LifePlanner.Step.TURN_IN_QUEST, true, NOW)));
        assertNull(log.active());
        assertEquals(0, yield.mesosTaken, "the reserved mesos went back");
        assertTrue(yield.itemsTaken.values().stream().allMatch(v -> v == 0), "and the reserved items");
        assertEquals(0, log.realDone());
        assertEquals(1, gateLines());
    }

    @Test
    void aThrowingRankingGateJustLeavesTheQuestOut() {
        gate.throwOn = "canStart";
        BotQuestLog log = new BotQuestLog();
        assertTrue(assertDoesNotThrow(() -> BotQuests.ranked(bot(FIELD), log, FIELD, TOWN, 0, 5)).isEmpty());
        assertEquals(1, gateLines(), "the ranking still says why, once");
        gate.throwOn = null;
        assertEquals(List.of(OTHER_QUEST, QUEST), BotQuests.ranked(bot(FIELD), log, FIELD, TOWN, 0, 5).stream()
                .map(c -> c.quest().id()).toList());
    }

    /** A gate that throws from the named call, as a broken quest script or a null status does. */
    static final class ThrowingGate implements BotQuests.Gate {
        String throwOn;
        int starts, completes, progress;
        final Map<Integer, QuestStatus.Status> status = new HashMap<>();

        private void maybe(String call) {
            if (call.equals(throwOn)) {
                throw new IllegalStateException("broken quest script: " + call);
            }
        }

        @Override public boolean canStart(Character chr, int questId, int npc) {
            maybe("canStart");
            return true;
        }
        @Override public boolean canComplete(Character chr, int questId, int npc) {
            maybe("canComplete");
            return progress >= 10;
        }
        @Override public void start(Character chr, int questId, int npc) {
            maybe("start");
            starts++;
            status.put(questId, QuestStatus.Status.STARTED);
        }
        @Override public void complete(Character chr, int questId, int npc) {
            maybe("complete");
            completes++;
            status.put(questId, QuestStatus.Status.COMPLETED);
        }
        @Override public QuestStatus.Status status(Character chr, int questId) {
            maybe("status");
            return status.getOrDefault(questId, QuestStatus.Status.NOT_STARTED);
        }
        @Override public int progress(Character chr, int questId, int mobId) {
            maybe("progress");
            return progress;
        }
    }
}
