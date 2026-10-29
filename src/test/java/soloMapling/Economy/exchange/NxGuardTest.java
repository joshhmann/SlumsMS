package soloMapling.Economy.exchange;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Exchange.Place;
import soloMapling.Economy.exchange.Exchange.Response;
import soloMapling.Economy.exchange.OrderBook.Side;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.1 stolen-cookie guard, the game's side (owner 2026-10-29: both guards): NX orders inside a band around the
 * official rate (a fixed floor before there is one), a cap on NX sold through the site per account per day, and site
 * requests refused unless the player signed in on the site within 12 h.
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
    }

    Response place(long account, Side side, long price, long nx) {
        now.addAndGet(11_000);
        return ex.place(new Place("g" + (++req), account, 10 + account, side, price, nx));
    }

    /** Three player-to-player fills at {@code price}: the official rate (the test config needs one fill). */
    void rateAt(long price) {
        for (int i = 0; i < 3; i++) {
            assertTrue(place(1, Side.SELL, price, 100).ok());
            assertTrue(place(2, Side.BUY, price, 100).ok());
        }
        assertEquals(price, ex.officialRate());
    }

    // ---------------------------------------------------------------- the band

    @Test
    void withARateAnNxSellBelowHalfAndABuyAboveDoubleAreRefusedWithTheRange() {
        rateAt(1_000_000);
        assertEquals(500_000, ex.nxBand()[0]);
        assertEquals(2_000_000, ex.nxBand()[1]);
        Response dump = place(3, Side.SELL, 499_000, 1_000);
        assertEquals("PRICE_BAND", dump.code());
        assertEquals(500_000L, dump.data().get("minPrice"));
        assertEquals(2_000_000L, dump.data().get("maxPrice"));
        assertTrue(dump.message().contains("500,000") && dump.message().contains("1,000,000"), dump.message());
        assertEquals(NX0, store.nx(3), "nothing left the account");
        assertTrue(place(3, Side.SELL, 500_000, 100).ok(), "half the rate is still fine");
        assertEquals("PRICE_BAND", place(4, Side.BUY, 2_001_000, 100).code());
        assertTrue(place(4, Side.BUY, 2_000_000, 100).ok());
        // a low BUY and a high SELL only rest
        assertTrue(place(4, Side.BUY, 1_000, 100).ok());
        assertTrue(place(3, Side.SELL, 100_000_000, 100).ok());
    }

    @Test
    void thePreviewIsRefusedTooSoTheSiteAndAtNxSeeTheRangeBeforeConfirming() {
        rateAt(1_000_000);
        now.addAndGet(11_000);
        Response pv = ex.preview(new Place("p", 3, 13, Side.SELL, 10_000, 5_000, "NX"));
        assertEquals("PRICE_BAND", pv.code());
        assertEquals(500_000L, pv.data().get("minPrice"));
    }

    @Test
    void withoutARateTheFixedFloorHolds() {
        assertNull(ex.officialRate());
        assertEquals(100_000, ex.nxBand()[0]);
        assertEquals("PRICE_BAND", place(3, Side.SELL, 1_000, 50_000).code(), "a 1-meso-per-NX dump");
        assertTrue(place(3, Side.SELL, 100_000, 100).ok());
        assertTrue(place(4, Side.BUY, 50_000_000, 100).ok(), "no rate: buys keep the ticker's range");
    }

    @Test
    void theBandIsOnlyForNxAndOffWhenSwitchedOff() {
        ex.guard(NxGuard.OFF);
        assertTrue(place(3, Side.SELL, 1_000, 100).ok());
        assertEquals(1_000, ex.nxBand()[0]);
    }

    @Test
    void theNxQaCheckSellsInsideTheBand() {
        rateAt(1_000_000);
        store.login(3, 13);
        LiveWallet w = store.finder().find(3);
        now.addAndGet(11_000);
        ExchangeQa.Staged st = ExchangeQa.stage(ex, w);
        assertTrue(st.bidPrice() >= 500_000, "staged at " + st.bidPrice());
        now.addAndGet(11_000);
        assertTrue(ex.preview(new Place("q", 3, 13, Side.SELL, st.bidPrice(), ExchangeQa.NX, "NX")).ok());
        assertTrue(ex.confirmLatest("qc", 3).ok());
        assertEquals("OK", ExchangeQa.teardown(ex, w).get("code"));
    }

    // ---------------------------------------------------------------- the site's NX sell cap

    Response siteSell(long account, long price, long nx) {
        now.addAndGet(11_000);
        String created = java.time.Instant.ofEpochMilli(now.get()).toString();
        ExchangeSite.Request pv = ExchangeSite.requests("[{\"id\":\"sp" + (++req) + "\",\"account\":" + account
                + ",\"type\":\"preview\",\"character\":" + (10 + account) + ",\"side\":\"SELL\",\"price\":" + price
                + ",\"nx\":" + nx + ",\"siteAuthAt\":" + (now.get() - H) + ",\"createdAt\":\"" + created + "\"}]")
                .get(0);
        Response p = ExchangeSite.answer(ex, pv, now.get());
        if (!p.ok()) {
            return p;
        }
        ExchangeSite.Request c = ExchangeSite.requests("[{\"id\":\"sc" + (++req) + "\",\"account\":" + account
                + ",\"type\":\"confirm\",\"token\":\"" + p.data().get("token") + "\",\"siteAuthAt\":" + (now.get() - H)
                + ",\"createdAt\":\"" + created + "\"}]").get(0);
        return ExchangeSite.answer(ex, c, now.get());
    }

    @Test
    void theSiteSellsAtMostTheDaysCapOfNxPerAccount() {
        Response a = siteSell(3, 1_000_000, 6_000);
        assertTrue(a.ok(), a.toString());
        Response b = siteSell(3, 1_000_000, 4_000);
        assertTrue(b.ok(), b.toString());
        Response over = siteSell(3, 1_000_000, 100);
        assertEquals("LIMIT_NX_DAY", over.code());
        assertEquals(10_000L, over.data().get("capNx"));
        assertEquals(10_000L, over.data().get("usedNx"));
        // another account has its own cap; the game's @nx is not counted
        assertTrue(siteSell(4, 1_000_000, 10_000).ok());
        now.addAndGet(11_000);
        Response game = ex.preview(new Place("ig", 3, 13, Side.SELL, 1_000_000, 100, "NX"));
        assertTrue(game.ok(), game.toString());
        assertTrue(ex.confirmLatest("igc", 3).ok(), "@nx (a \"t-\" preview) is not counted");
        // a day later the cap is fresh
        now.addAndGet(25 * H);
        assertTrue(siteSell(3, 1_000_000, 100).ok());
    }

    @Test
    void aCancelledUnfilledSiteSellGivesItsNxBackToTheCap() {
        Response a = siteSell(3, 1_000_000, 10_000);
        assertTrue(a.ok(), a.toString());
        long id = ((Number) a.data().get("orderId")).longValue();
        assertTrue(ex.cancel("x", 3, id).ok());
        assertTrue(siteSell(3, 1_000_000, 10_000).ok());
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
        assertEquals(100_000, d.floorPrice());
        assertEquals(10_000, d.siteSellNxPerDay());
        assertEquals(12 * H, d.siteAuthMaxMs());
        System.setProperty("slums.exchange.nxBand.minPct", "60");
        System.setProperty("slums.exchange.siteAuthMaxHours", "0");
        try {
            NxGuard s = NxGuard.fromSystem();
            assertEquals(60, s.minPct());
            assertEquals(0, s.siteAuthMaxMs());
        } finally {
            System.clearProperty("slums.exchange.nxBand.minPct");
            System.clearProperty("slums.exchange.siteAuthMaxHours");
        }
    }
}
