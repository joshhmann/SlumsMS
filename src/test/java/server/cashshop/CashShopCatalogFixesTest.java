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
        }
        assertEquals(2, coupons);
    }

    /** Owner 1.5.1: two all-day EXP cards, 2x for 7 days and 3x for 1 day. No other EXP card, no drop card. */
    @Test void shippedRateCouponsAreTheTwoAllDayExpCards() throws Exception {
        final Map<Integer, String> sold = new java.util.TreeMap<>();
        for (String[] f : shippedRows().values()) {
            if (CashShopServices.isRateCoupon(Integer.parseInt(f[0]))) {
                sold.put(Integer.parseInt(f[0]), f[1] + "/" + f[5] + "/" + f[7]);
            }
        }
        assertEquals(Map.of(5211048, "6000/7/2x EXP Card 7 days", 5211060, "3000/1/3x EXP Card 1 day"), sold);
    }

    @Test void shippedCatalogSellsPartnerRingsOnlyThroughThePartnerFlowAndDropsInertAreaEffects() throws Exception {
        final Map<Integer, String[]> rows = shippedRows();
        // 1.5.0: the v83 couple/friendship rings are sold, and only because the window's Buy routes them
        // through CashShopPartnerRings (name prompt, both ring records). Wedding/engagement rings stay out.
        for (int id : List.of(1112001, 1112002, 1112003, 1112005, 1112006, 1112007, 1112012,
                1112800, 1112801, 1112802, 1112810, 1112811, 1112812)) {
            assertTrue(rows.containsKey(id), "partner ring not sold: " + id);
            assertTrue(CashShopPartnerRings.isPartnerRing(id), "partner ring sold without a partner flow: " + id);
        }
        for (int id : List.of(1112803, 1112804, 1112806, 1112807, 1112809)) {
            assertFalse(rows.containsKey(id), "wedding/engagement ring sold: " + id);
        }
        assertFalse(rows.containsKey(5281000), "Passed Gas has no handler");
        assertFalse(rows.containsKey(5281001), "Floral Scent has no handler");
        // Served by RemoteStoreHandler (CP_UserRemoteShopOpenRequest).
        assertTrue(rows.containsKey(5470000));
        // Self-contained effect rings with no partner stay.
        assertTrue(rows.containsKey(1112900));
    }

    @Test void shippedCatalogDropsRowsTheWzDoesNotFlagAsCash() throws Exception {
        final Map<Integer, String[]> rows = shippedRows();
        for (int id : List.of(1002950, 1002951, 1002952, 1022037, 1042141, 1112916)) {
            assertFalse(rows.containsKey(id), "non-cash equip sold: " + id);
        }
    }

    @Test void equipGenderFollowsTheClientIdDigitRule() {
        assertEquals(0, constants.inventory.ItemConstants.getEquipGender(1000005));  // Men's Ninja Hat
        assertEquals(1, constants.inventory.ItemConstants.getEquipGender(1001003));  // Pink Nurse Hat
        assertEquals(1, constants.inventory.ItemConstants.getEquipGender(1051017));  // female overall
        assertEquals(2, constants.inventory.ItemConstants.getEquipGender(1002000));
        assertEquals(2, constants.inventory.ItemConstants.getEquipGender(1702000));  // NX cover
        assertEquals(2, constants.inventory.ItemConstants.getEquipGender(1112001));
        assertEquals(2, constants.inventory.ItemConstants.getEquipGender(5000000));  // not an equip
        assertEquals(2, constants.inventory.ItemConstants.getEquipGender(2000000));
        assertTrue(constants.inventory.ItemConstants.isWearableByGender(1000005, 0));
        assertFalse(constants.inventory.ItemConstants.isWearableByGender(1000005, 1));
        assertFalse(constants.inventory.ItemConstants.isWearableByGender(1001003, 0));
        assertTrue(constants.inventory.ItemConstants.isWearableByGender(1002000, 1));
    }
}
