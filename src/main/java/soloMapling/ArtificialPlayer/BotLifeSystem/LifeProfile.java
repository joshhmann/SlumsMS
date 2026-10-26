package soloMapling.ArtificialPlayer.BotLifeSystem;

import java.util.EnumMap;
import java.util.Map;
import java.util.SplittableRandom;

import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity;

/**
 * A bot's daily-life bias over seven channels. v2 (1.6.0 S1): the seed comes from the bot's Bartle blend
 * ({@link SocialProfile}) through {@link #BARTLE_MAP}; MERCHANT and SLACKER are small flavour traits Bartle doesn't
 * drive. v1 (a uniform 1-in-7 primary) is kept as {@link #forBotV1} only so saved v1 drift can be carried over.
 * Identity-stable: the same character id always gets the same seed. Drift lives in {@link LifeMemory}.
 */
public record LifeProfile(Map<Archetype, Double> blend, long grindDurationMs, long townDurationMs) {
    public static final int VERSION = 2;
    public enum Archetype { GRINDER, SOCIALITE, SHOW_OFF, HELPER, MERCHANT, EXPLORER, SLACKER }

    /**
     * How each Bartle type feeds the five core channels (each row sums to 1). Achievers grind and show off,
     * Explorers move maps, Socializers hang out and help, Killers compete for mobs and flex.
     */
    static final Map<SocialProfile.Type, Map<Archetype, Double>> BARTLE_MAP = Map.of(
            SocialProfile.Type.ACHIEVER, Map.of(Archetype.GRINDER, .75, Archetype.SHOW_OFF, .25),
            SocialProfile.Type.EXPLORER, Map.of(Archetype.EXPLORER, .80, Archetype.GRINDER, .20),
            SocialProfile.Type.SOCIALIZER, Map.of(Archetype.SOCIALITE, .75, Archetype.HELPER, .25),
            SocialProfile.Type.KILLER, Map.of(Archetype.GRINDER, .50, Archetype.SHOW_OFF, .50));
    /** Every channel keeps this floor so later nudges and learning never start from zero. */
    static final double CHANNEL_FLOOR = .02;

    public LifeProfile {
        blend = Map.copyOf(blend);
        double total = blend.values().stream().mapToDouble(Double::doubleValue).sum();
        if (blend.size() != Archetype.values().length || blend.values().stream()
                .anyMatch(v -> !Double.isFinite(v) || v < 0 || v > 1) || Math.abs(total - 1) > 1e-9
                || grindDurationMs < 20 * 60_000L || grindDurationMs > 60 * 60_000L
                || townDurationMs < 5 * 60_000L || townDurationMs > 20 * 60_000L) {
            throw new IllegalArgumentException("invalid life profile");
        }
    }

    /** The v2 seed: this bot's Bartle blend mapped onto the seven channels, plus its flavour traits. */
    public static LifeProfile forBot(int characterId) {
        return fromSocial(SocialProfile.forCharacter(characterId), characterId);
    }

    /** The seven-channel seed for a Bartle blend; flavour and durations still come from the character id. */
    public static LifeProfile fromSocial(SocialProfile social, int characterId) {
        SplittableRandom random = new SplittableRandom(characterId * 0x94D049BB133111EBL ^ 0x160B2L);
        double merchant = .02 + .08 * random.nextDouble();
        double slacker = .02 + .08 * random.nextDouble();
        Map<Archetype, Double> mixed = new EnumMap<>(Archetype.class);
        for (Archetype type : Archetype.values()) mixed.put(type, 0.0);
        social.blend().forEach((bartle, share) -> BARTLE_MAP.get(bartle)
                .forEach((channel, weight) -> mixed.merge(channel, share * weight, Double::sum)));
        // Sharpen the mix (square, renormalise) so the leading channel keeps about the weight v1's primary had
        // (.55-.70 there); a plain linear mix spreads a 55% Bartle lead too thin over two or three channels.
        double squares = 0;
        for (Archetype type : Archetype.values()) squares += mixed.get(type) * mixed.get(type);
        for (Archetype type : Archetype.values()) mixed.put(type, mixed.get(type) * mixed.get(type) / squares);
        double core = 1 - merchant - slacker - CHANNEL_FLOOR * Archetype.values().length;
        Map<Archetype, Double> blend = new EnumMap<>(Archetype.class);
        double sum = 0;
        for (Archetype type : Archetype.values()) {
            double flavour = type == Archetype.MERCHANT ? merchant : type == Archetype.SLACKER ? slacker : 0;
            double value = CHANNEL_FLOOR + flavour + core * mixed.get(type);
            blend.put(type, value);
            sum += value;
        }
        double drift = 1 - sum;
        blend.merge(Archetype.GRINDER, drift, Double::sum);
        LifeProfile v1 = forBotV1(characterId);
        return new LifeProfile(blend, v1.grindDurationMs(), v1.townDurationMs());
    }

    /** The 1.6.0-sprint v1 seed (uniform primary). Only for carrying saved v1 drift over and for durations. */
    public static LifeProfile forBotV1(int characterId) {
        SplittableRandom random = new SplittableRandom(characterId * 0x9E3779B97F4A7C15L ^ 0x160B07L);
        Archetype primary = Archetype.values()[random.nextInt(Archetype.values().length)];
        double primaryShare = .55 + .15 * random.nextDouble();
        Map<Archetype, Double> blend = new EnumMap<>(Archetype.class);
        double remainingTotal = 0;
        for (Archetype type : Archetype.values()) {
            if (type != primary) {
                double value = .1 + random.nextDouble();
                blend.put(type, value);
                remainingTotal += value;
            }
        }
        for (Archetype type : Archetype.values()) {
            blend.put(type, type == primary ? primaryShare : blend.get(type) / remainingTotal * (1 - primaryShare));
        }
        return new LifeProfile(blend, (20 + random.nextInt(41)) * 60_000L,
                (5 + random.nextInt(16)) * 60_000L);
    }

    public Archetype primary() {
        return java.util.Arrays.stream(Archetype.values())
                .max(java.util.Comparator.comparingDouble(a -> blend.get(a))).orElseThrow();
    }

    public double bias(Activity activity) {
        return .6 * switch (activity) {
            case GRIND -> blend.get(Archetype.GRINDER);
            case MOVE_MAP -> blend.get(Archetype.EXPLORER);
            case RESTOCK -> .5 * blend.get(Archetype.GRINDER);
            case SELL, FREE_MARKET -> blend.get(Archetype.MERCHANT);
            case HANG_OUT -> blend.get(Archetype.SOCIALITE);
            case HELP_FRIEND -> blend.get(Archetype.HELPER);
            case SHOW_OFF -> blend.get(Archetype.SHOW_OFF);
            case PARTY_QUEST -> .5 * (blend.get(Archetype.GRINDER) + blend.get(Archetype.SOCIALITE));
            case BREAK, LOG_OUT, FISH -> blend.get(Archetype.SLACKER);
            case CHANGE_CHANNEL -> 0; // 1.6.0 S8: no channel bias; the Bartle term and the crowd decide (LifePolicy)
            case INVITE_PQ, INVITE_GUILD -> .5 * blend.get(Archetype.SOCIALITE); // 1.6.0 S6 (Bartle in SocialInitiative)
        };
    }
}
