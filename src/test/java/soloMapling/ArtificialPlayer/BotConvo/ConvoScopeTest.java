package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 1.6.0 chat rules fix (tools/convo/benchmark/eval/fresh-v1/CODEX-HANDOFF.md, section 4): a request cue that matches
 * is a request only when it is in scope. The cue inside a negation, a report of someone else's words, a warning or a
 * past-tense story is a mention, not something the player wants the bot to do.
 */
class ConvoScopeTest {

    private static ConvoIntent intent(String line) {
        return ConvoSense.rules(line).intent();
    }

    // ── 4a negation scope ──

    @Test
    void aNegatedRequestIsTalk() {
        // fresh-001, 002, 003, 004, 007, 009, 010
        for (String line : List.of("nah im good, dont need a party invite", "not looking to trade right now sorry",
                "please dont follow me around the map", "i cant come to henesys, im busy",
                "dont add me to ur buddy list pls", "stop asking me to party", "no im not selling it")) {
            assertEquals(ConvoIntent.TALK, intent(line), line);
            ChatTriage.Intent legacy = ConvoSense.rules(line).triage().intent();
            assertEquals(ChatTriage.Intent.NONE, legacy, "ticket 37's actions must not act on: " + line);
        }
    }

    @Test
    void aNegatorThatGovernsAnotherVerbKeepsTheRequest() {
        assertEquals(ConvoIntent.BUFF, intent("dont forget to buff me pls"));
        assertEquals(ConvoIntent.COME, intent("no worries, come to henesys when u can"));
        assertEquals(ConvoIntent.HELP_KILL, intent("i cant believe u killed it, help me next time"));
        assertEquals(ConvoIntent.PARTY, intent("dont be late, party starts at 8"));
        assertEquals(ConvoIntent.PARTY, intent("dont worry about it, wanna party?"));
    }

    @Test
    void aNegatedCueLeavesARealRequestElsewhereInTheLine() {
        assertEquals(ConvoIntent.PARTY, intent("dont follow me, just party with me"));
        assertEquals(ChatTriage.Intent.PARTY, ChatTriage.rules("dont follow me, inv me pls").intent());
    }

    @Test
    void theModelCannotPutBackARequestTheRulesFoundNegated() {
        Map<String, ChatDecide.Answer> model = Map.of(ConvoSense.INTENT.name(), new ChatDecide.Answer("party", 0.99),
                ChatTriage.INTENT.name(), new ChatDecide.Answer("party", 0.99));
        String line = "nah im good, dont need a party invite";
        ConvoSense.Read r = ConvoSense.combine(line, ChatTriage.rules(line), model, 0.4, 0.8, 0.4);
        assertEquals(ConvoIntent.TALK, r.intent());
        assertEquals(ConvoSense.Source.RULE, r.intentSource());
        assertNotEquals(ChatTriage.Intent.PARTY, ChatTriage.combine(ChatTriage.rules(line), model, 0.4, 0.4).intent());
    }

    @Test
    void aSorryThatDeclinesIsPartOfTheRefusalNotAnApology() {
        assertEquals(ConvoIntent.TALK, intent("not looking to trade right now sorry"));
        assertEquals(ConvoIntent.TALK, intent("i wont trade my claw, sorry"));
        assertEquals(ConvoIntent.APOLOGY, intent("sorry for ksing u earlier"));
        assertEquals(ConvoIntent.APOLOGY, intent("my bad dude i didnt see u there"));
    }

    @Test
    void ordinaryRequestsStillWork() {
        assertEquals(ConvoIntent.PARTY, intent("wanna party for the quest"));
        assertEquals(ConvoIntent.FOLLOW, intent("follow me to the portal"));
        assertEquals(ConvoIntent.COME, intent("come to henesys pls"));
        assertEquals(ConvoIntent.BUFF, intent("can u buff me"));
        assertEquals(ConvoIntent.BUDDY, intent("add me as buddy pls"));
        assertEquals(ConvoIntent.TRADE, intent("are u selling red pots"));
        assertEquals(ConvoIntent.HELP_KILL, intent("help me kill this boss pls"));
        assertEquals(ChatTriage.Intent.FOLLOW, ChatTriage.rules("dont move").intent(), "a negator inside the cue");
    }

}
