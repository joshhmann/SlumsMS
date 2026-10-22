package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 59, owner decision 3: the light post-processor. */
class VoiceStylerTest {

    private static ConvoPersona persona(ConvoPersona.Voice v, double typo) {
        return new ConvoPersona(v, typo, 0, 0, 0, List.of());
    }

    @Test
    void lowercaseTypersAbbreviate() {
        String out = VoiceStyler.style("Are you going to Henesys? I'm busy right now.", persona(ConvoPersona.Voice.LOWER, 0),
                1, List.of());
        assertEquals(out, out.toLowerCase());
        assertTrue(out.contains(" u ") || out.startsWith("r u") || out.contains("rn") || out.contains("im"), out);
        assertFalse(out.endsWith("."), out);
    }

    @Test
    void properTypersSpellItOut() {
        String out = VoiceStyler.style("ya im at ur map rn", persona(ConvoPersona.Voice.PROPER, 0), 1, List.of());
        assertTrue(out.startsWith("Y"), out);
        assertTrue(out.contains("I'm") && out.contains("your") && out.contains("right now"), out);
    }

    @Test
    void protectedWordsAndPricesNeverChange() {
        for (int slot = 0; slot < 200; slot++) {
            String out = VoiceStyler.style("selling the work gloves for 1.2m henesys", persona(ConvoPersona.Voice.LEET, 0.06),
                    slot, Set.of("henesys", "gloves"));
            assertTrue(out.toLowerCase().contains("1.2m"), out);
            assertTrue(out.toLowerCase().contains("henesys") && out.toLowerCase().contains("gloves"), out);
        }
    }

    @Test
    void theSameBotAndLineAlwaysComeOutTheSame() {
        ConvoPersona p = persona(ConvoPersona.Voice.SPANISH, 0.04);
        assertEquals(VoiceStyler.style("lol ok wait pls", p, 7, List.of()), VoiceStyler.style("lol ok wait pls", p, 7, List.of()));
    }

    @Test
    void neverPastTheChatLimit() {
        String long69 = "a".repeat(30) + " " + "b".repeat(38);
        for (ConvoPersona.Voice v : ConvoPersona.Voice.values()) {
            for (int slot = 0; slot < 50; slot++) {
                assertTrue(VoiceStyler.style(long69, new ConvoPersona(v, 0.06, 1, 0, 0, List.of("kekeke", "Indeed.")), slot,
                        List.of()).length() <= VoiceStyler.MAX, v.name());
            }
        }
    }

    @Test
    void flavouredVoicesLaughTheirWay() {
        assertTrue(VoiceStyler.style("lol ok", persona(ConvoPersona.Voice.KOREAN, 0), 3, List.of()).contains("kekeke"));
        assertTrue(VoiceStyler.style("lol ok", persona(ConvoPersona.Voice.SPANISH, 0), 3, List.of()).contains("jaja"));
        assertEquals("ok see u", VoiceStyler.style("Oh, ok. See u!", persona(ConvoPersona.Voice.TERSE, 0), 3, List.of()));
    }

    @Test
    void typosAreAtMostOnePerLine() {
        String line = "please come over here really quick because these monsters hurt";
        String out = VoiceStyler.typo(line, 1.0, Set.of(), new java.util.Random(1));
        assertFalse(out.equals(line));
        int diff = 0;
        String[] a = line.split(" ");
        String[] b = out.split(" ");
        for (int i = 0; i < a.length; i++) {
            diff += a[i].equals(b[i]) ? 0 : 1;
        }
        assertEquals(1, diff);
    }

    @Test
    void theSwitchTurnsItOff() {
        System.setProperty("solomapling.convo.styler", "false");
        try {
            assertEquals("Hello There.", VoiceStyler.style("Hello There.", persona(ConvoPersona.Voice.LOWER, 0), 1, List.of()));
        } finally {
            System.clearProperty("solomapling.convo.styler");
        }
    }
}
