package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 59, owner decision 3: a stable "mixed real server" persona per bot. */
class ConvoPersonaTest {

    @Test
    void theSameSlotAlwaysGetsTheSamePersona() {
        assertEquals(ConvoPersona.of(1234), ConvoPersona.of(1234));
        assertEquals(ConvoPersona.of(1234, 0.5), ConvoPersona.of(1234, 0.5));
    }

    @Test
    void theMixLooksLikeARealServer() {
        Map<ConvoPersona.Voice, Integer> n = new EnumMap<>(ConvoPersona.Voice.class);
        for (int slot = 0; slot < 5000; slot++) {
            n.merge(ConvoPersona.of(slot).voice(), 1, Integer::sum);
        }
        assertTrue(n.get(ConvoPersona.Voice.LOWER) > 2400 && n.get(ConvoPersona.Voice.LOWER) < 3100, n.toString());
        assertTrue(n.get(ConvoPersona.Voice.PROPER) > 700, n.toString());
        for (ConvoPersona.Voice v : List.of(ConvoPersona.Voice.LEET, ConvoPersona.Voice.KOREAN, ConvoPersona.Voice.SPANISH,
                ConvoPersona.Voice.TERSE)) {
            assertTrue(n.get(v) > 150 && n.get(v) < 500, v + " " + n);
        }
    }

    @Test
    void everyPersonaHasOneToThreeCatchphrasesAndSaneRates() {
        for (int slot = 0; slot < 300; slot++) {
            ConvoPersona p = ConvoPersona.of(slot);
            assertTrue(p.catchphrases().size() >= 1 && p.catchphrases().size() <= 3, p.describe());
            assertTrue(p.typoRate() >= 0 && p.typoRate() <= 0.06, p.describe());
            assertTrue(p.jobPride() >= 0 && p.jobPride() <= 1 && p.guildLoyalty() >= 0 && p.guildLoyalty() <= 1);
        }
    }

    @Test
    void aGmCanForceAVoiceForStaging() {
        ConvoPersona.forceVoice(42, ConvoPersona.Voice.KOREAN);
        try {
            assertEquals(ConvoPersona.Voice.KOREAN, ConvoPersona.of(42).voice());
        } finally {
            ConvoPersona.clearVoice(42);
        }
    }

    @Test
    void thePromptCarriesTheVoiceAndNoNumbers() {
        ConvoPersona p = new ConvoPersona(ConvoPersona.Voice.PROPER, 0, 0.5, 0.9, 0.9, List.of("Indeed."));
        String prompt = p.prompt("hermit", true);
        assertTrue(prompt.contains("types properly") && prompt.contains("\"Indeed.\"") && prompt.contains("proud of being a hermit")
                && prompt.contains("loyal to your guild"), prompt);
        assertFalse(prompt.matches(".*\\d.*"), prompt);
    }
}
