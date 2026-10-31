package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * m02 stage A: the planner turns the bot's state, the player's state and the read of the line into one move and the
 * facts it may state, one test per branch, and the phrasing prompt stays short, single-voiced and free of anything the
 * checker would reject.
 */
class ReplyPlannerTest {

    private static V v() {
        return new V();
    }

    /** A small builder, so each test states only what it is about. */
    private static final class V {
        String botName = "Kitsune";
        int botLevel = 40;
        String botJob = "hunter";
        String place = "henesys";
        boolean town = true;
        List<String> mobs = List.of();
        String activity = "hanging out in town";
        boolean inPq;
        boolean busy;
        String playerName = "Slayer";
        int playerLevel = 38;
        String playerJob = "warrior";
        boolean playerIsBot;
        ReplyPlanner.Bond bond = ReplyPlanner.Bond.STRANGER;
        String romance;
        String note;
        String shared;
        List<String> spots = List.of("ant tunnel");
        String mySpot = "kerning subway";
        boolean botHasSpoken;
        String asked = "hey";
        ChatTriage.Kind kind = ChatTriage.Kind.GREETING;
        ChatTriage.Intent intent = ChatTriage.Intent.NONE;
        String voice = "How you type: lowercase, short, 2008 slang";

        V bond(ReplyPlanner.Bond bond) {
            this.bond = bond;
            return this;
        }

        V note(String note) {
            this.note = note;
            return this;
        }

        V sharedNote(String shared) {
            this.shared = shared;
            return this;
        }

        V kind(ChatTriage.Kind kind) {
            this.kind = kind;
            return this;
        }

        V intent(ChatTriage.Intent intent) {
            this.intent = intent;
            return this;
        }

        V asked(String asked) {
            this.asked = asked;
            return this;
        }

        V botHasSpoken(boolean spoken) {
            this.botHasSpoken = spoken;
            return this;
        }

        V busy(boolean busy) {
            this.busy = busy;
            return this;
        }

        V town(boolean town) {
            this.town = town;
            return this;
        }

        V place(String place) {
            this.place = place;
            return this;
        }

        V mobs(List<String> mobs) {
            this.mobs = mobs;
            return this;
        }

        V activity(String activity) {
            this.activity = activity;
            return this;
        }

        V voice(String voice) {
            this.voice = voice;
            return this;
        }

        V playerLevel(int level) {
            this.playerLevel = level;
            return this;
        }

        V spots(List<String> spots) {
            this.spots = spots;
            return this;
        }

        V playerIsBot(boolean bot) {
            this.playerIsBot = bot;
            return this;
        }

        ReplyPlanner.View build() {
            return new ReplyPlanner.View(botName, botLevel, botJob, place, town, mobs, activity, inPq, busy,
                    playerName, playerLevel, playerJob, playerIsBot, bond, romance, note, shared, spots, mySpot,
                    botHasSpoken, asked, kind, intent, voice);
        }
    }

    @Test
    void strangerGreetingIsHello() {
        ReplyPlanner.Plan p = ReplyPlanner.plan(v().build());
        assertNotNull(p);
        assertEquals(ReplyPlanner.Move.HELLO, p.move());
        assertTrue(facts(p).contains("you have never talked to them before"), () -> facts(p).toString());
        assertTrue(facts(p).contains("you are in henesys, a town: no monsters here and you are not grinding"), () -> facts(p).toString());
        assertFalse(facts(p).contains("they are your friend"));
    }

    @Test
    void greetingSomeoneYouKnowGreetsBack() {
        ReplyPlanner.Plan p = ReplyPlanner.plan(v().bond(ReplyPlanner.Bond.KNOWN).build());
        assertEquals(ReplyPlanner.Move.HELLO_BACK, p.move());
        assertTrue(facts(p).contains("you know them from before"), () -> facts(p).toString());
    }

    @Test
    void greetingAgainMidChatIsSmallTalk() {
        ReplyPlanner.Plan p = ReplyPlanner.plan(v().botHasSpoken(true).build());
        assertEquals(ReplyPlanner.Move.SMALL_TALK, p.move());
    }

    @Test
    void friendNoteInTheFacts() {
        ReplyPlanner.Plan p = ReplyPlanner.plan(v().bond(ReplyPlanner.Bond.FRIEND).note("hunts at golems").build());
        assertTrue(facts(p).contains("they are your friend"));
        assertTrue(facts(p).contains("what you remember about them: hunts at golems"));
    }

    @Test
    void datingIsTheOnlyBondThatMentionsRomance() {
        ReplyPlanner.Plan dating = ReplyPlanner.plan(v().bond(ReplyPlanner.Bond.DATING).build());
        assertTrue(facts(dating).stream().anyMatch(f -> f.contains("dating them")), () -> facts(dating).toString());
        for (ReplyPlanner.Bond bond : List.of(ReplyPlanner.Bond.STRANGER, ReplyPlanner.Bond.KNOWN,
                ReplyPlanner.Bond.FRIEND, ReplyPlanner.Bond.WARY)) {
            ReplyPlanner.Plan p = ReplyPlanner.plan(v().bond(bond).build());
            assertFalse(facts(p).stream().anyMatch(f -> f.contains("dating") || f.contains("married")), bond + ": " + facts(p));
        }
    }

    @Test
    void knownPlayerWeHaveNoNewsAbout() {
        ReplyPlanner.Plan p = ReplyPlanner.plan(v().bond(ReplyPlanner.Bond.KNOWN).note(null).build());
        assertTrue(facts(p).stream().anyMatch(f -> f.contains("do not say they leveled up")), () -> facts(p).toString());
        ReplyPlanner.Plan withNote = ReplyPlanner.plan(v().bond(ReplyPlanner.Bond.KNOWN).note("died at golems").build());
        assertFalse(facts(withNote).stream().anyMatch(f -> f.contains("do not say they leveled up")), () -> facts(withNote).toString());
    }

    @Test
    void whatAreYouDoingIsActivity() {
        ReplyPlanner.Plan p = ReplyPlanner.plan(v().kind(ChatTriage.Kind.TALK).asked("what u doing").build());
        assertEquals(ReplyPlanner.Move.ACTIVITY, p.move());
        assertTrue(facts(p).contains("right now you are hanging out in town"), () -> facts(p).toString());
    }

    @Test
    void grindingActivityNamesTheMobsAndNotTheTown() {
        ReplyPlanner.Plan here = ReplyPlanner.plan(v().kind(ChatTriage.Kind.TALK).asked("wyd").town(false)
                .place("ant tunnel").mobs(List.of("Zombie Mushroom")).activity("grinding").build());
        assertTrue(facts(here).contains("you are on ant tunnel, killing Zombie Mushroom"), () -> facts(here).toString());
        assertFalse(facts(here).stream().anyMatch(f -> f.contains("hanging out")), () -> facts(here).toString());
    }

    @Test
    void aTownActivityIsNeverGrinding() {
        ReplyPlanner.Plan p = ReplyPlanner.plan(v().kind(ChatTriage.Kind.TALK).asked("wat u up to").activity("grinding")
                .build());
        assertTrue(facts(p).stream().noneMatch(f -> f.contains("you are grinding") || f.contains("killing")), () -> facts(p).toString());
    }

    @Test
    void whereShouldITrainNamesASpot() {
        ReplyPlanner.Plan p = ReplyPlanner.plan(v().kind(ChatTriage.Kind.TALK).asked("where should i train").build());
        assertEquals(ReplyPlanner.Move.TRAIN_SPOT, p.move());
        assertTrue(facts(p).contains("a real training spot for their level is ant tunnel"), () -> facts(p).toString());
        assertTrue(facts(p).contains("you train at kerning subway"), () -> facts(p).toString());
    }

    @Test
    void whereShouldITrainWithNoSpotIsFreeChat() {
        assertNull(ReplyPlanner.plan(v().kind(ChatTriage.Kind.TALK).asked("where should i train").spots(List.of())
                .build()));
    }

    @Test
    void levelTalkUsesTheRealGap() {
        ReplyPlanner.Plan high = ReplyPlanner.plan(v().kind(ChatTriage.Kind.TALK).asked("what lvl r u").build());
        assertEquals(ReplyPlanner.Move.LEVEL_TALK, high.move());
        assertTrue(facts(high).contains("you are level 40 hunter"), () -> facts(high).toString());
        ReplyPlanner.Plan above = ReplyPlanner.plan(v().kind(ChatTriage.Kind.TALK).asked("im lvl 65")
                .playerLevel(65).build());
        assertTrue(facts(above).contains("they are 25 levels above you"), () -> facts(above).toString());
    }

    @Test
    void busyLineIsShortAndSaysWhy() {
        ReplyPlanner.Plan p = ReplyPlanner.plan(v().kind(ChatTriage.Kind.TALK).asked("lol nice hat").busy(true)
                .town(false).mobs(List.of("Horny Mushroom")).activity("grinding").build());
        assertEquals(ReplyPlanner.Move.BUSY, p.move());
        assertTrue(p.brief());
        assertTrue(facts(p).contains("you are in the middle of grinding here"), () -> facts(p).toString());
    }

    @Test
    void anythingElseKeepsTheOldPrompt() {
        assertNull(ReplyPlanner.plan(v().kind(ChatTriage.Kind.TALK).asked("yo i saw a cool hat").build()),
                "free chat: no plan, so the reply takes the prompt it took before this ticket");
        assertNull(ReplyPlanner.plan(v().kind(ChatTriage.Kind.TALK).asked("sell me ur hat").intent(ChatTriage.Intent.TRADE)
                .build()), "an ask belongs to the action layer");
        assertNull(ReplyPlanner.plan(v().playerIsBot(true).build()), "bot to bot talk has its own decisions");
        assertNull(ReplyPlanner.plan(null));
    }

    @Test
    void greetingReaderTakesGreetingsAndLeavesTheRestAlone() {
        for (String said : List.of("hey", "hi guys", "sup", "hello there", "hey hows it going", "how r u",
                "yo how you doin", "hi again", "nice to meet u", "heya", "hey dood")) {
            assertTrue(ReplyPlanner.greets(said), said + " is a greeting");
        }
        for (String said : List.of("yo i saw a cool hat", "lol ok", "wanna grind together", "yo u selling that", "", "sell me ur hat")) {
            assertFalse(ReplyPlanner.greets(said), said + " is not a greeting");
        }
        assertFalse(ReplyPlanner.greets(null));
    }

    @Test
    void promptIsShortAndCarriesOnlyFactsAndOneVoice() {
        for (ReplyPlanner.Move move : ReplyPlanner.Move.values()) {
            for (ReplyPlanner.Bond bond : ReplyPlanner.Bond.values()) {
                V v = v().bond(bond);
                v.kind = greeting(move) ? ChatTriage.Kind.GREETING : ChatTriage.Kind.TALK;
                v.botHasSpoken = move == ReplyPlanner.Move.SMALL_TALK;
                v.asked = switch (move) {
                    case ACTIVITY -> "what u doing";
                    case TRAIN_SPOT -> "where should i train";
                    case LEVEL_TALK -> "what lvl r u";
                    case BUSY -> "lol nice hat";
                    default -> "hey";
                };
                v.busy = move == ReplyPlanner.Move.BUSY;
                ReplyPlanner.View view = v.build();
                ReplyPlanner.Plan p = ReplyPlanner.plan(view);
                assertNotNull(p, move + "/" + bond + " should plan from these facts");
                String prompt = ReplyPlanner.prompt(view, p);
                assertTrue(prompt.length() < 1000, move + " prompt is " + prompt.length() + " chars:\n" + prompt);
                assertTrue(prompt.contains(p.move().outline), prompt);
                assertTrue(prompt.contains("the only things you may claim"), prompt);
                assertFalse(prompt.contains("Chat style:"), "one voice only:\n" + prompt);
                assertFalse(prompt.toLowerCase(java.util.Locale.ROOT).contains("mood:"), prompt);
                assertTrue(prompt.contains("Never say you are an AI"), prompt);
                if (bond != ReplyPlanner.Bond.DATING) {
                    assertFalse(prompt.contains("dating"), prompt);
                }
                if (view.town()) {
                    assertFalse(prompt.contains("you are grinding here"), prompt);
                }
            }
        }
    }

    @Test
    void theRealVoiceLinesKeepThePromptShort() {
        java.util.List<Integer> lengths = new ArrayList<>();
        for (int slot = 1; slot <= 8; slot++) {
            String voice = ConvoPersona.of(slot).prompt("hunter", false) + ConvoTune.promptLines();
            ReplyPlanner.View view = v().voice(voice).botHasSpoken(slot % 2 == 0).build();
            ReplyPlanner.Plan plan = ReplyPlanner.plan(view);
            assertNotNull(plan);
            int len = ReplyPlanner.prompt(view, plan).length();
            lengths.add(len);
            assertTrue(len < 1000, "slot " + slot + " prompt is " + len + " chars with its real voice line");
            ReplyPlanner.View busy = v().voice(voice).kind(ChatTriage.Kind.TALK).asked("where should i train").build();
            int busyLen = ReplyPlanner.prompt(busy, ReplyPlanner.plan(busy)).length();
            assertTrue(busyLen < 1000, "slot " + slot + " train prompt is " + busyLen + " chars");
        }
        java.util.Collections.sort(lengths);
        int median = lengths.get(lengths.size() / 2);
        System.out.println("planner prompt with a real voice line: median " + median + " chars, longest "
                + lengths.get(lengths.size() - 1));
        assertTrue(median < 1000);
    }

    @Test
    void scriptsSayTheSameMoveWithoutTheModel() {
        ReplyPlanner.clearForTest();
        Random rng = new Random(3);
        for (ReplyPlanner.Move move : ReplyPlanner.Move.values()) {
            V v = v();
            v.botHasSpoken = move == ReplyPlanner.Move.SMALL_TALK;
            v.busy = move == ReplyPlanner.Move.BUSY;
            v.asked = switch (move) {
                case ACTIVITY -> "what u doing";
                case TRAIN_SPOT -> "where should i train";
                case LEVEL_TALK -> "what lvl r u";
                case BUSY -> "lol nice hat";
                default -> "hey";
            };
            v.kind = greeting(move) ? ChatTriage.Kind.GREETING : ChatTriage.Kind.TALK;
            ReplyPlanner.View view = v.build();
            ReplyPlanner.Plan p = ReplyPlanner.plan(view);
            assertNotNull(p, move + " should plan from these facts");
            String line = ReplyPlanner.line(100 + move.ordinal(), view, p, rng);
            assertNotNull(line, move + " has a script");
            assertFalse(StateOfMind.claimsGrindingInTown(line), move + " in a town: " + line);
            assertTrue(line.length() < 70, line);
        }
    }

    @Test
    void aThirdBotDoesNotSayTheSameScriptWordForWord() {
        ReplyPlanner.clearForTest();
        long now = 1_000_000L;
        ReplyPlanner.said(1, "hey", now);
        assertTrue(ReplyPlanner.fresh(1, "hey", now), "a bot may repeat itself");
        ReplyPlanner.said(2, "hey", now);
        assertFalse(ReplyPlanner.fresh(3, "hey", now), "three bots, word for word: no");
        assertTrue(ReplyPlanner.fresh(3, "yo", now), "a different line is free");
        assertTrue(ReplyPlanner.fresh(3, "hey", now + 11 * 60_000L), "ten minutes later it is fair again");
    }

    @Test
    void theSwitchIsOffUnlessAsked() {
        String old = System.getProperty("solomapling.convo.planner");
        try {
            System.clearProperty("solomapling.convo.planner");
            assertFalse(ConvoSwitches.planner(), "off by default: TEST turns it on, live only after the owner's ok");
            System.setProperty("solomapling.convo.planner", "false");
            assertFalse(ConvoSwitches.planner());
            System.setProperty("solomapling.convo.planner", "true");
            assertTrue(ConvoSwitches.planner());
        } finally {
            if (old == null) {
                System.clearProperty("solomapling.convo.planner");
            } else {
                System.setProperty("solomapling.convo.planner", old);
            }
        }
    }

    @Test
    void anAskIsNeverPlanned()
            throws Exception {
        // the gate BotConversation uses: the planner only words plain talk
        java.lang.reflect.Method gate = soloMapling.ArtificialPlayer.BotConversation.class
                .getDeclaredMethod("planReply", soloMapling.ArtificialPlayer.BotChatActions.Outcome.class);
        gate.setAccessible(true);
        String old = System.getProperty("solomapling.convo.planner");
        try {
            System.clearProperty("solomapling.convo.planner");
            for (soloMapling.ArtificialPlayer.BotChatActions.Outcome outcome
                    : soloMapling.ArtificialPlayer.BotChatActions.Outcome.values()) {
                assertEquals(false, gate.invoke(null, outcome), "switch off: no plan for " + outcome);
            }
            System.setProperty("solomapling.convo.planner", "true");
            assertEquals(true, gate.invoke(null, soloMapling.ArtificialPlayer.BotChatActions.Outcome.NONE));
            for (soloMapling.ArtificialPlayer.BotChatActions.Outcome outcome
                    : soloMapling.ArtificialPlayer.BotChatActions.Outcome.values()) {
                if (outcome != soloMapling.ArtificialPlayer.BotChatActions.Outcome.NONE) {
                    assertEquals(false, gate.invoke(null, outcome), "an ask is the action layer's: " + outcome);
                }
            }
        } finally {
            if (old == null) {
                System.clearProperty("solomapling.convo.planner");
            } else {
                System.setProperty("solomapling.convo.planner", old);
            }
        }
    }

    private static boolean greeting(ReplyPlanner.Move move) {
        return move == ReplyPlanner.Move.HELLO || move == ReplyPlanner.Move.HELLO_BACK
                || move == ReplyPlanner.Move.SMALL_TALK;
    }

    private static List<String> facts(ReplyPlanner.Plan p) {
        return new ArrayList<>(p.facts());
    }
}
