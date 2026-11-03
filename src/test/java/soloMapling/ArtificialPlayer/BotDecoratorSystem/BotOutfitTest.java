package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import client.BotClient;
import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import constants.inventory.EquipType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotCustomization;
import soloMapling.itemPool.ItemInformationProviderUtilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Ticket 181 prog#P1: an overall sits in the same equipped slot as a coat (body part 5), so the old naked test
 * saw "no top, no pants" behind it, decoration handed the bot a coat and a pair of pants, and the overall was
 * dropped on the floor by the swap. An overall now dresses the bot, decoration fills only empty slots, and a
 * replaced piece goes back into the equip bag.
 */
class BotOutfitTest {

    private static final int COAT = 1040000;
    private static final int OVERALL = 1050000;
    private static final int PANTS = 1060000;
    private static final short SLOT_TOP = -5;
    private static final short SLOT_PANTS = -6;

    private Character bot;

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
        bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE + 4_100);
        bot.setName("OutfitBot");
        bot.setMap(mock(server.maps.MapleMap.class));   // equipping tells the map about the new look
    }

    @AfterEach
    void tearDown() {
        bot = null;
    }

    @Test
    void anOverallDressesTheBot() {
        wear(OVERALL);

        assertFalse(BotEquipChecker.isNaked(bot),
                "an overall covers the top and the pants: the bot must not read as naked");
    }

    @Test
    void decorationLeavesAnOverallAlone() {
        wear(OVERALL);

        assertEquals(List.of(), putOn(), "an overall is a whole outfit: nothing is missing, nothing gets replaced");
    }

    @Test
    void decorationFillsOnlyTheEmptySlot() {
        wear(COAT);

        assertEquals(List.of(PANTS), putOn(), "the worn coat stays; only the empty pants slot is filled");
    }

    @Test
    void replacingAWornPieceBagsIt() {
        wear(OVERALL);

        try (MockedStatic<ItemInformationProvider> itemData = mockStatic(ItemInformationProvider.class)) {
            ItemInformationProvider ii = mock(ItemInformationProvider.class);
            Map<String, Integer> stats = new HashMap<>();
            stats.put("cash", 0);
            stats.put("reqJob", 0);
            stats.put("reqLevel", 0);
            when(ii.getEquipStats(anyInt())).thenReturn(stats);
            when(ii.getEquipById(anyInt())).thenAnswer(call -> new Equip(call.getArgument(0), (short) 0));
            itemData.when(ItemInformationProvider::getInstance).thenReturn(ii);

            BotCustomization.EquipItem(bot, COAT, SLOT_TOP);
        }

        assertEquals(COAT, bot.getInventory(InventoryType.EQUIPPED).getItem(SLOT_TOP).getItemId(),
                "the coat went on");
        assertEquals(List.of(OVERALL), bagged(OVERALL), "the replaced overall is bagged, not discarded");
    }

    /** Puts one clean piece on the bot's body. */
    private void wear(int itemId) {
        short slot = itemId / 10000 == 106 ? SLOT_PANTS : SLOT_TOP;   // pants sit in their own slot
        bot.getInventory(InventoryType.EQUIPPED).addItemFromDB(new Equip(itemId, slot));
    }

    /** Runs decoration against a fixed gear pool and returns the item ids it handed the bot. */
    private List<Integer> putOn() {
        List<Integer> put = new ArrayList<>();
        try (MockedStatic<ItemInformationProviderUtilities> pool = mockStatic(ItemInformationProviderUtilities.class);
             MockedStatic<BotCustomization> wardrobe = mockStatic(BotCustomization.class)) {
            pool.when(() -> ItemInformationProviderUtilities.getRandomEquipForWearing(eq(EquipType.COAT), any()))
                    .thenReturn(COAT);
            pool.when(() -> ItemInformationProviderUtilities.getRandomEquipForWearing(eq(EquipType.PANTS), any()))
                    .thenReturn(PANTS);
            pool.when(() -> ItemInformationProviderUtilities.getRandomEquipForWearing(eq(EquipType.LONGCOAT), any()))
                    .thenReturn(OVERALL);
            wardrobe.when(() -> BotCustomization.EquipBot(any(), any())).thenAnswer(call -> {
                put.add(call.getArgument(1));
                return null;
            });

            BotDecorateEquips.equipTopBottom(bot);
        }
        return put;
    }

    private List<Integer> bagged(int itemId) {
        return bot.getInventory(InventoryType.EQUIP).list().stream()
                .filter(item -> item.getItemId() == itemId)
                .map(item -> item.getItemId())
                .toList();
    }
}
