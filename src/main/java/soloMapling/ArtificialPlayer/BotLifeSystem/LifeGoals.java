package soloMapling.ArtificialPlayer.BotLifeSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Derives at most two provisional aspirations. These never grant gear, EXP, money or job advances.
 *
 * <p>1.6.0 S8: next to the progression goals (JOB_ADVANCE, WEAPON_UPGRADE, LEVEL) a bot gets one life aspiration from
 * its point on Bartle's chart ({@link #seed(Facts)}): a Socializer wants to get married or join a guild, a Killer wants
 * a guild and a Zakum Helmet, an Achiever a Zakum Helmet, an Explorer to get rich. The third job's advance at 120 is
 * the {@link Kind#FOURTH_JOB} goal. {@link LifePlanner} turns each goal into a plan of steps.
 */
public final class LifeGoals {
    private LifeGoals() {}
    public enum Kind {
        JOB_ADVANCE, WEAPON_UPGRADE, LEVEL, MARRIAGE, GUILD, ZHELM, FOURTH_JOB, WEALTH,
        /** Ticket 158: a kill quest the bot took for its grind map (BotQuests); its target is the quest id. */
        QUEST,
        /* Ticket 162 part 2: farm a gear drop the gear-first map choice found worth chasing (-Dslums.grind.gearFirst). */
        FARM_ITEM;

        /** Whether this goal is about getting stronger (it feeds the PROGRESS need that pushes grinding). */
        public boolean progression() {
            return this == JOB_ADVANCE || this == WEAPON_UPGRADE || this == LEVEL || this == FOURTH_JOB
                    || this == ZHELM || this == FARM_ITEM;
        }
    }
    /* {@code itemId}: the item a FARM_ITEM goal farms (0 for every other kind). */
    public record Goal(Kind kind, int targetLevel, String description, double pressure, int itemId) {
        public Goal(Kind kind, int targetLevel, String description, double pressure) {
            this(kind, targetLevel, description, pressure, 0);
        }

        public Goal {
            if (targetLevel < 1 || !Double.isFinite(pressure) || pressure < 0 || pressure > 1) {
                throw new IllegalArgumentException("invalid goal");
            }
            java.util.Objects.requireNonNull(kind);
            java.util.Objects.requireNonNull(description);
        }
    }

    /**
     * Ticket 162 part 2: the drop the gear-first map choice wants most (GearFirstAdvisor.want), with how much: the
     * DPS fraction it adds discounted by how likely it drops within two hours. Null when the switch is off or
     * nothing in reach is worth chasing.
     */
    public record FarmWant(int itemId, String name, double desirability) {
    }

    /** A Zakum Helmet (v83 item 1002357), the 2009 flex. */
    public static final int ZHELM = 1002357;
    /** "Rich": ten million mesos carried. */
    public static final long WEALTH_TARGET = 10_000_000L;
    /** An aspiration needs at least this much pull from the bot's Bartle blend. */
    static final double ASPIRATION_FLOOR = .18;

    /**
     * What the goal seeding and the planner read from the game, memory-only. {@code social} is the bot's effective
     * point on Bartle's chart (null: no aspirations, the pre-S8 goals only).
     */
    public record Facts(int level, int jobId, OptionalInt weaponRequiredLevel, long meso, boolean inGuild,
                        boolean married, boolean hasZhelm, SocialProfile social, FarmWant farm) {
        public Facts {
            java.util.Objects.requireNonNull(weaponRequiredLevel);
        }

        public Facts(int level, int jobId, OptionalInt weaponRequiredLevel, long meso, boolean inGuild,
                     boolean married, boolean hasZhelm, SocialProfile social) {
            this(level, jobId, weaponRequiredLevel, meso, inGuild, married, hasZhelm, social, null);
        }

        public static Facts basic(int level, int jobId, OptionalInt weaponRequiredLevel) {
            return new Facts(level, jobId, weaponRequiredLevel, 0, false, false, false, null);
        }
    }

    /** Unknown weapon data stays absent. Recognise v83 Explorer/Cygnus/Aran tiers, not custom fifth jobs or Evan. */
    public static List<Goal> seed(int level, int jobId, OptionalInt weaponRequiredLevel) {
        if (level < 1 || level > 200 || jobId < 0 || weaponRequiredLevel.isPresent()
                && weaponRequiredLevel.getAsInt() < 0) {
            throw new IllegalArgumentException("invalid character facts");
        }
        List<Goal> goals = new ArrayList<>();
        int advance = nextAdvance(jobId);
        if (advance > 0) {
            goals.add(new Goal(Kind.JOB_ADVANCE, advance, "next job advance at level " + advance,
                    Math.min(1, level / (double) advance)));
        }
        if (weaponRequiredLevel.isPresent()) {
            int target = Math.max(1, level / 10 * 10);
            int gap = target - weaponRequiredLevel.getAsInt();
            if (gap >= 10) {
                goals.add(new Goal(Kind.WEAPON_UPGRADE, target, "weapon requiring level " + target + " or higher",
                        Math.min(1, gap / 30.0)));
            }
        }
        int cap = jobId >= 1000 && jobId < 2000 ? 120 : 200;
        if (goals.isEmpty() && level < cap) {
            goals.add(new Goal(Kind.LEVEL, level + 1, "reach level " + (level + 1), .25));
        }
        return List.copyOf(goals);
    }

    /**
     * 1.6.0 S8: the progression goal first (the 3rd job's advance at 120 as {@link Kind#FOURTH_JOB}), then one life
     * aspiration picked by the Bartle blend when the bot has one, else the second progression goal. At most two.
     */
    public static List<Goal> seed(Facts facts) {
        List<Goal> base = seed(facts.level(), facts.jobId(), facts.weaponRequiredLevel());
        List<Goal> out = new ArrayList<>();
        if (!base.isEmpty()) out.add(fourthJob(base.getFirst(), facts.jobId()));
        Goal aspiration = aspiration(facts);
        if (aspiration != null) out.add(aspiration);
        else if (base.size() > 1) out.add(fourthJob(base.get(1), facts.jobId()));
        return List.copyOf(out);
    }

    /** Whether this goal is met by these facts. */
    public static boolean met(Goal goal, Facts facts) {
        return switch (goal.kind()) {
            case MARRIAGE -> facts.married();
            case GUILD -> facts.inGuild();
            case ZHELM -> facts.hasZhelm();
            case WEALTH -> facts.meso() >= WEALTH_TARGET;
            // Done once the item is no longer the bot's want (it got one, or something better came along).
            case FARM_ITEM -> facts.farm() == null || facts.farm().itemId() != goal.itemId();
            case LEVEL -> facts.level() >= goal.targetLevel();
            case WEAPON_UPGRADE -> facts.weaponRequiredLevel().isPresent()
                    && facts.weaponRequiredLevel().getAsInt() >= goal.targetLevel();
            case JOB_ADVANCE, FOURTH_JOB -> nextAdvance(facts.jobId()) != goal.targetLevel();
            case QUEST -> false; // the bot's quest log says when it is done (LifeMemory.refresh)
        };
    }

    /** Pressure of a quest goal: a side goal, below a job advance at full pull. */
    public static final double QUEST_PRESSURE = .5;

    /**
     * Ticket 158: the goal for the bot's active quest (from {@code BotQuests}). It comes on top of the two seeded
     * goals while the quest runs, so a quest never pushes out a job advance or an aspiration. The target is the quest
     * id (the plan is re-made when the quest changes); the description names the quest.
     */
    public static Goal quest(int questId, String name) {
        return new Goal(Kind.QUEST, Math.max(1, questId), "finish the quest " + (name == null || name.isBlank()
                ? "#" + questId : name), QUEST_PRESSURE);
    }

    static Goal fourthJob(Goal goal, int jobId) {
        if (goal.kind() != Kind.JOB_ADVANCE || goal.targetLevel() != 120 || jobId >= 1000 && jobId < 2000) return goal;
        return new Goal(Kind.FOURTH_JOB, 120, "4th job at level 120", goal.pressure());
    }

    /** The life aspiration this blend pulls toward hardest, or null (too young, already done, or no strong pull). */
    static Goal aspiration(Facts f) {
        SocialProfile social = f.social();
        if (social == null) return null;
        double a = social.share(SocialProfile.Type.ACHIEVER), e = social.share(SocialProfile.Type.EXPLORER);
        double s = social.share(SocialProfile.Type.SOCIALIZER), k = social.share(SocialProfile.Type.KILLER);
        Kind best = null;
        double pull = ASPIRATION_FLOOR;
        double[] pulls = {
                !f.married() && f.level() >= 30 ? 1.2 * s : 0,                         // MARRIAGE
                !f.inGuild() && f.level() >= 20 ? .6 * s + .8 * k : 0,                  // GUILD
                !f.hasZhelm() && f.level() >= 40 && f.level() < 150 ? a + .4 * k : 0,   // ZHELM
                f.meso() < WEALTH_TARGET && f.level() >= 25 ? .9 * e + .3 * a : 0};     // WEALTH
        // FARM_ITEM: a gear-hungry bot (the want's need on GearFirstPlanner's scale), Achievers and Killers most.
        double need = f.farm() == null ? 0 : Math.min(1, f.farm().desirability() / .30);
        pulls = java.util.Arrays.copyOf(pulls, 5);
        pulls[4] = need * (.5 + .5 * a + .3 * k);
        Kind[] kinds = {Kind.MARRIAGE, Kind.GUILD, Kind.ZHELM, Kind.WEALTH, Kind.FARM_ITEM};
        for (int i = 0; i < kinds.length; i++) {
            if (pulls[i] > pull) { pull = pulls[i]; best = kinds[i]; }
        }
        if (best == null) return null;
        double pressure = Math.min(1, Math.max(0, pull - .1));
        return switch (best) {
            case MARRIAGE -> new Goal(Kind.MARRIAGE, f.level(), "get married", pressure);
            case GUILD -> new Goal(Kind.GUILD, f.level(), "join a guild", pressure);
            case ZHELM -> new Goal(Kind.ZHELM, Math.max(50, f.level()), "get a Zakum Helmet", pressure);
            case FARM_ITEM -> new Goal(Kind.FARM_ITEM, f.level(), "farm a " + f.farm().name(), pressure,
                    f.farm().itemId());
            default -> new Goal(Kind.WEALTH, f.level(), "save up 10m mesos", pressure);
        };
    }

    /** The level of this job's next advance (10/30/70/120), or 0 when there is none. */
    public static int nextAdvance(int job) {
        if (job == 0 || job == 1000 || job == 2000) return 10;
        boolean supported = job >= 100 && job <= 532 || job >= 1100 && job <= 1512
                || job == 2100 || job == 2110 || job == 2111 || job == 2112;
        if (!supported) return 0;
        int suffix = job % 100;
        if (suffix == 0) return 30;
        if (suffix == 10 || suffix == 20 || suffix == 30) return 70;
        if (suffix == 11 || suffix == 21 || suffix == 31) return 120;
        return 0;
    }
}
