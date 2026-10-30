package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import soloMapling.server.MethodScheduler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Bot buff maintenance. For each of the bot's class buffs whose recast timer has elapsed,
 * broadcasts the buff visual via BotBuffEffects - no stats, MP, or registered effect, just
 * the packets that make the buff look like it happened. Recast cadence is 90% of the buff's
 * WZ duration; timer-gated, so most ticks cast nothing. Call clearBot when a bot despawns.
 */
// Bot-buff maintenance loop; cosmetic reimplementation inspired by GreenCatMS. Credit: NutNNut for the idea.
public final class BotBuffDriver {

    // botId -> (skillId -> absolute epoch-ms at which this buff may be re-shown)
    private static final Map<Integer, Map<Integer, Long>> nextCastByBot = new ConcurrentHashMap<>();

    // Recast cadence for a buff whose WZ duration is missing / non-positive.
    private static final long FALLBACK_RECAST_MS = 60_000L;

    // Gap between consecutive buffs so a multi-buff bot casts them one at a time
    // (in order) rather than all at once.
    private static final long STAGGER_MS = 750L;

    private BotBuffDriver() {}

    /*
     * Maintain the bot's class buffs (the botBuff() entry point). Timer-gated
     * and cheap; safe to call every tick for every bot.
     */
    public static void botBuff(Character bot) {
        castDueBuffs(bot, false);
    }

    /*
     * Force every configured class buff to show immediately, ignoring timers.
     * Backs the !bot buff <id> GM test command. Returns the number of
     * buffs broadcast (0 means this bot's job has no configured buffs).
     */
    public static int forceBuff(Character bot) {
        return castDueBuffs(bot, true);
    }

    /*
     * 1.5.0 rc3 (owner decision 1): keep the class buffs up the way a player does - recast each one when it runs out,
     * a little late (up to lateMaxMs, and now and then a buff is forgotten for a while longer), staggered one cast at
     * a time. A party buff is recast on time while the bot is partied with a real player. The first call casts every
     * buff the bot holds (it arrives buffed). Returns how many casts were queued.
     */
    public static int maintain(Character bot, long lateMaxMs) {
        if (bot == null) return 0;
        List<Integer> buffIds = BotBuffConfig.buffsFor(bot);
        if (buffIds.isEmpty()) return 0;
        long now = System.currentTimeMillis();
        Map<Integer, Long> timers = nextCastByBot.computeIfAbsent(bot.getId(), k -> new ConcurrentHashMap<>());
        boolean partied = partiedWithPlayer(bot);
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        int cast = 0;
        boolean support = partied && soloMapling.ArtificialPlayer.BotSupportSwitches.partySupport();
        for (int skillId : buffIds) {
            boolean partyBuff = support && BotBuffEffects.isPartyBuff(skillId);
            boolean due = now >= timers.getOrDefault(skillId, 0L);
            if (!due && partyBuff && lackGapPassed(bot.getId(), skillId, now)
                    && BotBuffEffects.partyMemberLacks(bot, skillId)) {
                due = true;   // 161: a player who joined, died or ran out since the last cast gets it now
                lackRecastAt.put(lackKey(bot.getId(), skillId), now);
            }
            if (!due) {
                continue;
            }
            if (partyBuff && !payForPartyBuff(bot, skillId)) {
                continue;   // 161: a party buff costs the MP it costs a player; no MP, no cast, retry next look
            }
            final int sid = skillId;
            MethodScheduler.runAfterDelay(() -> BotBuffEffects.castBuff(bot, sid), cast * STAGGER_MS);
            int durationMs = BotBuffEffects.durationOf(sid);
            long late = lateMaxMs > 0 ? rng.nextLong(lateMaxMs + 1) : 0;
            if (lateMaxMs > 0 && rng.nextDouble() < 0.08) {
                late += 15_000 + rng.nextLong(30_001); // forgot it for a while
            }
            if (partied && BotBuffEffects.isPartyBuff(sid)) {
                late = 0; // the party is counting on it
            }
            timers.put(sid, now + (durationMs > 0 ? durationMs : FALLBACK_RECAST_MS) + late);
            if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.COMBAT)) {
                soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.COMBAT, bot.getMapId(), "BUFF " + bot.getName()
                        + " casts " + sid + " (lasts " + (durationMs / 1000) + " s, next cast " + (late / 1000)
                        + " s after it runs out" + (partied ? ", partied" : "") + ")");
            }
            cast++;
        }
        return cast;
    }

    private static final long LACK_GAP_MS = 30_000L;
    private static final Map<Long, Long> lackRecastAt = new ConcurrentHashMap<>();

    private static long lackKey(int botId, int skillId) {
        return ((long) botId << 32) ^ (skillId & 0xffffffffL);
    }

    private static boolean lackGapPassed(int botId, int skillId, long now) {
        Long at = lackRecastAt.get(lackKey(botId, skillId));
        return at == null || now - at >= LACK_GAP_MS;
    }

    /* Ticket 161: pays a party buff's MP (drinking MP potions if it must); a skill the bot has no level in is free. */
    private static boolean payForPartyBuff(Character bot, int skillId) {
        int level = bot.getSkillLevel(skillId);
        int cost = level <= 0 ? 0 : soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals.mpCost(bot, skillId, level);
        if (cost <= 0) {
            return true;
        }
        if (!soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals.affordMp(bot, cost)) {
            return false;
        }
        soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals.spend(bot, cost, 0);
        return true;
    }

    /* When each of the bot's buffs is due again (skill -> epoch ms), for the cb-1 read-out. */
    public static Map<Integer, Long> timersOf(int botId) {
        Map<Integer, Long> t = nextCastByBot.get(botId);
        return t == null ? Map.of() : Map.copyOf(t);
    }

    /* Test seam: pretend the bot cast this buff and it runs until the given time. */
    public static void markCastForTest(int botId, int skillId, long until) {
        nextCastByBot.computeIfAbsent(botId, k -> new ConcurrentHashMap<>()).put(skillId, until);
    }

    private static boolean partiedWithPlayer(Character bot) {
        net.server.world.Party party = bot.getParty();
        if (party == null) return false;
        for (net.server.world.PartyCharacter pc : party.getMembers()) {
            Character p = pc == null ? null : pc.getPlayer();
            if (p != null && p != bot && !soloMapling.ArtificialPlayer.BotHelpers.isBot(p)) return true;
        }
        return false;
    }

    /* Release a despawned bot's recast timers so the map doesn't grow unbounded. */
    public static void clearBot(int botId) {
        nextCastByBot.remove(botId);
        BotPartySupport.clearBot(botId);
        lackRecastAt.keySet().removeIf(k -> (int) (k >> 32) == botId);
    }

    /*
     * Show one specific buff skill on the bot now, bypassing the per-job config.
     * Backs !bot castbuff <id> <skillId> - fire e.g. 4101004
     * (Assassin Haste) on any bot to confirm the visual pipe independent of the
     * job lookup. Returns true if the visual was broadcast.
     */
    public static boolean castSkill(Character bot, int skillId) {
        if (bot == null || bot.getMap() == null) return false;
        BotBuffEffects.showBuff(bot, skillId);
        return true;
    }

    private static int castDueBuffs(Character bot, boolean force) {
        if (bot == null) return 0;

        List<Integer> buffIds = BotBuffConfig.buffsFor(bot);
        if (buffIds.isEmpty()) return 0;

        long now = System.currentTimeMillis();
        Map<Integer, Long> timers = nextCastByBot.computeIfAbsent(bot.getId(), k -> new ConcurrentHashMap<>());

        int cast = 0;
        for (int skillId : buffIds) {
            if (!force && now < timers.getOrDefault(skillId, 0L)) {
                continue; // not due yet
            }
            final int sid = skillId;
            // Stagger: each due buff fires ~STAGGER_MS after the previous, in order,
            // off-thread (never blocks the caller). The recast timer is set now so the
            // loop won't re-trigger before the scheduled cast runs. castBuff shows the
            // bot's animation and, for party buffs, spreads to nearby party members.
            MethodScheduler.runAfterDelay(() -> BotBuffEffects.castBuff(bot, sid), cast * STAGGER_MS);

            int durationMs = BotBuffEffects.durationOf(sid);
            long recastAt = durationMs > 0 ? now + (long) (durationMs * 0.9) : now + FALLBACK_RECAST_MS;
            timers.put(sid, recastAt);
            cast++;
        }
        return cast;
    }
}
