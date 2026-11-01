package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.DatabaseConnection;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 1.6.0.4 livelock, as a test. Live on 2026-11-01 02:08 UTC booted 3,000 bots: every bot's database work queued
 * on the connection pool behind the bots' share of it, and a player's login borrow waited 629 seconds inside a 30 s
 * timeout while the pool reported {@code waiting=267}. Three platform threads were spinning in
 * {@code ConcurrentBag.requite} because their virtual-thread borrowers never reached the handoff queue.
 *
 * <p>These tests put a real HikariCP pool (the same 10 connections) under a storm of bot workers and time a player's
 * ungated borrow the way a login does. The pre-fix gate let a refused bot go to the pool anyway, and let virtual
 * threads wait; the same harness against that gate measured a 11.9 s worst login borrow, and against the same pool
 * with the borrowers pinned in monitors, 792 ms. The share is a hard cap now, so the player's borrow must stay in
 * the tens of milliseconds.
 */
class BotDbGateLoadTest {

    private static final int BOTS = 3_000;
    private static final int PER_WORKER = 5;
    /** The storm can outlast the deadline only if a virtual thread is actually waiting on the gate. */
    private static final long STORM_MS = 20_000;
    private static final int LOGIN_BORROWS = 40;
    private static final long LOGIN_GAP_MS = 25;
    /** Generous: the measured worst borrow is a few ms, the pre-fix run 11,897 ms, and CI boxes are slow. */
    private static final long LOGIN_BUDGET_MS = 1_000;

    private HikariDataSource pool;
    private DataSource fake;

    @AfterEach
    void restore() {
        BotDbGate.useMaxWaitForTest(0);
        if (pool != null) {
            pool.close();
            pool = null;
        }
    }

    /** A connection source that answers like the JDBC driver does for the calls HikariCP makes, and nothing else. */
    private static Object noop(Class<?> type) {
        return Proxy.newProxyInstance(BotDbGateLoadTest.class.getClassLoader(), new Class<?>[]{type},
                (p, m, a) -> switch (m.getName()) {
                    case "close", "addBatch", "clearBatch", "setAutoCommit", "setFetchSize", "setQueryTimeout",
                         "setCatalog", "setSchema", "setTransactionIsolation", "setReadOnly", "setHoldability",
                         "abort", "setNetworkTimeout" -> null;
                    case "isClosed", "isWrapperFor", "isReadOnly", "isValid" -> false;
                    case "equals" -> p == a[0];
                    case "hashCode" -> System.identityHashCode(p);
                    case "toString" -> "noop";
                    case "getAutoCommit", "getReadOnly" -> true;
                    case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                    case "getHoldability" -> 2;
                    case "prepareStatement", "prepareCall" -> noop(java.sql.PreparedStatement.class);
                    case "createStatement" -> noop(java.sql.Statement.class);
                    default -> blank(m.getReturnType());
                });
    }

    /** A type-correct default for a mocked JDBC call: 0, false, or null. */
    private static Object blank(Class<?> type) {
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == boolean.class) {
            return false;
        }
        return null;
    }

    private void realPoolOfTen() {
        fake = (DataSource) Proxy.newProxyInstance(BotDbGateLoadTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (p, m, a) -> "getConnection".equals(m.getName()) ? noop(Connection.class) : blank(m.getReturnType()));
        HikariConfig cfg = new HikariConfig();
        cfg.setDataSource(fake);
        cfg.setPoolName("bot-db-gate-load-test");
        cfg.setMaximumPoolSize(DatabaseConnection.MAX_POOL_SIZE);
        cfg.setMinimumIdle(DatabaseConnection.MAX_POOL_SIZE);
        cfg.setConnectionTimeout(30_000);
        pool = new HikariDataSource(cfg);
    }

    @Test
    void aPlayerLoginGetsAConnectionWhileThreeThousandBotsWork() throws Exception {
        realPoolOfTen();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger inShare = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicLong ran = new AtomicLong();
        AtomicLong refused = new AtomicLong();

        try (ExecutorService bots = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> workers = new ArrayList<>();
            for (int i = 0; i < BOTS; i++) {
                workers.add(bots.submit(() -> {
                    for (int n = 0; n < PER_WORKER && !stop.get(); n++) {
                        try (Connection con = BotDbGate.connection(() -> pool.getConnection())) {
                            int now = inShare.incrementAndGet();
                            peak.accumulateAndGet(now, Math::max);
                            ran.incrementAndGet();
                            try {
                                Thread.sleep(1); // the query
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            inShare.decrementAndGet();
                        } catch (SQLException noRoom) {
                            refused.incrementAndGet();
                        }
                    }
                }));
            }

            // The player: a platform thread taking a connection straight from the pool, the way a login's
            // Character.loadCharFromDB does, once every LOGIN_GAP_MS, while the bots hammer their share.
            AtomicLong worstBorrowMs = new AtomicLong();
            AtomicInteger bids = new AtomicInteger();
            try (ExecutorService logins = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "player-login");
                t.setDaemon(true);
                return t;
            })) {
                for (int i = 0; i < LOGIN_BORROWS; i++) {
                    long started = System.nanoTime();
                    Future<Long> bid = logins.submit(() -> {
                        try (Connection con = pool.getConnection()) {
                            return System.nanoTime();
                        }
                    });
                    long tookMs = TimeUnit.NANOSECONDS.toMillis(bid.get(30, TimeUnit.SECONDS) - started);
                    worstBorrowMs.accumulateAndGet(tookMs, Math::max);
                    bids.incrementAndGet();
                    Thread.sleep(LOGIN_GAP_MS);
                }
            } finally {
                stop.set(true);
            }
            for (Future<?> worker : workers) {
                worker.get(STORM_MS, TimeUnit.MILLISECONDS);
            }

            assertEquals(LOGIN_BORROWS, bids.get());
            assertTrue(ran.get() > 0, "the bots did work through the gate");
            assertTrue(refused.get() > 0, "a storm of " + BOTS + " bots outnumbers the share, so some were refused");
            assertTrue(peak.get() <= BotDbGate.PERMITS,
                    "peak " + peak.get() + " connections in bot work, over the bots' share of " + BotDbGate.PERMITS);
            assertTrue(worstBorrowMs.get() < LOGIN_BUDGET_MS,
                    "a player's borrow waited " + worstBorrowMs.get() + " ms behind " + BOTS + " bots ("
                            + ran.get() + " ran, " + refused.get() + " refused)");
            assertEquals(BotDbGate.PERMITS, BotDbGate.available(), "every permit came back");
        }
    }

    /**
     * The population tick at 3,000 bots: one gossip round reads its tellers' notes in one statement, so the pool work
     * of a tick does not grow with how many bots are online.
     */
    @Test
    void aPopulationTickReadsOnceHoweverManyBotsAreOnline() {
        FakeMemoryTable memories = new FakeMemoryTable(false);
        memories.install();
        try {
            Map<Integer, List<Character>> byMap = new HashMap<>();
            for (int i = 0; i < BOTS; i++) {
                Character c = Character.getDefault(new BotClient(0, 0));
                c.setID(BotStore.BOT_ID_BASE + i + 1);
                c.setName("bot" + (i + 1));
                c.setLevel(35);
                byMap.computeIfAbsent(100_000_000 + (i % 250), k -> new ArrayList<>()).add(c);
            }
            BotGossip.step(byMap, System.currentTimeMillis(), new Random(7));
            assertEquals(1, memories.batchReads.get(),
                    "one read for a round of " + BotGossip.TELLERS_PER_STEP + " tellers out of " + BOTS + " bots");
        } finally {
            BotMemory.useConnectionSourceForTest(null);
        }
    }
}
