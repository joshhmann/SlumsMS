package soloMapling.Economy.exchange;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Exchange.Place;
import soloMapling.Economy.exchange.Exchange.Response;
import soloMapling.Economy.exchange.Ledger.Asset;
import soloMapling.Economy.exchange.OrderBook.Side;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.2 (owner 2026-10-28): trader bots buy NX with their own mesos and sell NX they own, inside the bots' band of
 * 2,500..10,000 mesos per NX (2,500,000..10,000,000 per 1,000) in whole lots of 100, at least 100. A bot has no cash
 * shop, so its NX lives in its own PAYOUT bucket in the ledger: it survives a restart, it can only be spent once, and
 * nothing is minted.
 */
class ExchangeBotNxTest {

    static final long BOT = 1_000_000_777L;
    static final long ACC = 2, CHR = 12;
    static final long NX0 = 1_000, PRICE = 5_000_000;

    MemoryExchangeStore store;
    AtomicLong now;
    Exchange ex;
    FakeBotWallet bot;

    @BeforeEach
    void setUp() throws Exception {
        store = new MemoryExchangeStore();
        store.addAccount(ACC, NX0);
        store.addCharacter(CHR, ACC, 100_000_000);
        now = new AtomicLong(1_800_000_000_000L);
        System.setProperty(soloMapling.Economy.exchange.volatility.MarketConfig.TRADERS_NX, "true");
        ex = start();
        bot = new FakeBotWallet(BOT, 50_000_000);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        System.clearProperty(soloMapling.Economy.exchange.volatility.MarketConfig.TRADERS_NX);
    }

    Exchange start() throws Exception {
        Exchange e = new Exchange(store, cfg(), store::online, store.finder(), now::get);
        assertTrue(e.recover().isEmpty());
        return e;
    }

    static ExchangeConfig cfg() {
        ExchangeConfig d = ExchangeConfig.defaults();
        return new ExchangeConfig(true, d.lotNx(), d.minNx(), d.maxNx(), d.minPrice(), d.maxPrice(), 100,
                d.maxOpen(), d.perDay(), TimeUnit.SECONDS.toMillis(10), d.expiryMs(), 0, d.regularSpreadBps(),
                d.regularNx(), d.rateWindowMs(), 3, d.pollSeconds(), 0, 60_000);
    }

    @Test
    void aBotBuysNxWithItsMesosAndSellsItBack() throws Exception {
        long nx0 = store.total(Asset.NX);
        long meso0 = bot.mesos;
        assertTrue(ex.place(new Place("p1", ACC, CHR, Side.SELL, PRICE, NX0, "NX")).ok());

        now.addAndGet(11_000);
        Response bought = ex.placeBot(new Place("b1", BOT, BOT, Side.BUY, PRICE, NX0, "NX"), bot);
        assertTrue(bought.ok(), bought.message());
        assertEquals(NX0, ex.botNx(BOT), "the bot's purse holds what it bought");
        assertEquals(meso0 - PRICE - 50_000, bot.mesos, "its mesos paid, the 100 bps fee on top");
        assertEquals(nx0, store.total(Asset.NX), "no NX minted");
        assertEquals(0, store.nx(ACC), "the player's NX is in the bot's hands now");

        // a restart: the purse is in the ledger, not in the bot's memory
        Exchange again = start();
        assertEquals(NX0, again.botNx(BOT), "the purse survives a restart");

        now.addAndGet(11_000);
        Response offered = again.placeBot(new Place("b2", BOT, BOT, Side.SELL, PRICE + 500_000, NX0, "NX"), bot);
        assertTrue(offered.ok(), offered.message());
        assertEquals(0, again.botNx(BOT), "the escrow left the purse");
        assertEquals(nx0, store.total(Asset.NX), "still no NX minted");

        now.addAndGet(11_000);
        Response twice = again.placeBot(new Place("b3", BOT, BOT, Side.SELL, PRICE, NX0, "NX"), bot);
        assertFalse(twice.ok(), "a bot cannot sell the same purse twice");
        assertEquals("INSUFFICIENT_FUNDS", twice.code());

        now.addAndGet(11_000);
        assertTrue(again.place(new Place("p2", ACC, CHR, Side.BUY, PRICE + 500_000, NX0, "NX")).ok());
        // the player's NX waits in their payout, as any fill's proceeds do, and is collected when they log in
        assertEquals(NX0, store.balance(Ledger.Bucket.payout(Asset.NX, ACC)), "the player has its NX back");
        assertEquals(nx0, store.total(Asset.NX), "conserved");
    }

    @Test
    void aBotKeepsToTheNxBand() {
        assertTrue(ex.place(new Place("p1", ACC, CHR, Side.SELL, PRICE, NX0, "NX")).ok());
        now.addAndGet(11_000);
        Response dear = ex.placeBot(new Place("b1", BOT, BOT, Side.BUY, 12_000_000, NX0, "NX"), bot);
        assertFalse(dear.ok(), "over 10,000,000 per 1,000 NX is outside the band for a buy");
        assertEquals("PRICE_BAND", dear.code());
        assertEquals(0, ex.botNx(BOT), "nothing bought");
        now.addAndGet(11_000);
        assertTrue(ex.placeBot(new Place("b2", BOT, BOT, Side.BUY, PRICE, NX0, "NX"), bot).ok(), "an NX purse");
        now.addAndGet(11_000);
        Response cheap = ex.placeBot(new Place("b3", BOT, BOT, Side.SELL, 2_000_000, NX0, "NX"), bot);
        assertFalse(cheap.ok(), "under 2,500,000 per 1,000 NX is outside the band for a sell");
        assertEquals("PRICE_BAND", cheap.code());
        assertEquals(NX0, ex.botNx(BOT), "the purse is untouched");
    }

    @Test
    void offUnlessTheFlagIsOn() {
        assertTrue(ex.place(new Place("p1", ACC, CHR, Side.SELL, PRICE, NX0, "NX")).ok());
        now.addAndGet(11_000);
        System.clearProperty(soloMapling.Economy.exchange.volatility.MarketConfig.TRADERS_NX);
        Response off = ex.placeBot(new Place("b1", BOT, BOT, Side.BUY, PRICE, NX0, "NX"), bot);
        assertFalse(off.ok(), "a bot's NX trading is off unless -Dslums.exchange.traders.nx is set");
        System.setProperty(soloMapling.Economy.exchange.volatility.MarketConfig.TRADERS_NX, "true");
        now.addAndGet(11_000);
        try {
            assertTrue(ex.placeBot(new Place("b2", BOT, BOT, Side.BUY, PRICE, NX0, "NX"), bot).ok());
        } finally {
            System.clearProperty(soloMapling.Economy.exchange.volatility.MarketConfig.TRADERS_NX);
        }
    }
}
