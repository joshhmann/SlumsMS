package soloMapling.ArtificialPlayer.BotTypes;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotStore;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * 1.5.1 owner QA on TEST: the owner talked to LilZakum (a training bot back in Henesys from a shop trip) and 3 s
 * after its reply it "set off from map 100000000 for map 110030000". The hold was only read by the town stop; the
 * shop return, the decision and the trip out ignored it.
 */
class TrainingBotChatHoldTest {

    private static int next = 7201;

    @AfterEach
    void clearHolds() throws Exception {
        holdLength(45_000L);
    }

    /** BotConversation's package-private test seam: sets the hold length and clears every hold. */
    private static void holdLength(long ms) throws Exception {
        Method m = BotConversation.class.getDeclaredMethod("holdMsForTest", long.class);
        m.setAccessible(true);
        m.invoke(null, ms);
    }

    private static Character character(int id, String name) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName(name);
        chr.setMapId(100000000);
        return chr;
    }

    @Test
    void everyTripPhaseWaitsForTheChatAndResumesAfter() throws Exception {
        for (TrainingBot.Phase p : new TrainingBot.Phase[]{TrainingBot.Phase.SHOP_TRAVEL, TrainingBot.Phase.SHOP_RETURN,
                TrainingBot.Phase.DECIDE, TrainingBot.Phase.GO_TRAIN, TrainingBot.Phase.GO_TOWN}) {
            Character chr = character(BotStore.BOT_ID_BASE + 62_000 + next, "LilZakum");
            Character player = character(next++, "Josh");
            TrainingBot bot = new TrainingBot(chr);
            bot.enterPhaseForTest(p);
            assertFalse(bot.pausedForChatForTest(), p + ": nobody talking, the trip runs");

            BotConversation.engaged(chr, player);
            assertTrue(bot.pausedForChatForTest(), p + ": a player is talking to it, it waits");
            assertTrue(bot.chatPausedForTest());

            holdLength(45_000L); // the hold ends
            assertFalse(bot.pausedForChatForTest(), p + ": the hold is over, the trip goes on");
            assertFalse(bot.chatPausedForTest());
        }
    }

    @Test
    void aGrindAndTheTownStopAreNotPausedByThisCheck() {
        assertFalse(TrainingBot.tripPhase(TrainingBot.Phase.GRIND));
        assertFalse(TrainingBot.tripPhase(TrainingBot.Phase.IN_TOWN), "the town stop holds on its own");
        assertFalse(TrainingBot.tripPhase(TrainingBot.Phase.BREAK_REST));
        assertFalse(TrainingBot.tripPhase(TrainingBot.Phase.SHOP_DWELL), "its browse wander asks the hold");
    }
}
