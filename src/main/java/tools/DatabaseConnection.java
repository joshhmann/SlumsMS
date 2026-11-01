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
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

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
