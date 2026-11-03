package scripting.npc;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;
import server.maps.MapleMap;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 1.6.4 (audit card b293): "a failed star force enhancement loses a star and the stats it gave". The old
 * {@code (nEquip.getLevel() % 1) != 0} test was never true, so a fail cost only the meso and spell traces.
 */
class StarForceFailTest {
    private static final int JUNK = 1302000;

    private final int rollsWas = YamlConfig.config.server.SCROLL_CHANCE_ROLLS;
    private MockedStatic<ItemInformationProvider> iiStatic;
    private Client client;
    private Character chr;

    @BeforeAll
    static void initItemProvider() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection).thenReturn(mock(java.sql.Connection.class, RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        ItemInformationProvider ii = mock(ItemInformationProvider.class);
        when(ii.isUpgradeable(anyInt())).thenReturn(true);
        when(ii.getName(anyInt())).thenAnswer(inv -> "Item" + inv.getArgument(0));
        when(ii.getEquipLevel(anyInt(), anyBoolean())).thenReturn(1);
        iiStatic = mockStatic(ItemInformationProvider.class);
        iiStatic.when(ItemInformationProvider::getInstance).thenReturn(ii);

        client = mock(Client.class);
        chr = Character.getDefault(client);
        when(client.getPlayer()).thenReturn(chr);
        Field mapField = Class.forName("client.AbstractCharacterObject").getDeclaredField("map");
        mapField.setAccessible(true);
        mapField.set(chr, mock(MapleMap.class));
    }

    @AfterEach
    void tearDown() {
        iiStatic.close();
        YamlConfig.config.server.SCROLL_CHANCE_ROLLS = rollsWas;
    }

    @Test
    void aFailedRollTakesTheStarTheSlotAndTheStatsBack() {
        YamlConfig.config.server.SCROLL_CHANCE_ROLLS = 0;   // (1 - prop)^0 == 1.0, so every roll fails
        NPCConversationManager cm = new NPCConversationManager(client, 2616, "2616");

        Equip equip = new Equip(JUNK, (short) 1, 3);
        equip.setLevel((byte) 5);
        equip.setUpgradeSlots((byte) 3);
        equip.setStr((short) 50);
        equip.setDex((short) 50);
        chr.getInventory(InventoryType.EQUIP).addItemFromDB(equip);

        Item result = cm.StarForceEquip(equip);

        assertSame(equip, result);
        assertEquals(4, equip.getLevel(), "a fail drops one star");
        assertEquals(4, equip.getUpgradeSlots(), "a fail gives the slot back");
        assertEquals(48, equip.getStr(), "the stats that star gave are gone (level 4 boost is 2)");
        assertEquals(48, equip.getDex());
    }
}
