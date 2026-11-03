package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import soloMapling.mechanics.RealRunPool.Admission;
import soloMapling.mechanics.RealRunPool.Origin;
import soloMapling.server.BotShutdown;

/**
 * A place in {@link RealRunPool} is held by a run from its admission to its end, and given back <b>on every end</b>: a
 * lease that leaked here would be a permanently smaller quota. Every way a {@link MechanicsRuns} run can end is driven
 * through the real tick or the real stop, with the reason asserted so a test cannot pass by ending some other way.
 *
 * <p>Mocked instances and a scheduler that hands the tick to the test, as {@link MechanicsRunsInstancesTest}.
 */
class MechanicsRunsPoolTest {

    private final AtomicLong clock = new AtomicLong(5_000_000L);
    private final RealRunPool pool = new RealRunPool(() -> 5, clock::get);
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final List<Runnable> ticks = new ArrayList<>();

    private java.util.function.Supplier<ScheduledExecutorService> wasScheduler;

    @BeforeEach
    void ticksAreMine() {
        wasScheduler = MechanicsRuns.scheduler;
        ScheduledExecutorService mine = mock(ScheduledExecutorService.class);
        when(mine.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenAnswer(inv -> {
            ticks.add(inv.getArgument(0));
            return mock(ScheduledFuture.class);
        });
        MechanicsRuns.scheduler = () -> mine;
    }

    @AfterEach
    void putBack() {
        MechanicsRuns.get().stopAll("test over");
        MechanicsRuns.scheduler = wasScheduler;
        System.clearProperty(Mechanics.FLAG);
        System.clearProperty(Mechanics.REAL_RUNS_FLAG);
        RealRunPool.get().clearForTest();
    }

    private Admission lease() {
        Admission a = pool.admit(Origin.KPQ_LOBBY, KpqStageDataTest.kpq(), 0);
        assertTrue(a.granted() && a.leased(), "the test's own admission");
        return a;
    }

    private MechanicsRuns.Active start(EventInstanceManager eim, long budgetMs, int lastStage, Admission admission) {
        return MechanicsRuns.get().start(eim, KpqStageDataTest.kpq(), true, budgetMs, 1, lastStage, true, lines::add,
                null, admission);
    }

    private static EventInstanceManager kpq(int channel, int from) {
        return MechanicsRunsInstancesTest.kpqOn(channel, MechanicsRunsInstancesTest.bots(from), from);
    }

    private void tick() {
        assertEquals(1, ticks.size(), "the run scheduled its tick once");
        ticks.get(0).run();
    }

    // ---- a lease is held while the run lives ------------------------------------------------------------------

    @Test
    void aRunHoldsItsPlaceWhileItPlaysAndSaysHowItCameToBe() {
        Admission admission = lease();
        MechanicsRuns.Active a = start(kpq(1, 6_100), 0, 1, admission);

        assertFalse(a.run().ended(), lines.toString());
        assertEquals(1, pool.held(), "a run in play holds its place");
        assertTrue(a.leased());
        assertEquals(Origin.KPQ_LOBBY, a.origin());
    }

    // ---- every end gives it back --------------------------------------------------------------------------------

    @Test
    void aClearedRunGivesItsPlaceBack() {
        EventInstanceManager eim = kpq(1, 6_200);
        when(eim.getProperty("1stageclear")).thenReturn("true");
        MechanicsRuns.Active a = start(eim, 0, 1, lease());

        tick();

        assertEquals("cleared", a.run().endedBecause());
        assertTrue(a.run().cleared());
        assertEquals(0, pool.held(), "the clear released the lease");
        assertFalse(a.leased());
    }

    @Test
    void aStoppedOrYieldedRunGivesItsPlaceBack() {
        MechanicsRuns.Active a = start(kpq(1, 6_300), 0, 1, lease());
        assertEquals(1, pool.held());

        MechanicsRuns.get().stop(a, "yielded to Zak's party");

        assertEquals("yielded to Zak's party", a.run().endedBecause());
        assertEquals(0, pool.held(), "a GM stop, a yield to a player, QA's stop: the place is free");
    }

    @Test
    void aRunWhoseClockRanOutGivesItsPlaceBack() {
        MechanicsRuns.Active a = start(kpq(1, 6_400), 1_000, 1, lease());
        a.run().markStarted(System.currentTimeMillis() - 60_000); // the run has been playing a minute of its one second

        tick();

        assertTrue(a.run().endedBecause().startsWith("time budget"), a.run().endedBecause());
        assertEquals(RunState.Ending.LOST, a.run().ending());
        assertEquals(0, pool.held(), "the clock ending the run released the lease");
    }

    @Test
    void aRunWhoseInstanceTheScriptEndedGivesItsPlaceBack() {
        // the death count's end (ExpeditionDeathCount): the budget is spent, the script disposes the instance under the run
        EventInstanceManager eim = kpq(1, 6_500);
        MechanicsRuns.Active a = start(eim, 0, 1, lease());
        when(eim.isEventDisposed()).thenReturn(true);

        tick();

        assertEquals("the event ended", a.run().endedBecause());
        assertEquals(0, pool.held(), "an instance disposed under the run released the lease");
    }

    @Test
    void aRunThatThrowsOnItsTickGivesItsPlaceBack() {
        EventInstanceManager eim = kpq(1, 6_600);
        MechanicsRuns.Active a = start(eim, 0, 1, lease());
        when(eim.getPlayers()).thenThrow(new IllegalStateException("the instance is half gone"));

        tick();

        assertTrue(a.run().endedBecause().startsWith("exception"), a.run().endedBecause());
        assertEquals(0, pool.held());
    }

    @Test
    void aRunWhoseLeaderLeftGivesItsPlaceBack() {
        List<Character> party = MechanicsRunsInstancesTest.bots(6_700);
        EventInstanceManager eim = MechanicsRunsInstancesTest.kpqOn(1, party, 6_700);
        MechanicsRuns.Active a = start(eim, 0, 1, lease());
        when(eim.getPlayers()).thenReturn(new ArrayList<>(party.subList(1, 4)));

        tick();

        assertTrue(a.run().endedBecause().startsWith("the leader left"), a.run().endedBecause());
        assertEquals(0, pool.held());
    }

    @Test
    void aRunThatEndsBecauseTheServerIsStoppingGivesItsPlaceBack() {
        MechanicsRuns.Active a = start(kpq(1, 6_800), 0, 1, lease());
        try (MockedStatic<BotShutdown> shutdown = mockStatic(BotShutdown.class)) {
            shutdown.when(BotShutdown::isQuiesced).thenReturn(true);
            tick();
        }
        assertEquals("the server is shutting down", a.run().endedBecause());
        assertEquals(0, pool.held());
    }

    @Test
    void aRunWhoseTickCannotBeScheduledGivesItsPlaceBack() {
        ScheduledExecutorService refusing = mock(ScheduledExecutorService.class);
        when(refusing.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any()))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("shutting down"));
        MechanicsRuns.scheduler = () -> refusing;

        MechanicsRuns.Active a = start(kpq(1, 6_900), 0, 1, lease());

        assertTrue(a.run().endedBecause().startsWith("could not start"), a.run().endedBecause());
        assertEquals(0, pool.held(), "a run that never got its tick gave the place back");
    }

    @Test
    void aRunThatEndsAtItsStartGivesItsPlaceBack() {
        // two bots: Kerning PQ takes three to four, so the orchestrator ends the run before it holds anybody
        List<Character> two = new ArrayList<>(MechanicsRunsInstancesTest.bots(7_000).subList(0, 2));
        EventInstanceManager eim = MechanicsRunsInstancesTest.kpqOn(1, two, 7_000);
        Admission admission = lease();

        MechanicsRuns.Active a = start(eim, 0, 1, admission);

        assertTrue(a.run().ended(), "the party was too small");
        assertTrue(a.run().endedBecause().contains("takes 3-4 members"), a.run().endedBecause());
        assertEquals(0, pool.held(), "a run refused at its start holds nothing");
        assertTrue(ticks.isEmpty(), "and was never scheduled");
    }

    @Test
    void aSecondRunOnOneInstanceIsRefusedAndGivesItsOwnPlaceBackNotTheFirstRuns() {
        EventInstanceManager eim = kpq(1, 7_100);
        MechanicsRuns.Active first = start(eim, 0, 1, lease());
        MechanicsRuns.Active second = start(eim, 0, 1, lease());

        assertTrue(second.run().ended());
        assertEquals("this event instance already has a run", second.run().endedBecause());
        assertEquals(1, pool.held(), "the refused run's place is back; the first run keeps its own");
        assertTrue(first.leased());
        assertFalse(second.leased());
        assertSame(first, MechanicsRuns.get().runFor(eim));

        MechanicsRuns.get().stop(first, "done");
        assertEquals(0, pool.held());
    }

    @Test
    void aFinishThatThrowsHalfwayStillGivesItsPlaceBack() {
        EventInstanceManager eim = kpq(1, 7_200);
        List<String> seen = new ArrayList<>();
        MechanicsRuns.Active a = MechanicsRuns.get().start(eim, KpqStageDataTest.kpq(), true, 0, 1, 1, true, line -> {
            seen.add(line);
            if (line.startsWith("RUN end")) {
                throw new IllegalStateException("the QA feed threw at the run's last line");
            }
        }, null, lease());

        assertThrows(IllegalStateException.class, () -> MechanicsRuns.get().stop(a, "stopped"));

        assertEquals(0, pool.held(), "however the end goes wrong, the lease goes back");
        assertTrue(seen.stream().anyMatch(l -> l.startsWith("RUN end")), seen.toString());
    }

    // ---- a run nobody leased is untouched ----------------------------------------------------------------------

    @Test
    void aStagedRunHoldsNoPlaceAndNeverTouchesThePool() {
        // the nine-argument start is what a GM, QA and every older caller use: exempt, always real
        MechanicsRuns.Active a = MechanicsRuns.get().start(kpq(1, 7_300), KpqStageDataTest.kpq(), true, 0, 1, 1, true,
                lines::add, null);

        assertFalse(a.leased());
        assertEquals(Origin.STAGED, a.origin());
        assertEquals(0, pool.held());
        MechanicsRuns.get().stop(a, "done");
        assertEquals(0, pool.held());
        assertEquals("done", a.run().endedBecause());
    }

    @Test
    void aRunAdmittedByAnUnlimitedPoolHoldsNoPlaceAndPlaysAsBefore() {
        RealRunPool unlimited = new RealRunPool(() -> -1, clock::get);
        Admission admission = unlimited.admit(Origin.KPQ_LOBBY, KpqStageDataTest.kpq(), 0);
        MechanicsRuns.Active a = start(kpq(1, 7_400), 0, 1, admission);

        assertFalse(a.run().ended(), lines.toString());
        assertFalse(a.leased());
        assertEquals(0, unlimited.held());
        assertNotNull(MechanicsRuns.get().runFor(a.instance()));
        assertEquals(Origin.KPQ_LOBBY, a.origin(), "it still knows how it came to be");
        MechanicsRuns.get().stop(a, "done");
        assertNull(MechanicsRuns.get().runFor(a.instance()));
    }

    // ---- the seam a real instance start goes through ---------------------------------------------------------------

    private static Character human(int id) {
        return MechanicsRunsInstancesTest.member(id, false);
    }

    private static EventInstanceManager humanLed(int channel, int humanId, int botsFrom) {
        List<Character> party = new ArrayList<>();
        party.add(human(humanId));
        party.addAll(MechanicsRunsInstancesTest.bots(botsFrom).subList(0, 3));
        return MechanicsRunsInstancesTest.kpqOn(channel, party, humanId);
    }

    @Test
    void aHumansPartyIsAlwaysRealAndTakesNoPlace() {
        System.setProperty(Mechanics.FLAG, "true");
        System.setProperty(Mechanics.REAL_RUNS_FLAG, "0"); // the pool admits no bot-only run at all

        MechanicsRuns.Active party = MechanicsRuns.onInstanceStarted(humanLed(1, 8_000, 8_010), false);
        MechanicsRuns.Active expedition = MechanicsRuns.onInstanceStarted(humanLed(2, 8_100, 8_110), true);

        assertNotNull(party, "a player's party plays whatever the quota says: " + lines);
        assertNotNull(expedition);
        assertEquals(Origin.HUMAN_PARTY, party.origin());
        assertEquals(Origin.HUMAN_EXPEDITION, expedition.origin());
        assertFalse(party.leased());
        assertFalse(expedition.leased());
        assertEquals(0, RealRunPool.get().held(), "and holds nothing from the bots' pool");
    }

    @Test
    void aBotOnlyInstanceAsksThePoolAndARefusedRunIsSimplyNotMade() {
        System.setProperty(Mechanics.FLAG, "true");
        System.setProperty(Mechanics.REAL_RUNS_FLAG, "0");
        EventInstanceManager refused = kpq(1, 8_200);

        assertNull(MechanicsRuns.onInstanceStarted(refused, false), "the pool admits none: no run is made");
        assertNull(MechanicsRuns.get().runFor(refused));
        assertEquals(0, RealRunPool.get().held());
        assertTrue(ticks.isEmpty(), "and nothing was scheduled");

        System.setProperty(Mechanics.REAL_RUNS_FLAG, "1");
        EventInstanceManager admitted = kpq(2, 8_300);
        MechanicsRuns.Active a = MechanicsRuns.onInstanceStarted(admitted, false);
        assertNotNull(a, lines.toString());
        assertEquals(Origin.BOT_INSTANCE, a.origin());
        assertTrue(a.leased());
        assertEquals(1, RealRunPool.get().held());
        assertNull(MechanicsRuns.onInstanceStarted(kpq(3, 8_400), false), "the one place is taken");

        MechanicsRuns.get().stop(a, "done");
        assertEquals(0, RealRunPool.get().held());
    }

    @Test
    void withTheSwitchUnsetABotOnlyInstanceStartsAsItAlwaysDidAndHoldsNoPlace() {
        System.setProperty(Mechanics.FLAG, "true");

        MechanicsRuns.Active a = MechanicsRuns.onInstanceStarted(kpq(1, 8_500), false);
        MechanicsRuns.Active b = MechanicsRuns.onInstanceStarted(kpq(2, 8_600), false);
        MechanicsRuns.Active c = MechanicsRuns.onInstanceStarted(kpq(3, 8_700), false);

        assertNotNull(a);
        assertNotNull(b);
        assertNotNull(c, "unlimited: every bot-only run is admitted");
        assertFalse(a.leased() || b.leased() || c.leased(), "and none of them is leased");
        assertEquals(0, RealRunPool.get().held());
        assertTrue(MechanicsRuns.get().status().stream().noneMatch(l -> l.contains("real-run pool")),
                "status reads exactly as it did: " + MechanicsRuns.get().status());
    }

    @Test
    void statusShowsThePoolOnlyWhileItCounts() {
        System.setProperty(Mechanics.FLAG, "true");
        System.setProperty(Mechanics.REAL_RUNS_FLAG, "2");
        MechanicsRuns.Active a = MechanicsRuns.onInstanceStarted(kpq(1, 8_800), false);
        assertNotNull(a, lines.toString());

        List<String> status = MechanicsRuns.get().status();

        assertTrue(status.stream().anyMatch(l -> l.startsWith("real-run pool: 1 of 2 held: kpq (BOT_INSTANCE)")),
                status.toString());
    }
}
