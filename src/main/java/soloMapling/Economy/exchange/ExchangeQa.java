package soloMapling.Economy.exchange;

import soloMapling.Economy.exchange.Ledger.Asset;
import soloMapling.Economy.exchange.OrderBook.Side;

import java.util.LinkedHashMap;
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

    /** Cancels the test regular and pays its house (NX and mesos) back to {@code w}. */
    public static Map<String, Object> teardown(Exchange ex, LiveWallet w) {
        Exchange.Response r = ex.unfundHouseLive(REGULAR, w, true);
        Map<String, Object> out = new LinkedHashMap<>(r.data());
        out.put("code", r.code());
        return out;
    }

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
