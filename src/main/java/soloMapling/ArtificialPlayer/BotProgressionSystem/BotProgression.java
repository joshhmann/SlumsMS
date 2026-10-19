package soloMapling.ArtificialPlayer.BotProgressionSystem;

import client.Character;
import client.Character.SkillEntry;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.WeaponType;
import constants.skills.Warrior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;

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
 * path it arrived by. A bot whose job is not a stock job ({@link BotBuild#isStockJob}) is never settled: its stats,
 * skills and job stay exactly as they are, and nothing about them is stored.
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
     * (tools, tests) the plan's own stock targets stand.
     */
    private static int serverMaxLevel(int skillId) {
        if (SkillFactory.getSkill(Warrior.POWER_STRIKE) == null) {
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
            if (!BotBuild.isStockJob(job)) {
                settledLevel.remove(bot.getId());
                return false;
            }
            BotBuild.State built = BotBuild.plan(job, bot.getLevel(), choiceFor(bot), catalog);
            apply(bot, built, true);
            settledLevel.put(bot.getId(), bot.getLevel());
            return true;
        }
    }

    /**
     * Restores a stored bot's build after its row was applied: the stored build when there is one, the back-filled
     * one otherwise, then any levels the row gained after the build was last settled. A bot whose job is not a stock
     * job keeps whatever it has and is not made real.
     */
    public static void restore(Character bot, BotRealStats stored) {
        synchronized (bot) {
            int job = bot.getJob().getId();
            if (!BotBuild.isStockJob(job)) {
                settledLevel.remove(bot.getId());
                if (stored != null) {
                    log.warn("Bot {} ({}) has a stored build but job {} is not a stock job; leaving it untouched",
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
            bot.updateHpMp(Math.max(1, stored.hp()), stored.mp());
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
        progress(bot, levelBefore);
    }

    /** The offscreen gain path: the levels above {@code levelBefore} came without any points; grant and spend them. */
    public static void afterOffscreenLevels(Character bot, int levelBefore) {
        progress(bot, bot.getLevel());
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
            if (!BotBuild.isStockJob(job)) {
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
            settledLevel.put(bot.getId(), level);
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
            }
        }
        int[] sps = bot.getRemainingSps();
        sps[0] = s.sp;
        bot.applyBotBuild(s.str, s.dex, s.int_, s.luk, s.remainingAp, sps, s.maxHp, s.maxMp);

        Map<Skill, SkillEntry> skills = new LinkedHashMap<>();
        s.skills.forEach((id, level) -> {
            Skill skill = SkillFactory.getSkill(id);
            if (skill == null || level <= 0) {
                return;
            }
            int master = BotBuild.isFourthJobSkill(id) ? Math.max(level, BotBuild.targetLevel(id / 10000, id, catalog)) : 0;
            skills.put(skill, new SkillEntry((byte) (int) level, master, -1));
        });
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
        return new BotBuild.Choice(hintFor(weapon, seed), altWeapon(weapon), seed);
    }

    private static WeaponType equippedWeapon(Character bot) {
        try {
            return BotAttack.resolveEquippedWeaponType(bot);
        } catch (RuntimeException | LinkageError e) {
            return null;   // no item data (tools, tests): the seed decides
        }
    }

    /** The 2nd job a weapon points to, or 0. Swords and wands fit several branches; the seed picks one. */
    static int hintFor(WeaponType weapon, int seed) {
        if (weapon == null) {
            return 0;
        }
        return switch (weapon) {
            case SWORD1H, SWORD2H -> Math.floorMod(seed, 2) == 0 ? 110 : 120;
            case GENERAL1H_SWING, GENERAL2H_SWING -> 110;
            case GENERAL1H_STAB, GENERAL2H_STAB -> 120;
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

    /** Whether the weapon is the second form of a warrior pair: axe, blunt weapon or pole-arm. */
    static boolean altWeapon(WeaponType weapon) {
        if (weapon == null) {
            return false;
        }
        return switch (weapon) {
            case GENERAL1H_SWING, GENERAL2H_SWING, GENERAL1H_STAB, GENERAL2H_STAB, POLE_ARM_SWING, POLE_ARM_STAB -> true;
            default -> false;
        };
    }
}
