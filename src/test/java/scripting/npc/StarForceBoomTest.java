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

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.4 (audit card b293): "a superior boom destroys the item and says so". The engine used to return null without
 * touching the inventory, and scripts/npc/2616.js answered 'Complete' anyway.
 */
class StarForceBoomTest {
    private static final int SUPERIOR = 1132174;   // ItemConstants.SuperiorItemIds

    private final int rollsWas = YamlConfig.config.server.SCROLL_CHANCE_ROLLS;
    private MockedStatic<ItemInformationProvider> iiStatic;
    private Client client;
    private Character chr;

    private static final class Loader extends scripting.AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    /** What the star force NPC script touches of cm. */
    public static class FakeStarForceCm {
        public Equip item;
        public String ok, simple, yesNo, playerMessage;
        public boolean disposed;
        private final List<Integer> gainedItems = new ArrayList<>();

        public String getStarForceEquipSlot() {
            return "#1# Fake Sword";
        }

        public Item getSFItem(int slot) {
            return item;
        }

        public Equip itemToEquip(Item it) {
            return (Equip) it;
        }

        public int getMeso() {
            return 1000;
        }

        public int getMesoStarforceCost(int level, int reqLevel, boolean superior) {
            return 1;
        }

        public int getSpellTraceCost(int level, int reqLevel, boolean superior) {
            return 1;
        }

        public int getReqLevel(int itemId) {
            return 100;
        }

        public boolean isSuperior(int itemId) {
            return true;
        }

        public String numberWithCommas(int value) {
            return String.valueOf(value);
        }

        public int getSFGain(int itemId, int level, boolean superior, String stat) {
            return 0;
        }

        public int getProp(Item it) {
            return 30;
        }

        public Item getStarForceEquip(int slot) {
            return item;
        }

        public void sendSimple(String text) {
            simple = text;
        }

        public void sendOk(String text) {
            ok = text;
        }

        public void sendYesNo(String text) {
            yesNo = text;
        }

        public void sendMessageToPlayer(String text) {
            playerMessage = text;
        }

        public void gainMeso(int amount) {
        }

        public void gainItem(int id, int quantity) {
            gainedItems.add(id);
        }

        public boolean haveItem(int id, int quantity) {
            return true;
        }

        public void dispose() {
            disposed = true;
        }

        /** The engine boomed the item: StarForceEquip returned null because the item no longer exists. */
        public Item StarForceEquip(Item it) {
            return null;
        }
    }

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

    /**
     * The boom chance of a superior item at 5+ stars with item level 10+ is 30%, rolled once per attempt, so 100
     * attempts miss it with probability 0.7^100.
     */
    @Test
    void aSuperiorBoomDestroysTheItemAndTellsTheClient() {
        YamlConfig.config.server.SCROLL_CHANCE_ROLLS = 1;
        NPCConversationManager cm = new NPCConversationManager(client, 2616, "2616");

        boolean boomed = false;
        for (int attempt = 0; attempt < 100 && !boomed; attempt++) {
            Equip equip = new Equip(SUPERIOR, (short) 1, 5);
            equip.setLevel((byte) 5);
            equip.setItemLevel((byte) 12);
            equip.setUpgradeSlots((byte) 5);
            chr.getInventory(InventoryType.EQUIP).addItemFromDB(equip);

            if (cm.StarForceEquip(equip) == null) {
                boomed = true;
                assertNull(chr.getInventory(InventoryType.EQUIP).getItem((short) 1),
                        "the boomed item is gone from the inventory");
            } else {
                chr.getInventory(InventoryType.EQUIP).removeItem((short) 1);
            }
        }

        assertTrue(boomed, "a 30% superior boom did not happen in 100 attempts");
        verify(client, atLeastOnce()).sendPacket(any());
    }

    @Test
    void theScriptDoesNotReportCompleteWhenTheItemWasDestroyed() throws Exception {
        ScriptEngine engine = new Loader().load("npc/2616.js");
        assertNotNull(engine, "scripts/npc/2616.js evaluates");
        FakeStarForceCm fake = new FakeStarForceCm();
        fake.item = new Equip(SUPERIOR, (short) 1, 5);
        fake.item.setLevel((byte) 6);
        engine.put("cm", fake);

        Invocable invocable = (Invocable) engine;
        invocable.invokeFunction("start");
        assertTrue(fake.simple.contains("#1# Fake Sword"), "the item list is offered");
        invocable.invokeFunction("action", 1, 0, 1);
        invocable.invokeFunction("action", 1, 0, 0);

        assertFalse("Complete".equals(fake.ok), "a destroyed item must not be reported as Complete");
        assertTrue(fake.ok.contains("destroyed"), "the player is told the item was destroyed");
    }
}
