package soloMapling.ArtificialPlayer;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * b247, the cause: the token pattern was upper case only, so every lower-case slot in a dialogue pack read as plain
 * text and was spoken raw. The live path was {@code SocialBot.appendGreeting} -> {@code getRandomLine("Greeting")}
 * -> {@code getRandomResolvedLine}, which returns a pack line untouched when {@code hasTokens} says it has none.
 */
class DialogueSlotResolutionTest {

    /** "hey {player}!" and "oh {player}, sup." - the greeting lines that reached chat with the braces in them. */
    private static final String LOWER_CASE_GREETING = "hey {player}!";

    @Test
    void lowerCaseSlotsAreTokens() {
        assertTrue(DialogueContextResolver.hasTokens(LOWER_CASE_GREETING));
        assertTrue(DialogueContextResolver.hasTokens("oh {player}, sup."));
        assertTrue(DialogueContextResolver.hasTokens("{me} here"));
        assertTrue(DialogueContextResolver.hasTokens("{map}?"), "lower-case map slot");
    }

    @Test
    void everySocialBotGreetingWithASlotResolvesToAPlayerName() {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(1_000_000_007);
        when(bot.getName()).thenReturn("Bobby");
        Character player = mock(Character.class);
        when(player.getName()).thenReturn("Alice");
        when(player.getLevel()).thenReturn(35);
        Job job = mock(Job.class);
        when(job.getId()).thenReturn(0);
        when(player.getJob()).thenReturn(job);

        BotDialogueHandler.DialogueConstructor dialog =
                BotDialogueHandler.getDialogueCon("SocialBotDialogue.yaml", "SocialBot", "Greeting");
        assertTrue(dialog != null && !dialog.getDialogue().isEmpty(), "Greeting node loads");
        int checked = 0;
        for (String raw : dialog.getDialogue()) {
            if (!DialogueContextResolver.hasTokens(raw)) {
                continue;
            }
            Optional<String> filled = DialogueContextResolver.fill(raw, bot, player);
            assertTrue(filled.isPresent(), "unresolved: " + raw);
            assertFalse(filled.get().contains("{"), "left a brace: " + filled.get() + " from " + raw);
            checked++;
        }
        assertTrue(checked >= 10, "greeting lines with slots: " + checked);
    }

    @Test
    void theGuardSeeksNoSlotInAPlainLine() {
        Character bot = mock(Character.class);
        when(bot.getName()).thenReturn("Bobby");
        MapleMap map = mock(MapleMap.class);
        when(map.getMapName()).thenReturn("Henesys");
        when(bot.getMap()).thenReturn(map);
        String plain = "yo, anyone doing ludi pq? :{";
        org.junit.jupiter.api.Assertions.assertEquals(plain,
                soloMapling.ArtificialPlayer.BotConvo.SpeechGuard.check(bot, plain));
    }
}
