package soloMapling.ArtificialPlayer.BotLifeSystem;

import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import java.util.Optional;
import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity;

/** Only delegates to the activity owner; it never grants items or executes model instructions. */
public final class LifeExecutor implements LifeAcceptance.Executor {
    private final TrainingBot bot;
    public LifeExecutor(TrainingBot bot) { this.bot = bot; }
    public boolean supports(Activity activity) { return bot.canStartLifeActivity(activity, System.currentTimeMillis()); }
    public Optional<LifeAcceptance.Accepted> start(Activity activity) {
        try {
            return bot.startLifeActivity(activity) ? Optional.of(new LifeAcceptance.Accepted(activity,
                    "started " + activity.name().toLowerCase(java.util.Locale.ROOT)
                            + "; arrival, purchases, sales and rewards require their own confirmed events")) : Optional.empty();
        } catch (RuntimeException failure) {
            LifeTownSlots.release(bot.getChr().getId());
            throw failure;
        }
    }
}
