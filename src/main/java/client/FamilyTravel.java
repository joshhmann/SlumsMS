package client;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 1.6.1 promise audit (ticket 53): Family Reunion and an accepted Family Summon work across channels, as the original
 * game's did (its Family result codes have no "different channel" refusal, and the pedigree shows each member's
 * channel). The traveller changes channel like a player picking one, and lands on the target's map when the new
 * channel logs them in ({@link Character#newClient}) instead of on the map they left. An arrival not used within
 * {@link #TTL_MS} is forgotten (they then land where they were).
 */
public final class FamilyTravel {

    /** How long an expected arrival waits for the channel login. */
    static final long TTL_MS = TimeUnit.SECONDS.toMillis(60);

    /** Where a character is expected to land on {@code channel}. */
    public record Arrival(int channel, int mapId, Point at, long expires) {
    }

    private static final Map<Integer, Arrival> pending = new ConcurrentHashMap<>();

    private FamilyTravel() {
    }

    /** Character {@code chrId} is about to change to {@code channel}; land them on {@code mapId} near {@code at}. */
    public static void expect(int chrId, int channel, int mapId, Point at, long now) {
        pending.put(chrId, new Arrival(channel, mapId, at == null ? null : new Point(at), now + TTL_MS));
    }

    /** Forgets an expected arrival (the channel change did not happen). */
    public static void cancel(int chrId) {
        pending.remove(chrId);
    }

    /** The arrival for {@code chrId} logging in on {@code channel} now, once; null when none (or another channel). */
    public static Arrival take(int chrId, int channel, long now) {
        if (pending.isEmpty()) {
            return null;
        }
        Arrival a = pending.remove(chrId);
        if (a == null || a.expires() < now || a.channel() != channel) {
            return null;
        }
        return a;
    }
}
