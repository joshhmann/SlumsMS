package soloMapling.Economy.exchange;

import soloMapling.Economy.exchange.OrderBook.Side;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ticket 113 round 2: the in-game entry, {@code @nx} (owner 2026-10-28: place in game too; {@code @exchange} stays the
 * item-exchange NPC). Text only, so it works anywhere and auto-QA can drive it:
 * <pre>
 *   @nx                         the market: best bid/ask, the official rate, your open orders
 *   @nx sell 1000 1.2m          preview selling 1,000 NX at 1.2m mesos per 1,000 NX (what fills now, fees)
 *   @nx buy 500 1150000         preview buying 500 NX at 1,150,000 per 1,000 NX
 *   @nx confirm                 place the last preview (within 60 s)
 *   @nx orders                  your orders
 *   @nx cancel 42               cancel order 42 (what it held comes straight back)
 *   @nx book                    the book's top levels
 *   @nx tickers                 every ticker: best bid/ask and last price
 *   @nx leaf                    one item ticker's market (also chaos, ws, elixir)
 *   @nx leaf sell 10 600k       preview selling 10 Maple Leaves at 600,000 mesos each (from this character's bags)
 *   @nx leaf buy 10 550k        preview buying 10 at 550,000 each (they land in your bags; held for you if full)
 *   @nx leaf book               that ticker's book
 * </pre>
 * {@code confirm}, {@code orders} and {@code cancel} work for every ticker (order ids are shared).
 * Placing and payouts use the character's {@link LiveWallet}: the escrow leaves this character (mesos) or the
 * account's NX at once, and fills, cancels and expiries pay back into memory while the player is online.
 */
public final class ExchangeChat {

    private static final AtomicLong SEQ = new AtomicLong();

    private ExchangeChat() {
    }

    /** Answers one {@code @nx} line; returns the lines to show. {@code w} is the player's own live wallet. */
    public static List<String> handle(Exchange ex, LiveWallet w, String[] params) {
        List<String> out = new ArrayList<>();
        if (ex == null) {
            out.add("The NX exchange is not open.");
            return out;
        }
        if (w == null) {
            out.add("The NX exchange is for players.");
            return out;
        }
        Ticker t = ex.ticker(Ticker.NX);
        if (params.length > 0 && !params[0].equalsIgnoreCase(Ticker.NX) && ex.ticker(params[0]) != null) {
            t = ex.ticker(params[0]);
            if (!ex.open(t.id())) {
                out.add("The " + t.id() + " ticker is closed.");
                return out;
            }
            params = java.util.Arrays.copyOfRange(params, 1, params.length);
        } else if (params.length > 0 && params[0].equalsIgnoreCase(Ticker.NX)) {
            params = java.util.Arrays.copyOfRange(params, 1, params.length);
        }
        String tk = t.item() ? t.id().toLowerCase(Locale.ROOT) + " " : "";
        String sub = params.length == 0 ? "" : params[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "buy", "sell" -> {
                Long nx = params.length > 1 ? amount(params[1]) : null;
                Long price = params.length > 2 ? amount(params[2]) : null;
                if (nx == null || price == null) {
                    out.add(t.item() ? "Use: @nx " + tk + sub + " <how many> <mesos per " + t.name() + ">, e.g. @nx "
                            + tk + sub + " 10 500k"
                            : "Use: @nx " + sub + " <NX> <mesos per 1,000 NX>, e.g. @nx " + sub + " 1000 1.2m");
                    return out;
                }
                Exchange.Response r = ex.preview(new Exchange.Place(rid(w), w.account(), w.character(),
                        sub.equals("buy") ? Side.BUY : Side.SELL, price, nx, t.id()));
                if (!r.ok()) {
                    out.add("NX exchange: " + r.message() + " (" + r.code() + ")");
                    return out;
                }
                out.addAll(previewLines(r, ex.config(), t));
            }
            case "confirm" -> {
                Exchange.Response r = ex.confirmLatest(rid(w), w.account());
                if (!r.ok()) {
                    out.add("NX exchange: " + r.message() + " (" + r.code() + ")");
                    return out;
                }
                Ticker ot = ex.ticker(String.valueOf(r.data().get("ticker")));
                if (ot == null || !ot.item()) {
                    out.add(String.format("Order %s placed: %s %,d NX at %,d. %s NX traded now; status %s.",
                            r.data().get("orderId"), String.valueOf(r.data().get("side")).toLowerCase(Locale.ROOT),
                            num(r.data().get("nx")), num(r.data().get("price")), fmt(num(r.data().get("filledNx"))),
                            r.data().get("status")));
                } else {
                    out.add(String.format("Order %s placed: %s %s at %,d each. %s traded now; status %s.",
                            r.data().get("orderId"), String.valueOf(r.data().get("side")).toLowerCase(Locale.ROOT),
                            ot.amount(num(r.data().get("nx"))), num(r.data().get("price")),
                            fmt(num(r.data().get("filledNx"))), r.data().get("status")));
                }
            }
            case "cancel" -> {
                Long id = params.length > 1 ? amount(params[1]) : null;
                if (id == null) {
                    out.add("Use: @nx cancel <order id> (see @nx orders)");
                    return out;
                }
                Exchange.Response r = ex.cancel(rid(w), w.account(), id);
                out.add(r.ok() ? "Order " + id + " cancelled; what it held is back."
                        : "NX exchange: " + r.message() + " (" + r.code() + ")");
            }
            case "orders" -> {
                try {
                    List<ExchangeStore.OrderRow> mine = ex.myOrders(w.account(), 8);
                    if (mine.isEmpty()) {
                        out.add("You have no NX exchange orders.");
                    }
                    for (ExchangeStore.OrderRow o : mine) {
                        Ticker ot = ex.ticker(o.ticker());
                        out.add(String.format("#%d %s %s at %,d: %s%s", o.id(),
                                o.side().name().toLowerCase(Locale.ROOT),
                                ot == null ? o.nxTotal() + " " + o.ticker() : ot.amount(o.nxTotal()), o.price(),
                                o.status(), o.open() ? String.format(" (%,d left)", o.nxLeft()) : ""));
                    }
                } catch (ExchangeStore.StoreException e) {
                    out.add("NX exchange: try again later.");
                }
            }
            case "book" -> {
                out.add(t.item() ? t.id() + " book (" + t.priceUnit() + "):" : "NX exchange book (mesos per 1,000 NX):");
                out.addAll(ex.bookLines(t.id(), 5));
            }
            case "tickers" -> {
                for (Ticker x : ex.tickers()) {
                    long bid = ex.depth(x.id(), Side.BUY, 1).isEmpty() ? 0 : ex.depth(x.id(), Side.BUY, 1).get(0).price();
                    long ask = ex.depth(x.id(), Side.SELL, 1).isEmpty() ? 0 : ex.depth(x.id(), Side.SELL, 1).get(0).price();
                    Long last = ex.stats(x.id()).last();
                    out.add(String.format("%s (%s): bid %s, ask %s, last %s (%s)", x.id(), x.name(),
                            bid > 0 ? fmt(bid) : "none", ask > 0 ? fmt(ask) : "none",
                            last == null ? "none" : fmt(last), x.unitLabel()));
                }
                out.add("@nx <ticker> to trade one, e.g. @nx leaf sell 10 500k");
            }
            default -> {
                if (t.item()) {
                    long bid = ex.depth(t.id(), Side.BUY, 1).isEmpty() ? 0 : ex.depth(t.id(), Side.BUY, 1).get(0).price();
                    long ask = ex.depth(t.id(), Side.SELL, 1).isEmpty() ? 0 : ex.depth(t.id(), Side.SELL, 1).get(0).price();
                    Long last = ex.stats(t.id()).last();
                    out.add(String.format("%s exchange%s: best bid %s, best ask %s, last %s (%s).", t.name(),
                            ex.halted() ? " (STOPPED)" : "", bid > 0 ? fmt(bid) : "none", ask > 0 ? fmt(ask) : "none",
                            last == null ? "none" : fmt(last), t.unitLabel()));
                    out.add("Orders " + t.minQty() + ".." + t.maxQty() + ". Selling takes tradeable copies from this "
                            + "character's bags. Fee " + pct(ex.config().feeBps()) + " of the mesos from each side.");
                    out.add("@nx " + tk + "sell <qty> <price> | @nx " + tk + "buy <qty> <price> | @nx confirm | "
                            + "@nx orders | @nx cancel <id> | @nx " + tk + "book");
                    return out;
                }
                Long rate = ex.officialRate();
                long bid = ex.depth(Side.BUY, 1).isEmpty() ? 0 : ex.depth(Side.BUY, 1).get(0).price();
                long ask = ex.depth(Side.SELL, 1).isEmpty() ? 0 : ex.depth(Side.SELL, 1).get(0).price();
                out.add(String.format("NX exchange%s: best bid %s, best ask %s, rate %s (mesos per 1,000 NX).",
                        ex.halted() ? " (STOPPED)" : "", bid > 0 ? fmt(bid) : "none", ask > 0 ? fmt(ask) : "none",
                        rate == null ? "none yet" : fmt(rate)));
                out.add("You have " + ex.openIdsOf(w.account()).size() + " open order(s). Fee "
                        + pct(ex.config().feeBps()) + " of the mesos from each side.");
                out.add("@nx sell <NX> <price> | @nx buy <NX> <price> | @nx confirm | @nx orders | @nx cancel <id>"
                        + " | @nx book | @nx tickers (items: " + itemIds(ex) + ")");
            }
        }
        return out;
    }

    private static String itemIds(Exchange ex) {
        List<String> ids = new ArrayList<>();
        for (Ticker x : ex.tickers()) {
            if (x.item()) {
                ids.add(x.id().toLowerCase(Locale.ROOT));
            }
        }
        return ids.isEmpty() ? "none" : String.join(", ", ids);
    }

    static List<String> previewLines(Exchange.Response r, ExchangeConfig c) {
        return previewLines(r, c, null);
    }

    static List<String> previewLines(Exchange.Response r, ExchangeConfig c, Ticker t) {
        if (t != null && t.item()) {
            return itemPreviewLines(r, c, t);
        }
        List<String> out = new ArrayList<>();
        var d = r.data();
        boolean sell = "SELL".equals(d.get("side"));
        @SuppressWarnings("unchecked")
        var takes = (java.util.Map<String, Object>) d.get("takes");
        @SuppressWarnings("unchecked")
        var match = (java.util.Map<String, Object>) d.get("matchNow");
        @SuppressWarnings("unchecked")
        var get = (java.util.Map<String, Object>) match.get("youGet");
        out.add(String.format("Preview: %s %,d NX at %,d mesos per 1,000 NX.", sell ? "sell" : "buy", num(d.get("nx")),
                num(d.get("price"))));
        out.add(String.format("It holds %,d %s now%s.", num(takes.get("amount")),
                "NX".equals(takes.get("asset")) ? "NX" : "mesos",
                sell ? "" : " (the price plus up to " + pct(c.feeBps()) + " fee; unused mesos come back)"));
        long now = num(match.get("nx"));
        if (now > 0) {
            out.add(String.format("Trades now: %,d NX for %,d mesos (fee %,d); you get %,d %s.", now,
                    num(match.get("mesos")), num(match.get("fee")), num(get.get("amount")),
                    "NX".equals(get.get("asset")) ? "NX" : "mesos"));
        } else {
            out.add("Nothing matches right now; the order waits in the book.");
        }
        if (Boolean.TRUE.equals(d.get("cancelledBySelfTrade"))) {
            out.add("Careful: it would meet your own order, so the rest would be cancelled.");
        }
        out.add("Type @nx confirm within " + c.confirmMs() / 1000 + " s to place it.");
        return out;
    }

    private static List<String> itemPreviewLines(Exchange.Response r, ExchangeConfig c, Ticker t) {
        List<String> out = new ArrayList<>();
        var d = r.data();
        boolean sell = "SELL".equals(d.get("side"));
        @SuppressWarnings("unchecked")
        var takes = (java.util.Map<String, Object>) d.get("takes");
        @SuppressWarnings("unchecked")
        var match = (java.util.Map<String, Object>) d.get("matchNow");
        out.add(String.format("Preview: %s %s at %,d mesos each.", sell ? "sell" : "buy", t.amount(num(d.get("nx"))),
                num(d.get("price"))));
        out.add(sell ? String.format("It takes %s from this character's bags now (tradeable copies only).",
                t.amount(num(takes.get("amount"))))
                : String.format("It holds %,d mesos now (the price plus up to %s fee; unused mesos come back).",
                num(takes.get("amount")), pct(c.feeBps())));
        long now = num(match.get("nx"));
        if (now > 0) {
            out.add(String.format("Trades now: %s for %,d mesos (fee %,d); you get %s.", t.amount(now),
                    num(match.get("mesos")), num(match.get("fee")), sell
                            ? String.format("%,d mesos", num(match.get("mesos")) - num(match.get("fee")))
                            : t.amount(now) + " (into your bags; held for you if they are full)"));
        } else {
            out.add("Nothing matches right now; the order waits in the book.");
        }
        if (Boolean.TRUE.equals(d.get("cancelledBySelfTrade"))) {
            out.add("Careful: it would meet your own order, so the rest would be cancelled.");
        }
        out.add("Type @nx confirm within " + c.confirmMs() / 1000 + " s to place it.");
        return out;
    }

    /** 1000, 1,000, 1.2m, 500k; null when it is not a whole positive number. */
    static Long amount(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim().toLowerCase(Locale.ROOT).replace(",", "");
        long mult = 1;
        if (t.endsWith("k")) {
            mult = 1_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            mult = 1_000_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("b")) {
            mult = 1_000_000_000;
            t = t.substring(0, t.length() - 1);
        }
        try {
            java.math.BigDecimal v = new java.math.BigDecimal(t).multiply(java.math.BigDecimal.valueOf(mult));
            if (v.signum() <= 0 || v.stripTrailingZeros().scale() > 0) {
                return null;
            }
            return v.longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    private static String rid(LiveWallet w) {
        return "g-" + w.character() + "-" + System.currentTimeMillis() + "-" + SEQ.incrementAndGet();
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static String fmt(long v) {
        return String.format("%,d", v);
    }

    private static String pct(int bps) {
        return bps % 100 == 0 ? bps / 100 + "%" : String.format(Locale.ROOT, "%.2f%%", bps / 100.0);
    }
}
