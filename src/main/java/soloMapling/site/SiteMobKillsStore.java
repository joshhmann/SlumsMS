package soloMapling.site;

import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

/** {@link SiteMobKills.Store} on table {@code site_mob_kills} (migration 047). */
final class SiteMobKillsStore implements SiteMobKills.Store {

    @Override
    public void write(Map<Long, Map<Integer, Integer>> counts) throws Exception {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("INSERT INTO site_mob_kills(hour_start, mob, kills) VALUES (?,?,?) "
                     + "ON DUPLICATE KEY UPDATE kills = VALUES(kills)")) {
            for (Map.Entry<Long, Map<Integer, Integer>> h : counts.entrySet()) {
                for (Map.Entry<Integer, Integer> m : h.getValue().entrySet()) {
                    ps.setLong(1, h.getKey());
                    ps.setInt(2, m.getKey());
                    ps.setInt(3, m.getValue());
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    @Override
    public Map<Long, Map<Integer, Integer>> read(long fromHour) throws Exception {
        Map<Long, Map<Integer, Integer>> out = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT hour_start, mob, kills FROM site_mob_kills WHERE hour_start >= ?")) {
            ps.setLong(1, fromHour);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getLong(1), k -> new HashMap<>()).put(rs.getInt(2), rs.getInt(3));
                }
            }
        }
        return out;
    }

    @Override
    public void prune(long beforeHour) throws Exception {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM site_mob_kills WHERE hour_start < ?")) {
            ps.setLong(1, beforeHour);
            ps.executeUpdate();
        }
    }
}
