package soloMapling.Economy.exchange.volatility;

import soloMapling.Economy.exchange.AssetRegistry.Asset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.ExchangeConfig;
import soloMapling.Economy.exchange.FakeBotWallet;
import soloMapling.Economy.exchange.MemoryExchangeStore;
import soloMapling.Economy.exchange.OrderBook.Side;
import soloMapling.Economy.exchange.Ticker;
import soloMapling.Economy.exchange.volatility.TraderBrain.Archetype;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * b375: ticket 270's daily item-sell ceiling is per account (filled + resting item sells, {@code ITEMS_CAP}). A
 * trader at the ceiling used to keep proposing sells, and the exchange refused each one, so the turn was spent on an
 * order that could not post. The brain now asks the exchange first and does not propose a sell that would be refused.
 */
class MarketTradersSellCapTest {

    static final long BOT = 1_000_000_321L;
    static final String CAP = "slums.exchange.items.sellCapMesosPerDay";

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
        System.clearProperty(CAP);
        System.clearProperty(MarketConfig.TRADERS_NX);
    }

    /** The exchange as the trader sees it, counting the LEAF sells it is asked to place for BOT. */
    Exchange counting(AtomicInteger sells) {
        Exchange spy = Mockito.spy(ex);
        Mockito.doAnswer(inv -> {
            Exchange.Place p = inv.getArgument(0);
            if (p.account() == BOT && p.side() == Side.SELL && "LEAF".equals(p.ticker())) {
                sells.incrementAndGet();
            }
            return inv.callRealMethod();
        }).when(spy).placeBot(Mockito.any(), Mockito.any());
        return spy;
    }

    int turns(Exchange target, FakeBotWallet w, Archetype kind, int n) {
        MarketTraders tr = new MarketTraders(new Random(5));
        MarketConfig cfg = MarketConfig.allOn();
        MarketTraders.Trader t = new MarketTraders.Trader(BOT, kind, 17);
        int done = 0;
        for (int i = 0; i < n; i++) {
            now.addAndGet(11_000);
            done += tr.act(target, t, w, now.get(), cfg, null, null);
        }
        return done;
    }

    @Test
    void aTraderWhoseAccountIsAtTheCapDoesNotProposeAnItemSell() {
        System.setProperty(CAP, "1000000");
        FakeBotWallet w = new FakeBotWallet(BOT, 50_000_000).with(Asset.LEAF, 200);
        // the account's whole day is already in a resting item sell: 100 LEAF at 10,000 = the 1,000,000 ceiling
        assertTrue(ex.placeBot(new Exchange.Place("seed", BOT, BOT, Side.SELL, 10_000, 100, "LEAF"), w).ok());

        AtomicInteger sells = new AtomicInteger();
        Exchange qa = counting(sells);
        turns(qa, w, Archetype.MAKER, 20);

        assertEquals(0, sells.get(), "the exchange would refuse each sell with ITEMS_CAP; it must not be asked");
        assertEquals(1, ex.openBotOrders(BOT, "LEAF").size(), "only the seed order is on the book");
    }

    /** The cap off (the shipped default) must not change what a trader does. */
    @Test
    void withoutACapATraderQuotesAsBefore() {
        FakeBotWallet w = new FakeBotWallet(BOT, 50_000_000).with(Asset.LEAF, 200);
        assertTrue(turns(ex, w, Archetype.MAKER, 5) > 0, "a MAKER on a flat market with stock quotes");
        assertFalse(ex.openBotOrders(BOT, "LEAF").isEmpty(), "its LEAF sell reached the book");
    }

    /** A ceiling with room left for the sell the brain makes changes nothing. */
    @Test
    void aCapThatLeavesRoomDoesNotStopTheSell() {
        System.setProperty(CAP, "100000000");
        FakeBotWallet w = new FakeBotWallet(BOT, 50_000_000).with(Asset.LEAF, 200);
        AtomicInteger sells = new AtomicInteger();
        Exchange qa = counting(sells);
        assertTrue(turns(qa, w, Archetype.MAKER, 5) > 0, "the trader still quotes with room left");
        assertTrue(sells.get() > 0, "a sell inside the remaining room is proposed");
        assertFalse(ex.openBotOrders(BOT, "LEAF").isEmpty());
    }
}
