package soloMapling.Economy.exchange.volatility;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.ExchangeConfig;
import soloMapling.Economy.exchange.ExchangeStore.FillRow;
import soloMapling.Economy.exchange.ExchangeStore.OrderRow;
import soloMapling.Economy.exchange.FakeBotWallet;
import soloMapling.Economy.exchange.Ledger.Asset;
import soloMapling.Economy.exchange.MemoryExchangeStore;
import soloMapling.Economy.exchange.OrderBook.Side;
import soloMapling.Economy.exchange.Ticker;
import soloMapling.Economy.exchange.volatility.TraderBrain.Archetype;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.1 exchange fix (TEST 2026-10-29): LEAF's best bid and last trade sat at 1,110 against a fair value of 500,000.
 * The cause: the LEAF QA check (xc-2) fills 10 leaves at 1,000 mesos each; that print became every trader's
 * reference, the guard (10x of the reference) dropped the value and maker quotes near the fair value, and momentum
 * and panic kept quoting around 1,000. Now the traders only read market information inside the fair band and never
 * quote outside it, and their old off-band quotes are cancelled on their next turn.
 */
class TraderFairBandTest {

    static final long FAIR = 500_000;

    MemoryExchangeStore store;
    AtomicLong now;
    Exchange ex;

    @BeforeEach
    void setUp() throws Exception {
        MarketFlows.get().clear();
        store = new MemoryExchangeStore();
        now = new AtomicLong(1_800_000_000_000L);
        ExchangeConfig d = ExchangeConfig.defaults();
        ExchangeConfig c = new ExchangeConfig(true, d.lotNx(), d.minNx(), d.maxNx(), d.minPrice(), d.maxPrice(), 100,
                d.maxOpen(), d.perDay(), TimeUnit.SECONDS.toMillis(10), d.expiryMs(), 0, d.regularSpreadBps(),
                d.regularNx(), d.rateWindowMs(), 3, d.pollSeconds(), 0, 60_000);
        ex = new Exchange(store, c, a -> false, a -> null, now::get, Ticker.defaults(c));
        assertTrue(ex.recover().isEmpty());
    }

    @AfterEach
    void tearDown() {
        MarketFlows.get().clear();
    }

    static FairValue fair500k() {
        FairValue f = new FairValue(0.5, 0.3);
        f.update(1_800_000_000_000L, MarketFlows.get(), new FairValue.Sources() {
            public long floor(String t) {
                return "LEAF".equals(t) ? FAIR : 3_000;
            }

            public long ceiling(String t, Map<String, Long> fairs) {
                return "LEAF".equals(t) ? 1_200_000 : 12_000;
            }
        });
        return f;
    }

    /** TEST's book: a 1,000-mesos LEAF print and a trader's resting 1,110 bid. */
    void poison(FakeBotWallet seller, FakeBotWallet buyer, FakeBotWallet bidder) {
        assertTrue(ex.placeBot(new Exchange.Place("p1", seller.id, seller.id, Side.SELL, 1_000, 10, "LEAF"), seller)
                .ok());
        now.addAndGet(11_000);
        assertTrue(ex.placeBot(new Exchange.Place("p2", buyer.id, buyer.id, Side.BUY, 1_000, 10, "LEAF"), buyer).ok());
        now.addAndGet(11_000);
        assertTrue(ex.placeBot(new Exchange.Place("p3", bidder.id, bidder.id, Side.BUY, 1_110, 500, "LEAF"), bidder)
                .ok());
        assertEquals(1_000, ex.lastTrades("LEAF", 1).get(0).price());
        now.addAndGet(11_000);
    }

    @Test
    void aTraderDoesNotAnchorOnAnOffBandPrintOrBid() {
        FakeBotWallet a = new FakeBotWallet(1_000_000_001L, 50_000_000).with(Asset.LEAF, 40);
        FakeBotWallet b = new FakeBotWallet(1_000_000_002L, 50_000_000);
        poison(a, b, b);
        TraderBrain.View v = MarketTraders.view(ex, ex.ticker("LEAF"), now.get(), 0, FAIR);
        assertEquals(FAIR, v.reference(), "the 1,000 print is not the reference");
        assertEquals(0, v.bestBid(), "the 1,110 bid is not the best bid the bot sees");
        assertEquals(FAIR, v.fair());
        assertEquals(166_667, v.bandLo());
        assertEquals(1_500_000, v.bandHi());
        // an in-band trade is the reference again
        assertTrue(ex.placeBot(new Exchange.Place("q1", a.id, a.id, Side.SELL, 520_000, 1, "LEAF"), a).ok());
        now.addAndGet(11_000);
        FakeBotWallet c = new FakeBotWallet(1_000_000_003L, 50_000_000);
        assertTrue(ex.placeBot(new Exchange.Place("q2", c.id, c.id, Side.BUY, 520_000, 1, "LEAF"), c).ok());
        assertEquals(520_000, MarketTraders.view(ex, ex.ticker("LEAF"), now.get(), 0, FAIR).reference());
    }

    @Test
    void withoutAFairValueTheStartingPriceAnchorsTheBand() {
        FakeBotWallet a = new FakeBotWallet(1_000_000_001L, 50_000_000).with(Asset.LEAF, 40);
        FakeBotWallet b = new FakeBotWallet(1_000_000_002L, 50_000_000);
        poison(a, b, b);
        TraderBrain.View v = MarketTraders.view(ex, ex.ticker("LEAF"), now.get(), 0, null);
        assertEquals(200_000, v.bandLo(), "a third of LEAF's 600,000 starting price");
        assertTrue(v.reference() >= v.bandLo() && v.reference() <= v.bandHi(), "reference " + v.reference());
        assertTrue(v.fair() >= v.bandLo(), "the 7-day median leaves the 1,000 print out: " + v.fair());
    }

    @Test
    void tradersOnAPoisonedBookQuoteAroundFairAndCancelTheirOldLowBids() {
        FakeBotWallet a = new FakeBotWallet(1_000_000_001L, 50_000_000).with(Asset.LEAF, 40);
        FakeBotWallet b = new FakeBotWallet(1_000_000_002L, 50_000_000);
        List<FakeBotWallet> ws = new ArrayList<>();
        List<MarketTraders.Trader> ts = new ArrayList<>();
        Archetype[] kinds = Archetype.values();
        for (int i = 0; i < 10; i++) {
            FakeBotWallet w = i == 0 ? b : new FakeBotWallet(1_000_000_100L + i, 80_000_000).with(Asset.LEAF, 30);
            ws.add(w);
            ts.add(new MarketTraders.Trader(w.id, kinds[i % kinds.length], 17 + i));
        }
        poison(a, ws.get(1), b);
        long fillsBefore = ex.lastTrades("LEAF", 500).size();
        MarketTraders tr = new MarketTraders(new Random(5));
        tr.use(fair500k(), null);
        MarketConfig cfg = MarketConfig.allOn();
        long[] band = TraderBrain.band(FAIR);
        for (int turn = 0; turn < 60; turn++) {
            for (int i = 0; i < ts.size(); i++) {
                now.addAndGet(11_000);
                tr.act(ex, ts.get(i), ws.get(i), now.get(), cfg, null, null);
            }
            now.addAndGet(5 * 60_000L);
        }
        assertTrue(tr.placedCount() > 20, "the traders traded: " + tr.placedCount());
        List<FillRow> fills = ex.lastTrades("LEAF", 500);
        List<FillRow> fresh = fills.subList(0, (int) (fills.size() - fillsBefore));
        assertFalse(fresh.isEmpty(), "the traders filled against each other");
        for (FillRow f : fresh) {
            assertTrue(f.price() >= band[0] && f.price() <= band[1], "a trader traded at " + f.price());
        }
        for (FakeBotWallet w : ws) {
            for (OrderRow o : ex.openBotOrders(w.id, "LEAF")) {
                assertTrue(o.price() >= band[0] && o.price() <= band[1],
                        w.id + " still quotes " + o.price() + " (" + o.requestId() + ")");
            }
        }
    }
}
