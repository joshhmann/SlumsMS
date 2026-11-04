package soloMapling.ArtificialPlayer.BotAffinitySystem;

import java.util.EnumMap;
import java.util.Map;

/**
 * Ticket 271 stage 2: the one table of what each event kind does to each relationship axis, for both edge kinds
 * ({@link BondEvent}: bot to bot, {@link AffinityEvent}: bot to player), and the one place the holder's character
 * scales it. Nothing else in the code names an axis delta.
 *
 * <p>A row is the change on the holder's own edge, in axis units (see {@link Axis}), for the holder being the one the
 * event happened to (the victim of a KS, the receiver of a gift). Who gets a row is the caller's choice and is
 * deliberately lopsided: a KS sours only the victim's edge; a gift warms the receiver's affection and obligation more
 * than the giver's ({@link BondEvent#GIFT_GIVEN}). Column order: familiarity, trust, affection, respect, attraction,
 * rivalry, resentment, obligation.
 *
 * <p>Scaling ({@link #scaled}), all factors exactly 1 for a neutral {@link Persona}:
 * <ul>
 *   <li>positive trust, affection, respect and attraction: Bartle Socializer share ({@link BondRules#scale}) x
 *   agreeableness (0.75..1.25); negative ones: Killer share x (1.25 - 0.5 agreeableness); negative trust also x
 *   (1.2 - 0.4 loyalty), a loyal bot forgives a slip;</li>
 *   <li>positive affection and obligation x empathy (0.8..1.2); positive obligation also x loyalty (0.7..1.3);</li>
 *   <li>rivalry x jealousy (0.6..1.4); resentment gained x Killer share x (1.3 - 0.6 agreeableness), resentment
 *   released (an apology) x (0.8 + 0.4 agreeableness);</li>
 *   <li>familiarity is not scaled: everyone gets to know whoever they meet.</li>
 * </ul>
 * Each row is first multiplied by the event's operator weight ({@code BondEvent.weight()}: how far
 * {@code -Dsolomapling.bonds.<event>} moved the score delta from its default), so tuning an event's weight tunes its
 * axes with it, and an event tuned to 0 moves nothing.
 */
final class AxisTable {

    private static final Map<BondEvent, double[]> BOND = new EnumMap<>(BondEvent.class);
    private static final Map<AffinityEvent, double[]> TIE = new EnumMap<>(AffinityEvent.class);

    private static double[] row(double fam, double trust, double aff, double resp, double attr, double riv,
                                double res, double obl) {
        return new double[]{fam, trust, aff, resp, attr, riv, res, obl};
    }

    static {
        //                                        fam   trust   aff    resp   attr   riv    res    obl
        BOND.put(BondEvent.PARTY,      row(0.08,  0.04,  0.06,  0.02,  0,     0,     0,     0));
        BOND.put(BondEvent.PARTY_TIME, row(0.03,  0.03,  0.03,  0.01,  0,     0,     0,     0));
        BOND.put(BondEvent.THREAD,     row(0.04,  0.01,  0.03,  0,     0,     0,     0,     0));
        BOND.put(BondEvent.GUILD,      row(0.05,  0.02,  0.04,  0,     0,     0,     0,     0.02));
        BOND.put(BondEvent.PQ,         row(0.10,  0.08,  0.06,  0.06,  0,     0,     0,     0.02));
        BOND.put(BondEvent.BUFF,       row(0.02,  0.02,  0.02,  0.02,  0,     0,     0,     0.04));
        BOND.put(BondEvent.TRADE,      row(0.03,  0.05,  0.02,  0.01,  0,     0,     0,     0));
        BOND.put(BondEvent.GIFT,       row(0.03,  0.03,  0.09,  0.01,  0,     0,     0,     0.12));
        // the giver: knows the other a little better, a touch fonder, and the other owes it (negative obligation)
        BOND.put(BondEvent.GIFT_GIVEN, row(0.03,  0.01,  0.03,  0.01,  0,     0,     0,    -0.06));
        BOND.put(BondEvent.NEAR,       row(0.02,  0,     0.005, 0,     0,     0,     0,     0));
        // stage 4 social acts: a compliment warms and earns respect; an apology and a reconciliation release resentment
        // and rivalry (negative numbers) and rebuild trust; an argument is a harder SPAT
        BOND.put(BondEvent.COMPLIMENT, row(0.02,  0.01,  0.04,  0.03,  0,     0,     0,     0));
        BOND.put(BondEvent.APOLOGY,    row(0.02,  0.08,  0.08,  0.01,  0,    -0.08, -0.22,  0));
        BOND.put(BondEvent.RECONCILE,  row(0.04,  0.12,  0.12,  0.03,  0,    -0.12, -0.25,  0));
        BOND.put(BondEvent.ARGUE,      row(0.03, -0.08, -0.12, -0.05,  0,     0.08,  0.20,  0));
        BOND.put(BondEvent.KS,         row(0.02, -0.04, -0.05, -0.03,  0,     0.05,  0.08,  0));
        BOND.put(BondEvent.SPAT,       row(0.03, -0.03, -0.05, -0.02,  0,     0.05,  0.05,  0));

        TIE.put(AffinityEvent.PARTY,        row(0.08,  0.04,  0.06,  0.02,  0,     0,     0,     0));
        TIE.put(AffinityEvent.BUFF,         row(0.02,  0.02,  0.03,  0.02,  0,     0,     0,     0.05));
        TIE.put(AffinityEvent.HEAL,         row(0.03,  0.03,  0.03,  0.02,  0,     0,     0,     0.06));
        TIE.put(AffinityEvent.RESCUE,       row(0.05,  0.08,  0.08,  0.06,  0,     0,     0,     0.12));
        TIE.put(AffinityEvent.TRADE,        row(0.04,  0.05,  0.04,  0.01,  0,     0,     0,     0));
        TIE.put(AffinityEvent.GIFT,         row(0.04,  0.04,  0.09,  0.02,  0,     0,     0,     0.14));
        TIE.put(AffinityEvent.FAME,         row(0.02,  0,     0.02,  0.12,  0,     0,     0,     0));
        TIE.put(AffinityEvent.KIND_CHAT,    row(0.03,  0.01,  0.03,  0,     0,     0,     0,     0));
        TIE.put(AffinityEvent.ROMANCE,      row(0.04,  0.03,  0.05,  0,     0.07,  0,     0,     0));
        TIE.put(AffinityEvent.APOLOGY,      row(0.02,  0.06,  0.12,  0,     0,    -0.15, -0.30,  0));
        TIE.put(AffinityEvent.FAVOR,        row(0.03,  0.05,  0.06,  0.04,  0,     0,     0,     0.10));
        TIE.put(AffinityEvent.KS,           row(0.02, -0.04, -0.05, -0.03,  0,     0.04,  0.08,  0));
        TIE.put(AffinityEvent.DROP_STEAL,   row(0.02, -0.10, -0.06, -0.06,  0,     0.03,  0.10,  0));
        TIE.put(AffinityEvent.DEFAME,       row(0.02, -0.15, -0.15, -0.12,  0,     0.06,  0.22,  0));
        TIE.put(AffinityEvent.RUDE_CHAT,    row(0.02, -0.03, -0.08, -0.05,  0,     0,     0.07,  0));
        TIE.put(AffinityEvent.SCAM,         row(0.03, -0.22, -0.10, -0.10,  0,     0,     0.20,  0));
        TIE.put(AffinityEvent.HEARSAY_GOOD, row(0,     0.02,  0.02,  0,     0,     0,     0,     0));
        TIE.put(AffinityEvent.HEARSAY_BAD,  row(0,    -0.03, -0.02,  0,     0,     0,     0.01,  0));
    }

    /**
     * Ticket 271 stage 5, a promise between two bots, on the PROMISED-TO bot's own edge toward the one who promised (the
     * only edge that learns of it). Kept: it trusts and respects a little more and feels it owes a little back. Broken: trust
     * and respect fall, a touch of resentment. Not a {@link BondEvent}: the score does not move, only the axes.
     */
    private static final double[] PROMISE_KEPT = row(0.02, 0.05, 0.01, 0.02, 0, 0, 0, 0.03);
    private static final double[] PROMISE_BROKEN = row(0.01, -0.06, -0.02, -0.02, 0, 0, 0.03, 0);

    private AxisTable() {
    }

    /** The promise row (kept or broken) after the holder's character; a new vector. */
    static double[] promise(boolean kept, Persona p) {
        return scaled((kept ? PROMISE_KEPT : PROMISE_BROKEN).clone(), 1.0, p);
    }

    /** The raw row of a bond event (a copy). */
    static double[] row(BondEvent event) {
        return BOND.get(event).clone();
    }

    /** The raw row of an affinity event (a copy). */
    static double[] row(AffinityEvent event) {
        return TIE.get(event).clone();
    }

    static boolean isZero(double[] row) {
        for (double d : row) {
            if (d != 0) {
                return false;
            }
        }
        return true;
    }

    /** The bond event's row after the operator weight and the holder's character. */
    static double[] scaled(BondEvent event, Persona p) {
        return scaled(row(event), event.weight(), p);
    }

    /** The affinity event's row at {@code weight} (an explicit delta over the default one) and the holder's character. */
    static double[] scaled(AffinityEvent event, double weight, Persona p) {
        return scaled(row(event), weight, p);
    }

    private static double[] scaled(double[] row, double weight, Persona p) {
        double socialShare = BondRules.scale(1, p.social(), p.killer());
        double killerShare = BondRules.scale(-1, p.social(), p.killer());
        double agree = p.agreeableness();
        for (int i = 0; i < Axis.COUNT; i++) {
            double d = row[i] * weight;
            if (d != 0) {
                switch (i) {
                    case Axis.TRU -> d *= d > 0 ? socialShare * (0.75 + 0.5 * agree)
                            : killerShare * (1.25 - 0.5 * agree) * (1.2 - 0.4 * p.loyalty());
                    case Axis.AFF -> d *= d > 0 ? socialShare * (0.75 + 0.5 * agree) * (0.8 + 0.4 * p.empathy())
                            : killerShare * (1.25 - 0.5 * agree);
                    case Axis.RES -> d *= d > 0 ? socialShare * (0.75 + 0.5 * agree)
                            : killerShare * (1.25 - 0.5 * agree);
                    case Axis.ATT -> d *= d > 0 ? socialShare * (0.75 + 0.5 * agree) : 1;
                    case Axis.RIV -> d *= d > 0 ? 0.6 + 0.8 * p.jealousy() : 1;
                    case Axis.REN -> d *= d > 0 ? killerShare * (1.3 - 0.6 * agree) : 0.8 + 0.4 * agree;
                    case Axis.OBL -> d *= d > 0 ? (0.7 + 0.6 * p.loyalty()) * (0.8 + 0.4 * p.empathy()) : 1;
                    default -> { // familiarity: unscaled
                    }
                }
            }
            row[i] = d;
        }
        return row;
    }
}
