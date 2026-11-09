package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;
import server.life.NPC;
import server.maps.MapObject;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotGearSystem.GearUpgrades;
import soloMapling.companions.CompanionSettings;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotBagRoom;
import soloMapling.Economy.SaleRules;
import soloMapling.companions.CompanionSellJunk;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 alt-qol-batch group D: the owner-ordered "sell junk" (ETC and USE, with the owner's keep-list). It keeps scrolls, equips, quest items,
 * potions, ammo, cash and untradeable items, so by default only plain ETC junk sells; "sell junk equips too" adds
 * plain equips but still keeps a scrolled piece and one that is an upgrade for the companion or its owner. The sale
 * goes through BotBagRoom's own path, so the mesos are the town stop's.
 */
class CompanionSellJunkTest {

    private static final int ETC_JUNK = 4000000;
    private static final int ETC_JUNK2 = 4001001;
    private static final int SCROLL = 2040000;
    private static final int POTION = 2000000;
    private static final int PLAIN_EQUIP = 1302000;
    private static final int SCROLLED_EQUIP = 1302001;
    private static final int UPGRADE_EQUIP = 1302002;
    private static final int QUEST_ITEM = 4030000;

    private Character alt;
    private Character owner;
    private MockedStatic<ItemInformationProvider> itemData;

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
    void stage() {
        // an Equip's constructor asks the item data for its equip level, so the provider is a mock from the start
        ItemInformationProvider ii = mock(ItemInformationProvider.class);
        itemData = mockStatic(ItemInformationProvider.class);
        itemData.when(ItemInformationProvider::getInstance).thenReturn(ii);
        when(ii.isCash(anyInt())).thenReturn(false);
        when(ii.isQuestItem(anyInt())).thenAnswer(call -> call.getArgument(0, Integer.class) / 10_000 == 403);
        when(ii.getPrice(anyInt(), anyInt())).thenReturn(10);
        BotHoldings.clearForTest();
        BotYieldCaps.clearForTest();
        BotYieldCaps.setForTest(new BotYieldCaps.Limits(false, 1.0, 600_000, 10_000, 100_000), null, null);
        SaleRules.useForTest(Set.of(), id -> false, id -> false); // no DB: nothing is cash or trade-blocked here
        // "an upgrade for this wearer" is the piece with this id: the real scorer needs WZ item stats
        GearUpgrades.install((wearer, candidate) -> candidate.getItemId() == UPGRADE_EQUIP ? 5 : 0);
        alt = bot();
        owner = mock(Character.class);
        when(owner.getId()).thenReturn(7);
    }

    @AfterEach
    void unstage() {
        itemData.close();
        CompanionSellJunk.useForTest(null);
        GearUpgrades.install(null);
        SaleRules.useForTest(null, null, null);
        BotYieldCaps.setForTest(null, null, null);
        BotYieldCaps.clearForTest();
        BotHoldings.clearForTest();
    }

    static Character bot() {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(BotStore.BOT_ID_BASE + 917_001);
        return c;
    }

    private void give(InventoryType type, Item item) {
        assertTrue(alt.getInventory(type).addItem(item) > 0);
    }

    @Test
    void theProtectionListKeepsScrollsEquipsAndQuestItemsAndSellsEtcJunk() {
        give(InventoryType.ETC, new Item(ETC_JUNK, (short) 0, (short) 1));
        give(InventoryType.ETC, new Item(QUEST_ITEM, (short) 0, (short) 1));
        give(InventoryType.USE, new Item(SCROLL, (short) 0, (short) 1));
        give(InventoryType.USE, new Item(POTION, (short) 0, (short) 1));
        give(InventoryType.ETC, new Item(ETC_JUNK2, (short) 0, (short) 1));
        give(InventoryType.EQUIP, new Equip(PLAIN_EQUIP, (short) 0));

        BotBagRoom.Sale sale = CompanionSellJunk.sell(alt, owner, false, item -> 10);

        assertEquals(2, sale.items(), "the two etc junk drops, nothing else");
        assertEquals(20, sale.mesosCredited());
        assertEquals(20, alt.getMeso(), "the mesos are credited exactly as the town stop credits them");
        assertFalse(alt.haveItemWithId(ETC_JUNK, false));
        assertFalse(alt.haveItemWithId(ETC_JUNK2, false));
        assertTrue(alt.haveItemWithId(QUEST_ITEM, false), "a quest item is never junk");
        assertTrue(alt.haveItemWithId(SCROLL, false), "the owner keeps every scroll");
        assertTrue(alt.haveItemWithId(POTION, false), "potions are what it fights with");
        assertTrue(alt.haveItemWithId(PLAIN_EQUIP, false), "every equip is kept by default");
    }

    /**
     * Owner, 8 Nov 2026 ("nvm add use"): sell junk sells ETC and USE, and the USE keep-list is every potion (HP, MP,
     * elixirs, food: whatever BotVitals drinks), return and teleport scrolls, skill and mastery books, summoning sacks,
     * pet food, stars and bullets, quest items and the rebirth items. One of each stays; the plain USE drop sells.
     */
    @Test
    void theUseKeepListKeepsEachClassTheOwnerNamedAndSellsPlainUseJunk() {
        int[] kept = {
                2000005,  // Power Elixir (200: HP/MP potions and elixirs)
                2001001,  // a 2001 potion (200)
                2002000,  // a stat pill (200)
                2010000,  // Apple (201: food it drinks for HP)
                2020000,  // Salad (202: food)
                2022000,  // a 2022 drink (202)
                2050004,  // All Cure Potion (205: cure potions)
                2030000,  // Return Scroll - Nearest Town (203)
                2030007,  // a teleport scroll (203)
                2280000,  // a skill book (228)
                2290000,  // a mastery book (229)
                2100000,  // a summoning sack (210)
                2120000,  // pet food (212)
                2070000,  // throwing stars (207)
                2330000,  // bullets (233)
        };
        for (int id : kept) {
            give(InventoryType.USE, new Item(id, (short) 0, (short) 1));
        }
        int plainUse = 2380000; // a plain USE drop on no keep-list (a monster card)
        give(InventoryType.USE, new Item(plainUse, (short) 0, (short) 1));

        BotBagRoom.Sale sale = CompanionSellJunk.sell(alt, owner, false, item -> 10);

        for (int id : kept) {
            assertTrue(alt.haveItemWithId(id, false), id + " is on the owner's keep-list");
        }
        assertFalse(alt.haveItemWithId(plainUse, false), "a plain USE drop is junk and sells");
        assertEquals(1, sale.items(), "only the plain USE drop");
    }

    /** The town stop never sells a rebirth key or ingredient (BotRebirth.keeps); an alt's ordered sale never does either. */
    @Test
    void theRebirthKeyAndIngredientsAreNeverSold() {
        // the second rebirth's two boss drops: neither is a quest item (this test's item data says 403xxxx is), so
        // only the rebirth rule can keep them
        int[] ingredients = soloMapling.ArtificialPlayer.BotProgressionSystem.BotRebirth.ingredientsFor(1);
        for (int id : ingredients) {
            give(InventoryType.ETC, new Item(id, (short) 0, (short) 1));
        }
        give(InventoryType.ETC, new Item(ETC_JUNK, (short) 0, (short) 1));

        BotBagRoom.Sale sale = CompanionSellJunk.sell(alt, owner, true, item -> 10);

        for (int id : ingredients) {
            assertTrue(alt.haveItemWithId(id, false), id + " is a rebirth ingredient: it stays");
        }
        assertEquals(1, sale.items(), "only the plain etc junk");
    }

    @Test
    void sellJunkEquipsTooSellsAPlainEquipButKeepsAScrolledOneAndAnUpgrade() {
        Equip plain = new Equip(PLAIN_EQUIP, (short) 0);
        Equip scrolled = new Equip(SCROLLED_EQUIP, (short) 0);
        scrolled.setLevel((byte) 1); // an upgrade slot was used
        Equip upgrade = new Equip(UPGRADE_EQUIP, (short) 0);
        give(InventoryType.EQUIP, plain);
        give(InventoryType.EQUIP, scrolled);
        give(InventoryType.EQUIP, upgrade);

        BotBagRoom.Sale sale = CompanionSellJunk.sell(alt, owner, true, item -> 10);

        assertEquals(1, sale.items(), "only the plain piece");
        assertFalse(alt.haveItemWithId(PLAIN_EQUIP, false), "a plain equip goes with \"equips too\"");
        assertTrue(alt.haveItemWithId(SCROLLED_EQUIP, false), "somebody scrolled it: never sold");
        assertTrue(alt.haveItemWithId(UPGRADE_EQUIP, false), "better than what its owner wears: never sold");
    }

    @Test
    void sellJunkEquipsTooStillKeepsAnUpgradeForTheAltItself() {
        Equip upgrade = new Equip(UPGRADE_EQUIP, (short) 0);
        give(InventoryType.EQUIP, upgrade);
        GearUpgrades.install((wearer, candidate) -> wearer == alt && candidate.getItemId() == UPGRADE_EQUIP ? 3 : 0);

        assertEquals(0, CompanionSellJunk.sell(alt, owner, true, item -> 10).items(),
                "a piece that is an upgrade for the companion stays too");
        assertTrue(alt.haveItemWithId(UPGRADE_EQUIP, false));
    }

    @Test
    void noShopOnTheMapSaysSoAndSellsNothing() {
        give(InventoryType.ETC, new Item(ETC_JUNK, (short) 0, (short) 1));
        MapleMap empty = mock(MapleMap.class);
        when(empty.getMapObjects()).thenReturn(List.of());
        alt.setMap(empty);
        alt.setPosition(new Point(0, 0));

        assertNull(CompanionSellJunk.shopOn(empty, new Point(0, 0)), "no shop NPC on this map");
        List<String> said = new ArrayList<>();
        assertFalse(CompanionSellJunk.order(alt, owner, false, said::add));
        assertEquals(List.of("there's no shop on this map"), said);
        assertTrue(alt.haveItemWithId(ETC_JUNK, false), "nothing is sold without a shop");
    }

    @Test
    void theShopOnTheMapIsTheNearestNpcThatHasOne() {
        MapleMap map = mock(MapleMap.class);
        NPC plainNpc = npc(2000, 300);
        NPC shopNpc = npc(2001, 120);
        when(map.getMapObjects()).thenReturn(new ArrayList<MapObject>(List.of(plainNpc, shopNpc)));
        CompanionSellJunk.useForTest(id -> id == 2001); // the second NPC is the one with a shop

        CompanionSellJunk.Shop shop = CompanionSellJunk.shopOn(map, new Point(0, 0));

        assertEquals(2001, shop == null ? 0 : shop.npc().getId(), "the shop NPC, not just any NPC");
        assertEquals(120, shop.at().x, "it walks to the shop NPC it found");
    }

    private static NPC npc(int id, int x) {
        NPC npc = mock(NPC.class);
        when(npc.getId()).thenReturn(id);
        when(npc.getPosition()).thenReturn(new Point(x, 0));
        return npc;
    }

    @Test
    void nothingToSellSaysSoAndNeverWalks() {
        MapleMap map = mock(MapleMap.class);
        alt.setMap(map);
        List<String> said = new ArrayList<>();

        assertFalse(CompanionSellJunk.order(alt, owner, false, said::add));
        assertEquals(List.of("nothing worth selling in my bag"), said);
    }

    @Test
    void theSaleTouchesNoOtherTabThanEtcAndUse() {
        assertEquals(List.of(InventoryType.ETC, InventoryType.USE), CompanionSellJunk.tabs(false));
        assertEquals(List.of(InventoryType.ETC, InventoryType.USE, InventoryType.EQUIP),
                CompanionSellJunk.tabs(true), "equips only when the owner says so");
    }

    @Test
    void aKeptItemIsTheOwnersProtectionListAndSaleRulesTogether() {
        Item scroll = new Item(SCROLL, (short) 0, (short) 1);
        Item junk = new Item(ETC_JUNK, (short) 0, (short) 1);
        assertTrue(CompanionSellJunk.protectedItem(alt, owner, scroll, true));
        assertFalse(CompanionSellJunk.protectedItem(alt, owner, junk, true));
        SaleRules.useForTest(Set.of(ETC_JUNK), id -> false, id -> false);
        assertTrue(CompanionSellJunk.protectedItem(alt, owner, junk, true),
                "a boss-only drop stays, whatever the mode");
        assertTrue(CompanionSellJunk.isPotionOrAmmo(2060000), "a bow's arrows are never sold");
        assertTrue(CompanionSellJunk.isPotionOrAmmo(2070000), "a thief's stars are never sold");
        assertTrue(CompanionSellJunk.isPotionOrAmmo(2000000), "a potion is never sold");
    }
}
