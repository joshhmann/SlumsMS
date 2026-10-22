package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.Character;
import client.inventory.Item;
import server.life.Monster;
import server.maps.MapItem;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Ticket 58: the places in the game where a real player does something to a bot, turned into affinity events. Every
 * hook is called from core code through {@code MapleRootSolo.runHook} (a failure never stops the player's action),
 * returns at once when the player is a bot or their ties are not loaded, and does only memory work.
 *
 * <ul>
 *   <li>{@link #onPartyJoin}: a player and a bot end up in one party (either joined the other).</li>
 *   <li>{@link #onBuff}: a player's party buff or heal lands on a bot.</li>
 *   <li>{@link #beforePlayerHit}: a player's first hit on a mob a bot is fighting right now (hit it in the last
 *   {@link #KS_WINDOW_MS}, still on the map, and did not give it up to the player) is a KS.</li>
 *   <li>{@link #onMobHitBot} / {@link #onPlayerKill}: a player kills a mob that was just hitting a bot: a rescue.</li>
 *   <li>{@link #onTrade}: a finished trade; a gift when the bot gave nothing back.</li>
 *   <li>{@link #onFame}: fame or defame.</li>
 *   <li>{@link #onPickup}: a player picks up a bot's drop soon after it fell (free for all, but still its drop).</li>
 * </ul>
 * Kind or rude chat is judged in {@code BotConversation} through {@link ChatTone}.
 */
public final class AffinityHooks {

    /** A mob that hit a bot this recently and then dies to a player counts as a rescue. */
    static final long RESCUE_WINDOW_MS = TimeUnit.SECONDS.toMillis(8);
    /** A bot's drop picked up by a player within this time of falling counts as taken from it. */
    static final long STEAL_WINDOW_MS = TimeUnit.SECONDS.toMillis(45);
    /** A bot that hit a mob this recently is still fighting it: a player's first hit on it is a KS. */
    static final long KS_WINDOW_MS = TimeUnit.SECONDS.toMillis(5);
    static final int MAX_RECENT_HITS = 4096;
    private static final int MAX_YIELDS = 4096;

    /**
     * (channel, map, mob oid) -> {bot character id, when}: mobs that hit a bot while a real player was there to
     * rescue it. Oldest first: past the cap the oldest go, never the whole table.
     */
    private static final Map<Long, long[]> recentBotHits = new java.util.LinkedHashMap<>();
    /** Bot id -> when it last gave up its whole spot to a player (KS manners): every mob it had there is yielded. */
    private static final Map<Integer, Long> spotYieldAt = new ConcurrentHashMap<>();
    /** (bot, mob oid) -> when the bot left that mob to a player (KS manners). */
    private static final Map<Long, Long> mobYieldAt = new ConcurrentHashMap<>();
    /** Whether a real player is on the bot's map (a seam for tests). */
    private static volatile java.util.function.Predicate<Character> realPlayerHere = AffinityHooks::realPlayerOnMap;

    private AffinityHooks() {
    }

    private static boolean bot(Character chr) {
        return chr != null && chr.getId() >= BotStore.BOT_ID_BASE;
    }

    private static boolean realLoaded(Character chr) {
        return chr != null && !bot(chr) && BotAffinity.enabled() && BotAffinity.loaded(chr.getId());
    }

    private static boolean sameParty(Character a, Character b) {
        return a.getParty() != null && b.getParty() != null && a.getParty().getId() == b.getParty().getId();
    }

    // ── party ──

    /** {@code joiner} just joined a party: every bot-player pair it makes with the members on the server counts. */
    public static void onPartyJoin(Character joiner, List<Character> members) {
        if (joiner == null || members == null) {
            return;
        }
        for (Character other : members) {
            if (other == null || other.getId() == joiner.getId() || bot(other) == bot(joiner)) {
                continue;
            }
            Character player = bot(joiner) ? other : joiner;
            if (realLoaded(player)) {
                BotAffinity.record(joiner, other, AffinityEvent.PARTY);
            }
        }
    }

    // ── buffs and heals ──

    public static void onBuff(Character from, Character to, boolean heal) {
        if (bot(to) && realLoaded(from)) {
            BotAffinity.record(to, from, heal ? AffinityEvent.HEAL : AffinityEvent.BUFF);
        }
    }

    // ── KS and rescue ──

    /**
     * Before a real player's hit lands: if this is their first hit on a mob a bot (not in their party) is fighting
     * right now, they took the bot's mob. "Right now": the bot hit it within {@link #KS_WINDOW_MS}, is still on the map
     * and alive, and did not give that mob (or its spot) up to a player since its last hit - a mob a bot left alone
     * out of KS manners is free to take.
     */
    public static void beforePlayerHit(Character player, Monster mob) {
        if (!realLoaded(player) || mob == null || mob.isAttackedBy(player)) {
            return;
        }
        long now = System.currentTimeMillis();
        int botId = mob.recentAttackerAtOrAbove(BotStore.BOT_ID_BASE, player.getId(), now - KS_WINDOW_MS);
        if (botId < 0 || player.getMap() == null) {
            return;
        }
        Character bot = player.getMap().getCharacterById(botId);
        if (bot == null || !bot.isAlive() || sameParty(bot, player)) {
            return; // the bot left the map or died, or they are fighting it together
        }
        if (!countsAsKs(botId, mob.getObjectId(), mob.lastHitBy(botId), now)) {
            return;
        }
        BotAffinity.record(bot, player, AffinityEvent.KS);
    }

    /**
     * The KS rule on its own: the bot's last hit on the mob was within {@link #KS_WINDOW_MS} of now, and the bot has not
     * yielded the mob (or its whole spot) since that hit.
     */
    static boolean countsAsKs(int botId, int mobOid, long botLastHit, long now) {
        if (botLastHit <= 0 || now - botLastHit > KS_WINDOW_MS) {
            return false;
        }
        Long spot = spotYieldAt.get(botId);
        if (spot != null && spot >= botLastHit) {
            return false;
        }
        Long one = mobYieldAt.get(yieldKey(botId, mobOid));
        return one == null || one < botLastHit;
    }

    /**
     * KS manners: a bot gave a mob up to a real player ({@code mob} non-null), or its whole spot ({@code mob} null).
     * A player's hit on what it gave up is never a KS.
     */
    public static void onBotYield(Character bot, Monster mob, long now) {
        if (!bot(bot) || !BotAffinity.enabled()) {
            return;
        }
        if (mob == null) {
            spotYieldAt.put(bot.getId(), now);
            if (spotYieldAt.size() > MAX_YIELDS) {
                spotYieldAt.values().removeIf(t -> now - t > KS_WINDOW_MS * 2);
            }
            return;
        }
        mobYieldAt.put(yieldKey(bot.getId(), mob.getObjectId()), now);
        if (mobYieldAt.size() > MAX_YIELDS) {
            mobYieldAt.values().removeIf(t -> now - t > KS_WINDOW_MS * 2);
        }
    }

    private static long yieldKey(int botId, int oid) {
        return ((long) botId << 32) ^ (oid & 0xffffffffL);
    }

    private static long hitKey(Character bot, int oid) {
        int channel = bot.getClient() == null ? 0 : bot.getClient().getChannel();
        return ((long) channel << 56) ^ ((long) bot.getMapId() << 24) ^ oid;
    }

    /**
     * A mob just hit a bot (bot contact damage). Kept a few seconds for {@link #onPlayerKill}, and only when a real
     * player is on the map to do the rescuing (bots fight unseen all day; their hits would crowd out the ones that
     * matter). Past the cap the oldest entries go first.
     */
    public static void onMobHitBot(Character bot, Monster mob, long now) {
        if (!bot(bot) || mob == null || !BotAffinity.enabled() || !realPlayerHere.test(bot)) {
            return;
        }
        long key = hitKey(bot, mob.getObjectId());
        synchronized (recentBotHits) {
            recentBotHits.remove(key); // re-inserted at the young end
            recentBotHits.put(key, new long[]{bot.getId(), now});
            java.util.Iterator<long[]> oldest = recentBotHits.values().iterator();
            while (recentBotHits.size() > MAX_RECENT_HITS && oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
    }

    private static boolean realPlayerOnMap(Character bot) {
        try {
            if (bot.getMap() == null) {
                return false;
            }
            for (Character c : bot.getMap().getAllPlayers()) {
                if (c != null && !bot(c)) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    /** Tests: how many mob hits are kept for rescues. */
    static int recentHitCount() {
        synchronized (recentBotHits) {
            return recentBotHits.size();
        }
    }

    /** A real player's hit killed a mob: if it was hitting a bot a moment ago, the player rescued that bot. */
    public static void onPlayerKill(Character player, Monster mob) {
        if (!realLoaded(player) || mob == null || player.getMap() == null) {
            return;
        }
        int channel = player.getClient() == null ? 0 : player.getClient().getChannel();
        long key = ((long) channel << 56) ^ ((long) player.getMapId() << 24) ^ mob.getObjectId();
        long[] hit;
        synchronized (recentBotHits) {
            hit = recentBotHits.remove(key);
        }
        if (hit == null || BotAffinity.now() - hit[1] > RESCUE_WINDOW_MS) {
            return;
        }
        Character bot = player.getMap().getCharacterById((int) hit[0]);
        if (bot != null) {
            BotAffinity.record(bot, player, AffinityEvent.RESCUE);
        }
    }

    // ── trades, fame, drops ──

    /** A trade went through. A side that gave something while the other gave nothing made a gift. */
    public static void onTrade(Character a, List<Item> aItems, int aMeso, Character b, List<Item> bItems, int bMeso) {
        if (a == null || b == null || bot(a) == bot(b)) {
            return;
        }
        boolean aBot = bot(a);
        Character player = aBot ? b : a;
        Character botChr = aBot ? a : b;
        if (!realLoaded(player)) {
            return;
        }
        boolean playerGave = !(aBot ? bItems : aItems).isEmpty() || (aBot ? bMeso : aMeso) > 0;
        boolean botGave = !(aBot ? aItems : bItems).isEmpty() || (aBot ? aMeso : bMeso) > 0;
        if (playerGave && !botGave) {
            BotAffinity.record(botChr, player, AffinityEvent.GIFT);
        } else if (playerGave || botGave) {
            BotAffinity.record(botChr, player, AffinityEvent.TRADE);
        }
    }

    public static void onFame(Character from, Character target, int change) {
        if (bot(target) && realLoaded(from)) {
            BotAffinity.record(target, from, change > 0 ? AffinityEvent.FAME : AffinityEvent.DEFAME);
        }
    }

    /**
     * A real player is picking up a drop. If a bot owns it (it was not in the player's party) and it fell moments
     * ago with the bot still on the map, the player took the bot's loot.
     */
    public static void onPickup(Character player, MapItem item) {
        if (item == null || !realLoaded(player) || item.getOwnerId() < BotStore.BOT_ID_BASE || player.getMap() == null) {
            return;
        }
        if (BotAffinity.now() - item.getDropTime() > STEAL_WINDOW_MS) {
            return;
        }
        Character owner = player.getMap().getCharacterById(item.getOwnerId());
        if (owner == null || sameParty(owner, player)) {
            return;
        }
        BotAffinity.record(owner, player, AffinityEvent.DROP_STEAL);
    }

    /** Test seam: forgets everything; {@code testRealPlayer} decides "a real player is on the map" (null = the real check). */
    static void resetForTest(java.util.function.Predicate<Character> testRealPlayer) {
        synchronized (recentBotHits) {
            recentBotHits.clear();
        }
        spotYieldAt.clear();
        mobYieldAt.clear();
        realPlayerHere = testRealPlayer != null ? testRealPlayer : AffinityHooks::realPlayerOnMap;
    }

    static void resetForTest() {
        resetForTest(null);
    }
}
