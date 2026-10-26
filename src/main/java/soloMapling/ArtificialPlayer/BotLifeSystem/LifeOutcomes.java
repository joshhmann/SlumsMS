package soloMapling.ArtificialPlayer.BotLifeSystem;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity;

/**
 * 1.6.0 S8: how a chosen activity ended, success or failure, for whoever learns from it.
 *
 * <p>Fired once per started activity: when the bot's {@link LifeActivityQueue} closes the activity (COMPLETED is a
 * success, CANCELLED a failure), and by executors that know better ({@link #report}). The brain itself listens
 * ({@link LifeRuntime}): a finished plan step makes its soft fact true ({@link LifePlanner#SOFT}).
 *
 * <p>Plug-in points for later slices:
 * <ul>
 *   <li>S9 (learning): {@link #listen} a {@link Listener} and turn outcomes into bounded reward updates
 *   ({@code LifeMemory.push}). Listeners run on the bot's update thread: keep them O(1), never block, never throw.</li>
 *   <li>S6 (initiative: INVITE_PQ, greet): add the activities to {@link LifeDecision.Activity}, their eligibility to the
 *   host's {@code canStartLifeActivity}, any extra pull with {@link LifePolicy#addBias}, and call {@link #report} with
 *   the real result (invite accepted or declined, greeting answered) when the queue's end does not say it.</li>
 * </ul>
 */
public final class LifeOutcomes {
    private LifeOutcomes() {}

    /** One finished activity. {@code detail} is a short machine-friendly reason ("completed", "cancelled", ...). */
    public record Outcome(int botId, Activity activity, boolean success, long at, String detail) {
        public Outcome {
            Objects.requireNonNull(activity);
            Objects.requireNonNull(detail);
        }
    }

    @FunctionalInterface
    public interface Listener {
        void onOutcome(Outcome outcome);
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public static void listen(Listener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public static void remove(Listener listener) {
        listeners.remove(listener);
    }

    /** Tells every listener; a listener that throws is skipped, never the bot's tick. */
    public static void report(Outcome outcome) {
        for (Listener listener : listeners) {
            try {
                listener.onOutcome(outcome);
            } catch (RuntimeException ignored) {
                // a learning hook must never break the bot that reported
            }
        }
    }

    public static void report(int botId, Activity activity, boolean success, long at, String detail) {
        report(new Outcome(botId, activity, success, at, detail));
    }
}
