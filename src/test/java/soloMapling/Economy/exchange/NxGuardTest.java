package soloMapling.Economy.exchange;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Exchange.Place;
import soloMapling.Economy.exchange.Exchange.Response;
import soloMapling.Economy.exchange.OrderBook.Side;
import soloMapling.Economy.exchange.Ledger.Asset;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.1 stolen-cookie guard, the game's side (owner 2026-10-29: both guards): NX orders inside a band around the
 * official rate (a fixed floor and ceiling before there is one) and site
 * requests refused unless the player signed in on the site within 12 h. No daily caps (owner 2026-10-29).
 */
class NxGuardTest {

    static final long NX0 = 100_000, MESO0 = 2_000_000_000L;
    static final long H = 3_600_000L;

    MemoryExchangeStore store;
    AtomicLong now;
    Exchange ex;
    int req;

    @BeforeEach
    void setUp() throws Exception {
        store = new MemoryExchangeStore();
        for (long a = 1; a <= 4; a++) {
            store.addAccount(a, NX0);
            store.addCharacter(10 + a, a, MESO0);
        }
        now = new AtomicLong(1_800_000_000_000L);
        ex = new Exchange(store, ExchangeLiveTest.cfg(100, 0), store::online, store.finder(), now::get);
        assertTrue(ex.recover().isEmpty());
        ex.guard(NxGuard.defaults());
        // 1.6.2 b005: every account signed in with its password just now (the game's own record)
        ex.signIns(new SiteReauth.SignIns());
        for (long a = 1; a <= 4; a++) {
            ex.signIns().record(a, now.get());
        }
    }

    Response place(long account, Side side, long price, long nx) {
        now.addAndGet(11_000);
        return ex.place(new Place("g" + (++req), account, 10 + account, side, price, nx));
    }

    /** Three player-to-player fills at {@code price}: the official rate (the test config needs one fill). */
    void rateAt(long price) {
        NxGuard g = ex.guard();
        ex.guard(NxGuard.OFF); // set the scene at any price
        for (int i = 0; i < 3; i++) {
            assertTrue(place(1, Side.SELL, price, 100).ok());
            assertTrue(place(2, Side.BUY, price, 100).ok());
        }
        assertEquals(price, ex.officialRate());
        ex.guard(g);
    }

    // ---------------------------------------------------------------- the band

    @Test
    void withARateAnNxSellBelowHalfAndABuyAboveDoubleAreRefusedWithTheRange() {
        // a rate high in the window, where the relative floor (50%) still binds above the window's own floor
        rateAt(7_000_000);
        assertEquals(3_500_000, ex.nxBand()[0], "50% of the rate");
        assertEquals(10_000_000, ex.nxBand()[1], "200% of 7,000,000 is 14,000,000: the window caps it");
        Response dump = place(3, Side.SELL, 3_499_000, 1_000);
        assertEquals("PRICE_BAND", dump.code());
        assertEquals(3_500_000L, dump.data().get("minPrice"));
        assertEquals(10_000_000L, dump.data().get("maxPrice"));
        // owner 2026-10-29: the text is per 1 NX, the data per 1,000 NX
        assertEquals("an NX sell must ask at least 3,500 mesos per NX (the official rate is 7,000 mesos per NX)",
                dump.message());
        assertEquals(NX0, store.nx(3), "nothing left the account");
        assertTrue(place(3, Side.SELL, 3_500_000, 100).ok(), "half the rate is still fine");
        assertEquals("PRICE_BAND", place(4, Side.BUY, 10_001_000, 100).code(), "the window, not 200%, is the ceiling");
        assertTrue(place(4, Side.BUY, 10_000_000, 100).ok());
        // a BUY at the window floor and a SELL at its ceiling only rest (they are inside the window)
        assertTrue(place(4, Side.BUY, 2_500_000, 100).ok());
        assertTrue(place(3, Side.SELL, 9_999_999, 100).ok());
    }

    @Test
    void withALowRateInsideTheWindowTheBuysCeilingIsStill200Percent() {
        // rate 4,000,000: 50% is 2,000,000 (under the window floor, so the window holds) and 200% is 8,000,000
        rateAt(4_000_000);
        assertEquals(2_500_000, ex.nxBand()[0], "the window floor, not 50% of the rate");
        assertEquals(8_000_000, ex.nxBand()[1], "200% of the rate, inside the window");
        Response over = place(4, Side.BUY, 8_001_000, 100);
        assertEquals("PRICE_BAND", over.code());
        assertEquals(8_000_000L, over.data().get("maxPrice"));
        assertEquals("an NX buy may bid at most 8,000 mesos per NX (the official rate is 4,000 mesos per NX)",
                over.message());
        assertTrue(place(4, Side.BUY, 8_000_000, 100).ok());
    }

    @Test
    void thePreviewIsRefusedTooSoTheSiteAndAtNxSeeTheRangeBeforeConfirming() {
        rateAt(4_000_000);
        now.addAndGet(11_000);
        Response pv = ex.preview(new Place("p", 3, 13, Side.SELL, 10_000, 5_000, "NX"));
        assertEquals("PRICE_BAND", pv.code());
        assertEquals(2_500_000L, pv.data().get("minPrice"));
        assertEquals(8_000_000L, pv.data().get("maxPrice"));
    }

    @Test
    void withoutARateTheBandIsAroundTheStartingPriceOf5000MesosPerNx() {
        // owner 2026-10-29: until players set a rate NX counts as 5,000 mesos per NX (the NX merchant bots' price);
        // the band is the usual 50%..200% of it (this replaced the 100,000 floor and the 10,000,000 ceiling knob)
        assertNull(ex.officialRate());
        assertEquals(2_500_000, ex.nxBand()[0]);
        assertEquals(10_000_000, ex.nxBand()[1]);
        Response dump = place(3, Side.SELL, 2_499_000, 1_000);
        assertEquals("PRICE_BAND", dump.code());
        assertEquals(2_500_000L, dump.data().get("minPrice"));
        assertNull(dump.data().get("rate"));
        assertEquals("an NX sell must ask at least 2,500 mesos per NX (no official rate yet: NX counts as 5,000 "
                + "mesos per NX)", dump.message());
        assertEquals("PRICE_BAND", place(3, Side.SELL, 100_000, 100).code(), "the old 100,000 floor is gone");
        assertTrue(place(3, Side.SELL, 2_500_000, 100).ok());
        // TEST 2026-10-29: before a rate a site buy had no ceiling; now 200% of the starting price
        Response drain = place(4, Side.BUY, 50_000_000, 100);
        assertEquals("PRICE_BAND", drain.code());
        assertEquals(10_000_000L, drain.data().get("maxPrice"));
        assertEquals("an NX buy may bid at most 10,000 mesos per NX (no official rate yet: NX counts as 5,000 "
                + "mesos per NX)", drain.message());
        assertTrue(place(4, Side.BUY, 10_000_000, 100).ok());
    }

    @Test
    void theOfficialRateIsASevenDayWindow() {
        // owner 2026-10-29: 7 days, at least 3 counted trades (was 24 h)
        assertEquals(168L * H, ExchangeConfig.defaults().rateWindowMs());
        assertEquals(3, ExchangeConfig.defaults().rateMinFills());
        ExchangeConfig d = ExchangeConfig.defaults();
        ExchangeConfig c = new ExchangeConfig(true, d.lotNx(), d.minNx(), d.maxNx(), d.minPrice(), d.maxPrice(), 100,
                1000, 100_000, 0, d.expiryMs(), 3, d.regularSpreadBps(), d.regularNx(), d.rateWindowMs(),
                d.rateMinFills(), d.pollSeconds(), 0, 60_000);
        ex = new Exchange(store, c, store::online, store.finder(), now::get);
        try {
            assertTrue(ex.recover().isEmpty());
        } catch (ExchangeStore.StoreException e) {
            throw new AssertionError(e);
        }
        ex.guard(NxGuard.defaults());
        for (int i = 0; i < 3; i++) {
            assertTrue(place(1, Side.SELL, 4_000_000, 100).ok());
            assertTrue(place(2, Side.BUY, 4_000_000, 100).ok());
        }
        now.addAndGet(3 * 24 * H);
        assertEquals(4_000_000L, ex.officialRate(), "three days later the rate still stands");
        assertEquals(2_500_000, ex.nxBand()[0], "50% of the rate is 2,000,000, under the hard window's floor");
        // after a restart too (the window is rebuilt from the store)
        ex = new Exchange(store, c, store::online, store.finder(), now::get);
        try {
            assertTrue(ex.recover().isEmpty());
        } catch (ExchangeStore.StoreException e) {
            throw new AssertionError(e);
        }
        assertEquals(4_000_000L, ex.officialRate());
        assertNull(ex.stats("NX").change(), "the 24 h change is still a 24 h figure");
        now.addAndGet(5 * 24 * H);
        assertNull(ex.officialRate(), "eight days later: gone");
    }

    @Test
    void atNxTypesAndShowsPricesPerNx() throws Exception {
        store.login(3, 13);
        LiveWallet w = store.finder().find(3);
        now.addAndGet(11_000);
        List<String> pv = ExchangeChat.handle(ex, w, new String[]{"sell", "100", "5000"});
        assertEquals("Preview: sell 100 NX at 5,000 mesos per NX.", pv.get(0));
        List<String> ok = ExchangeChat.handle(ex, w, new String[]{"confirm"});
        assertTrue(ok.get(0).contains("sell 100 NX at 5,000 mesos per NX"), ok.toString());
        assertEquals(5_000_000L, ex.myOrders(3, 1).get(0).price(), "stored per 1,000 NX");
        now.addAndGet(11_000);
        List<String> low = ExchangeChat.handle(ex, w, new String[]{"sell", "100", "2k"});
        assertTrue(low.get(0).contains("at least 2,500 mesos per NX") && low.get(0).endsWith("(PRICE_BAND)"),
                low.toString());
        List<String> market = ExchangeChat.handle(ex, w, new String[]{});
        assertTrue(market.get(0).contains("best ask 5,000") && market.get(0).contains("none yet (NX counts as 5,000"),
                market.toString());
        assertTrue(market.get(1).startsWith("Sell from 2,500, buy up to 10,000 mesos per NX"), market.toString());
        List<String> book = ExchangeChat.handle(ex, w, new String[]{"book"});
        assertEquals("NX exchange book (mesos per NX):", book.get(0));
        assertTrue(book.get(1).contains("ask 5,000  100 NX"), book.toString());
        assertTrue(ExchangeChat.handle(ex, w, new String[]{"orders"}).get(0).contains("at 5,000 per NX"));
        assertEquals(4_500_000L, ExchangeChat.priceNx("4.5k"));
        assertEquals(1_234_567L, ExchangeChat.priceNx("1234.567"));
        assertNull(ExchangeChat.priceNx("1.0001"));
        assertEquals("1234.567", ExchangeChat.typedNx(1_234_567));
        assertEquals("1,235", Exchange.perNx(1_234_567), "display rounds to whole mesos per NX");
    }

    // ---------------------------------------------------------------- the hard window (owner 2026-10-31 ~19:50 UTC)

    @Test
    void theRateCannotWalkPastTheHardCeilingOf10000MesosPerNx() {
        // owner 2026-10-31 ~19:50 UTC: a HARD window, 2,500..10,000 mesos per NX, whatever the official rate does
        rateAt(9_500_000);
        assertEquals(9_500_000L, ex.officialRate());
        assertEquals(4_750_000, ex.nxBand()[0], "the relative floor still applies inside the window");
        assertEquals(10_000_000, ex.nxBand()[1], "200% of 9,500,000 is 19,000,000; the window is the wall");
        // the 2026-10-31 probe: a fill at 9,900,000 used to let a 10,500,000 buy through
        Response over = place(4, Side.BUY, 10_500_000, 100);
        assertEquals("PRICE_BAND", over.code());
        assertEquals(10_000_000L, over.data().get("maxPrice"));
        assertEquals(9_500_000L, over.data().get("rate"));
        // the ceiling itself still fills, and the rate it moves to stays inside the window
        assertTrue(place(3, Side.SELL, 10_000_000, 100).ok(), "a sell at the ceiling is inside");
        assertTrue(place(4, Side.BUY, 10_000_000, 100).ok(), "a buy at the ceiling is inside");
        assertEquals(9_625_000L, ex.officialRate(), "the 7-day average of 9,500,000 x3 and 10,000,000");
        assertTrue(ex.officialRate() <= NxGuard.NX_HIGHEST, "the rate itself stays inside the window");
        assertEquals(10_000_000, ex.nxBand()[1], "and the band cannot go above it");
    }

    @Test
    void anOrderOutsideTheHardWindowIsRefusedOnEitherSideEvenWithTheGuardOff() {
        ex.guard(NxGuard.OFF);
        assertEquals(2_500_000, ex.nxBand()[0], "the window is not a switch: OFF drops the relative band, not the wall");
        assertEquals(10_000_000, ex.nxBand()[1]);
        Response highSell = place(3, Side.SELL, 10_000_001, 100);
        assertEquals("PRICE_BAND", highSell.code());
        assertEquals(10_000_000L, highSell.data().get("maxPrice"));
        assertEquals(2_500_000L, highSell.data().get("minPrice"));
        assertEquals("an NX order must price NX from 2,500 to 10,000 mesos per NX (no official rate yet: NX counts as "
                + "5,000 mesos per NX)", highSell.message());
        assertEquals("PRICE_BAND", place(4, Side.BUY, 2_499_999, 100).code(), "a cheap buy is outside too");
        assertEquals("PRICE_BAND", place(3, Side.SELL, 1_000, 100).code());
        assertEquals("PRICE_BAND", place(4, Side.BUY, 20_000_000, 100).code());
        assertEquals(NX0, store.nx(3), "a refused sell escrowed nothing");
        assertTrue(place(3, Side.SELL, 2_500_000, 100).ok(), "the edges are inside");
        assertTrue(place(4, Side.BUY, 10_000_000, 100).ok());
    }

    @Test
    void aHouseRegularCannotQuoteOutsideTheWindow() throws Exception {
        // the regulars are the house's own quotes and bypass priceBand, so placeOrder itself holds the window
        ex.guard(NxGuard.OFF);
        store.login(1, 11);
        LiveWallet w = store.finder().find(1);
        assertTrue(ex.fundHouseLive(1, w, Asset.MESO, 100_000_000, true).ok());
        assertTrue(ex.fundHouseLive(1, w, Asset.NX, 10_000, true).ok());
        now.addAndGet(11_000);
        Response high = ex.placeRegularForQa(1, Side.BUY, 20_000_000, 1_000);
        assertEquals("PRICE_BAND", high.code());
        assertEquals(10_000_000L, high.data().get("maxPrice"));
        assertEquals("PRICE_BAND", ex.placeRegularForQa(1, Side.SELL, 2_000_000, 1_000).code());
        assertTrue(ex.placeRegularForQa(1, Side.BUY, 9_000_000, 1_000).ok(), "inside the window still rests");
    }

    @Test
    void theBandIsOnlyForNxAndOffWhenSwitchedOff() {
        // OFF drops the relative band; the hard window is not a switch (owner 2026-10-31 ~19:50 UTC)
        ex.guard(NxGuard.OFF);
        assertEquals(2_500_000, ex.nxBand()[0]);
        assertEquals(10_000_000, ex.nxBand()[1]);
        assertEquals("PRICE_BAND", place(3, Side.SELL, 1_000, 100).code(), "outside the window");
        assertTrue(place(3, Side.SELL, 2_500_000, 100).ok(), "inside it");
        // and it is only NX: a Leaf bid at 1,000 is the ticker's business, not the NX window's
        assertTrue(ex.place(new Place("leaf", 3, 13, Side.BUY, 1_000, 1, "LEAF")).ok());
    }

    @Test
    void theNxQaCheckSellsInsideTheBand() {
        rateAt(4_000_000);
        store.login(3, 13);
        LiveWallet w = store.finder().find(3);
        now.addAndGet(11_000);
        ExchangeQa.Staged st = ExchangeQa.stage(ex, w);
        assertTrue(st.bidPrice() >= NxGuard.NX_LOWEST, "staged inside the window, at " + st.bidPrice());
        now.addAndGet(11_000);
        assertTrue(ex.preview(new Place("q", 3, 13, Side.SELL, st.bidPrice(), ExchangeQa.NX, "NX")).ok());
        assertTrue(ex.confirmLatest("qc", 3).ok());
        assertEquals("OK", ExchangeQa.teardown(ex, w).get("code"));
    }

    // ---------------------------------------------------------------- no daily caps (owner 2026-10-29)

    Response siteSell(long account, long price, long nx) {
        return siteOrder(account, "SELL", price, nx);
    }

    Response siteBuy(long account, long price, long nx) {
        return siteOrder(account, "BUY", price, nx);
    }

    Response siteOrder(long account, String side, long price, long nx) {
        now.addAndGet(11_000);
        String created = java.time.Instant.ofEpochMilli(now.get()).toString();
        ExchangeSite.Request pv = ExchangeSite.requests("[{\"id\":\"sp" + (++req) + "\",\"account\":" + account
                + ",\"type\":\"preview\",\"character\":" + (10 + account) + ",\"side\":\"" + side + "\",\"price\":" + price
                + ",\"nx\":" + nx + ",\"siteAuthAt\":" + (now.get() - H) + ",\"createdAt\":\"" + created + "\"}]")
                .get(0);
        Response p = ExchangeSite.answer(ex, pv, now.get());
        if (!p.ok()) {
            return p;
        }
        ExchangeSite.Request c = ExchangeSite.requests("[{\"id\":\"sc" + (++req) + "\",\"account\":" + account
                + ",\"type\":\"confirm\",\"token\":\"" + p.data().get("token") + "\",\"siteAuthAt\":" + (now.get() - H)
                + ",\"createdAt\":\"" + created + "\"}]").get(0);
        Response r = ExchangeSite.answer(ex, c, now.get());
        if ("PASSWORD_REQUIRED".equals(r.code())) { // 1.6.2 b005: the player types the password on the site
            assertTrue(ex.approve(account, (String) p.data().get("token")).ok());
            r = ExchangeSite.answer(ex, c, now.get());
        }
        return r;
    }

    @Test
    void theSiteHasNoDailyCapsOnlyTheBand() {
        // owner 2026-10-29: no daily caps; a stolen session is held by the band and the 12 h sign-in instead
        for (int i = 0; i < 3; i++) {
            assertTrue(siteSell(3, 5_000_000, 10_000).ok(), "sell " + i);
            assertTrue(siteBuy(4, 6_000_000, 20_000).ok(), "buy " + i + " (120m each)");
        }
        long rate = ex.officialRate();
        assertEquals(Math.min(rate * 2, NxGuard.NX_HIGHEST), ex.nxBand()[1], "200% of the rate, never past the window");
        assertEquals("PRICE_BAND", siteBuy(4, ex.nxBand()[1] + 1_000, 100).code(), "but never over it");
    }

    @Test
    void theSiteGetsThePriceBandWithAClearMessageOnceThe7DayRateExists() {
        rateAt(4_000_000);
        Response dump = siteSell(3, 1_000_000, 1_000);
        assertEquals("PRICE_BAND", dump.code());
        assertEquals("an NX sell must ask at least 2,500 mesos per NX (the official rate is 4,000 mesos per NX)",
                dump.message());
        assertEquals(2_500_000L, dump.data().get("minPrice"));
        assertEquals(8_000_000L, dump.data().get("maxPrice"));
        assertEquals(4_000_000L, dump.data().get("rate"));
        Response over = siteBuy(4, 8_001_000, 100);
        assertEquals("PRICE_BAND", over.code());
        assertEquals("an NX buy may bid at most 8,000 mesos per NX (the official rate is 4,000 mesos per NX)",
                over.message());
        assertEquals(NX0, store.nx(3), "nothing left the account");
    }

    // ---------------------------------------------------------------- the site sign-in age

    ExchangeSite.Request mine(String authField) {
        return ExchangeSite.requests("[{\"id\":\"m" + (++req) + "\",\"account\":1,\"type\":\"mine\"" + authField
                + ",\"createdAt\":" + now.get() + "}]").get(0);
    }

    @Test
    void aSiteRequestNeedsASignInFromTheLast12Hours() {
        long t = now.get();
        Response missing = ExchangeSite.answer(ex, mine(""), t);
        assertEquals("REAUTH", missing.code());
        assertEquals(12L, missing.data().get("maxAgeHours"));
        assertEquals("REAUTH", ExchangeSite.answer(ex, mine(",\"siteAuthAt\":" + (t - 13 * H)), t).code());
        assertEquals("REAUTH", ExchangeSite.answer(ex, mine(",\"siteAuthAt\":" + (t + 10 * 60_000L)), t).code(),
                "not from the future");
        assertTrue(ExchangeSite.answer(ex, mine(",\"siteAuthAt\":" + (t - 11 * H)), t).ok());
        assertTrue(ExchangeSite.answer(ex, mine(",\"siteAuthAt\":\""
                + java.time.Instant.ofEpochMilli(t - H) + "\""), t).ok(), "ISO-8601 too");
        // the public reads need none
        ExchangeSite.Request book = ExchangeSite.requests("[{\"id\":\"b\",\"account\":1,\"type\":\"book\","
                + "\"createdAt\":" + t + "}]").get(0);
        assertTrue(ExchangeSite.answer(ex, book, t).ok());
        // a preview and a cancel are refused before anything happens
        ExchangeSite.Request pv = ExchangeSite.requests("[{\"id\":\"p\",\"account\":3,\"type\":\"preview\","
                + "\"character\":13,\"side\":\"SELL\",\"price\":1000000,\"nx\":100,\"createdAt\":" + t + "}]").get(0);
        assertEquals("REAUTH", ExchangeSite.answer(ex, pv, t).code());
        ExchangeSite.Request cancel = ExchangeSite.requests("[{\"id\":\"c\",\"account\":3,\"type\":\"cancel\","
                + "\"orderId\":1,\"siteAuthAt\":" + (t - 20 * H) + ",\"createdAt\":" + t + "}]").get(0);
        assertEquals("REAUTH", ExchangeSite.answer(ex, cancel, t).code());
        // switched off
        ex.guard(NxGuard.defaults().withSiteAuthMaxMs(0));
        assertTrue(ExchangeSite.answer(ex, mine(""), t).ok());
    }

    @Test
    void theDefaultsAreTheOwnersAndReadFromTheJvm() {
        NxGuard d = NxGuard.defaults();
        assertEquals(50, d.minPct());
        assertEquals(200, d.maxPct());
        assertEquals(5_000_000, d.startPrice());
        assertEquals(12 * H, d.siteAuthMaxMs());
        System.setProperty("slums.exchange.nxBand.minPct", "60");
        System.setProperty("slums.exchange.siteAuthMaxHours", "0");
        System.setProperty("slums.exchange.nxStartPrice", "4000000");
        try {
            NxGuard s = NxGuard.fromSystem();
            assertEquals(60, s.minPct());
            assertEquals(0, s.siteAuthMaxMs());
            assertEquals(4_000_000, s.startPrice());
            assertEquals(8_000_000, s.band(null, 1_000, 100_000_000)[1], "the ceiling follows the starting price");
        } finally {
            System.clearProperty("slums.exchange.nxBand.minPct");
            System.clearProperty("slums.exchange.siteAuthMaxHours");
            System.clearProperty("slums.exchange.nxStartPrice");
        }
    }

}
