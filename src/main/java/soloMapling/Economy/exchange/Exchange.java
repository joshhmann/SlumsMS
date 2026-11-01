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
import soloMapling.Economy.exchange.volatility.MarketConfig;

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

    /**
     * A previewed place waiting for its confirm. {@code site}: previewed from the site; {@code approvedAt} (0 = not)
     * when the password was typed for it ({@link #approve}, b005 step-up).
     */
    private record Pending(Place place, long expiresAt, boolean site, long approvedAt) {
    }

    /** A site preview's token prefix on the order's request id ({@code @nx} in game: "t-"): which orders came from the site. */
    static final String SITE_RID = "s-";

    /** The stolen-cookie guard (1.6.1, owner 2026-10-29): NX price band, site sign-in age (no daily caps). */
    private volatile NxGuard guard = NxGuard.fromSystem();

    public NxGuard guard() {
        return guard;
    }

    /** Another guard (tests, the QA staging). */
    public void guard(NxGuard g) {
        guard = g == null ? NxGuard.OFF : g;
    }

    /** 1.6.2 b005: the game-side sign-in age and the password step-up for site orders ({@link SiteReauth}). */
    private volatile SiteReauth siteReauth = SiteReauth.fromSystem();
    private volatile SiteReauth.SignIns signIns = SiteReauth.GAME;
    /** Account -> {at, mesos} of its site buys confirmed without a password (the step-up's free amount). */
    private final Map<Long, Deque<long[]>> freeBuys = new HashMap<>();

    public SiteReauth siteReauth() {
        return siteReauth;
    }

    /** Another policy (tests). */
    public void siteReauth(SiteReauth r) {
        siteReauth = r == null ? SiteReauth.OFF : r;
    }

    /** Where password checks are read from ({@link SiteReauth#GAME} in the game). */
    public SiteReauth.SignIns signIns() {
        return signIns;
    }

    public void signIns(SiteReauth.SignIns s) {
        signIns = s == null ? new SiteReauth.SignIns() : s;
    }

    // ---------------------------------------------------------------- the NX desk (ticket 162)

    /** The desk's knobs ({@link DeskConfig}); the desk itself is null while the switch is off. */
    private volatile DeskConfig deskConfig = DeskConfig.fromSystem();
    private Desk desk;
    /** 1.6.3: the bots' hourly NX allowance at the desk ({@code -Dslums.exchange.traders.nx.*}). */
    private final DeskBotCaps deskBotCaps = DeskBotCaps.fromSystem();

    public DeskConfig deskConfig() {
        return deskConfig;
    }

    /** Another switch and numbers (tests, the QA staging): call before {@link #recover}. */
    public void deskConfig(DeskConfig c) {
        deskConfig = c == null ? DeskConfig.defaults() : c;
    }

    /** The running desk, or null when {@code -Dslums.exchange.desk} is off (then nothing here changes anything). */
    public synchronized Desk desk() {
        return desk;
    }

    /** Is the desk trading right now (switched on, a house account named, not halted)? */
    public synchronized boolean deskOpen() {
        return desk != null && closeReason() == null;
    }

    /** Why the desk is not trading: null when it is, else "no house account" or "halted" (or "off" without a desk). */
    public synchronized String closeReason() {
        if (desk == null) {
            return "off";
        }
        return config.houseAccount() <= 0 ? "no house account" : halted ? "halted" : null;
    }

    /** The desk's HOUSE bucket for an asset: its NX and its mesos (id {@link Desk#HOUSE}). */
    public static Bucket deskBucket(Asset a) {
        return Bucket.house(a, Desk.HOUSE);
    }

    /**
     * What a trader bot sees of the desk: the quotes of the moment and how much NX it may still trade this hour
     * ({@link DeskBotCaps}). Null when no bot should trade the desk now: the desk is off or closed, the exchange is
     * halted, {@code -Dslums.exchange.traders.nx} is off, the account is not a bot, or the hour's allowance is spent.
     */
    public record DeskBotQuote(long mid, long buy, long sell, long minQty, long lot, long left) {}

    public synchronized DeskBotQuote deskForBot(long account) {
        if (!recovered || halted || desk == null || closeReason() != null || !MarketConfig.tradersNxOn()
                || !botAccount(account)) {
            return null;
        }
        long now = clock.getAsLong();
        long left = Math.min(deskBotCaps.left(account, now), deskBotCaps.worldLeft(now));
        if (left < ticker(Ticker.NX).minQty()) {
            return null;
        }
        Ticker t = ticker(Ticker.NX);
        return new DeskBotQuote(desk.mid(), desk.buy(), desk.sell(), t.minQty(), t.lot(), left);
    }

    // ---------------------------------------------------------------- audit (1.6.2)

    private static final Logger AUDIT = LoggerFactory.getLogger("soloMapling.Economy.exchange.Audit");
    private volatile java.util.function.Consumer<String> audit = AUDIT::info;

    /** Where the audit lines go (tests); the game logs them on the {@code soloMapling.Economy.exchange.Audit} logger. */
    public void auditSink(java.util.function.Consumer<String> sink) {
        audit = sink == null ? AUDIT::info : sink;
    }

    /** One audit line: "NX exchange audit: " + {@code line}. Never throws. */
    void audit(String line) {
        try {
            audit.accept("NX exchange audit: " + line);
        } catch (RuntimeException e) {
            // the audit never breaks a trade
        }
    }

    /** "acct=3 char=13" for an order row (a regular: "regular=2"; a trader bot: "bot=..."). */
    private static String who(OrderRow r) {
        if (r == null) {
            return "acct=? char=?";
        }
        if (r.regular() > 0) {
            return "regular=" + r.regular();
        }
        return botAccount(r.account()) ? "bot=" + r.character() : "acct=" + r.account() + " char=" + r.character();
    }

    /** Applies a batch and, once it is in the store, writes its audit line ({@code what}: place, cancel, ...). */
    private void commit(Batch b, String what) throws StoreException {
        store.apply(b);
        audit(describe(b, what));
    }

    private String describe(Batch b, String what) {
        StringBuilder sb = new StringBuilder(what).append(" batch=").append(b.id());
        if (b.liveAccount() > 0) {
            sb.append(" live=acct=").append(b.liveAccount()).append(" char=").append(b.liveCharacter());
        }
        Map<Long, OrderRow> byId = new HashMap<>();
        for (OrderRow o : b.orders()) {
            byId.put(o.id(), o);
            sb.append(" | order #").append(o.id()).append(' ').append(o.requestId()).append(' ').append(who(o))
                    .append(' ').append(o.side()).append(' ').append(o.ticker()).append(' ').append(o.nxTotal())
                    .append('@').append(o.price()).append(" left=").append(o.nxLeft()).append(' ').append(o.status());
        }
        for (FillRow f : b.fills()) {
            OrderRow buy = byId.getOrDefault(f.buyOrder(), rows.get(f.buyOrder()));
            OrderRow sell = byId.getOrDefault(f.sellOrder(), rows.get(f.sellOrder()));
            sb.append(" | fill #").append(f.id()).append(' ').append(f.ticker()).append(' ').append(f.nx()).append('@')
                    .append(f.price()).append(" mesos=").append(f.mesos()).append(" buy#").append(f.buyOrder())
                    .append('(').append(who(buy)).append(") sell#").append(f.sellOrder()).append('(').append(who(sell))
                    .append(')');
        }
        for (Posting p : b.postings()) {
            if (p.from().kind().external() || p.to().kind().external() || p.why() == Why.REVERSE
                    || p.why() == Why.FUND) {
                sb.append(" | ").append(p.why()).append(' ').append(p.from()).append("->").append(p.to()).append(' ')
                        .append(p.amount()).append(p.account() > 0 ? " acct=" + p.account() : "");
            }
        }
        return sb.toString();
    }

    /**
     * The NX limit prices a player may use now: {lowest sell, highest buy} in mesos per 1,000 NX ({@link NxGuard}):
     * 50% and 200% of the official rate, else of NX's starting price (5,000,000), always inside the hard window
     * {@link NxGuard#NX_LOWEST}..{@link NxGuard#NX_HIGHEST} (2,500..10,000 mesos per NX, owner 2026-10-31 ~19:50 UTC),
     * which the relative band can only tighten.
     */
    public synchronized long[] nxBand() {
        Ticker t = tickers.get(Ticker.NX);
        return guard.band(officialRate(Ticker.NX), t.minPrice(), t.maxPrice());
    }

    /**
     * A price per 1,000 NX as players read it (owner 2026-10-29): whole mesos per 1 NX with thousands separators,
     * rounded half up for display only ("5,000,000" -> "5,000"; "1,234,567" -> "1,235").
     */
    public static String perNx(long per1000) {
        return String.format("%,d", Math.floorDiv(per1000 + 500, 1_000));
    }

    /** PRICE_BAND for an NX order outside {@link #nxBand}, else null. */
    private Response priceBand(String rid, Place p) {
        if (!Ticker.NX.equals(p.ticker())) {
            return null;
        }
        Response outside = outOfWindow(rid, p.ticker(), p.side(), p.price());
        if (outside != null) {
            return outside;
        }
        long[] b = nxBand();
        boolean low = p.side() == Side.SELL && p.price() < b[0];
        boolean high = p.side() == Side.BUY && p.price() > b[1];
        if (!low && !high) {
            return null;
        }
        String basis = rateBasis();
        String msg = low ? String.format("an NX sell must ask at least %s mesos per NX %s", perNx(b[0]), basis)
                : String.format("an NX buy may bid at most %s mesos per NX %s", perNx(b[1]), basis);
        return new Response(rid, false, "PRICE_BAND", msg, bandData(b));
    }

    /**
     * PRICE_BAND for an NX order priced outside the hard window (owner 2026-10-31 ~19:50 UTC: no NX order on either
     * side is ever outside {@link NxGuard#NX_LOWEST}..{@link NxGuard#NX_HIGHEST}, whatever the official rate says), else
     * null. Applied by every caller of {@link #placeOrder} as well, because the house regulars quote through their own
     * path and never see {@link #priceBand}.
     */
    private Response outOfWindow(String rid, String ticker, Side side, long price) {
        if (!Ticker.NX.equals(ticker) || (price >= NxGuard.NX_LOWEST && price <= NxGuard.NX_HIGHEST)) {
            return null;
        }
        // the two clamps players meet most (a dump below the floor, an overpay above the ceiling) keep their old words
        String msg = price < NxGuard.NX_LOWEST && side == Side.SELL
                ? String.format("an NX sell must ask at least %s mesos per NX %s", perNx(NxGuard.NX_LOWEST), rateBasis())
                : price > NxGuard.NX_HIGHEST && side == Side.BUY
                ? String.format("an NX buy may bid at most %s mesos per NX %s", perNx(NxGuard.NX_HIGHEST), rateBasis())
                : String.format("an NX order must price NX from %s to %s mesos per NX %s", perNx(NxGuard.NX_LOWEST),
                        perNx(NxGuard.NX_HIGHEST), rateBasis());
        return new Response(rid, false, "PRICE_BAND", msg, bandData(nxBand()));
    }

    /** How the refusal explains the band: the official rate, or NX's starting price before there is one. */
    private String rateBasis() {
        Long rate = officialRate(Ticker.NX);
        return rate == null
                ? String.format("(no official rate yet: NX counts as %s mesos per NX)",
                        perNx(guard.startPrice() > 0 ? guard.startPrice() : NxGuard.START_PRICE))
                : String.format("(the official rate is %s mesos per NX)", perNx(rate));
    }

    /** A PRICE_BAND's data: the band it refused against (per 1,000 NX) and the rate behind it (null before one). */
    private Map<String, Object> bandData(long[] b) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("minPrice", b[0]);
        d.put("maxPrice", b[1]);
        d.put("rate", officialRate(Ticker.NX));
        return d;
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
        List<OrderRow> clamped = new ArrayList<>();
        for (OrderRow r : l.openOrders()) {
            if (!books.containsKey(r.ticker())) {
                unknown.add("order " + r.id() + " is on an unknown ticker " + r.ticker());
                continue;
            }
            rows.put(r.id(), r);
            if (Ticker.NX.equals(r.ticker()) && (r.price() < NxGuard.NX_LOWEST
                    || r.price() > NxGuard.NX_HIGHEST)) {
                // an order placed before the hard window (owner 2026-10-31) can rest outside it: it is cancelled and
                // refunded below, through the normal path, once the book and the reconcile are in
                clamped.add(r);
            }
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
            // a bot's NX purse stays in PAYOUT (no wallet can take it): never a collection to retry
            if (e.getKey().kind() == Kind.PAYOUT && botAccount(e.getKey().id()) && e.getValue() > 0
                    && e.getKey().asset() != Asset.NX) {
                botPayouts.add(e.getKey().id());
            }
        }
        // the newest fills overall, and every ticker's own fills of the last 30 days: the official rate's 7-day window
        // must not lose NX's fills behind the item tickers' (bots trade those far more often)
        Map<String, java.util.TreeMap<Long, FillRow>> byTicker = new HashMap<>();
        for (FillRow f : l.recentFills()) {
            byTicker.computeIfAbsent(f.ticker(), k -> new java.util.TreeMap<>()).put(f.id(), f);
        }
        for (FillRow f : past) {
            byTicker.computeIfAbsent(f.ticker(), k -> new java.util.TreeMap<>()).put(f.id(), f);
        }
        byTicker.forEach((t, m) -> {
            ArrayDeque<FillRow> d = new ArrayDeque<>(m.values());
            while (d.size() > RECENT_FILLS) {
                d.removeFirst();
            }
            recent.put(t, d);
        });
        nextOrder = l.maxOrderId() + 1;
        nextFill = l.maxFillId() + 1;
        nextSeq = l.maxSeq() + 1;
        nextBatch = l.maxBatch() + 1;
        // ticket 162: the desk takes over NX's official rate. Its R is stored; without one it starts from the rate the
        // book published until now (the 7-day VWAP), else from NX's starting price.
        desk = null;
        String deskError = null;
        Long officialNow = officialRate(Ticker.NX);
        if (deskConfig.enabled()) {
            try {
                Desk.State stored = store.loadDesk();
                desk = Desk.start(deskConfig, stored, officialNow, clock.getAsLong());
                audit("desk start R=" + desk.mid() + " buy=" + desk.buy() + " sell=" + desk.sell()
                        + (stored != null ? " (stored)" : officialNow != null ? " (from the book's rate)" : " (start)"));
            } catch (StoreException e) {
                // the desk cannot quote without its stored R: halt, so nothing trades at a rate nobody has seen. Two
                // causes: the table is missing (migration 058 not applied) or the row is there and will not decode.
                deskError = "desk state unreadable (" + e + "): fix or delete exchange_desk row id 1, or apply "
                        + "migration 058-nx-desk if the table is missing";
                log.error("NX exchange: {}", deskError);
            }
        }
        recovered = true;
        List<String> problems = new ArrayList<>(unknown);
        problems.addAll(reconcile(l));
        if (deskError != null) {
            problems.add(deskError);
            halt(deskError);
        } else if (!problems.isEmpty()) {
            halt("reconcile: " + problems.size() + " mismatch(es), first: " + problems.get(0));
        } else {
            cancelClamped(clamped);
        }
        return problems;
    }

    /**
     * Cancels and refunds, through the normal cancel path, every open NX order the hard window now forbids (owner
     * 2026-10-31 ~20:05 UTC: "open NX orders whose price is outside the clamp are CANCELLED and REFUNDED through the
     * normal cancel path, each logged in the exchange audit"). Runs once at recovery, after the reconcile passed; the
     * refund lands in the payer's PAYOUT bucket exactly like its own cancel, and the store row is closed, never edited.
     */
    private void cancelClamped(List<OrderRow> clamped) {
        for (OrderRow r : clamped) {
            long owner = r.regular() > 0 ? Regulars.owner((int) r.regular()) : r.account();
            Response res = closeOrders("clamp cancel order #" + r.id(), List.of(r.id()), owner,
                    OrderBook.Reason.CANCELLED);
            if (res.ok()) {
                log.warn("exchange: clamp cancel order #{} ({} at {} per 1,000 NX, outside the hard window {}..{}): "
                        + "refunded to the payer", r.id(), r.side(), r.price(), NxGuard.NX_LOWEST,
                        NxGuard.NX_HIGHEST);
            } else {
                log.error("exchange: clamp cancel order #{} ({} at {} per 1,000 NX) failed: {} {}; it stays open",
                        r.id(), r.side(), r.price(), res.code(), res.message());
            }
        }
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
        if (desk != null && Ticker.NX.equals(p.ticker())) {
            return Response.error(rid, "BAD_REQUEST", deskBookOffMessage());
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
        Response guarded = priceBand(rid, p);
        if (guarded != null) {
            return guarded;
        }
        String limit = limits(p.account(), t, now);
        if (limit != null) {
            return Response.error(rid, limit, limitMessage(limit, t, p.account(), now));
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
        return preview(p, false);
    }

    /** {@code site}: from the site (its order's request id starts {@link #SITE_RID}). */
    public synchronized Response preview(Place p, boolean site) {
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
        if (desk != null && Ticker.NX.equals(p.ticker())) {
            return deskPreview(p, site);   // ticket 162: NX is the desk's; the book is off
        }
        String bad = validate(p);
        if (bad != null) {
            return Response.error(rid, "BAD_REQUEST", bad);
        }
        long now = clock.getAsLong();
        Ticker tk = tickers.get(p.ticker());
        Response guarded = priceBand(rid, p);
        if (guarded != null) {
            return guarded;
        }
        String limit = limits(p.account(), tk, now);
        if (limit != null) {
            return Response.error(rid, limit, limitMessage(limit, tk, p.account(), now));
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
        Place placed = new Place((site ? SITE_RID : "t-") + t, p.account(), p.character(), p.side(), p.price(), p.nx(),
                tk.id());
        pending.put(t, new Pending(placed, expires, site, 0));
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
        if (site) {
            // b005: the site shows the password box with the confirm when this is true (POST /exchange/approve)
            data.put("passwordRequired", passwordRequired(placed, now));
        }
        return Response.ok(rid, data);
    }

    /**
     * Step 2: places what the token previewed (the token is the order's request id, so a repeated confirm returns the
     * same order). TOKEN_EXPIRED once the token is older than {@link ExchangeConfig#confirmMs()} or unknown (also after
     * a restart: tokens live in memory only).
     */
    public synchronized Response confirm(String rid, long account, String token) {
        if (halted) {
            return Response.error(rid, "HALTED", "the exchange is stopped: " + haltReason);
        }
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
        if (desk != null && Ticker.NX.equals(x.place().ticker())) {
            return deskFill(rid, x.place(), token, x.site());   // ticket 162: at the held quote, now
        }
        Response r = place(x.place());
        return new Response(rid, r.ok(), r.code(), r.message(), r.data());
    }

    // ---------------------------------------------------------------- the NX desk (ticket 162)

    /** "the NX desk is closed: ..." with the reason the site shows ({@link #closeReason()}). */
    private String deskClosedMessage() {
        String why = closeReason();
        return "no house account".equals(why)
                ? "the NX desk is closed: no house account (-Dslums.exchange.houseAccount)"
                : "the NX desk is closed: " + why;
    }

    /** Why an NX order is refused while the desk runs (ticket 162 §4: the book is off for NX only). */
    private static String deskBookOffMessage() {
        return "NX trades at the desk's quote now: no NX orders. Use @nx buy/sell in game or the site's NX page";
    }

    /** Which wallet leg the player pays from: NX from the account (a SELL to the desk), mesos from the character. */
    private static Bucket walletOf(Place p, Side side) {
        return side == Side.SELL ? Bucket.wallet(Asset.NX, p.account()) : Bucket.wallet(Asset.MESO, p.character());
    }

    /**
     * A desk preview (ticket 162): the quote is held for {@link ExchangeConfig#confirmMs()} and the confirm fills the
     * whole amount at it. Unlike the book's preview the request carries no price (a price is refused: the desk trades
     * at its quote), and there is no queue and no partial fill. Nothing moves here.
     */
    private Response deskPreview(Place p, boolean site) {
        String rid = p.requestId();
        if (p.price() > 0) {
            return Response.error(rid, "BAD_REQUEST", "the NX desk trades at its quote: send no price");
        }
        if (closeReason() != null) {
            return Response.error(rid, "DESK_CLOSED", deskClosedMessage());
        }
        if (p.nx() < deskConfig.minQty()) {
            return Response.error(rid, "MIN_QTY", "the desk trades whole NX from " + deskConfig.minQty() + " up");
        }
        Side side = p.side();
        long now = clock.getAsLong();
        long price = desk.quote(side);
        long mesos;
        try {
            mesos = desk.mesos(side, p.nx(), price);
        } catch (ArithmeticException e) {
            return Response.error(rid, "BAD_REQUEST", "that is more NX than one trade can carry");
        }
        long fee = desk.config().fee(mesos);
        if (mesos <= 0 || mesos > Integer.MAX_VALUE - fee) {
            return Response.error(rid, "BAD_REQUEST", "that is more mesos than one trade can carry");
        }
        LiveWallet w = live.find(p.account());
        if (w != null && w.character() != p.character()) {
            return Response.error(rid, "LOG_OUT_FIRST", "you are logged in as another character: use that one, or log out");
        }
        if (w == null && presence.test(p.account())) {
            return Response.error(rid, "LOG_OUT_FIRST", "log out of the game to trade with the desk");
        }
        long deskNx, deskMeso;
        try {
            deskNx = store.balance(deskBucket(Asset.NX));
            deskMeso = store.balance(deskBucket(Asset.MESO));
        } catch (StoreException e) {
            return storeError(rid, e);
        }
        Response short_ = deskShort(rid, side, p.nx(), mesos, deskNx, deskMeso);
        if (short_ != null) {
            return short_;
        }
        Asset pays = side == Side.SELL ? Asset.NX : Asset.MESO;
        long takes = side == Side.SELL ? p.nx() : mesos + fee;
        long have;
        try {
            have = w != null ? w.have(pays) : store.walletBalance(walletOf(p, side));
        } catch (StoreException e) {
            return storeError(rid, e);
        }
        if (have < takes) {
            return Response.error(rid, "INSUFFICIENT_FUNDS", "you need " + takes + " " + unitName(pays)
                    + (pays == Asset.NX ? " on your account" : " on this character"));
        }
        long expires = now + config.confirmMs();
        String t = holdDesk(p, side, price, site, expires);
        Place held = new Place((site ? SITE_RID : "t-") + t, p.account(), p.character(), side, price, p.nx(),
                Ticker.NX);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", t);
        data.put("expiresAt", iso(expires));
        data.put("side", side.name());
        data.put("nx", p.nx());
        data.put("price", price);
        data.put("mid", desk.mid());
        // API.md: the total the player pays (BUY) or gets (SELL); fee 0 under the owner's set B
        data.put("mesos", side == Side.BUY ? mesos + fee : mesos - fee);
        data.put("desk_nx", deskNx);
        if (site) {
            data.put("passwordRequired", passwordRequired(held, now));
        }
        return Response.ok(rid, data);
    }

    /**
     * DESK_EMPTY when the house cannot deliver its side of that trade (what it has comes back as data): its NX for a
     * player's BUY, its mesos for a player's SELL. Nothing is minted.
     */
    private static Response deskShort(String rid, Side side, long nx, long mesos, long deskNx, long deskMeso) {
        boolean short_ = side == Side.BUY ? deskNx < nx : deskMeso < mesos;
        if (!short_) {
            return null;
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("desk_nx", deskNx);
        d.put("desk_mesos", deskMeso);
        return new Response(rid, false, "DESK_EMPTY", side == Side.BUY
                ? "the desk has " + deskNx + " NX to sell; ask for less"
                : "the desk has " + deskMeso + " mesos to buy with right now; try later", d);
    }

    /** Holds a desk quote: a fresh one-use token for {@code p} at {@code price}, kept until {@code expires}. */
    private String holdDesk(Place p, Side side, long price, boolean site, long expires) {
        long now = clock.getAsLong();
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
        pending.put(t, new Pending(new Place((site ? SITE_RID : "t-") + t, p.account(), p.character(), side, price,
                p.nx(), Ticker.NX), expires, site, 0));
        latestToken.put(p.account(), t);
        return t;
    }

    /**
     * A desk confirm: the whole amount at the quote the preview held, at once. Nothing moves when it refuses:
     * DESK_CLOSED (no house account, halted), QUOTE_MOVED (the hourly step moved the quote: a fresh quote and token
     * come with it) or DESK_EMPTY. A token is one use only.
     */
    private Response deskFill(String rid, Place p, String token, boolean site) {
        long now = clock.getAsLong();
        pending.remove(token);
        if (halted) {
            return Response.error(rid, "HALTED", "the exchange is stopped: " + haltReason);
        }
        if (closeReason() != null) {
            return Response.error(rid, "DESK_CLOSED", deskClosedMessage());
        }
        Side side = p.side();
        long nx = p.nx(), price = p.price();
        if (desk.quote(side) != price) {
            return deskQuoteMoved(rid, p, price, site, now);
        }
        long mesos = desk.mesos(side, nx, price);
        long fee = desk.config().fee(mesos);
        long deskNx, deskMeso;
        try {
            deskNx = store.balance(deskBucket(Asset.NX));
            deskMeso = store.balance(deskBucket(Asset.MESO));
        } catch (StoreException e) {
            return storeError(rid, e);
        }
        Response short_ = deskShort(rid, side, nx, mesos, deskNx, deskMeso);
        if (short_ != null) {
            return short_;
        }
        // A confirm is a write, so it repeats the preview's who-is-this checks, as place() does: the held quote was for
        // the previewed character, and the fill moves that character's mesos (BUY) or the account's NX (SELL). With
        // another character live, the store would write the row the request names while memory pays the live one.
        LiveWallet w = live.find(p.account());
        if (w != null) {
            if (w.character() != p.character()) {
                return Response.error(rid, "LOG_OUT_FIRST",
                        "you are logged in as another character: use that one, or log out");
            }
            return deskFillLive(rid, p, w, now, mesos, fee);
        }
        // offline, but the account is logged in and its character has not loaded yet: a wallet write now is overwritten
        // when it does (place() refuses the same window)
        if (presence.test(p.account())) {
            return Response.error(rid, "LOG_OUT_FIRST", "log out of the game to trade with the desk");
        }
        collectQuietly(p.account());
        return deskFillOffline(rid, p, now, mesos, fee);
    }

    /** QUOTE_MOVED with the desk's quote now and a fresh token for it (the site shows them and asks to confirm again). */
    private Response deskQuoteMoved(String rid, Place p, long held, boolean site, long now) {
        Side side = p.side();
        long price = desk.quote(side);
        long expires = now + config.confirmMs();
        String t = holdDesk(p, side, price, site, expires);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("price", price);
        d.put("mid", desk.mid());
        d.put("expiresAt", iso(expires));
        d.put("token", t);
        d.put("held", held);
        return new Response(rid, false, "QUOTE_MOVED", "the desk's quote moved since your preview: "
                + held + " is now " + price + " per 1,000 NX; confirm again", d);
    }

    /**
     * One desk fill for a player who is not logged in: the money moves straight between wallets and the house's HOUSE
     * buckets (NX from the account, mesos from or to the character), all in the batch's transaction, so a failure
     * changes nothing.
     */
    private Response deskFillOffline(String rid, Place p, long now, long mesos, long fee) {
        Side side = p.side();
        Asset pays = side == Side.SELL ? Asset.NX : Asset.MESO;
        long takes = side == Side.SELL ? p.nx() : mesos + fee;
        long have;
        try {
            have = store.walletBalance(walletOf(p, side));
        } catch (StoreException e) {
            return storeError(rid, e);
        }
        if (have < takes) {
            return Response.error(rid, "INSUFFICIENT_FUNDS", "you need " + takes + " " + unitName(pays)
                    + (pays == Asset.NX ? " on your account" : " on this character"));
        }
        committed = false;
        try {
            commitFill(p, now, mesos, fee, 0, 0);
        } catch (RuntimeException | StoreException e) {
            if (committed) {
                halt("desk fill " + rid + " failed after its commit: " + e);
            }
            log.error("NX exchange: desk fill {} failed", rid, e);
            return Response.error(rid, "ERROR", "try again later");
        }
        return Response.ok(rid, fillData(p, mesos, now));
    }

    /**
     * One desk fill for a logged-in player, under the character's save lock: check, save, take what it pays, commit,
     * then give what it buys in memory (a failed commit gives the take back). Nothing is minted either way.
     */
    private Response deskFillLive(String rid, Place p, LiveWallet w, long now, long mesos, long fee) {
        Side side = p.side();
        Asset pays = side == Side.SELL ? Asset.NX : Asset.MESO;
        long takes = side == Side.SELL ? p.nx() : mesos + fee;
        Asset gets = side == Side.SELL ? Asset.MESO : Asset.NX;
        long gives = side == Side.SELL ? mesos - fee : p.nx();
        return w.locked(() -> {
            if (!w.usable()) {
                return Response.error(rid, "TRY_AGAIN",
                        "you are changing channel, logging out or in the Cash Shop; try again in a moment");
            }
            if (!w.saveNow()) {
                return Response.error(rid, "ERROR", "could not save your character first; try again later");
            }
            if (w.have(pays) < takes) {
                return Response.error(rid, "INSUFFICIENT_FUNDS", "you need " + takes + " " + unitName(pays)
                        + (pays == Asset.NX ? " on your account" : " on this character"));
            }
            if (w.room(gets, gives) < gives) {
                return Response.error(rid, "BAD_REQUEST", "you cannot hold " + gives + " more " + unitName(gets)
                        + " on this character");
            }
            if (!w.take(pays, takes)) {
                return Response.error(rid, "INSUFFICIENT_FUNDS", "you need " + takes + " " + unitName(pays));
            }
            committed = false;
            try {
                commitFill(p, now, mesos, fee, w.account(), w.character());
            } catch (RuntimeException | StoreException e) {
                if (!committed) {
                    giveBack(w, pays, takes, "a refused desk fill " + rid);
                } else {
                    halt("desk fill " + rid + " failed after its commit: " + e);
                }
                log.error("NX exchange: desk fill {} failed", rid, e);
                return Response.error(rid, "ERROR", "try again later");
            }
            if (!w.give(gets, gives)) {
                log.error("NX exchange: desk fill {} committed but {} {} could not be given to character {} "
                        + "(account {}): staff must add it", rid, gives, gets, w.character(), w.account());
                halt("could not give " + gives + " " + gets + " to character " + w.character());
            }
            w.tell("NX desk: " + (side == Side.BUY ? "bought" : "sold") + " " + p.nx() + " NX at " + p.price()
                    + " mesos per 1,000 (" + (side == Side.BUY ? "-" : "+") + mesos + " mesos).");
            return Response.ok(rid, fillData(p, mesos, now));
        });
    }

    /**
     * 1.6.3 (b260): one desk fill for a trader bot, in its own purse and mesos. There is no preview, token or queue for
     * a bot (it has no site, and the desk trades only at its own quote), so the bot names a side and a whole lot of NX
     * and the fill goes in at the quote of the moment, which is the quote {@link #deskForBot} showed it a moment
     * before; a step in between is a slightly worse price, never a refusal. Its money leg is its bookkeeping bucket
     * (the store skips BOT buckets: the bot's own memory moved the mesos) and its NX leg is the purse {@link #botNx}
     * reads, so the house and the bot's NX still balance. MIN_QTY, BOT_CAP, DESK_EMPTY and INSUFFICIENT_FUNDS refuse
     * without moving anything, as they do for a player.
     */
    public synchronized Response deskBot(String rid, LiveWallet w, Side side, long nx) {
        if (!recovered) {
            return Response.error(rid, "UNAVAILABLE", "the exchange is not started");
        }
        if (halted) {
            return Response.error(rid, "HALTED", "the exchange is stopped: " + haltReason);
        }
        if (desk == null || closeReason() != null) {
            return Response.error(rid, "DESK_CLOSED", deskClosedMessage());
        }
        if (!MarketConfig.tradersNxOn()) {
            return Response.error(rid, "BAD_REQUEST", "trader bots do not trade NX (-Dslums.exchange.traders.nx)");
        }
        if (w == null || !botAccount(w.account())) {
            return Response.error(rid, "BAD_REQUEST", "a trader bot trades with its own wallet");
        }
        Ticker t = ticker(Ticker.NX);
        if (nx <= 0 || nx % t.lot() != 0) {
            return Response.error(rid, "BAD_REQUEST", "the desk trades whole lots of " + t.lot() + " NX");
        }
        if (nx < t.minQty()) {
            return Response.error(rid, "MIN_QTY", "the desk trades from " + t.minQty() + " NX up");
        }
        long now = clock.getAsLong();
        if (nx > deskBotCaps.left(w.account(), now)) {
            return Response.error(rid, "BOT_CAP", "this bot has " + deskBotCaps.left(w.account(), now)
                    + " NX of its hourly desk allowance left");
        }
        long worldLeft = deskBotCaps.worldLeft(now);
        if (nx > worldLeft) {
            return Response.error(rid, "BOT_CAP", "the traders have " + worldLeft
                    + " NX of their hourly desk allowance left");
        }
        long price = desk.quote(side);
        long mesos, fee;
        try {
            mesos = desk.mesos(side, nx, price);
        } catch (ArithmeticException e) {
            return Response.error(rid, "BAD_REQUEST", "that is more NX than one trade can carry");
        }
        fee = desk.config().fee(mesos);
        if (mesos <= 0 || mesos > Integer.MAX_VALUE - fee) {
            return Response.error(rid, "BAD_REQUEST", "that is more NX than one trade can carry");
        }
        long deskNx, deskMeso;
        try {
            deskNx = store.balance(deskBucket(Asset.NX));
            deskMeso = store.balance(deskBucket(Asset.MESO));
        } catch (StoreException e) {
            return storeError(rid, e);
        }
        Response short_ = deskShort(rid, side, nx, mesos, deskNx, deskMeso);
        if (short_ != null) {
            return short_;
        }
        Place p = new Place(rid, w.account(), w.character(), side, price, nx, Ticker.NX);
        Asset meso = Asset.MESO;
        long takes = side == Side.BUY ? mesos + fee : 0;
        return w.locked(() -> {
            if (!w.usable()) {
                return Response.error(rid, "TRY_AGAIN",
                        "the bot is changing channel or logging out; try again in a moment");
            }
            if (!w.saveNow()) {
                return Response.error(rid, "ERROR", "could not save the bot first; try again later");
            }
            if (side == Side.BUY) {
                if (w.have(meso) < takes) {
                    return Response.error(rid, "INSUFFICIENT_FUNDS", "the bot needs " + takes + " mesos");
                }
                if (!w.take(meso, takes)) {
                    return Response.error(rid, "INSUFFICIENT_FUNDS", "the bot needs " + takes + " mesos");
                }
            } else if (botNx(w.account()) < nx) {
                return Response.error(rid, "INSUFFICIENT_FUNDS", "the bot has fewer than " + nx + " NX");
            }
            committed = false;
            try {
                commitFill(p, now, mesos, fee, 0, 0, Bucket.bot(meso, w.character()),
                        Bucket.payout(Asset.NX, w.account()));
            } catch (RuntimeException | StoreException e) {
                if (!committed) {
                    if (side == Side.BUY) {
                        giveBack(w, meso, takes, "a refused desk fill " + rid);
                    }
                } else {
                    halt("desk fill " + rid + " failed after its commit: " + e);
                }
                log.error("NX exchange: bot desk fill {} failed", rid, e);
                return Response.error(rid, "ERROR", "try again later");
            }
            if (side == Side.SELL) {
                long paid = mesos - fee;
                if (!w.give(meso, paid)) {
                    log.error("NX exchange: bot desk fill {} committed but {} mesos could not be given to character "
                            + "{} (account {}): staff must add it", rid, paid, w.character(), w.account());
                    halt("could not give " + paid + " mesos to character " + w.character());
                }
                // the bot's mesos are in memory: save now, or a crash before its next save loses what the house paid
                w.saveNow();
            }
            deskBotCaps.count(w.account(), now, nx);
            w.tell("NX desk: " + (side == Side.BUY ? "bought" : "sold") + " " + nx + " NX at " + price
                    + " mesos per 1,000 (" + (side == Side.BUY ? "-" : "+") + mesos + " mesos).");
            return Response.ok(rid, fillData(p, mesos, now));
        });
    }

    /**
     * Writes one desk fill in the batch: the player's money leg to or from the desk's HOUSE bucket, the NX leg the
     * other way, a fee when one is configured, the fill row (no order ids: the desk has no orders) and the desk's new
     * state, in one transaction.
     */
    private long commitFill(Place p, long now, long mesos, long fee, long liveAccount, long liveCharacter)
            throws StoreException {
        return commitFill(p, now, mesos, fee, liveAccount, liveCharacter,
                Bucket.wallet(Asset.MESO, p.character()), Bucket.wallet(Asset.NX, p.account()));
    }

    /**
     * The same with the buckets the trader's side of the trade lives in: a player's character wallet and account NX,
     * or a trader bot's bookkeeping meso bucket and NX purse ({@link #deskBot}).
     */
    private long commitFill(Place p, long now, long mesos, long fee, long liveAccount, long liveCharacter,
                            Bucket playerMeso, Bucket playerNx) throws StoreException {
        Side side = p.side();
        long nx = p.nx(), price = p.price();
        long fillId = nextFill;
        Bucket houseNx = deskBucket(Asset.NX), houseMeso = deskBucket(Asset.MESO);
        List<Posting> postings = new ArrayList<>(3);
        if (side == Side.BUY) {
            postings.add(new Posting(Why.FILL, playerMeso, houseMeso, mesos, p.account(), 0, fillId));
            if (fee > 0) {
                postings.add(new Posting(Why.FILL, playerMeso, Bucket.fees(), fee, p.account(), 0, fillId));
            }
            postings.add(new Posting(Why.FILL, houseNx, playerNx, nx, p.account(), 0, fillId));
        } else {
            postings.add(new Posting(Why.FILL, playerNx, houseNx, nx, p.account(), 0, fillId));
            postings.add(new Posting(Why.FILL, houseMeso, playerMeso, mesos - fee, p.account(), 0, fillId));
            if (fee > 0) {
                postings.add(new Posting(Why.FILL, houseMeso, Bucket.fees(), fee, p.account(), 0, fillId));
            }
        }
        FillRow f = new FillRow(fillId, now, 0, 0, nx, price, mesos, false, false, Ticker.NX);
        Desk.Fill recorded = new Desk.Fill(fillId, now, p.account(), side, nx, price, mesos);
        desk.recordFill(fillId, now, p.account(), side, nx, price, mesos);
        Batch batch = new Batch(nextBatch, now, postings, List.of(), List.of(f), liveAccount, liveCharacter)
                .withDesk(desk.state());
        try {
            commit(batch, "desk fill " + side + " " + nx + "NX@" + price);
        } catch (StoreException | RuntimeException e) {
            desk.undoFill(recorded);   // nothing moved: the hour's volume and the next step must not count it
            throw e;
        }
        committed = true;
        nextFill++;
        nextBatch++;
        remember(List.of(f));
        noteBotPayouts(postings);
        return fillId;
    }

    /** {@code {"fill":{nx,price,mesos,at}, "mid":R}} for every desk fill. */
    private Map<String, Object> fillData(Place p, long mesos, long now) {
        Map<String, Object> fill = new LinkedHashMap<>();
        fill.put("nx", p.nx());
        fill.put("price", p.price());
        fill.put("mesos", mesos);
        fill.put("at", iso(now));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fill", fill);
        data.put("mid", desk.mid());
        return data;
    }

    /** The site's {@code mine} for NX after the cutover: the account's NX, its last 20 desk fills, step-up amount. */
    public synchronized Response deskMine(String rid, long account) {
        LiveWallet w = live.find(account);
        long nx;
        try {
            nx = w != null ? w.have(Asset.NX) : store.walletBalance(Bucket.wallet(Asset.NX, account));
        } catch (StoreException e) {
            return storeError(rid, e);
        }
        List<Map<String, Object>> fills = new ArrayList<>();
        if (desk != null) {
            for (Desk.Fill f : desk.fillsOf(account, 20)) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("nx", f.nx());
                e.put("price", f.price());
                e.put("mesos", f.mesos());
                e.put("side", f.side().name());
                e.put("at", iso(f.at()));
                fills.add(e);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nx", nx);
        data.put("fills", fills);
        data.put("orders", List.of());   // the NX book is off: nothing rests
        data.put("stepUpFreeMesos", siteReauth.freeBuyMesos());
        return Response.ok(rid, data);
    }

    /**
     * Ticket 162's hourly step: the desk's drift from the hour's net flow against the NX float, capped by the step,
     * the day and the hard window, then stored. Nothing while the desk is off, not recovered or halted.
     *
     * <p>A failed store write is logged, not thrown: R stays advanced in memory and goes out with the next batch that
     * does commit (a fill, or the next hour's step), so the rate the desk quotes is never behind what it paid. A crash
     * in between loses that step only; the next boot starts from the stored R and the day's anchor.
     */
    public synchronized long tickDesk() {
        if (!recovered || desk == null || halted) {
            return 0;
        }
        long now = clock.getAsLong();
        if (now - desk.lastTickAt() < Desk.HOUR_MS) {
            return 0;
        }
        long floatNx;
        try {
            floatNx = store.nxFloat();
        } catch (StoreException e) {
            log.warn("NX exchange: desk step skipped, could not read the NX float", e);
            return 0;
        }
        long bps = desk.tick(now, floatNx);
        try {
            commit(new Batch(nextBatch, now, List.of(), List.of(), List.of()).withDesk(desk.state()),
                    "desk step " + bps + "bps R=" + desk.mid());
            nextBatch++;
        } catch (StoreException e) {
            log.error("NX exchange: desk step not stored (R is {} in memory)", desk.mid(), e);
        }
        return bps;
    }

    /**
     * Ticket 162 cutover: with the desk on the NX book is off, so every open NX order (a player's or a regular's) is
     * cancelled and its escrow refunded through the normal cancel path. Idempotent: a second run finds none and closes
     * nothing. Offline players' refunds land in their wallets on the next collect.
     */
    public synchronized Response cancelNxBook(String rid) {
        if (!recovered) {
            return Response.error(rid, "UNAVAILABLE", "exchange not started");
        }
        List<Long> ids = new ArrayList<>();
        for (OrderRow r : rows.values()) {
            if (Ticker.NX.equals(r.ticker())) {
                ids.add(r.id());
            }
        }
        ids.sort(Long::compare);
        int closed = 0;
        long nx = 0;
        List<Long> failed = new ArrayList<>();
        for (long id : ids) {
            OrderRow r = rows.get(id);
            if (r == null || !r.open()) {
                continue;
            }
            long owner = r.regular() > 0 ? Regulars.owner((int) r.regular()) : r.account();
            Response one = closeOrders("cancel-nx order #" + id, List.of(id), owner, OrderBook.Reason.CANCELLED);
            if (one.ok()) {
                closed++;
                nx += r.side() == Side.SELL ? r.nxLeft() : 0;
            } else {
                failed.add(id);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("closed", closed);
        data.put("nxReturned", nx);
        data.put("failed", failed);
        data.put("openNx", openNxOrders());
        audit("cancel-nx: closed " + closed + " NX orders, " + nx + " NX escrow returned"
                + (failed.isEmpty() ? "" : ", failed " + failed));
        return Response.ok(rid, data);
    }

    /** How many NX orders are still resting (everything while the desk is off; nothing after a cancel-nx). */
    public synchronized int openNxOrders() {
        int n = 0;
        for (OrderRow r : rows.values()) {
            if (Ticker.NX.equals(r.ticker()) && r.open()) {
                n++;
            }
        }
        return n;
    }



    /**
     * Does this site order need the password typed for it ({@link SiteReauth})? Every SELL (NX or items leave the
     * account), and a BUY once the account's password-free site buys of the last 12 h plus this one would pass
     * {@link SiteReauth#freeBuyMesos}.
     */
    private boolean passwordRequired(Place p, long now) {
        SiteReauth r = siteReauth;
        if (!r.stepUp()) {
            return false;
        }
        if (p.side() == Side.SELL) {
            return true;
        }
        long cost = costOf(p);
        return freeBuysUsed(p.account(), now) + cost > r.freeBuyMesos();
    }

    /** What one order costs the account: the NX desk's total (mesos plus its fee) while the desk holds NX, else the book's. */
    private long costOf(Place p) {
        if (desk != null && Ticker.NX.equals(p.ticker())) {
            return desk.total(p.side(), p.nx(), p.price());
        }
        Ticker t = tickers.get(p.ticker());
        return t == null ? Long.MAX_VALUE : t.buyTotal(config, p.nx(), p.price());
    }

    private long freeBuysUsed(long account, long now) {
        Deque<long[]> d = freeBuys.get(account);
        if (d == null) {
            return 0;
        }
        while (!d.isEmpty() && now - d.peekFirst()[0] >= SiteReauth.FREE_WINDOW_MS) {
            d.removeFirst();
        }
        long sum = 0;
        for (long[] e : d) {
            sum += e[1];
        }
        if (d.isEmpty()) {
            freeBuys.remove(account);
        }
        return sum;
    }

    /**
     * b005: the auth API checked the account's password for this preview token ({@code POST /exchange/approve}). The
     * token is marked approved and gets a fresh {@link ExchangeConfig#confirmMs()} to be confirmed in (typing the
     * password takes time). TOKEN_EXPIRED for an unknown, expired or other account's token.
     */
    public synchronized Response approve(long account, String token) {
        long now = clock.getAsLong();
        Pending x = token == null ? null : pending.get(token);
        if (x == null || x.expiresAt() < now || x.place().account() != account) {
            audit("approve REFUSED acct=" + account + " TOKEN_EXPIRED");
            return Response.error("approve", "TOKEN_EXPIRED", "that preview expired; preview the order again");
        }
        if (halted) {
            return Response.error("approve", "HALTED", "the exchange is stopped: " + haltReason);
        }
        long expires = Math.max(x.expiresAt(), now + config.confirmMs());
        pending.put(token, new Pending(x.place(), expires, x.site(), now));
        audit("approve OK acct=" + account + " char=" + x.place().character() + " " + x.place().side() + " "
                + x.place().ticker() + " " + x.place().nx() + "@" + x.place().price());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("expiresAt", iso(expires));
        return Response.ok("approve", data);
    }

    /**
     * Step 2 from the site: as {@link #confirm}, but a big or risky order ({@link #passwordRequired}) needs the
     * password approved for this token first (PASSWORD_REQUIRED, nothing moves; the token stays valid so the site can
     * ask for the password and confirm again).
     */
    public synchronized Response confirmSite(String rid, long account, String token) {
        if (halted) {
            return confirm(rid, account, token);
        }
        long now = clock.getAsLong();
        Pending x = token == null ? null : pending.get(token);
        if (x == null || x.expiresAt() < now || x.place().account() != account) {
            return confirm(rid, account, token);
        }
        boolean needs = passwordRequired(x.place(), now);
        if (needs && x.approvedAt() <= 0) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("token", token);
            d.put("expiresAt", iso(x.expiresAt()));
            return new Response(rid, false, "PASSWORD_REQUIRED", x.place().side() == Side.SELL
                    ? "type your password to confirm a sell from the website"
                    : "type your password to confirm this buy (big buys from the website ask for it)", d);
        }
        Response r = confirm(rid, account, token);
        if (r.ok() && !needs && x.place().side() == Side.BUY && siteReauth.stepUp()) {
            freeBuys.computeIfAbsent(account, k -> new java.util.ArrayDeque<>())
                    .addLast(new long[]{now, costOf(x.place())});
        }
        return r;
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
            return t.item() ? "price must be " + t.minPrice() + ".." + t.maxPrice() + " " + t.priceUnit()
                    : "price must be " + perNx(t.minPrice()) + ".." + perNx(t.maxPrice()) + " mesos per NX";
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

    /** Milliseconds until the account may place again (0 when it may). */
    long cooldownLeftMs(long account, long now) {
        try {
            long last = 0;
            for (OrderRow r : store.ordersOf(account, dayWindow())) {
                last = Math.max(last, r.placedAt());
            }
            return last > 0 ? Math.max(0, config.cooldownMs() - (now - last)) : 0;
        } catch (StoreException e) {
            return 0;
        }
    }

    private String limitMessage(String code, Ticker t, long account, long now) {
        return switch (code) {
            case "LIMIT_OPEN" -> "at most " + t.maxOpen() + " open " + t.id() + " orders";
            case "LIMIT_DAY" -> "at most " + t.perDay() + " " + t.id() + " orders a day";
            case "COOLDOWN" -> {
                long left = Math.max(1, (cooldownLeftMs(account, now) + 999) / 1000);
                yield "wait " + left + " s before your next order (" + config.cooldownMs() / 1000 + " s between orders)";
            }
            default -> "try again later";
        };
    }

    /** Places an order (player or regular) and settles what it matches, all in one batch. */
    private Response placeOrder(String rid, long account, long character, int regular, Ticker t, Side side, long price,
                                long nx, long now, LiveWallet w) {
        // the hard window on every order, including the house regulars' own quotes (they skip priceBand): a fill
        // trades at the resting order's price, so this is what keeps every fill inside too (owner 2026-10-31 ~19:50)
        Response outside = t == null ? null : outOfWindow(rid, t.id(), side, price);
        if (outside != null) {
            return outside;
        }
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
        // 1.6.2: a trader bot's NX is held for it in PAYOUT (it has no cash shop), so an NX sell spends the purse
        boolean purse = bot && sold == Asset.NX && side == Side.SELL;
        Bucket source = regular > 0 ? Bucket.house(side == Side.SELL ? sold : Asset.MESO, regular)
                : purse ? Bucket.payout(Asset.NX, account)
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
            commit(batch, "place");
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
            commit(new Batch(nextBatch, now, postings, touched, List.of()), "close " + why + " " + rid);
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
            commit(new Batch(nextBatch, now, postings, touched, List.of()), "expire");
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
                if (botAccount(account)) {
                    continue; // b260: a bot's purse is a PAYOUT bucket keyed by the bot's id and is never swept
                }
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

    /**
     * Pays an offline account's waiting payouts. A trader bot is never collected here (b260): its NX purse IS its
     * PAYOUT bucket (see {@link #botNx}), so the collect that pays a player (PAYOUT NX → the account's wallet row)
     * would take the purse out of the only row anything reads for a bot, stranding it; the bot's mesos and items come
     * in through {@link #collectBot} instead.
     */
    private boolean collectQuietly(long account) {
        if (halted || botAccount(account) || presence.test(account)) {
            return false;
        }
        try {
            long batchId = nextBatch;
            List<Posting> done = store.collect(batchId, clock.getAsLong(), account);
            nextBatch++;
            if (!done.isEmpty()) {
                audit(describe(new Batch(batchId, clock.getAsLong(), done, List.of(), List.of()),
                        "payout offline acct=" + account));
            }
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
            commit(new Batch(nextBatch, now, postings, List.of(), List.of(), w.account(), w.character()), "payout live");
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
                commit(new Batch(nextBatch, clock.getAsLong(), List.of(new Posting(Why.COLLECT, p.to(), p.from(),
                        p.amount(), w.account(), 0, 0)), List.of(), List.of(), w.account(), w.character()), "payout undo");
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
                commit(new Batch(nextBatch, clock.getAsLong(), List.of(new Posting(Why.COLLECT, pay,
                        Bucket.wallet(a, w.character()), n, w.account(), 0, 0)), List.of(), List.of(), w.account(),
                        w.character(), w.inventoryWriter()), "payout items");
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
            commit(new Batch(nextBatch, clock.getAsLong(),
                    List.of(new Posting(Why.FUND, from, Bucket.house(asset, regular), amount, account, 0, 0)),
                    List.of(), List.of()), "fund regular " + regular);
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
        return w.locked(() -> fundBucketLocked(Bucket.house(asset, regular), "fund regular " + regular, w, asset, amount));
    }

    /**
     * Funds the NX desk's HOUSE from the house account's live wallet ({@code !exchange desk fund nx|meso <amount>}):
     * the only way the desk gets NX to sell or mesos to buy with. Nothing is minted.
     */
    public synchronized Response fundDeskLive(LiveWallet w, Asset asset, long amount, boolean qa) {
        if (amount <= 0 || amount > Integer.MAX_VALUE || asset.item()) {
            return Response.error("fund", "BAD_REQUEST", "amount");
        }
        if (!qa && (config.houseAccount() <= 0 || w.account() != config.houseAccount())) {
            return Response.error("fund", "NOT_HOUSE", "only the house account ("
                    + (config.houseAccount() <= 0 ? "none set: -Dslums.exchange.houseAccount" : config.houseAccount())
                    + ") funds the NX desk");
        }
        return w.locked(() -> fundBucketLocked(deskBucket(asset), "fund the NX desk", w, asset, amount));
    }

    /**
     * Takes the NX desk's whole HOUSE (NX and mesos) back to the house account's live character
     * ({@code !exchange desk unfund}), which lands at once. The desk keeps trading at its R; with no NX left it just
     * cannot sell until someone funds it again.
     */
    public synchronized Response unfundDeskLive(LiveWallet w, boolean qa) {
        if (!qa && (config.houseAccount() <= 0 || w.account() != config.houseAccount())) {
            return Response.error("unfund", "NOT_HOUSE", "only the house account takes the NX desk's money back");
        }
        List<Posting> postings = new ArrayList<>();
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            for (Asset a : Asset.values()) {
                long have = store.balance(deskBucket(a));
                data.put(a.name().toLowerCase(java.util.Locale.ROOT), have);
                if (have > 0) {
                    postings.add(new Posting(Why.FUND, deskBucket(a), a.perCharacter()
                            ? Bucket.payout(a, w.character()) : Bucket.payout(a, w.account()), have, 0, 0, 0));
                }
            }
            if (!postings.isEmpty()) {
                commit(new Batch(nextBatch, clock.getAsLong(), postings, List.of(), List.of()), "unfund the NX desk");
                nextBatch++;
            }
        } catch (StoreException e) {
            return storeError("unfund", e);
        }
        collectLive(w);
        return Response.ok("unfund", data);
    }

    /** The shared tail of a live funding: check, save, take, FUND post, commit (a refused commit gives the take back). */
    private Response fundBucketLocked(Bucket to, String what, LiveWallet w, Asset asset, long amount) {
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
            commit(new Batch(nextBatch, clock.getAsLong(), List.of(new Posting(Why.FUND, from, to, amount, w.account(),
                    0, 0)), List.of(), List.of(), w.account(), w.character()), what);
        } catch (StoreException e) {
            giveBack(w, asset, amount, "a refused " + what);
            return storeError("fund", e);
        }
        nextBatch++;
        return Response.ok("fund", new LinkedHashMap<>());
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
                commit(new Batch(nextBatch, clock.getAsLong(), postings, List.of(), List.of()), "unfund regular " + regular);
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
            commit(new Batch(nextBatch, clock.getAsLong(), out, List.of(), List.of()), "reverse fill " + fillId + " by " + by);
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
     * account and character are both the bot's character id). Every player limit applies (5 open per ticker, the day's
     * count, the cooldown, the fee). Under the bot's lock: take the escrow out of the bot, save the bot (so a crash
     * from here on loses the escrow rather than duplicating it), then write the order; a refused order gives it back.
     * Nothing is minted: what a bot cannot pay it does not post.
     * <p>
     * 1.6.2 (owner: bots trade NX too, {@code -Dslums.exchange.traders.nx}): the NX ticker is open to bots as well.
     * Bots have no cash shop of their own, so their NX is held for them inside the exchange ({@link #botNx}: the NX
     * they bought from a player, never granted). An NX sell draws on that purse in the same transaction as the order,
     * so it cannot be sold twice; an NX buy pays mesos out of the bot as any other buy. Inside the NX band as well
     * ({@link #nxBand}), which {@link #place} enforces for every trader.
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
        if (desk != null && Ticker.NX.equals(p.ticker())) {
            return Response.error(rid, "BAD_REQUEST", deskBookOffMessage());
        }
        String bad = validate(p);
        if (bad != null) {
            return Response.error(rid, "BAD_REQUEST", bad);
        }
        Ticker t = tickers.get(p.ticker());
        boolean nx = Ticker.NX.equals(t.id());
        if (!t.item() && !(nx && MarketConfig.tradersNxOn())) {
            return Response.error(rid, "BAD_REQUEST", "trader bots trade item tickers only");
        }
        long now = clock.getAsLong();
        // the players' NX band applies to a bot too: an NX order nobody inside it could fill is a dead order
        Response guarded = priceBand(rid, p);
        if (guarded != null) {
            return guarded;
        }
        String limit = limits(p.account(), t, now);
        if (limit != null) {
            return Response.error(rid, limit, limitMessage(limit, t, p.account(), now));
        }
        Asset a = p.side() == Side.SELL ? t.asset() : Asset.MESO;
        long amount = p.side() == Side.SELL ? p.nx() : t.buyTotal(config, p.nx(), p.price());
        boolean purse = nx && p.side() == Side.SELL;   // the bot's NX sits in the ledger, not in a wallet
        return w.locked(() -> {
            if (!w.usable()) {
                return Response.error(rid, "TRY_AGAIN", "the bot is not in the game");
            }
            if (purse) {
                if (botNx(p.account()) < p.nx()) {
                    return Response.error(rid, "INSUFFICIENT_FUNDS",
                            "the bot has fewer than " + p.nx() + " " + unitName(a));
                }
            } else if (w.have(a) < amount || !w.take(a, amount)) {
                return Response.error(rid, "INSUFFICIENT_FUNDS", "the bot has fewer than " + amount + " " + unitName(a));
            } else if (!w.saveNow()) {
                giveBack(w, a, amount, "an unsaved bot place " + rid);
                return Response.error(rid, "ERROR", "could not save the bot first");
            }
            Response placed;
            committed = false;
            try {
                placed = placeOrder(rid, p.account(), p.character(), 0, t, p.side(), p.price(), p.nx(), now, null);
            } catch (RuntimeException e) {
                if (!committed) {
                    if (!purse) {
                        giveBack(w, a, amount, "a failed bot place " + rid);
                        w.saveNow();
                    }
                } else {
                    halt("bot place " + rid + " failed after its commit: " + e);
                }
                log.error("exchange: bot place {} failed", rid, e);
                return Response.error(rid, "ERROR", "try again later");
            }
            if (!placed.ok()) {
                if (!purse) {
                    giveBack(w, a, amount, "a refused bot place " + rid);
                    w.saveNow();
                }
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

    /**
     * 1.6.2: the NX the exchange holds for a trader bot (0: none, not a bot, or the store failed). A bot has no cash
     * shop account of its own, so NX it buys stays in its PAYOUT bucket until it sells it back (see {@link #placeBot});
     * nothing is ever granted to a bot's purse.
     */
    public synchronized long botNx(long account) {
        if (!recovered || !botAccount(account)) {
            return 0;
        }
        try {
            return Math.max(0, store.balance(Bucket.payout(Asset.NX, account)));
        } catch (StoreException e) {
            log.warn("exchange: could not read bot {}'s NX purse", account, e);
            return 0;
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
                commit(new Batch(nextBatch, clock.getAsLong(), List.of(out), List.of(), List.of()), "payout bot");
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
                commit(new Batch(nextBatch, clock.getAsLong(), List.of(new Posting(Why.COLLECT,
                        Bucket.bot(a, w.character()), pay, n, 0, 0, 0)), List.of(), List.of()), "payout bot undo");
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
        if (!recovered || halted || desk != null || config.regulars() <= 0 || config.houseAccount() <= 0) {
            return 0;   // owner 2026-10-28: no house account named = regulars idle; ticket 162: no NX book under the desk
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

    /**
     * A ticker's official rate: the desk's R for NX while the desk runs (ticket 162: it replaces the VWAP for NX), else
     * the VWAP of its player-to-player fills (see {@link OfficialRate}), or null.
     */
    public synchronized Long officialRate(String ticker) {
        if (desk != null && Ticker.NX.equals(ticker)) {
            return desk.mid();
        }
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
        pending.clear(); // b005/halt: a preview made before the halt never places after a resume without a new look
        latestToken.clear();
        audit("HALT " + haltReason);
    }

    /**
     * RESUME, and with the desk on (ticket 162 §4) the NX book is off, so resume also cancels every resting NX order
     * and refunds its escrow ({@link #cancelNxBook}, idempotent: it finds none on a second run). Offline players'
     * refunds reach their wallets on the next collect.
     */
    public synchronized Response resume() {
        halted = false;
        haltReason = "";
        audit("RESUME");
        if (desk != null) {
            return cancelNxBook("resume");
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("closed", 0);
        d.put("nxReturned", 0);
        d.put("failed", List.of());
        d.put("openNx", 0);
        return Response.ok("resume", d);
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
        if (desk != null) {
            out.add(deskLine());
        }
        for (String id : openTickers) {
            Ticker t = tickers.get(id);
            if (t.item()) {
                out.add(tickerLine(t));
            }
        }
        return out;
    }

    /** The desk's inventory as {NX, mesos} in its HOUSE buckets (zeros when the store cannot answer). */
    public synchronized long[] deskInventory() {
        try {
            return new long[]{store.balance(deskBucket(Asset.NX)), store.balance(deskBucket(Asset.MESO))};
        } catch (StoreException e) {
            log.warn("NX exchange: could not read the desk's inventory", e);
            return new long[]{0, 0};
        }
    }

    /** The desk's status line ({@code !exchange status}): open or closed, R, its quotes, its inventory, today's move. */
    public synchronized String deskLine() {
        if (desk == null) {
            return "NX desk: off (-Dslums.exchange.desk)";
        }
        long[] inv = deskInventory();
        String why = closeReason();
        return "NX desk: " + (why == null ? "open" : "CLOSED (" + why + ")") + ", R " + desk.mid() + " ("
                + perNx(desk.mid()) + " mesos per NX), buy " + desk.buy() + " / sell " + desk.sell() + "; desk "
                + inv[0] + " NX / " + inv[1] + " mesos; moved today " + String.format("%.2f", desk.movedTodayPct())
                + "%, last step " + desk.lastStepBps() + " bps";
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
