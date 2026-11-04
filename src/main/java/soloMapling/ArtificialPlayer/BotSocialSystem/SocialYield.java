package soloMapling.ArtificialPlayer.BotSocialSystem;

import soloMapling.ArtificialPlayer.BotStore;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Ticket 271 measurement: what bots hand REAL (non-bot) characters, per kind: count, meso value and distinct receivers,
 * since boot, plus the player-hours the yield is divided by. Counters only; nothing here changes a gift, cap or price.
 * A receiver in the bot id range is ignored, so bot-to-bot gifts never count.
 */
public final class SocialYield {

    private static final class Kind {
        long count;
        long mesos;
        final Set<Integer> receivers = new HashSet<>();
    }

    private static final Map<String, Kind> KINDS = new TreeMap<>();
    private static long bootAt = -1;
    private static long lastSampleAt = -1;
    private static double playerMs;
    private static double pilotMs;

    private SocialYield() {
    }

    /** A bot handed {@code receiverId} something worth {@code mesos} (NPC or fair value, as the gift caps use). */
    public static synchronized void record(String kind, int receiverId, long mesos) {
        if (receiverId >= BotStore.BOT_ID_BASE) {
            return;
        }
        Kind k = KINDS.computeIfAbsent(kind, x -> new Kind());
        k.count++;
        k.mesos += Math.max(0, mesos);
        k.receivers.add(receiverId);
    }

    /** The denominator: {@code players} non-bot characters online, {@code inPilot} of them on the pilot map, at {@code now}. */
    public static synchronized void sample(int players, int inPilot, long now) {
        if (bootAt < 0) {
            bootAt = now;
        }
        if (lastSampleAt >= 0 && now > lastSampleAt) {
            playerMs += (double) players * (now - lastSampleAt);
            pilotMs += (double) inPilot * (now - lastSampleAt);
        }
        lastSampleAt = now;
    }

    /** {@code {kinds: {KIND: {count, mesos, receivers, perHour, mesosPerHour}}, playerHoursOnline, playerHoursInPilotMap, sinceHours}}. */
    public static synchronized Map<String, Object> snapshot(long now) {
        double hours = bootAt < 0 ? 0 : Math.max(0, now - bootAt) / 3_600_000.0;
        Map<String, Object> kinds = new LinkedHashMap<>();
        KINDS.forEach((name, k) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", k.count);
            m.put("mesos", k.mesos);
            m.put("receivers", k.receivers.size());
            m.put("perHour", hours > 0 ? k.count / hours : 0.0);
            m.put("mesosPerHour", hours > 0 ? k.mesos / hours : 0.0);
            kinds.put(name, m);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kinds", kinds);
        out.put("playerHoursOnline", playerMs / 3_600_000.0);
        out.put("playerHoursInPilotMap", pilotMs / 3_600_000.0);
        out.put("sinceHours", hours);
        return out;
    }

    /** Test seam. */
    public static synchronized void resetForTest() {
        KINDS.clear();
        bootAt = -1;
        lastSampleAt = -1;
        playerMs = 0;
        pilotMs = 0;
    }
}
