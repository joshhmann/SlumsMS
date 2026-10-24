package soloMapling.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The order and the time limits of the writes a server shutdown makes (1.5.0 release rehearsal).
 * <p>
 * Real players are saved first, and the shutdown waits for those saves to finish. Only then does any bot work
 * start, and all of it runs under a time budget: in the rehearsal on a copy of live the bot sweep waited on the
 * bots' share of the connection pool for every one of its bots, ran past systemd's five minutes and was killed.
 * The real players' final save came after the bot sweep then, so a kill there could have lost it. A bot that is not
 * written in time comes back from its last save; a real player is never made to wait for a bot.
 */
public final class ShutdownSaves {

    private static final Logger log = LoggerFactory.getLogger(ShutdownSaves.class);

    /** How long the shutdown waits for the real players' saves ({@code -Dslums.shutdown.playerBudgetMs}). */
    public static final long PLAYER_BUDGET_MS = Long.getLong("slums.shutdown.playerBudgetMs", 60_000L);

    /** The whole bot phase: quiesce, drain, sweep, sessions ({@code -Dslums.shutdown.botBudgetMs}). */
    public static final long BOT_BUDGET_MS = Long.getLong("slums.shutdown.botBudgetMs", 60_000L);

    /** Threads saving real players at once; a player save holds one or two pooled connections. */
    public static final int PLAYER_SAVERS = 4;

    /** Threads writing bots at once in the sweep. Players are done by then, so this is the pool's to give. */
    public static final int BOT_WRITERS = 4;

    /**
     * Past the bot budget, how long the shutdown still gives the bot phase to return (a hook or a write that is
     * already in flight) before it moves on without it.
     */
    static final long BOT_GRACE_MS = 5_000;

    /** The two writing steps of a shutdown. */
    public interface Steps {
        /** Saves every real player and returns once every save is done or the budget is spent. */
        void saveRealPlayers(long budgetMs);

        /** All bot work of the shutdown: stop their activity, then write them, within the budget. */
        void saveBots(long budgetMs);
    }

    private ShutdownSaves() {
    }

    /** Runs the steps in order: the real players' saves complete before any bot work begins. */
    public static void run(Steps steps) {
        run(steps, PLAYER_BUDGET_MS, BOT_BUDGET_MS, BOT_GRACE_MS);
    }

    static void run(Steps steps, long playerBudgetMs, long botBudgetMs, long graceMs) {
        long started = System.nanoTime();
        try {
            steps.saveRealPlayers(playerBudgetMs);
        } catch (RuntimeException e) {
            log.error("Saving the players at shutdown failed", e);
        }
        log.info("Shutdown: players saved in {} ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        long botsStarted = System.nanoTime();
        boolean finished = runWithin("bot-shutdown", () -> steps.saveBots(botBudgetMs), botBudgetMs + graceMs);
        long botsMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - botsStarted);
        if (finished) {
            log.info("Shutdown: bot phase done in {} ms", botsMs);
        } else {
            log.warn("Shutdown: the bot phase was still running after {} ms (budget {} ms); going on without it - bots"
                    + " not written resume from their last save", botsMs, botBudgetMs);
        }
    }

    /**
     * Applies {@code action} to every item on {@code threads} platform threads and waits until all are done or the
     * budget is spent. Returns the items whose action had not finished by then (still running or never started).
     */
    public static <T> List<T> forEachWithin(Collection<T> items, Consumer<T> action, int threads, long budgetMs) {
        List<T> list = new ArrayList<>(items);
        if (list.isEmpty()) {
            return List.of();
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(threads, list.size())), r -> {
            Thread t = new Thread(r, "shutdown-save");
            t.setDaemon(true);
            return t;
        });
        List<Future<?>> futures = new ArrayList<>();
        for (T item : list) {
            futures.add(pool.submit(() -> action.accept(item)));
        }
        pool.shutdown();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, budgetMs));
        List<T> late = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            try {
                futures.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                late.add(list.get(i));
            } catch (java.util.concurrent.ExecutionException e) {
                log.error("A save at shutdown failed", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                late.add(list.get(i));
            }
        }
        return late;
    }

    /** Runs work on its own daemon thread and waits for it at most {@code limitMs}; true when it finished. */
    static boolean runWithin(String name, Runnable work, long limitMs) {
        Thread t = new Thread(() -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                log.error("{} failed", name, e);
            }
        }, name);
        t.setDaemon(true);
        t.start();
        try {
            t.join(Math.max(1, limitMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !t.isAlive();
    }
}
