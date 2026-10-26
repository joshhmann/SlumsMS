package soloMapling.Economy.board;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The files the server writes for the site (docs/design/track-d-plan.md, "Site board contract"), as JSON-ready maps.
 * The shapes are the site's (agreed 2026-10-26; camelCase like the inbox); {@code "schema": 1} and
 * {@code generated_at} head every file but alerts-fired.json, times are UTC ISO-8601. Character names only: no
 * account names anywhere (account ids key alerts-fired.json, which the site needs to know whose alert fired). Pure.
 */
public final class BoardOutbox {

    public static final int SCHEMA = 1;

    private BoardOutbox() {
    }

    private static Map<String, Object> head(long now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("generated_at", Board.iso(now));
        return m;
    }

    /** {@code board.json}: every open post, players' and bots' alike, newest first. Names only. */
    public static Map<String, Object> board(List<Board.Post> posts, long now) {
        List<Board.Post> sorted = new ArrayList<>(posts);
        sorted.sort(Comparator.comparingLong(Board.Post::createdAt).reversed().thenComparing(Board.Post::id));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Board.Post p : sorted) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", p.id());
            r.put("character", p.character());
            r.put("kind", p.kind().name());
            r.put("itemId", p.itemId());
            r.put("qty", p.qty());
            r.put("price", p.price());
            r.put("createdAt", Board.iso(p.createdAt()));
            r.put("expiresAt", Board.iso(p.expiresAt()));
            rows.add(r);
        }
        Map<String, Object> m = head(now);
        m.put("posts", rows);
        return m;
    }

    /** {@code rejected.json}: posts from posts.json the board does not show, and why. */
    public static Map<String, Object> rejected(List<Board.Rejection> rejected, long now) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Board.Rejection r : rejected) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", r.id());
            e.put("reason", r.reason());
            rows.add(e);
        }
        Map<String, Object> m = head(now);
        m.put("rejected", rows);
        return m;
    }

    /** {@code removed.json}: posts a GM took down ({@code !board remove}); the site closes them. No GM names. */
    public static Map<String, Object> removed(List<BoardState.Removed> removed, long now) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BoardState.Removed r : removed) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", r.id());
            e.put("reason", r.reason() == null || r.reason().isBlank() ? "removed by a GM" : r.reason());
            rows.add(e);
        }
        Map<String, Object> m = head(now);
        m.put("removed", rows);
        return m;
    }

    /**
     * {@code alerts-fired.json}: {@code {"<accountId>": [{id, t, price}]}}, the site's shape (no schema key: the top
     * level is the accounts). {@code t}: when it fired; {@code price}: the cheapest unit listing price that set it off.
     */
    public static Map<String, Object> alertsFired(List<BoardState.Fired> fired) {
        Map<Integer, List<Map<String, Object>>> byAccount = new TreeMap<>();
        for (BoardState.Fired f : fired) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", f.id());
            e.put("t", Board.iso(f.firedAt()));
            e.put("price", f.listingPrice());
            byAccount.computeIfAbsent(f.account(), k -> new ArrayList<>()).add(e);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        byAccount.forEach((account, list) -> m.put(String.valueOf(account), list));
        return m;
    }
}
