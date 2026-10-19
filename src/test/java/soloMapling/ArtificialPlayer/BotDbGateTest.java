package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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
        AtomicInteger inUse = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        try (ExecutorService spawns = Executors.newVirtualThreadPerTaskExecutor()) {
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
                assertEquals(1, load.get(30, TimeUnit.SECONDS));
            }
        }
        assertTrue(peak.get() <= BotDbGate.PERMITS, "peak " + peak.get() + " over the bots' share");
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
}
