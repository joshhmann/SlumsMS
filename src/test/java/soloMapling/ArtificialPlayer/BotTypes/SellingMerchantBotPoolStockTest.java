package soloMapling.ArtificialPlayer.BotTypes;

import client.BotClient;
import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.BotShops;
import soloMapling.Economy.EconomyConfig;
import soloMapling.Economy.EconomyMeters;
import soloMapling.Economy.EconomyStore;
import soloMapling.Economy.PriceBook;
import soloMapling.FreeMarket.FMItem;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 181 (market#F-07): a selling merchant bot minted a brand new item every time it picked one up
 * ({@code generateItem} in {@code selectNextItem}), so every one of its trades put an item into the world. With
 * {@code -Dslums.bots.merchantpoolstock} it stocks only what the bot economy already holds - the resale pool bot shops
 * restock from ({@link BotShops}) - and refuses to stock while that pool is empty; every stock is then counted in the
 * mint ledger ({@code mint:item:merchant-bot}).
 *
 * <p>The stocking step is driven through the same private method the FSM calls, so on the code before the fix an empty
 * pool still ends with a freshly generated item up for sale and these tests fail.
 */
class SellingMerchantBotPoolStockTest {

    /** A scroll the pool prices; the item the bot sells in the cases that need one. */
    private static final int SCROLL = 2044702;
    private static final String SWITCH = "slums.bots.merchantpoolstock";
    private static final long DAY = 86_400_000L;

    private SellingMerchantBot bot;

    @BeforeEach
    void setUp() {
        System.clearProperty(SWITCH);
        EconomyConfig.useForTest(Map.of());
        PriceBook.useForTest(new EconomyStore.Memory(), Runnable::run, System::currentTimeMillis,
                item -> item.getItemId() == SCROLL ? 1_000_000L : 0L);
        EconomyMeters.useForTest(new EconomyStore.Memory(), Runnable::run, () -> LocalDate.ofEpochDay(100 * DAY / DAY));
        BotShops.clearResalePoolForTest();

        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(1_000_000_992);
        chr.setName("Sellr");
        chr.setMapId(910000000);
        bot = new SellingMerchantBot(chr);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(SWITCH);
        BotShops.clearResalePoolForTest();
        EconomyMeters.useForTest(null, null, null);
        PriceBook.useForTest(null, null, null, null);
        EconomyConfig.useForTest(null);
    }

    /** The stock list the FSM would have drawn, handed to the bot directly (two entries: the walk has room). */
    private static void stockList(SellingMerchantBot bot, int first, int second) throws Exception {
        List<FMItem> list = new ArrayList<>();
        list.add(new FMItem(first, 1_000, 1));
        list.add(new FMItem(second, 1_000, 1));
        Field f = SellingMerchantBot.class.getDeclaredField("itemsToSell");
        f.setAccessible(true);
        f.set(bot, list);
        Field index = SellingMerchantBot.class.getDeclaredField("itemIndex");
        index.setAccessible(true);
        index.setInt(bot, 0);
    }

    /** One SELECT_ITEM tick: the private stocking step, with its throw passed through. */
    private static void selectNextItem(SellingMerchantBot bot) throws Exception {
        Method m = SellingMerchantBot.class.getDeclaredMethod("selectNextItem");
        m.setAccessible(true);
        try {
            m.invoke(bot);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    @Test
    void againstAnEmptyPoolTheStockingPathStocksNothing() throws Exception {
        System.setProperty(SWITCH, "true");
        stockList(bot, SCROLL, SCROLL);

        selectNextItem(bot);
        assertNull(bot.getTradeInventory().getMainItemForSale(), "the pool is empty: nothing stocked");
        selectNextItem(bot);
        assertNull(bot.getTradeInventory().getMainItemForSale(), "the pool is still empty: the second call stocks nothing");
        assertEquals(0, BotShops.resalePoolSize(), "nothing was taken and nothing was made");
        assertEquals(0, EconomyMeters.get("mint:item:merchant-bot"), "nothing was stocked, so nothing is minted");
    }

    @Test
    void theBotStocksThePooledItemAndCountsIt() throws Exception {
        System.setProperty(SWITCH, "true");
        // the pool keeps its own copy, so the quantity it was pooled with is what tells the stocked item apart from a
        // generated one (generateItem always makes a quantity-1 item)
        Item pooled = new Item(SCROLL, (short) 0, (short) 3);
        assertTrue(BotShops.offerResale(pooled, 400_000L), "the pool takes the item");
        stockList(bot, SCROLL, SCROLL);

        selectNextItem(bot);

        Item forSale = bot.getTradeInventory().getMainItemForSale();
        assertEquals(SCROLL, forSale.getItemId());
        assertEquals(3, forSale.getQuantity(), "the item up for sale is the pool's, quantity and all, not a fresh one");
        assertTrue(bot.getTradeWants().getMesoWanted() > 0, "it is priced by the same rule as before");
        assertEquals(0, BotShops.resalePoolSize(), "the item left the pool");
        assertEquals(1, EconomyMeters.get("mint:item:merchant-bot"), "the stock is counted in the mint ledger");
    }

    @Test
    void withTheSwitchOffTheBotStillGeneratesItsItem() throws Exception {
        stockList(bot, SCROLL, SCROLL);

        selectNextItem(bot);

        Item forSale = bot.getTradeInventory().getMainItemForSale();
        assertNotNull(forSale, "the switch is off: 1.6.4's behaviour, a generated item is stocked");
        assertEquals(SCROLL, forSale.getItemId());
        assertTrue(bot.getTradeWants().getMesoWanted() > 0);
        assertEquals(1, EconomyMeters.get("mint:item:merchant-bot"),
                "the switch is off: the generated item is still counted, so the ledger measures today's minting");
    }
}
