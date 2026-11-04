package soloMapling.ArtificialPlayer.BotAffinitySystem;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Ticket 271 stage 2: the eight axes of one directed relationship edge ({@link Bond}: bot to bot, {@link Tie}: bot to
 * player). Every axis is a float on a stated range, as of the edge's {@code updatedAt}, and fades <b>lazily on read</b>
 * toward 0 (the familiarity axis toward a floor), each with its own half-life; nothing is written when time passes.
 *
 * <table>
 *   <caption>axes, ranges, default half-lives in days (positive side / negative side)</caption>
 *   <tr><td>FAMILIARITY</td><td>0..1</td><td>how well the holder knows the other; grows with every interaction, fades
 *       toward {@link #familiarityFloor()} and never below it</td><td>120</td></tr>
 *   <tr><td>TRUST</td><td>-1..1</td><td>would the holder rely on the other (fair trades, rescues vs scams)</td><td>56 / 28</td></tr>
 *   <tr><td>AFFECTION</td><td>-1..1</td><td>liking; negative is dislike</td><td>42 / 14 (as the legacy score)</td></tr>
 *   <tr><td>RESPECT</td><td>-1..1</td><td>admiration of skill and conduct; negative is contempt</td><td>84 / 42</td></tr>
 *   <tr><td>ATTRACTION</td><td>0..1</td><td>romantic interest</td><td>21</td></tr>
 *   <tr><td>RIVALRY</td><td>0..1</td><td>competitive pull (a friendly rival or an enemy)</td><td>28</td></tr>
 *   <tr><td>RESENTMENT</td><td>0..1</td><td>a held grudge</td><td>14</td></tr>
 *   <tr><td>OBLIGATION</td><td>-1..1</td><td>positive: the holder feels it owes the other; negative: the other owes the
 *       holder</td><td>21</td></tr>
 * </table>
 * Half-lives are tunable with {@code -Dsolomapling.axes.<axis>.halfLifeDays} and {@code ...negHalfLifeDays} (axis name
 * in lower case); the familiarity floor with {@code -Dsolomapling.axes.familiarity.floor}.
 */
public enum Axis {
    FAMILIARITY(0, 1, 120, 120), TRUST(-1, 1, 56, 28), AFFECTION(-1, 1, 42, 14), RESPECT(-1, 1, 84, 42),
    ATTRACTION(0, 1, 21, 21), RIVALRY(0, 1, 28, 28), RESENTMENT(0, 1, 14, 14), OBLIGATION(-1, 1, 21, 21);

    public static final int COUNT = 8;
    /** The ordinal of each axis, for code that indexes the {@code double[]} vectors. */
    public static final int FAM = 0, TRU = 1, AFF = 2, RES = 3, ATT = 4, RIV = 5, REN = 6, OBL = 7;

    private static final Axis[] ALL = values();

    public final double min;
    public final double max;
    private final double halfLifeDays;
    private final double negHalfLifeDays;

    Axis(double min, double max, double halfLifeDays, double negHalfLifeDays) {
        this.min = min;
        this.max = max;
        this.halfLifeDays = halfLifeDays;
        this.negHalfLifeDays = negHalfLifeDays;
    }

    static double prop(String key, double fallback) {
        try {
            return Double.parseDouble(System.getProperty(key, Double.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    static Axis of(int index) {
        return ALL[index];
    }

    public double clamp(double v) {
        return Math.max(min, Math.min(max, v));
    }

    /** Half-life of the positive (or only) side, ms. */
    public long halfLifeMs(boolean negativeSide) {
        double days = prop("solomapling.axes." + key() + (negativeSide ? ".negHalfLifeDays" : ".halfLifeDays"),
                negativeSide ? negHalfLifeDays : halfLifeDays);
        return (long) (days * TimeUnit.DAYS.toMillis(1));
    }

    public static double familiarityFloor() {
        return prop("solomapling.axes.familiarity.floor", 0.08);
    }

    /** The value {@code dtMs} after it was {@code v}: halves toward 0 (familiarity: toward its floor) per half-life. */
    public double faded(double v, long dtMs) {
        if (dtMs <= 0 || v == 0) {
            return v;
        }
        double target = this == FAMILIARITY ? familiarityFloor() : 0;
        if (this == FAMILIARITY && v <= target) {
            return v; // never pulled down below the floor, and not pulled up to it either
        }
        long halfLife = halfLifeMs(v < 0);
        if (halfLife <= 0) {
            return v;
        }
        return target + (v - target) * Math.pow(0.5, dtMs / (double) halfLife);
    }
}
