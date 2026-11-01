package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * b265: t-cv-1's "a refusal never says yes" rule (2026-10-31 run) read "maybe later k" as a yes, because its yes
 * pattern takes any word "k" and its refusal guard knew only no/nah/cant/can't/busy.
 * <p>
 * Round 1 stopped the trailing "k" from counting. Round 2 (owner 2026-11-01, "widen the refusal") makes the guard
 * read the declines the bots really say, so a decline that also carries a yes word ("maybe later ok", "not today
 * sure") is a NO; a real yes, and the two genuine words-vs-act mismatches, still fail the check.
 */
class RefusalNeverSaysYesTest {

    private static final String REFUSAL = "INTENT intent=come/rule tone=neutral/rule -> COME: NO (cant rn) -> nothing happens";

    /** A run whose only refusal is answered by one replied line in the exact shape ConvoQuality writes. */
    private static CheckRun run(String intentLine, String answer) {
        AutoQaRunnerTest.FakeWorld w = new AutoQaRunnerTest.FakeWorld();
        CheckRun r = new CheckRun(AutoQaCatalog.find("t-cv-1"), w);
        r.actor(1);
        w.qa("CONVO", intentLine);
        w.now += 1_000;
        w.qa("CONVO", "SAID Bot1 -> player 0xab1c [bank#12, decide, own, 1400 ms, slots=none] " + answer);
        w.now += 1_000;
        return r;
    }

    private static CheckRun run(String answer) {
        return run(REFUSAL, answer);
    }

    /** The defect: a refusal is answered with "maybe later k", and the rule calls it a yes. */
    @Test
    void aRefusalEndingInKIsNotAYes() {
        assertNull(AutoQaCatalog.refusalNeverSaysYes(run("maybe later k")),
                "\"maybe later k\" is a refusal, not a yes");
        assertNull(AutoQaCatalog.refusalNeverSaysYes(run("maybe later, k")), "the same with a comma");
        assertNull(AutoQaCatalog.refusalNeverSaysYes(run("cant rn k")), "a plain refusal with a trailing k");
    }

    /**
     * The owner's round-2 rule: these are the bots' own decline lines (source named per line), each with a yes word
     * in the same reply, and each must read as a NO.
     */
    @Test
    void aWidenedRefusalReadsAsNoEvenWithAYesWord() {
        List<String> declines = List.of(
                "maybe later ok",                 // ScriptedReplies.COME_NO / PARTY_NO / BUDDY_NO
                "maybe later, sure",              // ReplyPlanner PARTY_NO "maybe later sry"
                "not today ok",                   // owner's list
                "not rn ok",                      // LfgParty.LATER "not rn"
                "aw not now, ok",                 // TalkLines no_generic "aw not now"
                "nah im ok, ty tho",              // GuildDialogue InviteNo "nah im ok, ty tho"
                "nope ok",                        // AffinityDialogue Refuse / ShopOffer "Nope. Try again."
                "busy atm ok",                    // ScriptedReplies.COME_NO "busy atm"
                "im kinda busy ok",               // ScriptedReplies.HELP_NO "im kinda busy"
                "cant rn sry ok",                 // ScriptedReplies.COME_NO "cant rn sry"
                "cant do that sorry, ok",         // ShopOfferDialogue DeclineResponse
                "cannot right now ok",            // owner's "can't / cant / cannot"
                "too far lol ok",                 // ConvoActions TOO_FAR_LINES "too far lol"
                "too high lvl for me lol ok",     // ScriptedReplies.HELP_NO "too high lvl for me lol"
                "sleeping sry ok",                // ConversationDialogue/SocialBotDialogue sleeping lines
                "afk ok",                         // ConversationDialogue "yeah sorry was afk"
                "maybe next time ok",             // GuildDialogue AppNoNotnow
                "aw ok, next time",               // TalkLines reply_no "aw ok, next time"
                "hard pass ok",                   // ShopOfferDialogue DeclineResponse "Hard pass."
                "nty ok",                         // ConversationDialogue:62 "nty gl selling tho"
                "ill pass this time ok",          // TalkLines no_trade "ill pass this time"
                "nah too far rn ok");             // ConvoActions TOO_FAR_LINES "nah too far rn"
        for (String said : declines) {
            assertNull(AutoQaCatalog.refusalNeverSaysYes(run(said)), "\"" + said + "\" is a refusal, not a yes");
        }
    }

    /** The cases the rule already caught must keep failing the check: a real yes after a refusal. */
    @Test
    void aRealYesAfterARefusalIsStillCaught() {
        for (String said : List.of("sure, omw", "ok", "k", "k!", "im coming", "ok omw lol", "sure inv me", "kk")) {
            assertNotNull(AutoQaCatalog.refusalNeverSaysYes(run(said)), "\"" + said + "\" is a yes");
        }
        // agreeing idioms carry a refusal-looking word but answer the request: widening must not swallow them
        assertNotNull(AutoQaCatalog.refusalNeverSaysYes(run("no problem, omw")), "\"no problem, omw\" is a yes");
        assertNotNull(AutoQaCatalog.refusalNeverSaysYes(run("np, omw")), "\"np, omw\" is a yes");
    }

    /**
     * Genuine failure 1: the bot declined in words but the same line's outcome says the action happened. The widened
     * word list cannot hide this, because the act half is read from the outcome, not the words.
     */
    @Test
    void aBotThatSaidNoButDidTheActionStillFails() {
        String acted = "INTENT intent=come/rule tone=neutral/rule -> COME: NO (too far) -> COME_YES";
        assertNotNull(AutoQaCatalog.refusalNeverSaysYes(run(acted, "maybe later k")),
                "a NO that still did the action is a failure");
        assertNotNull(AutoQaCatalog.refusalNeverSaysYes(run(acted, "nah ok")),
                "the same for a shorter refusal");
        String joined = "INTENT intent=party/decide tone=friendly/decide -> PARTY: NO (wary) -> PARTY_ACCEPTED";
        assertNotNull(AutoQaCatalog.refusalNeverSaysYes(run(joined, "cant rn sry")),
                "joined the party after declining is a failure");
    }

    /** Genuine failure 2: the bot agreed in words and the same line's outcome says nothing happened. */
    @Test
    void aBotThatSaidYesAndDidNothingStillFails() {
        String nothing = "INTENT intent=party/decide tone=friendly/decide -> PARTY: YES (party rules) -> PARTY_DECLINED";
        assertNotNull(AutoQaCatalog.refusalNeverSaysYes(run(nothing, "sure inv me")),
                "a yes with nothing done is a failure");
        String came = "INTENT intent=come/rule tone=neutral/rule -> COME: YES (close by) -> COME_NO";
        assertNotNull(AutoQaCatalog.refusalNeverSaysYes(run(came, "ok omw")),
                "the same for come");
    }
}
