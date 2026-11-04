package soloMapling.mechanics;

import java.util.Optional;

/**
 * {@code opq-clouds} (Orbis PQ stage 1, the tower entrance 920010000): hit the cloud reactors, sweep up the pieces
 * they drop, put <b>twenty in one stack</b> on the altar, and have the leader talk to Eak, who steps out of the light.
 *
 * <p>Our scripts are the truth:
 * <ul>
 *   <li>{@code scripts/reactor/2006000.js} "Spawns Eak When 20 Clouds are Dropped": the altar's {@code act()} runs only
 *       when the server took a drop of exactly the reactor's react item and quantity inside its box
 *       ({@code MapleMap.activateItemReactors}), so the stack must go down whole ({@link SolverStep.PlaceItem});</li>
 *   <li>{@code scripts/npc/2013001.js:920010000} clears stage 0 on the leader's talk
 *       ({@code clearStage(0, eim)} → {@code statusStg0 = "1"}).</li>
 * </ul>
 *
 * <p>Deliberate divergence from cosmic_agents (AGPL-3.0, {@code AgentOpqCoordinator}): it loots the pieces and never
 * drops them on the altar, because its own server spawns Eak another way. On our tree the altar is the only way in,
 * so it is the leader who carries the twenty.
 */
public final class OpqCloudsSolver implements StageSolver {

    public static final String ID = "opq-clouds";

    /** Cloud piece, dropped by the twenty cloud reactors of 920010000. */
    static final int CLOUD_PIECE = 4001063;
    /** The cloud reactors all share this id (cosmic_agents calls it the courtyard cloud). */
    static final int CLOUD_REACTOR = 2002001;
    /** The altar reactor that takes 20 x 4001063 and spawns Eak ({@code scripts/reactor/2006000.js}). */
    static final String ALTAR = "2006000";
    /** The altar matches the whole stack exactly: twenty, no more, no less. */
    static final int ALTAR_PIECES = 20;
    static final int EAK_NPC = 2013001;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public java.util.Optional<String> leaderPrompt(StageView view, RunMember leader) {
        int have = view.itemCount(leader.characterId(), CLOUD_PIECE);
        if (view.reactorState(ALTAR) > 0) {
            return java.util.Optional.of("@" + leader.characterId() + " talk to Eak");
        }
        if (have >= ALTAR_PIECES) {
            return java.util.Optional.of("@" + leader.characterId() + " put the 20 cloud pieces on the altar");
        }
        return java.util.Optional.empty();
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        int id = member.characterId();
        if (view.mapOf(id) != view.stage().mapId()) {
            return new SolverStep.WarpTo(view.stage().mapId(), "into the tower entrance");
        }
        if (view.reactorState(ALTAR) > 0) {
            // the light flickered and Eak appeared: the leader's talk is what clears stage 0
            return member.leader() ? new SolverStep.TalkNpc(EAK_NPC, 0, 0)
                    : new SolverStep.Wait(2_000, "waiting for the leader to talk to Eak");
        }
        int have = view.itemCount(id, CLOUD_PIECE);
        if (member.leader()) {
            if (have >= ALTAR_PIECES) {
                return new SolverStep.PlaceItem(CLOUD_PIECE, ALTAR_PIECES, ALTAR, 0, 0,
                        "put the 20 cloud pieces on the altar");
            }
            if (view.floorItems(CLOUD_PIECE, id) > 0) {
                return new SolverStep.Loot(CLOUD_PIECE);
            }
            return new SolverStep.HitReactor(CLOUD_REACTOR);
        }
        Optional<RunMember> leader = view.leader();
        if (have > 0 && leader.isPresent() && leader.get().characterId() != id
                && view.mapOf(leader.get().characterId()) == view.stage().mapId()) {
            return PassHandover.give(view, id, CLOUD_PIECE, have, leader.get());
        }
        SolverStep back = PassHandover.takeBack(view, id, CLOUD_PIECE);
        if (back != null) {
            return back;
        }
        if (view.floorItems(CLOUD_PIECE, id) > 0) {
            return new SolverStep.Loot(CLOUD_PIECE);
        }
        return new SolverStep.HitReactor(CLOUD_REACTOR);
    }
}
