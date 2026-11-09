package soloMapling.Economy.exchange.volatility;

import soloMapling.Economy.exchange.AssetRegistry.Asset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.ExchangeConfig;
import soloMapling.Economy.exchange.FakeBotWallet;
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

    @Test
    void scrollCheckPassesForEveryArchetypeHoldingOneScroll() {
        for (TraderBrain.Archetype a : TraderBrain.Archetype.values()) {
            traders.clear();
            wallets.clear();
            now.addAndGet(60_000); // clear the order cooldown of the last round
            FakeBotWallet w = new FakeBotWallet(A, 5_000_000).with(Asset.CHAOS, 5);
            wallets.put(A, w);
            traders.add(new MarketTraders.Trader(A, a, A));
            List<String> r = MarketQa.scrollCheck(ex, roster, now.get());
            // never a FAIL; a MAKER (which quotes both sides on a flat market) must reach the real order and PASS
            assertTrue(!r.get(0).startsWith("FAIL"), a + ": " + String.join("\n", r));
            if (a == TraderBrain.Archetype.MAKER) {
                assertTrue(r.get(0).startsWith("PASS"), String.join("\n", r));
            }
        }
    }

    @Test
    void scrollCheckPassesWhenOneTraderHoldsBothScrolls() {
        FakeBotWallet w = trader(A, 5_000_000).with(Asset.CHAOS, 5).with(Asset.WS, 2);
        List<String> r = MarketQa.scrollCheck(ex, roster, now.get());
        assertTrue(r.get(0).startsWith("PASS"), String.join("\n", r));
    }

    @Test
    void aHolderInsideTheOrderCooldownIsPassedOverForOneThatIsReady() {
        FakeBotWallet busy = trader(A, 5_000_000).with(Asset.CHAOS, 5);
        FakeBotWallet ready = trader(B, 5_000_000).with(Asset.CHAOS, 5);
        assertTrue(ex.placeBot(new Exchange.Place("xc3", A, A, Side.BUY, 1_000, 1, "ELIXIR"), busy).ok());
        now.addAndGet(2_000);
        List<String> r = MarketQa.scrollCheck(ex, roster, now.get());
        assertTrue(r.get(0).startsWith("PASS"), String.join("\n", r));
        assertTrue(r.stream().anyMatch(l -> l.contains("inside the order cooldown")), String.join("\n", r));
        assertEquals(5, ready.items.get(Asset.CHAOS));
    }

    @Test
    void whenEveryHolderIsInsideTheOrderCooldownTheCheckSkipsInsteadOfFailing() {
        FakeBotWallet busy = trader(A, 5_000_000).with(Asset.CHAOS, 5);
        assertTrue(ex.placeBot(new Exchange.Place("xc3", A, A, Side.BUY, 1_000, 1, "ELIXIR"), busy).ok());
        now.addAndGet(2_000);
        List<String> r = MarketQa.scrollCheck(ex, roster, now.get());
        assertTrue(r.get(0).startsWith("SKIP"), String.join("\n", r));
    }

    /**
     * b375: a holder whose account is at the daily item-sell ceiling cannot list anything today. That is the holder's
     * state, not a fault in the sell the check tests: pass it over for the next holder, exactly like the cooldown,
     * instead of failing on the exchange's ITEMS_CAP refusal.
     */
    @Test
    void aHolderAtTheDailyItemSellCapIsPassedOverForOneThatIsReady() {
        FakeBotWallet capped = trader(A, 5_000_000).with(Asset.CHAOS, 5);
        FakeBotWallet ready = trader(B, 5_000_000).with(Asset.CHAOS, 5);
        List<String> r = MarketQa.scrollCheck(cappedFor(A), roster, now.get());
        assertTrue(r.get(0).startsWith("PASS"), String.join("\n", r));
        assertTrue(r.stream().anyMatch(l -> l.contains("trader " + A + " is at the daily item-sell cap")),
                String.join("\n", r));
        assertEquals(5, ready.items.get(Asset.CHAOS), "the ready holder's scroll went back");
        assertEquals(5, capped.items.get(Asset.CHAOS));
    }

    @Test
    void whenEveryHolderIsAtTheDailyItemSellCapTheCheckSkipsInsteadOfFailing() {
        trader(A, 5_000_000).with(Asset.CHAOS, 5);
        trader(B, 5_000_000).with(Asset.CHAOS, 5);
        List<String> r = MarketQa.scrollCheck(cappedFor(A, B), roster, now.get());
        assertTrue(r.get(0).startsWith("SKIP"), String.join("\n", r));
        assertTrue(r.get(0).contains("daily item-sell cap"), r.get(0));
    }

    /** A spy of the test's exchange that answers ITEMS_CAP for the placements of {@code accounts}, as the cap does. */
    Exchange cappedFor(long... accounts) {
        java.util.Set<Long> capped = new java.util.HashSet<>();
        for (long a : accounts) {
            capped.add(a);
        }
        Exchange spy = org.mockito.Mockito.spy(ex);
        org.mockito.Mockito.doAnswer(inv -> {
            Exchange.Place p = inv.getArgument(0);
            if (capped.contains(p.account())) {
                return new Exchange.Response(p.requestId(), false, "ITEMS_CAP",
                        "item sells are capped at 1,000,000 mesos a day per account: you have 1,000,000 filled or "
                                + "resting today", Map.of());
            }
            return inv.callRealMethod();
        }).when(spy).placeBot(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        return spy;
    }

    @Test
    void whenNoHolderIsOnlineTheCheckStagesAScrollInASellerAndTakesItBack() {
        FakeBotWallet w = trader(A, 5_000_000);
        List<String> r = MarketQa.scrollCheck(ex, roster, now.get());
        assertTrue(r.get(0).startsWith("PASS"), String.join("\n", r));
        assertTrue(r.stream().anyMatch(l -> l.contains("staged 1")), String.join("\n", r));
        assertEquals(0L, w.have(Asset.CHAOS) + w.have(Asset.WS));
        assertEquals(5_000_000L, w.mesos);
        assertTrue(ex.openBotOrders(A, "CHAOS").isEmpty());
    }

    @Test
    void stagingSkipsTradersWhoseArchetypeNeverSellsAndUsesOneWhoDoes() {
        FakeBotWallet holder = new FakeBotWallet(A, 5_000_000).with(Asset.CHAOS, 3);
        wallets.put(A, holder);
        traders.add(new MarketTraders.Trader(A, TraderBrain.Archetype.VALUE, A));
        FakeBotWallet maker = new FakeBotWallet(B, 5_000_000);
        wallets.put(B, maker);
        traders.add(new MarketTraders.Trader(B, TraderBrain.Archetype.MAKER, B));
        List<String> r = MarketQa.scrollCheck(ex, roster, now.get());
        assertTrue(r.get(0).startsWith("PASS"), String.join("\n", r));
        assertEquals(3L, holder.have(Asset.CHAOS));
        assertEquals(0L, maker.have(Asset.CHAOS) + maker.have(Asset.WS));
    }

    @Test
    void aRefusedOrFailedStagedScrollIsStillTakenBack() {
        FakeBotWallet w = trader(A, 0); // no mesos for the fee: the order may be refused, the scroll must not leak
        w.saveFails = false;
        MarketQa.scrollCheck(ex, roster, now.get());
        assertEquals(0L, w.have(Asset.CHAOS) + w.have(Asset.WS));
    }

    @Test
    void noTraderOnlineStillSkips() {
        List<String> r = MarketQa.scrollCheck(ex, roster, now.get());
        assertTrue(r.get(0).startsWith("SKIP"), String.join("\n", r));
    }
}
