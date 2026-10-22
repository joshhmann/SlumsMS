package soloMapling.ArtificialPlayer.BotAttackSystem;

import soloMapling.server.ExecutorServiceManager;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/*
 * Swings a bot again at the exact moment its last swing ends (1.5.0 attack pacing; ticket 32).
 *
 * The shared combat ticker visits every grinder every 250 ms, so a swing that ended between two visits waited for the
 * next one: a 660 ms swing became 750 ms, and bots swung slower than players. Each bot that swings on an observed map
 * now arms a one-shot "due tick" at the moment its swing ends (BotAttackDriver.swingEndsAt), which runs the bot's
 * combat tick there. While a due tick is armed and the swing is still playing, the shared sweep skips the bot: a
 * player cannot act mid-pose either (the walk lock already held it still), so those mid-pose visits were wasted work.
 * Unobserved bots never arm one (their combat tick does nothing) and keep the coarse tick.
 *
 * One clock per bot. Its ticks never overlap: the sweep tries the bot's lock and skips the bot when a due tick holds
 * it; a due tick waits for a sweep visit in progress (a combat tick takes well under a millisecond).
 *
 * On by default; -Dslums.botswingclock=false brings back the coarse ticker alone (needs a restart).
 */
public final class BotSwingClock {

    public static final String PROPERTY = "slums.botswingclock";

    /* Due ticks run so far (tests and !env perf). */
    public static final LongAdder DUE_TICKS = new LongAdder();

    /* Where a due tick is scheduled; tests swap in a manual one. */
    public interface Scheduler {
        void schedule(Runnable task, long delayMs);
    }

    private static final Scheduler POOL = (task, delayMs) ->
            ExecutorServiceManager.getScheduledExecutorService().schedule(task, delayMs, TimeUnit.MILLISECONDS);

    private static volatile Boolean enabledOverride; // tests

    /* A due tick further out than this is not armed: the coarse tick covers it (a turn or a swing is never this long). */
    static final long MAX_ARM_MS = 10_000;

    private final Scheduler scheduler;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile long armedAt; // epoch ms of the armed due tick, 0 = none

    public BotSwingClock() {
        this(POOL);
    }

    public BotSwingClock(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public static boolean enabled() {
        Boolean o = enabledOverride;
        return o != null ? o : Boolean.parseBoolean(System.getProperty(PROPERTY, "true"));
    }

    /* Tests: force the flag on or off (null = the system property again). */
    public static void setEnabledForTest(Boolean on) {
        enabledOverride = on;
    }

    /*
     * Whether the shared sweep should visit the bot now: not while a due tick is armed for a swing that is still
     * playing (swingEndsAt in the future). Anything else - no due tick, or the swing ended early (a keydown released
     * when its mobs died) - is visited as before.
     */
    public boolean sweepVisits(long now, long swingEndsAt) {
        return !enabled() || armedAt == 0 || now >= swingEndsAt;
    }

    /* The sweep's visit: runs tick unless a due tick is running it right now. */
    public boolean trySweep(Runnable tick) {
        if (!lock.tryLock()) {
            return false;
        }
        try {
            tick.run();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /*
     * Arms a due tick at dueAt (epoch ms) that runs tick, unless one is already armed for that moment, the moment has
     * passed, or it is further out than MAX_ARM_MS. A later arm replaces an earlier one: the replaced task does nothing.
     */
    public void armAt(long dueAt, long now, Runnable tick) {
        if (!enabled() || dueAt <= now || dueAt - now > MAX_ARM_MS || armedAt == dueAt) {
            return;
        }
        armedAt = dueAt;
        scheduler.schedule(() -> fire(dueAt, tick), dueAt - now);
    }

    /* The armed moment (epoch ms), or 0. */
    public long armedAt() {
        return armedAt;
    }

    /* Forget the armed due tick (the bot left its grind or the world): it does nothing when it fires. */
    public void disarm() {
        armedAt = 0;
    }

    private void fire(long dueAt, Runnable tick) {
        if (armedAt != dueAt) {
            return; // disarmed or replaced
        }
        lock.lock();
        try {
            if (armedAt != dueAt) {
                return;
            }
            armedAt = 0;
            DUE_TICKS.increment();
            tick.run();
        } catch (RuntimeException e) {
            // one bot's combat error never escapes into the shared scheduler
        } finally {
            lock.unlock();
        }
    }
}
