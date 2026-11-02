package soloMapling.mechanics;

/**
 * {@code opq-sweep} (Orbis PQ stage 3, the storage room 920010300): kill every monster and leave <b>nothing at all</b>
 * on the floor, then have the leader talk to Eak for the second statue piece.
 *
 * <p>Our script is the truth: {@code scripts/npc/2013001.js:920010300} clears only when
 * {@code cm.getMap().countMonsters() == 0 && cm.getMap().countItems() == 0}. {@code MapleMap.countItems()} counts
 * every {@code MapItem} it holds, mesos included, so the party sweeps mesos up too — {@link SolverStep.Loot} with item
 * id 0 means "anything down there" ({@code LiveStageView.floorDrops}).
 */
public final class OpqSweepSolver implements StageSolver {

    public static final String ID = "opq-sweep";

    static final int EAK_NPC = 2013001;

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
        if (view.mapOf(id) != view.stage().mapId()) {
            return new SolverStep.WarpTo(view.stage().mapId(), "into the storage room");
        }
        int mobs = view.liveMobs();
        int floor = view.floorItems(0, id);
        if (member.leader()) {
            if (mobs == 0 && floor == 0) {
                return new SolverStep.TalkNpc(EAK_NPC, 0, 0);
            }
            if (floor > 0) {
                return new SolverStep.Loot(0);
            }
            if (mobs > 0) {
                return new SolverStep.Fight(0, 0, 0);
            }
            return new SolverStep.Wait(1_500, "waiting for the floor to be swept");
        }
        if (floor > 0) {
            return new SolverStep.Loot(0);
        }
        if (mobs > 0) {
            return new SolverStep.Fight(0, 0, 0);
        }
        var leader = view.leader();
        if (leader.isPresent() && leader.get().characterId() != id) {
            int[] by = view.positionOf(leader.get().characterId());
            int[] at = view.positionOf(id);
            if (Math.abs(at[0] - by[0]) > 200 || Math.abs(at[1] - by[1]) > 90) {
                return new SolverStep.MoveTo(by[0] + (id % 2 == 0 ? 60 : -60), by[1], "back to the leader");
            }
        }
        return new SolverStep.Wait(1_500, "storage cleared, waiting for Eak");
    }
}
