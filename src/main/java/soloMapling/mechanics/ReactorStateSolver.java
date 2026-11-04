package soloMapling.mechanics;

/**
 * {@code reactor-state} (ticket 164, Ellin PQ stage 2): hit the stage's reactor until the state the row's clear names.
 *
 * <p>`scripts/portal/party6_stage.js:19-28` is the authority: the passage out of `930000200` refuses while
 * `getReactorByName("spine").getState() < 4`, so the spine reactor `3009000` (its own header says "Spine, map 930000200")
 * is the stage's finish line - and it writes no event property, which is why the row clears on
 * `{ reactorState: { id: 3009000, state: 4 } }` instead.
 *
 * <p>The solver only presses the reactor. If the state does not move, the row stalls with {@link #ID}'s step in the log
 * rather than claiming a stage nobody completed; discovering whether the spine needs an item first is a read this solver
 * deliberately does not guess at.
 */
public final class ReactorStateSolver implements StageSolver {

    public static final String ID = "reactor-state";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        int id = member.characterId();
        MechanicsModel.Stage stage = view.stage();
        if (view.mapOf(id) != stage.mapId()) {
            return new SolverStep.WarpTo(stage.mapId(), "back to the reactor");
        }
        if (stage.hitReactors().isEmpty()) {
            return new SolverStep.Wait(1_500, "this stage names no reactor to hit");
        }
        if (stage.clear() == null || stage.clear().reactorId() <= 0) {
            return new SolverStep.Wait(1_500, "this stage's clear names no reactor state to reach");
        }
        int now = view.reactorState(stage.clear().reactorId());
        if (now >= stage.clear().reactorState()) {
            return new SolverStep.Wait(1_500, "the reactor is at state " + now + ": the passage is the script's");
        }
        return new SolverStep.HitReactor(stage.hitReactors().get(0));
    }
}
