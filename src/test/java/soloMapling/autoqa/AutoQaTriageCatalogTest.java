package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The catalogue fixes from the rc6 full-run triage (2026-10-23): each guards a failure found in that run. */
class AutoQaTriageCatalogTest {

    static List<String> steps(String id) {
        return AutoQaCatalog.find(id).steps().stream().map(Check.Step::describe).toList();
    }

    static int index(List<String> steps, String part) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).contains(part)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void romanceChecksStartWithNobodyEngaged() {
        // t-mr-1 leaves both pretend players engaged; every later proposal was refused as "already engaged"
        for (String id : List.of("t-rm-1", "t-rm-2", "t-rm-4")) {
            assertTrue(steps(id).get(0).startsWith("fixture notEngaged"), id);
        }
    }

    @Test
    void rm3FlamesTheExBeforeStagingTwoNewPartners() {
        List<String> s = steps("t-rm-3");
        int flame = index(s, "!qa do flame"), second = index(s, "!qa do second"), confront = index(s, "!qa do confront");
        assertTrue(flame > index(s, "!qa do breakup") && flame < second, "flame works on the latest staged bot: the ex");
        assertEquals(2, s.stream().filter(x -> x.contains("!qa do second")).count(), "two live partners to confront");
        assertTrue(confront > second);
    }

    @Test
    void rv4AndPb1StartFromHenesysHuntingGroundOne() {
        for (String id : List.of("t-rv-4", "t-pb-1")) {
            List<String> s = steps(id);
            int warp = index(s, "!warp " + AutoQaCatalog.HHG1), run = index(s, "!qa run");
            assertTrue(warp >= 0 && warp < run, id + " warps first");
        }
    }

    @Test
    void cb3LetsTheGrindersStartBeforeDrainingPots() {
        List<String> s = steps("t-cb-3");
        int wait = index(s, "wait 30 s"), lowpots = index(s, "!qa do lowpots");
        assertTrue(wait >= 0 && wait < lowpots);
        assertTrue(s.stream().anyMatch(x -> x.contains("HABITS POT ({staged})")), "waits for a staged bot's pot");
    }

    @Test
    void bb1MakesTheBuddyAcceptCertainBeforeItAsks() {
        // rc6 full run 2026-10-23, 2 of 2: a stranger bot's yes is a 20% roll (0.7-1.3x by its social trait), so the
        // request sat "pending" for the whole 200 s wait; a silent no also blocks the pair for the rest of the day
        List<String> s = steps("t-bb-1");
        int certain = index(s, "fixture certainBuddies"), add = index(s, "buddy add");
        assertTrue(certain >= 0, "t-bb-1 does not make the accept certain: " + s);
        assertEquals("fixture certainBuddies 5", s.get(certain), "five minutes: the bot decides within 10-180 s");
        assertTrue(certain < add, "the accept has to be certain before the request goes out: " + s);
        assertTrue(index(s, "p1 says '!warp 100000000'") >= 0 && index(s, "p1 says '!warp 100000000'") < certain,
                "the bot is picked after the warp: " + s);
    }

    static AutoQaLine attack(long t, String bot, int busy, int gap, boolean killed) {
        return new AutoQaLine(t, "qa", "ATTACK", bot + " HERO skill 1121008 busy " + busy + "ms gap " + gap + "ms"
                + (killed ? " killed" : ""));
    }

    @Test
    void aWaitAfterAKillIsNotAHitch() {
        // t-at-1: 329 of 484 "slow" swings, every one of them right after a one-hit kill
        List<AutoQaLine> lines = List.of(attack(1, "A", 750, 9000, true), attack(2, "A", 750, 5000, true),
                attack(3, "A", 750, 4500, false), attack(4, "A", 750, 800, false), attack(5, "A", 750, 760, false));
        assertNull(AutoQaCatalog.pacing(lines, true));
    }

    @Test
    void machineGunAndRealHitchesStillFail() {
        List<AutoQaLine> fast = List.of(attack(1, "A", 750, 9000, false), attack(2, "A", 750, 300, false));
        assertNotNull(AutoQaCatalog.pacing(fast, false));
        List<AutoQaLine> hitch = List.of(attack(1, "A", 750, 800, false), attack(2, "A", 750, 4500, false));
        assertNotNull(AutoQaCatalog.pacing(hitch, true));
        assertNull(AutoQaCatalog.pacing(hitch, false), "t-at-1 only checks machine-gun swings");
    }

    @Test
    void aWaitWithNothingInReachIsNotAHitch() {
        // t-bk-1 (2026-10-23 22:00 UTC): RNGslaveZ hit a Red Snail, then logged "not sent: no targetable mobs within
        // 700px" every 400 ms for 5 s until one walked back: "1 of 1 swings waited over 3 s" was that wait
        List<AutoQaLine> lines = List.of(attack(1, "A", 660, 800, false),
                new AutoQaLine(2, "qa", "ATTACK", "A FP_ARCHMAGE skill 2121007 not sent: no targetable mobs within 700px"),
                attack(3, "A", 240, 5063, true));
        assertNull(AutoQaCatalog.pacing(lines, true));
        // another bot's empty reach does not excuse this one's hitch
        List<AutoQaLine> other = List.of(attack(1, "A", 660, 800, false),
                new AutoQaLine(2, "qa", "ATTACK", "B BISHOP skill 2321007 not sent: no targetable mobs within 700px"),
                attack(3, "A", 660, 5063, false));
        assertNotNull(AutoQaCatalog.pacing(other, true));
    }

    @Test
    void rv2FightsBeforeTheKsWarIsCalled() {
        List<String> s = steps("t-rv-2");
        int fight = index(s, "!qa do fight"), war = index(s, "!qa do kswar");
        assertTrue(fight >= 0 && fight < war, s.toString());
    }

    @Test
    void cb4StepsOntoABotAndFightsThenWantsAYield() {
        List<String> s = steps("t-cb-4");
        int step = index(s, "!qa do stepon"), fight = index(s, "!qa do fight");
        assertTrue(step > index(s, "wait 30 s") && fight > step, s.toString());
        String all = AutoQaCatalog.find("t-cb-4").expects().stream().map(Check.Expect::describe).toList().toString();
        assertTrue(all.contains("YIELD|CROWDED"), all);
    }

    @Test
    void gu2ExpectsTheSoftHalfBotsLimit() {
        String all = AutoQaCatalog.find("t-gu-2").expects().stream().map(Check.Expect::describe).toList().toString();
        assertTrue(all.contains("bots 1/2"), all);
    }
}
