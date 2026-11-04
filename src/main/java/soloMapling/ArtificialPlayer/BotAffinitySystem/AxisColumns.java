package soloMapling.ArtificialPlayer.BotAffinitySystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotDbGate;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialSim;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

/**
 * Ticket 271 stage 2, migration 059: the ten columns the axes and the stage live in on {@code botbond} and
 * {@code botaffinity} (eight FLOAT axes, {@code Stage} TINYINT, {@code HasAxes} TINYINT), and how the stores cope
 * when the migration is not applied: they ask once whether the columns exist ({@link Availability}, again 10 minutes
 * after a "no", as {@code BotMemory.hasKinds} does for migration 030) and, without them, read and write the older six
 * columns only, so a server on an unmigrated database keeps running with axes derived from the score at every load.
 * {@code HasAxes} is 0 on every row that existed before the migration (and any row an older jar wrote): the store
 * hands such a row to {@link Bond} / {@link Tie} through the legacy constructor, which derives the axes.
 */
final class AxisColumns {

    private static final Logger log = LoggerFactory.getLogger(AxisColumns.class);

    /** The new columns, in the order the stores bind them (axis order, then stage, then the marker). */
    static final String LIST = "Familiarity, Trust, Affection, Respect, Attraction, Rivalry, Resentment, Obligation, "
            + "Stage, HasAxes";
    static final String UPDATE = "Familiarity = VALUES(Familiarity), Trust = VALUES(Trust), Affection = VALUES(Affection), "
            + "Respect = VALUES(Respect), Attraction = VALUES(Attraction), Rivalry = VALUES(Rivalry), "
            + "Resentment = VALUES(Resentment), Obligation = VALUES(Obligation), Stage = VALUES(Stage), "
            + "HasAxes = VALUES(HasAxes)";
    static final String PLACEHOLDERS = "?, ?, ?, ?, ?, ?, ?, ?, ?, ?";
    private static final String[] AXIS_NAMES = {"Familiarity", "Trust", "Affection", "Respect", "Attraction", "Rivalry",
            "Resentment", "Obligation"};
    static final long RECHECK_MS = TimeUnit.MINUTES.toMillis(10);

    private AxisColumns() {
    }

    /** Binds the ten values from parameter index {@code from}: the axes as floats, the stage code, the marker 1. */
    static void bind(PreparedStatement ps, int from, double[] axes, Stage stage) throws SQLException {
        for (int i = 0; i < Axis.COUNT; i++) {
            ps.setFloat(from + i, (float) axes[i]);
        }
        ps.setInt(from + Axis.COUNT, stage.code());
        ps.setInt(from + Axis.COUNT + 1, 1);
    }

    /** The row's axes, or null when {@code HasAxes} is 0 (a row that predates the migration: derive them). */
    static double[] readAxes(ResultSet rs) throws SQLException {
        if (rs.getInt("HasAxes") == 0) {
            return null;
        }
        double[] a = new double[Axis.COUNT];
        for (int i = 0; i < Axis.COUNT; i++) {
            a[i] = Axis.of(i).clamp(rs.getFloat(AXIS_NAMES[i]));
        }
        return a;
    }

    static Stage readStage(ResultSet rs) throws SQLException {
        return Stage.ofCode(rs.getInt("Stage"));
    }

    /** Whether a table has the columns: asked once, again 10 minutes after a "no". */
    static final class Availability {
        private final String table;
        private volatile Boolean known;
        private volatile long checkedAt;

        /** Runs a statement on the database; throws what it throws. */
        @FunctionalInterface
        interface Probe {
            void run(String sql) throws SQLException;
        }

        Availability(String table) {
            this.table = table;
        }

        String probeSql() {
            return "SELECT " + LIST + " FROM " + table + " WHERE 1 = 0";
        }

        boolean present() {
            return present(sql -> {
                try (Connection con = BotDbGate.connection(); PreparedStatement ps = con.prepareStatement(sql);
                     ResultSet ignored = ps.executeQuery()) {
                    // the columns exist
                }
            });
        }

        boolean present(Probe probe) {
            Boolean was = known;
            long now = SocialSim.now();
            if (was != null && (was || now - checkedAt < RECHECK_MS)) {
                return was;
            }
            try {
                probe.run(probeSql());
                known = true;
                return true;
            } catch (SQLException e) {
                if (e.getErrorCode() == 1054 || "42S22".equals(e.getSQLState())) { // unknown column: 059 not applied
                    known = false;
                    checkedAt = now;
                    log.info("{} has no axis columns (migration 059 not applied): relationship axes are derived from the"
                            + " score at every load", table);
                }
                return false; // anything else (table missing, share busy): ask again next time
            } catch (RuntimeException e) {
                return false; // no pool yet
            }
        }

        /** Tests: forget what was learned. */
        void reset() {
            known = null;
            checkedAt = 0;
        }
    }
}
