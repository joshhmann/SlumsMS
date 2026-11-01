package soloMapling.ArtificialPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.server.ExecutorServiceManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A login-time read that the bots' share of the pool refused (see {@link BotDbGate}).
 * <p>
 * b272: at 3,000 bots the boot fill's logins ran their buddy, guild and romance reads on virtual threads, where the
 * gate refuses instead of waiting. A refused read was logged once and the bot logged in without that state for the
 * whole run - an empty buddy list, no guild, no partner - and anything it then wrote about that state was written on
 * top of a state it had never read. A refused read no longer just ends: the bot goes on a short list and one sweeper
 * tries it again a few times on a bot-db platform thread, where the share is waited for instead of refused.
 * <p>
 * Nothing is written from the unread view while it waits: every buddy write re-reads its pair from the table before
 * it writes (see {@link BotBuddies}), so a refused read costs the bot its list for a moment, never a stored row. After
 * {@link #ATTEMPTS} tries it goes without, logged once, until its next login.
 */
public final class LoginLoadRetry {

    private static final Logger log = LoggerFactory.getLogger(LoginLoadRetry.class);

    /** How many times a refused login read is tried again before this run gives up on it. */
    static final int ATTEMPTS = 6;

    /** Between sweeps: long enough for the logins in flight to give the share back. */
    static final long GAP_MS = 10_000;

    /** {@link #GAP_MS}, shortened by tests so a sweep finds its bots due. */
    private static volatile long gapMs = GAP_MS;

    /** Tests: the gap between sweeps in milliseconds. */
    static void useGapForTest(long ms) {
        gapMs = Math.max(0, ms);
    }

    /** Reads one bot's state again; returns normally once it is known, throws while it still is not. */
    @FunctionalInterface
    public interface Load {
        void load(int botId) throws Exception;
    }

    private record Waiting(int attempts, long dueAt) {
    }

    private final String what;
    private final Load load;
    private final Map<Integer, Waiting> waiting = new ConcurrentHashMap<>();
    private final AtomicBoolean sweeping = new AtomicBoolean();
    /** Tests: where the sweeper and its attempts run (null = the server's timers and bot-db threads). */
    private volatile Executor scheduler;

    /**
     * @param what what could not be read, in the words of the log line: {@code "buddy list"}
     * @param load reads one bot's state again, for a bot that is still live
     */
    public LoginLoadRetry(String what, Load load) {
        this.what = what;
        this.load = load;
    }

    /** A login's read was refused: the bot's state stays unknown, and it is read again shortly. */
    public void refused(int botId) {
        waiting.compute(botId, (id, w) -> new Waiting(w == null ? 0 : w.attempts(), now() + gapMs));
        schedule();
    }

    /** How many login reads are still waiting for another try. */
    public int waiting() {
        return waiting.size();
    }

    /** The state was read (this login, or a retry): nothing left to try. */
    public void done(int botId) {
        waiting.remove(botId);
    }

    /** Tests: run the sweeps here instead of on the server's timers ({@code null} restores that). */
    void useSchedulerForTest(Executor testScheduler) {
        scheduler = testScheduler;
        waiting.clear();
        sweeping.set(false);
    }

    /** Tests: the real gap and the server's timers again, with nothing left waiting. */
    void resetForTest() {
        gapMs = GAP_MS;
        scheduler = null;
        waiting.clear();
        sweeping.set(false);
    }

    private void schedule() {
        if (!sweeping.compareAndSet(false, true)) {
            return;
        }
        Executor test = scheduler;
        if (test != null) {
            test.execute(this::sweep); // tests drive the sweeps: a sweep never arms another one there
            return;
        }
        try {
            ExecutorServiceManager.getScheduledExecutorService().schedule(
                    () -> ExecutorServiceManager.runBotDb(this::sweep), gapMs,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            sweeping.set(false);
            log.debug("The bot {} retry could not be scheduled", what, e);
        }
    }

    private void sweep() {
        boolean more;
        try {
            long now = now();
            for (Map.Entry<Integer, Waiting> entry : List.copyOf(waiting.entrySet())) {
                int botId = entry.getKey();
                Waiting w = entry.getValue();
                if (w.dueAt() > now) {
                    continue;
                }
                if (w.attempts() >= ATTEMPTS) {
                    waiting.remove(botId, w);
                    log.warn("Bot {}: the {} could not be read after {} tries (the bots' share of the database pool"
                            + " stayed busy); this bot goes without it until its next login", botId, what,
                            ATTEMPTS);
                    continue;
                }
                waiting.replace(botId, w, new Waiting(w.attempts() + 1, now + gapMs));
                plan(() -> attempt(botId));
            }
        } catch (RuntimeException e) {
            log.warn("Could not sweep the bot login reads for the {}", what, e);
        } finally {
            more = !waiting.isEmpty();
            sweeping.set(false);
        }
        if (more) {
            schedule(); // the entries left are not due yet: wake for them
        }
    }

    private void attempt(int botId) {
        if (soloMapling.server.BotShutdown.isQuiesced()) {
            return;
        }
        try {
            load.load(botId);
            waiting.remove(botId);
            log.info("Bot {}: the {} was read on a retry", botId, what);
        } catch (Exception e) {
            if (BotDbGate.refusedIn(e)) {
                return; // still busy: the entry keeps its place, and the next sweep tries again
            }
            waiting.remove(botId);
            log.warn("Bot {}: the {} could not be read; this bot goes without it until its next login", botId, what,
                    e);
        }
    }

    private void plan(Runnable task) {
        Executor test = scheduler;
        if (test != null) {
            test.execute(task);
            return;
        }
        ExecutorServiceManager.runBotDb(task);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
