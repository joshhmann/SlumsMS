package soloMapling.ArtificialPlayer.BotMessagingSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * 1.5.1 owner QA: "other bots I tried to talk to just zoomed away". The conversation hold was armed only by the
 * brain's chat (say/talk/open with an LLM). A line a bot answered with a natural action ("party", "trade",
 * "follow"), the "u talking to me?" check, a named menu bot and the double-click window never armed it, and with no
 * LLM a named bot's open() returned before holding: the movement paths asked the hold and found none.
 */
class DispatcherChatHoldTest {

    private static int next = 7101;

    private static Character character(int id, String name) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName(name);
        chr.setMapId(100000000);
        return chr;
    }

    @Test
    void aNaturalActionAnswerHoldsTheBotLikeAChatLine() throws Exception {
        Character chr = character(BotStore.BOT_ID_BASE + 61_000 + next, "Partyr");
        Character player = character(next++, "Josh");
        BotSM bot = mock(TownWandererBot.class);
        when(bot.getChr()).thenReturn(chr);
        when(bot.naturalAction(any(Character.class), anyString())).thenReturn(true); // "wanna party?" -> invite
        assertFalse(BotConversation.isHoldingChat(chr));

        Method talk = Dispatcher.class.getDeclaredMethod("talkNaturally", BotSM.class, Character.class, String.class);
        talk.setAccessible(true);
        talk.invoke(new Dispatcher(MessageQueue.getInstance()), bot, player, "Partyr wanna party?");

        assertTrue(BotConversation.isHoldingChat(chr), "a bot that answered with an action keeps its spot");
    }

    @Test
    void namingABotWithNoBrainStillHoldsIt() {
        // The test config has no LLM endpoint: open() used to return before arming the hold.
        Character chr = character(BotStore.BOT_ID_BASE + 61_000 + next, "Menur");
        Character player = character(next++, "Josh");
        BotSM bot = mock(TownWandererBot.class);
        when(bot.getChr()).thenReturn(chr);
        BotConversation.open(bot, player);
        assertTrue(BotConversation.isHoldingChat(chr));
    }

    @Test
    void engagedHoldsOnlyOnThePlayersMap() {
        Character chr = character(BotStore.BOT_ID_BASE + 61_000 + next, "Farr");
        Character player = character(next++, "Josh");
        player.setMapId(104040000);
        BotConversation.engaged(chr, player);
        assertFalse(BotConversation.isHoldingChat(chr), "a player elsewhere has nobody to stand with");
        player.setMapId(100000000);
        BotConversation.engaged(chr, player);
        assertTrue(BotConversation.isHoldingChat(chr));
    }

    @Test
    void everyDispatcherRouteToABotArmsTheHold() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/Dispatcher.java"));
        for (String route : new String[]{"private void talkNaturally(", "private void askFirst(",
                "private void openMenu(", "private void handleBotRunning("}) {
            int at = src.indexOf(route);
            assertTrue(at >= 0, route);
            int end = src.indexOf("\n    }\n", at);
            assertTrue(src.substring(at, end).contains("BotConversation.engaged("),
                    route + " answers a player without holding the bot");
        }
    }
}
