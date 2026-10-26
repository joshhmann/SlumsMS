package soloMapling.ArtificialPlayer.BotLifeSystem;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

/** One waiting decision and one active activity, owned by one bot instance. No worker or global registry. */
public final class LifeActivityQueue {
    public static final long MAX_WAIT_MS = 30_000;
    public enum Offer { QUEUED, REPLACED, DUPLICATE, STALE, FOREIGN, CLOSED, DISABLED }
    public enum RunStatus { IDLE, BUSY, STALE, CLOSED, ATTEMPTED }
    public enum End { NONE, COMPLETED, CANCELLED }

    /** Only its queue can create a request; a different bot instance cannot accept it. */
    public static final class Request {
        private final LifeActivityQueue owner;
        private final long number, createdAt, expiresAt, epoch;
        private final Result decision;

        private Request(LifeActivityQueue owner, long number, Result decision, long createdAt, long expiresAt) {
            this.owner = owner;
            this.number = number;
            this.decision = decision;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.epoch = owner.epoch;
        }

        public long number() { return number; }
    }

    public record Run(long number, RunStatus status, Optional<LifeAcceptance.Attempt> attempt) {}
    public record State(long waiting, long active, Activity activity, boolean closed, long lastHandled,
                        long lastEnded, End end) {}

    // Start callbacks can wait on game locks. Keep serialization without pinning Java 21 carriers.
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
    private volatile Request waiting;
    private long issued, handled, active, ended, epoch;
    private Activity activeActivity;
    private boolean closed;
    private End end = End.NONE;
    private Thread startingThread;
    /** 1.6.0 S8: told once when an active activity ends (outside the lock); see {@link LifeOutcomes}. */
    private volatile java.util.function.BiConsumer<Activity, End> onEnd;
    private Activity endedActivity;
    private End endedHow;

    /** Who hears an activity end (one listener, the bot's brain). */
    public void onEnd(java.util.function.BiConsumer<Activity, End> listener) { onEnd = listener; }

    private void notifyEnded() {
        Activity activity;
        End how;
        lock.lock();
        try {
            activity = endedActivity; how = endedHow;
            endedActivity = null; endedHow = null;
        } finally { lock.unlock(); }
        var listener = onEnd;
        if (activity != null && listener != null && !lock.isHeldByCurrentThread()) {
            try { listener.accept(activity, how); } catch (RuntimeException ignored) { /* never break the bot */ }
        }
    }

    public Request prepare(Result decision, long nowMs, long waitMs) {
        lock.lock();
        try {
            Objects.requireNonNull(decision);
            if (closed || issued == Long.MAX_VALUE) throw new IllegalStateException("activity queue is closed");
            if (waitMs <= 0 || waitMs > MAX_WAIT_MS || nowMs < 0 || nowMs > Long.MAX_VALUE - waitMs) {
                throw new IllegalArgumentException("invalid request lifetime");
            }
            return new Request(this, ++issued, decision, nowMs, nowMs + waitMs);
        } finally { lock.unlock(); }
    }

    /** A newer decision replaces the waiting one, never the active activity. */
    public Offer offer(Request request, long nowMs) {
        lock.lock();
        try {
            Objects.requireNonNull(request);
            if (request.owner != this) return Offer.FOREIGN;
            if (closed) return Offer.CLOSED;
            if (request.epoch != epoch) return Offer.STALE;
            if (request.number <= handled) return Offer.DUPLICATE;
            if (request.number != issued || stale(request, nowMs)) {
                handled = Math.max(handled, request.number);
                if (waiting == request) waiting = null;
                return Offer.STALE;
            }
            if (waiting == request) return Offer.DUPLICATE;
            boolean replaced = waiting != null;
            if (replaced) handled = Math.max(handled, waiting.number);
            waiting = request;
            return replaced ? Offer.REPLACED : Offer.QUEUED;
        } finally { lock.unlock(); }
    }

    /** Cheap tick gate. Scoring and world scans are never performed here. */
    public boolean hasWaiting() { return waiting != null; }

    /** Called only by the bot's normal update. Lock covers the start so overlapping/reentrant drains cannot start twice. */
    public Run drain(long nowMs, boolean enabled, BooleanSupplier stillFree,
                                   LifeAcceptance.Executor executor) {
        lock.lock();
        try {
            if (closed) return empty(0, RunStatus.CLOSED);
            if (active != 0 || startingThread != null) return empty(0, RunStatus.BUSY);
            Request request = waiting;
            if (request == null) return empty(0, RunStatus.IDLE);
            waiting = null;
            handled = Math.max(handled, request.number); // consume before any callback, even one that throws
            if (request.epoch != epoch || request.number != issued || stale(request, nowMs)) {
                return empty(request.number, RunStatus.STALE);
            }
            startingThread = Thread.currentThread();
            try {
                var attempt = LifeAcceptance.tryStart(request.decision, enabled, stillFree, executor);
                if (closed) return empty(request.number, RunStatus.CLOSED);
                if (attempt.status() == LifeAcceptance.Status.ACCEPTED) {
                    active = request.number;
                    activeActivity = attempt.accepted().orElseThrow().activity();
                }
                return new Run(request.number, RunStatus.ATTEMPTED, Optional.of(attempt));
            } finally {
                startingThread = null;
            }
        } finally { lock.unlock(); }
    }

    /** The concrete start hook is allowed only inside this bot's drain callback. */
    public boolean isStartingHere() {
        lock.lock();
        try {
            return startingThread == Thread.currentThread();
        } finally { lock.unlock(); }
    }

    /** Old completion/cancellation callbacks cannot release a newer activity. */
    public boolean finish(long number, End outcome) {
        boolean finished = false;
        lock.lock();
        try {
            if (outcome == null || outcome == End.NONE) throw new IllegalArgumentException("missing activity outcome");
            if (number == 0 || number != active) return false;
            ended = active;
            end = outcome;
            endedActivity = activeActivity;
            endedHow = outcome;
            active = 0;
            activeActivity = null;
            finished = true;
            return true;
        } finally {
            lock.unlock();
            if (finished && !lock.isHeldByCurrentThread()) notifyEnded();
        }
    }

    public boolean cancelWaiting(Request request) {
        lock.lock();
        try {
            if (waiting != request || request == null) return false;
            handled = Math.max(handled, request.number);
            waiting = null;
            return true;
        } finally { lock.unlock(); }
    }

    /** A phase/owner change invalidates even requests prepared before it but submitted afterwards. */
    public void invalidateWaiting() {
        lock.lock();
        try {
            if (epoch == Long.MAX_VALUE) {
                close();
                return;
            }
            epoch++;
            handled = Math.max(handled, issued);
            waiting = null;
        } finally { lock.unlock(); }
    }

    public void close() {
        lock.lock();
        try {
            closed = true;
            waiting = null;
            if (active != 0) finish(active, End.CANCELLED);
        } finally { lock.unlock(); }
        if (!lock.isHeldByCurrentThread()) notifyEnded();
    }

    public State state() {
        lock.lock();
        try {
            return new State(waiting == null ? 0 : waiting.number, active, activeActivity, closed, handled, ended, end);
        } finally { lock.unlock(); }
    }

    private static boolean stale(Request request, long nowMs) {
        return nowMs < request.createdAt || nowMs >= request.expiresAt;
    }

    private static Run empty(long number, RunStatus status) { return new Run(number, status, Optional.empty()); }
}
