package soloMapling.mechanics;

/**
 * {@code ellin-stone} (ticket 164, Ellin PQ stage 5): one stone, one reactor, one NPC who opens the way.
 *
 * <p>The map `930000500` holds NPC `2133004` and a single `3002001` reactor (plus eight `3002002` decorations that carry
 * no drop rows at all, so nothing has to touch them). `scripts/reactor/3002001.js` is four lines - `showClearEffect` and
 * `rm.dropItems()` - and the stock `reactordrops` table has exactly one row for it: item `4001163` at `chance 1`, which is
 * a denominator, so the drop is certain. `scripts/npc/2133004.js:22-33` then takes it from the party <b>leader</b> and
 * `warpEventTeam(930000600)`: the stage's clear is the arrival, and nothing is written to the event.
 *
 * <p>Only the leader loots. The stone is one drop on the floor; eight members racing it would spend the stage stepping on
 * each other, and the NPC refuses anyone who is not the event leader anyway.
 */
public final class EllinStoneSolver implements StageSolver {

    public static final String ID = "ellin-stone";

    /** Fallbacks only: the row names the reactor, the NPC and the item, so a variant changes the row. */
    static final int REACTOR = 3002001;
    static final int NPC = 2133004;
    static final int STONE = 4001163;

    static int reactor(StageView view) {
        java.util.List<Integer> named = view.stage().hitReactors();
        return named.isEmpty() ? REACTOR : named.get(0);
    }

    static int npc(StageView view) {
        return view.stage().npcId() > 0 ? view.stage().npcId() : NPC;
    }

    static int stone(StageView view) {
        for (MechanicsModel.Objective o : view.stage().objectives()) {
            if (o.itemId() > 0 && (o.kind() == MechanicsModel.ObjectiveKind.COLLECT
                    || o.kind() == MechanicsModel.ObjectiveKind.HAND_IN)) {
                return o.itemId();
            }
        }
        return STONE;
    }

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
            return new SolverStep.WarpTo(stage.mapId(), "back to the stone's room");
        }
        if (!member.leader()) {
            return new SolverStep.Wait(1_500, "the stone is the leader's: waiting for him to open the way");
        }
        int stone = stone(view);
        if (view.itemCount(id, stone) > 0) {
            return new SolverStep.TalkNpc(npc(view), 0, 0);
        }
        if (view.floorItems(stone, id) > 0) {
            return new SolverStep.Loot(stone);
        }
        return new SolverStep.HitReactor(reactor(view));
    }
}
