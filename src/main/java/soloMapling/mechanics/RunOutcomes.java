package soloMapling.mechanics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static soloMapling.BotLogger.log;

/**
 * The one writer of {@link RunOutcome}: {@link #record} is called exactly once for each run that ends, by
 * {@code MechanicsRuns}, and by nothing else. It keeps the most recent {@link #KEEP} outcomes in memory, writes one
 * line to the bot log, and hands the outcome to every {@link #listen listener}: the bots' run chatter is one of them
 * ({@link RunChatter#onOutcome}), a spectator view or a wager would be others. A line said in chat is a symptom of the
 * outcome, never the record of it.
 *
 * <p><b>Not durable yet.</b> The ring is lost on a restart. The durable record the plan calls for is an append-only
 * table (a numbered migration with its rollback, written through the bots' database discipline); it is not built here,
 * and nothing in this class needs to change when it is: it is one more listener.
 *
 * <p>A listener that throws is logged and skipped; it can neither stop the other listeners nor reach the run's end.
 */
public final class RunOutcomes {

    /** How many outcomes the ring keeps. */
    public static final int KEEP = 500;

    private static final ConcurrentLinkedDeque<RunOutcome> recent = new ConcurrentLinkedDeque<>();
    private static final List<Consumer<RunOutcome>> listeners = new CopyOnWriteArrayList<>();

    private RunOutcomes() {
    }

    /** Keeps the outcome, logs it and tells every listener. Returns it. */
    static RunOutcome record(RunOutcome outcome) {
        recent.addLast(outcome);
        while (recent.size() > KEEP) {
            recent.pollFirst();
        }
        log("[Mechanics] " + outcome.line());
        for (Consumer<RunOutcome> listener : listeners) {
            try {
                listener.accept(outcome);
            } catch (RuntimeException | LinkageError e) {
                log("[Mechanics] an outcome listener threw for " + outcome.runId() + ": " + e);
            }
        }
        return outcome;
    }

    /** The most recent outcomes, oldest first. */
    public static List<RunOutcome> recent() {
        return new ArrayList<>(recent);
    }

    /** Tells {@code listener} about every outcome from now on. */
    public static void listen(Consumer<RunOutcome> listener) {
        listeners.add(listener);
    }

    /** Stops telling {@code listener}. */
    public static void unlisten(Consumer<RunOutcome> listener) {
        listeners.remove(listener);
    }

    /** Test hook: forget every outcome (the listeners stay). */
    static void clearForTest() {
        recent.clear();
    }
}
