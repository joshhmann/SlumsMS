package soloMapling.mechanics;

import soloMapling.mechanics.MechanicsModel.Objective;
import soloMapling.mechanics.MechanicsModel.ObjectiveKind;
import soloMapling.mechanics.MechanicsModel.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import java.util.TreeSet;

/**
 * {@code question-coupons} (ticket 130, KPQ stage 1 "Coupons for Cloto"). Everything it needs comes from the stage
 * data, which a test keeps in step with our own {@code scripts/npc/9020001.js}:
 * <ul>
 *   <li>an <b>answerer</b> asks the NPC for a question (the script picks one and keeps it in the event grid), kills the
 *   farm mob until it holds exactly the answer in coupons (extras are dropped), hands them in for a pass and drops
 *   the pass by the leader;</li>
 *   <li>the <b>leader</b> picks up the passes dropped for him and hands in one per other member, which clears the
 *   stage; until then he hunts next to whoever still farms (1.6.2), then waits by the NPC;</li>
 *   <li>a member whose pass is handed over hunts next to whoever still farms, looting nothing (1.6.2);</li>
 * </ul>
 * Wrong tries (persona): on the way up to the answer the bot hands in at a planned wrong count (the script says "that
 * is not the right answer"), then keeps farming. A real player's steps are never asked for; a real leader gets the
 * passes dropped at his feet and talks to the NPC himself.
 */
public final class QuestionCouponsSolver implements StageSolver {

    public static final String ID = "question-coupons";

    /**
     * Most visible wrong hand-ins one bot makes on KPQ stage 1 (each one is a walk back to Cloto). 1.6.2: was 2, and
     * a try could be far below the answer ("will try [8] first" for 25 on TEST: a walk across the map with 8, then
     * 17 more to hunt); now one, and only a near miss.
     */
    public static final int MAX_WRONG_HAND_INS = 1;

    /** How far below the answer a wrong hand-in may be: a near miss, handed in on the way to the right count. */
    public static final int NEAR_MISS = 5;

    /** How far (px) from the NPC the leader may stand while he waits for passes. */
    static final int LEADER_WAIT_PX = 150;

    /**
     * How close (px, horizontally) a member who is done hunts next to the one it helps: inside the loot search
     * ({@code StepExecutor.LOOT_SEARCH_PX}), so the coupons its kills drop are within the farmer's reach.
     */
    static final int HELP_RADIUS_PX = 250;
    static final int HELP_RADIUS_Y = 120;

    @Override
    public String id() {
        return ID;
    }

    /** The ids the solver works with, read from the stage's objectives. */
    record Plan(int npcId, int mobId, int couponId, int passId, String leaderRole, String answererRole) {

        static Plan of(Stage stage) {
            Objective farm = first(stage, ObjectiveKind.KILL_FOR_ITEM, null);
            Objective give = first(stage, ObjectiveKind.GIVE_TO_LEADER, null);
            Objective ask = first(stage, ObjectiveKind.TALK_NPC, null);
            if (farm == null || give == null) {
                throw new IllegalArgumentException(ID + " needs KILL_FOR_ITEM and GIVE_TO_LEADER objectives");
            }
            String leaderRole = stage.roles().stream().filter(r -> r.who() == MechanicsModel.Who.LEADER)
                    .map(MechanicsModel.Role::id).findFirst().orElse("leader");
            int npc = ask != null && ask.npcId() > 0 ? ask.npcId() : stage.npcId();
            return new Plan(npc, farm.mobId(), farm.itemId(), give.itemId(), leaderRole, farm.role());
        }

        private static Objective first(Stage stage, ObjectiveKind kind, String role) {
            for (Objective o : stage.objectives()) {
                if (o.kind() == kind && (role == null || o.role().equals(role))) {
                    return o;
                }
            }
            return null;
        }
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        Plan plan = Plan.of(view.stage());
        int id = member.characterId();
        String role = view.roleOf(id);
        if (plan.leaderRole().equals(role)) {
            return leaderStep(view, plan, id);
        }
        if (plan.answererRole().equals(role)) {
            return answererStep(view, plan, id);
        }
        return new SolverStep.Wait(2_000, "nothing to do on this stage (" + role + ")");
    }

    private static SolverStep leaderStep(StageView view, Plan plan, int id) {
        int need = Math.max(0, view.partySize() - 1);
        int have = view.itemCount(id, plan.passId());
        if (have >= need) {
            return new SolverStep.TalkNpc(plan.npcId(), plan.passId(), need);
        }
        if (view.floorItems(plan.passId(), id) > 0) {
            return new SolverStep.Loot(plan.passId());
        }
        // 1.6.2: while passes are still being earned the bot leader hunts next to the slowest farmer, as a player
        // leader does; the passes are dropped at his feet wherever he is, and he walks to the NPC once he has them
        SolverStep help = helpStep(view, plan, id);
        if (!(help instanceof SolverStep.Done)) {
            return help;
        }
        var npc = view.npcPosition(plan.npcId());
        if (npc.isPresent()) {
            int[] at = view.positionOf(id);
            int[] by = npc.get();
            if (Math.abs(at[0] - by[0]) > LEADER_WAIT_PX || Math.abs(at[1] - by[1]) > 90) {
                return new SolverStep.MoveTo(by[0] - LEADER_WAIT_PX / 2, by[1], "wait by the NPC");
            }
        }
        return new SolverStep.Wait(1_000, "waiting by the NPC for passes (" + have + "/" + need + ")");
    }

    private static SolverStep answererStep(StageView view, Plan plan, int id) {
        int status = view.memberStatus(id);
        int passes = view.itemCount(id, plan.passId());
        if (status == 0) {
            if (passes > 0) {
                var leader = view.leader();
                if (leader.isEmpty() || leader.get().characterId() == id) {
                    return new SolverStep.Wait(2_000, "holding the pass: no leader in the stage");
                }
                return new SolverStep.DropFor(plan.passId(), passes, leader.get().characterId());
            }
            return helpStep(view, plan, id);
        }
        if (status < 0) {
            return new SolverStep.TalkNpc(plan.npcId(), 0, 0);
        }
        OptionalInt answer = view.answerFor(id);
        if (answer.isEmpty()) {
            return new SolverStep.Wait(2_000, "question " + status + " has no answer in the data");
        }
        int coupons = view.itemCount(id, plan.couponId());
        int right = answer.getAsInt();
        if (coupons > right) {
            return new SolverStep.DropFor(plan.couponId(), coupons - right, 0);
        }
        int target = nextTarget(view.wrongTriesLeft(id), coupons, right);
        if (coupons < target) {
            return new SolverStep.Fight(plan.mobId(), plan.couponId(), target);
        }
        return new SolverStep.TalkNpc(plan.npcId(), plan.couponId(), coupons);
    }

    /**
     * 1.6.2 (TEST 2026-10-30, a real leader with three bots: after 5.5 minutes one bot was still hunting alone for
     * 17 coupons): a member whose pass is handed over keeps killing the farm mob next to whoever still needs
     * coupons, as players in a party do, and leaves the coupons to him (it loots nothing: {@code itemId} 0). Done
     * only when nobody farms any more.
     */
    static SolverStep helpStep(StageView view, Plan plan, int id) {
        RunMember farmer = null;
        int missing = 0;
        for (RunMember m : view.members()) {
            int other = m.characterId();
            if (other == id || !plan.answererRole().equals(view.roleOf(other)) || view.memberStatus(other) <= 0
                    || view.mapOf(other) != view.stage().mapId()) {
                continue;
            }
            OptionalInt answer = view.answerFor(other);
            int need = answer.isPresent() ? answer.getAsInt() - view.itemCount(other, plan.couponId()) : 0;
            if (farmer == null || need > missing) {
                farmer = m;
                missing = need;
            }
        }
        if (farmer == null) {
            return new SolverStep.Done();
        }
        int[] at = view.positionOf(id);
        int[] by = view.positionOf(farmer.characterId());
        if (Math.abs(at[0] - by[0]) > HELP_RADIUS_PX || Math.abs(at[1] - by[1]) > HELP_RADIUS_Y) {
            return new SolverStep.MoveTo(by[0], by[1], "help " + farmer.characterId() + " with coupons");
        }
        return new SolverStep.Fight(plan.mobId(), 0, 0);
    }

    /**
     * Whether a farming bot may pick up a coupon a monster dropped. The game lets party members share each other's
     * drops at once ({@code MapItem.canBePickedBy}); the bots take their own kills, a fellow bot's, and a real
     * member's only when he is not farming coupons himself (the leader, or one whose pass is handed in), so they
     * never vacuum a player's count. Anyone's once the drop's ownership window ran out.
     */
    static boolean mayTakeMobCoupon(StageView view, int botId, int ownerId, boolean ownershipOver) {
        if (ownerId == botId || ownerId <= 0 || ownershipOver) {
            return true;
        }
        for (RunMember m : view.members()) {
            if (m.characterId() == ownerId) {
                if (m.bot()) {
                    return true;
                }
                return m.leader() || view.memberStatus(ownerId) == 0;
            }
        }
        return false;
    }

    @Override
    public java.util.Optional<String> leaderPrompt(StageView view, RunMember leader) {
        Plan plan = Plan.of(view.stage());
        int id = leader.characterId();
        int need = Math.max(1, view.partySize() - 1);
        int have = view.itemCount(id, plan.passId());
        if (have >= need) {
            return java.util.Optional.of("@" + id + " u have all " + need + " passes, talk to Cloto");
        }
        if (have + view.floorItems(plan.passId(), id) >= need) {
            return java.util.Optional.of("@" + id + " pick up the passes by u and talk to Cloto");
        }
        return java.util.Optional.empty();
    }

    @Override
    public List<Integer> planWrongTries(Stage stage, int answer, MechanicsPersona persona, double mistakeFactor,
                                        Random rnd) {
        return plannedWrongCounts(answer, stage.answers().stream().map(MechanicsModel.Answer::value).toList(),
                persona, mistakeFactor, rnd);
    }

    /** The count the member hands in next: the first planned wrong count it has not passed yet, else the answer. */
    static int nextTarget(List<Integer> wrongLeft, int coupons, int right) {
        for (int w : wrongLeft) {
            if (w >= coupons && w < right) {
                return w;
            }
        }
        return right;
    }

    /**
     * The wrong coupon counts a member shows before the right one, ascending (it hands them in on its way up). The
     * plausible mistakes are the other answers in the table just below this one (a mixed-up question, at most
     * {@link #NEAR_MISS} short) and one short (a miscount); {@link TryPlanner} picks how many by persona and tier, at most {@link #MAX_WRONG_HAND_INS}.
     */
    public static List<Integer> plannedWrongCounts(int right, List<Integer> answerTable, MechanicsPersona persona,
                                                   double mistakeFactor, Random rnd) {
        TreeSet<Integer> options = new TreeSet<>();
        for (int a : answerTable) {
            if (a > 0 && a < right && right - a <= NEAR_MISS) {
                options.add(a);
            }
        }
        if (right > 1) {
            options.add(right - 1);
        }
        List<Integer> values = new ArrayList<>(options);
        values.add(right);
        List<Integer> picks = TryPlanner.tries(values.size() - 1, values.size(), persona, mistakeFactor, rnd,
                MAX_WRONG_HAND_INS);
        List<Integer> wrong = new ArrayList<>();
        for (int i = 0; i < picks.size() - 1; i++) {
            wrong.add(values.get(picks.get(i)));
        }
        wrong.sort(null);
        return List.copyOf(wrong);
    }
}
