package soloMapling.mechanics;

import java.util.Optional;

/**
 * 1.6.2 (TEST 2026-10-30, a real leader on KPQ stage 1): how a bot hands a pass to a <b>real</b> leader. Two bots
 * dropped their passes "by" the GM leader and told him so in party chat, but neither reached his Etc: a player moves
 * on, and a pass left on the floor is only his for the game's 15 s ownership window. So, as a player hands over:
 * <ul>
 *   <li>the bot first walks up to the leader ({@link #REACH_PX}), wherever he went, and drops the pass at his feet
 *   (the executor drops it at the leader's position, owned by him: the normal party drop he picks up himself);</li>
 *   <li>if the leader has not picked it up after {@link #TAKE_BACK_AFTER_MS} and stands away from it, the bot picks
 *   its own pass back up (by then the game lets anyone take it) and goes after him again.</li>
 * </ul>
 * Nothing is ever put straight into a player's inventory. A bot leader keeps the old path: passes dropped by him,
 * looted by his own Loot step. A pass dropped for a member is never another bot's to take ({@link #mayTake}).
 */
final class PassHandover {

    /** How close (px) a bot stands to a real leader before it drops: about a body's width either side. */
    static final int REACH_PX = 60;
    static final int REACH_Y = 60;
    /** How long a pass may lie at a real leader's feet before its dropper takes it back. */
    static final long TAKE_BACK_AFTER_MS = 20_000;
    /** A leader this close (px) to the pass is about to take it: leave it. */
    static final int LEADER_BY_PX = 100;

    private PassHandover() {
    }

    /** {@code a} is within the hand-over reach of {@code b} ({x, y}). */
    static boolean near(int[] a, int[] b) {
        return Math.abs(a[0] - b[0]) <= REACH_PX && Math.abs(a[1] - b[1]) <= REACH_Y;
    }

    /**
     * The step that hands {@code count} x {@code itemId} to {@code leader}: for a real leader, walk up to him first
     * and drop only when within {@link #REACH_PX}; for a bot leader, the drop (its executor walks it there).
     */
    static SolverStep give(StageView view, int id, int itemId, int count, RunMember leader) {
        if (!leader.bot()) {
            int[] by = view.positionOf(leader.characterId());
            if (!near(view.positionOf(id), by)) {
                return new SolverStep.MoveTo(by[0], by[1], "bring the pass to " + leader.characterId());
            }
        }
        return new SolverStep.DropFor(itemId, count, leader.characterId());
    }

    /**
     * After the drop: the bot's own pass still lies on the floor for a real leader who has not picked it up within
     * {@link #TAKE_BACK_AFTER_MS} and stands more than {@link #LEADER_BY_PX} away from it: take it back (then
     * {@link #give} walks it to him again). Null when there is nothing to take back.
     */
    static SolverStep takeBack(StageView view, int id, int itemId) {
        Optional<RunMember> leader = view.leader();
        if (leader.isEmpty() || leader.get().bot() || leader.get().characterId() == id) {
            return null;
        }
        int lid = leader.get().characterId();
        Optional<StageView.FloorDrop> drop = view.dropBy(id, itemId);
        if (drop.isEmpty() || drop.get().ownerId() != lid
                || view.nowMs() - drop.get().droppedAtMs() < TAKE_BACK_AFTER_MS) {
            return null;
        }
        int[] by = view.positionOf(lid);
        StageView.FloorDrop d = drop.get();
        if (view.mapOf(lid) == view.stage().mapId() && Math.abs(by[0] - d.x()) <= LEADER_BY_PX
                && Math.abs(by[1] - d.y()) <= REACH_Y) {
            return null; // he stands on it: his to pick up
        }
        return new SolverStep.TakeBack(itemId, "the leader left the pass lying for "
                + (view.nowMs() - d.droppedAtMs()) / 1000 + " s");
    }

    /**
     * Whether a member's floor scan may take a drop ({@code dropperId}: the character who dropped it, 0 for a
     * monster's): its own, an owner-less or free-for-all one, but never a hand-over another member dropped for
     * someone else (a pass a bot dropped at the leader's feet stays his even after the ownership window).
     */
    static boolean mayTake(int memberId, int ownerId, int dropperId, byte dropType) {
        if (ownerId == memberId) {
            return true;
        }
        if (dropperId > 0 && dropperId != memberId) {
            return false; // someone's hand-over, meant for its owner
        }
        return ownerId <= 0 || dropType == 2;
    }
}
