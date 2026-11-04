package soloMapling.ArtificialPlayer.BotAffinitySystem;

import java.util.Arrays;

/**
 * An immutable view of one edge's eight {@link Axis} values at one moment (already faded to that moment), plus the
 * pure arithmetic on the raw {@code double[]} vectors that {@link Bond} and {@link Tie} hold. The view is what readers
 * get ({@code BotBonds.axes}); nothing outside the rule classes mutates a vector.
 *
 * <p>It is one holder's own edge. A bot's belief about how the other feels about it is nothing more than this same
 * edge: no code reads the other direction's vector to answer "does Bob think Alice dislikes him", so a bot is never
 * omniscient about what the other feels.
 */
public final class Axes {

    public static final Axes NONE = new Axes(new double[Axis.COUNT]);

    private final double[] v;

    private Axes(double[] v) {
        this.v = v;
    }

    /** A copy of {@code values} (length {@link Axis#COUNT}) as a view. */
    public static Axes of(double[] values) {
        return new Axes(values.clone());
    }

    public double get(Axis axis) {
        return v[axis.ordinal()];
    }

    public double familiarity() {
        return v[Axis.FAM];
    }

    public double trust() {
        return v[Axis.TRU];
    }

    public double affection() {
        return v[Axis.AFF];
    }

    public double respect() {
        return v[Axis.RES];
    }

    public double attraction() {
        return v[Axis.ATT];
    }

    public double rivalry() {
        return v[Axis.RIV];
    }

    public double resentment() {
        return v[Axis.REN];
    }

    public double obligation() {
        return v[Axis.OBL];
    }

    public double warmth() {
        return warmth(v);
    }

    public double[] toArray() {
        return v.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Axes a && Arrays.equals(v, a.v);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(v);
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("Axes[");
        for (Axis a : Axis.values()) {
            b.append(a.key(), 0, 3).append('=').append(String.format("%.2f", v[a.ordinal()])).append(' ');
        }
        b.setCharAt(b.length() - 1, ']');
        return b.toString();
    }

    // ── arithmetic on raw vectors ──

    /** How warm the holder is toward the other: affection leads, then trust, then respect (-1..1). */
    static double warmth(double[] a) {
        return 0.6 * a[Axis.AFF] + 0.3 * a[Axis.TRU] + 0.1 * a[Axis.RES];
    }

    /** Every axis faded from {@code dtMs} ago (a new vector). */
    static double[] faded(double[] a, long dtMs) {
        double[] out = new double[Axis.COUNT];
        for (int i = 0; i < Axis.COUNT; i++) {
            out[i] = Axis.of(i).faded(a[i], dtMs);
        }
        return out;
    }

    /** {@code a + delta}, each axis clamped to its range (a new vector). */
    static double[] plus(double[] a, double[] delta) {
        double[] out = new double[Axis.COUNT];
        for (int i = 0; i < Axis.COUNT; i++) {
            out[i] = Axis.of(i).clamp(a[i] + delta[i]);
        }
        return out;
    }

    /** The largest magnitude among the axes that mean something once familiarity is set aside. */
    static double feeling(double[] a) {
        double m = 0;
        for (int i = 1; i < Axis.COUNT; i++) {
            m = Math.max(m, Math.abs(a[i]));
        }
        return m;
    }

    /**
     * How much an edge matters when a full list must drop one: the strongest of its score, its affection (either
     * sign), resentment, rivalry, half its attraction and a third of its familiarity, plus a bonus for the stage it
     * is in, so an estranged, rival, romantic or close edge outlives a faded neutral one. A scale of about 0..1.5.
     */
    static double significance(double score, double[] a, Stage stage) {
        double s = Math.abs(score) / 100;
        s = Math.max(s, Math.abs(a[Axis.AFF]));
        s = Math.max(s, a[Axis.REN]);
        s = Math.max(s, a[Axis.RIV]);
        s = Math.max(s, 0.5 * a[Axis.ATT]);
        s = Math.max(s, a[Axis.FAM] / 3);
        return s + stage.significanceBonus();
    }

    /**
     * The axes a stored row that has none would have had: from its score and the count of kind and hostile things
     * that built it. Positive scores read as affection (and some trust), negative as resentment, dislike and distrust.
     * Familiarity is zero only for an edge nothing ever touched.
     */
    static double[] legacy(double score, int kind, int hostile) {
        double[] a = new double[Axis.COUNT];
        double s = Math.max(-1, Math.min(1, score / 100));
        if (kind + hostile > 0 || s != 0) {
            a[Axis.FAM] = Math.min(1, Axis.familiarityFloor() + 0.04 * (kind + hostile));
        }
        if (s > 0) {
            a[Axis.AFF] = Math.min(1, 1.6 * s);
            a[Axis.TRU] = s;
            a[Axis.RES] = 0.3 * s;
        } else if (s < 0) {
            a[Axis.REN] = Math.min(1, -1.6 * s);
            a[Axis.AFF] = Math.max(-1, 0.8 * s);
            a[Axis.TRU] = 0.6 * s;
            a[Axis.RIV] = hostile >= 2 ? Math.min(1, -0.8 * s) : 0;
        }
        return a;
    }
}
