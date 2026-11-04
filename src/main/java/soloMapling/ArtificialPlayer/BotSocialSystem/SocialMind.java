package soloMapling.ArtificialPlayer.BotSocialSystem;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Stage 1 of the social layer (ticket 181): what a roster bot is like, how it feels and what it lacks.
 *
 * <ul>
 *   <li><b>Traits</b>: 12 continuous values in [0, 1], fixed by the roster slot and the bot's Bartle point, never
 *       stored (a seed gives the same traits after every restart). They shape how strongly events land and how fast
 *       needs grow; they do not pick actions by themselves.</li>
 *   <li><b>Emotions</b>: 9 values in [0, 1] that events raise, that fade toward a baseline with a half-life each, and
 *       that persist with their timestamp. Decay is applied on read, so an idle bot costs nothing and a restart
 *       loses nothing but the clock time that passed (which decays them, as it should).</li>
 *   <li><b>Needs</b>: 8 values in [0, 1] that grow with time at a trait-scaled rate and fall when satisfied.</li>
 * </ul>
 *
 * All time comes in as an argument (callers pass {@link SocialSim#now()}), so tests and simulated days are exact.
 * Not thread-safe on its own: {@code LifeMemory} owns one and guards it with its monitor.
 */
public final class SocialMind {

    public enum Trait {
        SOCIABILITY, AGREEABLENESS, CONFIDENCE, EMPATHY, CURIOSITY, AMBITION, LOYALTY, PATIENCE, IMPULSIVENESS,
        JEALOUSY, HONESTY, RISK_TOLERANCE
    }

    /** Baseline: where the emotion settles. Half-life: ms for the gap to the baseline to halve. */
    public enum Emotion {
        HAPPINESS(0.5, 6 * H), ANGER(0, 3 * H), SADNESS(0, 5 * H), FEAR(0, 1 * H), LONELINESS(0, 8 * H),
        AFFECTION(0, 12 * H), ANXIETY(0, 2 * H), PRIDE(0, 6 * H), EMBARRASSMENT(0, 1 * H);

        public final double baseline;
        public final long halfLifeMs;

        Emotion(double baseline, long halfLifeMs) {
            this.baseline = baseline;
            this.halfLifeMs = halfLifeMs;
        }
    }

    /** Hours of growth from satisfied (0) to fully unmet (1), before the trait scaling. */
    public enum Need {
        COMPANIONSHIP(6), BELONGING(24), RECOGNITION(16), ACHIEVEMENT(10), SECURITY(30), AUTONOMY(20),
        EXPLORATION(12), REST(5);

        public final double hoursToFull;

        Need(double hoursToFull) {
            this.hoursToFull = hoursToFull;
        }
    }

    private static final long H = 3_600_000L;

    private final Map<Trait, Double> traits;
    private final Map<Emotion, double[]> emotions = new EnumMap<>(Emotion.class); // value, at
    private final Map<Need, double[]> needs = new EnumMap<>(Need.class);          // value, at
    private boolean touched;
    private int version;

    private SocialMind(Map<Trait, Double> traits) {
        this.traits = traits;
    }

    /**
     * A bot's mind at rest: traits from the slot and its Bartle point (socializers are more sociable, killers less
     * agreeable, explorers more curious, achievers more ambitious), emotions at their baselines, needs at a seeded
     * moderate level. Deterministic: the same inputs give the same mind.
     */
    public static SocialMind seeded(int slot, double socialPull, double rivalPull, double explorerPull,
                                    double achieverPull, long now) {
        SplittableRandom r = new SplittableRandom(slot * 0x9E3779B97F4A7C15L ^ 0x50C1A1L);
        Map<Trait, Double> t = new EnumMap<>(Trait.class);
        for (Trait trait : Trait.values()) {
            double lean = switch (trait) {
                case SOCIABILITY -> 0.35 * socialPull - 0.1 * rivalPull;
                case AGREEABLENESS -> 0.2 * socialPull - 0.3 * rivalPull;
                case CONFIDENCE -> 0.15 * achieverPull + 0.15 * rivalPull;
                case EMPATHY -> 0.25 * socialPull - 0.15 * rivalPull;
                case CURIOSITY -> 0.35 * explorerPull;
                case AMBITION -> 0.35 * achieverPull;
                case LOYALTY -> 0.2 * socialPull;
                case IMPULSIVENESS -> 0.2 * rivalPull;
                case JEALOUSY -> 0.15 * rivalPull;
                case RISK_TOLERANCE -> 0.2 * rivalPull + 0.1 * explorerPull;
                default -> 0;
            };
            t.put(trait, clamp(0.5 + lean + (r.nextDouble() - 0.5) * 0.6));
        }
        SocialMind mind = new SocialMind(t);
        for (Need need : Need.values()) {
            mind.needs.put(need, new double[]{0.2 + 0.3 * r.nextDouble(), now});
        }
        return mind;
    }

    public double trait(Trait trait) {
        return traits.get(trait);
    }

    public Map<Trait, Double> traits() {
        return Map.copyOf(traits);
    }

    // ── emotions ──

    /** The emotion now: the stored value pulled toward its baseline by the time since it was set. */
    public double emotion(Emotion e, long now) {
        double[] s = emotions.get(e);
        if (s == null) return e.baseline;
        double dt = Math.max(0, now - (long) s[1]);
        double keep = Math.pow(0.5, dt / (double) e.halfLifeMs);
        return e.baseline + (s[0] - e.baseline) * keep;
    }

    /**
     * An event moves an emotion by {@code amount} (negative lowers it), scaled by how strongly this bot reacts. The
     * old value is first decayed to {@code now}, so events stack on what is left, not on what was.
     */
    public void feel(Emotion e, double amount, long now) {
        double current = emotion(e, now);
        emotions.put(e, new double[]{clamp(current + amount * reactivity(e)), now});
        touched = true;
        version++;
    }

    /** Impulsive bots feel anger, fear and embarrassment harder; patient ones less; empathic ones feel affection more. */
    double reactivity(Emotion e) {
        return switch (e) {
            case ANGER -> 1.0 + 0.6 * (trait(Trait.IMPULSIVENESS) - 0.5) - 0.4 * (trait(Trait.PATIENCE) - 0.5);
            case FEAR, ANXIETY -> 1.2 - 0.5 * trait(Trait.CONFIDENCE);
            case EMBARRASSMENT -> 1.2 - 0.5 * trait(Trait.CONFIDENCE);
            case AFFECTION -> 0.6 + 0.8 * trait(Trait.EMPATHY);
            case PRIDE -> 0.6 + 0.8 * trait(Trait.CONFIDENCE);
            case LONELINESS -> 0.6 + 0.8 * trait(Trait.SOCIABILITY);
            default -> 1.0;
        };
    }

    /** One number for how the bot is doing: happiness up, the negative emotions down, in [-1, 1]. */
    public double mood(long now) {
        double bad = (emotion(Emotion.ANGER, now) + emotion(Emotion.SADNESS, now) + emotion(Emotion.FEAR, now)
                + emotion(Emotion.LONELINESS, now) + emotion(Emotion.ANXIETY, now)) / 5;
        return clamp1(emotion(Emotion.HAPPINESS, now) * 2 - 1 - bad);
    }

    // ── needs ──

    /** The need now: it grows with the time since it was set, faster or slower by trait. */
    public double need(Need n, long now) {
        double[] s = needs.get(n);
        double dt = Math.max(0, now - (long) s[1]);
        return clamp(s[0] + dt / (n.hoursToFull * H) * growth(n));
    }

    /** Needs grow faster in the bots that care about them. */
    double growth(Need n) {
        return switch (n) {
            case COMPANIONSHIP, BELONGING -> 0.5 + trait(Trait.SOCIABILITY);
            case RECOGNITION -> 0.5 + 0.6 * trait(Trait.CONFIDENCE) + 0.4 * trait(Trait.AMBITION);
            case ACHIEVEMENT -> 0.5 + trait(Trait.AMBITION);
            case SECURITY -> 1.3 - 0.6 * trait(Trait.RISK_TOLERANCE);
            case EXPLORATION -> 0.5 + trait(Trait.CURIOSITY);
            case AUTONOMY -> 0.6 + 0.5 * trait(Trait.IMPULSIVENESS);
            case REST -> 1.0;
        };
    }

    /** Meeting a need: it falls by {@code amount} from where it stands now. */
    public void satisfy(Need n, double amount, long now) {
        needs.put(n, new double[]{clamp(need(n, now) - amount), now});
        touched = true;
        version++;
    }

    /** The most pressing need now and its value: what an idle bot would reach for. */
    public Need strongest(long now) {
        Need best = Need.REST;
        double top = -1;
        for (Need n : Need.values()) {
            double v = need(n, now);
            if (v > top) {
                top = v;
                best = n;
            }
        }
        return best;
    }

    // ── persistence ──

    /** True once an event, a satisfied need or a restore changed anything: a pristine mind needs no row. */
    public boolean touched() {
        return touched;
    }

    /** Counts every change to an emotion or need; the owner saves when it moves. */
    public int version() {
        return version;
    }

    /** JSON-ready: emotion and need values with their timestamps. Traits are not stored (they come from the seed). */
    public Map<String, Object> save() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> em = new LinkedHashMap<>();
        emotions.forEach((e, s) -> em.put(e.name(), List.of(s[0], (long) s[1])));
        Map<String, Object> nd = new LinkedHashMap<>();
        needs.forEach((n, s) -> nd.put(n.name(), List.of(s[0], (long) s[1])));
        out.put("emotions", em);
        out.put("needs", nd);
        return out;
    }

    /** Reads a save into this mind; unknown names and bad rows are skipped, never fatal. */
    public void restore(Object saved) {
        if (!(saved instanceof Map<?, ?> map)) return;
        read(map.get("emotions"), name -> {
            try {
                return Emotion.valueOf(name);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }, (e, row) -> emotions.put((Emotion) e, row));
        read(map.get("needs"), name -> {
            try {
                return Need.valueOf(name);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }, (n, row) -> needs.put((Need) n, row));
        touched = true;
        version++;
    }

    private static void read(Object block, java.util.function.Function<String, Enum<?>> byName,
                             java.util.function.BiConsumer<Enum<?>, double[]> put) {
        if (!(block instanceof Map<?, ?> rows)) return;
        for (Map.Entry<?, ?> row : rows.entrySet()) {
            try {
                Enum<?> key = byName.apply(String.valueOf(row.getKey()));
                if (key != null && row.getValue() instanceof List<?> v && v.size() == 2
                        && v.get(0) instanceof Number value && v.get(1) instanceof Number at
                        && Double.isFinite(value.doubleValue())) {
                    put.accept(key, new double[]{clamp(value.doubleValue()), at.longValue()});
                }
            } catch (RuntimeException skipped) { /* next row */ }
        }
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double clamp1(double v) {
        return Math.max(-1, Math.min(1, v));
    }
}
