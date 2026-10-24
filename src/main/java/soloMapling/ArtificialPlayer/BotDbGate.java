package soloMapling.ArtificialPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The share of the server's connection pool that bots may hold at once.
 * <p>
 * A cold boot spawns hundreds of bots on virtual threads, and every one of them loads the template character
 * and writes its store row. Left to the pool directly they took all ten connections - and since a character
 * load holds its own connection while its item, cash-shop and pet loads each take another, ten spawns holding
 * one each then waited thirty seconds for a second that never came, while players logging in waited behind
 * them. So bot database work asks this gate first: it waits here, holding nothing, until there is room in the
 * bots' share, and {@link #PLAYER_RESERVE} connections always stay out of reach of bots.
 * <p>
 * A thread already inside the gate is not counted again: a nested request from it runs straight on the pool.
 * The nesting of a character load is paid for up front ({@link #CHARACTER_LOAD}); anything else nested is one
 * level deep at most, and counting it would let threads that each hold a permit wait on each other forever.
 * For the same reason a read taken under a lock other bot threads queue on (the store's and the roster's
 * one-time loads) goes straight to the pool, and no wait here is unbounded ({@link #acquire}).
 */
public final class BotDbGate {

    /**
     * Connections a character load can hold at once: its own, an item or cash-shop load inside it, and a
     * pet load inside that.
     */
    public static final int CHARACTER_LOAD = 3;

    /** Connections an item-table load can hold at once: its own and a pet load inside it. */
    public static final int ITEM_LOAD = 2;

    /**
     * Connections no bot may take. A player login nests {@link #CHARACTER_LOAD} deep, so this lets one
     * through however busy the bots are, with one to spare for the server's own tasks.
     */
    static final int PLAYER_RESERVE = CHARACTER_LOAD + 1;

    static final int PERMITS = Math.max(CHARACTER_LOAD, DatabaseConnection.MAX_POOL_SIZE - PLAYER_RESERVE);

    /** How long bot work waits for room before going to the pool uncounted (see {@link #acquire}). */
    static final long MAX_WAIT_MS = 15_000;

    private static volatile long maxWaitMs = MAX_WAIT_MS;

    private static final long OVERRUN_WARNING_INTERVAL_MS = 60_000;

    private static final Logger log = LoggerFactory.getLogger(BotDbGate.class);

    private static final Semaphore permits = new Semaphore(PERMITS, true);

    private static final AtomicInteger overruns = new AtomicInteger();
    private static final AtomicLong lastOverrunWarning = new AtomicLong();

    /** Permits the current thread holds; shared with the connections it opened, which give theirs back. */
    private static final ThreadLocal<AtomicInteger> held = ThreadLocal.withInitial(AtomicInteger::new);

    @FunctionalInterface
    public interface SqlWork<T> {
        T run() throws SQLException;
    }

    private BotDbGate() {
    }

    /**
     * Runs work that takes its connections from the pool itself (a character or item load), holding
     * {@code connections} of the bots' share for as long as it runs.
     */
    public static <T> T hold(int connections, SqlWork<T> work) throws SQLException {
        AtomicInteger mine = held.get();
        if (mine.get() > 0) {
            return work.run();
        }
        boolean counted = acquire(connections);
        mine.addAndGet(connections);
        try {
            return work.run();
        } finally {
            mine.addAndGet(-connections);
            if (counted) {
                permits.release(connections);
            }
        }
    }

    /**
     * Runs work whose database calls go straight to the pool, outside the bots' share, on this thread only.
     * <p>
     * For the shutdown sweep (1.5.0 rehearsal): by then the players have been saved and bot activity has been
     * stopped, so the share has nothing left to protect - but bot work still in flight (a boot fill's logins,
     * a tick the drain gave up on) can keep holding it, and the sweep then waited {@link #MAX_WAIT_MS} for every
     * bot it saved until systemd killed the server. The sweep is a handful of writers; the pool's own timeout
     * bounds it.
     */
    public static <T> T unmetered(SqlWork<T> work) throws SQLException {
        AtomicInteger mine = held.get();
        // Counted as a permit this thread holds without having taken it from the semaphore: every request below
        // takes the nested path (straight to the pool), and nothing is released for it.
        mine.incrementAndGet();
        try {
            return work.run();
        } finally {
            mine.decrementAndGet();
        }
    }

    /** {@link #unmetered(SqlWork)} for work that throws no checked exception. */
    public static void unmetered(Runnable work) {
        AtomicInteger mine = held.get();
        mine.incrementAndGet();
        try {
            work.run();
        } finally {
            mine.decrementAndGet();
        }
    }

    /** A pooled connection that holds one permit of the bots' share until it is closed. */
    public static Connection connection() throws SQLException {
        return connection(DatabaseConnection::getConnection);
    }

    static Connection connection(BotStore.ConnectionSource pool) throws SQLException {
        AtomicInteger mine = held.get();
        if (mine.get() > 0) {
            return pool.get();
        }
        if (!acquire(1)) {
            return pool.get();
        }
        Connection con;
        try {
            con = pool.get();
        } catch (SQLException | RuntimeException e) {
            permits.release();
            throw e;
        }
        mine.incrementAndGet();
        return releasingOnClose(con, mine);
    }

    /** Test seam: the gate in front of a mock pool, as {@link #connection()} is in front of the real one. */
    static BotStore.ConnectionSource gatedForTest(BotStore.ConnectionSource pool) {
        return () -> connection(pool);
    }

    /** Test seam: a shorter wait before going to the pool uncounted ({@code <= 0} restores the default). */
    static void useMaxWaitForTest(long ms) {
        maxWaitMs = ms > 0 ? ms : MAX_WAIT_MS;
    }

    /** For the admin dashboard (ticket 35): the bots' permits, how many are free now, and waits that gave up. */
    public static int[] stats() {
        return new int[]{PERMITS, permits.availablePermits(), overruns.get()};
    }

    /** Permits free right now (tests and the GM read-out). */
    static int available() {
        return permits.availablePermits();
    }

    /**
     * Waits for room in the bots' share. Gives up after {@link #MAX_WAIT_MS} and lets the caller go to the pool
     * uncounted (returning false): on Java 21 a virtual thread that waits here inside a {@code synchronized}
     * block pins its carrier, and with every carrier pinned the threads holding the permits can never run to
     * give them back. The pool's own timeout is the backstop then, as it was before the gate.
     */
    private static boolean acquire(int connections) throws SQLException {
        try {
            if (permits.tryAcquire(connections, maxWaitMs, TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for a bot database connection", e);
        }
        int missed = overruns.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastOverrunWarning.get();
        if (now - last >= OVERRUN_WARNING_INTERVAL_MS && lastOverrunWarning.compareAndSet(last, now)) {
            log.warn("Bot database work waited {} ms for the bots' share of the pool ({} permits) and went to the"
                    + " pool uncounted; {} times so far this run", maxWaitMs, PERMITS, missed);
        }
        return false;
    }

    /** Wraps a connection so closing it - once - also gives its permit back. */
    private static Connection releasingOnClose(Connection con, AtomicInteger owner) {
        AtomicBoolean closed = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close") && method.getParameterCount() == 0) {
                        if (closed.compareAndSet(false, true)) {
                            try {
                                con.close();
                            } finally {
                                owner.decrementAndGet();
                                permits.release();
                            }
                        }
                        return null;
                    }
                    try {
                        return method.invoke(con, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
