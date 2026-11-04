package soloMapling.ArtificialPlayer.BotSocialSystem;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * The one clock and the one random source for the social layer (traits, emotions, needs, bond axes, social
 * actions). Production reads the wall clock and {@link ThreadLocalRandom}. A test or a simulated-days run calls
 * {@link #useForTest} to drive time by hand and make every draw reproducible.
 *
 * <p>Seeded draws are one stream per (bot, purpose), so a bot's decisions do not change when another bot's
 * decisions are added, removed or reordered. Social code takes {@code now()} and {@code random(...)} from here and
 * never calls {@code System.currentTimeMillis()} or {@code ThreadLocalRandom} directly.
 */
public final class SocialSim {

    private static volatile LongSupplier clock = System::currentTimeMillis;
    private static volatile boolean seeded;
    private static volatile long seed;
    private static final ConcurrentHashMap<Long, Random> streams = new ConcurrentHashMap<>();

    private SocialSim() {
    }

    /** Milliseconds since the epoch, or the test clock. */
    public static long now() {
        return clock.getAsLong();
    }

    /**
     * The random source for one bot and one purpose (for example {@code "talk-pick"}). Not seeded: the calling
     * thread's {@link ThreadLocalRandom}. Seeded: a stable per-(bot, purpose) stream. A seeded stream is not
     * thread-safe on its own, so callers must draw from it on the bot's own thread or under their own lock.
     */
    public static Random random(long botId, String purpose) {
        if (!seeded) {
            return ThreadLocalRandom.current();
        }
        long key = mix(botId, purpose.hashCode());
        return streams.computeIfAbsent(key, k -> new Random(mix(seed, k)));
    }

    /** Test and simulation hook: a manual clock and a fixed seed. Pair every call with {@link #reset()}. */
    public static void useForTest(LongSupplier testClock, long testSeed) {
        clock = testClock;
        seed = testSeed;
        streams.clear();
        seeded = true;
    }

    /** Back to the wall clock and unseeded draws. */
    public static void reset() {
        clock = System::currentTimeMillis;
        seeded = false;
        streams.clear();
    }

    /** SplitMix64-style finaliser: spreads two longs into one well-mixed long. */
    private static long mix(long a, long b) {
        long z = a * 0x9E3779B97F4A7C15L + b;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
