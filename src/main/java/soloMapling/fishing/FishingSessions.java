package soloMapling.fishing;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntUnaryOperator;

/** One bounded, cancellable timer per actor. Inventory effects stay behind Actor, including bot characters. */
public final class FishingSessions {
    public static final long INTERVAL_MS = 90_000;
    public record Reward(int itemId, String name, int weight) {
        public Reward {
            if (itemId < 2_000_000 || itemId >= 5_000_000 || name == null || name.isBlank() || weight <= 0)
                throw new IllegalArgumentException("invalid fishing reward");
        }
    }
    public record Position(int world, int channel, Object map, int x, int y, int chair, boolean eligible) { }
    public interface Actor {
        int id();
        default Object identity() { return this; }
        Position position();
        boolean give(Reward reward);
        void caught(Reward reward, long sequence);
        default void fullBag() { }
    }
    public enum Start { STARTED, ALREADY_FISHING, REFUSED, FULL }
    public enum Tick { WAIT, CAUGHT, FULL_BAG, CANCELLED, FAILED }
    private static final class Session {
        final Actor actor;
        final Position origin;
        final ReentrantLock lock = new ReentrantLock();
        long next, last, sequence;
        Session(Actor actor, Position origin, long now) {
            this.actor = actor; this.origin = origin; last = now; next = Math.addExact(now, INTERVAL_MS);
        }
    }
    private final ConcurrentHashMap<Integer, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger slots = new AtomicInteger();
    private final int limit;
    private final List<Reward> rewards;
    private final int total;
    private final IntUnaryOperator roll;

    public FishingSessions(int limit, List<Reward> rewards, IntUnaryOperator roll) {
        if (limit < 1 || rewards == null || rewards.isEmpty()) throw new IllegalArgumentException("empty fishing policy");
        this.limit = limit; this.rewards = List.copyOf(rewards); this.roll = Objects.requireNonNull(roll);
        total = rewards.stream().mapToInt(Reward::weight).reduce(0, Math::addExact);
    }
    public Start start(Actor actor, long now) {
        if (now < 0 || now > Long.MAX_VALUE - INTERVAL_MS) return Start.REFUSED;
        Position position = actor.position();
        if (actor.id() <= 0 || position == null || !position.eligible() || position.map() == null
                || position.chair() != 3011000) return Start.REFUSED;
        Session old = sessions.get(actor.id());
        if (old != null) {
            if (old.actor.identity() == actor.identity()) return Start.ALREADY_FISHING;
            cancel(actor.id(), old.actor.identity());
        }
        int count;
        do { count = slots.get(); if (count >= limit) return Start.FULL; }
        while (!slots.compareAndSet(count, count + 1));
        if (sessions.putIfAbsent(actor.id(), new Session(actor, position, now)) != null) {
            slots.decrementAndGet(); return Start.ALREADY_FISHING;
        }
        return Start.STARTED;
    }
    public boolean active(int id) { return sessions.containsKey(id); }
    public boolean active(int id, Object identity) {
        Session s = sessions.get(id); return s != null && s.actor.identity() == identity;
    }
    public int size() { return sessions.size(); }
    public void cancel(int id) { cancel(id, null); }
    public void cancel(int id, Object identity) {
        Session session = sessions.get(id);
        if (session == null) return;
        session.lock.lock();
        try { if (identity == null || session.actor.identity() == identity) remove(id, session); } finally { session.lock.unlock(); }
    }
    public void cancelWorld(int world) {
        sessions.forEach((id, s) -> { if (s.origin.world() == world) cancel(id); });
    }
    private void remove(int id, Session s) { if (sessions.remove(id, s)) slots.decrementAndGet(); }
    public void tickWorld(int world, long now) {
        sessions.forEach((id, s) -> { if (s.origin.world() == world) tick(id, now); });
    }
    public Tick tick(int id, long now) {
        Session s = sessions.get(id);
        if (s == null) return Tick.CANCELLED;
        s.lock.lock();
        try {
            if (sessions.get(id) != s) return Tick.CANCELLED;
            Position p = s.actor.position();
            if (now < s.last || now > Long.MAX_VALUE - INTERVAL_MS || !p.eligible()
                    || p.world() != s.origin.world() || p.channel() != s.origin.channel()
                    || p.map() != s.origin.map() || p.x() != s.origin.x() || p.y() != s.origin.y()
                    || p.chair() != s.origin.chair()) {
                remove(id, s); return Tick.CANCELLED;
            }
            s.last = now;
            if (now < s.next) return Tick.WAIT;
            // Spend this timer before inventory effects or callbacks. A stalled tick never catches up in a burst.
            s.next = now + INTERVAL_MS;
            long sequence = ++s.sequence;
            int value = roll.applyAsInt(total);
            if (value < 0 || value >= total) throw new IllegalArgumentException("fishing roll outside policy");
            Reward chosen = null;
            for (Reward reward : rewards) { if (value < reward.weight()) { chosen = reward; break; } value -= reward.weight(); }
            if (!s.actor.give(chosen)) { s.actor.fullBag(); return Tick.FULL_BAG; }
            s.actor.caught(chosen, sequence);
            return Tick.CAUGHT;
        } catch (RuntimeException failure) {
            remove(id, s); return Tick.FAILED;
        } finally { s.lock.unlock(); }
    }
}
