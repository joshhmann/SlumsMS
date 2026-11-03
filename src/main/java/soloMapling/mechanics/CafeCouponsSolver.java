package soloMapling.mechanics;

import soloMapling.mechanics.MechanicsModel.Objective;
import soloMapling.mechanics.MechanicsModel.ObjectiveKind;

import java.util.List;
import java.util.OptionalInt;

/**
 * {@code cafepq-coupons} (ticket 164, Cafe PQ): one stage per area, and the same shape six times over.
 *
 * <p>Every one of the six scripts (`scripts/event/CafePQ_1.js` .. `_6.js`) does the same thing: killing a monster in the
 * area spawns its drop <em>by hand</em> - {@code new Item(4001007, 0, getDroppedQuantity(mob))} on the floor
 * (`CafePQ_1.js:261-277`) - and the party's leader hands {@code couponsNeeded} of them to the Computer on 193000000,
 * which calls {@code eim.clearPQ()} (`scripts/npc/1052013.js:56-63`). Only the maps and the count differ
 * (`400`, `350`, `350`, `450`, `500`, `300`).
 *
 * <p>Two things keep this out of the generic solver:
 *
 * <ul>
 *   <li><b>The clear is not a property.</b> Nothing writes one: the instance's own flag is all there is, which the row
 *       says with {@code clear: { instance: true }} and {@link StageView#eventCleared()} reads.</li>
 *   <li><b>The NPC pays instead of charging.</b> `1052013.js:59` is {@code cm.gainItem(4001007, couponsNeeded)} - a
 *       positive quantity, so a party that talks to it <em>keeps</em> its coupons and is given the same number again.
 *       A row whose {@code HAND_IN} objective made the bots pay would be asking them for something the NPC is handing
 *       out, so this row states the count where the leader's own tally lives ({@code COLLECT}) and the talk to the NPC
 *       carries no item at all.</li>
 * </ul>
 *
 * <p>So the pooling is {@link PassHandover} (the same {@code DropFor} every other row uses) and the hand-over is a bare
 * {@code TalkNpc(npc, 0, 0)}: the script's own {@code haveItem} check is the gate.
 */
public final class CafeCouponsSolver implements StageSolver {

    public static final String ID = "cafepq-coupons";

    /** The one item all six areas drop; {@code CafePQ_1.js:74-77} makes it the instance's exclusive item. */
    static final int COUPON = 4001007;

    /** How many coupons the leader must hold: the row's {@code COLLECT} count, else 0 (never a clear then). */
    static int need(StageView view) {
        for (Objective o : view.stage().objectives()) {
            if (o.kind() == ObjectiveKind.COLLECT && o.count().isPresent() && o.itemId() > 0) {
                return o.count().get().resolve(view.partySize(), OptionalInt.empty()).orElse(0);
            }
        }
        return 0;
    }

    /** The item the row collects (the coupon unless a row names another). */
    static int item(StageView view) {
        for (Objective o : view.stage().objectives()) {
            if (o.kind() == ObjectiveKind.COLLECT && o.itemId() > 0) {
                return o.itemId();
            }
        }
        return COUPON;
    }

    @Override
    public String id() {
        return ID;
    }

    /**
     * The area's other maps, from the script's own {@code eventMaps}: {@code 190000001/2} for the first area,
     * {@code 195010000}..{@code 195030000} for the fourth. The row names one map per stage; the rest are the same
     * area's rooms, and a bot that wandered into one is not behind.
     */
    @Override
    public java.util.Set<Integer> extraMaps(StageView view) {
        List<Integer> rooms = CafeAreas.siblingsOf(view.stage().mapId());
        return rooms.isEmpty() ? java.util.Set.of() : java.util.Set.copyOf(rooms);
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        int id = member.characterId();
        int need = need(view);
        int item = item(view);
        if (need <= 0) {
            return new SolverStep.Wait(1_000, "this area names no coupon count");
        }
        if (view.mapOf(id) != view.stage().mapId()) {
            return new SolverStep.WarpTo(view.stage().mapId(), "back into the area");
        }
        int have = view.itemCount(id, item);
        if (member.leader()) {
            if (have >= need) {
                // the Computer checks haveItem(4001007, couponsNeeded) and clears the instance; it also pays the same
                // amount back (1052013.js:59), so nothing is handed over here
                return new SolverStep.TalkNpc(view.stage().npcId(), 0, 0);
            }
        } else if (have >= need) {
            RunMember leader = view.leader().orElse(null);
            if (leader != null && leader.characterId() != id) {
                return PassHandover.give(view, id, item, need, leader);
            }
        }
        if (view.floorItems(item, id) > 0) {
            return new SolverStep.Loot(item);
        }
        if (view.liveMobs() > 0) {
            return new SolverStep.Fight(0, item, need);
        }
        if (member.leader()) {
            return new SolverStep.Wait(1_500, "waiting for the area's monsters to respawn");
        }
        RunMember leader = view.leader().orElse(null);
        if (leader != null && leader.characterId() != id) {
            int[] at = view.positionOf(leader.characterId());
            return new SolverStep.MoveTo(at[0], at[1], "back with the leader");
        }
        return new SolverStep.Wait(1_500, "waiting for the area's monsters to respawn");
    }
}
