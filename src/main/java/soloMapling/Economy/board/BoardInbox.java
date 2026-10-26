package soloMapling.Economy.board;

import soloMapling.server.Json;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads what the site wrote: {@code posts.json} and {@code alerts.json} (docs/design/track-d-plan.md, "Site board
 * contract"). Field names are camelCase as agreed; snake_case spellings are accepted too. A row that cannot be read
 * becomes a {@link Board.Rejection} ("malformed: ...") instead of failing the file. Pure: no files, no game.
 */
public final class BoardInbox {

    private BoardInbox() {
    }

    public record Posts(List<Board.Post> posts, List<Board.Rejection> malformed) {
    }

    /** Parses {@code posts.json}: {@code {"schema":1,"posts":[...]}} or a bare array. Throws on a broken document. */
    public static Posts posts(String json) {
        Object doc = Json.parse(json);
        List<?> rows = doc instanceof Map<?, ?> m ? asList(m.get("posts")) : asList(doc);
        List<Board.Post> out = new ArrayList<>();
        List<Board.Rejection> bad = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> p)) {
                bad.add(new Board.Rejection("", "malformed: not an object"));
                continue;
            }
            String id = str(p.get("id"));
            if (id == null || id.isBlank() || id.length() > 64) {
                bad.add(new Board.Rejection(id == null ? "" : id, "malformed: id"));
                continue;
            }
            Long account = num(p.get("account"));
            String character = str(p.get("character"));
            Board.Kind kind = Board.Kind.parse(p.get("kind"));
            Long itemId = num(pick(p, "itemId", "item_id"));
            Long qty = num(p.get("qty"));
            Long price = num(p.get("price"));
            Long created = time(pick(p, "createdAt", "created_at"));
            Long expires = time(pick(p, "expiresAt", "expires_at"));
            String missing = account == null ? "account" : character == null || character.isBlank() ? "character"
                    : kind == null ? "kind" : itemId == null ? "itemId" : qty == null ? "qty" : price == null ? "price"
                    : created == null ? "createdAt" : null;
            if (missing != null) {
                bad.add(new Board.Rejection(id, "malformed: " + missing));
                continue;
            }
            out.add(new Board.Post(id.trim(), (int) (long) account, character.trim(), 0, kind, (int) (long) itemId,
                    (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, qty)), price, created,
                    expires == null ? 0 : expires, false));
        }
        return new Posts(out, bad);
    }

    /**
     * Parses {@code alerts.json}: {@code {"schema":1,"accounts":{"<accountId>":{"watch":[...],"alerts":[...]}}}} (the
     * site's shape; a list of {@code {"account":id,...}} rows is read too). An alert names its side as
     * {@code "dir": "below"|"above"} ({@code "direction"}, or {@code "below": price} / {@code "above": price}, also
     * read). Rows that cannot be read are skipped. Throws on a broken document.
     */
    public static List<Board.AccountAlerts> alerts(String json) {
        Object doc = Json.parse(json);
        Object accts = doc instanceof Map<?, ?> m ? m.get("accounts") : doc;
        List<Map.Entry<Object, Object>> rows = new ArrayList<>();
        if (accts instanceof Map<?, ?> byId) {
            byId.forEach((k, v) -> rows.add(Map.entry(k, v)));
        } else {
            for (Object row : asList(accts)) {
                if (row instanceof Map<?, ?> r && r.get("account") != null) {
                    rows.add(Map.entry(r.get("account"), r));
                }
            }
        }
        List<Board.AccountAlerts> out = new ArrayList<>();
        for (Map.Entry<Object, Object> row : rows) {
            if (!(row.getValue() instanceof Map<?, ?> a)) {
                continue;
            }
            Long account = num(row.getKey());
            if (account == null || account <= 0) {
                continue;
            }
            List<Integer> watch = new ArrayList<>();
            for (Object w : asList(pick(a, "watch", "watchlist"))) {
                Long id = num(w instanceof Map<?, ?> wm ? pick(wm, "itemId", "item_id") : w);
                if (id != null && id > 0) {
                    watch.add((int) (long) id);
                }
            }
            List<Board.Alert> alerts = new ArrayList<>();
            for (Object o : asList(a.get("alerts"))) {
                if (!(o instanceof Map<?, ?> al)) {
                    continue;
                }
                String id = str(al.get("id"));
                Long itemId = num(pick(al, "itemId", "item_id"));
                Board.Dir dir = Board.Dir.parse(pick(al, "direction", "dir"));
                Long price = num(al.get("price"));
                if (dir == null && num(al.get("below")) != null) {
                    dir = Board.Dir.BELOW;
                    price = price == null ? num(al.get("below")) : price;
                } else if (dir == null && num(al.get("above")) != null) {
                    dir = Board.Dir.ABOVE;
                    price = price == null ? num(al.get("above")) : price;
                }
                if (id == null || id.isBlank() || itemId == null || dir == null || price == null || price <= 0) {
                    continue;
                }
                Object armed = al.get("armed");
                Long armedAt = time(pick(al, "armedAt", "armed_at"));
                alerts.add(new Board.Alert(id.trim(), (int) (long) account, (int) (long) itemId, dir, price,
                        armed == null || Boolean.TRUE.equals(armed), armedAt == null ? 0 : armedAt));
            }
            out.add(new Board.AccountAlerts((int) (long) account, watch, alerts));
        }
        return out;
    }

    private static Object pick(Map<?, ?> m, String a, String b) {
        Object v = m.get(a);
        return v != null ? v : m.get(b);
    }

    private static List<?> asList(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    static String str(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return String.valueOf(n.longValue());
        }
        return o instanceof String s ? s : null;
    }

    static Long num(Object o) {
        if (o instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) && d == Math.rint(d) ? n.longValue() : null;
        }
        if (o instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** An ISO-8601 instant ("2026-10-26T07:15:00Z") or epoch milliseconds. */
    static Long time(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Instant.parse(s.trim()).toEpochMilli();
            } catch (DateTimeParseException e) {
                Long ms = num(s);
                return ms;
            }
        }
        return null;
    }
}
