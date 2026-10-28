package soloMapling.achievements;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * Progress and awards. An event moves a goal's stored progress; reaching the target flips the row to EARNED in the
 * database first (that write is the claim: it succeeds once), then the medal goes to the bag, the inventory is
 * saved, and only then the row becomes GRANTED. A crash between the steps is repaired by {@link #settle}: an EARNED
 * row whose medal is already in the bag is just marked GRANTED, otherwise the medal is handed over again. A full bag
 * leaves the row EARNED and tells the player; the next login or the next event hands it over.
 * <p>
 * Bots take the same path, except their medal is a best effort (a full bag of a bot is nobody's problem) and the row
 * is marked GRANTED either way. Events are queued on one thread so the game threads never wait for the database.
 */
public final class AchievementService {
    private static final Logger log = LoggerFactory.getLogger(AchievementService.class);
    private static final int CACHE_MAX = 6_000;

    public enum Grant { GRANTED, NO_ROOM, WAITING, ALREADY }

    private final GoalCatalog catalog;
    private final AchievementStore store;
    private final Hands hands;
    private final LongSupplier clock;
    private final Executor executor;
    private final ConcurrentHashMap<Integer, Map<String, AchievementStore.Row>> cache = new ConcurrentHashMap<>();

    public AchievementService(GoalCatalog catalog, AchievementStore store, Hands hands, LongSupplier clock,
                              Executor executor) {
        this.catalog = catalog;
        this.store = store;
        this.hands = hands;
        this.clock = clock;
        this.executor = executor;
    }

    public GoalCatalog catalog() {
        return catalog;
    }

    // ── events ──────────────────────────────────────────────────────────────────────────────────

    /** Queues an event; cheap on the calling thread, never throws. */
    public void submit(Subject who, Event e) {
        try {
            if (!concerns(who.id(), e)) {
                return;
            }
            executor.execute(() -> {
                try {
                    handle(who, e, false);
                } catch (RuntimeException | LinkageError ex) {
                    log.warn("achievement event {} for {} failed", e, who.id(), ex);
                }
            });
        } catch (RuntimeException | LinkageError ex) {
            log.warn("achievement event {} for {} not queued", e, who.id(), ex);
        }
    }

    /** Whether any goal could still move: the trigger matches and the character has not already finished it. */
    boolean concerns(int charId, Event e) {
        Map<String, AchievementStore.Row> rows = cache.get(charId);
        for (Goal g : catalog.forTrigger(e.trigger())) {
            if (!g.concerns(e)) {
                continue;
            }
            AchievementStore.Row r = rows == null ? null : rows.get(g.id());
            if (r == null || r.state() < AchievementStore.EARNED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Applies an event now and returns the goals it completed. {@code quiet}: nothing for the public feed (a login
     * seed of facts that were already true).
     */
    public synchronized List<Goal> handle(Subject who, Event e, boolean quiet) {
        List<Goal> earned = new ArrayList<>();
        try {
            Map<String, AchievementStore.Row> rows = rows(who.id());
            long now = clock.getAsLong();
            for (Goal g : catalog.forTrigger(e.trigger())) {
                AchievementStore.Row r = rows.get(g.id());
                if (r != null && r.state() >= AchievementStore.EARNED) {
                    continue;
                }
                int before = r == null ? 0 : r.progress();
                int after = g.next(before, e);
                if (after == before) {
                    continue;
                }
                if (g.done(after)) {
                    if (store.markEarned(who.id(), g.id(), after, now)) {
                        rows.put(g.id(), new AchievementStore.Row(after, AchievementStore.EARNED, now, 0));
                        earned.add(g);
                    }
                } else if (g.keepsProgress()) {
                    store.saveProgress(who.id(), g.id(), after, now);
                    rows.put(g.id(), new AchievementStore.Row(after, AchievementStore.PROGRESS, 0, 0));
                }
            }
            for (Goal g : earned) {
                if (!who.bot() && who.chr() != null) {
                    hands.tell(who.chr(), "Achievement: " + g.title() + " - " + g.text());
                }
                deliver(who, g, !quiet);
            }
            if (!earned.isEmpty() || hasOwed(rows)) {
                settleLocked(who, rows);
            }
        } catch (SQLException ex) {
            log.warn("achievement store failed for {}: {}", who.id(), ex.toString());
            cache.remove(who.id());
        }
        return earned;
    }

    // ── awards ──────────────────────────────────────────────────────────────────────────────────

    private void deliver(Subject who, Goal g, boolean publicly) {
        if (publicly && who.chr() != null) {
            hands.announce(who.chr(), g);
        }
        if (who.bot()) {
            try {
                if (who.chr() != null && !hands.has(who.chr(), g.medal())) {
                    hands.give(who.chr(), g);
                }
                store.markGranted(who.id(), g.id(), clock.getAsLong());
                cache.getOrDefault(who.id(), new HashMap<>()).computeIfPresent(g.id(), (k, r) ->
                        new AchievementStore.Row(r.progress(), AchievementStore.GRANTED, r.earnedAt(), clock.getAsLong()));
            } catch (SQLException ex) {
                log.warn("bot achievement {} for {} not marked granted: {}", g.id(), who.id(), ex.toString());
            }
        }
    }

    /** Hands over every medal this player is owed (login, @achievements, the next event). */
    public synchronized List<Grant> settle(Subject who) {
        try {
            return settleLocked(who, rows(who.id()));
        } catch (SQLException ex) {
            log.warn("achievement settle failed for {}: {}", who.id(), ex.toString());
            cache.remove(who.id());
            return List.of();
        }
    }

    private boolean hasOwed(Map<String, AchievementStore.Row> rows) {
        for (AchievementStore.Row r : rows.values()) {
            if (r.state() == AchievementStore.EARNED) {
                return true;
            }
        }
        return false;
    }

    private List<Grant> settleLocked(Subject who, Map<String, AchievementStore.Row> rows) {
        List<Grant> out = new ArrayList<>();
        if (who.chr() == null) {
            return out;
        }
        for (Goal g : catalog.goals()) {
            AchievementStore.Row r = rows.get(g.id());
            if (r != null && r.state() == AchievementStore.EARNED) {
                out.add(grantOne(who, g, rows));
            }
        }
        return out;
    }

    private Grant grantOne(Subject who, Goal g, Map<String, AchievementStore.Row> rows) {
        Character chr = who.chr();
        try {
            if (!hands.has(chr, g.medal())) {
                if (!hands.give(chr, g)) {
                    if (!who.bot()) {
                        hands.tell(chr, "Make room in your Equip inventory to receive the "
                                + medalName(g) + " (" + g.title() + ").");
                    }
                    return Grant.NO_ROOM;
                }
                hands.save(chr);
            }
            if (store.markGranted(who.id(), g.id(), clock.getAsLong())) {
                AchievementStore.Row r = rows.get(g.id());
                rows.put(g.id(), new AchievementStore.Row(r.progress(), AchievementStore.GRANTED, r.earnedAt(),
                        clock.getAsLong()));
                if (!who.bot()) {
                    hands.tell(chr, "You received the " + medalName(g) + ".");
                }
                return Grant.GRANTED;
            }
            return Grant.ALREADY;
        } catch (SQLException ex) {
            log.warn("achievement {} for {} not marked granted: {}", g.id(), who.id(), ex.toString());
            return Grant.WAITING;
        }
    }

    private String medalName(Goal g) {
        String n = hands.name(g.medal());
        return n == null || n.isBlank() ? "medal " + g.medal() : n.trim();
    }

    // ── staff ───────────────────────────────────────────────────────────────────────────────────

    /** GM grant: the goal is earned now whatever the progress, and the medal handed over when the character is here. */
    public synchronized Grant forceEarn(Subject who, Goal g) throws SQLException {
        Map<String, AchievementStore.Row> rows = rows(who.id());
        AchievementStore.Row r = rows.get(g.id());
        if (r != null && r.state() >= AchievementStore.EARNED) {
            return r.state() == AchievementStore.GRANTED ? Grant.ALREADY : settleOne(who, g, rows);
        }
        long now = clock.getAsLong();
        int progress = Math.max(r == null ? 0 : r.progress(), g.needed());
        if (!store.markEarned(who.id(), g.id(), progress, now)) {
            return Grant.ALREADY;
        }
        rows.put(g.id(), new AchievementStore.Row(progress, AchievementStore.EARNED, now, 0));
        if (who.bot()) {
            deliver(who, g, false);
            return Grant.GRANTED;
        }
        return settleOne(who, g, rows);
    }

    private Grant settleOne(Subject who, Goal g, Map<String, AchievementStore.Row> rows) {
        return who.chr() == null ? Grant.WAITING : grantOne(who, g, rows);
    }

    /** GM reset: forgets the goal (the medal item, if any, stays in the bag; remove it by hand). */
    public synchronized boolean reset(int charId, Goal g) throws SQLException {
        Map<String, AchievementStore.Row> rows = rows(charId);
        boolean had = rows.remove(g.id()) != null;
        store.delete(charId, g.id());
        return had;
    }

    // ── reading ─────────────────────────────────────────────────────────────────────────────────

    public synchronized AchievementStore.Row row(int charId, String goal) {
        try {
            return rows(charId).get(goal);
        } catch (SQLException ex) {
            return null;
        }
    }

    /** A copy of the character's rows. */
    public synchronized Map<String, AchievementStore.Row> snapshot(int charId) throws SQLException {
        return new HashMap<>(rows(charId));
    }

    public synchronized int earnedCount(int charId) {
        try {
            int n = 0;
            for (AchievementStore.Row r : rows(charId).values()) {
                if (r.state() >= AchievementStore.EARNED) {
                    n++;
                }
            }
            return n;
        } catch (SQLException ex) {
            return 0;
        }
    }

    private Map<String, AchievementStore.Row> rows(int charId) throws SQLException {
        Map<String, AchievementStore.Row> rows = cache.get(charId);
        if (rows == null) {
            if (cache.size() >= CACHE_MAX) {
                cache.clear();
            }
            rows = new HashMap<>(store.load(charId));
            cache.put(charId, rows);
        }
        return rows;
    }

    /** Forget a character's cached rows (logout), so the next event reads the database again. */
    public void forget(int charId) {
        cache.remove(charId);
    }
}
