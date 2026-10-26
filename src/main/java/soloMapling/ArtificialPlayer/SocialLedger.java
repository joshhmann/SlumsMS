package soloMapling.ArtificialPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.6.0 (S5): the "started it" ledger behind {@link SocialGate}. Per real player, the bots they started something with:
 * a {@code beef} weight (a KS, a stolen drop, a scam, a roast, a defame: +1 each, at most {@link #MAX_WEIGHT}) and a
 * {@code flirt} weight (+1 per flirt), each halving every {@link SocialGate#halfLifeMs()} from when it last grew. A bot
 * may beef back while its beef is at least {@link #ACTIVE}; it may flirt back while its flirt is.
 *
 * <p>Bounded: at most {@link #MAX_PER_PLAYER} bots per player (the weakest is dropped), and only real players who
 * started something have an entry at all. Memory only here; {@link SocialGate} persists rows through its store.
 */
final class SocialLedger {

    static final double ACTIVE = 0.5;
    static final double MAX_WEIGHT = 4.0;
    static final int MAX_PER_PLAYER = 64;

    /** One bot and player. Times are epoch ms. Guarded by the player's map. */
    static final class Entry {
        final int botId;
        double beef;
        long beefAt;
        double flirt;
        long flirtAt;
        String last = "";

        Entry(int botId) {
            this.botId = botId;
        }

        Row row(int playerId) {
            return new Row(playerId, botId, beef, beefAt, flirt, flirtAt, last);
        }
    }

    /** What the store keeps, one per bot and player (character ids, not slots). */
    record Row(int playerId, int botId, double beef, long beefAt, double flirt, long flirtAt, String lastKind) {
    }

    private final Map<Integer, Map<Integer, Entry>> byPlayer = new ConcurrentHashMap<>();

    static double decayed(double weight, long at, long now, long halfLifeMs) {
        if (weight <= 0) {
            return 0;
        }
        if (halfLifeMs <= 0) {
            return weight; // no fading configured
        }
        long age = Math.max(0, now - at);
        return weight * Math.pow(0.5, (double) age / halfLifeMs);
    }

    /** The player provoked the bot ({@code kind} for the log): its beef grows by one. The row to store. */
    Row provoked(int botId, int playerId, String kind, long now, long halfLifeMs) {
        Map<Integer, Entry> m = byPlayer.computeIfAbsent(playerId, k -> new HashMap<>());
        synchronized (m) {
            Entry e = m.computeIfAbsent(botId, Entry::new);
            e.beef = Math.min(MAX_WEIGHT, decayed(e.beef, e.beefAt, now, halfLifeMs) + 1);
            e.beefAt = now;
            e.last = kind == null ? "" : kind;
            Row row = e.row(playerId);
            bound(m, botId, now, halfLifeMs);
            return row;
        }
    }

    /** The player flirted with the bot first: its flirt weight grows by one. The row to store. */
    Row flirted(int botId, int playerId, long now, long halfLifeMs) {
        Map<Integer, Entry> m = byPlayer.computeIfAbsent(playerId, k -> new HashMap<>());
        synchronized (m) {
            Entry e = m.computeIfAbsent(botId, Entry::new);
            e.flirt = Math.min(MAX_WEIGHT, decayed(e.flirt, e.flirtAt, now, halfLifeMs) + 1);
            e.flirtAt = now;
            Row row = e.row(playerId);
            bound(m, botId, now, halfLifeMs);
            return row;
        }
    }

    double beef(int botId, int playerId, long now, long halfLifeMs) {
        Map<Integer, Entry> m = byPlayer.get(playerId);
        if (m == null) {
            return 0;
        }
        synchronized (m) {
            Entry e = m.get(botId);
            return e == null ? 0 : decayed(e.beef, e.beefAt, now, halfLifeMs);
        }
    }

    double flirt(int botId, int playerId, long now, long halfLifeMs) {
        Map<Integer, Entry> m = byPlayer.get(playerId);
        if (m == null) {
            return 0;
        }
        synchronized (m) {
            Entry e = m.get(botId);
            return e == null ? 0 : decayed(e.flirt, e.flirtAt, now, halfLifeMs);
        }
    }

    String lastKind(int botId, int playerId) {
        Map<Integer, Entry> m = byPlayer.get(playerId);
        if (m == null) {
            return "";
        }
        synchronized (m) {
            Entry e = m.get(botId);
            return e == null ? "" : e.last;
        }
    }

    /** The bots this player started something with that may still beef back (cheap: one map read when none). */
    List<Integer> wronged(int playerId, long now, long halfLifeMs) {
        Map<Integer, Entry> m = byPlayer.get(playerId);
        if (m == null) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        synchronized (m) {
            for (Entry e : m.values()) {
                if (decayed(e.beef, e.beefAt, now, halfLifeMs) >= ACTIVE) {
                    out.add(e.botId);
                }
            }
        }
        return out;
    }

    /** Puts a stored row back (boot); a row already in memory (a newer event) wins. */
    void restore(Row r) {
        Map<Integer, Entry> m = byPlayer.computeIfAbsent(r.playerId(), k -> new HashMap<>());
        synchronized (m) {
            if (m.containsKey(r.botId()) || m.size() >= MAX_PER_PLAYER) {
                return;
            }
            Entry e = new Entry(r.botId());
            e.beef = r.beef();
            e.beefAt = r.beefAt();
            e.flirt = r.flirt();
            e.flirtAt = r.flirtAt();
            e.last = r.lastKind() == null ? "" : r.lastKind();
            m.put(r.botId(), e);
        }
    }

    /** Forgets one pair. True when there was one. */
    boolean forget(int botId, int playerId) {
        Map<Integer, Entry> m = byPlayer.get(playerId);
        if (m == null) {
            return false;
        }
        synchronized (m) {
            return m.remove(botId) != null;
        }
    }

    /** Forgets a bot with everyone (a staged QA bot's id comes back for the next run). The players it was known to. */
    List<Integer> forgetBot(int botId) {
        List<Integer> out = new ArrayList<>();
        for (Map.Entry<Integer, Map<Integer, Entry>> p : byPlayer.entrySet()) {
            Map<Integer, Entry> m = p.getValue();
            synchronized (m) {
                if (m.remove(botId) != null) {
                    out.add(p.getKey());
                }
            }
        }
        return out;
    }

    int size() {
        int n = 0;
        for (Map<Integer, Entry> m : byPlayer.values()) {
            synchronized (m) {
                n += m.size();
            }
        }
        return n;
    }

    void clear() {
        byPlayer.clear();
    }

    /** Over the cap: drops the weakest pair other than {@code keep}. */
    private static void bound(Map<Integer, Entry> m, int keep, long now, long halfLifeMs) {
        while (m.size() > MAX_PER_PLAYER) {
            Entry weakest = null;
            double low = Double.MAX_VALUE;
            for (Entry e : m.values()) {
                if (e.botId == keep) {
                    continue;
                }
                double w = Math.max(decayed(e.beef, e.beefAt, now, halfLifeMs), decayed(e.flirt, e.flirtAt, now,
                        halfLifeMs));
                if (w < low) {
                    low = w;
                    weakest = e;
                }
            }
            if (weakest == null) {
                return;
            }
            m.remove(weakest.botId);
        }
    }
}
