package soloMapling.ArtificialPlayer.BotLifeSystem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity;

/**
 * 1.6.0 S8: a small goal-oriented action planner (GOAP) between the bot's goals ({@link LifeGoals}) and the utility
 * scorer ({@link LifePolicy}).
 *
 * <p>A goal is a fact to make true ({@link #goalFact}); the world is a set of {@link Fact}s read from the game
 * ({@link World}); a {@link Step} is an action of the {@link Activity} catalogue with preconditions and effects on those
 * facts. {@link #plan} finds the cheapest step sequence (uniform-cost search over at most 2^16 fact sets, a few hundred
 * expansions in practice). The bot does not follow the plan blindly: the plan's next step only gives its activity a
 * context bonus ({@link #STEP_BONUS} x goal pressure), so restocking, a friend's ask or fatigue still win.
 *
 * <p>Re-planning happens only when a goal or a fact changes ({@link #update}): the stored plan keeps its goal, target
 * and fact mask, and a tick with the same signature reuses it. Plans are saved in the bot's life profile
 * ({@link LifeMemory}), never the roster's activity state.
 *
 * <p>Some facts the game cannot tell (whether the bot met guild people, did the Zakum prequest, has loot to sell).
 * Those are {@link #SOFT}: a step that makes one is marked done when the bot finishes that step's activity
 * successfully ({@link LifeOutcomes}), and they are cleared when the goal changes.
 *
 * <p>Honest limits: bots advance jobs automatically when they reach the level (BotBuild), so the "travel to the
 * instructor" steps of a job goal only show for a bot that has the level and not the job. Nothing here grants items,
 * mesos, EXP or advances.
 */
public final class LifePlanner {
    private LifePlanner() {}

    /** The context bonus of the plan's next step at full goal pressure. */
    public static final double STEP_BONUS = .45;
    /** Search bound: never more expansions than this per plan. */
    static final int MAX_EXPANSIONS = 512;
    private static final LongAdder replans = new LongAdder();

    public enum Fact {
        LEVEL_READY, AT_INSTRUCTOR, ADVANCED, WEAPON_OK, HAS_FRIEND, HAS_CRUSH, HAS_RING, MARRIED,
        GUILD_CONTACT, IN_GUILD, ZAKUM_LEVEL, ZAKUM_READY, ZHELM, LOOT, RICH,
        /* Ticket 162: the farmed gear dropped (soft: made true by finishing the farm step). */
        GEAR_FARMED;

        int bit() { return 1 << ordinal(); }
    }

    /** Facts the game can't answer: made true by finishing the step that causes them. */
    public static final Set<Fact> SOFT = Collections.unmodifiableSet(EnumSet.of(Fact.AT_INSTRUCTOR,
            Fact.GUILD_CONTACT, Fact.ZAKUM_READY, Fact.LOOT, Fact.GEAR_FARMED));

    public enum Step {
        GRIND_TO_LEVEL(Activity.GRIND, "grind to level %d", 2, List.of(), Fact.LEVEL_READY),
        TRAVEL_TO_INSTRUCTOR(Activity.HANG_OUT, "travel to the job instructor's town", 1,
                List.of(Fact.LEVEL_READY), Fact.AT_INSTRUCTOR),
        TALK_TO_INSTRUCTOR(Activity.HANG_OUT, "talk to the job instructor", 1, List.of(Fact.AT_INSTRUCTOR),
                Fact.ADVANCED),
        SHOP_FOR_WEAPON(Activity.FREE_MARKET, "shop the FM for a better weapon", 2, List.of(), Fact.WEAPON_OK),
        MAKE_FRIENDS(Activity.HANG_OUT, "make friends in town", 2, List.of(), Fact.HAS_FRIEND),
        FIND_A_CRUSH(Activity.HANG_OUT, "spend time with a crush", 2, List.of(Fact.HAS_FRIEND), Fact.HAS_CRUSH),
        GET_A_RING(Activity.FREE_MARKET, "get a couple ring", 1, List.of(Fact.HAS_CRUSH), Fact.HAS_RING),
        WEDDING(Activity.HANG_OUT, "plan the wedding in Amoria", 1, List.of(Fact.HAS_RING), Fact.MARRIED),
        MEET_A_GUILD(Activity.HANG_OUT, "meet guild people in town", 1, List.of(), Fact.GUILD_CONTACT),
        JOIN_A_GUILD(Activity.HANG_OUT, "get a guild invite", 1, List.of(Fact.GUILD_CONTACT), Fact.IN_GUILD),
        GRIND_TO_50(Activity.GRIND, "grind to level 50", 2, List.of(), Fact.ZAKUM_LEVEL),
        ZAKUM_PREQUEST(Activity.PARTY_QUEST, "do the Zakum prequest in El Nath", 2, List.of(Fact.ZAKUM_LEVEL),
                Fact.ZAKUM_READY),
        ZAKUM_RUN(Activity.PARTY_QUEST, "find a Zakum run for the helmet", 2, List.of(Fact.ZAKUM_READY), Fact.ZHELM),
        FARM_LOOT(Activity.GRIND, "farm loot to sell", 1, List.of(), Fact.LOOT),
        SELL_LOOT(Activity.FREE_MARKET, "sell loot in the FM", 1, List.of(Fact.LOOT), Fact.RICH),
        /* Ticket 162: grind where the wanted drop falls (GearFirstAdvisor.farmPick picks the map). */
        FARM_GEAR(Activity.GRIND, "farm the gear drop it wants", 1, List.of(), Fact.GEAR_FARMED);

        public final Activity activity;
        final String text;
        final int cost, pre, eff;
        public final Fact effect;

        Step(Activity activity, String text, int cost, List<Fact> pre, Fact effect) {
            this.activity = activity;
            this.text = text;
            this.cost = cost;
            int mask = 0;
            for (Fact f : pre) mask |= f.bit();
            this.pre = mask;
            this.eff = effect.bit();
            this.effect = effect;
        }

        public String describe(int target) {
            return text.contains("%d") ? String.format(Locale.ROOT, text, target) : text;
        }
    }

    /** What the planner reads: the goal facts plus the social facts the goal seeding doesn't need. */
    public record World(LifeGoals.Facts facts, boolean hasFriend, boolean hasCrush, boolean hasRing, Set<Fact> soft) {
        public World {
            Objects.requireNonNull(facts);
            soft = soft == null || soft.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(soft));
        }
    }

    /** One goal's plan: the steps left, in order, and the fact mask it was made from (the re-plan signature). */
    public record Plan(LifeGoals.Kind goal, int target, List<Step> steps, int factMask) {
        public Plan {
            Objects.requireNonNull(goal);
            steps = List.copyOf(steps);
        }

        public Step next() {
            return steps.isEmpty() ? null : steps.getFirst();
        }

        public String describe() {
            if (steps.isEmpty()) return goal + ": nothing left to do";
            List<String> parts = new ArrayList<>();
            for (Step s : steps) parts.add(s.describe(target));
            return goal + ": " + String.join(" -> ", parts);
        }
    }

    public static Fact goalFact(LifeGoals.Kind kind) {
        return switch (kind) {
            case JOB_ADVANCE, FOURTH_JOB -> Fact.ADVANCED;
            case LEVEL -> Fact.LEVEL_READY;
            case WEAPON_UPGRADE -> Fact.WEAPON_OK;
            case MARRIAGE -> Fact.MARRIED;
            case GUILD -> Fact.IN_GUILD;
            case ZHELM -> Fact.ZHELM;
            case WEALTH -> Fact.RICH;
            case FARM_ITEM -> Fact.GEAR_FARMED;
        };
    }

    /** The facts true for this goal now (level readiness is against this goal's target). */
    public static int mask(LifeGoals.Goal goal, World world) {
        LifeGoals.Facts f = world.facts();
        int mask = 0;
        if (f.level() >= goal.targetLevel()) mask |= Fact.LEVEL_READY.bit();
        if ((goal.kind() == LifeGoals.Kind.JOB_ADVANCE || goal.kind() == LifeGoals.Kind.FOURTH_JOB)
                && LifeGoals.met(goal, f)) mask |= Fact.ADVANCED.bit();
        if (goal.kind() == LifeGoals.Kind.WEAPON_UPGRADE && LifeGoals.met(goal, f)) mask |= Fact.WEAPON_OK.bit();
        if (world.hasFriend()) mask |= Fact.HAS_FRIEND.bit();
        if (world.hasCrush()) mask |= Fact.HAS_CRUSH.bit();
        if (world.hasRing()) mask |= Fact.HAS_RING.bit();
        if (f.married()) mask |= Fact.MARRIED.bit() | Fact.HAS_RING.bit() | Fact.HAS_CRUSH.bit();
        if (f.inGuild()) mask |= Fact.IN_GUILD.bit();
        if (f.level() >= 50) mask |= Fact.ZAKUM_LEVEL.bit();
        if (f.hasZhelm()) mask |= Fact.ZHELM.bit();
        if (f.meso() >= LifeGoals.WEALTH_TARGET) mask |= Fact.RICH.bit();
        for (Fact soft : world.soft()) mask |= soft.bit();
        return mask;
    }

    /** The cheapest step sequence from the world's facts to the goal's fact (empty when already true or unreachable). */
    public static Plan plan(LifeGoals.Goal goal, World world) {
        int start = mask(goal, world);
        int want = goalFact(goal.kind()).bit();
        replans.increment();
        if ((start & want) != 0) return new Plan(goal.kind(), goal.targetLevel(), List.of(), start);
        // Uniform-cost search; ties broken by step order so the plan is deterministic.
        Map<Integer, Integer> cost = new HashMap<>();
        Map<Integer, int[]> parent = new HashMap<>(); // state -> {previous state, step ordinal}
        java.util.PriorityQueue<int[]> open = new java.util.PriorityQueue<>((x, y) -> x[1] != y[1]
                ? Integer.compare(x[1], y[1]) : Integer.compare(x[2], y[2]));
        // Only steps that lead toward the goal (their effect is the goal or a precondition of such a step).
        int relevant = want;
        for (boolean grew = true; grew; ) {
            grew = false;
            for (Step step : Step.values()) {
                if ((step.eff & relevant) != 0 && (step.pre & ~relevant) != 0) { relevant |= step.pre; grew = true; }
            }
        }
        open.add(new int[]{start, 0, 0});
        cost.put(start, 0);
        int expansions = 0, sequence = 0;
        while (!open.isEmpty() && expansions++ < MAX_EXPANSIONS) {
            int[] node = open.poll();
            int state = node[0];
            if (node[1] > cost.getOrDefault(state, Integer.MAX_VALUE)) continue;
            if ((state & want) != 0) return new Plan(goal.kind(), goal.targetLevel(), path(parent, start, state), start);
            for (Step step : Step.values()) {
                if ((step.eff & relevant) == 0 || (step.pre & ~state) != 0 || (step.eff & ~state) == 0) continue;
                int next = state | step.eff;
                int c = node[1] + step.cost;
                if (c < cost.getOrDefault(next, Integer.MAX_VALUE)) {
                    cost.put(next, c);
                    parent.put(next, new int[]{state, step.ordinal()});
                    open.add(new int[]{next, c, ++sequence});
                }
            }
        }
        return new Plan(goal.kind(), goal.targetLevel(), List.of(), start);
    }

    private static List<Step> path(Map<Integer, int[]> parent, int start, int end) {
        ArrayDeque<Step> out = new ArrayDeque<>();
        int at = end;
        while (at != start) {
            int[] p = parent.get(at);
            out.addFirst(Step.values()[p[1]]);
            at = p[0];
        }
        return List.copyOf(out);
    }

    /**
     * The plans for these goals: a stored plan whose goal, target and fact mask match is kept as it is (no search);
     * anything else is planned again. Returns the same list instance when nothing changed.
     */
    public static List<Plan> update(List<LifeGoals.Goal> goals, World world, List<Plan> stored) {
        List<Plan> out = new ArrayList<>(goals.size());
        boolean changed = stored.size() != goals.size();
        for (int i = 0; i < goals.size(); i++) {
            LifeGoals.Goal goal = goals.get(i);
            Plan old = i < stored.size() ? stored.get(i) : null;
            if (old != null && old.goal() == goal.kind() && old.target() == goal.targetLevel()
                    && old.factMask() == mask(goal, world)) {
                out.add(old);
            } else {
                out.add(plan(goal, world));
                changed = true;
            }
        }
        return changed ? List.copyOf(out) : stored;
    }

    /**
     * The step the bot should lean toward now: the next step of the most pressing goal that has one, with its bonus
     * ({@link #STEP_BONUS} x pressure). Null when no goal has a step left.
     */
    public record Next(Plan plan, Step step, double bonus) {}

    public static Next next(List<LifeGoals.Goal> goals, List<Plan> plans) {
        Next best = null;
        for (int i = 0; i < Math.min(goals.size(), plans.size()); i++) {
            Step step = plans.get(i).next();
            if (step == null) continue;
            double bonus = STEP_BONUS * Math.max(.25, goals.get(i).pressure());
            if (best == null || bonus > best.bonus()) best = new Next(plans.get(i), step, bonus);
        }
        return best;
    }

    /**
     * Where a step takes the bot, or -1 for "wherever it is" (grinding, its usual town). Towns only: the bot hangs out
     * there; nothing here talks to an NPC or enters a boss.
     */
    public static int destination(Step step, LifeGoals.Facts facts, int target) {
        return switch (step) {
            case TRAVEL_TO_INSTRUCTOR, TALK_TO_INSTRUCTOR -> instructorTown(facts.jobId(), target);
            case WEDDING -> 680000000;                        // Amoria
            case ZAKUM_PREQUEST -> 211000000;                 // El Nath
            case ZAKUM_RUN -> 211042300;                      // The Door to Zakum
            case SHOP_FOR_WEAPON, GET_A_RING, SELL_LOOT -> 910000000; // Free Market Entrance
            default -> -1;
        };
    }

    /** The town of the instructor for the advance at {@code target}, by class; -1 when unknown (a beginner's 1st). */
    static int instructorTown(int job, int target) {
        if (job >= 1000 && job < 2000) return 130000000;      // Ereve (Cygnus)
        if (job >= 2000 && job < 2200) return 140000000;      // Rien (Aran)
        if (target >= 120) return 240000000;                  // Leafre
        if (target >= 70) return 211000000;                   // El Nath
        if (target <= 10) return -1;
        return switch (job / 100) {
            case 1 -> 102000000;  // Perion
            case 2 -> 101000000;  // Ellinia
            case 3 -> 100000000;  // Henesys
            case 4 -> 103000000;  // Kerning City
            case 5 -> 120000000;  // Nautilus
            default -> -1;
        };
    }

    /** How many plans were searched this run (tests, metrics: proves re-planning happens only on a change). */
    public static long replans() {
        return replans.sum();
    }

    // ── saving: plain maps for LifeMemory's JSON ──

    static Map<String, Object> save(Plan plan) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("goal", plan.goal().name());
        out.put("target", plan.target());
        out.put("mask", plan.factMask());
        out.put("steps", plan.steps().stream().map(Enum::name).toList());
        return out;
    }

    static Plan read(Map<?, ?> map) {
        List<Step> steps = new ArrayList<>();
        if (map.get("steps") instanceof List<?> names) for (Object name : names) steps.add(Step.valueOf((String) name));
        return new Plan(LifeGoals.Kind.valueOf((String) map.get("goal")), ((Number) map.get("target")).intValue(),
                steps, ((Number) map.get("mask")).intValue());
    }
}
