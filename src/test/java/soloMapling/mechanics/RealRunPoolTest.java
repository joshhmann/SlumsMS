package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.RealRunPool.Admission;
import soloMapling.mechanics.RealRunPool.Origin;

/**
 * Phase 2 of the simulated-runs plan, the quota half: a global pool of leases for bot-only runs that play for real
 * ({@code -Dslums.mechanics.realruns=N}). The load-bearing facts, each pinned here:
 * <ul>
 *   <li>unset (or negative) is today's behaviour: every run is admitted and <b>no lease exists</b>;</li>
 *   <li>a place is held until its admission is released, released once however many times it is asked, and a late or
 *   repeated release never frees somebody else's place;</li>
 *   <li>a run with a human in it and a GM's staged run are never counted and never refused;</li>
 *   <li>a lease that is never released is taken back, loudly, when the run's own clock plus its after-clear budget is
 *   over, so a leak cannot shrink the quota for good;</li>
 *   <li>lowering the quota never evicts a run that is already playing.</li>
 * </ul>
 */
class RealRunPoolTest {

    private final AtomicInteger cap = new AtomicInteger(-1);
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final RealRunPool pool = new RealRunPool(cap::get, now::get);

    @AfterEach
    void clearProperty() {
        System.clearProperty(Mechanics.REAL_RUNS_FLAG);
    }

    private static Content kpq() {
        return KpqStageDataTest.kpq();
    }

    // ---- the switch -------------------------------------------------------------------------------------

    @Test
    void theSwitchIsUnlimitedUnlessItIsANonNegativeNumber() {
        assertEquals(-1, Mechanics.realRunCapacity(), "unset is unlimited");
        for (String unlimited : new String[] {"", "  ", "abc", "-1", "-7", "1.5", "two"}) {
            System.setProperty(Mechanics.REAL_RUNS_FLAG, unlimited);
            assertTrue(Mechanics.realRunCapacity() < 0, "'" + unlimited + "' is unlimited, not a quota");
        }
        System.setProperty(Mechanics.REAL_RUNS_FLAG, "0");
        assertEquals(0, Mechanics.realRunCapacity());
        System.setProperty(Mechanics.REAL_RUNS_FLAG, " 2 ");
        assertEquals(2, Mechanics.realRunCapacity());
        assertEquals("slums.mechanics.realruns", Mechanics.REAL_RUNS_FLAG, "the switch keeps the family's style");
    }

    @Test
    void theSingletonReadsTheSwitchOnEveryAsk() {
        assertEquals(-1, RealRunPool.get().capacity());
        System.setProperty(Mechanics.REAL_RUNS_FLAG, "2");
        assertEquals(2, RealRunPool.get().capacity(), "an operator can change it without a restart");
    }

    // ---- unlimited is phase 1 ----------------------------------------------------------------------------

    @Test
    void unsetAdmitsEveryRunAndMakesNoLease() {
        cap.set(-1);
        List<Admission> all = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            for (Origin origin : Origin.values()) {
                all.add(pool.admit(origin, kpq(), 0));
            }
        }
        for (Admission a : all) {
            assertTrue(a.granted(), "every run is admitted while the pool is unlimited");
            assertNull(a.why());
            assertFalse(a.leased(), "and none of them holds a place");
        }
        assertEquals(0, pool.held(), "no lease exists: nothing is tracked");
        assertNull(pool.describe(), "status says nothing about a pool that does not count");
        all.forEach(Admission::release); // a no-op, not an error
        assertEquals(0, pool.held());
    }

    // ---- a quota ------------------------------------------------------------------------------------------

    @Test
    void aQuotaAdmitsThatManyAndRefusesTheNext() {
        cap.set(2);
        Admission a = pool.admit(Origin.KPQ_LOBBY, kpq(), 0);
        Admission b = pool.admit(Origin.CONTENT_LOBBY, kpq(), 0);
        Admission c = pool.admit(Origin.KPQ_LOBBY, kpq(), 0);

        assertTrue(a.granted() && a.leased());
        assertTrue(b.granted() && b.leased());
        assertFalse(c.granted(), "the third is refused");
        assertFalse(c.leased());
        assertNotNull(c.why());
        assertTrue(c.why().contains("2 of 2"), c.why());
        assertTrue(c.why().contains("kpq (KPQ_LOBBY)"), "the refusal names who holds the places: " + c.why());
        assertEquals(2, pool.held(), "a refusal takes nothing");
        assertEquals("real-run pool: 2 of 2 held: kpq (KPQ_LOBBY) 0 min, kpq (CONTENT_LOBBY) 0 min", pool.describe());
    }

    @Test
    void aReleaseFreesExactlyOnePlaceHoweverOftenItIsAsked() {
        cap.set(2);
        Admission a = pool.admit(Origin.KPQ_LOBBY, kpq(), 0);
        Admission b = pool.admit(Origin.KPQ_LOBBY, kpq(), 0);

        a.release();
        a.release();
        a.release();

        assertEquals(1, pool.held(), "three releases of one admission free one place, not three");
        assertFalse(a.leased());
        assertTrue(b.leased(), "the other run still holds its own");
        Admission c = pool.admit(Origin.KPQ_LOBBY, kpq(), 0);
        assertTrue(c.granted(), "the freed place is taken");
        assertFalse(pool.admit(Origin.KPQ_LOBBY, kpq(), 0).granted(), "and the pool is full again, at two");
    }

    @Test
    void aQuotaOfZeroAdmitsNoBotOnlyRun() {
        cap.set(0);
        for (Origin origin : new Origin[] {Origin.KPQ_LOBBY, Origin.CONTENT_LOBBY, Origin.BOT_INSTANCE}) {
            Admission a = pool.admit(origin, kpq(), 0);
            assertFalse(a.granted(), origin + " is refused");
            assertTrue(a.why().contains("admits none") && a.why().contains("realruns=0"), a.why());
        }
        assertEquals(0, pool.held());
    }

    @Test
    void aHumansRunAndAStagedRunAreNeverCountedAndNeverRefused() {
        cap.set(0);
        for (Origin origin : new Origin[] {Origin.HUMAN_PARTY, Origin.HUMAN_EXPEDITION, Origin.STAGED}) {
            Admission a = RealRunPool.exempt(origin);
            assertTrue(a.granted(), origin + " plays whatever the quota says");
            assertFalse(a.leased(), origin + " holds no place");
            assertEquals(origin, a.origin());
            a.release(); // nothing to give back
        }
        assertEquals(0, pool.held());

        cap.set(1);
        Admission bots = pool.admit(Origin.KPQ_LOBBY, kpq(), 0);
        assertTrue(RealRunPool.exempt(Origin.STAGED).granted(), "a full pool does not refuse a GM");
        assertTrue(RealRunPool.exempt(Origin.HUMAN_PARTY).granted(), "or a player");
        assertEquals(1, pool.held(), "and they took nothing from the bots' place");
        assertTrue(bots.leased());
    }

    @Test
    void onlyTheBotOnlyOriginsAreGoverned() {
        assertTrue(Origin.KPQ_LOBBY.governed());
        assertTrue(Origin.CONTENT_LOBBY.governed());
        assertTrue(Origin.BOT_INSTANCE.governed());
        assertFalse(Origin.STAGED.governed());
        assertFalse(Origin.HUMAN_PARTY.governed());
        assertFalse(Origin.HUMAN_EXPEDITION.governed());
    }

    @Test
    void loweringTheQuotaNeverEvictsARunThatIsPlaying() {
        cap.set(3);
        Admission a = pool.admit(Origin.KPQ_LOBBY, kpq(), 0);
        Admission b = pool.admit(Origin.KPQ_LOBBY, kpq(), 0);
        Admission c = pool.admit(Origin.KPQ_LOBBY, kpq(), 0);

        cap.set(1);

        assertEquals(3, pool.held(), "the three that are playing keep their places");
        assertTrue(a.leased() && b.leased() && c.leased());
        assertFalse(pool.admit(Origin.KPQ_LOBBY, kpq(), 0).granted(), "but nothing new is admitted over the lowered quota");
        a.release();
        b.release();
        assertFalse(pool.admit(Origin.KPQ_LOBBY, kpq(), 0).granted(), "one held, quota one: still full");
        c.release();
        assertTrue(pool.admit(Origin.KPQ_LOBBY, kpq(), 0).granted(), "empty again: admitted");
    }

    @Test
    void anUnlimitedPoolThatBecomesQuotaedStartsCountingFromThenOn() {
        cap.set(-1);
        Admission before = pool.admit(Origin.KPQ_LOBBY, kpq(), 0);
        cap.set(1);
        assertEquals(0, pool.held(), "a run admitted while unlimited holds nothing, and is never evicted or counted");
        Admission after = pool.admit(Origin.KPQ_LOBBY, kpq(), 0);
        assertTrue(after.leased());
        before.release();
        assertEquals(1, pool.held(), "releasing an uncounted run does not free the counted one's place");
    }

    // ---- a lease that is never released ------------------------------------------------------------------

    @Test
    void aLeaseThatIsNeverReleasedIsTakenBackWhenTheRunsClockIsOver() {
        cap.set(1);
        Admission leaked = pool.admit(Origin.KPQ_LOBBY, kpq(), 0);
        long ttl = RealRunPool.ttlMs(kpq(), 0);

        now.addAndGet(ttl - 1);
        assertEquals(1, pool.held(), "still within the run's own clock plus the after-clear budget");
        assertFalse(pool.admit(Origin.KPQ_LOBBY, kpq(), 0).granted());

        now.addAndGet(1);
        assertEquals(0, pool.held(), "the leaked lease is reaped, loudly");
        Admission next = pool.admit(Origin.KPQ_LOBBY, kpq(), 0);
        assertTrue(next.granted() && next.leased(), "the quota is whole again");

        leaked.release(); // the late release of a reaped lease must not free the next run's place
        assertEquals(1, pool.held());
        assertTrue(next.leased());
    }

    @Test
    void theLeaseOutlivesTheContentsClockByTheAfterClearBudgetAndAMargin() {
        Content kpq = kpq();
        assertEquals(kpq.timeLimitMinutes() * 60_000L + Orchestrator.AFTER_CLEAR_BUDGET_MS + RealRunPool.TTL_MARGIN_MS,
                RealRunPool.ttlMs(kpq, 0));
        assertEquals(5 * 60_000L + Orchestrator.AFTER_CLEAR_BUDGET_MS + RealRunPool.TTL_MARGIN_MS,
                RealRunPool.ttlMs(kpq, 5 * 60_000L), "a run staged with its own budget is held to that budget");
        assertTrue(RealRunPool.ttlMs(kpq, 0) > 30 * 60_000L, "never shorter than the 30 minutes KPQ is given");
    }

    // ---- the refusal log -----------------------------------------------------------------------------------

    @Test
    void aFullPoolAnnouncesItsRefusalOncePerContentAndOriginPerTenMinutes() {
        cap.set(0);
        Admission first = pool.admit(Origin.KPQ_LOBBY, kpq(), 0);
        Admission again = pool.admit(Origin.KPQ_LOBBY, kpq(), 0);
        Admission otherOrigin = pool.admit(Origin.CONTENT_LOBBY, kpq(), 0);

        assertTrue(first.announce(), "the first refusal is said");
        assertFalse(again.announce(), "the next lobby tick's is not");
        assertTrue(otherOrigin.announce(), "a different origin is a different line");

        now.addAndGet(RealRunPool.LOG_GAP_MS - 1);
        assertFalse(pool.admit(Origin.KPQ_LOBBY, kpq(), 0).announce());
        now.addAndGet(1);
        assertTrue(pool.admit(Origin.KPQ_LOBBY, kpq(), 0).announce(), "said again after ten minutes");
        assertFalse(RealRunPool.exempt(Origin.STAGED).announce());
    }

    // ---- concurrency ---------------------------------------------------------------------------------------

    @Test
    void manyLobbiesAskingAtOnceNeverHoldMoreThanTheQuota() throws Exception {
        cap.set(3);
        int threads = 8;
        AtomicInteger highest = new AtomicInteger();
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(ex.submit(() -> {
                    go.await();
                    for (int i = 0; i < 2_000; i++) {
                        Admission a = pool.admit(Origin.CONTENT_LOBBY, kpq(), 0);
                        if (a.granted()) {
                            granted.incrementAndGet();
                            highest.accumulateAndGet(pool.held(), Math::max); // while this run holds its place
                            a.release();
                            a.release();
                        }
                    }
                    return null;
                }));
            }
            go.countDown();
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            ex.shutdownNow();
        }
        assertTrue(highest.get() <= 3, "never more than three places in use at once: " + highest.get());
        assertTrue(granted.get() > 0, "and the pool did hand places out");
        assertEquals(0, pool.held(), "everything given back");
    }
}
