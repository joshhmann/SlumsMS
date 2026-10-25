package client.inventory;

import client.BotClient;
import client.Character;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 1.5.2: InventoryManipulator's Client overloads took c.getPlayer(), but every bot shares one headless BotClient whose
 * getPlayer() is null (AGENTS.md). A bot's wedding ring ("InventoryManipulator.addFromDrop(player.getClient(), ring,
 * ...)" in RomanceWedding) and a bot's engagement token threw "Cannot invoke Character.getInventory(InventoryType)
 * because chr is null" inside the bot drama loop, and the ring or token was lost. The character overloads give the item
 * to the character it belongs to; packets to a bot's client go nowhere, as they should.
 */
class BotInventoryAddTest {

    private static final int RING = 4210000;   // Moonstone engagement box

    MockedStatic<ItemInformationProvider> itemData;

    @BeforeAll
    static void loadItemProviderClass() throws Exception {
        // ItemInformationProvider's static init reads the DB and the WZ; load the class once against mocks.
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(
                    any(provider.wz.WZFiles.class))).thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @BeforeEach
    void setUp() {
        ItemInformationProvider ii = mock(ItemInformationProvider.class);
        when(ii.isPickupRestricted(anyInt())).thenReturn(false);
        when(ii.getSlotMax(any(Character.class), anyInt())).thenReturn((short) 100);
        when(ii.isCash(anyInt())).thenReturn(false);
        itemData = mockStatic(ItemInformationProvider.class);
        itemData.when(ItemInformationProvider::getInstance).thenReturn(ii);
    }

    @AfterEach
    void tearDown() {
        itemData.close();
    }

    @Test
    void aBotsRingReachesTheBotsInventory() {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE + 4_001);
        bot.setName("RingBot");
        Item ring = new Item(RING, (short) 0, (short) 1, -1);

        assertTrue(InventoryManipulator.addFromDrop(bot, ring, false, -1));
        assertEquals(1, bot.getInventory(InventoryType.ETC).countById(RING));
    }

    @Test
    void aCharacterWithoutAClientGetsNothingInsteadOfAnException() {
        assertFalse(InventoryManipulator.addFromDrop((Character) null, new Item(RING, (short) 0, (short) 1, -1), false, -1));
        assertFalse(InventoryManipulator.addById((Character) null, RING, (short) 1));
    }
}
