package soloMapling.Economy.exchange.volatility;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.AssetRegistry.Asset;
import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.ExchangeCatalog;
import soloMapling.Economy.exchange.ExchangeConfig;
import soloMapling.Economy.exchange.Ledger.Bucket;
import soloMapling.Economy.exchange.MemoryExchangeStore;
import soloMapling.Economy.exchange.OrderBook.Side;
import soloMapling.Economy.exchange.Ticker;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 270 §6.6: the item maker. A catalog ticker has no bidders, so with {@code -Dslums.exchange.itemMaker=true}
 * the house quotes both sides of it at the fair value ∓ the regular spread — and it can only do that with real items
 * in its HOUSE, which is why funding a regular with an item asset is now allowed (and only with the maker on).
 */
class ExchangeItemMakerTest {

    static final String CATALOG = "slums.exchange.catalog";
    static final String MAKER = "slums.exchange.itemMaker";
    static final long NOW = 1_800_000_000_000L;

    MemoryExchangeStore store;
    AtomicLong now;
    soloMapling.Economy.exchange.Exchange engine;
    Ticker rock;

    static ExchangeConfig cfg() {
        ExchangeConfig d = ExchangeConfig.defaults();
        return new ExchangeConfig(true, d.lotNx(), d.minNx(), d.maxNx(), d.minPrice(), d.maxPrice(), 100, 1_000,
                100_000, 0, TimeUnit.HOURS.toMillis(72), 3, d.regularSpreadBps(), d.regularNx(), d.rateWindowMs(), 1,
                d.pollSeconds(), 9, 60_000);
    }

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty(CATALOG, "etc");
        System.setProperty(MAKER, "true");
        ExchangeCatalog.reset();
        store = new MemoryExchangeStore();
        store.addAccount(9, 0);           // the house account (cfg().houseAccount())
        store.addCharacter(19, 9, 10_000_000);
        now = new AtomicLong(NOW);
        ExchangeConfig c = cfg();
        engine = new Exchange(store, c, store::online, store.finder(), now::get, Ticker.fromSystem(c));
        engine.recover();
        rock = engine.ticker("E4006001");
        store.addItems(19, rock.asset(), 40);
        store.login(9, 19);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(CATALOG);
        System.clearProperty(MAKER);
        FairValue.setCurrent(null);
        ExchangeCatalog.reset();
    }

    void fund(long amount, Asset asset) {
        var r = engine.fundHouseLive(1, store.finder().find(9), asset, amount, false);
        assertTrue(r.ok(), r.code() + " " + r.message());
    }

    @Test
    void theHouseHoldsItemsOnlyWithTheMakerOn() throws Exception {
        Asset item = rock.asset();
        System.clearProperty(MAKER);
        var off = engine.fundHouseLive(1, store.finder().find(9), item, 20, false);
        assertEquals("BAD_REQUEST", off.code(), "without the maker a regular still never holds an item");
        System.setProperty(MAKER, "true");
        fund(20, item);
        fund(5_000_000, Asset.MESO);
        assertEquals(20, store.balance(Bucket.house(item, 1)), "the maker's asks are real items");
        assertEquals(5_000_000, store.balance(Bucket.house(Asset.MESO, 1)));
    }

    @Test
    void theMakerQuotesBothSidesAndThenRests() {
        fund(20, rock.asset());
        fund(5_000_000, Asset.MESO);
        seedFairValue(10_000);
        assertTrue(engine.tickRegulars() >= 2, "a bid and an ask are new orders");
        assertTrue(!engine.depth("E4006001", Side.BUY, 3).isEmpty(), "a bid");
        assertTrue(!engine.depth("E4006001", Side.SELL, 3).isEmpty(), "an ask");
        long bid = engine.depth("E4006001", Side.BUY, 1).get(0).price();
        long ask = engine.depth("E4006001", Side.SELL, 1).get(0).price();
        assertTrue(bid < 10_000 && ask > 10_000, "around the fair value, not at it: " + bid + " / " + ask);
        assertEquals(0, engine.tickRegulars(), "both sides already rest: the maker adds nothing");
        assertEquals(bid, engine.depth("E4006001", Side.BUY, 1).get(0).price(), "no second quote");
    }

    private void seedFairValue(long fair) {
        FairValue f = new FairValue(0.5, 0.3);
        FairValue.Sources src = new FairValue.Sources() {
            public long floor(String t) {
                return fair;
            }

            public long ceiling(String t, Map<String, Long> fairs) {
                return 0;
            }

            public long anchor(String t) {
                return fair;
            }
        };
        f.maybeUpdate(NOW, new MarketFlows(), src, List.of("E4006001"));
        assertEquals(fair, f.fair("E4006001"));
        FairValue.setCurrent(f);
    }

}
