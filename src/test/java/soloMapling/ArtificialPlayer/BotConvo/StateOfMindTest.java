package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 156: the state-of-mind checker. The rejected lines are TEST's 2026-10-30 transcript (b099-b102), each a
 * fail-before case: before this ticket nothing checked a reply, so every one of them went out.
 */
class StateOfMindTest {

    static final int HENESYS = 100000000, HUNTING_GROUND = 104040000, SLEEPYWOOD = 105040300, ANT_TUNNEL = 105050000,
            ANT_TUNNEL_II = 105050100, PERION = 102000000, GOLEM_TEMPLE = 100040100, FM = 910000000;

    @BeforeEach
    void names() {
        ConvoEntities.useForTest(new ConvoEntities.Names() {
            public Map<String, Integer> maps() {
                return Map.of("ant tunnel i", ANT_TUNNEL, "ant tunnel ii", ANT_TUNNEL_II,
                        "henesys hunting ground i", HUNTING_GROUND, "golem temple entrance", GOLEM_TEMPLE,
                        "land of wild boar", 101040001, "tunnel", 999, "the deep", 998, "pig beach", 104010001);
            }

            public Map<String, Integer> mobs() {
                return Map.of("wild boar", 2230102, "fire boar", 3210100, "stone golem", 5130101,
                        "horny mushroom", 2110200, "zombie mushroom", 2230101, "orange mushroom", 1210102,
                        "slime", 210100, "will", 9999990, "boss", 9999991, "snail", 100100);
            }

            public Map<String, Integer> items() {
                return Map.of("work gloves", 1082002, "orange mushroom cap", 1002033);
            }
        });
        StateOfMind.useRegions(id -> switch (id) {
            case HUNTING_GROUND, GOLEM_TEMPLE -> HENESYS;
            case ANT_TUNNEL, ANT_TUNNEL_II -> SLEEPYWOOD;
            case 101040001 -> PERION;
            default -> -1;
        });
    }

    @AfterEach
    void reset() {
        ConvoEntities.useForTest(null);
        StateOfMind.useRegions(null);
        StateOfMind.resetStatsForTest();
    }

    /** ItsArrow / narutoo / PuddinKernin: a lv 38 hunter standing in Henesys town, talking to the owner (lv 75). */
    static StateOfMind.Builder inHenesys() {
        return StateOfMind.builder().bot("ItsArrow", 38, "hunter")
                .here(new StateOfMind.Place(HENESYS, "Henesys", HENESYS), List.of())
                .doing("hanging out in town", null)
                .player("Josh", 75, "chief bandit", false);
    }

    /** The never-met crowd: bots on Henesys Hunting Ground I with snails and slimes, talking to a lv 200. */
    static StateOfMind.Builder onHuntingGround(boolean met) {
        return StateOfMind.builder().bot("Mushy343", 22, "magician")
                .here(new StateOfMind.Place(HUNTING_GROUND, "Henesys Hunting Ground I", HENESYS),
                        List.of("Snail", "Slime"))
                .doing("grinding", null)
                .player("Josh", 200, "bishop", false)
                .met(met, met ? List.of("asked me for a party once") : List.of());
    }

    private static void rejected(StateOfMind.Reason why, String line, StateOfMind.Snapshot s) {
        StateOfMind.Verdict v = StateOfMind.check(line, s);
        assertFalse(v.ok(), "should be rejected: " + line);
        assertEquals(why, v.reason(), line + " -> " + v);
    }

    private static void passes(String line, StateOfMind.Snapshot s) {
        StateOfMind.Verdict v = StateOfMind.check(line, s);
        assertTrue(v.ok(), line + " -> " + v);
    }

    // ── b099: made-up maps and mobs (ItsArrow, 08:35-08:36 UTC) ──

    @Test
    void b099MadeUpPlacesAndMonstersAreRejected() {
        StateOfMind.Snapshot s = inHenesys().build();
        assertFalse(StateOfMind.check("must be nice, im stuck at 47 grinding sleepy dungeon", s).ok());
        rejected(StateOfMind.Reason.PLACE, "ant tunnel, the deep part with the wild boars", s);
        rejected(StateOfMind.Reason.MOB, "wild boars and fire boars, decent exp tbh", s);
        rejected(StateOfMind.Reason.MOB, "lol my bad, boars r in perion, ant tunnel is slimes n mushrooms", s);
        assertFalse(StateOfMind.check("lol ya i gotta grind somewhere, sleepy dungeon gets old", s).ok());
    }

    @Test
    void placesAndMonstersInTheSnapshotPass() {
        StateOfMind.Snapshot s = inHenesys()
                .knows(new StateOfMind.Place(ANT_TUNNEL_II, "Ant Tunnel", SLEEPYWOOD),
                        List.of("Horny Mushroom", "Zombie Mushroom"))
                .build();
        passes("u should try ant tunnel, horny mushrooms r good exp", s);
        passes("ant tunnel has zombie mushrooms tbh", s);
        passes("shrooms in ant tunnel are ok", s);
        rejected(StateOfMind.Reason.MOB, "ant tunnel has wild boars", s); // still not the boars
    }

    @Test
    void aPlaceOrMonsterThePlayerNamedMayBeTalkedAbout() {
        StateOfMind.Snapshot s = inHenesys().line("is ant tunnel any good? i heard wild boars r there", true).build();
        passes("idk ant tunnel is ok i guess", s);
        passes("wild boars? idk", s);
    }

    // ── b100: false familiarity ──

    @Test
    void b100AgainAndInventedHistoryToSomeoneNeverMetIsRejected() {
        StateOfMind.Snapshot s = onHuntingGround(false).build();
        for (String line : List.of(
                "yo again lol u still in for kpq later or what",
                "oh hey {player} good to see u again lol",
                "oh hey fiance nim ^^ still grinding toward 70?",
                "oh hey u again lol town is wild tonight",
                "Oh hey, you came back <3 still thinking about our wedding?",
                "oh hey, u again xd amorias gone full drama tonight huh!!!",
                "yo again lol just half afk in hene tbh",
                "Yo again, just chillin in hene, you still on that grind to 70?",
                "yo again xd still grindin at 70 or slackin like me lolz!!!",
                "yo again, just chillin in hene, grats on 71 btw ^^",
                "yo again ^^ just chillin, hows ur lvl 100 goal going")) {
            rejected(StateOfMind.Reason.FAMILIAR, line, s);
        }
    }

    @Test
    void someoneMetBeforeMayBeGreetedAgainButNotGivenAWrongLevel() {
        StateOfMind.Snapshot s = onHuntingGround(true).build();
        passes("yo again lol", s);
        passes("oh hey u again, still need that party?", s);
        rejected(StateOfMind.Reason.LEVEL, "yo again ^^ just chillin, hows ur lvl 100 goal going", s);
        rejected(StateOfMind.Reason.LEVEL, "you still on that grind to 70?", s);
        rejected(StateOfMind.Reason.LEVEL, "grats on 71 btw ^^", s);
        passes("grats on 200!!", s);
    }

    @Test
    void earlierLinesOfThisChatCountAsHavingMet() {
        StateOfMind.Snapshot s = onHuntingGround(false).line("hey", true).line("sup", false)
                .line("i do", true).met(true, List.of()).build();
        passes("lol i do what, u agreeing to marry me again or what", s);
    }

    // ── b101: answers contradicting the bot's own state ──

    @Test
    void b101LevelAndLocationAndGrindingThatContradictTheBotAreRejected() {
        StateOfMind.Snapshot s = inHenesys().build();
        rejected(StateOfMind.Reason.LEVEL, "must be nice, im stuck at 47 grinding sleepy dungeon", s);
        rejected(StateOfMind.Reason.LOCATION, "just grinding in sleepy dungeon", s);
        assertFalse(StateOfMind.check("still grinding hene, sniping golems, ur a chiefbandit tho u must be", s).ok());
        rejected(StateOfMind.Reason.ACTIVITY, "still grinding hene tbh", s);
        assertFalse(StateOfMind.check("sup, just grinding golems tbh", s).ok());
        rejected(StateOfMind.Reason.LOCATION, "im in perion rn", s);
    }

    @Test
    void theSameLinesPassWhereTheyAreTrue() {
        StateOfMind.Snapshot field = StateOfMind.builder().bot("narutoo", 47, "hunter")
                .here(new StateOfMind.Place(GOLEM_TEMPLE, "Golem Temple Entrance", HENESYS), List.of("Stone Golem"))
                .doing("grinding", null).player("Josh", 75, "chief bandit", false).build();
        passes("still grinding near hene, sniping golems", field);
        passes("sup, just grinding golems tbh", field);
        passes("im 47 lol", field);
        passes("ya im almost 50 finally", field);
    }

    @Test
    void aPlanToGoSomewhereIsNotAClaimToBeThere() {
        StateOfMind.Snapshot s = inHenesys().doing("hanging out in town", "grind at Golem Temple")
                .heading(new StateOfMind.Place(GOLEM_TEMPLE, "Golem Temple Entrance", HENESYS), List.of("Stone Golem"))
                .build();
        passes("lol ya just chillin in hene rn, golems later", s);
        passes("gonna grind stone golems after i sort my pots", s);
    }

    // ── b102: the haggling loop (Zh3lmSader) ──

    @Test
    void b102RepeatingThePriceOverAnOpenOfferIsRejected() {
        StateOfMind.Snapshot offer = onHuntingGround(true).offer(new StateOfMind.Offer("work gloves", 9_520, 300_000))
                .build();
        rejected(StateOfMind.Reason.PRICE, "lol there it is again, 300k for work gloves, 9,520 and u open trade", offer);
        rejected(StateOfMind.Reason.PRICE, "i got work gloves, 9,520 and its yours. trade me", offer);
        StateOfMind.Snapshot no = onHuntingGround(true).offer(new StateOfMind.Offer("work gloves", 9_520, -1)).build();
        rejected(StateOfMind.Reason.PRICE, "i got work gloves 9,520", no);
    }

    @Test
    void answersToAnOpenOfferPass() {
        StateOfMind.Snapshot offer = onHuntingGround(true).offer(new StateOfMind.Offer("work gloves", 9_520, 300_000))
                .build();
        passes("lol its only 9,520, u dont need to pay 300k. trade me", offer);
        passes("ok 9,520 works, trade me", offer);
        StateOfMind.Snapshot no = onHuntingGround(true).offer(new StateOfMind.Offer("work gloves", 9_520, -1)).build();
        passes("ok ok, 8,000 for work gloves then? trade me", no);
        passes("9,520 is already my lowest for work gloves, its a fair price", no);
        passes("i got work gloves 9,520", onHuntingGround(true).build()); // no offer open: a plain quote
    }

    // ── false positives: ordinary chat passes ──

    @Test
    void ordinaryChatPasses() {
        StateOfMind.Snapshot town = inHenesys().build();
        for (String line : List.of(
                "lol ya im sleepy", "just chillin in hene", "gonna check fm, idk might come back",
                "wanna do kpq later?", "ya im 38 now", "ya im almost 40 finally", "orbis is nice tbh",
                "say that again?", "nope not doin that again", "grinding is so boring", "hows ur day going",
                "brb pots", "henesys is so packed rn", "im 5 mins away", "ya the boss is hard", "will do",
                "lol ur so lucky", "killing time lol", "im at 50% exp", "perion is for warriors",
                "u still there?", "nice orange mushroom cap", "idk ask in fm", "hi", "lol ya pigs drop nothing",
                "go to ellinia for 2nd job", "Not right now, maybe after I hit 70.")) {
            passes(line, town);
        }
        StateOfMind.Snapshot field = onHuntingGround(false).build();
        passes("these slimes drop nothing lol", field);
        passes("just chillin in hene", field);
        passes("ya im 22 lol", field);
        passes("hey whats up", field);
        passes("nope not doin that again, just grindin", field);
    }

    @Test
    void aFreeMarketRoomIsTheFreeMarket() {
        StateOfMind.Snapshot s = StateOfMind.builder().bot("Spirit8", 40, "page")
                .here(new StateOfMind.Place(910000004, "Free Market <4>", 910000004), List.of())
                .player("Josh", 75, "chief bandit", false).build();
        passes("just chillin in fm rn", s);
        rejected(StateOfMind.Reason.LOCATION, "im in perion rn", s);
    }

    @Test
    void withoutAKnownMapOnlyFamiliarityLevelAndPriceAreChecked() {
        StateOfMind.Snapshot s = StateOfMind.builder().bot("Walkr", 35, "beginner").player("Josh", 35, "beginner",
                false).build();
        passes("lol ya ant tunnel is ok", s);
        rejected(StateOfMind.Reason.FAMILIAR, "yo again", s);
    }

    @Test
    void aReplyToAnotherBotSkipsFamiliarity() {
        StateOfMind.Snapshot s = StateOfMind.builder().bot("A", 35, "beginner").player("B", 30, "beginner", true)
                .build();
        passes("yo again lol", s);
    }

    // ── the prompt and the safe line ──

    @Test
    void thePromptCarriesTheSnapshotAndTheRule() {
        StateOfMind.Snapshot s = inHenesys().doing("hanging out in town", "grind at Golem Temple")
                .heading(new StateOfMind.Place(GOLEM_TEMPLE, "Golem Temple Entrance", HENESYS), List.of("Stone Golem"))
                .knows(new StateOfMind.Place(ANT_TUNNEL_II, "Ant Tunnel", SLEEPYWOOD), List.of("Horny Mushroom"))
                .offer(new StateOfMind.Offer("work gloves", 9_520, 300_000)).build();
        String p = StateOfMind.prompt(s);
        assertTrue(p.contains("Only claim places, monsters, levels, history and prices that are in here"), p);
        assertTrue(p.contains("level 38 hunter, on Henesys"), p);
        assertTrue(p.contains("no monsters here"), p);
        assertTrue(p.contains("grind at Golem Temple (Golem Temple Entrance)"), p);
        assertTrue(p.contains("Ant Tunnel") && p.contains("Horny Mushroom") && p.contains("Stone Golem"), p);
        assertTrue(p.contains("Josh: level 75 chief bandit"), p);
        assertTrue(p.contains("NEVER met"), p);
        assertTrue(p.contains("they offered") && p.contains("never just repeat your price"), p);
        String field = StateOfMind.prompt(onHuntingGround(true).build());
        assertTrue(field.contains("monsters here: Snail, Slime") && field.contains("asked me for a party once"), field);
    }

    @Test
    void theSafeLineAlwaysPasses() {
        Random rng = new Random(7);
        List<StateOfMind.Snapshot> all = List.of(inHenesys().build(), onHuntingGround(false).build(),
                onHuntingGround(true).offer(new StateOfMind.Offer("work gloves", 9_520, 300_000)).build(),
                onHuntingGround(true).offer(new StateOfMind.Offer("work gloves", 9_520, 5_000)).build(),
                onHuntingGround(true).offer(new StateOfMind.Offer("work gloves", 9_520, -1)).build());
        for (StateOfMind.Snapshot s : all) {
            for (String asked : List.of("wat u doin", "hey", "where r u", "lol")) {
                String line = StateOfMind.safeLine(s, asked, rng);
                assertTrue(StateOfMind.check(line, s).ok(), line);
            }
        }
        assertEquals("just chillin in henesys rn", StateOfMind.safeLine(inHenesys().build(), "wat u doin", rng));
    }

    @Test
    void rejectsAreCounted() {
        StateOfMind.Snapshot s = inHenesys().build();
        StateOfMind.judge("ant tunnel, the deep part with the wild boars", s, "a -> b", "llm");
        StateOfMind.judge("hi", s, "a -> b", "llm");
        Map<String, Object> st = StateOfMind.stats();
        assertEquals(2L, st.get("checked"));
        assertEquals(1L, st.get("rejected"));
        assertEquals(Map.of("place", 1L), st.get("byReason"));
    }

    /** Ticket 156's latency budget: the check is microseconds, next to a model reply's seconds. */
    @Test
    void theCheckIsCheap() {
        StateOfMind.Snapshot s = inHenesys().knows(new StateOfMind.Place(ANT_TUNNEL_II, "Ant Tunnel", SLEEPYWOOD),
                List.of("Horny Mushroom")).line("hey whats up", true).build();
        String line = "Yo again, just chillin in hene, you still on that grind to 70?";
        for (int i = 0; i < 2_000; i++) {
            StateOfMind.check(line, s);
        }
        long t0 = System.nanoTime();
        int n = 5_000;
        for (int i = 0; i < n; i++) {
            StateOfMind.check(i % 2 == 0 ? line : "lol ya ant tunnel has horny mushrooms", s);
        }
        long perCheckMicros = (System.nanoTime() - t0) / n / 1000;
        System.out.println("StateOfMind.check: ~" + perCheckMicros + " us per reply");
        assertTrue(perCheckMicros < 2_000, perCheckMicros + " us"); // generous for slow CI
    }
}
