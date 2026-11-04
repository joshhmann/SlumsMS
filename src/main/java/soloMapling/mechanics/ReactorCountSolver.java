package soloMapling.mechanics;

/**
 * {@code reactor-count} (ticket 164, Magatia PQ stage 3): hit the stage's reactor until the stage's own counter reaches
 * the number the row's clear names.
 *
 * <p>`scripts/reactor/2618000.js:27-41` is the beaker: on `hit()`, and only while its state is 6, it adds one to
 * `statusStg3`, and at three it shows the clear effect, kills the room's monsters and hits the door reactor
 * (`jnr2_door`). So the reactor decides when a hit counts - the solver only has to keep hitting it, one hit per pass,
 * and stop when the counter the row's `clear.equals` names is reached.
 *
 * <p>The count is read from the event property, not assumed: a stage whose property is absent or not a number is waited
 * on with a line naming the property rather than hit blindly.
 */
public final class ReactorCountSolver implements StageSolver {

    public static final String ID = "reactor-count";

    @Override
    public String id() {
        return ID;
    }

    /** The value the row's clear wants, as an int, or -1 when the row is not a counted clear. */
    static int target(MechanicsModel.Clear clear) {
        if (clear == null || clear.equals() == null) {
            return -1;
        }
        try {
            return Integer.parseInt(clear.equals().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The counter as the event holds it now, or -1 when it is not written yet. */
    static int counter(StageView view) {
        MechanicsModel.Clear clear = view.stage().clear();
        if (clear == null || clear.eventProperty() == null) {
            return -1;
        }
        return view.eventProperty(clear.eventProperty()).map(s -> {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }).orElse(-1);
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        int id = member.characterId();
        MechanicsModel.Stage stage = view.stage();
        if (view.mapOf(id) != stage.mapId()) {
            return new SolverStep.WarpTo(stage.mapId(), "back to the beaker");
        }
        if (stage.hitReactors().isEmpty()) {
            return new SolverStep.Wait(1_500, "this stage names no reactor to hit, so its count cannot be raised");
        }
        int target = target(stage.clear());
        if (target < 0) {
            return new SolverStep.Wait(1_500, "the clear names no number, so a count of hits is meaningless");
        }
        int now = counter(view);
        if (now < 0) {
            return new SolverStep.Wait(1_500, "the event has not written "
                    + (stage.clear() == null ? "?" : stage.clear().eventProperty()) + " yet");
        }
        if (now >= target) {
            return new SolverStep.Wait(1_500, "the counter is at " + now + " of " + target + ": the door is the script's");
        }
        return new SolverStep.HitReactor(stage.hitReactors().get(0));
    }
}
