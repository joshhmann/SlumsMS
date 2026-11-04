package server;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * 1.6.4 b289 (audit shop economy F1): a shop grants a rechargeable as a whole {@code slotMax} stack, so what it
 * charges for that stack has to be the same expression selling the stack pays out — WZ {@code info/price} plus
 * {@code info/unitPrice} per unit ({@link ItemInformationProvider#getPrice}). Before the fix {@code Shop.buy}
 * charged the counter price once (500 for 2070000) while {@code Shop.sell} paid 250 + 9,999 = 10,249, minting
 * 9,749 mesos per buy/sell pair (9,949 at claw mastery 20, where the granted stack is 10,199).
 */
class ShopRechargePriceParityTest {
    private static final int STARS = 2070000;          // WZ info: price 250, unitPrice 1.0, slotMax 9999
    private static final int WHOLE_PRICE = 250;
    private static final double UNIT_PRICE = 1.0;
    private static final short SLOT_MAX = 9999;
    private static final short SLOT_MAX_MASTERY_20 = 10199;  // 9999 + 20 * 10, getExtraSlotMaxFromPlayer
    private static final int COUNTER_PRICE = 500;      // shopitems row for 2070000 in shop 1001100
    private static final int STARTING_MESOS = 1_000_000;

    @BeforeAll
    static void initializeProviderWithoutGameData() throws Exception {
        // These singletons read WZ and the DB in their static initializer; supply empty boundaries instead.
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @Test
    void aStackBoughtAtMasteryZeroSellsBackForNoMoreThanItCost() {
        try (Fixture f = new Fixture(SLOT_MAX)) {
            int before = f.mesos.get();
            f.shop.buy(f.client, (short) 0, STARS, (short) 1);
            int paid = before - f.mesos.get();

            f.useStillHolds(SLOT_MAX);
            f.shop.sell(f.client, InventoryType.USE, (short) 0, (short) 1);
            int received = f.mesos.get() - before + paid;
            int net = f.mesos.get() - before;

            assertTrue(net <= 0, "mastery 0: bought " + SLOT_MAX + " stars for " + paid
                    + " mesos and sold the same stack straight back for " + received
                    + ", a gain of " + net + " mesos per pair");
        }
    }

    @Test
    void aStackBoughtAtMasteryTwentySellsBackForNoMoreThanItCost() {
        try (Fixture f = new Fixture(SLOT_MAX_MASTERY_20)) {
            int before = f.mesos.get();
            f.shop.buy(f.client, (short) 0, STARS, (short) 1);
            int paid = before - f.mesos.get();

            f.useStillHolds(SLOT_MAX_MASTERY_20);
            f.shop.sell(f.client, InventoryType.USE, (short) 0, (short) 1);
            int received = f.mesos.get() - before + paid;
            int net = f.mesos.get() - before;

            assertTrue(net <= 0, "claw mastery 20: bought " + SLOT_MAX_MASTERY_20 + " stars for " + paid
                    + " mesos and sold the same stack straight back for " + received
                    + ", a gain of " + net + " mesos per pair");
        }
    }

    /** Empty stack refilled at the counter, then sold: the refill must cost what the full stack sells for. */
    @Test
    void rechargingAnEmptyStackCostsWhatTheFullStackSellsFor() {
        try (Fixture f = new Fixture(SLOT_MAX)) {
            Item empty = new Item(STARS, (short) 0, (short) 0);
            when(f.use.getItem((short) 0)).thenReturn(empty);
            int before = f.mesos.get();

            f.shop.recharge(f.client, (short) 0);
            int paid = before - f.mesos.get();
            assertEquals(SLOT_MAX, empty.getQuantity(), "the counter fills the stack to slotMax");

            f.shop.sell(f.client, InventoryType.USE, (short) 0, (short) 1);
            int received = f.mesos.get() - before + paid;
            assertEquals(received, paid, "a refill from empty cost " + paid
                    + " mesos but the full stack it fills sells for " + received);
        }
    }

    /** One shop-1001100 counter with 2070000 in slot 0, and a player holding mesos but no items yet. */
    private static final class Fixture implements AutoCloseable {
        final Client client = mock(Client.class);
        final Character player = mock(Character.class);
        final AtomicInteger mesos = new AtomicInteger(STARTING_MESOS);
        final Inventory use = mock(Inventory.class);
        final Shop shop = Shop.custom(1001100, 1040001, List.of(new ShopItem((short) 1, STARS, COUNTER_PRICE, 0)));

        Fixture(short grantedStack) {
            ItemInformationProvider ii = mock(ItemInformationProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
            ii.wholePriceCache = new HashMap<>(Map.of(STARS, WHOLE_PRICE));
            ii.unitPriceCache = new HashMap<>(Map.of(STARS, UNIT_PRICE));
            // The player's stack size (WZ slotMax plus mastery) needs live skills; use what the shop grants.
            doReturn(grantedStack).when(ii).getSlotMax(any(Character.class), eq(STARS));

            when(client.getPlayer()).thenReturn(player);
            when(player.getMeso()).thenAnswer(x -> mesos.get());
            doAnswer(x -> {
                mesos.addAndGet(x.getArgument(0));
                return null;
            }).when(player).gainMeso(anyInt(), anyBoolean());
            // recharge() uses the four-argument overload
            doAnswer(x -> {
                mesos.addAndGet(x.getArgument(0));
                return null;
            }).when(player).gainMeso(anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
            when(player.getInventory(InventoryType.USE)).thenReturn(use);

            manip = mockStatic(InventoryManipulator.class);
            singleton = mockStatic(ItemInformationProvider.class);
            singleton.when(ItemInformationProvider::getInstance).thenReturn(ii);
            manip.when(() -> InventoryManipulator.checkSpace(any(Client.class), anyInt(), anyInt(), anyString()))
                    .thenReturn(true);
        }

        private final org.mockito.MockedStatic<InventoryManipulator> manip;
        private final org.mockito.MockedStatic<ItemInformationProvider> singleton;

        @Override
        public void close() {
            singleton.close();
            manip.close();
        }

        /** What a completed purchase left the player holding: the stack it charged for. */
        void useStillHolds(short quantity) {
            when(use.getItem((short) 0)).thenReturn(new Item(STARS, (short) 0, quantity));
        }
    }
}
