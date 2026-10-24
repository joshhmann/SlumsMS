package soloMapling.ArtificialPlayer.BotLifeSystem;

import soloMapling.ArtificialPlayer.BotCommitments;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.qa.QaSuite;

import java.util.Optional;

import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity;

/** Existing restock-trip adapter, not installed on a scheduler. Both QA and an explicit execute flag are required. */
public final class RestockTripExecutor implements LifeAcceptance.Executor {
    private final BotSM bot;

    public RestockTripExecutor(BotSM bot) {
        this.bot = java.util.Objects.requireNonNull(bot);
    }

    public boolean supports(Activity activity) {
        return activity == Activity.RESTOCK && bot instanceof TrainingBot;
    }

    public Optional<LifeAcceptance.Accepted> start(Activity activity) {
        if (!QaSuite.isEnabled() || !Boolean.getBoolean("slums.botlife.execute") || !supports(activity)
                || bot.getChr() == null || BotCommitments.inFlight(bot)
                || BotConversation.hasAnyChat(bot.getChr().getId()) || !BotPopulationDirector.mayLogOut(bot)
                || !((TrainingBot) bot).requestRestockTrip()) {
            return Optional.empty();
        }
        return Optional.of(new LifeAcceptance.Accepted(Activity.RESTOCK,
                "requested a town restock trip; purchase not yet confirmed"));
    }
}
