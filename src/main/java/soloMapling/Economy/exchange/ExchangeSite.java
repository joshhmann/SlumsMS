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
 *     {"id":"r-0","account":12,"type":"preview","character":345,"characterName":"Name" (either or both),"side":"BUY"|"SELL","price":1200000,"nx":1000,
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
 * Stolen-cookie guard (1.6.1, owner 2026-10-29; {@link NxGuard}): preview, confirm, cancel and mine carry
 * {@code "siteAuthAt"} (epoch ms of the player's last real site sign-in), refused REAUTH when missing, over 12 h old
 * or in the future; NX orders outside the band around the official rate are refused PRICE_BAND (data minPrice,
 * maxPrice, rate); NX sold through the site is capped per account per 24 h, LIMIT_NX_DAY (data capNx, usedNx, leftNx).
 * <p>
 * Each request id is answered once. A request older than {@link #MAX_AGE_MS} is answered STALE and not acted on,
 * so a backlog after downtime never places orders the player gave up on. Places are idempotent by request id even
 * across restarts (the id is stored on the order). Error codes: BAD_REQUEST, STALE, HALTED, UNAVAILABLE,
 * LOG_OUT_FIRST, INSUFFICIENT_FUNDS, NOT_OWNER, NOT_FOUND, LIMIT_OPEN, LIMIT_DAY, COOLDOWN, DUPLICATE, ERROR,
 * BAD_INPUT (character and characterName name different characters), TOKEN_EXPIRED and TRY_AGAIN (the player is changing channel or in the Cash Shop). A player who is logged in can
 * place from the site too, as long as the order names the character they are playing (else LOG_OUT_FIRST).
 * <p>
 * Preview data: {@code token, expiresAt, side, price, nx, takes:{asset,amount} (escrow, plus a buy's fee reserve),
 * feeBpsEachSide, matchNow:{nx,mesos,fee,youGet:{asset,amount}}, restsNx, cancelledBySelfTrade}.
 * <p>
 * Tickers (owner 2026-10-28): preview, book and trades take an optional {@code "ticker"} ("NX" when absent; also
 * LEAF, CHAOS, WS, ELIXIR) and preview takes {@code "qty"} as another name for {@code "nx"}. An item ticker's price is
 * mesos per one item. Selling items needs the player in game (LOG_IN_FIRST from the site while offline: items leave
 * the bags only through the live character); buying from the site is fine, the items wait until that character is
 * in game. Two more public outbox files (shapes in docs/tickets/113, "Tickers"): {@link #TICKERS}, every ticker's
 * summary, depth and last trades, and {@link #HISTORY}, hourly OHLC candles per ticker for 30 days.
 */
public final class ExchangeSite {

    public static final int SCHEMA = 1;
    public static final long MAX_AGE_MS = 10 * 60_000L;
    public static final int KEEP_RESPONSES = 500;
    public static final String REQUESTS = "exchange-requests.json";
    public static final String RESPONSES = "exchange-responses.json";
    public static final String BOOK = "exchange-book.json";
    public static final String TICKERS = "exchange-tickers.json";
    public static final String HISTORY = "exchange-history.json";

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
                          long nx, long orderId, long createdAt, String error, String token, String characterName,
                          String ticker, Long siteAuthAt) {
        public Request {
            ticker = Ticker.norm(ticker) == null ? Ticker.NX : Ticker.norm(ticker);
        }

        /** Without a site sign-in time (refused REAUTH where one is needed, unless the check is off). */
        public Request(String id, long account, Type type, long character, OrderBook.Side side, long price, long nx,
                       long orderId, long createdAt, String error, String token, String characterName, String ticker) {
            this(id, account, type, character, side, price, nx, orderId, createdAt, error, token, characterName, ticker,
                    null);
        }

        /** The same request signed in at {@code at} (tests). */
        public Request signedInAt(Long at) {
            return new Request(id, account, type, character, side, price, nx, orderId, createdAt, error, token,
                    characterName, ticker, at);
        }

        public Request(String id, long account, Type type, long character, OrderBook.Side side, long price, long nx,
                       long orderId, long createdAt, String error, String token, String ticker) {
            this(id, account, type, character, side, price, nx, orderId, createdAt, error, token, null, ticker);
        }

        public Request(String id, long account, Type type, long character, OrderBook.Side side, long price, long nx,
                       long orderId, long createdAt, String error, String token) {
            this(id, account, type, character, side, price, nx, orderId, createdAt, error, token, null, Ticker.NX);
        }

        public Request(String id, long account, Type type, long character, OrderBook.Side side, long price, long nx,
                       long orderId, long createdAt, String error) {
            this(id, account, type, character, side, price, nx, orderId, createdAt, error, null, null, Ticker.NX);
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
            Long authAt = r.get("siteAuthAt") == null ? null : time(r.get("siteAuthAt"));
            String ticker = r.get("ticker") == null ? Ticker.NX
                    : r.get("ticker") instanceof String s && s.matches("[A-Za-z]{1,8}") ? s : null;
            String err = account == null || account <= 0 ? "account" : type == null ? "type"
                    : created == null ? "createdAt" : ticker == null ? "ticker" : null;
            long character = 0, price = 0, nx = 0, orderId = 0;
            OrderBook.Side side = null;
            String token = null;
            String charName = null;
            if (err == null && (type == Type.PLACE || type == Type.PREVIEW || type == Type.MINE)) {
                Object cn = r.get("characterName");
                if (cn != null && !(cn instanceof String)) {
                    err = "characterName";
                } else if (cn instanceof String cs && !cs.isBlank()) {
                    charName = cs.trim();
                    err = charName.length() > 32 ? "characterName" : null;
                }
                Long c0 = num(r.get("character"));
                if (err == null && type == Type.MINE && r.get("character") != null && (c0 == null || c0 <= 0)) {
                    err = "character";
                }
                character = c0 == null ? 0 : c0;
            }
            if (err == null && type == Type.CONFIRM) {
                token = r.get("token") instanceof String t && t.matches("[0-9a-f]{8,64}") ? t : null;
                err = token == null ? "token" : null;
            }
            if (err == null && (type == Type.PLACE || type == Type.PREVIEW)) {
                Long c = num(r.get("character")), p = num(r.get("price")),
                        n = num(r.get("nx") != null ? r.get("nx") : r.get("qty"));
                side = OrderBook.Side.parse(r.get("side"));
                boolean haveChar = (c != null && c > 0) || charName != null;
                err = !haveChar ? "character" : side == null ? "side" : p == null || p <= 0 ? "price"
                        : n == null || n <= 0 ? "nx" : null;
                character = c == null || c < 0 ? 0 : c;
                price = p == null ? 0 : p;
                nx = n == null ? 0 : n;
            } else if (err == null && type == Type.CANCEL) {
                Long o = num(r.get("orderId"));
                err = o == null || o <= 0 ? "orderId" : null;
                orderId = o == null ? 0 : o;
            }
            out.add(new Request(id, account == null ? 0 : account, type, character, side, price, nx, orderId,
                    created == null ? 0 : created, err, token, charName, ticker, authAt));
        }
        return out;
    }

    /**
     * 1.6.1 stolen-cookie guard (owner 2026-10-29): a request that acts for the account (preview, confirm, cancel,
     * mine; also the retired place) must carry {@code siteAuthAt}, the player's last real sign-in on the site (epoch ms
     * or ISO-8601), no older than {@link NxGuard#siteAuthMaxMs} (12 h) and not more than {@link NxGuard#SKEW_MS} in the
     * future; else REAUTH. Book and trades are public and need none. Off with {@code siteAuthMaxHours=0}.
     */
    static Exchange.Response reauth(Exchange ex, Request r, long now) {
        long max = ex.guard().siteAuthMaxMs();
        if (max <= 0 || r.type() == Type.BOOK || r.type() == Type.TRADES) {
            return null;
        }
        Long at = r.siteAuthAt();
        if (at != null && at <= now + NxGuard.SKEW_MS && now - at <= max) {
            return null;
        }
        long hours = max / 3_600_000L;
        return new Exchange.Response(r.id(), false, "REAUTH", "sign in again on the site (your last sign-in was "
                + (at == null ? "not sent" : at > now ? "in the future" : "over " + hours + " hours ago") + ")",
                Map.of("maxAgeHours", hours));
    }

    /** Answers one request against the exchange. */
    public static Exchange.Response answer(Exchange ex, Request r, long now) {
        if (r.error() != null) {
            return new Exchange.Response(r.id(), false, "BAD_REQUEST", "malformed: " + r.error(), Map.of());
        }
        if (now - r.createdAt() > MAX_AGE_MS) {
            return new Exchange.Response(r.id(), false, "STALE", "request too old; send it again", Map.of());
        }
        Exchange.Response reauth = reauth(ex, r, now);
        if (reauth != null) {
            return reauth;
        }
        long character = r.character();
        if (r.type() == Type.PREVIEW || r.type() == Type.PLACE || r.type() == Type.MINE) {
            long c = ex.resolveCharacter(r.account(), r.character(), r.characterName());
            if (c == Exchange.NOT_OWNER_CHAR) {
                return new Exchange.Response(r.id(), false, "NOT_OWNER", "that character is not on this account", Map.of());
            }
            if (c == Exchange.MISMATCH_CHAR) {
                return new Exchange.Response(r.id(), false, "BAD_INPUT", "character and characterName are different characters", Map.of());
            }
            if (c == Exchange.STORE_ERROR_CHAR) {
                return new Exchange.Response(r.id(), false, "ERROR", "try again later", Map.of());
            }
            character = c;
        }
        final long resolved = character;
        return switch (r.type()) {
            case PLACE -> new Exchange.Response(r.id(), false, "BAD_REQUEST", "use preview then confirm", Map.of());
            case PREVIEW -> ex.preview(new Exchange.Place(r.id(), r.account(), resolved, r.side(), r.price(),
                    r.nx(), r.ticker()), true);
            case CONFIRM -> ex.confirm(r.id(), r.account(), r.token());
            case CANCEL -> ex.cancel(r.id(), r.account(), r.orderId());
            case MINE -> {
                try {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (ExchangeStore.OrderRow o : ex.myOrders(r.account(), 50)) {
                        if (resolved > 0 && o.character() != resolved) {
                            continue;
                        }
                        rows.add(Exchange.orderJson(o));
                    }
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("orders", rows);
                    yield new Exchange.Response(r.id(), true, "OK", "", data);
                } catch (ExchangeStore.StoreException e) {
                    yield new Exchange.Response(r.id(), false, "ERROR", "try again later", Map.of());
                }
            }
            case BOOK -> ex.ticker(r.ticker()) == null
                    ? new Exchange.Response(r.id(), false, "BAD_REQUEST", "unknown ticker", Map.of())
                    : new Exchange.Response(r.id(), true, "OK", "", Ticker.NX.equals(r.ticker()) ? book(ex, now)
                    : tickerJson(ex, ex.ticker(r.ticker()), 20, 50));
            case TRADES -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("ticker", r.ticker());
                data.put("trades", trades(ex, r.ticker(), 50));
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
        m.put("ticker", Ticker.NX);
        m.put("unit", "1000nx");
        m.put("unit_label", "mesos per 1,000 NX");
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

    private static List<Map<String, Object>> trades(Exchange ex, String ticker, int n) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExchangeStore.FillRow f : ex.lastTrades(ticker, n)) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("t", Instant.ofEpochMilli(f.at()).toString());
            e.put("price", f.price());
            e.put("qty", f.nx());
            out.add(e);
        }
        return out;
    }

    private static List<Map<String, Object>> qtyLevels(List<OrderBook.Level> levels) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OrderBook.Level l : levels) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("price", l.price());
            e.put("qty", l.nx());
            e.put("orders", l.orders());
            out.add(e);
        }
        return out;
    }

    /** One ticker's public state: what it is, its 24 h numbers, depth and last trades. No names, no ids. */
    public static Map<String, Object> tickerJson(Exchange ex, Ticker t, int levels, int trades) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("name", t.name());
        m.put("item_id", t.item() ? t.asset().itemId() : null);
        m.put("unit", t.unit());
        m.put("unit_label", t.unitLabel());
        m.put("per", t.per());
        m.put("qty_label", t.item() ? "items" : "NX");
        m.put("min_qty", t.minQty());
        m.put("max_qty", t.maxQty());
        m.put("lot", t.lot());
        m.put("min_price", t.minPrice());
        m.put("max_price", t.maxPrice());
        m.put("max_open", t.maxOpen());
        m.put("per_day", t.perDay());
        m.put("expiry_hours", t.expiryMs() / 3_600_000L);
        List<OrderBook.Level> bids = ex.depth(t.id(), OrderBook.Side.BUY, levels);
        List<OrderBook.Level> asks = ex.depth(t.id(), OrderBook.Side.SELL, levels);
        m.put("bid", bids.isEmpty() ? null : bids.get(0).price());
        m.put("ask", asks.isEmpty() ? null : asks.get(0).price());
        TickerHistory.Stats s = ex.stats(t.id());
        m.put("last", s.last());
        m.put("change_24h", s.change());
        m.put("change_24h_pct", s.change() == null || s.reference() == null || s.reference() == 0 ? null
                : Math.round(s.change() * 10_000.0 / s.reference()) / 100.0);
        m.put("high_24h", s.high());
        m.put("low_24h", s.low());
        m.put("volume_24h", s.qty());
        m.put("turnover_24h", s.mesos());
        m.put("fills_24h", s.fills());
        m.put("rate", ex.officialRate(t.id()));
        // 1.6.1 volatility: the fair value from real drops and sinks (FairValue); null for NX and until computed
        m.put("fair", t.item() ? soloMapling.Economy.exchange.volatility.FairValue.of(t.id()) : null);
        m.put("bids", qtyLevels(bids));
        m.put("asks", qtyLevels(asks));
        m.put("trades", trades(ex, t.id(), trades));
        return m;
    }

    /** exchange-tickers.json: every open ticker (NX first), 10 levels a side and the last 20 trades each. */
    public static Map<String, Object> tickers(Exchange ex, long now) {
        Map<String, Object> m = head(now);
        m.put("halted", ex.halted());
        m.put("fee_bps_each_side", ex.config().feeBps());
        m.put("rate_window_hours", ex.config().rateWindowMs() / 3_600_000L);
        m.put("rate_min_fills", ex.config().rateMinFills());
        // limits (owner 2026-10-28): the item tickers' (null when they differ between item tickers; every ticker
        // also carries its own max_open / per_day / expiry_hours, so NX's are there too); the day's count is per
        // ticker, the cooldown per account across every ticker
        m.put("max_open_per_ticker", itemCommon(ex, t -> (long) t.maxOpen()));
        m.put("per_day", itemCommon(ex, t -> (long) t.perDay()));
        m.put("per_day_scope", "ticker");
        m.put("cooldown_sec", ex.config().cooldownMs() / 1000);
        m.put("expiry_hours", itemCommon(ex, t -> t.expiryMs() / 3_600_000L));
        m.put("events", announcedEvents(now));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Ticker t : ex.tickers()) {
            list.add(tickerJson(ex, t, 10, 20));
        }
        m.put("tickers", list);
        return m;
    }

    /**
     * The site banner's market events (1.6.1 volatility): ANNOUNCED market events and guild news only, never a secret
     * one: {@code [{id, name, ticker, ends_at}]}, {@code ends_at} unix seconds or null when open-ended.
     */
    static List<Map<String, Object>> announcedEvents(long now) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            out.addAll(soloMapling.Economy.exchange.volatility.MarketEvents.announcedNow(now));
            out.addAll(soloMapling.Economy.exchange.volatility.MarketNews.announcedNow(now));
        } catch (RuntimeException e) {
            // the banner is optional; the export never fails for it
        }
        return out;
    }

    /** The value every open item ticker shares, or null when they differ (or none is open). */
    private static Long itemCommon(Exchange ex, java.util.function.ToLongFunction<Ticker> f) {
        Long v = null;
        for (Ticker t : ex.tickers()) {
            if (!t.item()) {
                continue;
            }
            long x = f.applyAsLong(t);
            if (v != null && v != x) {
                return null;
            }
            v = x;
        }
        return v;
    }

    /** exchange-history.json: hourly OHLC candles per open ticker, the last 30 days, only hours that traded. */
    public static Map<String, Object> history(Exchange ex, long now) {
        Map<String, Object> m = head(now);
        m.put("interval", "1h");
        m.put("days", TickerHistory.KEEP_MS / TickerHistory.DAY_MS);
        Map<String, Object> per = new LinkedHashMap<>();
        for (Ticker t : ex.tickers()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("unit", t.unit());
            e.put("unit_label", t.unitLabel());
            List<Map<String, Object>> candles = new ArrayList<>();
            for (TickerHistory.Candle c : ex.candles(t.id())) {
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("t", Instant.ofEpochMilli(c.hour()).toString());
                k.put("o", c.open());
                k.put("h", c.high());
                k.put("l", c.low());
                k.put("c", c.close());
                k.put("v", c.qty());
                k.put("m", c.mesos());
                k.put("n", c.fills());
                candles.add(k);
            }
            e.put("candles", candles);
            per.put(t.id(), e);
        }
        m.put("tickers", per);
        return m;
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
