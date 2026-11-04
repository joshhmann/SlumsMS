package soloMapling.mechanics;

/**
 * {@code hold} (ticket 164): a stage the engine cannot drive yet, kept honest.
 *
 * <p>A row that stops one stage short would otherwise be reported as <b>cleared</b> - the orchestrator ends the run with
 * {@code "cleared"} at its last playable stage - which would tell a party a party quest was finished when it was not.
 * This solver never clears and never abandons: it waits, and the line names the fact that is missing, taken from the
 * stage's own {@code name}. A run that reaches it ends on its budget with that reason in its history, which is the truth.
 *
 * <p>It exists for exactly one reason: Magatia PQ's fifth stage is a four-room bitmask (`statusStg5`) that the
 * `jnr4_r1..r4` portals set and no script clears, so the run's next step cannot be decided from a read alone.
 */
public final class HoldSolver implements StageSolver {

    public static final String ID = "hold";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        return new SolverStep.Wait(3_000, "not modelled: " + view.stage().name());
    }
}
