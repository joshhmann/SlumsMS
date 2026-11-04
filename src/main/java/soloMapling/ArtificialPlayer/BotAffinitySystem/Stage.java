package soloMapling.ArtificialPlayer.BotAffinitySystem;

import java.util.Locale;

/**
 * Ticket 271 stage 2: where a directed relationship stands, derived from its {@link Axes} <b>and the stage it was in
 * before</b>, so a stage is sticky: every stage is entered at a higher threshold than it is left at (hysteresis), and
 * a stage moves at most one rung per event. The stored stage is what carries the history. This is separate from, and
 * does not change, the legacy {@link AffinityState} (NEUTRAL, FRIEND, RIVAL, NEMESIS) the old consumers read.
 *
 * <pre>
 *  STRANGER - ACQUAINTANCE - FRIEND - CLOSE_FRIEND          (warmth ladder, one rung at a time)
 *                               \-- ROMANTIC (from FRIEND / CLOSE_FRIEND)
 *  RIVAL      from anyone who is not a friend, when rivalry or resentment is high and affection is low
 *  ESTRANGED  from FRIEND / CLOSE_FRIEND / ROMANTIC only: resentment high with affection fallen, or affection gone
 *             negative. A friendship that merely faded drops down the ladder to ACQUAINTANCE, never to ESTRANGED.
 * </pre>
 * {@code warmth} is {@link Axes#warmth()}. Enter / leave thresholds:
 * <ul>
 *   <li>ACQUAINTANCE: familiarity 0.07 / 0.04 (the familiarity floor, 0.08, keeps a met pair above the leave line)</li>
 *   <li>FRIEND: warmth 0.30 with familiarity 0.20 and affection 0.15 / warmth 0.18</li>
 *   <li>CLOSE_FRIEND: warmth 0.62 with familiarity 0.45 and trust 0.35 / warmth 0.45 or trust 0.20</li>
 *   <li>ROMANTIC: attraction 0.45 with affection 0.35 / attraction 0.25 or affection 0.15</li>
 *   <li>RIVAL: (rivalry 0.35 or resentment 0.40) with affection under 0.25 / rivalry under 0.18 and resentment under 0.22</li>
 *   <li>ESTRANGED: (resentment 0.25 with warmth under 0.30) or affection at most -0.08, or a friend whose warmth falls
 *       under 0.18 while resentment is at least 0.15 / resentment under 0.15 and affection above -0.03</li>
 * </ul>
 */
public enum Stage {
    STRANGER(0), ACQUAINTANCE(0), FRIEND(0.2), CLOSE_FRIEND(0.4), RIVAL(0.4), ROMANTIC(0.5), ESTRANGED(0.5);

    private static final Stage[] ALL = values();

    private final double bonus;

    Stage(double bonus) {
        this.bonus = bonus;
    }

    /** Added to an edge's significance when a full list picks what to drop. */
    double significanceBonus() {
        return bonus;
    }

    /** The stable number stored in the database (the declaration order; never reorder, only append). */
    public int code() {
        return ordinal();
    }

    public static Stage ofCode(int code) {
        return code >= 0 && code < ALL.length ? ALL[code] : STRANGER;
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether this stage is one a friendship can be broken from. */
    boolean closeish() {
        return this == FRIEND || this == CLOSE_FRIEND || this == ROMANTIC;
    }

    /**
     * One rung from {@code prev} given {@code axes}. {@code up} false lets the stage only fall or cool (time passing
     * never makes friends; an interaction does).
     */
    static Stage step(Stage prev, double[] a, boolean up) {
        if (prev == null) {
            prev = STRANGER;
        }
        double fam = a[Axis.FAM], aff = a[Axis.AFF], trust = a[Axis.TRU], res = a[Axis.REN], riv = a[Axis.RIV];
        double attr = a[Axis.ATT], warmth = Axes.warmth(a);
        switch (prev) {
            case ESTRANGED:
                if (res < 0.15 && aff > -0.03) {
                    return fam >= 0.04 ? ACQUAINTANCE : STRANGER;
                }
                return ESTRANGED;
            case RIVAL:
                if (riv < 0.18 && res < 0.22) {
                    return fam >= 0.04 ? ACQUAINTANCE : STRANGER;
                }
                return RIVAL;
            default:
                break;
        }
        if (prev.closeish() && (res >= 0.25 && warmth < 0.30 || aff <= -0.08)) {
            return ESTRANGED;
        }
        if (!prev.closeish() && (riv >= 0.35 || res >= 0.40) && aff < 0.25) {
            return RIVAL;
        }
        switch (prev) {
            case STRANGER:
                return up && fam >= 0.07 ? ACQUAINTANCE : STRANGER;
            case ACQUAINTANCE:
                if (up && warmth >= 0.30 && fam >= 0.20 && aff >= 0.15) {
                    return FRIEND;
                }
                return fam < 0.04 ? STRANGER : ACQUAINTANCE;
            case FRIEND:
                if (up && attr >= 0.45 && aff >= 0.35) {
                    return ROMANTIC;
                }
                if (up && warmth >= 0.62 && fam >= 0.45 && trust >= 0.35) {
                    return CLOSE_FRIEND;
                }
                if (warmth < 0.18) {
                    return res >= 0.15 ? ESTRANGED : ACQUAINTANCE; // fell with a grudge, or merely faded
                }
                return FRIEND;
            case CLOSE_FRIEND:
                if (up && attr >= 0.45 && aff >= 0.35) {
                    return ROMANTIC;
                }
                return warmth < 0.45 || trust < 0.20 ? FRIEND : CLOSE_FRIEND;
            case ROMANTIC:
                if (attr < 0.25 || aff < 0.15) {
                    return warmth >= 0.18 ? FRIEND : res >= 0.15 ? ESTRANGED : ACQUAINTANCE;
                }
                return ROMANTIC;
            default:
                return prev;
        }
    }

    /** Repeats {@link #step} until it stops moving (at most 8 times). */
    static Stage settle(Stage prev, double[] a, boolean up) {
        Stage s = prev == null ? STRANGER : prev;
        for (int i = 0; i < 8; i++) {
            Stage next = step(s, a, up);
            if (next == s) {
                break;
            }
            s = next;
        }
        return s;
    }
}
