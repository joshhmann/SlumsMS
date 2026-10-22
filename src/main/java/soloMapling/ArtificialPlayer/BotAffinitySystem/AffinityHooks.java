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
 *   <li>{@link #beforePlayerHit}: a player's first hit on a mob a bot was already fighting is a KS.</li>
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
    private static final int MAX_RECENT_HITS = 4096;

    /** (channel, map, mob oid) -> {bot character id, when}. */
    private static final Map<Long, long[]> recentBotHits = new ConcurrentHashMap<>();

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
     * Before a real player's hit lands: if this is their first hit on a mob a bot (not in their party) has already
     * damaged, they took the bot's mob.
     */
    public static void beforePlayerHit(Character player, Monster mob) {
        if (!realLoaded(player) || mob == null || mob.isAttackedBy(player)) {
            return;
        }
        int botId = mob.otherAttackerAtOrAbove(BotStore.BOT_ID_BASE, player.getId());
        if (botId < 0 || player.getMap() == null) {
            return;
        }
        Character bot = player.getMap().getCharacterById(botId);
        if (bot == null || sameParty(bot, player)) {
            return; // the bot left the map, or they are fighting it together
        }
        BotAffinity.record(bot, player, AffinityEvent.KS);
    }

    private static long hitKey(Character bot, int oid) {
        int channel = bot.getClient() == null ? 0 : bot.getClient().getChannel();
        return ((long) channel << 56) ^ ((long) bot.getMapId() << 24) ^ oid;
    }

    /** A mob just hit a bot (bot contact damage). Kept a few seconds for {@link #onPlayerKill}. */
    public static void onMobHitBot(Character bot, Monster mob, long now) {
        if (!bot(bot) || mob == null || !BotAffinity.enabled()) {
            return;
        }
        if (recentBotHits.size() > MAX_RECENT_HITS) {
            recentBotHits.values().removeIf(v -> now - v[1] > RESCUE_WINDOW_MS);
            if (recentBotHits.size() > MAX_RECENT_HITS) {
                recentBotHits.clear();
            }
        }
        recentBotHits.put(hitKey(bot, mob.getObjectId()), new long[]{bot.getId(), now});
    }

    /** A real player's hit killed a mob: if it was hitting a bot a moment ago, the player rescued that bot. */
    public static void onPlayerKill(Character player, Monster mob) {
        if (!realLoaded(player) || mob == null || player.getMap() == null || recentBotHits.isEmpty()) {
            return;
        }
        int channel = player.getClient() == null ? 0 : player.getClient().getChannel();
        long key = ((long) channel << 56) ^ ((long) player.getMapId() << 24) ^ mob.getObjectId();
        long[] hit = recentBotHits.remove(key);
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

    /** Test seam. */
    static void resetForTest() {
        recentBotHits.clear();
    }
}
