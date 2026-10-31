package soloMapling.Economy.exchange.volatility;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.ExchangeConfig;
import soloMapling.Economy.exchange.ExchangeStore.OrderRow;
import soloMapling.Economy.exchange.FakeBotWallet;
import soloMapling.Economy.exchange.Ledger.Asset;
import soloMapling.Economy.exchange.LiveWallet;
import soloMapling.Economy.exchange.MemoryExchangeStore;
import soloMapling.Economy.exchange.OrderBook.Side;
import soloMapling.Economy.exchange.Ticker;
import soloMapling.Economy.exchange.volatility.TraderBrain.Archetype;

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.2 (owner: "the scrolls never trade"). Cause, TEST database 2026-10-31: 27 bots held Chaos Scrolls (2049100)
 * and not one exchange order for CHAOS had ever been placed; the same for the White Scroll. A trader's ticker came
 * from the dice only (LEAF 70%, else one of the four item tickers), so a bot that held a scroll quoted something else
 * and the scroll never reached the book. Now a bot holding a scroll offers that scroll on its turn.
 */
class MarketTradersScrollTest {

    static final long BOT = 1_000_000_321L;
    static final long P = 600_000;

    MemoryExchangeStore store;
    AtomicLong now;
    Exchange ex;

    @BeforeEach
    void setUp() throws Exception {
        MarketFlows.get().clear();
        store = new MemoryExchangeStore();
        store.addAccount(1, 100_000);
        store.addCharacter(11, 1, 100_000_000);
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
        System.clearProperty(MarketConfig.TRADERS_NX);
    }

    /** The bot's orders on {@code ticker}, after {@code turns} turns of the brain (its first turn included). */
    int turn(FakeBotWallet w, Archetype kind, String ticker, int turns) {
        MarketTraders tr = new MarketTraders(new Random(5));
        MarketConfig cfg = MarketConfig.allOn();
        MarketTraders.Trader t = new MarketTraders.Trader(w.id, kind, 17);
        for (int i = 0; i < turns; i++) {
            now.addAndGet(11_000);
            tr.act(ex, t, w, now.get(), cfg, null, null);
        }
        int n = 0;
        for (OrderRow o : ex.openBotOrders(w.id, ticker)) {
            n++;
        }
        return n;
    }

    /** t-xc-5 as auto-QA runs it: PASS for a holder (pick, a real sell, the cancel), SKIP when nobody holds one. */
    @Test
    void theScrollCheckPassesForAHolderAndSkipsForNone() {
        FakeBotWallet w = new FakeBotWallet(BOT, 50_000_000).with(Asset.CHAOS, 2);
        List<String> pass = MarketQa.scrollCheck(ex, roster(java.util.Map.of(BOT, w)), now.get());
        assertTrue(pass.get(0).startsWith("PASS"), String.join(" | ", pass));
        assertTrue(pass.get(0).contains("lists its scroll"), pass.get(0));
        assertEquals(2, w.have(Asset.CHAOS), "the check put the Chaos Scroll back");
        assertEquals(50_000_000, w.have(Asset.MESO), "and its mesos");

        List<String> skip = MarketQa.scrollCheck(ex, roster(java.util.Map.of()), now.get());
        assertTrue(skip.get(0).startsWith("SKIP"), String.join(" | ", skip));
        assertTrue(skip.get(0).contains("2.2 per trader-hour"), skip.get(0));
    }

    MarketTraders.Roster roster(java.util.Map<Long, FakeBotWallet> wallets) {
        List<MarketTraders.Trader> list = new java.util.ArrayList<>();
        long id = BOT;
        for (Long key : wallets.keySet()) {
            list.add(new MarketTraders.Trader(key, Archetype.MAKER, 17));
        }
        if (list.isEmpty()) {
            list.add(new MarketTraders.Trader(BOT + 99, Archetype.MAKER, 17));
        }
        return new MarketTraders.Roster() {
            public List<MarketTraders.Trader> traders() {
                return list;
            }

            public LiveWallet walletOf(MarketTraders.Trader t) {
                return wallets.get(t.id());
            }

            public int guildOf(MarketTraders.Trader t) {
                return 0;
            }
        };
    }

    @Test
    void aBotThatHoldsAScrollNeverQuotesAnotherTicker() {
        int i = 0;
        for (Archetype kind : Archetype.values()) {
            FakeBotWallet w = new FakeBotWallet(BOT + i++, 50_000_000).with(Asset.CHAOS, 2).with(Asset.LEAF, 5);
            turn(w, kind, "CHAOS", 5);
            assertEquals(0, ex.openBotOrders(w.id, "LEAF").size(),
                    kind + " held a Chaos Scroll and quoted leaves instead");
            assertEquals(0, ex.openBotOrders(w.id, "ELIXIR").size(), kind + " quoted elixirs instead of its scroll");
        }
    }

    @Test
    void theSellersListTheScroll() {
        // value asks above fair, the maker quotes both sides: with no history those are the ones with an ask
        FakeBotWallet v = new FakeBotWallet(BOT, 50_000_000).with(Asset.CHAOS, 2);
        assertTrue(turn(v, Archetype.VALUE, "CHAOS", 5) > 0, "a value trader lists the scroll it holds");
        FakeBotWallet m = new FakeBotWallet(BOT + 1, 50_000_000).with(Asset.CHAOS, 2);
        assertTrue(turn(m, Archetype.MAKER, "CHAOS", 5) > 0, "a maker lists the scroll it holds");
    }

    @Test
    void theWhiteScrollToo() {
        FakeBotWallet w = new FakeBotWallet(BOT, 50_000_000).with(Asset.WS, 1);
        assertTrue(turn(w, Archetype.MAKER, "WS", 5) > 0, "a bot holding a White Scroll offers it");
    }

    @Test
    void aBotWithoutAScrollTradesAsBefore() {
        FakeBotWallet w = new FakeBotWallet(BOT, 50_000_000).with(Asset.LEAF, 30);
        int leaves = 0;
        MarketTraders tr = new MarketTraders(new Random(9));
        MarketTraders.Trader t = new MarketTraders.Trader(w.id, Archetype.VALUE, 17);
        for (int i = 0; i < 20; i++) {
            now.addAndGet(11_000);
            tr.act(ex, t, w, now.get(), MarketConfig.allOn(), null, null);
        }
        for (OrderRow o : ex.openBotOrders(w.id, "LEAF")) {
            leaves++;
        }
        assertTrue(leaves > 0, "the dice still pick LEAF for a leaf holder");
    }

    @Test
    void aBotThatBoughtNxOffersItBack() {
        System.setProperty(MarketConfig.TRADERS_NX, "true");
        assertTrue(ex.place(new Exchange.Place("p1", 1, 11, Side.SELL, 5_000_000, 1_000, "NX")).ok());
        FakeBotWallet w = new FakeBotWallet(BOT, 50_000_000);
        assertTrue(ex.placeBot(new Exchange.Place("b1", BOT, BOT, Side.BUY, 5_000_000, 1_000, "NX"), w).ok());
        assertEquals(1_000, ex.botNx(BOT), "the bot's purse");
        List<OrderRow> nx = ex.openBotOrders(BOT, "NX");
        assertTrue(nx.isEmpty(), "nothing to sell yet");
        w.mesos = 0; // no mesos left to buy with: the turns can only offer its purse
        MarketTraders tr = new MarketTraders(new Random(3));
        MarketTraders.Trader t = new MarketTraders.Trader(w.id, Archetype.VALUE, 17);
        for (int i = 0; i < 10; i++) {
            now.addAndGet(11_000);
            tr.act(ex, t, w, now.get(), MarketConfig.allOn().withTraders(16, 0, true), null, null);
            if (!ex.openBotOrders(BOT, "NX").isEmpty()) {
                break;
            }
        }
        nx = ex.openBotOrders(BOT, "NX");
        assertFalse(nx.isEmpty(), "a bot with a purse offers NX back");
        for (OrderRow o : nx) {
            assertEquals(Side.SELL, o.side());
            assertEquals(0, o.nxLeft() % 100, "whole lots: " + o.nxLeft());
            assertTrue(o.price() >= MarketTraders.BOT_NX_MIN && o.price() <= MarketTraders.BOT_NX_MAX,
                    "inside the bot band: " + o.price());
        }
        assertTrue(ex.botNx(BOT) < 1_000, "the escrow left the purse");
    }

    @Test
    void offscreenFindsCarryTheirFractionAndNeverInventADrop() {
        MarketTraders.clearForTest();
        // 30 kills a minute at 3,000 per million (0.3%) is 0.09 a minute: one scroll after ~11 minutes, not before
        assertEquals(0, MarketTraders.offscreenScroll(77, 0, 60_000L, 30, 3_000));
        for (int i = 0; i < 10; i++) {
            assertEquals(0, MarketTraders.offscreenScroll(77, 0, 60_000L, 30, 3_000), "minute " + i);
        }
        assertEquals(1, MarketTraders.offscreenScroll(77, 0, 60_000L, 30, 3_000), "the eleventh minute's fraction");
        // no drop row, no find: the White Scroll has no mob in drop_data, so traders get none off screen
        assertEquals(0, MarketTraders.offscreenScroll(77, 1, 86_400_000L, 30, 0));
        // the bot's pace and the chance are what lands it
        assertEquals(0, MarketTraders.offscreenScroll(78, 0, 0, 30, 3_000));
        assertEquals(30, MarketTraders.offscreenScroll(79, 0, 60_000L, 30, 1_000_000), "one per kill at 30 kills");
        assertEquals(0, MarketTraders.offscreenScroll(80, 5, 60_000L, 30, 3_000), "a slot past the scrolls");
    }

    @Test
    void nothingIsOpenWhenTheLedgerHasNoScrolls() {
        FakeBotWallet w = new FakeBotWallet(BOT, 50_000_000);
        assertEquals(0, turn(w, Archetype.MAKER, "CHAOS", 5), "no scroll, no CHAOS quote");
    }
}
