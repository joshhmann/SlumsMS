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
 * </pre>
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
        String sub = params.length == 0 ? "" : params[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "buy", "sell" -> {
                Long nx = params.length > 1 ? amount(params[1]) : null;
                Long price = params.length > 2 ? amount(params[2]) : null;
                if (nx == null || price == null) {
                    out.add("Use: @nx " + sub + " <NX> <mesos per 1,000 NX>, e.g. @nx " + sub + " 1000 1.2m");
                    return out;
                }
                Exchange.Response r = ex.preview(new Exchange.Place(rid(w), w.account(), w.character(),
                        sub.equals("buy") ? Side.BUY : Side.SELL, price, nx));
                if (!r.ok()) {
                    out.add("NX exchange: " + r.message() + " (" + r.code() + ")");
                    return out;
                }
                out.addAll(previewLines(r, ex.config()));
            }
            case "confirm" -> {
                Exchange.Response r = ex.confirmLatest(rid(w), w.account());
                if (!r.ok()) {
                    out.add("NX exchange: " + r.message() + " (" + r.code() + ")");
                    return out;
                }
                out.add(String.format("Order %s placed: %s %,d NX at %,d. %s NX traded now; status %s.",
                        r.data().get("orderId"), String.valueOf(r.data().get("side")).toLowerCase(Locale.ROOT),
                        num(r.data().get("nx")), num(r.data().get("price")), fmt(num(r.data().get("filledNx"))),
                        r.data().get("status")));
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
                        out.add(String.format("#%d %s %,d NX at %,d: %s%s", o.id(),
                                o.side().name().toLowerCase(Locale.ROOT), o.nxTotal(), o.price(), o.status(),
                                o.open() ? String.format(" (%,d left)", o.nxLeft()) : ""));
                    }
                } catch (ExchangeStore.StoreException e) {
                    out.add("NX exchange: try again later.");
                }
            }
            case "book" -> {
                out.add("NX exchange book (mesos per 1,000 NX):");
                out.addAll(ex.bookLines(5));
            }
            default -> {
                Long rate = ex.officialRate();
                long bid = ex.depth(Side.BUY, 1).isEmpty() ? 0 : ex.depth(Side.BUY, 1).get(0).price();
                long ask = ex.depth(Side.SELL, 1).isEmpty() ? 0 : ex.depth(Side.SELL, 1).get(0).price();
                out.add(String.format("NX exchange%s: best bid %s, best ask %s, rate %s (mesos per 1,000 NX).",
                        ex.halted() ? " (STOPPED)" : "", bid > 0 ? fmt(bid) : "none", ask > 0 ? fmt(ask) : "none",
                        rate == null ? "none yet" : fmt(rate)));
                out.add("You have " + ex.openIdsOf(w.account()).size() + " open order(s). Fee "
                        + pct(ex.config().feeBps()) + " of the mesos from each side.");
                out.add("@nx sell <NX> <price> | @nx buy <NX> <price> | @nx confirm | @nx orders | @nx cancel <id>"
                        + " | @nx book");
            }
        }
        return out;
    }

    static List<String> previewLines(Exchange.Response r, ExchangeConfig c) {
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
