package soloMapling.Economy.exchange;

import soloMapling.Economy.exchange.ExchangeStore.Batch;
import soloMapling.Economy.exchange.ExchangeStore.Failure;
import soloMapling.Economy.exchange.ExchangeStore.FillRow;
import soloMapling.Economy.exchange.ExchangeStore.OrderRow;
import soloMapling.Economy.exchange.ExchangeStore.StoreException;
import soloMapling.Economy.exchange.Ledger.Asset;
import soloMapling.Economy.exchange.Ledger.Bucket;
import soloMapling.Economy.exchange.Ledger.Kind;
import soloMapling.Economy.exchange.Ledger.Posting;
import soloMapling.Economy.exchange.Ledger.Why;
import soloMapling.Economy.exchange.OrderBook.Side;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

/**
 * Ticket 113: the NX/meso exchange (owner 2026-10-27: an order book, "like forex"). Wraps the pure {@link OrderBook}
 * with escrow and settlement through an {@link ExchangeStore}. Every public method is synchronized: one order at a
 * time, and the in-memory book only changes when the store accepted the matching batch (otherwise the book is rolled
 * back to the copy taken before), so the book and the database never disagree.
 * <p>
 * Money flow (all of it ledger postings, {@link Ledger}):
 * <ol>
 *   <li><b>place</b>: the seller's NX (accounts.nxCredit) or the buyer's mesos (the named character's meso) move into
 *   the order's ESCROW, in the same transaction as the order row, the fills it makes at once and their settlement.
 *   The wallet is touched only while the whole account is offline: the game holds wallets in memory and saves them
 *   whole, so a wallet changed under a logged-in player would be overwritten (a dupe or a loss).</li>
 *   <li><b>fill</b>: escrow to escrow-free PAYOUT, both sides in one transaction: the seller's escrowed NX to the
 *   buyer's NX payout, the buyer's escrowed mesos to the seller's meso payout (less the fee, if any). A fill never
 *   needs the counterparty offline.</li>
 *   <li><b>cancel / expiry / fully filled</b>: what is left in the order's escrow goes to its owner's PAYOUT.</li>
 *   <li><b>collect</b>: PAYOUT to the wallets: offline accounts through the database (each tick, and before a place);
 *   a logged-in player's live character and its NX directly in memory, under the character's save lock, with the
 *   same delta written in the same transaction as the ledger ({@link LiveWallet}).</li>
 * </ol>
 * Round 2 (owner 2026-10-28): placing works while logged in too, from the game ({@code @nx}) or the site, through the
 * {@link LiveWallet} of the account's live character; the site's place is a preview then a confirm with a short-lived
 * token; fees are taken from both sides (a buy reserves its own fee in FEEHOLD); staff can reverse a fill while halted.
 * {@link #halt} is the emergency stop: no new orders, no fills, no expiry, no collection; cancels still work (escrow
 * to payout stays inside the exchange). Start-up runs {@link #recover}, which halts on any ledger mismatch.
 * <p>
 * Tickers (owner 2026-10-28): one {@link OrderBook} per {@link Ticker} (NX, LEAF, CHAOS, WS, ELIXIR), all priced in
 * mesos, sharing order ids, batches, the ledger and the limits' day count. An item ticker escrows the item itself:
 * it leaves the live character's bags on a sell (never offline), and fills, cancels and expiries pay it into the
 * owning character's PAYOUT, which {@link #collectLive} empties into the bags as far as they have room. Regulars quote
 * NX only. Every NX method without a ticker works as before.
 */
public final class Exchange {

    private static final Logger log = LoggerFactory.getLogger(Exchange.class);

    /** Is any character of this account in the game right now (the game's view; the store checks the DB too)? */
    public interface Presence extends LongPredicate {
    }

    /** One answer to the site or the GM command. {@code code} is OK or an error code (docs/tickets/113). */
    public record Response(String requestId, boolean ok, String code, String message, Map<String, Object> data) {
        static Response ok(String requestId, Map<String, Object> data) {
            return new Response(requestId, true, "OK", "", data);
        }

        static Response error(String requestId, String code, String message) {
            return new Response(requestId, false, code, message, Map.of());
        }
    }

    /**
     * A place request: {@code character} is the account's character whose mesos pay (buy) or receive (sell).
     * {@code ticker} is the book ({@link Ticker}, "NX" when omitted); for an item ticker {@code nx} is the number of
     * items and {@code price} is mesos per item, and the items leave (sell) or reach (buy) this character's bags.
     */
    public record Place(String requestId, long account, long character, Side side, long price, long nx,
                        String ticker) {
        public Place {
            ticker = Ticker.norm(ticker) == null ? Ticker.NX : Ticker.norm(ticker);
        }

        public Place(String requestId, long account, long character, Side side, long price, long nx) {
            this(requestId, account, character, side, price, nx, Ticker.NX);
        }
    }

    private static final int RECENT_FILLS = 2000;

    /**
     * 1.6.1 volatility: a trader bot's orders carry the bot's character id as their account (bots share one game
     * account, and real account ids are far below this), so limits, self-trade and cancels are per bot.
     */
    public static final long BOT_ACCOUNT_BASE = 1_000_000_000L;

    /** Is this order account a trader bot (its character id)? */
    public static boolean botAccount(long account) {
        return account >= BOT_ACCOUNT_BASE;
    }

    /** A previewed place waiting for its confirm. */
    private record Pending(Place place, long expiresAt) {
    }

    private final ExchangeStore store;
    private final ExchangeConfig config;
    private final Presence presence;
    private final LiveWallet.Finder live;
    private final LongSupplier clock;
    /** Open buy order id -> its FEEHOLD balance (rebuilt from the store at recovery). */
    private final Map<Long, Long> feeHold = new HashMap<>();
    private final Map<String, Pending> pending = new HashMap<>();
    private final Map<Long, String> latestToken = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    /** Every ticker the exchange knows (a book each, so recovery always finds one), and the ones open for orders. */
    private final Map<String, Ticker> tickers;
    private final java.util.Set<String> openTickers;
    private Map<String, OrderBook> books = new LinkedHashMap<>();
    private final Map<Long, OrderRow> rows = new HashMap<>();
    private final Map<String, Deque<FillRow>> recent = new HashMap<>();
    private final Map<String, TickerHistory> history = new HashMap<>();
    /**
     * Fills the QA checks' test regular ({@link ExchangeQa#REGULAR}) took part in (1.6.1 fix, TEST 2026-10-29: the
     * xc-1/xc-2 checks printed NX at 1,001,000 and LEAF at 1,000 onto the public charts). They settle like any fill and
     * stay in the store, the ledger and the staff's {@code !exchange fills}, but they are not market activity: they
     * never reach the public trades, {@code last}, high/low/volume, the candles, the official rate or the fair value's
     * flows ({@link #lastTrades}, {@link #isQaFill}).
     */
    private final java.util.Set<Long> qaFills = new java.util.HashSet<>();
    /** Character id -> when it was last told its bags are too full for an item payout. */
    private final Map<Long, Long> roomNagged = new HashMap<>();
    /** Trader bots (character ids) with something waiting in a PAYOUT ({@link #collectBot}). */
    private final java.util.Set<Long> botPayouts = new java.util.TreeSet<>();
    private long nextOrder = 1, nextFill = 1, nextSeq = 1, nextBatch = 1;
    private volatile boolean halted;
    private volatile String haltReason = "";
    private boolean recovered;
    /** Set once a place's batch is in the store (a live place never gives memory back after that). */
    private boolean committed;

    public Exchange(ExchangeStore store, ExchangeConfig config, Presence presence, LongSupplier clock) {
        this(store, config, presence, LiveWallet.Finder.NONE, clock);
    }

    public Exchange(ExchangeStore store, ExchangeConfig config, Presence presence, LiveWallet.Finder live,
                    LongSupplier clock) {
        this(store, config, presence, live, clock, Ticker.defaults(config));
    }

    /** With the open tickers (NX is always open; every known ticker still gets a book for recovery). */
    public Exchange(ExchangeStore store, ExchangeConfig config, Presence presence, LiveWallet.Finder live,
                    LongSupplier clock, List<Ticker> open) {
        this.store = store;
        this.config = config;
        this.presence = presence;
        this.live = live == null ? LiveWallet.Finder.NONE : live;
        this.clock = clock;
        Map<String, Ticker> all = Ticker.byId(Ticker.defaults(config));
        java.util.Set<String> on = new java.util.LinkedHashSet<>();
        on.add(Ticker.NX);
        for (Ticker t : open == null ? List.<Ticker>of() : open) {
            all.put(t.id(), t);
            on.add(t.id());
        }
        this.tickers = java.util.Collections.unmodifiableMap(all);
        this.openTickers = java.util.Collections.unmodifiableSet(on);
        this.books = newBooks();
    }

    private Map<String, OrderBook> newBooks() {
        Map<String, OrderBook> m = new LinkedHashMap<>();
        for (Ticker t : tickers.values()) {
            m.put(t.id(), new OrderBook(t.per()));
        }
        return m;
    }

    private Map<String, OrderBook> copyBooks() {
        Map<String, OrderBook> m = new LinkedHashMap<>();
        books.forEach((k, v) -> m.put(k, v.copy()));
        return m;
    }

    private OrderBook nxBook() {
        return books.get(Ticker.NX);
    }

    // ---------------------------------------------------------------- recovery

    /**
     * Rebuilds the book from the store's open orders and reconciles: every stored exchange balance must equal the
     * ledger's sum for that bucket, and every open order's escrow must equal what the order says it holds (no escrow
     * on closed orders). Any mismatch halts the exchange (nothing is auto-fixed) and is returned.
     */
    public synchronized List<String> recover() throws StoreException {
        ExchangeStore.Loaded l = store.load(RECENT_FILLS);
        List<FillRow> past = store.fillsSince(clock.getAsLong() - TickerHistory.KEEP_MS);
        books = newBooks();
        rows.clear();
        recent.clear();
        history.clear();
        Map<String, List<OrderBook.Order>> open = new HashMap<>();
        List<String> unknown = new ArrayList<>();
        for (OrderRow r : l.openOrders()) {
            if (!books.containsKey(r.ticker())) {
                unknown.add("order " + r.id() + " is on an unknown ticker " + r.ticker());
                continue;
            }
            rows.put(r.id(), r);
            open.computeIfAbsent(r.ticker(), k -> new ArrayList<>()).add(toOrder(r));
        }
        open.forEach((t, os) -> books.get(t).restoreAll(os));
        qaFills.clear();
        Map<Long, Long> regularOf = new HashMap<>();
        for (FillRow f : past) {
            markQa(f, regularOf);
        }
        for (FillRow f : l.recentFills()) {
            markQa(f, regularOf);
        }
        for (FillRow f : past) {
            if (!qaFills.contains(f.id())) {
                history.computeIfAbsent(f.ticker(), k -> new TickerHistory()).add(f);
            }
        }
        feeHold.clear();
        botPayouts.clear();
        for (Map.Entry<Bucket, Long> e : l.balances().entrySet()) {
            if (e.getKey().kind() == Kind.FEEHOLD && e.getValue() > 0) {
                feeHold.put(e.getKey().id(), e.getValue());
            }
            if (e.getKey().kind() == Kind.PAYOUT && botAccount(e.getKey().id()) && e.getValue() > 0) {
                botPayouts.add(e.getKey().id());
            }
        }
        for (FillRow f : l.recentFills()) {
            recent.computeIfAbsent(f.ticker(), k -> new ArrayDeque<>()).addLast(f);
        }
        nextOrder = l.maxOrderId() + 1;
        nextFill = l.maxFillId() + 1;
        nextSeq = l.maxSeq() + 1;
        nextBatch = l.maxBatch() + 1;
        recovered = true;
        List<String> problems = new ArrayList<>(unknown);
        problems.addAll(reconcile(l));
        if (!problems.isEmpty()) {
            halt("reconcile: " + problems.size() + " mismatch(es), first: " + problems.get(0));
        }
        return problems;
    }

    public static List<String> reconcile(ExchangeStore.Loaded l) {
        List<String> out = new ArrayList<>();
        Map<Bucket, Long> want = new HashMap<>(l.ledgerSums());
        Map<Bucket, Long> have = new HashMap<>(l.balances());
        for (Bucket b : union(want, have)) {
            long w = want.getOrDefault(b, 0L), h = have.getOrDefault(b, 0L);
            if (w != h) {
                out.add(b + " stored " + h + ", ledger " + w);
            }
            if (h < 0) {
                out.add(b + " negative " + h);
            }
        }
        Map<Long, OrderRow> open = new HashMap<>();
        for (OrderRow r : l.openOrders()) {
            open.put(r.id(), r);
            if (assetOf(r.ticker()) == null || assetOf(r.ticker()) == Asset.MESO) {
                out.add("order " + r.id() + " has an unknown ticker " + r.ticker());
                continue;
            }
            Bucket e = escrowOf(r.ticker(), r.side(), r.id());
            long held = r.side() == Side.SELL ? r.nxLeft() : r.mesosHeld();
            if (have.getOrDefault(e, 0L) != held) {
                out.add("order " + r.id() + " holds " + held + " but " + e + " is " + have.getOrDefault(e, 0L));
            }
            if (r.nxLeft() <= 0 || (r.side() == Side.BUY
                    && r.mesosHeld() < Math.multiplyExact(r.nxLeft(), r.price()) / perOf(r.ticker()))) {
                out.add("order " + r.id() + " is open with a bad remainder");
            }
        }
        for (Map.Entry<Bucket, Long> e : have.entrySet()) {
            Kind k = e.getKey().kind();
            if ((k == Kind.ESCROW || k == Kind.FEEHOLD) && e.getValue() != 0) {
                OrderRow o = open.get(e.getKey().id());
                if (o == null || (k == Kind.FEEHOLD && o.side() != Side.BUY)) {
                    out.add(e.getKey() + " holds " + e.getValue() + " for an order that is not an open "
                            + (k == Kind.FEEHOLD ? "buy" : "order"));
                }
            }
        }
        return out;
    }

    private static java.util.Set<Bucket> union(Map<Bucket, Long> a, Map<Bucket, Long> b) {
        java.util.Set<Bucket> s = new java.util.HashSet<>(a.keySet());
        s.addAll(b.keySet());
        return s;
    }

    // ---------------------------------------------------------------- orders

    public synchronized Response place(Place p) {
        String rid = p.requestId();
        if (!recovered) {
            return Response.error(rid, "UNAVAILABLE", "exchange not started");
        }
        if (botAccount(p.account())) {
            return Response.error(rid, "BAD_REQUEST", "account/character");
        }
        if (halted) {
            return Response.error(rid, "HALTED", "the exchange is stopped: " + haltReason);
        }
        String bad = validate(p);
        if (bad != null) {
            return Response.error(rid, "BAD_REQUEST", bad);
        }
        try {
            OrderRow earlier = store.byRequest(rid);
            if (earlier != null) {
                if (earlier.account() != p.account()) {
                    return Response.error(rid, "DUPLICATE", "request id already used");
                }
                return Response.ok(rid, orderJson(earlier));
            }
        } catch (StoreException e) {
            return storeError(rid, e);
        }
        long now = clock.getAsLong();
        Ticker t = tickers.get(p.ticker());
        String limit = limits(p.account(), t, now);
        if (limit != null) {
            return Response.error(rid, limit, limitMessage(limit, t));
        }
        LiveWallet w = live.find(p.account());
        if (w != null) {
            if (w.character() != p.character()) {
                return Response.error(rid, "LOG_OUT_FIRST",
                        "you are logged in as another character: use that one, or log out");
            }
            return placeLive(p, t, w, now);
        }
        if (t.item() && p.side() == Side.SELL) {
            return Response.error(rid, "LOG_IN_FIRST", itemsInGame(t));
        }
        if (presence.test(p.account())) {
            return Response.error(rid, "LOG_OUT_FIRST", "log out of the game to place an order");
        }
        collectQuietly(p.account());
        return placeOrder(rid, p.account(), p.character(), 0, t, p.side(), p.price(), p.nx(), now, null);
    }

    private static String itemsInGame(Ticker t) {
        return t.name() + " is sold from your bags: log in and use @nx " + t.id().toLowerCase(java.util.Locale.ROOT)
                + " sell";
    }

    /** "NX" / "mesos" / the item's name. */
    private String unitName(Asset a) {
        if (a == Asset.NX) {
            return "NX";
        }
        if (a == Asset.MESO) {
            return "mesos";
        }
        Ticker t = tickers.get(a.name());
        return "tradeable " + (t == null ? a.name() : t.name());
    }

    /**
     * A place for a logged-in player ({@link LiveWallet}): under the character's save lock, save it, take the escrow
     * (and a buy's fee reserve) out of memory, then write order, escrow, ledger, fills and the wallet's delta in one
     * transaction; give it back in memory if that fails. Payouts from fills made at once land in memory straight away.
     */
    private Response placeLive(Place p, Ticker t, LiveWallet w, long now) {
        String rid = p.requestId();
        Asset a = p.side() == Side.SELL ? t.asset() : Asset.MESO;
        long amount = p.side() == Side.SELL ? p.nx() : t.buyTotal(config, p.nx(), p.price());
        return w.locked(() -> {
            if (!w.usable()) {
                return Response.error(rid, "TRY_AGAIN",
                        "you are changing channel, logging out or in the Cash Shop; try again in a moment");
            }
            if (!w.saveNow()) {
                return Response.error(rid, "ERROR", "could not save your character first; try again later");
            }
            if (!w.take(a, amount)) {
                return Response.error(rid, "INSUFFICIENT_FUNDS", "you need " + amount + " " + unitName(a) + " on "
                        + (a == Asset.NX ? "your account" : "this character"));
            }
            Response placed;
            committed = false;
            try {
                placed = placeOrder(rid, p.account(), p.character(), 0, t, p.side(), p.price(), p.nx(), now, w);
            } catch (RuntimeException e) {
                if (!committed) {
                    giveBack(w, a, amount, "a failed place " + rid);
                } else {
                    halt("place " + rid + " failed after its commit: " + e);
                }
                log.error("NX exchange: place {} failed", rid, e);
                return Response.error(rid, "ERROR", "try again later");
            }
            if (!placed.ok()) {
                giveBack(w, a, amount, "a refused place " + rid);
            } else {
                collectLiveLocked(w);
            }
            return placed;
        });
    }

    /** Puts back in memory what a refused write took. Should never fail: the lock is held and it was just taken. */
    private void giveBack(LiveWallet w, Asset a, long amount, String what) {
        if (!w.give(a, amount)) {
            log.error("NX exchange: could not give back {} {} to character {} (account {}) after {}: staff must add it",
                    amount, a, w.character(), w.account(), what);
            halt("could not give back " + amount + " " + a + " to character " + w.character());
        }
    }

    // ---------------------------------------------------------------- confirm step (owner 2026-10-28)

    /**
     * Step 1 of a place: checks everything a place checks, simulates the match against the book as it is now, and
     * returns a token the player confirms within {@link ExchangeConfig#confirmMs()}. Nothing moves.
     */
    public synchronized Response preview(Place p) {
        String rid = p.requestId();
        if (!recovered) {
            return Response.error(rid, "UNAVAILABLE", "exchange not started");
        }
        if (botAccount(p.account())) {
            return Response.error(rid, "BAD_REQUEST", "account/character");
        }
        if (halted) {
            return Response.error(rid, "HALTED", "the exchange is stopped: " + haltReason);
        }
        String bad = validate(p);
        if (bad != null) {
            return Response.error(rid, "BAD_REQUEST", bad);
        }
        long now = clock.getAsLong();
        Ticker tk = tickers.get(p.ticker());
        String limit = limits(p.account(), tk, now);
        if (limit != null) {
            return Response.error(rid, limit, limitMessage(limit, tk));
        }
        LiveWallet w = live.find(p.account());
        if (w != null && w.character() != p.character()) {
            return Response.error(rid, "LOG_OUT_FIRST", "you are logged in as another character: use that one, or log out");
        }
        if (w == null && tk.item() && p.side() == Side.SELL) {
            return Response.error(rid, "LOG_IN_FIRST", itemsInGame(tk));
        }
        if (w == null && presence.test(p.account())) {
            return Response.error(rid, "LOG_OUT_FIRST", "log out of the game to place an order");
        }
        Asset a = p.side() == Side.SELL ? tk.asset() : Asset.MESO;
        long takes = p.side() == Side.SELL ? p.nx() : tk.buyTotal(config, p.nx(), p.price());
        if (w != null && w.have(a) < takes) {
            return Response.error(rid, "INSUFFICIENT_FUNDS", "you need " + takes + " " + unitName(a));
        }
        long hold = p.side() == Side.BUY ? tk.cost(p.nx(), p.price()) : 0;
        OrderBook sim = books.get(tk.id()).copy();
        OrderBook.Result r = sim.place(new OrderBook.Order(nextOrder, p.account(), false, p.side(), p.price(), p.nx(),
                p.nx(), hold, now, 0, nextSeq), now);
        long nx = 0, mesos = 0, fees = 0;
        for (OrderBook.Fill f : r.fills()) {
            nx += f.nx();
            mesos += f.mesos();
            fees += config.fee(f.mesos());
        }
        for (Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().expiresAt() < now) {
                it.remove();
            }
        }
        byte[] b = new byte[12];
        random.nextBytes(b);
        StringBuilder token = new StringBuilder();
        for (byte x : b) {
            token.append(String.format("%02x", x));
        }
        String t = token.toString();
        long expires = now + config.confirmMs();
        pending.put(t, new Pending(new Place("t-" + t, p.account(), p.character(), p.side(), p.price(), p.nx(),
                tk.id()), expires));
        latestToken.put(p.account(), t);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", t);
        data.put("expiresAt", iso(expires));
        data.put("ticker", tk.id());
        data.put("unit", tk.unit());
        data.put("side", p.side().name());
        data.put("price", p.price());
        data.put("nx", p.nx());
        data.put("qty", p.nx());
        data.put("takes", Map.of("asset", a.name(), "amount", takes));
        data.put("feeBpsEachSide", config.feeBps());
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("nx", nx);
        match.put("qty", nx);
        match.put("mesos", mesos);
        match.put("fee", fees);
        match.put("youGet", p.side() == Side.SELL ? Map.of("asset", "MESO", "amount", mesos - fees)
                : Map.of("asset", tk.asset().name(), "amount", nx));
        data.put("matchNow", match);
        data.put("restsNx", p.nx() - nx);
        data.put("cancelledBySelfTrade", r.closed().stream().anyMatch(c -> c.orderId() == nextOrder
                && c.reason() == OrderBook.Reason.SELF_TRADE));
        return Response.ok(rid, data);
    }

    /**
     * Step 2: places what the token previewed (the token is the order's request id, so a repeated confirm returns the
     * same order). TOKEN_EXPIRED once the token is older than {@link ExchangeConfig#confirmMs()} or unknown (also after
     * a restart: tokens live in memory only).
     */
    public synchronized Response confirm(String rid, long account, String token) {
        long now = clock.getAsLong();
        Pending x = token == null ? null : pending.get(token);
        if (x == null || x.expiresAt() < now) {
            if (x != null) {
                pending.remove(token);
            }
            return Response.error(rid, "TOKEN_EXPIRED", "that preview expired; preview the order again");
        }
        if (x.place().account() != account) {
            return Response.error(rid, "TOKEN_EXPIRED", "that preview expired; preview the order again");
        }
        Response r = place(x.place());
        return new Response(rid, r.ok(), r.code(), r.message(), r.data());
    }

    /** The in-game {@code @nx confirm}: the account's latest preview. */
    public synchronized Response confirmLatest(String rid, long account) {
        String t = latestToken.get(account);
        if (t == null) {
            return Response.error(rid, "TOKEN_EXPIRED", "nothing to confirm; preview an order first");
        }
        Response r = confirm(rid, account, t);
        if (r.ok()) {
            latestToken.remove(account);
        }
        return r;
    }

    private String validate(Place p) {
        if (p.requestId() == null || p.requestId().isBlank() || p.requestId().length() > 64) {
            return "requestId";
        }
        if (botAccount(p.account()) != botAccount(p.character()) || (botAccount(p.account())
                && p.account() != p.character())) {
            return "account/character";
        }
        if (p.account() <= 0 || p.character() <= 0) {
            return "account/character";
        }
        if (p.side() == null) {
            return "side";
        }
        Ticker t = tickers.get(p.ticker());
        if (t == null || !openTickers.contains(t.id())) {
            return "ticker must be one of " + String.join(", ", openTickers);
        }
        if (p.nx() < t.minQty() || p.nx() > t.maxQty() || p.nx() % t.lot() != 0) {
            return t.item() ? "quantity must be " + t.minQty() + ".." + t.maxQty()
                    : "nx must be " + t.minQty() + ".." + t.maxQty() + " in steps of " + t.lot();
        }
        if (p.price() < t.minPrice() || p.price() > t.maxPrice()) {
            return "price must be " + t.minPrice() + ".." + t.maxPrice() + " " + t.priceUnit();
        }
        if (p.side() == Side.BUY && t.buyTotal(config, p.nx(), p.price()) > Integer.MAX_VALUE) {
            return "costs more mesos than a character can hold";
        }
        return null;
    }

    /**
     * Open orders and the day's count are per ticker (owner 2026-10-28: 20 a day PER TICKER, each ticker its own open
     * limit, {@link Ticker#maxOpen}/{@link Ticker#perDay}); the cooldown is the account's across every ticker.
     */
    private String limits(long account, Ticker t, long now) {
        if (books.get(t.id()).ordersOf(account).size() >= t.maxOpen()) {
            return "LIMIT_OPEN";
        }
        try {
            int today = 0;
            long last = 0;
            for (OrderRow r : store.ordersOf(account, dayWindow())) {
                if (r.placedAt() > now - 86_400_000L && t.id().equals(r.ticker())) {
                    today++;
                }
                last = Math.max(last, r.placedAt());
            }
            if (today >= t.perDay()) {
                return "LIMIT_DAY";
            }
            if (last > 0 && now - last < config.cooldownMs()) {
                return "COOLDOWN";
            }
        } catch (StoreException e) {
            return "ERROR";
        }
        return null;
    }

    /** How many of an account's newest orders cover every ticker's day (the sum of their day limits, capped). */
    private int dayWindow() {
        long n = 1;
        for (Ticker t : tickers.values()) {
            n += t.perDay();
        }
        return (int) Math.min(5_000, n);
    }

    private String limitMessage(String code, Ticker t) {
        return switch (code) {
            case "LIMIT_OPEN" -> "at most " + t.maxOpen() + " open " + t.id() + " orders";
            case "LIMIT_DAY" -> "at most " + t.perDay() + " " + t.id() + " orders a day";
            case "COOLDOWN" -> "wait " + config.cooldownMs() / 1000 + " s between orders";
            default -> "try again later";
        };
    }

    /** Places an order (player or regular) and settles what it matches, all in one batch. */
    private Response placeOrder(String rid, long account, long character, int regular, Ticker t, Side side, long price,
                                long nx, long now, LiveWallet w) {
        long id = nextOrder;
        long seq = nextSeq;
        long owner = regular > 0 ? Regulars.owner(regular) : account;
        long hold = side == Side.BUY ? t.cost(nx, price) : 0;
        long expires = t.expiryMs() > 0 ? now + t.expiryMs() : 0;
        OrderBook.Order o = new OrderBook.Order(id, owner, regular > 0, side, price, nx, nx, hold, now, expires, seq);
        OrderRow row = new OrderRow(id, rid, account, character, regular, side, price, nx, nx, hold, now, expires, seq,
                "OPEN", 0, t.id());
        OrderBook book = books.get(t.id());
        OrderBook before = book.copy();
        Map<Long, OrderRow> rowsBefore = new HashMap<>(rows);
        rows.put(id, row);
        OrderBook.Result r = book.place(o, now);
        List<Posting> postings = new ArrayList<>();
        Asset sold = t.asset();
        boolean bot = regular == 0 && botAccount(account);
        Bucket source = regular > 0 ? Bucket.house(side == Side.SELL ? sold : Asset.MESO, regular)
                : bot ? Bucket.bot(side == Side.SELL ? sold : Asset.MESO, character)
                : side == Side.BUY ? Bucket.wallet(Asset.MESO, character)
                : sold.perCharacter() ? Bucket.wallet(sold, character) : Bucket.wallet(Asset.NX, account);
        long escrowIn = side == Side.SELL ? nx : hold;
        long legAccount = regular > 0 || bot ? 0 : account;
        postings.add(new Posting(Why.PLACE, source, escrowOf(t.id(), side, id), escrowIn, legAccount, id, 0));
        Map<Long, Long> holds = new HashMap<>(feeHold);
        long reserve = side == Side.BUY ? config.fee(hold) : 0;
        if (reserve > 0) {
            postings.add(new Posting(Why.PLACE, source, Bucket.feeHold(id), reserve, legAccount, id, 0));
            holds.put(id, reserve);
        }
        List<FillRow> fills = new ArrayList<>();
        java.util.Set<Long> qa = new java.util.HashSet<>();
        long fillId = nextFill;
        for (OrderBook.Fill f : r.fills()) {
            if (qaOrder(rows.get(f.buyOrder())) || qaOrder(rows.get(f.sellOrder()))) {
                qa.add(fillId);
            }
            fills.add(settle(f, fillId++, postings, holds, t));
        }
        for (OrderBook.Closed c : r.closed()) {
            release(c, postings, holds);
        }
        List<OrderRow> touched = touchedRows(r, now, book);
        Batch batch = w == null ? new Batch(nextBatch, now, postings, touched, fills)
                : new Batch(nextBatch, now, postings, touched, fills, w.account(), w.character(),
                w.inventoryWriter());
        try {
            store.apply(batch);
        } catch (StoreException e) {
            books.put(t.id(), before);
            rows.clear();
            rows.putAll(rowsBefore);
            return storeError(rid, e);
        }
        committed = true;
        feeHold.clear();
        feeHold.putAll(holds);
        nextOrder++;
        nextSeq++;
        nextFill = fillId;
        nextBatch++;
        for (OrderRow o2 : touched) {
            if (o2.open()) {
                rows.put(o2.id(), o2);
            } else {
                rows.remove(o2.id());
            }
        }
        qaFills.addAll(qa); // only once committed: a failed batch's fill ids are used again
        remember(fills);
        noteBotPayouts(postings);
        if (t.item()) {
            for (FillRow f : fills) { // 1.6.1 fair value: a taker buy is demand, a taker sell supply (QA checks: not)
                if (!qaFills.contains(f.id())) {
                    soloMapling.Economy.exchange.volatility.MarketFlows.exchangeFill(t.id(), side == Side.BUY, f.nx(),
                            now);
                }
            }
        }
        Map<String, Object> data = orderJson(touched.get(0));
        data.put("fills", fills.size());
        data.put("filledNx", r.fills().stream().mapToLong(OrderBook.Fill::nx).sum());
        return Response.ok(rid, data);
    }

    /** Postings for one fill (both sides' fees: the seller's from the mesos, the buyer's from its FEEHOLD); its row. */
    private FillRow settle(OrderBook.Fill f, long fillId, List<Posting> postings, Map<Long, Long> holds, Ticker t) {
        OrderRow buy = rows.get(f.buyOrder());
        OrderRow sell = rows.get(f.sellOrder());
        // what was sold goes to the buyer: NX to its account's payout, items to the buying character's payout
        Bucket qtyTo = ownerBucket(buy, t.asset());
        postings.add(new Posting(Why.FILL, escrowOf(t.id(), Side.SELL, sell.id()), qtyTo, f.nx(), 0, sell.id(),
                fillId));
        long fee = config.fee(f.mesos());
        long toSeller = f.mesos() - fee;
        Bucket mesoTo = sell.regular() > 0 ? Bucket.house(Asset.MESO, sell.regular())
                : Bucket.payout(Asset.MESO, sell.character());
        if (toSeller > 0) {
            postings.add(new Posting(Why.FILL, escrowOf(Side.BUY, buy.id()), mesoTo, toSeller, 0, buy.id(), fillId));
        }
        if (fee > 0) {
            postings.add(new Posting(Why.FILL, escrowOf(Side.BUY, buy.id()), Bucket.fees(), fee, 0, buy.id(), fillId));
        }
        // the buyer's fee, from its reserve; never more than is left (a fee raised after the order was placed)
        long buyerFee = Math.min(config.fee(f.mesos()), holds.getOrDefault(buy.id(), 0L));
        if (buyerFee > 0) {
            postings.add(new Posting(Why.FILL, Bucket.feeHold(buy.id()), Bucket.fees(), buyerFee, 0, buy.id(), fillId));
            holds.merge(buy.id(), -buyerFee, Long::sum);
        }
        return new FillRow(fillId, f.at(), f.buyOrder(), f.sellOrder(), f.nx(), f.price(), f.mesos(), f.buyerRegular(),
                f.sellerRegular(), t.id());
    }

    /** The postings returning a closing order's escrow (and a buy's fee reserve) to its owner's payout (or house). */
    private void release(OrderBook.Closed c, List<Posting> postings, Map<Long, Long> holds) {
        OrderRow r = rows.get(c.orderId());
        long amount = c.side() == Side.SELL ? c.nxReleased() : c.mesosReleased();
        Asset a = c.side() == Side.SELL ? assetOf(r.ticker()) : Asset.MESO;
        Bucket to = ownerBucket(r, a);
        if (amount > 0) {
            postings.add(new Posting(Why.RELEASE, escrowOf(r.ticker(), c.side(), c.orderId()), to, amount, 0,
                    c.orderId(), 0));
        }
        Long rest = holds.remove(c.orderId());
        if (rest != null && rest > 0) {
            postings.add(new Posting(Why.RELEASE, Bucket.feeHold(c.orderId()), to, rest, 0, c.orderId(), 0));
        }
    }

    /**
     * Where an order's owner receives {@code a}: its PAYOUT (NX by account; mesos and items by the order's character)
     * or a regular's HOUSE.
     */
    private static Bucket ownerBucket(OrderRow r, Asset a) {
        return r.regular() > 0 ? Bucket.house(a, r.regular())
                : a.perCharacter() ? Bucket.payout(a, r.character()) : Bucket.payout(a, r.account());
    }

    /** New rows for the placed order (first) and every order the match changed. */
    private List<OrderRow> touchedRows(OrderBook.Result r, long now, OrderBook book) {
        Map<Long, OrderRow> out = new LinkedHashMap<>();
        out.put(r.order().id, rowAfter(rows.get(r.order().id), r.order(), null, now));
        for (OrderBook.Fill f : r.fills()) {
            for (long id : new long[]{f.buyOrder(), f.sellOrder()}) {
                if (!out.containsKey(id)) {
                    OrderBook.Order live = book.get(id);
                    out.put(id, live != null ? rowAfter(rows.get(id), live, null, now) : null);
                }
            }
        }
        for (OrderBook.Closed c : r.closed()) {
            OrderRow base = rows.get(c.orderId());
            long filled = 0;
            for (OrderBook.Fill f : r.fills()) {
                if (f.buyOrder() == c.orderId() || f.sellOrder() == c.orderId()) {
                    filled += f.nx();
                }
            }
            // nxLeft on a closed row: the NX it never traded
            out.put(c.orderId(), new OrderRow(base.id(), base.requestId(), base.account(), base.character(),
                    base.regular(), base.side(), base.price(), base.nxTotal(), base.nxLeft() - filled, 0,
                    base.placedAt(), base.expiresAt(), base.seq(), c.reason().name(), now, base.ticker()));
        }
        out.values().removeIf(java.util.Objects::isNull);
        return new ArrayList<>(out.values());
    }

    private static OrderRow rowAfter(OrderRow base, OrderBook.Order live, String status, long now) {
        return new OrderRow(base.id(), base.requestId(), base.account(), base.character(), base.regular(), base.side(),
                base.price(), base.nxTotal(), live.nxLeft(), live.mesosHeld(), base.placedAt(), base.expiresAt(),
                base.seq(), status == null ? "OPEN" : status, status == null ? 0 : now, base.ticker());
    }

    public synchronized Response cancel(String rid, long account, long orderId) {
        if (!recovered) {
            return Response.error(rid, "UNAVAILABLE", "exchange not started");
        }
        OrderRow base = rows.get(orderId);
        if (base == null || base.account() != account || base.regular() != 0) {
            return Response.error(rid, "NOT_FOUND", "no open order " + orderId);
        }
        Response r = closeOrders(rid, List.of(orderId), account, OrderBook.Reason.CANCELLED);
        if (r.ok() && !halted) {
            LiveWallet w = live.find(account);
            if (w != null) {
                collectLive(w);
            }
        }
        return r;
    }

    /** Cancels a regular's order (the regulars' tick). */
    synchronized Response cancelRegular(int regular, long orderId) {
        return closeOrders("regular-" + regular, List.of(orderId), Regulars.owner(regular), OrderBook.Reason.CANCELLED);
    }

    private Response closeOrders(String rid, List<Long> ids, long owner, OrderBook.Reason why) {
        long now = clock.getAsLong();
        Map<String, OrderBook> before = copyBooks();
        List<Posting> postings = new ArrayList<>();
        List<OrderRow> touched = new ArrayList<>();
        Map<Long, Long> holds = new HashMap<>(feeHold);
        for (long id : ids) {
            OrderRow b = rows.get(id);
            OrderBook.Closed c = b == null ? null : books.get(b.ticker()).cancel(id, owner, why);
            if (c == null) {
                continue;
            }
            release(c, postings, holds);
            touched.add(new OrderRow(b.id(), b.requestId(), b.account(), b.character(), b.regular(), b.side(),
                    b.price(), b.nxTotal(), b.nxLeft(), 0, b.placedAt(), b.expiresAt(),
                    b.seq(), why.name(), now, b.ticker()));
        }
        if (touched.isEmpty()) {
            return Response.error(rid, "NOT_FOUND", "no open order");
        }
        try {
            store.apply(new Batch(nextBatch, now, postings, touched, List.of()));
        } catch (StoreException e) {
            books = before;
            return storeError(rid, e);
        }
        nextBatch++;
        feeHold.clear();
        feeHold.putAll(holds);
        touched.forEach(t -> rows.remove(t.id()));
        noteBotPayouts(postings);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("closed", touched.stream().map(OrderRow::id).toList());
        return Response.ok(rid, data);
    }

    /** Closes expired orders (not while halted). Returns how many. */
    public synchronized int expire() {
        if (!recovered || halted) {
            return 0;
        }
        long now = clock.getAsLong();
        Map<String, OrderBook> before = copyBooks();
        List<OrderBook.Closed> closed = new ArrayList<>();
        for (OrderBook b : books.values()) {
            closed.addAll(b.expire(now));
        }
        if (closed.isEmpty()) {
            return 0;
        }
        List<Posting> postings = new ArrayList<>();
        List<OrderRow> touched = new ArrayList<>();
        Map<Long, Long> holds = new HashMap<>(feeHold);
        for (OrderBook.Closed c : closed) {
            release(c, postings, holds);
            OrderRow b = rows.get(c.orderId());
            touched.add(new OrderRow(b.id(), b.requestId(), b.account(), b.character(), b.regular(), b.side(),
                    b.price(), b.nxTotal(), b.nxLeft(), 0, b.placedAt(), b.expiresAt(), b.seq(), "EXPIRED", now,
                    b.ticker()));
        }
        try {
            store.apply(new Batch(nextBatch, now, postings, touched, List.of()));
        } catch (StoreException e) {
            books = before;
            return 0;
        }
        nextBatch++;
        feeHold.clear();
        feeHold.putAll(holds);
        touched.forEach(t -> rows.remove(t.id()));
        noteBotPayouts(postings);
        return touched.size();
    }

    // ---------------------------------------------------------------- payouts

    /** Moves waiting payouts into wallets for every offline account (not while halted). Returns accounts paid. */
    public synchronized int collectAll() {
        if (!recovered || halted) {
            return 0;
        }
        int paid = 0;
        try {
            for (long account : store.accountsWithPayout()) {
                LiveWallet w = live.find(account);
                if (w != null ? collectLive(w) : collectQuietly(account)) {
                    paid++;
                }
            }
        } catch (StoreException e) {
            return paid;
        }
        return paid;
    }

    private boolean collectQuietly(long account) {
        if (halted || presence.test(account)) {
            return false;
        }
        try {
            List<Posting> done = store.collect(nextBatch, clock.getAsLong(), account);
            nextBatch++;
            return !done.isEmpty();
        } catch (StoreException e) {
            return false;
        }
    }

    /** Pays a logged-in player's waiting NX and this character's waiting mesos straight into memory. */
    public synchronized boolean collectLive(LiveWallet w) {
        return w.locked(() -> collectLiveLocked(w));
    }

    /**
     * Under the character's lock: PAYOUT -> wallet as one transaction (deltas on accounts.nxCredit and the live
     * character's meso), then the same amounts into memory. Whatever memory cannot hold (the 2,147,483,647 cap) stays
     * in PAYOUT. If memory refuses after the commit (a gain raced the cap), a second transaction puts it back.
     */
    private boolean collectLiveLocked(LiveWallet w) {
        if (halted || !recovered || !w.usable()) {
            return false;
        }
        long now = clock.getAsLong();
        List<Posting> postings = new ArrayList<>();
        try {
            Bucket nxPay = Bucket.payout(Asset.NX, w.account());
            long nx = Math.min(store.balance(nxPay), Integer.MAX_VALUE - w.have(Asset.NX));
            if (nx > 0) {
                postings.add(new Posting(Why.COLLECT, nxPay, Bucket.wallet(Asset.NX, w.account()), nx, w.account(), 0,
                        0));
            }
            Bucket mesoPay = Bucket.payout(Asset.MESO, w.character());
            long meso = Math.min(store.balance(mesoPay), Integer.MAX_VALUE - w.have(Asset.MESO));
            if (meso > 0) {
                postings.add(new Posting(Why.COLLECT, mesoPay, Bucket.wallet(Asset.MESO, w.character()), meso,
                        w.account(), 0, 0));
            }
        } catch (StoreException e) {
            return false;
        }
        boolean items = collectItemsLocked(w);
        if (postings.isEmpty()) {
            return items;
        }
        try {
            store.apply(new Batch(nextBatch, now, postings, List.of(), List.of(), w.account(), w.character()));
        } catch (StoreException e) {
            return items;
        }
        nextBatch++;
        List<String> got = new ArrayList<>();
        for (Posting p : postings) {
            if (w.give(p.asset(), p.amount())) {
                got.add(String.format("%,d %s", p.amount(), p.asset() == Asset.NX ? "NX" : "mesos"));
                continue;
            }
            // memory could not take it after all: undo this leg in the database (wallet back to payout)
            try {
                store.apply(new Batch(nextBatch, clock.getAsLong(), List.of(new Posting(Why.COLLECT, p.to(), p.from(),
                        p.amount(), w.account(), 0, 0)), List.of(), List.of(), w.account(), w.character()));
                nextBatch++;
            } catch (StoreException e) {
                log.error("NX exchange: {} {} credited to account {} in the database but not in memory; halting",
                        p.amount(), p.asset(), w.account(), e);
                halt("payout to character " + w.character() + " half done: " + p.amount() + " " + p.asset());
            }
        }
        if (!got.isEmpty()) {
            w.tell("NX exchange: " + String.join(" and ", got) + " arrived.");
        }
        return items || !got.isEmpty();
    }

    /**
     * Under the character's lock: each item payout of this character into its bags, as much as fits. The bag changes
     * in memory first, then one transaction moves PAYOUT to the wallet and writes the inventory as memory now holds it
     * ({@link LiveWallet#inventoryWriter}); if that fails the items are taken back out of memory. What does not fit
     * waits (the player is told, at most every 10 minutes).
     */
    private boolean collectItemsLocked(LiveWallet w) {
        boolean any = false;
        for (Ticker t : tickers.values()) {
            if (!t.item()) {
                continue;
            }
            Asset a = t.asset();
            Bucket pay = Bucket.payout(a, w.character());
            long waiting;
            try {
                waiting = store.balance(pay);
            } catch (StoreException e) {
                return any;
            }
            if (waiting <= 0) {
                continue;
            }
            long n = Math.min(waiting, w.room(a, waiting));
            if (n <= 0) {
                long now = clock.getAsLong();
                Long last = roomNagged.get(w.character());
                if (last == null || now - last > 600_000L) {
                    roomNagged.put(w.character(), now);
                    w.tell("NX exchange: " + t.amount(waiting) + " wait for you; make room in your bags.");
                }
                continue;
            }
            if (!w.give(a, n)) {
                continue;
            }
            try {
                store.apply(new Batch(nextBatch, clock.getAsLong(), List.of(new Posting(Why.COLLECT, pay,
                        Bucket.wallet(a, w.character()), n, w.account(), 0, 0)), List.of(), List.of(), w.account(),
                        w.character(), w.inventoryWriter()));
                nextBatch++;
            } catch (StoreException e) {
                if (!w.take(a, n)) {
                    log.error("NX exchange: {} {} given to character {} in memory but not in the database; halting",
                            n, a, w.character(), e);
                    halt("item payout to character " + w.character() + " half done: " + n + " " + a);
                }
                continue;
            }
            any = true;
            w.tell("NX exchange: " + t.amount(n) + " arrived" + (n < waiting ? " (" + (waiting - n)
                    + " more wait: make room in your bags)" : "") + ".");
        }
        return any;
    }

    /**
     * Funds a regular's HOUSE from a real, offline staff wallet (NX from the account, mesos from the character). The
     * only way money reaches a regular: nothing is minted.
     */
    public synchronized Response fundHouse(int regular, long account, long character, Asset asset, long amount) {
        if (regular <= 0 || amount <= 0 || asset.item()) {
            return Response.error("fund", "BAD_REQUEST", "regular and amount");
        }
        if (presence.test(account)) {
            return Response.error("fund", "LOG_OUT_FIRST", "the funding account must be offline");
        }
        Bucket from = asset == Asset.NX ? Bucket.wallet(Asset.NX, account) : Bucket.wallet(Asset.MESO, character);
        try {
            store.apply(new Batch(nextBatch, clock.getAsLong(),
                    List.of(new Posting(Why.FUND, from, Bucket.house(asset, regular), amount, account, 0, 0)),
                    List.of(), List.of()));
        } catch (StoreException e) {
            return storeError("fund", e);
        }
        nextBatch++;
        return Response.ok("fund", new LinkedHashMap<>());
    }

    /**
     * Funds a regular's HOUSE from the house account's live wallet ({@code !exchange fund}): only a character logged in
     * on {@link ExchangeConfig#houseAccount()} can, unless {@code qa} (the auto-QA check, which funds its own test
     * regular from the GM's own staff account and takes it all back). Nothing is minted.
     */
    public synchronized Response fundHouseLive(int regular, LiveWallet w, Asset asset, long amount, boolean qa) {
        if (regular <= 0 || amount <= 0 || amount > Integer.MAX_VALUE || asset.item()) {
            return Response.error("fund", "BAD_REQUEST", "regular and amount");
        }
        if (!qa && (config.houseAccount() <= 0 || w.account() != config.houseAccount())) {
            return Response.error("fund", "NOT_HOUSE", "only the house account ("
                    + (config.houseAccount() <= 0 ? "none set: -Dslums.exchange.houseAccount" : config.houseAccount())
                    + ") funds the regulars");
        }
        return w.locked(() -> {
            if (!w.usable()) {
                return Response.error("fund", "TRY_AGAIN", "changing channel or in the Cash Shop");
            }
            if (!w.saveNow()) {
                return Response.error("fund", "ERROR", "could not save first");
            }
            if (!w.take(asset, amount)) {
                return Response.error("fund", "INSUFFICIENT_FUNDS", "not enough " + asset);
            }
            Bucket from = asset == Asset.NX ? Bucket.wallet(Asset.NX, w.account())
                    : Bucket.wallet(Asset.MESO, w.character());
            try {
                store.apply(new Batch(nextBatch, clock.getAsLong(), List.of(new Posting(Why.FUND, from,
                        Bucket.house(asset, regular), amount, w.account(), 0, 0)), List.of(), List.of(), w.account(),
                        w.character()));
            } catch (StoreException e) {
                giveBack(w, asset, amount, "a refused house funding");
                return storeError("fund", e);
            }
            nextBatch++;
            return Response.ok("fund", new LinkedHashMap<>());
        });
    }

    /**
     * Takes a regular out ({@code !exchange unfund}): cancels its orders (escrow back to its HOUSE), then moves its
     * whole HOUSE (NX and mesos) to the live character's payout, which lands at once. Same house-account rule.
     */
    public synchronized Response unfundHouseLive(int regular, LiveWallet w, boolean qa) {
        if (regular <= 0) {
            return Response.error("unfund", "BAD_REQUEST", "regular");
        }
        if (!qa && (config.houseAccount() <= 0 || w.account() != config.houseAccount())) {
            return Response.error("unfund", "NOT_HOUSE", "only the house account takes a regular's money back");
        }
        List<Long> open = openIdsOf(Regulars.owner(regular));
        if (!open.isEmpty()) {
            Response c = closeOrders("unfund-" + regular, open, Regulars.owner(regular), OrderBook.Reason.CANCELLED);
            if (!c.ok()) {
                return c;
            }
        }
        List<Posting> postings = new ArrayList<>();
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            for (Asset a : Asset.values()) {
                long have = store.balance(Bucket.house(a, regular));
                data.put(a.name().toLowerCase(java.util.Locale.ROOT), have);
                if (have > 0) {
                    postings.add(new Posting(Why.FUND, Bucket.house(a, regular), a.perCharacter()
                            ? Bucket.payout(a, w.character()) : Bucket.payout(a, w.account()), have, 0, 0, 0));
                }
            }
            if (!postings.isEmpty()) {
                store.apply(new Batch(nextBatch, clock.getAsLong(), postings, List.of(), List.of()));
                nextBatch++;
            }
        } catch (StoreException e) {
            return storeError("unfund", e);
        }
        collectLive(w);
        return Response.ok("unfund", data);
    }

    /** The auto-QA check's own regular quote (placed from its HOUSE, which the check funded). */
    public synchronized Response placeRegularForQa(int regular, Side side, long price, long nx) {
        return placeRegularForQa(regular, Ticker.NX, side, price, nx);
    }

    /**
     * The same on any ticker. On an item ticker the test regular only ever bids (mesos from its HOUSE); it never
     * sells items, so nothing is minted: what it buys comes from the tester and goes back at teardown.
     */
    public synchronized Response placeRegularForQa(int regular, String ticker, Side side, long price, long nx) {
        if (!recovered || halted) {
            return Response.error("qa", "HALTED", "not running");
        }
        Ticker t = tickers.get(Ticker.norm(ticker));
        if (t == null || (t.item() && side == Side.SELL)) {
            return Response.error("qa", "BAD_REQUEST", "ticker/side");
        }
        return placeOrder("qa-regular-" + regular + "-" + nextOrder, 0, 0, regular, t, side, price, nx,
                clock.getAsLong(), null);
    }

    // ---------------------------------------------------------------- staff reversal (owner 2026-10-28)

    /**
     * Reverses one fill with compensating postings (nothing is edited; the ledger only grows): the NX goes back to the
     * seller and the mesos and both fees back to the buyer, into their PAYOUT (or HOUSE). Each amount comes from where
     * the fill put it: the payout if it is still there, otherwise the wallet it was collected into (that player must
     * be logged out: PLAYER_ONLINE). Only while halted, once per fill. Logged.
     */
    public synchronized Response reverse(long fillId, String by) {
        String rid = "reverse-" + fillId;
        if (!halted) {
            return Response.error(rid, "NOT_HALTED", "halt the exchange first (!exchange halt)");
        }
        try {
            FillRow f = store.fill(fillId);
            if (f == null) {
                return Response.error(rid, "NOT_FOUND", "no fill " + fillId);
            }
            List<Posting> made = store.postingsOfFill(fillId);
            if (made.stream().anyMatch(p -> p.why() == Why.REVERSE)) {
                return Response.error(rid, "ALREADY_REVERSED", "fill " + fillId + " was already reversed");
            }
            OrderRow buy = store.order(f.buyOrder());
            OrderRow sell = store.order(f.sellOrder());
            if (buy == null || sell == null) {
                return Response.error(rid, "NOT_FOUND", "the fill's orders are missing");
            }
            Map<Bucket, Long> left = new HashMap<>();
            List<Posting> out = new ArrayList<>();
            for (Posting p : made) {
                if (p.why() != Why.FILL) {
                    continue;
                }
                Bucket back = p.from().kind() == Kind.ESCROW && p.asset() != Asset.MESO
                        ? ownerBucket(sell, p.asset()) : ownerBucket(buy, Asset.MESO);
                Bucket src = p.to();
                long need = p.amount();
                if (src.kind() == Kind.PAYOUT) {
                    long have = left.computeIfAbsent(src, b -> {
                        try {
                            return store.balance(b);
                        } catch (StoreException e) {
                            throw new IllegalStateException(e);
                        }
                    });
                    long fromPayout = Math.min(have, need);
                    if (fromPayout > 0) {
                        out.add(new Posting(Why.REVERSE, src, back, fromPayout, 0, p.orderId(), fillId));
                        left.put(src, have - fromPayout);
                    }
                    long rest = need - fromPayout;
                    if (rest > 0) {
                        long account = src.asset() == Asset.NX ? src.id()
                                : src.asset() == Asset.MESO ? sell.account() : buy.account();
                        out.add(new Posting(Why.REVERSE, Bucket.wallet(src.asset(), src.id()), back, rest, account,
                                p.orderId(), fillId));
                    }
                } else {
                    out.add(new Posting(Why.REVERSE, src, back, need, 0, p.orderId(), fillId));
                }
            }
            if (out.isEmpty()) {
                return Response.error(rid, "NOT_FOUND", "fill " + fillId + " moved nothing");
            }
            store.apply(new Batch(nextBatch, clock.getAsLong(), out, List.of(), List.of()));
            nextBatch++;
            Deque<FillRow> rec = recent.get(f.ticker());
            if (rec != null) {
                rec.removeIf(r -> r.id() == fillId);
            }
            log.warn("NX exchange: fill {} REVERSED by {} ({} NX at {}; {} postings)", fillId, by, f.nx(), f.price(),
                    out.size());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("fillId", fillId);
            data.put("postings", out.size());
            return Response.ok(rid, data);
        } catch (StoreException e) {
            if (e.failure == Failure.ONLINE) {
                return Response.error(rid, "PLAYER_ONLINE", "a player of this fill already collected it and is "
                        + "logged in; ask them to log out, then reverse again");
            }
            return storeError(rid, e);
        } catch (IllegalStateException e) {
            return Response.error(rid, "ERROR", "try again later");
        }
    }

    // ---------------------------------------------------------------- trader bots (1.6.1 volatility)

    private void noteBotPayouts(List<Posting> postings) {
        for (Posting p : postings) {
            if (p.to().kind() == Kind.PAYOUT && botAccount(p.to().id())) {
                botPayouts.add(p.to().id());
            }
        }
    }

    /** Trader bots (character ids) with mesos or items waiting in their PAYOUT. */
    public synchronized List<Long> botsWithPayout() {
        return new ArrayList<>(botPayouts);
    }

    /**
     * A trader bot's order, from its own bags and mesos ({@code w} is the bot's {@code BotTraderWallet}; the order's
     * account and character are both the bot's character id). Item tickers only, every player limit applies (5 open
     * per ticker, the day's count, the cooldown, the fee). Under the bot's lock: take the escrow out of the bot, save
     * the bot (so a crash from here on loses the escrow rather than duplicating it), then write the order; a refused
     * order gives it back. Nothing is minted: what a bot cannot pay it does not post.
     */
    public synchronized Response placeBot(Place p, LiveWallet w) {
        String rid = p.requestId();
        if (!recovered) {
            return Response.error(rid, "UNAVAILABLE", "exchange not started");
        }
        if (halted) {
            return Response.error(rid, "HALTED", "the exchange is stopped: " + haltReason);
        }
        if (w == null || !botAccount(p.account()) || w.account() != p.account() || w.character() != p.character()) {
            return Response.error(rid, "BAD_REQUEST", "not a trader bot's own wallet");
        }
        String bad = validate(p);
        if (bad != null) {
            return Response.error(rid, "BAD_REQUEST", bad);
        }
        Ticker t = tickers.get(p.ticker());
        if (!t.item()) {
            return Response.error(rid, "BAD_REQUEST", "trader bots trade item tickers only");
        }
        long now = clock.getAsLong();
        String limit = limits(p.account(), t, now);
        if (limit != null) {
            return Response.error(rid, limit, limitMessage(limit, t));
        }
        Asset a = p.side() == Side.SELL ? t.asset() : Asset.MESO;
        long amount = p.side() == Side.SELL ? p.nx() : t.buyTotal(config, p.nx(), p.price());
        return w.locked(() -> {
            if (!w.usable()) {
                return Response.error(rid, "TRY_AGAIN", "the bot is not in the game");
            }
            if (w.have(a) < amount || !w.take(a, amount)) {
                return Response.error(rid, "INSUFFICIENT_FUNDS", "the bot has fewer than " + amount + " " + unitName(a));
            }
            if (!w.saveNow()) {
                giveBack(w, a, amount, "an unsaved bot place " + rid);
                return Response.error(rid, "ERROR", "could not save the bot first");
            }
            Response placed;
            committed = false;
            try {
                placed = placeOrder(rid, p.account(), p.character(), 0, t, p.side(), p.price(), p.nx(), now, null);
            } catch (RuntimeException e) {
                if (!committed) {
                    giveBack(w, a, amount, "a failed bot place " + rid);
                    w.saveNow();
                } else {
                    halt("bot place " + rid + " failed after its commit: " + e);
                }
                log.error("NX exchange: bot place {} failed", rid, e);
                return Response.error(rid, "ERROR", "try again later");
            }
            if (!placed.ok()) {
                giveBack(w, a, amount, "a refused bot place " + rid);
                w.saveNow();
            } else {
                collectBotLocked(w);
            }
            return placed;
        });
    }

    /** Cancels one of a trader bot's open orders and pays what it held back into the bot (if it is in game). */
    public synchronized Response cancelBot(String rid, LiveWallet w, long orderId) {
        if (!recovered) {
            return Response.error(rid, "UNAVAILABLE", "exchange not started");
        }
        OrderRow base = rows.get(orderId);
        if (w == null || base == null || !botAccount(w.account()) || base.account() != w.account()
                || base.regular() != 0) {
            return Response.error(rid, "NOT_FOUND", "no open order " + orderId);
        }
        Response r = closeOrders(rid, List.of(orderId), w.account(), OrderBook.Reason.CANCELLED);
        if (r.ok() && !halted) {
            collectBot(w);
        }
        return r;
    }

    /** A trader bot's open orders on a ticker (newest last). */
    public synchronized List<OrderRow> openBotOrders(long botCharacter, String ticker) {
        List<OrderRow> out = new ArrayList<>();
        OrderBook b = books.get(Ticker.norm(ticker));
        if (b == null) {
            return out;
        }
        for (OrderBook.Order o : b.ordersOf(botCharacter)) {
            OrderRow r = rows.get(o.id);
            if (r != null) {
                out.add(rowAfter(r, o, null, r.placedAt()));
            }
        }
        out.sort(java.util.Comparator.comparingLong(OrderRow::seq));
        return out;
    }

    /** How many orders this account placed on a ticker in the last 24 h (trader bots pace themselves with it). */
    public synchronized int placedToday(long account, String ticker) {
        long now = clock.getAsLong();
        Ticker t = tickers.get(Ticker.norm(ticker));
        if (t == null) {
            return Integer.MAX_VALUE;
        }
        try {
            int n = 0;
            for (OrderRow r : store.ordersOf(account, dayWindow())) {
                n += r.placedAt() > now - 86_400_000L && t.id().equals(r.ticker()) ? 1 : 0;
            }
            return n;
        } catch (StoreException e) {
            return t.perDay();
        }
    }

    /** Pays a trader bot's waiting mesos and items into the bot (as far as its bags take them). */
    public synchronized boolean collectBot(LiveWallet w) {
        return w.locked(() -> collectBotLocked(w));
    }

    /**
     * Under the bot's lock: PAYOUT -> the bot, one asset at a time: the transaction first, then memory; if memory
     * refuses (bags full, the meso cap), a second transaction puts it back into PAYOUT. The bot is saved afterwards.
     * A crash between the commit and the save loses the payout to the bot; it never duplicates it.
     */
    private boolean collectBotLocked(LiveWallet w) {
        if (halted || !recovered || !botAccount(w.account()) || !w.usable()) {
            return false;
        }
        boolean any = false, left = false;
        List<Asset> assets = new ArrayList<>();
        assets.add(Asset.MESO);
        for (Ticker t : tickers.values()) {
            if (t.item()) {
                assets.add(t.asset());
            }
        }
        for (Asset a : assets) {
            Bucket pay = Bucket.payout(a, w.character());
            long waiting;
            try {
                waiting = store.balance(pay);
            } catch (StoreException e) {
                return any;
            }
            if (waiting <= 0) {
                continue;
            }
            long n = Math.min(waiting, w.room(a, waiting));
            if (n < waiting) {
                left = true;
            }
            if (n <= 0) {
                continue;
            }
            Posting out = new Posting(Why.COLLECT, pay, Bucket.bot(a, w.character()), n, 0, 0, 0);
            try {
                store.apply(new Batch(nextBatch, clock.getAsLong(), List.of(out), List.of(), List.of()));
                nextBatch++;
            } catch (StoreException e) {
                left = true;
                continue;
            }
            if (w.give(a, n)) {
                any = true;
                continue;
            }
            left = true;
            try {
                store.apply(new Batch(nextBatch, clock.getAsLong(), List.of(new Posting(Why.COLLECT,
                        Bucket.bot(a, w.character()), pay, n, 0, 0, 0)), List.of(), List.of()));
                nextBatch++;
            } catch (StoreException e) {
                log.error("NX exchange: {} {} left PAYOUT for bot {} but did not reach it; halting", n, a,
                        w.character(), e);
                halt("bot payout to " + w.character() + " half done: " + n + " " + a);
            }
        }
        if (!left) {
            botPayouts.remove(w.character());
        }
        if (any) {
            w.saveNow();
        }
        return any;
    }

    // ---------------------------------------------------------------- regulars

    /**
     * Keeps each regular's quotes resting: a regular with no open order on a side posts the {@link Regulars} quote
     * for it (if its house can pay). Not while halted; nothing without a reference price. Returns orders placed.
     */
    public synchronized int tickRegulars() {
        if (!recovered || halted || config.regulars() <= 0 || config.houseAccount() <= 0) {
            return 0;   // owner 2026-10-28: no house account named = regulars idle
        }
        Long ref = officialRate();
        if (ref == null) {
            long bid = nxBook().best(Side.BUY), ask = nxBook().best(Side.SELL);
            ref = bid > 0 && ask > 0 ? (bid + ask) / 2 : null;
        }
        int placed = 0;
        long now = clock.getAsLong();
        for (Regulars.Quote q : Regulars.quotes(ref, config.regulars(), config.regularSpreadBps(), config.regularNx(),
                config.minPrice(), config.maxPrice())) {
            boolean has = nxBook().ordersOf(Regulars.owner(q.regular())).stream().anyMatch(o -> o.side == q.side());
            if (has) {
                continue;
            }
            Response r = placeOrder("regular-" + q.regular() + "-" + nextOrder, 0, 0, q.regular(),
                    tickers.get(Ticker.NX), q.side(), q.price(), q.nx(), now, null);
            if (r.ok()) {
                placed++;
            }
        }
        return placed;
    }

    // ---------------------------------------------------------------- reads

    /** The NX official rate (mesos per 1,000 NX). */
    public synchronized Long officialRate() {
        return officialRate(Ticker.NX);
    }

    /** A ticker's official rate: the VWAP of its player-to-player fills (see {@link OfficialRate}), or null. */
    public synchronized Long officialRate(String ticker) {
        Deque<FillRow> r = recent.get(ticker);
        return OfficialRate.vwap(r == null ? List.of() : new ArrayList<>(r), clock.getAsLong(), config.rateWindowMs(),
                config.rateMinFills());
    }

    public synchronized List<OrderBook.Level> depth(Side side, int levels) {
        return depth(Ticker.NX, side, levels);
    }

    public synchronized List<OrderBook.Level> depth(String ticker, Side side, int levels) {
        OrderBook b = books.get(ticker);
        return b == null ? List.of() : b.depth(side, levels);
    }

    /** The newest NX fills first. */
    public synchronized List<FillRow> lastTrades(int n) {
        return lastTrades(Ticker.NX, n);
    }

    /** A ticker's newest market fills first (never the QA checks': see {@link #isQaFill}). */
    public synchronized List<FillRow> lastTrades(String ticker, int n) {
        return lastTrades(ticker, n, false);
    }

    /** A ticker's newest fills first; {@code withQa}: the QA checks' too (the staff's {@code !exchange fills}). */
    public synchronized List<FillRow> lastTrades(String ticker, int n, boolean withQa) {
        List<FillRow> out = new ArrayList<>();
        Deque<FillRow> r = recent.get(ticker);
        if (r == null) {
            return out;
        }
        var it = r.descendingIterator();
        while (it.hasNext() && out.size() < n) {
            FillRow f = it.next();
            if (withQa || !qaFills.contains(f.id())) {
                out.add(f);
            }
        }
        return out;
    }

    /** The world the exchange serves (the main world; both servers run world id 1). */
    public static final int MAIN_WORLD = 1;

    /**
     * Resolves a site request's character within one account: by id, by name (case-insensitive, exact), or both
     * (which must agree). Returns the character id, 0 when neither was given, or a negative code:
     * {@link #NOT_OWNER_CHAR} (not on this account / not in the main world) or {@link #MISMATCH_CHAR}.
     */
    public static final long NOT_OWNER_CHAR = -1, MISMATCH_CHAR = -2, STORE_ERROR_CHAR = -3;

    public synchronized long resolveCharacter(long account, long id, String name) {
        boolean named = name != null && !name.isBlank();
        if (id <= 0 && !named) {
            return 0;
        }
        try {
            List<ExchangeStore.CharRef> mine = store.charactersOf(account, MAIN_WORLD);
            Long byId = null, byName = null;
            for (ExchangeStore.CharRef c : mine) {
                if (id > 0 && c.id() == id) {
                    byId = c.id();
                }
                if (named && c.name() != null && c.name().equalsIgnoreCase(name.trim())) {
                    byName = c.id();
                }
            }
            if (named && byName == null) {
                return NOT_OWNER_CHAR;
            }
            if (id > 0 && byId == null) {
                return NOT_OWNER_CHAR;
            }
            if (id > 0 && named && !byId.equals(byName)) {
                return MISMATCH_CHAR;
            }
            return id > 0 ? byId : byName;
        } catch (StoreException e) {
            return STORE_ERROR_CHAR;
        }
    }

    /** A ticker's rolling 24 h numbers (last, change, high, low, volume). */
    public synchronized TickerHistory.Stats stats(String ticker) {
        return history.computeIfAbsent(ticker, k -> new TickerHistory()).stats(clock.getAsLong(),
                config.rateMinFills());
    }

    /** A ticker's hourly candles, the last 30 days, oldest first (only hours that traded). */
    public synchronized List<TickerHistory.Candle> candles(String ticker) {
        return history.computeIfAbsent(ticker, k -> new TickerHistory()).candles(clock.getAsLong());
    }

    /** The open tickers, NX first. */
    public List<Ticker> tickers() {
        List<Ticker> out = new ArrayList<>();
        for (String id : openTickers) {
            out.add(tickers.get(id));
        }
        return out;
    }

    /** A ticker by id ("leaf" works), open or not; null when unknown. */
    public Ticker ticker(String id) {
        String n = Ticker.norm(id);
        return n == null ? null : tickers.get(n);
    }

    public boolean open(String ticker) {
        return openTickers.contains(Ticker.norm(ticker));
    }

    public synchronized List<OrderRow> myOrders(long account, int limit) throws StoreException {
        return store.ordersOf(account, limit);
    }

    /** Open orders on every book. */
    public synchronized int openOrders() {
        int n = 0;
        for (OrderBook b : books.values()) {
            n += b.size();
        }
        return n;
    }

    public synchronized void halt(String reason) {
        halted = true;
        haltReason = reason == null || reason.isBlank() ? "halted by staff" : reason;
    }

    public synchronized void resume() {
        halted = false;
        haltReason = "";
    }

    public boolean halted() {
        return halted;
    }

    public String haltReason() {
        return haltReason;
    }

    public ExchangeConfig config() {
        return config;
    }

    /** Open order ids of an owner (account id; {@link Regulars#owner} for a regular) on every book, oldest first. */
    public synchronized List<Long> openIdsOf(long owner) {
        List<OrderBook.Order> all = new ArrayList<>();
        for (OrderBook b : books.values()) {
            all.addAll(b.ordersOf(owner));
        }
        all.sort(java.util.Comparator.comparingLong(o -> o.seq));
        return all.stream().map(o -> o.id).toList();
    }

    /** Test access: the NX book's invariants and held totals. */
    synchronized OrderBook bookForTest() {
        return nxBook();
    }

    synchronized OrderBook bookForTest(String ticker) {
        return books.get(ticker);
    }

    // ---------------------------------------------------------------- helpers

    private void remember(List<FillRow> fills) {
        for (FillRow f : fills) {
            Deque<FillRow> r = recent.computeIfAbsent(f.ticker(), k -> new ArrayDeque<>());
            r.addLast(f);
            if (r.size() > RECENT_FILLS) {
                r.removeFirst();
            }
            if (!qaFills.contains(f.id())) {
                history.computeIfAbsent(f.ticker(), k -> new TickerHistory()).add(f);
            }
        }
    }

    /** Is this order the QA checks' test regular's? */
    private static boolean qaOrder(OrderRow o) {
        return o != null && o.regular() == ExchangeQa.REGULAR;
    }

    /** At start: notes a stored fill as a QA fill when one of its orders was the test regular's. */
    private void markQa(FillRow f, Map<Long, Long> regularOf) {
        if (!f.buyerRegular() && !f.sellerRegular()) {
            return;
        }
        for (long id : new long[]{f.buyOrder(), f.sellOrder()}) {
            Long reg = regularOf.get(id);
            if (reg == null) {
                OrderRow o = rows.get(id);
                try {
                    o = o != null ? o : store.order(id);
                } catch (StoreException e) {
                    o = null;
                }
                reg = o == null ? 0L : o.regular();
                regularOf.put(id, reg);
            }
            if (reg == ExchangeQa.REGULAR) {
                qaFills.add(f.id());
                return;
            }
        }
    }

    /** Was this fill one of the QA checks' (the test regular took part)? */
    public synchronized boolean isQaFill(long fillId) {
        return qaFills.contains(fillId);
    }

    static String iso(long ms) {
        return java.time.Instant.ofEpochMilli(ms).toString();
    }

    /** An NX order's escrow bucket (NX for a sell, mesos for a buy). */
    static Bucket escrowOf(Side side, long orderId) {
        return escrowOf(Ticker.NX, side, orderId);
    }

    /** An order's escrow bucket: the ticker's asset for a sell, mesos for a buy. */
    static Bucket escrowOf(String ticker, Side side, long orderId) {
        return Bucket.escrow(side == Side.SELL ? assetOf(ticker) : Asset.MESO, orderId);
    }

    /** The asset a ticker trades (a ticker id is its asset's name); null when unknown. */
    static Asset assetOf(String ticker) {
        try {
            return Asset.valueOf(ticker);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    /** Prices of a ticker are mesos per this many units. */
    static long perOf(String ticker) {
        return Ticker.NX.equals(ticker) ? OrderBook.PER : 1;
    }

    private static OrderBook.Order toOrder(OrderRow r) {
        long owner = r.regular() > 0 ? Regulars.owner((int) r.regular()) : r.account();
        return new OrderBook.Order(r.id(), owner, r.regular() > 0, r.side(), r.price(), r.nxTotal(), r.nxLeft(),
                r.mesosHeld(), r.placedAt(), r.expiresAt(), r.seq());
    }

    static Map<String, Object> orderJson(OrderRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", r.id());
        m.put("ticker", r.ticker());
        m.put("side", r.side().name());
        m.put("price", r.price());
        m.put("nx", r.nxTotal());
        m.put("nxLeft", r.nxLeft());
        m.put("qty", r.nxTotal());
        m.put("qtyLeft", r.nxLeft());
        m.put("mesosHeld", r.mesosHeld());
        m.put("status", r.status());
        m.put("placedAt", iso(r.placedAt()));
        m.put("expiresAt", r.expiresAt() == 0 ? null : iso(r.expiresAt()));
        return m;
    }

    private static Response storeError(String rid, StoreException e) {
        return switch (e.failure) {
            case ONLINE -> Response.error(rid, "LOG_OUT_FIRST", "log out of the game to place an order");
            case MOVED -> Response.error(rid, "TRY_AGAIN", "you are changing channel or logging out; try again");
            case INSUFFICIENT -> Response.error(rid, "INSUFFICIENT_FUNDS", "not enough NX, mesos or items");
            case NOT_OWNER -> Response.error(rid, "NOT_OWNER", "that character is not on this account");
            case DUPLICATE -> Response.error(rid, "DUPLICATE", "request id already used");
            case ERROR -> Response.error(rid, "ERROR", "try again later");
        };
    }

    /** Status lines for {@code !exchange status}: the whole exchange, then one line per item ticker. */
    public synchronized List<String> status() {
        List<String> out = new ArrayList<>();
        long[] held = nxBook().heldTotals();
        Long rate = officialRate();
        Deque<FillRow> nxRecent = recent.get(Ticker.NX);
        out.add("Exchange: " + (halted ? "HALTED (" + haltReason + ")" : "running") + ", " + openOrders()
                + " open orders, NX escrow " + held[0] + " NX / " + held[1] + " mesos");
        out.add("Best bid " + nxBook().best(Side.BUY) + ", best ask " + nxBook().best(Side.SELL)
                + " (mesos per 1,000 NX); official rate " + (rate == null ? "none yet" : rate) + "; fills in memory "
                + (nxRecent == null ? 0 : nxRecent.size()));
        out.add("Fee " + config.feeBps() + " bps from each side, orders " + config.minNx() + ".." + config.maxNx()
                + " NX by " + config.lotNx() + ", NX " + config.maxOpen() + " open / " + config.perDay()
                + " a day per ticker, " + config.cooldownMs() / 1000 + " s apart, regulars "
                + config.regulars() + (config.houseAccount() > 0 ? " (house account " + config.houseAccount() + ")"
                : " (idle: no house account)"));
        for (String id : openTickers) {
            Ticker t = tickers.get(id);
            if (t.item()) {
                out.add(tickerLine(t));
            }
        }
        return out;
    }

    /** One ticker's status line ({@code !exchange status <ticker>}). */
    public synchronized String tickerLine(Ticker t) {
        OrderBook b = books.get(t.id());
        long[] held = b.heldTotals();
        TickerHistory.Stats st = stats(t.id());
        return String.format("%s (%s): %d open, bid %,d / ask %,d %s; escrow %,d / %,d mesos; 24h last %s, vol %,d; "
                        + "limits %d open / %d a day / %d h / %,d per order; fair %s%s",
                t.id(), t.name(), b.size(), b.best(Side.BUY), b.best(Side.SELL), t.priceUnit(), held[0], held[1],
                st.last() == null ? "none" : String.format("%,d", st.last()), st.qty(), t.maxOpen(), t.perDay(),
                t.expiryMs() / 3_600_000L, t.maxQty(), fairText(t.id()),
                openTickers.contains(t.id()) ? "" : " (closed for new orders)");
    }

    /** A ticker's fair value (1.6.1 volatility, {@code FairValue}) for the status line. */
    private static String fairText(String ticker) {
        Long f = soloMapling.Economy.exchange.volatility.FairValue.of(ticker);
        return f == null ? "none yet" : String.format("%,d", f);
    }

    /** Lines for {@code !exchange book}. */
    public synchronized List<String> bookLines(int levels) {
        return bookLines(Ticker.NX, levels);
    }

    /** A ticker's book lines. */
    public synchronized List<String> bookLines(String ticker, int levels) {
        List<String> out = new ArrayList<>();
        Ticker t = tickers.get(ticker);
        OrderBook b = books.get(ticker);
        if (t == null || b == null) {
            return out;
        }
        String u = t.item() ? " " + t.name() : " NX";
        List<OrderBook.Level> asks = b.depth(Side.SELL, levels);
        for (int i = asks.size() - 1; i >= 0; i--) {
            OrderBook.Level l = asks.get(i);
            out.add("  ask " + l.price() + "  " + l.nx() + u + " (" + l.orders() + ")");
        }
        out.add("  ----");
        for (OrderBook.Level l : b.depth(Side.BUY, levels)) {
            out.add("  bid " + l.price() + "  " + l.nx() + u + " (" + l.orders() + ")");
        }
        return out;
    }
}
