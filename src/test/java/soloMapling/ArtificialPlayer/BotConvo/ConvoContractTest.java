package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 59, owner decision 1: the reply + action contract. */
class ConvoContractTest {

    @Test
    void aTagAtTheEndIsReadAndStripped() {
        ConvoContract.Reply r = ConvoContract.parse("ya omw [do:come]");
        assertEquals("ya omw", r.text());
        assertEquals(ConvoAction.COME, r.action());
        assertNull(r.arg());
    }

    @Test
    void anArgumentAndOtherBracketsAndSpellingsWork() {
        ConvoContract.Reply r = ConvoContract.parse("k lets go (do: help_kill ant tunnel)");
        assertEquals("k lets go", r.text());
        assertEquals(ConvoAction.HELP, r.action());
        assertEquals("ant tunnel", r.arg());
        assertEquals(ConvoAction.PARTY, ConvoContract.parse("sure [DO=party_invite]").action());
    }

    @Test
    void noTagIsNoAction() {
        ConvoContract.Reply r = ConvoContract.parse("lol ya pigs drop nothing");
        assertEquals("lol ya pigs drop nothing", r.text());
        assertEquals(ConvoAction.NONE, r.action());
    }

    @Test
    void anUnknownActionIsNoActionAndAllTagsAreStripped() {
        ConvoContract.Reply r = ConvoContract.parse("ok [do:give_10m] [do:party]");
        assertEquals("ok", r.text());
        assertEquals(ConvoAction.NONE, r.action(), "only the first tag counts, and an unknown name is no action");
        assertEquals("sure", ConvoContract.parse("sure [action: none]").text());
    }

    @Test
    void onlyTheFirstTagCounts() {
        ConvoContract.Reply r = ConvoContract.parse("sure [do:buff] [do:gift]");
        assertEquals(ConvoAction.BUFF, r.action());
        assertEquals("sure", r.text());
    }

    @Test
    void aJsonAnswerIsReadTheSameWay() {
        ConvoContract.Reply r = ConvoContract.parse("{\"say\": \"omw\", \"do\": \"come\"}");
        assertEquals("omw", r.text());
        assertEquals(ConvoAction.COME, r.action());
        assertEquals(ConvoAction.NONE, ConvoContract.parse("{\"say\": \"lol\"}").action());
    }

    @Test
    void theInstructionsListOnlyPostHocActions() {
        String text = ConvoContract.instructions();
        assertTrue(text.contains("[do:help]") && text.contains("[do:come]") && text.contains("[do:flirt]"), text);
        assertTrue(!text.contains("[do:gift]") && !text.contains("[do:trade]") && !text.contains("[do:date]"),
                "economy and romance steps never start from a tag: " + text);
    }

    @Test
    void economyAndRomanceAreNeverPostHoc() {
        for (ConvoAction a : ConvoAction.values()) {
            if (a.family == ConvoAction.Family.ECONOMY || a == ConvoAction.DATE || a == ConvoAction.GUIDE
                    || a == ConvoAction.LEAVE_GUILD) {
                assertTrue(!a.postHoc, a.name());
            }
        }
    }
}
