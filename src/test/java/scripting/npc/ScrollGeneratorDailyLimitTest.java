package scripting.npc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Owner 2026-10-26: the Maple TV scroll generator gives at most 5 prizes per character per day (quest record 32010). */
class ScrollGeneratorDailyLimitTest {

    @Test
    void fivePrizesADayThenNothingUntilTomorrow() {
        String rec = null;
        for (int i = 1; i <= 5; i++) {
            rec = NPCConversationManager.dailyCountClaim(rec, "2026-10-26", 5);
            assertEquals("2026-10-26:" + i, rec);
        }
        assertNull(NPCConversationManager.dailyCountClaim(rec, "2026-10-26", 5), "the sixth the same day is refused");
        assertEquals(5, NPCConversationManager.dailyCountUsed(rec, "2026-10-26"));
        assertEquals(0, NPCConversationManager.dailyCountUsed(rec, "2026-10-27"), "a new day resets");
        assertEquals("2026-10-27:1", NPCConversationManager.dailyCountClaim(rec, "2026-10-27", 5));
    }

    @Test
    void junkRecordsCountAsUnused() {
        assertEquals(0, NPCConversationManager.dailyCountUsed("", "2026-10-26"));
        assertEquals(0, NPCConversationManager.dailyCountUsed("2026-10-26:abc", "2026-10-26"));
        assertEquals(0, NPCConversationManager.dailyCountUsed("2026-10-26:-4", "2026-10-26"));
        assertEquals("2026-10-26:1", NPCConversationManager.dailyCountClaim("garbage", "2026-10-26", 5));
    }

    @Test
    void theRecordIdIsNotTheJumpQuestOne() {
        assertEquals(32010, NPCConversationManager.SCROLL_GENERATOR_DAILY_QUEST);
        assertEquals(5, NPCConversationManager.SCROLL_GENERATOR_DAILY_LIMIT);
    }
}
