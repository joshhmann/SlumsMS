package soloMapling.ArtificialPlayer.BotLifeSystem;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * 1.6.0 S1: where a bot sits on Bartle's chart. Bartle is a plane, not four sliders: x runs from PLAYERS (-1) to
 * WORLD (+1), y from INTERACTING (-1) to ACTING (+1). The corners are the four types: Killer (-1,+1), Achiever (+1,+1),
 * Socializer (-1,-1), Explorer (+1,-1). A bot near the centre is a mix; a bot deep in a corner is pure.
 *
 * <p>The blend of the four types comes from the point, bilinear, so it sums to 1 by itself and the leading type is
 * the quadrant. Everything downstream ({@link #rivalPull}, {@link #socialPull}, {@link LifeProfile#fromSocial},
 * {@link SocialSelf}) reads the blend.
 *
 * <p>The seed is drawn from the roster slot only, so the same slot gets the same point on every boot. The quadrant
 * follows the owner's mix (Socializer 40%, Achiever 30%, Explorer 20%, Killer 10%) through a golden-ratio (Weyl)
 * sequence on the slot, so any run of consecutive slots matches it exactly while neighbouring slots differ. The depth
 * inside the quadrant and the bot's {@linkplain #plasticityOf plasticity} are seeded per slot too.
 *
 * <p>Learning never moves the seed: {@link LifeMemory} stores a drift vector beside it, capped at a radius of
 * {@code plasticity x 1.2}, and {@link #drifted} makes the effective point. A very plastic bot can drift into another
 * quadrant over weeks; a set-in-its-ways bot never can.
 */
public record SocialProfile(double x, double y) {

    public enum Type {
        ACHIEVER(1, 1), EXPLORER(1, -1), SOCIALIZER(-1, -1), KILLER(-1, 1);

        /** The corner of the chart: x = world (+1) vs players (-1), y = acting (+1) vs interacting (-1). */
        public final int cornerX, cornerY;

        Type(int cornerX, int cornerY) {
            this.cornerX = cornerX;
            this.cornerY = cornerY;
        }
    }

    /**
     * What pushes a bot across the chart, and which way: one table that life events use now and slice S9's outcome
     * learning extends. Each push moves the point {@link #STEP} x plasticity x strength toward its direction.
     */
    public enum Push {
        // Life events (LifeMemory.Event) in S1.
        JOB_ADVANCE(Type.ACHIEVER, 1), BIG_DROP(Type.ACHIEVER, 1), MARRIAGE(Type.SOCIALIZER, 1),
        GUILD_WAR(Type.KILLER, 1),
        /** A boomed scroll: a small push toward Killer, mostly up the acting axis (frustration). */
        BOOM(-.5, 1, .5),
        // Outcome hooks for S9 (not wired in S1).
        PARTY(Type.SOCIALIZER, 1), CHAT(Type.SOCIALIZER, .5), KS(Type.KILLER, 1), ROAST(Type.KILLER, .5),
        GRIND(Type.ACHIEVER, .5), BOSS(Type.ACHIEVER, 1), NEW_MAP(Type.EXPLORER, 1), QUEST(Type.EXPLORER, .5);

        /** Unit direction on the chart. */
        public final double dx, dy;
        /** Relative size of one push (1 = a full step). */
        public final double strength;

        Push(Type toward, double strength) {
            this(toward.cornerX, toward.cornerY, strength);
        }

        Push(double dx, double dy, double strength) {
            double length = Math.hypot(dx, dy);
            this.dx = dx / length;
            this.dy = dy / length;
            this.strength = strength;
        }
    }

    /** Leading-type mix, in the order the Weyl draw walks it: Socializer 40, Achiever 30, Explorer 20, Killer 10. */
    static final Type[] DRAW_ORDER = {Type.SOCIALIZER, Type.ACHIEVER, Type.EXPLORER, Type.KILLER};
    static final double[] DRAW_SHARE = {.40, .30, .20, .10};
    /** Seeded depth of each coordinate inside its quadrant: 0.15 (near the centre) .. 0.95 (deep in the corner). */
    static final double DEPTH_MIN = .15, DEPTH_MAX = .95;
    /** Plasticity: 0.05 + 0.55 u^3, so most bots are set in their ways and about 18% are above 0.35. */
    static final double PLASTICITY_MIN = .05, PLASTICITY_SPAN = .55;
    /** How far a bot may drift from its seed point, per unit of plasticity. */
    public static final double RADIUS_PER_PLASTICITY = 1.2;
    /** One full push moves the point this far times the bot's plasticity. */
    public static final double STEP = .04;

    public SocialProfile {
        if (!Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x) > 1 || Math.abs(y) > 1) {
            throw new IllegalArgumentException("invalid social point");
        }
    }

    /** The roster slot of a character id (bots are {@code BOT_ID_BASE + slot}; anything else is its own id). */
    public static int slotOf(int characterId) {
        int base = soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE;
        return characterId >= base ? characterId - base : characterId;
    }

    public static SocialProfile forCharacter(int characterId) {
        return forSlot(slotOf(characterId));
    }

    /** The seeded point of this slot: deterministic, no state. */
    public static SocialProfile forSlot(int slot) {
        Type quadrant = leadingTypeOf(slot);
        SplittableRandom random = new SplittableRandom(slot * 0xBF58476D1CE4E5B9L ^ 0xBA27L);
        return new SocialProfile(quadrant.cornerX * depth(random.nextDouble()),
                quadrant.cornerY * depth(random.nextDouble()));
    }

    /** Depth of one coordinate: skewed toward the middle (u^1.5), so mixed bots are common and pure ones exist. */
    static double depth(double u) {
        return DEPTH_MIN + (DEPTH_MAX - DEPTH_MIN) * Math.pow(u, 1.5);
    }

    /** How impressionable this slot is, 0.05..0.60: it scales the learning rate and the drift radius. */
    public static double plasticityOf(int slot) {
        double u = new SplittableRandom(slot * 0xD1B54A32D192ED03L ^ 0x9A57L).nextDouble();
        return PLASTICITY_MIN + PLASTICITY_SPAN * u * u * u;
    }

    /** The seeded pull back toward the seed point per day (S9 applies it): set-in-their-ways bots snap back faster. */
    public static double pullBackOf(double plasticity) {
        return .02 + .08 * (1 - plasticity);
    }

    /** The quadrant a slot is drawn: a Weyl sequence in 64-bit fixed point, so it is exact and stable. */
    static Type leadingTypeOf(int slot) {
        double u = (((slot + 1L) * 0x9E3779B97F4A7C15L) >>> 11) * 0x1.0p-53;
        for (int i = 0; i < DRAW_ORDER.length; i++) {
            if (u < DRAW_SHARE[i]) {
                return DRAW_ORDER[i];
            }
            u -= DRAW_SHARE[i];
        }
        return DRAW_ORDER[DRAW_ORDER.length - 1];
    }

    /** The four types' shares, bilinear in the point; they sum to 1. */
    public Map<Type, Double> blend() {
        Map<Type, Double> blend = new EnumMap<>(Type.class);
        for (Type type : Type.values()) {
            blend.put(type, share(type));
        }
        return blend;
    }

    public double share(Type type) {
        return (1 + type.cornerX * x) * (1 + type.cornerY * y) / 4;
    }

    /** The leading type: the quadrant the point is in (on an axis, world and acting win the tie). */
    public Type leading() {
        boolean world = x >= 0, acting = y >= 0;
        return world ? (acting ? Type.ACHIEVER : Type.EXPLORER) : (acting ? Type.KILLER : Type.SOCIALIZER);
    }

    /** How deep in its corner the point is, 0 (centre) .. 1 (pure). */
    public double depth() {
        return Math.max(Math.abs(x), Math.abs(y));
    }

    /**
     * The Killer disposition, 0..1: the pull toward rivalry, KS spats and trash talk. A hook for the spice slices (S7)
     * and the utility bias (S8); nothing in S1 acts on it.
     */
    public double rivalPull() {
        return share(Type.KILLER);
    }

    /** Socializer share, 0..1: the pull toward hanging out, crews and initiative (S6/S8 hook). */
    public double socialPull() {
        return share(Type.SOCIALIZER);
    }

    /**
     * The effective point after a drift (dx, dy) from this seed: the drift is cut to {@code plasticity x 1.2} and the
     * point clamped to the chart.
     */
    public SocialProfile drifted(double dx, double dy, double plasticity) {
        double[] capped = capDrift(dx, dy, plasticity);
        return new SocialProfile(clamp(x + capped[0]), clamp(y + capped[1]));
    }

    /** The drift {dx, dy} after one more push from drift (dx, dy): capped by plasticity and kept on the chart. */
    public double[] push(double dx, double dy, Push push, double plasticity) {
        double step = STEP * plasticity * push.strength;
        double[] capped = capDrift(dx + push.dx * step, dy + push.dy * step, plasticity);
        return new double[]{clamp(x + capped[0]) - x, clamp(y + capped[1]) - y};
    }

    static double[] capDrift(double dx, double dy, double plasticity) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy)) {
            return new double[]{0, 0};
        }
        double radius = RADIUS_PER_PLASTICITY * Math.max(0, plasticity);
        double length = Math.hypot(dx, dy);
        if (length <= radius || length == 0) {
            return new double[]{dx, dy};
        }
        return new double[]{dx / length * radius, dy / length * radius};
    }

    private static double clamp(double v) {
        return Math.max(-1, Math.min(1, v));
    }

    /** "SOCIALIZER (-0.62,-0.41): A 10% E 20% S 52% K 18%". */
    public String describe() {
        return String.format(Locale.ROOT, "%s (%+.2f,%+.2f): A %.0f%% E %.0f%% S %.0f%% K %.0f%%", leading(), x, y,
                100 * share(Type.ACHIEVER), 100 * share(Type.EXPLORER), 100 * share(Type.SOCIALIZER),
                100 * share(Type.KILLER));
    }
}
