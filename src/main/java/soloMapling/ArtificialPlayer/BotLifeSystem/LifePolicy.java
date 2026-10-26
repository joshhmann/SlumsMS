package soloMapling.ArtificialPlayer.BotLifeSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

/**
 * Provisional shadow policy: context eligibility is separate from execution support. No actions are called here.
 *
 * <p>1.6.0 S8: {@link #options(Context, LifeProfile, Situation)} adds what the brain knows beyond needs and people:
 * the bot's point on Bartle's chart ({@link #bartle}: Killers lean to rivalry, KS spats and showing off, Socializers to
 * hanging out, helping and parties, Explorers to new maps, fishing and other channels, Achievers to grinding and
 * bossing), an unasked chance to help a friend or join a PQ that fits, a crowded map or a KS war (CHANGE_CHANNEL),
 * and the plan's next step ({@link LifePlanner}). The two-argument form is the pre-S8 policy, kept for the golden
 * cases.
 */
public final class LifePolicy {
    private LifePolicy() {}
    public static final double INTERRUPT_MARGIN = .25;
    public static final double STICKINESS = .8;

    public record Context(Needs needs, Map<Activity, String> eligible, int friends, int strangers, int rivals,
                          boolean friendAsked, boolean dramaOff, boolean commitment, Current current,
                          List<LifeGoals.Goal> goals, List<String> unknown) {
        public Context {
            eligible = Map.copyOf(eligible);
            goals = List.copyOf(goals);
            unknown = List.copyOf(unknown);
            if (friends < 0 || strangers < 0 || rivals < 0) throw new IllegalArgumentException("negative people count");
        }
    }

    /**
     * 1.6.0 S8: the facts beyond {@link Context}. {@code social} null = no Bartle term. {@code crowd} is the map's bot
     * load against its cap (1 = at the cap); {@code helpOffered}/{@code pqOpen} are an unasked friend to help and a PQ
     * lobby that fits the bot's level and the PQ hours; {@code planStep}/{@code planBonus} the planner's next step.
     */
    public record Situation(SocialProfile social, boolean helpOffered, boolean pqOpen, double crowd, boolean ksWar,
                            Activity planStep, double planBonus, int botId) {
        public static final Situation NONE = new Situation(null, false, false, 0, false, null, 0, 0);

        public Situation {
            if (!Double.isFinite(crowd) || crowd < 0 || !Double.isFinite(planBonus) || planBonus < 0) {
                throw new IllegalArgumentException("invalid situation");
            }
        }
    }

    /** A pluggable extra pull (S6 initiative, S9 learned deltas). Must be cheap, pure and bounded; never throws. */
    @FunctionalInterface
    public interface Bias {
        double bonus(Activity activity, Context context, Situation situation);
    }

    private static final List<Bias> biases = new CopyOnWriteArrayList<>();
    /** Every plug-in bias together is clamped to this, so no hook can take over the brain. */
    public static final double MAX_PLUGIN_BIAS = .5;

    public static void addBias(Bias bias) { biases.add(java.util.Objects.requireNonNull(bias)); }

    public static void removeBias(Bias bias) { biases.remove(bias); }

    /**
     * How strongly each Bartle type pulls toward each activity; a bot's term is its blend times this table.
     * Rows are tuned against the TEST mix (audit finding 4): no row is larger than a strong need.
     */
    static final Map<SocialProfile.Type, Map<Activity, Double>> BARTLE = Map.of(
            SocialProfile.Type.ACHIEVER, Map.of(Activity.GRIND, .30, Activity.PARTY_QUEST, .22,
                    Activity.SHOW_OFF, .08, Activity.MOVE_MAP, .05),
            SocialProfile.Type.EXPLORER, Map.of(Activity.MOVE_MAP, .32, Activity.FISH, .30,
                    Activity.FREE_MARKET, .10, Activity.CHANGE_CHANNEL, .06, Activity.HANG_OUT, .04),
            SocialProfile.Type.SOCIALIZER, Map.of(Activity.HANG_OUT, .14, Activity.HELP_FRIEND, .36,
                    Activity.PARTY_QUEST, .26, Activity.FISH, .10, Activity.FREE_MARKET, .05),
            SocialProfile.Type.KILLER, Map.of(Activity.SHOW_OFF, .32, Activity.GRIND, .16,
                    Activity.PARTY_QUEST, .06));

    /** The Bartle term of one activity for this point (0 without a point). */
    public static double bartle(SocialProfile social, Activity activity) {
        if (social == null) return 0;
        double sum = 0;
        for (SocialProfile.Type type : SocialProfile.Type.values()) {
            sum += social.share(type) * BARTLE.get(type).getOrDefault(activity, 0.0);
        }
        return sum;
    }

    public static List<Option> options(Context context, LifeProfile profile) {
        return options(context, profile, Situation.NONE);
    }

    public static List<Option> options(Context context, LifeProfile profile, Situation situation) {
        List<Option> options = new ArrayList<>();
        double killer = situation.social() == null ? 0 : situation.social().rivalPull();
        boolean s8 = situation != Situation.NONE;
        for (Activity activity : Activity.values()) {
            // Rivals push most bots out of town; a Killer-leaning bot is drawn to them instead (flex, trash talk).
            double rivalFactor = situation.social() == null ? 1 : Math.max(-.6, Math.min(1, 1 - 3 * killer));
            double pull = switch (activity) {
                case HANG_OUT, SHOW_OFF -> Math.min(1.2, .6 * context.friends())
                        + Math.min(.2, .04 * context.strangers())
                        - (context.dramaOff() ? 0 : Math.min(.8, .4 * context.rivals()) * rivalFactor)
                        + (activity == Activity.SHOW_OFF ? Math.min(.3, .05 * context.strangers() * killer) : 0);
                case HELP_FRIEND -> context.friendAsked() ? 1.5 : situation.helpOffered() ? HELP_PULL : 0;
                case PARTY_QUEST -> situation.pqOpen() ? PQ_PULL : 0;
                case CHANGE_CHANNEL -> channelPull(situation, killer);
                // A Killer in a KS war stays and fights for the mobs.
                // A packed map is a bad grind (every mob contested), a KS war a worse one, except for a Killer.
                case GRIND -> (s8 ? GRIND_HABIT - Math.min(.8, Math.max(0, 2 * (situation.crowd() - CROWDED))) : 0)
                        + (situation.ksWar() ? .5 * killer : 0);
                default -> 0;
            };
            if (activity == situation.planStep()) pull += situation.planBonus();
            double personality = profile.bias(activity) + bartle(situation.social(), activity) + plugins(activity,
                    context, situation);
            boolean eligible = context.eligible().containsKey(activity)
                    && (activity != Activity.HELP_FRIEND || context.friendAsked() || situation.helpOffered())
                    && (activity != Activity.CHANGE_CHANNEL || situation.crowd() >= CROWDED || situation.ksWar());
            options.add(new Option(activity, s8 ? tuned(activity) : weights(activity), pull, personality, eligible,
                    context.eligible().getOrDefault(activity, "destination, activity or adapter not verified")));
        }
        return List.copyOf(options);
    }

    /** The pull of an unasked friend to help, and of a PQ lobby that fits (both S8). */
    static final double HELP_PULL = .55, PQ_PULL = .35;
    /** Most of a 2009 day was grinding: a standing pull toward it, so a full need list isn't needed to keep at it. */
    static final double GRIND_HABIT = .3;

    /**
     * 1.6.0 S8 retune of the need weights (audit finding 4: town won every idle moment). Time in town now fills the
     * SOCIAL need ({@link LifeRuntime#accepted}), so a town stay answers less of it; bags weigh less on grinding (bots
     * sell on their errands); helping and fishing answer more of a bot's needs. The pre-S8 table stays for the
     * two-argument policy.
     */
    private static Map<Need, Double> tuned(Activity activity) {
        return switch (activity) {
            case GRIND -> Map.of(Need.PROGRESS, 1.4, Need.SUPPLIES, -1.6, Need.BAG_SPACE, -.4, Need.FATIGUE, -.5);
            case HANG_OUT -> Map.of(Need.SOCIAL, .7, Need.BOREDOM, .2);
            case HELP_FRIEND -> Map.of(Need.SOCIAL, .6, Need.PROGRESS, .3);
            case FISH -> Map.of(Need.BOREDOM, .7, Need.SOCIAL, .3, Need.FATIGUE, .5, Need.SUPPLIES, -.5);
            case FREE_MARKET -> Map.of(Need.BAG_SPACE, .8, Need.BOREDOM, .3);
            default -> weights(activity);
        };
    }

    /** A map this full (bots against its cap) counts as crowded. */
    public static final double CROWDED = .8;

    /** "too many ppl": grows from the crowded mark to the cap; a KS war adds to it, less for a Killer who enjoys it. */
    static double channelPull(Situation situation, double killer) {
        double crowd = situation.crowd() < CROWDED ? 0 : Math.min(1.2, .5 + 2.5 * (situation.crowd() - CROWDED));
        double ks = situation.ksWar() ? Math.max(0, .7 * (1 - 2.5 * killer)) : 0;
        return crowd + ks;
    }

    private static double plugins(Activity activity, Context context, Situation situation) {
        if (biases.isEmpty()) return 0;
        double sum = 0;
        for (Bias bias : biases) {
            try {
                double b = bias.bonus(activity, context, situation);
                if (Double.isFinite(b)) sum += b;
            } catch (RuntimeException ignored) { /* a hook never breaks a decision */ }
        }
        return Math.max(-MAX_PLUGIN_BIAS, Math.min(MAX_PLUGIN_BIAS, sum));
    }

    public static Result preview(Context context, LifeProfile profile, long nowMs) {
        return LifeDecision.evaluate(context.needs(), options(context, profile), context.current(), nowMs,
                INTERRUPT_MARGIN, 0, a -> 0, context.commitment());
    }

    /** The need weights per activity (shared by every bot; personality and context sit on top). */
    public static Map<Need, Double> weightsOf(Activity activity) {
        return weights(activity);
    }

    private static Map<Need, Double> weights(Activity activity) {
        return switch (activity) {
            case GRIND -> Map.of(Need.PROGRESS, 1.4, Need.SUPPLIES, -1.6, Need.BAG_SPACE, -.8, Need.FATIGUE, -.5);
            case RESTOCK -> Map.of(Need.SUPPLIES, 3.0);
            case SELL -> Map.of(Need.BAG_SPACE, 2.0);
            case HANG_OUT -> Map.of(Need.SOCIAL, 1.0, Need.BOREDOM, .4);
            case MOVE_MAP -> Map.of(Need.BOREDOM, 1.2);
            case HELP_FRIEND -> Map.of(Need.SOCIAL, .3);
            case SHOW_OFF -> Map.of(Need.SOCIAL, .5, Need.BOREDOM, .2);
            case PARTY_QUEST -> Map.of(Need.PROGRESS, .8, Need.SOCIAL, .5);
            case FREE_MARKET -> Map.of(Need.BAG_SPACE, .6, Need.BOREDOM, .3);
            case BREAK -> Map.of(Need.FATIGUE, 1.7, Need.BOREDOM, .3);
            case LOG_OUT -> Map.of(Need.FATIGUE, 2.0);
            case FISH -> Map.of(Need.BOREDOM, .6, Need.SOCIAL, .3, Need.FATIGUE, .2, Need.SUPPLIES, -.5);
            case CHANGE_CHANNEL -> Map.of(Need.BOREDOM, .2);
        };
    }
}
