package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import soloMapling.ArtificialPlayer.BotTier;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The shape of the bot population: every number a newly minted roster bot is rolled from - level, tier, class and
 * gender - in one place, so the mix can be tuned without hunting through the spawn path.
 *
 * <p><b>Why.</b> The player-likeness audit (gap 6, "the population looks like a demo") found the roster rolled a
 * "for demo" tier mix (30% S, 30% A, 20% B, 10% C, 10% D), which put only 16% of the roster under Lv30 with a mass at
 * 50-80; it also found about 10 pirates and about 35 Cygnus/Aran out of 9,010 bots, and a gender split of exactly
 * 50/50 (5,077 male, 5,079 female). A 2009 GMS server looked nothing like that.
 *
 * <p><b>Targets</b> (audit scorecard: share of the roster under Lv30, of pirates + Aran, and of male bots):
 * <ul>
 *   <li>Levels: about 45% under Lv30, 30-40% between 31 and 70, about 15% at 71+ with a thin tail to 200.</li>
 *   <li>Classes: Cygnus Knights ~10% and Aran ~5% of every bot; the remaining 85% are explorers split
 *       Warrior/Magician/Bowman/Thief/Pirate 22/20/20/22/16.</li>
 *   <li>Gender: about 65% male.</li>
 * </ul>
 *
 * <p><b>Levels.</b> {@link #rollLevel} draws from {@link #LEVEL_WEIGHTS} restricted to the range it is given: a
 * training cohort keeps its band (a Victoria cohort stays in Victoria's level range) while the inside of the band is
 * shaped by the population's overall curve, so low levels are common everywhere they can happen. Cohorts that cannot
 * hold the whole curve bend the aggregate - Victoria's town fields stop at 25-30 and its dungeons (Golem's Temple,
 * the Subway, the Remains, the Drake caves) carry their own Lv30+ cohorts, the deep hubs start at 50 - which is
 * intended: with the bands in {@code EnvironmentManager.TRAINING_COHORTS} the population lands at 43% under 30, 43% at
 * 30-70 and 14% at 71+ ({@link soloMapling.ArtificialPlayer.BotDecoratorSystem.BotPopulationMixTest}).
 *
 * <p>Only bots minted after this runs are affected; a bot adopted from the store keeps the level, job and gender it
 * was born with (the store restores them), so the existing roster drifts to the new mix as bots quit and new ones
 * join.
 */
public final class BotPopulationMix {

    // ---------------------------------------------------------------- base-class codes
    // The spawn path passes one of these through BotGeneration.createBot -> BotDecorate.selectJobForClass. The
    // explorer codes match Job.getJobNiche() (1=warrior .. 5=pirate); Cygnus and Aran have no niche of their own.
    public static final int WARRIOR = 1;
    public static final int MAGICIAN = 2;
    public static final int BOWMAN = 3;
    public static final int THIEF = 4;
    public static final int PIRATE = 5;
    public static final int CYGNUS = 6;
    public static final int ARAN = 7;

    // ---------------------------------------------------------------- levels

    /** One row of the level curve: levels {@code lo}..{@code hi} hold {@code weight} of the population. */
    public record LevelWeights(int lo, int hi, double weight) {
        public boolean holds(int level) {
            return level >= lo && level <= hi;
        }
    }

    /**
     * The population's level curve, bottom-heavy as a 2009 server's was: every ten levels a little rarer, from the
     * Lv10-29 band (where a v83 server spent most of its players: Victoria Island) down to a thin tail at 100+.
     * The weights need not sum to anything; they are read as shares of their total. Tune here.
     */
    public static final List<LevelWeights> LEVEL_WEIGHTS = List.of(
            new LevelWeights(1, 9, 9),        // new characters and mush mom campers
            new LevelWeights(10, 19, 20),
            new LevelWeights(20, 29, 17),     // the bulk of Victoria Island
            new LevelWeights(30, 39, 10),     // second job
            new LevelWeights(40, 49, 8),
            new LevelWeights(50, 59, 7),      // Sleepywood, Orbis, El Nath
            new LevelWeights(60, 69, 6),
            new LevelWeights(70, 79, 6),      // third job
            new LevelWeights(80, 89, 5),
            new LevelWeights(90, 99, 4),
            new LevelWeights(100, 120, 2),    // fourth job at 120
            new LevelWeights(121, 200, 1));   // the thin tail

    // ---------------------------------------------------------------- tier, class and gender

    /**
     * The tier split of a normal population (the split this class's own comment in {@code BotDecorate} called
     * "original rates" before the demo mix replaced it): most bots are ordinary, elites are rare. The tier only
     * drives looks and gear quality, never the level.
     */
    static final int TIER_S_PERCENT = 5;
    static final int TIER_A_PERCENT = 15;
    static final int TIER_B_PERCENT = 40;
    static final int TIER_C_PERCENT = 30;
    // the rest is D

    /** Cygnus Knights and Aran as a share of every new bot (audit targets: about 10% and 5%). */
    static final int CYGNUS_PERCENT = 10;
    static final int ARAN_PERCENT = 5;

    /** Explorer classes, in the order of {@link #WARRIOR}..{@link #PIRATE}: 22/20/20/22/16 of the explorers. */
    static final int[] EXPLORER_PERCENT = {22, 20, 20, 22, 16};

    /** Share of new bots that are male (0 = male, 1 = female, as {@code Character.gender} wants it). */
    static final int MALE_PERCENT = 65;

    // 2nd-job branches, in percent, rolled once per bot and kept for its whole line. The warrior and magician
    // weights are the server's long-standing nostalgic ones (spearmen and clerics over-represented); the rest are
    // an even split because nothing in v83 favoured either branch.
    static final int[] WARRIOR_BRANCH = {110, 120, 130};   // Fighter 30, Page 26, Spearman 44
    static final int[] WARRIOR_BRANCH_PERCENT = {30, 26, 44};
    static final int[] MAGE_BRANCH = {210, 220, 230};      // F/P 26, I/L 30, Cleric 44
    static final int[] MAGE_BRANCH_PERCENT = {26, 30, 44};
    static final int[] BOWMAN_BRANCH = {310, 320};
    static final int[] BOWMAN_BRANCH_PERCENT = {50, 50};
    static final int[] THIEF_BRANCH = {410, 420};          // Assassin 55, Bandit 45
    static final int[] THIEF_BRANCH_PERCENT = {55, 45};
    static final int[] PIRATE_BRANCH = {510, 520};         // Brawler, Gunslinger
    static final int[] PIRATE_BRANCH_PERCENT = {50, 50};
    static final int[] CYGNUS_CLASSES = {1100, 1200, 1300, 1400, 1500};   // Dawn Warrior, Blaze Wizard,
                                                                         // Wind Archer, Night Walker, Thunder Breaker

    private BotPopulationMix() {
    }

    // ---------------------------------------------------------------- rolls

    /**
     * A level in {@code minLevel..maxLevel} (exactly {@code minLevel} when the range is one level or inverted),
     * drawn from {@link #LEVEL_WEIGHTS} restricted to the range. A range the curve says nothing about (no row
     * overlaps it) falls back to a flat draw.
     */
    public static int rollLevel(int minLevel, int maxLevel) {
        if (maxLevel <= minLevel) {
            return Math.max(1, minLevel);
        }
        double total = 0;
        for (int level = minLevel; level <= maxLevel; level++) {
            total += weightOf(level);
        }
        if (total <= 0) {
            return ThreadLocalRandom.current().nextInt(minLevel, maxLevel + 1);
        }
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        for (int level = minLevel; level <= maxLevel; level++) {
            roll -= weightOf(level);
            if (roll < 0) {
                return level;
            }
        }
        return maxLevel;
    }

    /** The curve's weight for one level: its row's weight spread evenly over the row's levels. */
    static double weightOf(int level) {
        for (LevelWeights row : LEVEL_WEIGHTS) {
            if (row.holds(level)) {
                return row.weight() / (row.hi() - row.lo() + 1);
            }
        }
        return 0;
    }

    /**
     * The share of a population drawn from the whole curve (no band) that is at or below {@code level}, in percent.
     * What the level curve asks for before cohorts bend it; tests and the release report read it.
     */
    public static double shareUpTo(int level) {
        double total = 0;
        double upTo = 0;
        for (LevelWeights row : LEVEL_WEIGHTS) {
            for (int lv = row.lo(); lv <= row.hi(); lv++) {
                double w = weightOf(lv);
                total += w;
                if (lv <= level) {
                    upTo += w;
                }
            }
        }
        return total <= 0 ? 0 : upTo / total * 100;
    }

    /** A tier for a new bot: mostly B and C, rarely S (see {@link #TIER_S_PERCENT}). */
    public static BotTier rollTier() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < TIER_S_PERCENT) {
            return BotTier.S;
        }
        if (roll < TIER_S_PERCENT + TIER_A_PERCENT) {
            return BotTier.A;
        }
        if (roll < TIER_S_PERCENT + TIER_A_PERCENT + TIER_B_PERCENT) {
            return BotTier.B;
        }
        if (roll < TIER_S_PERCENT + TIER_A_PERCENT + TIER_B_PERCENT + TIER_C_PERCENT) {
            return BotTier.C;
        }
        return BotTier.D;
    }

    /**
     * The class line of a new bot: one of {@link #WARRIOR}..{@link #PIRATE}, {@link #CYGNUS} or {@link #ARAN}, with
     * Cygnus Knights at {@link #CYGNUS_PERCENT} and Aran at {@link #ARAN_PERCENT} of every bot.
     */
    public static int rollBaseClass() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < CYGNUS_PERCENT) {
            return CYGNUS;
        }
        if (roll < CYGNUS_PERCENT + ARAN_PERCENT) {
            return ARAN;
        }
        int explorer = ThreadLocalRandom.current().nextInt(100);
        int seen = 0;
        for (int i = 0; i < EXPLORER_PERCENT.length; i++) {
            seen += EXPLORER_PERCENT[i];
            if (explorer < seen) {
                return i + WARRIOR;
            }
        }
        return PIRATE;
    }

    /** 0 for male, 1 for female: {@link #MALE_PERCENT} of new bots are male. */
    public static int rollGender() {
        return ThreadLocalRandom.current().nextInt(100) < MALE_PERCENT ? 0 : 1;
    }

    /**
     * The job a bot of {@code baseClass} heads for after its 2nd advancement, as the hint the build's job ladder
     * reads: a 2nd-job id for an explorer, the Cygnus class's 1st-job id for a Cygnus Knight (the ladder turns it
     * into 11x0..15x0), 0 for Aran (it has one line). Rolled once, so a bot keeps its branch for its whole life.
     */
    public static int secondJobHint(int baseClass) {
        return switch (baseClass) {
            case WARRIOR -> pick(WARRIOR_BRANCH, WARRIOR_BRANCH_PERCENT);
            case MAGICIAN -> pick(MAGE_BRANCH, MAGE_BRANCH_PERCENT);
            case BOWMAN -> pick(BOWMAN_BRANCH, BOWMAN_BRANCH_PERCENT);
            case THIEF -> pick(THIEF_BRANCH, THIEF_BRANCH_PERCENT);
            case PIRATE -> pick(PIRATE_BRANCH, PIRATE_BRANCH_PERCENT);
            case CYGNUS -> CYGNUS_CLASSES[ThreadLocalRandom.current().nextInt(CYGNUS_CLASSES.length)];
            default -> 0;
        };
    }

    private static int pick(int[] ids, int[] percent) {
        int roll = ThreadLocalRandom.current().nextInt(100);
        int seen = 0;
        for (int i = 0; i < ids.length; i++) {
            seen += percent[i];
            if (roll < seen) {
                return ids[i];
            }
        }
        return ids[ids.length - 1];
    }
}
