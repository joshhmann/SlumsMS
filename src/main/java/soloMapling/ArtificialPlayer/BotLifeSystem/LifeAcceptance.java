package soloMapling.ArtificialPlayer.BotLifeSystem;

import java.util.Optional;
import java.util.function.BooleanSupplier;

import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

/** Acceptance boundary, deliberately not installed on any bot tick. Only accepted work returns an action fact. */
public final class LifeAcceptance {
    private LifeAcceptance() {}
    public enum Status { SHADOW, HELD, NO_CHANGE, UNSUPPORTED, REJECTED, FAILED, ACCEPTED }
    public record Accepted(Activity activity, String fact) {
        public Accepted {
            java.util.Objects.requireNonNull(activity);
            if (fact == null || fact.isBlank()) throw new IllegalArgumentException("accepted work needs a fact");
        }
    }
    public record Attempt(Status status, Optional<Accepted> accepted) {}
    public interface Executor {
        boolean supports(Activity activity);
        /** Empty means refused; a fact describes accepted work, not completion of the whole trip. */
        Optional<Accepted> start(Activity activity);
    }

    public static Attempt tryStart(Result result, boolean enabled, BooleanSupplier stillFree, Executor executor) {
        if (!enabled) return no(Status.SHADOW);
        if (result.outcome() == Outcome.HOLD_COMMITMENT) return no(Status.HELD);
        if (result.outcome() != Outcome.CHOOSE || result.selected().isEmpty()) return no(Status.NO_CHANGE);
        Activity activity = result.selected().orElseThrow();
        try {
            if (!stillFree.getAsBoolean()) return no(Status.HELD);
            if (!executor.supports(activity)) return no(Status.UNSUPPORTED);
            // Eligibility can change since scoring (a conversation, trade or invite may have started).
            if (!stillFree.getAsBoolean()) return no(Status.HELD);
            Optional<Accepted> accepted = executor.start(activity);
            if (accepted.isEmpty()) return no(Status.REJECTED);
            if (accepted.get().activity() != activity) return no(Status.FAILED);
            return new Attempt(Status.ACCEPTED, accepted);
        } catch (RuntimeException failure) {
            return no(Status.FAILED);
        }
    }

    private static Attempt no(Status status) { return new Attempt(status, Optional.empty()); }
}
