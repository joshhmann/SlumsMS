package soloMapling.ArtificialPlayer.BotProgressionSystem;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A bot's character build, as pure arithmetic (real-bots tickets 05/06): which job it advances into and when, where
 * its AP goes and where its SP goes. Nothing here touches a {@code Character}, the database or the server's skill
 * data directly - {@link BotProgression} reads a bot into a {@link State}, runs these rules on it and writes it back.
 *
 * <p><b>Coverage.</b> MapleRoot's regular classes up to level {@value #MAX_LEVEL}: Explorers (beginner, warrior,
 * magician, bowman, thief, pirate and their branches), Cygnus Knights and Aran, each with four advancements. Any other
 * job - the 5th tier (x13, rebirth- and item-gated), Super Beginner and Synth Master (700/710), Evan, GM jobs - is
 * refused ({@link #isBuildJob(int)}), and the caller must leave such a bot exactly as it is rather than guess a build
 * for it.
 *
 * <p><b>Points.</b> The amounts mirror the server's own level-up and job-change rules ({@code Character.levelUp},
 * {@code Character.changeJob}) so a bot gains what a player would: 5 AP per level, 3 SP per level once it has a
 * job, 1 SP on every advancement (3 on the 4th), 5 AP on the 3rd and 4th advancement (7 on each Cygnus one), and
 * the average of the server's HP/MP rolls. A new character starts at 4/4/4/4 with 9 AP, the dice total.
 *
 * <p><b>AP.</b> Every class has a primary stat and a secondary one; AP goes to the secondary until it reaches its
 * target for the bot's level, otherwise to the primary:
 * <ul>
 *   <li>Warrior, Dawn Warrior, Aran: STR, with DEX at 4 + level/2.</li>
 *   <li>Magician, Blaze Wizard: INT, with LUK at level + 3.</li>
 *   <li>Bowman, Wind Archer: DEX, with STR at 4 + level/2.</li>
 *   <li>Thief, Night Walker: LUK, with DEX at 4 + level/2 and at least 25 (the 1st-job requirement).</li>
 *   <li>Brawler line, Thunder Breaker: STR, with DEX at 4 + level/2 and at least 20; Gunslinger line: DEX, with
 *       STR at 4 + level/2.</li>
 * </ul>
 * A beginner already builds for the class it is going to take.
 *
 * <p><b>SP.</b> Per job, in the order of {@link BotSkillPlan}: attacks first, then mastery, then key buffs. The
 * current job's list is filled first and SP left over goes down to the earlier jobs' lists. A skill is never raised
 * above the lower of its plan target and the server's maximum ({@link SkillCatalog}), a skill the server lacks is
 * skipped, and a skill an NPC teaches ({@link BotSkillPlan#isGranted}) is only raised once the bot holds it. SP
 * nothing can absorb stays unspent, as it would for a player.
 *
 * <p><b>Advancement.</b> 1st job at 10 (8 for a magician), 2nd at 30, 3rd at 70, 4th at 120, for every class - the
 * Job Master (NPC 9010003) gives Cygnus Knights a 4th job too. The class and branch come from {@link Choice}: the
 * 2nd job the bot's weapon points to when it fits the class, otherwise one picked from the bot's id so the same bot
 * always picks the same. An advancement also brings what the NPCs teach with it
 * ({@link BotSkillPlan#advancementGrants}), and the 4th job the package of NPC 2084000
 * ({@link BotSkillPlan#fourthJobPackage}), counted in {@link State#packagesBought} so the caller can charge for it.
 */
public final class BotBuild {

    /** Builds are defined up to here; levels above it (MapleRoot goes to 255) gain nothing more from a build. */
    public static final int MAX_LEVEL = 200;
    public static final int AP_PER_LEVEL = 5;
    public static final int SP_PER_LEVEL = 3;
    static final int START_STAT = 4;
    static final int START_AP = 9;
    static final int START_HP = 50;
    static final int START_MP = 5;

    private static final int[] EXPLORER_CLASSES = {100, 200, 300, 400, 500};
    private static final int[] CYGNUS_CLASSES = {1100, 1200, 1300, 1400, 1500};

    private static final Set<Integer> BUILD_JOBS = Set.of(
            0, 100, 110, 111, 112, 120, 121, 122, 130, 131, 132,
            200, 210, 211, 212, 220, 221, 222, 230, 231, 232,
            300, 310, 311, 312, 320, 321, 322,
            400, 410, 411, 412, 420, 421, 422,
            500, 510, 511, 512, 520, 521, 522,
            1000, 1100, 1110, 1111, 1112, 1200, 1210, 1211, 1212, 1300, 1310, 1311, 1312,
            1400, 1410, 1411, 1412, 1500, 1510, 1511, 1512,
            2000, 2100, 2110, 2111, 2112);

    public enum Stat { STR, DEX, INT, LUK }

    /** The server's maximum level of a skill, or 0 when the server does not have the skill at all. */
    @FunctionalInterface
    public interface SkillCatalog {
        int maxLevel(int skillId);

        /** Trusts the plan's own targets: every listed skill exists, up to its plan target. */
        SkillCatalog PLAN = skillId -> Integer.MAX_VALUE;
    }

    /**
     * How a bot picks within its class. {@code secondJobHint} is the 2nd job its weapon points to (0 for none) and
     * {@code seed} breaks ties the same way every time for the same bot.
     */
    public record Choice(int secondJobHint, int seed) {
        public static Choice of(int seed) {
            return new Choice(0, seed);
        }
    }

    /** A bot's build state: job, level, base stats, pools, unspent points and skills (id to level). */
    public static final class State {
        public int job;
        public int level;
        public int str = START_STAT;
        public int dex = START_STAT;
        public int int_ = START_STAT;
        public int luk = START_STAT;
        public int maxHp = START_HP;
        public int maxMp = START_MP;
        public int remainingAp = START_AP;
        /** Unspent SP. Every job covered here uses skill book 0, so this is that book's count. */
        public int sp;
        public final Map<Integer, Integer> skills = new TreeMap<>();
        /** How many 4th-job packages (NPC 2084000) this state was given since it was made; never stored. */
        public int packagesBought;

        public State(int job, int level) {
            this.job = job;
            this.level = level;
        }

        public int stat(Stat stat) {
            return switch (stat) {
                case STR -> str;
                case DEX -> dex;
                case INT -> int_;
                case LUK -> luk;
            };
        }

        void addStat(Stat stat) {
            switch (stat) {
                case STR -> str++;
                case DEX -> dex++;
                case INT -> int_++;
                case LUK -> luk++;
            }
        }

        public int skillLevel(int skillId) {
            return skills.getOrDefault(skillId, 0);
        }
    }

    private BotBuild() {
    }

    // ---------------------------------------------------------------- jobs

    /** True for a job this build covers. Anything else must be left untouched by the caller. */
    public static boolean isBuildJob(int jobId) {
        return BUILD_JOBS.contains(jobId);
    }

    static boolean isBeginner(int jobId) {
        return jobId == 0 || jobId == 1000 || jobId == 2000;
    }

    static boolean isCygnus(int jobId) {
        return jobId >= 1000 && jobId < 2000;
    }

    /** 0 for a beginner, 1..4 for the 1st..4th job. */
    public static int tier(int jobId) {
        if (isBeginner(jobId)) {
            return 0;
        }
        if (jobId % 100 == 0) {
            return 1;
        }
        return jobId % 10 + 2;
    }

    /** The 1st job of a job's line, or the beginner job itself. */
    static int firstJobOf(int jobId) {
        return isBeginner(jobId) ? jobId : jobId / 100 * 100;
    }

    /** The 2nd job of a job's line (2nd job and up only). */
    static int secondJobOf(int jobId) {
        return jobId / 10 * 10;
    }

    /** The jobs a bot of this job has passed through, 1st job first (empty for a beginner). */
    public static int[] lineage(int jobId) {
        int tier = tier(jobId);
        int[] jobs = new int[tier];
        for (int t = 1; t <= tier; t++) {
            jobs[t - 1] = t == 1 ? firstJobOf(jobId) : secondJobOf(jobId) + (t - 2);
        }
        return jobs;
    }

    /** The class a beginner of this kind takes at its 1st advancement. */
    static int plannedFirstJob(int jobId, Choice choice) {
        if (!isBeginner(jobId)) {
            return firstJobOf(jobId);
        }
        int hint = choice.secondJobHint();
        if (jobId == 2000) {
            return 2100;
        }
        if (jobId == 1000) {
            int hinted = cygnusClassFor(hint);
            return hinted != 0 ? hinted : CYGNUS_CLASSES[Math.floorMod(choice.seed(), CYGNUS_CLASSES.length)];
        }
        if (hint >= 110 && hint < 600) {
            return hint / 100 * 100;
        }
        return EXPLORER_CLASSES[Math.floorMod(choice.seed(), EXPLORER_CLASSES.length)];
    }

    /** The Cygnus class matching a hint (a Cygnus job, or the explorer job of the same weapon), or 0. */
    private static int cygnusClassFor(int hint) {
        int explorer = hint >= 1000 && hint < 2000 ? hint - 1000 : hint;
        if (explorer < 100 || explorer >= 600) {
            return 0;
        }
        return switch (explorer / 100) {
            case 1 -> 1100;
            case 2 -> 1200;
            case 3 -> 1300;
            case 4 -> 1400;
            case 5 -> 1500;
            default -> 0;
        };
    }

    /** The 2nd job this bot takes from its 1st job (or is heading for, as a beginner). */
    static int plannedSecondJob(int jobId, Choice choice) {
        if (tier(jobId) >= 2) {
            return secondJobOf(jobId);
        }
        int first = plannedFirstJob(jobId, choice);
        if (isCygnus(first) || first == 2100) {
            return first + 10;
        }
        int[] branches = switch (first) {
            case 100 -> new int[]{110, 120, 130};
            case 200 -> new int[]{210, 220, 230};
            case 300 -> new int[]{310, 320};
            case 400 -> new int[]{410, 420};
            case 500 -> new int[]{510, 520};
            default -> new int[0];
        };
        for (int branch : branches) {
            if (branch == choice.secondJobHint() / 10 * 10) {   // a 3rd/4th job hint names its branch too
                return branch;
            }
        }
        return branches[Math.floorMod(choice.seed(), branches.length)];
    }

    /** The job a bot advances into from this one, or -1 when there is none. */
    public static int nextJob(int jobId, Choice choice) {
        return switch (tier(jobId)) {
            case 0 -> plannedFirstJob(jobId, choice);
            case 1 -> plannedSecondJob(jobId, choice);
            case 2, 3 -> jobId + 1;
            default -> -1;
        };
    }

    /** The level at which a bot of this job advances, or 0 when it never does. */
    public static int advancementLevel(int jobId, Choice choice) {
        return switch (tier(jobId)) {
            case 0 -> plannedFirstJob(jobId, choice) == 200 ? 8 : 10;
            case 1 -> 30;
            case 2 -> 70;
            case 3 -> 120;
            default -> 0;
        };
    }

    // ---------------------------------------------------------------- AP

    /** The stat a class pours its AP into. */
    public static Stat primaryStat(int jobId, Choice choice) {
        return switch (classKey(jobId, choice)) {
            case 100, 1100, 2100, 510, 1500 -> Stat.STR;
            case 200, 1200 -> Stat.INT;
            case 300, 1300, 520 -> Stat.DEX;
            case 400, 1400 -> Stat.LUK;
            default -> Stat.STR;
        };
    }

    /** The stat a class keeps at its requirement. */
    public static Stat secondaryStat(int jobId, Choice choice) {
        return switch (classKey(jobId, choice)) {
            case 200, 1200 -> Stat.LUK;
            case 300, 1300, 520 -> Stat.STR;
            default -> Stat.DEX;
        };
    }

    /** What the secondary stat is raised to at this level before AP goes to the primary. */
    public static int secondaryTarget(int jobId, int level, Choice choice) {
        int key = classKey(jobId, choice);
        return switch (key) {
            case 200, 1200 -> level + 3;
            case 400, 1400 -> Math.max(25, START_STAT + level / 2);
            case 510, 1500 -> Math.max(20, START_STAT + level / 2);
            default -> START_STAT + level / 2;
        };
    }

    /** The class a build follows: the 1st job, or the pirate branch, since brawlers and gunslingers differ. */
    private static int classKey(int jobId, Choice choice) {
        int first = plannedFirstJob(jobId, choice);
        if (first == 500) {
            return plannedSecondJob(jobId, choice);
        }
        return first;
    }

    /** Spends every unspent AP of the state by the class rule. */
    public static void spendAp(State s, Choice choice) {
        Stat primary = primaryStat(s.job, choice);
        Stat secondary = secondaryStat(s.job, choice);
        int target = secondaryTarget(s.job, Math.min(s.level, MAX_LEVEL), choice);
        while (s.remainingAp > 0) {
            s.addStat(s.stat(secondary) < target ? secondary : primary);
            s.remainingAp--;
        }
    }

    // ---------------------------------------------------------------- SP

    /** Whether the bot takes the second form of each pair in its SP order (the dagger thief, the gunslinger). */
    static boolean takesAlternate(int jobId, Choice choice) {
        int second = plannedSecondJob(jobId, choice);
        return switch (plannedFirstJob(jobId, choice)) {
            case 400 -> second == 420;
            case 500 -> second == 520;
            default -> false;
        };
    }

    /** True for a 4th-job skill: those carry a master level. */
    public static boolean isFourthJobSkill(int skillId) {
        int job = skillId / 10000;
        return isBuildJob(job) && tier(job) == 4;
    }

    /** The highest level this build ever raises the skill to (its plan target capped by the server), or 0. */
    public static int targetLevel(int jobId, int skillId, SkillCatalog catalog) {
        int best = 0;
        for (BotSkillPlan.Entry entry : BotSkillPlan.forJob(jobId)) {
            if (entry.skillId() == skillId || entry.altSkillId() == skillId) {
                best = Math.max(best, entry.target());
            }
        }
        return Math.min(best, Math.max(0, catalog.maxLevel(skillId)));
    }

    /** Spends the state's SP: the current job's order first, then down the lineage for what is left. */
    public static void spendSp(State s, Choice choice, SkillCatalog catalog) {
        int[] jobs = lineage(s.job);
        boolean alt = takesAlternate(s.job, choice);
        for (int i = jobs.length - 1; i >= 0 && s.sp > 0; i--) {
            for (BotSkillPlan.Entry entry : BotSkillPlan.forJob(jobs[i])) {
                if (s.sp <= 0) {
                    break;
                }
                int skillId = entry.skillFor(alt);
                int max = catalog.maxLevel(skillId);
                if (max <= 0) {
                    continue;   // the server does not have this skill: skip it rather than invent it
                }
                int target = Math.min(entry.target(), max);
                int have = s.skillLevel(skillId);
                if (have >= target || (have == 0 && BotSkillPlan.isGranted(skillId))) {
                    continue;   // done, or taught by an NPC the bot has not been to yet
                }
                int add = Math.min(target - have, s.sp);
                s.skills.put(skillId, have + add);
                s.sp -= add;
            }
        }
    }

    // ---------------------------------------------------------------- levels and advancement

    /** Average HP/MP the server rolls on a level-up for this job ({@code Character.levelUp}). */
    static int[] levelUpPools(int jobId) {
        if (isBeginner(jobId)) {
            return new int[]{60, 60};
        }
        int first = firstJobOf(jobId);
        return switch (first) {
            case 100, 1100 -> new int[]{26, 5};
            case 200, 1200 -> new int[]{12, 23};
            case 300, 400, 1300, 1400 -> new int[]{22, 15};
            case 500, 1500 -> new int[]{25, 20};
            case 2100 -> new int[]{46, 6};
            default -> new int[]{0, 0};
        };
    }

    /** Average HP/MP the server adds on an advancement into this job ({@code Character.changeJob}). */
    static int[] advancementPools(int newJobId) {
        int job = newJobId % 1000;
        if (job == 100) {
            return new int[]{225, 0};
        } else if (job == 200) {
            return new int[]{0, 125};
        } else if (job % 100 == 0) {
            return new int[]{1, 37};
        } else if (job < 200) {
            return new int[]{325, 0};
        } else if (job < 300) {
            return new int[]{0, 475};
        }
        return new int[]{325, 175};
    }

    /** The points one level-up grants a bot of this job, as the server grants them. */
    public static void grantLevel(State s) {
        s.remainingAp += AP_PER_LEVEL;
        if (tier(s.job) > 0) {
            s.sp += SP_PER_LEVEL;
        }
        int[] pools = levelUpPools(s.job);
        s.maxHp += pools[0];
        s.maxMp += pools[1];
    }

    /**
     * Moves the state into its next job with the points the server grants for the advancement, and what the NPCs
     * teach with it: the Job Master's grants and, on the 4th job, the skill package.
     */
    static void advance(State s, int newJob, SkillCatalog catalog) {
        s.job = newJob;
        s.sp += tier(newJob) == 4 ? 3 : 1;
        int tier = tier(newJob);
        if (isCygnus(newJob) && tier >= 2) {
            s.remainingAp += 7;
        } else if (tier >= 3) {
            s.remainingAp += 5;
        }
        int[] pools = advancementPools(newJob);
        s.maxHp += pools[0];
        s.maxMp += pools[1];
        grant(s, BotSkillPlan.advancementGrants(newJob), catalog);
        grantFourthJobPackage(s, catalog);
    }

    /**
     * Gives a 4th-job state its share of NPC 2084000's package, once: a bot that just advanced, or one built before
     * the package was modelled. Echo of Hero marks it as bought, since nothing else teaches it (Hero's Will could have
     * been bought with SP by an older build); without Echo in the server's data, any package skill held does.
     * Counted in {@link State#packagesBought} when it granted anything.
     */
    static void grantFourthJobPackage(State s, SkillCatalog catalog) {
        List<BotSkillPlan.Grant> pack = BotSkillPlan.fourthJobPackage(s.job);
        if (tier(s.job) != 4 || pack.isEmpty()) {
            return;
        }
        int marker = pack.get(0).skillId();
        if (catalog.maxLevel(marker) > 0) {
            if (s.skillLevel(marker) > 0) {
                return;   // already bought
            }
        } else {
            for (BotSkillPlan.Grant g : pack) {
                if (s.skillLevel(g.skillId()) > 0) {
                    return;
                }
            }
        }
        if (grant(s, pack, catalog) > 0) {
            s.packagesBought++;
        }
    }

    /** Teaches each skill at its grant level (capped by the server, skipped when the server lacks it); no SP spent. */
    private static int grant(State s, List<BotSkillPlan.Grant> grants, SkillCatalog catalog) {
        int granted = 0;
        for (BotSkillPlan.Grant g : grants) {
            int level = Math.min(g.level(), Math.max(0, catalog.maxLevel(g.skillId())));
            if (level > s.skillLevel(g.skillId())) {
                s.skills.put(g.skillId(), level);
                granted++;
            }
        }
        return granted;
    }

    /**
     * Brings the state in line with its level: SP goes into the current job first (a player has to spend it before
     * the advancement NPC will talk to them), then every advancement due at this level happens, each followed by
     * its own SP spend, and finally the AP is spent.
     */
    public static void settle(State s, Choice choice, SkillCatalog catalog) {
        grantFourthJobPackage(s, catalog);
        spendSp(s, choice, catalog);
        int level = Math.min(s.level, MAX_LEVEL);
        while (true) {
            int at = advancementLevel(s.job, choice);
            int next = nextJob(s.job, choice);
            if (at <= 0 || next < 0 || level < at || !isBuildJob(next)) {
                break;
            }
            advance(s, next, catalog);
            spendSp(s, choice, catalog);
        }
        spendAp(s, choice);
    }

    /**
     * Grants and settles the levels after {@code fromLevel} up to the state's level, one level at a time, as a bot
     * that had been playing all along would have got them. Levels past {@link #MAX_LEVEL} grant nothing.
     */
    public static void catchUp(State s, int fromLevel, Choice choice, SkillCatalog catalog) {
        int target = s.level;
        for (int l = fromLevel + 1; l <= Math.min(target, MAX_LEVEL); l++) {
            s.level = l - 1;
            grantLevel(s);   // the level-up is granted at the job held before it, as the server does
            s.level = l;
            settle(s, choice, catalog);
        }
        s.level = target;
        settle(s, choice, catalog);
    }

    /**
     * The whole build of a bot of this job and level, from a fresh level-1 character: what an existing bot with
     * no stored build is back-filled with, and what a new bot starts with. The job is never taken backwards - a bot
     * whose stored job is ahead of what its level would give keeps it, by advancing along its own line - and a
     * job behind its level advances, as it would on its next level-up.
     *
     * @throws IllegalArgumentException for a job the build does not cover
     */
    public static State plan(int jobId, int level, Choice choice, SkillCatalog catalog) {
        if (!isBuildJob(jobId)) {
            throw new IllegalArgumentException("No build for job " + jobId);
        }
        int beginner = isCygnus(jobId) ? 1000 : jobId >= 2000 ? 2000 : 0;
        Choice line = alongLineOf(jobId, choice);
        State s = new State(beginner, 1);
        s.level = Math.max(1, Math.min(level, MAX_LEVEL));
        catchUp(s, 1, line, catalog);
        while (tier(s.job) < tier(jobId)) {
            int next = nextJob(s.job, line);
            if (next < 0) {
                break;
            }
            advance(s, next, catalog);
            spendSp(s, line, catalog);
        }
        spendAp(s, line);
        s.level = Math.max(1, level);
        return s;
    }

    /** A choice that keeps a bot on the line of the job it already has: past the beginner, its class is no choice. */
    static Choice alongLineOf(int jobId, Choice choice) {
        if (tier(jobId) == 0) {
            return choice;
        }
        return new Choice(plannedSecondJob(jobId, choice), choice.seed());
    }
}
