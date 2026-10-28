package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Decision;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Feasible;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Goal;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Mind;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Relation;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Answer;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Ask;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Manner;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.1 ticket 114: the bot-to-bot decision tree - who greets, what is asked, how it is answered. */
class TalkBrainTest {

    static Mind mind(String name, double social, double killer, double mood, Goal goal, double pressure) {
        return new Mind(name.hashCode(), name, 40, social, killer, mood, goal, pressure, false, false, false, -1, false);
    }

    static final Relation FRIEND = new Relation("friend", 0.6, true);
    static final Relation RIVAL = new Relation("rival", -0.5, true);
    static final Relation NEUTRAL_KNOWN = new Relation("neutral", 0.05, true);

    @Test
    void greetsOnlyBotsItKnows() {
        Mind me = mind("Ann", 0.8, 0.1, 0.5, Goal.NONE, 0);
        Random rng = new Random(3);
        for (int i = 0; i < 100; i++) {
            assertFalse(TalkBrain.greets(me, Relation.STRANGER, rng), "a stranger is never greeted");
        }
        int friends = 0;
        for (int i = 0; i < 200; i++) {
            friends += TalkBrain.greets(me, FRIEND, rng) ? 1 : 0;
        }
        assertTrue(friends > 170, "a sociable bot greets a friend almost always: " + friends);
        Mind busy = new Mind(1, "B", 40, 0.9, 0, 1, Goal.NONE, 0, false, false, false, -1, true);
        assertFalse(TalkBrain.greets(busy, FRIEND, rng), "a bot keeping a commitment does not stop to chat");
    }

    @Test
    void mannerFollowsBondPersonaAndMood() {
        assertEquals(Manner.WARM, TalkBrain.manner(mind("A", 0.7, 0.1, 0.3, Goal.NONE, 0), FRIEND));
        assertEquals(Manner.RUDE, TalkBrain.manner(mind("K", 0.2, 0.7, 0.0, Goal.NONE, 0), RIVAL));
        assertEquals(Manner.CURT, TalkBrain.manner(mind("S", 0.6, 0.1, 0.4, Goal.NONE, 0), RIVAL),
                "a gentle bot is curt to a rival, not rude");
        assertEquals(Manner.CURT, TalkBrain.manner(mind("N", 0.25, 0.25, 0.0, Goal.NONE, 0), NEUTRAL_KNOWN));
        assertEquals(Manner.RUDE, TalkBrain.manner(mind("G", 0.05, 0.8, -1, Goal.NONE, 0), NEUTRAL_KNOWN),
                "a worn-out Killer snaps even at a neutral bot");
    }

    @Test
    void theGameRuleComesFirstAndItsReasonIsTheAnswer() {
        Decision d = TalkBrain.answer(mind("B", 1, 0, 1, Goal.LEVEL, 1), mind("A", 0.5, 0, 0, Goal.NONE, 0), FRIEND,
                Ask.PARTY, Feasible.no("already in a party"), -1, new Random(1));
        assertEquals(Answer.NO, d.answer());
        assertEquals("already in a party", d.why());
        assertEquals(Manner.WARM, d.manner(), "a friend says no warmly");
    }

    @Test
    void friendsMostlySayYesRivalsMostlyNo() {
        Mind asker = mind("A", 0.5, 0.1, 0.2, Goal.NONE, 0);
        Mind friendly = mind("B", 0.7, 0.1, 0.4, Goal.LEVEL, 0.7);
        Mind sour = mind("C", 0.2, 0.7, -0.3, Goal.NONE, 0);
        Random rng = new Random(7);
        int yesFriend = 0, yesRival = 0;
        for (int i = 0; i < 400; i++) {
            yesFriend += TalkBrain.answer(friendly, asker, FRIEND, Ask.PARTY, Feasible.YES, -1, rng).yes() ? 1 : 0;
            yesRival += TalkBrain.answer(sour, asker, RIVAL, Ask.PARTY, Feasible.YES, -1, rng).yes() ? 1 : 0;
        }
        assertTrue(yesFriend > 340, "friends: " + yesFriend);
        assertTrue(yesRival < 40, "rivals: " + yesRival);
    }

    @Test
    void goalsShapeTheAnswer() {
        Mind asker = mind("A", 0.5, 0.1, 0.2, Goal.NONE, 0);
        Mind leveling = new Mind(2, "L", 40, 0.3, 0.2, 0.1, Goal.LEVEL, 0.9, false, true, false, -1, false);
        Mind lonely = new Mind(3, "C", 40, 0.3, 0.2, 0.1, Goal.COMPANY, 0.9, true, false, false, -1, false);
        double grind = TalkBrain.willingness(leveling, asker, NEUTRAL_KNOWN, Ask.COME_ALONG);
        double company = TalkBrain.willingness(lonely, asker, NEUTRAL_KNOWN, Ask.COME_ALONG);
        assertTrue(company > grind + 0.4, "a lonely bot wants to come along, a grinder near its goal does not: "
                + company + " vs " + grind);
        assertTrue(TalkBrain.willingness(leveling, asker, NEUTRAL_KNOWN, Ask.PARTY)
                > TalkBrain.willingness(leveling, asker, NEUTRAL_KNOWN, Ask.HELP), "a leveler parties, it doesn't carry");
    }

    @Test
    void aComeAlongItDoesNotWantIsCounteredWithItsOwnTown() {
        Mind me = new Mind(2, "B", 40, 0.3, 0.2, 0.0, Goal.LEVEL, 0.8, false, true, false, 101000000, false);
        Mind asker = mind("A", 0.5, 0.1, 0.2, Goal.NONE, 0);
        int counters = 0;
        Random rng = new Random(11);
        for (int i = 0; i < 200; i++) {
            Decision d = TalkBrain.answer(me, asker, NEUTRAL_KNOWN, Ask.COME_ALONG, Feasible.YES, 100000000, rng);
            counters += d.answer() == Answer.COUNTER ? 1 : 0;
        }
        assertTrue(counters > 40, "counters: " + counters);
        Decision same = TalkBrain.answer(me, asker, NEUTRAL_KNOWN, Ask.COME_ALONG, Feasible.YES, 101000000,
                new Random(5));
        assertNotEquals(Answer.COUNTER, same.answer(), "never a counter to the town it was going to anyway");
    }

    @Test
    void theAskComesFromTheOpenersGoalsAndOnlyFromWhatIsAllowed() {
        Mind leveler = new Mind(1, "A", 30, 0.2, 0.2, 0.2, Goal.LEVEL, 1.0, false, true, true, -1, false);
        Mind strong = new Mind(2, "B", 45, 0.3, 0.2, 0.2, Goal.NONE, 0, false, false, false, -1, false);
        Map<Ask, Feasible> all = new EnumMap<>(Ask.class);
        for (Ask a : Ask.values()) {
            all.put(a, Feasible.YES);
        }
        Map<Ask, Double> w = TalkBrain.askWeights(leveler, strong, FRIEND, all);
        assertTrue(w.get(Ask.PARTY) > w.get(Ask.GOSSIP) && w.get(Ask.HELP) > w.get(Ask.GOSSIP),
                "a bot pushing for its next level asks for a party or help: " + w);
        Map<Ask, Feasible> onlyGossip = Map.of(Ask.GOSSIP, Feasible.YES);
        Random rng = new Random(2);
        for (int i = 0; i < 50; i++) {
            assertEquals(Ask.GOSSIP, TalkBrain.chooseAsk(leveler, strong, FRIEND, onlyGossip, rng));
        }
        assertNull(TalkBrain.chooseAsk(leveler, strong, RIVAL, all, rng), "no favours asked of a rival");
        assertNull(TalkBrain.chooseAsk(leveler, strong, FRIEND, Map.of(), rng));
    }

    @Test
    void groupTurnsNeverRepeatTheLastSpeaker() {
        double[] chat = {0.9, 0.1, 0.5, 0.3};
        Random rng = new Random(4);
        int last = 0;
        int[] spoke = new int[4];
        for (int i = 0; i < 400; i++) {
            int next = TalkBrain.nextSpeaker(chat, last, -1, rng);
            assertNotEquals(last, next);
            spoke[next]++;
            last = next;
        }
        assertTrue(spoke[0] > spoke[1], "the chattiest speaks more");
        assertEquals(2, TalkBrain.nextSpeaker(chat, 0, 2, rng), "the one addressed answers first");
    }
}
