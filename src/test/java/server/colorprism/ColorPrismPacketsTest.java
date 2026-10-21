package server.colorprism;

import client.BotClient;
import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.InventoryType;
import net.opcodes.RecvOpcode;
import net.opcodes.SendOpcode;
import net.packet.Packet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * The server side of slums.dll weapontint.cpp's WeaponTint_HandleSync, byte for byte: LP 0x372F, then subtype
 * 1 snapshot {count(1), count x [key(4) hue(2) chroma(1) bright(1)]}, 2 result {code(1)}, 3 table
 * {players(1), players x [name(u16 len + bytes), count(1), entries]}. Little-endian, hue signed.
 */
class ColorPrismPacketsTest {
    private static final int WEAPON = 1702000;
    private static final int CAPE = 1102000;

    @BeforeAll
    static void init() throws Exception {
        TintTest.initItemProvider();
    }

    private static Character player(String name) {
        Character chr = Character.getDefault(mock(Client.class));
        chr.setName(name);
        return chr;
    }

    private static Equip wear(Character chr, InventoryType inv, int itemId, int pos) {
        Equip e = TintTest.equip(itemId, pos);
        chr.getInventory(inv).addItemFromDB(e);
        return e;
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
    }

    private static byte[] get(Packet p) {
        return p.getBytes();
    }

    @Test
    void opcodesAreTheReservedWeaponTintPair() {
        assertEquals(0x372E, RecvOpcode.WEAPON_TINT_ACTION.getValue());
        assertEquals(0x372F, SendOpcode.WEAPON_TINT_SYNC.getValue());
    }

    @Test
    void constantsMatchTheClient() {
        // weapontint.cpp kAction_* / kResp_* and weapontint.h WeaponTintResult, kTintKey_*, kTintLayer_*
        assertEquals(0, ColorPrismPackets.ACTION_REQUEST_SNAPSHOT);
        assertEquals(1, ColorPrismPackets.ACTION_APPLY);
        assertEquals(2, ColorPrismPackets.ACTION_RESTORE);
        assertEquals(3, ColorPrismPackets.ACTION_APPLY_LOOK);
        assertEquals(4, ColorPrismPackets.ACTION_RESTORE_LOOK);
        assertEquals(1, ColorPrismPackets.SUB_SNAPSHOT);
        assertEquals(2, ColorPrismPackets.SUB_RESULT);
        assertEquals(3, ColorPrismPackets.SUB_MAP_TABLE);
        assertEquals(1, ColorPrismPackets.RESULT_OK);
        assertEquals(2, ColorPrismPackets.RESULT_NO_CASH_WEAPON);
        assertEquals(3, ColorPrismPackets.RESULT_NO_ITEM);
        assertEquals(4, ColorPrismPackets.RESULT_NOT_TINTED);
        assertEquals(5, ColorPrismPackets.RESULT_FAILED);
        assertEquals(1, ColorPrismPackets.TINT_KEY_HAIR);
        assertEquals(2, ColorPrismPackets.TINT_KEY_FACE);
        assertEquals(10_000_000, ColorPrismPackets.EFFECT_KEY_BIAS);
        assertEquals(0, ColorPrismPackets.LAYER_BODY);
        assertEquals(1, ColorPrismPackets.LAYER_EFFECTS);
    }

    @Test
    void resultIsSubtypeTwoAndOneCodeByte() {
        assertArrayEquals(bytes(0x2F, 0x37, 2, 4), get(ColorPrismPackets.result(ColorPrismPackets.RESULT_NOT_TINTED)));
    }

    @Test
    void emptySnapshotIsJustACountOfZero() {
        assertArrayEquals(bytes(0x2F, 0x37, 1, 0), get(ColorPrismPackets.snapshot(player("Plain"))));
    }

    @Test
    void snapshotCarriesBodyEffectHairAndFaceKeys() {
        Character chr = player("Dyer");
        Equip w = wear(chr, InventoryType.EQUIPPED, WEAPON, -111);
        w.setTint(-1, -100, 100);             // hue 0 degrees
        w.setFxTint(-360, 5, -5);             // hue 359 degrees on the effect layer
        chr.setHairTint(-181, 0, 0);
        chr.setFaceTint(-2, 1, 1);

        byte[] expected = bytes(0x2F, 0x37, 1, 4,
                0x70, 0xF8, 0x19, 0x00, 0xFF, 0xFF, 0x9C, 0x64,           // 1702000, -1, -100, 100
                0xF0, 0x8E, 0xB2, 0x00, 0x98, 0xFE, 0x05, 0xFB,           // 11702000, -360, 5, -5
                0x01, 0x00, 0x00, 0x00, 0x4B, 0xFF, 0x00, 0x00,           // hair, -181
                0x02, 0x00, 0x00, 0x00, 0xFE, 0xFF, 0x01, 0x01);          // face, -2, 1, 1
        assertArrayEquals(expected, get(ColorPrismPackets.snapshot(chr)));
    }

    @Test
    void snapshotIncludesTheEquipTabButPrefersTheWornCopy() {
        Character chr = player("Dyer");
        wear(chr, InventoryType.EQUIP, CAPE, 3).setTint(-50, 0, 0);
        wear(chr, InventoryType.EQUIPPED, WEAPON, -111);                  // untinted, worn
        wear(chr, InventoryType.EQUIP, WEAPON, 4).setTint(-60, 0, 0);     // tinted spare of the same id
        List<ColorPrismPackets.Entry> own = ColorPrismPackets.ownEntries(chr);
        assertEquals(List.of(new ColorPrismPackets.Entry(CAPE, (short) -50, (byte) 0, (byte) 0)), own);
        // other players only see what is worn
        assertEquals(List.of(), ColorPrismPackets.visibleEntries(chr));
    }

    @Test
    void tableListsTintedRealPlayersByName() {
        Character a = player("Alice");
        wear(a, InventoryType.EQUIPPED, WEAPON, -111).setTint(-10, 20, 30);
        Character plain = player("Bob");
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setName("Botty");
        bot.setHairTint(-5, 0, 0);
        Character longName = player("FourteenChars!");
        longName.setHairTint(-5, 0, 0);
        Character c = player("Cy");
        c.setFaceTint(-3, -1, -2);

        byte[] expected = bytes(0x2F, 0x37, 3, 2,
                5, 0, 'A', 'l', 'i', 'c', 'e', 1,
                0x70, 0xF8, 0x19, 0x00, 0xF6, 0xFF, 20, 30,
                2, 0, 'C', 'y', 1,
                0x02, 0x00, 0x00, 0x00, 0xFD, 0xFF, 0xFF, 0xFE);
        assertArrayEquals(expected, get(ColorPrismPackets.mapTable(List.of(a, plain, bot, longName, c))));
    }

    /** The same bytes as the client's tests/weapontint_proto_test.cpp (slums.dll addon-colorprism); change both. */
    @Test
    void sharedGoldenWithTheClientTest() {
        Character chr = player("Pinky");
        Equip w = wear(chr, InventoryType.EQUIPPED, WEAPON, -111);
        w.setTint(-121, 50, -20);
        w.setFxTint(-1, 0, 0);
        chr.setHairTint(-360, -100, 100);
        assertArrayEquals(java.util.HexFormat.of().parseHex("2f37010370f8190087ff32ecf08eb200ffff00000100000098fe9c64"),
                get(ColorPrismPackets.snapshot(chr)));
        assertArrayEquals(java.util.HexFormat.of().parseHex("2f370204"),
                get(ColorPrismPackets.result(ColorPrismPackets.RESULT_NOT_TINTED)));
    }

    @Test
    void emptyTableStillReplacesTheClientsTable() {
        assertArrayEquals(bytes(0x2F, 0x37, 3, 0), get(ColorPrismPackets.mapTable(List.of(player("Bob")))));
    }
}
