package server.setitem;

import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Decodes the packets the way the client's setitem.cpp does, so the two sides keep one layout. */
class SetItemPacketsTest {

    private static InPacket in(Packet p) {
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(p.getBytes()));
        assertEquals(SendOpcode.SET_ITEM_SYNC.getValue(), in.readShort());
        assertEquals(0x3727, SendOpcode.SET_ITEM_SYNC.getValue());
        return in;
    }

    record Decoded(int id, String name, int complete, List<Integer> items, List<String> names, Map<Integer, Map<String, Integer>> tiers) {
    }

    private static List<Decoded> decodeCatalog(List<Packet> packets) {
        List<Decoded> out = new ArrayList<>();
        for (int i = 0; i < packets.size(); i++) {
            InPacket in = in(packets.get(i));
            assertEquals(SetItemPackets.TYPE_CATALOG, in.readByte());
            int flags = in.readByte();
            assertEquals(i == 0, (flags & SetItemPackets.FLAG_FIRST) != 0);
            assertEquals(i == packets.size() - 1, (flags & SetItemPackets.FLAG_LAST) != 0);
            int sets = in.readShort();
            for (int s = 0; s < sets; s++) {
                int id = in.readInt();
                String name = in.readString();
                int complete = in.readShort();
                int n = in.readShort();
                List<Integer> items = new ArrayList<>();
                List<String> names = new ArrayList<>();
                for (int k = 0; k < n; k++) {
                    items.add(in.readInt());
                    names.add(in.readString());
                }
                Map<Integer, Map<String, Integer>> tiers = new LinkedHashMap<>();
                int t = in.readShort();
                for (int k = 0; k < t; k++) {
                    int pieces = in.readShort();
                    Map<String, Integer> stats = new LinkedHashMap<>();
                    int st = in.readShort();
                    for (int j = 0; j < st; j++) {
                        stats.put(in.readString(), in.readInt());
                    }
                    tiers.put(pieces, stats);
                }
                out.add(new Decoded(id, name, complete, items, names, tiers));
            }
            assertEquals(0, in.available(), "no trailing bytes");
        }
        return out;
    }

    @Test
    void catalogCarriesSetsAndHidesOtherClassesWeapons() throws Exception {
        SetItemCatalog c = SetItemCatalogTest.marx();
        // A Hermit (412): the sword and halberd are warrior weapons, so only armour shows...
        List<Decoded> thief = decodeCatalog(SetItemPackets.catalog(c, 412, List.of(), id -> "Item" + id));
        Decoded marx = thief.stream().filter(d -> d.id() == 6).findFirst().orElseThrow();
        assertEquals("Marx Von Leon Set", marx.name());
        assertEquals(6, marx.complete());
        assertEquals(List.of(1003154, 1052299, 1082285, 1072471, 1102262), marx.items());
        assertEquals("Item1003154", marx.names().get(0));
        assertEquals(Map.of("incAllStat", 4), marx.tiers().get(4));
        assertEquals(List.of(4, 5, 6), new ArrayList<>(marx.tiers().keySet()));
        // ...unless the thief is wearing one.
        Decoded worn = decodeCatalog(SetItemPackets.catalog(c, 412, List.of(1402091), id -> null)).get(0);
        assertTrue(worn.items().contains(1402091));
        assertEquals("Item 1402091", worn.names().get(worn.items().indexOf(1402091)));
        // A Dawn Warrior (1110) and an Aran (2110) see both warrior weapons; a beginner sees everything.
        assertEquals(7, decodeCatalog(SetItemPackets.catalog(c, 1110, List.of(), id -> "x")).get(0).items().size());
        assertEquals(7, decodeCatalog(SetItemPackets.catalog(c, 2110, List.of(), id -> "x")).get(0).items().size());
        assertEquals(7, decodeCatalog(SetItemPackets.catalog(c, 0, List.of(), id -> "x")).get(0).items().size());
    }

    /** The same bytes are checked by the client's tests/setitem_proto_test.cpp: change both together. */
    static final String GOLDEN_CATALOG = "273701030100020000000b00476f64646573732053657402000200475d10000d00476f64646573732053686f6573"
            + "788310001100476f646465737320577269737462616e640100020002000a00696e63416c6c53746174030000000600696e634d48502c010000";
    static final String GOLDEN_PIECES = "2737020100020000000200475d100078831000030000000300000003000000030000002c01000000000000";

    @Test
    void goldenBytesSharedWithTheClient() throws Exception {
        SetItemCatalog c = SetItemCatalogTest.parse("""
                sets:
                  - id: 2
                    name: "Goddess Set"
                    complete: 2
                    items: [1072455, 1082232]
                    tiers:
                      2: {incAllStat: 3, incMHP: 300}
                """, new ArrayList<>());
        List<Packet> catalog = SetItemPackets.catalog(c, 0, List.of(), id -> id == 1072455 ? "Goddess Shoes" : "Goddess Wristband");
        assertEquals(1, catalog.size());
        assertEquals(GOLDEN_CATALOG, java.util.HexFormat.of().formatHex(catalog.get(0).getBytes()));
        List<Integer> worn = List.of(1072455, 1082232);
        assertEquals(GOLDEN_PIECES, java.util.HexFormat.of().formatHex(
                SetItemPackets.pieceCounts(c.wornPieces(worn), c.bonusFor(worn)).getBytes()));
    }

    @Test
    void weaponBranches() {
        assertEquals(1, SetItemPackets.weaponBranch(1302000));
        assertEquals(4, SetItemPackets.weaponBranch(1332000));
        assertEquals(2, SetItemPackets.weaponBranch(1372000));
        assertEquals(3, SetItemPackets.weaponBranch(1462000));
        assertEquals(4, SetItemPackets.weaponBranch(1472000));
        assertEquals(5, SetItemPackets.weaponBranch(1492000));
        assertEquals(0, SetItemPackets.weaponBranch(1003154));
        assertFalse(SetItemPackets.canUseWeapon(2210, 1302000), "Evan is a magician");
        assertTrue(SetItemPackets.canUseWeapon(2210, 1372000));
    }

    @Test
    void theShippedCatalogSplitsIntoPartsUnderTheLimit() throws Exception {
        SetItemCatalog c = SetItemCatalog.parse(new com.esotericsoftware.yamlbeans.YamlReader(
                java.nio.file.Files.newBufferedReader(soloMapling.DataFiles.path(SetItemCatalog.DATA_PATH))).read(), id -> true, new ArrayList<>());
        List<Packet> packets = SetItemPackets.catalog(c, 0, List.of(), id -> "A fairly long item name here");
        assertTrue(packets.size() > 1);
        for (Packet p : packets) {
            assertTrue(p.getBytes().length < SetItemPackets.CATALOG_PART_BYTES + 2_000, "part of " + p.getBytes().length);
        }
        assertEquals(c.sets().size(), decodeCatalog(packets).size());
    }

    @Test
    void emptyCatalogIsOneFirstAndLastPart() {
        List<Packet> packets = SetItemPackets.catalog(SetItemCatalog.EMPTY, 100, List.of(), id -> "x");
        assertEquals(1, packets.size());
        assertEquals(List.of(), decodeCatalog(packets));
    }

    @Test
    void pieceCountsThenTheSixBonusInts() throws Exception {
        SetItemCatalog c = SetItemCatalogTest.marx();
        List<Integer> worn = List.of(1003154, 1052299, 1082285, 1072471, 1402091, 1072455);
        InPacket in = in(SetItemPackets.pieceCounts(c.wornPieces(worn), c.bonusFor(worn)));
        assertEquals(SetItemPackets.TYPE_PIECE_COUNTS, in.readByte());
        assertEquals(2, in.readShort());
        assertEquals(6, in.readInt());
        assertEquals(5, in.readShort());
        for (int item : List.of(1003154, 1052299, 1082285, 1072471, 1402091)) {
            assertEquals(item, in.readInt());
        }
        assertEquals(2, in.readInt());
        assertEquals(1, in.readShort());
        assertEquals(1072455, in.readInt());
        // str, dex, int, luk (AllStat 4 + 6), maxHp, maxMp (Goddess needs 2 pieces: none).
        for (int v : new int[]{10, 10, 10, 10, 0, 0}) {
            assertEquals(v, in.readInt());
        }
        assertEquals(0, in.available());
    }
}
