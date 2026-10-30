package soloMapling.ArtificialPlayer.BotTradeSystem;

import client.BotClient;
import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotSupplySystem.SupplyShare;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotGiveTrade.Give;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * 1.6.2 ticket 99: what "trade pots / scrolls / use / etc / equips / ammo / rocks / <item> / <slot>" picks (after
 * nutnnut/Cosmic BotInventoryManager.collectItems): only what a player could put in a window - never a worn item,
 * never an untradeable one.
 */
class TradeCategoriesTest {

    static final int RED = 2000000;
    static final int BLUE = 2000003;
    static final int SCROLL = 2040001;
    static final int ARROW = 2060000;
    static final int STAR = 2070000;
    static final int SNAIL_SHELL = 4000000;
    static final int QUEST_ITEM = 4031000;
    static final int MAGIC_ROCK = 4006000;
    static final int HAT = 1002000;
    static final int SHOES = 1072000;

    static final Map<Integer, String> NAMES = Map.of(RED, "Red Potion", BLUE, "Blue Potion", SCROLL,
            "Scroll for Helmet for DEF", SNAIL_SHELL, "Snail Shell", HAT, "Blue Bandana", SHOES, "Leather Sandals");

    final Character alt = Character.getDefault(new BotClient(0, 0));

    @BeforeEach
    void setUp() {
        TradeCategories.useForTest(new TradeCategories.Facts() {
            @Override
            public boolean tradeable(Item item) {
                return item.getItemId() != QUEST_ITEM;
            }

            @Override
            public String name(int itemId) {
                return NAMES.getOrDefault(itemId, "");
            }

            @Override
            public boolean potion(int itemId) {
                return itemId / 10000 == 200;
            }

            @Override
            public short slotOf(int itemId) {
                return itemId == HAT ? (short) -1 : itemId == SHOES ? (short) -7 : 0;
            }
        });
        SupplyShare.useForTest((holder, kind, needy) -> {
            List<Item> out = new ArrayList<>();
            for (Item i : holder.getInventory(InventoryType.USE).list()) {
                if (kind == SupplyShare.Kind.HP && i.getItemId() == RED || kind == SupplyShare.Kind.MP && i.getItemId() == BLUE) {
                    out.add(i);
                }
            }
            return out;
        });
        put(InventoryType.USE, new Item(RED, (short) 1, (short) 100));
        put(InventoryType.USE, new Item(BLUE, (short) 2, (short) 40));
        put(InventoryType.USE, new Item(SCROLL, (short) 3, (short) 2));
        put(InventoryType.USE, new Item(ARROW, (short) 4, (short) 1000));
        put(InventoryType.USE, new Item(STAR, (short) 5, (short) 500));
        put(InventoryType.ETC, new Item(SNAIL_SHELL, (short) 1, (short) 30));
        put(InventoryType.ETC, new Item(QUEST_ITEM, (short) 2, (short) 1));
        put(InventoryType.ETC, new Item(MAGIC_ROCK, (short) 3, (short) 25));
        put(InventoryType.EQUIP, equip(HAT, (short) 1));
        put(InventoryType.EQUIP, equip(SHOES, (short) 2));
        put(InventoryType.EQUIPPED, equip(HAT, (short) -1)); // worn: never picked by a category
    }

    @AfterEach
    void tearDown() {
        TradeCategories.useForTest(null);
        SupplyShare.useForTest(null);
    }

    private void put(InventoryType tab, Item item) {
        alt.getInventory(tab).addItemFromDB(item);
    }

    private static Equip equip(int itemId, short position) {
        Equip e = mock(Equip.class, CALLS_REAL_METHODS);
        doReturn(itemId).when(e).getItemId();
        e.setPosition(position);
        e.setQuantity((short) 1);
        return e;
    }

    private List<Integer> ids(String category) {
        return TradeCategories.select(alt, category).stream().map(g -> g.item().getItemId()).toList();
    }

    @Test
    void eachCategoryPicksItsItems() {
        assertEquals(List.of(RED, BLUE), ids("pots"));
        assertEquals(List.of(RED), ids("pots:hp"));
        assertEquals(List.of(BLUE), ids("pots:mp"));
        assertEquals(List.of(SCROLL), ids("scrolls"));
        assertEquals(List.of(RED, BLUE, SCROLL), ids("use"), "use leaves the ammo to 'trade ammo'");
        assertEquals(List.of(ARROW, STAR), ids("ammo"));
        assertEquals(List.of(SNAIL_SHELL, MAGIC_ROCK), ids("etc"), "the untradeable quest item stays");
        assertEquals(List.of(MAGIC_ROCK), ids("rocks"));
        assertEquals(List.of(HAT, SHOES), ids("equips"), "the worn hat is not in the bag");
        assertEquals(List.of(HAT), ids("slot:hat"));
        assertEquals(List.of(RED), ids("name:red pot"));
        assertEquals(List.of(HAT, BLUE), ids("name:blue"));
        assertTrue(ids("mesos:1000").isEmpty());
        assertTrue(ids("name:x").isEmpty(), "too short a name matches nothing");
    }

    @Test
    void wholeStacksInInventoryOrder() {
        List<Give> pots = TradeCategories.select(alt, "pots");
        assertEquals(100, pots.get(0).quantity());
        assertEquals(InventoryType.USE, pots.get(0).type());
    }

    @Test
    void howCompanionsSayIt() {
        assertEquals("hp pots", TradeCategories.said("pots:hp"));
        assertEquals("hat", TradeCategories.said("slot:hat"));
        assertEquals("red potion", TradeCategories.said("name:red potion"));
        assertEquals(100_000, TradeCategories.mesos("mesos:100000"));
        assertEquals(-1, TradeCategories.mesos("pots"));
    }
}
