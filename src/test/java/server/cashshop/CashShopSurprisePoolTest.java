package server.cashshop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.CashShop.CashItem;
import server.CashShop.CashItemFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0nk/Cosmic#244: a Cash Shop Surprise used to be able to hand out a package item (910xxxx), which is not a real item
 * and crashes the v83 client. The random pool is built from on-sale commodities; packages are never eligible.
 */
class CashShopSurprisePoolTest {

    private static final int NORMAL_SN = 1;
    private static final int NORMAL_ITEM = 4000000;
    private static final int PACKAGE_SN = 2;
    private static final int PACKAGE_ITEM = 9102237; // itemId / 10000 == 910

    private Map<Integer, CashItem> itemsWere;
    private List<Integer> poolWere;

    @BeforeEach
    void savePool() throws Exception {
        itemsWere = new HashMap<>(items());
        poolWere = new ArrayList<>(pool());
    }

    @AfterEach
    void restorePool() throws Exception {
        setPool(itemsWere, poolWere);
    }

    @Test
    void aPackageSerialInThePoolIsNeverReturned() throws Exception {
        Map<Integer, CashItem> items = new HashMap<>();
        items.put(NORMAL_SN, cashItem(NORMAL_SN, NORMAL_ITEM));
        items.put(PACKAGE_SN, cashItem(PACKAGE_SN, PACKAGE_ITEM));
        setPool(items, List.of(NORMAL_SN, PACKAGE_SN));

        for (int i = 0; i < 2_000; i++) {
            CashItem reward = CashItemFactory.getRandomCashItem();
            assertNotNull(reward, "the surprise must still pick from the normal item");
            assertEquals(NORMAL_ITEM, reward.getItemId(), "a package item must never be the surprise reward");
        }
    }

    @Test
    void aPoolOfOnlyPackageSerialsYieldsNoReward() throws Exception {
        Map<Integer, CashItem> items = new HashMap<>();
        items.put(PACKAGE_SN, cashItem(PACKAGE_SN, PACKAGE_ITEM));
        setPool(items, List.of(PACKAGE_SN));

        assertNull(CashItemFactory.getRandomCashItem(), "no real item left in the pool means no surprise reward");
    }

    private static CashItem cashItem(int sn, int itemId) throws Exception {
        Constructor<CashItem> ctor = CashItem.class.getDeclaredConstructor(
                int.class, int.class, int.class, long.class, short.class, boolean.class);
        ctor.setAccessible(true);
        return ctor.newInstance(sn, itemId, 1, 0L, (short) 1, true);
    }

    private static Field itemsField() throws Exception {
        Field f = CashItemFactory.class.getDeclaredField("items");
        f.setAccessible(true);
        return f;
    }

    private static Field poolField() throws Exception {
        Field f = CashItemFactory.class.getDeclaredField("randomitemsns");
        f.setAccessible(true);
        return f;
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, CashItem> items() throws Exception {
        return (Map<Integer, CashItem>) itemsField().get(null);
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> pool() throws Exception {
        return (List<Integer>) poolField().get(null);
    }

    private static void setPool(Map<Integer, CashItem> items, List<Integer> pool) throws Exception {
        itemsField().set(null, items);
        poolField().set(null, pool);
    }
}
