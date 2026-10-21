package server.cashshop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.ItemInformationProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CashShopCatalogFixesTest {
    @org.junit.jupiter.api.BeforeAll static void initializeItemProviderWithoutExternalData() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            var connection = mock(java.sql.Connection.class, RETURNS_DEEP_STUBS);
            db.when(tools.DatabaseConnection::getConnection).thenReturn(connection);
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    /** itemId -> columns of the shipped catalogue, comments skipped. */
    private static Map<Integer, String[]> shippedRows() throws Exception {
        final Map<Integer, String[]> rows = new HashMap<>();
        for (String line : Files.readAllLines(Path.of("cashshop", "catalog.tsv"))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            final String[] f = line.split("\t");
            rows.put(Integer.parseInt(f[0]), f);
        }
        return rows;
    }

    @Test void loaderKeepsServiceRowsButStillDropsOtherPackages(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("catalog.tsv"), String.join("\n",
                "9110000\t6400\t1\t5\t2\t0\t2\tStorage Slots +8",
                "9111000\t6400\t1\t5\t2\t0\t2\tEquip Slots +8",
                "9114000\t6800\t1\t5\t2\t0\t2\tEtc Slots +8",
                "9112000\t6800\t2\t5\t2\t0\t2\tUse Slots x2",
                "9100000\t1000\t1\t7\t0\t0\t2\tSome Package", ""));
        final String old = System.getProperty("cashshop-path");
        System.setProperty("cashshop-path", dir.toString());
        try (var ii = mockStatic(ItemInformationProvider.class)) {
            ii.when(ItemInformationProvider::getInstance).thenReturn(mock(ItemInformationProvider.class));
            try {
                CashShopCatalog.load();
                assertNotNull(CashShopCatalog.byItemId(9111000));
                assertNotNull(CashShopCatalog.byItemId(9114000));
                assertNull(CashShopCatalog.byItemId(9110000), "storage is not a service");
                assertNull(CashShopCatalog.byItemId(9112000), "a service row must have count 1");
                assertNull(CashShopCatalog.byItemId(9100000));
            } finally {
                Files.writeString(dir.resolve("catalog.tsv"), "");
                CashShopCatalog.load();                // leave the shared catalogue empty again
            }
        } finally {
            if (old == null) System.clearProperty("cashshop-path"); else System.setProperty("cashshop-path", old);
        }
    }

    @Test void shippedCatalogSellsSlotExpansionsAtStockPrices() throws Exception {
        final Map<Integer, String[]> rows = shippedRows();
        assertEquals("6400", rows.get(9111000)[1]);
        assertEquals("6800", rows.get(9112000)[1]);
        assertEquals("6800", rows.get(9113000)[1]);
        assertEquals("6800", rows.get(9114000)[1]);
        assertFalse(rows.containsKey(9110000));
        assertTrue(rows.containsKey(CashShopServices.EXTRA_CHARACTER_SLOT));
    }

    @Test void shippedCatalogDropsInertItems() throws Exception {
        final Map<Integer, String[]> rows = shippedRows();
        for (int inert : List.of(5400000, 5401000, 5222000, 5460000)) {
            assertFalse(rows.containsKey(inert), "inert row still sold: " + inert);
        }
    }

    @Test void shippedRateCouponsAllExpire() throws Exception {
        int coupons = 0;
        for (String[] f : shippedRows().values()) {
            final int id = Integer.parseInt(f[0]);
            if (!CashShopServices.isRateCoupon(id)) continue;
            coupons++;
            final int period = Integer.parseInt(f[5]);
            assertTrue(period > 0, "permanent rate coupon " + id);
            if (id == 5211048 || id == 5211060 || id == 5360042) {
                assertEquals(1, period, "hour coupon " + id);
            }
        }
        assertTrue(coupons > 30);
    }
}
