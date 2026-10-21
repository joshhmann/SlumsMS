package server.setitem;

import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * LP {@code SET_ITEM_SYNC} (0x3727), read by the client's setitem.cpp. A leading type byte picks the shape:
 *
 * <pre>
 * 1 catalog      byte flags (1 = first part: clear what you have, 2 = last part: catalog complete)
 *                short sets, per set: int id, str name, short complete, short items (int id, str name)...,
 *                short tiers (short pieces, short stats (str key, int value)...)...
 * 2 pieceCounts  short sets, per set: int id, short worn (int item)...
 *                then int str, dex, int, luk, maxHp, maxMp: the folded set bonus the Ability window adds
 * </pre>
 *
 * The catalog goes out in parts of at most {@link #CATALOG_PART_BYTES}, since a v83 packet is limited in size.
 */
public final class SetItemPackets {

    static final byte TYPE_CATALOG = 1;
    static final byte TYPE_PIECE_COUNTS = 2;
    static final byte FLAG_FIRST = 1;
    static final byte FLAG_LAST = 2;
    static final int CATALOG_PART_BYTES = 16_000;

    private SetItemPackets() {
    }

    /**
     * The catalog for one job. Weapons another class uses are left out (a warrior's list does not show wands), unless
     * worn; beginners see every weapon. An empty catalog is still one (empty, first and last) part.
     */
    public static List<Packet> catalog(SetItemCatalog catalog, int jobId, Collection<Integer> wornItemIds,
                                       IntFunction<String> itemName) {
        List<List<byte[]>> parts = new ArrayList<>();
        List<byte[]> current = new ArrayList<>();
        int size = 0;
        for (ItemSetInfo set : catalog.sets()) {
            byte[] entry = encodeSet(set, jobId, wornItemIds, itemName);
            if (!current.isEmpty() && size + entry.length > CATALOG_PART_BYTES) {
                parts.add(current);
                current = new ArrayList<>();
                size = 0;
            }
            current.add(entry);
            size += entry.length;
        }
        parts.add(current);

        List<Packet> packets = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            OutPacket p = OutPacket.create(SendOpcode.SET_ITEM_SYNC);
            p.writeByte(TYPE_CATALOG);
            p.writeByte((i == 0 ? FLAG_FIRST : 0) | (i == parts.size() - 1 ? FLAG_LAST : 0));
            p.writeShort(parts.get(i).size());
            parts.get(i).forEach(p::writeBytes);
            packets.add(p);
        }
        return packets;
    }

    private static byte[] encodeSet(ItemSetInfo set, int jobId, Collection<Integer> worn, IntFunction<String> itemName) {
        Bytes b = new Bytes();
        b.int32(set.id());
        b.str(set.name());
        b.int16(set.completeCount());
        List<Integer> shown = new ArrayList<>();
        for (int item : set.itemIds()) {
            if (worn.contains(item) || canUseWeapon(jobId, item)) {
                shown.add(item);
            }
        }
        b.int16(shown.size());
        for (int item : shown) {
            b.int32(item);
            String name = itemName.apply(item);
            b.str(name != null ? name : "Item " + item);
        }
        b.int16(set.tiers().size());
        for (Map.Entry<Integer, Map<String, Integer>> tier : set.tiers().entrySet()) {
            b.int16(tier.getKey());
            b.int16(tier.getValue().size());
            tier.getValue().forEach((key, value) -> {
                b.str(key);
                b.int32(value);
            });
        }
        return b.toByteArray();
    }

    /** Job branch of a weapon by item category (1 warrior, 2 magician, 3 bowman, 4 thief, 5 pirate); 0 = not a weapon. */
    static int weaponBranch(int itemId) {
        return switch ((itemId / 10000) % 100) {
            case 30, 31, 32, 40, 41, 42, 43, 44 -> 1;
            case 37, 38 -> 2;
            case 45, 46 -> 3;
            case 33, 47 -> 4;
            case 48, 49 -> 5;
            default -> 0;
        };
    }

    /** True for armour and accessories, and for weapons of the job's branch (Cygnus and Legends included). */
    static boolean canUseWeapon(int jobId, int itemId) {
        int weapon = weaponBranch(itemId);
        int branch = (jobId / 100) % 10;
        return weapon == 0 || branch == 0 || weapon == branch;
    }

    /** Which pieces of each worn set the player has on, then the folded bonus for the Ability window. */
    public static Packet pieceCounts(Map<Integer, List<Integer>> wornPieces, SetBonus bonus) {
        OutPacket p = OutPacket.create(SendOpcode.SET_ITEM_SYNC);
        p.writeByte(TYPE_PIECE_COUNTS);
        p.writeShort(wornPieces.size());
        wornPieces.forEach((setId, items) -> {
            p.writeInt(setId);
            p.writeShort(items.size());
            items.forEach(p::writeInt);
        });
        p.writeInt(bonus.str());
        p.writeInt(bonus.dex());
        p.writeInt(bonus.int_());
        p.writeInt(bonus.luk());
        p.writeInt(bonus.maxHp());
        p.writeInt(bonus.maxMp());
        return p;
    }

    /** Little-endian writer with the same string encoding as {@link OutPacket#writeString}. */
    private static final class Bytes {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        void int16(int v) {
            out.write(v & 0xFF);
            out.write((v >>> 8) & 0xFF);
        }

        void int32(int v) {
            int16(v);
            int16(v >>> 16);
        }

        void str(String s) {
            byte[] bytes = s.getBytes(constants.string.CharsetConstants.CHARSET);
            int16(bytes.length);
            out.write(bytes, 0, bytes.length);
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
