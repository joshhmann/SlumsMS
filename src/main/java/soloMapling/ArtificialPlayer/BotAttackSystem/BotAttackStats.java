package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotAmmo;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Per-bot tally of the grinding attack loop (BotAttackDriver.botAttack), written as one debug line per bot per minute:
 * how many attempts swung, with which skills, and why the rest did not. Answers "is this bot attacking, and if not,
 * why" from the server log without a client: a bot that stands in range swinging at nothing shows up as a column of
 * "out of reach" misses. Cooldown ticks are counted apart, since most ticks of a fighting bot are cooldown. The line
 * ends with what the bot holds (potions, ammo, HP/MP), so consecutive lines give its on-screen supply rates (real bots 08).
 */
final class BotAttackStats {

    private static final Logger log = LoggerFactory.getLogger(BotAttackStats.class);
    private static final long WINDOW_MS = 60_000;

    private static final class Tally {
        final long since;
        int cooldown;
        int swings;
        int kills;
        final Map<Integer, Integer> bySkill = new TreeMap<>();
        final Map<String, Integer> misses = new TreeMap<>();

        Tally(long since) {
            this.since = since;
        }
    }

    private static final Map<Integer, Tally> tallies = new ConcurrentHashMap<>();

    private BotAttackStats() {}

    static void record(Character bot, BotAttackDriver.AttackResult result) {
        if (bot != null && result != null && result.hit() && result.killed()) {
            soloMapling.autoqa.BotWatch.killed(bot.getId()); // 1.6.0 (owner 2026-10-26) bot watch; free when idle
        }
        if (bot == null || result == null || !log.isDebugEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        Tally t = tallies.computeIfAbsent(bot.getId(), id -> new Tally(now));
        synchronized (t) {
            if (result.hit()) {
                t.swings++;
                t.bySkill.merge(result.skillId(), 1, Integer::sum);
                if (result.killed()) {
                    t.kills++;
                }
            } else if ("on cooldown".equals(result.reason())) {
                t.cooldown++;
            } else {
                t.misses.merge(missKind(result.reason()), 1, Integer::sum);
            }
            if (now - t.since >= WINDOW_MS) {
                tallies.remove(bot.getId(), t);
                log.debug("Bot attacks {} ({} lv {}) over {}s: {} swings {} kills {}, misses {}, cooldown ticks {}; "
                                + "holds HP pots {} MP pots {} ammo {}, HP {}/{} MP {}/{}",
                        bot.getName(), bot.getJob(), bot.getLevel(), (now - t.since) / 1000, t.swings, t.kills,
                        t.bySkill, t.misses, t.cooldown, BotVitals.potionCount(bot, true),
                        BotVitals.potionCount(bot, false),
                        BotAmmo.held(bot, BotAttack.resolveEquippedWeaponType(bot)), bot.getHp(),
                        bot.getCurrentMaxHp(), bot.getMp(), bot.getCurrentMaxMp());
            }
        }
    }

    /* The reason without its per-attempt numbers, so like misses count together. */
    static String missKind(String reason) {
        if (reason == null) {
            return "unknown";
        }
        if (reason.startsWith("nearest")) {
            return "out of reach";
        }
        if (reason.startsWith("no targetable mobs")) {
            return "no mob near";
        }
        if (reason.startsWith("no attack for job")) {
            return "no attack";
        }
        return reason;
    }

    static void clear(int botId) {
        tallies.remove(botId);
    }
}
