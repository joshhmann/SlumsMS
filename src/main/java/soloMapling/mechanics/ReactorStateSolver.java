package soloMapling.mechanics;

import java.util.List;

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
        List<MechanicsModel.Objective> places = stage.objectives().stream()
                .filter(o -> o.kind() == MechanicsModel.ObjectiveKind.PLACE_ITEM && o.hasReactor()).toList();
        if (!places.isEmpty()) {
            return chain(view, member, places);
        }
        return new SolverStep.HitReactor(stage.hitReactors().get(0));
    }

    /** b365: how long a placed stack is left for its item reactor to take (it takes it 5 s after it lands). */
    static final long PLACE_WINDOW_MS = 7_000;

    /**
     * 1.6.6 b365 (Ellin PQ stage 2): the spine is an item reactor, opened a state at a time by Diluted Poison
     * (4001162), which the fountain (3002000) makes out of Concentrated Poison (4001161), which 9300173 drops. The row
     * lists the placements in order, last one on the reactor the clear names; the chain is worked from its end: place
     * what is held for the furthest step, else pick it up, else fight for the first item.
     */
    private static SolverStep chain(StageView view, RunMember member, List<MechanicsModel.Objective> places) {
        int id = member.characterId();
        for (int i = places.size() - 1; i >= 0; i--) {
            MechanicsModel.Objective o = places.get(i);
            int reactor = Integer.parseInt(o.reactor().trim());
            int count = o.count().map(c -> c.resolve(view.partySize(), java.util.OptionalInt.empty()).orElse(1))
                    .orElse(1);
            String key = "placedAt:" + reactor + ":" + view.reactorState(reactor);
            Object at = view.stageMemory().get(key);
            boolean settling = at instanceof Long t && view.nowMs() - t < PLACE_WINDOW_MS;
            if (view.itemCount(id, o.itemId()) >= count) {
                if (settling) {
                    return new SolverStep.Wait(1_000, "the reactor takes the stack a few seconds after it lands");
                }
                view.stageMemory().put(key, view.nowMs());
                return new SolverStep.PlaceItemOn(reactor, o.itemId(), count, o.id());
            }
            if (!settling && view.floorItems(o.itemId(), id) > 0) {
                return new SolverStep.Loot(o.itemId());
            }
        }
        List<Integer> mobs = view.stage().objectives().stream()
                .filter(o -> o.kind() == MechanicsModel.ObjectiveKind.KILL_FOR_ITEM && o.mobId() > 0)
                .map(MechanicsModel.Objective::mobId).toList();
        int first = places.get(0).itemId();
        return new SolverStep.Fight(mobs, first, 1, 0, 0, 0);
    }
}
