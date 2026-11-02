package soloMapling.mechanics;

/**
 * {@code opq-subroom} (Orbis PQ stage 6, the lounge 920010600 and its four rooms 920010601-604): the forty lounge
 * pieces drop from the monsters in the side rooms, and Eak — who takes them — stands in the lounge itself.
 *
 * <p>Our script is the truth: {@code scripts/npc/2013001.js:920010600} takes {@code 40} x {@code 4001052} from the
 * leader and clears stage 5 ({@code clearStage(5, eim)}). So the stage's map is one side room (where the work is) and
 * {@link #EAK_MAP} is where the hand-in happens: the leader warps back once he carries forty, everybody else stays and
 * keeps farming. cosmic_agents (AGPL-3.0, {@code AgentOpqDefinition.LOUNGE_SUBROOM_ENTRY_PORTALS}) splits the party
 * over all four rooms; ours sends them all into one, because the four rooms share their drop and a full party clears
 * one room faster than four twos.
 */
public final class OpqSubRoomSolver implements StageSolver {

    public static final String ID = "opq-subroom";

    static final int LOUNGE_PIECE = 4001052;
    static final int NEED = 40;
    static final int EAK_NPC = 2013001;
    /** Eak stands in the lounge (920010600), not in a side room. */
    static final int EAK_MAP = 920010600;

    @Override
    public String id() {
        return ID;
    }

    /** the sealed room is still this stage's business: its clear opened the box that holds our fourth piece */
    @Override
    public java.util.Set<Integer> extraMaps(StageView view) {
        return java.util.Set.of(920010500);
    }

    @Override
    public java.util.Optional<String> leaderPrompt(StageView view, RunMember leader) {
        int id = leader.characterId();
        if (view.itemCount(id, LOUNGE_PIECE) >= NEED) {
            return java.util.Optional.of("@" + id + " take the 40 pieces to Eak in the lounge");
        }
        if (view.floorItems(LOUNGE_PIECE, id) > 0) {
            return java.util.Optional.of("@" + id + " grab the pieces we dropped by u");
        }
        return java.util.Optional.empty();
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        int id = member.characterId();
        SolverStep sealedBox = OpqBoxes.take(view, id, OpqBoxes.SEALED);
        if (sealedBox != null) {
            return sealedBox; // the sealed room's clear opened the box that holds the fourth scar
        }
        int have = view.itemCount(id, LOUNGE_PIECE);
        if (member.leader()) {
            if (have >= NEED) {
                // the hand-in is in the lounge: walk (warp) back with the forty, never let them lie in a side room
                return view.mapOf(id) == EAK_MAP
                        ? new SolverStep.TalkNpc(EAK_NPC, LOUNGE_PIECE, NEED)
                        : new SolverStep.WarpTo(EAK_MAP, "back to Eak with the 40 pieces");
            }
            if (view.mapOf(id) != view.stage().mapId()) {
                return new SolverStep.WarpTo(view.stage().mapId(), "into a lounge room");
            }
            if (view.floorItems(LOUNGE_PIECE, id) > 0) {
                return new SolverStep.Loot(LOUNGE_PIECE);
            }
            if (view.liveMobs() > 0) {
                return new SolverStep.Fight(0, LOUNGE_PIECE, NEED);
            }
            return new SolverStep.Wait(1_500, "waiting in the lounge room (" + have + "/" + NEED + ")");
        }
        if (view.mapOf(id) != view.stage().mapId()) {
            return new SolverStep.WarpTo(view.stage().mapId(), "into a lounge room");
        }
        var leader = view.leader();
        boolean leaderHere = leader.isPresent() && view.mapOf(leader.get().characterId()) == view.stage().mapId();
        if (have > 0 && leaderHere && leader.get().characterId() != id) {
            return PassHandover.give(view, id, LOUNGE_PIECE, have, leader.get());
        }
        SolverStep back = PassHandover.takeBack(view, id, LOUNGE_PIECE);
        if (back != null) {
            return back;
        }
        if (view.liveMobs() > 0) {
            return new SolverStep.Fight(0, LOUNGE_PIECE, Integer.MAX_VALUE);
        }
        if (view.floorItems(LOUNGE_PIECE, id) > 0) {
            return new SolverStep.Loot(LOUNGE_PIECE);
        }
        if (leaderHere && leader.get().characterId() != id) {
            int[] by = view.positionOf(leader.get().characterId());
            int[] at = view.positionOf(id);
            if (Math.abs(at[0] - by[0]) > 200 || Math.abs(at[1] - by[1]) > 90) {
                return new SolverStep.MoveTo(by[0] + (id % 2 == 0 ? 60 : -60), by[1], "back to the leader");
            }
        }
        return new SolverStep.Wait(1_500, "lounge room cleared, waiting for the leader");
    }
}
