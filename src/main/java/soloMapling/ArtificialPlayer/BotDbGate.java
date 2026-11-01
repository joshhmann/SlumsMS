package soloMapling.ArtificialPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.server.ExecutorServiceManager;
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
 * <b>The share is a hard cap (1.6.2, live 3k livelock).</b> Bot work that cannot get room in time now fails with
 * a {@link SQLException} instead of going to the pool uncounted as 1.6.0 and 1.6.1 let it. The uncounted path
 * made the gate decorative exactly when it mattered: at {@code BOT_ROSTER_PEAK_ONLINE=3000} logins waited out
 * the gate, then piled onto the pool anyway, so the pool sat with hundreds of borrowers and every login took
 * tens of seconds. Hikari's {@code ConcurrentBag.requite} spins while it believes a borrower is waiting, and that
 * spin fed on the pile-up (see the class notes in the ticket); with the cap the pool's borrower count is bounded
 * by six, so the pool never sees a queue to spin on and players keep their four connections.
 * <p>
 * A thread already inside the gate is not counted again: a nested request from it runs straight on the pool.
 * The nesting of a character load is paid for up front ({@link #CHARACTER_LOAD}); anything else nested is one
 * level deep at most, and counting it would let threads that each hold a permit wait on each other forever.
 * For the same reason a read taken under a lock other bot threads queue on (the store's and the roster's
 * one-time loads) goes straight to the pool, and no wait here is unbounded ({@link #acquire}).
 * <p>
 * <b>Virtual threads never wait here.</b> On Java 21 a virtual thread that blocks inside a {@code synchronized}
 * block pins its carrier thread, and the gate cannot see monitors, so it cannot tell a safe wait from a pinning
 * one. A wait would then cost a carrier for as long as the share is busy, and with enough of them the carriers
 * that must run to give the permits back are gone. Bot work that has to wait for the share (a login, the
 * autosave sweep) is therefore run on a platform thread ({@link soloMapling.server.ExecutorServiceManager#runBotDb}),
 * and bot work on a virtual thread takes the non-blocking path: a permit now, or a failure the caller already
 * handles.
 * <p>
 * <b>What the cap does not cover (b269 audit).</b> A login charges {@link #CHARACTER_LOAD} permits for the whole
 * nested load (a second {@code hold} from the same thread is free), so the six-permit share holds two logins, not
 * five; {@code BotPopulationDirector.LOGIN_SLOTS} never binds. Outside this gate there are still one-shot reads that
 * go straight to the pool in eleven bot classes - the roster's and the store's one-time loads (they run under the
 * locks other bot threads queue on, and counting them could deadlock), the yield store's read, and nine cache-miss
 * reads (`MakerPlan`, `BotNames`, `BotRebirth` three times, `OffscreenBossRun`, `BotSellerCaps`, `FamilyQa`,
 * `PlayerWealthIndex`, `LifeMemoryStore`). Each borrows one connection for one statement and none runs per tick, so
 * the pool's borrower count stays bounded; {@link #unmetered} has exactly one caller, the shutdown save sweep
 * (`BotStore.settleAndSaveAll`).
 * <p>
 * A refused request fails, and a virtual thread that had nothing to show for it loses that write: bot memory stamps
 * its notes' {@code LastUsedAt}/{@code FollowedUpAt} at debug level on a refusal, and a refused insert leaves the note
 * in memory only. That is deliberate - making virtual threads wait here is what pinned carriers on 2026-11-01 - and the
 * cure is the paced boot fill ({@code BotPopulationDirector.LoginPace}): refusals should be visible in the minute
 * line, not routine.
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

    /** How long a platform thread waits for room in the bots' share before the work fails. */
    static final long MAX_WAIT_MS = 15_000;

    private static volatile long maxWaitMs = MAX_WAIT_MS;

    private static final long OVERRUN_WARNING_INTERVAL_MS = 60_000;

    private static final Logger log = LoggerFactory.getLogger(BotDbGate.class);

    private static final Semaphore permits = new Semaphore(PERMITS, true);

    /** Bot work the share had no room for, and so never ran. */
    private static final AtomicInteger refusals = new AtomicInteger();
    private static final AtomicLong lastRefusalWarning = new AtomicLong();

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
        acquire(connections);
        mine.addAndGet(connections);
        try {
            return work.run();
        } finally {
            mine.addAndGet(-connections);
            permits.release(connections);
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
        acquire(1);
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

    /** Test seam: a shorter wait before bot work fails ({@code <= 0} restores the default). */
    static void useMaxWaitForTest(long ms) {
        maxWaitMs = ms > 0 ? ms : MAX_WAIT_MS;
    }

    /** For the admin dashboard (ticket 35): the bots' permits, how many are free now, and bot work refused. */
    public static int[] stats() {
        return new int[]{PERMITS, permits.availablePermits(), refusals.get()};
    }

    /** Permits free right now (tests and the GM read-out). */
    static int available() {
        return permits.availablePermits();
    }

    /** Once a minute in the log: this line is how a lane session tells a busy share from a wedged pool. */
    private static final long STATS_LOG_INTERVAL_MS = 60_000;
    private static final AtomicBoolean statsLogStarted = new AtomicBoolean();

    /**
     * Starts the once-a-minute share/pool line; safe to call more than once. Bot work is the only thing this gate
     * guards, so the bot director starts it when it starts.
     */
    static void startStatsLogging() {
        if (!statsLogStarted.compareAndSet(false, true)) {
            return;
        }
        ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(BotDbGate::logStats,
                STATS_LOG_INTERVAL_MS, STATS_LOG_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private static void logStats() {
        try {
            log.info("BotDbGate: {}", statsLine(stats(), DatabaseConnection.poolStats()));
        } catch (RuntimeException e) {
            log.debug("Bot DB share stats unavailable", e);
        }
    }

    /** {@code stats()} is {permits, free, refusals}; {@code pool} is {@code DatabaseConnection.poolStats()}. */
    static String statsLine(int[] stats, int[] pool) {
        String share = (PERMITS - stats[1]) + " of " + PERMITS + " bot permits in use, " + stats[2]
                + " refused so far; pool ";
        if (pool == null) {
            return share + "not started";
        }
        return share + "active=" + pool[0] + " idle=" + pool[1] + " total=" + pool[2] + " waiting=" + pool[3];
    }

    /**
     * Takes {@code connections} of the bots' share, or throws: nothing here ever runs bot work on the pool
     * uncounted.
     * <p>
     * A platform thread waits up to {@link #MAX_WAIT_MS} for room; a virtual thread does not wait at all. A
     * virtual thread that blocked here inside a {@code synchronized} block would pin its carrier, and the gate
     * cannot see monitors - see the class notes.
     */
    private static void acquire(int connections) throws SQLException {
        if (permits.tryAcquire(connections)) {
            return;
        }
        if (!Thread.currentThread().isVirtual()) {
            try {
                if (permits.tryAcquire(connections, maxWaitMs, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while waiting for a bot database connection", e);
            }
        }
        int refused = refusals.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastRefusalWarning.get();
        if (now - last >= OVERRUN_WARNING_INTERVAL_MS && lastRefusalWarning.compareAndSet(last, now)) {
            log.warn("Bot database work found no room in the bots' share of the pool ({} connections, {} free) and"
                    + " was refused; {} times so far this run", PERMITS, permits.availablePermits(), refused);
        }
        throw new SQLException("The bots' share of the database pool (" + PERMITS + " connections, "
                + PLAYER_RESERVE + " kept for players) is busy; bot work was refused rather than queued on the pool");
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
