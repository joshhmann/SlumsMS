package soloMapling.FreeMarket.ShopOfferSystem;

import client.BotClient;
import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.PlayerShop;
import server.maps.PlayerShopItem;
import soloMapling.ArtificialPlayer.BotScrollSystem.BotConsignment;
import soloMapling.Economy.EconomyConfig;
import soloMapling.Economy.EconomyStore;
import soloMapling.Economy.PriceBand;
import soloMapling.Economy.PriceBook;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ticket 181 (market#F-03): an offer a bot accepted rewrote the listing's price straight from the player's number, so a
 * bot stall could be emptied at 70% (or anything above {@code ShopMath.isSaleablePrice}) of its own ask, with no floor
 * under it. With {@code -Dslums.offer.pricefloor} the accepted price is floored at the bottom of the selling band
 * ({@link PriceBand#of}), and never under what the bot put into a consigned lot ({@link BotConsignment}).
 *
 * <p>Every assertion drives the real writing method ({@code ShopOfferResponse.acceptOffer}) through reflection, so on
 * the code before the fix the floored cases see the player's own number written to the shelf and fail.
 */
class ShopOfferPriceFloorTest {

    /** A scroll the pool prices (scrolls.yaml: Claw Att 10%); its fair value comes from the test's static value. */
    private static final int SCROLL = 2044702;
    private static final long STATIC = 1_000_000L;
    private static final String SWITCH = "slums.offer.pricefloor";

    @BeforeEach
    void setUp() {
        EconomyConfig.useForTest(Map.of());
        PriceBook.useForTest(new EconomyStore.Memory(), Runnable::run, System::currentTimeMillis,
                item -> item.getItemId() == SCROLL ? STATIC : 0L);
        BotConsignment.clearForTest();
        System.clearProperty(SWITCH);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(SWITCH);
        BotConsignment.clearForTest();
        PriceBook.useForTest(null, null, null, null);
        EconomyConfig.useForTest(null);
    }

    private static PlayerShopItem listing(long askingPrice) {
        PlayerShopItem shopItem = new PlayerShopItem(new Item(SCROLL, (short) 0, (short) 1), (short) 1,
                (int) askingPrice);
        shopItem.setDoesExist(true);
        return shopItem;
    }

    /** The real accept path: present owner, offer accepted at {@code offerPrice}, the shelf gets the new price. */
    private static void acceptOffer(PlayerShopItem shopItem, long offerPrice) throws Exception {
        Character owner = Character.getDefault(new BotClient(0, 0));
        owner.setID(1_100_000_001);
        owner.setName("Offerbot");
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(1_100_000_002);
        player.setName("Buyer");
        PlayerShop shop = mock(PlayerShop.class);
        when(shop.getOwner()).thenReturn(owner);

        OfferParser.ParsedOffer offer = new OfferParser.ParsedOffer("item", 0, offerPrice, shopItem);
        Method m = ShopOfferResponse.class.getDeclaredMethod("acceptOffer", PlayerShop.class, Character.class,
                Character.class, OfferParser.ParsedOffer.class, long.class);
        m.setAccessible(true);
        m.invoke(null, shop, owner, player, offer, offerPrice);
    }

    @Test
    void anOfferAtSeventyPercentOfTheAskIsFlooredAtTheSellingBandsBottom() throws Exception {
        System.setProperty(SWITCH, "true");
        PlayerShopItem shopItem = listing(1_000_000L);
        long low = PriceBand.of(PriceBand.Side.BOT_SELLS, PriceBook.stackValue(shopItem.getItem()), false).low();

        acceptOffer(shopItem, 700_000L);

        assertTrue(shopItem.getPrice() >= low, "an accepted 70% offer left " + shopItem.getPrice()
                + " on the shelf, under the selling band's bottom " + low);
        assertEquals(low, shopItem.getPrice(), "the floor is the band's bottom, not more");
    }

    @Test
    void aConsignedLotIsNeverRewrittenBelowTheBotsOwnPrice() throws Exception {
        System.setProperty(SWITCH, "true");
        PlayerShopItem shopItem = listing(1_200_000L);
        BotConsignment.putForTest(77, null, shopItem, 1_200_000);   // what the bot valued the lot at when it listed it

        acceptOffer(shopItem, 840_000L);   // 70% of the bot's own price

        assertTrue(shopItem.getPrice() >= 1_200_000,
                "a consigned lot fell to " + shopItem.getPrice() + ", under the bot's own 1,200,000");
    }

    @Test
    void withTheSwitchOffAnAcceptedOfferStillWritesThePlayersNumber() throws Exception {
        PlayerShopItem shopItem = listing(1_000_000L);
        long low = PriceBand.of(PriceBand.Side.BOT_SELLS, PriceBook.stackValue(shopItem.getItem()), false).low();

        acceptOffer(shopItem, 700_000L);

        assertTrue(700_000L < low, "the case must be one the floor would have moved");
        assertEquals(700_000, shopItem.getPrice(), "the switch is off: 1.6.4's behaviour, the player's number, exactly");
    }

    @Test
    void withTheSwitchOffAConsignedLotStillFallsToThePlayersNumber() throws Exception {
        PlayerShopItem shopItem = listing(1_200_000L);
        BotConsignment.putForTest(78, null, shopItem, 1_200_000);

        acceptOffer(shopItem, 840_000L);

        assertEquals(840_000, shopItem.getPrice(), "the switch is off: a consigned lot is rewritten as it always was");
    }
}
