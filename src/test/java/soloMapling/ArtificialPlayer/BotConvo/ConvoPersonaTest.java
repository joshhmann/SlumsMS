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
        assertTrue(n.get(ConvoPersona.Voice.LOWER) > 2400 && n.get(ConvoPersona.Voice.LOWER) < 3300, n.toString());
        assertTrue(n.get(ConvoPersona.Voice.PROPER) > 700, n.toString());
        for (ConvoPersona.Voice v : List.of(ConvoPersona.Voice.LEET, ConvoPersona.Voice.KOREAN,
                ConvoPersona.Voice.TERSE)) {
            assertTrue(n.get(v) > 150 && n.get(v) < 500, v + " " + n);
        }
        // 1.6.0 tune: the Spanish accent is rarer (about 3%, was 7%)
        assertTrue(n.get(ConvoPersona.Voice.SPANISH) > 80 && n.get(ConvoPersona.Voice.SPANISH) < 200, n.toString());
    }

    @Test
    void fewerBotsHaveTheAccentAndNoOtherBotChangesVoice() {
        int spanish = 0;
        for (int slot = 0; slot < 5000; slot++) {
            ConvoPersona p = ConvoPersona.of(slot);
            java.util.Random rng = new java.util.Random(slot * 0x5DEECE66DL + 0x2F);
            int roll = rng.nextInt(100);
            // the old table: LOWER 55, PROPER 18, LEET 8, KOREAN 7, SPANISH 7, TERSE 5
            boolean wasSpanish = roll >= 88 && roll < 95;
            if (!wasSpanish) {
                ConvoPersona.Voice old = roll < 55 ? ConvoPersona.Voice.LOWER : roll < 73 ? ConvoPersona.Voice.PROPER
                        : roll < 81 ? ConvoPersona.Voice.LEET : roll < 88 ? ConvoPersona.Voice.KOREAN
                        : ConvoPersona.Voice.TERSE;
                assertEquals(old, p.voice(), "slot " + slot);
            } else if (p.voice() == ConvoPersona.Voice.SPANISH) {
                spanish++;
            } else {
                assertEquals(ConvoPersona.Voice.LOWER, p.voice());
            }
        }
        assertTrue(spanish > 80 && spanish < 200, "spanish " + spanish);
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
        assertTrue(prompt.contains("capitals and apostrophes") && prompt.contains("\"Indeed.\"") && prompt.contains("proud of being a hermit")
                && prompt.contains("loyal to your guild"), prompt);
        assertFalse(prompt.matches(".*\\d.*"), prompt);
    }
}
