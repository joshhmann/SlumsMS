package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MapChatLog;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owner report 2026-10-22: what a bot heard on its map before it answers a player. */
class BotConversationHeardTest {

    @AfterEach
    void reset() {
        MapChatLog.resetForTest();
    }

    private static Character character(int id, String name) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName(name);
        return chr;
    }

    @Test
    void theBotKnowsItsOwnQuestionAndTheMapsChat() {
        Character bot = character(20_001, "Kiki");
        Character josh = character(7, "Josh");
        long now = System.currentTimeMillis();
        MapChatLog.record(1, bot.getMapId(), bot.getId(), "Kiki", "anyone from the east coast?", true, now - 5_000);
        MapChatLog.record(1, josh.getMapId(), josh.getId(), "Josh", "im from the east", false, now - 1_000);

        BotConversation.Heard heard = BotConversation.heard(bot, josh, now);
        assertEquals("anyone from the east coast?", heard.justSaid());
        assertEquals("Kiki (you): anyone from the east coast?\nJosh: im from the east", heard.transcript());

        Character other = character(20_002, "Mochi");
        assertNull(BotConversation.heard(other, josh, now).justSaid(), "another bot did not ask it");
    }

    @Test
    void aLineAlreadyInTheChatIsNotAddedTwice() {
        List<BotBrain.Turn> history = List.of(BotBrain.Turn.user("yo"), BotBrain.Turn.assistant("sup, u from the east?"));
        assertTrue(BotConversation.alreadyInHistory(history, "u from the east?"), "said in two parts, one turn");
        assertFalse(BotConversation.alreadyInHistory(history, "anyone from the east coast?"));
    }
}
