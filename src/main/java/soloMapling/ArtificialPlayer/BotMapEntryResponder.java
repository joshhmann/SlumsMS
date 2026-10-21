package soloMapling.ArtificialPlayer;

import client.Character;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.GCMoveSystem.BotPerformanceMonitor;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.ExecutorServiceManager;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventSubscriber;
import soloMapling.server.EventMessageSystem.EventType;
import soloMapling.server.EventMessageSystem.GameEvent;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static soloMapling.ArtificialPlayer.BotHelpers.isBot;

// Makes bots feel instantly alive when a real player and a bot come to share a map. Two directions,
// one nudge:
//   A) a player enters a map that has bots - onEvent(MAP_ENTERED): mark the map observed now (instant
//      FULL movement/combat for the GC LOD) and nudge the nearest running bots on it (1.4.2: at most 12) to re-evaluate promptly
//      instead of on its slow 2-6s/10s wheel.
//   B) a bot enters a map a real player is already on - onBotArrivedObserved(): nudge just the arriving
//      bot. Its movement is already made instant in GCMovementDriver.onMapChange; this wakes its macro
//      brain too.
// Reuses the already-published MAP_ENTERED event - no base Cosmic file is touched.
public final class BotMapEntryResponder implements EventSubscriber {

    private static final BotMapEntryResponder INSTANCE = new BotMapEntryResponder();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    // Stagger window: each nudged bot's next tick fires somewhere in here, so bots don't all react on
    // the same frame (organic, and spreads the reschedule cost across the burst).
    private static final long NUDGE_MIN_MS = 150;
    private static final long NUDGE_MAX_MS = 700;

    // 1.4.2 (perf): a player entering a busy map nudges only the bots nearest to it, after its client has had a
    // moment to load the field. Before, every bot on the map (up to ~80 in Henesys) woke within 150-700 ms of the
    // entry, so their movement, emotes and chat landed on the client in the same second as the map's spawn burst.
    // The rest keep their own 2-6 s wheel, which already spreads them out.
    static final int ENTRY_NUDGE_MAX_BOTS = 12;
    static final long ENTRY_NUDGE_MIN_MS = 300;
    static final long ENTRY_NUDGE_MAX_MS = 1200;

    private BotMapEntryResponder() {
    }

    // Subscribe once at startup (idempotent). Called from EnvironmentManager.environmentLoadStartup.
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            EventBus.getInstance().subscribe(EventType.MAP_ENTERED, INSTANCE);
        }
    }

    @Override
    public boolean matchesFilter(GameEvent event) {
        return event != null && event.getType() == EventType.MAP_ENTERED;
    }

    // Direction A. Runs synchronously on the player's map-change thread (EventBus.publish has no
    // try/catch and does no offloading), so keep it cheap: do the O(1) observe inline, hand the bot
    // sweep to a virtual thread, and swallow everything - an uncaught throw here would break the
    // player's map entry.
    @Override
    public void onEvent(GameEvent event) {
        try {
            MapleMap map = event.getMap();
            if (map == null) {
                return;
            }
            GCMovement.markObservedNow(map.getId()); // instant FULL for the GC movement/combat LOD
            Character entrant = event.getMapleCharacter();
            java.awt.Point at = entrant != null ? new java.awt.Point(entrant.getPosition()) : null;
            ExecutorServiceManager.runAsync(() -> nudgeBotsOnMap(map, at));
        } catch (Throwable ignored) {
            // never let a responder failure propagate into MapleMap.addPlayer
        }
    }

    private void nudgeBotsOnMap(MapleMap map, java.awt.Point at) {
        try {
            for (Character chr : nearestBots(map.getAllPlayers(), at, ENTRY_NUDGE_MAX_BOTS)) { // snapshot copy
                nudge(chr, ENTRY_NUDGE_MIN_MS, ENTRY_NUDGE_MAX_MS);
            }
        } catch (Throwable ignored) {
        }
    }

    /* The bots among these characters nearest to the point (all of them up to max when the point is unknown). */
    static java.util.List<Character> nearestBots(java.util.Collection<Character> chars, java.awt.Point at, int max) {
        java.util.List<Character> bots = new java.util.ArrayList<>();
        for (Character chr : chars) {
            if (chr != null && isBot(chr)) {
                bots.add(chr);
            }
        }
        if (at != null && bots.size() > max) {
            bots.sort(java.util.Comparator.comparingDouble(b -> at.distanceSq(b.getPosition())));
        }
        return bots.size() > max ? bots.subList(0, max) : bots;
    }

    // Direction B. A bot just entered a map a real player is already on; wake its macro brain. Safe to
    // call from the movement tick thread (nudgeSoon only reschedules, never runs the FSM).
    public static void onBotArrivedObserved(Character bot) {
        try {
            if (bot != null) {
                INSTANCE.nudge(bot);
            }
        } catch (Throwable ignored) {
        }
    }

    private void nudge(Character botChr) {
        nudge(botChr, NUDGE_MIN_MS, NUDGE_MAX_MS);
    }

    private void nudge(Character botChr, long minMs, long maxMs) {
        BotSM bot = CharacterStorage.getAllBots().get(botChr.getId());
        if (bot == null || !bot.getRunning()) {
            return;
        }
        long jitter = ThreadLocalRandom.current().nextLong(minMs, maxMs + 1);
        bot.nudgeSoon(jitter);
        BotPerformanceMonitor.recordEntryNudges(1);
    }
}
