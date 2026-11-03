package soloMapling.mechanics;

import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Stage;
import soloMapling.mechanics.MechanicsModel.Tier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import static soloMapling.BotLogger.log;

/**
 * Ticket 164, phase 2 of {@code SIMULATED-RUNS-PLAN.md}: how many <b>bot-only</b> runs may play for real at once.
 *
 * <p>Today every bot-only run (Kerning's bot lobby, the content lobbies) holds its bots, ticks four times a second and
 * keeps its maps at full physics for as long as the content's clock runs. That is what proves a row works with no
 * human, and it is what costs: held bots times runs times tiers. The owner's wording (2026-11-03): "maybe 1 or 2 runs
 * can have real bots running through while other runs are simulated". This class is the quota half of that: a global
 * pool of {@code N} leases, {@code -Dslums.mechanics.realruns=N} ({@link Mechanics#REAL_RUNS_FLAG}).
 *
 * <p><b>Inert by default.</b> With the property unset (or negative) every bot-only run is admitted and <em>no lease is
 * made</em>: {@link #held()} stays 0, nothing is tracked, nothing is released, and the lobbies and staging behave
 * exactly as they did before this class existed. Only a non-negative {@code N} changes anything, and then it only
 * <em>refuses</em> a bot-only start that finds the pool full: a refused run is simply not made.
 *
 * <p><b>Who takes a lease.</b> Only a run that no human is in and that nobody asked to watch: the KPQ bot lobby
 * ({@link Origin#KPQ_LOBBY}), the content lobbies ({@link Origin#CONTENT_LOBBY}) and a bot-only party that reached an
 * event some other way ({@link Origin#BOT_INSTANCE}). A run with a real player in it ({@link Origin#HUMAN_PARTY},
 * {@link Origin#HUMAN_EXPEDITION}) and a GM's or QA's staged run ({@link Origin#STAGED}) are always real, always
 * admitted and never counted: {@link #exempt}.
 *
 * <p><b>Lifetime.</b> A lease is taken <em>before anything is created</em> (no party, no spawned bot, no instance), is
 * carried by the run's {@link Admission} and is released in exactly one place, {@code MechanicsRuns.finish}, which every
 * end of a run passes through - plus the starts that never get that far, which release it where they stop. A lease is
 * never promoted or demoted while a run lives: a run is real from its admission to its end. As a backstop against a
 * run that somehow never ends, a lease expires {@link #ttlMs after the run's own clock plus its after-clear budget}
 * and says so loudly; a leaked lease would otherwise shrink the quota for good.
 *
 * <p><b>Not here (phase 2 scheduling).</b> Which content gets the free lease (rotation), the "below five real samples,
 * wait rather than fake" rule and the simulated run itself are not built; a refused run is just not started.
 */
public final class RealRunPool {

    /** Who is asking. Carried by the {@link Admission} into the run, so a record can say how the run came to be. */
    public enum Origin {
        /** {@link KpqLobby}: bots standing at Lakelis form a party. */
        KPQ_LOBBY,
        /** {@link ContentLobby}: a party of fresh bots is spawned for a row. */
        CONTENT_LOBBY,
        /** A GM's {@code !mechanics} or a QA scenario. Always real, never counted, never refused. */
        STAGED,
        /** A real player's party started the event ({@code EventManager.startInstance}). */
        HUMAN_PARTY,
        /** A real player's expedition started the event. */
        HUMAN_EXPEDITION,
        /** A party with no human in it reached an event by a path that is not a lobby: it takes a lease like one. */
        BOT_INSTANCE;

        /** Whether this origin is a bot-only run the quota governs. */
        public boolean governed() {
            return this == KPQ_LOBBY || this == CONTENT_LOBBY || this == BOT_INSTANCE;
        }
    }

    /** One taken place in the pool: held by a run from its admission to its end. */
    private static final class Lease {
        final String contentId;
        final Origin origin;
        final long grantedAtMs;
        final long expiresAtMs;
        final AtomicBoolean released = new AtomicBoolean();

        Lease(String contentId, Origin origin, long grantedAtMs, long expiresAtMs) {
            this.contentId = contentId;
            this.origin = origin;
            this.grantedAtMs = grantedAtMs;
            this.expiresAtMs = expiresAtMs;
        }
    }

    /**
     * The answer to "may this run be made?", and the thing that travels with the run afterwards. {@link #release()} is
     * idempotent and may be called from every place that gives the run up.
     */
    public static final class Admission {
        private final boolean granted;
        private final Origin origin;
        private final Lease lease;
        private final RealRunPool pool;
        private final String why;
        private final boolean announce;

        private final boolean rotation;

        private Admission(boolean granted, Origin origin, Lease lease, RealRunPool pool, String why, boolean announce,
                          boolean rotation) {
            this.granted = granted;
            this.origin = origin;
            this.lease = lease;
            this.pool = pool;
            this.why = why;
            this.announce = announce;
            this.rotation = rotation;
        }

        /** Whether a free place was refused because another content has waited longer, rather than because none is free. */
        public boolean byRotation() {
            return rotation;
        }

        public boolean granted() {
            return granted;
        }

        public Origin origin() {
            return origin;
        }

        /** Whether this admission occupies a place in the pool (never true for an exempt run or an unlimited pool). */
        public boolean leased() {
            return lease != null && !lease.released.get();
        }

        /** Why a run was refused, or null when it was admitted. */
        public String why() {
            return why;
        }

        /**
         * Whether the refusal is worth a log line: the first for this content and origin, then one every
         * {@link #LOG_GAP_MS}. A full pool is refused every lobby tick; the log should not say so every minute.
         */
        public boolean announce() {
            return announce;
        }

        /** Gives the place back. Safe to call any number of times, from any thread; a no-op for an unleased run. */
        public void release() {
            if (lease != null && pool != null) {
                pool.release(lease);
            }
        }
    }

    /** The quota's refusal is logged once per content and origin per this long. */
    static final long LOG_GAP_MS = 10 * 60_000L;
    /** Added to a run's own clock before its lease may be reaped: the spawn, the walk to the NPC and the staging. */
    static final long TTL_MARGIN_MS = 2 * 60_000L;
    /** How long an ask counts as "wants a real run": three lobby ticks (the lobbies tick once a minute). */
    static final long WANT_WINDOW_MS = 3 * 60_000L;

    private static final RealRunPool INSTANCE = new RealRunPool(Mechanics::realRunCapacity, System::currentTimeMillis);

    public static RealRunPool get() {
        return INSTANCE;
    }

    private final IntSupplier capacity;
    private final LongSupplier clock;
    private final List<Lease> leases = new ArrayList<>();
    private final Map<String, Long> lastAnnounced = new HashMap<>();
    private final Map<RunKey, Long> lastReal = new HashMap<>();
    private final Map<RunKey, Long> wanting = new HashMap<>();

    /** Tests build their own: a quota and a clock they control. */
    RealRunPool(IntSupplier capacity, LongSupplier clock) {
        this.capacity = capacity;
        this.clock = clock;
    }

    /** An always-real run that is never counted and never refused (a human's run, a GM's or QA's staged run). */
    public static Admission exempt(Origin origin) {
        return new Admission(true, origin, null, null, null, false, false);
    }

    /**
     * Asks whether a bot-only run of {@code content} (at {@link Tier#NORMAL}) may be made now. See
     * {@link #admit(Origin, Content, Tier, long)}.
     */
    public synchronized Admission admit(Origin origin, Content content, long budgetMs) {
        return admit(origin, content, Tier.NORMAL, budgetMs);
    }

    /**
     * Asks whether a bot-only run of {@code content} at {@code tier} may be made now, for the one that has a lease taken
     * for it when the pool counts. {@code budgetMs} is the run's own time budget (0: the content's).
     *
     * <p><b>Rotation.</b> A free place does not simply go to whoever asks first. Every ask is remembered for
     * {@link #WANT_WINDOW_MS}; a free place goes to the <em>stalest</em> of the {@link RunKey}s that have asked in that
     * window - the one whose last real pass (a lease granted, or a human's or a GM's run, {@link #notePass}) is oldest -
     * and any other asker is refused with that as the reason. A key that has never been real is the stalest of all. Ties
     * break on content id, then tier ({@link #stalest}), so the order never depends on who asked first. This is what lets
     * every row have a real pass eventually while the lobbies only ever ask one at a time.
     */
    public synchronized Admission admit(Origin origin, Content content, Tier tier, long budgetMs) {
        int cap = capacity.getAsInt();
        if (cap < 0) {
            return new Admission(true, origin, null, null, null, false, false); // phase 1: nothing counted, nothing made
        }
        long now = clock.getAsLong();
        reap(now);
        RunKey key = new RunKey(content.id(), tier);
        wanting.values().removeIf(at -> now - at > WANT_WINDOW_MS);
        wanting.put(key, now);
        if (leases.size() >= cap) {
            String why = cap == 0
                    ? "the real-run pool admits none (-D" + Mechanics.REAL_RUNS_FLAG + "=0)"
                    : "the real-run pool is full (" + leases.size() + " of " + cap + " held: " + holders(now) + ")";
            return new Admission(false, origin, null, null, why, announceDue(origin, content, now), false);
        }
        RunKey first = stalest(wanting.keySet(), k -> lastReal.getOrDefault(k, 0L));
        if (!first.equals(key)) {
            String why = "a place is free but " + first + " has waited longer for a real run (last real "
                    + (lastReal.containsKey(first) ? (now - lastReal.get(first)) / 60_000 + " min ago" : "never") + ")";
            return new Admission(false, origin, null, null, why, announceDue(origin, content, now), true);
        }
        wanting.remove(key);
        lastReal.put(key, now);
        Lease lease = new Lease(content.id(), origin, now, now + ttlMs(content, budgetMs));
        leases.add(lease);
        return new Admission(true, origin, lease, this, null, false, false);
    }

    /**
     * A content and tier: what rotation orders, and what a simulated run is sampled by. The unit "a row gets a real pass".
     */
    public record RunKey(String contentId, Tier tier) {
        @Override
        public String toString() {
            return contentId + (tier == Tier.NORMAL ? "" : "/" + tier);
        }
    }

    /**
     * The key that has waited longest for a real run: the smallest {@code lastReal}, ties broken by content id, then
     * tier. Pure: no clock, no order of arrival.
     */
    static RunKey stalest(java.util.Collection<RunKey> candidates, java.util.function.ToLongFunction<RunKey> lastReal) {
        RunKey best = null;
        for (RunKey k : candidates) {
            if (best == null || compare(k, best, lastReal) < 0) {
                best = k;
            }
        }
        return best;
    }

    private static int compare(RunKey a, RunKey b, java.util.function.ToLongFunction<RunKey> lastReal) {
        int byTime = Long.compare(lastReal.applyAsLong(a), lastReal.applyAsLong(b));
        if (byTime != 0) {
            return byTime;
        }
        int byContent = a.contentId().compareTo(b.contentId());
        return byContent != 0 ? byContent : a.tier().compareTo(b.tier());
    }

    /**
     * A real run that holds no lease started (a human's party, a GM's or QA's staged run): that row had a real pass now, so
     * it is not the stalest. Nothing is tracked while the pool is unlimited.
     */
    public synchronized void notePass(String contentId, Tier tier) {
        if (capacity.getAsInt() < 0) {
            return;
        }
        lastReal.put(new RunKey(contentId, tier), clock.getAsLong());
    }

    /** When this key last had a real pass (a lease granted or a noted pass), or -1. Always -1 while the pool is unlimited. */
    public synchronized long lastReal(RunKey key) {
        return lastReal.getOrDefault(key, -1L);
    }

    private boolean announceDue(Origin origin, Content content, long now) {
        String key = origin + ":" + content.id();
        Long last = lastAnnounced.get(key);
        if (last != null && now - last < LOG_GAP_MS) {
            return false;
        }
        lastAnnounced.put(key, now);
        return true;
    }

    private synchronized void release(Lease lease) {
        if (lease.released.compareAndSet(false, true)) {
            leases.remove(lease);
        }
    }

    /** Takes back every lease whose run should have ended by now, and says so: a leaked lease shrinks the quota for good. */
    private void reap(long now) {
        for (Lease l : List.copyOf(leases)) {
            if (now >= l.expiresAtMs && l.released.compareAndSet(false, true)) {
                leases.remove(l);
                log("[Mechanics] real-run lease for " + l.contentId + " (" + l.origin + ") expired after "
                        + (now - l.grantedAtMs) / 60_000 + " min: its run never released it");
            }
        }
    }

    private String holders(long now) {
        StringBuilder sb = new StringBuilder();
        for (Lease l : leases) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(l.contentId).append(" (").append(l.origin).append(") ").append((now - l.grantedAtMs) / 60_000)
                    .append(" min");
        }
        return sb.toString();
    }

    /** The leases held now, after expired ones are taken back. Always 0 while the pool is unlimited. */
    public synchronized int held() {
        reap(clock.getAsLong());
        return leases.size();
    }

    /** The quota now, or a negative number for unlimited. */
    public int capacity() {
        return capacity.getAsInt();
    }

    /** One line for {@code !mechanics status}: the quota and who holds it; null while the pool is unlimited. */
    public synchronized String describe() {
        int cap = capacity.getAsInt();
        if (cap < 0) {
            return null;
        }
        long now = clock.getAsLong();
        reap(now);
        return "real-run pool: " + leases.size() + " of " + cap + " held" + (leases.isEmpty() ? "" : ": " + holders(now));
    }

    /**
     * How long a lease may outlive its admission before it is taken back: the longest the run can legitimately play
     * (its budget, or the content's own limit, or the sum of its stages' limits - a boss row has one clock per room),
     * the after-clear budget (prizes, bonus, way out) and a margin for the staging in front of it.
     */
    static long ttlMs(Content content, long budgetMs) {
        long playMs;
        if (budgetMs > 0) {
            playMs = budgetMs;
        } else {
            long stages = 0;
            for (Stage s : content.stages()) {
                stages += Math.max(0, s.timeLimitMinutes());
            }
            playMs = Math.max(Math.max(1, content.timeLimitMinutes()), stages) * 60_000L;
        }
        return playMs + Orchestrator.AFTER_CLEAR_BUDGET_MS + TTL_MARGIN_MS;
    }

    /** Test hook: drop every lease and remembered refusal. */
    synchronized void clearForTest() {
        leases.clear();
        lastAnnounced.clear();
        lastReal.clear();
        wanting.clear();
    }
}
