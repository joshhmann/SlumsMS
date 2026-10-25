package soloMapling.ArtificialPlayer.BotLifeSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

/** Provisional shadow policy: context eligibility is separate from execution support. No actions are called here. */
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

    public static List<Option> options(Context context, LifeProfile profile) {
        List<Option> options = new ArrayList<>();
        for (Activity activity : Activity.values()) {
            double pull = switch (activity) {
                case HANG_OUT, SHOW_OFF -> Math.min(1.2, .6 * context.friends())
                        + Math.min(.2, .04 * context.strangers())
                        - (context.dramaOff() ? 0 : Math.min(.8, .4 * context.rivals()));
                case HELP_FRIEND -> context.friendAsked() ? 1.5 : 0;
                default -> 0;
            };
            boolean eligible = context.eligible().containsKey(activity)
                    && (activity != Activity.HELP_FRIEND || context.friendAsked());
            options.add(new Option(activity, weights(activity), pull, profile.bias(activity), eligible,
                    context.eligible().getOrDefault(activity, "destination, activity or adapter not verified")));
        }
        return List.copyOf(options);
    }

    public static Result preview(Context context, LifeProfile profile, long nowMs) {
        return LifeDecision.evaluate(context.needs(), options(context, profile), context.current(), nowMs,
                INTERRUPT_MARGIN, 0, a -> 0, context.commitment());
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
        };
    }
}
