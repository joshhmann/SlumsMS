package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Stage;
import soloMapling.mechanics.MechanicsModel.Tier;

/**
 * Ticket 130 (owner decision 2026-11-02, "pause for revive"): a human death in a boss expedition holds the run instead of
 * ending it. The scripts unregister a dead player so it can revive and walk back in
 * ({@code scripts/event/ZakumBattle.js:140-160}, {@code scripts/event/HorntailBattle.js:140-160}); the run reads that as
 * a member leaving, and until now that was the abort. {@link RevivePause} is the switch - <b>off by default</b> since
 * round 9, so every test that wants the rule turns it on explicitly - with five minutes of window, and it is a boss rule
 * only.
 */
class RevivePauseTest {

    private static final long START = 1_000_000L;
    private static final int HUMAN = 1;

    @BeforeEach
    void turnTheRuleOn() {
        System.setProperty(RevivePause.FLAG, "true");
    }

    @AfterEach
    void turnItOffAgain() {
        System.clearProperty(RevivePause.FLAG);
        System.clearProperty(RevivePause.WINDOW_PROPERTY);
    }

    /** Round 9: the rule is a switch like any other, so its default is OFF and the release conf carries the on line. */
    @Test
    void theRevivePauseIsOffUnlessAServerTurnsItOn() {
        System.clearProperty(RevivePause.FLAG);
        assertFalse(RevivePause.on(), "-Dslums.bossfight.revivepause is off by default");
        System.setProperty(RevivePause.FLAG, "true");
        assertTrue(RevivePause.on());
        System.setProperty(RevivePause.FLAG, "FALSE");
        assertFalse(RevivePause.on(), "and any spelling of false keeps it off");
    }

    /** The shipped Horntail row: kind BOSS, three stages, six members minimum. */
    private static Content horntail() {
        Content content = MechanicsRuns.contentFor("HorntailBattle");
        assertNotNull(content, "horntail.yaml must be listed in content/index.txt");
        return content;
    }

    private static List<RunMember> party() {
        List<RunMember> out = new ArrayList<>();
        out.add(new RunMember(HUMAN, true, false, MechanicsPersona.PERFECT));
        for (int id = 2; id <= 6; id++) {
            out.add(new RunMember(id, false, true, MechanicsPersona.PERFECT));
        }
        return out;
    }

    // ---- 1: a member lost at the boss holds the run ------------------------------------------------

    @Test
    void aMemberLostAtTheBossHoldsTheRunUntilItComesBack() {
        assertTrue(RevivePause.on(), "this test turned -Dslums.bossfight.revivepause on");
        Orchestrator o = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0);
        RunState run = o.start(horntail(), Tier.NORMAL, party());
        View view = new View(run);

        view.present.remove(HUMAN); // the scripts unregister him: playerDead -> unregisterPlayer
        o.memberLost(run, HUMAN, "no longer in the stage", START);

        assertNull(run.endedBecause(), "a revive must not end the expedition");
        assertEquals(5, run.members().size(), "he is out of the run's membership while he revives");
        assertNotNull(run.waitingFor(), "the run holds itself for him");
        assertEquals(5 * 60_000L, RevivePause.windowMs(horntail()), "five minutes by default");

        Map<Integer, SolverStep> steps = o.tick(run, view, START + 10_000);
        assertEquals(5, steps.size(), "every bot says something");
        for (Map.Entry<Integer, SolverStep> e : steps.entrySet()) {
            SolverStep.Wait wait = assertInstanceOf(SolverStep.Wait.class, e.getValue(),
                    "nothing is warped and nothing is solved while the run is held");
            assertTrue(wait.why().contains("Zak (" + HUMAN + ")"), wait.why());
            assertTrue(wait.why().contains("revive"), wait.why());
        }
        assertEquals(1, run.currentStage(), "no stage advances");

        view.present.add(HUMAN); // he walked back in through the altar
        Map<Integer, SolverStep> after = o.tick(run, view, START + 60_000);
        assertNull(run.endedBecause());
        assertNull(run.waitingFor(), "the pause lifts the moment he is back");
        assertEquals(6, run.members().size(), "and he is a member again");
        assertEquals(1, run.currentStage());
        assertFalse(after.values().stream().anyMatch(s -> s instanceof SolverStep.Wait w && w.why().contains("revive")),
                "no bot is still waiting for the revive: " + after.values());
        assertTrue(run.events().stream().anyMatch(e -> e.line().contains("is back after 60 s")),
                "one line when it resumes: " + run.events());
        assertTrue(run.events().stream().anyMatch(e -> e.line().contains("run held for 300 s")),
                "one line when it starts holding: " + run.events());
    }

    // ---- the same loss without the switch is today's abort ------------------------------------------

    @Test
    void withoutTheSwitchTheSameLossEndsTheRunAsItAlwaysDid() {
        System.setProperty(RevivePause.FLAG, "false");
        try {
            Orchestrator o = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0);
            RunState run = o.start(horntail(), Tier.NORMAL, party());
            o.memberLost(run, HUMAN, "no longer in the stage", START);

            assertTrue(run.endedBecause().startsWith("the leader left"), run.endedBecause());
            assertNull(run.waitingFor(), "nothing is held");
        } finally {
            System.clearProperty(RevivePause.FLAG);
        }
    }

    // ---- 2: the window runs out ---------------------------------------------------------------------

    @Test
    void theWindowRunningOutEndsTheRunAndSaysItWasTheWindow() {
        Orchestrator o = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0);
        RunState run = o.start(horntail(), Tier.NORMAL, party());
        View view = new View(run);

        view.present.remove(HUMAN);
        o.memberLost(run, HUMAN, "no longer in the stage", START);
        long window = RevivePause.windowMs(horntail());

        o.tick(run, view, START + window - 1_000);
        assertNull(run.endedBecause(), "still inside the window");

        o.tick(run, view, START + window + 1);
        assertTrue(run.ended(), "the window is over");
        assertTrue(run.endedBecause().contains("revive window"), run.endedBecause());
        assertTrue(run.endedBecause().contains("not a solver failure"), run.endedBecause());
        assertTrue(run.endedBecause().contains("Zak (" + HUMAN + ")"), run.endedBecause());
    }

    // ---- 3: the death cap wins over the pause -------------------------------------------------------

    @Test
    void theDeathCapsEndWinsOverThePause() {
        // the cap's own decision first: two deaths on a party with one left is the end
        ExpeditionDeathCount count = new ExpeditionDeathCount();
        assertTrue(count.arm(1));
        assertEquals(ExpeditionDeathCount.Charge.SPENT, count.charge());
        assertEquals(ExpeditionDeathCount.Charge.EXHAUSTED, count.charge());

        // and what that end does to a paused run: EventInstanceManager.endForDeathCount ends the instance, and
        // MechanicsRuns.tick aborts on it before the orchestrator gets a say. A pause must not outlive that.
        Orchestrator o = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0);
        RunState run = o.start(horntail(), Tier.NORMAL, party());
        View view = new View(run);
        view.present.remove(HUMAN);
        o.memberLost(run, HUMAN, "no longer in the stage", START);
        assertNotNull(run.waitingFor());

        o.abort(run, "the event ended");
        Map<Integer, SolverStep> steps = o.tick(run, view, START + 10_000);
        assertTrue(steps.isEmpty(), "an ended run does nothing, paused or not");
        assertTrue(run.endedBecause().contains("the event ended"), run.endedBecause());
    }

    // ---- 4: a party quest keeps today's rule --------------------------------------------------------

    @Test
    void aPartyQuestStillEndsWhenAMemberLeaves() {
        Content kpq = MechanicsRuns.contentFor("KerningPQ");
        assertNotNull(kpq, "kpq.yaml must be listed in content/index.txt");
        assertEquals(MechanicsModel.Kind.PQ, kpq.kind());
        Orchestrator o = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0);
        RunState run = o.start(kpq, Tier.NORMAL, List.of(
                new RunMember(HUMAN, true, false, MechanicsPersona.PERFECT),
                new RunMember(2, false, true, MechanicsPersona.PERFECT),
                new RunMember(3, false, true, MechanicsPersona.PERFECT)));

        o.memberLost(run, 3, "left the party", START);
        assertTrue(run.endedBecause().startsWith("too few members"), run.endedBecause());
        assertNull(run.waitingFor(), "a party quest never holds itself for a revive");
    }

    /** The instance as the orchestrator sees it: who is registered, and the stage the run is on. */
    private static final class View implements StageView {

        private final RunState run;
        private final Set<Integer> present = new HashSet<>();
        private Stage stage = horntail().stages().get(0);

        View(RunState run) {
            this.run = run;
            for (RunMember m : run.members()) {
                present.add(m.characterId());
            }
        }

        @Override
        public Stage stage() {
            return stage;
        }

        @Override
        public List<RunMember> members() {
            List<RunMember> out = new ArrayList<>();
            for (RunMember m : run.members()) {
                if (present.contains(m.characterId())) {
                    out.add(m);
                }
            }
            return out;
        }

        @Override
        public boolean registered(int characterId) {
            return present.contains(characterId);
        }

        @Override
        public Optional<String> nameOf(int characterId) {
            return characterId == HUMAN ? Optional.of("Zak") : Optional.of("bot" + characterId);
        }

        @Override
        public int[] positionOf(int characterId) {
            return new int[]{0, 0};
        }

        @Override
        public String roleOf(int characterId) {
            return "head";
        }

        @Override
        public OptionalInt answerFor(int characterId) {
            return OptionalInt.empty();
        }

        @Override
        public int itemCount(int characterId, int itemId) {
            return 0;
        }

        @Override
        public Optional<String> eventProperty(String key) {
            return Optional.empty();
        }
    }
}
