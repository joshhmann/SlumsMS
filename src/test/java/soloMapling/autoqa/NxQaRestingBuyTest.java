package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.Exchange.Place;
import soloMapling.Economy.exchange.ExchangeConfig;
import soloMapling.Economy.exchange.Ledger.Asset;
import soloMapling.Economy.exchange.MemoryExchangeStore;
import soloMapling.Economy.exchange.NxGuard;
import soloMapling.Economy.exchange.OrderBook.Side;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * b264: t-xc-1 rests an NX buy on the book and cancels it. It was priced at the ticker's raw configured minimum
 * ({@code ExchangeConfig.minPrice()}, 1,000 mesos per 1,000 NX), which the hard window
 * ({@link NxGuard#NX_LOWEST}..{@link NxGuard#NX_HIGHEST}, 2,500..10,000 mesos per 1,000 NX, owner 2026-10-31 ~19:50
 * UTC) refuses with PRICE_BAND, so the check never placed the order it exists to check. The check prices at the
 * effective band minimum instead ({@code Exchange.nxBand()[0]}, clamp-aware): {@code !qa run xc-1} prints the same
 * order for a staff member to type, so both go through the one helper.
 */
class NxQaRestingBuyTest {

    static final long NX0 = 100_000;
    static final long MESO0 = 2_000_000_000L;

    /** The test server's shape: 100-NX lots, no regulars, no cooldown, the default 1,000..100,000,000 ticker range. */
    static ExchangeConfig cfg() {
        ExchangeConfig d = ExchangeConfig.defaults();
        return new ExchangeConfig(true, d.lotNx(), d.minNx(), d.maxNx(), d.minPrice(), d.maxPrice(), d.feeBps(), 5, 20,
                0, TimeUnit.HOURS.toMillis(72), 0, d.regularSpreadBps(), d.regularNx(), d.rateWindowMs(),
                d.rateMinFills(), d.pollSeconds(), 0, 60_000);
    }

    static Exchange exchange(MemoryExchangeStore store, AtomicLong now) throws Exception {
        Exchange ex = new Exchange(store, cfg(), store::online, store.finder(), now::get);
        assertTrue(ex.recover().isEmpty(), "recover");
        ex.guard(NxGuard.defaults());
        return ex;
    }

    /**
     * The defect itself, on a real book: the raw config minimum is refused (so the old check failed at "the resting
     * buy was not placed"), while the effective band minimum rests. This half passes before the fix too - it is the
     * exchange's behaviour, which the card says must not change - and pins the reason the check had to move.
     */
    @Test
    void theRawConfigMinimumIsRefusedAndTheBandFloorRests() throws Exception {
        MemoryExchangeStore store = new MemoryExchangeStore();
        store.addAccount(1, NX0);
        store.addCharacter(11, 1, MESO0);
        AtomicLong now = new AtomicLong(1_800_000_000_000L);
        Exchange ex = exchange(store, now);
        assertEquals(1_000, ex.config().minPrice(), "the ticker's raw floor");
        assertEquals(2_500_000, ex.nxBand()[0], "the clamp-aware floor");
        assertEquals(2_500_000, NxGuard.NX_LOWEST);

        assertEquals("PRICE_BAND", ex.place(new Place("old", 1, 11, Side.BUY, ex.config().minPrice(), 100)).code(),
                "the price t-xc-1 used to rest its buy at");
        assertEquals(MESO0, store.meso(11), "the refused place escrowed nothing");
        assertTrue(ex.place(new Place("new", 1, 11, Side.BUY, ex.nxBand()[0], 100)).ok(),
                "the effective band minimum rests");
        assertEquals(MESO0 - ex.config().buyTotal(100, ex.nxBand()[0]), store.meso(11));
    }

    /** The body of {@code Probes160.nxExchange(SyntheticPlayer)}, where t-xc-1's steps are written. */
    private static String nxExchangeBody() throws Exception {
        String src = Files.readString(Path.of("src/main/java/soloMapling/autoqa/Probes160.java"));
        int start = src.indexOf("nxExchange(SyntheticPlayer p)");
        assertTrue(start > 0, "Probes160 has no nxExchange probe: t-xc-1 lost its implementation");
        int end = src.indexOf("\n    static ", start);
        return src.substring(start, end < 0 ? src.length() : end);
    }

    /** The body of {@code ExchangeScenarios.InGame.stage}, which prints the {@code !qa run xc-1} steps. */
    private static String qaScenarioBody() throws Exception {
        String src = Files.readString(Path.of("src/main/java/soloMapling/qa/ExchangeScenarios.java"));
        int cls = src.indexOf("final class InGame");
        assertTrue(cls > 0, "ExchangeScenarios has no xc-1 scenario class");
        int start = src.indexOf("public List<String> stage(QaContext ctx)", cls);
        assertTrue(start > 0, "ExchangeScenarios has no xc-1 staging");
        int end = src.indexOf("\n        public ", start);
        return src.substring(start, end < 0 ? src.length() : end);
    }

    /** t-xc-1 (the auto-QA half) prices the resting buy at the band floor, not at the ticker's minimum. */
    @Test
    void theAutoQaRestingBuyIsPricedAtTheBandFloor() throws Exception {
        String body = nxExchangeBody();
        assertFalse(body.contains("cfg.minPrice()"),
                "t-xc-1 still prices its resting buy at the ticker's raw minimum, under the hard window's 2,500");
        assertTrue(body.contains("ExchangeQa.restingBuyPrice(ex)"),
                "t-xc-1 must price its resting buy at the effective band minimum");
    }

    /** !qa run xc-1 prints the same order for a staff member to type: it too must be inside the window. */
    @Test
    void theQaScenarioPrintsTheBandFloorForTheRestingBuy() throws Exception {
        String body = qaScenarioBody();
        assertFalse(body.contains("config().minPrice()"),
                "!qa run xc-1 still prints a resting buy price the exchange refuses");
        assertTrue(body.contains("ExchangeQa.restingBuyPrice(ex)"),
                "!qa run xc-1 must print the effective band minimum");
    }
}
