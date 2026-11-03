package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.BotClient;
import client.Character;
import client.Client;
import client.Job;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;
import server.Shop;
import server.ShopItem;
import soloMapling.ArtificialPlayer.BotHoldings;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 1.6.4 b289 (audit shop economy F1, bot path): a bot buys its stars and bullets at the same NPC counter a player
 * does, so it has to be charged what that counter charges — {@link ItemInformationProvider#getPrice(int, int)} over
 * the whole stack (the whole price plus the unit price of each star), the expression a sale pays back. Before the
 * fix the bot paid the {@code shopitems} listing price once per stack, 500 for a 9,999-star stack that a player
 * pays 10,249 for — so a bot could buy a stack for 500 and sell it back for 10,249.
 */
class BotShopPriceParityTest {
    private static final int STARS = 2070000;        // shopitems lists it at 500 a stack
    private static final int LISTED = 500;
    private static final short SLOT_MAX = 800;       // the item data's stack size (stub for a stock v83 slotMax)
    private static final int WHOLE_PRICE = 250;      // WZ info/price
    private static final double UNIT_PRICE = 1.0;    // WZ info/unitPrice
    private static final int STACK_PRICE = 1050;     // 250 + ceil(800 * 1.0)

    private MockedStatic<ItemInformationProvider> singleton;
    private ShopCatalog.Site site;

    @BeforeAll
    static void initializeProviderWithoutGameData() throws Exception {
        // The singleton reads WZ and the DB in its static initializer; supply empty boundaries instead.
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @BeforeEach
    void data() throws Exception {
        ItemInformationProvider ii = mock(ItemInformationProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        // What little of the item data the bot path reads besides the price; getPrice stays the real derivation.
        doReturn(SLOT_MAX).when(ii).getSlotMax(STARS);
        doReturn(false).when(ii).isPickupRestricted(STARS);
        doReturn(WHOLE_PRICE).when(ii).getWholePrice(STARS);
        doReturn(UNIT_PRICE).when(ii).getUnitPrice(STARS);
        doReturn(SLOT_MAX).when(ii).getSlotMax(any(Character.class), eq(STARS));
        singleton = mockStatic(ItemInformationProvider.class);
        singleton.when(ItemInformationProvider::getInstance).thenReturn(ii);

        Method clear = BotHoldings.class.getDeclaredMethod("clearForTest");
        clear.setAccessible(true);
        clear.invoke(null);
        BotShopping.resetForTest();
        BotShopping.useWeaponForTest(bot -> WeaponType.CLAW);
        // No price or slotMax here: the bot asks the item data, through the seam's own default.
        SupplyData.useForTest(new SupplyData.Source() {
            @Override
            public SupplyData.Recovery recovery(int itemId) {
                return null;   // no potions sold at this counter
            }

            @Override
            public int bulletCount(int skillId, int level) {
                return 1;
            }

            @Override
            public int bulletConsume(int skillId, int level) {
                return 0;
            }

            @Override
            public int mpCon(int skillId, int level) {
                return 0;
            }

            @Override
            public int hpCon(int skillId, int level) {
                return 0;
            }

            @Override
            public int x(int skillId, int level) {
                return 0;
            }

            @Override
            public int duration(int skillId, int level) {
                return 0;
            }

            @Override
            public int projectileWatk(int itemId) {
                return itemId == STARS ? 15 : 0;
            }
        });
        site = new ShopCatalog.Site(100000102, 1012002, 1, new Point(300, 150),
                List.of(new ShopCatalog.Listing(6, STARS, LISTED, 0)));
    }

    @AfterEach
    void restore() {
        if (singleton != null) {
            singleton.close();
        }
        SupplyData.useForTest(null);
        BotShopping.useWeaponForTest(null);
    }

    @Test
    void aBoughtStarStackCostsTheBotWhatTheCounterChargesAPlayer() throws Exception {
        Character bot = bot(1_400);   // budget 1,050: one stack, then nothing left for a second
        int playerCharge = playerCharge();
        assertEquals(STACK_PRICE, playerCharge, "the counter charges the item data's stack price, not the listing");
        int before = bot.getMeso();

        BotShopping.Receipt r = BotShopping.shop(bot, site, needs(STARS));

        assertEquals(playerCharge, r.spent, "the bot pays what the counter charges a player for the same stack");
        assertEquals(playerCharge, before - bot.getMeso(), "and the mesos actually leave");
        assertEquals(SLOT_MAX, count(bot, STARS), "one whole stack");
    }

    @Test
    void aBotWhoseBudgetIsBelowTheStackPriceBuysNoStars() throws Exception {
        Character bot = bot(1_200);   // budget 900: above the 500 listing, below the 1,050 stack price
        assertEquals(900, BotShopping.budget(bot));
        int before = bot.getMeso();

        BotShopping.Receipt r = BotShopping.shop(bot, site, needs(STARS));

        assertEquals(0, count(bot, STARS), "budget 900 is below the 1,050 the counter charges for a stack");
        assertEquals(before, bot.getMeso(), "and it is not charged the old 500 either");
        assertTrue(r.isEmpty());
    }

    /* What Shop.buy deducts for the same stack: the player's side of the counter, over this test's item data. */
    private int playerCharge() {
        Client client = mock(Client.class);
        Character player = mock(Character.class);
        AtomicInteger mesos = new AtomicInteger(1_000_000);
        when(client.getPlayer()).thenReturn(player);
        when(player.getMeso()).thenAnswer(x -> mesos.get());
        doAnswer(x -> {
            mesos.addAndGet(x.getArgument(0));
            return null;
        }).when(player).gainMeso(anyInt(), anyBoolean());
        Shop shop = Shop.custom(1001100, 1040001, List.of(new ShopItem((short) 1, STARS, LISTED, 0)));
        try (var manip = mockStatic(InventoryManipulator.class)) {
            manip.when(() -> InventoryManipulator.checkSpace(any(Client.class), anyInt(), anyInt(), anyString()))
                    .thenReturn(true);
            int before = mesos.get();
            shop.buy(client, (short) 0, STARS, (short) 1);
            return before - mesos.get();
        }
    }

    private static SupplyNeeds needs(int ammoItemId) {
        return new SupplyNeeds(BotSupplies.Shortage.NONE, ammoItemId, 0, List.of(), 0, 0, true);
    }

    private static Character bot(int mesos) throws Exception {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(1_000_000_000 + mesos);
        chr.setJob(Job.ASSASSIN);
        chr.setLevel(1);
        for (String f : new String[]{"maxhp", "localmaxhp", "clientmaxhp"}) {
            setInt(chr, f, 900);
        }
        for (String f : new String[]{"maxmp", "localmaxmp", "clientmaxmp"}) {
            setInt(chr, f, 300);
        }
        chr.updateHpMp(900, 300);
        chr.gainMeso(mesos, false, false, false);
        chr.getInventory(InventoryType.USE).setSlotLimit(48);
        return chr;
    }

    private static int count(Character bot, int itemId) {
        int n = 0;
        for (Item item : bot.getInventory(InventoryType.USE).listById(itemId)) {
            n += item.getQuantity();
        }
        return n;
    }

    private static void setInt(Object target, String name, int value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(target, value);
                return;
            } catch (NoSuchFieldException e) {
                // look further up
            }
        }
        throw new NoSuchFieldException(name);
    }
}
