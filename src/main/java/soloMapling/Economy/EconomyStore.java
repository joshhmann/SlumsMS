package soloMapling.Economy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotDbGate;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where the economy lane keeps what must survive a restart (migration {@code 020-economy-trade}):
 * <ul>
 *   <li>{@code pricesamples}: every trade the price board learns from ({@link PriceBook});</li>
 *   <li>{@code economyday}: the world's daily meters (bot buying from players, gifts, tax, godlies:
 *   {@link EconomyMeters});</li>
 *   <li>{@code botgifts}: when each bot last gave each player a gift ({@link BotGifts}).</li>
 * </ul>
 * Without the migration the JDBC store logs once and keeps nothing: everything still works in memory until a
 * restart (a restart can then only let a meter start again at 0 for the day).
 */
public interface EconomyStore {

    void insertSample(PriceBook.Sample sample);

    /** Samples taken at or after {@code sinceMs}, oldest first. */
    List<PriceBook.Sample> samplesSince(long sinceMs);

    /** Deletes samples older than {@code beforeMs}; how many went. */
    int pruneSamples(long beforeMs);

    /** Today's value of every meter. */
    Map<String, Long> meters(LocalDate day);

    void saveMeter(LocalDate day, String meter, long amount);

    /** When this bot last gave this player a gift, or 0. */
    long lastGift(int botCharId, int playerId);

    void saveGift(int botCharId, int playerId, long at, String what);

    // ---- the real store ----

    final class Jdbc implements EconomyStore {
        private static final Logger log = LoggerFactory.getLogger(Jdbc.class);

        static final String INSERT = "INSERT INTO pricesamples (ItemId, UnitPrice, NormPrice, Quantity, Source, PlayerSide,"
                + " At) VALUES (?, ?, ?, ?, ?, ?, ?)";
        static final String SINCE = "SELECT ItemId, UnitPrice, NormPrice, Quantity, Source, PlayerSide, At FROM pricesamples"
                + " WHERE At >= ? ORDER BY At";
        static final String PRUNE = "DELETE FROM pricesamples WHERE At < ?";
        static final String METERS = "SELECT Meter, Amount FROM economyday WHERE Day = ?";
        static final String METER_UPSERT = "INSERT INTO economyday (Day, Meter, Amount) VALUES (?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE Amount = VALUES(Amount)";
        static final String GIFT_GET = "SELECT GivenAt FROM botgifts WHERE BotId = ? AND CharacterId = ?";
        static final String GIFT_UPSERT = "INSERT INTO botgifts (BotId, CharacterId, GivenAt, Gift) VALUES (?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE GivenAt = VALUES(GivenAt), Gift = VALUES(Gift)";

        private volatile boolean warned;

        private void missing(Exception e) {
            if (!warned) {
                warned = true;
                log.warn("Economy data is not stored (is migration 020-economy-trade applied?); keeping it in memory: {}",
                        e.toString());
            } else {
                log.debug("Economy store", e);
            }
        }

        @Override
        public void insertSample(PriceBook.Sample s) {
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(INSERT)) {
                ps.setInt(1, s.itemId());
                ps.setLong(2, s.unitPrice());
                ps.setLong(3, s.normPrice());
                ps.setInt(4, s.quantity());
                ps.setString(5, s.source().key());
                ps.setInt(6, s.playerSide() ? 1 : 0);
                ps.setLong(7, s.at());
                ps.executeUpdate();
            } catch (SQLException | RuntimeException e) {
                missing(e);
            }
        }

        @Override
        public List<PriceBook.Sample> samplesSince(long sinceMs) {
            List<PriceBook.Sample> out = new ArrayList<>();
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(SINCE)) {
                ps.setLong(1, sinceMs);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        PriceBook.Source src = PriceBook.Source.parse(rs.getString("Source"));
                        if (src != null) {
                            out.add(new PriceBook.Sample(rs.getInt("ItemId"), rs.getLong("UnitPrice"),
                                    rs.getLong("NormPrice"), rs.getInt("Quantity"), src, rs.getInt("PlayerSide") != 0,
                                    rs.getLong("At")));
                        }
                    }
                }
            } catch (SQLException | RuntimeException e) {
                missing(e);
            }
            return out;
        }

        @Override
        public int pruneSamples(long beforeMs) {
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(PRUNE)) {
                ps.setLong(1, beforeMs);
                return ps.executeUpdate();
            } catch (SQLException | RuntimeException e) {
                missing(e);
                return 0;
            }
        }

        @Override
        public Map<String, Long> meters(LocalDate day) {
            Map<String, Long> out = new HashMap<>();
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(METERS)) {
                ps.setDate(1, Date.valueOf(day));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString("Meter"), rs.getLong("Amount"));
                    }
                }
            } catch (SQLException | RuntimeException e) {
                missing(e);
            }
            return out;
        }

        @Override
        public void saveMeter(LocalDate day, String meter, long amount) {
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(METER_UPSERT)) {
                ps.setDate(1, Date.valueOf(day));
                ps.setString(2, meter);
                ps.setLong(3, amount);
                ps.executeUpdate();
            } catch (SQLException | RuntimeException e) {
                missing(e);
            }
        }

        @Override
        public long lastGift(int botCharId, int playerId) {
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(GIFT_GET)) {
                ps.setInt(1, botCharId);
                ps.setInt(2, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0;
                }
            } catch (SQLException | RuntimeException e) {
                missing(e);
                return 0;
            }
        }

        @Override
        public void saveGift(int botCharId, int playerId, long at, String what) {
            try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(GIFT_UPSERT)) {
                ps.setInt(1, botCharId);
                ps.setInt(2, playerId);
                ps.setLong(3, at);
                ps.setString(4, what == null ? "" : what.length() > 64 ? what.substring(0, 64) : what);
                ps.executeUpdate();
            } catch (SQLException | RuntimeException e) {
                missing(e);
            }
        }
    }

    // ---- memory only (tests, and a server without the migration behaves like this until a restart) ----

    final class Memory implements EconomyStore {
        public final List<PriceBook.Sample> samples = new CopyOnWriteArrayList<>();
        public final Map<String, Long> meterRows = new ConcurrentHashMap<>();
        public final Map<Long, Long> gifts = new ConcurrentHashMap<>();

        @Override
        public void insertSample(PriceBook.Sample sample) {
            samples.add(sample);
        }

        @Override
        public List<PriceBook.Sample> samplesSince(long sinceMs) {
            List<PriceBook.Sample> out = new ArrayList<>();
            for (PriceBook.Sample s : samples) {
                if (s.at() >= sinceMs) {
                    out.add(s);
                }
            }
            return out;
        }

        @Override
        public int pruneSamples(long beforeMs) {
            int before = samples.size();
            samples.removeIf(s -> s.at() < beforeMs);
            return before - samples.size();
        }

        @Override
        public Map<String, Long> meters(LocalDate day) {
            Map<String, Long> out = new HashMap<>();
            String prefix = day + "|";
            meterRows.forEach((k, v) -> {
                if (k.startsWith(prefix)) {
                    out.put(k.substring(prefix.length()), v);
                }
            });
            return out;
        }

        @Override
        public void saveMeter(LocalDate day, String meter, long amount) {
            meterRows.put(day + "|" + meter, amount);
        }

        @Override
        public long lastGift(int botCharId, int playerId) {
            return gifts.getOrDefault(((long) botCharId << 32) ^ playerId, 0L);
        }

        @Override
        public void saveGift(int botCharId, int playerId, long at, String what) {
            gifts.put(((long) botCharId << 32) ^ playerId, at);
        }
    }
}
