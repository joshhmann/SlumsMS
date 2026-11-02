package soloMapling.mechanics;

/**
 * Solves one kind of stage (ticket 130, "Solvers"). A stage names its solver in the data ({@code solver:
 * question-coupons}); solvers are stateless and shared by every run, so all per-run memory lives in the
 * {@link StageView} (the event instance) or the orchestrator's run state.
 *
 * <p>Planned solvers: {@code question-coupons} (KPQ 1), {@code combination} (KPQ 2-4 ropes/platforms, LPQ 8 boxes),
 * {@code collect-hand-in} (LPQ passes, OPQ clouds), {@code jump-puzzle}, {@code kill-all}, {@code boss-phases}.
 */
public interface StageSolver {

    /** The {@code solver:} id this handles. */
    String id();

    /** The next step for one member. Called on the member's own tick; must be cheap and must not block. */
    SolverStep next(StageView view, RunMember member);

    /**
     * The maps this stage drives besides its own, if any. A stage is otherwise one map: a bot found anywhere else is
     * walked through the portal an earlier stage opened (or waits). Orbis PQ's last stage needs this because the
     * script sends the team to the garden and back before the statue's base can be armed, and neither map is a
     * separate stage - only the base has a property to watch.
     */
    default java.util.Set<Integer> extraMaps(StageView view) {
        return java.util.Set.of();
    }

    /**
     * The visible wrong tries a member with this persona shows on the stage's puzzle before the right {@code answer},
     * in the order it shows them (see {@link TryPlanner}). Planned once per member and stage by the orchestrator;
     * none by default.
     */
    default java.util.List<Integer> planWrongTries(MechanicsModel.Stage stage, int answer, MechanicsPersona persona,
                                                   double mistakeFactor, java.util.Random rnd) {
        return java.util.List.of();
    }

    /**
     * As above with the stage's live view and the member (a solver whose puzzle belongs to one member, like the
     * combination leader, plans for that member only). Defaults to the stage-only plan.
     */
    default java.util.List<Integer> planWrongTries(StageView view, RunMember member, int answer,
                                                   MechanicsPersona persona, double mistakeFactor, java.util.Random rnd) {
        return planWrongTries(view.stage(), answer, persona, mistakeFactor, rnd);
    }

    /**
     * 1.6.2 (bot audit K4): what a bot should tell a real leader whose turn it is (the passes are in, the formation
     * is ready), or empty. Only the leader can do these steps (the scripts check {@code isEventLeader}), and a real
     * player is never steered, so the bots ask him, again every little while, instead of idling to the time limit.
     */
    default java.util.Optional<String> leaderPrompt(StageView view, RunMember leader) {
        return java.util.Optional.empty();
    }

    /**
     * How long after a real leader's turn comes the first reminder waits, and the gap between reminders after it
     * ({first, every} ms). Default 20 s then every 40 s (a formation may still be settling).
     */
    default long[] leaderPromptTiming() {
        return new long[]{Orchestrator.LEADER_PROMPT_FIRST_MS, Orchestrator.LEADER_PROMPT_EVERY_MS};
    }

    /** A visible wrong attempt for this member right now, or null (persona tries, see {@link TryPlanner}). */
    default SolverStep wrongTry(StageView view, RunMember member) {
        return null;
    }
}
