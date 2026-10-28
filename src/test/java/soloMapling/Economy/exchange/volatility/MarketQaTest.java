package soloMapling.Economy.exchange.volatility;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.ExchangeConfig;
import soloMapling.Economy.exchange.FakeBotWallet;
import soloMapling.Economy.exchange.Ledger.Asset;
import soloMapling.Economy.exchange.MemoryExchangeStore;
import soloMapling.Economy.exchange.OrderBook.Side;
import soloMapling.Economy.exchange.Ticker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.1 QA round 3, t-xc-4: the guild-war check stands on its own. On TEST it failed with "no online trader with
 * 100,000 mesos" after its only rich trader hit the 10 s order cooldown (t-xc-3 had just used it).
 */
class MarketQaTest {

    static final long A = 1_000_000_123L, B = 1_000_000_456L;

    MemoryExchangeStore store;
    AtomicLong now;
    Exchange ex;
    Map<Long, FakeBotWallet> wallets;
    List<MarketTraders.Trader> traders;
    MarketTraders.Roster roster;

    @BeforeEach
    void setUp() throws Exception {
        store = new MemoryExchangeStore();
        now = new AtomicLong(1_800_000_000_000L);
        ExchangeConfig d = ExchangeConfig.defaults();
        ExchangeConfig c = new ExchangeConfig(true, d.lotNx(), d.minNx(), d.maxNx(), d.minPrice(), d.maxPrice(), 100,
                d.maxOpen(), d.perDay(), TimeUnit.SECONDS.toMillis(10), d.expiryMs(), 0, d.regularSpreadBps(),
                d.regularNx(), d.rateWindowMs(), 3, d.pollSeconds(), 0, 60_000);
        ex = new Exchange(store, c, a -> false, a -> null, now::get, Ticker.defaults(c));
        assertTrue(ex.recover().isEmpty());
        wallets = new HashMap<>();
        traders = new ArrayList<>();
        roster = new MarketTraders.Roster() {
            public List<MarketTraders.Trader> traders() {
                return traders;
            }

            public soloMapling.Economy.exchange.LiveWallet walletOf(MarketTraders.Trader t) {
                return wallets.get(t.id());
            }
        };
    }

    FakeBotWallet trader(long id, long mesos) {
        FakeBotWallet w = new FakeBotWallet(id, mesos);
        wallets.put(id, w);
        traders.add(new MarketTraders.Trader(id, TraderBrain.Archetype.MAKER, id));
        return w;
    }

    @Test
    void aPoorTraderIsToppedUpForTheCheckAndTheTopUpIsTakenBackExactly() {
        FakeBotWallet poor = trader(A, 5_000);
        List<String> r = MarketQa.warCheck(ex, roster, now.get(), ms -> { });
        assertTrue(r.get(0).startsWith("PASS"), String.join("\n", r));
        assertEquals(5_000, poor.mesos, "nothing minted is left behind");
        assertTrue(r.stream().anyMatch(l -> l.contains("topped up 95000")), String.join("\n", r));
        assertEquals(0, ex.openBotOrders(A, "ELIXIR").size(), "the bid was cancelled");
    }

    @Test
    void aTraderInsideTheCooldownIsPassedOverForOneThatIsReady() {
        FakeBotWallet rich = trader(A, 5_000_000);
        FakeBotWallet ready = trader(B, 200_000);
        // the richest trader just ordered (t-xc-3 ran a moment ago)
        assertTrue(ex.placeBot(new Exchange.Place("xc3", A, A, Side.BUY, 1_000, 1, "ELIXIR"), rich).ok());
        now.addAndGet(2_000);
        long rich0 = rich.mesos;
        List<String> r = MarketQa.warCheck(ex, roster, now.get(), ms -> { throw new AssertionError("no wait needed"); });
        assertTrue(r.get(0).startsWith("PASS"), String.join("\n", r));
        assertTrue(r.stream().anyMatch(l -> l.startsWith("trader " + B + " (guild")), String.join("\n", r));
        assertEquals(200_000, ready.mesos);
        assertEquals(rich0, rich.mesos);
    }

    @Test
    void whenEveryTraderIsInsideTheCooldownTheCheckWaitsItOutOnce() {
        FakeBotWallet only = trader(A, 300_000);
        assertTrue(ex.placeBot(new Exchange.Place("xc3", A, A, Side.BUY, 1_000, 1, "ELIXIR"), only).ok());
        long mesos0 = only.mesos;
        List<Long> waited = new ArrayList<>();
        List<String> r = MarketQa.warCheck(ex, roster, now.get(), ms -> {
            waited.add(ms);
            now.addAndGet(ms);
        });
        assertEquals(List.of(11_000L), waited);
        assertTrue(r.get(0).startsWith("PASS"), String.join("\n", r));
        assertEquals(mesos0, only.mesos);
    }

    @Test
    void noOnlineTraderFailsAndSaysWhoIsThere() {
        trader(A, 1_000_000);
        wallets.clear();
        List<String> r = MarketQa.warCheck(ex, roster, now.get(), ms -> { });
        assertTrue(r.get(0).startsWith("FAIL no online trader"), String.join("\n", r));
        assertTrue(r.stream().anyMatch(l -> l.contains(A + " MAKER offline")), String.join("\n", r));
    }
}
