package soloMapling.ArtificialPlayer.BotBrainSystem;

import org.junit.jupiter.api.Test;
import soloMapling.server.Json;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplyCleanerTest {

    @Test
    void aPlainLinePassesThrough() {
        assertEquals(Optional.of("got it at the quest shop in henesys lol"),
                ReplyCleaner.clean("got it at the quest shop in henesys lol"));
    }

    @Test
    void quotesSpeakerTagsAndExtraLinesAreRemoved() {
        assertEquals(Optional.of("Caught it on the roof after a long grind, man!"),
                ReplyCleaner.clean("\"Caught it on the roof after a long grind, man!\""));
        assertEquals(Optional.of("ya its from the halloween event"),
                ReplyCleaner.clean("xXSkyBladeXx: ya its from the halloween event\n\n(The bot smiles.)"));
    }

    @Test
    void reasoningIsNeverAChatLine() {
        assertEquals(Optional.of("lol thanks"), ReplyCleaner.clean("<think>be casual</think>lol thanks"));
        assertEquals(Optional.empty(), ReplyCleaner.clean("<think>still thinking when the tokens ran out"));
        assertEquals(Optional.empty(), ReplyCleaner.clean("Thinking Process:\n1. Analyze the request"));
        assertEquals(Optional.empty(), ReplyCleaner.clean("   \n  "));
        assertEquals(Optional.empty(), ReplyCleaner.clean(null));
    }

    @Test
    void aLongLineIsClampedToTheChatBoxAtAWord() {
        String longLine = "dude i got this hat from the halloween event like two years ago and nobody has it anymore lol";
        String clamped = ReplyCleaner.clean(longLine).orElseThrow();
        assertTrue(clamped.length() <= ReplyCleaner.MAX_CHAT_LENGTH, clamped);
        assertTrue(longLine.startsWith(clamped));
        assertFalse(clamped.endsWith(" "));
    }

    @Test
    void notesAreAtMostTwoShortLinesAndNoneMeansNothing() {
        assertEquals(Optional.of("said they're saving for a pink adventurer cape\nis a lv52 fire/poison mage"),
                ReplyCleaner.notes("- said they're saving for a pink adventurer cape\n2) is a lv52 fire/poison mage\n- a third"));
        assertEquals(Optional.empty(), ReplyCleaner.notes("NONE"));
        assertEquals(Optional.empty(), ReplyCleaner.notes("<think>nothing</think>\nNONE."));
        String longNote = ReplyCleaner.notes("x ".repeat(120)).orElseThrow();
        assertTrue(longNote.length() <= ReplyCleaner.MAX_NOTE_LENGTH);
    }

    @Test
    void jsonRoundTripsTheShapesTheClientUses() {
        Map<String, Object> value = Map.of("s", "line \"quoted\"\nnext\t\u0001", "n", 3L, "d", 1.5, "b", true,
                "l", List.of(1L, "two"), "m", Map.of());
        assertEquals(value, Json.parse(Json.write(value)));
        assertEquals("{\"x\":{\"enable_thinking\": false}}", Json.write(Map.of("x", new Json.Raw("{\"enable_thinking\": false}"))));
        assertTrue(Json.isValid("{\"enable_thinking\": false}"));
        assertFalse(Json.isValid("{nope"));
        assertFalse(Json.isValid("20 30"));
    }

    @Test
    void assistantPhrasingMarkdownEmojisParagraphsAndTheRealWorldFailTheVoiceCheck() {
        assertEquals("assistant phrasing", ReplyCleaner.voiceProblem("As an AI, I don't play games"));
        assertEquals("assistant phrasing", ReplyCleaner.voiceProblem("I'd love to help you find a training spot!"));
        assertEquals("assistant phrasing", ReplyCleaner.voiceProblem("great question! try ant tunnel"));
        assertEquals("markdown", ReplyCleaner.voiceProblem("**Ant Tunnel** is great for ur lvl"));
        assertEquals("markdown", ReplyCleaner.voiceProblem("*waves* hey"));
        assertEquals("emojis", ReplyCleaner.voiceProblem("lol ya \uD83D\uDE02\uD83D\uDE02"));
        assertEquals("real-world reference", ReplyCleaner.voiceProblem("we should hit 6th street in austin later"));
        assertEquals("real-world reference", ReplyCleaner.voiceProblem("add me on discord"));
        assertEquals("several lines", ReplyCleaner.voiceProblem("sure\nok\nlets go\nnow"));
        assertEquals("paragraph", ReplyCleaner.voiceProblem("x".repeat(ReplyCleaner.MAX_RAW_LENGTH + 1)));
        assertEquals(Optional.empty(), ReplyCleaner.chat("Absolutely! Feel free to ask me anything."));
    }

    @Test
    void aPlayerLinePassesAndLosesAFinalFullStopAndAStrayEmoji() {
        assertEquals(null, ReplyCleaner.voiceProblem("lol ya pigs drop nothing"));
        assertEquals(null, ReplyCleaner.voiceProblem("idk maybe ant tunnel? -_-"));
        assertEquals(Optional.of("ya im almost 30 finally"), ReplyCleaner.chat("ya im almost 30 finally."));
        assertEquals(Optional.of("brb pots..."), ReplyCleaner.chat("brb pots..."));
        assertEquals(Optional.of("gg"), ReplyCleaner.chat("gg \uD83D\uDE02"));
    }
}
