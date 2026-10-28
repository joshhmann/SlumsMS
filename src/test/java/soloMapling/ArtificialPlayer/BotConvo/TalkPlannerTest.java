package soloMapling.ArtificialPlayer.BotConvo;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Feasible;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Goal;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Mind;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Relation;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Answer;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Ask;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Manner;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Move;
import soloMapling.ArtificialPlayer.BotConvo.TalkPlanner.Offer;
import soloMapling.ArtificialPlayer.BotConvo.TalkPlanner.Plan;
import soloMapling.ArtificialPlayer.BotConvo.TalkPlanner.Step;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.1 ticket 114: a conversation planned from the bots' minds - greet, small talk, the ask, each answer, the act on
 * a yes - for pairs and groups, and its words (built-in lines, the model's rewording kept only when it keeps the
 * meaning), and the batched memory notes.
 */
class TalkPlannerTest {

    @AfterEach
    void reset() {
        BotTalk.resetForTest();
        BotLineIntents.resetForTest();
    }

    static Character chr(int slot, String name, int level) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(BotStore.BOT_ID_BASE + 90_000 + slot);
        c.setName(name);
        c.setLevel(level);
        return c;
    }

    /** A world of fixed minds and bonds; every ask allowed unless removed. */
    static final class FakeSense implements TalkPlanner.Sense {
        final Map<Integer, Mind> minds = new HashMap<>();
        final Map<String, Relation> rel = new HashMap<>();
        final Map<Ask, Offer> offers = new EnumMap<>(Ask.class);
        final Map<Integer, Feasible> accept = new HashMap<>();
        String met;

        FakeSense mind(Character c, double social, double killer, double mood, Goal goal, double pressure, int town) {
            minds.put(c.getId(), new Mind(c.getId(), c.getName(), c.getLevel(), social, killer, mood, goal, pressure,
                    false, false, false, town, false));
            return this;
        }

        FakeSense bond(Character a, Character b, Relation r) {
            rel.put(a.getId() + ">" + b.getId(), r);
            rel.put(b.getId() + ">" + a.getId(), r);
            return this;
        }

        @Override
        public Mind mind(Character c, long now) {
            return minds.get(c.getId());
        }

        @Override
        public Relation relation(Character from, Character to) {
            return to == null ? Relation.STRANGER : rel.getOrDefault(from.getId() + ">" + to.getId(), Relation.STRANGER);
        }

        @Override
        public Map<Ask, Offer> offers(Character asker, Character to, boolean group, long now, Random rng) {
            Map<Ask, Offer> out = new EnumMap<>(offers);
            if (group) {
                out.keySet().retainAll(List.of(Ask.PARTY, Ask.COME_ALONG));
            }
            return out;
        }

        @Override
        public Feasible canAccept(Character listener, Character asker, Offer offer, long now) {
            return accept.getOrDefault(listener.getId(), Feasible.YES);
        }

        @Override
        public String met(Character a, Character b) {
            return met;
        }

        @Override
        public Map<String, String> slots(Character speaker, Character to) {
            Map<String, String> s = new HashMap<>();
            s.put("map", "Henesys");
            s.put("lv", String.valueOf(to == null ? speaker.getLevel() : to.getLevel()));
            return s;
        }
    }

    static Offer party() {
        return new Offer(Ask.PARTY, "a duo", Map.of(), null, null);
    }

    static Offer comeAlong(int town) {
        return new Offer(Ask.COME_ALONG, TalkPlanner.townName(town), Map.of("town", TalkPlanner.townName(town)), town, null);
    }

    @Test
    void friendsGreetTalkAskAndAYesIsCarriedOut() {
        Character a = chr(1, "Ann", 30);
        Character b = chr(2, "Bob", 32);
        FakeSense s = new FakeSense().mind(a, 0.7, 0.1, 0.4, Goal.LEVEL, 0.9, -1).mind(b, 0.8, 0.1, 0.5, Goal.LEVEL, 0.8, -1)
                .bond(a, b, new Relation("friend", 0.7, true));
        s.offers.put(Ask.PARTY, party());
        s.met = "partied up for Kerning PQ";
        int yes = 0;
        for (int seed = 0; seed < 40; seed++) {
            Plan p = TalkPlanner.plan(List.of(a, b), false, null, s, false, 0, new Random(seed));
            assertEquals(Move.GREET, p.steps.get(0).intent.move());
            assertEquals(a, p.steps.get(0).speaker);
            assertEquals(Move.GREET, p.steps.get(1).intent.move());
            assertEquals(b, p.steps.get(1).speaker, "the other greets back");
            assertTrue(p.steps.stream().anyMatch(x -> x.intent.move() == Move.SMALL_TALK));
            Step small = p.steps.stream().filter(x -> x.intent.move() == Move.SMALL_TALK).findFirst().orElseThrow();
            assertTrue(small.text.contains("partied up for Kerning PQ"), "the small talk brings up their last chat: "
                    + small.text);
            assertEquals(Ask.PARTY, p.ask);
            Step answer = p.steps.stream().filter(x -> x.intent.move() == Move.ANSWER).findFirst().orElseThrow();
            assertEquals(b, answer.speaker);
            assertEquals(Manner.WARM, answer.intent.manner(), "friends answer warmly");
            if (answer.intent.answer() == Answer.YES) {
                yes++;
                assertNotNull(answer.act, "the yes line carries the act");
                assertEquals(List.of(b), answer.act.yes());
                assertEquals(a, answer.act.asker());
            } else {
                assertNull(answer.act);
            }
            for (Step st : p.steps) {
                assertFalse(st.text.contains("{"), "no unfilled slot: " + st.text);
            }
        }
        assertTrue(yes >= 34, "two sociable friends who want exp party up nearly always: " + yes + "/40");
    }

    @Test
    void theOtherBotsStateDecidesFirstAndTheTrueReasonIsKept() {
        Character a = chr(3, "Ann", 30);
        Character b = chr(4, "Bob", 32);
        FakeSense s = new FakeSense().mind(a, 0.7, 0.1, 0.4, Goal.LEVEL, 0.9, -1).mind(b, 0.9, 0.0, 1, Goal.LEVEL, 1, -1)
                .bond(a, b, new Relation("friend", 0.9, true));
        s.offers.put(Ask.PARTY, party());
        s.accept.put(b.getId(), Feasible.no("already in a party"));
        Plan p = TalkPlanner.plan(List.of(a, b), false, Ask.PARTY, s, false, 0, new Random(1));
        assertEquals(Answer.NO, p.decisions.get(b.getId()).answer());
        assertEquals("already in a party", p.decisions.get(b.getId()).why());
        assertTrue(p.steps.stream().noneMatch(x -> x.act != null), "a no is never acted on");
    }

    @Test
    void rivalsAskNoFavoursAndSpeakRudely() {
        Character a = chr(5, "Kai", 40);
        Character b = chr(6, "Rex", 41);
        FakeSense s = new FakeSense().mind(a, 0.1, 0.8, -0.2, Goal.NONE, 0, -1).mind(b, 0.1, 0.8, -0.2, Goal.NONE, 0, -1)
                .bond(a, b, new Relation("rival", -0.6, true));
        s.offers.put(Ask.PARTY, party());
        Plan p = TalkPlanner.plan(List.of(a, b), false, null, s, false, 0, new Random(2));
        assertNull(p.ask);
        assertEquals("they are rivals: no favours asked", p.noAskWhy);
        assertEquals(Manner.RUDE, p.steps.get(0).intent.manner());
    }

    @Test
    void aGreetingOnlyIsAHelloAndItsAnswer() {
        Character a = chr(7, "Ann", 30);
        Character b = chr(8, "Bob", 32);
        FakeSense s = new FakeSense().mind(a, 0.5, 0.1, 0.4, Goal.NONE, 0, -1).mind(b, 0.5, 0.1, 0.4, Goal.NONE, 0, -1)
                .bond(a, b, new Relation("friend", 0.5, true));
        Plan p = TalkPlanner.plan(List.of(a, b), true, null, s, false, 0, new Random(3));
        assertEquals(2, p.steps.size());
        assertTrue(p.greetOnly);
        assertEquals("a greeting only", p.noAskWhy);
    }

    @Test
    void aCounterIsAnsweredByTheOpenerAndTheTripFollowsAYes() {
        Character a = chr(9, "Ann", 30);
        Character b = chr(10, "Bob", 32);
        int henesys = 100000000, ellinia = 101000000;
        FakeSense s = new FakeSense().mind(a, 0.9, 0.0, 0.8, Goal.COMPANY, 1, henesys)
                .bond(a, b, new Relation("friend", 0.4, true));
        s.minds.put(b.getId(), new Mind(b.getId(), "Bob", 32, 0.2, 0.3, -0.2, Goal.LEVEL, 0.9, false, true, false,
                ellinia, false));
        s.offers.put(Ask.COME_ALONG, comeAlong(henesys));
        boolean sawCounter = false;
        for (int seed = 0; seed < 200 && !sawCounter; seed++) {
            Plan p = TalkPlanner.plan(List.of(a, b), false, Ask.COME_ALONG, s, false, 0, new Random(seed));
            if (p.decisions.get(b.getId()).answer() != Answer.COUNTER) {
                continue;
            }
            sawCounter = true;
            Step counter = p.steps.stream().filter(x -> x.intent.answer() == Answer.COUNTER).findFirst().orElseThrow();
            assertTrue(counter.text.toLowerCase().contains("ellinia"), counter.text);
            Step reply = p.steps.stream().filter(x -> x.intent.move() == Move.REPLY).findFirst().orElseThrow();
            assertEquals(a, reply.speaker);
            assertNotNull(p.counterTaken);
            if (p.counterTaken.yes()) {
                assertNotNull(reply.act);
                assertEquals(ellinia, reply.act.offer().payload());
            }
        }
        assertTrue(sawCounter, "a grinder with its own town counters a come-along now and then");
    }

    @Test
    void aGroupTakesTurnsAndEachAnswersForItself() {
        Character a = chr(11, "Ann", 30);
        Character b = chr(12, "Bob", 31);
        Character c = chr(13, "Cat", 29);
        Character d = chr(14, "Dan", 33);
        FakeSense s = new FakeSense().mind(a, 0.8, 0.1, 0.5, Goal.COMPANY, 0.8, -1)
                .mind(b, 0.8, 0.1, 0.5, Goal.COMPANY, 0.8, -1).mind(c, 0.3, 0.2, 0.1, Goal.LEVEL, 0.9, -1)
                .mind(d, 0.1, 0.8, -0.4, Goal.NONE, 0, -1)
                .bond(a, b, new Relation("friend", 0.6, true)).bond(a, c, new Relation("friend", 0.5, true))
                .bond(a, d, new Relation("rival", -0.5, true));
        s.offers.put(Ask.COME_ALONG, comeAlong(101000000));
        s.offers.put(Ask.TRADE, new Offer(Ask.TRADE, "x", Map.of(), null, null)); // never asked of a group
        for (int seed = 0; seed < 30; seed++) {
            Plan p = TalkPlanner.plan(List.of(a, b, c, d), false, null, s, false, 0, new Random(seed));
            assertTrue(p.group);
            List<Integer> answered = new ArrayList<>();
            Character prev = null;
            for (Step st : p.steps) {
                if (st.intent.move() == Move.SMALL_TALK && prev != null && st.speaker != a) {
                    assertNotEquals(prev, st.speaker, "no one speaks twice in a row in the small talk");
                }
                if (st.intent.move() == Move.ANSWER) {
                    answered.add(st.speaker.getId());
                    assertNotEquals(Answer.COUNTER, st.intent.answer(), "a group goes where the opener says, or not");
                }
                prev = st.speaker;
            }
            if (p.ask != null) {
                assertEquals(Ask.COME_ALONG, p.ask);
                assertEquals(3, answered.size(), "each of the three answers for itself");
                assertEquals(3, new java.util.HashSet<>(answered).size());
                assertEquals(Manner.RUDE, p.decisions.get(d.getId()).manner(), "the rival answers rudely");
                Step act = p.steps.stream().filter(x -> x.act != null).findFirst().orElse(null);
                long yes = p.decisions.values().stream().filter(TalkBrain.Decision::yes).count();
                if (yes > 0) {
                    assertNotNull(act);
                    assertEquals(yes, act.act.yes().size(), "every yes goes along");
                }
            }
        }
    }

    @Test
    void builtInLinesArePg13AndNeverClaimToBeAnAi() {
        for (String line : TalkLines.allBuiltIns()) {
            String filled = line.replaceAll("\\{[a-z]+}", "Henesys");
            assertNull(ContentGuard.chatProblem(filled), "content guard: " + line);
            assertFalse(ContentGuard.aiTalk(filled), "AI talk: " + line);
        }
    }

    @Test
    void aModelLineIsKeptOnlyWhenItKeepsTheMeaning() {
        Character a = chr(15, "Ann", 30);
        Character b = chr(16, "Bob", 32);
        FakeSense s = new FakeSense().mind(a, 0.7, 0.1, 0.4, Goal.LEVEL, 0.9, -1).mind(b, 0.9, 0.1, 0.9, Goal.LEVEL, 1, -1)
                .bond(a, b, new Relation("friend", 0.9, true));
        s.offers.put(Ask.PARTY, party());
        Plan p = TalkPlanner.plan(List.of(a, b), false, Ask.PARTY, s, false, 0, new Random(5));
        StringBuilder reply = new StringBuilder();
        for (Step st : p.steps) {
            String text = st.intent.move() == Move.ANSWER
                    ? (st.intent.answer() == Answer.YES ? "nah cant rn" : "sure inv me") // flips the meaning
                    : st.intent.move() == Move.GREET ? "as an ai i cannot greet" : "hey whats up";
            reply.append(st.speaker.getName()).append(": ").append(text).append('\n');
        }
        int kept = BotTalk.reword(p, reply.toString());
        for (Step st : p.steps) {
            if (st.intent.move() == Move.ANSWER || st.intent.move() == Move.GREET) {
                assertNotEquals("llm", st.source, "a flipped answer or AI talk is not kept: " + st.text);
            }
        }
        assertTrue(kept > 0, "the plain lines are kept");
        assertTrue(BotTalk.keeps(TalkIntent.answer(Ask.PARTY, Answer.YES, Manner.WARM, 0, null), "ya im down"));
        assertFalse(BotTalk.keeps(TalkIntent.answer(Ask.PARTY, Answer.NO, Manner.WARM, 0, null), "sure lets go"));
        assertEquals(0, BotTalk.reword(p, "Zed: hi\nZed: yo"), "the wrong speaker is never kept");
    }

    @Test
    void notesAreWrittenInABatchAndALaterGreetingCanUseThem() {
        Map<Integer, LifeMemory> mems = new HashMap<>();
        BotTalk.memories = id -> mems.computeIfAbsent(id, k -> new LifeMemory(k, null));
        Character a = chr(17, "Ann", 30);
        Character b = chr(18, "Bob", 32);
        BotTalk.note(a, b, "partied up");
        BotTalk.note(b, a, "partied up");
        assertNull(mems.get(a.getId()), "nothing is written before the batch");
        assertEquals(2, BotTalk.writeNotes(1_000));
        assertEquals("partied up", mems.get(a.getId()).metWith(b.getId()).what());
        assertEquals("Ann", mems.get(b.getId()).metWith(a.getId()).name());
        // the note survives a save and load (life memory is persisted by the roster sweep)
        LifeMemory reloaded = new LifeMemory(a.getId(), mems.get(a.getId()).save(2_000));
        assertEquals("partied up", reloaded.metWith(b.getId()).what());
    }
}
