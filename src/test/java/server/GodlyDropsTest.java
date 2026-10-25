package server;

import client.Character;
import client.inventory.Equip;
import config.ServerConfig;
import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Owner 2026-10-25 (1.5.2): godly drops are rare (1.5% of mob equip drops) and modest (+1..+5 on 3 stats). */
class GodlyDropsTest {

    @org.junit.jupiter.api.BeforeAll
    static void initializeProviderWithoutGameData() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(
                    org.mockito.ArgumentMatchers.any(provider.wz.WZFiles.class))).thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    private final double percentWas = YamlConfig.config.server.GODLY_DROP_PERCENT;

    @AfterEach
    void restore() {
        YamlConfig.config.server.GODLY_DROP_PERCENT = percentWas;
    }

    private static Equip sword() {
        Equip e = mock(Equip.class, CALLS_REAL_METHODS); // a real Equip loads item data
        e.setGiftFrom("");
        e.setStr((short) 3);
        e.setDex((short) 2);
        e.setWatk((short) 17);
        e.setSpeed((short) 1);
        e.setHp((short) 10);
        return e; // INT, LUK, M.ATT, defenses, accuracy, avoid, jump, MP stay 0
    }

    private static int total(Equip e) {
        return e.getStr() + e.getDex() + e.getInt() + e.getLuk() + e.getWatk() + e.getMatk() + e.getWdef()
                + e.getMdef() + e.getAcc() + e.getAvoid() + e.getSpeed() + e.getJump() + e.getHp() + e.getMp();
    }

    @Test
    void theOwnersDefaults() {
        ServerConfig d = new ServerConfig();
        assertEquals(1.5, d.GODLY_DROP_PERCENT);
        assertEquals(3, d.GODLY_DROP_STATS);
        assertEquals(1, d.GODLY_DROP_MIN_BONUS);
        assertEquals(5, d.GODLY_DROP_MAX_BONUS);
    }

    @Test
    void threeExistingStatsGoUpByOneToFiveAndTheItemIsMarked() {
        Random r = new Random(7);
        for (int i = 0; i < 500; i++) {
            Equip e = sword();
            List<String> bumped = GodlyDrops.makeGodly(e, (lo, hi) -> lo + r.nextInt(hi - lo + 1));
            assertEquals(3, bumped.size(), bumped.toString());
            int gain = total(e) - (3 + 2 + 17 + 1 + 10);
            assertTrue(gain >= 3 && gain <= 15, "gain " + gain);
            assertTrue(e.getStr() >= 3 && e.getStr() <= 8 && e.getWatk() >= 17 && e.getWatk() <= 22);
            assertEquals(0, e.getInt() + e.getLuk() + e.getMatk() + e.getWdef() + e.getMdef() + e.getAcc()
                    + e.getAvoid() + e.getJump() + e.getMp(), "no new stats, as with a chaos scroll");
            assertTrue(GodlyDrops.isGodlyDrop(e));
        }
    }

    @Test
    void anItemWithoutStatsIsNotMarked() {
        Equip bare = mock(Equip.class, CALLS_REAL_METHODS);
        bare.setGiftFrom("");
        assertTrue(GodlyDrops.makeGodly(bare, (lo, hi) -> lo).isEmpty());
        assertFalse(GodlyDrops.isGodlyDrop(bare));
    }

    @Test
    void theRateKnobDecides() {
        YamlConfig.config.server.GODLY_DROP_PERCENT = 0;
        for (int i = 0; i < 1000; i++) {
            assertFalse(GodlyDrops.isGodlyDrop(GodlyDrops.rollMobDrop(sword())));
        }
        YamlConfig.config.server.GODLY_DROP_PERCENT = 100;
        assertTrue(GodlyDrops.isGodlyDrop(GodlyDrops.rollMobDrop(sword())));

        YamlConfig.config.server.GODLY_DROP_PERCENT = 1.5;
        int godly = 0;
        for (int i = 0; i < 40_000; i++) {
            if (GodlyDrops.isGodlyDrop(GodlyDrops.rollMobDrop(sword()))) {
                godly++;
            }
        }
        assertTrue(godly > 400 && godly < 800, godly + " of 40,000 (expected about 600)");
    }

    @Test
    void onlyAPlayerLootingAMobDropGetsTheNote() {
        Equip e = sword();
        GodlyDrops.makeGodly(e, (lo, hi) -> lo);
        ItemInformationProvider ii = mock(ItemInformationProvider.class);
        Character player = mock(Character.class), bot = mock(Character.class);
        when(player.getId()).thenReturn(42);
        when(bot.getId()).thenReturn(1_000_000_042);
        try (var iip = mockStatic(ItemInformationProvider.class)) {
            iip.when(ItemInformationProvider::getInstance).thenReturn(ii);
            when(ii.getName(anyInt())).thenReturn("Sword");
            GodlyDrops.tellLooter(player, e, false);
            GodlyDrops.tellLooter(player, e, true);   // someone dropped it again: no news
            GodlyDrops.tellLooter(bot, e, false);
            GodlyDrops.tellLooter(player, sword(), false); // not godly
        }
        verify(player).dropMessage(6, "[Godly] Your Sword dropped with bonus stats!");
        verify(bot, never()).dropMessage(anyInt(), anyString());
    }
}
