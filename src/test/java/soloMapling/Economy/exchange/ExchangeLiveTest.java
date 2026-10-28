package soloMapling.Economy.exchange;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Exchange.Place;
import soloMapling.Economy.exchange.Exchange.Response;
import soloMapling.Economy.exchange.Ledger.Asset;
import soloMapling.Economy.exchange.Ledger.Bucket;
import soloMapling.Economy.exchange.MemoryExchangeStore.Session;
import soloMapling.Economy.exchange.OrderBook.Side;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 113 round 2 (owner 2026-10-28): placing while logged in, payouts straight into a live character, the confirm
 * step, both-sides fees, staff reversal and the house-funded regulars. Money rules: total NX and mesos (live view and
 * database view) the same before and after, never negative, no dupe, in every scenario the owner listed: save after
 * escrow, a crash between the memory change and the commit, a channel change, a logout mid-place, and the site and the
 * game placing at once.
 */
class ExchangeLiveTest {

    static final long P = 1_000_000;
    static final long NX0 = 100_000;
    static final long MESO0 = 50_000_000;

    MemoryExchangeStore store;
    AtomicLong now;
    Exchange ex;
    long totalNx;
    long totalMeso;
    int req;

    static ExchangeConfig cfg(int feeBps, long house) {
        ExchangeConfig d = ExchangeConfig.defaults();
        return new ExchangeConfig(true, d.lotNx(), d.minNx(), d.maxNx(), d.minPrice(), d.maxPrice(), feeBps, 1000,
                100_000, 0, TimeUnit.HOURS.toMillis(72), 3, d.regularSpreadBps(), d.regularNx(), d.rateWindowMs(), 1,
                d.pollSeconds(), house, 60_000);
    }

    @BeforeEach
    void setUp() throws Exception {
        store = new MemoryExchangeStore();
        for (long a = 1; a <= 4; a++) {
            store.addAccount(a, NX0);
            store.addCharacter(10 + a, a, MESO0);
        }
        store.addCharacter(22, 2, 1_000); // account 2's second character
        store.addAccount(9, 1_000_000);   // the house (staff) account
        store.addCharacter(19, 9, 1_000_000_000);
        now = new AtomicLong(1_800_000_000_000L);
        ex = start(cfg(100, 0));
        totalNx = store.totalNx();
        totalMeso = store.totalMeso();
    }

    Exchange start(ExchangeConfig c) throws Exception {
        Exchange e = new Exchange(store, c, store::online, store.finder(), now::get);
        assertTrue(e.recover().isEmpty());
        return e;
    }

    Response place(long account, Side side, long price, long nx) {
        return ex.place(new Place("r" + (++req), account, 10 + account, side, price, nx));
    }

    static long id(Response r) {
        return ((Number) r.data().get("orderId")).longValue();
    }

    /** Live totals and database totals both unchanged; ledger reconciles; nothing negative. */
    void conserved() throws Exception {
        assertEquals(totalNx, store.totalNx(), "NX conserved (live view)");
        assertEquals(totalMeso, store.totalMeso(), "mesos conserved (live view)");
        assertTrue(Exchange.reconcile(store.load(10)).isEmpty(), "ledger reconciles: " + Exchange.reconcile(store.load(10)));
        ex.bookForTest().check();
        for (var e : store.balances().entrySet()) {
            assertTrue(e.getValue() >= 0, e.getKey() + " negative");
        }
        for (long a = 1; a <= 4; a++) {
            assertTrue(store.nx(a) >= 0);
            assertTrue(store.meso(10 + a) >= 0);
        }
    }

    /** With nobody logged in, the database alone must hold every NX and meso. */
    void persistedConserved() {
        assertEquals(totalNx, store.persistedTotal(Asset.NX), "NX conserved (database)");
        assertEquals(totalMeso, store.persistedTotal(Asset.MESO), "mesos conserved (database)");
    }

    // ---------------------------------------------------------------- placing in game

    @Test
    void aBuyPlacedInGameTakesTheEscrowAndFeeReserveFromTheLiveCharacter() throws Exception {
        Session s = store.login(2, 12);
        s.gainMeso(12, 5_000); // looted since the last save: memory has more than the database
        totalMeso = store.totalMeso();
        Response r = place(2, Side.BUY, 1_250_000, 2000);
        assertTrue(r.ok(), r.toString());
        long hold = 2_500_000, reserve = 25_000;
        assertEquals(MESO0 + 5_000 - hold - reserve, s.meso.get(12L), "memory paid the hold and the fee reserve");
        assertEquals(MESO0 + 5_000 - hold - reserve, store.meso(12), "the database matches memory (save + delta)");
        assertEquals(hold, store.balance(Bucket.escrow(Asset.MESO, id(r))));
        assertEquals(reserve, store.balance(Bucket.feeHold(id(r))));
        assertEquals(1, store.saves, "the character was saved first");
        conserved();
    }

    @Test
    void aSellPlacedInGameTakesTheAccountsLiveNx() throws Exception {
        Session s = store.login(1, 11);
        Response r = place(1, Side.SELL, P, 3000);
        assertTrue(r.ok(), r.toString());
        assertEquals(NX0 - 3000, s.nx);
        assertEquals(NX0 - 3000, store.nx(1));
        conserved();
    }

    @Test
    void notEnoughInMemoryRefusesAndTakesNothing() throws Exception {
        Session s = store.login(2, 12);
        assertTrue(s.spendMeso(12, MESO0 - 100_000));
        Response r = place(2, Side.BUY, P, 1000); // needs 1,010,000
        assertEquals("INSUFFICIENT_FUNDS", r.code());
        assertEquals(100_000, s.meso.get(12L));
        assertEquals(0, ex.openOrders());
        store.logout(2);
        totalMeso -= MESO0 - 100_000;
        conserved();
        persistedConserved();
    }

    @Test
    void anotherCharacterOrTheCashShopRefuse() throws Exception {
        store.login(2, 22);
        assertEquals("LOG_OUT_FIRST", place(2, Side.BUY, P, 100).code(), "logged in as another character");
        store.logout(2);
        Session s = store.login(2, 12);
        s.inCashShop = true;
        assertEquals("TRY_AGAIN", place(2, Side.BUY, P, 100).code());
        s.inCashShop = false;
        assertTrue(place(2, Side.BUY, P, 100).ok());
        conserved();
    }

    // ---------------------------------------------------------------- payouts online

    @Test
    void fillsCancelsAndExpiryPayAnOnlinePlayerStraightIntoMemory() throws Exception {
        Session buyer = store.login(2, 12);
        long buy = id(place(2, Side.BUY, P, 2000));
        assertTrue(place(1, Side.SELL, P, 1000).ok()); // offline seller fills half
        ex.collectAll();
        assertEquals(NX0 + 1000, buyer.nx, "the NX landed in the live account without a logout");
        assertEquals(NX0 + 1000, store.nx(2), "and in the database, in the same transaction");
        assertEquals(0, store.balance(Bucket.payout(Asset.NX, 2)));
        assertEquals(MESO0 + 990_000, store.meso(11), "the offline seller got its mesos less its 1% fee");
        long before = buyer.meso.get(12L);
        assertTrue(ex.cancel("c", 2, buy).ok());
        assertEquals(before + 1_000_000 + 10_000, buyer.meso.get(12L), "the cancel refunded the rest and its reserve");
        assertEquals(MESO0 - 1_010_000, buyer.meso.get(12L));
        assertEquals(20_000, store.balance(Bucket.fees()));
        conserved();
        store.logout(2);
        persistedConserved();
    }

    @Test
    void aGiveRefusedAfterTheCommitIsPutBackInPayout() throws Exception {
        Session s = store.login(2, 12);
        assertTrue(place(2, Side.BUY, P, 1000).ok());
        assertTrue(place(1, Side.SELL, P, 1000).ok());
        store.refuseGives(1);
        ex.collectAll(); // the DB commit landed, memory refused: a second transaction puts it back
        assertEquals(NX0, s.nx);
        assertEquals(1000, store.balance(Bucket.payout(Asset.NX, 2)));
        assertFalse(ex.halted());
        conserved();
        ex.collectAll();
        assertEquals(NX0 + 1000, s.nx);
        conserved();
    }

    @Test
    void theWalletCapOnlineLeavesTheRestInPayout() throws Exception {
        Session s = store.login(2, 12);
        long buy = id(place(2, Side.BUY, P, 1000));
        s.gainMeso(12, Integer.MAX_VALUE - s.meso.get(12L) - 500);
        totalMeso = store.totalMeso();
        assertTrue(ex.cancel("c", 2, buy).ok());
        assertEquals(Integer.MAX_VALUE, (long) s.meso.get(12L));
        assertEquals(1_010_000 - 500, store.balance(Bucket.payout(Asset.MESO, 12)));
        conserved();
    }

    // ---------------------------------------------------------------- the owner's dupe list

    /** Save after escrow: spending everything and the logout's absolute save cannot bring the escrow back. */
    @Test
    void saveAfterEscrowCannotDupe() throws Exception {
        Session s = store.login(2, 12);
        long buy = id(place(2, Side.BUY, P, 10_000)); // 10m + 100k reserve
        long left = MESO0 - 10_100_000;
        assertEquals(left, s.meso.get(12L));
        assertFalse(s.spendMeso(12, left + 1), "the escrow is not spendable");
        assertTrue(s.spendMeso(12, left));
        store.logout(2); // absolute save
        assertEquals(0, store.meso(12));
        totalMeso -= left;
        assertTrue(ex.cancel("c", 2, buy).ok());
        ex.collectAll();
        assertEquals(10_100_000, store.meso(12), "exactly the escrow came back, once");
        ex.collectAll();
        assertEquals(10_100_000, store.meso(12));
        conserved();
        persistedConserved();
    }

    /** A crash at any step of the transaction: memory is given back, and the database alone is consistent too. */
    @Test
    void aCrashBetweenTheMemoryChangeAndTheCommitLeavesBothConsistent() throws Exception {
        for (int step = 0; step < 12; step++) {
            setUp();
            assertTrue(place(1, Side.SELL, P, 500).ok());
            assertTrue(place(3, Side.SELL, 1_100_000, 500).ok());
            Session s = store.login(2, 12);
            long mem = s.meso.get(12L);
            store.crashAfterSteps(step);
            Response r = place(2, Side.BUY, 1_200_000, 1000); // two fills, a release, fees: many steps
            if (r.ok()) {
                continue; // past the last step: nothing crashed
            }
            assertEquals("ERROR", r.code(), "step " + step);
            assertEquals(mem, s.meso.get(12L), "memory given back at step " + step);
            store.crash(2); // the server dies right after: memory is gone, only the database is left
            persistedConserved();
            Exchange again = start(cfg(100, 0));
            assertEquals(2, again.openOrders());
        }
    }

    /** A crash after the commit (no save afterwards): the database has the delta and the escrow. */
    @Test
    void aCrashAfterTheCommitLosesNothing() throws Exception {
        Session s = store.login(2, 12);
        long buy = id(place(2, Side.BUY, P, 1000));
        s.gainMeso(12, 777); // unsaved progress, lost by the crash like any crash
        store.crash(2);
        persistedConserved();
        ex = start(cfg(100, 0));
        assertEquals(1, ex.openOrders());
        assertTrue(ex.cancel("c", 2, buy).ok());
        ex.collectAll();
        assertEquals(MESO0, store.meso(12));
        conserved();
    }

    /** A channel change that is already in flight refuses; one right after carries the change to the new channel. */
    @Test
    void channelChangeNeverDupes() throws Exception {
        Session s = store.login(2, 12);
        store.beginTransition(2);
        assertEquals("TRY_AGAIN", place(2, Side.BUY, P, 1000).code());
        assertEquals(MESO0, s.meso.get(12L));
        // the race: the wallet looked usable, then the channel change flipped loggedin before the transaction
        Session s2 = store.login(2, 12);
        Exchange racy = new Exchange(store, cfg(100, 0), store::online, a -> flipsBeforeWrite(store.walletOf(s2)),
                now::get);
        racy.recover();
        assertEquals("TRY_AGAIN", racy.place(new Place("race", 2, 12, Side.BUY, P, 1000)).code());
        assertEquals(MESO0, s2.meso.get(12L), "memory given back");
        Session s3 = store.changeChannel(2);
        assertEquals(MESO0, s3.meso.get(12L));
        conserved();
        // placed, then a channel change: the new channel loads the wallet without the escrow
        ex = start(cfg(100, 0));
        long buy = id(place(2, Side.BUY, P, 1000));
        Session s4 = store.changeChannel(2);
        assertEquals(MESO0 - 1_010_000, s4.meso.get(12L));
        assertTrue(ex.cancel("c", 2, buy).ok());
        assertEquals(MESO0, s4.meso.get(12L), "the refund landed on the new channel");
        conserved();
    }

    private LiveWallet flipsBeforeWrite(LiveWallet w) {
        return new LiveWallet() {
            public long account() {
                return w.account();
            }

            public long character() {
                return w.character();
            }

            public <T> T locked(Supplier<T> body) {
                return w.locked(body);
            }

            public boolean usable() {
                return w.usable();
            }

            public boolean saveNow() {
                boolean ok = w.saveNow();
                store.beginTransition(w.account());
                return ok;
            }

            public long have(Asset a) {
                return w.have(a);
            }

            public boolean take(Asset a, long amount) {
                return w.take(a, amount);
            }

            public boolean give(Asset a, long amount) {
                return w.give(a, amount);
            }
        };
    }

    /** A logout during a place waits for the character's lock, then saves memory with the change in it. */
    @Test
    void logoutMidPlaceWaitsAndSavesTheChange() throws Exception {
        Session s = store.login(2, 12);
        CountDownLatch inside = new CountDownLatch(1), go = new CountDownLatch(1);
        LiveWallet slow = new LiveWallet() {
            final LiveWallet w = store.walletOf(s);

            public long account() {
                return w.account();
            }

            public long character() {
                return w.character();
            }

            public <T> T locked(Supplier<T> body) {
                return w.locked(body);
            }

            public boolean usable() {
                return w.usable();
            }

            public boolean saveNow() {
                inside.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return w.saveNow();
            }

            public long have(Asset a) {
                return w.have(a);
            }

            public boolean take(Asset a, long amount) {
                return w.take(a, amount);
            }

            public boolean give(Asset a, long amount) {
                return w.give(a, amount);
            }
        };
        Exchange slowEx = new Exchange(store, cfg(100, 0), store::online, a -> a == 2 ? slow : null, now::get);
        slowEx.recover();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Response> placing = pool.submit(() -> slowEx.place(new Place("slow", 2, 12, Side.BUY, P, 1000)));
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        Future<?> logout = pool.submit(() -> store.logout(2));
        Thread.sleep(100);
        assertFalse(logout.isDone(), "the logout's save waits for the character's lock");
        go.countDown();
        assertTrue(placing.get(5, TimeUnit.SECONDS).ok());
        logout.get(5, TimeUnit.SECONDS);
        pool.shutdown();
        assertFalse(store.online(2));
        assertEquals(MESO0 - 1_010_000, store.meso(12), "the logout saved memory with the escrow taken");
        persistedConserved();
    }

    /** The site and the game placing for the same logged-in player at once, with other traders and spending. */
    @Test
    void concurrentSiteAndInGamePlacingConserves() throws Exception {
        Session s2 = store.login(2, 12);
        Session s3 = store.login(3, 13);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<?>> jobs = new ArrayList<>();
        AtomicLong spent = new AtomicLong();
        for (int t = 0; t < 6; t++) {
            int seed = t;
            jobs.add(pool.submit(() -> {
                Random rnd = new Random(seed);
                for (int i = 0; i < 250; i++) {
                    long acc = 1 + rnd.nextInt(4);
                    int op = rnd.nextInt(12);
                    if (op < 7) {
                        Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                        long price = 950_000 + rnd.nextInt(100) * 1000L, nx = 100L * (1 + rnd.nextInt(10));
                        Place p = new Place("t" + seed + "-" + i, acc, 10 + acc, side, price, nx);
                        if (seed % 2 == 0) {
                            ex.place(p); // the site's path
                        } else {
                            Response pr = ex.preview(p); // the game's (and site's) preview + confirm
                            if (pr.ok()) {
                                ex.confirm("c" + seed + "-" + i, acc, (String) pr.data().get("token"));
                            }
                        }
                    } else if (op < 9) {
                        List<Long> mine = ex.openIdsOf(acc);
                        if (!mine.isEmpty()) {
                            ex.cancel("x" + seed + "-" + i, acc, mine.get(0));
                        }
                    } else if (op == 9) {
                        ex.collectAll();
                    } else if (op == 10) {
                        if (s2.spendMeso(12, 1000)) {
                            spent.addAndGet(1000);
                        }
                    } else {
                        now.addAndGet(1000);
                        ex.expire();
                    }
                }
            }));
        }
        for (Future<?> f : jobs) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        totalMeso -= spent.get();
        conserved();
        assertTrue(s2.meso.get(12L) >= 0 && s3.meso.get(13L) >= 0 && s2.nx >= 0 && s3.nx >= 0);
        store.logout(2);
        store.logout(3);
        persistedConserved();
        assertTrue(store.fills().size() > 0, "trades happened");
    }

    // ---------------------------------------------------------------- confirm step

    @Test
    void previewMovesNothingAndConfirmPlacesOnce() throws Exception {
        assertTrue(place(1, Side.SELL, P, 500).ok());
        Response pv = ex.preview(new Place("p", 2, 12, Side.BUY, 1_100_000, 1000));
        assertTrue(pv.ok(), pv.toString());
        String token = (String) pv.data().get("token");
        assertNotNull(token);
        @SuppressWarnings("unchecked")
        var match = (java.util.Map<String, Object>) pv.data().get("matchNow");
        assertEquals(500L, match.get("nx"));
        assertEquals(500_000L, match.get("mesos"));
        assertEquals(1, ex.openOrders());
        assertEquals(MESO0, store.meso(12), "a preview moves nothing");
        assertEquals("TOKEN_EXPIRED", ex.confirm("x", 3, token).code(), "another account's token");
        Response c1 = ex.confirm("c1", 2, token);
        Response c2 = ex.confirm("c2", 2, token);
        assertTrue(c1.ok() && c2.ok());
        assertEquals(id(c1), id(c2), "the same token places once");
        assertEquals(1, store.fills().size());
        now.addAndGet(61_000);
        String t2 = (String) ex.preview(new Place("p2", 2, 12, Side.BUY, P, 100)).data().get("token");
        now.addAndGet(61_000);
        assertEquals("TOKEN_EXPIRED", ex.confirm("c3", 2, t2).code());
        conserved();
    }

    @Test
    void theInGameCommandPreviewsConfirmsListsAndCancels() throws Exception {
        Session s = store.login(2, 12);
        LiveWallet w = store.finder().find(2);
        List<String> pv = ExchangeChat.handle(ex, w, new String[]{"buy", "1,000", "1.2m"});
        assertTrue(pv.get(0).startsWith("Preview: buy 1,000 NX at 1,200,000"), pv.toString());
        assertTrue(pv.get(pv.size() - 1).contains("@nx confirm"));
        assertEquals(MESO0, s.meso.get(12L));
        List<String> ok = ExchangeChat.handle(ex, w, new String[]{"confirm"});
        assertTrue(ok.get(0).startsWith("Order "), ok.toString());
        assertEquals(MESO0 - 1_212_000, s.meso.get(12L));
        assertEquals("NX exchange: nothing to confirm; preview an order first (TOKEN_EXPIRED)",
                ExchangeChat.handle(ex, w, new String[]{"confirm"}).get(0));
        long orderId = ex.openIdsOf(2).get(0);
        assertTrue(ExchangeChat.handle(ex, w, new String[]{"orders"}).get(0).contains("#" + orderId));
        assertTrue(ExchangeChat.handle(ex, w, new String[]{"cancel", String.valueOf(orderId)}).get(0)
                .contains("cancelled"));
        assertEquals(MESO0, s.meso.get(12L), "the refund came straight back");
        assertTrue(ExchangeChat.handle(ex, w, new String[]{"buy", "150", "1m"}).get(0).contains("BAD_REQUEST"));
        assertTrue(ExchangeChat.handle(ex, w, new String[]{}).get(0).startsWith("NX exchange: best bid"));
        assertEquals("The NX exchange is not open.", ExchangeChat.handle(null, w, new String[]{}).get(0));
        assertEquals(1_200_000L, ExchangeChat.amount("1.2m"));
        assertEquals(500_000L, ExchangeChat.amount("500k"));
        assertEquals(null, ExchangeChat.amount("1.5"));
        assertEquals(null, ExchangeChat.amount("-3"));
        conserved();
    }

    // ---------------------------------------------------------------- fees

    @Test
    void aFeeRaisedAfterPlacingNeverOverdrawsTheReserve() throws Exception {
        ex = start(cfg(0, 0));
        long buy = id(place(2, Side.BUY, P, 1000)); // no reserve
        ex = start(cfg(100, 0));
        assertTrue(place(1, Side.SELL, P, 1000).ok());
        assertEquals(10_000, store.balance(Bucket.fees()), "only the seller's fee: the buyer reserved none");
        assertEquals("FILLED", store.order(buy).status());
        conserved();
    }

    // ---------------------------------------------------------------- staff reversal

    @Test
    void aReversalRunsOnlyWhileHaltedOnceAndPutsEverythingBack() throws Exception {
        assertTrue(place(1, Side.SELL, P, 1000).ok());
        assertTrue(place(2, Side.BUY, P, 1000).ok());
        long fill = store.fills().get(0).id();
        ex.collectAll(); // both sides already collected into their wallets
        assertEquals(NX0 + 1000, store.nx(2));
        assertEquals("NOT_HALTED", ex.reverse(fill, "gm").code());
        ex.halt("investigating");
        store.login(2, 12);
        assertEquals("PLAYER_ONLINE", ex.reverse(fill, "gm").code(), "the buyer collected and is online");
        store.logout(2);
        Response r = ex.reverse(fill, "gm");
        assertTrue(r.ok(), r.toString());
        assertEquals("ALREADY_REVERSED", ex.reverse(fill, "gm").code());
        assertEquals(0, store.balance(Bucket.fees()), "both fees went back to the buyer");
        conserved();
        ex.resume();
        ex.collectAll();
        assertEquals(NX0, store.nx(1));
        assertEquals(NX0, store.nx(2));
        assertEquals(MESO0, store.meso(11));
        assertEquals(MESO0, store.meso(12));
        assertTrue(store.ledger().stream().anyMatch(row -> row.posting().why() == Ledger.Why.REVERSE));
        conserved();
        assertTrue(start(cfg(100, 0)).recover().isEmpty(), "reconciles after the reversal");
    }

    // ---------------------------------------------------------------- regulars

    @Test
    void regularsAreIdleWithoutAHouseAndOnlyTheHouseAccountFundsThem() throws Exception {
        assertTrue(place(1, Side.SELL, 1_100_000, 1000).ok());
        assertTrue(place(3, Side.BUY, 900_000, 1000).ok());
        assertEquals(0, ex.tickRegulars(), "no house account: idle");
        Session player = store.login(2, 12);
        LiveWallet pw = store.finder().find(2);
        assertEquals("NOT_HOUSE", ex.fundHouseLive(1, pw, Asset.NX, 1000, false).code());
        store.logout(2);
        ex = start(cfg(100, 9));
        Session house = store.login(9, 19);
        LiveWallet hw = store.finder().find(9);
        assertEquals("NOT_HOUSE", ex.fundHouseLive(1, pw, Asset.NX, 1000, false).code(), "still not the house");
        for (int reg = 1; reg <= 3; reg++) {
            assertTrue(ex.fundHouseLive(reg, hw, Asset.NX, 1000, false).ok());
            assertTrue(ex.fundHouseLive(reg, hw, Asset.MESO, 10_000_000, false).ok());
        }
        assertEquals(1_000_000 - 3000, house.nx);
        assertEquals(6, ex.tickRegulars(), "3 regulars, a bid and an ask each");
        assertEquals(1_000_000 + 30_000, ex.bookForTest().best(Side.SELL), "3% above the mid");
        conserved();
        assertTrue(place(4, Side.BUY, 1_030_000, 500).ok()); // a player takes regular 1's ask
        assertEquals(null, ex.officialRate(), "regulars never set the rate");
        long nx0 = house.nx, meso0 = house.meso.get(19L);
        for (int reg = 1; reg <= 3; reg++) {
            assertTrue(ex.unfundHouseLive(reg, hw, false).ok());
        }
        assertEquals(0, ex.openIdsOf(Regulars.owner(1)).size());
        assertEquals(nx0 + 3000 - 500, house.nx, "the house got back its NX less what regular 1 sold");
        assertTrue(house.meso.get(19L) > meso0 + 30_000_000 - 1, "and its mesos plus regular 1's proceeds");
        conserved();
        store.logout(9);
        persistedConserved();
        assertFalse(player.meso.isEmpty());
    }
}
