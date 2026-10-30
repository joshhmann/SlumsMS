package soloMapling.mechanics;

import soloMapling.mechanics.MechanicsModel.Objective;
import soloMapling.mechanics.MechanicsModel.ObjectiveKind;
import soloMapling.mechanics.MechanicsModel.Stage;

import java.util.OptionalInt;

/**
 * {@code kill-collect-hand-in} (1.6.1 KPQ automation; KPQ stage 5, "Bosses and passes"). Every member fights the
 * stage's monsters, which drop the passes; the members drop theirs next to the leader, the leader loots them and,
 * holding enough, hands them to the NPC, which clears the stage. Ids and counts come from the stage data (kept equal
 * to our {@code scripts/npc/9020001.js} by {@code KpqStageDataTest}):
 * <ul>
 *   <li>{@code HAND_IN} (leader): the NPC, the item and how many ({@code 10} passes);</li>
 *   <li>{@code GIVE_TO_LEADER}: the item the others drop for the leader.</li>
 * </ul>
 * A real leader gets the passes dropped at his feet and talks to the NPC himself; nobody steers him. Collectors only
 * pick up what the monsters dropped (never a pass already dropped for the leader), so passes never go round in a
 * circle.
 */
public final class KillCollectHandInSolver implements StageSolver {

    public static final String ID = "kill-collect-hand-in";

    /** A collector carries at most this many passes before walking them over (and all of them once the map is clear). */
    static final int GIVE_AT = 3;
    /** How close (px) to the NPC the leader waits once the monsters are dead. */
    static final int LEADER_WAIT_PX = 150;

    @Override
    public String id() {
        return ID;
    }

    record Plan(int npcId, int itemId, int need) {

        static Plan of(Stage stage, int partySize) {
            Objective hand = null;
            Objective give = null;
            for (Objective o : stage.objectives()) {
                if (o.kind() == ObjectiveKind.HAND_IN && hand == null) {
                    hand = o;
                } else if (o.kind() == ObjectiveKind.GIVE_TO_LEADER && give == null) {
                    give = o;
                }
            }
            if (hand == null || hand.count().isEmpty()) {
                throw new IllegalArgumentException(ID + " needs a HAND_IN objective with a count");
            }
            int need = hand.count().get().resolve(partySize, OptionalInt.empty()).orElse(0);
            int npc = hand.npcId() > 0 ? hand.npcId() : stage.npcId();
            return new Plan(npc, give != null ? give.itemId() : hand.itemId(), need);
        }
    }

    @Override
    public java.util.Optional<String> leaderPrompt(StageView view, RunMember leader) {
        Plan plan = Plan.of(view.stage(), view.partySize());
        int id = leader.characterId();
        int have = view.itemCount(id, plan.itemId());
        if (have >= plan.need()) {
            return java.util.Optional.of("@" + id + " u have the " + plan.need() + " passes, talk to Cloto");
        }
        if (view.floorItems(plan.itemId(), id) > 0) {
            return java.util.Optional.of("@" + id + " grab the passes we dropped by u");
        }
        return java.util.Optional.empty();
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        Plan plan = Plan.of(view.stage(), view.partySize());
        int id = member.characterId();
        int have = view.itemCount(id, plan.itemId());
        int mobs = view.liveMobs();
        if (member.leader()) {
            if (have >= plan.need()) {
                return new SolverStep.TalkNpc(plan.npcId(), plan.itemId(), plan.need());
            }
            if (view.floorItems(plan.itemId(), id) > 0) {
                return new SolverStep.Loot(plan.itemId());
            }
            if (mobs > 0) {
                return new SolverStep.Fight(0, plan.itemId(), plan.need());
            }
            var npc = view.npcPosition(plan.npcId());
            if (npc.isPresent()) {
                int[] at = view.positionOf(id);
                int[] by = npc.get();
                if (Math.abs(at[0] - by[0]) > LEADER_WAIT_PX || Math.abs(at[1] - by[1]) > 90) {
                    return new SolverStep.MoveTo(by[0] - LEADER_WAIT_PX / 2, by[1], "wait by the NPC for passes");
                }
            }
            return new SolverStep.Wait(1_000, "waiting by the NPC for passes (" + have + "/" + plan.need() + ")");
        }
        var leader = view.leader();
        if (have > 0 && leader.isPresent() && leader.get().characterId() != id
                && view.mapOf(leader.get().characterId()) == view.stage().mapId()
                && (mobs == 0 || have >= GIVE_AT)) {
            return PassHandover.give(view, id, plan.itemId(), have, leader.get());
        }
        SolverStep back = PassHandover.takeBack(view, id, plan.itemId());
        if (back != null) {
            return back;
        }
        if (mobs > 0) {
            return new SolverStep.Fight(0, plan.itemId(), Integer.MAX_VALUE);
        }
        if (leader.isPresent() && leader.get().characterId() != id) {
            int[] by = view.positionOf(leader.get().characterId());
            int[] at = view.positionOf(id);
            if (Math.abs(at[0] - by[0]) > 200 || Math.abs(at[1] - by[1]) > 90) {
                return new SolverStep.MoveTo(by[0] + (id % 2 == 0 ? 60 : -60), by[1], "back to the leader");
            }
        }
        return new SolverStep.Wait(1_500, "monsters cleared, passes handed over");
    }
}
