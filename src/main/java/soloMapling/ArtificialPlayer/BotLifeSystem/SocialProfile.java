package soloMapling.ArtificialPlayer.BotLifeSystem;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * 1.6.0 S1: a bot's Bartle blend (Achiever / Explorer / Socializer / Killer) with one leading type. It sits on top of
 * the seven {@link LifeProfile} bias channels: {@link LifeProfile#forBot} maps this blend onto them, and MERCHANT and
 * SLACKER stay flavour traits that Bartle doesn't drive.
 *
 * <p>The seed is drawn from the bot's roster slot only, so the same slot gets the same profile on every boot and on
 * every server. The leading type follows the owner's mix (Socializer 40%, Achiever 30%, Explorer 20%, Killer 10%)
 * through a golden-ratio (Weyl) sequence on the slot rather than a random roll, so any run of consecutive slots
 * matches the mix to well within a percent while neighbouring slots still differ.
 *
 * <p>Learned change (slice S9) never edits the seed: {@link LifeMemory} stores a bounded delta beside it and
 * {@link #withDelta} makes the effective blend, so learning can decay back toward who the bot was seeded as.
 */
public record SocialProfile(Map<Type, Double> blend) {

    public enum Type { ACHIEVER, EXPLORER, SOCIALIZER, KILLER }

    /** Leading-type mix, in the order the Weyl draw walks it: Socializer 40, Achiever 30, Explorer 20, Killer 10. */
    static final Type[] DRAW_ORDER = {Type.SOCIALIZER, Type.ACHIEVER, Type.EXPLORER, Type.KILLER};
    static final double[] DRAW_SHARE = {.40, .30, .20, .10};
    /** The leading type's share of the blend; the other three split the rest and always stay below it. */
    static final double LEAD_MIN = .50, LEAD_MAX = .70;
    /** How far learning may move one axis away from the seed (slice S9 fills the delta; S1 only bounds it). */
    public static final double MAX_DELTA = .15;

    public SocialProfile {
        blend = Map.copyOf(blend);
        double total = blend.values().stream().mapToDouble(Double::doubleValue).sum();
        if (blend.size() != Type.values().length || blend.values().stream()
                .anyMatch(v -> v == null || !Double.isFinite(v) || v < 0 || v > 1) || Math.abs(total - 1) > 1e-9) {
            throw new IllegalArgumentException("invalid social profile");
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

    /** The seeded blend of this slot: deterministic, no state, a few hundred nanoseconds. */
    public static SocialProfile forSlot(int slot) {
        Type lead = leadingTypeOf(slot);
        SplittableRandom random = new SplittableRandom(slot * 0xBF58476D1CE4E5B9L ^ 0xBA27L);
        double leadShare = LEAD_MIN + (LEAD_MAX - LEAD_MIN) * random.nextDouble();
        Map<Type, Double> weights = new EnumMap<>(Type.class);
        double rest = 0;
        for (Type type : Type.values()) {
            if (type != lead) {
                // Each of the others is at most 1/(1+.2+.2) of the remainder: below .36 when the lead is >= .50.
                double w = .2 + .8 * random.nextDouble();
                weights.put(type, w);
                rest += w;
            }
        }
        Map<Type, Double> blend = new EnumMap<>(Type.class);
        double others = 0;
        for (Type type : Type.values()) {
            if (type != lead) {
                double share = weights.get(type) / rest * (1 - leadShare);
                blend.put(type, share);
                others += share;
            }
        }
        blend.put(lead, 1 - others);
        return new SocialProfile(blend);
    }

    /** The leading type a slot is drawn: a Weyl sequence in 64-bit fixed point, so it is exact and stable. */
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

    public Type leading() {
        return Arrays.stream(Type.values()).max(Comparator.comparingDouble(blend::get)).orElseThrow();
    }

    public double share(Type type) {
        return blend.get(type);
    }

    /**
     * The Killer disposition, 0..1: how strongly this bot is pulled toward rivalry, KS spats and trash talk. A hook
     * for the spice slices (S7) and the utility bias (S8); nothing in S1 acts on it. Killer-led bots sit at .5 or
     * more, everyone else below .36.
     */
    public double rivalPull() {
        return blend.get(Type.KILLER);
    }

    /** Socializer share, 0..1: the pull toward hanging out, crews and initiative (S6/S8 hook). */
    public double socialPull() {
        return blend.get(Type.SOCIALIZER);
    }

    /** The effective blend after a learned delta (each axis clamped to +-{@link #MAX_DELTA}), renormalised. */
    public SocialProfile withDelta(Map<Type, Double> delta) {
        if (delta == null || delta.isEmpty()) {
            return this;
        }
        Map<Type, Double> moved = new EnumMap<>(Type.class);
        double total = 0;
        for (Type type : Type.values()) {
            double d = clampDelta(delta.getOrDefault(type, 0.0));
            double v = Math.max(0, blend.get(type) + d);
            moved.put(type, v);
            total += v;
        }
        if (total <= 0) {
            return this;
        }
        return new SocialProfile(normalise(moved, total));
    }

    static double clampDelta(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return 0;
        }
        return Math.max(-MAX_DELTA, Math.min(MAX_DELTA, value));
    }

    private static Map<Type, Double> normalise(Map<Type, Double> values, double total) {
        Map<Type, Double> out = new EnumMap<>(Type.class);
        double sum = 0;
        Type last = null;
        for (Type type : Type.values()) {
            double v = values.get(type) / total;
            out.put(type, v);
            sum += v;
            last = type;
        }
        out.put(last, Math.max(0, out.get(last) + (1 - sum)));
        return out;
    }

    /** "SOCIALIZER-led: A 12% E 18% S 58% K 12%". */
    public String describe() {
        return String.format(Locale.ROOT, "%s-led: A %.0f%% E %.0f%% S %.0f%% K %.0f%%", leading(),
                100 * blend.get(Type.ACHIEVER), 100 * blend.get(Type.EXPLORER), 100 * blend.get(Type.SOCIALIZER),
                100 * blend.get(Type.KILLER));
    }
}
