package soloMapling.ArtificialPlayer.BotLifeSystem;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

/**
 * Ticket 60's pure daily-life scorer. Call on an interrupt or activity completion, never per combat tick.
 * All calibration, time and noise are supplied by the caller; this class cannot execute or announce an action.
 * Missing needs are unknown, not observed zeroes. Their contribution is omitted from a partial preview.
 */
public final class LifeDecision {
    private LifeDecision() {}

    public enum Need { SUPPLIES, BAG_SPACE, BOREDOM, SOCIAL, FATIGUE, PROGRESS }
    /**
     * The action catalogue. 1.6.0 S8 adds CHANGE_CHANNEL (a crowded map or a KS war: "cc", then the same map on a
     * quieter channel). Slice S6 appends its initiative actions after it: INVITE_PQ (a bot in a PQ lobby invites a
     * real player of the PQ's level band, "lf kpq? u in") and INVITE_GUILD (a friend bot asks a guildless player into
     * its guild). Both report how the player answered through {@link LifeOutcomes} (SocialInitiative).
     * 1.6.0 world events append EVENT: drop what it is doing and head to a running world event it was offered
     * (soloMapling.worldevents: WorldEventBias leans it by Bartle type, the event's map is the destination).
     */
    public enum Activity { GRIND, MOVE_MAP, RESTOCK, SELL, HANG_OUT, HELP_FRIEND, SHOW_OFF, PARTY_QUEST, FREE_MARKET,
        BREAK, LOG_OUT, FISH, CHANGE_CHANNEL, INVITE_PQ, INVITE_GUILD, EVENT }
    public enum Outcome { CHOOSE, KEEP_CURRENT, HOLD_COMMITMENT, NO_AVAILABLE_OPTION }

    public record Needs(Map<Need, Double> values) {
        public Needs {
            values = Map.copyOf(values);
            values.values().forEach(v -> unit(v, "need"));
        }
    }

    /** Unavailable options are excluded, even if a context or personality bonus would otherwise win. */
    public record Option(Activity activity, Map<Need, Double> weights, double contextBonus,
                         double personalityBonus, boolean available, String reason) {
        public Option {
            Objects.requireNonNull(activity);
            weights = Map.copyOf(weights);
            weights.values().forEach(v -> finite(v, "weight"));
            finite(contextBonus, "context bonus");
            finite(personalityBonus, "personality bonus");
            Objects.requireNonNull(reason);
        }
    }

    /** Expected duration and stickiness are input, not production defaults. No current activity uses null. */
    public record Current(Activity activity, long startedAtMs, long expectedDurationMs, double stickiness) {
        public Current {
            Objects.requireNonNull(activity);
            if (expectedDurationMs <= 0) {
                throw new IllegalArgumentException("expected duration must be positive");
            }
            nonnegative(stickiness, "stickiness");
        }

        double bonus(long nowMs) {
            // Double subtraction avoids overflowing a long for malformed/backwards timestamps.
            double elapsed = Math.max(0, (double) nowMs - startedAtMs);
            return stickiness * Math.max(0, 1 - elapsed / expectedDurationMs);
        }
    }

    public record Score(Activity activity, Map<Need, Double> contributions, double contextBonus,
                        double personalityBonus, double noise, double stickiness, double total, String reason) {
        public Score {
            contributions = Map.copyOf(contributions);
        }
    }

    /** Ranking explains scores; selected also respects commitments and the margin, so may differ from rank one. */
    public record Result(List<Score> ranking, Optional<Activity> selected, Outcome outcome) {
        public Result {
            ranking = List.copyOf(ranking);
            Objects.requireNonNull(selected);
            Objects.requireNonNull(outcome);
        }

        public List<Score> topThree() {
            return ranking.subList(0, Math.min(3, ranking.size()));
        }
    }

    /** Noise returns a stable value in [-1, 1] per activity. A zero amplitude never calls it. */
    public static Result evaluate(Needs needs, List<Option> options, Current current, long nowMs,
                                  double interruptMargin, double noiseAmplitude,
                                  ToDoubleFunction<Activity> noise, boolean commitmentInFlight) {
        Objects.requireNonNull(needs);
        Objects.requireNonNull(noise);
        nonnegative(interruptMargin, "interrupt margin");
        nonnegative(noiseAmplitude, "noise amplitude");
        if (options.stream().map(Option::activity).distinct().count() != options.size()) {
            throw new IllegalArgumentException("duplicate activity");
        }
        List<Score> ranking = options.stream().filter(Option::available)
                .map(option -> score(needs, option, current, nowMs, noiseAmplitude, noise))
                .sorted(Comparator.comparingDouble(Score::total).reversed().thenComparing(Score::activity))
                .toList();
        if (commitmentInFlight) {
            return new Result(ranking, Optional.ofNullable(current).map(Current::activity), Outcome.HOLD_COMMITMENT);
        }
        if (ranking.isEmpty()) {
            return new Result(ranking, Optional.empty(), Outcome.NO_AVAILABLE_OPTION);
        }
        Score best = ranking.getFirst();
        if (current != null) {
            Optional<Score> previous = ranking.stream().filter(s -> s.activity() == current.activity()).findFirst();
            if (previous.isPresent() && (best.activity() == current.activity()
                    || best.total() - previous.get().total() <= interruptMargin)) {
                return new Result(ranking, Optional.of(current.activity()), Outcome.KEEP_CURRENT);
            }
        }
        return new Result(ranking, Optional.of(best.activity()), Outcome.CHOOSE);
    }

    private static Score score(Needs needs, Option option, Current current, long nowMs,
                               double amplitude, ToDoubleFunction<Activity> noise) {
        Map<Need, Double> contributions = new java.util.EnumMap<>(Need.class);
        // Enum order makes floating-point accumulation independent of input map iteration order.
        double total = 0;
        for (Need need : Need.values()) {
            if (needs.values().containsKey(need) && option.weights().containsKey(need)) {
                double contribution = needs.values().get(need) * option.weights().get(need);
                contributions.put(need, contribution);
                total += contribution;
            }
        }
        double random = 0;
        if (amplitude > 0) {
            double sample = noise.applyAsDouble(option.activity());
            finite(sample, "noise sample");
            if (Math.abs(sample) > 1) {
                throw new IllegalArgumentException("noise sample must be in [-1, 1]");
            }
            random = amplitude * sample;
        }
        double sticky = current != null && current.activity() == option.activity() ? current.bonus(nowMs) : 0;
        total += option.contextBonus() + option.personalityBonus() + random + sticky;
        finite(total, "total score");
        return new Score(option.activity(), contributions, option.contextBonus(), option.personalityBonus(), random,
                sticky, total, option.reason());
    }

    private static void finite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void nonnegative(double value, String name) {
        finite(value, name);
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void unit(double value, String name) {
        finite(value, name);
        if (value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be in [0, 1]");
        }
    }
}
