package soloMapling.ArtificialPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where {@link SocialGate} keeps what must survive a restart (migration 031: {@code botsocialopt},
 * {@code botstartedit}): each player's {@code @quiet} / {@code @romance off} choices and the "started it" ledger.
 * Read once at boot, written only on a change, always off the game thread. Without the migration the JDBC store logs
 * once and keeps nothing: the gate then works from memory until a restart.
 */
public interface SocialGateStore {

    /** A player's stored choices. */
    record Opt(int charId, boolean quietMegaphones, boolean quietBots, boolean romanceOff) {
        boolean isDefault() {
            return !quietMegaphones && !quietBots && !romanceOff;
        }
    }

    /** Everything stored. Null when the tables could not be read; {@code missing} when the migration is not applied. */
    record Snapshot(List<Opt> opts, List<SocialLedger.Row> ledger, boolean missing) {
    }

    /** Everything, after deleting ledger rows not touched since {@code fadedBefore} (they faded to nothing). */
    Snapshot loadAll(long fadedBefore);

    void saveOpt(Opt opt, long now);

    void saveLedger(SocialLedger.Row row);

    void deleteLedger(int playerId, int botId);

    /** The real store: the server's database through the bots' share of the pool. */
    final class Jdbc implements SocialGateStore {
        private static final Logger log = LoggerFactory.getLogger(Jdbc.class);

        static final String OPTS = "SELECT CharacterId, QuietMegaphones, QuietBots, RomanceOff FROM botsocialopt";
        // the ledger's beef weight lives in the 031 columns Spice / SpiceAt (named before the owner's "Beef" rename)
        static final String LEDGER = "SELECT CharacterId, BotId, Spice, SpiceAt, Flirt, FlirtAt, LastKind FROM botstartedit";
        static final String PRUNE = "DELETE FROM botstartedit WHERE SpiceAt < ? AND FlirtAt < ?";
        static final String OPT_UPSERT = "INSERT INTO botsocialopt (CharacterId, QuietMegaphones, QuietBots, RomanceOff,"
                + " UpdatedAt) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE QuietMegaphones = VALUES(QuietMegaphones),"
                + " QuietBots = VALUES(QuietBots), RomanceOff = VALUES(RomanceOff), UpdatedAt = VALUES(UpdatedAt)";
        static final String OPT_DELETE = "DELETE FROM botsocialopt WHERE CharacterId = ?";
        static final String LEDGER_UPSERT = "INSERT INTO botstartedit (CharacterId, BotId, Spice, SpiceAt, Flirt, FlirtAt,"
                + " LastKind) VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE Spice = VALUES(Spice),"
                + " SpiceAt = VALUES(SpiceAt), Flirt = VALUES(Flirt), FlirtAt = VALUES(FlirtAt), LastKind = VALUES(LastKind)";
        static final String LEDGER_DELETE = "DELETE FROM botstartedit WHERE CharacterId = ? AND BotId = ?";

        private volatile boolean warned;

        private void missing(SQLException e) {
            if (!warned) {
                warned = true;
                log.warn("Social gate choices are not stored (is migration 031-social-gate applied?); keeping them in"
                        + " memory", e);
            } else {
                log.debug("Social gate store", e);
            }
        }

        static boolean noTable(SQLException e) {
            return e.getErrorCode() == 1146 || "42S02".equals(e.getSQLState());
        }

        @Override
        public Snapshot loadAll(long fadedBefore) {
            List<Opt> opts = new ArrayList<>();
            List<SocialLedger.Row> rows = new ArrayList<>();
            try (Connection con = BotDbGate.connection()) {
                try (PreparedStatement ps = con.prepareStatement(PRUNE)) {
                    ps.setLong(1, fadedBefore);
                    ps.setLong(2, fadedBefore);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = con.prepareStatement(OPTS); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        opts.add(new Opt(rs.getInt(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4)));
                    }
                }
                try (PreparedStatement ps = con.prepareStatement(LEDGER); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new SocialLedger.Row(rs.getInt(1), rs.getInt(2), rs.getDouble(3), rs.getLong(4),
                                rs.getDouble(5), rs.getLong(6), rs.getString(7)));
                    }
                }
                return new Snapshot(opts, rows, false);
            } catch (SQLException e) {
                missing(e);
                return noTable(e) ? new Snapshot(List.of(), List.of(), true) : null;
            }
        }

        @Override
        public void saveOpt(Opt opt, long now) {
            try (Connection con = BotDbGate.connection();
                 PreparedStatement ps = con.prepareStatement(opt.isDefault() ? OPT_DELETE : OPT_UPSERT)) {
                ps.setInt(1, opt.charId());
                if (!opt.isDefault()) {
                    ps.setBoolean(2, opt.quietMegaphones());
                    ps.setBoolean(3, opt.quietBots());
                    ps.setBoolean(4, opt.romanceOff());
                    ps.setLong(5, now);
                }
                ps.executeUpdate();
            } catch (SQLException e) {
                missing(e);
            }
        }

        @Override
        public void saveLedger(SocialLedger.Row row) {
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(LEDGER_UPSERT)) {
                ps.setInt(1, row.playerId());
                ps.setInt(2, row.botId());
                ps.setFloat(3, (float) row.beef());
                ps.setLong(4, row.beefAt());
                ps.setFloat(5, (float) row.flirt());
                ps.setLong(6, row.flirtAt());
                String kind = row.lastKind() == null ? "" : row.lastKind();
                ps.setString(7, kind.length() > 8 ? kind.substring(0, 8) : kind);
                ps.executeUpdate();
            } catch (SQLException e) {
                missing(e);
            }
        }

        @Override
        public void deleteLedger(int playerId, int botId) {
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(LEDGER_DELETE)) {
                ps.setInt(1, playerId);
                ps.setInt(2, botId);
                ps.executeUpdate();
            } catch (SQLException e) {
                missing(e);
            }
        }
    }

    /** Tests: a store in memory that outlives the gate's own memory (a "restart" is a reload from it). */
    final class Memory implements SocialGateStore {
        final Map<Integer, Opt> opts = new ConcurrentHashMap<>();
        final Map<Long, SocialLedger.Row> ledger = new ConcurrentHashMap<>();
        volatile boolean failing;

        static long key(int playerId, int botId) {
            return ((long) playerId << 32) | (botId & 0xffffffffL);
        }

        @Override
        public Snapshot loadAll(long fadedBefore) {
            if (failing) {
                return null;
            }
            ledger.values().removeIf(r -> r.beefAt() < fadedBefore && r.flirtAt() < fadedBefore);
            return new Snapshot(List.copyOf(opts.values()), List.copyOf(ledger.values()), false);
        }

        @Override
        public void saveOpt(Opt opt, long now) {
            if (opt.isDefault()) {
                opts.remove(opt.charId());
            } else {
                opts.put(opt.charId(), opt);
            }
        }

        @Override
        public void saveLedger(SocialLedger.Row row) {
            ledger.put(key(row.playerId(), row.botId()), row);
        }

        @Override
        public void deleteLedger(int playerId, int botId) {
            ledger.remove(key(playerId, botId));
        }
    }
}
