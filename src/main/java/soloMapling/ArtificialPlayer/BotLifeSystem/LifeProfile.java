package soloMapling.ArtificialPlayer.BotLifeSystem;

import java.util.EnumMap;
import java.util.Map;
import java.util.SplittableRandom;

import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity;

/** Provisional v1 calibration approved for the feature test branch; identity-stable, no persistence or drift. */
public record LifeProfile(Map<Archetype, Double> blend, long grindDurationMs, long townDurationMs) {
    public static final int VERSION = 1;
    public enum Archetype { GRINDER, SOCIALITE, SHOW_OFF, HELPER, MERCHANT, EXPLORER, SLACKER }

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

    public static LifeProfile forBot(int characterId) {
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
            case BREAK, LOG_OUT -> blend.get(Archetype.SLACKER);
        };
    }
}
