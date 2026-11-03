package soloMapling.Economy.exchange;

import soloMapling.Economy.exchange.AssetRegistry.Asset;
import soloMapling.Economy.exchange.OrderBook.Side;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ticket 113 round 2: the staging shared by {@code !qa run xc-1} and the auto-QA check t-xc-1. A test regular
 * ({@link #REGULAR}, above any configured regular) is funded from the tester's own staff wallet, nothing minted, and
 * bids for {@link #NX} NX at a price just above the book's best bid, so a sell at that price fills against it first.
 * {@link #teardown} cancels it and pays its whole house back to the tester.
 */
public final class ExchangeQa {

    /** The test regular's id (regulars 1..3 are the configured ones). */
    public static final int REGULAR = 9;
    public static final long NX = 100;
    /** A low price (mesos per 1,000 NX) so the check moves little: 100 NX = 10,000 mesos. */
    public static final long BASE_PRICE = 100_000;

    private ExchangeQa() {
    }

    /** What the staging set up. */
    public record Staged(long bidPrice, long bidOrder, long funded) {
    }

    /** Funds the test regular from {@code w} and rests its bid. Throws IllegalStateException with the reason. */
    public static Staged stage(Exchange ex, LiveWallet w) {
        if (deskOn(ex)) {
            throw new IllegalStateException("NX trades at the desk's quote now, so no test regular can bid on the NX "
                    + "book (t-xc-1 trades at the desk instead)");
        }
        if (ex.halted()) {
            throw new IllegalStateException("the exchange is halted: " + ex.haltReason());
        }
        if (!ex.openIdsOf(Regulars.owner(REGULAR)).isEmpty()) {
            teardown(ex, w); // a run that died half way
        }
        long bestBid = ex.depth(Side.BUY, 1).isEmpty() ? 0 : ex.depth(Side.BUY, 1).get(0).price();
        // 1.6.1 NX band: the tester's sell must ask at least the band's lowest sell price
        long price = Math.max(Math.max(BASE_PRICE, bestBid + 1_000), ex.nxBand()[0]);
        long need = ex.config().buyTotal(NX, price);
        Exchange.Response f = ex.fundHouseLive(REGULAR, w, Asset.MESO, need, true);
        if (!f.ok()) {
            throw new IllegalStateException("funding the test regular: " + f.code() + " " + f.message());
        }
        Exchange.Response bid = ex.placeRegularForQa(REGULAR, Side.BUY, price, NX);
        if (!bid.ok()) {
            teardown(ex, w);
            throw new IllegalStateException("the test regular's bid: " + bid.code() + " " + bid.message());
        }
        return new Staged(price, ((Number) bid.data().get("orderId")).longValue(), need);
    }

    /**
     * The price xc-1 / t-xc-1 rests its own NX buy at (b264): the effective band minimum the exchange accepts now,
     * {@link Exchange#nxBand()}{@code [0]}. It used to be the ticker's raw configuration minimum
     * ({@code ExchangeConfig.minPrice()}, 1,000 mesos per 1,000 NX), which is under the hard window
     * ({@link NxGuard#NX_LOWEST}..{@link NxGuard#NX_HIGHEST}, 2,500..10,000 per 1,000 NX, owner 2026-10-31 ~19:50 UTC),
     * so the place was refused with PRICE_BAND and the check failed at "the resting buy was not placed". The band
     * minimum is clamp-aware (it is never under {@link NxGuard#NX_LOWEST}) and still the cheapest price the book takes.
     */
    public static long restingBuyPrice(Exchange ex) {
        return ex.nxBand()[0];
    }

    /** Cancels the test regular and pays its house (NX and mesos) back to {@code w}. */
    public static Map<String, Object> teardown(Exchange ex, LiveWallet w) {
        Exchange.Response r = ex.unfundHouseLive(REGULAR, w, true);
        Map<String, Object> out = new LinkedHashMap<>(r.data());
        out.put("code", r.code());
        return out;
    }

    // ---------------------------------------------------------------- the NX desk (t-xc-1 / xc-1 with the desk on)

    /**
     * The desk check's size: one {@link DeskConfig#minQty()} of NX each way, so the round trip fits any desk that has
     * one trade's stock. 100 NX at the start mid is 500,000 mesos.
     */
    public static final long DESK_NX = 100;

    /** Whether NX trades at the desk (ticket 162 §4): then the NX book is off and only a preview+confirm moves NX. */
    public static boolean deskOn(Exchange ex) {
        return ex.desk() != null;
    }

    /** The wallet's NX and mesos and the desk's own inventory, as one moment of a desk round trip. */
    public record Snapshot(long nx, long meso, long deskNx, long deskMeso) {
        public static Snapshot of(Exchange ex, LiveWallet w) {
            long[] inv = ex.deskInventory();
            return new Snapshot(w.have(Asset.NX), w.have(Asset.MESO), inv[0], inv[1]);
        }
    }

    /**
     * What a BUY of {@code nx} NX at the desk and the SELL of the same NX straight back did: the two fills' own quotes
     * and mesos, their fees, and the wallet and desk either side. {@link #problems()} is the ledger check: nothing
     * minted, the same NX at both ends, and the spread (plus the desk's fees) the only thing the player paid.
     */
    public record DeskRound(long nx, long buyPrice, long buyMesos, long sellPrice, long sellMesos, long fees,
                            Snapshot before, Snapshot after) {

        /** The quote difference per 1,000 NX: what the round trip costs on that much NX, before fees. */
        public long spreadPaidPerMille() {
            return buyPrice - sellPrice;
        }

        /** Every way the round trip did not keep the books: empty when it did. */
        public List<String> problems() {
            List<String> bad = new ArrayList<>();
            if (before.nx() != after.nx()) {
                bad.add("NX " + before.nx() + " -> " + after.nx() + " (a BUY then a SELL of the same " + nx
                        + " NX must give it all back)");
            }
            if (before.deskNx() != after.deskNx()) {
                bad.add("the desk's NX " + before.deskNx() + " -> " + after.deskNx() + " (it must end where it started)");
            }
            long paid = before.meso() - after.meso();
            long wantPaid = buyMesos - sellMesos + fees;
            if (paid != wantPaid) {
                bad.add("the round trip cost " + paid + " mesos, want the spread and the fees: " + wantPaid);
            }
            long deskGained = after.deskMeso() - before.deskMeso();
            long wantGained = buyMesos - sellMesos;
            if (deskGained != wantGained) {
                bad.add("the desk gained " + deskGained + " mesos, want " + wantGained + " (nothing minted)");
            }
            if (paid - deskGained != fees) {
                bad.add((paid - deskGained) + " mesos left the player and the desk together, want only the fees "
                        + fees);
            }
            return bad;
        }
    }

    /**
     * The desk round trip as the two fills left it (newest first from {@link Desk#fillsOf}): a SELL of {@code nx} and,
     * before it, a BUY of {@code nx}. The fees are {@link DeskConfig#fee} of each fill's mesos. Throws
     * IllegalStateException with what was found when the desk did not record that pair.
     */
    public static DeskRound deskRound(Desk desk, long account, long nx, Snapshot before, Snapshot after) {
        List<Desk.Fill> f = desk.fillsOf(account, 2);
        if (f.size() < 2 || f.get(0).side() != Side.SELL || f.get(1).side() != Side.BUY
                || f.get(0).nx() != nx || f.get(1).nx() != nx) {
            throw new IllegalStateException("the desk recorded " + f + ", want a BUY then a SELL of " + nx + " NX");
        }
        long fees = desk.config().fee(f.get(1).mesos()) + desk.config().fee(f.get(0).mesos());
        return new DeskRound(nx, f.get(1).price(), f.get(1).mesos(), f.get(0).price(), f.get(0).mesos(), fees, before,
                after);
    }

    /**
     * t-xc-1 with the desk on: preview+confirm a BUY of {@code nx} NX at the desk's sell quote, then the SELL of the
     * same NX at its buy quote, and return what the two fills did. Throws IllegalStateException with the reason it
     * cannot run (a closed desk, the desk short of stock, a wallet that cannot pay). Nothing rests: the desk fills at
     * once, so there is no teardown.
     */
    public static DeskRound roundDesk(Exchange ex, LiveWallet w, long nx) {
        Desk desk = ex.desk();
        if (desk == null) {
            throw new IllegalStateException("the NX desk is off (-Dslums.exchange.desk=true)");
        }
        if (ex.halted()) {
            throw new IllegalStateException("the exchange is halted: " + ex.haltReason());
        }
        if (ex.closeReason() != null) {
            throw new IllegalStateException("the NX desk is closed: " + ex.closeReason());
        }
        DeskConfig dc = desk.config();
        long buyMesos, sellMesos;
        try {
            buyMesos = desk.mesos(Side.BUY, nx, desk.sell());
            sellMesos = desk.mesos(Side.SELL, nx, desk.buy());
        } catch (ArithmeticException e) {
            throw new IllegalStateException("that is more NX than one desk trade can carry");
        }
        Snapshot before = Snapshot.of(ex, w);
        if (before.deskNx() < nx) {
            throw new IllegalStateException("the desk has " + before.deskNx() + " NX to sell, need " + nx
                    + ": !exchange desk fund nx 50000");
        }
        if (before.deskMeso() + buyMesos < sellMesos) {
            throw new IllegalStateException("the desk has " + before.deskMeso() + " mesos to buy the " + nx
                    + " NX back, need " + sellMesos + ": !exchange desk fund meso 50000000");
        }
        long need = buyMesos + dc.fee(buyMesos);
        if (before.meso() < need) {
            throw new IllegalStateException("you need " + need + " mesos to buy " + nx + " NX at the desk's "
                    + desk.sell() + " per 1,000 (you have " + before.meso() + ")");
        }
        deskTrade(ex, w, Side.BUY, nx);
        deskTrade(ex, w, Side.SELL, nx);
        return deskRound(desk, w.account(), nx, before, Snapshot.of(ex, w));
    }

    /** One desk preview+confirm, retried once at the fresh quote when the desk stepped in between. */
    private static void deskTrade(Exchange ex, LiveWallet w, Side side, long nx) {
        Exchange.Response last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String rid = "qa-desk-" + n.getAndIncrement();
            Exchange.Response pv = ex.preview(new Exchange.Place(rid, w.account(), w.character(), side, 0, nx,
                    Ticker.NX));
            if (!pv.ok()) {
                last = pv;
                break;
            }
            last = ex.confirm(rid + "-c", w.account(), (String) pv.data().get("token"));
            if (last.ok() || !"QUOTE_MOVED".equals(last.code())) {
                break;
            }
        }
        if (last == null || !last.ok()) {
            throw new IllegalStateException("the desk's " + side + " of " + nx + " NX: "
                    + (last == null ? "no answer" : last.code() + " " + last.message()));
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong n = new java.util.concurrent.atomic.AtomicLong();

    // ---------------------------------------------------------------- item tickers (xc-2 / t-xc-2: LEAF)

    /** The item check's ticker and size: 10 Maple Leaves. */
    public static final String ITEM_TICKER = "LEAF";
    public static final long ITEMS = 10;
    /** A low price per leaf so the check moves little (10 leaves = 10,000 mesos); above the book's best bid. */
    public static final long ITEM_BASE_PRICE = 1_000;

    /** What the item staging set up. */
    public record StagedItem(String ticker, long bidPrice, long bidOrder, long funded) {
    }

    /**
     * Funds the test regular with {@code w}'s mesos (nothing minted) and rests its bid for {@code qty} of
     * {@code ticker} one meso above the book's best bid, so the tester's sell at that price fills against it first.
     * The regular only bids: the items it buys come from the tester and go back to the tester at {@link #teardown}.
     */
    public static StagedItem stageItem(Exchange ex, LiveWallet w, String ticker, long qty) {
        if (ex.halted()) {
            throw new IllegalStateException("the exchange is halted: " + ex.haltReason());
        }
        Ticker t = ex.ticker(ticker);
        if (t == null || !t.item() || !ex.open(t.id())) {
            throw new IllegalStateException("the " + ticker + " ticker is not open");
        }
        if (!ex.openIdsOf(Regulars.owner(REGULAR)).isEmpty()) {
            teardown(ex, w);
        }
        long bestBid = ex.depth(t.id(), Side.BUY, 1).isEmpty() ? 0 : ex.depth(t.id(), Side.BUY, 1).get(0).price();
        long price = Math.max(ITEM_BASE_PRICE, bestBid + 1);
        long need = t.buyTotal(ex.config(), qty, price);
        Exchange.Response f = ex.fundHouseLive(REGULAR, w, Asset.MESO, need, true);
        if (!f.ok()) {
            throw new IllegalStateException("funding the test regular: " + f.code() + " " + f.message());
        }
        Exchange.Response bid = ex.placeRegularForQa(REGULAR, t.id(), Side.BUY, price, qty);
        if (!bid.ok()) {
            teardown(ex, w);
            throw new IllegalStateException("the test regular's bid: " + bid.code() + " " + bid.message());
        }
        return new StagedItem(t.id(), price, ((Number) bid.data().get("orderId")).longValue(), need);
    }

    /**
     * The mesos {@link #stageItem} will take from the tester's wallet for {@code qty} of {@code ticker} right now (the
     * book's best bid moves with the bot traders, so this is read at staging time), or -1 when the ticker is unknown.
     */
    public static long itemFundingNeed(Exchange ex, String ticker, long qty) {
        Ticker t = ex.ticker(ticker);
        if (t == null || !t.item()) {
            return -1;
        }
        long bestBid = ex.depth(t.id(), Side.BUY, 1).isEmpty() ? 0 : ex.depth(t.id(), Side.BUY, 1).get(0).price();
        return t.buyTotal(ex.config(), qty, Math.max(ITEM_BASE_PRICE, bestBid + 1));
    }

    /** What a seller gets for {@code qty} of an item ticker at {@code price} each, after its fee. */
    public static long sellerGets(ExchangeConfig c, Ticker t, long qty, long price) {
        long m = t.cost(qty, price);
        return m - c.fee(m);
    }

    /** What the seller gets for {@code nx} NX at {@code price}, after its fee. */
    public static long sellerGets(ExchangeConfig c, long nx, long price) {
        long m = OrderBook.cost(nx, price);
        return m - c.fee(m);
    }
}
