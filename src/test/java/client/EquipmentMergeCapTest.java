package client;

import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.YamlConfig;
import constants.inventory.ItemConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Owner 2026-10-26: Dalair's equipment merge (9000040) needs level 160 (110 Cygnus) only, with no Maker requirement (owner 2026-10-27), whatever
 * USE_STARTER_MERGE says, and any one item takes at most 3 merges (count kept in server-only flag bits).
 */
class EquipmentMergeCapTest {
    private static final int WORN_HAT = 1002000;
    private static final int JUNK = 1302000;

    private final boolean starterMergeWas = YamlConfig.config.server.USE_STARTER_MERGE;
    private final int maxStatWas = YamlConfig.config.server.MAX_EQUIPMNT_STAT;
    private MockedStatic<ItemInformationProvider> iiStatic;
    private Character chr;
    private Equip hat;

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
    void setUp() {
        YamlConfig.config.server.MAX_EQUIPMNT_STAT = 150000;   // as on TEST and live; a test config may leave it 0
        ItemInformationProvider ii = mock(ItemInformationProvider.class);
        when(ii.isUpgradeable(anyInt())).thenReturn(true);
        when(ii.isCash(anyInt())).thenReturn(false);
        when(ii.getName(anyInt())).thenAnswer(inv -> "Item" + inv.getArgument(0));
        when(ii.getEquipLevel(anyInt(), anyBoolean())).thenReturn(1);
        iiStatic = mockStatic(ItemInformationProvider.class);
        iiStatic.when(ItemInformationProvider::getInstance).thenReturn(ii);

        Client client = mock(Client.class);
        chr = Character.getDefault(client);
        chr.setName("Merger");
        when(client.getPlayer()).thenReturn(chr);
        chr.setLevel(160);

        hat = new Equip(WORN_HAT, (short) -1);
        hat.setStr((short) 5);
        hat.setWdef((short) 10);
        chr.getInventory(InventoryType.EQUIPPED).addItemFromDB(hat);
    }

    @AfterEach
    void tearDown() {
        iiStatic.close();
        YamlConfig.config.server.USE_STARTER_MERGE = starterMergeWas;
        YamlConfig.config.server.MAX_EQUIPMNT_STAT = maxStatWas;
    }

    private Equip junk(short slot) {
        Equip e = new Equip(JUNK, slot);
        e.setStr((short) 50);
        e.setDex((short) 50);
        chr.getInventory(InventoryType.EQUIP).addItemFromDB(e);
        return e;
    }

    private int equipCount() {
        return chr.getInventory(InventoryType.EQUIP).list().size();
    }

    @Test
    void flagBitsHoldTheCountAndTheClientNeverSeesThem() {
        short flag = ItemConstants.UNTRADEABLE | ItemConstants.MERGE_UNTRADEABLE;
        assertEquals(0, ItemConstants.getMergeCount(flag));
        short two = ItemConstants.withMergeCount(flag, 2);
        assertEquals(2, ItemConstants.getMergeCount(two));
        assertEquals(flag, ItemConstants.clientFlag(two), "count bits are stripped, every other bit kept");
        assertEquals(3, ItemConstants.getMergeCount(ItemConstants.withMergeCount(flag, 9)), "clamped to the cap");
        assertEquals(flag, ItemConstants.withMergeCount(two, 0));
    }

    @Test
    void starterMergeFlagNoLongerSkipsTheLevelGate() {
        YamlConfig.config.server.USE_STARTER_MERGE = true;
        junk((short) 1);

        chr.setLevel(30);
        assertFalse(chr.meetsEquipmentMergeRequirements());
        assertEquals(Character.MERGE_REQUIREMENTS, chr.mergeItemsFromName("Item" + JUNK));
        assertEquals(1, equipCount(), "nothing destroyed");

        chr.setLevel(160);
        assertTrue(chr.meetsEquipmentMergeRequirements(), "level 160 with no Maker skill at all passes");
    }

    @Test
    void anItemTakesAtMostThreeMerges() {
        for (int run = 1; run <= 3; run++) {
            junk((short) 1);
            assertEquals(Character.MERGE_OK, chr.mergeItemsFromName("Item" + JUNK), "merge " + run);
            assertEquals(0, equipCount(), "the source is used up");
            assertEquals(run, Character.getMergeCount(hat));
        }
        assertTrue(chr.getMergeableEquipped().isEmpty());
        assertTrue((hat.getFlag() & ItemConstants.MERGE_UNTRADEABLE) != 0);

        junk((short) 1);
        short str = hat.getStr();
        assertEquals(Character.MERGE_ALL_CAPPED, chr.mergeItemsFromName("Item" + JUNK));
        assertEquals(1, equipCount(), "a capped merge destroys nothing");
        assertEquals(str, hat.getStr(), "and adds nothing");
        assertEquals(3, Character.getMergeCount(hat));
    }

    @Test
    void previewListsWhatWillBeDestroyedAndSkipsLockedItems() {
        junk((short) 2);
        Equip locked = junk((short) 3);
        locked.setFlag(ItemConstants.LOCK);
        junk((short) 5);
        chr.getInventory(InventoryType.EQUIP).addItemFromDB(new Equip(JUNK + 1, (short) 1));

        assertNull(chr.previewMergeFromName("No Such Thing"));
        List<String> preview = chr.previewMergeFromName("Item" + JUNK);
        assertNotNull(preview);
        assertEquals(List.of("2: Item" + JUNK, "5: Item" + JUNK), preview, "from the named slot onward, locked kept");

        assertEquals(Character.MERGE_OK, chr.mergeItemsFromName("Item" + JUNK));
        assertNotNull(chr.getInventory(InventoryType.EQUIP).getItem((short) 3), "the locked item survives");
        assertNotNull(chr.getInventory(InventoryType.EQUIP).getItem((short) 1), "items before the slot survive");
        assertNull(chr.getInventory(InventoryType.EQUIP).getItem((short) 2));
        assertNull(chr.getInventory(InventoryType.EQUIP).getItem((short) 5));
    }

    @Test
    void nothingToMergeIsReported() {
        Item locked = junk((short) 1);
        locked.setFlag(ItemConstants.LOCK);
        assertEquals(Character.MERGE_NO_SOURCES, chr.mergeItemsFromName("Item" + JUNK));
        assertEquals(Character.MERGE_NOT_FOUND, chr.mergeItemsFromName("Nope"));
        assertEquals(0, Character.getMergeCount(hat));
    }
}
