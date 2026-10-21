package soloMapling.qa;

import client.Character;

import java.awt.Point;
import java.util.List;

/**
 * What a {@code !qa} scenario may do to the game, in one place: {@link GameQaWorld} on the server, a recording fake
 * in the tests. Every method that hands something out returns what it really did, so the scenario can register an
 * exact undo.
 */
public interface QaWorld {

    /** The GM's character if online on this server, else null (cleanups after a logout). */
    Character online(int characterId);

    void message(Character gm, String line);

    /** Warps the GM to the map's spawn point. False when the map does not exist. */
    boolean warp(Character gm, int mapId);

    int meso(Character gm);

    void gainMeso(Character gm, int delta);

    /** Gives the item; the quantity really given (0 when the inventory is full or the id unknown). */
    int giveItem(Character gm, int itemId, int quantity);

    /** Gives a pet that expires after the given days; true when it was given. */
    boolean givePet(Character gm, int petItemId, int days);

    /** How many of the item the GM carries in its inventories (equipped items do not count). */
    int countItem(Character gm, int itemId);

    /** Takes up to the quantity of the item back from the inventories; how many were taken. */
    int takeItem(Character gm, int itemId, int quantity);

    /** True when one of the GM's summoned pets is this pet item. */
    boolean petSummoned(Character gm, int petItemId);

    String itemName(int itemId);

    /** The item's normal value as the bots price it (ItemDatabase), or null when it has none. */
    Integer marketValue(int itemId);

    /** Where the NPC stands on the GM's current map, or null. */
    Point npcPosition(Character gm, int npcId);

    /** Spawns idle bots (no FSM) around a point of the GM's map; their character ids. */
    List<Integer> spawnIdleBots(Character gm, Point around, int count);

    /** Spawns FM shopper bots in the GM's FM room that stay in that room; their character ids. */
    List<Integer> spawnFmShoppers(Character gm, int count);

    /** !botshowcase on the GM's map with these arguments; the bots' ids, and its report lines added to {@code report}. */
    List<Integer> spawnShowcase(Character gm, String[] args, List<String> report);

    /** Takes a spawned bot out of the world if it is still there. */
    void despawnBot(int botId);

    /**
     * Opens a player shop for the GM where it stands, stocked from its inventory: one bundle of one of each item at
     * the given price ({itemId, price} pairs), the way the shop window does it. The GM's client shows the owner
     * window. What went wrong, or null when the shop is open.
     */
    String openPlayerShop(Character gm, String description, List<int[]> stock);

    /** Warps the GM onto a named portal of the map. False when the map or portal does not exist. */
    boolean warpToPortal(Character gm, int mapId, String portalName);

    /** "map 271010500 at (x, y), nearest portal out00" for the GM now. */
    String whereIs(Character gm);

    /** Closes the GM's own open player shop (items go back to the GM); false when it has none. */
    boolean closePlayerShop(Character gm);

    /** Closes the GM's hired merchant the way a server restart does (items go to Fredrick); what happened. */
    String closeOwnMerchant(Character gm);

    /** One line per channel: players, bots and the load bar the login screen shows. */
    List<String> channelLoads();

    /** The bot buy cap status for the GM's account (ticket 56A), one line. */
    String sellerCapStatus(Character gm);

    /** The guild creation fee from config.yaml. */
    int guildCost();

    /** The ground under the live hostile mob with the most live mobs around it on the GM's map, or null. */
    Point busiestMobSpot(Character gm);

    /** Live hostile mobs on the GM's map. */
    int liveMobCount(Character gm);

    /** Puts a spawned bot under the movement driver, standing idle with this reason (id-1). */
    void holdIdle(int botId, String reason);

    /** "idle hits 2, safe-spot moves 3" for a bot, or null when it is gone. */
    String idleStats(int botId);

    /** Idle safety (bots step out of mob packs before idling) on or off; the previous value. */
    boolean setIdleSafety(boolean on);

    /** Gives a bot an item through its holdings (ticket 11, sc-2); how many it got. */
    int giveBotItem(int botId, int itemId, int quantity);

    /**
     * The bot makes one real scroll roll on its own gear with the given appetite (careful | normal | gambler; null =
     * its own). The log lines of the roll; a line saying so when it has nothing it would scroll.
     */
    List<String> botScrollStep(int botId, String appetite);

    /**
     * Ticket 58 (af-1..af-3): a new bot next to the GM that feels {@code state} (friend | rival | nemesis) about them;
     * a training bot held on the GM's map when {@code training}, else a social bot. Its id, or -1.
     */
    int stageAffinityBot(Character gm, String state, boolean training);

    /** Takes a staged affinity bot away and forgets its ties. */
    void unstageAffinityBot(int botId);

    /** {@code @botdrama} for the GM: off = true. The previous value. */
    boolean setBotDramaOff(Character gm, boolean off);

    /** GM-only read-out of how a bot feels about the GM ("rival: score -40 ..."). */
    String affinityOf(int botId, Character gm);

    /** A fresh grudge of the bot against the GM, and a KS spree when they share a map; what happened. */
    String forceGrudge(int botId, Character gm);
}
