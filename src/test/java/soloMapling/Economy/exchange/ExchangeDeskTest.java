package soloMapling.Economy.exchange;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Exchange.Place;
import soloMapling.Economy.exchange.Exchange.Response;
import soloMapling.Economy.exchange.Ledger.Asset;
import soloMapling.Economy.exchange.Ledger.Bucket;
import soloMapling.Economy.exchange.Ledger.Posting;
import soloMapling.Economy.exchange.Ledger.Why;
import soloMapling.Economy.exchange.MemoryExchangeStore.Session;
import soloMapling.Economy.exchange.OrderBook.Side;
import soloMapling.server.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 162: the NX house desk. R (mesos per 1,000 NX) is the published rate; players trade the whole amount at the
 * quote (buy = floor(R*(1-spread)) what the house pays, sell = ceil(R*(1+spread)) what it charges), the quote is held
 * for the confirm, every fill is an ordinary ledger posting between the player's wallet and the house's HOUSE buckets
 * (nothing minted), the hourly drift is capped by the step, the day and the hard window, and with the desk on the NX
 * order book is off. With the switch off ({@code -Dslums.exchange.desk}) nothing here exists: the book runs as before.
 */
class ExchangeDeskTest {

    static final long NX0 = 100_000;
    static final long MESO0 = 50_000_000;
    static final long HOUSE = 9;          // the house account
    static final long HOUSE_CHAR = 19;
    static final long HOUR = Desk.HOUR_MS;

    MemoryExchangeStore store;
    AtomicLong now;
    DeskConfig deskCfg;
    int seq;
    long totalNx, totalMeso;

    @BeforeEach
    void setUp() throws Exception {
        store = new MemoryExchangeStore();
        for (long a = 1; a <= 3; a++) {
            store.addAccount(a, NX0);
            store.addCharacter(10 + a, a, MESO0);
        }
        store.addAccount(HOUSE, 1_000_000);
        store.addCharacter(HOUSE_CHAR, HOUSE, 1_000_000_000);
        now = new AtomicLong(1_800_000_000_000L);
        deskCfg = DeskConfig.defaults().on();
    }

    /** An exchange with the desk on (or off, when {@code cfg} is the default), sharing this store. */
    Exchange start(DeskConfig cfg, long house) throws Exception {
        return start(ExchangeLiveTest.cfg(100, house), cfg);
    }

    /** The same, against another memory store (a double that behaves like the JDBC one). */
    Exchange start(MemoryExchangeStore st, DeskConfig cfg) throws Exception {
        Exchange e = new Exchange(st, ExchangeLiveTest.cfg(100, HOUSE), st::online, st.finder(), now::get);
        e.guard(ExchangeTest.engineGuard());
        e.siteReauth(SiteReauth.OFF);
        e.deskConfig(cfg);
        assertTrue(e.recover().isEmpty());
        return e;
    }

    Exchange start(ExchangeConfig ec, DeskConfig cfg) throws Exception {
        Exchange e = new Exchange(store, ec, store::online, store.finder(), now::get);
        e.guard(ExchangeTest.engineGuard());
        e.siteReauth(SiteReauth.OFF);
        e.deskConfig(cfg);
        assertTrue(e.recover().isEmpty());
        return e;
    }

    /**
     * MemoryExchangeStore with JdbcExchangeStore.move's checks (ownership only): a meso leg for any character of the
     * live account is written, never refused for not being the live one. The memory store itself refuses it with
     * NOT_OWNER, which would hide finding 1.
     */
    static final class JdbcLikeStore extends MemoryExchangeStore {
        @Override
        public synchronized void apply(ExchangeStore.Batch b) throws ExchangeStore.StoreException {
            try {
                super.apply(b);
            } catch (ExchangeStore.StoreException e) {
                if (e.failure != ExchangeStore.Failure.NOT_OWNER) {
                    throw e;
                }
                super.apply(new ExchangeStore.Batch(b.id(), b.at(), b.postings(), b.orders(), b.fills(),
                        b.liveAccount(), mesoCharacter(b), b.inventory(), b.desk()));
            }
        }

        /** The character the batch's meso wallet leg names: what move() writes into characters.meso. */
        private static long mesoCharacter(ExchangeStore.Batch b) throws ExchangeStore.StoreException {
            for (Posting p : b.postings()) {
                for (Bucket w : List.of(p.from(), p.to())) {
                    if (w.kind() == Ledger.Kind.WALLET && w.asset() == Asset.MESO) {
                        return w.id();
                    }
                }
            }
            throw new ExchangeStore.StoreException(ExchangeStore.Failure.ERROR, "no player meso leg");
        }
    }

    /** The test's config with a longer hold, so a preview can outlive an hourly step (QUOTE_MOVED). */
    static ExchangeConfig slowConfirm(long confirmMs) {
        ExchangeConfig d = ExchangeConfig.defaults();
        return new ExchangeConfig(true, d.lotNx(), d.minNx(), d.maxNx(), d.minPrice(), d.maxPrice(), 100, 1000,
                100_000, 0, java.util.concurrent.TimeUnit.HOURS.toMillis(72), 3, d.regularSpreadBps(), d.regularNx(),
                d.rateWindowMs(), 1, d.pollSeconds(), HOUSE, confirmMs);
    }

    /** Moves NX from account 1 and mesos from character 11 into the desk's HOUSE buckets (a FUND, as staff funding). */
    void seedDesk(long nx, long meso) throws Exception {
        List<Posting> ps = new ArrayList<>();
        if (nx > 0) {
            ps.add(new Posting(Why.FUND, Bucket.wallet(Asset.NX, 1), Exchange.deskBucket(Asset.NX), nx, 1, 0, 0));
        }
        if (meso > 0) {
            ps.add(new Posting(Why.FUND, Bucket.wallet(Asset.MESO, 11), Exchange.deskBucket(Asset.MESO), meso, 1, 0,
                    0));
        }
        store.apply(new ExchangeStore.Batch(9000, now.get(), ps, List.of(), List.of()));
        totalNx = store.totalNx();
        totalMeso = store.totalMeso();
    }

    Response preview(Exchange e, long account, Side side, long nx) {
        return e.preview(new Place("p" + (++seq), account, 10 + account, side, 0, nx, Ticker.NX));
    }

    Response previewAndConfirm(Exchange e, long account, Side side, long nx) {
        Response pv = preview(e, account, side, nx);
        if (!pv.ok()) {
            return pv;
        }
        return e.confirm("c" + (++seq), account, (String) pv.data().get("token"));
    }

    /** Nothing is minted: the NX and the mesos are the same totals, and the ledger reconciles. */
    void conserved(Exchange e) throws Exception {
        assertEquals(totalNx, store.totalNx(), "NX conserved");
        assertEquals(totalMeso, store.totalMeso(), "mesos conserved");
        assertTrue(Exchange.reconcile(store.load(10)).isEmpty(), "reconciles: " + Exchange.reconcile(store.load(10)));
        exBook(e, "NX").check();
        for (var b : store.balances().entrySet()) {
            assertTrue(b.getValue() >= 0, b.getKey() + " negative");
        }
        assertTrue(store.nx(1) >= 0 && store.nx(2) >= 0 && store.nx(3) >= 0, "no wallet goes negative");
        assertTrue(store.meso(11) >= 0 && store.meso(12) >= 0 && store.meso(13) >= 0, "no wallet goes negative");
    }

    private static OrderBook exBook(Exchange e, String ticker) {
        return e.bookForTest(ticker);
    }

    @Test
    void theQuotesAreTheSpreadAroundRAndEveryFillIsAtTheQuote() throws Exception {
        Exchange e = start(deskCfg, HOUSE);
        seedDesk(5_000, 15_000_000);
        Desk d = e.desk();
        assertNotNull(d);
        assertEquals(5_000_000, d.mid(), "no stored R and no book rate yet: the start price");
        assertEquals(4_900_000, d.buy(), "the house pays floor(R*(1-2%))");
        assertEquals(5_100_000, d.sell(), "it charges ceil(R*(1+2%))");

        // a BUY from a player who is not logged in: mesos from the character, NX to the account, all at the quote
        Response pv = preview(e, 2, Side.BUY, 1_000);
        assertTrue(pv.ok(), pv.toString());
        assertEquals(5_100_000L, pv.data().get("price"), "a BUY pays the sell side");
        assertEquals(5_100_000L, pv.data().get("mesos"));
        assertEquals(5_000_000L, pv.data().get("mid"));
        Response fill = e.confirm("c-fill", 2, (String) pv.data().get("token"));
        assertTrue(fill.ok(), fill.toString());
        Map<?, ?> f = (Map<?, ?>) fill.data().get("fill");
        assertEquals(1_000L, f.get("nx"));
        assertEquals(5_100_000L, f.get("price"));
        assertEquals(5_100_000L, f.get("mesos"));
        assertEquals(NX0 + 1_000, store.nx(2));
        assertEquals(MESO0 - 5_100_000, store.meso(12));
        assertEquals(4_000, store.balance(Exchange.deskBucket(Asset.NX)), "the desk gave 1,000 NX of its own");
        assertEquals(15_000_000 + 5_100_000, store.balance(Exchange.deskBucket(Asset.MESO)));
        String status = String.join("\n", e.status());
        assertTrue(status.contains("NX desk: open"), status);
        assertTrue(status.contains("R 5000000"), status);
        assertTrue(status.contains("buy 4900000 / sell 5100000"), status);
        assertTrue(status.contains("desk 4000 NX / 20100000 mesos"), status);
        conserved(e);

        // ... and a SELL back: the player gets the buy side, the desk gets the NX back
        Response back = previewAndConfirm(e, 2, Side.SELL, 1_000);
        assertTrue(back.ok(), back.toString());
        assertEquals(4_900_000L, ((Map<?, ?>) back.data().get("fill")).get("price"));
        assertEquals(NX0, store.nx(2));
        assertEquals(MESO0 - 5_100_000 + 4_900_000, store.meso(12));
        assertEquals(5_000, store.balance(Exchange.deskBucket(Asset.NX)));
        assertEquals(15_000_000 + 200_000, store.balance(Exchange.deskBucket(Asset.MESO)),
                "the desk keeps the spread: 200,000 mesos on 1,000 NX");
        conserved(e);
    }

    @Test
    void manyRandomFillsKeepEveryNxAndEveryMeso() throws Exception {
        Exchange e = start(deskCfg, HOUSE);
        seedDesk(100_000, 50_000_000);
        Random rnd = new Random(7);
        int ok = 0;
        for (int i = 0; i < 200; i++) {
            long acct = 1 + rnd.nextInt(3);
            Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
            long nx = 100L * (1 + rnd.nextInt(10));
            Response r = previewAndConfirm(e, acct, side, nx);
            if (r.ok()) {
                ok++;
                Map<?, ?> fill = (Map<?, ?>) r.data().get("fill");
                long price = (Long) fill.get("price");
                long mesos = (Long) fill.get("mesos");
                assertEquals(e.desk().quote(side), price, "every fill is at the quote of that side");
                assertEquals(side == Side.BUY ? -Math.floorDiv(-nx * price, 1_000) : nx * price / 1_000, mesos,
                        "a BUY rounds up, a SELL down, both per 1,000 NX");
            } else {
                assertTrue(Set.of("DESK_EMPTY", "INSUFFICIENT_FUNDS").contains(r.code()),
                        "only the two honest refusals here: " + r);
            }
        }
        assertTrue(ok >= 100, "most of the 200 trades fill: " + ok);
        conserved(e);
    }

    @Test
    void aLoggedInPlayerGetsTheFillInTheirWalletUnderTheLock() throws Exception {
        Exchange e = start(deskCfg, HOUSE);
        seedDesk(10_000, 20_000_000);
        Session s = store.login(2, 12);
        Response r = previewAndConfirm(e, 2, Side.BUY, 500);
        assertTrue(r.ok(), r.toString());
        assertEquals(NX0 + 500, s.nx, "the account's NX is in the live session");
        assertEquals(MESO0 - 2_550_000, s.meso.get(12L));
        assertEquals(NX0 + 500, store.nx(2), "the transaction saved it too");
        assertTrue(store.saves > 0, "the character was saved before the trade");
        conserved(e);
    }

    /**
     * Finding 1: the confirm repeats the preview's who-is-this check, as place() does. A quote held for character 12
     * while its player was offline, confirmed after logging in as character 23 of the same account, is refused: the
     * fill moves the previewed character's mesos, and the store writes the row the request names. MemoryExchangeStore
     * happened to answer NOT_OWNER here; a JDBC store, which only checks ownership, would have written it.
     */
    @Test
    void aConfirmForAnotherCharacterIsRefusedAndMovesNothing() throws Exception {
        store.addCharacter(23, 2, MESO0);
        Exchange e = start(deskCfg, HOUSE);
        seedDesk(10_000, 20_000_000);
        Response pv = preview(e, 2, Side.SELL, 1_000);
        assertTrue(pv.ok(), pv.toString());
        Session s = store.login(2, 23);
        assertEquals(23, s.live, "another character of the account is the live one now");
        Response r = e.confirm("c1", 2, (String) pv.data().get("token"));
        assertEquals("LOG_OUT_FIRST", r.code(), r.toString());
        assertTrue(r.message().contains("another character"), r.message());
        assertEquals(NX0, store.nx(2), "the account's NX is untouched");
        assertEquals(MESO0, store.meso(12), "the previewed character's mesos are untouched");
        assertEquals(NX0, s.nx, "nothing reached the live wallet's NX");
        assertEquals(MESO0, s.meso.get(23L), "nor the live character's mesos");
        assertTrue(e.desk().fillsOf(2, 20).isEmpty(), "no fill was recorded");
        conserved(e);
    }

    /**
     * Finding 1 against a store that behaves like MySQL: {@link JdbcExchangeStore#move} checks ownership only (the NX
     * bucket is the account, the meso character belongs to it) and never compares a leg to the live character, so the
     * exchange's own check is the only thing between a mis-confirmed quote and minted money. Here the SELL would pay
     * character 12's row and character 23's live wallet both: twice the mesos for one NX.
     */
    @Test
    void aJdbcStoreWouldWriteTheWrongCharacterSoTheExchangeRefusesFirst() throws Exception {
        JdbcLikeStore jdbc = new JdbcLikeStore();
        for (long a = 1; a <= 3; a++) {
            jdbc.addAccount(a, NX0);
            jdbc.addCharacter(10 + a, a, MESO0);
        }
        jdbc.addCharacter(23, 2, MESO0);
        jdbc.addAccount(HOUSE, 1_000_000);
        jdbc.addCharacter(HOUSE_CHAR, HOUSE, 1_000_000_000);
        Exchange e = start(jdbc, deskCfg);
        jdbc.apply(new ExchangeStore.Batch(9000, now.get(), List.of(
                new Posting(Why.FUND, Bucket.wallet(Asset.NX, 1), Exchange.deskBucket(Asset.NX), 5_000, 1, 0, 0),
                new Posting(Why.FUND, Bucket.wallet(Asset.MESO, 11), Exchange.deskBucket(Asset.MESO),
                        20_000_000, 1, 0, 0)), List.of(), List.of()));
        Response pv = preview(e, 2, Side.SELL, 1_000);
        assertTrue(pv.ok(), pv.toString());
        Session s = jdbc.login(2, 23);
        Response r = e.confirm("c1", 2, (String) pv.data().get("token"));
        assertEquals("LOG_OUT_FIRST", r.code(), r.toString());
        assertEquals(MESO0, jdbc.meso(12), "the previewed character's row was not written");
        assertEquals(MESO0, s.meso.get(23L), "and the live character's wallet was not paid");
        assertTrue(e.desk().fillsOf(2, 20).isEmpty(), "no fill was recorded");
    }

    /**
     * Finding 2: the confirm's offline path repeats place()'s presence check. A player who logs in between the preview
     * and the confirm has no live wallet yet while their character loads; a wallet write in that window is
     * overwritten by the save that follows.
     */
    @Test
    void aConfirmWhileTheAccountIsStillLoadingIsRefused() throws Exception {
        Exchange e = start(deskCfg, HOUSE);
        seedDesk(10_000, 20_000_000);
        Response pv = preview(e, 2, Side.SELL, 1_000);
        assertTrue(pv.ok(), pv.toString());
        store.login(2);                       // logged in, character not loaded: no live wallet yet
        assertNull(store.finder().find(2), "no live wallet yet");
        assertTrue(store.online(2), "but the account is in game");
        Response r = e.confirm("c1", 2, (String) pv.data().get("token"));
        assertEquals("LOG_OUT_FIRST", r.code(), r.toString());
        assertTrue(r.message().contains("log out"), r.message());
        assertEquals(NX0, store.nx(2), "the account's NX is untouched");
        assertEquals(MESO0, store.meso(12), "and the character's mesos");
        assertTrue(e.desk().fillsOf(2, 20).isEmpty(), "no fill was recorded");
        conserved(e);
    }

    @Test
    void aRefusedDeskFillLeavesNoPhantomInTheDesk() throws Exception {
        Exchange e = start(deskCfg, HOUSE);
        seedDesk(60_000, 40_000_000);
        long nx0 = store.nx(2), meso0 = store.meso(12);
        store.crashAfterSteps(0);   // the store refuses the batch, as a dead database would
        Response r = previewAndConfirm(e, 2, Side.BUY, 1_000);
        assertFalse(r.ok(), "the store refused: nothing moved");
        assertEquals("ERROR", r.code());
        assertTrue(e.desk().fillsOf(2, 20).isEmpty(), "a fill that never happened must not be remembered");
        now.addAndGet(Desk.HOUR_MS);
        assertEquals(0, e.tickDesk(), "and its NX must not drift R: the hour's flow is still zero");
        assertEquals(5_000_000, e.desk().mid());
        assertEquals(nx0, store.nx(2));
        assertEquals(meso0, store.meso(12));
        conserved(e);
    }

    @Test
    void theHeldQuoteIsOneUseAndExpires() throws Exception {
        Exchange e = start(deskCfg, HOUSE);
        seedDesk(5_000, 20_000_000);
        Response pv = preview(e, 2, Side.BUY, 1_000);
        String token = (String) pv.data().get("token");
        assertTrue(e.confirm("c1", 2, token).ok());
        assertEquals("TOKEN_EXPIRED", e.confirm("c2", 2, token).code(), "a desk token fills once");
        Response pv2 = preview(e, 2, Side.BUY, 1_000);
        now.addAndGet(61_000);
        assertEquals("TOKEN_EXPIRED", e.confirm("c3", 2, (String) pv2.data().get("token")).code(),
                "a held quote lasts confirmSec");
    }

    @Test
    void theStepCeilingAndTheDayCapHoldTheRate() {
        // the desk's own maths: 7 bps per 1% of the float traded, but never more than 50 bps an hour
        Desk d = Desk.start(deskCfg, null, null, now.get());
        assertEquals(5_000_000, d.mid());
        d.recordFill(1, now.get(), 2, Side.BUY, 100_000, d.sell(), 510_000_000);  // the house sold 100,000 NX
        assertEquals(50, d.tick(now.get() + HOUR, 100_000), "100% of the float in an hour would be 700 bps: capped at 50");
        assertEquals(5_025_000, d.mid());

        // a 10%-an-hour step still cannot walk R more than 20% away from the day's first rate
        DeskConfig steep = new DeskConfig(true, 200, 7, 1_000, 20, 0, 100);
        Desk c = Desk.start(steep, null, null, now.get());
        for (int h = 1; h <= 6; h++) {
            c.recordFill(h, now.get() + h * HOUR, 2, Side.BUY, 1_000_000, c.sell(), 0);
            c.tick(now.get() + h * HOUR, 100_000);
        }
        assertEquals(6_000_000, c.mid(), "5,000,000 + 20% is the day's ceiling");
        assertEquals(20.0, Math.round(c.movedTodayPct() * 100) / 100.0, 0.01, "moved 20.00% today");

        // a new day: the anchor moves to R and the rate may drift again
        c.recordFill(99, now.get() + Desk.DAY_MS, 2, Side.SELL, 1_000_000, c.buy(), 0);
        assertEquals(-1_000, c.tick(now.get() + Desk.DAY_MS, 100_000),
                "a new day's anchor is 6,000,000, so the 10% step is allowed (the day cap permits a fall)");
        assertEquals(5_400_000, c.mid());
        assertTrue(Math.abs(c.movedTodayPct()) <= 20.0001, "still inside the day cap");
    }

    @Test
    void theHardWindowIsTheLastWord() {
        DeskConfig wide = new DeskConfig(true, 200, 7, 50, 100, 0, 100);   // day cap 100% so the band can be reached
        Desk d = Desk.start(wide, null, null, now.get());
        for (int h = 1; h <= 400; h++) {
            d.recordFill(h, now.get() + h * HOUR, 2, Side.BUY, 1_000_000, d.sell(), 0);
            d.tick(now.get() + h * HOUR, 1_000_000);
        }
        assertEquals(NxGuard.NX_HIGHEST, d.mid(), "never above the hard window");
        for (int h = 401; h <= 800; h++) {
            d.recordFill(h, now.get() + h * HOUR, 2, Side.SELL, 1_000_000, d.buy(), 0);
            d.tick(now.get() + h * HOUR, 1_000_000);
        }
        assertEquals(NxGuard.NX_LOWEST, d.mid(), "and never below it");
    }

    @Test
    void rSurvivesARestart() throws Exception {
        Exchange e = start(deskCfg, HOUSE);
        seedDesk(10_000, 50_000_000);
        Response pv = preview(e, 2, Side.SELL, 5_000);   // the house buys NX: the next step falls
        assertTrue(e.confirm("c1", 2, (String) pv.data().get("token")).ok());
        now.addAndGet(HOUR + 1);
        int bps = (int) e.tickDesk();
        assertTrue(bps < 0, "a house buy pulls R down: " + bps);
        long r = e.desk().mid();
        assertTrue(r < 5_000_000);
        assertEquals(r, e.officialRate(), "R is the official NX rate, not the book's VWAP");

        Exchange again = start(deskCfg, HOUSE);
        assertEquals(r, again.desk().mid(), "R is stored and comes back");
        assertEquals(r, again.officialRate());
    }

    @Test
    void noHouseAccountClosesTheDesk() throws Exception {
        Exchange e = start(deskCfg, 0);
        Desk d = e.desk();
        assertNotNull(d, "the desk exists (its state and R stay) ...");
        assertFalse(e.deskOpen(), "... but it does not trade");
        assertEquals("no house account", e.closeReason());
        seedDesk(10_000, 10_000_000);
        assertEquals("DESK_CLOSED", preview(e, 2, Side.BUY, 1_000).code());
        assertEquals("DESK_CLOSED", preview(e, 2, Side.SELL, 1_000).code());
        long r = d.mid();
        now.addAndGet(HOUR + 1);
        assertEquals(0, e.tickDesk(), "no drift without an open desk");
        assertEquals(r, d.mid());
        Map<String, Object> json = ExchangeSite.desk(e, now.get());
        assertEquals(false, json.get("open"));
        assertEquals("no house account", json.get("closed_reason"));
        assertTrue(Exchange.reconcile(store.load(10)).isEmpty());
    }

    @Test
    void haltedClosesTheDeskToo() throws Exception {
        Exchange e = start(deskCfg, HOUSE);
        seedDesk(10_000, 10_000_000);
        e.halt("maintenance");
        assertEquals("halted", e.closeReason());
        Response r = preview(e, 2, Side.BUY, 1_000);
        assertFalse(r.ok());
        assertTrue(Set.of("DESK_CLOSED", "HALTED").contains(r.code()), r.code());
        assertEquals("halted", ExchangeSite.desk(e, now.get()).get("closed_reason"));
        e.resume();
        assertNull(e.closeReason());
        assertTrue(preview(e, 2, Side.BUY, 1_000).ok(), "open again after resume");
    }

    @Test
    void deskEmptyWhenItHasNoNxToSellOrNoMesosToBuyWith() throws Exception {
        Exchange e = start(deskCfg, HOUSE);
        seedDesk(0, 0);
        Response buy = preview(e, 2, Side.BUY, 1_000);
        assertEquals("DESK_EMPTY", buy.code());
        assertEquals(0L, buy.data().get("desk_nx"));
        Response sell = preview(e, 2, Side.SELL, 1_000);
        assertEquals("DESK_EMPTY", sell.code(), "the desk has no mesos either");
        assertFalse((Boolean) ExchangeSite.desk(e, now.get()).get("selling"));

        seedDesk(1_000, 10_000_000);
        assertEquals("DESK_EMPTY", preview(e, 2, Side.BUY, 2_000).code(), "it will not sell NX it does not have");
        assertTrue(preview(e, 2, Side.BUY, 1_000).ok());
        assertTrue((Boolean) ExchangeSite.desk(e, now.get()).get("selling"));
        assertTrue(Exchange.reconcile(store.load(10)).isEmpty());
    }

    @Test
    void quoteMovedAtConfirmCarriesTheFreshQuote() throws Exception {
        // a hold that outlives the hourly step: the preview is taken, the hour turns, the confirm finds a new quote
        Exchange e = start(slowConfirm(2 * HOUR), deskCfg);
        seedDesk(0, 50_000_000);
        Response first = preview(e, 3, Side.SELL, 3_000);     // the house buys: R falls at the next hour
        long held = (Long) first.data().get("price");
        assertEquals(4_900_000, held);
        assertTrue(e.confirm("c1", 3, (String) first.data().get("token")).ok());

        Response pv = preview(e, 3, Side.SELL, 1_000);
        assertEquals(held, pv.data().get("price"));
        now.addAndGet(HOUR + 1);
        assertTrue(e.tickDesk() < 0, "the hour's drift moved R down");
        assertTrue(e.desk().buy() < held);

        Response moved = e.confirm("c2", 3, (String) pv.data().get("token"));
        assertEquals("QUOTE_MOVED", moved.code());
        assertEquals(e.desk().buy(), moved.data().get("price"));
        assertEquals(e.desk().mid(), moved.data().get("mid"));
        assertNotNull(moved.data().get("token"));
        assertFalse(moved.data().get("token").equals(pv.data().get("token")), "a fresh token comes with it");
        Response then = e.confirm("c3", 3, (String) moved.data().get("token"));
        assertTrue(then.ok(), "confirming the fresh quote fills at it: " + then);
        assertEquals(e.desk().buy(), ((Map<?, ?>) then.data().get("fill")).get("price"));
        conserved(e);
    }

    @Test
    void anNxOrderIsRefusedWhileTheDeskRunsAndItemsAreNot() throws Exception {
        Exchange e = start(deskCfg, HOUSE);
        Response nx = e.place(new Place("o1", 2, 12, Side.BUY, 5_000_000, 1_000, Ticker.NX));
        assertFalse(nx.ok());
        assertEquals("BAD_REQUEST", nx.code());
        assertTrue(nx.message().contains("desk"), nx.message());
        assertEquals(0, e.openNxOrders());
        // a trader bot cannot rest NX either, even with its own flag on: the desk is the only NX market now
        long botId = 1_000_000_777L;
        System.setProperty(soloMapling.Economy.exchange.volatility.MarketConfig.TRADERS_NX, "true");
        try {
            FakeBotWallet bot = new FakeBotWallet(botId, 50_000_000);
            Response botNx = e.placeBot(new Place("o4", botId, botId, Side.BUY, e.desk().sell(), 1_000, Ticker.NX),
                    bot);
            assertFalse(botNx.ok());
            assertEquals("BAD_REQUEST", botNx.code());
            assertTrue(botNx.message().contains("desk"), botNx.message());
            assertEquals(0, e.botNx(botId), "nothing was traded");
            assertEquals(50_000_000, bot.mesos, "the bot paid nothing");
        } finally {
            System.clearProperty(soloMapling.Economy.exchange.volatility.MarketConfig.TRADERS_NX);
        }
        // the item tickers are untouched by the desk
        Response leaf = e.place(new Place("o2", 2, 12, Side.BUY, 600_000, 10, "LEAF"));
        assertTrue(leaf.ok(), leaf.toString());
        assertEquals(1, e.openOrders(), "the LEAF order rests");
        // and with the desk off the NX book is exactly as it was
        Exchange off = start(DeskConfig.defaults(), HOUSE);
        assertTrue(off.place(new Place("o3", 2, 12, Side.BUY, 5_000_000, 1_000, Ticker.NX)).ok(),
                "switch off: NX orders work as before");
    }

    @Test
    void cancelNxBookReturnsEveryEscrowOnceAndIsIdempotent() throws Exception {
        Exchange off = start(DeskConfig.defaults(), HOUSE);
        Response sell = off.place(new Place("o1", 3, 13, Side.SELL, 5_000_000, 1_000, Ticker.NX));
        assertTrue(sell.ok(), sell.toString());
        assertEquals(NX0 - 1_000, store.nx(3), "the NX is in escrow");
        assertEquals(1_000, store.balance(Bucket.escrow(Asset.NX, ((Number) sell.data().get("orderId")).longValue())));

        Exchange e = start(deskCfg, HOUSE);   // the cutover: the desk takes over the NX book
        assertEquals(1, e.openNxOrders(), "the old NX order is still resting at recovery");
        Response r = e.resume();
        assertTrue(r.ok(), r.toString());
        assertEquals(1, r.data().get("closed"));
        assertEquals(1_000L, r.data().get("nxReturned"));
        assertEquals(0, e.openNxOrders());
        assertEquals(NX0 - 1_000, store.nx(3), "offline, the refund waits in the account's payout");
        assertEquals(1_000, store.balance(Bucket.payout(Asset.NX, 3)));
        e.collectAll();
        assertEquals(NX0, store.nx(3), "the escrow came back to its owner");
        Response again = e.cancelNxBook("cancel-nx");
        assertEquals(0, again.data().get("closed"), "idempotent: nothing left to close");
        assertEquals(0L, again.data().get("nxReturned"));
        assertEquals(NX0, store.nx(3));
        assertTrue(Exchange.reconcile(store.load(10)).isEmpty());
    }

    @Test
    void aDeskSellOrABigDeskBuyStillAsksTheWebsiteForThePassword() throws Exception {
        Exchange e = start(deskCfg, HOUSE);
        e.siteReauth(new SiteReauth(false, true, 0)); // step-up on, no free buys
        seedDesk(90_000, 40_000_000);
        Response pv = site(e, "\"id\":\"a\",\"account\":2,\"type\":\"preview\",\"character\":12,\"side\":\"SELL\","
                + "\"nx\":1000");
        assertTrue(pv.ok(), pv.toString());
        assertEquals(true, pv.data().get("passwordRequired"), "a sell asks");
        String token = (String) pv.data().get("token");
        assertEquals("PASSWORD_REQUIRED",
                site(e, "\"id\":\"b\",\"account\":2,\"type\":\"confirm\",\"token\":\"" + token + "\"").code());
        assertEquals(NX0, store.nx(2), "nothing moved while it waited for the password");
        assertTrue(e.approve(2, token).ok());
        Response filled = site(e, "\"id\":\"c\",\"account\":2,\"type\":\"confirm\",\"token\":\"" + token + "\"");
        assertTrue(filled.ok(), filled.toString());
        assertEquals(NX0 - 1_000, store.nx(2));
        conserved(e);
    }

    @Test
    void theInGameCommandTradesAtTheDeskQuoteWithNoPriceToType() throws Exception {
        Exchange e = start(deskCfg, HOUSE);
        seedDesk(4_000, 40_000_000);
        MemoryExchangeStore.Session s = store.login(2, 12);
        LiveWallet w = store.finder().find(2);
        List<String> help = ExchangeChat.handle(e, w, new String[0]);
        assertTrue(help.get(0).startsWith("NX desk"), help.toString());
        assertTrue(help.get(0).contains("holds 4,000 NX"), help.toString());
        assertTrue(help.get(2).contains("no price"), help.toString());
        List<String> pv = ExchangeChat.handle(e, w, new String[]{"buy", "1,000"});
        assertTrue(pv.get(0).startsWith("NX desk: buy 1,000 NX at 5,100 mesos per NX = 5,100,000 mesos"),
                pv.toString());
        assertTrue(pv.get(1).contains("@nx confirm"), pv.toString());
        assertEquals(MESO0, s.meso.get(12L), "a preview moves nothing");
        List<String> filled = ExchangeChat.handle(e, w, new String[]{"confirm"});
        assertTrue(filled.get(0).startsWith("NX desk: 1,000 NX at 5,100 mesos per NX, 5,100,000 mesos now"),
                filled.toString());
        assertEquals(MESO0 - 5_100_000, s.meso.get(12L));
        assertEquals(NX0 + 1_000, s.nx);
        assertTrue(ExchangeChat.handle(e, w, new String[]{"buy", "1,000", "5000"}).get(0).contains("no price"),
                "typing a price is answered, not traded");
        assertTrue(ExchangeChat.handle(e, w, new String[]{"buy"}).get(0).contains("the desk's quote is the price"));
        conserved(e);
    }

    @Test
    void theRunnerWritesTheDeskFileEachTickAndRemovesItWithTheDeskOff(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        Exchange e = start(deskCfg, HOUSE);
        seedDesk(10_000, 20_000_000);
        java.nio.file.Path in = dir.resolve("in"), out = dir.resolve("out");
        java.nio.file.Files.createDirectories(in);
        new ExchangeService.Runner(e, in, out, now::get).tick();
        Map<?, ?> file = (Map<?, ?>) Json.parse(
                java.nio.file.Files.readString(out.resolve(ExchangeSite.DESK)));
        assertEquals(18, file.keySet().size(), "nx-desk.json is API.md's shape: " + file.keySet());
        assertEquals(e.desk().mid(), ((Number) file.get("mid")).longValue());
        assertEquals(10_000L, ((Number) file.get("desk_nx")).longValue());

        // the switch is a boot switch: a restart with the desk off (same outbox) leaves no file behind
        Exchange off = start(DeskConfig.defaults(), HOUSE);
        new ExchangeService.Runner(off, in, out, now::get).tick();
        assertFalse(java.nio.file.Files.exists(out.resolve(ExchangeSite.DESK)),
                "the site must not show a dead desk");
    }

    @Test
    void anUnreadableDeskStateHaltsInsteadOfTradingAtAGuess() throws Exception {
        MemoryExchangeStore broken = new MemoryExchangeStore();
        broken.deskReadFailure = new ExchangeStore.StoreException(ExchangeStore.Failure.ERROR,
                "Table 'mapleroot.exchange_desk' doesn't exist");
        broken.addAccount(1, NX0);
        broken.addCharacter(11, 1, MESO0);
        Exchange e = new Exchange(broken, ExchangeLiveTest.cfg(100, HOUSE), broken::online, broken.finder(), now::get);
        e.guard(ExchangeTest.engineGuard());
        e.siteReauth(SiteReauth.OFF);
        e.deskConfig(deskCfg);
        List<String> problems = e.recover();
        assertFalse(problems.isEmpty(), "recovery reports the desk's state as a problem");
        assertTrue(problems.get(0).contains("058-nx-desk"), problems.get(0));
        assertNull(e.desk(), "no desk without its rate");
        assertTrue(e.halted(), "and nothing trades until the table is there");
        assertTrue(e.status().get(0).contains("HALTED"), e.status().get(0));
    }

    @Test
    void theDeskStateRoundTripsThroughItsJson() {
        DeskConfig cfg = DeskConfig.defaults().on();
        Desk d = Desk.start(cfg, null, null, now.get());
        d.recordFill(1, now.get(), 2, Side.BUY, 1_000, d.sell(), 5_100_000);
        now.addAndGet(HOUR);
        assertEquals(7, d.tick(now.get(), 100_000), "1,000 NX of a 100,000 float is 1%: 7 bps");
        String json = Desk.encode(d.state());
        Desk again = Desk.start(cfg, Desk.decode(json), null, now.get());
        assertEquals(d.mid(), again.mid(), "R comes back");
        assertEquals(d.movedTodayPct(), again.movedTodayPct(), "and the day's anchor");
        assertEquals(d.lastStepBps(), again.lastStepBps(), "and the last step");
        assertEquals(1, again.fillsOf(2, 20).size(), "and the fill log");
        assertEquals(2, again.hours().size(), "the fill's hour and the ticked hour");
        assertEquals(1_000, again.hours().get(0).volumeNx());
        assertEquals(d.buy(), again.hours().get(1).buy(), "and the quote it stepped to");
    }

    @Test
    void theSwitchOffLeavesTodaysBehaviourExactly() throws Exception {
        Exchange e = start(DeskConfig.defaults(), HOUSE);
        assertNull(e.desk(), "no desk object");
        assertEquals("off", e.closeReason());
        assertFalse(e.deskOpen());
        assertTrue(e.status().stream().noneMatch(l -> l.contains("NX desk")), "status is unchanged");
        assertNull(ExchangeSite.desk(e, now.get()), "no nx-desk.json content (the runner deletes the file)");
        Map<String, Object> ticker = ExchangeSite.tickerJson(e, e.ticker(Ticker.NX), 5, 5);
        assertEquals("on", ticker.get("book"), "the NX book is on");
        assertNull(e.officialRate(), "no desk: the rate is the book's VWAP (none yet)");
        Response sell = e.place(new Place("o1", 3, 13, Side.SELL, 5_000_000, 1_000, Ticker.NX));
        assertTrue(sell.ok(), sell.toString());
        now.addAndGet(HOUR + 1);
        assertEquals(0, e.tickDesk(), "no drift while the desk is off");
    }

    @Test
    void theDeskFileIsApiMdShape() throws Exception {
        Exchange e = start(deskCfg, HOUSE);
        seedDesk(60_000, 40_000_000);
        Response pv = preview(e, 2, Side.BUY, 1_000);
        assertTrue(e.confirm("c1", 2, (String) pv.data().get("token")).ok());
        now.addAndGet(HOUR + 1);
        e.tickDesk();

        Map<String, Object> m = ExchangeSite.desk(e, now.get());
        assertEquals(Set.of("schema", "generated_at", "open", "closed_reason", "mid", "buy", "sell", "spread_bps",
                "fee_bps", "drift_bps_last_hour", "day_cap_pct", "moved_today_pct", "floor", "ceil", "desk_nx",
                "selling", "min_qty", "history"), m.keySet());
        assertEquals(1, m.get("schema"));
        assertTrue(((String) m.get("generated_at")).endsWith("Z"));
        assertEquals(true, m.get("open"));
        assertNull(m.get("closed_reason"));
        long mid = (Long) m.get("mid");
        assertEquals(e.desk().mid(), mid);
        assertEquals(e.desk().buy(), m.get("buy"), "the desk pays 2% under R");
        assertEquals(e.desk().sell(), m.get("sell"), "and charges 2% over it");
        assertEquals(5_000_500L, mid, "the hour ticked R up 1 bp on the 1,000 NX the house sold");
        assertEquals(200, m.get("spread_bps"));
        assertEquals(0, m.get("fee_bps"));
        assertEquals(20, m.get("day_cap_pct"));
        assertEquals(NxGuard.NX_LOWEST, m.get("floor"));
        assertEquals(NxGuard.NX_HIGHEST, m.get("ceil"));
        assertEquals(59_000L, m.get("desk_nx"));
        assertEquals(true, m.get("selling"));
        assertEquals(100L, m.get("min_qty"));
        assertTrue(m.get("drift_bps_last_hour") instanceof Number);
        assertTrue(m.get("moved_today_pct") instanceof Number);
        List<?> hist = (List<?>) m.get("history");
        assertFalse(hist.isEmpty(), "the hour that just closed is in the history");
        for (Object o : hist) {
            assertEquals(Set.of("hour", "mid", "buy", "sell", "volume_nx", "fills"), ((Map<?, ?>) o).keySet());
            assertTrue(((String) ((Map<?, ?>) o).get("hour")).endsWith("Z"));
        }
        assertTrue(hist.stream().anyMatch(o -> Long.valueOf(1_000).equals(((Map<?, ?>) o).get("volume_nx"))),
                "the hour the 1,000 NX trade happened in is in it: " + hist);
        assertTrue(hist.stream().anyMatch(o -> Integer.valueOf(1).equals(((Map<?, ?>) o).get("fills"))));
        // the file serialises (Json.write is what the runner uses)
        assertTrue(Json.write(m).contains("\"buy\":"));
    }

    /** One site request body with today's sign-in stamps (the stolen-cookie guard wants {@code siteAuthAt}). */
    String req(String body) {
        String at = java.time.Instant.ofEpochMilli(now.get()).toString();
        return "[{\"createdAt\":\"" + at + "\",\"siteAuthAt\":\"" + at + "\"," + body + "}]";
    }

    /** The site's answer to one request ({@code body} is the request's own fields). */
    Response site(Exchange e, String body) {
        List<ExchangeSite.Request> rs = ExchangeSite.requests(req(body));
        assertEquals(1, rs.size());
        return ExchangeSite.answer(e, rs.get(0), now.get());
    }

    @Test
    void theSitePreviewsWithoutAPriceAndRefusesOne() throws Exception {
        Exchange e = start(deskCfg, HOUSE);
        seedDesk(10_000, 20_000_000);
        List<ExchangeSite.Request> reqs = ExchangeSite.requests(req(
                "\"id\":\"a\",\"account\":2,\"type\":\"preview\",\"character\":12,\"side\":\"BUY\",\"nx\":1000"));
        assertNull(reqs.get(0).error(), "an NX preview needs no price");
        Response pv = ExchangeSite.answer(e, reqs.get(0), now.get());
        assertTrue(pv.ok(), pv.toString());
        assertEquals(5_100_000L, pv.data().get("price"));

        Response bad = site(e, "\"id\":\"b\",\"account\":2,\"type\":\"preview\",\"character\":12,\"side\":\"BUY\","
                + "\"price\":5000000,\"nx\":1000");
        assertEquals("BAD_REQUEST", bad.code());
        assertTrue(bad.message().contains("quote"), bad.message());

        String token = (String) pv.data().get("token");
        Response f = site(e, "\"id\":\"c\",\"account\":2,\"type\":\"confirm\",\"token\":\"" + token + "\"");
        assertTrue(f.ok(), f.toString());
        assertEquals(1_000L, ((Map<?, ?>) f.data().get("fill")).get("nx"));

        Response m = site(e, "\"id\":\"m\",\"account\":2,\"type\":\"mine\"");
        assertTrue(m.ok(), m.toString());
        assertEquals(NX0 + 1_000, m.data().get("nx"));
        assertEquals(1, ((List<?>) m.data().get("fills")).size());
        assertTrue(((List<?>) m.data().get("orders")).isEmpty(), "the NX book is off: nothing rests");
        assertNotNull(m.data().get("stepUpFreeMesos"));

        assertEquals("MIN_QTY", site(e, "\"id\":\"s\",\"account\":2,\"type\":\"preview\",\"character\":12,"
                + "\"side\":\"SELL\",\"nx\":50").code());
        assertEquals("OK", site(e, "\"id\":\"u\",\"account\":2,\"type\":\"preview\",\"character\":12,"
                + "\"side\":\"SELL\",\"nx\":150").code(), "whole NX: 150 is fine, the minimum is 100");
        assertEquals("BAD_REQUEST", site(e, "\"id\":\"v\",\"account\":2,\"type\":\"preview\",\"character\":12,"
                + "\"side\":\"BUY\",\"nx\":9000000000000000").code(), "more NX than the mesos maths hold: refused");
        conserved(e);
    }

    @Test
    void theSiteSaysDeskClosedWithoutAHouseAccount() throws Exception {
        Exchange e = start(deskCfg, 0);
        Response r = site(e, "\"id\":\"a\",\"account\":2,\"type\":\"preview\",\"character\":12,\"side\":\"BUY\","
                + "\"nx\":1000");
        assertEquals("DESK_CLOSED", r.code());
        assertTrue(r.message().contains("no house account"), r.message());
    }

    @Test
    void theDeskMovesItsMoneyBetweenTheHouseAccountAndItsHouse() throws Exception {
        Exchange e = start(deskCfg, HOUSE);
        store.login(2, 12);
        Response notHouse = e.fundDeskLive(store.finder().find(2), Asset.MESO, 10_000_000, false);
        assertEquals("NOT_HOUSE", notHouse.code(), "only the house account funds the desk");

        Session house = store.login(HOUSE, HOUSE_CHAR);
        assertTrue(e.fundDeskLive(store.finder().find(HOUSE), Asset.MESO, 10_000_000, false).ok());
        assertEquals(10_000_000, store.balance(Exchange.deskBucket(Asset.MESO)));
        assertEquals(0, store.balance(Exchange.deskBucket(Asset.NX)));
        assertFalse((Boolean) ExchangeSite.desk(e, now.get()).get("selling"), "no NX to sell yet");
        assertTrue(e.fundDeskLive(store.finder().find(HOUSE), Asset.NX, 2_000, false).ok());
        assertEquals(2_000, store.balance(Exchange.deskBucket(Asset.NX)));
        assertTrue((Boolean) ExchangeSite.desk(e, now.get()).get("selling"));

        // a logged-in player buys 1,000 NX from the desk: its mesos up, its NX down
        Response buy = previewAndConfirm(e, 2, Side.BUY, 1_000);
        assertTrue(buy.ok(), buy.toString());
        assertEquals(1_000, store.balance(Exchange.deskBucket(Asset.NX)));
        assertEquals(15_100_000, store.balance(Exchange.deskBucket(Asset.MESO)));

        // the house takes it all back: the buckets empty, the live wallet has it
        long nxBefore = house.nx;
        Response unfund = e.unfundDeskLive(store.finder().find(HOUSE), false);
        assertTrue(unfund.ok(), unfund.toString());
        assertEquals(0, store.balance(Exchange.deskBucket(Asset.NX)));
        assertEquals(0, store.balance(Exchange.deskBucket(Asset.MESO)));
        assertEquals(nxBefore + 1_000, house.nx, "the NX left is back on the house account");
        assertEquals(1_000_000_000L - 10_000_000 + 15_100_000, (long) house.meso.get(HOUSE_CHAR),
                "and the mesos on its character (it funded 10,000,000 and took back 15,100,000)");
        assertFalse((Boolean) ExchangeSite.desk(e, now.get()).get("selling"), "nothing left: the desk stops selling");
        assertEquals("NOT_HOUSE", e.unfundDeskLive(store.finder().find(2), false).code());
        assertTrue(Exchange.reconcile(store.load(10)).isEmpty());
    }
}
