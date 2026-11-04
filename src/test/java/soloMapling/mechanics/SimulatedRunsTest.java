package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotRunOutcome.Slot;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Tier;
import soloMapling.mechanics.RealRunPool.Origin;
import soloMapling.mechanics.RealRunPool.RunKey;
import soloMapling.mechanics.RunOutcome.Mode;
import soloMapling.mechanics.RunOutcome.Participant;
import soloMapling.mechanics.RunOutcome.ParticipantKind;
import soloMapling.mechanics.RunOutcome.Result;
import soloMapling.mechanics.SimulatedRuns.Attempt;
import soloMapling.mechanics.SimulatedRuns.Verdict;

/**
 * The run a full pool makes instead of no run: no instance, no held bot, no tick, one scheduled completion and one
 * {@link RunOutcome} marked {@link Mode#SIMULATED} that the bots' chatter reads like any other. Everything here is driven
 * through {@link SimulatedRuns#tryStart} on its own samples, dice, clock and scheduler.
 */
class SimulatedRunsTest {

    private static final Content KPQ = KpqStageDataTest.kpq();

    private final RunSamples samples = new RunSamples();
    private final AtomicInteger cap = new AtomicInteger(8);
    private final AtomicInteger floor = new AtomicInteger(5);
    private final AtomicLong now = new AtomicLong(50_000_000L);
    private final SimulatedRuns sims = new SimulatedRuns(samples, cap::get, floor::get, new Random(11), now::get);
    private final List<RunOutcome> heard = new CopyOnWriteArrayList<>();
    private final Consumer<RunOutcome> ear = heard::add;
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();
    private final List<Integer> brains = new ArrayList<>();

    private java.util.function.Supplier<ScheduledExecutorService> wasScheduler;

    @BeforeEach
    void setUp() {
        RunOutcomes.listen(ear);
        wasScheduler = MechanicsRuns.scheduler;
        ScheduledExecutorService mine = mock(ScheduledExecutorService.class);
        when(mine.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
            scheduled.add(inv.getArgument(0));
            delays.add(inv.getArgument(1));
            ScheduledFuture<?> f = mock(ScheduledFuture.class);
            futures.add(f);
            return f;
        });
        MechanicsRuns.scheduler = () -> mine;
    }

    @AfterEach
    void tearDown() {
        RunOutcomes.unlisten(ear);
        MechanicsRuns.scheduler = wasScheduler;
        for (int id : brains) {
            CharacterStorage.removeActiveBot(id);
        }
        System.clearProperty(Mechanics.CHATTER_FLAG);
    }

    private void warm(int wins, int losses) {
        for (int i = 0; i < wins; i++) {
            samples.onOutcome(RunSamplesTest.lobbyRun(Result.WON, 600_000 + i * 1_000));
        }
        for (int i = 0; i < losses; i++) {
            samples.onOutcome(RunSamplesTest.lobbyRun(Result.LOST, 1_800_000 + i * 1_000));
        }
    }

    private static List<Participant> bots(int from, int n) {
        List<Participant> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Participant(from + i, ParticipantKind.BOT, i == 0));
        }
        return out;
    }

    private Attempt start(Origin origin, List<Participant> who) {
        return sims.tryStart(origin, KPQ, Tier.NORMAL, () -> who, "the real-run pool is full");
    }

    private Slot brain(int id) {
        BotSM sm = mock(BotSM.class);
        Slot slot = new Slot();
        when(sm.runOutcome()).thenReturn(slot);
        CharacterStorage.addActiveBot(id, sm);
        brains.add(id);
        return slot;
    }

    // ---- the floor: wait, never fake ---------------------------------------------------------------------------

    @Test
    void aContentWithFourRealRunsMakesNoSimulatedRunAndSaysItIsWaiting() {
        warm(3, 1);

        Attempt a = start(Origin.KPQ_LOBBY, bots(100, 4));

        assertEquals(Verdict.WAITING_FOR_SAMPLES, a.verdict());
        assertFalse(a.started());
        assertTrue(a.why().contains("kpq has 4 of 5 real runs") && a.why().contains("not faked"), a.why());
        assertTrue(scheduled.isEmpty(), "nothing was scheduled");
        assertEquals(0, sims.inFlight());
        assertFalse(sims.busy(100), "and no bot was named");
        assertTrue(heard.isEmpty(), "and no outcome was invented");
    }

    @Test
    void theFifthRealRunIsWhatLetsItStart() {
        warm(3, 1);
        assertEquals(Verdict.WAITING_FOR_SAMPLES, start(Origin.KPQ_LOBBY, bots(100, 4)).verdict());
        warm(1, 0);
        assertEquals(Verdict.STARTED, start(Origin.KPQ_LOBBY, bots(100, 4)).verdict());
    }

    @Test
    void theFloorIsASwitchAndNeverBelowOne() {
        assertEquals(Mechanics.DEFAULT_SIM_FLOOR, Mechanics.simulatedFloor());
        System.setProperty(Mechanics.SIM_FLOOR_FLAG, "0");
        try {
            assertEquals(1, Mechanics.simulatedFloor(), "0 would be 'invent one from nothing'");
            System.setProperty(Mechanics.SIM_FLOOR_FLAG, "9");
            assertEquals(9, Mechanics.simulatedFloor());
            System.setProperty(Mechanics.SIM_FLOOR_FLAG, "x");
            assertEquals(5, Mechanics.simulatedFloor());
        } finally {
            System.clearProperty(Mechanics.SIM_FLOOR_FLAG);
        }
        assertEquals(8, Mechanics.simulatedRunCapacity());
        System.setProperty(Mechanics.SIM_RUNS_FLAG, "0");
        try {
            assertEquals(0, Mechanics.simulatedRunCapacity());
        } finally {
            System.clearProperty(Mechanics.SIM_RUNS_FLAG);
        }
    }

    // ---- what a started run is --------------------------------------------------------------------------------

    @Test
    void aSimulatedRunHoldsNothingAndEndsWithOneSimulatedRecordAfterTheSampledDuration() {
        warm(5, 0);
        int held = MechanicsHold.count();
        Attempt a = start(Origin.KPQ_LOBBY, bots(200, 4));

        assertTrue(a.started(), a.why());
        assertNotNull(a.runId());
        assertEquals(1, sims.inFlight());
        assertEquals(1, scheduled.size(), "one scheduled completion and nothing else");
        long wait = delays.get(0);
        assertTrue(wait >= 600_000 && wait <= 604_000, "the wait is a real win's duration: " + wait);
        assertEquals(held, MechanicsHold.count(), "no bot is held");
        assertTrue(MechanicsRuns.get().active().stream().noneMatch(r -> r.runId().equals(a.runId())), "and it is no run");
        assertTrue(heard.isEmpty(), "nothing is said before it is over");
        assertTrue(sims.describe().get(0).startsWith("simulated kpq (KPQ_LOBBY): 4 bot(s), WON in "), sims.describe().toString());

        now.addAndGet(wait);
        scheduled.get(0).run();

        assertEquals(1, heard.size());
        RunOutcome o = heard.get(0);
        assertEquals(a.runId(), o.runId());
        assertEquals(Mode.SIMULATED, o.mode());
        assertEquals(Result.WON, o.result());
        assertEquals("kpq", o.contentId());
        assertEquals(Tier.NORMAL, o.tier());
        assertEquals(Origin.KPQ_LOBBY, o.origin());
        assertEquals(wait, o.durationMs(), "the record's duration is the one it waited");
        assertEquals(List.of(200, 201, 202, 203), o.participants().stream().map(Participant::characterId).toList());
        assertEquals(200, o.leaderId());
        assertFalse(o.humanAtStart() || o.humanAtEnd());
        assertEquals(new RunOutcome.Basis(5, 1.0), o.simulated());
        assertEquals("simulated: cleared", o.reason());
        assertTrue(o.line().contains(" WON simulated KPQ_LOBBY ") && o.line().contains("sampled from 5 real runs, pWin 1.00"),
                o.line());
        assertEquals(0, sims.inFlight());
        assertFalse(sims.busy(200), "its bots are free again");
    }

    @Test
    void aSimulatedLossIsALossFromTheRealLossesAndAFailedRunSaysSo() {
        warm(0, 5);
        Attempt a = start(Origin.KPQ_LOBBY, bots(300, 4));
        assertTrue(a.started());
        assertTrue(delays.get(0) >= 1_800_000, "a loss takes as long as the real losses did");
        scheduled.get(0).run();

        RunOutcome o = heard.get(0);
        assertEquals(Result.LOST, o.result());
        assertEquals(3, o.stageReached());
        assertEquals("simulated: time budget ran out", o.reason());
    }

    @Test
    void theChatterSpeaksASimulatedRunLikeAnyOtherButNotAnOwnedAlt() {
        warm(5, 0);
        Slot leader = brain(400);
        Slot other = brain(401);
        Slot third = brain(402);
        Slot logged = null; // 403 has no brain: it logged out meanwhile, and says nothing
        start(Origin.KPQ_LOBBY, bots(400, 4));
        scheduled.get(0).run();

        long said = List.of(leader, other, third).stream().filter(s -> s.get() != null).count();
        assertEquals(2, said, "the leader and one other report the win");
        assertNotNull(leader.get(), "the leader first");
        assertNull(logged);
    }

    @Test
    void aSimulatedRunIsNeverASampleSoTheNextOneDrawsFromRealRunsOnly() {
        warm(5, 0);
        start(Origin.KPQ_LOBBY, bots(500, 4));
        Consumer<RunOutcome> feeding = samples::onOutcome; // the real wiring: MechanicsRuns registers the global sampler
        RunOutcomes.listen(feeding);
        try {
            scheduled.get(0).run();
        } finally {
            RunOutcomes.unlisten(feeding);
        }
        assertEquals(5, samples.count(new RunKey("kpq", Tier.NORMAL)));
    }

    // ---- what it never is --------------------------------------------------------------------------------------

    @Test
    void aRunWithAHumanInItIsNeverSimulated() {
        warm(5, 0);
        List<Participant> mixed = List.of(new Participant(600, ParticipantKind.PLAYER, true),
                new Participant(601, ParticipantKind.BOT, false), new Participant(602, ParticipantKind.BOT, false));

        Attempt a = start(Origin.KPQ_LOBBY, mixed);

        assertEquals(Verdict.NOT_ALLOWED, a.verdict());
        assertTrue(scheduled.isEmpty());
        assertEquals(0, sims.inFlight());
        assertFalse(sims.busy(601));
    }

    @Test
    void aHumansAGmsOrAQaRunIsNeverSimulatedWhateverTheSamplesSay() {
        warm(5, 0);
        for (Origin o : new Origin[] {Origin.HUMAN_PARTY, Origin.HUMAN_EXPEDITION, Origin.STAGED}) {
            Attempt a = start(o, bots(700, 4));
            assertEquals(Verdict.NOT_ALLOWED, a.verdict(), o.toString());
            assertTrue(a.why().contains("always real"), a.why());
        }
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void noBotToReportMeansNoRunAndTheBotsAreNotAskedForUntilEveryOtherCheckPassed() {
        warm(3, 0); // below the floor
        AtomicInteger asked = new AtomicInteger();
        Attempt cold = sims.tryStart(Origin.CONTENT_LOBBY, KPQ, Tier.NORMAL, () -> {
            asked.incrementAndGet();
            return bots(1, 4);
        }, "full");
        assertEquals(Verdict.WAITING_FOR_SAMPLES, cold.verdict());
        assertEquals(0, asked.get(), "the scan for idle bots is not paid for a run that cannot start");

        warm(2, 0);
        Attempt none = sims.tryStart(Origin.CONTENT_LOBBY, KPQ, Tier.NORMAL, List::of, "full");
        assertEquals(Verdict.NO_PARTY, none.verdict());
        assertEquals(0, sims.inFlight());
    }

    // ---- the limits ------------------------------------------------------------------------------------------------

    @Test
    void oneSimulatedRunPerContentAndTierAtATime() {
        warm(5, 0);
        assertTrue(start(Origin.KPQ_LOBBY, bots(800, 4)).started());

        Attempt second = start(Origin.KPQ_LOBBY, bots(810, 4));
        assertEquals(Verdict.ALREADY_RUNNING, second.verdict());
        assertFalse(sims.busy(810), "the refused run named nobody");

        Attempt otherTier = sims.tryStart(Origin.KPQ_LOBBY, KPQ, Tier.HARD, () -> bots(820, 4), "full");
        assertEquals(Verdict.WAITING_FOR_SAMPLES, otherTier.verdict(), "another tier is its own key, with its own samples");

        scheduled.get(0).run();
        assertTrue(start(Origin.KPQ_LOBBY, bots(830, 4)).started(), "free again once it ended");
    }

    @Test
    void theNumberInFlightIsCappedAndZeroTurnsSimulationOff() {
        warm(5, 0);
        Content opq = MechanicsRuns.contentFor("OrbisPQ");
        for (int i = 0; i < 5; i++) {
            samples.onOutcome(RunSamplesTest.real("opq", Tier.NORMAL, Result.WON, 1_000, Origin.CONTENT_LOBBY, Mode.REAL,
                    false));
        }
        cap.set(1);
        assertTrue(start(Origin.KPQ_LOBBY, bots(900, 4)).started());
        Attempt full = sims.tryStart(Origin.CONTENT_LOBBY, opq, Tier.NORMAL, () -> bots(910, 5), "full");
        assertEquals(Verdict.AT_CAPACITY, full.verdict());
        assertTrue(full.why().contains("1 of 1"), full.why());

        cap.set(0);
        scheduled.get(0).run();
        Attempt off = sims.tryStart(Origin.CONTENT_LOBBY, opq, Tier.NORMAL, () -> bots(910, 5), "full");
        assertEquals(Verdict.AT_CAPACITY, off.verdict());
        assertTrue(off.why().contains("simulated runs are off"), off.why());
    }

    @Test
    void theBotsOfARunInFlightAreBusyUntilItEnds() {
        warm(5, 0);
        start(Origin.KPQ_LOBBY, bots(1_000, 3));
        assertTrue(sims.busy(1_000) && sims.busy(1_001) && sims.busy(1_002));
        assertFalse(sims.busy(1_003));
        scheduled.get(0).run();
        assertFalse(sims.busy(1_000));
    }

    @Test
    void aRefusingSchedulerMakesNoRunAndNamesNobody() {
        warm(5, 0);
        ScheduledExecutorService refusing = mock(ScheduledExecutorService.class);
        when(refusing.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("shutting down"));
        MechanicsRuns.scheduler = () -> refusing;

        Attempt a = start(Origin.KPQ_LOBBY, bots(1_100, 4));

        assertEquals(Verdict.COULD_NOT_SCHEDULE, a.verdict());
        assertEquals(0, sims.inFlight());
        assertFalse(sims.busy(1_100));
    }

    // ---- ending it from outside --------------------------------------------------------------------------------

    @Test
    void aStopEndsItAbandonedAndNoBotSaysAResultNobodyDrew() {
        warm(5, 0);
        Slot leader = brain(1_200);
        start(Origin.KPQ_LOBBY, bots(1_200, 4));

        assertEquals(1, sims.stopAll("stopped by a GM"));

        verify(futures.get(0)).cancel(false);
        assertEquals(1, heard.size());
        assertEquals(Result.ABANDONED, heard.get(0).result());
        assertEquals(Mode.SIMULATED, heard.get(0).mode());
        assertEquals("stopped by a GM", heard.get(0).reason());
        assertNull(leader.get(), "an abandoned run is not talked about");
        assertEquals(0, sims.inFlight());
        assertFalse(sims.busy(1_200));

        scheduled.get(0).run(); // the cancelled timer firing anyway changes nothing
        assertEquals(1, heard.size());
        assertEquals(0, sims.stopAll("again"));
    }

    @Test
    void aTimerThatFiresWhileTheServerIsStoppingWritesNothing() {
        warm(5, 0);
        start(Origin.KPQ_LOBBY, bots(1_300, 4));
        try (var shutdown = org.mockito.Mockito.mockStatic(soloMapling.server.BotShutdown.class)) {
            shutdown.when(soloMapling.server.BotShutdown::isQuiesced).thenReturn(true);
            scheduled.get(0).run();
        }
        assertTrue(heard.isEmpty());
        assertEquals(0, sims.inFlight());
        assertFalse(sims.busy(1_300));
    }

    // ---- who reports a run that has no bots standing at its door -----------------------------------------------

    @Test
    void thePickIsTheRequestedNumberLeaderFirstAndSeededDiceGiveTheSameParty() {
        List<SimulatedRuns.Candidate> idle = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            idle.add(new SimulatedRuns.Candidate(i, 50 + i));
        }
        List<Participant> a = SimulatedRuns.pick(idle, 5, new Random(3));
        List<Participant> b = SimulatedRuns.pick(new ArrayList<>(idle).reversed(), 5, new Random(3));

        assertEquals(5, a.size());
        assertEquals(a, b, "the order the bots are listed in does not matter");
        assertTrue(a.get(0).leader() && a.stream().filter(Participant::leader).count() == 1);
        assertTrue(a.stream().allMatch(p -> p.kind() == ParticipantKind.BOT));
        assertEquals(3, SimulatedRuns.pick(idle.subList(0, 3), 5, new Random(3)).size(), "fewer than asked when fewer idle");
        assertTrue(SimulatedRuns.pick(List.of(), 5, new Random(3)).isEmpty());
        assertEquals(List.of(new Participant(7, ParticipantKind.BOT, true), new Participant(8, ParticipantKind.BOT, false)),
                SimulatedRuns.party(List.of(7, 8)));
    }

    @Test
    void aRefusalIsToldOncePerKeyPerTenMinutes() {
        assertTrue(sims.tellDue("kpq:waiting"));
        assertFalse(sims.tellDue("kpq:waiting"));
        assertTrue(sims.tellDue("opq:waiting"), "another key is another line");
        now.addAndGet(RealRunPool.LOG_GAP_MS - 1);
        assertFalse(sims.tellDue("kpq:waiting"));
        now.addAndGet(1);
        assertTrue(sims.tellDue("kpq:waiting"));
    }

    @Test
    void neverStartsAnythingThroughTheRealRunMachinery() {
        warm(5, 0);
        verify(MechanicsRuns.scheduler.get(), never()).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
        start(Origin.KPQ_LOBBY, bots(1_400, 4));
        verify(MechanicsRuns.scheduler.get(), never()).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
        assertEquals(0, RealRunPool.get().held(), "and it never touched the pool");
    }
}
