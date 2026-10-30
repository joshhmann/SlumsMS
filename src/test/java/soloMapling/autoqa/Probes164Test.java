package soloMapling.autoqa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConvo.ConvoEntities;
import soloMapling.ArtificialPlayer.BotConvo.StateOfMind;
import soloMapling.ArtificialPlayer.GCMoveSystem.NavTrapQa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 164 b224: the chat-grounding (156) and nutnnut-tools (157) checks; their wiring and verdict rules. */
class Probes164Test {

    static final List<String> NEW = List.of("t-gr-1", "t-gr-2", "t-nv-2", "t-nv-3");
    static final int HENESYS = 100000000, HHG1 = 104040000;

    @BeforeEach
    void names() {
        ConvoEntities.useForTest(new ConvoEntities.Names() {
            public Map<String, Integer> maps() {
                return Map.of("henesys hunting ground i", HHG1);
            }

            public Map<String, Integer> mobs() {
                return Map.of("snail", 100100);
            }

            public Map<String, Integer> items() {
                return Map.of();
            }
        });
        StateOfMind.useRegions(id -> id == HHG1 ? HENESYS : -1);
    }

    @AfterEach
    void reset() {
        ConvoEntities.useForTest(null);
        StateOfMind.useRegions(null);
    }

    @Test
    void theNewChecksAreAutomatedNightlyAndUseOnlyKnownProbes() {
        Pattern probe = Pattern.compile("^(?:probe|fixture) (\\w+)");
        for (String id : NEW) {
            Check c = AutoQaCatalog.find(id);
            assertNotNull(c, id);
            assertEquals(Check.Kind.AUTO, c.kind(), id);
            assertTrue(c.nightly(), id + " stages nothing lasting");
            int probes = 0;
            for (Check.Expect e : c.expects()) {
                Matcher m = probe.matcher(e.describe());
                if (m.find()) {
                    assertTrue(Probes164.NAMES.contains(m.group(1)), id + " probes unknown " + m.group(1));
                    probes++;
                }
            }
            assertEquals(1, probes, id);
            assertFalse(AutoQaRelease.allowList().containsKey(id), id + " may not SKIP in a release run");
        }
        assertNull(Probes164.probe("noSuchProbe", null));
        for (String n : Probes164.NAMES) {
            assertNull(Probes160.probe(n, null), n + " is not also a 1.6.0 probe name");
            assertNull(Probes162.probe(n, null), n + " is not also a 1.6.2 probe name");
        }
    }

    static StateOfMind.Snapshot bot(int level, boolean met) {
        return StateOfMind.builder().bot("Mushy343", level, "magician")
                .here(new StateOfMind.Place(HHG1, "Henesys Hunting Ground I", HENESYS), List.of("Snail"))
                .doing("grinding", null).player("AutoQaOne", 70, "hermit", false)
                .met(met, List.of()).build();
    }

    private static List<StateOfMind.Verdict> judge(List<Probes164.Claim> claims, StateOfMind.Snapshot s) {
        List<StateOfMind.Verdict> out = new ArrayList<>();
        for (Probes164.Claim c : claims) {
            out.add(StateOfMind.check(c.text(), s));
        }
        return out;
    }

    @Test
    void theClaimsAreJudgedAsTheProbeExpectsByTheRealChecker() {
        StateOfMind.Snapshot s = bot(35, false);
        List<Probes164.Claim> claims = Probes164.claims(35, HENESYS, false);
        assertEquals(5, claims.size());
        assertEquals(List.of(), Probes164.claimProblems(claims, judge(claims, s)));
        assertEquals(StateOfMind.Reason.LEVEL, StateOfMind.check("im lvl 72 rn", s).reason());
        assertEquals(StateOfMind.Reason.LOCATION, StateOfMind.check("im grinding in leafre rn", s).reason());
        assertEquals(StateOfMind.Reason.FAMILIAR, StateOfMind.check("hey again! good to see u", s).reason());
        // a bot that has met the player is not held to "again"; a bot in Leafre is told about Mu Lung instead
        assertEquals(4, Probes164.claims(35, HENESYS, true).size());
        assertTrue(Probes164.claims(35, 240000000, false).stream().anyMatch(c -> c.text().contains("mu lung")));
    }

    @Test
    void aCheckerThatLetsAnUntrueLineThroughOrRejectsATrueOneIsCaught() {
        List<Probes164.Claim> claims = List.of(new Probes164.Claim("im lvl 35 rn", true),
                new Probes164.Claim("im lvl 72 rn", false));
        List<String> bad = Probes164.claimProblems(claims, List.of(new StateOfMind.Verdict(StateOfMind.Reason.LEVEL,
                "x"), StateOfMind.Verdict.OK));
        assertEquals(2, bad.size(), bad.toString());
        assertTrue(bad.get(0).startsWith("rejected a true line"));
        assertTrue(bad.get(1).startsWith("let an untrue line through"));
    }

    @Test
    void theSnapshotMustHoldTheBotsAndThePlayersRealFacts() {
        StateOfMind.Snapshot s = bot(35, false);
        assertEquals(List.of(), Probes164.truthProblems(s, "Mushy343", 35, HHG1, "AutoQaOne", 70));
        List<String> bad = Probes164.truthProblems(s, "Mushy343", 36, HENESYS, "AutoQaOne", 71);
        assertEquals(3, bad.size(), bad.toString());
        assertEquals(List.of("no snapshot for the bot"), Probes164.truthProblems(null, "x", 1, 1, "y", 1));
    }

    @Test
    void theLiveReplyMustStateTheRealLevel() {
        assertNull(Probes164.levelProblem("35", 35));
        assertNull(Probes164.levelProblem("im 35 lol, almost 36", 35));
        assertNotNull(Probes164.levelProblem("lvl 70 why", 35));
        assertNotNull(Probes164.levelProblem("why u asking", 35));
        assertNotNull(Probes164.levelProblem("135", 35), "135 is not 35");
        assertEquals("no reply", Probes164.levelProblem(null, 35));
    }

    @Test
    void theForcedDraftMustBeRejectedRetriedAndNeverSaid() {
        String untrue = "lol im lvl 75 grinding in leafre, good to see u again";
        assertEquals(List.of(), Probes164.retryProblems(true, "doing ok just grinding snails", untrue, 1, 1, 1));
        assertTrue(Probes164.retryProblems(false, "x", untrue, 0, 0, 0).get(0).contains("never reached the chat model"));
        List<String> said = Probes164.retryProblems(true, untrue, untrue, 0, 0, 0);
        assertEquals(4, said.size(), said.toString());
        assertTrue(said.get(0).startsWith("the untrue draft was said"));
        assertTrue(Probes164.retryProblems(true, null, untrue, 1, 1, 1).get(0).contains("no reply"));
    }

    @Test
    void theTrapVerdictFailsOnlyOnANewTrap() {
        NavTrapQa.Traps t = new NavTrapQa.Traps(104000000, 1471, 986, new TreeSet<>(Set.of(1, 2, 3)));
        List<String> ok = Probes164.trapVerdict(t, Map.of(1, "", 2, "", 3, "", 4, ""));
        assertTrue(ok.get(0).startsWith("PASS "), ok.toString());
        assertTrue(ok.stream().anyMatch(l -> l.startsWith("1 baseline trap(s) now have a way back") && l.contains("[4]")),
                ok.toString());
        List<String> fresh = Probes164.trapVerdict(t, Map.of(1, "", 2, ""));
        assertTrue(fresh.get(0).startsWith("FAIL 1 new one-way trap(s), not in the baseline: [3]"), fresh.toString());
        List<String> empty = Probes164.trapVerdict(new NavTrapQa.Traps(104000000, 3, 3, new TreeSet<>()), Map.of(1, ""));
        assertTrue(empty.get(0).contains("the world graph did not load"), empty.toString());
        List<String> noBaseline = Probes164.trapVerdict(t, Map.of());
        assertTrue(noBaseline.get(0).contains("baseline (soloMapling/nav-trap-maps.txt) is missing"), noBaseline.toString());
    }

    @Test
    void theInspectViewMustBeTheBot() {
        Map<String, Object> view = Map.of("ok", true, "cid", 1000000123, "name", "Mushy343", "level", 30, "map", HHG1);
        assertEquals(List.of(), Probes164.inspectProblems(view, 1000000123, "Mushy343", 30, HHG1));
        assertEquals(1, Probes164.inspectProblems(view, 1000000123, "Mushy343", 31, HHG1).size());
        assertEquals(1, Probes164.inspectProblems(null, 1000000123, "Mushy343", 30, HHG1).size());
    }
}
