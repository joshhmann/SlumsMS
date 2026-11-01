package tools;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
     */
    public static final class SnapshotConnection {
        private Connection connection;

        private SnapshotConnection() {
        }

        /** The read's own connection, opened on first use (and again after {@link #close()}). */
        public synchronized Connection get() throws SQLException {
            if (connection == null || connection.isClosed()) {
                connection = DriverManager.getConnection(getDbUrl(), YamlConfig.config.server.DB_USER,
                        YamlConfig.config.server.DB_PASS);
            }
            return connection;
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

    private static String getDbUrl() {
        // Environment variables override what's defined in the config file
        // This feature is used for the Docker support
        String hostOverride = System.getenv("DB_HOST");
        String host = hostOverride != null ? hostOverride : YamlConfig.config.server.DB_HOST;
        String dbUrl = String.format(YamlConfig.config.server.DB_URL_FORMAT, host);
        return dbUrl;
    }

    private static HikariConfig getConfig() {
        HikariConfig config = new HikariConfig();

        config.setJdbcUrl(getDbUrl());
        config.setUsername(YamlConfig.config.server.DB_USER);
        config.setPassword(YamlConfig.config.server.DB_PASS);

        final int initFailTimeoutSeconds = YamlConfig.config.server.INIT_CONNECTION_POOL_TIMEOUT;
        config.setInitializationFailTimeout(SECONDS.toMillis(initFailTimeoutSeconds));
        // 10 s, not Hikari's 30 s (b269): a wedged pool must fail its borrowers loudly and quickly instead of
        // letting a player login sit for half a minute behind whatever jammed it.
        config.setConnectionTimeout(SECONDS.toMillis(10));
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
