package soloMapling.mechanics;

/**
 * {@code magatia-files} (ticket 164, Magatia PQ stage 4): two documents, two side rooms, and one NPC who takes both from
 * the leader.
 *
 * <p>`scripts/npc/2112005.js:72-87` is the whole stage: the NPC stands on the stage's own map and, when the <b>leader</b>
 * holds 4001134 (Alcadno's file) and 4001135 (Zenumist's), it consumes one of each, shows the clear effect, sets
 * `statusStg4 = 1`, empties the map and hits the door (`jnr3_out3`). The two files come out of the two side rooms
 * (`926110201`/`926110202`), which is why the row declares them.
 *
 * <p>So: with both files in hand, walk to the stage map and talk (the talk carries no item - the script's own
 * {@code haveItem} pair is the gate, and passing an item id would only make the leader hand one over twice). Without
 * them, farm whichever is short in the rooms, and pool on the leader, who is the only member the NPC listens to.
 */
public final class MagatiaFilesSolver implements StageSolver {

    public static final String ID = "magatia-files";

    static final int ALCADNO_FILE = 4001134;
    static final int ZENUMIST_FILE = 4001135;
    static final int[] FILES = {ALCADNO_FILE, ZENUMIST_FILE};

    /** The file the row still needs in this member's hands: the first one he is short of, or -1 when he has both. */
    static int shortOf(StageView view, int characterId) {
        for (int file : FILES) {
            if (view.itemCount(characterId, file) < 1) {
                return file;
            }
        }
        return -1;
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
        int[] npc = view.positionOf(id);
        int missing = shortOf(view, id);
        if (missing < 0) {
            RunMember leader = view.leader().orElse(null);
            if (member.leader()) {
                if (view.mapOf(id) != stage.mapId()) {
                    return new SolverStep.WarpTo(stage.mapId(), "back to Juliet with both files");
                }
                if (view.npcPosition(stage.npcId()).isPresent()) {
                    return new SolverStep.TalkNpc(stage.npcId(), 0, 0);
                }
                return new SolverStep.Wait(1_500, "the NPC who takes the files is not on this map");
            }
            if (leader != null && leader.characterId() != id) {
                return PassHandover.give(view, id, FILES[0], 1, leader);
            }
            return new SolverStep.Wait(1_500, "waiting for the leader");
        }
        if (member.leader() && view.mapOf(id) == stage.mapId()) {
            return new SolverStep.WarpTo(view.stage().maps().isEmpty() ? stage.mapId() : view.stage().maps().get(0),
                    "into a side room for " + missing);
        }
        if (view.mapOf(id) == stage.mapId()) {
            return new SolverStep.WarpTo(view.stage().maps().isEmpty() ? stage.mapId() : view.stage().maps().get(0),
                    "into a side room for " + missing);
        }
        if (view.floorItems(missing, id) > 0) {
            return new SolverStep.Loot(missing);
        }
        if (view.liveMobs() > 0) {
            return new SolverStep.Fight(0, missing, 1);
        }
        if (npc.length == 0) {
            return new SolverStep.Wait(1_500, "no monsters left in this room for " + missing);
        }
        return new SolverStep.Wait(1_500, "waiting for the room to re-spawn " + missing);
    }
}
