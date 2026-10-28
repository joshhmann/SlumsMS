package soloMapling.Economy.exchange;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Exchange.Place;
import soloMapling.Economy.exchange.Exchange.Response;
import soloMapling.Economy.exchange.ExchangeStore.FillRow;
import soloMapling.Economy.exchange.Ledger.Asset;
import soloMapling.Economy.exchange.Ledger.Bucket;
import soloMapling.Economy.exchange.OrderBook.Side;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 113: escrow, settlement, recovery, races and dupes, against the in-memory store (same rules as MySQL). */
class ExchangeTest {

    static final long P = 1_000_000; // 1m mesos per 1,000 NX
    static final long NX0 = 100_000;
    static final long MESO0 = 50_000_000;

    MemoryExchangeStore store;
    AtomicLong now;
    Exchange ex;
    long totalNx;
    long totalMeso;
    int req;

    static ExchangeConfig cfg(int feeBps, int maxOpen, int perDay, long cooldownMs, long expiryMs, int regulars) {
        ExchangeConfig d = ExchangeConfig.defaults();
        return new ExchangeConfig(true, d.lotNx(), d.minNx(), d.maxNx(), d.minPrice(), d.maxPrice(), feeBps, maxOpen,
                perDay, cooldownMs, expiryMs, regulars, d.regularSpreadBps(), d.regularNx(), d.rateWindowMs(), 1,
                d.pollSeconds());
    }

    static ExchangeConfig open() {
        return cfg(0, 1000, 100_000, 0, TimeUnit.HOURS.toMillis(72), 0);
    }

    @BeforeEach
    void setUp() throws Exception {
        store = new MemoryExchangeStore();
        for (long a = 1; a <= 4; a++) {
            store.addAccount(a, NX0);
            store.addCharacter(10 + a, a, MESO0);
        }
        store.addAccount(9, 1_000_000); // staff account that funds the house
        store.addCharacter(19, 9, 1_000_000_000);
        now = new AtomicLong(1_800_000_000_000L);
        ex = start(open());
        totalNx = store.totalNx();
        totalMeso = store.totalMeso();
    }

    Exchange start(ExchangeConfig c) throws Exception {
        Exchange e = new Exchange(store, c, store::online, now::get);
        assertTrue(e.recover().isEmpty());
        return e;
    }

    Response place(long account, Side side, long price, long nx) {
        return ex.place(new Place("r" + (++req), account, 10 + account, side, price, nx));
    }

    void conserved() throws Exception {
        assertEquals(totalNx, store.totalNx(), "NX conserved");
        assertEquals(totalMeso, store.totalMeso(), "mesos conserved");
        assertTrue(Exchange.reconcile(store.load(10)).isEmpty(), "ledger reconciles");
        ex.bookForTest().check();
        for (var e : store.balances().entrySet()) {
            assertTrue(e.getValue() >= 0, e.getKey() + " negative");
        }
    }

    long orderId(Response r) {
        return ((Number) r.data().get("orderId")).longValue();
    }

    @Test
    void placingASellEscrowsTheNx() throws Exception {
        Response r = place(1, Side.SELL, P, 1000);
        assertTrue(r.ok(), r.toString());
        assertEquals(NX0 - 1000, store.nx(1));
        assertEquals(1000, store.balance(Bucket.escrow(Asset.NX, orderId(r))));
        assertEquals("OPEN", r.data().get("status"));
        conserved();
    }

    @Test
    void placingABuyEscrowsTheMesosOfTheNamedCharacter() throws Exception {
        Response r = place(2, Side.BUY, 1_250_000, 2000);
        assertTrue(r.ok());
        assertEquals(MESO0 - 2_500_000, store.meso(12));
        assertEquals(2_500_000, store.balance(Bucket.escrow(Asset.MESO, orderId(r))));
        conserved();
    }

    @Test
    void aFillSettlesBothSidesIntoPayoutsThenWallets() throws Exception {
        long sell = orderId(place(1, Side.SELL, P, 1000));
        Response buy = place(2, Side.BUY, 1_200_000, 1000);
        assertEquals(1, ((Number) buy.data().get("fills")).intValue());
        assertEquals("FILLED", buy.data().get("status"));
        assertEquals("FILLED", store.order(sell).status());
        // the buyer paid the maker's price; the 200k difference came back
        assertEquals(1000, store.balance(Bucket.payout(Asset.NX, 2)));
        assertEquals(1_000_000, store.balance(Bucket.payout(Asset.MESO, 11)));
        assertEquals(200_000, store.balance(Bucket.payout(Asset.MESO, 12)));
        conserved();
        assertEquals(2, ex.collectAll());
        assertEquals(NX0 + 1000, store.nx(2));
        assertEquals(MESO0 - 1_000_000, store.meso(12));
        assertEquals(MESO0 + 1_000_000, store.meso(11));
        assertEquals(NX0 - 1000, store.nx(1));
        assertTrue(store.accountsWithPayout().isEmpty());
        conserved();
        assertEquals(1, store.fills().size());
    }

    @Test
    void partialFillsAcrossManyOrdersConserve() throws Exception {
        place(1, Side.SELL, 900_000, 300);
        place(3, Side.SELL, 950_000, 500);
        place(4, Side.SELL, 1_100_000, 1000);
        Response big = place(2, Side.BUY, 1_000_000, 1500);
        assertEquals(2, ((Number) big.data().get("fills")).intValue());
        assertEquals(800L, big.data().get("filledNx"));
        assertEquals("OPEN", big.data().get("status"));
        assertEquals(700, ((Number) big.data().get("nxLeft")).longValue());
        conserved();
        place(4, Side.SELL, 1_000_000, 1000); // takes the rest of the bid
        conserved();
        ex.collectAll();
        conserved();
    }

    @Test
    void aLoggedInAccountCannotPlaceAndNothingMoves() throws Exception {
        store.login(1);
        Response r = place(1, Side.SELL, P, 1000);
        assertEquals("LOG_OUT_FIRST", r.code());
        assertEquals(0, ex.openOrders());
        store.logout(1);
        conserved();
    }

    @Test
    void theStoreRefusesALoginThatRacedTheGameCheck() throws Exception {
        // the game said offline, but the account logged in before the transaction: the DB lock check refuses
        Exchange racy = new Exchange(store, open(), a -> false, now::get);
        racy.recover();
        store.login(1);
        Response r = racy.place(new Place("race", 1, 11, Side.SELL, P, 1000));
        assertEquals("LOG_OUT_FIRST", r.code());
        assertEquals(0, racy.openOrders(), "book rolled back");
        store.logout(1);
        assertEquals(NX0, store.nx(1));
    }

    @Test
    void insufficientFundsRollTheBookBack() throws Exception {
        place(1, Side.SELL, P, 1000);
        int before = ex.openOrders();
        Response r = place(2, Side.SELL, P, 50_000 * 3); // over maxNx -> bad request
        assertEquals("BAD_REQUEST", r.code());
        Response poor = ex.place(new Place("poor", 2, 12, Side.BUY, 100_000_000, 50_000)); // 5bn > int cap
        assertEquals("BAD_REQUEST", poor.code());
        Response tooMuch = ex.place(new Place("much", 2, 12, Side.BUY, 2_000_000, 40_000)); // 80m > 50m
        assertEquals("INSUFFICIENT_FUNDS", tooMuch.code());
        assertEquals(before, ex.openOrders());
        assertEquals(P, ex.bookForTest().best(Side.SELL), "the ask it would have hit is still there");
        conserved();
    }

    @Test
    void aCharacterOfAnotherAccountCannotPay() throws Exception {
        Response r = ex.place(new Place("x", 1, 12, Side.BUY, P, 1000));
        assertEquals("NOT_OWNER", r.code());
        assertEquals(MESO0, store.meso(12));
        conserved();
    }

    @Test
    void sizeAndPriceRules() {
        assertEquals("BAD_REQUEST", place(1, Side.SELL, P, 150).code(), "not a multiple of the lot");
        assertEquals("BAD_REQUEST", place(1, Side.SELL, P, 0).code());
        assertEquals("BAD_REQUEST", place(1, Side.SELL, 999, 1000).code(), "under min price");
        assertEquals("BAD_REQUEST", place(1, Side.SELL, 100_000_001, 1000).code());
        assertEquals("BAD_REQUEST", ex.place(new Place("", 1, 11, Side.SELL, P, 1000)).code());
        assertEquals("BAD_REQUEST", ex.place(new Place("q", 1, 11, null, P, 1000)).code());
        assertTrue(place(1, Side.SELL, P, 100).ok());
    }

    @Test
    void limitsOpenOrdersPerDayAndCooldown() throws Exception {
        ex = start(cfg(0, 2, 3, 10_000, 0, 0));
        assertTrue(place(1, Side.SELL, 2_000_000, 100).ok());
        assertEquals("COOLDOWN", place(1, Side.SELL, 2_000_000, 100).code());
        now.addAndGet(10_000);
        assertTrue(place(1, Side.SELL, 2_000_000, 100).ok());
        now.addAndGet(10_000);
        assertEquals("LIMIT_OPEN", place(1, Side.SELL, 2_000_000, 100).code());
        ex.cancel("c", 1, ex.openIdsOf(1).get(0));
        assertTrue(place(1, Side.SELL, 2_000_000, 100).ok());
        now.addAndGet(10_000);
        ex.cancel("c2", 1, ex.openIdsOf(1).get(0));
        assertEquals("LIMIT_DAY", place(1, Side.SELL, 2_000_000, 100).code());
        now.addAndGet(TimeUnit.DAYS.toMillis(1));
        assertTrue(place(1, Side.SELL, 2_000_000, 100).ok());
    }

    @Test
    void aRepeatedRequestIdPlacesOnce() throws Exception {
        Response a = ex.place(new Place("same", 1, 11, Side.SELL, P, 1000));
        Response b = ex.place(new Place("same", 1, 11, Side.SELL, P, 1000));
        assertTrue(a.ok() && b.ok());
        assertEquals(orderId(a), orderId(b));
        assertEquals(NX0 - 1000, store.nx(1));
        assertEquals("DUPLICATE", ex.place(new Place("same", 2, 12, Side.BUY, P, 1000)).code());
        // and after a restart
        ex = start(open());
        assertEquals(orderId(a), orderId(ex.place(new Place("same", 1, 11, Side.SELL, P, 1000))));
        assertEquals(1, ex.openOrders());
        conserved();
    }

    @Test
    void cancelReleasesOnceAndOnlyForTheOwner() throws Exception {
        long id = orderId(place(2, Side.BUY, 1_500_000, 1000));
        assertEquals("NOT_FOUND", ex.cancel("c0", 1, id).code(), "someone else's order");
        assertTrue(ex.cancel("c1", 2, id).ok());
        assertEquals("NOT_FOUND", ex.cancel("c2", 2, id).code(), "double cancel");
        assertEquals(1_500_000, store.balance(Bucket.payout(Asset.MESO, 12)));
        assertEquals("CANCELLED", store.order(id).status());
        conserved();
        ex.collectAll();
        assertEquals(MESO0, store.meso(12));
        conserved();
    }

    @Test
    void concurrentDoubleCancelReleasesOnce() throws Exception {
        long id = orderId(place(1, Side.SELL, P, 5000));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Response>> out = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            int n = i;
            out.add(pool.submit(() -> {
                go.await();
                return ex.cancel("c" + n, 1, id);
            }));
        }
        go.countDown();
        int ok = 0;
        for (Future<Response> f : out) {
            ok += f.get().ok() ? 1 : 0;
        }
        pool.shutdown();
        assertEquals(1, ok);
        assertEquals(5000, store.balance(Bucket.payout(Asset.NX, 1)));
        conserved();
    }

    @Test
    void expiryReleasesAndExpiredOrdersNeverFill() throws Exception {
        long id = orderId(place(1, Side.SELL, P, 1000));
        now.addAndGet(TimeUnit.HOURS.toMillis(72));
        // a buy arriving after expiry but before the sweep must not fill it
        Response buy = place(2, Side.BUY, P, 1000);
        assertEquals(0, ((Number) buy.data().get("fills")).intValue());
        assertEquals("EXPIRED", store.order(id).status());
        assertEquals(1000, store.balance(Bucket.payout(Asset.NX, 1)));
        now.addAndGet(TimeUnit.HOURS.toMillis(73));
        assertEquals(1, ex.expire());
        assertEquals(0, ex.openOrders());
        conserved();
    }

    @Test
    void haltStopsOrdersFillsExpiryAndPayoutsButNotCancels() throws Exception {
        long sell = orderId(place(1, Side.SELL, P, 1000));
        long buy = orderId(place(3, Side.BUY, 900_000, 1000));
        ex.halt("test");
        assertEquals("HALTED", place(2, Side.BUY, P, 1000).code());
        now.addAndGet(TimeUnit.DAYS.toMillis(10));
        assertEquals(0, ex.expire());
        assertEquals(0, ex.collectAll());
        assertTrue(ex.cancel("c", 1, sell).ok());
        assertEquals(1000, store.balance(Bucket.payout(Asset.NX, 1)), "still in payout while halted");
        ex.resume();
        assertEquals(1, ex.expire());
        assertEquals("EXPIRED", store.order(buy).status());
        assertEquals(2, ex.collectAll());
        assertEquals(NX0, store.nx(1));
        conserved();
    }

    /** A crash at any point inside a matching place leaves the store and the book exactly as before. */
    @Test
    void aCrashAtAnyStepChangesNothing() throws Exception {
        place(1, Side.SELL, 900_000, 300);
        place(3, Side.SELL, 950_000, 500);
        place(4, Side.BUY, 800_000, 1000);
        int ledger = store.ledger().size();
        List<OrderBook.Level> asks = ex.depth(Side.SELL, 10);
        List<OrderBook.Level> bids = ex.depth(Side.BUY, 10);
        int crashes = 0;
        Response ok;
        for (int stepAt = 0; ; stepAt++) {
            store.crashAfterSteps(stepAt);
            Response r = ex.place(new Place("crash" + stepAt, 2, 12, Side.BUY, 1_000_000, 1000));
            if (r.ok()) {
                ok = r;
                break;
            }
            crashes++;
            assertEquals("ERROR", r.code(), "step " + stepAt);
            assertEquals(ledger, store.ledger().size());
            assertEquals(asks, ex.depth(Side.SELL, 10));
            assertEquals(bids, ex.depth(Side.BUY, 10));
            assertEquals(MESO0, store.meso(12));
            conserved();
        }
        store.crashAfterSteps(-1);
        // 5 postings (escrow in, 2 per fill) + 3 order rows + 2 fill rows = 10 steps, a crash at each one
        assertEquals(10, crashes);
        assertEquals(2, ((Number) ok.data().get("fills")).intValue());
        conserved();
        // a restart between the commit and anything else rebuilds the same book
        Exchange again = start(open());
        assertEquals(ex.depth(Side.BUY, 10), again.depth(Side.BUY, 10));
        assertEquals(ex.depth(Side.SELL, 10), again.depth(Side.SELL, 10));
        assertEquals(ex.lastTrades(10), again.lastTrades(10));
    }

    @Test
    void aCrashDuringCancelOrCollectChangesNothing() throws Exception {
        long id = orderId(place(1, Side.SELL, P, 1000));
        store.crashAfterSteps(0);
        assertEquals("ERROR", ex.cancel("c", 1, id).code());
        assertNotNull(ex.bookForTest().get(id), "still open in the book");
        assertEquals("OPEN", store.order(id).status());
        assertTrue(ex.cancel("c", 1, id).ok());
        store.crashAfterSteps(0);
        assertEquals(0, ex.collectAll());
        assertEquals(1000, store.balance(Bucket.payout(Asset.NX, 1)));
        assertEquals(1, ex.collectAll());
        assertEquals(NX0, store.nx(1));
        conserved();
    }

    @Test
    void restartRecoversTheBookAndKeepsIdsUnique() throws Exception {
        place(1, Side.SELL, 1_100_000, 1000);
        place(2, Side.BUY, 900_000, 1000);
        place(3, Side.SELL, 1_000_000, 500);
        place(4, Side.BUY, 1_000_000, 200);
        Exchange again = start(open());
        assertEquals(ex.openOrders(), again.openOrders());
        assertEquals(ex.depth(Side.SELL, 10), again.depth(Side.SELL, 10));
        ex = again;
        assertTrue(place(4, Side.BUY, 1_100_000, 1300).ok());
        List<Long> fillIds = store.fills().stream().map(FillRow::id).toList();
        assertEquals(fillIds.size(), fillIds.stream().distinct().count());
        conserved();
    }

    @Test
    void aTamperedBalanceHaltsAtStart() throws Exception {
        long id = orderId(place(1, Side.SELL, P, 1000));
        store.tamper(Bucket.escrow(Asset.NX, id), 5000);
        Exchange again = new Exchange(store, open(), store::online, now::get);
        List<String> problems = again.recover();
        assertFalse(problems.isEmpty());
        assertTrue(again.halted());
        assertEquals("HALTED", again.place(new Place("z", 2, 12, Side.BUY, P, 1000)).code());
    }

    /**
     * The dupe the design exists to stop: escrowed mesos leave the character before the player can log in, so a
     * logged-in player spending "all" their mesos cannot spend the escrow, and the game's save-on-logout (absolute
     * values) cannot bring it back. Payouts wait until the player is offline again.
     */
    @Test
    void spendingWhileEscrowedCannotDupe() throws Exception {
        long buy = orderId(place(2, Side.BUY, P, 10_000)); // 10m mesos escrowed
        MemoryExchangeStore.Session s = store.login(2);
        assertEquals(MESO0 - 10_000_000, s.meso.get(12L), "the game loads the wallet without the escrow");
        assertFalse(s.spendMeso(12, MESO0), "cannot spend the escrowed part");
        assertTrue(s.spendMeso(12, MESO0 - 10_000_000)); // spends everything it really has (an NPC sink)
        long spent = MESO0 - 10_000_000;
        // a seller fills the buy while the buyer is logged in: settlement never needs the buyer offline
        assertTrue(place(1, Side.SELL, P, 10_000).ok());
        assertEquals("FILLED", store.order(buy).status());
        ex.collectAll(); // pays the offline seller, skips the logged-in buyer
        assertEquals(10_000, store.balance(Bucket.payout(Asset.NX, 2)));
        store.logout(2); // the game saves its (absolute) values
        assertEquals(0, store.meso(12));
        ex.collectAll();
        assertEquals(NX0 + 10_000, store.nx(2));
        assertEquals(0, store.meso(12), "no mesos came back from nowhere");
        assertEquals(totalMeso - spent, store.totalMeso());
        assertEquals(totalNx, store.totalNx());
    }

    @Test
    void feesGoToTheFeeBucketAndStillConserve() throws Exception {
        ex = start(cfg(100, 1000, 100_000, 0, 0, 0)); // 1% from each side (owner 2026-10-28)
        place(1, Side.SELL, P, 1000);
        place(2, Side.BUY, P, 1000);
        assertEquals(20_000, store.balance(Bucket.fees()), "1% from the seller and 1% from the buyer");
        assertEquals(990_000, store.balance(Bucket.payout(Asset.MESO, 11)));
        assertEquals(MESO0 - 1_010_000, store.meso(12), "the buyer paid the price plus its 1%");
        conserved();
    }

    @Test
    void walletCapLeavesTheRestInPayout() throws Exception {
        store.addCharacter(50, 4, 5000);
        long id = orderId(ex.place(new Place("cap", 4, 50, Side.BUY, 1000, 1000))); // holds 1,000 mesos
        store.addCharacter(50, 4, Integer.MAX_VALUE - 100); // earned in game meanwhile
        totalMeso = store.totalMeso();
        assertTrue(ex.cancel("c", 4, id).ok());
        ex.collectAll();
        assertEquals(Integer.MAX_VALUE, store.meso(50));
        assertEquals(900, store.balance(Bucket.payout(Asset.MESO, 50)));
        conserved();
    }

    @Test
    void regularsQuoteFromAFundedHouseAndNeverMoveTheRateAlone() throws Exception {
        ex = start(cfg(0, 1000, 100_000, 0, 0, 2));
        place(1, Side.SELL, 1_100_000, 1000);
        place(2, Side.BUY, 900_000, 1000);
        ex = start(cfg(0, 1000, 100_000, 0, 0, 3));
        assertTrue(ex.fundHouse(3, 9, 19, Asset.NX, 10_000).ok());
        assertTrue(ex.fundHouse(3, 9, 19, Asset.MESO, 100_000_000).ok());
        assertEquals(0, ex.tickRegulars(), "owner 2026-10-28: no house account named = regulars idle, even funded");
        ex.cancel("c1", 1, ex.openIdsOf(1).get(0));
        ex.cancel("c2", 2, ex.openIdsOf(2).get(0));
        ex = start(cfg(0, 1000, 100_000, 0, 0, 2).withHouse(9));
        totalNx = store.totalNx();
        totalMeso = store.totalMeso();
        assertEquals(0, ex.tickRegulars(), "no reference price yet: no quotes");
        place(1, Side.SELL, 1_100_000, 1000);
        place(2, Side.BUY, 900_000, 1000);
        assertEquals(0, ex.tickRegulars(), "unfunded house cannot quote");
        assertEquals("LOG_OUT_FIRST", fundWhileOnline());
        assertTrue(ex.fundHouse(1, 9, 19, Asset.NX, 10_000).ok());
        assertTrue(ex.fundHouse(1, 9, 19, Asset.MESO, 100_000_000).ok());
        assertTrue(ex.fundHouse(2, 9, 19, Asset.NX, 10_000).ok());
        assertTrue(ex.fundHouse(2, 9, 19, Asset.MESO, 100_000_000).ok());
        assertEquals(4, ex.tickRegulars());
        assertEquals(0, ex.tickRegulars(), "quotes already resting");
        assertEquals(1_000_000 + 30_000, ex.bookForTest().best(Side.SELL));
        assertEquals(1_000_000 - 30_000, ex.bookForTest().best(Side.BUY));
        conserved();
        // a player taking a regular's ask trades, but never sets the official rate (owner 2026-10-28)
        Exchange.Response cross = ex.place(new Place("p", 3, 13, Side.BUY, 1_030_000, 500));
        assertTrue(cross.ok());
        assertEquals(1, ((Number) cross.data().get("fills")).intValue());
        assertEquals(null, ex.officialRate(), "a player-vs-regular fill does not count");
        assertEquals(OfficialRate.vwap(List.of(new FillRow(1, now.get(), 1, 2, 500, 5_000_000, 0, true, true)),
                now.get(), 1000, 1), null);
        conserved();
    }

    private String fundWhileOnline() {
        store.login(9);
        try {
            return ex.fundHouse(1, 9, 19, Asset.NX, 1).code();
        } finally {
            store.logout(9);
        }
    }

    /** Many threads placing, cancelling, expiring and collecting at once: everything still adds up. */
    @Test
    void concurrentTradingConserves() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        AtomicBoolean failed = new AtomicBoolean();
        AtomicInteger fills = new AtomicInteger();
        List<Future<?>> jobs = new ArrayList<>();
        for (int t = 0; t < 6; t++) {
            long seed = t;
            jobs.add(pool.submit(() -> {
                Random rnd = new Random(seed);
                for (int i = 0; i < 300; i++) {
                    long acc = 1 + rnd.nextInt(4);
                    int op = rnd.nextInt(10);
                    if (op < 6) {
                        Response r = ex.place(new Place("t" + seed + "-" + i, acc, 10 + acc,
                                rnd.nextBoolean() ? Side.BUY : Side.SELL, 900_000 + rnd.nextInt(200) * 1000L,
                                100L * (1 + rnd.nextInt(20))));
                        if (r.ok()) {
                            fills.addAndGet(((Number) r.data().get("fills")).intValue());
                        }
                    } else if (op < 8) {
                        List<Long> mine = ex.openIdsOf(acc);
                        if (!mine.isEmpty()) {
                            ex.cancel("x" + seed + "-" + i, acc, mine.get(0));
                        }
                    } else if (op == 8) {
                        ex.collectAll();
                    } else {
                        now.addAndGet(1000);
                        ex.expire();
                    }
                }
            }));
        }
        for (Future<?> f : jobs) {
            try {
                f.get(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                failed.set(true);
                e.printStackTrace();
            }
        }
        pool.shutdown();
        assertFalse(failed.get());
        assertTrue(fills.get() > 0, "some trades happened");
        conserved();
    }
}
