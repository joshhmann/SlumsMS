package soloMapling.ArtificialPlayer.BotQuestSystem;

import org.junit.jupiter.api.Test;
import soloMapling.WzTestData;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Ticket 158 item 1: the quest index keeps only kill-and-turn-in quests a bot can run through the real path. */
class BotQuestIndexTest {

    static BotQuestIndex.QuestMeta meta(int id, int startNpc, int endNpc, Map<Integer, Integer> mobs, List<String> keys) {
        return new BotQuestIndex.QuestMeta(id, startNpc, endNpc, 0, 0, mobs, 100, 0, Map.of(), false, false, false,
                keys, true);
    }

    @Test
    void aKillQuestWithBothNpcsQualifies() {
        assertTrue(BotQuestIndex.qualifies(meta(1, 10, 11, Map.of(100100, 10), List.of("mob", "npc", "lvmin"))));
    }

    @Test
    void scriptedTimedNpclessMoblessOrFetchQuestsDoNot() {
        var ok = meta(1, 10, 11, Map.of(100100, 10), List.of("mob", "npc"));
        assertFalse(BotQuestIndex.qualifies(new BotQuestIndex.QuestMeta(1, 10, 11, 0, 0, ok.mobs(), 0, 0, Map.of(),
                false, true, false, ok.completeReqKeys(), true)), "scripted");
        assertFalse(BotQuestIndex.qualifies(new BotQuestIndex.QuestMeta(1, 10, 11, 0, 0, ok.mobs(), 0, 0, Map.of(),
                false, false, true, ok.completeReqKeys(), true)), "timed");
        assertFalse(BotQuestIndex.qualifies(new BotQuestIndex.QuestMeta(1, 10, 11, 0, 0, ok.mobs(), 0, 0, Map.of(),
                false, false, false, ok.completeReqKeys(), false)), "actions a bot can't take (random items, chains)");
        assertFalse(BotQuestIndex.qualifies(meta(1, 0, 11, ok.mobs(), ok.completeReqKeys())), "no start NPC");
        assertFalse(BotQuestIndex.qualifies(meta(1, 10, 0, ok.mobs(), ok.completeReqKeys())), "no end NPC");
        assertFalse(BotQuestIndex.qualifies(meta(1, 10, 11, Map.of(), List.of("npc"))), "talk quest, no kills");
        assertFalse(BotQuestIndex.qualifies(meta(1, 10, 11, ok.mobs(), List.of("mob", "item"))), "fetch an item");
        assertFalse(BotQuestIndex.qualifies(meta(1, 10, 11, ok.mobs(), List.of("mob", "money"))), "pay mesos");
        assertFalse(BotQuestIndex.qualifies(meta(1, 10, 11, ok.mobs(), List.of("mob", "pet"))), "needs a pet");
    }

    @Test
    void theIndexFindsQuestsByMob() {
        var a = meta(1, 10, 11, Map.of(100100, 10, 100101, 5), List.of("mob"));
        var b = meta(2, 10, 11, Map.of(100100, 20), List.of("mob"));
        var fetch = meta(3, 10, 11, Map.of(100100, 1), List.of("mob", "item"));
        var index = BotQuestIndex.of(List.of(a, b, fetch));
        assertEquals(2, index.byId().size());
        assertEquals(List.of(1, 2), index.forMob(100100).stream().map(BotQuestIndex.QuestMeta::id).toList());
        assertEquals(List.of(1), index.forMob(100101).stream().map(BotQuestIndex.QuestMeta::id).toList());
        assertTrue(index.forMob(999).isEmpty());
        assertEquals(15, a.killsNeeded());
    }

    @Test
    void theServerQuestWzHasRunnableKillQuests() {
        WzTestData.assumePresent("Quest.wz");
        var index = BotQuestIndex.build();
        assertTrue(index.byId().size() >= 50, "found only " + index.byId().size());
        for (var q : index.byId().values()) {
            assertTrue(BotQuestIndex.qualifies(q), "quest " + q.id());
            assertFalse(q.mobs().isEmpty());
        }
        assertFalse(index.byMob().isEmpty());
        System.out.println("BotQuestIndex: " + index.byId().size() + " runnable kill quests over " + index.byMob().size()
                + " mobs; Henesys-area (start NPC 1012xxx): " + index.byId().values().stream()
                .filter(q -> q.startNpc() / 1000 == 1012).map(BotQuestIndex.QuestMeta::id).toList());
    }
}
