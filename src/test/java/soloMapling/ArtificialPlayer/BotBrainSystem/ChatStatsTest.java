package soloMapling.ArtificialPlayer.BotBrainSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatStatsTest {

    @AfterEach
    void reset() {
        ChatStats.resetForTest();
    }

    @Test
    void eachReplyCountsByTierWithItsLatency() {
        ChatStats.resetForTest();
        ChatStats.reply(ChatStats.Tier.LLM, 1200, "Kimchiz", "talk");
        ChatStats.reply(ChatStats.Tier.LLM, 1800, "Kimchiz", null);
        ChatStats.reply(ChatStats.Tier.FLAGGED, 900, "Kimchiz", "scam/rule");
        ChatStats.flagged("scam");
        ChatStats.action("party:party_accepted");
        Map<String, Object> snap = ChatStats.snapshot();
        Map<?, ?> llm = (Map<?, ?>) ((Map<?, ?>) snap.get("tiers")).get("llm");
        assertEquals(2L, llm.get("n"));
        assertEquals(1500L, llm.get("avgMs"));
        assertEquals(1800L, llm.get("maxMs"));
        assertEquals(Map.of("scam", 1L), snap.get("flags"));
        assertEquals(Map.of("party:party_accepted", 1L), snap.get("actions"));
        assertEquals(1, ChatStats.count(ChatStats.Tier.FLAGGED));
    }

    @Test
    void anHourLaterTheHourIsSummarisedAndStartsOver() {
        ChatStats.resetForTest();
        ChatStats.reply(ChatStats.Tier.SCRIPTED, 1000, "Kimchiz", "thanks");
        long now = System.currentTimeMillis();
        ChatStats.startHourAtForTest(now - ChatStats.HOUR_MS - 1);
        ChatStats.maybeSummarize(now);
        Map<?, ?> last = (Map<?, ?>) ChatStats.lastHourForTest().get("scripted");
        assertEquals(1L, last.get("n"));
        Map<?, ?> thisHour = (Map<?, ?>) ((Map<?, ?>) ChatStats.snapshot().get("thisHour")).get("scripted");
        assertEquals(0L, thisHour.get("n"));
        assertTrue(((Map<?, ?>) ChatStats.snapshot().get("tiers")).containsKey("ignored"));
    }
}
