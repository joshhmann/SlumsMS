package soloMapling.mechanics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Stage;
import soloMapling.mechanics.MechanicsModel.Tier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.4, owner 2026-11-03: bots are mortal by default, so a boss run has to know what to do with a dead one. The two
 * failures to prevent are a run that keeps billing after losing a body (the dead bot's role claim holds a jaws/pass
 * stage hostage until the budget runs out) and a run that waits forever on a corpse because "back" was asked as
 * "registered" and a dead bot never unregisters.
 *
 * <p>Neither needs new machinery: a dead member is a member lost - the same {@code memberLost} the scripts' own
 * unregistration of a dead player has always caused - and the revive pause is the existing hold. With the pause off the
 * run ends through an existing path with a reason that names the loss.
 */
class DeadMemberTest {

    private static final long START = 1_000_000L;
    private static final int HUMAN = 1;

    @AfterEach
    void switchesBack() {
        System.clearProperty(RevivePause.FLAG);
    }

    private static Content horntail() {
        Content content = MechanicsRuns.contentFor("HorntailBattle");
        assertNotNull(content, "horntail.yaml must be listed in content/index.txt");
        return content;
    }

    /** One human leader and five bots: the shipped Horntail row's six-member minimum. */
    private static List<RunMember> party() {
        List<RunMember> out = new ArrayList<>();
        out.add(new RunMember(HUMAN, true, false, MechanicsPersona.PERFECT));
        for (int id = 2; id <= 6; id++) {
            out.add(new RunMember(id, false, true, MechanicsPersona.PERFECT));
        }
        return out;
    }

    private static Orchestrator orchestrator() {
        return new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0);
    }

    // ---- 1: a dead bot is a member lost, and the pause waits on the body -----------------------------

    @Test
    void aDeadBotIsAMemberLostAndThePauseDoesNotLiftOnItsCorpse() {
        System.setProperty(RevivePause.FLAG, "true");
        Orchestrator o = orchestrator();
        RunState run = o.start(horntail(), Tier.NORMAL, party());
        View view = new View(run);
        view.dead.add(2); // bot 2 took a lethal hit where it stood, and stays registered: a bot never unregisters

        Map<Integer, SolverStep> steps = o.tick(run, view, START + 10_000);

        assertEquals(5, run.members().size(), "the run lost the bot: it is not a member while it lies there");
        assertNull(run.endedBecause(), "one bot down is not the end of the expedition");
        assertNotNull(run.waitingFor(), "the run holds for it");
        assertEquals(2, run.waitingFor().member().characterId());
        assertEquals(4, steps.size(), "the four bots who are left each say something");
        for (SolverStep step : steps.values()) {
            assertTrue(step instanceof SolverStep.Wait, "nothing advances while a member is being revived: " + step);
        }

        // second tick, still dead: "registered" is true (a bot never leaves the instance), so this is where the old
        // resume test would have lifted the pause onto a corpse
        o.tick(run, view, START + 20_000);
        assertNotNull(run.waitingFor(), "a corpse is not a returning member");
        assertEquals(5, run.members().size());

        view.dead.remove(2); // a Bishop's Resurrection, or a revive item
        o.tick(run, view, START + 30_000);
        assertNull(run.waitingFor(), "the pause lifts the moment it is alive again");
        assertEquals(6, run.members().size(), "and it is a member again");
        assertTrue(run.events().stream().anyMatch(e -> e.line().contains("is back after")), run.events().toString());
    }

    @Test
    void aDeadHumanIsStillTheSolversBusinessNotAMemberLost() {
        System.setProperty(RevivePause.FLAG, "true");
        Orchestrator o = orchestrator();
        RunState run = o.start(horntail(), Tier.NORMAL, party());
        View view = new View(run);
        view.dead.add(HUMAN); // he died and the script has not got to unregisterPlayer yet

        Map<Integer, SolverStep> steps = o.tick(run, view, START + 5_000);

        assertEquals(6, run.members().size(), "the rule here is the bot's: a human's death is the script's and the "
                + "leader wait's, and it must not turn into a lost member under either of them");
        assertNull(run.waitingFor());
        assertNull(run.endedBecause());
        assertTrue(steps.values().stream().anyMatch(s -> s instanceof SolverStep.Wait w
                        && w.why().contains("the leader is down")),
                "the leader's own wait is what a dead human gets: " + steps.values());
    }

    // ---- 2: with the pause off, a dead bot ends the run through an existing path --------------------

    @Test
    void withThePauseOffADeadBotEndsTheRunThroughAnExistingPath() {
        // the switch is off by default now: no property set here
        Orchestrator o = orchestrator();
        RunState run = o.start(horntail(), Tier.NORMAL, party());
        View view = new View(run);
        view.dead.add(2);

        o.tick(run, view, START + 10_000);

        assertTrue(run.ended(), "a six-member expedition that is down to five is over");
        assertTrue(run.endedBecause().startsWith("too few members left (5 < 6)"), run.endedBecause());
        assertNull(run.waitingFor(), "and nothing waits on the corpse");
    }

    @Test
    void withThePauseOffALostBotDoesNotStallARunThatCanStillFight() {
        Orchestrator o = orchestrator();
        Content kpq = MechanicsRuns.contentFor("KerningPQ");
        assertNotNull(kpq);
        RunState run = o.start(kpq, Tier.NORMAL, List.of(
                new RunMember(HUMAN, true, false, MechanicsPersona.PERFECT),
                new RunMember(2, false, true, MechanicsPersona.PERFECT),
                new RunMember(3, false, true, MechanicsPersona.PERFECT),
                new RunMember(4, false, true, MechanicsPersona.PERFECT)));
        View view = new View(run, kpq.stages().get(0));
        view.dead.add(2);

        o.tick(run, view, START + 1_000);

        assertFalse(run.ended(), "still three members, which is the row's minimum: the run goes on");
        assertEquals(3, run.members().size());
        assertNull(run.waitingFor(), "a party quest never holds itself for a corpse");
        assertTrue(run.events().stream().anyMatch(e -> e.line().contains("2 lost: died")), run.events().toString());
    }

    // ---- 3: the hold goes back to a bot the run has lost -------------------------------------------------

    @Test
    void aLostMemberGetsItsOwnBrainBackAndAPausedRunKeepsHoldingTheOneItWaitsFor() {
        Orchestrator o = orchestrator();
        RunState run = o.start(horntail(), Tier.NORMAL, party());
        java.util.Map<Integer, client.Character> bots = new java.util.LinkedHashMap<>();
        for (int id = 2; id <= 6; id++) {
            bots.put(id, testutil.Mocks.chr("bot" + id));
        }
        List<String> said = new ArrayList<>();
        MechanicsRuns.Active active = new MechanicsRuns.Active(run, null, bots, said::add, null, true, null, List.of());
        for (int id = 2; id <= 6; id++) {
            active.pausedBrains.add(id);
        }
        o.memberLost(run, 2, "died", START); // revive pause off: the run carries on without it

        MechanicsRuns.get().releaseLostMembers(active);

        assertFalse(active.bots.containsKey(2), "the lost bot is no longer driven");
        assertTrue(said.stream().anyMatch(l -> l.contains("bot2 is out of the run")), said.toString());
        assertEquals(4, active.bots.size(), "the other four are still the run's");
    }

    @Test
    void theMemberAPausedRunWaitsForStaysHeldUntilItIsBack() {
        System.setProperty(RevivePause.FLAG, "true");
        Orchestrator o = orchestrator();
        RunState run = o.start(horntail(), Tier.NORMAL, party());
        java.util.Map<Integer, client.Character> bots = new java.util.LinkedHashMap<>();
        for (int id = 2; id <= 6; id++) {
            bots.put(id, testutil.Mocks.chr("bot" + id));
        }
        MechanicsRuns.Active active = new MechanicsRuns.Active(run, null, bots, l -> { }, null, true, null, List.of());
        o.memberLost(run, 2, "died", START);
        assertNotNull(run.waitingFor());

        MechanicsRuns.get().releaseLostMembers(active);

        assertTrue(active.bots.containsKey(2), "the pause is what keeps it in the run: do not hand its brain back yet");
    }

    /** The instance as the orchestrator sees it: who is registered, who is alive, and the stage the run is on. */
    private static final class View implements StageView {

        private final RunState run;
        private final Set<Integer> present = new HashSet<>();
        private final Set<Integer> dead = new HashSet<>();
        private final Stage stage;

        View(RunState run) {
            this(run, horntail().stages().get(0));
        }

        View(RunState run, Stage stage) {
            this.run = run;
            this.stage = stage;
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
        public boolean alive(int characterId) {
            return !dead.contains(characterId);
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
