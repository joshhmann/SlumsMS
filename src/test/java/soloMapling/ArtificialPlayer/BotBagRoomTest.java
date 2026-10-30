package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapItem;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotBagRoom;

import soloMapling.Economy.SaleRules;

import java.awt.Point;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

// 1.6.0 owner report 2026-10-26: CapeLover (lv 18 warrior) ground a Spore map with a carpet of loot under it. Its equip
// and etc tabs were 24/24 (TEST DB), so BotHoldings.canCreditDrop refused every equip and new etc drop, GrindLoot
// skipped them, and nothing in its town stop ever emptied the bag. These pin the two bots-only fixes.
class BotBagRoomTest {

    private static final int ETC_JUNK = 4000000;      // distinct ids so nothing stacks
    private static final int SPORE_CAP = 4000500;     // a fresh etc drop the bot holds none of
    private static final int EQUIP_JUNK = 1302000;

    @BeforeEach
    void setUp() {
        BotHoldings.clearForTest();
        BotYieldCaps.clearForTest();
        // caps off: no DB in a unit test (the capped path is BotYieldCapsTest's)
        BotYieldCaps.setForTest(new BotYieldCaps.Limits(false, 1.0, 600_000, 10_000, 100_000), null, null);
    }

    @AfterEach
    void tearDown() {
        BotYieldCaps.setForTest(null, null, null);
        BotYieldCaps.clearForTest();
        System.clearProperty("solomapling.botBagSlots");
        SaleRules.useForTest(null, null, null);
    }

    private static Character bot() {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(BotStore.BOT_ID_BASE + 4273723);
        return bot;
    }

    private static void fill(Character chr, InventoryType type, int firstId) {
        Inventory inv = chr.getInventory(type);
        for (int i = 0; inv.getNumFreeSlot() > 0; i++) {
            Item item = new Item(firstId + i, (short) 0, (short) 1, -1);   // an equip id as a plain Item: no WZ here
            assertTrue(inv.addItem(item) > 0);
        }
        assertTrue(inv.isFull());
    }

    private static MapItem etcDrop(Character bot, int itemId) {
        return new MapItem(new Item(itemId, (short) 0, (short) 1, -1), new Point(0, 0), bot, bot, bot.getClient(), (byte) 0, false);
    }

    @Test
    void aFullEtcTabLeavesTheDropUntilTheTownSaleMakesRoom() {
        Character bot = bot();
        fill(bot, InventoryType.ETC, ETC_JUNK);
        MapItem drop = etcDrop(bot, SPORE_CAP);
        assertFalse(BotHoldings.canCreditDrop(bot, drop), "a full etc tab cannot take a new etc drop (the carpet)");
        assertTrue(BotBagRoom.nearlyFull(bot), "a full tab is what sends the bot to town");

        BotBagRoom.Sale sale = BotBagRoom.sellJunk(bot, item -> 10, item -> false);

        assertEquals(12, sale.items(), "sold down to half of 24");
        assertEquals(120, sale.mesosCredited());
        assertEquals(120, bot.getMeso());
        assertTrue(BotBagRoom.fill(bot.getInventory(InventoryType.ETC)) <= 0.5);
        assertFalse(BotBagRoom.nearlyFull(bot));
        assertTrue(BotHoldings.canCreditDrop(bot, drop), "after the sale the bot can loot again");
    }

    @Test
    void theSaleSellsTheCheapestFirstAndNeverAKeeper() {
        Character bot = bot();
        fill(bot, InventoryType.EQUIP, EQUIP_JUNK);
        Inventory inv = bot.getInventory(InventoryType.EQUIP);
        Set<Integer> keepers = Set.of(EQUIP_JUNK, EQUIP_JUNK + 1);   // e.g. quest or cash items
        // price = id offset: the cheapest are the first ids; the two cheapest are keepers
        BotBagRoom.sellJunk(bot, item -> item.getItemId() - EQUIP_JUNK, item -> keepers.contains(item.getItemId()));

        assertEquals(12, inv.getNumFreeSlot());
        for (int id : keepers) {
            assertTrue(bot.haveItemWithId(id, false), "a keeper is never sold: " + id);
        }
        for (int i = 2; i < 14; i++) {
            assertFalse(bot.haveItemWithId(EQUIP_JUNK + i, false), "cheap junk went first: " + i);
        }
        for (int i = 14; i < 24; i++) {
            assertTrue(bot.haveItemWithId(EQUIP_JUNK + i, false), "the dearer half stays: " + i);
        }
    }

    @Test
    void aHalfEmptyBagSellsNothingAndTheUseTabIsNeverSold() {
        Character bot = bot();
        fill(bot, InventoryType.USE, 2000000);
        bot.getInventory(InventoryType.ETC).addItem(new Item(ETC_JUNK, (short) 0, (short) 1, -1));

        BotBagRoom.Sale sale = BotBagRoom.sellJunk(bot, item -> 10, item -> false);

        assertEquals(0, sale.items());
        assertTrue(bot.getInventory(InventoryType.USE).isFull(), "potions, ammo and scrolls stay");
        assertFalse(BotBagRoom.nearlyFull(bot), "a full use tab alone does not send it selling");
    }

    @Test
    void aPopulationBotGetsTheBiggerBag() {
        Character bot = bot();
        BotBagRoom.widen(bot);
        for (InventoryType type : new InventoryType[]{InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP, InventoryType.ETC}) {
            assertEquals(96, Byte.toUnsignedInt(bot.getInventory(type).getSlotLimit()), type.name());
        }
        fill(bot, InventoryType.ETC, ETC_JUNK);
        assertEquals(96, bot.getInventory(InventoryType.ETC).list().size());
    }

    @Test
    void everyBotTabIsTheV83MaximumAndNeverMore() {
        System.setProperty("solomapling.botBagSlots", "200");
        Character bot = bot();
        BotBagRoom.widen(bot);
        for (InventoryType type : new InventoryType[]{InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP,
                InventoryType.ETC, InventoryType.CASH}) {
            assertEquals(BotBagRoom.V83_MAX_SLOTS, Byte.toUnsignedInt(bot.getInventory(type).getSlotLimit()), type.name());
        }
    }

    @Test
    void aWidenNeverShrinksABag() {
        System.setProperty("solomapling.botBagSlots", "24");
        Character bot = bot();
        bot.getInventory(InventoryType.ETC).setSlotLimit(48);
        BotBagRoom.widen(bot);
        assertEquals(48, Byte.toUnsignedInt(bot.getInventory(InventoryType.ETC).getSlotLimit()));
    }

    @Test
    void aRealPlayerKeepsTheClientsSlotCounts() {
        Character player = Character.getDefault(mock(client.Client.class));
        player.setID(42);
        BotBagRoom.widen(player);
        for (InventoryType type : new InventoryType[]{InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP, InventoryType.ETC}) {
            assertEquals(24, Byte.toUnsignedInt(player.getInventory(type).getSlotLimit()), type.name());
        }
        assertFalse(BotBagRoom.widenable(player));
        assertEquals(0, BotBagRoom.sellJunk(player).items(), "a player's bag is never sold off");
    }

    @Test
    void aBotClientBelowTheBotIdBaseIsNotWidened() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(51);   // a real character id run headless (auto-QA, owned alt): the client still decides
        BotBagRoom.widen(chr);
        assertEquals(24, Byte.toUnsignedInt(chr.getInventory(InventoryType.ETC).getSlotLimit()));
    }

    // 1.6.2 bot audit G9: the town-stop sale had its own keep rule and so sold things no other bot sale may sell.
    @Test
    void theTownStopSaleNeverSellsWhatSaleRulesForbids() {
        Character bot = bot();
        fill(bot, InventoryType.EQUIP, EQUIP_JUNK);
        Inventory inv = bot.getInventory(InventoryType.EQUIP);
        int bossDrop = EQUIP_JUNK + 3;   // a boss-only drop: SaleRules refuses it, the junk sort would not
        SaleRules.useForTest(Set.of(bossDrop), id -> false, id -> false);

        BotBagRoom.Sale sale = BotBagRoom.sellJunk(bot, item -> item.getItemId() - EQUIP_JUNK,
                BotBagRoom.townStopKeep(bot, id -> false));

        assertEquals(12, sale.items(), "the sale still frees the tab");
        assertTrue(bot.haveItemWithId(bossDrop, false), "a boss-only drop is not junk");
        assertFalse(bot.haveItemWithId(EQUIP_JUNK + 0, false), "plain junk still sells");
    }
}
