package soloMapling.autoqa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 271 stage 7: the social checks - their wiring, and every verdict rule with a pass and a fail. */
class Probes164SocialTest {

    static final List<String> NEW = List.of("t-ms-1", "t-sm-1", "t-sa-1");

    @AfterEach
    void reset() {
        Probes164Social.resetPilotForTest();
    }

    // ── wiring ──

    @Test
    void theNewChecksAreAutomatedNightlyLaneOnlyAndUseOnlyKnownProbes() {
        Pattern probe = Pattern.compile("^(?:probe|fixture) (\\w+)");
        for (String id : NEW) {
            Check c = AutoQaCatalog.find(id);
            assertNotNull(c, id);
            assertEquals(Check.Kind.AUTO, c.kind(), id);
            assertTrue(c.nightly(), id + " stages nothing lasting");
            assertTrue(c.laneOnly(), id + " reads this server's own counters, so it holds no lane");
            int probes = 0;
            for (Check.Expect e : c.expects()) {
                Matcher m = probe.matcher(e.describe());
                if (m.find()) {
                    assertTrue(Probes164Social.NAMES.contains(m.group(1)), id + " probes unknown " + m.group(1));
                    probes++;
                }
            }
            assertEquals(1, probes, id);
            assertFalse(AutoQaRelease.allowList().containsKey(id), id + " may not SKIP in a release run");
        }
        assertNull(Probes164Social.probe("noSuchProbe", null));
        for (String n : Probes164Social.NAMES) {
            assertNull(Probes160.probe(n, null), n + " is not also a 1.6.0 probe name");
            assertNull(Probes162.probe(n, null), n + " is not also a 1.6.2 probe name");
            assertNull(Probes164.probe(n, null), n + " is not also a ticket-164 probe name");
        }
    }

    @Test
    void thePilotCheckWaitsForThePilotBeforeItJoinsTheCatalogue() {
        // t-sp-1 is the 1.6.2 "bots spread out" check, so the pilot took t-pi-1. It is registered only once
        // SocialPilot exists: until then it could only SKIP, and a release run FAILs on any SKIP that is not on the
        // client-only allow-list (ticket 164 b222). This test is the tripwire for stage 8 forgetting to add it.
        Check spread = AutoQaCatalog.find("t-sp-1");
        assertNotNull(spread);
        assertTrue(spread.title().contains("spread"), spread.title());
        if (Probes164Social.pilotPresent()) {
            Check pilot = AutoQaCatalog.find("t-pi-1");
            assertNotNull(pilot, "SocialPilot exists, so t-pi-1 must be registered in AutoQaCatalog");
            assertTrue(Probes164Social.NAMES.contains("socialPilot"));
        } else {
            assertNull(AutoQaCatalog.find("t-pi-1"), "t-pi-1 waits for the pilot class");
        }
    }

    @Test
    void everyProbeAnswersAVerdictOnAColdServerInsteadOfThrowing() {
        // no brain has run and no pilot class exists: both must SKIP and name why. The other two read counters other
        // tests in the same JVM may have touched, so only "it answered" is asserted for them.
        for (String what : List.of("modeSwitch", "socialPilot")) {
            List<String> lines = Probes164Social.probe(what, null);
            assertNotNull(lines, what);
            assertTrue(lines.get(0).startsWith("SKIP "), what + " said " + lines.get(0));
        }
        for (String what : List.of("socialMind", "socialActs")) {
            List<String> lines = Probes164Social.probe(what, null);
            assertNotNull(lines, what);
            assertFalse(lines.isEmpty(), what);
            String first = lines.get(0);
            assertTrue(first.startsWith("PASS ") || first.startsWith("SKIP ") || first.startsWith("FAIL "),
                    what + " said " + first);
        }
    }

    // ── t-ms-1 ──

    static Map<String, Integer> census(int training, int social, int town) {
        Map<String, Integer> m = new TreeMap<>();
        m.put("TRAINING", training);
        m.put("SOCIAL", social);
        m.put("TOWN", town);
        return m;
    }

    static Map<String, Long> modeStats(long out, long back, long refused) {
        Map<String, Long> s = new TreeMap<>();
        s.put("evaluations", 500L);
        s.put("switches", out + back);
        if (out > 0) s.put("switch TRAINING>SOCIAL", out);
        if (back > 0) s.put("switch SOCIAL>TRAINING", back);
        if (refused > 0) s.put("refused swap refused", refused);
        return s;
    }

    @Test
    void modeSwitchSkipsWhileThePopulationOrTheHistoryIsTooSmall() {
        assertEquals("only 3 roster bots have been through a mode decision (need 8 online)",
                Probes164Social.modeSwitchSkip(10_000, 3));
        assertTrue(Probes164Social.modeSwitchSkip(239, 8).startsWith("the brain has made 239 mode decisions"));
        assertNull(Probes164Social.modeSwitchSkip(240, 8));
        assertNull(Probes164Social.modeSwitchSkip(9_000, 20));
    }

    @Test
    void modeSwitchPassesAPopulationInAllThreeModesSwitchingBothWays() {
        assertNull(Probes164Social.modeSwitchProblem(census(8, 5, 3), modeStats(7, 5, 0), 2));
    }

    @Test
    void modeSwitchFailsAnEmptyMode() {
        assertEquals("no bot is in social mode (census {SOCIAL=0, TOWN=4, TRAINING=12})",
                Probes164Social.modeSwitchProblem(census(12, 0, 4), modeStats(7, 5, 0), 1));
    }

    @Test
    void modeSwitchFailsAOneWayFlow() {
        assertTrue(Probes164Social.modeSwitchProblem(census(8, 5, 3), modeStats(7, 0, 0), 1)
                .startsWith("no bot has come back to training mode"));
        assertTrue(Probes164Social.modeSwitchProblem(census(8, 5, 3), modeStats(0, 5, 0), 1)
                .startsWith("no bot has left training"));
        assertTrue(Probes164Social.modeSwitchProblem(census(8, 5, 3), modeStats(0, 0, 0), 1)
                .startsWith("only 0 mode switch(es)"));
    }

    @Test
    void modeSwitchFailsTooManyRefusedSwapsOrATooFastBot() {
        // 5% of 20 switches is 1: one refusal is allowed, two are not
        assertNull(Probes164Social.modeSwitchProblem(census(8, 5, 3), modeStats(10, 10, 1), 1));
        assertEquals("the type swap was refused 2 time(s) in 20 switch(es) (cap 5%)",
                Probes164Social.modeSwitchProblem(census(8, 5, 3), modeStats(10, 10, 2), 1));
        assertEquals("a bot switched 4 times in the last hour (cap 3)",
                Probes164Social.modeSwitchProblem(census(8, 5, 3), modeStats(10, 10, 0), 4));
    }

    // ── t-sm-1 ──

    @Test
    void socialMindSkipsUntilThereAreDecisionsAndLoadedMinds() {
        assertEquals("the social layer has not made a decision yet", Probes164Social.socialMindSkip(6, 0));
        assertEquals("only 4 online bot(s) have a loaded mind (need 5)", Probes164Social.socialMindSkip(4, 90));
        assertNull(Probes164Social.socialMindSkip(5, 1));
    }

    static Probes164Social.MindRead mind(int id, double mood, boolean feels, boolean needMoved) {
        return new Probes164Social.MindRead(id, mood, feels, needMoved, true);
    }

    @Test
    void socialMindPassesWhenMoodsAreInRangeAndMindsHaveMoved() {
        List<Probes164Social.MindRead> sample = List.of(mind(1, 0.2, true, false), mind(2, -0.3, false, true),
                mind(3, 0.0, false, false), mind(4, 0.9, true, false), mind(5, -1.0, false, false));
        assertNull(Probes164Social.socialMindProblem(sample));
    }

    @Test
    void socialMindFailsANaNAnOutOfRangeMoodOrAStillPristineSample() {
        List<Probes164Social.MindRead> nan = List.of(
                new Probes164Social.MindRead(7, 0.1, true, true, false),
                mind(8, 0.1, true, true));
        assertEquals("bot 7 has a NaN or infinite value in its mind", Probes164Social.socialMindProblem(nan));
        assertEquals("bot 9 has mood 1.5 outside [-1, 1]",
                Probes164Social.socialMindProblem(List.of(mind(9, 1.5, true, true))));
        assertEquals("only 1 of 5 loaded mind(s) have moved off their seed values (need 2)",
                Probes164Social.socialMindProblem(List.of(mind(1, 0.1, true, false), mind(2, 0.1, false, false),
                        mind(3, 0.1, false, false), mind(4, 0.1, false, false), mind(5, 0.1, false, false))));
    }

    // ── t-sa-1 ──

    @Test
    void socialActsSkipsUntilEnoughActsHaveBeenAttempted() {
        assertTrue(Probes164Social.socialActsSkip(5, 4).startsWith("only 9 social act(s) have been attempted"));
        assertNull(Probes164Social.socialActsSkip(18, 2));
    }

    static Map<String, Long> actCounters(String... pairs) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], Long.parseLong(pairs[i + 1]));
        return m;
    }

    static Map<String, Long> budgetCounters(long allowed, long denied, long turns) {
        Map<String, Long> m = new TreeMap<>();
        m.put("allowed", allowed);
        m.put("denied", denied);
        m.put("denied turn-limit", turns);
        m.put("denied pair-cooldown", denied - turns);
        return m;
    }

    static Map<String, Long> goodActs() {
        return actCounters("GREET.ok", "12", "CONVERSE.ok", "30", "GIVE_GIFT.ok", "3", "GREET.budget", "1",
                "decision.tick", "900");
    }

    @Test
    void socialActsPassesThreeKindsAndAWorkingBudget() {
        assertNull(Probes164Social.socialActsProblem(goodActs(), budgetCounters(250, 12, 3)));
    }

    @Test
    void socialActsFailsTooFewKindsOrTooManyErrors() {
        Map<String, Long> twoKinds = actCounters("GREET.ok", "12", "CONVERSE.ok", "30", "GREET.budget", "1");
        assertTrue(Probes164Social.socialActsProblem(twoKinds, budgetCounters(250, 12, 3))
                .startsWith("only 2 kind(s) of social act have landed"));
        Map<String, Long> erroring = actCounters("GREET.ok", "12", "CONVERSE.ok", "30", "GIVE_GIFT.ok", "3",
                "GIVE_GIFT.error", "4");
        assertEquals("4 social act(s) ended in an error against 45 that landed (cap 5%)",
                Probes164Social.socialActsProblem(erroring, budgetCounters(250, 12, 3)));
    }

    @Test
    void socialActsFailsABudgetThatRefusesMostActsOrACapThatNeverBites() {
        assertTrue(Probes164Social.socialActsProblem(goodActs(), budgetCounters(20, 40, 3))
                .startsWith("the budget refused 40 of 60 act(s) (cap 50%)"));
        assertEquals("no pair hit the turn limit in 300 allowed act(s): the cap is not being applied",
                Probes164Social.socialActsProblem(goodActs(), budgetCounters(300, 20, 0)));
        assertTrue(Probes164Social.socialActsProblem(goodActs(), budgetCounters(20, 20, 15))
                .contains("pairs are stuck in a capped exchange"));
    }

    @Test
    void socialActsFailsAKindThatIsAlmostAlwaysRefused() {
        Map<String, Long> stuck = actCounters("GREET.ok", "12", "CONVERSE.ok", "30", "GIVE_GIFT.ok", "3",
                "INVITE.refused", "14");
        assertEquals("INVITE was refused or held back 14 time(s) in 14 attempt(s): a staging problem, not a working act",
                Probes164Social.socialActsProblem(stuck, budgetCounters(250, 12, 3)));
    }

    // ── t-pi-1 ──

    @Test
    void thePilotCheckSkipsUntilThePilotClassExists() {
        Probes164Social.usePilotForTest(() -> null, () -> null);
        List<String> out = Probes164Social.probe("socialPilot", null);
        assertEquals(1, out.size());
        assertEquals("SKIP pilot not built (no soloMapling.ArtificialPlayer.BotSocialSystem.SocialPilot)", out.get(0));
    }

    @Test
    void thePilotCheckSkipsWhenThePilotIsOff() {
        Probes164Social.usePilotForTest(() -> false, () -> Map.of("bots", 20L));
        assertEquals(List.of("SKIP the pilot is off"), Probes164Social.probe("socialPilot", null));
    }

    @Test
    void thePilotCheckPassesOnMovingCounters() {
        Map<String, Long> stats = new TreeMap<>();
        stats.put("bots", 20L);
        stats.put("greetings", 4L);
        stats.put("routines", 11L);
        stats.put("online", 5L);
        Probes164Social.usePilotForTest(() -> true, () -> stats);
        List<String> out = Probes164Social.probe("socialPilot", null);
        assertEquals("PASS the pilot reports 4 counter(s)", out.get(0));
        assertTrue(out.contains("greetings 4"), out.toString());
    }

    @Test
    void thePilotVerdictFailsEmptyOrNegativeCounters() {
        assertEquals("the pilot reports no counters at all", Probes164Social.pilotProblem(Map.of()));
        assertEquals("every pilot counter is still 0: the pilot has not done anything",
                Probes164Social.pilotProblem(Map.of("bots", 0L, "greetings", 0L)));
        assertEquals("the pilot's 'greetings' counter is negative (-1)",
                Probes164Social.pilotProblem(Map.of("greetings", -1L)));
        assertNull(Probes164Social.pilotProblem(Map.of("bots", 20L)));
    }
}
