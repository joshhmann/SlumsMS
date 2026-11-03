package soloMapling.mechanics;

/**
 * {@code ellin-marbles} (ticket 164, Ellin PQ stage 4): the NPC hands out purifier marbles, the party throws them at the
 * poison flowers, and the flowers leave the marbles the NPC wants back.
 *
 * <p>`scripts/npc/2133001.js:47-84` gives the leader ten `2270004` and takes twenty `4001169`; the marble's own rule
 * lives in `server/life/MonsterCatch.usePurifierMarble` (a poison flower below four tenths of its HP dies, one marble is
 * spent, one `4001169` drops). `:93-94` is the finish: it takes the twenty and warps the whole team to `930000500`, which
 * is why the stage's clear is `partyOnMap` rather than a property - the script writes nothing at all.
 *
 * <p>So the loop is: talk once to be given marbles, spend them on the flowers, loot what the flowers leave, and let the
 * leader hand twenty over.
 */
public final class EllinMarblesSolver implements StageSolver {

    public static final String ID = "ellin-marbles";

    /**
     * Fallbacks only. Every number a difficulty variant would scale - how many marbles the NPC wants back, which item is
     * the marble, which monster takes it - is read from the row first (a chaos Ellin PQ that wanted forty, or a different
     * flower, is a row change and not a code change). The constants stay for a row that names nothing.
     */
    static final int MARBLE = 2270004;
    static final int FLOWER = 9300174;
    static final int GOLEM_MARBLE = 4001169;
    static final int NEED = 20;

    /** How many the row's own hand-in asks for, else {@link #NEED}. */
    static int need(StageView view) {
        for (MechanicsModel.Objective o : view.stage().objectives()) {
            if (o.kind() == MechanicsModel.ObjectiveKind.HAND_IN) {
                return o.count().map(c -> c.resolve(view.partySize(), java.util.OptionalInt.empty())).orElse(null)
                        .isPresent() ? o.count().get().resolve(view.partySize(), java.util.OptionalInt.empty())
                        .getAsInt() : NEED;
            }
        }
        return NEED;
    }

    /** The item the leader hands over: the row's hand-in item, else {@link #GOLEM_MARBLE}. */
    static int handedIn(StageView view) {
        for (MechanicsModel.Objective o : view.stage().objectives()) {
            if (o.kind() == MechanicsModel.ObjectiveKind.HAND_IN && o.itemId() > 0) {
                return o.itemId();
            }
        }
        return GOLEM_MARBLE;
    }

    /** The marble the row says to spend: the collected item that is not the hand-in one, else {@link #MARBLE}. */
    static int marble(StageView view) {
        int handed = handedIn(view);
        for (MechanicsModel.Objective o : view.stage().objectives()) {
            if (o.kind() == MechanicsModel.ObjectiveKind.COLLECT && o.itemId() > 0 && o.itemId() != handed) {
                return o.itemId();
            }
        }
        return MARBLE;
    }

    /** The monster the row lists for this stage, else {@link #FLOWER}. */
    static int flower(StageView view) {
        return view.stage().mobs().isEmpty() ? FLOWER : view.stage().mobs().get(0);
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
            return new SolverStep.WarpTo(stage.mapId(), "back to Ellin");
        }
        int need = need(view);
        int handed = handedIn(view);
        int have = view.itemCount(id, handed);
        if (member.leader()) {
            if (have >= need && view.npcPosition(stage.npcId()).isPresent()) {
                return new SolverStep.TalkNpc(stage.npcId(), handed, need);
            }
            if (view.itemCount(id, marble(view)) == 0) {
                // the first talk is what hands the leader the ten marbles (2133001.js:47-60)
                return new SolverStep.TalkNpc(stage.npcId(), 0, 0);
            }
        } else if (have >= need) {
            RunMember leader = view.leader().orElse(null);
            if (leader != null && leader.characterId() != id) {
                return PassHandover.give(view, id, handed, need, leader);
            }
        }
        if (view.floorItems(handed, id) > 0) {
            return new SolverStep.Loot(handed);
        }
        if (view.liveMobs() > 0) {
            return new SolverStep.UseItemOnMob(marble(view), flower(view));
        }
        return new SolverStep.Wait(1_500, "no flowers left and " + have + " of " + need + " marbles: the room respawns them");
    }
}
