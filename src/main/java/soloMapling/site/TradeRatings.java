package soloMapling.site;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 1.6.0 Track D (owner, 2026-10-26): trade ratings per character, for bots and players alike: trades completed, and
 * trades that fell through. A fall-through is a trade window in which at least one side had put in items or mesos and
 * that then closed without completing (cancelled, closed, a disconnect, a map change); an empty window opened and
 * closed counts for nobody.
 * <p>
 * The trade code ({@code server.Trade}) calls {@link #completed} and {@link #fellThrough}: two map merges, no I/O.
 * One minimum-priority daemon thread adds the pending counts to {@code traderatings} (migration 035) every minute in
 * one batch, and at shutdown. Without the table the counts stay pending in memory (bounded) and are retried; the game
 * never waits on or sees any of it.
 */
public final class TradeRatings {

    private static final Logger log = LoggerFactory.getLogger(TradeRatings.class);

    static final String UPSERT = "INSERT INTO traderatings (CharId, Completed, FellThrough, UpdatedAt) VALUES (?, ?, ?, ?)"
            + " ON DUPLICATE KEY UPDATE Completed = Completed + VALUES(Completed),"
            + " FellThrough = FellThrough + VALUES(FellThrough), UpdatedAt = VALUES(UpdatedAt)";
    /** Characters with pending counts kept while the table cannot be written; more are dropped (logged). */
    static final int MAX_PENDING = 50_000;
    static final long FLUSH_SECONDS = 60;
    private static final long WARN_EVERY_MS = TimeUnit.MINUTES.toMillis(15);

    /** Character id -> {completed, fell through} not yet written. */
    private static final ConcurrentHashMap<Integer, long[]> pending = new ConcurrentHashMap<>();
    private static volatile ScheduledExecutorService executor;
    private static volatile long lastWarnAt;
    private static volatile Writer writer = TradeRatings::writeDb;

    /** Writes one batch; tests substitute one. */
    interface Writer {
        void write(Map<Integer, long[]> batch, long now) throws SQLException;
    }

    private TradeRatings() {
    }

    // ── from the trade code ─────────────────────────────────────────────────────────────────────

    /** A trade between these two completed. */
    public static void completed(Character a, Character b) {
        if (bothBots(a, b)) {
            return;
        }
        count(a, 0);
        count(b, 0);
    }

    /**
     * A trade window between these two closed without completing. {@code offered}: either side had put in items or
     * mesos; when false nothing is counted (an empty window opened and closed).
     */
    public static void fellThrough(Character a, Character b, boolean offered) {
        if (!offered || bothBots(a, b)) {
            return;
        }
        count(a, 1);
        count(b, 1);
    }

    /**
     * 1.6.0 (owner 2026-10-26): the trader boards are for players; a trade between two bots (one a staged window, or
     * any future path) never counts for either.
     */
    static boolean bothBots(Character a, Character b) {
        try {
            return a != null && b != null && soloMapling.ArtificialPlayer.BotHelpers.isArtificial(a.getId())
                    && soloMapling.ArtificialPlayer.BotHelpers.isArtificial(b.getId());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void count(Character chr, int which) {
        try {
            if (chr == null || chr.getId() <= 0) {
                return;
            }
            if (pending.size() >= MAX_PENDING && !pending.containsKey(chr.getId())) {
                return; // the table has been unwritable for a long time: stop growing
            }
            pending.compute(chr.getId(), (k, v) -> {
                long[] c = v == null ? new long[2] : v;
                c[which]++;
                return c;
            });
        } catch (RuntimeException e) {
            // never into the trade
        }
    }

    // ── writing ─────────────────────────────────────────────────────────────────────────────────

    public static synchronized void start() {
        if (executor != null) {
            return;
        }
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "trade-ratings");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        ex.scheduleWithFixedDelay(TradeRatings::flushSafely, FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS);
        executor = ex;
    }

    /** At shutdown: stop the timer and write what is pending. */
    public static synchronized void shutdown() {
        ScheduledExecutorService ex = executor;
        executor = null;
        if (ex != null) {
            ex.shutdown();
            try {
                ex.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flushSafely();
    }

    static void flushSafely() {
        try {
            flush(System.currentTimeMillis());
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastWarnAt >= WARN_EVERY_MS) {
                lastWarnAt = now;
                log.warn("Trade ratings could not be saved ({} characters pending); the game is unaffected", pending.size(), t);
            }
        }
    }

    /** Takes the pending counts and writes them in one batch; on failure they go back to pending. */
    static synchronized int flush(long now) throws SQLException {
        Map<Integer, long[]> batch = new HashMap<>();
        for (Integer id : new ArrayList<>(pending.keySet())) {
            long[] c = pending.remove(id);
            if (c != null) {
                batch.put(id, c);
            }
        }
        if (batch.isEmpty()) {
            return 0;
        }
        try {
            writer.write(batch, now);
            return batch.size();
        } catch (SQLException | RuntimeException e) {
            batch.forEach((id, c) -> pending.merge(id, c, (x, y) -> new long[]{x[0] + y[0], x[1] + y[1]}));
            throw e;
        }
    }

    private static void writeDb(Map<Integer, long[]> batch, long now) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(UPSERT)) {
            for (Map.Entry<Integer, long[]> e : batch.entrySet()) {
                ps.setInt(1, e.getKey());
                ps.setLong(2, e.getValue()[0]);
                ps.setLong(3, e.getValue()[1]);
                ps.setLong(4, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── the export ──────────────────────────────────────────────────────────────────────────────

    /** One character's stored ratings. */
    public record Row(int charId, long completed, long fellThrough) {
    }

    /** The private per-player file for the site's "My trades" panel; it goes to the board outbox, never public. */
    public static final String PRIVATE_FILE = "trade-ratings.json";

    /**
     * {@value #PRIVATE_FILE}: {@code {"generated": "<ISO UTC>", "ratings": {"<IGN>": {"completed": n,
     * "fellThrough": n}}}} for every character with at least one rated trade, from the same people as the public
     * traders board ({@code everyone}: {@link SiteLeaderboards#players} and the bots), so GM and auto-QA characters
     * are never in it. Unlike leaderboards.json there is no {@link SiteLeaderboards#TRADER_MIN}. Pure.
     */
    public static Map<String, Object> privateFile(List<SiteLeaderboards.Person> everyone, List<Row> trades, long now) {
        Map<Integer, String> names = new HashMap<>();
        for (SiteLeaderboards.Person p : everyone) {
            if (p.name() != null && !p.name().isBlank() && !SiteFeed.isQaName(p.name())) {
                names.put(p.id(), p.name());
            }
        }
        Map<String, Object> ratings = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Row t : trades == null ? List.<Row>of() : trades) {
            String name = names.get(t.charId());
            if (name != null && t.completed() + t.fellThrough() >= 1) {
                Map<String, Object> r = new java.util.LinkedHashMap<>();
                r.put("completed", t.completed());
                r.put("fellThrough", t.fellThrough());
                ratings.put(name, r);
            }
        }
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("generated", java.time.Instant.ofEpochMilli(now).toString());
        m.put("ratings", new java.util.LinkedHashMap<>(ratings));
        return m;
    }

    // ── test seams ──

    static Map<Integer, long[]> pendingForTest() {
        Map<Integer, long[]> out = new HashMap<>();
        pending.forEach((k, v) -> out.put(k, v.clone()));
        return out;
    }

    static void resetForTest(Writer w) {
        pending.clear();
        writer = w != null ? w : TradeRatings::writeDb;
    }

}
