package soloMapling.ArtificialPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Database writes that must not run on the caller's thread (a Netty IO thread, or one holding a guild's member lock)
 * and must land in the order they were asked for: writes with the same key run one after another, in submission order,
 * on the given executor; writes with different keys run independently. A failing write is logged and does not stop
 * the ones queued after it. Nothing here ever waits: {@link #submit} only queues.
 */
public final class SerialWrites {

    private static final Logger log = LoggerFactory.getLogger(SerialWrites.class);
    private static final CompletableFuture<Void> DONE = CompletableFuture.completedFuture(null);

    private final Executor executor;
    private final Map<Object, CompletableFuture<Void>> tails = new HashMap<>();

    public SerialWrites(Executor executor) {
        this.executor = executor;
    }

    /** Queues {@code task} after every earlier task of the same key. */
    public void submit(Object key, Runnable task) {
        CompletableFuture<Void> previous;
        CompletableFuture<Void> mine = new CompletableFuture<>();
        synchronized (tails) {
            previous = tails.getOrDefault(key, DONE);
            tails.put(key, mine);
        }
        previous.whenComplete((v, t) -> {
            try {
                executor.execute(() -> run(key, task, mine));
            } catch (RuntimeException rejected) {
                log.warn("A queued write could not be started", rejected);
                finish(key, mine);
            }
        });
    }

    /** How many keys have writes queued or running (tests and GM read-outs). */
    public int pendingKeys() {
        synchronized (tails) {
            return tails.size();
        }
    }

    private void run(Object key, Runnable task, CompletableFuture<Void> mine) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.warn("A queued write failed", e);
        } finally {
            finish(key, mine);
        }
    }

    private void finish(Object key, CompletableFuture<Void> mine) {
        synchronized (tails) {
            tails.remove(key, mine);
        }
        mine.complete(null);
    }
}
