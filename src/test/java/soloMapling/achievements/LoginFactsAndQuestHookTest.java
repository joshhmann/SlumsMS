package soloMapling.achievements;

import client.Character;
import client.QuestStatus;
import client.Job;
import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.quest.Quest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 1.6.1 promise audit: a guild, a marriage and rebirths from before achievements count at login (once, however often
 * one logs in), and quests that scripts complete (Quest.forceComplete) count toward the 50-quest goal.
 */
class LoginFactsAndQuestHookTest {

    @AfterEach
    void tearDown() {
        Achievements.setEnabledForTest(null);
        Achievements.useForTest(null);
    }

    private static AchievementService service(MemoryStore store) throws Exception {
        Achievements.setEnabledForTest(true);
        AchievementService s = new AchievementService(GoalCatalogTest.shipped(), store, new FakeHands(), () -> 5L,
                Runnable::run);
        Achievements.useForTest(s);
        return s;
    }

    private static Character player(int id) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getJob()).thenReturn(Job.BEGINNER);
        when(chr.getLevel()).thenReturn(10);
        when(chr.getMaxLevel()).thenReturn(200);
        return chr;
    }

    @Test
    void aGuildAMarriageAndARebirthFromBeforeCountAtLogin() throws Exception {
        boolean rebirth = YamlConfig.config.server.USE_REBIRTH_SYSTEM;
        try {
            YamlConfig.config.server.USE_REBIRTH_SYSTEM = true;
            MemoryStore store = new MemoryStore();
            service(store);
            Character chr = player(7_001);
            when(chr.getGuildId()).thenReturn(12);
            when(chr.isMarried()).thenReturn(true);
            when(chr.getReborns()).thenReturn(1);
            Achievements.onLogin(chr);
            for (String goal : List.of("guild", "wedding", "rebirth-1")) {
                var row = store.load(7_001).get(goal);
                assertNotNull(row, goal + " counted at login: " + store.load(7_001));
                assertTrue(row.earnedAt() > 0, goal);
            }
            Character none = player(7_002);
            Achievements.onLogin(none);
            assertNull(store.load(7_002).get("guild"));
            assertNull(store.load(7_002).get("wedding"));
            assertNull(store.load(7_002).get("rebirth-1"));
        } finally {
            YamlConfig.config.server.USE_REBIRTH_SYSTEM = rebirth;
        }
    }

    @Test
    void aLoginFactIsAFloorNeverAddedTwice() {
        Goal threeRebirths = new Goal("r3", "t", "x", 1, Trigger.REBIRTH, 3, List.of(), List.of(), List.of());
        int p = threeRebirths.next(0, Event.fact(Trigger.REBIRTH, 2));
        p = threeRebirths.next(p, Event.fact(Trigger.REBIRTH, 2));
        assertEquals(2, p, "two logins with 2 rebirths stay at 2");
        assertEquals(3, threeRebirths.next(p, Event.of(Trigger.REBIRTH)), "a real rebirth still adds");
    }

    @Test
    void aScriptCompletedQuestCountsOnceAndARepeatOfADoneQuestDoesNot() throws Exception {
        MemoryStore store = new MemoryStore();
        service(store);
        Character chr = player(7_003);
        when(chr.getMap()).thenReturn(mock(MapleMap.class));
        Quest quest = mock(Quest.class, CALLS_REAL_METHODS);
        QuestStatus started = mock(QuestStatus.class);
        when(started.getStatus()).thenReturn(QuestStatus.Status.STARTED);
        when(chr.getQuest(quest)).thenReturn(started);
        assertTrue(quest.forceComplete(chr, 9010000)); // what qm.forceCompleteQuest() runs
        assertEquals(1, store.load(7_003).get("quests-50").progress());

        QuestStatus done = mock(QuestStatus.class);
        when(done.getStatus()).thenReturn(QuestStatus.Status.COMPLETED);
        when(chr.getQuest(quest)).thenReturn(done);
        quest.forceComplete(chr, 9010000);
        assertEquals(1, store.load(7_003).get("quests-50").progress(), "re-completing a done quest is not a new quest");
    }
}
