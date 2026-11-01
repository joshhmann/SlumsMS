package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The bots' share of the connection pool. A cold boot of 1,924 bots once took all ten connections with
 * template loads that each waited on a nested one, and players logging in timed out behind them.
 */
class BotDbGateTest {

    @Test
    void aPlayerLoginAlwaysHasRoomLeftInThePool() {
        assertTrue(DatabaseConnection.MAX_POOL_SIZE - BotDbGate.PERMITS >= BotDbGate.CHARACTER_LOAD,
                "a player login nests a character load deep, and bots must never be able to take that room");
        assertTrue(BotDbGate.PERMITS >= BotDbGate.CHARACTER_LOAD, "a bot template load must fit in the bots' share");
    }

    @Test
    void aMassSpawnNeverHoldsMoreThanTheBotsShare() throws Exception {
        // Platform threads may wait for the share (the login, save and bot-db workers all are platform threads):
        // however many spawns pile up, no more than the share is ever held and every spawn eventually runs.
        AtomicInteger inUse = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        BotDbGate.useMaxWaitForTest(10_000);
        try (ExecutorService spawns = Executors.newFixedThreadPool(32)) {
            List<Future<Integer>> loads = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                loads.add(spawns.submit(() -> BotDbGate.hold(BotDbGate.CHARACTER_LOAD, () -> {
                    int now = inUse.addAndGet(BotDbGate.CHARACTER_LOAD);
                    peak.accumulateAndGet(now, Math::max);
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    inUse.addAndGet(-BotDbGate.CHARACTER_LOAD);
                    return 1;
                })));
            }
            for (Future<Integer> load : loads) {
                assertEquals(1, load.get(60, TimeUnit.SECONDS));
            }
        } finally {
            BotDbGate.useMaxWaitForTest(0);
        }
        assertTrue(peak.get() <= BotDbGate.PERMITS, "peak " + peak.get() + " over the bots' share");
        assertEquals(BotDbGate.PERMITS, BotDbGate.available(), "every permit came back");
    }

    @Test
    void aVirtualThreadStormIsRefusedAndFinishesAtOnce() throws Exception {
        // Bot ticks and logins run on virtual threads. Since 1.6.2 they are never queued on the gate, because a
        // virtual thread parked inside a monitor pins the carrier that would hand the permits back. A 200-wide storm
        // therefore either runs inside the share or is refused, and it is over in milliseconds either way.
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        AtomicInteger inUse = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        long started = System.nanoTime();
        try (ExecutorService spawns = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> loads = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                loads.add(spawns.submit(() -> {
                    try {
                        BotDbGate.hold(BotDbGate.CHARACTER_LOAD, () -> {
                            int now = inUse.addAndGet(BotDbGate.CHARACTER_LOAD);
                            peak.accumulateAndGet(now, Math::max);
                            ran.incrementAndGet();
                            try {
                                Thread.sleep(1);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            inUse.addAndGet(-BotDbGate.CHARACTER_LOAD);
                            return null;
                        });
                    } catch (SQLException busy) {
                        refused.incrementAndGet();
                    }
                }));
            }
            for (Future<?> load : loads) {
                load.get(30, TimeUnit.SECONDS);
            }
        }
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals(200, ran.get() + refused.get(), "every spawn either ran or was refused, none hung");
        assertTrue(ran.get() > 0, "some of the storm ran");
        assertTrue(peak.get() <= BotDbGate.PERMITS, "peak " + peak.get() + " over the bots' share");
        assertTrue(tookMs < 10_000, "200 refused-or-run spawns took " + tookMs + " ms");
        assertEquals(BotDbGate.PERMITS, BotDbGate.available(), "every permit came back");
    }

    @Test
    void aConnectionGivesItsPermitBackWhenClosedAndOnlyOnce() throws SQLException {
        Connection pooled = mock(Connection.class);
        Connection con = BotDbGate.connection(() -> pooled);
        assertEquals(BotDbGate.PERMITS - 1, BotDbGate.available());

        con.close();
        con.close();

        verify(pooled, times(1)).close();
        assertEquals(BotDbGate.PERMITS, BotDbGate.available());
    }

    @Test
    void aPoolThatFailsDoesNotKeepThePermit() {
        assertThrows(SQLException.class, () -> BotDbGate.connection(() -> {
            throw new SQLException("pool timed out");
        }));
        assertEquals(BotDbGate.PERMITS, BotDbGate.available());
    }

    @Test
    void workAlreadyInsideTheGateIsNotMadeToWaitOnItself() throws SQLException {
        // With the whole share held by this thread, a nested request that waited for a permit would wait forever.
        Connection pooled = mock(Connection.class);
        Connection nested = BotDbGate.hold(BotDbGate.PERMITS, () -> {
            assertEquals(0, BotDbGate.available());
            return BotDbGate.connection(() -> pooled);
        });
        assertSame(pooled, nested, "a nested connection runs straight on the pool");
        assertEquals(BotDbGate.PERMITS, BotDbGate.available());
    }

    @Test
    void workThatCannotGetRoomFailsInsteadOfTakingAPlayerConnection() throws Exception {
        // Live 1.6.0.4 with 3,000 bots (2026-11-01): a login waited out the gate and then went to the pool
        // uncounted anyway, so the pool sat with hundreds of borrowers and players waited tens of seconds.
        // The share is a hard cap now: no room, no work.
        Connection pooled = mock(Connection.class);
        AtomicInteger poolAsked = new AtomicInteger();
        BotStore.ConnectionSource source = () -> {
            poolAsked.incrementAndGet();
            return pooled;
        };
        BotDbGate.useMaxWaitForTest(50);
        try (ExecutorService other = Executors.newSingleThreadExecutor()) {
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);
            Future<?> holder = other.submit(() -> BotDbGate.hold(BotDbGate.PERMITS, () -> {
                holding.countDown();
                try {
                    done.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            holding.await();
            try {
                assertThrows(SQLException.class, () -> BotDbGate.connection(source),
                        "no room in the bots' share, so no connection from the pool");
                assertThrows(SQLException.class, () -> BotDbGate.hold(BotDbGate.CHARACTER_LOAD, () -> "ran"),
                        "a load without room does not run either");
                assertEquals(0, poolAsked.get(), "the pool was never asked");
                assertEquals(0, BotDbGate.available(), "nothing was taken");
            } finally {
                done.countDown();
            }
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            BotDbGate.useMaxWaitForTest(0);
        }
        assertEquals(BotDbGate.PERMITS, BotDbGate.available());
    }

    @Test
    void aVirtualThreadNeverWaitsForTheBotsShare() throws Exception {
        // On Java 21 a virtual thread that blocks inside a synchronized block pins its carrier, and a pinned
        // carrier cannot run the threads that give the permits back. The gate cannot see monitors, so it never
        // makes a virtual thread wait - even with a 10 s wait configured it answers at once.
        Connection pooled = mock(Connection.class);
        BotDbGate.useMaxWaitForTest(10_000);
        try (ExecutorService other = Executors.newSingleThreadExecutor()) {
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);
            Future<?> holder = other.submit(() -> BotDbGate.hold(BotDbGate.PERMITS, () -> {
                holding.countDown();
                try {
                    done.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            holding.await();
            try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
                long started = System.nanoTime();
                Future<Boolean> refused = virtual.submit(() -> {
                    try {
                        BotDbGate.connection(() -> pooled);
                        return false;
                    } catch (SQLException expected) {
                        return true;
                    }
                });
                assertTrue(refused.get(5, TimeUnit.SECONDS), "the virtual thread was refused, not queued");
                long waitedMs = (System.nanoTime() - started) / 1_000_000;
                assertTrue(waitedMs < 2_000, "a virtual thread did not wait for the share (waited " + waitedMs + " ms)");
            } finally {
                done.countDown();
            }
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            BotDbGate.useMaxWaitForTest(0);
        }
        assertEquals(BotDbGate.PERMITS, BotDbGate.available());
    }

    @Test
    void theMinuteLineShowsTheShareTheRefusalsAndThePool() {
        assertEquals("0 of 6 bot permits in use, 0 refused so far; pool active=0 idle=10 total=10 waiting=0",
                BotDbGate.statsLine(new int[]{6, 6, 0}, new int[]{0, 10, 10, 0}));
        assertEquals("6 of 6 bot permits in use, 17 refused so far; pool not started",
                BotDbGate.statsLine(new int[]{6, 0, 17}, null));
    }
}
