package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 59, owner decision 8: the fast read. The accuracy floors replay the recorded Decide answers offline
 * (docs/tickets/59, section 9), so a rule change that loses accuracy fails here.
 */
class ConvoSenseTest {

    @Test
    void tuningSetAccuracyHolds() throws Exception {
        ConvoSenseEvaluation.Score s = ConvoSenseEvaluation.score(0.4, 0.8);
        assertTrue(s.pct(s.combinedIntent()) >= 95, "intent " + s.pct(s.combinedIntent()) + " " + s.misses());
        assertTrue(s.pct(s.combinedTone()) >= 85, "tone " + s.pct(s.combinedTone()));
        assertEquals(0, s.falseActions(), "no talk line may be read as a request");
    }

    @Test
    void heldOutAccuracyHoldsAndNeverInventsARequest() throws Exception {
        ConvoSenseEvaluation.Score s = ConvoSenseEvaluation.score(ConvoSenseEvaluation.HOLDOUT,
                ConvoSenseEvaluation.HOLDOUT_ANSWERS, 0.4, 0.8);
        assertTrue(s.pct(s.combinedIntent()) >= 75, "intent " + s.pct(s.combinedIntent()) + " " + s.misses());
        assertTrue(s.combinedIntent() >= s.ruleIntent(), "Decide may only add to the rules");
        assertEquals(0, s.falseActions());
    }

    @Test
    void rulesWinOverTheModel() {
        Map<String, ChatDecide.Answer> model = Map.of(ConvoSense.INTENT.name(), new ChatDecide.Answer("trade", 0.99));
        ConvoSense.Read r = ConvoSense.combine("wanna party?", ChatTriage.rules("wanna party?"), model, 0.4, 0.8, 0.4);
        assertEquals(ConvoIntent.PARTY, r.intent());
        assertEquals(ConvoSense.Source.RULE, r.intentSource());
    }

    @Test
    void economyRomanceAndInsultsNeverComeFromTheModel() {
        for (String label : new String[]{"trade", "haggle", "date", "flirt", "insult"}) {
            Map<String, ChatDecide.Answer> model = Map.of(ConvoSense.INTENT.name(), new ChatDecide.Answer(label, 0.99));
            ConvoSense.Read r = ConvoSense.combine("hmm whatever", ChatTriage.rules("hmm whatever"), model, 0.4, 0.8, 0.4);
            assertEquals(ConvoIntent.TALK, r.intent(), label);
        }
        Map<String, ChatDecide.Answer> help = Map.of(ConvoSense.INTENT.name(), new ChatDecide.Answer("help_kill", 0.7));
        assertEquals(ConvoIntent.HELP_KILL, ConvoSense.combine("these things r too strong", ChatTriage.rules(
                "these things r too strong"), help, 0.4, 0.8, 0.4).intent());
    }

    @Test
    void anUnsureModelLeavesItTalk() {
        Map<String, ChatDecide.Answer> model = Map.of(ConvoSense.INTENT.name(), new ChatDecide.Answer("party", 0.2));
        assertEquals(ConvoIntent.TALK, ConvoSense.combine("hmm ok", ChatTriage.rules("hmm ok"), model, 0.4, 0.8, 0.4)
                .intent());
    }

    @Test
    void aCounterOfferGoesDownTheTradePath() {
        ConvoSense.Read r = ConvoSense.rules("would u do 500k");
        assertEquals(ConvoIntent.HAGGLE, r.intent());
        assertEquals(ChatTriage.Intent.TRADE, r.triage().intent(), "the quote and PriceBand live on the trade path");
    }

    @Test
    void tonesByRule() {
        assertEquals(ConvoSense.Tone.RUDE, ConvoSense.rules("ty noob").tone());
        assertEquals(ConvoSense.Tone.FLIRTY, ConvoSense.rules("ur cute").tone());
        assertEquals(ConvoSense.Tone.KIND, ConvoSense.rules("sorry for ksing").tone());
        assertEquals(ConvoSense.Tone.NEUTRAL, ConvoSense.rules("what lvl r u").tone());
    }

    @Test
    void placesAreFound() {
        ConvoSense.Read r = ConvoSense.rules("can u come to sleepywood real quick");
        assertEquals(ConvoIntent.COME, r.intent());
        assertEquals(105040300, r.entities().first(ConvoEntities.Kind.MAP).id());
        assertEquals(ConvoIntent.COME, ConvoSense.rules("meet me at henesys").intent());
    }
}
