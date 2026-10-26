package soloMapling.ArtificialPlayer.BotAffinitySystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotDbGate;
import soloMapling.ArtificialPlayer.BotStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where bot-to-bot bonds live (migration 029: {@code botbond}). Read once, whole, off the game thread (at most about
 * 3,000 x K rows); written in batches of what changed every few minutes ({@link BotBonds#flush}). Without the
 * migration the JDBC store logs once and keeps nothing: bonds still work in memory until a restart.
 */
public interface BondStore {

    /** Every stored bond (character ids), or null when the table could not be read (retry later). */
    List<Bond> loadAll();

    /** Deletes the pairs in {@code deletes} (keys from {@link BotBonds#key}), then upserts {@code saves}. */
    void write(Collection<Long> deletes, Collection<Bond> saves);

    /** The real store: the server's database through the bots' share of the pool. */
    final class Jdbc implements BondStore {
        private static final Logger log = LoggerFactory.getLogger(Jdbc.class);

        static final String SELECT = "SELECT BotId, OtherId, Score, Kind, Hostile, UpdatedAt FROM botbond";
        static final String UPSERT = "INSERT INTO botbond (BotId, OtherId, Score, Kind, Hostile, UpdatedAt) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE Score = VALUES(Score), Kind = VALUES(Kind), "
                + "Hostile = VALUES(Hostile), UpdatedAt = VALUES(UpdatedAt)";
        static final String DELETE = "DELETE FROM botbond WHERE BotId = ? AND OtherId = ?";

        private volatile boolean warned;

        private void missing(SQLException e) {
            if (!warned) {
                warned = true;
                log.warn("Bot bonds are not stored (is migration 029-bot-bonds applied?); keeping them in memory", e);
            } else {
                log.debug("Bot bond store", e);
            }
        }

        @Override
        public List<Bond> loadAll() {
            List<Bond> out = new ArrayList<>();
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(SELECT);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Bond(BotStore.BOT_ID_BASE + rs.getInt("BotId"), BotStore.BOT_ID_BASE
                            + rs.getInt("OtherId"), rs.getFloat("Score"), rs.getInt("Kind"), rs.getInt("Hostile"),
                            rs.getLong("UpdatedAt")));
                }
                return out;
            } catch (SQLException e) {
                missing(e);
                return AffinityStore.Jdbc.noTable(e) ? out : null;
            }
        }

        @Override
        public void write(Collection<Long> deletes, Collection<Bond> saves) {
            if (deletes.isEmpty() && saves.isEmpty()) {
                return;
            }
            try (Connection con = BotDbGate.connection()) {
                if (!deletes.isEmpty()) {
                    try (PreparedStatement ps = con.prepareStatement(DELETE)) {
                        for (long k : deletes) {
                            ps.setInt(1, BotBonds.keyBot(k) - BotStore.BOT_ID_BASE);
                            ps.setInt(2, BotBonds.keyOther(k) - BotStore.BOT_ID_BASE);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                if (!saves.isEmpty()) {
                    try (PreparedStatement ps = con.prepareStatement(UPSERT)) {
                        for (Bond b : saves) {
                            ps.setInt(1, b.botId - BotStore.BOT_ID_BASE);
                            ps.setInt(2, b.otherId - BotStore.BOT_ID_BASE);
                            ps.setFloat(3, (float) Math.max(-BondRules.MAX, Math.min(BondRules.MAX, b.score)));
                            ps.setInt(4, Math.min(999, b.kind));
                            ps.setInt(5, Math.min(999, b.hostile));
                            ps.setLong(6, b.updatedAt);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
            } catch (SQLException e) {
                missing(e);
            }
        }
    }

    /** A store in memory: tests, and a server without a database. */
    class Memory implements BondStore {
        final Map<Long, Bond> rows = new ConcurrentHashMap<>();
        volatile boolean failing;
        volatile int writes;

        @Override
        public List<Bond> loadAll() {
            if (failing) {
                return null;
            }
            List<Bond> out = new ArrayList<>();
            for (Bond b : rows.values()) {
                Bond copy = b.snapshot();
                copy.score = (float) copy.score; // as the table stores it
                out.add(copy);
            }
            return out;
        }

        @Override
        public void write(Collection<Long> deletes, Collection<Bond> saves) {
            writes++;
            for (long k : deletes) {
                rows.remove(k);
            }
            for (Bond b : saves) {
                Bond copy = b.snapshot();
                rows.put(BotBonds.key(b.botId, b.otherId), copy);
            }
        }

        public int size() {
            return rows.size();
        }

        public Bond row(int botId, int otherId) {
            return rows.get(BotBonds.key(botId, otherId));
        }
    }
}
