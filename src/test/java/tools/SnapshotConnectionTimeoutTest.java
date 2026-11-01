package tools;

import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Follow-up to ticket 174: the snapshot connections are outside Hikari, so the pool's 10 s borrow bound does not
 * reach them. They open with {@code connectTimeout} and read with {@code socketTimeout} of their own, because the
 * reads that use them run while holding {@code LOAD_LOCK} / {@code ROSTER_LOCK} - the monitors every spawning bot
 * queues on. Without those bounds a database host that accepts TCP and then says nothing holds those monitors for
 * the OS TCP timeout, which is minutes.
 *
 * <p>The tests here are offline: a loopback {@link ServerSocket} accepts the connection and never speaks, which is
 * what a black-holed database looks like from the driver. The bounds are shortened through their system properties
 * so the test takes under a second on the fixed code; on unmodified code the properties were not read at all, the
 * driver waits forever, and the first test fails on its deadline instead of passing.
 */
class SnapshotConnectionTimeoutTest {

    /** How long the whole bounded open may take here: the properties below say 600 ms, this is CI headroom. */
    private static final long DEADLINE_MS = 10_000;

    private static final int SHORT_BOUND_MS = 600;

    /**
     * The property names, spelled out rather than read from {@code DatabaseConnection}: this file has to compile
     * against the commit before the change to be the fail-before test (there the properties were not read at all, so
     * the driver waits forever and the deadline below is what fails).
     */
    private static final String CONNECT_TIMEOUT_PROPERTY = "slums.db.snapshotConnectTimeoutMs";
    private static final String SOCKET_TIMEOUT_PROPERTY = "slums.db.snapshotSocketTimeoutMs";

    private ServerSocket silent;
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();
    private String savedUrlFormat;

    @AfterEach
    void restore() throws IOException {
        if (savedUrlFormat != null) {
            YamlConfig.config.server.DB_URL_FORMAT = savedUrlFormat;
            savedUrlFormat = null;
        }
        System.clearProperty(CONNECT_TIMEOUT_PROPERTY);
        System.clearProperty(SOCKET_TIMEOUT_PROPERTY);
        for (Socket socket : accepted) {
            socket.close();
        }
        accepted.clear();
        if (silent != null) {
            silent.close();
            silent = null;
        }
    }

    @Test
    void aSnapshotConnectionToADatabaseThatNeverAnswersGivesUpWithinItsBound() throws Exception {
        pointAtSilentDatabase();
        System.setProperty(CONNECT_TIMEOUT_PROPERTY, Integer.toString(SHORT_BOUND_MS));
        System.setProperty(SOCKET_TIMEOUT_PROPERTY, Integer.toString(SHORT_BOUND_MS));

        DatabaseConnection.SnapshotConnection snapshot = DatabaseConnection.newSnapshotConnection();
        ExecutorService probe = Executors.newSingleThreadExecutor(runnable -> {
            // Daemon: on unmodified code this thread is stuck in the driver's unbounded read and must not keep the
            // test JVM alive after the failure.
            Thread thread = new Thread(runnable, "snapshot-timeout-probe");
            thread.setDaemon(true);
            return thread;
        });
        long start = System.nanoTime();
        try {
            Future<Connection> attempt = probe.submit(snapshot::get);
            try {
                Connection opened = attempt.get(DEADLINE_MS, TimeUnit.MILLISECONDS);
                opened.close();
                fail("a snapshot connection was opened against a database that never answers");
            } catch (TimeoutException e) {
                fail("the snapshot connection did not give up: still open after " + DEADLINE_MS + " ms, so its open"
                        + " is unbounded and would hold the caller's monitor for the OS TCP timeout");
            } catch (ExecutionException e) {
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                assertAll("the open fails loudly and inside the bound",
                        () -> assertInstanceOf(SQLException.class, e.getCause(),
                                "a database that never answers must fail as a SQLException, as it did before"),
                        () -> assertTrue(elapsedMs < DEADLINE_MS,
                                "the open took " + elapsedMs + " ms, past the " + DEADLINE_MS + " ms deadline"));
            }
        } finally {
            // No snapshot.close() here: on unmodified code the probe thread is stuck inside the synchronized get(),
            // so close() would block on that monitor and hang the test instead of failing it.
            probe.shutdownNow();
        }
    }

    /**
     * Points the snapshot connection at a loopback socket that accepts and then says nothing. The URL carries no
     * {@code %s}, so the {@code DB_HOST} environment override in {@code getDbUrl()} cannot redirect it elsewhere.
     */
    private void pointAtSilentDatabase() throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        silent = server;
        Thread acceptor = new Thread(() -> {
            try {
                while (!server.isClosed()) {
                    accepted.add(server.accept());
                }
            } catch (IOException e) {
                // The test is over and the socket is closed; nothing left to accept.
            }
        }, "silent-database");
        acceptor.setDaemon(true);
        acceptor.start();

        savedUrlFormat = YamlConfig.config.server.DB_URL_FORMAT;
        YamlConfig.config.server.DB_URL_FORMAT = "jdbc:mysql://127.0.0.1:" + server.getLocalPort() + "/slumsms";
    }
}
