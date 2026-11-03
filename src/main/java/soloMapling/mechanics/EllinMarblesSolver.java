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

    static final int MARBLE = 2270004;
    static final int FLOWER = 9300174;
    static final int GOLEM_MARBLE = 4001169;
    static final int NEED = 20;

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
        int have = view.itemCount(id, GOLEM_MARBLE);
        if (member.leader()) {
            if (have >= NEED && view.npcPosition(stage.npcId()).isPresent()) {
                return new SolverStep.TalkNpc(stage.npcId(), GOLEM_MARBLE, NEED);
            }
            if (view.itemCount(id, MARBLE) == 0) {
                // the first talk is what hands the leader the ten marbles (2133001.js:47-60)
                return new SolverStep.TalkNpc(stage.npcId(), 0, 0);
            }
        } else if (have >= NEED) {
            RunMember leader = view.leader().orElse(null);
            if (leader != null && leader.characterId() != id) {
                return PassHandover.give(view, id, GOLEM_MARBLE, NEED, leader);
            }
        }
        if (view.floorItems(GOLEM_MARBLE, id) > 0) {
            return new SolverStep.Loot(GOLEM_MARBLE);
        }
        if (view.liveMobs() > 0) {
            return new SolverStep.UseItemOnMob(MARBLE, FLOWER);
        }
        return new SolverStep.Wait(1_500, "no flowers left and " + have + " of " + NEED + " marbles: the room respawns them");
    }
}
