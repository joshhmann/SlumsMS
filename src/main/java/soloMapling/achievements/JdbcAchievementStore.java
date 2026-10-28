package soloMapling.achievements;

import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/** Table {@code character_achievements} (migration 042). */
public final class JdbcAchievementStore implements AchievementStore {

    /** Where connections come from (the game's pool; a scratch database in the integration test). */
    @FunctionalInterface
    public interface Conns {
        Connection get() throws SQLException;
    }

    private final Conns conns;

    public JdbcAchievementStore() {
        this(DatabaseConnection::getConnection);
    }

    public JdbcAchievementStore(Conns conns) {
        this.conns = conns;
    }

    @Override
    public Map<String, Row> load(int charId) throws SQLException {
        Map<String, Row> out = new HashMap<>();
        try (Connection con = conns.get();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT achievement, progress, state, earned_at, granted_at FROM character_achievements"
                             + " WHERE charid = ?")) {
            ps.setInt(1, charId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), new Row(rs.getInt(2), rs.getInt(3), rs.getLong(4), rs.getLong(5)));
                }
            }
        }
        return out;
    }

    @Override
    public void saveProgress(int charId, String goal, int progress, long now) throws SQLException {
        try (Connection con = conns.get();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO character_achievements (charid, achievement, progress, state, earned_at, granted_at)"
                             + " VALUES (?, ?, ?, 0, 0, 0) ON DUPLICATE KEY UPDATE progress ="
                             + " IF(state = 0, VALUES(progress), progress)")) {
            ps.setInt(1, charId);
            ps.setString(2, goal);
            ps.setInt(3, progress);
            ps.executeUpdate();
        }
    }

    @Override
    public boolean markEarned(int charId, String goal, int progress, long now) throws SQLException {
        try (Connection con = conns.get()) {
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE character_achievements SET progress = ?, state = 1, earned_at = ? WHERE charid = ?"
                            + " AND achievement = ? AND state = 0")) {
                ps.setInt(1, progress);
                ps.setLong(2, now);
                ps.setInt(3, charId);
                ps.setString(4, goal);
                if (ps.executeUpdate() == 1) {
                    return true;
                }
            }
            // No open row: insert one (INSERT IGNORE reports 0 when the goal is already earned or granted).
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT IGNORE INTO character_achievements (charid, achievement, progress, state, earned_at,"
                            + " granted_at) VALUES (?, ?, ?, 1, ?, 0)")) {
                ps.setInt(1, charId);
                ps.setString(2, goal);
                ps.setInt(3, progress);
                ps.setLong(4, now);
                return ps.executeUpdate() == 1;
            }
        }
    }

    @Override
    public boolean markGranted(int charId, String goal, long now) throws SQLException {
        try (Connection con = conns.get();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE character_achievements SET state = 2, granted_at = ? WHERE charid = ? AND achievement = ?"
                             + " AND state = 1")) {
            ps.setLong(1, now);
            ps.setInt(2, charId);
            ps.setString(3, goal);
            return ps.executeUpdate() == 1;
        }
    }

    @Override
    public void delete(int charId, String goal) throws SQLException {
        try (Connection con = conns.get();
             PreparedStatement ps = con.prepareStatement(
                     "DELETE FROM character_achievements WHERE charid = ? AND achievement = ?")) {
            ps.setInt(1, charId);
            ps.setString(2, goal);
            ps.executeUpdate();
        }
    }
}
