package soloMapling.ArtificialPlayer.BotQuestSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.server.Json;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Ticket 158 item 2: a bot stores one active quest and a capped list of completed ids, nothing else. */
class BotQuestLogTest {
    static final long DAY = 86_400_000L;

    @AfterEach
    void clear() {
        System.clearProperty("slums.botquests.keepDone");
    }

    static BotQuestLog.Active quest(int id) {
        return new BotQuestLog.Active(id, 1012100, 1012100, 100000000, 100000000, 100010000, Map.of(100100, 10), 0);
    }

    @Test
    void completedIdsAreCappedOldestFirst() {
        System.setProperty("slums.botquests.keepDone", "3");
        var log = new BotQuestLog();
        for (int id = 1; id <= 5; id++) {
            log.pick(quest(id));
            log.taken(BotQuestLog.Mode.MODELLED);
            log.complete(false, id);
        }
        assertEquals(java.util.List.of(3, 4, 5), log.done());
        assertFalse(log.completed(1));
        assertEquals(5, log.modelledDone());
        assertNull(log.active());
    }

    @Test
    void killsCountOnlyOnceTakenAndNeverPastTheNeed() {
        var log = new BotQuestLog();
        log.pick(quest(1));
        log.addKills(100100, 4);
        assertEquals(0, log.active().killed(100100), "not taken yet: no kills count");
        log.taken(BotQuestLog.Mode.MODELLED);
        log.addKills(100100, 4.6);
        log.addKills(999, 50);
        assertEquals(4, log.active().killed(100100));
        assertFalse(log.active().hunted());
        log.addKills(100100, 40);
        assertEquals(10, log.active().killed(100100));
        assertTrue(log.active().hunted());
        log.syncKills(100100, 3);
        assertEquals(10, log.active().killed(100100), "a sync never lowers a count");
    }

    @Test
    void theDailyCountRollsOverAtUtcMidnight() {
        var log = new BotQuestLog();
        log.pick(quest(1));
        log.complete(true, 10 * DAY + 5);
        log.pick(quest(2));
        log.complete(true, 10 * DAY + 6);
        assertEquals(2, log.doneToday(10 * DAY + 7));
        assertEquals(0, log.doneToday(11 * DAY));
        assertEquals(2, log.realDone());
    }

    @Test
    void aDroppedQuestIsSkippedThisRunButNotSaved() {
        var log = new BotQuestLog();
        log.pick(quest(7));
        log.drop();
        assertNull(log.active());
        assertTrue(log.skipped(7));
        assertFalse(BotQuestLog.read(log.save()).skipped(7));
    }

    @Test
    void everySavedChangeBumpsTheVersion() {
        var log = new BotQuestLog();
        int v = log.version();
        log.pick(quest(1));
        assertTrue(log.version() > v);
        v = log.version();
        log.taken(BotQuestLog.Mode.REAL);
        assertTrue(log.version() > v);
        v = log.version();
        log.addKills(100100, .4);
        assertEquals(v, log.version(), "a fraction of a kill is not worth a save");
        log.addKills(100100, .7);
        assertTrue(log.version() > v);
    }

    @Test
    void savesAndReadsBackThroughJson() {
        var log = new BotQuestLog();
        log.pick(quest(9));
        log.complete(true, 3 * DAY);
        log.pick(quest(10));
        log.taken(BotQuestLog.Mode.REAL);
        log.addKills(100100, 6);
        Object json = Json.parse(Json.write(log.save()));
        var back = BotQuestLog.read(json);
        assertEquals(java.util.List.of(9), back.done());
        assertEquals(1, back.realDone());
        assertEquals(1, back.doneToday(3 * DAY + 1));
        var a = back.active();
        assertNotNull(a);
        assertEquals(10, a.questId);
        assertTrue(a.taken());
        assertEquals(BotQuestLog.Mode.REAL, a.mode());
        assertEquals(6, a.killed(100100));
        assertEquals(100010000, a.huntMap);
        assertEquals(Map.of(100100, 10), a.need);
    }

    @Test
    void garbageReadsAsAnEmptyLog() {
        assertTrue(BotQuestLog.read(null).isEmpty());
        assertTrue(BotQuestLog.read("x").isEmpty());
        assertNull(BotQuestLog.read(Map.of("active", Map.of("id", "nope"))).active());
    }

    @Test
    void aFullLogStaysWellUnderHalfAKilobyte() {
        var log = new BotQuestLog();
        for (int id = 20000; id < 20000 + BotQuestLog.keepDone() + 10; id++) {
            log.pick(quest(id));
            log.complete(true, id);
        }
        log.pick(new BotQuestLog.Active(29999, 1012100, 1012101, 100000000, 101000000, 100010000,
                Map.of(100100, 10, 100101, 20), 1_700_000_000_000L));
        log.taken(BotQuestLog.Mode.REAL);
        int bytes = Json.write(log.save()).length();
        assertTrue(bytes < 512, bytes + " bytes");
    }
}
