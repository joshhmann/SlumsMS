package soloMapling.Economy.exchange;

import client.inventory.Item;
import constants.inventory.ItemConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Exchange.Place;
import soloMapling.Economy.exchange.Exchange.Response;
import soloMapling.Economy.exchange.Ledger.Asset;
import soloMapling.Economy.exchange.Ledger.Bucket;
import soloMapling.Economy.exchange.MemoryExchangeStore.Session;
import soloMapling.Economy.exchange.OrderBook.Side;
import soloMapling.server.Json;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 113, tickers (owner 2026-10-28): LEAF, CHAOS, WS and ELIXIR are player order books priced in mesos on the
 * same engine as NX. An item sell escrows the item out of the live character's bags, a cancel returns it, a fill
 * delivers it to the buyer's bags (held in PAYOUT while the buyer is offline or the bags are full), and only the 1%
 * fee of each side leaves. Items never move offline and nothing is minted.
 */
class ExchangeTickersTest {

    static final long MESO0 = 50_000_000;
    static final long LEAVES0 = 100;
    static final long P = 600_000; // mesos per leaf

    MemoryExchangeStore store;
    AtomicLong now;
    Exchange ex;
    long totalMeso, totalLeaf, totalNx;
    int req;

    @BeforeEach
    void setUp() throws Exception {
        store = new MemoryExchangeStore();
        for (long a = 1; a <= 3; a++) {
            store.addAccount(a, 100_000);
            store.addCharacter(10 + a, a, MESO0);
            store.addItems(10 + a, Asset.LEAF, LEAVES0);
        }
        now = new AtomicLong(1_800_000_000_000L);
        ex = start();
        totalMeso = store.totalMeso();
        totalLeaf = store.total(Asset.LEAF);
        totalNx = store.totalNx();
    }

    Exchange start() throws Exception {
        Exchange e = new Exchange(store, ExchangeLiveTest.cfg(100, 0), store::online, store.finder(), now::get);
        assertTrue(e.recover().isEmpty());
        return e;
    }

    Response place(long account, String ticker, Side side, long price, long qty) {
        return ex.place(new Place("r" + (++req), account, 10 + account, side, price, qty, ticker));
    }

    static long id(Response r) {
        return ((Number) r.data().get("orderId")).longValue();
    }

    void conserved() throws Exception {
        assertEquals(totalLeaf, store.total(Asset.LEAF), "leaves conserved");
        assertEquals(totalMeso, store.totalMeso(), "mesos conserved");
        assertEquals(totalNx, store.totalNx(), "NX untouched");
        assertTrue(Exchange.reconcile(store.load(10)).isEmpty(), "reconciles: " + Exchange.reconcile(store.load(10)));
        for (String t : List.of("NX", "LEAF", "CHAOS", "WS", "ELIXIR")) {
            ex.bookForTest(t).check();
        }
        for (var e : store.balances().entrySet()) {
            assertTrue(e.getValue() >= 0, e.getKey() + " negative");
        }
    }

    @Test
    void theTickersAreNxThenTheFourItemsWithTheirUnits() {
        List<Ticker> ts = ex.tickers();
        assertEquals(List.of("NX", "LEAF", "CHAOS", "WS", "ELIXIR"), ts.stream().map(Ticker::id).toList());
        assertEquals(4001126, ex.ticker("leaf").asset().itemId());
        assertEquals(2049100, ex.ticker("CHAOS").asset().itemId());
        assertEquals(2340000, ex.ticker("ws").asset().itemId());
        assertEquals(2000005, ex.ticker("elixir").asset().itemId());
        assertEquals("1000nx", ex.ticker("NX").unit());
        assertEquals("item", ex.ticker("LEAF").unit());
        assertEquals(1000, ex.ticker("NX").per());
        assertEquals(1, ex.ticker("LEAF").per());
        assertEquals(6_000_000, ex.ticker("LEAF").cost(10, P), "an item price is per one item");
    }

    @Test
    void aLiveSellEscrowsLeavesAnOfflineBuyFillsAndOnlyTheTwoFeesLeave() throws Exception {
        Session seller = store.login(1, 11);
        Response sell = place(1, "LEAF", Side.SELL, P, 10);
        assertTrue(sell.ok(), sell.toString());
        assertEquals("LEAF", sell.data().get("ticker"));
        assertEquals(90, seller.item(Asset.LEAF), "10 leaves left the bags");
        assertEquals(10, store.balance(Bucket.escrow(Asset.LEAF, id(sell))));
        assertEquals(90, store.items(11, Asset.LEAF), "the database's bags changed in the same write");
        conserved();
        // an offline buyer (account 2) pays mesos from the database; the fill is at once
        Response buy = place(2, "LEAF", Side.BUY, P, 10);
        assertTrue(buy.ok(), buy.toString());
        assertEquals("FILLED", buy.data().get("status"));
        assertEquals(MESO0 - 6_060_000, store.meso(12), "price plus the buyer's 1%");
        assertEquals(120_000, store.balance(Bucket.fees()), "1% from each side");
        // items never move offline: they wait in the buyer character's payout
        ex.collectAll();
        assertEquals(MESO0 + 6_000_000 - 60_000, seller.meso.get(11L), "the seller's mesos land live, less 1%");
        assertEquals(10, store.balance(Bucket.payout(Asset.LEAF, 12)));
        assertEquals(LEAVES0, store.items(12, Asset.LEAF));
        conserved();
        // the buyer logs in: the leaves land in the bags
        Session buyer = store.login(2, 12);
        ex.collectAll();
        assertEquals(LEAVES0 + 10, buyer.item(Asset.LEAF));
        assertEquals(LEAVES0 + 10, store.items(12, Asset.LEAF), "written with the inventory");
        assertEquals(0, store.balance(Bucket.payout(Asset.LEAF, 12)));
        conserved();
        // the NX book saw none of it
        assertTrue(ex.lastTrades(5).isEmpty());
        assertEquals(1, ex.lastTrades("LEAF", 5).size());
        assertTrue(Exchange.reconcile(store.load(10)).isEmpty());
        assertTrue(start().lastTrades("LEAF", 5).size() == 1, "recovery keeps the ticker of every fill");
    }

    @Test
    void aCancelReturnsTheEscrowedLeavesToTheBags() throws Exception {
        Session s = store.login(1, 11);
        Response sell = place(1, "LEAF", Side.SELL, 900_000, 25);
        assertTrue(sell.ok(), sell.toString());
        assertEquals(LEAVES0 - 25, s.item(Asset.LEAF));
        Response c = ex.cancel("c", 1, id(sell));
        assertTrue(c.ok(), c.toString());
        assertEquals(LEAVES0, s.item(Asset.LEAF), "straight back while online");
        assertEquals(LEAVES0, store.items(11, Asset.LEAF));
        assertEquals("CANCELLED", store.order(id(sell)).status());
        assertEquals("LEAF", store.order(id(sell)).ticker());
        conserved();
    }

    @Test
    void itemsAreSoldOnlyInGameAndOnlyTradeableCopiesCount() throws Exception {
        Response off = place(1, "LEAF", Side.SELL, P, 10);
        assertEquals("LOG_IN_FIRST", off.code(), off.toString());
        Response pv = ex.preview(new Place("p", 1, 11, Side.SELL, P, 10, "LEAF"));
        assertEquals("LOG_IN_FIRST", pv.code());
        assertEquals(LEAVES0, store.items(11, Asset.LEAF));
        Session s = store.login(1, 11);
        Response tooMany = place(1, "LEAF", Side.SELL, P, 101);
        assertEquals("INSUFFICIENT_FUNDS", tooMany.code(), tooMany.toString());
        assertEquals(LEAVES0, s.item(Asset.LEAF), "nothing taken");
        // a store write without the inventory is refused (the MySQL store needs it to write the bags)
        assertThrows(ExchangeStore.StoreException.class, () -> store.apply(new ExchangeStore.Batch(999, 1,
                List.of(new Ledger.Posting(Ledger.Why.PLACE, Bucket.wallet(Asset.LEAF, 11),
                        Bucket.escrow(Asset.LEAF, 999), 1, 1, 999, 0)), List.of(), List.of(), 1, 11)));
        conserved();
    }

    @Test
    void untradeableLockedOrExpiringCopiesAreNotTradeable() {
        assertTrue(GameLiveWallet.tradeable((short) 0, -1));
        assertTrue(GameLiveWallet.tradeable(ItemConstants.KARMA_USE, -1), "trade-once copies may still trade");
        assertFalse(GameLiveWallet.tradeable(ItemConstants.UNTRADEABLE, -1));
        assertFalse(GameLiveWallet.tradeable(ItemConstants.LOCK, -1));
        assertFalse(GameLiveWallet.tradeable(ItemConstants.MERGE_UNTRADEABLE, -1));
        assertFalse(GameLiveWallet.tradeable((short) 0, 1_900_000_000_000L), "an expiring copy");
        assertFalse(GameLiveWallet.tradeable((Item) null));
    }

    @Test
    void quantitiesPricesAndTickersAreChecked() throws Exception {
        store.login(1, 11);
        assertEquals("BAD_REQUEST", place(1, "LEAF", Side.SELL, P, 0).code());
        assertEquals("BAD_REQUEST", place(1, "LEAF", Side.BUY, P, 10_001).code(), "LEAF max 10,000");
        assertEquals("BAD_REQUEST", place(1, "CHAOS", Side.BUY, 1_000, 1_001).code(), "CHAOS max 1,000");
        assertEquals("BAD_REQUEST", place(1, "WS", Side.BUY, 1_000, 1_001).code(), "WS max 1,000");
        assertEquals("BAD_REQUEST", place(1, "ELIXIR", Side.BUY, 1, 10_001).code(), "ELIXIR max 10,000");
        assertEquals("BAD_REQUEST", place(1, "LEAF", Side.BUY, 1_000_000_001L, 1).code(), "price per item max 1b");
        assertEquals("BAD_REQUEST", place(1, "GOLD", Side.BUY, P, 1).code(), "unknown ticker");
        assertEquals("BAD_REQUEST", place(1, "LEAF", Side.BUY, 300_000_000, 10).code(), "more than a wallet holds");
        assertTrue(place(1, "ELIXIR", Side.BUY, 5_000, 7).ok(), "any whole quantity: no lots for items");
        // a closed ticker
        Exchange nxOnly = new Exchange(store, ExchangeLiveTest.cfg(100, 0), store::online, store.finder(), now::get,
                List.of(Ticker.nx(ExchangeLiveTest.cfg(100, 0))));
        assertTrue(nxOnly.recover().isEmpty(), "a closed ticker's open orders still load");
        assertFalse(nxOnly.open("LEAF"));
        assertEquals("BAD_REQUEST", nxOnly.place(new Place("z", 1, 11, Side.BUY, P, 1, "LEAF")).code());
        assertEquals(List.of("NX"), nxOnly.tickers().stream().map(Ticker::id).toList());
    }

    @Test
    void booksAreSeparateAndOpenOrderLimitsArePerTicker() throws Exception {
        Session s1 = store.login(1, 11);
        assertTrue(place(1, "LEAF", Side.SELL, 1_000, 10).ok());
        // an NX buy at a price that would cross never meets the leaf ask, and a CHAOS buy neither
        Response nx = place(2, "NX", Side.BUY, 1_000_000, 100);
        assertTrue(nx.ok());
        assertEquals("OPEN", nx.data().get("status"));
        Response chaos = place(2, "CHAOS", Side.BUY, 1_000_000, 1);
        assertEquals("OPEN", chaos.data().get("status"));
        assertEquals(3, ex.openOrders());
        assertEquals(1, ex.depth("LEAF", Side.SELL, 5).size());
        assertTrue(ex.depth(Side.SELL, 5).isEmpty(), "the NX book has no asks");
        // a ticker's open-order limit does not count another ticker's orders
        ExchangeConfig d = ExchangeConfig.defaults();
        ExchangeConfig two = new ExchangeConfig(true, d.lotNx(), d.minNx(), d.maxNx(), d.minPrice(), d.maxPrice(), 100,
                1, 100_000, 0, d.expiryMs(), 0, d.regularSpreadBps(), d.regularNx(), d.rateWindowMs(), 1,
                d.pollSeconds(), 0, 60_000);
        // each item ticker has its own limits now (owner 2026-10-28); here every ticker allows one open order
        List<Ticker> one = Ticker.defaults(two).stream().map(t -> t.withLimits(1, 100_000, t.expiryMs())).toList();
        Exchange lim = new Exchange(store, two, store::online, store.finder(), now::get, one);
        assertTrue(lim.recover().isEmpty());
        assertEquals("LIMIT_OPEN", lim.place(new Place("l1", 1, 11, Side.SELL, 1_000, 1, "LEAF")).code());
        assertEquals(0, s1.item(Asset.WS));
        assertEquals("INSUFFICIENT_FUNDS", lim.place(new Place("l2", 1, 11, Side.SELL, 50_000, 1, "WS")).code(),
                "no white scrolls to sell: refused for funds, not by the LEAF limit");
        assertTrue(lim.place(new Place("l3", 1, 11, Side.BUY, 50_000, 1, "WS")).ok(), "WS has its own limit");
        conserved();
    }

    /**
     * Owner 2026-10-28: the item tickers' limits went up: 10 open orders and 20 orders a day PER TICKER, orders live
     * 7 days; NX keeps its own (here the test config's). Fails before: 5 open and 20 a day across all tickers, 72 h.
     */
    @Test
    void itemTickersHaveTenOpenTwentyADayEachAndSevenDayOrders() throws Exception {
        ExchangeConfig d = ExchangeConfig.defaults();
        ExchangeConfig c = new ExchangeConfig(true, d.lotNx(), d.minNx(), d.maxNx(), d.minPrice(), d.maxPrice(), 100,
                d.maxOpen(), d.perDay(), 0, d.expiryMs(), 0, d.regularSpreadBps(), d.regularNx(), d.rateWindowMs(), 1,
                d.pollSeconds(), 0, 60_000);
        Exchange e = new Exchange(store, c, store::online, store.finder(), now::get, Ticker.defaults(c));
        assertTrue(e.recover().isEmpty());
        Ticker leaf = e.ticker("LEAF");
        assertEquals(10, leaf.maxOpen());
        assertEquals(20, leaf.perDay());
        assertEquals(168L * 3_600_000L, leaf.expiryMs());
        assertEquals(5, e.ticker("NX").maxOpen(), "NX unchanged");
        store.login(1, 11);
        int n = 0;
        for (int i = 0; i < 10; i++) {
            Response r = e.place(new Place("o" + (n++), 1, 11, Side.SELL, 2_000_000 + i, 1, "LEAF"));
            assertTrue(r.ok(), r.toString());
            if (i == 0) {
                long exp = java.time.Instant.parse((String) r.data().get("expiresAt")).toEpochMilli();
                assertEquals(now.get() + 168L * 3_600_000L, exp, "7 days");
            }
        }
        assertEquals("LIMIT_OPEN", e.place(new Place("o" + (n++), 1, 11, Side.SELL, 3_000_000, 1, "LEAF")).code());
        // cancel them all and place again: the day's count is 20 on LEAF
        for (long id : e.openIdsOf(1)) {
            assertTrue(e.cancel("c" + id, 1, id).ok());
        }
        for (int i = 0; i < 10; i++) {
            assertTrue(e.place(new Place("o" + (n++), 1, 11, Side.SELL, 2_000_000 + i, 1, "LEAF")).ok());
        }
        for (long id : e.openIdsOf(1)) {
            e.cancel("c" + id, 1, id);
        }
        assertEquals("LIMIT_DAY", e.place(new Place("o" + (n++), 1, 11, Side.SELL, 2_000_000, 1, "LEAF")).code(),
                "20 LEAF orders today");
        // ... but each ticker counts its own day: ELIXIR and NX still take orders
        assertTrue(e.place(new Place("o" + (n++), 1, 11, Side.BUY, 1_000, 1, "ELIXIR")).ok(), "per ticker, not all");
        assertTrue(e.place(new Place("o" + (n++), 1, 11, Side.BUY, 100_000, 100, "NX")).ok());
        now.addAndGet(86_400_001L);
        assertTrue(e.place(new Place("o" + (n++), 1, 11, Side.SELL, 2_000_000, 1, "LEAF")).ok(), "a new day");
    }

    @Test
    void theTickersExportCarriesTheLimits() {
        Map<String, Object> j = ExchangeSite.tickers(ex, now.get());
        assertEquals(10L, j.get("max_open_per_ticker"));
        assertEquals(20L, j.get("per_day"));
        assertEquals("ticker", j.get("per_day_scope"));
        assertEquals(0L, j.get("cooldown_sec"), "the test config has no cooldown");
        assertEquals(168L, j.get("expiry_hours"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ts = (List<Map<String, Object>>) j.get("tickers");
        Map<String, Object> nx = ts.get(0), leaf = ts.get(1);
        assertEquals("NX", nx.get("id"));
        assertEquals(1000, nx.get("max_open"), "NX keeps the exchange config's (the test's 1,000)");
        assertEquals(72L, nx.get("expiry_hours"));
        assertEquals(10, leaf.get("max_open"));
        assertEquals(20, leaf.get("per_day"));
        assertEquals(10_000L, leaf.get("max_qty"));
        // 1.6.1 volatility: the banner's announced events (none here) and each item ticker's fair value (null until
        // the volatility thread computed one)
        assertEquals(List.of(), j.get("events"));
        assertTrue(leaf.containsKey("fair"));
    }

    @Test
    void aFullBagHoldsThePayoutAndDeliversWhatFits() throws Exception {
        Session seller = store.login(1, 11);
        assertTrue(place(1, "LEAF", Side.SELL, P, 30).ok());
        Session buyer = store.login(2, 12);
        buyer.bagRoom = 0;
        Response buy = place(2, "LEAF", Side.BUY, P, 30);
        assertTrue(buy.ok(), buy.toString());
        assertEquals(LEAVES0, buyer.item(Asset.LEAF), "no room: nothing delivered");
        assertEquals(30, store.balance(Bucket.payout(Asset.LEAF, 12)), "held for pickup");
        buyer.bagRoom = 12;
        ex.collectAll();
        assertEquals(LEAVES0 + 12, buyer.item(Asset.LEAF), "what fits arrives");
        assertEquals(18, store.balance(Bucket.payout(Asset.LEAF, 12)));
        buyer.bagRoom = Long.MAX_VALUE;
        ex.collectAll();
        assertEquals(LEAVES0 + 30, buyer.item(Asset.LEAF));
        assertEquals(LEAVES0 - 30, seller.item(Asset.LEAF));
        conserved();
        store.logout(1);
        store.logout(2);
        assertEquals(totalLeaf, store.persistedTotal(Asset.LEAF), "the database alone holds every leaf");
    }

    @Test
    void aFailedWriteGivesTheLeavesBackAndAFailedDeliveryTakesThemBack() throws Exception {
        Session s = store.login(1, 11);
        store.crashAfterSteps(0);
        Response r = place(1, "LEAF", Side.SELL, P, 10);
        assertFalse(r.ok());
        assertEquals(LEAVES0, s.item(Asset.LEAF), "memory given back");
        assertEquals(LEAVES0, store.items(11, Asset.LEAF));
        assertEquals(0, ex.openOrders());
        conserved();
        assertTrue(place(1, "LEAF", Side.SELL, P, 10).ok());
        assertTrue(place(2, "LEAF", Side.BUY, P, 10).ok()); // offline: the leaves wait
        ex.collectAll(); // the seller's mesos land first
        Session b = store.login(2, 12);
        // the delivery's write fails: the leaves come back out of memory and keep waiting
        store.crashAfterSteps(0);
        ex.collectAll();
        assertEquals(LEAVES0, b.item(Asset.LEAF));
        assertEquals(10, store.balance(Bucket.payout(Asset.LEAF, 12)));
        assertFalse(ex.halted());
        conserved();
        ex.collectAll();
        assertEquals(LEAVES0 + 10, b.item(Asset.LEAF));
        conserved();
    }

    @Test
    void aStaffReversalPutsTheLeavesAndMesosBack() throws Exception {
        store.login(1, 11);
        assertTrue(place(1, "LEAF", Side.SELL, P, 10).ok());
        assertTrue(place(2, "LEAF", Side.BUY, P, 10).ok()); // offline buyer: its leaves wait in payout
        long fill = ex.lastTrades("LEAF", 1).get(0).id();
        ex.halt("test");
        Response rev = ex.reverse(fill, "it");
        assertTrue(rev.ok(), rev.toString());
        assertEquals(0, store.balance(Bucket.payout(Asset.LEAF, 12)));
        assertEquals(10, store.balance(Bucket.payout(Asset.LEAF, 11)), "the seller's leaves back to its payout");
        ex.resume();
        ex.collectAll();
        assertEquals(MESO0, store.meso(12), "the buyer's mesos and fee back");
        conserved();
    }

    @Test
    void regularsStayNxOnly() throws Exception {
        ExchangeConfig c = ExchangeLiveTest.cfg(100, 9);
        store.addAccount(9, 1_000_000);
        store.addCharacter(19, 9, 1_000_000_000);
        store.addItems(19, Asset.LEAF, 50);
        Exchange e = new Exchange(store, c, store::online, store.finder(), now::get);
        assertTrue(e.recover().isEmpty());
        assertEquals("BAD_REQUEST", e.fundHouse(1, 9, 19, Asset.LEAF, 10).code(), "never items into a house");
        Session house = store.login(9, 19);
        assertEquals("BAD_REQUEST", e.fundHouseLive(1, store.finder().find(9), Asset.LEAF, 10, false).code());
        assertEquals(50, house.item(Asset.LEAF));
        assertEquals("BAD_REQUEST", e.placeRegularForQa(9, "LEAF", Side.SELL, P, 1).code(),
                "a regular never sells items");
        // a funded regular quotes on NX only
        assertTrue(e.fundHouseLive(1, store.finder().find(9), Asset.MESO, 100_000_000, false).ok());
        assertTrue(e.fundHouseLive(1, store.finder().find(9), Asset.NX, 1_000, false).ok());
        store.logout(9);
        assertTrue(e.place(new Place("a", 1, 11, Side.SELL, 1_000_000, 100)).ok());
        now.addAndGet(20_000);
        assertTrue(e.place(new Place("b", 2, 12, Side.BUY, 1_200_000, 100)).ok());
        e.tickRegulars();
        assertTrue(e.depth("LEAF", Side.BUY, 5).isEmpty() && e.depth("LEAF", Side.SELL, 5).isEmpty());
    }

    @Test
    void theLeafQaStagingFundsABidFromTheTestersMesosAndGivesEverythingBack() throws Exception {
        Session s = store.login(1, 11);
        LiveWallet w = store.finder().find(1);
        ExchangeQa.StagedItem st = ExchangeQa.stageItem(ex, w, "LEAF", ExchangeQa.ITEMS);
        assertEquals(ExchangeQa.ITEM_BASE_PRICE, st.bidPrice());
        // the tester sells into it
        Response pv = ex.preview(new Place("q1", 1, 11, Side.SELL, st.bidPrice(), 10, "LEAF"));
        assertTrue(pv.ok(), pv.toString());
        Response placed = ex.confirmLatest("q1c", 1);
        assertTrue(placed.ok(), placed.toString());
        assertEquals(LEAVES0 - 10, s.item(Asset.LEAF));
        long gets = ExchangeQa.sellerGets(ex.config(), ex.ticker("LEAF"), 10, st.bidPrice());
        assertEquals(9_900, gets);
        Map<String, Object> td = ExchangeQa.teardown(ex, w);
        assertEquals("OK", td.get("code"));
        assertEquals(LEAVES0, s.item(Asset.LEAF), "the regular's leaves came back");
        assertEquals(MESO0 - 200, s.meso.get(11L), "exactly the two 1% fees");
        conserved();
    }

    // ---------------------------------------------------------------- history and the site's export

    @Test
    void historyKeepsHourlyCandlesAndTheRolling24hNumbers() {
        TickerHistory h = new TickerHistory();
        long t0 = 1_800_000_000_000L - 1_800_000_000_000L % TickerHistory.HOUR_MS;
        h.add(fill(1, t0 - TickerHistory.DAY_MS - 10, 500));          // before the window: the reference
        h.add(fill(2, t0 + 1_000, 520));
        h.add(fill(3, t0 + 2_000, 480));
        h.add(fill(4, t0 + TickerHistory.HOUR_MS + 5, 540));
        long now = t0 + TickerHistory.HOUR_MS + 10;
        TickerHistory.Stats s = h.stats(now);
        assertEquals(540L, s.last());
        assertEquals(40L, s.change(), "last minus the last price before the 24 h window");
        assertEquals(540L, s.high());
        assertEquals(480L, s.low());
        assertEquals(30, s.qty());
        assertEquals(3, s.fills());
        List<TickerHistory.Candle> cs = h.candles(now);
        assertEquals(3, cs.size());
        TickerHistory.Candle c = cs.get(1);
        assertEquals(t0, c.hour());
        assertEquals(520, c.open());
        assertEquals(520, c.high());
        assertEquals(480, c.low());
        assertEquals(480, c.close());
        assertEquals(20, c.qty());
        assertEquals(2, c.fills());
        // 30 days later the old candles are gone, the last price stays
        TickerHistory.Stats late = h.stats(now + TickerHistory.KEEP_MS + TickerHistory.DAY_MS);
        assertEquals(540L, late.last());
        assertEquals(0, late.qty());
        assertNull(late.high());
        assertTrue(h.candles(now + TickerHistory.KEEP_MS + TickerHistory.DAY_MS).isEmpty());
    }

    static ExchangeStore.FillRow fill(long id, long at, long price) {
        return new ExchangeStore.FillRow(id, at, 1, 2, 10, price, price * 10, false, false, "LEAF");
    }

    @Test
    void theSiteExportsEveryTickerWithItsUnitAndAnHourlyHistory() throws Exception {
        store.login(1, 11);
        assertTrue(place(1, "LEAF", Side.SELL, P, 10).ok());
        assertTrue(place(2, "LEAF", Side.BUY, P, 4).ok());
        assertTrue(place(3, "LEAF", Side.BUY, 550_000, 3).ok());
        long t = now.get();
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) Json.parse(Json.write(ExchangeSite.tickers(ex, t)));
        assertEquals(1L, ((Number) doc.get("schema")).longValue());
        assertEquals(100L, ((Number) doc.get("fee_bps_each_side")).longValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ts = (List<Map<String, Object>>) doc.get("tickers");
        assertEquals(List.of("NX", "LEAF", "CHAOS", "WS", "ELIXIR"), ts.stream().map(m -> m.get("id")).toList());
        Map<String, Object> nx = ts.get(0);
        assertEquals("1000nx", nx.get("unit"));
        assertEquals("mesos per 1,000 NX", nx.get("unit_label"));
        assertNull(nx.get("last"));
        Map<String, Object> leaf = ts.get(1);
        assertEquals("item", leaf.get("unit"));
        assertEquals("mesos per Maple Leaf", leaf.get("unit_label"));
        assertEquals(4001126L, ((Number) leaf.get("item_id")).longValue());
        assertEquals(P, ((Number) leaf.get("last")).longValue());
        assertEquals(550_000L, ((Number) leaf.get("bid")).longValue());
        assertEquals(P, ((Number) leaf.get("ask")).longValue());
        assertEquals(4L, ((Number) leaf.get("volume_24h")).longValue());
        assertEquals(2_400_000L, ((Number) leaf.get("turnover_24h")).longValue());
        assertEquals(0L, ((Number) leaf.get("change_24h")).longValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> asks = (List<Map<String, Object>>) leaf.get("asks");
        assertEquals(6L, ((Number) asks.get(0).get("qty")).longValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trades = (List<Map<String, Object>>) leaf.get("trades");
        assertEquals(4L, ((Number) trades.get(0).get("qty")).longValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> hist = (Map<String, Object>) Json.parse(Json.write(ExchangeSite.history(ex, t)));
        assertEquals("1h", hist.get("interval"));
        assertEquals(30L, ((Number) hist.get("days")).longValue());
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> per = (Map<String, Map<String, Object>>) hist.get("tickers");
        assertEquals("item", per.get("LEAF").get("unit"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candles = (List<Map<String, Object>>) per.get("LEAF").get("candles");
        assertEquals(1, candles.size());
        assertEquals(P, ((Number) candles.get(0).get("c")).longValue());
        assertEquals(4L, ((Number) candles.get(0).get("v")).longValue());
        assertTrue(((List<?>) per.get("NX").get("candles")).isEmpty());
        // the NX book file names its unit too
        assertEquals("1000nx", ExchangeSite.book(ex, t).get("unit"));
    }

    @Test
    void theSiteCanPreviewAndConfirmAnItemBuyByTicker() throws Exception {
        store.login(1, 11);
        assertTrue(place(1, "LEAF", Side.SELL, P, 10).ok());
        String created = java.time.Instant.ofEpochMilli(now.get() - 1000).toString();
        List<ExchangeSite.Request> rs = ExchangeSite.requests("[{\"id\":\"s1\",\"account\":2,\"type\":\"preview\","
                + "\"character\":12,\"side\":\"BUY\",\"price\":600000,\"qty\":5,\"ticker\":\"leaf\",\"createdAt\":\""
                + created + "\"},{\"id\":\"s2\",\"account\":2,\"type\":\"book\",\"ticker\":\"LEAF\",\"createdAt\":\""
                + created + "\"},{\"id\":\"s3\",\"account\":2,\"type\":\"book\",\"ticker\":\"le af\",\"createdAt\":\""
                + created + "\"}]");
        assertEquals("LEAF", rs.get(0).ticker());
        assertEquals(5, rs.get(0).nx(), "qty is another name for nx");
        assertEquals("ticker", rs.get(2).error());
        Response pv = ExchangeSite.answer(ex, rs.get(0), now.get());
        assertTrue(pv.ok(), pv.toString());
        assertEquals("LEAF", pv.data().get("ticker"));
        assertEquals("item", pv.data().get("unit"));
        @SuppressWarnings("unchecked")
        Map<String, Object> get = (Map<String, Object>) ((Map<String, Object>) pv.data().get("matchNow")).get("youGet");
        assertEquals("LEAF", get.get("asset"));
        Response conf = ex.confirm("s1c", 2, (String) pv.data().get("token"));
        assertTrue(conf.ok(), conf.toString());
        assertEquals(5, store.balance(Bucket.payout(Asset.LEAF, 12)), "bought offline: waits for the character");
        Response book = ExchangeSite.answer(ex, rs.get(1), now.get());
        assertEquals("LEAF", book.data().get("id"));
        conserved();
    }

    @Test
    void theGameCommandTradesATicker() throws Exception {
        store.login(1, 11);
        LiveWallet w = store.finder().find(1);
        List<String> pv = ExchangeChat.handle(ex, w, new String[]{"leaf", "sell", "10", "600k"});
        assertTrue(pv.get(0).startsWith("Preview: sell 10 Maple Leaf at 600,000 mesos each"), pv.toString());
        List<String> ok = ExchangeChat.handle(ex, w, new String[]{"confirm"});
        assertTrue(ok.get(0).startsWith("Order "), ok.toString());
        assertTrue(ok.get(0).contains("10 Maple Leaf"), ok.toString());
        assertTrue(ExchangeChat.handle(ex, w, new String[]{"orders"}).get(0).contains("Maple Leaf"));
        assertTrue(ExchangeChat.handle(ex, w, new String[]{"leaf"}).get(0).startsWith("Maple Leaf exchange"));
        assertTrue(ExchangeChat.handle(ex, w, new String[]{"leaf", "book"}).get(0).startsWith("LEAF book"));
        List<String> tickers = ExchangeChat.handle(ex, w, new String[]{"tickers"});
        assertEquals(6, tickers.size());
        assertTrue(tickers.get(1).startsWith("LEAF (Maple Leaf): bid none, ask 600,000"), tickers.toString());
        // NX stays as it was
        assertTrue(ExchangeChat.handle(ex, w, new String[]{}).get(0).startsWith("NX exchange: best bid"));
        assertTrue(ExchangeChat.handle(ex, w, new String[]{"nx", "book"}).get(0).startsWith("NX exchange book"));
        assertNotNull(ex.tickerLine(ex.ticker("LEAF")));
    }
}
