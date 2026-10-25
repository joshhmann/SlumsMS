package soloMapling.ArtificialPlayer.BotTypes;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * 1.5.1 owner QA: Henesys townsfolk the owner talked to still left. While held, a HenesysBot's decision still rolled
 * a map hop or (in the Pet Park) a jump-quest conversion, and a refused hop still walked the bot to a main platform.
 */
class HenesysBotChatHoldTest {

    @Test
    void aHeldBotOnlyIdlesOrEmotes() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(BotStore.BOT_ID_BASE + 63_001);
        chr.setName("HermitCandy");
        chr.setMapId(100000202); // the Pet Park: unheld, a quarter of decisions turn it into a jump quest bot
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(7301);
        player.setMapId(100000202);
        HenesysBot bot = new HenesysBot(chr);
        BotConversation.engaged(chr, player);
        for (int i = 0; i < 300; i++) {
            bot.decideNextAction();
            assertTrue(Set.of("RESET", "IDLE", "EMOTE").contains(bot.stateForTest()),
                    "a held townsperson must not wander, hop or convert, got " + bot.stateForTest());
        }
    }

    @Test
    void aRefusedHopDoesNotWalkOffAnyway() throws Exception {
        String src = Files.readString(Path.of("src/main/java/soloMapling/ArtificialPlayer/BotTypes/HenesysBot.java"));
        int at = src.indexOf("case CHANGE_MAP:");
        String block = src.substring(at, src.indexOf("break;", at));
        assertTrue(block.indexOf("if (changeMap())") >= 0 && block.indexOf("if (changeMap())")
                < block.indexOf("wanderToMainPlatform()"), "the walk to a main platform follows a hop that happened");
    }
}
