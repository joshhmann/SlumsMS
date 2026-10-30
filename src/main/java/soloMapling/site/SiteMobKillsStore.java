package soloMapling.site;

import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

/** {@link SiteMobKills.Store} on table {@code site_mob_kills} (migration 047; its estimated column, 056). */
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

    /** 1.6.2 b015: the estimated part of rows {@link #write} just set (column from migration 056). */
    @Override
    public void writeEstimated(Map<Long, Map<Integer, Integer>> estimated) throws Exception {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE site_mob_kills SET estimated = ? WHERE hour_start = ? AND mob = ?")) {
            for (Map.Entry<Long, Map<Integer, Integer>> h : estimated.entrySet()) {
                for (Map.Entry<Integer, Integer> m : h.getValue().entrySet()) {
                    ps.setInt(1, m.getValue());
                    ps.setLong(2, h.getKey());
                    ps.setInt(3, m.getKey());
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    @Override
    public Map<Long, Map<Integer, Integer>> readEstimated(long fromHour) throws Exception {
        Map<Long, Map<Integer, Integer>> out = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT hour_start, mob, estimated FROM site_mob_kills "
                     + "WHERE hour_start >= ? AND estimated > 0")) {
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
    public long oldestSplitHour(long fromHour) throws Exception {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT MIN(hour_start) FROM site_mob_kills "
                     + "WHERE hour_start >= ? AND estimated IS NOT NULL")) {
            ps.setLong(1, fromHour);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long h = rs.getLong(1);
                    return rs.wasNull() ? -1 : h;
                }
            }
        }
        return -1;
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
