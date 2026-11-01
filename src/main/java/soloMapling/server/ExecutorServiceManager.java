package soloMapling.server;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ExecutorServiceManager {
    //    private static final ExecutorService executorService = Executors.newCachedThreadPool(); // is this causing error 38 / dc to login?
    // Fable Phase 2 (F12): 100 -> 8. Its only consumers are chat-dispatch matching and
    // GM command bodies - a bounded handful of quick tasks. Blocking bulk work
    // (spawn waves, choreography) already runs on the virtual-thread executor.
    private static final ExecutorService executorService = Executors.newFixedThreadPool(8);
    private static final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(10);
    private static final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    // 1.6.2 (live 3k DB livelock): bot work that may have to wait for the bots' share of the connection pool
    // (a login, the autosave sweep) must not run on a virtual thread. On Java 21 a virtual thread that blocks
    // inside a synchronized block pins its carrier, and BotDbGate cannot see monitors, so it refuses to make a
    // virtual thread wait; a platform thread may wait there without pinning anything.
    private static final java.util.concurrent.atomic.AtomicInteger botDbThreads = new java.util.concurrent.atomic.AtomicInteger();
    private static final ExecutorService botDbExecutor = Executors.newFixedThreadPool(8, task -> {
        Thread t = new Thread(task, "bot-db-" + botDbThreads.incrementAndGet());
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

    public static void scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        scheduledExecutorService.scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    public static void shutdown() {
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
