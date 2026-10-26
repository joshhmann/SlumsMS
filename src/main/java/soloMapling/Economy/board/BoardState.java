package soloMapling.Economy.board;

import soloMapling.server.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * What the board remembers across restarts, in one small JSON file (no table): which bots already answered which post
 * (one offer per post per bot, {@link #OFFER_CAP} per post), which alerts fired and whether the site has disarmed them
 * since, and which posts a GM removed. Thread-safe.
 */
public final class BoardState {

    public static final int OFFER_CAP = 2;
    /** Post entries are forgotten this long after the post left the inbox (posts live 3 days). */
    static final long FORGET_POST_MS = TimeUnit.DAYS.toMillis(4);
    /** Fired alerts stay in alerts-fired.json this long, unless the site deletes the alert first. */
    static final long KEEP_FIRED_MS = TimeUnit.DAYS.toMillis(30);

    /** A post the board has seen: when first, and the bots that offered (in order). */
    static final class Seen {
        final long firstSeen;
        long lastSeen;
        final List<Integer> bots = new ArrayList<>();

        Seen(long firstSeen) {
            this.firstSeen = firstSeen;
            this.lastSeen = firstSeen;
        }
    }

    /** One fired alert. {@code acked}: the site has since shown it disarmed, so a later re-arm can fire it again. */
    public record Fired(String id, int account, int itemId, Board.Dir dir, long price, long firedAt, long listingPrice,
                        boolean acked) {
        Fired ack() {
            return new Fired(id, account, itemId, dir, price, firedAt, listingPrice, true);
        }
    }

    public record Removed(String id, long at, String by, String reason) {
    }

    private final Map<String, Seen> posts = new LinkedHashMap<>();
    private final Map<String, Fired> fired = new LinkedHashMap<>();
    private final Map<String, Removed> removed = new LinkedHashMap<>();

    // ---- posts and offers ----

    /** True the first time a post id is seen (it is then remembered). */
    public synchronized boolean firstSight(String postId, long now) {
        Seen s = posts.get(postId);
        if (s != null) {
            s.lastSeen = now;
            return false;
        }
        posts.put(postId, new Seen(now));
        return true;
    }

    public synchronized long firstSeen(String postId) {
        Seen s = posts.get(postId);
        return s == null ? 0 : s.firstSeen;
    }

    public synchronized int offers(String postId) {
        Seen s = posts.get(postId);
        return s == null ? 0 : s.bots.size();
    }

    public synchronized boolean offered(String postId, int botId) {
        Seen s = posts.get(postId);
        return s != null && s.bots.contains(botId);
    }

    /** Records an offer; false (nothing recorded) when the bot already offered or the post has its cap. */
    public synchronized boolean takeOffer(String postId, int botId, long now) {
        Seen s = posts.computeIfAbsent(postId, k -> new Seen(now));
        if (s.bots.contains(botId) || s.bots.size() >= OFFER_CAP) {
            return false;
        }
        s.bots.add(botId);
        return true;
    }

    /** Forgets posts that left the inbox long ago. */
    public synchronized void prunePosts(java.util.Set<String> present, long now) {
        for (String id : present) {
            Seen s = posts.get(id);
            if (s != null) {
                s.lastSeen = now;
            }
        }
        posts.values().removeIf(s -> now - s.lastSeen > FORGET_POST_MS);
        removed.values().removeIf(r -> now - r.at() > FORGET_POST_MS && !present.contains(r.id()));
    }

    // ---- alerts ----

    public synchronized Fired fired(String alertId) {
        return fired.get(alertId);
    }

    public synchronized void fire(Fired f) {
        fired.put(f.id(), f);
    }

    /** The site showed the alert disarmed: a later re-arm may fire it again. */
    public synchronized void ack(String alertId) {
        Fired f = fired.get(alertId);
        if (f != null && !f.acked()) {
            fired.put(alertId, f.ack());
        }
    }

    /** Fired alerts, oldest first, after dropping those the site deleted or that are older than 30 days. */
    public synchronized List<Fired> firedAlerts(java.util.Set<String> alertIds, long now) {
        fired.values().removeIf(f -> !alertIds.contains(f.id()) || now - f.firedAt() > KEEP_FIRED_MS);
        return new ArrayList<>(fired.values());
    }

    // ---- GM removals ----

    public synchronized boolean remove(String postId, long at, String by, String reason) {
        return removed.putIfAbsent(postId, new Removed(postId, at, by, reason)) == null;
    }

    public synchronized java.util.Set<String> removedIds() {
        return new java.util.HashSet<>(removed.keySet());
    }

    public synchronized List<Removed> removals() {
        return new ArrayList<>(removed.values());
    }

    // ---- the file ----

    public synchronized String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", 1);
        Map<String, Object> ps = new LinkedHashMap<>();
        posts.forEach((id, s) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("first_seen", s.firstSeen);
            e.put("last_seen", s.lastSeen);
            e.put("bots", new ArrayList<>(s.bots));
            ps.put(id, e);
        });
        m.put("posts", ps);
        List<Object> fs = new ArrayList<>();
        fired.values().forEach(f -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", f.id());
            e.put("account", f.account());
            e.put("item_id", f.itemId());
            e.put("direction", f.dir().name().toLowerCase(java.util.Locale.ROOT));
            e.put("price", f.price());
            e.put("fired_at", f.firedAt());
            e.put("listing_price", f.listingPrice());
            e.put("acked", f.acked());
            fs.add(e);
        });
        m.put("fired", fs);
        List<Object> rs = new ArrayList<>();
        removed.values().forEach(r -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", r.id());
            e.put("at", r.at());
            e.put("by", r.by());
            e.put("reason", r.reason());
            rs.add(e);
        });
        m.put("removed", rs);
        return Json.write(m);
    }

    /** Reads a saved state; an unreadable part is skipped (the board then may answer a post once more). */
    public static BoardState fromJson(String json) {
        BoardState st = new BoardState();
        Object doc = Json.parse(json);
        if (!(doc instanceof Map<?, ?> m)) {
            return st;
        }
        if (m.get("posts") instanceof Map<?, ?> ps) {
            ps.forEach((id, v) -> {
                if (v instanceof Map<?, ?> e && BoardInbox.num(e.get("first_seen")) != null) {
                    Seen s = new Seen(BoardInbox.num(e.get("first_seen")));
                    Long last = BoardInbox.num(e.get("last_seen"));
                    s.lastSeen = last == null ? s.firstSeen : last;
                    if (e.get("bots") instanceof List<?> bots) {
                        for (Object b : bots) {
                            Long id2 = BoardInbox.num(b);
                            if (id2 != null) {
                                s.bots.add((int) (long) id2);
                            }
                        }
                    }
                    st.posts.put(String.valueOf(id), s);
                }
            });
        }
        if (m.get("fired") instanceof List<?> fs) {
            for (Object o : fs) {
                if (o instanceof Map<?, ?> e) {
                    String id = BoardInbox.str(e.get("id"));
                    Board.Dir dir = Board.Dir.parse(e.get("direction"));
                    Long account = BoardInbox.num(e.get("account")), item = BoardInbox.num(e.get("item_id")),
                            price = BoardInbox.num(e.get("price")), at = BoardInbox.num(e.get("fired_at")),
                            listing = BoardInbox.num(e.get("listing_price"));
                    if (id != null && dir != null && account != null && item != null && price != null && at != null) {
                        st.fired.put(id, new Fired(id, (int) (long) account, (int) (long) item, dir, price, at,
                                listing == null ? 0 : listing, Boolean.TRUE.equals(e.get("acked"))));
                    }
                }
            }
        }
        if (m.get("removed") instanceof List<?> rs) {
            for (Object o : rs) {
                if (o instanceof Map<?, ?> e) {
                    String id = BoardInbox.str(e.get("id"));
                    Long at = BoardInbox.num(e.get("at"));
                    if (id != null && at != null) {
                        st.removed.put(id, new Removed(id, at, String.valueOf(e.get("by")),
                                e.get("reason") == null ? "" : String.valueOf(e.get("reason"))));
                    }
                }
            }
        }
        return st;
    }
}
