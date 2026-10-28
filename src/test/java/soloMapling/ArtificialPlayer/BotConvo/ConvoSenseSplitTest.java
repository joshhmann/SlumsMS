package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.1 two-endpoint split: which questions go to which endpoint, a failed half, the skip rules. */
class ConvoSenseSplitTest {

    private static final String INTENT_CON = "{\"answers\":{\"convo_intent\":{\"choice\":\"help_kill\",\"confidence\":0.9},"
            + "\"con\":{\"choice\":\"normal\",\"confidence\":0.9}}}";
    private static final String TONE_ONLY = "{\"answers\":{\"convo_tone\":{\"choice\":\"rude\",\"confidence\":0.95}}}";
    private static final String ALL = "{\"answers\":{\"convo_intent\":{\"choice\":\"help_kill\",\"confidence\":0.9},"
            + "\"convo_tone\":{\"choice\":\"rude\",\"confidence\":0.95},\"con\":{\"choice\":\"normal\",\"confidence\":0.9}}}";
    // no rule fires on this one: intent, tone and con are all left to the model
    private static final String LINE = "these things r too strong for me hmm";

    private static ChatDecide client(List<String> bodies, String reply) {
        return new ChatDecide((body, ms) -> {
            bodies.add(body);
            if (reply == null) {
                throw new java.io.IOException("down");
            }
            return reply;
        }, 400, 100);
    }

    @Test
    void questionsGoToTheirOwnEndpointAndMerge() {
        List<String> main = new CopyOnWriteArrayList<>(), tone = new CopyOnWriteArrayList<>();
        ConvoSense.Read r = ConvoSense.read(LINE, client(main, INTENT_CON), client(tone, TONE_ONLY), null);
        assertEquals(1, main.size());
        assertEquals(1, tone.size());
        assertTrue(main.get(0).contains("convo_intent") && main.get(0).contains("\"con\"") && !main.get(0).contains("convo_tone"));
        assertTrue(tone.get(0).contains("convo_tone") && !tone.get(0).contains("convo_intent") && !tone.get(0).contains("\"con\""));
        assertEquals(ConvoIntent.HELP_KILL, r.intent());
        assertEquals(ConvoSense.Source.DECIDE, r.intentSource());
        assertEquals(ConvoSense.Tone.RUDE, r.tone());
    }

    @Test
    void aFailedToneHalfFallsBackToRulesAndTheOtherHalfStillCounts() {
        List<String> main = new CopyOnWriteArrayList<>(), tone = new CopyOnWriteArrayList<>();
        ConvoSense.Read r = ConvoSense.read(LINE, client(main, INTENT_CON), client(tone, null), null);
        assertEquals(ConvoIntent.HELP_KILL, r.intent());
        assertEquals(ConvoSense.Tone.NEUTRAL, r.tone());
        assertEquals(ConvoSense.Source.DEFAULT, r.toneSource());
    }

    @Test
    void aFailedMainHalfKeepsTheToneAnswer() {
        List<String> main = new CopyOnWriteArrayList<>(), tone = new CopyOnWriteArrayList<>();
        ConvoSense.Read r = ConvoSense.read(LINE, client(main, null), client(tone, TONE_ONLY), null);
        assertEquals(ConvoIntent.TALK, r.intent());
        assertEquals(ConvoSense.Source.DEFAULT, r.intentSource());
        assertEquals(ConvoSense.Tone.RUDE, r.tone());
    }

    @Test
    void noToneClientIsOneCallWithAllThreeQuestions() {
        List<String> main = new CopyOnWriteArrayList<>();
        ConvoSense.Read r = ConvoSense.read(LINE, client(main, ALL), null, null);
        assertEquals(1, main.size());
        assertTrue(main.get(0).contains("convo_intent") && main.get(0).contains("convo_tone") && main.get(0).contains("\"con\""));
        assertEquals(ConvoSense.Tone.RUDE, r.tone());
        assertEquals(ConvoIntent.HELP_KILL, r.intent());
    }

    @Test
    void theToneCallIsSkippedWhenARuleGaveTheTone() {
        String line = "thank you so much, that was really kind of you";
        assertTrue(ConvoSense.toneRule(line, ChatTriage.rules(line)).isPresent(), "test line needs a tone rule");
        List<String> main = new CopyOnWriteArrayList<>(), tone = new CopyOnWriteArrayList<>();
        ConvoSense.read(line, client(main, INTENT_CON), client(tone, TONE_ONLY), null);
        assertEquals(0, tone.size());
    }

    @Test
    void theIntentConCallIsSkippedWhenRulesGaveBoth() {
        String line = "wanna party, i will paypal you 5 dollars";
        ChatTriage.Result rules = ChatTriage.rules(line);
        assertTrue(ConvoSense.intentRule(line).isPresent() && rules.conSource() == ChatTriage.Source.RULE,
                "test line needs intent and con rules");
        assertTrue(ConvoSense.toneRule(line, rules).isEmpty(), "test line must leave tone open");
        List<String> main = new CopyOnWriteArrayList<>(), tone = new CopyOnWriteArrayList<>();
        ConvoSense.read(line, client(main, INTENT_CON), client(tone, TONE_ONLY), null);
        assertEquals(0, main.size());
        assertEquals(1, tone.size());
    }

    @Test
    void eachEndpointGetsItsOwnModelId() {
        List<String> main = new CopyOnWriteArrayList<>(), tone = new CopyOnWriteArrayList<>();
        ChatDecide m = new ChatDecide((b, ms) -> { main.add(b); return INTENT_CON; }, 400, 100, "jev-a");
        ChatDecide t = new ChatDecide((b, ms) -> { tone.add(b); return TONE_ONLY; }, 400, 100, "jev-b");
        ConvoSense.read(LINE, m, t, null);
        assertTrue(main.get(0).contains("\"model\":\"jev-a\""), main.get(0));
        assertTrue(tone.get(0).contains("\"model\":\"jev-b\""), tone.get(0));
        assertEquals("decide", client(new CopyOnWriteArrayList<>(), ALL).model());
    }
}
