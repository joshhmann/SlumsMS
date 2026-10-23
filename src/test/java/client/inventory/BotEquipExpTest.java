package client.inventory;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 1.5.0 rc6 bug hunt (P2): Equip.gainItemExp ended with c.getPlayer().forceUpdateItem, but every bot shares one
 * BotClient whose getPlayer() is null. A bot wearing a levelable equip threw out of gainExp, which aborted
 * Monster.killBy's EXP share for everyone else on the mob. The equip EXP now goes through the wearer.
 */
class BotEquipExpTest {

    private static final int SWORD = 1302000;
    MockedStatic<ItemInformationProvider> itemData;
    double expRate;

    @BeforeAll
    static void loadItemProviderClass() throws Exception {
        // ItemInformationProvider's static init reads the DB and the WZ; load the class once against mocks.
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(
                    org.mockito.ArgumentMatchers.any(provider.wz.WZFiles.class))).thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @BeforeEach
    void setUp() {
        ItemInformationProvider ii = mock(ItemInformationProvider.class);
        when(ii.isUpgradeable(anyInt())).thenReturn(true);
        when(ii.isCash(anyInt())).thenReturn(false);
        when(ii.getName(anyInt())).thenReturn("Sword");
        when(ii.getEquipLevel(anyInt(), anyBoolean())).thenReturn(5);
        when(ii.getEquipLevelReq(anyInt())).thenReturn(10);
        expRate = config.YamlConfig.config.server.EQUIP_EXP_RATE;
        config.YamlConfig.config.server.EQUIP_EXP_RATE = 1;
        itemData = mockStatic(ItemInformationProvider.class);
        itemData.when(ItemInformationProvider::getInstance).thenReturn(ii);
    }

    @AfterEach
    void tearDown() {
        itemData.close();
        config.YamlConfig.config.server.EQUIP_EXP_RATE = expRate;
    }

    @Test
    void aBotsEquipGainsExpWithoutThrowing() {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE + 3_000);
        bot.setName("ExpBot");
        bot.setMap(mock(server.maps.MapleMap.class));   // a level-up tells the map about the new look
        Equip sword = new Equip(SWORD, (short) -11);
        bot.getInventory(InventoryType.EQUIPPED).addItemFromDB(sword);

        assertDoesNotThrow(() -> bot.increaseEquipExp(10_000), "threw a NullPointerException before");
        assertTrue(sword.getItemLevel() > 1, "the bot's equip really levelled (gainLevel ran through the wearer too)");
    }

    @Test
    void theClientOverloadIgnoresAClientWithNoPlayer() {
        Equip sword = new Equip(SWORD, (short) -11);
        assertDoesNotThrow(() -> sword.gainItemExp(new BotClient(0, 0), 10_000));
    }
}
