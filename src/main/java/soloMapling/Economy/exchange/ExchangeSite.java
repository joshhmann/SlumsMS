package soloMapling.Economy.exchange;

import soloMapling.server.Json;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ticket 113: the site's side of the exchange, over the same file channel as the Buy/Sell board ({@code SiteBoard}):
 * the site never touches the database; it writes requests, the game server answers. Pure: parsing and JSON shapes.
 * <pre>
 * inbox  exchange-requests.json   (site writes; the server reads every poll)
 *   {"schema":1,"requests":[
 *     {"id":"r-0","account":12,"type":"preview","character":345,"side":"BUY"|"SELL","price":1200000,"nx":1000,
 *      "createdAt":"2026-10-27T18:00:00Z"},       step 1: checks + what would match now; returns data.token
 *     {"id":"r-1","account":12,"type":"confirm","token":"9f2c...","createdAt":...},   step 2: places it (within
 *      confirmSec, default 60 s; the same token twice returns the same order; TOKEN_EXPIRED after)
 *     ("place" is no longer accepted from the site: BAD_REQUEST "use preview then confirm")
 *     {"id":"r-2","account":12,"type":"cancel","orderId":77,"createdAt":...},
 *     {"id":"r-3","account":12,"type":"mine","createdAt":...},        my orders (open and recent)
 *     {"id":"r-4","account":12,"type":"book","createdAt":...},        book depth (also public in exchange-book.json)
 *     {"id":"r-5","account":12,"type":"trades","createdAt":...}]}    last trades (also public)
 * outbox exchange-responses.json  (private: account ids; mode 0640 like the board's files)
 *   {"schema":1,"generated_at":...,"responses":[{"id":"r-1","account":12,"ok":true,"code":"OK","message":"",
 *     "data":{...}}]}   newest last, the last 500 kept; the site matches by id and then drops its request.
 * outbox exchange-book.json  (public: no names, no ids but order counts)
 *   {"schema":1,"generated_at":...,"halted":false,"rate":{"mesos_per_1000nx":1180000|null,"window_hours":24,
 *     "min_fills":3},"bids":[{"price":..,"nx":..,"orders":..}],"asks":[...],"trades":[{"t":..,"price":..,"nx":..}]}
 * </pre>
 * Each request id is answered once. A request older than {@link #MAX_AGE_MS} is answered STALE and not acted on,
 * so a backlog after downtime never places orders the player gave up on. Places are idempotent by request id even
 * across restarts (the id is stored on the order). Error codes: BAD_REQUEST, STALE, HALTED, UNAVAILABLE,
 * LOG_OUT_FIRST, INSUFFICIENT_FUNDS, NOT_OWNER, NOT_FOUND, LIMIT_OPEN, LIMIT_DAY, COOLDOWN, DUPLICATE, ERROR,
 * TOKEN_EXPIRED and TRY_AGAIN (the player is changing channel or in the Cash Shop). A player who is logged in can
 * place from the site too, as long as the order names the character they are playing (else LOG_OUT_FIRST).
 * <p>
 * Preview data: {@code token, expiresAt, side, price, nx, takes:{asset,amount} (escrow, plus a buy's fee reserve),
 * feeBpsEachSide, matchNow:{nx,mesos,fee,youGet:{asset,amount}}, restsNx, cancelledBySelfTrade}.
 */
public final class ExchangeSite {

    public static final int SCHEMA = 1;
    public static final long MAX_AGE_MS = 10 * 60_000L;
    public static final int KEEP_RESPONSES = 500;
    public static final String REQUESTS = "exchange-requests.json";
    public static final String RESPONSES = "exchange-responses.json";
    public static final String BOOK = "exchange-book.json";

    private ExchangeSite() {
    }

    public enum Type {
        PLACE, PREVIEW, CONFIRM, CANCEL, MINE, BOOK, TRADES;

        static Type parse(Object o) {
            if (!(o instanceof String s)) {
                return null;
            }
            try {
                return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** One parsed request; {@code error} non-null when it could not be read (answered BAD_REQUEST). */
    public record Request(String id, long account, Type type, long character, OrderBook.Side side, long price,
                          long nx, long orderId, long createdAt, String error, String token) {
        public Request(String id, long account, Type type, long character, OrderBook.Side side, long price, long nx,
                       long orderId, long createdAt, String error) {
            this(id, account, type, character, side, price, nx, orderId, createdAt, error, null);
        }
    }

    /** Parses exchange-requests.json ({@code {"requests":[...]}} or a bare array). Throws on a broken document. */
    public static List<Request> requests(String json) {
        Object doc = Json.parse(json);
        Object rows = doc instanceof Map<?, ?> m ? m.get("requests") : doc;
        List<Request> out = new ArrayList<>();
        if (!(rows instanceof List<?> list)) {
            return out;
        }
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> r)) {
                continue;
            }
            String id = r.get("id") instanceof String s ? s.trim() : null;
            if (id == null || id.isEmpty() || id.length() > 64) {
                continue; // cannot be answered without an id
            }
            Long account = num(r.get("account"));
            Type type = Type.parse(r.get("type"));
            Long created = time(r.get("createdAt"));
            String err = account == null || account <= 0 ? "account" : type == null ? "type"
                    : created == null ? "createdAt" : null;
            long character = 0, price = 0, nx = 0, orderId = 0;
            OrderBook.Side side = null;
            String token = null;
            if (err == null && type == Type.CONFIRM) {
                token = r.get("token") instanceof String t && t.matches("[0-9a-f]{8,64}") ? t : null;
                err = token == null ? "token" : null;
            }
            if (err == null && (type == Type.PLACE || type == Type.PREVIEW)) {
                Long c = num(r.get("character")), p = num(r.get("price")), n = num(r.get("nx"));
                side = OrderBook.Side.parse(r.get("side"));
                err = c == null || c <= 0 ? "character" : side == null ? "side" : p == null || p <= 0 ? "price"
                        : n == null || n <= 0 ? "nx" : null;
                character = c == null ? 0 : c;
                price = p == null ? 0 : p;
                nx = n == null ? 0 : n;
            } else if (err == null && type == Type.CANCEL) {
                Long o = num(r.get("orderId"));
                err = o == null || o <= 0 ? "orderId" : null;
                orderId = o == null ? 0 : o;
            }
            out.add(new Request(id, account == null ? 0 : account, type, character, side, price, nx, orderId,
                    created == null ? 0 : created, err, token));
        }
        return out;
    }

    /** Answers one request against the exchange. */
    public static Exchange.Response answer(Exchange ex, Request r, long now) {
        if (r.error() != null) {
            return new Exchange.Response(r.id(), false, "BAD_REQUEST", "malformed: " + r.error(), Map.of());
        }
        if (now - r.createdAt() > MAX_AGE_MS) {
            return new Exchange.Response(r.id(), false, "STALE", "request too old; send it again", Map.of());
        }
        return switch (r.type()) {
            case PLACE -> new Exchange.Response(r.id(), false, "BAD_REQUEST", "use preview then confirm", Map.of());
            case PREVIEW -> ex.preview(new Exchange.Place(r.id(), r.account(), r.character(), r.side(), r.price(),
                    r.nx()));
            case CONFIRM -> ex.confirm(r.id(), r.account(), r.token());
            case CANCEL -> ex.cancel(r.id(), r.account(), r.orderId());
            case MINE -> {
                try {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (ExchangeStore.OrderRow o : ex.myOrders(r.account(), 50)) {
                        rows.add(Exchange.orderJson(o));
                    }
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("orders", rows);
                    yield new Exchange.Response(r.id(), true, "OK", "", data);
                } catch (ExchangeStore.StoreException e) {
                    yield new Exchange.Response(r.id(), false, "ERROR", "try again later", Map.of());
                }
            }
            case BOOK -> new Exchange.Response(r.id(), true, "OK", "", book(ex, now));
            case TRADES -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("trades", trades(ex));
                yield new Exchange.Response(r.id(), true, "OK", "", data);
            }
        };
    }

    /** One response as JSON (the account is added so the site can check it). */
    public static Map<String, Object> responseJson(Exchange.Response r, long account) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.requestId());
        m.put("account", account);
        m.put("ok", r.ok());
        m.put("code", r.code());
        m.put("message", r.message());
        m.put("data", r.data());
        return m;
    }

    public static Map<String, Object> responsesFile(List<Map<String, Object>> responses, long now) {
        Map<String, Object> m = head(now);
        m.put("responses", responses);
        return m;
    }

    /** exchange-book.json: depth (20 levels a side), the official rate and the last 50 trades. No names. */
    public static Map<String, Object> book(Exchange ex, long now) {
        Map<String, Object> m = head(now);
        m.put("halted", ex.halted());
        Map<String, Object> rate = new LinkedHashMap<>();
        rate.put("mesos_per_1000nx", ex.officialRate());
        rate.put("window_hours", ex.config().rateWindowMs() / 3_600_000L);
        rate.put("min_fills", ex.config().rateMinFills());
        m.put("rate", rate);
        m.put("bids", levels(ex.depth(OrderBook.Side.BUY, 20)));
        m.put("asks", levels(ex.depth(OrderBook.Side.SELL, 20)));
        m.put("trades", trades(ex));
        return m;
    }

    private static List<Map<String, Object>> levels(List<OrderBook.Level> levels) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OrderBook.Level l : levels) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("price", l.price());
            e.put("nx", l.nx());
            e.put("orders", l.orders());
            out.add(e);
        }
        return out;
    }

    private static List<Map<String, Object>> trades(Exchange ex) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExchangeStore.FillRow f : ex.lastTrades(50)) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("t", Instant.ofEpochMilli(f.at()).toString());
            e.put("price", f.price());
            e.put("nx", f.nx());
            out.add(e);
        }
        return out;
    }

    private static Map<String, Object> head(long now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("generated_at", Instant.ofEpochMilli(now).toString());
        return m;
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

    static Long time(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Instant.parse(s.trim()).toEpochMilli();
            } catch (DateTimeParseException e) {
                return num(s);
            }
        }
        return null;
    }
}
