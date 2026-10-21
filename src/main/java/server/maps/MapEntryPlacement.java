package server.maps;

import client.Character;
import config.YamlConfig;
import server.TimerManager;
import soloMapling.ArtificialPlayer.BotHelpers;

import java.awt.Point;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * 1.4.2 (perf): what a player entering a map gets at once, and what follows.
 * <p>
 * {@link MapleMap#sendObjectPlacement} used to send every object on the map in one burst; with USE_MAXRANGE on (live)
 * that is every bot, every drop (up to ITEM_LIMIT_ON_MAP) and every mob, in the same few milliseconds as SET_FIELD.
 * Now the objects within the view range (850 px, the range the ranged objects use without USE_MAXRANGE) go at once,
 * and bots and drops further away follow, nearest first, in chunks every {@link #STEP_MS} ms, all of them within
 * about {@link #MAX_STEPS} x {@link #STEP_MS} ms. Everything else (NPCs, mobs, reactors, shops, merchants, doors,
 * summons, the entrant's own things) is unchanged.
 * <p>
 * Safety, checked again when a deferred object is sent ({@link MapleMap#sendDeferredPlacement}):
 * <ul>
 *   <li>the entrant is still on this map, on the same entry (left or re-entered: nothing more is sent);</li>
 *   <li>a bot is still on the map on the same entry (a bot that left, or left and came back, has had its removal
 *   or its own spawn broadcast to the entrant, so the deferred spawn would be stale or a duplicate). The check and
 *   the send happen under the map's character read lock, so the bot's removal broadcast cannot overtake the spawn;</li>
 *   <li>a drop is still on the map and not picked up, checked and sent under the drop's own lock, as the pickup and
 *   expiry paths do.</li>
 * </ul>
 * Bots with a player shop or a mini game are never deferred (their spawn packet carries the room box). A packet
 * about a bot the client has not spawned yet (its movement, an emote) is dropped by the client; the bot's spawn
 * packet, when it comes, carries its current position, look, buffs, pets and chair, so nothing is lost.
 */
public final class MapEntryPlacement {

    /** Squared view range, the same as MapleMap's ranged distance without USE_MAXRANGE (850 px). */
    static final double VIEW_RANGE_SQ = 722_500;
    /** Time between two deferred chunks. */
    static final long STEP_MS = 100;
    /** Deferred objects per chunk at the least. */
    static final int MIN_CHUNK = 30;
    /** Chunks at the most: the whole map has arrived MAX_STEPS x STEP_MS after the entry. */
    static final int MAX_STEPS = 15;

    /** Where the chunks are scheduled; the tests run them by hand. */
    interface Scheduler {
        void schedule(Runnable task, long delayMs);
    }

    static volatile Scheduler scheduler = (task, delayMs) -> TimerManager.getInstance().schedule(task, delayMs);

    /** Called when the last chunk of an entry has gone out: how many objects were deferred (QA meter). */
    static volatile Listener listener;

    public interface Listener {
        void entered(Character entrant, int mapId, int sentNow, int deferred);

        void trickled(Character entrant, int mapId, int deferred, int sent);
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    private MapEntryPlacement() {
    }

    static boolean enabled() {
        return YamlConfig.config.server.MAP_ENTRY_TRICKLE;
    }

    static boolean inView(Point at, MapObject o) {
        return at.distanceSq(o.getPosition()) <= VIEW_RANGE_SQ;
    }

    /** A bot whose spawn may wait: any bot, unless it owns a player shop or a mini game. */
    static boolean deferrableCharacter(MapObject o) {
        if (!(o instanceof Character c) || !BotHelpers.isBot(c)) {
            return false;
        }
        return c.getPlayerShop() == null && c.getMiniGame() == null;
    }

    /** Ranged objects that may wait: drops. Mobs never wait (the map-transition handler re-spawns them). */
    static boolean deferrableRanged(MapObject o) {
        return o.getType() == MapObjectType.ITEM;
    }

    static int chunkSize(int deferred) {
        return Math.max(MIN_CHUNK, (deferred + MAX_STEPS - 1) / MAX_STEPS);
    }

    static void sortNearestFirst(Point at, List<Deferred> deferred) {
        deferred.sort(Comparator.comparingDouble(d -> at.distanceSq(d.object().getPosition())));
    }

    /** A deferred object and, for a bot, its map entry stamp at the time of the decision (null: not a character). */
    record Deferred(MapObject object, Long stamp) {
    }

    /** Runs the chunks: {@code step} sends chunk i and answers whether to go on. */
    static void run(int chunks, IntPredicate step) {
        scheduleStep(0, chunks, step);
    }

    private static void scheduleStep(int i, int chunks, IntPredicate step) {
        scheduler.schedule(() -> {
            if (step.test(i) && i + 1 < chunks) {
                scheduleStep(i + 1, chunks, step);
            }
        }, STEP_MS);
    }

    static void notifyEntered(Character entrant, int mapId, int sentNow, int deferred) {
        Listener l = listener;
        if (l != null) {
            try {
                l.entered(entrant, mapId, sentNow, deferred);
            } catch (RuntimeException ignored) {
                // a QA meter must never break a map entry
            }
        }
    }

    static void notifyTrickled(Character entrant, int mapId, int deferred, int sent) {
        Listener l = listener;
        if (l != null) {
            try {
                l.trickled(entrant, mapId, deferred, sent);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
