package soloMapling.ArtificialPlayer.BotScrollSystem;

import client.BotClient;
import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.id.ItemId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.HiredMerchant;
import server.maps.PlayerShopItem;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules.Appetite;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules.ScrollFacts;
import soloMapling.ArtificialPlayer.BotSupplySystem.ShopCatalog;
import soloMapling.Economy.EconomyConfig;

import java.awt.Point;
import java.lang.reflect.Field;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Ticket 11 on real bot Characters, real inventories and real equips: only the item data (odds, names, values) and
 * the dice are stubbed, so every outcome can be forced.
 */
class BotScrollingTest {

    static final int SWORD = 1302000;
    static final int S60 = 2043001, S10 = 2043002, DARK70 = 2043004, S100 = 2043000;

    /** The forced results of the next rolls. */
    final Deque<BotScrolling.Outcome> dice = new ArrayDeque<>();
    final List<String> megaphones = new ArrayList<>();
    final AtomicLong now = new AtomicLong(1_000_000_000L);
    int nextId = 1_000_500;

    final BotScrolling.Facts facts = new BotScrolling.Facts() {
        @Override
        public ScrollFacts scroll(int id) {
            return switch (id) {
                case S100 -> new ScrollFacts(id, 100, 0, 10);
                case S60 -> new ScrollFacts(id, 60, 0, 23);
                case S10 -> new ScrollFacts(id, 10, 0, 60);
                case DARK70 -> new ScrollFacts(id, 70, 50, 23);
                default -> null;
            };
        }

        @Override public List<Integer> reqs(int scrollId) { return List.of(); }
        @Override public List<Integer> catalogFor(int equipItemId) { return List.of(S100, S60, S10, DARK70); }
        @Override public int totalSlots(Equip equip) { return 7; }
        @Override public boolean cash(int itemId) { return false; }
        @Override public String name(int itemId) { return "Sword"; }
        @Override public Integer marketValue(int itemId) { return itemId == S60 ? 200_000 : 50_000; }
        @Override public Integer equipValue(Equip equip) { return 100_000 + 50_000 * equip.getLevel(); }
    };

    /** The server's roll, with the result forced: same slot and stat rules as scrollEquipWithId. */
    final BotScrolling.Roller roller = (equip, scrollId, white) -> {
        BotScrolling.Outcome o = dice.isEmpty() ? BotScrolling.Outcome.FAIL : dice.poll();
        if (o == BotScrolling.Outcome.SUCCESS) {
            equip.setUpgradeSlots((byte) (equip.getUpgradeSlots() - 1));
            equip.setLevel((byte) (equip.getLevel() + 1));
            equip.setWatk((short) (equip.getWatk() + 2));
            return equip;
        }
        if (!white) {
            equip.setUpgradeSlots((byte) (equip.getUpgradeSlots() - 1));
        }
        return o == BotScrolling.Outcome.CURSE ? null : equip;
    };

    // As WeaponTintHandlerTest: load ItemInformationProvider once with no DB and no WZ behind it.
    @org.junit.jupiter.api.BeforeAll
    static void initItemProvider() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(
                    org.mockito.ArgumentMatchers.any(provider.wz.WZFiles.class))).thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    // The item data for the whole test: a blank provider (no slot max known, nothing restricted), as headless.
    org.mockito.MockedStatic<server.ItemInformationProvider> itemData;

    @BeforeEach
    void setUp() {
        itemData = mockStatic(server.ItemInformationProvider.class);
        itemData.when(server.ItemInformationProvider::getInstance).thenReturn(mock(server.ItemInformationProvider.class));
        ScrollLedger.resetForTest();
        ScrollBudget.clearForTest();
        ScrollWants.clearForTest();
        BotConsignment.clearForTest();
        BotScrolling.useForTest(facts, roller, (bot, node, tokens, key) -> megaphones.add(node + " " + tokens),
                now::get);
        ScrollWants.useClockForTest(now::get);
    }

    @AfterEach
    void tearDown() {
        itemData.close();
        BotScrolling.useForTest(null, null, null, null);
        ScrollWants.useClockForTest(null);
        ScrollWants.clearForTest();
        BotConsignment.clearForTest();
    }

    private Character newBot(int level) throws Exception {
        Character bot = Character.getDefault(new BotClient(0, 0));
        Field id = Character.class.getDeclaredField("id");
        id.setAccessible(true);
        id.setInt(bot, nextId++);
        bot.setName("bot" + bot.getId());
        bot.setLevel(level);
        return bot;
    }

    private static Equip sword(Character bot, short position, int slots) {
        Equip e = new Equip(SWORD, position, slots);
        e.setWatk((short) 17);
        bot.getInventory(position < 0 ? InventoryType.EQUIPPED : InventoryType.EQUIP).addItemFromDB(e);
        return e;
    }

    private static void give(Character bot, int itemId, int qty) {
        assertTrue(BotHoldings.grantItem(bot, new Item(itemId, (short) 0, (short) qty)));
    }

    private static int count(Character bot, int itemId) {
        return bot.getInventory(InventoryType.USE).countById(itemId);
    }

    // ---- ticket 162 part 5: the EV planner (-Dslums.scroll.ev) ----

    private BotScrolling.Facts priced(java.util.function.IntFunction<Integer> prices) {
        return new BotScrolling.Facts() {
            @Override public ScrollFacts scroll(int id) { return facts.scroll(id); }
            @Override public List<Integer> reqs(int scrollId) { return List.of(); }
            @Override public List<Integer> catalogFor(int equipItemId) { return List.of(S100, S60, S10, DARK70); }
            @Override public int totalSlots(Equip equip) { return 7; }
            @Override public boolean cash(int itemId) { return false; }
            @Override public String name(int itemId) { return "Sword"; }
            @Override public Integer marketValue(int itemId) { return prices.apply(itemId); }
            @Override public Integer equipValue(Equip equip) { return 50_000; }
        };
    }

    @Test
    void evPlannerPicksAProfitableScrollAndKeepsOurDarkScrollRules() throws Exception {
        // 60% scrolls are cheap and the sure ones dear: a 60% pass is worth more than it costs.
        BotScrolling.useForTest(priced(id -> id == S60 ? 20_000 : id == SWORD ? 50_000 : 500_000), roller,
                (b, node, tokens, key) -> false, now::get);
        System.setProperty("slums.scroll.ev", "true");
        try {
            Character bot = newBot(50);
            Equip worn = sword(bot, (short) -11, 7);
            give(bot, DARK70, 3);
            // A normal bot never puts a dark scroll on worn gear, EV or not: nothing to do.
            assertNull(BotScrolling.plan(bot, Appetite.NORMAL, new Random(1)));
            give(bot, S60, 3);
            BotScrolling.Plan plan = BotScrolling.plan(bot, Appetite.NORMAL, new Random(1));
            assertNotNull(plan);
            assertSame(worn, plan.equip());
            assertEquals(S60, plan.choice().scrollId());
        } finally {
            System.clearProperty("slums.scroll.ev");
        }
    }

    // 1.6.2 bot audit G10: the EV planner filtered the scrolls with its own, looser rules, so a careful bot could put
    // a 10% scroll on worn gear without a white scroll - which ScrollRules.choose never does.
    @Test
    void evPlannerKeepsACarefulBotsLongShotRules() throws Exception {
        BotScrolling.useForTest(priced(id -> id == S10 ? 100 : id == SWORD ? 50_000 : 500_000), roller,
                (b, node, tokens, key) -> false, now::get);
        System.setProperty("slums.scroll.ev", "true");
        try {
            Character bot = newBot(50);
            sword(bot, (short) -11, 7);          // worn, and no white scroll held
            give(bot, S10, 3);
            assertNull(BotScrolling.plan(bot, Appetite.CAREFUL, new Random(1)),
                    "no 10% scroll on worn gear without a white scroll");
        } finally {
            System.clearProperty("slums.scroll.ev");
        }
    }

    @Test
    void evPlannerStillAllowsACarefulBotsLongShotUnderAWhiteScroll() throws Exception {
        EconomyConfig.useForTest(java.util.Map.of("scrolling.whiteOnLongShots", true));
        BotScrolling.useForTest(priced(id -> id == S10 ? 100 : id == SWORD ? 50_000 : 500_000), roller,
                (b, node, tokens, key) -> false, now::get);
        System.setProperty("slums.scroll.ev", "true");
        try {
            Character bot = newBot(50);
            sword(bot, (short) 5, 7);            // in the bag, not worn
            give(bot, ItemId.WHITE_SCROLL, 1);
            give(bot, S10, 3);
            BotScrolling.Plan plan = BotScrolling.plan(bot, Appetite.CAREFUL, new Random(1));
            assertNotNull(plan);
            assertEquals(S10, plan.choice().scrollId());
            assertTrue(plan.choice().whiteScroll(), "the white scroll pays for the attempt");
        } finally {
            System.clearProperty("slums.scroll.ev");
            EconomyConfig.useForTest(null);
        }
    }

    @Test
    void evPlannerDeclinesAScrollThatCostsMoreThanItAdds() throws Exception {
        // Fail-before: the old appetite rule (careful: the surest scroll) uses the 100% scroll whatever it costs.
        Character bot = newBot(50);
        sword(bot, (short) -11, 1);
        give(bot, S100, 1);
        BotScrolling.Facts pricey = new BotScrolling.Facts() {
            @Override public ScrollFacts scroll(int id) { return facts.scroll(id); }
            @Override public List<Integer> reqs(int scrollId) { return List.of(); }
            @Override public List<Integer> catalogFor(int equipItemId) { return List.of(S100); }
            @Override public int totalSlots(Equip equip) { return 7; }
            @Override public boolean cash(int itemId) { return false; }
            @Override public String name(int itemId) { return "Sword"; }
            @Override public Integer marketValue(int itemId) { return itemId == S100 ? 5_000_000 : 10_000; }
            @Override public Integer equipValue(Equip equip) { return 10_000; }
        };
        BotScrolling.useForTest(pricey, roller, (b, node, tokens, key) -> false, now::get);
        assertNotNull(BotScrolling.plan(bot, Appetite.CAREFUL, new Random(1)), "the appetite rule scrolls anyway");
        System.setProperty("slums.scroll.ev", "true");
        try {
            // One slot left and a scroll worth far more than the piece can ever be worth: stopping wins.
            assertNull(BotScrolling.plan(bot, Appetite.CAREFUL, new Random(1)));
        } finally {
            System.clearProperty("slums.scroll.ev");
        }
    }

    @Test
    void evPlannerDeclinesAScrollWhoseMarketPriceOutgrewWhatItAdds() throws Exception {
        // 162: the planner prices a held scroll at what it sells for now (ScrollMarketPrice), not at the yaml's price.
        // The sword's value curve is made by 60% scrolls (20k, +23); the bot holds a 100% scroll (+10) that the yaml
        // prices at 10k: worth using (+10 on this piece is worth ~33k). Then the market shows it sells for ~40k.
        Character bot = newBot(50);
        sword(bot, (short) -11, 1);
        give(bot, S100, 1);
        BotScrolling.Facts market = new BotScrolling.Facts() {
            @Override public ScrollFacts scroll(int id) { return facts.scroll(id); }
            @Override public List<Integer> reqs(int scrollId) { return List.of(); }
            @Override public List<Integer> catalogFor(int equipItemId) { return List.of(S60); }
            @Override public int totalSlots(Equip equip) { return 7; }
            @Override public boolean cash(int itemId) { return false; }
            @Override public String name(int itemId) { return "Sword"; }
            @Override public Integer marketValue(int itemId) {
                return itemId == SWORD ? 50_000 : itemId == S60 ? 20_000 : itemId == S100 ? 10_000 : null;
            }
            @Override public Integer equipValue(Equip equip) { return 50_000; }
        };
        BotScrolling.useForTest(market, roller, (b, node, tokens, key) -> false, now::get);
        soloMapling.Economy.EconomyConfig.useForTest(java.util.Map.of());
        soloMapling.Economy.PriceBook.useForTest(new soloMapling.Economy.EconomyStore.Memory(), Runnable::run, now::get,
                item -> item.getItemId() == S100 ? 10_000L : 0L);
        System.setProperty("slums.scroll.ev", "true");
        try {
            BotScrolling.Plan plan = BotScrolling.plan(bot, Appetite.NORMAL, new Random(1));
            assertNotNull(plan, "at the yaml's 10k the 100% scroll is worth using");
            assertEquals(S100, plan.choice().scrollId());
            // two weeks of real sales at 40k
            for (int d = 0; d < 14; d++) {
                for (int i = 0; i < 8; i++) {
                    soloMapling.Economy.PriceBook.record(new soloMapling.Economy.PriceBook.Sample(S100, 40_000,
                            40_000, 1, soloMapling.Economy.PriceBook.Source.SHOP, true, now.get()));
                }
                now.addAndGet(86_400_000L);
            }
            long learned = soloMapling.Economy.ScrollMarketPrice.price(S100, 10_000L);
            assertTrue(learned > 35_000, "learned " + learned);
            assertNull(BotScrolling.plan(bot, Appetite.NORMAL, new Random(1)),
                    "selling it beats using it: the bot keeps the scroll");
        } finally {
            System.clearProperty("slums.scroll.ev");
            soloMapling.Economy.PriceBook.useForTest(null, null, null, null);
            soloMapling.Economy.EconomyConfig.useForTest(null);
        }
    }

    // 162 runfix (t-sc-1/t-sc-2 on TEST 10-30): the QA kit on a bag sword, priced as the item pool prices them. The
    // 100% scroll has no market price (the pool does not list it), as 2043000/2043003/2043023 on the real catalog.
    private BotScrolling.Facts qaKitPrices() {
        return priced(id -> id == S60 ? Integer.valueOf(1_300_000) : id == S10 ? Integer.valueOf(450_000) : null);
    }

    private Character qaKitBot() throws Exception {
        Character bot = newBot(49);
        sword(bot, (short) 1, 5);
        give(bot, S60, 4);
        give(bot, S10, 2);
        give(bot, DARK70, 1);
        return bot;
    }

    @Test
    void evPlannerDoesNotCountAnUnpricedScrollAsFreeInTheValueCurve() throws Exception {
        // Fail-before: the unpriced 100% scroll went into the reproduction curve at 0 meso, so every +stat was "free"
        // to make again, the curve stayed flat at the base and no persona would use any scroll it held.
        BotScrolling.useForTest(qaKitPrices(), roller, (b, node, tokens, key) -> false, now::get);
        System.setProperty("slums.scroll.ev", "true");
        try {
            Character bot = qaKitBot();
            for (Appetite a : Appetite.values()) {
                assertNotNull(BotScrolling.plan(bot, a, new Random(1)), a + " scrolls its sword");
            }
        } finally {
            System.clearProperty("slums.scroll.ev");
        }
    }

    @Test
    void evPlannerKeepsTheCarefulPersonaOffLongShots() throws Exception {
        // Fail-before: the planner prices the 10% scroll's +60 above the 60%'s +23 and a careful bot took the 10%.
        BotScrolling.useForTest(qaKitPrices(), roller, (b, node, tokens, key) -> false, now::get);
        System.setProperty("slums.scroll.ev", "true");
        try {
            Character bot = qaKitBot();
            assertEquals(S60, BotScrolling.plan(bot, Appetite.CAREFUL, new Random(1)).choice().scrollId());
        } finally {
            System.clearProperty("slums.scroll.ev");
        }
    }

    @Test
    void evPlannerLetsAGamblerGambleWhenNoPlayIsWorthItsMeso() throws Exception {
        // Fail-before: one slot and a 5M 100% scroll on a 10k sword: EV stops every persona, the gambler too.
        Character bot = newBot(50);
        sword(bot, (short) 1, 1);
        give(bot, S100, 1);
        BotScrolling.useForTest(priced(id -> id == S100 ? Integer.valueOf(5_000_000) : id == SWORD ? Integer.valueOf(10_000) : null), roller,
                (b, node, tokens, key) -> false, now::get);
        System.setProperty("slums.scroll.ev", "true");
        try {
            assertNull(BotScrolling.plan(bot, Appetite.NORMAL, new Random(1)));
            assertNull(BotScrolling.plan(bot, Appetite.CAREFUL, new Random(1)));
            BotScrolling.Plan plan = BotScrolling.plan(bot, Appetite.GAMBLER, new Random(1));
            assertNotNull(plan, "a gambler gambles");
            assertEquals(S100, plan.choice().scrollId());
        } finally {
            System.clearProperty("slums.scroll.ev");
        }
    }

    @Test
    void aPassChangesTheStatsForRealAndUsesUpTheScroll() throws Exception {
        Character bot = newBot(50);
        Equip e = sword(bot, (short) -11, 7);
        give(bot, S60, 2);
        dice.add(BotScrolling.Outcome.SUCCESS);

        BotScrolling.Plan plan = BotScrolling.plan(bot, Appetite.NORMAL, new Random(1));
        assertNotNull(plan);
        assertSame(e, plan.equip(), "worn gear first");
        BotScrolling.RollReport r = BotScrolling.rollOnce(bot, plan);

        assertEquals(BotScrolling.Outcome.SUCCESS, r.outcome());
        assertEquals(19, e.getWatk());
        assertEquals(6, e.getUpgradeSlots());
        assertEquals(1, count(bot, S60), "one scroll used");
        assertTrue(r.line().contains("60%") && r.line().contains("watk 17") && r.line().contains("watk 19"), r.line());
        assertEquals(1, ScrollLedger.success.get());
    }

    @Test
    void aCurseDestroysTheWornItemAndTheLossIsPersisted() throws Exception {
        Character bot = newBot(50);
        Equip e = sword(bot, (short) -11, 3);
        e.setLevel((byte) 3);
        give(bot, DARK70, 1);
        dice.add(BotScrolling.Outcome.CURSE);
        ScrollFacts dark = facts.scroll(DARK70);

        BotScrolling.RollReport r = BotScrolling.rollOnce(bot,
                new BotScrolling.Plan(e, new ScrollRules.Choice(DARK70, false), dark));

        assertEquals(BotScrolling.Outcome.CURSE, r.outcome());
        assertNull(bot.getInventory(InventoryType.EQUIPPED).getItem((short) -11), "the boomed sword is gone");
        assertTrue(BotHoldings.hasUnpersistedDebit(bot), "the store must write the loss, not restore the sword");
        assertEquals(1, ScrollLedger.curse.get());
        assertEquals(250_000, ScrollLedger.destroyedValue.get(), "its value before the roll is ledgered");
        assertEquals(1, megaphones.size(), "a 3-pass boom is news");
        assertTrue(megaphones.get(0).startsWith("ScrollBoom") && megaphones.get(0).contains("PASSED=3")
                && megaphones.get(0).contains("TOTAL=7") && megaphones.get(0).contains("ITEM=sword"), megaphones.get(0));
    }

    @Test
    void aCurseInTheBagDestroysThatItemOnly() throws Exception {
        Character bot = newBot(50);
        Equip worn = sword(bot, (short) -11, 0);
        Equip bag = sword(bot, (short) 1, 7);
        give(bot, DARK70, 1);
        dice.add(BotScrolling.Outcome.CURSE);

        BotScrolling.rollOnce(bot, new BotScrolling.Plan(bag, new ScrollRules.Choice(DARK70, false),
                facts.scroll(DARK70)));

        assertNull(bot.getInventory(InventoryType.EQUIP).getItem((short) 1));
        assertSame(worn, bot.getInventory(InventoryType.EQUIPPED).getItem((short) -11));
        assertTrue(megaphones.isEmpty(), "a clean boom is not megaphoned");
    }

    @Test
    void aWhiteScrollIsUsedAndSavesTheSlot() throws Exception {
        Character bot = newBot(50);
        Equip bag = sword(bot, (short) 1, 7);
        give(bot, S10, 1);
        give(bot, ItemId.WHITE_SCROLL, 1);
        dice.add(BotScrolling.Outcome.FAIL);

        assertNull(BotScrolling.plan(bot, Appetite.CAREFUL, new Random(1)),
                "1.5.0 rc3: by default a careful bot keeps its white scroll off a 10%");
        soloMapling.Economy.EconomyConfig.useForTest(java.util.Map.of("scrolling.whiteOnLongShots", "true"));
        BotScrolling.Plan plan;
        try {
            plan = BotScrolling.plan(bot, Appetite.CAREFUL, new Random(1));
        } finally {
            soloMapling.Economy.EconomyConfig.useForTest(null);
        }
        assertNotNull(plan);
        assertTrue(plan.choice().whiteScroll());
        BotScrolling.RollReport r = BotScrolling.rollOnce(bot, plan);

        assertTrue(r.white());
        assertEquals(7, bag.getUpgradeSlots(), "the white scroll kept the slot");
        assertEquals(0, count(bot, ItemId.WHITE_SCROLL));
        assertEquals(0, count(bot, S10));
        assertEquals(1, ScrollLedger.whiteUsed.get());
    }

    @Test
    void aBotStopsWhenTheSlotsRunOut() throws Exception {
        Character bot = newBot(50);
        sword(bot, (short) -11, 2);
        give(bot, S60, 5);
        for (int i = 0; i < 5; i++) {
            dice.add(BotScrolling.Outcome.FAIL);
        }
        int rolls = 0;
        BotScrolling.Plan plan;
        while ((plan = BotScrolling.plan(bot, Appetite.NORMAL, new Random(2))) != null) {
            assertNotNull(BotScrolling.rollOnce(bot, plan));
            rolls++;
        }
        assertEquals(2, rolls);
        assertEquals(3, count(bot, S60), "the scrolls it did not need are kept");
    }

    @Test
    void megaphoneOnlyForAPerfectItemAndOnlyOnceInTheCooldown() throws Exception {
        Character bot = newBot(50);
        Equip e = sword(bot, (short) -11, 1);
        e.setLevel((byte) 6);
        give(bot, S60, 1);
        dice.add(BotScrolling.Outcome.SUCCESS);
        BotScrolling.rollOnce(bot, BotScrolling.plan(bot, Appetite.NORMAL, new Random(1)));
        assertEquals(1, megaphones.size());
        assertTrue(megaphones.get(0).startsWith("ScrollPerfect") && megaphones.get(0).contains("PASSED=7"),
                megaphones.get(0));

        Equip other = sword(bot, (short) 2, 1);
        other.setLevel((byte) 6);
        give(bot, S60, 1);
        dice.add(BotScrolling.Outcome.SUCCESS);
        BotScrolling.rollOnce(bot, new BotScrolling.Plan(other, new ScrollRules.Choice(S60, false), facts.scroll(S60)));
        assertEquals(1, megaphones.size(), "one per bot per 30 minutes");

        Equip third = sword(bot, (short) 3, 7);
        give(bot, S60, 1);
        dice.add(BotScrolling.Outcome.FAIL);
        now.addAndGet(BotScrolling.MEGAPHONE_COOLDOWN_MS + 1);
        BotScrolling.rollOnce(bot, new BotScrolling.Plan(third, new ScrollRules.Choice(S60, false), facts.scroll(S60)));
        assertEquals(1, megaphones.size(), "a plain fail is never megaphoned");
    }

    @Test
    void npcScrollsAreBoughtWithinTheBudgetAndGmShopsAreIgnored() throws Exception {
        Character bot = newBot(50);
        sword(bot, (short) -11, 7);
        bot.gainMeso(1_000_000, false, false, false);
        ShopCatalog.Site weaponStore = new ShopCatalog.Site(101000000, 1032103, 1032103, new Point(0, 0), List.of(
                new ShopCatalog.Listing(0, S100, 70_000, 0)));
        ShopCatalog.Site gmShop = new ShopCatalog.Site(101000000, 9999998, 9999998, new Point(0, 0), List.of(
                new ShopCatalog.Listing(0, S60, 1, 0)));

        int bought = BotScrolling.buyAtNpc(bot, List.of(weaponStore, gmShop));

        // spare 650,000: a quarter is 162,500, so each 70,000 buy fits; the count is the 3-per-scroll cap
        assertEquals(3, bought);
        assertEquals(3, count(bot, S100));
        assertEquals(0, count(bot, S60), "a 1-meso GM shop is never a bot's shop");
        assertEquals(1_000_000 - 210_000, bot.getMeso());
        assertEquals(210_000, ScrollLedger.mesos[ScrollLedger.Source.NPC.ordinal()].get());
    }

    @Test
    void aPoorBotBuysNothing() throws Exception {
        Character bot = newBot(50);
        sword(bot, (short) -11, 7);
        bot.gainMeso(360_000, false, false, false);   // reserve at lv 50 is 350,000
        ShopCatalog.Site store = new ShopCatalog.Site(1, 1, 1, new Point(0, 0), List.of(
                new ShopCatalog.Listing(0, S100, 70_000, 0)));
        assertEquals(0, BotScrolling.buyAtNpc(bot, List.of(store)));
        assertEquals(360_000, bot.getMeso());
    }

    @Test
    void anFmShopperBuysAWantedScrollWithTheWantingBotsMesos() throws Exception {
        Character wanter = newBot(70);
        sword(wanter, (short) -11, 7);
        wanter.gainMeso(5_000_000, false, false, false);
        Character shopper = newBot(10);
        assertEquals(1, BotScrolling.postWants(wanter, Appetite.CAREFUL, new Random(1)));
        assertTrue(ScrollWants.wanted(S100, 50_000));
        assertFalse(ScrollWants.wanted(S100, 60_000), "not over the scroll's value");

        String forBot = ScrollWants.fill(shopper, S100, 40_000, true, () ->
                BotHoldings.spendMeso(shopper, 40_000) && BotHoldings.grantItem(shopper, new Item(S100, (short) 0, (short) 1)));

        assertEquals(wanter.getName(), forBot);
        assertEquals(5_000_000 - 40_000, wanter.getMeso(), "the wanting bot paid");
        assertEquals(0, shopper.getMeso(), "the shopper passed the price on, its allowance untouched");
        assertEquals(1, count(wanter, S100));
        assertEquals(0, count(shopper, S100));
        assertFalse(ScrollWants.wanted(S100, 1), "the want is filled");
        assertEquals(1, ScrollLedger.bought[ScrollLedger.Source.PLAYER_SHOP.ordinal()].get());
    }

    @Test
    void aFailedFmPurchaseGivesTheMesosBack() throws Exception {
        Character wanter = newBot(70);
        sword(wanter, (short) -11, 7);
        wanter.gainMeso(5_000_000, false, false, false);
        Character shopper = newBot(10);
        BotScrolling.postWants(wanter, Appetite.CAREFUL, new Random(1));

        assertNull(ScrollWants.fill(shopper, S100, 40_000, false, () -> false));

        assertEquals(5_000_000, wanter.getMeso());
        assertEquals(0, shopper.getMeso());
        assertTrue(ScrollWants.wanted(S100, 40_000), "still wanted");
        assertEquals(ScrollBudget.dailyCap(70), ScrollBudget.maxPrice(wanter.getId(), 70, 1_000_000_000L, false),
                "the budget reservation is given back");
    }

    /** 1.5.0 rc3 review: afterRoll sent spare equips to BotConsignment.list with no SaleRules / godly check. */
    @Test
    void aGodlyPieceABotScrolledIsNeverPutOnSale() {
        soloMapling.Economy.SaleRules.useForTest(java.util.Set.of(), id -> false, id -> false);
        try {
            org.mockito.Mockito.when(server.ItemInformationProvider.getInstance().getEquipStats(SWORD))
                    .thenReturn(java.util.Map.of("PAD", 17, "tuc", 7, "reqLevel", 50));
            Equip fair = new Equip(SWORD, (short) 1, 0);
            fair.setLevel((byte) 7);
            fair.setWatk((short) (17 + 14));   // seven 60% passes: +14, under the godly line (2 x 7 + 5)
            Equip godly = new Equip(SWORD, (short) 2, 0);
            godly.setLevel((byte) 7);
            godly.setWatk((short) (17 + 35));   // seven 10% passes: +35
            assertNull(BotConsignment.whyNotList(fair), "an average or good piece may be listed");
            assertNull(BotConsignment.whyNotList(godly), "owner 2026-10-29: a bot may consign its own godly piece");
            assertEquals("godly gear", soloMapling.Economy.SaleRules.whyNot(godly), "gifts, Mega Buy, bot-to-bot still refuse it");
        } finally {
            soloMapling.Economy.SaleRules.useForTest(null, null, null);
        }
    }

    @Test
    void aSoldListingPaysTheBotAndAnUnsoldOneComesBack() throws Exception {
        Character bot = newBot(50);
        Equip sold = sword(bot, (short) 1, 0);
        Equip unsold = sword(bot, (short) 2, 0);
        HiredMerchant shop = mock(HiredMerchant.class);
        org.mockito.Mockito.when(shop.isOpen()).thenReturn(true);
        PlayerShopItem soldItem = new PlayerShopItem(sold, (short) 1, 300_000);
        PlayerShopItem unsoldItem = new PlayerShopItem(unsold, (short) 1, 300_000);
        org.mockito.Mockito.when(shop.withdrawListing(unsoldItem)).thenReturn(true);
        bot.getInventory(InventoryType.EQUIP).removeSlot((short) 1);
        bot.getInventory(InventoryType.EQUIP).removeSlot((short) 2);
        BotConsignment.putForTest(bot.getId(), shop, soldItem, 300_000);
        BotConsignment.useClockForTest(now::get);
        BotConsignment.putForTest(bot.getId(), shop, unsoldItem, 300_000);
        assertTrue(BotConsignment.isConsigned(soldItem), "FM shopper bots leave it alone");

        soldItem.setBundles((short) 0);
        BotConsignment.onSold(soldItem, 291_000);
        assertEquals(291_000, BotConsignment.owedForTest(bot.getId()));
        now.addAndGet(BotConsignment.LISTING_TTL_MS + 1);
        BotConsignment.collect(bot);
        BotConsignment.useClockForTest(null);

        assertEquals(291_000, bot.getMeso(), "paid what the player paid, after the fee");
        assertEquals(1, bot.getInventory(InventoryType.EQUIP).listById(SWORD).size(), "the unsold sword is back");
        assertEquals(0, BotConsignment.open());
    }

    @Test
    void aFinishedFailureWithNoValueIsTossedAndAGoodOneListedOrKept() throws Exception {
        Character bot = newBot(50);
        Equip junk = sword(bot, (short) 1, 0);
        BotScrolling.useForTest(new BotScrolling.Facts() {
            @Override public ScrollFacts scroll(int id) { return facts.scroll(id); }
            @Override public List<Integer> reqs(int s) { return List.of(); }
            @Override public List<Integer> catalogFor(int e) { return List.of(); }
            @Override public int totalSlots(Equip e) { return 7; }
            @Override public boolean cash(int i) { return false; }
            @Override public String name(int i) { return "Sword"; }
            @Override public Integer marketValue(int i) { return null; }
            @Override public Integer equipValue(Equip e) { return null; }
        }, roller, (b, n, t, k) -> true, now::get);

        assertEquals("tossed it", BotScrolling.afterRoll(bot, junk, BotScrolling.Outcome.FAIL, false));
        assertNull(bot.getInventory(InventoryType.EQUIP).getItem((short) 1));
        assertEquals(1, ScrollLedger.dropped.get());

        BotScrolling.useForTest(facts, roller, (b, n, t, k) -> true, now::get);
        Equip good = sword(bot, (short) 2, 0);
        good.setLevel((byte) 5);
        // no bot shop open in a test world: the bot keeps it rather than lose it
        assertNull(BotScrolling.afterRoll(bot, good, BotScrolling.Outcome.SUCCESS, false));
        assertSame(good, bot.getInventory(InventoryType.EQUIP).getItem((short) 2));
        assertNull(BotScrolling.afterRoll(bot, good, BotScrolling.Outcome.CURSE, false));
    }

    @Test
    void theLedgerReportNamesWhereTheMesosWent() {
        ScrollLedger.bought(ScrollLedger.Source.NPC, 70_000);
        ScrollLedger.rolled(BotScrolling.Outcome.SUCCESS, false, null);
        ScrollLedger.rolled(BotScrolling.Outcome.CURSE, false, 500_000);
        List<String> lines = ScrollLedger.report();
        assertTrue(lines.get(0).contains("1 from NPCs for 70,000 (sink)"), lines.get(0));
        assertTrue(lines.get(1).contains("2 scrolls used: 1 passed, 0 failed, 1 boomed"), lines.get(1));
        assertTrue(lines.get(1).contains("500,000 mesos of gear destroyed"), lines.get(1));
    }
}
