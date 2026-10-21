package net.server.channel.handlers;

import client.BotClient;
import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.YamlConfig;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.colorprism.ColorPrismPackets;
import server.colorprism.ColorPrismStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** WeaponTintHandler: target and prism validation, the tint rules, and that a prism is spent only on success. */
class WeaponTintHandlerTest {
    private static final int PRISM = 5782000;
    private static final int CASH_WEAPON = 1702000;
    private static final int PLAIN_SWORD = 1302000;
    private static final short PRISM_SLOT = 3;

    private final boolean wasEnabled = YamlConfig.config.server.COLOR_PRISM_ENABLED;

    private Client client;
    private Character chr;
    private Effects effects;
    private WeaponTintHandler handler;

    /** Records what the handler does to the world; consuming really removes the prism so double-spends show. */
    private static final class Effects implements WeaponTintHandler.Effects {
        final Set<Integer> cash = Set.of(CASH_WEAPON, PRISM);
        final List<Short> consumed = new ArrayList<>();
        int saves;
        int broadcasts;

        @Override
        public boolean isCash(int itemId) {
            return cash.contains(itemId);
        }

        @Override
        public void consumePrism(Client c, short slot) {
            consumed.add(slot);
            c.getPlayer().getInventory(InventoryType.CASH).removeSlot(slot);
        }

        @Override
        public void save(Character player) {
            saves++;
        }

        @Override
        public void broadcastMapTable(Character player) {
            broadcasts++;
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

    private static <T> T withItemProvider(Supplier<T> body) {
        try (var ii = mockStatic(server.ItemInformationProvider.class)) {
            ii.when(server.ItemInformationProvider::getInstance).thenReturn(mock(server.ItemInformationProvider.class));
            return body.get();
        }
    }

    @BeforeEach
    void setUp() {
        YamlConfig.config.server.COLOR_PRISM_ENABLED = true;
        ColorPrismStore.setSchemaPresent(true);
        client = mock(Client.class);
        chr = Character.getDefault(client);
        chr.setName("Dyer");
        org.mockito.Mockito.when(client.getPlayer()).thenReturn(chr);
        effects = new Effects();
        handler = new WeaponTintHandler(effects);
        chr.getInventory(InventoryType.CASH).addItemFromDB(new Item(PRISM, PRISM_SLOT, (short) 1));
    }

    @AfterEach
    void tearDown() {
        YamlConfig.config.server.COLOR_PRISM_ENABLED = wasEnabled;
        ColorPrismStore.setSchemaPresent(null);
    }

    private Equip wear(int itemId, short pos) {
        Equip e = withItemProvider(() -> new Equip(itemId, pos));
        chr.getInventory(pos < 0 ? InventoryType.EQUIPPED : InventoryType.EQUIP).addItemFromDB(e);
        return e;
    }

    private static InPacket apply(int invType, int invPos, int itemId, int hue, int chroma, int bright, int prismPos, int layer) {
        OutPacket o = new ByteBufOutPacket();
        o.writeByte(ColorPrismPackets.ACTION_APPLY);
        o.writeByte(invType);
        o.writeShort(invPos);
        o.writeInt(itemId);
        o.writeShort(hue);
        o.writeByte(chroma);
        o.writeByte(bright);
        o.writeShort(prismPos);
        o.writeByte(layer);
        return new ByteBufInPacket(Unpooled.wrappedBuffer(o.getBytes()));
    }

    private static InPacket restore(int invType, int invPos, int itemId, int prismPos, int layer) {
        OutPacket o = new ByteBufOutPacket();
        o.writeByte(ColorPrismPackets.ACTION_RESTORE);
        o.writeByte(invType);
        o.writeShort(invPos);
        o.writeInt(itemId);
        o.writeShort(prismPos);
        o.writeByte(layer);
        return new ByteBufInPacket(Unpooled.wrappedBuffer(o.getBytes()));
    }

    private static InPacket look(int action, int kind, int hue, int chroma, int bright, int prismPos) {
        OutPacket o = new ByteBufOutPacket();
        o.writeByte(action);
        o.writeByte(kind);
        if (action == ColorPrismPackets.ACTION_APPLY_LOOK) {
            o.writeShort(hue);
            o.writeByte(chroma);
            o.writeByte(bright);
        }
        o.writeShort(prismPos);
        return new ByteBufInPacket(Unpooled.wrappedBuffer(o.getBytes()));
    }

    /** The result code the client received (the first WEAPON_TINT_SYNC subtype-2 packet). */
    private int resultCode() {
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeast(1)).sendPacket(sent.capture());
        for (Packet p : sent.getAllValues()) {
            byte[] b = p.getBytes();
            if (b.length == 4 && b[0] == 0x2F && b[1] == 0x37 && b[2] == ColorPrismPackets.SUB_RESULT) {
                return b[3];
            }
        }
        throw new AssertionError("no result packet");
    }

    private boolean prismLeft() {
        return chr.getInventory(InventoryType.CASH).getItem(PRISM_SLOT) != null;
    }

    @Test
    void applyDyesAWornCashWeaponAndSpendsOnePrism() {
        Equip w = wear(CASH_WEAPON, (short) -111);
        handler.handlePacket(apply(1, -111, CASH_WEAPON, -200, 40, -40, PRISM_SLOT, 0), client);
        assertEquals(ColorPrismPackets.RESULT_OK, resultCode());
        assertEquals(-200, w.getTintHue());
        assertEquals(40, w.getTintChroma());
        assertEquals(-40, w.getTintBright());
        assertFalse(w.isFxTinted());
        assertEquals(List.of(PRISM_SLOT), effects.consumed);
        assertEquals(1, effects.saves);
        assertEquals(1, effects.broadcasts);
        assertFalse(prismLeft());
    }

    @Test
    void effectsLayerDyesTheEffectTintOnly() {
        Equip w = wear(CASH_WEAPON, (short) 5);    // in the Equip tab, not worn
        handler.handlePacket(apply(1, 5, CASH_WEAPON, -1, 0, 0, PRISM_SLOT, 1), client);
        assertEquals(ColorPrismPackets.RESULT_OK, resultCode());
        assertTrue(w.isFxTinted());
        assertFalse(w.isTinted());
    }

    @Test
    void nonCashEquipIsRejectedAndThePrismKept() {
        Equip sword = wear(PLAIN_SWORD, (short) -11);
        handler.handlePacket(apply(1, -11, PLAIN_SWORD, -10, 0, 0, PRISM_SLOT, 0), client);
        assertEquals(ColorPrismPackets.RESULT_NO_CASH_WEAPON, resultCode());
        assertFalse(sword.isTinted());
        assertTrue(effects.consumed.isEmpty());
        assertTrue(prismLeft());
        assertEquals(0, effects.saves);
    }

    @Test
    void wrongItemIdAtTheSlotIsRejected() {
        Equip w = wear(CASH_WEAPON, (short) -111);
        handler.handlePacket(apply(1, -111, CASH_WEAPON + 1, -10, 0, 0, PRISM_SLOT, 0), client);
        assertEquals(ColorPrismPackets.RESULT_NO_CASH_WEAPON, resultCode());
        assertFalse(w.isTinted());
        assertTrue(prismLeft());
    }

    @Test
    void emptySlotAndNonEquipInventoriesAreRejected() {
        handler.handlePacket(apply(1, -111, CASH_WEAPON, -10, 0, 0, PRISM_SLOT, 0), client);
        assertEquals(ColorPrismPackets.RESULT_NO_CASH_WEAPON, resultCode());
        // invType 5 (CASH) with a positive slot names the prism itself, which is not an equip
        handler.handlePacket(apply(5, PRISM_SLOT, PRISM, -10, 0, 0, PRISM_SLOT, 0), client);
        assertTrue(effects.consumed.isEmpty());
        assertTrue(prismLeft());
    }

    @Test
    void invalidTintValuesAreRejectedWithoutSpendingThePrism() {
        Equip w = wear(CASH_WEAPON, (short) -111);
        int[][] bad = {{0, 10, 10}, {120, 0, 0}, {-361, 0, 0}, {-10, 101, 0}, {-10, 0, -101}};
        for (int[] t : bad) {
            handler.handlePacket(apply(1, -111, CASH_WEAPON, t[0], t[1], t[2], PRISM_SLOT, 0), client);
        }
        assertEquals(ColorPrismPackets.RESULT_FAILED, resultCode());
        assertFalse(w.isTinted());
        assertTrue(effects.consumed.isEmpty());
        assertTrue(prismLeft());
    }

    @Test
    void noPrismMeansNoDye() {
        chr.getInventory(InventoryType.CASH).removeSlot(PRISM_SLOT);
        Equip w = wear(CASH_WEAPON, (short) -111);
        handler.handlePacket(apply(1, -111, CASH_WEAPON, -10, 0, 0, PRISM_SLOT, 0), client);
        assertEquals(ColorPrismPackets.RESULT_NO_ITEM, resultCode());
        assertFalse(w.isTinted());
    }

    @Test
    void thePrismIsFoundWhenTheHintedSlotIsStale() {
        Equip w = wear(CASH_WEAPON, (short) -111);
        handler.handlePacket(apply(1, -111, CASH_WEAPON, -10, 0, 0, 9, 0), client);
        assertEquals(ColorPrismPackets.RESULT_OK, resultCode());
        assertTrue(w.isTinted());
        assertEquals(List.of(PRISM_SLOT), effects.consumed);
    }

    @Test
    void aSecondConfirmCannotSpendTheSamePrism() {
        Equip w = wear(CASH_WEAPON, (short) -111);
        handler.handlePacket(apply(1, -111, CASH_WEAPON, -10, 0, 0, PRISM_SLOT, 0), client);
        handler.handlePacket(apply(1, -111, CASH_WEAPON, -20, 0, 0, PRISM_SLOT, 0), client);
        assertEquals(-10, w.getTintHue());
        assertEquals(1, effects.consumed.size());
    }

    @Test
    void restoreSpendsAPrismOnlyWhenThereIsATintToUndo() {
        Equip w = wear(CASH_WEAPON, (short) -111);
        handler.handlePacket(restore(1, -111, CASH_WEAPON, PRISM_SLOT, 0), client);
        assertEquals(ColorPrismPackets.RESULT_NOT_TINTED, resultCode());
        assertTrue(effects.consumed.isEmpty());

        w.setTint(-30, 0, 0);
        handler.handlePacket(restore(1, -111, CASH_WEAPON, PRISM_SLOT, 0), client);
        assertFalse(w.isTinted());
        assertEquals(List.of(PRISM_SLOT), effects.consumed);
    }

    @Test
    void hairAndFaceLooks() {
        handler.handlePacket(look(ColorPrismPackets.ACTION_APPLY_LOOK, ColorPrismPackets.TINT_KEY_HAIR, -90, 10, 20, PRISM_SLOT), client);
        assertEquals(ColorPrismPackets.RESULT_OK, resultCode());
        assertEquals(-90, chr.getHairTintHue());
        assertFalse(chr.isFaceTinted());
        assertEquals(1, effects.consumed.size());

        chr.getInventory(InventoryType.CASH).addItemFromDB(new Item(PRISM, PRISM_SLOT, (short) 1));
        handler.handlePacket(look(ColorPrismPackets.ACTION_RESTORE_LOOK, ColorPrismPackets.TINT_KEY_FACE, 0, 0, 0, PRISM_SLOT), client);
        assertEquals(1, effects.consumed.size());   // eyes were not tinted: nothing spent
        handler.handlePacket(look(ColorPrismPackets.ACTION_RESTORE_LOOK, ColorPrismPackets.TINT_KEY_HAIR, 0, 0, 0, PRISM_SLOT), client);
        assertFalse(chr.isHairTinted());
        assertEquals(2, effects.consumed.size());

        chr.getInventory(InventoryType.CASH).addItemFromDB(new Item(PRISM, PRISM_SLOT, (short) 1));
        handler.handlePacket(look(ColorPrismPackets.ACTION_APPLY_LOOK, 7, -90, 10, 20, PRISM_SLOT), client);   // unknown kind
        handler.handlePacket(look(ColorPrismPackets.ACTION_APPLY_LOOK, ColorPrismPackets.TINT_KEY_FACE, 90, 0, 0, PRISM_SLOT), client);
        assertFalse(chr.isFaceTinted());
        assertEquals(2, effects.consumed.size());
    }

    @Test
    void offOrBotMeansSilence() {
        wear(CASH_WEAPON, (short) -111);
        YamlConfig.config.server.COLOR_PRISM_ENABLED = false;
        handler.handlePacket(apply(1, -111, CASH_WEAPON, -10, 0, 0, PRISM_SLOT, 0), client);
        verify(client, never()).sendPacket(any());

        YamlConfig.config.server.COLOR_PRISM_ENABLED = true;
        ColorPrismStore.setSchemaPresent(false);    // flag on, migration missing
        handler.handlePacket(apply(1, -111, CASH_WEAPON, -10, 0, 0, PRISM_SLOT, 0), client);
        verify(client, never()).sendPacket(any());
        assertTrue(effects.consumed.isEmpty());

        ColorPrismStore.setSchemaPresent(true);
        BotClient botClient = new BotClient(0, 0);
        Character bot = Character.getDefault(botClient);
        Client botC = mock(Client.class);
        org.mockito.Mockito.when(botC.getPlayer()).thenReturn(bot);
        handler.handlePacket(apply(1, -111, CASH_WEAPON, -10, 0, 0, PRISM_SLOT, 0), botC);
        verify(botC, never()).sendPacket(any());
    }

    @Test
    void snapshotRequestAnswersWithTheSnapshot() {
        OutPacket o = new ByteBufOutPacket();
        o.writeByte(ColorPrismPackets.ACTION_REQUEST_SNAPSHOT);
        handler.handlePacket(new ByteBufInPacket(Unpooled.wrappedBuffer(o.getBytes())), client);
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client).sendPacket(sent.capture());
        byte[] b = sent.getValue().getBytes();
        assertEquals(0x2F, b[0]);
        assertEquals(0x37, b[1]);
        assertEquals(ColorPrismPackets.SUB_SNAPSHOT, b[2]);
        assertEquals(0, b[3]);
        assertEquals(4, b.length);
    }
}
