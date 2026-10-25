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

    // ── 4b reported / quoted speech and warnings ──

    @Test
    void aCueInSomeoneElsesReportedWordsIsAMention() {
        // fresh-013, 014, 015, 017, 018, 019, 020 and the dev set's challenge-065, 066
        for (String line : List.of("some guy in fm was yelling b> claw 10m", "she typed party pls in guild chat",
                "he said follow me right before logging off", "someone wrote add me im pro on the board",
                "my friend said buff me pls then died lol", "they kept spamming trade me trade me",
                "he asked wanna party but then went afk", "someone said add me but i didnt",
                "he typed follow me in the guild chat yesterday")) {
            assertEquals(ConvoIntent.TALK, intent(line), line);
            assertEquals(ChatTriage.Intent.NONE, ConvoSense.rules(line).triage().intent(), line);
        }
    }

    @Test
    void aCueInsideQuotesIsAMention() {
        assertEquals(ConvoIntent.TALK, intent("what does \"party pls\" mean"));
        assertEquals(ConvoIntent.PARTY, intent("\"lol\" party pls"));
    }

    @Test
    void aThirdPartySubjectMakesTheCueAMention() {
        assertEquals(ConvoIntent.TALK, intent("my friend wants a party for kpq"));
        assertEquals(ConvoIntent.TALK, intent("the npc says come to henesys"));
        // an address to everyone around is still a request
        assertEquals(ConvoIntent.HELP_KILL, intent("someone help me kill this boss"));
        assertEquals(ConvoIntent.PARTY, intent("ppl wanna party?"));
    }

    @Test
    void aRelayAddressedToTheBotIsAmbiguousAndStaysARequestForTheOwnerToDecide() {
        // CODEX-HANDOFF 4b: "he said you should buff me" may be a real relay. The rules do not guess: a report whose
        // words are addressed to the bot ("said u ...") keeps the request, as before this fix. Flagged for the owner.
        assertEquals(ConvoIntent.BUFF, intent("he said you should buff me"));
        assertEquals(ConvoIntent.BUFF, intent("she told me u could buff me pls"));
    }

    @Test
    void aWarningIsAMentionAndNeverAConFlag() {
        // fresh-048 and the warning set fresh-045..054
        assertEquals(ConvoIntent.TALK, intent("be careful of the trade offer in fm"));
        for (String line : List.of("watch out for the guy offering to double mesos",
                "dont trust anyone asking for ur password", "someone told me theyd double my mesos, its a scam",
                "be careful of the trade offer in fm", "never share ur account info with strangers",
                "that guy asking for a loan is a scammer", "ppl who ask u to go first are scammers",
                "watch out, someone is impersonating a gm", "if they ask for ur pin its a scam",
                "i got scammed yesterday, be careful", "watch out for people offering to double mesos")) {
            ConvoSense.Read r = ConvoSense.rules(line);
            assertEquals(ConvoIntent.TALK, r.intent(), line);
            assertEquals(ChatTriage.Con.NORMAL, r.triage().con(), line);
        }
        // the real thing is still flagged
        assertEquals(ChatTriage.Con.RWT, ChatTriage.rules("whats ur password").con());
        assertEquals(ChatTriage.Con.SCAM, ChatTriage.rules("give me 1m and ill double it").con());
    }

    // ── 4c recall gaps ──

    @Test
    void whereIsAPlaceAsksTheWay() {
        assertEquals(ConvoIntent.GUIDE, intent("where is kerning city")); // fresh-059
        assertEquals(ConvoIntent.GUIDE, intent("wheres ellinia"));
        assertEquals(ConvoIntent.GUIDE, intent("how do i get to orbis"));
        // asking the way is a question, not something one can decline: a negator does not scope it
        assertEquals(ConvoIntent.GUIDE, intent("i dont know how to get to ellinia"));
        assertNotEquals(ConvoIntent.GUIDE, intent("where is ur guild master"), "not a place");
        assertEquals(ChatTriage.Intent.WHERE_AT, ConvoSense.rules("where r u").triage().intent());
    }

    @Test
    void askingForTheLowestPriceIsHaggling() {
        assertEquals(ConvoIntent.HAGGLE, intent("whats ur lowest price")); // fresh-044
        assertEquals(ConvoIntent.HAGGLE, intent("whats ur best price for the claw"));
        // "how much for" stays the trade opener the dev set labels it (legacy-tuning-012); flagged for the owner
        assertEquals(ConvoIntent.TRADE, intent("how much for the work gloves"));
    }

    // ── 4d past-tense narrative ──

    @Test
    void aPastTenseStoryIsTalk() {
        assertEquals(ConvoIntent.TALK, intent("i bargained down to 300k last time")); // fresh-032
        assertEquals(ConvoIntent.TALK, intent("i traded with a guy who was selling claws"));
        assertEquals(ConvoIntent.TALK, intent("i bought a claw from a guy selling them"));
        // a question or a please makes it a request again; a request in its own clause stays one
        assertEquals(ConvoIntent.TRADE, intent("i was selling claws earlier u want one?"));
        assertEquals(ConvoIntent.PARTY, intent("we partied yesterday, wanna party again"));
        assertEquals(ConvoIntent.HAGGLE, intent("would u do 500k"));
        // "need" is not a past tense
        assertEquals(ConvoIntent.PARTY, intent("i need a party for kpq"));
        assertEquals(ConvoIntent.PARTY, intent("we need 1 more for kpq"));
    }

    @Test
    void noobIsAnInsultOnlyInTheSecondPerson() {
        ConvoSense.Read r = ConvoSense.rules("i helped a noob with his quest earlier"); // fresh-030
        assertEquals(ConvoIntent.TALK, r.intent());
        assertEquals(ConvoSense.Tone.NEUTRAL, r.tone());
        for (String line : List.of("u r a noob", "nice dmg noob", "ur such a noob lol", "lmao get rekt noob",
                "lol u died to that? noob", "ty noob")) {
            assertEquals(ConvoIntent.INSULT, intent(line), line);
        }
    }
}
