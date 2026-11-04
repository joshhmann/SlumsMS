package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import soloMapling.mechanics.MechanicsModel.Tier;
import soloMapling.mechanics.RealRunPool.Origin;
import soloMapling.mechanics.RealRunPool.RunKey;
import soloMapling.mechanics.RunOutcome.Mode;
import soloMapling.mechanics.RunOutcome.Participant;
import soloMapling.mechanics.RunOutcome.ParticipantKind;
import soloMapling.mechanics.RunOutcome.Result;

/** What a simulated run is sampled from, and the floor under which it waits instead of faking. */
class RunSamplesTest {

    static final RunKey KPQ = new RunKey("kpq", Tier.NORMAL);

    private final RunSamples samples = new RunSamples();

    /** One run's outcome as {@link RunOutcomes} would hand it to the sampler. */
    static RunOutcome real(String content, Tier tier, Result result, long durationMs, Origin origin, Mode mode,
                           boolean human) {
        List<Participant> who = human
                ? List.of(new Participant(1, ParticipantKind.PLAYER, true), new Participant(2, ParticipantKind.BOT, false))
                : List.of(new Participant(2, ParticipantKind.BOT, true), new Participant(3, ParticipantKind.BOT, false));
        return new RunOutcome("r", "k", content, content, MechanicsModel.Kind.PQ, tier, result,
                result == Result.LOST ? "time budget ran out" : "cleared", mode, origin, 1_000, 1_000 + durationMs,
                result == Result.WON ? 5 : 3, 5, who, human, human);
    }

    static RunOutcome lobbyRun(Result result, long durationMs) {
        return real("kpq", Tier.NORMAL, result, durationMs, Origin.KPQ_LOBBY, Mode.REAL, false);
    }

    private void feed(int wins, int losses) {
        for (int i = 0; i < wins; i++) {
            samples.onOutcome(lobbyRun(Result.WON, 600_000 + i));
        }
        for (int i = 0; i < losses; i++) {
            samples.onOutcome(lobbyRun(Result.LOST, 1_800_000 + i));
        }
    }

    // ---- the floor ---------------------------------------------------------------------------------------------

    @Test
    void belowTheFloorNothingIsDrawnAndAtTheFloorSomethingIs() {
        feed(3, 1);
        assertEquals(4, samples.count(KPQ));
        assertTrue(samples.draw(KPQ, 5, new Random(1)).isEmpty(), "four real runs of five: it waits");

        feed(1, 0);
        assertTrue(samples.draw(KPQ, 5, new Random(1)).isPresent(), "five real runs: it may be sampled");
        assertTrue(samples.draw(KPQ, 6, new Random(1)).isEmpty(), "the floor is the caller's, not a constant");
    }

    @Test
    void aKeyWithNoRunsAtAllNeverDrawsAndTheFloorIsAtLeastOne() {
        assertTrue(samples.draw(KPQ, 5, new Random(1)).isEmpty());
        assertTrue(samples.draw(KPQ, 0, new Random(1)).isEmpty(), "a floor of 0 is still not 'invent one'");
    }

    // ---- what counts as a sample --------------------------------------------------------------------------------

    @Test
    void onlyARealBotOnlyRunThatWasWonOrLostFromALobbyCountsAndOnlyForItsOwnKey() {
        samples.onOutcome(real("kpq", Tier.NORMAL, Result.WON, 1, Origin.KPQ_LOBBY, Mode.SIMULATED, false));
        samples.onOutcome(real("kpq", Tier.NORMAL, Result.ABANDONED, 1, Origin.KPQ_LOBBY, Mode.REAL, false));
        samples.onOutcome(real("kpq", Tier.NORMAL, Result.WON, 1, Origin.HUMAN_PARTY, Mode.REAL, true));
        samples.onOutcome(real("kpq", Tier.NORMAL, Result.WON, 1, Origin.STAGED, Mode.REAL, false));
        samples.onOutcome(real("kpq", Tier.HARD, Result.WON, 1, Origin.KPQ_LOBBY, Mode.REAL, false));
        samples.onOutcome(real("opq", Tier.NORMAL, Result.WON, 1, Origin.KPQ_LOBBY, Mode.REAL, false));
        assertEquals(0, samples.count(KPQ), "a simulated run, an abandoned one, a human's, a staged one, another tier and"
                + " another content are none of them samples of kpq at NORMAL");

        samples.onOutcome(real("kpq", Tier.NORMAL, Result.WON, 1, Origin.CONTENT_LOBBY, Mode.REAL, false));
        samples.onOutcome(real("kpq", Tier.NORMAL, Result.LOST, 1, Origin.BOT_INSTANCE, Mode.REAL, false));
        assertEquals(2, samples.count(KPQ));
        assertEquals(1, samples.count(new RunKey("kpq", Tier.HARD)), "the other tier's run is a sample of its own key");
        assertEquals(1, samples.count(new RunKey("opq", Tier.NORMAL)), "and so is the other content's");
    }

    @Test
    void aSimulatedRunIsNeverASampleSoNothingIsDrawnFromAnInvention() {
        feed(5, 0);
        for (int i = 0; i < 50; i++) {
            samples.onOutcome(real("kpq", Tier.NORMAL, Result.LOST, 1, Origin.KPQ_LOBBY, Mode.SIMULATED, false));
        }
        assertEquals(5, samples.count(KPQ));
        for (int seed = 0; seed < 100; seed++) {
            assertEquals(Result.WON, samples.draw(KPQ, 5, new Random(seed)).orElseThrow().sample().result(),
                    "fifty simulated losses changed nothing");
        }
    }

    @Test
    void onlyTheLatestTwentyAreKept() {
        feed(0, 30); // thirty losses...
        for (int i = 0; i < RunSamples.KEEP; i++) {
            samples.onOutcome(lobbyRun(Result.WON, 1)); // ...then twenty wins push every loss out
        }
        assertEquals(RunSamples.KEEP, samples.count(KPQ));
        assertEquals(1.0, samples.draw(KPQ, 5, new Random(1)).orElseThrow().basis().pWin(), 0.0);
    }

    // ---- what is drawn --------------------------------------------------------------------------------------------

    @Test
    void theResultFollowsTheShareOfWinsAndTheDurationIsARealOneOfThatResult() {
        feed(6, 2);
        Set<Long> winDurations = new HashSet<>();
        Set<Long> lossDurations = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            winDurations.add(600_000L + i);
        }
        for (int i = 0; i < 2; i++) {
            lossDurations.add(1_800_000L + i);
        }

        int won = 0;
        int n = 4_000;
        Random rng = new Random(20_261_103L);
        for (int i = 0; i < n; i++) {
            RunSamples.Drawn d = samples.draw(KPQ, 5, rng).orElseThrow();
            assertEquals(8, d.basis().samples());
            assertEquals(0.75, d.basis().pWin(), 1e-9);
            if (d.sample().result() == Result.WON) {
                won++;
                assertTrue(winDurations.contains(d.sample().durationMs()), "a win's duration is a real win's");
                assertEquals(5, d.sample().stageReached());
            } else {
                assertTrue(lossDurations.contains(d.sample().durationMs()), "a loss's duration is a real loss's");
                assertEquals(3, d.sample().stageReached());
                assertEquals("time budget ran out", d.sample().reason());
            }
        }
        assertEquals(0.75, (double) won / n, 0.03, "wins come at the rate the real runs had them");
    }

    @Test
    void allWinsAlwaysWinAndAllLossesAlwaysLose() {
        feed(5, 0);
        for (int seed = 0; seed < 200; seed++) {
            assertEquals(Result.WON, samples.draw(KPQ, 5, new Random(seed)).orElseThrow().sample().result());
        }
        RunSamples losing = new RunSamples();
        for (int i = 0; i < 5; i++) {
            losing.onOutcome(lobbyRun(Result.LOST, 100 + i));
        }
        for (int seed = 0; seed < 200; seed++) {
            assertEquals(Result.LOST, losing.draw(KPQ, 5, new Random(seed)).orElseThrow().sample().result());
        }
    }

    @Test
    void theSameDiceGiveTheSameDrawAndAnotherSeedCanDiffer() {
        feed(5, 5);
        RunSamples.Drawn a = samples.draw(KPQ, 5, new Random(7)).orElseThrow();
        RunSamples.Drawn b = samples.draw(KPQ, 5, new Random(7)).orElseThrow();
        assertEquals(a, b);
        boolean differs = false;
        for (int seed = 0; seed < 50 && !differs; seed++) {
            differs = !samples.draw(KPQ, 5, new Random(seed)).orElseThrow().equals(a);
        }
        assertTrue(differs, "it is a draw, not a constant");
        assertFalse(a.basis().samples() == 0);
    }
}
