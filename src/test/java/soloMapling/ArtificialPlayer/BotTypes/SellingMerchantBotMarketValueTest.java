package soloMapling.ArtificialPlayer.BotTypes;

import client.BotClient;
import client.Character;
import client.inventory.Item;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.FreeMarket.FMItem;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * b271, the selling half: the item pool does not value every item. A selling merchant bot that drew such an item threw
 * {@code NullPointerException} out of {@code selectNextItem} (the bare {@code int rawValue = getItemMarketValue(item)}),
 * which the bot tick swallowed every cycle - the merchant simply stopped stocking. A missing value now means "not
 * stocked, not sold, never at a made-up price": the bot moves on to another item in its list and names the item id in
 * one WARN per JVM.
 *
 * <p>Every assertion drives the real stocking step through the same private method the FSM calls, so this test throws
 * {@code NullPointerException} on the code before the fix.
 */
class SellingMerchantBotMarketValueTest {

    // The warning set and the pool are process-wide: every test uses its own item id.
    static final int NO_VALUE_A = 2_049_990;
    static final int NO_VALUE_B = 2_049_991;
    static final int NO_VALUE_C = 2_049_992;
    static final int NO_VALUE_D = 2_049_993;
    // And a scroll the pool does price (scrolls.yaml: Claw Att 10%, 500,000).
    static final int VALUED = 2044702;

    private static final class Capture extends AbstractAppender {
        final List<String> lines = new CopyOnWriteArrayList<>();

        Capture() {
            super("sellnull-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            lines.add(event.getMessage().getFormattedMessage());
        }
    }

    final Capture capture = new Capture();
    Logger logger;
    Level level;
    SellingMerchantBot bot;

    @BeforeEach
    void setUp() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(1_000_000_991);
        chr.setName("Sellr");
        chr.setMapId(910000000);
        bot = new SellingMerchantBot(chr);

        logger = (Logger) LogManager.getLogger(SellingMerchantBot.class);
        level = logger.getLevel();
        Configurator.setLevel(logger.getName(), Level.WARN); // the test config turns the root logger off
        capture.start();
        logger.addAppender(capture);
    }

    @AfterEach
    void tearDown() {
        logger.removeAppender(capture);
        capture.stop();
        Configurator.setLevel(logger.getName(), level);
    }

    /** The stock list the FSM would have drawn, handed to the bot directly. */
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

    private long warningsFor(int itemId) {
        String needle = "Item " + itemId + " ";
        return capture.lines.stream().filter(l -> l.contains(needle)).count();
    }

    @Test
    void anItemThePoolCannotValueIsNotStockedAndTheNextOneIs() throws Exception {
        stockList(bot, VALUED, NO_VALUE_A);
        selectNextItem(bot);   // the unvalued item is the one this tick lands on

        Item forSale = bot.getTradeInventory().getMainItemForSale();
        assertEquals(VALUED, forSale == null ? -1 : forSale.getItemId(),
                "the unvalued item stays out of the trade window; the valued one is stocked");
        assertTrue(bot.getTradeWants().getMesoWanted() > 0, "a valued item still gets a real asking price");
        assertTrue(bot.takesTradesNow(), "and the bot is a seller");
        assertEquals(1, warningsFor(NO_VALUE_A), "the missing value is named once, with its item id");
    }

    @Test
    void aStockListWithNothingValuedLeavesNothingForSale() throws Exception {
        stockList(bot, NO_VALUE_B, NO_VALUE_C);
        selectNextItem(bot);

        assertNull(bot.getTradeInventory().getMainItemForSale(), "nothing unvalued is ever put up for sale");
        assertFalse(bot.takesTradesNow(), "the bot did not become a seller");
        assertEquals(1, warningsFor(NO_VALUE_B));
        assertEquals(1, warningsFor(NO_VALUE_C));
    }

    @Test
    void theSameItemIdIsWarnedAboutOnlyOnce() throws Exception {
        stockList(bot, VALUED, NO_VALUE_D);
        selectNextItem(bot);
        selectNextItem(bot);   // the same unvalued id again in the same JVM
        selectNextItem(bot);
        assertEquals(1, warningsFor(NO_VALUE_D), "one line per item id, not one per tick");
    }
}
