package soloMapling.mechanics;

import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Tier;

import java.util.List;
import java.util.Map;

/**
 * Runs one piece of content for one party (ticket 130, "The orchestrator"). Generalised from {@code OPQOrchestrator}:
 * a blackboard per run, roles claimed per stage ({@link RoleClaims}), the stage's {@link StageSolver} asked for each
 * bot's next step, stage clears read from the event instance, failure and retry handled here. {@link Orchestrator}
 * is the logic (no server); {@link MechanicsRuns} drives it on the game server and carries the steps out.
 */
public interface RunOrchestrator {

    /** One run, from entry to exit. */
    interface Run {
        Content content();

        Tier tier();

        int currentStage();

        List<RunMember> members();

        /** Why the run stopped, or null while it is going. */
        String endedBecause();
    }

    /** Starts a run when a party with bots enters the content's first stage. */
    Run start(Content content, Tier tier, List<RunMember> members);

    /**
     * One orchestrator tick (1-4 Hz, like OPQOrchestrator): detect clears, move to the next stage, handle the timer,
     * and return the next step of every bot member still in the run (real players get none: never steered).
     */
    Map<Integer, SolverStep> tick(Run run, StageView view, long nowMs);

    /** A member left, died out of the PQ or disconnected: re-claim roles, or abandon if the party is too small. */
    void memberLost(Run run, int characterId, String why);

    /** Ends the run (timer, too few members, a ragequit) and lets the bots go back to their lives. */
    void abort(Run run, String why);
}
