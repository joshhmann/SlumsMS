package soloMapling.server;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ExecutorServiceManager {
    //    private static final ExecutorService executorService = Executors.newCachedThreadPool(); // is this causing error 38 / dc to login?
    // Fable Phase 2 (F12): 100 -> 8. Its only consumers are chat-dispatch matching and
    // GM command bodies - a bounded handful of quick tasks. Blocking bulk work
    // (spawn waves, choreography) already runs on the virtual-thread executor.
    private static final ExecutorService executorService = Executors.newFixedThreadPool(8);
    private static final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(10);
    private static final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    // 1.6.3 (the live 3k DB livelock; the 1.6.2 line never shipped): bot work that may wait for the bots' share
    // (a login, the autosave sweep) must not run on a virtual thread. On Java 21 a virtual thread that blocks
    // inside a synchronized block pins its carrier, and BotDbGate cannot see monitors, so it refuses to make a
    // virtual thread wait; a platform thread may wait there without pinning anything.
    private static final java.util.concurrent.atomic.AtomicInteger botDbThreads = new java.util.concurrent.atomic.AtomicInteger();
    private static final ExecutorService botDbExecutor = Executors.newFixedThreadPool(8, task -> {
        Thread t = new Thread(task, "bot-db-" + botDbThreads.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    // 1.6.3 (b269 review): a bot leaving the world writes its row, and a refused write used to settle the bot's
    // holdings onto the map instead. The despawn therefore runs here, on a platform thread that may wait for the
    // bots' share of the pool; it is its own pool so a session end's departures do not queue behind logins, and
    // so the autosave sweep keeps its own threads.
    private static final AtomicInteger botLogoutThreads = new AtomicInteger();
    private static final ExecutorService botLogoutExecutor = Executors.newFixedThreadPool(4, task -> {
        Thread t = new Thread(task, "bot-logout-" + botLogoutThreads.incrementAndGet());
        t.setDaemon(true);
        return t;
    });


    // Private constructor to prevent instantiation

    private ExecutorServiceManager() {
    }

    public static ExecutorService getExecutorService() {
        return executorService;
    }

    public static ScheduledExecutorService getScheduledExecutorService() {
        return scheduledExecutorService;
    }

    public static ExecutorService getVirtualThreadExecutorService() {
        return virtualThreadExecutor;
    }

    /**
     * Use this for multithreaded processing. Best suited for lightweight, concurrent tasks.
     * Example
     * runAsync(() -> methodName(parameter));
     */
    public static void runAsync(Runnable task) {
        virtualThreadExecutor.submit(task);
    }

    /**
     * Bot work that may wait for the bots' share of the connection pool: a bot login, the autosave sweep.
     * Runs on a platform thread, where waiting costs nobody's carrier (see {@link soloMapling.ArtificialPlayer.BotDbGate}).
     */
    public static void runBotDb(Runnable task) {
        botDbExecutor.execute(task);
    }

    /**
     * A bot leaving the world: it writes its row, so it may have to wait for the bots' share of the pool
     * ({@link soloMapling.ArtificialPlayer.BotDbGate}). Runs on a platform thread.
     */
    public static void runBotLogout(Runnable task) {
        botLogoutExecutor.execute(task);
    }

    /**
     * Runs {@code task} on a bot-logout thread and waits for it. A virtual thread that did the work itself would
     * be refused by the gate instead of waiting (see {@link soloMapling.ArtificialPlayer.BotDbGate}), which on a
     * despawn loses the bot's row; parking it here makes the wait happen where waiting is allowed.
     */
    public static void callBotLogout(Runnable task) {
        if (!Thread.currentThread().isVirtual()) {
            task.run();
            return;
        }
        try {
            botLogoutExecutor.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a bot logout thread", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    public static void scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        scheduledExecutorService.scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    public static void shutdown() {
        shutdownExecutor(botLogoutExecutor);
        shutdownExecutor(botDbExecutor);
        shutdownExecutor(executorService);
        shutdownExecutor(scheduledExecutorService);
    }

    private static void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    System.err.println("Executor did not terminate");
                }
            }
        } catch (InterruptedException ie) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
