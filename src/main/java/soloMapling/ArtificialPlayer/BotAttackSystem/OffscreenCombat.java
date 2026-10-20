package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import client.inventory.WeaponType;
import server.life.LifeFactory;
import server.life.Monster;
import server.life.MonsterStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotAmmo;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotSupplies;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Real bots 07: the offscreen grind's kill rate and EXP from the same numbers an observed bot hits with. A bot
 * grinding where no player looks swings the attacks it would swing on screen (BotAttackConfig, its own skills and
 * levels) at the map's representative mob (its median-level one), through BotDamage's averaged swing: so many
 * swings to kill, at the profile's swing cooldown plus a walk between kills, never more than the old abstract
 * 30 a minute (what a map's spawns support). Each kill is worth the mob's EXP times the bot's EXP rate, the same
 * as a kill on screen gives a soloing bot, scaled by the owner's offscreen EXP pace (a third, see expPace).
 *
 * Cheap by design: one estimate per bot, kept until its level, job, map or attack totals change.
 */
public final class OffscreenCombat {

    private OffscreenCombat() {}

    private static final Logger log = LoggerFactory.getLogger(OffscreenCombat.class);

    // The ceiling: a map's spawns keep a grinder busy at most this fast (the old abstract rate).
    static final double MAX_KILLS_PER_MIN = 30.0;
    // Walking to the next mob or pack between kills.
    static final double SECONDS_BETWEEN_KILLS = 1.5;
    // The mobs an AoE swing catches in a typical pack.
    static final int PACK_SIZE = 3;

    // Owner's decision (2026-10-19): offscreen bots level at about a third of the real-EXP pace, "so bots climb
    // more like a casual player and the world stays spread across levels longer". Only the EXP a kill is worth
    // offscreen is scaled - the kill rate (and its cap) is the real one, and observed kills give real EXP.
    // Override with -Dsolomapling.offscreenExpPace=<factor> (a positive number; 1 is the full real pace).
    static final double DEFAULT_OFFSCREEN_EXP_PACE = 1.0 / 3.0;
    static final String OFFSCREEN_EXP_PACE_PROPERTY = "solomapling.offscreenExpPace";

    /*
     * The offscreen grind's pace: kills a minute, EXP per kill, and the level of the mob it was worked out on; and,
     * for what the grind uses up (real bots 08, OffscreenSupplies), the swings a minute it takes, the attack swung
     * (skill, the level it is cast at, its route) and the mob's physical attack.
     */
    public record Rate(double killsPerMinute, double expPerKill, int mobLevel, double swingsPerMinute, int skillId,
                       int skillLevel, BotAttackProfile.Route route, int mobAttack) {
        public Rate(double killsPerMinute, double expPerKill, int mobLevel) {
            this(killsPerMinute, expPerKill, mobLevel, 0, 0, 0, BotAttackProfile.Route.CLOSE, 0);
        }

        public double expPerSecond() {
            return killsPerMinute / 60.0 * expPerKill;
        }
    }

    // One attack's offscreen pace, and what was swung to get it.
    private record Pace(double kills, double swings, int skillId, int skillLevel, BotAttackProfile.Route route) {
        static final Pace NONE = new Pace(0, 0, 0, 0, BotAttackProfile.Route.CLOSE);
    }

    private record Cached(int key, Rate rate) {
    }

    private static final Map<Integer, Cached> byBot = new ConcurrentHashMap<>();
    private static final Map<Integer, MonsterStats> representative = new ConcurrentHashMap<>();
    private static final MonsterStats NO_MOB = new MonsterStats();

    /* The bot's offscreen pace on this map, or null when the map has no mob to grind or no attack fits. */
    public static Rate rate(Character bot, int mapId) {
        MonsterStats mob = representativeMob(mapId);
        if (bot == null || mob == null) {
            return null;
        }
        WeaponType weapon = BotAttack.resolveEquippedWeaponType(bot);
        BotAmmo.syncStats(bot, weapon);   // the totals count the ammo it carries now (real bots 08)
        int key = Objects.hash(mapId, bot.getLevel(), bot.getJob(), BotAttackDriver.equippedWeaponId(bot),
                BotSupplies.outOfAmmo(bot), bot.getTotalWatk(), bot.getTotalMagic(), bot.getTotalStr(), bot.getTotalDex(), bot.getTotalInt(),
                bot.getTotalLuk(), bot.getExpRate());
        Cached cached = byBot.get(bot.getId());
        if (cached != null && cached.key() == key) {
            return cached.rate();
        }
        Rate rate = estimate(bot, mob, MapMobIndex.mobCount(mapId),
                bot.getMap() != null ? bot.getMap().getExpRateMultiplier() : 1);
        byBot.put(bot.getId(), new Cached(key, rate));
        if (log.isDebugEnabled()) {
            log.debug("Offscreen pace: bot {} ({}) lv {} {} on map {} vs a lv {} mob ({} HP): {} kills/min, {} EXP a kill",
                    bot.getId(), bot.getName(), bot.getLevel(), bot.getJob(), mapId, mob.getLevel(), mob.getHp(),
                    String.format("%.1f", rate.killsPerMinute()), Math.round(rate.expPerKill()));
        }
        return rate;
    }

    public static void clearBot(int botId) {
        byBot.remove(botId);
    }

    static Rate estimate(Character bot, MonsterStats mob, int mobsOnMap, int mapExpMultiplier) {
        WeaponType weapon = BotAttack.resolveEquippedWeaponType(bot);
        BotAttackConfig.JobAttacks attacks = BotAttackConfig.resolve(bot.getJob(), weapon,
                BotAttackDriver.ownedSkills(bot));
        long hp = mob.getHp() != null ? Math.max(1, mob.getHp()) : 1;
        Pace best = Pace.NONE;
        if (attacks.single() != null) {
            best = pace(bot, withAmmo(bot, attacks.single(), weapon), weapon, mob, hp, 1);
        }
        if (attacks.aoe() != null && mobsOnMap >= 2) {
            BotAttackProfile aoe = withAmmo(bot, attacks.aoe(), weapon);
            int caught = Math.max(1, Math.min(Math.min(aoe.numAttacked, PACK_SIZE), mobsOnMap));
            Pace pack = pace(bot, aoe, weapon, mob, hp, caught);
            if (pack.kills() > best.kills()) {
                best = pack;
            }
        }
        return new Rate(best.kills(), expPerKill(mob.getExp(), bot.getExpRate(), mapExpMultiplier), mob.getLevel(),
                best.swings(), best.skillId(), best.skillLevel(), best.route(), mob.getPADamage());
    }

    /* An offscreen kill's EXP: the mob's EXP x the bot's EXP rate x the map multiplier, at the offscreen pace. */
    static double expPerKill(int mobExp, int expRate, int mapExpMultiplier) {
        return (double) mobExp * expRate * Math.max(1, mapExpMultiplier) * expPace();
    }

    /* The offscreen EXP pace factor: the system property when it is a positive number, else a third. */
    static double expPace() {
        String configured = System.getProperty(OFFSCREEN_EXP_PACE_PROPERTY);
        if (configured != null) {
            try {
                double pace = Double.parseDouble(configured.trim());
                if (pace > 0 && Double.isFinite(pace)) {
                    return pace;
                }
            } catch (NumberFormatException ignored) {
                // falls through to the default
            }
            log.warn("Ignoring -D{}={}: not a positive number", OFFSCREEN_EXP_PACE_PROPERTY, configured);
        }
        return DEFAULT_OFFSCREEN_EXP_PACE;
    }

    /*
     * What the bot would swing for this attack with the ammo it holds (real bots 08, as SwingSupply decides on
     * screen): a plain shot when it has too few for the skill, its ranged weapon in melee when it has none.
     */
    private static BotAttackProfile withAmmo(Character bot, BotAttackProfile profile, WeaponType weapon) {
        if (profile.route != BotAttackProfile.Route.RANGED || !BotAmmo.needsAmmo(bot, weapon, profile.skillId)) {
            return profile;
        }
        if (BotAmmo.usable(bot, weapon, profile.skillId, packetLevel(bot, profile)) != null) {
            return profile;
        }
        return BotAmmo.usable(bot, weapon, 0, 0) != null ? BotAttackProfile.ranged(0, 1) : BotAttackProfile.basicSwing();
    }

    private static int packetLevel(Character bot, BotAttackProfile profile) {
        return BotAttackDriver.packetLevel(bot, profile);
    }

    private static Pace pace(Character bot, BotAttackProfile profile, WeaponType weapon,
                             MonsterStats mob, long hp, int mobsPerSwing) {
        int skillId = profile.skillId;
        int skillLevel = BotAttackDriver.packetLevel(bot, profile);
        BotAttackProfile.Route route = profile.route;
        if (!BotSkillActions.weaponFits(skillId, BotAttackDriver.equippedWeaponId(bot))) {
            skillId = 0;   // what the driver draws instead
            skillLevel = 0;
            route = BotAttackProfile.Route.CLOSE;
        }
        int lines = BotAttackDriver.shadowDoubled(bot, profile.numDamage);
        BotDamage.Swing swing = BotDamage.swing(bot, skillId, skillLevel, route, weapon, mobsPerSwing + 1,
                lines != profile.numDamage);
        double perMob = swing.expectedPerMob(mob, lines);
        if (log.isDebugEnabled()) {
            log.debug("Offscreen swing: bot {} lv {} {} skill {} lv {} x{} lines on {} mob(s): line {}-{} (crit {}% x{}), {} a mob",
                    bot.getId(), bot.getLevel(), bot.getJob(), skillId, skillLevel, lines, mobsPerSwing,
                    swing.base().min(), swing.base().max(), Math.round(swing.crit().chance() * 100),
                    String.format("%.2f", swing.crit().factor()), Math.round(perMob));
        }
        double kills = killsPerMinute(perMob, hp, profile.cooldownMs, mobsPerSwing);
        return new Pace(kills, swingsPerMinute(kills, perMob, hp, mobsPerSwing), skillId, skillLevel, route);
    }

    /* The swings a minute behind a kill rate: each pack of mobsPerSwing takes ceil(hp / perSwing) swings. */
    static double swingsPerMinute(double killsPerMinute, double perSwing, long hp, int mobsPerSwing) {
        if (perSwing <= 0 || killsPerMinute <= 0) {
            return 0;
        }
        return killsPerMinute / Math.max(1, mobsPerSwing) * Math.ceil(hp / perSwing);
    }

    /* Kills a minute for a swing that takes perSwing off each of mobsPerSwing mobs every cooldownMs. */
    static double killsPerMinute(double perSwing, long hp, int cooldownMs, int mobsPerSwing) {
        if (perSwing <= 0) {
            return 0;
        }
        double swingsToKill = Math.ceil(hp / perSwing);
        double seconds = swingsToKill * Math.max(1, cooldownMs) / 1000.0 + SECONDS_BETWEEN_KILLS;
        return Math.min(MAX_KILLS_PER_MIN, 60.0 * mobsPerSwing / seconds);
    }

    // The map's median-level mob, as MapMobIndex reports the map's level.
    private static MonsterStats representativeMob(int mapId) {
        MonsterStats stats = representative.computeIfAbsent(mapId, id -> {
            List<Integer> ids = new ArrayList<>(MapMobIndex.mobIds(id));
            List<MonsterStats> mobs = new ArrayList<>();
            for (int mobId : ids.stream().distinct().toList()) {
                try {
                    Monster m = LifeFactory.getMonster(mobId);
                    if (m != null && m.getStats() != null && !m.getStats().isBoss()) {
                        mobs.add(m.getStats());
                    }
                } catch (RuntimeException e) {
                    // a mob the data cannot build is skipped
                }
            }
            if (mobs.isEmpty()) {
                return NO_MOB;
            }
            mobs.sort(Comparator.comparingInt(MonsterStats::getLevel));
            return mobs.get(mobs.size() / 2);
        });
        return stats == NO_MOB ? null : stats;
    }
}
