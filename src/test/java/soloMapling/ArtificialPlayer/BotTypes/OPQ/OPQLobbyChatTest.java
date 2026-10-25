package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "PQ bots are not great": thirteen lobby bots each spoke a recruit line every 12 s, about 65 lines a minute in one
 * map. The cap has to hold for the lobby, not for each bot.
 */
class OPQLobbyChatTest {

    @AfterEach
    void tearDown() {
        OPQLobbyChat.resetForTest();
        System.clearProperty("solomapling.opq.lobbyRecruitPerMinute");
    }

    @Test
    void aLobbyOfThirteenBotsStillGetsOnlyTheLobbyBudget() {
        int allowed = 0;
        for (int bot = 0; bot < 13; bot++) {
            if (OPQLobbyChat.allowRecruit(1_000L + bot, 0, OPQConstants.OPQ_LOBBY)) {
                allowed++;
            }
        }
        assertEquals(OPQLobbyChat.DEFAULT_MAX_LINES_PER_MINUTE, allowed);
    }

    @Test
    void theBudgetComesBackAfterAMinute() {
        for (int i = 0; i < OPQLobbyChat.DEFAULT_MAX_LINES_PER_MINUTE; i++) {
            assertTrue(OPQLobbyChat.allowRecruit(i, 0, OPQConstants.OPQ_LOBBY));
        }
        assertFalse(OPQLobbyChat.allowRecruit(1_000L, 0, OPQConstants.OPQ_LOBBY));
        assertTrue(OPQLobbyChat.allowRecruit(OPQLobbyChat.WINDOW_MS, 0, OPQConstants.OPQ_LOBBY),
                "a line spoken a minute ago no longer counts");
    }

    @Test
    void everyChannelAndLobbyKeepsItsOwnBudget() {
        for (int i = 0; i < OPQLobbyChat.DEFAULT_MAX_LINES_PER_MINUTE; i++) {
            assertTrue(OPQLobbyChat.allowRecruit(0, 0, OPQConstants.OPQ_LOBBY));
        }
        assertFalse(OPQLobbyChat.allowRecruit(0, 0, OPQConstants.OPQ_LOBBY));
        assertTrue(OPQLobbyChat.allowRecruit(0, 1, OPQConstants.OPQ_LOBBY), "channel 1 lobby is separate");
    }

    @Test
    void theOperatorCanTurnRecruitingChatOff() {
        System.setProperty("solomapling.opq.lobbyRecruitPerMinute", "0");
        assertFalse(OPQLobbyChat.allowRecruit(0, 0, OPQConstants.OPQ_LOBBY));
    }

    @Test
    void oneBotCannotSpeakTwiceInARowWithinItsOwnGap() {
        for (int i = 0; i < 200; i++) {
            long gap = OPQLobbyChat.nextIntervalMs();
            assertTrue(gap >= OPQLobbyChat.DEFAULT_BOT_INTERVAL_MIN_MS
                            && gap <= OPQLobbyChat.DEFAULT_BOT_INTERVAL_MAX_MS,
                    "interval out of range: " + gap);
        }
    }
}
