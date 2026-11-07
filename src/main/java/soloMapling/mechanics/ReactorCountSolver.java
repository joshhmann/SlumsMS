package soloMapling.mechanics;

import java.util.List;

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
        List<MechanicsModel.Objective> beakers = stage.objectives().stream()
                .filter(o -> o.kind() == MechanicsModel.ObjectiveKind.PLACE_ITEM && o.hasReactor()).toList();
        if (!beakers.isEmpty()) {
            return fill(view, member, beakers, now);
        }
        return new SolverStep.HitReactor(stage.hitReactors().get(0));
    }

    /**
     * b365: the state a beaker is full at. Reactor.wz 2618000 states 0-6 each take 1 x 4001132 (type 100); the drop
     * that lands while it is at 6 runs 2618000.js hit() - the count - and moves it to 7, then 8 for good. So seven
     * liquids fill one beaker; a drop on a full one just lies on the floor.
     */
    static final int FULL_STATE = 7;
    /**
     * b365: how long one liquid is waited on before another may follow. The server takes it 5 s after it lands
     * ({@code MapleMap.activateItemReactors}); a second one dropped meanwhile would only be taken after the first's state
     * change, and one dropped on a full beaker is lost on the floor - so one at a time, the next on the state change.
     */
    static final long IN_FLIGHT_MS = 8_000;

    /**
     * 1.6.6 b365 (Magatia PQ stage 3): the three beakers ({@code beaker1..3}, all reactor 2618000) are item reactors,
     * filled with Suspicious Liquid (4001132) that the Homunculus (9300147) drop. Each counts once, when full; they
     * leak a state per 9.5 s without liquid (type 101, timeOut 9500), so the party fills one beaker at a time - the one
     * the count has reached - one liquid per state change (about 6.5 s, inside the leak).
     */
    private static SolverStep fill(StageView view, RunMember member, List<MechanicsModel.Objective> beakers, int count) {
        int id = member.characterId();
        MechanicsModel.Objective beaker = beakers.get(Math.min(count, beakers.size() - 1));
        String name = beaker.reactor().trim();
        int liquid = beaker.itemId();
        int state = view.reactorState(name);
        int needed = state < 0 ? 0 : Math.max(0, FULL_STATE - state);
        String key = "fill:" + name;
        int held = view.itemCount(id, liquid);
        if (held > 0) {
            if (needed == 0) {
                return new SolverStep.Wait(1_000, name + " is full: waiting for it to count");
            }
            Object last = view.stageMemory().get(key);
            if (last instanceof long[] l && l[0] == state && view.nowMs() - l[1] < IN_FLIGHT_MS) {
                return new SolverStep.Wait(1_000, "a liquid is on its way into " + name);
            }
            view.stageMemory().put(key, new long[]{state, view.nowMs()});
            return new SolverStep.PlaceItem(liquid, 1, name, 0, 0, "one liquid into " + name);
        }
        if (view.floorItems(liquid, id) > 0) {
            return new SolverStep.Loot(liquid);
        }
        List<Integer> mobs = view.stage().objectives().stream()
                .filter(o -> o.kind() == MechanicsModel.ObjectiveKind.KILL_FOR_ITEM && o.mobId() > 0)
                .map(MechanicsModel.Objective::mobId).toList();
        return new SolverStep.Fight(mobs, liquid, Math.max(1, needed), 0, 0, 0);
    }
}
