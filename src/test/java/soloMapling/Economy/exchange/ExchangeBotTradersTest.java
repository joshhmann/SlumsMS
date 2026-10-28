package soloMapling.Economy.exchange;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Exchange.Place;
import soloMapling.Economy.exchange.Exchange.Response;
import soloMapling.Economy.exchange.Ledger.Asset;
import soloMapling.Economy.exchange.Ledger.Bucket;
import soloMapling.Economy.exchange.MemoryExchangeStore.Session;
import soloMapling.Economy.exchange.OrderBook.Side;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.1 volatility: trader bots on the exchange. A bot's order comes out of its own holdings (the BOT bucket, outside
 * the books like a wallet), every player limit applies, payouts go back into the bot only while it is in the game,
 * and mesos plus items across players, bots and the exchange are conserved: the fees stay in FEES, nothing is minted.
 */
class ExchangeBotTradersTest {

    static final long BOT = 1_000_000_123L, BOT2 = 1_000_000_456L;
    static final long P = 600_000;

    MemoryExchangeStore store;
    AtomicLong now;
    Exchange ex;
    FakeBotWallet bot, bot2;
    long meso0, leaf0;
    int req;

    @BeforeEach
    void setUp() throws Exception {
        store = new MemoryExchangeStore();
        for (long a = 1; a <= 2; a++) {
            store.addAccount(a, 0);
            store.addCharacter(10 + a, a, 100_000_000);
            store.addItems(10 + a, Asset.LEAF, 100);
        }
        now = new AtomicLong(1_800_000_000_000L);
        ex = start(ExchangeLiveTest.cfg(100, 0));
        bot = new FakeBotWallet(BOT, 50_000_000).with(Asset.LEAF, 40);
        bot2 = new FakeBotWallet(BOT2, 50_000_000).with(Asset.LEAF, 40);
        meso0 = totalMeso();
        leaf0 = totalLeaf();
    }

    Exchange start(ExchangeConfig c) throws Exception {
        Exchange e = new Exchange(store, c, store::online, store.finder(), now::get);
        assertTrue(e.recover().isEmpty());
        return e;
    }

    long totalMeso() {
        return store.totalMeso() + bot.mesos + bot2.mesos;
    }

    long totalLeaf() {
        return store.total(Asset.LEAF) + bot.have(Asset.LEAF) + bot2.have(Asset.LEAF);
    }

    void conserved() throws Exception {
        assertEquals(meso0, totalMeso(), "mesos conserved (the fees are in FEES)");
        assertEquals(leaf0, totalLeaf(), "leaves conserved");
        assertTrue(Exchange.reconcile(store.load(10)).isEmpty(), "reconciles: " + Exchange.reconcile(store.load(10)));
        ex.bookForTest("LEAF").check();
        for (var e : store.balances().entrySet()) {
            assertTrue(e.getValue() >= 0, e.getKey() + " negative");
        }
    }

    Response botPlace(FakeBotWallet w, Side side, long price, long qty) {
        return ex.placeBot(new Place("b" + (++req), w.id, w.id, side, price, qty, "LEAF"), w);
    }

    static long id(Response r) {
        return ((Number) r.data().get("orderId")).longValue();
    }

    @Test
    void aBotSellsItsOwnLeavesToAPlayerAndOnlyTheFeesLeave() throws Exception {
        Response sell = botPlace(bot, Side.SELL, P, 10);
        assertTrue(sell.ok(), sell.toString());
        assertEquals(30, bot.have(Asset.LEAF), "10 leaves left the bot");
        assertTrue(bot.saves >= 1, "the bot was saved before the escrow was written");
        assertEquals(10, store.balance(Bucket.escrow(Asset.LEAF, id(sell))));
        conserved();
        Response buy = ex.place(new Place("p1", 2, 12, Side.BUY, P, 10, "LEAF"));
        assertTrue(buy.ok(), buy.toString());
        assertEquals("FILLED", buy.data().get("status"));
        assertEquals(List.of(BOT), ex.botsWithPayout());
        assertTrue(ex.collectBot(bot));
        assertEquals(50_000_000 + 6_000_000 - 60_000, bot.mesos, "the bot gets the price less its 1%");
        assertEquals(120_000, store.balance(Bucket.fees()));
        assertTrue(ex.botsWithPayout().isEmpty());
        conserved();
    }

    @Test
    void aBotNeverSellsWhatItDoesNotHoldAndNeverTradesNx() throws Exception {
        assertEquals("INSUFFICIENT_FUNDS", botPlace(bot, Side.SELL, P, 41).code());
        assertEquals(40, bot.have(Asset.LEAF));
        assertEquals("INSUFFICIENT_FUNDS", botPlace(bot, Side.BUY, 1_000_000_000L, 2).code(), "60m mesos only");
        Response nx = ex.placeBot(new Place("nx", BOT, BOT, Side.BUY, 100_000, 100, "NX"), bot);
        assertEquals("BAD_REQUEST", nx.code());
        // a player request can never use a bot's id, and a bot's wallet must be its own
        assertEquals("BAD_REQUEST", ex.place(new Place("x", BOT, BOT, Side.BUY, P, 1, "LEAF")).code());
        assertEquals("BAD_REQUEST", ex.placeBot(new Place("y", BOT2, BOT2, Side.BUY, P, 1, "LEAF"), bot).code());
        assertEquals("BAD_REQUEST", ex.placeBot(new Place("z", 1, 11, Side.BUY, P, 1, "LEAF"), bot).code());
        conserved();
    }

    @Test
    void aBotThatIsNotInTheGameOrCannotBeSavedPlacesNothing() throws Exception {
        bot.online = false;
        assertEquals("TRY_AGAIN", botPlace(bot, Side.SELL, P, 5).code());
        bot.online = true;
        bot.saveFails = true;
        assertEquals("ERROR", botPlace(bot, Side.SELL, P, 5).code());
        assertEquals(40, bot.have(Asset.LEAF), "given back");
        assertEquals(0, ex.openOrders());
        conserved();
    }

    @Test
    void botsKeepEveryPlayerLimit() throws Exception {
        // 10 open per ticker
        for (int i = 0; i < 10; i++) {
            assertTrue(botPlace(bot, Side.SELL, P + i * 1000, 1).ok());
        }
        assertEquals("LIMIT_OPEN", botPlace(bot, Side.SELL, P, 1).code());
        assertEquals(10, ex.openBotOrders(BOT, "LEAF").size());
        // cancels pay the leaves back into the bot at once
        for (var o : ex.openBotOrders(BOT, "LEAF")) {
            assertTrue(ex.cancelBot("c" + o.id(), bot, o.id()).ok());
        }
        assertEquals(40, bot.have(Asset.LEAF));
        // 20 a day per ticker
        for (int i = 0; i < 10; i++) {
            assertTrue(botPlace(bot, Side.SELL, P, 1).ok());
        }
        assertEquals(20, ex.placedToday(BOT, "LEAF"));
        for (long id : ex.openIdsOf(BOT)) {
            ex.cancelBot("c" + id, bot, id);
        }
        assertEquals("LIMIT_DAY", botPlace(bot, Side.SELL, P, 1).code());
        // a player cannot cancel a bot's order, nor a bot another's
        assertTrue(botPlace(bot2, Side.SELL, P, 1).ok());
        long o2 = ex.openIdsOf(BOT2).get(0);
        assertEquals("NOT_FOUND", ex.cancel("pc", 1, o2).code());
        assertEquals("NOT_FOUND", ex.cancelBot("bc", bot, o2).code());
        conserved();
        // the cooldown and the fee: a config with the 10 s cooldown
        ExchangeConfig d = ExchangeConfig.defaults();
        ExchangeConfig cool = new ExchangeConfig(true, d.lotNx(), d.minNx(), d.maxNx(), d.minPrice(), d.maxPrice(),
                100, d.maxOpen(), d.perDay(), TimeUnit.SECONDS.toMillis(10), d.expiryMs(), 0, d.regularSpreadBps(),
                d.regularNx(), d.rateWindowMs(), 1, d.pollSeconds(), 0, 60_000);
        ex = start(cool);
        now.addAndGet(TimeUnit.DAYS.toMillis(1));
        assertTrue(botPlace(bot, Side.BUY, 1_000, 1).ok());
        assertEquals("COOLDOWN", botPlace(bot, Side.BUY, 1_000, 1).code());
        now.addAndGet(10_000);
        assertTrue(botPlace(bot, Side.BUY, 1_000, 1).ok());
        conserved();
    }

    @Test
    void twoBotsTradeWithEachOtherAndAPayoutWaitsWhileABotIsAway() throws Exception {
        assertTrue(botPlace(bot, Side.SELL, P, 20).ok());
        bot.online = false;                      // the seller logs out before the fill
        Response buy = botPlace(bot2, Side.BUY, P + 50_000, 20);
        assertTrue(buy.ok(), buy.toString());
        assertEquals("FILLED", buy.data().get("status"), "fills at the resting ask's price");
        assertEquals(60, bot2.have(Asset.LEAF), "the buyer is online: delivered at once");
        assertFalse(ex.collectBot(bot), "the seller is away: its mesos wait in PAYOUT");
        assertEquals(List.of(BOT), ex.botsWithPayout());
        conserved();
        // a restart: the payout and the bots' order history recover and reconcile
        ex = start(ExchangeLiveTest.cfg(100, 0));
        assertEquals(List.of(BOT), ex.botsWithPayout());
        bot.online = true;
        assertTrue(ex.collectBot(bot));
        assertEquals(50_000_000 + 12_000_000 - 120_000, bot.mesos);
        conserved();
    }

    @Test
    void aBotWithFullBagsKeepsItsLeavesInPayoutUntilTheyFit() throws Exception {
        Session seller = store.login(1, 11);
        assertTrue(ex.place(new Place("ps", 1, 11, Side.SELL, P, 10, "LEAF")).ok());
        bot.bagRoom = 4;
        assertTrue(botPlace(bot, Side.BUY, P, 10).ok());
        assertEquals(44, bot.have(Asset.LEAF), "4 fit");
        assertEquals(6, store.balance(Bucket.payout(Asset.LEAF, BOT)));
        assertEquals(List.of(BOT), ex.botsWithPayout());
        bot.bagRoom = 100;
        ex.collectBot(bot);
        assertEquals(50, bot.have(Asset.LEAF));
        assertTrue(ex.botsWithPayout().isEmpty());
        ex.collectAll();
        assertEquals(90, seller.item(Asset.LEAF));
        conserved();
    }
}
