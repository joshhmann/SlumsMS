package soloMapling.ArtificialPlayer;

import client.Character;
import client.Client;
import client.Job;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver;
import soloMapling.ArtificialPlayer.BotBuffRequestSystem.BotBuffRequestHandler;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.SoloMaplingUtilities;

import java.awt.*;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static soloMapling.ArtificialPlayer.BotClientHandler.getBotClient;
import static soloMapling.ArtificialPlayer.BotCommandsPack.WarpCommands.botEnterPortalDropDown;
import static soloMapling.ArtificialPlayer.BotDecoratorSystem.BotDecorate.setBotVariables;
import static soloMapling.ArtificialPlayer.BotDecoratorSystem.BotDecorate.setBotVariablesInRange;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.microTurnAroundToLeft;
import static soloMapling.DebugUtilities.debugprint;
import static soloMapling.FreeMarket.FMShopDescGen.registerCharacterName;
import static soloMapling.server.ExecutorServiceManager.runAsync;
import static soloMapling.server.SoloMaplingUtilities.getChr;
import static soloMapling.server.SoloMaplingUtilities.getMapleMapById;
import static soloMapling.server.SoloMaplingUtilities.world;

public class BotGeneration {

    // Bot generation - handles anything related to putting the bots in the server

    /**
     * Worst-case duration of the spawn choreography (pre-drop delay 0.5-1.2s
     * + portal lag 1.5s + drop-down playback + optional turn-around delay
     * 1.0-1.5s + turn playback). Anything that must visually wait for a freshly
     * spawned bot to finish arriving (FSM first tick, shop opening, chair sits,
     * facing adjustments) should delay by at least this much.
     */
    public static final long SPAWN_CHOREOGRAPHY_MAX_MS = 7000;

    /**
     * Bots placed since server start, restored ones included (for startup logging). Counted where the
     * bots are placed, because a bot's slot no longer says anything about how many have been placed.
     */
    private static final AtomicInteger placedBots = new AtomicInteger();

    public static int getBotsCreatedCount() {
        return placedBots.get();
    }


    /**
     * Every bot template load goes through {@link BotDbGate}, paying for the connections
     * {@code Character.loadCharFromDB} nests up front, so parallel spawns can never hold the whole pool between
     * them while each waits for a nested connection - and players logging in keep their reserve.
     */
    private static Character loadTemplate(int templateId) throws SQLException {
        return BotDbGate.hold(BotDbGate.CHARACTER_LOAD,
                () -> Character.loadCharFromDB(templateId, getBotClient(), false));
    }

    public static Character getConsoleBot() {
        Character consoleBot = getChr(soloMapling.server.SoloMaplingConstants.GameConstants.CONSOLE_BOT_ID);
        if (consoleBot != null) {
            return consoleBot;
        }

        int botId = soloMapling.server.SoloMaplingConstants.GameConstants.CONSOLE_BOT_ID;
        int baseId = soloMapling.server.MapleRootSolo.templateId(); // Base Bot Character

        try {
            Character chr = loadTemplate(baseId);
            consoleBot = chr;
        } catch (SQLException e) {
            e.printStackTrace();
        }

        consoleBot = setConsoleBot(consoleBot, botId); // Bot onDemandBot
        addBotToServer(consoleBot);
        return consoleBot;
    }

    /**
     * How a brand new bot is decorated: any class at 10-80 (ad-hoc spawns: drama staging, GM commands), any class in
     * the asked level range (staging that names a level), or the asked class at a level in its range (the roster:
     * the class mix and the level curve both come from {@code BotPopulationMix}).
     */
    enum Decoration { RANDOM, LEVEL_RANGE, CLASS }

    /**
     * 1.5.0 rc6 (QA t-rm-2): a level range with no class (staging "a lv 60 partner", "grinders at the map's level")
     * keeps its range; it used to fall into the random 10-80 path and lose it.
     */
    static Decoration decorationFor(int baseClass, int minLevel) {
        if (baseClass > 0) {
            return Decoration.CLASS;
        }
        return minLevel > 0 ? Decoration.LEVEL_RANGE : Decoration.RANDOM;
    }

    public static int createBot(int slot, Point pos, MapleMap map) {
        return createBot(slot, pos, map, 0, 0, 0, 0);
    }

    public static int createBot(int slot, Point pos, MapleMap map, int baseClass, int minLevel, int maxLevel) {
        return createBot(slot, pos, map, baseClass, minLevel, maxLevel, 0);
    }

    /**
     * Creates one bot at the given slot of the store's key space, which the caller owns: a
     * {@link BotStore#planSlot(int, int, int) plan slot} for a boot wave (stable across boots, so the bot
     * is adopted rather than minted), or {@link BotStore#nextDynamicSlot() nextDynamicSlot()} for an
     * on-demand spawn, which is always a brand new bot.
     *
     * @param forcedJobId &gt; 0 pins the exact job (GM 'trainhere' test spawn); 0 = a random job for the class.
     */
    public static int createBot(int slot, Point pos, MapleMap map, int baseClass, int minLevel, int maxLevel,
                                int forcedJobId) {
        // Ticket 57: a bot of the environment's town dressing goes to the channel furthest below its share of the
        // dressing; any other bot (a roster login picks its channel itself) goes to the map's channel.
        int reserved = -1;
        if (map != null && map.getEventInstance() == null && BotChannels.isDressing(slot)) {
            reserved = BotChannels.reserveDressing();
            MapleMap there = BotChannels.map(reserved, map.getId());
            if (there != null) {
                map = there;
            }
        }
        try {
            return createBotOnChannel(slot, pos, map, baseClass, minLevel, maxLevel, forcedJobId);
        } finally {
            if (reserved > 0) {
                BotChannels.releaseDressing(reserved);
            }
        }
    }

    private static int createBotOnChannel(int slot, Point pos, MapleMap map, int baseClass, int minLevel, int maxLevel,
                                          int forcedJobId) {
        Client client = clientFor(map);
        int cid = soloMapling.server.MapleRootSolo.templateId(); // the dedicated bot template character

        Character bot = null;
        try {
            Character chr = loadTemplate(cid);
            bot = chr;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        if (bot == null) {
            return -1;
        }
        // The slot is spent only once the load has succeeded: cohorts spawn in parallel, so a slot given
        // back after a failure would not go to the bot the wave meant to have it anyway.
        int botId = BotStore.BOT_ID_BASE + slot;
        bot = setBotStats(bot, botId, client); // Bot onDemandBot
        // Ticket 06: the slot's stored bot, if this plan position has been filled in an earlier run, keeps
        // its identity, progression, looks, holdings and last-seen placement - so it must not be decorated
        // again (decoration would re-roll level, job and appearance). The identity is applied before the
        // bot joins the map, so the spawn packet already carries the stored name instead of the
        // placeholder one. On-demand spawns never adopt: their slot is arrival order, not a plan position.
        boolean adopted = BotStore.isPlanned(slot) && BotStore.restoreIdentity(bot);
        // Ticket bot-persistence/05: one name per character, for good. An adopted bot keeps its stored name
        // unless an older bot or a real player holds it; the store records a rename with the bot's next save.
        bot.setName(adopted ? BotNames.claimStored(botId, bot.getName()) : BotNames.claimNew(botId));
        registerCharacterName(bot.getName());
        // Ticket bot-persistence/06: a roster member logs back in where it logged out, not where its spawn
        // wave would put a new bot, and its bot type is handed what it was doing.
        MapleMap placeMap = map;
        Point placePos = pos;
        if (adopted && BotRoster.isMember(slot)) {
            BotStore.Persisted stored = BotStore.persistedFor(botId);
            BotResume.Placement resume = BotResume.placementFor(stored.mapId, new Point(stored.posX, stored.posY),
                    id -> client.getChannelServer().getMapFactory().getMap(id));
            if (resume != null) {
                placeMap = resume.map();
                placePos = resume.position();
            }
            BotRoster.recordLogin(botId, System.currentTimeMillis(), map.getId(), placeMap.getId(), placePos);
        }
        // 1.6.0: a population bot carries a bigger bag than a player's 24 slots (no client to desync): see BotBagRoom.
        soloMapling.ArtificialPlayer.BotSupplySystem.BotBagRoom.widen(bot);
        addBotToServer(bot, client);
        BotBuddies.onBotLogin(bot); // ticket 49: its player buddies see it online; requests made while away are answered
        soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.onBotLogin(bot); // 1.5.0: its guild, before it shows on the map
        soloMapling.ArtificialPlayer.BotDramaSystem.BotDrama.onBotLogin(bot); // 1.5.0: a bot engaged or married to a player
        BotFamily.onBotLogin(bot); // 1.6.1 ticket 53: a player's family junior
        placeBotOnMap(bot, placePos, placeMap);
        placedBots.incrementAndGet();
        if (adopted) {
            BotStore.restorePlacement(bot, placeMap);
        } else {
            // Decorate before the drop-down plays so the bot arrives fully dressed
            // (decoration is an in-memory cache lookup, takes microseconds).
            switch (decorationFor(baseClass, minLevel)) {
                case RANDOM -> setBotVariables(bot);
                case LEVEL_RANGE -> setBotVariablesInRange(bot, minLevel, Math.max(minLevel, maxLevel));
                case CLASS -> setBotVariables(bot, baseClass, minLevel, maxLevel, forcedJobId);
            }
            bot.setFame(BotFame.forBot(botId, bot.getLevel()));
            // A brand new bot is minted with the initial-creator grants and gets its store row right away,
            // so a restart between creation and its first save cannot re-mint it as somebody else.
            try {
                BotStore.recordFirstCreation(bot);
                // A bot the population waves mint while seeding joins the roster (bot-persistence ticket 08).
                if (BotRoster.POPULATION_WAVES.contains(BotStore.waveOf(slot))) {
                    BotRoster.enroll(slot, System.currentTimeMillis());
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        // 1.6.0 (ticket 92): ~1 in 20 hangout/explorer bots own a Fishing Chair; new ones get it now, existing ones at
        // this login (once: it is saved with the bag). After decoration and restore, so neither replaces the bag.
        soloMapling.fishing.BotFishingChairs.ensure(bot);
        BotPets.attach(bot); // 1.5.0 rc4: its pet, now that its level is known (drawn from its id: nothing stored)
        // Choreography sleeps ~2.5-6s in total; play it on a virtual thread so
        // mass spawning isn't gated on each bot's arrival animation. Drop-down ->
        // turn-around ordering is preserved because it's one sequential task.
        Character finalBot = bot;
        runAsync(() -> playSpawnChoreography(finalBot));
        return botId;
    }


    /**
     * Places the bot on a map + position and plays the portal drop-down animation
     * so the spawn looks like a real player arriving. Sequence runs inline so the
     * caller waits for the full spawn choreography to finish before continuing —
     * use this (not createBot's async path) when downstream actions must not race
     * the drop-down / turn-around (e.g. OPQ map transitions).
     *
     * This method blocks the calling thread for roughly 2.5-6s total. Callers
     * should already be running on a pooled executor (not the client thread).
     */
    public static void warpBotToLocation(Character fakechar, Point pos, MapleMap map) {
        placeBotOnMap(fakechar, pos, map);
        playSpawnChoreography(fakechar);
    }

    private static void placeBotOnMap(Character fakechar, Point pos, MapleMap map) {
        if (fakechar.getMap() == map) {
            fakechar.getMap().removePlayer(fakechar);
        }
        fakechar.setMap(map);
        fakechar.setPosition(pos);
        fakechar.setStance(5);
        map.addPlayer(fakechar);
    }

    /**
     * The spawn arrival choreography. Blocks the calling thread while it plays:
     *   - drop-down fires 500-1200ms after the bot is added to the map
     *     (plus ~1.5s portal lag inside botEnterPortalDropDown)
     *   - a random-direction micro turn-around fires 1000-1500ms after the
     *     drop-down playback completes - the strict ordering matters, the turn
     *     must never overlap the drop-down packets
     * Worst case is bounded by {@link #SPAWN_CHOREOGRAPHY_MAX_MS}.
     */
    private static void playSpawnChoreography(Character fakechar) {
        long dropDelayMs = ThreadLocalRandom.current().nextLong(500, 1201);
        if (!BotHelpers.blockingSleep(dropDelayMs)) return;
        botEnterPortalDropDown(fakechar);

        // Bots spawn facing right by default, so a 50% roll to flip to left gives
        // roughly even left/right distribution without a no-op right-turn.
        if (ThreadLocalRandom.current().nextBoolean()) {
            long turnDelayMs = ThreadLocalRandom.current().nextLong(1000, 1501);
            if (!BotHelpers.blockingSleep(turnDelayMs)) return;
            microTurnAroundToLeft(fakechar);
        }
    }

    private static Character setConsoleBot(Character baseChr, int botId) {
        Character onDemandBot = baseChr; // Character.getDefault(c)
        onDemandBot.setClient(getBotClient());
        onDemandBot.setWorld(getBotClient().getWorld());   // ticket 41: the bots' world, not the template row's
        onDemandBot.setName("Console");

        onDemandBot.setID(botId);
        onDemandBot.setLevel(69);
        onDemandBot.setFame(BotFame.forBot(botId, 69));
        onDemandBot.setJob(Job.getById(420));

        return onDemandBot;
    }

    private static Character setBotStats(Character baseChr, int botId, Client client) {
        Character onDemandBot = baseChr; // Character.getDefault(c)
        onDemandBot.setClient(client);
        onDemandBot.setWorld(client.getWorld());   // ticket 41: the bots' world, not the template row's
        onDemandBot.setName(""); // claimed in createBot once it is known whether the slot is adopted
        onDemandBot.setID(botId);
        onDemandBot.setFame(0); // set from the level once decoration has rolled it (BotFame)
        return onDemandBot;
    }

    public static void removeBotFromServer(Character fakechar) {
        soloMapling.ArtificialPlayer.BotConvo.SayDo.event(fakechar, soloMapling.ArtificialPlayer.BotConvo.SayDo.Ev.LOGOUT, fakechar.getMapId()); // rc6 say-do
        // MapleRoot: stop the bot's FSM, wander, recruit/party queues and any trade before anything else.
        BotLifecycle.stopActivities(fakechar);
        // A bot's lifecycle commitments end here, in this one place, so every caller leaves the same clean
        // slate behind whether or not it stopped the bot's FSM first (/dchere does not).
        //
        // An open trade window goes first: it holds items and mesos out of both wallets, so a save taken
        // before the cancel would write the bot out without the refund and destroy that value (the same
        // ordering BotStore.settleAndSaveAll uses).
        BotStore.closeOpenWindow(fakechar);
        // Dynamic control owns a per-bot driver, its travel/follow/fidget pollers and the shared movement
        // lock. Left enabled, a departed bot's trip would keep polling a character that is in no map, and
        // the lock would stay held. disable is null-safe and idempotent.
        GCMovement.disable(fakechar);
        // Spot claims are lifecycle state kept across LOD demotion/promotion, so only a map change or this
        // despawn ends them; releasing by bot id catches every (map, spot) it held.
        BotSpotClaims.releaseAllFor(fakechar.getId());

        // Ticket 06: a bot's holdings now go into the store with it, so leaving the world costs it nothing.
        // If the store cannot be written the bot is settled onto the map instead - while the map is still
        // available - so what it accumulated is never silently destroyed.
        BotStore.saveThenSettle(fakechar);
        BotRoster.recordSessions(java.util.List.of(fakechar), System.currentTimeMillis(), true);
        BotConversation.endAllOf(fakechar.getId()); // a bot leaving mid-chat still writes down what it heard
        BotBuddies.onBotLogout(fakechar); // ticket 49: its player buddies see it go offline
        soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.onBotLogout(fakechar); // 1.5.0: offline on its guild's list
        BotFamily.onBotLogout(fakechar); // 1.6.1 ticket 53: save its family rep, offline in the pedigree
        leaveEventOnDespawn(fakechar);
        if (fakechar.getMap() != null) fakechar.getMap().removePlayer(fakechar);
        removeFromChannels(fakechar);
        world().getPlayerStorage().removePlayer(fakechar.getId());
        leaveMapWarpedIntoMeanwhile(fakechar);
        CharacterStorage.removeActiveBot(fakechar.getId());//
        BotBuffDriver.clearBot(fakechar.getId());   // Phase 3a: release buff recast timers
        BotBuffRequestHandler.clearBot(fakechar.getId());   // release chat-buff-request cooldown
        BotPets.forget(fakechar.getId());   // 1.5.0 rc4: its pet's follow state (the pet left the map with it)
    }

    /**
     * 1.6.2 (bot audit): a bot despawning inside a party quest leaves it the way a disconnecting player does (the
     * event script's {@code playerDisconnected}: unregistered, the PQ ended when too few are left). It used to stay in
     * the instance's member list, so the instance outlived everyone and held the channel's only KPQ slot.
     */
    static void leaveEventOnDespawn(Character bot) {
        scripting.event.EventInstanceManager eim = bot.getEventInstance();
        if (eim == null || eim.isEventDisposed()) {
            return;
        }
        try {
            eim.playerDisconnected(bot);
        } catch (RuntimeException e) {
            soloMapling.BotLogger.log("[Despawn] " + bot.getName() + " could not leave " + eim.getName() + ": " + e);
        }
    }

    /**
     * 1.5.2 (ticket 80, after nutnnut/Cosmic ae5753df0): a warp on another thread (the bot's tick) can land the bot in
     * a new map between its removal from the old one and its removal from the channel. Once the channel no longer
     * holds the bot, a warp that has not added it yet unwinds itself (Character.changeMapInternal); one that already
     * did is caught here, so the bot never stays behind in a map as a ghost.
     */
    static void leaveMapWarpedIntoMeanwhile(Character fakechar) {
        MapleMap now = fakechar.getMap();
        // the same map too: a warp can re-add the bot to the map it was just taken out of (a respawn, a same-map warp)
        if (now != null && now.getCharacterById(fakechar.getId()) == fakechar) {
            now.removePlayer(fakechar);
        }
    }

    /** Ticket 57: the shared client of the map's channel (channel 1 when the map has none). */
    private static Client clientFor(MapleMap map) {
        net.server.channel.Channel ch = map == null ? null : map.getChannelServer();
        if (ch == null || ch.getId() == soloMapling.server.SoloMaplingConstants.GameConstants.CHANNEL_1) {
            return getBotClient();
        }
        return BotClientHandler.getBotClient(ch.getId());
    }

    /** Takes a bot out of its channel's player storage, and out of any other channel's should it be in one. */
    private static void removeFromChannels(Character fakechar) {
        net.server.channel.Channel own = fakechar.getClient() == null ? null : fakechar.getClient().getChannelServer();
        if (own != null) {
            own.removePlayer(fakechar);
        } else {
            soloMapling.server.SoloMaplingUtilities.channel().removePlayer(fakechar);
        }
        removeFromOthers(fakechar, own, BotChannels.channels());
    }

    /**
     * Out of every channel but its own that still holds it. A channel that was shut down keeps its place in the world
     * with no player storage (Channel.shutdown sets it null): skipped. rc6 auto-QA triage: gu-8's "unstage the guild
     * bots" threw a NullPointerException here on the test server, and the staged bots stayed online.
     */
    static void removeFromOthers(Character fakechar, net.server.channel.Channel own,
                                 Iterable<net.server.channel.Channel> channels) {
        for (net.server.channel.Channel ch : channels) {
            if (ch == null || ch == own) {
                continue;
            }
            net.server.PlayerStorage ps = ch.getPlayerStorage();
            if (ps != null && ps.getCharacterById(fakechar.getId()) == fakechar) {
                ch.removePlayer(fakechar);
            }
        }
    }

    private static void addBotToServer(Character fakechar) {
        addBotToServer(fakechar, getBotClient());
    }

    private static void addBotToServer(Character fakechar, Client client) {
        client.getChannelServer().addPlayer(fakechar);
//        World world = Server.getInstance().getWorld(BotSM.GameConstants.WORLD_SCANIA);
        world().getPlayerStorage().addPlayer(fakechar);
    }

    public static void spawnBotFm(Character fakechar, Point pt) {
        int fmMap = 910000000;
        MapleMap spawnMap = fakechar.getClient().getChannelServer().getMapFactory().getMap(fmMap);
        fakechar.setMap(spawnMap);
        fakechar.setPosition(pt);
        fakechar.setStance(5);
        spawnMap.addPlayer(fakechar);
    }

    /*
    Creates a bot on demand. The bot is registered in channel storage synchronously
    inside createBot, so it's normally ready on the first check - the 100ms poll
    loop only kicks in as a fallback. If the bot isn't ready after 3000ms, returns null.
     */
    public static Character createBotPollReadiness(int slot, Point position, int mapId) {
        int botId = BotGeneration.createBot(slot, position, getMapleMapById(mapId));

        for (int i = 0; i < 30; i++) { // 30 * 100ms = 3000ms max
            Character fakechar = BotHelpers.getCharFromChannelStorage(botId);
            if (fakechar != null) {
                if (i > 0) {
                    debugprint("Bot " + botId + " ready after " + (i * 100) + "ms");
                }
                return fakechar;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        System.err.println("Bot " + botId + " not ready after 3 seconds, skipping store");
        return null;
    }

}
