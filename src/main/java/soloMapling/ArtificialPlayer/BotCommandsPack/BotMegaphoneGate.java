package soloMapling.ArtificialPlayer.BotCommandsPack;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * One server-wide budget for the megaphones bots use on their own (the ambient megaphone ticker, scrolling bots'
 * sale ads): at most one every {@code -Dsolomapling.botMegaphoneGapSeconds} (default 120). With hundreds of bots
 * online the pink lines otherwise flood every player's chat. A bot that loses the race just skips its megaphone.
 * GM commands that make a bot shout do not go through the gate.
 */
public final class BotMegaphoneGate {

    static final long DEFAULT_GAP_SECONDS = 120;

    private static final AtomicLong lastAt = new AtomicLong(Long.MIN_VALUE / 2);
    private static volatile LongSupplier clock = System::currentTimeMillis;

    private BotMegaphoneGate() {
    }

    /** Whether a bot may megaphone now; claims the slot when it may. */
    public static boolean tryAcquire() {
        long now = clock.getAsLong();
        long gapMs = TimeUnit.SECONDS.toMillis(Math.max(0, Long.getLong("solomapling.botMegaphoneGapSeconds",
                DEFAULT_GAP_SECONDS)));
        while (true) {
            long last = lastAt.get();
            if (now - last < gapMs) {
                return false;
            }
            if (lastAt.compareAndSet(last, now)) {
                return true;
            }
        }
    }

    /**
     * Notes a megaphone-like line that went out without asking (1.5.0: a player's gachapon notice), so the next bot
     * megaphone waits a full gap after it. Never refuses anything.
     */
    public static void note() {
        lastAt.accumulateAndGet(clock.getAsLong(), Math::max);
    }

    /** Test seam: a fixed clock (null restores the system clock) and a fresh budget. */
    static void useClockForTest(LongSupplier testClock) {
        clock = testClock == null ? System::currentTimeMillis : testClock;
        lastAt.set(Long.MIN_VALUE / 2);
    }
}
