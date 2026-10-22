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
     * 1.5.0 gachapon (ga-1): a forced roll for the GM at a town's machine, no ticket; {@code tier} (common | uncommon
     * | rare | jackpot) forces the tier, null rolls normally. The result line.
     */
    String gachaRoll(Character gm, String town, String tier);

    /** The machine's odds per tier and an n-roll simulation, as lines; a line saying so for an unknown town. */
    List<String> gachaOdds(String town, int simulateRolls);

    /** The map the town's machine NPC stands on (its last {@code maps:} entry), or 0. */
    int gachaMap(String town);

    /** Ticket 56B (ga-2): gives a spawned bot mesos (test only: the bot leaves at stop); how many it got. */
    int giveBotMeso(int botId, int amount);

    /** The bot's ceiling in mesos, or -1 when it is gone. */
    long botCeiling(int botId);

    /** "Lv 72 Bob: 14,200,000 held, ceiling ..." or a line saying it is gone. */
    String botWealthLine(int botId);

    /** One forced spending step (gachapon if its map has a machine, then upkeep): what it did. */
    List<String> botWealthStep(int botId);

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

    /**
     * Ticket 58 (cp-1, we-1): two new bots next to the GM that are a pair: {@code kind} dating | married | friends;
     * {@code sameGender} true / false / null (any). Their ids, or null.
     */
    int[] stageCouple(Character gm, String kind, Boolean sameGender);

    /** Breaks a staged couple up and takes both bots away. */
    void unstageCouple(int[] ids);

    /** Runs the couples' minute step now. */
    void coupleStep();

    /** Starts the Amoria wedding for the couple this bot is in; the problem, or null. */
    String startWedding(int botId, String by);

    /** Stops a running bot wedding, if any; how many stopped. */
    int stopWeddings(String by);

    /** Whether the GM is on the channel world events run on (channel 1). */
    boolean onEventChannel(Character gm);

    // ---- 1.5.0 bot guilds (gu-1 .. gu-7) ----

    /** Every bot guild, player guilds with bot members, and the boss run on now, one line each. */
    List<String> botGuildList();

    /**
     * A bot guild for a scenario: the named one, else one with a rival ({@code withRival}), else the biggest; null
     * when there is none.
     */
    String pickBotGuild(String wanted, boolean withRival);

    /** The rival guild's name of a bot guild, or null. */
    String rivalOf(String guild);

    /**
     * A new bot next to the GM that feels {@code state} (friend | neutral | rival | nemesis) about them, a member of
     * {@code guild} at once ("mine" = the GM's guild, a bot guild's name, null = no guild). Its id, or -1.
     */
    int stageGuildBot(Character gm, String state, String guild, int rank);

    /** Takes a staged guild bot out of its guild and the world. */
    void unstageGuildBot(int botId);

    /**
     * A bot guild action now (recruit | jab | bossrun | bossstop | step | quit | ignore | rival): what happened.
     * {@code args}: the guild (or the bot id for quit/ignore/rival), then extras.
     */
    List<String> botGuildAction(Character gm, String action, String[] args);

    /** The GM's guild id (0 = none). */
    int guildIdOf(Character gm);

    /** The GM leaves their guild, as the guild window's Leave button does. False when in none. */
    boolean leaveGuild(Character gm);

    /** The GM joins a bot guild now as the stock accept does (gu-8). False when in a guild or it is full. */
    boolean joinBotGuild(Character gm, String guild);

    // ---- 1.5.0 rc3: bots fight like people, spread out (cb-1 .. cb-4, sp-1) ----

    /**
     * Grinders on the GM's map: one per job name given (that job's check level, skills maxed), else {@code count}
     * bots of random classes at the map's mob level + {@code levelOffset}. {@code hold}: kept on the map; else they
     * only start there. {@code atGm}: next to the GM, else in the map's densest mob pack. Their ids.
     */
    default List<Integer> stageGrinders(Character gm, List<String> jobs, int count, int levelOffset, boolean hold,
                                        boolean atGm) {
        return List.of();
    }

    /** Takes staged grinders away. */
    default void unstageGrinders(List<Integer> ids) {
    }

    /** One line per staged bot: {@code buffs | position | habits}. */
    default List<String> grinderReport(List<Integer> ids, String what) {
        return List.of();
    }

    /** sp-1: the spread of every bot on the GM's map instance. */
    default List<String> spreadTally(Character gm) {
        return List.of();
    }

    /** cb-3 steps on staged grinders: die | lowpots | hurt. */
    default List<String> grinderAction(List<Integer> ids, String action) {
        return List.of();
    }

    // ---- 1.5.0 rc4: bot pets (pt-1) ----

    /** Gives staged bots a pet (with Meso Magnet and Item Pouch when {@code loots}); one line per bot. */
    default List<String> stagePets(List<Integer> ids, boolean loots) {
        return List.of();
    }

    /** One line per staged bot: its pet, pet equips and loot style. */
    default List<String> petReport(List<Integer> ids) {
        return List.of();
    }

    /** pt-1 {@code drop}: a meso pile of each bot's own and one of the GM's next to it. */
    default List<String> petDrops(Character gm, List<Integer> ids) {
        return List.of();
    }

    /** The human-combat switches, one line. */
    default String combatTuning() {
        return "";
    }
}
