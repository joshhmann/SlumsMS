package soloMapling.worldevents;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which bots are headed to a world event, and where (ticket 21). A training bot that answers the call asks here on its
 * next decision and trains on the event map instead of its own pick; when the event ends the entry is dropped and the
 * bot's own session carries on (it finishes the grind it is on, then decides as usual). A bot is steered at most
 * {@link #MAX_DECISIONS} times per event, so one that cannot reach the map gives up instead of looping (an invasion
 * death and the walk back count one each, so a bot can die and come back a few times).
 * <p>
 * 1.6.0: with the bot brain on, a call is first an {@link Offer}: the bot's brain weighs the EVENT activity against
 * what it is doing (its Bartle point leans it, {@link WorldEventBias}) and only a bot that picks it is steered
 * ({@link WorldEvents#accept}). An offer that is not taken lapses after {@link #OFFER_MS}.
 */
public final class WorldEventSteer {

    static final int MAX_DECISIONS = 6;
    /** How long an unanswered offer stands (the brain decides at least every 30 s). */
    static final long OFFER_MS = 3 * 60_000L;

    record Call(int eventId, int mapId, int decisions) {
    }

    /** 1.6.0: an invitation the bot's brain may take (EVENT activity); kind leans the Bartle pull. */
    public record Offer(int eventId, int mapId, EventTemplate.Kind kind, long until) {
    }

    private static final Map<Integer, Call> calls = new ConcurrentHashMap<>();
    private static final Map<Integer, Offer> offers = new ConcurrentHashMap<>();

    static void offer(int charId, int eventId, int mapId, EventTemplate.Kind kind, long now) {
        offers.put(charId, new Offer(eventId, mapId, kind, now + OFFER_MS));
    }

    /** The standing offer to this bot, or null (none, or lapsed). */
    public static Offer offerFor(int charId, long now) {
        Offer o = offers.get(charId);
        if (o == null) {
            return null;
        }
        if (now > o.until()) {
            offers.remove(charId, o);
            return null;
        }
        return o;
    }

    static Offer takeOffer(int charId, long now) {
        Offer o = offerFor(charId, now);
        if (o != null) {
            offers.remove(charId, o);
        }
        return o;
    }

    static boolean offered(int charId) {
        return offers.containsKey(charId);
    }

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

    /** True while GM / {@code !qa} staging holds this bot on a map ({@link #hold}). */
    public static boolean isStagingHeld(int charId) {
        Call c = calls.get(charId);
        return c != null && c.eventId() == STAGING;
    }

    /** Drops every bot headed to this event. */
    static void release(int eventId) {
        calls.values().removeIf(c -> c.eventId() == eventId);
        offers.values().removeIf(o -> o.eventId() == eventId);
    }

    /** The event a bot is steered to (not a GM hold), or 0. */
    static int eventOf(int charId) {
        Call c = calls.get(charId);
        return c == null || c.eventId() == STAGING ? 0 : c.eventId();
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
        offers.clear();
    }
}
