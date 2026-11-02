package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        int lastLevel;
        List<String> spots = List.of("ant tunnel");
        String mySpot = "kerning subway";
        boolean botHasSpoken;
        String asked = "hey";
        ChatTriage.Kind kind = ChatTriage.Kind.GREETING;
        ChatTriage.Intent intent = ChatTriage.Intent.NONE;
        String voice = "How you type: lowercase, short, 2008 slang";
        soloMapling.ArtificialPlayer.BotChatActions.Outcome outcome =
                soloMapling.ArtificialPlayer.BotChatActions.Outcome.NONE;
        String outcomeFact;

        V outcome(soloMapling.ArtificialPlayer.BotChatActions.Outcome outcome, String fact) {
            this.outcome = outcome;
            this.outcomeFact = fact;
            return this;
        }

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

        V inPq(boolean pq) {
            this.inPq = pq;
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

        V lastLevel(int level) {
            this.lastLevel = level;
            return this;
        }

        ReplyPlanner.View build() {
            return new ReplyPlanner.View(botName, botLevel, botJob, place, town, mobs, activity, inPq, busy,
                    playerName, playerLevel, playerJob, playerIsBot, bond, romance, note, shared, lastLevel, spots,
                    mySpot,
                    botHasSpoken, asked, kind, intent, voice, outcome, outcomeFact);
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

    /** Bare time claims: a busy line says what it is doing, not only how long it will take (owner's answer 6). */
    private static final List<String> BARE_TIME = List.of("brb", "1 sec", "2 min", "sec sry", "one min",
            "gimme a sec", "busy rn sry", "kinda busy rn");

    @Test
    void busyLineNamesWhatItIsDoing() {
        ReplyPlanner.View grinding = v().kind(ChatTriage.Kind.TALK).asked("lol nice hat").busy(true).town(false)
                .place("ant tunnel").mobs(List.of("Pig")).activity("grinding").build();
        ReplyPlanner.Plan p = ReplyPlanner.plan(grinding);
        assertEquals(ReplyPlanner.Move.BUSY, p.move());
        List<String> lines = ReplyPlanner.scripts(grinding, p.move());
        assertFalse(lines.isEmpty(), "a busy grinding bot has something to say");
        for (String line : lines) {
            assertFalse(BARE_TIME.contains(line), "grinding: \"" + line + "\" says nothing about what it is doing");
            assertTrue(line.contains("pig") || line.contains("ant tunnel"),
                    "grinding: \"" + line + "\" does not name the mob or the map");
            assertTrue(line.length() <= 24, "grinding: \"" + line + "\" is not one short line");
        }

        ReplyPlanner.View inPq = v().kind(ChatTriage.Kind.TALK).asked("lol nice hat").busy(true).inPq(true)
                .activity("waiting to do Orbis PQ").build();
        ReplyPlanner.Plan pqPlan = ReplyPlanner.plan(inPq);
        assertEquals(ReplyPlanner.Move.BUSY, pqPlan.move());
        List<String> pqLines = ReplyPlanner.scripts(inPq, pqPlan.move());
        assertFalse(pqLines.isEmpty());
        for (String line : pqLines) {
            assertFalse(BARE_TIME.contains(line), "pq: \"" + line + "\" says nothing about what it is doing");
            assertTrue(line.contains("pq"), "pq: \"" + line + "\" does not say pq");
            assertTrue(line.length() <= 12, "pq: \"" + line + "\" is not shorter than the grinding line: " + line);
        }
        assertTrue(pqLines.stream().anyMatch(l -> l.length() < lines.get(0).length()),
                "a pq line is shorter than a grinding one: " + pqLines + " vs " + lines);
    }

    /** REVIEW-A2 item 3: a town has no monsters, so a busy bot there never calls what it is doing grinding. */
    @Test
    void aBusyBotInTownNeverSaysItIsGrinding() {
        for (String activity : List.of("waiting to do Orbis PQ", "grinding", "hanging out in town")) {
            ReplyPlanner.View town = v().kind(ChatTriage.Kind.TALK).asked("lol nice hat").busy(true).town(true)
                    .place("henesys").mobs(List.of()).activity(activity).build();
            ReplyPlanner.Plan p = ReplyPlanner.plan(town);
            assertEquals(ReplyPlanner.Move.BUSY, p.move(), activity);
            List<String> lines = ReplyPlanner.scripts(town, p.move());
            assertFalse(lines.isEmpty(), activity);
            for (String line : lines) {
                assertFalse(line.toLowerCase(java.util.Locale.ROOT).contains("grind"),
                        activity + ": \"" + line + "\" says the bot is grinding in a town");
                assertFalse(StateOfMind.claimsGrindingInTown(line),
                        activity + ": \"" + line + "\" claims grinding in town");
            }
        }
        ReplyPlanner.View pqWait = v().kind(ChatTriage.Kind.TALK).asked("lol nice hat").busy(true).town(true)
                .place("orbis").activity("waiting to do Orbis PQ").build();
        assertTrue(ReplyPlanner.scripts(pqWait, ReplyPlanner.Move.BUSY).stream()
                        .anyMatch(l -> l.contains("orbis pq") || l.contains("waiting")),
                "the town line names what the bot is waiting for: "
                        + ReplyPlanner.scripts(pqWait, ReplyPlanner.Move.BUSY));
    }

    @Test
    void noScriptBegs() {
        // owner's answer 5: no begging, in any move, with any player level - and the triage's own con rules are the judge
        for (ReplyPlanner.Move move : ReplyPlanner.Move.values()) {
            for (int playerLevel : List.of(5, 20, 38, 65, 140)) {
                V v = viewFor(move).playerLevel(playerLevel);
                ReplyPlanner.View view = v.build();
                ReplyPlanner.Plan p = ReplyPlanner.plan(view);
                assertNotNull(p, move + " should plan");
                List<String> lines = ReplyPlanner.scripts(view, p.move());
                assertFalse(lines.isEmpty(), move + " has scripts");
                for (String line : lines) {
                    String where = move + " at player " + playerLevel + ": " + line;
                    assertFalse(ChatTriage.conRule(line).filter(con -> con == ChatTriage.Con.SCAM).isPresent(),
                            where + " is begging by the chat rules");
                    assertFalse(line.contains("carry"), where + " asks for a carry");
                }
            }
        }
    }

    @Test
    void levelTalkSaysTheLevelAndTheNextGoal() {
        ReplyPlanner.View view = viewFor(ReplyPlanner.Move.LEVEL_TALK).playerLevel(65).build();
        ReplyPlanner.Plan p = ReplyPlanner.plan(view);
        assertEquals(ReplyPlanner.Move.LEVEL_TALK, p.move());
        assertTrue(facts(p).contains("they are 25 levels above you"), () -> facts(p).toString());
        List<String> lines = ReplyPlanner.scripts(view, p.move());
        assertTrue(lines.stream().anyMatch(l -> l.contains("40")), () -> lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains(LevelTalk.nextGoal(40))), () -> lines.toString());
        assertTrue(facts(p).contains("your next job goal is " + LevelTalk.nextGoal(40)), () -> facts(p).toString());
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

    /** Brief step 7: stable parts first, facts next, the move instruction last, so the model reads the ask last. */
    @Test
    void thePromptPutsTheStablePartsFirstAndTheMoveLast() {
        for (ReplyPlanner.Move move : ReplyPlanner.Move.values()) {
            V v = viewFor(move).bond(ReplyPlanner.Bond.KNOWN);
            ReplyPlanner.View view = v.build();
            ReplyPlanner.Plan p = ReplyPlanner.plan(view);
            assertNotNull(p, move + " should plan from these facts");
            String prompt = ReplyPlanner.prompt(view, p);
            int who = prompt.indexOf("You are Kitsune");
            int voice = prompt.indexOf(v.voice);
            int rules = prompt.indexOf("Never say you are an AI");
            int facts = prompt.indexOf("the only things you may claim");
            int instruction = prompt.indexOf(p.move().outline);
            assertTrue(who == 0, move + ": who the bot is comes first:\n" + prompt);
            assertTrue(voice > who, move + ": the voice comes after who it is:\n" + prompt);
            assertTrue(rules > voice, move + ": the rules come before anything that changes:\n" + prompt);
            assertTrue(facts > rules, move + ": the facts come after the rules:\n" + prompt);
            assertTrue(instruction > facts, move + ": the move is the last thing read:\n" + prompt);
            if (p.brief()) {
                assertTrue(prompt.indexOf("You are busy") > instruction, move + ": the brief note rides with the move");
            }
        }
    }

    @Test
    void promptIsShortAndCarriesOnlyFactsAndOneVoice() {
        for (ReplyPlanner.Move move : ReplyPlanner.Move.values()) {
            for (ReplyPlanner.Bond bond : ReplyPlanner.Bond.values()) {
                V v = viewFor(move).bond(bond);
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
        Random rng = new Random(3);
        for (ReplyPlanner.Move move : ReplyPlanner.Move.values()) {
            V v = viewFor(move);
            ReplyPlanner.View view = v.build();
            ReplyPlanner.Plan p = ReplyPlanner.plan(view);
            assertNotNull(p, move + " should plan from these facts");
            String line = ReplyPlanner.line(view, p, rng);
            assertNotNull(line, move + " has a script");
            assertFalse(StateOfMind.claimsGrindingInTown(line), move + " in a town: " + line);
            assertTrue(line.length() < 70, line);
        }
    }

    /**
     * Owner's answer 8: a greeting comes from what is true now (where the bot is, what it is killing, whether the two
     * have history), not from a stock hello. Progress since the last meeting needs a fact nothing records yet, so the
     * greeting never claims one (the bond fact says it must not).
     */
    @Test
    void greetingsUseTheRelationshipAndWhatIsTrueNow() {
        ReplyPlanner.View town = viewFor(ReplyPlanner.Move.HELLO).build();
        List<String> townLines = ReplyPlanner.scripts(town, ReplyPlanner.Move.HELLO);
        assertTrue(townLines.stream().anyMatch(l -> l.contains("henesys")),
                "a hello says where the bot is: " + townLines);
        assertTrue(townLines.stream().noneMatch(l -> l.contains("remember")),
                "a stranger's hello claims no history: " + townLines);

        ReplyPlanner.View mob = viewFor(ReplyPlanner.Move.HELLO).town(false).place("ant tunnel")
                .mobs(List.of("Pig")).activity("grinding").build();
        List<String> mobLines = ReplyPlanner.scripts(mob, ReplyPlanner.Move.HELLO);
        assertTrue(mobLines.stream().anyMatch(l -> l.contains("pigs")),
                "a hello says what the bot is killing: " + mobLines);

        ReplyPlanner.View known = viewFor(ReplyPlanner.Move.HELLO_BACK).bond(ReplyPlanner.Bond.FRIEND)
                .sharedNote("partied together in henesys").build();
        List<String> knownLines = ReplyPlanner.scripts(known, ReplyPlanner.Move.HELLO_BACK);
        assertTrue(knownLines.stream().anyMatch(l -> l.contains("remember")),
                "a friend's hello comes back to what the two did: " + knownLines);
        assertTrue(knownLines.stream().anyMatch(l -> l.contains("again")),
                "and says it is not the first time: " + knownLines);
        ReplyPlanner.Plan knownPlan = ReplyPlanner.plan(known);
        assertNotNull(knownPlan);
        assertTrue(facts(knownPlan).contains("what you two did before: partied together in henesys"),
                () -> facts(knownPlan).toString());
        assertTrue(facts(knownPlan).contains("they are your friend"), () -> facts(knownPlan).toString());
    }

    /**
     * cv-13 (hunting ground, 2026-11-01): "hey! lolz long time xd" was the planned answer to "hey" on a mob map, so the
     * line named nothing at all. A hello on a mob map is short and names the mob: it is in the facts, the prompt asks
     * for it, and every scripted hello carries it for when the model does not.
     */
    @Test
    void aHelloOnAMobMapNamesTheMob() {
        ReplyPlanner.View grinding = v().town(false).place("henesys hunting ground i")
                .mobs(List.of("Orange Mushroom", "Slime")).activity("grinding").build();
        ReplyPlanner.Plan plan = ReplyPlanner.plan(grinding);
        assertNotNull(plan);
        assertEquals(ReplyPlanner.Move.HELLO, plan.move());
        assertTrue(facts(plan).contains("you are on henesys hunting ground i, killing Orange Mushroom, Slime"),
                () -> facts(plan).toString());
        assertTrue(ReplyPlanner.prompt(grinding, plan).contains("Name the mob you are killing (orange mushrooms)"),
                ReplyPlanner.prompt(grinding, plan));
        List<String> lines = ReplyPlanner.scripts(grinding, plan.move());
        assertFalse(lines.isEmpty(), "a hello on a mob map has scripts");
        for (String line : lines) {
            assertTrue(line.contains("orange mushroom"), "\"" + line + "\" does not name the mob");
            assertTrue(line.length() <= 40, "\"" + line + "\" is not one short line");
        }

        // the mob is a fact on a map whose own name is unknown too, and the ask comes with it
        ReplyPlanner.View unnamed = v().town(false).place("").mobs(List.of("Stirge")).build();
        ReplyPlanner.Plan mobOnly = ReplyPlanner.plan(unnamed);
        assertNotNull(mobOnly);
        assertTrue(facts(mobOnly).contains("you are killing Stirge here"), () -> facts(mobOnly).toString());
        assertTrue(ReplyPlanner.prompt(unnamed, mobOnly).contains("stirges"), ReplyPlanner.prompt(unnamed, mobOnly));

        // a town hello asks for no mob, and one with no monsters keeps the plain hellos
        ReplyPlanner.View town = v().build();
        ReplyPlanner.Plan townPlan = ReplyPlanner.plan(town);
        assertNotNull(townPlan);
        assertNull(townPlan.instruction(), "nothing to name in a town");
        assertTrue(ReplyPlanner.scripts(town, townPlan.move()).stream().noneMatch(l -> l.contains("orange mushroom")));
    }

    /** Owner's answer 8: the greeting knows how far the player has come since the two last talked. */
    @Test
    void aGreetingGratsTheLevelTheyGainedSinceTheLastTalk() {
        ReplyPlanner.View up = viewFor(ReplyPlanner.Move.HELLO_BACK).bond(ReplyPlanner.Bond.KNOWN)
                .lastLevel(30).build(); // playerLevel is 38
        List<String> lines = ReplyPlanner.scripts(up, ReplyPlanner.Move.HELLO_BACK);
        assertTrue(lines.stream().anyMatch(l -> l.contains("grats") || l.contains("leveled")),
                "a level gained is worth one line: " + lines);
        ReplyPlanner.Plan plan = ReplyPlanner.plan(up);
        assertNotNull(plan);
        assertTrue(facts(plan).contains("when you last talked they were level 30"), () -> facts(plan).toString());
        assertTrue(facts(plan).stream().anyMatch(f -> f.contains("they leveled up since then")),
                () -> facts(plan).toString());
    }

    @Test
    void aGreetingNeverInventsALevelUp() {
        ReplyPlanner.View same = viewFor(ReplyPlanner.Move.HELLO_BACK).bond(ReplyPlanner.Bond.KNOWN)
                .lastLevel(38).build();
        List<String> lines = ReplyPlanner.scripts(same, ReplyPlanner.Move.HELLO_BACK);
        assertTrue(lines.stream().noneMatch(l -> l.contains("grats") || l.contains("leveled")),
                "the level is the same as last time, so no grats: " + lines);
        ReplyPlanner.Plan plan = ReplyPlanner.plan(same);
        assertNotNull(plan);
        assertTrue(facts(plan).stream().anyMatch(f -> f.contains("still level 38")), () -> facts(plan).toString());
    }

    @Test
    void aGreetingFromMemorylessHistoryStillClaimsNothing() {
        ReplyPlanner.View never = viewFor(ReplyPlanner.Move.HELLO_BACK).bond(ReplyPlanner.Bond.KNOWN)
                .lastLevel(0).build();
        ReplyPlanner.Plan plan = ReplyPlanner.plan(never);
        assertNotNull(plan);
        assertTrue(facts(plan).stream().anyMatch(f -> f.startsWith("you do not know what they have done")),
                () -> facts(plan).toString());
    }

    /** The pair's own net ({@code SocialGate.fresh}) refuses a line at delivery; the planner only offers another. */
    @Test
    void theDeliveryGuardPicksTheScriptNotThePlanner() {
        ReplyPlanner.View view = viewFor(ReplyPlanner.Move.HELLO).build();
        ReplyPlanner.Plan plan = ReplyPlanner.plan(view);
        List<String> options = ReplyPlanner.scripts(view, plan.move());
        assertTrue(options.size() > 2, options.toString());
        String wanted = options.get(options.size() - 1);
        Random rng = new Random(31);
        for (int i = 0; i < 20; i++) {
            assertEquals(wanted, ReplyPlanner.line(view, plan, rng, text -> text.equals(wanted)),
                    "only the script the delivery point accepts comes out");
            assertNull(ReplyPlanner.line(view, plan, rng, text -> false),
                    "everything refused: null, and the caller says its own fallback");
        }
    }

    /** b247/b248 carry repetition across bots: the planner keeps no ring of its own, only shuffles its scripts. */
    @Test
    void theSameMoveVariesWithoutThePlannersOwnRing() {
        ReplyPlanner.View view = viewFor(ReplyPlanner.Move.HELLO).build();
        ReplyPlanner.Plan plan = ReplyPlanner.plan(view);
        assertNotNull(plan);
        Random rng = new Random(5);
        java.util.Set<String> distinct = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) {
            distinct.add(ReplyPlanner.line(view, plan, rng));
        }
        assertTrue(distinct.size() > 3, "the scripts are shuffled, so one view gives several lines: " + distinct);
    }

    /**
     * Every line a bot says to a player goes through {@link SpeechGuard} at the delivery point
     * ({@code BotConversation.deliverChecked}); a planned line must survive it untouched, which is why the planner
     * scripts carry no slot at all.
     */
    @Test
    void plannedLinesCarryNoSlotAndPassTheSpeechGuard() {
        Character bot = mock(Character.class);
        when(bot.getName()).thenReturn("Kitsune");
        when(bot.getLevel()).thenReturn(40);
        when(bot.getMap()).thenReturn(mock(server.maps.MapleMap.class));
        Character player = mock(Character.class);
        when(player.getName()).thenReturn("Slayer");
        when(player.getLevel()).thenReturn(38);
        Random rng = new Random(9);
        for (ReplyPlanner.Move move : ReplyPlanner.Move.values()) {
            ReplyPlanner.View view = viewFor(move).build();
            ReplyPlanner.Plan plan = ReplyPlanner.plan(view);
            String line = ReplyPlanner.line(view, plan, rng);
            assertNotNull(line, move.name());
            assertFalse(SpeechGuard.hasSlot(line), move + " planned a slot: " + line);
            assertEquals(line, SpeechGuard.check(bot, player, line),
                    move + " planned line was changed or dropped by the speech guard: " + line);
        }
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
    void partyAndFollowAsksArePlannedAndOtherAsksStayTheActionLayers()
            throws Exception {
        // the gate BotConversation uses: plain talk and the party ask the gates already answered are planned; every
        // other ask keeps the action layer's prompt (m02 stage B)
        java.lang.reflect.Method gate = soloMapling.ArtificialPlayer.BotConversation.class
                .getDeclaredMethod("planReply", soloMapling.ArtificialPlayer.BotChatActions.Outcome.class);
        gate.setAccessible(true);
        java.util.Set<soloMapling.ArtificialPlayer.BotChatActions.Outcome> party =
                java.util.Set.of(soloMapling.ArtificialPlayer.BotChatActions.Outcome.PARTY_ACCEPTED,
                        soloMapling.ArtificialPlayer.BotChatActions.Outcome.PARTY_DECLINED,
                        soloMapling.ArtificialPlayer.BotChatActions.Outcome.PARTY_WARY,
                        soloMapling.ArtificialPlayer.BotChatActions.Outcome.PARTY_ALREADY,
                        soloMapling.ArtificialPlayer.BotChatActions.Outcome.FOLLOW_ACCEPTED,
                        soloMapling.ArtificialPlayer.BotChatActions.Outcome.FOLLOW_DECLINED);
        String old = System.getProperty("solomapling.convo.planner");
        try {
            System.clearProperty("solomapling.convo.planner");
            for (soloMapling.ArtificialPlayer.BotChatActions.Outcome outcome
                    : soloMapling.ArtificialPlayer.BotChatActions.Outcome.values()) {
                assertEquals(false, gate.invoke(null, outcome), "switch off: no plan for " + outcome);
            }
            System.setProperty("solomapling.convo.planner", "true");
            for (soloMapling.ArtificialPlayer.BotChatActions.Outcome outcome
                    : soloMapling.ArtificialPlayer.BotChatActions.Outcome.values()) {
                assertEquals(outcome == soloMapling.ArtificialPlayer.BotChatActions.Outcome.NONE
                                || party.contains(outcome), gate.invoke(null, outcome),
                        "planned or the action layer's: " + outcome);
            }
        } finally {
            if (old == null) {
                System.clearProperty("solomapling.convo.planner");
            } else {
                System.setProperty("solomapling.convo.planner", old);
            }
        }
    }

    /** Stage B: the gates answer the party ask; the move only words their verdict, from their own sentence. */
    @Test
    void aPartyYesSaysYesWithoutInventingAnything() {
        ReplyPlanner.View yes = viewFor(ReplyPlanner.Move.PARTY_YES).build();
        ReplyPlanner.Plan p = ReplyPlanner.plan(yes);
        assertNotNull(p, "an accepted party ask is planned");
        assertEquals(ReplyPlanner.Move.PARTY_YES, p.move());
        assertEquals("Say yes to the party and tell them to send you the party invite.", p.instruction());
        String prompt = ReplyPlanner.prompt(yes, p);
        assertTrue(prompt.endsWith(p.instruction() + " "), "the verdict is the last thing read:\n" + prompt);
        assertFalse(prompt.contains("say no"), prompt);
        for (String line : ReplyPlanner.scripts(yes, p.move())) {
            assertTrue(line.toLowerCase(java.util.Locale.ROOT).contains("inv"), "a yes asks for the invite: " + line);
        }
    }

    @Test
    void aPartyNoNeverReadsAsAYes() {
        List<String> yes = List.of("sure", " ya ", "yea", "im down", "ok, ", "ok ", "yep");
        for (soloMapling.ArtificialPlayer.BotChatActions.Outcome no
                : List.of(soloMapling.ArtificialPlayer.BotChatActions.Outcome.PARTY_DECLINED,
                        soloMapling.ArtificialPlayer.BotChatActions.Outcome.PARTY_WARY,
                        soloMapling.ArtificialPlayer.BotChatActions.Outcome.PARTY_ALREADY,
                        soloMapling.ArtificialPlayer.BotChatActions.Outcome.FOLLOW_DECLINED)) {
            V v = viewFor(ReplyPlanner.Move.PARTY_NO).outcome(no, "Say no to the party for now.");
            ReplyPlanner.View view = v.build();
            ReplyPlanner.Plan p = ReplyPlanner.plan(view);
            assertNotNull(p, "a refused party ask is planned: " + no);
            assertEquals(ReplyPlanner.Move.PARTY_NO, p.move(), no.name());
            String prompt = ReplyPlanner.prompt(view, p);
            assertFalse(prompt.contains("Say yes"), no + " prompt says yes:\n" + prompt);
            for (String line : ReplyPlanner.scripts(view, p.move())) {
                String lower = " " + line.toLowerCase(java.util.Locale.ROOT) + " ";
                for (String word : yes) {
                    assertFalse(lower.contains(word), no + " refused the ask but says \"" + line + "\"");
                }
            }
        }
    }

    /** A trade, an invite or a help ask is the action layer's own line: the planner leaves it alone. */
    @Test
    void otherActionsAreNotPlanned() {
        for (soloMapling.ArtificialPlayer.BotChatActions.Outcome other
                : soloMapling.ArtificialPlayer.BotChatActions.Outcome.values()) {
            if (ReplyPlanner.partyMove(other) != null
                    || other == soloMapling.ArtificialPlayer.BotChatActions.Outcome.NONE) {
                continue;
            }
            assertNull(ReplyPlanner.plan(viewFor(ReplyPlanner.Move.HELLO).outcome(other, "Say no.").build()),
                    other + " is the action layer's");
        }
    }

    private static boolean greeting(ReplyPlanner.Move move) {
        return move == ReplyPlanner.Move.HELLO || move == ReplyPlanner.Move.HELLO_BACK
                || move == ReplyPlanner.Move.SMALL_TALK;
    }

    /** The state one move is planned from, so a test over every move states only what that move adds. */
    private static V viewFor(ReplyPlanner.Move move) {
        V v = v();
        v.botHasSpoken = move == ReplyPlanner.Move.SMALL_TALK;
        v.busy = move == ReplyPlanner.Move.BUSY;
        v.asked = switch (move) {
            case ACTIVITY -> "what u doing";
            case TRAIN_SPOT -> "where should i train";
            case LEVEL_TALK -> "what lvl r u";
            case BUSY -> "lol nice hat";
            case PARTY_YES -> "can i join ur pt";
            case PARTY_NO -> "can i join ur pt";
            default -> "hey";
        };
        v.kind = greeting(move) ? ChatTriage.Kind.GREETING : ChatTriage.Kind.TALK;
        if (move == ReplyPlanner.Move.PARTY_YES) {
            v.outcome = soloMapling.ArtificialPlayer.BotChatActions.Outcome.PARTY_ACCEPTED;
            v.outcomeFact = "Say yes to the party and tell them to send you the party invite.";
        } else if (move == ReplyPlanner.Move.PARTY_NO) {
            v.outcome = soloMapling.ArtificialPlayer.BotChatActions.Outcome.PARTY_DECLINED;
            v.outcomeFact = "Say no to the party for now (busy, or just not feeling it).";
        }
        return v;
    }

    private static List<String> facts(ReplyPlanner.Plan p) {
        return new ArrayList<>(p.facts());
    }
}
