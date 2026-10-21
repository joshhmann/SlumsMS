package soloMapling.worldevents;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which bots are headed to a world event, and where (ticket 21). A training bot that answers the call asks here on its
 * next decision and trains on the event map instead of its own pick; when the event ends the entry is dropped and the
 * bot's own session carries on (it finishes the grind it is on, then decides as usual). A bot is steered at most
 * {@link #MAX_DECISIONS} times per event, so one that cannot reach the map gives up instead of looping.
 */
public final class WorldEventSteer {

    static final int MAX_DECISIONS = 3;

    record Call(int eventId, int mapId, int decisions) {
    }

    private static final Map<Integer, Call> calls = new ConcurrentHashMap<>();

    private WorldEventSteer() {
    }

    static void steer(int charId, int eventId, int mapId) {
        calls.put(charId, new Call(eventId, mapId, 0));
    }

    static boolean isSteered(int charId) {
        return calls.containsKey(charId);
    }

    /** The event id GM staging holds use (no real event has it). */
    public static final int STAGING = -58;

    /**
     * GM / {@code !qa} staging (ticket 58): keeps a training bot training on this map as if an event had called it
     * there (no walk-off, no crowd bail), until {@link #unhold}. Never counts toward a decision limit.
     */
    public static void hold(int charId, int mapId) {
        calls.put(charId, new Call(STAGING, mapId, Integer.MIN_VALUE / 2));
    }

    public static void unhold(int charId) {
        Call c = calls.get(charId);
        if (c != null && c.eventId() == STAGING) {
            calls.remove(charId, c);
        }
    }

    /** Drops every bot headed to this event. */
    static void release(int eventId) {
        calls.values().removeIf(c -> c.eventId() == eventId);
    }

    /** The event map a deciding bot should train on, or -1. Counts the decision. */
    public static int decide(int charId) {
        Call c = calls.get(charId);
        if (c == null) {
            return -1;
        }
        if (c.decisions() >= MAX_DECISIONS) {
            calls.remove(charId, c);
            return -1;
        }
        calls.put(charId, new Call(c.eventId(), c.mapId(), c.decisions() + 1));
        return c.mapId();
    }

    /** The event map this bot is headed to or holding, or -1 (no decision counted). */
    public static int mapFor(int charId) {
        Call c = calls.get(charId);
        return c == null ? -1 : c.mapId();
    }

    static int count(int eventId) {
        return (int) calls.values().stream().filter(c -> c.eventId() == eventId).count();
    }

    static void clearForTest() {
        calls.clear();
    }
}
