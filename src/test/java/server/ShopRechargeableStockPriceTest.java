package server;

import constants.inventory.ItemConstants;
import org.junit.jupiter.api.Test;
import soloMapling.WzTestData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * 1.6.4 b289 (audit shop economy F1, data side): a counter now charges for a rechargeable stack what the item data
 * says it is worth — {@code Shop.buy} (server/Shop.java:105-134), {@code Shop.recharge} (:265-283) and the bot's
 * shopping all call {@link ItemInformationProvider#getPrice(int, int)} over the whole stack. That price is -1 when
 * the WZ data carries no price, and every one of those paths then refuses the sale, so a stock rechargeable with no
 * price is a counter that cannot sell it. This test walks the rechargeable ids this repo puts in {@code shopitems}
 * and holds the data to that: each has a stack size and a stack price the counter can charge.
 */
class ShopRechargeableStockPriceTest {
    /* Every counter's stock as SQL in this repo lists it; the base shopitems table itself is not in the repo. */
    private static final List<String> SHOP_ITEMS_SQL = List.of(
            "database/migrations/021-cosmetics-merchant.sql",
            "database/migrations/024-restore-stock-shop-items.sql",
            "database/migrations/064-casino-chip-shop.sql");
    /* Each stock row is written "SELECT shopid, itemid, price, ..." (024) or the same shape after INSERT ... VALUES. */
    private static final Pattern STOCK_ROW = Pattern.compile("SELECT\\s+(\\d+)\\s*,\\s*(\\d+)\\s*,");

    @Test
    void everyRechargeableOnSaleHasAStackPriceTheCounterCanCharge() throws Exception {
        Set<Integer> ids = rechargeableStock();
        assertTrue(!ids.isEmpty(), "the repo's shopitems rows sell at least one rechargeable");
        WzTestData.assumePresent("Item.wz");

        ItemInformationProvider ii;
        // The provider's static initializer reads card ids from the database; the item data itself comes from wz/.
        try (var db = mockStatic(tools.DatabaseConnection.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(Connection.class, RETURNS_DEEP_STUBS));
            ii = ItemInformationProvider.getInstance();
        }
        List<String> unpriced = new ArrayList<>();
        for (int itemId : ids) {
            int slotMax = ii.getSlotMax(itemId);
            int stackPrice = ii.getPrice(itemId, slotMax);
            if (slotMax <= 0 || stackPrice < 0) {
                unpriced.add(itemId + " (slotMax " + slotMax + ", stack price " + stackPrice + ")");
            }
        }
        assertTrue(unpriced.isEmpty(), "a counter cannot charge for these rechargeables: " + unpriced);
    }

    /* The rechargeable item ids in the shop rows this repo ships: a stack they are sold in is what a counter charges. */
    private static Set<Integer> rechargeableStock() throws Exception {
        Set<Integer> ids = new LinkedHashSet<>();
        for (String sql : SHOP_ITEMS_SQL) {
            Matcher row = STOCK_ROW.matcher(Files.readString(Path.of(sql)));
            while (row.find()) {
                int itemId = Integer.parseInt(row.group(2));
                if (ItemConstants.isRechargeable(itemId)) {
                    ids.add(itemId);
                }
            }
        }
        return ids;
    }
}
