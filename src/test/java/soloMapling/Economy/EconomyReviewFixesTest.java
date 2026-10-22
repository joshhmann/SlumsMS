package soloMapling.Economy;

import client.Character;
import client.inventory.Equip;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Regression tests for the economy exploits the 1.5.0-rc3 pre-release review found. */
class EconomyReviewFixesTest {

    static final int MOKBI = 2070002, GLOVE_60 = 2040804, ORE = 4004000, SWORD = 1302000;

    EconomyTestSupport t;

    // As BotScrollingTest: load ItemInformationProvider once with no DB and no WZ behind it (equips need it).
    @org.junit.jupiter.api.BeforeAll
    static void initItemProvider() throws Exception {
        try (var db = org.mockito.Mockito.mockStatic(tools.DatabaseConnection.class);
             var providers = org.mockito.Mockito.mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(
                    org.mockito.ArgumentMatchers.any(provider.wz.WZFiles.class))).thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    // a blank item provider for the test (no slot max known, nothing restricted), as BotScrollingTest
    org.mockito.MockedStatic<server.ItemInformationProvider> itemData;

    @BeforeEach
    void setUp() {
        itemData = org.mockito.Mockito.mockStatic(server.ItemInformationProvider.class);
        itemData.when(server.ItemInformationProvider::getInstance).thenReturn(mock(server.ItemInformationProvider.class));
        t = new EconomyTestSupport();
        t.statics.put(MOKBI, 75_000L);       // the item pool's price: a whole set of stars
        t.statics.put(GLOVE_60, 1_000_000L);
        t.statics.put(ORE, 10_000L);
    }

    @AfterEach
    void tearDown() {
        itemData.close();
        PriceBook.useNpcValueForTest(null);
        PendingGrants.useForTest(null);
        EconomyTestSupport.reset();
    }

    private static PriceBook.Party p(int account, String ip) {
        return new PriceBook.Party(account, ip, null);
    }

    // ---- P0 1: the throwing-star mint ----

    @Test
    void aStarStackIsValuedAsOneSetNotPerStar() {
        Item stars = EconomyTestSupport.item(MOKBI, 9_999);
        assertEquals(1, PriceBook.units(stars));
        assertEquals(75_000, PriceBook.stackValue(stars), "a recharged Mokbi stack is one set");
        assertEquals(50, PriceBook.units(EconomyTestSupport.item(ORE, 50)), "other stacks still count each unit");

        TradeSessionTest.FakeWindow window = new TradeSessionTest.FakeWindow();
        TradeSessionTest.FakeWallet wallet = new TradeSessionTest.FakeWallet();
        window.items.add(stars);
        TradeSession s = new TradeSession(TradeSession.Mode.BOT_BUYS, window, wallet, PriceBook::fairValue);
        s.tick();
        assertTrue(window.posted > 0 && window.posted <= 71_250, "offers inside 0.95 x one set: " + window.posted);
        assertEquals(71_250, s.band().high());
    }

    @Test
    void aBotNeverPaysMuchMoreThanAnNpcForALowValueItem() {
        PriceBook.useNpcValueForTest(item -> item.getItemId() == MOKBI ? 11_000L : 1L);
        Item stars = EconomyTestSupport.item(MOKBI, 9_999);
        assertEquals(33_000, PriceBook.botBuyValue(stars), "3 x the NPC value (empty star + recharge)");
        TradeSessionTest.FakeWindow window = new TradeSessionTest.FakeWindow();
        window.items.add(stars);
        TradeSession s = new TradeSession(TradeSession.Mode.BOT_BUYS, window, new TradeSessionTest.FakeWallet(),
                PriceBook::botBuyValue);
        s.tick();
        assertTrue(s.band().high() <= 33_000, "the band follows the cap: " + s.band());
        assertEquals(1_000_000, PriceBook.botBuyValue(EconomyTestSupport.item(GLOVE_60, 1)),
                "a scroll an NPC prices at 1 meso has no NPC value; and it is not low-value");
    }

    @Test
    void aStarSaleLeavesAPerSetSample() {
        PriceBook.record(EconomyTestSupport.item(MOKBI, 9_999), 70_000, 9_999, PriceBook.Source.SHOP, true);
        PriceBook.record(EconomyTestSupport.item(MOKBI, 500), 80_000, 500, PriceBook.Source.SHOP, true);
        PriceBook.record(EconomyTestSupport.item(MOKBI, 800), 75_000, 800, PriceBook.Source.SHOP, true);
        assertEquals(75_000, PriceBook.quote(MOKBI, 0).median(), "mesos per set, not per star");
    }

    // ---- P0 2: @price pump and dump ----

    @Test
    void tradesBetweenOnePersonsAltsLeaveNoSample() {
        Item glove = EconomyTestSupport.item(GLOVE_60, 1);
        assertFalse(PriceBook.recordBetween(glove, 50_000_000, 1, PriceBook.Source.TRADE, p(1, "1.1.1.1"),
                p(1, "2.2.2.2")), "one account");
        assertFalse(PriceBook.recordBetween(glove, 50_000_000, 1, PriceBook.Source.TRADE, p(1, "9.9.9.9"),
                p(2, "9.9.9.9")), "one address now");
        assertFalse(PriceBook.recordBetween(glove, 50_000_000, 1, PriceBook.Source.TRADE,
                new PriceBook.Party(3, "3.3.3.3", "ABCD1234"), new PriceBook.Party(4, "4.4.4.4", "ABCD1234")),
                "one machine");
        // account 5 traded from 5.5.5.5 today; account 6 plays from there now, then 5 moves to another address
        assertTrue(PriceBook.recordBetween(EconomyTestSupport.item(ORE, 1), 10_000, 1, PriceBook.Source.TRADE,
                p(5, "5.5.5.5"), p(7, "7.7.7.7")));
        assertTrue(PriceBook.recordBetween(EconomyTestSupport.item(ORE, 1), 10_000, 1, PriceBook.Source.TRADE,
                p(6, "5.5.5.5"), p(8, "8.8.8.8")));
        assertFalse(PriceBook.recordBetween(glove, 50_000_000, 1, PriceBook.Source.TRADE, p(5, "10.0.0.1"),
                p(6, "10.0.0.2")), "an address both accounts used lately");
        assertEquals(0, PriceBook.quote(GLOVE_60, 0).samples());

        // through the trade window too
        List<Item> none = Collections.emptyList();
        assertFalse(PriceBook.onWindowTrade(p(9, "1.2.3.4"), List.of(glove), 50_000_000, p(9, "4.3.2.1"), none, 0));
        assertTrue(PriceBook.onWindowTrade(p(10, "1.2.3.5"), List.of(glove), 1_100_000, p(11, "4.3.2.2"), none, 0));
    }

    @Test
    void onePairLeavesOneSamplePerItemPerDay() {
        Item glove = EconomyTestSupport.item(GLOVE_60, 1);
        assertTrue(PriceBook.recordBetween(glove, 1_000_000, 1, PriceBook.Source.TRADE, p(1, "a"), p(2, "b")));
        assertFalse(PriceBook.recordBetween(glove, 1_000_000, 1, PriceBook.Source.TRADE, p(2, "b"), p(1, "a")),
                "the same pair, either way round");
        assertTrue(PriceBook.recordBetween(EconomyTestSupport.item(ORE, 1), 10_000, 1, PriceBook.Source.TRADE,
                p(1, "a"), p(2, "b")), "another item is another sample");
        t.now.addAndGet(EconomyTestSupport.DAY);
        assertTrue(PriceBook.recordBetween(glove, 1_000_000, 1, PriceBook.Source.TRADE, p(1, "a"), p(2, "b")),
                "the next day");
    }

    @Test
    void aPumpedOrDumpedMedianStaysNearTheStaticValue() {
        Item glove = EconomyTestSupport.item(GLOVE_60, 1);
        for (int i = 0; i < 10; i++) {
            assertTrue(PriceBook.recordBetween(glove, 500_000_000, 1, PriceBook.Source.TRADE,
                    p(100 + i, "10.0.0." + i), p(200 + i, "10.0.1." + i)));
        }
        assertEquals(500_000_000, PriceBook.quote(GLOVE_60, 0).median(), "the board shows what was traded");
        assertEquals(4_000_000, PriceBook.fairValue(glove), "but bots price it at most 4x its static value");

        t.statics.put(ORE, 10_000L);
        Item ore = EconomyTestSupport.item(ORE, 1);
        for (int i = 0; i < 10; i++) {
            PriceBook.recordBetween(ore, 1, 1, PriceBook.Source.TRADE, p(300 + i, "10.0.2." + i),
                    p(400 + i, "10.0.3." + i));
        }
        assertEquals(2_500, PriceBook.fairValue(ore), "and at least a quarter of it");
    }

    @Test
    void anEquipsBandIsWiderAndTiedToItsValuation() {
        t.statics.put(SWORD, 1_000_000L);   // EquipValuation's value for this sword (staticValueOf)
        Equip sword = new Equip(SWORD, (short) 0, 7);
        assertEquals(8_000_000, PriceBook.clampToStatic(sword, 50_000_000));
        assertEquals(6_000_000, PriceBook.clampToStatic(sword, 6_000_000), "inside 8x it stands");
        assertEquals(250_000, PriceBook.clampToStatic(sword, 1));
        assertEquals(4_000_000, PriceBook.clampToStatic(EconomyTestSupport.item(GLOVE_60, 1), 6_000_000));
    }

    @Test
    void playerSamplesReplaceBotSeedsOnlyWithDistinctSellersAndBuyers() {
        Item glove = EconomyTestSupport.item(GLOVE_60, 1);
        for (int i = 0; i < 4; i++) {
            PriceBook.record(glove, 1_000_000, 1, PriceBook.Source.BOT_SEED, false);
        }
        // one seller (with five buyer alts on different addresses) sells high five times
        for (int i = 0; i < 5; i++) {
            PriceBook.recordBetween(glove, 3_000_000, 1, PriceBook.Source.TRADE, p(1, "1.0.0.1"),
                    p(10 + i, "2.0.0." + i));
        }
        PriceBook.Quote q = PriceBook.quote(GLOVE_60, 0);
        assertFalse(q.playerOnly(), "one seller cannot take the board over");
        assertEquals(9, q.samples(), "the bot seeds still count");
        for (int i = 0; i < 2; i++) {
            PriceBook.recordBetween(glove, 3_000_000, 1, PriceBook.Source.TRADE, p(20 + i, "3.0.0." + i),
                    p(30 + i, "4.0.0." + i));
        }
        assertTrue(PriceBook.quote(GLOVE_60, 0).playerOnly(), "three sellers and buyers: players decide");
    }

    // ---- P2 7: chat haggling names a last price ----

    @Test
    void chatHagglingCountsItsRoundsAndNamesALastPrice() {
        BotDirectTrade.Quote q = new BotDirectTrade.Quote(EconomyTestSupport.item(GLOVE_60, 1), 1_200_000, 0);
        List<PriceBand.Kind> kinds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            BotDirectTrade.Haggled h = BotDirectTrade.haggleQuote(q, 1, false, 0);
            kinds.add(h.decision().kind());
            q = h.quote();
        }
        assertEquals(4, q.rounds());
        assertEquals(PriceBand.Kind.COUNTER, kinds.get(0));
        assertEquals(PriceBand.Kind.FINAL, kinds.get(3), "the 4th counter is the last price: " + kinds);
        assertEquals(950_000, q.price(), "its floor");
    }

    // ---- P2 8: meter writes in any order ----

    @Test
    void meterWritesLandingOutOfOrderStillLeaveTheLatestTotal() {
        List<Runnable> queued = new ArrayList<>();
        LocalDate day = LocalDate.ofEpochDay(t.now.get() / EconomyTestSupport.DAY);
        EconomyMeters.useForTest(t.store, queued::add, () -> day);
        EconomyMeters.add(EconomyMeters.TAX, 5);
        EconomyMeters.add(EconomyMeters.TAX, 7);
        EconomyMeters.add(EconomyMeters.TAX, 1);
        Collections.reverse(queued);   // the pool ran them backwards
        queued.forEach(Runnable::run);
        assertEquals(13L, t.store.meterRows.get(day + "|" + EconomyMeters.TAX));
    }

    // ---- P2 9: gifts per account ----

    @Test
    void anAccountsAltsShareItsDailyGifts() {
        assertTrue(BotGifts.claimAccount(42));
        assertTrue(BotGifts.claimAccount(42));
        assertFalse(BotGifts.claimAccount(42), "two a day for the account, whichever alt");
        assertTrue(BotGifts.claimAccount(43), "another account has its own");
        BotGifts.releaseAccount(42);
        assertTrue(BotGifts.claimAccount(42), "a gift that did not happen gives its claim back");
        assertFalse(EconomyMeters.line().contains(EconomyMeters.GIFT_ACCOUNT_PREFIX), EconomyMeters.line());
    }

    // ---- P2 10: the resale hand-off moves the item bought ----

    @Test
    void theResaleHandOffFindsTheItemBoughtNotAnyOfItsId() {
        Equip own = new Equip(SWORD, (short) 1, 7);
        own.setWatk((short) 60);   // the bot's own, better sword
        Equip bought = new Equip(SWORD, (short) 0, 7);
        bought.setWatk((short) 20);
        Equip boughtCopy = (Equip) bought.copy();   // a shop hands over a copy
        boughtCopy.setPosition((short) 2);
        assertSame(boughtCopy, BotBuying.findBought(List.of(own, boughtCopy), bought));
        assertSame(bought, BotBuying.findBought(List.of(own, boughtCopy, bought), bought), "the very instance first");
        assertNull(BotBuying.findBought(List.of(own), bought), "not there: the bot keeps what it has");

        Item flagged = EconomyTestSupport.item(ORE, 100);
        flagged.setFlag((short) 8);
        Item plain = EconomyTestSupport.item(ORE, 50);
        assertSame(plain, BotBuying.findBought(List.of(flagged, plain), EconomyTestSupport.item(ORE, 10)));
    }

    // ---- P2 11: an item a bot cannot take back is kept, not lost ----

    @Test
    void anItemABotCannotTakeBackWaitsForIt() {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(77);
        when(bot.getName()).thenReturn("Bob");
        boolean[] room = {false};
        List<Item> got = new ArrayList<>();
        PendingGrants.useForTest((b, item) -> room[0] && got.add(item));
        Item glove = EconomyTestSupport.item(GLOVE_60, 1);
        assertFalse(PendingGrants.giveBack(bot, glove, "test"));
        assertEquals(1, PendingGrants.waiting(77));
        assertEquals(0, PendingGrants.deliver(bot), "still no room");
        room[0] = true;
        assertEquals(1, PendingGrants.deliver(bot));
        assertEquals(List.of(glove), got);
        assertEquals(0, PendingGrants.waiting(77));
    }

    // ---- owner decision: bot shops on channels 1-3 ----

    @Test
    void botShopsSpreadOverChannelsOneToThreeWithChannelOneBusiest() {
        BotShops.resetForTest(t.now::get);
        try {
            int[] targets = BotShops.channelTargets();
            assertEquals(List.of(4, 2, 2), java.util.Arrays.stream(targets).boxed().toList());
            boolean[] all = {true, true, true};
            for (int bot = 1; bot <= 8; bot++) {
                BotShops.putShopForTest(bot, BotShops.pickChannel(BotShops.openPerChannel(), targets, all));
            }
            assertEquals(List.of(4, 2, 2), java.util.Arrays.stream(BotShops.openPerChannel()).boxed().toList(),
                    "the world cap of 8 as 4/2/2");
            assertEquals(1, BotShops.pickChannel(new int[]{0, 0, 0}, targets, all), "the first goes to channel 1");
            assertEquals(3, BotShops.pickChannel(new int[]{4, 2, 0}, targets, new boolean[]{true, true, true}));
            assertEquals(1, BotShops.pickChannel(new int[]{0, 0, 0}, targets, new boolean[]{true, false, false}),
                    "a channel that does not run gets none");
            assertEquals(1, BotShops.pickChannel(new int[]{4, 2, 2}, targets, all),
                    "past every target (a GM's extra) the least loaded for its share");
        } finally {
            BotShops.resetForTest(null);
        }
    }

    @Test
    void aBotCannotOpenShopsOnTwoChannels() {
        BotShops.resetForTest(t.now::get);
        try {
            Character bot = mock(Character.class);
            when(bot.getId()).thenReturn(501);
            when(bot.getName()).thenReturn("Shopkeep");
            BotShops.putShopForTest(501, 2);
            List<String> out = BotShops.open(bot, 1, 0, null, true);
            assertEquals(List.of("Shopkeep already has a shop open on channel 2"), out);
            assertEquals(List.of(0, 1, 0), java.util.Arrays.stream(BotShops.openPerChannel()).boxed().toList());

            Character other = mock(Character.class);
            when(other.getId()).thenReturn(502);
            when(other.getName()).thenReturn("Racer");
            assertTrue(BotShops.tryOpeningForTest(502), "one open in flight");
            assertEquals(List.of("Racer is opening a shop already"), BotShops.open(other, 3, 0, null, true),
                    "a second open at the same moment, on another channel, is refused");
        } finally {
            BotShops.resetForTest(null);
        }
    }

    @Test
    void configNamesAreTheDocumentedOnes() {
        EconomyConfig.useForTest(Map.of("priceBook.bandHigh", "2"));
        Item glove = EconomyTestSupport.item(GLOVE_60, 1);
        assertEquals(2_000_000, PriceBook.clampToStatic(glove, 9_000_000));
    }
}
