package soloMapling.ArtificialPlayer.BotSocialSystem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ticket 271 stage 5: one bot's persisted intentions. At most {@link #MAX_LIVE} live ones, ordered by due time then id,
 * plus a short memory of how the last few ended. It lives inside the bot's {@code LifeMemory} (JSON key
 * {@code "intents"}, same lazy pattern as the social mind) and is not thread-safe on its own: reach it only through
 * {@code LifeMemory.intents(..)}, which holds the memory's lock and marks the memory dirty when {@link #version}
 * moves. A read changes nothing.
 *
 * <p>Ids are {@code actor << 32 | sequence}; the sequence only ever grows (it is saved), so an id is never reused
 * across a restart. Text is bounded ({@link SocialIntent#ORIGIN_MAX}).
 */
public final class IntentBook {

    /** Live intents per actor. */
    public static final int MAX_LIVE = 3;
    /** How many finished intents are remembered (for {@code !botwhy}). */
    public static final int MAX_RECENT = 5;

    /** How a finished intent ended. */
    public enum End {
        KEPT, BROKEN, EXPIRED, CANCELLED;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One finished intent, as remembered. */
    public record Done(long id, int target, SocialActKind kind, End end, long at) {
    }

    private final int actor;
    private final ArrayList<SocialIntent> live = new ArrayList<>();
    private final ArrayDeque<Done> recent = new ArrayDeque<>();
    private int next = 1;
    private int version;
    private boolean touched;

    public IntentBook(int actor) {
        this.actor = actor;
    }

    /** Counts every change; the owner saves when it moves. */
    public int version() {
        return version;
    }

    /** True once something was added, ended or restored: an untouched book needs no row. */
    public boolean touched() {
        return touched;
    }

    public boolean isEmpty() {
        return live.isEmpty();
    }

    public int size() {
        return live.size();
    }

    /** The live intents, earliest due first (a copy). */
    public List<SocialIntent> live() {
        return List.copyOf(live);
    }

    /** The live intents whose due time has come (a copy, earliest first); includes those already past their deadline. */
    public List<SocialIntent> due(long now) {
        List<SocialIntent> out = new ArrayList<>(live.size());
        for (SocialIntent in : live) {
            if (in.dueBy(now)) {
                out.add(in);
            }
        }
        return out;
    }

    /** Whether any live intent is due within {@code window} of {@code now} (or is already due and not yet past its deadline). */
    public boolean dueSoon(long now, long window) {
        for (SocialIntent in : live) {
            if (!in.expiredBy(now) && in.dueAt() - now <= window) {
                return true;
            }
        }
        return false;
    }

    /** The live intent for this target and kind, or null. */
    public SocialIntent find(int target, SocialActKind kind) {
        for (SocialIntent in : live) {
            if (in.target() == target && in.kind() == kind) {
                return in;
            }
        }
        return null;
    }

    public SocialIntent get(long id) {
        for (SocialIntent in : live) {
            if (in.id() == id) {
                return in;
            }
        }
        return null;
    }

    /** The next id (the sequence advances). */
    private long nextId() {
        return ((long) actor << 32) | (next++ & 0xffffffffL);
    }

    /** Adds one; null when the book is full. The caller has already checked everything else. */
    public SocialIntent add(int target, SocialActKind kind, long createdAt, long dueAt, long expiresAt, int mapId,
                            String origin) {
        if (live.size() >= MAX_LIVE) {
            return null;
        }
        SocialIntent made = new SocialIntent(nextId(), actor, target, kind, createdAt, dueAt, expiresAt, mapId, origin);
        live.add(made);
        sort();
        changed();
        return made;
    }

    /** Ends an intent: removed from the live list and noted in the recent ones. Null when it is not live (already ended). */
    public SocialIntent end(long id, End how, long now) {
        for (int i = 0; i < live.size(); i++) {
            SocialIntent in = live.get(i);
            if (in.id() == id) {
                live.remove(i);
                recent.addFirst(new Done(in.id(), in.target(), in.kind(), how, now));
                while (recent.size() > MAX_RECENT) {
                    recent.removeLast();
                }
                changed();
                return in;
            }
        }
        return null;
    }

    /** How the last few intents ended, newest first. */
    public List<Done> recent() {
        return List.copyOf(recent);
    }

    private void sort() {
        live.sort(Comparator.comparingLong(SocialIntent::dueAt).thenComparingLong(SocialIntent::id));
    }

    private void changed() {
        version++;
        touched = true;
    }

    // ── persistence ──

    /** JSON-ready: the sequence, the live intents and the recent endings. */
    public Map<String, Object> save() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("next", next);
        List<Object> rows = new ArrayList<>();
        for (SocialIntent in : live) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", in.id());
            row.put("target", in.target());
            row.put("kind", in.kind().name());
            row.put("created", in.createdAt());
            row.put("due", in.dueAt());
            row.put("expires", in.expiresAt());
            row.put("map", in.mapId());
            row.put("origin", in.origin());
            rows.add(row);
        }
        out.put("live", rows);
        List<Object> done = new ArrayList<>();
        for (Done d : recent) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.id());
            row.put("target", d.target());
            row.put("kind", d.kind().name());
            row.put("end", d.end().name());
            row.put("at", d.at());
            done.add(row);
        }
        out.put("recent", done);
        return out;
    }

    /**
     * Reads a save into this (empty) book. Unknown kinds, rows without the needed fields, a deadline not after its
     * due time, and anything over {@link #MAX_LIVE} (the latest due go first) are skipped, never fatal.
     */
    public void restore(Object saved) {
        if (!(saved instanceof Map<?, ?> map)) {
            return;
        }
        live.clear();
        recent.clear();
        long maxSeq = 0;
        if (map.get("live") instanceof List<?> rows) {
            for (Object row : rows) {
                try {
                    if (row instanceof Map<?, ?> m && m.get("id") instanceof Number id
                            && m.get("target") instanceof Number target && m.get("kind") instanceof String kind
                            && m.get("due") instanceof Number due && m.get("expires") instanceof Number expires
                            && expires.longValue() > due.longValue()) {
                        SocialIntent in = new SocialIntent(id.longValue(), actor, target.intValue(),
                                SocialActKind.valueOf(kind), num(m.get("created"), due.longValue()), due.longValue(),
                                expires.longValue(), (int) num(m.get("map"), 0),
                                m.get("origin") instanceof String s ? s : "");
                        live.add(in);
                        maxSeq = Math.max(maxSeq, in.id() & 0xffffffffL);
                    }
                } catch (RuntimeException skipped) { /* next row */ }
            }
        }
        sort();
        while (live.size() > MAX_LIVE) {
            live.remove(live.size() - 1);
        }
        if (map.get("recent") instanceof List<?> rows) {
            for (Object row : rows) {
                try {
                    if (recent.size() < MAX_RECENT && row instanceof Map<?, ?> m && m.get("id") instanceof Number id
                            && m.get("target") instanceof Number target && m.get("kind") instanceof String kind
                            && m.get("end") instanceof String end) {
                        recent.addLast(new Done(id.longValue(), target.intValue(), SocialActKind.valueOf(kind),
                                End.valueOf(end), num(m.get("at"), 0)));
                        maxSeq = Math.max(maxSeq, id.longValue() & 0xffffffffL);
                    }
                } catch (RuntimeException skipped) { /* next row */ }
            }
        }
        long saved1 = num(map.get("next"), 1);
        next = (int) Math.max(1, Math.min(Integer.MAX_VALUE - 1, Math.max(saved1, maxSeq + 1)));
        touched = true;
        version++;
    }

    private static long num(Object o, long fallback) {
        return o instanceof Number n ? n.longValue() : fallback;
    }
}
