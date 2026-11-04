package soloMapling.ArtificialPlayer.BotAffinitySystem;

import java.util.concurrent.TimeUnit;

/**
 * 1.6.0 S2: the arithmetic of bot-to-bot bonds, pure and tested. The ideas of {@link AffinityRules} (which stays the
 * bot-to-player rule book, owned by slice S3) carried over to bot pairs:
 * <ul>
 *   <li>Scores run -100..100 and fade toward 0 in <b>weeks</b> (owner, 2026-10-26): a friendship halves every
 *   {@code friendHalfLifeDays} (42, six weeks), a grudge every {@code grudgeHalfLifeDays} (14, two weeks).</li>
 *   <li>A state needs history, with the same thresholds as {@link AffinityRules}: FRIEND at
 *   {@link AffinityRules#friendAt()} (25) with 3 kind things, RIVAL at {@link AffinityRules#rivalAt()} (-25) with 2
 *   hostile things, NEMESIS at {@link AffinityRules#nemesisAt()} (-65) with 5.</li>
 *   <li>A bond faded under {@link AffinityRules#FADED} forgets its history (and may be dropped).</li>
 *   <li>The delta is scaled by the bot's disposition (Bartle): socializers bond faster, killers sour faster.</li>
 * </ul>
 * The half-lives are {@code -Dsolomapling.bonds.*} properties.
 */
public final class BondRules {

    public static final double MAX = 100;

    private BondRules() {
    }

    static double prop(String key, double fallback) {
        try {
            return Double.parseDouble(System.getProperty("solomapling.bonds." + key, Double.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static long friendHalfLifeMs() {
        return (long) (prop("friendHalfLifeDays", 42) * TimeUnit.DAYS.toMillis(1));
    }

    public static long grudgeHalfLifeMs() {
        return (long) (prop("grudgeHalfLifeDays", 14) * TimeUnit.DAYS.toMillis(1));
    }

    /** The score {@code dtMs} after it was {@code score}: halves every half-life (grudges faster than friendships). */
    public static double faded(double score, long dtMs) {
        if (score == 0 || dtMs <= 0) {
            return score;
        }
        long halfLife = score < 0 ? grudgeHalfLifeMs() : friendHalfLifeMs();
        if (halfLife <= 0) {
            return score;
        }
        return score * Math.pow(0.5, dtMs / (double) halfLife);
    }

    public static double scoreAt(Bond bond, long now) {
        return faded(bond.score, now - bond.updatedAt);
    }

    /**
     * Whether the bond has faded to nothing (it may be dropped): its score is under {@link AffinityRules#FADED} and no
     * axis but familiarity still holds a feeling (0.03, as 3 of 100). A grudge the score forgot but resentment kept is
     * not dropped.
     */
    public static boolean faded(Bond bond, long now) {
        return Math.abs(scoreAt(bond, now)) < AffinityRules.FADED && Axes.feeling(axesAt(bond, now)) < 0.03;
    }

    /** Which state the bond is in now (NEUTRAL for none). */
    public static AffinityState state(Bond bond, long now) {
        if (bond == null) {
            return AffinityState.NEUTRAL;
        }
        double s = scoreAt(bond, now);
        if (s >= AffinityRules.friendAt() && bond.kind >= 3) {
            return AffinityState.FRIEND;
        }
        if (s <= AffinityRules.nemesisAt() && bond.hostile >= 5) {
            return AffinityState.NEMESIS;
        }
        if (s <= AffinityRules.rivalAt() && bond.hostile >= 2) {
            return AffinityState.RIVAL;
        }
        return AffinityState.NEUTRAL;
    }

    /**
     * How a bot's Bartle disposition scales a delta: the Socializer share speeds up kind events, the Killer share
     * hostile ones. A share of 0.25 (an even blend) is x1; the result is kept within 0.6..1.8.
     */
    public static double scale(int delta, double socialShare, double killerShare) {
        double share = delta >= 0 ? socialShare : killerShare;
        return Math.max(0.6, Math.min(1.8, 1 + 2 * (share - 0.25)));
    }

    /**
     * Applies one event at {@code now} with an already-scaled {@code delta} to the score, and the event's
     * {@link AxisTable} row to the axes (a neutral character). See the {@link Persona} overload.
     */
    public static boolean apply(Bond bond, BondEvent event, double delta, long now, boolean ignoreCooldown) {
        return apply(bond, event, delta, now, ignoreCooldown, Persona.NEUTRAL);
    }

    /**
     * Applies one event at {@code now}: {@code delta} (already Bartle-scaled) to the score exactly as before, and the
     * event's {@link AxisTable} row, scaled by {@code persona}, to the faded axes; then the stage moves at most one
     * rung ({@link Stage#step}) from where time left it. False (nothing changes) when the pair's cooldown for the
     * event is still running (unless {@code ignoreCooldown}: QA acceleration only), or the delta is 0 and the event
     * has no axis effect either. An event with a 0 delta and an axis row ({@link BondEvent#GIFT_GIVEN}) moves the
     * axes only.
     */
    public static boolean apply(Bond bond, BondEvent event, double delta, long now, boolean ignoreCooldown,
                                Persona persona) {
        double[] row = AxisTable.scaled(event, persona);
        if (delta == 0 && AxisTable.isZero(row)) {
            return false;
        }
        long last = bond.lastAt(event);
        long cooldown = event.cooldownMs();
        if (!ignoreCooldown && last > 0 && cooldown > 0 && now - last < cooldown) {
            return false;
        }
        double current = scoreAt(bond, now);
        double[] axes = axesAt(bond, now);
        Stage prev = Stage.settle(bond.stage, axes, false);
        if (Math.abs(current) < AffinityRules.FADED) {
            bond.kind = 0;      // a faded history is forgotten: earning a state starts over
            bond.hostile = 0;
        }
        bond.score = Math.max(-MAX, Math.min(MAX, current + delta));
        if (delta > 0) {
            bond.kind = Math.min(999, bond.kind + 1);
        } else if (delta < 0) {
            bond.hostile = Math.min(999, bond.hostile + 1);
        }
        bond.axes = Axes.plus(axes, row);
        bond.stage = Stage.step(prev, bond.axes, true);
        bond.updatedAt = now;
        bond.stamp(event, now);
        bond.dirty = true;
        return true;
    }

    /** GM / QA staging: puts the bond squarely in a state as of now. */
    public static void stage(Bond bond, AffinityState state, long now) {
        switch (state) {
            case FRIEND -> set(bond, 60, 5, 0);
            case RIVAL -> set(bond, -40, 0, 3);
            case NEMESIS -> set(bond, -85, 0, 8);
            case NEUTRAL -> set(bond, 0, 0, 0);
        }
        bond.updatedAt = now;
        bond.lastAt = null;
        bond.dirty = true;
    }

    private static void set(Bond bond, double score, int kind, int hostile) {
        bond.score = score;
        bond.kind = kind;
        bond.hostile = hostile;
        bond.axes = Axes.legacy(score, kind, hostile);
        bond.stage = Stage.settle(Stage.STRANGER, bond.axes, true);
    }

    // ── ticket 181 stage 2: axes and stage ──

    /** The bond's eight axes faded to {@code now} (a new vector; each axis by its own half-life). */
    static double[] axesAt(Bond bond, long now) {
        return Axes.faded(bond.axes, now - bond.updatedAt);
    }

    /** The bond's stage at {@code now}: the stored stage, cooled by whatever time has faded (never raised by time). */
    static Stage stageAt(Bond bond, long now) {
        return Stage.settle(bond.stage, axesAt(bond, now), false);
    }

    /** How much the bond matters when a full list must drop one ({@link Axes#significance}). */
    static double significance(Bond bond, long now) {
        double[] axes = axesAt(bond, now);
        return Axes.significance(scoreAt(bond, now), axes, Stage.settle(bond.stage, axes, false));
    }
}
