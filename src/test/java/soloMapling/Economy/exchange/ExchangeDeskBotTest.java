package soloMapling.Economy.exchange;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Exchange.Place;
import soloMapling.Economy.exchange.Exchange.Response;
import soloMapling.Economy.exchange.Ledger.Asset;
import soloMapling.Economy.exchange.Ledger.Bucket;
import soloMapling.Economy.exchange.OrderBook.Side;
import soloMapling.Economy.exchange.volatility.MarketConfig;
import soloMapling.Economy.exchange.volatility.MarketTraders;
import soloMapling.Economy.exchange.volatility.TraderBrain;

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.3 (b260): trader bots at the NX desk. With {@code -Dslums.exchange.traders.nx=true} and the desk open a bot
 * buys at the desk's sell quote with its own mesos and sells the NX in its own purse at the desk's buy quote, through
 * the same fill path a player's confirm uses: the same postings, the same fill row, the same drift of R, MIN_QTY,
 * DESK_EMPTY, and nothing minted. Its money leg is the bot's own off-ledger bucket (as every bot trade), its NX leg is
 * the purse {@link Exchange#botNx} reads. Two hourly allowances bound it: one bot's and the whole population's
 * ({@code -Dslums.exchange.traders.nx.perBotHour}, {@code .worldHour}).
 */
class ExchangeDeskBotTest {

    static final long HOUSE = 9, HOUSE_CHAR = 19;
    static final long BOT = 1_000_000_111L, BOT2 = 1_000_000_222L, BOT3 = 1_000_000_333L;
    static final long ACC = 2, CHR = 12;
    static final long FUND = 3, FUND_CHR = 13;
    static final long HOUR = Desk.HOUR_MS;
    static final long NX0 = 150_000, MESO0 = 500_000_000;

    MemoryExchangeStore store;
    AtomicLong now;
    Exchange ex;
    FakeBotWallet bot, bot2, bot3;
    long houseNx0, houseMeso0, botMeso0, nx0;

    @BeforeEach
    void setUp() throws Exception {
        store = new MemoryExchangeStore();
        store.addAccount(ACC, 0);
        store.addCharacter(CHR, ACC, 100_000_000);
        store.addAccount(HOUSE, 1_000);
        store.addCharacter(HOUSE_CHAR, HOUSE, 1_000_000_000);
        store.addAccount(FUND, NX0);
        store.addCharacter(FUND_CHR, FUND, MESO0);
        now = new AtomicLong(1_800_000_000_000L);
        System.setProperty(MarketConfig.TRADERS_NX, "true");
        ex = start();
        seedDesk(NX0, MESO0);
        bot = new FakeBotWallet(BOT, 50_000_000);
        bot2 = new FakeBotWallet(BOT2, 50_000_000);
        bot3 = new FakeBotWallet(BOT3, 50_000_000);
        houseNx0 = store.balance(Exchange.deskBucket(Asset.NX));
        houseMeso0 = store.balance(Exchange.deskBucket(Asset.MESO));
        botMeso0 = bot.mesos;
        nx0 = store.totalNx();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(MarketConfig.TRADERS_NX);
        System.clearProperty(MarketConfig.TRADERS_NX_PER_BOT_HOUR);
        System.clearProperty(MarketConfig.TRADERS_NX_WORLD_HOUR);
    }

    /** The desk's own HOUSE buckets get their stock from the house account, as staff funding does. */
    void seedDesk(long nx, long meso) throws Exception {
        List<Ledger.Posting> ps = new java.util.ArrayList<>();
        if (nx > 0) {
            ps.add(new Ledger.Posting(Ledger.Why.FUND, Bucket.wallet(Asset.NX, FUND),
                    Exchange.deskBucket(Asset.NX), nx, FUND, 0, 0));
        }
        if (meso > 0) {
            ps.add(new Ledger.Posting(Ledger.Why.FUND, Bucket.wallet(Asset.MESO, FUND_CHR),
                    Exchange.deskBucket(Asset.MESO), meso, FUND, 0, 0));
        }
        store.apply(new ExchangeStore.Batch(9000, now.get(), ps, List.of(), List.of()));
    }

    Exchange start() throws Exception {
        return start(store);
    }

    Exchange start(MemoryExchangeStore st) throws Exception {
        Exchange e = new Exchange(st, ExchangeLiveTest.cfg(100, HOUSE), st::online, st.finder(), now::get);
        e.siteReauth(SiteReauth.OFF);
        e.deskConfig(DeskConfig.defaults().on());
        assertTrue(e.recover().isEmpty());
        return e;
    }

    private void conserved() throws Exception {
        assertEquals(nx0, store.totalNx(), "NX conserved: a desk fill transfers it");
        assertTrue(Exchange.reconcile(store.load(10)).isEmpty(), "reconciles: " + Exchange.reconcile(store.load(10)));
        for (var e : store.balances().entrySet()) {
            assertTrue(e.getValue() >= 0, e.getKey() + " negative");
        }
    }

    @Test
    void aBotBuysAtTheDeskSellQuoteAndItsPurseHoldsTheNx() throws Exception {
        Exchange.DeskBotQuote q = ex.deskForBot(BOT);
        assertNotNull(q, "the desk quotes a trader bot");
        assertEquals(ex.desk().sell(), q.sell(), "the bot pays what the house charges");
        assertEquals(100, q.minQty());
        assertEquals(100, q.lot());
        long mesos = ex.desk().mesos(Side.BUY, 1_000, q.sell());

        Response r = ex.deskBot("d1", bot, Side.BUY, 1_000);
        assertTrue(r.ok(), String.valueOf(r.message()));
        assertEquals(1_000, ex.botNx(BOT), "the purse holds the NX");
        assertEquals(botMeso0 - mesos, bot.mesos, "the bot paid the quote");
        assertEquals(houseNx0 - 1_000, store.balance(Exchange.deskBucket(Asset.NX)), "the house sold its NX");
        assertEquals(houseMeso0 + mesos, store.balance(Exchange.deskBucket(Asset.MESO)), "and was paid for it");
        assertEquals(0, store.nx(BOT), "no wallet row: a bot has no cash shop");
        Desk.Fill f = ex.desk().fillsOf(BOT, 10).get(0);
        assertEquals(Side.BUY, f.side());
        assertEquals(q.sell(), f.price());
        assertEquals(1_000, f.nx());
        conserved();
    }

    /**
     * 163-b260 proof: a bot's desk fill must be nameable from the audit alone. The desk keeps no orders, so the fill
     * row has buyOrder=sellOrder=0 and the audit used to print {@code buy#0(acct=? char=?) sell#0(acct=? char=?)}:
     * every bot desk fill looked unattributed, and a grep for {@code bot=} found only the book's LEAF lines.
     */
    @Test
    void theAuditNamesTheTraderOnADeskFill() throws Exception {
        List<String> audit = new java.util.ArrayList<>();
        ex.auditSink(audit::add);

        Response r = ex.deskBot("d1", bot, Side.BUY, 1_000);
        assertTrue(r.ok(), String.valueOf(r.message()));

        String line = audit.stream().filter(s -> s.contains("fill #")).reduce((a, b) -> b).orElseThrow();
        assertTrue(line.contains("desk bot=" + BOT), line);
        assertFalse(line.contains("acct=?"), line);
    }

    @Test
    void aBotSellsItsPurseAtTheDeskBuyQuote() throws Exception {
        Response buy = ex.deskBot("d1", bot, Side.BUY, 1_000);
        assertTrue(buy.ok(), "first it buys a purse: " + buy.code() + " " + buy.message());
        now.addAndGet(HOUR);   // a fresh hour: the buy used this one's allowance
        Exchange.DeskBotQuote q = ex.deskForBot(BOT);
        assertNotNull(q);
        long mesos = ex.desk().mesos(Side.SELL, 1_000, q.buy());
        long before = bot.mesos;

        Response r = ex.deskBot("d2", bot, Side.SELL, 1_000);
        assertTrue(r.ok(), String.valueOf(r.message()));
        assertEquals(0, ex.botNx(BOT), "the purse is empty");
        assertEquals(before + mesos, bot.mesos, "the bot was paid the desk's buy quote");
        assertEquals(houseNx0, store.balance(Exchange.deskBucket(Asset.NX)), "the NX came back to the house");
        // the bot's mesos are its own off-ledger bucket (as on every bot trade): what it pays in lands in the house
        // and what it takes out leaves the house, so the two move opposite
        assertEquals(houseMeso0 + (botMeso0 - bot.mesos), store.balance(Exchange.deskBucket(Asset.MESO)),
                "the house keeps the spread the bot paid");
        assertTrue(bot.mesos < botMeso0, "the round trip cost the bot the desk's spread");
        conserved();
    }

    @Test
    void theDeskIsEmptyForABotAsItIsForAPlayer() throws Exception {
        MemoryExchangeStore poor = new MemoryExchangeStore();
        poor.addAccount(HOUSE, 0);
        poor.addCharacter(HOUSE_CHAR, HOUSE, 0);
        now = new AtomicLong(1_800_000_000_000L);
        Exchange e = start(poor);

        Response buy = e.deskBot("d1", bot, Side.BUY, 1_000);
        assertFalse(buy.ok());
        assertEquals("DESK_EMPTY", buy.code());
        assertEquals(50_000_000, bot.mesos, "nothing was paid");

        // a purse the desk cannot pay for: it has no mesos to buy NX with
        FakeBotWallet rich = new FakeBotWallet(BOT, 50_000_000);
        assertEquals("DESK_EMPTY", e.deskBot("d2", rich, Side.SELL, 1_000).code());
        assertEquals(0, e.botNx(BOT), "and nothing changed hands");
    }

    @Test
    void oneBotsHourlyAllowanceStopsItAndReopensTheNextHour() throws Exception {
        System.setProperty(MarketConfig.TRADERS_NX_PER_BOT_HOUR, "1000");
        ex = start();

        Response first = ex.deskBot("d1", bot, Side.BUY, 1_000);
        assertTrue(first.ok(), first.code() + ": " + first.message());
        assertNull(ex.deskForBot(BOT), "no allowance left: the desk is closed to this bot this hour");
        Response again = ex.deskBot("d2", bot, Side.BUY, 1_000);
        assertFalse(again.ok());
        assertEquals("BOT_CAP", again.code());
        assertEquals(1_000, ex.botNx(BOT), "the purse is what it was");
        assertEquals(botMeso0 - ex.desk().mesos(Side.BUY, 1_000, ex.desk().sell()), bot.mesos,
                "and the bot paid once");

        assertNotNull(ex.deskForBot(BOT2), "another bot still has its own hour");
        now.addAndGet(HOUR);
        assertNotNull(ex.deskForBot(BOT), "the hour rolls over");
        Response later = ex.deskBot("d3", bot, Side.BUY, 1_000);
        assertTrue(later.ok(), "the next hour is whole again: " + later.code() + " " + later.message());
        assertEquals(2_000, ex.botNx(BOT));
        conserved();
    }

    @Test
    void theWorldAllowanceHoldsWithManyBots() throws Exception {
        System.setProperty(MarketConfig.TRADERS_NX_PER_BOT_HOUR, "10000");
        System.setProperty(MarketConfig.TRADERS_NX_WORLD_HOUR, "2000");
        ex = start();

        Response a = ex.deskBot("d1", bot, Side.BUY, 1_000);
        assertTrue(a.ok(), a.code() + ": " + a.message());
        Response b = ex.deskBot("d2", bot2, Side.BUY, 1_000);
        assertTrue(b.ok(), "the second bot has the rest of the world's hour: " + b.code() + " " + b.message());
        for (FakeBotWallet w : List.of(bot, bot2, bot3)) {
            Response r = ex.deskBot("d3-" + w.id, w, Side.BUY, 1_000);
            assertFalse(r.ok(), "the world's hour is spent");
            assertEquals("BOT_CAP", r.code());
        }
        assertEquals(houseNx0 - 2_000, store.balance(Exchange.deskBucket(Asset.NX)),
                "exactly the world's allowance left the house");
        now.addAndGet(HOUR);
        Response next = ex.deskBot("d4", bot3, Side.BUY, 1_000);
        assertTrue(next.ok(), "a new hour, a new allowance: " + next.code() + " " + next.message());
        conserved();
    }

    @Test
    void aBotTradesTheDeskOnlyWithItsFlagAndAnOpenDesk() throws Exception {
        assertEquals("BAD_REQUEST", ex.deskBot("d1", new FakeBotWallet(ACC, 50_000_000), Side.BUY, 1_000).code(),
                "a player's account is not a trader bot");
        assertEquals("BAD_REQUEST", ex.deskBot("d2", bot, Side.BUY, 150).code(), "whole lots of 100 NX");
        assertEquals("BAD_REQUEST", ex.deskBot("d3", bot, Side.BUY, 0).code());

        System.clearProperty(MarketConfig.TRADERS_NX);
        assertNull(ex.deskForBot(BOT), "no bot trades the desk with the flag off");
        assertEquals("BAD_REQUEST", ex.deskBot("d4", bot, Side.BUY, 1_000).code());
        System.setProperty(MarketConfig.TRADERS_NX, "true");

        ex.halt("test");
        assertEquals("HALTED", ex.deskBot("d5", bot, Side.BUY, 1_000).code());
        assertNull(ex.deskForBot(BOT));
    }

    @Test
    void aClosedDeskIsClosedForBotsToo() throws Exception {
        Exchange e = new Exchange(store, ExchangeLiveTest.cfg(100, HOUSE), store::online, store.finder(), now::get);
        e.siteReauth(SiteReauth.OFF);
        e.deskConfig(DeskConfig.defaults());   // off
        assertTrue(e.recover().isEmpty());
        assertNull(e.deskForBot(BOT));
        assertEquals("DESK_CLOSED", e.deskBot("d1", bot, Side.BUY, 1_000).code());

        Exchange houseLess = new Exchange(store, ExchangeLiveTest.cfg(100, 0), store::online, store.finder(),
                now::get);
        houseLess.siteReauth(SiteReauth.OFF);
        houseLess.deskConfig(DeskConfig.defaults().on());
        assertTrue(houseLess.recover().isEmpty());
        assertNull(houseLess.deskForBot(BOT), "no house account, no desk");
        assertEquals("DESK_CLOSED", houseLess.deskBot("d2", bot, Side.BUY, 1_000).code());
    }

    /**
     * The switch-off promise (brief item 3): with {@code -Dslums.exchange.traders.nx} at its default a trader's turn
     * is what it was in 1.6.2 with a desk running: the desk is not touched, no NX order is placed (placeBot refuses
     * it), and only the item tickers trade. Nothing about a bot's NX changes.
     */
    @Test
    void withTheFlagOffATradersTurnNeverTouchesTheDesk() throws Exception {
        System.clearProperty(MarketConfig.TRADERS_NX);
        ex = start();
        MarketTraders traders = new MarketTraders(new Random(7));
        MarketTraders.Roster roster = roster(bot);
        MarketConfig cfg = MarketConfig.allOn().withTraders(1, 0, false);
        for (int i = 0; i < 12; i++) {
            now.addAndGet(TimeUnit.MINUTES.toMillis(5));
            traders.tick(ex, now.get(), roster, cfg, null, null);
        }
        assertTrue(ex.desk().fillsOf(BOT, 50).isEmpty(), "the desk saw no bot");
        assertTrue(ex.openBotOrders(BOT, Ticker.NX).isEmpty(), "and no NX was put on the book");
        assertEquals(0, ex.botNx(BOT));
        conserved();
    }

    /**
     * The 1.6.3 behaviour end to end: a trader's turns reach the desk, at the desk's quote, in whole lots, inside the
     * hourly allowance, and the desk's fill is the same row a player's confirm writes.
     */
    @Test
    void aTradersTurnFillsAtTheDesk() throws Exception {
        MarketTraders traders = new MarketTraders(new Random(11));
        MarketTraders.Roster roster = roster(bot, TraderBrain.Archetype.WHALE);
        MarketConfig cfg = MarketConfig.allOn().withTraders(1, 0, true);
        long deskFills = 0;
        for (int i = 0; i < 400 && deskFills == 0; i++) {
            now.addAndGet(TimeUnit.MINUTES.toMillis(5));
            traders.tick(ex, now.get(), roster, cfg, null, null);
            deskFills = ex.desk().fillsOf(BOT, 100).size();
        }
        assertTrue(deskFills > 0, "a trader's turn reaches the desk");
        Desk.Fill f = ex.desk().fillsOf(BOT, 100).get(0);
        assertEquals(0, f.nx() % 100, "whole lots");
        assertTrue(f.nx() >= 1_000, "at least the ticker's minimum");
        assertEquals(ex.desk().mesos(f.side(), f.nx(), f.price()), f.mesos(), "the desk's own rounding");
        assertTrue(ex.botNx(BOT) > 0 || f.side() == Side.SELL);
        assertTrue(ex.openBotOrders(BOT, Ticker.NX).isEmpty(), "never an NX order on the book with the desk on");
        conserved();
    }

    private MarketTraders.Roster roster(FakeBotWallet w) {
        return roster(w, TraderBrain.Archetype.MAKER);
    }

    private MarketTraders.Roster roster(FakeBotWallet w, TraderBrain.Archetype a) {
        MarketTraders.Trader t = new MarketTraders.Trader(w.id, a, 3L);
        return new MarketTraders.Roster() {
            @Override
            public List<MarketTraders.Trader> traders() {
                return List.of(t);
            }

            @Override
            public LiveWallet walletOf(MarketTraders.Trader tr) {
                return w;
            }
        };
    }
}
