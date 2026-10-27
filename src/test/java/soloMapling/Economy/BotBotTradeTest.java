package soloMapling.Economy;

import client.BotClient;
import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.stats.EconomyCounters;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0: bot-to-bot trades from TRADE threads - the deal, the atomic swap, the caps, and what the market never sees. */
class BotBotTradeTest {

    static final int GLOVE_60 = 4000019;     // a common ETC item (the name is the old fixture's)
    static final int SCROLL = 2040804;
    static final int POTION = 2000000;
    static final int CASH_ITEM = 5040000;
    static final int QUEST_ITEM = 4031999;  // untradeable in EconomyTestSupport's sale rules
    static final int BASE = 1_000_000_000;

    EconomyTestSupport t;
    final AtomicLong clock = new AtomicLong(1_000 * EconomyTestSupport.DAY);
    final Map<String, Object> config = new HashMap<>();
    int nextId = BASE + 100;

    @BeforeEach
    void setUp() {
        t = new EconomyTestSupport();
        t.statics.put(GLOVE_60, 100_000L);
        t.statics.put(SCROLL, 100_000L);
        t.statics.put(POTION, 1_000L);
        t.statics.put(CASH_ITEM, 100_000L);
        t.statics.put(QUEST_ITEM, 100_000L);
        config.put("botBotTrade.agreeChance", "1.0");
        EconomyConfig.useForTest(config);
        BotBotTrade.resetForTest(clock::get);
        EconomyCounters.resetForTest();
    }

    @AfterEach
    void tearDown() {
        BotBotTrade.resetForTest(null);
        EconomyCounters.resetForTest();
        EconomyTestSupport.reset();
    }

    void set(String key, String value) {
        config.put(key, value);
        EconomyConfig.useForTest(config);
    }

    /** A level-70 roster bot (wealth ceiling 588k at 1x: the buyer keeps 147k) holding {@code meso}. */
    Character bot(int meso) throws Exception {
        Character c = Character.getDefault(new BotClient(0, 0));
        Field id = Character.class.getDeclaredField("id");
        id.setAccessible(true);
        id.setInt(c, nextId++);
        c.setName("Bot" + c.getId());
        c.setLevel(70);
        c.gainMeso(meso, false, false, false);
        return c;
    }

    static Item give(Character bot, int itemId) {
        Item it = new Item(itemId, (short) 0, (short) 1);
        assertTrue(bot.getInventory(it.getInventoryType()).addItem(it) > 0);
        return it;
    }

    static boolean holds(Character bot, Item item) {
        return bot.getInventory(item.getInventoryType()).list().contains(item);
    }

    static int count(Character bot, int itemId) {
        return bot.getInventory(itemId / 1_000_000 == 4 ? InventoryType.ETC : InventoryType.USE).countById(itemId);
    }

    @Test
    void anAgreedDealSwapsTheItemForTheMesosAtTheBandsMeetingPrice() throws Exception {
        Character seller = bot(100_000);
        Character buyer = bot(400_000);
        Item glove = give(seller, GLOVE_60);
        BotBotTrade.Deal d = BotBotTrade.plan(seller, buyer, new Random(1));
        assertNotNull(d);
        assertSame(seller, d.seller());
        assertTrue(d.agreed(), d.reason());
        assertEquals(95_000, d.price(), "0.95 x fair: the seller's floor and the buyer's ceiling");
        assertEquals("sell " + d.itemName() + " for 95,000", d.decision());

        BotBotTrade.Result r = BotBotTrade.execute(d);
        assertTrue(r.ok(), r.why());
        assertEquals(195_000, seller.getMeso());
        assertEquals(305_000, buyer.getMeso());
        assertEquals(500_000, seller.getMeso() + buyer.getMeso(), "mesos only moved between the two");
        assertFalse(holds(seller, glove));
        assertEquals(1, count(buyer, GLOVE_60));
        assertEquals(95_000, EconomyCounters.peek().botTransferMesos(), "a bot-to-bot transfer");
        assertEquals(0, EconomyCounters.peek().totalSources());
        assertEquals(0, EconomyCounters.peek().totalSinks());
        assertEquals(0, EconomyCounters.peek().tradeMesos(), "not counted as a player trade");
    }

    @Test
    void aBotToBotTradeNeverReachesThePriceBoard() throws Exception {
        Character seller = bot(100_000);
        Character buyer = bot(400_000);
        give(seller, GLOVE_60);
        assertTrue(BotBotTrade.execute(BotBotTrade.plan(seller, buyer, new Random(1))).ok());
        assertTrue(PriceBook.recentSamples(GLOVE_60).isEmpty(), "no sample: no sales.json, daily price or NX rate");
        assertTrue(t.store.samples.isEmpty(), "nothing written to pricesamples");

        // and the board refuses a sample between two bots whoever sends it
        Item it = EconomyTestSupport.item(GLOVE_60, 1);
        assertFalse(PriceBook.recordBetween(it, 95_000, 1, PriceBook.Source.BOT_TRADE,
                new PriceBook.Party(-seller.getId(), null, null), new PriceBook.Party(-buyer.getId(), null, null)));
        assertFalse(PriceBook.record(new PriceBook.Sample(GLOVE_60, 95_000, 95_000, 1, PriceBook.Source.BOT_TRADE,
                true, clock.get(), -seller.getId(), -buyer.getId())));
        assertTrue(PriceBook.recentSamples(GLOVE_60).isEmpty());
        // a bot selling to a player still prices it
        assertTrue(PriceBook.record(new PriceBook.Sample(GLOVE_60, 95_000, 95_000, 1, PriceBook.Source.BOT_TRADE,
                true, clock.get(), -seller.getId(), 77)));
    }

    @Test
    void aFailedSwapLeavesBothBotsAsTheyWere() throws Exception {
        Character seller = bot(100_000);
        Character buyer = bot(400_000);
        Item glove = give(seller, GLOVE_60);
        // the buyer's bag is full: the item cannot land, so everything is undone
        Inventory etc = buyer.getInventory(InventoryType.ETC);
        for (int i = 0; etc.getNextFreeSlot() > 0; i++) {
            assertTrue(etc.addItem(new Item(4001000 + i, (short) 0, (short) 1)) > 0);
        }
        assertEquals("no room in the buyer's bag", BotBotTrade.swap(seller, buyer, glove, 1, 95_000));
        assertEquals(100_000, seller.getMeso());
        assertEquals(400_000, buyer.getMeso());
        assertEquals(1, count(seller, GLOVE_60));
        assertEquals(0, count(buyer, GLOVE_60));

        // the item left the seller before the swap: the mesos go back
        Character seller2 = bot(100_000);
        Character buyer2 = bot(400_000);
        Item gone = give(seller2, GLOVE_60);
        seller2.getInventory(InventoryType.ETC).removeSlot(gone.getPosition());
        assertEquals("the item left the seller's bag", BotBotTrade.swap(seller2, buyer2, gone, 1, 95_000));
        assertEquals(100_000, seller2.getMeso());
        assertEquals(400_000, buyer2.getMeso());
        assertEquals(0, count(buyer2, GLOVE_60));

        // the buyer cannot pay: nothing moves
        Character seller3 = bot(100_000);
        Character poor = bot(1_000);
        Item g3 = give(seller3, GLOVE_60);
        assertEquals("the buyer could not pay", BotBotTrade.swap(seller3, poor, g3, 1, 95_000));
        assertEquals(100_000, seller3.getMeso());
        assertEquals(1_000, poor.getMeso());
        assertTrue(holds(seller3, g3));
    }

    @Test
    void executeChecksAgainAndAFailureMovesNothingAndCostsNoCap() throws Exception {
        Character seller = bot(100_000);
        Character buyer = bot(400_000);
        Item glove = give(seller, GLOVE_60);
        BotBotTrade.Deal d = BotBotTrade.plan(seller, buyer, new Random(1));
        seller.getInventory(InventoryType.ETC).removeSlot(glove.getPosition());   // sold elsewhere meanwhile
        BotBotTrade.Result r = BotBotTrade.execute(d);
        assertFalse(r.ok());
        assertEquals("the seller no longer has it", r.why());
        assertEquals(100_000, seller.getMeso());
        assertEquals(400_000, buyer.getMeso());
        assertEquals(0, EconomyCounters.peek().botTransferMesos());
        assertTrue(BotBotTrade.capsLeft(seller.getId(), buyer.getId(), clock.get()));
    }

    @Test
    void theBuyerSaysNoWhenTheMoneyDoesNotWorkAndNothingMoves() throws Exception {
        Character seller = bot(100_000);
        Character buyer = bot(200_000);   // 200k less its 147k reserve: 53k to spend
        give(seller, GLOVE_60);
        BotBotTrade.Deal d = BotBotTrade.plan(seller, buyer, new Random(1));
        assertNotNull(d);
        assertFalse(d.agreed());
        assertEquals("too pricey for them", d.reason());
        assertEquals("no deal", d.decision());
        assertFalse(BotBotTrade.execute(d).ok());
        assertEquals(200_000, buyer.getMeso());
        assertEquals(1, count(seller, GLOVE_60));

        // a seller the sale would push over its wealth ceiling keeps the item
        Character rich = bot(550_000);
        give(rich, GLOVE_60);
        BotBotTrade.Deal over = BotBotTrade.offer(rich, bot(400_000), new Random(1));
        assertFalse(over.agreed());
        assertEquals("the seller has enough mesos", over.reason());

        // and a buyer in a mood says no now and then
        set("botBotTrade.agreeChance", "0");
        BotBotTrade.Deal mood = BotBotTrade.plan(seller, bot(400_000), new Random(1));
        assertFalse(mood.agreed());
        assertEquals("not interested", mood.reason());
    }

    @Test
    void onlyBagItemsTheSaleRulesAllowAreOffered() throws Exception {
        Character seller = bot(100_000);
        give(seller, CASH_ITEM);
        give(seller, QUEST_ITEM);
        assertNull(BotBotTrade.plan(seller, bot(400_000), new Random(1)), "cash and untradeable items are never offered");

        Character s0 = bot(100_000);
        give(s0, SCROLL);
        assertNull(BotBotTrade.plan(s0, bot(400_000), new Random(1)), "never a scroll");
        assertFalse(BotBotTrade.offerable(new Item(1302000, (short) 1, (short) 1)), "never an equip");

        Item worn = new Item(GLOVE_60, (short) -5, (short) 1);
        assertFalse(BotBotTrade.offerable(worn), "never an item off the bot's body");
        Item stars = new Item(2070000, (short) 3, (short) 500);
        assertFalse(BotBotTrade.offerable(stars), "never its ammo");

        set("botBotTrade.maxPrice", "50000");
        Character s2 = bot(100_000);
        give(s2, GLOVE_60);
        assertNull(BotBotTrade.plan(s2, bot(400_000), new Random(1)), "over the price cap: nothing to offer");
    }

    @Test
    void potionsGoHalfTheStackAtMostSoTheSellerKeepsItsKit() throws Exception {
        Character seller = bot(100_000);
        Character buyer = bot(400_000);
        Item pots = new Item(POTION, (short) 0, (short) 100);
        assertTrue(seller.getInventory(InventoryType.USE).addItem(pots) > 0);
        BotBotTrade.Deal d = BotBotTrade.plan(seller, buyer, new Random(1));
        assertNotNull(d);
        assertEquals(50, d.quantity());
        assertEquals(47_500, d.price(), "0.95 x 50 x 1,000");
        assertTrue(BotBotTrade.execute(d).ok());
        assertEquals(50, count(seller, POTION));
        assertEquals(50, count(buyer, POTION));
        assertEquals(500_000, seller.getMeso() + buyer.getMeso());

        Character few = bot(100_000);
        assertTrue(few.getInventory(InventoryType.USE).addItem(new Item(POTION, (short) 0, (short) 15)) > 0);
        assertNull(BotBotTrade.plan(few, bot(400_000), new Random(1)), "15 potions: its own kit, not for sale");
    }

    @Test
    void onlyRosterBotsTrade() throws Exception {
        Character player = org.mockito.Mockito.mock(Character.class);
        org.mockito.Mockito.when(player.getClient()).thenReturn(org.mockito.Mockito.mock(client.Client.class));
        assertFalse(BotBotTrade.mayTrade(player));
        Character alt = bot(400_000);
        Field owner = Character.class.getDeclaredField("ownedAltOwner");
        owner.setAccessible(true);
        owner.set(alt, player);
        assertFalse(BotBotTrade.mayTrade(alt), "a player's owned alt never trades with bots on its own");
        Character seller = bot(100_000);
        give(seller, GLOVE_60);
        assertNull(BotBotTrade.plan(seller, alt, new Random(1)));
    }

    @Test
    void eachBotTradesThreeTimesADayAndTheWorldThirtyTimesAnHour() throws Exception {
        Character seller = bot(100_000);
        for (int i = 0; i < 3; i++) {
            give(seller, GLOVE_60);
            assertTrue(BotBotTrade.execute(BotBotTrade.plan(seller, bot(400_000), new Random(1))).ok(), "trade " + i);
        }
        give(seller, GLOVE_60);
        assertNull(BotBotTrade.plan(seller, bot(400_000), new Random(1)), "its fourth trade today");
        clock.addAndGet(EconomyTestSupport.DAY);
        assertNotNull(BotBotTrade.plan(seller, bot(400_000), new Random(1)), "a new day");

        BotBotTrade.resetForTest(clock::get);
        for (int i = 0; i < 30; i++) {
            assertTrue(BotBotTrade.claim(BASE + 5000 + 2 * i, BASE + 5001 + 2 * i, clock.get()), "world trade " + i);
        }
        assertFalse(BotBotTrade.claim(BASE + 9000, BASE + 9001, clock.get()), "the 31st this hour");

        BotBotTrade.resetForTest(clock::get);
        set("botBotTrade.worldPerHour", "1");
        Character s1 = bot(100_000);
        give(s1, GLOVE_60);
        assertTrue(BotBotTrade.execute(BotBotTrade.plan(s1, bot(400_000), new Random(1))).ok());
        Character s2 = bot(100_000);
        give(s2, GLOVE_60);
        Character b2 = bot(400_000);
        BotBotTrade.Deal planned = BotBotTrade.offer(s2, b2, new Random(1));
        assertNull(BotBotTrade.plan(s2, b2, new Random(1)), "the world's hour is used up");
        BotBotTrade.Result r = BotBotTrade.execute(planned);
        assertEquals("a trade cap is reached", r.why());
        assertEquals(400_000, b2.getMeso());
        clock.addAndGet(BotBotTrade.HOUR_MS);
        assertTrue(BotBotTrade.execute(planned).ok(), "an hour later");
        assertEquals(List.of(), PriceBook.recentSamples(GLOVE_60));
    }
}
