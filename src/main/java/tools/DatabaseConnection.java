package tools;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import config.ServerConfig;
import config.YamlConfig;
import database.note.NoteRowMapper;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author Frz (Big Daddy)
 * @author The Real Spookster - some modifications to this beautiful code
 * @author Ronan - some connection pool to this beautiful code
 */
public class DatabaseConnection {
    private static final Logger log = LoggerFactory.getLogger(DatabaseConnection.class);
    /** Hikari's default. SoloMapling's bots hold at most part of it, leaving the rest to players (BotDbGate). */
    public static final int MAX_POOL_SIZE = 10;
    /**
     * Bound on opening a connection (b269): a pool borrow gives up after this, and so does a snapshot connection - a
     * database that is not answering must fail the caller loudly instead of holding it for the OS TCP timeout.
     */
    static final int CONNECT_TIMEOUT_MS = 10_000;
    /**
     * Bound on one socket read of a snapshot connection (ticket 174's fix, follow-up). Each snapshot read is a single
     * small-table scan: milliseconds on the database host, so 30 s is ~1000x headroom for a loaded server, while a
     * black-holed connection fails in 30 s instead of holding LOAD_LOCK / ROSTER_LOCK for minutes.
     */
    static final int SNAPSHOT_SOCKET_TIMEOUT_MS = 30_000;
    /** How long a snapshot connection may take to open, if a deployment's database is slower than the default. */
    static final String SNAPSHOT_CONNECT_TIMEOUT_PROPERTY = "slums.db.snapshotConnectTimeoutMs";
    /** How long one snapshot socket read may block, if 30 s does not fit a deployment. */
    static final String SNAPSHOT_SOCKET_TIMEOUT_PROPERTY = "slums.db.snapshotSocketTimeoutMs";
    private static HikariDataSource dataSource;
    private static Jdbi jdbi;

    public static Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new IllegalStateException("Unable to get connection - connection pool is uninitialized");
        }

        return dataSource.getConnection();
    }

    public static Handle getHandle() {
        if (jdbi == null) {
            throw new IllegalStateException("Unable to get handle - connection pool is uninitialized");
        }

        return jdbi.open();
    }

    /**
     * Pool use for the admin dashboard (ticket 35): active, idle and total connections and threads waiting for one;
     * null before the pool exists. Read-only.
     */
    public static int[] poolStats() {
        HikariDataSource ds = dataSource;
        var pool = ds == null ? null : ds.getHikariPoolMXBean();
        return pool == null ? null : new int[]{pool.getActiveConnections(), pool.getIdleConnections(),
                pool.getTotalConnections(), pool.getThreadsAwaitingConnection()};
    }

    /**
     * A connection of its own for a one-time snapshot read (ticket 174). It is opened straight from the driver on
     * first use, never taken from the pool and never returned to it: the reads that use one - the bot roster and the
     * bot store's roster - run while holding a monitor every spawning bot queues on, so a borrow that had to wait
     * for a pooled connection there would hold those bots for up to the pool's connection timeout (10 s) and pin
     * the carrier of any virtual thread among them.
     *
     * <p>Being outside the pool, it does not inherit anything the pool sets, so it opens and reads under its own
     * bounds: {@link #CONNECT_TIMEOUT_MS} to open and {@link #SNAPSHOT_SOCKET_TIMEOUT_MS} per socket read.
     */
    public static final class SnapshotConnection {
        private Connection connection;

        private SnapshotConnection() {
        }

        /** The read's own connection, opened on first use (and again after {@link #close()}). */
        public synchronized Connection get() throws SQLException {
            if (connection == null || connection.isClosed()) {
                connection = open();
            }
            return connection;
        }

        /**
         * Opens it straight from the driver, bounded (follow-up to ticket 174): this connection is not Hikari's, so
         * the pool's 10 s borrow bound does not apply to it, and the reads that use it run while holding a monitor
         * every spawning bot queues on - an unbounded open or read there would hold them for the OS TCP timeout.
         * The bounds go in the driver's properties, not appended to the URL: {@code DB_URL_FORMAT} is the admin's
         * and may already carry a query string, and a property here wins over one in the URL.
         */
        private Connection open() throws SQLException {
            Properties properties = new Properties();
            properties.setProperty("user", YamlConfig.config.server.DB_USER);
            properties.setProperty("password", YamlConfig.config.server.DB_PASS);
            properties.setProperty("connectTimeout", Integer.toString(snapshotConnectTimeoutMs()));
            properties.setProperty("socketTimeout", Integer.toString(snapshotSocketTimeoutMs()));
            return DriverManager.getConnection(getDbUrl(), properties);
        }

        /** Closes it at shutdown; a later use opens a new one, which is what a restart needs. */
        public synchronized void close() {
            if (connection == null) {
                return;
            }
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("Could not close a snapshot connection: {}", e.getMessage());
            }
            connection = null;
        }
    }

    private static final Set<SnapshotConnection> snapshotConnections = ConcurrentHashMap.newKeySet();

    /**
     * Hands out a {@link SnapshotConnection} and remembers it, so {@link #closeSnapshotConnections()} at shutdown
     * closes every one that was handed out.
     */
    public static SnapshotConnection newSnapshotConnection() {
        SnapshotConnection connection = new SnapshotConnection();
        snapshotConnections.add(connection);
        return connection;
    }

    /** Closes the snapshot connections (ticket 174): they are not in the pool, so Hikari never returns them. */
    public static void closeSnapshotConnections() {
        for (SnapshotConnection connection : snapshotConnections) {
            connection.close();
        }
    }

    /** How long a snapshot connection may take to open before it gives up. */
    static int snapshotConnectTimeoutMs() {
        return timeoutMs(SNAPSHOT_CONNECT_TIMEOUT_PROPERTY, CONNECT_TIMEOUT_MS);
    }

    /** How long one socket read of a snapshot connection may block. */
    static int snapshotSocketTimeoutMs() {
        return timeoutMs(SNAPSHOT_SOCKET_TIMEOUT_PROPERTY, SNAPSHOT_SOCKET_TIMEOUT_MS);
    }

    /**
     * The property's value, or the fallback. The driver reads 0 as "wait forever", which is exactly what these bounds
     * exist to rule out, so anything that is not a positive number keeps the bounded default.
     */
    private static int timeoutMs(String property, int fallbackMs) {
        String value = System.getProperty(property);
        if (value == null) {
            return fallbackMs;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallbackMs;
        } catch (NumberFormatException e) {
            log.warn("Ignoring {}={} (not a number of milliseconds); using {} ms", property, value, fallbackMs);
            return fallbackMs;
        }
    }

    private static String getDbUrl() {
        // Environment variables override what's defined in the config file
        // This feature is used for the Docker support
        String hostOverride = System.getenv("DB_HOST");
        String host = hostOverride != null ? hostOverride : YamlConfig.config.server.DB_HOST;
        String dbUrl = String.format(YamlConfig.config.server.DB_URL_FORMAT, host);
        return dbUrl;
    }

    /** The pool's connection timeout when the config says nothing: Hikari's own 30 s, and what this pool ran on first. */
    static final int DEFAULT_CONNECTION_TIMEOUT_MS = 30_000;

    /** Hikari refuses a connection timeout under 250 ms. */
    private static final int MIN_CONNECTION_TIMEOUT_MS = 250;

    /**
     * How long a thread waits for a connection from the pool before its work fails ({@code DB_CONNECTION_TIMEOUT_MS},
     * {@link #DEFAULT_CONNECTION_TIMEOUT_MS} when unset). b269 hard-coded 10 s so that a wedged pool fails its
     * borrowers loudly and quickly instead of letting a player login sit for half a minute; that is still the
     * setting to use on live, but a busy boot fill is not a wedged pool and the choice belongs in config.
     */
    static int connectionTimeoutMs() {
        ServerConfig server = YamlConfig.config == null ? null : YamlConfig.config.server;
        return connectionTimeoutMs(server == null ? 0 : server.DB_CONNECTION_TIMEOUT_MS);
    }

    /** What the pool is told to wait, from the config value: unset keeps Hikari's default, a silly value is floored. */
    static int connectionTimeoutMs(int configured) {
        return configured <= 0 ? DEFAULT_CONNECTION_TIMEOUT_MS : Math.max(MIN_CONNECTION_TIMEOUT_MS, configured);
    }

    private static HikariConfig getConfig() {
        HikariConfig config = new HikariConfig();

        config.setJdbcUrl(getDbUrl());
        config.setUsername(YamlConfig.config.server.DB_USER);
        config.setPassword(YamlConfig.config.server.DB_PASS);

        final int initFailTimeoutSeconds = YamlConfig.config.server.INIT_CONNECTION_POOL_TIMEOUT;
        config.setInitializationFailTimeout(SECONDS.toMillis(initFailTimeoutSeconds));
        config.setConnectionTimeout(connectionTimeoutMs());
        config.setMaximumPoolSize(MAX_POOL_SIZE);

        config.addDataSourceProperty("cachePrepStmts", true);
        config.addDataSourceProperty("prepStmtCacheSize", 25);
        config.addDataSourceProperty("prepStmtCacheSqlLimit", 2048);

        return config;
    }

    /**
     * Initiate connection to the database
     *
     * @return true if connection to the database initiated successfully, false if not successful
     */
    public static boolean initializeConnectionPool() {
        if (dataSource != null) {
            return true;
        }

        log.info("Initializing connection pool...");
        final HikariConfig config = getConfig();
        Instant initStart = Instant.now();
        try {
            dataSource = new HikariDataSource(config);
            initializeJdbi(dataSource);
            long initDuration = Duration.between(initStart, Instant.now()).toMillis();
            log.info("Connection pool initialized in {} ms", initDuration);
            return true;
        } catch (Exception e) {
            long timeout = Duration.between(initStart, Instant.now()).getSeconds();
            log.error("Failed to initialize database connection pool. Gave up after {} seconds.", timeout);
        }

        // Timed out - failed to initialize
        return false;
    }

    private static void initializeJdbi(DataSource dataSource) {
        jdbi = Jdbi.create(dataSource)
                .registerRowMapper(new NoteRowMapper());
    }
}
