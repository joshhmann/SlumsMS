package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import client.Skill;
import client.SkillFactory;
import server.StatEffect;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/*
 * Ticket 02: what a movement skill (Teleport, Flash Jump) costs a bot, paid the way a player pays it. A real client
 * sends the cast as a skill-use request and the server takes the skill's WZ mpCon at the caster's level
 * (SpecialMoveHandler -> StatEffect.applyTo); nothing else is broadcast for these two, observers see the move from the
 * movement packet alone. So a bot pays the same MP (BotVitals.mpCost, with Element Amplification) through the same
 * real-MP pool as its attacks (ticket 08), drinking an MP potion like an auto-pot when it is short, and honours a
 * skill cooldown if the skill data has one (MapleRoot's Teleport and Flash Jump have none).
 *
 * Thrift: a bot with no MP potions keeps the last 30% of its MP (the auto-pot line) for fighting, so a dry mage stops
 * blinking and walks instead of draining itself.
 */
public final class NavSkillCast {
    private NavSkillCast() {
    }

    /* Where the numbers come from; tests swap it. */
    interface Data {
        int mpCost(Character bot, int skillId, int level);

        int cooldownMs(int skillId, int level);

        int mpPotions(Character bot);
    }

    static final Data SERVER = new Data() {
        @Override
        public int mpCost(Character bot, int skillId, int level) {
            try {
                return BotVitals.mpCost(bot, skillId, level);
            } catch (RuntimeException | LinkageError unavailable) {
                return 0;
            }
        }

        @Override
        public int cooldownMs(int skillId, int level) {
            try {
                Skill skill = SkillFactory.getSkill(skillId);
                StatEffect effect = skill != null ? skill.getEffect(level) : null;
                return effect != null ? Math.max(0, effect.getCooldown()) * 1000 : 0;
            } catch (RuntimeException | LinkageError unavailable) {
                return 0;
            }
        }

        @Override
        public int mpPotions(Character bot) {
            try {
                return BotVitals.potionCount(bot, false);
            } catch (RuntimeException | LinkageError unavailable) {
                return 0;
            }
        }
    };

    static volatile Data data = SERVER;

    static final double DRY_RESERVE = 0.30; // with no MP potions, keep this share of max MP for fighting
    static final long REFUSED_BACKOFF_MS = 3000;

    private static final Map<Long, Long> readyAt = new ConcurrentHashMap<>();
    static final AtomicLong teleports = new AtomicLong();
    static final AtomicLong flashJumps = new AtomicLong();
    static final AtomicLong mpSpent = new AtomicLong();
    static final AtomicLong refused = new AtomicLong();

    /* What one cast of this move costs this bot in MP (0 when it holds no skill for it). */
    static int mpCost(Character bot, BotNavigationGraph.EdgeType type) {
        int skill = NavMovementSkills.skillFor(bot, type);
        return skill == 0 ? 0 : data.mpCost(bot, skill, bot.getSkillLevel(skill));
    }

    /* Whether the bot could pay for this move now: off cooldown, and the MP or a potion to drink for it. */
    static boolean affordable(Character bot, BotNavigationGraph.EdgeType type) {
        int skill = NavMovementSkills.skillFor(bot, type);
        if (skill == 0) {
            return false;
        }
        Long ready = readyAt.get(key(bot, skill));
        if (ready != null && System.currentTimeMillis() < ready) {
            return false;
        }
        int cost = data.mpCost(bot, skill, bot.getSkillLevel(skill));
        if (cost <= 0) {
            return true;
        }
        int mp = bot.getMp();
        if (mp >= cost + (int) (bot.getCurrentMaxMp() * DRY_RESERVE)) {
            return true;
        }
        return data.mpPotions(bot) > 0; // short or under the reserve: fine while there is a potion to drink
    }

    /*
     * Pays for one cast: drinks if it must, takes the MP, starts the cooldown. False (nothing paid) when the bot
     * cannot: the caller walks instead.
     */
    static boolean pay(Character bot, BotNavigationGraph.EdgeType type) {
        if (!affordable(bot, type)) {
            refused.incrementAndGet();
            return false;
        }
        int skill = NavMovementSkills.skillFor(bot, type);
        int level = bot.getSkillLevel(skill);
        int cost = data.mpCost(bot, skill, level);
        if (cost > 0) {
            if (!BotVitals.affordMp(bot, cost)) {
                // The potion it counted on did not cover it: walk for a few seconds rather than replan into the
                // same cast every tick.
                readyAt.put(key(bot, skill), System.currentTimeMillis() + REFUSED_BACKOFF_MS);
                refused.incrementAndGet();
                return false;
            }
            BotVitals.spend(bot, cost, 0);
            mpSpent.addAndGet(cost);
        }
        int cooldown = data.cooldownMs(skill, level);
        if (cooldown > 0) {
            readyAt.put(key(bot, skill), System.currentTimeMillis() + cooldown);
        }
        (type == BotNavigationGraph.EdgeType.TELEPORT ? teleports : flashJumps).incrementAndGet();
        return true;
    }

    /* One line for !gcmove learned and the nav lab. */
    public static String describe() {
        return "movement skills: " + teleports.get() + " teleports, " + flashJumps.get() + " flash jumps, "
                + mpSpent.get() + " MP spent, " + refused.get() + " refused (MP or cooldown)";
    }

    static void resetCounters() {
        teleports.set(0);
        flashJumps.set(0);
        mpSpent.set(0);
        refused.set(0);
        readyAt.clear();
    }

    private static long key(Character bot, int skill) {
        return ((long) bot.getId() << 32) ^ skill;
    }
}
