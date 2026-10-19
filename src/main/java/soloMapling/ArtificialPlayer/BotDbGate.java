package soloMapling.ArtificialPlayer;

import tools.DatabaseConnection;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    private static final Semaphore permits = new Semaphore(PERMITS, true);

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

    /** Permits free right now (tests and the GM read-out). */
    static int available() {
        return permits.availablePermits();
    }

    private static void acquire(int connections) throws SQLException {
        try {
            permits.acquire(connections);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for a bot database connection", e);
        }
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
