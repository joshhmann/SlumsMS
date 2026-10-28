package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConvo.BotLineIntents;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.1 ticket 114: a bot's line never goes through the chat classifier. With {@code -Dslums.bottalk} on, the chat
 * brain refuses a bot speaker at the door (counted); the counter of classified bot lines stays 0.
 */
class BotTalkGuardTest {

    @AfterEach
    void reset() {
        System.clearProperty("slums.bottalk");
    }

    static Character bot(int id, String name) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(id);
        c.setName(name);
        return c;
    }

    @Test
    void aBotLineToABotIsKeptFromTheClassifier() {
        System.setProperty("slums.bottalk", "true");
        BotSM listener = mock(TownWandererBot.class);
        when(listener.getChr()).thenReturn(bot(BotStore.BOT_ID_BASE + 77_001, "Lisa"));
        Character speaker = bot(BotStore.BOT_ID_BASE + 77_002, "Sam");
        long kept = BotLineIntents.counts().get("keptFromClassifier");
        long classified = BotLineIntents.counts().get("classifiedBotLines");
        assertFalse(BotConversation.talk(listener, speaker, "wanna party?"), "the player chat brain never starts");
        assertFalse(BotConversation.say(listener, speaker, "yo", null), "nor through say");
        assertEquals(kept + 2, BotLineIntents.counts().get("keptFromClassifier"));
        assertEquals(classified, BotLineIntents.counts().get("classifiedBotLines"), "no bot line was classified");
        assertFalse(BotConversation.isTalking(listener.getChr().getId(), speaker.getId()), "no chat session was opened");
    }
}
