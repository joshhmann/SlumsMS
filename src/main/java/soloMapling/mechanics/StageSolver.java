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
     * The visible wrong tries a member with this persona shows on the stage's puzzle before the right {@code answer},
     * in the order it shows them (see {@link TryPlanner}). Planned once per member and stage by the orchestrator;
     * none by default.
     */
    default java.util.List<Integer> planWrongTries(MechanicsModel.Stage stage, int answer, MechanicsPersona persona,
                                                   double mistakeFactor, java.util.Random rnd) {
        return java.util.List.of();
    }

    /** A visible wrong attempt for this member right now, or null (persona tries, see {@link TryPlanner}). */
    default SolverStep wrongTry(StageView view, RunMember member) {
        return null;
    }
}
