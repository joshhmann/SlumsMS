package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.0 tune, TEST social trace 2026-10-26: the Spanish-accent voice said 26 of 194 thread lines (16 by one bot), some
 * all Spanish. A light accent now: at most one such word a line, never a Spanish sentence, and its bank lines are its
 * own.
 */
class SpanishAccentTest {

    private static ConvoPersona persona(ConvoPersona.Voice v) {
        return new ConvoPersona(v, 0, 1, 0, 0, List.of("jaja", "porfa", "amigo"));
    }

    @Test
    void atMostOneAccentWordALine() {
        assertEquals("jaja ty bro drops? need mesos", VoiceStyler.oneAccentWord("jaja ty bro q drops? porfa, need mesos amigo"));
        assertEquals("jaja, level r u? need spot for 30?", VoiceStyler.oneAccentWord("jaja, q level r u? need spot for 30?"));
        assertEquals("porfa wait", VoiceStyler.oneAccentWord("porfa wait"));
        assertEquals("lol ok", VoiceStyler.oneAccentWord("lol ok"));
        for (String line : List.of("jaja did u see the new patch notes amigo", "q nerf to drops porfa? my grinding is dead",
                "lol ok wait pls", "haha ya pls hurry", "what lol pls")) {
            for (int slot = 0; slot < 200; slot++) {
                for (ConvoPersona.Voice v : ConvoPersona.Voice.values()) {
                    String out = VoiceStyler.style(line, persona(v), slot, List.of());
                    assertTrue(VoiceStyler.spanishWords(out) <= 1, v + " " + slot + ": " + out);
                }
            }
        }
    }

    @Test
    void aWholeSpanishLineIsSpotted() {
        for (String line : List.of("bro q caro, 300 y trato hecho porfa", "jaja si bro, q caro, no puedo comprar porfa",
                "jaja, alguien para orbis pq? porfa, amigo", "lol porfa espera ya estoy en grupo")) {
            assertTrue(VoiceStyler.spanishSentence(line), line);
        }
        for (String line : List.of("jaja ok 350", "porfa wait", "y u even care", "ty amigo")) {
            assertFalse(VoiceStyler.spanishSentence(line), line);
        }
    }

    @Test
    void spanishBankLinesAreTheSpanishVoicesOwnAndNeverWholeSpanish() throws Exception {
        ChatBank b = ChatBank.read(new ByteArrayInputStream(String.join("\n",
                "1\ttalk\tany\tneutral\tspanish\t-\tq pasa bro is it true?",
                "2\ttalk\tany\tneutral\tspanish\t-\tlol porfa espera ya estoy en grupo",
                "3\ttalk\tany\tneutral\tlower\t-\tya same").getBytes(StandardCharsets.UTF_8)));
        int said = 0;
        for (int i = 0; i < 50; i++) {
            for (ConvoPersona.Voice v : new ConvoPersona.Voice[]{ConvoPersona.Voice.LOWER, ConvoPersona.Voice.LEET, null}) {
                ChatBank.Pick p = b.pick(new ChatBank.Query("talk", null, "neutral", v, 0, Map.of()), null, null,
                        new Random(i));
                if (p != null) { // a tiny bank: the other-voice roll may leave nothing
                    said++;
                    assertEquals(3, p.id(), "a " + v + " bot never says a Spanish-voice line");
                }
            }
            ChatBank.Pick p = b.pick(new ChatBank.Query("talk", null, "neutral", ConvoPersona.Voice.SPANISH, 0, Map.of()),
                    null, null, new Random(i));
            assertTrue(p == null || p.id() != 2, "never the all-Spanish line");
        }
        assertTrue(said > 50, "said " + said);
    }
}
