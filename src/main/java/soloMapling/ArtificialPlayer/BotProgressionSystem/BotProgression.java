package soloMapling.ArtificialPlayer.BotProgressionSystem;

import client.Character;
import client.Character.SkillEntry;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.WeaponType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotWeapons;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGear;
import soloMapling.ArtificialPlayer.BotHoldings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies {@link BotBuild} to live bots (real-bots tickets 05/06). This is the only code that changes a bot's base
 * stats, unspent points, skills or job by its build:
 * <ul>
 *   <li>{@link #buildNew} - a newly minted bot gets the whole build of its job and level;</li>
 *   <li>{@link #restore} - a stored bot gets its stored build back, or, when it has none yet, is back-filled once
 *       from its level and job; levels it gained without a settle in between are caught up;</li>
 *   <li>{@link #afterLevelUp} - the observed gain path: the server's level-up already granted AP/SP/HP/MP, so the
 *       bot only spends them and advances;</li>
 *   <li>{@link #afterOffscreenLevels} - the offscreen gain path: those levels came without points, so they are
 *       granted here, the way the server would have, and then spent.</li>
 * </ul>
 *
 * <p>A bot is "real" from the moment one of these settles it, and the level it was settled at is remembered: every
 * later step grants only the levels after that one, so no level is ever paid twice and none is skipped, whichever
 * path it arrived by. A bot whose job the build does not cover ({@link BotBuild#isBuildJob}) is never settled: its
 * stats, skills and job stay exactly as they are, and nothing about them is stored.
 *
 * <p>The 4th-job skill package (NPC 2084000, {@link BotSkillPlan#fourthJobPackage}) costs a player 10,000,000 mesos.
 * Bots hold real mesos, so a bot that has them pays when the build gives it the package; one that does not still gets
 * the package and the price is not collected - charging bots that cannot pay belongs to the economy work (real-bots
 * ticket 15), not to the build.
 *
 * <p>Advancement sets the job directly. {@code Character.changeJob} is the player path - party and guild
 * broadcasts, job announcements, dragon spawns and the points it grants itself - and none of it applies to a bot;
 * the build grants the advancement's points instead.
 */
public final class BotProgression {

    private static final Logger log = LoggerFactory.getLogger(BotProgression.class);

    /** Character id to the level its build was last settled at; absent for a bot that is not real. */
    private static final Map<Integer, Integer> settledLevel = new ConcurrentHashMap<>();

    private static volatile BotBuild.SkillCatalog catalog = BotProgression::serverMaxLevel;

    private BotProgression() {
    }

    /** True when this bot's build is kept by this class (and therefore stored). */
    public static boolean isReal(int charId) {
        return settledLevel.containsKey(charId);
    }

    /** The level this bot's build was last settled at, or -1 when it is not real. */
    public static int settledLevel(int charId) {
        return settledLevel.getOrDefault(charId, -1);
    }

    /** Forgets a bot (it left the world for good, or a test is done with it). */
    public static void forget(int charId) {
        settledLevel.remove(charId);
    }

    /** Test seam: the skill catalog, or back to the server's with {@code null}. */
    static void useCatalogForTest(BotBuild.SkillCatalog testCatalog) {
        catalog = testCatalog == null ? BotProgression::serverMaxLevel : testCatalog;
    }

    /**
     * The server's maximum level of a skill, or 0 when the server does not have it. Before the skill data is loaded
     * (tools, tests) the plan's own targets stand.
     */
    private static int serverMaxLevel(int skillId) {
        if (!SkillFactory.isLoaded()) {
            return Integer.MAX_VALUE;
        }
        Skill skill = SkillFactory.getSkill(skillId);
        return skill == null ? 0 : skill.getMaxLevel();
    }

    // ---------------------------------------------------------------- entry points

    /**
     * Gives a newly minted bot (or a stored one with no build yet) the whole build of its job and level. Returns
     * false, changing nothing, for a job the build does not cover.
     */
    public static boolean buildNew(Character bot) {
        synchronized (bot) {
            int job = bot.getJob().getId();
            if (!BotBuild.isBuildJob(job)) {
                settledLevel.remove(bot.getId());
                return false;
            }
            BotBuild.State built = BotBuild.plan(job, bot.getLevel(), choiceFor(bot), catalog);
            apply(bot, built, true);
            payForPackages(bot, built);
            settledLevel.put(bot.getId(), bot.getLevel());
            return true;
        }
    }

    /**
     * Restores a stored bot's build after its row was applied: the stored build when there is one, the back-filled
     * one otherwise, then any levels the row gained after the build was last settled. A bot whose job the build does
     * not cover keeps whatever it has and is not made real.
     */
    public static void restore(Character bot, BotRealStats stored) {
        synchronized (bot) {
            int job = bot.getJob().getId();
            if (!BotBuild.isBuildJob(job)) {
                settledLevel.remove(bot.getId());
                if (stored != null) {
                    log.warn("Bot {} ({}) has a stored build but the build does not cover job {}; leaving it untouched",
                            bot.getId(), bot.getName(), job);
                }
                return;
            }
            if (stored == null) {
                buildNew(bot);   // back-fill: written with the bot's next save
                return;
            }
            BotBuild.State state = fromStored(job, bot.getLevel(), stored);
            apply(bot, state, false);
            bot.updateHpMp(soloMapling.ArtificialPlayer.BotStore.restoredHp(stored.hp(), Math.max(stored.maxHp(),
                    bot.getCurrentMaxHp())), stored.mp());
            if (bot.getLevel() < stored.buildLevel()) {
                // The row's level went down (re-rolled while it was saved): its build is for a level it no longer
                // has, so it is rebuilt rather than kept.
                buildNew(bot);
                return;
            }
            settledLevel.put(bot.getId(), stored.buildLevel());
            progress(bot, bot.getLevel());
        }
    }

    /**
     * The observed gain path: the server's level-up ran for every level above {@code levelBefore} and granted its
     * points, so those are only spent (and the job advanced) here.
     */
    public static void afterLevelUp(Character bot, int levelBefore) {
        int jobBefore = bot.getJob().getId();
        progress(bot, levelBefore);
        soloMapling.site.SiteFeed.jobChanged(bot, jobBefore, bot.getJob().getId()); // 1.6.0 Track D: website feed
        if (bot.getJob().getId() != jobBefore) soloMapling.achievements.Achievements.onJob(bot, bot.getJob().getId()); // 1.6.1 ticket 93
        BotGear.afterProgress(bot);   // real bots 10: gear it can wear now
    }

    /** The offscreen gain path: the levels above {@code levelBefore} came without any points; grant and spend them. */
    public static void afterOffscreenLevels(Character bot, int levelBefore) {
        int jobBefore = bot.getJob().getId();
        progress(bot, bot.getLevel());
        soloMapling.site.SiteFeed.jobChanged(bot, jobBefore, bot.getJob().getId()); // 1.6.0 Track D: website feed
        if (bot.getJob().getId() != jobBefore) soloMapling.achievements.Achievements.onJob(bot, bot.getJob().getId()); // 1.6.1 ticket 93
        BotGear.afterProgress(bot);
    }

    /**
     * Settles a real bot at its current level. Levels after the last settled one up to {@code ungrantedTo} had no
     * points from the server and get the build's grant; levels after {@code ungrantedTo} already had theirs.
     */
    static void progress(Character bot, int ungrantedTo) {
        synchronized (bot) {
            int settled = settledLevel(bot.getId());
            if (settled < 0) {
                return;
            }
            int job = bot.getJob().getId();
            if (!BotBuild.isBuildJob(job)) {
                settledLevel.remove(bot.getId());
                return;
            }
            int level = bot.getLevel();
            if (level < settled) {
                buildNew(bot);
                return;
            }
            BotBuild.Choice choice = BotBuild.alongLineOf(job, choiceFor(bot));
            BotBuild.State state = capture(bot);
            int grantTo = Math.min(ungrantedTo, level);
            if (grantTo > settled) {
                state.level = grantTo;
                BotBuild.catchUp(state, settled, choice, catalog);
            }
            state.level = level;
            BotBuild.settle(state, choice, catalog);
            apply(bot, state, level > settled);
            payForPackages(bot, state);
            settledLevel.put(bot.getId(), level);
        }
    }

    /**
     * Charges the bot NPC 2084000's price for each package its build just gave it, when it can afford it. A bot that
     * cannot keeps the package unpaid (see the class comment).
     */
    private static void payForPackages(Character bot, BotBuild.State state) {
        for (int i = 0; i < state.packagesBought; i++) {
            if (bot.getMeso() >= BotSkillPlan.FOURTH_JOB_PACKAGE_MESOS
                    && BotHoldings.spendMeso(bot, BotSkillPlan.FOURTH_JOB_PACKAGE_MESOS)) {
                log.debug("Bot {} ({}) paid {} mesos for the 4th-job skill package", bot.getId(), bot.getName(),
                        BotSkillPlan.FOURTH_JOB_PACKAGE_MESOS);
            } else {
                log.debug("Bot {} ({}) got the 4th-job skill package unpaid ({} mesos held)", bot.getId(),
                        bot.getName(), bot.getMeso());
            }
        }
    }

    // ---------------------------------------------------------------- reading and writing a bot

    /** The bot's build as stored, or {@code null} when it is not real (nothing about it is stored then). */
    public static BotRealStats snapshot(Character bot) {
        int settled = settledLevel(bot.getId());
        if (settled < 0) {
            return null;
        }
        TreeMap<Integer, BotRealStats.SkillLevel> skills = new TreeMap<>();
        for (Map.Entry<Skill, SkillEntry> entry : bot.getSkills().entrySet()) {
            SkillEntry held = entry.getValue();
            if (entry.getKey() != null && held != null && held.skillevel > 0) {
                skills.put(entry.getKey().getId(), new BotRealStats.SkillLevel(held.skillevel, held.masterlevel));
            }
        }
        return new BotRealStats(bot.getStr(), bot.getDex(), bot.getInt(), bot.getLuk(), bot.getMaxHp(), bot.getMaxMp(),
                bot.getHp(), bot.getMp(), bot.getRemainingAp(), bot.getRemainingSps(), settled, skills);
    }

    private static BotBuild.State capture(Character bot) {
        BotBuild.State s = new BotBuild.State(bot.getJob().getId(), bot.getLevel());
        s.str = bot.getStr();
        s.dex = bot.getDex();
        s.int_ = bot.getInt();
        s.luk = bot.getLuk();
        s.maxHp = bot.getMaxHp();
        s.maxMp = bot.getMaxMp();
        s.remainingAp = bot.getRemainingAp();
        s.sp = bot.getRemainingSps()[0];
        for (Map.Entry<Skill, SkillEntry> entry : bot.getSkills().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue().skillevel > 0) {
                s.skills.put(entry.getKey().getId(), (int) entry.getValue().skillevel);
            }
        }
        return s;
    }

    private static BotBuild.State fromStored(int job, int level, BotRealStats stored) {
        BotBuild.State s = new BotBuild.State(job, level);
        s.str = stored.str();
        s.dex = stored.dex();
        s.int_ = stored.int_();
        s.luk = stored.luk();
        s.maxHp = stored.maxHp();
        s.maxMp = stored.maxMp();
        s.remainingAp = stored.remainingAp();
        s.sp = stored.sp()[0];
        stored.skills().forEach((id, held) -> s.skills.put(id, held.level()));
        return s;
    }

    /**
     * Writes a build onto the bot: job, base stats, unspent points, base pools and the skill map (replacing it
     * whole, so the clone template's skills never leak into a bot). Skills the server does not have are dropped.
     */
    private static void apply(Character bot, BotBuild.State s, boolean heal) {
        if (bot.getJob().getId() != s.job) {
            Job job = Job.getById(s.job);
            if (job != null) {
                bot.setJob(job);
                // A Cygnus Knight or pirate can advance into a job whose skills its weapon cannot use.
                BotWeapons.ensureFittingWeapon(bot);
            }
        }
        Map<Skill, SkillEntry> skills = new LinkedHashMap<>();
        int refunded = 0;
        for (Map.Entry<Integer, Integer> held : s.skills.entrySet()) {
            int id = held.getKey();
            int level = held.getValue();
            Skill skill = SkillFactory.getSkill(id);
            if (skill == null) {
                // A stored skill the server no longer has (its skill data changed): the SP comes back to the bot
                // rather than vanishing with the skill, and is spent again by the next settle.
                refunded += Math.max(0, level);
                continue;
            }
            if (level <= 0) {
                continue;
            }
            int master = BotBuild.isFourthJobSkill(id) || BotSkillPlan.isGranted(id)
                    ? Math.max(level, BotBuild.targetLevel(id / 10000, id, catalog)) : 0;
            skills.put(skill, new SkillEntry((byte) level, master, -1));
        }
        int[] sps = bot.getRemainingSps();
        sps[0] = s.sp + refunded;
        bot.applyBotBuild(s.str, s.dex, s.int_, s.luk, s.remainingAp, sps, s.maxHp, s.maxMp);
        bot.replaceBotSkills(skills);

        bot.refreshStatsAfterProgression();
        if (heal) {
            bot.updateHpMp(bot.getCurrentMaxHp(), bot.getCurrentMaxMp());
        }
    }

    // ---------------------------------------------------------------- choice

    /** How this bot picks within its class: by the weapon it carries, ties broken by its id. */
    static BotBuild.Choice choiceFor(Character bot) {
        int seed = bot.getId();
        WeaponType weapon = equippedWeapon(bot);
        return new BotBuild.Choice(hintFor(weapon, seed), seed);
    }

    private static WeaponType equippedWeapon(Character bot) {
        try {
            return BotAttack.resolveEquippedWeaponType(bot);
        } catch (RuntimeException | LinkageError e) {
            return null;   // no item data (tools, tests): the seed decides
        }
    }

    /**
     * The 2nd job a weapon points to, or 0. Swords and wands fit several branches; the seed picks one. MapleRoot's
     * warriors train swords (Fighter, Page) or spears (Spearman) only, so an axe or blunt weapon reads as a sword
     * and a pole-arm as a spear.
     */
    static int hintFor(WeaponType weapon, int seed) {
        if (weapon == null) {
            return 0;
        }
        return switch (weapon) {
            case SWORD1H, SWORD2H, GENERAL1H_SWING, GENERAL2H_SWING, GENERAL1H_STAB, GENERAL2H_STAB ->
                    Math.floorMod(seed, 2) == 0 ? 110 : 120;
            case SPEAR_STAB, SPEAR_SWING, POLE_ARM_SWING, POLE_ARM_STAB -> 130;
            case WAND, STAFF -> 210 + 10 * Math.floorMod(seed, 3);
            case BOW -> 310;
            case CROSSBOW -> 320;
            case CLAW -> 410;
            case DAGGER_THIEVES, DAGGER_OTHER -> 420;
            case KNUCKLE -> 510;
            case GUN -> 520;
            default -> 0;
        };
    }
}
