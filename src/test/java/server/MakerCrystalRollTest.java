package server;

import client.inventory.Equip;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/** Owner 2026-10-25 (1.5.2): Maker Black/Dark crystals only ever improve the stats an item already has. */
class MakerCrystalRollTest {

    @BeforeAll
    static void initializeProviderWithoutGameData() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection).thenReturn(mock(java.sql.Connection.class, RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    private static Equip item() {
        Equip e = mock(Equip.class, org.mockito.Mockito.CALLS_REAL_METHODS); // a real Equip loads item data
        e.setStr((short) 10);
        e.setLuk((short) 1);
        e.setAcc((short) 4);
        e.setWatk((short) 17);
        e.setWdef((short) 1);
        e.setHp((short) 30);
        return e; // DEX, INT, avoid, magic attack, M.DEF, speed, jump and MP stay 0
    }

    @Test
    void aDarkCrystalNeverLowersAStatAndAddsNoNewOnes() {
        boolean improved = false;
        for (int i = 0; i < 2000; i++) {
            Equip e = item();
            ItemInformationProvider.applyMakerCrystalRoll(e, 3, false);
            assertBetween(e.getStr(), 10, 13);
            assertBetween(e.getLuk(), 1, 4);
            assertBetween(e.getAcc(), 4, 7);
            assertEquals(0, e.getDex());
            assertEquals(0, e.getInt());
            assertEquals(0, e.getAvoid());
            assertEquals(17, e.getWatk(), "a stat crystal leaves attack alone");
            improved |= e.getStr() > 10;
        }
        assertTrue(improved, "the crystal does add stats");
    }

    @Test
    void aBlackCrystalNeverLowersAStatAndAddsNoNewOnes() {
        for (int i = 0; i < 2000; i++) {
            Equip e = item();
            ItemInformationProvider.applyMakerCrystalRoll(e, 5, true);
            assertBetween(e.getWatk(), 17, 22);
            assertBetween(e.getWdef(), 1, 6);
            assertBetween(e.getHp(), 30, 35);
            assertEquals(0, e.getMatk());
            assertEquals(0, e.getMdef());
            assertEquals(0, e.getSpeed());
            assertEquals(0, e.getJump());
            assertEquals(0, e.getMp());
            assertEquals(10, e.getStr(), "an option crystal leaves STR alone");
        }
    }

    private static void assertBetween(int actual, int min, int max) {
        assertTrue(actual >= min && actual <= max, actual + " not in " + min + ".." + max);
    }
}
