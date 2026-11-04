package soloMapling.mechanics;

/**
 * {@code treasure-chest} (ticket 164, Treasure PQ): one map, one chest, one monster.
 *
 * <p>`scripts/event/TreasurePQ.js` is the shortest PQ in the tree:
 *
 * <ul>
 *   <li>`setup` (`:115-125`) shuffles the reactors on `674030000` and sets `statusStg1` to `"0"` - a property nothing
 *       ever reads again, so it is not a clear signal.</li>
 *   <li>`scripts/reactor/6741001.js` is the whole summon: `function act() { rm.spawnMonster(9400589); }`. The chest has
 *       no state to check and no item to drop, so hitting it is the only way anything on this map exists to fight.</li>
 *   <li>`monsterKilled` (`:244-252`) watches for `9400589` alone: on its death it shows the clear effect, gives stage
 *       reward 2 and calls `eim.clearPQ()` (`:232-237`), whose whole body is `setEventCleared()` plus a schedule that
 *       warps the team to the bonus map ten seconds later.</li>
 * </ul>
 *
 * <p>So the row clears on the instance's flag (`clear: { instance: true }`) and this solver is four rules: kill what is
 * out, otherwise hit the chest, otherwise wait for the chest to come back. It is the second-cheapest row on the branch
 * and it proves the same two primitives twice: a summon by reactor, and a clear by `eim.clearPQ()`.
 */
public final class TreasureChestSolver implements StageSolver {

    public static final String ID = "treasure-chest";

    /** The map the party plays, and the chest that is the only source of monsters on it. */
    static final int MAP = 674030000;
    static final int CHEST = 6741001;

    /** The one monster the event watches (`TreasurePQ.js:239-243,244-252`). */
    static final int MV = 9400589;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        if (view.mapOf(member.characterId()) != MAP) {
            return new SolverStep.WarpTo(MAP, "into the treasure room");
        }
        if (view.liveMobs() > 0) {
            return new SolverStep.Fight(0, 0, 0);
        }
        if (view.reactorState(CHEST) >= 0) {
            // the chest's act() is the summon; it has no state, so a hit is all it takes
            return new SolverStep.HitReactor(CHEST);
        }
        return new SolverStep.Wait(1_500, "waiting for the treasure chest to come back");
    }
}
