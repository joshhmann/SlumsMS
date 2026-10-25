package soloMapling.ArtificialPlayer.BotPartySystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyChatRoute.Choice;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyChatRoute.Last;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyChatRoute.Member;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyChatRoute.Step;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 85: which party bot answers a party-chat line. */
class PartyChatRouteTest {

    static final Member NAYRB = new Member(1_000_000_101, "Nayrb", 400);
    static final Member KIRA = new Member(1_000_000_102, "KiraStar", 120);
    static final Member FAR = new Member(1_000_000_103, "Zenpai", Integer.MAX_VALUE);
    static final long NOW = 1_000_000L;

    @Test
    void aNameWinsEvenWhenAnotherBotIsCloser() {
        Choice c = PartyChatRoute.route("nayrb u ready?", List.of(NAYRB, KIRA), Last.NONE, NOW, new Random(1));
        assertEquals(Step.NAME, c.step());
        assertEquals(NAYRB.id(), c.botId());
    }

    @Test
    void aClearReplyGoesToTheBotThatJustSpoke() {
        Last last = new Last(NAYRB.id(), "omw to the portal", NOW - 10_000);
        Choice c = PartyChatRoute.route("ok see u there", List.of(NAYRB, KIRA), last, NOW, new Random(1));
        assertEquals(Step.REPLY, c.step(), "a short acknowledgement of the last bot line");
        assertEquals(NAYRB.id(), c.botId());
        Choice q = PartyChatRoute.route("how long?", List.of(NAYRB, KIRA), last, NOW, new Random(1));
        assertEquals(Step.REPLY, q.step(), "a question right after a bot line");
    }

    @Test
    void aStaleBotLineIsNotAReply() {
        Last last = new Last(NAYRB.id(), "omw to the portal", NOW - PartyChatRoute.REPLY_MS - 1);
        Choice c = PartyChatRoute.route("ok see u there", List.of(NAYRB, KIRA), last, NOW, new Random(1));
        assertEquals(Step.CLOSEST, c.step());
        assertEquals(KIRA.id(), c.botId());
    }

    @Test
    void otherwiseTheClosestBotOnTheMapAnswersAndExactlyOne() {
        Choice c = PartyChatRoute.route("anyone want to do the next map", List.of(NAYRB, KIRA, FAR), Last.NONE, NOW, new Random(1));
        assertEquals(Step.CLOSEST, c.step());
        assertEquals(KIRA.id(), c.botId());
    }

    @Test
    void withNoBotOnTheMapAnyPartyBotMayAnswer() {
        Choice c = PartyChatRoute.route("hello", List.of(FAR), Last.NONE, NOW, new Random(1));
        assertEquals(Step.ANY, c.step());
        assertEquals(FAR.id(), c.botId());
    }

    @Test
    void nobodyAnswersAnEmptyPartyOrAnEmptyLine() {
        assertFalse(PartyChatRoute.route("hi", List.of(), Last.NONE, NOW, new Random(1)).answers());
        assertFalse(PartyChatRoute.route("  ", List.of(NAYRB), Last.NONE, NOW, new Random(1)).answers());
    }

    @Test
    void namedLinesAndRepliesAlwaysGetAnAnswerOpenChatterOnlySometimes() {
        assertEquals(1.0, PartyChatRoute.answerChance(Step.NAME, "x", 3));
        assertEquals(1.0, PartyChatRoute.answerChance(Step.REPLY, "x", 3));
        assertEquals(0.0, PartyChatRoute.answerChance(Step.NONE, "x", 3));
        assertTrue(PartyChatRoute.answerChance(Step.CLOSEST, "ready?", 3) >= 0.8, "a question to the party");
        assertTrue(PartyChatRoute.answerChance(Step.CLOSEST, "guys wait for me", 3) >= 0.8);
        assertTrue(PartyChatRoute.answerChance(Step.CLOSEST, "this map has so many mobs", 3) < 0.5, "plain chatter");
        assertTrue(PartyChatRoute.answerChance(Step.CLOSEST, "this map has so many mobs", 1) > PartyChatRoute.answerChance(Step.CLOSEST, "this map has so many mobs", 3),
                "a lone bot is plainly the one being talked to");
    }
}
