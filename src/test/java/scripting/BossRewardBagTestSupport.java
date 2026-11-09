package scripting;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.server.world.World;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Shared harness for the 1.6.7 bag-space fixes at the three reward NPCs (Plunk 3004006, Adobis 2030008, Tana
 * 2041025). Each member is a mocked {@link Character} over <b>real</b> {@link Inventory} bags and a real
 * {@link AbstractPlayerInteraction}, so {@code gainItem} lands in the bag, a full bag answers exactly as it does in
 * game, and a test asserts what the player now holds rather than which methods the script called.
 *
 * <p>Only the item data provider is mocked ({@code ItemInformationProvider}), because reading it needs the WZ files
 * and the database: the slot limits are the test's to choose, which is also how a reward that needs several stacks
 * (1000 Power Elixirs at 100 per stack) is exercised.
 */
final class BossRewardBagTestSupport {

    /** Room in every bag; a test fills a tab as far as its case needs. */
    static final byte SLOTS = 24;

    static final List<InventoryType> TABS = List.of(InventoryType.EQUIP, InventoryType.USE,
            InventoryType.SETUP, InventoryType.ETC, InventoryType.CASH);

    private BossRewardBagTestSupport() {
    }

    /**
     * Loads ItemInformationProvider against mocks once: its static init reads the database and the WZ, and this test
     * needs neither. Same trick as JobMasterBagTest / BotInventoryAddTest.
     */
    static void loadItemProviderClass() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(
                    any(provider.wz.WZFiles.class))).thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    /**
     * Stubs {@link ItemInformationProvider}: everything stacks to {@code defaultSlotMax} unless the test gives that
     * item its own limit. Must be closed after each test.
     */
    static MockedStatic<ItemInformationProvider> stubItemData(short defaultSlotMax, Map<Integer, Short> slotMaxByItem) {
        ItemInformationProvider ii = mock(ItemInformationProvider.class);
        when(ii.isPickupRestricted(anyInt())).thenReturn(false);
        when(ii.isCash(anyInt())).thenReturn(false);
        Map<Integer, Short> limits = new HashMap<>(slotMaxByItem);
        when(ii.getSlotMax(any(Character.class), anyInt()))
                .thenAnswer(call -> limits.getOrDefault(call.getArgument(1, Integer.class), defaultSlotMax));

        MockedStatic<ItemInformationProvider> stub = mockStatic(ItemInformationProvider.class);
        stub.when(ItemInformationProvider::getInstance).thenReturn(ii);
        return stub;
    }

    /** One player: its own client (so a grant cannot be confused with another member's), real bags, real api. */
    static final class Member {
        final int id;
        final Character chr;
        final Map<InventoryType, Inventory> bags = new EnumMap<>(InventoryType.class);
        final List<String> drops = new ArrayList<>();
        final List<String> unclaimed = new ArrayList<>();
        final AbstractPlayerInteraction api;

        Member(int id, World world) {
            this.id = id;
            Client client = mock(Client.class);
            when(client.getWorldServer()).thenReturn(world);

            chr = mock(Character.class);
            when(client.getPlayer()).thenReturn(chr);
            for (InventoryType tab : TABS) {
                bags.put(tab, new Inventory(chr, tab, SLOTS));
            }
            doAnswer(call -> bags.get(call.getArgument(0, InventoryType.class)))
                    .when(chr).getInventory(any(InventoryType.class));
            when(chr.getId()).thenReturn(id);
            when(chr.getClient()).thenReturn(client);
            when(chr.getWorld()).thenReturn(1);
            doAnswer(call -> {
                drops.add(call.getArgument(0, String.class));
                return null;
            }).when(chr).dropMessage(anyString());

            // Scripts ask "do you hold item X, and enough of it" through Character.getItemQuantity; answer it from
            // the same real bags gainItem writes to, so a script's material gate sees what the player really holds.
            when(chr.getItemQuantity(anyInt(), anyBoolean()))
                    .thenAnswer(call -> countEverywhere(call.getArgument(0, Integer.class)));

            api = new AbstractPlayerInteraction(client, chr);
            when(chr.getAbstractPlayerInteraction()).thenReturn(api);
        }

        /** That item across the four item tabs, the way a player would count it. */
        int countEverywhere(int itemId) {
            int total = 0;
            for (InventoryType tab : TABS) {
                total += count(tab, itemId);
            }
            return total;
        }

        Inventory bag(InventoryType tab) {
            return bags.get(tab);
        }

        /** How many of that item this member holds, across stacks. */
        int count(InventoryType tab, int itemId) {
            int total = 0;
            for (Item item : bag(tab).listById(itemId)) {
                total += item.getQuantity();
            }
            return total;
        }

        int occupied(InventoryType tab) {
            return SLOTS - bag(tab).getNumFreeSlot();
        }

        /** Fills that tab with {@code used} different single items, so every one of them costs a slot. */
        void fill(InventoryType tab, int used) {
            int base = switch (tab) {
                case EQUIP -> 1_000_000;
                case USE -> 2_001_000;
                case SETUP -> 3_000_000;
                case ETC -> 4_000_000;
                default -> 5_000_000;
            };
            int before = occupied(tab);
            for (int i = 0; i < used; i++) {
                assertTrue(bag(tab).addItem(new Item(base + i, (short) 0, (short) 1, -1)) > 0,
                        "filler " + (base + i) + " fits in " + tab);
            }
            assertEquals(before + used, occupied(tab), "tab " + tab + " now has " + (before + used) + " slots taken");
        }

        /** Puts one stack of that item in the tab. */
        Item stack(InventoryType tab, int itemId, int quantity) {
            Item item = new Item(itemId, (short) 0, (short) quantity, -1);
            assertTrue(bag(tab).addItem(item) > 0, "a stack of " + itemId + " fits in " + tab);
            return item;
        }
    }
}
