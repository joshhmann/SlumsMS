package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import server.maps.MapleMap;
import server.maps.Portal;
import scripting.event.EventManager;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeCommands;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotScriptedWarp;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotTransit;
import soloMapling.BotLogger;
import soloMapling.server.BotShutdown;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/*
 * Cross-map travel executor (the "GCTravel" half). Layers a coarse hop-by-hop orchestrator on top
 * of GCMovement: route via GCWorldGraph, then for each hop walk to the portal that
 * leads to the next map (using GCMove) and step through it. Ticket 03 adds transit hops
 * (BotTransit): walk to the cab/boat/ferry NPC (its ticket seller first), wait for the event's
 * boarding window, pay the fare out of the bot's own mesos, then ride - waiting rooms and decks
 * are left by the server's own event, exactly as a player is. Anything else warps (changeMap).
 * The route is recomputed from the live current map each poll, so landing anywhere unexpected
 * self-corrects.
 *
 * LOD: the walk to the dock uses GCMovement, so an on-screen bot visibly walks to the NPC and an
 * off-screen one moves on the coarse planner; the boarding windows and voyages run on the real
 * schedule either way (a bot waiting off-screen costs nothing but one poll).
 *
 * Runs on its own ~300 ms poller per traveling bot, independent of the 50 ms physics tick.
 */
// Ported from GreenCatMS. Credit: NutNNut.
final class GCTravel {
    private GCTravel() {
    }

    // Route-search depth cap. Kept >= TrainingBot's discovery radius so anything a bot can DISCOVER it
    // can also ROUTE to (route may use taxi/ferry shortcuts, so the actual hop count is usually lower).
    // Ticket 03: cross-continent routes (Henesys -> Leafre is ~25 hops counting the dock and the decks).
    private static final int MAX_HOPS = GCWorldGraph.MAX_ROUTE_HOPS;
    private static final int POLL_MS = 300;
    // "At the portal" box: portals often sit a little above the floor the bot stands on, so the
    // vertical tolerance is generous while X stays tight.
    private static final int ENTER_X = 35;
    private static final int ENTER_Y = 100;
    // Warp a hop ONLY when the bot is genuinely stuck — neither closing on the portal NOR moving from the
    // spot it stands in for this long. A bot still walking a huge map (even one whose straight-line distance
    // to the portal isn't dropping — climbing a rope to an elevated portal, detouring round terrain, getting
    // knocked around by mobs) keeps changing position, so it's never cut off; map size is fine.
    private static final long HOP_STUCK_MS = 12_000;
    private static final int HOP_PROGRESS_EPS_PX = 16;  // min portal-distance drop that counts as closing in
    private static final int HOP_MOVE_EPS_PX = 16;      // min position change that counts as "not in the same spot"
    // Stand on the portal this long (after the walk has finished) before stepping through, so the
    // server warp never out-runs the walk packets (which read as "vanished mid-stride" to clients).
    private static final long PORTAL_ENTER_DWELL_MS = 350;
    // "Basically on top of it" snap: a bot that keeps failing to settle while near the hop target
    // just acts. 100px so it reaches a bot bouncing at the sloped lip of a portal platform, "just
    // barely off" below/beside it (the settled box above already tolerates 100px of Y, so a
    // smaller snap circle left exactly those bots uncovered). The clock survives brief excursions
    // (a wide arc, a slide back down the slope) via the grace — only staying away longer resets
    // it. A clean walk-up still normally enters through the settled dwell first.
    private static final int SNAP_ENTER_RADIUS_PX = 100;
    private static final long SNAP_ENTER_MS = 1_200;
    private static final long SNAP_NEAR_GRACE_MS = 1_500;
    // Soft-lock treadmill window: all samples confined to one small bounding box for the whole
    // window means the bot is cycling in place (jump, fall, slide back, retry). A box, not an
    // anchored circle: slope physics slides a bot a couple hundred px per failed cycle, which
    // kept escaping (and thereby resetting) the old fixed-anchor circle so it never latched.
    // A genuinely traveling bot outgrows 400px within a few seconds of walking.
    private static final int SOFT_LOCK_SPAN_PX = 400;
    private static final long SOFT_LOCK_MS = 20_000;
    // Absolute per-hop wall-clock ceiling — the backstop no movement pattern can evade. The
    // longest legitimate hop (huge map, detours, climbs) stays well under this.
    private static final long HOP_MAX_MS = 90_000;

    private static final ScheduledExecutorService POOL = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "gctravel-poll");
        t.setDaemon(true);
        return t;
    });

    private static final Map<Integer, Trip> TRIPS = new ConcurrentHashMap<>();

    static final class Trip {
        final Character bot;
        final int destMapId;
        final Consumer<Boolean> callback;
        ScheduledFuture<?> task;
        long settledAtMs;       // when the bot first arrived + stopped at the current hop's portal (0 = not yet)
        int hopBestDist = Integer.MAX_VALUE; // closest the bot has gotten to the current hop's portal
        int lastPosX = Integer.MIN_VALUE;    // last sampled bot position (liveness: is it still moving at all?)
        int lastPosY = Integer.MIN_VALUE;
        long hopProgressAtMs;   // last time the bot made progress (closed on the portal OR moved from its spot)
        long nearTargetSinceMs; // snap clock: since when the bot has been near the hop target (0 = not near)
        long lastNearTargetAtMs;// last sample inside the snap circle (grace keeps brief exits from resetting)
        int softMinX;           // bounding box of samples since the soft-lock window opened
        int softMaxX;
        int softMinY;
        int softMaxY;
        long softLockSinceMs;   // when the current soft-lock window opened (0 = unset)
        long hopStartAtMs;      // when the current hop's map was entered — feeds the absolute ceiling
        int lastMapId = -1;
        // Ticket 03 transit state.
        final int rebirths;     // rebirth gates the route may pass (BotMapGates)
        boolean noFares;        // couldn't pay a fare: replan over free hops only
        final boolean noWarp;   // ticket 59: a chat-asked walk - give up rather than warp anywhere
        final boolean ownedAlt; // 1.6.0.4: an owned alt walks portals only (see the constructor)
        boolean sellerVisited;  // walked past the ticket seller on this map already
        volatile boolean awaitingBoarding; // standing at a boarding NPC until the event opens entry
        volatile boolean aboard;           // on a waiting room / deck / ride map
        long watchedHoldSinceMs;           // 1.6.0: a fallback warp held because a player watches (0 = not held)
        // Loop guard: how often each transit hop (from << 32 | to) was taken on this trip. A trip that keeps
        // taking the same boat or ferry is going round in circles (the Ellinia <-> Ereve ferry loop).
        final Map<Long, Integer> transitHops = new java.util.HashMap<>();

        Trip(Character bot, int destMapId, Consumer<Boolean> callback) {
            this.bot = bot;
            this.destMapId = destMapId;
            this.callback = callback;
            this.rebirths = soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates.rebirthsOf(bot);
            // 1.6.0.4: an owned alt is a real character following its owner: it walks portals only - no warp (a warp
            // could set it down in an event map's public copy), no paid or boarded transit (its own mesos), no free ride.
            this.ownedAlt = bot.isOwnedAlt();
            this.noWarp = warpsForbidden(bot.getId()) || ownedAlt;
            this.noFares = ownedAlt;
        }
    }

    /** Ticket 59: bots whose trips must never warp (a walk a player asked for in chat), until when. */
    private static final Map<Integer, Long> NO_WARP_UNTIL = new java.util.concurrent.ConcurrentHashMap<>();

    /** For {@code ms} from now every trip this bot starts gives up instead of warping (chat-asked walks). */
    public static void forbidWarps(int botId, long ms) {
        NO_WARP_UNTIL.put(botId, nowMs() + Math.max(0, ms));
    }

    static boolean warpsForbidden(int botId) {
        Long until = NO_WARP_UNTIL.get(botId);
        if (until == null) {
            return false;
        }
        if (until < nowMs()) {
            NO_WARP_UNTIL.remove(botId, until);
            return false;
        }
        return true;
    }

    /** A no-warp trip gives up: logged, and its callback hears false. */
    private static void giveUpNoWarp(Trip trip, String reason) {
        BotLogger.log("[GCTravel] " + trip.bot.getName() + " gave up a chat-asked walk (no warps) - " + reason);
        finish(trip, false);
    }

    static void travel(Character bot, int destMapId, Consumer<Boolean> callback) {
        if (bot == null) {
            return;
        }
        cancel(bot);
        if (GCMovement.hasLeftWorld(bot)) {
            // A logged-out bot's errand callback (a sauna trip's way home, say) asks for a trip after removal. No
            // trip, no warp and no callback: the callback would only chain the next request.
            return;
        }
        if (bot.getMap() != null && bot.getMapId() == destMapId) {
            fire(callback, true);
            return;
        }
        // Rule: a bot never travels out of an open trade window. Cancel it before movement is enabled,
        // because GCMovementDriver freezes a traveling bot while it holds a trade - a trip requested
        // mid-trade would otherwise sit still until the soft-lock / hop-ceiling warp and the trade would
        // die implicitly instead. The abort only closes the window; the trip below still starts and
        // still fires its callback.
        BotTradeCommands.abortOpenTradeForTravel(bot);
        GCMovement.enable(bot);
        Trip trip = new Trip(bot, destMapId, callback);
        trip.hopProgressAtMs = nowMs();
        trip.hopStartAtMs = nowMs();
        trip.task = POOL.scheduleAtFixedRate(
                () -> {
                    try {
                        tick(trip);
                    } catch (Throwable ignored) {
                        // a thrown poll would cancel the periodic task
                    }
                }, 0, POLL_MS, TimeUnit.MILLISECONDS);
        TRIPS.put(bot.getId(), trip);
    }

    static void cancel(Character bot) {
        if (bot == null) {
            return;
        }
        Trip t = TRIPS.remove(bot.getId());
        if (t != null && t.task != null) {
            t.task.cancel(false);
        }
    }

    static boolean isTraveling(Character bot) {
        return bot != null && TRIPS.containsKey(bot.getId());
    }

    /* Ticket 03: {traveling, queued at a boarding NPC, aboard a ride} across all bots, for !env travel. */
    static int[] transitCounts() {
        int queued = 0;
        int aboard = 0;
        for (Trip t : TRIPS.values()) {
            if (t.awaitingBoarding) {
                queued++;
            }
            if (t.aboard) {
                aboard++;
            }
        }
        return new int[]{TRIPS.size(), queued, aboard};
    }

    /* Ticket 03: true while the bot waits on transit - queued at a boarding NPC or aboard a ride. It stands
     * still on purpose, so travel watchdogs must count this as progress. */
    static boolean isInTransit(Character bot) {
        Trip t = bot == null ? null : TRIPS.get(bot.getId());
        return t != null && (t.awaitingBoarding || t.aboard);
    }

    static void tick(Trip trip) {
        if (BotShutdown.isQuiesced()) {
            cancel(trip.bot); // Ticket 09: no hop, no warp and no callback once the gate has closed
            return;
        }
        Character bot = trip.bot;
        if (bot == null || bot.getMap() == null) {
            finish(trip, false);
            return;
        }
        if (GCMovement.hasLeftWorld(bot)) {
            // Logged out mid-trip (a trip started in the beat between the bot's movement teardown and its removal
            // from the channel). Its map is still set, so without this every poll would warp it again and log
            // "got stuck when moving" every 300 ms. End the trip and the driver silently, as the shutdown gate does.
            GCMovement.disable(bot);
            return;
        }
        int cur = bot.getMapId();
        if (cur == trip.destMapId) {
            finish(trip, true);
            return;
        }

        // New map since the last poll = a hop completed (or a warp/unexpected change): reset the
        // hop timer and clear any stale GCMove target so we re-target a portal on this map.
        if (cur != trip.lastMapId) {
            if (trip.lastMapId != -1 && transitLoop(trip, trip.lastMapId, cur)) {
                BotLogger.log("[GCTravel] " + bot.getName() + " took transit hop " + trip.lastMapId + " -> " + cur
                        + " " + TRANSIT_LOOP_LIMIT + " times on its way to " + trip.destMapId
                        + " - travel is looping, goal abandoned");
                finish(trip, false);
                return;
            }
            trip.lastMapId = cur;
            trip.watchedHoldSinceMs = 0L;
            trip.settledAtMs = 0L;
            trip.hopBestDist = Integer.MAX_VALUE;
            trip.lastPosX = Integer.MIN_VALUE;
            trip.lastPosY = Integer.MIN_VALUE;
            trip.hopProgressAtMs = nowMs();
            trip.nearTargetSinceMs = 0L;
            trip.softLockSinceMs = 0L;
            trip.hopStartAtMs = nowMs();
            trip.sellerVisited = false;
            trip.awaitingBoarding = false;
            trip.aboard = false;
            GCMovement.clearMoveIntent(bot);
        }

        // Aboard a ride (waiting room, deck, crane...): nothing to walk to - wait for the server to move us.
        BotTransit.Edge ride = BotTransit.rideOn(cur);
        if (ride != null) {
            ride(trip, bot, ride);
            return;
        }

        List<Integer> route = GCWorldGraph.route(cur, trip.destMapId, MAX_HOPS, trip.rebirths, !trip.noFares,
                savedReturn(bot), bot.getLevel());
        if (route == null) {
            if (trip.noFares) {
                // The only way on costs mesos this bot doesn't have: give up rather than warp for free.
                BotLogger.log("[GCTravel] " + bot.getName() + " can't afford the way from " + cur + " to "
                        + trip.destMapId + " (" + bot.getMeso() + " mesos) - trip failed");
                finish(trip, false);
                return;
            }
            if (trip.noWarp) {
                giveUpNoWarp(trip, "no route to " + trip.destMapId);
                return;
            }
            // The Free Market's doors are scripted portals the world graph cannot see: walk through one like a player
            Point door = marketDoor(bot.getMap(), trip.destMapId);
            if (door != null) {
                int dest = trip.destMapId;
                approachAndAct(trip, bot, door, dest, "market portal on map " + cur + " -> map " + dest,
                        () -> enterMarketDoor(bot, cur, dest));
                return;
            }
            // Nothing connects the two maps at all - warp the remainder to the destination.
            warpOrHold(trip, bot, trip.destMapId, "no portal or transit route to " + trip.destMapId);
            return;
        }
        if (route.isEmpty()) {
            finish(trip, true);
            return;
        }
        int nextHop = route.get(0);

        // Prefer a walkable portal; else a transit hop (cab, boat, ferry...); else a curated scripted warp;
        // else warp the hop.
        Portal portal = findPortalTo(bot.getMap(), nextHop);
        if (portal != null) {
            approachAndAct(trip, bot, portal.getPosition(), nextHop,
                    "portal " + portal.getId() + " -> map " + nextHop,
                    () -> GCPortals.enter(bot, portal)); // walk to the portal, stand a beat, step through
            return;
        }
        BotTransit.Edge transit = BotTransit.edge(cur, nextHop);
        if (transit != null && transit.isBoard()) {
            if (trip.ownedAlt) {
                giveUpNoWarp(trip, "an owned alt does not board '" + transit.name() + "' to " + nextHop);
                return;
            }
            board(trip, bot, transit);
            return;
        }
        // Curated scripted-warp portal (e.g. subway entrance): its WZ target is a script, so findPortalTo
        // can't see it. Walk to the portal's spot and warp to the destination, like the script's pi.warp.
        BotScriptedWarp.WarpEdge sw = BotScriptedWarp.edge(cur, nextHop);
        if (sw != null) {
            Point trigger = BotScriptedWarp.portalPos(bot.getMap(), sw.portalName());
            if (trigger == null) {
                if (trip.noWarp) {
                    giveUpNoWarp(trip, "scripted portal '" + sw.portalName() + "' not on map " + cur);
                    return;
                }
                warpOrHold(trip, bot, nextHop, "scripted portal '" + sw.portalName() + "' not on map " + cur);
                return;
            }
            approachAndAct(trip, bot, trigger, nextHop,
                    "scripted portal '" + sw.portalName() + "' -> map " + nextHop,
                    () -> warpToPortal(bot, sw.toMapId(), sw.toPortalId(),
                            "scripted warp " + cur + " -> " + nextHop));
            return;
        }
        if (trip.noWarp) {
            giveUpNoWarp(trip, "no walkable portal on map " + cur + " to " + nextHop);
            return;
        }
        warpOrHold(trip, bot, nextHop, "no walkable portal/transit/scripted-warp on map " + cur + " to " + nextHop);
    }

    // ── Transit (ticket 03) ─────────────────────────────────────────────────

    /*
     * A boarding hop: walk past the ticket seller (when one stands on this map), then to the boarding NPC
     * or scripted portal, and board. A bot that can't get there in the usual hop windows is set down beside
     * the NPC instead of being warped past it, so the fare and the boarding window still apply.
     */
    private static void board(Trip trip, Character bot, BotTransit.Edge e) {
        MapleMap map = bot.getMap();
        String intent = "'" + e.name() + "' " + e.fromMapId() + " -> " + e.toMapId();
        if (!trip.sellerVisited && e.sellerNpcId() > 0) {
            Point seller = BotTransit.npcPos(map, e.sellerNpcId());
            if (seller != null) {
                approachAndAct(trip, bot, seller, e.toMapId(), "ticket seller for " + intent, () -> {
                    trip.sellerVisited = true;
                    restartHop(trip);
                    return true;
                }, () -> {
                    trip.sellerVisited = true;
                    restartHop(trip);
                });
                return;
            }
            trip.sellerVisited = true;
        }
        Point at = e.via() == BotTransit.Via.PORTAL ? BotTransit.portalPos(map, e.portalName())
                : BotTransit.npcPos(map, e.npcId());
        if (at == null) {
            tryBoard(trip, bot, e); // NPC not spawned on this map: board where we stand (the fare still applies)
            return;
        }
        approachAndAct(trip, bot, at, e.toMapId(), intent, () -> tryBoard(trip, bot, e), () -> {
            // set down beside the NPC; boarding happens next poll. 1.6.0: while watched it walks on instead
            GCMovement.teleportTo(bot, at.x, at.y, "board-setdown");
            restartHop(trip);
        });
    }

    /* Board now if the event's window is open and the bot can pay. False = still queued at the NPC. */
    static boolean tryBoard(Trip trip, Character bot, BotTransit.Edge e) {
        if (e.event() != null && !boardingOpen(bot, e.event())) {
            if (!trip.awaitingBoarding) {
                trip.awaitingBoarding = true;
                BotLogger.log("[GCTravel] " + bot.getName() + " queued for '" + e.name() + "' on map "
                        + e.fromMapId() + " (boarding closed)");
            }
            restartHop(trip); // waiting in line is not being stuck
            return false;
        }
        if (e.minLevel() > 0 && bot.getLevel() < e.minLevel()) {
            // The script turns the bot away (Jeff lets nobody under 50 past Ice Valley): no free pass.
            BotLogger.log("[GCTravel] " + bot.getName() + " (lv " + bot.getLevel() + ") is too low for '" + e.name()
                    + "' (needs " + e.minLevel() + ") - trip failed");
            finish(trip, false);
            return true;
        }
        if (e.via() == BotTransit.Via.HYPER_ROCK) {
            long left = BotTransit.hyperCooldownLeftMs(bot);
            if (left > 0) {
                if (!trip.awaitingBoarding) {
                    trip.awaitingBoarding = true;
                    BotLogger.log("[GCTravel] " + bot.getName() + " waits " + (left / 1000) + "s for the hyper rock on map "
                            + e.fromMapId());
                }
                restartHop(trip); // the 5-minute cooldown, as HyperTPHandler times a player
                return false;
            }
            if (!hyperRockMayLand(bot, e.toMapId())) {
                BotLogger.log("[GCTravel] " + bot.getName() + " can't teleport to " + e.toMapId() + " - trip failed");
                finish(trip, false);
                return true;
            }
        }
        trip.awaitingBoarding = false;
        int fare = e.fareFor(bot.getJob().getId());
        if (fare > 0 && !BotHoldings.spendMeso(bot, fare)) {
            trip.noFares = true; // replan over free hops; failing that the trip ends without a free warp
            BotLogger.log("[GCTravel] " + bot.getName() + " can't pay " + fare + " for '" + e.name()
                    + "' (" + bot.getMeso() + " mesos)");
            return true;
        }
        if (e.saveLocation() != null) {
            bot.saveLocation(e.saveLocation());
        }
        int to = e.toMapId();
        if (e.returnToSaved() != null) {
            int saved = bot.getSavedLocation(e.returnToSaved()); // the script's own way home
            to = saved > 0 ? saved : constants.id.MapId.LITH_HARBOUR;
        }
        if (e.via() == BotTransit.Via.HYPER_ROCK) {
            BotTransit.hyperUsed(bot);
            bot.saveLocationOnWarp(); // HyperTPHandler does this for a player too
        }
        BotLogger.log("[GCTravel] " + bot.getName() + " took '" + e.name() + "' " + e.fromMapId() + " -> " + to
                + (fare > 0 ? " for " + fare + " mesos" : ""));
        arrive(bot, to, e);
        return true;
    }

    /* HyperTPHandler's own refusals: a restricted map, a rebirth gate, or a field the rock can't reach. */
    private static boolean hyperRockMayLand(Character bot, int mapId) {
        if (!soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates.mayEnter(mapId, bot)) {
            return false;
        }
        try {
            MapleMap target = bot.getMap().getChannelServer().getMapFactory().getMap(mapId);
            return target != null && !server.maps.FieldLimit.CANNOTVIPROCK.check(target.getFieldLimit());
        } catch (RuntimeException e) {
            return true; // no live map to ask (a test seam): the table's own check stands
        }
    }

    /* Aboard a ride. Server-driven rides end when the event (or the ferry's timer) moves the bot; past `sec`
     * plus a grace the bot is moved anyway, so a missing event can never strand it. Per-player instances
     * (Hak, the dragon) have nothing to move a bot, so it gets off itself after `sec`. */
    static void ride(Trip trip, Character bot, BotTransit.Edge e) {
        trip.aboard = true;
        long limitMs = bot.getMap().getWorldServer() == null ? e.scaledMs(1)
                : bot.getMap().getWorldServer().getTransportationTime((int) Math.min(Integer.MAX_VALUE, e.sec() * 1000L));
        long aboardMs = nowMs() - trip.hopStartAtMs;
        if (!e.serverDriven()) {
            if (aboardMs >= limitMs) {
                BotLogger.log("[GCTravel] " + bot.getName() + " arrived by '" + e.name() + "' -> " + e.toMapId());
                arrive(bot, e.toMapId(), e);
            }
            return;
        }
        long graceMs = Math.max(30_000L, limitMs / 5);
        if (aboardMs >= limitMs + graceMs) {
            warp(bot, e.toMapId(), "'" + e.name() + "' never moved the bot off map " + e.fromMapId()
                    + " in " + ((limitMs + graceMs) / 1000) + "s - ride fallback");
        }
    }

    private static void arrive(Character bot, int mapId, BotTransit.Edge e) {
        try {
            if (mapId == e.toMapId() && e.toPortalName() != null) {
                bot.changeMap(mapId, e.toPortalName());
            } else if (mapId == e.toMapId() && e.toPortalId() >= 0) {
                bot.changeMap(mapId, e.toPortalId());
            } else {
                bot.changeMap(mapId);
            }
        } catch (Throwable ignored) {
            // a bad map id / lifecycle race shouldn't kill the trip; the next poll re-evaluates
        }
    }

    /* Mirrors the boarding NPCs' check: em.getProperty("entry") == "true". An event that isn't running
     * (no such script, or no property yet) doesn't hold the bot - the ride's own fallback timer covers it. */
    static boolean boardingOpen(Character bot, String event) {
        try {
            EventManager em = bot.getMap().getChannelServer().getEventSM().getEventManager(event);
            String entry = em == null ? null : em.getProperty("entry");
            return entry == null || "true".equals(entry);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /* Where the World Tour would take this bot home: its saved town, else the script's default. */
    static int savedReturn(Character bot) {
        int saved = bot.peekSavedLocation(WORLD_TOUR);
        return saved > 0 ? saved : GCWorldGraph.NO_SAVED_RETURN;
    }

    private static final String WORLD_TOUR = "WORLDTOUR";

    /* A trip that takes the same transit hop this many times is looping, not getting anywhere. No sane route
     * repeats a hop at all; the slack covers a ride that set the bot down somewhere odd once. */
    static final int TRANSIT_LOOP_LIMIT = 3;

    /* Count the map change from -> to when it was a transit hop (boarding, or getting off a ride); true once that
     * hop has been taken TRANSIT_LOOP_LIMIT times on this trip. Walked portals are not counted. */
    static boolean transitLoop(Trip trip, int from, int to) {
        if (BotTransit.edge(from, to) == null && !BotTransit.isRideMap(from)) {
            return false;
        }
        long key = ((long) from << 32) | (to & 0xffffffffL);
        return trip.transitHops.merge(key, 1, Integer::sum) >= TRANSIT_LOOP_LIMIT;
    }

    private static void restartHop(Trip trip) {
        long now = nowMs();
        trip.settledAtMs = 0L;
        trip.hopStartAtMs = now;
        trip.hopProgressAtMs = now;
        trip.softLockSinceMs = 0L;
        trip.nearTargetSinceMs = 0L;
        trip.hopBestDist = Integer.MAX_VALUE;
    }

    /*
     * Walk the bot to dest, wait until it has arrived AND finished the walk (not mid-stride),
     * stand a short dwell, then run action (enter the portal / ride the cab). Progress-aware:
     * a bot still closing the distance OR just moving across the map is never cut off; only a bot that stays
     * in the same spot for the whole window warps the hop.
     */
    private static void approachAndAct(Trip trip, Character bot, Point dest, int nextHop, String intent, Runnable action) {
        approachAndAct(trip, bot, dest, nextHop, intent, () -> {
            action.run();
            return true;
        });
    }

    private static void approachAndAct(Trip trip, Character bot, Point dest, int nextHop, String intent,
                                       BooleanSupplier action) {
        approachAndAct(trip, bot, dest, nextHop, intent, action, null);
    }

    /*
     * As above, with action answering whether it acted (false = still waiting at the target, e.g. queued
     * for a boat) and giveUp replacing the "warp the hop" escape for hops that must not be skipped (a paid
     * boarding sets the bot down beside the NPC instead). Null giveUp = warp the hop.
     */
    private static void approachAndAct(Trip trip, Character bot, Point dest, int nextHop, String intent,
                                       BooleanSupplier action, Runnable giveUp) {
        Point bp = bot.getPosition();
        long now = nowMs();

        // Absolute per-hop ceiling: whatever the physics looked like, a hop this old has failed.
        if (now - trip.hopStartAtMs >= HOP_MAX_MS) {
            escape(trip, bot, nextHop, giveUp, "HOP-CEILING: hop " + (HOP_MAX_MS / 1000) + "s old on map "
                    + bot.getMapId() + " at (" + bp.x + "," + bp.y + "), intent: " + intent);
            return;
        }

        // Snap clock: time since the bot first came near the hop target. Survives brief
        // excursions (wide arc, slide back down the slope) via the grace window.
        long sdx = bp.x - dest.x;
        long sdy = bp.y - dest.y;
        boolean nearTarget = sdx * sdx + sdy * sdy <= (long) SNAP_ENTER_RADIUS_PX * SNAP_ENTER_RADIUS_PX;
        if (nearTarget) {
            trip.lastNearTargetAtMs = now;
            if (trip.nearTargetSinceMs == 0L) {
                trip.nearTargetSinceMs = now;
            }
        } else if (trip.nearTargetSinceMs != 0L && now - trip.lastNearTargetAtMs > SNAP_NEAR_GRACE_MS) {
            trip.nearTargetSinceMs = 0L;
        }

        boolean atDest = Math.abs(bp.x - dest.x) <= ENTER_X && Math.abs(bp.y - dest.y) <= ENTER_Y;
        boolean settled = atDest && !GCMovement.isMoving(bot);
        if (settled) {
            if (trip.settledAtMs == 0L) {
                trip.settledAtMs = now;                           // just arrived + stopped: start dwell
            } else if (now - trip.settledAtMs >= PORTAL_ENTER_DWELL_MS) {
                action.getAsBoolean();                            // stood a beat — act now (or keep queueing)
            }
            return;
        }
        trip.settledAtMs = 0L;                                    // still walking / knocked off: restart dwell

        // Basically on top of the target but never settling — mid-arc counts as moving, so a bot
        // arc-jumping over the portal on a curved floor would hover here forever. Just act.
        if (nearTarget && now - trip.nearTargetSinceMs >= SNAP_ENTER_MS) {
            action.getAsBoolean();
            return;
        }

        // Soft-lock treadmill window (see SOFT_LOCK_* above): samples confined to one small
        // bounding box for the whole window — warp the hop and log where it was and what it
        // wanted, so recurring bad map+portal approaches can be mined from the log.
        if (trip.softLockSinceMs == 0L) {
            trip.softLockSinceMs = now;
            trip.softMinX = bp.x;
            trip.softMaxX = bp.x;
            trip.softMinY = bp.y;
            trip.softMaxY = bp.y;
        } else {
            trip.softMinX = Math.min(trip.softMinX, bp.x);
            trip.softMaxX = Math.max(trip.softMaxX, bp.x);
            trip.softMinY = Math.min(trip.softMinY, bp.y);
            trip.softMaxY = Math.max(trip.softMaxY, bp.y);
            if (trip.softMaxX - trip.softMinX > SOFT_LOCK_SPAN_PX
                    || trip.softMaxY - trip.softMinY > SOFT_LOCK_SPAN_PX) {
                trip.softLockSinceMs = now;                       // outgrew the box: real travel — reopen here
                trip.softMinX = bp.x;
                trip.softMaxX = bp.x;
                trip.softMinY = bp.y;
                trip.softMaxY = bp.y;
            } else if (now - trip.softLockSinceMs >= SOFT_LOCK_MS) {
                escape(trip, bot, nextHop, giveUp, "SOFT-LOCK: confined to "
                        + (trip.softMaxX - trip.softMinX) + "x" + (trip.softMaxY - trip.softMinY)
                        + "px for " + (SOFT_LOCK_MS / 1000) + "s on map " + bot.getMapId()
                        + " at (" + bp.x + "," + bp.y + "), intent: " + intent);
                return;
            }
        }

        // Progress = the bot is closing on the portal OR simply moving from where it stood. Straight-line
        // distance to the portal can plateau on a big map while the bot travels fine (climbing to an elevated
        // portal, detouring round terrain, getting knocked around by mobs), so a stalled distance alone is NOT
        // stuck. The position-displacement is measured from the last progress point (anchor), not the previous
        // tick, so slow steady movement still accumulates across the window. Only a bot that stays inside the
        // move epsilon for the whole window is genuinely wedged and gets warped.
        int dist = Math.abs(bp.x - dest.x) + Math.abs(bp.y - dest.y);
        boolean closingIn = dist < trip.hopBestDist - HOP_PROGRESS_EPS_PX;
        boolean moved = trip.lastPosX == Integer.MIN_VALUE
                || Math.abs(bp.x - trip.lastPosX) + Math.abs(bp.y - trip.lastPosY) > HOP_MOVE_EPS_PX;
        if (closingIn || moved) {
            if (closingIn) {
                trip.hopBestDist = dist;
            }
            trip.lastPosX = bp.x;
            trip.lastPosY = bp.y;
            trip.hopProgressAtMs = now;
        } else if (now - trip.hopProgressAtMs > HOP_STUCK_MS) {
            escape(trip, bot, nextHop, giveUp, "stuck " + (HOP_STUCK_MS / 1000) + "s — not moving toward hop target on map " + bot.getMapId());
            return;
        }

        if (!GCMovement.isMoving(bot)) {
            GCMovement.move(bot, dest.x, dest.y);                 // (re)issue the walk to the hop target
        }
    }

    private static Portal findPortalTo(MapleMap map, int targetMapId) {
        for (Portal p : map.getPortals()) {
            if (p.getTargetMapId() == targetMapId) {
                return p;
            }
        }
        return null;
    }

    /* A hop's escape hatch: warp the hop, or the hop's own giveUp when it must not be skipped. */
    private static void escape(Trip trip, Character bot, int nextHop, Runnable giveUp, String reason) {
        if (giveUp == null && trip.noWarp) {
            giveUpNoWarp(trip, reason);
            return;
        }
        if (giveUp == null) {
            warpOrHold(trip, bot, nextHop, reason);
            return;
        }
        BotLogger.log("[GCTravel] " + bot.getName() + " gave up walking - " + reason);
        giveUp.run();
    }

    // ── The Free Market's doors ─────────────────────────────────────────────

    static final int FREE_MARKET = 910000000;

    /* The town portals into the Free Market: each runs a marketNN script (or the Arcane River one) that warps to its
     * "out00"; the Free Market's own door (classicfm_exit) leads back out. */
    static final java.util.Set<String> MARKET_ENTRY_SCRIPTS = java.util.Set.of("market01", "market02", "market03",
            "market04", "market05", "market06", "market07", "market08", "market09", "market10", "market11", "market12",
            "market13", "market14", "market15", "market16", "market17", "market18", "market19", "market20", "market21",
            "market22", "market23", "market24", "market26", "market34", "market52", "market53", "market54", "market55",
            "market56", "market110", "market201", "arc_to_classicfm");
    static final String MARKET_EXIT_SCRIPT = "classicfm_exit";

    /*
     * TEST bot watch 2026-10-27 (Maple3, Ludibrium): a FREE_MARKET or SELL trip has no route the world graph knows - the
     * doors are scripted - so it warped, and with a player in town WatchGate held the warp 45 s and the trip failed
     * ("said FREE_MARKET but never entered it"). A bot in a town with a market door walks to it and steps through,
     * as a player does; one in the Free Market walks out of its door, wherever it is headed. Either side: whether a
     * portal with this script is a door for a trip from fromMapId to destMapId.
     */
    static boolean isMarketDoor(String script, int fromMapId, int destMapId) {
        if (script == null) {
            return false;
        }
        return fromMapId == FREE_MARKET ? destMapId != FREE_MARKET && MARKET_EXIT_SCRIPT.equals(script)
                : destMapId == FREE_MARKET && MARKET_ENTRY_SCRIPTS.contains(script);
    }

    /* Where this map's market door stands for a trip to destMapId, or null (no door on this map, or not that trip). */
    static Point marketDoor(MapleMap map, int destMapId) {
        if (map == null || (map.getId() != FREE_MARKET && destMapId != FREE_MARKET)) {
            return null;
        }
        for (Portal p : map.getPortals()) {
            if (isMarketDoor(p.getScriptName(), map.getId(), destMapId)) {
                return p.getPosition();
            }
        }
        return null;
    }

    /* Through the door: into the Free Market at its "out00" (the scripts' landing), or out of it to the trip's end. */
    private static void enterMarketDoor(Character bot, int fromMapId, int destMapId) {
        if (fromMapId != FREE_MARKET) {
            BotLogger.log("[GCTravel] " + bot.getName() + " walked into the Free Market from map " + fromMapId);
            try {
                bot.saveLocation("CLASSIC_FREE_MARKET"); // as the marketNN script does
                bot.changeMap(FREE_MARKET, "out00");
            } catch (Throwable ignored) {
                // a lifecycle race shouldn't kill the trip; the next poll re-evaluates
            }
            return;
        }
        warp(bot, destMapId, "walked out of the Free Market's door");
    }

    // 1.6.0 player likeness (WatchGate): a trip that would vanish off a map a real player is on waits for the walk
    // instead - the hop clock restarts, so the bot keeps trying to walk to its portal - for at most this long. Then the
    // trip fails (the caller picks something else to do) rather than blinking out in front of the player.
    static final long WATCHED_HOLD_MS = 45_000;

    /* The fallback warp of a hop, unless a player is watching: then hold (walk again) up to WATCHED_HOLD_MS, then fail. */
    static void warpOrHold(Trip trip, Character bot, int mapId, String reason) {
        if (WatchGate.mayReposition(bot, "travel-warp")) {
            trip.watchedHoldSinceMs = 0L;
            warp(bot, mapId, reason);
            return;
        }
        long now = nowMs();
        if (trip.watchedHoldSinceMs == 0L) {
            trip.watchedHoldSinceMs = now;
            BotLogger.log("[GCTravel] " + bot.getName() + " would warp to map " + mapId + " but a player is on map "
                    + bot.getMapId() + " - walking on instead (" + reason + ")");
        }
        if (now - trip.watchedHoldSinceMs >= WATCHED_HOLD_MS) {
            BotLogger.log("[GCTravel] " + bot.getName() + " still watched on map " + bot.getMapId() + " after "
                    + (WATCHED_HOLD_MS / 1000) + "s - trip given up instead of a visible warp");
            finish(trip, false);
            return;
        }
        restartHop(trip);
    }

    private static void warp(Character bot, int mapId, String reason) {
        BotLogger.log("[GCTravel] " + bot.getName() + " warped to map " + mapId + " — " + reason);
        try {
            bot.changeMap(mapId);
        } catch (Throwable ignored) {
            // a bad map id / lifecycle race shouldn't kill the trip; the next poll re-evaluates
        }
    }

    private static void warpToPortal(Character bot, int mapId, int portalId, String reason) {
        BotLogger.log("[GCTravel] " + bot.getName() + " warped to map " + mapId + " portal " + portalId + " — " + reason);
        try {
            bot.changeMap(mapId, portalId);
        } catch (Throwable ignored) {
            // a bad map id / lifecycle race shouldn't kill the trip; the next poll re-evaluates
        }
    }

    private static void finish(Trip trip, boolean ok) {
        TRIPS.remove(trip.bot.getId());
        if (trip.task != null) {
            trip.task.cancel(false);
        }
        // Clear the travel's move intent only — never tear down an active follow session (a
        // follow-driven trip arriving on the target's map must let GCFollow resume same-map follow).
        GCMovement.clearMoveIntent(trip.bot);
        fire(trip.callback, ok);
    }

    private static void fire(Consumer<Boolean> cb, boolean ok) {
        if (cb != null) {
            try {
                cb.accept(ok);
            } catch (Throwable ignored) {
                // callback errors must not propagate into the poller
            }
        }
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }
}
