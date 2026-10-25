package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import client.Character;
import client.Job;
import client.inventory.InventoryType;
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild;
import soloMapling.ArtificialPlayer.BotTier;
import soloMapling.itemPool.EquipMetadataCache;

import java.util.concurrent.ThreadLocalRandom;


public class BotDecorate {

    /**
     * Fraction of bots that get the full class-aware decoration pass.
     * The rest keep only their QuickEquip generic/classless outfit so the
     * population reads as a mix of casual and kitted-out players.
     * Tune to taste while experimenting.
     */
    public static final double FULL_DECORATION_RATE = 0.5;

    public static int calculate_min_equip_level(short level) {
        int nearestLowInterval = (((level / 10) * 10) - 10) + 1;
        return nearestLowInterval;
    }

    /** A tier for a new bot: the population's split, see {@link BotPopulationMix#TIER_S_PERCENT}. */
    public static BotTier getRandomTier() {
        return BotPopulationMix.rollTier();
    }

    /**
     * Selects an appropriate MapleStory job for a randomly rolled class at the given level.
     *
     * @param level The character's level
     * @return The job ID constant corresponding to the appropriate job
     */
    public static int selectJobByLevel(int level) {
        return selectJobForClass(rollBaseClass(), level);
    }

    /**
     * The class line of a new bot, {@link BotPopulationMix#WARRIOR}..{@link BotPopulationMix#ARAN}.
     *
     * @see BotPopulationMix#rollBaseClass()
     */
    public static int rollBaseClass() {
        return BotPopulationMix.rollBaseClass();
    }

    /**
     * The job a bot of {@code baseClass} holds at {@code level}, walked up the same ladder the bot's own build
     * advances along ({@code BotBuild.nextJob}/{@code advancementLevel}) so a spawned bot is exactly where a player
     * of that class would be: beginner below the 1st advancement (10; 8 for magicians), 1st job to 29, 2nd to 69,
     * 3rd to 119, 4th from 120. Covers explorers, Cygnus Knights (2nd job at 30, 4th at 120 as MapleRoot gives
     * them) and Aran. The 2nd-job branch is rolled once here and the bot keeps it for its whole line.
     *
     * @param baseClass one of {@code BotPopulationMix}'s class codes; anything else is an explorer warrior
     * @param level     the bot's level
     * @return the job id, always one {@code BotBuild} has a skill build for (or 0/1000/2000 for a beginner)
     */
    public static int selectJobForClass(int baseClass, int level) {
        BotBuild.Choice choice = new BotBuild.Choice(BotPopulationMix.secondJobHint(baseClass),
                ThreadLocalRandom.current().nextInt());
        int beginner = switch (baseClass) {
            case BotPopulationMix.CYGNUS -> 1000;   // NOBLESSE
            case BotPopulationMix.ARAN -> 2000;     // LEGEND
            default -> 0;                           // BEGINNER
        };
        int job = beginner;
        while (true) {
            int at = BotBuild.advancementLevel(job, choice);
            int next = BotBuild.nextJob(job, choice);
            if (at <= 0 || next < 0 || level < at) {
                return job;
            }
            job = next;
        }
    }

    /**
     * Selects a gender for a new bot: {@link BotPopulationMix#MALE_PERCENT} of them are male.
     *
     * @return 0 for male, 1 for female
     */
    public static int selectRandomGender() {
        return BotPopulationMix.rollGender();
    }

    public static void setBotVariables(Character bot) {
        setBotVariablesInRange(bot, 10, 80);
    }

    /**
     * As {@link #setBotVariables(Character)} (any class, the casual decoration mix) at a level in the range: staging
     * that asks for "a lv 60 partner" or "grinders at the map's level" without picking a class. The level comes from
     * the population's curve restricted to the range ({@link BotPopulationMix#rollLevel}), and exactly
     * {@code minLevel} when the range is one level.
     */
    public static void setBotVariablesInRange(Character bot, int minLevel, int maxLevel) {
        BotTier tier = getRandomTier();
        bot.setTier(tier);
        int level = BotPopulationMix.rollLevel(minLevel, maxLevel);
        int job = selectJobByLevel(level);
        bot.setGender(selectRandomGender());
        bot.setLevel(level);
        bot.setJob(Job.getById(job));

        BotDecorateBody.decorateBotBody(bot);

        if (EquipMetadataCache.isInitialized()) {
            // Full class-aware decoration is an in-memory cache lookup now, so it
            // runs inline at spawn. FULL_DECORATION_RATE preserves the population
            // mix of "kitted out" (full decoration) and "casual" (QuickEquip) bots.
            if (ThreadLocalRandom.current().nextDouble() < FULL_DECORATION_RATE) {
                BotDecorateEquips.decorateBotEquips(bot);
            } else {
                QuickEquip.apply(bot);
                // Safety net: if QuickEquip left the bot with no clothing (common
                // for low-level bots where the curated pool has no matching items),
                // run the full decoration so they don't walk around shirtless.
                if (!hasClothing(bot)) {
                    BotDecorateEquips.decorateBotEquips(bot);
                }
            }
        } else {
            // Cache not ready (bots spawned before environment init): quick
            // generic equip now, full decoration deferred to the queue.
            QuickEquip.apply(bot);
            if (!hasClothing(bot)
                    || ThreadLocalRandom.current().nextDouble() < FULL_DECORATION_RATE) {
                BotDecorationQueue.addBot("default", bot.getId());
            }
        }

        // The casual QuickEquip look hands out any weapon; the bot fights with one of its job's.
        BotWeapons.ensureFittingWeapon(bot);

        // NX cosmetic layer - runs on every bot regardless of which equip path
        // it took. Its own 30% base gate decides whether the bot actually gets
        // any NX pieces.
        BotDecorateNX.apply(bot);
    }

    public static void setBotVariables(Character bot, int baseClass, int minLevel, int maxLevel) {
        setBotVariables(bot, baseClass, minLevel, maxLevel, 0);
    }

    // forcedJobId > 0 pins the exact job (e.g. the GM 'trainhere hermit' test spawn); 0 = a random job
    // appropriate for the class + level. Everything else (level, gender, body, class-coherent gear, NX) is
    // identical, so a forced-job bot is decorated ONCE, correctly — no post-hoc re-decoration needed.
    public static void setBotVariables(Character bot, int baseClass, int minLevel, int maxLevel, int forcedJobId) {
        BotTier tier = getRandomTier();
        bot.setTier(tier);
        // One level drawn from the population's curve inside the range; an exact-level spawn pins both ends.
        int level = BotPopulationMix.rollLevel(minLevel, maxLevel);
        int job = (forcedJobId > 0) ? forcedJobId : selectJobForClass(baseClass, level);
        bot.setGender(selectRandomGender());
        bot.setLevel(level);
        bot.setJob(Job.getById(job));

        BotDecorateBody.decorateBotBody(bot);

        // Level 1-9 beginners get curated starter gear and nothing else (no
        // class-aware pass, no deferred queue, no NX overlay) so they read as
        // plain newbies. Uses explicit item ids, so the cache state doesn't matter.
        if (BeginnerEquip.isBeginner(bot)) {
            BeginnerEquip.apply(bot);
            return;
        }

        if (EquipMetadataCache.isInitialized()) {
            BotDecorateEquips.decorateBotEquips(bot);
        } else {
            // Cache not ready (env still booting): dress generically now and defer
            // the full class-aware pass - same fallback the random path uses.
            QuickEquip.apply(bot);
            BotDecorationQueue.addBot("default", bot.getId());
        }

        // The class pass picks a weapon from the job's kinds; make sure one landed (a gap in the item pool would
        // leave the bot bare-handed) and that a two-handed build is not still carrying a shield.
        BotWeapons.ensureFittingWeapon(bot);

        BotDecorateNX.apply(bot);
    }

    private static boolean hasClothing(Character bot) {
        return bot.getInventory(InventoryType.EQUIPPED).getItem((short) -5) != null  // coat/overall
                || bot.getInventory(InventoryType.EQUIPPED).getItem((short) -6) != null; // pants
    }

    /**
     * Convenience method: add a bot to the deferred decoration queue under a specific category.
     * Call this after spawn if you want a category other than "default" (e.g. "fm", "henesys").
     */
    public static void addBotToDecorationQueue(String category, int botId) {
        BotDecorationQueue.addBot(category, botId);
    }
}
