package soloMapling.ArtificialPlayer.BotAffinitySystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotStore;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.2 b092 (auto-QA t-af-1 failed 6 of 16 full runs): the staged friend stood in the Free Market chatting with
 * town bots all run (cluster conversations, chatter, threads) and a social bot only looks for players it likes while
 * it is free of those, so it never acted within the check's wait. A staged social friend is held out of the drafting.
 */
class StagedFriendHoldTest {

    @Test
    void aHeldBotIsHeldUntilReleased() {
        int id = BotStore.BOT_ID_BASE + 777_001;
        assertFalse(AffinityStaging.held(id));
        AffinityStaging.hold(id);
        assertTrue(AffinityStaging.held(id));
        AffinityStaging.release(id);
        assertFalse(AffinityStaging.held(id));
    }

    @Test
    void aHeldSocialBotIsNotDraftableButStillNoticesThePlayer() throws Exception {
        String src = Files.readString(Path.of("src/main/java/soloMapling/ArtificialPlayer/BotTypes/SocialBot.java"));
        int avail = src.indexOf("public boolean isAvailableForAmbientActions()");
        int end = src.indexOf("}", avail);
        assertTrue(src.substring(avail, end).contains("AffinityStaging.held("),
                "town chatter drafts through isAvailableForAmbientActions: a held bot must answer no");
        assertTrue(src.contains("AffinityStaging.held(getChr().getId())\n                && isFreeOfTalk()) {\n"
                + "            maybeNoticePlayers();"), "a held bot still looks for the player it likes");
    }

    @Test
    void theConversationChecksKeepTheirBotsInTheChatter() throws Exception {
        String src = Files.readString(Path.of("src/main/java/soloMapling/qa/GameQaWorld.java"));
        int at = src.indexOf("public int stageConvoBot(");
        assertTrue(src.substring(at, src.indexOf("return id;", at)).contains("AffinityStaging.release(id)"));
    }
}
